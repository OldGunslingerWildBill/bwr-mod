package dev.bwr.mod.spentfuel;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * What a spent fuel pool offers its neighbours: its cells as an item handler and the cooling pump's power input on the
 * controller, and a water flange on each of the three ports (only on the face the flange points out of). The ports
 * have no block entity, so each capability resolves the owning controller when it is used and never keeps one.
 */
public final class SpentFuelPoolCapabilities {

    private SpentFuelPoolCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BwrBlockEntities.SPENT_FUEL_POOL.get(),
                (be, side) -> be.itemHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BwrBlockEntities.SPENT_FUEL_POOL.get(),
                (be, side) -> be.energy());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BwrBlockEntities.NEW_FUEL_ELEVATOR.get(),
                (be, side) -> be.itemHandler());
        for (SpentFuelPoolPortBlock block : new SpentFuelPoolPortBlock[] {
                BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT.get(), BwrBlocks.SPENT_FUEL_POOL_COOLING_IN.get(),
                BwrBlocks.SPENT_FUEL_POOL_COOLING_OUT.get()}) {
            event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, face) -> {
                if (face == null || face != state.getValue(SpentFuelPoolPortBlock.FACING)) {
                    return null;
                }
                return LiveCapabilities.fluid(level, pos, state.getBlock(), current -> {
                    if (face != current.getValue(SpentFuelPoolPortBlock.FACING)) {
                        return null;
                    }
                    SpentFuelPoolBlockEntity owner = SpentFuelPoolRegistry.ownerOf(level, pos);
                    return owner == null ? null : owner.waterHandler(block.kind());
                });
            }, block);
        }
    }
}
