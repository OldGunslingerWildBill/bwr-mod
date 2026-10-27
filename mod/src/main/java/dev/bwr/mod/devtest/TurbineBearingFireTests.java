package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineFire;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.power.TurbineFires;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A turbine fire from the lube oil system (TURBINE-MAINTENANCE.md, TURBINE-AUX.md): a bearing run with no oil film on a turning shaft
 * heats past the oil's ignition point and lights what is left in its housing, if the train's tanks hold oil; a running oil pump feeds
 * the fire from the tank; water puts it out; a train with no oil has nothing to burn. The generator fixture of {@link TurbineAuxTests}.
 */
@GameTestHolder("bwraux")
@PrefixGameTestTemplate(false)
public final class TurbineBearingFireTests {
    private TurbineBearingFireTests() {
    }

    @FunctionalInterface
    private interface Body {
        void run(TurbineAuxTests.Fx f) throws Exception;
    }

    private static void run(GameTestHelper h, BlockPos root, Body body) {
        var l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, TurbineAuxTests.Fx.bounds(root), () -> {
            long time = l.getGameTime();
            TurbineAuxTests.Fx f = null;
            try {
                f = new TurbineAuxTests.Fx(l, root);
                body.run(f);
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    /** Floor under the places a fire on the generator burns, so the fire has something to stand on. */
    private static void floor(TurbineAuxTests.Fx f) {
        for (BlockPos p : TurbineFires.fireCells(f.gen)) f.l.setBlock(p.below(), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 400)
    public static void aBearingRunDryLightsItsOilAndARunningPumpFeedsTheFire(GameTestHelper h) {
        run(h, new BlockPos(80400, 245, 78000), f -> {
            floor(f);
            TurbineAuxBlockEntity tank = f.tank(10_000);
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            int s = 0;
            while (!f.gen.burning() && s < 20 * 120) {
                f.tick(f.gen, 1);
                s++;
            }
            h.assertTrue(f.gen.burning() && f.gen.fire() == TurbineFire.Kind.OIL, "a bearing run dry lit its oil: " + f.gen.bearingC + " C after " + s / 20 + " s");
            h.assertTrue(s / 20.0 > 20 && s / 20.0 < 90 && f.gen.bearingC >= TurbineFire.BEARING_IGNITION_C, "in about a minute, past the ignition point: " + s / 20 + " s");
            h.assertTrue(Math.abs(tank.oilLitres() - (10_000 - TurbineFire.BEARING_SPILL_L)) < 1, "the oil in its housing and drains caught: " + tank.oilLitres());
            h.assertTrue(SignalProviders.of(f.gen).read("fire").getAsDouble() == 1, "the panel reads the fire");
            f.tick(f.gen, 40);
            h.assertTrue(TurbineFires.fireCells(f.gen).stream().anyMatch(p -> f.l.getBlockState(p).is(Blocks.FIRE)), "fire burns beside the bearings");
            // the AC pump back on: oil to the bearings, and out of the wiped bearing into the fire
            tank.setAcPump(true);
            double before = tank.oilLitres();
            f.tick(f.gen, 200, () -> tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            double fed = before - tank.oilLitres();
            h.assertTrue(f.gen.burning() && fed > 30, "the running pump feeds the fire from the tank: " + fed + " L in 10 s");
            tank.setAcPump(false);
            f.l.setBlock(TurbineFires.fireCells(f.gen).get(0), Blocks.WATER.defaultBlockState(), 3);
            f.tick(f.gen, 20);
            h.assertTrue(!f.gen.burning(), "water on it put it out");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 400)
    public static void aTrainWithNoOilHasNothingToBurn(GameTestHelper h) {
        run(h, new BlockPos(80500, 245, 78000), f -> {
            floor(f);
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 20 * 120);
            h.assertTrue(f.gen.bearingC > TurbineFire.BEARING_IGNITION_C && !f.gen.burning(),
                    "a bearing far past the ignition point with no oil in the train lights nothing: " + f.gen.bearingC + " C");
        });
    }
}
