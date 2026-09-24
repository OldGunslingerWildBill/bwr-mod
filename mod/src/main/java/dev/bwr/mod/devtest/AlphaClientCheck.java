package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Real client/server panels and placed models, in an opt-in disposable test world. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class AlphaClientCheck {
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static final String[] NAMES={"hardware","boron-tank","slc-pump","ads-division","ads-relief","water-outfall"};
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("bwr.alphaClientCheck"))return;var mc=Minecraft.getInstance();
        if(++age>2400)throw new IllegalStateException("Alpha client timeout at "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){started=true;mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.options.enableVsync().set(false);
            mc.createWorldOpenFlows().createFreshLevel("bwr-alpha-check-"+System.currentTimeMillis(),new LevelSettings("Alpha hardware",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;}
        if(mc.player==null||mc.level==null||++joined<100)return;
        if(!requested){requested=true;shown=0;int current=stage;var uuid=mc.player.getUUID();var server=mc.getSingleplayerServer();mc.setScreen(null);
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);
                var pumpPos=new BlockPos(32,110,32);var tankPos=new BlockPos(38,110,32);
                if(current==0){
                    for(var p:BlockPos.betweenClosed(new BlockPos(28,109,28),new BlockPos(43,109,39)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),2);
                    AlphaRegressionTests.place(l,BwrBlocks.SLC_PUMP.get(),pumpPos,Direction.NORTH);
                    AlphaRegressionTests.place(l,BwrBlocks.SLC_BORON_TANK.get(),tankPos,Direction.NORTH);
                    var tank=(SlcTankBlockEntity)l.getBlockEntity(tankPos);tank.waterInlet().fill(dev.bwr.mod.water.ThermalWater.atTemperature(10000,25),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    for(int i=0;i<35;i++)tank.addCharge();
                    l.setBlock(new BlockPos(34,110,36),BwrBlocks.ADS_CONTROLLER.get().defaultBlockState(),2);
                    l.setBlock(new BlockPos(36,110,36),BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(),2);
                    l.setBlock(new BlockPos(40,110,36),BwrBlocks.WATER_DISCHARGE_PORT.get().defaultBlockState(),2);
                    var pump=(EccsPumpBlockEntity)l.getBlockEntity(pumpPos);pump.setPanelControlled(true);pump.energy().receiveEnergy(Integer.MAX_VALUE,false);
                    for(var role:new AssemblyPort[]{AssemblyPort.WATER_SUCTION,AssemblyPort.WATER_DISCHARGE}){
                        var b=BwrBlocks.SLC_PUMP.get();var p=b.portPosition(pumpPos,pump.getBlockState(),role);var d=b.portFace(pump.getBlockState(),role);
                        for(int i=1;i<=2;i++){var at=p.relative(d,i);l.setBlock(at,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,at),3);}
                    }
                    player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(27.5,114,24.5,-42,20);
                }else{
                    var pos=switch(current){case 1->tankPos.north();case 2->pumpPos;case 3->new BlockPos(34,110,36);case 4->new BlockPos(36,110,36);default->new BlockPos(40,110,36);};
                    player.connection.teleport(pos.getX()+.5,pos.getY()+1,pos.getZ()-3,0,15);
                    if(current==2)PumpControlMenu.open(player,pumpPos,pumpPos);else ServiceMenu.open(player,pos);
                }
            });
        }
        boolean ready=stage==0?mc.screen==null&&mc.player.getY()>110:stage==2?mc.player.containerMenu instanceof PumpControlMenu pumpMenu&&pumpMenu.present:mc.player.containerMenu instanceof ServiceMenu service&&service.kind>0;
        if(ready&&++shown>55){
            var dir=mc.gameDirectory.toPath().resolve("alpha-check");java.nio.file.Files.createDirectories(dir);
            try(var shot=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
            LogUtils.getLogger().info("ALPHA CLIENT PASS {}",NAMES[stage]);
            if(++stage==NAMES.length){LogUtils.getLogger().info("ALPHA CLIENT COMPLETE");mc.stop();}else requested=false;
        }
    }
}
