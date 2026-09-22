package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Real menu packets and rendered layouts in an automatically closed disposable world. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CompactCoreClientCheck {
    private static final BlockPos MIN=new BlockPos(145,200,145);
    private static boolean started,requested;
    private static int age,joined,stage,shown;
    private static final String[] NAMES={"reference-fuel","reference-drives","reference-refuelling","maximum-fuel","maximum-drives"};
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bwr.compactCorePanelCheck"))return;
        var mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Compact-core client timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;
            mc.createWorldOpenFlows().createFreshLevel("bwr-compact-core-check-"+System.currentTimeMillis(),
                    new LevelSettings("Compact core check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        int width=stage<3?15:21;
        var root=MIN.offset(-1,3,width/2);
        if(!requested) {
            requested=true;shown=0;int current=stage;var uuid=mc.player.getUUID();
            var server=mc.getSingleplayerServer();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var level=player.serverLevel();
                player.closeContainer();player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                player.connection.teleport(root.getX()-2,root.getY(),root.getZ(),-90,0);
                if(current==0||current==3)try {
                    var be=CompactCoreRegressionTests.build(level,MIN,width,width,false);
                    if(!be.isFormed())throw new AssertionError(be.statusLines());
                    for(int slot:be.corePositions())be.loadAssembly(slot,new net.minecraft.world.item.ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                } catch(Exception ex){throw new IllegalStateException(ex);}
                var be=(ReactorControllerBlockEntity)level.getBlockEntity(root);
                if(current==2) {be.setVesselState(VesselState.REFUELING);RefuellingMenu.open(player,be);}
                else ReactorPanelMenu.open(player,be);
            });
        }
        if(stage==2) {
            if(!(mc.player.containerMenu instanceof RefuellingMenu m)||m.map==null||m.map.coreSlotCount!=764)return;
        } else {
            if(!(mc.player.containerMenu instanceof ReactorPanelMenu m)||!m.formed||m.map==null||m.map.coreSlotCount!=(stage<3?764:1476))return;
        }
        shown++;
        if((stage==1||stage==4)&&shown==5) {
            var button=mc.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("RODS")).findFirst().orElseThrow();
            ((Button)button).onPress();
            var menu=(ReactorPanelMenu)mc.player.containerMenu;
            menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH,menu.rodCount-1,28);
        }
        if((stage==1||stage==4)&&shown==70) {
            var menu=(ReactorPanelMenu)mc.player.containerMenu;
            if(menu.rodDemandLabels[menu.rodCount-1]!=28)throw new AssertionError("GUI command did not reach the last physical drive");
        }
        if(shown<90)return;
        mc.getToasts().clear();
        try {
            var dir=mc.gameDirectory.toPath().resolve("compact-core-check");java.nio.file.Files.createDirectories(dir);
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {image.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        } catch(java.io.IOException ex) {throw new IllegalStateException(ex);}
        com.mojang.logging.LogUtils.getLogger().info("COMPACT CORE CLIENT: captured {}",NAMES[stage]);
        requested=false;
        if(++stage==NAMES.length) {com.mojang.logging.LogUtils.getLogger().info("COMPACT CORE CLIENT PASS: reference/max maps, refuelling and last-drive commands");mc.stop();}
    }
}
