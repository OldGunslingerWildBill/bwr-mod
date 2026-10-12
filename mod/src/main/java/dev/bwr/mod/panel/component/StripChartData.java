package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Strip-chart recorder: 1 to 4 pens, a configurable time span, and a bounded
 * history per pen.
 *
 * <h3>History</h3>
 * Each pen keeps a ring of at most {@link #CAPACITY} {@code (game tick, value)}
 * points, recorded <em>on change</em> (input 0..3 pushing a new value): a pen
 * that does not move costs nothing, a busy one is thinned to one point per
 * {@code span / }{@link #COLUMNS} ticks (a change in the same slot overwrites
 * the last point). The paper is the window {@code [now - span, now]}; between
 * points the pen holds its last value, so a flat signal is a flat line.
 * Points older than the window (except the one that still defines the pen at the
 * left edge) are dropped as new ones arrive.
 *
 * <h3>Sync</h3>
 * The server samples (it is the one that sees every input change) and saves the
 * history; clients receive it in the full state as ages in ticks and then
 * record the same input deltas themselves, so there is no per-sample traffic.
 * Times are absolute game ticks in memory and relative ages when saved, so a
 * reloaded world, or a client with a slightly different clock, still lines up.
 */
public final class StripChartData extends ComponentData {
    public static final int MAX_PENS = 4;
    public static final int CAPACITY = 160;
    public static final int COLUMNS = 120;
    public static final int MIN_SPAN_SECONDS = 10;
    public static final int MAX_SPAN_SECONDS = 3600;
    public static final int[] DEFAULT_COLORS = {0xD02020, 0x2050D0, 0x108010, 0x202020};

    public int pens = 1;
    public int spanSeconds = 300;
    public double min = 0.0;
    public double max = 100.0;
    public final int[] penColor = DEFAULT_COLORS.clone();

    private final long[][] times = new long[MAX_PENS][CAPACITY];
    private final float[][] values = new float[MAX_PENS][CAPACITY];
    private final int[] head = new int[MAX_PENS];
    private final int[] count = new int[MAX_PENS];
    /** True while {@code times} holds negative ages from a load, not yet turned into absolute ticks. */
    private boolean rebasePending;
    private long lastNow;

    public static List<ConfigField> schema() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.integer("Pens", "component.bwr.field.pens", 1, MAX_PENS));
        fields.add(ConfigField.integer("Span", "component.bwr.field.span", MIN_SPAN_SECONDS, MAX_SPAN_SECONDS));
        fields.add(ConfigField.decimal("Min", "component.bwr.field.min", -GaugeData.LIMIT, GaugeData.LIMIT));
        fields.add(ConfigField.decimal("Max", "component.bwr.field.max", -GaugeData.LIMIT, GaugeData.LIMIT));
        for (int i = 1; i <= MAX_PENS; i++) {
            fields.add(ConfigField.color("Pen" + i + "Color", "component.bwr.field.pen" + i + "_color"));
        }
        return List.copyOf(fields);
    }

    public long spanTicks() {
        return spanSeconds * 20L;
    }

    public double span() {
        return max > min ? max - min : 1.0;
    }

    /** Position of {@code value} on the scale, 0..1 (NaN: 0). */
    public double fraction(double value) {
        return Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min) / span()));
    }

    // ---- history

    /** Number of recorded points of a pen. */
    public int points(int pen) {
        return pen >= 0 && pen < MAX_PENS ? count[pen] : 0;
    }

    /** Game tick of point {@code k} (0 is the oldest) of a pen. Call {@link #rebase} first on a client. */
    public long time(int pen, int k) {
        return times[pen][(head[pen] + k) % CAPACITY];
    }

    /** Value of point {@code k} of a pen; NaN is a gap (no signal). */
    public float value(int pen, int k) {
        return values[pen][(head[pen] + k) % CAPACITY];
    }

    /** Turns ages restored by a load into absolute ticks relative to {@code now}. Cheap once done. */
    public void rebase(long now) {
        lastNow = now;
        if (!rebasePending) {
            return;
        }
        rebasePending = false;
        for (int pen = 0; pen < MAX_PENS; pen++) {
            for (int k = 0; k < count[pen]; k++) {
                times[pen][(head[pen] + k) % CAPACITY] += now;
            }
        }
    }

    /**
     * Records the pens' current inputs at the host's clock. Only pens whose value differs from their last point add
     * one. Called from {@code valuesChanged}, on both sides.
     */
    public void record(PanelComponent component) {
        long now = host() == null ? lastNow : host().gameTime();
        rebase(now);
        for (int pen = 0; pen < pens; pen++) {
            push(pen, now, (float) component.input(pen));
        }
    }

    /** Adds a point to a pen (see the class description for thinning). Public for tests. */
    public void push(int pen, long now, float value) {
        long bucket = Math.max(1L, spanTicks() / COLUMNS);
        int n = count[pen];
        if (n == 0 && Float.isNaN(value)) {
            return;
        }
        if (n > 0) {
            int last = (head[pen] + n - 1) % CAPACITY;
            if (same(values[pen][last], value)) {
                return;
            }
            if (n > 1 && times[pen][last] / bucket == now / bucket) {
                values[pen][last] = value;
                return;
            }
        }
        if (n == CAPACITY) {
            head[pen] = (head[pen] + 1) % CAPACITY;
            n--;
        }
        int slot = (head[pen] + n) % CAPACITY;
        times[pen][slot] = now;
        values[pen][slot] = value;
        n++;
        long windowStart = now - spanTicks();
        while (n >= 2 && times[pen][(head[pen] + 1) % CAPACITY] <= windowStart) {
            head[pen] = (head[pen] + 1) % CAPACITY;
            n--;
        }
        count[pen] = n;
    }

    private static boolean same(float a, float b) {
        return a == b || Float.isNaN(a) && Float.isNaN(b);
    }

    /** Forgets all history. */
    public void clearHistory() {
        for (int pen = 0; pen < MAX_PENS; pen++) {
            head[pen] = 0;
            count[pen] = 0;
        }
    }

    // ---- persistence

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Pens", pens);
        tag.putInt("Span", spanSeconds);
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        for (int i = 0; i < MAX_PENS; i++) {
            tag.putInt("Pen" + (i + 1) + "Color", penColor[i]);
        }
        long now = host() == null ? lastNow : host().gameTime();
        for (int pen = 0; pen < MAX_PENS; pen++) {
            int n = count[pen];
            int[] ages = new int[n];
            int[] bits = new int[n];
            for (int k = 0; k < n; k++) {
                int slot = (head[pen] + k) % CAPACITY;
                long age = rebasePending ? -times[pen][slot] : now - times[pen][slot];
                ages[k] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, age));
                bits[k] = Float.floatToRawIntBits(values[pen][slot]);
            }
            tag.put("Pen" + (pen + 1) + "Ages", new IntArrayTag(ages));
            tag.put("Pen" + (pen + 1) + "Values", new IntArrayTag(bits));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        pens = tag.contains("Pens", Tag.TAG_ANY_NUMERIC) ? Math.max(1, Math.min(MAX_PENS, tag.getInt("Pens"))) : 1;
        spanSeconds = tag.contains("Span", Tag.TAG_ANY_NUMERIC)
                ? Math.max(MIN_SPAN_SECONDS, Math.min(MAX_SPAN_SECONDS, tag.getInt("Span"))) : 300;
        min = GaugeData.number(tag, "Min", 0.0, -GaugeData.LIMIT, GaugeData.LIMIT);
        max = GaugeData.number(tag, "Max", 100.0, -GaugeData.LIMIT, GaugeData.LIMIT);
        for (int i = 0; i < MAX_PENS; i++) {
            String key = "Pen" + (i + 1) + "Color";
            penColor[i] = tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) & 0xFFFFFF : DEFAULT_COLORS[i];
        }
        clearHistory();
        for (int pen = 0; pen < MAX_PENS; pen++) {
            int[] ages = tag.getIntArray("Pen" + (pen + 1) + "Ages");
            int[] bits = tag.getIntArray("Pen" + (pen + 1) + "Values");
            int n = Math.min(CAPACITY, Math.min(ages.length, bits.length));
            for (int k = 0; k < n; k++) {
                times[pen][k] = -(long) Math.max(0, ages[k]);
                values[pen][k] = Float.intBitsToFloat(bits[k]);
            }
            count[pen] = n;
        }
        rebasePending = true;
    }
}
