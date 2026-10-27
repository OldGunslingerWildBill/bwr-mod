package dev.bwr.mod.devtest;

import dev.bwr.core.accident.BlastPhysics;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.ContainmentWallStrength;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.accident.AccidentBlast;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Accident blasts in the world: hydrogen burns, detonation and containment failure, vessel head failure, pipe whip,
 * the lower head, the protection rules and the save rules. Each test drives the real core events where it can (the
 * containment model is stepped until it raises them) and the real reactor tick for boundary failures.
 *
 * <p>Fixtures sit at x = 700,000 and east, one per test, 400 blocks apart. The namespace is {@code bwrblast}
 * ({@code -PbwrOnlyBlast} runs just these); the template is the shared empty one.
 */
@GameTestHolder("bwrblast")
@PrefixGameTestTemplate(false)
public final class AccidentBlastTests {
    private AccidentBlastTests() {
    }

    private static final int WIDTH = 9, DEPTH = 9, HEIGHT = 12;

    /** A vessel, and optionally a sealed stone room around it with a weak plank column in the roof. */
    private static final class Plant {
        final ServerLevel level;
        final BlockPos min;
        final ReactorControllerBlockEntity reactor;
        final ContainmentControllerBlockEntity room;
        final BlockPos lo, hi;
        /** The weakest spot of the room: a plank column in the roof unless the walls are weaker than planks. */
        BlockPos weakColumn;

        Plant(GameTestHelper h, BlockPos min, boolean withRoom, Block wall) throws Exception {
            this.level = h.getLevel();
            this.min = min;
            this.lo = min.offset(-9, -5, -9);
            this.hi = min.offset(17, 17, 17);
            this.weakColumn = new BlockPos(min.getX() + 4, hi.getY(), min.getZ() + 4);
            boolean plankRoof = withRoom && wall != Blocks.GRAY_CONCRETE;
            if (withRoom) {
                hollow(level, lo, hi, wall);
                if (plankRoof) level.setBlock(weakColumn, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            } else {
                clear(level, lo, hi);
            }
            reactor = CompactCoreRegressionTests.build(level, min, WIDTH, DEPTH, HEIGHT, false);
            h.assertTrue(reactor.isFormed(), "fixture reactor did not form: " + reactor.statusLines());
            if (withRoom) {
                BlockPos at = min.offset(-6, 0, -6);
                level.setBlock(at, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
                room = (ContainmentControllerBlockEntity) level.getBlockEntity(at);
                survey(level, room);
                h.assertTrue(room.isSealed() && room.strengthReady() && room.linkedReactors().contains(reactor.getBlockPos()),
                        "fixture room did not seal and link: " + room.statusLines());
                if (plankRoof) h.assertTrue(weakColumn.equals(room.weakestSpot().position()),
                        "plank roof was not the weakest spot: " + room.statusLines());
                else weakColumn = room.weakestSpot().position();
            } else {
                room = null;
            }
        }

        ContainmentAtmosphere gas() {
            return reactor.core().getContainment();
        }

        void afterStep() {
            reactor.severeEffects().afterStep(reactor);
        }

        /** A point inside the room, offset from the interior minimum. */
        BlockPos at(int dx, int dy, int dz) {
            return min.offset(dx, dy, dz);
        }
    }

    /** A blast confined to the 13 x 7 x 13 box around {@code o}: no unloaded chunk to wait for. */
    private static AccidentBlast.Job near(ServerLevel level, BlockPos o, double tntKg, double capPsi) {
        return AccidentBlast.interior(level, o.getX() + 0.5, o.getY() + 0.5, o.getZ() + 0.5, tntKg, capPsi, 4.0f,
                new BlockPos[] {o.offset(-6, -3, -6), o.offset(6, 3, 6)});
    }

    private static void load(ServerLevel level, BlockPos lo, BlockPos hi) {
        for (int x = lo.getX() >> 4; x <= hi.getX() >> 4; x++)
            for (int z = lo.getZ() >> 4; z <= hi.getZ() >> 4; z++) level.getChunk(x, z);
    }

    private static void clear(ServerLevel level, BlockPos lo, BlockPos hi) {
        load(level, lo, hi);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    private static void hollow(ServerLevel level, BlockPos lo, BlockPos hi, Block wall) {
        load(level, lo, hi);
        for (int x = lo.getX(); x <= hi.getX(); x++)
            for (int z = lo.getZ(); z <= hi.getZ(); z++)
                for (int y = lo.getY(); y <= hi.getY(); y++) {
                    boolean shell = x == lo.getX() || x == hi.getX() || y == lo.getY() || y == hi.getY()
                            || z == lo.getZ() || z == hi.getZ();
                    level.setBlock(new BlockPos(x, y, z), (shell ? wall : Blocks.AIR).defaultBlockState(), 2);
                }
    }

    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for (int i = 0; i < 64; i++) {
            ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(),
                    level.getBlockState(controller.getBlockPos()), controller);
            if (!controller.isScanning()) {
                for (int j = 0; j < 64 && controller.isSealed() && !controller.strengthReady(); j++)
                    ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(),
                            level.getBlockState(controller.getBlockPos()), controller);
                return;
            }
        }
        throw new AssertionError("containment scan exceeded 64 ticks");
    }

    private static void finish(Plant plant) {
        plant.reactor.severeEffects().blasts().runToCompletion(plant.level, 200);
    }

    /** Step the containment until its hydrogen burns (8% in air): a deflagration. */
    private static void burnHydrogen(ContainmentAtmosphere gas, int freeVolumeM3) {
        gas.setFreeVolumeM3(freeVolumeM3);
        for (int i = 0; i < 20_000 && gas.getBurnCount() == 0; i++) gas.step(0.05, 0.0, 0.0, 1.0);
        if (gas.getBurnCount() == 0) throw new AssertionError("hydrogen never burned");
    }

    /** Store hydrogen in an inerted containment, then let air back in: a detonation. */
    private static void detonateHydrogen(ContainmentAtmosphere gas, int freeVolumeM3) {
        gas.setFreeVolumeM3(freeVolumeM3);
        gas.initialiseInerted();
        for (int i = 0; i < 1200; i++) gas.step(0.5, 0.0, 0.0, 1.0);
        gas.setInertingCommanded(false);
        for (int i = 0; i < 4 * 3600 && !gas.isFailed(); i++) gas.step(0.0, 0.0, 0.0, 1.0);
        if (!gas.isFailed()) throw new AssertionError("de-inerted hydrogen never detonated");
    }

    private static int items(ServerLevel level, BlockPos lo, BlockPos hi) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(lo.getX(), lo.getY(), lo.getZ(),
                hi.getX() + 1, hi.getY() + 1, hi.getZ() + 1)).size();
    }

    private static void assertVesselIntact(GameTestHelper h, Plant plant, String when) throws Exception {
        assertVesselIntact(h, plant.level, plant.reactor, when);
    }

    private static void assertVesselIntact(GameTestHelper h, ServerLevel level, ReactorControllerBlockEntity reactor, String when) throws Exception {
        h.assertTrue(level.getBlockState(reactor.getBlockPos()).is(BwrBlocks.REACTOR_CONTROLLER.get()),
                "reactor controller destroyed " + when);
        var s = reactor.structure();
        for (BlockPos p : BlockPos.betweenClosed(s.interiorMin().offset(-1, -1, -1), s.interiorMax().offset(1, 1, 1))) {
            boolean shell = p.getX() == s.interiorMin().getX() - 1 || p.getX() == s.interiorMax().getX() + 1
                    || p.getY() == s.interiorMin().getY() - 1 || p.getY() == s.interiorMax().getY() + 1
                    || p.getZ() == s.interiorMin().getZ() - 1 || p.getZ() == s.interiorMax().getZ() + 1;
            if (!shell) continue;
            var state = level.getBlockState(p);
            h.assertTrue(!state.isAir(), "vessel shell block " + p.toShortString() + " destroyed " + when);
        }
        for (BlockPos drive : s.crdPositions())
            h.assertTrue(level.getBlockState(drive).is(BwrBlocks.CONTROL_ROD_DRIVE.get()),
                    "control rod drive " + drive.toShortString() + " destroyed " + when);
        reactor.markStructureDirty();
        SpargerRegressionTests.validate(reactor);
        h.assertTrue(reactor.isFormed(), "vessel no longer formed " + when + ": " + reactor.statusLines());
    }

    // ------------------------------------------------------------------
    // 1. Hydrogen deflagration
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 600)
    public static void deflagrationBreaksGlassButNotConcrete(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(700_000, 190, 700_000), true, Blocks.GRAY_CONCRETE);
        var level = plant.level;
        BlockPos glassPane = plant.at(-6, 8, 2), glassBlock = plant.at(10, 10, 6), glow = plant.at(5, 11, -5),
                torch = plant.at(-4, 10, 8), stone = plant.at(-6, 9, 3), obsidian = plant.at(11, 9, 3),
                ironBlock = plant.at(6, 12, 12);
        level.setBlock(glassPane, Blocks.GLASS_PANE.defaultBlockState(), 2);
        level.setBlock(glassBlock, Blocks.GLASS.defaultBlockState(), 2);
        level.setBlock(glow, Blocks.GLOWSTONE.defaultBlockState(), 2);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), 2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(obsidian, Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(ironBlock, Blocks.IRON_BLOCK.defaultBlockState(), 2);
        BlockPos wall = plant.lo.offset(0, 10, 12); // a concrete room wall block, at the room's edge
        h.assertTrue(level.getBlockState(wall).is(Blocks.GRAY_CONCRETE), "fixture wall is not concrete");

        burnHydrogen(plant.gas(), plant.room.freeVolume());
        plant.afterStep();
        finish(plant);

        h.assertTrue(level.getBlockState(glassPane).isAir(), "glass pane survived the hydrogen burn");
        h.assertTrue(level.getBlockState(glassBlock).isAir(), "free-standing glass block survived the burn");
        h.assertTrue(level.getBlockState(glow).isAir(), "glowstone light fixture survived the burn");
        h.assertTrue(level.getBlockState(torch).isAir(), "torch survived the burn");
        h.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "stone broke in a deflagration");
        h.assertTrue(level.getBlockState(obsidian).is(Blocks.OBSIDIAN), "obsidian broke in a deflagration");
        h.assertTrue(level.getBlockState(ironBlock).is(Blocks.IRON_BLOCK), "iron block broke in a deflagration");
        h.assertTrue(level.getBlockState(wall).is(Blocks.GRAY_CONCRETE), "a concrete wall block broke in a deflagration");
        for (BlockPos p : BlockPos.betweenClosed(plant.lo, plant.hi)) {
            boolean shell = p.getX() == plant.lo.getX() || p.getX() == plant.hi.getX() || p.getY() == plant.lo.getY()
                    || p.getY() == plant.hi.getY() || p.getZ() == plant.lo.getZ() || p.getZ() == plant.hi.getZ();
            if (shell)
                h.assertTrue(level.getBlockState(p).is(Blocks.GRAY_CONCRETE), "wall block " + p.toShortString() + " broke");
        }
        assertVesselIntact(h, plant, "by the burn");
        survey(level, plant.room);
        h.assertTrue(plant.room.isSealed(), "a deflagration below the failure pressure opened the room: " + plant.room.statusLines());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void moreHydrogenBreaksFurtherAndEachPaneFollowsTheModel(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos origin = new BlockPos(704_000, 190, 704_000);
        load(level, origin.offset(-2, -2, -2), origin.offset(130, 2, 2));
        double glassRating = ContainmentWallStrength.psi(Blocks.GLASS_PANE.getExplosionResistance());
        int[] broken = new int[2];
        double[] kg = {5.0, 40.0};
        for (int run = 0; run < 2; run++) {
            BlockPos base = origin.offset(0, 0, run * 0);
            clear(level, base.offset(-2, -2, -2), base.offset(130, 2, 2));
            for (int d = 2; d <= 125; d++) level.setBlock(base.offset(d, 0, 0), Blocks.GLASS_PANE.defaultBlockState(), 2);
            double tnt = BlastPhysics.deflagrationTntKg(kg[run]);
            var job = AccidentBlast.interior(level, base.getX() + 0.5, base.getY() + 0.5, base.getZ() + 0.5, tnt, 10.0, 4.0f, null);
            var queue = new AccidentBlast.Queue();
            queue.add(job);
            queue.runToCompletion(level, 100);
            for (int d = 2; d <= 125; d++) {
                boolean gone = level.getBlockState(base.offset(d, 0, 0)).isAir();
                boolean expected = BlastPhysics.deflagrationPsi(tnt, 10.0, d) > glassRating;
                h.assertTrue(gone == expected, "pane at " + d + " m from a " + kg[run] + " kg burn: broken=" + gone
                        + " but the model says " + BlastPhysics.deflagrationPsi(tnt, 10.0, d) + " psi against " + glassRating);
                if (gone) broken[run]++;
            }
        }
        h.assertTrue(broken[0] > 5 && broken[1] > broken[0], "the larger burn did not break more panes: " + broken[0] + " vs " + broken[1]);
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 2. Detonation and overpressure failure: breach at the weakest spot
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void detonationBreachesTheWeakestColumnAndTheRoomLeaks(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(700_400, 190, 700_000), true, Blocks.STONE);
        var level = plant.level;
        BlockPos weak = plant.weakColumn;
        BlockPos farWall = plant.lo.offset(0, 8, 12);
        BlockPos nearWall = weak.offset(3, 0, 0);
        BlockPos glassPane = plant.at(-6, 8, 2), machine = plant.at(13, 10, 3), obsidian = plant.at(11, 9, 3);
        level.setBlock(glassPane, Blocks.GLASS_PANE.defaultBlockState(), 2);
        level.setBlock(machine, Blocks.IRON_BLOCK.defaultBlockState(), 2);
        level.setBlock(obsidian, Blocks.OBSIDIAN.defaultBlockState(), 2);
        h.assertTrue(level.getBlockState(nearWall).is(Blocks.STONE) && level.getBlockState(farWall).is(Blocks.STONE),
                "fixture wall is not stone");
        int wallsBefore = countNot(level, plant.lo, plant.hi, true);

        detonateHydrogen(plant.gas(), plant.room.freeVolume());
        plant.afterStep();
        finish(plant);

        h.assertTrue(level.getBlockState(weak).isAir(), "the weakest column was not breached");
        h.assertTrue(level.getBlockState(nearWall).isAir(), "the breach is only the single column: neighbour at 3 blocks stands");
        h.assertTrue(level.getBlockState(farWall).is(Blocks.STONE), "a wall block far from the weakest spot broke");
        int wallsAfter = countNot(level, plant.lo, plant.hi, true);
        int removed = wallsBefore - wallsAfter;
        h.assertTrue(removed > 9 && removed <= 8 * 8 * 4, "breach removed " + removed + " wall blocks; expected a hole, not one column or the building");
        h.assertTrue(level.getBlockState(glassPane).isAir(), "interior glass survived a detonation");
        h.assertTrue(level.getBlockState(obsidian).is(Blocks.OBSIDIAN), "obsidian broke in a detonation");
        assertVesselIntact(h, plant, "by the detonation");
        h.assertTrue(items(level, plant.lo.offset(-2, -2, -2), plant.hi.offset(2, 2, 2)) > 0,
                "breached wall dropped nothing under vanilla explosion rules");

        survey(level, plant.room);
        h.assertTrue(!plant.room.isSealed(), "the room still reports sealed after the breach: " + plant.room.statusLines());
        h.assertTrue(plant.room.leak() != null, "the room reports no leak position");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 1200)
    public static void aSecondBurnAfterTheBreachStaysInsideTheRoom(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(705_600, 190, 704_000), true, Blocks.STONE);
        var level = plant.level;
        // Things outside the room, in reach of an unconfined blast: a pane and a stone brick beyond the east wall, and
        // the ground three rows under the floor.
        BlockPos pane = new BlockPos(plant.hi.getX() + 6, plant.min.getY() + 6, plant.min.getZ() + 4);
        BlockPos brick = pane.above(2);
        BlockPos ground = new BlockPos(plant.min.getX() + 4, plant.lo.getY() - 3, plant.min.getZ() + 4);
        load(level, pane.offset(-2, 0, -2), pane.offset(2, 0, 2));
        level.setBlock(pane, Blocks.GLASS_PANE.defaultBlockState(), 2);
        level.setBlock(brick, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(ground, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        detonateHydrogen(plant.gas(), plant.room.freeVolume());
        plant.afterStep();
        finish(plant);
        h.assertTrue(level.getBlockState(plant.weakColumn).isAir(), "the first detonation did not breach");
        h.assertTrue(level.getBlockState(pane).is(Blocks.GLASS_PANE) && level.getBlockState(brick).is(Blocks.STONE_BRICKS),
                "the first detonation reached outside the room");
        // The containment is breached, so no sealed room links to the reactor now. The gas, with the hole to breathe
        // through, burns again.
        h.assertTrue(dev.bwr.mod.containment.ContainmentControllerRegistry.linkedReactor(level, plant.reactor.getBlockPos()) == null,
                "the breached room still links to its reactor");
        h.assertTrue(plant.reactor.saveWithFullMetadata(level.registryAccess()).contains("AccidentRoomMin"),
                "the remembered room was not saved");
        int burns = plant.gas().getBurnCount();
        for (int i = 0; i < 20_000 && plant.gas().getBurnCount() == burns; i++) plant.gas().step(0.5, 0.0, 0.0, 1.0);
        h.assertTrue(plant.gas().getBurnCount() > burns, "the gas never burned again");
        plant.afterStep();
        finish(plant);
        h.assertTrue(level.getBlockState(pane).is(Blocks.GLASS_PANE), "the second burn reached outside the room (pane)");
        h.assertTrue(level.getBlockState(brick).is(Blocks.STONE_BRICKS), "the second burn reached outside the room (brick)");
        h.assertTrue(level.getBlockState(ground).is(Blocks.SMOOTH_STONE), "the second burn broke the ground");
        assertVesselIntact(h, plant, "by the second burn");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void overpressureFailureOpensTheWeakestSpotInProportion(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(700_800, 190, 700_000), true, Blocks.STONE);
        var level = plant.level;
        BlockPos weak = plant.weakColumn;
        double rating = plant.room.weakestSpot().ratingPsig();
        // A small room load: burst just past the rating.
        var gas = plant.gas();
        gas.setFreeVolumeM3(plant.room.freeVolume());
        gas.setFailurePressurePsig(rating);
        for (int i = 0; i < 20_000 && !gas.isFailed(); i++) gas.step(0.0, 40_000.0, 0.0, 1.0);
        h.assertTrue(gas.isFailed() && gas.getFailureMode() == ContainmentAtmosphere.FailureMode.OVERPRESSURE,
                "the containment never failed on pressure: " + gas);
        int before = countNot(level, plant.lo, plant.hi, true);
        plant.afterStep();
        finish(plant);
        h.assertTrue(level.getBlockState(weak).isAir(), "overpressure did not open the weakest column");
        int removed = before - countNot(level, plant.lo, plant.hi, true);
        h.assertTrue(removed >= 1 && removed < 100, "overpressure removed " + removed + " wall blocks; a small hole was expected");
        assertVesselIntact(h, plant, "by the overpressure failure");
        survey(level, plant.room);
        h.assertTrue(!plant.room.isSealed() && plant.room.leak() != null, "the room does not report the overpressure breach");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void breachGrowsWithEnergyAndExcessPressure(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(704_800, 190, 704_000), true, Blocks.STONE);
        var level = plant.level;
        double rating = plant.room.weakestSpot().ratingPsig();
        int[] sizes = new int[3];
        double[][] cases = {{rating * 1.1, 2.0}, {rating * 3.0, 2.0}, {rating * 3.0, 6.0}}; // load, scale blocks
        for (int i = 0; i < cases.length; i++) {
            var plan = plant.room.planBreach(level, cases[i][0], cases[i][1]);
            h.assertTrue(plan != null, "no breach plan for case " + i);
            sizes[i] = plan.blocks().size();
            survey(level, plant.room); // planning reports the leak; a survey of the untouched wall seals it again
            h.assertTrue(plant.room.isSealed() && plant.room.strengthReady(), "room did not reseal for the next plan");
        }
        h.assertTrue(sizes[0] >= 1, "the weakest column is always part of the plan");
        h.assertTrue(sizes[1] > sizes[0], "more excess pressure did not widen the hole: " + sizes[0] + " vs " + sizes[1]);
        h.assertTrue(sizes[2] > sizes[1], "more energy did not widen the hole: " + sizes[1] + " vs " + sizes[2]);
        h.assertTrue(plant.room.planBreach(level, 0.0, 0.0).blocks().size() == 1, "an unloaded plan is exactly the weakest column");
        h.succeed();
    }

    private static int countNot(ServerLevel level, BlockPos lo, BlockPos hi, boolean shellOnly) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            boolean shell = p.getX() == lo.getX() || p.getX() == hi.getX() || p.getY() == lo.getY()
                    || p.getY() == hi.getY() || p.getZ() == lo.getZ() || p.getZ() == hi.getZ();
            if (shellOnly && !shell) continue;
            if (!level.getBlockState(p).isAir()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 3. Vessel head failure
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void headFailureConeBreaksAboveAndLeavesTheVesselFormed(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(701_200, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        int top = plant.min.getY() + HEIGHT; // top shell row
        BlockPos axis = plant.at(4, 0, 4);
        BlockPos floorAbove = new BlockPos(axis.getX(), top + 4, axis.getZ());
        BlockPos plugAbove = new BlockPos(axis.getX(), top + 1, axis.getZ());
        BlockPos craneAbove = new BlockPos(axis.getX() + 3, top + 9, axis.getZ() - 2);
        BlockPos roofAbove = new BlockPos(axis.getX(), top + 12, axis.getZ());
        BlockPos farSide = new BlockPos(axis.getX() + 30, top + 3, axis.getZ()); // outside the cone at that height
        BlockPos sideOfVessel = plant.at(10, 5, 4); // against the shell, below the top: not in the cone
        BlockPos veryHigh = new BlockPos(axis.getX(), top + 46, axis.getZ());
        load(level, axis.offset(-48, 0, -48), axis.offset(48, 0, 48));
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) level.setBlock(floorAbove.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(plugAbove, Blocks.IRON_BLOCK.defaultBlockState(), 2);
        level.setBlock(craneAbove, Blocks.IRON_BLOCK.defaultBlockState(), 2);
        level.setBlock(roofAbove, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        level.setBlock(farSide, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(sideOfVessel.east(2), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(veryHigh, Blocks.STONE_BRICKS.defaultBlockState(), 2);

        plant.reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
        ReactorControllerBlockEntity.serverTick(level, plant.reactor.getBlockPos(), plant.reactor.getBlockState(), plant.reactor);
        finish(plant);

        h.assertTrue(plant.reactor.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD), "head did not fail");
        h.assertTrue(level.getBlockState(plugAbove).isAir(), "the shield plug above the head stands");
        h.assertTrue(level.getBlockState(floorAbove).isAir(), "the refuelling floor above the head stands");
        h.assertTrue(level.getBlockState(floorAbove.offset(2, 0, 3)).isAir(), "the refuelling floor within the cone stands");
        h.assertTrue(level.getBlockState(craneAbove).isAir(), "the crane above the head stands");
        h.assertTrue(level.getBlockState(roofAbove).isAir(), "the roof above the head stands");
        h.assertTrue(level.getBlockState(farSide).is(Blocks.STONE_BRICKS), "a block far outside the cone broke");
        h.assertTrue(level.getBlockState(sideOfVessel.east(2)).is(Blocks.STONE_BRICKS), "a block beside the vessel broke");
        h.assertTrue(level.getBlockState(veryHigh).is(Blocks.STONE_BRICKS), "the blast reached 46 blocks up");
        assertVesselIntact(h, plant, "by the head failure");
        h.assertTrue((plant.reactor.getUpdateTag(level.registryAccess()).getInt("VisualBoundaryMask")
                & dev.bwr.mod.reactor.ReactorBoundaryEffects.HEAD) != 0, "the damaged head model is no longer flagged");
        h.assertTrue(plant.reactor.severeEffects().blasts().isEmpty(), "the head blast left work behind");
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 4. Pipe break at pressure
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void pipeBreakWhipIsBoundedToTwoBlocks(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        BlockPos min = new BlockPos(701_600, 190, 700_000);
        clear(level, min.offset(-12, -4, -12), min.offset(22, 20, 22));
        var reactor = CompactCoreRegressionTests.build(level, min, WIDTH, DEPTH, 12, false);
        h.assertTrue(reactor.isFormed(), "fixture reactor did not form");
        BlockPos port = min.offset(4, 3, -1), outside = port.north();
        level.setBlock(port, BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState()
                .setValue(dev.bwr.mod.reactor.RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 3);
        for (int i = 0; i < 4; i++) level.setBlock(outside.north(i), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        SpargerRegressionTests.validate(reactor);
        h.assertTrue(reactor.isFormed(), "reactor lost formation with the port");
        // Things around the break: weak blocks at every axis distance, and a building behind.
        BlockPos[] probes = {outside.east(1), outside.east(2), outside.east(3), outside.above(1), outside.above(2), outside.above(3),
                outside.west(1), outside.west(2), outside.west(3), outside.below(1), outside.below(2), outside.below(3)};
        BlockPos farBuilding = outside.north(9).east(3);
        for (BlockPos p : probes) level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        for (int x = -4; x <= 4; x++) for (int y = -2; y <= 6; y++)
            level.setBlock(outside.north(7).offset(x, y, 0), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(farBuilding, Blocks.STONE_BRICKS.defaultBlockState(), 2);

        reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.FEEDWATER_LINE, 1500);
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
        reactor.severeEffects().blasts().runToCompletion(level, 20);

        h.assertTrue(level.getBlockState(outside).isAir(), "the pipe at the break survived");
        h.assertTrue(level.getBlockState(outside.north()).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),
                "the rest of the line was destroyed");
        for (int i = 0; i < probes.length; i++) {
            int distance = (i % 3) + 1;
            boolean gone = level.getBlockState(probes[i]).isAir();
            h.assertTrue(gone == (distance <= 2), "plank at " + distance + " blocks from the break at 1,500 psig: gone=" + gone);
        }
        for (int x = -4; x <= 4; x++) for (int y = -2; y <= 6; y++)
            h.assertTrue(level.getBlockState(outside.north(7).offset(x, y, 0)).is(Blocks.STONE_BRICKS),
                    "the building seven blocks away was damaged at " + x + "," + y);
        h.assertTrue(level.getBlockState(farBuilding).is(Blocks.STONE_BRICKS), "a block nine blocks away broke");
        h.assertTrue(level.getBlockState(port).is(BwrBlocks.RPV_WATER_INJECTION_PORT.get()), "the vessel port was destroyed");
        assertVesselIntact(h, level, reactor, "by the pipe whip");
        h.assertTrue(reactor.severeEffects().blasts().isEmpty(), "pipe whip left work behind");
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 5. Lower head failure
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void lowerHeadFailureBreaksThePedestalUnderTheVessel(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(704_400, 190, 704_000), false, Blocks.AIR);
        var level = plant.level;
        int floorTop = plant.min.getY() - 5;
        // A deep concrete floor under the vessel and well beyond it.
        BlockPos floorLo = plant.min.offset(-6, -22, -6), floorHi = new BlockPos(plant.min.getX() + 14, floorTop, plant.min.getZ() + 14);
        load(level, floorLo, floorHi);
        for (BlockPos p : BlockPos.betweenClosed(floorLo, floorHi)) level.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
        BlockPos under = new BlockPos(plant.min.getX() + 4, floorTop, plant.min.getZ() + 4);
        BlockPos deepUnder = under.below(16);
        BlockPos beside = new BlockPos(plant.min.getX() + 13, floorTop, plant.min.getZ() + 4);
        h.assertTrue(level.getBlockState(deepUnder).is(Blocks.GRAY_CONCRETE) && level.getBlockState(beside).is(Blocks.GRAY_CONCRETE),
                "fixture floor missing");
        var accident = plant.reactor.core().getSevereAccident();
        double[] a = accident.toArray();
        a[4] = 0.6;                    // ex-vessel fraction
        a[7] = 1;                      // lower head failed
        accident.fromArray(a);
        plant.reactor.severeEffects().lowerHeadFailed(plant.reactor);
        h.assertTrue(plant.reactor.severeEffects().blasts().has(AccidentBlast.Kind.PEDESTAL), "no pedestal blast was queued");
        plant.afterStep();
        finish(plant);
        h.assertTrue(level.getBlockState(under).isAir(), "the floor directly under the vessel centre stands");
        h.assertTrue(level.getBlockState(under.below(2)).isAir(), "the pedestal is only one layer deep");
        h.assertTrue(level.getBlockState(deepUnder).is(Blocks.GRAY_CONCRETE), "the pedestal blast reached the bottom of the slab");
        h.assertTrue(level.getBlockState(beside).is(Blocks.GRAY_CONCRETE), "the floor beside the footprint of the vessel broke");
        assertVesselIntact(h, plant, "by the lower head failure");
        // The corium pool waited for the pedestal, then anchored on what is left.
        for (int i = 0; i < 6; i++) plant.afterStep();
        h.assertTrue(plant.reactor.severeEffects().corium().isAnchored(), "corium never anchored after the pedestal blast");
        h.assertTrue(plant.reactor.severeEffects().corium().floorY() < floorTop, "corium anchored on a floor the blast removed");
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 6. Protection: unbreakable blocks, events of other mods, chunks
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void unbreakableBlocksSurviveAnyPressure(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = new BlockPos(702_800, 190, 700_000);
        clear(level, o.offset(-6, -3, -6), o.offset(6, 3, 6));
        BlockPos bedrock = o.east(1), frame = o.east(2), barrier = o.east(3), stone = o.east(4), obsidian = o.west(1);
        level.setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), 2);
        level.setBlock(frame, Blocks.END_PORTAL_FRAME.defaultBlockState(), 2);
        level.setBlock(barrier, Blocks.BARRIER.defaultBlockState(), 2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(obsidian, Blocks.OBSIDIAN.defaultBlockState(), 2);
        var queue = new AccidentBlast.Queue();
        queue.add(near(level, o, 1.0e6, 1.0e9));
        queue.runToCompletion(level, 50);
        h.assertTrue(level.getBlockState(stone).isAir(), "control: stone did not break under a million kilograms of TNT");
        h.assertTrue(level.getBlockState(obsidian).isAir(), "control: obsidian did not break under a million kilograms of TNT");
        h.assertTrue(level.getBlockState(bedrock).is(Blocks.BEDROCK), "bedrock broke");
        h.assertTrue(level.getBlockState(frame).is(Blocks.END_PORTAL_FRAME), "an end portal frame broke");
        h.assertTrue(level.getBlockState(barrier).is(Blocks.BARRIER), "a barrier broke");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 600)
    public static void aFormedVesselAndItsControllersSurviveAnyPressure(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(705_200, 190, 704_000), false, Blocks.AIR);
        var level = plant.level;
        var s = plant.reactor.structure();
        // Blast centred on the vessel itself with pressure no material resists (apart from what is protected by name).
        BlockPos[] box = {plant.min.offset(-14, -6, -14), plant.min.offset(22, 20, 22)};
        double cx = plant.min.getX() + 4.5, cy = plant.min.getY() + 6.5, cz = plant.min.getZ() + 4.5;
        BlockPos controller = plant.reactor.getBlockPos();
        var lamp = plant.min.offset(-4, 8, 4);
        level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 2);
        // No protection of its own: only the registry of formed reactors stands between this blast and the vessel.
        var bare = new AccidentBlast.Queue();
        bare.add(AccidentBlast.interior(level, cx, cy, cz, 1.0e6, 1.0e9, 10.0f, box));
        bare.runToCompletion(level, 100);
        h.assertTrue(level.getBlockState(lamp).isAir(), "control: the blast did not break the lamp beside the vessel");
        assertVesselIntact(h, plant, "by a blast with no protection of its own");
        // And with its own protected volume, from the source reactor.
        var named = new AccidentBlast.Queue();
        named.add(AccidentBlast.interior(level, cx, cy, cz, 1.0e6, 1.0e9, 10.0f, box).protect(s.interiorMin(), s.interiorMax()));
        named.runToCompletion(level, 100);
        assertVesselIntact(h, plant, "by a blast that names its reactor");
        h.assertTrue(level.getBlockState(controller).is(BwrBlocks.REACTOR_CONTROLLER.get()), "controller destroyed");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void cancelledBreakEventIsHonoured(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = new BlockPos(703_200, 190, 700_000);
        clear(level, o.offset(-6, -3, -6), o.offset(6, 3, 6));
        BlockPos protectedPane = o.east(2), openPane = o.east(3), protectedWall = o.west(2);
        level.setBlock(protectedPane, Blocks.GLASS_PANE.defaultBlockState(), 2);
        level.setBlock(openPane, Blocks.GLASS_PANE.defaultBlockState(), 2);
        level.setBlock(protectedWall, Blocks.GLASS_PANE.defaultBlockState(), 2);
        List<BlockPos> asked = new ArrayList<>();
        java.util.function.Consumer<BlockEvent.BreakEvent> claim = event -> {
            if (event.getLevel() != level) return;
            asked.add(event.getPos().immutable());
            if (event.getPos().equals(protectedPane) || event.getPos().equals(protectedWall)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(claim);
        try {
            var queue = new AccidentBlast.Queue();
            queue.add(near(level, o, 200.0, 20.0));
            queue.runToCompletion(level, 20);
        } finally {
            NeoForge.EVENT_BUS.unregister(claim);
        }
        h.assertTrue(asked.contains(openPane) && asked.contains(protectedPane), "no break event was posted for the blocks: " + asked);
        h.assertTrue(level.getBlockState(protectedPane).is(Blocks.GLASS_PANE), "a block whose break event was cancelled still broke");
        h.assertTrue(level.getBlockState(protectedWall).is(Blocks.GLASS_PANE), "a second cancelled block still broke");
        h.assertTrue(level.getBlockState(openPane).isAir(), "the uncancelled pane did not break");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void aPrunedDetonateListIsHonoured(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = new BlockPos(703_600, 190, 700_000);
        clear(level, o.offset(-6, -3, -6), o.offset(6, 3, 6));
        BlockPos a = o.east(2), b = o.east(3), c = o.west(2);
        for (BlockPos p : List.of(a, b, c)) level.setBlock(p, Blocks.GLASS_PANE.defaultBlockState(), 2);
        // Detonate: a protection mod prunes the list. Start: another cancels the whole slice.
        java.util.function.Consumer<ExplosionEvent.Detonate> prune = event -> {
            if (event.getLevel() == level) event.getAffectedBlocks().remove(b);
        };
        NeoForge.EVENT_BUS.addListener(prune);
        try {
            var queue = new AccidentBlast.Queue();
            queue.add(near(level, o, 200.0, 20.0));
            queue.runToCompletion(level, 20);
        } finally {
            NeoForge.EVENT_BUS.unregister(prune);
        }
        h.assertTrue(level.getBlockState(b).is(Blocks.GLASS_PANE), "a block pruned from the Detonate list still broke");
        h.assertTrue(level.getBlockState(a).isAir() && level.getBlockState(c).isAir(), "the unpruned panes did not break");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void aCancelledExplosionStartDiscardsTheBlast(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = new BlockPos(703_600, 190, 700_400);
        clear(level, o.offset(-6, -3, -6), o.offset(6, 3, 6));
        BlockPos a = o.east(2), c = o.west(2);
        for (BlockPos p : List.of(a, c)) level.setBlock(p, Blocks.GLASS_PANE.defaultBlockState(), 2);
        java.util.function.Consumer<ExplosionEvent.Start> stop = event -> {
            if (event.getLevel() == level) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(stop);
        try {
            var queue = new AccidentBlast.Queue();
            queue.add(near(level, o, 200.0, 20.0));
            queue.runToCompletion(level, 20);
            h.assertTrue(queue.isEmpty(), "a cancelled blast stayed queued");
        } finally {
            NeoForge.EVENT_BUS.unregister(stop);
        }
        h.assertTrue(level.getBlockState(a).is(Blocks.GLASS_PANE) && level.getBlockState(c).is(Blocks.GLASS_PANE),
                "a blast whose Start event was cancelled still broke blocks");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void blastInAnUnloadedChunkWaitsAndNeverLoadsIt(GameTestHelper h) {
        var level = h.getLevel();
        // The source is at the east edge of chunk A; the sweep reaches into chunk B, which nothing has loaded.
        int chunkAX = 45_000, chunkBX = chunkAX + 1, chunkZ = 45_000;
        BlockPos source = new BlockPos(chunkAX * 16 + 14, 190, chunkZ * 16 + 8);
        BlockPos inA = source.west(3), inB = new BlockPos(chunkBX * 16 + 3, 190, source.getZ());
        var chunkA = new net.minecraft.world.level.ChunkPos(chunkAX, chunkZ);
        var chunkB = new net.minecraft.world.level.ChunkPos(chunkBX, chunkZ);
        try (ChunkLab lab = new ChunkLab(level)) {
            level.getChunk(chunkAX, chunkZ);
            h.assertTrue(!lab.loaded(chunkBX, chunkZ), "fixture chunk B was already loaded");
            level.setBlock(inA, Blocks.GLASS_PANE.defaultBlockState(), 2);
            var job = AccidentBlast.interior(level, source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5, 200.0, 20.0, 4.0f,
                    new BlockPos[] {new BlockPos(chunkAX * 16 + 8, 188, chunkZ * 16 + 5), new BlockPos(chunkBX * 16 + 8, 192, chunkZ * 16 + 11)});
            h.assertTrue(job.pendingChunks().contains(chunkB), "the sweep does not cover chunk B: " + job.pendingChunks());
            var queue = new AccidentBlast.Queue();
            queue.add(job);
            queue.runToCompletion(level, 20);
            h.assertTrue(level.getBlockState(inA).isAir(), "the loaded part of the blast was not done");
            h.assertTrue(!lab.loaded(chunkBX, chunkZ), "the blast loaded the chunk it could not touch");
            h.assertTrue(lab.syncLoads.isEmpty(), "the blast loaded a chunk synchronously: " + lab.syncLoads);
            h.assertTrue(!queue.isEmpty() && job.pendingChunks().contains(chunkB),
                    "the unloaded part was dropped instead of deferred: " + job.pendingChunks());
            h.assertTrue(!job.pendingChunks().contains(chunkA), "the loaded chunk is still pending: " + job.pendingChunks());
            // Deferral survives a save.
            var saved = new CompoundTag();
            queue.write(saved);
            var restored = new AccidentBlast.Queue();
            restored.read(saved);
            h.assertTrue(!restored.isEmpty() && restored.jobs().get(0).pendingChunks().equals(job.pendingChunks()),
                    "the deferred chunks did not survive a save");
            // The chunk arrives; the deferred part is done at the block states then, and only then.
            level.getChunk(chunkBX, chunkZ);
            level.setBlock(inB, Blocks.GLASS_PANE.defaultBlockState(), 2);
            restored.runToCompletion(level, 20);
            h.assertTrue(level.getBlockState(inB).isAir(), "the deferred part was not done once the chunk loaded");
            h.assertTrue(restored.isEmpty(), "the job did not finish after its chunk loaded");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 7. Once, and never again after a reload
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void aFailureBlastNeverReplaysAfterReload(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(702_000, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        int top = plant.min.getY() + HEIGHT;
        BlockPos axis = plant.at(4, 0, 4);
        BlockPos above = new BlockPos(axis.getX(), top + 3, axis.getZ());
        load(level, above.offset(-48, 0, -48), above.offset(48, 0, 48));
        level.setBlock(above, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        plant.reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
        ReactorControllerBlockEntity.serverTick(level, plant.reactor.getBlockPos(), plant.reactor.getBlockState(), plant.reactor);
        finish(plant);
        h.assertTrue(level.getBlockState(above).isAir(), "the head failure did not break the block above the vessel");

        // Save, remove, reload from the saved tag, and put the block back: nothing may break it again.
        var root = plant.reactor.getBlockPos();
        var saved = plant.reactor.saveWithFullMetadata(level.registryAccess());
        h.assertTrue(!saved.contains(AccidentBlast.KEY), "a finished blast was saved");
        level.removeBlockEntity(root);
        var reloaded = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, level.getBlockState(root), saved, level.registryAccess());
        level.setBlockEntity(reloaded);
        SpargerRegressionTests.validate(reloaded);
        int head = dev.bwr.mod.reactor.ReactorBoundaryEffects.HEAD;
        h.assertTrue((saved.getInt("AppliedBoundaryFailures") & head) != 0, "the head failure was not saved as applied");
        h.assertTrue(reloaded.isFormed() && reloaded.structure() != null, "the reloaded reactor did not re-form: " + reloaded.statusLines());
        level.setBlock(above, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        for (int i = 0; i < 5; i++)
            ReactorControllerBlockEntity.serverTick(level, root, level.getBlockState(root), reloaded);
        reloaded.severeEffects().blasts().runToCompletion(level, 20);
        h.assertTrue(reloaded.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD), "reload healed the head");
        h.assertTrue((reloaded.saveWithFullMetadata(level.registryAccess()).getInt("AppliedBoundaryFailures") & head) != 0,
                "reload forgot that the head failure had been applied");
        h.assertTrue(level.getBlockState(above).is(Blocks.STONE_BRICKS), "reload replayed the head failure blast");
        h.assertTrue(reloaded.severeEffects().blasts().isEmpty(), "reload queued a blast");

        // A half-done blast resumes, and finishes exactly once.
        BlockPos glassLo = above.offset(-12, 6, -12);
        clear(level, glassLo, glassLo.offset(24, 3, 24));
        for (int x = 0; x <= 24; x++) for (int z = 0; z <= 24; z++) for (int y = 0; y <= 3; y++)
            level.setBlock(glassLo.offset(x, y, z), Blocks.GLASS.defaultBlockState(), 2); // 2,500 blocks: three ticks of budget
        var queue = reloaded.severeEffects().blasts();
        double cx = glassLo.getX() + 12.5, cy = glassLo.getY() + 1.5, cz = glassLo.getZ() + 12.5;
        queue.add(AccidentBlast.interior(level, cx, cy, cz, 50.0, 20.0, 4.0f, null));
        queue.run(level);
        int left = glassLeft(level, glassLo);
        h.assertTrue(left > 0 && left < 2_500, "the first tick should have broken some but not all: " + left + " left");
        h.assertTrue(!queue.isEmpty(), "a half-done blast is not queued");
        var mid = reloaded.saveWithFullMetadata(level.registryAccess());
        h.assertTrue(mid.contains(AccidentBlast.KEY), "a half-done blast was not saved");
        level.removeBlockEntity(root);
        var resumed = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, level.getBlockState(root), mid, level.registryAccess());
        level.setBlockEntity(resumed);
        SpargerRegressionTests.validate(resumed);
        h.assertTrue(!resumed.severeEffects().blasts().isEmpty(), "reload dropped the half-done blast");
        resumed.severeEffects().blasts().runToCompletion(level, 50);
        h.assertTrue(glassLeft(level, glassLo) < left, "the resumed blast did no work");
        h.assertTrue(resumed.severeEffects().blasts().isEmpty(), "the resumed blast did not finish");
        // Whatever stands now is beyond the reach of the blast, not skipped by the resume: the model says which.
        double glass = ContainmentWallStrength.psi(Blocks.GLASS.getExplosionResistance());
        for (int x = 0; x <= 24; x++) for (int z = 0; z <= 24; z++) for (int y = 0; y <= 3; y++) {
            BlockPos p = glassLo.offset(x, y, z);
            double d = Math.sqrt(sq(p.getX() + 0.5 - cx) + sq(p.getY() + 0.5 - cy) + sq(p.getZ() + 0.5 - cz));
            boolean expected = Math.min(20.0, BlastPhysics.overpressurePsi(50.0, Math.max(d, 0.5))) > glass;
            h.assertTrue(level.getBlockState(p).isAir() == expected,
                    "pane at " + p.toShortString() + " broken=" + level.getBlockState(p).isAir() + " but the model says " + expected);
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void theAppliedFailureFlagsSurviveAReload(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(702_400, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        int head = dev.bwr.mod.reactor.ReactorBoundaryEffects.HEAD;
        plant.reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
        ReactorControllerBlockEntity.serverTick(level, plant.reactor.getBlockPos(), plant.reactor.getBlockState(), plant.reactor);
        finish(plant);
        var saved = plant.reactor.saveWithFullMetadata(level.registryAccess());
        h.assertTrue((saved.getInt("AppliedBoundaryFailures") & head) != 0, "the head failure was not saved as applied");
        var root = plant.reactor.getBlockPos();
        level.removeBlockEntity(root);
        var reloaded = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, level.getBlockState(root), saved, level.registryAccess());
        level.setBlockEntity(reloaded);
        // Straight after the load, before anything ticks: the failure is still marked as done, so nothing will fire it again.
        h.assertTrue((reloaded.saveWithFullMetadata(level.registryAccess()).getInt("AppliedBoundaryFailures") & head) != 0,
                "a reload forgot that the head failure had been applied");
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 8. An accident does not stop because the vessel is no longer formed
    // ------------------------------------------------------------------

    /** Core damage and contamination in the snapshot, as a melt that has begun. */
    private static void melt(ReactorControllerBlockEntity reactor) {
        var accident = reactor.core().getSevereAccident();
        double[] a = accident.toArray();
        a[1] = 1.0;                    // failed cladding
        a[2] = 0.5;                    // molten in the core
        a[13] = dev.bwr.core.accident.SevereAccident.Stage.FUEL_MELT.ordinal();
        a[14] = 1;                     // melt event already fired: nothing to replay
        accident.fromArray(a);
        reactor.core().getContainment().releaseDirectToEnvironment(0.5);
    }

    private static void tick(ReactorControllerBlockEntity reactor, int ticks) {
        for (int i = 0; i < ticks; i++)
            ReactorControllerBlockEntity.serverTick(reactor.getLevel(), reactor.getBlockPos(), reactor.getBlockState(), reactor);
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 800)
    public static void aMeltKeepsDosingAndBlastingWhenAShellBlockIsMined(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(703_000, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        var reactor = plant.reactor;
        BlockPos glassLo = plant.min.offset(-8, HEIGHT + 8, -8);
        // Every chunk the blast can reach is loaded here: a blast waits for an unloaded chunk and never loads it.
        load(level, glassLo.offset(-48, 0, -48), glassLo.offset(72, 0, 72));
        clear(level, glassLo, glassLo.offset(24, 3, 24));
        for (int x = 0; x <= 24; x++) for (int z = 0; z <= 24; z++) for (int y = 0; y <= 3; y++)
            level.setBlock(glassLo.offset(x, y, z), Blocks.GLASS.defaultBlockState(), 2);
        var player = NewFuelElevatorTests.player(level, "meltdown-unformed",
                new net.minecraft.world.phys.Vec3(plant.min.getX() - 4, plant.min.getY() + 2, plant.min.getZ() + 4));
        level.addNewPlayer(player);
        try {
            melt(reactor);
            h.assertTrue(reactor.severeEffects().accidentInProgress(reactor.core()), "a melting core is not an accident in progress");
            double cx = glassLo.getX() + 12.5, cy = glassLo.getY() + 1.5, cz = glassLo.getZ() + 12.5;
            reactor.severeEffects().blasts().add(AccidentBlast.interior(level, cx, cy, cz, 50.0, 20.0, 4.0f, null));
            player.removeAllEffects();

            // Formed: the first tick starts the blast.
            tick(reactor, 1);
            int left = glassLeft(level, glassLo);
            h.assertTrue(left > 0 && left < 2_500, "the first tick should have broken some but not all: " + left + " left");
            h.assertTrue(!reactor.severeEffects().blasts().isEmpty(), "the blast finished in one tick");

            // Mine one block of the shell.
            BlockPos shell = plant.min.offset(WIDTH, 4, 4);
            h.assertTrue(level.getBlockState(shell).is(BwrBlocks.REACTOR_VESSEL.get()), "fixture: no shell block at " + shell.toShortString());
            level.destroyBlock(shell, false);
            reactor.markStructureDirty();
            tick(reactor, 1);
            h.assertTrue(reactor.structure() == null && !reactor.isFormed(), "the vessel is still formed with a hole in the shell");
            h.assertTrue(reactor.core() != null, "the core was discarded with the structure");

            double clock = reactor.core().getElapsedSeconds();
            int before = glassLeft(level, glassLo);
            player.removeAllEffects();
            tick(reactor, 45);
            h.assertTrue(reactor.structure() == null, "the vessel re-formed on its own");
            h.assertTrue(reactor.core().getElapsedSeconds() > clock, "the core froze with the structure");
            h.assertTrue(glassLeft(level, glassLo) < before,
                    "the queued blast stopped when the shell block was mined: " + before + " -> " + glassLeft(level, glassLo));
            h.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER) && player.hasEffect(net.minecraft.world.effect.MobEffects.WITHER),
                    "contamination stopped dosing when the shell block was mined: " + player.getActiveEffects());
            tick(reactor, 60);
            h.assertTrue(reactor.severeEffects().blasts().isEmpty(), "the blast never finished with the vessel unformed");
            h.succeed();
        } finally {
            level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            level.removeBlock(reactor.getBlockPos(), false);
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void anUndamagedUnformedReactorStaysFrozenAsBefore(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(703_400, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        var reactor = plant.reactor;
        try {
            tick(reactor, 2);
            h.assertTrue(!reactor.severeEffects().accidentInProgress(reactor.core()), "a healthy core counts as an accident");
            level.destroyBlock(plant.min.offset(WIDTH, 4, 4), false);
            reactor.markStructureDirty();
            tick(reactor, 1);
            h.assertTrue(reactor.structure() == null, "the vessel is still formed with a hole in the shell");
            double clock = reactor.core().getElapsedSeconds();
            tick(reactor, 20);
            h.assertTrue(reactor.core().getElapsedSeconds() == clock, "a healthy unformed reactor stepped: " + clock + " -> " + reactor.core().getElapsedSeconds());
            h.succeed();
        } finally {
            level.removeBlock(reactor.getBlockPos(), false);
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "accidentblast", timeoutTicks = 400)
    public static void theLowerHeadStillBreaksThePedestalWithTheVesselUnformed(GameTestHelper h) throws Exception {
        var plant = new Plant(h, new BlockPos(703_800, 190, 700_000), false, Blocks.AIR);
        var level = plant.level;
        var reactor = plant.reactor;
        int floorTop = plant.min.getY() - 5;
        BlockPos floorLo = plant.min.offset(-6, -12, -6), floorHi = new BlockPos(plant.min.getX() + 14, floorTop, plant.min.getZ() + 14);
        load(level, floorLo, floorHi);
        for (BlockPos p : BlockPos.betweenClosed(floorLo, floorHi)) level.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
        try {
            tick(reactor, 1); // remembers the vessel box while it is formed
            level.destroyBlock(plant.min.offset(WIDTH, 4, 4), false);
            reactor.markStructureDirty();
            tick(reactor, 1);
            h.assertTrue(reactor.structure() == null, "the vessel is still formed with a hole in the shell");
            var accident = reactor.core().getSevereAccident();
            double[] a = accident.toArray();
            a[4] = 0.6;
            a[7] = 1;
            accident.fromArray(a);
            reactor.severeEffects().lowerHeadFailed(reactor);
            h.assertTrue(reactor.severeEffects().blasts().has(AccidentBlast.Kind.PEDESTAL), "no pedestal blast was queued with the vessel unformed");
            reactor.severeEffects().blasts().runToCompletion(level, 200);
            BlockPos under = new BlockPos(plant.min.getX() + 4, floorTop, plant.min.getZ() + 4);
            h.assertTrue(level.getBlockState(under).isAir(), "the floor under the unformed vessel stands");
            // and the box survives a save: it is not lost to a chunk unload
            var saved = reactor.saveWithFullMetadata(level.registryAccess());
            h.assertTrue(saved.contains("AccidentVesselMin") && saved.contains("AccidentVesselMax"), "the vessel box was not saved");
            h.succeed();
        } finally {
            level.removeBlock(reactor.getBlockPos(), false);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int glassLeft(ServerLevel level, BlockPos lo) {
        int n = 0;
        for (int x = 0; x <= 24; x++) for (int z = 0; z <= 24; z++) for (int y = 0; y <= 3; y++)
            if (level.getBlockState(lo.offset(x, y, z)).is(Blocks.GLASS)) n++;
        return n;
    }
}
