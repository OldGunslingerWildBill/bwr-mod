package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * RPS trip assembly, the owner's guarded pair of red trip buttons for the RPS A and B channels. Two outputs, "a"
 * and "b", each momentary like a pushbutton: 1 while that button is held. The click position picks the button (the
 * left half is A, the right half B); the cap slides in while it is held, and the same fail-safe that lets go of a
 * pushbutton whose player dropped off the network lets go of this one ({@link PanelInputType#HOLD_FAILSAFE_TICKS}).
 *
 * <p>Nothing here scrams anything. Players wire each output to the reactor's manual scram or an RPS channel command
 * themselves (a panel binding), or read it from a program. Holding one button and moving the pointer to the other
 * lets go of the first.
 */
public final class RpsTripType extends PanelInputType {
    public static final String ID_PATH = "rps_trip";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(16, 8), Footprint.of(24, 12), Footprint.of(32, 16));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("LegendA", "component.bwr.field.legend_a", RpsTripData.MAX_LEGEND),
            ConfigField.text("LegendB", "component.bwr.field.legend_b", RpsTripData.MAX_LEGEND));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("a"), PortSpec.of("b"));

    RpsTripType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new RpsTripData();
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
        return "RPS";
    }

    @Override
    public boolean holdable() {
        return true;
    }

    /** Which button a click at {@code cellX} cells from the left edge lands on: -1 for A (left half), +1 for B. */
    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return cellX < component.w() / 2.0 ? -1.0 : 1.0;
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        switch (action) {
            case PRESS -> {
                if (value == 0.0) {
                    return false;
                }
                int pressed = value < 0 ? 0 : 1;
                context.setOutput(1 - pressed, 0.0);
                context.setOutput(pressed, 1.0);
                armFailsafe(context);
                return true;
            }
            case RELEASE -> {
                disarm(context);
                context.setOutput(0, 0.0);
                context.setOutput(1, 0.0);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
