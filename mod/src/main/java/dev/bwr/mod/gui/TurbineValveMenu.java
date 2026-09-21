package dev.bwr.mod.gui;

import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

public class TurbineValveMenu extends BwrMenu {
    public boolean present,stop;
    public double target,position,flow;
    public TurbineValveMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos());}
    public TurbineValveMenu(int id,Inventory inv,BlockPos pos){super(BwrMenus.TURBINE_VALVE.get(),id,inv,pos,inv.player.level().getBlockState(pos).getBlock());}
    public static void open(ServerPlayer p,BlockPos pos){
        if(!(p.level().getBlockEntity(pos) instanceof TurbineValveBlockEntity v))return;
        p.openMenu(new SimpleMenuProvider((id,inv,who)->new TurbineValveMenu(id,inv,pos),v.getBlockState().getBlock().getName()),b->b.writeBlockPos(pos));
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        var v=blockEntity(TurbineValveBlockEntity.class);b.writeBoolean(v!=null);if(v==null)return;
        b.writeBoolean(v.stopValve());b.writeDouble(v.target());b.writeDouble(v.position());b.writeDouble(v.flowKgPerS());
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){present=b.readBoolean();if(!present)return;stop=b.readBoolean();target=b.readDouble();position=b.readDouble();flow=b.readDouble();}
    @Override public void handleCommand(ServerPlayer who,int command,int a,int unused){
        var v=blockEntity(TurbineValveBlockEntity.class);if(!stillValid(who)||v==null)return;
        if(command==0&&a>=0&&a<=1000&&(!v.stopValve()||a==0||a==1000))v.setTarget(a/1000.0);markSnapshotDirty();
    }
}
