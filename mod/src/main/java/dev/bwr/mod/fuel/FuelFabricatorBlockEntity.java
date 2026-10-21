package dev.bwr.mod.fuel;

import net.minecraft.network.chat.Component;

import dev.bwr.mod.text.BwrText;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
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
 * The fuel fabricator. Turns Mekanism's enrichment products into bundles.
 * SPEC 2.4.
 *
 * <h2>How it works</h2>
 * Heavy metal is pulled out of the {@link FuelFeed} a little at a time and piled
 * up in a batch, along with a running total of how much of that heavy metal was
 * fissile. When the batch reaches one bundle's worth
 * ({@link FuelFabrication#batchHeavyMetalKg()}), the fabricator divides the two
 * and stamps the answer onto the assembly as its enrichment.
 *
 * <p>So the enrichment of the product is the time-average of the enrichment of
 * the feed. There is no dial, no recipe selection and no mode: a player who
 * wants HEU runs more Mekanism fissile fuel and less hexafluoride into the
 * machine, and the bundle that falls out says 20% because that is what they
 * put in. Their enrichment infrastructure is the enrichment control, which is
 * exactly what SPEC 2.4 buys by hanging off Mekanism instead of reimplementing
 * ore processing.
 *
 * <h2>The item route</h2>
 * There is a second way in that needs no Mekanism chemistry: uranium and steel
 * <i>items</i> (any mod's, by tag: see {@link FuelItemRoute}). A selector picks the
 * enrichment of the assembly, the cost in uranium is the enrichment plant's mass
 * balance for that enrichment ({@link FuelItemRoute#cost}), and the uranium that
 * does not become product comes out as depleted uranium ingots. The slots are
 * uranium in, steel in, assembly out and depleted uranium out
 * ({@link FabricatorItemView}).
 *
 * <p>Uranium goes from its slot into an internal store, because a 20 % assembly
 * takes 172 ingots and a stack holds 64; the store is topped up only as far as
 * the selected assembly needs, and breaking the block gives it back. Steel and
 * the finished work are committed together when the time is up, so nothing is
 * consumed by an assembly that does not happen. The depleted uranium of one
 * assembly can be more than a stack (167 for 20 %), so what the output slot
 * cannot hold waits in a second store and moves into the slot as it empties.
 *
 * <p>The two routes share the assembly slot and the energy buffer, and take
 * turns: the item route does not start while a chemical batch is partly built,
 * and the chemical route does not draw while an item assembly is being made.
 * An idle machine costs a boolean test per tick: the item route looks at its
 * inputs only when something about them changed.
 *
 * <h2>What it does not do</h2>
 * It does not refuse to build anything. There is no interlock on enrichment, no
 * criticality check on the batch, no cap on how much plutonium a player may
 * blend in. A fabricator asked to build a 90% plutonium bundle builds it. The
 * consequences of loading that into a core are the point kinetics equations'
 * business, and finding out is the player's.
 */
public class FuelFabricatorBlockEntity extends BlockEntity {

    /** Heavy metal drawn from the feed per tick while running, kg. ~18 s per bundle. */
    private static final double DRAW_KG_PER_TICK = 0.5;

    /** Energy consumed per tick while drawing, FE. */
    private static final int ENERGY_PER_TICK = 200;

    /** Buffer size, FE. Roughly one bundle's worth of work. */
    private static final int ENERGY_CAPACITY = 100_000;

    /** Output slot only. Feed arrives as chemicals; nothing is inserted by hand. */
    private static final int OUTPUT_SLOT = 0;

    /**
     * Heavy metal and its fissile content accumulated so far towards the next
     * bundle. Kept as kilograms rather than as a progress fraction because the
     * fissile total has to be tracked alongside it — progress alone would lose
     * the enrichment the player worked for.
     */
    private double batchHeavyMetalKg;
    private double batchFissileKg;
    private double batchPlutoniumKg;

    /** Most depleted uranium the machine will hold (slot plus waiting), ingots; it stops rather than void any. */
    private static final int TAILS_CAPACITY = 512;

    /** Stack size of a depleted uranium ingot. */
    private static final int TAILS_STACK = 64;

    private final FuelFeed feed = FuelFeeds.create();

    // --- item route state ---------------------------------------------------

    /** Selected assembly, a name on {@link FuelItemRoute#TARGETS}. Saved only when it is not the default. */
    private String target = FuelItemRoute.DEFAULT_TARGET;

    /** Uranium taken out of the input slot for the current assembly, half ingots. */
    private int uraniumUnits;

    /** Depleted uranium made but not yet moved into the output slot, ingots. */
    private int tailsPending;

    /** Ticks of work done on the current item assembly; 0 when none is under way. */
    private int workTicks;

    /** Set when something the item route looks at changed; an idle machine with it clear does no item work. */
    private boolean itemDirty = true;

    private FuelItemRoute.Cost cachedCost;
    private Object cachedRevision;
    private String cachedTarget;

    private final ItemStackHandler output = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            itemDirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // Nothing may be inserted: this slot is where bundles appear.
            return false;
        }
    };

    /**
     * Accepts energy from outside and refuses to give any back — a machine, not
     * a battery. Internal consumption goes through {@link MachineEnergy#drain},
     * which is not part of the capability, so a neighbouring cable can never
     * siphon the buffer back out.
     */
    private final class MachineEnergy extends EnergyStorage {

        MachineEnergy() {
            super(ENERGY_CAPACITY, ENERGY_CAPACITY / 10, 0);
        }

        @Override public int receiveEnergy(int amount, boolean simulate) {
            int accepted=super.receiveEnergy(amount,simulate);
            if(accepted>0 && !simulate) setChanged();
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

    private final ItemStackHandler tailsOut = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            itemDirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false; // depleted uranium appears here; nothing goes in
        }
    };

    private final ItemStackHandler uraniumIn = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            itemDirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return FuelItemRoute.uraniumUnits(stack) > 0;
        }
    };

    private final ItemStackHandler steelIn = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            itemDirty = true;
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return FuelItemRoute.isSteel(stack);
        }
    };

    private final FabricatorItemView viewAll = new FabricatorItemView(output, tailsOut, uraniumIn, steelIn, true, true);
    private final FabricatorItemView viewInputs = new FabricatorItemView(output, tailsOut, uraniumIn, steelIn, true, false);
    private final FabricatorItemView viewOutputs = new FabricatorItemView(output, tailsOut, uraniumIn, steelIn, false, true);

    public FuelFabricatorBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.FUEL_FABRICATOR.get(), pos, state);
        feed.setChangeListener(this::setChanged);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FuelFabricatorBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (itemDirty && tailsPending > 0) {
            flushTails();
        }
        if (!output.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            // Bundle waiting to be collected. Backing up rather than voiding is
            // the only sane behaviour for something that cost this much feed.
            return;
        }
        if (energy.getEnergyStored() < ENERGY_PER_TICK) {
            return;
        }
        if ((itemDirty || workTicks > 0) && tickItemRoute()) {
            return; // an item assembly is being made; the chemical feed waits
        }

        double wanted = Math.min(DRAW_KG_PER_TICK,
                FuelFabrication.batchHeavyMetalKg() - batchHeavyMetalKg);
        if (!(wanted > 0.0)) {
            return;
        }

        FuelFeed.Draw drawn = feed.draw(wanted);
        if (drawn.isEmpty()) {
            return;
        }

        energy.drain(ENERGY_PER_TICK);
        batchHeavyMetalKg += drawn.heavyMetalKg();
        batchFissileKg += drawn.fissileKg();
        batchPlutoniumKg += drawn.plutoniumKg();
        setChanged();

        if (batchHeavyMetalKg >= FuelFabrication.batchHeavyMetalKg()) {
            finishBundle();
        }
    }

    // --- the item route -----------------------------------------------------

    /** The cost of the selected assembly, cached until the target or the fuel table changes. Null if it cannot be made. */
    private FuelItemRoute.Cost currentCost() {
        Object revision = FuelTypes.revision();
        if (cachedTarget != target || cachedRevision != revision) {
            cachedCost = FuelItemRoute.cost(target);
            cachedTarget = target;
            cachedRevision = revision;
        }
        return cachedCost;
    }

    /**
     * One tick of the item route. Returns true when it used this tick to make an
     * assembly, so the chemical route must not also draw.
     */
    private boolean tickItemRoute() {
        FuelItemRoute.Cost cost = currentCost();
        if (cost == null) {
            workTicks = 0;
            itemDirty = false;
            return false;
        }
        absorbUranium(cost);
        if (workTicks == 0) {
            if (!canMake(cost)) {
                itemDirty = false;
                return false;
            }
            setChanged();
        }
        energy.drain(ENERGY_PER_TICK);
        workTicks++;
        if (workTicks >= cost.workTicks()) {
            workTicks = 0;
            if (canMake(cost)) {
                makeAssembly(cost);
            } else {
                itemDirty = false; // something was taken away while it ran; nothing was consumed
            }
            setChanged();
        }
        return true;
    }

    /** Moves ore from the input slot into the machine until it holds enough for one assembly. */
    private void absorbUranium(FuelItemRoute.Cost cost) {
        ItemStack held = uraniumIn.getStackInSlot(0);
        if (held.isEmpty() || uraniumUnits >= cost.units()) {
            return;
        }
        int each = FuelItemRoute.uraniumUnits(held);
        if (each <= 0) {
            return;
        }
        int wanted = (cost.units() - uraniumUnits + each - 1) / each;
        int taken = Math.min(wanted, held.getCount());
        uraniumIn.extractItem(0, taken, false);
        uraniumUnits += taken * each;
        setChanged();
    }

    private boolean canMake(FuelItemRoute.Cost cost) {
        return batchHeavyMetalKg <= 0.0
                && output.getStackInSlot(OUTPUT_SLOT).isEmpty()
                && uraniumUnits >= cost.units()
                && steelIn.getStackInSlot(0).getCount() >= cost.steel()
                && tailsHeld() + cost.tails() <= TAILS_CAPACITY;
    }

    private void makeAssembly(FuelItemRoute.Cost cost) {
        FuelType type = FuelTypes.byNameOrPreset(cost.fuelType());
        steelIn.extractItem(0, cost.steel(), false);
        uraniumUnits -= cost.units();
        tailsPending += cost.tails();
        output.setStackInSlot(OUTPUT_SLOT, FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),
                FuelAssemblyData.fresh(type != null ? type : FuelTypes.fallback())));
        flushTails();
    }

    /** Moves waiting depleted uranium into the output slot as far as it fits. */
    private void flushTails() {
        if (tailsPending <= 0) {
            return;
        }
        ItemStack held = tailsOut.getStackInSlot(0);
        int room = TAILS_STACK - held.getCount();
        int moved = Math.min(room, tailsPending);
        if (moved <= 0) {
            return;
        }
        tailsOut.setStackInSlot(0, held.isEmpty()
                ? new ItemStack(BwrItems.DEPLETED_URANIUM.get(), moved)
                : held.copyWithCount(held.getCount() + moved));
        tailsPending -= moved;
        setChanged();
    }

    private void finishBundle() {
        FuelAssemblyData data = FuelFabrication.fabricate(
                batchHeavyMetalKg, batchFissileKg, batchPlutoniumKg);
        output.setStackInSlot(OUTPUT_SLOT,
                FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), data));
        batchHeavyMetalKg = 0.0;
        batchFissileKg = 0.0;
        batchPlutoniumKg = 0.0;
        setChanged();
    }

    // --- Accessors ------------------------------------------------------

    public ItemStackHandler output() {
        return output;
    }

    public EnergyStorage energy() {
        return energy;
    }

    /** The chemical feed, or {@link FuelFeed#NONE}. Mekanism integration reaches through here. */
    public FuelFeed feed() {
        return feed;
    }

    /** Depleted uranium output slot. */
    public ItemStackHandler tailsOut() {
        return tailsOut;
    }

    public ItemStackHandler uraniumIn() {
        return uraniumIn;
    }

    public ItemStackHandler steelIn() {
        return steelIn;
    }

    /** The item capability: the four slots as a pipe or hopper may use them from this side (null: all of them). */
    public FabricatorItemView itemView(Direction side) {
        if (side == Direction.UP) {
            return viewInputs;
        }
        return side == Direction.DOWN ? viewOutputs : viewAll;
    }

    /** The selected assembly, a name on {@link FuelItemRoute#TARGETS}. */
    public String target() {
        return target;
    }

    /**
     * Selects the assembly the item route makes. A name that is not on the
     * ladder is refused. Changing it restarts an assembly under way (nothing has
     * been consumed by one, so nothing is lost).
     */
    public boolean setTarget(String name) {
        if (!FuelItemRoute.isTarget(name)) {
            return false;
        }
        if (!name.equals(target)) {
            target = name;
            workTicks = 0;
            itemDirty = true;
            setChanged();
        }
        return true;
    }

    /** What the selected assembly costs and returns, or null if it cannot be made. */
    public FuelItemRoute.Cost itemCost() {
        return currentCost();
    }

    /** Uranium the machine has taken in for the assembly, half ingots. */
    public int uraniumHeldUnits() {
        return uraniumUnits;
    }

    /** Ticks of work done on the assembly under way, 0 if none. */
    public int itemWorkTicks() {
        return workTicks;
    }

    /** Depleted uranium held, ingots: the output slot plus what waits to move into it. */
    public int tailsHeld() {
        return tailsOut.getStackInSlot(0).getCount() + tailsPending;
    }

    /**
     * Everything the machine holds as items, for when the block is broken:
     * the finished assembly, the depleted uranium, what is left in the input
     * slots and the uranium it had taken in (as ingots, of the first item any
     * mod has tagged as a uranium ingot; a half ingot is lost).
     */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        addDrop(drops, output.extractItem(0, 64, false));
        addDrop(drops, tailsOut.extractItem(0, 64, false));
        addDrop(drops, uraniumIn.extractItem(0, 64, false));
        addDrop(drops, steelIn.extractItem(0, 64, false));
        for (int left = tailsPending; left > 0; left -= TAILS_STACK) {
            drops.add(new ItemStack(BwrItems.DEPLETED_URANIUM.get(), Math.min(TAILS_STACK, left)));
        }
        tailsPending = 0;
        int ingots = uraniumUnits / FuelItemRoute.UNITS_PER_INGOT;
        uraniumUnits = 0;
        if (ingots > 0) {
            var holder = BuiltInRegistries.ITEM.getTag(FuelItemRoute.URANIUM_INGOTS)
                    .flatMap(set -> set.stream().findFirst());
            if (holder.isPresent()) {
                Item ingot = holder.get().value();
                for (int left = ingots; left > 0; left -= ingot.getDefaultMaxStackSize()) {
                    drops.add(new ItemStack(ingot, Math.min(ingot.getDefaultMaxStackSize(), left)));
                }
            }
        }
        return drops;
    }

    private static void addDrop(List<ItemStack> drops, ItemStack stack) {
        if (!stack.isEmpty()) {
            drops.add(stack);
        }
    }

    /** Heavy metal accumulated towards the next bundle, kg. */
    public double batchHeavyMetalKg() {
        return batchHeavyMetalKg;
    }

    /**
     * Enrichment the current batch would produce if it were finished right now.
     * Zero for an empty batch. A measurement of the pile, not a target.
     */
    public double batchEnrichmentWeightFraction() {
        return batchHeavyMetalKg > 0.0 ? batchFissileKg / batchHeavyMetalKg : 0.0;
    }

    public List<String> statusLines() {
        return BwrText.english(statusComponents());
    }

    public List<Component> statusComponents() {
        List<Component> out = new ArrayList<>();
        out.addAll(feed.statusComponents());
        out.add(BwrText.tr(
                "status.bwr.fuel_fabricator.batch_kg_hm_at_issile",
                "Batch %s / %s kg HM at %s%% fissile",
                String.format(java.util.Locale.ROOT, "%.1f", batchHeavyMetalKg),
                String.format(java.util.Locale.ROOT, "%.0f", FuelFabrication.batchHeavyMetalKg()),
                String.format(java.util.Locale.ROOT, "%.2f", batchEnrichmentWeightFraction() * 100.0)));
        if (batchHeavyMetalKg > 0.0) {
            out.add(BwrText.tr("status.bwr.fuel_fabricator.would_fabricate", "Would fabricate: %s" , FuelFabrication.fabricate(
                    batchHeavyMetalKg, batchFissileKg, batchPlutoniumKg).fuelTypeName()));
        }
        FuelItemRoute.Cost cost = currentCost();
        if (cost != null) {
            out.add(BwrText.tr(
                    "status.bwr.fuel_fabricator.item_route",
                    "Item route: %s takes %s uranium and %s steel and returns %s depleted uranium",
                    cost.fuelType(), cost.ingots(), cost.steel(), cost.tails()));
            out.add(BwrText.tr(
                    "status.bwr.fuel_fabricator.item_route_progress",
                    "Taken in %s of %s uranium, %s%% of the work done",
                    String.format(java.util.Locale.ROOT, "%.1f", uraniumUnits / (double) FuelItemRoute.UNITS_PER_INGOT),
                    cost.ingots(),
                    String.format(java.util.Locale.ROOT, "%.0f", 100.0 * workTicks / cost.workTicks())));
        }
        out.add(BwrText.tr(
                "status.bwr.fuel_fabricator.fe",
                "%s / %s FE",
                String.format(java.util.Locale.ROOT, "%,d", energy.getEnergyStored()),
                String.format(java.util.Locale.ROOT, "%,d", energy.getMaxEnergyStored())));
        ItemStack held = output.getStackInSlot(OUTPUT_SLOT);
        if (!held.isEmpty()) {
            out.add(BwrText.tr(
                    "status.bwr.fuel_fabricator.output_slot_holds_a_finished_bundle_the_machine",
                    "Output slot holds a finished bundle; the machine is stopped until it is taken."));
        }
        return out;
    }

    // --- Persistence -----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("BatchHeavyMetalKg", batchHeavyMetalKg);
        tag.putDouble("BatchFissileKg", batchFissileKg);
        tag.putDouble("BatchPlutoniumKg", batchPlutoniumKg);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.put("Output", output.serializeNBT(registries));
        tag.put("Feed", feed.save(registries));
        // Item route. Each key is written only when it holds something, so a
        // fabricator that has never used the item route saves exactly what it
        // saved before it existed, and one on the default target stays unmarked.
        if (!FuelItemRoute.DEFAULT_TARGET.equals(target)) {
            tag.putString("Target", target);
        }
        if (uraniumUnits > 0) {
            tag.putInt("UraniumUnits", uraniumUnits);
        }
        if (tailsPending > 0) {
            tag.putInt("TailsPending", tailsPending);
        }
        if (workTicks > 0) {
            tag.putInt("WorkTicks", workTicks);
        }
        if (!uraniumIn.getStackInSlot(0).isEmpty()) {
            tag.put("UraniumIn", uraniumIn.serializeNBT(registries));
        }
        if (!steelIn.getStackInSlot(0).isEmpty()) {
            tag.put("SteelIn", steelIn.serializeNBT(registries));
        }
        if (!tailsOut.getStackInSlot(0).isEmpty()) {
            tag.put("TailsOut", tailsOut.serializeNBT(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        batchHeavyMetalKg = tag.getDouble("BatchHeavyMetalKg");
        batchFissileKg = tag.getDouble("BatchFissileKg");
        batchPlutoniumKg = tag.getDouble("BatchPlutoniumKg");
        energy.setStored(tag.getInt("Energy"));
        if (tag.contains("Output")) {
            output.deserializeNBT(registries, tag.getCompound("Output"));
        }
        // A world saved with Mekanism and reopened without it loads the feed
        // tag into FuelFeed.NONE, which discards it. That is a data loss the
        // player caused by removing the mod; the finished bundle in the output
        // slot, which is the valuable part, survives regardless.
        if (tag.contains("Feed")) {
            feed.load(registries, tag.getCompound("Feed"));
        }
        // Item route. A fabricator saved before it existed has none of these
        // keys and comes up empty on the default target, with its chemical
        // tanks and batch exactly as they were.
        String saved = tag.getString("Target");
        target = FuelItemRoute.isTarget(saved) ? saved : FuelItemRoute.DEFAULT_TARGET;
        uraniumUnits = Math.max(0, Math.min(1_000_000, tag.getInt("UraniumUnits")));
        tailsPending = Math.max(0, Math.min(1_000_000, tag.getInt("TailsPending")));
        workTicks = Math.max(0, Math.min(1_000_000, tag.getInt("WorkTicks")));
        loadSlot(uraniumIn, tag, "UraniumIn", registries);
        loadSlot(steelIn, tag, "SteelIn", registries);
        loadSlot(tailsOut, tag, "TailsOut", registries);
        itemDirty = true;
    }

    /** Restores one single-slot handler, or empties it when the save has none. */
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
