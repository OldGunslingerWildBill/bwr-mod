package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.reactor.LprmReadout;

import java.util.List;

/**
 * The full core display (Columbia lamp matrix or Browns Ferry digital module panel): a read-only picture of the whole
 * core, one rod at a time and one LPRM string at a time, from one binding to the reactor controller. See
 * {@link CoreDisplayData}. It has no outputs and nothing to operate.
 */
public final class FullCoreDisplayType extends CoreDisplayType {
    /** Sizes in cells: a 185-rod core is 96 x 96 as Columbia at 3 cells per tile, up to 128 x 77 as Browns Ferry. */
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(96, 96), Footprint.of(128, 128), Footprint.of(64, 64), Footprint.of(80, 80), Footprint.of(112, 112),
            Footprint.of(48, 48), Footprint.of(128, 96), Footprint.of(128, 80), Footprint.of(112, 80));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.choice(FullCoreDisplayData.KEY_STYLE, "component.bwr.field.fcd_style", FullCoreDisplayData.STYLES),
            ConfigField.integer("TileCells", "component.bwr.field.tile_cells", FcdLayout.MIN_TILE, FcdLayout.MAX_TILE),
            ConfigField.text("Caption", "component.bwr.field.caption", CoreDisplayData.MAX_CAPTION),
            ConfigField.choice("EdgeNumbers", "component.bwr.field.edge_numbers", FullCoreDisplayData.EDGE_MODES),
            ConfigField.bool("CoordLegends", "component.bwr.field.coord_legends"),
            ConfigField.decimal("AccumFraction", "component.bwr.field.accum_fraction", 0.1, 1.0),
            ConfigField.decimal("Downscale", "component.bwr.field.lprm_downscale", 0.0, 100.0),
            ConfigField.decimal("Upscale", "component.bwr.field.lprm_upscale", 0.0, 200.0));

    FullCoreDisplayType() {
        super(BwrMod.id("full_core_display"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new FullCoreDisplayData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public String labelPrefix() {
        return "FCD";
    }

    /** Not operable: a display. The framework's input handling skips it. */
    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        return false;
    }

    /** The defaults the schema documents, for tests. */
    public static double defaultDownscale() {
        return LprmReadout.DEFAULT_DOWNSCALE_PERCENT;
    }
}
