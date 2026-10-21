package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regressions found by the dedicated-server and old-save checks (ServerBootCheck, SaveCompatCheck). */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ServerCompatTests {
    private ServerCompatTests() {
    }

    /**
     * An assembled condensate tank controller whose block entity holds no valid dimensions (a command-placed block, or a
     * save whose block entity data is gone) used to throw "Invalid tank dimensions" out of its scheduled tick, which
     * crashes the whole server. It must instead be cleaned up like any other tank that is not complete.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void tankControllerWithoutDimensionsDoesNotCrashTheTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockState state = BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState()
                .setValue(CondensateStorageTankBlock.ASSEMBLED, true)
                .setValue(CondensateStorageTankBlock.CONTROLLER, true);
        level.setBlock(pos, state, 3);
        try {
            level.getBlockState(pos).tick(level, pos, level.random);
        } catch (RuntimeException e) {
            helper.fail("the tick of a tank controller without dimensions threw " + e);
            return;
        }
        level.removeBlock(pos, false);
        helper.succeed();
    }
}
