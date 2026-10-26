package dev.bwr.mod.devtest;

import dev.bwr.core.accident.HeadMissile;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.accident.HeadMissileLauncher;
import dev.bwr.mod.accident.ReactorHeadEntity;
import dev.bwr.mod.accident.ShieldPlugConcreteBlock;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.CraneAnchorBlock;
import dev.bwr.mod.refuelling.PlugSectionEntity;
import dev.bwr.mod.refuelling.PlugSeamMarkerItem;
import dev.bwr.mod.refuelling.PlugSections;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shield plug lifting with the reactor building crane (BUILDING-CRANE.md, "Lifting the shield plugs"): crane anchors that stand only on plug tops, seams that
 * split a plug into sections, PLUG OFF taking exactly one section off the well and setting it down state for state, PLUG ON putting it back, the rated
 * capacity and the four-anchor rule, the head that will not lift while a plug covers the well, a laydown that is never cleared by replacing a block, saves
 * and reloads in the middle of a lift, a crane or runway broken under a hanging section, the head missile and the shielding with the plug off and on, and
 * an old crane with no plug data. Own namespace ({@code bwrplug}), batch {@code plugs}; {@code -PbwrGameTestNamespaces=bwrplug} runs just these. The crane is
 * stepped a tick at a time as its scheduled block tick would ({@link CraneFixture}).
 *
 * <p>The refuel floor of every fixture is a slab of stone one block under the rails' side of the hall (z O.z-13..O.z-4, x O.x-3..O.x+20, top at the
 * level of the first row above the vessel); the plug stands over the well, the head stand and the laydown are on the floor.
 */
@GameTestHolder("bwrplug")
@PrefixGameTestTemplate(false)
public final class PlugLiftTests {

    private PlugLiftTests() {
    }

    private static final int RAIL_DY = 20;

    private static BlockPos at(int i) {
        return new BlockPos(86000 + 300 * i, 190, 86000);
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    /** A crane fixture with a refuel floor and helpers to build a plug over its well. */
    private static final class Rig {
        final CraneFixture f;
        final ServerLevel level;
        final BlockPos o;
        final VesselAppearance.Envelope env;
        final List<BlockPos> built = new ArrayList<>();

        Rig(ServerLevel level, BlockPos o) throws Exception {
            this.level = level;
            this.o = o;
            this.f = CraneFixture.build(level, o, RAIL_DY);
            this.env = f.reactor().vesselEnvelope();
            if (env == null) {
                throw new IllegalStateException("the fixture reactor has no envelope");
            }
            for (int x = -3; x <= 20; x++) {
                for (int z = -13; z <= -4; z++) {
                    level.setBlock(o.offset(x, 8, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }

        /** The first row above the vessel, the plug's base, at the west side of its footprint. */
        BlockPos wellBase() {
            return new BlockPos(env.min().getX() + 1, env.max().getY() + 1, env.min().getZ() + 2);
        }

        /** A block of plug tier {@code tier} (1..3) in every cell of a box. */
        void plug(BlockPos min, int sx, int sy, int sz, int tier) {
            Block block = tier == 3 ? BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get()
                    : tier == 2 ? BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get() : BwrBlocks.SHIELD_PLUG_CONCRETE.get();
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        BlockPos p = min.offset(x, y, z);
                        level.setBlock(p, block.defaultBlockState(), 2);
                        built.add(p);
                    }
                }
            }
        }

        /** A crane anchor on each of the four top corners of a box that has just been built. */
        void fourAnchors(BlockPos min, int sx, int sy, int sz) {
            for (int x : new int[]{0, sx - 1}) {
                for (int z : new int[]{0, sz - 1}) {
                    anchor(min.offset(x, sy, z));
                }
            }
        }

        void anchor(BlockPos p) {
            level.setBlock(p, BwrBlocks.CRANE_ANCHOR.get().defaultBlockState(), 2);
            built.add(p);
        }

        /** Marks the seam between two columns of a box: every joint across the plane between x = {@code x} and x + 1. */
        void seamEastOf(BlockPos min, int x, int sy, int sz) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    BlockPos p = min.offset(x, y, z);
                    if (!Boolean.TRUE.equals(PlugSeamMarkerItem.toggle(level, p, Direction.EAST))) {
                        throw new IllegalStateException("no seam at " + p);
                    }
                }
            }
        }

        /** Two 3 x 2 x 3 pieces side by side over the well, split by a seam, each with its four anchors: A to the west (nearer the well's middle). */
        BlockPos[] twoSections() {
            BlockPos a = wellBase(), b = a.offset(3, 0, 0);
            plug(a, 3, 2, 3, 1);
            plug(b, 3, 2, 3, 1);
            seamEastOf(a, 2, 2, 3);
            fourAnchors(a, 3, 2, 3);
            fourAnchors(b, 3, 2, 3);
            return new BlockPos[]{a, b};
        }

        /** Every block state in a box (air included). */
        Map<BlockPos, BlockState> snapshot(BlockPos min, int sx, int sy, int sz) {
            Map<BlockPos, BlockState> m = new LinkedHashMap<>();
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        m.put(new BlockPos(x, y, z), level.getBlockState(min.offset(x, y, z)));
                    }
                }
            }
            return m;
        }

        List<PlugSectionEntity> entities() {
            return level.getEntitiesOfClass(PlugSectionEntity.class, new AABB(o.offset(-60, -20, -60).getCenter(), o.offset(80, 60, 60).getCenter()));
        }

        BuildingCraneBlockEntity crane() {
            return f.crane;
        }

        void run(GameTestHelper h) {
            int n = f.runCrane(120_000);
            h.assertTrue(!f.crane.busy(), "the crane never finished: " + f.crane.phase() + " " + english(f.crane.haltMessage()));
            h.assertTrue(f.crane.phase() == Phase.IDLE, "the crane halted: " + english(f.crane.haltMessage()));
            h.assertTrue(n > 0, "the crane ran");
        }

        void dispose() {
            try {
                for (PlugSectionEntity e : entities()) {
                    e.discard();
                }
                for (Entity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(o.offset(-60, -20, -60).getCenter(), o.offset(80, 60, 60).getCenter()))) {
                    e.discard();
                }
                for (BlockPos p : BlockPos.betweenClosed(o.offset(-4, 9, -14), o.offset(21, 16, 10))) {
                    if (!level.getBlockState(p).isAir() && !level.getBlockState(p).is(BwrBlocks.HEAD_STAND.get())) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                for (int x = -3; x <= 20; x++) {
                    for (int z = -13; z <= -4; z++) {
                        level.setBlock(o.offset(x, 8, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            } finally {
                f.dispose();
            }
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} ten ticks after the fixtures were built, then disposes them and succeeds. The wait matters: an entity added to a chunk that has only
     * just been forced is not visible to the level until the chunk is entity-ticking, and the crane (rightly) waits for its section's entity.
     */
    private static void later(GameTestHelper h, Body body, Rig... rigs) {
        h.runAfterDelay(10, () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                disposeAll(rigs);
                throw e;
            } catch (Exception e) {
                disposeAll(rigs);
                throw new IllegalStateException(e);
            }
            disposeAll(rigs);
            h.succeed();
        });
    }

    private static void disposeAll(Rig... rigs) {
        for (Rig r : rigs) {
            r.dispose();
        }
    }

    private static boolean allAir(ServerLevel level, BlockPos min, Map<BlockPos, BlockState> shape) {
        for (var e : shape.entrySet()) {
            if (!e.getValue().isAir() && !level.getBlockState(min.offset(e.getKey())).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameStates(ServerLevel level, BlockPos min, Map<BlockPos, BlockState> shape) {
        for (var e : shape.entrySet()) {
            if (level.getBlockState(min.offset(e.getKey())) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static long solid(Map<BlockPos, BlockState> shape) {
        return shape.values().stream().filter(s -> !s.isAir()).count();
    }

    private static void detensionAll(GameTestHelper h, CraneFixture f) {
        h.assertTrue(f.tensioner.start(true) == null, "the tensioner starts");
        f.runTensioner(20_000, true);
        h.assertTrue(!f.tensioner.running() && f.reactor().studsOff() == f.reactor().studTotal(), "every stud is off: " + f.reactor().studsOff());
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1. Anchors
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void craneAnchorsStandOnTheTopOfShieldPlugBlocksAndNothingElse(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(0));
        try {
            var player = FakePlayerFactory.getMinecraft(r.level);
            BlockPos plug = r.o.offset(2, 9, -8), stone = r.o.offset(5, 9, -8), lead = r.o.offset(8, 9, -8);
            r.level.setBlock(plug, BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState(), 2);
            r.level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
            r.level.setBlock(lead, BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState(), 2);
            ItemStack anchors = new ItemStack(BwrItems.CRANE_ANCHOR.get(), 8);
            // on top of a plug block (any tier): placed
            for (BlockPos p : new BlockPos[]{plug, lead}) {
                var res = BwrItems.CRANE_ANCHOR.get().place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, anchors,
                        new BlockHitResult(Vec3.atCenterOf(p).add(0, 0.5, 0), Direction.UP, p, false)));
                h.assertTrue(res.consumesAction() && r.level.getBlockState(p.above()).is(BwrBlocks.CRANE_ANCHOR.get()), "an anchor stands on the plug block at " + p);
            }
            // on stone, on the side of a plug block, in the air: refused
            var onStone = BwrItems.CRANE_ANCHOR.get().place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, anchors,
                    new BlockHitResult(Vec3.atCenterOf(stone).add(0, 0.5, 0), Direction.UP, stone, false)));
            h.assertTrue(!onStone.consumesAction() && r.level.getBlockState(stone.above()).isAir(), "an anchor was placed on stone");
            BlockPos beside = plug.east();
            var onSide = BwrItems.CRANE_ANCHOR.get().place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, anchors,
                    new BlockHitResult(Vec3.atCenterOf(plug).add(0.5, 0, 0), Direction.EAST, plug, false)));
            h.assertTrue(!onSide.consumesAction() && r.level.getBlockState(beside).isAir(), "an anchor was placed on the side of a plug block, in the air");
            h.assertTrue(CraneAnchorBlockCanSurvive(r.level, plug.above()) && !CraneAnchorBlockCanSurvive(r.level, stone.above()), "standsOnPlug follows the block below");
            // the plug under an anchor goes: the anchor goes with it, and nothing else does
            r.level.setBlock(lead, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(r.level.getBlockState(lead.above()).isAir(), "an anchor stands over nothing");
            h.assertTrue(r.level.getBlockState(plug.above()).is(BwrBlocks.CRANE_ANCHOR.get()), "the other anchor was not touched");
        } finally {
            r.dispose();
        }
        h.succeed();
    }

    private static boolean CraneAnchorBlockCanSurvive(ServerLevel level, BlockPos pos) {
        return CraneAnchorBlock.standsOnPlug(level, pos);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2. Seams
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aSeamSplitsAPlugIntoTwoSectionsAndTheMarkerTogglesAndClearsIt(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(1));
        try {
            BlockPos a = r.wellBase(), b = a.offset(3, 0, 0);
            r.plug(a, 3, 2, 3, 1);
            r.plug(b, 3, 2, 3, 2);
            r.fourAnchors(a, 3, 2, 3);
            r.fourAnchors(b, 3, 2, 3);
            // without a seam the two pieces are one section: 36 blocks of two tiers, eight anchors
            var one = PlugSections.survey(r.level, a);
            h.assertTrue(one.section() != null && one.section().blockCount() == 36 && one.section().anchors().size() == 8,
                    "one section of 36 blocks and eight anchors: " + (one.section() == null ? english(one.problem()) : one.section().blockCount()));
            h.assertTrue(one.section().tiers()[0] == 18 && one.section().tiers()[1] == 18 && one.section().tiers()[2] == 0, "18 of tier 1 and 18 of tier 2");
            h.assertTrue(Math.abs(one.section().massT() - (18 * 2.4 + 18 * 2.9)) < 1e-9, "mass 95.4 t: " + one.section().massT());
            // the marker: a click near the east edge of the top face of a block of A marks the joint with B
            Direction edge = PlugSeamMarkerItem.edgeOf(Direction.UP, new Vec3(0.93, 1.0, 0.5));
            h.assertTrue(edge == Direction.EAST, "near the east edge: " + edge);
            h.assertTrue(PlugSeamMarkerItem.edgeOf(Direction.NORTH, new Vec3(0.5, 0.96, 0.0)) == Direction.UP, "near the top edge of a north face");
            h.assertTrue(PlugSeamMarkerItem.edgeOf(Direction.EAST, new Vec3(1.0, 0.5, 0.04)) == Direction.NORTH, "near the north edge of an east face");
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) {
                    h.assertTrue(PlugSeamMarkerItem.toggle(r.level, a.offset(2, y, z), edge) == Boolean.TRUE, "seam marked");
                }
            }
            BlockState left = r.level.getBlockState(a.offset(2, 0, 0)), right = r.level.getBlockState(b);
            h.assertTrue(left.getValue(ShieldPlugConcreteBlock.SEAM_EAST) && right.getValue(ShieldPlugConcreteBlock.SEAM_WEST),
                    "both blocks carry the seam, on their own faces");
            var sa = PlugSections.survey(r.level, a).section();
            var sb = PlugSections.survey(r.level, b).section();
            h.assertTrue(sa != null && sb != null && sa.blockCount() == 18 && sb.blockCount() == 18 && sa.anchors().size() == 4 && sb.anchors().size() == 4,
                    "two sections of 18 blocks and four anchors each");
            h.assertTrue(sa.tiers()[0] == 18 && sb.tiers()[1] == 18, "A is tier 1 and B tier 2");
            h.assertTrue(sa.max().getX() < sb.min().getX(), "A lies west of B");
            // a seam hanging on only one block's state still splits (the other side was edited by hand)
            r.level.setBlock(a.offset(2, 0, 1), left.setValue(ShieldPlugConcreteBlock.SEAM_EAST, false), 2);
            h.assertTrue(PlugSections.survey(r.level, a).section().blockCount() == 18, "one block's seam is enough to split the joint");
            r.level.setBlock(a.offset(2, 0, 1), left.setValue(ShieldPlugConcreteBlock.SEAM_EAST, true), 2);
            // a stepped plug is split at a seam on a floor too: a seam across a step (vertical joint) separates the layers
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    PlugSeamMarkerItem.toggle(r.level, b.offset(x, 0, z), Direction.UP);
                }
            }
            h.assertTrue(PlugSections.survey(r.level, b).section().blockCount() == 9 && PlugSections.survey(r.level, b.above()).section().blockCount() == 9,
                    "a seam between the two layers of B splits it into two layers");
            // the marker is its own undo: the same place again removes the seam; sneak clears every seam on a block
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    h.assertTrue(PlugSeamMarkerItem.toggle(r.level, b.offset(x, 0, z), Direction.UP) == Boolean.FALSE, "the second click removes the seam");
                }
            }
            h.assertTrue(PlugSections.survey(r.level, b).section().blockCount() == 18, "B is whole again");
            int cleared = PlugSeamMarkerItem.clearSeams(r.level, a.offset(2, 0, 0));
            h.assertTrue(cleared == 1 && !ShieldPlugConcreteBlock.hasSeam(r.level.getBlockState(a.offset(2, 0, 0))) && !ShieldPlugConcreteBlock.hasSeam(r.level.getBlockState(b)),
                    "clearing a block's seams clears its side and the neighbour's: " + cleared);
            // a seam goes between two plug blocks
            h.assertTrue(PlugSeamMarkerItem.toggle(r.level, a, Direction.WEST) == null, "no plug block on that side: no seam");
            h.assertTrue(!ShieldPlugConcreteBlock.seamed(r.level.getBlockState(a), Direction.WEST), "the block was not changed");
            // a seam is only a block state: strength and shielding do not change
            h.assertTrue(r.level.getBlockState(a).getBlock().getExplosionResistance() == ShieldPlugConcreteBlock.RESISTANCE_1, "blast resistance is unchanged");
        } finally {
            r.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // 3 and 4. PLUG OFF and PLUG ON
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void plugOffLiftsExactlyOneSectionOffTheWellAndSetsItDownExactlyOnTheFloor(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(2));
        later(h, () -> {
            BlockPos[] sec = r.twoSections();
            BlockPos a = sec[0], b = sec[1];
            var shapeA = r.snapshot(a, 3, 3, 3);
            var shapeB = r.snapshot(b, 3, 3, 3);
            h.assertTrue(solid(shapeA) == 22 && solid(shapeB) == 22, "fixture: 18 plug blocks and 4 anchors each: " + solid(shapeA));
            var crane = r.crane();
            Component why = crane.queuePlug(true, null);
            h.assertTrue(why == null, "PLUG OFF queued: " + english(why));
            var plan = crane.plugPlan();
            h.assertTrue(plan != null && plan.homeMin().equals(a) && plan.blocks() == 18 && Math.abs(plan.massT() - 18 * 2.4) < 1e-9,
                    "the section nearest the well's middle is taken: " + (plan == null ? "no plan" : plan.homeMin() + " " + plan.blocks()));
            BlockPos lay = plan.layMin();
            h.assertTrue(!PlugSections.coversWell(r.env, new ArrayList<>(List.of(lay, lay.offset(2, 0, 2))), BlockPos.ZERO), "the laydown is not over the well");
            h.assertTrue(allAir(r.level, lay, shapeA), "the laydown is clear");
            h.assertTrue(r.level.getBlockState(lay.below()).is(Blocks.STONE), "the section rests on the floor");

            // on the hook: the blocks are out of the world and held by one entity, which hangs under the hook
            r.f.runCraneUntil(Phase.TRANSIT, 120_000);
            h.assertTrue(crane.carriedPlug() && crane.holding(), "the hook holds the section");
            h.assertTrue(allAir(r.level, a, shapeA) && solid(r.snapshot(a, 3, 3, 3)) == 0, "the section's blocks have left the well");
            h.assertTrue(sameStates(r.level, b, shapeB), "the other section is untouched");
            h.assertTrue(r.entities().size() == 1, "one entity holds the section: " + r.entities().size());
            PlugSectionEntity e = r.entities().get(0);
            h.assertTrue(e.cells().size() == 22 && e.getUUID().equals(crane.plugEntityId()), "it holds 22 blocks and the crane knows it");
            var tip = crane.tip();
            h.assertTrue(Math.abs(e.getX() - tip.x()) < 1e-9 && Math.abs(e.getZ() - tip.z()) < 1e-9 && Math.abs(e.getY() - (tip.y() - plan.hookAboveBase())) < 1e-9,
                    "the entity hangs under the hook: " + e.position() + " vs " + tip);
            h.assertTrue(crane.carriedSummary().contains("18 blocks") && crane.carriedSummary().contains("43.2 t"), crane.carriedSummary());
            // the head cannot be lifted away from the hook's load, and nothing else is queued meanwhile
            h.assertTrue(crane.queueHead(true) != null, "the crane is busy");
            // it moves with the crane: a hundred ticks later it is still under the hook
            for (int i = 0; i < 100; i++) {
                crane.scheduledTick(r.level);
            }
            tip = crane.tip();
            e = r.entities().get(0);
            h.assertTrue(Math.abs(e.getX() - tip.x()) < 1e-9 && Math.abs(e.getY() - (tip.y() - plan.hookAboveBase())) < 1e-9, "still under the hook after moving");
            r.run(h);

            // set down: every block is where it should be, state for state, and the entity is gone
            h.assertTrue(sameStates(r.level, lay, shapeA), "the section lies on the laydown exactly as it stood on the well");
            h.assertTrue(allAir(r.level, a, shapeA), "nothing is left on the well");
            h.assertTrue(sameStates(r.level, b, shapeB), "the other section is untouched");
            h.assertTrue(r.entities().isEmpty() && !crane.holding() && crane.plugPlan() == null, "the entity is gone and the hook is empty");
            h.assertTrue(crane.plugsLaid() == 1 && crane.laidSections().get(0).lay().equals(lay) && crane.laidSections().get(0).home().equals(a), "the laydown book has it");
            h.assertTrue(r.f.phases.contains(Phase.LATCH) && r.f.phases.contains(Phase.TRANSIT) && r.f.phases.contains(Phase.RELEASE), "the move went through its phases");
            // the second section is taken next (the first is out of the way, the second is now the nearest)
            h.assertTrue(crane.queuePlug(true, null) == null, "the next PLUG OFF queued: ");
            r.run(h);
            h.assertTrue(allAir(r.level, b, shapeB) && crane.plugsLaid() == 2, "the second section is off too");
        }, r);
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void plugOnPutsTheSectionBackExactlyStateForState(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(3));
        later(h, () -> {
            BlockPos[] sec = r.twoSections();
            BlockPos a = sec[0];
            // make the section interesting: a tier mix and a seam on its own top
            r.level.setBlock(a.offset(1, 1, 1), BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState(), 2);
            r.level.setBlock(a.offset(0, 0, 0), BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState()
                    .setValue(ShieldPlugConcreteBlock.SEAM_NORTH, false), 2);
            var before = r.snapshot(a, 3, 3, 3);
            var crane = r.crane();
            h.assertTrue(crane.queuePlug(false, null) != null, "PLUG ON with nothing laid down is refused");
            h.assertTrue(crane.queuePlug(true, null) == null, "PLUG OFF queued");
            r.run(h);
            BlockPos lay = crane.laidSections().get(0).lay();
            h.assertTrue(sameStates(r.level, lay, before) && allAir(r.level, a, before), "off");
            Component why = crane.queuePlug(false, null);
            h.assertTrue(why == null, "PLUG ON queued: " + english(why));
            r.run(h);
            h.assertTrue(sameStates(r.level, a, before), "back on the well, state for state (tiers, seams and anchors)");
            h.assertTrue(allAir(r.level, lay, before), "the laydown is empty again");
            h.assertTrue(crane.plugsLaid() == 0 && r.entities().isEmpty() && crane.plugPlan() == null, "nothing laid, no entity, no plan");
            h.assertTrue(PlugSections.survey(r.level, a).section().blockCount() == 18, "the section is whole and seamed as before");
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 5 and 6. Capacity and the four anchors
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aSectionOverTheCranesRatingIsRefusedAndASeamBringsItUnderIt(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(4));
        later(h, () -> {
            BlockPos min = r.o.offset(8, 9, -12);
            r.plug(min, 8, 1, 8, 1);
            r.fourAnchors(min, 8, 1, 8);
            var crane = r.crane();
            Component why = crane.queuePlug(true, null);
            String text = english(why);
            h.assertTrue(why != null && text.contains("153.6 t") && text.contains("125 t") && text.contains("seam"), "refused over capacity, with the reason and a seam: " + text);
            h.assertTrue(!crane.busy() && crane.plugPlan() == null && r.entities().isEmpty() && PlugSections.survey(r.level, min).section().blockCount() == 64,
                    "nothing moved");
            // a seam cuts it into two halves of 32 blocks (76.8 t); each half is given its four anchors
            for (int z = 0; z < 8; z++) {
                PlugSeamMarkerItem.toggle(r.level, min.offset(3, 0, z), Direction.EAST);
            }
            for (int z : new int[]{0, 7}) {
                r.anchor(min.offset(3, 1, z));
                r.anchor(min.offset(4, 1, z));
            }
            var west = PlugSections.survey(r.level, min).section();
            var east = PlugSections.survey(r.level, min.offset(7, 0, 7)).section();
            h.assertTrue(west != null && east != null && west.blockCount() == 32 && east.blockCount() == 32 && Math.abs(west.massT() - 76.8) < 1e-9
                    && west.anchors().size() == 4 && east.anchors().size() == 4, "two halves of 32 blocks, 76.8 t, four anchors each");
            Component ok = crane.queuePlug(true, null);
            h.assertTrue(ok == null, "a half under the rating is taken: " + english(ok));
            r.run(h);
            h.assertTrue(crane.plugsLaid() == 1 && r.entities().isEmpty(), "and set down");
        }, r);
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void thirtySixBlocksOfTheHeaviestTierAreOverTheRatingAndThirtyFiveAreNot(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(16));
        later(h, () -> {
            BlockPos min = r.o.offset(8, 9, -12);
            r.plug(min, 6, 1, 6, 3);
            r.fourAnchors(min, 6, 1, 6);
            Component why = r.crane().queuePlug(true, null);
            h.assertTrue(why != null && english(why).contains("126.0 t"), "36 tier 3 blocks are 126 t, over the 125 t rating: " + english(why));
            // taking one block off the corner (and its anchor) leaves 35 blocks, 122.5 t, and a section the crane takes
            r.level.setBlock(min.offset(5, 1, 5), Blocks.AIR.defaultBlockState(), 2);
            r.level.setBlock(min.offset(5, 0, 5), Blocks.AIR.defaultBlockState(), 2);
            r.anchor(min.offset(5, 1, 4));
            var s = PlugSections.survey(r.level, min).section();
            h.assertTrue(s != null && s.blockCount() == 35 && Math.abs(s.massT() - 122.5) < 1e-9 && s.anchors().size() == 4, "35 blocks, 122.5 t, four anchors");
            Component ok = r.crane().queuePlug(true, null);
            h.assertTrue(ok == null, "122.5 t is under the rating: " + english(ok));
            r.run(h);
        }, r);
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aSectionNeedsExactlyFourAnchorsToBeLifted(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(5));
        later(h, () -> {
            BlockPos min = r.wellBase();
            r.plug(min, 3, 2, 3, 1);
            var crane = r.crane();
            Component why = crane.queuePlug(true, null);
            h.assertTrue(why != null && english(why).contains("no shield plug section"), "no anchors: " + english(why));
            r.anchor(min.offset(0, 2, 0));
            r.anchor(min.offset(2, 2, 0));
            r.anchor(min.offset(0, 2, 2));
            why = crane.queuePlug(true, null);
            h.assertTrue(why != null && english(why).contains("needs 4 anchors, has 3"), "three anchors: " + english(why));
            r.anchor(min.offset(2, 2, 2));
            r.anchor(min.offset(1, 2, 1));
            why = crane.queuePlug(true, null);
            h.assertTrue(why != null && english(why).contains("needs 4 anchors, has 5"), "five anchors: " + english(why));
            r.level.setBlock(min.offset(1, 2, 1), Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(r.entities().isEmpty() && PlugSections.survey(r.level, min).section().blockCount() == 18, "nothing moved while it was refused");
            why = crane.queuePlug(true, null);
            h.assertTrue(why == null, "four anchors: queued: " + english(why));
            r.run(h);
            // an anchor can name its section
            r.level.setBlock(min.offset(1, 2, 1), Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(crane.queuePlug(false, null) == null, "PLUG ON");
            r.run(h);
            h.assertTrue(crane.queuePlug(true, min.offset(0, 2, 0)) == null, "PLUG OFF naming an anchor of the section");
            h.assertTrue(crane.plugPlan() != null && crane.plugPlan().homeMin().equals(min), "that section");
            r.run(h);
            h.assertTrue(crane.queuePlug(true, min.offset(1, 2, 2)) != null && english(crane.queuePlug(true, min.offset(1, 2, 2))).contains("no crane anchor"),
                    "an anchor that is not there");
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 7. The head and the plug over the well
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void liftHeadIsRefusedWhileAPlugCoversTheWellAndAllowedOnceItIsOff(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(6));
        later(h, () -> {
            BlockPos min = r.wellBase();
            r.plug(min, 3, 2, 3, 2);
            r.fourAnchors(min, 3, 2, 3);
            var crane = r.crane();
            detensionAll(h, r.f);
            h.assertTrue(r.f.reactor().headLiftBlocked() == null, "fixture: the head is liftable by the studs and the pressure");
            Component why = crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("shield plug still covers the reactor well") && english(why).contains("PLUG OFF"),
                    "LIFT HEAD refused with the plug over the well: " + english(why));
            h.assertTrue(r.f.reactor().vesselState() == VesselState.SHUTDOWN && !crane.busy(), "nothing moved");
            h.assertTrue(PlugSections.plugOverWell(r.level, r.env, crane.site().railY).size() == 18, "the survey counts the plug over the well: 18 blocks");
            h.assertTrue(crane.queuePlug(true, null) == null, "PLUG OFF");
            r.run(h);
            h.assertTrue(PlugSections.plugOverWell(r.level, r.env, crane.site().railY).isEmpty(), "no plug over the well any more");
            why = crane.queueHead(true);
            h.assertTrue(why == null, "LIFT HEAD allowed with the plug off: " + english(why));
            r.run(h);
            h.assertTrue(r.f.reactor().vesselState() == VesselState.REFUELING && r.f.standBe.holdsHead(), "the head is on its stand");
            // the section cannot go back over the open well while the head is off
            why = crane.queuePlug(false, null);
            h.assertTrue(why != null && english(why).contains("head is off"), "PLUG ON refused with the head off: " + english(why));
            // and the head cannot be set back with the plug put back by hand
            r.level.setBlock(min, BwrBlocks.SHIELD_PLUG_CONCRETE.get().defaultBlockState(), 2);
            why = crane.queueHead(false);
            h.assertTrue(why != null && english(why).contains("shield plug still covers"), "SET HEAD refused with a plug block over the well: " + english(why));
            r.level.setBlock(min, Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(crane.queueHead(false) == null, "SET HEAD allowed");
            r.run(h);
            h.assertTrue(crane.queuePlug(false, null) == null, "PLUG ON allowed once the head is back");
            r.run(h);
            h.assertTrue(PlugSections.plugOverWell(r.level, r.env, crane.site().railY).size() == 18, "the plug is over the well again");
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 8. The laydown
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aLaydownThatIsNotClearIsRefusedAndNoBlockIsEverReplaced(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(7));
        later(h, () -> {
            BlockPos[] sec = r.twoSections();
            var crane = r.crane();
            // 1. no floor at all: no place to set it down
            for (int x = -3; x <= 20; x++) {
                for (int z = -13; z <= -4; z++) {
                    r.level.setBlock(r.o.offset(x, 8, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            Component why = crane.queuePlug(true, null);
            h.assertTrue(why != null && english(why).contains("no clear place"), "no floor, no laydown: " + english(why));
            h.assertTrue(r.entities().isEmpty() && PlugSections.survey(r.level, sec[0]).section().blockCount() == 18, "nothing moved");
            // 2. the floor is back, but a block is placed on the laydown after the crane set out
            for (int x = -3; x <= 20; x++) {
                for (int z = -13; z <= -4; z++) {
                    r.level.setBlock(r.o.offset(x, 8, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            var shapeA = r.snapshot(sec[0], 3, 3, 3);
            h.assertTrue(crane.queuePlug(true, null) == null, "queued");
            BlockPos lay = crane.plugPlan().layMin();
            r.f.runCraneUntil(Phase.LOWER_EMPTY, 120_000);
            BlockPos blocker = lay.offset(1, 1, 1);
            r.level.setBlock(blocker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            r.f.runCrane(120_000);
            h.assertTrue(crane.phase() == Phase.HALTED && english(crane.haltMessage()).contains("not clear"), "halted before the lift: " + crane.phase() + " " + english(crane.haltMessage()));
            h.assertTrue(sameStates(r.level, sec[0], shapeA) && r.entities().isEmpty() && !crane.holding(), "the section never left the well");
            h.assertTrue(r.level.getBlockState(blocker).is(Blocks.DIAMOND_BLOCK), "the block on the laydown was not replaced");
            crane.abort();
            // 3. a block placed on the laydown while the section is in the air stops the set-down; the section stays on the hook
            r.level.setBlock(blocker, Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(crane.queuePlug(true, null) == null, "queued again");
            lay = crane.plugPlan().layMin();
            blocker = lay.offset(1, 1, 1);
            r.f.runCraneUntil(Phase.LOWER_LOADED, 120_000);
            r.level.setBlock(blocker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            r.f.runCrane(120_000);
            h.assertTrue(crane.phase() == Phase.HALTED && crane.carriedPlug(), "halted with the section on the hook: " + crane.phase() + " " + english(crane.haltMessage()));
            h.assertTrue(english(crane.haltMessage()).contains("not clear") && r.level.getBlockState(blocker).is(Blocks.DIAMOND_BLOCK), "it did not replace the block");
            h.assertTrue(r.entities().size() == 1 && r.entities().get(0).cells().size() == 22, "the entity still holds all 22 blocks");
            r.level.setBlock(blocker, Blocks.AIR.defaultBlockState(), 2);
            crane.resume();
            r.run(h);
            h.assertTrue(sameStates(r.level, lay, shapeA) && r.entities().isEmpty(), "with the way clear the set-down finishes exactly");
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 9. Saves and reloads
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aSectionHangingFromTheHookSavesAndLoadsOnceWithEveryBlock(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(8));
        later(h, () -> {
            BlockPos[] sec = r.twoSections();
            var shapeA = r.snapshot(sec[0], 3, 3, 3);
            var shapeB = r.snapshot(sec[1], 3, 3, 3);
            var regs = r.level.registryAccess();
            h.assertTrue(r.crane().queuePlug(true, null) == null, "queued");
            r.f.runCraneUntil(Phase.TRANSIT, 120_000);
            for (int i = 0; i < 150; i++) {
                r.crane().scheduledTick(r.level);
            }
            h.assertTrue(r.crane().phase() == Phase.TRANSIT && r.crane().carriedPlug(), "in transit with the section: " + r.crane().phase());
            double progress = r.crane().progress();
            var tipBefore = r.crane().tip();
            var lay = r.crane().plugPlan().layMin();
            // the world is saved: the crane's tag and the entity's tag
            CompoundTag craneTag = r.crane().saveWithoutMetadata(regs);
            h.assertTrue(craneTag.contains("Plug") && craneTag.contains("PlugEntity"), "the crane saved its plan and the entity's id");
            PlugSectionEntity e = r.entities().get(0);
            CompoundTag entityTag = new CompoundTag();
            h.assertTrue(e.save(entityTag), "the entity is saved");
            java.util.UUID id = e.getUUID();
            // ...and read again: the chunk unloads (the entity leaves with it, losing nothing), then loads
            e.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(r.entities().isEmpty() && allAir(r.level, sec[0], shapeA) && !(r.level.getBlockState(lay).is(BwrBlocks.SHIELD_PLUG_CONCRETE.get())),
                    "unloading the entity put nothing in the world and dropped nothing");
            h.assertTrue(r.level.getEntitiesOfClass(ItemEntity.class, new AABB(r.o.getCenter().add(-60, -20, -60), r.o.getCenter().add(80, 60, 60))).isEmpty(), "no item dropped");
            r.level.removeBlockEntity(r.f.console);
            var again = new BuildingCraneBlockEntity(r.f.console, r.level.getBlockState(r.f.console));
            again.setLevel(r.level);
            again.loadWithComponents(craneTag, regs);
            r.level.setBlockEntity(again);
            r.f.crane = again;
            Entity loaded = EntityType.create(entityTag, r.level).orElseThrow();
            h.assertTrue(r.level.addFreshEntity(loaded), "the entity loads");
            h.assertTrue(r.entities().size() == 1 && loaded.getUUID().equals(id), "exactly one entity, the same one");
            h.assertTrue(((PlugSectionEntity) loaded).cells().size() == 22, "it holds all 22 blocks");
            h.assertTrue(Math.abs(again.progress() - progress) < 1e-9 && Math.abs(again.tip().x() - tipBefore.x()) < 1e-9, "the crane carries on from where it was");
            r.run(h);
            h.assertTrue(r.entities().isEmpty(), "no entity is left, and none was duplicated");
            h.assertTrue(sameStates(r.level, lay, shapeA) && allAir(r.level, sec[0], shapeA) && sameStates(r.level, sec[1], shapeB), "the blocks are on the laydown once, exactly");
            long total = 0;
            for (BlockPos p : BlockPos.betweenClosed(r.o.offset(-4, 9, -14), r.o.offset(21, 16, 10))) {
                if (r.level.getBlockState(p).getBlock() instanceof ShieldPlugConcreteBlock) {
                    total++;
                }
            }
            h.assertTrue(total == 36, "36 plug blocks in the world, as built: " + total);
            // a saved crane that finished writes no plug data
            CompoundTag done = r.crane().saveWithoutMetadata(regs);
            h.assertTrue(!done.contains("PlugEntity") && !done.contains("Plug") && done.contains("Laid"), "only the laydown book remains");
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 10. A broken crane
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aCraneOrRunwayBrokenUnderAHangingSectionLosesNoBlocks(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(9));
        Rig q = new Rig(h.getLevel(), at(10));
        Rig s = new Rig(h.getLevel(), at(11));
        Rig k = new Rig(h.getLevel(), at(12));
        later(h, () -> {
            // (a) the console is broken: the section goes back where it came from, state for state
            BlockPos[] sec = r.twoSections();
            var shapeA = r.snapshot(sec[0], 3, 3, 3);
            h.assertTrue(r.crane().queuePlug(true, null) == null, "queued");
            r.f.runCraneUntil(Phase.TRANSIT, 120_000);
            h.assertTrue(r.entities().size() == 1 && allAir(r.level, sec[0], shapeA), "the section is on the hook");
            r.level.setBlock(r.f.console, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(r.entities().isEmpty(), "the entity is gone");
            h.assertTrue(sameStates(r.level, sec[0], shapeA), "the section is back where it was taken from, exactly");
            // (b) the console is broken and the place it came from has been built over: the blocks drop as items, all of them
            BlockPos[] secQ = q.twoSections();
            var shapeAQ = q.snapshot(secQ[0], 3, 3, 3);
            h.assertTrue(q.crane().queuePlug(true, null) == null, "queued");
            q.f.runCraneUntil(Phase.TRANSIT, 120_000);
            q.level.setBlock(secQ[0].offset(1, 0, 1), Blocks.OBSIDIAN.defaultBlockState(), 2);
            q.level.setBlock(q.f.console, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(q.entities().isEmpty(), "the entity is gone");
            h.assertTrue(q.level.getBlockState(secQ[0].offset(1, 0, 1)).is(Blocks.OBSIDIAN) && solid(q.snapshot(secQ[0], 3, 3, 3)) == 1,
                    "nothing was replaced at the origin, and nothing was put there");
            h.assertTrue(shapeAQ.size() == 27, "fixture");
            int plugItemsQ = 0, anchorItemsQ = 0;
            for (ItemEntity itemQ : q.level.getEntitiesOfClass(ItemEntity.class, new AABB(q.o.getCenter().add(-60, -20, -60), q.o.getCenter().add(80, 60, 60)))) {
                if (itemQ.getItem().is(BwrItems.SHIELD_PLUG_CONCRETE.get())) {
                    plugItemsQ += itemQ.getItem().getCount();
                } else if (itemQ.getItem().is(BwrItems.CRANE_ANCHOR.get())) {
                    anchorItemsQ += itemQ.getItem().getCount();
                }
            }
            h.assertTrue(plugItemsQ == 18 && anchorItemsQ == 4, "every block dropped as an itemQ: " + plugItemsQ + " plug blocks and " + anchorItemsQ + " anchors");
            // (c) the runway is broken: the section's own once-a-second check puts it back, and the crane lets go of it
            BlockPos[] secS = s.twoSections();
            var shapeAS = s.snapshot(secS[0], 3, 3, 3);
            h.assertTrue(s.crane().queuePlug(true, null) == null, "queued");
            s.f.runCraneUntil(Phase.TRANSIT, 120_000);
            PlugSectionEntity eS = s.entities().get(0);
            // while the runway stands, its checks change nothing
            eS.tickCount = 20;
            eS.tick();
            h.assertTrue(s.entities().size() == 1 && s.crane().carriedPlug(), "a healthy crane keeps its load");
            for (int x = -3; x <= 22; x++) {
                s.level.setBlock(s.o.offset(x, RAIL_DY, 9), Blocks.AIR.defaultBlockState(), 2);
            }
            eS.tickCount = 40;
            eS.tick();
            h.assertTrue(s.entities().isEmpty() && !s.crane().carriedPlug() && sameStates(s.level, secS[0], shapeAS), "the section was put back exactly and the hook let go");
            // (d) /kill on the entity does not lose the blocks either
            BlockPos[] secK = k.twoSections();
            var shapeAK = k.snapshot(secK[0], 3, 3, 3);
            h.assertTrue(k.crane().queuePlug(true, null) == null, "queued");
            k.f.runCraneUntil(Phase.TRANSIT, 120_000);
            k.entities().get(0).kill();
            h.assertTrue(k.entities().isEmpty() && sameStates(k.level, secK[0], shapeAK), "a killed section went back where it came from");
            h.assertTrue(k.crane().carriedPlug(), "the crane still thinks it holds it until it ticks");
            k.crane().scheduledTick(k.level);
            h.assertTrue(!k.crane().carriedPlug() && !k.crane().busy() && k.crane().plugPlan() == null, "the crane then lets go of it: the hook is empty");
        }, r, q, s, k);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 11. The head missile and the shielding
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 400)
    public static void theHeadMissileAndTheShieldingSeeThePlugOnlyWhileItIsOnTheWell(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(13));
        later(h, () -> {
            // a plug of one layer of the heaviest tier over the whole well and its margin, cut by a seam into a 4 x 7 and a 3 x 7 piece
            BlockPos min = new BlockPos(r.env.min().getX(), r.env.max().getY() + 1, r.env.min().getZ());
            r.plug(min, 7, 1, 7, 3);
            for (int z = 0; z < 7; z++) {
                PlugSeamMarkerItem.toggle(r.level, min.offset(3, 0, z), Direction.EAST);
            }
            r.anchor(min.offset(0, 1, 0));
            r.anchor(min.offset(3, 1, 0));
            r.anchor(min.offset(0, 1, 6));
            r.anchor(min.offset(3, 1, 6));
            var whole = r.snapshot(min, 7, 2, 7);
            int w = r.env.width(), d = r.env.depth();
            double ax = (r.env.min().getX() + r.env.max().getX() + 1) / 2.0, az = (r.env.min().getZ() + r.env.max().getZ() + 1) / 2.0;
            int row = r.env.max().getY() + 1;
            var cells = HeadMissileLauncher.footprint(ax, az, w, d, row);
            double plugRatingJ = HeadMissile.crushJ(ContainmentStrength.contribution(r.level, min, BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState()));
            double full = cells.size() * plugRatingJ;
            double part = cells.stream().filter(p -> p.getX() >= min.getX() + 4).count() * plugRatingJ;
            // an energy between the two: a whole plug holds the head, a plug with its west piece lifted off does not
            double wanted = (full + part) / 2.0;
            double psig = 1400.0;
            double stored = 1.0e6;
            while (HeadMissile.kineticEnergyJ(stored, psig, w, d) < wanted && stored < 1.0e13) {
                stored *= 1.002;
            }
            double ke = HeadMissile.kineticEnergyJ(stored, psig, w, d);
            h.assertTrue(ke > part && ke < full, "fixture: the energy lies between a partial and a whole row's capacity: " + part + " < " + ke + " < " + full);

            // 1. the plug is on the well: it holds the head
            var launch = HeadMissileLauncher.launch(r.level, r.env.min(), r.env.max(), stored, psig);
            h.assertTrue(launch != null && launch.stopRow() == 0 && launch.ceilingY() == row, "the whole plug stops the head at its first row: " + (launch == null ? "no launch" : launch.stopRow()));
            r.level.getEntitiesOfClass(ReactorHeadEntity.class, new AABB(r.o.getCenter().add(-60, -30, -60), r.o.getCenter().add(80, 80, 60))).forEach(Entity::discard);
            // the launch broke the row it held: build it again, exactly as it was
            for (var e : whole.entrySet()) {
                r.level.setBlock(min.offset(e.getKey()), e.getValue(), 2);
            }
            // 2. the west piece is lifted off: its blocks are gone from the well and stop nothing
            var crane = r.crane();
            Component why = crane.queuePlug(true, null);
            h.assertTrue(why == null, "PLUG OFF: " + english(why));
            r.run(h);
            h.assertTrue(r.level.getBlockState(min).isAir() && !r.level.getBlockState(min.offset(4, 0, 0)).isAir(), "the west piece is off, the east piece is on");
            var launch2 = HeadMissileLauncher.launch(r.level, r.env.min(), r.env.max(), stored, psig);
            h.assertTrue(launch2 != null && launch2.stopRow() != 0 && launch2.ceilingY() != row, "the plug with a section off no longer stops the head at that row: " + (launch2 == null ? "no launch" : launch2.stopRow()));
            r.level.getEntitiesOfClass(ReactorHeadEntity.class, new AABB(r.o.getCenter().add(-60, -30, -60), r.o.getCenter().add(80, 80, 60))).forEach(Entity::discard);
            for (int x = 4; x < 7; x++) {
                for (int z = 0; z < 7; z++) {
                    BlockState want = whole.get(new BlockPos(x, 0, z));
                    r.level.setBlock(min.offset(x, 0, z), want, 2);
                }
            }
            // 3. put back, it holds again, and the section is exactly as it was
            Component back = crane.queuePlug(false, null);
            h.assertTrue(back == null, "PLUG ON: " + english(back));
            r.run(h);
            h.assertTrue(sameStates(r.level, min, whole), "the plug is whole again, state for state");
            var launch3 = HeadMissileLauncher.launch(r.level, r.env.min(), r.env.max(), stored, psig);
            h.assertTrue(launch3 != null && launch3.stopRow() == 0 && launch3.ceilingY() == row, "the plug put back stops the head at its first row again");
        }, r);
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void aRadiationSourceBelowThePlugIsShieldedOnlyWhileTheSectionIsOnTheWell(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(14));
        later(h, () -> {
            BlockPos[] sec = r.twoSections();
            var crane = r.crane();
            Vec3 source = new Vec3(sec[0].getX() + 1.5, sec[0].getY() - 3.5, sec[0].getZ() + 1.5);
            Vec3 meter = new Vec3(sec[0].getX() + 1.5, sec[0].getY() + 6.0, sec[0].getZ() + 1.5);
            Object owner = new Object();
            RadiationSources.emit(r.level, owner, source, 1000.0, 1.0);
            try {
                double on = RadiationSources.doseRateMsvPerHour(r.level, meter);
                // PLUG OFF
                h.assertTrue(crane.queuePlug(true, null) == null, "PLUG OFF");
                r.f.runCraneUntil(Phase.TRANSIT, 120_000);
                double carried = RadiationSources.doseRateMsvPerHour(r.level, meter);
                r.run(h);
                double off = RadiationSources.doseRateMsvPerHour(r.level, meter);
                h.assertTrue(off > on * 100.0, "with the section off, the reading is unshielded: " + off + " against " + on);
                h.assertTrue(carried == off, "a section hanging from the hook shields nothing: " + carried + " against " + off);
                h.assertTrue(crane.queuePlug(false, null) == null, "PLUG ON");
                r.run(h);
                double again = RadiationSources.doseRateMsvPerHour(r.level, meter);
                h.assertTrue(again == on, "put back, it shields exactly as before: " + again + " against " + on);
            } finally {
                RadiationSources.clear(r.level, owner);
            }
        }, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 12. Old saves
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "plugs", timeoutTicks = 200)
    public static void anOldCraneWithNoPlugDataLoadsAndAnIdleCraneWithAPlugWritesNoneAndTicksNothing(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), at(15));
        later(h, () -> {
            var regs = r.level.registryAccess();
            BlockPos[] sec = r.twoSections();
            var shape = r.snapshot(sec[0], 3, 3, 3);
            // an idle crane beside a plug writes nothing about plugs
            CompoundTag idle = r.crane().saveWithFullMetadata(regs);
            h.assertTrue(!idle.contains("Plug") && !idle.contains("PlugEntity") && !idle.contains("Laid"), "an idle crane writes no plug keys: " + idle);
            // a tag of an older build (the keys that existed then only) loads: no plan, nothing held, nothing laid
            CompoundTag old = new CompoundTag();
            for (String key : idle.getAllKeys()) {
                if (!key.equals("Plug") && !key.equals("PlugEntity") && !key.equals("Laid")) {
                    old.put(key, idle.get(key).copy());
                }
            }
            var loaded = new BuildingCraneBlockEntity(r.f.console, r.level.getBlockState(r.f.console));
            loaded.setLevel(r.level);
            loaded.loadWithComponents(old, regs);
            h.assertTrue(loaded.plugPlan() == null && !loaded.carriedPlug() && loaded.plugsLaid() == 0 && !loaded.holding() && loaded.phase() == Phase.IDLE,
                    "an old crane loads with no plug data");
            r.level.removeBlockEntity(r.f.console);
            r.level.setBlockEntity(loaded);
            r.f.crane = loaded;
            loaded.refreshSite();
            h.assertTrue(loaded.queuePlug(false, null) != null && english(loaded.queuePlug(false, null)).contains("No shield plug section is set down"), "nothing is laid down in an old world");
            h.assertTrue(loaded.queuePlug(true, null) == null, "and a plug can be lifted at once");
            r.run(h);
            // an old block state: a plug block saved before seams existed has no seam properties and loads with every seam off
            CompoundTag oldBlock = new CompoundTag();
            oldBlock.putString("Name", "bwr:shield_plug_concrete");
            BlockState fromOld = net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), oldBlock);
            h.assertTrue(fromOld == BwrBlocks.SHIELD_PLUG_CONCRETE.get().defaultBlockState() && !ShieldPlugConcreteBlock.hasSeam(fromOld),
                    "an old plug block (no properties saved) is the plain block with no seams");
            h.assertTrue(!loaded.busy() && loaded.phase() == Phase.IDLE && loaded.plugPlan() == null, "idle again, with nothing to tick");
            h.assertTrue(PlugLift.RATED_CAPACITY_T == 125.0 && shape != null, "the crane is rated 125 t");
            BlockEntity be = r.level.getBlockEntity(r.f.console);
            h.assertTrue(be == loaded, "fixture sanity");
        }, r);
    }
}
