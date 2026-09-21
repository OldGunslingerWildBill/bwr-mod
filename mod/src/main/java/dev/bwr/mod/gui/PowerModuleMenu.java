package dev.bwr.mod.gui;

import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.block.Block;

/** Readouts for any part of a large shaft module. */
public class PowerModuleMenu extends BwrMenu {
    private final BlockPos anchor;private final Block expected;
    public boolean present,running,generator,hp;
    public double rpm,flow,shaftMW,electricMW,trainMW,inletP,outletP,inletC,outletC,water,energy;
    public int hpCount,lpCount,generatorCount;
    public String status="Waiting for server";
    public PowerModuleMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos(),b.readBlockPos());}
    public PowerModuleMenu(int id,Inventory inv,BlockPos root,BlockPos anchor){super(BwrMenus.POWER_MODULE.get(),id,inv,root,inv.player.level().getBlockState(root).getBlock());this.anchor=anchor.immutable();expected=inv.player.level().getBlockState(root).getBlock();}
    public static void open(ServerPlayer player,BlockPos root,BlockPos anchor){
        if(!(player.level().getBlockEntity(root) instanceof PowerModuleBlockEntity m))return;
        player.openMenu(new SimpleMenuProvider((id,inv,who)->new PowerModuleMenu(id,inv,root,anchor),m.getBlockState().getBlock().getName()),b->{b.writeBlockPos(root);b.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(Player who){
        if(!level().isLoaded(pos)||!level().isLoaded(anchor)||who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)>64)return false;
        var s=level().getBlockState(anchor);
        return s.is(expected)&&s.getBlock() instanceof PowerModuleBlock b&&b.origin(anchor,s).equals(pos)&&b.complete(level(),pos,s);
    }
    public void sample(){
        present=level().getBlockEntity(pos) instanceof PowerModuleBlockEntity;if(!present)return;
        var m=(PowerModuleBlockEntity)level().getBlockEntity(pos);
        running=m.running;generator=m.block().generator();hp=m.block().highPressure();rpm=m.rpm;
        flow=m.flowKgPerS;shaftMW=m.shaftMW;electricMW=m.electricMW;trainMW=m.trainMW;inletP=m.inletPsia;outletP=m.outletPsia;
        inletC=m.inletC;outletC=m.outletC;water=m.waterStored();energy=m.energyStored();hpCount=m.hpCount;lpCount=m.lpCount;generatorCount=m.generatorCount;status=m.status;
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        sample();b.writeBoolean(present);if(!present)return;
        b.writeBoolean(running);b.writeBoolean(generator);b.writeBoolean(hp);
        for(double v:new double[]{rpm,flow,shaftMW,electricMW,trainMW,inletP,outletP,inletC,outletC,water,energy})b.writeDouble(v);
        b.writeInt(hpCount);b.writeInt(lpCount);b.writeInt(generatorCount);b.writeUtf(status);
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){
        present=b.readBoolean();if(!present)return;running=b.readBoolean();generator=b.readBoolean();hp=b.readBoolean();
        rpm=b.readDouble();flow=b.readDouble();shaftMW=b.readDouble();electricMW=b.readDouble();trainMW=b.readDouble();inletP=b.readDouble();outletP=b.readDouble();inletC=b.readDouble();outletC=b.readDouble();water=b.readDouble();energy=b.readDouble();
        hpCount=b.readInt();lpCount=b.readInt();generatorCount=b.readInt();status=b.readUtf();
    }
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int unused){
        // Turbine sections have no local actuator; use the upstream steam valve.
    }
}
