package dev.bwr.mod.devtest;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.fuel.CompactCoreLayout;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.LprmReadout;
import dev.bwr.mod.rods.RodIndications;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * The per-rod indications and the LPRM string readouts of the full core display (spec item 2), as plant signals:
 * {@code rod_<n>_drift}, {@code _scram}, {@code _full_in}, {@code _full_out}, {@code _accum_trouble},
 * {@code _lattice_x}, {@code _lattice_y}, {@code _label}, and {@code lprm_<s>_<a-d>_percent / bypassed / failed /
 * downscale / upscale} with the string labels, on a real formed 185-rod core; and the pure layout math behind them
 * ({@link CoreRodMap}).
 *
 * <p>Fixture origin 77_900 / 77_900, {@code slot * 64} blocks apart in X (see {@link FcdTestSupport}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdIndicationTests {
    static final int ORIGIN = 77_900;

    private static double read(SignalProvider provider, String key) {
        return provider.read(key).orElse(Double.NaN);
    }

    // ---- pure layout math

    @GameTest(template = "empty", batch = "fcd")
    public static void rodMapNamesRodsInTheGeConvention(GameTestHelper h) {
        // A full 15 x 15 grid: columns 02..58 west to east, rows 03..59 south to north, the middle rod 30-31.
        int[] col = new int[225];
        int[] row = new int[225];
        for (int i = 0; i < 225; i++) {
            col[i] = i % 15;
            row[i] = i / 15;
        }
        CoreRodMap map = CoreRodMap.of(col, row);
        h.assertTrue(map.cols() == 15 && map.rows() == 15 && map.rodCount() == 225, "grid size");
        h.assertTrue(map.label(7 * 15 + 7).equals("30-31"), "middle rod is " + map.label(7 * 15 + 7));
        h.assertTrue(map.label(0).equals("02-59") && map.label(14).equals("58-59"), "north row reads 59: " + map.label(0));
        h.assertTrue(map.label(14 * 15).equals("02-03"), "south west rod: " + map.label(14 * 15));
        h.assertTrue(map.label(4 * 15 + 4).equals("18-43"), "label of column 4 row 4: " + map.label(4 * 15 + 4));
        // Rod 18-31 (a rod of the photograph) is the 5th column from the west and the 8th row from the south.
        h.assertTrue(map.label(6 * 15 + 4).equals("18-35") && map.label(7 * 15 + 4).equals("18-31"), "18-31 at column 4, row 7");
        // A core narrower than 15 is centred in the grid; a wider one starts at 02.
        int[] c9 = new int[81];
        int[] r9 = new int[81];
        for (int i = 0; i < 81; i++) {
            c9[i] = i % 9;
            r9[i] = i / 9;
        }
        CoreRodMap narrow = CoreRodMap.of(c9, r9);
        h.assertTrue(narrow.label(4 * 9 + 4).equals("30-31"), "a 9 x 9 core is centred: " + narrow.label(4 * 9 + 4));
        int[] c21 = new int[21];
        int[] r21 = new int[21];
        for (int i = 0; i < 21; i++) {
            c21[i] = i;
        }
        CoreRodMap wide = CoreRodMap.of(c21, r21);
        h.assertTrue(wide.label(0).startsWith("02-") && wide.label(20).startsWith("82-"), "a 21 wide core runs on: " + wide.label(20));
        // Bad input is refused, not drawn.
        boolean refused = false;
        try {
            CoreRodMap.of(new int[] {0, 0}, new int[] {0, 0});
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        h.assertTrue(refused, "two rods in one cell");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void lprmStringsStandBetweenFourRodsOnTheGeGrid(GameTestHelper h) {
        CoreRodMap map = map185();
        h.assertTrue(map.rodCount() == 185, "185 rods");
        var strings = map.lprmStrings();
        h.assertTrue(strings.size() == 39, "the 185-rod core has 39 strings, got " + strings.size());
        Set<String> seen = new HashSet<>();
        int previousY = Integer.MAX_VALUE;
        int previousX = -1;
        for (CoreRodMap.Lprm string : strings) {
            h.assertTrue(string.x() % 8 == 0 && string.y() % 8 == 1, "LPRM on the 8 grid: " + string.label());
            h.assertTrue(seen.add(string.label()), "duplicate string " + string.label());
            for (int dc = 0; dc <= 1; dc++) {
                for (int dr = 0; dr <= 1; dr++) {
                    h.assertTrue(map.rodAt(string.leftCol() + dc, string.topRow() + dr) >= 0, "a rod is missing at " + string.label());
                }
            }
            h.assertTrue(string.y() < previousY || string.y() == previousY && string.x() > previousX,
                    "strings are numbered top row first, west to east");
            previousY = string.y();
            previousX = string.x();
        }
        h.assertTrue(seen.contains("16-33") && seen.contains("40-17") && seen.contains("24-41") && !seen.contains("08-49"),
                "strings of the photographed board that this rounded core has (08-49 sits outside its octagon): " + seen);
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void smallestCoreMapsWithoutStrings(GameTestHelper h) {
        CompactCoreLayout layout = new CompactCoreLayout(5, 5);
        int[] col = new int[layout.drives().size()];
        int[] row = new int[col.length];
        for (int i = 0; i < col.length; i++) {
            col[i] = layout.drives().get(i).x();
            row[i] = layout.drives().get(i).z();
        }
        CoreRodMap map = CoreRodMap.of(col, row);
        h.assertTrue(map.rodCount() == col.length && map.rodCount() >= 1, "smallest core has rods");
        for (int i = 0; i < map.rodCount(); i++) {
            h.assertTrue(map.rodAt(map.col(i), map.row(i)) == i, "rod cell lookup");
            h.assertTrue(map.label(i).matches("\\d\\d-\\d\\d"), "label " + map.label(i));
        }
        h.succeed();
    }

    private static CoreRodMap map185() {
        CompactCoreLayout layout = new CompactCoreLayout(15, 15);
        int[] col = new int[layout.drives().size()];
        int[] row = new int[col.length];
        for (int i = 0; i < col.length; i++) {
            col[i] = layout.drives().get(i).x();
            row[i] = layout.drives().get(i).z();
        }
        return CoreRodMap.of(col, row);
    }

    // ---- end notches, on a core stepped by hand (a real rod takes 50 s to cross the stroke)

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 100)
    public static void fullOutAndFullInFollowTheEndNotches(GameTestHelper h) {
        CoreConfig config = new CoreConfig();
        config.controlRodCount = 4;
        ReactorCore core = new ReactorCore(config);
        core.initialiseCold();
        core.setCrdPowered(true);
        core.setCrdWaterSupplied(true);
        for (int r = 0; r < 4; r++) {
            core.setRodNormalMotionAvailable(r, true);
        }
        h.assertTrue(RodIndications.fullIn(core, 0) && !RodIndications.fullOut(core, 0), "a cold core starts fully in");
        core.setRodNotchDemand(0, 24);
        core.setRodNotchDemand(1, 5);
        for (int i = 0; i < 1200; i++) {
            core.step();
        }
        h.assertTrue(RodIndications.fullOut(core, 0) && !RodIndications.fullIn(core, 0), "rod 1 withdrawn to notch 48");
        h.assertTrue(!RodIndications.fullIn(core, 1) && !RodIndications.fullOut(core, 1), "rod 2 is part way");
        h.assertTrue(RodIndications.fullIn(core, 2), "rod 3 never moved");
        h.assertTrue(!RodIndications.accumulatorTrouble(core, 0, RodIndications.DEFAULT_ACCUMULATOR_FRACTION),
                "a full accumulator has no trouble");
        h.assertTrue(RodIndications.accumulatorTrouble(core, 0, 1.01), "the threshold is the caller's");
        h.succeed();
    }

    // ---- signals on a real formed core

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 100)
    public static void rodAndStringCoordinatesFromARealCore(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 0, 15, 15, run -> {
            SignalProvider rp = SignalProviders.of(run.be);
            CompactCoreLayout layout = new CompactCoreLayout(15, 15);
            h.assertTrue(run.be.core().getControlRodCount() == 185, "reference core");
            Set<String> labels = new HashSet<>();
            for (int r = 0; r < 185; r++) {
                var drive = layout.drives().get(r);
                int x = 2 + 4 * drive.x();
                int y = 3 + 4 * (14 - drive.z());
                String label = String.format("%02d-%02d", x, y);
                h.assertTrue(rp.readText("rod_" + (r + 1) + "_label").orElse("").equals(label),
                        "rod " + (r + 1) + " label " + rp.readText("rod_" + (r + 1) + "_label").orElse("?") + " expected " + label);
                h.assertTrue(read(rp, "rod_" + (r + 1) + "_lattice_x") == x && read(rp, "rod_" + (r + 1) + "_lattice_y") == y,
                        "rod " + (r + 1) + " lattice");
                h.assertTrue(labels.add(label), "labels are unique");
            }
            h.assertTrue(!rp.readText("rod_186_label").isPresent() && !rp.read("rod_186_lattice_x").isPresent(), "no rod 186");
            h.assertTrue(read(rp, "lprm_count") == 39, "39 strings");
            h.assertTrue(rp.readText("lprm_1_label").isPresent() && rp.readText("lprm_39_label").isPresent()
                    && !rp.readText("lprm_40_label").isPresent(), "string labels");
            h.assertTrue(rp.signals().stream().anyMatch(s -> s.key().equals("rod_185_accum_trouble"))
                    && rp.signals().stream().anyMatch(s -> s.key().equals("lprm_39_d_upscale")), "specs are listed");
            // A string number outside the core, a bad level and a bad field read no signal.
            h.assertTrue(!rp.read("lprm_0_a_percent").isPresent() && !rp.read("lprm_40_a_percent").isPresent()
                    && !rp.read("lprm_1_e_percent").isPresent() && !rp.read("lprm_1_a_bogus").isPresent(), "bad lprm keys");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 100)
    public static void lprmReadoutsAreTheChambersOfTheCore(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 1, 15, 15, run -> {
            SignalProvider rp = SignalProviders.of(run.be);
            ReactorCore core = run.be.core();
            // Level a of string 1 is chamber 0 (APRM channel 1, input 1), b is chamber 1 (channel 2, input 1).
            core.getAveragePowerRangeMonitor(0).setLprmFailed(0, true);
            core.getAveragePowerRangeMonitor(1).setLprmBypassed(0, true);
            h.assertTrue(read(rp, "lprm_1_a_failed") == 1.0 && read(rp, "lprm_1_a_percent") == 0.0
                    && read(rp, "lprm_1_a_downscale") == 0.0 && read(rp, "lprm_1_a_upscale") == 0.0,
                    "a failed chamber reads zero and raises neither alarm");
            h.assertTrue(read(rp, "lprm_1_b_bypassed") == 1.0 && read(rp, "lprm_1_b_failed") == 0.0
                    && read(rp, "lprm_1_b_downscale") == 0.0, "a bypassed chamber is not in alarm");
            h.assertTrue(read(rp, "lprm_1_c_bypassed") == 0.0 && read(rp, "lprm_1_c_failed") == 0.0, "other chambers are in service");
            for (int level = 0; level < 4; level++) {
                char name = (char) ('a' + level);
                double percent = LprmReadout.percent(core, 5, level);
                h.assertTrue(read(rp, "lprm_6_" + name + "_percent") == percent, "string 6 level " + name + " follows the chamber");
                h.assertTrue(read(rp, "lprm_6_" + name + "_downscale") == (percent < LprmReadout.DEFAULT_DOWNSCALE_PERCENT ? 1.0 : 0.0),
                        "downscale follows the default threshold");
                h.assertTrue(read(rp, "lprm_6_" + name + "_upscale") == (percent > LprmReadout.DEFAULT_UPSCALE_PERCENT ? 1.0 : 0.0),
                        "upscale follows the default threshold");
            }
            // A hot gain pushes the chambers past the upscale threshold.
            core.getAveragePowerRangeMonitor(2).setGammaBackgroundPercent(150.0);
            h.assertTrue(read(rp, "lprm_1_c_upscale") == 1.0 && read(rp, "lprm_1_c_downscale") == 0.0, "upscale alarm");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 700)
    public static void rodFlagsFollowScramAccumulatorAndPosition(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 2, 9, 9, run -> {
            SignalProvider rp = SignalProviders.of(run.be);
            int rods = run.be.core().getControlRodCount();
            run.be.rods().setBaseAccumulatorRechargePerSecond(0.0);
            for (int r = 1; r <= rods; r++) {
                h.assertTrue(read(rp, "rod_" + r + "_full_in") == 1.0 && read(rp, "rod_" + r + "_full_out") == 0.0
                        && read(rp, "rod_" + r + "_scram") == 0.0 && read(rp, "rod_" + r + "_drift") == 0.0
                        && read(rp, "rod_" + r + "_accum_trouble") == 0.0, "rod " + r + " starts fully in with every indication off");
            }
            h.assertTrue(rp.command("rod_3_position", 8), "withdraw rod 3 to 08");
            run.then(220, () -> {
                h.assertTrue(read(rp, "rod_3_position") == 8.0, "rod 3 at 08, is at " + read(rp, "rod_3_position"));
                h.assertTrue(read(rp, "rod_3_full_in") == 0.0 && read(rp, "rod_3_drift") == 0.0 && read(rp, "rod_3_scram") == 0.0,
                        "a rod that reached its demand is not full in, drifting or scrammed");
                h.assertTrue(rp.command("scram", 1), "scram");
                h.assertTrue(read(rp, "rod_3_scram") == 1.0, "the scram lamp lights for a rod that is not fully in");
                h.assertTrue(read(rp, "rod_1_scram") == 0.0 && read(rp, "rod_2_scram") == 0.0, "rods that are fully in show no scram");
                h.assertTrue(read(rp, "rod_3_drift") == 0.0, "a scram is not drift");
            });
            run.then(120, () -> {
                h.assertTrue(read(rp, "rod_3_full_in") == 1.0 && read(rp, "rod_3_scram") == 0.0, "scrammed in");
                h.assertTrue(read(rp, "rod_3_accum_trouble") == 1.0, "the spent accumulator shows trouble (charge "
                        + read(rp, "rod_3_accumulator") + ")");
                h.assertTrue(read(rp, "rod_4_accum_trouble") == 0.0, "an untouched accumulator is full");
            });
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 300)
    public static void driftIsARodAwayFromItsDemandThatNothingDrives(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 3, 9, 9, run -> {
            SignalProvider rp = SignalProviders.of(run.be);
            FcdTestSupport.starveDrives(run.level, run.be);
            run.then(5, () -> {
                h.assertTrue(rp.command("rod_2_position", 4), "demand rod 2 out two notches");
                h.assertTrue(read(rp, "rod_2_drift") == 1.0, "a rod that cannot move to its demand drifts");
                h.assertTrue(read(rp, "rod_1_drift") == 0.0 && read(rp, "rod_3_drift") == 0.0, "other rods do not");
                FcdTestSupport.supplyDrives(run.level, run.be);
            });
            run.then(10, () -> {
                h.assertTrue(read(rp, "rod_2_position") < 4.0, "the rod is travelling, not there yet");
                h.assertTrue(read(rp, "rod_2_drift") == 0.0, "travelling toward its demand under power is not drift");
            });
            run.then(150, () -> h.assertTrue(read(rp, "rod_2_position") == 4.0 && read(rp, "rod_2_drift") == 0.0, "arrived"));
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 100)
    public static void unformedReactorReadsNoIndications(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 4, 9, 9, run -> {
            SignalProvider rp = SignalProviders.of(run.be);
            run.level.removeBlock(run.be.structure().crdPositions().get(0), false);
            run.then(5, () -> {
                FcdRodTests.revalidate(run.be);
                for (String key : new String[] {"rod_1_drift", "rod_1_scram", "rod_1_full_in", "rod_1_full_out", "rod_1_accum_trouble",
                        "rod_1_lattice_x", "rod_1_lattice_y", "lprm_count"}) {
                    h.assertTrue(!rp.read(key).isPresent(), key + " must read no signal when the reactor is not formed");
                }
                h.assertTrue(!rp.readText("rod_1_label").isPresent(), "no label either");
            });
        });
    }
}
