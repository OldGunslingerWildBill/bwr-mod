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
            Footprint.of(48, 4), Footprint.of(64, 4), Footprint.of(24, 6), Footprint.of(32, 8),
            // valve plates: up to three lines over a bottom band with corner fields, tag and dot
            Footprint.of(32, 10), Footprint.of(24, 8), Footprint.of(32, 12));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("Text", "component.bwr.field.text", NameplateData.MAX_TEXT),
            ConfigField.text("Text2", "component.bwr.field.text_line_2", NameplateData.MAX_TEXT),
            ConfigField.text("Text3", "component.bwr.field.text_line_3", NameplateData.MAX_TEXT),
            ConfigField.text("CornerLeft", "component.bwr.field.corner_left", NameplateData.MAX_CORNER),
            ConfigField.text("CornerRight", "component.bwr.field.corner_right", NameplateData.MAX_CORNER),
            ConfigField.text("Tag", "component.bwr.field.tag_text", NameplateData.MAX_TAG),
            ConfigField.color("TagColor", "component.bwr.field.tag_color"),
            ConfigField.bool("Dot", "component.bwr.field.dot_show"),
            ConfigField.color("DotColor", "component.bwr.field.dot_color"),
            ConfigField.bool("Stripe", "component.bwr.field.stripe_show"),
            ConfigField.color("StripeColor", "component.bwr.field.stripe_color"),
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
