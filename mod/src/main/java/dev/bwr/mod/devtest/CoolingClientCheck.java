package dev.bwr.mod.devtest;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.gui.CoolingMenu;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Disposable client world: whole models, actual pipe joints, moving fans and control GUI. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CoolingClientCheck {
    private static final BlockPos[] ROOTS={new BlockPos(140,200,140),new BlockPos(172,200,140),new BlockPos(194,200,140),new BlockPos(204,200,140),new BlockPos(211,200,140)};
    private static final String[] NAMES={"natural-draft","circular-induced-draft","water-pumps-intake","fan-controls","intake-controls","submerged-intake","tall-vapor-plumes"};
    private static final double[][] CAMERAS={{175,224,94,35,10},{194,216,112,38,18},{214,208,124,35,14},{173,202,130,0,0},{211,201,137,0,0},{213,201.3,137.3,37,5},{180,275,24,16,0}};
    private static boolean started,requested;private static int age,joined,stage,shown;
    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.coolingPanelCheck"))return;var l=event.getServer().overworld();
        for(var root:ROOTS)if(l.getBlockEntity(root) instanceof CoolingBlockEntity be){if(be.design().watts>0)be.power().receiveEnergy(Integer.MAX_VALUE,false);if(be.design().tower){be.plant().fillInput(be.design().flow/20,false);be.plant().drain(be.design().flow/20,false);}}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){
        if(!Boolean.getBoolean("bwr.coolingPanelCheck"))return;var mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Cooling client timed out stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(55);
            mc.createWorldOpenFlows().createFreshLevel("bwr-cooling-check-"+System.currentTimeMillis(),new LevelSettings("Cooling check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        if(!requested){requested=true;shown=0;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.getAbilities().flying=true;player.setNoGravity(true);player.onUpdateAbilities();
                if(selected==0){for(int x=7;x<=14;x++)for(int z=7;z<=10;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                    for(var p:BlockPos.betweenClosed(new BlockPos(126,199,126),new BlockPos(216,199,154)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    int index=0;for(var d:Design.values()){var root=ROOTS[index++];var be=d==Design.INTAKE?wetIntake(l,root):CoolingRuntimeCheck.place(l,d,root,Direction.NORTH);
                        for(var c:be.layout().ports)if(c.role().water()){var p=be.layout().world(root,Direction.NORTH,c);var face=CoolingBlock.portFace(l.getBlockState(p));for(int k=1;k<=2;k++){var at=p.relative(face,k);l.setBlock(at,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,at),3);}}
                        if(d.watts>0){be.power().receiveEnergy(Integer.MAX_VALUE,false);be.setTarget(.5);}if(d==Design.MECHANICAL)be.plant().fillInput(150000,false);
                    }

                    l.setBlock(ROOTS[4].north(2),Blocks.WATER.defaultBlockState(),3);l.setBlock(ROOTS[4].west(2),Blocks.WATER.defaultBlockState(),3);
                }
                l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);var c=CAMERAS[selected];player.connection.teleport(c[0],c[1],c[2],(float)c[3],(float)c[4]);
                if(selected>=5)player.closeContainer();
                if(selected==4){player.closeContainer();CoolingMenu.open(player,ROOTS[4],ROOTS[4]);}
                if(selected==3){var be=(CoolingBlockEntity)l.getBlockEntity(ROOTS[1]);be.plant().restore(150000,0,0);be.power().receiveEnergy(Integer.MAX_VALUE,false);CoolingMenu.open(player,ROOTS[1],CoolingRuntimeCheck.port(be,CoolingBlock.Port.INLET));}
            });}
        if(!(mc.level.getBlockEntity(ROOTS[0]) instanceof CoolingBlockEntity))return;
        if(stage==6)mc.options.fov().set(90);
        if((stage<3||stage>=5)&&mc.screen!=null||stage>=3&&stage<5&&!(mc.player.containerMenu instanceof CoolingMenu m&&m.present))return;
        var c=CAMERAS[stage];mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(Vec3.ZERO);mc.player.setPos(c[0],c[1],c[2]);mc.player.setYRot((float)c[3]);mc.player.setXRot((float)c[4]);
        if(++shown<(stage==6?200:90))return;mc.options.hideGui=stage<3||stage>=5;mc.getToasts().clear();if(shown<95)return;
        try{var dir=mc.gameDirectory.toPath().resolve("cooling-check");java.nio.file.Files.createDirectories(dir);try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}}catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        if(stage==1){var fan=(CoolingBlockEntity)mc.level.getBlockEntity(ROOTS[1]);if(fan.vaporActivity()<=0||Math.abs(fan.fanAngle-fan.previousFanAngle-6)>0.01)throw new AssertionError("Mechanical vapor activity/fan direction or speed incorrect at 50% power");}
        if(stage==6){int particles=Integer.parseInt(mc.particleEngine.countParticles());if(particles<100)throw new AssertionError("No sustained cooling plumes: "+particles);LogUtils.getLogger().info("VAPOR PARTICLES: {}",particles);}
        LogUtils.getLogger().info("COOLING CLIENT CHECK: captured {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("COOLING CLIENT CHECK PASS: five models, ports, fan motion and control screen");mc.stop();}
    }
    private static CoolingBlockEntity wetIntake(net.minecraft.server.level.ServerLevel l,BlockPos root){
        for(var p:BlockPos.betweenClosed(root.offset(-3,0,-3),root.offset(3,3,3)))l.setBlock(p,Blocks.WATER.defaultBlockState(),3);
        var b=BwrBlocks.SCREENED_WATER_INTAKE.get();var item=new net.minecraft.world.item.ItemStack(b);
        var context=new net.minecraft.world.item.context.DirectionalPlaceContext(l,root,Direction.SOUTH,item,Direction.UP);
        var state=b.getStateForPlacement(context);if(state==null)throw new AssertionError("Submerged intake placement refused");
        l.setBlock(root,state,3);b.setPlacedBy(l,root,state,null,item);return (CoolingBlockEntity)l.getBlockEntity(root);
    }

}
