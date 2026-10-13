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
        for (int i = 0; i < 3600 * 20 && vessel.getSaturationTemperatureC() >= 100; i++) {
            exchanger.drainHot(120, false);
            exchanger.fillCold(120, false);
            double duty = exchanger.stepReactor(vessel.getSaturationTemperatureC(),
                    vessel.getLiquidMassKg(), 1500, .05);
            peakMW = Math.max(peakMW, duty);
            vessel.setShutdownCoolingMW(duty);
            vessel.step(20, 0, 0, 0, 0, .05);
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
}
