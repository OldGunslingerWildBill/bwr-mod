package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;

/**
 * The two pen recorder's data: a {@link StripChartData} that always has exactly two pens, red and green by default, and a
 * span of its own for the second pen (a real two pen recorder reads two different quantities, such as two IRM ranges).
 * Pen 1 uses {@code Min} and {@code Max}, pen 2 {@code Min2} and {@code Max2}. The history, its thinning, saving and syncing are the strip
 * chart's own.
 */
public final class TwoPenRecorderData extends StripChartData {
    public static final int PEN_1 = 0xD02020;
    public static final int PEN_2 = 0x108010;

    public double min2 = 0.0;
    public double max2 = 100.0;

    public TwoPenRecorderData() {
        pens = 2;
        penColor[0] = PEN_1;
        penColor[1] = PEN_2;
    }

    /** Position of {@code value} on pen {@code pen}'s own scale, 0..1 (NaN: 0). */
    public double fraction(int pen, double value) {
        if (pen == 0) {
            return fraction(value);
        }
        double span = max2 > min2 ? max2 - min2 : 1.0;
        return Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min2) / span));
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        tag.putDouble("Min2", min2);
        tag.putDouble("Max2", max2);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        pens = 2;
        if (!tag.contains("Pen1Color")) {
            penColor[0] = PEN_1;
        }
        if (!tag.contains("Pen2Color")) {
            penColor[1] = PEN_2;
        }
        min2 = GaugeData.number(tag, "Min2", 0.0, -GaugeData.LIMIT, GaugeData.LIMIT);
        max2 = GaugeData.number(tag, "Max2", 100.0, -GaugeData.LIMIT, GaugeData.LIMIT);
    }
}
