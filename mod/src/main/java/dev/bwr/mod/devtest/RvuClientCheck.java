package dev.bwr.mod.devtest;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.registry.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Disposable actual-game RVU captures, including moving water and GPU cache stability. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class RvuClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160),ROOT=MIN.offset(-1,3,7);
    private static final String[] NAMES={"rvu-exterior","rvu-full-water","rvu-low-water","rvu-empty","rvu-broken","rvu-repaired"};
    private static boolean started,requested;private static int age,joined,stage,shown;private static long uploads,previousUploads;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.rvuCheck"))return;var mc=Minecraft.getInstance();
        if(++age>5000)throw new AssertionError("RVU timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rvu-"+System.currentTimeMillis(),new LevelSettings("RVU check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        boolean overhead=stage>=1&&stage<=3;
        var camera=overhead?new Vec3(168,228,155):new Vec3(135,208,135);
        var target=new Vec3(167.5,overhead?203:200,167.5);var delta=target.subtract(camera);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)l.setChunkForced(x,z,true);
                try {
                    if(selected==0) {
                        var be=CompactCoreRegressionTests.build(l,MIN,15,15,20,false);
                        be.setVesselState(VesselState.REFUELING);
                        for(int slot:be.corePositions())be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                        for(Direction face:Direction.Plane.HORIZONTAL)for(int y:new int[]{3,17}) {
                            if(face==Direction.WEST&&y==3)continue; // Controller occupies this interface.
                            var p=MIN.offset(7,y,7).relative(face,8);
                            l.setBlock(p,(y==3?BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,face):BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState()),3);
                            for(int i=1;i<=3;i++) {
                                var at=p.relative(face,i);
                                l.setBlock(at,y==3?BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,at):BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,at),3);
                            }
                        }
                        SpargerRegressionTests.validate(be);be.setVesselState(VesselState.SHUTDOWN);
                    }
                    var be=(ReactorControllerBlockEntity)l.getBlockEntity(ROOT);
                    if(selected>=1&&selected<=3) {
                        be.setVesselState(VesselState.REFUELING);
                        be.core().getPressureVessel().setCollapsedLevelIn(selected==1?8:selected==2?-230:-530);
                    }
                    if(selected==4)l.removeBlock(MIN.offset(7,17,-1),false);
                    if(selected==5){l.setBlock(MIN.offset(7,17,-1),BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);be.setVesselState(VesselState.SHUTDOWN);}
                    SpargerRegressionTests.validate(be);
                }catch(Exception ex){throw new IllegalStateException(ex);}
                l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
            });
        }
        if(++shown<120)return;
        if(stage==0&&shown==120) {
            new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player,mc.level.enabledFeatures(),true);
            FuelCatalogueClientCheck.run(mc);
        }
        var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(ROOT);boolean formed=stage!=4;
        if(be.clientFormed()!=formed)throw new AssertionError("RVU formed sync "+stage);
        for(var p:new BlockPos[]{ROOT,MIN.offset(7,3,-1),MIN.offset(7,17,15)}) {
            var state=mc.level.getBlockState(p);var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
            var data=model.getModelData(mc.level,p,state,ModelData.EMPTY);int faces=0;
            for(Direction face:Direction.values())faces+=model.getQuads(state,face,net.minecraft.util.RandomSource.create(42),data,null).size();
            faces+=model.getQuads(state,null,net.minecraft.util.RandomSource.create(42),data,null).size();
            if((faces==0)!=formed)throw new AssertionError("RVU port cube visible/hidden incorrectly "+p);
        }
        if((stage==1||stage==2)&&!be.clientWaterPresent()||stage==3&&be.clientWaterPresent())throw new AssertionError("Water inventory sync "+stage);
        for(String part:ReactorVesselRenderer.PARTS) {
            var model=mc.getModelManager().getModel(ReactorVesselRenderer.model(part));
            var quads=model.getQuads(null,null,net.minecraft.util.RandomSource.create(0),ModelData.EMPTY,null);
            if(model==mc.getModelManager().getMissingModel()||quads.isEmpty()||quads.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing RVU mesh/material "+part);
        }
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=true;mc.getToasts().clear();
        if(shown==125)uploads=MachineMeshCache.uploadCount();if(shown<155)return;
        if(MachineMeshCache.uploadCount()!=uploads)throw new AssertionError("RVU water causes mesh rebuilds");
        if((stage==2||stage==3)&&uploads!=previousUploads)throw new AssertionError("Changing water level rebuilt static geometry");
        previousUploads=uploads;
        var dir=mc.gameDirectory.toPath().resolve("rvu-check");java.nio.file.Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("RVU CHECK {} level={} meshUploads={}",NAMES[stage],be.clientWaterLevelIn(0),uploads);requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("RVU CLIENT CHECK PASS");mc.stop();}
    }
}
