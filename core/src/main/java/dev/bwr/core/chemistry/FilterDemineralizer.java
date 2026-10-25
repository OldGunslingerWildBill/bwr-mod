package dev.bwr.core.chemistry;

/**
 * The RWCU stainless steel nanofilter (registry id {@code rwcu_filter_demineralizer}, the name of the unit it replaced): a
 * replaceable element that is consumed. Pure Java.
 *
 * <p><b>What is real and what is not.</b> Sintered 316L stainless-steel media are real RWCU filter elements, rated for hot
 * water (their makers quote 1000 F), but they catch particles: crud. Dissolved ions need an ion exchanger, and the resin ones
 * die above 140 F; a real inorganic exchanger that works at 285 C exists only for cobalt (titanium oxide on sponge titanium).
 * This element does <em>both</em>, crud and dissolved ions, at full reactor temperature, because the owner wants one simple
 * cleanup unit with no temperature trap. There is no "nanometal particle" BWR technology: the "nano" is a name, and
 * RWCU.md says so.
 *
 * <p>The element exhausts in proportion to what it removes: it holds {@link #ION_CAPACITY_G} grams of ionic impurity and
 * {@link #CRUD_CAPACITY_G} grams of crud. It removes {@link #EFFICIENCY} of the ions passing until the last fifth of its ionic
 * capacity, then fades to nothing; an exhausted element removes no ions. Crud is filtered (efficiency {@link #CRUD_EFFICIENCY})
 * until the element is full of it. Temperature plays no part: it works at any water temperature.
 *
 * <p>The cake of crud on the fibres raises the pressure drop: {@link #DP_CLEAN_PSI} psid at the pump's rated 60 kg/s when new and
 * {@link #DP_SPENT_PSI} when full of crud, quadratic in flow. The pump meets it on its own curve
 * ({@link RwcuPumpDesign#loopFlowKgPerS}), so a loaded element lowers the whole loop's flow.
 *
 * <p>What the element has caught is radioactive: {@link #activityBq()} is the activity of the crud in it, which the block
 * entity makes a gamma source and the replaced element carries away as a spent element.
 */
public final class FilterDemineralizer {
    public static final double ION_CAPACITY_G = 100.0;
    public static final double CRUD_CAPACITY_G = 200.0;
    public static final double EFFICIENCY = 0.95;
    public static final double CRUD_EFFICIENCY = 0.90;
    /** The flow the pressure drops are stated at, kg/s (the RWCU pump's rated flow, 950 gpm). */
    public static final double RATED_FLOW_KG_PER_S = 60.0;
    public static final double DP_CLEAN_PSI = 5.0;
    public static final double DP_SPENT_PSI = 100.0;
    /** Fraction of the ionic capacity over which the bed's efficiency fades out as it exhausts. */
    private static final double FADE = 0.20;
    /** Ionic capacity left below which the bed is spent and removes nothing, g. */
    public static final double SPENT_BELOW_G = 0.5;

    private boolean bedPresent = true;
    private double ionLoadG;
    private double crudLoadG;
    private double activityBq;
    private double lastEfficiency;

    /** What one step did, g and Bq. */
    public record Result(double removedIonG, double removedCrudG, double removedBq) {}

    public boolean bedPresent() { return bedPresent; }
    public double ionLoadG() { return ionLoadG; }
    public double crudLoadG() { return crudLoadG; }
    public double activityBq() { return activityBq; }
    public double lastEfficiency() { return lastEfficiency; }

    /** Share of the element's crud capacity that is full, 0..1: what the pressure drop follows. */
    public double crudLoading() { return Math.min(1.0, crudLoadG / CRUD_CAPACITY_G); }

    /** Pressure drop across the element at the rated flow, psid; zero with no element (an open spool). */
    public double pressureDropAtRatedPsi() {
        return bedPresent ? DP_CLEAN_PSI + (DP_SPENT_PSI - DP_CLEAN_PSI) * crudLoading() : 0;
    }

    /** Pressure drop across the element at {@code flowKgPerS}, psid (quadratic in flow). */
    public double pressureDropPsi(double flowKgPerS) {
        double f = Math.max(0, flowKgPerS) / RATED_FLOW_KG_PER_S;
        return pressureDropAtRatedPsi() * f * f;
    }

    /** Ionic capacity left in this element, g. */
    public double ionCapacityLeftG() {
        double left = bedPresent ? Math.max(0, ION_CAPACITY_G - ionLoadG) : 0;
        return left < SPENT_BELOW_G ? 0 : left;
    }

    /** Share of the element's life used, 0..1: its loaded ionic or crud capacity, whichever is more. */
    public double exhaustion() {
        if (!bedPresent) return 1.0;
        if (ionCapacityLeftG() <= 0) return 1.0;
        return Math.min(1.0, Math.max(ionLoadG / ION_CAPACITY_G, crudLoadG / CRUD_CAPACITY_G));
    }

    /** Gamma power of what the element holds, watts. */
    public double gammaWatts() { return activityBq * CoolantChemistry.GAMMA_WATTS_PER_BQ; }

    /**
     * Passes water through the element for {@code dt} seconds.
     *
     * @param flowKgPerS water through it
     * @param inletC     its temperature, for the readout only: the element works at any temperature
     * @param ionGPerKg  ionic impurity in it
     * @param crudGPerKg crud in it
     * @param bqPerG     activity of that crud
     */
    public Result process(double flowKgPerS, double inletC, double ionGPerKg, double crudGPerKg, double bqPerG, double dt) {
        lastEfficiency = 0;
        if (!bedPresent || !(dt > 0)) return new Result(0, 0, 0);
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
        return new Result(ions, crud, bq);
    }

    /** What a replaced element carries away: {ionic grams, crud grams, becquerels}. */
    public double[] spentBed() { return new double[]{ionLoadG, crudLoadG, activityBq}; }

    /** Puts a new element in (the old one is discarded by the caller as a spent element). */
    public void replaceBed() {
        bedPresent = true;
        ionLoadG = 0;
        crudLoadG = 0;
        activityBq = 0;
    }

    /** Takes the element out (an empty housing is an open spool and removes nothing). */
    public void removeBed() {
        bedPresent = false;
        ionLoadG = 0;
        crudLoadG = 0;
        activityBq = 0;
    }

    /**
     * {present, ions, crud, activity, 0}. The last slot was the resin's heat damage; it is kept so a saved resin bed (five
     * numbers) still loads, and it is always zero now.
     */
    public double[] toArray() {
        return new double[]{bedPresent ? 1 : 0, ionLoadG, crudLoadG, activityBq, 0};
    }

    public void fromArray(double[] a) {
        if (a == null || a.length < 5) return;
        bedPresent = a[0] > 0.5;
        ionLoadG = Math.max(0, a[1]);
        crudLoadG = Math.max(0, a[2]);
        activityBq = Math.max(0, a[3]);
    }
}
