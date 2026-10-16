package dev.bwr.mod.devtest;

import dev.bwr.core.accident.SevereAccident;
import dev.bwr.mod.accident.CoriumBlock;
import dev.bwr.mod.accident.CoriumConfig;
import dev.bwr.mod.accident.CoriumPool;
import dev.bwr.mod.damage.SevereAccidentNbt;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ex-vessel corium in the world. Each test builds the 15x15 reference vessel
 * over an air gap and a concrete floor, forces the severe accident state
 * through its snapshot, and drives the reactor's own corium pool directly so
 * nothing steps the physics underneath it.
 *
 * <p>Geometry for the reference vessel at {@code min}: interior 15 wide, so
 * the footprint radius is 7.0 and the centre column is {@code min + 7}. The
 * drives hang at {@code y - 2}, the gap is {@code y - 3} and {@code y - 4},
 * and the floor's top is {@code y - 5}.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class CoriumMeltTests {
    private CoriumMeltTests() {
    }

    private static final int FLOOR_DROP = 5;

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void poolFormsUnderVesselOnlyAfterHeadFailure(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(20000, 190, 20000));
        try {
            rig.run();
            helper.assertTrue(!rig.pool().isAnchored(), "pool anchored before the head failed");
            helper.assertTrue(!rig.isCorium(rig.at(0, 1, 0)), "corium placed before the head failed");

            rig.accident(0.0, false);
            rig.run();
            helper.assertTrue(rig.pool().isAnchored() && rig.pool().floorY() == rig.floorY,
                    "floor found at " + rig.pool().floorY() + ", expected " + rig.floorY);
            helper.assertTrue(rig.pool().radius() == 7.0, "footprint radius " + rig.pool().radius());
            helper.assertTrue(rig.isHot(rig.at(0, 1, 0)), "no molten pool under the vessel centre");
            helper.assertTrue(rig.isHot(rig.at(7, 1, 0)) && rig.isHot(rig.at(-7, 1, 0))
                    && rig.isHot(rig.at(0, 1, 7)), "pool does not reach the vessel's inner radius");
            helper.assertTrue(!rig.isCorium(rig.at(8, 1, 0)) && !rig.isCorium(rig.at(6, 1, 6)),
                    "pool spread outside the footprint");
            helper.assertTrue(rig.level.getBlockState(rig.at(0, 0, 0)).is(Blocks.GRAY_CONCRETE),
                    "floor melted with no ablation");
            helper.assertTrue(rig.level.getBlockState(rig.at(0, 1, 0)).getLightEmission() == CoriumBlock.HOT_LIGHT,
                    "molten corium is not lit");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void meltDepthTracksAblation(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(20200, 190, 20000));
        try {
            rig.accident(0.5, false);
            rig.run();
            helper.assertTrue(rig.isCorium(rig.at(0, 0, 0)), "0.5 m of ablation did not melt the floor surface");
            helper.assertTrue(!rig.isCorium(rig.at(0, -1, 0)), "0.5 m of ablation melted two layers");

            rig.accident(2.0, false);
            rig.run();
            helper.assertTrue(rig.isCorium(rig.at(0, -1, 0)), "2 m of ablation did not reach the second layer");
            helper.assertTrue(!rig.isCorium(rig.at(0, -2, 0)), "2 m of ablation melted three layers");

            rig.accident(SevereAccident.BASEMAT_THICKNESS_M, false);
            rig.run();
            helper.assertTrue(rig.isCorium(rig.at(0, -2, 0)), "full basemat depth did not reach the third layer");
            helper.assertTrue(!rig.isCorium(rig.at(0, -3, 0)), "melted below the basemat before melt-through");
            // The cavity tapers: 7.0 at the surface, 5.5 three layers down.
            helper.assertTrue(rig.isCorium(rig.at(6, 0, 0)) && !rig.isCorium(rig.at(6, -2, 0))
                    && rig.isCorium(rig.at(5, -2, 0)), "ablation front does not taper with depth");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void meltThroughGoesOneDeeperInTheCentre(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(20400, 190, 20000));
        try {
            rig.accident(SevereAccident.BASEMAT_THICKNESS_M, true);
            rig.run();
            helper.assertTrue(rig.isCorium(rig.at(0, -3, 0)), "no melt-through hole under the centre");
            helper.assertTrue(!rig.isCorium(rig.at(1, -3, 0)) && !rig.isCorium(rig.at(0, -3, 1)),
                    "melt-through hole wider than the centre column");
            helper.assertTrue(!rig.isCorium(rig.at(0, -4, 0)), "melt-through went two blocks deeper");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void unbreakableBlockEntityAndPlantBlocksSurvive(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(20600, 190, 20000));
        try {
            var bedrock = rig.at(1, 0, 0);
            var chest = rig.at(2, -1, 0);
            var plant = rig.at(0, 0, 1);
            var poolChest = rig.at(-1, 1, 0);
            rig.level.setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), 2);
            rig.level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
            rig.level.setBlock(plant, BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState(), 2);
            rig.level.setBlock(poolChest, Blocks.CHEST.defaultBlockState(), 2);
            rig.accident(2.0, false);
            rig.run();
            helper.assertTrue(rig.level.getBlockState(bedrock).is(Blocks.BEDROCK), "bedrock replaced");
            helper.assertTrue(rig.level.getBlockState(chest).is(Blocks.CHEST)
                    && rig.level.getBlockEntity(chest) != null, "block entity in the floor replaced");
            helper.assertTrue(rig.level.getBlockState(poolChest).is(Blocks.CHEST), "block entity in the pool replaced");
            helper.assertTrue(rig.level.getBlockState(plant).is(BwrBlocks.SUPPRESSION_POOL_WALL.get()),
                    "BWR plant block replaced");
            for (var drive : rig.controller.structure().crdPositions())
                helper.assertTrue(rig.level.getBlockState(drive).is(BwrBlocks.CONTROL_ROD_DRIVE.get()), "drive replaced");
            helper.assertTrue(rig.isCorium(rig.at(3, 0, 0)) && rig.isCorium(rig.at(2, 0, 0))
                    && rig.isCorium(rig.at(3, -1, 0)), "neighbours of protected blocks were not melted");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void poolFreezesWhenAttackStopsAndStaysFrozen(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(20800, 190, 20000));
        try {
            rig.accident(1.0, false);
            rig.run();
            helper.assertTrue(rig.isHot(rig.at(0, 0, 0)) && rig.isHot(rig.at(0, 1, 0)), "fresh corium not molten");
            // No physics step, so no concrete attack: the quench clock runs.
            for (int i = 0; i < CoriumPool.COOLING_DELAY_TICKS - 20; i++) rig.tick();
            helper.assertTrue(!rig.pool().isSolidified() && rig.isHot(rig.at(0, 1, 0)), "froze before the delay");
            rig.run();
            for (int i = 0; i < 40; i++) rig.tick();
            helper.assertTrue(rig.pool().isSolidified(), "pool never froze");
            int hot = rig.count(true), cold = rig.count(false);
            helper.assertTrue(hot == 0 && cold > 0, "after freezing: " + hot + " hot, " + cold + " cold");
            // Deeper melting afterwards adds slag, never molten corium.
            rig.accident(2.0, false);
            rig.run();
            helper.assertTrue(rig.isCorium(rig.at(0, -1, 0)) && !rig.isHot(rig.at(0, -1, 0)),
                    "corium placed after freezing is molten");
            helper.assertTrue(rig.count(true) == 0 && rig.count(false) > cold, "frozen pool turned hot again");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void reloadNeitherRepeatsNorLosesPlacement(GameTestHelper helper) throws Exception {
        var rig = Rig.build(helper, new BlockPos(21000, 190, 20000));
        try {
            rig.accident(2.0, false);
            for (int i = 0; i < 4; i++) rig.tick(40);
            int placedBefore = rig.pool().placedCount();
            helper.assertTrue(placedBefore > 0 && placedBefore < rig.expectedPositions(3),
                    "test needs a partly placed pool, got " + placedBefore);
            // A marker where corium already went: a repeated placement would overwrite it.
            var marker = rig.at(0, 1, 0);
            helper.assertTrue(rig.isCorium(marker), "centre was not placed first");
            rig.level.setBlock(marker, Blocks.STONE.defaultBlockState(), 2);

            var snapshot = rig.controller.saveWithFullMetadata(rig.level.registryAccess());
            helper.assertTrue(snapshot.contains(SevereAccidentNbt.CORIUM_KEY), "corium progress not saved");
            rig.reload(snapshot);
            helper.assertTrue(rig.controller.isFormed(), "reactor did not reform after reload");
            helper.assertTrue(rig.pool().isAnchored() && rig.pool().placedCount() == placedBefore
                    && rig.pool().floorY() == rig.floorY, "corium progress changed across reload");
            rig.run();
            helper.assertTrue(rig.level.getBlockState(marker).is(Blocks.STONE), "reload placed a block twice");
            int expected = rig.expectedPositions(3) - 1;
            helper.assertTrue(rig.count(true) == expected,
                    "reload lost placement: " + rig.count(true) + " of " + expected);

            // An old save has no key: nothing placed yet, and it still loads.
            var old = snapshot.copy();
            old.remove(SevereAccidentNbt.CORIUM_KEY);
            rig.reload(old);
            helper.assertTrue(!rig.pool().isAnchored() && rig.pool().placedCount() == 0,
                    "missing key did not read as nothing placed");
            helper.assertTrue(rig.controller.isFormed()
                    && rig.controller.core().getSevereAccident().isLowerHeadFailed(),
                    "old save lost the accident itself");
            helper.succeed();
        } finally {
            rig.close();
        }
    }

    /** Toggles a global, so it runs alone in its own batch. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "corium_config")
    public static void configOffAltersNothing(GameTestHelper helper) throws Exception {
        helper.assertTrue(CoriumConfig.SPEC.isLoaded(), "server config not loaded");
        var rig = Rig.build(helper, new BlockPos(21200, 190, 20000));
        try {
            rig.accident(SevereAccident.BASEMAT_THICKNESS_M, true);
            CoriumConfig.ALTER_WORLD.set(false);
            for (int i = 0; i < 20; i++) rig.controller.severeEffects().afterStep(rig.controller);
            helper.assertTrue(!rig.pool().isAnchored() && rig.count(true) == 0 && rig.count(false) == 0,
                    "world altered with coriumAltersWorld off");
            CoriumConfig.ALTER_WORLD.set(true);
            for (int i = 0; i < 40; i++) rig.controller.severeEffects().afterStep(rig.controller);
            helper.assertTrue(rig.isCorium(rig.at(0, 1, 0)), "turning the config back on did not resume placement");
            helper.succeed();
        } finally {
            CoriumConfig.ALTER_WORLD.set(true);
            rig.close();
        }
    }

    /** One vessel over a floor, and handles on its corium pool. */
    private static final class Rig {
        final ServerLevel level;
        final BlockPos min;
        final int floorY;
        final int centreX;
        final int centreZ;
        ReactorControllerBlockEntity controller;

        private Rig(ServerLevel level, BlockPos min, ReactorControllerBlockEntity controller) {
            this.level = level;
            this.min = min;
            this.controller = controller;
            this.floorY = min.getY() - FLOOR_DROP;
            this.centreX = min.getX() + 7;
            this.centreZ = min.getZ() + 7;
        }

        static Rig build(GameTestHelper helper, BlockPos min) throws Exception {
            ServerLevel level = helper.getLevel();
            var controller = CompactCoreRegressionTests.build(level, min, 15, 15, false);
            helper.assertTrue(controller.isFormed(), "reference reactor did not form");
            for (int x = -4; x <= 18; x++) {
                for (int z = -4; z <= 18; z++) {
                    for (int y = -3; y >= -16; y--) {
                        var p = min.offset(x, y, z);
                        level.setBlock(p, (y > -FLOOR_DROP ? Blocks.AIR : Blocks.GRAY_CONCRETE).defaultBlockState(), 2);
                    }
                }
            }
            return new Rig(level, min, controller);
        }

        /** Offset from the floor block in the centre column. */
        BlockPos at(int dx, int dy, int dz) {
            return new BlockPos(centreX + dx, floorY + dy, centreZ + dz);
        }

        CoriumPool pool() {
            return controller.severeEffects().corium();
        }

        void accident(double ablationM, boolean basematFailed) {
            var accident = controller.core().getSevereAccident();
            double[] a = accident.toArray();
            a[4] = 0.6;                    // ex-vessel fraction
            a[7] = 1;                      // lower head failed
            a[8] = ablationM;
            a[9] = basematFailed ? 1 : 0;
            a[13] = SevereAccident.Stage.CORE_CONCRETE_INTERACTION.ordinal();
            accident.fromArray(a);
        }

        void tick() {
            tick(100_000);
        }

        void tick(int budget) {
            var s = controller.structure();
            pool().update(level, s.interiorMin(), s.interiorMax(),
                    controller.core().getSevereAccident(), true, budget);
        }

        /** Until idle; a handful of calls, far short of the quench delay. */
        void run() {
            var s = controller.structure();
            for (int i = 0; i < 8; i++) {
                if (!pool().update(level, s.interiorMin(), s.interiorMax(),
                        controller.core().getSevereAccident(), true, 100_000)) {
                    return;
                }
            }
        }

        boolean isCorium(BlockPos p) {
            return level.getBlockState(p).is(BwrBlocks.CORIUM.get());
        }

        boolean isHot(BlockPos p) {
            var s = level.getBlockState(p);
            return s.is(BwrBlocks.CORIUM.get()) && s.getValue(CoriumBlock.HOT);
        }

        /** Corium in the rig's floor region, hot or cold. */
        int count(boolean hot) {
            int n = 0;
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    for (int y = 2; y >= -6; y--) {
                        var s = level.getBlockState(at(x, y, z));
                        if (s.is(BwrBlocks.CORIUM.get()) && s.getValue(CoriumBlock.HOT) == hot) n++;
                    }
                }
            }
            return n;
        }

        /** Pool plus the first {@code floorLayers} floor layers. */
        int expectedPositions(int layers) {
            int n = 0;
            for (int i = 0; i < layers; i++) n += pool().layer(i).size();
            return n;
        }

        void reload(CompoundTag snapshot) throws Exception {
            var pos = controller.getBlockPos();
            var state = level.getBlockState(pos);
            level.removeBlockEntity(pos);
            controller = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, state, snapshot, level.registryAccess());
            level.setBlockEntity(controller);
            var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
            method.setAccessible(true);
            method.invoke(controller, level);
        }

        /** The reactor would otherwise keep melting and dosing after the test. */
        void close() {
            FixtureCleanup.drainAt(level, controller.getBlockPos());
            level.removeBlock(controller.getBlockPos(), false);
        }
    }
}
