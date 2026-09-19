package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in real client/server screen smoke test. Uses a fresh disposable world, never a player's save. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class PumpPanelClientCheck {
    private static boolean started,requested;
    private static int age,stage,shown;
    private static final String[] NAMES={"rhr-panel","hpci-panel","recirculation-panel","condensate-tank-panel","reactor-info","dvss-in-world","jet-pair-in-vessel"};
    /** Use a deterministic hover position while capturing the real INFO screen. */
    @SubscribeEvent public static void render(net.neoforged.neoforge.client.event.ScreenEvent.Render.Pre event) {
        if(Boolean.getBoolean("bwr.pumpPanelCheck") && stage==4 && event.getScreen() instanceof ReactorPanelScreen) {
            event.setCanceled(true);
            event.getScreen().renderWithTooltip(event.getGuiGraphics(),0,0,event.getPartialTick());
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bwr.pumpPanelCheck"))return;
        Minecraft mc=Minecraft.getInstance();
        if(++age>3600)throw new IllegalStateException("Pump panel client check timed out at stage "+stage);
        if(!started && mc.screen!=null && mc.getOverlay()==null) {
            started=true;
            mc.options.pauseOnLostFocus=false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.createWorldOpenFlows().createFreshLevel("bwr-panel-check-"+System.currentTimeMillis(),
                    new LevelSettings("Pump panel check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null)return;
        if(!requested) {
            requested=true;shown=0;
            var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);if(player==null)throw new IllegalStateException("Test player missing");
                var level=player.serverLevel();var pos=player.blockPosition().offset(3,0,0);
                if(selected==4) {
                    player.getAbilities().flying=true;player.onUpdateAbilities();
                    player.teleportTo(172.5,200,133.5);
                    var reactor=PumpAssemblyRuntimeCheck.vessel(level);
                    ReactorPanelMenu.open(player,reactor);return;
                }
                if(selected==5) {
                    var at=new net.minecraft.core.BlockPos(188,120,133);
                    for(var p:net.minecraft.core.BlockPos.betweenClosed(at.offset(-5,-1,-5),at.offset(5,-1,5)))level.setBlock(p,net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    var pump=BwrBlocks.RECIRCULATION_PUMP.get();var state=pump.placementState();
                    level.setBlock(at,state,3);pump.setPlacedBy(level,at,state,null,new net.minecraft.world.item.ItemStack(pump));
                    for(var role:new AssemblyPort[]{AssemblyPort.WATER_SUCTION,AssemblyPort.WATER_DISCHARGE}) {
                        var port=pump.portPosition(at,state,role);var face=pump.portFace(state,role);
                        for(int i=1;i<=2;i++){var p=port.relative(face,i);level.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level,p),3);}
                    }
                    level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                    player.connection.teleport(181.5,124.5,123.5,-35,0);return;
                }
                if(selected==6) {
                    player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,600,0,false,false));
                    var reactor=PumpAssemblyRuntimeCheck.vessel(level);var jet=BwrBlocks.JET_PUMP.get();
                    for(int x:new int[]{159,169}) {
                        var root=new net.minecraft.core.BlockPos(x,195,130);
                        var state=jet.placementState().setValue(PumpAssemblyBlock.FACING,x==159?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.WEST);
                        level.setBlock(root,state,3);jet.setPlacedBy(level,root,state,null,new net.minecraft.world.item.ItemStack(jet));
                    }
                    ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+21);
                    var flow=dev.bwr.mod.flow.RecirculationNetwork.measure(level,reactor,java.util.List.of());
                    if(flow.pairedJets()!=2 || flow.unmatchedJets()!=0)throw new IllegalStateException("Visible opposite-wall jet pair did not match: "+flow);
                    LogUtils.getLogger().info("JET PAIR CLIENT CHECK: matched {}, unmatched {}",flow.pairedJets(),flow.unmatchedJets());
                    player.connection.teleport(164.5,199.9,137,180,30);return;
                }
                var block=switch(selected){case 0->BwrBlocks.RHR_PUMP.get();case 1->BwrBlocks.HPCI_TURBINE_PUMP.get();case 2->BwrBlocks.RECIRCULATION_PUMP.get();default->BwrBlocks.CONDENSATE_STORAGE_TANK.get();};
                level.setBlock(pos,block.defaultBlockState(),3);
                var be=level.getBlockEntity(pos);
                if(be instanceof EccsPumpBlockEntity p){p.setPanelControlled(true);p.setSpeedDemandFraction(.5);p.setSuctionSource(SuctionSource.CONDENSATE_TANK);p.energy().receiveEnergy(Integer.MAX_VALUE,false);}
                if(be instanceof RecirculationPumpBlockEntity p){p.setTargetSpeedFraction(.5);}
                if(be instanceof CondensateStorageTankBlockEntity tank){
                    tank.fillKg(800000);
                    player.openMenu(new SimpleMenuProvider((id,inv,who)->new CondensateTankMenu(id,inv,pos),block.getName()),buf->buf.writeBlockPos(pos));
                }else PumpControlMenu.open(player,pos,pos);
            });
        }
        boolean ready=stage==6?mc.screen==null && mc.player.getX()<170 && mc.player.getY()>199:stage==5?mc.screen==null && mc.player.getX()>180:stage==4?mc.screen instanceof ReactorPanelScreen && mc.player.containerMenu instanceof ReactorPanelMenu reactorMenu && reactorMenu.formed && reactorMenu.map!=null
                :stage==3?mc.screen instanceof CondensateTankScreen && mc.player.containerMenu instanceof CondensateTankMenu tankMenu && tankMenu.stored==800000
                :mc.screen instanceof PumpControlScreen && mc.player.containerMenu instanceof PumpControlMenu m && m.present && Math.abs(m.target-.5)<1e-9;
        if(ready && stage==4 && shown==0) {
            mc.screen.mouseClicked((mc.getWindow().getGuiScaledWidth()-256)/2+120,(mc.getWindow().getGuiScaledHeight()-230)/2+20,0);
        }
        if(ready && stage==4)mc.getToasts().clear();
        if(ready && stage>=5)mc.options.hideGui=true;
        if(!ready || ++shown<20)return;
        try {
            var directory=mc.gameDirectory.toPath().resolve("panel-check");java.nio.file.Files.createDirectories(directory);
            try(var screenshot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){screenshot.writeToFile(directory.resolve(NAMES[stage]+".png"));}
            LogUtils.getLogger().info("PUMP PANEL CLIENT CHECK: rendered and synchronized {}",NAMES[stage]);
        } catch(java.io.IOException e){throw new IllegalStateException("Could not save panel check",e);}
        mc.player.closeContainer();requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("PUMP PANEL CLIENT CHECK PASS: five real screens, DVSS placement and matched one-column jets");mc.options.hideGui=false;mc.stop();}
    }
}
