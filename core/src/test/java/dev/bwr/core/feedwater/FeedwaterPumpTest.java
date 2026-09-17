package dev.bwr.core.feedwater;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.eccs.EccsPump;
import dev.bwr.core.eccs.PumpDesign;

/**
 * The reactor feed pumps — {@code SPEC.md} section 15.
 *
 * <h2>What these are actually holding down</h2>
 * The feedwater physics has been in {@code ReactorCore} since it was written and
 * nothing in the world drove it: a Lua program set a kg/s number and the vessel
 * believed it. These tests pin the hardware that now stands between the two, and
 * they are deliberately concentrated on the properties that make the two pumps
 * different from each other, because a nameplate that produces two machines
 * which behave identically is the failure worth catching. Everything below falls
 * out of {@link EccsPump} and the numbers in {@link FeedwaterDesign}; none of it
 * is special-cased anywhere.
 *
 * <p>There is nothing here about vessel level, and there is not meant to be.
 * Feedwater does not chase level in this mod — the pumps take a demand and
 * deliver against a pressure, and closing that loop is the player's program.
 */
public final class FeedwaterPumpTest {

    private FeedwaterPumpTest() {
    }

    /** Rated dome pressure, the differential a feed pump normally works against. */
    private static final double RATED_DOME_PSIG = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

    /** A pump run to steady state at a given vessel pressure and power supply. */
    private static EccsPump settled(FeedwaterDesign design, double vesselPsig,
                                    double electricalWatts) {
        EccsPump pump = new EccsPump(design);
        pump.setRunning(true);
        pump.setFlowDemandFraction(1.0);
        pump.setVesselPressurePsig(vesselPsig);
        pump.setSuctionPressurePsig(0.0);
        pump.setExhaustPressurePsig(FeedwaterDesign.DRIVE_EXHAUST_PSIA
                - PhysicalConstants.ATMOSPHERIC_PSI);
        pump.setSuctionTemperatureC(32.0);
        pump.setElectricalPowerAvailableWatts(electricalWatts);
        // Sixty seconds at a twentieth of a second, comfortably past the
        // twenty-second startup ramp.
        for (int i = 0; i < 1200; i++) {
            pump.step(0.05);
        }
        return pump;
    }

    /** Enough electricity that a motor drive is never the limit. */
    private static double amplePower(FeedwaterDesign design) {
        return design.motorRatingWatts();
    }

    /**
     * Two pumps make a plant, and the arithmetic that says so is not a coincidence
     * anyone can quietly break.
     *
     * <p>Rated feedwater flow is rated <i>steam</i> flow, because at steady state
     * the vessel boils exactly what feedwater puts in. If someone changes
     * {@link FeedwaterDesign#FEED_PUMP_RATED_GPM} without meaning to, a plant
     * either cannot reach rated power or reaches it on one pump, and both of
     * those quietly destroy the half-capacity mechanic.
     */
    public static void test01_twoPumpsCoverRatedFeedwaterFlow() {
        double one = FeedwaterDesign.MOTOR_FEED_PUMP.ratedFlowKgPerS();
        double rated = FeedwaterDesign.RATED_FEEDWATER_FLOW_KG_PER_S;

        Check.relative(0.5, one / rated, 0.02,
                "one feed pump as a fraction of rated plant feedwater flow");
        Check.relative(rated, 2.0 * one, 0.02, "two feed pumps against rated flow");
        Check.exactly(one, FeedwaterDesign.TURBINE_FEED_PUMP.ratedFlowKgPerS(),
                "the two drives are the same pump and must have the same capacity");

        // A feed pump that cannot out-develop the dome is a feed pump that never
        // works. Shutoff head has to clear rated pressure with margin, or the
        // plant has no normal inventory path at power at all.
        Check.greaterThan(RATED_DOME_PSIG, FeedwaterDesign.FEED_PUMP_SHUTOFF_HEAD_PSI,
                "feed pump shutoff head against rated dome pressure");
        Check.greaterThan(RATED_DOME_PSIG, FeedwaterDesign.FEED_PUMP_RATED_HEAD_PSI,
                "feed pump rated head against rated dome pressure");
    }

    /**
     * Both pumps feed a vessel at rated pressure, and neither feeds one above its
     * shutoff head.
     *
     * <p>The second half is the one that matters. Nothing refuses: there is no
     * injection permissive and no pressure interlock anywhere in this model. The
     * water stops arriving because the impeller cannot develop the head, which is
     * the same reason it stops in a real plant.
     */
    public static void test02_shutoffHeadStopsFlowWithoutRefusingAnything() {
        for (FeedwaterDesign design : FeedwaterDesign.all()) {
            EccsPump atPower = settled(design, RATED_DOME_PSIG, amplePower(design));
            Check.greaterThan(0.0, atPower.getFlowKgPerS(),
                    design.displayName() + " flow at rated dome pressure");
            Check.isFalse(atPower.isAboveShutoffHead(),
                    "%s should be below its shutoff head at %.0f psig",
                    design.displayName(), RATED_DOME_PSIG);

            double aboveShutoff = design.maximumDischargePsi() + 100.0;
            EccsPump stalled = settled(design, aboveShutoff, amplePower(design));
            Check.exactly(0.0, stalled.getFlowKgPerS(),
                    design.displayName() + " flow above its shutoff head");
            Check.isTrue(stalled.isAboveShutoffHead(),
                    "%s should report itself above shutoff head at %.0f psig",
                    design.displayName(), aboveShutoff);
        }
    }

    /**
     * The motor-driven pump is a large electrical commitment and stops dead
     * without a bus. The turbine-driven pump refuses electricity entirely.
     *
     * <p>These two facts are the whole reason there are two pumps, and both are
     * consequences of one number — the motor rating — rather than of any branch.
     */
    public static void test03_theMotorDrivenPumpNeedsAnElectricalSupply() {
        FeedwaterDesign motor = FeedwaterDesign.MOTOR_FEED_PUMP;

        EccsPump supplied = settled(motor, RATED_DOME_PSIG, amplePower(motor));
        Check.greaterThan(0.0, supplied.getFlowKgPerS(), "motor feed pump flow when supplied");
        Check.greaterThan(0.0, supplied.getElectricalDemandWatts(),
                "motor feed pump electrical demand");
        Check.exactly(0.0, supplied.getSteamDemandKgPerS(),
                "a motor drive must take no steam out of the vessel");

        EccsPump blackout = settled(motor, RATED_DOME_PSIG, 0.0);
        Check.exactly(0.0, blackout.getFlowKgPerS(),
                "motor feed pump flow with no electrical supply");
        Check.exactly(0.0, blackout.getSpeedFraction(),
                "motor feed pump shaft speed with no electrical supply");

        // Sized against the real duty point rather than picked. A motor that
        // cannot turn its own pump at rated conditions is a machine that never
        // works, and a motor several times larger than it needs to be quietly
        // deletes the electrical cost that is the point of this pump existing.
        Check.inRange(1.0, 1.5, motor.motorRatingWatts() / motor.ratedShaftPowerWatts(),
                "motor rating as a multiple of rated shaft power");
        Check.greaterThan(1.0e6, motor.motorRatingWatts(),
                "a reactor feed pump motor is a megawatt-class machine");
    }

    /**
     * The turbine-driven pump costs no electricity, takes steam out of the
     * vessel to run, and dies on a depressurised vessel with nobody having
     * written a cutoff pressure.
     *
     * <p>The last of those is the emergent behaviour the whole
     * {@code SteamTurbineDrive} model exists for: specific work falls with the
     * expansion ratio until the wheel cannot overcome its own windage. It is
     * also what makes turbine-driven feedwater unavailable exactly when a player
     * has blown the vessel down, which is a real and unpleasant coupling.
     */
    public static void test04_theTurbineDrivenPumpIsHostageToVesselPressure() {
        FeedwaterDesign turbine = FeedwaterDesign.TURBINE_FEED_PUMP;

        Check.exactly(0.0, turbine.motorRatingWatts(),
                "a turbine drive must have no motor rating, so it refuses every joule offered");

        EccsPump atPower = settled(turbine, RATED_DOME_PSIG, 0.0);
        Check.greaterThan(0.0, atPower.getFlowKgPerS(),
                "turbine feed pump flow at rated pressure with no electrical supply at all");
        Check.greaterThan(0.0, atPower.getSteamDemandKgPerS(),
                "turbine feed pump drive steam at rated pressure");
        Check.exactly(0.0, atPower.getElectricalDemandWatts(),
                "a turbine drive must want no electricity");

        // Cheap at power. If this ever climbs to a few per cent of rated steam
        // flow the pump has stopped being a good machine and has become RCIC.
        double steamFraction =
                atPower.getSteamDemandKgPerS() / FeedwaterDesign.RATED_FEEDWATER_FLOW_KG_PER_S;
        Check.inRange(0.0, 0.03, steamFraction,
                "turbine feed pump drive steam as a fraction of rated steam flow");

        // A cold plant has no steam, so it has no feedwater from this machine.
        EccsPump cold = settled(turbine, 0.0, 0.0);
        Check.exactly(0.0, cold.getFlowKgPerS(),
                "turbine feed pump flow on a cold, unpressurised vessel");

        // And the fade is gradual on the way down rather than a cliff at some
        // written-down number.
        double previous = Double.MAX_VALUE;
        for (double psig : new double[]{1025.0, 800.0, 600.0, 400.0, 200.0, 100.0}) {
            double flow = settled(turbine, psig, 0.0).getFlowKgPerS();
            Check.finiteAndNonNegative(flow, "turbine feed pump flow at " + psig + " psig");
            Check.isTrue(flow <= previous + 1.0e-9,
                    "turbine feed pump flow should not rise as the vessel depressurises: "
                            + "%.3f kg/s at %.0f psig follows %.3f kg/s", flow, psig, previous);
            previous = flow;
        }
    }

    /**
     * A stopped pump coasts down rather than stopping dead, and a feed pump
     * coasts down much faster than a recirculation pump.
     *
     * <p>That asymmetry is deliberate and it is the reason losing feedwater is a
     * sharper transient than losing flow: a recirculation pump carries a flywheel
     * precisely so that flow decays slowly, and a feed pump carries none because
     * nobody wants inventory still arriving after the pump was stopped.
     */
    public static void test05_aStoppedFeedPumpCoastsDownAndThenStops() {
        FeedwaterDesign design = FeedwaterDesign.MOTOR_FEED_PUMP;
        EccsPump pump = settled(design, RATED_DOME_PSIG, amplePower(design));
        double running = pump.getSpeedFraction();
        Check.greaterThan(0.5, running, "shaft speed before the stop command");

        pump.setRunning(false);
        pump.step(0.05);
        double justAfter = pump.getSpeedFraction();
        Check.isTrue(justAfter < running,
                "the shaft should have begun slowing one tick after the stop command");
        Check.greaterThan(0.0, justAfter,
                "a feed pump must not stop dead in a single tick — it has inertia");

        for (int i = 0; i < 1200; i++) {
            pump.step(0.05);
        }
        Check.exactly(0.0, pump.getSpeedFraction(), "shaft speed a minute after the stop command");
        Check.exactly(0.0, pump.getFlowKgPerS(), "flow a minute after the stop command");

        Check.lessThan(11.0, design.coastdownSeconds(),
                "a feed pump must coast down faster than a recirculation pump's flywheel");
    }

    /**
     * The heater string: cold feedwater on a cold plant, rated feedwater at
     * rated flow, and monotone in between.
     *
     * <p>This is what stops a plant with feed pumps and no modelled heaters from
     * being unable to reach rated power — feeding 32 °C water at rated flow costs
     * roughly a quarter of rated thermal power in heating duty alone. It also
     * produces loss of feedwater heating for free: anything that cuts feedwater
     * flow cuts feedwater temperature with it, which adds subcooling, which adds
     * reactivity. That transient takes power <i>up</i>, and it surprises people.
     */
    public static void test06_theHeaterStringTracksFlowAndNeverCools() {
        final double condensate = 32.0;
        final double rated = PhysicalConstants.RATED_FEEDWATER_TEMPERATURE_C;

        Check.exactly(condensate, FeedwaterHeating.finalTemperatureC(condensate, 0.0),
                "feedwater temperature with no flow at all");
        Check.exactly(rated, FeedwaterHeating.finalTemperatureC(condensate, 1.0),
                "feedwater temperature at rated flow");
        Check.exactly(rated, FeedwaterHeating.finalTemperatureC(condensate, 4.0),
                "the heaters are saturated above rated flow, not hotter");

        double previous = -Double.MAX_VALUE;
        for (double fraction = 0.0; fraction <= 1.0 + 1.0e-9; fraction += 0.05) {
            double t = FeedwaterHeating.finalTemperatureC(condensate, fraction);
            Check.inRange(condensate - 1.0e-9, rated + 1.0e-9, t,
                    "feedwater temperature at flow fraction " + fraction);
            Check.isTrue(t >= previous - 1.0e-9,
                    "feedwater temperature must not fall as flow rises: %.3f at %.2f", t, fraction);
            previous = t;
        }

        // A heater string cannot cool anything, and a non-finite argument must
        // not become a non-finite vessel inlet temperature.
        Check.exactly(300.0, FeedwaterHeating.finalTemperatureC(300.0, 0.0),
                "water already hotter than the heaters passes through unchanged");
        Check.finite(FeedwaterHeating.finalTemperatureC(condensate, Double.NaN),
                "feedwater temperature with a non-finite flow fraction");
        Check.finite(FeedwaterHeating.finalTemperatureC(Double.NaN, 0.5),
                "feedwater temperature with a non-finite suction temperature");
    }

    /**
     * The generalised pump model really is shared, and the feed pumps really do
     * go through it.
     *
     * <p>{@link EccsPump} took an {@code EccsDesign} until the feed pumps needed
     * it. If someone later re-narrows it, or quietly forks a second pump model
     * for feedwater, this stops compiling or stops passing — which is the point.
     * Two copies of a pump model drift, and the second copy is always the one
     * that misses the fix.
     */
    public static void test07_bothFeedPumpsRunOnTheSharedPumpModel() {
        for (FeedwaterDesign design : FeedwaterDesign.all()) {
            PumpDesign asShared = design;
            EccsPump pump = new EccsPump(asShared);
            Check.isTrue(pump.design() == asShared,
                    "%s must be driven by the shared pump model", design.displayName());
            Check.isFalse(design.isConstantDisplacement(),
                    "%s is a centrifugal pump and must have a curve", design.displayName());
            Check.exactly(0.0, design.boronPpmPerMinuteAtRatedFlow(),
                    "a feed pump must not inject boron: " + design.displayName());
            Check.finiteAndPositive(design.ratedShaftPowerWatts(),
                    "rated shaft power of " + design.displayName());
        }

        Check.isTrue(FeedwaterDesign.MOTOR_FEED_PUMP.drive() == PumpDesign.Drive.ELECTRIC_MOTOR,
                "the motor-driven feed pump must be motor driven");
        Check.isTrue(FeedwaterDesign.TURBINE_FEED_PUMP.drive() == PumpDesign.Drive.STEAM_TURBINE,
                "the turbine-driven feed pump must be turbine driven");
        Check.isTrue(FeedwaterDesign.byId("motor_feed_pump") == FeedwaterDesign.MOTOR_FEED_PUMP,
                "byId must find the motor-driven pump");
        Check.isTrue(FeedwaterDesign.byId("turbine_feed_pump") == FeedwaterDesign.TURBINE_FEED_PUMP,
                "byId must find the turbine-driven pump");
        Check.isTrue(FeedwaterDesign.byId("no_such_pump") == null,
                "byId must answer null for an id that does not exist");
    }
}
