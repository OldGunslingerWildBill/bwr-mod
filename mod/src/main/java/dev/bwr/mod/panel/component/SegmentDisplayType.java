package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.ArrayList;
import java.util.List;

/**
 * The 7-segment display and the 16-segment alphanumeric display (spec 2.1):
 * 5x8 cells per digit, 1 to 12 digits (so the footprint list is the digit
 * counts), amber by default. They share this class and {@link SegmentDisplayData};
 * the 7-segment shows digits and the letters a seven-segment digit can form, the
 * 16-segment full ASCII. Inputs {@code v0..v3} feed the format string.
 */
public final class SegmentDisplayType extends ComponentType {
    /** Cells one digit occupies across and down. */
    public static final int DIGIT_W = 5;
    public static final int DIGIT_H = 8;
    private static final int[] DIGIT_COUNTS = {4, 1, 2, 3, 5, 6, 8, 10, 12};
    /** A real size display (shorter than one big digit) is one cell wide and two tall per digit. */
    public static final int COMPACT_DIGIT_W = 1;
    public static final int COMPACT_DIGIT_H = 2;
    /** Digits of the real size displays, the first being where a new item starts: a 4 digit panel meter window. */
    private static final int[] COMPACT_DIGIT_COUNTS = {4, 1, 2, 3, 6, 8};
    /** Twice the real size, where a new item starts: 4 digits, each 2 cells wide and 4 tall. */
    private static final Footprint DOUBLE_DEFAULT = Footprint.of(4 * 2 * COMPACT_DIGIT_W, 2 * COMPACT_DIGIT_H);
    private static final List<ConfigField> SCHEMA = SegmentDisplayData.schema();
    private static final List<PortSpec> INPUTS = List.of(
            PortSpec.of("v0"), PortSpec.of("v1"), PortSpec.of("v2"), PortSpec.of("v3"));

    private final boolean sixteen;
    private final List<Footprint> footprints;

    SegmentDisplayType(boolean sixteen) {
        super(BwrMod.id(sixteen ? "sixteen_segment_display" : "seven_segment_display"));
        this.sixteen = sixteen;
        List<Footprint> list = new ArrayList<>();
        for (int digits : DIGIT_COUNTS) {
            list.add(Footprint.of(digits * DIGIT_W, DIGIT_H));
        }
        for (int digits : COMPACT_DIGIT_COUNTS) {
            list.add(Footprint.of(digits * COMPACT_DIGIT_W, COMPACT_DIGIT_H));
        }
        list.add(DOUBLE_DEFAULT);
        this.footprints = List.copyOf(list);
    }

    public boolean sixteen() {
        return sixteen;
    }

    /** Cells one digit is across on a display {@code cellsHigh} cells tall: five for the big digits, one for a real size one, two on the doubled one (a digit is half as wide as it is tall). */
    public static int digitWidth(int cellsHigh) {
        return cellsHigh >= DIGIT_H ? DIGIT_W : Math.max(COMPACT_DIGIT_W, cellsHigh / COMPACT_DIGIT_H * COMPACT_DIGIT_W);
    }

    @Override
    public int realSizes() {
        return COMPACT_DIGIT_COUNTS.length + 1;
    }

    /** A new item starts at twice the real size, in each dimension. */
    @Override
    public Footprint realDefaultFootprint() {
        return DOUBLE_DEFAULT;
    }

    @Override
    public List<Footprint> footprints() {
        return footprints;
    }

    @Override
    public ComponentData createData() {
        return new SegmentDisplayData(sixteen);
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public void valuesChanged(PanelComponent component) {
        ((SegmentDisplayData) component.data()).invalidate();
    }

    @Override
    public void onLoaded(PanelComponent component) {
        ((SegmentDisplayData) component.data()).invalidate();
    }

    @Override
    public String labelPrefix() {
        return sixteen ? "AD" : "SD";
    }
}
