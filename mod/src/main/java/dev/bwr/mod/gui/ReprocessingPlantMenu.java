package dev.bwr.mod.gui;

import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
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
 * The reprocessing plant's screen: the slot for the spent assembly, the three product slots, what the assembly in the
 * slot will yield (worked out on the client from the item itself, through the same core arithmetic the plant uses),
 * what the plant holds in fractions, progress and stored energy.
 *
 * <p>There are no commands: the plant works whenever it has an irradiated assembly and power.
 */
public class ReprocessingPlantMenu extends BwrMenu {

    /** The four machine slots, in the order they were added to this menu. */
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_PLUTONIUM = 1;
    public static final int SLOT_URANIUM = 2;
    public static final int SLOT_WASTE = 3;

    /** Slot positions, relative to the panel; the screen's captions are laid out to sit over them. */
    public static final int SLOT_Y = 100;
    public static final int INPUT_X = 26;
    public static final int PLUTONIUM_X = 63;
    public static final int URANIUM_X = 100;
    public static final int WASTE_X = 137;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 142;
    private static final int HOTBAR_Y = 200;

    private static final int MACHINE_SLOT_COUNT = 4;
    private static final int PLAYER_SLOT_COUNT = 36;

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public int energyStored;
    public int energyCapacity;
    public int workTicks;
    /** What the plant holds, in grains: whole items waiting for room and the fractions (see {@link SpentFuel}). */
    public long plutoniumGrains;
    public long uraniumGrains;
    public long wasteGrains;

    /** Client constructor. */
    public ReprocessingPlantMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public ReprocessingPlantMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.REPROCESSING_PLANT.get(), containerId, inventory, pos, BwrBlocks.REPROCESSING_PLANT.get());

        ReprocessingPlantBlockEntity be = blockEntity(ReprocessingPlantBlockEntity.class);
        IItemHandler input = be != null ? be.input() : new ItemStackHandler(1);
        IItemHandler plutonium = be != null ? be.plutoniumOut() : new ItemStackHandler(1);
        IItemHandler uranium = be != null ? be.uraniumOut() : new ItemStackHandler(1);
        IItemHandler waste = be != null ? be.wasteOut() : new ItemStackHandler(1);
        // The input slot takes only an irradiated assembly the plant can reprocess (a fresh one is refused here as everywhere).
        addSlot(new SlotItemHandler(input, 0, INPUT_X, SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return Reprocessing.accepts(stack);
            }
        });
        addSlot(new OutputOnly(plutonium, PLUTONIUM_X));
        addSlot(new OutputOnly(uranium, URANIUM_X));
        addSlot(new OutputOnly(waste, WASTE_X));
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    private static final class OutputOnly extends SlotItemHandler {

        OutputOnly(IItemHandler handler, int x) {
            super(handler, 0, x, SLOT_Y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false; // products appear here; nothing goes in
        }
    }

    public static void open(ServerPlayer player, ReprocessingPlantBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new ReprocessingPlantMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.reprocessing_plant")),
                be.getBlockPos());
    }

    private ReprocessingPlantBlockEntity plant() {
        return blockEntity(ReprocessingPlantBlockEntity.class);
    }

    /** The assembly in the input slot, or empty. */
    public ItemStack assembly() {
        return getSlot(SLOT_INPUT).getItem();
    }

    /**
     * What the assembly in the slot will split into, in grains, or null when there is none or it would be refused.
     * Computed from the item with the same arithmetic the block entity runs.
     */
    public SpentFuel.Split expectedSplit() {
        ItemStack held = assembly();
        if (!Reprocessing.accepts(held)) {
            return null;
        }
        FuelAssemblyData data = FuelAssemblies.dataOf(held);
        if (!data.equals(cachedData)) {
            cachedData = data;
            cachedSplit = Reprocessing.split(data);
        }
        return cachedSplit;
    }

    private FuelAssemblyData cachedData;
    private SpentFuel.Split cachedSplit;

    /** Decay power of the assembly in the input slot now, W, worked out on this side from the item and the game time. */
    public double assemblyDecayWatts() {
        var level = level();
        return level == null ? 0.0 : dev.bwr.mod.fuel.SpentFuelHeat.decayWatts(assembly(), level.getGameTime());
    }

    /** True when the assembly in the slot is above the plant's decay heat limit and the plant has not started on it. */
    public boolean assemblyTooHot() {
        var level = level();
        return level != null && workTicks == 0 && Reprocessing.accepts(assembly())
                && dev.bwr.mod.fuel.SpentFuelHeat.tooHotToReprocess(assembly(), level.getGameTime());
    }

    /** Seconds until the assembly in the slot is cool enough, from the item and the game time. */
    public double assemblySecondsUntilCool() {
        var level = level();
        return level == null ? 0.0 : dev.bwr.mod.fuel.SpentFuelHeat.secondsUntilReprocessable(assembly(), level.getGameTime());
    }

    /** Work done on the assembly in the slot, 0..1. */
    public double progress() {
        return clampFraction(workTicks / (double) Reprocessing.WORK_TICKS);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        ReprocessingPlantBlockEntity be = plant();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        buf.writeVarInt(be.energy().getEnergyStored());
        buf.writeVarInt(be.energy().getMaxEnergyStored());
        buf.writeVarInt(be.workTicks());
        buf.writeVarLong(be.plutoniumGrains());
        buf.writeVarLong(be.uraniumGrains());
        buf.writeVarLong(be.wasteGrains());
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
        plutoniumGrains = buf.readVarLong();
        uraniumGrains = buf.readVarLong();
        wasteGrains = buf.readVarLong();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        // No commands: the plant runs when it has an irradiated assembly and power.
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < MACHINE_SLOT_COUNT) {
            if (!moveItemStackTo(stack, MACHINE_SLOT_COUNT, MACHINE_SLOT_COUNT + PLAYER_SLOT_COUNT, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else {
            // Only an irradiated assembly goes to the input slot; anything else moves between hotbar and main.
            boolean moved;
            if (Reprocessing.accepts(stack)) {
                moved = moveItemStackTo(stack, SLOT_INPUT, SLOT_INPUT + 1, false);
            } else {
                boolean fromHotbar = index >= MACHINE_SLOT_COUNT + 27;
                moved = fromHotbar
                        ? moveItemStackTo(stack, MACHINE_SLOT_COUNT, MACHINE_SLOT_COUNT + 27, false)
                        : moveItemStackTo(stack, MACHINE_SLOT_COUNT + 27, MACHINE_SLOT_COUNT + PLAYER_SLOT_COUNT, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
