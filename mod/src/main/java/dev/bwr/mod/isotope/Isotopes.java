package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.IsotopeExtraction;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.world.item.Item;

/** The constants of the isotope capsules and of the separator that fills them. */
public final class Isotopes {

    private Isotopes() {
    }

    /** Grams of isotope in one capsule: one Beta-M's worth (0.255 kg of strontium-90). The fuel list says the same. */
    public static final double CAPSULE_GRAMS = 250.0;

    /** Grains in a capsule (a grain is 5 mg: see {@link IsotopeExtraction}). */
    public static final long CAPSULE_GRAINS = IsotopeExtraction.grainsOf(CAPSULE_GRAMS);

    /** Grains in one waste item and one stripped-waste item. */
    public static final long GRAINS_PER_WASTE_ITEM = dev.bwr.core.fuel.SpentFuel.GRAINS_PER_NINTH;

    /** Ticks of work for one waste item, and FE per tick: 10,000 FE an item. */
    public static final int WORK_TICKS = 100;
    public static final int ENERGY_PER_TICK = 100;

    public static Item strontium() {
        return BwrItems.STRONTIUM_90_CAPSULE.get();
    }

    public static Item caesium() {
        return BwrItems.CAESIUM_137_CAPSULE.get();
    }

    public static Item americium() {
        return BwrItems.AMERICIUM_241_CAPSULE.get();
    }
}
