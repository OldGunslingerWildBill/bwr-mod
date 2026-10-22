package dev.bwr.mod.fuel;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The capabilities the fabricator and the reprocessing plant expose that do not involve Mekanism: the
 * four item slots ({@link FabricatorItemView}) and the energy buffer. Both are plain NeoForge, so this class is
 * safe to load on any install.
 *
 * <p>Mekanism's own chemical capability is registered separately in
 * {@code MekanismFuelFeed}, which is only ever touched when Mekanism is loaded.
 */
public final class FuelCapabilities {

    private FuelCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                BwrBlockEntities.FUEL_FABRICATOR.get(),
                (be, side) -> be.itemView(side));

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.FUEL_FABRICATOR.get(),
                (be, side) -> be.energy());

        // The reprocessing plant: the same pair, four slots (spent assembly in, three products out) and an energy buffer.
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                BwrBlockEntities.REPROCESSING_PLANT.get(),
                (be, side) -> be.itemView(side));

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.REPROCESSING_PLANT.get(),
                (be, side) -> be.energy());
    }
}
