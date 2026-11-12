package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Annunciator window, 4x3 (spec 2.1): a backlit tile with up to four engraved lines; windows that touch draw as one
 * grouped box (the client's {@code AnnunciatorBoxes}).
 * The alarm condition (input above / below / equal to a setpoint) is display
 * behaviour only; the sequence is ISA-18.1 A, see {@link AnnunciatorLogic}.
 * Acknowledge and reset come from a pushbutton configured with that role for the
 * same group number ({@link AnnunciatorGroups}).
 *
 * <p>The state machine runs on both sides from the same input deltas, so the
 * flashing needs no packets of its own; the latched state is part of the
 * full-state sync and the save.
 */
public final class AnnunciatorType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(12, 8), Footprint.of(16, 12),
            // real sizes: one window tile, 2.8125 x 2.625 in, and a double size one (both retired)
            Footprint.of(1, 1), Footprint.of(2, 2),
            // alpha.60 size table: 8x6, where a new item starts, and 8x8
            Footprint.of(8, 6), Footprint.of(8, 8),
            // half height windows: one alarm of a split tile (the Columbia style pairs two, one above the other, into a
            // tile of 8x6, 12x8 or 16x12)
            Footprint.of(8, 3), Footprint.of(12, 4), Footprint.of(16, 6));
    /** Retired: 1x1 and 2x2. */
    private static final java.util.Set<Integer> RETIRED = java.util.Set.of(2, 3);
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("Line1", "component.bwr.field.line1", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line2", "component.bwr.field.line2", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line3", "component.bwr.field.line3", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line4", "component.bwr.field.line4", AnnunciatorData.MAX_TEXT),
            ConfigField.color("Color", "component.bwr.field.tile_color", List.of(
                    ConfigField.swatch("white", AnnunciatorData.COLOR_WHITE),
                    ConfigField.swatch("yellow", AnnunciatorData.COLOR_YELLOW),
                    ConfigField.swatch("red", AnnunciatorData.COLOR_RED))),
            ConfigField.choice(PanelStyles.KEY_STYLE, "component.bwr.field.style", AnnunciatorData.STYLES),
            ConfigField.text(AnnunciatorData.KEY_BOX_NAME, "component.bwr.field.box_name", AnnunciatorData.MAX_TEXT),
            ConfigField.choice("Condition", "component.bwr.field.condition", AnnunciatorData.CONDITIONS),
            ConfigField.decimal("Setpoint", "component.bwr.field.setpoint", -AnnunciatorData.LIMIT, AnnunciatorData.LIMIT),
            ConfigField.integer("AnnGroup", "component.bwr.field.ann_group", AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP),
            ConfigField.choice("Reset", "component.bwr.field.reset_mode", AnnunciatorData.RESET_MODES),
            // Text, not an integer: an integer field reads a missing key as 0, which would give every window address 0.
            ConfigField.text(AnnunciatorData.KEY_ADDRESS, "component.bwr.field.light_address", 5),
            ConfigField.choice(AnnunciatorData.KEY_SOUND, "component.bwr.field.alarm_sound", AnnunciatorSounds.CHOICES),
            ConfigField.text(AnnunciatorData.KEY_CUSTOM_SOUND, "component.bwr.field.custom_sound", AnnunciatorSounds.MAX_ID));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("input"));

    AnnunciatorType() {
        super(BwrMod.id("annunciator"));
    }

    @Override
    public int realSizes() {
        return 2;
    }

    /** A new item starts at 8x6 (alpha.60 size table). */
    @Override
    public Footprint realDefaultFootprint() {
        return Footprint.of(8, 6);
    }

    /**
     * Sizes added after the real sizes (the two of the alpha.60 size table, then the three half height windows); see
     * {@link ComponentType#appendedSizes()}.
     */
    @Override
    public int appendedSizes() {
        return 5;
    }

    /** Retired sizes, by index into {@link #FOOTPRINTS}: still placeable from an old stack or a saved part, no longer offered ({@link Footprints}). */
    @Override
    public java.util.Set<Integer> retiredFootprints() {
        return RETIRED;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new AnnunciatorData();
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
    public String labelPrefix() {
        return "AN";
    }

    @Override
    public void valuesChanged(PanelComponent component) {
        evaluate(component);
    }

    /** A config edit may have moved the setpoint: judge the current input again. */
    @Override
    public void onLoaded(PanelComponent component) {
        evaluate(component);
    }

    private static void evaluate(PanelComponent component) {
        double input = component.input(0);
        if (Double.isNaN(input)) {
            return; // no signal holds the state
        }
        AnnunciatorData data = (AnnunciatorData) component.data();
        if (data.logic.update(data.alarming(input))) {
            data.latchedChanged();
        }
    }
}
