package dev.bwr.mod.gui.client;

/**
 * The colour scales of the core maps, each with a legend on the screen that uses it.
 *
 * <p>Every scale changes in lightness as well as hue, so a bundle's value can be read by a player who cannot tell the
 * hues apart. Burnup runs from a light teal (fresh) to a dark red-orange (spent) rather than the green-to-red it used to,
 * which red-green colour blindness cannot separate.
 */
public final class LatticeColours {
    private LatticeColours() {
    }

    /** An empty core position. */
    public static final int EMPTY = 0xFF191C20;
    /** A non-fuel cassette. */
    public static final int INSERT = 0xFFB886D6;
    /** A rod that is moving: its notch differs from its demand. */
    public static final int MOVING = 0xFFC8A028;

    private static final int FRESH = 0xFF6EE1D7;
    private static final int SPENT = 0xFFBE3C1E;

    /** Burnup as a fraction of the core's peak: light teal fresh, dark red-orange spent. */
    public static int burnup(double fraction) {
        return lerp(FRESH, SPENT, fraction);
    }

    /** k-infinity from 0.90 (dark) to 1.25 (bright), as a fraction of that range. */
    public static int kInf(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        int v = (int) Math.round(50 + 190 * f);
        return 0xFF000000 | (v << 16) | (v << 8) | (v / 2 + 40);
    }

    /** A control rod's insertion: dark grey (in) to bright green (out). */
    public static int rod(double out) {
        double f = Math.max(0.0, Math.min(1.0, out));
        int grey = (int) Math.round(40 + 80 * (1.0 - f));
        int green = (int) Math.round(120 + 100 * f);
        return 0xFF000000 | (grey << 16) | (green << 8) | grey;
    }

    private static int lerp(int a, int b, double t) {
        double f = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
