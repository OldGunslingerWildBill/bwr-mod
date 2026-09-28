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
    private static final String[] NAMES={"rvu-exterior","rvu-full-water","rvu-low-water","rvu-empty","rvu-broken","rvu-repaired","rvu-pressure-failure","rvu-cherenkov","rvu-inside","rvu-dry-no-glow","rvu-sparger-corners","rvu-jet-proportions"};
    private static boolean started,requested;private static int age,joined,stage,shown;private static long uploads,previousUploads;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.rvuCheck"))return;var mc=Minecraft.getInstance();
        if(++age>5000)throw new AssertionError("RVU timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rvu-"+System.currentTimeMillis(),new LevelSettings("RVU check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        boolean overhead=stage>=1&&stage<=3||stage==7||stage==9;
        var camera=stage==11?new Vec3(167.5,197,160.25):stage==10?new Vec3(150,195,145):stage==8?new Vec3(167.5,204.8,165):stage==6?new Vec3(145,223,140):overhead?new Vec3(168,228,155):new Vec3(135,208,135);
        var target=stage==11?new Vec3(170.25,194.5,161.1):stage==10?new Vec3(150,191.5,151):new Vec3(167.5,overhead?203:stage==8?202:200,167.5);var delta=target.subtract(camera);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)l.setChunkForced(x,z,true);
                try {
                    if(selected==0) {
                        var be=CompactCoreRegressionTests.build(l,MIN,15,15,20,false);
                        for(int offset:new int[]{4,7,10}) {
                            Alpha24RegressionTests.place(l,MIN.offset(0,0,offset),Direction.EAST,true);
                            Alpha24RegressionTests.place(l,MIN.offset(14,0,offset),Direction.WEST,true);
                            Alpha24RegressionTests.place(l,MIN.offset(offset,0,0),Direction.SOUTH,true);
                            Alpha24RegressionTests.place(l,MIN.offset(offset,0,14),Direction.NORTH,true);
                        }
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
                    if(selected==6){
                        be.core().initialiseHotShutdown();
                        be.core().getPressureVessel().setCollapsedLevelIn(8);
                        be.core().getPressureVessel().restorePressurePsig(1500);
                        be.core().getBoundaryStress().forceFailure(dev.bwr.core.boundary.BoundaryComponent.FEEDWATER_LINE,1500);
                        be.core().getBoundaryStress().forceFailure(dev.bwr.core.boundary.BoundaryComponent.REACTOR_VESSEL_HEAD,1700);
                    }
                    if(selected==7) {
                        be.core().initialiseCold();be.core().getDecayHeat().setToSaturatedInventory(1);
                        be.core().getPressureVessel().setCollapsedLevelIn(8);
                        be.setVesselState(VesselState.REFUELING);
                        SpargerRegressionTests.rings(l,be);
                    }
                    if(selected==9)be.core().getPressureVessel().restoreLiquidMassKg(0);
                    if(selected==10) {
                        for(int loop=0;loop<2;loop++) {
                            var s=BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,loop==0?CoreSpraySpargerBlock.Loop.LPCS:CoreSpraySpargerBlock.Loop.HPCS);
                            for(int x=0;x<=2;x++)for(int z=0;z<=2;z++)if(x==0||x==2||z==0||z==2)
                                l.setBlock(new BlockPos(147+loop*4+x,191,150+z),s,3);
                        }
                    }
                    SpargerRegressionTests.validate(be);
                }catch(Exception ex){throw new IllegalStateException(ex);}
                l.setDayTime(selected==8?18000:6000);l.setWeatherParameters(6000,0,false,false);player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
            });
        }
        if(++shown<120)return;
        if(stage==0&&shown==120) {
            new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player,mc.level.enabledFeatures(),true);
            FuelCatalogueClientCheck.run(mc);
        }
        var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(ROOT);boolean formed=stage!=4;
        if(be.clientFormed()!=formed)throw new AssertionError("RVU formed sync "+stage);
        if(formed&&be.clientVesselEnvelope().jets().size()!=12)throw new AssertionError("Jet snapshot missing or duplicated");
        var jetPos=MIN.offset(0,4,7);var jetState=mc.level.getBlockState(jetPos);
        var jetModel=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(jetState));
        var jetData=jetModel.getModelData(mc.level,jetPos,jetState,ModelData.EMPTY);
        if(jetModel.getQuads(jetState,null,net.minecraft.util.RandomSource.create(0),jetData,null).isEmpty()!=formed)throw new AssertionError("Jet construction mesh visibility incorrect "+stage);
        if(jetState.getCollisionShape(mc.level,jetPos).isEmpty()!=formed)throw new AssertionError("Client jet collision stale");
        if(stage==0)checkJetFit(mc);
        if(stage==6&&!be.clientHeadFailed())throw new AssertionError("Failed head not visible on client");
        var roof=MIN.offset(7,20,7);boolean headOpen=stage>=1&&stage<=3||stage>=6;
        if(stage!=4&&mc.level.getBlockState(roof).getCollisionShape(mc.level,roof).isEmpty()!=headOpen)throw new AssertionError("Client head collision stale "+stage);
        if((stage==7||stage==8)&&CherenkovAppearance.strength(be.clientPowerFractionOfRated(),be.clientHasFuel(),be.clientWaterPresent(),VesselWaterGeometry.height(be.clientVesselEnvelope(),be.clientWaterLevelIn(0)))<.05)throw new AssertionError("Decaying wet fuel has no glow");
        if(stage==9&&be.clientWaterPresent())throw new AssertionError("Dry glow fixture retained water");
        if(stage==10)for(int loop=0;loop<2;loop++)for(int x:new int[]{0,2})for(int z:new int[]{0,2}) {
            var p=new BlockPos(147+loop*4+x,191,150+z);var state=mc.level.getBlockState(p);
            if(!state.getValue(CoreSpraySpargerBlock.CORNER))throw new AssertionError("Missing corner "+p);
            var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
            var quads=model.getQuads(state,null,net.minecraft.util.RandomSource.create(0),ModelData.EMPTY,null);
            if(quads.isEmpty()||quads.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing corner model/material");
        }
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
        else if(Boolean.getBoolean("bwr.rvuAccident")) {
            if(stage==1)stage=6;
            else {com.mojang.logging.LogUtils.getLogger().info("RVU ACCIDENT CLIENT CHECK PASS");mc.stop();}
        }
        else if(stage==1&&Boolean.getBoolean("bwr.rvuQuick"))stage=7;
    }
    private static void checkJetFit(Minecraft mc) {
        for(var part:dev.bwr.mod.reactor.client.VesselJetGeometry.PARTS) {
        var id=dev.bwr.mod.reactor.client.VesselJetGeometry.model(part);var model=mc.getModelManager().getModel(id);
        var quads=model.getQuads(null,null,net.minecraft.util.RandomSource.create(0),ModelData.EMPTY,null);
        if(model==mc.getModelManager().getMissingModel()||quads.isEmpty()||quads.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Jet asset missing: "+part);
        for(var q:quads) {
            var v=q.getVertices();int stride=v.length/4;
            for(int i=0;i<4;i++)if(Math.abs(Float.intBitsToFloat(v[i*stride]))>.61001||Math.abs(Float.intBitsToFloat(v[i*stride+2]))>.48001)throw new AssertionError("Jet Blender part exceeds fitting envelope: "+part);
        }
        }
        for(int[] size:new int[][]{{7,7,10},{17,17,22},{23,23,32},{9,13,20},{23,7,131}})for(boolean rip:new boolean[]{false,true})for(int count:new int[]{1,2,12,32,80}) {
            var jets=new java.util.ArrayList<VesselAppearance.Jet>();
            for(int i=0;i<count;i++)jets.add(new VesselAppearance.Jet(new BlockPos(1,1,i+1),Direction.EAST,true));
            var e=new VesselAppearance.Envelope(BlockPos.ZERO,new BlockPos(size[0]-1,size[2]-1,size[1]-1),java.util.List.of(),java.util.List.of(),java.util.Map.of(),jets);
            var core=new VesselCoreAppearance(2,42,java.util.List.of(),java.util.List.of(),rip);
            var mounts=dev.bwr.mod.reactor.client.VesselJetGeometry.layout(e,core);
            for(var m:mounts) {
                if(!dev.bwr.mod.reactor.client.VesselJetGeometry.clearsWalls(e,m.x(),m.z(),m.yaw(),m.scale(),dev.bwr.mod.reactor.client.ReactorCoreGeometry.shroudOuterRadius(core),.4480001))throw new AssertionError("Jet intersects shroud or vessel "+java.util.Arrays.toString(size));
                if(m.top()>=dev.bwr.mod.reactor.client.ReactorCoreGeometry.layout(e,core).top())throw new AssertionError("Jet extends above core");
                if(m.straightLength()<1.4-1e-6||m.straightLength()>3.4+1e-6)throw new AssertionError("Straight-pipe stretch exceeded design limits");
                double ratio=(m.top()-m.bottom())/m.scale();
                if(ratio<4-1e-5||ratio>6+1e-5)throw new AssertionError("Vessel height distorted overall pump proportions");
            }
            for(int i=0;i<count;i++)for(int j=i+1;j<count;j++) {
                var a=mounts.get(i);var b=mounts.get(j);
                if(Math.hypot(a.x()-b.x(),a.z()-b.z())<2*a.scale()*dev.bwr.mod.reactor.client.VesselJetGeometry.MODEL_RADIUS)throw new AssertionError("Jet models overlap");
            }
        }
    }
}
