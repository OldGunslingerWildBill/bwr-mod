package dev.bwr.mod.fuel;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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

    private final FuelFeed feed = FuelFeeds.create();

    private final ItemStackHandler output = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
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

    public FuelFabricatorBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.FUEL_FABRICATOR.get(), pos, state);
        feed.setChangeListener(this::setChanged);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FuelFabricatorBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (!output.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            // Bundle waiting to be collected. Backing up rather than voiding is
            // the only sane behaviour for something that cost this much feed.
            return;
        }
        if (energy.getEnergyStored() < ENERGY_PER_TICK) {
            return;
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
        List<String> out = new ArrayList<>();
        out.addAll(feed.statusLines());
        out.add(String.format(Locale.ROOT, "Batch %.1f / %.0f kg HM at %.2f%% fissile",
                batchHeavyMetalKg, FuelFabrication.batchHeavyMetalKg(),
                batchEnrichmentWeightFraction() * 100.0));
        if (batchHeavyMetalKg > 0.0) {
            out.add("Would fabricate: " + FuelFabrication.fabricate(
                    batchHeavyMetalKg, batchFissileKg, batchPlutoniumKg).fuelTypeName());
        }
        out.add(String.format(Locale.ROOT, "%,d / %,d FE",
                energy.getEnergyStored(), energy.getMaxEnergyStored()));
        ItemStack held = output.getStackInSlot(OUTPUT_SLOT);
        if (!held.isEmpty()) {
            out.add("Output slot holds a finished bundle; the machine is stopped until it is taken.");
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
    }
}
