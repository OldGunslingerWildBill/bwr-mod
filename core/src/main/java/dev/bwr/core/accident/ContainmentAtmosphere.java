package dev.bwr.core.accident;

import java.util.ArrayList;
import java.util.List;
import dev.bwr.core.thermal.Saturation;

/**
 * Lumped primary containment atmosphere: SPEC section 8.3, the hydrogen.
 *
 * <p>Zirconium oxidation and core-concrete interaction put hydrogen into a
 * fixed free volume of gas. Once it passes about 4% by volume with enough
 * oxygen present it is flammable; at {@link #IGNITION_FRACTION} it lights.
 * A deflagration burns the hydrogen and spikes the pressure. A mixture that
 * reaches oxygen while already above {@link #DETONATION_FRACTION} detonates
 * instead, and a detonation always fails containment.
 *
 * <p><b>Inerting</b> is the defence real Mark I and II containments use:
 * nitrogen purged in until oxygen is below 4%. Hydrogen can then accumulate
 * to any concentration without burning — until oxygen comes back. De-inerting
 * a containment full of hydrogen, or failing it so air leaks in, is exactly how
 * a stored hazard becomes a detonation. Purging is not instant: oxygen relaxes
 * toward its target with {@link #INERTING_TIME_CONSTANT_S}.
 *
 * <p><b>Venting</b> through the suppression pool lowers pressure and removes
 * hydrogen, at the cost of a scrubbed release ({@link #POOL_DECONTAMINATION_FACTOR}).
 * A failed containment leaks unscrubbed.
 *
 * <p>Steam, structural heat storage and spray use a lumped one-node model.
 * Burn criteria and pressure rise use simple lean
 * hydrogen-air relations. Game model, not a containment analysis code.
 */
public final class ContainmentAtmosphere {

    /** One-shot events for the world adapter. */
    public enum Event {
        HYDROGEN_FLAMMABLE,
        HYDROGEN_DEFLAGRATION,
        HYDROGEN_DETONATION,
        CONTAINMENT_OVERPRESSURE_FAILURE
    }

    /** Why containment failed, if it has. */
    public enum FailureMode {
        NONE, OVERPRESSURE, DETONATION, BASEMAT_MELT_THROUGH
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    public static final double GAS_CONSTANT = 8.314462618;
    public static final double ATMOSPHERIC_PA = 101_325.0;
    public static final double PA_PER_PSI = 6894.757;

    /** Free volume, drywell plus wetwell airspace, at rated size, m3. Mark I order. */
    public static final double FREE_VOLUME_AT_RATED_M3 = 8000.0;

    /** Legacy initial gas temperature, K. */
    public static final double GAS_TEMPERATURE_K = 330.0;
    public static final double STEAM_MOL_PER_KG = 1000.0 / 18.01528;
    /** Blowdown steam that contacts drywell structures before the downcomers; one-node calibration. */
    public static final double DRYWELL_WALL_CONTACT_FRACTION = 0.75;
    public static final double STEAM_INERTING_FRACTION = 0.55;
    public static final double STEAM_LATENT_HEAT_J_PER_KG = 2_257_000.0;
    public static final double STEAM_REFERENCE_ENTHALPY_KJ_PER_KG = 2675.0;
    public static final double STEAM_HEAT_CAPACITY_J_PER_KG_K = 2100.0;
    public static final double GAS_HEAT_CAPACITY_J_PER_MOL_K = 29.0;
    /** Lumped concrete and steel heat capacity per free cubic metre. */
    public static final double WALL_HEAT_CAPACITY_J_PER_K_M3 = 100_000.0;
    /** Gas-to-structure sensible and condensation exchange time scale. */
    public static final double WALL_HEAT_TRANSFER_TIME_S = 3600.0;
    /** Structure's passive loss to a 27 C surrounding per free cubic metre. */
    public static final double AMBIENT_LOSS_W_PER_K_M3 = 0.1;
    public static final double AMBIENT_TEMPERATURE_K = 300.0;
    /** Airborne activity remaining after 1 kg/m3 of spray traverses the room. */
    public static final double SPRAY_DECONTAMINATION_PER_KG_M3 = 0.8;
    public static final double SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K = 4180.0;

    public static final double AIR_OXYGEN_FRACTION = 0.21;

    /** Oxygen fraction an inerted containment is held at. Tech specs require below 4%. */
    public static final double INERTED_OXYGEN_FRACTION = 0.02;

    /** Time constant for nitrogen purge or de-inerting, seconds. */
    public static final double INERTING_TIME_CONSTANT_S = 1800.0;

    /** A purge stops once oxygen is this close to its target, mole fraction. */
    public static final double PURGE_COMPLETE_TOLERANCE = 0.002;

    /**
     * After a burn, hydrogen still arriving burns as it enters — a standing
     * flame at the release point — for this long, rather than accumulating to
     * a second explosion seconds later. Seconds.
     */
    public static final double STANDING_FLAME_S = 120.0;

    /** Lower flammability limit of hydrogen in air, mole fraction. */
    public static final double FLAMMABILITY_FRACTION = 0.041;

    /** Minimum oxygen for combustion, mole fraction. */
    public static final double OXYGEN_LIMIT_FRACTION = 0.05;

    /** Hydrogen fraction at which a burn starts given oxygen, mole fraction. */
    public static final double IGNITION_FRACTION = 0.08;

    /** Hydrogen fraction at ignition above which the burn is a detonation, mole fraction. */
    public static final double DETONATION_FRACTION = 0.13;

    /** Lean AICC pressure ratio slope: P_peak / P = 1 + slope * x_H2. */
    public static final double AICC_PRESSURE_SLOPE = 8.5;

    /** Containment design pressure, psig. Mark I. */
    public static final double DESIGN_PRESSURE_PSIG = 56.0;

    /** Pressure at which containment fails, psig. About 2.5 times design. */
    public static final double FAILURE_PRESSURE_PSIG = 140.0;

    /** Time constant for gas leaving through an open vent, seconds. */
    public static final double VENT_TIME_CONSTANT_S = 300.0;

    /** Time constant for gas leaving through a failed containment, seconds. */
    public static final double FAILED_TIME_CONSTANT_S = 600.0;

    /** An open boundary exchanges with the outdoors on a much shorter time scale. */
    public static final double OPEN_BOUNDARY_TIME_CONSTANT_S = 5.0;

    /** Design leakage of an intact containment, fraction of contents per second (0.5%/day). */
    public static final double DESIGN_LEAK_FRACTION_PER_S = 0.005 / 86_400.0;

    /** Suppression pool decontamination factor on a wetwell vent. */
    public static final double POOL_DECONTAMINATION_FACTOR = 100.0;

    /** Time constant for airborne fission products settling and plating out, seconds. */
    public static final double DEPOSITION_TIME_CONSTANT_S = 7200.0;

    public static final double HYDROGEN_MOL_PER_KG = 1000.0 / 2.016;

    /**
     * Half-life of the contamination hazard indices, seconds of simulated time.
     * Real fission product mixtures fall off quickly over the first days as the
     * short-lived iodine and tellurium decay, then linger on caesium. One
     * simulated day keeps a wrecked plant dangerous to work around without
     * making the area permanently uninhabitable.
     */
    public static final double CONTAMINATION_HALF_LIFE_S = 86_400.0;

    // ---------------------------------------------------------------
    // Configuration and state
    // ---------------------------------------------------------------

    private double volumeM3 = FREE_VOLUME_AT_RATED_M3;
    private double failurePressurePsig = FAILURE_PRESSURE_PSIG;
    private double leakArea;
    private double nitrogenMol;
    private double oxygenMol;
    private double hydrogenMol;
    private double otherGasMol;
    private double steamKg;
    private double gasTemperatureK = GAS_TEMPERATURE_K;
    private double wallTemperatureK = GAS_TEMPERATURE_K;
    private double lastSprayFlowKgPerS;
    private SteamInputs pendingSteamInputs = SteamInputs.NONE;
    private boolean inertingCommanded;
    private boolean ventOpen;
    private FailureMode failureMode = FailureMode.NONE;
    private double airborneFraction;
    private double depositedFraction;
    private double environmentalReleaseFraction;
    private int burnCount;
    private double hydrogenBurnedKg;
    private double peakPressurePsig;
    private double lastBurnPeakPsig;
    private boolean flammableLatched;
    private double plantContamination;
    private double siteContamination;
    private boolean purgeActive;
    private double clockSeconds;
    private double lastBurnClockSeconds = Double.NEGATIVE_INFINITY;

    private final List<Event> pendingEvents = new ArrayList<>();

    public ContainmentAtmosphere() {
        fillWithAir(AIR_OXYGEN_FRACTION);
    }

    /** Scale the free volume to the reactor's size; refills with air at atmospheric pressure. */
    public void setSizeScale(double scale) {
        if (Double.isFinite(scale) && scale > 0.0) {
            double oxygen = oxygenFraction();
            volumeM3 = FREE_VOLUME_AT_RATED_M3 * scale;
            fillWithAir(oxygen > 0.0 ? oxygen : AIR_OXYGEN_FRACTION);
        }
    }

    /** Resize an already filled room without changing its pressure or gas mixture. */
    public void setFreeVolumeM3(double volume) {
        if (!Double.isFinite(volume)) return;
        double next = Math.max(50.0, volume);
        double ratio = next / volumeM3;
        nitrogenMol *= ratio;
        oxygenMol *= ratio;
        hydrogenMol *= ratio;
        otherGasMol *= ratio;
        steamKg *= ratio;
        volumeM3 = next;
    }

    public void setFailurePressurePsig(double pressure) {
        if (Double.isFinite(pressure)) failurePressurePsig = Math.max(0.0, pressure);
    }

    public double getFailurePressurePsig() {
        return failurePressurePsig;
    }

    /** Fractional opening in the boundary; zero is sealed and one is outdoors. */
    public void setLeakArea(double fraction) {
        if (Double.isFinite(fraction)) leakArea = Math.clamp(fraction, 0.0, 1.0);
    }

    public double getLeakArea() {
        return leakArea;
    }

    /** Activity carried directly outside by a coolant line, without pool scrubbing. */
    public void releaseDirectToEnvironment(double fraction) {
        double released = Math.max(0.0, finite(fraction));
        environmentalReleaseFraction += released;
        siteContamination += released;
    }

    /** Start inerted, as an operating BWR containment normally is. */
    public void initialiseInerted() {
        inertingCommanded = true;
        purgeActive = false;
        fillWithAir(INERTED_OXYGEN_FRACTION);
    }

    private void fillWithAir(double oxygenFraction) {
        double total = ATMOSPHERIC_PA * volumeM3 / (GAS_CONSTANT * gasTemperatureK);
        oxygenMol = total * oxygenFraction;
        nitrogenMol = total - oxygenMol;
        hydrogenMol = 0.0;
        otherGasMol = 0.0;
        steamKg = 0.0;
    }

    // ---------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------

    /**
     * Start a nitrogen purge (true) or an air purge to de-inert (false). The
     * purge runs over {@link #INERTING_TIME_CONSTANT_S} and stops once oxygen
     * reaches its target; after that a sealed containment simply holds what it
     * has. Burns consume oxygen, and nothing puts it back except another purge
     * or a failed boundary.
     */
    public void setInertingCommanded(boolean inerting) {
        if (inerting != this.inertingCommanded || !purgeActive) {
            purgeActive = true;
        }
        this.inertingCommanded = inerting;
    }

    /** True while a nitrogen or air purge is running. */
    public boolean isPurgeActive() {
        return purgeActive;
    }

    /** Stop the supply without starting an air purge or resetting the gas inventory. */
    public void stopPurge() {
        purgeActive = false;
        inertingCommanded = false;
    }

    public boolean isInertingCommanded() {
        return inertingCommanded;
    }

    /** Open or close the hardened wetwell vent. */
    public void setVentOpen(boolean open) {
        this.ventOpen = open;
    }

    public boolean isVentOpen() {
        return ventOpen;
    }

    /** Containment fails because corium ate through the basemat. */
    public void failByMeltThrough() {
        if (failureMode == FailureMode.NONE) {
            failureMode = FailureMode.BASEMAT_MELT_THROUGH;
        }
    }

    /**
     * The broken wall has been rebuilt and the room seals again. Overpressure
     * and detonation failures clear; melt-through does not, because the corium
     * is still in the floor. Contamination, release totals and peak pressure
     * are history and stay.
     */
    public void clearStructuralFailure() {
        if (failureMode == FailureMode.OVERPRESSURE || failureMode == FailureMode.DETONATION) {
            failureMode = FailureMode.NONE;
        }
    }

    // ---------------------------------------------------------------
    // Step
    // ---------------------------------------------------------------

    /** Optional process inputs for one containment step; zero means disconnected. */
    public record SteamInputs(double drywellSteamKgPerS, double drywellEnthalpyKJPerKg,
                              double wetwellSteamKgPerS, double poolSurfaceTemperatureK,
                              double sprayKgPerS, double sprayTemperatureK) {
        public static final SteamInputs NONE = new SteamInputs(0, 0, 0, 0, 0, 0);
    }

    /** Stage process inputs for the next step only. A missed update cannot latch flow. */
    public void setSteamInputs(SteamInputs inputs) {
        pendingSteamInputs = inputs == null ? SteamInputs.NONE : inputs;
    }

    /**
     * @param hydrogenInKgPerS       hydrogen entering containment, kg/s
     * @param otherGasInMolPerS      non-condensable gas entering, mol/s
     * @param fissionProductFraction fission products released from the fuel this step, fraction of core inventory
     * @param dtSeconds              step, seconds
     */
    public void step(double hydrogenInKgPerS, double otherGasInMolPerS,
                     double fissionProductFraction, double dtSeconds) {
        SteamInputs inputs = pendingSteamInputs;
        pendingSteamInputs = SteamInputs.NONE;
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }
        stepSteam(inputs, dtSeconds);
        hydrogenMol += Math.max(0.0, finite(hydrogenInKgPerS)) * HYDROGEN_MOL_PER_KG * dtSeconds;
        otherGasMol += Math.max(0.0, finite(otherGasInMolPerS)) * dtSeconds;
        double fuelRelease = Math.max(0.0, finite(fissionProductFraction));
        // An open boundary has no enclosed airspace in which to retain its
        // share of a new release. A completely uncontained core sends it all
        // outside immediately; a partial opening partitions it by area.
        releaseDirectToEnvironment(fuelRelease * leakArea);
        airborneFraction += fuelRelease * (1.0 - leakArea);

        clockSeconds += dtSeconds;

        // Oxygen moves toward a target only while a purge drives it: nitrogen
        // to inert, air to de-inert. Nitrogen makes up the difference so the
        // purge itself does not change the total. A failed containment gets its
        // air from outside instead, below.
        double total = totalMol();
        boolean failed = failureMode != FailureMode.NONE;
        boolean openBoundary = leakArea > 0.0;
        double air = oxygenMol + nitrogenMol;
        if (!failed && !openBoundary && purgeActive && air > 0.0) {
            double targetOxygen = inertingCommanded ? INERTED_OXYGEN_FRACTION : AIR_OXYGEN_FRACTION;
            double targetOxygenMol = targetOxygen * air;
            double alpha = 1.0 - Math.exp(-dtSeconds / INERTING_TIME_CONSTANT_S);
            double change = (targetOxygenMol - oxygenMol) * alpha;
            oxygenMol += change;
            nitrogenMol -= change;
            if (purgeActive && Math.abs(oxygenMol - targetOxygenMol) < PURGE_COMPLETE_TOLERANCE * air) {
                purgeActive = false;
            }
        }

        // Gas leaves through the vent, the failure, or design leakage. Every
        // species leaves in proportion, so hydrogen and airborne activity go
        // with it; only a pool-scrubbed vent holds most of the activity back.
        double pressurePa = total * GAS_CONSTANT * gasTemperatureK / volumeM3;
        double excessFraction = Math.max(0.0, (pressurePa - ATMOSPHERIC_PA) / pressurePa);
        double leaving = DESIGN_LEAK_FRACTION_PER_S * dtSeconds;
        double decontamination = 1.0;
        if (failed) {
            leaving = Math.max(leaving, excessFraction * (1.0 - Math.exp(-dtSeconds / FAILED_TIME_CONSTANT_S)));
        } else if (openBoundary) {
            leaving = Math.max(leaving, excessFraction *
                    (1.0 - Math.exp(-dtSeconds * leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S)));
        } else if (ventOpen) {
            leaving = Math.max(leaving, excessFraction * (1.0 - Math.exp(-dtSeconds / VENT_TIME_CONSTANT_S)));
            decontamination = POOL_DECONTAMINATION_FACTOR;
        }
        removeProportionally(Math.min(1.0, Math.max(0.0, leaving)), decontamination);

        // A failed containment also breathes: convection and wind swap its gas
        // for outside air. This is what brings oxygen back to a hydrogen-filled
        // building, and it is the second half of the Fukushima failure mode.
        if (failed || openBoundary) {
            double exchangeRate = (failed ? 1.0 / (4.0 * FAILED_TIME_CONSTANT_S) : 0.0)
                    + leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S;
            double exchange = 1.0 - Math.exp(-dtSeconds * exchangeRate);
            double outside = ATMOSPHERIC_PA * volumeM3 / (GAS_CONSTANT * gasTemperatureK);
            double replaced = Math.min(totalMol(), outside) * exchange;
            removeProportionally(replaced / totalMol(), 1.0);
            // Below atmospheric — after a burn has consumed gas — outside air
            // flows in through the same opening until the pressure is equal.
            double deficit = Math.max(0.0, outside - (totalMol() + replaced))
                    * (1.0 - Math.exp(-dtSeconds * Math.max(
                    failed ? 1.0 / FAILED_TIME_CONSTANT_S : 0.0, exchangeRate)));
            oxygenMol += (replaced + deficit) * AIR_OXYGEN_FRACTION;
            nitrogenMol += (replaced + deficit) * (1.0 - AIR_OXYGEN_FRACTION);
        }

        // Fission products settle and plate out inside the plant.
        double settled = airborneFraction * (1.0 - Math.exp(-dtSeconds / DEPOSITION_TIME_CONSTANT_S));
        airborneFraction -= settled;
        depositedFraction += settled;
        plantContamination += settled;
        double decay = Math.exp(-dtSeconds * Math.log(2.0) / CONTAMINATION_HALF_LIFE_S);
        plantContamination *= decay;
        siteContamination *= decay;

        // Combustion. Shortly after a burn, arriving hydrogen burns at the
        // release point instead of building toward another explosion.
        if (clockSeconds - lastBurnClockSeconds < STANDING_FLAME_S && !isSteamInerted()
                && oxygenFraction() >= OXYGEN_LIMIT_FRACTION && hydrogenFraction() >= FLAMMABILITY_FRACTION) {
            double burned = Math.min(hydrogenMol - FLAMMABILITY_FRACTION * totalMol(), 2.0 * oxygenMol);
            if (burned > 0.0) {
                hydrogenMol -= burned;
                oxygenMol -= burned / 2.0;
                hydrogenBurnedKg += burned / HYDROGEN_MOL_PER_KG;
                lastBurnClockSeconds = clockSeconds;
            }
        }
        double xH2 = hydrogenFraction();
        double xO2 = oxygenFraction();
        boolean flammable = xH2 >= FLAMMABILITY_FRACTION && xO2 >= OXYGEN_LIMIT_FRACTION
                && !isSteamInerted();
        if (flammable && !flammableLatched) {
            pendingEvents.add(Event.HYDROGEN_FLAMMABLE);
        }
        flammableLatched = flammable;
        if (!isSteamInerted() && xO2 >= OXYGEN_LIMIT_FRACTION && xH2 >= IGNITION_FRACTION) {
            burn(xH2 >= DETONATION_FRACTION);
            lastBurnClockSeconds = clockSeconds;
        }

        double psig = getPressurePsig();
        peakPressurePsig = Math.max(peakPressurePsig, psig);
        if (failureMode == FailureMode.NONE && psig > failurePressurePsig) {
            failureMode = FailureMode.OVERPRESSURE;
            pendingEvents.add(Event.CONTAINMENT_OVERPRESSURE_FAILURE);
        }
    }

    private void stepSteam(SteamInputs in, double dt) {
        double dry = nonNegative(in.drywellSteamKgPerS());
        double wet = nonNegative(in.wetwellSteamKgPerS());
        double spray = nonNegative(in.sprayKgPerS());
        lastSprayFlowKgPerS = spray;
        if (dry + wet + spray + steamKg <= 0.0) return; // Legacy gas-only behavior.

        double arriving = (dry + wet) * dt;
        steamKg += arriving;
        double gasCapacity = Math.max(1.0, dryMol() * GAS_HEAT_CAPACITY_J_PER_MOL_K
                + steamKg * STEAM_MOL_PER_KG * 34.0);
        double inletH = in.drywellEnthalpyKJPerKg() > 0.0 && Double.isFinite(in.drywellEnthalpyKJPerKg())
                ? in.drywellEnthalpyKJPerKg() : STEAM_REFERENCE_ENTHALPY_KJ_PER_KG;
        double inletK = 373.15 + (inletH - STEAM_REFERENCE_ENTHALPY_KJ_PER_KG) * 1000.0
                / STEAM_HEAT_CAPACITY_J_PER_KG_K;
        inletK = Math.clamp(inletK, 273.15, 650.0);
        double wetK = in.poolSurfaceTemperatureK() > 0.0 && Double.isFinite(in.poolSurfaceTemperatureK())
                ? Math.max(373.15, in.poolSurfaceTemperatureK()) : 373.15;
        gasTemperatureK += (dry * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K * (inletK - gasTemperatureK)
                + wet * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K * (wetK - gasTemperatureK)) / gasCapacity;

        double wallCapacity = WALL_HEAT_CAPACITY_J_PER_K_M3 * volumeM3;
        double contact = 1.0 - Math.exp(-dt / WALL_HEAT_TRANSFER_TIME_S);
        double gasToWall = (gasTemperatureK - wallTemperatureK) * gasCapacity * contact;
        gasTemperatureK -= gasToWall / gasCapacity;
        wallTemperatureK += gasToWall / wallCapacity;

        // Steam condenses on the coldest available surface. A warm pool does
        // not raise the cap imposed by colder containment structures.
        double surfaceK = wallTemperatureK;
        boolean poolIsColdest = in.poolSurfaceTemperatureK() > 0.0
                && Double.isFinite(in.poolSurfaceTemperatureK())
                && in.poolSurfaceTemperatureK() < wallTemperatureK;
        if (poolIsColdest) surfaceK = in.poolSurfaceTemperatureK();
        double wallLimitKg = saturatedSteamKg(surfaceK);
        double condensed = Math.max(0.0, steamKg - wallLimitKg);
        steamKg -= condensed;
        // Keep the released latent heat in this lumped thermal node. The
        // linked pool temperature controls saturation; its detailed surface
        // heat exchange is outside this one-node atmosphere model.
        wallTemperatureK += condensed * STEAM_LATENT_HEAT_J_PER_KG / wallCapacity;

        if (spray > 0.0 && in.sprayTemperatureK() > 0.0 && Double.isFinite(in.sprayTemperatureK())) {
            double sprayK = Math.clamp(in.sprayTemperatureK(), 273.15, 450.0);
            double targetKg = saturatedSteamKg(sprayK);
            double potential = Math.max(0.0, steamKg - targetKg);
            double perKg = STEAM_LATENT_HEAT_J_PER_KG
                    + STEAM_HEAT_CAPACITY_J_PER_KG_K * Math.max(0.0, gasTemperatureK - sprayK);
            double captured = Math.min(potential, spray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K
                    * Math.max(0.0, Math.max(373.15, gasTemperatureK) - sprayK) / Math.max(1.0, perKg));
            steamKg -= captured;
            double gasCooling = Math.min(Math.max(0.0, gasTemperatureK - sprayK) * gasCapacity,
                    spray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K * Math.max(0.0, gasTemperatureK - sprayK));
            gasTemperatureK -= gasCooling / gasCapacity;
            double washed = airborneFraction * (1.0 - Math.exp(-SPRAY_DECONTAMINATION_PER_KG_M3
                    * spray * dt / volumeM3));
            airborneFraction -= washed;
            depositedFraction += washed;
            plantContamination += washed;
        }
        wallTemperatureK -= (wallTemperatureK - AMBIENT_TEMPERATURE_K)
                * (1.0 - Math.exp(-AMBIENT_LOSS_W_PER_K_M3 * volumeM3 * dt / wallCapacity));
        gasTemperatureK = Math.clamp(gasTemperatureK, 273.15, 650.0);
        wallTemperatureK = Math.clamp(wallTemperatureK, 273.15, 650.0);
    }

    private double saturatedSteamKg(double temperatureK) {
        double c = Math.clamp(temperatureK - 273.15, 0.0, 250.0);
        // Buck saturation curve below boiling; the existing plant steam-table
        // fit handles the higher range. Both meet near atmospheric boiling.
        double pa = c <= 100.0
                ? 611.21 * Math.exp((18.678 - c / 234.5) * c / (257.14 + c))
                : Saturation.pressurePsiaFromTemperatureCelsius(c) * PA_PER_PSI;
        return pa * volumeM3 / (GAS_CONSTANT * Math.max(273.15, gasTemperatureK) * STEAM_MOL_PER_KG);
    }

    private void burn(boolean detonation) {
        double before = getPressurePsia();
        double xH2 = hydrogenFraction();
        double burned = Math.min(hydrogenMol, 2.0 * oxygenMol);
        double peakPsia = before * (1.0 + AICC_PRESSURE_SLOPE * xH2);
        hydrogenMol -= burned;
        oxygenMol -= burned / 2.0;
        // The water formed condenses at containment temperature; the gas
        // inventory falls by 1.5 mol per mol of hydrogen burned.
        hydrogenBurnedKg += burned / HYDROGEN_MOL_PER_KG;
        burnCount++;
        lastBurnPeakPsig = peakPsia - ATMOSPHERIC_PA / PA_PER_PSI;
        peakPressurePsig = Math.max(peakPressurePsig, lastBurnPeakPsig);
        if (detonation) {
            pendingEvents.add(Event.HYDROGEN_DETONATION);
            if (failureMode == FailureMode.NONE) {
                failureMode = FailureMode.DETONATION;
            }
        } else {
            pendingEvents.add(Event.HYDROGEN_DEFLAGRATION);
            if (failureMode == FailureMode.NONE && lastBurnPeakPsig > failurePressurePsig) {
                failureMode = FailureMode.OVERPRESSURE;
                pendingEvents.add(Event.CONTAINMENT_OVERPRESSURE_FAILURE);
            }
        }
    }

    /**
     * Remove a fraction of every gas species, carrying the same fraction of the
     * airborne fission products out with it. A decontamination factor above one
     * holds the difference back in the plant (a scrubbed vent).
     */
    private void removeProportionally(double fraction, double decontamination) {
        if (!(fraction > 0.0)) {
            return;
        }
        double keep = 1.0 - Math.min(1.0, fraction);
        nitrogenMol *= keep;
        oxygenMol *= keep;
        hydrogenMol *= keep;
        otherGasMol *= keep;
        steamKg *= keep;
        double escaping = airborneFraction * (1.0 - keep);
        airborneFraction -= escaping;
        double released = escaping / Math.max(1.0, decontamination);
        environmentalReleaseFraction += released;
        siteContamination += released;
        depositedFraction += escaping - released;
        plantContamination += escaping - released;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    public double getVolumeM3() {
        return volumeM3;
    }

    private double totalMol() {
        return Math.max(1e-9, dryMol() + steamKg * STEAM_MOL_PER_KG);
    }

    private double dryMol() {
        return nitrogenMol + oxygenMol + hydrogenMol + otherGasMol;
    }

    /** Hydrogen mole fraction, 0..1. */
    public double hydrogenFraction() {
        return hydrogenMol / totalMol();
    }

    /** Oxygen mole fraction, 0..1. */
    public double oxygenFraction() {
        return oxygenMol / totalMol();
    }

    public double getHydrogenPercent() {
        return 100.0 * hydrogenFraction();
    }

    public double getOxygenPercent() {
        return 100.0 * oxygenFraction();
    }

    public double getHydrogenKg() {
        return hydrogenMol / HYDROGEN_MOL_PER_KG;
    }

    public double getSteamKg() { return steamKg; }
    public double getSteamPercent() { return 100.0 * steamKg * STEAM_MOL_PER_KG / totalMol(); }
    public double getGasTemperatureK() { return gasTemperatureK; }
    public double getGasTemperatureC() { return gasTemperatureK - 273.15; }
    public double getWallTemperatureK() { return wallTemperatureK; }
    public double getLastSprayFlowKgPerS() { return lastSprayFlowKgPerS; }
    public double getSteamPartialPressurePsia() {
        return steamKg * STEAM_MOL_PER_KG * GAS_CONSTANT * gasTemperatureK / volumeM3 / PA_PER_PSI;
    }
    public boolean isSteamInerted() { return getSteamPercent() >= 100.0 * STEAM_INERTING_FRACTION; }

    public double getPressurePsia() {
        return totalMol() * GAS_CONSTANT * gasTemperatureK / volumeM3 / PA_PER_PSI;
    }

    public double getPressurePsig() {
        return getPressurePsia() - ATMOSPHERIC_PA / PA_PER_PSI;
    }

    /** Drywell and wetwell share one gas inventory and pressure in this model. */
    public int getPressureNodeCount() { return 1; }
    public double getDrywellPressurePsig() { return getPressurePsig(); }
    public double getWetwellPressurePsig() { return getPressurePsig(); }

    public double getPeakPressurePsig() {
        return peakPressurePsig;
    }

    public double getLastBurnPeakPressurePsig() {
        return lastBurnPeakPsig;
    }

    /** True when the current mixture would burn if lit. */
    public boolean isFlammable() {
        return !isSteamInerted() && hydrogenFraction() >= FLAMMABILITY_FRACTION
                && oxygenFraction() >= OXYGEN_LIMIT_FRACTION;
    }

    /** True once oxygen is below the combustion limit. */
    public boolean isInerted() {
        return oxygenFraction() < OXYGEN_LIMIT_FRACTION;
    }

    public boolean isFailed() {
        return failureMode != FailureMode.NONE;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public int getBurnCount() {
        return burnCount;
    }

    public double getHydrogenBurnedKg() {
        return hydrogenBurnedKg;
    }

    /** Fission products airborne in containment, fraction of core inventory. */
    public double getAirborneFraction() {
        return airborneFraction;
    }

    /** Fission products deposited on surfaces inside the plant, fraction of core inventory. Monotonic. */
    public double getDepositedFraction() {
        return depositedFraction;
    }

    /** Fission products released to the environment, fraction of core inventory. Monotonic. */
    public double getEnvironmentalReleaseFraction() {
        return environmentalReleaseFraction;
    }

    /**
     * Hazard inside the plant, fraction-of-core-inventory units: what is still
     * airborne in containment plus deposited activity that has not yet decayed.
     * The world adapter turns this into a dose near the reactor.
     */
    public double getPlantContaminationIndex() {
        return airborneFraction + plantContamination;
    }

    /** Hazard released to the surroundings, same units, decaying. */
    public double getSiteContaminationIndex() {
        return siteContamination;
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

    public static final int SNAPSHOT_VERSION = 3;
    public static final int LEGACY_SNAPSHOT_LENGTH = 22;
    public static final int V2_SNAPSHOT_LENGTH = 24;
    public static final int SNAPSHOT_LENGTH = 28;

    public double[] toArray() {
        double[] a = new double[SNAPSHOT_LENGTH];
        a[0] = SNAPSHOT_VERSION;
        a[1] = volumeM3;
        a[2] = nitrogenMol;
        a[3] = oxygenMol;
        a[4] = hydrogenMol;
        a[5] = otherGasMol;
        a[6] = inertingCommanded ? 1 : 0;
        a[7] = ventOpen ? 1 : 0;
        a[8] = failureMode.ordinal();
        a[9] = airborneFraction;
        a[10] = depositedFraction;
        a[11] = environmentalReleaseFraction;
        a[12] = burnCount;
        a[13] = hydrogenBurnedKg;
        a[14] = peakPressurePsig;
        a[15] = lastBurnPeakPsig;
        a[16] = flammableLatched ? 1 : 0;
        a[17] = plantContamination;
        a[18] = siteContamination;
        a[19] = purgeActive ? 1 : 0;
        a[20] = clockSeconds;
        a[21] = Double.isFinite(lastBurnClockSeconds) ? lastBurnClockSeconds : -1.0e12;
        a[22] = failurePressurePsig;
        a[23] = leakArea;
        a[24] = steamKg;
        a[25] = gasTemperatureK;
        a[26] = wallTemperatureK;
        a[27] = lastSprayFlowKgPerS;
        return a;
    }

    public void fromArray(double[] a) {
        if (a == null || a.length < LEGACY_SNAPSHOT_LENGTH) {
            return;
        }
        if (Double.isFinite(a[1]) && a[1] > 0.0) volumeM3 = Math.max(50.0, a[1]);
        nitrogenMol = nonNegative(a[2]);
        oxygenMol = nonNegative(a[3]);
        hydrogenMol = nonNegative(a[4]);
        otherGasMol = nonNegative(a[5]);
        if (nitrogenMol + oxygenMol <= 0.0) fillWithAir(AIR_OXYGEN_FRACTION);
        inertingCommanded = a[6] != 0;
        ventOpen = a[7] != 0;
        FailureMode[] modes = FailureMode.values();
        int mode = (int) Math.round(finite(a[8]));
        failureMode = mode >= 0 && mode < modes.length ? modes[mode] : FailureMode.NONE;
        airborneFraction = nonNegative(a[9]);
        depositedFraction = nonNegative(a[10]);
        environmentalReleaseFraction = nonNegative(a[11]);
        burnCount = (int) Math.max(0, Math.round(finite(a[12])));
        hydrogenBurnedKg = nonNegative(a[13]);
        peakPressurePsig = finite(a[14]);
        lastBurnPeakPsig = finite(a[15]);
        flammableLatched = a[16] != 0;
        plantContamination = nonNegative(a[17]);
        siteContamination = nonNegative(a[18]);
        purgeActive = a[19] != 0;
        clockSeconds = nonNegative(a[20]);
        lastBurnClockSeconds = Double.isFinite(a[21]) ? a[21] : Double.NEGATIVE_INFINITY;
        failurePressurePsig = a.length >= V2_SNAPSHOT_LENGTH && a[0] >= 2
                && Double.isFinite(a[22]) ? Math.max(0.0, a[22]) : FAILURE_PRESSURE_PSIG;
        leakArea = a.length >= V2_SNAPSHOT_LENGTH && a[0] >= 2
                && Double.isFinite(a[23]) ? Math.clamp(a[23], 0.0, 1.0) : 0.0;
        steamKg = a.length >= SNAPSHOT_LENGTH && a[0] >= SNAPSHOT_VERSION ? nonNegative(a[24]) : 0.0;
        gasTemperatureK = a.length >= SNAPSHOT_LENGTH && a[0] >= SNAPSHOT_VERSION
                && Double.isFinite(a[25]) ? Math.clamp(a[25], 273.15, 650.0) : GAS_TEMPERATURE_K;
        wallTemperatureK = a.length >= SNAPSHOT_LENGTH && a[0] >= SNAPSHOT_VERSION
                && Double.isFinite(a[26]) ? Math.clamp(a[26], 273.15, 650.0) : GAS_TEMPERATURE_K;
        lastSprayFlowKgPerS = a.length >= SNAPSHOT_LENGTH && a[0] >= SNAPSHOT_VERSION
                ? nonNegative(a[27]) : 0.0;
        pendingSteamInputs = SteamInputs.NONE;
        pendingEvents.clear();
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private static double nonNegative(double v) {
        return Math.max(0.0, finite(v));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "Containment[H2 %.2f%%, O2 %.2f%%, %.1f psig%s%s, burns %d, released %.3f%%]",
                getHydrogenPercent(), getOxygenPercent(), getPressurePsig(),
                inertingCommanded ? " inerting" : "", isFailed() ? " FAILED/" + failureMode : "",
                burnCount, 100 * environmentalReleaseFraction);
    }
}
