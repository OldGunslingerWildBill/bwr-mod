package dev.bwr.mod.fuel;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * What a hopper or pipe sees of a reprocessing plant: four slots,
 *
 * <pre>
 *   0  spent assembly  in
 *   1  plutonium nuggets  out
 *   2  depleted uranium  out
 *   3  fission-product waste  out
 * </pre>
 *
 * <p>The sides are the fabricator's: the top face shows only the input and the bottom face only the outputs, so a
 * hopper above feeds the plant and a hopper below collects from it; the four sides and the unsided query see all four.
 * Outputs can only be taken, the input can only be given (a pipe cannot pull the assembly back out; the screen can),
 * and the input slot only accepts an irradiated assembly the plant can reprocess: a fresh one is refused here as
 * everywhere.
 */
public final class ReprocessingItemView implements IItemHandler {

    public static final int INPUT = 0;
    public static final int PLUTONIUM = 1;
    public static final int URANIUM = 2;
    public static final int WASTE = 3;

    private final ItemStackHandler input;
    private final ItemStackHandler plutonium;
    private final ItemStackHandler uranium;
    private final ItemStackHandler waste;
    private final boolean inputs;
    private final boolean outputs;

    ReprocessingItemView(ItemStackHandler input, ItemStackHandler plutonium, ItemStackHandler uranium,
                         ItemStackHandler waste, boolean inputs, boolean outputs) {
        this.input = input;
        this.plutonium = plutonium;
        this.uranium = uranium;
        this.waste = waste;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    private ItemStackHandler handler(int slot) {
        return switch (slot) {
            case INPUT -> input;
            case PLUTONIUM -> plutonium;
            case URANIUM -> uranium;
            case WASTE -> waste;
            default -> null;
        };
    }

    private boolean visible(int slot) {
        return slot == INPUT ? inputs : outputs;
    }

    @Override
    public int getSlots() {
        return 4;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemStackHandler h = handler(slot);
        return h == null || !visible(slot) ? ItemStack.EMPTY : h.getStackInSlot(0);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemStackHandler h = handler(slot);
        if (h == null || slot != INPUT || !visible(slot)) {
            return stack;
        }
        return h.insertItem(0, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStackHandler h = handler(slot);
        if (h == null || slot == INPUT || !visible(slot)) {
            return ItemStack.EMPTY;
        }
        return h.extractItem(0, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStackHandler h = handler(slot);
        return h == null ? 0 : h.getSlotLimit(0);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        ItemStackHandler h = handler(slot);
        return h != null && slot == INPUT && visible(slot) && h.isItemValid(0, stack);
    }
}
