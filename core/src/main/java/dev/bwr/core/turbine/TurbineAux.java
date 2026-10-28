package dev.bwr.core.turbine;

/**
 * The physics of a turbine-generator train's auxiliaries (TURBINE-AUX.md): the lube oil system and the bearings, the rotor
 * and its bow on the turning gear, and the generator's winding and its cooling. Pure functions of numbers; the
 * mod keeps the state and calls them once a tick. Every constant is game calibration, not a manufacturer curve.
 *
 * <p>Nothing here decides anything for the player. A pump, the gear, and the cooling pumps run when the player
 * turns them on; this class only says what the hardware does while they run or do not.
 */
public final class TurbineAux {
    private TurbineAux() {}

    public static final double AMBIENT_C = 25;

    // ---------------------------------------------------------------- lube oil

    /** Litres one Main Oil Tank holds. */
    public static final double OIL_TANK_L = 20_000;
    /** Header pressure when the pumps deliver exactly the flow the bearings need, psig. */
    public static final double OIL_PSIG_NOMINAL = 25;
    /** Each pump's delivery as a fraction of the flow the bearings need at rated speed. */
    public static final double AC_PUMP_FLOW = 0.70, DC_PUMP_FLOW = 0.45;
    /** The shaft-driven pump reaches its full delivery at this fraction of rated speed. */
    public static final double SHAFT_PUMP_FULL_SPEED = 0.90;
    /** Motor ratings, W (converted to FE/t by the mod). */
    public static final double AC_PUMP_W = 75_000, DC_PUMP_W = 30_000, GEAR_MOTOR_W = 15_000, STATOR_PUMPS_W = 90_000;

    /** The shaft-driven main oil pump's delivery: none at standstill, rising with the square of speed, full near rated. */
    public static double shaftPumpFlow(double rpm) {
        double s = Math.max(0, rpm) / ShaftDynamics.RATED_RPM / SHAFT_PUMP_FULL_SPEED;
        return Math.min(1, s * s);
    }

    /** The oil flow the bearings need, as a fraction of the rated need: a floor at standstill, all of it at rated speed. */
    public static double flowNeed(double rpm) {
        return 0.4 + 0.6 * Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
    }

    /** Header pressure from the sum of the pumps' delivery. */
    public static double oilPsig(double totalFlow) {
        return OIL_PSIG_NOMINAL * Math.min(1.2, Math.max(0, totalFlow));
    }

    /** Oil film quality, 0 (metal on metal) to 1 (a full film), from the delivered flow against what the speed needs. */
    public static double film(double totalFlow, double rpm) {
        return Math.max(0, Math.min(1, Math.max(0, totalFlow) / flowNeed(rpm)));
    }

    /** What a pump can deliver of an oil tank that is running low: nothing from an empty tank, full above 5 %. */
    public static double suction(double oilLitres) {
        return Math.max(0, Math.min(1, oilLitres / (0.05 * OIL_TANK_L)));
    }

    public static final double BEARING_TAU_S = 90, BEARING_RISE_RATED_K = 28;
    public static final double BEARING_DAMAGE_C = 115;

    /** The temperature a bearing settles at: oil temperature plus the friction rise, which a missing film multiplies. */
    public static double bearingTargetC(double film, double oilC, double rpm) {
        double s = Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
        double starved = 1 - Math.max(0, Math.min(1, film));
        double reference = film * oilC + starved * AMBIENT_C;
        return Math.min(600, reference + BEARING_RISE_RATED_K * Math.pow(s, 1.2) * (1 + 60 * starved * starved));
    }

    public static double bearingStepC(double bearingC, double targetC, double dt) {
        return bearingC + (targetC - bearingC) * (1 - Math.exp(-dt / BEARING_TAU_S));
    }

    /** Damage a bearing adds in dt seconds: nothing below the babbitt softening temperature, 1.2 %/s at 60 K above it. */
    public static double bearingDamage(double bearingC, double dt) {
        return 0.012 * Math.max(0, Math.min(3, (bearingC - BEARING_DAMAGE_C) / 60)) * dt;
    }

    /** Heat the bearings put into the oil, kW. */
    public static double oilHeatKw(double rpm, double film) {
        double s = Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
        return 3000 * s * s * Math.max(0, Math.min(1, film));
    }

    public static final double OIL_KJ_PER_KG_K = 2.0, OIL_KG_PER_L = 0.87, OIL_AMBIENT_KW_PER_K = 15;
    public static final double OIL_COOLER_UA_KW_PER_K = 150, OIL_COOLER_WATER_KG_PER_S = 20;

    /** The oil's new temperature after dt seconds of bearing heat, cooler duty and loss to the hall. A floor of 1,000 L of thermal mass. */
    public static double oilStepC(double oilC, double oilLitres, double heatKw, double coolerKw, double dt) {
        double kjPerK = Math.max(1000, oilLitres) * OIL_KG_PER_L * OIL_KJ_PER_KG_K;
        double net = heatKw - coolerKw - OIL_AMBIENT_KW_PER_K * (oilC - AMBIENT_C);
        return oilC + net * dt / kjPerK;
    }

    public static final double OIL_LEAK_L_PER_S = 0.9;

    /** Oil lost in dt seconds: with the pressure the pumps hold, and doubling with every 15 K of oil above 50 C (it oxidises and thins). */
    public static double oilLossLitres(double psig, double oilC, double dt) {
        double pressureFraction = Math.min(1.2, Math.max(0, psig) / OIL_PSIG_NOMINAL);
        double heat = Math.pow(2, Math.max(0, Math.min(60, oilC - 50)) / 15);
        return OIL_LEAK_L_PER_S * pressureFraction * heat * dt;
    }

    // ------------------------------------------------ lube oil conditioning: the filter housing and its cartridge

    /**
     * What a clean cartridge in service leaves of the oil's loss: a tenth. Real lube oil is not consumed, it is degraded (water,
     * particles, oxidation products) and then replaced; a full-flow filter with a side-stream conditioner holds it in service for
     * years instead of months, and the game's "loss" is that degradation plus the small leak, so a tenth is the ten-fold life.
     */
    public static final double OIL_FILTER_FACTOR = 0.1;
    /** Seconds a cartridge lasts at the rated header pressure and 50 C oil, then it is full. Hot oil and pressure load it faster. */
    public static final double FILTER_LIFE_S = 21_600;
    /** Differential pressure across the cartridge, psi: clean, full (the bypass valve opens there), and the reading at which a change is due. */
    public static final double FILTER_DP_CLEAN_PSI = 3, FILTER_DP_FULL_PSI = 20, FILTER_DP_CHANGE_PSI = 12;

    /** Differential pressure across a cartridge holding {@code load} (0 clean, 1 full) of its dirt: flat when clean, steeper as it fills. */
    public static double filterDpPsi(double load) {
        double l = Math.max(0, Math.min(1, load));
        return FILTER_DP_CLEAN_PSI + (FILTER_DP_FULL_PSI - FILTER_DP_CLEAN_PSI) * l * l;
    }

    /** A full cartridge cannot pass the flow: the bypass valve opens and the oil goes round it. */
    public static boolean filterBypassOpen(double load) {
        return load >= 1;
    }

    /** The share of the unfiltered loss that remains: a tenth with a cartridge in service, all of it with none or with the bypass open. */
    public static double filterFactor(boolean cartridge, double load) {
        return cartridge && !filterBypassOpen(load) ? OIL_FILTER_FACTOR : 1;
    }

    /** The cartridge's new load after dt seconds: it fills with the pressure the pumps hold and faster with hot oil, as the oil's loss does. */
    public static double filterLoadStep(double load, double psig, double oilC, double dt) {
        double pressureFraction = Math.min(1.2, Math.max(0, psig) / OIL_PSIG_NOMINAL);
        double heat = Math.pow(2, Math.max(0, Math.min(60, oilC - 50)) / 15);
        return Math.min(1, Math.max(0, load) + pressureFraction * heat * dt / FILTER_LIFE_S);
    }

    /** Seconds until a cartridge holding {@code load} is full if the header pressure and oil temperature stay as they are; infinite with no flow. */
    public static double filterSecondsLeft(double load, double psig, double oilC) {
        double perSecond = filterLoadStep(0, psig, oilC, 1);
        return perSecond > 0 ? (1 - Math.max(0, Math.min(1, load))) / perSecond : Double.POSITIVE_INFINITY;
    }

    /** Oil lost in dt seconds through a filter that leaves {@code factor} of the loss ({@link #filterFactor}). */
    public static double oilLossLitres(double psig, double oilC, double dt, double factor) {
        return oilLossLitres(psig, oilC, dt) * factor;
    }

    // ----------------------------------------------------- shell-and-tube coolers

    public static final double WATER_KJ_PER_KG_K = 4.186;

    /** Heat a cooler takes from a process at {@code processC} through {@code ua} kW/K into water entering at {@code waterInC}, kW. Zero with no water. */
    public static double coolerKw(double processC, double ua, double waterInC, double waterKgPerS) {
        if (!(waterKgPerS > 0) || processC <= waterInC) return 0;
        double capacity = waterKgPerS * WATER_KJ_PER_KG_K;
        return (1 - Math.exp(-ua / capacity)) * capacity * (processC - waterInC);
    }

    // ------------------------------------------------------ rotor, bow, turning gear

    /** The gear's jaw clutch overruns above this speed, so it can neither be engaged nor drive the shaft above it, rpm. */
    public static final double GEAR_MAX_RPM = 10;
    /** The speed the gear rolls the shaft at, rpm. */
    public static final double GEAR_RPM = 3;
    /** Below this speed the rotor is for practical purposes at rest and bows if it is hot, rpm. */
    public static final double STANDSTILL_RPM = 1;
    public static final double ROTOR_HEAT_TAU_S = 300, ROTOR_COOL_TAU_S = 14_400;
    public static final double BOW_HOT_C = 120, BOW_FULL_S = 900, BOW_RECOVER_S = 1800;
    /** Vibration velocity above which a section starts to take damage, mm/s. */
    public static final double VIBRATION_DAMAGE_MM_S = 7.1;

    /** The speed the gear leaves the shaft at: it takes a shaft at or below its overrun speed down to its roll speed and never lets it stop. */
    public static double gearSpeed(double rpm) {
        return rpm <= GEAR_MAX_RPM ? GEAR_RPM : rpm;
    }

    /** Rotor metal temperature: soaks toward the steam temperature while steam flows, then cools toward the hall. NaN steam means none. */
    public static double rotorStepC(double rotorC, double steamC, double dt) {
        boolean heating = !Double.isNaN(steamC) && steamC > rotorC;
        double target = Double.isNaN(steamC) ? AMBIENT_C : Math.max(steamC, AMBIENT_C);
        double tau = heating ? ROTOR_HEAT_TAU_S : (Double.isNaN(steamC) ? ROTOR_COOL_TAU_S : ROTOR_HEAT_TAU_S);
        return rotorC + (target - rotorC) * (1 - Math.exp(-dt / tau));
    }

    /**
     * The rotor bow (0 straight, 1 fully bowed): a hot rotor at standstill bows at a rate that grows with its temperature above
     * {@value #BOW_HOT_C} C, and one the gear is rolling straightens out over {@value #BOW_RECOVER_S} s.
     */
    public static double bowStep(double bow, double rotorC, double rpm, boolean gearRolling, double dt) {
        if (rpm < STANDSTILL_RPM && rotorC > BOW_HOT_C) {
            double rate = Math.min(1.5, (rotorC - BOW_HOT_C) / 150) / BOW_FULL_S;
            return Math.min(1, bow + rate * dt);
        }
        if (gearRolling && rpm >= STANDSTILL_RPM && rpm <= GEAR_MAX_RPM) return Math.max(0, bow - dt / BOW_RECOVER_S);
        return bow;
    }

    /** Shaft vibration, mm/s: a healthy rotor's rises with speed; a bow adds a peak through the critical speed band near 35 % of rated. */
    public static double vibrationMmPerS(double bow, double rpm) {
        double s = Math.max(0, rpm) / ShaftDynamics.RATED_RPM;
        if (s <= 0) return 0;
        double band = 0.25 + 0.75 * Math.exp(-Math.pow((s - 0.35) / 0.12, 2));
        double turning = Math.min(1, s / 0.03);
        return 1.5 * Math.min(1.5, s) + 14 * Math.max(0, Math.min(1, bow)) * band * turning;
    }

    /** Damage vibration adds in dt seconds: nothing under its onset level, 0.6 %/s for every 7 mm/s over it. */
    public static double vibrationDamage(double mmPerS, double dt) {
        return 0.006 * Math.max(0, mmPerS - VIBRATION_DAMAGE_MM_S) / 7 * dt;
    }

    // ------------------------------------------------- generator winding and cooling

    public static final double WINDING_LIMIT_C = 130;
    public static final double WINDING_LOSS_RATED_KW = 22_000, FIELD_LOSS_RATED_KW = 1_500;
    public static final double WINDING_KJ_PER_K = 33_000, COOLANT_KJ_PER_K = 80_000;
    public static final double WINDING_UA_FULL_KW_PER_K = 340, WINDING_UA_NATURAL_KW_PER_K = 40;
    public static final double GEN_COOLER_UA_KW_PER_K = 800, GEN_COOLER_WATER_KG_PER_S = 500;
    public static final double STATOR_FLOW_TAU_S = 3;
    public static final double H2_FULL_KG = 20, H2_FULL_PSIG = 30, H2_CYLINDER_KG = 5;
    public static final double H2_LEAK_KG_PER_S = 0.1 / 60;

    /** First-order approach of a fraction (the stator water flow) to its target. */
    public static double approach(double value, double target, double tauS, double dt) {
        return value + (target - value) * (1 - Math.exp(-dt / tauS));
    }

    /** Heat the winding, core and field put into the cooling, kW: the winding loss goes with the square of the load. */
    public static double windingLossKw(double loadFraction, double rpm) {
        double load = Math.max(0, Math.min(1.5, loadFraction));
        double s = Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
        return WINDING_LOSS_RATED_KW * load * load + FIELD_LOSS_RATED_KW * s * s;
    }

    /** Hydrogen pressure from the gas in the casing, psig. */
    public static double h2Psig(double kg) {
        return H2_FULL_PSIG * Math.max(0, kg) / H2_FULL_KG;
    }

    /** The share of the full cooling that is there: stator water 45 %, the casing gas 55 % (hydrogen at full pressure, air at 25 % of it). */
    public static double coolingFraction(double statorFlowFraction, double h2Psig) {
        double gas = 0.25 + 0.75 * Math.max(0, Math.min(1, h2Psig / H2_FULL_PSIG));
        return 0.45 * Math.max(0, Math.min(1, statorFlowFraction)) + 0.55 * gas;
    }

    /** Heat from the winding into the coolant, kW. */
    public static double windingToCoolantKw(double windingC, double coolantC, double coolingFraction) {
        return WINDING_UA_FULL_KW_PER_K * Math.max(0, Math.min(1, coolingFraction)) * (windingC - coolantC);
    }

    public static double windingStepC(double windingC, double lossKw, double toCoolantKw, double dt) {
        double natural = WINDING_UA_NATURAL_KW_PER_K * (windingC - AMBIENT_C);
        return windingC + (lossKw - toCoolantKw - natural) * dt / WINDING_KJ_PER_K;
    }

    public static double coolantStepC(double coolantC, double fromWindingKw, double coolerKw, double dt) {
        return coolantC + (fromWindingKw - coolerKw) * dt / COOLANT_KJ_PER_K;
    }

    /** Damage the insulation takes in dt seconds: nothing under the limit, 1 %/s at 40 K over it. */
    public static double windingDamage(double windingC, double dt) {
        return 0.01 * Math.max(0, Math.min(3, (windingC - WINDING_LIMIT_C) / 40)) * dt;
    }
}
