package dev.bwr.mod.panel.model;

/**
 * Component state to part pose: the angle a handle, key, knob or needle is turned to, and how far a pointer or
 * a cap has slid. Pure functions so the numbers are unit tested; the renderer only feeds them state.
 *
 * <p>Every angle is in degrees, measured from the pose the part was modelled in (rotation 0, the middle of its
 * travel), positive clockwise as the operator sees the face. Modellers author handles pointing at the middle
 * position (NORMAL on a control switch, whose grip hangs down) and needles pointing at mid-scale, selectors
 * pointing at 12 o'clock.
 */
public final class Poses {
    private Poses() {
    }

    /** Control switch handle swing between CLOSE, NORMAL and OPEN. */
    public static final float CONTROL_SWITCH_STEP_DEG = 45.0f;
    /** Selector detent spacing (the SB series indexes in 45 degree steps). */
    public static final float DETENT_STEP_DEG = 45.0f;
    /** A two position device (a key switch, a two position selector) turns a quarter turn. */
    public static final float TWO_POSITION_STEP_DEG = 90.0f;
    /** Pointer sweep of the round switchboard meter (the AB-40 scale is 250 degrees). */
    public static final float DIAL_SWEEP_DEG = 250.0f;
    /** Setpoint knob sweep, 0 to 100 percent. */
    public static final float KNOB_SWEEP_DEG = 270.0f;

    /**
     * Control switch handle angle for the mod's position -1 (CLOSE), 0 (NORMAL) or +1 (OPEN, the breaker's TRIP);
     * other values clamp. The modelled pistol grip hangs straight down at NORMAL; CLOSE turns it 45 degrees
     * clockwise and TRIP 45 degrees counter-clockwise, hence the minus sign.
     */
    public static float controlSwitchAngle(int position) {
        return -Math.max(-1, Math.min(1, position)) * CONTROL_SWITCH_STEP_DEG;
    }

    /**
     * Handle angle of detent {@code index} (0-based) of {@code count}, symmetric about the modelled pose:
     * 3 positions are -45, 0, +45; 4 are -67.5, -22.5, +22.5, +67.5; 2 turn a quarter (-45, +45).
     */
    public static float detentAngle(int index, int count) {
        int n = Math.max(1, count);
        int i = Math.max(0, Math.min(n - 1, index));
        float step = n == 2 ? TWO_POSITION_STEP_DEG : DETENT_STEP_DEG;
        return (i - (n - 1) / 2.0f) * step;
    }

    /** Key angle: off and on are a quarter turn apart. */
    public static float keyAngle(boolean on) {
        return detentAngle(on ? 1 : 0, 2);
    }

    /** Needle angle for a scale fraction 0..1 over {@code sweepDegrees}: -sweep/2 at 0, 0 mid scale, +sweep/2 at 1. */
    public static float needleAngle(double fraction, float sweepDegrees) {
        return (float) ((clamp01(fraction) - 0.5) * sweepDegrees);
    }

    /** Setpoint knob angle for 0..100 percent: -135 to +135 degrees. */
    public static float knobAngle(double percent) {
        return (float) ((clamp01(percent / 100.0) - 0.5) * KNOB_SWEEP_DEG);
    }

    /** Fraction of the modelled travel for a pointer on a scale fraction (0 low end .. 1 high end). */
    public static float pointerSlide(double fraction) {
        return (float) clamp01(fraction);
    }

    /** Fraction of the modelled travel for a pushbutton cap: all the way in while held. */
    public static float capSlide(boolean held) {
        return held ? 1.0f : 0.0f;
    }

    /** {@code v} limited to 0..1; NaN (no signal) reads as 0. */
    public static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }
}
