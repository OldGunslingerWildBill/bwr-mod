package dev.bwr.core.fuel;

/**
 * The uranium mass balance of an enrichment plant, in one place.
 *
 * <p>An enrichment plant splits a feed stream (assay {@code xf}) into a product
 * stream (assay {@code xp}) and a tails stream (assay {@code xt}). Conserving
 * total uranium and U-235 gives the feed needed per unit of product:
 *
 * <pre>    feed / product = (xp - xt) / (xf - xt)        tails = feed - product</pre>
 *
 * <p>With natural feed (0.711 %) and 0.25 % tails that is 1.0 for natural
 * uranium, 5.3 for 2.7 %, 7.1 for 3.5 %, 10.2 for 4.95 %, 16.8 for 8 %, 42.3 for
 * 19.75 % and 42.8 for 20 %. The fuel fabricator's item route multiplies these by
 * a fixed amount of product per assembly, and what does not become product comes
 * back as depleted uranium.
 *
 * <h2>Why integers</h2>
 * Assays are put on a 1e-5 grid (0.711 % is 711) and the counts are integer
 * arithmetic, so the rounding is exact: a natural assembly costs exactly its
 * product, never one ingot more because 1.0 came out as 1.0000000000000002.
 * Every shipped assay (0.711, 1.2, 1.4, 2.7, 3.5, 4.95, 8, 19.75, 20 %) is on
 * the grid. An assay from a datapack that is not is rounded to it, which is a
 * precision of 0.001 percentage points.
 *
 * <h2>Rounding</h2>
 * The feed is rounded <b>up</b> (nothing is made out of a fraction of an ingot)
 * and the tails are rounded <b>down</b> (the machine never returns more than
 * went in), so it can only lose to rounding, never gain. The tails are the floor
 * of the exact difference, {@code floor(P * r) - P}; the rounded-up feed minus
 * the product can be one more than that.
 */
public final class EnrichmentMassBalance {

    private EnrichmentMassBalance() {
    }

    /** Assay of natural uranium, weight fraction of U-235. */
    public static final double NATURAL_ASSAY = 0.00711;

    /** Assay of the tails. 0.25 % is the figure a real enrichment plant is run to. */
    public static final double TAILS_ASSAY = 0.0025;

    private static final long GRID = 100_000L;

    /** An assay (weight fraction) on the 1e-5 grid. */
    private static long grid(double assay) {
        return Math.round(assay * GRID);
    }

    /**
     * Feed per product at these assays, the exact ratio. For display and
     * documentation; counts come from {@link #feedUnits}.
     */
    public static double feedPerProduct(double productAssay, double feedAssay, double tailsAssay) {
        long p = checked(productAssay, feedAssay, tailsAssay);
        return (double) (p - grid(tailsAssay)) / (double) (grid(feedAssay) - grid(tailsAssay));
    }

    /** {@link #feedPerProduct(double, double, double)} for natural feed and the standard tails. */
    public static double feedPerProduct(double productAssay) {
        return feedPerProduct(productAssay, NATURAL_ASSAY, TAILS_ASSAY);
    }

    /** Whole units of feed needed to make {@code productUnits} of product, rounded up. */
    public static int feedUnits(int productUnits, double productAssay, double feedAssay, double tailsAssay) {
        long p = checked(productAssay, feedAssay, tailsAssay);
        long numerator = (long) productUnits * (p - grid(tailsAssay));
        long denominator = grid(feedAssay) - grid(tailsAssay);
        return Math.toIntExact(Math.floorDiv(numerator + denominator - 1, denominator));
    }

    /** Whole units of tails left over when making {@code productUnits} of product, rounded down. */
    public static int tailsUnits(int productUnits, double productAssay, double feedAssay, double tailsAssay) {
        long p = checked(productAssay, feedAssay, tailsAssay);
        long numerator = (long) productUnits * (p - grid(tailsAssay));
        long denominator = grid(feedAssay) - grid(tailsAssay);
        return Math.toIntExact(Math.floorDiv(numerator, denominator) - productUnits);
    }

    /** Feed units for natural feed and the standard tails. */
    public static int feedUnits(int productUnits, double productAssay) {
        return feedUnits(productUnits, productAssay, NATURAL_ASSAY, TAILS_ASSAY);
    }

    /** Tails units for natural feed and the standard tails. */
    public static int tailsUnits(int productUnits, double productAssay) {
        return tailsUnits(productUnits, productAssay, NATURAL_ASSAY, TAILS_ASSAY);
    }

    private static long checked(double productAssay, double feedAssay, double tailsAssay) {
        long p = grid(productAssay);
        long f = grid(feedAssay);
        long t = grid(tailsAssay);
        if (!(t > 0 && t < f)) {
            throw new IllegalArgumentException("tails assay " + tailsAssay
                    + " must be above zero and below the feed assay " + feedAssay);
        }
        if (p < f) {
            throw new IllegalArgumentException("product assay " + productAssay
                    + " is below the feed assay " + feedAssay + "; an enrichment plant cannot make it");
        }
        if (p >= GRID) {
            throw new IllegalArgumentException("product assay " + productAssay + " must be below 1");
        }
        return p;
    }
}
