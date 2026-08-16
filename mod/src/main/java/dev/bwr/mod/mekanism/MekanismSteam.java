package dev.bwr.mod.mekanism;

import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;

import java.util.Optional;

/**
 * The handful of Mekanism identities this mod needs, in one place.
 *
 * <p><b>Loading this class resolves Mekanism types.</b> Nothing may touch it
 * unless {@code BwrMod.isMekanismPresent()} is true, which is why the check
 * lives in {@code BwrMod} — in a different class — exactly as
 * {@code BwrPeripheralSupport} is guarded for CC:Tweaked.
 */
final class MekanismSteam {

    private MekanismSteam() {
    }

    /** Mekanism's steam chemical. Unified {@code Chemical} in 10.7; there is no {@code Gas} any more. */
    static final ResourceLocation STEAM_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "steam");

    /**
     * Mekanism's block chemical-handler capability.
     *
     * <p>Recreated rather than referenced. The name and handler class come from
     * {@code mekanism.common.capabilities.Capabilities.CHEMICAL}, which lives in
     * Mekanism's common jar and is not part of the {@code api} classifier this
     * mod compiles against. NeoForge caches capabilities by name and asserts the
     * type matches, so building one with the same name and the same interface
     * hands back the very instance Mekanism registered — pipes and turbines see
     * one capability, not two.
     *
     * <p>If a future Mekanism renames this capability, steam will simply stop
     * being offered: no crash, no wrong behaviour, just an outlet nothing can
     * connect to. That is the failure mode to look for if a version bump breaks
     * the boundary.
     */
    static final BlockCapability<IChemicalHandler, Direction> CHEMICAL_HANDLER =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"),
                    IChemicalHandler.class);

    /** Resolved once; the chemical registry is static and does not change under us. */
    private static Holder<Chemical> steamHolder;

    /**
     * A holder for Mekanism's steam, or null if this Mekanism does not have it.
     *
     * <p>A holder rather than a bare {@code Chemical} because that is what the
     * surviving {@code ChemicalStack} constructor takes — the
     * {@code Chemical}-and-amount form is deprecated for removal in 10.7. A
     * lookup miss simply returns null and the outlet offers nothing, which is
     * the same inert behaviour as Mekanism being absent altogether.
     */
    static Holder<Chemical> steam() {
        if (steamHolder == null) {
            Optional<? extends Holder<Chemical>> found = MekanismAPI.CHEMICAL_REGISTRY.getHolder(STEAM_ID);
            steamHolder = found.orElse(null);
        }
        return steamHolder;
    }
}
