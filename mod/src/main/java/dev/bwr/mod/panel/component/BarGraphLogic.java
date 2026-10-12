package dev.bwr.mod.panel.component;

/**
 * Pure maths of the segmented bar graph, with no Minecraft types so it can be
 * tested directly: how many segments a value lights, and which band
 * (normal / warn / alarm) each segment belongs to.
 *
 * <p>A segment's band is decided by the value at its centre. When the alarm
 * level is at or above the warn level the bands are on the high side (warn at
 * and above {@code warn}, alarm at and above {@code alarm}); when the alarm
 * level is below the warn level they are on the low side (warn at and below
 * {@code warn}, alarm at and below {@code alarm}), as for a low-level alarm.
 */
public final class BarGraphLogic {
    private BarGraphLogic() {
    }

    public static final int NORMAL = 0;
    public static final int WARN = 1;
    public static final int ALARM = 2;

    /** Segments lit for {@code value} on a scale {@code min..max} of {@code segments}; none for no signal. */
    public static int lit(double value, double min, double max, int segments) {
        if (Double.isNaN(value) || segments <= 0 || !(max > min)) {
            return 0;
        }
        double fraction = (value - min) / (max - min);
        return (int) Math.max(0, Math.min(segments, Math.round(fraction * segments)));
    }

    /** The scale value at the centre of segment {@code index} (0 = lowest). */
    public static double centre(int index, double min, double max, int segments) {
        return min + (index + 0.5) / segments * (max - min);
    }

    /** The band of segment {@code index}. */
    public static int band(int index, double min, double max, int segments, double warn, double alarm) {
        double at = centre(index, min, max, segments);
        if (alarm >= warn) {
            return at >= alarm ? ALARM : at >= warn ? WARN : NORMAL;
        }
        return at <= alarm ? ALARM : at <= warn ? WARN : NORMAL;
    }
}
