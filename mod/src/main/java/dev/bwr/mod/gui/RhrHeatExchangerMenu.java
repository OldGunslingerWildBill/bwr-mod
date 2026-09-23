package dev.bwr.mod.gui;

import dev.bwr.mod.registry.*;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

public class RhrHeatExchangerMenu extends BwrMenu {
    public boolean present,connected;
    public double cold,hot,primaryFlow,secondaryFlow,heat,hotTemperature,primaryOut;
    public RhrHeatExchangerMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos());}
    public RhrHeatExchangerMenu(int id,Inventory inv,BlockPos p){super(BwrMenus.RHR_HEAT_EXCHANGER.get(),id,inv,p,BwrBlocks.RHR_HEAT_EXCHANGER.get());}
    public static void open(ServerPlayer p,RhrHeatExchangerBlockEntity be){p.openMenu(new SimpleMenuProvider((id,inv,who)->new RhrHeatExchangerMenu(id,inv,be.getBlockPos()),BwrBlocks.RHR_HEAT_EXCHANGER.get().getName()),b->b.writeBlockPos(be.getBlockPos()));}
    @Override protected void writeSnapshot(FriendlyByteBuf b){var be=blockEntity(RhrHeatExchangerBlockEntity.class);b.writeBoolean(be!=null);if(be==null)return;var h=be.plant();b.writeBoolean(be.returnPool()!=null);for(double n:new double[]{h.cold(),h.hot(),h.primaryFlow(),h.secondaryFlow(),h.heatMW(),h.secondaryOutC(),h.primaryOutC()})b.writeDouble(n);}
    @Override protected void readSnapshot(FriendlyByteBuf b){present=b.readBoolean();if(!present)return;connected=b.readBoolean();cold=b.readDouble();hot=b.readDouble();primaryFlow=b.readDouble();secondaryFlow=b.readDouble();heat=b.readDouble();hotTemperature=b.readDouble();primaryOut=b.readDouble();}
    @Override public void handleCommand(ServerPlayer p,int command,int a,int b){}
}
