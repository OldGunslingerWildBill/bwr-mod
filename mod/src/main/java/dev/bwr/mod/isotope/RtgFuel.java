package dev.bwr.mod.isotope;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One line of the RTG fuel list: what an item is as a radioisotope heat source. The list is the item data map
 * {@code bwr:rtg_fuel} ({@code data/bwr/data_maps/item/rtg_fuel.json}); an item is RTG fuel exactly when it has an entry,
 * so a datapack or another mod adds a fuel with one line and no code. Every number is per the isotope, in SI.
 *
 * @param specificPowerWPerG thermal power of one gram of the isotope in equilibrium with the daughters that carry its heat, W/g
 * @param halfLifeYears      half-life, years; 0 or less never decays
 * @param gramsPerItem       grams of the isotope in one item
 * @param gammaWPerG         photon power one gram sends out, W/g: what the plant's dose model needs (0, the default, is no photons)
 * @param capsuleTransmission what the item's own wall lets through of those photons, 0..1 (default 1)
 * @param isotope            label for screens, for example {@code Sr-90}; empty shows nothing
 */
public record RtgFuel(double specificPowerWPerG, double halfLifeYears, double gramsPerItem, double gammaWPerG,
                      double capsuleTransmission, String isotope) {

    public static final Codec<RtgFuel> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(0.0, 1.0e6).fieldOf("specific_power_w_per_g").forGetter(RtgFuel::specificPowerWPerG),
            Codec.doubleRange(0.0, 1.0e12).optionalFieldOf("half_life_years", 0.0).forGetter(RtgFuel::halfLifeYears),
            Codec.doubleRange(0.0, 1.0e9).fieldOf("grams_per_item").forGetter(RtgFuel::gramsPerItem),
            Codec.doubleRange(0.0, 1.0e6).optionalFieldOf("gamma_w_per_g", 0.0).forGetter(RtgFuel::gammaWPerG),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("capsule_transmission", 1.0).forGetter(RtgFuel::capsuleTransmission),
            Codec.STRING.optionalFieldOf("isotope", "").forGetter(RtgFuel::isotope)
    ).apply(i, RtgFuel::new));

    /** Fresh thermal power of one item, W. */
    public double thermalWattsPerItem() {
        return gramsPerItem * specificPowerWPerG;
    }
}
