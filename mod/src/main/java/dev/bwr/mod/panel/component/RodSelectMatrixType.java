package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.fcd.FcdLayout;

import java.util.List;

/**
 * The rod select matrix (Columbia benchboard): one square illuminated pushbutton per rod in core layout. Pressing a
 * button sets the output {@code rod} to that rod's number and sends it through the output's sinks every time, so a
 * plant command sink on the reactor's {@code select_rod} selects the rod; the button of the reactor's selected rod
 * lights white. It moves nothing. The rod motion controls are ordinary pushbuttons wired to the
 * {@code selected_rod_*} commands.
 */
public final class RodSelectMatrixType extends CoreDisplayType {
    /** A 185-rod core is 51 x 51 cells at 3 cells per button. */
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(64, 64), Footprint.of(48, 48), Footprint.of(80, 80), Footprint.of(96, 96), Footprint.of(112, 112),
            Footprint.of(128, 128), Footprint.of(32, 32));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.integer("TileCells", "component.bwr.field.tile_cells", FcdLayout.MIN_TILE, FcdLayout.MAX_TILE));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("rod"));

    RodSelectMatrixType() {
        super(BwrMod.id("rod_select_matrix"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new RodSelectMatrixData();
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
        return "RSM";
    }

    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return ((RodSelectMatrixData) component.data()).rodAt(component.w(), component.h(), cellX, cellY);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        if (action != OperateAction.PRESS) {
            return action == OperateAction.RELEASE;
        }
        PanelComponent component = context.component();
        RodSelectMatrixData data = (RodSelectMatrixData) component.data();
        int rod = (int) Math.round(value);
        dev.bwr.mod.reactor.CoreRodMap map = data.snapshot().cells().map();
        if (map == null || !data.snapshot().formed() || rod < 1 || rod > map.rodCount()) {
            return false;
        }
        data.lastPressed = rod;
        markSaved(context);
        // Every press reaches the sinks, also a press of the rod that is already the output's value.
        context.owner().pressOutput(component, 0, rod);
        return true;
    }

    @Override
    public void onLoaded(PanelComponent component) {
        component.storeOutput(0, ((RodSelectMatrixData) component.data()).lastPressed);
    }
}
