package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Thumbwheel, 1x2 cells per digit (spec 2.1): the footprint's width is the
 * number of digits (1 to 8), the output "value" is the number shown. Clicking
 * the top half of a digit raises it and the bottom half lowers it (0 to 9 and
 * round again, no carry, like a real wheel); scrolling over a digit does the
 * same. SET writes the whole number.
 *
 * <p>TURN and PRESS carry the digit as {@code +-(index + 1)}, index 0 being the
 * leftmost digit, the sign being the direction.
 */
public final class ThumbwheelType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(3, 2), Footprint.of(1, 2), Footprint.of(2, 2), Footprint.of(4, 2), Footprint.of(5, 2),
            Footprint.of(6, 2), Footprint.of(7, 2), Footprint.of(8, 2));
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

    private static int digitAt(PanelComponent component, double cellX) {
        return Math.max(0, Math.min(component.w() - 1, (int) Math.floor(cellX)));
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
        data.value = Math.min(data.value, ThumbwheelData.limit(component.w()) - 1);
        component.storeOutput(0, data.value);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        ThumbwheelData data = (ThumbwheelData) component.data();
        int digits = component.w();
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
