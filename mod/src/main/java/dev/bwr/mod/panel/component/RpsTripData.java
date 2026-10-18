package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;

/**
 * RPS trip assembly configuration: the two engraved legends over the A and B trip buttons. No latched state: a
 * button is 1 only while held. A legend is saved only when it differs from its default, so a bare tag (a save that
 * never set one) loads and re-saves unchanged.
 */
public final class RpsTripData extends ComponentData {
    /** Longest legend, characters. */
    public static final int MAX_LEGEND = 16;
    public static final String DEFAULT_LEGEND_A = "RPS A TRIP";
    public static final String DEFAULT_LEGEND_B = "RPS B TRIP";

    public String legendA = DEFAULT_LEGEND_A;
    public String legendB = DEFAULT_LEGEND_B;

    @Override
    public void save(CompoundTag tag) {
        if (!legendA.equals(DEFAULT_LEGEND_A)) {
            tag.putString("LegendA", legendA);
        }
        if (!legendB.equals(DEFAULT_LEGEND_B)) {
            tag.putString("LegendB", legendB);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        legendA = tag.contains("LegendA") ? ConfigSchema.cleanText(tag.getString("LegendA"), MAX_LEGEND) : DEFAULT_LEGEND_A;
        legendB = tag.contains("LegendB") ? ConfigSchema.cleanText(tag.getString("LegendB"), MAX_LEGEND) : DEFAULT_LEGEND_B;
    }
}
