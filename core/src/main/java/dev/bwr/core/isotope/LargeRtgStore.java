package dev.bwr.core.isotope;

import dev.bwr.core.radiation.DoseModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a Large RTG holds and makes: the arithmetic behind the 2 x 2 x 3 multiblock, with no Minecraft in it. The same
 * physics as the small RTG ({@link RadioisotopePower}) over a bulk store instead of six wells.
 *
 * <h2>The store</h2>
 * Up to {@link #CAPACITY_GRAMS} (1,000 kg) of isotope material in total, mixed isotopes allowed. It is tracked as a
 * count per item kind (an ingot, a storage block, a capsule), not as 4,000 slots, and the mass is always
 * {@code sum of count x grams per item}, computed fresh, so nothing drifts. An item that does not fit whole is refused;
 * the rest of a stack goes in.
 *
 * <h2>Decay</h2>
 * Every item of one kind has the same half-life, so however many batches went in at different times, the kind's
 * thermal power is one exponential: {@code count x grams x specific power x 2^(-age / half-life)} with a single
 * <i>effective age</i> that is kept exact when a batch is added ({@link #mergedAgeSeconds}). That is why a bulk store needs
 * one clock per kind and not one per item.
 *
 * <h2>Power</h2>
 * Thermal watts are the sum over the kinds; electric watts are thermal times {@link RadioisotopePower#THERMOCOUPLE_EFFICIENCY};
 * FE per tick is electric over the mod's one conversion times the server's output multiplier. <b>Heat-only</b> mode stops
 * the electricity and nothing else: the unit still makes its full thermal power.
 *
 * <h2>Shielding</h2>
 * The housing is {@link #LEAD_EQUIVALENT_CM} of lead-equivalent wall. What gets through for an isotope is
 * {@code 2^(-thickness / half-value layer)}, with the half-value layer in lead from the fuel list (caesium-137 0.65 cm,
 * cobalt-60 1.2 cm). The small RTG's flat 1e-4 does not apply here.
 */
public final class LargeRtgStore {

    /** What a Large RTG can hold, grams: 1,000 kg. */
    public static final double CAPACITY_GRAMS = 1_000_000.0;
    /** Lead-equivalent wall of the housing, cm (the footprint is 2 m square; the core cavity is what is left). */
    public static final double LEAD_EQUIVALENT_CM = 40.0;
    /** Rounding slack when asking whether another item still fits, grams (a nanogram of arithmetic noise, not a gram of cheating). */
    private static final double SLACK_GRAMS = 1.0e-6;

    /**
     * One item kind's numbers, from the RTG fuel list.
     *
     * @param label the isotope, for the screen
     */
    public record Fuel(String label, double specificPowerWPerG, double halfLifeYears, double gramsPerItem, double gammaWPerG,
                       double capsuleTransmission, double leadHvlCm) {
    }

    /** One item kind in the store. */
    public static final class Bucket {
        private final String key;
        private final Fuel fuel;
        private long count;
        /** The effective start of the kind's decay clock, seconds of world time; may be negative. */
        private double epochSeconds;

        private Bucket(String key, Fuel fuel, long count, double epochSeconds) {
            this.key = key;
            this.fuel = fuel;
            this.count = count;
            this.epochSeconds = epochSeconds;
        }

        public String key() {
            return key;
        }

        public Fuel fuel() {
            return fuel;
        }

        public long count() {
            return count;
        }

        public double epochSeconds() {
            return epochSeconds;
        }

        public double grams() {
            return count * fuel.gramsPerItem();
        }

        /** Fraction of the original activity left at {@code nowSeconds}. */
        public double decay(double nowSeconds) {
            return RadioisotopePower.decayFactor(fuel.halfLifeYears(), nowSeconds - epochSeconds);
        }
    }

    /** What the store makes at one moment. */
    public record Output(double thermalWatts, double electricWatts, double fePerTick, double bareGammaWatts, double outsideGammaWatts,
                         double doseAtOneMetreMsvPerH, double bareDoseAtOneMetreMsvPerH, double shortestHalfLifeYears, double massGrams) {
        public static final Output NONE = new Output(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    /** Transmission of the housing for an isotope with this lead half-value layer, 0..1. */
    public static double shieldTransmission(double leadHvlCm) {
        if (!(leadHvlCm > 0.0)) {
            return 1.0;
        }
        return Math.pow(0.5, LEAD_EQUIVALENT_CM / leadHvlCm);
    }

    /**
     * The effective age that keeps the thermal power exact when {@code added} fresh items join {@code count} that
     * have decayed to {@code factorNow} of their power: the new kind's average remaining fraction, as an age.
     */
    public static double mergedAgeSeconds(double halfLifeYears, long count, double factorNow, long added) {
        if (!(halfLifeYears > 0.0) || count + added <= 0) {
            return 0.0;
        }
        double average = (count * factorNow + added) / (double) (count + added);
        // average = 2^(-age / half-life)  =>  age = -half-life x log2(average)
        return -halfLifeYears * RadioisotopePower.SECONDS_PER_YEAR * (Math.log(average) / Math.log(2.0));
    }

    /** Total mass held, grams. */
    public double massGrams() {
        double g = 0.0;
        for (Bucket b : buckets.values()) {
            g += b.grams();
        }
        return g;
    }

    /** Whole items of {@code gramsPerItem} that still fit. */
    public long room(double gramsPerItem) {
        if (!(gramsPerItem > 0.0)) {
            return 0L;
        }
        double free = CAPACITY_GRAMS - massGrams() + SLACK_GRAMS;
        return free <= 0.0 ? 0L : (long) Math.floor(free / gramsPerItem);
    }

    /** Puts up to {@code wanted} items of kind {@code key} in; returns how many went in (the rest is refused). */
    public long insert(String key, Fuel fuel, long wanted, double nowSeconds) {
        long n = Math.min(Math.max(0L, wanted), room(fuel.gramsPerItem()));
        if (n <= 0L) {
            return 0L;
        }
        Bucket b = buckets.get(key);
        if (b == null) {
            buckets.put(key, new Bucket(key, fuel, n, nowSeconds));
        } else {
            double age = mergedAgeSeconds(fuel.halfLifeYears(), b.count, b.decay(nowSeconds), n);
            b.count += n;
            b.epochSeconds = nowSeconds - age;
        }
        return n;
    }

    /** Restores a kind from a save (no capacity check against hand-edited numbers beyond the clamp the caller makes). */
    public void restore(String key, Fuel fuel, long count, double epochSeconds) {
        if (count > 0L) {
            buckets.put(key, new Bucket(key, fuel, count, epochSeconds));
        }
    }

    /** Takes everything out, returning the kinds with their counts (the caller turns them back into items). */
    public List<Bucket> drain() {
        List<Bucket> all = new ArrayList<>(buckets.values());
        buckets.clear();
        return all;
    }

    public List<Bucket> buckets() {
        return new ArrayList<>(buckets.values());
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public long itemCount() {
        long n = 0L;
        for (Bucket b : buckets.values()) {
            n += b.count;
        }
        return n;
    }

    /**
     * Power, heat and dose at {@code nowSeconds}.
     *
     * @param heatOnly   true stops the electricity; the thermal power is unchanged
     * @param multiplier the server's RTG output multiplier (1 by default), applied to the electricity only
     */
    public Output output(double nowSeconds, boolean heatOnly, double multiplier) {
        if (buckets.isEmpty()) {
            return Output.NONE;
        }
        double thermal = 0.0;
        double bare = 0.0;
        double outside = 0.0;
        double halfLife = 0.0;
        for (Bucket b : buckets.values()) {
            Fuel f = b.fuel;
            double age = nowSeconds - b.epochSeconds;
            double decay = RadioisotopePower.decayFactor(f.halfLifeYears(), age);
            thermal += b.count * RadioisotopePower.thermalWatts(f.gramsPerItem(), f.specificPowerWPerG(), f.halfLifeYears(), age);
            double gamma = b.count * RadioisotopePower.gammaWatts(f.gramsPerItem(), f.gammaWPerG(), f.capsuleTransmission()) * decay;
            bare += gamma;
            outside += gamma * shieldTransmission(f.leadHvlCm());
            if (f.halfLifeYears() > 0.0 && (halfLife == 0.0 || f.halfLifeYears() < halfLife)) {
                halfLife = f.halfLifeYears();
            }
        }
        double electric = heatOnly ? 0.0 : RadioisotopePower.electricWatts(thermal, RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        double fe = RadioisotopePower.fePerTick(electric, multiplier);
        return new Output(thermal, electric, fe, bare, outside, DoseModel.pointSourceMsvPerH(outside, 1.0, 1.0),
                DoseModel.pointSourceMsvPerH(bare, 1.0, 1.0), halfLife, massGrams());
    }
}
