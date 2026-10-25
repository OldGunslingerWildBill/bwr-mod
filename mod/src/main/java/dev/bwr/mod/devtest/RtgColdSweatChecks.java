package dev.bwr.mod.devtest;

import com.momosoftworks.coldsweat.api.event.core.registry.BlockTempRegisterEvent;
import com.momosoftworks.coldsweat.api.registry.BlockTempRegistry;
import com.momosoftworks.coldsweat.api.temperature.modifier.BlockTempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The RTG heat against the real Cold Sweat (only with {@code -PbwrColdSweat}, which puts Cold Sweat on the dev runtime): its
 * BlockTemp registry holds the RTG sources; its own {@code BlockTempModifier} (the thing that warms a player) reads the heat of a
 * loaded RTG at a distance and nothing from an empty one, falls off with distance, and scales with the watts. This names Cold
 * Sweat types, so it is never loaded without Cold Sweat ({@link LargeRtgTests} checks first).
 */
final class RtgColdSweatChecks {

    private RtgColdSweatChecks() {
    }

    private static double warmth(Player player) {
        return new BlockTempModifier().calculate(player, Temperature.Trait.WORLD).apply(0.0);
    }

    static void run(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var small = BwrBlocks.RTG.get();
        var big = BwrBlocks.LARGE_RTG.get();
        // Cold Sweat builds its BlockTemp registry when a level loads; if this test world's load did not, build it the way it does.
        boolean ours = BlockTempRegistry.getBlockTempsFor(small.defaultBlockState()).stream().anyMatch(t -> t.getClass().getName().startsWith("dev.bwr.mod.compat.coldsweat"));
        if (!ours) {
            NeoForge.EVENT_BUS.post(new BlockTempRegisterEvent());
            BlockTempRegistry.MAPPED_BLOCKS.removeAll(small);
            BlockTempRegistry.MAPPED_BLOCKS.removeAll(big);
        }
        for (var block : new net.minecraft.world.level.block.Block[] {small, big}) {
            boolean registered = BlockTempRegistry.getBlockTempsFor(block.defaultBlockState()).stream().anyMatch(t -> t.getClass().getName().startsWith("dev.bwr.mod.compat.coldsweat"));
            h.assertTrue(registered, "no RTG BlockTemp registered for " + block);
        }
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos spot = new BlockPos(7500, 200, 7500);
        l.getChunk(spot);
        for (var p : BlockPos.betweenClosed(spot.offset(-12, -1, -12), spot.offset(30, 6, 12))) {
            l.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        l.setBlock(spot, small.defaultBlockState(), 3);
        var rtg = (RtgBlockEntity) l.getBlockEntity(spot);
        player.setPos(spot.getX() + 0.5 + 2.0, spot.getY(), spot.getZ() + 0.5);
        double empty = warmth(player);
        h.assertTrue(empty == 0.0, "an empty RTG warmed the player by " + empty);
        rtg.fuel().setStackInSlot(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
        double one = warmth(player);
        rtg.fuel().setStackInSlot(1, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
        double two = warmth(player);
        h.assertTrue(one > 0.0 && Math.abs(two / one - 2.0) < 1e-6, "one ingot warms " + one + ", two " + two);
        player.setPos(spot.getX() + 0.5 + 5.0, spot.getY(), spot.getZ() + 0.5);
        double far = warmth(player);
        player.setPos(spot.getX() + 0.5 + 20.0, spot.getY(), spot.getZ() + 0.5);
        double out = warmth(player);
        h.assertTrue(far > 0.0 && far < two && out == 0.0, "near " + two + ", far " + far + ", out of range " + out);
        rtg.setHeatOnly(true);
        player.setPos(spot.getX() + 0.5 + 2.0, spot.getY(), spot.getZ() + 0.5);
        h.assertTrue(Math.abs(warmth(player) - two) < 1e-12, "heat-only changed the warmth");
        rtg.removalDrops();
        l.removeBlock(spot, false);
        // the Large RTG: its twelve cells share the watts
        BlockPos root = spot.offset(0, 0, 6);
        var stack = new ItemStack(big);
        ((net.minecraft.world.item.BlockItem) stack.getItem()).place(new DirectionalPlaceContext(l, root, Direction.SOUTH, stack, Direction.UP));
        var large = (LargeRtgBlockEntity) l.getBlockEntity(root);
        player.setPos(root.getX() + 0.5 + 3.0, root.getY() + 1, root.getZ() + 0.5);
        h.assertTrue(warmth(player) == 0.0, "an empty Large RTG warmed the player");
        large.loadFrom(8, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 8));
        double w8 = warmth(player);
        large.loadFrom(8, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 8));
        double w16 = warmth(player);
        h.assertTrue(w8 > 0.0 && Math.abs(w16 / w8 - 2.0) < 1e-6, "8 ingots warm " + w8 + ", 16 warm " + w16);
        large.removalDrops();
        l.removeBlock(root, false);
        System.out.println("COLD SWEAT: registry has the RTG and Large RTG BlockTemps; real BlockTempModifier: empty 0, 1 ingot " + one + ", 2 ingots " + two
                + " MC units at 2 m, " + far + " at 5 m, 0 at 20 m; Large RTG 8 ingots " + w8 + ", 16 ingots " + w16);
    }
}
