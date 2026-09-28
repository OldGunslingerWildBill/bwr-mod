package dev.bwr.mod.devtest;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.flow.RecirculationNetwork;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.reactor.client.*;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import net.minecraft.client.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.util.*;
import java.nio.file.*;

/** One requested fixture only: 17 x 17, 28 blocks from construction drives to cap.
 * Tests real server formation, real baked meshes, client lifecycle, and the GPU cache. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class ReactorFitClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160), ROOT=MIN.offset(-1,3,7), WALL=MIN.offset(7,3,-1);
    private static final String[] NAMES={"exterior","underside","open-core","inside-shroud","unformed","reformed-new-rings","save-reloaded","resource-reloaded","water-at-fuel-top"};
    private static boolean started,requested;
    private static volatile boolean serverReady,resourceReady=true;
    private static volatile Throwable failure;
    private static int age,joined,stage,shown;
    private static long uploads;
    private static final List<BlockPos> JETS=new ArrayList<>();
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}

    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.reactorFitCheck"))return;
        var mc=Minecraft.getInstance();if(failure!=null)throw new AssertionError("Reactor fit server checks",failure);
        if(++age>5000)throw new AssertionError("Reactor fit timeout stage="+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(55);
            mc.createWorldOpenFlows().createFreshLevel("bwr-fit-"+System.currentTimeMillis(),new LevelSettings("17x17x28 fit check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        Vec3 camera=switch(stage) {
            case 1 -> new Vec3(150,184,150);
            case 2,8 -> new Vec3(167.5,224,161);
            case 3 -> new Vec3(167.5,202,160.1);
            default -> new Vec3(137,204,137);
        };
        Vec3 target=switch(stage) {
            case 1 -> new Vec3(167.5,190,167.5);
            case 2,8 -> new Vec3(167.5,198,167.5);
            case 3 -> new Vec3(167.5,195,170);
            default -> new Vec3(167.5,201,167.5);
        };
        var delta=target.subtract(camera);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;serverReady=false;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                try {
                    var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                    player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                    for(int x=8;x<=12;x++)for(int z=8;z<=12;z++)l.setChunkForced(x,z,true);
                    var be=selected==0?build(l):(ReactorControllerBlockEntity)l.getBlockEntity(ROOT);
                    if(selected==2){be.setVesselState(VesselState.REFUELING);be.core().getPressureVessel().restoreLiquidMassKg(0);}
                    if(selected==4)l.removeBlock(WALL,false);
                    if(selected==5){
                        l.setBlock(WALL,BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),3);
                        rings(l,be,false);be.setVesselState(VesselState.SHUTDOWN);
                    }
                    if(selected==6){
                        var snapshot=be.saveWithFullMetadata(l.registryAccess());var state=be.getBlockState();l.removeBlockEntity(ROOT);
                        check(!VesselDriveAccess.contains(l,MIN.below(2))&&!VesselJetAccess.contains(l,JETS.getFirst()),"Removed owner retained indexes");
                        be=(ReactorControllerBlockEntity)BlockEntity.loadStatic(ROOT,state,snapshot,l.registryAccess());l.setBlockEntity(be);
                    }
                    if(selected==8){be.setVesselState(VesselState.REFUELING);be.core().getPressureVessel().setCollapsedLevelIn(dev.bwr.core.PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN);}
                    SpargerRegressionTests.validate(be);
                    serverChecks(l,be,selected!=4);
                    l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);
                    player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
                    serverReady=true;
                }catch(Throwable ex){failure=ex;}
            });
            if(selected==7){resourceReady=false;mc.reloadResourcePacks().whenComplete((v,ex)->{if(ex!=null)failure=ex;resourceReady=true;});}
        }
        if(!serverReady||!resourceReady||mc.getOverlay()!=null)return;
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=true;mc.getToasts().clear();
        if(++shown<120)return;
        var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(ROOT);check(be!=null,"Missing client controller");
        if(shown==120){clientChecks(mc,be,stage!=4);if(stage==0){geometryChecks(mc,be);exportLayout(mc,be);}uploads=MachineMeshCache.uploadCount();}
        if(shown<150)return;
        check(MachineMeshCache.uploadCount()==uploads,"Static geometry rebuilt without construction edits at stage "+stage);
        var dir=mc.gameDirectory.toPath().resolve("reactor-fit-check");Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("REACTOR FIT PASS {}: 17x17x28, 9-block core; uploads={}",NAMES[stage],uploads);
        requested=false;if(++stage==NAMES.length){Files.writeString(dir.resolve("PASS.txt"),"PASS: 17x17x28 only; 9-block active core; 764 bundles; 185 rendered drives; 225 construction drives hidden; 12 complete jets at 3 elevations; old/new rings; unform/reform; server save reload; resource reload; water level; GPU cache; actual baked-vertex containment.\n");mc.stop();}
    }

    private static ReactorControllerBlockEntity build(ServerLevel l)throws Exception {
        var be=CompactCoreRegressionTests.build(l,MIN,15,15,25,false);
        // Reproduce an overbuilt square drive field, without deleting the player's spare blocks.
        for(int x=0;x<15;x++)for(int z=0;z<15;z++)if(!l.getBlockState(MIN.offset(x,-2,z)).is(BwrBlocks.CONTROL_ROD_DRIVE.get()))
            l.setBlock(MIN.offset(x,-2,z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),3);
        int[] offsets={4,7,10},heights={0,3,15};
        for(int i=0;i<3;i++)for(Direction face:Direction.Plane.HORIZONTAL) {
            int offset=offsets[i],edge=i==2?1:0;var p=switch(face){case EAST->MIN.offset(edge,heights[i],offset);case WEST->MIN.offset(14-edge,heights[i],offset);case SOUTH->MIN.offset(offset,heights[i],edge);default->MIN.offset(offset,heights[i],14-edge);};
            Alpha24RegressionTests.place(l,p,face,true);JETS.add(p);
        }
        be.setVesselState(VesselState.REFUELING);
        for(int slot:be.corePositions())check(be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"Fuel refused");
        rings(l,be,true);SpargerRegressionTests.validate(be);
        be.setVesselState(VesselState.SHUTDOWN);
        // Bottom-face I/O and the existing daisy chain remain connected to their saved cells.
        for(int x=0;x<15;x++)for(int z=0;z<15;z++) {
            var hardware=((ControlRodDriveBlockEntity)l.getBlockEntity(MIN.offset(x,-2,z))).hardware();
            hardware.setEnergyStoredFe(0);hardware.setWaterStoredMb(0);
        }
        var first=(ControlRodDriveBlockEntity)l.getBlockEntity(MIN.below(2));
        var second=(ControlRodDriveBlockEntity)l.getBlockEntity(MIN.below(2).south());
        var energy=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,first.getBlockPos(),Direction.DOWN);
        var water=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,second.getBlockPos(),Direction.DOWN);
        check(energy!=null&&water!=null,"Saved bottom-face drive connections inaccessible");
        check(energy.receiveEnergy(10000,false)>0,"Drive power rejected");
        check(water.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,4000),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)>0,"Drive water rejected");
        return be;
    }
    private static void rings(ServerLevel l,ReactorControllerBlockEntity be,boolean legacy) {
        int newTop=VesselInternalsGeometry.constructionFuelTop(MIN,MIN.offset(14,24,14)),oldTop=MIN.getY()+19;
        for(var loop:CoreSpraySpargerBlock.Loop.values())for(int x=0;x<15;x++)for(int z=0;z<15;z++)if(x==0||x==14||z==0||z==14) {
            if(!legacy)l.removeBlock(new BlockPos(MIN.getX()+x,oldTop+loop.blocksAboveTaf(),MIN.getZ()+z),false);
            l.setBlock(new BlockPos(MIN.getX()+x,(legacy?oldTop:newTop)+loop.blocksAboveTaf(),MIN.getZ()+z),BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,loop),3);
        }
    }
    private static void serverChecks(ServerLevel l,ReactorControllerBlockEntity be,boolean formed) {
        check(be.isFormed()==formed,"Server formation mismatch: "+be.statusLines());
        check((!formed||be.assemblyCount()==764)&&be.core().getControlRodCount()==185,"Core capacity changed: bundles="+be.assemblyCount()+" drives="+be.core().getControlRodCount());
        check(be.core().getCoreLoading().loadedAssemblyCount()==764,"Fuel lost during lifecycle");
        if(formed) {
            var e=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
            check(e.width()==17&&e.depth()==17&&e.height()+1==28,"Wrong fixture dimensions");
            check(e.jets().size()==12&&e.driveCells().size()==225,"Missing/duplicated internals snapshot");
            check(be.structure().topOfActiveFuelY()==e.min().getY()+11,"Construction fuel top disagrees with visual cap");
            check(be.structure().sprayRingCompleteness()==1,"Old/new rings lost spray capacity");
            var flow=RecirculationNetwork.measure(l,be,List.of());check(flow.pairedJets()==12&&flow.unmatchedJets()==0,"Raised jet pairing lost");
        }
        for(int x=0;x<15;x++)for(int z=0;z<15;z++) {
            var p=MIN.offset(x,-2,z);check(l.getBlockEntity(p) instanceof ControlRodDriveBlockEntity,"Drive deleted");
            check(VesselDriveAccess.contains(l,p)==formed,"Server drive index stale");
            check(l.getBlockState(p).getCollisionShape(l,p).isEmpty()==formed,"Server drive ghost collision");
        }
        for(var p:JETS)for(int y=0;y<6;y++)check(VesselJetAccess.contains(l,p.above(y))==formed,"Server jet child index stale");
    }
    private static void clientChecks(Minecraft mc,ReactorControllerBlockEntity be,boolean formed) {
        check(be.clientFormed()==formed,"Client formation sync stale");
        for(int x=0;x<15;x++)for(int z=0;z<15;z++)hidden(mc,MIN.offset(x,-2,z),formed);
        for(var p:JETS)for(int y=0;y<6;y++)hidden(mc,p.above(y),formed);
        hidden(mc,ROOT,formed);
        if(formed) {
            var e=be.clientVesselEnvelope();check(e.jets().size()==12&&e.driveCells().size()==225,"Client snapshot incomplete");
            check(be.clientCoreAppearance().drives().size()==185,"Client drive mapping changed");
            var roof=MIN.offset(7,25,7);boolean open=stage==2||stage==3||stage==8;
            check(mc.level.getBlockState(roof).getCollisionShape(mc.level,roof).isEmpty()==open,"Head collision does not match open state");
        }
    }
    private static void hidden(Minecraft mc,BlockPos p,boolean hidden) {
        var s=mc.level.getBlockState(p);var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(s));
        var data=model.getModelData(mc.level,p,s,ModelData.EMPTY);int faces=0;
        for(int i=0;i<7;i++)faces+=model.getQuads(s,i==6?null:Direction.values()[i],RandomSource.create(0),data,null).size();
        check((faces==0)==hidden,"Construction mesh visibility wrong at "+p);
        if(!p.equals(ROOT))check(s.getCollisionShape(mc.level,p).isEmpty()==hidden,"Client ghost collision at "+p);
    }
    private static List<Vec3> vertices(Minecraft mc,ModelResourceLocation id) {
        var model=mc.getModelManager().getModel(id);check(model!=mc.getModelManager().getMissingModel(),"Missing asset "+id);
        var result=new ArrayList<Vec3>();
        for(int side=0;side<7;side++)for(var q:model.getQuads(null,side==6?null:Direction.values()[side],RandomSource.create(0),ModelData.EMPTY,null)) {
            check(!q.getSprite().contents().name().getPath().equals("missingno"),"Missing material "+id);
            var v=q.getVertices();int stride=v.length/4;
            for(int i=0;i<4;i++)result.add(new Vec3(Float.intBitsToFloat(v[i*stride]),Float.intBitsToFloat(v[i*stride+1]),Float.intBitsToFloat(v[i*stride+2])));
        }
        check(!result.isEmpty(),"Empty geometry "+id);return result;
    }
    private static void geometryChecks(Minecraft mc,ReactorControllerBlockEntity be) {
        var e=be.clientVesselEnvelope();var c=be.clientCoreAppearance();var core=ReactorCoreGeometry.layout(e,c);
        check(Math.abs(core.top()-core.bottom()-9)<1e-6,"Active fuel not capped at 9 blocks");
        check(Math.abs(VesselWaterGeometry.height(e,dev.bwr.core.PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN)-core.top())<1e-6,"Water TAF does not match fuel");
        for(double y:new double[]{.5,2,7,11,18,23})check(Math.abs(VesselWaterGeometry.height(e,VesselWaterGeometry.levelAtHeight(e,y))-y)<1e-6,"Water coordinate roundtrip failed");
        var jets=VesselJetGeometry.layout(e,c);
        for(var m:jets)for(var part:VesselJetGeometry.PARTS)for(var v:vertices(mc,VesselJetGeometry.model(part))) {
            double y=switch(part){case "straight"->1.6+v.y*m.straightLength();case "upper"->1.6+m.straightLength()+v.y;case "brace"->1.6+m.straightLength()*.42+v.y;default->v.y;};
            double x=m.x()+m.scale()*(v.x*Math.cos(m.yaw())+v.z*Math.sin(m.yaw()));
            double z=m.z()+m.scale()*(-v.x*Math.sin(m.yaw())+v.z*Math.cos(m.yaw()));
            double r=Math.hypot(x/e.width(),z/e.depth());
            check(r>.375&&r<.448,"Actual jet vertex crossed shroud/vessel: "+part+" radius="+r);
            check(m.bottom()+y*m.scale()<core.top()+.01,"Jet extends above modeled fuel");
        }
        var drives=VesselDriveGeometry.layout(e,c);var points=vertices(mc,VesselDriveGeometry.MODEL);
        for(var m:drives)for(var v:points) {
            double x=m.x()+(v.x-.5)*m.scale(),z=m.z()+(v.z-.5)*m.scale();
            check(Math.hypot(x/e.width(),z/e.depth())<.375,"Actual drive housing outside circular shroud footprint");
            check(v.y>=-.001&&v.y<=1.001,"Drive exceeds one-block height");
        }
        for(int i=0;i<drives.size();i++)for(int j=i+1;j<drives.size();j++) {
            var a=drives.get(i);var b=drives.get(j);check(Math.hypot(a.x()-b.x(),a.z()-b.z())>=a.scale()*.99,"Drive housings overlap");
        }
        for(String part:ReactorVesselRenderer.PARTS)vertices(mc,ReactorVesselRenderer.model(part));
    }
    private static void exportLayout(Minecraft mc,ReactorControllerBlockEntity be)throws Exception {
        var e=be.clientVesselEnvelope();var c=be.clientCoreAppearance();var l=ReactorCoreGeometry.layout(e,c);
        var data=new LinkedHashMap<String,Object>();data.put("width",e.width());data.put("depth",e.depth());data.put("height",e.height());
        data.put("core",l);data.put("drives",VesselDriveGeometry.layout(e,c));
        data.put("jets",VesselJetGeometry.layout(e,c).stream().map(m->Map.of("x",m.x(),"z",m.z(),"yaw",m.yaw(),"scale",m.scale(),"straight",m.straightLength(),"bottom",m.bottom(),"top",m.top())).toList());
        data.put("fuel",c.cells().stream().map(cell->Map.of("x",l.x(cell.slot()),"z",l.z(cell.slot()))).toList());
        var dir=mc.gameDirectory.toPath().resolve("reactor-fit-check");Files.createDirectories(dir);
        Files.writeString(dir.resolve("layout.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data));
    }
}
