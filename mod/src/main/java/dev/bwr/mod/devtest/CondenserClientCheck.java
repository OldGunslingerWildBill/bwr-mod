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
    private static final String[] NAMES={"lp-condenser-front","lp-condenser-rear","lp-condenser-night","condenser-front-seam","condenser-rear-seam","condenser-panel","snap-green","snap-red","snap-placed","round-makeup-ports","adjacent-lp-condensers"};
    private static final double[][] CAMERAS={{157,219,128,37,13},{131,219,160,-139,13},
        {157,219,128,37,13},{148,213,134,22,7},{141,213,155,-160,7},{144,213,138,0,0},
        {149.2,213,139.8,45,-20},{149.2,213,139.8,45,-20},{157,219,128,37,13},{144.5,212,138,0,3},{150,220,127,22,25}};
    private static boolean started,requested,clicked;private static int age,joined,stage,shown;
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
                    var b=BwrBlocks.ARABELLE_CONDENSER.get();var state=b.defaultBlockState().setValue(CondenserBlock.FACING,Direction.EAST);
                    l.setBlock(ROOT,state,3);b.setPlacedBy(l,ROOT,state,null,new ItemStack(b));
                    var lp=BwrBlocks.LP_TURBINE.get();var ls=lp.placementState().setValue(PumpAssemblyBlock.FACING,Direction.NORTH);
                    l.setBlock(ROOT.above(6),ls,3);lp.setPlacedBy(l,ROOT.above(6),ls,null,new ItemStack(lp));
                    var layout=CondenserLayout.INSTANCE;
                    for(var cell:layout.ports){
                        var p=layout.world(ROOT,Direction.EAST,cell);var face=CondenserBlock.portFace(l.getBlockState(p));
                        if(cell.role()==CondenserBlock.Port.BYPASS){
                            for(int i=1;i<=3;i++){var at=p.relative(face,i);l.setBlock(at,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,at),3);}
                        }else{
                            var at=p.relative(face);l.setBlock(at,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,at),3);
                            if(face==Direction.DOWN){
                                var outward=cell.role()==CondenserBlock.Port.HOT?Direction.EAST:Direction.WEST;
                                for(int i=1;i<=2;i++){var q=at.relative(outward,i);l.setBlock(q,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,q),3);}
                            }
                        }
                    }
                    if(!((CondenserBlockEntity)l.getBlockEntity(ROOT)).ready()||!lp.complete(l,ROOT.above(6),ls))throw new AssertionError("Photographed assembly incomplete");
                }
                l.setDayTime(selected==2?18000:6000);l.setWeatherParameters(6000,0,false,false);
                if(selected==6){
                    player.closeContainer();l.removeBlock(ROOT,false);
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(BwrBlocks.ARABELLE_CONDENSER.get()));
                }
                if(selected==7)l.setBlock(ROOT,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
                if(selected==8)l.removeBlock(ROOT,false);
                if(selected==10){
                    var other=ROOT.north(7);var lp=BwrBlocks.LP_TURBINE.get();var ls=lp.placementState();
                    l.setBlock(other.above(6),ls,3);lp.setPlacedBy(l,other.above(6),ls,null,new ItemStack(lp));
                    var b=BwrBlocks.ARABELLE_CONDENSER.get();var cs=b.defaultBlockState().setValue(CondenserBlock.FACING,Direction.EAST);
                    if(!CondenserBlock.canPlaceAt(l,null,other,Direction.EAST))throw new AssertionError("Adjacent condenser cannot fit");
                    l.setBlock(other,cs,3);b.setPlacedBy(l,other,cs,null,new ItemStack(b));
                    if(!((CondenserBlockEntity)l.getBlockEntity(other)).ready())throw new AssertionError("Adjacent condenser incomplete");
                    for(int dx:new int[]{-7,7})for(var p:BlockPos.betweenClosed(ROOT.offset(dx,0,-12),ROOT.offset(dx,5,5)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                }
                var camera=CAMERAS[selected];player.connection.teleport(camera[0],camera[1],camera[2],(float)camera[3],(float)camera[4]);
                if(selected==5)dev.bwr.mod.gui.CondenserMenu.open(player,ROOT,ROOT);
            });
        }
        if(stage!=5&&mc.screen!=null||stage==5&&!(mc.player.containerMenu instanceof dev.bwr.mod.gui.CondenserMenu m&&m.present))return;
        if(stage<6&&!(mc.level.getBlockEntity(ROOT) instanceof CondenserBlockEntity)||!mc.level.getBlockState(ROOT.above(6)).is(BwrBlocks.LP_TURBINE.get()))return;
        if(stage==6&&!mc.level.getBlockState(ROOT).isAir()||stage==7&&!mc.level.getBlockState(ROOT).is(Blocks.DIAMOND_BLOCK))return;
        if(stage==8&&!clicked){
            if(!mc.level.getBlockState(ROOT).isAir())return;
            // Use the normal client interaction path, without sneaking. It must
            // place below the LP instead of opening the turbine control screen.
            var camera=CAMERAS[6];mc.player.setPos(camera[0],camera[1],camera[2]);mc.player.setYRot(45);mc.player.setXRot(-20);
            if(++shown<10)return;
            if(!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)||CondenserPlacement.attachment(mc.level,hit)==null)throw new AssertionError("snap test is not aiming at the underside: "+mc.hitResult);
            mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);clicked=true;shown=0;return;
        }
        if(stage==8&&!(mc.level.getBlockEntity(ROOT) instanceof CondenserBlockEntity))return;
        var camera=CAMERAS[stage];
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(Vec3.ZERO);mc.player.setPos(camera[0],camera[1],camera[2]);
        mc.player.setYRot((float)camera[3]);mc.player.setXRot((float)camera[4]);
        if(++shown<90)return;
        mc.options.hideGui=stage<5||stage>=8;mc.getToasts().clear();if(shown<95)return;
        if(stage==6||stage==7){
            if(!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit))throw new AssertionError("No preview hit");
            var target=CondenserPlacement.attachment(mc.level,hit);
            if(target==null||!target.root().equals(ROOT)||CondenserBlock.canPlaceAt(mc.level,mc.player,ROOT,Direction.EAST)!=(stage==6))throw new AssertionError("Incorrect snap preview target/validity");
        }
        try{
            var dir=mc.gameDirectory.toPath().resolve("condenser-check");java.nio.file.Files.createDirectories(dir);
            try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
        LogUtils.getLogger().info("CONDENSER CLIENT CHECK: captured {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("CONDENSER CLIENT CHECK PASS: LP fit, eight pipe ports, shell seams, panel, green/red previews and unsneaked underside placement");mc.stop();}
    }
}
