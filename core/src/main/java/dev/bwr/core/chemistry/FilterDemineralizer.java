package dev.bwr.core.chemistry;

/**
 * One RWCU filter-demineraliser: a resin bed that is consumed. Pure Java.
 *
 * <p>The bed exhausts in proportion to the impurity it removes: it holds {@link #ION_CAPACITY_G} grams of ionic impurity
 * and {@link #CRUD_CAPACITY_G} grams of crud. It removes {@link #EFFICIENCY} of what passes until the last fifth of its
 * ionic capacity, then fades to nothing; an exhausted bed removes no ions. Crud is filtered (efficiency
 * {@link #CRUD_EFFICIENCY}) until the bed is full of it.
 *
 * <p>Resin does not survive heat: above {@link #TEMPERATURE_LIMIT_C} (140 F) it decomposes at
 * {@code (dT / 40 K)^2 / 600 s} of the bed per second, permanently losing that share of its capacity and releasing the share
 * of the captured impurity it can no longer hold back into the water. Nothing isolates it: that is the consequence of
 * losing the non-regenerative exchanger's cooling.
 *
 * <p>What the bed has caught is radioactive: {@link #activityBq()} is the activity of the crud in it, which the
 * block entity makes a gamma source and the replaced bed carries away as spent resin.
 */
public final class FilterDemineralizer {
    public static final double ION_CAPACITY_G = 100.0;
    public static final double CRUD_CAPACITY_G = 200.0;
    public static final double EFFICIENCY = 0.95;
    public static final double CRUD_EFFICIENCY = 0.90;
    /** The resin limit, 140 F. */
    public static final double TEMPERATURE_LIMIT_C = 60.0;
    public static final double DAMAGE_RATE_REFERENCE_K = 40.0;
    public static final double DAMAGE_SECONDS_AT_REFERENCE = 600.0;
    /** Fraction of the ionic capacity over which the bed's efficiency fades out as it exhausts. */
    private static final double FADE = 0.20;
    /** Ionic capacity left below which the bed is spent and removes nothing, g. */
    public static final double SPENT_BELOW_G = 0.5;

    private boolean bedPresent = true;
    private double ionLoadG;
    private double crudLoadG;
    private double activityBq;
    private double damage;
    private double lastEfficiency;
    private double lastRemovedIonG;
    private double lastReleasedIonG;
    private boolean hot;

    /** What one step did, g and Bq. */
    public record Result(double removedIonG, double removedCrudG, double removedBq, double releasedIonG) {}

    public boolean bedPresent() { return bedPresent; }
    public double ionLoadG() { return ionLoadG; }
    public double crudLoadG() { return crudLoadG; }
    public double activityBq() { return activityBq; }
    public double damage() { return damage; }
    public double lastEfficiency() { return lastEfficiency; }
    public boolean overTemperature() { return hot; }

    /** Ionic capacity left in this bed, g. */
    public double ionCapacityLeftG() {
        double left = bedPresent ? Math.max(0, ION_CAPACITY_G * (1.0 - damage) - ionLoadG) : 0;
        return left < SPENT_BELOW_G ? 0 : left;
    }

    /** Share of the bed's life used, 0..1: its loaded ionic and crud capacity and its heat damage, whichever is most. */
    public double exhaustion() {
        if (!bedPresent) return 1.0;
        if (ionCapacityLeftG() <= 0) return 1.0;
        return Math.min(1.0, Math.max(damage, Math.max(ionLoadG / ION_CAPACITY_G, crudLoadG / CRUD_CAPACITY_G)));
    }

    /** Gamma power of what the bed holds, watts. */
    public double gammaWatts() { return activityBq * CoolantChemistry.GAMMA_WATTS_PER_BQ; }

    /**
     * Passes water through the bed for {@code dt} seconds.
     *
     * @param flowKgPerS water through the bed
     * @param inletC     its temperature
     * @param ionGPerKg  ionic impurity in it
     * @param crudGPerKg crud in it
     * @param bqPerG     activity of that crud
     */
    public Result process(double flowKgPerS, double inletC, double ionGPerKg, double crudGPerKg, double bqPerG, double dt) {
        lastEfficiency = 0;
        lastRemovedIonG = 0;
        lastReleasedIonG = 0;
        hot = false;
        if (!bedPresent || !(dt > 0)) return new Result(0, 0, 0, 0);
        double released = 0;
        if (inletC > TEMPERATURE_LIMIT_C) {
            hot = true;
            double over = (inletC - TEMPERATURE_LIMIT_C) / DAMAGE_RATE_REFERENCE_K;
            double increment = Math.min(1.0 - damage, over * over / DAMAGE_SECONDS_AT_REFERENCE * dt);
            if (increment > 0) {
                double before = 1.0 - damage;
                double share = increment / before;
                released = ionLoadG * share;
                ionLoadG -= released;
                damage += increment;
                // What the damaged resin cannot hold any more is released too.
                double excess = ionLoadG - ION_CAPACITY_G * (1.0 - damage);
                if (excess > 0) {
                    released += excess;
                    ionLoadG -= excess;
                }
            }
        }
        lastReleasedIonG = released;
        double kg = Math.max(0, flowKgPerS) * dt;
        double leftFraction = ionCapacityLeftG() / ION_CAPACITY_G;
        double efficiency = EFFICIENCY * Math.min(1.0, leftFraction / FADE);
        lastEfficiency = efficiency;
        double ions = Math.min(ionCapacityLeftG(), kg * Math.max(0, ionGPerKg) * efficiency);
        double crud = crudLoadG < CRUD_CAPACITY_G
                ? Math.min(CRUD_CAPACITY_G - crudLoadG, kg * Math.max(0, crudGPerKg) * CRUD_EFFICIENCY) : 0;
        double bq = crud * Math.max(0, bqPerG);
        ionLoadG += ions;
        crudLoadG += crud;
        activityBq += bq;
        lastRemovedIonG = ions;
        return new Result(ions, crud, bq, released);
    }

    /** What a replaced bed carries away: {ionic grams, crud grams, becquerels, damage}. */
    public double[] spentBed() { return new double[]{ionLoadG, crudLoadG, activityBq, damage}; }

    /** Puts a fresh bed in (the old one is discarded by the caller as spent resin). */
    public void replaceBed() {
        bedPresent = true;
        ionLoadG = 0;
        crudLoadG = 0;
        activityBq = 0;
        damage = 0;
    }

    /** Takes the bed out (an empty vessel removes nothing). */
    public void removeBed() {
        bedPresent = false;
        ionLoadG = 0;
        crudLoadG = 0;
        activityBq = 0;
        damage = 0;
    }

    public double[] toArray() {
        return new double[]{bedPresent ? 1 : 0, ionLoadG, crudLoadG, activityBq, damage};
    }

    public void fromArray(double[] a) {
        if (a == null || a.length < 5) return;
        bedPresent = a[0] > 0.5;
        ionLoadG = Math.max(0, a[1]);
        crudLoadG = Math.max(0, a[2]);
        activityBq = Math.max(0, a[3]);
        damage = Math.max(0, Math.min(1, a[4]));
    }
}
