package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.TerryTurbineScreen;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in screenshots and real menu networking in a fresh disposable world. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class TerryPanelClientCheck {
    private static boolean started,requested;
    private static int age,stage,shown;
    private static final String[] NAMES={"terry-rcic-world","terry-rcic-panel","terry-hpci-world","terry-hpci-panel"};
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bwr.terryPanelCheck"))return;
        var mc=Minecraft.getInstance();
        if(++age>4000)throw new IllegalStateException("Terry client check timed out at "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.options.fov().set(55);
            mc.createWorldOpenFlows().createFreshLevel("bwr-terry-check-"+System.currentTimeMillis(),
                    new LevelSettings("Terry check",GameType.CREATIVE,false,Difficulty.PEACEFUL,false,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(314159L,false,false),a->a.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
            return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        if(!requested) {
            requested=true;shown=0;int selected=stage;
            var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(uuid);var level=player.serverLevel();
                boolean hp=selected>=2;var block=hp?BwrBlocks.HPCI_TURBINE.get():BwrBlocks.RCIC_TWL.get();
                var root=new BlockPos(hp?264:240,120,180);var panel=root.offset(hp?4:3,2,0);
                if(selected%2==0) {
                    for(var p:BlockPos.betweenClosed(root.offset(-3,-1,-3),root.offset(10,-1,7)))level.setBlock(p,net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    var state=block.placementState();level.setBlock(root,state,3);block.setPlacedBy(level,root,state,null,new net.minecraft.world.item.ItemStack(block));
                    for(var role:AssemblyPort.values()) {
                        var port=block.portPosition(root,state,role);var face=block.portFace(state,role);
                        for(int n=1;n<=3;n++) {
                            var p=port.relative(face,n);var pipe=role.isSteam()?BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(level,p):BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level,p);
                            level.setBlock(p,pipe,3);
                        }
                    }
                    var be=(EccsPumpBlockEntity)level.getBlockEntity(root);be.setPanelControlled(true);be.setSpeedDemandFraction(.65);be.setSuctionSource(SuctionSource.CONDENSATE_TANK);
                    be.waterInlet().fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1_000_000),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    player.getAbilities().flying=true;player.onUpdateAbilities();
                    player.connection.teleport(root.getX()+11,root.getY()+6,root.getZ()-9,34,17);
                    level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                } else {
                    player.connection.teleport(panel.getX()+.5,panel.getY(),panel.getZ()-1.5,0,0);
                    PumpControlMenu.open(player,root,panel);
                }
            });
        }
        boolean panel=stage%2==1;
        // Initial login can restore the client's old flying flag after a server teleport.
        // Hold this test-only camera while asynchronous chunk meshes finish uploading.
        if(!panel&&requested&&Math.abs(mc.player.getX()-(stage>=2?275:251))<1) {
            mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mc.player.setPos(stage>=2?275:251,126,171);mc.player.setYRot(34);mc.player.setXRot(17);
        }
        boolean ready=panel?mc.screen instanceof TerryTurbineScreen&&mc.player.containerMenu instanceof TerryTurbineMenu m&&m.present&&m.target==.65&&!m.portLocations[3].isEmpty()
                :mc.screen==null&&Math.abs(mc.player.getX()-(stage>=2?275:251))<1;
        if(!ready)return;
        mc.options.hideGui=!panel;mc.getToasts().clear();
        // Initial chunk meshes can still be uploading after the teleport packet arrives.
        if(++shown<(panel?30:120))return;
        try {
            var folder=mc.gameDirectory.toPath().resolve("terry-check");java.nio.file.Files.createDirectories(folder);
            try(var picture=Screenshot.takeScreenshot(mc.getMainRenderTarget())){picture.writeToFile(folder.resolve(NAMES[stage]+".png"));}
            LogUtils.getLogger().info("TERRY CLIENT CHECK: {}",NAMES[stage]);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
        mc.player.closeContainer();requested=false;
        if(++stage==NAMES.length){LogUtils.getLogger().info("TERRY CLIENT CHECK PASS: both skids, round connected ports, synchronized screens");mc.options.hideGui=false;mc.stop();}
    }
}
