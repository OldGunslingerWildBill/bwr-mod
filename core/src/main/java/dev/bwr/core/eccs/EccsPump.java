package dev.bwr.core.eccs;

import dev.bwr.core.thermal.Saturation;

/**
 * One emergency core cooling pump, as a piece of machinery.
 *
 * <p>Give it a {@link PumpDesign}, tell it each tick what the vessel pressure
 * is, what its drive has available and how much water its suction can supply,
 * and it reports what it actually delivers. All of the interesting behaviour is
 * a consequence of the numbers rather than of a rule:
 *
 * <ul>
 *   <li>Flow falls to zero as vessel pressure approaches the pump's shutoff
 *       head — {@link PumpCurve}. Nothing refuses to run; the water just stops
 *       arriving.</li>
 *   <li>A turbine drive loses power as the vessel blows down —
 *       {@link SteamTurbineDrive} — until it cannot overcome its own windage.</li>
 *   <li>A motor drive on a weak bus runs slower, and because head goes as the
 *       square of speed, a brownout can take a low pressure pump below its
 *       shutoff head entirely.</li>
 *   <li>Speed follows a first-order lag, so nothing appears instantly. RCIC
 *       takes half a minute to come up.</li>
 * </ul>
 *
 * <h2>No logic lives here</h2>
 * This class never starts, stops or throttles itself. {@link #setRunning} and
 * {@link #setFlowDemandFraction} are actuators driven from outside — in the mod,
 * ultimately from the player's Lua. It has no opinion about vessel level, no
 * initiation signal and no notion of an accident. It is a pump.
 *
 * <p>Pure Java, no Minecraft imports. Not thread safe; one instance per machine,
 * stepped from the server thread.
 */
public final class EccsPump {

    /** Electrical to shaft conversion for the motor-driven machines. */
    public static final double MOTOR_EFFICIENCY = 0.95;

    /** Below this the pump is treated as stopped rather than crawling. */
    private static final double SPEED_DEADBAND = 1.0e-4;

    private final PumpDesign design;

    // --- commanded state ---------------------------------------------
    private boolean running;
    private double flowDemandFraction = 1.0;
    private double speedDemandFraction = 1.0;

    // --- inputs, refreshed every step --------------------------------
    private double vesselPressurePsig;
    private double suctionPressurePsig;
    private double exhaustPressurePsig;
    private double suctionTemperatureC = 32.0;
    private double electricalPowerAvailableWatts;
    private double suctionFlowLimitKgPerS = Double.MAX_VALUE;
    private double steamSupplyLimitKgPerS = Double.MAX_VALUE;
    private double steamInletPressurePsig = Double.NaN;

    // --- state -------------------------------------------------------
    private double speedFraction;

    // --- outputs -----------------------------------------------------
    private double flowKgPerS;
    private double shaftPowerWatts;
    private double steamDemandKgPerS;
    private double electricalDemandWatts;
    private double achievableSpeedFraction;

    public EccsPump(PumpDesign design) {
        if (design == null) {
            throw new IllegalArgumentException("design must not be null");
        }
        this.design = design;
    }

    public PumpDesign design() {
        return design;
    }

    // -----------------------------------------------------------------
    // Actuators. Bare, like everything else in this codebase.
    // -----------------------------------------------------------------

    /**
     * Start or stop the pump. Checks nothing: it does not look at level, at
     * pressure, at whether a suction source is lined up or at whether the drive
     * has any power. Deciding when to start an emergency pump is the player's
     * job, written in Lua, and the deliberate absence of any condition here is
     * the point of the project.
     */
    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Commanded flow as a fraction of the design's rated flow, 0 to 1.
     *
     * <p>This is the discharge throttle, not a level setpoint and not a speed.
     * The machine spins up to full speed and the flow control valve holds the
     * commanded flow, exactly as the real 700 gpm RCIC flow controller does
     * [TTC 2.7] — which is also why a pump asked for rated flow against a
     * nearly empty vessel delivers rated flow instead of running out to half
     * again as much.
     *
     * <p>If the pump curve cannot reach the commanded flow against the present
     * vessel pressure, the valve is simply wide open and the curve wins.
     */
    public void setFlowDemandFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return;
        }
        this.flowDemandFraction = Math.min(1.0, Math.max(0.0, fraction));
    }

    public double getFlowDemandFraction() {
        return flowDemandFraction;
    }

    /** Operator shaft-speed ceiling, independent of the existing discharge flow throttle. */
    public void setSpeedDemandFraction(double fraction) {
        if (Double.isFinite(fraction)) speedDemandFraction = clampFraction(fraction);
    }
    public double getSpeedDemandFraction() { return speedDemandFraction; }

    // -----------------------------------------------------------------
    // Inputs
    // -----------------------------------------------------------------

    /** Pressure the pump is discharging into, psig. */
    public void setVesselPressurePsig(double psig) {
        this.vesselPressurePsig = psig;
    }

    /** Pressure at the pump suction, psig. Roughly zero for a pool or a tank. */
    public void setSuctionPressurePsig(double psig) {
        this.suctionPressurePsig = psig;
    }

    /**
     * Back pressure on a turbine drive, psig — the containment airspace the
     * exhaust discharges into. A containment that has been allowed to
     * pressurise takes power away from the pumps keeping the core covered.
     */
    public void setExhaustPressurePsig(double psig) {
        this.exhaustPressurePsig = psig;
    }
    public double getExhaustPressurePsig() { return exhaustPressurePsig; }
    public double getSteamInletPressurePsig() { return driveInletPressurePsig(); }
    public double getVesselPressurePsig() { return vesselPressurePsig; }

    /** Temperature of the water at the suction, degrees C. */
    public void setSuctionTemperatureC(double temperatureC) {
        this.suctionTemperatureC = temperatureC;
    }

    public double getSuctionTemperatureC() {
        return suctionTemperatureC;
    }

    /** Electrical power presently available to a motor drive, watts. */
    public void setElectricalPowerAvailableWatts(double watts) {
        this.electricalPowerAvailableWatts = Math.max(0.0, watts);
    }

    /**
     * Most the suction source can actually supply this step, kg/s. A tank that
     * is nearly empty, or a pool that is down to the level where the pumps lose
     * suction, limits the pump regardless of what the impeller could do.
     */
    public void setSuctionFlowLimitKgPerS(double kgPerS) {
        this.suctionFlowLimitKgPerS = Math.max(0.0, kgPerS);
    }

    /** Physical supply at a piped turbine inlet. Older machines retain the design limit. */
    public void setSteamSupplyLimitKgPerS(double kgPerS) {
        steamSupplyLimitKgPerS = Double.isFinite(kgPerS) ? Math.max(0.0, kgPerS) : 0.0;
    }

    /** Independent drive pressure; water discharge pressure still sets the pump load. */
    public void setSteamInletPressurePsig(double psig) {
        steamInletPressurePsig = Double.isFinite(psig) ? Math.max(0.0, psig) : 0.0;
    }

    private double driveInletPressurePsig() {
        return Double.isNaN(steamInletPressurePsig) ? vesselPressurePsig : steamInletPressurePsig;
    }

    // -----------------------------------------------------------------
    // The step
    // -----------------------------------------------------------------

    /** Advance the machine by one interval. */
    public void step(double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }

        double dp = differentialPressurePsi();
        // The whole shaft power balance goes into the speed solve, windage
        // included, rather than windage being deducted at its full-speed value up
        // front. Windage goes as s^3, so billing the s=1 figure against a machine
        // that is going to settle at half speed both over-charges it and leaves
        // the bisected function flat once the flow control valve saturates --
        // which made the solved speed jump discontinuously from part speed to
        // exactly 1.0 across a fraction of a psi. See shaftPowerAtSpeed.
        achievableSpeedFraction = solveAchievableSpeed(dp, availableShaftPowerWatts());

        double target = running ? Math.min(speedDemandFraction, achievableSpeedFraction) : 0.0;
        double tau = (target >= speedFraction)
                ? Math.max(0.1, design.startupSeconds() / 3.0)
                : Math.max(0.1, design.coastdownSeconds() / 3.0);
        speedFraction += (target - speedFraction) * (1.0 - Math.exp(-dtSeconds / tau));
        if (speedFraction < SPEED_DEADBAND) {
            speedFraction = 0.0;
        }

        flowKgPerS = deliveredFlowKgPerS(dp, speedFraction);

        // The same expression the speed solve bisected on, so the power the drive
        // was sized against and the power reported afterwards -- and therefore the
        // steam a turbine drive is charged for -- cannot disagree.
        shaftPowerWatts = (speedFraction > 0.0) ? shaftPowerAtSpeed(dp, speedFraction) : 0.0;

        if (design.drive() == PumpDesign.Drive.STEAM_TURBINE) {
            double demand = SteamTurbineDrive.steamDemandKgPerS(shaftPowerWatts,
                    Saturation.psiaFromPsig(driveInletPressurePsig()),
                    Saturation.psiaFromPsig(exhaustPressurePsig),
                    design.turbineEfficiency());
            steamDemandKgPerS = Double.isFinite(demand)
                    ? Math.min(demand, Math.min(design.maximumSteamKgPerS(), steamSupplyLimitKgPerS)) : 0.0;
            electricalDemandWatts = 0.0;
        } else {
            steamDemandKgPerS = 0.0;
            electricalDemandWatts = shaftPowerWatts / MOTOR_EFFICIENCY;
        }
    }

    /**
     * What actually comes out of the discharge: the smallest of what the
     * impeller can produce, what the flow control valve has been told to pass,
     * and what the suction source can supply.
     */
    private double deliveredFlowKgPerS(double dp, double speed) {
        return Math.min(Math.min(curveFlowKgPerS(dp, speed),
                        flowDemandFraction * design.ratedFlowKgPerS()),
                suctionFlowLimitKgPerS);
    }

    /**
     * The flow the pump curve alone would give at this speed, before the
     * throttle or the suction source has its say.
     */
    private double curveFlowKgPerS(double dp, double speed) {
        if (speed <= 0.0) {
            return 0.0;
        }
        if (design.isConstantDisplacement()) {
            // A positive displacement pump moves the same volume per revolution
            // whatever the discharge pressure -- right up to the point where its
            // relief valve lifts and the whole delivery spills back to suction.
            return dp >= design.maximumDischargePsi() ? 0.0 : design.ratedFlowKgPerS() * speed;
        }
        return design.curve().flowKgPerS(dp, speed);
    }

    /** Shaft power lost to windage and friction at a given speed, watts. */
    private double windagePowerWatts(double speed) {
        double s = Math.min(1.0, Math.max(0.0, speed));
        return design.windageFractionOfRatedShaft() * design.ratedShaftPowerWatts() * s * s * s;
    }

    /**
     * Gross shaft power the drive can produce right now, watts. Windage is a load
     * on this, charged inside {@link #shaftPowerAtSpeed} at whatever speed is
     * being tested, and is deliberately not deducted here.
     */
    private double availableShaftPowerWatts() {
        if (design.drive() == PumpDesign.Drive.STEAM_TURBINE) {
            return SteamTurbineDrive.shaftPowerWatts(Math.min(design.maximumSteamKgPerS(), steamSupplyLimitKgPerS),
                    Saturation.psiaFromPsig(driveInletPressurePsig()),
                    Saturation.psiaFromPsig(exhaustPressurePsig),
                    design.turbineEfficiency());
        }
        return electricalPowerAvailableWatts * MOTOR_EFFICIENCY;
    }

    /**
     * Fastest the drive can turn this pump against the present discharge
     * pressure: the largest speed whose total shaft demand the drive can still
     * meet. {@link #shaftPowerAtSpeed} rises monotonically with speed — the
     * hydraulic term because both flow and developed head do, the windage term
     * because it goes as {@code s^3} — so a bisection settles it in a fixed and
     * very small number of steps.
     *
     * @param availableWatts everything the drive can produce, watts. Windage is
     *                       charged inside the balance at the speed being tested,
     *                       not subtracted from the budget beforehand.
     */
    private double solveAchievableSpeed(double dp, double availableWatts) {
        if (availableWatts <= 0.0) {
            return 0.0;
        }
        if (shaftPowerAtSpeed(dp, 1.0) <= availableWatts) {
            return 1.0;
        }
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 24; i++) {
            double mid = 0.5 * (lo + hi);
            if (shaftPowerAtSpeed(dp, mid) <= availableWatts) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * The complete shaft power balance at a given speed, watts:
     * {@code hydraulic(Q, H)/eta_pump + windage(s)}.
     *
     * <p>Both terms matter and both were wrong before. The hydraulic term is
     * charged at the head the impeller <i>develops</i> at the flow it is passing
     * ({@link #workingDifferentialPsi}), not at the system differential; and
     * windage is charged at the speed under test rather than at full speed. What
     * comes out is strictly increasing in speed with no flat region, which is what
     * makes the bisection above well-posed and the answer continuous in vessel
     * pressure.
     */
    private double shaftPowerAtSpeed(double dp, double speed) {
        double q = deliveredFlowKgPerS(dp, speed);
        return PumpDesign.hydraulicPowerWatts(q, workingDifferentialPsi(dp, q, speed))
                / design.pumpEfficiency()
                + windagePowerWatts(speed);
    }

    /**
     * The pressure rise the machine is actually working against, psi.
     *
     * <p>For a centrifugal pump this is the head the impeller develops at the
     * flow being passed, which is the system differential only when the discharge
     * valve is wide open and the curve itself is setting the flow. As soon as the
     * flow control valve throttles — every pump below its rated duty point, i.e.
     * throughout the depressurised regime the low pressure systems exist for — the
     * machine climbs its own curve and does strictly more work per kilogram than
     * the vessel differential accounts for. The excess is destroyed across the
     * valve; the drive pays for it either way. The {@code Math.max} is numerical
     * belt and braces: by construction {@code H(Q) >= dp} for any {@code Q} at or
     * below the curve's own flow at this speed.
     *
     * <p>A positive displacement pump has no curve to climb. It moves its
     * displacement whatever the discharge pressure and its relief valve caps how
     * hard it can push, so the differential it is charged for is capped there.
     */
    private double workingDifferentialPsi(double dp, double flowKgPerS, double speed) {
        if (design.isConstantDisplacement()) {
            return Math.min(dp, design.maximumDischargePsi());
        }
        return Math.max(dp, design.curve().headAtFlowPsi(flowKgPerS, speed));
    }

    // -----------------------------------------------------------------
    // Measurements
    // -----------------------------------------------------------------

    /** Water actually delivered, kg/s. */
    public double getFlowKgPerS() {
        return flowKgPerS;
    }

    /** Shaft speed, 0 to 1. Lags the command; a stopped pump coasts down. */
    public double getSpeedFraction() {
        return speedFraction;
    }

    /** Pressure the pump has to push against, psi. */
    public double differentialPressurePsi() {
        return Math.max(0.0, vesselPressurePsig - suctionPressurePsig);
    }

    /**
     * Flow this pump could deliver against the present vessel pressure if it
     * were running at full speed with an unlimited suction. Zero means the
     * vessel is harder than the pump, not that the pump is broken — compare it
     * with {@link #getFlowKgPerS()} to tell those apart.
     */
    public double getAvailableFlowKgPerS() {
        return curveFlowKgPerS(differentialPressurePsi(), 1.0);
    }

    /**
     * Whether the vessel is presently at a higher pressure than this pump can
     * develop <b>at full speed</b> — "is the vessel harder than this machine".
     *
     * <p>A measurement of the pump curve against a pressure gauge, published so
     * a control program can distinguish "no injection because the pump is above
     * its shutoff head" from "no injection because the pump is not running".
     * It is <b>not</b> a permissive: nothing consults it, nothing is prevented
     * by it, and a pump in this condition runs perfectly happily and delivers
     * nothing at all. If the player wants the real plant's 500 psig injection
     * inhibit, they write it themselves against this reading.
     *
     * <p><b>Evaluated at the nameplate, not at the current speed, and that is the
     * whole point.</b> Evaluating it at the current speed made it unconditionally
     * true for every stopped pump, because the affinity law puts a stopped
     * machine's shutoff head at zero and any positive vessel pressure clears it.
     * That defeats the reading's stated purpose exactly: the documented control
     * program {@code if not rhr.isAboveShutoffHead() then rhr.start() end} would
     * never start RHR at any vessel pressure at all, since the answer only goes
     * false once the pump is already turning. It also contradicted
     * {@link #getAvailableFlowKgPerS()} on the same object in the same tick,
     * which deliberately asks the same question at full speed. The two now agree
     * by construction: this is false exactly when that is non-zero.
     *
     * <p>The at-current-speed variant is a real and different measurement — it is
     * what tells you a brownout has taken a running pump below its head — and it
     * is published separately as {@link #isAboveDevelopedHead()}.
     */
    public boolean isAboveShutoffHead() {
        double dp = differentialPressurePsi();
        if (design.isConstantDisplacement()) {
            return dp >= design.maximumDischargePsi();
        }
        return design.curve().isAboveShutoffHead(dp, 1.0);
    }

    /**
     * Whether the vessel is presently at a higher pressure than this pump can
     * develop <b>at the speed it is actually turning</b>.
     *
     * <p>The companion to {@link #isAboveShutoffHead()}. Head goes as the square
     * of speed, so a motor on a sagging bus or a turbine starved by a
     * depressurising vessel can be above its developed head while sitting
     * comfortably below its nameplate shutoff head — the machine is capable and
     * the drive is not. A stopped pump is trivially above its developed head,
     * which is why this is the wrong reading to gate a start on and the right one
     * to diagnose a running machine that has stopped delivering.
     */
    public boolean isAboveDevelopedHead() {
        double dp = differentialPressurePsi();
        if (design.isConstantDisplacement()) {
            return speedFraction <= 0.0 || dp >= design.maximumDischargePsi();
        }
        return design.curve().isAboveShutoffHead(dp, Math.max(speedFraction, SPEED_DEADBAND));
    }

    /** Head the pump can develop at its current speed, psi. */
    public double getDevelopedHeadPsi() {
        if (design.isConstantDisplacement()) {
            return speedFraction > 0.0 ? design.maximumDischargePsi() : 0.0;
        }
        return design.curve().shutoffHeadPsi(speedFraction);
    }

    /** Shaft power the pump is absorbing, watts. */
    public double getShaftPowerWatts() {
        return shaftPowerWatts;
    }

    /** Steam the turbine drive is taking out of the vessel, kg/s. Zero on a motor. */
    public double getSteamDemandKgPerS() {
        return steamDemandKgPerS;
    }

    /** Electrical power the motor drive wants, watts. Zero on a turbine. */
    public double getElectricalDemandWatts() {
        return electricalDemandWatts;
    }

    /** Fastest the drive could turn the pump right now, 0 to 1. */
    public double getAchievableSpeedFraction() {
        return achievableSpeedFraction;
    }

    /**
     * True when the drive cannot turn the pump as fast as it wants to go — a
     * starved turbine on a depressurising vessel, or a motor on a sagging bus.
     * A comparison of two measurements, published because a player who cannot
     * see it has no way to tell an underpowered pump from a broken one, or from
     * one that is simply above its shutoff head.
     */
    public boolean isDriveLimited() {
        return running && achievableSpeedFraction < 1.0 - 1.0e-6;
    }

    /**
     * Flow that was commanded, kg/s — the throttle setting times rated flow.
     * Compare with {@link #getFlowKgPerS()} to see whether the machine is
     * managing it.
     */
    public double getDemandedFlowKgPerS() {
        return flowDemandFraction * design.ratedFlowKgPerS();
    }

    /** Boron this pump is adding at its present flow, ppm per minute. */
    public double getBoronPpmPerMinute() {
        double rated = design.ratedFlowKgPerS();
        if (design.boronPpmPerMinuteAtRatedFlow() <= 0.0 || rated <= 0.0) {
            return 0.0;
        }
        return design.boronPpmPerMinuteAtRatedFlow() * (flowKgPerS / rated);
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    /**
     * Snapshot for NBT. A pump unloaded mid-coastdown resumes mid-coastdown,
     * per {@code SPEC.md} section 11.
     */
    public double[] toArray() {
        return new double[]{running ? 1.0 : 0.0, flowDemandFraction, speedFraction, speedDemandFraction};
    }

    /**
     * Restore from {@link #toArray()}.
     *
     * <p><b>Sanitised on the way in, not merely on the way out.</b>
     * {@link #setFlowDemandFraction} refuses a non-finite demand and clamps the
     * rest, but that is no help at all to a value that is already on disk: this
     * method is the other door into the same two fields, and it used to assign
     * both of them raw. A NaN written by an older build — or a fraction outside
     * 0..1 from a hand-edited save — came back as itself, and from there it is
     * one step into the plant. {@code step} takes
     * {@code min(flowDemandFraction, ...)}, which is NaN, drives
     * {@code speedFraction} to NaN, and the flow that reaches the vessel's mass
     * balance is NaN with it: level, pressure and every reading downstream of
     * them become dashes with nothing to say where it started.
     *
     * <p>Both fields are fractions, so both clamp. A non-finite value is taken
     * as zero rather than propagated — a pump that reloads stopped is a visible,
     * recoverable problem, and a pump that reloads poisoned is neither.
     */
    public void fromArray(double[] a) {
        if (a == null || a.length < 3) {
            return;
        }
        running = a[0] != 0.0;
        flowDemandFraction = clampFraction(a[1]);
        speedFraction = clampFraction(a[2]);
        speedDemandFraction = a.length > 3 ? clampFraction(a[3]) : 1.0;
    }

    /** A saved fraction as a usable 0..1, with a non-finite value taken as zero. */
    private static double clampFraction(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }
}
