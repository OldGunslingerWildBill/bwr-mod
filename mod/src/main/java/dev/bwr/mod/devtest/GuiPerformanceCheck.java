package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.BwrScreen;
import dev.bwr.mod.gui.client.style.Ui;
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
    /** Per-frame screen CPU, so the log can give a mean and a 95th percentile, not only a mean. */
    private static long[] samples=new long[1<<16],sampleTimes=new long[1<<16];private static int sampleCount;
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
        if(!requested&&!stageWanted(stage)) {
            // -Dbwr.guiPerformanceStages=4,6 runs only those stages (the numbering is unchanged).
            if(++stage==8)finish(mc);
            return;
        }
        if(!requested) {
            requested=true;began=System.nanoTime();frames=0;renderNanos=0;sampleCount=0;
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
        // Closing a creative player's server menu can reopen the creative inventory locally.
        // The two world baselines must measure the world, not an unrelated inventory screen.
        if ((stage == 5 || stage == 7) && mc.screen != null) mc.setScreen(null);
        if((stage==1||stage==3)&&!(mc.screen instanceof BwrScreen<?>))return;
        if((stage==4||stage==6)&&(!(mc.player.containerMenu instanceof ReactorPanelMenu m)||m.map==null||!m.formed))return;
        if(pixels()) {
            if((stage==4||stage==6)&&mc.screen instanceof dev.bwr.mod.gui.client.ReactorPanelScreen screen&&mc.player.containerMenu instanceof ReactorPanelMenu menu)
                pixelTick(mc,screen,menu);
            else if(stage!=4&&stage!=6) { requested=false; if(++stage==8)finish(mc); }
            return;
        }
        if(System.nanoTime()-began>12_000_000_000L) {
            if ((stage == 5 || stage == 7) && mc.screen != null)
                throw new AssertionError("GUI world baseline still has a screen at stage " + stage);
            double seconds=(System.nanoTime()-began)/1e9-3;
            LogUtils.getLogger().info("GUI PERFORMANCE stage={} screen={} fps={} screenCPUms={} windowActive={}",stage,
                mc.screen==null?"world":mc.screen.getClass().getSimpleName(),frames/seconds,frames==0?0:renderNanos/1e6/frames,mc.isWindowActive());
            if(sampleCount>0&&mc.screen instanceof dev.bwr.mod.gui.client.ReactorPanelScreen)checkBudget(mc);
            if(sampleCount>0) {
                var sorted=java.util.Arrays.copyOf(samples,sampleCount);java.util.Arrays.sort(sorted);
                LogUtils.getLogger().info("GUI PERFORMANCE STATS stage={} screen={} frames={} meanMs={} medianMs={} p95Ms={} maxMs={}",stage,
                    mc.screen==null?"world":mc.screen.getClass().getSimpleName(),sampleCount,renderNanos/1e6/sampleCount,
                    sorted[sampleCount/2]/1e6,sorted[Math.min(sampleCount-1,(int)Math.ceil(sampleCount*0.95)-1)]/1e6,sorted[sampleCount-1]/1e6);
            }
            if(mc.screen!=null)try{
                var dir=mc.gameDirectory.toPath().resolve("gui-performance");java.nio.file.Files.createDirectories(dir);
                CheckShots.save(mc,dir.resolve("stage-"+stage+".png"));
            }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
            requested=false;
            if(++stage==8)finish(mc);
        }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if(Boolean.getBoolean("bwr.guiPerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L)frames++;
    }
    @SubscribeEvent public static void before(ScreenEvent.Render.Pre event) { if(Boolean.getBoolean("bwr.guiPerformanceCheck"))renderStart=System.nanoTime(); }
    @SubscribeEvent public static void after(ScreenEvent.Render.Post event) {
        if(Boolean.getBoolean("bwr.guiPerformanceCheck")&&requested&&System.nanoTime()-began>3_000_000_000L) {
            long took=System.nanoTime()-renderStart;renderNanos+=took;
            if(sampleCount<samples.length){sampleTimes[sampleCount]=System.nanoTime();samples[sampleCount++]=took;}
        }
    }
    /**
     * The reactor panel must stay cheap. A frame costs about a quarter of a millisecond now and more than a millisecond and
     * a half when it is drawn one draw call at a time (about two before its text was cached), so the budget (default
     * 0.8 ms, -Dbwr.guiPerformanceBudgetMs) sits between the two, and it is applied to the best of three 3-second windows of
     * the run, so that another program using the machine for a stretch of the run cannot fail it.
     */
    private static void checkBudget(Minecraft mc) {
        double budget=Double.parseDouble(System.getProperty("bwr.guiPerformanceBudgetMs","0.8"));
        long start=began+3_000_000_000L;double best=Double.MAX_VALUE;StringBuilder windows=new StringBuilder();
        for(int w=0;w<3;w++) {
            var part=new long[sampleCount];int n=0;
            for(int i=0;i<sampleCount;i++)if((sampleTimes[i]-start)/3_000_000_000L==w)part[n++]=samples[i];
            if(n==0)continue;
            java.util.Arrays.sort(part,0,n);double median=part[n/2]/1e6;best=Math.min(best,median);
            windows.append(String.format(java.util.Locale.ROOT," %.3f",median));
        }
        LogUtils.getLogger().info("GUI PERFORMANCE WINDOWS stage={} medianMsPerWindow={} budgetMs={}",stage,windows,budget);
        if(best>budget)throw new AssertionError("GUI PERFORMANCE BUDGET: the reactor panel took "+best+" ms per frame in its best 3-second window at stage "+stage+", over the budget of "+budget+" ms");
    }
    private static boolean stageWanted(int stage) {
        String list=System.getProperty("bwr.guiPerformanceStages");
        if(list==null||list.isBlank())return true;
        for(String part:list.split(","))if(part.trim().equals(Integer.toString(stage)))return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Pixel mode (-Dbwr.guiPerformancePixels=true): a deterministic set of 1920 x 1080 pictures of the reactor panel
    // (stages 4 and 6, the 15 x 15 and 21 x 21 cores), for comparing the panel pixel by pixel before and after a
    // drawing change. The menu's numbers are frozen so that the plant running behind it cannot change a digit, and the
    // mouse is placed by hand so that hover, tooltip and focus states are the same every run.
    // ------------------------------------------------------------------
    private static void finish(Minecraft mc) {
        if(pixMismatch>0)throw new AssertionError("GUI PIXELS: "+pixMismatch+" pixels of the reactor panel differ between the batched and the legacy drawing");
        if(pixels())LogUtils.getLogger().info("GUI PIXELS identical in every scenario");
        LogUtils.getLogger().info("GUI PERFORMANCE COMPLETE");mc.stop();
    }
    private static boolean pixels() { return Boolean.getBoolean("bwr.guiPerformancePixels"); }
    private static int pixStep,pixWait,pixPhase,pixMismatch;
    private static com.mojang.blaze3d.platform.NativeImage pixLive;
    private static ReactorPanelMenu pixMenu;
    private static final java.util.Map<java.lang.reflect.Field,Object> FROZEN=new java.util.LinkedHashMap<>();

    private static void freeze(ReactorPanelMenu menu) {
        FROZEN.clear();
        canonicalise(menu);
        for(var f:ReactorPanelMenu.class.getDeclaredFields()) {
            int m=f.getModifiers();
            if(java.lang.reflect.Modifier.isStatic(m)||java.lang.reflect.Modifier.isFinal(m))continue;
            try{f.setAccessible(true);FROZEN.put(f,f.get(menu));}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
        }
    }
    /**
     * Replaces the live numbers with fixed ones (the same every run, whatever the plant was doing when the menu was
     * frozen), chosen so that some readouts sit in their caution and alarm bands: icons, colours and every text style
     * get drawn.
     */
    private static void canonicalise(ReactorPanelMenu menu) {
        try {
            for(var f:ReactorPanelMenu.class.getDeclaredFields()) {
                int m=f.getModifiers();
                if(java.lang.reflect.Modifier.isStatic(m)||java.lang.reflect.Modifier.isFinal(m))continue;
                f.setAccessible(true);
                if(f.getType()==double.class)f.setDouble(menu,Math.floorMod(f.getName().hashCode(),9000)/7.0+0.5);
            }
            menu.powerFractionOfRated=1.12;menu.pressurePsig=1300.4;menu.peakCladTemperatureC=1104;menu.reactivityDollars=0.37;
            menu.uncoveredFuelFraction=0.08;menu.oxidationFraction=0.02;menu.srmPeriodSeconds=-12.5;menu.irmPeriodSeconds=1.0e5;
            menu.scramActive=true;menu.chargedAccumulators=Math.max(0,menu.rodCount-3);
            for(int i=0;i<menu.rodNotchLabels.length;i++){menu.rodNotchLabels[i]=(i*7)%49;if(i<menu.rodDemandLabels.length)menu.rodDemandLabels[i]=i%5==0?Math.min(48,menu.rodNotchLabels[i]+1):menu.rodNotchLabels[i];}
            var map=menu.map;
            map.peakWeight=0.012;map.coreThermalMW=3100;
            for(int i=0;i<map.coreSlotCount;i++) {
                map.relativeFlux[i]=((i*37)%101)/100.0;map.burnupMwdPerTonne[i]=(i*113)%30000;map.kInf[i]=0.9+((i*53)%35)/100.0;
            }
        } catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    private static void thaw(ReactorPanelMenu menu) {
        if(menu!=pixMenu||FROZEN.isEmpty())return;
        try{for(var e:FROZEN.entrySet())e.getKey().set(menu,e.getValue());}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    /** Puts the mouse at a point given in GUI coordinates of the live window. */
    private static void mouseTo(Minecraft mc,double guiX,double guiY) {
        var w=mc.getWindow();
        setDouble(mc.mouseHandler,"xpos",guiX*w.getScreenWidth()/w.getGuiScaledWidth());
        setDouble(mc.mouseHandler,"ypos",guiY*w.getScreenHeight()/w.getGuiScaledHeight());
    }
    private static void setDouble(Object o,String name,double v) {
        try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.setDouble(o,v);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    private static Object field(Object o,String name) {
        try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    private static void setOverlay(dev.bwr.mod.gui.client.ReactorPanelScreen screen,String name) {
        try {
            Class<?> type=Class.forName("dev.bwr.mod.gui.client.ReactorPanelScreen$Overlay");
            for(Object c:type.getEnumConstants())if(c.toString().equals(name)) {
                var m=screen.getClass().getDeclaredMethod("setOverlay",type);m.setAccessible(true);m.invoke(screen,c);
            }
        } catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    private static net.minecraft.client.gui.components.AbstractWidget widget(dev.bwr.mod.gui.client.ReactorPanelScreen screen,String name) {
        return (net.minecraft.client.gui.components.AbstractWidget)field(screen,name);
    }
    private static void mouseOver(Minecraft mc,net.minecraft.client.gui.components.AbstractWidget b) {
        mouseTo(mc,b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0);
    }

    private static final String[] PIX_NAMES={"flux-nohover","flux-cellhover","flux-centre","rods-selected","info","info-scrolled","flux-tabfocus","flux-buttonhover","flux-pressed"};

    private static void pixelTick(Minecraft mc,dev.bwr.mod.gui.client.ReactorPanelScreen screen,ReactorPanelMenu menu) {
        var grid=(dev.bwr.mod.gui.client.LatticeGridWidget)field(screen,"grid");
        if(pixMenu!=menu) { pixMenu=menu;pixStep=0;pixWait=0;pixPhase=0;FROZEN.clear(); }
        thaw(menu);
        if(pixPhase==1) {
            // the same picture again, drawn the legacy way; the numbers, the mouse and every widget state are as they were
            if(++pixWait<4)return;
            comparePanel(mc,screen,pixStep-1);
            Ui.setLegacyDrawing(false);pixPhase=0;
            if(pixStep-1==8) { var b=widget(screen,"rodOut");screen.mouseReleased(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,0); }
            pixStep++;pixWait=0;
            return;
        }
        if(pixStep==0) {
            if(++pixWait<80)return;
            freeze(menu);pixStep=1;pixWait=0;
        }
        int step=pixStep-1;
        if(step>=PIX_NAMES.length) {
            pixMenu=null;FROZEN.clear();requested=false;
            if(++stage==8)finish(mc);
            return;
        }
        var w=mc.getWindow();
        double midX=w.getGuiScaledWidth()/2.0,midY=w.getGuiScaledHeight()/2.0;
        if(pixWait==0) {
            switch(step) {
                case 0 -> { setOverlay(screen,"FLUX");mouseTo(mc,2,2); }
                case 1 -> { double[] c=grid.cellCentre(menu.map.coreSlotCount/2+3);mouseTo(mc,c[0],c[1]); }
                case 2 -> mouseTo(mc,midX,midY);
                case 3 -> { setOverlay(screen,"RODS");double[] c=grid.cellCentre(10);mouseTo(mc,c[0],c[1]); }
                case 4 -> { setOverlay(screen,"INFO");mouseTo(mc,2,2); }
                case 5 -> { setOverlay(screen,"INFO");mouseTo(mc,midX,midY);screen.mouseScrolled(midX,midY,0,-3); }
                case 6 -> { setOverlay(screen,"FLUX");mouseTo(mc,2,2);screen.setFocused(null);for(int i=0;i<4;i++)screen.keyPressed(258,15,0); }
                case 7 -> { screen.setFocused(null);mouseOver(mc,widget(screen,"rodOut")); }
                case 8 -> mouseOver(mc,widget(screen,"rodOut"));
                default -> {}
            }
        }
        if(step==3&&pixWait==10) {
            // select a rod by clicking it, then rest the pointer on the one beside it
            double[] c=grid.cellCentre(10);screen.mouseClicked(c[0],c[1],0);screen.mouseReleased(c[0],c[1],0);
            double[] d=grid.cellCentre(11);mouseTo(mc,d[0],d[1]);
        }
        if(step==8&&pixWait==10) {
            // hold the left button down on "Pull rod" without releasing it: the pressed look
            var b=widget(screen,"rodOut");screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,0);
        }
        int wait=step==7?45:25;
        if(++pixWait>=wait) {
            try {
                var dir=mc.gameDirectory.toPath().resolve("gui-performance-pixels");java.nio.file.Files.createDirectories(dir);
                pixLive=CheckShots.saveKeeping(mc,dir.resolve("stage-"+stage+"-"+PIX_NAMES[step]+".png"));
            }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
            Ui.setLegacyDrawing(true);pixPhase=1;pixWait=0;
        }
    }
    /** Takes the legacy picture and counts the pixels of the panel that differ from the batched one (the world behind it is not compared: it moves). */
    private static void comparePanel(Minecraft mc,dev.bwr.mod.gui.client.ReactorPanelScreen screen,int step) {
        try(var batched=pixLive;var legacy=CheckShots.saveKeeping(mc,mc.gameDirectory.toPath().resolve("gui-performance-pixels").resolve("stage-"+stage+"-"+PIX_NAMES[step]+"-legacy.png"))) {
            pixLive=null;
            double s=mc.getWindow().getGuiScale();int margin=0;
            int x0=Math.max(0,(int)((screen.getGuiLeft()-margin)*s)),y0=Math.max(0,(int)((screen.getGuiTop()-margin)*s));
            int x1=Math.min(batched.getWidth(),(int)((screen.getGuiLeft()+screen.getXSize()+margin)*s)),y1=Math.min(batched.getHeight(),(int)((screen.getGuiTop()+screen.getYSize()+margin)*s));
            int differing=0,firstX=-1,firstY=-1;
            for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)if(batched.getPixelRGBA(x,y)!=legacy.getPixelRGBA(x,y)){if(differing++==0){firstX=x;firstY=y;}}
            LogUtils.getLogger().info("GUI PIXELS stage={} scenario={} region={}x{} differing={} first=({},{})",stage,PIX_NAMES[step],x1-x0,y1-y0,differing,firstX,firstY);
            pixMismatch+=differing;
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if(Boolean.getBoolean("bwr.guiPerformanceCheck")&&pixels()&&pixMenu!=null)thaw(pixMenu);
    }
}
