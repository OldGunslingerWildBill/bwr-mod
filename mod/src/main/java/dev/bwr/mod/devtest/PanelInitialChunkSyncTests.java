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
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A late-arriving player must see components even if the chunk-watch custom packet races the chunk itself. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelInitialChunkSyncTests {
    private PanelInitialChunkSyncTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void initialChunkCarriesComponentsAndLiveValues(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = new BlockPos(54_900, 110, 54_900);
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        FixtureChunks.force(level, cx, cz, true);
        try {
            level.getChunk(cx, cz);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            PanelBlockEntity panel = (PanelBlockEntity) level.getBlockEntity(pos);
            var gauge = PanelOps.placeDirect(level, pos, 1, 1, ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4));
            helper.assertTrue(gauge != null, "fixture gauge did not fit");
            panel.setInput(gauge, 0, 0.75);
            var tag = panel.getUpdateTag(level.registryAccess());
            helper.assertTrue(tag.contains("bwr_panel", Tag.TAG_COMPOUND), "initial chunk tag has no panel state");
            var components = tag.getCompound("bwr_panel").getList("Components", Tag.TAG_COMPOUND);
            helper.assertTrue(components.size() == 1, "initial chunk did not include the gauge");
            helper.assertTrue(components.getCompound(0).getList("InValues", Tag.TAG_DOUBLE).getDouble(0) == 0.75,
                    "initial chunk did not include the live needle value");
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            FixtureChunks.force(level, cx, cz, false);
        }
        helper.succeed();
    }
}
