package dev.bwr.core.fuel;

import java.util.List;

/**
 * What a finished irradiation target holds: the physics behind the reactor-bred isotopes (BREEDING-TARGETS.md).
 *
 * <h2>The calibration</h2>
 * The mod's cobalt target needs {@code 72 h} of running at rated local flux, a gameplay time "matched to long fuel cycles".
 * This class gives that time a meaning: <b>72 game hours stand for one 18-month BWR cycle</b> (13,140 h at rated flux), so one
 * game hour is {@link #FLUENCE_HOURS_PER_GAME_HOUR} = 182.5 real hours of neutron exposure, at a thermal flux of
 * {@link #THERMAL_FLUX} = 3e13 n/cm2/s (a typical in-core thermal flux of a BWR; a design choice, not a measurement). At those
 * two numbers the cobalt cassette (10 kg of cobalt-59, sigma 37.2 b) is 4.7 % converted, 474 g of cobalt-60 (about 0.5 MCi), the
 * size of an industrial source batch, so the calibration is not absurd.
 *
 * <h2>The chain</h2>
 * A target is a short chain of nuclides {@code N0 -> N1 -> ... -> Nn}. Each step is a thermal capture (rate
 * {@code sigma x flux}) or a decay that feeds the next member (Bi-210 to Po-210, Pa-233 to U-233, U-237 to Np-237, Np-238 to
 * Pu-238, Ra-227 to Ac-227). Every member is removed by its own decay and by burning (capture or fission it does not pass on:
 * U-233's 576 b, Np-238's fission, Ac-227's 890 b), which is why a long irradiation saturates. The inventory obeys the linear
 * system {@code dN/dt = A N}, solved exactly with a matrix exponential, so there is no time step to get wrong.
 *
 * <p>What is deliberately left out, each of which would change a yield by tens of percent but not its order of magnitude:
 * resonance capture (only the thermal cross section is used: yields are slightly low), self-shielding of a thick target
 * (slightly high), the neutron spectrum (a fast-flux (n,2n) route to U-237 that adds a little neptunium), and the plutonium that a
 * uranium target also breeds (it stays in the cassette, which the mod has no way to reprocess).
 */
public final class BreedingTargets {

    private BreedingTargets() {
    }

    /** Thermal flux at rated power in a cassette position, n/cm2/s (design choice). */
    public static final double THERMAL_FLUX = 3.0e13;

    /** One game hour of rated-flux exposure is this many hours of real neutron exposure: 72 game hours = 13,140 h = 18 months. */
    public static final double FLUENCE_HOURS_PER_GAME_HOUR = 13_140.0 / 72.0;

    private static final double DAY = 86_400.0;
    private static final double YEAR = 365.25 * DAY;
    private static final double BARN = 1.0e-24;

    /**
     * One member of a chain.
     *
     * @param name          for the documentation and tests
     * @param massNumber    A, the mass per mole in grams to the accuracy that matters here
     * @param halfLifeSeconds 0 for stable
     * @param captureBarns  thermal capture that makes the next member (0 for the last, whose further captures count as loss)
     * @param lossBarns     thermal removal that does not feed the next member (fission, capture to something not followed)
     * @param decayFeeds    true when this member's decay makes the next member
     */
    public record Nuclide(String name, double massNumber, double halfLifeSeconds, double captureBarns, double lossBarns, boolean decayFeeds) {
    }

    /**
     * A target kind's route.
     *
     * @param kind          the cassette
     * @param payloadGrams  grams of the starting element in the cassette
     * @param startFraction share of the payload that is the first nuclide (depleted uranium is 0.25 % uranium-235, the rest is
     *                      uranium-238, which this chain does not follow)
     * @param chain         the nuclides, the last the product
     * @param hours         the cassette's rated exposure, game hours (the kind's own exposure, stated again for the tables)
     */
    public record Route(CoreInsert.Kind kind, double payloadGrams, double startFraction, List<Nuclide> chain, double hours) {
        public Nuclide product() {
            return chain.get(chain.size() - 1);
        }
    }

    public static final Route BISMUTH = new Route(CoreInsert.Kind.BISMUTH_TARGET, 10_000, 1.0, List.of(
            // sigma to the 5.01-day ground state, 16.2 mb (Al-Khasawneh 2021); the other 17.6 mb of the 33.8 mb goes to the Bi-210m isomer
            new Nuclide("Bi-209", 209, 0, 0.0162, 0, false),
            new Nuclide("Bi-210", 210, 5.012 * DAY, 0, 0, true),
            new Nuclide("Po-210", 210, 138.376 * DAY, 0, 0, false)), 96);

    public static final Route THORIUM = new Route(CoreInsert.Kind.THORIUM_TARGET, 45_000, 1.0, List.of(
            new Nuclide("Th-232", 232, 0, 7.37, 0, false),
            // Th-233 (22 min) is folded into the capture; Pa-233 itself captures 40 b to Pa-234, lost to the chain
            new Nuclide("Pa-233", 233, 26.975 * DAY, 0, 40.0, true),
            new Nuclide("U-233", 233, 1.592e5 * YEAR, 0, 576.4, false)), 168);

    public static final Route URANIUM = new Route(CoreInsert.Kind.URANIUM_TARGET, 45_000, EnrichmentMassBalance.TAILS_ASSAY, List.of(
            new Nuclide("U-235", 235, 0, 98.3, 585.0, false),
            new Nuclide("U-236", 236, 0, 5.1, 0, false),
            new Nuclide("U-237", 237, 6.752 * DAY, 0, 0, true),
            new Nuclide("Np-237", 237, 2.144e6 * YEAR, 0, 175.9, false)), 240);

    /** The neptunium target holds eight neptunium ingots, each what one uranium target made (about 0.2 g). */
    public static final Route NEPTUNIUM = new Route(CoreInsert.Kind.NEPTUNIUM_TARGET, 1.6, 1.0, List.of(
            new Nuclide("Np-237", 237, 0, 175.9, 0, false),
            new Nuclide("Np-238", 238, 2.099 * DAY, 0, 2100.0, true),
            new Nuclide("Pu-238", 238, 87.7 * YEAR, 0, 560.0, false)), 120);

    /** Eight radium items of 10 mg of radium-226 each. */
    public static final Route RADIUM = new Route(CoreInsert.Kind.RADIUM_TARGET, 0.08, 1.0, List.of(
            new Nuclide("Ra-226", 226, 0, 12.8, 0, false),
            new Nuclide("Ra-227", 227, 42.2 * 60.0, 0, 0, true),
            new Nuclide("Ac-227", 227, 21.772 * YEAR, 0, 890.0, false)), 168);

    /** Eight rare earth concentrates stand for half a kilogram of thulium. */
    public static final Route THULIUM = new Route(CoreInsert.Kind.THULIUM_TARGET, 500, 1.0, List.of(
            new Nuclide("Tm-169", 169, 0, 100.2, 0, false),
            new Nuclide("Tm-170", 170, 128.6 * DAY, 0, 100.0, false)), 48);

    /** The calibration route: 10 kg of cobalt-59 to cobalt-60 (sigma 37.2 b; the Co-60 burn-up of about 2 b is from the evaluated files, not opened). */
    public static final Route COBALT_REFERENCE = new Route(CoreInsert.Kind.COBALT_TARGET, 10_000, 1.0, List.of(
            new Nuclide("Co-59", 59, 0, 37.2, 0, false),
            new Nuclide("Co-60", 60, 5.2714 * YEAR, 0, 2.0, false)), 72);

    /**
     * Grams of the product in one ingot of it (what the data map says): a finished cassette's yield, rounded to two or three figures,
     * so that one cassette makes one ingot. The tests keep each within 3 % of {@link #cassetteYieldGrams}.
     */
    public static final double PO210_INGOT_GRAMS = 0.082, U233_INGOT_GRAMS = 480.0, NP237_INGOT_GRAMS = 0.20,
            PU238_INGOT_GRAMS = 0.28, AC227_INGOT_GRAMS = 0.00103, TM170_INGOT_GRAMS = 19.0;

    public static final List<Route> ROUTES = List.of(BISMUTH, THORIUM, URANIUM, NEPTUNIUM, RADIUM, THULIUM);

    /** The route of a target kind, or null for a kind that breeds nothing. */
    public static Route routeOf(CoreInsert.Kind kind) {
        for (Route r : ROUTES) {
            if (r.kind() == kind) {
                return r;
            }
        }
        return null;
    }

    /** Real hours of rated-flux neutron exposure that {@code gameSeconds} of rated running stand for. */
    public static double fluenceSeconds(double gameSeconds) {
        return gameSeconds * FLUENCE_HOURS_PER_GAME_HOUR;
    }

    /** Grams of the product after {@code gameSeconds} of rated-flux running (the route's whole payload starts as the first nuclide). */
    public static double productGrams(Route route, double gameSeconds) {
        return inventoryGrams(route, gameSeconds)[route.chain().size() - 1];
    }

    /** Grams of every chain member after {@code gameSeconds} of rated running. */
    public static double[] inventoryGrams(Route route, double gameSeconds) {
        int n = route.chain().size();
        double t = fluenceSeconds(Math.max(0.0, gameSeconds));
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            Nuclide m = route.chain().get(i);
            double lambda = m.halfLifeSeconds() > 0 ? Math.log(2) / m.halfLifeSeconds() : 0;
            a[i][i] -= lambda + (m.captureBarns() + m.lossBarns()) * BARN * THERMAL_FLUX;
            if (i + 1 < n) {
                a[i + 1][i] += m.captureBarns() * BARN * THERMAL_FLUX + (m.decayFeeds() ? lambda : 0);
            }
        }
        double[][] e = expm(a, t);
        double[] out = new double[n];
        double atoms0 = route.payloadGrams() * route.startFraction() / route.chain().get(0).massNumber();
        for (int i = 0; i < n; i++) {
            out[i] = e[i][0] * atoms0 * route.chain().get(i).massNumber();
        }
        return out;
    }

    /** Grams of product a finished cassette of this kind holds. */
    public static double cassetteYieldGrams(CoreInsert.Kind kind) {
        Route r = routeOf(kind);
        if (kind == CoreInsert.Kind.COBALT_TARGET) {
            r = COBALT_REFERENCE;
        }
        return r == null ? 0.0 : productGrams(r, r.hours() * 3600.0);
    }

    /** exp(a t) by scaling and squaring of a short Taylor series; a is tiny (at most 4 x 4). */
    private static double[][] expm(double[][] a, double t) {
        int n = a.length;
        double norm = 0;
        for (double[] row : a) {
            double s = 0;
            for (double v : row) {
                s += Math.abs(v * t);
            }
            norm = Math.max(norm, s);
        }
        int squarings = norm > 0 ? Math.max(0, (int) Math.ceil(Math.log(norm) / Math.log(2)) + 2) : 0;
        double scale = t / Math.pow(2, squarings);
        double[][] x = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                x[i][j] = a[i][j] * scale;
            }
        }
        double[][] result = identity(n);
        double[][] term = identity(n);
        for (int k = 1; k <= 24; k++) {
            term = scaleBy(multiply(term, x), 1.0 / k);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    result[i][j] += term[i][j];
                }
            }
        }
        for (int i = 0; i < squarings; i++) {
            result = multiply(result, result);
        }
        return result;
    }

    private static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            m[i][i] = 1.0;
        }
        return m;
    }

    private static double[][] scaleBy(double[][] m, double f) {
        for (double[] row : m) {
            for (int j = 0; j < row.length; j++) {
                row[j] *= f;
            }
        }
        return m;
    }

    private static double[][] multiply(double[][] x, double[][] y) {
        int n = x.length;
        double[][] z = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                for (int j = 0; j < n; j++) {
                    z[i][j] += x[i][k] * y[k][j];
                }
            }
        }
        return z;
    }
}
