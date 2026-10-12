package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Character LCD (spec 2.1): clock-style, 1 to 4 rows of N characters in a 5x7
 * dot matrix with a backlight colour. One character is one cell wide and one
 * row is two cells tall, so the footprint is {@code (chars) x (2 * rows)}.
 * Shows its format string (inputs {@code v0..v3}) or what a CC program wrote.
 */
public final class LcdType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS;

    static {
        List<Footprint> list = new ArrayList<>();
        for (int rows : new int[] {2, 1, 3, 4}) {
            for (int chars : new int[] {16, 20, 8, 24, 32, 40}) {
                list.add(Footprint.of(chars, rows * 2));
            }
        }
        FOOTPRINTS = List.copyOf(list);
    }

    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("Format", "component.bwr.field.lcd_format", LcdData.MAX_FORMAT),
            ConfigField.color("Backlight", "component.bwr.field.backlight"),
            ConfigField.color("Ink", "component.bwr.field.dot_color"));
    private static final List<PortSpec> INPUTS = List.of(
            PortSpec.of("v0"), PortSpec.of("v1"), PortSpec.of("v2"), PortSpec.of("v3"));

    LcdType() {
        super(BwrMod.id("character_lcd"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new LcdData();
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
        ((LcdData) component.data()).invalidate();
    }

    @Override
    public void onLoaded(PanelComponent component) {
        ((LcdData) component.data()).invalidate();
    }

    @Override
    public String labelPrefix() {
        return "LC";
    }
}
