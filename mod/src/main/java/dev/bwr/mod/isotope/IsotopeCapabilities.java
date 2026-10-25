package dev.bwr.mod.isotope;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The separator's five item slots and its energy buffer, and the RTG's energy output (extract-only, every face): plain
 * NeoForge, nothing from Mekanism or CC.
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
    }
}
