package dev.bwr.core.thermal;

import dev.bwr.core.PhysicalConstants;

/**
 * Saturated water and steam properties as closed-form correlations.
 *
 * <p>The reactor vessel is a saturated system: {@code SPEC.md} section 6.1
 * fixes pressure and temperature to each other on the saturation curve, so the
 * model tracks pressure and reads everything else off this class. That is not a
 * simplification of a BWR, it is what a BWR <i>is</i> — a pot of boiling water
 * whose temperature is decided by the pressure the steam line lets it hold.
 *
 * <h2>The temperature correlation</h2>
 * <pre>
 *   T_sat(degF) = 115.1 * P(psia)^0.225
 *   P(psia)     = (T_sat(degF) / 115.1)^(1/0.225)
 * </pre>
 *
 * <p><b>Validation error band.</b> Checked against Keenan &amp; Keyes steam
 * tables: <b>error is under 0.4 degF across the whole 800-1400 psia band</b>,
 * and {@code +0.02 degF} at 1000 psia. Point by point:
 * <pre>
 *   psia    table degF   fit degF   error
 *     14.7    212.00     210.71     -1.29
 *    500      467.01     465.95     -1.06
 *    800      518.23     517.93     -0.30
 *   1000      544.58     544.60     +0.02
 *   1100      556.28     556.40     +0.12
 *   1250      572.42     572.64     +0.22
 *   1400      587.07     587.43     +0.36
 * </pre>
 * It degrades to about 3.5 degF near 50-100 psia, which only matters during a
 * deep cooldown, and is still far better than the model needs. Below
 * {@link #MINIMUM_PRESSURE_PSIA} the correlation is not meaningful at all and
 * the input is floored; that floor is a numerical guard, not a physical limit.
 * At rated dome pressure, 1025 psig = 1039.7 psia, this gives 549.4 degF =
 * 287.4 degC, which is the BWR/6 rated condition [TTC 2.5.2.2].
 *
 * <h2>The fluid property correlations</h2>
 * Saturated vapour density, liquid density, latent heat and liquid enthalpy all
 * began as two-point power-law fits anchored at 800 and 1400 psia — the band the
 * vessel actually lives in, from the bottom of the pressure-control range to
 * past the 1375 psig ASME code limit. Each is validated in its own javadoc.
 * They live here rather than in {@link PressureVessel} because the pressure,
 * void and fuel models all need them and three private copies of a steam table
 * is three chances to disagree.
 *
 * <p>A useful internal consistency check on the set, at rated pressure:
 * {@code h_f + h_fg = 1278.5 + 1489.6 = 2768.1 kJ/kg}, against a table value of
 * about 2770. The fits were made independently and agree to 0.07%.
 *
 * <h2>Why none of them is a single power law any more</h2>
 * <b>The vessel does not stay in the 800-1400 psia band.</b> ADS, the SRVs, the
 * low-pressure ECCS pumps and RHR shutdown cooling all exist precisely to take
 * it out of that band, and an extrapolated power law goes wrong in whichever
 * direction its exponent points: the original liquid-density fit reads
 * {@code +63.9%} at atmospheric pressure and {@code +2740%} at the 0.1 psia
 * floor, latent heat {@code +120%} and {@code +800%} at the same two points, and
 * vapour density and liquid enthalpy — whose exponents point the other way —
 * {@code -46%} and {@code -10%} at atmospheric. Those are not cosmetic.
 * {@link PressureVessel} divides inventory by the liquid density to get
 * collapsed level and therefore whether the fuel is covered, divides core power
 * by {@code h_fg} to get boil-off rate, and holds its whole steam dome as
 * {@code V * rho_g(P)}. A depressurised vessel with its whole inventory intact
 * used to report a dry core.
 *
 * <p>So all four are now piecewise. Each is <b>bit-identical to the original
 * power law throughout 800-1400 psia</b>, so every calibration quoted against
 * that band still holds; outside it each hands over to a form that stays right:
 * <ul>
 *   <li><b>Liquid density</b> below 800 psia blends into
 *       {@link #liquidDensityFromTemperatureKgPerM3} evaluated at the saturation
 *       temperature. That quadratic was fitted on 20-250 degC and is within
 *       0.35% of the steam tables all the way from 14.7 to 800 psia, where the
 *       power law is out by up to 64%. The blend is a smoothstep over 400-800
 *       psia, so there is no step in level as a blowdown crosses it.</li>
 *   <li><b>Liquid enthalpy</b> below 800 psia does the same thing with the same
 *       window, into {@link #subcooledLiquidEnthalpyKJPerKg} at the saturation
 *       temperature — liquid water is nearly incompressible, so one quadratic in
 *       temperature serves the subcooled and the saturated case alike. Under
 *       1.1% from 14.7 psia up, against -10.4% for the power law.</li>
 *   <li><b>Latent heat</b> below 800 psia uses a Watson relation,
 *       {@code h_fg = C * (1 - T/T_crit)^n}, fitted to the same two anchors the
 *       power law would use if it could reach them: 2257 kJ/kg at 14.696 psia and
 *       1604 kJ/kg at 800 psia. It is within 0.61% across that whole range and
 *       meets the power law at 800 psia to 0.01%, so the handover is seamless.
 *       Above 1400 psia it blends back to the same Watson form, which carries
 *       {@code h_fg} to <b>zero at the critical pressure</b> instead of the
 *       954 kJ/kg the power law reported at 5015 psia — water has no two-phase
 *       region above 3208 psia and the model should not pretend otherwise.</li>
 *   <li><b>Vapour density</b> is the odd one out and is <i>still</i> a power
 *       law — four of them, in series, each fitted through the steam-table
 *       density at both its ends. It gets no blend and no corresponding-states
 *       form because {@link #pressurePsiaFromVapourDensityKgPerM3} has to invert
 *       it exactly (that inverse is the fixed-volume dome closure) and
 *       {@link #vapourDensitySlopeKgPerM3PerPsi} has to differentiate it
 *       exactly, and a blend of two forms is neither invertible in closed form
 *       nor worth the loss. Under 1% from the 0.1 psia floor to 1400 psia.</li>
 * </ul>
 *
 * <p><b>The three vapour-density functions are one object with three faces.</b>
 * Value, inverse and slope read the same segment table; change one and the other
 * two have to change with it, or the vessel's pressure equation stops closing.
 *
 * <h2>What this class is not</h2>
 * Nothing here judges a pressure or a temperature. There is no operating range,
 * no limit, no alarm. It converts numbers.
 *
 * <p>Pure static, stateless, no Minecraft imports.
 */
public final class Saturation {

    private Saturation() {
    }

    // ---------------------------------------------------------------
    // The saturation temperature correlation
    // ---------------------------------------------------------------

    /** Leading coefficient of the T_sat correlation, degF at 1 psia. */
    public static final double T_SAT_COEFFICIENT_DEG_F = 115.1;

    /** Exponent of the T_sat correlation. */
    public static final double T_SAT_EXPONENT = 0.225;

    /**
     * Numerical floor on any pressure handed to a correlation, psia.
     *
     * <p>Every correlation here is a power law and every power law needs a
     * positive argument. A vessel driven to a hard vacuum by a condensing
     * transient would otherwise produce NaN and poison the whole state. This is
     * a guard on the arithmetic; it says nothing about what pressures the plant
     * can reach.
     */
    public static final double MINIMUM_PRESSURE_PSIA = 0.1;

    /**
     * Critical pressure of water, psia [Keenan &amp; Keyes]. Above it there is no
     * liquid/vapour distinction at all: latent heat is zero, the two densities
     * are equal, and "saturated" means nothing.
     *
     * <p>This is <i>not</i> a limit on the state. {@link PressureVessel} can and
     * does reach higher pressures during an unrelieved isolation, and nothing
     * here stops it — this constant only tells {@link #latentHeatKJPerKg} where
     * its two-phase region ends so it stops reporting a latent heat that no
     * longer exists.
     */
    public static final double CRITICAL_PRESSURE_PSIA = 3208.0;

    /** Standard atmospheric pressure, psi. Mirrors {@link PhysicalConstants#ATMOSPHERIC_PSI}. */
    public static final double ATMOSPHERIC_PSI = PhysicalConstants.ATMOSPHERIC_PSI;

    /** Absolute zero offset, degrees C to kelvin. */
    private static final double KELVIN_OFFSET = 273.15;

    // ---------------------------------------------------------------
    // Unit conversion
    // ---------------------------------------------------------------

    /** Gauge pressure to absolute, psig to psia. */
    public static double psiaFromPsig(double pressurePsig) {
        return pressurePsig + ATMOSPHERIC_PSI;
    }

    /** Absolute pressure to gauge, psia to psig. */
    public static double psigFromPsia(double pressurePsia) {
        return pressurePsia - ATMOSPHERIC_PSI;
    }

    /** Fahrenheit to Celsius. */
    public static double celsiusFromFahrenheit(double degF) {
        return (degF - 32.0) / 1.8;
    }

    /** Celsius to Fahrenheit. */
    public static double fahrenheitFromCelsius(double degC) {
        return degC * 1.8 + 32.0;
    }

    /** Clamps a pressure to the domain the correlations are defined on, psia. */
    public static double clampPressurePsia(double pressurePsia) {
        if (!(pressurePsia > MINIMUM_PRESSURE_PSIA)) {
            return MINIMUM_PRESSURE_PSIA; // also catches NaN
        }
        return pressurePsia;
    }

    // ---------------------------------------------------------------
    // Pressure to temperature
    // ---------------------------------------------------------------

    /** Saturation temperature at an absolute pressure, degrees F. */
    public static double temperatureFahrenheitFromPsia(double pressurePsia) {
        return T_SAT_COEFFICIENT_DEG_F * Math.pow(clampPressurePsia(pressurePsia), T_SAT_EXPONENT);
    }

    /** Saturation temperature at an absolute pressure, degrees C. */
    public static double temperatureCelsiusFromPsia(double pressurePsia) {
        return celsiusFromFahrenheit(temperatureFahrenheitFromPsia(pressurePsia));
    }

    /** Saturation temperature at a gauge pressure, degrees F. */
    public static double temperatureFahrenheitFromPsig(double pressurePsig) {
        return temperatureFahrenheitFromPsia(psiaFromPsig(pressurePsig));
    }

    /**
     * Saturation temperature at a gauge pressure, degrees C. This is the bulk
     * coolant temperature of a saturated vessel. At rated 1025 psig it returns
     * 287.4 degC.
     */
    public static double temperatureCelsiusFromPsig(double pressurePsig) {
        return temperatureCelsiusFromPsia(psiaFromPsig(pressurePsig));
    }

    // ---------------------------------------------------------------
    // Temperature to pressure
    // ---------------------------------------------------------------

    /** Saturation pressure at a temperature, degrees F to psia. */
    public static double pressurePsiaFromTemperatureFahrenheit(double temperatureF) {
        if (!(temperatureF > 0.0)) {
            return MINIMUM_PRESSURE_PSIA;
        }
        return Math.pow(temperatureF / T_SAT_COEFFICIENT_DEG_F, 1.0 / T_SAT_EXPONENT);
    }

    /** Saturation pressure at a temperature, degrees C to psia. */
    public static double pressurePsiaFromTemperatureCelsius(double temperatureC) {
        return pressurePsiaFromTemperatureFahrenheit(fahrenheitFromCelsius(temperatureC));
    }

    /** Saturation pressure at a temperature, degrees F to psig. */
    public static double pressurePsigFromTemperatureFahrenheit(double temperatureF) {
        return psigFromPsia(pressurePsiaFromTemperatureFahrenheit(temperatureF));
    }

    /** Saturation pressure at a temperature, degrees C to psig. */
    public static double pressurePsigFromTemperatureCelsius(double temperatureC) {
        return psigFromPsia(pressurePsiaFromTemperatureCelsius(temperatureC));
    }

    // ---------------------------------------------------------------
    // Slope of the saturation curve
    // ---------------------------------------------------------------

    /**
     * Slope of the saturation curve, degrees F per psi. Analytic derivative of
     * the correlation: {@code 0.225 * T_sat / P}.
     */
    public static double temperatureSlopeFahrenheitPerPsi(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        return T_SAT_EXPONENT * temperatureFahrenheitFromPsia(p) / p;
    }

    /**
     * Slope of the saturation curve, degrees C per psi. About 0.066 degC/psi at
     * rated pressure — this is the number behind level shrink on a pressure
     * rise, because every psi of pressurisation demands that the entire vessel
     * water inventory absorb another 0.066 degC of sensible heat before it can
     * boil again.
     */
    public static double temperatureSlopeCelsiusPerPsi(double pressurePsia) {
        return temperatureSlopeFahrenheitPerPsi(pressurePsia) / 1.8;
    }

    // ---------------------------------------------------------------
    // Saturated vapour density
    // ---------------------------------------------------------------

    /** Leading coefficient of the saturated vapour density fit, kg/m3 at 1 psia. */
    public static final double VAPOUR_DENSITY_COEFFICIENT = 0.015867;

    /** Exponent of the saturated vapour density fit. */
    public static final double VAPOUR_DENSITY_EXPONENT = 1.1192;

    /** Pressure at and above which vapour density is the 800-1400 psia power law, psia. */
    public static final double VAPOUR_DENSITY_POWER_LAW_START_PSIA = 800.0;

    /**
     * Nodes of the piecewise saturated-vapour-density fit, {psia, kg/m3}.
     *
     * <p>The first three are Keenan &amp; Keyes saturated specific volumes
     * converted with {@code rho = 16.0185 / v_g[ft3/lbm]}: 26.80, 4.434 and
     * 1.1613 ft3/lbm at 14.696, 100 and 400 psia. The fourth is not tabulated —
     * it is the 800-1400 psia power law's <i>own</i> value at its lower limit,
     * so the handover into the operating band is exact by construction rather
     * than nearly exact. (The power law reads 28.160 there against a table
     * 28.147, and it is the power law the operating band is calibrated on.)
     *
     * <p>Each consecutive pair defines one power-law segment, fitted through
     * both its endpoints. The endpoints are shared, so the function is
     * continuous at every node to within a rounding error, which is what lets
     * {@link #pressurePsiaFromVapourDensityKgPerM3} stay an exact inverse.
     */
    private static final double[][] VAPOUR_DENSITY_NODES = {
            {14.696, 0.59770},
            {100.0, 3.61274},
            {400.0, 13.79370},
            {VAPOUR_DENSITY_POWER_LAW_START_PSIA,
                    VAPOUR_DENSITY_COEFFICIENT
                            * Math.pow(VAPOUR_DENSITY_POWER_LAW_START_PSIA, VAPOUR_DENSITY_EXPONENT)},
    };

    /** Exponent of each vapour-density segment, lowest first; the last is the power law's. */
    private static final double[] VAPOUR_DENSITY_SEGMENT_EXPONENTS = vapourDensitySegmentExponents();

    /** Leading coefficient of each vapour-density segment, lowest first. */
    private static final double[] VAPOUR_DENSITY_SEGMENT_COEFFICIENTS =
            vapourDensitySegmentCoefficients();

    private static double[] vapourDensitySegmentExponents() {
        double[] exponents = new double[VAPOUR_DENSITY_NODES.length];
        for (int i = 0; i < exponents.length - 1; i++) {
            exponents[i] = Math.log(VAPOUR_DENSITY_NODES[i + 1][1] / VAPOUR_DENSITY_NODES[i][1])
                    / Math.log(VAPOUR_DENSITY_NODES[i + 1][0] / VAPOUR_DENSITY_NODES[i][0]);
        }
        exponents[exponents.length - 1] = VAPOUR_DENSITY_EXPONENT;
        return exponents;
    }

    private static double[] vapourDensitySegmentCoefficients() {
        double[] coefficients = new double[VAPOUR_DENSITY_SEGMENT_EXPONENTS.length];
        for (int i = 0; i < coefficients.length - 1; i++) {
            coefficients[i] = VAPOUR_DENSITY_NODES[i][1]
                    / Math.pow(VAPOUR_DENSITY_NODES[i][0], VAPOUR_DENSITY_SEGMENT_EXPONENTS[i]);
        }
        coefficients[coefficients.length - 1] = VAPOUR_DENSITY_COEFFICIENT;
        return coefficients;
    }

    /**
     * Which segment owns a pressure. Segment {@code i} spans node {@code i} to
     * node {@code i + 1}; the last segment is the power law and is unbounded
     * above. Pressures below the first node extrapolate segment 0 downward,
     * which is deliberate — see {@link #vapourDensityKgPerM3}.
     */
    private static int vapourDensitySegment(double pressurePsia) {
        for (int i = 1; i < VAPOUR_DENSITY_NODES.length; i++) {
            if (pressurePsia < VAPOUR_DENSITY_NODES[i][0]) {
                return i - 1;
            }
        }
        return VAPOUR_DENSITY_NODES.length - 1;
    }

    /**
     * Saturated steam density, kg/m3.
     *
     * <p>Validation against Keenan &amp; Keyes specific volumes:
     * <pre>
     *   psia    table kg/m3   fit kg/m3   error     source
     *      0.1    0.005439     0.005536   +1.78%    segment 0, extrapolated
     *      1      0.04802      0.04802    -0.00%    segment 0, extrapolated
     *      5      0.21785      0.21736    -0.22%    segment 0, extrapolated
     *     14.696  0.59770      0.59770    +0.00%    segment 0
     *     50      1.88143      1.88540    +0.21%    segment 0
     *    100      3.61274      3.61274    +0.00%    segment 1
     *    200      7.00109      7.05925    +0.83%    segment 1
     *    300     10.37960     10.44569    +0.64%    segment 1
     *    400     13.79370     13.79370    +0.00%    segment 2
     *    500     17.25580     17.35654    +0.58%    segment 2
     *    600     20.79780     20.94070    +0.69%    segment 2
     *    800     28.14700     28.16003    +0.05%    power law
     *   1000     35.92400     36.14888    +0.63%    power law
     *   1039.7   37.5         37.75865    +0.69%    power law
     *   1250     46.38        46.40412    +0.05%    power law
     *   1400     53.11        52.67946    -0.81%    power law
     * </pre>
     * <b>Under 0.85% from 1 psia to 1400 psia</b>, +1.8% at the 0.1 psia floor,
     * and unchanged from the original two-point fit at and above 800 psia —
     * every calibration quoted against the operating band still holds to the
     * last bit.
     *
     * <p>Below 800 psia the single power law was abandoned. Its exponent is
     * above 1, so extrapolating downward deflates the density without limit:
     * -6.0% at 400 psia, -24.0% at 100, -46.3% at atmospheric and -77.8% at the
     * 0.1 psia floor. That is not cosmetic either: {@link PressureVessel} holds
     * its dome steam as {@code V_dome * rho_g(P)}, so during a blowdown the
     * dome was reported to hold half the steam it really does, and the
     * {@link #vapourDensitySlopeKgPerM3PerPsi} built from the same fit made the
     * dome look correspondingly stiff.
     *
     * <p>One power law cannot cover the range because the local exponent is not
     * constant: saturated steam is nearly ideal at atmospheric and strongly
     * non-ideal near the critical point, and {@code d ln rho_g / d ln P} climbs
     * from 0.94 at 14.7 psia to 1.12 by 1400 psia. Nor is there a Watson-style
     * corresponding-states form to reach for here, because the two things a
     * caller depends on — an exact analytic inverse and an exact analytic slope
     * — are exactly what a corresponding-states form would cost. So the fit
     * stays a power law and the <i>band</i> is split instead: four segments,
     * each fitted through the steam-table density at both its ends. Every
     * segment keeps a closed-form inverse and a closed-form derivative, and the
     * three of them stay mutually consistent because all three read the same
     * segment out of the same tables.
     *
     * <p>The price is a kink in the slope at each node — the value is
     * continuous, its derivative is not, and dome compressibility steps by up to
     * 9% as pressure crosses 800 psia. That is a model artefact and the real
     * {@code d rho_g / dP} is smooth, but it is the same trade
     * {@link #latentHeatKJPerKg} already makes at its own 800 psia handover, and
     * a few per cent step in a stiffness term is far cheaper than a 46% error in
     * the quantity itself.
     *
     * <p>Below 14.696 psia segment 0 is extrapolated rather than handed to
     * anything else. That is safe in a way the old extrapolation was not:
     * saturated steam approaches an ideal gas as pressure falls, the fit
     * approaches the right power of P as it does, and the residual at the 0.1
     * psia floor is +1.7% rather than -77.8%.
     */
    public static double vapourDensityKgPerM3(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        int segment = vapourDensitySegment(p);
        return VAPOUR_DENSITY_SEGMENT_COEFFICIENTS[segment]
                * Math.pow(p, VAPOUR_DENSITY_SEGMENT_EXPONENTS[segment]);
    }

    /**
     * Inverse of {@link #vapourDensityKgPerM3}: the pressure at which saturated
     * steam has the given density, psia.
     *
     * <p>This is the closure of a fixed-volume steam dome. Put a known mass of
     * steam in a known volume and the saturation curve decides the pressure —
     * no ideal gas law, no separate equation of state.
     *
     * <p>The segment is chosen by density rather than by pressure, against the
     * densities at the very same nodes. Density rises strictly with pressure, so
     * the two selections pick the same segment and the round trip is exact.
     * <b>Anything added to {@link #vapourDensityKgPerM3} has to be added here and
     * to {@link #vapourDensitySlopeKgPerM3PerPsi} in the same breath</b> — a
     * forward fit the inverse does not know about does not merely lose accuracy,
     * it breaks the vessel's pressure equation, which closes by round-tripping
     * dome mass through both.
     */
    public static double pressurePsiaFromVapourDensityKgPerM3(double densityKgPerM3) {
        if (!(densityKgPerM3 > 0.0)) {
            return MINIMUM_PRESSURE_PSIA;
        }
        int segment = VAPOUR_DENSITY_NODES.length - 1;
        for (int i = 1; i < VAPOUR_DENSITY_NODES.length; i++) {
            if (densityKgPerM3 < VAPOUR_DENSITY_NODES[i][1]) {
                segment = i - 1;
                break;
            }
        }
        return Math.pow(densityKgPerM3 / VAPOUR_DENSITY_SEGMENT_COEFFICIENTS[segment],
                1.0 / VAPOUR_DENSITY_SEGMENT_EXPONENTS[segment]);
    }

    /**
     * Rate of change of saturated steam density with pressure, kg/m3 per psi.
     * Analytic derivative of whichever segment owns the pressure:
     * {@code b_i * rho_g / P}. This is the compressibility of the steam dome and
     * one of the two terms that set how sharply pressure responds to a mismatch
     * between steam made and steam taken.
     */
    public static double vapourDensitySlopeKgPerM3PerPsi(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        return VAPOUR_DENSITY_SEGMENT_EXPONENTS[vapourDensitySegment(p)]
                * vapourDensityKgPerM3(p) / p;
    }

    // ---------------------------------------------------------------
    // Saturated liquid density
    // ---------------------------------------------------------------

    /** Leading coefficient of the saturated liquid density fit, kg/m3 at 1 psia. */
    public static final double LIQUID_DENSITY_COEFFICIENT = 2541.2;

    /** Exponent of the saturated liquid density fit. Negative: hotter water is thinner. */
    public static final double LIQUID_DENSITY_EXPONENT = -0.17909;

    /**
     * Pressure below which saturated liquid density comes entirely from the
     * saturation temperature, psia.
     */
    public static final double LIQUID_DENSITY_BLEND_START_PSIA = 400.0;

    /**
     * Pressure at and above which saturated liquid density comes entirely from
     * the 800-1400 psia power law, psia. Between this and
     * {@link #LIQUID_DENSITY_BLEND_START_PSIA} the two are smoothstepped
     * together.
     */
    public static final double LIQUID_DENSITY_BLEND_END_PSIA = 800.0;

    /**
     * Saturated liquid water density, kg/m3.
     *
     * <p>Validation:
     * <pre>
     *   psia    table kg/m3   fit kg/m3   error     source
     *     14.7    958.4        958.9      +0.05%    T_sat quadratic
     *    100      903.1        905.6      +0.28%    T_sat quadratic
     *    200      871.2        873.0      +0.21%    T_sat quadratic
     *    400      828.3        829.2      +0.11%    T_sat quadratic
     *    600      795.7        802.5      +0.85%    blend
     *    800      767.6        767.6      +0.00%    power law
     *   1000      741.9        737.5      -0.59%    power law
     *   1039.7    738          732.4      -0.76%    power law
     *   1250      711.9        708.6      -0.46%    power law
     *   1400      694.4        694.4      -0.00%    power law
     * </pre>
     * <b>Under 1% from 14.7 to 1400 psia</b>, and unchanged from the original
     * two-point fit at and above 800 psia — every calibration quoted against the
     * operating band still holds to the last bit.
     *
     * <p>Below 800 psia the power law is abandoned. Its exponent is negative, so
     * extrapolating downward inflates the density without limit: +12.9% at 200
     * psia, +23.4% at 100, +63.9% at atmospheric and a nonsensical 3838 kg/m3 at
     * the 0.1 psia floor. {@link PressureVessel#getCollapsedLevelIn()} divides
     * inventory by this number, so that error became a level error and then a
     * covered-fuel-fraction error: a vessel blown down to 0 psig with its entire
     * 220 tonne inventory still in it reported three quarters of the core dry.
     * <b>Do not "simplify" this back to one power law.</b> The replacement is the
     * subcooled quadratic evaluated at the saturation temperature, which is
     * within 0.35% over the whole range the power law cannot reach.
     *
     * <p>Above 1400 psia the power law is still extrapolated and still drifts
     * (+5% at 2000 psia, +35% at 3000, and it never approaches the 322 kg/m3
     * critical density). That is left as it is: nothing decides whether the fuel
     * is covered from a supercritical density, and there is no second fitted
     * segment up there to hand over to.
     *
     * <p>This density is what makes vessel level indication lie during a
     * pressure transient: the reference leg of a differential-pressure level
     * instrument is calibrated against one value of it [TTC 3.1.2.1.1], and the
     * instrument has no way to know the value has moved.
     */
    public static double liquidDensityKgPerM3(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        double powerLaw = LIQUID_DENSITY_COEFFICIENT * Math.pow(p, LIQUID_DENSITY_EXPONENT);
        if (p >= LIQUID_DENSITY_BLEND_END_PSIA) {
            return powerLaw;
        }
        double fromTemperature = liquidDensityFromTemperatureKgPerM3(temperatureCelsiusFromPsia(p));
        double weight = blendWeight(p, LIQUID_DENSITY_BLEND_START_PSIA, LIQUID_DENSITY_BLEND_END_PSIA);
        return fromTemperature + weight * (powerLaw - fromTemperature);
    }

    // ---------------------------------------------------------------
    // Latent heat and enthalpy
    // ---------------------------------------------------------------

    /** Leading coefficient of the latent heat fit, kJ/kg at 1 psia. */
    public static final double LATENT_HEAT_COEFFICIENT = 10640.6;

    /** Exponent of the latent heat fit. Negative: less heat per kg as pressure rises. */
    public static final double LATENT_HEAT_EXPONENT = -0.28305;

    /**
     * Saturation temperature this class's own {@code T_sat} correlation puts at
     * {@link #CRITICAL_PRESSURE_PSIA}, kelvin — 648.66 K, against a true critical
     * temperature of 647.10 K.
     *
     * <p>Deriving it from the correlation rather than quoting the physical
     * constant is deliberate: it makes {@link #latentHeatKJPerKg} reach exactly
     * zero at exactly 3208 psia and stay strictly positive everywhere below,
     * which the physical 647.10 K would not, because the {@code P^0.225} fit
     * overshoots by 1.6 degC that far outside its band.
     */
    private static final double CRITICAL_TEMPERATURE_K =
            celsiusFromFahrenheit(T_SAT_COEFFICIENT_DEG_F
                    * Math.pow(CRITICAL_PRESSURE_PSIA, T_SAT_EXPONENT)) + KELVIN_OFFSET;

    /**
     * Leading coefficient of the Watson latent-heat relation, kJ/kg.
     * Fitted with {@link #LATENT_HEAT_WATSON_EXPONENT} to h_fg = 2257 kJ/kg at
     * 14.696 psia and 1604 kJ/kg at 800 psia.
     */
    public static final double LATENT_HEAT_WATSON_COEFFICIENT = 3055.997;

    /**
     * Exponent of the Watson latent-heat relation, {@code h_fg ~ (1 - T/T_c)^n}.
     * Watson's own generic value is 0.38; fitting it to the two water anchors
     * gives 0.355.
     */
    public static final double LATENT_HEAT_WATSON_EXPONENT = 0.3550046;

    /** Pressure at and above which latent heat comes from the 800-1400 psia power law, psia. */
    public static final double LATENT_HEAT_POWER_LAW_START_PSIA = 800.0;

    /** Pressure above which latent heat starts blending back to the Watson relation, psia. */
    public static final double LATENT_HEAT_BLEND_START_PSIA = 1400.0;

    /** Pressure at and above which latent heat is the Watson relation alone, psia. */
    public static final double LATENT_HEAT_BLEND_END_PSIA = 1800.0;

    /**
     * Numerical floor on latent heat, kJ/kg.
     *
     * <p>h_fg is a <i>denominator</i>: {@code PressureVessel.boiling},
     * {@code liquidCapacityKgPerPsi} and {@code VoidModel.exitQuality} all divide
     * by it. Letting it reach the physically correct zero at the critical point
     * would give Infinity/Infinity and poison the state, so it stops just short.
     * The ratio those callers actually form — boiling over pressure capacity —
     * has h_fg in both numerator and denominator and stays finite and correct as
     * this floor is approached, which is why a floor is enough and a special case
     * is not needed. Same species of guard as {@link #MINIMUM_PRESSURE_PSIA}.
     */
    public static final double MINIMUM_LATENT_HEAT_KJ_PER_KG = 1.0;

    /**
     * Latent heat of vaporisation h_fg, kJ/kg.
     *
     * <p>Validation:
     * <pre>
     *   psia    table kJ/kg   fit kJ/kg   error     source
     *     14.7    2257         2257       +0.00%    Watson
     *     50      2149         2144       -0.23%    Watson
     *    100      2066.6       2058       -0.40%    Watson
     *    200      1960.8       1949       -0.61%    Watson
     *    500      1756.5       1748       -0.50%    Watson
     *    800      1604         1604       +0.01%    power law
     *   1000      1512         1506       -0.40%    power law
     *   1039.7    1495         1490       -0.37%    power law
     *   1250      1409         1414       +0.34%    power law
     *   1400      1369         1369       +0.01%    power law
     *   1600      1305         1304       -0.11%    blend
     *   2000      1136         1133       -0.24%    Watson
     *   3000       605          576       -4.8%     Watson
     *   3208         0            0        exact    Watson
     * </pre>
     * <b>Under 0.5% across 800-1400 psia</b> — unchanged there, bit for bit, from
     * the original two-point power law — and under 0.7% from 14.7 psia up to it.
     *
     * <p>The power law used to be the whole function, and outside its band it was
     * badly wrong in both directions. Below: +21% at 200 psia, +40% at 100, +120%
     * at atmospheric, so a decay-heat-only vessel after an ADS blowdown was told
     * it boiled off 40% slower than it does, and every time-to-uncovery a control
     * program computed from it was optimistic by that much. Above: it returned
     * 954 kJ/kg at 5015 psia, in a regime where water has no latent heat at all
     * because it has no two-phase region — see {@link #CRITICAL_PRESSURE_PSIA}.
     *
     * <p>Both ends are now the same Watson relation, {@code C * (1 - T/T_c)^n},
     * with T from the saturation curve. It is a real corresponding-states form
     * rather than a second extrapolation, which is why one fit covers 14.7 psia
     * to the critical point at better than 1% over almost all of it.
     */
    public static double latentHeatKJPerKg(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        double watson = LATENT_HEAT_WATSON_COEFFICIENT
                * Math.pow(reducedTemperatureDeficit(p), LATENT_HEAT_WATSON_EXPONENT);
        double value;
        if (p < LATENT_HEAT_POWER_LAW_START_PSIA) {
            // The two forms agree at 800 psia to 0.01% (1604.0 against 1604.2),
            // so the handover needs no blend and introducing one would only make
            // the answer worse on the side where Watson is the better fit.
            value = watson;
        } else {
            double powerLaw = LATENT_HEAT_COEFFICIENT * Math.pow(p, LATENT_HEAT_EXPONENT);
            double weight = blendWeight(p, LATENT_HEAT_BLEND_START_PSIA, LATENT_HEAT_BLEND_END_PSIA);
            value = powerLaw + weight * (watson - powerLaw);
        }
        return Math.max(MINIMUM_LATENT_HEAT_KJ_PER_KG, value);
    }

    /**
     * {@code 1 - T_sat/T_crit}, the reduced-temperature deficit the Watson
     * relation is a power of. Floored at zero so the supercritical region gives
     * a latent heat of zero rather than a NaN from a negative base.
     */
    private static double reducedTemperatureDeficit(double pressurePsia) {
        double kelvin = temperatureCelsiusFromPsia(pressurePsia) + KELVIN_OFFSET;
        return Math.max(0.0, 1.0 - kelvin / CRITICAL_TEMPERATURE_K);
    }

    /**
     * Smoothstep from 0 at {@code start} to 1 at {@code end}. Used to hand one
     * fitted segment over to another without a step in the value or a kink in its
     * slope — a step here would show up as the vessel level or the boil-off rate
     * jumping as a blowdown crosses the handover pressure.
     */
    private static double blendWeight(double value, double start, double end) {
        if (!(end > start)) {
            return 1.0;
        }
        double u = (value - start) / (end - start);
        if (!(u > 0.0)) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 1.0;
        }
        return u * u * (3.0 - 2.0 * u);
    }

    /**
     * Derivative of {@link #blendWeight} with respect to its value, per unit of
     * whatever the blend is over. Zero outside the window, where the weight is
     * pinned at 0 or 1 — which is also why a blended quantity's slope reduces to
     * one branch's own slope on either side of the handover.
     */
    private static double blendWeightSlope(double value, double start, double end) {
        if (!(end > start)) {
            return 0.0;
        }
        double u = (value - start) / (end - start);
        if (!(u > 0.0) || u >= 1.0) {
            return 0.0;
        }
        return 6.0 * u * (1.0 - u) / (end - start);
    }

    /** Leading coefficient of the saturated liquid enthalpy fit, kJ/kg at 1 psia. */
    public static final double LIQUID_ENTHALPY_COEFFICIENT = 173.46;

    /** Exponent of the saturated liquid enthalpy fit. */
    public static final double LIQUID_ENTHALPY_EXPONENT = 0.28754;

    /**
     * Pressure below which saturated liquid enthalpy comes entirely from the
     * saturation temperature, psia.
     */
    public static final double LIQUID_ENTHALPY_BLEND_START_PSIA = 400.0;

    /**
     * Pressure at and above which saturated liquid enthalpy comes entirely from
     * the 800-1400 psia power law, psia. Between this and
     * {@link #LIQUID_ENTHALPY_BLEND_START_PSIA} the two are smoothstepped
     * together — the same window, for the same reason, as
     * {@link #liquidDensityKgPerM3}.
     */
    public static final double LIQUID_ENTHALPY_BLEND_END_PSIA = 800.0;

    /**
     * Saturated liquid enthalpy h_f, kJ/kg, measured from the steam-table datum
     * (liquid at 0 degC).
     *
     * <p>Validation:
     * <pre>
     *   psia    table kJ/kg   fit kJ/kg   error     source
     *     14.7    419.0        416.1      -0.71%    T_sat quadratic
     *     50      582.1        575.8      -1.08%    T_sat quadratic
     *    100      694.3        689.1      -0.75%    T_sat quadratic
     *    200      826.9        822.9      -0.48%    T_sat quadratic
     *    300      916.4        912.3      -0.45%    T_sat quadratic
     *    400      986.7        981.2      -0.55%    T_sat quadratic
     *    500     1045.6       1037.8      -0.74%    blend
     *    600     1097.2       1089.3      -0.72%    blend
     *    800     1185.8       1185.6      -0.01%    power law
     *   1000     1262         1264.2      +0.18%    power law
     *   1039.7   1275         1278.4      +0.27%    power law
     *   1250     1345.8       1348.0      +0.16%    power law
     *   1400     1392.8       1392.6      -0.01%    power law
     * </pre>
     * <b>Under 0.3% across 800-1400 psia</b> — unchanged there, bit for bit,
     * from the original two-point power law — and under 1.1% from 14.7 psia up
     * to it, against -10.4% for the power law extrapolated to atmospheric.
     *
     * <p>Below atmospheric the comparison stops meaning much, and the table
     * above deliberately stops there. At 1 psia this returns 191.5 kJ/kg against
     * a tabulated 162.1, but that is not this function being wrong by 18% — it
     * is {@code T_sat} reading 46.2 degC where the tables say 38.7, and 191.5 is
     * the right enthalpy for 46.2 degC to within 1%. Reading h_f off the model's
     * own saturation temperature is what makes the two agree; the power law it
     * replaced was independently wrong <i>and</i> inconsistent with the
     * temperature everything else in the plant was using.
     *
     * <p>The difference between this and the enthalpy of the water actually
     * entering the core is the core inlet subcooling, which sets where in the
     * core boiling starts. That is the single strongest lever pressure has on
     * void fraction, and therefore on reactivity — see {@link VoidModel}. It is
     * also, through {@link #vapourEnthalpyKJPerKg}, what a kilogram of steam is
     * worth when it condenses in the suppression pool: an RCIC turbine
     * exhausting to the pool at containment pressure was carrying 2633 kJ/kg
     * against a table 2676, and the pool heated 1.6% slow for it.
     *
     * <p>Below 800 psia the power law is abandoned, exactly as it is for
     * {@link #liquidDensityKgPerM3} and over exactly the same blend window. The
     * replacement is {@link #subcooledLiquidEnthalpyKJPerKg} evaluated at the
     * saturation temperature — liquid water is nearly incompressible, so its
     * enthalpy is very nearly a function of temperature alone, which is why one
     * quadratic in T serves both the subcooled and the saturated case. That has
     * a second virtue beyond accuracy: h_f is now consistent with the
     * temperature the rest of the model believes the water is at, because both
     * come from the same {@code T_sat}. Below about 100 psia what residual is
     * left is mostly the {@code P^0.225} temperature correlation's own error and
     * not this fit's — at 50 psia {@code T_sat} is 1.9 degC low, which is 1.4%
     * of h_f on its own.
     *
     * <p><b>{@link #liquidEnthalpySlopeKJPerKgPerPsi} differentiates this
     * function and must keep doing so.</b> It is not {@code b * h_f / P} any
     * more below 800 psia, and a "simplification" back to that form would leave
     * the vessel's liquid heat capacity — and therefore flashing, and therefore
     * level — reading the slope of a curve the model no longer follows.
     */
    public static double liquidEnthalpyKJPerKg(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        double powerLaw = LIQUID_ENTHALPY_COEFFICIENT * Math.pow(p, LIQUID_ENTHALPY_EXPONENT);
        if (p >= LIQUID_ENTHALPY_BLEND_END_PSIA) {
            return powerLaw;
        }
        double fromTemperature = subcooledLiquidEnthalpyKJPerKg(temperatureCelsiusFromPsia(p));
        double weight = blendWeight(p, LIQUID_ENTHALPY_BLEND_START_PSIA, LIQUID_ENTHALPY_BLEND_END_PSIA);
        return fromTemperature + weight * (powerLaw - fromTemperature);
    }

    /** Saturated vapour enthalpy h_g = h_f + h_fg, kJ/kg. About 2768 kJ/kg at rated. */
    public static double vapourEnthalpyKJPerKg(double pressurePsia) {
        return liquidEnthalpyKJPerKg(pressurePsia) + latentHeatKJPerKg(pressurePsia);
    }

    /**
     * Rate of change of saturated liquid enthalpy with pressure, kJ/kg per psi.
     * Analytic derivative of {@link #liquidEnthalpyKJPerKg}, about 0.354 kJ/kg
     * per psi at rated.
     *
     * <p>Multiply by the vessel water mass and divide by {@code h_fg} and you
     * have the mass of steam that must condense to raise pressure one psi. At
     * rated with 2.2e5 kg of water that is about 52 kg per psi, roughly seven
     * times the steam the dome itself has to accumulate — the water inventory,
     * not the steam space, is what makes a BWR's pressure response as slow as
     * it is.
     *
     * <p>At and above 800 psia this is still {@code b * h_f / P} and still
     * bit-identical to what it always was. Below it the enthalpy is a blend of a
     * power law and a quadratic in {@code T_sat}, so the derivative is the
     * product and chain rule applied to that blend: the quadratic's slope in
     * temperature times {@code dT_sat/dP}, the power law's own slope, and the
     * term from the smoothstep weight itself moving. All three pieces are closed
     * form, so this stays an exact derivative and not a difference quotient —
     * {@link PressureVessel} uses it and {@link #liquidEnthalpyKJPerKg} in the
     * same expression, and a slope that does not belong to the value would put
     * flashing mass into a vessel that never gave it up.
     */
    public static double liquidEnthalpySlopeKJPerKgPerPsi(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        double powerLaw = LIQUID_ENTHALPY_COEFFICIENT * Math.pow(p, LIQUID_ENTHALPY_EXPONENT);
        double powerLawSlope = LIQUID_ENTHALPY_EXPONENT * powerLaw / p;
        if (p >= LIQUID_ENTHALPY_BLEND_END_PSIA) {
            return powerLawSlope;
        }
        double temperatureC = temperatureCelsiusFromPsia(p);
        double fromTemperature = subcooledLiquidEnthalpyKJPerKg(temperatureC);
        double fromTemperatureSlope = subcooledLiquidEnthalpySlopeKJPerKgC(temperatureC)
                * temperatureSlopeCelsiusPerPsi(p);
        double weight = blendWeight(p, LIQUID_ENTHALPY_BLEND_START_PSIA,
                LIQUID_ENTHALPY_BLEND_END_PSIA);
        double weightSlope = blendWeightSlope(p, LIQUID_ENTHALPY_BLEND_START_PSIA,
                LIQUID_ENTHALPY_BLEND_END_PSIA);
        return fromTemperatureSlope
                + weight * (powerLawSlope - fromTemperatureSlope)
                + weightSlope * (powerLaw - fromTemperature);
    }

    /**
     * Specific heat of saturated liquid water, kJ/kg per degree C, taken as
     * {@code dh_f/dT_sat} along the saturation line. About 5.35 kJ/kg/degC at
     * rated pressure — far above the 4.18 of cold water, because near the
     * critical point the liquid is expanding hard as it heats.
     *
     * <p>This is a ratio of two slopes, so it moved when
     * {@link #liquidEnthalpyKJPerKg} became piecewise. At and above 800 psia it
     * is unchanged. Below, it now follows the quadratic's own derivative and is
     * the better number for it — the suppression pool sizes its whole heat
     * capacity on this at containment pressure, where it reads 4.27 kJ/kg/degC
     * against a tabulated 4.21 (+1.4%) rather than the old 4.10 (-2.7%). The
     * quadratic's derivative is a cruder object than the quadratic itself, and
     * around 400 psia it runs about 3.6% low, but at every pressure below 800
     * it is closer than the extrapolated power law was.
     */
    public static double liquidSpecificHeatKJPerKgC(double pressurePsia) {
        double p = clampPressurePsia(pressurePsia);
        return liquidEnthalpySlopeKJPerKgPerPsi(p) / temperatureSlopeCelsiusPerPsi(p);
    }

    // ---------------------------------------------------------------
    // Subcooled liquid
    // ---------------------------------------------------------------

    /** Quadratic coefficients of the subcooled liquid enthalpy fit. */
    public static final double SUBCOOLED_ENTHALPY_LINEAR_KJ_PER_KG_C = 4.1093;
    public static final double SUBCOOLED_ENTHALPY_QUADRATIC_KJ_PER_KG_C2 = 8.174e-4;

    /**
     * Enthalpy of subcooled (compressed) liquid water at a given temperature,
     * kJ/kg. Pressure dependence is neglected, which is worth well under 1% at
     * the pressures involved because liquid water is nearly incompressible.
     *
     * <p>Used for feedwater and ECCS injection enthalpy, where what matters is
     * how much core power gets spent heating the incoming water to saturation
     * rather than making steam.
     *
     * <p>Validation against saturated liquid enthalpy by temperature:
     * <pre>
     *   degC    table kJ/kg   fit kJ/kg   error
     *     50      209.3        207.5      -0.9%
     *    100      419.1        419.1      +0.0%
     *    150      632.2        634.8      +0.4%
     *    200      852.4        854.6      +0.26%
     *    220      943.6        943.6      +0.0%
     * </pre>
     * Under 0.5% over 100-220 degC, about -1.7% at 20 degC.
     */
    public static double subcooledLiquidEnthalpyKJPerKg(double temperatureC) {
        double t = Double.isFinite(temperatureC) ? Math.max(0.0, temperatureC) : 0.0;
        return SUBCOOLED_ENTHALPY_LINEAR_KJ_PER_KG_C * t
                + SUBCOOLED_ENTHALPY_QUADRATIC_KJ_PER_KG_C2 * t * t;
    }

    /**
     * Derivative of {@link #subcooledLiquidEnthalpyKJPerKg} with respect to
     * temperature, kJ/kg per degree C — which is simply the specific heat the
     * quadratic implies, rising from 4.11 at 0 degC to 4.47 at 220 degC.
     *
     * <p>Flat at zero below 0 degC so it differentiates the clamp in
     * {@link #subcooledLiquidEnthalpyKJPerKg} rather than the unclamped
     * quadratic; nothing in the plant gets there, but a slope that disagreed
     * with its own value at the boundary is the sort of thing that only shows up
     * as a conservation error much later.
     */
    private static double subcooledLiquidEnthalpySlopeKJPerKgC(double temperatureC) {
        if (!(Double.isFinite(temperatureC) && temperatureC > 0.0)) {
            return 0.0;
        }
        return SUBCOOLED_ENTHALPY_LINEAR_KJ_PER_KG_C
                + 2.0 * SUBCOOLED_ENTHALPY_QUADRATIC_KJ_PER_KG_C2 * temperatureC;
    }

    /**
     * Density of subcooled liquid water at a given temperature, kg/m3.
     *
     * <p>A quadratic fit over 20-250 degC. Its first job is the reference leg of
     * a vessel level instrument, which sits in the drywell at whatever
     * temperature the drywell happens to be, not at reactor temperature.
     *
     * <p>Its second job is to supply {@link #liquidDensityKgPerM3} below 800
     * psia, where it is evaluated at the saturation temperature and is used a
     * little past its fitted band — 270 degC at the 800 psia handover, where it
     * is still within 0.33% of the tables. That accuracy is why the handover is
     * placed there and not higher; by 1400 psia it has drifted to +1.9%.
     *
     * <pre>
     *   degC    table kg/m3   fit kg/m3   error
     *     20      998.2        998.2      +0.00%
     *     57.2    984.8        983.6      -0.12%
     *    100      958.4        958.4      +0.00%
     *    150      917.0        917.7      +0.07%
     *    200      864.7        864.7      +0.00%
     *    250      799.2        799.5      +0.04%
     * </pre>
     */
    public static double liquidDensityFromTemperatureKgPerM3(double temperatureC) {
        double t = Double.isFinite(temperatureC) ? Math.max(0.0, temperatureC) : 0.0;
        return 1003.267 - 0.20450 * t - 0.00244167 * t * t;
    }
}
