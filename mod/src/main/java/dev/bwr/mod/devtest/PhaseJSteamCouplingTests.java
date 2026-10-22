package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Live boundary-flow routing to the containment atmosphere. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseJSteamCouplingTests {
    private PhaseJSteamCouplingTests() {}

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void brokenSteamLinePressurisesSealedRoomWithoutPool(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(55900, 190, 55900);
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
            helper.assertTrue(reactor.isFormed(), "steam coupling reactor did not form");
            reactor.core().initialiseHotShutdown();
            BlockPos roomPos = base.offset(-4, 0, -4);
            level.setBlock(roomPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var room = (ContainmentControllerBlockEntity) level.getBlockEntity(roomPos);
            room.markDirty();
            for (int i = 0; i < 64 && (room.isScanning() || !room.isSealed() || !room.strengthReady()); i++)
                ContainmentControllerBlockEntity.serverTick(level, roomPos, room.getBlockState(), room);
            helper.assertTrue(room.isSealed() && room.strengthReady(), "steam coupling room not sealed: " + room.statusLines());
            reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.MAIN_STEAM_LINE, 1025);
            double before = reactor.core().getContainment().getPressurePsig();
            double peakBreak = 0, peakSteam = 0, peakPressure = before;
            for (int i = 0; i < 400; i++) {
                ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
                peakBreak = Math.max(peakBreak, reactor.core().getBreakSteamFlowKgPerS());
                peakSteam = Math.max(peakSteam, reactor.core().getContainment().getSteamKg());
                peakPressure = Math.max(peakPressure, reactor.core().getContainment().getPressurePsig());
            }
            helper.assertTrue(peakBreak > 0,
                    "broken steam line did not discharge");
            var gas = reactor.core().getContainment();
            helper.assertTrue(gas.getLeakArea() == 0 && peakSteam > 0 && peakPressure > before,
                    "sealed drywell did not retain break steam: " + gas);
            helper.succeed();
        } finally {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++)
                FixtureChunks.force(level, cx, cz, false);
        }
    }
}
