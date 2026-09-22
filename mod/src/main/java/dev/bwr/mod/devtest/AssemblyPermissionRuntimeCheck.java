package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** Executed on the dedicated dev server, where vanilla spawn protection is active. */
public final class AssemblyPermissionRuntimeCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static int run(ServerLevel level) {
        var server=level.getServer();var data=(net.minecraft.world.level.storage.ServerLevelData)level.getLevelData();
        var oldSpawn=level.getSharedSpawnPos();float oldAngle=level.getSharedSpawnAngle();
        var player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("9e76cb9d-3cbe-47ee-aa80-6f2ea45ded51"),"bwr_permission_test"));
        var operator=new com.mojang.authlib.GameProfile(UUID.fromString("c88b97f7-5fbd-43ab-aa56-70771b5fc831"),"bwr_test_operator");
        var root=new BlockPos(240,200,224);int passed=0;
        try {
            check(server instanceof net.minecraft.server.dedicated.DedicatedServer,"permission check needs dedicated server");
            check(server.getSpawnProtectionRadius()>0,"fixture spawn protection is disabled");
            server.getPlayerList().op(operator);player.setGameMode(GameType.SURVIVAL);player.setPos(200,240,224);player.setYRot(0);
            for(int x=13;x<=17;x++)for(int z=12;z<=16;z++)level.getChunk(x,z);
            data.setSpawn(root.east(server.getSpawnProtectionRadius()+1),0);
            check(level.mayInteract(player,root)&&!level.mayInteract(player,root.east()),"spawn boundary fixture is invalid");
            for(var block:List.of(BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RCIC_TWL.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.ARABELLE_CONDENSER.get(),BwrBlocks.CIRCULATING_WATER_PUMP.get())) {
                var context=new BlockPlaceContext(level,player,InteractionHand.MAIN_HAND,new ItemStack(block),new BlockHitResult(Vec3.atCenterOf(root),Direction.UP,root,false));
                check(block.getStateForPlacement(context)==null,"placement crossed protected boundary: "+block);
                var state=block instanceof dev.bwr.mod.eccs.PumpAssemblyBlock p?p.placementState():block.defaultBlockState();
                level.setBlock(root,state,3);block.setPlacedBy(level,root,state,null,new ItemStack(block));
                var footprint=AssemblyAccess.cells(level,root,state);
                check(footprint.size()>1&&!AssemblyAccess.allAllowed(footprint,p->level.mayInteract(player,p)),"assembly does not cross boundary");
                var event=new BlockEvent.BreakEvent(level,root,state,player);NeoForge.EVENT_BUS.post(event);
                check(event.isCanceled()&&level.getBlockState(root).is(block),"unprotected root can remove protected remote cells");
                level.removeBlock(root,false);passed++;
            }
            // Tank parts retain their footprint even when their controller is unavailable.
            var block=BwrBlocks.CONDENSATE_STORAGE_TANK.get();level.setBlock(root,block.defaultBlockState(),3);
            var tank=(dev.bwr.mod.eccs.CondensateStorageTankBlockEntity)level.getBlockEntity(root);
            server.getPlayerList().op(player.getGameProfile());player.setGameMode(GameType.CREATIVE);
            check(dev.bwr.mod.eccs.CondensateTankAssembly.resize(tank,player,3,3).startsWith("Assembled"),"operator tank build failed");
            server.getPlayerList().deop(player.getGameProfile());player.setGameMode(GameType.SURVIVAL);
            var event=new BlockEvent.BreakEvent(level,root,level.getBlockState(root),player);NeoForge.EVENT_BUS.post(event);check(event.isCanceled(),"tank teardown crossed protection");passed++;
            level.removeBlock(root,false);
            var unformed=CondensateTankRuntimeCheck.buildShell(level,player,root,3,3);
            check(!unformed.assembled(),"automatic tank crossed spawn protection");passed++;
            for(var at:BlockPos.betweenClosed(root.offset(-1,0,-1),root.offset(1,2,1)))level.removeBlock(at,false);
            com.mojang.logging.LogUtils.getLogger().info("ASSEMBLY PERMISSION CHECK PASS: {} dedicated-server scenarios",passed);return 0;
        } catch(Throwable e) {com.mojang.logging.LogUtils.getLogger().error("ASSEMBLY PERMISSION CHECK FAIL after {} scenarios",passed,e);return 1;}
        finally {level.removeBlock(root,false);data.setSpawn(oldSpawn,oldAngle);server.getPlayerList().deop(operator);server.getPlayerList().deop(player.getGameProfile());}
    }
}
