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
    private static final String[] NAMES={"rhr-panel","hpci-panel","recirculation-panel","condensate-tank-panel","reactor-info","dvss-in-world","jet-pair-in-vessel",
            "modern-lpcs","modern-hpcs","modern-rhr","modern-motor-feed","modern-turbine-feed","pipe-colors"};
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
                if(selected>=7 && selected<=11) {
                    var pump=new PumpAssemblyBlock[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get(),BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get()}[selected-7];
                    var at=new net.minecraft.core.BlockPos(220+(selected-7)*18,120,160);
                    for(var p:net.minecraft.core.BlockPos.betweenClosed(at.offset(-7,-1,-5),at.offset(7,-1,5)))level.setBlock(p,net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    var state=pump.placementState();level.setBlock(at,state,3);pump.setPlacedBy(level,at,state,null,new net.minecraft.world.item.ItemStack(pump));
                    for(var port:pump.ports(state)) {
                        var atPort=pump.portPosition(at,state,port.role());var face=pump.portFace(state,port.role());
                        for(int i=1;i<=3;i++) {
                            var p=atPort.relative(face,i);
                            var pipe=port.role().isSteam()?BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(level,p):BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level,p);
                            if(port.role()==AssemblyPort.WATER_DISCHARGE)pipe=pipe.setValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT,dev.bwr.mod.piping.PipePaint.RED);
                            level.setBlock(p,pipe,3);
                        }
                    }
                    player.connection.teleport(at.getX()-8,126,150,-39,20);return;
                }
                if(selected==12) {
                    var at=new net.minecraft.core.BlockPos(325,120,160);
                    for(var p:net.minecraft.core.BlockPos.betweenClosed(at.offset(-2,-1,-2),at.offset(12,-1,12)))level.setBlock(p,net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    int[] masks={12,36,44,63};
                    for(int i=0;i<16;i++) {
                        var block=(i%2==0?BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get():BwrBlocks.PRESSURISED_TUBE.get());
                        var state=block.defaultBlockState().setValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT,dev.bwr.mod.piping.PipePaint.of(net.minecraft.world.item.DyeColor.byId(i)));
                        var directions=net.minecraft.core.Direction.values();
                        for(int d=0;d<6;d++)state=state.setValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(directions[d]),(masks[i%4]&(1<<d))!=0);
                        level.setBlock(at.offset((i%4)*3,0,(i/4)*3),state,18);
                    }
                    player.connection.teleport(321,131,149,-28,35);return;
                }
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
        boolean ready=stage>=7?mc.screen==null && Math.abs(mc.player.getX()-(stage==12?321:212+(stage-7)*18))<1
                :stage==6?mc.screen==null && mc.player.getX()<170 && mc.player.getY()>199:stage==5?mc.screen==null && mc.player.getX()>180:stage==4?mc.screen instanceof ReactorPanelScreen && mc.player.containerMenu instanceof ReactorPanelMenu reactorMenu && reactorMenu.formed && reactorMenu.map!=null
                :stage==3?mc.screen instanceof CondensateTankScreen && mc.player.containerMenu instanceof CondensateTankMenu tankMenu && tankMenu.stored==800000
                :mc.screen instanceof PumpControlScreen && mc.player.containerMenu instanceof PumpControlMenu m && m.present && Math.abs(m.target-.5)<1e-9;
        if(ready && stage==4 && shown==0) {
            mc.screen.mouseClicked((mc.getWindow().getGuiScaledWidth()-256)/2+120,(mc.getWindow().getGuiScaledHeight()-230)/2+20,0);
        }
        if(ready && stage==4)mc.getToasts().clear();
        if(ready && stage>=5)mc.options.hideGui=true;
        if(ready && stage>=7)mc.options.fov().set(48);
        if(!ready || ++shown<20)return;
        try {
            var directory=mc.gameDirectory.toPath().resolve("panel-check");java.nio.file.Files.createDirectories(directory);
            try(var screenshot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){screenshot.writeToFile(directory.resolve(NAMES[stage]+".png"));}
            LogUtils.getLogger().info("PUMP PANEL CLIENT CHECK: rendered and synchronized {}",NAMES[stage]);
        } catch(java.io.IOException e){throw new IllegalStateException("Could not save panel check",e);}
        mc.player.closeContainer();requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("PUMP PANEL CLIENT CHECK PASS: five screens, DVSS, jets, five modern pumps and all sixteen pipe colors");mc.options.hideGui=false;mc.stop();}
    }
}
