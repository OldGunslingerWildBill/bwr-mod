package dev.bwr.core.feedwater;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.eccs.PumpCurve;
import dev.bwr.core.eccs.PumpDesign;
import dev.bwr.core.thermal.VoidModel;

/**
 * The nameplate data of one reactor feed pump — {@code SPEC.md} section 15.
 *
 * <h2>Feedwater is the normal path, and it is the one that fails first</h2>
 * Emergency core cooling is for after something has gone wrong. Feedwater is
 * what holds vessel inventory every second of normal operation, and losing it
 * is how most level transients start. The two are deliberately separate systems
 * in this model and in a real plant.
 *
 * <h2>The two machines, and why the choice is not cosmetic</h2>
 * Both develop the same head and move the same water. Everything that separates
 * them falls out of {@link PumpDesign.Drive}:
 *
 * <ul>
 *   <li><b>The motor-driven pump</b> is simple, controllable and completely
 *       indifferent to what the reactor is doing — and it wants 13 MW of shaft
 *       power to do it, which at this mod's exchange rate is about twice a
 *       recirculation pump each. Feed pumping is the largest parasitic
 *       electrical load in a real plant, and it is here too. In a station
 *       blackout it is scrap metal.</li>
 *   <li><b>The turbine-driven pump</b> costs no bus load at all, because its
 *       motive force is the reactor's own steam. In exchange it is hostage to
 *       the reactor: it cannot start on a cold plant, and it fades as the
 *       vessel depressurises until it cannot overcome its own windage. That
 *       coupling — level control tied to steam production — is the one
 *       {@code SPEC.md} section 15 calls the point of the whole system.</li>
 * </ul>
 *
 * <p>Nothing in this file decides anything. There is no level control, no
 * three-element controller, no feed pump trip and no runback. A feed pump takes
 * a commanded flow demand from the player and delivers what the physics allows.
 *
 * <h2>Where the drive steam goes</h2>
 * A real reactor feed pump turbine exhausts to the main condenser. This mod has
 * no condenser: the player's Mekanism turbine is the condenser, and it is where
 * the main steam already goes. So the drive steam is treated exactly like main
 * steam — it leaves the vessel and does not come back, and the player returns it
 * as water through these same pumps. It is deliberately <b>not</b> dumped into
 * the suppression pool the way the RCIC and HPCI exhausts are: those are
 * emergency machines running for minutes into a heat sink sized for it, and a
 * feed pump turbine runs continuously at power. Cooking the plant's own heat
 * sink as the price of normal operation would be wrong physics, not a hard
 * mechanic.
 *
 * <p>Exhaust back pressure is taken as atmospheric rather than a condenser
 * vacuum, because this model has no condenser to draw a vacuum. That
 * understates the work the turbine gets from each kilogram, which is the
 * conservative direction to be wrong in.
 *
 * <p>Pure data and pure Java. Immutable, so the catalogue constants can be
 * shared freely.
 */
public final class FeedwaterDesign implements PumpDesign {

    /**
     * Feedwater flow the plant needs at rated power, kg/s.
     *
     * <p>Not a chosen number: at steady state the vessel boils exactly what
     * feedwater puts in, so rated feedwater flow <i>is</i> rated steam flow —
     * 15.4 million lb/hr, from {@code REFERENCE-DATA.md} section 8.
     */
    public static final double RATED_FEEDWATER_FLOW_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * VoidModel.KG_PER_LB / 3600.0;

    /**
     * Rated capacity of one feed pump, US gpm.
     *
     * <p>Two of these make a plant. Half-capacity pumps are what a real BWR/6
     * carries, and the reason is worth keeping: losing one does not lose
     * feedwater, it halves it, so the plant has a degraded state to be operated
     * in rather than only a working one and a broken one.
     */
    public static final double FEED_PUMP_RATED_GPM = 15_400.0;

    /**
     * Differential head at the rated duty point, psi.
     *
     * <p>It has to exceed the rated dome pressure of 1025 psig or no water
     * enters the vessel at power at all. A real plant reaches this figure with a
     * condensate pump and a booster pump in series ahead of the feed pump; there
     * is no booster train in this model, so one pump does the whole lift from an
     * effectively atmospheric suction. That is why the shaft power below lands
     * nearer the real feed pump <i>turbine</i> rating than the real feed pump
     * motor rating.
     */
    public static final double FEED_PUMP_RATED_HEAD_PSI = 1100.0;

    /** Differential head at zero flow, psi. */
    public static final double FEED_PUMP_SHUTOFF_HEAD_PSI = 1400.0;

    /**
     * Turbine exhaust back pressure, psia. Atmospheric — see the class comment
     * on why this model does not claim a condenser vacuum.
     */
    public static final double DRIVE_EXHAUST_PSIA = PhysicalConstants.ATMOSPHERIC_PSI;

    private final String id;
    private final String displayName;
    private final Drive drive;
    private final PumpCurve curve;
    private final double motorRatingWatts;
    private final double maximumSteamKgPerS;
    private final double turbineEfficiency;

    private FeedwaterDesign(String id, String displayName, Drive drive,
                            double motorRatingWatts, double maximumSteamKgPerS,
                            double turbineEfficiency) {
        this.id = id;
        this.displayName = displayName;
        this.drive = drive;
        this.curve = new PumpCurve(FEED_PUMP_SHUTOFF_HEAD_PSI,
                PumpDesign.kgPerSecondFromGpm(FEED_PUMP_RATED_GPM), FEED_PUMP_RATED_HEAD_PSI);
        this.motorRatingWatts = motorRatingWatts;
        this.maximumSteamKgPerS = maximumSteamKgPerS;
        this.turbineEfficiency = turbineEfficiency;
    }

    // -----------------------------------------------------------------
    // The catalogue
    // -----------------------------------------------------------------

    /**
     * Motor-driven reactor feed pump.
     *
     * <p>13 MW, which is the shaft power the duty point demands plus its own
     * windage plus motor losses, not a balance figure someone liked the look of.
     * At {@code EccsPower}'s exchange rate that is a little under a megaFE per
     * tick, per pump, and a plant wants two — so feedwater is the largest
     * electrical load in the mod by a wide margin. That is the correct answer:
     * it is the largest load in a real plant too, and it is exactly why the
     * turbine-driven alternative below exists at all.
     */
    public static final FeedwaterDesign MOTOR_FEED_PUMP =
            new FeedwaterDesign("motor_feed_pump", "Motor-Driven Reactor Feed Pump",
                    Drive.ELECTRIC_MOTOR, 13.0e6, 0.0, 0.0);

    /**
     * Turbine-driven reactor feed pump — the RFPT.
     *
     * <p>A multistage machine at 70% isentropic efficiency, which is what
     * separates it from the RCIC Terry wheel at 15%: it is chosen to be good,
     * not merely to start without electricity. Twenty-four kg/s of admission
     * steam is about 1.2% of rated steam flow, so at power it is cheap. On a
     * depressurising vessel it is not cheap at all, and eventually it is not
     * possible — no cutoff pressure is written anywhere; the wheel simply stops
     * making enough work to turn itself.
     */
    public static final FeedwaterDesign TURBINE_FEED_PUMP =
            new FeedwaterDesign("turbine_feed_pump", "Turbine-Driven Reactor Feed Pump",
                    Drive.STEAM_TURBINE, 0.0, 24.0, 0.70);

    /** Every feedwater design, in build order. */
    public static FeedwaterDesign[] all() {
        return new FeedwaterDesign[]{MOTOR_FEED_PUMP, TURBINE_FEED_PUMP};
    }

    /** Look a design up by its lowercase id, or null. */
    public static FeedwaterDesign byId(String id) {
        for (FeedwaterDesign d : all()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    // PumpDesign
    // -----------------------------------------------------------------

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public Drive drive() {
        return drive;
    }

    @Override
    public PumpCurve curve() {
        return curve;
    }

    @Override
    public boolean isConstantDisplacement() {
        return false;
    }

    @Override
    public double ratedFlowKgPerS() {
        return curve.ratedFlowKgPerS();
    }

    @Override
    public double maximumDischargePsi() {
        return curve.shutoffHeadPsi();
    }

    @Override
    public double pumpEfficiency() {
        return 0.70;
    }

    @Override
    public double motorRatingWatts() {
        return motorRatingWatts;
    }

    @Override
    public double maximumSteamKgPerS() {
        return maximumSteamKgPerS;
    }

    @Override
    public double turbineEfficiency() {
        return turbineEfficiency;
    }

    @Override
    public double windageFractionOfRatedShaft() {
        return 0.15;
    }

    /**
     * Twenty seconds to rated speed. A feed pump is started against a closed
     * discharge and brought up, and it is a large machine.
     */
    @Override
    public double startupSeconds() {
        return 20.0;
    }

    /**
     * Six seconds to a stop. Deliberately short, and deliberately much shorter
     * than a recirculation pump's eleven: a feed pump carries no flywheel,
     * because nobody wants inventory arriving in a vessel after the pump has
     * been stopped. Losing feedwater really is close to instantaneous, and that
     * is what makes it the transient it is.
     */
    @Override
    public double coastdownSeconds() {
        return 6.0;
    }

    @Override
    public double boronPpmPerMinuteAtRatedFlow() {
        return 0.0;
    }

    @Override
    public double ratedShaftPowerWatts() {
        return PumpDesign.hydraulicPowerWatts(ratedFlowKgPerS(), curve.ratedHeadPsi())
                / pumpEfficiency();
    }

    @Override
    public String toString() {
        return displayName + " (" + drive + ", "
                + String.format("%.0f", ratedFlowKgPerS()) + " kg/s)";
    }
}
