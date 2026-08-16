package dev.bwr.mod.fuel;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * Where the fabricator gets heavy metal from.
 *
 * <p>This interface exists to keep Mekanism out of
 * {@link FuelFabricatorBlockEntity}. Mekanism is a soft dependency, so nothing
 * that is loaded unconditionally may name a Mekanism type — and a block entity
 * that has been placed in a world is very much loaded unconditionally, whether
 * or not the player still has Mekanism installed. The block entity therefore
 * talks to a {@code FuelFeed}, which on a Mekanism install is backed by chemical
 * tanks and otherwise is {@link #NONE}.
 *
 * <p>Everything crossing this boundary is in kilograms of heavy metal, not
 * millibuckets of anything, so the fabricator's arithmetic never depends on
 * another mod's unit conventions.
 */
public interface FuelFeed {

    /**
     * What a draw actually yielded.
     *
     * @param heavyMetalKg total heavy metal removed from the feed, kg
     * @param fissileKg    fissile content of that heavy metal, kg
     * @param plutoniumKg  how much of that heavy metal was plutonium, kg
     */
    record Draw(double heavyMetalKg, double fissileKg, double plutoniumKg) {

        public static final Draw NOTHING = new Draw(0.0, 0.0, 0.0);

        public boolean isEmpty() {
            return !(heavyMetalKg > 0.0);
        }

        public Draw plus(Draw other) {
            return new Draw(heavyMetalKg + other.heavyMetalKg,
                    fissileKg + other.fissileKg,
                    plutoniumKg + other.plutoniumKg);
        }
    }

    /**
     * Removes up to {@code maxHeavyMetalKg} of heavy metal and reports what came
     * out. Implementations blend their inputs however their plumbing dictates;
     * the resulting enrichment is whatever the player's feed rates produced.
     */
    Draw draw(double maxHeavyMetalKg);

    /** Human-readable contents, for the right-click readout. */
    List<String> statusLines();

    /** Called when the block entity is reconstructed, so the feed can mark it dirty. */
    void setChangeListener(Runnable onChanged);

    CompoundTag save(HolderLookup.Provider registries);

    void load(HolderLookup.Provider registries, CompoundTag tag);

    /**
     * The feed on an install with no Mekanism. Accepts nothing, yields nothing,
     * and says so — the fabricator block still exists, still loads, and still
     * keeps whatever was in its output slot, which is what a soft dependency has
     * to mean for a block already placed in someone's world.
     */
    FuelFeed NONE = new FuelFeed() {
        @Override
        public Draw draw(double maxHeavyMetalKg) {
            return Draw.NOTHING;
        }

        @Override
        public List<String> statusLines() {
            return List.of("No chemical feed: Mekanism is not installed.");
        }

        @Override
        public void setChangeListener(Runnable onChanged) {
        }

        @Override
        public CompoundTag save(HolderLookup.Provider registries) {
            return new CompoundTag();
        }

        @Override
        public void load(HolderLookup.Provider registries, CompoundTag tag) {
        }
    };
}
