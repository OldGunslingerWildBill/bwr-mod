package dev.bwr.core;

import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.core.rumble.RumbleLaw.Profile;
import dev.bwr.core.rumble.RumbleShake;

/** The rumble distance law, its calibration and the camera shake it drives (RUMBLE.md). */
public final class RumbleLawTest {
    private RumbleLawTest() {}

    @SuppressWarnings("unused")
    public static void test01_wholeReliefValveBankIsAtPerceptionAt8000Blocks() {
        double bank = RumbleLaw.LARGE_PLANT_RELIEF_VALVES * RumbleLaw.RELIEF_VALVE_KG_PER_S * 2775.0 * 1000.0;
        Check.relative(RumbleLaw.LARGE_PLANT_BANK_W, bank, 0.02, "the rounded bank power matches 18 x 42 kg/s x 2775 kJ/kg");
        double at8000 = Profile.GROUND.raw(RumbleLaw.LARGE_PLANT_BANK_W, 8000);
        Check.relative(RumbleLaw.PERCEPTION, at8000, 1e-9, "the bank's intensity at 8,000 blocks");
        double range = Profile.GROUND.range(RumbleLaw.LARGE_PLANT_BANK_W);
        Check.relative(8000, range, 1e-6, "range of the full bank");
        Check.note("full bank %.3g W: range %.1f blocks, intensity at the source %.3f", RumbleLaw.LARGE_PLANT_BANK_W, range,
                Profile.GROUND.raw(RumbleLaw.LARGE_PLANT_BANK_W, 0));
    }

    public static void test02_oneValveIsFeltForHundredsOfBlocksAndClearlyAtThePool() {
        double one = RumbleLaw.RELIEF_VALVE_KG_PER_S * 2775.0 * 1000.0;
        double range = Profile.GROUND.range(one);
        Check.inRange(300, 700, range, "one relief valve's range, blocks");
        double near = Profile.GROUND.intensity(one, 10);
        Check.inRange(0.06, 0.20, near, "one valve 10 blocks from the pool");
        Check.inRange(0.3, 0.7, RumbleLaw.level(near), "level of one valve at 10 blocks");
        Check.exactly(0.0, Profile.GROUND.intensity(one, range * 1.01), "silent beyond its range");
        Check.note("one valve %.3g W: range %.1f blocks, 10 blocks away %.3f", one, range, near);
    }

    public static void test03_monotonicInPowerAndDistance() {
        for (Profile profile : Profile.values()) {
            double last = -1;
            for (double p = 1e3; p < 1e12; p *= 1.7) {
                double v = profile.raw(p, 25);
                Check.isTrue(v >= last, "%s not rising with power at %g W", profile, p);
                last = v;
            }
            last = Double.MAX_VALUE;
            for (double r = 0; r < 20000; r += 7.3) {
                double v = profile.raw(1e8, r);
                Check.isTrue(v <= last, "%s not falling with distance at %g", profile, r);
                Check.finite(v, "intensity");
                last = v;
            }
            Check.exactly(0.0, profile.raw(0, 5), "no power, no rumble");
            Check.exactly(0.0, profile.raw(-5, 5), "negative power");
            Check.exactly(0.0, profile.raw(Double.NaN, 5), "NaN power");
        }
    }

    public static void test04_badlyVibratingTurbineShakesItsHallAndIsHeardTensOfBlocks() {
        double healthy = RumbleLaw.turbinePowerW(RumbleLaw.healthyVibrationMmPerS(1500), 1500);
        double alarm = RumbleLaw.turbinePowerW(7.1 + RumbleLaw.healthyVibrationMmPerS(1500), 1500);
        double bowed = RumbleLaw.turbinePowerW(15.5, 525);
        Check.isTrue(healthy < alarm && alarm < bowed * 4, "healthy %g, 7.1 mm/s over %g, bowed at critical speed %g", healthy, alarm, bowed);
        double hallRange = Profile.MACHINE.range(alarm);
        Check.inRange(40, 120, hallRange, "range of a turbine at the 7.1 mm/s level, blocks");
        double healthyRange = Profile.MACHINE.range(healthy);
        Check.inRange(0, 25, healthyRange, "a healthy running turbine is heard only in its own hall");
        Check.inRange(0.4, 1.0, RumbleLaw.level(Profile.MACHINE.intensity(alarm, 10)), "level 10 blocks from a turbine at 7.1 mm/s");
        Check.isTrue(Profile.MACHINE.range(bowed) < 150, "a bowed rotor is not heard at 150 blocks");
        Check.exactly(0.0, RumbleLaw.turbinePowerW(10, 0), "a stopped rotor makes no rumble");
        Check.note("turbine: healthy %.3g W range %.1f; 7.1 mm/s %.3g W range %.1f; bowed %.3g W range %.1f", healthy, healthyRange, alarm, hallRange,
                bowed, Profile.MACHINE.range(bowed));
    }

    public static void test05_mapsAreBoundedAndSoftlyClipped() {
        Check.exactly(0.0, RumbleLaw.level(0), "level at 0");
        Check.exactly(0.0, RumbleLaw.shakeAmplitude(0), "shake at 0");
        double last = 0;
        for (double i = 0.001; i < 100; i *= 1.3) {
            double a = RumbleLaw.shakeAmplitude(i), v = RumbleLaw.soundVolume(i), p = RumbleLaw.soundPitch(i);
            Check.isTrue(a >= last && a <= 1 && v >= 0 && v <= 1 && p >= 0.7 && p <= 1.0, "bounds at %g: %g %g %g", i, a, v, p);
            last = a;
        }
        Check.isTrue(RumbleLaw.shakeAmplitude(RumbleLaw.PERCEPTION) < 0.1, "a rumble at the perception limit is a faint sway");
        Check.isTrue(RumbleLaw.shakeAmplitude(0.5) > 0.9, "a strong rumble is nearly the full shake");
    }

    public static void test06_cameraShakeIsSmoothBoundedAndScalesWithAmplitude() {
        var o = new RumbleShake.Offsets();
        double maxAngle = 0, maxMove = 0, maxStep = 0, last = 0;
        for (double t = 0; t < 60; t += 1.0 / 60) {
            RumbleShake.compute(1, 0, 1, t, o);
            maxAngle = Math.max(maxAngle, o.maxAngleDeg());
            maxMove = Math.max(maxMove, o.maxMoveBlocks());
            if (t > 0) maxStep = Math.max(maxStep, Math.abs(o.rollDeg - last));
            last = o.rollDeg;
        }
        Check.inRange(3.5, RumbleShake.ROLL_DEG + 1e-9, maxAngle, "the strongest ground shake reaches several degrees but never more than the limit");
        Check.inRange(0.05, RumbleShake.BOB_Y + 1e-9, maxMove, "the position moves a fifth of a block at most");
        Check.isTrue(maxStep < 2.5, "roll moved %g degrees between two 60 Hz frames: that is jitter, not smooth noise", maxStep);
        double both = 0;
        for (double t = 0; t < 30; t += 1.0 / 30) {
            RumbleShake.compute(1, 1, 1, t, o);
            both = Math.max(both, o.maxAngleDeg());
        }
        Check.isTrue(both <= RumbleShake.MAX_TOTAL * RumbleShake.ROLL_DEG + 1e-9, "two full sources are bounded: %g", both);
        double faint = 0;
        for (double t = 0; t < 60; t += 1.0 / 30) {
            RumbleShake.compute(RumbleLaw.shakeAmplitude(RumbleLaw.PERCEPTION), 0, 0, t, o);
            faint = Math.max(faint, o.maxAngleDeg());
        }
        Check.isTrue(faint < 0.6, "at the perception limit the view sways %g degrees", faint);
        RumbleShake.compute(0, 0, 1, 3, o);
        Check.exactly(0.0, o.maxAngleDeg() + o.maxMoveBlocks(), "no amplitude, no motion");
    }

    /**
     * What the ground shake feels like is its velocity and acceleration, not its displacement: a slow sway of several degrees moves the view far
     * more than a tremor of a degree does, but the tremor is what is felt as shaking. This measures the Hann-windowed spectrum of the velocity
     * (first difference) of the roll, pitch, yaw and bob of the whole motion, 10 s at 240 Hz, long after the start of the game, at full strength.
     */
    private static double[] velocitySpectrum(double nearness, double[] freqOut) {
        var o = new RumbleShake.Offsets();
        final double fs = 240;
        final int n = 2400;
        double[][] x = new double[4][n];
        for (int i = 0; i < n; i++) {
            RumbleShake.compute(1, 0, nearness, 3600 + i / fs, o);
            x[0][i] = o.rollDeg;
            x[1][i] = o.pitchDeg;
            x[2][i] = o.yawDeg;
            x[3][i] = o.y * (RumbleShake.TREMOR_ROLL_DEG / RumbleShake.TREMOR_BOB_Y); // a bob weighs like the angle it comes with
        }
        double[] power = new double[freqOut.length];
        for (int k = 0; k < freqOut.length; k++) {
            double f = freqOut[k], p = 0;
            for (double[] axis : x) {
                double re = 0, im = 0;
                for (int i = 1; i < n; i++) {
                    double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
                    double v = (axis[i] - axis[i - 1]) * fs;
                    re += w * v * Math.cos(2 * Math.PI * f * i / fs);
                    im += w * v * Math.sin(2 * Math.PI * f * i / fs);
                }
                p += re * re + im * im;
            }
            power[k] = p;
        }
        return power;
    }

    public static void test07_groundShakeIsAFastTremorOfTenToFourteenHertz() {
        double[] freqs = new double[390];
        for (int k = 0; k < freqs.length; k++) freqs[k] = 0.5 + 0.1 * k; // 0.5 .. 39.4 Hz
        double[] peaks = new double[2];
        int idx = 0;
        for (double nearness : new double[] {1.0, 0.0}) {
            double[] power = velocitySpectrum(nearness, freqs);
            double best = 0, bestHz = 0, total = 0, band = 0;
            for (int k = 0; k < freqs.length; k++) {
                total += power[k];
                if (freqs[k] >= 9.5 && freqs[k] <= 14.5) band += power[k];
                if (power[k] > best) { best = power[k]; bestHz = freqs[k]; }
            }
            Check.inRange(10.0, 14.0, bestHz, "dominant frequency of the felt (velocity) motion at nearness " + nearness + ", Hz");
            Check.isTrue(band / total > 0.7, "only %.2f of the motion's velocity power is between 9.5 and 14.5 Hz at nearness %g", band / total, nearness);
            Check.note("nearness %.0f: dominant %.1f Hz, %.0f %% of the velocity power in 9.5-14.5 Hz", nearness, bestHz, 100 * band / total);
            peaks[idx++] = bestHz;
        }
        Check.isTrue(peaks[0] > peaks[1] + 1.0, "the tremor is faster near the source: %g Hz near, %g Hz far", peaks[0], peaks[1]);
    }

    public static void test08_tremorLayerHasTenToFourteenHertzZeroCrossingsAndStaysSmall() {
        var o = new RumbleShake.Offsets();
        for (double nearness : new double[] {1.0, 0.5, 0.0}) {
            int crossings = 0;
            double last = 0, maxStep = 0, maxAngle = 0, maxMove = 0;
            final double fs = 240;
            for (int i = 0; i < 2400; i++) {
                o.clear();
                RumbleShake.tremor(1, nearness, 100 + i / fs, o);
                if (i > 0 && (o.rollDeg > 0) != (last > 0)) crossings++;
                if (i > 0) maxStep = Math.max(maxStep, Math.abs(o.rollDeg - last));
                last = o.rollDeg;
                maxAngle = Math.max(maxAngle, o.maxAngleDeg());
                maxMove = Math.max(maxMove, o.maxMoveBlocks());
            }
            double hz = crossings / 2.0 / 10.0;
            // At 0.5 two carriers beat; the count is then somewhat higher or lower, but still between the two.
            Check.inRange(9.5, 14.5, hz, "zero crossings of the tremor roll in 10 s, as Hz, at nearness " + nearness);
            Check.isTrue(maxAngle <= 1.0 + 1e-9, "the tremor is about a degree of angle: %g", maxAngle);
            Check.isTrue(maxMove <= 0.04 + 1e-9, "the tremor is small positional jitter: %g blocks", maxMove);
            Check.note("tremor at nearness %.1f: %.1f Hz by zero crossings, up to %.2f degrees and %.3f blocks", nearness, hz, maxAngle, maxMove);
        }
        // Whole motion: the tremor adds at most about 1.1 degrees to the largest step between two 60 Hz frames.
        double maxStep = 0, last = 0;
        for (double t = 0; t < 60; t += 1.0 / 60) {
            o.clear();
            RumbleShake.tremor(1, 1, t, o);
            if (t > 0) maxStep = Math.max(maxStep, Math.abs(o.rollDeg - last));
            last = o.rollDeg;
        }
        Check.isTrue(maxStep < 1.25, "the tremor alone moved the roll %g degrees between two 60 Hz frames", maxStep);
        Check.note("tremor alone: largest roll step between two 60 Hz frames %.2f degrees", maxStep);
    }
}
