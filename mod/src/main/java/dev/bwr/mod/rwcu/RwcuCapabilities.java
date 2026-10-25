package dev.bwr.mod.rwcu;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** FE into the pump (every face but its two flanges); cooling water in and out of the cooler's side flanges. */
public final class RwcuCapabilities {
    private RwcuCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BwrBlockEntities.RWCU_PUMP.get(), (be, side) -> {
            var state = be.getBlockState();
            return side == null || ((RwcuPumpBlock) state.getBlock()).roleAt(state, side) == null ? be.energy() : null;
        });
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (l, p, s, be, face) -> {
            if (face == null) return null;
            var block = (RwcuCoolerBlock) s.getBlock();
            var role = block.roleAt(s, face);
            if (role != RwcuUnitBlock.Role.COOLING_IN && role != RwcuUnitBlock.Role.COOLING_OUT) return null;
            return LiveCapabilities.fluid(l, p, s.getBlock(), current -> {
                if (!(l.getBlockEntity(p) instanceof RwcuCoolerBlockEntity cooler)) return null;
                var r = ((RwcuCoolerBlock) current.getBlock()).roleAt(current, face);
                if (r == RwcuUnitBlock.Role.COOLING_IN) return cooler.secondary(true);
                if (r == RwcuUnitBlock.Role.COOLING_OUT) return cooler.secondary(false);
                return null;
            });
        }, BwrBlocks.RWCU_NONREGENERATIVE_HEAT_EXCHANGER.get());
    }
}
