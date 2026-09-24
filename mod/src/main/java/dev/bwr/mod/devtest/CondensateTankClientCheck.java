package dev.bwr.mod.devtest;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.gui.CondensateTankMenu;
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

/** Disposable world and actual client/server size command; no player saves touched. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CondensateTankClientCheck {
    private static final BlockPos[] ROOTS={new BlockPos(140,210,140),new BlockPos(162,210,140),new BlockPos(189,210,140)};
    private static final String[] NAMES={"tank-sizes","tank-ports","tank-status","tank-dismantled"};
    private static final double[][] CAMERAS={{212,227,74,32,5},{173,218,126,38,10},{157,212,137,-30,0},{173,218,126,38,10}};
    private static boolean started,requested;private static int age,joined,stage,shown;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.tankPanelCheck"))return;var mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Tank client timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(55);
            mc.createWorldOpenFlows().createFreshLevel("bwr-tank-check-"+System.currentTimeMillis(),new LevelSettings("Tank check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        if(!requested){requested=true;shown=0;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{var p=server.getPlayerList().getPlayer(uuid);var l=p.serverLevel();p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.connection.teleport(220,250,140,0,0);
                if(selected==0){for(int x=7;x<=14;x++)for(int z=7;z<=10;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                    for(var at:BlockPos.betweenClosed(new BlockPos(134,209,130),new BlockPos(200,209,150)))l.setBlock(at,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    int[][] sizes={{3,3},{7,8},{15,24}};for(int i=0;i<3;i++){var root=ROOTS[i];var be=CondensateTankRuntimeCheck.buildShell(l,p,root,sizes[i][0],sizes[i][1]);if(!be.assembled())throw new AssertionError("Player shell did not autoform");be.fillKg(Math.min(100000,be.capacityKg()/2));
                        for(var face:Direction.Plane.HORIZONTAL){var at=root.offset(CondensateTankShape.port(be.diameter,face));for(int j=1;j<=2;j++){var pipe=at.relative(face,j);l.setBlock(pipe,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,pipe),3);}}
                    }
                }
                if(selected==3){p.closeContainer();l.destroyBlock(ROOTS[1],true);if(CondensateTankRuntimeCheck.casings(l,ROOTS[1])==0)throw new AssertionError("Dismantled casings disappeared");}
                l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);var c=CAMERAS[selected];p.connection.teleport(c[0],c[1],c[2],(float)c[3],(float)c[4]);if(selected==2)CondensateTankMenu.open(p,ROOTS[1].west(3).above());
            });}
        if(!(mc.level.getBlockEntity(ROOTS[0]) instanceof CondensateStorageTankBlockEntity))return;
        if(stage!=2&&mc.screen!=null||stage==2&&!(mc.player.containerMenu instanceof CondensateTankMenu m&&m.present))return;
        var c=CAMERAS[stage];mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(Vec3.ZERO);mc.player.setPos(c[0],c[1],c[2]);mc.player.setYRot((float)c[3]);mc.player.setXRot((float)c[4]);
        shown++;
        if(stage==2){var m=(CondensateTankMenu)mc.player.containerMenu;if(shown==20)m.sendCommand(0,7,10);if(shown==70&&m.height!=8)throw new AssertionError("Read-only tank menu accepted obsolete resize command: "+m.notice);}
        if(shown<90)return;mc.options.hideGui=stage!=2;mc.getToasts().clear();if(shown<95)return;
        try{var dir=mc.gameDirectory.toPath().resolve("tank-check");java.nio.file.Files.createDirectories(dir);try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}}catch(java.io.IOException e){throw new IllegalStateException(e);}
        LogUtils.getLogger().info("CONDENSATE TANK CLIENT CHECK: captured {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("CONDENSATE TANK CLIENT CHECK PASS: three automatically formed sizes, joined flanges, read-only status screen and visible dismantled casings");mc.stop();}
    }
}
