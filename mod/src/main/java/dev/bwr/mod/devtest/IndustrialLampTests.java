package dev.bwr.mod.devtest;

import dev.bwr.mod.lighting.HighBayLampBlock;
import dev.bwr.mod.lighting.IndustrialLampBlock;
import dev.bwr.mod.lighting.WallPackLampBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * The industrial lamps (LIGHTING.md): registration, the warm-up ramp on scheduled ticks, redstone on and off, the
 * restrike cooling of the hot lamps, the saved state, the mounts and the shapes of the wall pack, and the loot.
 * Batch "industriallamps" in namespace bwrlamps (-PbwrGameTestNamespaces=bwrlamps). Each test builds in its own template
 * area; the times are game ticks from the start of the test.
 */
@GameTestHolder("bwrlamps")
@PrefixGameTestTemplate(false)
public final class IndustrialLampTests {
    private IndustrialLampTests() {
    }

    /** Every lamp block by registry name, with the supplier of its block and whether it is a high bay. */
    private record Lamp(String name, Supplier<? extends Block> block, boolean highBay) {
    }

    private static final List<Lamp> LAMPS = List.of(
            new Lamp("lps_high_bay", BwrBlocks.LPS_HIGH_BAY, true),
            new Lamp("lps_wall_pack", BwrBlocks.LPS_WALL_PACK, false),
            new Lamp("hps_high_bay", BwrBlocks.HPS_HIGH_BAY, true),
            new Lamp("hps_wall_pack", BwrBlocks.HPS_WALL_PACK, false),
            new Lamp("mercury_high_bay", BwrBlocks.MERCURY_HIGH_BAY, true),
            new Lamp("mercury_wall_pack", BwrBlocks.MERCURY_WALL_PACK, false));

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = IndustrialLampTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A high bay's support: the stone above it. A wall pack's support: the stone behind it. */
    private static void support(GameTestHelper h, BlockPos lamp, boolean highBay, Direction facing) {
        h.setBlock(highBay ? lamp.above() : lamp.relative(facing.getOpposite()), Blocks.STONE);
    }

    private static int light(GameTestHelper h, BlockPos pos) {
        return h.getBlockState(pos).getLightEmission();
    }

    // ---- registration and the data

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps")
    public static void lampsAreRegisteredWithItemLootTagAndADarkStartingState(GameTestHelper h) throws IOException {
        String tag = resource("/data/minecraft/tags/block/mineable/pickaxe.json");
        for (Lamp lamp : LAMPS) {
            Block block = lamp.block().get();
            check(h, BuiltInRegistries.BLOCK.getKey(block).equals(ResourceLocation.fromNamespaceAndPath("bwr", lamp.name())),
                    lamp.name() + " is not registered under its name");
            check(h, BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("bwr", lamp.name())),
                    lamp.name() + " has no item");
            BlockState def = block.defaultBlockState();
            check(h, !def.getValue(IndustrialLampBlock.LIT) && def.getValue(IndustrialLampBlock.WARMUP) == 0
                    && def.getValue(IndustrialLampBlock.COOLING) == 0, lamp.name() + " does not start dark and cold");
            check(h, def.getLightEmission() == 0, lamp.name() + " gives light when it is off");
            String loot = resource("/data/bwr/loot_table/blocks/" + lamp.name() + ".json");
            check(h, loot.contains("\"bwr:" + lamp.name() + "\"") && loot.contains("minecraft:survives_explosion"),
                    lamp.name() + " does not drop itself");
            check(h, tag.contains("\"bwr:" + lamp.name() + "\""), lamp.name() + " is not in the pickaxe tag");
        }
        check(h, BwrBlocks.HPS_HIGH_BAY.get() instanceof HighBayLampBlock, "the high bay is not a HighBayLampBlock");
        check(h, BwrBlocks.HPS_WALL_PACK.get() instanceof WallPackLampBlock, "the wall pack is not a WallPackLampBlock");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps")
    public static void theLampsSaveEveryStateAndTheRecipesAreWritten(GameTestHelper h) throws IOException {
        ServerLevel level = h.getLevel();
        int states = 0;
        for (Lamp lamp : LAMPS) {
            Block block = lamp.block().get();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                check(h, restored == state, lamp.name() + " state " + state + " did not survive a save");
                states++;
            }
        }
        check(h, states == 3 * (32 + 128), "unexpected lamp state count " + states);
        for (Lamp lamp : LAMPS) {
            String recipe = resource("/data/bwr/recipe/" + lamp.name() + ".json");
            check(h, recipe.contains("\"id\": \"bwr:" + lamp.name() + "\""), lamp.name() + " has no recipe for itself");
        }
        h.succeed();
    }

    // ---- redstone, warm-up and restrike

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps", timeoutTicks = 600)
    public static void hpsRedstoneStartsAFourEightTwelveFifteenWarmUpOnScheduledSteps(GameTestHelper h) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        BlockPos power = lamp.east();
        support(h, lamp, true, Direction.NORTH);
        h.setBlock(lamp, BwrBlocks.HPS_HIGH_BAY.get());
        check(h, light(h, lamp) == 0, "an unpowered lamp gives light");
        h.setBlock(power, Blocks.REDSTONE_BLOCK);
        // A high-pressure lamp takes 400 ticks to warm: three steps of 133 ticks after the strike at tick 0.
        h.runAfterDelay(2, () -> {
            check(h, h.getBlockState(lamp).getValue(IndustrialLampBlock.LIT), "the lamp did not light on redstone");
            check(h, light(h, lamp) == 4, "the strike gives light " + light(h, lamp) + ", not 4");
        });
        h.runAfterDelay(140, () -> check(h, light(h, lamp) == 8, "after the first step the light is " + light(h, lamp) + ", not 8"));
        h.runAfterDelay(270, () -> check(h, light(h, lamp) == 12, "after the second step the light is " + light(h, lamp) + ", not 12"));
        h.runAfterDelay(410, () -> {
            check(h, light(h, lamp) == 15, "after the third step the light is " + light(h, lamp) + ", not 15");
            check(h, h.getBlockState(lamp).getValue(IndustrialLampBlock.WARMUP) == 3, "the warm-up stage is not full");
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps", timeoutTicks = 800)
    public static void aHotHpsCoolsBeforeItRestrikesAndAPoweredLampWaitsForIt(GameTestHelper h) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        BlockPos power = lamp.east();
        support(h, lamp, true, Direction.NORTH);
        h.setBlock(lamp, BwrBlocks.HPS_HIGH_BAY.get());
        h.setBlock(power, Blocks.REDSTONE_BLOCK);
        // Fully warm at about 400 ticks, switched off at 410 (hot), powered again at 420 while it is still cooling.
        h.runAfterDelay(410, () -> {
            check(h, light(h, lamp) == 15, "the lamp is not full before the switch-off");
            h.setBlock(power, Blocks.AIR);
        });
        h.runAfterDelay(412, () -> {
            check(h, light(h, lamp) == 0, "the lamp did not go out at once");
            check(h, h.getBlockState(lamp).getValue(IndustrialLampBlock.COOLING) == 3, "the hot lamp is not cooling");
        });
        h.runAfterDelay(420, () -> h.setBlock(power, Blocks.REDSTONE_BLOCK));
        // Cooling is three 100-tick units from the switch-off: it ends at 710. Before that the lamp must stay dark.
        h.runAfterDelay(440, () -> check(h, light(h, lamp) == 0, "the lamp restruck while it was still hot"));
        h.runAfterDelay(690, () -> check(h, light(h, lamp) == 0, "the lamp restruck before its cooling was done"));
        h.runAfterDelay(725, () -> {
            check(h, light(h, lamp) == 4, "the lamp did not restrike after cooling, light " + light(h, lamp));
            check(h, h.getBlockState(lamp).getValue(IndustrialLampBlock.COOLING) == 0, "the cooling count did not clear");
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps", timeoutTicks = 200)
    public static void aLowPressureSodiumLampRestrikesAtOnce(GameTestHelper h) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        BlockPos power = lamp.east();
        support(h, lamp, true, Direction.NORTH);
        h.setBlock(lamp, BwrBlocks.LPS_HIGH_BAY.get());
        h.setBlock(power, Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(60, () -> h.setBlock(power, Blocks.AIR));
        h.runAfterDelay(62, () -> {
            check(h, light(h, lamp) == 0, "the lamp did not go out");
            h.setBlock(power, Blocks.REDSTONE_BLOCK);
        });
        h.runAfterDelay(64, () -> {
            check(h, light(h, lamp) == 4, "the low-pressure lamp did not restrike at once, light " + light(h, lamp));
            check(h, h.getBlockState(lamp).getValue(IndustrialLampBlock.WARMUP) == 0, "the restrike did not start cold");
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps", timeoutTicks = 200)
    public static void aMidWarmUpLampKeepsItsStageAndItsPendingStepInTheSave(GameTestHelper h) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        BlockPos power = lamp.east();
        support(h, lamp, true, Direction.NORTH);
        h.setBlock(lamp, BwrBlocks.MERCURY_HIGH_BAY.get());
        h.setBlock(power, Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(100, () -> {
            BlockState state = h.getBlockState(lamp);
            check(h, state.getValue(IndustrialLampBlock.LIT) && state.getValue(IndustrialLampBlock.WARMUP) == 0,
                    "the mid-warm-up state is " + state);
            ServerLevel level = h.getLevel();
            check(h, level.getBlockTicks().hasScheduledTick(h.absolutePos(lamp), BwrBlocks.MERCURY_HIGH_BAY.get()),
                    "the warm-up step is not scheduled, so the lamp would stall after a save");
            BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
            check(h, restored == state, "the mid-warm-up state does not survive the save format");
            h.succeed();
        });
    }

    // ---- mounts and shapes

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps")
    public static void aHighBayHangsFromACeilingAndAWallPackMountsOnAWall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos high = h.absolutePos(new BlockPos(2, 2, 2));
        BlockState bay = BwrBlocks.HPS_HIGH_BAY.get().defaultBlockState();
        check(h, !bay.canSurvive(level, high), "a high bay hung in the air");
        h.setBlock(new BlockPos(2, 3, 2), Blocks.STONE);
        check(h, bay.canSurvive(level, high), "a high bay did not hang from the ceiling");

        BlockPos pack = h.absolutePos(new BlockPos(6, 2, 6));
        BlockState east = BwrBlocks.HPS_WALL_PACK.get().defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.EAST);
        check(h, !east.canSurvive(level, pack), "a wall pack facing east mounted with no wall to the west");
        h.setBlock(new BlockPos(5, 2, 6), Blocks.STONE);
        check(h, east.canSurvive(level, pack), "a wall pack facing east did not mount on the wall to its west");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrlamps", batch = "industriallamps")
    public static void theWallPackLensTurnsWithItsFacing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        Block pack = BwrBlocks.LPS_WALL_PACK.get();
        AABB north = pack.defaultBlockState().getShape(level, at).bounds();
        AABB east = pack.defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.EAST).getShape(level, at).bounds();
        AABB south = pack.defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.SOUTH).getShape(level, at).bounds();
        AABB west = pack.defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.WEST).getShape(level, at).bounds();
        // The lens reaches 4/16 in from the front and the wall plate sits against the back.
        check(h, Math.abs(north.minZ - 0.25) < 1e-6 && Math.abs(north.maxZ - 1.0) < 1e-6, "north shape " + north);
        check(h, Math.abs(east.maxX - 0.75) < 1e-6 && Math.abs(east.minX - 0.0) < 1e-6, "east shape " + east);
        check(h, Math.abs(south.maxZ - 0.75) < 1e-6 && Math.abs(south.minZ - 0.0) < 1e-6, "south shape " + south);
        check(h, Math.abs(west.minX - 0.25) < 1e-6 && Math.abs(west.maxX - 1.0) < 1e-6, "west shape " + west);
        h.succeed();
    }
}
