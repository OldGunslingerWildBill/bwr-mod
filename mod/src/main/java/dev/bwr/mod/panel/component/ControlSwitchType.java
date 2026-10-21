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
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(8, 12), Footprint.of(12, 16),
            // real sizes: a breaker control switch escutcheon, about 6 x 9 cm, and the model's own 4.4 x 6.6 in
            Footprint.of(1, 2), Footprint.of(2, 3));
    private static final List<ConfigField> SCHEMA = PanelStyles.withLook(ControlSwitchData.TYPE, List.of(
            ConfigField.bool("Spring", "component.bwr.field.spring"),
            ConfigField.text("CloseText", "component.bwr.field.close_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("NormalText", "component.bwr.field.normal_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("OpenText", "component.bwr.field.open_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("RedText", "component.bwr.field.red_text", ControlSwitchData.MAX_TEXT),
            ConfigField.text("GreenText", "component.bwr.field.green_text", ControlSwitchData.MAX_TEXT),
            ConfigField.choice("FlagRed", "component.bwr.field.flag_red", ControlSwitchData.FLAG_MODES)));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("red"), PortSpec.of("green"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("position"), PortSpec.of("open"), PortSpec.of("close"));

    ControlSwitchType() {
        super(BwrMod.id("control_switch"));
    }

    @Override
    public int realSizes() {
        return 2;
    }

    /**
     * Not twice the real size like the other types: the owner placed the 8 x 12 and the 12 x 16 GE switch side by side and
     * found the smaller exactly the right size (the larger is for other panels), so a new item starts at 8 x 12, the
     * first size of the list ({@link ComponentType#realDefaultFootprint}). The real sizes stay on the list.
     */
    @Override
    public Footprint realDefaultFootprint() {
        return FOOTPRINTS.get(0);
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
        } else if (data.position != ControlSwitchData.NORMAL && data.lastOperated == ControlSwitchData.NORMAL) {
            data.lastOperated = data.position; // a held-position switch saved before the latch existed
        }
        publish(component, data.position);
    }

    private static void publish(PanelComponent component, int position) {
        component.storeOutput(0, position);
        component.storeOutput(1, position > 0 ? 1.0 : 0.0);
        component.storeOutput(2, position < 0 ? 1.0 : 0.0);
    }

    /**
     * Both sides, on every output change: latches the last position that was not NORMAL. The server runs this
     * from {@code setOutput} and the client from the output delta, so the two stay in step without a full sync;
     * a late joiner gets the latched value in the saved data.
     */
    @Override
    public void valuesChanged(PanelComponent component) {
        if (component.data() instanceof ControlSwitchData data) {
            int position = (int) Math.round(component.output(0));
            if (position != ControlSwitchData.NORMAL && position != data.lastOperated
                    && position >= ControlSwitchData.CLOSE && position <= ControlSwitchData.OPEN) {
                data.lastOperated = position;
            }
        }
    }

    private static void move(OperateContext context, ControlSwitchData data, int position) {
        int lastOperated = data.lastOperated;
        data.position = position;
        if (!data.spring) {
            markSaved(context);
        }
        context.setOutput(0, position);
        context.setOutput(1, position > 0 ? 1.0 : 0.0);
        context.setOutput(2, position < 0 ? 1.0 : 0.0);
        if (data.lastOperated != lastOperated) {
            markSaved(context); // the target flag's latch changed, whether or not the handle springs back
        }
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
