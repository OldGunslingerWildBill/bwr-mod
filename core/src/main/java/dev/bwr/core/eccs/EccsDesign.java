package dev.bwr.core.eccs;

/**
 * The nameplate data of one emergency core cooling system — {@code SPEC.md}
 * section 9.1, with the capacities from {@code REFERENCE-DATA.md} section 9.
 *
 * <h2>The differences between these six machines are the whole design space</h2>
 * Nothing about the tradeoffs below is enforced by a rule anywhere. They are all
 * consequences of the numbers in this file being fed through {@link PumpCurve}
 * and {@link SteamTurbineDrive}:
 *
 * <ul>
 *   <li><b>RCIC</b> needs no AC and delivers 700 gpm. That is enough to hold
 *       level against decay heat boiloff and nothing like enough to keep up
 *       with a large break. A player who builds only RCIC survives a blackout
 *       and loses to a pipe.</li>
 *   <li><b>HPCI</b> also needs no AC and delivers seven times as much — but it
 *       swallows an order of magnitude more steam doing it, so it drags the
 *       vessel pressure down while it runs. Using it is not free.</li>
 *   <li><b>HPCS</b> is bigger and simpler and needs a 2.2 MW motor. In a
 *       station blackout it is scrap metal unless emergency power was built and
 *       actually starts.</li>
 *   <li><b>LPCS and RHR/LPCI</b> move enormous volumes and develop only a few
 *       hundred psi of head. Above that head they deliver literally nothing,
 *       which is what makes the ADS blowdown a decision with a cost rather than
 *       a free button.</li>
 *   <li><b>SLC</b> is a positive displacement pump moving 43 gpm of sodium
 *       pentaborate. It is the answer to an ATWS and it takes the better part
 *       of an hour to work.</li>
 * </ul>
 *
 * <p>Pure data and pure Java. Immutable, so the catalogue constants can be
 * shared freely.
 */
public final class EccsDesign {

    /** Litres, and therefore kilograms of cold water, per US gallon. */
    public static final double KG_PER_GALLON = 3.785411784;

    /** Convert a pump rating in US gallons per minute to kg/s of cold water. */
    public static double kgPerSecondFromGpm(double gpm) {
        return gpm * KG_PER_GALLON / 60.0;
    }

    /** What turns the shaft. This is the single most consequential property here. */
    public enum Drive {
        /**
         * A steam turbine fed from the reactor itself. Needs no electrical
         * supply of any kind, consumes steam, and loses power as the vessel
         * depressurises.
         */
        STEAM_TURBINE,
        /**
         * An electric motor. Simple, controllable, indifferent to reactor
         * pressure — and completely dead without a bus behind it.
         */
        ELECTRIC_MOTOR
    }

    /** Where the water goes, which decides what it does for an uncovered core. */
    public enum Delivery {
        /**
         * Into the vessel. Has to refill the downcomer and recover level before
         * the fuel sees anything.
         */
        VESSEL_INJECTION,
        /**
         * Onto the fuel, through a sparger ring. Cools an uncovered core
         * directly without waiting for the vessel to refill, and its capacity
         * scales with how complete the ring is ({@code SPEC.md} section 9.4).
         */
        CORE_SPRAY
    }

    private final String id;
    private final String displayName;
    private final Drive drive;
    private final Delivery delivery;
    private final PumpCurve curve;
    private final double constantDisplacementFlowKgPerS;
    private final double maximumDischargePsi;
    private final double pumpEfficiency;
    private final double motorRatingWatts;
    private final double maximumSteamKgPerS;
    private final double turbineEfficiency;
    private final double windageFractionOfRatedShaft;
    private final double startupSeconds;
    private final double coastdownSeconds;
    private final double boronPpmPerMinuteAtRatedFlow;
    private final boolean poolCoolingCapable;

    private EccsDesign(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.drive = b.drive;
        this.delivery = b.delivery;
        this.curve = b.curve;
        this.constantDisplacementFlowKgPerS = b.constantDisplacementFlowKgPerS;
        this.maximumDischargePsi = b.maximumDischargePsi;
        this.pumpEfficiency = b.pumpEfficiency;
        this.motorRatingWatts = b.motorRatingWatts;
        this.maximumSteamKgPerS = b.maximumSteamKgPerS;
        this.turbineEfficiency = b.turbineEfficiency;
        this.windageFractionOfRatedShaft = b.windageFractionOfRatedShaft;
        this.startupSeconds = b.startupSeconds;
        this.coastdownSeconds = b.coastdownSeconds;
        this.boronPpmPerMinuteAtRatedFlow = b.boronPpmPerMinuteAtRatedFlow;
        this.poolCoolingCapable = b.poolCoolingCapable;
    }

    // -----------------------------------------------------------------
    // The catalogue
    // -----------------------------------------------------------------

    /**
     * Reactor Core Isolation Cooling. The station blackout workhorse.
     *
     * <p>700 gpm [TTC 2.7] driven by a Terry turbine, whose isentropic
     * efficiency really is about 15% — it is a single stage impulse wheel
     * chosen because it will run on wet steam and start without electricity,
     * not because it is any good at extracting work. That awfulness is why
     * RCIC's steam consumption is measured in kilograms per second at all: a
     * decent turbine would use a quarter as much.
     */
    public static final EccsDesign RCIC = new Builder("rcic", "RCIC")
            .drive(Drive.STEAM_TURBINE)
            .delivery(Delivery.VESSEL_INJECTION)
            .curve(new PumpCurve(1500.0, kgPerSecondFromGpm(700.0), 1025.0))
            .turbine(6.0, 0.15)
            .startup(30.0)
            .build();

    /**
     * High Pressure Coolant Injection. Seven times RCIC's flow from the same
     * kind of drive, and it takes seven times the steam to do it. Injects into
     * the core shroud.
     */
    public static final EccsDesign HPCI = new Builder("hpci", "HPCI")
            .drive(Drive.STEAM_TURBINE)
            .delivery(Delivery.VESSEL_INJECTION)
            .curve(new PumpCurve(1500.0, kgPerSecondFromGpm(5000.0), 1025.0))
            .turbine(25.0, 0.25)
            .startup(25.0)
            .build();

    /**
     * High Pressure Core Spray. Motor driven, so it needs a large bus behind
     * it, and it sprays rather than injects.
     *
     * <p>The curve is the real BWR/6 machine: 6350 gpm at 200 psid and
     * 1550 gpm at 1147 psid are two published duty points, and a single
     * quadratic through both gives a shutoff head of 1207 psi. Nothing was
     * chosen here except which two numbers to fit.
     */
    public static final EccsDesign HPCS = new Builder("hpcs", "HPCS")
            .drive(Drive.ELECTRIC_MOTOR)
            .delivery(Delivery.CORE_SPRAY)
            .curve(new PumpCurve(1207.0, kgPerSecondFromGpm(6350.0), 200.0))
            .motorWatts(2.6e6)
            .startup(27.0)
            .build();

    /**
     * Low Pressure Core Spray. 6350 gpm at 122 psid and a shutoff head of
     * 300 psi — so above about 300 psig in the vessel it delivers nothing at
     * all, however hard the motor turns.
     */
    public static final EccsDesign LPCS = new Builder("lpcs", "LPCS")
            .drive(Drive.ELECTRIC_MOTOR)
            .delivery(Delivery.CORE_SPRAY)
            .curve(new PumpCurve(300.0, kgPerSecondFromGpm(6350.0), 122.0))
            .motorWatts(0.7e6)
            .startup(40.0)
            .build();

    /**
     * Residual Heat Removal, in its low pressure coolant injection mode. The
     * biggest volume in the plant and the softest pump in it: 7100 gpm at
     * 20 psid, shutoff at 230 psi.
     *
     * <p>The same loop also does suppression pool cooling, and it cannot do
     * both at once — which is the choice between saving the core now and
     * keeping the heat sink you will need in an hour.
     */
    public static final EccsDesign RHR = new Builder("rhr", "RHR / LPCI")
            .drive(Drive.ELECTRIC_MOTOR)
            .delivery(Delivery.VESSEL_INJECTION)
            .curve(new PumpCurve(230.0, kgPerSecondFromGpm(7100.0), 20.0))
            .motorWatts(0.6e6)
            .startup(40.0)
            .poolCooling(true)
            .build();

    /**
     * Standby Liquid Control — {@code SPEC.md} section 9.3,
     * {@code REFERENCE-DATA.md} section 7.
     *
     * <p>A positive displacement pump, so its 43 gpm is indifferent to vessel
     * pressure right up to the point where its relief valve lifts at 1400 psi.
     * At full rate it adds 14 ppm of boron a minute, which is the middle of the
     * real 8 to 20 ppm/min band, and boron is worth 8.3e-5 dk/k per ppm. Reaching
     * the 600 ppm shutdown concentration therefore takes about 43 minutes and
     * the full 750 ppm design concentration about 54.
     *
     * <p>That slowness is the mechanic, not an inconvenience: an ATWS with
     * stuck rods commits the player to the better part of an hour of degraded
     * operation while the boron builds.
     */
    public static final EccsDesign SLC = new Builder("slc", "SLC")
            .drive(Drive.ELECTRIC_MOTOR)
            .delivery(Delivery.VESSEL_INJECTION)
            .constantDisplacement(kgPerSecondFromGpm(43.0), 1400.0)
            .motorWatts(40.0e3)
            .startup(5.0)
            .boronPpmPerMinute(14.0)
            .build();

    /** Every design, in build order. */
    public static EccsDesign[] all() {
        return new EccsDesign[]{RCIC, HPCI, HPCS, LPCS, RHR, SLC};
    }

    /** Look a design up by its lowercase id, or null. */
    public static EccsDesign byId(String id) {
        for (EccsDesign d : all()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Drive drive() {
        return drive;
    }

    public Delivery delivery() {
        return delivery;
    }

    /** The head-flow characteristic, or null for a positive displacement pump. */
    public PumpCurve curve() {
        return curve;
    }

    /** True for a positive displacement pump, whose flow does not care about head. */
    public boolean isConstantDisplacement() {
        return curve == null;
    }

    /** Rated flow, kg/s — the curve's duty point, or the displacement rate. */
    public double ratedFlowKgPerS() {
        return curve != null ? curve.ratedFlowKgPerS() : constantDisplacementFlowKgPerS;
    }

    /**
     * The differential pressure beyond which no water is delivered, psi. For a
     * centrifugal pump this is the shutoff head; for a positive displacement
     * pump it is the discharge relief setting, above which the pump spills back
     * to its own suction and puts nothing in the vessel.
     */
    public double maximumDischargePsi() {
        return curve != null ? curve.shutoffHeadPsi() : maximumDischargePsi;
    }

    public double pumpEfficiency() {
        return pumpEfficiency;
    }

    /** Shaft rating of the motor, watts. Zero on a turbine-driven machine. */
    public double motorRatingWatts() {
        return motorRatingWatts;
    }

    /** Largest steam flow the turbine's admission valve can pass, kg/s. */
    public double maximumSteamKgPerS() {
        return maximumSteamKgPerS;
    }

    public double turbineEfficiency() {
        return turbineEfficiency;
    }

    /**
     * Power the turbine burns spinning itself, as a fraction of the shaft power
     * the pump needs at its rated duty point. This is what eventually stops a
     * turbine-driven system on a depressurising vessel: the wheel cannot make
     * enough work to overcome its own windage, and no cutoff pressure had to be
     * written down anywhere for that to happen.
     */
    public double windageFractionOfRatedShaft() {
        return windageFractionOfRatedShaft;
    }

    /** Time from a start command to essentially rated speed, seconds. */
    public double startupSeconds() {
        return startupSeconds;
    }

    /** Time to coast to a stop after a stop command, seconds. */
    public double coastdownSeconds() {
        return coastdownSeconds;
    }

    /** Boron added at full flow, ppm per minute. Zero except on SLC. */
    public double boronPpmPerMinuteAtRatedFlow() {
        return boronPpmPerMinuteAtRatedFlow;
    }

    /** Whether this loop can be lined up to cool the suppression pool instead. */
    public boolean isPoolCoolingCapable() {
        return poolCoolingCapable;
    }

    /**
     * Shaft power the pump needs at its rated duty point, watts. Used as the
     * reference for windage and as the sizing basis for the motor.
     */
    public double ratedShaftPowerWatts() {
        double dpPsi = curve != null ? curve.ratedHeadPsi() : maximumDischargePsi;
        return hydraulicPowerWatts(ratedFlowKgPerS(), dpPsi) / pumpEfficiency;
    }

    /**
     * Hydraulic power delivered by moving a mass flow of cold water across a
     * pressure difference, watts. {@code P = Q_volumetric * dp}.
     */
    public static double hydraulicPowerWatts(double flowKgPerS, double differentialPsi) {
        if (!(flowKgPerS > 0.0) || !(differentialPsi > 0.0)) {
            return 0.0;
        }
        final double coldWaterDensityKgPerM3 = 1000.0;
        return (flowKgPerS / coldWaterDensityKgPerM3) * differentialPsi * SteamTurbineDrive.PA_PER_PSI;
    }

    @Override
    public String toString() {
        return displayName + " (" + drive + ", " + String.format("%.0f", ratedFlowKgPerS()) + " kg/s)";
    }

    // -----------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------

    private static final class Builder {
        private final String id;
        private final String displayName;
        private Drive drive = Drive.ELECTRIC_MOTOR;
        private Delivery delivery = Delivery.VESSEL_INJECTION;
        private PumpCurve curve;
        private double constantDisplacementFlowKgPerS;
        private double maximumDischargePsi;
        private double pumpEfficiency = 0.70;
        private double motorRatingWatts;
        private double maximumSteamKgPerS;
        private double turbineEfficiency;
        private double windageFractionOfRatedShaft = 0.15;
        private double startupSeconds = 30.0;
        private double coastdownSeconds = 6.0;
        private double boronPpmPerMinuteAtRatedFlow;
        private boolean poolCoolingCapable;

        Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        Builder drive(Drive d) {
            this.drive = d;
            return this;
        }

        Builder delivery(Delivery d) {
            this.delivery = d;
            return this;
        }

        Builder curve(PumpCurve c) {
            this.curve = c;
            return this;
        }

        Builder constantDisplacement(double flowKgPerS, double reliefPsi) {
            this.constantDisplacementFlowKgPerS = flowKgPerS;
            this.maximumDischargePsi = reliefPsi;
            return this;
        }

        Builder motorWatts(double watts) {
            this.motorRatingWatts = watts;
            return this;
        }

        Builder turbine(double maxSteamKgPerS, double efficiency) {
            this.maximumSteamKgPerS = maxSteamKgPerS;
            this.turbineEfficiency = efficiency;
            return this;
        }

        Builder startup(double seconds) {
            this.startupSeconds = seconds;
            return this;
        }

        Builder boronPpmPerMinute(double ppm) {
            this.boronPpmPerMinuteAtRatedFlow = ppm;
            return this;
        }

        Builder poolCooling(boolean b) {
            this.poolCoolingCapable = b;
            return this;
        }

        EccsDesign build() {
            return new EccsDesign(this);
        }
    }
}
