package dev.bwr.mod.gui;

import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.registry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/** Read-only view of the actual finite condensate inventory. */
public class CondensateTankMenu extends BwrMenu {
    public boolean present;
    public double stored,capacity,temperature;
    public CondensateTankMenu(int id,Inventory inv,RegistryFriendlyByteBuf data){this(id,inv,data.readBlockPos());}
    public CondensateTankMenu(int id,Inventory inv,BlockPos pos){super(BwrMenus.CONDENSATE_TANK.get(),id,inv,pos,BwrBlocks.CONDENSATE_STORAGE_TANK.get());}
    public static void open(ServerPlayer player,BlockPos pos){player.openMenu(new SimpleMenuProvider((id,inv,p)->new CondensateTankMenu(id,inv,pos),BwrBlocks.CONDENSATE_STORAGE_TANK.get().getName()),pos);}
    @Override protected void writeSnapshot(FriendlyByteBuf b){var tank=blockEntity(CondensateStorageTankBlockEntity.class);b.writeBoolean(tank!=null);if(tank!=null){b.writeDouble(tank.storedKg());b.writeDouble(tank.capacityKg());b.writeDouble(CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C);}}
    @Override protected void readSnapshot(FriendlyByteBuf b){present=b.readBoolean();if(present){stored=b.readDouble();capacity=b.readDouble();temperature=b.readDouble();}}
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int b){}
}
