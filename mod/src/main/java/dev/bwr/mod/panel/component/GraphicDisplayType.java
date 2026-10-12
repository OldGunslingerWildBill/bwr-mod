package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Graphic display (spec 2.1): a pixel framebuffer, 32 pixels per block edge,
 * 16 palette colours, from 1x1 to 4x4 blocks (16..64 cells per side). It has no
 * inputs and no outputs: its picture comes from a CC program through the
 * drawing API of {@link GraphicDisplayData}.
 */
public final class GraphicDisplayType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(32, 32), Footprint.of(16, 16), Footprint.of(48, 48), Footprint.of(64, 64),
            Footprint.of(32, 16), Footprint.of(64, 32), Footprint.of(48, 32), Footprint.of(16, 32),
            Footprint.of(32, 64), Footprint.of(64, 48));
    private static final List<ConfigField> SCHEMA = GraphicDisplayData.schema();

    GraphicDisplayType() {
        super(BwrMod.id("graphic_display"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new GraphicDisplayData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public String labelPrefix() {
        return "GD";
    }
}
