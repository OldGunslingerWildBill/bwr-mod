package dev.bwr.mod.fuel;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * What a hopper or pipe sees of a fuel fabricator: four slots, in a fixed order
 * that keeps the assembly in slot 0 (where the only slot used to be, so a pipe
 * set up before the item route existed still extracts the assembly).
 *
 * <pre>
 *   0  assembly  out
 *   1  depleted uranium  out
 *   2  uranium   in
 *   3  steel     in
 * </pre>
 *
 * <p>Outputs can only be taken, inputs can only be given, from every side; the
 * inputs cannot be pulled back out by a pipe (the GUI can). The top face shows
 * only the inputs and the bottom face only the outputs, so a hopper above feeds
 * the machine and a hopper below collects from it; the four sides and the
 * unsided query see all four.
 */
public final class FabricatorItemView implements IItemHandler {

    public static final int ASSEMBLY = 0;
    public static final int TAILS = 1;
    public static final int URANIUM = 2;
    public static final int STEEL = 3;

    private final ItemStackHandler assembly;
    private final ItemStackHandler tails;
    private final ItemStackHandler uranium;
    private final ItemStackHandler steel;
    private final boolean inputs;
    private final boolean outputs;

    FabricatorItemView(ItemStackHandler assembly, ItemStackHandler tails, ItemStackHandler uranium,
                       ItemStackHandler steel, boolean inputs, boolean outputs) {
        this.assembly = assembly;
        this.tails = tails;
        this.uranium = uranium;
        this.steel = steel;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    private ItemStackHandler handler(int slot) {
        return switch (slot) {
            case ASSEMBLY -> assembly;
            case TAILS -> tails;
            case URANIUM -> uranium;
            case STEEL -> steel;
            default -> null;
        };
    }

    private boolean visible(int slot) {
        return slot == ASSEMBLY || slot == TAILS ? outputs : inputs;
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
        if (h == null || !visible(slot) || slot == ASSEMBLY || slot == TAILS) {
            return stack;
        }
        return h.insertItem(0, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStackHandler h = handler(slot);
        if (h == null || !visible(slot) || slot == URANIUM || slot == STEEL) {
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
        return h != null && visible(slot) && h.isItemValid(0, stack);
    }
}
