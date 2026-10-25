package dev.bwr.mod.gui;

import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.isotope.RtgFuels;
import dev.bwr.mod.registry.BwrBlocks;
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
 * The RTG's screen: the six capsule wells, and what the capsules make (thermal power, electric power, FE per tick),
 * the shortest half-life, the dose at 1 m, and the energy waiting in the buffer. There are no commands: an RTG has no
 * switch. The numbers are the block entity's own readout, sent four times a second.
 */
public class RtgMenu extends BwrMenu {

    public static final int FIRST_SLOT = 0;
    /** Slot positions, relative to the panel. */
    public static final int SLOT_Y = 100;
    public static final int SLOT_X = 24;
    public static final int SLOT_PITCH = 22;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 142;
    private static final int HOTBAR_Y = 200;
    private static final int PLAYER_SLOT_COUNT = 36;

    // --- client-visible snapshot ---------------------------------------

    public boolean present;
    public int capsules;
    public double thermalWatts;
    public double electricWatts;
    public double fePerTick;
    public double doseMsvPerH;
    public double shortestHalfLifeYears;
    public double bufferFe;

    /** Client constructor. */
    public RtgMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public RtgMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.RTG.get(), containerId, inventory, pos, BwrBlocks.RTG.get());
        RtgBlockEntity be = blockEntity(RtgBlockEntity.class);
        IItemHandler fuel = be != null ? be.fuel() : new ItemStackHandler(RtgBlockEntity.SLOTS);
        for (int i = 0; i < RtgBlockEntity.SLOTS; i++) {
            addSlot(new SlotItemHandler(fuel, i, SLOT_X + i * SLOT_PITCH, SLOT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return RtgFuels.isFuel(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    public static void open(ServerPlayer player, RtgBlockEntity be) {
        player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new RtgMenu(id, inventory, be.getBlockPos()),
                Component.translatable("menu.bwr.rtg")), be.getBlockPos());
    }

    private RtgBlockEntity rtg() {
        return blockEntity(RtgBlockEntity.class);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        RtgBlockEntity be = rtg();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        RtgBlockEntity.Readout r = be.readout();
        buf.writeVarInt(r.capsules());
        buf.writeDouble(r.thermalWatts());
        buf.writeDouble(r.electricWatts());
        buf.writeDouble(r.fePerTick());
        buf.writeDouble(r.doseAtOneMetreMsvPerH());
        buf.writeDouble(r.shortestHalfLifeYears());
        buf.writeDouble(be.bufferFe());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        capsules = buf.readVarInt();
        thermalWatts = buf.readDouble();
        electricWatts = buf.readDouble();
        fePerTick = buf.readDouble();
        doseMsvPerH = buf.readDouble();
        shortestHalfLifeYears = buf.readDouble();
        bufferFe = buf.readDouble();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        // No commands: an RTG has no switch.
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int machine = RtgBlockEntity.SLOTS;
        boolean moved;
        if (index < machine) {
            moved = moveItemStackTo(stack, machine, machine + PLAYER_SLOT_COUNT, true);
        } else if (RtgFuels.isFuel(stack)) {
            moved = moveItemStackTo(stack, 0, machine, false);
        } else {
            boolean fromHotbar = index >= machine + 27;
            moved = fromHotbar ? moveItemStackTo(stack, machine, machine + 27, false)
                    : moveItemStackTo(stack, machine + 27, machine + PLAYER_SLOT_COUNT, false);
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
