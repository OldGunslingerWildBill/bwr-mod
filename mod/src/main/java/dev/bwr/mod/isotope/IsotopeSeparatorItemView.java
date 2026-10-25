package dev.bwr.mod.isotope;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * What a hopper or pipe sees of an isotope separator: five slots,
 *
 * <pre>
 *   0  fission-product waste  in
 *   1  strontium-90 capsules  out
 *   2  caesium-137 capsules   out
 *   3  americium-241 capsules out
 *   4  stripped waste         out
 * </pre>
 *
 * The reprocessing plant's sides: the top shows only the input, the bottom only the outputs, the four sides and the
 * unsided query all five. Outputs can only be taken, the input only given, and the input takes only fission-product waste.
 */
public final class IsotopeSeparatorItemView implements IItemHandler {

    private final ItemStackHandler items;
    private final boolean inputs;
    private final boolean outputs;

    IsotopeSeparatorItemView(ItemStackHandler items, boolean inputs, boolean outputs) {
        this.items = items;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    private boolean visible(int slot) {
        return slot == IsotopeSeparatorBlockEntity.INPUT ? inputs : outputs;
    }

    @Override
    public int getSlots() {
        return items.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slot < 0 || slot >= items.getSlots() || !visible(slot) ? ItemStack.EMPTY : items.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot != IsotopeSeparatorBlockEntity.INPUT || !visible(slot)) {
            return stack;
        }
        return items.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot <= IsotopeSeparatorBlockEntity.INPUT || slot >= items.getSlots() || !visible(slot)) {
            return ItemStack.EMPTY;
        }
        return items.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return slot < 0 || slot >= items.getSlots() ? 0 : items.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == IsotopeSeparatorBlockEntity.INPUT && visible(slot) && items.isItemValid(slot, stack);
    }
}
