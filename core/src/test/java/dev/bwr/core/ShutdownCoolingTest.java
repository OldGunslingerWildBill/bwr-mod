package dev.bwr.core;

import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.pool.RhrHeatExchanger;
import dev.bwr.core.thermal.PressureVessel;

/** Operator-lined RHR vessel loop and its low-pressure pipe consequence. */
public final class ShutdownCoolingTest {
    private ShutdownCoolingTest() {}

    public static void test01_exchangerCoolsAReactorBelowBoiling() {
        var vessel = new PressureVessel(new CoreConfig());
        vessel.initialiseToNormalLevel();
        vessel.restorePressurePsig(130);
        var exchanger = new RhrHeatExchanger();
        double start = vessel.getSaturationTemperatureC();
        double peakMW = 0;
        // The former one-hour horizon assumed an uncited 100-MW exchanger.
        // A single 27.1-MW BWR exchanger removing 20 MW of continuing decay
        // heat needs hours; the cold-shutdown assertion itself is unchanged.
        for (int i = 0; i < 24 * 3600 && vessel.getSaturationTemperatureC() >= 100; i++) {
            exchanger.drainHot(2400, false);
            exchanger.fillCold(2400, false);
            double duty = exchanger.stepReactor(vessel.getSaturationTemperatureC(),
                    vessel.getLiquidMassKg(), 1500, 1.0);
            peakMW = Math.max(peakMW, duty);
            vessel.setShutdownCoolingMW(duty);
            vessel.step(20, 0, 0, 0, 0, 1.0);
        }
        Check.greaterThan(10, peakMW, "running physical exchanger removes measurable heat");
        Check.lessThan(start, vessel.getSaturationTemperatureC(), "shutdown loop cools the vessel");
        Check.lessThan(100, vessel.getSaturationTemperatureC(), "vessel reaches cold shutdown");
        Check.note("shutdown cooling: %.1f C -> %.1f C, peak exchanger %.1f MW",
                start, vessel.getSaturationTemperatureC(), peakMW);
    }

    public static void test02_highPressureLineupMakesAnOutsideContainmentBreak() {
        var boundary = new BoundaryStress();
        Check.greaterThan(100, BoundaryStress.RHR_PIPE_DESIGN_PRESSURE_PSIG,
                "low pressure RHR line has a named rating");
        Check.isFalse(boundary.hasFailed(), "line is sound before lineup");
        boundary.forceInterfacingSystemsLoca();
        Check.isTrue(boundary.isInterfacingSystemsLoca() && boundary.hasFailed(),
                "exposed low pressure pipe ruptures");
        Check.greaterThan(0, boundary.activeOpenings().size(), "rupture drains the pressure boundary");
        var restored = new BoundaryStress();
        restored.fromArray(boundary.toArray());
        Check.isTrue(restored.isInterfacingSystemsLoca(), "outside break survives a save");
        restored.repairInterfacingSystemsLine();
        Check.isFalse(restored.hasFailed(), "repair closes only the outside break");
    }

    public static void test03_shutdownCoolingReportsExpireAfterOneCoreStep() {
        var core = new ReactorCore(new CoreConfig());
        core.initialiseHotShutdown();
        core.addShutdownCoolingMW(80);
        core.step();
        Check.absolute(80, core.getShutdownCoolingMW(), 0, "measured duty applied");
        core.step();
        Check.absolute(0, core.getShutdownCoolingMW(), 0, "unreported duty expires");
    }

    public static void test04_vesselMetalStoresHeatDuringForcedCooldown() {
        var vessel = new PressureVessel(new CoreConfig());
        vessel.initialiseToNormalLevel();
        vessel.restorePressurePsig(130);
        double seconds = 0;
        while (vessel.getSaturationTemperatureC() >= 100 && seconds < 3600) {
            vessel.setShutdownCoolingMW(100);
            vessel.step(20, 0, 0, 0, 0, .05);
            seconds += .05;
        }
        Check.greaterThan(18 * 60, seconds,
                "vessel and internals stored heat prevents the former 15.9-minute cooldown");
        Check.greaterThan(vessel.getSaturationTemperatureC(), vessel.getMetalTemperatureC(),
                "the steel remains hotter than coolant during rapid shutdown cooling");
    }

    public static void test05_excessCooldownRateDamagesBoundaryWithoutInterlock() {
        var boundary = new BoundaryStress();
        boundary.recordCooldownRateCPerHour(80, 600);
        Check.greaterThan(0, boundary.getCooldownThermalStress(),
                "faster-than-100-F-per-hour cooldown uses boundary life");
        Check.greaterThan(0, boundary.getSecondsAboveCooldownLimit(),
                "time above cooldown rate limit is recorded");
        double damage = boundary.getCooldownThermalStress();
        boundary.recordCooldownRateCPerHour(30, 600);
        Check.absolute(damage, boundary.getCooldownThermalStress(), 0,
                "throttled cooldown adds no thermal-stress damage");
        var restored = new BoundaryStress();
        restored.fromArray(boundary.toArray());
        Check.absolute(damage, restored.getCooldownThermalStress(), 0,
                "cooldown damage survives a save");
    }

    public static void test06_hotMetalNodeRoundTripsAndLegacyEquilibrates() {
        var core = new ReactorCore(new CoreConfig());
        core.initialiseHotShutdown();
        core.getPressureVessel().restorePressurePsig(130);
        core.getPressureVessel().restoreMetalTemperatureC(170);
        var state = core.toState();
        var loaded = new ReactorCore(new CoreConfig());
        loaded.fromState(state);
        Check.absolute(170, loaded.getPressureVessel().getMetalTemperatureC(), 0,
                "saved steel temperature survives core restore");
        loaded.getPressureVessel().restoreMetalTemperatureC(0);
        Check.absolute(loaded.getCoolantTemperatureC(), loaded.getPressureVessel().getMetalTemperatureC(), 1e-9,
                "legacy missing metal node starts at coolant equilibrium");
    }
}
