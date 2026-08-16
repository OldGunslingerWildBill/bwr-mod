package dev.bwr.core.fuel;

import java.util.Objects;

/**
 * One fuel assembly and everything that has happened to it. SPEC 2.3.
 *
 * <pre>
 *   k_inf = base(enrichment) - burnupPenalty(burnup) - gadPenalty(gadRemaining)
 * </pre>
 *
 * <p><b>This object has no idea where it is.</b> There is deliberately no slot,
 * position, index or core reference on it. Burnup and remaining gadolinia are
 * carried on the Minecraft item as data components, which is what makes real
 * fuel shuffling possible: pull a partly-burned assembly out of a hot central
 * position, put it on the periphery next cycle, and its exposure travels with
 * it. Position lives in {@link CoreLoading}, exposure lives here, and the two
 * meet only when the core is loaded.
 *
 * <p>Mutable and identity-compared on purpose. Shuffling means moving <i>this
 * assembly</i>, not a value equal to it, so {@code equals} is left as reference
 * equality. Use {@link #copy()} when a snapshot is wanted.
 *
 * <p>Nothing in this class knows a fuel name; it reads numbers off its
 * {@link FuelType}.
 */
public final class FuelAssembly {

    // ---------------------------------------------------------------
    // Model constants
    // ---------------------------------------------------------------

    /** Heavy metal mass of a standard BWR assembly, kg. ~180 kg U per bundle. */
    public static final double REFERENCE_HEAVY_METAL_MASS_KG = 180.0;

    /**
     * k-infinity held down per unit gadolinia weight fraction, fresh.
     * With the LEU preset's 0.02 loading this is 0.10 dk of burnable poison,
     * which is the right order for a gadded BWR bundle.
     */
    public static final double GAD_KINF_PER_WEIGHT_FRACTION = 5.0;

    /**
     * Burnup at which a fresh gadolinia loading is exhausted, MWd/tonne. Real
     * gad is sized to burn out roughly a third of the way through the cycle so
     * its disappearance offsets fuel depletion and keeps k-infinity flat.
     */
    public static final double GAD_BURNOUT_MWD_PER_TONNE = 15000.0;

    /**
     * Self-shielding exponent for the remaining gadolinia. Gad burns onion-skin
     * fashion from the outside of the pellet inward: the rod stays essentially
     * black until it is nearly gone, so absorption follows the shrinking
     * surface area (remaining^(2/3)) rather than the remaining mass. A linear
     * law would make the poison fade away smoothly, which is not what a gadded
     * core does — it holds, then lets go.
     */
    public static final double GAD_SELF_SHIELDING_EXPONENT = 2.0 / 3.0;

    /**
     * Burnup scale over which breeding gain saturates, MWd/tonne. Applies only
     * to fuels with a non-zero {@link FuelType#conversionGainKInf()}.
     */
    public static final double CONVERSION_SATURATION_MWD_PER_TONNE = 20000.0;

    /**
     * Ratio of non-fissile to fissile thermal absorption in the lattice, used
     * by the enrichment response. Chosen so thermal utilisation is ~0.73 at 3.5%
     * enrichment, which puts the LEU preset on the steep part of the curve and
     * HEU on the flat part — doubling enrichment near 20% buys very little, and
     * that saturation is why real fuel designers stop enriching and start
     * adding poison.
     */
    private static final double THERMAL_ABSORPTION_RATIO = 0.0135;

    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final double KG_PER_TONNE = 1000.0;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final FuelType fuelType;
    private final double enrichmentWeightFraction;
    private final double heavyMetalMassKg;

    private double burnupMwdPerTonne;
    private double gadoliniaRemainingFraction;

    /** A fresh assembly of this fuel at its nominal enrichment and standard mass. */
    public FuelAssembly(FuelType fuelType) {
        this(fuelType, fuelType.nominalEnrichmentWeightFraction(), REFERENCE_HEAVY_METAL_MASS_KG);
    }

    /** A fresh assembly at a chosen enrichment — what the player fed the enrichment chain. */
    public FuelAssembly(FuelType fuelType, double enrichmentWeightFraction, double heavyMetalMassKg) {
        this.fuelType = Objects.requireNonNull(fuelType, "fuelType");
        if (!(enrichmentWeightFraction > 0.0) || enrichmentWeightFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "enrichmentWeightFraction must be in (0,1), was " + enrichmentWeightFraction);
        }
        if (!(heavyMetalMassKg > 0.0)) {
            throw new IllegalArgumentException(
                    "heavyMetalMassKg must be positive, was " + heavyMetalMassKg);
        }
        this.enrichmentWeightFraction = enrichmentWeightFraction;
        this.heavyMetalMassKg = heavyMetalMassKg;
        this.burnupMwdPerTonne = 0.0;
        this.gadoliniaRemainingFraction = 1.0;
    }

    /**
     * Rebuilds an assembly from stored item data components. This is the read
     * side of persistence; {@link #burnupMwdPerTonne()} and
     * {@link #gadoliniaRemainingFraction()} are the write side.
     */
    public static FuelAssembly restore(FuelType fuelType,
                                       double enrichmentWeightFraction,
                                       double heavyMetalMassKg,
                                       double burnupMwdPerTonne,
                                       double gadoliniaRemainingFraction) {
        FuelAssembly assembly = new FuelAssembly(fuelType, enrichmentWeightFraction, heavyMetalMassKg);
        assembly.setBurnupMwdPerTonne(burnupMwdPerTonne);
        assembly.setGadoliniaRemainingFraction(gadoliniaRemainingFraction);
        return assembly;
    }

    // ---------------------------------------------------------------
    // Identity and stored state
    // ---------------------------------------------------------------

    public FuelType fuelType() {
        return fuelType;
    }

    /** Fissile weight fraction as loaded, dimensionless. */
    public double enrichmentWeightFraction() {
        return enrichmentWeightFraction;
    }

    public double heavyMetalMassKg() {
        return heavyMetalMassKg;
    }

    public double heavyMetalMassTonnes() {
        return heavyMetalMassKg / KG_PER_TONNE;
    }

    /** Accumulated exposure, MWd per tonne of heavy metal. */
    public double burnupMwdPerTonne() {
        return burnupMwdPerTonne;
    }

    /** Fraction of the original gadolinia loading still present, 1 fresh, 0 burned out. */
    public double gadoliniaRemainingFraction() {
        return gadoliniaRemainingFraction;
    }

    public void setBurnupMwdPerTonne(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            throw new IllegalArgumentException("burnupMwdPerTonne must be >= 0, was " + value);
        }
        this.burnupMwdPerTonne = value;
    }

    public void setGadoliniaRemainingFraction(double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("gadoliniaRemainingFraction must be a number");
        }
        this.gadoliniaRemainingFraction = clamp(value, 0.0, 1.0);
    }

    // ---------------------------------------------------------------
    // Reactivity — SPEC 2.3
    // ---------------------------------------------------------------

    /**
     * base(enrichment): k-infinity of this assembly fresh and unpoisoned.
     *
     * <p>Scales the fuel type's quoted k-infinity by thermal utilisation, which
     * saturates with enrichment. The curve is steep below ~5% and nearly flat
     * above ~15%, so LEU responds strongly to a little extra enrichment while
     * HEU does not — which is the physical reason HEU's advantage is cycle
     * length rather than a proportionally larger k-infinity.
     */
    public double baseKInf() {
        double here = thermalUtilisation(enrichmentWeightFraction);
        double nominal = thermalUtilisation(fuelType.nominalEnrichmentWeightFraction());
        return fuelType.kInfBase() * (here / nominal);
    }

    /**
     * burnupPenalty(burnup): k-infinity lost to depletion, net of any fissile
     * bred in. Linear depletion is a good fit to a real k-infinity-versus-burnup
     * curve once the burnable poison is gone; the early hump comes from the gad
     * term, not from this one.
     *
     * <p>Negative for a breeder early in life — thorium genuinely gets better
     * before it gets worse.
     */
    public double burnupPenaltyKInf() {
        double depletion = fuelType.depletionKInfPerMwdPerTonne() * burnupMwdPerTonne;
        double bred = 0.0;
        if (fuelType.conversionGainKInf() > 0.0) {
            bred = fuelType.conversionGainKInf()
                    * (1.0 - Math.exp(-burnupMwdPerTonne / CONVERSION_SATURATION_MWD_PER_TONNE));
        }
        return depletion - bred;
    }

    /** gadPenalty(gadRemaining): k-infinity held down by the remaining burnable poison. */
    public double gadPenaltyKInf() {
        double loading = fuelType.gadContentWeightFraction();
        if (loading <= 0.0 || gadoliniaRemainingFraction <= 0.0) {
            return 0.0;
        }
        double shielded = Math.pow(gadoliniaRemainingFraction, GAD_SELF_SHIELDING_EXPONENT);
        return GAD_KINF_PER_WEIGHT_FRACTION * loading * shielded;
    }

    /**
     * Infinite multiplication factor of this assembly right now. Clamped at
     * zero: a fully spent assembly multiplies nothing, it does not multiply
     * negatively.
     */
    public double kInf() {
        double k = baseKInf() - burnupPenaltyKInf() - gadPenaltyKInf();
        return Math.max(0.0, k);
    }

    // ---------------------------------------------------------------
    // Pass-through nuclear data, for the flux-weighted aggregate
    // ---------------------------------------------------------------

    /** Total delayed neutron fraction of this assembly's fuel, dimensionless. */
    public double beta() {
        return fuelType.beta();
    }

    public double promptLifetimeSeconds() {
        return fuelType.promptLifetimeSeconds();
    }

    public double dopplerCoeffPerC() {
        return fuelType.dopplerCoeffPerC();
    }

    public double heatPerFissionMeV() {
        return fuelType.heatPerFissionMeV();
    }

    // ---------------------------------------------------------------
    // Exposure
    // ---------------------------------------------------------------

    /**
     * Adds exposure directly and burns the gadolinia that goes with it.
     *
     * @param deltaMwdPerTonne exposure increment, MWd/tonne; ignored if not positive
     */
    public void accumulateBurnupMwdPerTonne(double deltaMwdPerTonne) {
        if (!(deltaMwdPerTonne > 0.0)) {
            return;
        }
        burnupMwdPerTonne += deltaMwdPerTonne;
        if (fuelType.gadContentWeightFraction() > 0.0 && gadoliniaRemainingFraction > 0.0) {
            gadoliniaRemainingFraction = Math.max(0.0,
                    gadoliniaRemainingFraction - deltaMwdPerTonne / GAD_BURNOUT_MWD_PER_TONNE);
        }
    }

    /**
     * Adds the exposure produced by running this assembly at a given thermal
     * power for a given time. Straight bookkeeping: MW x days / tonnes.
     *
     * <p>Central assemblies see more power and therefore burn faster, which is
     * the entire reason fuel shuffling exists.
     *
     * @param assemblyThermalMW this assembly's share of core thermal power, MW
     * @param seconds           elapsed time, seconds
     * @return the exposure increment applied, MWd/tonne
     */
    public double accumulateBurnup(double assemblyThermalMW, double seconds) {
        if (!(assemblyThermalMW > 0.0) || !(seconds > 0.0)) {
            return 0.0;
        }
        double megawattDays = assemblyThermalMW * (seconds / SECONDS_PER_DAY);
        double delta = megawattDays / heavyMetalMassTonnes();
        accumulateBurnupMwdPerTonne(delta);
        return delta;
    }

    /** Thermal power this assembly produces at a given fission rate, MW. Convenience for the item tooltip. */
    public double thermalPowerMW(double fissionsPerSecond) {
        return fissionsPerSecond * fuelType.heatPerFissionJoules() * 1.0e-6;
    }

    /** An independent copy with the same exposure. Snapshot, not a shuffle. */
    public FuelAssembly copy() {
        return restore(fuelType, enrichmentWeightFraction, heavyMetalMassKg,
                burnupMwdPerTonne, gadoliniaRemainingFraction);
    }

    private static double thermalUtilisation(double fissileWeightFraction) {
        return fissileWeightFraction
                / (fissileWeightFraction + THERMAL_ABSORPTION_RATIO * (1.0 - fissileWeightFraction));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public String toString() {
        return String.format(
                "FuelAssembly[%s e=%.4f burnup=%.0f MWd/t gad=%.2f k_inf=%.4f]",
                fuelType.name(), enrichmentWeightFraction, burnupMwdPerTonne,
                gadoliniaRemainingFraction, kInf());
    }
}
