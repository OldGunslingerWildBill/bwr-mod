package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.*;
import dev.bwr.mod.reactor.client.CoreSpargerGeometry;
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

/** Actual game model baking, networked appearance and assembly lifecycle. Disposable world only. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class SpargerClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160);
    private static final String[] NAMES={"reference-rings","missing-segments","repaired-rings","unformed-segments","minimum-rings","maximum-rings","rectangular-rings"};
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static int w(){return stage==4?5:stage==5?21:stage==6?7:15;}
    private static int d(){return stage==6?11:w();}
    private static int h(){return stage==4?8:stage==5?22:stage==6?14:20;}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.spargerCheck"))return;var mc=Minecraft.getInstance();
        if(++age>4200)throw new IllegalStateException("Sparger client timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-sparger-check-"+System.currentTimeMillis(),new LevelSettings("Sparger check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        int w=w(),d=d(),h=h(),expected=2*(2*w+2*d-4);var root=MIN.offset(-1,3,d/2);
        double cx=MIN.getX()+w/2.0,cz=MIN.getZ()+d/2.0;
        var camera=new Vec3(cx-w*.50,MIN.getY()+h+Math.max(w,d)*1.0,cz-d*.90);
        var target=new Vec3(cx,MIN.getY()+h-3,cz);var delta=target.subtract(camera);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)l.setChunkForced(x,z,true);
                try {
                    if(selected==0||selected>=4) {
                        for(var p:BlockPos.betweenClosed(MIN.offset(-3,-3,-3),MIN.offset(24,25,24)))l.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                        var be=CompactCoreRegressionTests.build(l,MIN,w,d,h,false);SpargerRegressionTests.rings(l,be);SpargerRegressionTests.validate(be);be.setVesselState(VesselState.REFUELING);
                    } else if(l.getBlockEntity(root) instanceof ReactorControllerBlockEntity be) {
                        if(selected==1) {
                            for(var loop:CoreSpraySpargerBlock.Loop.values())for(int x=4;x<=6;x++)l.removeBlock(new BlockPos(MIN.getX()+x,CoreSpraySpargerBlock.requiredY(loop,be.structure().topOfActiveFuelY()),MIN.getZ()),false);
                        } else if(selected==2)SpargerRegressionTests.rings(l,be);
                        else if(selected==3)l.removeBlock(MIN.west().above(),false);
                        SpargerRegressionTests.validate(be);
                    }
                }catch(Exception ex){throw new IllegalStateException(ex);}
                l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
            });
        }
        if(++shown<130)return;
        boolean formed=stage!=3;var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(root);
        var envelope=be.clientVesselEnvelope();
        if(formed&&(envelope==null||envelope.spargers().size()!=expected-(stage==1?6:0)))throw new AssertionError("Wrong client ring count at stage "+stage);
        if(!formed&&envelope!=null)throw new AssertionError("Unformed ring still rendered");
        var probe=MIN.offset(0,h-2,0); // HPCS is one below interior ceiling.
        if(VesselAppearance.hidesSparger(mc.level,probe)!=formed)throw new AssertionError("Stale sparger block visibility");
        var state=mc.level.getBlockState(probe);var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
        var data=model.getModelData(mc.level,probe,state,ModelData.EMPTY);
        if(model.getQuads(state,null,net.minecraft.util.RandomSource.create(42),data,null).isEmpty()!=formed)throw new AssertionError("Sparger chunk mesh disagrees");
        for(var part:CoreSpargerGeometry.PARTS) {
            var baked=mc.getModelManager().getModel(CoreSpargerGeometry.model(part));
            var faces=baked.getQuads(null,null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);
            if(baked==mc.getModelManager().getMissingModel()||faces.isEmpty()||faces.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing sparger model/material "+part);
        }
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=true;mc.getToasts().clear();
        if(shown<140)return;
        var dir=mc.gameDirectory.toPath().resolve("sparger-check");java.nio.file.Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("SPARGER CLIENT CHECK: {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("SPARGER CLIENT CHECK PASS: four sizes, materials, gaps, repair and unforming");mc.stop();}
    }
}
