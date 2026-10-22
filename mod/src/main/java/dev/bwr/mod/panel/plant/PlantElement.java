package dev.bwr.mod.panel.plant;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One thing on a plant computer page. All kinds share one record so a page is a flat list and a patch is a few bytes per
 * element. Positions are 1-based character cells ({@code col}, {@code row}) of the display's text grid.
 *
 * <ul>
 *   <li>{@link Kind#TEXT}: a line of text in a colour.</li>
 *   <li>{@link Kind#FIELD}: a caption on the left and a number with its unit on the right, coloured by the alarm limits.</li>
 *   <li>{@link Kind#BAR}: a caption, a bar between {@code min} and {@code max}, and the number; coloured like a field.</li>
 *   <li>{@link Kind#TREND}: a plot of the last {@code len} points pushed, between {@code min} and {@code max}.</li>
 *   <li>{@link Kind#TILE}: a labelled green / amber / red tile (the critical safety function grid).</li>
 *   <li>{@link Kind#KEY}: a soft key on the bottom row; touching it fires {@code plant_computer_touch}.</li>
 * </ul>
 * Mutable; the owner ({@link PlantState}) keeps the counts and the change flags.
 */
public final class PlantElement {
    public enum Kind { TEXT, FIELD, BAR, TREND, TILE, KEY }

    public final Kind kind;
    public final String id;
    public int col = 1;
    public int row = 1;
    public int w = 1;
    public int h = 1;
    /** Text line, caption, tile label or key label. */
    public String text = "";
    public String unit = "";
    /** Colour index ({@link PlantColors}): the text colour, or the normal colour of a field, bar or trend. */
    public int colour = PlantColors.WHITE;
    public int decimals = 1;
    /** Current value of a field or bar; NaN is "no data". For a trend, the last value pushed. */
    public float value = Float.NaN;
    public float min = 0.0f;
    public float max = 100.0f;
    public float warnLow = Float.NaN;
    public float warnHigh = Float.NaN;
    public float alarmLow = Float.NaN;
    public float alarmHigh = Float.NaN;
    /**
     * Tile state ({@link PlantColors#STATE_OFF} ...), the number of a soft key (1 to {@link PlantLimits#MAX_KEYS}), or for a
     * bar 1 when it shows no number (just the bar).
     */
    public int state;
    /** Trend: history length, and the points held, oldest first, each 0..65535 across min..max. */
    public int len = PlantLimits.DEFAULT_POINTS;
    public char[] points = new char[0];
    public int count;

    public PlantElement(Kind kind, String id) {
        this.kind = kind;
        this.id = id;
    }

    public PlantElement copy() {
        PlantElement e = new PlantElement(kind, id);
        e.col = col;
        e.row = row;
        e.w = w;
        e.h = h;
        e.text = text;
        e.unit = unit;
        e.colour = colour;
        e.decimals = decimals;
        e.value = value;
        e.min = min;
        e.max = max;
        e.warnLow = warnLow;
        e.warnHigh = warnHigh;
        e.alarmLow = alarmLow;
        e.alarmHigh = alarmHigh;
        e.state = state;
        e.len = len;
        e.points = Arrays.copyOf(points, points.length);
        e.count = count;
        return e;
    }

    // ---- derived state

    /** The status of the value: off without data, else ok / warn / alarm by the limits. */
    public int status() {
        return status(value);
    }

    public int status(float v) {
        if (Float.isNaN(v)) {
            return PlantColors.STATE_OFF;
        }
        if (!Float.isNaN(alarmHigh) && v >= alarmHigh || !Float.isNaN(alarmLow) && v <= alarmLow) {
            return PlantColors.STATE_ALARM;
        }
        if (!Float.isNaN(warnHigh) && v >= warnHigh || !Float.isNaN(warnLow) && v <= warnLow) {
            return PlantColors.STATE_WARN;
        }
        return PlantColors.STATE_OK;
    }

    /** The colour a field or bar value is drawn in: its own colour while ok, amber, red, or grey without data. */
    public int valueColour() {
        int s = status();
        return s == PlantColors.STATE_OK ? colour : PlantColors.ofState(s);
    }

    /** The shown text of the value. */
    public String valueText() {
        return PlantFormat.number(value, decimals);
    }

    /** How full a bar is, 0 to 1 (0 without data). */
    public float fraction() {
        return fraction(value);
    }

    public float fraction(float v) {
        if (Float.isNaN(v) || !(max > min)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (v - min) / (max - min)));
    }

    /** The value a trend point holds. */
    public float pointValue(int index) {
        return min + (max - min) * (points[index] / 65535.0f);
    }

    public static char quantise(float v, float min, float max) {
        if (Float.isNaN(v) || !(max > min)) {
            return 0;
        }
        float f = Math.max(0.0f, Math.min(1.0f, (v - min) / (max - min)));
        return (char) Math.round(f * 65535.0f);
    }

    /**
     * Sets the value of a field or bar. Returns whether what is shown changed: the text, the status, or a bar's fill by
     * half a percent of its width. A value that moves below that does not queue a patch.
     */
    public boolean setValue(float v) {
        if (Float.isInfinite(v)) {
            v = Float.NaN;
        }
        boolean same = PlantFormat.number(v, decimals).equals(PlantFormat.number(value, decimals)) && status(v) == status();
        if (same && kind == Kind.BAR) {
            same = Math.round(fraction(v) * 200.0f) == Math.round(fraction() * 200.0f);
        }
        if (same) {
            // The value stays as it was shown, so a client that was sent the old one is exactly in step with the server.
            return false;
        }
        value = v;
        return true;
    }

    /** Adds a point to a trend: the oldest goes when it is full. */
    public void push(float v) {
        if (points.length != len) {
            points = Arrays.copyOf(points, len);
            count = Math.min(count, len);
        }
        if (count == len) {
            System.arraycopy(points, 1, points, 0, len - 1);
            points[len - 1] = quantise(v, min, max);
        } else {
            points[count++] = quantise(v, min, max);
        }
        value = v;
    }

    // ---- wire form

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int n = in.readUnsignedByte();
        if (n > max * 4) {
            throw new IOException("string too long");
        }
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        // Texts are printable ASCII (PlantApi); anything else in a save or packet is shown as '?'.
        StringBuilder s = new StringBuilder(Math.min(n, max));
        for (int i = 0; i < n && s.length() < max; i++) {
            char c = (char) (bytes[i] & 0xFF);
            s.append(c >= 32 && c < 127 ? c : '?');
        }
        return s.toString();
    }

    static void writeId(DataOutputStream out, String id) throws IOException {
        writeString(out, id);
    }

    static String readId(DataInputStream in) throws IOException {
        return readString(in, PlantLimits.MAX_ID);
    }

    static void writeText(DataOutputStream out, String text) throws IOException {
        writeString(out, text);
    }

    static String readText(DataInputStream in, int max) throws IOException {
        return readString(in, max);
    }

    void write(DataOutputStream out) throws IOException {
        out.writeByte(kind.ordinal());
        writeString(out, id);
        out.writeByte(col);
        out.writeByte(row);
        out.writeByte(w);
        out.writeByte(h);
        out.writeByte(colour);
        writeString(out, text);
        switch (kind) {
            case TEXT -> {
            }
            case FIELD -> {
                writeString(out, unit);
                out.writeByte(decimals);
                out.writeFloat(value);
                out.writeFloat(warnLow);
                out.writeFloat(warnHigh);
                out.writeFloat(alarmLow);
                out.writeFloat(alarmHigh);
            }
            case BAR -> {
                writeString(out, unit);
                out.writeByte(decimals);
                out.writeByte(state);
                out.writeFloat(value);
                out.writeFloat(min);
                out.writeFloat(max);
                out.writeFloat(warnLow);
                out.writeFloat(warnHigh);
                out.writeFloat(alarmLow);
                out.writeFloat(alarmHigh);
            }
            case TREND -> {
                writeString(out, unit);
                out.writeByte(decimals);
                out.writeFloat(min);
                out.writeFloat(max);
                out.writeFloat(value);
                out.writeByte(len);
                out.writeByte(count);
                for (int i = 0; i < count; i++) {
                    out.writeChar(points[i]);
                }
            }
            case TILE, KEY -> out.writeByte(state);
        }
    }

    /** Reads one element, checking every number; throws {@link IOException} on anything out of range. */
    static PlantElement read(DataInputStream in) throws IOException {
        int k = in.readUnsignedByte();
        if (k >= Kind.values().length) {
            throw new IOException("unknown kind");
        }
        Kind kind = Kind.values()[k];
        PlantElement e = new PlantElement(kind, readId(in));
        e.col = in.readUnsignedByte();
        e.row = in.readUnsignedByte();
        e.w = in.readUnsignedByte();
        e.h = in.readUnsignedByte();
        e.colour = in.readUnsignedByte();
        if (e.col < 1 || e.row < 1 || e.w < 1 || e.h < 1 || e.col > PlantLimits.MAX_COLS || e.row > PlantLimits.MAX_ROWS
                || e.w > PlantLimits.MAX_COLS || e.h > PlantLimits.MAX_ROWS || e.colour >= PlantColors.NAMES.size()) {
            throw new IOException("element out of range");
        }
        e.text = readString(in, PlantLimits.MAX_TEXT);
        switch (kind) {
            case TEXT -> {
            }
            case FIELD -> {
                e.unit = readString(in, PlantLimits.MAX_UNIT);
                e.decimals = decimals(in.readUnsignedByte());
                e.value = in.readFloat();
                e.warnLow = in.readFloat();
                e.warnHigh = in.readFloat();
                e.alarmLow = in.readFloat();
                e.alarmHigh = in.readFloat();
            }
            case BAR -> {
                e.unit = readString(in, PlantLimits.MAX_UNIT);
                e.decimals = decimals(in.readUnsignedByte());
                e.state = in.readUnsignedByte() & 1;
                e.value = in.readFloat();
                e.min = in.readFloat();
                e.max = in.readFloat();
                e.warnLow = in.readFloat();
                e.warnHigh = in.readFloat();
                e.alarmLow = in.readFloat();
                e.alarmHigh = in.readFloat();
            }
            case TREND -> {
                e.unit = readString(in, PlantLimits.MAX_UNIT);
                e.decimals = decimals(in.readUnsignedByte());
                e.min = in.readFloat();
                e.max = in.readFloat();
                e.value = in.readFloat();
                e.len = in.readUnsignedByte();
                e.count = in.readUnsignedByte();
                if (e.len < PlantLimits.MIN_POINTS || e.len > PlantLimits.MAX_POINTS || e.count > e.len) {
                    throw new IOException("trend out of range");
                }
                e.points = new char[e.len];
                for (int i = 0; i < e.count; i++) {
                    e.points[i] = in.readChar();
                }
            }
            case TILE -> {
                e.state = in.readUnsignedByte();
                if (e.state >= PlantColors.STATES.size()) {
                    throw new IOException("tile state");
                }
            }
            case KEY -> {
                e.state = in.readUnsignedByte();
                if (e.state < 1 || e.state > PlantLimits.MAX_KEYS) {
                    throw new IOException("key number");
                }
            }
        }
        if (!(e.max > e.min) && (kind == Kind.BAR || kind == Kind.TREND)) {
            throw new IOException("scale");
        }
        return e;
    }

    private static int decimals(int d) throws IOException {
        if (d > 4) {
            throw new IOException("decimals");
        }
        return d;
    }

    /** A trend push on the wire: the last {@code n} points and the value. */
    void writePush(DataOutputStream out, int n) throws IOException {
        int send = Math.min(n, count);
        out.writeByte(send);
        for (int i = count - send; i < count; i++) {
            out.writeChar(points[i]);
        }
        out.writeFloat(value);
    }

    void readPush(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n > len) {
            throw new IOException("push too long");
        }
        char[] fresh = new char[n];
        for (int i = 0; i < n; i++) {
            fresh[i] = in.readChar();
        }
        float last = in.readFloat();
        if (points.length != len) {
            points = Arrays.copyOf(points, len);
        }
        for (char point : fresh) {
            if (count == len) {
                System.arraycopy(points, 1, points, 0, len - 1);
                points[len - 1] = point;
            } else {
                points[count++] = point;
            }
        }
        value = last;
    }
}
