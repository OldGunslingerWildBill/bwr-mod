package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Engraved label plate, 4 cells tall and W wide (spec 2.1). The smallest useful
 * component: it has no inputs, no outputs and no interaction, and exists to
 * prove placement, config, saves and drawing.
 */
public final class NameplateType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(16, 4), Footprint.of(8, 4), Footprint.of(24, 4), Footprint.of(32, 4),
            Footprint.of(48, 4), Footprint.of(64, 4), Footprint.of(24, 6), Footprint.of(32, 8));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("Text", "component.bwr.field.text", NameplateData.MAX_TEXT),
            ConfigField.color("Plate", "component.bwr.field.plate_color"),
            ConfigField.color("Ink", "component.bwr.field.text_color"));

    NameplateType() {
        super(BwrMod.id("nameplate"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new NameplateData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public String labelPrefix() {
        return "NP";
    }
}
