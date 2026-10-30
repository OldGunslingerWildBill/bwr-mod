package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.floodlight.AimReplans;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.FacingBeamLampBlock;
import dev.bwr.mod.floodlight.PendingLights;
import dev.bwr.mod.floodlight.XenonBeam;
import dev.bwr.mod.floodlight.XenonFloodlightBlock;
import dev.bwr.mod.gui.FloodlightAimMenu;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The xenon floodlight's aim in whole degrees and its reach of 8 to 128 blocks (LIGHTING.md): the aiming screen's commands and
 * their checks, the one re-plan for a burst of clicks, the old step saves, the planner at long reach, the cleanup of lights that
 * landed far away and the computer face. Namespace {@code bwrfloodlight}.
 *
 * <p>Two fixtures, each forced and built like the ones in {@link FloodlightTests}: a hall (a stone floor and a wall, 30 high) and a
 * long box (133 blocks long, 46 high) with a wall 123 blocks from the lamp. A fixture's chunks are forced through
 * {@link FixtureChunks}; the test waits until all are loaded before it builds, and until the light engine is idle before it lets
 * go. The tests of the long box run one at a time ({@code batch}).
 */
@GameTestHolder("bwrfloodlight")
@PrefixGameTestTemplate(false)
public final class FloodlightAimTests {
    private FloodlightAimTests() {
    }

    private static final int BASE = 760_000;
    private static final int FLOOR = 100;
    /** Fixture writes in the unloaded-chunk tests: tell the clients, no neighbour shape updates (see {@link #buildChunk}). */
    private static final int BUILD_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<ChunkPos> HOLD = TicketType.create("bwr_floodlight_aim_hold", Comparator.comparingLong(ChunkPos::toLong));

    /** A fixture box: x from {@code minDx} to {@code maxDx} and z from {@code minDz} to {@code maxDz} of the origin, {@code height} of air over the floor. */
    private record Box(int x0, int z0, int minDx, int maxDx, int minDz, int maxDz, int height) {
        BlockPos at(int dx, int dy, int dz) {
            return new BlockPos(x0 + dx, FLOOR + dy, z0 + dz);
        }
    }

    private static Box hall(int n) {
        return new Box(BASE + 200 * n, BASE, -4, 44, -22, 22, 30);
    }

    /** 133 blocks long; the lamp is at dx 0 and the wall at dx 123. */
    private static Box longBox(int n) {
        return new Box(BASE, BASE + 10_000 + 400 * n, -4, 130, -44, 44, 46);
    }

    private static final int WALL = 123;

    // ---- fixture

    private static void forceArea(ServerLevel level, Box b, boolean on) {
        for (int cx = (b.x0 + b.minDx - 2) >> 4; cx <= (b.x0 + b.maxDx + 2) >> 4; cx++) {
            for (int cz = (b.z0 + b.minDz - 2) >> 4; cz <= (b.z0 + b.maxDz + 2) >> 4; cz++) {
                FixtureChunks.force(level, b, cx, cz, on);
            }
        }
    }

    private static boolean areaLoaded(ServerLevel level, Box b) {
        for (int cx = (b.x0 + b.minDx - 2) >> 4; cx <= (b.x0 + b.maxDx + 2) >> 4; cx++) {
            for (int cz = (b.z0 + b.minDz - 2) >> 4; cz <= (b.z0 + b.maxDz + 2) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A stone floor and air over it; nothing a failed run left behind stays. */
    private static void build(ServerLevel level, Box b) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = b.minDx; dx <= b.maxDx; dx++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                level.setBlock(b.at(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 1; dy <= b.height; dy++) {
                    BlockPos p = b.at(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, air, 2);
                    }
                }
            }
        }
    }

    /** A wall of stone across the box at {@code dx}, floor to top. */
    private static void wall(ServerLevel level, Box b, int dx) {
        for (int dy = 1; dy <= b.height; dy++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                level.setBlock(b.at(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
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

    /** Waits until the light engine has nothing left to do (and at least 20 ticks), then runs {@code then}. */
    private static void settle(GameTestHelper helper, ServerLevel level, int waited, Runnable then) {
        helper.runAfterDelay(5, () -> {
            if (waited >= 20 && !level.getChunkSource().getLightEngine().hasLightWork()) {
                then.run();
            } else if (waited > 3000) {
                helper.assertTrue(false, "the light engine never settled");
            } else {
                settle(helper, level, waited + 5, then);
            }
        });
    }

    /**
     * Waits until the light engine is idle and the sampled wall light has read the same three times, 10 ticks apart (the engine
     * can have a task in flight while it reports no work), at least 30 ticks, then runs {@code then}.
     */
    private static void settleLight(GameTestHelper helper, ServerLevel level, Box b, Runnable then) {
        settleLight(helper, level, b, Set.of(), 0, Long.MIN_VALUE, 0, then);
    }

    /** As above, and every cell of {@code lights} must read 15: the engine reports no work part way through a big update. */
    private static void settleLight(GameTestHelper helper, ServerLevel level, Box b, Set<Long> lights, Runnable then) {
        settleLight(helper, level, b, lights, 0, Long.MIN_VALUE, 0, then);
    }

    private static long wallSum(ServerLevel level, Box b) {
        long sum = 0;
        for (int dy = -30; dy <= 30; dy += 3) {
            for (int dz = -30; dz <= 30; dz += 3) {
                sum = sum * 31 + wallLight(level, b, 22 + dy, dz);
            }
        }
        return sum;
    }

    private static void settleLight(GameTestHelper helper, ServerLevel level, Box b, Set<Long> lights, int waited, long last, int same, Runnable then) {
        helper.runAfterDelay(10, () -> {
            long now = wallSum(level, b);
            int stable = now == last ? same + 1 : 0;
            boolean allLit = true;
            for (long key : lights) {
                allLit &= level.getBrightness(LightLayer.BLOCK, BlockPos.of(key)) == 15;
            }
            if (waited >= 30 && stable >= 3 && allLit && !level.getChunkSource().getLightEngine().hasLightWork()) {
                then.run();
            } else if (waited > 3000) {
                helper.assertTrue(false, "the light engine never settled");
            } else {
                settleLight(helper, level, b, lights, waited + 10, now, stable, then);
            }
        });
    }

    /** Runs {@code body} on a forced, loaded, built box; the body calls {@code done} when its last check has passed. */
    private static void onBox(GameTestHelper helper, Box b, Consumer<Runnable> body) {
        ServerLevel level = helper.getLevel();
        forceArea(level, b, true);
        waitFor(helper, () -> areaLoaded(level, b), 1200, "the box's chunks to load", () -> {
            build(level, b);
            settle(helper, level, 0, () -> body.accept(() -> settle(helper, level, 0, () -> {
                forceArea(level, b, false);
                helper.succeed();
            })));
        });
    }

    // ---- helpers

    private static BeamLampBlockEntity floodlight(ServerLevel level, BlockPos at, Direction facing) {
        level.setBlock(at, BwrBlocks.XENON_FLOODLIGHT.get().defaultBlockState().setValue(XenonFloodlightBlock.FACING, facing), 3);
        return (BeamLampBlockEntity) level.getBlockEntity(at);
    }

    /** A redstone block behind the lamp is the power; air takes it away. Then the lamp re-plans, as on a redstone change. */
    private static void power(ServerLevel level, BlockPos lamp, boolean on) {
        Direction behind = level.getBlockState(lamp).getValue(XenonFloodlightBlock.FACING).getOpposite();
        level.setBlock(lamp.relative(behind), on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        ((BeamLampBlockEntity) level.getBlockEntity(lamp)).reconcile();
    }

    private static Vec3 centroid(Map<Long, Byte> cells) {
        double x = 0, y = 0, z = 0;
        for (long key : cells.keySet()) {
            BlockPos p = BlockPos.of(key);
            x += p.getX();
            y += p.getY();
            z += p.getZ();
        }
        int n = Math.max(1, cells.size());
        return new Vec3(x / n, y / n, z / n);
    }

    /** Every light block in the box is one the lamp placed, and every placed cell holds a light. */
    private static void checkExactlyTheLights(GameTestHelper helper, ServerLevel level, Box b, Map<Long, Byte> placed) {
        for (int dx = b.minDx; dx <= b.maxDx; dx++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                for (int dy = 0; dy <= b.height; dy++) {
                    BlockPos p = b.at(dx, dy, dz);
                    boolean light = level.getBlockState(p).is(Blocks.LIGHT);
                    helper.assertTrue(light == placed.containsKey(p.asLong()),
                            "a light block at " + p + " is " + (light ? "not in the lamp's list" : "missing"));
                }
            }
        }
        for (long key : placed.keySet()) {
            helper.assertTrue(level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a placed cell is not a light at " + BlockPos.of(key));
        }
    }

    // ---- the aim in degrees

    /**
     * One degree moves the lit pool: a floodlight 30 blocks from a wall, turned by one degree of tilt or heading, plans a different set
     * of lights whose centre moves the right way (up for tilt, to the right for heading) by about half a block (30 tan 1 degree),
     * and five degrees moves it five times as far. Tilt and heading in single degrees are not rounded to the old 15 and 45 degree steps.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "flaimhall")
    public static void aOneDegreeChangeOfAimMovesTheLitPool(GameTestHelper helper) {
        Box b = hall(0);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, 31);
            BlockPos lamp = b.at(0, 14, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Map<Long, Byte> base = new HashMap<>(be.placed());
            Vec3 c0 = centroid(base);
            helper.assertTrue(base.size() >= 20, "the level beam lights the wall: " + base.size());

            helper.assertTrue(be.setAim(0, 1), "one degree up is accepted");
            be.reconcile();
            Map<Long, Byte> up1 = new HashMap<>(be.placed());
            Vec3 c1 = centroid(up1);
            helper.assertTrue(!up1.equals(base), "one degree of tilt plans a different set of lights");
            helper.assertTrue(c1.y - c0.y > 0.15 && c1.y - c0.y < 1.2, "one degree up lifts the pool by about half a block: " + (c1.y - c0.y));
            helper.assertTrue(be.pitchDegrees() == 1 && be.yawDegrees() == 0, "the aim is the exact degree: " + be.pitchDegrees());

            be.setAim(0, 5);
            be.reconcile();
            double up5 = centroid(be.placed()).y - c0.y;
            helper.assertTrue(up5 > 2.0 && up5 < 3.4, "five degrees up lifts it about 2.6 blocks: " + up5);

            be.setAim(1, 0);
            be.reconcile();
            Map<Long, Byte> right1 = new HashMap<>(be.placed());
            Vec3 c2 = centroid(right1);
            helper.assertTrue(!right1.equals(base), "one degree of heading plans a different set of lights");
            helper.assertTrue(c2.z - c0.z > 0.15 && c2.z - c0.z < 1.2, "one degree to the right moves the pool south (a floodlight facing east) by about half a block: " + (c2.z - c0.z));
            helper.assertTrue(Math.abs(c2.y - c0.y) < 0.3, "and it does not move up or down: " + (c2.y - c0.y));

            be.setAim(359, 0);
            be.reconcile();
            helper.assertTrue(centroid(be.placed()).z - c0.z < -0.15, "359 degrees is one degree to the left");
            be.setAim(0, -1);
            be.reconcile();
            helper.assertTrue(centroid(be.placed()).y - c0.y < -0.15, "one degree down lowers the pool");

            power(level, lamp, false);
            done.run();
        });
    }

    /** The quick tilt (sneak-right-click) still steps the old 15 degrees, and the old turn 45, from any degree. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theQuickStepsStillMoveOnTheOldGrid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        BeamLampBlockEntity be = floodlight(level, at, Direction.NORTH);
        helper.assertTrue(be.yawDegrees() == 0 && be.pitchDegrees() == 0 && be.reach() == 32, "a new floodlight is level, straight ahead, reach 32");
        be.stepAim(true);
        helper.assertTrue(be.pitchDegrees() == 15, "the first quick tilt is 15 degrees up: " + be.pitchDegrees());
        be.stepAim(true);
        helper.assertTrue(be.pitchDegrees() == 30, "then +30");
        be.stepAim(true);
        helper.assertTrue(be.pitchDegrees() == -75, "and round to -75");
        be.setAim(0, 7);
        be.stepAim(true);
        helper.assertTrue(be.pitchDegrees() == 15, "from 7 degrees the next step of the grid is 15: " + be.pitchDegrees());
        be.setAim(100, 0);
        be.stepAim(false);
        helper.assertTrue(be.yawDegrees() == 135, "from 100 degrees the next step of the 45 degree grid is 135: " + be.yawDegrees());
        be.setAim(359, 0);
        be.stepAim(false);
        helper.assertTrue(be.yawDegrees() == 0, "and round to 0: " + be.yawDegrees());
        be.setAim(30, -10);
        var message = (net.minecraft.network.chat.contents.TranslatableContents) be.aimMessage().getContents();
        helper.assertTrue(message.getKey().equals("message.bwr.xenon_floodlight.aim") && message.getArgs().length == 2
                && Integer.valueOf(30).equals(message.getArgs()[0]) && Integer.valueOf(-10).equals(message.getArgs()[1]), "the aim message still carries the two angles");
        level.removeBlock(at, false);
        helper.succeed();
    }

    // ---- saves

    /**
     * A save from before (the step keys {@code Yaw} 3 and {@code Pitch} 2, no degrees) loads to 135 degrees and -45 degrees, and the
     * lamp lights exactly the cells a lamp turned there with the old steps did. A save with degrees keeps them exactly, the reach too;
     * the default aim writes no new key; an aim on the old grid also writes the old keys.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "flaimhall")
    public static void anOldSaveWithStepAimLoadsToTheSameAimAndLights(GameTestHelper helper) {
        Box b = hall(1);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            BlockPos lamp = b.at(20, 14, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            // Aim it the old way: 3 yaw steps and 7 pitch steps round to step 2 (-45 degrees).
            for (int i = 0; i < 3; i++) {
                be.stepAim(false);
            }
            for (int i = 0; i < 5; i++) {
                be.stepAim(true);
            }
            helper.assertTrue(be.yawDegrees() == 135 && be.pitchDegrees() == -45, "stepped to 135 and -45: " + be.yawDegrees() + ", " + be.pitchDegrees());
            power(level, lamp, true);
            Map<Long, Byte> expected = new HashMap<>(be.placed());
            helper.assertTrue(!expected.isEmpty(), "the stepped aim lights something");
            power(level, lamp, false);
            helper.assertTrue(be.placed().isEmpty(), "and it is gone when off");

            CompoundTag old = new CompoundTag();
            old.putInt("Yaw", 3);
            old.putInt("Pitch", 2);
            old.putString("id", "bwr:beam_lamp");
            old.putInt("x", lamp.getX());
            old.putInt("y", lamp.getY());
            old.putInt("z", lamp.getZ());
            level.removeBlockEntity(lamp);
            BlockEntity loaded = BlockEntity.loadStatic(lamp, level.getBlockState(lamp), old, level.registryAccess());
            helper.assertTrue(loaded instanceof BeamLampBlockEntity, "the old save loads as a lamp");
            BeamLampBlockEntity from = (BeamLampBlockEntity) loaded;
            level.setBlockEntity(from);
            helper.assertTrue(from.yawDegrees() == 135 && from.pitchDegrees() == -45 && from.reach() == 32,
                    "the old save is 135 degrees, -45 degrees, reach 32: " + from.yawDegrees() + ", " + from.pitchDegrees() + ", " + from.reach());
            power(level, lamp, true);
            helper.assertTrue(from.placed().equals(expected), "and it lights exactly the cells the stepped lamp lit: " + from.placed().size() + " of " + expected.size());
            power(level, lamp, false);
            helper.assertTrue(from.placed().isEmpty(), "and cleans up");

            // Another old save: only a yaw (the pitch key is missing): level.
            CompoundTag yawOnly = new CompoundTag();
            yawOnly.putInt("Yaw", 9);
            BeamLampBlockEntity y = new BeamLampBlockEntity(lamp, level.getBlockState(lamp));
            y.loadWithComponents(yawOnly, level.registryAccess());
            helper.assertTrue(y.yawDegrees() == 45 && y.pitchDegrees() == 0, "Yaw 9 wraps to step 1 (45 degrees), no Pitch is level: " + y.yawDegrees() + ", " + y.pitchDegrees());

            // A save with degrees.
            level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
            BeamLampBlockEntity fresh = floodlight(level, lamp, Direction.EAST);
            helper.assertTrue(fresh.setAim(37, -12, 100), "37, -12 and a reach of 100 are accepted");
            CompoundTag saved = fresh.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(saved.getInt("YawDeg") == 37 && saved.getInt("PitchDeg") == -12 && saved.getInt("Reach") == 100,
                    "the save carries the degrees and the reach: " + saved);
            helper.assertTrue(!saved.contains("Yaw") && !saved.contains("Pitch"), "37 and -12 are off the old grid, so no step keys");
            BeamLampBlockEntity again = new BeamLampBlockEntity(lamp, level.getBlockState(lamp));
            again.loadWithComponents(saved, level.registryAccess());
            helper.assertTrue(again.yawDegrees() == 37 && again.pitchDegrees() == -12 && again.reach() == 100, "and loads back exactly");
            // The client gets the same aim (the head turns to the exact angle).
            CompoundTag update = fresh.getUpdateTag(level.registryAccess());
            BeamLampBlockEntity client = new BeamLampBlockEntity(lamp, level.getBlockState(lamp));
            client.loadWithComponents(update, level.registryAccess());
            helper.assertTrue(client.yawDegrees() == 37 && client.pitchDegrees() == -12, "the client's copy has the exact angles: " + client.yawDegrees() + ", " + client.pitchDegrees());
            Vec3 d = client.beamDirection();
            Vec3 server = fresh.beamDirection();
            helper.assertTrue(d.distanceTo(server) < 1e-9, "and so the same beam direction");

            // Defaults write nothing.
            fresh.setAim(0, 0, 32);
            CompoundTag plain = fresh.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(!plain.contains("YawDeg") && !plain.contains("PitchDeg") && !plain.contains("Reach") && !plain.contains("Yaw") && !plain.contains("Pitch"),
                    "the default aim writes no aim key: " + plain);
            // On the old grid both forms are written.
            fresh.setAim(90, -15);
            CompoundTag grid = fresh.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(grid.getInt("YawDeg") == 90 && grid.getInt("Yaw") == 2 && grid.getInt("PitchDeg") == -15 && grid.getInt("Pitch") == 4,
                    "an aim on the old grid is also written as the old steps: " + grid);
            level.removeBlock(lamp, false);
            done.run();
        });
    }

    // ---- the screen's commands

    private static ServerPlayer player(ServerLevel level, String name, GameType mode) {
        ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
        p.setGameMode(mode);
        return p;
    }

    /** Opens the aiming menu for {@code p} on the lamp at {@code lamp}, standing at {@code stand}. */
    private static FloodlightAimMenu menu(ServerPlayer p, BlockPos lamp, Vec3 stand) {
        p.setPos(stand.x, stand.y, stand.z);
        return new FloodlightAimMenu(7, p.getInventory(), lamp);
    }

    /**
     * Every command is checked: a good one aims the lamp; out-of-range yaw, pitch and reach, an unknown command, a player too far away,
     * a spectator, a player who may not build, and a high-bay (no aim) change nothing. Thirty commands a second are taken and the
     * rest dropped (the window opens again 20 ticks later).
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theAimCommandsAreCheckedAndRateLimited(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lamp = helper.absolutePos(new BlockPos(2, 2, 2));
        BeamLampBlockEntity be = floodlight(level, lamp, Direction.NORTH);
        Vec3 near = Vec3.atCenterOf(lamp).add(2, 0, 0);
        ServerPlayer creative = player(level, "aimer", GameType.CREATIVE);
        FloodlightAimMenu m = menu(creative, lamp, near);

        m.handleCommand(creative, FloodlightAimMenu.SET_AIM, 200, -20);
        helper.assertTrue(be.yawDegrees() == 200 && be.pitchDegrees() == -20, "a good command aims the lamp: " + be.yawDegrees() + ", " + be.pitchDegrees());
        m.handleCommand(creative, FloodlightAimMenu.SET_REACH, 100, 0);
        helper.assertTrue(be.reach() == 100 && be.yawDegrees() == 200, "and a good reach command sets the reach: " + be.reach());

        int[][] bad = {{360, 0}, {-1, 0}, {0, 31}, {0, -76}, {Integer.MAX_VALUE, 0}, {0, Integer.MIN_VALUE}};
        for (int[] c : bad) {
            m.handleCommand(creative, FloodlightAimMenu.SET_AIM, c[0], c[1]);
            helper.assertTrue(be.yawDegrees() == 200 && be.pitchDegrees() == -20, "the aim " + Arrays.toString(c) + " is refused: " + be.yawDegrees() + ", " + be.pitchDegrees());
        }
        for (int r : new int[] {7, 129, 0, -5, Integer.MAX_VALUE}) {
            m.handleCommand(creative, FloodlightAimMenu.SET_REACH, r, 0);
            helper.assertTrue(be.reach() == 100, "the reach " + r + " is refused: " + be.reach());
        }
        m.handleCommand(creative, 9, 10, 10);
        m.handleCommand(creative, -1, 10, 10);
        helper.assertTrue(be.yawDegrees() == 200 && be.reach() == 100, "an unknown command does nothing");
        m.handleCommand(creative, FloodlightAimMenu.SET_REACH, 8, 0);
        helper.assertTrue(be.reach() == 8, "8 is the shortest reach");
        m.handleCommand(creative, FloodlightAimMenu.SET_REACH, 128, 0);
        helper.assertTrue(be.reach() == 128, "128 is the longest");
        m.handleCommand(creative, FloodlightAimMenu.SET_AIM, 359, 30);
        helper.assertTrue(be.yawDegrees() == 359 && be.pitchDegrees() == 30, "359 and +30 are the limits");
        m.handleCommand(creative, FloodlightAimMenu.SET_AIM, 0, -75);
        helper.assertTrue(be.yawDegrees() == 0 && be.pitchDegrees() == -75, "0 and -75 are the other limits");
        be.setAim(40, 10, 32);

        // Too far away: the same good command from 20 blocks does nothing.
        ServerPlayer far = player(level, "far-aimer", GameType.CREATIVE);
        FloodlightAimMenu farMenu = menu(far, lamp, Vec3.atCenterOf(lamp).add(20, 0, 0));
        farMenu.handleCommand(far, FloodlightAimMenu.SET_AIM, 111, 11);
        helper.assertTrue(be.yawDegrees() == 40 && be.pitchDegrees() == 10, "a player 20 blocks away cannot aim it");
        // Just inside the reach is fine.
        FloodlightAimMenu edge = menu(far, lamp, Vec3.atCenterOf(lamp).add(5, 0, 0));
        edge.handleCommand(far, FloodlightAimMenu.SET_AIM, 111, 11);
        helper.assertTrue(be.yawDegrees() == 111, "and 5 blocks away is fine");
        be.setAim(40, 10, 32);

        ServerPlayer spectator = player(level, "ghost", GameType.SPECTATOR);
        FloodlightAimMenu ghost = menu(spectator, lamp, near);
        ghost.handleCommand(spectator, FloodlightAimMenu.SET_AIM, 99, 9);
        ghost.handleCommand(spectator, FloodlightAimMenu.SET_REACH, 99, 9);
        helper.assertTrue(be.yawDegrees() == 40 && be.reach() == 32, "a spectator cannot aim it");

        ServerPlayer adventurer = player(level, "adventurer", GameType.ADVENTURE);
        FloodlightAimMenu adv = menu(adventurer, lamp, near);
        adv.handleCommand(adventurer, FloodlightAimMenu.SET_AIM, 99, 9);
        helper.assertTrue(be.yawDegrees() == 40, "a player who may not build cannot aim it");

        // A high-bay has nothing to aim.
        BlockPos bay = helper.absolutePos(new BlockPos(6, 2, 2));
        level.setBlock(bay, BwrBlocks.XENON_HIGH_BAY.get().defaultBlockState().setValue(FacingBeamLampBlock.FACING, Direction.DOWN), 3);
        FloodlightAimMenu bayMenu = menu(creative, bay, Vec3.atCenterOf(bay).add(2, 0, 0));
        bayMenu.handleCommand(creative, FloodlightAimMenu.SET_AIM, 99, 9);
        bayMenu.handleCommand(creative, FloodlightAimMenu.SET_REACH, 99, 0);
        BeamLampBlockEntity bayBe = (BeamLampBlockEntity) level.getBlockEntity(bay);
        helper.assertTrue(bayBe.yawDegrees() == 0 && bayBe.pitchDegrees() == 0 && bayBe.reach() == 48, "a high-bay cannot be aimed: " + bayBe.yawDegrees() + ", " + bayBe.reach());
        helper.assertTrue(!bayBe.setAim(10, 10) && !bayBe.setReach(60), "and its aim methods refuse");
        level.removeBlock(bay, false);

        // The rate limit: a hundred good commands in one tick, thirty are taken.
        FloodlightAimMenu rapid = menu(creative, lamp, near);
        for (int i = 1; i <= 100; i++) {
            rapid.handleCommand(creative, FloodlightAimMenu.SET_AIM, i, 0);
        }
        helper.assertTrue(be.yawDegrees() == FloodlightAimMenu.MAX_COMMANDS, "a hundred commands in a tick: " + FloodlightAimMenu.MAX_COMMANDS + " are taken, not " + be.yawDegrees());
        helper.runAfterDelay(FloodlightAimMenu.WINDOW_TICKS + 2, () -> {
            rapid.handleCommand(creative, FloodlightAimMenu.SET_AIM, 250, 0);
            helper.assertTrue(be.yawDegrees() == 250, "twenty ticks later commands are taken again: " + be.yawDegrees());
            level.removeBlock(lamp, false);
            helper.succeed();
        });
    }

    // ---- one re-plan for a burst

    /**
     * Fifteen commands in one tick move the head (the lamp's aim) at once and cost no plan; four ticks later there is exactly one plan,
     * for the last aim, and the lights are that plan's. A click every tick for twelve ticks costs at most four plans, not twelve.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "flaimhall")
    public static void aBurstOfClicksMakesOneReplan(GameTestHelper helper) {
        Box b = hall(2);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, 24);
            BlockPos lamp = b.at(0, 14, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            ServerPlayer p = player(level, "clicker", GameType.CREATIVE);
            FloodlightAimMenu m = menu(p, lamp, Vec3.atCenterOf(lamp).add(0, 0, 3));
            // The block's own first plan runs a tick after it is placed (onPlace schedules it): let it pass before counting.
            helper.runAfterDelay(4, () -> burst(helper, level, b, lamp, be, p, m, done));
        });
    }

    private static void burst(GameTestHelper helper, ServerLevel level, Box b, BlockPos lamp, BeamLampBlockEntity be, ServerPlayer p,
                              FloodlightAimMenu m, Runnable done) {
        {
            Map<Long, Byte> before = new HashMap<>(be.placed());
            int plans0 = be.plans();
            long total0 = BeamLampBlockEntity.plansTotal();
            for (int i = 1; i <= 15; i++) {
                m.handleCommand(p, FloodlightAimMenu.SET_AIM, i, i - 8);
            }
            helper.assertTrue(be.yawDegrees() == 15 && be.pitchDegrees() == 7, "the head is at the last aim at once: " + be.yawDegrees() + ", " + be.pitchDegrees());
            helper.assertTrue(be.plans() == plans0, "fifteen clicks have cost no plan yet: " + (be.plans() - plans0));
            helper.assertTrue(be.placed().equals(before), "and the lights have not moved yet");
            helper.assertTrue(AimReplans.waiting() >= 1, "a re-plan is waiting");
            helper.runAfterDelay(AimReplans.DELAY_TICKS + 3, () -> {
                helper.assertTrue(be.plans() == plans0 + 1, "one plan for fifteen clicks: " + (be.plans() - plans0));
                helper.assertTrue(BeamLampBlockEntity.plansTotal() >= total0 + 1, "counted");
                helper.assertTrue(!be.placed().equals(before), "the lights moved to the new aim");
                Map<Long, Byte> settled = new HashMap<>(be.placed());
                be.reconcile();
                helper.assertTrue(be.placed().equals(settled), "and they are what a plan of the last aim gives");
                checkExactlyTheLights(helper, level, b, be.placed());

                // A click every tick for twelve ticks.
                int start = be.plans();
                FloodlightAimMenu m2 = menu(p, lamp, Vec3.atCenterOf(lamp).add(0, 0, 3));
                clickEveryTick(helper, m2, p, 0, 12, () -> helper.runAfterDelay(AimReplans.DELAY_TICKS + 3, () -> {
                    int plans = be.plans() - start;
                    helper.assertTrue(plans >= 1 && plans <= 4, "twelve clicks in twelve ticks cost " + plans + " plans, at most four");
                    helper.assertTrue(be.yawDegrees() == 100 + 11, "the head followed every click: " + be.yawDegrees());
                    power(level, lamp, false);
                    checkExactlyTheLights(helper, level, b, Map.of());
                    done.run();
                }));
            });
        }
    }

    private static void clickEveryTick(GameTestHelper helper, FloodlightAimMenu m, ServerPlayer p, int i, int n, Runnable then) {
        m.handleCommand(p, FloodlightAimMenu.SET_AIM, 100 + i, 0);
        if (i + 1 < n) {
            helper.runAfterDelay(1, () -> clickEveryTick(helper, m, p, i + 1, n, then));
        } else {
            then.run();
        }
    }

    // ---- exact cleanup

    /**
     * Re-aiming and changing the reach puts every old light back exactly: after each change every light in the box is on the lamp's
     * list and every listed cell is a light, and when the lamp goes off the box reads as it did before.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "flaimhall")
    public static void reAimingAndChangingTheReachCleanUpExactly(GameTestHelper helper) {
        Box b = hall(3);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, 24);
            Map<BlockPos, BlockState> before = new HashMap<>();
            for (int dx = b.minDx; dx <= b.maxDx; dx++) {
                for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                    for (int dy = 0; dy <= b.height; dy++) {
                        before.put(b.at(dx, dy, dz), level.getBlockState(b.at(dx, dy, dz)));
                    }
                }
            }
            BlockPos lamp = b.at(0, 14, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Set<Long> everLit = new HashSet<>(be.placed().keySet());
            helper.assertTrue(everLit.size() > 10, "the wall 24 blocks off is lit at the usual reach: " + everLit.size());
            checkExactlyTheLights(helper, level, b, be.placed());

            be.setReach(20);
            be.reconcile();
            helper.assertTrue(be.placed().isEmpty(), "a reach of 20 does not reach the wall at 24: " + be.placed().size());
            checkExactlyTheLights(helper, level, b, be.placed());

            be.setReach(26);
            be.reconcile();
            helper.assertTrue(!be.placed().isEmpty(), "a reach of 26 does");
            everLit.addAll(be.placed().keySet());
            checkExactlyTheLights(helper, level, b, be.placed());

            int[][] aims = {{17, -12}, {200, 4}, {359, 30}, {1, 1}, {270, -75}, {45, 0}};
            for (int[] a : aims) {
                be.setAim(a[0], a[1], 64);
                be.reconcile();
                everLit.addAll(be.placed().keySet());
                checkExactlyTheLights(helper, level, b, be.placed());
            }
            be.setAim(0, 0, 128);
            be.reconcile();
            everLit.addAll(be.placed().keySet());
            checkExactlyTheLights(helper, level, b, be.placed());
            be.setReach(8);
            be.reconcile();
            helper.assertTrue(be.placed().isEmpty() || be.placed().size() < 5, "a reach of 8 lights next to nothing");
            checkExactlyTheLights(helper, level, b, be.placed());

            power(level, lamp, false);
            checkExactlyTheLights(helper, level, b, Map.of());
            for (long key : everLit) {
                helper.assertTrue(!level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a light is left at " + BlockPos.of(key));
            }
            for (var e : before.entrySet()) {
                if (!e.getKey().equals(lamp) && !e.getKey().equals(lamp.west())) {
                    helper.assertTrue(level.getBlockState(e.getKey()).equals(e.getValue()), "not restored at " + e.getKey());
                }
            }
            done.run();
        });
    }

    // ---- long reach

    /** Block light of the wall's front layer (the cells in front of the wall), at height {@code dy}, {@code dz} from the middle. */
    private static int wallLight(ServerLevel level, Box b, int dy, int dz) {
        return level.getBrightness(LightLayer.BLOCK, b.at(WALL - 1, dy, dz));
    }

    /**
     * What the lights of a plan give a wall cell, as the light engine will spread it through open air (15 less one a block, by
     * the sum of the three distances).
     */
    private static int best(List<BlockPos> lights, BlockPos cell) {
        int best = 0;
        for (BlockPos l : lights) {
            best = Math.max(best, 15 - (Math.abs(l.getX() - cell.getX()) + Math.abs(l.getY() - cell.getY()) + Math.abs(l.getZ() - cell.getZ())));
        }
        return best;
    }

    /**
     * A floodlight 22 up looking along a 133-block box at a wall 123 blocks away. At reach 128 it lights the wall: the lights keep to the
     * cap, the engine's light at the middle of the pool is 12 or more, and every cell of the pool within 20 blocks of the middle is at 6
     * or more (both worked out from the lights and read back from the light engine). At reach 16, 32 and 64 it lights nothing at the
     * wall, and the wall front reads 0.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flaim1")
    public static void reach128LightsAFarWallAndShorterReachesDoNot(GameTestHelper helper) {
        Box b = longBox(0);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, WALL);
            BlockPos lamp = b.at(0, 22, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            helper.assertTrue(be.setReach(128), "reach 128 is accepted");
            power(level, lamp, true);
            List<BlockPos> lights = XenonBeam.positions(be.placed());
            helper.assertTrue(lights.size() <= XenonBeam.MAX_LIGHTS && lights.size() >= 100, "reach 128 keeps to the cap and lights a good many: " + lights.size());
            int onWall = 0;
            for (BlockPos l : lights) {
                if (l.getX() == b.at(WALL - 1, 0, 0).getX()) {
                    onWall++;
                }
            }
            helper.assertTrue(onWall >= 100, "the lights are on the far wall: " + onWall);
            int lowest = 99, count = 0, dim = 0;
            double sum = 0;
            for (int dy = -20; dy <= 20; dy++) {
                for (int dz = -20; dz <= 20; dz++) {
                    if (dy * dy + dz * dz <= 400) {
                        int v = best(lights, b.at(WALL - 1, 22 + dy, dz));
                        lowest = Math.min(lowest, v);
                        sum += v;
                        count++;
                        if (v < 6) {
                            dim++;
                        }
                    }
                }
            }
            LogUtils.getLogger().info("FLOODLIGHT AIM FARWALL reach=128 lights={} onWall={} computed pool light within 20 blocks of the middle: lowest {} mean {} cells below 6: {} of {}",
                    lights.size(), onWall, lowest, String.format("%.1f", sum / count), dim, count);
            helper.assertTrue(lowest >= 6, "every cell of the far pool is lit to 6 or more by the plan: lowest " + lowest);
            Set<Long> far = new HashSet<>(be.placed().keySet());
            settleLight(helper, level, b, far, () -> {
                int mid = wallLight(level, b, 22, 0);
                int engineLow = 99;
                for (int dy = -20; dy <= 20; dy += 2) {
                    for (int dz = -20; dz <= 20; dz += 2) {
                        if (dy * dy + dz * dz <= 400) {
                            engineLow = Math.min(engineLow, wallLight(level, b, 22 + dy, dz));
                        }
                    }
                }
                LogUtils.getLogger().info("FLOODLIGHT AIM FARWALL engine light at the middle {} lowest within 20 blocks {}", mid, engineLow);
                helper.assertTrue(mid >= 12, "the middle of the far pool reads 12 or more in the light engine: " + mid);
                helper.assertTrue(engineLow >= 6, "the far pool reads 6 or more everywhere within 20 blocks of the middle: " + engineLow);
                shorter(helper, level, b, lamp, be, far, done);
            });
        });
    }

    private static void shorter(GameTestHelper helper, ServerLevel level, Box b, BlockPos lamp, BeamLampBlockEntity be, Set<Long> far, Runnable done) {
        be.setReach(16);
        be.reconcile();
        helper.assertTrue(be.placed().isEmpty(), "a reach of 16 lights nothing here: " + be.placed().size());
        for (long key : far) {
            helper.assertTrue(!level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a far light is left at " + BlockPos.of(key));
        }
        be.setReach(64);
        be.reconcile();
        helper.assertTrue(be.placed().isEmpty(), "a reach of 64 does not reach the wall at 123 either: " + be.placed().size());
        be.setReach(32);
        be.reconcile();
        helper.assertTrue(be.placed().isEmpty(), "nor does the usual reach of 32");
        settleLight(helper, level, b, () -> {
            int max = 0;
            for (int dy = -20; dy <= 20; dy += 2) {
                for (int dz = -20; dz <= 20; dz += 2) {
                    max = Math.max(max, wallLight(level, b, 22 + dy, dz));
                }
            }
            helper.assertTrue(max == 0, "the wall is dark at reach 32: the brightest cell reads " + max);
            power(level, lamp, false);
            done.run();
        });
    }

    // ---- what a plan costs

    private static double median(double[] t) {
        double[] s = t.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    /**
     * What one re-plan of a lit floodlight costs at reach 32, 64 and 128 (the steady state: it reads the world, plans, finds nothing to
     * change), and a yard of 20 lit floodlights at reach 128 re-planning one after the other; all logged ({@code FLOODLIGHT AIM PLANCOST}).
     * The bounds are only there to catch a plan that has become slow by an order of magnitude.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flaim2")
    public static void thePlanCostAtLongReachIsMeasured(GameTestHelper helper) {
        Box b = longBox(1);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, WALL);
            BlockPos lamp = b.at(0, 22, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            for (int reach : new int[] {32, 64, 128}) {
                be.setReach(reach);
                power(level, lamp, true);
                for (int i = 0; i < 30; i++) {
                    be.reconcile();
                }
                double[] t = new double[40];
                for (int i = 0; i < t.length; i++) {
                    long s = System.nanoTime();
                    be.reconcile();
                    t[i] = (System.nanoTime() - s) / 1e6;
                }
                LogUtils.getLogger().info("FLOODLIGHT AIM PLANCOST single floodlight reach={} lights={}: median {} ms (min {} max {})", reach, be.placed().size(),
                        String.format("%.2f", median(t)), String.format("%.2f", Arrays.stream(t).min().getAsDouble()), String.format("%.2f", Arrays.stream(t).max().getAsDouble()));
                helper.assertTrue(median(t) < 150, "a re-plan at reach " + reach + " stays cheap: " + median(t) + " ms");
            }
            power(level, lamp, false);
            level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);

            // A yard: twenty floodlights across the box, all at reach 128, each aimed a little differently.
            List<BeamLampBlockEntity> yard = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                BlockPos at = b.at(0, 22, -38 + 4 * i);
                BeamLampBlockEntity f = floodlight(level, at, Direction.EAST);
                f.setAim((i * 7) % 12, (i % 5) - 2, 128);
                power(level, at, true);
                yard.add(f);
            }
            int total = 0;
            for (BeamLampBlockEntity f : yard) {
                total += f.placed().size();
            }
            for (int i = 0; i < 6; i++) {
                for (BeamLampBlockEntity f : yard) {
                    f.reconcile();
                }
            }
            double[] pass = new double[10];
            for (int k = 0; k < pass.length; k++) {
                long s = System.nanoTime();
                for (BeamLampBlockEntity f : yard) {
                    f.reconcile();
                }
                pass[k] = (System.nanoTime() - s) / 1e6;
            }
            LogUtils.getLogger().info("FLOODLIGHT AIM PLANCOST yard of 20 lit floodlights at reach 128 ({} lights in all): one pass re-planning all 20 takes median {} ms (min {} max {}), {} ms a lamp",
                    total, String.format("%.1f", median(pass)), String.format("%.1f", Arrays.stream(pass).min().getAsDouble()),
                    String.format("%.1f", Arrays.stream(pass).max().getAsDouble()), String.format("%.2f", median(pass) / 20));
            helper.assertTrue(median(pass) < 3000, "a pass over the yard stays cheap: " + median(pass) + " ms");
            for (BeamLampBlockEntity f : yard) {
                power(level, f.getBlockPos(), false);
                helper.assertTrue(f.placed().isEmpty(), "the yard lamp is off");
                level.setBlock(f.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            }
            settle(helper, level, 0, () -> {
                checkNoLightsInBox(helper, level, b);
                done.run();
            });
        });
    }

    /** The refresh of long-reach floodlights is spread over 40 ticks by position; a lamp at the usual reach is not delayed. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aYardOfLongReachFloodlightsDoesNotReplanInOneTick(GameTestHelper helper) {
        Box b = longBox(1);
        int[] perTick = new int[dev.bwr.mod.floodlight.BeamLampBlock.STAGGER_TICKS];
        for (int i = 0; i < 20; i++) {
            BlockPos at = b.at(0, 22, -38 + 4 * i);
            int s = dev.bwr.mod.floodlight.BeamLampBlock.refreshStagger(at, 128);
            helper.assertTrue(s >= 0 && s < perTick.length, "the stagger is in range: " + s);
            perTick[s]++;
            helper.assertTrue(dev.bwr.mod.floodlight.BeamLampBlock.refreshStagger(at, 32) == 0
                    && dev.bwr.mod.floodlight.BeamLampBlock.refreshStagger(at, 8) == 0, "a lamp at the usual reach or less is not delayed");
        }
        int distinct = 0, most = 0;
        for (int n : perTick) {
            distinct += n > 0 ? 1 : 0;
            most = Math.max(most, n);
        }
        LogUtils.getLogger().info("FLOODLIGHT AIM STAGGER yard of 20 at reach 128: {} distinct refresh offsets, at most {} lamps in one tick", distinct, most);
        helper.assertTrue(distinct >= 12 && most <= 3, "twenty lamps spread over " + distinct + " ticks, at most " + most + " in one");
        helper.succeed();
    }

    /**
     * The floor, not a wall, at long reach: a floodlight 22 up tilted 15 degrees down (the floor is 82 blocks off along the beam) with a
     * reach of 128. The pool is long and thin, and the cap of 256 lights is spread over it. The light worked out from the plan for every
     * floor cell inside the cone is logged ({@code FLOODLIGHT AIM FLOORPOOL}), against the same lamp 10 up at reach 32 and tilted 30
     * down. Every cell inside the cone within 100 blocks of the lamp reads 6 or more.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flaim5")
    public static void theFloorPoolAtLongReachIsCovered(GameTestHelper helper) {
        Box b = longBox(2);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            double[] far = floorPool(helper, level, b, 22, -15, 128, 100);
            double[] near = floorPool(helper, level, b, 10, -30, 32, 32);
            // The yard of the pictures: 12 up, level, a wall 123 blocks off. Floor and wall share the lights.
            wall(level, b, WALL);
            double[] yard = floorPool(helper, level, b, 12, 0, 128, 118);
            helper.assertTrue(far[0] >= 6, "the long floor pool is lit to 6 or more everywhere inside the cone: lowest " + far[0]
                    + ", " + far[2] + " cells of " + far[3] + " below 6");
            helper.assertTrue(near[0] >= 6, "the usual pool is too: lowest " + near[0]);
            helper.assertTrue(yard[0] >= 6, "the floor of a yard with a far wall too: lowest " + yard[0] + ", " + yard[2] + " cells of " + yard[3] + " below 6");
            done.run();
        });
    }

    /** The floor cells (the air cell on the floor) inside the 13.5 degree cone of the lamp, up to {@code within} blocks from its lens. */
    private static List<BlockPos> poolCells(Box b, BeamLampBlockEntity be, double within) {
        Vec3 origin = be.beamOrigin();
        Vec3 axis = be.beamDirection().normalize();
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -2; dx <= WALL - 3; dx++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                BlockPos cell = b.at(dx, 1, dz);
                Vec3 v = Vec3.atCenterOf(cell).subtract(origin);
                double dist = v.length();
                if (dist <= within && dist >= 1 && Math.toDegrees(Math.acos(v.dot(axis) / dist)) <= 13.5) {
                    out.add(cell);
                }
            }
        }
        return out;
    }

    /**
     * The same yard as the pictures (the floodlight 12 up, level, reach 128, a wall 123 blocks away), read back from the light engine
     * once it has settled: every floor cell inside the cone within 118 blocks reads {@code at least 5}.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "flaim6")
    public static void theYardFloorReadsLitInTheLightEngine(GameTestHelper helper) {
        Box b = longBox(3);
        onBox(helper, b, done -> {
            ServerLevel level = helper.getLevel();
            wall(level, b, WALL);
            BlockPos lamp = b.at(0, 12, 0);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            be.setAim(0, 0, 128);
            power(level, lamp, true);
            List<BlockPos> cells = poolCells(b, be, 118);
            Set<Long> placed = new HashSet<>(be.placed().keySet());
            int[] last = {-1};
            int[] same = {0};
            Runnable[] poll = new Runnable[1];
            poll[0] = () -> helper.runAfterDelay(10, () -> {
                long sum = 0;
                for (BlockPos c : cells) {
                    sum = sum * 31 + level.getBrightness(LightLayer.BLOCK, c);
                }
                same[0] = (int) sum == last[0] ? same[0] + 1 : 0;
                last[0] = (int) sum;
                // (The engine can report no work while it is part way: it is done when every light reads 15 and the cells stop changing.)
                boolean allLit = true;
                for (long key : placed) {
                    allLit &= level.getBrightness(LightLayer.BLOCK, BlockPos.of(key)) == 15;
                }
                if (same[0] >= 3 && sum != 0 && allLit && !level.getChunkSource().getLightEngine().hasLightWork()) {
                    int lowest = 99, dim = 0;
                    long total = 0;
                    for (BlockPos c : cells) {
                        int v = level.getBrightness(LightLayer.BLOCK, c);
                        lowest = Math.min(lowest, v);
                        total += v;
                        dim += v < 5 ? 1 : 0;
                    }
                    int atFifteen = 0, floorLights = 0;
                    List<BlockPos> lightCells = XenonBeam.positions(be.placed());
                    for (BlockPos l : lightCells) {
                        atFifteen += level.getBrightness(LightLayer.BLOCK, l) == 15 ? 1 : 0;
                        floorLights += l.getY() == FLOOR + 1 ? 1 : 0;
                    }
                    int far = 0, farLit = 0;
                    for (BlockPos l : lightCells) {
                        if (l.getY() == FLOOR + 1 && l.getX() > b.x0 + 90) {
                            far++;
                            farLit += level.getBrightness(LightLayer.BLOCK, l) == 15 ? 1 : 0;
                        }
                    }
                    LogUtils.getLogger().info("FLOODLIGHT AIM YARDFLOOR {} of {} lights read 15 in the engine, {} are floor lights, {} of {} floor lights past 90 blocks read 15; chunk light work: {}",
                            atFifteen, lightCells.size(), floorLights, farLit, far, level.getChunkSource().getLightEngine().hasLightWork());
                    LogUtils.getLogger().info("FLOODLIGHT AIM YARDFLOOR engine light of {} floor cells inside the cone: lowest {} mean {} below 5: {} ({} lights)",
                            cells.size(), lowest, String.format("%.1f", total / (double) cells.size()), dim, placed.size());
                    helper.assertTrue(lowest >= 5, "the yard floor reads lit in the light engine: lowest " + lowest + ", " + dim + " of " + cells.size() + " cells below 5");
                    power(level, lamp, false);
                    done.run();
                } else {
                    poll[0].run();
                }
            });
            poll[0].run();
        });
    }

    /** Lowest, mean, cells below 6 and cells, of the computed floor light inside the cone up to {@code within} blocks. */
    private static double[] floorPool(GameTestHelper helper, ServerLevel level, Box b, int height, int pitch, int reach, double within) {
        BlockPos lamp = b.at(0, height, 0);
        BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
        be.setAim(0, pitch, reach);
        power(level, lamp, true);
        List<BlockPos> lights = XenonBeam.positions(be.placed());
        Vec3 origin = be.beamOrigin();
        Vec3 axis = be.beamDirection().normalize();
        int lowest = 99, cells = 0, dim = 0;
        double sum = 0;
        for (int dx = -2; dx <= WALL - 3; dx++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                BlockPos cell = b.at(dx, 1, dz);
                Vec3 v = Vec3.atCenterOf(cell).subtract(origin);
                double dist = v.length();
                if (dist > within || dist < 1 || Math.toDegrees(Math.acos(v.dot(axis) / dist)) > 13.5) {
                    continue;
                }
                int best = best(lights, cell);
                lowest = Math.min(lowest, best);
                sum += best;
                cells++;
                if (best < 6) {
                    dim++;
                }
            }
        }
        LogUtils.getLogger().info("FLOODLIGHT AIM FLOORPOOL height={} pitch={} reach={}: {} lights, {} floor cells inside the cone within {} blocks, light lowest {} mean {} cells below 6: {}",
                height, pitch, reach, lights.size(), cells, (int) within, lowest, String.format("%.1f", cells == 0 ? 0 : sum / cells), dim);
        power(level, lamp, false);
        level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
        return new double[] {lowest, cells == 0 ? 0 : sum / cells, dim, cells};
    }

    private static void checkNoLightsInBox(GameTestHelper helper, ServerLevel level, Box b) {
        for (int dx = b.minDx; dx <= b.maxDx; dx++) {
            for (int dz = b.minDz; dz <= b.maxDz; dz++) {
                for (int dy = 1; dy <= b.height; dy++) {
                    if (level.getBlockState(b.at(dx, dy, dz)).is(Blocks.LIGHT)) {
                        helper.assertTrue(false, "a light was left at " + b.at(dx, dy, dz));
                    }
                }
            }
        }
    }

    // ---- chunks that are not loaded, at long reach

    private static void hold(ServerLevel level, ChunkPos c, boolean on) {
        if (on) {
            level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        } else {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
    }

    /**
     * Stone floor and air in one held chunk, 12 high, a wall at local x {@code wallX} (or none). Written without neighbour
     * shape updates ({@link #BUILD_FLAGS}): a changed block on the chunk's edge would otherwise read its neighbour in the
     * next chunk, and that read loads the chunk (a one-tick ticket at full level), so the first run in a world, where the
     * area is still empty and every block changes, loaded the chunks the tests check stay unloaded.
     */
    private static void buildChunk(ServerLevel level, ChunkPos c, int wallX) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos floor = new BlockPos(c.getMinBlockX() + x, FLOOR, c.getMinBlockZ() + z);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), BUILD_FLAGS);
                for (int dy = 1; dy <= 12; dy++) {
                    level.setBlock(floor.above(dy), x == wallX ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), BUILD_FLAGS);
                }
            }
        }
    }

    /**
     * The lamp (reach 128) is in a held chunk A and a wall is in the held chunk F, 106 blocks east, with six chunks between them that
     * nobody holds: the beam stops at the first of them. Nothing is lit in F, and nothing loads a chunk.
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "flaim3")
    public static void aLongBeamStopsAtAnUnloadedChunkAndLoadsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos a = new ChunkPos((BASE + 5_000) >> 4, (BASE + 5_000) >> 4);
        ChunkPos f = new ChunkPos(a.x + 7, a.z);
        hold(level, a, true);
        hold(level, f, true);
        waitFor(helper, () -> level.hasChunk(a.x, a.z) && level.hasChunk(f.x, f.z), 1200, "chunks A and F to load", () -> {
            for (int i = 1; i < 7; i++) {
                helper.assertTrue(!level.hasChunk(a.x + i, a.z), "the chunk between is not loaded: " + (a.x + i));
            }
            buildChunk(level, a, -1);
            buildChunk(level, f, 6);
            BlockPos lamp = new BlockPos(a.getMinBlockX() + 12, FLOOR + 3, a.getMinBlockZ() + 8);
            helper.assertTrue(!level.hasChunk(a.x + 1, a.z), "building chunks A and F loaded the chunk next to A");
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            helper.assertTrue(!level.hasChunk(a.x + 1, a.z), "placing the lamp loaded the chunk next to A");
            be.setReach(128);
            power(level, lamp, true);
            for (long key : be.placed().keySet()) {
                BlockPos p = BlockPos.of(key);
                helper.assertTrue(new ChunkPos(p).equals(a), "a light outside chunk A, the only chunk the beam can see: " + p);
            }
            for (int i = 1; i < 7; i++) {
                helper.assertTrue(!level.hasChunk(a.x + i, a.z) && level.getChunkSource().getChunkNow(a.x + i, a.z) == null,
                        "planning at reach 128 loaded the chunk " + (a.x + i));
            }
            helper.assertTrue(!level.getBlockState(new BlockPos(f.getMinBlockX() + 5, FLOOR + 3, a.getMinBlockZ() + 8)).is(Blocks.LIGHT), "the far wall got no light");
            power(level, lamp, false);
            for (int i = 1; i < 7; i++) {
                helper.assertTrue(!level.hasChunk(a.x + i, a.z), "and switching off loaded nothing");
            }
            level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
            hold(level, a, false);
            hold(level, f, false);
            helper.succeed();
        });
    }

    /**
     * Eight chunks in a row are held (the lamp in the first, a wall in the last, 106 blocks off) and the beam, at reach 128, lights the far
     * wall. Then the last chunk is let go and unloads. A shorter reach cannot take the far lights back (it cannot see them), so they stay
     * on the list; when the chunk is back the next plan takes them. Lit up again and let go once more, the lamp is switched off: the far
     * lights go to the queue, nothing is loaded, and they are removed exactly once when the chunk loads again.
     */
    @GameTest(template = "empty", timeoutTicks = 4800, batch = "flaim4")
    public static void lightsPlacedFarAwayAreCleanedUpExactlyIncludingThroughTheQueue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos a = new ChunkPos((BASE + 6_000) >> 4, (BASE + 6_000) >> 4);
        List<ChunkPos> row = new ArrayList<>();
        for (int i = 0; i <= 7; i++) {
            row.add(new ChunkPos(a.x + i, a.z));
        }
        ChunkPos f = row.get(7);
        for (ChunkPos c : row) {
            hold(level, c, true);
        }
        PendingLights pending = PendingLights.get(level);
        waitFor(helper, () -> row.stream().allMatch(c -> level.hasChunk(c.x, c.z)), 1800, "the eight chunks to load", () -> {
            for (ChunkPos c : row) {
                buildChunk(level, c, c.equals(f) ? 6 : -1);
            }
            pending.processLoaded(level);
            BlockPos lamp = new BlockPos(a.getMinBlockX() + 12, FLOOR + 3, a.getMinBlockZ() + 8);
            BeamLampBlockEntity be = floodlight(level, lamp, Direction.EAST);
            be.setReach(128);
            power(level, lamp, true);
            Set<BlockPos> inF = new HashSet<>();
            Set<BlockPos> elsewhere = new HashSet<>();
            for (long key : be.placed().keySet()) {
                BlockPos p = BlockPos.of(key);
                (new ChunkPos(p).equals(f) ? inF : elsewhere).add(p);
            }
            helper.assertTrue(!inF.isEmpty(), "the beam lands on the wall in chunk F, 106 blocks away: " + be.placed().size() + " lights, " + elsewhere.size() + " elsewhere");

            hold(level, f, false);
            waitFor(helper, () -> !level.hasChunk(f.x, f.z), 1200, "chunk F to unload", () -> {
                be.setReach(40);
                be.reconcile();
                helper.assertTrue(be.placed().keySet().containsAll(inF.stream().map(BlockPos::asLong).toList()),
                        "the far lights stay on the list while their chunk is out of sight");
                helper.assertTrue(!level.hasChunk(f.x, f.z) && level.getChunkSource().getChunkNow(f.x, f.z) == null, "and planning did not load F");
                hold(level, f, true);
                waitFor(helper, () -> level.hasChunk(f.x, f.z), 1200, "chunk F to load again", () -> {
                    be.reconcile();
                    for (BlockPos p : inF) {
                        helper.assertTrue(!level.getBlockState(p).is(Blocks.LIGHT), "with F back and the reach 40, the far light is taken: " + p);
                        helper.assertTrue(!be.placed().containsKey(p.asLong()), "and it is off the list: " + p);
                    }
                    // Again: reach 128, lit, F lets go, the lamp goes off.
                    be.setReach(128);
                    be.reconcile();
                    Set<BlockPos> farAgain = new HashSet<>();
                    be.placed().keySet().forEach(k -> {
                        if (new ChunkPos(BlockPos.of(k)).equals(f)) {
                            farAgain.add(BlockPos.of(k));
                        }
                    });
                    helper.assertTrue(farAgain.size() == inF.size(), "the same far lights again: " + farAgain.size() + " of " + inF.size());
                    hold(level, f, false);
                    waitFor(helper, () -> !level.hasChunk(f.x, f.z), 1200, "chunk F to unload again", () -> {
                        power(level, lamp, false);
                        helper.assertTrue(be.placed().isEmpty(), "the switched-off lamp keeps no list");
                        for (BlockPos p : farAgain) {
                            helper.assertTrue(pending.contains(p), "a far light in the unloaded chunk is queued: " + p);
                        }
                        helper.assertTrue(pending.sizeIn(f) == farAgain.size(), "exactly those are queued: " + pending.sizeIn(f));
                        for (ChunkPos c : row) {
                            if (!c.equals(f)) {
                                helper.assertTrue(level.hasChunk(c.x, c.z), "the other chunks are still loaded");
                            }
                        }
                        for (BlockPos p : elsewhere) {
                            helper.assertTrue(!level.getBlockState(p).is(Blocks.LIGHT), "a light in a loaded chunk was restored at once: " + p);
                        }
                        helper.assertTrue(!level.hasChunk(f.x, f.z) && level.getChunkSource().getChunkNow(f.x, f.z) == null, "switching off did not load F");
                        long restored = PendingLights.restoredTotal();
                        hold(level, f, true);
                        waitFor(helper, () -> level.hasChunk(f.x, f.z), 1200, "chunk F to load a third time", () ->
                                waitFor(helper, () -> !pending.contains(farAgain.iterator().next()), PendingLights.SWEEP_TICKS + 20, "the queue to run", () -> {
                                    for (BlockPos p : farAgain) {
                                        helper.assertTrue(!level.getBlockState(p).is(Blocks.LIGHT), "the queue removed the far light: " + p);
                                    }
                                    helper.assertTrue(PendingLights.restoredTotal() - restored == farAgain.size(),
                                            "each far light removed once: " + (PendingLights.restoredTotal() - restored) + " of " + farAgain.size());
                                    level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
                                    level.setBlock(lamp.west(), Blocks.AIR.defaultBlockState(), 2);
                                    for (ChunkPos c : row) {
                                        hold(level, c, false);
                                    }
                                    helper.succeed();
                                }));
                    });
                });
            });
        });
    }

    // ---- computers

    /** The computer face reads the aim in degrees and the reach, and aims the floodlight (not the high-bay). Needs CC:Tweaked. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theComputerFaceReadsAndAimsTheFloodlight(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            helper.succeed();
            return;
        }
        try {
            FloodlightAimCcChecks.verify(helper);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        helper.succeed();
    }
}
