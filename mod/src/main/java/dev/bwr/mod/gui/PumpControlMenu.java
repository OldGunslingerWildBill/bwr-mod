package dev.bwr.mod.gui;

import dev.bwr.core.eccs.PumpDesign;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/** One panel for powered pumps. Commands apply only to the open, reachable machine. */
public class PumpControlMenu extends BwrMenu {
    public static final int SPEED=0, RUN=1, CONTROL=2, SUCTION=3, MODE=4;
    private final BlockPos anchor;
    private final Block expected;
    public boolean slc;
    public boolean present, running, turbine, recirculation, eccs, rhr, poolSuction, poolCooling;
    public int control; // 0 panel, 1 redstone, 2 computer
    public double target, actual, flow, pressure, temperature, energy, capacity, buffer;
    public String connection="Waiting for pump", drive="";
    public PumpControlMenu(int id, Inventory inv, RegistryFriendlyByteBuf data) { this(id,inv,data.readBlockPos(),data.readBlockPos()); }
    public PumpControlMenu(int id, Inventory inv, BlockPos root, BlockPos anchor) {
        this(BwrMenus.PUMP_CONTROL.get(),id,inv,root,anchor);
    }
    protected PumpControlMenu(net.minecraft.world.inventory.MenuType<?> type,int id,Inventory inv,BlockPos root,BlockPos anchor) {
        super(type,id,inv,root,inv.player.level().getBlockState(root).getBlock());
        this.anchor=anchor.immutable(); expected=inv.player.level().getBlockState(root).getBlock();
    }
    public static void open(ServerPlayer player, BlockPos root, BlockPos anchor) {
        var be=player.level().getBlockEntity(root);
        if (!(be instanceof EccsPumpBlockEntity || be instanceof FeedwaterPumpBlockEntity || be instanceof RecirculationPumpBlockEntity)) return;
        if(be.getBlockState().getBlock() instanceof TurbineAssemblyBlock) { TerryTurbineMenu.open(player,root,anchor); return; }
        player.openMenu(new SimpleMenuProvider((id,inv,who)->new PumpControlMenu(id,inv,root,anchor),
                be.getBlockState().getBlock().getName()),data->{data.writeBlockPos(root);data.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(Player who) {
        if(!level().isLoaded(pos) || !level().isLoaded(anchor) || who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)>64) return false;
        var root=level().getBlockState(pos); var part=level().getBlockState(anchor);
        if(!root.is(expected) || !part.is(expected)) return false;
        return !(root.getBlock() instanceof ProcessAssembly a) || a.origin(anchor,part).equals(pos) && a.complete(level(),pos,root);
    }
    public void sample() {
        BlockEntity be=level().getBlockEntity(pos);
        eccs=be instanceof EccsPumpBlockEntity; recirculation=be instanceof RecirculationPumpBlockEntity;
        present=eccs || recirculation || be instanceof FeedwaterPumpBlockEntity;
        if(!present)return;
        if(be instanceof RecirculationPumpBlockEntity p) {
            turbine=false;rhr=false;poolSuction=false;poolCooling=false;buffer=0;
            target=p.getTargetSpeedFraction();actual=p.getActualSpeedFraction();running=target>0;
            control=p.isComputerControlled()?2:0;drive="Electric recirculation";
            energy=p.getEnergyStoredFe();capacity=p.getEnergyCapacityFe();flow=p.getCoreFlowContributionKgPerS();
            var c=p.getControllerPos()!=null && level().isLoaded(p.getControllerPos())
                    && level().getBlockEntity(p.getControllerPos()) instanceof ReactorControllerBlockEntity owner && owner.isFormed()?owner:null;
            pressure=c==null?0:c.core().getPressurePsig(); temperature=c==null?Double.NaN:c.core().getCoolantTemperatureC();
            connection=c==null?"Recirculation loop disconnected":"Recirculation loop connected";
        } else {
            var p=eccs?((EccsPumpBlockEntity)be).pump():((FeedwaterPumpBlockEntity)be).pump();
            turbine=p.design().drive()==PumpDesign.Drive.STEAM_TURBINE;
            drive=turbine?"Steam turbine":"Electric motor";
            target=p.getSpeedDemandFraction();actual=p.getSpeedFraction();running=p.isRunning();
            pressure=p.differentialPressurePsi();temperature=p.getSuctionTemperatureC();
            if(be instanceof EccsPumpBlockEntity e) {
                control=e.isComputerControlled()?2:e.isPanelControlled()?0:1;
                rhr=e.design()==dev.bwr.core.eccs.EccsDesign.RHR;
                slc=e.design()==dev.bwr.core.eccs.EccsDesign.SLC;
                poolSuction=e.getSuctionSource()==SuctionSource.SUPPRESSION_POOL;
                poolCooling=e.getMode()==EccsPumpBlockEntity.Mode.POOL_COOLING;
                flow=e.getDeliveredFlowKgPerS(); energy=e.energy().getEnergyStored();capacity=e.energy().getMaxEnergyStored();
                buffer=e.waterInlet().getFluidInTank(0).getAmount();
                connection=e.connectionStatus();
            } else {
                var f=(FeedwaterPumpBlockEntity)be;
                control=f.isComputerControlled()?2:f.isPanelControlled()?0:1;
                rhr=false;poolSuction=false;poolCooling=false;
                flow=f.getDeliveredFlowKgPerS();energy=f.energy().getEnergyStored();capacity=f.energy().getMaxEnergyStored();buffer=f.getSuctionBufferKg();
                connection=f.getReactorPos()==null?"Water discharge disconnected":"Water discharge connected";
            }
        }
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b) {
        sample(); b.writeBoolean(present); if(!present)return;
        b.writeBoolean(running);b.writeBoolean(turbine);b.writeBoolean(recirculation);b.writeBoolean(eccs);b.writeBoolean(rhr);
        b.writeBoolean(poolSuction);b.writeBoolean(poolCooling);b.writeInt(control);
        for(double v:new double[]{target,actual,flow,pressure,temperature,energy,capacity,buffer}) b.writeDouble(v);
        b.writeUtf(connection);b.writeUtf(drive);b.writeBoolean(slc);
    }
    @Override protected void readSnapshot(FriendlyByteBuf b) {
        present=b.readBoolean();if(!present)return;
        running=b.readBoolean();turbine=b.readBoolean();recirculation=b.readBoolean();eccs=b.readBoolean();rhr=b.readBoolean();
        poolSuction=b.readBoolean();poolCooling=b.readBoolean();control=b.readInt();
        target=b.readDouble();actual=b.readDouble();flow=b.readDouble();pressure=b.readDouble();temperature=b.readDouble();energy=b.readDouble();capacity=b.readDouble();buffer=b.readDouble();
        connection=b.readUtf();drive=b.readUtf();slc=b.readBoolean();
    }
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int unused) {
        if(!stillValid(sender))return;
        sample();if(!present || command!=CONTROL && control==2)return;
        BlockEntity be=level().getBlockEntity(pos);
        if(be instanceof RecirculationPumpBlockEntity p) {
            if(command==CONTROL)p.setComputerControlled(a==2);
            if(command==SPEED)p.setTargetSpeedFraction(fromPerMille(a));
            if(command==RUN)p.setTargetSpeedFraction(a==0?0:target>0?target:1);
        } else if(be instanceof EccsPumpBlockEntity p) {
            switch(command) {
                case CONTROL -> {p.setPanelControlled(a==0);p.setComputerControlled(a==2);if(a==1)p.setRunning(level().hasNeighborSignal(pos));}
                case SPEED -> {p.setPanelControlled(true);p.setSpeedDemandFraction(fromPerMille(a));}
                case RUN -> {p.setPanelControlled(true);p.setRunning(a!=0);}
                case SUCTION -> {p.setPanelControlled(true);p.setSuctionSource(a==0?SuctionSource.CONDENSATE_TANK:SuctionSource.SUPPRESSION_POOL);}
                case MODE -> {if(rhr){p.setPanelControlled(true);p.setMode(a==0?EccsPumpBlockEntity.Mode.INJECTION:EccsPumpBlockEntity.Mode.POOL_COOLING);}}
                default -> {return;}
            }
        } else if(be instanceof FeedwaterPumpBlockEntity p) {
            switch(command) {
                case CONTROL -> {p.setPanelControlled(a==0);p.setComputerControlled(a==2);if(a==1)p.setRunning(level().hasNeighborSignal(pos));}
                case SPEED -> {p.setPanelControlled(true);p.setSpeedDemandFraction(fromPerMille(a));}
                case RUN -> {p.setPanelControlled(true);p.setRunning(a!=0);}
                default -> {return;}
            }
        }
        markSnapshotDirty();
    }
}
