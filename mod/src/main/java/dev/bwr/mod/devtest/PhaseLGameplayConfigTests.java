package dev.bwr.mod.devtest;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.fuel.CoreInsert;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.fuel.CoreInsertData;
import dev.bwr.mod.fuel.SpecialtyRodItem;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server TOML values actually reach core, wall rating, and tritium harvest. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseLGameplayConfigTests {
    private PhaseLGameplayConfigTests() {}

    @GameTest(template = "empty", batch = "l1_gameplay_config", timeoutTicks = 300)
    public static void serverGameplayConfigChangesOutcomes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = new BlockPos(65_900, 190, 65_900);
        int cx = wall.getX() >> 4, cz = wall.getZ() >> 4;
        double hazard = GameplayConfig.FAILURE_HAZARD_PER_SECOND.get();
        double speed = GameplayConfig.ACCIDENT_PROGRESSION_MULTIPLIER.get();
        double halfLife = GameplayConfig.CONTAMINATION_HALF_LIFE_SECONDS.get();
        double cobalt = GameplayConfig.COBALT_EXPOSURE_HOURS.get();
        double k = GameplayConfig.WALL_STRENGTH_K.get();
        double cap = GameplayConfig.WALL_STRENGTH_CAP_PSIG.get();
        int yield = GameplayConfig.TRITIUM_SAMPLES_PER_ROD.get();
        try {
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 2);
            helper.assertTrue(GameplayConfig.SPEC.isLoaded(), "server gameplay config did not load");
            GameplayConfig.FAILURE_HAZARD_PER_SECOND.set(0.0);
            GameplayConfig.ACCIDENT_PROGRESSION_MULTIPLIER.set(2.0);
            GameplayConfig.CONTAMINATION_HALF_LIFE_SECONDS.set(60.0);
            GameplayConfig.COBALT_EXPOSURE_HOURS.set(1.0);
            GameplayConfig.WALL_STRENGTH_K.set(10.0);
            GameplayConfig.WALL_STRENGTH_CAP_PSIG.set(20.0);
            GameplayConfig.TRITIUM_SAMPLES_PER_ROD.set(2);
            var core = new ReactorCore(new CoreConfig());
            GameplayConfig.applyTo(core);
            helper.assertTrue(core.getBoundaryStress().getFailureHazardPerSecond() == 0
                    && core.getSevereAccident().getProgressionMultiplier() == 2
                    && core.getContainment().getContaminationHalfLifeSeconds() == 60,
                    "server config did not reach core gameplay fields");
            var cobaltInsert = new CoreInsert(CoreInsert.Kind.COBALT_TARGET, 1800);
            helper.assertTrue(cobaltInsert.progress() == .5,
                    "configured cobalt irradiation time did not affect core insert");
            level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(Math.abs(ContainmentStrength.contribution(level, wall,
                    level.getBlockState(wall)) - 20.0) < 1e-9,
                    "configured per-block wall strength cap was ignored");

            var player = FakePlayerFactory.getMinecraft(level);
            int before = countSamples(player);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    SpecialtyRodItem.stack(new CoreInsertData(CoreInsert.Kind.TRITIUM_TARGET,
                            CoreInsert.Kind.TRITIUM_TARGET.exposureSeconds)));
            BwrItems.SPECIALTY_ROD.get().use(level, player, net.minecraft.world.InteractionHand.MAIN_HAND);
            helper.assertTrue(countSamples(player) - before == 2,
                    "configured tritium harvest yield was ignored");
            helper.succeed();
        } finally {
            GameplayConfig.FAILURE_HAZARD_PER_SECOND.set(hazard);
            GameplayConfig.ACCIDENT_PROGRESSION_MULTIPLIER.set(speed);
            GameplayConfig.CONTAMINATION_HALF_LIFE_SECONDS.set(halfLife);
            GameplayConfig.COBALT_EXPOSURE_HOURS.set(cobalt);
            GameplayConfig.WALL_STRENGTH_K.set(k);
            GameplayConfig.WALL_STRENGTH_CAP_PSIG.set(cap);
            GameplayConfig.TRITIUM_SAMPLES_PER_ROD.set(yield);
            GameplayConfig.refreshIrradiationTimes();
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 2);
            level.setChunkForced(cx, cz, false);
        }
    }

    private static int countSamples(net.minecraft.server.level.ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(BwrItems.TRITIUM_SAMPLE.get())) total += stack.getCount();
        }
        return total;
    }
}
