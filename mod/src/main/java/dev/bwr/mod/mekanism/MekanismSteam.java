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

    /**
     * Mekanism's steam chemical. Unified {@code Chemical} in 10.7; there is no
     * {@code Gas} any more.
     *
     * <p>Checked against the bytecode of Mekanism 1.21.1-10.7.19.85 rather than
     * from memory, because handing over the wrong chemical produces exactly the
     * symptom a wrong pipe does — a turbine that quietly refuses everything —
     * and nothing in this project has ever run in a world to catch it.
     * {@code MekanismChemicals} registers this one under the plain name
     * {@code "steam"} in the {@code mekanism} namespace, and
     * {@code BoilerMultiblockData} fills its steam tank with that same
     * {@code MekanismChemicals.STEAM}. A Thermoelectric Boiler and this outlet
     * therefore produce the identical chemical, which is the only test that
     * matters: whatever an Industrial Turbine accepts from one, it accepts from
     * the other.
     *
     * <p>Not to be confused with {@code mekanism:water_vapor}, which is
     * registered right beside it and is a different chemical entirely.
     */
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
     * <p>Verified in the bytecode of 1.21.1-10.7.19.85:
     * {@code Capabilities.CHEMICAL} is a {@code MultiTypeCapability} built from
     * {@code Mekanism.rl("chemical_handler")} — namespace {@code mekanism} — and
     * that constructor calls {@code BlockCapability.createSided(name,
     * IChemicalHandler.class)}. Name, context type and handler class all match
     * what is built here, so this is the same instance and not a look-alike.
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
