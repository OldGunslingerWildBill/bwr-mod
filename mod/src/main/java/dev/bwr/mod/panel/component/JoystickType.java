package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Joystick bypass switch ({@code bwr:joystick_switch}), a Square-D style four way joystick of the kind used for IRM and APRM
 * bypass: a lever with five positions, the centre and up, down, left and right.
 *
 * <h3>Outputs</h3>
 * {@code up}, {@code down}, {@code left}, {@code right}: 1 while the lever is in that direction (never two at once, and all
 * 0 at the centre), and {@code position}: 0 centre, 1 up, 2 down, 3 left, 4 right. Wire each direction to whatever it should
 * command; nothing here acts on its own.
 *
 * <h3>Operating it</h3>
 * Click the plate on the side you want: the lever goes there; click the middle (the boot) to centre it. With the
 * <b>spring return</b> setting it is a spring return joystick: it goes where you click only while the use key is held and the
 * lever returns to the centre when you let go (the same hold and fail-safe as a pushbutton); without it, the lever stays
 * where it was put, also across a restart.
 */
public final class JoystickType extends PanelInputType {
    public static final String ID_PATH = "joystick_switch";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(8, 8), Footprint.of(12, 12), Footprint.of(16, 16),
            // The real tail: twice the size first (a new item starts there), then the exact real size. A 30 mm joystick on a
            // 2 in square plate (about 6 cm) is 1 x 1, so 2 x 2 is its double; a 4 in one is 2 x 2, doubled 4 x 4.
            Footprint.of(2, 2), Footprint.of(1, 1), Footprint.of(4, 4));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.bool("Spring", "component.bwr.field.joystick_spring"),
            ConfigField.text("LegendUp", "component.bwr.field.legend_up", JoystickData.MAX_LEGEND),
            ConfigField.text("LegendDown", "component.bwr.field.legend_down", JoystickData.MAX_LEGEND),
            ConfigField.text("LegendLeft", "component.bwr.field.legend_left", JoystickData.MAX_LEGEND),
            ConfigField.text("LegendRight", "component.bwr.field.legend_right", JoystickData.MAX_LEGEND));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("up"), PortSpec.of("down"), PortSpec.of("left"),
            PortSpec.of("right"), PortSpec.of("position"));
    public static final int OUT_POSITION = 4;
    /** The part of the plate (a share of the distance from the middle to a side) that is the boot: a click there centres the lever. */
    public static final double BOOT_RADIUS = 0.16;

    JoystickType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public int realSizes() {
        return 3;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new JoystickData();
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
        return "JS";
    }

    @Override
    public boolean holdable() {
        return true;
    }

    /**
     * The position at a point of the plate, from {@code (u, v)} as fractions of its width and height: the centre inside the
     * boot, else the direction the point lies in from the middle (up and down win a tie with left and right).
     */
    public static int positionAt(double u, double v) {
        double dx = u - 0.5;
        double dy = v - 0.5;
        if (Math.hypot(dx, dy) < BOOT_RADIUS) {
            return JoystickData.CENTRE;
        }
        if (Math.abs(dy) >= Math.abs(dx)) {
            return dy < 0 ? JoystickData.UP : JoystickData.DOWN;
        }
        return dx < 0 ? JoystickData.LEFT : JoystickData.RIGHT;
    }

    /** A press names a position as {@code 1 + position}; 0 means nothing. */
    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return 1 + positionAt(cellX / Math.max(1, component.w()), cellY / Math.max(1, component.h()));
    }

    @Override
    public void onLoaded(PanelComponent component) {
        applyOutputs(component, ((JoystickData) component.data()).position);
    }

    @Override
    public void configured(OperateContext context, double[] outputsBefore) {
        // Spring switched on for a lever that sits off centre: the load centred it, and the plant must hear the centre.
        announceMoved(context, outputsBefore);
    }

    private static void applyOutputs(PanelComponent component, int position) {
        for (int i = 0; i < 4; i++) {
            component.storeOutput(i, position == i + 1 ? 1.0 : 0.0);
        }
        component.storeOutput(OUT_POSITION, position);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        JoystickData data = (JoystickData) context.component().data();
        switch (action) {
            case PRESS -> {
                int position = (int) Math.round(value) - 1;
                if (position < JoystickData.CENTRE || position >= JoystickData.POSITIONS) {
                    return false;
                }
                move(context, data, position);
                if (data.spring && position != JoystickData.CENTRE) {
                    armFailsafe(context);
                }
                return true;
            }
            case RELEASE -> {
                disarm(context);
                if (data.spring && data.position != JoystickData.CENTRE) {
                    move(context, data, JoystickData.CENTRE);
                }
                return true;
            }
            case SET -> {
                int position = (int) Math.round(value);
                if (position < JoystickData.CENTRE || position >= JoystickData.POSITIONS) {
                    return false;
                }
                move(context, data, position);
                if (data.spring && position != JoystickData.CENTRE) {
                    armFailsafe(context); // a program's pulse: the spring brings it back
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void move(OperateContext context, JoystickData data, int position) {
        PanelComponent component = context.component();
        if (position == data.position && component.output(OUT_POSITION) == position) {
            return;
        }
        data.position = position;
        markSaved(context);
        // The direction that lets go first, then the one that is taken, so a listener never sees two at once.
        for (int i = 0; i < 4; i++) {
            if (position != i + 1) {
                context.setOutput(i, 0.0);
            }
        }
        if (position != JoystickData.CENTRE) {
            context.setOutput(position - 1, 1.0);
        }
        context.setOutput(OUT_POSITION, position);
    }
}
