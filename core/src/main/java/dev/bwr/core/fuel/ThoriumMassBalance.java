package dev.bwr.core.fuel;

/**
 * What a thorium assembly costs in uranium and thorium, in one place: the fuel fabricator's thorium route and the
 * documentation both come from here.
 *
 * <p>Thorium-232 is fertile, not fissile: a fresh thorium assembly needs a fissile <b>driver</b> until U-233 has bred in.
 * The driver here is high-assay low-enriched uranium (HALEU) at {@link #DRIVER_ASSAY} (19.75 % U-235, the mod's
 * {@code uranium_1975} grade, the highest assay below the 20 % line), made inside the machine by the same enrichment mass
 * balance as the uranium route ({@link EnrichmentMassBalance}). The driver fraction is the thorium fuel type's own
 * nominal enrichment (2, 3 and 5 % of the heavy metal in the shipped grades), counted as the U-235's mass fraction of the
 * whole assembly's heavy metal ({@value SpentFuel#INGOTS_PER_ASSEMBLY} ingot-units, the same model as the other routes).
 *
 * <pre>
 *   driver product D   = P x f / Ad                       (ingots of HALEU in P ingots of heavy metal)
 *   natural uranium    = D x (Ad - xt) / (xf - xt)        rounded up      (feed of an enrichment plant)
 *   depleted uranium   = natural uranium - D              rounded down
 *   thorium            = P - D                            rounded up to whole ingots (the matrix)
 * </pre>
 *
 * <p>Like the other routes it can lose to rounding and never gain: uranium and thorium are rounded up, the tails down. Thorium
 * is monoisotopic Th-232, so there is no thorium enrichment, only the driver. All assays are on the same 1e-5 grid as
 * {@link EnrichmentMassBalance}, so the rounding is exact.
 */
public final class ThoriumMassBalance {

    private ThoriumMassBalance() {
    }

    /** Assay of the driver, weight fraction of U-235: HALEU just under the 20 % line. */
    public static final double DRIVER_ASSAY = 0.1975;

    private static final long GRID = 100_000L;

    private static long grid(double v) {
        return Math.round(v * GRID);
    }

    /** The fraction on the grid, refused unless it is a fraction of the heavy metal the driver can supply. */
    private static long checkedFraction(double driverFraction) {
        long f = grid(driverFraction);
        if (!(f > 0 && f < grid(DRIVER_ASSAY))) {
            throw new IllegalArgumentException("driver fraction " + driverFraction + " must be above 0 and below the driver assay " + DRIVER_ASSAY);
        }
        return f;
    }

    /** Ingots of driver uranium (at {@link #DRIVER_ASSAY}) in {@code productIngots} of thorium fuel; exact, for display. */
    public static double driverIngots(int productIngots, double driverFraction) {
        return productIngots * (double) checkedFraction(driverFraction) / grid(DRIVER_ASSAY);
    }

    /** Whole ingots of natural uranium the enrichment of the driver takes, rounded up. */
    public static int feedIngots(int productIngots, double driverFraction) {
        long f = checkedFraction(driverFraction);
        long ad = grid(DRIVER_ASSAY), xf = grid(EnrichmentMassBalance.NATURAL_ASSAY), xt = grid(EnrichmentMassBalance.TAILS_ASSAY);
        long numerator = (long) productIngots * f * (ad - xt);
        long denominator = ad * (xf - xt);
        return Math.toIntExact(Math.floorDiv(numerator + denominator - 1, denominator));
    }

    /** Whole ingots of depleted uranium the enrichment leaves, rounded down (never more than went in beyond the driver). */
    public static int tailsIngots(int productIngots, double driverFraction) {
        long f = checkedFraction(driverFraction);
        long ad = grid(DRIVER_ASSAY), xf = grid(EnrichmentMassBalance.NATURAL_ASSAY), xt = grid(EnrichmentMassBalance.TAILS_ASSAY);
        long numerator = (long) productIngots * f * (ad - xf);
        long denominator = ad * (xf - xt);
        return Math.toIntExact(Math.floorDiv(numerator, denominator));
    }

    /** Whole ingots of thorium: the heavy metal the driver does not make up, rounded up. */
    public static int thoriumIngots(int productIngots, double driverFraction) {
        long f = checkedFraction(driverFraction);
        long ad = grid(DRIVER_ASSAY);
        long numerator = (long) productIngots * (ad - f);
        return Math.toIntExact(Math.floorDiv(numerator + ad - 1, ad));
    }

    // --- the U-233 driver (BREEDING-TARGETS.md): the driver made in the reactor from thorium instead of enriched from uranium ---

    /** Grams in one U-233 ingot: what one finished thorium target makes ({@link BreedingTargets#U233_INGOT_GRAMS}). */
    public static final long U233_INGOT_GRAMS = Math.round(BreedingTargets.U233_INGOT_GRAMS);

    /** Grams of heavy metal in one ingot-unit: an assembly is {@value SpentFuel#INGOTS_PER_ASSEMBLY} of them and 180 kg. */
    public static final long GRAMS_PER_INGOT_UNIT = 45_000L;

    /**
     * Whole U-233 ingots that make the driver of {@code productIngots} of thorium fuel when the driver is bred U-233 and not
     * enriched uranium: the driver fraction of the heavy metal's mass, in whole ingots, rounded up. Nothing is enriched, so there is
     * no depleted uranium back.
     */
    public static int u233Ingots(int productIngots, double driverFraction) {
        long f = grid(driverFraction);
        if (!(f > 0 && f < grid(DRIVER_ASSAY))) {
            throw new IllegalArgumentException("driver fraction " + driverFraction + " must be above 0 and below the driver assay " + DRIVER_ASSAY);
        }
        long numerator = (long) productIngots * GRAMS_PER_INGOT_UNIT * f;
        long denominator = U233_INGOT_GRAMS * GRID;
        return Math.toIntExact(Math.floorDiv(numerator + denominator - 1, denominator));
    }

    /** Whole thorium ingots of an assembly whose driver is U-233: the heavy metal less the driver's few percent, rounded up. */
    public static int thoriumIngotsWithU233(int productIngots, double driverFraction) {
        long f = grid(driverFraction);
        long numerator = (long) productIngots * (GRID - f);
        return Math.toIntExact(Math.floorDiv(numerator + GRID - 1, GRID));
    }
}
