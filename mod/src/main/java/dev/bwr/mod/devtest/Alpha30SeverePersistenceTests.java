package dev.bwr.mod.devtest;

import dev.bwr.mod.damage.SevereAccidentNbt;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Save compatibility for damage and gas during both a formed and a broken vessel. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha30SeverePersistenceTests {
    private Alpha30SeverePersistenceTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void damagedCoreAndHydrogenSurviveReload(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var vesselMin = new BlockPos(12000, 190, 12000);
        var controller = CompactCoreRegressionTests.build(level, vesselMin, 15, 15, false);
        helper.assertTrue(controller.isFormed(), "reference reactor did not form");

        double[] damage = controller.core().getSevereAccident().toArray();
        damage[1] = 0.35; // Permanently failed cladding.
        damage[10] = 0.0175; // Gap inventory already released.
        controller.core().getSevereAccident().fromArray(damage);

        double[] atmosphere = controller.core().getContainment().toArray();
        atmosphere[4] = 12345.0; // Preserved hydrogen moles, below ignition.
        controller.core().getContainment().fromArray(atmosphere);

        var pos = controller.getBlockPos();
        var block = level.getBlockState(pos);
        var snapshot = controller.saveWithFullMetadata(level.registryAccess());
        helper.assertTrue(snapshot.contains(SevereAccidentNbt.KEY), "SevereAccident key missing");
        helper.assertTrue(snapshot.getCompound(SevereAccidentNbt.KEY).contains("core")
                && snapshot.getCompound(SevereAccidentNbt.KEY).contains("containment"),
                "damage or gas missing from SevereAccident key");

        level.removeBlockEntity(pos);
        controller = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, block, snapshot, level.registryAccess());
        level.setBlockEntity(controller);
        revalidate(controller);
        helper.assertTrue(controller.isFormed(), "reactor did not reform after reload");
        verifyState(helper, controller);

        // The reactor may be saved again while a casing is missing. Its pending
        // SevereAccident tag must survive that extra save and subsequent repair.
        var floor = controller.structure().interiorMin().below();
        level.removeBlock(floor, false);
        revalidate(controller);
        var brokenSnapshot = controller.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        controller = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, block, brokenSnapshot, level.registryAccess());
        level.setBlockEntity(controller);
        revalidate(controller);
        helper.assertTrue(!controller.isFormed(), "open vessel unexpectedly formed");
        var pendingSnapshot = controller.saveWithFullMetadata(level.registryAccess());
        helper.assertTrue(pendingSnapshot.contains(SevereAccidentNbt.KEY),
                "unformed reactor discarded pending SevereAccident key");
        level.removeBlockEntity(pos);
        controller = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, block, pendingSnapshot, level.registryAccess());
        level.setBlockEntity(controller);
        level.setBlock(floor, BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(), 2);
        revalidate(controller);
        helper.assertTrue(controller.isFormed(), "repaired vessel did not reform");
        verifyState(helper, controller);
        helper.succeed();
    }

    private static void verifyState(GameTestHelper helper, ReactorControllerBlockEntity controller) {
        helper.assertTrue(Math.abs(controller.core().getSevereAccident().getFailedCladFraction() - 0.35) < 1.0e-9,
                "cladding damage reset on reload");
        helper.assertTrue(Math.abs(controller.core().getContainment().toArray()[4] - 12345.0) < 1.0e-6,
                "containment hydrogen reset on reload");
    }

    private static void revalidate(ReactorControllerBlockEntity controller) throws Exception {
        var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
        method.setAccessible(true);
        method.invoke(controller, controller.getLevel());
    }
}
