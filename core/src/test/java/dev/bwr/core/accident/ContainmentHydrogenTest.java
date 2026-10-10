package dev.bwr.core.accident;

import dev.bwr.core.Check;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC 8.3: hydrogen in containment, inerting, burns, detonation, venting
 * and fission product release.
 */
public final class ContainmentHydrogenTest {

    private ContainmentHydrogenTest() {
    }

    static List<ContainmentAtmosphere.Event> run(ContainmentAtmosphere ct, double h2KgPerS, double gasMolPerS,
                                                 double seconds, double dt) {
        List<ContainmentAtmosphere.Event> events = new ArrayList<>();
        int steps = (int) Math.round(seconds / dt);
        for (int i = 0; i < steps; i++) {
            ct.step(h2KgPerS, gasMolPerS, 0.0, dt);
            events.addAll(ct.drainEvents());
        }
        return events;
    }

    public static void test01_airFilledContainmentBurnsAtEightPercent() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        Check.relative(21.0, ct.getOxygenPercent(), 1e-9, "fresh containment oxygen");
        Check.absolute(0.0, ct.getPressurePsig(), 1e-6, "fresh containment pressure");
        double peakH2 = 0;
        List<ContainmentAtmosphere.Event> events = new ArrayList<>();
        for (int i = 0; i < 4000 && ct.getBurnCount() == 0; i++) {
            ct.step(0.05, 0, 0, 1.0);
            peakH2 = Math.max(peakH2, ct.getHydrogenPercent());
            events.addAll(ct.drainEvents());
        }
        Check.exactly(1, ct.getBurnCount(), "one burn");
        Check.isTrue(events.indexOf(ContainmentAtmosphere.Event.HYDROGEN_FLAMMABLE)
                        < events.indexOf(ContainmentAtmosphere.Event.HYDROGEN_DEFLAGRATION)
                        && events.contains(ContainmentAtmosphere.Event.HYDROGEN_FLAMMABLE),
                "flammable warning precedes the burn, got %s", events);
        Check.isFalse(events.contains(ContainmentAtmosphere.Event.HYDROGEN_DETONATION), "a lean burn is not a detonation");
        Check.inRange(7.5, 8.5, peakH2, "hydrogen percent at ignition");
        Check.lessThan(1.0, ct.getHydrogenPercent(), "hydrogen after the burn");
        Check.lessThan(20.0, ct.getOxygenPercent(), "oxygen consumed by the burn");
        Check.greaterThan(5.0, ct.getLastBurnPeakPressurePsig(), "burn pressure spike");
        Check.isFalse(ct.isFailed(), "an 8% deflagration does not fail containment");
        Check.note("ignited at %.2f%% H2, spike to %.1f psig, %.0f kg burned",
                peakH2, ct.getLastBurnPeakPressurePsig(), ct.getHydrogenBurnedKg());
    }

    public static void test02_inertedContainmentStoresHydrogenUntilOxygenReturns() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        ct.initialiseInerted();
        Check.isTrue(ct.isInerted(), "initialised inerted");
        var events = run(ct, 0.5, 0, 1200, 1.0);
        Check.exactly(0, ct.getBurnCount(), "no burn while inerted");
        Check.greaterThan(20.0, ct.getHydrogenPercent(), "hydrogen accumulated while inerted");
        Check.isFalse(events.contains(ContainmentAtmosphere.Event.HYDROGEN_FLAMMABLE), "no flammable warning while inerted");

        // De-inert with all that hydrogen in it.
        ct.setInertingCommanded(false);
        events = run(ct, 0, 0, 4 * 3600, 1.0);
        Check.isTrue(events.contains(ContainmentAtmosphere.Event.HYDROGEN_DETONATION),
                "de-inerting a hydrogen-rich containment detonates, got %s", events);
        Check.isTrue(ct.isFailed(), "detonation fails containment");
        Check.isTrue(ct.getFailureMode() == ContainmentAtmosphere.FailureMode.DETONATION, "failure mode");
        Check.note("inerted: %.0f kg H2 stored safely; de-inerting detonated it", 600.0);
    }

    public static void test03_inertingTakesTime() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        ct.setInertingCommanded(true);
        run(ct, 0, 0, 600, 1.0);
        Check.isFalse(ct.isInerted(), "ten minutes is not enough to inert");
        run(ct, 0, 0, 3 * 3600, 5.0);
        Check.isTrue(ct.isInerted(), "inerted after a few time constants");
        Check.lessThan(4.0, ct.getOxygenPercent(), "inerted oxygen");
    }

    public static void test04_ventingRelievesPressureThroughThePool() {
        ContainmentAtmosphere vented = new ContainmentAtmosphere();
        vented.initialiseInerted();
        ContainmentAtmosphere sealed = new ContainmentAtmosphere();
        sealed.initialiseInerted();
        vented.step(0, 0, 0.10, 1.0);
        sealed.step(0, 0, 0.10, 1.0);
        run(vented, 0, 50, 1800, 1.0);
        run(sealed, 0, 50, 1800, 1.0);
        vented.setVentOpen(true);
        run(vented, 0, 50, 1800, 1.0);
        run(sealed, 0, 50, 1800, 1.0);
        Check.lessThan(sealed.getPressurePsig() * 0.5, vented.getPressurePsig(), "vented pressure");
        Check.greaterThan(0.0, vented.getEnvironmentalReleaseFraction(), "venting releases activity");
        Check.lessThan(0.01, vented.getEnvironmentalReleaseFraction(),
                "a pool-scrubbed vent releases far less than the airborne inventory");
        Check.isFalse(vented.isFailed(), "vented containment intact");
        Check.note("after 1 h of 50 mol/s gas: sealed %.1f psig, vented %.1f psig, released %.4f%%",
                sealed.getPressurePsig(), vented.getPressurePsig(), 100 * vented.getEnvironmentalReleaseFraction());
    }

    public static void test05_noncondensableGasOverpressuresAnUnventedContainment() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        ct.initialiseInerted();
        ct.step(0, 0, 0.20, 1.0);
        var events = run(ct, 0, 500, 6 * 3600, 2.0);
        Check.isTrue(events.contains(ContainmentAtmosphere.Event.CONTAINMENT_OVERPRESSURE_FAILURE),
                "overpressure failure event, got %s", events);
        Check.isTrue(ct.getFailureMode() == ContainmentAtmosphere.FailureMode.OVERPRESSURE, "failure mode");
        Check.greaterThan(0.001, ct.getEnvironmentalReleaseFraction(), "failed containment releases unscrubbed");
        Check.lessThan(ContainmentAtmosphere.FAILURE_PRESSURE_PSIG, ct.getPressurePsig(),
                "failed containment no longer holds pressure");
        Check.note("peak %.0f psig, release %.2f%% of core inventory",
                ct.getPeakPressurePsig(), 100 * ct.getEnvironmentalReleaseFraction());
    }

    public static void test06_contaminationDecaysButReleasesAreRecorded() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        ct.step(0, 0, 0.05, 1.0);
        double initial = ct.getPlantContaminationIndex();
        Check.relative(0.05, initial, 1e-3, "plant contamination right after a release");
        run(ct, 0, 0, 2 * 86_400, 30.0);
        Check.lessThan(initial * 0.3, ct.getPlantContaminationIndex(), "contamination after two half-lives");
        Check.greaterThan(0.04, ct.getDepositedFraction(), "deposited inventory is still recorded");
    }

    public static void test07_snapshotRoundTrips() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        run(ct, 0.05, 10, 2000, 1.0);
        ct.setVentOpen(true);
        ct.setInertingCommanded(true);
        ct.step(0, 0, 0.02, 1.0);
        ct.drainEvents();
        double[] snapshot = ct.toArray();
        ContainmentAtmosphere restored = new ContainmentAtmosphere();
        restored.fromArray(snapshot);
        Check.arraysExactly(snapshot, restored.toArray(), "containment snapshot");
        Check.isTrue(restored.drainEvents().isEmpty(), "restored containment queues no events");
        Check.relative(ct.getHydrogenPercent(), restored.getHydrogenPercent(), 1e-12, "restored hydrogen");
    }

    public static void test09_sustainedReleaseBurnsAsAFlameNotRepeatedExplosions() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        // A large release, like zirconium meeting steam during a blowdown.
        var events = run(ct, 2.0, 0, 300, 0.05);
        long explosions = events.stream().filter(e -> e == ContainmentAtmosphere.Event.HYDROGEN_DEFLAGRATION
                || e == ContainmentAtmosphere.Event.HYDROGEN_DETONATION).count();
        Check.isTrue(explosions >= 1 && explosions <= 3,
                "a sustained release causes one or a few burns, not one per tick: got %d", explosions);
        Check.greaterThan(100.0, ct.getHydrogenBurnedKg(), "hydrogen consumed by the standing flame");
        Check.note("2 kg/s for 5 min: %d explosion event(s), %.0f kg burned, %.1f%% O2 left",
                explosions, ct.getHydrogenBurnedKg(), ct.getOxygenPercent());
    }

    public static void test10_aSealedContainmentDoesNotRegainOxygen() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        run(ct, 2.0, 0, 1800, 0.25);
        double oxygenAfterBurns = ct.getOxygenPercent();
        Check.lessThan(ContainmentAtmosphere.OXYGEN_LIMIT_FRACTION * 100.0, oxygenAfterBurns,
                "burns starve a sealed containment of oxygen");
        var events = run(ct, 0.05, 0, 4 * 3600, 1.0);
        Check.isFalse(ct.isFailed(), "containment holds this much hydrogen");
        Check.lessThan(oxygenAfterBurns + 0.01, ct.getOxygenPercent(), "oxygen does not come back by itself");
        Check.isFalse(events.contains(ContainmentAtmosphere.Event.HYDROGEN_DETONATION),
                "hydrogen piling up in an oxygen-starved containment does not detonate");
        Check.greaterThan(13.0, ct.getHydrogenPercent(), "hydrogen accumulates once oxygen is gone");
        Check.note("oxygen-starved at %.1f%% O2; hydrogen then stands at %.0f%% without burning",
                ct.getOxygenPercent(), ct.getHydrogenPercent());
    }

    public static void test11_aFailedContainmentBreathesInAirAndTheHydrogenDetonates() {
        ContainmentAtmosphere ct = new ContainmentAtmosphere();
        run(ct, 2.0, 0, 1800, 0.25);
        Check.lessThan(5.0, ct.getOxygenPercent(), "oxygen-starved before the failure");
        ct.failByMeltThrough();
        var events = run(ct, 0, 0, 3 * 3600, 1.0);
        Check.isTrue(events.contains(ContainmentAtmosphere.Event.HYDROGEN_DETONATION),
                "air reaching a hydrogen-filled failed containment detonates, got %s", events);
        Check.absolute(0.0, ct.getPressurePsig(), 1.0, "failed containment equalises with outside");
        Check.greaterThan(15.0, ct.getOxygenPercent(), "outside air has replaced the atmosphere");
        Check.lessThan(1.0, ct.getHydrogenPercent(), "hydrogen has gone");
        Check.note("after melt-through: %d burn(s), %.1f%% O2, %.2f%% H2, %.1f psig",
                ct.getBurnCount(), ct.getOxygenPercent(), ct.getHydrogenPercent(), ct.getPressurePsig());
    }

    public static void test08_sizeScalesTheFreeVolume() {
        ContainmentAtmosphere small = new ContainmentAtmosphere();
        small.setSizeScale(0.25);
        ContainmentAtmosphere large = new ContainmentAtmosphere();
        run(small, 0.01, 0, 600, 1.0);
        run(large, 0.01, 0, 600, 1.0);
        Check.relative(4.0 * large.getHydrogenPercent(), small.getHydrogenPercent(), 0.05,
                "the same hydrogen in a quarter of the volume");
    }
}
