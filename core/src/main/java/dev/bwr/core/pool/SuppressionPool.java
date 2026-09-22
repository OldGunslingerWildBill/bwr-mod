package dev.bwr.core.pool;

import dev.bwr.core.thermal.Saturation;

/**
 * The suppression pool — {@code SPEC.md} section 12.
 *
 * <p>Not a decorative water block. This is a real heat sink with a finite heat
 * capacity, and running out of that capacity is one of the genuine constraints
 * on how long a plant can sit in an extended transient.
 *
 * <h2>The closed loop that makes it interesting</h2>
 * Steam relieved through the SRVs discharges under water and condenses,
 * dumping its latent heat into the pool. If ECCS is also taking suction from
 * the pool, that same water is injected into the vessel, boils, and comes back
 * through the SRVs. The loop is closed: unlimited water, but it heats up every
 * time round. Tank suction is cooler and finite; pool suction is warm and
 * endless. Choosing between them as a transient drags on is the decision this
 * class exists to make real ({@code SPEC.md} section 9.2).
 *
 * <h2>How big it is, is not this class's decision</h2>
 * The BWR/6 figures in {@link #DEFAULT_MASS_KG} are a default, not a fixture.
 * A pool is as large as the basin it was built in, and
 * {@link #resizeToDesignMassKg} is where that size arrives from. Every quantity
 * that is naturally relative — the suction floor, the level fraction — is
 * measured against {@link #getDesignMassKg()}, so a small pool behaves like a
 * small pool rather than like a permanently empty large one. It heats faster,
 * it runs out of heat capacity sooner, and it loses suction after fewer
 * kilograms. Those are consequences of building it small, not penalties for it.
 *
 * <h2>Why suppression degrades rather than failing</h2>
 * Condensation needs subcooling. As the pool warms toward saturation at
 * containment pressure, each kilogram of steam has less margin to condense
 * into, and above the saturation point it simply does not condense at all —
 * it passes through as vapour and pressurises containment. That is physics,
 * not a threshold someone chose, so it is modelled continuously here rather
 * than as a cliff.
 *
 * <p><b>No protection logic lives in this class.</b> There is no trip, no
 * automatic RHR start, no alarm. Pool temperature, subcooling and the fraction
 * of steam actually being condensed are published as measurements; what counts
 * as too hot, and what to do about it, is the player's to decide and write in
 * Lua. Real plants call this a heat capacity temperature limit and set it
 * administratively — which is exactly the kind of chosen number this mod
 * leaves to the operator.
 *
 * <p>Pure Java, no Minecraft imports, so it is unit-testable standalone.
 */
public final class SuppressionPool {

    /**
     * Nominal BWR/6 suppression pool inventory, kilograms. Roughly 3400 cubic
     * metres of water; the exact figure varies by containment design.
     *
     * <p>This is the <i>default</i> design inventory, not a fixed one. A pool
     * built in the world is whatever size the player dug, and
     * {@link #resizeToDesignMassKg} is how that size gets in. Everything that
     * used to be measured against this constant is measured against
     * {@link #getDesignMassKg()} instead — see {@link #drawSuctionKg} and
     * {@link #getLevelFraction()}.
     */
    public static final double DEFAULT_MASS_KG = 3.4e6;

    /**
     * Fraction of the design inventory below which the ECCS pumps lose suction.
     *
     * <p>Hardware, not a permissive. The suction line penetrates the pool wall
     * some way above the floor, and once the water falls below the intake the
     * pump ingests vapour and stops developing head — it does not refuse to
     * start, it simply has nothing to pull. The residue below the intake is
     * still there, still hot, and still condensing steam; it is just not
     * reachable through that nozzle.
     *
     * <p>It has to be a fraction of the pool's own design inventory rather than
     * of the BWR/6 figure. As an absolute 170,000 kg it made every pool smaller
     * than a full-size one permanently unable to supply suction, which is why
     * the mod could not size a pool from the structure the player built.
     */
    public static final double SUCTION_FLOOR_FRACTION = 0.05;

    /** Nominal initial pool temperature, degrees C. */
    public static final double DEFAULT_TEMPERATURE_C = 32.0;

    /**
     * Containment pressure the pool sits under, psia. Held at roughly
     * atmospheric here; a containment model would drive this, and when one
     * exists it should be fed in through {@link #setContainmentPressurePsia}
     * rather than recomputed.
     */
    public static final double DEFAULT_CONTAINMENT_PRESSURE_PSIA = 14.696;

    /**
     * Subcooling at which condensation is considered fully effective, degrees C.
     * Below this the pool still condenses, just less completely. This is a
     * model-calibration constant, not a plant setpoint.
     */
    private static final double FULL_CONDENSATION_SUBCOOLING_C = 12.0;

    private double massKg;
    private double designMassKg;
    private double temperatureC;
    private double containmentPressurePsia = DEFAULT_CONTAINMENT_PRESSURE_PSIA;

    /** Running totals, useful to the player and to the damage model. */
    private double cumulativeHeatInputMJ;
    private double cumulativeRhrRemovedMJ;
    private double lastUncondensedSteamKgPerS;
    private double lastCondensationEffectiveness = 1.0;

    public SuppressionPool() {
        this(DEFAULT_MASS_KG, DEFAULT_TEMPERATURE_C);
    }

    /**
     * A pool holding {@code massKg} of water at {@code temperatureC}, whose
     * design inventory is that same mass — a full pool, in other words, which is
     * the only state a pool can be built in.
     */
    public SuppressionPool(double massKg, double temperatureC) {
        this.massKg = Math.max(1.0, massKg);
        this.designMassKg = this.massKg;
        this.temperatureC = temperatureC;
    }

    // -----------------------------------------------------------------
    // Heat input
    // -----------------------------------------------------------------

    /**
     * Condense SRV discharge into the pool for one timestep.
     *
     * <p>Each kilogram of steam arrives with the vapour enthalpy of the vessel
     * it came from and leaves as liquid at pool temperature, so the heat
     * deposited is the difference. Whatever fraction cannot be condensed
     * because the pool lacks subcooling is reported through
     * {@link #getUncondensedSteamKgPerS()} and belongs to containment, not here.
     *
     * <p><b>The pressure is passed to {@link Saturation} unmodified, and it must
     * stay that way.</b> Callers legitimately condense across the entire range
     * from a rated-pressure SRV lift down to a turbine-driven pump exhausting to
     * the pool at containment pressure — 14.7 psia, two decades below the band
     * the enthalpy correlations were originally fitted on. Getting h_g right down
     * there is {@code Saturation}'s job and it is done there, once, for every
     * caller. Do <b>not</b> "fix" a wrong-looking deposited heat by clamping the
     * pressure into a fitted band here or by carrying a local correction: a clamp
     * would hand an RCIC exhaust the enthalpy of 800 psia steam, which is the
     * larger error, and a local correction is a second steam table that will
     * disagree with the first.
     *
     * @param steamKgPerS      SRV discharge rate, kilograms per second
     * @param domePressurePsig pressure the steam was relieved from, psig
     * @param dtSeconds        timestep, seconds
     * @return heat actually absorbed by the pool this step, megajoules
     */
    public double condenseSteam(double steamKgPerS, double domePressurePsig, double dtSeconds) {
        if (steamKgPerS <= 0.0 || dtSeconds <= 0.0) {
            lastUncondensedSteamKgPerS = 0.0;
            lastCondensationEffectiveness = condensationEffectiveness();
            return 0.0;
        }

        double effectiveness = condensationEffectiveness();
        lastCondensationEffectiveness = effectiveness;

        double condensedKgPerS = steamKgPerS * effectiveness;
        lastUncondensedSteamKgPerS = steamKgPerS - condensedKgPerS;

        double domePsia = Saturation.psiaFromPsig(domePressurePsig);
        double steamEnthalpy = Saturation.vapourEnthalpyKJPerKg(domePsia);
        double poolLiquidEnthalpy = Saturation.subcooledLiquidEnthalpyKJPerKg(temperatureC);

        double perKgKJ = Math.max(0.0, steamEnthalpy - poolLiquidEnthalpy);
        double heatMJ = condensedKgPerS * dtSeconds * perKgKJ / 1000.0;

        // Condensed steam joins the pool as liquid inventory.
        addHeatMJ(heatMJ);
        massKg += condensedKgPerS * dtSeconds;
        return heatMJ;
    }

    /**
     * Add heat from any other source — reactor blowdown through a break, or
     * turbine-driven ECCS exhaust discharging to the pool.
     */
    public void addHeatMJ(double heatMJ) {
        if (heatMJ == 0.0) {
            return;
        }
        double cp = specificHeatKJPerKgC();
        temperatureC += (heatMJ * 1000.0) / (massKg * cp);
        if (heatMJ > 0.0) {
            cumulativeHeatInputMJ += heatMJ;
        }
    }

    // -----------------------------------------------------------------
    // Heat removal
    // -----------------------------------------------------------------

    /**
     * Run RHR in pool cooling mode for one timestep.
     *
     * <p>Heat rejection scales with the temperature difference between the pool
     * and the ultimate heat sink, so a hot pool cools faster than a warm one
     * and cooling asymptotes rather than driving the pool below the sink.
     *
     * @param dutyFraction   how hard RHR is running, 0..1, commanded by the player
     * @param capacityMWatFullDT rated duty at the reference temperature difference
     * @param heatSinkC      ultimate heat sink temperature, degrees C
     * @param dtSeconds      timestep, seconds
     * @return heat removed this step, megajoules
     */
    public double coolWithRhr(double dutyFraction, double capacityMWatFullDT,
                              double heatSinkC, double dtSeconds) {
        if (dutyFraction <= 0.0 || dtSeconds <= 0.0) {
            return 0.0;
        }
        double duty = Math.min(1.0, Math.max(0.0, dutyFraction));
        double deltaT = temperatureC - heatSinkC;
        if (deltaT <= 0.0) {
            return 0.0;
        }

        // Reference difference at which the rated duty is quoted.
        final double referenceDeltaTC = 60.0;
        double powerMW = capacityMWatFullDT * duty * (deltaT / referenceDeltaTC);
        double removedMJ = powerMW * dtSeconds;

        // Never overshoot the sink inside one step.
        double maxRemovableMJ = deltaT * massKg * specificHeatKJPerKgC() / 1000.0;
        removedMJ = Math.min(removedMJ, maxRemovableMJ);

        addHeatMJ(-removedMJ);
        cumulativeRhrRemovedMJ += removedMJ;
        return removedMJ;
    }

    // -----------------------------------------------------------------
    // Inventory
    // -----------------------------------------------------------------

    /**
     * Resize the pool to the basin it actually sits in, kilograms.
     *
     * <p>The design inventory is a property of the structure, so it is imposed
     * from outside rather than assumed. What makes this safe to call every time
     * the structure is surveyed — which is what the block entity does — is that
     * it moves the <i>difference</i>. Water is added or removed only for the
     * change in capacity, so a pool that has been drawn down by hours of ECCS
     * suction, or filled by hours of condensate, keeps that history across a
     * survey that finds the same basin. Digging the basin bigger and filling it
     * adds water; draining part of it takes water away. A survey that finds no
     * change does nothing at all.
     *
     * <p>Added water arrives at {@code makeupTemperatureC} and mixes, because
     * water poured into a hot pool is cold water poured into a hot pool.
     *
     * @param designMassKg       inventory the structure holds when full, kg
     * @param makeupTemperatureC temperature of water added if the pool grew
     */
    public void resizeToDesignMassKg(double designMassKg, double makeupTemperatureC) {
        if (!Double.isFinite(designMassKg) || designMassKg <= 0.0) {
            return;
        }
        double target = Math.max(1.0, designMassKg);
        double delta = target - this.designMassKg;
        this.designMassKg = target;
        if (delta > 0.0) {
            addWaterKg(delta, makeupTemperatureC);
        } else if (delta < 0.0) {
            // Removing water removes it at pool temperature, so the temperature
            // of what is left does not move. Only the heat capacity does.
            massKg = Math.max(1.0, massKg + delta);
        }
    }

    /** Inventory this pool holds when the basin is full, kilograms. */
    public double getDesignMassKg() {
        return designMassKg;
    }

    /** Geometry changes alter capacity, never replenish an already metered basin. */
    public void resizeCapacityKeepingInventory(double capacityKg) {
        if(!Double.isFinite(capacityKg) || capacityKg<=0) return;
        designMassKg=Math.max(1,capacityKg);
        massKg=Math.max(1,Math.min(massKg,designMassKg));
    }

    /**
     * Water above the pump intake, kilograms — what suction can actually reach.
     *
     * <p>A measurement, not a decision: it says how much water is above the
     * nozzle, and callers that need to know how much they can draw ask it rather
     * than reconstructing the floor from constants. Reconstructing it was how
     * the mod side ended up computing a negative available volume for any pool
     * smaller than a BWR/6.
     */
    public double getAvailableSuctionKg() {
        return Math.max(0.0, massKg - designMassKg * SUCTION_FLOOR_FRACTION);
    }

    /**
     * Draw suction for an ECCS pump taking water from the pool.
     *
     * <p>Bounded by {@link #getAvailableSuctionKg()}, so delivery tails off as
     * the water reaches the intake and stops when it passes it — see
     * {@link #SUCTION_FLOOR_FRACTION}. The pump is not refused; there is
     * nothing left above the nozzle for it to lift.
     *
     * @return kilograms actually delivered, which may be less than requested if
     *         the pool is running down
     */
    public double drawSuctionKg(double requestedKgPerS, double dtSeconds) {
        if (requestedKgPerS <= 0.0 || dtSeconds <= 0.0) {
            return 0.0;
        }
        double wanted = requestedKgPerS * dtSeconds;
        double delivered = Math.min(wanted, getAvailableSuctionKg());
        massKg -= delivered;
        return delivered;
    }

    /** Add water at a given temperature — makeup from a tank, or condensate return. */
    public void addWaterKg(double kg, double temperatureOfAddedWaterC) {
        if (kg <= 0.0) {
            return;
        }
        double total = massKg + kg;
        temperatureC = (temperatureC * massKg + temperatureOfAddedWaterC * kg) / total;
        massKg = total;
    }

    // -----------------------------------------------------------------
    // Measurements — no judgements
    // -----------------------------------------------------------------

    public double getTemperatureC() {
        return temperatureC;
    }

    public double getMassKg() {
        return massKg;
    }

    /**
     * Pool level as a fraction of the design inventory — of this pool's own
     * basin, not of a BWR/6's. Above 1.0 means condensed steam has more than
     * replaced what suction has taken, which is a real thing a closed relief
     * loop does.
     */
    public double getLevelFraction() {
        return massKg / designMassKg;
    }

    /** Saturation temperature at containment pressure, degrees C. */
    public double getSaturationTemperatureC() {
        return Saturation.temperatureCelsiusFromPsia(containmentPressurePsia);
    }

    /**
     * How far below saturation the pool is, degrees C. This is the quantity that
     * actually governs whether steam condenses; zero means the pool is boiling
     * and suppression has stopped working.
     */
    public double getSubcoolingC() {
        return getSaturationTemperatureC() - temperatureC;
    }

    /**
     * Fraction of arriving steam the pool can currently condense, 0..1.
     * Falls off as subcooling is used up. Physics, not a setpoint.
     */
    public double condensationEffectiveness() {
        double subcooling = getSubcoolingC();
        if (subcooling <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, subcooling / FULL_CONDENSATION_SUBCOOLING_C);
    }

    /** Effectiveness as of the last {@link #condenseSteam} call. */
    public double getLastCondensationEffectiveness() {
        return lastCondensationEffectiveness;
    }

    /**
     * Steam that arrived but could not be condensed, kilograms per second.
     * This is what pressurises containment when the pool is exhausted.
     */
    public double getUncondensedSteamKgPerS() {
        return lastUncondensedSteamKgPerS;
    }

    /** True once the pool has reached saturation and is boiling. */
    public boolean isBoiling() {
        return getSubcoolingC() <= 0.0;
    }

    /**
     * Remaining heat capacity before the pool reaches saturation, megajoules.
     * The honest answer to "how much longer can I keep relieving into this?"
     */
    public double getRemainingHeatCapacityMJ() {
        double subcooling = Math.max(0.0, getSubcoolingC());
        return subcooling * massKg * specificHeatKJPerKgC() / 1000.0;
    }

    public double getCumulativeHeatInputMJ() {
        return cumulativeHeatInputMJ;
    }

    public double getCumulativeRhrRemovedMJ() {
        return cumulativeRhrRemovedMJ;
    }

    public double getContainmentPressurePsia() {
        return containmentPressurePsia;
    }

    public void setContainmentPressurePsia(double psia) {
        this.containmentPressurePsia = Math.max(Saturation.psiaFromPsig(0.0) * 0.1, psia);
    }

    /** Liquid specific heat at pool conditions, kJ/kg/degC. */
    private double specificHeatKJPerKgC() {
        return Saturation.liquidSpecificHeatKJPerKgC(containmentPressurePsia);
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    /** Snapshot for NBT persistence and client sync. */
    public double[] toArray() {
        return new double[]{
                massKg, temperatureC, containmentPressurePsia,
                cumulativeHeatInputMJ, cumulativeRhrRemovedMJ, designMassKg
        };
    }

    /**
     * Restore from {@link #toArray()}.
     *
     * <p>A snapshot written before the design inventory existed is five long and
     * leaves it where it was, which for a pool restored from such a save is the
     * default BWR/6 figure — the size that save was actually running at. The
     * structure survey corrects it on the next scan either way.
     */
    public void fromArray(double[] a) {
        if (a == null || a.length < 5) {
            return;
        }
        massKg = a[0];
        temperatureC = a[1];
        containmentPressurePsia = a[2];
        cumulativeHeatInputMJ = a[3];
        cumulativeRhrRemovedMJ = a[4];
        if (a.length >= 6 && Double.isFinite(a[5]) && a[5] > 0.0) {
            designMassKg = a[5];
        }
    }
}
