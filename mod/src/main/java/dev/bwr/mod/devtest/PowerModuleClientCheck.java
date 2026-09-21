package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** Opt-in screenshots and live menu/network checks, using a fresh disposable world. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class PowerModuleClientCheck {
    private static boolean started,requested;private static int age,stage,shown,joined;
    private static List<PowerModuleBlockEntity> modules=List.of();
    private static final String[] NAMES={"power-train","generator-exterior","hp-turbine-panel","lp-turbine-panel","generator-panel","steam-valves","control-valve-panel","stop-valve-panel"};
    private static final BlockPos CONTROL=new BlockPos(178,222,153),STOP=new BlockPos(176,222,153);
    /** Test load takes energy through the actual output capability, like an external grid. */
    @SubscribeEvent public static void load(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.powerPanelCheck"))return;
        for(var m:modules)if(m.block().generator()&&!m.isRemoved()){
            var s=m.getBlockState();var cap=m.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,m.block().terminal(m.getBlockPos(),s),m.block().terminalFace(s));
            if(cap!=null)cap.extractEnergy(Integer.MAX_VALUE,false);
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.powerPanelCheck"))return;Minecraft mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Power module client check timeout, stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(65);
            mc.createWorldOpenFlows().createFreshLevel("bwr-power-check-"+System.currentTimeMillis(),
                new LevelSettings("Modular turbine check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        // Initial login abilities arrive after the player object; staging earlier can
        // have the flight flag overwritten by that packet and drop the camera to ground.
        if(++joined<40)return;
        if(!requested){requested=true;shown=0;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.getAbilities().flying=true;player.setNoGravity(true);player.onUpdateAbilities();
                if(selected==0){
                    for(int x=7;x<=12;x++)for(int z=7;z<=10;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                    for(var p:BlockPos.betweenClosed(new BlockPos(124,214,137),new BlockPos(182,214,150)))l.setBlock(p,net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    modules=PowerModuleRuntimeCheck.makeTrain(l,215,145);
                    var reactor=PumpAssemblyRuntimeCheck.vessel(l);reactor.core().initialiseHotShutdown();
                    var nozzlePos=new BlockPos(164,201,127);l.setBlock(nozzlePos,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
                    var route=List.of(new BlockPos(164,201,126),new BlockPos(185,201,126),new BlockPos(185,222,126),new BlockPos(185,222,153),new BlockPos(130,222,153),new BlockPos(130,220,153),new BlockPos(139,220,153));
                    for(int i=1;i<route.size();i++)PowerModuleRuntimeCheck.pipe(l,route.get(i-1),route.get(i));
                    for(int i=0;i<2;i++){var m=modules.get(i);PowerModuleRuntimeCheck.pipe(l,new BlockPos(m.getBlockPos().getX(),220,153),m.block().portPosition(m.getBlockPos(),m.getBlockState(),AssemblyPort.STEAM_INLET).above());}
                    for(var p:List.of(CONTROL,STOP)){
                        var b=p.equals(CONTROL)?BwrBlocks.TURBINE_CONTROL_VALVE.get():BwrBlocks.STEAM_STOP_VALVE.get();
                        l.setBlock(p,b.defaultBlockState().setValue(dev.bwr.mod.steam.TurbineValveBlock.FACING,Direction.EAST),3);
                        var v=(dev.bwr.mod.steam.TurbineValveBlockEntity)l.getBlockEntity(p);v.setTarget(1);v.stroke(3);
                    }
                    var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(nozzlePos);nozzle.noteController(reactor.getBlockPos());nozzle.setComputerControlled(true);nozzle.setPosition(1);nozzle.refreshAttachment(l);nozzle.refreshLine(l);
                    // LP return lines into finite storage tanks, using actual Mekanism mechanical pipes.
                    for(int i=2;i<6;i++){var m=modules.get(i);var p=m.block().portPosition(m.getBlockPos(),m.getBlockState(),AssemblyPort.WATER_DISCHARGE);
                        var face=m.block().portFace(m.getBlockState(),AssemblyPort.WATER_DISCHARGE);
                        var mek=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:ultimate_mechanical_pipe"));
                        l.setBlock(p.relative(face,3),BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
                        for(int j=1;j<3;j++)l.setBlock(p.relative(face,j),mek.defaultBlockState(),3);
                    }
                    l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);
                    player.connection.teleport(154,233,103,0,18);return;
                }
                player.closeContainer();
                if(selected==1){player.connection.teleport(189,224,133,43,18);return;}
                if(selected==5){player.connection.teleport(180,225,148,26,25);return;}
                if(selected>=6){var p=selected==6?CONTROL:STOP;player.connection.teleport(p.getX()+.5,p.getY()+1,p.getZ()-2,0,15);TurbineValveMenu.open(player,p);return;}
                var m=modules.get(selected==2?0:selected==3?2:6);var p=m.getBlockPos();
                var anchor=m.block().generator()?m.block().terminal(p,m.getBlockState()):m.block().portPosition(p,m.getBlockState(),m.block().highPressure()?AssemblyPort.STEAM_EXHAUST:AssemblyPort.WATER_DISCHARGE);
                player.connection.teleport(anchor.getX()+.5,anchor.getY()+1,anchor.getZ()-3.5,0,12);
                PowerModuleMenu.open(player,p,anchor);
            });
        }
        if((stage<2||stage==5)&&requested&&mc.screen==null){
            // Keep this opt-in photographic camera fixed while distant chunk meshes arrive.
            mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mc.player.setPos(stage==5?180:stage==0?154:189,stage==5?225:stage==0?233:224,stage==5?148:stage==0?103:133);
            mc.player.setYRot(stage==5?26:stage==0?0:43);mc.player.setXRot(stage==5?25:18);
        }
        boolean ready=stage>=6?mc.screen instanceof TurbineValveScreen&&mc.player.containerMenu instanceof TurbineValveMenu valveMenu&&valveMenu.present
                :(stage<2||stage==5)?mc.screen==null
                && mc.level.getBlockState(new BlockPos(176,215,145)).is(BwrBlocks.NUCLEAR_GENERATOR.get())
                :mc.screen instanceof PowerModuleScreen&&mc.player.containerMenu instanceof PowerModuleMenu menu&&menu.present&&menu.hpCount==2&&menu.lpCount==4&&menu.generatorCount==1&&menu.trainMW>0;
        if(!ready&&age%200==0)LogUtils.getLogger().info("POWER CLIENT waiting: stage={} position={} screen={} generator={}",stage,mc.player.position(),mc.screen==null?"world":mc.screen.getClass().getSimpleName(),mc.level.getBlockState(new BlockPos(176,215,145)));
        if(ready&&stage>=6&&shown==0){
            ((TurbineValveMenu)mc.player.containerMenu).sendCommand(0,stage==6?653:0);
            if(stage==6)for(var widget:mc.screen.children())if(widget instanceof net.minecraft.client.gui.components.EditBox edit)edit.setValue("65.3");
        }
        if(ready&&stage>=6&&shown>45){
            var v=(TurbineValveMenu)mc.player.containerMenu;double target=stage==6?.653:0;
            if(Math.abs(v.target-target)>1e-6||Math.abs(v.position-target)>1e-6)throw new IllegalStateException("Valve command/stroke did not round-trip");
            if(stage==7&&v.flow>1e-6)throw new IllegalStateException("Closed stop valve still reports steam flow");
        }
        if(!ready||++shown<60)return;
        mc.options.hideGui=stage<2||stage==5;mc.getToasts().clear();
        if(shown<65)return;
        try{var dir=mc.gameDirectory.toPath().resolve("power-check");java.nio.file.Files.createDirectories(dir);try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
        LogUtils.getLogger().info("POWER CLIENT CHECK: rendered {}",NAMES[stage]);mc.player.closeContainer();requested=false;mc.options.hideGui=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("POWER CLIENT CHECK PASS: assembled train, Blender valves, three readout panels and two synchronized valve controls");mc.stop();}
    }
}
