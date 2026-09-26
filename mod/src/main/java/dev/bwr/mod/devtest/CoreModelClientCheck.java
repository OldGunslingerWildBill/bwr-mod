package dev.bwr.mod.devtest;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.reactor.client.*;
import dev.bwr.mod.registry.*;
import net.minecraft.client.*;
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

/** Real client screenshots, loading changes and GPU cache stability. Never player saves. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class CoreModelClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160);
    private static final String[] NAMES={"empty-core","loaded-764-core","removed-bundles","minimum-core","maximum-core","rectangular-core","legacy-core","closed-vessel","unformed-vessel"};
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static long stableUploads;
    private static int w(){return stage==3?5:stage==4?21:stage==5?7:15;}
    private static int d(){return stage==5?11:w();}
    private static int h(){return stage==3?8:stage==4?22:stage==5?14:20;}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.coreModelCheck"))return;var mc=Minecraft.getInstance();
        if(++age>6500)throw new IllegalStateException("Core model timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-core-model-"+System.currentTimeMillis(),new LevelSettings("Core model check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        int w=w(),d=d(),h=h();var root=MIN.offset(-1,3,d/2);
        double cx=MIN.getX()+w/2.0,cz=MIN.getZ()+d/2.0;
        var camera=stage<7?new Vec3(cx-w*.26,MIN.getY()+h+w*.85,cz-d*.50):new Vec3(cx-w*1.75,MIN.getY()+h*.8,cz-d*1.75);
        var target=new Vec3(cx,MIN.getY()+h*(stage<7?.6:.43),cz);var delta=target.subtract(camera);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)l.setChunkForced(x,z,true);
                try {
                    if(selected==0||selected>=3&&selected<=7) {
                        for(var p:BlockPos.betweenClosed(MIN.offset(-3,-3,-3),MIN.offset(24,25,24)))l.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                        var be=CompactCoreRegressionTests.build(l,MIN,w,d,h,selected==6);
                        SpargerRegressionTests.rings(l,be);SpargerRegressionTests.validate(be);
                        be.setVesselState(selected==7?VesselState.SHUTDOWN:VesselState.REFUELING);
                        if(selected!=0)for(int slot:be.corePositions())be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                    } else if(l.getBlockEntity(root) instanceof ReactorControllerBlockEntity be) {
                        if(selected==1)for(int slot:be.corePositions())be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                        if(selected==2)for(int i=0;i<40;i++)be.unloadAssembly(be.corePositions()[i]);
                        if(selected==8){l.removeBlock(MIN.west().above(),false);SpargerRegressionTests.validate(be);}
                    }
                }catch(Exception ex){throw new IllegalStateException(ex);}
                l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);
            });
        }
        if(++shown<130)return;
        var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(root);var core=be.clientCoreAppearance();var e=be.clientVesselEnvelope();
        if(stage==8){if(core!=null||e!=null)throw new AssertionError("Stale unformed core");}
        else {
            if(core==null||e==null)throw new AssertionError("No client core stage "+stage);
            int expected=stage==6?225:new dev.bwr.core.fuel.CompactCoreLayout(w,d).assemblyCount();
            if(core.cells().size()!=expected)throw new AssertionError("Wrong visible core capacity");
            long loaded=core.cells().stream().filter(c->c.content()!=0).count();
            if(loaded!=(stage==0?0:stage==2?expected-40:expected))throw new AssertionError("Wrong loaded count "+loaded);
            var shape=ReactorCoreGeometry.layout(e,core);
            if(stage==6)for(var drive:core.drives()) {
                double gx=ReactorCoreGeometry.driveX(e,core,shape,drive)/shape.pitchX()+core.latticeWidth()/2.0;
                double gz=ReactorCoreGeometry.driveZ(e,core,shape,drive)/shape.pitchZ()+core.latticeWidth()/2.0;
                if(Math.abs(gx-(drive.x()+(31-w)/2))>1e-5||Math.abs(gz-(drive.z()+(31-d)/2))>1e-5)
                    throw new AssertionError("Legacy blade not aligned with fuel-cell boundary");
            }
            for(var cell:core.cells()) {
                double radius=Math.hypot((Math.abs(shape.x(cell.slot()))+shape.pitchX()*.5)/e.width(),(Math.abs(shape.z(cell.slot()))+shape.pitchZ()*.5)/e.depth());
                if(radius>.363)throw new AssertionError("Core clips shroud at "+cell.slot());
            }
        }
        for(String part:ReactorCoreGeometry.PARTS) {
            var baked=mc.getModelManager().getModel(ReactorCoreGeometry.model(part));
            var faces=baked.getQuads(null,null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);
            if(baked==mc.getModelManager().getMissingModel()||faces.isEmpty()||faces.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing core material "+part);
        }
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=true;mc.getToasts().clear();
        if(shown==140)stableUploads=MachineMeshCache.uploadCount();if(shown<170)return;
        if(MachineMeshCache.uploadCount()!=stableUploads)throw new AssertionError("Stationary core reuploads each frame");
        var dir=mc.gameDirectory.toPath().resolve("core-model-check");java.nio.file.Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("CORE MODEL CHECK: {} uploads={} cacheBytes={}",NAMES[stage],stableUploads,MachineMeshCache.cachedBytes());requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("CORE MODEL CHECK PASS: four sizes, legacy, inventory, shroud clearance, materials, GPU cache and unforming");mc.stop();}
    }
}
