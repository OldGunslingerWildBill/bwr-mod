package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.LargeRtgStore;
import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Large RTG's controller: up to 1,000 kg of isotope ingots, blocks and capsules in one bulk store
 * ({@link LargeRtgStore}), and the same physics as the small RTG over it.
 *
 * <h2>Loading and mass</h2>
 * Anything with an entry in the RTG fuel list goes in: by hand (right-click with it, or the screen's load button), or
 * by hopper or pipe through the item capability (insert only, from any face of any cell). The block keeps a count per item
 * kind, not 4,000 slots; the mass is the sum of count times the list's grams per item, and an item that would take the
 * total past 1,000 kg is refused whole. Nothing comes out except by breaking the block, which drops every item that went
 * in (as the same items, so the mass is conserved to the item).
 *
 * <h2>The ticker (one per assembly)</h2>
 * The same generator pattern as the small RTG: a server ticker on this block entity only (the other eleven cells have no
 * block entity and no ticker). An empty unit with nothing in the buffer returns after one boolean test; a fuelled one does
 * one modulo a tick, and every {@value #PUSH_INTERVAL} ticks settles the buffer from the game clock (lazy: {@code rate x
 * ticks since last}) and offers it to what touches the bottom layer. Every 100 ticks it also confirms the footprint is whole
 * (twelve block reads), which stands in for the per-cell scheduled ticks the older assemblies use.
 *
 * <h2>Heat-only</h2>
 * The thermocouples convert only about 5 %, so the unit makes its thermal power regardless; the switch only stops the
 * electricity. Used as a heater (RTG.md) it is the same body with the 5 % not sent to the grid.
 */
public class LargeRtgBlockEntity extends BlockEntity {

    /** Server ticks between pushes. */
    public static final int PUSH_INTERVAL = 5;
    /** Ticks between footprint checks. */
    private static final int STRUCTURE_INTERVAL = 100;
    /** Energy the block can hold, FE: about 14 ticks of a full cobalt unit, five minutes of a small strontium load. */
    public static final int BUFFER_FE = 1_000_000;
    /** Photon power below which the block registers no point source in the world, W (see {@link #updateRadiation}). */
    public static final double MIN_SOURCE_WATTS = 1.0e-9;

    private final LargeRtgStore store = new LargeRtgStore();
    private boolean heatOnly;
    private double buffer;
    private long lastSettle;
    private boolean registeredSource;

    /** Insert-only item access: a hopper or pipe loads it; nothing can be taken out. */
    private final IItemHandler itemCapability = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || !RtgFuels.isFuel(stack)) {
                return stack;
            }
            int taken = accept(stack, stack.getCount(), simulate);
            return taken >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - taken);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && RtgFuels.isFuel(stack);
        }
    };

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

    public LargeRtgBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.LARGE_RTG.get(), pos, state);
    }

    // --- The ticker ---------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, LargeRtgBlockEntity be) {
        be.tick(level, state);
    }

    private void tick(Level level, BlockState state) {
        long now = level.getGameTime();
        if ((now + worldPosition.asLong()) % STRUCTURE_INTERVAL == 0 && LargeRtgBlock.allLoaded(level, worldPosition, state.getValue(LargeRtgBlock.FACING))
                && !LargeRtgBlock.complete(level, worldPosition, state)) {
            level.destroyBlock(worldPosition, true);
            return;
        }
        if (store.isEmpty() && buffer < 1.0) {
            return;
        }
        if ((now + worldPosition.asLong()) % PUSH_INTERVAL != 0) {
            return;
        }
        settle();
        if (buffer < 1.0) {
            return;
        }
        int offer = (int) Math.floor(buffer);
        Direction facing = state.getValue(LargeRtgBlock.FACING);
        Set<Long> own = new HashSet<>();
        for (int i = 0; i < LargeRtgBlock.CELLS; i++) {
            own.add(LargeRtgBlock.cellPos(worldPosition, facing, i).asLong());
        }
        // Everything touching the bottom layer: its four sides and its underside (the layers above are the radiator stack).
        for (int i = 0; i < LargeRtgBlock.CELLS && offer > 0; i++) {
            if (LargeRtgBlock.offset(i).getY() != 0) {
                continue;
            }
            BlockPos cell = LargeRtgBlock.cellPos(worldPosition, facing, i);
            for (Direction face : Direction.values()) {
                if (face == Direction.UP || offer <= 0) {
                    continue;
                }
                BlockPos next = cell.relative(face);
                if (own.contains(next.asLong()) || !level.isLoaded(next)) {
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
    }

    /** Brings the buffer up to the game clock: rate times the ticks since the last settle, capped at {@link #BUFFER_FE}. */
    private void settle() {
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        long elapsed = now - lastSettle;
        lastSettle = now;
        if (elapsed <= 0 || store.isEmpty()) {
            return;
        }
        double rate = output().fePerTick();
        if (rate > 0.0) {
            buffer = Math.min(BUFFER_FE, buffer + rate * Math.min(elapsed, 24_000L));
        }
    }

    // --- Contents -------------------------------------------------------------------

    private static String key(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static LargeRtgStore.Fuel fuelOf(RtgFuel f) {
        return new LargeRtgStore.Fuel(f.isotope(), f.specificPowerWPerG(), f.halfLifeYears(), f.gramsPerItem(), f.gammaWPerG(),
                f.capsuleTransmission(), f.leadHvlCm());
    }

    private double nowSeconds() {
        return level == null ? 0.0 : level.getGameTime() / RadioisotopePower.TICKS_PER_SECOND;
    }

    /** How many of {@code stack} (up to {@code wanted}) go in; with {@code simulate} false they are put in. */
    int accept(ItemStack stack, int wanted, boolean simulate) {
        RtgFuel data = RtgFuels.of(stack);
        if (data == null || data.gramsPerItem() <= 0.0 || wanted <= 0 || level == null) {
            return 0;
        }
        if (simulate) {
            return (int) Math.min(wanted, store.room(data.gramsPerItem()));
        }
        if (!level.isClientSide()) {
            settle();
        }
        int n = (int) store.insert(key(stack), fuelOf(data), wanted, nowSeconds());
        if (n > 0) {
            setChanged();
            updateRadiation();
        }
        return n;
    }

    /** Loads up to {@code count} of {@code stack}'s item; returns how many went in (the caller removes them from the stack). */
    public int loadFrom(int count, ItemStack stack) {
        return accept(stack, Math.min(count, stack.getCount()), false);
    }

    public IItemHandler itemCapability() {
        return itemCapability;
    }

    public IEnergyStorage energy() {
        return energyCapability;
    }

    public LargeRtgStore store() {
        return store;
    }

    public boolean heatOnly() {
        return heatOnly;
    }

    public void setHeatOnly(boolean value) {
        if (heatOnly != value) {
            settle();
            heatOnly = value;
            setChanged();
        }
    }

    /** Energy waiting in the buffer, FE. */
    public double bufferFe() {
        settle();
        return buffer;
    }

    /** Power, heat and dose now, from the store, the clock, the mode and the server's output multiplier. */
    public LargeRtgStore.Output output() {
        if (level == null) {
            return LargeRtgStore.Output.NONE;
        }
        return store.output(nowSeconds(), heatOnly, GameplayConfig.rtgOutputMultiplier());
    }

    /** Thermal power the body gives off now, W: what warms the room (Cold Sweat reads this). Zero when empty. */
    public double thermalWatts() {
        return output().thermalWatts();
    }

    // --- Radiation ---------------------------------------------------------------------

    /** The middle of the assembly, where its point source sits. */
    private Vec3 centre() {
        if (level == null) {
            return Vec3.atCenterOf(worldPosition);
        }
        Direction facing = getBlockState().getValue(LargeRtgBlock.FACING);
        Vec3 a = Vec3.atCenterOf(LargeRtgBlock.cellPos(worldPosition, facing, 0));
        Vec3 b = Vec3.atCenterOf(LargeRtgBlock.cellPos(worldPosition, facing, LargeRtgBlock.CELLS - 1));
        return a.add(b).scale(0.5);
    }

    /** Sets (or clears) this block's point source from the contents. Server side only. */
    private void updateRadiation() {
        if (level == null || level.isClientSide()) {
            return;
        }
        double outside = output().outsideGammaWatts();
        // Behind 40 cm of lead, strontium and caesium leave e-19 W of photons: a world source of that is a reading of zero to any
        // instrument and only costs the dose model a source to sum. Below a nanowatt (about 1e-6 mSv/h at a metre) none is registered;
        // the screen and the signal still print the true number.
        if (outside > MIN_SOURCE_WATTS) {
            // the housing is the whole footprint, in the 40 cm lead figure already: its cells are not counted again on the line
            Direction facing = getBlockState().getValue(LargeRtgBlock.FACING);
            RadiationSources.emit(level, this, centre(), outside, 1.0, false, dev.bwr.mod.radiation.BlockShielding.Box.of(
                    LargeRtgBlock.cellPos(worldPosition, facing, 0), LargeRtgBlock.cellPos(worldPosition, facing, LargeRtgBlock.CELLS - 1)));
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
            // Nothing is produced for the time the chunk was away: the clock restarts here (see RtgBlockEntity.onLoad).
            if (lastSettle != 0L) {
                settle();
            }
            lastSettle = level.getGameTime();
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

    /** What the block holds, as the items that went in; the store is empty afterwards (the energy buffer is lost). */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        for (LargeRtgStore.Bucket b : store.drain()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(b.key()));
            long left = b.count();
            int stackMax = Math.max(1, item.getDefaultMaxStackSize());
            while (left > 0) {
                int n = (int) Math.min(stackMax, left);
                drops.add(new ItemStack(item, n));
                left -= n;
            }
        }
        buffer = 0;
        setChanged();
        updateRadiation();
        return drops;
    }

    /** Spawns the contents where the controller stands. Server side. */
    public void dropContents() {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (ItemStack drop : removalDrops()) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, drop);
        }
    }

    /**
     * A dose rate for the Large RTG's readouts. The housing can stop an isotope to a number far below what
     * {@code DoseModel.formatRate} prints (it would say 0.00 uSv/h), so below 5 nSv/h the value is printed as it is, in
     * mSv/h and exponent form, and never rounded to nothing.
     */
    public static String doseText(double msvPerH) {
        if (!(msvPerH > 0.0)) {
            return "0 mSv/h";
        }
        if (msvPerH < 5.0e-6) {
            return String.format(Locale.ROOT, "%.2e mSv/h", msvPerH);
        }
        if (msvPerH >= 1.0e9) {
            return String.format(Locale.ROOT, "%.3g MSv/h", msvPerH / 1.0e9);
        }
        if (msvPerH >= 1.0e6) {
            return String.format(Locale.ROOT, "%.3g kSv/h", msvPerH / 1.0e6);
        }
        return DoseModel.formatRate(msvPerH);
    }

    public List<Component> statusComponents() {
        LargeRtgStore.Output r = output();
        List<Component> out = new ArrayList<>();
        if (store.isEmpty()) {
            out.add(BwrText.tr("status.bwr.large_rtg.empty", "Nothing in the Large RTG: it makes nothing"));
            return out;
        }
        out.add(BwrText.tr("status.bwr.large_rtg.mass", "%s kg of %s kg: %s W thermal, %s W electric, %s FE/t",
                String.format(Locale.ROOT, "%.2f", r.massGrams() / 1000.0), String.format(Locale.ROOT, "%.0f", LargeRtgStore.CAPACITY_GRAMS / 1000.0),
                String.format(Locale.ROOT, "%.0f", r.thermalWatts()), String.format(Locale.ROOT, "%.0f", r.electricWatts()),
                String.format(Locale.ROOT, "%.2f", r.fePerTick())));
        out.add(BwrText.tr("status.bwr.large_rtg.dose", "Dose at 1 m: %s", doseText(r.doseAtOneMetreMsvPerH())));
        if (heatOnly) {
            out.add(BwrText.tr("status.bwr.large_rtg.heat_only", "Heat only: no FE is made"));
        }
        return out;
    }

    // --- Persistence -------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Written only when there is something or the switch is off its default: an empty unit saves nothing.
        if (!store.isEmpty()) {
            ListTag kinds = new ListTag();
            for (LargeRtgStore.Bucket b : store.buckets()) {
                CompoundTag t = new CompoundTag();
                t.putString("Id", b.key());
                t.putLong("Count", b.count());
                t.putDouble("Epoch", b.epochSeconds());
                kinds.add(t);
            }
            tag.put("Kinds", kinds);
        }
        if (heatOnly) {
            tag.putBoolean("HeatOnly", true);
        }
        if (buffer >= 0.001) {
            tag.putDouble("Buffer", buffer);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        store.drain();
        if (tag.contains("Kinds", Tag.TAG_LIST)) {
            ListTag kinds = tag.getList("Kinds", Tag.TAG_COMPOUND);
            for (int i = 0; i < kinds.size(); i++) {
                CompoundTag t = kinds.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(t.getString("Id"));
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                    continue;
                }
                Item item = BuiltInRegistries.ITEM.get(id);
                RtgFuel data = RtgFuels.of(new ItemStack(item));
                long count = Math.max(0L, t.getLong("Count"));
                if (data == null || data.gramsPerItem() <= 0.0) {
                    // A fuel line that has gone from the data: keep the items (they drop when the block is broken), no power.
                    store.restore(id.toString(), new LargeRtgStore.Fuel("", 0.0, 0.0, 0.0, 0.0, 1.0, 1.0), count, 0.0);
                } else {
                    // A hand-edited save cannot hold more than the cap.
                    store.restore(id.toString(), fuelOf(data), Math.min(count, store.room(data.gramsPerItem())), t.getDouble("Epoch"));
                }
            }
        }
        heatOnly = tag.getBoolean("HeatOnly");
        buffer = Math.max(0.0, Math.min(BUFFER_FE, tag.getDouble("Buffer")));
        if (store.isEmpty()) {
            buffer = 0.0;
        }
    }
}
