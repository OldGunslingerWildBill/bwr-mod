package dev.bwr.mod.eccs;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The capabilities the plant's machinery exposes: energy on everything with a
 * motor, and a water tank on the condensate storage tank. All plain NeoForge,
 * so this class is safe on any install.
 *
 * <p>The turbine-driven machines register the same energy capability and then
 * refuse every joule offered, because their maximum receive rate is derived
 * from a motor rating of zero. That is more useful than not registering it:
 * a cable run to RCIC visibly does nothing, which is the correct lesson.
 *
 * <p>The recirculation pumps are not an emergency system and do not belong to
 * this package, but they are registered here rather than in a class of their
 * own because {@code BwrMod} already attaches a listener to this one and a
 * second handler would buy nothing. Leaving them out is not an option: with no
 * energy capability a cable finds nothing to push into, the pump's buffer stays
 * empty forever, and forced circulation — half of what a BWR operator actually
 * has to manipulate — silently does not exist in a running game.
 */
public final class EccsCapabilities {

    private EccsCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.ECCS_PUMP.get(),
                (be, side) -> be.energy());

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.ADS_CONTROLLER.get(),
                (be, side) -> be.energy());

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.RECIRCULATION_PUMP.get(),
                (be, side) -> be.energy());

        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(),
                (be, side) -> be.tank());

        // The reactor feed pumps. Energy on both, because the turbine-driven one
        // refuses every joule offered through a motor rating of zero and that is
        // more useful than not registering it: a cable run to it visibly does
        // nothing, which is the correct lesson.
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.FEEDWATER_PUMP.get(),
                (be, side) -> be.energy());

        // And water on both, on every face. This is the condensate return: the
        // player's Mekanism turbine condenses this plant's steam and gives the
        // water back, and this is where it goes. There is no condenser block in
        // this mod because the turbine already is one.
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                BwrBlockEntities.FEEDWATER_PUMP.get(),
                (be, side) -> be.suction());
    }
}
