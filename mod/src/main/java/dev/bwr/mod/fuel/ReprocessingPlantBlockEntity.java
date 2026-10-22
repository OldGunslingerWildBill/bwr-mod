package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.SpentFuel;
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
 * The reprocessing plant: a spent fuel assembly in, plutonium, depleted uranium and fission-product waste out.
 *
 * <h2>How it works</h2>
 * One assembly at a time. With power and an irradiated assembly in the input slot it works for
 * {@link Reprocessing#WORK_TICKS} ticks at {@link Reprocessing#ENERGY_PER_TICK} FE a tick, and when the time is up it
 * commits: the assembly is consumed and its split ({@link Reprocessing#split}, the core's {@link SpentFuel}) is added to
 * three fractional stores, one for each product. A fresh assembly (no burnup) and a thorium assembly are refused at the
 * slot, and are never worked on even if one gets in some other way. Taking the assembly out while it works costs
 * nothing: nothing is consumed until the commit.
 *
 * <h2>Nothing rounds into or out of existence</h2>
 * An assembly is four ingots of heavy metal, which is 36 nuggets, and spent fuel holds a fraction of a nugget of
 * plutonium (about a quarter of one for LEU at 45 GWd/t). The stores keep the three products in grains, a millionth of
 * a nugget ({@link SpentFuel}); a whole item is released to its output slot only when its store holds a whole item
 * (a nugget or a waste item is 1,000,000 grains, an ingot of uranium nine times that), and the remainder stays in the
 * store and carries into the next assembly. The three splits of an assembly sum to its heavy metal exactly, so across
 * any number of assemblies the items out plus what the stores hold equal the heavy metal that went in, to the grain.
 *
 * <p>The items that are due but do not fit (an output slot holds 64) wait in their store, and the plant does not start
 * another assembly while a store holds a full stack's worth of one product. Nothing is voided: breaking the block drops
 * the items it holds, including the whole items waiting in the stores; only the fractions of an item are lost, as a
 * partly filled pile of grains has no item to drop.
 *
 * <p>An idle plant costs one boolean test a tick: it looks at its slots only when something about them changed.
 */
public class ReprocessingPlantBlockEntity extends BlockEntity {

    /** Buffer, FE: three assemblies' worth of work. */
    private static final int ENERGY_CAPACITY = 100_000;

    /** Stack size of the three products. */
    private static final int OUTPUT_STACK = 64;

    /** A product with this many whole items waiting to move into a full slot stops the plant starting another assembly. */
    private static final int BACKLOG_LIMIT = OUTPUT_STACK;

    private long plutoniumGrains;
    private long uraniumGrains;
    private long wasteGrains;
    private int workTicks;
    private boolean dirty = true;
    /** The assembly in the slot is above the decay heat limit: look again once a second until it has cooled. */
    private boolean waitingHot;

    private final ItemStackHandler input = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            dirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return Reprocessing.accepts(stack);
        }
    };

    /** An output slot: products appear here, nothing may be put in. */
    private final class OutputSlot extends ItemStackHandler {

        OutputSlot() {
            super(1);
        }

        @Override
        protected void onContentsChanged(int slot) {
            dirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }

    private final ItemStackHandler plutoniumOut = new OutputSlot();
    private final ItemStackHandler uraniumOut = new OutputSlot();
    private final ItemStackHandler wasteOut = new OutputSlot();

    /** Accepts energy from outside and gives none back: a machine, not a battery. */
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

    private final ReprocessingItemView viewAll = new ReprocessingItemView(input, plutoniumOut, uraniumOut, wasteOut, true, true);
    private final ReprocessingItemView viewInputs = new ReprocessingItemView(input, plutoniumOut, uraniumOut, wasteOut, true, false);
    private final ReprocessingItemView viewOutputs = new ReprocessingItemView(input, plutoniumOut, uraniumOut, wasteOut, false, true);

    public ReprocessingPlantBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.REPROCESSING_PLANT.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ReprocessingPlantBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (!dirty && workTicks == 0 && !waitingHot) {
            return;
        }
        if (waitingHot && !dirty && workTicks == 0 && level != null && level.getGameTime() % SpentFuelHeat.TICKS_PER_SECOND != 0) {
            return;
        }
        flush();
        ItemStack held = input.getStackInSlot(0);
        if (!Reprocessing.accepts(held) || backedUp()) {
            // nothing to do, or a product is piling up: wait for the slots to change
            workTicks = 0;
            dirty = false;
            waitingHot = false;
            return;
        }
        if (workTicks == 0 && isTooHot()) {
            // Too hot to take apart: the plant does not start on it. The decay heat only falls, so once it has
            // started an assembly is never stopped; this looks again once a second.
            waitingHot = true;
            dirty = false;
            return;
        }
        waitingHot = false;
        if (energy.getEnergyStored() < Reprocessing.ENERGY_PER_TICK) {
            return;
        }
        if (workTicks == 0) {
            setChanged();
        }
        energy.drain(Reprocessing.ENERGY_PER_TICK);
        workTicks++;
        if (workTicks >= Reprocessing.WORK_TICKS) {
            workTicks = 0;
            commit(held);
            setChanged();
        }
    }

    /** Consumes the assembly and adds what it held to the stores. */
    private void commit(ItemStack assembly) {
        SpentFuel.Split split = Reprocessing.split(FuelAssemblies.dataOf(assembly));
        input.setStackInSlot(0, ItemStack.EMPTY);
        plutoniumGrains += split.plutonium();
        uraniumGrains += split.uranium();
        wasteGrains += split.fissionProducts();
        flush();
    }

    /** True while a product has a full stack's worth of items waiting for room. */
    private boolean backedUp() {
        return plutoniumGrains / Reprocessing.GRAINS_PER_NUGGET >= BACKLOG_LIMIT
                || uraniumGrains / Reprocessing.GRAINS_PER_INGOT >= BACKLOG_LIMIT
                || wasteGrains / Reprocessing.GRAINS_PER_NUGGET >= BACKLOG_LIMIT;
    }

    /** Moves the whole items the stores hold into the output slots as far as they fit. */
    private void flush() {
        plutoniumGrains -= move(plutoniumOut, BwrItems.PLUTONIUM_NUGGET.get(),
                plutoniumGrains / Reprocessing.GRAINS_PER_NUGGET) * Reprocessing.GRAINS_PER_NUGGET;
        uraniumGrains -= move(uraniumOut, BwrItems.DEPLETED_URANIUM.get(),
                uraniumGrains / Reprocessing.GRAINS_PER_INGOT) * Reprocessing.GRAINS_PER_INGOT;
        wasteGrains -= move(wasteOut, BwrItems.FISSION_PRODUCT_WASTE.get(),
                wasteGrains / Reprocessing.GRAINS_PER_NUGGET) * Reprocessing.GRAINS_PER_NUGGET;
    }

    private static long move(ItemStackHandler slot, Item item, long items) {
        if (items <= 0) {
            return 0;
        }
        ItemStack held = slot.getStackInSlot(0);
        int room = held.isEmpty() ? OUTPUT_STACK : held.is(item) ? OUTPUT_STACK - held.getCount() : 0;
        int moved = (int) Math.min(items, room);
        if (moved <= 0) {
            return 0;
        }
        slot.setStackInSlot(0, held.isEmpty() ? new ItemStack(item, moved) : held.copyWithCount(held.getCount() + moved));
        return moved;
    }

    // --- Accessors ------------------------------------------------------

    public ItemStackHandler input() {
        return input;
    }

    public ItemStackHandler plutoniumOut() {
        return plutoniumOut;
    }

    public ItemStackHandler uraniumOut() {
        return uraniumOut;
    }

    public ItemStackHandler wasteOut() {
        return wasteOut;
    }

    public EnergyStorage energy() {
        return energy;
    }

    /** The item capability: the four slots as a pipe or hopper may use them from this side (null: all of them). */
    public ReprocessingItemView itemView(Direction side) {
        if (side == Direction.UP) {
            return viewInputs;
        }
        return side == Direction.DOWN ? viewOutputs : viewAll;
    }

    /** Decay power of the assembly in the input slot now, W (zero for an empty slot). */
    public double inputDecayWatts() {
        return level == null ? 0.0 : SpentFuelHeat.decayWatts(input.getStackInSlot(0), level.getGameTime());
    }

    /** True if the assembly in the input slot is above the configured decay heat limit, so the plant will not start on it. */
    public boolean isTooHot() {
        ItemStack held = input.getStackInSlot(0);
        return level != null && !held.isEmpty() && SpentFuelHeat.tooHotToReprocess(held, level.getGameTime());
    }

    /** Seconds until the assembly in the slot is cool enough (zero if it is, infinity if never). */
    public double secondsUntilCool() {
        return level == null ? 0.0 : SpentFuelHeat.secondsUntilReprocessable(input.getStackInSlot(0), level.getGameTime());
    }

    /** Ticks of work done on the assembly in the slot, 0 if none is under way. */
    public int workTicks() {
        return workTicks;
    }

    /** What the three stores hold, in grains (a millionth of a nugget): whole items waiting for room and the fractions. */
    public long plutoniumGrains() {
        return plutoniumGrains;
    }

    public long uraniumGrains() {
        return uraniumGrains;
    }

    public long wasteGrains() {
        return wasteGrains;
    }

    /**
     * Everything the plant holds as items, for when the block is broken: the assembly, the products in the output
     * slots and the whole items waiting in the stores. The fractions of an item are lost with the block.
     */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        addDrop(drops, input.extractItem(0, 64, false));
        addDrop(drops, plutoniumOut.extractItem(0, OUTPUT_STACK, false));
        addDrop(drops, uraniumOut.extractItem(0, OUTPUT_STACK, false));
        addDrop(drops, wasteOut.extractItem(0, OUTPUT_STACK, false));
        dropWhole(drops, BwrItems.PLUTONIUM_NUGGET.get(), plutoniumGrains / Reprocessing.GRAINS_PER_NUGGET);
        dropWhole(drops, BwrItems.DEPLETED_URANIUM.get(), uraniumGrains / Reprocessing.GRAINS_PER_INGOT);
        dropWhole(drops, BwrItems.FISSION_PRODUCT_WASTE.get(), wasteGrains / Reprocessing.GRAINS_PER_NUGGET);
        plutoniumGrains = 0;
        uraniumGrains = 0;
        wasteGrains = 0;
        workTicks = 0;
        return drops;
    }

    private static void dropWhole(List<ItemStack> drops, Item item, long items) {
        for (long left = items; left > 0; left -= OUTPUT_STACK) {
            drops.add(new ItemStack(item, (int) Math.min(OUTPUT_STACK, left)));
        }
    }

    private static void addDrop(List<ItemStack> drops, ItemStack stack) {
        if (!stack.isEmpty()) {
            drops.add(stack);
        }
    }

    public List<String> statusLines() {
        return BwrText.english(statusComponents());
    }

    public List<Component> statusComponents() {
        List<Component> out = new ArrayList<>();
        ItemStack held = input.getStackInSlot(0);
        if (held.isEmpty()) {
            out.add(BwrText.tr("status.bwr.reprocessing_plant.empty", "No spent assembly in the plant"));
        } else {
            FuelAssemblyData data = FuelAssemblies.dataOf(held);
            out.add(BwrText.tr("status.bwr.reprocessing_plant.input",
                    "%s assembly, burnup %s MWd/t, %s%% of the work done",
                    data.fuelTypeName(),
                    String.format(Locale.ROOT, "%,.0f", data.burnupMwdPerTonne()),
                    String.format(Locale.ROOT, "%.0f", 100.0 * workTicks / Reprocessing.WORK_TICKS)));
        }
        if (!held.isEmpty() && workTicks == 0 && isTooHot()) {
            out.add(BwrText.tr("status.bwr.reprocessing_plant.too_hot",
                    "Too hot: %s kW of decay heat, the plant waits for %s kW or less. Cool it in a spent fuel pool.",
                    String.format(Locale.ROOT, "%.1f", inputDecayWatts() / 1000.0),
                    String.format(Locale.ROOT, "%.0f", SpentFuelHeat.reprocessingLimitWatts() / 1000.0)));
        }
        out.add(BwrText.tr("status.bwr.reprocessing_plant.held",
                "Held: %s plutonium nuggets, %s depleted uranium ingots, %s waste",
                String.format(Locale.ROOT, "%.3f", plutoniumGrains / (double) Reprocessing.GRAINS_PER_NUGGET),
                String.format(Locale.ROOT, "%.3f", uraniumGrains / (double) Reprocessing.GRAINS_PER_INGOT),
                String.format(Locale.ROOT, "%.3f", wasteGrains / (double) Reprocessing.GRAINS_PER_NUGGET)));
        out.add(BwrText.tr("status.bwr.reprocessing_plant.fe", "%s / %s FE",
                String.format(Locale.ROOT, "%,d", energy.getEnergyStored()),
                String.format(Locale.ROOT, "%,d", energy.getMaxEnergyStored())));
        if (backedUp()) {
            out.add(BwrText.tr("status.bwr.reprocessing_plant.backed_up",
                    "A product is piling up: take the outputs and the plant will go on."));
        }
        return out;
    }

    // --- Persistence -----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy.getEnergyStored());
        // Each key is written only when it holds something, so an idle plant saves just its energy.
        if (workTicks > 0) {
            tag.putInt("WorkTicks", workTicks);
        }
        if (plutoniumGrains > 0) {
            tag.putLong("PlutoniumGrains", plutoniumGrains);
        }
        if (uraniumGrains > 0) {
            tag.putLong("UraniumGrains", uraniumGrains);
        }
        if (wasteGrains > 0) {
            tag.putLong("WasteGrains", wasteGrains);
        }
        saveSlot(tag, "Input", input, registries);
        saveSlot(tag, "PlutoniumOut", plutoniumOut, registries);
        saveSlot(tag, "UraniumOut", uraniumOut, registries);
        saveSlot(tag, "WasteOut", wasteOut, registries);
    }

    private static void saveSlot(CompoundTag tag, String key, ItemStackHandler handler, HolderLookup.Provider registries) {
        if (!handler.getStackInSlot(0).isEmpty()) {
            tag.put(key, handler.serializeNBT(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.setStored(tag.getInt("Energy"));
        workTicks = Math.max(0, Math.min(1_000_000, tag.getInt("WorkTicks")));
        plutoniumGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("PlutoniumGrains")));
        uraniumGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("UraniumGrains")));
        wasteGrains = Math.max(0, Math.min(Long.MAX_VALUE / 4, tag.getLong("WasteGrains")));
        loadSlot(input, tag, "Input", registries);
        loadSlot(plutoniumOut, tag, "PlutoniumOut", registries);
        loadSlot(uraniumOut, tag, "UraniumOut", registries);
        loadSlot(wasteOut, tag, "WasteOut", registries);
        dirty = true;
    }

    private static void loadSlot(ItemStackHandler handler, CompoundTag tag, String key, HolderLookup.Provider registries) {
        if (tag.contains(key)) {
            handler.deserializeNBT(registries, tag.getCompound(key));
            if (handler.getSlots() != 1) {
                handler.setSize(1);
            }
        } else {
            handler.setStackInSlot(0, ItemStack.EMPTY);
        }
    }
}
