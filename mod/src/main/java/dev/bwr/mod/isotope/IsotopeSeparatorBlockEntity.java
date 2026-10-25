package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.IsotopeExtraction;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The isotope separator: fission-product waste in, strontium-90, caesium-137 and americium-241 capsules and stripped waste
 * out. It is the reprocessing plant's next stage and is built the same way (see {@code ReprocessingPlantBlockEntity}).
 *
 * <h2>Why a machine of its own</h2>
 * The plant's four slots, its screen, its peripheral and its saves are pinned by its own tests and by old worlds, and the
 * separator needs a different input (waste, not an assembly) and different outputs. A second block leaves the plant exactly
 * as it was; a mode on the plant would have changed its slot layout, its menu and its save.
 *
 * <h2>How it works</h2>
 * One waste item at a time: with power it works {@link Isotopes#WORK_TICKS} ticks at {@link Isotopes#ENERGY_PER_TICK}
 * FE a tick (10,000 FE an item); when the time is up the item is consumed and its 1,000,000 grains are split by
 * {@link IsotopeExtraction} into strontium, caesium, americium and stripped waste, which are added to four stores. A whole
 * capsule (50,000 grains, 250 g) or a whole stripped item (1,000,000 grains) is released to its slot only when its store holds
 * one; the rest carries over, and the grains in the stores plus the items out always equal the waste that went in, to the
 * grain. Stripped waste is a different item, so it cannot be fed back in to be mined a second time. Taking the waste out
 * while it works costs nothing: nothing is consumed until the commit.
 *
 * <p>An idle machine costs one boolean test a tick, like the plant. Breaking the block drops the whole items it holds; the
 * fractions of an item are lost with it.
 */
public class IsotopeSeparatorBlockEntity extends BlockEntity {

    public static final int INPUT = 0;
    public static final int STRONTIUM = 1;
    public static final int CAESIUM = 2;
    public static final int AMERICIUM = 3;
    public static final int STRIPPED = 4;
    private static final int SLOTS = 5;

    private static final int ENERGY_CAPACITY = 40_000;
    /** An output store with this many whole items waiting stops the machine starting another waste item. */
    private static final int BACKLOG_LIMIT = 16;

    private long strontiumGrains;
    private long caesiumGrains;
    private long americiumGrains;
    private long strippedGrains;
    private int workTicks;
    private boolean dirty = true;

    private final ItemStackHandler items = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            dirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == INPUT && stack.is(BwrItems.FISSION_PRODUCT_WASTE.get());
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == INPUT || slot == STRIPPED ? 64 : 16;
        }
    };

    private final class MachineEnergy extends EnergyStorage {

        MachineEnergy() {
            super(ENERGY_CAPACITY, ENERGY_CAPACITY / 10, 0);
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int accepted = super.receiveEnergy(amount, simulate);
            if (accepted > 0 && !simulate) {
                setChanged();
            }
            return accepted;
        }

        void drain(int amount) {
            this.energy = Math.max(0, this.energy - amount);
        }

        void setStored(int stored) {
            this.energy = Math.max(0, Math.min(this.capacity, stored));
        }
    }

    private final MachineEnergy energy = new MachineEnergy();
    private final IsotopeSeparatorItemView viewAll = new IsotopeSeparatorItemView(items, true, true);
    private final IsotopeSeparatorItemView viewInputs = new IsotopeSeparatorItemView(items, true, false);
    private final IsotopeSeparatorItemView viewOutputs = new IsotopeSeparatorItemView(items, false, true);

    public IsotopeSeparatorBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.ISOTOPE_SEPARATOR.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, IsotopeSeparatorBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (!dirty && workTicks == 0) {
            return;
        }
        flush();
        ItemStack held = items.getStackInSlot(INPUT);
        if (held.isEmpty() || !held.is(BwrItems.FISSION_PRODUCT_WASTE.get()) || backedUp()) {
            workTicks = 0;
            dirty = false;
            return;
        }
        if (energy.getEnergyStored() < Isotopes.ENERGY_PER_TICK) {
            return;
        }
        if (workTicks == 0) {
            setChanged();
        }
        energy.drain(Isotopes.ENERGY_PER_TICK);
        workTicks++;
        if (workTicks >= Isotopes.WORK_TICKS) {
            workTicks = 0;
            commit();
            setChanged();
        }
    }

    /** Consumes one waste item and adds its split to the stores. */
    private void commit() {
        items.extractItem(INPUT, 1, false);
        addWaste(Isotopes.GRAINS_PER_WASTE_ITEM);
    }

    /** Puts {@code grains} of waste through the split into the stores (the commit; also what the tests drive directly). */
    public void addWaste(long grains) {
        IsotopeExtraction.Split split = IsotopeExtraction.split(grains);
        strontiumGrains += split.strontium();
        caesiumGrains += split.caesium();
        americiumGrains += split.americium();
        strippedGrains += split.stripped();
        flush();
        setChanged();
    }

    private boolean backedUp() {
        return strontiumGrains / Isotopes.CAPSULE_GRAINS >= BACKLOG_LIMIT
                || caesiumGrains / Isotopes.CAPSULE_GRAINS >= BACKLOG_LIMIT
                || americiumGrains / Isotopes.CAPSULE_GRAINS >= BACKLOG_LIMIT
                || strippedGrains / Isotopes.GRAINS_PER_WASTE_ITEM >= 64;
    }

    private void flush() {
        strontiumGrains -= move(STRONTIUM, Isotopes.strontium(), strontiumGrains / Isotopes.CAPSULE_GRAINS) * Isotopes.CAPSULE_GRAINS;
        caesiumGrains -= move(CAESIUM, Isotopes.caesium(), caesiumGrains / Isotopes.CAPSULE_GRAINS) * Isotopes.CAPSULE_GRAINS;
        americiumGrains -= move(AMERICIUM, Isotopes.americium(), americiumGrains / Isotopes.CAPSULE_GRAINS) * Isotopes.CAPSULE_GRAINS;
        strippedGrains -= move(STRIPPED, BwrItems.STRIPPED_WASTE.get(), strippedGrains / Isotopes.GRAINS_PER_WASTE_ITEM) * Isotopes.GRAINS_PER_WASTE_ITEM;
    }

    private long move(int slot, Item item, long count) {
        if (count <= 0) {
            return 0;
        }
        ItemStack held = items.getStackInSlot(slot);
        int limit = items.getSlotLimit(slot);
        int room = held.isEmpty() ? limit : held.is(item) ? limit - held.getCount() : 0;
        int moved = (int) Math.min(count, room);
        if (moved <= 0) {
            return 0;
        }
        items.setStackInSlot(slot, held.isEmpty() ? new ItemStack(item, moved) : held.copyWithCount(held.getCount() + moved));
        return moved;
    }

    // --- Accessors ---------------------------------------------------------

    public ItemStackHandler items() {
        return items;
    }

    public EnergyStorage energy() {
        return energy;
    }

    public IsotopeSeparatorItemView itemView(Direction side) {
        if (side == Direction.UP) {
            return viewInputs;
        }
        return side == Direction.DOWN ? viewOutputs : viewAll;
    }

    public int workTicks() {
        return workTicks;
    }

    public long strontiumGrains() {
        return strontiumGrains;
    }

    public long caesiumGrains() {
        return caesiumGrains;
    }

    public long americiumGrains() {
        return americiumGrains;
    }

    public long strippedGrains() {
        return strippedGrains;
    }

    /** Everything the machine holds as whole items, for when the block is broken. */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack out = items.extractItem(slot, 64, false);
            if (!out.isEmpty()) {
                drops.add(out);
            }
        }
        dropWhole(drops, Isotopes.strontium(), strontiumGrains / Isotopes.CAPSULE_GRAINS);
        dropWhole(drops, Isotopes.caesium(), caesiumGrains / Isotopes.CAPSULE_GRAINS);
        dropWhole(drops, Isotopes.americium(), americiumGrains / Isotopes.CAPSULE_GRAINS);
        dropWhole(drops, BwrItems.STRIPPED_WASTE.get(), strippedGrains / Isotopes.GRAINS_PER_WASTE_ITEM);
        strontiumGrains = 0;
        caesiumGrains = 0;
        americiumGrains = 0;
        strippedGrains = 0;
        workTicks = 0;
        return drops;
    }

    private static void dropWhole(List<ItemStack> drops, Item item, long count) {
        for (long left = count; left > 0; left -= 16) {
            drops.add(new ItemStack(item, (int) Math.min(16, left)));
        }
    }

    public List<String> statusLines() {
        return BwrText.english(statusComponents());
    }

    public List<Component> statusComponents() {
        List<Component> out = new ArrayList<>();
        ItemStack held = items.getStackInSlot(INPUT);
        out.add(held.isEmpty()
                ? BwrText.tr("status.bwr.isotope_separator.empty", "No waste in the separator")
                : BwrText.tr("status.bwr.isotope_separator.input", "%s waste, %s%% of the work done", held.getCount(),
                String.format(Locale.ROOT, "%.0f", 100.0 * workTicks / Isotopes.WORK_TICKS)));
        out.add(BwrText.tr("status.bwr.isotope_separator.held", "Held: %s g Sr-90, %s g Cs-137, %s g Am-241, %s stripped waste",
                String.format(Locale.ROOT, "%.1f", IsotopeExtraction.grams(strontiumGrains)),
                String.format(Locale.ROOT, "%.1f", IsotopeExtraction.grams(caesiumGrains)),
                String.format(Locale.ROOT, "%.1f", IsotopeExtraction.grams(americiumGrains)),
                String.format(Locale.ROOT, "%.3f", strippedGrains / (double) Isotopes.GRAINS_PER_WASTE_ITEM)));
        out.add(BwrText.tr("status.bwr.isotope_separator.fe", "%s / %s FE",
                String.format(Locale.ROOT, "%,d", energy.getEnergyStored()), String.format(Locale.ROOT, "%,d", energy.getMaxEnergyStored())));
        if (backedUp()) {
            out.add(BwrText.tr("status.bwr.isotope_separator.backed_up", "A product is piling up: take the outputs and the separator will go on."));
        }
        return out;
    }

    // --- Persistence -------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy.getEnergyStored());
        if (workTicks > 0) {
            tag.putInt("WorkTicks", workTicks);
        }
        if (strontiumGrains > 0) {
            tag.putLong("StrontiumGrains", strontiumGrains);
        }
        if (caesiumGrains > 0) {
            tag.putLong("CaesiumGrains", caesiumGrains);
        }
        if (americiumGrains > 0) {
            tag.putLong("AmericiumGrains", americiumGrains);
        }
        if (strippedGrains > 0) {
            tag.putLong("StrippedGrains", strippedGrains);
        }
        boolean any = false;
        for (int slot = 0; slot < SLOTS; slot++) {
            any |= !items.getStackInSlot(slot).isEmpty();
        }
        if (any) {
            tag.put("Items", items.serializeNBT(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.setStored(tag.getInt("Energy"));
        workTicks = Math.max(0, Math.min(1_000_000, tag.getInt("WorkTicks")));
        strontiumGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("StrontiumGrains")));
        caesiumGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("CaesiumGrains")));
        americiumGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("AmericiumGrains")));
        strippedGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("StrippedGrains")));
        if (tag.contains("Items")) {
            items.deserializeNBT(registries, tag.getCompound("Items"));
            if (items.getSlots() != SLOTS) {
                items.setSize(SLOTS);
            }
        } else {
            for (int slot = 0; slot < SLOTS; slot++) {
                items.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        dirty = true;
    }
}
