package dev.bwr.core.chemistry;

/**
 * Reactor coolant chemistry: ionic impurity (conductivity) and a corrosion-product ("crud") inventory, with the
 * activity the crud picks up in the core. Pure Java, no Minecraft.
 *
 * <p>Everything here is gameplay calibration with real orders of magnitude (BWR coolant is about 0.1 uS/cm, Level 1 action
 * is 1 uS/cm, the 10 uS/cm shutdown limit). One ionic gram is a gram of NaCl equivalent; one kg of coolant carrying
 * {@code g} grams per kg holds {@code 1000 g} ppm and each ppm is {@link #US_PER_CM_PER_PPM} uS/cm over pure water.
 *
 * <h2>What changes it</h2>
 * <ul>
 *   <li>Feedwater impurity: the feedwater flow times {@link #FEEDWATER_IONIC_G_PER_KG} (2 ppb) and feedwater iron times
 *       {@link #FEEDWATER_CRUD_G_PER_KG} (0.2 ppb).</li>
 *   <li>Condenser in-leakage: {@link #CONDENSER_INLEAKAGE_G_PER_S} while the condenser takes its reference steam flow,
 *       scaled by how much it takes. The mod has no hook for a river-cooled condenser's tube leak, so this is a small
 *       constant source that a caller may override ({@link #setCondenserInleakageGPerS}).</li>
 *   <li>Corrosion of the coolant circuit: a small constant release, higher when the water is already conductive.</li>
 *   <li>Cleanup removes both: {@link #removeIons} and {@link #removeCrud} (the RWCU demineraliser and the blowdown).</li>
 * </ul>
 *
 * <h2>What it does</h2>
 * Conductivity above {@link #WEAR_THRESHOLD_US_PER_CM} adds slow structural and fuel-cladding corrosion wear (two counters;
 * the structural one is handed to the boundary stress by the core as it accrues), and the activated crud is a gamma source
 * ({@link #crudGammaWatts}) around the coolant lines. Nothing here acts on the plant: it never trips, isolates or alarms.
 */
public final class CoolantChemistry {
    /** Conductivity of pure water at 25 C, uS/cm. */
    public static final double PURE_WATER_US_PER_CM = 0.06;
    /** uS/cm per ppm of ionic impurity (NaCl equivalent, about 2.1). */
    public static final double US_PER_CM_PER_PPM = 2.0;
    /** A clean plant, uS/cm: what a new core starts at and old saves load as. */
    public static final double CLEAN_US_PER_CM = 0.10;
    public static final double FEEDWATER_IONIC_G_PER_KG = 2.0e-6;
    public static final double FEEDWATER_CRUD_G_PER_KG = 2.0e-7;
    public static final double CONDENSER_INLEAKAGE_G_PER_S = 1.5e-4;
    public static final double CONDENSER_REFERENCE_STEAM_KG_PER_S = 1900.0;
    public static final double CORROSION_IONIC_G_PER_S = 5.0e-5;
    public static final double CORROSION_CRUD_G_PER_S = 1.0e-5;
    /** Gamma watts per becquerel of Co-60 (two gammas, 2.5 MeV per decay). */
    public static final double GAMMA_WATTS_PER_BQ = 4.0e-13;
    /** Activity per gram of crud that has reached equilibrium at rated power, Bq. */
    public static final double SPECIFIC_ACTIVITY_BQ_PER_G = 1.0e12;
    /** Time constant of crud activation in the core flux at power, s. */
    public static final double ACTIVATION_SECONDS = 7200.0;
    /** Co-60 half life, s (5.27 years). */
    public static final double HALF_LIFE_SECONDS = 5.27 * 365.25 * 86400.0;
    /** Conductivity above which corrosion wear accrues, uS/cm (the Level 1 action level). */
    public static final double WEAR_THRESHOLD_US_PER_CM = 1.0;
    /** Structural wear per second at 10 uS/cm (in boundary-stress units, where 1 is a failure-grade stress). */
    public static final double STRUCTURAL_WEAR_PER_S_AT_10 = 1.0e-6;
    public static final double CLADDING_WEAR_THRESHOLD_US_PER_CM = 0.3;
    public static final double CLADDING_WEAR_PER_S_PER_US = 1.0 / 3.6e6;
    /** Water mass below which concentrations are not computed from the inventory, kg. */
    private static final double MIN_LIQUID_KG = 1000.0;

    private double ionicG;
    private double crudG;
    private double activityBq;
    private double claddingWear;
    private double structuralWear;
    private double condenserInleakageGPerS = CONDENSER_INLEAKAGE_G_PER_S;
    private double lastStructuralWearIncrement;
    private double lastConductivity = CLEAN_US_PER_CM;

    /** Snapshot length of {@link #toArray}. */
    public static final int SNAPSHOT_LENGTH = 5;

    public CoolantChemistry() {
        reset(300_000.0);
    }

    /** A clean coolant of {@code liquidKg}: conductivity {@link #CLEAN_US_PER_CM}, no crud, no wear. */
    public void reset(double liquidKg) {
        ionicG = ionicGramsFor(CLEAN_US_PER_CM, liquidKg);
        crudG = 0;
        activityBq = 0;
        claddingWear = 0;
        structuralWear = 0;
        lastStructuralWearIncrement = 0;
        lastConductivity = CLEAN_US_PER_CM;
    }

    public static double ionicGramsFor(double microSiemensPerCm, double liquidKg) {
        double ppm = Math.max(0, microSiemensPerCm - PURE_WATER_US_PER_CM) / US_PER_CM_PER_PPM;
        return ppm * Math.max(liquidKg, MIN_LIQUID_KG) / 1000.0;
    }

    /** The water the chemistry is in grew or shrank by {@code ratio} with its concentrations unchanged. */
    public void scale(double ratio) {
        if (!(ratio > 0) || !Double.isFinite(ratio)) return;
        ionicG *= ratio;
        crudG *= ratio;
        activityBq *= ratio;
    }

    public void setCondenserInleakageGPerS(double g) {
        condenserInleakageGPerS = Double.isFinite(g) ? Math.max(0, g) : CONDENSER_INLEAKAGE_G_PER_S;
    }

    public double getCondenserInleakageGPerS() { return condenserInleakageGPerS; }

    /** Ionic impurity per kg of coolant, g/kg. */
    public double ionicConcentrationGPerKg(double liquidKg) {
        return ionicG / Math.max(liquidKg, MIN_LIQUID_KG);
    }

    /** Crud per kg of coolant, g/kg. */
    public double crudConcentrationGPerKg(double liquidKg) {
        return crudG / Math.max(liquidKg, MIN_LIQUID_KG);
    }

    /** Conductivity, uS/cm. */
    public double conductivityUsPerCm(double liquidKg) {
        return PURE_WATER_US_PER_CM + US_PER_CM_PER_PPM * ionicConcentrationGPerKg(liquidKg) * 1000.0;
    }

    public double getIonicG() { return ionicG; }
    public double getCrudG() { return crudG; }
    public double getActivityBq() { return activityBq; }
    public double getCladdingWear() { return claddingWear; }
    public double getStructuralWear() { return structuralWear; }
    /** Activity per gram of the circulating crud, Bq/g. */
    public double specificActivityBqPerG() { return crudG > 1e-12 ? activityBq / crudG : 0; }
    /** Structural wear added by the last step, in the boundary stress's units. */
    public double lastStructuralWearIncrement() { return lastStructuralWearIncrement; }
    public double lastConductivityUsPerCm() { return lastConductivity; }

    /** Gamma power of the circulating activated crud, watts. */
    public double crudGammaWatts() { return activityBq * GAMMA_WATTS_PER_BQ; }

    /** Removes ionic impurity (cleanup or blowdown); returns what was actually removed, g. */
    public double removeIons(double grams) {
        double n = Double.isFinite(grams) ? Math.max(0, Math.min(ionicG, grams)) : 0;
        ionicG -= n;
        return n;
    }

    public void addIons(double grams) {
        if (Double.isFinite(grams) && grams > 0) ionicG += grams;
    }

    /** Removes crud and its share of the activity; returns {grams, becquerels} removed. */
    public double[] removeCrud(double grams) {
        double n = Double.isFinite(grams) ? Math.max(0, Math.min(crudG, grams)) : 0;
        double share = crudG > 0 ? n / crudG : 0;
        double bq = activityBq * share;
        crudG -= n;
        activityBq -= bq;
        return new double[]{n, bq};
    }

    public void addCrud(double grams) {
        if (Double.isFinite(grams) && grams > 0) crudG += grams;
    }

    /**
     * Advances the chemistry.
     *
     * @param liquidKg             water in the vessel
     * @param feedwaterKgPerS      feedwater flow delivered to the vessel
     * @param condenserSteamKgPerS steam going to the condenser (turbine plus bypass)
     * @param powerFraction        fission power as a fraction of rated (activates the crud)
     * @param dt                   seconds
     */
    public void step(double liquidKg, double feedwaterKgPerS, double condenserSteamKgPerS, double powerFraction, double dt) {
        if (!(dt > 0) || !Double.isFinite(dt)) return;
        double fw = Math.max(0, feedwaterKgPerS);
        double kappa = conductivityUsPerCm(liquidKg);
        double condenser = Math.min(1.0, Math.max(0, condenserSteamKgPerS) / CONDENSER_REFERENCE_STEAM_KG_PER_S);
        ionicG += (fw * FEEDWATER_IONIC_G_PER_KG + condenser * condenserInleakageGPerS + CORROSION_IONIC_G_PER_S) * dt;
        double corrosion = CORROSION_CRUD_G_PER_S * (1.0 + Math.max(0, kappa - WEAR_THRESHOLD_US_PER_CM));
        crudG += (fw * FEEDWATER_CRUD_G_PER_KG + corrosion) * dt;
        // Crud picks up activity in the flux; the activity it has stays (decaying only with the Co-60 half life).
        double p = Math.max(0, Math.min(1.5, powerFraction));
        double equilibrium = crudG * SPECIFIC_ACTIVITY_BQ_PER_G * p;
        if (activityBq < equilibrium) activityBq += (equilibrium - activityBq) / ACTIVATION_SECONDS * dt;
        activityBq = Math.max(0, activityBq - activityBq * Math.log(2.0) / HALF_LIFE_SECONDS * dt);
        kappa = conductivityUsPerCm(liquidKg);
        lastConductivity = kappa;
        lastStructuralWearIncrement = 0;
        if (kappa > WEAR_THRESHOLD_US_PER_CM) {
            lastStructuralWearIncrement = (kappa - WEAR_THRESHOLD_US_PER_CM) / 9.0 * STRUCTURAL_WEAR_PER_S_AT_10 * dt;
            structuralWear += lastStructuralWearIncrement;
        }
        if (kappa > CLADDING_WEAR_THRESHOLD_US_PER_CM) {
            claddingWear += (kappa - CLADDING_WEAR_THRESHOLD_US_PER_CM) * CLADDING_WEAR_PER_S_PER_US * dt;
        }
    }

    /** Whether anything has moved off the clean default (a save writes the chemistry only when it has). */
    public boolean differsFromClean(double liquidKg) {
        return crudG > 0 || activityBq > 0 || claddingWear > 0 || structuralWear > 0
                || Math.abs(conductivityUsPerCm(liquidKg) - CLEAN_US_PER_CM) > 1e-6
                || condenserInleakageGPerS != CONDENSER_INLEAKAGE_G_PER_S;
    }

    public double[] toArray() {
        return new double[]{ionicG, crudG, activityBq, claddingWear, structuralWear};
    }

    public void fromArray(double[] a) {
        if (a == null || a.length < SNAPSHOT_LENGTH) return;
        for (int i = 0; i < SNAPSHOT_LENGTH; i++) if (!Double.isFinite(a[i]) || a[i] < 0) return;
        ionicG = a[0];
        crudG = a[1];
        activityBq = a[2];
        claddingWear = a[3];
        structuralWear = a[4];
    }
}
