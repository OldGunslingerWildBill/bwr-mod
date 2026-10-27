package dev.bwr.core.rumble;

/**
 * The procedural camera motion of a rumble (RUMBLE.md): smooth gradient noise, never a random number per frame. Pure and
 * deterministic in time, so a game test can sample it. Output is a camera offset only; nothing here touches a player's aim.
 *
 * <p>Full strength ({@code amplitude} = 1) is a hard shake: 6 degrees of roll, 5 degrees of pitch and of yaw, 0.18 blocks up and down and
 * 0.09 blocks sideways, at 1 to 3 Hz for a distant source and up to 3 to 8 Hz beside it.
 */
public final class RumbleShake {
    private RumbleShake() {}

    public static final double ROLL_DEG = 6.0, PITCH_DEG = 5.0, YAW_DEG = 5.0, BOB_Y = 0.18, BOB_XZ = 0.09;
    /** Two sources never add to more than this many full shakes. */
    public static final double MAX_TOTAL = 1.25;

    /** Camera offsets: degrees for the angles, blocks for the position. */
    public static final class Offsets {
        public double yawDeg, pitchDeg, rollDeg, x, y, z;
        public void clear() { yawDeg = pitchDeg = rollDeg = x = y = z = 0; }
        /** Largest absolute value of any angle, degrees. */
        public double maxAngleDeg() { return Math.max(Math.abs(yawDeg), Math.max(Math.abs(pitchDeg), Math.abs(rollDeg))); }
        /** Largest absolute displacement of the position, blocks. */
        public double maxMoveBlocks() { return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))); }
    }

    private static double grad(int i, int seed) {
        int h = i * 0x27d4eb2d + seed * 0x165667b1;
        h ^= h >>> 15; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;
        return (h & 0xffff) / 32767.5 - 1.0;
    }

    /** One-dimensional gradient noise in [-1, 1], continuous with continuous slope. */
    public static double noise(double x, int seed) {
        double fl = Math.floor(x);
        int i = (int) fl;
        double f = x - fl;
        double u = f * f * f * (f * (f * 6 - 15) + 10);
        double a = grad(i, seed) * f, b = grad(i + 1, seed) * (f - 1);
        return Math.max(-1, Math.min(1, 2.0 * (a + (b - a) * u)));
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /**
     * Two octaves of noise, normalised and softly stretched so that the motion actually reaches the stated amplitudes: raw gradient noise
     * seldom goes past 0.6 of its range, which would make every figure here twice what is seen. Smooth (tanh is), and never above 0.96.
     */
    private static double two(double x, int seed) {
        return Math.tanh(1.9 * (noise(x, seed) + 0.5 * noise(2.31 * x + 17.0, seed + 91)) / 1.5);
    }

    /**
     * @param ground    shake amplitude, 0..1, of the ground-borne rumble (pool, blasts): irregular, surging like chugging steam
     * @param machine   shake amplitude, 0..1, of a vibrating machine: steadier, with a faster hum
     * @param nearness  0..1, how close the nearest source is (1 beside it): raises the frequency
     * @param seconds   time, seconds
     */
    public static void compute(double ground, double machine, double nearness, double seconds, Offsets out) {
        out.clear();
        ground = clamp01(ground);
        machine = clamp01(machine);
        double total = ground + machine;
        if (total <= 0) return;
        if (total > MAX_TOTAL) { ground *= MAX_TOTAL / total; machine *= MAX_TOTAL / total; }
        nearness = clamp01(nearness);
        if (ground > 0) {
            double f = lerp(0.9, 3.2, nearness);
            // Surges between about 55 % and 100 % every second or two: the chugging of condensing steam.
            double env = 0.78 + 0.22 * noise(seconds * 0.65, 7);
            double a = ground * env;
            out.rollDeg += a * ROLL_DEG * two(seconds * f, 11);
            out.pitchDeg += a * PITCH_DEG * two(seconds * f * 1.13, 23);
            out.yawDeg += a * YAW_DEG * two(seconds * f * 0.87, 37);
            out.y += a * BOB_Y * two(seconds * f * 1.31, 41);
            out.x += a * BOB_XZ * two(seconds * f * 0.93, 53);
            out.z += a * BOB_XZ * two(seconds * f * 1.07, 59);
        }
        if (machine > 0) {
            double f = lerp(2.2, 6.0, nearness);
            double w = 2 * Math.PI * 9.5 * seconds;
            out.rollDeg += machine * ROLL_DEG * (two(seconds * f, 71) + 0.35 * Math.sin(w)) / 1.35;
            out.pitchDeg += machine * PITCH_DEG * (two(seconds * f * 1.09, 83) + 0.35 * Math.sin(w + 2.1)) / 1.35;
            out.yawDeg += machine * YAW_DEG * (two(seconds * f * 0.91, 97) + 0.35 * Math.sin(w + 4.2)) / 1.35;
            out.y += machine * BOB_Y * (two(seconds * f * 1.21, 101) + 0.35 * Math.sin(w + 1.0)) / 1.35;
            out.x += machine * BOB_XZ * (two(seconds * f * 0.97, 103) + 0.35 * Math.sin(w + 3.0)) / 1.35;
            out.z += machine * BOB_XZ * (two(seconds * f * 1.03, 107) + 0.35 * Math.sin(w + 5.0)) / 1.35;
        }
    }

    private static double clamp01(double v) { return v > 0 ? (v < 1 ? v : 1) : 0; }

    /** Nearness, 0..1, of a source at a distance in blocks: 1 beside it, one half at about 83 blocks, almost nothing at 1 km. */
    public static double nearness(double distanceBlocks) {
        return Math.exp(-Math.max(0, distanceBlocks) / 120.0);
    }
}
