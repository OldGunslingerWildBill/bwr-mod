package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Reloading a chunk with adjacent panels must not recursively instantiate each neighbour. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelChunkReloadTests {
    private PanelChunkReloadTests() {}

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void adjacentPanelsSurviveChunkReload(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = new BlockPos(56_900, 110, 56_900);
        BlockPos second = first.west();
        int cx = first.getX() >> 4, cz = first.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        try {
            level.getChunk(cx, cz);
            for (BlockPos pos : new BlockPos[] {first, second}) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
            helper.assertTrue(PanelOps.placeDirect(level, first, 1, 1,
                    ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4)) != null, "first gauge did not fit");
            helper.assertTrue(PanelOps.placeDirect(level, second, 1, 1,
                    ComponentTypes.PUSHBUTTON, Footprint.of(3, 3)) != null, "second button did not fit");
        } finally {
            level.setChunkForced(cx, cz, false);
        }
        helper.runAfterDelay(100, () -> {
            boolean unloaded = !level.hasChunk(cx, cz);
            level.setChunkForced(cx, cz, true);
            try {
                helper.assertTrue(unloaded, "fixture chunk did not unload before reload");
                level.getChunk(cx, cz);
                helper.assertTrue(level.getBlockEntity(first) instanceof PanelBlockEntity one
                                && one.ownedCount() == 1, "first panel lost its gauge after reload");
                helper.assertTrue(level.getBlockEntity(second) instanceof PanelBlockEntity two
                                && two.ownedCount() == 1, "second panel lost its button after reload");
            } finally {
                level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
                level.setChunkForced(cx, cz, false);
            }
            helper.succeed();
        });
    }
}
