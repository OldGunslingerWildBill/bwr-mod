package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Opt-in disposable client fixture; excluded from the released JAR. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class SuppressionClientCheck {
    private static final BlockPos O=new BlockPos(160,190,160),HX=O.offset(6,6,-3);
    private static final String[] NAMES={"concrete-basin","exchanger-ports","exchanger-panel","pool-panel","spray-panel","pool-spray","detailed-water-pumps"};
    private static boolean started,requested,fixture,warming;
    private static volatile boolean spraying;private static int age,joined,stage,shown,warmTicks;
    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if(!Boolean.getBoolean("bwr.suppressionPanelCheck")||!fixture)return;
        var l=e.getServer().overworld();
        if(spraying&&l.getBlockEntity(O.offset(4,2,0)) instanceof SuppressionPoolBlockEntity pool) {
            pool.water(false).fill(new FluidStack(Fluids.WATER,30),FluidAction.EXECUTE);
            pool.reportSteamKgPerS(pool.getBlockPos(),l.getGameTime(),200,1000);
        }
        if(l.getBlockEntity(O.offset(12,0,3)) instanceof EccsPumpBlockEntity pump)pump.energy().setStored(pump.energy().getMaxEnergyStored());
        if(l.getBlockEntity(HX) instanceof RhrHeatExchangerBlockEntity hx){hx.secondary(true).fill(new FluidStack(Fluids.WATER,120),FluidAction.EXECUTE);hx.secondary(false).drain(120,FluidAction.EXECUTE);}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if(!Boolean.getBoolean("bwr.suppressionPanelCheck"))return;
        var mc=Minecraft.getInstance();if(++age>3500)throw new IllegalStateException("Suppression client timeout: "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.fov().set(55);mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-suppression-check-"+System.currentTimeMillis(),new LevelSettings("Suppression basin check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        // Let chunk generation and initial skylight finish before editing the distant fixture.
        if(!warming){warming=true;mc.getSingleplayerServer().execute(()->{var l=mc.getSingleplayerServer().overworld();for(int x=8;x<=13;x++)for(int z=8;z<=12;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}});return;}
        if(++warmTicks<100)return;
        var camera=stage==6?new Vec3(200,198,146):stage==5?new Vec3(173,200,155):stage==0?new Vec3(181,204,141):stage==1?new Vec3(184,195,162):stage==2?new Vec3(166.5,197,154):new Vec3(166.5,192,158);
        var target=stage==6?new Vec3(187,193,160):stage==5?new Vec3(165,193,163):stage==0?new Vec3(167,192.5,162):stage==1?new Vec3(183.5,192,168):stage==2?Vec3.atCenterOf(HX):Vec3.atCenterOf(O.offset(6,1,0));
        var delta=target.subtract(camera);float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        if(!requested) {
            requested=true;shown=0;int selected=stage;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();player.setNoGravity(true);player.getAbilities().flying=true;player.onUpdateAbilities();
                try {
                    if(selected==0) {
                        var pool=SuppressionBasinRegressionTests.build(l,O);SuppressionBasinRegressionTests.connect(l,O);SuppressionBasinRegressionTests.validate(pool);
                        pool.pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000,80).toArray());
                        for(var p:BlockPos.betweenClosed(O.offset(-2,-1,-6),O.offset(27,-1,10)))l.setBlock(p,Blocks.SMOOTH_STONE.defaultBlockState(),3);
                        for(int i=0;i<4;i++) {
                            var p=O.offset(19+i*3,1,8);var face=Direction.from2DDataValue(i);
                            l.setBlock(p.below(),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                            l.setBlock(p,BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState().setValue(RhrHeatExchangerBlock.FACING,face),3);
                            for(var d:Direction.Plane.HORIZONTAL){var q=p.relative(d);l.setBlock(q,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,q),3);}
                        }
                        dev.bwr.mod.devtest.CoolingRuntimeCheck.place(l,dev.bwr.core.turbine.CoolingWaterUnit.Design.CIRCULATING,O.offset(23,0,0),Direction.NORTH);
                        dev.bwr.mod.devtest.CoolingRuntimeCheck.place(l,dev.bwr.core.turbine.CoolingWaterUnit.Design.MAKEUP,O.offset(30,0,0),Direction.NORTH);
                        fixture=true;
                    }
                    player.closeContainer();player.connection.teleport(camera.x,camera.y,camera.z,yaw,pitch);l.setDayTime(6000);l.setWeatherParameters(6000,0,false,false);
                    if(selected==2)RhrHeatExchangerMenu.open(player,(RhrHeatExchangerBlockEntity)l.getBlockEntity(HX));
                    if(selected==4) {
                        var pool=(SuppressionPoolBlockEntity)l.getBlockEntity(O.offset(4,2,0));
                        pool.pool().fromArray(new dev.bwr.core.pool.SuppressionPool(50_000,105).toArray());pool.pool().resizeCapacityKeepingInventory(105_000);
                        SuppressionPoolMenu.open(player,pool,O.offset(6,1,0));spraying=true;
                    }
                    if(selected==3)SuppressionPoolMenu.open(player,(SuppressionPoolBlockEntity)l.getBlockEntity(O.offset(4,2,0)),O.offset(6,1,0));
                }catch(Exception ex){throw new IllegalStateException(ex);}
            });
        }
        ++shown;
        if(stage==4&&shown==30&&mc.player.containerMenu instanceof SuppressionPoolMenu menu)menu.sendCommand(SuppressionPoolMenu.CMD_FILL_MODE,1);
        if(stage==0&&shown==40){var uuid=mc.player.getUUID();var server=mc.getSingleplayerServer();server.execute(()->{
            var player=server.getPlayerList().getPlayer(uuid);var l=player.serverLevel();
            // The bulk fixture is built before teleportation. Resend completed chunk/light
            // data after lighting settles, rather than photographing the initial partial packet.
            for(int x=9;x<=12;x++)for(int z=9;z<=11;z++)player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(l.getChunk(x,z),l.getChunkSource().getLightEngine(),null,null));
        });}
        if(shown<100)return;
        if(mc.level.getBrightness(LightLayer.SKY,O.above(7))!=15||mc.level.getBrightness(LightLayer.SKY,O.offset(23,3,8))!=15)throw new AssertionError("Preview client skylight has not synchronized");
        if(stage==2&&(!(mc.player.containerMenu instanceof RhrHeatExchangerMenu m)||!m.present||!m.connected||m.heat<=0))throw new AssertionError("Exchanger live panel missing flow / heat");
        if(stage==3&&(!(mc.player.containerMenu instanceof SuppressionPoolMenu m)||!m.formed||!m.concrete||m.physicalCoolingMW<=0||m.passiveCoolingMW<=0))throw new AssertionError("Wall-port pool panel failed to open/sync RHR and natural cooling");
        if(stage==4&&(!(mc.player.containerMenu instanceof SuppressionPoolMenu m)||!m.sprayMode||m.sprayFlow<=0||m.sprayCondensed<=0))throw new AssertionError("Spray GUI command or finite condensation failed");
        if(stage==5) {
            var pool=(SuppressionPoolBlockEntity)mc.level.getBlockEntity(O.offset(4,2,0));
            if(!pool.visualFormed||pool.visualReturns.length==0||pool.visualSpray<=0)throw new AssertionError("Spray header visual state missing");
        }
        var standalone=new java.util.ArrayList<net.minecraft.client.resources.model.ModelResourceLocation>();
        for(var part:new String[]{"water_surface","spray_rail","spray_riser"})standalone.add(dev.bwr.mod.suppression.client.SuppressionPoolRenderer.model(part));
        for(var design:new dev.bwr.core.turbine.CoolingWaterUnit.Design[]{dev.bwr.core.turbine.CoolingWaterUnit.Design.CIRCULATING,dev.bwr.core.turbine.CoolingWaterUnit.Design.MAKEUP}) {
            standalone.add(dev.bwr.mod.cooling.client.CoolingRenderer.model(design));standalone.add(dev.bwr.mod.cooling.client.CoolingRenderer.previous(design));
        }
        for(var id:standalone) {
            var baked=mc.getModelManager().getModel(id);int count=0;
            for(int i=0;i<7;i++){var quads=baked.getQuads(null,i<6?Direction.values()[i]:null,net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);count+=quads.size();for(var q:quads)if(q.getSprite().contents().name().getPath().equals("missingno"))throw new AssertionError("Missing standalone texture: "+id);}
            if(baked==mc.getModelManager().getMissingModel()||count==0)throw new AssertionError("Missing standalone model: "+id);
        }
        for(var block:new net.minecraft.world.level.block.Block[]{BwrBlocks.SUPPRESSION_POOL_WALL.get(),BwrBlocks.SUPPRESSION_POOL_SUCTION.get(),BwrBlocks.SUPPRESSION_POOL_RETURN.get(),BwrBlocks.RHR_HEAT_EXCHANGER.get()})for(var state:block.getStateDefinition().getPossibleStates()) {
            var model=mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));int count=0;
            for(int i=0;i<7;i++){var quads=model.getQuads(state,i==6?null:Direction.values()[i],net.minecraft.util.RandomSource.create(42),ModelData.EMPTY,null);count+=quads.size();for(var q:quads)if(q.getSprite().contents().name().getPath().equals("missingno"))throw new AssertionError("Missing suppression texture: "+state);}
            if(model==mc.getModelManager().getMissingModel()||count==0)throw new AssertionError("Missing suppression model: "+state);
        }
        mc.player.setPos(camera.x,camera.y,camera.z);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(Vec3.ZERO);mc.options.hideGui=stage<2||stage>=5;mc.getToasts().clear();if(shown<110)return;
        try{var dir=mc.gameDirectory.toPath().resolve("suppression-check");java.nio.file.Files.createDirectories(dir);try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}}catch(java.io.IOException ex){throw new IllegalStateException(ex);}
        com.mojang.logging.LogUtils.getLogger().info("SUPPRESSION CLIENT CHECK: {}",NAMES[stage]);requested=false;
        if(++stage==NAMES.length){com.mojang.logging.LogUtils.getLogger().info("SUPPRESSION CLIENT CHECK PASS: concrete basin, four flange orientations, live water level, spray command/flow, wall-port panels and detailed pump models");mc.stop();}
    }
}
