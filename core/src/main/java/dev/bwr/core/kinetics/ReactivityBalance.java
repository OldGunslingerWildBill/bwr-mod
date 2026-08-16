package dev.bwr.core.kinetics;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;

/**
 * The reactivity balance of {@code SPEC.md} section 1.2:
 * <pre>
 *   rho = rho_rods + rho_void + rho_doppler + rho_xenon + rho_pressure + rho_fuel + rho_boron
 * </pre>
 *
 * <p>Every term is separately readable. That is a requirement, not a
 * convenience: control logic lives in the player's Lua, and someone debugging
 * why their reactor did something unexpected needs to see <i>which</i> term
 * moved, not just that the total changed. A power rise driven by void collapse
 * and one driven by a xenon burnout look identical in the total and completely
 * different in the breakdown.
 *
 * <h2>Usage</h2>
 * Set the measured inputs each tick, then read the components. Inputs are
 * measurements the rest of the model produces; the setters marked as
 * calibration are model constants a fuel registry or datapack may retune.
 * Getters evaluate straight off the inputs, so there is no stale-read hazard
 * and no ordering requirement beyond "set before you read".
 *
 * <h2>The getters do not write anything, and that is load-bearing</h2>
 * Every component getter is a pure function of the input fields. It was
 * tempting to cache the seven components and have each getter refresh the cache
 * when a dirty flag was set, and that is exactly the shape that turns a reader
 * into a writer. The owner of this object is the server tick thread; the
 * CC:Tweaked peripheral reads the same instance from the computer thread, on a
 * {@code @LuaFunction} with no {@code mainThread = true}. With a self-refreshing
 * cache, two threads mutate the same eight fields with no synchronisation
 * between them and no field volatile — so a Lua program polling
 * {@code getReactivity()} in a loop could hand the server thread a total that it
 * had itself written from a half-updated input set, and that number goes
 * straight into the kinetics as this tick's rho. Recomputing is a handful of
 * transcendentals and costs nothing at tick rate. <b>Do not reintroduce the
 * cache.</b>
 *
 * <p>Cross-thread readers that want a set of components guaranteed to have come
 * from one consistent set of inputs should read {@link #publishedBreakdown()},
 * which hands back the immutable snapshot the owning thread stored on its last
 * {@link #update()} rather than evaluating against inputs that may be mid-write.
 *
 * <h2>Signs</h2>
 * Void and Doppler coefficients are negative, and that is the whole reason
 * this is a BWR. More void means less moderator means less reactivity; hotter
 * fuel means broader resonances means more capture means less reactivity. Get
 * either sign wrong and the reactor becomes an RBMK.
 *
 * <p>Nothing here judges the result. There is no acceptable range, no alarm,
 * no shutdown margin check.
 */
public final class ReactivityBalance {

    /** Ordered component names, parallel to {@link Breakdown#toArray()}. */
    public static final String[] COMPONENT_NAMES = {
            "rods", "void", "doppler", "xenon", "pressure", "fuel", "boron"};

    /**
     * Void fraction is clamped below this before the void integral is taken.
     * The {@code 1/(1-a)} coefficient scaling diverges at {@code a = 1}, which
     * is a dry core — a condition the severe accident model owns, not the void
     * reactivity term.
     */
    public static final double MAX_VOID_FRACTION = 0.999;

    /**
     * The pressure coefficient to use <i>only</i> if the void model ignores
     * pressure, dk/k per psi. Roughly one cent per psi, the order of magnitude
     * quoted for BWR pressure coefficients, and positive because raising
     * pressure collapses steam bubbles back into moderator.
     *
     * <p>See {@link #setPressureCoefficientDkOverKPerPsi} before using it — the
     * default of zero is correct for the void model {@code SPEC.md} section 6.3
     * actually asks for.
     */
    public static final double VOID_COLLAPSE_PRESSURE_COEFFICIENT_PER_PSI = 6.5e-5;

    // --- measured inputs ---------------------------------------------
    private double rodWorthDkOverK;
    private double voidFraction;
    private double fuelTemperatureC = 20.0;
    private double xenonConcentration;
    private double pressurePsig = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
    private double fuelExcessReactivityDkOverK;
    private double boronPpm;

    // --- calibration --------------------------------------------------
    private double voidCoefficientPerVoidFraction = PhysicalConstants.VOID_COEFF_PER_VOID_FRACTION;
    private double voidCoefficientBurnupScale = 1.0;
    private double referenceVoidFraction = 0.0;

    private double dopplerCoefficientPerCAtAnchor = PhysicalConstants.DOPPLER_COEFF_PER_C;
    private double dopplerAnchorTemperatureC = 600.0;
    private double dopplerZeroTemperatureC = 20.0;

    private double xenonWorthPerUnitConcentration = -0.028;

    private double pressureCoefficientDkOverKPerPsi = 0.0;
    private double referencePressurePsig = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

    private double boronMixingEffectiveness =
            PhysicalConstants.SLC_SHUTDOWN_BORON_PPM / PhysicalConstants.SLC_DESIGN_BORON_PPM; // 0.8

    // --- published ----------------------------------------------------

    /**
     * The last snapshot {@link #update()} took, for readers on other threads.
     *
     * <p>Volatile so the reference and the record behind it are safely
     * published; the record itself is immutable, so a reader either sees a
     * complete earlier balance or a complete later one and never a mixture. This
     * is the only field any thread other than the owner should touch, and it is
     * written on exactly one code path.
     */
    private volatile Breakdown published;

    /** Defaults with no fuel excess reactivity loaded. */
    public ReactivityBalance() {
    }

    /**
     * @param config supplies the fresh-core excess reactivity as the initial
     *               {@code rho_fuel}. Values are copied, not held by reference.
     */
    public ReactivityBalance(CoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.fuelExcessReactivityDkOverK = config.excessReactivity;
    }

    // ---------------------------------------------------------------
    // Computation
    // ---------------------------------------------------------------

    /**
     * Evaluate the whole balance, publish it as an immutable snapshot for
     * readers on other threads, and return the net reactivity, dk/k.
     *
     * <p>The value returned is the one inside the snapshot that was just built,
     * never a field another thread could have overwritten in between. That
     * matters because this return value is what the reactor hands to
     * {@code PointKinetics.step} as this tick's rho.
     *
     * <p>Only the owning thread may call this: it is the one write in the class.
     */
    public double update() {
        Breakdown snapshot = breakdown();
        published = snapshot;
        return snapshot.totalDkOverK();
    }

    /**
     * Void reactivity, integrating a coefficient that steepens with void.
     *
     * <p>A constant void coefficient is wrong in a way that matters. The NRC
     * manual's own worked example [TTC 1.7.2.1.2]: a 1% void increment removes
     * about 1.1% of the water still present at 10% void, but about 3.45% of it
     * at 70% void. The same bubble displaces a far larger share of what is left
     * when little is left. So the coefficient scales as
     * <pre>   alpha_v(a) = alpha_v0 / (1 - a)</pre>
     * — a ratio of 3.0 between those two points against the manual's 3.14,
     * which is as close as a one-parameter form gets.
     *
     * <p>Reactivity is the integral of that coefficient from the reference void
     * fraction to the current one, which has a closed form:
     * <pre>   rho_void = alpha_v0 * ln( (1 - a_ref) / (1 - a) )</pre>
     * With {@code alpha_v0} negative this is negative for {@code a > a_ref},
     * and it steepens as void climbs. A flat coefficient understates feedback
     * at the top of the core, which is exactly where BWR behaviour lives.
     */
    private double computeVoid() {
        double a = clampVoid(voidFraction);
        double aRef = clampVoid(referenceVoidFraction);
        double coefficient = voidCoefficientPerVoidFraction * voidCoefficientBurnupScale;
        return coefficient * Math.log((1.0 - aRef) / (1.0 - a));
    }

    private static double clampVoid(double a) {
        if (!Double.isFinite(a) || a < 0.0) {
            return 0.0;
        }
        return Math.min(a, MAX_VOID_FRACTION);
    }

    /**
     * Doppler reactivity in the square-root-of-temperature form
     * {@code SPEC.md} section 1.2 asks for:
     * <pre>   rho_doppler = A * ( sqrt(T) - sqrt(T_zero) ),   A = 2 * alpha_D * sqrt(T_anchor)</pre>
     * with temperatures in kelvin.
     *
     * <p>Resonance broadening goes as {@code sqrt(T)}, so the coefficient is
     * strongest in a cold core and weakens as the fuel heats. {@code A} is
     * chosen so the local slope {@code d(rho)/dT = A / (2*sqrt(T))} equals the
     * quoted linear coefficient at the anchor temperature, which is how a
     * single quoted number like -1.6e-5 dk/k per degC gets turned into a curve
     * without inventing a second number. With the defaults, the Doppler defect
     * from cold to a 600 degC average fuel temperature is about -1180 pcm.
     *
     * <p>This term must respond on the fuel temperature time constant with no
     * further lag. It is the prompt negative feedback that terminates a power
     * excursion [TTC 1.7.2.1.3], and if it is delayed the low-beta fuels behave
     * wrongly — a Pu core survives on Doppler arriving in milliseconds.
     */
    private double computeDoppler() {
        double anchorK = dopplerAnchorTemperatureC + 273.15;
        double zeroK = dopplerZeroTemperatureC + 273.15;
        double fuelK = fuelTemperatureC + 273.15;
        if (fuelK < 0.0) {
            fuelK = 0.0;
        }
        double a = 2.0 * dopplerCoefficientPerCAtAnchor * Math.sqrt(anchorK);
        return a * (Math.sqrt(fuelK) - Math.sqrt(zeroK));
    }

    /** Xe-135 poisoning against the model's concentration units, dk/k. */
    private double computeXenon() {
        return xenonWorthPerUnitConcentration * xenonConcentration;
    }

    /** Direct pressure reactivity against the reference pressure, dk/k. */
    private double computePressure() {
        return pressureCoefficientDkOverKPerPsi * (pressurePsig - referencePressurePsig);
    }

    /** Dissolved boron reactivity, dk/k. Negative. */
    private double computeBoron() {
        return -PhysicalConstants.BORON_WORTH_PER_PPM * boronMixingEffectiveness * boronPpm;
    }

    // ---------------------------------------------------------------
    // Components. Every one of these is a pure read — see the class
    // comment on why none of them may write a field.
    // ---------------------------------------------------------------

    /** Control rod worth currently inserted, dk/k. Negative. */
    public double getRodsDkOverK() {
        return rodWorthDkOverK;
    }

    /** Void reactivity relative to the reference void fraction, dk/k. Negative as void rises. */
    public double getVoidDkOverK() {
        return computeVoid();
    }

    /** Doppler (fuel temperature) reactivity, dk/k. Negative as fuel heats. */
    public double getDopplerDkOverK() {
        return computeDoppler();
    }

    /** Xe-135 poisoning, dk/k. Negative. */
    public double getXenonDkOverK() {
        return computeXenon();
    }

    /** Direct pressure reactivity, dk/k. Zero unless a coefficient has been set. */
    public double getPressureDkOverK() {
        return computePressure();
    }

    /** Excess reactivity of the loaded fuel, dk/k. Positive in a core that can go critical. */
    public double getFuelDkOverK() {
        return fuelExcessReactivityDkOverK;
    }

    /** Dissolved boron reactivity from SLC injection, dk/k. Negative. */
    public double getBoronDkOverK() {
        return computeBoron();
    }

    /** Net reactivity, dk/k — the sum of all seven components. */
    public double getTotalDkOverK() {
        return breakdown().totalDkOverK();
    }

    /**
     * Immutable snapshot of the whole balance, for the peripheral readout and
     * CSV dumps. Evaluated fresh from the inputs as they stand, so the total it
     * carries is always exactly the sum of the components it carries.
     */
    public Breakdown breakdown() {
        double rods = rodWorthDkOverK;
        double voidTerm = computeVoid();
        double doppler = computeDoppler();
        double xenon = computeXenon();
        double pressure = computePressure();
        double fuel = fuelExcessReactivityDkOverK;
        double boron = computeBoron();
        return new Breakdown(rods, voidTerm, doppler, xenon, pressure, fuel, boron,
                rods + voidTerm + doppler + xenon + pressure + fuel + boron);
    }

    /**
     * The balance as of the owning thread's last {@link #update()}, or a fresh
     * {@link #breakdown()} if it has never been called.
     *
     * <p>This is what a reader on another thread — the CC:Tweaked peripheral,
     * a client sync, a logger — should use. {@link #breakdown()} evaluates
     * against the input fields live, so a reader racing the tick can catch them
     * part-written and get a balance mixing this tick's void with last tick's
     * fuel temperature. This returns a snapshot the owning thread finished
     * building, so its components are all from one moment even though that
     * moment may be up to a tick old. Neither call mutates anything.
     */
    public Breakdown publishedBreakdown() {
        Breakdown snapshot = published;
        return snapshot != null ? snapshot : breakdown();
    }

    /**
     * One reactivity balance, all components in dk/k.
     *
     * @param rodsDkOverK     control rods, negative
     * @param voidDkOverK     moderator void, negative as void rises
     * @param dopplerDkOverK  fuel temperature, negative as fuel heats
     * @param xenonDkOverK    Xe-135, negative
     * @param pressureDkOverK direct pressure effect, positive on a pressure rise
     * @param fuelDkOverK     loaded fuel excess reactivity, positive
     * @param boronDkOverK    dissolved boron, negative
     * @param totalDkOverK    the sum
     */
    public record Breakdown(
            double rodsDkOverK,
            double voidDkOverK,
            double dopplerDkOverK,
            double xenonDkOverK,
            double pressureDkOverK,
            double fuelDkOverK,
            double boronDkOverK,
            double totalDkOverK
    ) {
        /** Components in {@link ReactivityBalance#COMPONENT_NAMES} order, excluding the total. */
        public double[] toArray() {
            return new double[]{rodsDkOverK, voidDkOverK, dopplerDkOverK, xenonDkOverK,
                    pressureDkOverK, fuelDkOverK, boronDkOverK};
        }
    }

    // ---------------------------------------------------------------
    // Instantaneous coefficients — measurements, useful on a panel
    // ---------------------------------------------------------------

    /**
     * The void coefficient at the current void fraction, dk/k per unit void
     * fraction. Divide by 100 for the per-%-void form the literature quotes.
     */
    public double currentVoidCoefficientPerVoidFraction() {
        double a = clampVoid(voidFraction);
        return voidCoefficientPerVoidFraction * voidCoefficientBurnupScale / (1.0 - a);
    }

    /** The Doppler coefficient at the current fuel temperature, dk/k per degree C. */
    public double currentDopplerCoefficientPerC() {
        double fuelK = Math.max(1.0, fuelTemperatureC + 273.15);
        double a = 2.0 * dopplerCoefficientPerCAtAnchor * Math.sqrt(dopplerAnchorTemperatureC + 273.15);
        return a / (2.0 * Math.sqrt(fuelK));
    }

    /** Convert an infinite multiplication factor to reactivity: {@code (k - 1) / k}. */
    public static double reactivityFromKInfinity(double kInfinity) {
        if (!(kInfinity > 0.0) || !Double.isFinite(kInfinity)) {
            throw new IllegalArgumentException("k_inf must be finite and positive, got " + kInfinity);
        }
        return (kInfinity - 1.0) / kInfinity;
    }

    // ---------------------------------------------------------------
    // Measured inputs
    // ---------------------------------------------------------------

    /** Total worth of all inserted rods, dk/k, from {@link RodWorth}. Negative. */
    public void setRodWorthDkOverK(double rodWorthDkOverK) {
        this.rodWorthDkOverK = rodWorthDkOverK;
    }

    /** Core-average void fraction, 0..1, from the thermal hydraulics. */
    public void setVoidFraction(double voidFraction) {
        this.voidFraction = voidFraction;
    }

    /** Volume-average fuel temperature, degrees C. */
    public void setFuelTemperatureC(double fuelTemperatureC) {
        this.fuelTemperatureC = fuelTemperatureC;
    }

    /**
     * Xe-135 concentration in the units the xenon model uses. The default worth
     * coefficient assumes those units are normalised so 1.0 is the equilibrium
     * inventory at rated power; see {@link #setXenonWorthPerUnitConcentration}.
     */
    public void setXenonConcentration(double xenonConcentration) {
        this.xenonConcentration = xenonConcentration;
    }

    /** Reactor steam dome pressure, psig. */
    public void setPressurePsig(double pressurePsig) {
        this.pressurePsig = pressurePsig;
    }

    /**
     * Excess reactivity of the loaded fuel with all rods withdrawn and no
     * feedback, dk/k. The aggregate of every assembly's {@code k_inf} after
     * burnup and gadolinia penalties; see {@link #reactivityFromKInfinity}.
     * Falls through the cycle, and end of cycle is when it can no longer
     * overcome the feedback terms.
     */
    public void setFuelExcessReactivityDkOverK(double fuelExcessReactivityDkOverK) {
        this.fuelExcessReactivityDkOverK = fuelExcessReactivityDkOverK;
    }

    /** Dissolved boron concentration from SLC injection, ppm. */
    public void setBoronPpm(double boronPpm) {
        this.boronPpm = boronPpm;
    }

    // ---------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------

    /**
     * Void coefficient at zero void, dk/k per unit void fraction. Negative.
     * Defaults to {@link PhysicalConstants#VOID_COEFF_PER_VOID_FRACTION}. The
     * value at any other void fraction is this scaled by {@code 1/(1-a)}.
     */
    public void setVoidCoefficientPerVoidFraction(double voidCoefficientPerVoidFraction) {
        this.voidCoefficientPerVoidFraction = voidCoefficientPerVoidFraction;
    }

    /**
     * Multiplier on the void coefficient, default 1.0. Both the void and
     * Doppler coefficients drift with burnup, generally becoming less negative
     * through the cycle [TTC 1.7.2.1.2], so a late-cycle core is twitchier than
     * a fresh one. A fuel model sets this below 1.0 as burnup accumulates.
     */
    public void setVoidCoefficientBurnupScale(double voidCoefficientBurnupScale) {
        this.voidCoefficientBurnupScale = voidCoefficientBurnupScale;
    }

    /**
     * Void fraction at which the void term reads zero, default 0.0. This is
     * the state the fuel excess reactivity is quoted against; move it only if
     * the fuel model quotes excess reactivity at operating void instead of at
     * zero void.
     */
    public void setReferenceVoidFraction(double referenceVoidFraction) {
        this.referenceVoidFraction = referenceVoidFraction;
    }

    /** Linear Doppler coefficient, dk/k per degree C, applying at the anchor temperature. Negative. */
    public void setDopplerCoefficientPerCAtAnchor(double dopplerCoefficientPerCAtAnchor) {
        this.dopplerCoefficientPerCAtAnchor = dopplerCoefficientPerCAtAnchor;
    }

    /** Fuel temperature at which the quoted Doppler coefficient is the true local slope, degrees C. */
    public void setDopplerAnchorTemperatureC(double dopplerAnchorTemperatureC) {
        this.dopplerAnchorTemperatureC = dopplerAnchorTemperatureC;
    }

    /** Fuel temperature at which the Doppler term reads zero, degrees C. Default 20. */
    public void setDopplerZeroTemperatureC(double dopplerZeroTemperatureC) {
        this.dopplerZeroTemperatureC = dopplerZeroTemperatureC;
    }

    /**
     * Xenon worth per unit of the xenon model's concentration units, dk/k.
     * Negative. Default -0.028, i.e. -2800 pcm at a concentration of 1.0, on
     * the assumption that the xenon model normalises 1.0 to the equilibrium
     * inventory at rated power. Retune this together with the xenon model, not
     * separately — the coefficient and the concentration units only mean
     * anything as a pair.
     *
     * <p>Two wirings work, and they are numerically identical:
     * feed the xenon inventory as a multiple of its rated-power equilibrium and
     * leave this coefficient at its default; or feed the raw inventory in
     * atoms per cm^3 and set this to the xenon model's worth per atom per cm^3.
     * The first needs no calibration transfer and is the safer default.
     */
    public void setXenonWorthPerUnitConcentration(double xenonWorthPerUnitConcentration) {
        this.xenonWorthPerUnitConcentration = xenonWorthPerUnitConcentration;
    }

    /**
     * Direct pressure coefficient, dk/k per psi. <b>Default zero, and that is
     * usually correct.</b>
     *
     * <p>{@code SPEC.md} section 6.3 routes the pressure effect through void:
     * pressure rises, bubbles collapse, moderator density rises, reactivity
     * rises. If the thermal hydraulics computes void fraction as
     * {@code f(power, flow, pressure)} as the spec requires, then the entire
     * pressurisation transient — MSIV closure, pressure spike, void collapse,
     * power surge — is already produced by the void term, and adding a
     * pressure coefficient here would count it twice.
     *
     * <p>Set this to {@link #VOID_COLLAPSE_PRESSURE_COEFFICIENT_PER_PSI} only
     * if paired with a void correlation that ignores pressure. A small negative
     * value is also physically defensible on its own terms if void collapse is
     * handled elsewhere: at saturation a higher pressure means a higher
     * saturation temperature and therefore slightly less dense liquid water,
     * which is a weak loss of moderator worth roughly -7e-6 per psi.
     */
    public void setPressureCoefficientDkOverKPerPsi(double pressureCoefficientDkOverKPerPsi) {
        this.pressureCoefficientDkOverKPerPsi = pressureCoefficientDkOverKPerPsi;
    }

    /** Pressure at which the pressure term reads zero, psig. Defaults to rated dome pressure. */
    public void setReferencePressurePsig(double referencePressurePsig) {
        this.referencePressurePsig = referencePressurePsig;
    }

    /**
     * Fraction of injected boron that is actually where it can absorb, default
     * 0.8.
     *
     * <p>Derived, not invented: the manual requires 600 ppm for a 0.05 dk/k
     * shutdown margin but specifies a 750 ppm tank concentration, the extra 25%
     * covering imperfect mixing and leakage [TTC 7.4]. 600/750 = 0.8. With this
     * factor, injecting the full design concentration delivers exactly the
     * design shutdown margin, and boron worth ramps slightly behind injected
     * mass the way the manual's own margin implies. Set to 1.0 for perfect
     * mixing.
     */
    public void setBoronMixingEffectiveness(double boronMixingEffectiveness) {
        if (!(boronMixingEffectiveness >= 0.0) || !Double.isFinite(boronMixingEffectiveness)) {
            throw new IllegalArgumentException(
                    "boron mixing effectiveness must be finite and non-negative, got "
                            + boronMixingEffectiveness);
        }
        this.boronMixingEffectiveness = boronMixingEffectiveness;
    }

    // ---------------------------------------------------------------
    // Calibration readback
    // ---------------------------------------------------------------

    public double getVoidCoefficientPerVoidFraction() {
        return voidCoefficientPerVoidFraction;
    }

    public double getVoidCoefficientBurnupScale() {
        return voidCoefficientBurnupScale;
    }

    public double getReferenceVoidFraction() {
        return referenceVoidFraction;
    }

    public double getDopplerCoefficientPerCAtAnchor() {
        return dopplerCoefficientPerCAtAnchor;
    }

    public double getDopplerAnchorTemperatureC() {
        return dopplerAnchorTemperatureC;
    }

    public double getDopplerZeroTemperatureC() {
        return dopplerZeroTemperatureC;
    }

    public double getXenonWorthPerUnitConcentration() {
        return xenonWorthPerUnitConcentration;
    }

    public double getPressureCoefficientDkOverKPerPsi() {
        return pressureCoefficientDkOverKPerPsi;
    }

    public double getReferencePressurePsig() {
        return referencePressurePsig;
    }

    public double getBoronMixingEffectiveness() {
        return boronMixingEffectiveness;
    }

    // ---------------------------------------------------------------
    // Input readback
    // ---------------------------------------------------------------

    public double getVoidFraction() {
        return voidFraction;
    }

    public double getFuelTemperatureC() {
        return fuelTemperatureC;
    }

    public double getXenonConcentration() {
        return xenonConcentration;
    }

    public double getPressurePsig() {
        return pressurePsig;
    }

    public double getFuelExcessReactivityDkOverK() {
        return fuelExcessReactivityDkOverK;
    }

    public double getBoronPpm() {
        return boronPpm;
    }
}
