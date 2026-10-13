package dev.bwr.core.accident;

import dev.bwr.core.Check;

import java.util.Arrays;

/** Steam inventory, thermal sinks and legacy containment snapshots. */
public final class ContainmentSteamTest {
    private ContainmentSteamTest() {}

    public static void test01_steamRaisesPressureAndCondensesOnStructures() {
        var room = new ContainmentAtmosphere();
        room.setFailurePressurePsig(1000);
        for (int i = 0; i < 400; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(200, 2800, 0, 0, 0, 0));
            room.step(0, 0, 0, .05);
        }
        Check.greaterThan(0, room.getSteamKg(), "steam remains after the wall absorbs its initial load");
        Check.greaterThan(0, room.getSteamPartialPressurePsia(), "steam partial pressure");
        Check.greaterThan(ContainmentAtmosphere.GAS_TEMPERATURE_K, room.getWallTemperatureK(),
                "condensing steam warms the structure");
        Check.greaterThan(0, room.getPressurePsig(), "steam pressurizes containment");
        Check.note("steam %.0f kg, gas %.1f C, wall %.1f C, pressure %.1f psig",
                room.getSteamKg(), room.getGasTemperatureC(), room.getWallTemperatureK() - 273.15,
                room.getPressurePsig());
    }

    public static void test02_hotPoolRaisesTheWetwellSteamFloor() {
        var cold = new ContainmentAtmosphere();
        var hot = new ContainmentAtmosphere();
        for (int i = 0; i < 800; i++) {
            cold.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 250, 303.15, 0, 0));
            hot.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 250, 373.15, 0, 0));
            cold.step(0, 0, 0, .05);
            hot.step(0, 0, 0, .05);
        }
        Check.greaterThan(cold.getSteamKg(), hot.getSteamKg(), "hot pool leaves more steam in wetwell");
        Check.greaterThan(cold.getPressurePsig(), hot.getPressurePsig(), "hot pool raises pressure");
    }

    public static void test03_sprayRemovesSteamAndAirborneActivity() {
        var unsprayed = new ContainmentAtmosphere();
        var sprayed = new ContainmentAtmosphere();
        for (int i = 0; i < 900; i++) {
            var input = new ContainmentAtmosphere.SteamInputs(450, 2800, 0, 0, 0, 0);
            unsprayed.setSteamInputs(input);
            sprayed.setSteamInputs(input);
            unsprayed.step(0, 0, 0, .05);
            sprayed.step(0, 0, 0, .05);
        }
        unsprayed.step(0, 0, .1, .05);
        sprayed.step(0, 0, .1, .05);
        for (int i = 0; i < 600; i++) {
            sprayed.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 0, 0, 500, 293.15));
            sprayed.step(0, 0, 0, .05);
            unsprayed.step(0, 0, 0, .05);
        }
        Check.lessThan(unsprayed.getSteamKg(), sprayed.getSteamKg(), "spray condenses steam");
        Check.lessThan(unsprayed.getAirborneFraction(), sprayed.getAirborneFraction(),
                "spray washes airborne activity onto structures");
        Check.lessThan(unsprayed.getPressurePsig(), sprayed.getPressurePsig(), "spray lowers pressure");
    }

    public static void test04_steamInertingThenSprayRevealsHydrogen() {
        var room = new ContainmentAtmosphere();
        room.setFailurePressurePsig(1000);
        for (int i = 0; i < 10000 && room.getSteamPercent() < 70.0; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(1200, 2800, 0, 0, 0, 0));
            room.step(0, 0, 0, .05);
        }
        Check.isTrue(room.isSteamInerted(), "steam-rich mixture is inert");
        // Add hydrogen without ignition while the high steam fraction suppresses combustion.
        for (int i = 0; i < 400; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(1200, 2800, 0, 0, 0, 0));
            room.step(5.0, 0, 0, .05);
        }
        Check.exactly(0, room.getBurnCount(), "stored hydrogen cannot ignite while steam inerted");
        Check.note("before spray: steam %.1f%% H2 %.1f%% O2 %.1f%% wall %.1f C",
                room.getSteamPercent(), room.getHydrogenPercent(), room.getOxygenPercent(),
                room.getWallTemperatureK() - 273.15);
        for (int i = 0; i < 20000 && room.getBurnCount() == 0; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 0, 0, 1000, 283.15));
            room.step(0, 0, 0, .05);
        }
        Check.note("after spray: steam %.1f%% H2 %.1f%% O2 %.1f%% burns %d",
                room.getSteamPercent(), room.getHydrogenPercent(), room.getOxygenPercent(), room.getBurnCount());
        Check.greaterThan(0, room.getBurnCount(), "spray exposes a combustible mixture");
        Check.note("steam inerting reversed after spray; burn peak %.1f psig", room.getLastBurnPeakPressurePsig());
    }

    public static void test05_v2SnapshotLoadsAtLegacyTemperatureWithNoSteam() {
        var original = new ContainmentAtmosphere();
        original.setFailurePressurePsig(92);
        original.setLeakArea(.2);
        double[] v2 = Arrays.copyOf(original.toArray(), ContainmentAtmosphere.V2_SNAPSHOT_LENGTH);
        v2[0] = 2;
        var restored = new ContainmentAtmosphere();
        restored.fromArray(v2);
        Check.absolute(0, restored.getSteamKg(), 0, "legacy has no steam");
        Check.absolute(ContainmentAtmosphere.GAS_TEMPERATURE_K, restored.getGasTemperatureK(), 0,
                "legacy temperature");
        Check.absolute(92, restored.getFailurePressurePsig(), 0, "v2 failure rating");
        Check.absolute(.2, restored.getLeakArea(), 0, "v2 leak area");
        Check.absolute(original.getPressurePsig(), restored.getPressurePsig(), 1e-9, "v2 pressure");
    }

    public static void test06_v3SnapshotRoundTripsHotSteam() {
        var original = new ContainmentAtmosphere();
        original.setFailurePressurePsig(1000);
        for (int i = 0; i < 100; i++) {
            original.setSteamInputs(new ContainmentAtmosphere.SteamInputs(400, 2800, 0, 0, 0, 0));
            original.step(0, 0, 0, .05);
        }
        var restored = new ContainmentAtmosphere();
        restored.fromArray(original.toArray());
        Check.arraysExactly(original.toArray(), restored.toArray(), "v3 steam and thermal state");
    }
}
