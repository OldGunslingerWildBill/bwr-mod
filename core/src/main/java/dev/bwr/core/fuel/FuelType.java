package dev.bwr.core.fuel;

import java.util.List;
import java.util.Objects;

/**
 * Immutable nuclear data for one kind of fuel. SPEC 2.1.
 *
 * <p><b>This is data, not behaviour.</b> Every field is a number a datapack or
 * JSON registry entry can supply; nothing here is computed from a hardcoded
 * table of fuel names. The presets below exist so the standalone core module
 * has something to run against and so the datapack has a worked example — they
 * are defaults, not the registry.
 *
 * <p><b>ReactorCore must never see a fuel name.</b> It sees the aggregated
 * constants produced by {@link CoreLoading}: one k-infinity, one beta, one
 * prompt lifetime, one Doppler coefficient. {@link #name()} exists for the item
 * layer, the refuelling GUI and the datapack loader, and for nothing else. If
 * physics code ever branches on {@code name}, that is a bug.
 *
 * <p><b>On plutonium and MOX.</b> Their danger is entirely emergent from
 * {@link #beta()}: 0.002099 for Pu-239 against 0.006502 for U-235 is a factor
 * of 3.1 less control margin, so a reactivity insertion that LEU rides out on
 * delayed neutrons can take a plutonium core prompt critical. That number is
 * Keepin's measured delayed neutron fraction, not a balance knob. There must
 * never be a hardcoded "plutonium is dangerous" penalty, damage multiplier or
 * instability term anywhere in this mod — the point of modelling kinetics
 * properly is that the hazard falls out of the equations by itself. Anyone
 * tempted to add one has found a bug in the kinetics instead.
 *
 * <p>Units are in the component names. The two dimensionless multiplication
 * factors ({@code kInfBase}, {@code beta}) carry no unit suffix because they
 * have no unit.
 *
 * @param name                            registry name, e.g. {@code "leu"}. Item/GUI layer only.
 * @param kInfBase                        infinite multiplication factor of fresh, unpoisoned fuel at
 *                                        {@link #nominalEnrichmentWeightFraction()}, dimensionless
 * @param beta                            total delayed neutron fraction of the fissioning isotope,
 *                                        dimensionless. Keepin thermal-fission data
 * @param promptLifetimeSeconds           prompt neutron lifetime, seconds. Shorter with higher
 *                                        fissile loading — more absorbers, less time between birth
 *                                        and capture
 * @param depletionKInfPerMwdPerTonne     k-infinity lost per MWd/tonne of burnup. This is the
 *                                        cycle-length knob
 * @param dopplerCoeffPerC                fuel temperature coefficient, dk/k per degree C. Must be
 *                                        negative — it is the prompt term that terminates an excursion
 * @param gadContentWeightFraction        initial gadolinia loading, weight fraction. Burnable poison:
 *                                        holds down beginning-of-cycle excess reactivity and burns out
 * @param heatPerFissionMeV               recoverable energy per fission, MeV
 * @param nominalEnrichmentWeightFraction fissile weight fraction at which {@code kInfBase} was
 *                                        quoted. An assembly loaded at a different enrichment scales
 *                                        off this — see {@link FuelAssembly#baseKInf()}
 * @param conversionGainKInf              k-infinity eventually gained from breeding fissile in,
 *                                        saturating with burnup. Zero for every fuel that is not a
 *                                        net breeder; non-zero for thorium, which starts subcritical
 *                                        on its own and improves as U-233 builds in (SPEC 2.2)
 */
public record FuelType(
        String name,
        double kInfBase,
        double beta,
        double promptLifetimeSeconds,
        double depletionKInfPerMwdPerTonne,
        double dopplerCoeffPerC,
        double gadContentWeightFraction,
        double heatPerFissionMeV,
        double nominalEnrichmentWeightFraction,
        double conversionGainKInf
) {

    /** Enrichment assumed when a loader supplies only the seven SPEC 2.1 parameters. */
    public static final double DEFAULT_NOMINAL_ENRICHMENT_WEIGHT_FRACTION = 0.035;

    /** Joules per MeV, for converting {@link #heatPerFissionMeV()}. */
    public static final double JOULES_PER_MEV = 1.602176634e-13;

    /**
     * Validates on construction so a malformed datapack entry fails at load
     * time with a legible message rather than producing NaN reactivity forty
     * minutes into someone's fuel cycle.
     */
    public FuelType {
        Objects.requireNonNull(name, "fuel type name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("fuel type name must not be empty");
        }
        require(kInfBase > 0.0, name, "kInfBase must be positive, was " + kInfBase);
        require(beta > 0.0 && beta < 1.0, name, "beta must be in (0,1), was " + beta);
        require(promptLifetimeSeconds > 0.0, name,
                "promptLifetimeSeconds must be positive, was " + promptLifetimeSeconds);
        require(depletionKInfPerMwdPerTonne >= 0.0, name,
                "depletionKInfPerMwdPerTonne must not be negative, was " + depletionKInfPerMwdPerTonne);
        require(dopplerCoeffPerC < 0.0, name,
                "dopplerCoeffPerC must be negative, was " + dopplerCoeffPerC);
        require(gadContentWeightFraction >= 0.0 && gadContentWeightFraction < 1.0, name,
                "gadContentWeightFraction must be in [0,1), was " + gadContentWeightFraction);
        require(heatPerFissionMeV > 0.0, name,
                "heatPerFissionMeV must be positive, was " + heatPerFissionMeV);
        require(nominalEnrichmentWeightFraction > 0.0 && nominalEnrichmentWeightFraction < 1.0, name,
                "nominalEnrichmentWeightFraction must be in (0,1), was " + nominalEnrichmentWeightFraction);
        require(conversionGainKInf >= 0.0, name,
                "conversionGainKInf must not be negative, was " + conversionGainKInf);
    }

    /**
     * The seven SPEC 2.1 parameters plus a name. Nominal enrichment defaults to
     * {@value #DEFAULT_NOMINAL_ENRICHMENT_WEIGHT_FRACTION} and the breeding gain
     * to zero, so a datapack entry that does not mention either behaves as a
     * conventional non-breeding fuel quoted at LEU enrichment.
     */
    public FuelType(String name,
                    double kInfBase,
                    double beta,
                    double promptLifetimeSeconds,
                    double depletionKInfPerMwdPerTonne,
                    double dopplerCoeffPerC,
                    double gadContentWeightFraction,
                    double heatPerFissionMeV) {
        this(name, kInfBase, beta, promptLifetimeSeconds, depletionKInfPerMwdPerTonne,
                dopplerCoeffPerC, gadContentWeightFraction, heatPerFissionMeV,
                DEFAULT_NOMINAL_ENRICHMENT_WEIGHT_FRACTION, 0.0);
    }

    private static void require(boolean condition, String name, String message) {
        if (!condition) {
            throw new IllegalArgumentException("fuel type '" + name + "': " + message);
        }
    }

    /** Recoverable energy per fission in joules. */
    public double heatPerFissionJoules() {
        return heatPerFissionMeV * JOULES_PER_MEV;
    }

    /** True when this fuel breeds fissile faster than it is worth accounting as pure depletion. */
    public boolean isNetBreeder() {
        return conversionGainKInf > 0.0;
    }

    // ---------------------------------------------------------------
    // Presets
    //
    // Every beta below is a measured physical constant, taken from the Keepin
    // thermal-fission data reproduced in REFERENCE-DATA.md section 4. They are
    // NOT tuned for game balance and must not be adjusted to make a fuel tier
    // feel better. Everything that makes plutonium frightening comes out of
    // 0.002099 by itself.
    //
    // The remaining values are model calibration: chosen so a fresh LEU core
    // reaches criticality with margin for rods, so HEU carries visibly more
    // excess reactivity, and so discharge burnup lands in the right decade
    // (LEU end of cycle near 29 GWd/tonne). Those are legitimately tunable and
    // are the natural first thing for a datapack to override.
    // ---------------------------------------------------------------

    /**
     * Low-enriched uranium, ~3.5% U-235. The training-wheels fuel: modest
     * excess reactivity, the largest delayed neutron fraction available, and a
     * strong Doppler coefficient from all the U-238 sitting in the lattice.
     */
    public static final FuelType LEU = new FuelType(
            "leu",
            1.22,        // kInfBase
            0.006502,    // beta — Keepin U-235 thermal, sum of six groups
            4.0e-5,      // prompt lifetime, s
            6.5e-6,      // k_inf lost per MWd/tonne
            -1.6e-5,     // Doppler, dk/k per C — literature value for operating BWRs
            0.02,        // gadolinia weight fraction
            202.5,       // MeV per fission, U-235
            0.035,       // nominal enrichment
            0.0);        // no net breeding

    /**
     * Highly enriched uranium, ~20% U-235. Same isotope, same beta — the danger
     * is not kinetic but the sheer excess reactivity held down by poison and
     * rods, plus a weaker Doppler coefficient because there is far less U-238
     * to absorb on resonance. Withdrawal errors are severe here.
     */
    public static final FuelType HEU = new FuelType(
            "heu",
            1.45,
            0.006502,    // beta — same fissioning isotope as LEU, so identical
            2.5e-5,      // shorter lifetime: more fissile, neutrons captured sooner
            2.0e-6,      // depletes slowly, long cycle
            -1.1e-5,     // weaker Doppler: less U-238 resonance absorber
            0.04,        // more burnable poison needed to hold the excess down
            202.5,
            0.20,
            0.0);

    /**
     * Plutonium fuel. Beta is roughly a third of U-235's, so the control margin
     * is roughly a third. Transients an LEU core rides out on delayed neutrons
     * can put this core prompt critical. Nothing in the code enforces that —
     * it is what the point kinetics equations do with beta = 0.002099.
     */
    public static final FuelType PLUTONIUM = new FuelType(
            "plutonium",
            1.30,
            0.002099,    // beta — Keepin Pu-239 thermal, sum of six groups
            2.0e-5,      // large thermal fission cross-section, short lifetime
            5.0e-6,
            -1.4e-5,
            0.02,
            211.5,       // MeV per fission, Pu-239
            0.06,
            0.0);

    /**
     * Mixed oxide: plutonium in a depleted-uranium matrix. Beta sits between
     * the uranium and plutonium values because both isotopes fission. Harder
     * spectrum, twitchier than LEU, but the Pu-240 resonances give it the
     * strongest Doppler of any fuel here — an unusually self-limiting fuel that
     * is nevertheless unusually easy to take prompt critical.
     */
    public static final FuelType MOX = new FuelType(
            "mox",
            1.24,
            0.0035,      // beta — blended U/Pu fission rate, SPEC 2.2 range
            3.0e-5,
            5.5e-6,
            -1.8e-5,     // Pu-240 resonance absorption: strong Doppler
            0.025,
            206.0,
            0.07,
            0.0);

    /**
     * Thorium with a fissile driver. Th-232 is fertile, not fissile, so a fresh
     * load is barely able to sustain a chain reaction on its own and needs
     * driver assemblies of LEU or HEU in the same core — which the flux-weighted
     * aggregate in {@link CoreLoading} handles without any special case. As
     * U-233 breeds in, {@code conversionGainKInf} lifts k-infinity above where
     * it started, then depletion eventually wins. Rewards long-term planning.
     */
    public static final FuelType THORIUM = new FuelType(
            "thorium",
            1.05,        // starts weak — a full thorium core is marginal by itself
            0.00266,     // beta — U-233, the isotope that actually fissions here
            4.2e-5,
            3.0e-6,
            -1.9e-5,     // Th-232 is a strong resonance absorber
            0.0,         // no burnable poison: there is no excess to hold down
            199.7,       // MeV per fission, U-233
            0.03,
            0.10);       // breeds U-233 in, saturating with burnup

    /**
     * The built-in fuels, in tier order. A datapack loader should treat this as
     * the default contents of the registry and be free to replace any of them.
     */
    public static List<FuelType> presets() {
        return List.of(LEU, HEU, PLUTONIUM, MOX, THORIUM);
    }
}
