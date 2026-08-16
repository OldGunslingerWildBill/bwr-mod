package dev.bwr.core.thermal;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;

/**
 * Core void fraction as a function of power, flow and pressure — {@code SPEC.md}
 * section 6.3, and the single most important feedback in the plant.
 *
 * <h2>What the model actually computes</h2>
 * A BWR core is a once-through boiler. Water enters the bottom subcooled, is
 * heated to saturation over the first fifth or so of the core height, and boils
 * the rest of the way up. Three quantities follow from an energy balance and a
 * slip correlation:
 *
 * <pre>
 *   enthalpy rise    dH   = Q / W                       kJ/kg
 *   boiling boundary z_b  = dH_subcool / dH             fraction of core height
 *   exit quality     x    = (dH - dH_subcool) / h_fg    kg steam per kg flow
 *   void fraction    a(x) = x / ( x + S * (rho_g/rho_f) * (1 - x) )
 * </pre>
 *
 * The core average is the axial integral of {@code a(x(z))} from the boiling
 * boundary to the top of the core, taken numerically over
 * {@link #getAxialNodes()} nodes. There is no fitted "void versus power" curve
 * anywhere in here; the shape falls out of the energy balance.
 *
 * <h2>The three sensitivities, and their signs</h2>
 * <ul>
 *   <li><b>Power up at constant flow:</b> {@code dH} rises, so exit quality
 *       rises and the boiling boundary drops toward the bottom of the core.
 *       Void rises. Void reactivity is negative, so power is self-limiting —
 *       this is the negative feedback that makes a BWR a BWR.</li>
 *   <li><b>Flow down at constant power:</b> {@code dH = Q/W} rises for exactly
 *       the same reason. <b>Falling flow raises void</b>, which is why a real
 *       plant trips the recirculation pumps at 1130 psig "to insert negative
 *       reactivity by means of void formation, assuming the reactor fails to
 *       scram" [TTC 3.1.3]. That trip is the player's to write; the physics it
 *       exploits lives here.</li>
 *   <li><b>Pressure up:</b> voids collapse and reactivity is <i>added</i>. Two
 *       mechanisms, both pointing the same way, and getting this sign right is
 *       the whole point of {@code SPEC.md} section 6.3:
 *       <ol>
 *         <li>Steam gets denser, so the same mass of steam occupies less volume
 *           and {@code rho_g/rho_f} rises, pushing {@code a(x)} down.</li>
 *         <li>Saturation temperature rises, so the water arriving at the core
 *           inlet — which has not had time to heat up — is suddenly more
 *           subcooled. The boiling boundary climbs and a chunk of the core stops
 *           boiling altogether. This is the larger and faster of the two.</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p>The second mechanism is transient, and the model says so. Core inlet
 * enthalpy is a lagged state relaxing toward its steady value with
 * {@link #getRecirculationTransitTimeConstantSeconds()}, the time it takes water
 * to go round the recirculation loop. So a pressurisation produces a large
 * prompt void collapse that partly recovers over the next ten or twenty seconds
 * — which is the shape of a real MSIV closure transient, not an approximation
 * of one.
 *
 * <p>With the defaults, a +100 psi step from rated drives the quasi-static void
 * fraction from 0.371 to 0.274 the instant the pressure moves — saturation
 * enthalpy tracks pressure with no delay, so the water is subcooled immediately.
 * The actual void follows over a channel transit, and the reactivity that
 * arrives is about <b>+1000 pcm</b>, roughly a dollar and a half on LEU and four
 * dollars on plutonium. Over the following minute the recirculation loop
 * re-equilibrates and it settles back to about +315 pcm. MSIV closure, pressure
 * spike, void collapse, power surge, and the difference between fuels, all
 * emergent.
 *
 * <h2>Two lags, two jobs</h2>
 * <ul>
 *   <li>{@link #getRecirculationTransitTimeConstantSeconds()}, about 12 s — how
 *       long inlet enthalpy takes to catch up with a pressure change. This is
 *       what makes the pressure effect large and then fading.</li>
 *   <li>{@link #getVoidTransitTimeConstantSeconds()}, about 1 s — how long the
 *       void distribution takes to answer a change in boiling rate. This is what
 *       makes the void-power loop a dynamical system rather than an algebraic
 *       one, and it is not optional; see the constant's own note.</li>
 * </ul>
 *
 * <h2>Who owns the reactivity</h2>
 * This class computes <b>void fraction</b>. {@code ReactivityBalance} turns it
 * into reactivity. Feed {@link #getCoreAverageVoidFraction()} into
 * {@code ReactivityBalance.setVoidFraction} and leave its pressure coefficient
 * at zero: the pressure effect is already inside the void number, and adding a
 * separate pressure coefficient would count it twice.
 *
 * <p>The {@code voidCoefficient...} and {@code voidReactivity...} statics below
 * are the same {@code 1/(1-a)} steepening {@code ReactivityBalance} applies
 * internally, exposed here for panels, tests and CSV dumps. They are
 * measurements. Do not add their output to the reactivity balance.
 *
 * <h2>Tick order</h2>
 * Per {@code SPEC.md} section 6.3 the sub-stepped kinetics must see void and
 * pressure frozen within a tick. Update this once per tick, before the kinetics
 * solve, using last tick's pressure; then update {@link PressureVessel} after.
 *
 * <p>Nothing here is a judgement. There is no acceptable void fraction, no
 * stability boundary check, no flow limit. Pure Java, doubles, no Minecraft.
 */
public final class VoidModel {

    // ---------------------------------------------------------------
    // Plant scaling
    // ---------------------------------------------------------------

    /** Kilograms per pound mass, for the lb/hr plant data in {@link PhysicalConstants}. */
    public static final double KG_PER_LB = 0.45359237;

    /**
     * Rated core flow, kg/s. 104e6 lb/hr [TTC 1.8] is 13104 kg/s — about
     * thirteen tonnes of water through the core every second, which is why
     * losing recirculation is a reactivity event and not just a cooling one.
     */
    public static final double RATED_CORE_FLOW_KG_PER_S =
            PhysicalConstants.RATED_CORE_FLOW_LB_PER_HR * KG_PER_LB / 3600.0;

    /**
     * Rated steam flow divided by rated core flow: 15.4e6 / 104e6 = 0.1481.
     * The core boils about fifteen percent of what passes through it and
     * recirculates the rest. Recorded because it is the direct plant-data check
     * on the exit quality this model computes.
     */
    public static final double RATED_EXIT_QUALITY =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR / PhysicalConstants.RATED_CORE_FLOW_LB_PER_HR;

    /**
     * Numerical floor on core flow fraction.
     *
     * <p>Zero forced flow would divide by zero in {@code Q/W}. A real core with
     * the pumps off still circulates on buoyancy, but natural circulation is
     * explicitly deferred by {@code SPEC.md} section 4.5 because it turns flow
     * into an output and closes an algebraic loop. This floor stands in for the
     * residual driving head until that arrives. It is a numerical guard, and the
     * behaviour it produces — flow to nearly zero at power drives quality to one
     * and void to its ceiling — is the right answer anyway.
     */
    public static final double MINIMUM_FLOW_FRACTION = 0.01;

    /**
     * Ceiling on void fraction, matching {@code ReactivityBalance.MAX_VOID_FRACTION}.
     * The {@code 1/(1-a)} coefficient scaling diverges at a = 1, which is a dry
     * core — the severe accident model's problem, not the void term's.
     */
    public static final double MAX_VOID_FRACTION = 0.999;

    // ---------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------

    /**
     * Default slip ratio between the steam and liquid phases.
     *
     * <p>Steam is buoyant and travels up the channel faster than the water, so
     * it occupies less volume than a homogeneous mixture would suggest. At BWR
     * pressures the phases are close in density and slip is modest; 1.5 puts
     * core exit void at about 0.70 and core average at about 0.375 at rated
     * conditions, both matching operating BWR values.
     */
    public static final double DEFAULT_SLIP_RATIO = 1.5;

    /**
     * Default feedwater enthalpy, kJ/kg — about 420 degF, a BWR/6 final
     * feedwater temperature. Sets the equilibrium core inlet subcooling and
     * therefore where boiling starts.
     */
    public static final double DEFAULT_FEEDWATER_ENTHALPY_KJ_PER_KG =
            Saturation.subcooledLiquidEnthalpyKJPerKg(215.6);

    /**
     * Default recirculation loop transit time, seconds. Water leaving the core
     * takes this long to come back round the downcomer and jet pumps, so this is
     * how long the prompt subcooling excursion from a pressure change survives
     * before the loop re-equilibrates.
     */
    public static final double DEFAULT_RECIRCULATION_TRANSIT_TIME_S = 12.0;

    /**
     * Default void transit time, seconds — how long the void distribution takes
     * to establish itself after the boiling rate changes. Core height over
     * two-phase mixture velocity: about 3.8 m at 3 to 4 m/s.
     *
     * <p><b>This delay is load bearing, not decoration.</b> The void-to-power
     * coupling is worth roughly -0.021 dk/k per unit of relative power, which is
     * -3.2 dollars on U-235 and nearly -10 on plutonium. Left algebraic, that is
     * an enormous feedback gain with no dynamics, and integrating it explicitly
     * against 20 Hz kinetics produces a divergent tick-to-tick oscillation that
     * is a numerical artefact rather than reactor behaviour. The physical answer
     * is that steam has to travel, and giving the loop its real time constant
     * both fixes the arithmetic and is what a BWR actually does. It is also why
     * {@code SPEC.md} section 6.3 flags this loop as the numerically delicate one.
     */
    public static final double DEFAULT_VOID_TRANSIT_TIME_S = 1.0;

    /** Default axial integration nodes across the boiling length. */
    public static final int DEFAULT_AXIAL_NODES = 20;

    private final CoreConfig config;

    private double slipRatio = DEFAULT_SLIP_RATIO;
    private double feedwaterEnthalpyKJPerKg = DEFAULT_FEEDWATER_ENTHALPY_KJ_PER_KG;
    private double recirculationTransitTimeConstantSeconds = DEFAULT_RECIRCULATION_TRANSIT_TIME_S;
    private double voidTransitTimeConstantSeconds = DEFAULT_VOID_TRANSIT_TIME_S;
    private int axialNodes = DEFAULT_AXIAL_NODES;
    private double ratedCoreFlowKgPerS = RATED_CORE_FLOW_KG_PER_S;

    // --- state ---------------------------------------------------------

    /**
     * Lagged core inlet enthalpy, kJ/kg. The one piece of memory this model has,
     * and it has to be <i>enthalpy</i> rather than subcooling.
     *
     * <p>Subcooling is {@code h_f(P) - h_inlet}. When pressure moves, {@code h_f}
     * moves with it instantly — that is the saturation curve — while the water
     * already in the downcomer keeps the enthalpy it left the vessel with. So
     * subcooling jumps promptly and only then relaxes. Lagging subcooling
     * directly instead would throw away that jump and with it most of the
     * pressure-to-reactivity coupling: a +100 psi step raises {@code h_f} by
     * 34 kJ/kg but raises the <i>equilibrium</i> subcooling by only 5.
     */
    private double coreInletEnthalpyKJPerKg;

    // --- last computed --------------------------------------------------

    private double coreInletSubcoolingKJPerKg;

    /** Void fraction actually in the channels, lagging the quasi-static target. */
    private double coreAverageVoidFraction;

    /** Void fraction the current power, flow and pressure ask for, before the transit lag. */
    private double quasiStaticVoidFraction;

    private double exitVoidFraction;
    private double exitQuality;
    private double boilingBoundaryFraction;
    private double enthalpyRiseKJPerKg;
    private double equilibriumSubcoolingKJPerKg;
    private double densityRatio;
    private double pressurePsig = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

    /**
     * @param config supplies the rated thermal power this core is scaled
     *               against. The reference is retained, so retuning
     *               {@code ratedThermalMW} takes effect on the next update.
     */
    public VoidModel(CoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        initialiseToSteadyState(1.0, 1.0, PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
    }

    // ---------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------

    /**
     * Advance the void model one tick.
     *
     * @param totalPowerFractionOfRated total core thermal power as a fraction of
     *        rated — <b>fission plus decay heat</b>. Decay heat boils water too;
     *        a scrammed core still has void because it is still making steam.
     * @param coreFlowFraction core flow as a fraction of rated, floored at
     *        {@link #MINIMUM_FLOW_FRACTION}
     * @param pressurePsig reactor dome pressure, psig
     * @param dtSeconds step length, seconds. Non-positive recomputes the
     *        algebraic state at the current subcooling without advancing the lag.
     * @return the new core-average void fraction, 0..{@link #MAX_VOID_FRACTION}
     */
    public double update(double totalPowerFractionOfRated,
                         double coreFlowFraction,
                         double pressurePsig,
                         double dtSeconds) {
        double power = finiteNonNegative(totalPowerFractionOfRated);
        double flow = Math.max(MINIMUM_FLOW_FRACTION, finiteNonNegative(coreFlowFraction));
        this.pressurePsig = Double.isFinite(pressurePsig)
                ? pressurePsig : PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

        double psia = Saturation.psiaFromPsig(this.pressurePsig);
        double latentHeat = Saturation.latentHeatKJPerKg(psia);

        // Energy balance: how much enthalpy every kilogram picks up crossing the core.
        double coreThermalKW = power * config.ratedThermalMW * 1000.0;
        double flowKgPerS = flow * ratedCoreFlowKgPerS;
        this.enthalpyRiseKJPerKg = coreThermalKW / flowKgPerS;

        // Where the subcooling, and therefore the inlet enthalpy, would settle if
        // pressure and power held.
        this.equilibriumSubcoolingKJPerKg = equilibriumSubcooling(power, flow, this.pressurePsig);
        double equilibriumInletEnthalpy =
                Saturation.liquidEnthalpyKJPerKg(psia) - this.equilibriumSubcoolingKJPerKg;

        // Relax the inlet enthalpy toward it over one loop transit. Saturation
        // enthalpy has already moved with pressure; this has not. That gap is the
        // prompt void collapse, and this lag is what lets it recover afterwards.
        if (dtSeconds > 0.0 && Double.isFinite(dtSeconds)) {
            double tau = recirculationTransitTimeConstantSeconds;
            double alpha = (tau > 0.0) ? 1.0 - Math.exp(-dtSeconds / tau) : 1.0;
            this.coreInletEnthalpyKJPerKg +=
                    (equilibriumInletEnthalpy - this.coreInletEnthalpyKJPerKg) * alpha;
        }

        recomputeVoidGeometry(psia, latentHeat);

        // Void itself lags the quasi-static answer by a channel transit. Steam
        // that has not been made yet cannot be displacing water yet.
        if (dtSeconds > 0.0 && Double.isFinite(dtSeconds)) {
            double tau = voidTransitTimeConstantSeconds;
            double alpha = (tau > 0.0) ? 1.0 - Math.exp(-dtSeconds / tau) : 1.0;
            this.coreAverageVoidFraction +=
                    (this.quasiStaticVoidFraction - this.coreAverageVoidFraction) * alpha;
        } else {
            this.coreAverageVoidFraction = this.quasiStaticVoidFraction;
        }
        return this.coreAverageVoidFraction;
    }

    /**
     * The axial solve. Splits the core into a non-boiling length and a boiling
     * length and integrates the slip correlation over the boiling length.
     */
    private void recomputeVoidGeometry(double pressurePsia, double latentHeatKJPerKg) {
        double liquidDensity = Saturation.liquidDensityKgPerM3(pressurePsia);
        double vapourDensity = Saturation.vapourDensityKgPerM3(pressurePsia);
        this.densityRatio = vapourDensity / liquidDensity;

        // Subcooling is derived, not stored: saturation enthalpy tracks pressure
        // instantly while the inlet enthalpy lags, and the difference between
        // them is the whole prompt pressure effect.
        double subcooling = Math.max(0.0,
                Saturation.liquidEnthalpyKJPerKg(pressurePsia) - this.coreInletEnthalpyKJPerKg);

        double rise = this.enthalpyRiseKJPerKg;
        if (!(rise > 0.0)) {
            // No power means no boiling, and a solid water core has no void.
            this.coreInletSubcoolingKJPerKg = subcooling;
            this.boilingBoundaryFraction = 1.0;
            this.exitQuality = 0.0;
            this.exitVoidFraction = 0.0;
            this.quasiStaticVoidFraction = 0.0;
            return;
        }

        subcooling = Math.min(subcooling, rise);
        this.coreInletSubcoolingKJPerKg = subcooling;
        this.boilingBoundaryFraction = subcooling / rise;

        double boilingEnthalpy = rise - subcooling;
        this.exitQuality = clamp(boilingEnthalpy / latentHeatKJPerKg, 0.0, 1.0);
        this.exitVoidFraction = voidFractionFromQuality(this.exitQuality, densityRatio, slipRatio);

        // Quality rises linearly with height above the boiling boundary, so the
        // core average is the mean of a(x) over the boiling length, scaled by
        // the fraction of the core that is boiling at all.
        double boilingLength = 1.0 - this.boilingBoundaryFraction;
        if (!(boilingLength > 0.0) || !(this.exitQuality > 0.0)) {
            this.quasiStaticVoidFraction = 0.0;
            return;
        }
        int nodes = this.axialNodes;
        double sum = 0.0;
        for (int k = 0; k < nodes; k++) {
            double fractionAlongBoilingLength = (k + 0.5) / nodes;
            double quality = this.exitQuality * fractionAlongBoilingLength;
            sum += voidFractionFromQuality(quality, densityRatio, slipRatio);
        }
        this.quasiStaticVoidFraction = clamp(boilingLength * sum / nodes, 0.0, MAX_VOID_FRACTION);
    }

    /**
     * Steady-state core inlet subcooling, kJ/kg.
     *
     * <p>At steady state the vessel boils off exactly as much as feedwater puts
     * in, and that cold feedwater mixes into the recirculating saturated water
     * in the downcomer. Energy balance on the mixing:
     * <pre>
     *   dH_subcool = (Q / W_core) * (h_f - h_fw) / (h_g - h_fw)
     * </pre>
     * At rated conditions this returns 52.5 kJ/kg, which puts the boiling
     * boundary 19% of the way up the core and gives an exit quality of 0.148 —
     * against the plant's own 15.4e6/104e6 = 0.148 [TTC 1.8]. The model
     * reproduces the rated operating point from geometry and thermodynamics, not
     * from a tuned constant.
     *
     * @param powerFractionOfRated total core thermal power, fraction of rated
     * @param coreFlowFraction     core flow, fraction of rated
     * @param pressurePsig         dome pressure, psig
     */
    public double equilibriumSubcooling(double powerFractionOfRated,
                                        double coreFlowFraction,
                                        double pressurePsig) {
        double power = finiteNonNegative(powerFractionOfRated);
        double flow = Math.max(MINIMUM_FLOW_FRACTION, finiteNonNegative(coreFlowFraction));
        double psia = Saturation.psiaFromPsig(
                Double.isFinite(pressurePsig) ? pressurePsig : PhysicalConstants.RATED_DOME_PRESSURE_PSIG);

        double liquidEnthalpy = Saturation.liquidEnthalpyKJPerKg(psia);
        double vapourEnthalpy = Saturation.vapourEnthalpyKJPerKg(psia);
        double feedwaterRise = vapourEnthalpy - feedwaterEnthalpyKJPerKg;
        if (!(feedwaterRise > 0.0)) {
            return 0.0;
        }
        double rise = power * config.ratedThermalMW * 1000.0 / (flow * ratedCoreFlowKgPerS);
        double subcooling = rise * (liquidEnthalpy - feedwaterEnthalpyKJPerKg) / feedwaterRise;
        return Math.max(0.0, subcooling);
    }

    // ---------------------------------------------------------------
    // The slip correlation
    // ---------------------------------------------------------------

    /**
     * Void fraction from flow quality, by the slip (drift-flux) relation:
     * <pre>
     *   a = x / ( x + S * (rho_g/rho_f) * (1 - x) )
     * </pre>
     *
     * <p>The density ratio is the reason a couple of percent of steam by mass is
     * most of the channel by volume: at rated pressure {@code rho_g/rho_f} is
     * about 0.051, so a kilogram of steam takes up twenty times the room a
     * kilogram of water does. Raising pressure squeezes that ratio, which is
     * mechanism one of the pressure-to-reactivity coupling.
     *
     * @param quality     flow quality, kg steam per kg total, 0..1
     * @param densityRatio saturated vapour density over saturated liquid density
     * @param slipRatio   steam velocity over liquid velocity, at least 1
     */
    public static double voidFractionFromQuality(double quality, double densityRatio, double slipRatio) {
        if (!(quality > 0.0)) {
            return 0.0;
        }
        double x = Math.min(quality, 1.0);
        double s = Math.max(1.0, slipRatio);
        double denominator = x + s * densityRatio * (1.0 - x);
        if (!(denominator > 0.0)) {
            return MAX_VOID_FRACTION;
        }
        return Math.min(x / denominator, MAX_VOID_FRACTION);
    }

    // ---------------------------------------------------------------
    // Void coefficient steepening — measurements only
    // ---------------------------------------------------------------

    /**
     * The void coefficient at a given void fraction, dk/k per unit void
     * fraction:
     * <pre>
     *   alpha_v(a) = alpha_v0 / (1 - a)
     * </pre>
     *
     * <p><b>A constant void coefficient is wrong in a way that matters.</b> The
     * NRC manual's worked example [TTC 1.7.2.1.2]: a 1% void increment removes
     * about 1.1% of the water still present at 10% void, but about 3.45% of it
     * at 70% void. The same bubble displaces a far larger share of what is left
     * when little is left. The {@code 1/(1-a)} form gives a ratio of 3.0 between
     * those two points against the manual's 3.14 — as close as a one-parameter
     * form gets. A flat coefficient understates feedback at the top of the core,
     * which is exactly where BWR behaviour lives.
     *
     * <p>This is a readout. {@code ReactivityBalance} applies the same scaling
     * internally when it integrates the void term; do not add both.
     *
     * @param baseCoefficientPerVoidFraction the coefficient at zero void, negative
     * @param voidFraction                   current void fraction, 0..1
     */
    public static double voidCoefficientPerVoidFraction(double baseCoefficientPerVoidFraction,
                                                        double voidFraction) {
        double a = clamp(voidFraction, 0.0, MAX_VOID_FRACTION);
        return baseCoefficientPerVoidFraction / (1.0 - a);
    }

    /**
     * Void reactivity, dk/k — the integral of the steepening coefficient from a
     * reference void fraction to the current one, which has a closed form:
     * <pre>
     *   rho_void = alpha_v0 * ln( (1 - a_ref) / (1 - a) )
     * </pre>
     * Negative for {@code a &gt; a_ref} with a negative base coefficient, and
     * steepening as void climbs.
     *
     * <p>A readout, for panels and CSV dumps. The live reactivity term belongs
     * to {@code ReactivityBalance}.
     */
    public static double voidReactivityDkOverK(double baseCoefficientPerVoidFraction,
                                               double referenceVoidFraction,
                                               double voidFraction) {
        double a = clamp(voidFraction, 0.0, MAX_VOID_FRACTION);
        double aRef = clamp(referenceVoidFraction, 0.0, MAX_VOID_FRACTION);
        return baseCoefficientPerVoidFraction * Math.log((1.0 - aRef) / (1.0 - a));
    }

    /**
     * The void coefficient at the void fraction this model last computed, dk/k
     * per unit void fraction, using
     * {@link PhysicalConstants#VOID_COEFF_PER_VOID_FRACTION} as the base.
     * Divide by 100 for the per-%-void form the literature quotes.
     */
    public double currentVoidCoefficientPerVoidFraction() {
        return voidCoefficientPerVoidFraction(
                PhysicalConstants.VOID_COEFF_PER_VOID_FRACTION, coreAverageVoidFraction);
    }

    // ---------------------------------------------------------------
    // Sensitivity probes
    // ---------------------------------------------------------------

    /**
     * Core-average void fraction at an arbitrary operating point with the inlet
     * subcooling already re-equilibrated, without disturbing the live state.
     *
     * <p>This is the <i>settled</i> answer. It deliberately does not show the
     * prompt subcooling excursion that {@link #update} produces on a pressure
     * change, because that excursion is a property of the lagged state and not
     * of the operating point. For tests, panel what-ifs and tuning.
     */
    public double steadyStateVoidFraction(double totalPowerFractionOfRated,
                                          double coreFlowFraction,
                                          double pressurePsig) {
        double power = finiteNonNegative(totalPowerFractionOfRated);
        double flow = Math.max(MINIMUM_FLOW_FRACTION, finiteNonNegative(coreFlowFraction));
        double psig = Double.isFinite(pressurePsig)
                ? pressurePsig : PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
        double psia = Saturation.psiaFromPsig(psig);

        double rise = power * config.ratedThermalMW * 1000.0 / (flow * ratedCoreFlowKgPerS);
        if (!(rise > 0.0)) {
            return 0.0;
        }
        double subcooling = Math.min(equilibriumSubcooling(power, flow, psig), rise);
        double boundary = subcooling / rise;
        double quality = clamp((rise - subcooling) / Saturation.latentHeatKJPerKg(psia), 0.0, 1.0);
        double ratio = Saturation.vapourDensityKgPerM3(psia) / Saturation.liquidDensityKgPerM3(psia);

        double boilingLength = 1.0 - boundary;
        if (!(boilingLength > 0.0) || !(quality > 0.0)) {
            return 0.0;
        }
        double sum = 0.0;
        for (int k = 0; k < axialNodes; k++) {
            sum += voidFractionFromQuality(quality * (k + 0.5) / axialNodes, ratio, slipRatio);
        }
        return clamp(boilingLength * sum / axialNodes, 0.0, MAX_VOID_FRACTION);
    }

    /**
     * Prompt sensitivity of core-average void fraction to pressure, per psi,
     * evaluated by central difference about the current state with core inlet
     * enthalpy held — i.e. what a pressure step does <i>before</i> the
     * recirculation loop catches up.
     *
     * <p><b>Negative.</b> Pressure up, void down. If this ever reads positive
     * the plant has become an RBMK and something is wrong upstream. About
     * -1.1e-3 per psi at rated conditions, which through the void coefficient is
     * roughly +10 pcm per psi — about a cent and a half of reactivity for every
     * psi of pressurisation.
     */
    public double promptVoidSensitivityPerPsi() {
        double delta = 5.0;
        double psia = Saturation.psiaFromPsig(pressurePsig);
        double savedAverage = quasiStaticVoidFraction;
        double savedExit = exitVoidFraction;
        double savedQuality = exitQuality;
        double savedBoundary = boilingBoundaryFraction;
        double savedRatio = densityRatio;
        double savedSubcooling = coreInletSubcoolingKJPerKg;

        double up = probeAtPressure(psia + delta);
        double down = probeAtPressure(psia - delta);

        quasiStaticVoidFraction = savedAverage;
        exitVoidFraction = savedExit;
        exitQuality = savedQuality;
        boilingBoundaryFraction = savedBoundary;
        densityRatio = savedRatio;
        coreInletSubcoolingKJPerKg = savedSubcooling;
        return (up - down) / (2.0 * delta);
    }

    private double probeAtPressure(double pressurePsia) {
        double psia = Saturation.clampPressurePsia(pressurePsia);
        recomputeVoidGeometry(psia, Saturation.latentHeatKJPerKg(psia));
        return quasiStaticVoidFraction;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    /**
     * Core-average void fraction, 0..1, lagged by the channel transit. This is
     * what the reactivity balance and the vessel level swell want.
     */
    public double getCoreAverageVoidFraction() {
        return coreAverageVoidFraction;
    }

    /**
     * Void fraction the current power, flow and pressure are asking for, before
     * the transit lag, 0..1. The difference between this and
     * {@link #getCoreAverageVoidFraction()} is how far behind the boiling is.
     */
    public double getQuasiStaticVoidFraction() {
        return quasiStaticVoidFraction;
    }

    /** Void fraction at the core exit, 0..1. About 0.70 at rated conditions. */
    public double getExitVoidFraction() {
        return exitVoidFraction;
    }

    /** Flow quality at the core exit, kg steam per kg of core flow. About 0.148 at rated. */
    public double getExitQuality() {
        return exitQuality;
    }

    /**
     * Height of the boiling boundary as a fraction of active fuel height, 0..1.
     * About 0.19 at rated: the bottom fifth of the core is not boiling, it is
     * heating subcooled water. A pressure rise pushes this number up and a power
     * rise pushes it down.
     */
    public double getBoilingBoundaryFraction() {
        return boilingBoundaryFraction;
    }

    /** Coolant enthalpy rise across the core, kJ/kg. Q over W. About 273 at rated. */
    public double getEnthalpyRiseKJPerKg() {
        return enthalpyRiseKJPerKg;
    }

    /**
     * Core inlet subcooling actually in effect, kJ/kg — derived from the lagged
     * inlet enthalpy against the current saturation enthalpy. Jumps promptly on
     * a pressure change and then relaxes.
     */
    public double getCoreInletSubcoolingKJPerKg() {
        return coreInletSubcoolingKJPerKg;
    }

    /** Core inlet enthalpy, kJ/kg — the lagged state, and what gets persisted. */
    public double getCoreInletEnthalpyKJPerKg() {
        return coreInletEnthalpyKJPerKg;
    }

    /** Core inlet subcooling the loop is relaxing toward, kJ/kg. About 52.5 at rated. */
    public double getEquilibriumSubcoolingKJPerKg() {
        return equilibriumSubcoolingKJPerKg;
    }

    /** Saturated vapour density over saturated liquid density at current pressure. */
    public double getDensityRatio() {
        return densityRatio;
    }

    /** Dome pressure the last update used, psig. */
    public double getPressurePsig() {
        return pressurePsig;
    }

    // ---------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------

    /** Slip ratio, steam velocity over liquid velocity. Clamped to at least 1. */
    public double getSlipRatio() {
        return slipRatio;
    }

    /** @see #getSlipRatio() */
    public void setSlipRatio(double slipRatio) {
        if (!(slipRatio >= 1.0) || !Double.isFinite(slipRatio)) {
            throw new IllegalArgumentException("slip ratio must be finite and at least 1, got " + slipRatio);
        }
        this.slipRatio = slipRatio;
    }

    /**
     * Feedwater enthalpy, kJ/kg. Colder feedwater means more core inlet
     * subcooling, a higher boiling boundary and less void — which is a real
     * reactivity handle a player can build a feedwater-temperature control
     * scheme around.
     */
    public double getFeedwaterEnthalpyKJPerKg() {
        return feedwaterEnthalpyKJPerKg;
    }

    /** @see #getFeedwaterEnthalpyKJPerKg() */
    public void setFeedwaterEnthalpyKJPerKg(double feedwaterEnthalpyKJPerKg) {
        if (!(feedwaterEnthalpyKJPerKg >= 0.0) || !Double.isFinite(feedwaterEnthalpyKJPerKg)) {
            throw new IllegalArgumentException(
                    "feedwater enthalpy must be finite and non-negative, got " + feedwaterEnthalpyKJPerKg);
        }
        this.feedwaterEnthalpyKJPerKg = feedwaterEnthalpyKJPerKg;
    }

    /** Convenience: set feedwater enthalpy from a temperature in degrees C. */
    public void setFeedwaterTemperatureC(double feedwaterTemperatureC) {
        setFeedwaterEnthalpyKJPerKg(Saturation.subcooledLiquidEnthalpyKJPerKg(feedwaterTemperatureC));
    }

    /** Recirculation loop transit time, seconds — the inlet subcooling lag. */
    public double getRecirculationTransitTimeConstantSeconds() {
        return recirculationTransitTimeConstantSeconds;
    }

    /** @see #getRecirculationTransitTimeConstantSeconds() */
    public void setRecirculationTransitTimeConstantSeconds(double seconds) {
        if (!(seconds >= 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException(
                    "transit time constant must be finite and non-negative, got " + seconds);
        }
        this.recirculationTransitTimeConstantSeconds = seconds;
    }

    /**
     * Void transit time, seconds — the lag between a change in boiling rate and
     * the void fraction that results. See {@link #DEFAULT_VOID_TRANSIT_TIME_S};
     * setting it to zero makes the void-power feedback algebraic and the
     * coupled loop will oscillate at tick resolution.
     */
    public double getVoidTransitTimeConstantSeconds() {
        return voidTransitTimeConstantSeconds;
    }

    /** @see #getVoidTransitTimeConstantSeconds() */
    public void setVoidTransitTimeConstantSeconds(double seconds) {
        if (!(seconds >= 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException(
                    "void transit time constant must be finite and non-negative, got " + seconds);
        }
        this.voidTransitTimeConstantSeconds = seconds;
    }

    /** Axial nodes used for the void integral. Accuracy knob; 20 is ample. */
    public int getAxialNodes() {
        return axialNodes;
    }

    /** @see #getAxialNodes() */
    public void setAxialNodes(int axialNodes) {
        if (axialNodes < 1) {
            throw new IllegalArgumentException("axial nodes must be at least 1, got " + axialNodes);
        }
        this.axialNodes = axialNodes;
    }

    /** Rated core mass flow, kg/s, that {@code coreFlowFraction} is a fraction of. */
    public double getRatedCoreFlowKgPerS() {
        return ratedCoreFlowKgPerS;
    }

    /** @see #getRatedCoreFlowKgPerS() */
    public void setRatedCoreFlowKgPerS(double ratedCoreFlowKgPerS) {
        if (!(ratedCoreFlowKgPerS > 0.0) || !Double.isFinite(ratedCoreFlowKgPerS)) {
            throw new IllegalArgumentException(
                    "rated core flow must be finite and positive, got " + ratedCoreFlowKgPerS);
        }
        this.ratedCoreFlowKgPerS = ratedCoreFlowKgPerS;
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    /**
     * Restore the lagged core inlet enthalpy after a chunk reload, so a vessel
     * resumes mid-transient instead of snapping to equilibrium. Persistence only.
     */
    public void restoreCoreInletEnthalpyKJPerKg(double enthalpyKJPerKg) {
        this.coreInletEnthalpyKJPerKg =
                (Double.isFinite(enthalpyKJPerKg) && enthalpyKJPerKg > 0.0) ? enthalpyKJPerKg : 0.0;
    }

    /** Restore the lagged void fraction after a chunk reload. Persistence only. */
    public void restoreCoreAverageVoidFraction(double voidFraction) {
        this.coreAverageVoidFraction = clamp(voidFraction, 0.0, MAX_VOID_FRACTION);
    }

    /**
     * Put the lagged state at its equilibrium for an operating point and
     * recompute, for a run that starts already at power.
     */
    public void initialiseToSteadyState(double totalPowerFractionOfRated,
                                        double coreFlowFraction,
                                        double pressurePsig) {
        double psig = Double.isFinite(pressurePsig)
                ? pressurePsig : PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
        this.coreInletEnthalpyKJPerKg =
                Saturation.liquidEnthalpyKJPerKg(Saturation.psiaFromPsig(psig))
                        - equilibriumSubcooling(totalPowerFractionOfRated, coreFlowFraction, psig);
        update(totalPowerFractionOfRated, coreFlowFraction, psig, 0.0);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double finiteNonNegative(double value) {
        return (Double.isFinite(value) && value > 0.0) ? value : 0.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.min(maximum, Math.max(minimum, value));
    }

    @Override
    public String toString() {
        return String.format(
                "VoidModel[a_avg=%.4f a_exit=%.4f x_exit=%.4f z_boil=%.3f dH=%.1f dH_sub=%.1f kJ/kg P=%.1f psig]",
                coreAverageVoidFraction, exitVoidFraction, exitQuality, boilingBoundaryFraction,
                enthalpyRiseKJPerKg, coreInletSubcoolingKJPerKg, pressurePsig);
    }
}
