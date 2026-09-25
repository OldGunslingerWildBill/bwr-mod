package dev.bwr.mod.mekanism;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.SteamExport;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Attaches the Mekanism chemical handler to the turbine steam outlet, and the
 * push that empties it.
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

    /**
     * Both halves of the steam boundary: the tank Mekanism may extract from, and
     * the push that offers the same tank to the neighbours.
     *
     * <p>The push is installed here rather than from its own entry point because
     * this method is already the one thing {@code BwrMod} calls behind the
     * {@code isLoaded("mekanism")} guard, it runs once during mod construction
     * long before any level exists, and keeping the pull and the push side by
     * side is the only way a reader finds both. They are one boundary; a future
     * change to either has to consider the other, and code that has to be
     * considered together should be read together.
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                MekanismSteam.CHEMICAL_HANDLER,
                BwrBlockEntities.TURBINE_STEAM_OUTLET.get(),
                (be, side) -> new TurbineSteamOutletChemicalHandler(be));

        SteamExport.install(new TurbineSteamOutletPusher());
        event.registerBlock(MekanismSteam.CHEMICAL_HANDLER,(level,pos,state,be,side)->
                side==state.getValue(dev.bwr.mod.suppression.SuppressionPoolSteamPortBlock.FACING)
                        ?new SuppressionSteamChemicalHandler(level,pos,side):null,
                dev.bwr.mod.registry.BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get());
    }
}
