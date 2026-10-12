package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.suppression.FormedSuppressionPoolRegistry;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** 20 kFE buffer; one nitrogen canister provides 72,000 running ticks. */
public final class ContainmentEquipmentBlockEntity extends BlockEntity {
    private static final int CAPACITY_FE = 20_000, RUN_FE = 4, CANISTER_TICKS = 72_000;
    private int energyFe, nitrogenTicks;
    private boolean computerDemand;
    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int amount, boolean simulate) {
            int accepted = Math.min(Math.max(0, amount), CAPACITY_FE - energyFe);
            if (!simulate && accepted > 0) { energyFe += accepted; setChanged(); }
            return accepted;
        }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return energyFe; }
        @Override public int getMaxEnergyStored() { return CAPACITY_FE; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    };

    public ContainmentEquipmentBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONTAINMENT_EQUIPMENT.get(), pos, state);
    }
    public boolean isVent() { return ((ContainmentEquipmentBlock) getBlockState().getBlock()).isVent(); }
    public IEnergyStorage energy() { return energy; }
    public int nitrogenTicks() { return nitrogenTicks; }
    public boolean demand() { return computerDemand; }
    public void setDemand(boolean demand) { computerDemand = demand; setChanged(); }
    public boolean refill() {
        if (isVent() || nitrogenTicks > CANISTER_TICKS - 1) return false;
        nitrogenTicks = Math.min(CANISTER_TICKS, nitrogenTicks + CANISTER_TICKS);
        setChanged();
        return true;
    }
    public boolean supplied() { return energyFe >= RUN_FE && (isVent() || nitrogenTicks > 0); }
    public boolean outletClear(ContainmentControllerBlockEntity room) {
        if (level == null || !isVent()) return false;
        var facing = getBlockState().getValue(ContainmentEquipmentBlock.FACING);
        BlockPos next = worldPosition.relative(facing);
        // A straight stack may cross the wall; the final discharge must be outside.
        for (int i = 0; i < 32 && level.isLoaded(next); i++, next = next.relative(facing)) {
            var state = level.getBlockState(next);
            if (state.is(BwrBlocks.PRESSURISED_TUBE.get())) continue;
            if (state.getBlock() instanceof ContainmentPenetrationBlock penetration
                    && penetration.service() == ContainmentPenetrationBlock.Service.STEAM
                    && !state.getValue(ContainmentPenetrationBlock.RUPTURED)
                    && ContainmentPenetrationBlock.axial(state, facing)
                    && level.getBlockEntity(next) instanceof ContainmentPenetrationBlockEntity valve
                    && valve.position() > 0) continue;
            return state.isAir() && !room.encloses(next);
        }
        return false;
    }
    public SuppressionPoolBlockEntity mountedPool() {
        if (level == null || !isVent()) return null;
        for (SuppressionPoolBlockEntity pool : FormedSuppressionPoolRegistry.controllers(level)) {
            if (!pool.isFormed()) continue;
            // Both concrete basins and enclosed tanks expose this surveyed boundary.
            if ((pool.isConcreteBasin() || pool.isEnclosedTank())
                    && pool.touchesConcreteBounds(worldPosition)) return pool;
        }
        return null;
    }
    public boolean ready(ContainmentControllerBlockEntity room) {
        if (level == null || room == null || !supplied() || !room.encloses(worldPosition)) return false;
        if (!isVent()) return true;
        SuppressionPoolBlockEntity pool = mountedPool();
        return pool != null && room.linkedPools().contains(pool.getBlockPos()) && outletClear(room);
    }
    public boolean active(ContainmentControllerBlockEntity room) {
        return ready(room) && (computerDemand || isVent() && level.hasNeighborSignal(worldPosition));
    }
    public void spendTick() {
        if (energyFe < RUN_FE) return;
        energyFe -= RUN_FE;
        if (!isVent() && nitrogenTicks > 0) nitrogenTicks--;
        setChanged();
    }
    @Override public void onLoad() { super.onLoad(); if (level instanceof ServerLevel) ContainmentEquipmentRegistry.register(this); }
    @Override public void setRemoved() { ContainmentEquipmentRegistry.unregister(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { ContainmentEquipmentRegistry.unregister(this); super.onChunkUnloaded(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("EnergyFe", energyFe); tag.putInt("NitrogenTicks", nitrogenTicks);
        tag.putBoolean("ComputerDemand", computerDemand);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energyFe = Math.clamp(tag.getInt("EnergyFe"), 0, CAPACITY_FE);
        nitrogenTicks = Math.clamp(tag.getInt("NitrogenTicks"), 0, CANISTER_TICKS);
        computerDemand = tag.getBoolean("ComputerDemand");
    }
}
