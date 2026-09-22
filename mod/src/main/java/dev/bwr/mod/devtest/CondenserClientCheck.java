package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Opt-in real-world visual regression: material colors, LP fit, and visible pipe seams. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CondenserClientCheck {
    private static final BlockPos ROOT=new BlockPos(144,210,144);
    private static final String[] NAMES={"lp-condenser-front","lp-condenser-rear","lp-condenser-night","condenser-front-seam","condenser-rear-seam"};
    private static final double[][] CAMERAS={{157,219,128,37,13},{131,219,160,-139,13},
        {157,219,128,37,13},{148,213,134,22,7},{141,213,155,-160,7}};
    private static boolean started,requested;private static int age,joined,stage,shown;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bwr.condenserPanelCheck"))return;
        var mc=Minecraft.getInstance();
        if(++age>3000)throw new IllegalStateException("Condenser visual check timed out, stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null){
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(55);
            mc.createWorldOpenFlows().createFreshLevel("bwr-condenser-check-"+System.currentTimeMillis(),
                new LevelSettings("Condenser fit check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        if(!requested){
            requested=true;shown=0;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();int selected=stage;
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                player.getAbilities().flying=true;player.setNoGravity(true);player.onUpdateAbilities();
                if(selected==0){
                    for(int x=7;x<=10;x++)for(int z=7;z<=10;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                    for(var p:BlockPos.betweenClosed(ROOT.offset(-8,-1,-8),ROOT.offset(8,-1,8)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    var b=BwrBlocks.ARABELLE_CONDENSER.get();var state=b.defaultBlockState().setValue(CondenserBlock.FACING,Direction.NORTH);
                    l.setBlock(ROOT,state,3);b.setPlacedBy(l,ROOT,state,null,new ItemStack(b));
                    var lp=BwrBlocks.LP_TURBINE.get();var ls=lp.placementState().setValue(PumpAssemblyBlock.FACING,Direction.NORTH);
                    l.setBlock(ROOT.above(6),ls,3);lp.setPlacedBy(l,ROOT.above(6),ls,null,new ItemStack(lp));
                    var layout=CondenserLayout.INSTANCE;
                    for(var cell:layout.ports){
                        var p=layout.world(ROOT,Direction.NORTH,cell);var face=CondenserBlock.portFace(l.getBlockState(p));
                        if(cell.role()==CondenserBlock.Port.BYPASS){
                            for(int i=1;i<=3;i++){var at=p.relative(face,i);l.setBlock(at,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,at),3);}
                        }else{
                            var at=p.relative(face);l.setBlock(at,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,at),3);
                            if(face==Direction.DOWN){
                                var outward=cell.role()==CondenserBlock.Port.HOT?Direction.NORTH:Direction.SOUTH;
                                for(int i=1;i<=2;i++){var q=at.relative(outward,i);l.setBlock(q,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,q),3);}
                            }
                        }
                    }
                    if(!((CondenserBlockEntity)l.getBlockEntity(ROOT)).ready()||!lp.complete(l,ROOT.above(6),ls))throw new AssertionError("Photographed assembly incomplete");
                }
                l.setDayTime(selected==2?18000:6000);l.setWeatherParameters(6000,0,false,false);
                var camera=CAMERAS[selected];player.connection.teleport(camera[0],camera[1],camera[2],(float)camera[3],(float)camera[4]);
            });
        }
        if(mc.screen!=null||!(mc.level.getBlockEntity(ROOT) instanceof CondenserBlockEntity)||!mc.level.getBlockState(ROOT.above(6)).is(BwrBlocks.LP_TURBINE.get()))return;
        var camera=CAMERAS[stage];
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(Vec3.ZERO);mc.player.setPos(camera[0],camera[1],camera[2]);
        mc.player.setYRot((float)camera[3]);mc.player.setXRot((float)camera[4]);
        if(++shown<90)return;
        mc.options.hideGui=true;mc.getToasts().clear();if(shown<95)return;
        try{
            var dir=mc.gameDirectory.toPath().resolve("condenser-check");java.nio.file.Files.createDirectories(dir);
            try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
        LogUtils.getLogger().info("CONDENSER CLIENT CHECK: captured {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("CONDENSER CLIENT CHECK PASS: LP fit and six attached pipe ports, day/rear/night and shell seam frames");mc.stop();}
    }
}
