package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Push-to-turn switch, the owner's red round knob of the ADS manual activation (AUTO on the left, ARM on the right).
 * The knob turns only while it is pushed in: a plain click pushes it in and nothing else, and turning it is
 * push-and-turn.
 *
 * <h3>Operating it</h3>
 * Hold the use key (right-click) on the knob to push it in; it stays in while the key is down and the cap slides
 * back when the key is let go (the same hold fail-safe as a pushbutton). <b>While it is held in, scroll the mouse
 * wheel over it to turn it</b>: up turns it to the second legend (ARM), down to the first (AUTO). This is the way every
 * other panel input turns (selectors and toggles scroll too), only here the wheel works while the knob is pushed in.
 * Scrolling without pushing is refused with a hint on the action bar. A knob configured with a spring return goes
 * back to AUTO when it is let go.
 *
 * <h3>Outputs</h3>
 * "position" is 0 (AUTO) or 1 (ARM) and stays where it was left, including across a restart. "pushed" is 1 while the
 * knob is held in. Players wire them as they like, for example both ADS switches' positions to the ADS controller's
 * {@code ads_activate_a} and {@code ads_activate_b}; nothing here acts on its own.
 */
public final class PushTurnSwitchType extends PanelInputType {
    public static final String ID_PATH = "push_turn_switch";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(8, 8), Footprint.of(12, 12), Footprint.of(16, 16));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.bool("Spring", "component.bwr.field.spring_return"),
            ConfigField.text("Legend1", "component.bwr.field.legend_1", PushTurnData.MAX_LEGEND),
            ConfigField.text("Legend2", "component.bwr.field.legend_2", PushTurnData.MAX_LEGEND));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("position"), PortSpec.of("pushed"));

    PushTurnSwitchType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new PushTurnData();
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
        return "PT";
    }

    @Override
    public boolean holdable() {
        return true;
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    @Override
    public void onLoaded(PanelComponent component) {
        component.storeOutput(0, ((PushTurnData) component.data()).position);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        PushTurnData data = (PushTurnData) component.data();
        switch (action) {
            case PRESS -> {
                // A click pushes the knob in and does nothing else, whichever half it landed on.
                context.setOutput(1, 1.0);
                armFailsafe(context);
                return true;
            }
            case RELEASE -> {
                disarm(context);
                context.setOutput(1, 0.0);
                if (data.spring && data.position != 0) {
                    turn(context, data, 0);
                }
                return true;
            }
            case TURN, SET -> {
                if (component.output(1) < 0.5) {
                    // Turned without being pushed in: it does not move. Say why.
                    if (context.player() != null) {
                        context.player().displayClientMessage(Component.translatable("message.bwr.panel.push_to_turn"), true);
                    }
                    return false;
                }
                int next = action == OperateAction.SET ? (value >= 0.5 ? 1 : 0) : step(value) > 0 ? 1 : step(value) < 0 ? 0 : data.position;
                if (next != data.position) {
                    turn(context, data, next);
                }
                armFailsafe(context);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void turn(OperateContext context, PushTurnData data, int position) {
        data.position = position;
        markSaved(context);
        context.setOutput(0, position);
    }
}
