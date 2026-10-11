package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerRegistry;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-world room surveys, breach repair, persistence and reactor linkage. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31ContainmentTests {
    private Alpha31ContainmentTests() {}

    private static ContainmentControllerBlockEntity place(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        return (ContainmentControllerBlockEntity) level.getBlockEntity(pos);
    }

    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for (int i = 0; i < 32; i++) {
            ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(),
                    level.getBlockState(controller.getBlockPos()), controller);
            if (!controller.isScanning()) return;
        }
        throw new AssertionError("containment scan exceeded 32 ticks");
    }

    private static ContainmentControllerBlockEntity cube(ServerLevel level, BlockPos center,
                                                         int layers, Block wall) {
        int radius = layers + 1;
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    boolean outside = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) > 1;
                    level.setBlock(pos, (outside ? wall : Blocks.AIR).defaultBlockState(), 2);
                }
        var controller = place(level, center);
        survey(level, controller);
        return controller;
    }

    /** A hollow box: {@code wall} on every cell with a coordinate on {@code lo} or {@code hi}, air inside. */
    private static void hollow(ServerLevel level, BlockPos lo, BlockPos hi, Block wall) {
        for (int x = lo.getX(); x <= hi.getX(); x++)
            for (int z = lo.getZ(); z <= hi.getZ(); z++) {
                level.getChunk(x >> 4, z >> 4);
                for (int y = lo.getY(); y <= hi.getY(); y++) {
                    boolean shell = x == lo.getX() || x == hi.getX() || y == lo.getY() || y == hi.getY()
                            || z == lo.getZ() || z == hi.getZ();
                    level.setBlock(new BlockPos(x, y, z), (shell ? wall : Blocks.AIR).defaultBlockState(), 2);
                }
            }
    }

    private static void tickReactor(ServerLevel level, ReactorControllerBlockEntity reactor) {
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(),
                level.getBlockState(reactor.getBlockPos()), reactor);
    }

    private static void tickController(ServerLevel level, ContainmentControllerBlockEntity controller) {
        ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(),
                level.getBlockState(controller.getBlockPos()), controller);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void sealedReactorStaysContainedWhileRatingsRecompute(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(36000, 190, 36000);
        int width = 11, depth = 11, height = 8;
        // 55 x 24 x 55 interior: over 11,000 boundary faces, so one rating pass
        // outlasts the tick its survey completes in (two 4,000-face budgets).
        hollow(level, min.offset(-23, -4, -23), min.offset(width + 22, height + 13, depth + 22), Blocks.STONE);
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(
                level, min, width, depth, height, false);
        helper.assertTrue(reactor.isFormed(), "reference vessel did not form");
        var containment = place(level, min.offset(-10, 0, -10));
        survey(level, containment);
        helper.assertTrue(containment.isSealed() && containment.linkedReactors().contains(reactor.getBlockPos()),
                "large reactor room did not seal and link: " + containment.statusLines());
        helper.assertTrue(containment.boundaryFaces().size() > 8_000,
                "room too small to span rating ticks: " + containment.boundaryFaces().size() + " faces");
        for (int i = 0; i < 32 && !containment.strengthReady(); i++) tickController(level, containment);
        helper.assertTrue(containment.strengthReady(), "initial ratings never finished");
        tickReactor(level, reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea() == 0.0, "sealed room not linked");

        // Timer revalidation: survey again, then rebuild every rating over several ticks.
        containment.markDirty();
        boolean recomputing = false;
        for (int tick = 0; tick < 64; tick++) {
            tickController(level, containment);
            tickReactor(level, reactor);
            recomputing |= !containment.isScanning() && !containment.strengthReady();
            helper.assertTrue(reactor.core().getContainment().getLeakArea() == 0.0,
                    "sealed room went uncontained on revalidation tick " + tick
                            + ": scanning=" + containment.isScanning()
                            + " strengthReady=" + containment.strengthReady());
            if (recomputing && containment.strengthReady()) break;
        }
        helper.assertTrue(recomputing, "revalidation never recomputed ratings across ticks");
        helper.assertTrue(containment.strengthReady(), "rating recompute never finished");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void rebuiltBreachRestoresContainment(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(38000, 190, 38000);
        int width = 11, depth = 11, height = 8;
        hollow(level, min.offset(-4, -4, -4), min.offset(width + 3, height + 2, depth + 3), Blocks.STONE);
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(
                level, min, width, depth, height, false);
        helper.assertTrue(reactor.isFormed(), "reference vessel did not form");
        var containment = place(level, min.offset(-3, 0, -3));
        BlockPos weakWall = min.offset(-4, 0, 0);
        level.setBlock(weakWall, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        survey(level, containment);
        helper.assertTrue(containment.isSealed() && containment.strengthReady()
                        && weakWall.equals(containment.weakestSpot().position()),
                "plank wall was not the weakest spot: " + containment.statusLines());
        tickReactor(level, reactor);
        var atmosphere = reactor.core().getContainment();
        double[] ambient = atmosphere.toArray();
        double[] gas = atmosphere.toArray();
        gas[2] *= 2.0;
        gas[3] *= 2.0;
        atmosphere.fromArray(gas);
        tickReactor(level, reactor);
        helper.assertTrue(level.getBlockState(weakWall).isAir() && !containment.isSealed()
                        && atmosphere.getFailureMode() == dev.bwr.core.accident.ContainmentAtmosphere.FailureMode.OVERPRESSURE,
                "overpressure did not fail containment and open the plank wall: " + atmosphere);
        double peak = atmosphere.getPeakPressurePsig();
        tickReactor(level, reactor);
        helper.assertTrue(atmosphere.getLeakArea() == 1.0 && atmosphere.isFailed(),
                "breached containment was not open and failed");
        survey(level, containment);
        helper.assertTrue(!containment.isSealed() && weakWall.equals(containment.leak()),
                "survey did not report the blown-out wall");

        // The hole has vented the overpressure by the time the wall is rebuilt.
        double[] vented = atmosphere.toArray();
        vented[2] = ambient[2];
        vented[3] = ambient[3];
        atmosphere.fromArray(vented);
        level.setBlock(weakWall, Blocks.STONE.defaultBlockState(), 2);
        survey(level, containment);
        helper.assertTrue(containment.isSealed(), "rebuilt wall did not reseal: " + containment.statusLines());
        tickReactor(level, reactor);
        helper.assertTrue(atmosphere.getLeakArea() == 0.0 && !atmosphere.isFailed(),
                "rebuilt and resealed containment still reports failure: " + atmosphere);
        helper.assertTrue(atmosphere.getPeakPressurePsig() == peak, "repair erased the peak pressure");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void lShapedRoomDoesNotLinkReactorInItsNotch(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(40000, 190, 40000);
        hollow(level, origin, origin.offset(40, 14, 40), Blocks.STONE);
        // Partition off the notch: the containment is the L along the north and
        // west sides, and the square room in the south-east corner is next door.
        for (int i = 9; i < 40; i++)
            for (int y = 1; y < 14; y++) {
                level.setBlock(origin.offset(9, y, i), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(origin.offset(i, y, 9), Blocks.STONE.defaultBlockState(), 2);
            }
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(
                level, origin.offset(20, 3, 20), 11, 11, 8, false);
        helper.assertTrue(reactor.isFormed(), "reference vessel did not form");
        var containment = place(level, origin.offset(4, 3, 4));
        survey(level, containment);
        BlockPos reactorPos = reactor.getBlockPos();
        helper.assertTrue(containment.isSealed() && containment.contains(reactorPos),
                "L-shaped room did not seal around a box covering the notch: " + containment.statusLines());
        helper.assertTrue(!containment.linkedReactors().contains(reactorPos),
                "reactor in the room next door was linked by the L's bounding box");
        helper.assertTrue(ContainmentControllerRegistry.linkedReactor(level, reactorPos) == null,
                "registry linked a reactor outside the L-shaped interior");
        tickReactor(level, reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea() == 1.0,
                "reactor outside the L-shaped containment ran sealed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void firstSurveyReportsLeakAtTheGap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A brand-new controller has no previous sealed box to compare against,
        // and the search runs about 20 blocks up to the build limit before it
        // gives up. The readout must still name the one-block hole.
        BlockPos sideCenter = new BlockPos(42008, 296, 42008);
        BlockPos roofCenter = new BlockPos(42040, 296, 42008);
        for (int cx = (42008 - 32) >> 4; cx <= (42040 + 32) >> 4; cx++)
            for (int cz = (42008 - 32) >> 4; cz <= (42008 + 32) >> 4; cz++) level.getChunk(cx, cz);
        hollow(level, sideCenter.offset(-2, -2, -2), sideCenter.offset(2, 2, 2), Blocks.STONE);
        hollow(level, roofCenter.offset(-2, -2, -2), roofCenter.offset(2, 2, 2), Blocks.STONE);
        BlockPos sideGap = sideCenter.east(2);
        BlockPos roofGap = roofCenter.above(2);
        level.setBlock(sideGap, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(roofGap, Blocks.AIR.defaultBlockState(), 2);
        var side = place(level, sideCenter);
        survey(level, side);
        helper.assertTrue(!side.isSealed() && side.leak() != null && side.leak().distManhattan(sideGap) <= 1,
                "first survey put the side-wall leak far from the gap " + sideGap.toShortString()
                        + ": " + side.statusLines());
        var roof = place(level, roofCenter);
        survey(level, roof);
        helper.assertTrue(!roof.isSealed() && roof.leak() != null && roof.leak().distManhattan(roofGap) <= 1,
                "first survey put the roof leak far from the gap " + roofGap.toShortString()
                        + ": " + roof.statusLines());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void layeredRatingsDepthCapAndWeakestColumnRupture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wood = cube(level, new BlockPos(32008, 230, 32008), 3, Blocks.OAK_PLANKS);
        helper.assertTrue(wood.isSealed() && wood.strengthReady()
                && wood.failurePressurePsig() > 29 && wood.failurePressurePsig() < 31,
                "three planks did not rate about 30 psig: " + wood.statusLines());
        var obsidian = cube(level, new BlockPos(32104, 230, 32104), 2, Blocks.OBSIDIAN);
        helper.assertTrue(obsidian.failurePressurePsig() > 140,
                "two obsidian layers did not exceed 140 psig: " + obsidian.statusLines()
                        + " strengthReady=" + obsidian.strengthReady()
                        + " rawResistance=" + level.getBlockState(obsidian.getBlockPos().east(2))
                        .getExplosionResistance(level, obsidian.getBlockPos().east(2), null));

        BlockPos deepStart = new BlockPos(32208, 230, 32208);
        for (int i = 0; i < 20; i++) {
            BlockPos pos = deepStart.east(i);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        var deep = ContainmentStrength.rate(level,
                new ContainmentControllerBlockEntity.BoundaryFace(deepStart, Direction.EAST), 16);
        helper.assertTrue(deep != null && deep.deep() && deep.layerCount() == 16,
                "20-deep stone column ignored the scan depth cap");

        BlockPos failureCenter = new BlockPos(32312, 317, 32312);
        var weak = cube(level, failureCenter, 1, Blocks.STONE);
        BlockPos roof = failureCenter.above(2);
        level.setBlock(roof, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        survey(level, weak);
        helper.assertTrue(roof.equals(weak.weakestSpot().position()),
                "wood roof was not the weakest spot: " + weak.statusLines());
        helper.assertTrue(roof.equals(weak.ruptureWeakest(level)), "rupture did not choose wood roof");
        helper.assertTrue(level.getBlockState(roof).isAir()
                && level.getBlockState(failureCenter.north(2)).is(Blocks.STONE),
                "rupture broke another column or left the weak one intact");
        survey(level, weak);
        helper.assertTrue(!weak.isSealed() && roof.equals(weak.leak()),
                "rupture did not leave a reported hole");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void stoneRoomBreachRepairAndReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = new BlockPos(28008, 317, 28008);
        level.getChunk(center.getX() >> 4, center.getZ() >> 4);
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            boolean shell = Math.abs(x) == 2 || Math.abs(y) == 2 || Math.abs(z) == 2;
            level.setBlock(center.offset(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
        }
        var controller = place(level, center);
        survey(level, controller);
        helper.assertTrue(controller.isSealed() && controller.freeVolume() == 27,
                "5x5x5 stone room should have 27 interior cells: " + controller.statusLines());

        BlockPos roof = center.above(2);
        level.setBlock(roof, Blocks.AIR.defaultBlockState(), 2);
        survey(level, controller);
        helper.assertTrue(!controller.isSealed() && roof.equals(controller.leak()),
                "open roof did not report the breach: " + controller.statusLines());
        helper.assertTrue(ContainmentControllerRegistry.linkedReactor(level, center) == null,
                "leaking room linked a reactor");

        level.setBlock(roof, Blocks.STONE.defaultBlockState(), 2);
        survey(level, controller);
        helper.assertTrue(controller.isSealed() && controller.leak() == null,
                "repaired roof did not reseal");

        BlockPos side = center.east(2);
        level.setBlock(side, Blocks.GLASS.defaultBlockState(), 2);
        survey(level, controller);
        helper.assertTrue(controller.isSealed(), "full-cube glass should seal");
        level.setBlock(side, Blocks.HONEY_BLOCK.defaultBlockState(), 2);
        survey(level, controller);
        helper.assertTrue(!controller.isSealed() && side.equals(controller.leak()),
                "gas-permeable honey block should leak at its position");
        level.setBlock(side, Blocks.STONE.defaultBlockState(), 2);
        survey(level, controller);
        helper.assertTrue(controller.isSealed(), "replaced side wall did not reseal");

        var state = level.getBlockState(center);
        var saved = controller.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(center);
        var restored = (ContainmentControllerBlockEntity) BlockEntity.loadStatic(
                center, state, saved, level.registryAccess());
        level.setBlockEntity(restored);
        helper.assertTrue(restored.isSealed() && restored.freeVolume() == 27
                && restored.interiorMin().equals(controller.interiorMin())
                && restored.interiorMax().equals(controller.interiorMax())
                && restored.weakestSpot() != null
                && restored.weakestSpot().position().equals(controller.weakestSpot().position())
                && restored.weakestSpot().ratingPsig() == controller.weakestSpot().ratingPsig(),
                "containment room result lost on reload");
        survey(level, restored);
        helper.assertTrue(restored.isSealed(), "reloaded room failed resurvey");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void formedReactorUsesMeasuredRoomVolume(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(30000, 190, 30000);
        int width = 11, depth = 11, height = 8;
        for (int x = -4; x <= width + 3; x++)
            for (int z = -4; z <= depth + 3; z++)
                for (int y = -4; y <= height + 2; y++) {
                    BlockPos pos = min.offset(x, y, z);
                    level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    boolean wall = x == -4 || x == width + 3 || z == -4 || z == depth + 3
                            || y == -4 || y == height + 2;
                    level.setBlock(pos, (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(
                level, min, width, depth, height, false);
        helper.assertTrue(reactor.isFormed(), "reference vessel did not form");
        BlockPos controllerPos = min.offset(-3, 0, -3);
        var containment = place(level, controllerPos);
        survey(level, containment);
        helper.assertTrue(containment.isSealed(), "reactor containment did not seal: " + containment.statusLines());
        helper.assertTrue(containment.linkedReactors().contains(reactor.getBlockPos()),
                "formed reactor was not linked to the room");
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(),
                level.getBlockState(reactor.getBlockPos()), reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea() == 0.0
                && reactor.core().getContainment().getVolumeM3() == containment.freeVolume(),
                "measured room volume not installed in reactor physics: leakArea="
                        + reactor.core().getContainment().getLeakArea() + " volume="
                        + reactor.core().getContainment().getVolumeM3() + " measured=" + containment.freeVolume()
                        + " formed=" + reactor.isFormed());

        BlockPos weakWall = min.offset(-4, 0, 0);
        level.setBlock(weakWall, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        survey(level, containment);
        helper.assertTrue(containment.strengthReady() && weakWall.equals(containment.weakestSpot().position()),
                "reactor room did not identify the weak outer wall");
        double[] gas = reactor.core().getContainment().toArray();
        gas[2] *= 2.0;
        gas[3] *= 2.0;
        reactor.core().getContainment().fromArray(gas);
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(),
                level.getBlockState(reactor.getBlockPos()), reactor);
        helper.assertTrue(level.getBlockState(weakWall).isAir()
                && level.getBlockState(weakWall.south()).is(Blocks.STONE)
                && !containment.isSealed() && weakWall.equals(containment.leak()),
                "reactor overpressure event did not rupture only the weakest column");

        level.removeBlock(controllerPos, false);
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(),
                level.getBlockState(reactor.getBlockPos()), reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea() == 1.0,
                "reactor stayed sealed after containment controller removal");
        helper.succeed();
    }
}
