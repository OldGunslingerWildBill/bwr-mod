package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31AirlockReviewTests {
    private Alpha31AirlockReviewTests() {}

    private static void hold(ServerLevel level, BlockPos center, int radius, boolean forced) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++)
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++)
                level.setChunkForced(x, z, forced);
    }

    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for (int i = 0; i < 64; i++) {
            ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(), controller.getBlockState(), controller);
            if (!controller.isScanning() && (controller.strengthReady() || !controller.isSealed())) return;
        }
        throw new AssertionError("airlock review survey unfinished: " + controller.statusLines());
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void releasedOverrideAndReloadKeepMediumLeak(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(45212, 190, 45212);
        hold(level, origin, 56, true);
        for (int x = -4; x <= 14; x++) for (int z = -4; z <= 14; z++) {
            level.getChunk((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4);
            for (int y = -4; y <= 11; y++) {
                boolean shell = x == -4 || x == 14 || z == -4 || z == 14 || y == -4 || y == 11;
                level.setBlock(origin.offset(x, y, z), (shell ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 2);
            }
        }
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(level, origin, 11, 11, 8, false);
        helper.assertTrue(reactor.isFormed(), "review vessel did not form");
        BlockPos root = origin.offset(14, 1, 5);
        ContainmentAirlockBlock block = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state = block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.EAST);
        for (int x = 15; x <= 16; x++) for (int z = 4; z <= 6; z++) for (int y = 0; y <= 3; y++) {
            boolean shell = z == 4 || z == 6 || y == 0 || y == 3;
            level.setBlock(origin.offset(x, y, z), (shell ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 2);
        }
        for (int i = 0; i < 6; i++) level.setBlock(block.cell(root, Direction.EAST, i), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
        var door = (ContainmentAirlockBlockEntity) level.getBlockEntity(root);
        BlockPos controllerPos = origin.offset(-2, 0, -2);
        level.setBlock(controllerPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var controller = (ContainmentControllerBlockEntity) level.getBlockEntity(controllerPos);
        survey(level, controller);
        helper.assertTrue(controller.isSealed() && controller.linkedReactors().contains(reactor.getBlockPos()),
                "review reactor was not linked before opening");
        door.setComputerControlled(true);
        door.setOverride(true);
        survey(level, controller);
        door.setOverride(false);
        survey(level, controller);
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
        helper.assertTrue(!door.override() && door.innerOpen() && door.outerOpen()
                        && controller.isForcedAirlockLeak() && reactor.core().getContainment().getLeakArea() == 0.35,
                "releasing override changed a still-open airlock's medium leak");

        var saved = controller.saveWithoutMetadata(level.registryAccess());
        level.removeBlockEntity(controllerPos);
        var restored = new ContainmentControllerBlockEntity(controllerPos, level.getBlockState(controllerPos));
        restored.loadWithComponents(saved, level.registryAccess());
        level.setBlockEntity(restored);
        restored.onLoad();
        helper.assertTrue(!restored.encloses(reactor.getBlockPos())
                        && restored.linkedReactors().contains(reactor.getBlockPos())
                        && ContainmentControllerRegistry.forcedAirlockFor(level, reactor.getBlockPos()) == restored,
                "saved reactor link did not preserve the medium leak before resurvey: links="
                        + restored.linkedReactors() + " forced=" + restored.isForcedAirlockLeak()
                        + " leak=" + restored.leak() + " stale=" + restored.isStale());
        ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea() == 0.35,
                "reloaded airlock leak did not reach reactor physics");
        hold(level, origin, 56, false);
        helper.succeed();
    }
}
