package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * GE style pistol-grip control switch, 3x4 (spec 2.1): CLOSE / NORMAL / OPEN
 * with spring return to NORMAL, a red lamp (open / running) and a green lamp
 * (closed / stopped). The lamps are driven by the two inputs, so they can show
 * the real state of the device the switch commands rather than the handle
 * position; each carries a caption so the colour is not the only cue.
 *
 * <p>Outputs: "position" (-1 CLOSE, 0 NORMAL, +1 OPEN), plus "open" and
 * "close", each 1 only while the handle is in that position, which map onto
 * boolean commands without any logic.
 *
 * <p>Operation: holding the use key on the left third of the switch holds it
 * at CLOSE, the right third at OPEN; letting go springs it back to NORMAL
 * (the vanilla fallback holds for {@value #PULSE_TICKS} ticks). Scrolling
 * turns the handle one position. With the spring off the handle stays where it
 * was put, including across a restart.
 */
public final class ControlSwitchType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(3, 4));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.bool("Spring", "component.bwr.field.spring"),
            ConfigField.text("CloseText", "component.bwr.field.close_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("NormalText", "component.bwr.field.normal_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("OpenText", "component.bwr.field.open_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("RedText", "component.bwr.field.red_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("GreenText", "component.bwr.field.green_text", ControlSwitchData.MAX_TEXT));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("red"), PortSpec.of("green"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("position"), PortSpec.of("open"), PortSpec.of("close"));

    ControlSwitchType() {
        super(BwrMod.id("control_switch"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new ControlSwitchData();
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
    public List<PortSpec> outputPorts(ComponentData data) {
        return OUTPUTS;
    }

    @Override
    public String labelPrefix() {
        return "CS";
    }

    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        double third = component.w() / 3.0;
        return cellX < third ? -1.0 : cellX > 2.0 * third ? 1.0 : 0.0;
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    @Override
    public boolean holdable() {
        return true;
    }

    @Override
    public void onLoaded(PanelComponent component) {
        ControlSwitchData data = (ControlSwitchData) component.data();
        if (data.spring) {
            data.position = ControlSwitchData.NORMAL;
        }
        publish(component, data.position);
    }

    private static void publish(PanelComponent component, int position) {
        component.storeOutput(0, position);
        component.storeOutput(1, position > 0 ? 1.0 : 0.0);
        component.storeOutput(2, position < 0 ? 1.0 : 0.0);
    }

    private static void move(OperateContext context, ControlSwitchData data, int position) {
        data.position = position;
        if (!data.spring) {
            markSaved(context);
        }
        context.setOutput(0, position);
        context.setOutput(1, position > 0 ? 1.0 : 0.0);
        context.setOutput(2, position < 0 ? 1.0 : 0.0);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        ControlSwitchData data = (ControlSwitchData) context.component().data();
        switch (action) {
            case PRESS -> {
                move(context, data, step(value));
                if (data.spring) {
                    armFailsafe(context);
                }
                return true;
            }
            case TURN -> {
                move(context, data, Math.max(ControlSwitchData.CLOSE, Math.min(ControlSwitchData.OPEN, data.position + step(value))));
                if (data.spring && data.position != ControlSwitchData.NORMAL) {
                    // A scroll is a single flick: the spring takes it back after a moment.
                    dev.bwr.mod.panel.PanelTimers.schedule(context.level(), context.owner().getBlockPos(),
                            context.component().id(), PULSE_TICKS);
                }
                return true;
            }
            case SET -> {
                move(context, data, clampInt(Math.round(value), ControlSwitchData.CLOSE, ControlSwitchData.OPEN));
                if (data.spring && data.position != ControlSwitchData.NORMAL) {
                    armFailsafe(context);
                }
                return true;
            }
            case RELEASE -> {
                disarm(context);
                if (data.spring && data.position != ControlSwitchData.NORMAL) {
                    move(context, data, ControlSwitchData.NORMAL);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
