package dev.bwr.core.thermal;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;

/**
 * Fuel and cladding temperature, zirconium oxidation and hydrogen generation.
 * {@code SPEC.md} sections 8.1 to 8.3.
 *
 * <h2>The thermal model</h2>
 * Two lumped nodes, fuel and clad, integrated by backward Euler:
 * <pre>
 *   C_f * dT_f/dt = Q_fission + Q_decay - U_fc * (T_f - T_c)
 *   C_c * dT_c/dt = U_fc * (T_f - T_c) + Q_zr - U_cc * (T_c - T_coolant) - Q_spray
 * </pre>
 *
 * <p>The fuel-to-clad conductance defaults to
 * {@code fuelHeatCapacityMJperC / fuelTimeConstantS}, so the fuel node relaxes
 * with exactly the first-order time constant {@link CoreConfig#fuelTimeConstantS}
 * specifies — six seconds by default. That lag is not cosmetic. Doppler
 * reactivity is the prompt feedback that terminates a power excursion
 * [TTC 1.7.2.1.3], and it acts on <i>fuel</i> temperature, so this time constant
 * is the delay between a power rise and the negative reactivity that stops it.
 * With a plutonium core's beta being a third of U-235's, that delay is the
 * difference between a bump and an excursion.
 *
 * <p>Backward Euler rather than explicit stepping because the clad node is
 * stiff: 25 MJ/degC against 300 MW/degC of nucleate boiling is a 0.08 s time
 * constant, shorter than a game tick.
 *
 * <p>At rated conditions the defaults give clad about 12 degC above the
 * saturated coolant and fuel about 179 degC above the clad, so a volume-average
 * fuel temperature near 478 degC. That is in the right neighbourhood of the
 * 600 degC Doppler anchor the reactivity balance uses.
 *
 * <h2>Cooling, and why uncovery is a cliff</h2>
 * Clad-to-coolant conductance is split by how much of the fuel is under water,
 * with the submerged part scaled by where it sits on the boiling curve:
 * <pre>
 *   U_cc = covered * U_wet * boilingCurveFactor(superheat)
 *        + (1 - covered) * (steam flow * cp_steam)
 * </pre>
 * Nucleate boiling is a superb heat sink. Steam is not, and worse, the steam
 * available to cool the uncovered part is <i>made by boiling the water that is
 * still there</i>. As level falls, more fuel is uncovered and less steam is
 * generated to cool it, so the two terms move against each other. That coupling
 * is why core uncovery accelerates rather than settling out, and it is modelled
 * here rather than asserted.
 *
 * <h2>The zirconium-water reaction, and why severe accidents have a cliff</h2>
 * <pre>
 *   Zr + 2 H2O  -&gt;  ZrO2 + 2 H2  + 6.42 MJ per kg of Zr
 * </pre>
 * The rate follows the Baker-Just parabolic law, {@code d(R^2)/dt = A exp(-B/T)}
 * with {@code R} the reacted zirconium per unit area. Two consequences, both of
 * them the point:
 *
 * <ul>
 *   <li><b>It is exothermic and self-accelerating.</b> The Arrhenius factor
 *       roughly triples per 100 degC, so heat raises temperature raises rate
 *       raises heat. Around 1200 degC — {@link PhysicalConstants#ZR_REACTION_ONSET_C}
 *       — the reaction is producing about 26 MW, comparable to decay heat an hour
 *       after shutdown. At 1500 degC it is near 100 MW and at 1800 degC near
 *       400 MW, an order of magnitude past anything decay heat can do.
 *       <b>Nothing in the code declares 1200 degC to be a threshold.</b> It falls
 *       out of the rate law and the protective oxide film a normally-operated
 *       core already carries, which is the correct reason for it to be there.</li>
 *   <li><b>The oxide is protective, until it is not.</b> The parabolic law means
 *       the growing ZrO2 layer slows further attack. Thermal-shock a thick brittle
 *       oxide by flooding a core at 1800 K and it cracks, exposing fresh metal:
 *       the reaction restarts on a clean surface while the flood itself boils off
 *       a surge of steam to feed it. That is the <b>quench spike</b>, documented in
 *       the QUENCH experiment series and observed at TMI-2, and it is why
 *       {@code SPEC.md} section 8.2 branches on peak clad temperature and
 *       cumulative oxidation fraction. Both are tracked here, both are monotonic,
 *       and the branch is emergent: reflood early and the oxide is thin and the
 *       recovery clean; reflood late and it is not.</li>
 * </ul>
 *
 * <p>The reaction is also limited by steam supply, because steam is the
 * reactant. A starved core stops oxidising and then resumes violently when water
 * arrives — which is exactly what makes reflood timing matter and why
 * {@link #setSteamSupplyKgPerS} should be wired to real boiloff plus spray
 * evaporation, not left at its default.
 *
 * <h2>What this class does not do</h2>
 * No trips, no setpoints, no automatic spray, no "core damage" flag. It reports
 * temperatures, an oxidation fraction and a hydrogen mass. Deciding that any of
 * those numbers is bad, and doing something about it, is the player's job in Lua.
 *
 * <p>Pure Java, doubles, no Minecraft. Not thread safe.
 */
public final class FuelThermal {

    // ---------------------------------------------------------------
    // Zirconium-water chemistry
    // ---------------------------------------------------------------

    /** Heat of reaction, MJ per kg of zirconium consumed. 586 kJ/mol over 91.22 g/mol. */
    public static final double ZR_HEAT_OF_REACTION_MJ_PER_KG = 6.42;

    /** Hydrogen produced per kg of zirconium consumed: 2 x 2.016 / 91.22. */
    public static final double HYDROGEN_PER_ZIRCONIUM_KG_PER_KG = 0.044196;

    /** Steam consumed per kg of zirconium: 2 x 18.015 / 91.22. */
    public static final double STEAM_PER_ZIRCONIUM_KG_PER_KG = 0.394969;

    /**
     * Baker-Just parabolic rate coefficient, in (kg/m2)^2 per second, converted
     * from the correlation's native mg/cm2 form:
     * {@code W^2 = 6.13e6 * t * exp(-45500/(R*T))}.
     */
    public static final double BAKER_JUST_COEFFICIENT = 613.0;

    /**
     * Baker-Just activation temperature, kelvin: 45500 cal/mol divided by the gas
     * constant 1.987 cal/mol/K.
     */
    public static final double BAKER_JUST_ACTIVATION_K = 22898.8;

    /** Zirconium in the core — cladding plus channel boxes, kg. */
    public static final double DEFAULT_ZIRCONIUM_INVENTORY_KG = 60_000.0;

    /**
     * Cladding surface area exposed to coolant, m2. 748 assemblies of 62 rods,
     * 11.2 mm outside diameter over 3.81 m of active length.
     */
    public static final double DEFAULT_CLAD_SURFACE_AREA_M2 = 6200.0;

    /**
     * Oxide film a normally-operated core already carries, expressed as reacted
     * zirconium per unit area, kg/m2.
     *
     * <p>About 20 microns of ZrO2 after a cycle, which by the Pilling-Bedworth
     * ratio is 13 microns of consumed zirconium at 6500 kg/m3. <b>This constant
     * is what makes 1200 degC the onset temperature.</b> The parabolic rate law
     * diverges on bare metal, so a core with no film would appear to oxidise
     * significantly at 1000 degC, which is wrong. With a realistic film the
     * reaction is negligible at 1000 degC, matches decay heat at 1200 degC, and
     * runs away above 1500 degC — the real curve, from a real starting condition.
     */
    public static final double DEFAULT_INITIAL_OXIDE_AREAL_MASS_KG_PER_M2 = 0.083;

    /**
     * Floor on the protective oxide, kg/m2 — set equal to the as-operated film.
     *
     * <p>Cracking exposes fresh metal, but at quench temperatures a fresh
     * zirconium surface re-oxidises almost immediately, so the effective
     * diffusion barrier bottoms out around the thickness a normal cycle grows
     * rather than going to zero. It also keeps the parabolic rate finite. This
     * floor is the main dial on quench spike severity: lower it and a late
     * reflood gets closer to a runaway.
     */
    public static final double MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2 =
            DEFAULT_INITIAL_OXIDE_AREAL_MASS_KG_PER_M2;

    /** ZrO2 density, kg/m3, for reporting oxide thickness. */
    public static final double ZIRCONIA_DENSITY_KG_PER_M3 = 5680.0;

    /** Pilling-Bedworth ratio: ZrO2 volume per unit volume of zirconium consumed. */
    public static final double PILLING_BEDWORTH_RATIO = 1.56;

    /** Zirconium metal density, kg/m3. */
    public static final double ZIRCONIUM_DENSITY_KG_PER_M3 = 6500.0;

    // ---------------------------------------------------------------
    // Quench cracking
    // ---------------------------------------------------------------

    /**
     * Cooling rate above which thermal shock starts cracking the oxide,
     * degrees C per second. Below this a cooldown is gradual enough that the
     * brittle layer follows it.
     */
    public static final double QUENCH_CRACKING_ONSET_C_PER_S = 5.0;

    /**
     * Fraction of the protective oxide lost per second per (degC/s) of cooling
     * rate beyond the onset.
     *
     * <p>The severity dial for the quench spike, and the one number in this
     * class that is fitted to behaviour rather than derived. A 130 degC/s quench
     * of a core above {@link PhysicalConstants#QUENCH_SPIKE_THRESHOLD_C} strips
     * the layer toward its floor inside a couple of seconds, which is what puts
     * a thin oxide and a hot surface in the same place at the same time. Raise
     * it and late refloods get nastier; drop it to zero and the quench spike
     * disappears entirely and reflood becomes free.
     */
    public static final double QUENCH_CRACKING_COEFFICIENT_PER_C_PER_S = 0.02;

    // ---------------------------------------------------------------
    // Heat transfer
    // ---------------------------------------------------------------

    /**
     * Clad heat capacity, MJ per degree C — about 70 tonnes of Zircaloy at
     * 0.33 kJ/kg/degC.
     */
    public static final double DEFAULT_CLAD_HEAT_CAPACITY_MJ_PER_C = 25.0;

    /**
     * Clad-to-coolant conductance with the fuel under water, MW per degree C.
     * Nucleate boiling: 3579 MW across about 12 degC of wall superheat.
     */
    public static final double DEFAULT_WET_CONDUCTANCE_MW_PER_C = 300.0;

    /** Specific heat of superheated steam, kJ per kg per degree C. */
    public static final double STEAM_SPECIFIC_HEAT_KJ_PER_KG_C = 2.5;

    /**
     * Wall superheat above which a submerged surface can no longer be wetted,
     * degrees C — the Leidenfrost point, about 250 degC of superheat at BWR
     * pressures.
     *
     * <p><b>Why this matters more than it looks.</b> Water thrown at metal that
     * is hundreds of degrees above saturation does not touch it: a vapour film
     * forms and insulates. So a hot core cannot be quenched instantly no matter
     * how much water arrives, and the quench front takes tens of seconds to
     * sweep the fuel. That delay is what makes a quench spike possible at all —
     * without it, cladding would drop from 2000 degC to saturation inside a
     * tick, the oxide would crack against an already-cold surface, and the
     * documented QUENCH and TMI-2 behaviour would quietly not happen.
     */
    public static final double REWET_SUPERHEAT_C = 250.0;

    /**
     * Clad-to-coolant conductance in film boiling as a fraction of the nucleate
     * boiling value. Film boiling runs around 100-300 W/m2K against 30-50 kW/m2K
     * for nucleate boiling, so half a percent.
     */
    public static final double FILM_BOILING_CONDUCTANCE_FRACTION = 0.005;

    /** Width of the transition between nucleate and film boiling, degrees C. */
    public static final double BOILING_TRANSITION_C = 40.0;

    /**
     * Temperature scale over which core spray saturates, degrees C. Spray
     * removes heat up to the enthalpy its flow can carry; this smooths the
     * approach to that ceiling instead of clipping it, which would put a corner
     * in the middle of a reflood transient.
     */
    public static final double SPRAY_APPROACH_C = 100.0;

    /** Default steam supply, kg/s. Rated steam flow: generous but finite. */
    public static final double DEFAULT_STEAM_SUPPLY_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * VoidModel.KG_PER_LB / 3600.0;

    /** Clad temperature change per sub-step above which {@link #step} subdivides, degrees C. */
    public static final double MAX_CLAD_STEP_C = 10.0;

    /** Ceiling on sub-steps per {@link #step}. */
    public static final int MAX_SUB_STEPS = 64;

    // ---------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------

    private final CoreConfig config;

    private double fuelHeatCapacityMJPerC;
    private double cladHeatCapacityMJPerC = DEFAULT_CLAD_HEAT_CAPACITY_MJ_PER_C;
    private double fuelToCladConductanceMWPerC;
    private double wetConductanceMWPerC = DEFAULT_WET_CONDUCTANCE_MW_PER_C;
    private double zirconiumInventoryKg = DEFAULT_ZIRCONIUM_INVENTORY_KG;
    private double cladSurfaceAreaM2 = DEFAULT_CLAD_SURFACE_AREA_M2;

    // --- inputs the caller drives each tick -----------------------------

    private double coveredFuelFraction = 1.0;
    private double steamCoolingFlowKgPerS;
    private double coreSprayFlowKgPerS;
    private double sprayTemperatureC = 40.0;
    private double steamSupplyKgPerS = DEFAULT_STEAM_SUPPLY_KG_PER_S;

    // --- state ----------------------------------------------------------

    private double fuelTemperatureC = 20.0;
    private double cladTemperatureC = 20.0;
    private double peakCladTemperatureC = 20.0;
    private double peakFuelTemperatureC = 20.0;
    private double reactedArealMassKgPerM2;
    private double protectiveArealMassKgPerM2 = DEFAULT_INITIAL_OXIDE_AREAL_MASS_KG_PER_M2;
    private double hydrogenGeneratedKg;

    // --- last computed ---------------------------------------------------

    private double zirconiumReactionRateKgPerS;
    private double kineticReactionRateKgPerS;
    private double zirconiumReactionPowerMW;
    private double hydrogenGenerationRateKgPerS;
    private double steamConsumptionKgPerS;
    private double sprayHeatRemovalMW;
    private double sprayEvaporationKgPerS;
    private double coolantTemperatureC = 20.0;
    private double cladToCoolantConductanceMWPerC;

    /**
     * @param config supplies the rated thermal power, the fuel heat capacity and
     *               the fuel time constant. The fuel-to-clad conductance is
     *               derived as {@code capacity / timeConstant} so the node's lag
     *               is the configured one by construction; override it with
     *               {@link #setFuelToCladConductanceMWPerC} and the effective
     *               time constant moves with it.
     */
    public FuelThermal(CoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.fuelHeatCapacityMJPerC = config.fuelHeatCapacityMJperC;
        double tau = config.fuelTimeConstantS;
        this.fuelToCladConductanceMWPerC = (tau > 0.0)
                ? config.fuelHeatCapacityMJperC / tau
                : config.fuelHeatCapacityMJperC;
    }

    // ---------------------------------------------------------------
    // Step
    // ---------------------------------------------------------------

    /**
     * Advance fuel and clad temperature and the oxidation state one tick.
     *
     * @param fissionPowerFractionOfRated fission power, fraction of rated
     * @param decayHeatFractionOfRated    decay heat, fraction of rated, from
     *                                    {@link DecayHeat}. Kept separate from
     *                                    fission power only for the readout; both
     *                                    are deposited in the fuel.
     * @param pressurePsig                dome pressure, psig. The vessel is
     *                                    saturated, so this sets the coolant sink
     *                                    temperature and the spray and boiling
     *                                    enthalpies.
     * @param dtSeconds                   step length, seconds; non-positive is a no-op
     * @return the new clad temperature, degrees C
     */
    public double step(double fissionPowerFractionOfRated,
                       double decayHeatFractionOfRated,
                       double pressurePsig,
                       double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return cladTemperatureC;
        }

        double psia = Saturation.psiaFromPsig(finite(pressurePsig));
        this.coolantTemperatureC = Saturation.temperatureCelsiusFromPsia(psia);
        double latentHeatKJPerKg = Saturation.latentHeatKJPerKg(psia);
        double liquidEnthalpyKJPerKg = Saturation.liquidEnthalpyKJPerKg(psia);

        double fuelPowerMW = (nonNegative(fissionPowerFractionOfRated)
                + nonNegative(decayHeatFractionOfRated)) * config.ratedThermalMW;

        double covered = clamp(coveredFuelFraction, 0.0, 1.0);
        double steamConductance =
                nonNegative(steamCoolingFlowKgPerS) * STEAM_SPECIFIC_HEAT_KJ_PER_KG_C / 1000.0;

        // Enthalpy a kilogram of spray water can absorb before it is gone:
        // sensible heat to saturation plus the latent heat of boiling it.
        double sprayEnthalpyKJPerKg = latentHeatKJPerKg
                + Math.max(0.0, liquidEnthalpyKJPerKg
                        - Saturation.subcooledLiquidEnthalpyKJPerKg(sprayTemperatureC));
        double sprayCapacityMW = nonNegative(coreSprayFlowKgPerS) * sprayEnthalpyKJPerKg / 1000.0;

        int subSteps = chooseSubSteps(fuelPowerMW, dtSeconds);
        double dt = dtSeconds / subSteps;

        for (int s = 0; s < subSteps; s++) {
            stepOnce(fuelPowerMW, covered, steamConductance, sprayCapacityMW,
                    sprayEnthalpyKJPerKg, dt);
        }
        return cladTemperatureC;
    }

    /**
     * Fraction of the nucleate boiling conductance available at a given wall
     * superheat — a smoothed boiling curve. Unity while the surface is wetted,
     * {@link #FILM_BOILING_CONDUCTANCE_FRACTION} once it is blanketed in vapour,
     * with a transition centred on {@link #REWET_SUPERHEAT_C}.
     */
    public static double boilingCurveFactor(double wallSuperheatC) {
        double x = (wallSuperheatC - REWET_SUPERHEAT_C) / BOILING_TRANSITION_C;
        double wetted = 1.0 / (1.0 + Math.exp(x));
        return FILM_BOILING_CONDUCTANCE_FRACTION
                + (1.0 - FILM_BOILING_CONDUCTANCE_FRACTION) * wetted;
    }

    private void stepOnce(double fuelPowerMW, double coveredFraction, double steamConductanceMWPerC,
                          double sprayCapacityMW, double sprayEnthalpyKJPerKg, double dt) {
        double previousCladTemperatureC = cladTemperatureC;

        // Submerged fuel is only as well cooled as the boiling regime allows.
        double wetConductance = wetConductanceMWPerC
                * boilingCurveFactor(cladTemperatureC - coolantTemperatureC);
        this.cladToCoolantConductanceMWPerC = coveredFraction * wetConductance
                + (1.0 - coveredFraction) * steamConductanceMWPerC;

        // --- zirconium-water reaction, explicit at the current clad temperature.
        // The rate law is what makes this autocatalytic; nothing amplifies it by
        // hand, and nothing declares an onset temperature.
        double kineticRate = kineticReactionRateKgPerS(cladTemperatureC);
        double rate = kineticRate;

        double remainingKg = Math.max(0.0,
                (totalArealZirconiumKgPerM2() - reactedArealMassKgPerM2) * cladSurfaceAreaM2);
        if (rate * dt > remainingKg) {
            rate = remainingKg / dt;
        }
        double steamLimitedRate = nonNegative(steamSupplyKgPerS) / STEAM_PER_ZIRCONIUM_KG_PER_KG;
        if (rate > steamLimitedRate) {
            rate = steamLimitedRate;
        }
        if (!(rate > 0.0)) {
            rate = 0.0;
        }

        double zirconiumPowerMW = rate * ZR_HEAT_OF_REACTION_MJ_PER_KG;

        // --- core spray, saturating smoothly as the clad approaches the sink.
        double superheatC = Math.max(0.0, cladTemperatureC - coolantTemperatureC);
        double sprayMW = sprayCapacityMW * (1.0 - Math.exp(-superheatC / SPRAY_APPROACH_C));

        // --- two-node backward Euler.
        double af = fuelHeatCapacityMJPerC / dt;
        double ac = cladHeatCapacityMJPerC / dt;
        double u = fuelToCladConductanceMWPerC;
        double ucc = cladToCoolantConductanceMWPerC;

        double b1 = af * fuelTemperatureC + fuelPowerMW;
        double b2 = ac * cladTemperatureC + zirconiumPowerMW + ucc * coolantTemperatureC - sprayMW;

        double determinant = (af + u) * (ac + u + ucc) - u * u;
        if (!(determinant > 0.0)) {
            return; // degenerate configuration; leave the state alone rather than poison it
        }
        double newFuel = (b1 * (ac + u + ucc) + u * b2) / determinant;
        double newClad = ((af + u) * b2 + u * b1) / determinant;

        fuelTemperatureC = Double.isFinite(newFuel) ? newFuel : fuelTemperatureC;
        cladTemperatureC = Double.isFinite(newClad) ? newClad : cladTemperatureC;

        // --- oxidation bookkeeping. Every one of these is monotonic.
        double reactedKg = rate * dt;
        if (reactedKg > 0.0) {
            double arealIncrement = reactedKg / cladSurfaceAreaM2;
            reactedArealMassKgPerM2 += arealIncrement;
            protectiveArealMassKgPerM2 += arealIncrement;
            hydrogenGeneratedKg += reactedKg * HYDROGEN_PER_ZIRCONIUM_KG_PER_KG;
        }

        // --- quench cracking. A rapid cooldown of a hot, thick, brittle oxide
        // spalls it and exposes fresh metal. Reflooding early leaves this branch
        // untouched; reflooding late does not.
        double coolingRateCPerS = (previousCladTemperatureC - cladTemperatureC) / dt;
        if (previousCladTemperatureC > PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C
                && coolingRateCPerS > QUENCH_CRACKING_ONSET_C_PER_S) {
            double excess = coolingRateCPerS - QUENCH_CRACKING_ONSET_C_PER_S;
            double lossPerSecond = QUENCH_CRACKING_COEFFICIENT_PER_C_PER_S * excess;
            protectiveArealMassKgPerM2 *= Math.exp(-lossPerSecond * dt);
            if (protectiveArealMassKgPerM2 < MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2) {
                protectiveArealMassKgPerM2 = MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2;
            }
        }

        if (cladTemperatureC > peakCladTemperatureC) {
            peakCladTemperatureC = cladTemperatureC;
        }
        if (fuelTemperatureC > peakFuelTemperatureC) {
            peakFuelTemperatureC = fuelTemperatureC;
        }

        this.kineticReactionRateKgPerS = kineticRate;
        this.zirconiumReactionRateKgPerS = rate;
        this.zirconiumReactionPowerMW = zirconiumPowerMW;
        this.hydrogenGenerationRateKgPerS = rate * HYDROGEN_PER_ZIRCONIUM_KG_PER_KG;
        this.steamConsumptionKgPerS = rate * STEAM_PER_ZIRCONIUM_KG_PER_KG;
        this.sprayHeatRemovalMW = sprayMW;
        this.sprayEvaporationKgPerS = (sprayEnthalpyKJPerKg > 0.0)
                ? sprayMW * 1000.0 / sprayEnthalpyKJPerKg : 0.0;
    }

    /**
     * Unrestricted Baker-Just reaction rate at a given clad temperature, kg of
     * zirconium per second — before the remaining-metal and steam-supply limits.
     *
     * <pre>
     *   d(R^2)/dt = A * exp(-B / T)      so      dR/dt = A * exp(-B / T) / (2 R)
     * </pre>
     * with {@code R} the reacted areal mass of the protective layer. The
     * {@code 1/R} is the parabolic self-limiting: a thick oxide is a diffusion
     * barrier, and cracking it is what removes that protection.
     */
    public double kineticReactionRateKgPerS(double cladTemperatureC) {
        double kelvin = cladTemperatureC + 273.15;
        if (!(kelvin > 0.0) || !Double.isFinite(kelvin)) {
            return 0.0;
        }
        double parabolic = BAKER_JUST_COEFFICIENT * Math.exp(-BAKER_JUST_ACTIVATION_K / kelvin);
        double protective = Math.max(MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2, protectiveArealMassKgPerM2);
        double arealRate = parabolic / (2.0 * protective);
        return arealRate * cladSurfaceAreaM2;
    }

    private int chooseSubSteps(double fuelPowerMW, double dtSeconds) {
        double zirconiumPowerMW =
                kineticReactionRateKgPerS(cladTemperatureC) * ZR_HEAT_OF_REACTION_MJ_PER_KG;
        double drive = zirconiumPowerMW + fuelPowerMW;
        if (!(drive > 0.0) || !(cladHeatCapacityMJPerC > 0.0)) {
            return 1;
        }
        double estimate = drive * dtSeconds / cladHeatCapacityMJPerC;
        int steps = (int) Math.ceil(estimate / MAX_CLAD_STEP_C);
        return Math.max(1, Math.min(MAX_SUB_STEPS, steps));
    }

    // ---------------------------------------------------------------
    // Temperature readouts
    // ---------------------------------------------------------------

    /** Volume-average fuel temperature, degrees C. Drives Doppler reactivity. */
    public double getFuelTemperatureC() {
        return fuelTemperatureC;
    }

    /** Average cladding temperature, degrees C. */
    public double getCladTemperatureC() {
        return cladTemperatureC;
    }

    /**
     * Highest cladding temperature ever reached, degrees C. <b>Monotonic — this
     * never decreases.</b> One of the two states {@code SPEC.md} section 8.2
     * branches on, because damage is a record of what happened, not of what is
     * happening now.
     */
    public double getPeakCladTemperatureC() {
        return peakCladTemperatureC;
    }

    /** Highest fuel temperature ever reached, degrees C. Monotonic. */
    public double getPeakFuelTemperatureC() {
        return peakFuelTemperatureC;
    }

    /**
     * Peak fuel temperature as a fraction of the UO2 melting point,
     * {@link PhysicalConstants#FUEL_MELT_C}. A plain ratio. Relocation and
     * corium formation belong to the severe accident model, not here.
     */
    public double getPeakFuelTemperatureFractionOfMelt() {
        return peakFuelTemperatureC / PhysicalConstants.FUEL_MELT_C;
    }

    /** Coolant sink temperature the last step used, degrees C — saturation at dome pressure. */
    public double getCoolantTemperatureC() {
        return coolantTemperatureC;
    }

    /** Effective clad-to-coolant conductance the last step used, MW per degree C. */
    public double getCladToCoolantConductanceMWPerC() {
        return cladToCoolantConductanceMWPerC;
    }

    // ---------------------------------------------------------------
    // Oxidation readouts
    // ---------------------------------------------------------------

    /**
     * Cumulative zirconium oxidation, 0..1 of the core inventory. <b>Monotonic.</b>
     * The second state {@code SPEC.md} section 8.2 branches on. At TMI-2 this
     * reached roughly a third.
     */
    public double getOxidationFraction() {
        double total = totalArealZirconiumKgPerM2();
        if (!(total > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, reactedArealMassKgPerM2 / total);
    }

    /** Zirconium consumed so far, kg. Monotonic. */
    public double getOxidisedZirconiumKg() {
        return reactedArealMassKgPerM2 * cladSurfaceAreaM2;
    }

    /**
     * Hydrogen generated so far, kg. <b>Monotonic.</b> This is the star mechanic
     * of {@code SPEC.md} section 8.3 — it accumulates in containment and above
     * about 4% by volume it is explosive, which is why real Mark I and II
     * containments are nitrogen-inerted. Full oxidation of the core inventory
     * makes about 2650 kg of it.
     */
    public double getHydrogenGeneratedKg() {
        return hydrogenGeneratedKg;
    }

    /** Hydrogen production rate over the last step, kg/s. */
    public double getHydrogenGenerationRateKgPerS() {
        return hydrogenGenerationRateKgPerS;
    }

    /**
     * Heat the zirconium-water reaction is adding, MW. Compare against decay
     * heat: once this is the larger of the two, cooling has to remove both and
     * the reaction is feeding itself.
     */
    public double getZirconiumReactionPowerMW() {
        return zirconiumReactionPowerMW;
    }

    /** Zirconium consumption rate over the last step, kg/s, after all limits. */
    public double getZirconiumReactionRateKgPerS() {
        return zirconiumReactionRateKgPerS;
    }

    /**
     * Reaction rate the temperature alone would give, kg/s, before the
     * remaining-metal and steam-supply limits. Larger than
     * {@link #getZirconiumReactionRateKgPerS()} means the reaction is starved —
     * and a starved reaction is stored energy waiting for the next water to
     * arrive.
     */
    public double getKineticReactionRateKgPerS() {
        return kineticReactionRateKgPerS;
    }

    /** Steam the reaction is consuming, kg/s. */
    public double getSteamConsumptionKgPerS() {
        return steamConsumptionKgPerS;
    }

    /**
     * Thickness of the protective oxide layer, microns. Falls when a quench
     * cracks it, which is the visible signature of a quench spike about to
     * happen.
     */
    public double getProtectiveOxideThicknessMicrons() {
        double metalThicknessM = protectiveArealMassKgPerM2 / ZIRCONIUM_DENSITY_KG_PER_M3;
        return metalThicknessM * PILLING_BEDWORTH_RATIO * 1.0e6;
    }

    /** Total oxide grown, microns, ignoring cracking. Monotonic. */
    public double getTotalOxideThicknessMicrons() {
        double metalThicknessM = reactedArealMassKgPerM2 / ZIRCONIUM_DENSITY_KG_PER_M3;
        return metalThicknessM * PILLING_BEDWORTH_RATIO * 1.0e6;
    }

    /** Heat core spray is removing, MW. */
    public double getSprayHeatRemovalMW() {
        return sprayHeatRemovalMW;
    }

    /**
     * Spray water boiled off, kg/s. Route this back into
     * {@link #setSteamSupplyKgPerS} and into the vessel steam balance: the flood
     * that cools the core is also the flood that supplies the reactant, and that
     * loop is the quench spike.
     */
    public double getSprayEvaporationKgPerS() {
        return sprayEvaporationKgPerS;
    }

    // ---------------------------------------------------------------
    // Cooling inputs
    // ---------------------------------------------------------------

    /**
     * Fraction of the active fuel height under water, 0..1, from
     * {@link PressureVessel#getCoveredFuelFraction()}.
     */
    public void setCoveredFuelFraction(double coveredFuelFraction) {
        this.coveredFuelFraction = clamp(coveredFuelFraction, 0.0, 1.0);
    }

    public double getCoveredFuelFraction() {
        return coveredFuelFraction;
    }

    /**
     * Steam flowing up past the uncovered fuel, kg/s — normally the vessel
     * boiloff from {@link PressureVessel#getSteamGenerationKgPerS()}. This is
     * the only heat sink an uncovered rod has, and its supply shrinks as the
     * water that makes it runs out.
     */
    public void setSteamCoolingFlowKgPerS(double steamCoolingFlowKgPerS) {
        this.steamCoolingFlowKgPerS = nonNegative(steamCoolingFlowKgPerS);
    }

    public double getSteamCoolingFlowKgPerS() {
        return steamCoolingFlowKgPerS;
    }

    /**
     * Core spray flow, kg/s. Spray cools uncovered fuel directly, which is the
     * mechanical distinction {@code SPEC.md} section 9.4 insists on: spray buys
     * time in an uncovered core, injection has to refill the vessel first.
     */
    public void setCoreSprayFlowKgPerS(double coreSprayFlowKgPerS) {
        this.coreSprayFlowKgPerS = nonNegative(coreSprayFlowKgPerS);
    }

    public double getCoreSprayFlowKgPerS() {
        return coreSprayFlowKgPerS;
    }

    /** Core spray water temperature, degrees C. Colder spray carries more heat per kg. */
    public void setSprayTemperatureC(double sprayTemperatureC) {
        this.sprayTemperatureC = finite(sprayTemperatureC);
    }

    public double getSprayTemperatureC() {
        return sprayTemperatureC;
    }

    /**
     * Steam available to the zirconium-water reaction, kg/s — boiloff plus spray
     * evaporation.
     *
     * <p>Defaults to rated steam flow, which is effectively unlimited. <b>Wire
     * this to the real steam supply.</b> Steam starvation is not a detail: it is
     * why a dry, hot core pauses its oxidation and then resumes violently the
     * moment water arrives, and leaving the default in place deletes that.
     */
    public void setSteamSupplyKgPerS(double steamSupplyKgPerS) {
        this.steamSupplyKgPerS = nonNegative(steamSupplyKgPerS);
    }

    public double getSteamSupplyKgPerS() {
        return steamSupplyKgPerS;
    }

    // ---------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------

    /** Fuel heat capacity, MJ per degree C for the whole core. */
    public double getFuelHeatCapacityMJPerC() {
        return fuelHeatCapacityMJPerC;
    }

    /** @see #getFuelHeatCapacityMJPerC() */
    public void setFuelHeatCapacityMJPerC(double fuelHeatCapacityMJPerC) {
        this.fuelHeatCapacityMJPerC = requirePositive(fuelHeatCapacityMJPerC, "fuel heat capacity");
    }

    /** Clad heat capacity, MJ per degree C for the whole core. */
    public double getCladHeatCapacityMJPerC() {
        return cladHeatCapacityMJPerC;
    }

    /** @see #getCladHeatCapacityMJPerC() */
    public void setCladHeatCapacityMJPerC(double cladHeatCapacityMJPerC) {
        this.cladHeatCapacityMJPerC = requirePositive(cladHeatCapacityMJPerC, "clad heat capacity");
    }

    /**
     * Fuel-to-clad conductance, MW per degree C. Together with the fuel heat
     * capacity this <i>is</i> the fuel time constant:
     * {@code tau = capacity / conductance}.
     */
    public double getFuelToCladConductanceMWPerC() {
        return fuelToCladConductanceMWPerC;
    }

    /** @see #getFuelToCladConductanceMWPerC() */
    public void setFuelToCladConductanceMWPerC(double fuelToCladConductanceMWPerC) {
        this.fuelToCladConductanceMWPerC =
                requirePositive(fuelToCladConductanceMWPerC, "fuel to clad conductance");
    }

    /** Effective fuel thermal time constant, seconds: capacity over conductance. */
    public double getEffectiveFuelTimeConstantSeconds() {
        return fuelHeatCapacityMJPerC / fuelToCladConductanceMWPerC;
    }

    /** Clad-to-coolant conductance with the fuel submerged, MW per degree C. */
    public double getWetConductanceMWPerC() {
        return wetConductanceMWPerC;
    }

    /** @see #getWetConductanceMWPerC() */
    public void setWetConductanceMWPerC(double wetConductanceMWPerC) {
        this.wetConductanceMWPerC = requirePositive(wetConductanceMWPerC, "wet conductance");
    }

    /** Zirconium in the core, kg — cladding plus channels. */
    public double getZirconiumInventoryKg() {
        return zirconiumInventoryKg;
    }

    /** @see #getZirconiumInventoryKg() */
    public void setZirconiumInventoryKg(double zirconiumInventoryKg) {
        this.zirconiumInventoryKg = requirePositive(zirconiumInventoryKg, "zirconium inventory");
    }

    /** Cladding surface area, m2. */
    public double getCladSurfaceAreaM2() {
        return cladSurfaceAreaM2;
    }

    /** @see #getCladSurfaceAreaM2() */
    public void setCladSurfaceAreaM2(double cladSurfaceAreaM2) {
        this.cladSurfaceAreaM2 = requirePositive(cladSurfaceAreaM2, "clad surface area");
    }

    /** Zirconium per unit clad area, kg/m2 — about 9.7 with the defaults. */
    public double totalArealZirconiumKgPerM2() {
        return zirconiumInventoryKg / cladSurfaceAreaM2;
    }

    // ---------------------------------------------------------------
    // Initialisation and persistence
    // ---------------------------------------------------------------

    /**
     * Put fuel and clad at their steady-state temperatures for a given power and
     * pressure, with the fuel fully covered. Starting a run at rated power from
     * 20 degC would otherwise inject a several-minute thermal transient and a
     * spurious Doppler ramp that have nothing to do with the scenario.
     */
    public void initialiseToSteadyState(double totalPowerFractionOfRated, double pressurePsig) {
        double powerMW = nonNegative(totalPowerFractionOfRated) * config.ratedThermalMW;
        this.coolantTemperatureC = Saturation.temperatureCelsiusFromPsig(finite(pressurePsig));

        // The boiling curve makes the clad balance implicit, so settle it by
        // fixed point. Converges in a handful of passes at any power; a core hot
        // enough to be in film boiling at steady state finds that too.
        double conductance = wetConductanceMWPerC;
        for (int i = 0; i < 40; i++) {
            double clad = coolantTemperatureC + powerMW / conductance;
            conductance = wetConductanceMWPerC * boilingCurveFactor(clad - coolantTemperatureC);
        }
        this.cladToCoolantConductanceMWPerC = conductance;
        this.cladTemperatureC = coolantTemperatureC + powerMW / conductance;
        this.fuelTemperatureC = cladTemperatureC + powerMW / fuelToCladConductanceMWPerC;
        this.peakCladTemperatureC = Math.max(peakCladTemperatureC, cladTemperatureC);
        this.peakFuelTemperatureC = Math.max(peakFuelTemperatureC, fuelTemperatureC);
    }

    /** Restore persisted temperatures, degrees C. Persistence only. */
    public void restoreTemperatures(double fuelTemperatureC, double cladTemperatureC) {
        this.fuelTemperatureC = finite(fuelTemperatureC);
        this.cladTemperatureC = finite(cladTemperatureC);
    }

    /**
     * Restore persisted damage state.
     *
     * <p>All three are monotonic in normal operation, so this is the only way
     * they can decrease and it exists solely so a world reload resumes where it
     * left off. Restoring lower values than the core has actually reached erases
     * damage that happened.
     *
     * <p><b>This overload cannot restore peak fuel temperature.</b> It has no
     * parameter for it, so all it can do is put a floor under it at the peak clad
     * temperature, and fuel runs about 180 degC hotter than clad even at rated —
     * far more in an excursion — so the floor always understates it. A caller
     * that persists {@link #getPeakFuelTemperatureC()} must use
     * {@link #restoreDamageState(double, double, double, double)} or
     * {@link #restorePeakFuelTemperatureC(double)} instead. This overload is kept
     * only for callers that have no saved value to give.
     *
     * @param peakCladTemperatureC highest clad temperature reached, degrees C
     * @param oxidationFraction    cumulative oxidation, 0..1
     * @param hydrogenGeneratedKg  hydrogen produced so far, kg
     */
    public void restoreDamageState(double peakCladTemperatureC,
                                   double oxidationFraction,
                                   double hydrogenGeneratedKg) {
        this.peakCladTemperatureC = finite(peakCladTemperatureC);
        this.peakFuelTemperatureC = Math.max(this.peakFuelTemperatureC, this.peakCladTemperatureC);
        double fraction = clamp(oxidationFraction, 0.0, 1.0);
        this.reactedArealMassKgPerM2 = fraction * totalArealZirconiumKgPerM2();
        this.protectiveArealMassKgPerM2 = Math.max(MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2,
                DEFAULT_INITIAL_OXIDE_AREAL_MASS_KG_PER_M2 + this.reactedArealMassKgPerM2);
        this.hydrogenGeneratedKg = Math.max(0.0, finite(hydrogenGeneratedKg));
    }

    /**
     * Restore persisted damage state including the peak fuel temperature.
     *
     * <p>Peak fuel temperature is the model's fuel-melt record — it is what
     * {@link #getPeakFuelTemperatureFractionOfMelt()} is computed from — and it
     * is monotonic, so losing it across a save undoes a melt that happened.
     * Prefer this over the three-argument overload wherever the value is
     * actually persisted.
     *
     * @param peakCladTemperatureC highest clad temperature reached, degrees C
     * @param peakFuelTemperatureC highest fuel centreline temperature reached,
     *                             degrees C. Floored at the peak clad temperature
     *                             so a save written before the value was
     *                             persisted still restores something sane rather
     *                             than 20 degC.
     * @param oxidationFraction    cumulative oxidation, 0..1
     * @param hydrogenGeneratedKg  hydrogen produced so far, kg
     */
    public void restoreDamageState(double peakCladTemperatureC,
                                   double peakFuelTemperatureC,
                                   double oxidationFraction,
                                   double hydrogenGeneratedKg) {
        restoreDamageState(peakCladTemperatureC, oxidationFraction, hydrogenGeneratedKg);
        restorePeakFuelTemperatureC(peakFuelTemperatureC);
    }

    /**
     * Restore the persisted peak fuel temperature, degrees C. Persistence only.
     *
     * <p>Floored at the peak clad temperature, which is a genuine physical floor:
     * heat flows fuel to clad, so the fuel cannot have peaked cooler than the
     * clad did. Order-independent with respect to
     * {@link #restoreDamageState(double, double, double)} for that reason.
     */
    public void restorePeakFuelTemperatureC(double peakFuelTemperatureC) {
        this.peakFuelTemperatureC = Math.max(this.peakCladTemperatureC, finite(peakFuelTemperatureC));
    }

    /** Restore the protective oxide layer separately, kg/m2 of reacted zirconium. Persistence only. */
    public void restoreProtectiveArealMassKgPerM2(double protectiveArealMassKgPerM2) {
        this.protectiveArealMassKgPerM2 = Math.max(MINIMUM_PROTECTIVE_AREAL_MASS_KG_PER_M2,
                finite(protectiveArealMassKgPerM2));
    }

    /** Protective oxide layer as reacted zirconium per unit area, kg/m2. For persistence. */
    public double getProtectiveArealMassKgPerM2() {
        return protectiveArealMassKgPerM2;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double requirePositive(double value, String name) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and positive, got " + value);
        }
        return value;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double nonNegative(double value) {
        return (Double.isFinite(value) && value > 0.0) ? value : 0.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.min(maximum, Math.max(minimum, value));
    }

    @Override
    public String toString() {
        return String.format(
                "FuelThermal[fuel %.0f degC, clad %.0f degC (peak %.0f), oxidation %.3f%%, H2 %.1f kg,"
                        + " Zr power %.1f MW]",
                fuelTemperatureC, cladTemperatureC, peakCladTemperatureC,
                100.0 * getOxidationFraction(), hydrogenGeneratedKg, zirconiumReactionPowerMW);
    }
}
