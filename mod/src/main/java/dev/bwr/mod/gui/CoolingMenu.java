package dev.bwr.mod.gui;
import dev.bwr.mod.cooling.*;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
public class CoolingMenu extends BwrMenu {
    private final BlockPos anchor;public boolean present,ready,submerged;public Design design=Design.NATURAL;
    public double input,output,flow,loss,heat,target,actual;public int energy,draw;
    public CoolingMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos(),b.readBlockPos());}
    public CoolingMenu(int id,Inventory inv,BlockPos p,BlockPos anchor){super(BwrMenus.COOLING.get(),id,inv,p,inv.player.level().getBlockState(p).getBlock());this.anchor=anchor.immutable();}
    public static void open(ServerPlayer player,BlockPos root,BlockPos anchor){
        player.openMenu(new SimpleMenuProvider((id,inv,p)->new CoolingMenu(id,inv,root,anchor),player.level().getBlockState(root).getBlock().getName()),b->{b.writeBlockPos(root);b.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(Player who){return level().isLoaded(pos)&&level().isLoaded(anchor)&&who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)<=64&&level().getBlockEntity(anchor) instanceof CoolingBlockEntity part&&part.owner()!=null&&part.root().equals(pos);}
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        var be=blockEntity(CoolingBlockEntity.class);present=be!=null&&be.plant()!=null;b.writeBoolean(present);if(!present)return;
        var u=be.plant();b.writeEnum(be.design());b.writeBoolean(be.ready());b.writeBoolean(be.design()==Design.INTAKE&&be.submerged());
        for(double n:new double[]{u.input(),u.output(),u.flow(),u.loss(),u.heatMW(),be.target(),be.actual()})b.writeDouble(n);
        b.writeInt(be.storedFE());b.writeInt(be.draw());
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){
        present=b.readBoolean();if(!present)return;design=b.readEnum(Design.class);ready=b.readBoolean();submerged=b.readBoolean();
        input=b.readDouble();output=b.readDouble();flow=b.readDouble();loss=b.readDouble();heat=b.readDouble();target=b.readDouble();actual=b.readDouble();energy=b.readInt();draw=b.readInt();
    }
    @Override public void handleCommand(ServerPlayer who,int command,int a,int b){if(command==0&&a>=0&&a<=1000){var be=blockEntity(CoolingBlockEntity.class);if(be!=null)be.setTarget(a/1000.0);markSnapshotDirty();}}
}
