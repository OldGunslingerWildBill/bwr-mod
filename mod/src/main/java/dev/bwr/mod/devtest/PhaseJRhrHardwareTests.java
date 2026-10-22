package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentSprayHeaderBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The passive header is discovered by the room and meters its actual inventory. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseJRhrHardwareTests {
    private PhaseJRhrHardwareTests() {}

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void sprayHeaderFeedsLinkedReactorWithoutBlockTicker(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(57900, 190, 57900);
        BlockPos lo = base.offset(-6, -5, -6), hi = base.offset(17, 15, 17);
        int cx0 = lo.getX() >> 4, cx1 = hi.getX() >> 4;
        int cz0 = lo.getZ() >> 4, cz1 = hi.getZ() >> 4;
        try {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
                FixtureChunks.force(level, cx, cz, true);
                level.getChunk(cx, cz);
            }
            for (int x = lo.getX(); x <= hi.getX(); x++)
                for (int z = lo.getZ(); z <= hi.getZ(); z++)
                    for (int y = lo.getY(); y <= hi.getY(); y++) {
                        boolean shell = x == lo.getX() || x == hi.getX() || z == lo.getZ() || z == hi.getZ()
                                || y == lo.getY() || y == hi.getY();
                        level.setBlock(new BlockPos(x, y, z),
                                (shell ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 2);
                    }
            ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(level, base, 11, 11, 8, false);
            helper.assertTrue(reactor.isFormed(), "spray fixture reactor did not form");
            reactor.core().initialiseHotShutdown();
            BlockPos sprayPos = base.offset(-2, 0, -2);
            level.setBlock(sprayPos, BwrBlocks.CONTAINMENT_SPRAY_HEADER.get().defaultBlockState(), 3);
            var header = (ContainmentSprayHeaderBlockEntity) level.getBlockEntity(sprayPos);
            helper.assertTrue(header.receiveWaterKg(200, 20) == 200, "header did not accept water");
            BlockPos roomPos = base.offset(-4, 0, -4);
            level.setBlock(roomPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var room = (ContainmentControllerBlockEntity) level.getBlockEntity(roomPos);
            room.markDirty();
            for (int i = 0; i < 64 && (room.isScanning() || !room.isSealed() || !room.strengthReady()); i++)
                ContainmentControllerBlockEntity.serverTick(level, roomPos, room.getBlockState(), room);
            helper.assertTrue(room.isSealed() && room.linkedSprayHeaders().contains(sprayPos),
                    "room did not discover internal spray header: " + room.statusLines());
            ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
            helper.assertTrue(reactor.core().getContainment().getLastSprayFlowKgPerS() > 0,
                    "containment did not receive measured spray flow");
            helper.assertTrue(header.getWaterKg() < 200, "spray did not consume header inventory");
            helper.succeed();
        } finally {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++)
                FixtureChunks.force(level, cx, cz, false);
        }
    }
}
