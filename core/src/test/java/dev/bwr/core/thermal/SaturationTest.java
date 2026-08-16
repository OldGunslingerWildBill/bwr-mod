package dev.bwr.core.thermal;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;

/**
 * Acceptance test 8: the saturation curve, against Keenan &amp; Keyes.
 *
 * <p>The vessel is a saturated system — pressure is tracked and everything
 * thermal is read off this curve — so an error here is an error in coolant
 * temperature, in subcooling, in void fraction and therefore in reactivity.
 * The correlation {@code T_sat(degF) = 115.1 * P(psia)^0.225} is claimed to be
 * good to 0.4 degF across the operating band; this checks the claim rather than
 * taking it.
 */
public final class SaturationTest {

    private SaturationTest() {
    }

    /** Keenan &amp; Keyes saturation temperatures, {psia, degF}. */
    private static final double[][] STEAM_TABLE_DEG_F = {
            {800.0, 518.23},
            {1000.0, 544.58},
            {1100.0, 556.28},
            {1250.0, 572.42},
            {1400.0, 587.07},
    };

    /**
     * <b>Acceptance 8.</b> 1000 psia within 0.5 degF of 544.58, and rated dome
     * pressure landing on 287.4 degC.
     */
    public static void test01_saturationTemperatureAtOneThousandPsia() {
        double fit = Saturation.temperatureFahrenheitFromPsia(1000.0);
        Check.note("T_sat(1000 psia) = %.4f degF, table 544.58 degF, error %+.4f degF",
                fit, fit - 544.58);
        Check.absolute(544.58, fit, 0.5, "T_sat at 1000 psia");

        double ratedC = Saturation.temperatureCelsiusFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
        Check.note("rated dome %.0f psig = %.2f psia -> %.3f degC",
                PhysicalConstants.RATED_DOME_PRESSURE_PSIG,
                Saturation.psiaFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG), ratedC);
        Check.absolute(287.4, ratedC, 0.1, "coolant temperature at rated dome pressure");

        // The spec draft's 1150 psig was an error; the BWR/6 rated dome pressure
        // is 1025 psig. Guard the constant so a later edit cannot quietly move it.
        Check.exactly(1025.0, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, "rated dome pressure, psig");
    }

    /** The whole 800 to 1400 psia band, against the tabulated values. */
    public static void test02_saturationCurveAcrossTheOperatingBand() {
        double worstError = 0.0;
        for (double[] point : STEAM_TABLE_DEG_F) {
            double fit = Saturation.temperatureFahrenheitFromPsia(point[0]);
            double error = fit - point[1];
            worstError = Math.max(worstError, Math.abs(error));
            Check.note("%7.1f psia: table %.2f degF, fit %.2f degF, error %+.3f degF",
                    point[0], point[1], fit, error);
            Check.absolute(point[1], fit, 0.5, "T_sat at " + point[0] + " psia");
        }
        Check.note("worst error across 800-1400 psia: %.3f degF", worstError);
        Check.lessThan(0.4, worstError, "worst T_sat error across the operating band");
    }

    /** Pressure and temperature must invert cleanly in both directions. */
    public static void test03_pressureTemperatureInversionRoundTrips() {
        for (double psia : new double[]{200.0, 500.0, 800.0, 1039.696, 1250.0, 1400.0}) {
            double degF = Saturation.temperatureFahrenheitFromPsia(psia);
            double back = Saturation.pressurePsiaFromTemperatureFahrenheit(degF);
            Check.relative(psia, back, 1.0e-12, "psia -> degF -> psia at " + psia);
        }
        for (double psig : new double[]{0.0, 400.0, 1025.0, 1375.0}) {
            double degC = Saturation.temperatureCelsiusFromPsig(psig);
            double back = Saturation.pressurePsigFromTemperatureCelsius(degC);
            Check.absolute(psig, back, 1.0e-9, "psig -> degC -> psig at " + psig);
        }
    }

    /**
     * The analytic slope must match a finite difference of the curve it claims
     * to differentiate. That slope is what drives level shrink on a pressure
     * rise, so a wrong sign or a missing factor shows up as inventory appearing
     * from nowhere.
     */
    public static void test04_saturationSlopeMatchesFiniteDifference() {
        for (double psia : new double[]{800.0, 1039.696, 1250.0}) {
            double h = 0.01;
            double numeric = (Saturation.temperatureFahrenheitFromPsia(psia + h)
                    - Saturation.temperatureFahrenheitFromPsia(psia - h)) / (2.0 * h);
            double analytic = Saturation.temperatureSlopeFahrenheitPerPsi(psia);
            Check.relative(numeric, analytic, 1.0e-6, "dT_sat/dP at " + psia + " psia");
        }
        double slopeC = Saturation.temperatureSlopeCelsiusPerPsi(1039.696);
        Check.note("dT_sat/dP at rated = %.5f degC/psi", slopeC);
        Check.absolute(0.066, slopeC, 0.005, "dT_sat/dP at rated dome pressure, degC/psi");
    }

    /**
     * The enthalpy fits, against the steam tables, across the operating band.
     *
     * <p><b>{@code h_g} is not checked against {@code h_f + h_fg} here, and the
     * javadoc that used to claim it was must not come back.</b>
     * {@code Saturation.vapourEnthalpyKJPerKg} <i>is</i> literally
     * {@code liquidEnthalpyKJPerKg(p) + latentHeatKJPerKg(p)} — the same two
     * calls, the same argument, the same addition order — so
     * {@code Check.exactly(hf + hfg, hg, ...)} compared the sum against itself
     * and could not fail for any implementation. Worse, it enforced the opposite
     * of what it claimed: it would have passed for any implementation that is
     * that sum and failed for any genuinely independent one, while telling the
     * next reader that h_g had an independent check.
     *
     * <p>What replaces it is a real one. Each pressure carries its own tabulated
     * h_f and h_fg from Keenan &amp; Keyes, and h_g is checked against their sum
     * — a number that comes from the tables and not from this class. An
     * independent h_g fit that ran 3% low would fail here; so would a broken
     * h_f, a broken h_fg, or a vapour enthalpy that lost the latent heat term.
     */
    public static void test05_enthalpyFitsAgreeWithTheSteamTables() {
        // {psia, h_f kJ/kg, h_fg kJ/kg} from Keenan & Keyes. h_g is their sum,
        // which is where the expected vapour enthalpy below comes from.
        double[][] table = {
                {800.0, 1185.8, 1604.0},
                {1000.0, 1262.0, 1512.0},
                {1039.696, 1275.0, 1495.0},
                {1250.0, 1345.8, 1409.0},
                {1400.0, 1392.8, 1369.0},
        };

        double worstVapourError = 0.0;
        for (double[] point : table) {
            double psia = point[0];
            double tableHf = point[1];
            double tableHfg = point[2];
            double tableHg = tableHf + tableHfg;

            double hf = Saturation.liquidEnthalpyKJPerKg(psia);
            double hfg = Saturation.latentHeatKJPerKg(psia);
            double hg = Saturation.vapourEnthalpyKJPerKg(psia);

            Check.note("%8.1f psia: h_f %7.1f (table %7.1f), h_fg %7.1f (table %7.1f), "
                            + "h_g %7.1f (table %7.1f, %+.2f%%)",
                    psia, hf, tableHf, hfg, tableHfg, hg, tableHg,
                    100.0 * (hg - tableHg) / tableHg);

            Check.relative(tableHf, hf, 0.01, "saturated liquid enthalpy at " + psia + " psia");
            Check.relative(tableHfg, hfg, 0.01, "latent heat at " + psia + " psia");
            Check.relative(tableHg, hg, 0.005,
                    "saturated vapour enthalpy at " + psia + " psia, against the tabulated h_f + h_fg");
            worstVapourError = Math.max(worstVapourError, Math.abs(hg - tableHg) / tableHg);
        }
        Check.note("worst h_g error against the tabulated sum across 800-1400 psia: %.3f%%",
                100.0 * worstVapourError);

        double psia = 1039.696;
        Check.relative(37.5, Saturation.vapourDensityKgPerM3(psia), 0.02, "steam density at rated");
        Check.relative(738.0, Saturation.liquidDensityKgPerM3(psia), 0.02, "water density at rated");
    }

    /**
     * A vacuum must not produce NaN. Every correlation is a power law and the
     * pressure floor is the guard that keeps a condensing transient from
     * poisoning the whole state with NaN.
     */
    public static void test06_correlationsSurviveAVacuum() {
        for (double psia : new double[]{-100.0, 0.0, 1.0e-9, Double.NaN}) {
            Check.finiteAndPositive(Saturation.temperatureFahrenheitFromPsia(psia),
                    "T_sat at " + psia + " psia");
            Check.finiteAndPositive(Saturation.vapourDensityKgPerM3(psia),
                    "steam density at " + psia + " psia");
            Check.finiteAndPositive(Saturation.liquidDensityKgPerM3(psia),
                    "water density at " + psia + " psia");
            Check.finiteAndPositive(Saturation.latentHeatKJPerKg(psia),
                    "latent heat at " + psia + " psia");
        }
    }
}
