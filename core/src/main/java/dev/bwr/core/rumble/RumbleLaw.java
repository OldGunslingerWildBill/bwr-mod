package dev.bwr.core.rumble;

import dev.bwr.core.turbine.ShaftDynamics;

/**
 * How far a machine's rumble carries: the distance law, the perception limit and the maps from intensity to what a player
 * sees and hears (RUMBLE.md). Pure arithmetic with no Minecraft types, so the acceptance suite and the game tests use the very
 * same numbers the server and the client use. It reads nothing from the plant and acts on nothing: a rumble is an effect.
 *
 * <h2>The law</h2>
 * {@code I(P, r) = A * sqrt(P / Pref) * (r0 / (r + r0))^n * exp(-r / L)} where {@code P} is the source power in watts and
 * {@code r} the distance in blocks (metres). Amplitude goes with the square root of power (power is amplitude squared);
 * the spreading exponent {@code n} and the extra attenuation length {@code L} belong to the {@link Profile}.
 *
 * <h2>Two profiles</h2>
 * <ul>
 *   <li>{@link Profile#GROUND}: steam condensing in the suppression pool and blasts. The energy goes into the basemat and the soil
 *       and travels as surface waves, which spread over a circle: amplitude falls as {@code r^-0.5}, so a source of eighteen times
 *       the power is felt eighteen times as far. Calibrated so that the whole relief valve bank of a large plant, discharging
 *       at rated capacity, sits exactly on the {@link #PERCEPTION} limit at {@link #CALIBRATION_DISTANCE_BLOCKS} (8,000 blocks).</li>
 *   <li>{@link Profile#MACHINE}: a shaft train shaking its hall. Carried by the building and the air, so it spreads as {@code 1/r}
 *       and dies in tens of blocks.</li>
 * </ul>
 */
public final class RumbleLaw {
    private RumbleLaw() {}

    /** The weakest intensity anyone can feel or hear; anything below it is zero. */
    public static final double PERCEPTION = 0.02;
    /** Distance at which the relief valve bank of a large plant, at rated capacity, is exactly at {@link #PERCEPTION}. */
    public static final double CALIBRATION_DISTANCE_BLOCKS = 8000.0;
    /** Safety relief valves of the largest plant the mod models (Columbia-class). */
    public static final int LARGE_PLANT_RELIEF_VALVES = 18;
    /** Steam one fully open relief valve passes at rated pressure, kg/s ({@code SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S}). */
    public static final double RELIEF_VALVE_KG_PER_S = 42.0;
    /** Rounded power of that whole bank condensing in the pool: 18 x 42 kg/s x about 2,775 kJ/kg of saturated steam at rated pressure. */
    public static final double LARGE_PLANT_BANK_W = 2.1e9;
    /** Intensity at which the shake and the sound reach their ceiling (the soft limit). */
    public static final double FULL = 0.15;
    /** Time constant of a one-shot impulse (a missile, a blast), seconds: its energy leaves the source over about this long. */
    public static final double IMPULSE_TAU_S = 2.0;
    /** Vibration level that has the {@link Profile#MACHINE} reference power, mm/s. */
    public static final double REFERENCE_VIBRATION_MM_S = 7.1;

    /** A family of distance laws. */
    public enum Profile {
        /** Ground-borne: pool condensation and blasts. */
        GROUND(LARGE_PLANT_BANK_W, 16.0, 0.5, 100_000.0,
                PERCEPTION * Math.sqrt((CALIBRATION_DISTANCE_BLOCKS + 16.0) / 16.0) * Math.exp(CALIBRATION_DISTANCE_BLOCKS / 100_000.0)),
        /** Building-borne: a vibrating turbine train. Reference power is the rumble of a rotor at the 7.1 mm/s vibration level, at rated speed. */
        MACHINE(2.0e7, 6.0, 1.0, 60.0, 0.9);

        /** Reference power, W. */
        public final double referenceW;
        /** Core radius, blocks: inside it the source is a point no longer and the law stops rising. */
        public final double coreRadius;
        /** Spreading exponent. */
        public final double spreading;
        /** Extra attenuation length, blocks. */
        public final double attenuationLength;
        /** Intensity of the reference power at the source. */
        public final double amplitude;

        Profile(double referenceW, double coreRadius, double spreading, double attenuationLength, double amplitude) {
            this.referenceW = referenceW;
            this.coreRadius = coreRadius;
            this.spreading = spreading;
            this.attenuationLength = attenuationLength;
            this.amplitude = amplitude;
        }

        /** The law itself, with no perception limit: finite, never negative, rising with power and falling with distance. */
        public double raw(double powerW, double distanceBlocks) {
            if (!(powerW > 0) || !Double.isFinite(powerW)) return 0;
            double r = Math.max(0, Double.isFinite(distanceBlocks) ? distanceBlocks : 1e12);
            double ratio = coreRadius / (r + coreRadius);
            double spread = spreading == 0.5 ? Math.sqrt(ratio) : spreading == 1.0 ? ratio : Math.pow(ratio, spreading);
            double v = amplitude * Math.sqrt(powerW / referenceW) * spread * Math.exp(-r / attenuationLength);
            return Double.isFinite(v) ? v : 0;
        }

        /** What a player at this distance perceives: {@link #raw}, or zero if it is under {@link #PERCEPTION}. */
        public double intensity(double powerW, double distanceBlocks) {
            double v = raw(powerW, distanceBlocks);
            return v >= PERCEPTION ? v : 0;
        }

        /** Distance, blocks, at which a source of this power falls to {@link #PERCEPTION}; 0 if it is under it even at the source. */
        public double range(double powerW) {
            if (raw(powerW, 0) < PERCEPTION) return 0;
            double lo = 0, hi = 1e9;
            for (int i = 0; i < 200; i++) {
                double mid = 0.5 * (lo + hi);
                if (raw(powerW, mid) >= PERCEPTION) lo = mid; else hi = mid;
            }
            return lo;
        }
    }

    /** Soft ceiling of intensity, 0..1: roughly linear well under {@link #FULL}, flat well over it. */
    public static double level(double intensity) {
        if (!(intensity > 0)) return 0;
        return Double.isFinite(intensity) ? Math.tanh(intensity / FULL) : 1;
    }

    /** Fraction of the largest camera shake for an intensity (the level, a little steeper so a faint rumble stays faint). */
    public static double shakeAmplitude(double intensity) {
        return Math.pow(level(intensity), 1.3);
    }

    /** Volume of the rumble loop for an intensity, 0..1 (before the player's own sliders). */
    public static double soundVolume(double intensity) {
        return Math.min(1.0, Math.pow(level(intensity), 0.8));
    }

    /** Pitch of the loop: a distant rumble is deeper, because the air and ground keep the highs. 0.70..1.00. */
    public static double soundPitch(double intensity) {
        return 0.70 + 0.30 * level(intensity);
    }

    /** Power a pool receives, W, from the mass flow of saturated steam (kg/s) and its enthalpy (kJ/kg). */
    public static double condensingPowerW(double kgPerS, double enthalpyKJPerKg) {
        return Math.max(0, kgPerS) * Math.max(0, enthalpyKJPerKg) * 1000.0;
    }

    /** Equivalent power, W, of a one-shot energy in joules, spread over {@link #IMPULSE_TAU_S}. */
    public static double impulsePowerW(double energyJ) {
        return Math.max(0, energyJ) / IMPULSE_TAU_S;
    }

    /** Vibration, mm/s, that a healthy, centred rotor shows at a speed (the baseline part of {@code TurbineAux.vibrationMmPerS}). */
    public static double healthyVibrationMmPerS(double rpm) {
        return 1.5 * Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
    }

    /**
     * Rumble power of a turbine train, W, from its shaft vibration (mm/s, the reading the auxiliaries publish) and its speed.
     * Only the vibration above what a healthy rotor shows counts, weighted by how much energy the shaft holds (never under 0.4:
     * a bowed rotor shakes hardest at low speed, near its critical speed); a small steady hum rides on top, so a turning train is
     * heard in its own hall and nowhere else.
     */
    public static double turbinePowerW(double vibrationMmPerS, double rpm) {
        double s = Math.min(1.5, Math.max(0, rpm) / ShaftDynamics.RATED_RPM);
        if (s <= 0 || !Double.isFinite(vibrationMmPerS)) return 0;
        double over = Math.max(0, vibrationMmPerS - healthyVibrationMmPerS(rpm)) / REFERENCE_VIBRATION_MM_S;
        double energy = 0.4 + 0.6 * s * s;
        return Profile.MACHINE.referenceW * (energy * over * over + 0.004 * s * s * s);
    }
}
