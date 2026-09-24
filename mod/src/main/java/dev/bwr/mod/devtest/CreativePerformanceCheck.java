package dev.bwr.mod.devtest;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
/** Real creative tab benchmark. Inert unless requested; excluded from released artifacts. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class CreativePerformanceCheck {
    private static boolean started,requested;private static int age,joined,stage,frames;
    private static long began,renderStart,renderNanos;
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) throws Exception {
        if(!Boolean.getBoolean("bwr.creativePerformanceCheck"))return;var mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Creative benchmark timeout "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){started=true;
            mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
            mc.options.guiScale().set(2);mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);
            mc.createWorldOpenFlows().createFreshLevel("bwr-creative-benchmark-"+System.currentTimeMillis(),
                new LevelSettings("Creative benchmark",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||++joined<120)return;
        if(!requested){requested=true;began=System.nanoTime();frames=0;renderNanos=0;
            mc.setScreen(null);
            if(stage>0){var screen=new CreativeModeInventoryScreen(mc.player,mc.level.enabledFeatures(),true);mc.setScreen(screen);
                var select=CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab",CreativeModeTab.class);select.setAccessible(true);
                select.invoke(screen,stage==1?CreativeModeTabs.getDefaultTab():stage==2?BwrItems.TAB.get():BwrItems.FUEL_TAB.get());
                if(stage==3)FuelCatalogueClientCheck.run(mc);
                if(stage==2)for(var item:BwrItems.TAB.get().getDisplayItems()){
                    var model=mc.getItemRenderer().getModel(item,mc.level,mc.player,0).applyTransform(net.minecraft.world.item.ItemDisplayContext.GUI,new com.mojang.blaze3d.vertex.PoseStack(),false);
                    int quads=model.getQuads(null,null,net.minecraft.util.RandomSource.create(0)).size();
                    for(var side:net.minecraft.core.Direction.values())quads+=model.getQuads(null,side,net.minecraft.util.RandomSource.create(0)).size();
                    LogUtils.getLogger().info("CREATIVE ITEM {} quads={}",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()),quads);
                }
            }
        }
        if(System.nanoTime()-began>12_000_000_000L){double seconds=(System.nanoTime()-began)/1e9-3;
            LogUtils.getLogger().info("CREATIVE PERFORMANCE stage={} fps={} screenCPUms={}",stage,frames/seconds,frames==0?0:renderNanos/1e6/frames);
            var dir=mc.gameDirectory.toPath().resolve("creative-performance");java.nio.file.Files.createDirectories(dir);
            try(var shot=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve("stage-"+stage+".png"));}
            requested=false;if(++stage==4){LogUtils.getLogger().info("CREATIVE PERFORMANCE COMPLETE");mc.stop();}
        }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post e){if(Boolean.getBoolean("bwr.creativePerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L)frames++;}
    @SubscribeEvent public static void before(ScreenEvent.Render.Pre e){if(Boolean.getBoolean("bwr.creativePerformanceCheck"))renderStart=System.nanoTime();}
    @SubscribeEvent public static void after(ScreenEvent.Render.Post e){if(Boolean.getBoolean("bwr.creativePerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L)renderNanos+=System.nanoTime()-renderStart;}
}
