package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.reactor.CoreSpraySpargerBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.UUID;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class Alpha27RegressionTests {
    private static FakePlayer player(ServerLevel level, String name) {
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name));
        player.setGameMode(GameType.CREATIVE);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }
    private static void clear(ServerLevel level, BlockPos p) {
        for(var pos:BlockPos.betweenClosed(p.offset(-2,-1,-2),p.offset(2,1,2))) {
            level.getChunk(pos);level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static BlockHitResult hit(BlockPos p, Direction side) {
        return new BlockHitResult(Vec3.atCenterOf(p),side,p,false);
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void sprayBranchFacesPlayerAndKeepsFacing(GameTestHelper h) {
        var level=h.getLevel();var player=player(level,"SpargerPlacement");
        var block=BwrBlocks.CORE_SPRAY_SPARGER.get();int i=0;
        for(var direction:Direction.Plane.HORIZONTAL) {
            var p=new BlockPos(26400+i++*8,220,26400);clear(level,p);
            level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
            player.setPos(Vec3.atCenterOf(p.relative(direction.getOpposite(),2)));
            player.setYRot(direction.toYRot());
            var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,new ItemStack(block),hit(p.below(),Direction.UP));
            var placed=block.getStateForPlacement(context);
            h.assertTrue(placed.getValue(CoreSpraySpargerBlock.FACING).getClockWise()==direction.getOpposite(),"spray faces away from player looking "+direction);
            level.setBlock(p,placed,3);
            var along=placed.getValue(CoreSpraySpargerBlock.FACING);
            level.setBlock(p.relative(along),placed,3);
            level.setBlock(p.relative(along.getOpposite()),placed,3);
            h.assertTrue(level.getBlockState(p)==placed,"straight neighbors flipped nozzle "+direction);
            level.removeBlock(p.relative(along),false);
            h.assertTrue(level.getBlockState(p)==placed,"removing neighbor flipped nozzle "+direction);
        }
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static ItemStack configurator(String mode) throws Exception {
        var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:configurator")));
        if(stack.isEmpty())throw new AssertionError("Mekanism Configurator is required for this integration test");
        var data=(DataComponentType<Object>)stack.getItem().getClass().getMethod("getModeDataType").invoke(stack.getItem());
        var modes=Class.forName("mekanism.common.item.ItemConfigurator$ConfiguratorMode").getEnumConstants();
        stack.set(data,Arrays.stream(modes).filter(v->((Enum<?>)v).name().equals(mode)).findFirst().orElseThrow());
        return stack;
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void realConfiguratorRotatesAndPersistsStraightAndCorner(GameTestHelper h) throws Exception {
        var level=h.getLevel();var player=player(level,"SpargerRotation");
        var block=BwrBlocks.CORE_SPRAY_SPARGER.get();
        var tool=configurator("ROTATE");
        h.assertTrue(tool.canPerformAction(ItemAbility.get("wrench_rotate")),"Configurator not in Rotate mode");
        for(boolean corner:new boolean[]{false,true}) {
            var p=new BlockPos(corner?26460:26450,220,26400);clear(level,p);
            player.setPos(Vec3.atCenterOf(p.south(2)));
            player.setItemInHand(InteractionHand.MAIN_HAND,tool);
            var initial=block.defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,CoreSpraySpargerBlock.Loop.HPCS);
            level.setBlock(p,initial,3);
            level.setBlock(p.north(),initial,3);
            level.setBlock(corner?p.east():p.south(),initial,3);
            var facing=level.getBlockState(p).getValue(CoreSpraySpargerBlock.FACING);
            for(int n=0;n<4;n++) {
                var result=player.gameMode.useItemOn(player,level,tool,InteractionHand.MAIN_HAND,hit(p,Direction.UP));
                facing=facing.getClockWise();var state=level.getBlockState(p);
                h.assertTrue(result.consumesAction(),"Configurator click not handled");
                h.assertTrue(state.getValue(CoreSpraySpargerBlock.FACING)==facing,"Configurator failed quarter turn");
                h.assertTrue(state.getValue(CoreSpraySpargerBlock.CORNER)==corner,"rotation changed straight/corner shape");
                h.assertTrue(state.getValue(CoreSpraySpargerBlock.LOOP)==CoreSpraySpargerBlock.Loop.HPCS,"rotation changed loop");
                h.assertTrue(state.getValue(CoreSpraySpargerBlock.MANUAL_ORIENTATION),"rotation not locked");
                level.removeBlock(p.north(),false);level.setBlock(p.north(),initial,3);
                h.assertTrue(level.getBlockState(p)==state,"neighbor undid manual rotation");
                var restored=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),NbtUtils.writeBlockState(state));
                h.assertTrue(restored==state,"rotation lost during save/reload");
            }
            var old=NbtUtils.writeBlockState(initial);old.getCompound("Properties").remove("manual_orientation");
            h.assertTrue(!NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),old).getValue(CoreSpraySpargerBlock.MANUAL_ORIENTATION),"old save does not default to automatic corners");
            player.setGameMode(GameType.ADVENTURE);var before=level.getBlockState(p);
            player.gameMode.useItemOn(player,level,tool,InteractionHand.MAIN_HAND,hit(p,Direction.UP));
            h.assertTrue(level.getBlockState(p)==before,"tool bypasses build permission");
            player.setGameMode(GameType.CREATIVE);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void heldItemsDoNotToggleLoopOrSwallowAdjacentPlacement(GameTestHelper h) throws Exception {
        var level=h.getLevel();var player=player(level,"SpargerInteraction");
        var block=BwrBlocks.CORE_SPRAY_SPARGER.get();var p=new BlockPos(26480,220,26400);clear(level,p);
        player.setPos(Vec3.atCenterOf(p.south(2)));player.setYRot(Direction.NORTH.toYRot());
        var initial=block.defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,CoreSpraySpargerBlock.Loop.HPCS);
        level.setBlock(p,initial,3);
        for(var stack:new ItemStack[]{new ItemStack(Items.STICK),configurator("CONFIGURATE_ITEMS")}) {
            player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,hit(p,Direction.EAST));
            h.assertTrue(level.getBlockState(p)==initial,"held item changed loop or orientation");
        }
        // Ordinary, non-sneaking placement on an existing ring segment.
        var stack=new ItemStack(block);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,hit(p,Direction.EAST));
        h.assertTrue(level.getBlockState(p.east()).is(block),"right click swallowed adjacent block placement");
        h.assertTrue(level.getBlockState(p).getValue(CoreSpraySpargerBlock.LOOP)==CoreSpraySpargerBlock.Loop.HPCS,"placement switched existing loop");
        h.assertTrue(level.getBlockState(p.east()).getValue(CoreSpraySpargerBlock.LOOP)==CoreSpraySpargerBlock.Loop.HPCS,"extension lost inherited loop");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit(p,Direction.UP));
        h.assertTrue(level.getBlockState(p).getValue(CoreSpraySpargerBlock.LOOP)==CoreSpraySpargerBlock.Loop.LPCS,"empty hand no longer toggles loop");
        h.succeed();
    }
}
