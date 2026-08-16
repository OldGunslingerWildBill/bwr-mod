package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;

import java.util.List;
import java.util.Map;

/**
 * What comes out of the fabricator, given what went in. SPEC 2.4.
 *
 * <h2>There are no recipes here</h2>
 * The fabricator does not have an LEU recipe and an HEU recipe. It has a
 * crucible: heavy metal goes in, fissile goes in, and the assembly it turns out
 * is described by the ratio the player achieved. Feed a trickle of Mekanism
 * fissile fuel into a river of uranium hexafluoride and you get a 3% bundle;
 * feed the two at comparable rates and you get a 20% bundle out of the same
 * machine with no configuration changed. That is the whole point of routing
 * this through Mekanism's enrichment chain rather than writing our own: the
 * enrichment <i>is</i> the player's plumbing.
 *
 * <h2>What the tables below are and are not</h2>
 * {@link #CANDIDATES} says which nuclear data a given heavy metal turns into.
 * That is a fabrication fact — a bundle of plutonium in a uranium matrix is
 * called MOX and has MOX's measured delayed neutron fraction — and it is the
 * item layer's business, which is the one place {@link FuelType} permits fuel
 * names to be handled.
 *
 * <p>It is <b>not</b> a difficulty ladder. Nothing here makes plutonium worse
 * than uranium; it simply routes plutonium feed to the fuel type whose beta is
 * 0.002099, and the point kinetics does the rest. There is no multiplier, no
 * penalty, and no gate: a player who wants to fabricate a 90% enriched
 * plutonium bundle and load it into the centre of the core is free to, and the
 * consequences are whatever the equations say they are.
 */
public final class FuelFabrication {

    private FuelFabrication() {
    }

    /** Below this plutonium fraction of the heavy metal, the bundle is uranium fuel. */
    public static final double MOX_LOWER_PLUTONIUM_FRACTION = 0.02;

    /** Above this plutonium fraction, there is no uranium matrix left to call it MOX. */
    public static final double MOX_UPPER_PLUTONIUM_FRACTION = 0.90;

    /** Which heavy metal the matrix is made of. Decided by what was fed in, nothing else. */
    public enum Family {
        /** Uranium matrix, uranium fissile. */
        URANIUM,
        /** Plutonium dispersed in a uranium matrix — mixed oxide. */
        MIXED,
        /** Essentially no uranium matrix left. */
        PLUTONIUM
    }

    /**
     * Fuel type <i>names</i> reachable from each feed family. {@link #select}
     * resolves them against {@link FuelTypes} at the moment of fabrication and
     * picks within the list by the enrichment the player actually achieved.
     *
     * <p>Names rather than {@link FuelType} constants, because the registry is
     * datapack-driven (SPEC 2.1) and a pack that retunes {@code mox} must retune
     * what this machine turns out. Resolving late is what makes that true; a
     * table of compiled constants would quietly keep fabricating the shipped
     * defaults forever. Handling fuel names is the item layer's job and this is
     * the item layer.
     *
     * <p>Thorium is deliberately absent: Mekanism's chain produces no thorium,
     * so a thorium bundle is not something this machine can make. It stays
     * available through crafting.
     */
    private static final Map<Family, List<String>> CANDIDATE_NAMES = Map.of(
            Family.URANIUM, List.of(FuelType.LEU.name(), FuelType.HEU.name()),
            Family.MIXED, List.of(FuelType.MOX.name()),
            Family.PLUTONIUM, List.of(FuelType.PLUTONIUM.name()));

    /** Classifies a batch by how much of its heavy metal is plutonium. */
    public static Family familyFor(double plutoniumHeavyMetalFraction) {
        if (plutoniumHeavyMetalFraction < MOX_LOWER_PLUTONIUM_FRACTION) {
            return Family.URANIUM;
        }
        if (plutoniumHeavyMetalFraction > MOX_UPPER_PLUTONIUM_FRACTION) {
            return Family.PLUTONIUM;
        }
        return Family.MIXED;
    }

    /**
     * The fuel type whose nuclear data was measured closest below the achieved
     * enrichment.
     *
     * <p>Picking the highest candidate whose nominal enrichment does not exceed
     * what was achieved, rather than the nearest, means enrichment never buys a
     * fuel type the player did not pay for. A 12% uranium batch comes out as
     * LEU nuclear data carried at 12% — which through
     * {@link FuelAssembly#baseKInf()} is a genuinely stronger bundle than 3.5%
     * LEU, just not as strong as real 20% HEU with its harder spectrum and
     * weaker Doppler coefficient. Crossing 20% is what buys the HEU data.
     *
     * <p>Resolution goes through {@link FuelTypes#byNameOrPreset}, so a fuel
     * whose datapack entry failed to decode is still fabricated with its own
     * nuclear data rather than quietly becoming LEU. That only holds because
     * {@link FuelTypes#byNameOrFallback} consults the same presets when the
     * stamped name is read back off the itemstack — the two must stay in step,
     * or this guard merely writes a label the physics cannot honour.
     */
    public static FuelType select(Family family, double enrichmentWeightFraction) {
        FuelType chosen = null;
        for (String name : CANDIDATE_NAMES.get(family)) {
            FuelType candidate = FuelTypes.byNameOrPreset(name);
            if (candidate == null) {
                continue;
            }
            if (chosen == null) {
                chosen = candidate;
                continue;
            }
            if (candidate.nominalEnrichmentWeightFraction() <= enrichmentWeightFraction
                    && candidate.nominalEnrichmentWeightFraction()
                    >= chosen.nominalEnrichmentWeightFraction()) {
                chosen = candidate;
            }
        }
        return chosen != null ? chosen : FuelTypes.fallback();
    }

    /**
     * Turns an accumulated batch into a bundle.
     *
     * @param heavyMetalKg total heavy metal in the batch, kg
     * @param fissileKg    fissile content of that heavy metal, kg
     * @param plutoniumKg  how much of the heavy metal is plutonium, kg
     */
    public static FuelAssemblyData fabricate(double heavyMetalKg, double fissileKg, double plutoniumKg) {
        if (!(heavyMetalKg > 0.0)) {
            throw new IllegalArgumentException("cannot fabricate from " + heavyMetalKg + " kg of heavy metal");
        }
        double enrichment = fissileKg / heavyMetalKg;
        Family family = familyFor(plutoniumKg / heavyMetalKg);
        FuelType type = select(family, enrichment);
        return new FuelAssemblyData(type.name(), enrichment, heavyMetalKg, 0.0, 1.0);
    }

    /**
     * Heavy metal one bundle is built from, kg. Matches
     * {@link FuelAssembly#REFERENCE_HEAVY_METAL_MASS_KG} so that a fabricated
     * bundle and a hand-crafted one are the same physical object.
     */
    public static double batchHeavyMetalKg() {
        return FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG;
    }
}
