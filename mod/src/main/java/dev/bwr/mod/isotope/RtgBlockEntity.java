package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The radioisotope thermoelectric generator (RTG). Capsules in, a small steady supply of FE out, for as long as the
 * isotopes last, which is decades.
 *
 * <h2>The physics</h2>
 * Each of its {@value #SLOTS} wells holds one item with an entry in the RTG fuel list ({@link RtgFuels}). Thermal power is the
 * sum over the wells of {@code grams x specific power x 2^(-t / half-life)}; electric power is that times
 * {@link RadioisotopePower#THERMOCOUPLE_EFFICIENCY}; FE per tick is that over 13.4 W per FE/t (the mod's one conversion)
 * times the server's {@code rtgOutputMultiplier}, which is 1 unless the owner changes it (see RTG.md). The decay clock of a
 * well starts when its capsule is put in, in world time; a capsule in a chest does not age. At the real half-lives
 * (decades to centuries) the decay is negligible in game time and the number says so.
 *
 * <h2>Pushing FE: the existing generator pattern</h2>
 * A generator has to hand its power on, so this block has a server ticker, as the turbine generator has (and the
 * reprocessing plant has for its work). It is as cheap as a ticker gets: an RTG with no capsule and no stored energy returns
 * after one boolean test; a fuelled one does one modulo and, every {@value #PUSH_INTERVAL} ticks, brings its small buffer
 * up to date from the game clock (the buffer fills lazily, {@code rate x ticks since last}, so a skipped tick loses
 * nothing) and offers it to the six neighbours that are loaded and can receive. The capability it exposes is also
 * extractable, so a cable that pulls (Mekanism's, for one) gets the same energy without waiting for a push.
 * Nothing is produced for the time a chunk is unloaded: the clock restarts on load.
 *
 * <h2>Heat and dose</h2>
 * The body always runs at its capsules' thermal power (a sealed unit has no off switch; the buffer being full only stops
 * the electricity). It is also a gamma source at the {@code RadiationSources} extension point: the capsules' photon power times
 * {@link RadioisotopePower#BODY_TRANSMISSION}, set when the contents change and when the chunk loads, cleared when it is
 * removed or unloaded. Strontium-90 and americium-241 read a few tenths of a mSv/h or less at a metre; caesium-137 is
 * dangerous without a wall.
 */
public class RtgBlockEntity extends BlockEntity {

    public static final int SLOTS = 6;
    /** Server ticks between pushes. */
    public static final int PUSH_INTERVAL = 5;
    /** Energy the block can hold, FE: a few seconds of a full strontium unit; it never holds more. */
    public static final int BUFFER_FE = 1_000;
    private static final long NOT_LOADED = -1L;

    private final ItemStackHandler fuel = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            contentsChanged(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return RtgFuels.isFuel(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    /** World time each well's capsule went in (the start of its decay clock), or {@link #NOT_LOADED}. */
    private final long[] loadedAt = new long[SLOTS];
    private double buffer;
    private long lastSettle;
    private boolean fuelled;
    private boolean registeredSource;
    /** The heat-only switch: true stops the electricity (the heat is unchanged). Saved only when true. */
    private boolean heatOnly;

    /** What the block is making now. */
    public record Readout(double thermalWatts, double electricWatts, double fePerTick, double gammaWatts,
                          double doseAtOneMetreMsvPerH, double shortestHalfLifeYears, int capsules) {
        public static final Readout NONE = new Readout(0, 0, 0, 0, 0, 0, 0);
    }

    /** Extract-only energy: a generator, not a battery. */
    private final IEnergyStorage energyCapability = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            settle();
            int n = Math.min(Math.max(0, maxExtract), (int) Math.floor(buffer));
            if (!simulate && n > 0) {
                buffer -= n;
                setChanged();
            }
            return n;
        }

        @Override
        public int getEnergyStored() {
            settle();
            return (int) Math.floor(buffer);
        }

        @Override
        public int getMaxEnergyStored() {
            return BUFFER_FE;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    public RtgBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RTG.get(), pos, state);
        java.util.Arrays.fill(loadedAt, NOT_LOADED);
    }

    // --- The ticker: push, cheaply -------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, RtgBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        if (!fuelled && buffer < 1.0) {
            return;
        }
        long now = level.getGameTime();
        if ((now + worldPosition.asLong()) % PUSH_INTERVAL != 0) {
            return;
        }
        settle();
        if (buffer < 1.0) {
            return;
        }
        int offer = (int) Math.floor(buffer);
        Direction[] faces = Direction.values();
        int start = (int) Math.floorMod(now / PUSH_INTERVAL, 6L);
        for (int i = 0; i < 6 && offer > 0; i++) {
            Direction face = faces[(start + i) % 6];
            BlockPos next = worldPosition.relative(face);
            if (!level.isLoaded(next)) {
                continue;
            }
            IEnergyStorage target = level.getCapability(Capabilities.EnergyStorage.BLOCK, next, face.getOpposite());
            if (target != null && target.canReceive()) {
                int taken = Math.max(0, Math.min(offer, target.receiveEnergy(offer, false)));
                buffer -= taken;
                offer -= taken;
            }
        }
    }

    /** Brings the buffer up to the game clock: rate times the ticks since the last settle, capped at {@link #BUFFER_FE}. */
    private void settle() {
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        long elapsed = now - lastSettle;
        lastSettle = now;
        if (elapsed <= 0 || !fuelled) {
            return;
        }
        double rate = readout().fePerTick();
        if (rate > 0.0) {
            buffer = Math.min(BUFFER_FE, buffer + rate * Math.min(elapsed, 24_000L));
        }
    }

    // --- Contents --------------------------------------------------------------

    private void contentsChanged(int slot) {
        if (level != null && !level.isClientSide()) {
            settle();
        }
        ItemStack held = fuel.getStackInSlot(slot);
        if (held.isEmpty()) {
            loadedAt[slot] = NOT_LOADED;
        } else if (loadedAt[slot] == NOT_LOADED && level != null) {
            loadedAt[slot] = level.getGameTime();
        }
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            any |= !fuel.getStackInSlot(i).isEmpty();
        }
        fuelled = any;
        setChanged();
        updateRadiation();
    }

    /** True when the unit is used as a heater only: it still runs at its full thermal power, and makes no FE. */
    public boolean heatOnly() {
        return heatOnly;
    }

    public void setHeatOnly(boolean value) {
        if (heatOnly != value) {
            if (level != null && !level.isClientSide()) {
                settle();
            }
            heatOnly = value;
            setChanged();
        }
    }

    public ItemStackHandler fuel() {
        return fuel;
    }

    public IEnergyStorage energy() {
        return energyCapability;
    }

    /** Test hook: the world time well {@code slot}'s capsule went in. */
    public void setLoadedAt(int slot, long gameTime) {
        loadedAt[slot] = gameTime;
        setChanged();
    }

    public long loadedAt(int slot) {
        return loadedAt[slot];
    }

    /** Energy waiting in the buffer, FE. */
    public double bufferFe() {
        settle();
        return buffer;
    }

    /** Power, heat and dose now, from the capsules, the clock and the server's output multiplier. */
    public Readout readout() {
        if (level == null || !fuelled) {
            return Readout.NONE;
        }
        long now = level.getGameTime();
        double thermal = 0.0;
        double gamma = 0.0;
        double halfLife = 0.0;
        int capsules = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack held = fuel.getStackInSlot(slot);
            RtgFuel data = RtgFuels.of(held);
            if (data == null) {
                continue;
            }
            capsules++;
            double age = loadedAt[slot] == NOT_LOADED ? 0.0 : Math.max(0L, now - loadedAt[slot]) / RadioisotopePower.TICKS_PER_SECOND;
            double factor = RadioisotopePower.decayFactor(data.halfLifeYears(), age);
            thermal += RadioisotopePower.thermalWatts(data.gramsPerItem(), data.specificPowerWPerG(), data.halfLifeYears(), age);
            gamma += RadioisotopePower.gammaWatts(data.gramsPerItem(), data.gammaWPerG(), data.capsuleTransmission()) * factor;
            if (data.halfLifeYears() > 0.0 && (halfLife == 0.0 || data.halfLifeYears() < halfLife)) {
                halfLife = data.halfLifeYears();
            }
        }
        double electric = heatOnly ? 0.0 : RadioisotopePower.electricWatts(thermal, RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        double fe = RadioisotopePower.fePerTick(electric, GameplayConfig.rtgOutputMultiplier());
        double outside = gamma * RadioisotopePower.BODY_TRANSMISSION;
        return new Readout(thermal, electric, fe, outside, DoseModel.pointSourceMsvPerH(outside, 1.0, 1.0), halfLife, capsules);
    }

    // --- Radiation --------------------------------------------------------------

    /** Sets (or clears) this block's point source from the contents. Server side only. */
    private void updateRadiation() {
        if (level == null || level.isClientSide()) {
            return;
        }
        double outside = readout().gammaWatts();
        if (outside > 0.0) {
            RadiationSources.emit(level, this, Vec3.atCenterOf(worldPosition), outside, 1.0);
            registeredSource = true;
        } else if (registeredSource) {
            RadiationSources.clear(level, this);
            registeredSource = false;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            // Nothing is produced for the time the chunk was away: the clock restarts here. A block entity that is loaded
            // fresh has no clock yet (lastSettle 0). One that is already running (onLoad is called again when the block
            // entity is re-attached to its chunk) first banks what it has made since its last settle, so no tick is dropped.
            if (lastSettle != 0L) {
                settle();
            }
            lastSettle = level.getGameTime();
            for (int slot = 0; slot < SLOTS; slot++) {
                if (!fuel.getStackInSlot(slot).isEmpty() && loadedAt[slot] == NOT_LOADED) {
                    loadedAt[slot] = level.getGameTime();
                }
            }
            updateRadiation();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && registeredSource) {
            RadiationSources.clear(level, this);
            registeredSource = false;
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && registeredSource) {
            RadiationSources.clear(level, this);
            registeredSource = false;
        }
        super.onChunkUnloaded();
    }

    /** What the block holds, for when it is broken: the capsules (the energy buffer is lost). */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack out = fuel.extractItem(slot, 1, false);
            if (!out.isEmpty()) {
                drops.add(out);
            }
        }
        buffer = 0;
        return drops;
    }

    public List<String> statusLines() {
        return BwrText.english(statusComponents());
    }

    public List<Component> statusComponents() {
        Readout r = readout();
        List<Component> out = new ArrayList<>();
        if (r.capsules() == 0) {
            out.add(BwrText.tr("status.bwr.rtg.empty", "No capsules in the RTG: it makes nothing"));
            return out;
        }
        out.add(BwrText.tr("status.bwr.rtg.power", "%s capsules: %s W thermal, %s W electric, %s FE/t",
                r.capsules(), String.format(Locale.ROOT, "%.1f", r.thermalWatts()),
                String.format(Locale.ROOT, "%.2f", r.electricWatts()), String.format(Locale.ROOT, "%.3f", r.fePerTick())));
        out.add(BwrText.tr("status.bwr.rtg.dose", "Dose at 1 m: %s", DoseModel.formatRate(r.doseAtOneMetreMsvPerH())));
        if (heatOnly) {
            out.add(BwrText.tr("status.bwr.rtg.heat_only", "Heat only: no FE is made"));
        }
        return out;
    }

    // --- Persistence --------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Written only when there is something: an empty RTG saves nothing.
        if (fuelled) {
            tag.put("Fuel", fuel.serializeNBT(registries));
            tag.putLongArray("LoadedAt", loadedAt.clone());
        }
        if (buffer >= 0.001) {
            tag.putDouble("Buffer", buffer);
        }
        if (heatOnly) {
            tag.putBoolean("HeatOnly", true);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        java.util.Arrays.fill(loadedAt, NOT_LOADED);
        if (tag.contains("Fuel")) {
            fuel.deserializeNBT(registries, tag.getCompound("Fuel"));
            if (fuel.getSlots() != SLOTS) {
                fuel.setSize(SLOTS);
            }
        } else {
            for (int slot = 0; slot < SLOTS; slot++) {
                fuel.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        long[] saved = tag.getLongArray("LoadedAt");
        for (int slot = 0; slot < SLOTS && slot < saved.length; slot++) {
            loadedAt[slot] = saved[slot];
        }
        boolean any = false;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (fuel.getStackInSlot(slot).isEmpty()) {
                loadedAt[slot] = NOT_LOADED;
            } else {
                any = true;
            }
        }
        fuelled = any;
        buffer = Math.max(0.0, Math.min(BUFFER_FE, tag.getDouble("Buffer")));
        heatOnly = tag.getBoolean("HeatOnly");
    }
}
