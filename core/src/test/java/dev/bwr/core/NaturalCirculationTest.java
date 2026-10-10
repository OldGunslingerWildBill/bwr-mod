package dev.bwr.core;

/**
 * SPEC 4.5: with the recirculation pumps tripped or absent, buoyancy keeps
 * water moving through the core at a rate set by power and level.
 */
public final class NaturalCirculationTest {

    private NaturalCirculationTest() {
    }

    static ReactorCore ratedCore() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.initialiseAtTotalPowerFraction(1.0);
        return core;
    }

    static void run(ReactorCore core, double seconds, double dt) {
        int steps = (int) Math.round(seconds / dt);
        for (int i = 0; i < steps; i++) {
            core.step(dt);
        }
    }

    /**
     * Run with a minimal pressure regulator and level controller standing in
     * for the plant's: turbine flow follows steam production to hold rated
     * pressure, feedwater follows turbine flow to hold normal level. Without
     * them a rated turbine demand depressurises the vessel as power falls and
     * the core flashes to steam, which is a test of the controller, not of
     * natural circulation.
     */
    static void runRegulated(ReactorCore core, double seconds, double dt) {
        int steps = (int) Math.round(seconds / dt);
        for (int i = 0; i < steps; i++) {
            double pressureError = core.getPressurePsig() - PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
            core.setTurbineSteamFlowKgPerS(Math.max(0.0, core.getSteamGenerationKgPerS() + 2.0 * pressureError));
            core.setFeedwaterFlowKgPerS(Math.max(0.0, core.getTurbineSteamFlowKgPerS()
                    - 20.0 * (core.getCollapsedLevelIn() - 8.0)));
            core.step(dt);
        }
    }

    public static void test01_pumpTripAtPowerSettlesOnNaturalCirculation() {
        ReactorCore core = ratedCore();
        core.setRecirculationFlowFraction(0.0);
        runRegulated(core, 240, 0.25);
        double flow = core.getCoreFlowFraction();
        double power = core.getTotalPowerFractionOfRated();
        Check.inRange(0.15, 0.40, flow, "natural circulation flow after a two-pump trip at rated");
        Check.inRange(0.15, 0.60, power, "power on natural circulation");
        Check.absolute(PhysicalConstants.RATED_DOME_PRESSURE_PSIG, core.getPressurePsig(), 15.0, "regulated pressure");
        Check.isTrue(core.isNaturalCirculationDominant(), "natural circulation is setting the flow");
        Check.relative(ReactorCore.NATURAL_CIRCULATION_COEFFICIENT * Math.cbrt(power),
                core.getNaturalCirculationFlowFraction(), 0.05, "flow follows the cube root of power");
        Check.note("two-pump trip from rated, regulated: %.1f%% flow at %.1f%% power after 4 min",
                100 * flow, 100 * power);
    }

    public static void test02_withoutNaturalCirculationFlowStops() {
        ReactorCore core = ratedCore();
        core.setNaturalCirculationEnabled(false);
        core.setRecirculationFlowFraction(0.0);
        runRegulated(core, 180, 0.25);
        Check.lessThan(0.01, core.getCoreFlowFraction(), "flow with the buoyancy floor disabled");
        Check.exactly(0.0, core.getNaturalCirculationFlowFraction(), "reported natural circulation");
    }

    public static void test03_decayHeatAloneDrivesASmallFlow() {
        ReactorCore core = ratedCore();
        core.scram();
        core.setRecirculationFlowFraction(0.0);
        run(core, 300, 0.25);
        double flow = core.getCoreFlowFraction();
        Check.inRange(0.04, 0.2, flow, "natural circulation on decay heat");
        Check.note("5 min after scram and pump trip: %.1f%% flow on %.2f%% power",
                100 * flow, 100 * core.getTotalPowerFractionOfRated());
    }

    public static void test04_lowLevelBreaksTheCirculationPath() {
        ReactorCore core = ratedCore();
        core.getPressureVessel().setCollapsedLevelIn(ReactorCore.NATURAL_CIRCULATION_FULL_LEVEL_IN);
        double full = core.naturalCirculationFlowFraction();
        core.getPressureVessel().setCollapsedLevelIn(
                0.5 * (ReactorCore.NATURAL_CIRCULATION_FULL_LEVEL_IN + ReactorCore.NATURAL_CIRCULATION_LOST_LEVEL_IN));
        double half = core.naturalCirculationFlowFraction();
        core.getPressureVessel().setCollapsedLevelIn(ReactorCore.NATURAL_CIRCULATION_LOST_LEVEL_IN - 1.0);
        double lost = core.naturalCirculationFlowFraction();
        Check.greaterThan(0.3, full, "natural circulation at full level and rated power");
        Check.relative(0.5 * full, half, 1e-9, "half-way level halves the driving path");
        Check.exactly(0.0, lost, "no natural circulation below the separator return");
    }

    public static void test06_aRecirculationBreakShortCircuitsTheLoop() {
        ReactorCore core = ratedCore();
        double intact = core.naturalCirculationFlowFraction();
        core.getBoundaryStress().forceFailure(dev.bwr.core.boundary.BoundaryComponent.RECIRCULATION_LINE,
                core.getPressurePsig());
        Check.greaterThan(0.3, intact, "natural circulation available before the break");
        Check.exactly(0.0, core.naturalCirculationFlowFraction(), "natural circulation through a broken loop");
    }

    public static void test05_runningPumpsOverrideTheFloor() {
        ReactorCore core = ratedCore();
        run(core, 20, 0.25);
        Check.relative(1.0, core.getCoreFlowFraction(), 0.02, "rated pumped flow is unchanged");
        Check.isFalse(core.isNaturalCirculationDominant(), "pumps are setting the flow");
    }
}
