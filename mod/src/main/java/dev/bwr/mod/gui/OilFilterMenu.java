package dev.bwr.mod.gui;

import dev.bwr.mod.power.OilFilterCartridgeItem;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * The Oil Filter Housing (TURBINE-AUX.md): its one cartridge slot, and the cartridge's life, the pressure drop across it and
 * whether the oil is going round it. Readings only; the one thing to do here is change the cartridge.
 */
public class OilFilterMenu extends BwrMenu {
    public static final int SLOT_CARTRIDGE = 0;
    /** Where the slot sits (the left card has room for it) and where the player's inventory goes, as on the other slot screens. */
    public static final int SLOT_X = 82, SLOT_Y = 100;
    private static final int INVENTORY_X = 8, INVENTORY_Y = 142, HOTBAR_Y = 200;
    private static final int MACHINE_SLOTS = 1, PLAYER_SLOTS = 36;

    public boolean present, attached, cartridge, bypass, changeDue;
    /** Cartridge life left (1 new, 0 spent), pressure drop (psid), hours left at today's flow (negative: nothing flows), the share of the unfiltered loss let through. */
    public double life, dpPsi, hoursLeft, factor, oilC, psig;

    public OilFilterMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) { this(containerId, inventory, extra.readBlockPos()); }

    public OilFilterMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.OIL_FILTER.get(), containerId, inventory, pos, BwrBlocks.OIL_FILTER_HOUSING.get());
        var be = blockEntity(TurbineAuxBlockEntity.class);
        IItemHandler items = be != null && be.cartridgeSlot() != null ? be.cartridgeSlot() : new ItemStackHandler(1);
        addSlot(new SlotItemHandler(items, SLOT_CARTRIDGE, SLOT_X, SLOT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get()); }
        });
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof TurbineAuxBlockEntity be)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new OilFilterMenu(id, inventory, pos), Component.translatable("block.bwr.oil_filter_housing")),
                b -> b.writeBlockPos(pos));
    }

    @Override protected void writeSnapshot(FriendlyByteBuf b) {
        var be = blockEntity(TurbineAuxBlockEntity.class);
        b.writeBoolean(be != null);
        if (be == null) return;
        var tank = be.tank();
        b.writeBoolean(tank != null);
        b.writeBoolean(be.hasCartridge());
        b.writeBoolean(be.filterBypass());
        b.writeBoolean(be.filterChangeDue());
        double hours = be.filterHoursLeft();
        for (double v : new double[] {be.filterLife(), be.filterDpPsi(), Double.isFinite(hours) ? hours : -1, be.filterFactor(),
                tank == null ? 0 : tank.oilC(), tank == null ? 0 : tank.psig()})
            b.writeDouble(v);
    }

    @Override protected void readSnapshot(FriendlyByteBuf b) {
        present = b.readBoolean();
        if (!present) return;
        attached = b.readBoolean(); cartridge = b.readBoolean(); bypass = b.readBoolean(); changeDue = b.readBoolean();
        life = b.readDouble(); dpPsi = b.readDouble(); hoursLeft = b.readDouble(); factor = b.readDouble(); oilC = b.readDouble(); psig = b.readDouble();
    }

    @Override public void handleCommand(ServerPlayer who, int command, int a, int unused) {
        // No commands: the slot is the control.
    }

    @Override public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size() || !slots.get(index).hasItem()) return ItemStack.EMPTY;
        var slot = slots.get(index);
        ItemStack stack = slot.getItem();
        var be = blockEntity(TurbineAuxBlockEntity.class);
        // A cartridge leaving the slot carries exactly how full it is, not the thousandth its stack was last stamped with.
        if (index < MACHINE_SLOTS && be != null && !level().isClientSide()) OilFilterCartridgeItem.setLoad(stack, be.filterLoad());
        ItemStack original = stack.copy();
        boolean moved;
        if (index < MACHINE_SLOTS) {
            moved = moveItemStackTo(stack, MACHINE_SLOTS, MACHINE_SLOTS + PLAYER_SLOTS, true);
        } else if (stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get())) {
            moved = moveItemStackTo(stack, SLOT_CARTRIDGE, SLOT_CARTRIDGE + 1, false);
        } else {
            boolean fromHotbar = index >= MACHINE_SLOTS + 27;
            moved = fromHotbar ? moveItemStackTo(stack, MACHINE_SLOTS, MACHINE_SLOTS + 27, false)
                    : moveItemStackTo(stack, MACHINE_SLOTS + 27, MACHINE_SLOTS + PLAYER_SLOTS, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }
}
