package dev.bwr.mod.devtest;

import dev.bwr.mod.floodlight.BeamLampBlock;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.FacingBeamLampBlock;
import dev.bwr.mod.floodlight.HpsFlatPanelBlock;
import dev.bwr.mod.floodlight.PendingLights;
import dev.bwr.mod.floodlight.XenonBeam;
import dev.bwr.mod.floodlight.XenonFloodlightBlock;
import dev.bwr.mod.floodlight.XenonHighBayBlock;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The beam lamps (xenon floodlight, xenon high-bay, HPS flat panel): the light they place is exactly what they take away, a
 * player's own light blocks are never touched, and nothing they do loads a chunk.
 *
 * <p>Every test has its own fixture site (620,000 plus 200 a test). A site's chunks are forced through {@link FixtureChunks}
 * and the test waits until every one of them is loaded by that ticket before it builds anything (building into a chunk that
 * is still on its way would load it on the spot, synchronously, with its neighbours half generated). At the end the test
 * waits for the light engine to settle before it lets the chunks go, so no chunk generation is left half done behind a
 * released ticket. Namespace {@code bwrfloodlight}: {@code -PbwrGameTestNamespaces=bwrfloodlight} runs just these.
 */
@GameTestHolder("bwrfloodlight")
@PrefixGameTestTemplate(false)
public final class FloodlightTests {
    private FloodlightTests() {
    }

    private static final int BASE = 620_000;
    private static final int FLOOR = 100;
    private static final int WIDTH = 42;     // x from -2 to +39 of the site
    private static final int DEPTH = 20;     // z from -20 to +20
    private static final int HEIGHT = 14;    // y from floor to floor + 14
    /** Holds a single chunk at ticket level 33: loaded and full, its neighbours not. */
    private static final TicketType<ChunkPos> HOLD = TicketType.create("bwr_floodlight_hold", Comparator.comparingLong(ChunkPos::toLong));

    private record Site(int x, int z) {
        BlockPos at(int dx, int dy, int dz) {
            return new BlockPos(x + dx, FLOOR + dy, z + dz);
        }
    }

    private static Site site(int n) {
        return new Site(BASE + 200 * n, BASE);
    }

    // ---- fixture: force, wait, build, run, settle, release

    private static void forceArea(ServerLevel level, Site s, boolean on) {
        for (int cx = (s.x() - 4) >> 4; cx <= (s.x() + WIDTH + 4) >> 4; cx++) {
            for (int cz = (s.z() - DEPTH - 4) >> 4; cz <= (s.z() + DEPTH + 4) >> 4; cz++) {
                FixtureChunks.force(level, s, cx, cz, on);
            }
        }
    }

    private static boolean areaLoaded(ServerLevel level, Site s) {
        for (int cx = (s.x() - 4) >> 4; cx <= (s.x() + WIDTH + 4) >> 4; cx++) {
            for (int cz = (s.z() - DEPTH - 4) >> 4; cz <= (s.z() + DEPTH + 4) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Stone floor, air above it. */
    private static void build(ServerLevel level, Site s) {
        for (int dx = -2; dx < WIDTH - 2; dx++) {
            for (int dz = -DEPTH; dz <= DEPTH; dz++) {
                level.setBlock(s.at(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 1; dy <= HEIGHT; dy++) {
                    level.setBlock(s.at(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Runs {@code body} on a forced, loaded, built site. The body calls {@code done} when its last check has passed (at once,
     * or after waits of its own); the site is then settled and released, and the test succeeds.
     */
    private static void onSite(GameTestHelper helper, int n, Consumer<Runnable> body) {
        ServerLevel level = helper.getLevel();
        Site s = site(n);
        forceArea(level, s, true);
        waitFor(helper, () -> areaLoaded(level, s), 600, "the site's chunks to load", () -> {
            build(level, s);
            body.accept(() -> settle(helper, level, 0, () -> {
                forceArea(level, s, false);
                helper.succeed();
            }));
        });
    }

    /** Waits until the light engine has nothing left to do, and at least 20 ticks, then runs {@code then}. */
    private static void settle(GameTestHelper helper, ServerLevel level, int waited, Runnable then) {
        helper.runAfterDelay(5, () -> {
            if (waited >= 20 && !level.getChunkSource().getLightEngine().hasLightWork()) {
                then.run();
            } else if (waited > 400) {
                helper.assertTrue(false, "the light engine never settled");
            } else {
                settle(helper, level, waited + 5, then);
            }
        });
    }

    /** Polls every 5 ticks until {@code ready}, then runs {@code then}; fails the test after {@code maxTicks}. */
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

    // ---- helpers

    /** Every cell of the built box, read now. */
    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, Site s) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int dx = -2; dx < WIDTH - 2; dx++) {
            for (int dz = -DEPTH; dz <= DEPTH; dz++) {
                for (int dy = 0; dy <= HEIGHT; dy++) {
                    BlockPos p = s.at(dx, dy, dz);
                    out.put(p, level.getBlockState(p));
                }
            }
        }
        return out;
    }

    private static BeamLampBlockEntity placeFloodlight(ServerLevel level, BlockPos at, Direction facing) {
        level.setBlock(at, BwrBlocks.XENON_FLOODLIGHT.get().defaultBlockState().setValue(XenonFloodlightBlock.FACING, facing), 3);
        return (BeamLampBlockEntity) level.getBlockEntity(at);
    }

    private static BeamLampBlockEntity placeFixed(ServerLevel level, Block block, BlockPos at, Direction facing) {
        level.setBlock(at, block.defaultBlockState().setValue(FacingBeamLampBlock.FACING, facing), 3);
        return (BeamLampBlockEntity) level.getBlockEntity(at);
    }

    /** A redstone block west of the lamp is the power; air takes it away. Then the lamp re-plans, as on a redstone change. */
    private static void power(ServerLevel level, BlockPos lamp, boolean on) {
        level.setBlock(lamp.west(), on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        ((BeamLampBlockEntity) level.getBlockEntity(lamp)).reconcile();
    }

    /** A wall of stone across the site at {@code dx}, the full height. */
    private static void wall(ServerLevel level, Site s, int dx) {
        for (int dy = 1; dy <= HEIGHT; dy++) {
            for (int dz = -DEPTH; dz <= DEPTH; dz++) {
                level.setBlock(s.at(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    /** Every light block in the box is one the lamp placed (or one of {@code foreign}), and every placed cell holds a light. */
    private static void checkExactlyTheLights(GameTestHelper helper, ServerLevel level, Site s, Map<Long, Byte> placed,
                                              Set<BlockPos> foreign) {
        for (var e : snapshot(level, s).entrySet()) {
            boolean light = e.getValue().is(Blocks.LIGHT);
            boolean ours = placed.containsKey(e.getKey().asLong());
            boolean theirs = foreign.contains(e.getKey());
            helper.assertTrue(light == (ours || theirs), "a light block at " + e.getKey() + " is "
                    + (light ? "not in the lamp's list" : "missing"));
            helper.assertTrue(!(ours && theirs), "the lamp claims a player's light at " + e.getKey());
        }
        for (long key : placed.keySet()) {
            helper.assertTrue(level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a placed cell is not a light at " + BlockPos.of(key));
        }
    }

    private static void checkExactlyTheLights(GameTestHelper helper, ServerLevel level, Site s, Map<Long, Byte> placed) {
        checkExactlyTheLights(helper, level, s, placed, Set.of());
    }

    /** Every cell not in {@code except} reads the same as in {@code before}. */
    private static void checkRestored(GameTestHelper helper, ServerLevel level, Map<BlockPos, BlockState> before, Set<BlockPos> except) {
        for (var e : before.entrySet()) {
            if (except.contains(e.getKey())) {
                continue;
            }
            helper.assertTrue(level.getBlockState(e.getKey()).equals(e.getValue()),
                    "not restored position by position at " + e.getKey() + ": " + level.getBlockState(e.getKey()) + " != " + e.getValue());
        }
    }

    private static void checkAllWereAir(GameTestHelper helper, Map<BlockPos, BlockState> before, Map<Long, Byte> placed) {
        for (long key : placed.keySet()) {
            BlockPos p = BlockPos.of(key);
            // A cell outside the built box (a beam running past its edge onto the terrain) is checked by placeCell's own rule.
            helper.assertTrue(before.get(p) == null || before.get(p).isAir(), "a light was placed in a cell that was not air: " + p + " " + before.get(p));
        }
    }

    /** None of {@code cells} (copied from a lamp's list while it was lit) holds a light any more, inside the box or out. */
    private static void checkGone(GameTestHelper helper, ServerLevel level, Set<Long> cells) {
        for (long key : cells) {
            helper.assertTrue(!level.getBlockState(BlockPos.of(key)).is(Blocks.LIGHT), "a light the lamp placed is still there at " + BlockPos.of(key));
        }
    }

    // ---- registration

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theLampsAreRegisteredHaveNoTickerAndCannotBePushed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(BwrBlocks.XENON_FLOODLIGHT.get() instanceof XenonFloodlightBlock, "the floodlight block is registered");
        helper.assertTrue(BwrBlocks.XENON_HIGH_BAY.get() instanceof XenonHighBayBlock, "the high-bay block is registered");
        helper.assertTrue(BwrBlocks.HPS_FLAT_PANEL.get() instanceof HpsFlatPanelBlock, "the HPS flat panel block is registered");
        helper.assertTrue(BwrItems.XENON_FLOODLIGHT.get() != null && BwrItems.XENON_HIGH_BAY.get() != null
                && BwrItems.HPS_FLAT_PANEL.get() != null, "the three items are registered");
        for (Block block : new Block[] {BwrBlocks.XENON_FLOODLIGHT.get(), BwrBlocks.XENON_HIGH_BAY.get(), BwrBlocks.HPS_FLAT_PANEL.get()}) {
            BlockState state = block.defaultBlockState();
            helper.assertTrue(!state.getValue(BeamLampBlock.LIT), block + " starts unlit");
            helper.assertTrue(((EntityBlock) block).getTicker(level, state, BwrBlockEntities.BEAM_LAMP.get()) == null,
                    block + " has no block ticker");
            helper.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK, "a piston cannot move " + block);
        }
        helper.succeed();
    }

    // ---- placing and removing

    /** Power on lights the cone where it lands; power off (the signal lost) takes every light away and restores each cell. */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aLitFloodlightPlacesLightsOnlyInAirAndPowerLossTurnsEveryOneOff(GameTestHelper helper) {
        onSite(helper, 0, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(0);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            helper.assertTrue(be.lit(), "the powered floodlight is lit");
            Map<Long, Byte> placed = new HashMap<>(be.placed());
            helper.assertTrue(placed.size() >= 10, "a lit floodlight lights where its beam lands: " + placed.size());
            helper.assertTrue(placed.keySet().stream().anyMatch(k -> BlockPos.of(k).getX() == s.at(23, 0, 0).getX()),
                    "the beam lands on the wall it points at");
            checkAllWereAir(helper, before, placed);
            for (long key : placed.keySet()) {
                helper.assertTrue(level.getBlockState(BlockPos.of(key)).getValue(LightBlock.LEVEL) == 15, "a xenon light is level 15");
            }
            checkExactlyTheLights(helper, level, s, placed);

            power(level, lamp, false);
            checkGone(helper, level, placed.keySet());
            helper.assertTrue(!be.lit(), "the floodlight goes out when the signal is lost");
            helper.assertTrue(be.placed().isEmpty(), "the lamp's list is empty when it is off");
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west()));
            done.run();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void reAimingRemovesTheOldLightsExactlyAndLightsTheNewCone(GameTestHelper helper) {
        onSite(helper, 1, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(1);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Map<Long, Byte> first = new HashMap<>(be.placed());
            helper.assertTrue(!first.isEmpty(), "the first aim lights something");

            be.stepAim(true); // pitch down 15 degrees... wrapping from level to +15: either way a different cone
            be.stepAim(false);
            Map<Long, Byte> second = new HashMap<>(be.placed());
            helper.assertTrue(!second.isEmpty() && !second.equals(first), "a new aim plans a different set of lights");
            for (long key : first.keySet()) {
                if (!second.containsKey(key)) {
                    BlockPos p = BlockPos.of(key);
                    helper.assertTrue(level.getBlockState(p).equals(before.get(p)), "an old light was not restored at " + p);
                }
            }
            checkExactlyTheLights(helper, level, s, second);

            power(level, lamp, false);
            checkGone(helper, level, first.keySet());
            checkGone(helper, level, second.keySet());
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west()));
            done.run();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void breakingTheLampRemovesEveryLightItPlaced(GameTestHelper helper) {
        onSite(helper, 2, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(2);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            helper.assertTrue(!be.placed().isEmpty(), "the lamp is lit before it is broken");
            Set<Long> placed = new HashSet<>(be.placed().keySet());

            level.destroyBlock(lamp, false);
            checkGone(helper, level, placed);
            level.setBlock(lamp.west(), Blocks.AIR.defaultBlockState(), 2);
            helper.assertTrue(level.getBlockState(lamp).isAir(), "the lamp is gone");
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of());
            done.run();
        });
    }

    /**
     * An explosion removes the lamp through the same block removal as breaking it (light blocks themselves are blast proof).
     * Nothing the lamp placed may survive, and the blast cannot have put a light anywhere.
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void anExplosionLeavesNoLightBehind(GameTestHelper helper) {
        onSite(helper, 7, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(7);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Set<Long> placed = new HashSet<>(be.placed().keySet());
            helper.assertTrue(!placed.isEmpty(), "the lamp is lit before the blast");

            level.explode(null, lamp.getX() + 0.5, lamp.getY() + 0.5, lamp.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.BLOCK);
            helper.assertTrue(!level.getBlockState(lamp).is(BwrBlocks.XENON_FLOODLIGHT.get()), "the blast removed the lamp");
            for (var e : snapshot(level, s).entrySet()) {
                helper.assertTrue(!e.getValue().is(Blocks.LIGHT), "a light survived the blast at " + e.getKey());
            }
            checkGone(helper, level, placed);
            done.run();
        });
    }

    // ---- what may be replaced, and what is never touched

    /** A block placed over one of the lamp's lights is kept: the restore touches only a cell that still holds a light. */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aBlockPlacedOverALightIsNeverOverwritten(GameTestHelper helper) {
        onSite(helper, 8, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(8);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            BlockPos taken = BlockPos.of(be.placed().keySet().iterator().next());
            level.setBlock(taken, Blocks.GLASS.defaultBlockState(), 2);

            be.reconcile();
            helper.assertTrue(!be.placed().containsKey(taken.asLong()), "the lamp forgets a cell a block now holds");
            power(level, lamp, false);
            helper.assertTrue(level.getBlockState(taken).is(Blocks.GLASS), "the glass placed over the light is not overwritten");
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west(), taken));
            done.run();
        });
    }

    /**
     * A player's own light blocks in the beam (one where the beam lands, one in its path, one waterlogged) survive every way
     * the lamp lets its light go: power loss, a new aim, a replan, and breaking the lamp. The lamp never lists them.
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aPlayersOwnLightInTheBeamSurvivesEveryCleanup(GameTestHelper helper) {
        onSite(helper, 10, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(10);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            // First find where the beam lands, then take one of those cells and two more for the player's lights.
            BeamLampBlockEntity probe = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            BlockPos landing = BlockPos.of(probe.placed().keySet().iterator().next());
            power(level, lamp, false);
            level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(level.getBlockState(landing).isAir(), "the probe lamp left the landing cell as it was");

            BlockPos inPath = s.at(10, 3, 0);
            BlockPos wet = s.at(23, 1, 1);
            level.setBlock(landing, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 2);
            level.setBlock(inPath, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9), 2);
            level.setBlock(wet, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12).setValue(LightBlock.WATERLOGGED, true), 2);
            Map<BlockPos, BlockState> theirs = Map.of(landing, level.getBlockState(landing), inPath, level.getBlockState(inPath),
                    wet, level.getBlockState(wet));
            Runnable check = () -> {
                for (var e : theirs.entrySet()) {
                    helper.assertTrue(level.getBlockState(e.getKey()).equals(e.getValue()), "the player's light at " + e.getKey()
                            + " was touched: " + level.getBlockState(e.getKey()));
                }
            };
            Map<BlockPos, BlockState> before = snapshot(level, s);

            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            helper.assertTrue(!be.placed().isEmpty(), "the lamp lights around the player's lights");
            checkExactlyTheLights(helper, level, s, be.placed(), theirs.keySet());
            check.run();
            be.reconcile();
            check.run();
            power(level, lamp, false);                 // power loss
            check.run();
            checkExactlyTheLights(helper, level, s, Map.of(), theirs.keySet());
            power(level, lamp, true);
            be.stepAim(false);                          // a new aim
            be.stepAim(false);
            be.stepAim(false);
            be.stepAim(false);
            be.stepAim(false);
            be.stepAim(false);
            be.stepAim(false);
            be.stepAim(false);                          // all the way round, back to east
            check.run();
            checkExactlyTheLights(helper, level, s, be.placed(), theirs.keySet());
            level.destroyBlock(lamp, false);            // broken
            level.setBlock(lamp.west(), Blocks.AIR.defaultBlockState(), 2);
            check.run();
            checkExactlyTheLights(helper, level, s, Map.of(), theirs.keySet());
            checkRestored(helper, level, before, Set.of());
            done.run();
        });
    }

    /**
     * A solid wall across the beam (at dx = 4): the beam lands in front of it, nothing in the wall is replaced, and nothing
     * behind the wall is lit.
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void nothingIsPlacedIntoASolidBlockOrBehindIt(GameTestHelper helper) {
        onSite(helper, 3, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(3);
            wall(level, s, 4);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            helper.assertTrue(!be.placed().isEmpty(), "the beam lands on the wall");
            for (int dy = 1; dy <= HEIGHT; dy++) {
                for (int dz = -DEPTH; dz <= DEPTH; dz++) {
                    helper.assertTrue(level.getBlockState(s.at(4, dy, dz)).is(Blocks.STONE), "the stone wall was replaced at " + s.at(4, dy, dz));
                }
            }
            for (long key : be.placed().keySet()) {
                helper.assertTrue(BlockPos.of(key).getX() < s.at(4, 0, 0).getX(), "a cell behind the wall is lit: " + BlockPos.of(key));
            }
            checkAllWereAir(helper, before, be.placed());
            power(level, lamp, false);
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west()));
            done.run();
        });
    }

    // ---- saves

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void saveAndReloadKeepsTheExactCleanup(GameTestHelper helper) {
        onSite(helper, 4, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(4);
            wall(level, s, 24);
            BlockPos lamp = s.at(0, 3, 0);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            be.stepAim(true);
            power(level, lamp, true);
            Map<Long, Byte> placed = new HashMap<>(be.placed());

            CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(saved.contains("Lights") && saved.contains("Pitch"), "the saved lamp carries its aim and its list of lights");
            level.removeBlockEntity(lamp);
            BlockEntity loaded = BlockEntity.loadStatic(lamp, level.getBlockState(lamp), saved, level.registryAccess());
            helper.assertTrue(loaded instanceof BeamLampBlockEntity, "the lamp loads back as a lamp");
            BeamLampBlockEntity reloaded = (BeamLampBlockEntity) loaded;
            level.setBlockEntity(reloaded);
            helper.assertTrue(reloaded.placed().equals(placed), "the reloaded list is the saved list");
            helper.assertTrue(reloaded.pitchStep() == be.pitchStep(), "the reloaded aim is the saved aim");
            checkExactlyTheLights(helper, level, s, placed);

            power(level, lamp, false);
            helper.assertTrue(reloaded.placed().isEmpty(), "the reloaded lamp removed its list when it went off");
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west()));
            CompoundTag unlit = reloaded.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(!unlit.contains("Lights") && !unlit.contains("LightKinds"), "an unlit lamp saves no list");
            done.run();
        });
    }

    // ---- unloaded chunks: queued removal, no chunk load

    private static void hold(ServerLevel level, ChunkPos c, boolean on) {
        if (on) {
            level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        } else {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
    }

    /** Stone floor and air in one held chunk, a wall at local x {@code wallX} (or none). */
    private static void buildChunk(ServerLevel level, ChunkPos c, int wallX) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos floor = new BlockPos(c.getMinBlockX() + x, FLOOR, c.getMinBlockZ() + z);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 1; dy <= 10; dy++) {
                    level.setBlock(floor.above(dy), x == wallX ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * The lamp sits in chunk A, near its east edge, and its beam lands on a wall in chunk B. Each chunk is held on its own at
     * ticket level 33, so it is loaded and its other neighbours are not. B is let go and unloads; the lamp is switched off.
     * The cells in B are queued, and nothing loads B: not the cleanup, not processing the queue. When B is held again and
     * loads, the queue runs once at the end of that tick: every queued light is gone, a player's own light in B is still
     * there, and a light put back on a queued cell afterwards is left alone (the entry is gone).
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void aRemovalQueuedInAnUnloadedChunkRunsExactlyOnceWhenItLoadsAndLoadsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = site(9);
        ChunkPos a = new ChunkPos(s.x() >> 4, s.z() >> 4);
        ChunkPos b = new ChunkPos(a.x + 1, a.z);
        hold(level, a, true);
        hold(level, b, true);
        PendingLights pending = PendingLights.get(level);
        waitFor(helper, () -> level.hasChunk(a.x, a.z) && level.hasChunk(b.x, b.z), 600, "chunks A and B to load", () -> {
            helper.assertTrue(!level.hasChunk(b.x + 1, b.z) && !level.hasChunk(a.x, a.z + 1), "a held chunk's other neighbours are not loaded");
            buildChunk(level, a, -1);
            buildChunk(level, b, 6);
            pending.processLoaded(level); // anything a crashed earlier run left queued here (the test world is kept)
            BlockPos lamp = new BlockPos(a.getMinBlockX() + 12, FLOOR + 2, a.getMinBlockZ() + 8);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Set<BlockPos> inB = new HashSet<>();
            Set<BlockPos> inA = new HashSet<>();
            for (long key : be.placed().keySet()) {
                BlockPos p = BlockPos.of(key);
                (new ChunkPos(p).equals(b) ? inB : inA).add(p);
                helper.assertTrue(new ChunkPos(p).equals(a) || new ChunkPos(p).equals(b), "a light outside the loaded chunks at " + p);
            }
            helper.assertTrue(!inB.isEmpty(), "the beam lands in chunk B: " + be.placed().size() + " lights, " + inA.size() + " in A");
            BlockPos players = new BlockPos(b.getMinBlockX() + 2, FLOOR + 1, b.getMinBlockZ() + 1);
            level.setBlock(players, Blocks.LIGHT.defaultBlockState(), 2);

            // Let go of B. Its neighbour A is still held, so B stays in memory below full: unloaded to the game (it is not a
            // loaded chunk, nothing may read or write it), and it comes back without a load event. That is what a lamp's
            // neighbour chunk does when a player walks away; a real unload and load is the next test's.
            hold(level, b, false);
            waitFor(helper, () -> !level.hasChunk(b.x, b.z), 600, "chunk B to unload", () -> {
                power(level, lamp, false);
                helper.assertTrue(be.placed().isEmpty(), "the switched-off lamp keeps no list");
                for (BlockPos p : inA) {
                    helper.assertTrue(level.getBlockState(p).isAir(), "a light in the loaded chunk A was restored at once: " + p);
                }
                for (BlockPos p : inB) {
                    helper.assertTrue(pending.contains(p), "a light in unloaded chunk B is queued: " + p);
                }
                helper.assertTrue(pending.sizeIn(b) == inB.size(), "exactly B's lights are queued: " + pending.sizeIn(b) + " of " + inB.size());
                helper.assertTrue(!pending.contains(players), "the player's light is not queued");
                helper.assertTrue(!level.hasChunk(b.x, b.z) && level.getChunkSource().getChunkNow(b.x, b.z) == null,
                        "switching the lamp off did not load chunk B");
                helper.assertTrue(pending.processLoaded(level) == 0, "processing the queue with B unloaded restores nothing");
                helper.assertTrue(!level.hasChunk(b.x, b.z) && level.getChunkSource().getChunkNow(b.x, b.z) == null,
                        "processing the queue did not load chunk B");
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(!level.hasChunk(b.x, b.z), "nothing loaded chunk B in the 20 ticks after the cleanup");
                    long restoredBefore = PendingLights.restoredTotal();
                    hold(level, b, true);
                    waitFor(helper, () -> level.hasChunk(b.x, b.z), 600, "chunk B to load again", () ->
                    waitFor(helper, () -> !pending.contains(inB.iterator().next()), PendingLights.SWEEP_TICKS + 20, "the queue to run once B loaded", () -> {
                        for (BlockPos p : inB) {
                            helper.assertTrue(level.getBlockState(p).isAir(), "a queued light was removed when B loaded: " + p + " "
                                    + level.getBlockState(p));
                            helper.assertTrue(!pending.contains(p), "the queue entry is gone: " + p);
                        }
                        helper.assertTrue(PendingLights.restoredTotal() - restoredBefore == inB.size(),
                                "the queue removed each light once: " + (PendingLights.restoredTotal() - restoredBefore) + " of " + inB.size());
                        helper.assertTrue(level.getBlockState(players).is(Blocks.LIGHT), "the player's own light in B survived");
                        // A light put back on a queued cell afterwards is a new light; the queue does not run again.
                        BlockPos again = inB.iterator().next();
                        level.setBlock(again, Blocks.LIGHT.defaultBlockState(), 2);
                        helper.assertTrue(pending.processLoaded(level) == 0, "the queue does not run twice");
                        helper.assertTrue(level.getBlockState(again).is(Blocks.LIGHT), "a later light on a once-queued cell survives");
                        helper.assertTrue(pending.sizeIn(b) == 0, "the queue holds nothing of B any more");
                        level.setBlock(again, Blocks.AIR.defaultBlockState(), 2);
                        level.setBlock(players, Blocks.AIR.defaultBlockState(), 2);
                        settle(helper, level, 0, () -> {
                            hold(level, a, false);
                            hold(level, b, false);
                            helper.succeed();
                        });
                    }));
                });
            });
        });
    }

    /**
     * A lit lamp's chunk unloads (as every chunk does on a shutdown) and loads again. The unload does nothing: no light is
     * removed or queued, and no chunk is loaded by it (the lamp's chunk stays unloaded, its lit neighbour keeps its lights).
     * The list survives the real save and load, and switching off afterwards removes every light exactly.
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void unloadingALitLampsChunkTouchesNothingAndTheListSurvives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Far from every other fixture (2,000 blocks south), so once both chunks are let go nothing keeps them in memory.
        Site s = new Site(BASE + 200 * 11, BASE + 2000);
        ChunkPos a = new ChunkPos(s.x() >> 4, s.z() >> 4);
        ChunkPos b = new ChunkPos(a.x + 1, a.z);
        hold(level, a, true);
        hold(level, b, true);
        PendingLights pending = PendingLights.get(level);
        waitFor(helper, () -> level.hasChunk(a.x, a.z) && level.hasChunk(b.x, b.z), 600, "chunks A and B to load", () -> {
            buildChunk(level, a, -1);
            buildChunk(level, b, 6);
            BlockPos lamp = new BlockPos(a.getMinBlockX() + 12, FLOOR + 2, a.getMinBlockZ() + 8);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            power(level, lamp, true);
            Map<Long, Byte> placed = new HashMap<>(be.placed());
            Set<BlockPos> inB = new HashSet<>();
            placed.keySet().forEach(k -> {
                if (new ChunkPos(BlockPos.of(k)).equals(b)) {
                    inB.add(BlockPos.of(k));
                }
            });
            helper.assertTrue(!inB.isEmpty(), "the beam lands in chunk B");

            hold(level, a, false);
            hold(level, b, false);
            waitFor(helper, () -> !level.hasChunk(a.x, a.z) && be.isRemoved(), 2000, "the lamp's chunk to unload for real (saved, block entities dropped)",
                    () -> helper.runAfterDelay(40, () -> {
                helper.assertTrue(!level.hasChunk(a.x, a.z) && level.getChunkSource().getChunkNow(a.x, a.z) == null
                        && !level.hasChunk(b.x, b.z) && level.getChunkSource().getChunkNow(b.x, b.z) == null,
                        "nothing loaded the lamp's chunks again after they unloaded");
                for (long key : placed.keySet()) {
                    helper.assertTrue(!pending.contains(BlockPos.of(key)), "the unload queued nothing: " + BlockPos.of(key));
                }
                hold(level, a, true);
                hold(level, b, true);
                waitFor(helper, () -> level.hasChunk(a.x, a.z) && level.hasChunk(b.x, b.z)
                        && level.getBlockEntity(lamp) instanceof BeamLampBlockEntity, 600, "the lamp's chunks to load again", () -> {
                    BeamLampBlockEntity back = (BeamLampBlockEntity) level.getBlockEntity(lamp);
                    helper.assertTrue(back != be, "the lamp is a fresh block entity, loaded from the save");
                    helper.assertTrue(back.placed().equals(placed), "the list survived the save and load");
                    for (BlockPos p : inB) {
                        helper.assertTrue(level.getBlockState(p).is(Blocks.LIGHT), "the unload left the light in B alone: " + p);
                    }
                    power(level, lamp, false);
                    helper.assertTrue(back.placed().isEmpty(), "switched off, the reloaded lamp keeps no list");
                    for (long key : placed.keySet()) {
                        helper.assertTrue(level.getBlockState(BlockPos.of(key)).isAir(), "a light was removed at " + BlockPos.of(key));
                    }
                    for (long key : placed.keySet()) {
                        helper.assertTrue(!pending.contains(BlockPos.of(key)), "nothing was queued, both chunks are loaded: " + BlockPos.of(key));
                    }
                    level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
                    settle(helper, level, 0, () -> {
                        hold(level, a, false);
                        hold(level, b, false);
                        helper.succeed();
                    });
                });
            }));
        });
    }

    /**
     * A queued cell in a chunk nothing has loaded (a real load from disk or generation, which fires the chunk load event): the
     * entry waits without loading the chunk, and is processed and dropped once the chunk loads. Nothing else is touched.
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aQueuedCellInAChunkNothingHasLoadedWaitsAndIsDroppedWhenItLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos c = new ChunkPos((BASE + 200 * 13) >> 4, (BASE + 4000) >> 4);
        BlockPos far = new BlockPos(c.getMinBlockX() + 3, FLOOR + 1, c.getMinBlockZ() + 3);
        PendingLights pending = PendingLights.get(level);
        helper.assertTrue(!level.hasChunk(c.x, c.z), "the far chunk starts unloaded");
        pending.add(far, XenonBeam.AIR);
        helper.assertTrue(pending.processLoaded(level) == 0 && pending.contains(far), "the entry waits while its chunk is unloaded");
        helper.runAfterDelay(PendingLights.SWEEP_TICKS + 5, () -> {
            helper.assertTrue(!level.hasChunk(c.x, c.z) && level.getChunkSource().getChunkNow(c.x, c.z) == null,
                    "a sweep of the queue did not load the chunk");
            helper.assertTrue(pending.contains(far), "the entry still waits");
            hold(level, c, true);
            waitFor(helper, () -> level.hasChunk(c.x, c.z), 600, "the far chunk to load", () ->
                waitFor(helper, () -> !pending.contains(far), PendingLights.SWEEP_TICKS + 20, "the queue to run", () -> {
                    helper.assertTrue(pending.sizeIn(c) == 0, "the chunk's entries are gone");
                    settle(helper, level, 0, () -> {
                        hold(level, c, false);
                        helper.succeed();
                    });
                }));
        });
    }

    // ---- the planner at a chunk edge

    /**
     * A beam crossing into unloaded chunks (cells at x of {@code boundary} or beyond): the planner must never read an unloaded
     * cell (reading one fails the test), and a ray that reaches one lands nowhere.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void thePlannerNeverReadsAnUnloadedCell(GameTestHelper helper) {
        int boundary = BASE + 80;
        int wallX = boundary + 4;
        XenonBeam.World world = new XenonBeam.World() {
            @Override
            public boolean loaded(BlockPos pos) {
                return pos.getX() < boundary;
            }

            @Override
            public BlockState state(BlockPos pos) {
                if (pos.getX() >= boundary) {
                    throw new AssertionError("the planner read an unloaded cell at " + pos);
                }
                return pos.getY() <= FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean opaque(BlockState state, BlockPos pos) {
                return state.is(Blocks.STONE) || pos.getX() == wallX;
            }
        };
        BlockPos lamp = new BlockPos(boundary - 12, FLOOR + 3, BASE);
        Vec3 origin = new Vec3(lamp.getX() + 0.5, lamp.getY() + 0.625, lamp.getZ() + 0.5).add(0.45, 0.0, 0.0);
        Map<Long, Byte> planned = XenonBeam.plan(world, lamp, origin, new Vec3(1, -0.25, 0), 32, 15, 2, Map.of());
        helper.assertTrue(!planned.isEmpty(), "the beam lands on the loaded floor");
        for (long key : planned.keySet()) {
            helper.assertTrue(BlockPos.of(key).getX() < boundary, "a light was planned in the unloaded chunk at " + BlockPos.of(key));
        }
        // Level, the beam would land on the wall beyond the boundary: nothing is planned there and nothing is read.
        Map<Long, Byte> level = XenonBeam.plan(world, lamp, origin, new Vec3(1, 0, 0), 32, 5, 2, Map.of());
        for (long key : level.keySet()) {
            helper.assertTrue(BlockPos.of(key).getX() < boundary, "a light crossed the edge at " + BlockPos.of(key));
        }
        helper.assertTrue(XenonBeam.plan(world, lamp, origin, new Vec3(0, 1, 0), 32, 15, 2, Map.of()).isEmpty(),
                "a beam into the open sky lands nowhere");
        helper.succeed();
    }

    // ---- water

    /**
     * A beam through a pool of still water lands on its floor: the lights there are waterlogged and the water stays. Flowing
     * water in the beam is never used (a light would turn it into a source).
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void lightsInStillWaterAreWaterloggedAndFlowingWaterIsNeverUsed(GameTestHelper helper) {
        onSite(helper, 5, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(5);
            for (int dx = 1; dx <= 20; dx++) {
                for (int dy = 1; dy <= 6; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        level.setBlock(s.at(dx, dy, dz), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
            // A strip of flowing water on the floor, right where the beam lands.
            Set<BlockPos> flowing = new HashSet<>();
            for (int dx = 6; dx <= 20; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = s.at(dx, 1, dz);
                    level.setBlock(p, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3), 2);
                    flowing.add(p);
                }
            }
            BlockPos lamp = s.at(0, 4, 0);
            level.setBlock(lamp.west(), Blocks.STONE.defaultBlockState(), 2);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            BeamLampBlockEntity be = placeFloodlight(level, lamp, Direction.EAST);
            be.stepAim(true);
            be.stepAim(true);
            be.stepAim(true);
            be.stepAim(true);
            be.stepAim(true);
            be.stepAim(true); // from level round to -30 degrees: into the pool floor
            power(level, lamp, true);
            int wet = 0;
            for (var entry : be.placed().entrySet()) {
                BlockPos p = BlockPos.of(entry.getKey());
                helper.assertTrue(!flowing.contains(p), "a light went into flowing water at " + p);
                if (before.get(p) != null && before.get(p).is(Blocks.WATER)) {
                    wet++;
                    BlockState now = level.getBlockState(p);
                    helper.assertTrue(now.is(Blocks.LIGHT) && now.getValue(LightBlock.WATERLOGGED), "a light in water is waterlogged at " + p);
                    helper.assertTrue(level.getFluidState(p).is(Fluids.WATER) && level.getFluidState(p).isSource(), "the water is still there at " + p);
                    helper.assertTrue(entry.getValue() == XenonBeam.WATER, "the lamp remembers it was water at " + p);
                }
            }
            helper.assertTrue(wet > 0, "some lights went into the still water: " + be.placed().size() + " lights");
            for (BlockPos p : flowing) {
                helper.assertTrue(level.getBlockState(p).equals(before.get(p)), "flowing water was changed at " + p);
            }

            power(level, lamp, false);
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(lamp, lamp.west()));
            done.run();
        });
    }

    // ---- the high-bay, the HPS panel and the cap

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aHighBayLightsTheFloorOfARoomUnderItWithinTheCap(GameTestHelper helper) {
        onSite(helper, 6, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(6);
            BlockPos bay = s.at(20, HEIGHT, 0);
            BeamLampBlockEntity be = placeFixed(level, BwrBlocks.XENON_HIGH_BAY.get(), bay, Direction.DOWN);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            power(level, bay, true);
            int n = be.placed().size();
            helper.assertTrue(n >= 40 && n <= XenonBeam.MAX_LIGHTS, "the high bay lights a room within the cap: " + n);
            int onFloor = 0;
            for (long key : be.placed().keySet()) {
                BlockPos p = BlockPos.of(key);
                helper.assertTrue(p.getY() < bay.getY(), "a high-bay light is below the fixture at " + p);
                if (p.getY() == FLOOR + 1) {
                    onFloor++;
                }
            }
            helper.assertTrue(onFloor * 2 > n, "most high-bay lights sit on the floor: " + onFloor + " of " + n);
            checkAllWereAir(helper, before, be.placed());
            Set<Long> placed = new HashSet<>(be.placed().keySet());
            power(level, bay, false);
            checkGone(helper, level, placed);
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(bay, bay.west()));
            done.run();
        });
    }

    /**
     * The HPS panel floods the floor under it within the cap. Its warm-up steps the panel and every projected light through
     * levels 4, 8, 12 and 15 (in place: the same cells), and switching it off takes every light away.
     */
    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void anHpsPanelWarmsUpInFourStepsAndCleansUpExactly(GameTestHelper helper) {
        onSite(helper, 12, done -> {
            ServerLevel level = helper.getLevel();
            Site s = site(12);
            BlockPos panel = s.at(20, HEIGHT, 0);
            BeamLampBlockEntity be = placeFixed(level, BwrBlocks.HPS_FLAT_PANEL.get(), panel, Direction.DOWN);
            Map<BlockPos, BlockState> before = snapshot(level, s);
            power(level, panel, true);
            Set<Long> cells = new HashSet<>(be.placed().keySet());
            helper.assertTrue(cells.size() >= 40 && cells.size() <= XenonBeam.MAX_LIGHTS, "the panel floods the floor: " + cells.size());
            checkAllWereAir(helper, before, be.placed());
            int[] expected = {4, 8, 12, 15};
            HpsFlatPanelBlock block = (HpsFlatPanelBlock) BwrBlocks.HPS_FLAT_PANEL.get();
            for (int stage = 0; stage < 4; stage++) {
                BlockState state = level.getBlockState(panel);
                helper.assertTrue(state.getValue(HpsFlatPanelBlock.WARMUP) == stage, "warm-up stage " + stage + ": " + state);
                helper.assertTrue(state.getLightEmission() == expected[stage], "the panel glows at " + expected[stage]);
                for (long key : be.placed().keySet()) {
                    helper.assertTrue(level.getBlockState(BlockPos.of(key)).getValue(LightBlock.LEVEL) == expected[stage],
                            "a projected light is at level " + expected[stage] + " at stage " + stage);
                }
                helper.assertTrue(be.placed().keySet().equals(cells), "the warm-up re-levels the same cells");
                block.tick(state, level, panel, RandomSource.create(1)); // the scheduled warm-up tick
            }
            helper.assertTrue(level.getBlockState(panel).getValue(HpsFlatPanelBlock.WARMUP) == 3, "the panel stays at full");
            power(level, panel, false);
            checkGone(helper, level, cells);
            helper.assertTrue(level.getBlockState(panel).getValue(HpsFlatPanelBlock.WARMUP) == 0, "off, the warm-up resets");
            checkExactlyTheLights(helper, level, s, Map.of());
            checkRestored(helper, level, before, Set.of(panel, panel.west()));
            done.run();
        });
    }
}
