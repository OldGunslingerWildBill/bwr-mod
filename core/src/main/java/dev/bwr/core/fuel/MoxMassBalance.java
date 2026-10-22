package dev.bwr.core.fuel;

/**
 * What a mixed-oxide assembly costs in plutonium and depleted uranium, in one place: the fuel fabricator's MOX route
 * and the documentation both come from here.
 *
 * <p>An assembly is {@value SpentFuel#INGOTS_PER_ASSEMBLY} ingot-units of heavy metal, the same model as the
 * uranium route ({@link EnrichmentMassBalance}) and as the reprocessing plant that makes the plutonium
 * ({@link SpentFuel}). The plutonium fraction is the MOX fuel type's own nominal enrichment (its effective fissile
 * fraction, the datapack's number), counted as the plutonium's mass fraction. Plutonium is counted in nuggets, a ninth
 * of an ingot, the unit the plant produces it in; the rest of the heavy metal is the depleted-uranium matrix, in whole
 * ingots.
 *
 * <p>Like the uranium route it can lose to rounding and never gain: plutonium is rounded up to whole nuggets, and the
 * matrix up to whole ingots, so an assembly always holds at least the heavy metal it was stamped with. Assays are put
 * on the same 1e-5 grid as {@link EnrichmentMassBalance} so the rounding is exact (7 % is 7,000, never 7.000000001).
 */
public final class MoxMassBalance {

    private MoxMassBalance() {
    }

    private static final long GRID = 100_000L;

    /** Nuggets of plutonium in {@code productIngots} of MOX at this plutonium mass fraction, rounded up. */
    public static int plutoniumNuggets(int productIngots, double plutoniumFraction) {
        long fraction = Math.round(plutoniumFraction * GRID);
        if (fraction <= 0 || fraction >= GRID) {
            throw new IllegalArgumentException("plutonium fraction " + plutoniumFraction + " must be in (0,1)");
        }
        long numerator = (long) productIngots * SpentFuel.NINTHS_PER_INGOT * fraction;
        return Math.toIntExact(Math.floorDiv(numerator + GRID - 1, GRID));
    }

    /**
     * Ingots of depleted-uranium matrix: the heavy metal of the product that the plutonium does not make up, rounded up
     * to a whole ingot.
     */
    public static int matrixIngots(int productIngots, int plutoniumNuggets) {
        int totalNuggets = productIngots * SpentFuel.NINTHS_PER_INGOT;
        int matrixNuggets = Math.max(0, totalNuggets - plutoniumNuggets);
        return Math.floorDiv(matrixNuggets + SpentFuel.NINTHS_PER_INGOT - 1, SpentFuel.NINTHS_PER_INGOT);
    }
}
