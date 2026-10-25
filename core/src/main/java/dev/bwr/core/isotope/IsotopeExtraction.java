package dev.bwr.core.isotope;

import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.SpentFuel;

/**
 * What the isotope separator takes out of fission-product waste, in the reprocessing plant's own fixed-point grains
 * ({@link SpentFuel}: a grain is a millionth of a waste item, so a waste item is 1,000,000 grains).
 *
 * <h2>Mass</h2>
 * A waste item is a ninth of an ingot of the heavy metal that was fissioned, and an assembly is 4 ingots, 180 kg
 * ({@link FuelAssembly#REFERENCE_HEAVY_METAL_MASS_KG}), so a waste item is 5 kg and a grain is 5 mg
 * ({@link #GRAMS_PER_GRAIN}). The separator splits the grains it is given into four parts that add up to the input to the
 * grain, exactly as the plant's three products do, so nothing is made or lost to rounding:
 * <ul>
 *   <li><b>Strontium-90</b>, {@link #STRONTIUM_PPM} parts per million of the fissioned mass: the cumulative thermal
 *       yield of U-235 (5.73 % of fissions) times 90 u over the 235 u of a fissioned atom;</li>
 *   <li><b>Caesium-137</b>, {@link #CAESIUM_PPM}: 6.221 % times 137 / 235;</li>
 *   <li><b>Americium-241</b>, {@link #AMERICIUM_PPM}: 1.5 % of the waste item. It is not a fission product: it grew in
 *       from the plutonium-241 that decayed (14.3-year half-life) between discharge and reprocessing, and PUREX sends it to
 *       the high-level waste, so the model books it inside the waste item. A tonne of spent LWR fuel holds about 0.1 kg of
 *       americium at discharge and 0.7 kg a decade later; 1.5 % of an assembly's waste at 45 GWd/t is 0.7 kg per tonne;</li>
 *   <li>the rest, <b>stripped waste</b>, a separate item the separator refuses to take again, so the same mass cannot
 *       be mined twice.</li>
 * </ul>
 * The strontium and caesium shares are as at discharge; the model does not age waste, so a real assembly cooled for
 * ten years holds about 80 % of the strontium and caesium shown here (28.9 and 30.0 year half-lives).
 */
public final class IsotopeExtraction {

    private IsotopeExtraction() {
    }

    /** Parts per million of a waste item's mass. */
    public static final long STRONTIUM_PPM = 21_945L;
    public static final long CAESIUM_PPM = 36_267L;
    public static final long AMERICIUM_PPM = 15_000L;

    /** Grams of mass in a grain: an assembly's 180 kg over its 36,000,000 grains, 5 mg. */
    public static final double GRAMS_PER_GRAIN = FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG * 1000.0 / SpentFuel.ASSEMBLY_GRAINS;

    /** Most grains one split is defined for (keeps the products inside a long: 2.5e13 grains is 25 million waste items). */
    private static final long MAX_GRAINS = 25_000_000_000_000L;

    /** The four parts of some waste, in grains; they add up to the input. */
    public record Split(long strontium, long caesium, long americium, long stripped) {

        public long total() {
            return strontium + caesium + americium + stripped;
        }
    }

    /** Splits {@code wasteGrains} of waste. The stripped waste is what the three isotopes leave, so the four always add up to the input. */
    public static Split split(long wasteGrains) {
        long grains = Math.max(0L, Math.min(MAX_GRAINS, wasteGrains));
        long strontium = grains * STRONTIUM_PPM / 1_000_000L;
        long caesium = grains * CAESIUM_PPM / 1_000_000L;
        long americium = grains * AMERICIUM_PPM / 1_000_000L;
        return new Split(strontium, caesium, americium, grains - strontium - caesium - americium);
    }

    /** Grams in {@code grains}. */
    public static double grams(long grains) {
        return grains * GRAMS_PER_GRAIN;
    }

    /** Grains in {@code grams} of isotope, rounded to the nearest grain. */
    public static long grainsOf(double grams) {
        return Math.round(grams / GRAMS_PER_GRAIN);
    }
}
