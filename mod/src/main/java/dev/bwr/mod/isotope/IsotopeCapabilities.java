package dev.bwr.mod.isotope;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The separator's five item slots and its energy buffer, the RTG's energy output (extract-only, every face), and the
 * Large RTG's: every one of its twelve cells forwards energy (extract-only) and items (insert-only) to the one controller,
 * live (nothing keeps a controller across an unload). Plain NeoForge, nothing from Mekanism or CC.
 */
public final class IsotopeCapabilities {

    private IsotopeCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BwrBlockEntities.ISOTOPE_SEPARATOR.get(),
                (be, side) -> be.itemView(side));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BwrBlockEntities.ISOTOPE_SEPARATOR.get(),
                (be, side) -> be.energy());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BwrBlockEntities.RTG.get(),
                (be, side) -> be.energy());
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,
                (level, pos, state, be, side) -> LiveCapabilities.energy(level, pos, state.getBlock(), current -> {
                    LargeRtgBlockEntity c = LargeRtgBlock.controller(level, pos, current);
                    return c == null ? null : c.energy();
                }), BwrBlocks.LARGE_RTG.get());
        event.registerBlock(Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, side) -> liveItems(level, pos), BwrBlocks.LARGE_RTG.get());
    }

    /** The controller's insert-only handler, resolved on every call. */
    private static IItemHandler liveItems(Level level, BlockPos position) {
        BlockPos pos = position.immutable();
        return new IItemHandler() {
            private IItemHandler current() {
                if (!level.isLoaded(pos)) {
                    return null;
                }
                var state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof LargeRtgBlock)) {
                    return null;
                }
                LargeRtgBlockEntity c = LargeRtgBlock.controller(level, pos, state);
                return c == null ? null : c.itemCapability();
            }

            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                IItemHandler h = current();
                return h == null ? stack : h.insertItem(slot, stack, simulate);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                IItemHandler h = current();
                return h != null && h.isItemValid(slot, stack);
            }
        };
    }
}
