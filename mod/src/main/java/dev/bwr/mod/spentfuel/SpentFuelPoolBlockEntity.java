package dev.bwr.mod.spentfuel;

import dev.bwr.core.pool.SpentFuelPool;
import dev.bwr.core.pool.SpentFuelPoolCooler;
import dev.bwr.core.thermal.AssemblyDecayHeat;
import dev.bwr.core.thermal.WaterInventory;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.reactor.CherenkovAppearance;
import dev.bwr.mod.reactor.ReactorSevereAccidentEffects;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The controller of a spent fuel pool: it surveys the shell, holds the stored assemblies, runs the pool's physics
 * ({@link SpentFuelPool}) and the cooling train ({@link SpentFuelPoolCooler}), and is the only ticker in the whole
 * structure. Racks, walls and ports are plain blocks; the racks' contents live here.
 *
 * <h2>Cost</h2>
 * Nothing is done on an idle tick except a counter. Once a second a formed pool computes the decay power of each
 * stored assembly (eighteen exponentials each), steps its heat balance and, if the cooling pump is on and powered,
 * the exchanger. A structure survey (a few thousand block reads) happens when a block of the pool is placed or
 * broken near it, and once every five seconds while the pool is not formed.
 *
 * <h2>No automatic actions</h2>
 * Cooling runs only while the player (or their computer) has switched the pump on; makeup water arrives only through
 * a port that something outside feeds; the pool never starts, stops or trips anything itself.
 *
 * <h2>Saves</h2>
 * Water, the racks' contents (each assembly as its full item, discharge stamp included, with the state of the fuel
 * only if it is not the default) and the cooling state, each key only when non-default. A pool that is not formed
 * keeps its assemblies and its water but stands still. An older world has none of these keys and loads as an empty
 * unformed controller.
 */
public class SpentFuelPoolBlockEntity extends BlockEntity {

    public static final int STEP_TICKS = 20;
    public static final int SCAN_RETRY_TICKS = 100;
    public static final int SCAN_DEBOUNCE_TICKS = 10;
    /** FE per tick the circulation pump draws while it runs. */
    public static final int COOLING_FE_PER_TICK = 400;
    private static final int ENERGY_CAPACITY = 80_000;
    private static final int HOT_WATER_PUSH_KG = 400;

    /** One rack: four cells, what is in them, and the physical state of the fuel. */
    static final class Rack {
        final BlockPos pos;
        double topM = 1.0;
        final ItemStack[] stacks = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        SpentFuelPool.Slot[] slots = new SpentFuelPool.Slot[SpentFuelPool.ASSEMBLIES_PER_RACK];

        Rack(BlockPos pos, double topM) {
            this.pos = pos.immutable();
            retop(topM);
        }

        /** Same cells at a new height (a rescan): the slot objects are rebuilt carrying their state over. */
        void retop(double top) {
            topM = top;
            SpentFuelPool.Slot[] next = new SpentFuelPool.Slot[SpentFuelPool.ASSEMBLIES_PER_RACK];
            for (int k = 0; k < next.length; k++) {
                SpentFuelPool.Slot s = new SpentFuelPool.Slot(top);
                SpentFuelPool.Slot old = slots[k];
                if (old != null) {
                    s.decayW = old.decayW;
                    s.releaseWeight = old.releaseWeight;
                    s.fuelC = old.fuelC;
                    s.damaged = old.damaged;
                    s.ignited = old.ignited;
                    s.zirconiumLeft = old.zirconiumLeft;
                    s.covered = old.covered;
                }
                s.occupied = !stacks[k].isEmpty();
                next[k] = s;
            }
            slots = next;
        }

        int count() {
            int n = 0;
            for (ItemStack s : stacks) {
                if (!s.isEmpty()) {
                    n++;
                }
            }
            return n;
        }

        boolean untouched() {
            if (count() > 0) {
                return false;
            }
            return true;
        }
    }

    /** What the GUI, the peripheral and the tests read: the last second's numbers. */
    public record RackView(BlockPos pos, double topM, int assemblies, double decayW, double hottestFuelC,
                           int uncovered, int damaged, int burning) {
    }

    public record PoolView(boolean formed, double massKg, double capacityKg, double levelM, double temperatureC,
                           double waterAboveFuelM, double highestRackTopM, int assemblies, int cells,
                           double decayW, double coveredDecayW, double uncoveredDecayW,
                           double evaporationKgPerS, double boilKgPerS, double passiveLossW, double coolingW,
                           double secondsToBoil, double secondsToUncover,
                           boolean coolingEnabled, boolean coolingRunning, int energyFE,
                           double coolerColdKg, double coolerHotKg, double coolerColdC, double coolerHotC,
                           double hazard, int damaged, int burning, List<RackView> racks) {
    }

    private final SpentFuelPool pool = new SpentFuelPool();
    private final SpentFuelPoolCooler cooler = new SpentFuelPoolCooler();
    private final LinkedHashMap<BlockPos, Rack> racks = new LinkedHashMap<>();
    private final List<Rack> rackList = new ArrayList<>();
    private SpentFuelPool.Slot[] flat = new SpentFuelPool.Slot[0];
    private final Set<BlockPos> owned = new HashSet<>();
    private final List<BlockPos> coolingOutPorts = new ArrayList<>();

    private SpentFuelPoolLayout.Layout layout;
    private boolean formed;
    private boolean structureDirty = true;
    private long dirtyAt;
    private long nextScan;
    private Component problem = BwrText.tr("status.bwr.spent_fuel_pool.not_surveyed", "The pool has not been surveyed yet.");

    private boolean coolingEnabled;
    private boolean coolingRunning;
    private double pendingMass = Double.NaN;
    private double pendingC = 30.0;
    private double hazard;
    private int sinceStep;
    private double totalDecayW;
    private boolean registered;

    // client-side picture, filled from the update tag
    public boolean visFormed;
    public BlockPos visMin;
    public BlockPos visMax;
    public int visLevelQ;
    public long[] visRacks = new long[0];
    public byte[] visGlow = new byte[0];
    private int visualHash = Integer.MIN_VALUE;
    /** The picture changed and the client has not been told yet; it is sent from the ticker, where the chunk is known to be ticking. */
    private boolean visualPending;

    private final class MachineEnergy extends EnergyStorage {
        MachineEnergy() {
            super(ENERGY_CAPACITY, ENERGY_CAPACITY / 4, 0);
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
            this.energy = Math.max(0, Math.min(capacity, stored));
        }
    }

    private final MachineEnergy energy = new MachineEnergy();

    public SpentFuelPoolBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.SPENT_FUEL_POOL.get(), pos, state);
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            SpentFuelPoolRegistry.register(this);
            registered = true;
            markStructureDirty();
        }
    }

    @Override
    public void setRemoved() {
        SpentFuelPoolRegistry.unregister(this);
        registered = false;
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        SpentFuelPoolRegistry.unregister(this);
        registered = false;
        super.onChunkUnloaded();
    }

    public boolean isFormed() {
        return formed;
    }

    public boolean owns(BlockPos pos) {
        return owned.contains(pos);
    }

    public SpentFuelPoolLayout.Layout layout() {
        return layout;
    }

    public SpentFuelPool pool() {
        return pool;
    }

    public SpentFuelPoolCooler cooler() {
        return cooler;
    }

    public EnergyStorage energy() {
        return energy;
    }

    public void markStructureDirty() {
        structureDirty = true;
        if (level != null) {
            dirtyAt = level.getGameTime();
        }
    }

    /** A report was asked for: survey now if the structure changed since the last look. */
    public void refreshForReport() {
        if (structureDirty && level instanceof ServerLevel) {
            scan();
        }
    }

    /** Surveys the structure now (tests and tools; the ticker does it by itself). */
    public void scanNow() {
        if (level instanceof ServerLevel) {
            scan();
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SpentFuelPoolBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        if (!registered) {
            SpentFuelPoolRegistry.register(this);
            registered = true;
        }
        if (structureDirty ? now >= dirtyAt + SCAN_DEBOUNCE_TICKS : (!formed && now >= nextScan)) {
            scan();
        }
        if (formed && ++sinceStep >= STEP_TICKS) {
            sinceStep = 0;
            step(serverLevel, now);
        }
        // Told from here and not where the change was made: a block update for a chunk that is not ticking yet is dropped.
        if (visualPending) {
            visualPending = false;
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
        if (!formed) {
            return;
        }
        if (hazard > 1.0e-7 && now % ReactorSevereAccidentEffects.DOSE_INTERVAL_TICKS == 0) {
            applyDoses(serverLevel);
        }
    }

    // ---------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------

    private void scan() {
        if (!registered && level != null && !level.isClientSide()) {
            // a pool surveyed before its first tick (a bridge console asks the registry straight away) is known to it
            SpentFuelPoolRegistry.register(this);
            registered = true;
        }
        structureDirty = false;
        long now = level.getGameTime();
        SpentFuelPoolLayout.Layout found = SpentFuelPoolLayout.inspect(level, worldPosition);
        if (!found.ok()) {
            nextScan = now + SCAN_RETRY_TICKS;
            problem = found.problem();
            layout = null;
            if (formed) {
                formed = false;
                SpentFuelPoolRegistry.release(this);
                owned.clear();
                coolingOutPorts.clear();
                markVisual(true);
            }
            return;
        }
        layout = found;
        problem = null;
        pool.configure(found.footprintCells(), found.heightCells());
        if (!Double.isNaN(pendingMass)) {
            pool.restore(pendingMass, pendingC);
            pendingMass = Double.NaN;
        }
        // racks: keep the contents of the ones that are still there, drop the contents of the ones that are not
        LinkedHashMap<BlockPos, Rack> next = new LinkedHashMap<>();
        for (BlockPos p : found.racks()) {
            Rack rack = racks.remove(p);
            if (rack == null) {
                rack = new Rack(p, found.rackTopM(p));
            } else {
                rack.retop(found.rackTopM(p));
            }
            next.put(p, rack);
        }
        for (Rack gone : racks.values()) {
            dropAll(gone, gone.pos);
        }
        racks.clear();
        racks.putAll(next);
        owned.clear();
        owned.addAll(SpentFuelPoolLayout.claimed(found));
        coolingOutPorts.clear();
        coolingOutPorts.addAll(found.coolingOut());
        SpentFuelPoolRegistry.claim(this, owned);
        rebuildFlat();
        formed = true;
        for (Rack rack : racks.values()) {
            syncRackState(rack);
        }
        markVisual(true);
        setChanged();
    }

    private void rebuildFlat() {
        rackList.clear();
        rackList.addAll(racks.values());
        flat = new SpentFuelPool.Slot[rackList.size() * SpentFuelPool.ASSEMBLIES_PER_RACK];
        for (int r = 0; r < rackList.size(); r++) {
            System.arraycopy(rackList.get(r).slots, 0, flat, r * SpentFuelPool.ASSEMBLIES_PER_RACK, SpentFuelPool.ASSEMBLIES_PER_RACK);
        }
    }

    private List<ItemStack> dropAll(Rack rack, BlockPos at) {
        List<ItemStack> dropped = new ArrayList<>();
        for (int k = 0; k < rack.stacks.length; k++) {
            if (!rack.stacks[k].isEmpty()) {
                dropped.add(rack.stacks[k].copy());
                Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, rack.stacks[k]);
                rack.stacks[k] = ItemStack.EMPTY;
                rack.slots[k].occupied = false;
            }
        }
        return dropped;
    }

    /** The bottom block of the rack that {@code pos} (any of its blocks) belongs to, or {@code pos} itself. */
    private BlockPos base(BlockPos pos) {
        for (int k = 0; k < SpentFuelPoolLayout.RACK_HEIGHT; k++) {
            if (racks.containsKey(pos.below(k))) {
                return pos.below(k);
            }
        }
        return pos;
    }

    /** A rack was mined or blown up: what was in it is dropped. */
    public List<ItemStack> rackBroken(BlockPos any) {
        BlockPos pos = base(any);
        Rack rack = racks.remove(pos);
        List<ItemStack> dropped = List.of();
        if (rack != null) {
            dropped = dropAll(rack, pos);
            for (int k = 0; k < SpentFuelPoolLayout.RACK_HEIGHT; k++) {
                owned.remove(pos.above(k));
            }
            rebuildFlat();
            setChanged();
            markVisual(true);
        }
        return dropped;
    }

    /**
     * Writes the rack's blocks: the top one is marked and carries the four cell booleans, so its model shows an assembly
     * head in each occupied cell on top of the tubes.
     */
    private void syncRackState(Rack rack) {
        for (int b = 0; b < SpentFuelPoolLayout.RACK_HEIGHT; b++) {
            BlockPos at = rack.pos.above(b);
            if (level == null || !level.isLoaded(at)) {
                continue;
            }
            BlockState state = level.getBlockState(at);
            if (!state.is(BwrBlocks.SPENT_FUEL_RACK.get())) {
                continue;
            }
            boolean top = b == SpentFuelPoolLayout.RACK_HEIGHT - 1;
            BlockState wanted = state.setValue(SpentFuelRackBlock.TOP, top);
            for (int k = 0; k < SpentFuelRackBlock.CELL.length; k++) {
                wanted = wanted.setValue(SpentFuelRackBlock.CELL[k], top && !rack.stacks[k].isEmpty());
            }
            if (wanted != state) {
                level.setBlock(at, wanted, 2);
            }
        }
    }

    // ---------------------------------------------------------------
    // Fuel in and out
    // ---------------------------------------------------------------

    private void place(Rack rack, int cell, ItemStack one) {
        rack.stacks[cell] = one;
        SpentFuelPool.Slot slot = rack.slots[cell];
        slot.occupied = true;
        boolean covered = pool.massKg() > 0.0 && pool.levelM() >= rack.topM - 1.0e-9;
        slot.fuelC = covered ? pool.temperatureC() : SpentFuelPool.AMBIENT_C;
        slot.damaged = false;
        slot.ignited = false;
        slot.zirconiumLeft = 1.0;
        slot.covered = covered;
        slot.decayW = level == null ? 0.0 : SpentFuelHeat.decayWatts(one, level.getGameTime());
        syncRackState(rack);
        setChanged();
        markVisual(false);
    }

    private ItemStack take(Rack rack, int cell) {
        ItemStack out = rack.stacks[cell];
        rack.stacks[cell] = ItemStack.EMPTY;
        SpentFuelPool.Slot slot = rack.slots[cell];
        slot.occupied = false;
        slot.decayW = 0.0;
        syncRackState(rack);
        setChanged();
        markVisual(false);
        return out;
    }

    private static boolean isAssembly(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BwrItems.FUEL_ASSEMBLY.get());
    }

    /** Puts one assembly into the rack at {@code pos}, in {@code preferredCell} if it is free, else the first free one. */
    public boolean insertAt(BlockPos pos, int preferredCell, ItemStack one) {
        Rack rack = formed ? racks.get(base(pos)) : null;
        if (rack == null || !isAssembly(one)) {
            return false;
        }
        int cell = -1;
        if (preferredCell >= 0 && preferredCell < rack.stacks.length && rack.stacks[preferredCell].isEmpty()) {
            cell = preferredCell;
        } else {
            for (int k = 0; k < rack.stacks.length && cell < 0; k++) {
                if (rack.stacks[k].isEmpty()) {
                    cell = k;
                }
            }
        }
        if (cell < 0) {
            return false;
        }
        place(rack, cell, one.copyWithCount(1));
        return true;
    }

    /** Puts one assembly into the first free cell of the pool. */
    public boolean insertAnywhere(ItemStack one) {
        if (!formed || !isAssembly(one)) {
            return false;
        }
        for (Rack rack : rackList) {
            if (insertAt(rack.pos, -1, one)) {
                return true;
            }
        }
        return false;
    }

    /** Takes an assembly out of the rack: the one in {@code preferredCell} if there is one, else the one cooled longest. */
    public ItemStack extractFrom(BlockPos pos, int preferredCell) {
        Rack rack = formed ? racks.get(base(pos)) : null;
        if (rack == null) {
            return ItemStack.EMPTY;
        }
        if (preferredCell >= 0 && preferredCell < rack.stacks.length && !rack.stacks[preferredCell].isEmpty()) {
            return take(rack, preferredCell);
        }
        long now = level == null ? 0L : level.getGameTime();
        int best = -1;
        double bestWatts = Double.MAX_VALUE;
        for (int k = 0; k < rack.stacks.length; k++) {
            if (!rack.stacks[k].isEmpty()) {
                double watts = SpentFuelHeat.decayWatts(rack.stacks[k], now);
                if (watts < bestWatts) {
                    bestWatts = watts;
                    best = k;
                }
            }
        }
        return best < 0 ? ItemStack.EMPTY : take(rack, best);
    }

    /** All the assemblies the pool holds, emptied out (the controller was broken). */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        for (Rack rack : racks.values()) {
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    drops.add(rack.stacks[k]);
                    rack.stacks[k] = ItemStack.EMPTY;
                    rack.slots[k].occupied = false;
                }
            }
        }
        for (Rack rack : racks.values()) {
            syncRackState(rack);
        }
        return drops;
    }

    public int assemblyCount() {
        int n = 0;
        for (Rack rack : racks.values()) {
            n += rack.count();
        }
        return n;
    }

    public int cellCount() {
        return racks.size() * SpentFuelPool.ASSEMBLIES_PER_RACK;
    }

    /** The stack in cell {@code cell} of the rack at {@code pos} (empty if none). For tests and tools. */
    public ItemStack stackAt(BlockPos pos, int cell) {
        Rack rack = racks.get(pos);
        return rack == null || cell < 0 || cell >= rack.stacks.length ? ItemStack.EMPTY : rack.stacks[cell];
    }

    /** The pool's slot state at a cell: fuel temperature, burst, burning. For tests and tools. */
    public SpentFuelPool.Slot slotAt(BlockPos pos, int cell) {
        Rack rack = racks.get(pos);
        return rack == null || cell < 0 || cell >= rack.slots.length ? null : rack.slots[cell];
    }

    /** The rack's cells as lines for a chat report. */
    public List<Component> rackReport(BlockPos pos) {
        List<Component> lines = new ArrayList<>();
        Rack rack = racks.get(base(pos));
        if (rack == null) {
            return lines;
        }
        long now = level == null ? 0L : level.getGameTime();
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.rack_report", "Rack: %s of 4 cells in use, top %s m above the floor.",
                rack.count(), String.format(Locale.ROOT, "%.0f", rack.topM)));
        for (int k = 0; k < rack.stacks.length; k++) {
            ItemStack stack = rack.stacks[k];
            if (stack.isEmpty()) {
                continue;
            }
            var data = FuelAssemblies.dataOf(stack);
            lines.add(BwrText.tr("status.bwr.spent_fuel_pool.cell_report",
                    "Cell %s: %s, burnup %s MWd/t, %s kW decay heat, cooled %s",
                    k + 1, data.fuelTypeName(), String.format(Locale.ROOT, "%,.0f", data.burnupMwdPerTonne()),
                    String.format(Locale.ROOT, "%.1f", SpentFuelHeat.decayWatts(stack, now) / 1000.0),
                    duration(SpentFuelHeat.coolingSeconds(stack, now))));
        }
        return lines;
    }

    // ---------------------------------------------------------------
    // Item handler (hoppers and pipes)
    // ---------------------------------------------------------------

    private final IItemHandler items = new IItemHandler() {
        private Rack rackOf(int slot) {
            int r = slot / SpentFuelPool.ASSEMBLIES_PER_RACK;
            return formed && slot >= 0 && r < rackList.size() ? rackList.get(r) : null;
        }

        @Override
        public int getSlots() {
            return formed ? rackList.size() * SpentFuelPool.ASSEMBLIES_PER_RACK : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            Rack rack = rackOf(slot);
            return rack == null ? ItemStack.EMPTY : rack.stacks[slot % SpentFuelPool.ASSEMBLIES_PER_RACK];
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            Rack rack = rackOf(slot);
            int cell = slot % SpentFuelPool.ASSEMBLIES_PER_RACK;
            if (rack == null || !isAssembly(stack) || !rack.stacks[cell].isEmpty()) {
                return stack;
            }
            if (!simulate) {
                place(rack, cell, stack.copyWithCount(1));
            }
            return stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            Rack rack = rackOf(slot);
            int cell = slot % SpentFuelPool.ASSEMBLIES_PER_RACK;
            if (rack == null || amount < 1 || rack.stacks[cell].isEmpty()) {
                return ItemStack.EMPTY;
            }
            return simulate ? rack.stacks[cell].copy() : take(rack, cell);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isAssembly(stack);
        }
    };

    /** The item capability: one slot per cell, rack by rack; empty while the pool is not formed. */
    public IItemHandler itemHandler() {
        return items;
    }

    // ---------------------------------------------------------------
    // Water (makeup port, cooling inlet and outlet)
    // ---------------------------------------------------------------

    private static boolean isWater(FluidStack stack) {
        return stack.is(Fluids.WATER);
    }

    private final IFluidHandler makeup = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return 2000;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return isWater(stack);
        }

        @Override
        public int fill(FluidStack stack, FluidAction action) {
            if (!formed || stack.isEmpty() || !isWater(stack)) {
                return 0;
            }
            double celsius = ThermalWater.temperature(stack, ThermalWater.AMBIENT_C);
            int accepted = (int) Math.floor(Math.min(stack.getAmount(), pool.spaceKg()));
            if (accepted > 0 && action.execute()) {
                pool.receiveWaterKg(accepted, celsius);
                setChanged();
            }
            return accepted;
        }

        @Override
        public FluidStack drain(FluidStack stack, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int max, FluidAction action) {
            return FluidStack.EMPTY;
        }
    };

    private final IFluidHandler coldIn = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            int n = (int) Math.floor(cooler.coldKg());
            return n > 0 ? ThermalWater.stack(n, cooler.coldEnthalpy()) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return SpentFuelPoolCooler.CAPACITY_KG;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return isWater(stack);
        }

        @Override
        public int fill(FluidStack stack, FluidAction action) {
            if (!formed || stack.isEmpty() || !isWater(stack)) {
                return 0;
            }
            double h = ThermalWater.enthalpy(stack);
            int accepted = (int) Math.floor(cooler.fillCold(stack.getAmount(), h, true));
            if (accepted > 0 && action.execute()) {
                cooler.fillCold(accepted, h, false);
                setChanged();
            }
            return accepted;
        }

        @Override
        public FluidStack drain(FluidStack stack, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int max, FluidAction action) {
            return FluidStack.EMPTY;
        }
    };

    private final IFluidHandler hotOut = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            int n = (int) Math.floor(cooler.hotKg());
            return n > 0 ? ThermalWater.stack(n, cooler.hotEnthalpy()) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return SpentFuelPoolCooler.CAPACITY_KG;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return false;
        }

        @Override
        public int fill(FluidStack stack, FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack stack, FluidAction action) {
            return isWater(stack) ? drain(stack.getAmount(), action) : FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int max, FluidAction action) {
            if (!formed) {
                return FluidStack.EMPTY;
            }
            int n = (int) Math.floor(cooler.drainHot(max, true));
            if (n <= 0) {
                return FluidStack.EMPTY;
            }
            double h = cooler.hotEnthalpy();
            if (action.execute()) {
                cooler.drainHot(n, false);
                setChanged();
            }
            return ThermalWater.stack(n, h);
        }
    };

    public IFluidHandler waterHandler(SpentFuelPoolPortBlock.Kind kind) {
        return switch (kind) {
            case MAKEUP -> makeup;
            case COOLING_IN -> coldIn;
            case COOLING_OUT -> hotOut;
        };
    }

    /** The cooling train's hot water is pushed into whatever accepts water at an outlet port. */
    private void pushHotWater(ServerLevel l) {
        if (cooler.hotKg() < 1.0) {
            return;
        }
        for (BlockPos port : coolingOutPorts) {
            if (!l.isLoaded(port)) {
                continue;
            }
            BlockState state = l.getBlockState(port);
            if (!(state.getBlock() instanceof SpentFuelPoolPortBlock)) {
                continue;
            }
            Direction face = state.getValue(SpentFuelPoolPortBlock.FACING);
            BlockPos next = port.relative(face);
            if (!l.isLoaded(next)) {
                continue;
            }
            IFluidHandler sink = l.getCapability(Capabilities.FluidHandler.BLOCK, next, face.getOpposite());
            if (sink == null) {
                continue;
            }
            FluidStack offered = hotOut.drain(HOT_WATER_PUSH_KG, IFluidHandler.FluidAction.SIMULATE);
            if (offered.isEmpty()) {
                return;
            }
            int accepted = sink.fill(offered, IFluidHandler.FluidAction.EXECUTE);
            if (accepted > 0) {
                hotOut.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    // ---------------------------------------------------------------
    // Physics
    // ---------------------------------------------------------------

    /** The cooling pump is the player's to switch; this changes nothing else. */
    public void setCoolingEnabled(boolean enabled) {
        if (coolingEnabled != enabled) {
            coolingEnabled = enabled;
            setChanged();
        }
    }

    public boolean coolingEnabled() {
        return coolingEnabled;
    }

    public boolean coolingRunning() {
        return coolingRunning;
    }

    /** Test and tooling hook: {@code seconds} of pool physics in one-second steps, the game time running on from {@code startTick}. */
    public void simulateSeconds(int seconds, long startTick) {
        if (!formed || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int i = 0; i < seconds; i++) {
            step(serverLevel, startTick + (long) i * STEP_TICKS);
        }
    }

    /** One second of physics at game time {@code now}. */
    private void step(ServerLevel l, long now) {
        double total = 0.0;
        for (Rack rack : rackList) {
            for (int k = 0; k < rack.stacks.length; k++) {
                SpentFuelPool.Slot slot = rack.slots[k];
                ItemStack stack = rack.stacks[k];
                slot.occupied = !stack.isEmpty();
                if (slot.occupied) {
                    slot.decayW = SpentFuelHeat.decayWatts(stack, now);
                    slot.releaseWeight = Math.min(1.0, FuelAssemblies.dataOf(stack).burnupMwdPerTonne() / 10_000.0);
                    total += slot.decayW;
                } else {
                    slot.decayW = 0.0;
                }
            }
        }
        totalDecayW = total;
        coolingRunning = false;
        if (coolingEnabled) {
            int need = COOLING_FE_PER_TICK * STEP_TICKS;
            if (energy.getEnergyStored() >= need) {
                energy.drain(need);
                cooler.step(pool, 1.0);
                coolingRunning = true;
            } else {
                cooler.clearReadouts();
            }
        } else {
            cooler.clearReadouts();
        }
        pushHotWater(l);
        SpentFuelPool.Report report = pool.step(1.0, flat);
        double halfLife = GameplayConfig.contaminationHalfLifeSeconds();
        hazard = (hazard + report.hazardAdded) * Math.exp(-Math.log(2.0) / halfLife);
        if (hazard < 1.0e-9) {
            hazard = 0.0;
        }
        markVisual(false);
        setChanged();
    }

    private void applyDoses(ServerLevel l) {
        double limit = ReactorSevereAccidentEffects.MAX_DOSE_DISTANCE_BLOCKS * ReactorSevereAccidentEffects.MAX_DOSE_DISTANCE_BLOCKS;
        double half = ReactorSevereAccidentEffects.PLANT_HALF_DISTANCE_BLOCKS;
        for (ServerPlayer player : l.players()) {
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }
            double d2 = player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
            if (d2 > limit) {
                continue;
            }
            double dose = hazard / (1.0 + d2 / (half * half)) * GameplayConfig.contaminationDoseMultiplier();
            ReactorSevereAccidentEffects.dose(player, dose);
        }
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    public double hazard() {
        return hazard;
    }

    /** The highest rack top that holds fuel, metres above the floor (0 if none does). */
    private double highestOccupiedTop() {
        double top = 0.0;
        for (Rack rack : rackList) {
            if (rack.count() > 0) {
                top = Math.max(top, rack.topM);
            }
        }
        return top;
    }

    public PoolView view() {
        List<RackView> rackViews = new ArrayList<>(rackList.size());
        int damaged = 0;
        int burning = 0;
        for (Rack rack : rackList) {
            double decay = 0.0;
            double hottest = 0.0;
            int uncovered = 0;
            int rackDamaged = 0;
            int rackBurning = 0;
            for (int k = 0; k < rack.stacks.length; k++) {
                if (rack.stacks[k].isEmpty()) {
                    continue;
                }
                SpentFuelPool.Slot slot = rack.slots[k];
                decay += slot.decayW;
                hottest = Math.max(hottest, slot.fuelC);
                if (!slot.covered) {
                    uncovered++;
                }
                if (slot.damaged) {
                    rackDamaged++;
                }
                if (slot.ignited) {
                    rackBurning++;
                }
            }
            damaged += rackDamaged;
            burning += rackBurning;
            rackViews.add(new RackView(rack.pos, rack.topM, rack.count(), decay, hottest, uncovered, rackDamaged, rackBurning));
        }
        double top = highestOccupiedTop();
        double level = pool.levelM();
        return new PoolView(formed, pool.massKg(), pool.capacityKg(), level, pool.temperatureC(),
                level - top, top, assemblyCount(), cellCount(),
                totalDecayW, pool.coveredDecayW(), pool.uncoveredDecayW(),
                pool.evaporationKgPerS(), pool.boilKgPerS(), pool.passiveLossW(), pool.coolingW(),
                pool.secondsToBoil(), pool.secondsToUncover(top),
                coolingEnabled, coolingRunning, energy.getEnergyStored(),
                cooler.coldKg(), cooler.hotKg(), cooler.coldTemperatureC(), cooler.hotTemperatureC(),
                hazard, damaged, burning, rackViews);
    }

    /** One rack's contribution to the radiation field: where the heads of its assemblies are, and their decay heat. */
    @FunctionalInterface
    public interface RackSourceVisitor {
        /**
         * @param x       centre of the rack block, x
         * @param topY    world Y of the rack top (the assembly heads)
         * @param z       centre of the rack block, z
         * @param decayW  summed decay heat of the assemblies in the rack, W
         * @param coverM  metres of water over the rack top (zero or less: uncovered)
         */
        void accept(double x, double topY, double z, double decayW, double coverM);
    }

    /** Calls {@code visitor} for every rack that holds fuel with decay heat (read from the last second's numbers; no allocation). */
    public void forEachRackSource(RackSourceVisitor visitor) {
        if (!formed || layout == null) {
            return;
        }
        double level = pool.levelM();
        for (Rack rack : rackList) {
            double watts = 0.0;
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    watts += rack.slots[k].decayW;
                }
            }
            if (watts > 0.0) {
                visitor.accept(rack.pos.getX() + 0.5, rack.pos.getY() + rack.topM, rack.pos.getZ() + 0.5, watts, level - rack.topM);
            }
        }
    }

    public Component problem() {
        return problem;
    }

    public List<Component> statusComponents() {
        List<Component> lines = new ArrayList<>();
        if (!formed) {
            lines.add(BwrText.tr("status.bwr.spent_fuel_pool.unformed", "Spent fuel pool is not formed."));
            if (problem != null) {
                lines.add(problem);
            }
            lines.add(BwrText.tr("status.bwr.spent_fuel_pool.build_help",
                    "Build a floor and four walls of Spent Fuel Pool Lining up to the rim, one controller in the shell, racks on the floor inside."));
            return lines;
        }
        PoolView v = view();
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.formed",
                "Spent fuel pool: %s racks, %s of %s cells in use, %s m2 by %s m deep.",
                v.racks().size(), v.assemblies(), v.cells(), layout.footprintCells(), layout.heightCells()));
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.water",
                "Water %s of %s kg, level %s m, %s C; %s m %s the highest fuel.",
                String.format(Locale.ROOT, "%,.0f", v.massKg()), String.format(Locale.ROOT, "%,.0f", v.capacityKg()),
                String.format(Locale.ROOT, "%.2f", v.levelM()), String.format(Locale.ROOT, "%.1f", v.temperatureC()),
                String.format(Locale.ROOT, "%.2f", Math.abs(v.waterAboveFuelM())), v.waterAboveFuelM() >= 0 ? "above" : "below"));
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.heat",
                "Decay heat %s kW; time to boil %s, to uncover the fuel %s.",
                String.format(Locale.ROOT, "%,.1f", v.decayW() / 1000.0), duration(v.secondsToBoil()), duration(v.secondsToUncover())));
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.cooling",
                "Cooling pump %s, %s MW removed, %s FE stored.",
                v.coolingRunning() ? "running" : v.coolingEnabled() ? "on but without power or cold water" : "off",
                String.format(Locale.ROOT, "%.2f", v.coolingW() / 1.0e6), String.format(Locale.ROOT, "%,d", v.energyFE())));
        return lines;
    }

    /** A time for a person: "3.2 h", "41 min", "never". */
    public static String duration(double seconds) {
        if (Double.isInfinite(seconds) || Double.isNaN(seconds) || seconds > 9.0e8) {
            return "never";
        }
        if (seconds < 1.0) {
            return "now";
        }
        if (seconds < 90.0) {
            return String.format(Locale.ROOT, "%.0f s", seconds);
        }
        if (seconds < 5400.0) {
            return String.format(Locale.ROOT, "%.0f min", seconds / 60.0);
        }
        if (seconds < 172_800.0) {
            return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0);
        }
        return String.format(Locale.ROOT, "%.1f d", seconds / 86_400.0);
    }

    // ---------------------------------------------------------------
    // Visual state for the client
    // ---------------------------------------------------------------

    private void markVisual(boolean force) {
        if (level == null || level.isClientSide()) {
            return;
        }
        int hash = formed ? 1 : 0;
        hash = 31 * hash + (int) Math.round(pool.levelM() * 8.0);
        for (Rack rack : rackList) {
            hash = 31 * hash + Long.hashCode(rack.pos.asLong());
            for (int k = 0; k < rack.stacks.length; k++) {
                hash = 31 * hash + glowOf(rack, k);
            }
        }
        if (force || hash != visualHash) {
            visualHash = hash;
            visualPending = true;
        }
    }

    private static int glowOf(Rack rack, int k) {
        if (rack.stacks[k].isEmpty()) {
            return 0;
        }
        SpentFuelPool.Slot slot = rack.slots[k];
        double fraction = slot.decayW / AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W;
        double strength = CherenkovAppearance.strength(fraction, true, slot.covered, slot.covered ? 12.0 : 0.0);
        return (int) Math.round(strength * 15.0);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("VisFormed", formed && layout != null);
        if (formed && layout != null) {
            tag.putLong("VisMin", layout.min().asLong());
            tag.putLong("VisMax", layout.max().asLong());
            tag.putInt("VisLevel", (int) Math.round(pool.levelM() * 8.0));
            long[] positions = new long[rackList.size()];
            byte[] glow = new byte[rackList.size() * SpentFuelPool.ASSEMBLIES_PER_RACK];
            for (int r = 0; r < rackList.size(); r++) {
                positions[r] = rackList.get(r).pos.asLong();
                for (int k = 0; k < SpentFuelPool.ASSEMBLIES_PER_RACK; k++) {
                    glow[r * SpentFuelPool.ASSEMBLIES_PER_RACK + k] = (byte) glowOf(rackList.get(r), k);
                }
            }
            tag.putLongArray("VisRacks", positions);
            tag.putByteArray("VisGlow", glow);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readVisual(tag);
    }

    /** The picture the controller sends. A chunk packet delivers it through {@code loadAdditional}, a block update through {@code handleUpdateTag}. */
    private void readVisual(CompoundTag tag) {
        visFormed = tag.getBoolean("VisFormed");
        if (visFormed) {
            visMin = BlockPos.of(tag.getLong("VisMin"));
            visMax = BlockPos.of(tag.getLong("VisMax"));
            visLevelQ = tag.getInt("VisLevel");
            visRacks = tag.getLongArray("VisRacks");
            visGlow = tag.getByteArray("VisGlow");
        } else {
            visMin = null;
            visMax = null;
            visRacks = new long[0];
            visGlow = new byte[0];
        }
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        double mass = Double.isNaN(pendingMass) ? pool.massKg() : pendingMass;
        double celsius = Double.isNaN(pendingMass) ? pool.temperatureC() : pendingC;
        if (mass > 0.0) {
            tag.putDouble("WaterKg", mass);
            tag.putDouble("WaterC", celsius);
        }
        if (coolingEnabled) {
            tag.putBoolean("Cooling", true);
        }
        if (energy.getEnergyStored() > 0) {
            tag.putInt("Energy", energy.getEnergyStored());
        }
        if (cooler.coldKg() > 0.0) {
            tag.putDouble("ColdKg", cooler.coldKg());
            tag.putDouble("ColdH", cooler.coldEnthalpy());
        }
        if (cooler.hotKg() > 0.0) {
            tag.putDouble("HotKg", cooler.hotKg());
            tag.putDouble("HotH", cooler.hotEnthalpy());
        }
        if (hazard > 0.0) {
            tag.putDouble("Hazard", hazard);
        }
        ListTag rackTags = new ListTag();
        for (Rack rack : racks.values()) {
            if (rack.untouched()) {
                continue;
            }
            CompoundTag rackTag = new CompoundTag();
            rackTag.putLong("Pos", rack.pos.asLong());
            ListTag cells = new ListTag();
            for (int k = 0; k < rack.stacks.length; k++) {
                if (rack.stacks[k].isEmpty()) {
                    continue;
                }
                CompoundTag cell = new CompoundTag();
                cell.putByte("I", (byte) k);
                cell.put("Item", rack.stacks[k].save(registries));
                SpentFuelPool.Slot slot = rack.slots[k];
                if (!slot.covered) {
                    cell.putDouble("FuelC", slot.fuelC);
                }
                if (slot.damaged) {
                    cell.putBoolean("Damaged", true);
                }
                if (slot.ignited) {
                    cell.putBoolean("Ignited", true);
                }
                if (slot.zirconiumLeft < 1.0) {
                    cell.putDouble("Zr", slot.zirconiumLeft);
                }
                cells.add(cell);
            }
            rackTag.put("Cells", cells);
            rackTags.add(rackTag);
        }
        if (!rackTags.isEmpty()) {
            tag.put("Racks", rackTags);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("VisFormed")) {
            // an update tag from the server (a chunk packet reads it here); never present in a saved controller
            readVisual(tag);
            return;
        }
        pendingMass = tag.contains("WaterKg") ? Math.max(0.0, tag.getDouble("WaterKg")) : 0.0;
        pendingC = tag.contains("WaterC") ? tag.getDouble("WaterC") : 30.0;
        if (pendingMass <= 0.0) {
            pendingMass = Double.NaN;
        }
        // if the pool was already configured (a reload of a live block entity), restore straight away
        if (formed && !Double.isNaN(pendingMass)) {
            pool.restore(pendingMass, pendingC);
            pendingMass = Double.NaN;
        } else if (formed) {
            pool.restore(0.0, 30.0);
        }
        coolingEnabled = tag.getBoolean("Cooling");
        energy.setStored(tag.getInt("Energy"));
        cooler.restore(tag.contains("ColdKg") ? tag.getDouble("ColdKg") : 0.0, tag.getDouble("ColdH"),
                tag.contains("HotKg") ? tag.getDouble("HotKg") : 0.0, tag.getDouble("HotH"));
        hazard = Math.max(0.0, tag.getDouble("Hazard"));
        racks.clear();
        if (tag.contains("Racks", Tag.TAG_LIST)) {
            ListTag rackTags = tag.getList("Racks", Tag.TAG_COMPOUND);
            for (int i = 0; i < rackTags.size(); i++) {
                CompoundTag rackTag = rackTags.getCompound(i);
                BlockPos pos = BlockPos.of(rackTag.getLong("Pos"));
                Rack rack = new Rack(pos, 1.0);
                ListTag cells = rackTag.getList("Cells", Tag.TAG_COMPOUND);
                for (int c = 0; c < cells.size(); c++) {
                    CompoundTag cell = cells.getCompound(c);
                    int k = cell.getByte("I");
                    if (k < 0 || k >= rack.stacks.length) {
                        continue;
                    }
                    ItemStack stack = ItemStack.parse(registries, cell.get("Item")).orElse(ItemStack.EMPTY);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    rack.stacks[k] = stack;
                    SpentFuelPool.Slot slot = rack.slots[k];
                    slot.occupied = true;
                    slot.covered = !cell.contains("FuelC");
                    slot.fuelC = cell.contains("FuelC") ? cell.getDouble("FuelC") : SpentFuelPool.AMBIENT_C;
                    slot.damaged = cell.getBoolean("Damaged");
                    slot.ignited = cell.getBoolean("Ignited");
                    slot.zirconiumLeft = cell.contains("Zr") ? cell.getDouble("Zr") : 1.0;
                }
                racks.put(pos, rack);
            }
        }
        rackList.clear();
        rackList.addAll(racks.values());
        flat = new SpentFuelPool.Slot[0];
        // The structure is surveyed again from scratch: formed is a fact about the world, not a saved flag.
        formed = false;
        structureDirty = true;
    }
}
