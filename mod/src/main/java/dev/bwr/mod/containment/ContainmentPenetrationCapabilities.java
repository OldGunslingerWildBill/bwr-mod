package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Live, inventory-free capability bridge. It never retains a neighbor across edits or unloads. */
public final class ContainmentPenetrationCapabilities {
    private ContainmentPenetrationCapabilities() {}
    private static final ThreadLocal<Set<BlockPos>> RESOLVING = ThreadLocal.withInitial(HashSet::new);

    public static void register(RegisterCapabilitiesEvent event) {
        for (var equipment : new ContainmentEquipmentBlock[] {
                BwrBlocks.CONTAINMENT_NITROGEN_SUPPLY.get(), BwrBlocks.CONTAINMENT_VENT_VALVE.get() })
            event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, unused, side) ->
                    level.getBlockEntity(pos) instanceof ContainmentEquipmentBlockEntity be ? be.energy() : null, equipment);
        for (var vault : new ContainmentVaultDoorBlock[] {
                BwrBlocks.CONTAINMENT_VAULT_DOOR.get(), BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get() }) {
            event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, unused, side) -> {
                var owner = vault.owner(level, pos, state);
                return owner == null ? null : owner.energy();
            }, vault);
        }
        for (var entry : new net.minecraft.world.level.block.Block[] {
                BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get(), BwrBlocks.CONTAINMENT_WATER_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_PASS_THROUGH.get(), BwrBlocks.CONTAINMENT_POWER_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_DATA_PENETRATION.get() }) {
            var block = (ContainmentPenetrationBlock) entry;
            event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, unused, side) -> {
                if (side == null || state.getValue(ContainmentPenetrationBlock.RUPTURED)) return null;
                if (block.hasValve() && !ContainmentPenetrationBlock.axial(state, side)) {
                    var be = level.getBlockEntity(pos);
                    return be instanceof ContainmentPenetrationBlockEntity valve ? valve.energy() : null;
                }
                if (block.service() != ContainmentPenetrationBlock.Service.POWER
                        && block.service() != ContainmentPenetrationBlock.Service.PASS_THROUGH) return null;
                if (!ContainmentPenetrationBlock.axial(state, side)) return null;
                return energy(level, pos.immutable(), block, side);
            }, block);
            if (block.service() == ContainmentPenetrationBlock.Service.PASS_THROUGH) {
                event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, unused, side) ->
                        side != null && ContainmentPenetrationBlock.axial(state, side)
                                ? fluid(level, pos.immutable(), block, side) : null, block);
                event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, unused, side) ->
                        side != null && ContainmentPenetrationBlock.axial(state, side)
                                ? item(level, pos.immutable(), block, side) : null, block);
            }
        }
    }

    private static <T> T opposite(Level level, BlockPos pos, ContainmentPenetrationBlock block,
                                  Direction side, Function<BlockPos, T> lookup) {
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (!state.is(block) || state.getValue(ContainmentPenetrationBlock.RUPTURED)
                || !ContainmentPenetrationBlock.axial(state, side)) return null;
        if (block.hasValve()) {
            if (!(level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity valve)
                    || valve.position() <= 0.0) return null;
        }
        BlockPos next = pos.relative(side.getOpposite());
        if (!level.isLoaded(next)) return null;
        // This bridge is for foreign capabilities. BWR coolant uses its own
        // dedicated steam/water penetrations and never a foreign handler.
        BlockState neighbor = level.getBlockState(next);
        if (neighbor.getBlock() instanceof ContainmentPenetrationBlock) return null;
        if (neighbor.is(BwrBlocks.PRESSURISED_TUBE.get())
                || neighbor.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())) return null;
        Set<BlockPos> resolving = RESOLVING.get();
        if (!resolving.add(pos)) return null;
        try { return lookup.apply(next); }
        finally { resolving.remove(pos); }
    }

    private static IEnergyStorage energy(Level level, BlockPos pos, ContainmentPenetrationBlock block, Direction side) {
        return new IEnergyStorage() {
            private IEnergyStorage current() {
                return opposite(level, pos, block, side,
                        next -> level.getCapability(Capabilities.EnergyStorage.BLOCK, next, side));
            }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                IEnergyStorage e = current();
                if (e == null) return 0;
                ContainmentPenetrationBlockEntity be = level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity found ? found : null;
                int allowed = block.service() == ContainmentPenetrationBlock.Service.POWER && be != null
                        ? be.feAllowance(amount) : amount;
                int moved = e.receiveEnergy(allowed, simulate);
                if (!simulate && block.service() == ContainmentPenetrationBlock.Service.POWER && be != null) be.recordFe(moved);
                return moved;
            }
            @Override public int extractEnergy(int amount, boolean simulate) {
                IEnergyStorage e = current();
                if (e == null) return 0;
                ContainmentPenetrationBlockEntity be = level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity found ? found : null;
                int allowed = block.service() == ContainmentPenetrationBlock.Service.POWER && be != null
                        ? be.feAllowance(amount) : amount;
                int moved = e.extractEnergy(allowed, simulate);
                if (!simulate && block.service() == ContainmentPenetrationBlock.Service.POWER && be != null) be.recordFe(moved);
                return moved;
            }
            @Override public int getEnergyStored() { IEnergyStorage e = current(); return e == null ? 0 : e.getEnergyStored(); }
            @Override public int getMaxEnergyStored() { IEnergyStorage e = current(); return e == null ? 0 : e.getMaxEnergyStored(); }
            @Override public boolean canExtract() { IEnergyStorage e = current(); return e != null && e.canExtract(); }
            @Override public boolean canReceive() { IEnergyStorage e = current(); return e != null && e.canReceive(); }
        };
    }

    private static IFluidHandler fluid(Level level, BlockPos pos, ContainmentPenetrationBlock block, Direction side) {
        return new IFluidHandler() {
            private IFluidHandler current() {
                return opposite(level, pos, block, side,
                        next -> level.getCapability(Capabilities.FluidHandler.BLOCK, next, side));
            }
            @Override public int getTanks() { IFluidHandler f = current(); return f == null ? 0 : f.getTanks(); }
            @Override public FluidStack getFluidInTank(int tank) { IFluidHandler f = current(); return f == null ? FluidStack.EMPTY : f.getFluidInTank(tank); }
            @Override public int getTankCapacity(int tank) { IFluidHandler f = current(); return f == null ? 0 : f.getTankCapacity(tank); }
            @Override public boolean isFluidValid(int tank, FluidStack stack) { IFluidHandler f = current(); return f != null && f.isFluidValid(tank, stack); }
            @Override public int fill(FluidStack stack, FluidAction action) { IFluidHandler f = current(); return f == null ? 0 : f.fill(stack, action); }
            @Override public FluidStack drain(FluidStack stack, FluidAction action) { IFluidHandler f = current(); return f == null ? FluidStack.EMPTY : f.drain(stack, action); }
            @Override public FluidStack drain(int amount, FluidAction action) { IFluidHandler f = current(); return f == null ? FluidStack.EMPTY : f.drain(amount, action); }
        };
    }

    private static IItemHandler item(Level level, BlockPos pos, ContainmentPenetrationBlock block, Direction side) {
        return new IItemHandler() {
            private IItemHandler current() {
                return opposite(level, pos, block, side,
                        next -> level.getCapability(Capabilities.ItemHandler.BLOCK, next, side));
            }
            @Override public int getSlots() { IItemHandler i = current(); return i == null ? 0 : i.getSlots(); }
            @Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
                IItemHandler i = current(); return i == null ? net.minecraft.world.item.ItemStack.EMPTY : i.getStackInSlot(slot);
            }
            @Override public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
                IItemHandler i = current(); return i == null ? stack : i.insertItem(slot, stack, simulate);
            }
            @Override public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                IItemHandler i = current(); return i == null ? net.minecraft.world.item.ItemStack.EMPTY : i.extractItem(slot, amount, simulate);
            }
            @Override public int getSlotLimit(int slot) { IItemHandler i = current(); return i == null ? 0 : i.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
                IItemHandler i = current(); return i != null && i.isItemValid(slot, stack);
            }
        };
    }
}
