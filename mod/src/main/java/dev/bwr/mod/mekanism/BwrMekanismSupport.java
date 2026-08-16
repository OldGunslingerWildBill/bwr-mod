package dev.bwr.mod.mekanism;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Attaches the Mekanism chemical handler to the turbine steam outlet.
 *
 * <h2>Soft dependency, deliberately</h2>
 * Every class in this package references {@code mekanism.*} types, so none of
 * them may be loaded unless Mekanism is installed. {@code BwrMod} checks
 * {@code ModList.get().isLoaded("mekanism")} before registering this listener,
 * and because the check happens in a different class the JVM never has to
 * resolve a Mekanism type on a Mekanism-less install. This mirrors
 * {@code dev.bwr.mod.peripheral.BwrPeripheralSupport} exactly.
 *
 * <p>Mekanism is a <b>compileOnly</b> dependency against the {@code api}
 * classifier and is never bundled. It is MIT licensed; players install it
 * themselves.
 *
 * <p>With Mekanism absent the outlet still places, ticks and reports. Nothing
 * drains its buffer, so it fills, delivered flow goes to zero and the block is
 * inert — the reactor behaves exactly as it would with the turbine stop valves
 * shut, which is the honest answer rather than a crash or a special case.
 */
public final class BwrMekanismSupport {

    private BwrMekanismSupport() {
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                MekanismSteam.CHEMICAL_HANDLER,
                BwrBlockEntities.TURBINE_STEAM_OUTLET.get(),
                (be, side) -> new TurbineSteamOutletChemicalHandler(be));
    }
}
