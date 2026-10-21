package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Annunciator control module: the ACK, RESET, SILENCE and TEST pushbuttons of an annunciator box in one part (real
 * size 2 x 1 cells). Each is momentary like a pushbutton: its output is 1 while held and 0 otherwise, and only one
 * is held at a time. Which button a click operates is the quarter of the face it lands on ({@link #clickValue}).
 *
 * <ul>
 *   <li>ACK and RESET do what a pushbutton with that role does today ({@link AnnunciatorGroups}) for the module's
 *       group, once per press: they are the ISA-18.1 sequence's own acknowledge and reset, nothing more.</li>
 *   <li>SILENCE has no horn in the mod to silence, so it is an output only, for a program or a sink to use.</li>
 *   <li>TEST lights every window of the module's group while it is held (a lamp test, drawn by the client from the
 *       synced output); it changes no alarm state and sends nothing to the plant.</li>
 * </ul>
 * Nothing here acts without a press.
 */
public final class AnnunciatorControlType extends PanelInputType {
    public static final int ACK = 0;
    public static final int RESET = 1;
    public static final int SILENCE = 2;
    public static final int TEST = 3;
    public static final int BUTTONS = 4;
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(24, 6),
            // real sizes: four 12 mm buttons in a row, and a double size one
            Footprint.of(2, 1), Footprint.of(4, 2));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.integer("AnnGroup", "component.bwr.field.ann_group", AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("ack"), PortSpec.of("reset"), PortSpec.of("silence"),
            PortSpec.of("test"));

    AnnunciatorControlType() {
        super(BwrMod.id("annunciator_control"));
    }

    @Override
    public int realSizes() {
        return 2;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new AnnunciatorControlData();
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
        return "ACM";
    }

    @Override
    public boolean holdable() {
        return true;
    }

    /** The button under a click: the quarter of the face it is in, left to right ACK, RESET, SILENCE, TEST. */
    public static int buttonAt(double width, double cellX) {
        return (int) Math.max(0, Math.min(BUTTONS - 1, Math.floor(cellX / (width / BUTTONS))));
    }

    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return buttonAt(component.w(), cellX);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        switch (action) {
            case PRESS -> {
                if (!Double.isFinite(value)) {
                    return false;
                }
                int button = Math.max(0, Math.min(BUTTONS - 1, (int) Math.round(value)));
                boolean edge = component.output(button) == 0.0;
                for (int i = 0; i < BUTTONS; i++) {
                    if (i != button && component.output(i) != 0.0) {
                        context.setOutput(i, 0.0); // only one button is under the finger
                    }
                }
                context.setOutput(button, 1.0);
                armFailsafe(context);
                if (edge) {
                    int group = ((AnnunciatorControlData) component.data()).group;
                    switch (button) {
                        case ACK -> AnnunciatorGroups.acknowledge(context.level(), context.owner(), group);
                        case RESET -> AnnunciatorGroups.reset(context.level(), context.owner(), group);
                        default -> {
                        }
                    }
                }
                return true;
            }
            case RELEASE -> {
                disarm(context);
                for (int i = 0; i < BUTTONS; i++) {
                    if (component.output(i) != 0.0) {
                        context.setOutput(i, 0.0);
                    }
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
