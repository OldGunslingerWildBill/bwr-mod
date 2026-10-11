package dev.bwr.core.accident;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.Saturation;

import java.util.ArrayList;
import java.util.List;

/**
 * Severe accident progression past the zirconium-water reaction: SPEC section
 * 8.1 stages 4 to 7. {@code FuelThermal} already owns stages 1 to 3 (uncovery,
 * clad heatup, oxidation, quench spike and hydrogen); this class takes its
 * temperatures and carries the accident on from there.
 *
 * <ol start="4">
 *   <li><b>Cladding failure.</b> Pressurised rods balloon and burst between
 *       about 750 and 1200 degC. The failed fraction is monotonic and releases
 *       the fuel-clad gap inventory. The coolant is now radioactive for good.</li>
 *   <li><b>Fuel melt and relocation.</b> Above {@link #MELT_ONSET_C} the
 *       lumped core liquefies (molten zirconium dissolving UO2 well below the
 *       2800 degC UO2 melting point). Heat above that point goes into latent
 *       heat instead of temperature. Molten material slumps into the lower
 *       plenum with a time constant of {@link #RELOCATION_TIME_CONSTANT_S}.</li>
 *   <li><b>Lower head failure.</b> Debris in a flooded lower plenum is quenched
 *       and the head survives — the TMI-2 outcome. Dry, hot debris creeps the
 *       head, faster at pressure, until it fails. The breach drains the vessel
 *       from the bottom and the debris leaves the vessel.</li>
 *   <li><b>Molten core-concrete interaction.</b> Corium on the drywell floor
 *       ablates concrete with whatever decay heat water does not remove,
 *       producing non-condensable gas and some hydrogen. Water reaching the
 *       floor (from any break, or injected through the failed head) slows or
 *       stops it. Ablating through the basemat fails containment.</li>
 * </ol>
 *
 * <p>Every damage state here is a record of what happened. Nothing decreases:
 * failed cladding stays failed, relocated fuel stays relocated and a breached
 * head stays breached. Recovery means stopping the next stage, not undoing the
 * last one.
 *
 * <p><b>Scope.</b> This is a lumped game model. It shares the class of the real
 * phenomena — burst temperatures, liquefaction, debris coolability, creep
 * rupture, ablation — and its constants are chosen so the stages unfold over
 * plausible times (minutes to relocate, tens of minutes to an hour or more for
 * a dry lower head, hours for basemat attack). It is not MELCOR or MAAP, and
 * its timings are not predictions for any real plant.
 */
public final class SevereAccident {

    // ---------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------

    /** SPEC section 8.1 stage numbers. Ordinal equals the stage number. */
    public enum Stage {
        INTACT("Intact", true),
        CORE_UNCOVERY("Core uncovery", true),
        CLADDING_HEATUP("Cladding heatup", true),
        ZIRCONIUM_REACTION("Zirconium-water reaction", true),
        CLADDING_FAILURE("Cladding failure", false),
        FUEL_MELT("Fuel melt and relocation", false),
        LOWER_HEAD_FAILURE("Lower head failure", false),
        CORE_CONCRETE_INTERACTION("Core-concrete interaction", false);

        private final String displayName;
        private final boolean recoverable;

        Stage(String displayName, boolean recoverable) {
            this.displayName = displayName;
            this.recoverable = recoverable;
        }

        public String displayName() {
            return displayName;
        }

        /** True when restoring cooling returns the core to an undamaged state. */
        public boolean isRecoverable() {
            return recoverable;
        }

        public int number() {
            return ordinal();
        }

        public static Stage byNumber(int number) {
            Stage[] values = values();
            return values[Math.max(0, Math.min(values.length - 1, number))];
        }
    }

    /** One-shot milestones for the world adapter: sounds, effects, chat. Never replayed after a reload. */
    public enum Event {
        CLADDING_FAILURE,
        FUEL_MELT_ONSET,
        CORE_RELOCATION,
        LOWER_HEAD_FAILURE,
        BASEMAT_MELT_THROUGH
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    /** Clad temperature where pressurised rods begin to balloon and burst, degC. */
    public static final double CLAD_BURST_ONSET_C = 750.0;

    /** Clad temperature by which every rod has failed, degC. */
    public static final double CLAD_BURST_COMPLETE_C = 1200.0;

    /**
     * Lumped-core liquefaction temperature, degC. Zircaloy melts at 1855 degC
     * and molten zirconium dissolves UO2, so real cores lose geometry hundreds
     * of degrees below the 2800 degC UO2 melting point. For a single-node core
     * average the liquefaction plateau is set at 2200 degC.
     */
    public static final double MELT_ONSET_C = 2200.0;

    /** Fuel specific heat used to turn the configured heat capacity into a mass, kJ/kg/degC. */
    public static final double FUEL_SPECIFIC_HEAT_KJ_PER_KG_C = 0.5;

    /** Latent heat of fusion of UO2, kJ/kg. */
    public static final double FUEL_LATENT_HEAT_KJ_PER_KG = 277.0;

    /** Time constant for molten material slumping into the lower plenum, seconds. */
    public static final double RELOCATION_TIME_CONSTANT_S = 180.0;

    /**
     * Debris-to-water conductance for the whole core submerged in the lower
     * plenum, MW per degC. Quenches a full core's worth of debris over about two
     * minutes; smaller beds scale with their mass.
     */
    public static final double WET_DEBRIS_CONDUCTANCE_MW_PER_C = 1.0;

    /**
     * Dry debris heat loss to structures and steam for the whole core, MW per
     * degC. With rated-scale decay heat this holds a dry bed in the 2000s degC:
     * hot enough to creep the head, not an unbounded temperature.
     */
    public static final double DRY_DEBRIS_CONDUCTANCE_MW_PER_C = 0.016;

    /** Lower plenum water depth needed to cover the debris bed, inches above vessel bottom. */
    public static final double DEBRIS_COVER_DEPTH_IN = 40.0;

    /** Water depth above vessel bottom where debris begins to be wetted, inches. */
    public static final double DEBRIS_WETTING_DEPTH_IN = 10.0;

    /** Debris temperature below which the lower head does not creep, degC. */
    public static final double LOWER_HEAD_CREEP_ONSET_C = 1000.0;

    /** Temperature span over which creep reaches its reference rate, degC. */
    public static final double LOWER_HEAD_CREEP_SPAN_C = 1400.0;

    /**
     * Time for dry debris at {@code onset + span} to fail the head at rated
     * pressure, seconds. Depressurised, the head lasts {@code 1 / 0.4} times as
     * long — which is why emergency depressurisation still matters after melt.
     */
    public static final double LOWER_HEAD_FAILURE_TIME_S = 2400.0;

    /** Debris fraction of the core needed before creep reaches its full rate. */
    public static final double LOWER_HEAD_LOADING_FRACTION = 0.05;

    /** Lower head breach liquid discharge at rated pressure, fraction of rated core flow. */
    public static final double LOWER_HEAD_BREACH_LIQUID_FRACTION = 0.10;

    /** Lower head breach steam discharge at rated pressure, fraction of rated steam flow. */
    public static final double LOWER_HEAD_BREACH_STEAM_FRACTION = 0.50;

    /** Breach elevation on the instrument scale, inches: one inch above vessel bottom. */
    public static final double LOWER_HEAD_BREACH_ELEVATION_IN =
            1.0 - PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;

    /** Drywell floor (pedestal) area at rated size, m2. */
    public static final double CAVITY_FLOOR_AREA_M2 = 30.0;

    /** Concrete basemat thickness under the pedestal, m. */
    public static final double BASEMAT_THICKNESS_M = 3.0;

    /** Concrete density, kg/m3. */
    public static final double CONCRETE_DENSITY_KG_PER_M3 = 2300.0;

    /** Energy to heat, decompose and melt concrete, MJ/kg. */
    public static final double CONCRETE_ABLATION_MJ_PER_KG = 2.0;

    /** Fraction of uncooled corium heat that goes down into the concrete; the rest radiates up. */
    public static final double DOWNWARD_HEAT_FRACTION = 0.5;

    /** Heat a kilogram of water carries away boiling off corium, MJ/kg. */
    public static final double WATER_QUENCH_MJ_PER_KG = 2.6;

    /**
     * Ceiling on heat a flooded floor can remove from corium, MW per m2. A crust
     * forms and limits the flux, so flooding slows ablation dramatically but
     * cannot always stop it while decay heat is high.
     */
    public static final double FLOODED_CORIUM_FLUX_LIMIT_MW_PER_M2 = 1.0;

    /** Non-condensable gas released per kg of concrete ablated (CO2/CO), mol/kg. */
    public static final double CONCRETE_GAS_MOL_PER_KG = 4.0;

    /** Hydrogen released per kg of concrete ablated (water reduced by metal), kg/kg. */
    public static final double CONCRETE_HYDROGEN_KG_PER_KG = 0.002;

    /** Gap inventory of volatile fission products, fraction of core inventory (NUREG-1465 order). */
    public static final double GAP_RELEASE_FRACTION = 0.05;

    /** Further in-vessel volatile release with the whole core molten, fraction. */
    public static final double IN_VESSEL_MELT_RELEASE_FRACTION = 0.30;

    /** Further ex-vessel volatile release with the whole core on uncooled concrete, fraction. */
    public static final double EX_VESSEL_RELEASE_FRACTION = 0.15;

    /** Time constant for ex-vessel release while core-concrete interaction continues, seconds. */
    public static final double EX_VESSEL_RELEASE_TIME_CONSTANT_S = 3600.0;

    /** Molten fraction treated as melt onset, to ignore the first joule. */
    public static final double MELT_EVENT_FRACTION = 1.0e-4;

    // ---------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------

    private double sizeScale = 1.0;

    // ---------------------------------------------------------------
    // State (all monotonic except the debris temperature)
    // ---------------------------------------------------------------

    private double failedCladFraction;
    private double moltenInCoreFraction;
    private double relocatedInVesselFraction;
    private double exVesselFraction;
    private double debrisTemperatureC;
    private double lowerHeadDamage;
    private boolean lowerHeadFailed;
    private double ablationDepthM;
    private boolean basematFailed;
    private double gapReleaseFraction;
    private double meltReleaseFraction;
    private double exVesselReleaseFraction;
    private int worstStage;
    private boolean meltEventFired;
    private boolean relocationEventFired;

    // ---------------------------------------------------------------
    // Last step outputs
    // ---------------------------------------------------------------

    private int currentStage;
    private double fuelEnergyToRemoveMJ;
    private double debrisHeatToVesselMW;
    private double debrisDecayHeatMW;
    private double exVesselDecayHeatMW;
    private double coriumWaterCoolingMW;
    private double ablationRateMPerS;
    private double concreteGasMolPerS;
    private double concreteHydrogenKgPerS;
    private double releaseRateFractionPerS;
    private double lastReleasedFraction;
    private final List<Event> pendingEvents = new ArrayList<>();

    /**
     * Scales the drywell floor area to the reactor's rated power, so a small
     * reactor's corium spreads on a proportionally smaller floor.
     */
    public void setSizeScale(double scale) {
        if (Double.isFinite(scale) && scale > 0.0) {
            this.sizeScale = scale;
        }
    }

    public double getSizeScale() {
        return sizeScale;
    }

    // ---------------------------------------------------------------
    // Step
    // ---------------------------------------------------------------

    /**
     * Advance the progression one step. Call after {@code FuelThermal.step},
     * then remove {@link #getFuelEnergyToRemoveMJ()} from the fuel node.
     *
     * @param fuelTemperatureC        lumped fuel temperature, degC
     * @param cladTemperatureC        lumped clad temperature, degC
     * @param fuelHeatCapacityMJPerC  fuel node heat capacity, MJ/degC
     * @param decayHeatMW             total decay heat of the whole core inventory, MW
     * @param pressurePsig            dome pressure, psig
     * @param collapsedLevelIn        collapsed level, instrument scale, inches
     * @param uncoveredFuelFraction   fraction of active fuel above the water, 0..1
     * @param floorWaterKgPerS        water reaching the drywell floor, kg/s
     * @param dtSeconds               step, seconds
     */
    public void step(double fuelTemperatureC, double cladTemperatureC, double fuelHeatCapacityMJPerC,
                     double decayHeatMW, double pressurePsig, double collapsedLevelIn,
                     double uncoveredFuelFraction, double floorWaterKgPerS, double dtSeconds) {
        fuelEnergyToRemoveMJ = 0.0;
        debrisHeatToVesselMW = 0.0;
        coriumWaterCoolingMW = 0.0;
        ablationRateMPerS = 0.0;
        concreteGasMolPerS = 0.0;
        concreteHydrogenKgPerS = 0.0;
        releaseRateFractionPerS = 0.0;
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }
        double fuelTemp = finite(fuelTemperatureC);
        double cladTemp = finite(cladTemperatureC);
        double decay = Math.max(0.0, finite(decayHeatMW));
        double pressure = finite(pressurePsig);
        double tsat = Saturation.temperatureCelsiusFromPsia(Saturation.psiaFromPsig(pressure));
        double releasedBefore = getReleasedFraction();

        // --- 4. Cladding failure: burst fraction follows the hottest the clad has been.
        double burst = smoothstep((cladTemp - CLAD_BURST_ONSET_C)
                / (CLAD_BURST_COMPLETE_C - CLAD_BURST_ONSET_C));
        if (burst > failedCladFraction) {
            if (failedCladFraction <= 0.0) {
                pendingEvents.add(Event.CLADDING_FAILURE);
            }
            failedCladFraction = burst;
        }
        gapReleaseFraction = Math.max(gapReleaseFraction, GAP_RELEASE_FRACTION * failedCladFraction);

        // --- 5a. Melt: heat above the liquefaction plateau becomes latent heat.
        double capacity = Math.max(0.0, finite(fuelHeatCapacityMJPerC));
        double fuelMassKg = capacity * 1000.0 / FUEL_SPECIFIC_HEAT_KJ_PER_KG_C;
        double solidInCore = Math.max(0.0, 1.0 - moltenInCoreFraction - relocatedInVesselFraction - exVesselFraction);
        if (fuelTemp > MELT_ONSET_C && capacity > 0.0 && solidInCore > 0.0) {
            double excessMJ = (fuelTemp - MELT_ONSET_C) * capacity;
            double latentMJPerFraction = fuelMassKg * FUEL_LATENT_HEAT_KJ_PER_KG / 1000.0;
            double melted = Math.min(solidInCore, excessMJ / latentMJPerFraction);
            moltenInCoreFraction += melted;
            fuelEnergyToRemoveMJ = melted * latentMJPerFraction;
        }
        if (!meltEventFired && moltenInCoreFraction + relocatedInVesselFraction + exVesselFraction > MELT_EVENT_FRACTION) {
            meltEventFired = true;
            pendingEvents.add(Event.FUEL_MELT_ONSET);
        }
        meltReleaseFraction = Math.max(meltReleaseFraction,
                IN_VESSEL_MELT_RELEASE_FRACTION * Math.min(1.0, getMeltedFraction()));

        // --- 5b. Relocation into the lower plenum, or straight out of a breached head.
        if (moltenInCoreFraction > 0.0) {
            double moved = moltenInCoreFraction * (1.0 - Math.exp(-dtSeconds / RELOCATION_TIME_CONSTANT_S));
            moltenInCoreFraction -= moved;
            if (lowerHeadFailed) {
                exVesselFraction += moved;
            } else {
                double before = relocatedInVesselFraction;
                relocatedInVesselFraction += moved;
                debrisTemperatureC = before > 0.0
                        ? (debrisTemperatureC * before + MELT_ONSET_C * moved) / relocatedInVesselFraction
                        : MELT_ONSET_C;
            }
            if (!relocationEventFired && getRelocatedFraction() > LOWER_HEAD_LOADING_FRACTION * 0.1) {
                relocationEventFired = true;
                pendingEvents.add(Event.CORE_RELOCATION);
            }
        }

        // --- 5c. In-vessel debris bed: heated by its share of decay heat, cooled by
        //         the lower plenum water when there is any, otherwise barely at all.
        debrisDecayHeatMW = decay * relocatedInVesselFraction;
        if (relocatedInVesselFraction > 0.0 && !lowerHeadFailed) {
            double levelAboveBottom = finite(collapsedLevelIn) + PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
            double wetness = clamp((levelAboveBottom - DEBRIS_WETTING_DEPTH_IN)
                    / (DEBRIS_COVER_DEPTH_IN - DEBRIS_WETTING_DEPTH_IN), 0.0, 1.0);
            double heatCapacity = relocatedInVesselFraction * capacity;
            double conductance = relocatedInVesselFraction
                    * (WET_DEBRIS_CONDUCTANCE_MW_PER_C * wetness + DRY_DEBRIS_CONDUCTANCE_MW_PER_C);
            if (heatCapacity > 0.0) {
                double next = (heatCapacity * debrisTemperatureC + debrisDecayHeatMW * dtSeconds
                        + conductance * tsat * dtSeconds) / (heatCapacity + conductance * dtSeconds);
                debrisTemperatureC = Double.isFinite(next) ? next : debrisTemperatureC;
                debrisHeatToVesselMW = Math.max(0.0, conductance * (debrisTemperatureC - tsat));
            }

            // --- 6. Lower head creep, only while the bed is hot. Damage never heals.
            double creep = Math.max(0.0, (debrisTemperatureC - LOWER_HEAD_CREEP_ONSET_C) / LOWER_HEAD_CREEP_SPAN_C);
            double pressureFactor = 0.4 + 0.6 * clamp(pressure / PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 0.0, 1.2);
            double loading = Math.min(1.0, relocatedInVesselFraction / LOWER_HEAD_LOADING_FRACTION);
            lowerHeadDamage += creep * creep * pressureFactor * loading * dtSeconds / LOWER_HEAD_FAILURE_TIME_S;
            if (lowerHeadDamage >= 1.0) {
                lowerHeadDamage = 1.0;
                lowerHeadFailed = true;
                exVesselFraction += relocatedInVesselFraction;
                relocatedInVesselFraction = 0.0;
                debrisHeatToVesselMW = 0.0;
                pendingEvents.add(Event.LOWER_HEAD_FAILURE);
            }
        }

        // --- 7. Corium on the floor. Water removes what it can; the rest attacks concrete.
        exVesselDecayHeatMW = decay * exVesselFraction;
        boolean concreteAttack = false;
        if (exVesselFraction > 0.0 && !basematFailed) {
            double area = CAVITY_FLOOR_AREA_M2 * sizeScale;
            double waterCapacityMW = Math.max(0.0, finite(floorWaterKgPerS)) * WATER_QUENCH_MJ_PER_KG;
            coriumWaterCoolingMW = Math.min(exVesselDecayHeatMW,
                    Math.min(waterCapacityMW, FLOODED_CORIUM_FLUX_LIMIT_MW_PER_M2 * area));
            double uncooledMW = exVesselDecayHeatMW - coriumWaterCoolingMW;
            if (uncooledMW > 0.0) {
                double concreteKgPerS = DOWNWARD_HEAT_FRACTION * uncooledMW / CONCRETE_ABLATION_MJ_PER_KG;
                ablationRateMPerS = concreteKgPerS / CONCRETE_DENSITY_KG_PER_M3 / area;
                ablationDepthM += ablationRateMPerS * dtSeconds;
                concreteGasMolPerS = concreteKgPerS * CONCRETE_GAS_MOL_PER_KG;
                concreteHydrogenKgPerS = concreteKgPerS * CONCRETE_HYDROGEN_KG_PER_KG;
                concreteAttack = true;
                double target = EX_VESSEL_RELEASE_FRACTION * Math.min(1.0, exVesselFraction)
                        * uncooledMW / Math.max(1.0e-9, exVesselDecayHeatMW);
                if (target > exVesselReleaseFraction) {
                    exVesselReleaseFraction += (target - exVesselReleaseFraction)
                            * (1.0 - Math.exp(-dtSeconds / EX_VESSEL_RELEASE_TIME_CONSTANT_S));
                }
                if (ablationDepthM >= BASEMAT_THICKNESS_M) {
                    ablationDepthM = BASEMAT_THICKNESS_M;
                    basematFailed = true;
                    pendingEvents.add(Event.BASEMAT_MELT_THROUGH);
                }
            }
        }

        // --- Stage bookkeeping.
        int permanent = Stage.INTACT.ordinal();
        if (failedCladFraction > 0.0) permanent = Stage.CLADDING_FAILURE.ordinal();
        if (meltEventFired) permanent = Stage.FUEL_MELT.ordinal();
        if (lowerHeadFailed) permanent = Stage.LOWER_HEAD_FAILURE.ordinal();
        if (ablationDepthM > 0.0) permanent = Stage.CORE_CONCRETE_INTERACTION.ordinal();
        int transientStage = Stage.INTACT.ordinal();
        if (uncoveredFuelFraction > 0.0) transientStage = Stage.CORE_UNCOVERY.ordinal();
        if (uncoveredFuelFraction > 0.0 && cladTemp > tsat + 100.0) transientStage = Stage.CLADDING_HEATUP.ordinal();
        if (cladTemp > PhysicalConstants.ZR_REACTION_ONSET_C) transientStage = Stage.ZIRCONIUM_REACTION.ordinal();
        if (concreteAttack) transientStage = Stage.CORE_CONCRETE_INTERACTION.ordinal();
        currentStage = Math.max(permanent, transientStage);
        worstStage = Math.max(worstStage, currentStage);

        double releasedAfter = getReleasedFraction();
        lastReleasedFraction = Math.max(0.0, releasedAfter - releasedBefore);
        releaseRateFractionPerS = lastReleasedFraction / dtSeconds;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    /** Stage now: permanent damage, or a worse transient condition. */
    public Stage getStage() {
        return Stage.byNumber(currentStage);
    }

    /** Worst stage ever reached. Monotonic. */
    public Stage getWorstStage() {
        return Stage.byNumber(worstStage);
    }

    /** True once damage can no longer be undone by restoring cooling. */
    public boolean isPermanentDamage() {
        return failedCladFraction > 0.0;
    }

    /** Fraction of fuel rods with failed cladding, 0..1. Monotonic. */
    public double getFailedCladFraction() {
        return failedCladFraction;
    }

    /** Fraction of the core molten but still in the core region, 0..1. */
    public double getMoltenInCoreFraction() {
        return moltenInCoreFraction;
    }

    /** Fraction of the core that has melted at some point, 0..1. Monotonic. */
    public double getMeltedFraction() {
        return moltenInCoreFraction + relocatedInVesselFraction + exVesselFraction;
    }

    /** Fraction of the core no longer in its channels: lower plenum plus ex-vessel. Monotonic. */
    public double getRelocatedFraction() {
        return relocatedInVesselFraction + exVesselFraction;
    }

    /** Fraction of the core lying in the lower plenum, 0..1. */
    public double getLowerPlenumDebrisFraction() {
        return relocatedInVesselFraction;
    }

    /** Fraction of the core outside the vessel, on the drywell floor, 0..1. Monotonic. */
    public double getExVesselFraction() {
        return exVesselFraction;
    }

    /** Fraction of the core neither molten nor relocated, 0..1. */
    public double getIntactFraction() {
        return Math.max(0.0, 1.0 - getMeltedFraction());
    }

    /** Lower plenum debris temperature, degC. Meaningless without debris. */
    public double getDebrisTemperatureC() {
        return relocatedInVesselFraction > 0.0 ? debrisTemperatureC : 0.0;
    }

    /** Lower head creep damage, 0..1; failure at 1. Monotonic. */
    public double getLowerHeadDamage() {
        return lowerHeadDamage;
    }

    public boolean isLowerHeadFailed() {
        return lowerHeadFailed;
    }

    /** Concrete ablated under the pedestal, m. Monotonic. */
    public double getAblationDepthM() {
        return ablationDepthM;
    }

    /** Ablation rate over the last step, mm per hour. */
    public double getAblationRateMmPerHour() {
        return ablationRateMPerS * 1000.0 * 3600.0;
    }

    public boolean isBasematFailed() {
        return basematFailed;
    }

    /** True while uncooled corium is attacking concrete. */
    public boolean isCoreConcreteInteractionActive() {
        return ablationRateMPerS > 0.0;
    }

    /**
     * Volatile fission products released from the fuel so far, fraction of the
     * core inventory. Monotonic. The gap release alone makes the coolant highly
     * radioactive; the rest is what containment is for.
     */
    public double getReleasedFraction() {
        return gapReleaseFraction + meltReleaseFraction + exVesselReleaseFraction;
    }

    /** Release over the last step, fraction of core inventory. Hand this to containment. */
    public double getLastReleasedFraction() {
        return lastReleasedFraction;
    }

    public double getReleaseRateFractionPerS() {
        return releaseRateFractionPerS;
    }

    /** Fuel energy to take out of the fuel node as latent heat this step, MJ. */
    public double getFuelEnergyToRemoveMJ() {
        return fuelEnergyToRemoveMJ;
    }

    /** Heat the in-vessel debris bed gave to water and steam this step, MW. */
    public double getDebrisHeatToVesselMW() {
        return debrisHeatToVesselMW;
    }

    /** Decay heat in the lower plenum debris, MW. */
    public double getDebrisDecayHeatMW() {
        return debrisDecayHeatMW;
    }

    /** Decay heat in ex-vessel corium, MW. */
    public double getExVesselDecayHeatMW() {
        return exVesselDecayHeatMW;
    }

    /** Heat water on the drywell floor is removing from corium, MW. */
    public double getCoriumWaterCoolingMW() {
        return coriumWaterCoolingMW;
    }

    /** Non-condensable gas from concrete decomposition, mol/s. */
    public double getConcreteGasMolPerS() {
        return concreteGasMolPerS;
    }

    /** Hydrogen from concrete decomposition, kg/s. */
    public double getConcreteHydrogenKgPerS() {
        return concreteHydrogenKgPerS;
    }

    /** Events since the last call, oldest first. Clears the queue. */
    public List<Event> drainEvents() {
        if (pendingEvents.isEmpty()) {
            return List.of();
        }
        List<Event> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    public static final int SNAPSHOT_VERSION = 1;
    public static final int SNAPSHOT_LENGTH = 16;

    /** Snapshot for NBT. Pending events are deliberately not saved: a reload never replays them. */
    public double[] toArray() {
        double[] a = new double[SNAPSHOT_LENGTH];
        a[0] = SNAPSHOT_VERSION;
        a[1] = failedCladFraction;
        a[2] = moltenInCoreFraction;
        a[3] = relocatedInVesselFraction;
        a[4] = exVesselFraction;
        a[5] = debrisTemperatureC;
        a[6] = lowerHeadDamage;
        a[7] = lowerHeadFailed ? 1 : 0;
        a[8] = ablationDepthM;
        a[9] = basematFailed ? 1 : 0;
        a[10] = gapReleaseFraction;
        a[11] = meltReleaseFraction;
        a[12] = exVesselReleaseFraction;
        a[13] = worstStage;
        a[14] = meltEventFired ? 1 : 0;
        a[15] = relocationEventFired ? 1 : 0;
        return a;
    }

    public void fromArray(double[] a) {
        if (a == null || a.length < SNAPSHOT_LENGTH) {
            return;
        }
        failedCladFraction = fraction(a[1]);
        moltenInCoreFraction = fraction(a[2]);
        relocatedInVesselFraction = fraction(a[3]);
        exVesselFraction = fraction(a[4]);
        debrisTemperatureC = finite(a[5]);
        lowerHeadDamage = fraction(a[6]);
        lowerHeadFailed = a[7] != 0;
        ablationDepthM = Math.max(0.0, Math.min(BASEMAT_THICKNESS_M, finite(a[8])));
        basematFailed = a[9] != 0;
        gapReleaseFraction = fraction(a[10]);
        meltReleaseFraction = fraction(a[11]);
        exVesselReleaseFraction = fraction(a[12]);
        worstStage = (int) Math.max(0, Math.min(Stage.values().length - 1, Math.round(finite(a[13]))));
        meltEventFired = a[14] != 0;
        relocationEventFired = a[15] != 0;
        currentStage = worstStage >= Stage.CLADDING_FAILURE.ordinal() ? permanentStageFromState() : 0;
        pendingEvents.clear();
    }

    private int permanentStageFromState() {
        if (ablationDepthM > 0.0) return Stage.CORE_CONCRETE_INTERACTION.ordinal();
        if (lowerHeadFailed) return Stage.LOWER_HEAD_FAILURE.ordinal();
        if (meltEventFired) return Stage.FUEL_MELT.ordinal();
        if (failedCladFraction > 0.0) return Stage.CLADDING_FAILURE.ordinal();
        return Stage.INTACT.ordinal();
    }

    /** Return to a pristine core: new fuel load or a creative reset. */
    public void reset() {
        fromArray(new double[SNAPSHOT_LENGTH]);
        worstStage = 0;
        currentStage = 0;
    }

    // ---------------------------------------------------------------

    private static double smoothstep(double x) {
        double t = clamp(x, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double fraction(double v) {
        return clamp(finite(v), 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "SevereAccident[%s, clad failed %.1f%%, melted %.1f%%, plenum %.1f%%, ex-vessel %.1f%%, head %.2f%s, ablation %.2f m, released %.2f%%]",
                getStage().displayName(), 100 * failedCladFraction, 100 * getMeltedFraction(),
                100 * relocatedInVesselFraction, 100 * exVesselFraction, lowerHeadDamage,
                lowerHeadFailed ? " FAILED" : "", ablationDepthM, 100 * getReleasedFraction());
    }
}
