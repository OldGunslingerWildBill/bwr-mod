package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

import java.util.Arrays;

/** Acceptance checks for world-sized, leaky containment and coolant-line bypass. */
public final class ContainmentStructurePhysicsTest {
    private ContainmentStructurePhysicsTest() {
    }

    public static void test01_freeVolumePreservesPressureAndDoublesReleaseConcentration() {
        var resized = new ContainmentAtmosphere();
        resized.step(0.02, 3.0, 0.0, 20.0);
        double pressure = resized.getPressurePsig();
        double hydrogen = resized.getHydrogenPercent();
        resized.setFreeVolumeM3(4000.0);
        Check.absolute(pressure, resized.getPressurePsig(), 1e-10, "resizing changed pressure");
        Check.absolute(hydrogen, resized.getHydrogenPercent(), 1e-10, "resizing changed gas fractions");
        resized.setFreeVolumeM3(1.0);
        Check.absolute(50.0, resized.getVolumeM3(), 0.0, "minimum free volume");

        var small = new ContainmentAtmosphere();
        small.setFreeVolumeM3(4000.0);
        var large = new ContainmentAtmosphere();
        small.step(1.0, 0.0, 0.0, 1.0);
        large.step(1.0, 0.0, 0.0, 1.0);
        Check.relative(2.0 * large.getHydrogenPercent(), small.getHydrogenPercent(), 0.01,
                "same release in half the volume");
    }

    public static void test02_thirtyPsiWallFailsOnABurnThatTheDefaultSurvives() {
        var prepared = new ContainmentAtmosphere();
        double[] gas = prepared.toArray();
        double ambientMoles = gas[2] + gas[3];
        gas[5] = ambientMoles * 20.0 /
                (ContainmentAtmosphere.ATMOSPHERIC_PA / ContainmentAtmosphere.PA_PER_PSI);
        prepared.fromArray(gas);
        var weak = new ContainmentAtmosphere();
        weak.fromArray(prepared.toArray());
        weak.setFailurePressurePsig(30.0);
        var strong = new ContainmentAtmosphere();
        strong.fromArray(prepared.toArray());
        double totalMoles = gas[2] + gas[3] + gas[5];
        double h2Kg = 0.081 / (1.0 - 0.081) * totalMoles /
                ContainmentAtmosphere.HYDROGEN_MOL_PER_KG;
        weak.step(h2Kg, 0.0, 0.0, 1.0);
        strong.step(h2Kg, 0.0, 0.0, 1.0);
        Check.exactly(1, weak.getBurnCount(), "weak-wall burn occurred");
        Check.exactly(1, strong.getBurnCount(), "strong-wall burn occurred");
        Check.isTrue(weak.getFailureMode() == ContainmentAtmosphere.FailureMode.OVERPRESSURE,
                "30 psig wall survived burn");
        Check.isFalse(strong.isFailed(), "140 psig wall failed the same burn");
        Check.inRange(30.0, 140.0, weak.getLastBurnPeakPressurePsig(), "burn pressure bracket");
    }

    public static void test03_openBoundaryDispersesGasAndRestoringSealAllowsAccumulation() {
        var ct = new ContainmentAtmosphere();
        ct.setLeakArea(1.0);
        ct.step(0.0, 0.0, 0.02, 1.0);
        Check.absolute(0.02, ct.getEnvironmentalReleaseFraction(), 1e-12,
                "uncontained activity must go directly outdoors");
        Check.absolute(0.0, ct.getAirborneFraction(), 1e-12,
                "uncontained activity stored indoors");
        double peak = 0.0;
        for (int i = 0; i < 600; i++) {
            ct.step(0.5, 0.0, 0.0, 1.0);
            peak = Math.max(peak, ct.getHydrogenPercent());
        }
        Check.lessThan(100.0 * ContainmentAtmosphere.FLAMMABILITY_FRACTION, peak,
                "uncontained hydrogen reached flammability during a steady release");
        Check.isFalse(ct.isFailed(), "an opening alone set a failure mode");
        ct.setLeakArea(0.0);
        for (int i = 0; i < 60; i++) ct.step(0.5, 0.0, 0.0, 1.0);
        Check.greaterThan(peak + 1.0, ct.getHydrogenPercent(),
                "hydrogen did not accumulate after the boundary was repaired");
    }

    public static void test04_newSnapshotRoundTripsAndLegacySnapshotLoads() {
        var ct = new ContainmentAtmosphere();
        ct.setFreeVolumeM3(1234.0);
        ct.setFailurePressurePsig(81.0);
        ct.setLeakArea(0.4);
        ct.step(0.01, 0.0, 0.02, 2.0);
        double[] snapshot = ct.toArray();
        Check.exactly(ContainmentAtmosphere.SNAPSHOT_VERSION, (int) snapshot[0], "version");
        var restored = new ContainmentAtmosphere();
        restored.fromArray(snapshot);
        Check.arraysExactly(snapshot, restored.toArray(), "new containment snapshot");

        double[] legacy = Arrays.copyOf(snapshot, ContainmentAtmosphere.LEGACY_SNAPSHOT_LENGTH);
        legacy[0] = 1.0;
        var migrated = new ContainmentAtmosphere();
        migrated.fromArray(legacy);
        Check.absolute(ct.getHydrogenPercent(), migrated.getHydrogenPercent(), 1e-12,
                "legacy gas inventory");
        Check.absolute(ContainmentAtmosphere.FAILURE_PRESSURE_PSIG,
                migrated.getFailurePressurePsig(), 0.0, "legacy failure pressure");
        Check.absolute(0.0, migrated.getLeakArea(), 0.0, "legacy sealed default");
    }

    public static void test05_wallResistanceCalibrationAndCap() {
        Check.inRange(29.0, 31.0, 3.0 * ContainmentWallStrength.psi(3.0),
                "three wood plank layers");
        Check.inRange(13.0, 15.0, ContainmentWallStrength.psi(6.0),
                "stone or iron resistance");
        Check.greaterThan(140.0, 2.0 * ContainmentWallStrength.psi(1200.0),
                "two obsidian layers");
        Check.absolute(250.0, ContainmentWallStrength.psi(Double.POSITIVE_INFINITY), 0.0,
                "per-block cap");
        Check.absolute(0.0, ContainmentWallStrength.psi(-1.0), 0.0,
                "negative resistance");
    }

    public static void test06_openCoolantLineBypassesContainmentOnlyAfterCladdingFails() {
        var sealed = damagedCore(0.0, 0.0);
        var bypassed = damagedCore(1.0, 0.0);
        sealed.step(0.05);
        bypassed.step(0.05);
        double released = sealed.getSevereAccident().getLastReleasedFraction();
        Check.greaterThan(0.0, released, "damaged cladding released no inventory");
        Check.absolute(released * ReactorCore.BYPASS_CARRYOVER,
                bypassed.getContainment().getEnvironmentalReleaseFraction()
                        - sealed.getContainment().getEnvironmentalReleaseFraction(),
                1e-6, "open line direct release");
        Check.absolute(released * ReactorCore.BYPASS_CARRYOVER,
                sealed.getContainment().getAirborneFraction()
                        - bypassed.getContainment().getAirborneFraction(),
                1e-6, "airborne share removed by bypass");

        var intact = new ReactorCore(new CoreConfig());
        intact.setContainmentBypassFlowFraction(1.0);
        intact.step(0.05);
        Check.absolute(0.0, intact.getContainment().getEnvironmentalReleaseFraction(), 1e-12,
                "open line released activity before cladding failed");

        var uncontained = damagedCore(1.0, 1.0);
        uncontained.step(0.05);
        Check.absolute(uncontained.getSevereAccident().getLastReleasedFraction(),
                uncontained.getContainment().getEnvironmentalReleaseFraction(), 1e-6,
                "uncontained release was bypassed twice or retained indoors");
    }

    private static ReactorCore damagedCore(double bypassFlow, double leakArea) {
        var core = new ReactorCore(new CoreConfig());
        double[] state = core.getSevereAccident().toArray();
        state[1] = 0.5;
        state[10] = 0.0;
        state[13] = SevereAccident.Stage.CLADDING_FAILURE.number();
        core.getSevereAccident().fromArray(state);
        core.getContainment().setLeakArea(leakArea);
        core.setContainmentBypassFlowFraction(bypassFlow);
        return core;
    }
}
