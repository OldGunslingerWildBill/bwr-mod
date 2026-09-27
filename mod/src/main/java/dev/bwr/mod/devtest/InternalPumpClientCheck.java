package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Isolated client fixture for RIP layouts, the chunk-baked CRD, and the pump panel. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class InternalPumpClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160);
    private static final String[] NAMES={"crd-detail","rip-under-vessel","reference-core","minimum-core","maximum-core","mount-guides","rip-panel","one-rip-core","annulus-plan"};
    private static boolean started,requested;private static int age,joined,stage,shown;
    private static long uploads;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.ripModelCheck"))return;var mc=Minecraft.getInstance();
        if(++age>6500)throw new IllegalStateException("RIP client check timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rip-model-"+System.currentTimeMillis(),new LevelSettings("RIP model check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        int width=stage==3?5:stage==4?21:15,height=stage==3?8:20;
        var root=MIN.offset(-1,3,width/2);double cx=MIN.getX()+width/2.0,cz=MIN.getZ()+width/2.0;
        Vec3 camera,target;
        if(stage==0){camera=Vec3.atCenterOf(MIN.offset(-4,-2,-4)).add(1.4,.65,-2.1);target=Vec3.atCenterOf(MIN.offset(-4,-2,-4));}
        else if(stage==1||stage==5){camera=new Vec3(cx-width*.85,MIN.getY()-7,cz-width*.85);target=new Vec3(cx,MIN.getY()-1,cz);}
        else if(stage==6){camera=new Vec3(MIN.getX()+4,MIN.getY()-3,MIN.getZ()+4);target=camera.add(1,0,1);}
        else if(stage==8){camera=new Vec3(cx,MIN.getY()+height+width*1.4,cz-.001);target=new Vec3(cx,MIN.getY()+height*.6,cz);}
        else{camera=new Vec3(cx-width*.26,MIN.getY()+height+width*.85,cz-width*.50);target=new Vec3(cx,MIN.getY()+height*.6,cz);}
        var delta=target.subtract(camera);float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
                player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                for(int x=8;x<=13;x++)for(int z=8;z<=13;z++)l.setChunkForced(x,z,true);
                try {
                    for(var p:BlockPos.betweenClosed(MIN.offset(-5,-6,-5),MIN.offset(24,25,24)))l.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                    var be=CompactCoreRegressionTests.build(l,MIN,width,width,height,false);
                    BlockPos first=null;
                    for(var site:InternalPumpLayout.mounts(width,width)){if(selected==7&&first!=null)break;var pos=InternalPumpRegressionTests.install(l,MIN,site,Direction.NORTH);if(first==null)first=pos;}
                    var desiredDrives=new java.util.HashSet<>(new CompactCoreLayout(width,width,true).drives());
                    for(int x=0;x<width;x++)for(int z=0;z<width;z++) {
                        var at=MIN.offset(x,-2,z);
                        if(!desiredDrives.contains(new CompactCoreLayout.Drive(x,z))&&l.getBlockState(at).is(BwrBlocks.CONTROL_ROD_DRIVE.get()))l.removeBlock(at,false);
                    }
                    InternalPumpRegressionTests.validate(be);
                    if(!be.isFormed())throw new AssertionError("Client RIP fixture invalid: "+be.statusLines());
                    SpargerRegressionTests.rings(l,be);InternalPumpRegressionTests.validate(be);
                    be.setVesselState(selected==1||selected==5||selected==6?VesselState.SHUTDOWN:VesselState.REFUELING);
                    if((selected>=2&&selected<=4)||selected>=7)for(int slot:be.corePositions())be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                    l.setBlock(MIN.offset(-4,-2,-4),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
                    player.setItemInHand(InteractionHand.MAIN_HAND,selected==5?new ItemStack(BwrBlocks.RIP_PUMP.get()):ItemStack.EMPTY);
                    l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(MIN.offset(-8,-9,-8)),Vec3.atLowerCornerOf(MIN.offset(28,29,28)))).forEach(net.minecraft.world.entity.Entity::discard);
                    l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);player.connection.teleport(camera.x,camera.y-player.getEyeHeight(),camera.z,yaw,pitch);
                    if(selected==6)dev.bwr.mod.gui.PumpControlMenu.open(player,first,first.below());
                }catch(Exception ex){throw new IllegalStateException(ex);}
            });
        }
        if(++shown<130)return;
        var be=(ReactorControllerBlockEntity)mc.level.getBlockEntity(root);var core=be.clientCoreAppearance();
        var expected=new CompactCoreLayout(width,width,true);
        if(core==null||!core.internalPumps()||core.cells().size()!=expected.assemblyCount()||core.drives().size()!=expected.drives().size())throw new AssertionError("RIP client snapshot not reduced");
        var envelope=be.clientVesselEnvelope();
        var shape=dev.bwr.mod.reactor.client.ReactorCoreGeometry.layout(envelope,core);
        double outer=dev.bwr.mod.reactor.client.ReactorCoreGeometry.shroudOuterRadius(core);
        if(Math.abs(outer/.448-5600.7/7112)>1e-6)throw new AssertionError("Shroud does not match ABWR diameter ratio");
        double furthest=0;
        for(var cell:core.cells()) {
            double r=Math.hypot((Math.abs(shape.x(cell.slot()))+.5*shape.pitchX())/envelope.width(),
                    (Math.abs(shape.z(cell.slot()))+.5*shape.pitchZ())/envelope.depth());
            if(r>AbwrShroudGeometry.INNER_RADIUS-.001)throw new AssertionError("Fuel array clips ABWR shroud");
            furthest=Math.max(furthest,r);
        }
        var packed=RipCorePacking.layout(width,width);
        if(Math.abs(shape.pitchX()-packed.pitchX())>1e-6||Math.abs(shape.pitchZ()-packed.pitchZ())>1e-6)throw new AssertionError("Client pitch differs from real fuel packing");
        if(furthest>AbwrShroudGeometry.FUEL_RADIUS+1e-6)throw new AssertionError("Fuel packing lost its shroud clearance");
        for(var drive:core.drives())if(!RipCorePacking.bladeFits(drive.x(),drive.z(),shape.pitchX(),shape.pitchZ(),width,width))throw new AssertionError("Peripheral control blade clips shroud");
        for(String part:new String[]{"abwr_shroud","abwr_shroud_rim"}) {
            var mesh=mc.getModelManager().getModel(dev.bwr.mod.reactor.client.ReactorCoreGeometry.model(part));
            var quads=mesh.getQuads(null,null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);
            if(quads.isEmpty()||quads.stream().anyMatch(q->q.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing ABWR shroud asset");
            double min=Double.POSITIVE_INFINITY,max=0;
            for(var quad:quads){var vertices=quad.getVertices();int stride=vertices.length/4;
                for(int i=0;i<4;i++){double r=Math.hypot(Float.intBitsToFloat(vertices[i*stride]),Float.intBitsToFloat(vertices[i*stride+2]));min=Math.min(min,r);max=Math.max(max,r);}}
            double expectedOuter=part.endsWith("rim")?AbwrShroudGeometry.RIM_RADIUS:AbwrShroudGeometry.OUTER_RADIUS;
            if(Math.abs(min-AbwrShroudGeometry.INNER_RADIUS)>1e-5||Math.abs(max-expectedOuter)>1e-5)throw new AssertionError("Blender asset and ABWR dimensions disagree: "+part+" "+min+" "+max);
        }
        var baked=mc.getBlockRenderer().getBlockModel(BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState());
        var faces=baked.getQuads(null,null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);
        if(faces.isEmpty()||faces.stream().anyMatch(face->face.getSprite().contents().name().getPath().equals("missingno")))throw new AssertionError("Missing CRD model/material");
        if(stage==6&&(!(mc.player.containerMenu instanceof dev.bwr.mod.gui.PumpControlMenu m)||!m.connection.equals("Mounted inside reactor vessel")))throw new AssertionError("RIP GUI not showing a valid mount");
        mc.player.setPos(camera.x,camera.y-mc.player.getEyeHeight(),camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=stage!=6;mc.getToasts().clear();
        if(shown==140)uploads=dev.bwr.mod.client.MachineMeshCache.uploadCount();if(shown<170)return;
        if(dev.bwr.mod.client.MachineMeshCache.uploadCount()!=uploads)throw new AssertionError("RIP core uploads every frame");
        var dir=mc.gameDirectory.toPath().resolve("rip-model-check");java.nio.file.Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("RIP MODEL CHECK: {} assemblies={} drives={}",NAMES[stage],core.cells().size(),core.drives().size());requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("RIP MODEL CHECK PASS: nine views, ABWR shroud dimensions, expanded fuel geometry, CRD materials, mount guides, panel, stable GPU cache");mc.stop();}
    }
}
