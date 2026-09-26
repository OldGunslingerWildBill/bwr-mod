package dev.bwr.mod.gui;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.water.WaterDischargeBlockEntity;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

/** Small hardware panel. The clicked face remains the reach/permission anchor. */
public final class ServiceMenu extends BwrMenu {
    public int kind,division; public boolean active;public List<String> lines=List.of();
    public ServiceMenu(int id,Inventory inv,RegistryFriendlyByteBuf data){this(id,inv,data.readBlockPos());}
    public ServiceMenu(int id,Inventory inv,BlockPos pos){super(BwrMenus.SERVICE.get(),id,inv,pos,inv.player.level().getBlockState(pos).getBlock());}
    public static void open(ServerPlayer p,BlockPos pos){p.openMenu(new SimpleMenuProvider((id,inv,who)->new ServiceMenu(id,inv,pos),p.level().getBlockState(pos).getBlock().getName()),pos);}
    private BlockEntity owner(){var s=level().getBlockState(pos);return s.getBlock() instanceof PumpAssemblyBlock b?b.controller(level(),pos,s):level().getBlockEntity(pos);}
    private static String number(double n){return String.format(Locale.ROOT,"%,.2f",n);}
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        var be=owner();var rows=new ArrayList<String>();int k=0,d=0;boolean on=false;
        if(be instanceof SlcTankBlockEntity t){
            k=1;var s=t.solution();rows.add(t.ready()?"Boron tank assembled":"Tank incomplete");
            rows.add("Solution: "+number(s.massKg())+" / 15,000 kg");rows.add("Dissolved borate: "+number(s.borateKg())+" kg");
            rows.add("Elemental boron: "+number(s.boronFraction()*1e6)+" ppm");rows.add("Water temperature: "+number(s.temperatureC())+" C");
            rows.add("Fill water through the top flange.");rows.add("Use borate charges on the tank to dissolve them.");rows.add("Bottom front flange feeds the SLC pump.");
        }else if(be instanceof AdsControllerBlockEntity a){
            k=2;d=a.getDivision();on=a.isOpen();rows.add("Division: "+d+" | "+(on?"OPEN command":"Closed command"));
            rows.add("Valves: "+a.getHeldOpenCount()+" open / "+a.getValveCount()+" connected");
            rows.add("Nitrogen charge: "+number(a.getNitrogenCharge()*100)+"%");rows.add("Commands: "+(a.isComputerControlled()?"Panel / computer":"Redstone"));
            rows.add("Only matching division valves are commanded.");rows.add("Actuation is manual or player-programmed.");
        }else if(be instanceof SafetyReliefValveBlockEntity v){
            k=3;d=v.getDivision();on=v.isOpen();rows.add("Division: "+d+" | "+(on?"Open":"Closed"));
            rows.add("Commands: "+(v.isComputerControlled()?"Panel / computer / division":"Redstone"));rows.add("Relief outlet: downward to suppression pool");
            rows.add("Steam inlet: left-side flange");
        }else if(be instanceof WaterDischargeBlockEntity w){
            k=4;on=w.enabled();rows.add(w.clearMouth()?"Outfall mouth clear":"Outfall blocked or unloaded");
            rows.add("Water discharged: "+number(w.totalKg())+" kg");rows.add("Flow: "+number(w.flowKgPerS())+" / 60,000 kg/s");
            rows.add("Outlet temperature: "+number(w.temperatureC())+" C");rows.add("Water leaves the plant into the environment.");
        }
        b.writeInt(k);b.writeInt(d);b.writeBoolean(on);b.writeInt(rows.size());for(String row:rows)b.writeUtf(row,160);
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){kind=b.readInt();division=b.readInt();active=b.readBoolean();int n=b.readInt();if(n<0||n>12)throw new IllegalArgumentException();var rows=new ArrayList<String>();for(int i=0;i<n;i++)rows.add(b.readUtf(160));lines=List.copyOf(rows);}
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int unused){
        if(!stillValid(sender))return;
        var be=owner();
        if(be instanceof AdsControllerBlockEntity t){
            if(command==1){t.setComputerControlled(true);t.setOpen(!t.isOpen());}
            if(command==2)t.setDivision(t.getDivision()%4+1);
            if(command==3){t.setComputerControlled(false);t.setOpen(level().hasNeighborSignal(pos));}
        }else if(be instanceof SafetyReliefValveBlockEntity t){
            if(command==1){t.setComputerControlled(true);t.setOpen(!t.isOpen());}
            if(command==2)t.setDivision(t.getDivision()%4+1);
            if(command==3){t.setComputerControlled(false);t.setOpen(level().hasNeighborSignal(pos));}
        }else if(be instanceof WaterDischargeBlockEntity t&&command==1)t.setEnabled(!t.enabled());
        markSnapshotDirty();
    }
}
