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
 * The Oil Filter Housing (TURBINE-AUX.md), a duplex filter: its two cartridge slots, side A and side B, the transfer valve that picks
 * the side in service, and the cartridge in service's life, the pressure drop across it and whether the oil is going round it. The
 * side out of service can be opened and its cartridge changed while the plant runs; the cartridge in service cannot be taken out (the
 * canister is under pressure: throw the transfer valve first).
 */
public class OilFilterMenu extends BwrMenu {
    /** Side A's slot (the one the single-slot housing had) and side B's. */
    public static final int SLOT_CARTRIDGE = 0, SLOT_B = 1;
    public static final int CMD_TRANSFER = 0;
    /** Where the slots sit (the left card has room for them) and where the player's inventory goes, as on the other slot screens. */
    public static final int SLOT_X = 52, SLOT_X_B = 112, SLOT_Y = 100;
    private static final int INVENTORY_X = 8, INVENTORY_Y = 168, HOTBAR_Y = 226;
    private static final int MACHINE_SLOTS = 2, PLAYER_SLOTS = 36;

    public boolean present, attached, cartridge, bypass, changeDue;
    /** The transfer valve: side B in service. The readings below are the side in service; {@code other*} are the side out of service. */
    public boolean sideB, otherCartridge, otherBypass;
    public double otherLife, otherDpPsi;
    /** Cartridge life left (1 new, 0 spent), pressure drop (psid), hours left at today's flow (negative: nothing flows), the share of the unfiltered loss let through. */
    public double life, dpPsi, hoursLeft, factor, oilC, psig;

    /** The slot of the side in service: 0 for A, 1 for B (what the server knows, what the last snapshot said on the client). */
    private int inService() {
        var be = blockEntity(TurbineAuxBlockEntity.class);
        return level().isClientSide() ? (sideB ? 1 : 0) : be == null ? 0 : be.sideInService();
    }

    public OilFilterMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) { this(containerId, inventory, extra.readBlockPos()); }

    public OilFilterMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.OIL_FILTER.get(), containerId, inventory, pos, BwrBlocks.OIL_FILTER_HOUSING.get());
        var be = blockEntity(TurbineAuxBlockEntity.class);
        IItemHandler items = be != null && be.cartridgeSlot() != null ? be.cartridgeSlot() : new ItemStackHandler(2);
        for (int side = 0; side < 2; side++) {
            final int index = side;
            addSlot(new SlotItemHandler(items, index, index == 0 ? SLOT_X : SLOT_X_B, SLOT_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get()); }
                /** The cartridge in service stays: it cannot be taken out while the canister is under pressure. */
                @Override public boolean mayPickup(Player player) { return index != inService() || !hasItem(); }
            });
        }
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
        be.syncStateForScreen();
        b.writeBoolean(tank != null);
        b.writeBoolean(be.hasCartridge());
        b.writeBoolean(be.filterBypass());
        b.writeBoolean(be.filterChangeDue());
        int other = 1 - be.sideInService();
        b.writeBoolean(be.sideBInService());
        b.writeBoolean(be.hasCartridge(other));
        b.writeBoolean(be.filterBypass(other));
        b.writeDouble(be.filterLife(other));
        b.writeDouble(be.filterDpPsi(other));
        double hours = be.filterHoursLeft();
        for (double v : new double[] {be.filterLife(), be.filterDpPsi(), Double.isFinite(hours) ? hours : -1, be.filterFactor(),
                tank == null ? 0 : tank.oilC(), tank == null ? 0 : tank.psig()})
            b.writeDouble(v);
    }

    @Override protected void readSnapshot(FriendlyByteBuf b) {
        present = b.readBoolean();
        if (!present) return;
        attached = b.readBoolean(); cartridge = b.readBoolean(); bypass = b.readBoolean(); changeDue = b.readBoolean();
        sideB = b.readBoolean(); otherCartridge = b.readBoolean(); otherBypass = b.readBoolean(); otherLife = b.readDouble(); otherDpPsi = b.readDouble();
        life = b.readDouble(); dpPsi = b.readDouble(); hoursLeft = b.readDouble(); factor = b.readDouble(); oilC = b.readDouble(); psig = b.readDouble();
    }

    @Override public void handleCommand(ServerPlayer who, int command, int a, int unused) {
        var be = blockEntity(TurbineAuxBlockEntity.class);
        if (!stillValid(who) || be == null) return;
        if (command == CMD_TRANSFER) be.selectSide(a != 0);
        markSnapshotDirty();
    }

    @Override public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size() || !slots.get(index).hasItem()) return ItemStack.EMPTY;
        var slot = slots.get(index);
        ItemStack stack = slot.getItem();
        var be = blockEntity(TurbineAuxBlockEntity.class);
        // The cartridge in service stays where it is.
        if (index < MACHINE_SLOTS && !slot.mayPickup(who)) return ItemStack.EMPTY;
        // A cartridge leaving a slot carries exactly how full it is, not the thousandth its stack was last stamped with.
        if (index < MACHINE_SLOTS && be != null && !level().isClientSide()) OilFilterCartridgeItem.setLoad(stack, be.filterLoad(index));
        ItemStack original = stack.copy();
        boolean moved;
        if (index < MACHINE_SLOTS) {
            moved = moveItemStackTo(stack, MACHINE_SLOTS, MACHINE_SLOTS + PLAYER_SLOTS, true);
        } else if (stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get())) {
            // Into the side out of service first (the way a cartridge is changed with the plant running), then an empty side in service.
            int off = 1 - inService();
            moved = moveItemStackTo(stack, off, off + 1, false) || moveItemStackTo(stack, 1 - off, 2 - off, false);
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
