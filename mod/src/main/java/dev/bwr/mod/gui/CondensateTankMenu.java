package dev.bwr.mod.gui;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/** The clicked part stays the reach anchor; reads and commands resolve its owner. */
public class CondensateTankMenu extends BwrMenu {
    public boolean present,assembled,ready;public double stored,capacity,temperature;
    public int diameter=7,height=8,blocks=1;public String notice="";
    public CondensateTankMenu(int id,Inventory inv,RegistryFriendlyByteBuf data){this(id,inv,data.readBlockPos());}
    public CondensateTankMenu(int id,Inventory inv,BlockPos pos){super(BwrMenus.CONDENSATE_TANK.get(),id,inv,pos,BwrBlocks.CONDENSATE_STORAGE_TANK.get());}
    public static void open(ServerPlayer player,BlockPos pos){player.openMenu(new SimpleMenuProvider((id,inv,p)->new CondensateTankMenu(id,inv,pos),BwrBlocks.CONDENSATE_STORAGE_TANK.get().getName()),pos);}
    private CondensateStorageTankBlockEntity tank(){var part=blockEntity(CondensateStorageTankBlockEntity.class);return part==null?null:part.owner();}
    @Override protected void writeSnapshot(FriendlyByteBuf b){var tank=tank();b.writeBoolean(tank!=null);if(tank!=null){b.writeDouble(tank.storedKg());b.writeDouble(tank.capacityKg());b.writeDouble(tank.temperatureC());b.writeBoolean(tank.assembled());b.writeBoolean(tank.ready());b.writeInt(tank.diameter);b.writeInt(tank.height);b.writeInt(tank.paidBlocks);b.writeUtf(notice,256);}}
    @Override protected void readSnapshot(FriendlyByteBuf b){present=b.readBoolean();if(present){stored=b.readDouble();capacity=b.readDouble();temperature=b.readDouble();assembled=b.readBoolean();ready=b.readBoolean();diameter=b.readInt();height=b.readInt();blocks=b.readInt();notice=b.readUtf(256);}}
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int b){ /* Tank size comes from placed blocks. */ }
}
