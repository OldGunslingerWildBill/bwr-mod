package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Thumbwheel, 3x6 cells per digit (spec 2.1): the footprint's width is
 * three cells per digit (1 to 8 digits), the output "value" is the number shown. Clicking
 * the top half of a digit raises it and the bottom half lowers it (0 to 9 and
 * round again, no carry, like a real wheel); scrolling over a digit does the
 * same. SET writes the whole number.
 *
 * <p>TURN and PRESS carry the digit as {@code +-(index + 1)}, index 0 being the
 * leftmost digit, the sign being the direction.
 */
public final class ThumbwheelType extends PanelInputType {
    /** Cells one digit occupies across and down. */
    public static final int DIGIT_W = 3;
    public static final int DIGIT_H = 6;
    /** Cells one digit of a real size thumbwheel (shorter than {@link #DIGIT_H}) is across and down. */
    public static final int COMPACT_DIGIT_W = 1;
    public static final int COMPACT_DIGIT_H = 2;
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(3 * DIGIT_W, DIGIT_H), Footprint.of(DIGIT_W, DIGIT_H), Footprint.of(2 * DIGIT_W, DIGIT_H),
            Footprint.of(4 * DIGIT_W, DIGIT_H), Footprint.of(5 * DIGIT_W, DIGIT_H), Footprint.of(6 * DIGIT_W, DIGIT_H),
            Footprint.of(7 * DIGIT_W, DIGIT_H), Footprint.of(8 * DIGIT_W, DIGIT_H),
            // real sizes: one digit is one cell wide and two tall; the first is a 3 digit setpoint
            Footprint.of(3 * COMPACT_DIGIT_W, COMPACT_DIGIT_H), Footprint.of(COMPACT_DIGIT_W, COMPACT_DIGIT_H),
            Footprint.of(2 * COMPACT_DIGIT_W, COMPACT_DIGIT_H), Footprint.of(4 * COMPACT_DIGIT_W, COMPACT_DIGIT_H),
            Footprint.of(5 * COMPACT_DIGIT_W, COMPACT_DIGIT_H), Footprint.of(6 * COMPACT_DIGIT_W, COMPACT_DIGIT_H),
            Footprint.of(8 * COMPACT_DIGIT_W, COMPACT_DIGIT_H));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.color("Color", "component.bwr.field.digit_color"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("value"));

    ThumbwheelType() {
        super(BwrMod.id("thumbwheel"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new ThumbwheelData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> outputPorts(ComponentData data) {
        return OUTPUTS;
    }

    @Override
    public String labelPrefix() {
        return "TW";
    }

    /** Cells one digit is across on a thumbwheel {@code cellsHigh} cells tall: three, or one on a real size one. */
    public static int digitWidth(int cellsHigh) {
        return cellsHigh >= DIGIT_H ? DIGIT_W : COMPACT_DIGIT_W;
    }

    @Override
    public int realSizes() {
        return 7;
    }

    /** Number of digits: the width in whole digit cells, at least one. */
    public static int digits(PanelComponent component) {
        return Math.max(1, Math.min(ThumbwheelData.MAX_DIGITS, component.w() / digitWidth(component.h())));
    }

    private static int digitAt(PanelComponent component, double cellX) {
        return Math.max(0, Math.min(digits(component) - 1, (int) Math.floor(cellX / digitWidth(component.h()))));
    }

    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        int digit = digitAt(component, cellX);
        return cellY < component.h() / 2.0 ? digit + 1 : -(digit + 1);
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    @Override
    public double scrollValue(PanelComponent component, double cellX, double cellY, int notches) {
        int digit = digitAt(component, cellX);
        return notches > 0 ? digit + 1 : -(digit + 1);
    }

    @Override
    public void onLoaded(PanelComponent component) {
        ThumbwheelData data = (ThumbwheelData) component.data();
        data.value = Math.min(data.value, ThumbwheelData.limit(digits(component)) - 1);
        component.storeOutput(0, data.value);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        ThumbwheelData data = (ThumbwheelData) component.data();
        int digits = digits(component);
        long limit = ThumbwheelData.limit(digits);
        switch (action) {
            case PRESS, TURN -> {
                int code = (int) Math.round(Math.abs(value));
                if (code < 1 || code > digits) {
                    return false;
                }
                int index = code - 1;
                int current = ThumbwheelData.digit(data.value, digits, index);
                int next = (current + (value > 0 ? 1 : 9)) % 10;
                data.value += (long) (next - current) * ThumbwheelData.limit(digits - 1 - index);
            }
            case SET -> data.value = Math.max(0L, Math.min(limit - 1, Math.round(value)));
            default -> {
                return false;
            }
        }
        markSaved(context);
        context.setOutput(0, data.value);
        return true;
    }
}
