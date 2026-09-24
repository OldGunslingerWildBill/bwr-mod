package dev.bwr.mod.gui;

import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.registry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;

/** Reachability uses the clicked assembly part rather than its distant foundation. */
public class CondenserMenu extends BwrMenu {
    private final BlockPos anchor;
    public boolean present, ready;
    public double cold,hot,condensate,steam,steamRate,coolingRate,rejectedMW,coldC,hotC,condensateC,pressure,vacuum;
    public CondenserMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos(),b.readBlockPos());}
    public CondenserMenu(int id,Inventory inv,BlockPos root,BlockPos anchor){
        super(BwrMenus.CONDENSER.get(),id,inv,root,BwrBlocks.ARABELLE_CONDENSER.get());this.anchor=anchor.immutable();
    }
    public static void open(ServerPlayer player,BlockPos root,BlockPos anchor){
        player.openMenu(new SimpleMenuProvider((id,inv,p)->new CondenserMenu(id,inv,root,anchor),
                BwrBlocks.ARABELLE_CONDENSER.get().getName()),b->{b.writeBlockPos(root);b.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(Player who){
        return level().isLoaded(pos)&&level().isLoaded(anchor)&&who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)<=64
                &&level().getBlockEntity(anchor) instanceof CondenserBlockEntity part&&part.owner()!=null&&part.owner().getBlockPos().equals(pos);
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        var be=blockEntity(CondenserBlockEntity.class);present=be!=null&&be.plant()!=null;b.writeBoolean(present);if(!present)return;
        var p=be.plant();b.writeBoolean(be.ready());
        for(double n:new double[]{p.cold(),p.hot(),p.condensate(),p.steam.mass(),p.steamRate(),p.coolingRate(),p.rejectedMW(),p.coldC(),p.hotC(),p.condensateC(),p.backpressurePsia(),p.vacuumInHg()})b.writeDouble(n);
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){
        present=b.readBoolean();if(!present)return;ready=b.readBoolean();cold=b.readDouble();hot=b.readDouble();condensate=b.readDouble();
        steam=b.readDouble();steamRate=b.readDouble();coolingRate=b.readDouble();rejectedMW=b.readDouble();coldC=b.readDouble();hotC=b.readDouble();condensateC=b.readDouble();pressure=b.readDouble();vacuum=b.readDouble();
    }
    @Override public void handleCommand(ServerPlayer p,int command,int a,int b){} // Read-only instrumentation.
}
