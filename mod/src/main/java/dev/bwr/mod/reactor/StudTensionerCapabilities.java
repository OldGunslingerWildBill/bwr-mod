package dev.bwr.mod.reactor;

import dev.bwr.mod.registry.BwrBlocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The stud tensioner's power pack takes FE on every side. */
public final class StudTensionerCapabilities {
    private StudTensionerCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, unused, side) ->
                level.getBlockEntity(pos) instanceof StudTensionerBlockEntity be ? be.energy() : null, BwrBlocks.STUD_TENSIONER.get());
    }
}
