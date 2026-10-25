package dev.bwr.mod.gui;

import dev.bwr.mod.isotope.IsotopeSeparatorBlockEntity;
import dev.bwr.mod.isotope.Isotopes;
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
 * The isotope separator's screen: the waste slot and the four product slots, what one waste item yields, what the
 * machine holds in fractions, progress and stored energy. No commands: it works whenever it has waste and power.
 */
public class IsotopeSeparatorMenu extends BwrMenu {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_STRONTIUM = 1;
    public static final int SLOT_CAESIUM = 2;
    public static final int SLOT_AMERICIUM = 3;
    public static final int SLOT_STRIPPED = 4;
    public static final int SLOT_Y = 100;
    public static final int[] SLOT_X = {18, 52, 80, 108, 142};
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 142;
    private static final int HOTBAR_Y = 200;
    private static final int MACHINE_SLOT_COUNT = 5;
    private static final int PLAYER_SLOT_COUNT = 36;

    public boolean present;
    public int energyStored;
    public int energyCapacity;
    public int workTicks;
    public long strontiumGrains;
    public long caesiumGrains;
    public long americiumGrains;
    public long strippedGrains;

    /** Client constructor. */
    public IsotopeSeparatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public IsotopeSeparatorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.ISOTOPE_SEPARATOR.get(), containerId, inventory, pos, BwrBlocks.ISOTOPE_SEPARATOR.get());
        IsotopeSeparatorBlockEntity be = blockEntity(IsotopeSeparatorBlockEntity.class);
        IItemHandler items = be != null ? be.items() : new ItemStackHandler(MACHINE_SLOT_COUNT);
        addSlot(new SlotItemHandler(items, SLOT_INPUT, SLOT_X[0], SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(BwrItems.FISSION_PRODUCT_WASTE.get());
            }
        });
        for (int slot = 1; slot < MACHINE_SLOT_COUNT; slot++) {
            addSlot(new SlotItemHandler(items, slot, SLOT_X[slot], SLOT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    public static void open(ServerPlayer player, IsotopeSeparatorBlockEntity be) {
        player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new IsotopeSeparatorMenu(id, inventory, be.getBlockPos()),
                Component.translatable("menu.bwr.isotope_separator")), be.getBlockPos());
    }

    private IsotopeSeparatorBlockEntity separator() {
        return blockEntity(IsotopeSeparatorBlockEntity.class);
    }

    /** Work done on the item in the slot, 0..1. */
    public double progress() {
        return clampFraction(workTicks / (double) Isotopes.WORK_TICKS);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        IsotopeSeparatorBlockEntity be = separator();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        buf.writeVarInt(be.energy().getEnergyStored());
        buf.writeVarInt(be.energy().getMaxEnergyStored());
        buf.writeVarInt(be.workTicks());
        buf.writeVarLong(be.strontiumGrains());
        buf.writeVarLong(be.caesiumGrains());
        buf.writeVarLong(be.americiumGrains());
        buf.writeVarLong(be.strippedGrains());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        energyStored = buf.readVarInt();
        energyCapacity = buf.readVarInt();
        workTicks = buf.readVarInt();
        strontiumGrains = buf.readVarLong();
        caesiumGrains = buf.readVarLong();
        americiumGrains = buf.readVarLong();
        strippedGrains = buf.readVarLong();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        // No commands: the separator runs when it has waste and power.
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved;
        if (index < MACHINE_SLOT_COUNT) {
            moved = moveItemStackTo(stack, MACHINE_SLOT_COUNT, MACHINE_SLOT_COUNT + PLAYER_SLOT_COUNT, true);
        } else if (stack.is(BwrItems.FISSION_PRODUCT_WASTE.get())) {
            moved = moveItemStackTo(stack, SLOT_INPUT, SLOT_INPUT + 1, false);
        } else {
            boolean fromHotbar = index >= MACHINE_SLOT_COUNT + 27;
            moved = fromHotbar ? moveItemStackTo(stack, MACHINE_SLOT_COUNT, MACHINE_SLOT_COUNT + 27, false)
                    : moveItemStackTo(stack, MACHINE_SLOT_COUNT + 27, MACHINE_SLOT_COUNT + PLAYER_SLOT_COUNT, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
