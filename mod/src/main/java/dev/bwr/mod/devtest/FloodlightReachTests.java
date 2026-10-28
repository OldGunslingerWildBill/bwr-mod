package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.FacingBeamLampBlock;
import dev.bwr.mod.floodlight.HpsFlatPanelBlock;
import dev.bwr.mod.floodlight.XenonBeam;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * How far the high-bay and the HPS flat panel light a big, flat floor, and what a re-plan costs. The fixture is a 141 x 141
 * stone floor (a 100-chunk site of its own at 700,000) with open air above it; the block light is read back from the light
 * engine once it has stopped changing. The beam of each lamp is bright (level 15, the HPS warm-up level) in its main cone and
 * four fifths of that outside it (the spill zone), out to 48 blocks (high-bay) or 40 (HPS panel).
 *
 * <p>Each test is a batch of its own, so the tests (which share the floor) run one after another. Every table of floor light a
 * test reads is also logged ({@code FLOODLIGHT REACH}), as is the cost of a plan ({@code FLOODLIGHT PLAN...}). Namespace
 * {@code bwrfloodlight}.
 */
@GameTestHolder("bwrfloodlight")
@PrefixGameTestTemplate(false)
public final class FloodlightReachTests {
    private FloodlightReachTests() {
    }

    private static final int X0 = 700_000;
    private static final int Z0 = 700_000;
    private static final int FLOOR = 100;
    private static final int HALF = 70;
    /** The floor's cells are read at this height: the air cell on top of the stone. */
    private static final int ABOVE_FLOOR = 1;

    private static BlockPos at(int dx, int dy, int dz) {
        return new BlockPos(X0 + dx, FLOOR + dy, Z0 + dz);
    }

    // ---- fixture

    private static void forceArea(ServerLevel level, boolean on) {
        for (int cx = (X0 - HALF - 2) >> 4; cx <= (X0 + HALF + 2) >> 4; cx++) {
            for (int cz = (Z0 - HALF - 2) >> 4; cz <= (Z0 + HALF + 2) >> 4; cz++) {
                FixtureChunks.force(level, X0, cx, cz, on);
            }
        }
    }

    private static boolean areaLoaded(ServerLevel level) {
        for (int cx = (X0 - HALF - 2) >> 4; cx <= (X0 + HALF + 2) >> 4; cx++) {
            for (int cz = (Z0 - HALF - 2) >> 4; cz <= (Z0 + HALF + 2) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void waitFor(GameTestHelper helper, BooleanSupplier ready, int maxTicks, String what, Runnable then) {
        if (ready.getAsBoolean()) {
            then.run();
            return;
        }
        helper.runAfterDelay(5, () -> {
            if (ready.getAsBoolean()) {
                then.run();
            } else if (maxTicks <= 0) {
                helper.assertTrue(false, "timed out waiting for " + what);
            } else {
                waitFor(helper, ready, maxTicks - 5, what, then);
            }
        });
    }

    private static int light(ServerLevel level, int dx, int dz) {
        return level.getBrightness(LightLayer.BLOCK, at(dx, ABOVE_FLOOR, dz));
    }

    /** Checksum of the block light of the whole floor: it stops changing when the light engine is done. */
    private static long floorSum(ServerLevel level) {
        long sum = 0;
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                sum = sum * 31 + light(level, dx, dz);
            }
        }
        return sum;
    }

    /** Waits until the light engine has nothing queued and the floor's light has read the same three times, 10 ticks apart. */
    private static void settle(GameTestHelper helper, ServerLevel level, Runnable then) {
        settle(helper, level, 0, Long.MIN_VALUE, 0, then);
    }

    private static void settle(GameTestHelper helper, ServerLevel level, int waited, long last, int same, Runnable then) {
        helper.runAfterDelay(10, () -> {
            long now = floorSum(level);
            int stable = now == last ? same + 1 : 0;
            if (waited >= 30 && stable >= 3 && !level.getChunkSource().getLightEngine().hasLightWork()) {
                then.run();
            } else if (waited > 3000) {
                helper.assertTrue(false, "the light engine never settled");
            } else {
                settle(helper, level, waited + 10, now, stable, then);
            }
        });
    }

    /** Runs {@code body} on a forced, loaded, built floor; the body calls {@code done} when its last check has passed. */
    private static void onFloor(GameTestHelper helper, Consumer<Runnable> body) {
        ServerLevel level = helper.getLevel();
        forceArea(level, true);
        waitFor(helper, () -> areaLoaded(level), 1200, "the site's chunks to load", () -> {
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    level.setBlock(at(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                    // Whatever a test that failed halfway left standing over the floor (its lamp, its lights) goes.
                    for (int dy = 1; dy <= 34; dy++) {
                        if (!level.getBlockState(at(dx, dy, dz)).isAir()) {
                            level.setBlock(at(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            settle(helper, level, () -> body.accept(() -> settle(helper, level, () -> {
                forceArea(level, false);
                helper.succeed();
            })));
        });
    }

    /** A lamp, lit by a redstone block behind it (above a ceiling lamp, west of a wall one): never in front of its beam. */
    private static final class Lamp {
        final BeamLampBlockEntity be;
        final BlockPos pos;
        final BlockPos power;
        final ServerLevel level;

        Lamp(ServerLevel level, Block block, BlockPos pos, Direction facing) {
            this.level = level;
            this.pos = pos;
            this.power = facing == Direction.DOWN ? pos.above() : pos.relative(facing.getOpposite());
            level.setBlock(pos, block.defaultBlockState().setValue(FacingBeamLampBlock.FACING, facing), 3);
            this.be = (BeamLampBlockEntity) level.getBlockEntity(pos);
        }

        void on() {
            level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
            be.reconcile();
        }

        /** Runs the HPS panel's three warm-up ticks (6.7 s each in the game). */
        void warmUp() {
            for (int k = 0; k < 3; k++) {
                tick();
            }
        }

        /** One scheduled tick of an HPS panel: the next warm-up step. */
        void tick() {
            ((HpsFlatPanelBlock) BwrBlocks.HPS_FLAT_PANEL.get()).tick(level.getBlockState(pos), level, pos, RandomSource.create(1));
        }

        void off() {
            level.setBlock(power, Blocks.AIR.defaultBlockState(), 2);
            be.reconcile();
        }

        /** Takes the lamp and its power block away (after {@link #off}). */
        void remove() {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(power, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** Light of every floor cell, min and mean, by 4-block spans of horizontal distance from (0, 0). */
    private static String table(ServerLevel level, int[] marks) {
        StringBuilder sb = new StringBuilder();
        for (int r : marks) {
            int lo = 99;
            long total = 0;
            int n = 0;
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d >= r && d < r + 4) {
                        int v = light(level, dx, dz);
                        lo = Math.min(lo, v);
                        total += v;
                        n++;
                    }
                }
            }
            sb.append(String.format("%d:%s/%s ", r, n == 0 ? "-" : String.valueOf(lo), n == 0 ? "-" : String.format("%.1f", total / (double) n)));
        }
        return sb.toString();
    }

    private static final int[] MARKS = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64};

    /** The lowest block light of the floor cells with {@code from <= distance < to} from (0, 0) (99 when there are none). */
    private static int lowest(ServerLevel level, double from, double to) {
        int lo = 99;
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double d = Math.hypot(dx, dz);
                if (d >= from && d < to) {
                    lo = Math.min(lo, light(level, dx, dz));
                }
            }
        }
        return lo;
    }

    private static double mean(ServerLevel level, double from, double to) {
        long total = 0;
        int n = 0;
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double d = Math.hypot(dx, dz);
                if (d >= from && d < to) {
                    total += light(level, dx, dz);
                    n++;
                }
            }
        }
        return n == 0 ? 0 : total / (double) n;
    }

    /** No light block anywhere over the floor (the footprint, from the floor up to {@code top} above it). */
    private static void checkNoLights(GameTestHelper helper, ServerLevel level, int top) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = 1; dy <= top; dy++) {
                    helper.assertTrue(!level.getBlockState(at(dx, dy, dz)).is(Blocks.LIGHT), "a light was left at " + at(dx, dy, dz));
                }
            }
        }
    }

    /** The lights of a lamp over the floor as (horizontal distance, level) pairs. */
    private static List<double[]> lights(ServerLevel level, BeamLampBlockEntity be) {
        List<double[]> out = new ArrayList<>();
        for (long key : be.placed().keySet()) {
            BlockPos p = BlockPos.of(key);
            out.add(new double[] {Math.hypot(p.getX() - X0, p.getZ() - Z0), level.getBlockState(p).getValue(LightBlock.LEVEL)});
        }
        return out;
    }

    // ---- the high-bay

    /**
     * A high-bay 12 blocks over a flat floor lights it out to the far end of its 48-block reach: no floor cell within 40 blocks
     * is below light 4, the main beam's floor (within 10 blocks) is at 10 or more, and the spill zone is level 12 against
     * the beam's 15. The same lamp 30 up (it lit nothing at all before: 24 blocks of range) lights the floor within 30. Off,
     * every light is gone.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flreach1")
    public static void aHighBayLightsALargeFloorOutToItsReach(GameTestHelper helper) {
        onFloor(helper, done -> {
            ServerLevel level = helper.getLevel();
            Lamp lamp = new Lamp(level, BwrBlocks.XENON_HIGH_BAY.get(), at(0, 12, 0), Direction.DOWN);
            lamp.on();
            int count = lamp.be.placed().size();
            List<double[]> placed = lights(level, lamp.be);
            helper.assertTrue(count <= XenonBeam.MAX_LIGHTS, "the high-bay keeps to the cap: " + count);
            helper.assertTrue(count >= 150, "the high-bay lights a big floor with a good many lights: " + count);
            settle(helper, level, () -> {
                LogUtils.getLogger().info("FLOODLIGHT REACH high-bay h=12 lights={} floor light (min/mean over 4 blocks from distance): {}",
                        count, table(level, MARKS));
                helper.assertTrue(lowest(level, 0, 40) >= 4, "no floor cell within 40 blocks of a high-bay 12 up is dark: lowest "
                        + lowest(level, 0, 40));
                helper.assertTrue(mean(level, 20, 40) >= 8.0, "the far floor (20 to 40 blocks out) is usefully lit: mean " + mean(level, 20, 40));
                helper.assertTrue(lowest(level, 0, 10) >= 10, "the floor under the main beam is bright: lowest " + lowest(level, 0, 10));
                // No hard dark edge where the main beam ends (the floor radius of its 55-degree edge is 17 blocks).
                helper.assertTrue(lowest(level, 17, 27) >= 5, "the floor just outside the main beam is not dark: lowest " + lowest(level, 17, 27));

                int beam = 0;
                int spill = 0;
                for (double[] l : placed) {
                    if (l[0] <= 14) {
                        helper.assertTrue(l[1] == 15, "a light inside the main beam is level 15: " + l[1] + " at " + l[0]);
                        beam++;
                    } else if (l[0] >= 20) {
                        helper.assertTrue(l[1] == 12, "a light in the spill zone is level 12 (four fifths of 15): " + l[1] + " at " + l[0]);
                        spill++;
                    }
                }
                helper.assertTrue(beam >= 10 && spill >= 100, "the beam and its spill both hold lights: " + beam + " and " + spill);

                Set<Long> cells = new HashSet<>(lamp.be.placed().keySet());
                lamp.off();
                for (long key : cells) {
                    helper.assertTrue(!level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a light is still there after the lamp went off: " + BlockPos.of(key));
                }
                helper.assertTrue(lamp.be.placed().isEmpty(), "off, the lamp keeps no list");
                checkNoLights(helper, level, 14);
                lamp.remove();

                // The same lamp, 30 up: the floor was dark all over before (24 blocks of range).
                Lamp high = new Lamp(level, BwrBlocks.XENON_HIGH_BAY.get(), at(0, 30, 0), Direction.DOWN);
                high.on();
                int highCount = high.be.placed().size();
                helper.assertTrue(highCount > 0 && highCount <= XenonBeam.MAX_LIGHTS, "a high-bay 30 up lights the floor: " + highCount);
                settle(helper, level, () -> {
                    LogUtils.getLogger().info("FLOODLIGHT REACH high-bay h=30 lights={} floor light (min/mean over 4 blocks from distance): {}",
                            highCount, table(level, MARKS));
                    helper.assertTrue(lowest(level, 0, 30) >= 7, "no floor cell within 30 blocks of a high-bay 30 up is dark: lowest " + lowest(level, 0, 30));
                    high.off();
                    checkNoLights(helper, level, 32);
                    high.remove();
                    done.run();
                });
            });
        });
    }

    // ---- the HPS panel

    /**
     * An HPS panel on the ceiling 12 up lights the floor out past 30 blocks, at every warm-up step (4, 8, 12, 15 on the main
     * beam, the spill zone at four fifths of that), without moving a light; a panel on a wall, aimed out over the floor,
     * lights it along its axis out to 40 blocks.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flreach2")
    public static void anHpsPanelLightsALargeFloorFromTheCeilingAndFromAWall(GameTestHelper helper) {
        onFloor(helper, done -> {
            ServerLevel level = helper.getLevel();
            Lamp lamp = new Lamp(level, BwrBlocks.HPS_FLAT_PANEL.get(), at(0, 12, 0), Direction.DOWN);
            lamp.on();
            Map<Long, Byte> cells = new HashMap<>(lamp.be.placed());
            helper.assertTrue(cells.size() >= 150 && cells.size() <= XenonBeam.MAX_LIGHTS, "the panel lights a big floor within the cap: " + cells.size());
            int[] main = {4, 8, 12, 15};
            int[] spill = {3, 6, 10, 12};
            for (int stage = 0; stage < 4; stage++) {
                helper.assertTrue(lamp.be.placed().equals(cells), "the warm-up re-levels the same cells (stage " + stage + ")");
                int beam = 0;
                int outer = 0;
                for (double[] l : lights(level, lamp.be)) {
                    if (l[0] <= 20) {
                        helper.assertTrue(l[1] == main[stage], "a light inside the main beam is at " + main[stage] + " at stage " + stage + ": " + l[1]);
                        beam++;
                    } else if (l[0] >= 30) {
                        helper.assertTrue(l[1] == spill[stage], "a spill light is at " + spill[stage] + " at stage " + stage + ": " + l[1]);
                        outer++;
                    }
                }
                helper.assertTrue(beam >= 10 && outer >= 10, "beam and spill both hold lights at stage " + stage + ": " + beam + " and " + outer);
                lamp.tick();
            }
            settle(helper, level, () -> {
                LogUtils.getLogger().info("FLOODLIGHT REACH hps-panel h=12 lights={} floor light (min/mean over 4 blocks from distance): {}",
                        cells.size(), table(level, MARKS));
                helper.assertTrue(lowest(level, 0, 32) >= 4, "no floor cell within 32 blocks of an HPS panel 12 up is dark: lowest " + lowest(level, 0, 32));
                helper.assertTrue(lowest(level, 0, 12) >= 11, "the floor under the main beam is bright: lowest " + lowest(level, 0, 12));
                lamp.off();
                checkNoLights(helper, level, 14);
                lamp.remove();

                // On a wall, 12 up, facing east: the floor along the axis.
                Lamp wall = new Lamp(level, BwrBlocks.HPS_FLAT_PANEL.get(), at(0, 12, 0), Direction.EAST);
                wall.on();
                wall.warmUp();
                int wallCount = wall.be.placed().size();
                helper.assertTrue(wallCount >= 100 && wallCount <= XenonBeam.MAX_LIGHTS, "the wall panel lights the floor within the cap: " + wallCount);
                settle(helper, level, () -> {
                    StringBuilder sb = new StringBuilder();
                    for (int d : new int[] {1, 2, 4, 6, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48}) {
                        sb.append(d).append(':').append(axis(level, d)).append(' ');
                    }
                    LogUtils.getLogger().info("FLOODLIGHT REACH hps-panel-on-wall h=12 lights={} floor light on the axis (min over 5 cells across): {}", wallCount, sb);
                    for (int d = 4; d <= 40; d += 4) {
                        helper.assertTrue(axis(level, d) >= 5, "the floor " + d + " blocks out from a wall panel is lit: " + axis(level, d));
                    }
                    wall.off();
                    checkNoLights(helper, level, 14);
                    wall.remove();

                    // On the ceiling 30 up the floor was dark all over before.
                    Lamp high = new Lamp(level, BwrBlocks.HPS_FLAT_PANEL.get(), at(0, 30, 0), Direction.DOWN);
                    high.on();
                    high.warmUp();
                    int highCount = high.be.placed().size();
                    helper.assertTrue(highCount > 0 && highCount <= XenonBeam.MAX_LIGHTS, "an HPS panel 30 up lights the floor: " + highCount);
                    settle(helper, level, () -> {
                        LogUtils.getLogger().info("FLOODLIGHT REACH hps-panel h=30 lights={} floor light (min/mean over 4 blocks from distance): {}",
                                highCount, table(level, MARKS));
                        helper.assertTrue(lowest(level, 0, 24) >= 8, "no floor cell within 24 blocks of an HPS panel 30 up is dark: lowest " + lowest(level, 0, 24));
                        high.off();
                        checkNoLights(helper, level, 32);
                        high.remove();
                        done.run();
                    });
                });
            });
        });
    }

    /** The lowest light of the five floor cells across the axis, {@code d} blocks east of the lamp. */
    private static int axis(ServerLevel level, int d) {
        int lo = 99;
        for (int dz = -2; dz <= 2; dz++) {
            lo = Math.min(lo, light(level, d, dz));
        }
        return lo;
    }

    // ---- saves

    /**
     * A reloaded high-bay re-plans to the same cells at the same levels (the spill levels are not saved; the plan gives them
     * back), and a lamp from an older save, whose every light was level 15, is brought to the spill levels without a light
     * being added or taken away.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flreach3")
    public static void aReloadedHighBayKeepsItsSpillLevelsAndAnOldSaveIsReLevelled(GameTestHelper helper) {
        onFloor(helper, done -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = at(0, 12, 0);
            Lamp lamp = new Lamp(level, BwrBlocks.XENON_HIGH_BAY.get(), pos, Direction.DOWN);
            lamp.on();
            Map<Long, Byte> placed = new HashMap<>(lamp.be.placed());
            Map<Long, Integer> levels = new HashMap<>();
            for (long key : placed.keySet()) {
                levels.put(key, level.getBlockState(BlockPos.of(key)).getValue(LightBlock.LEVEL));
            }
            helper.assertTrue(levels.containsValue(12) && levels.containsValue(15), "the high-bay has both beam and spill lights");

            CompoundTag saved = lamp.be.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(saved.contains("Lights") && saved.contains("LightKinds"), "the saved lamp keeps the list in the old format");
            helper.assertTrue(!saved.contains("Spill") && !saved.contains("Levels"), "no new key is written for the levels");
            level.removeBlockEntity(pos);
            BlockEntity loaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess());
            helper.assertTrue(loaded instanceof BeamLampBlockEntity, "the lamp loads back");
            BeamLampBlockEntity reloaded = (BeamLampBlockEntity) loaded;
            level.setBlockEntity(reloaded);
            helper.assertTrue(reloaded.placed().equals(placed), "the reloaded list is the saved list");
            reloaded.reconcile();
            helper.assertTrue(reloaded.placed().equals(placed), "the reloaded lamp plans the same cells");
            for (var e : levels.entrySet()) {
                helper.assertTrue(level.getBlockState(BlockPos.of(e.getKey())).getValue(LightBlock.LEVEL) == e.getValue(),
                        "the reloaded lamp keeps the level at " + BlockPos.of(e.getKey()));
            }

            // An alpha.58 save: every light of the lamp at level 15.
            for (long key : placed.keySet()) {
                BlockPos p = BlockPos.of(key);
                level.setBlock(p, level.getBlockState(p).setValue(LightBlock.LEVEL, 15), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            reloaded.reconcile();
            helper.assertTrue(reloaded.placed().equals(placed), "re-levelling an old lamp adds and removes no light");
            for (var e : levels.entrySet()) {
                helper.assertTrue(level.getBlockState(BlockPos.of(e.getKey())).getValue(LightBlock.LEVEL) == e.getValue(),
                        "the old lamp's light is brought to level " + e.getValue() + " at " + BlockPos.of(e.getKey()));
            }
            level.setBlock(lamp.power, Blocks.AIR.defaultBlockState(), 2);
            reloaded.reconcile();
            helper.assertTrue(reloaded.placed().isEmpty(), "off, the reloaded lamp keeps no list");
            checkNoLights(helper, level, 14);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            done.run();
        });
    }

    // ---- a hall

    /**
     * 40 high-bays, 12 up on a 12-block grid over a 96 x 48 floor, all lit: every lamp keeps to the cap, the floor under them
     * is lit everywhere, one steady re-plan of all 40 (the 5 s refresh, every light already in place) is timed and logged,
     * and switching them all off takes every light away.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flreach4")
    public static void aHallOfFortyHighBaysIsLitEverywhereAndReplansCheaply(GameTestHelper helper) {
        onFloor(helper, done -> {
            ServerLevel level = helper.getLevel();
            List<Lamp> lamps = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int gx = 0; gx < 8; gx++) {
                for (int gz = 0; gz < 5; gz++) {
                    Lamp lamp = new Lamp(level, BwrBlocks.XENON_HIGH_BAY.get(), at(-42 + gx * 12, 12, -24 + gz * 12), Direction.DOWN);
                    lamps.add(lamp);
                    lamp.on();
                }
            }
            double placeMs = (System.nanoTime() - t0) / 1e6;
            int total = 0;
            for (Lamp lamp : lamps) {
                int n = lamp.be.placed().size();
                helper.assertTrue(n > 0 && n <= XenonBeam.MAX_LIGHTS, "every lamp of the hall keeps to the cap: " + n);
                total += n;
            }
            double[] pass = new double[25];
            for (int k = 0; k < pass.length; k++) {
                long s = System.nanoTime();
                for (Lamp lamp : lamps) {
                    lamp.be.reconcile();
                }
                pass[k] = (System.nanoTime() - s) / 1e6;
            }
            Arrays.sort(pass);
            LogUtils.getLogger().info("FLOODLIGHT PLANCOST 40 high-bays: {} lights in all, first placement of the hall {} ms, one steady re-plan of all 40: "
                            + "median {} ms (min {} max {}), {} ms a lamp", total, String.format("%.1f", placeMs), String.format("%.1f", pass[12]),
                    String.format("%.1f", pass[0]), String.format("%.1f", pass[24]), String.format("%.2f", pass[12] / lamps.size()));
            // Generous: several times what it measures, so only a real blow-up trips it.
            helper.assertTrue(pass[12] < 1000, "a steady re-plan of 40 high-bays is cheap: " + pass[12] + " ms");
            final int totalLights = total;
            settle(helper, level, () -> {
                int lo = 99;
                long sum = 0;
                int n = 0;
                for (int dx = -48; dx <= 48; dx++) {
                    for (int dz = -30; dz <= 30; dz++) {
                        int v = light(level, dx, dz);
                        lo = Math.min(lo, v);
                        sum += v;
                        n++;
                    }
                }
                LogUtils.getLogger().info("FLOODLIGHT PLANCOST hall of {} lights: floor light min {} mean {}", totalLights, lo,
                        String.format("%.1f", sum / (double) n));
                helper.assertTrue(lo >= 9, "the floor of the hall is lit everywhere: lowest " + lo);
                for (Lamp lamp : lamps) {
                    lamp.off();
                }
                checkNoLights(helper, level, 14);
                for (Lamp lamp : lamps) {
                    helper.assertTrue(lamp.be.placed().isEmpty(), "a lamp of the hall keeps no light when off");
                    lamp.remove();
                }
                done.run();
            });
        });
    }

    // ---- the planner on its own

    private static XenonBeam.World flatFloor(int boundaryX) {
        return new XenonBeam.World() {
            @Override
            public boolean loaded(BlockPos pos) {
                return pos.getX() < boundaryX;
            }

            @Override
            public BlockState state(BlockPos pos) {
                if (pos.getX() >= boundaryX) {
                    throw new AssertionError("the planner read an unloaded cell at " + pos);
                }
                return pos.getY() <= FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean opaque(BlockState state, BlockPos pos) {
                return pos.getY() <= FLOOR;
            }
        };
    }

    /**
     * A planner with a spill zone: the spill cells are a part of the plan, all outside the main beam, the main beam's cells
     * are none of them spill, the plan reaches the far floor, keeps to the cap, and never reads an unloaded cell. Without a
     * spill zone nothing is spill.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "flreach5")
    public static void aPlanWithASpillZoneSeparatesTheSpillStaysWithinTheCapAndNeverReadsAnUnloadedCell(GameTestHelper helper) {
        BlockPos lamp = at(0, 12, 0);
        Vec3 origin = Vec3.atCenterOf(lamp);
        Vec3 down = new Vec3(0, -1, 0);
        XenonBeam.Plan plan = XenonBeam.plan(flatFloor(Integer.MAX_VALUE), lamp, origin, down, 48, 55, 80, 2, Map.of());
        helper.assertTrue(plan.cells().size() <= XenonBeam.MAX_LIGHTS, "the plan keeps to the cap: " + plan.cells().size());
        helper.assertTrue(!plan.spill().isEmpty() && plan.cells().keySet().containsAll(plan.spill()), "the spill is a part of the plan");
        double far = 0;
        for (long key : plan.cells().keySet()) {
            BlockPos p = BlockPos.of(key);
            double r = Math.hypot(p.getX() - lamp.getX(), p.getZ() - lamp.getZ());
            far = Math.max(far, r);
            helper.assertTrue(p.getY() == FLOOR + 1, "every light lands on the floor: " + p);
            if (plan.isSpill(key)) {
                helper.assertTrue(r > 14, "a spill cell is outside the main beam (its floor radius is 16 blocks): " + r);
            } else {
                helper.assertTrue(r < 19, "a cell inside the main beam is not spill: " + r);
            }
        }
        helper.assertTrue(far > 40, "the plan reaches the far floor (46 blocks): " + far);
        helper.assertTrue(plan.cells().equals(XenonBeam.plan(flatFloor(Integer.MAX_VALUE), lamp, origin, down, 48, 55, 80, 2, Map.of()).cells()),
                "the plan is repeatable");

        XenonBeam.Plan none = XenonBeam.plan(flatFloor(Integer.MAX_VALUE), lamp, origin, down, 24, 55, 55, 2, Map.of());
        helper.assertTrue(none.spill().isEmpty() && !none.cells().isEmpty(), "a lamp without a spill zone has no spill");
        helper.assertTrue(none.cells().equals(XenonBeam.plan(flatFloor(Integer.MAX_VALUE), lamp, origin, down, 24, 55, 2, Map.of())),
                "the plan of a lamp without a spill zone is the old plan");

        // Across the edge of the loaded area (x = +20 from the lamp): nothing is read or planned beyond it.
        int boundary = lamp.getX() + 20;
        XenonBeam.Plan edge = XenonBeam.plan(flatFloor(boundary), lamp, origin, down, 48, 55, 80, 2, Map.of());
        helper.assertTrue(!edge.cells().isEmpty(), "the loaded part of the floor is lit");
        for (long key : edge.cells().keySet()) {
            helper.assertTrue(BlockPos.of(key).getX() < boundary, "a light was planned in the unloaded area at " + BlockPos.of(key));
        }
        helper.succeed();
    }

    /** The planner alone, on a flat floor with nothing to read: what the geometry costs, logged. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "flreach6")
    public static void thePlannersGeometryCostIsMeasured(GameTestHelper helper) {
        XenonBeam.World world = flatFloor(Integer.MAX_VALUE);
        BlockPos lamp = at(0, 12, 0);
        Vec3 o = Vec3.atCenterOf(lamp);
        Vec3 down = new Vec3(0, -1, 0);
        Vec3 east = new Vec3(1, 0, 0);
        Runnable[] cases = {
                () -> XenonBeam.plan(world, lamp, o, down, 24, 55, 2, Map.of()),
                () -> XenonBeam.plan(world, lamp, o, down, 48, 55, 80, 2, Map.of()),
                () -> XenonBeam.plan(world, lamp, o, east, 40, 65, 80, 2, Map.of()),
                () -> XenonBeam.plan(world, lamp, o, new Vec3(1, -0.5, 0), 32, 15, 2, Map.of()),
                // The new range and cone through the old planner (no spill zone: an even spread of the most rays it may cast).
                () -> XenonBeam.plan(world, lamp, o, down, 48, 80, 2, Map.of()),
        };
        String[] names = {"high-bay as it was (24 blocks, 110 degrees, no spill)", "high-bay now (48 blocks, 110/160 degrees)",
                "HPS panel on a wall now (40 blocks, 130/160 degrees)", "xenon floodlight (32 blocks, 30 degrees)",
                "for comparison: 48 blocks, 160 degrees through the planner without a spill zone (2048 rays)"};
        for (int k = 0; k < cases.length; k++) {
            for (int i = 0; i < 150; i++) {
                cases[k].run();
            }
            double[] t = new double[200];
            for (int i = 0; i < t.length; i++) {
                long s = System.nanoTime();
                cases[k].run();
                t[i] = (System.nanoTime() - s) / 1e6;
            }
            Arrays.sort(t);
            LogUtils.getLogger().info("FLOODLIGHT PLANGEOMETRY {}: median {} ms (min {} p90 {})", names[k], String.format("%.2f", t[100]),
                    String.format("%.2f", t[0]), String.format("%.2f", t[180]));
            helper.assertTrue(t[100] < 50, names[k] + " plans in a sane time: " + t[100] + " ms");
        }
        helper.succeed();
    }
}
