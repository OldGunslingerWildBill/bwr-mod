package dev.bwr.mod.devtest;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.condenser.client.CondenserRenderer;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.cooling.client.CoolingRenderer;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.client.CondensateTankRenderer;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.registry.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.concurrent.CompletableFuture;

/** Actual client comparison of identical geometry through cached and ordinary CPU vertex paths.
 * The entire fixture and its renderer wrappers are excluded from the distributable JAR. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class MachineRenderCheck {
    private static final BlockPos NATURAL=new BlockPos(140,200,140),MECHANICAL=new BlockPos(174,200,140);
    private static final BlockPos CORE=new BlockPos(142,200,170),CONDENSER=new BlockPos(176,200,175),TANK=new BlockPos(195,200,164);
    private static final String[] NAMES={"gpu-day","cpu-day","gpu-repeat","offscreen","top-only","gpu-night","resource-reload"};
    private static boolean started,requested,fixture,measure;
    private static volatile boolean built;
    private static int age,joined,stage,frames,frameCalls;
    private static long began,frameCpu,totalCpu,totalCalls,stableUploads,stableDraws,reloadUploads;
    private static CompletableFuture<Void> reload;
    private static boolean enabled(){return Boolean.getBoolean("bwr.machineRenderCheck");}

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void renderers(EntityRenderersEvent.RegisterRenderers event){
        if(!enabled())return;
        event.registerBlockEntityRenderer(BwrBlockEntities.COOLING.get(),c->new Measured<>(new CoolingRenderer(c)));
        event.registerBlockEntityRenderer(BwrBlockEntities.CONDENSER.get(),c->new Measured<>(new CondenserRenderer(c)));
        event.registerBlockEntityRenderer(BwrBlockEntities.REACTOR_CONTROLLER.get(),c->new Measured<>(new ReactorVesselRenderer(c)));
        event.registerBlockEntityRenderer(BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(),c->new Measured<>(new CondensateTankRenderer(c)));
    }
    private record Measured<T extends BlockEntity>(BlockEntityRenderer<T> delegate) implements BlockEntityRenderer<T>{
        @Override public void render(T be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
            long start=System.nanoTime();
            // A wrapper selects the existing special-consumer fallback; no production debug switch.
            delegate.render(be,partial,pose,stage==1?buffers::getBuffer:buffers,light,overlay);
            frameCpu+=System.nanoTime()-start;frameCalls++;
        }
        @Override public AABB getRenderBoundingBox(T be){return delegate.getRenderBoundingBox(be);}
        @Override public int getViewDistance(){return delegate.getViewDistance();}
        @Override public boolean shouldRenderOffScreen(T be){return delegate.shouldRenderOffScreen(be);}
        @Override public boolean shouldRender(T be,Vec3 camera){return delegate.shouldRender(be,camera);}
    }
    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(enabled()&&built&&event.getServer().overworld().getBlockEntity(MECHANICAL) instanceof CoolingBlockEntity be){
            be.power().receiveEnergy(Integer.MAX_VALUE,false);be.plant().fillInput(be.design().flow/20,false);be.plant().drain(be.design().flow/20,false);
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!enabled())return;var mc=Minecraft.getInstance();
        if(++age>6000)throw new AssertionError("Machine render check timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){
            started=true;mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
            mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.particles().set(ParticleStatus.MINIMAL);mc.options.fov().set(60);
            mc.options.renderDistance().set(12);mc.options.simulationDistance().set(12);
            mc.createWorldOpenFlows().createFreshLevel("bwr-mesh-check-"+System.currentTimeMillis(),new LevelSettings("Machine meshes",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        if(!fixture){
            fixture=true;var id=mc.player.getUUID();var server=mc.getSingleplayerServer();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(id);var l=player.serverLevel();
                player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(236,245,84,40,14);
                for(int x=7;x<=16;x++)for(int z=7;z<=13;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                for(var p:BlockPos.betweenClosed(new BlockPos(125,199,125),new BlockPos(210,199,193)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),2);
                CoolingRuntimeCheck.place(l,Design.NATURAL,NATURAL,Direction.NORTH);
                var fan=CoolingRuntimeCheck.place(l,Design.MECHANICAL,MECHANICAL,Direction.NORTH);fan.setTarget(.5);fan.power().receiveEnergy(Integer.MAX_VALUE,false);
                CoolingRuntimeCheck.place(l,Design.CIRCULATING,new BlockPos(198,200,140),Direction.EAST);
                try{if(!CompactCoreRegressionTests.build(l,CORE,15,15,20,false).isFormed())throw new AssertionError("Fixture reactor unformed");}catch(Exception ex){throw new IllegalStateException(ex);}
                var cb=BwrBlocks.ARABELLE_CONDENSER.get();var cs=cb.defaultBlockState().setValue(CondenserBlock.FACING,Direction.EAST);
                l.setBlock(CONDENSER,cs,3);cb.setPlacedBy(l,CONDENSER,cs,null,new ItemStack(cb));
                var lp=BwrBlocks.LP_TURBINE.get();var ls=lp.placementState().setValue(PumpAssemblyBlock.FACING,Direction.NORTH);
                l.setBlock(CONDENSER.above(6),ls,3);lp.setPlacedBy(l,CONDENSER.above(6),ls,null,new ItemStack(lp));
                CondensateTankRuntimeCheck.buildShell(l,player,TANK,7,8);built=true;
            });return;
        }
        if(!built||mc.screen!=null||mc.getOverlay()!=null||!(mc.level.getBlockEntity(NATURAL) instanceof CoolingBlockEntity))return;
        if(reload!=null){if(!reload.isDone())return;reload.join();reload=null;began=System.nanoTime();}
        if(!requested){
            requested=true;began=System.nanoTime();frames=0;totalCpu=0;totalCalls=0;measure=false;
            var server=mc.getSingleplayerServer();int selected=stage;server.execute(()->server.overworld().setDayTime(selected==5?18000:6000));
            if(stage==6){reloadUploads=MachineMeshCache.uploadCount();reload=mc.reloadResourcePacks();return;}
        }
        boolean top=stage==4;mc.options.fov().set(top?35:60);mc.options.hideGui=true;mc.getToasts().clear();
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(top?140:236,top?233:245,top?105:84);mc.player.setYRot(top?0:stage==3?220:40);mc.player.setXRot(top?0:stage==3?0:14);
        if(System.nanoTime()-began<8_000_000_000L)return;
        if(frames<20)throw new AssertionError("No measured frames at "+stage);
        if(stage==3){if(totalCalls!=0)throw new AssertionError("Offscreen models still submitted: "+totalCalls);}
        else if(totalCalls==0)throw new AssertionError("Visible machines not rendered at "+stage);
        if(stage!=1&&MachineMeshCache.uploadCount()!=stableUploads)throw new AssertionError("Steady geometry rebuilt during stage "+stage);
        if(stage!=1&&stage!=3&&MachineMeshCache.drawCount()<=stableDraws)throw new AssertionError("GPU path not active");
        if(stage==6&&MachineMeshCache.uploadCount()<=reloadUploads)throw new AssertionError("Resource reload did not rebuild GPU meshes");
        LogUtils.getLogger().info("MACHINE RENDER {} frames={} fps={} rendererCPUms={} calls/frame={} meshes={} bytes={} uploads={}",NAMES[stage],frames,frames/((System.nanoTime()-began)/1e9-2),totalCpu/1e6/frames,(double)totalCalls/frames,MachineMeshCache.cachedMeshes(),MachineMeshCache.cachedBytes(),MachineMeshCache.uploadCount());
        try{var dir=mc.gameDirectory.toPath().resolve("machine-render-check");java.nio.file.Files.createDirectories(dir);try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}}catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        requested=false;
        if(++stage==NAMES.length){MachineMeshCache.clear();if(MachineMeshCache.cachedBytes()!=0||MachineMeshCache.cachedMeshes()!=0)throw new AssertionError("GPU cache did not close");LogUtils.getLogger().info("MACHINE RENDER CHECK PASS: cached/CPU comparison, steady uploads, full bounds, offscreen rejection, night, reload and cleanup");mc.stop();}
    }
    @SubscribeEvent public static void pre(RenderFrameEvent.Pre event){if(enabled()){frameCpu=0;frameCalls=0;}}
    @SubscribeEvent public static void post(RenderFrameEvent.Post event){
        if(!enabled()||!requested||reload!=null||System.nanoTime()-began<2_000_000_000L)return;
        if(!measure){measure=true;stableUploads=MachineMeshCache.uploadCount();stableDraws=MachineMeshCache.drawCount();}
        frames++;totalCpu+=frameCpu;totalCalls+=frameCalls;
    }
    @SubscribeEvent public static void visibility(RenderLevelStageEvent event){
        if(!enabled()||!requested||stage!=4||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||System.nanoTime()-began<2_000_000_000L)return;
        if(event.getFrustum().isVisible(new AABB(NATURAL)))throw new AssertionError("Top-only camera still sees controller");
        var be=(CoolingBlockEntity)Minecraft.getInstance().level.getBlockEntity(NATURAL);
        if(!event.getFrustum().isVisible(be.layout().bounds(NATURAL,Direction.NORTH))||frameCalls==0)throw new AssertionError("Tower top disappeared with controller out of view");
    }
}
