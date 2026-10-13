package dev.bwr.core;

import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.SevereAccident;
import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.fuel.CoreInsert;

/** Gameplay knobs affect physical outcomes while defaults remain unchanged. */
public final class GameplayTuningTest {
    private GameplayTuningTest() {}

    public static void test01_failureHazardCanBeDisabledWithoutDisablingStress() {
        var stressed = new BoundaryStress();
        stressed.setFailureHazardPerSecond(0);
        stressed.step(1375, 2400);
        Check.greaterThan(1, stressed.getStress(dev.bwr.core.boundary.BoundaryComponent.MAIN_STEAM_LINE),
                "stress still accumulates when random rupture odds are zero");
        Check.isFalse(stressed.hasFailed(), "zero rupture odds prevent random failure");
        Check.absolute(0, stressed.getFailureRatePerSecond(), 0, "zero hazard rate");
    }

    public static void test02_accidentSpeedChangesRelocationNotFuelThermodynamics() {
        var normal = new SevereAccident();
        var fast = new SevereAccident();
        fast.setProgressionMultiplier(2);
        normal.step(2300, 500, 1000, 0, 0, -500, 1, 0, 10);
        fast.step(2300, 500, 1000, 0, 0, -500, 1, 0, 10);
        Check.greaterThan(normal.getRelocatedFraction(), fast.getRelocatedFraction(),
                "faster progression moves more molten fuel per simulated second");
        Check.absolute(normal.getFuelEnergyToRemoveMJ(), fast.getFuelEnergyToRemoveMJ(), 1e-9,
                "rate tuning must not change the energy required to melt fuel");
    }

    public static void test03_contaminationHalfLifeIsTunable() {
        var atmosphere = new ContainmentAtmosphere();
        atmosphere.setContaminationHalfLifeSeconds(60);
        atmosphere.releaseDirectToEnvironment(1);
        atmosphere.step(0, 0, 0, 60);
        Check.absolute(.5, atmosphere.getSiteContaminationIndex(), 1e-9,
                "one configured half-life halves site hazard");
    }

    public static void test04_irradiationTargetTimeIsTunable() {
        var kind = CoreInsert.Kind.COBALT_TARGET;
        double original = kind.exposureSeconds;
        try {
            kind.setExposureSeconds(3600);
            var insert = new CoreInsert(kind, 0);
            insert.irradiate(1, 1800);
            Check.absolute(.5, insert.progress(), 1e-12, "half the configured exposure");
        } finally {
            kind.setExposureSeconds(original);
        }
    }
}
