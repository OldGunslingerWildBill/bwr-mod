package dev.bwr.mod.devtest;
import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.cooling.client.CoolingRenderer;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Disposable client world for the cooling-tower remodel pictures (1920 x 1080 through CheckShots): both towers running with
 * their plume, the fans and louvers up close, the hyperbolic tower's base legs and rim, and the distant LOD view.
 * Run with -PbwrCoolingTowerShots; pictures land in run/turbineModelCheck/cooling-tower-shots/.
 */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CoolingTowerShotsCheck {
    private static final BlockPos NATURAL=new BlockPos(140,200,140),ROUND=new BlockPos(172,200,140);
    /** name, camera x y z, look-at x y z, fov, ticks to wait, hide gui, expect LOD */
    private record Shot(String name,double cx,double cy,double cz,double tx,double ty,double tz,int fov,int ticks,boolean lod){}
    private static final Shot[] SHOTS={
        new Shot("natural-plume-near",192,212,82,140,236,140,70,420,false),
        new Shot("natural-base-legs",147,201.9,113,141,202.6,128,74,60,false),
        new Shot("natural-rim-and-ladder",168,226,104,146,232,140,80,60,false),
        new Shot("natural-lod-far",150,232,28,140,255,140,75,60,true),
        new Shot("round-plume",206,214,98,172,212,140,70,420,false),
        new Shot("round-fans-closeup",178,211.2,124.5,172,206.4,140,76,60,false),
        new Shot("round-fan-stack-closeup",181.5,210.6,136.0,176.9,206.7,140,70,60,false),
        new Shot("round-louvers-and-ports",164,203.4,123.5,170,203,132,70,60,false),
        new Shot("both-towers",208,214,96,156,218,140,72,420,false),
    };
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static long lodAtRequest;
    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.coolingTowerShots"))return;var l=event.getServer().overworld();
        for(var root:new BlockPos[]{NATURAL,ROUND})if(l.getBlockEntity(root) instanceof CoolingBlockEntity be){
            if(be.design().watts>0)be.power().receiveEnergy(Integer.MAX_VALUE,false);
            be.plant().fillInput(be.design().flow/20,false);be.plant().drain(be.design().flow/20,false);}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){
        if(!Boolean.getBoolean("bwr.coolingTowerShots"))return;var mc=Minecraft.getInstance();
        if(++age>9000)throw new IllegalStateException("Cooling tower shots timed out at stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.particles().set(ParticleStatus.ALL);
            mc.options.renderDistance().set(16);mc.options.hideGui=true;
            mc.createWorldOpenFlows().createFreshLevel("bwr-cooling-towers-"+System.currentTimeMillis(),new LevelSettings("Cooling towers",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        var shot=SHOTS[stage];
        if(!requested){requested=true;shown=0;lodAtRequest=CoolingRenderer.lodDraws;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            mc.options.fov().set(shot.fov());
            server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.getAbilities().flying=true;player.setNoGravity(true);player.onUpdateAbilities();
                if(selected==0){for(int x=6;x<=14;x++)for(int z=4;z<=12;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                    for(var p:BlockPos.betweenClosed(new BlockPos(100,199,100),new BlockPos(215,199,170)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    var natural=CoolingRuntimeCheck.place(l,Design.NATURAL,NATURAL,Direction.NORTH);natural.plant().fillInput(150000,false);
                    var round=CoolingRuntimeCheck.place(l,Design.MECHANICAL,ROUND,Direction.NORTH);round.power().receiveEnergy(Integer.MAX_VALUE,false);round.setTarget(.6);round.plant().fillInput(150000,false);
                }
                l.setDayTime(5000);l.setWeatherParameters(6000,0,false,false);
                player.connection.teleport(shot.cx(),shot.cy()-1.62,shot.cz(),yaw(shot),pitch(shot));
            });}
        if(!(mc.level.getBlockEntity(NATURAL) instanceof CoolingBlockEntity)||!(mc.level.getBlockEntity(ROUND) instanceof CoolingBlockEntity))return;
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setPos(shot.cx(),shot.cy()-1.62,shot.cz());mc.player.setYRot(yaw(shot));mc.player.setXRot(pitch(shot));
        if(mc.screen!=null)return;
        if(++shown<shot.ticks()+30)return;mc.getToasts().clear();mc.options.hideGui=true;if(shown<shot.ticks()+35)return;
        try{var dir=mc.gameDirectory.toPath().resolve("cooling-tower-shots");java.nio.file.Files.createDirectories(dir);CheckShots.save(mc,dir.resolve(shot.name()+".png"));}
        catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        boolean usedLod=CoolingRenderer.lodDraws>lodAtRequest;
        if(usedLod!=shot.lod())throw new AssertionError(shot.name()+": distant LOD mesh "+(usedLod?"drawn":"not drawn")+" but expected "+(shot.lod()?"drawn":"not drawn"));
        if(shot.ticks()>=400){int particles=Integer.parseInt(mc.particleEngine.countParticles());
            if(particles<100&&dev.bwr.mod.cooling.client.VaporPlumeRenderer.count()<100)throw new AssertionError(shot.name()+": no sustained plume ("+particles+" particles)");}
        LogUtils.getLogger().info("COOLING TOWER SHOTS: captured {} (lod={}, mesh uploads={}, draws={})",shot.name(),usedLod,dev.bwr.mod.client.MachineMeshCache.uploadCount(),dev.bwr.mod.client.MachineMeshCache.drawCount());
        requested=false;stage++;
        if(stage==SHOTS.length){LogUtils.getLogger().info("COOLING TOWER SHOTS PASS: {} pictures, LOD only far away, plume sustained",SHOTS.length);mc.stop();}
    }
    private static float yaw(Shot s){return (float)Math.toDegrees(Math.atan2(-(s.tx()-s.cx()),s.tz()-s.cz()));}
    private static float pitch(Shot s){return (float)-Math.toDegrees(Math.atan2(s.ty()-s.cy(),Math.hypot(s.tx()-s.cx(),s.tz()-s.cz())));}
}
