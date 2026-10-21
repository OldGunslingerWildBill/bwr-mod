package dev.bwr.mod.panel.component;

/**
 * Where the parts of the manual/auto controller station sit on its face, as fractions of the component (0..1 across and
 * 0..1 down). The model draws from these and the type's {@link ControllerStationType#clickValue click} reads them, so
 * the part you aim at is the part that is drawn, at every size. Pure numbers: nothing here touches Minecraft.
 *
 * <p>From the top: a cream scale window with the process-variable pointer (red, right of the scale line) and the
 * setpoint pointer (green, left of it) on ONE scale, and the demand bar beside it; a row of two lamp pushbuttons AUTO
 * and MAN; a row of two pushbuttons RAISE and LOWER.
 */
public final class StationLayout {
    private StationLayout() {
    }

    /** The regions {@link #regionAt} names (they are also the value a click sends). */
    public static final int NONE = 0;
    public static final int AUTO = 1;
    public static final int MAN = 2;
    public static final int RAISE = 3;
    public static final int LOWER = 4;

    /** The cream scale window. */
    public static final float WIN_X0 = 0.07f;
    public static final float WIN_X1 = 0.93f;
    public static final float WIN_Y0 = 0.035f;
    public static final float WIN_Y1 = 0.595f;

    /** The scale line: its x, and the y of 100 % (top) and 0 % (bottom). */
    public static final float AXIS_X = 0.30f;
    public static final float SCALE_TOP = 0.150f;
    public static final float SCALE_BOTTOM = 0.530f;
    /** Setpoint pointer (left of the line): tip x and base x. PV pointer (right): tip and base. */
    public static final float SP_TIP = 0.275f;
    public static final float SP_BASE = 0.135f;
    public static final float PV_TIP = 0.325f;
    public static final float PV_BASE = 0.465f;
    /** Half the height of a pointer triangle (of the component height). */
    public static final float POINTER_HALF = 0.026f;
    /** Scale numerals, left edge. */
    public static final float NUMERAL_X = 0.485f;

    /** The demand bar. */
    public static final float BAR_X0 = 0.675f;
    public static final float BAR_X1 = 0.835f;

    /** The AUTO and MAN lamp pushbuttons: lens rectangles. */
    public static final float LENS_Y0 = 0.640f;
    public static final float LENS_Y1 = 0.740f;
    /** The RAISE and LOWER caps. */
    public static final float CAP_Y0 = 0.840f;
    public static final float CAP_Y1 = 0.970f;
    /** The left and right pushbuttons: left one x range and right one x range. */
    public static final float LEFT_X0 = 0.08f;
    public static final float LEFT_X1 = 0.47f;
    public static final float RIGHT_X0 = 0.53f;
    public static final float RIGHT_X1 = 0.92f;
    /** Where the rows' hit areas change: above is the scale window (no control), the lamp row, then the cap row. */
    public static final float LAMP_ROW_Y0 = 0.60f;
    public static final float CAP_ROW_Y0 = 0.82f;

    /** y (fraction of the height) of a point {@code fraction} (0..1) up the scale. */
    public static float scaleY(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        return (float) (SCALE_BOTTOM - f * (SCALE_BOTTOM - SCALE_TOP));
    }

    /** The control under the point {@code (u, v)}, fractions of the component: one of the region constants. */
    public static int regionAt(double u, double v) {
        if (u < 0.0 || u > 1.0 || v < LAMP_ROW_Y0 || v > 1.0) {
            return NONE;
        }
        boolean left = u < 0.5;
        if (v < CAP_ROW_Y0) {
            return left ? AUTO : MAN;
        }
        return left ? RAISE : LOWER;
    }

    /** Centre of a control's region, as {u, v}: where a click on it aims. */
    public static float[] centreOf(int region) {
        return switch (region) {
            case AUTO -> new float[] {(LEFT_X0 + LEFT_X1) / 2.0f, (LENS_Y0 + LENS_Y1) / 2.0f};
            case MAN -> new float[] {(RIGHT_X0 + RIGHT_X1) / 2.0f, (LENS_Y0 + LENS_Y1) / 2.0f};
            case RAISE -> new float[] {(LEFT_X0 + LEFT_X1) / 2.0f, (CAP_Y0 + CAP_Y1) / 2.0f};
            case LOWER -> new float[] {(RIGHT_X0 + RIGHT_X1) / 2.0f, (CAP_Y0 + CAP_Y1) / 2.0f};
            default -> new float[] {0.5f, 0.3f};
        };
    }
}
