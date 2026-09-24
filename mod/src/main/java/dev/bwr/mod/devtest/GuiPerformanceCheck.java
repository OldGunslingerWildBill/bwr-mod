package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.BwrScreen;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Repeatable, uncapped client comparison. Disposable save; never shipped. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class GuiPerformanceCheck {
    private static boolean started,requested;
    private static int age,joined,stage,frames;
    private static long began,renderStart,renderNanos;
    private static final BlockPos P=new BlockPos(8,100,8);
    private static final BlockPos CORE=new BlockPos(145,180,145);
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bwr.guiPerformanceCheck"))return;
        var mc=Minecraft.getInstance();
        if(++age>5000)throw new IllegalStateException("GUI benchmark timeout "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);mc.options.guiScale().set(2);
            mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);
            mc.createWorldOpenFlows().createFreshLevel("bwr-gui-benchmark-"+System.currentTimeMillis(),
                new LevelSettings("GUI benchmark",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<150)return;
        if(!requested) {
            requested=true;began=System.nanoTime();frames=0;renderNanos=0;
            var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                player.closeContainer();player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                player.connection.teleport(8,102,4,0,0);l.setDayTime(6000);
                if(selected==0)l.setBlock(P,BwrBlocks.RHR_PUMP.get().defaultBlockState(),3);
                if(selected==1||selected==3)PumpControlMenu.open(player,P,P);
                if(selected>=4) {
                    int width=selected<6?15:21;var root=CORE.offset(-1,3,width/2);
                    player.connection.teleport(root.getX()-2,root.getY(),root.getZ(),-90,0);
                    if(selected==4||selected==6)try {
                        var reactor=CompactCoreRegressionTests.build(l,CORE,width,width,false);
                        for(int slot:reactor.corePositions())reactor.loadAssembly(slot,new net.minecraft.world.item.ItemStack(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get()));
                        ReactorPanelMenu.open(player,reactor);
                    }catch(Exception ex){throw new IllegalStateException(ex);}
                }
            });
        }
        if((stage==1||stage==3)&&!(mc.screen instanceof BwrScreen<?>))return;
        if((stage==4||stage==6)&&(!(mc.player.containerMenu instanceof ReactorPanelMenu m)||m.map==null||!m.formed))return;
        if(System.nanoTime()-began>12_000_000_000L) {
            double seconds=(System.nanoTime()-began)/1e9-3;
            LogUtils.getLogger().info("GUI PERFORMANCE stage={} screen={} fps={} screenCPUms={} windowActive={}",stage,
                mc.screen==null?"world":mc.screen.getClass().getSimpleName(),frames/seconds,frames==0?0:renderNanos/1e6/frames,mc.isWindowActive());
            if(mc.screen!=null)try{
                var dir=mc.gameDirectory.toPath().resolve("gui-performance");java.nio.file.Files.createDirectories(dir);
                try(var shot=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve("stage-"+stage+".png"));}
            }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
            requested=false;
            if(++stage==8){LogUtils.getLogger().info("GUI PERFORMANCE COMPLETE");mc.stop();}
        }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if(Boolean.getBoolean("bwr.guiPerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L)frames++;
    }
    @SubscribeEvent public static void before(ScreenEvent.Render.Pre event) { if(Boolean.getBoolean("bwr.guiPerformanceCheck"))renderStart=System.nanoTime(); }
    @SubscribeEvent public static void after(ScreenEvent.Render.Post event) {
        if(Boolean.getBoolean("bwr.guiPerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L)renderNanos+=System.nanoTime()-renderStart;
    }
}
