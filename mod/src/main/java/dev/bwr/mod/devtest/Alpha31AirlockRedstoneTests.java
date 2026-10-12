package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
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
public final class Alpha31AirlockRedstoneTests {
    private Alpha31AirlockRedstoneTests() {}

    private static void pulse(ServerLevel level, BlockPos switchPos) {
        level.setBlock(switchPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        level.setBlock(switchPos, Blocks.AIR.defaultBlockState(), 3);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void redstonePulsesCycleInterlockedLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos root = new BlockPos(45312, 220, 45312);
        for (int x = (root.getX() - 8) >> 4; x <= (root.getX() + 8) >> 4; x++)
            for (int z = (root.getZ() - 8) >> 4; z <= (root.getZ() + 8) >> 4; z++)
                level.setChunkForced(x, z, true);
        var block = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state = block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.EAST);
        for (int i = 0; i < 6; i++) level.setBlock(block.cell(root, Direction.EAST, i), Blocks.AIR.defaultBlockState(), 2);
        BlockPos switchPos = root.north();
        level.setBlock(switchPos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
        var door = (ContainmentAirlockBlockEntity) level.getBlockEntity(root);

        pulse(level, switchPos);
        helper.assertTrue(door.innerOpen() && !door.outerOpen() && !door.override(),
                "first redstone pulse did not open only the inner leaf");
        pulse(level, switchPos);
        helper.assertTrue(!door.innerOpen() && !door.outerOpen() && !door.override(),
                "second redstone pulse did not close the inner leaf");
        pulse(level, switchPos);
        helper.assertTrue(!door.innerOpen() && door.outerOpen() && !door.override(),
                "third redstone pulse did not open only the outer leaf");
        pulse(level, switchPos);
        helper.assertTrue(!door.innerOpen() && !door.outerOpen() && !door.override(),
                "fourth redstone pulse did not close the outer leaf");
        door.setComputerControlled(true);
        door.setOverride(true);
        helper.assertTrue(door.innerOpen() && door.outerOpen() && door.override(),
                "CC override could not force both leaves open");

        for (int i = 0; i < 6; i++) level.removeBlock(block.cell(root, Direction.EAST, i), false);
        for (int x = (root.getX() - 8) >> 4; x <= (root.getX() + 8) >> 4; x++)
            for (int z = (root.getZ() - 8) >> 4; z <= (root.getZ() + 8) >> 4; z++)
                level.setChunkForced(x, z, false);
        helper.succeed();
    }
}
