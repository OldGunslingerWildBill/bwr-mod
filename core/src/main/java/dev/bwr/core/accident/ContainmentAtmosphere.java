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
 * <p>The drywell and wetwell have separate gas inventories and structural heat
 * storage. Downcomers carry a pressure-driven mixture to the suppression pool;
 * vacuum breakers admit reverse flow from the wetwell.
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
    /** Browns Ferry Mark I: 159,000 ft3 drywell, 119,000 ft3 wetwell free air. */
    public static final double DRYWELL_VOLUME_FRACTION = 159.0 / (159.0 + 119.0);
    /** NRC Mark I downcomers end about four feet below the pool surface. */
    public static final double DOWNCOMER_SUBMERGENCE_HEAD_PA = 1000.0 * 9.80665 * 1.2192;
    /** Effective discharge and vacuum-breaker time constants, model calibration. */
    public static final double DOWNCOMER_TIME_S = 12.0;
    public static final double VACUUM_BREAKER_TIME_S = 2.0;
    /** Effective condensing areas scale with free-volume surface area. */
    public static final double WALL_AREA_PER_M3_TWO_THIRDS = 6.0;
    public static final double WALL_CONDENSATION_W_PER_M2_K = 300.0;
    public static final double POOL_SURFACE_AREA_M2_AT_RATED = 700.0;
    public static final double POOL_SURFACE_TRANSFER_W_PER_M2_K = 600.0;

    /** Legacy initial gas temperature, K. */
    public static final double GAS_TEMPERATURE_K = 330.0;
    public static final double STEAM_MOL_PER_KG = 1000.0 / 18.01528;
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
    // The original fields above are the drywell node. Version 4 appends the
    // wetwell node rather than changing the stable legacy snapshot offsets.
    private double wetwellVolumeM3 = volumeM3 * (1.0 - DRYWELL_VOLUME_FRACTION);
    private double wetwellNitrogenMol;
    private double wetwellOxygenMol;
    private double wetwellHydrogenMol;
    private double wetwellOtherGasMol;
    private double wetwellSteamKg;
    private double wetwellGasTemperatureK = GAS_TEMPERATURE_K;
    private double wetwellWallTemperatureK = GAS_TEMPERATURE_K;
    private double wetwellAirborneFraction;
    private double peakDrywellPressurePsig;
    private double peakWetwellPressurePsig;
    private double lastDowncomerSteamKgPerS;
    private double lastPoolSurfaceCondensedKgPerS;
    private double lastPoolEvaporatedKgPerS;
    private boolean wetwellFlammableLatched;
    private double wetwellLastBurnClockSeconds = Double.NEGATIVE_INFINITY;
    private int wetwellBurnCount;
    private double wetwellHydrogenBurnedKg;
    private double wetwellLastBurnPeakPsig;
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
        wetwellNitrogenMol *= ratio;
        wetwellOxygenMol *= ratio;
        wetwellHydrogenMol *= ratio;
        wetwellOtherGasMol *= ratio;
        wetwellSteamKg *= ratio;
        wetwellVolumeM3 *= ratio;
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
        wetwellVolumeM3 = volumeM3 * (1.0 - DRYWELL_VOLUME_FRACTION);
        double total = ATMOSPHERIC_PA * drywellVolumeM3() / (GAS_CONSTANT * gasTemperatureK);
        oxygenMol = total * oxygenFraction;
        nitrogenMol = total - oxygenMol;
        hydrogenMol = 0.0;
        otherGasMol = 0.0;
        steamKg = 0.0;
        double wetTotal = ATMOSPHERIC_PA * wetwellVolumeM3 / (GAS_CONSTANT * wetwellGasTemperatureK);
        wetwellOxygenMol = wetTotal * oxygenFraction;
        wetwellNitrogenMol = wetTotal - wetwellOxygenMol;
        wetwellHydrogenMol = wetwellOtherGasMol = wetwellSteamKg = 0.0;
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
                              double sprayKgPerS, double sprayTemperatureK,
                              double wetwellSprayKgPerS, double wetwellSprayTemperatureK) {
        public SteamInputs(double drywellSteamKgPerS, double drywellEnthalpyKJPerKg,
                           double wetwellSteamKgPerS, double poolSurfaceTemperatureK,
                           double sprayKgPerS, double sprayTemperatureK) {
            this(drywellSteamKgPerS, drywellEnthalpyKJPerKg, wetwellSteamKgPerS,
                    poolSurfaceTemperatureK, sprayKgPerS, sprayTemperatureK, 0, 0);
        }
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
        recordPressurePeaks();
        exchangeVents(inputs.poolSurfaceTemperatureK() > 0.0, dtSeconds);
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
            double wetAir = wetwellOxygenMol + wetwellNitrogenMol;
            if (purgeActive && Math.abs(oxygenMol - targetOxygenMol) < PURGE_COMPLETE_TOLERANCE * air
                    && Math.abs(wetwellOxygenMol - targetOxygen * wetAir) < PURGE_COMPLETE_TOLERANCE * wetAir) {
                purgeActive = false;
            }
        }

        // Gas leaves through the vent, the failure, or design leakage. Every
        // species leaves in proportion, so hydrogen and airborne activity go
        // with it; only a pool-scrubbed vent holds most of the activity back.
        double pressurePa = total * GAS_CONSTANT * gasTemperatureK / drywellVolumeM3();
        double excessFraction = Math.max(0.0, (pressurePa - ATMOSPHERIC_PA) / pressurePa);
        double leaving = DESIGN_LEAK_FRACTION_PER_S * dtSeconds;
        double decontamination = 1.0;
        if (failed) {
            leaving = Math.max(leaving, excessFraction * (1.0 - Math.exp(-dtSeconds / FAILED_TIME_CONSTANT_S)));
        } else if (openBoundary) {
            leaving = Math.max(leaving, excessFraction *
                    (1.0 - Math.exp(-dtSeconds * leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S)));
        }
        removeProportionally(Math.min(1.0, Math.max(0.0, leaving)), decontamination);

        // A failed containment also breathes: convection and wind swap its gas
        // for outside air. This is what brings oxygen back to a hydrogen-filled
        // building, and it is the second half of the Fukushima failure mode.
        if (failed || openBoundary) {
            double exchangeRate = (failed ? 1.0 / (4.0 * FAILED_TIME_CONSTANT_S) : 0.0)
                    + leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S;
            double exchange = 1.0 - Math.exp(-dtSeconds * exchangeRate);
            double outside = ATMOSPHERIC_PA * drywellVolumeM3() / (GAS_CONSTANT * gasTemperatureK);
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
        if (clockSeconds - lastBurnClockSeconds < STANDING_FLAME_S && !drywellSteamInerted()
                && drywellOxygenFraction() >= OXYGEN_LIMIT_FRACTION && drywellHydrogenFraction() >= FLAMMABILITY_FRACTION) {
            double burned = Math.min(hydrogenMol - FLAMMABILITY_FRACTION * totalMol(), 2.0 * oxygenMol);
            if (burned > 0.0) {
                hydrogenMol -= burned;
                oxygenMol -= burned / 2.0;
                hydrogenBurnedKg += burned / HYDROGEN_MOL_PER_KG;
                lastBurnClockSeconds = clockSeconds;
            }
        }
        double xH2 = drywellHydrogenFraction();
        double xO2 = drywellOxygenFraction();
        boolean flammable = xH2 >= FLAMMABILITY_FRACTION && xO2 >= OXYGEN_LIMIT_FRACTION
                && !drywellSteamInerted();
        if (flammable && !flammableLatched) {
            pendingEvents.add(Event.HYDROGEN_FLAMMABLE);
        }
        flammableLatched = flammable;
        if (!drywellSteamInerted() && xO2 >= OXYGEN_LIMIT_FRACTION && xH2 >= IGNITION_FRACTION) {
            burn(xH2 >= DETONATION_FRACTION);
            lastBurnClockSeconds = clockSeconds;
        }

        stepWetwellGas(dtSeconds);
        double psig = Math.max(getDrywellPressurePsig(), getWetwellPressurePsig());
        recordPressurePeaks();
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
        double wetSpray = nonNegative(in.wetwellSprayKgPerS());
        lastSprayFlowKgPerS = spray + wetSpray;
        lastPoolSurfaceCondensedKgPerS = 0;
        lastPoolEvaporatedKgPerS = 0;
        lastDowncomerSteamKgPerS = 0;
        if (dry + wet + spray + wetSpray + steamKg + wetwellSteamKg <= 0
                && in.poolSurfaceTemperatureK() <= 0) return;

        double inletH = in.drywellEnthalpyKJPerKg() > 0 && Double.isFinite(in.drywellEnthalpyKJPerKg())
                ? in.drywellEnthalpyKJPerKg() : STEAM_REFERENCE_ENTHALPY_KJ_PER_KG;
        double inletK = Math.clamp(373.15 + (inletH - STEAM_REFERENCE_ENTHALPY_KJ_PER_KG)
                * 1000 / STEAM_HEAT_CAPACITY_J_PER_KG_K, 273.15, 650.0);
        double wetK = in.poolSurfaceTemperatureK() > 0 ? Math.max(373.15, in.poolSurfaceTemperatureK()) : 373.15;
        double dryCapacity = gasHeatCapacity(false);
        double wetCapacity = gasHeatCapacity(true);
        steamKg += dry * dt;
        wetwellSteamKg += wet * dt;
        gasTemperatureK = Math.clamp(gasTemperatureK + dry * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K
                * (inletK - gasTemperatureK) / Math.max(1, dryCapacity + dry * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K),
                273.15, 650.0);
        wetwellGasTemperatureK = Math.clamp(wetwellGasTemperatureK + wet * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K
                * (wetK - wetwellGasTemperatureK) / Math.max(1, wetCapacity + wet * dt * STEAM_HEAT_CAPACITY_J_PER_KG_K),
                273.15, 650.0);

        double dryWallCapacity = WALL_HEAT_CAPACITY_J_PER_K_M3 * drywellVolumeM3();
        double wetWallCapacity = WALL_HEAT_CAPACITY_J_PER_K_M3 * wetwellVolumeM3;
        double contact = 1 - Math.exp(-dt / WALL_HEAT_TRANSFER_TIME_S);
        double dryHeat = (gasTemperatureK - wallTemperatureK) * gasHeatCapacity(false) * contact;
        double wetHeat = (wetwellGasTemperatureK - wetwellWallTemperatureK) * gasHeatCapacity(true) * contact;
        gasTemperatureK -= dryHeat / gasHeatCapacity(false);
        wallTemperatureK += dryHeat / dryWallCapacity;
        wetwellGasTemperatureK -= wetHeat / gasHeatCapacity(true);
        wetwellWallTemperatureK += wetHeat / wetWallCapacity;

        double dryArea = WALL_AREA_PER_M3_TWO_THIRDS * Math.pow(drywellVolumeM3(), 2.0 / 3.0);
        double wetArea = WALL_AREA_PER_M3_TWO_THIRDS * Math.pow(wetwellVolumeM3, 2.0 / 3.0);
        double dryCondensed = surfaceCondensation(steamKg, gasTemperatureK, drywellVolumeM3(),
                wallTemperatureK, dryArea, WALL_CONDENSATION_W_PER_M2_K, dt);
        double wetCondensed = surfaceCondensation(wetwellSteamKg, wetwellGasTemperatureK, wetwellVolumeM3,
                wetwellWallTemperatureK, wetArea, WALL_CONDENSATION_W_PER_M2_K, dt);
        steamKg -= dryCondensed;
        wetwellSteamKg -= wetCondensed;
        wallTemperatureK += dryCondensed * STEAM_LATENT_HEAT_J_PER_KG / dryWallCapacity;
        wetwellWallTemperatureK += wetCondensed * STEAM_LATENT_HEAT_J_PER_KG / wetWallCapacity;

        if (in.poolSurfaceTemperatureK() > 0 && Double.isFinite(in.poolSurfaceTemperatureK())) {
            double poolK = Math.clamp(in.poolSurfaceTemperatureK(), 273.15, 500.0);
            double poolArea = POOL_SURFACE_AREA_M2_AT_RATED * Math.pow(volumeM3 / FREE_VOLUME_AT_RATED_M3, 2.0 / 3.0);
            double captured = surfaceCondensation(wetwellSteamKg, wetwellGasTemperatureK, wetwellVolumeM3,
                    poolK, poolArea, POOL_SURFACE_TRANSFER_W_PER_M2_K, dt);
            wetwellSteamKg -= captured;
            lastPoolSurfaceCondensedKgPerS = captured / dt;
            double partial = steamPartialPa(true);
            double saturation = saturationPressurePa(poolK);
            if (partial < saturation && poolK > 300) {
                double evaporated = Math.min((saturation - partial) * wetwellVolumeM3
                        / (GAS_CONSTANT * wetwellGasTemperatureK * STEAM_MOL_PER_KG),
                        poolArea * POOL_SURFACE_TRANSFER_W_PER_M2_K
                        * Math.max(0, poolK - saturationTemperatureK(partial)) * dt / STEAM_LATENT_HEAT_J_PER_KG);
                wetwellSteamKg += Math.max(0, evaporated);
                lastPoolEvaporatedKgPerS = Math.max(0, evaporated) / dt;
            }
        }

        if (spray > 0 && in.sprayTemperatureK() > 0 && Double.isFinite(in.sprayTemperatureK())) {
            double sprayK = Math.clamp(in.sprayTemperatureK(), 273.15, 450.0);
            double potential = Math.max(0, steamKg - saturatedSteamKg(sprayK, drywellVolumeM3(), gasTemperatureK));
            double captured = Math.min(potential, spray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K
                    * Math.max(0, Math.max(373.15, gasTemperatureK) - sprayK) / STEAM_LATENT_HEAT_J_PER_KG);
            steamKg -= captured;
            gasTemperatureK -= Math.min(Math.max(0, gasTemperatureK - sprayK),
                    spray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K
                    * Math.max(0, gasTemperatureK - sprayK) / gasHeatCapacity(false));
            double washed = airborneFraction * (1 - Math.exp(-SPRAY_DECONTAMINATION_PER_KG_M3
                    * spray * dt / drywellVolumeM3()));
            airborneFraction -= washed;
            depositedFraction += washed;
            plantContamination += washed;
        }
        if (wetSpray > 0 && in.wetwellSprayTemperatureK() > 0
                && Double.isFinite(in.wetwellSprayTemperatureK())) {
            double sprayK = Math.clamp(in.wetwellSprayTemperatureK(), 273.15, 450.0);
            double potential = Math.max(0, wetwellSteamKg
                    - saturatedSteamKg(sprayK, wetwellVolumeM3, wetwellGasTemperatureK));
            double captured = Math.min(potential, wetSpray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K
                    * Math.max(0, Math.max(373.15, wetwellGasTemperatureK) - sprayK)
                    / STEAM_LATENT_HEAT_J_PER_KG);
            wetwellSteamKg -= captured;
            wetwellGasTemperatureK -= Math.min(Math.max(0, wetwellGasTemperatureK - sprayK),
                    wetSpray * dt * SPRAY_WATER_HEAT_CAPACITY_J_PER_KG_K
                    * Math.max(0, wetwellGasTemperatureK - sprayK) / gasHeatCapacity(true));
            double washed = wetwellAirborneFraction * (1 - Math.exp(-SPRAY_DECONTAMINATION_PER_KG_M3
                    * wetSpray * dt / wetwellVolumeM3));
            wetwellAirborneFraction -= washed;
            depositedFraction += washed;
            plantContamination += washed;
        }
        wallTemperatureK -= (wallTemperatureK - AMBIENT_TEMPERATURE_K)
                * (1 - Math.exp(-AMBIENT_LOSS_W_PER_K_M3 * dt / WALL_HEAT_CAPACITY_J_PER_K_M3));
        wetwellWallTemperatureK -= (wetwellWallTemperatureK - AMBIENT_TEMPERATURE_K)
                * (1 - Math.exp(-AMBIENT_LOSS_W_PER_K_M3 * dt / WALL_HEAT_CAPACITY_J_PER_K_M3));
        gasTemperatureK = Math.clamp(gasTemperatureK, 273.15, 650.0);
        wetwellGasTemperatureK = Math.clamp(wetwellGasTemperatureK, 273.15, 650.0);
        wallTemperatureK = Math.clamp(wallTemperatureK, 273.15, 650.0);
        wetwellWallTemperatureK = Math.clamp(wetwellWallTemperatureK, 273.15, 650.0);
    }

    private static double surfaceCondensation(double steam, double gasK, double volume, double surfaceK,
                                              double area, double h, double dt) {
        if (steam <= 0) return 0;
        double partialPa = steam * STEAM_MOL_PER_KG * GAS_CONSTANT * gasK / volume;
        double excess = Math.max(0, steam - saturatedSteamKg(surfaceK, volume, gasK));
        double thermal = area * h * Math.max(0, saturationTemperatureK(partialPa) - surfaceK)
                * dt / STEAM_LATENT_HEAT_J_PER_KG;
        return Math.min(excess, thermal);
    }

    private static double saturationPressurePa(double temperatureK) {
        double c = Math.clamp(temperatureK - 273.15, 0, 250);
        return c <= 100 ? 611.21 * Math.exp((18.678 - c / 234.5) * c / (257.14 + c))
                : Saturation.pressurePsiaFromTemperatureCelsius(c) * PA_PER_PSI;
    }

    private static double saturationTemperatureK(double partialPa) {
        double low = 273.15, high = 523.15;
        for (int i = 0; i < 30; i++) {
            double middle = (low + high) * .5;
            if (saturationPressurePa(middle) < partialPa) low = middle; else high = middle;
        }
        return (low + high) * .5;
    }

    private static double saturatedSteamKg(double temperatureK, double volume, double gasK) {
        return saturationPressurePa(temperatureK) * volume
                / (GAS_CONSTANT * Math.max(273.15, gasK) * STEAM_MOL_PER_KG);
    }

    private double gasHeatCapacity(boolean wet) {
        double gas = wet ? wetwellDryMol() : dryMol();
        double steam = wet ? wetwellSteamKg : steamKg;
        return Math.max(1, gas * GAS_HEAT_CAPACITY_J_PER_MOL_K + steam * STEAM_MOL_PER_KG * 34);
    }

    private void exchangeVents(boolean poolLinked, double dt) {
        double dryPa = drywellPressurePa(), wetPa = wetwellPressurePa();
        if (dryPa > wetPa + DOWNCOMER_SUBMERGENCE_HEAD_PA) {
            double fraction = Math.clamp((dryPa - wetPa - DOWNCOMER_SUBMERGENCE_HEAD_PA) / dryPa
                    * (1 - Math.exp(-dt / DOWNCOMER_TIME_S)), 0, 1);
            double movingN2 = nitrogenMol * fraction, movingO2 = oxygenMol * fraction;
            double movingH2 = hydrogenMol * fraction, movingOther = otherGasMol * fraction;
            double movingSteam = steamKg * fraction;
            double movingActivity = airborneFraction * fraction;
            nitrogenMol -= movingN2; oxygenMol -= movingO2;
            hydrogenMol -= movingH2; otherGasMol -= movingOther;
            steamKg -= movingSteam; airborneFraction -= movingActivity;
            double wetCapacity = gasHeatCapacity(true);
            double transportedCapacity = (movingN2 + movingO2 + movingH2 + movingOther)
                    * GAS_HEAT_CAPACITY_J_PER_MOL_K;
            if (!poolLinked) transportedCapacity += movingSteam * STEAM_MOL_PER_KG * 34;
            wetwellGasTemperatureK = (wetwellGasTemperatureK * wetCapacity
                    + gasTemperatureK * transportedCapacity) / (wetCapacity + transportedCapacity);
            wetwellNitrogenMol += movingN2; wetwellOxygenMol += movingO2;
            wetwellHydrogenMol += movingH2; wetwellOtherGasMol += movingOther;
            if (poolLinked) {
                lastDowncomerSteamKgPerS = movingSteam / dt;
                // Aerosols following the submerged vents are pool-scrubbed.
                wetwellAirborneFraction += movingActivity / POOL_DECONTAMINATION_FACTOR;
                depositedFraction += movingActivity * (1 - 1 / POOL_DECONTAMINATION_FACTOR);
                plantContamination += movingActivity * (1 - 1 / POOL_DECONTAMINATION_FACTOR);
            } else {
                wetwellSteamKg += movingSteam;
                wetwellAirborneFraction += movingActivity;
            }
        } else if (wetPa > dryPa) {
            // Passive vacuum breakers admit wetwell gas to protect the drywell shell.
            double fraction = Math.clamp((wetPa - dryPa) / wetPa
                    * (1 - Math.exp(-dt / VACUUM_BREAKER_TIME_S)), 0, 1);
            double movingN2 = wetwellNitrogenMol * fraction, movingO2 = wetwellOxygenMol * fraction;
            double movingH2 = wetwellHydrogenMol * fraction, movingOther = wetwellOtherGasMol * fraction;
            double movingSteam = wetwellSteamKg * fraction;
            double movingActivity = wetwellAirborneFraction * fraction;
            wetwellNitrogenMol -= movingN2; wetwellOxygenMol -= movingO2;
            wetwellHydrogenMol -= movingH2; wetwellOtherGasMol -= movingOther;
            wetwellSteamKg -= movingSteam; wetwellAirborneFraction -= movingActivity;
            double dryCapacity = gasHeatCapacity(false);
            double transportedCapacity = (movingN2 + movingO2 + movingH2 + movingOther)
                    * GAS_HEAT_CAPACITY_J_PER_MOL_K + movingSteam * STEAM_MOL_PER_KG * 34;
            gasTemperatureK = (gasTemperatureK * dryCapacity
                    + wetwellGasTemperatureK * transportedCapacity) / (dryCapacity + transportedCapacity);
            nitrogenMol += movingN2; oxygenMol += movingO2;
            hydrogenMol += movingH2; otherGasMol += movingOther;
            steamKg += movingSteam; airborneFraction += movingActivity;
        }
    }

    private void stepWetwellGas(double dt) {
        double air = wetwellNitrogenMol + wetwellOxygenMol;
        boolean failed = failureMode != FailureMode.NONE;
        boolean openBoundary = leakArea > 0;
        if (!failed && !openBoundary && purgeActive && air > 0) {
            double target = (inertingCommanded ? INERTED_OXYGEN_FRACTION : AIR_OXYGEN_FRACTION) * air;
            double change = (target - wetwellOxygenMol) * (1 - Math.exp(-dt / INERTING_TIME_CONSTANT_S));
            wetwellOxygenMol += change;
            wetwellNitrogenMol -= change;
            if (Math.abs(wetwellOxygenMol - target) < PURGE_COMPLETE_TOLERANCE * air
                    && Math.abs(oxygenMol - (inertingCommanded ? INERTED_OXYGEN_FRACTION : AIR_OXYGEN_FRACTION)
                    * (oxygenMol + nitrogenMol)) < PURGE_COMPLETE_TOLERANCE * (oxygenMol + nitrogenMol))
                purgeActive = false;
        }
        double pressurePa = wetwellPressurePa();
        double excess = Math.max(0, (pressurePa - ATMOSPHERIC_PA) / pressurePa);
        double leaving = DESIGN_LEAK_FRACTION_PER_S * dt;
        double decontamination = 1;
        if (failed) leaving = Math.max(leaving, excess * (1 - Math.exp(-dt / FAILED_TIME_CONSTANT_S)));
        else if (openBoundary) leaving = Math.max(leaving,
                excess * (1 - Math.exp(-dt * leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S)));
        else if (ventOpen) {
            leaving = Math.max(leaving, excess * (1 - Math.exp(-dt / VENT_TIME_CONSTANT_S)));
            decontamination = POOL_DECONTAMINATION_FACTOR;
        }
        removeWetProportionally(Math.min(1, Math.max(0, leaving)), decontamination);
        if (failed || openBoundary) {
            double exchangeRate = (failed ? 1.0 / (4 * FAILED_TIME_CONSTANT_S) : 0)
                    + leakArea / OPEN_BOUNDARY_TIME_CONSTANT_S;
            double exchange = 1 - Math.exp(-dt * exchangeRate);
            double outside = ATMOSPHERIC_PA * wetwellVolumeM3 / (GAS_CONSTANT * wetwellGasTemperatureK);
            double replaced = Math.min(wetwellTotalMol(), outside) * exchange;
            removeWetProportionally(replaced / wetwellTotalMol(), 1);
            double deficit = Math.max(0, outside - (wetwellTotalMol() + replaced))
                    * (1 - Math.exp(-dt * Math.max(failed ? 1.0 / FAILED_TIME_CONSTANT_S : 0, exchangeRate)));
            wetwellOxygenMol += (replaced + deficit) * AIR_OXYGEN_FRACTION;
            wetwellNitrogenMol += (replaced + deficit) * (1 - AIR_OXYGEN_FRACTION);
        }
        double settled = wetwellAirborneFraction * (1 - Math.exp(-dt / DEPOSITION_TIME_CONSTANT_S));
        wetwellAirborneFraction -= settled;
        depositedFraction += settled;
        plantContamination += settled;
        double xH2 = wetwellHydrogenMol / wetwellTotalMol();
        double xO2 = wetwellOxygenMol / wetwellTotalMol();
        boolean steamInerted = wetwellSteamKg * STEAM_MOL_PER_KG / wetwellTotalMol() >= STEAM_INERTING_FRACTION;
        if (clockSeconds - wetwellLastBurnClockSeconds < STANDING_FLAME_S && !steamInerted
                && xO2 >= OXYGEN_LIMIT_FRACTION && xH2 >= FLAMMABILITY_FRACTION) {
            double burned = Math.min(wetwellHydrogenMol - FLAMMABILITY_FRACTION * wetwellTotalMol(),
                    2 * wetwellOxygenMol);
            if (burned > 0) {
                wetwellHydrogenMol -= burned; wetwellOxygenMol -= burned / 2;
                wetwellHydrogenBurnedKg += burned / HYDROGEN_MOL_PER_KG;
                wetwellLastBurnClockSeconds = clockSeconds;
            }
        }
        xH2 = wetwellHydrogenMol / wetwellTotalMol();
        xO2 = wetwellOxygenMol / wetwellTotalMol();
        boolean flammable = !steamInerted && xH2 >= FLAMMABILITY_FRACTION && xO2 >= OXYGEN_LIMIT_FRACTION;
        if (flammable && !wetwellFlammableLatched) pendingEvents.add(Event.HYDROGEN_FLAMMABLE);
        wetwellFlammableLatched = flammable;
        if (!steamInerted && xH2 >= IGNITION_FRACTION && xO2 >= OXYGEN_LIMIT_FRACTION) {
            double peakPsia = wetwellPressurePa() / PA_PER_PSI * (1 + AICC_PRESSURE_SLOPE * xH2);
            double burned = Math.min(wetwellHydrogenMol, 2 * wetwellOxygenMol);
            wetwellHydrogenMol -= burned; wetwellOxygenMol -= burned / 2;
            wetwellHydrogenBurnedKg += burned / HYDROGEN_MOL_PER_KG;
            wetwellBurnCount++;
            wetwellLastBurnClockSeconds = clockSeconds;
            wetwellLastBurnPeakPsig = peakPsia - ATMOSPHERIC_PA / PA_PER_PSI;
            peakWetwellPressurePsig = Math.max(peakWetwellPressurePsig, wetwellLastBurnPeakPsig);
            if (xH2 >= DETONATION_FRACTION) {
                pendingEvents.add(Event.HYDROGEN_DETONATION);
                if (failureMode == FailureMode.NONE) failureMode = FailureMode.DETONATION;
            } else {
                pendingEvents.add(Event.HYDROGEN_DEFLAGRATION);
                if (failureMode == FailureMode.NONE && wetwellLastBurnPeakPsig > failurePressurePsig) {
                    failureMode = FailureMode.OVERPRESSURE;
                    pendingEvents.add(Event.CONTAINMENT_OVERPRESSURE_FAILURE);
                }
            }
        }
    }

    private void removeWetProportionally(double fraction, double decontamination) {
        if (!(fraction > 0)) return;
        double keep = 1 - Math.min(1, fraction);
        wetwellNitrogenMol *= keep; wetwellOxygenMol *= keep;
        wetwellHydrogenMol *= keep; wetwellOtherGasMol *= keep; wetwellSteamKg *= keep;
        double escaped = wetwellAirborneFraction * (1 - keep);
        wetwellAirborneFraction -= escaped;
        double released = escaped / Math.max(1, decontamination);
        environmentalReleaseFraction += released;
        siteContamination += released;
        depositedFraction += escaped - released;
        plantContamination += escaped - released;
    }

    private double drywellVolumeM3() { return volumeM3 - wetwellVolumeM3; }
    private double drywellPressurePa() { return totalMol() * GAS_CONSTANT * gasTemperatureK / drywellVolumeM3(); }
    private double wetwellPressurePa() { return wetwellTotalMol() * GAS_CONSTANT * wetwellGasTemperatureK / wetwellVolumeM3; }
    private double wetwellDryMol() { return wetwellNitrogenMol + wetwellOxygenMol + wetwellHydrogenMol + wetwellOtherGasMol; }
    private double wetwellTotalMol() { return Math.max(1e-9, wetwellDryMol() + wetwellSteamKg * STEAM_MOL_PER_KG); }
    private double steamPartialPa(boolean wet) {
        return (wet ? wetwellSteamKg * wetwellGasTemperatureK / wetwellVolumeM3
                : steamKg * gasTemperatureK / drywellVolumeM3()) * STEAM_MOL_PER_KG * GAS_CONSTANT;
    }
    private double drywellHydrogenFraction() { return hydrogenMol / totalMol(); }
    private double drywellOxygenFraction() { return oxygenMol / totalMol(); }
    private boolean drywellSteamInerted() { return steamKg * STEAM_MOL_PER_KG / totalMol() >= STEAM_INERTING_FRACTION; }
    private void recordPressurePeaks() {
        double dry = getDrywellPressurePsig(), wet = getWetwellPressurePsig();
        peakDrywellPressurePsig = Math.max(peakDrywellPressurePsig, dry);
        peakWetwellPressurePsig = Math.max(peakWetwellPressurePsig, wet);
        peakPressurePsig = Math.max(peakPressurePsig, Math.max(peakDrywellPressurePsig, peakWetwellPressurePsig));
        if (failureMode == FailureMode.NONE && Math.max(dry, wet) > failurePressurePsig) {
            failureMode = FailureMode.OVERPRESSURE;
            pendingEvents.add(Event.CONTAINMENT_OVERPRESSURE_FAILURE);
        }
    }

    private void burn(boolean detonation) {
        double before = drywellPressurePa() / PA_PER_PSI;
        double xH2 = drywellHydrogenFraction();
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
        return (hydrogenMol + wetwellHydrogenMol) / (totalMol() + wetwellTotalMol());
    }

    /** Oxygen mole fraction, 0..1. */
    public double oxygenFraction() {
        return (oxygenMol + wetwellOxygenMol) / (totalMol() + wetwellTotalMol());
    }

    public double getHydrogenPercent() {
        return 100.0 * hydrogenFraction();
    }
    public double getDrywellHydrogenPercent() { return 100.0 * drywellHydrogenFraction(); }
    public double getWetwellHydrogenPercent() { return 100.0 * wetwellHydrogenMol / wetwellTotalMol(); }

    public double getOxygenPercent() {
        return 100.0 * oxygenFraction();
    }
    public double getDrywellOxygenPercent() { return 100.0 * drywellOxygenFraction(); }
    public double getWetwellOxygenPercent() { return 100.0 * wetwellOxygenMol / wetwellTotalMol(); }

    public double getHydrogenKg() {
        return (hydrogenMol + wetwellHydrogenMol) / HYDROGEN_MOL_PER_KG;
    }

    public double getSteamKg() { return steamKg + wetwellSteamKg; }
    public double getDrywellSteamKg() { return steamKg; }
    public double getWetwellSteamKg() { return wetwellSteamKg; }
    public double getSteamPercent() { return 100.0 * getSteamKg() * STEAM_MOL_PER_KG / (totalMol() + wetwellTotalMol()); }
    public double getGasTemperatureK() { return (gasTemperatureK * drywellVolumeM3() + wetwellGasTemperatureK * wetwellVolumeM3) / volumeM3; }
    public double getGasTemperatureC() { return getGasTemperatureK() - 273.15; }
    public double getDrywellGasTemperatureC() { return gasTemperatureK - 273.15; }
    public double getWetwellGasTemperatureC() { return wetwellGasTemperatureK - 273.15; }
    public double getWallTemperatureK() { return wallTemperatureK; }
    public double getWetwellGasTemperatureK() { return wetwellGasTemperatureK; }
    public double getWetwellWallTemperatureK() { return wetwellWallTemperatureK; }
    public double getLastSprayFlowKgPerS() { return lastSprayFlowKgPerS; }
    public double getLastDowncomerSteamKgPerS() { return lastDowncomerSteamKgPerS; }
    public double getLastPoolSurfaceCondensedKgPerS() { return lastPoolSurfaceCondensedKgPerS; }
    public double getLastPoolEvaporatedKgPerS() { return lastPoolEvaporatedKgPerS; }
    public double getSteamPartialPressurePsia() {
        return Math.max(steamPartialPa(false), steamPartialPa(true)) / PA_PER_PSI;
    }
    public double getDrywellSteamPartialPressurePsia() { return steamPartialPa(false) / PA_PER_PSI; }
    public double getWetwellSteamPartialPressurePsia() { return steamPartialPa(true) / PA_PER_PSI; }
    public boolean isSteamInerted() { return getSteamPercent() >= 100.0 * STEAM_INERTING_FRACTION; }

    public double getPressurePsia() {
        return Math.max(drywellPressurePa(), wetwellPressurePa()) / PA_PER_PSI;
    }

    public double getPressurePsig() {
        return getPressurePsia() - ATMOSPHERIC_PA / PA_PER_PSI;
    }

    public int getPressureNodeCount() { return 2; }
    public double getDrywellPressurePsig() { return drywellPressurePa() / PA_PER_PSI - ATMOSPHERIC_PA / PA_PER_PSI; }
    public double getWetwellPressurePsig() { return wetwellPressurePa() / PA_PER_PSI - ATMOSPHERIC_PA / PA_PER_PSI; }
    public double getPeakDrywellPressurePsig() { return peakDrywellPressurePsig; }
    public double getPeakWetwellPressurePsig() { return peakWetwellPressurePsig; }
    public double getDrywellVolumeM3() { return drywellVolumeM3(); }
    public double getWetwellVolumeM3() { return wetwellVolumeM3; }

    public double getPeakPressurePsig() {
        return peakPressurePsig;
    }

    public double getLastBurnPeakPressurePsig() {
        return Math.max(lastBurnPeakPsig, wetwellLastBurnPeakPsig);
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
        return burnCount + wetwellBurnCount;
    }

    public double getHydrogenBurnedKg() {
        return hydrogenBurnedKg + wetwellHydrogenBurnedKg;
    }

    /** Fission products airborne in containment, fraction of core inventory. */
    public double getAirborneFraction() {
        return airborneFraction + wetwellAirborneFraction;
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
        return getAirborneFraction() + plantContamination;
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

    public static final int SNAPSHOT_VERSION = 4;
    public static final int LEGACY_SNAPSHOT_LENGTH = 22;
    public static final int V2_SNAPSHOT_LENGTH = 24;
    public static final int V3_SNAPSHOT_LENGTH = 28;
    public static final int SNAPSHOT_LENGTH = 47;

    public double[] toArray() {
        double[] a = new double[SNAPSHOT_LENGTH];
        a[0] = SNAPSHOT_VERSION;
        a[1] = volumeM3;
        a[2] = nitrogenMol + wetwellNitrogenMol;
        a[3] = oxygenMol + wetwellOxygenMol;
        a[4] = hydrogenMol + wetwellHydrogenMol;
        a[5] = otherGasMol + wetwellOtherGasMol;
        a[6] = inertingCommanded ? 1 : 0;
        a[7] = ventOpen ? 1 : 0;
        a[8] = failureMode.ordinal();
        a[9] = airborneFraction + wetwellAirborneFraction;
        a[10] = depositedFraction;
        a[11] = environmentalReleaseFraction;
        a[12] = getBurnCount();
        a[13] = getHydrogenBurnedKg();
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
        a[24] = getSteamKg();
        a[25] = gasTemperatureK;
        a[26] = wallTemperatureK;
        a[27] = lastSprayFlowKgPerS;
        a[28] = wetwellVolumeM3;
        a[29] = wetwellNitrogenMol;
        a[30] = wetwellOxygenMol;
        a[31] = wetwellHydrogenMol;
        a[32] = wetwellOtherGasMol;
        a[33] = wetwellSteamKg;
        a[34] = wetwellGasTemperatureK;
        a[35] = wetwellWallTemperatureK;
        a[36] = wetwellAirborneFraction;
        a[37] = peakDrywellPressurePsig;
        a[38] = peakWetwellPressurePsig;
        a[39] = lastDowncomerSteamKgPerS;
        a[40] = lastPoolSurfaceCondensedKgPerS;
        a[41] = lastPoolEvaporatedKgPerS;
        a[42] = wetwellFlammableLatched ? 1 : 0;
        a[43] = Double.isFinite(wetwellLastBurnClockSeconds) ? wetwellLastBurnClockSeconds : -1.0e12;
        a[44] = wetwellBurnCount;
        a[45] = wetwellHydrogenBurnedKg;
        a[46] = wetwellLastBurnPeakPsig;
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
        steamKg = a.length >= V3_SNAPSHOT_LENGTH && a[0] >= 3 ? nonNegative(a[24]) : 0.0;
        gasTemperatureK = a.length >= V3_SNAPSHOT_LENGTH && a[0] >= 3
                && Double.isFinite(a[25]) ? Math.clamp(a[25], 273.15, 650.0) : GAS_TEMPERATURE_K;
        wallTemperatureK = a.length >= V3_SNAPSHOT_LENGTH && a[0] >= 3
                && Double.isFinite(a[26]) ? Math.clamp(a[26], 273.15, 650.0) : GAS_TEMPERATURE_K;
        lastSprayFlowKgPerS = a.length >= V3_SNAPSHOT_LENGTH && a[0] >= 3
                ? nonNegative(a[27]) : 0.0;
        if (a.length >= SNAPSHOT_LENGTH && a[0] >= SNAPSHOT_VERSION) {
            wetwellVolumeM3 = Math.clamp(finite(a[28]), 1, volumeM3 - 1);
            wetwellNitrogenMol = Math.min(nitrogenMol, nonNegative(a[29])); nitrogenMol -= wetwellNitrogenMol;
            wetwellOxygenMol = Math.min(oxygenMol, nonNegative(a[30])); oxygenMol -= wetwellOxygenMol;
            wetwellHydrogenMol = Math.min(hydrogenMol, nonNegative(a[31])); hydrogenMol -= wetwellHydrogenMol;
            wetwellOtherGasMol = Math.min(otherGasMol, nonNegative(a[32])); otherGasMol -= wetwellOtherGasMol;
            wetwellSteamKg = Math.min(steamKg, nonNegative(a[33])); steamKg -= wetwellSteamKg;
            wetwellGasTemperatureK = Double.isFinite(a[34]) ? Math.clamp(a[34], 273.15, 650) : GAS_TEMPERATURE_K;
            wetwellWallTemperatureK = Double.isFinite(a[35]) ? Math.clamp(a[35], 273.15, 650) : GAS_TEMPERATURE_K;
            wetwellAirborneFraction = Math.min(airborneFraction, nonNegative(a[36])); airborneFraction -= wetwellAirborneFraction;
            peakDrywellPressurePsig = finite(a[37]); peakWetwellPressurePsig = finite(a[38]);
            lastDowncomerSteamKgPerS = nonNegative(a[39]);
            lastPoolSurfaceCondensedKgPerS = nonNegative(a[40]);
            lastPoolEvaporatedKgPerS = nonNegative(a[41]);
            wetwellFlammableLatched = a[42] != 0;
            wetwellLastBurnClockSeconds = Double.isFinite(a[43]) ? a[43] : Double.NEGATIVE_INFINITY;
            wetwellBurnCount = (int)Math.max(0, Math.round(finite(a[44]))); burnCount -= wetwellBurnCount;
            wetwellHydrogenBurnedKg = Math.min(hydrogenBurnedKg, nonNegative(a[45]));
            hydrogenBurnedKg -= wetwellHydrogenBurnedKg;
            wetwellLastBurnPeakPsig = finite(a[46]);
        } else {
            // V1-V3 had one well-mixed atmosphere; preserve its pressure and
            // composition by apportioning each inventory by the cited volumes.
            wetwellVolumeM3 = volumeM3 * (1 - DRYWELL_VOLUME_FRACTION);
            double wetFraction = wetwellVolumeM3 / volumeM3;
            wetwellNitrogenMol = nitrogenMol * wetFraction; nitrogenMol -= wetwellNitrogenMol;
            wetwellOxygenMol = oxygenMol * wetFraction; oxygenMol -= wetwellOxygenMol;
            wetwellHydrogenMol = hydrogenMol * wetFraction; hydrogenMol -= wetwellHydrogenMol;
            wetwellOtherGasMol = otherGasMol * wetFraction; otherGasMol -= wetwellOtherGasMol;
            wetwellSteamKg = steamKg * wetFraction; steamKg -= wetwellSteamKg;
            wetwellGasTemperatureK = gasTemperatureK; wetwellWallTemperatureK = wallTemperatureK;
            wetwellAirborneFraction = airborneFraction * wetFraction; airborneFraction -= wetwellAirborneFraction;
            peakDrywellPressurePsig = peakWetwellPressurePsig = peakPressurePsig;
            wetwellBurnCount = 0; wetwellHydrogenBurnedKg = 0; wetwellLastBurnPeakPsig = 0;
            wetwellFlammableLatched = false; wetwellLastBurnClockSeconds = Double.NEGATIVE_INFINITY;
            lastDowncomerSteamKgPerS = lastPoolSurfaceCondensedKgPerS = lastPoolEvaporatedKgPerS = 0;
        }
        if (nitrogenMol + oxygenMol + wetwellNitrogenMol + wetwellOxygenMol <= 0) fillWithAir(AIR_OXYGEN_FRACTION);
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
