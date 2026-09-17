package dev.bwr.core.eccs;

/**
 * The nameplate of any centrifugal pump in the plant, as {@link EccsPump} needs
 * to see it.
 *
 * <h2>Why this interface exists</h2>
 * {@link EccsPump} was written for emergency core cooling and named for it, but
 * nothing in it is about emergency core cooling. It is a shaft with a
 * head-flow curve on one end and either a motor or a steam turbine on the
 * other: give it a pressure to push against and a power supply and it tells you
 * how much water moves. A reactor feed pump is the same machine with different
 * numbers on the plate.
 *
 * <p>So the pump model takes this interface rather than {@link EccsDesign}, and
 * {@code dev.bwr.core.feedwater.FeedwaterDesign} implements it too. There is one
 * pump model in this codebase and there is meant to go on being one: two copies
 * would drift, and the second copy is always the one that misses the fix.
 *
 * <p>The members here are exactly what {@code EccsPump} reads and no more.
 * Everything that is genuinely about emergency cooling — the delivery path, the
 * boron rate, whether the loop can be lined up to cool the pool — stays on
 * {@link EccsDesign} where it belongs.
 *
 * <p>Pure Java, no Minecraft imports.
 */
public interface PumpDesign {

    /**
     * What turns the shaft. This is the single most consequential property a
     * pump has, in this mod and in a real plant.
     */
    enum Drive {
        /**
         * A steam turbine fed from the reactor itself. Needs no electrical
         * supply of any kind, consumes steam, and loses power as the vessel
         * depressurises.
         */
        STEAM_TURBINE,
        /**
         * An electric motor. Simple, controllable, indifferent to reactor
         * pressure — and completely dead without a bus behind it.
         */
        ELECTRIC_MOTOR
    }

    /** Litres, and therefore kilograms of cold water, per US gallon. */
    double KG_PER_GALLON = 3.785411784;

    /** Convert a pump rating in US gallons per minute to kg/s of cold water. */
    static double kgPerSecondFromGpm(double gpm) {
        return gpm * KG_PER_GALLON / 60.0;
    }

    /**
     * Hydraulic power delivered by moving a mass flow of cold water across a
     * pressure difference, watts. {@code P = Q_volumetric * dp}.
     */
    static double hydraulicPowerWatts(double flowKgPerS, double differentialPsi) {
        if (!(flowKgPerS > 0.0) || !(differentialPsi > 0.0)) {
            return 0.0;
        }
        final double coldWaterDensityKgPerM3 = 1000.0;
        return (flowKgPerS / coldWaterDensityKgPerM3) * differentialPsi * SteamTurbineDrive.PA_PER_PSI;
    }

    /** Lowercase identifier, unique within its own catalogue. */
    String id();

    /** Name as a human would say it. */
    String displayName();

    /** Motor or turbine. */
    Drive drive();

    /** The head-flow characteristic, or null for a positive displacement pump. */
    PumpCurve curve();

    /** True for a positive displacement pump, whose flow does not care about head. */
    boolean isConstantDisplacement();

    /** Rated flow, kg/s — the curve's duty point, or the displacement rate. */
    double ratedFlowKgPerS();

    /**
     * The differential pressure beyond which no water is delivered, psi. For a
     * centrifugal pump this is the shutoff head; for a positive displacement
     * pump it is the discharge relief setting.
     */
    double maximumDischargePsi();

    /** Hydraulic efficiency of the pump itself, 0 to 1. */
    double pumpEfficiency();

    /** Shaft rating of the motor, watts. Zero on a turbine-driven machine. */
    double motorRatingWatts();

    /** Largest steam flow the turbine's admission valve can pass, kg/s. */
    double maximumSteamKgPerS();

    /** Isentropic efficiency of the turbine drive, 0 to 1. Zero on a motor. */
    double turbineEfficiency();

    /**
     * Power the drive burns spinning itself, as a fraction of the shaft power
     * the pump needs at its rated duty point. This is what eventually stops a
     * turbine-driven machine on a depressurising vessel, with no cutoff pressure
     * written down anywhere.
     */
    double windageFractionOfRatedShaft();

    /** Time from a start command to essentially rated speed, seconds. */
    double startupSeconds();

    /** Time to coast to a stop after a stop command, seconds. */
    double coastdownSeconds();

    /** Boron added at full flow, ppm per minute. Zero on everything but SLC. */
    double boronPpmPerMinuteAtRatedFlow();

    /** Shaft power the pump needs at its rated duty point, watts. */
    double ratedShaftPowerWatts();
}
