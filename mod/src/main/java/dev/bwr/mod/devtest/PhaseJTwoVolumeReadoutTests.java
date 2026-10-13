package dev.bwr.mod.devtest;

import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.peripheral.ReactorPeripheral;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Distinct drywell/wetwell readings reach signals and, when present, CC. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseJTwoVolumeReadoutTests {
    private PhaseJTwoVolumeReadoutTests() {}

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void separatedPressureReachesReadouts(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(61_900, 190, 61_900);
        BlockPos lo = base.offset(-6, -5, -6), hi = base.offset(17, 15, 17);
        int cx0 = lo.getX() >> 4, cx1 = hi.getX() >> 4;
        int cz0 = lo.getZ() >> 4, cz1 = hi.getZ() >> 4;
        try {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
                level.setChunkForced(cx, cz, true);
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
            helper.assertTrue(reactor.isFormed(), "two-volume fixture reactor did not form");
            BlockPos roomPos = base.offset(-4, 0, -4);
            level.setBlock(roomPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var room = (ContainmentControllerBlockEntity) level.getBlockEntity(roomPos);
            room.markDirty();
            for (int i = 0; i < 64 && (room.isScanning() || !room.isSealed() || !room.strengthReady()); i++)
                ContainmentControllerBlockEntity.serverTick(level, roomPos, room.getBlockState(), room);
            helper.assertTrue(room.isSealed() && room.linkedReactors().contains(reactor.getBlockPos()),
                    "two-volume room did not link reactor");
            ContainmentAtmosphere gas = reactor.core().getContainment();
            gas.setFailurePressurePsig(1000);
            for (int i = 0; i < 100; i++) {
                gas.setSteamInputs(new ContainmentAtmosphere.SteamInputs(400, 2800, 0, 0, 0, 0));
                gas.step(0, 0, 0, .05);
            }
            double dry = gas.getDrywellPressurePsig(), wet = gas.getWetwellPressurePsig();
            helper.assertTrue(gas.getPressureNodeCount() == 2 && dry > wet + 0.1,
                    "break pressure did not separate: dry=" + dry + " wet=" + wet);
            var reactorSignals = SignalProviders.of(reactor);
            var roomSignals = SignalProviders.of(room);
            helper.assertTrue(reactorSignals != null && roomSignals != null,
                    "two-volume signal adapters unavailable");
            helper.assertTrue(reactorSignals.read("containment_drywell_pressure").orElse(Double.NaN) == dry
                    && reactorSignals.read("containment_wetwell_pressure").orElse(Double.NaN) == wet
                    && roomSignals.read("drywell_pressure").orElse(Double.NaN) == dry
                    && roomSignals.read("wetwell_pressure").orElse(Double.NaN) == wet
                    && roomSignals.read("pressure_nodes").orElse(Double.NaN) == 2,
                    "signal adapters did not expose separate pressure nodes");
            if (BwrMod.isComputerCraftPresent()) {
                Map<String, Object> cc = new ReactorPeripheral(reactor).getContainment();
                helper.assertTrue(((Number)cc.get("drywellPressurePsig")).doubleValue() == dry
                        && ((Number)cc.get("wetwellPressurePsig")).doubleValue() == wet
                        && ((Number)cc.get("pressureNodeCount")).intValue() == 2
                        && "drywell_wetwell".equals(cc.get("pressureModel")),
                        "CC getContainment did not expose separate pressure nodes");
            }
            helper.succeed();
        } finally {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++)
                level.setChunkForced(cx, cz, false);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void sprayHeaderPlacementSelectsWetwellAirspace(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(63_900, 190, 63_900);
        int cx0 = (origin.getX() - 16) >> 4, cx1 = (origin.getX() + 32) >> 4;
        int cz0 = (origin.getZ() - 16) >> 4, cz1 = (origin.getZ() + 20) >> 4;
        try {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
            SuppressionPoolBlockEntity pool = SuppressionTankRegressionTests.build(level, origin);
            // The persistent GameTest world can restore the controller's old
            // pool inventory even after the shell is rebuilt. Clear it as part
            // of the fixture, then create a deliberately shallow water level.
            pool.pool().drainWaterKg(pool.pool().getMassKg());
            helper.assertTrue(pool.pool().getMassKg() == 0,
                    "wetwell fixture retained water from a previous run: " + pool.pool().getMassKg());
            // The spray header must be in air above the pool surface; a nearly
            // full pool can submerge the fixed test coordinate on some tanks.
            pool.pool().addWaterKg(1_000, 32);
            helper.assertTrue(pool.isWetwellSprayHeader(origin.offset(4, 3, 3)),
                    "header over enclosed pool water was not assigned to wetwell airspace; surface="
                            + pool.surfaceY() + " mass=" + pool.pool().getMassKg());
            helper.assertTrue(!pool.isWetwellSprayHeader(origin.offset(11, 3, 3)),
                    "drywell header outside the pool footprint was assigned to wetwell");
            helper.succeed();
        } finally {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++)
                level.setChunkForced(cx, cz, false);
        }
    }
}
