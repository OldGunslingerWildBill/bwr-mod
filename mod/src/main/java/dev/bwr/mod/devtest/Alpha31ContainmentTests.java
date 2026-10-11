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
