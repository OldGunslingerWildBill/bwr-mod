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
 * A pool holds up to {@value SpentFuelPoolLayout#MAX_RACKS} racks, 8,192 assemblies. Each stored assembly keeps its decay
 * curve with the history part worked out once ({@link SpentFuelHeat.Curve}: eighteen exponentials an evaluation, the same
 * number to the last bit as the plain formula). The decay power every assembly will have at the next one-second step is
 * worked out a twentieth of the racks per tick ahead of it, so no tick does all of them; the step itself only sums them,
 * steps the heat balance (one pass over the cells) and, if the cooling pump is on and powered, the exchanger. A rack whose
 * contents changed since its share was worked out is worked out again at the step, so the step's numbers are exactly
 * those of computing everything at that tick. A structure survey (up to about 100,000 block reads for the largest pool,
 * straight from the chunk sections) happens when a block of the pool is placed or broken near it, and, while the pool is
 * not formed, every five seconds once the block that stopped the last survey has changed. The client gets the racks as a
 * bitmap of the floor and the glow as four bits a cell (under 5 kB for the largest pool), and only when the picture changed.
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
        /** Each cell's decay curve, made from its stack the first time it is needed and dropped when the stack changes. */
        private final SpentFuelHeat.Curve[] curves = new SpentFuelHeat.Curve[SpentFuelPool.ASSEMBLIES_PER_RACK];
        /** Each cell's item as last saved: the stack does not change while it is in the rack, so it is written once. */
        private final CompoundTag[] savedItems = new CompoundTag[SpentFuelPool.ASSEMBLIES_PER_RACK];
        /** Decay power and release weight of each cell at game time {@link #nextAt}, worked out ahead of that step. */
        final double[] nextW = new double[SpentFuelPool.ASSEMBLIES_PER_RACK];
        final double[] nextRelease = new double[SpentFuelPool.ASSEMBLIES_PER_RACK];
        long nextAt = Long.MIN_VALUE;
        /** The slots' decay power has been set since the rack was loaded (by a step or an insertion). */
        boolean heated;
        /** Index in the pool's rack list. */
        int index;

        /** Puts {@code stack} in cell {@code k} (or empties it): its curve and saved form are made again when needed. */
        void set(int k, ItemStack stack) {
            stacks[k] = stack;
            curves[k] = null;
            savedItems[k] = null;
            nextAt = Long.MIN_VALUE;
        }

        SpentFuelHeat.Curve curve(int k) {
            SpentFuelHeat.Curve c = curves[k];
            if (c == null) {
                c = new SpentFuelHeat.Curve(stacks[k]);
                curves[k] = c;
            }
            return c;
        }

        CompoundTag savedItem(int k, HolderLookup.Provider registries) {
            CompoundTag t = savedItems[k];
            if (t == null) {
                t = (CompoundTag) stacks[k].save(registries);
                savedItems[k] = t;
            }
            return t;
        }

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

    /**
     * The pool's numbers. {@code rackCount} is the number of racks; {@code racks} holds a line for each of them from
     * {@link #view()} and is empty from {@link #summary()} (a panel or a computer asks for the racks it shows,
     * {@link #rackViews}).
     */
    public record PoolView(boolean formed, double massKg, double capacityKg, double levelM, double temperatureC,
                           double waterAboveFuelM, double highestRackTopM, int assemblies, int cells,
                           double decayW, double coveredDecayW, double uncoveredDecayW,
                           double evaporationKgPerS, double boilKgPerS, double passiveLossW, double coolingW,
                           double secondsToBoil, double secondsToUncover,
                           boolean coolingEnabled, boolean coolingRunning, int energyFE,
                           double coolerColdKg, double coolerHotKg, double coolerColdC, double coolerHotC,
                           double hazard, int damaged, int burning, int rackCount, List<RackView> racks) {
    }

    private final SpentFuelPool pool = new SpentFuelPool();
    private final SpentFuelPoolCooler cooler = new SpentFuelPoolCooler();
    private final dev.bwr.mod.refuelling.CanalExchange canalExchange = new dev.bwr.mod.refuelling.CanalExchange();
    private final LinkedHashMap<BlockPos, Rack> racks = new LinkedHashMap<>();
    private final List<Rack> rackList = new ArrayList<>();
    private SpentFuelPool.Slot[] flat = new SpentFuelPool.Slot[0];
    private final Set<BlockPos> owned = new HashSet<>();
    /** The racks of {@link #layout}, as a set. */
    private final Set<BlockPos> surveyedRacks = new HashSet<>();
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
    /** The last survey that failed at one block, while the pool is not formed: a retry first looks at that block. */
    private SpentFuelPoolLayout.Layout lastFailure;
    /** Surveys made, and the game time of the last one-second step: for the tests and tools. */
    private int surveys;
    private long lastStepAt = Long.MIN_VALUE;

    // client-side picture, filled from the update tag
    public boolean visFormed;
    public BlockPos visMin;
    public BlockPos visMax;
    public int visLevelQ;
    public long[] visRacks = new long[0];
    public byte[] visGlow = new byte[0];
    /** The glow strength (0 to 15) of each cell, rack by rack in {@link #rackList} order; kept up to date on the server. */
    private byte[] glow = new byte[0];
    private boolean glowChanged;
    private int sentLevelQ = Integer.MIN_VALUE;
    private boolean sentFormed;
    /** Bumped whenever a rack's decay heat or contents change: the radiation picture is rebuilt from it when asked. */
    private int heatVersion;
    private DoseSources doseSources;
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

    /** The water through a cattle chute slot in this pool's wall (idle for a pool without one). Server side. */
    public dev.bwr.mod.refuelling.CanalExchange canalExchange() {
        return canalExchange;
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
        if (level instanceof ServerLevel serverLevel) {
            tick(serverLevel, serverLevel.getGameTime());
        }
    }

    /** Test hook: one tick of the controller as if the game time were {@code now} (the cost tests time it). */
    public void tickForTest(long now) {
        if (level instanceof ServerLevel serverLevel) {
            tick(serverLevel, now);
        }
    }

    private void tick(ServerLevel serverLevel, long now) {
        if (!registered) {
            SpentFuelPoolRegistry.register(this);
            registered = true;
        }
        if (structureDirty ? now >= dirtyAt + SCAN_DEBOUNCE_TICKS : (!formed && now >= nextScan)) {
            if (!structureDirty && SpentFuelPoolLayout.stillBlocked(serverLevel, lastFailure)) {
                // the block that stopped the last survey is still there, so the pool cannot form yet: no survey
                nextScan = now + SCAN_RETRY_TICKS;
            } else {
                scan();
            }
        }
        if (formed) {
            prepareHeat(now);
            prepareSave(serverLevel);
        }
        if (formed && ++sinceStep >= STEP_TICKS) {
            sinceStep = 0;
            step(serverLevel, now);
        }
        // the water through a cattle chute slot, if the wall has one (a pool without one returns at once)
        if (formed && canalExchange.step(serverLevel, this, 1.0 / 20.0)) {
            markVisual(false);
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
        surveys++;
        SpentFuelPoolLayout.Layout found = SpentFuelPoolLayout.inspect(level, worldPosition);
        if (!found.ok()) {
            nextScan = now + SCAN_RETRY_TICKS;
            problem = found.problem();
            lastFailure = found;
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
        surveyedRacks.clear();
        surveyedRacks.addAll(found.racks());
        lastFailure = null;
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
            rackList.get(r).index = r;
            System.arraycopy(rackList.get(r).slots, 0, flat, r * SpentFuelPool.ASSEMBLIES_PER_RACK, SpentFuelPool.ASSEMBLIES_PER_RACK);
        }
        glow = new byte[flat.length];
        for (Rack rack : rackList) {
            for (int k = 0; k < SpentFuelPool.ASSEMBLIES_PER_RACK; k++) {
                glow[rack.index * SpentFuelPool.ASSEMBLIES_PER_RACK + k] = (byte) glowOf(rack, k);
            }
        }
        heatVersion++;
    }

    private List<ItemStack> dropAll(Rack rack, BlockPos at) {
        List<ItemStack> dropped = new ArrayList<>();
        for (int k = 0; k < rack.stacks.length; k++) {
            if (!rack.stacks[k].isEmpty()) {
                dropped.add(rack.stacks[k].copy());
                Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, rack.stacks[k]);
                rack.set(k, ItemStack.EMPTY);
                rack.slots[k].occupied = false;
            }
        }
        heatVersion++;
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
        rack.set(cell, one);
        SpentFuelPool.Slot slot = rack.slots[cell];
        slot.occupied = true;
        boolean covered = pool.massKg() > 0.0 && pool.levelM() >= rack.topM - 1.0e-9;
        slot.fuelC = covered ? pool.temperatureC() : SpentFuelPool.AMBIENT_C;
        slot.damaged = false;
        slot.ignited = false;
        slot.zirconiumLeft = 1.0;
        slot.covered = covered;
        slot.decayW = level == null ? 0.0 : rack.curve(cell).watts(level.getGameTime());
        rack.heated = true;
        heatVersion++;
        syncRackState(rack);
        setChanged();
        updateGlow(rack, cell);
        markVisual(false);
    }

    private ItemStack take(Rack rack, int cell) {
        ItemStack out = rack.stacks[cell];
        rack.set(cell, ItemStack.EMPTY);
        SpentFuelPool.Slot slot = rack.slots[cell];
        slot.occupied = false;
        slot.decayW = 0.0;
        heatVersion++;
        syncRackState(rack);
        setChanged();
        updateGlow(rack, cell);
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
            for (int k = 0; k < rack.stacks.length; k++) {
                if (rack.stacks[k].isEmpty()) {
                    place(rack, k, one.copyWithCount(1));
                    return true;
                }
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
                double watts = rack.curve(k).watts(now);
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
                    rack.set(k, ItemStack.EMPTY);
                    rack.slots[k].occupied = false;
                }
            }
        }
        heatVersion++;
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

    /** A cell of a rack: the bottom block of the rack, and the cell number 0 to 3. */
    public record CellRef(BlockPos rack, int cell) {
    }

    /** The first free cell, racks in the pool's order and cells in order (what a plain insert takes), or null when every cell is taken or the pool is not formed. */
    public CellRef firstFreeCell() {
        if (!formed) {
            return null;
        }
        for (Rack rack : rackList) {
            for (int k = 0; k < rack.stacks.length; k++) {
                if (rack.stacks[k].isEmpty()) {
                    return new CellRef(rack.pos, k);
                }
            }
        }
        return null;
    }

    /** How many cells are free (0 while the pool is not formed). */
    public int freeCells() {
        return formed ? cellCount() - assemblyCount() : 0;
    }

    /**
     * The cell of the assembly that has cooled longest (the least decay heat), or null when the pool holds none. Ranked by the
     * decay heat the pool refreshes every second (an assembly put in since then by its heat when it went in): a panel asks
     * this several times a second, and working out 8,192 curves each time would cost more than a millisecond.
     */
    public CellRef coolestCell() {
        if (!formed) {
            return null;
        }
        long now = level == null ? 0L : level.getGameTime();
        CellRef best = null;
        double bestWatts = Double.MAX_VALUE;
        for (Rack rack : rackList) {
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    double watts = rack.heated ? rack.slots[k].decayW : rack.curve(k).watts(now);
                    if (watts < bestWatts) {
                        bestWatts = watts;
                        best = new CellRef(rack.pos, k);
                    }
                }
            }
        }
        return best;
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

    /**
     * The kept decay curve of the assembly in a cell (null if the cell is empty): {@link SpentFuelHeat.Curve#watts} is exactly
     * {@link SpentFuelHeat#decayWatts} of the stack, without reading the item again. For computers and panels that list
     * thousands of assemblies.
     */
    public SpentFuelHeat.Curve cellCurve(BlockPos pos, int cell) {
        Rack rack = racks.get(pos);
        return rack == null || cell < 0 || cell >= rack.stacks.length || rack.stacks[cell].isEmpty() ? null : rack.curve(cell);
    }

    /**
     * The decay power of the assembly in a cell as the pool last worked it out (at its one-second step, or when the assembly
     * went in), W; 0 for an empty cell. For panels refreshed several times a second over thousands of cells.
     */
    public double cellDecayW(BlockPos pos, int cell) {
        Rack rack = racks.get(pos);
        if (rack == null || cell < 0 || cell >= rack.stacks.length || rack.stacks[cell].isEmpty()) {
            return 0.0;
        }
        return rack.heated ? rack.slots[cell].decayW : rack.curve(cell).watts(level == null ? 0L : level.getGameTime());
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

    /**
     * Works out, for this tick's twentieth of the racks, the decay power each assembly will have at the next step (the tick
     * on which {@link #sinceStep} reaches {@link #STEP_TICKS}). Nothing the pool shows or does changes here: the step takes
     * these numbers over only if they were made for its own game time and the rack has not changed since.
     */
    private void prepareHeat(long now) {
        int n = rackList.size();
        int part = sinceStep;
        if (n == 0 || part < 0 || part >= STEP_TICKS) {
            return;
        }
        long stepAt = now + (STEP_TICKS - 1 - part);
        int to = (int) ((long) (part + 1) * n / STEP_TICKS);
        for (int r = (int) ((long) part * n / STEP_TICKS); r < to; r++) {
            Rack rack = rackList.get(r);
            if (rack.nextAt == stepAt) {
                continue;
            }
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    SpentFuelHeat.Curve curve = rack.curve(k);
                    rack.nextW[k] = curve.watts(stepAt);
                    rack.nextRelease[k] = Math.min(1.0, curve.burnupMwdPerTonne / 10_000.0);
                }
            }
            rack.nextAt = stepAt;
        }
    }

    /** Racks looked at per tick for an assembly whose saved form is not written yet. */
    private static final int SAVE_PREPARE_RACKS = 2;
    private int saveCursor;

    /**
     * Writes the saved form of a few stored assemblies a tick ({@link Rack#savedItem}), so the first save after a load or a big
     * delivery does not encode 8,192 items at once (about 80 ms); a save then only copies references. Each stack is written once.
     */
    private void prepareSave(ServerLevel l) {
        int n = rackList.size();
        for (int i = 0; i < SAVE_PREPARE_RACKS && n > 0; i++) {
            saveCursor = saveCursor >= n - 1 ? 0 : saveCursor + 1;
            Rack rack = rackList.get(saveCursor);
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    rack.savedItem(k, l.registryAccess());
                }
            }
        }
    }

    /** One second of physics at game time {@code now}. */
    private void step(ServerLevel l, long now) {
        lastStepAt = now;
        double total = 0.0;
        for (Rack rack : rackList) {
            boolean ready = rack.nextAt == now;
            for (int k = 0; k < rack.stacks.length; k++) {
                SpentFuelPool.Slot slot = rack.slots[k];
                ItemStack stack = rack.stacks[k];
                slot.occupied = !stack.isEmpty();
                if (slot.occupied) {
                    if (ready) {
                        slot.decayW = rack.nextW[k];
                        slot.releaseWeight = rack.nextRelease[k];
                    } else {
                        SpentFuelHeat.Curve curve = rack.curve(k);
                        slot.decayW = curve.watts(now);
                        slot.releaseWeight = Math.min(1.0, curve.burnupMwdPerTonne / 10_000.0);
                    }
                    total += slot.decayW;
                } else {
                    slot.decayW = 0.0;
                }
            }
            rack.heated = true;
        }
        heatVersion++;
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
        for (Rack rack : rackList) {
            for (int k = 0; k < SpentFuelPool.ASSEMBLIES_PER_RACK; k++) {
                updateGlow(rack, k);
            }
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

    /** How many structure surveys this controller has made (tests: a retry that found the same block in the way makes none). */
    public int surveyCount() {
        return surveys;
    }

    /** Game time of the last one-second step ({@code Long.MIN_VALUE} before the first). */
    public long lastStepAt() {
        return lastStepAt;
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

    private static RackView rackView(Rack rack) {
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
        return new RackView(rack.pos, rack.topM, rack.count(), decay, hottest, uncovered, rackDamaged, rackBurning);
    }

    /** Every number of the pool with a line for every rack (up to 2,048 of them: for tests and tools, not for a panel's 4 Hz). */
    public PoolView view() {
        List<RackView> rackViews = new ArrayList<>(rackList.size());
        for (Rack rack : rackList) {
            rackViews.add(rackView(rack));
        }
        return view(rackViews);
    }

    /** Every number of the pool without the rack lines ({@link PoolView#racks()} is empty; {@link #rackViews} gives the ones shown). */
    public PoolView summary() {
        return view(List.of());
    }

    private PoolView view(List<RackView> rackViews) {
        int damaged = 0;
        int burning = 0;
        // only stored assemblies count: an emptied cell's slot keeps the flags of what was in it
        for (Rack rack : rackList) {
            for (int k = 0; k < rack.stacks.length; k++) {
                if (!rack.stacks[k].isEmpty()) {
                    if (rack.slots[k].damaged) {
                        damaged++;
                    }
                    if (rack.slots[k].ignited) {
                        burning++;
                    }
                }
            }
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
                hazard, damaged, burning, rackList.size(), rackViews);
    }

    /** Racks in the pool, as last surveyed. */
    public int rackCount() {
        return rackList.size();
    }

    /** The bottom block of rack {@code index} (0-based, the pool's order: the order of {@link SpentFuelPoolLayout.Layout#racks()}). */
    public BlockPos rackPos(int index) {
        return index >= 0 && index < rackList.size() ? rackList.get(index).pos : null;
    }

    /** Whether {@code pos} is the bottom block of one of the racks of the last survey ({@link SpentFuelPoolLayout.Layout#racks()}, without its list search). */
    public boolean surveyedRack(BlockPos pos) {
        return layout != null && surveyedRacks.contains(pos);
    }

    /** Lines for racks {@code first} to {@code first + count - 1} (clamped to the racks there are). */
    public List<RackView> rackViews(int first, int count) {
        int from = Math.max(0, first);
        int to = (int) Math.min(rackList.size(), (long) from + Math.max(0, count));
        List<RackView> list = new ArrayList<>(Math.max(0, to - from));
        for (int r = from; r < to; r++) {
            list.add(rackView(rackList.get(r)));
        }
        return list;
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

    /** World Y of the pool's water surface (the interior floor plus the level); NaN while the pool is not formed. */
    public double surfaceY() {
        return formed && layout != null ? layout.min().getY() + 1 + pool.levelM() : Double.NaN;
    }

    /**
     * Whether the point is in the pool's water: inside the interior, above the floor and below the surface (the pool's water is a
     * body in the model, drawn as a surface; vanilla water blocks in it make no difference). False while the pool is not formed or empty.
     */
    public boolean inWater(double x, double y, double z) {
        if (!formed || layout == null || !(pool.massKg() > 0.0)) {
            return false;
        }
        BlockPos lo = layout.min();
        BlockPos hi = layout.max();
        return x >= lo.getX() + 1 && x <= hi.getX() && z >= lo.getZ() + 1 && z <= hi.getZ()
                && y >= lo.getY() + 1 && y < surfaceY();
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

    /** Up to this many racks with fuel, a dose point sees every rack on its own ({@link #forEachRackSource}, exactly as always). */
    public static final int EXACT_DOSE_SOURCES = 64;

    /**
     * A region is taken as one source at its heat-weighted centre once it is this small against its distance: side under
     * {@value} times the distance. The error of the lumped 1/r^2 is second order in that ratio, a few per cent at worst, and the
     * water and the blocks on the one line stand for those of its racks' lines.
     */
    public static final double DOSE_REGION_OPENING = 0.3;

    /**
     * The pool's fuel as dose sources for a point at ({@code x}, {@code y}, {@code z}). A pool with up to
     * {@value #EXACT_DOSE_SOURCES} racks holding fuel gives every rack, as {@link #forEachRackSource}. A larger one is a
     * quadtree over its floor: a near rack is its own source, a region of racks far enough away against its size
     * ({@link #DOSE_REGION_OPENING}) is one source of their summed heat at their heat-weighted centre (rounded to a sixteenth
     * of a block, so a shielding ray cached for it stays valid from one second to the next). A meter over the middle of a full
     * 2,048-rack pool sees a few hundred sources, not 2,048. Same visitor, same rack-top height and water cover.
     */
    public void forEachDoseSource(double x, double y, double z, RackSourceVisitor visitor) {
        if (!formed || layout == null) {
            return;
        }
        DoseSources tree = doseSources();
        if (tree == null || tree.fuelled <= EXACT_DOSE_SOURCES) {
            forEachRackSource(visitor);
            return;
        }
        tree.visit(tree.levels - 1, 0, 0, x, y, z, pool.levelM() - tree.topM, visitor);
    }

    private DoseSources doseSources() {
        if (doseSources == null || doseSources.version != heatVersion) {
            doseSources = DoseSources.build(this);
        }
        return doseSources;
    }

    /** The racks' decay heat summed over the squares of a quadtree of the pool's floor, rebuilt when the heat changes. */
    private static final class DoseSources {
        int version;
        int fuelled;
        int x0;
        int z0;
        int levels;
        int size;
        double topY;
        double topM;
        double[][] w;
        double[][] wx;
        double[][] wz;

        /** Null when the racks do not all share one rack-top height (never for a surveyed pool); the caller then goes rack by rack. */
        static DoseSources build(SpentFuelPoolBlockEntity be) {
            DoseSources t = new DoseSources();
            t.version = be.heatVersion;
            SpentFuelPoolLayout.Layout l = be.layout;
            t.x0 = l.min().getX() + 1;
            t.z0 = l.min().getZ() + 1;
            int span = Math.max(Math.max(1, l.max().getX() - t.x0), l.max().getZ() - t.z0);
            t.size = 1;
            t.levels = 1;
            while (t.size < span) {
                t.size <<= 1;
                t.levels++;
            }
            t.w = new double[t.levels][];
            t.wx = new double[t.levels][];
            t.wz = new double[t.levels][];
            for (int lv = 0; lv < t.levels; lv++) {
                int n = t.size >> lv;
                t.w[lv] = new double[n * n];
                t.wx[lv] = new double[n * n];
                t.wz[lv] = new double[n * n];
            }
            boolean first = true;
            for (Rack rack : be.rackList) {
                double watts = 0.0;
                for (int k = 0; k < rack.stacks.length; k++) {
                    if (!rack.stacks[k].isEmpty()) {
                        watts += rack.slots[k].decayW;
                    }
                }
                if (!(watts > 0.0)) {
                    continue;
                }
                double topY = rack.pos.getY() + rack.topM;
                int i = rack.pos.getX() - t.x0;
                int j = rack.pos.getZ() - t.z0;
                if (first) {
                    t.topY = topY;
                    t.topM = rack.topM;
                    first = false;
                } else if (topY != t.topY || rack.topM != t.topM) {
                    return null;
                }
                if (i < 0 || j < 0 || i >= t.size || j >= t.size) {
                    return null;
                }
                int index = i * t.size + j;
                t.w[0][index] = watts;
                t.wx[0][index] = watts * (rack.pos.getX() + 0.5);
                t.wz[0][index] = watts * (rack.pos.getZ() + 0.5);
                t.fuelled++;
            }
            for (int lv = 1; lv < t.levels; lv++) {
                int n = t.size >> lv;
                int below = n * 2;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double sw = 0.0;
                        double sx = 0.0;
                        double sz = 0.0;
                        for (int a = 0; a < 2; a++) {
                            for (int b = 0; b < 2; b++) {
                                int c = (2 * i + a) * below + 2 * j + b;
                                sw += t.w[lv - 1][c];
                                sx += t.wx[lv - 1][c];
                                sz += t.wz[lv - 1][c];
                            }
                        }
                        t.w[lv][i * n + j] = sw;
                        t.wx[lv][i * n + j] = sx;
                        t.wz[lv][i * n + j] = sz;
                    }
                }
            }
            return t;
        }

        void visit(int lv, int i, int j, double x, double y, double z, double cover, RackSourceVisitor visitor) {
            int n = size >> lv;
            int index = i * n + j;
            double watts = w[lv][index];
            if (!(watts > 0.0)) {
                return;
            }
            if (lv == 0) {
                visitor.accept(x0 + i + 0.5, topY, z0 + j + 0.5, watts, cover);
                return;
            }
            double cx = Math.round(wx[lv][index] / watts * 16.0) / 16.0;
            double cz = Math.round(wz[lv][index] / watts * 16.0) / 16.0;
            double dx = cx - x;
            double dy = topY - y;
            double dz = cz - z;
            double side = 1 << lv;
            if (side * side < DOSE_REGION_OPENING * DOSE_REGION_OPENING * (dx * dx + dy * dy + dz * dz)) {
                visitor.accept(cx, topY, cz, watts, cover);
                return;
            }
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    visit(lv - 1, 2 * i + a, 2 * j + b, x, y, z, cover, visitor);
                }
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
        for (SpentFuelPoolLayout.Slot slot : layout.slots()) {
            lines.add(BwrText.tr("status.bwr.spent_fuel_pool.slot",
                    "Cattle chute slot in the wall at x %s, z %s: y %s to the rim at y %s, sill y %s.",
                    slot.x(), slot.z(), slot.bottomY(), slot.topY(), String.format(Locale.ROOT, "%.2f", slot.sillY())));
        }
        lines.addAll(canalExchange.statusComponents());
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
        // the racks themselves only change with a survey or a broken rack, which force; the glow keeps its own record of changes
        int levelQ = (int) Math.round(pool.levelM() * 8.0);
        if (force || glowChanged || levelQ != sentLevelQ || formed != sentFormed) {
            glowChanged = false;
            sentLevelQ = levelQ;
            sentFormed = formed;
            visualPending = true;
        }
    }

    /** Brings the glow byte of one cell up to date and notes whether the picture changed. */
    private void updateGlow(Rack rack, int k) {
        int i = rack.index * SpentFuelPool.ASSEMBLIES_PER_RACK + k;
        if (i < 0 || i >= glow.length || rack.index >= rackList.size() || rackList.get(rack.index) != rack) {
            return;
        }
        byte g = (byte) glowOf(rack, k);
        if (glow[i] != g) {
            glow[i] = g;
            glowChanged = true;
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
            writeRackPicture(tag);
        }
        return tag;
    }

    /**
     * The racks as a bitmap of the interior floor (one bit a square, x-major: bit {@code ix * depth + iz}) and their cells' glow
     * as four bits each, two bytes a rack, in the bitmap's order. Every rack stands on the floor, one block above the shell's
     * bottom. For the largest pool that is 481 bytes of bitmap and 4,096 of glow, against 24 kB as positions and bytes.
     */
    private void writeRackPicture(CompoundTag tag) {
        int x0 = layout.min().getX() + 1;
        int z0 = layout.min().getZ() + 1;
        int iw = Math.max(0, layout.max().getX() - x0);
        int id = Math.max(0, layout.max().getZ() - z0);
        int[] rackAt = new int[iw * id];
        Arrays.fill(rackAt, -1);
        for (int r = 0; r < rackList.size(); r++) {
            BlockPos p = rackList.get(r).pos;
            int ix = p.getX() - x0;
            int iz = p.getZ() - z0;
            if (ix >= 0 && iz >= 0 && ix < iw && iz < id) {
                rackAt[ix * id + iz] = r;
            }
        }
        byte[] map = new byte[(rackAt.length + 7) / 8];
        int count = 0;
        for (int i = 0; i < rackAt.length; i++) {
            if (rackAt[i] >= 0) {
                map[i >> 3] |= (byte) (1 << (i & 7));
                count++;
            }
        }
        byte[] cells = new byte[count * 2];
        int n = 0;
        for (int r : rackAt) {
            if (r < 0) {
                continue;
            }
            int g = r * SpentFuelPool.ASSEMBLIES_PER_RACK;
            int g0 = g < glow.length ? glow[g] & 15 : 0;
            int g1 = g + 1 < glow.length ? glow[g + 1] & 15 : 0;
            int g2 = g + 2 < glow.length ? glow[g + 2] & 15 : 0;
            int g3 = g + 3 < glow.length ? glow[g + 3] & 15 : 0;
            cells[n++] = (byte) (g0 | g1 << 4);
            cells[n++] = (byte) (g2 | g3 << 4);
        }
        tag.putByteArray("VisRackMap", map);
        tag.putByteArray("VisRackGlow", cells);
    }

    /** Size in bytes of the picture the controller sends a client now (the update tag, written out): the cost tests read it. */
    public int updateTagBytes(HolderLookup.Provider registries) {
        var out = new java.io.ByteArrayOutputStream();
        try {
            net.minecraft.nbt.NbtIo.writeAnyTag(getUpdateTag(registries), new java.io.DataOutputStream(out));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.size();
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
            readRackPicture(tag.getByteArray("VisRackMap"), tag.getByteArray("VisRackGlow"));
        } else {
            visMin = null;
            visMax = null;
            visRacks = new long[0];
            visGlow = new byte[0];
        }
    }

    /** Unpacks {@link #writeRackPicture} into {@link #visRacks} (bottom blocks) and {@link #visGlow} (one byte a cell). */
    private void readRackPicture(byte[] map, byte[] cells) {
        int x0 = visMin.getX() + 1;
        int z0 = visMin.getZ() + 1;
        int y = visMin.getY() + 1;
        int id = Math.max(0, visMax.getZ() - z0);
        int squares = Math.max(0, visMax.getX() - x0) * id;
        int count = 0;
        for (int i = 0; i < squares && (i >> 3) < map.length; i++) {
            if ((map[i >> 3] & (1 << (i & 7))) != 0) {
                count++;
            }
        }
        long[] positions = new long[count];
        byte[] strengths = new byte[count * SpentFuelPool.ASSEMBLIES_PER_RACK];
        int r = 0;
        for (int i = 0; i < squares && (i >> 3) < map.length; i++) {
            if ((map[i >> 3] & (1 << (i & 7))) == 0) {
                continue;
            }
            positions[r] = BlockPos.asLong(x0 + i / id, y, z0 + i % id);
            if (2 * r + 1 < cells.length) {
                strengths[4 * r] = (byte) (cells[2 * r] & 15);
                strengths[4 * r + 1] = (byte) ((cells[2 * r] >> 4) & 15);
                strengths[4 * r + 2] = (byte) (cells[2 * r + 1] & 15);
                strengths[4 * r + 3] = (byte) ((cells[2 * r + 1] >> 4) & 15);
            }
            r++;
        }
        visRacks = positions;
        visGlow = strengths;
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
                cell.put("Item", rack.savedItem(k, registries));
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
                    rack.set(k, stack);
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
