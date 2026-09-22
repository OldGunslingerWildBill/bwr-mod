package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Disposable client/server visual and lifecycle checks, never player saves. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class VesselClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160);
    private static final String[] NAMES={"reference-vessel","open-head","broken-shell","repaired-shell","controller-removed","minimum-vessel","maximum-vessel","rectangular-vessel"};
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static int width(){return stage==5?5:stage==6?21:stage==7?7:15;}
    private static int depth(){return stage==7?11:width();}
    private static int height(){return stage==5?8:stage==6?22:stage==7?14:20;}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bwr.vesselModelCheck"))return;
        var mc=Minecraft.getInstance();
        if(++age>4500)throw new IllegalStateException("Vessel client timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-vessel-check-"+System.currentTimeMillis(),new LevelSettings("Vessel check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        int w=width(),d=depth(),h=height();var root=MIN.offset(-1,3,d/2);var breach=MIN.offset(-1,10,d/2+2);
        double cx=MIN.getX()+w/2.0,cz=MIN.getZ()+d/2.0;
        double distance=Math.max(w,d)*1.65+8;
        var camera=new Vec3(cx-distance,MIN.getY()+h*.9,cz-distance);
        var target=new Vec3(cx,MIN.getY()+h*.47,cz);
        var delta=target.subtract(camera);float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var level=player.serverLevel();
                player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=7;x<=13;x++)for(int z=7;z<=13;z++)level.setChunkForced(x,z,true);
                try {
                    if(selected==0||selected>=5) {
                        // Clear the previous disposable fixture before building another size.
                        for(var p:BlockPos.betweenClosed(MIN.offset(-5,-3,-5),MIN.offset(24,25,24)))level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                        for(var p:BlockPos.betweenClosed(MIN.offset(-6,-3,-6),MIN.offset(25,-3,25)))level.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),2);
                        var be=CompactCoreRegressionTests.build(level,MIN,w,d,h,false);
                        if(!be.isFormed())throw new AssertionError(be.statusLines());
                        for(var port:new BlockPos[]{MIN.offset(w/2,3,-1),MIN.offset(w/2,h-4,-1)}) {
                            level.setBlock(port,(port.getY()==MIN.getY()+3?BwrBlocks.RPV_WATER_INJECTION_PORT.get():BwrBlocks.RPV_STEAM_OUTLET.get()).defaultBlockState(),3);
                            for(int i=1;i<=3;i++) {
                                var at=port.north(i);
                                level.setBlock(at,port.getY()==MIN.getY()+3?BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level,at):BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(level,at),3);
                            }
                        }
                        be.markStructureDirty();
                    } else if(selected==1)((ReactorControllerBlockEntity)level.getBlockEntity(root)).setVesselState(VesselState.REFUELING);
                    else if(selected==2)level.setBlock(breach,Blocks.AIR.defaultBlockState(),3);
                    else if(selected==3) {
                        level.setBlock(breach,BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),3);
                        ((ReactorControllerBlockEntity)level.getBlockEntity(root)).setVesselState(VesselState.SHUTDOWN);
                    } else if(selected==4)level.removeBlock(root,false);
                    if(level.getBlockEntity(root) instanceof ReactorControllerBlockEntity be)be.markStructureDirty();
                } catch(Exception ex){throw new IllegalStateException(ex);}
                level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
            });
        }
        shown++;if(shown<120)return;
        boolean formed=stage!=2&&stage!=4;
        var owner=mc.level.getBlockEntity(root);
        if(formed&&(!(owner instanceof ReactorControllerBlockEntity be)||be.clientVesselEnvelope()==null||!be.clientFormed()))throw new AssertionError("Missing client vessel envelope: "+stage);
        var probe=MIN.offset(-1,2,2);var state=mc.level.getBlockState(probe);
        if(VesselAppearance.hides(mc.level,probe)!=formed)throw new AssertionError("Stale client shell visibility at stage "+stage);
        var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
        var data=model.getModelData(mc.level,probe,state,ModelData.EMPTY);
        var quads=model.getQuads(state,Direction.WEST,net.minecraft.util.RandomSource.create(42),data,null);
        if(quads.isEmpty()!=formed)throw new AssertionError("Chunk model disagrees with formed state");
        for(String name:ReactorVesselRenderer.PARTS) {
            var part=mc.getModelManager().getModel(ReactorVesselRenderer.model(name));
            var faces=part.getQuads(null,null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);
            if(part==mc.getModelManager().getMissingModel()||faces.isEmpty()||faces.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing vessel material: "+name);
        }
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);
        mc.options.hideGui=true;mc.getToasts().clear();if(shown<130)return;
        try {
            var dir=mc.gameDirectory.toPath().resolve("vessel-check");java.nio.file.Files.createDirectories(dir);
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        com.mojang.logging.LogUtils.getLogger().info("VESSEL CLIENT CHECK: {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("VESSEL CLIENT CHECK PASS: four sizes, materials, open head, break/repair and controller removal");mc.stop();}
    }
}
