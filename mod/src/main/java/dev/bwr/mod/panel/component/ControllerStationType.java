package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Manual/auto controller station (a GE / Bailey / Foxboro hand-auto station): a scale with the process-variable pointer and
 * the setpoint pointer on it, a demand bar, AUTO and MAN lamp pushbuttons, and RAISE and LOWER pushbuttons. Id
 * {@code bwr:controller_station}.
 *
 * <h3>Inputs (bind them like any panel input)</h3>
 * <ul>
 *   <li>{@code pv}: the process variable, in the scale's units (Min to Max). No signal hides its pointer.</li>
 *   <li>{@code setpoint}: optional. Bound, the setpoint pointer follows it and the station's setpoint is remote; unbound, the
 *       setpoint is the station's own (local).</li>
 *   <li>{@code auto_demand}: the demand, 0 to 100 percent, that an outside source provides (a CC program, a plant
 *       controller signal). In AUTO the demand bar shows it.</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * {@code demand} (0 to 100, the station's own demand), {@code mode} (0 MAN, 1 AUTO), {@code raise} and {@code lower}
 * (1 only while the button is held: momentary), {@code setpoint} (the local setpoint in the scale's units).
 *
 * <h3>What it does, and what it never does</h3>
 * <b>The station holds no control law and nothing acts by itself.</b> An output changes only when a player operates the
 * station. In MAN, RAISE and LOWER (and the mouse wheel over it) move the demand by Step percent; in AUTO with no bound
 * setpoint they move the local setpoint by Setpoint step percent of the span; in AUTO with a bound setpoint they move
 * nothing (only the momentary {@code raise} and {@code lower} outputs show the press, for a program that wants it). In AUTO
 * the demand bar shows the {@code auto_demand} input and the {@code demand} output stays where MAN left it. Pressing MAN
 * takes over from the demand the bar shows (bumpless: the {@code demand} output becomes {@code auto_demand} when that has a
 * signal). Wire {@code demand} (with {@code mode}) to an actuator, and bind {@code auto_demand} to whatever you wrote that
 * should run it in AUTO: the mod provides the hardware, you provide the logic.
 *
 * <h3>Operating it</h3>
 * Click AUTO, MAN, RAISE or LOWER; hold the use key on RAISE or LOWER to keep stepping (about five steps a second).
 */
public final class ControllerStationType extends PanelInputType {
    public static final String ID_PATH = "controller_station";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(8, 16), Footprint.of(12, 24),
            // The real tail (see realSizes): each real size is listed twice over, first twice its size (the size a new item
            // starts at, since the real size alone proved too small to read) and then its exact real size. A 3 x 6 cell
            // station is 19 x 37 cm (a DIN-style 144 x 288 mm one is 2.3 x 4.6 cells but would not hold a legible scale);
            // the compact one is 2 x 4.
            Footprint.of(6, 12), Footprint.of(3, 6), Footprint.of(4, 8), Footprint.of(2, 4));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.decimal("Min", "component.bwr.field.min", -ControllerStationData.LIMIT, ControllerStationData.LIMIT),
            ConfigField.decimal("Max", "component.bwr.field.max", -ControllerStationData.LIMIT, ControllerStationData.LIMIT),
            ConfigField.text("Units", "component.bwr.field.units", ControllerStationData.MAX_UNITS),
            ConfigField.decimal("Step", "component.bwr.field.demand_step", ControllerStationData.MIN_STEP, ControllerStationData.MAX_STEP),
            ConfigField.decimal("SpStep", "component.bwr.field.setpoint_step", ControllerStationData.MIN_STEP, ControllerStationData.MAX_STEP));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("pv"), PortSpec.of("setpoint"), PortSpec.of("auto_demand"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("demand"), PortSpec.of("mode"), PortSpec.of("raise"),
            PortSpec.of("lower"), PortSpec.of("setpoint"));

    /** Input indices. */
    public static final int IN_PV = 0;
    public static final int IN_SETPOINT = 1;
    public static final int IN_AUTO_DEMAND = 2;
    /** Output indices. */
    public static final int OUT_DEMAND = 0;
    public static final int OUT_MODE = 1;
    public static final int OUT_RAISE = 2;
    public static final int OUT_LOWER = 3;
    public static final int OUT_SETPOINT = 4;
    /** Most notches one scroll event may count. */
    private static final int MAX_NOTCHES = 10;

    ControllerStationType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public int realSizes() {
        return 4;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new ControllerStationData();
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
    public boolean holdable() {
        return true;
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    /** The region (AUTO, MAN, RAISE, LOWER or none) at the point, found from the same fractions the model draws. */
    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return StationLayout.regionAt(cellX / Math.max(1, component.w()), cellY / Math.max(1, component.h()));
    }

    /** A new station's setpoint is mid-span: its output says so from the start. */
    @Override
    public void onPlaced(PanelComponent component) {
        onLoaded(component);
    }

    @Override
    public void onLoaded(PanelComponent component) {
        ControllerStationData data = (ControllerStationData) component.data();
        component.storeOutput(OUT_DEMAND, data.demand);
        component.storeOutput(OUT_MODE, data.auto ? 1.0 : 0.0);
        component.storeOutput(OUT_SETPOINT, data.setpointValue());
    }

    /** What the demand bar shows: the auto demand in AUTO when it has a signal, else the station's own demand. */
    public static double shownDemand(PanelComponent component) {
        ControllerStationData data = (ControllerStationData) component.data();
        double auto = component.input(IN_AUTO_DEMAND);
        if (data.auto && !Double.isNaN(auto)) {
            return Math.max(0.0, Math.min(100.0, auto));
        }
        return Math.max(0.0, Math.min(100.0, component.output(OUT_DEMAND)));
    }

    /** What the setpoint pointer shows, in the scale's units: the bound setpoint, else the local one. */
    public static double shownSetpoint(PanelComponent component) {
        double bound = component.input(IN_SETPOINT);
        return Double.isNaN(bound) ? ((ControllerStationData) component.data()).setpointValue() : bound;
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        ControllerStationData data = (ControllerStationData) component.data();
        switch (action) {
            case PRESS -> {
                int region = (int) Math.round(value);
                switch (region) {
                    case StationLayout.AUTO -> setMode(context, data, true);
                    case StationLayout.MAN -> setMode(context, data, false);
                    case StationLayout.RAISE, StationLayout.LOWER -> {
                        boolean raise = region == StationLayout.RAISE;
                        context.setOutput(raise ? OUT_LOWER : OUT_RAISE, 0.0);
                        context.setOutput(raise ? OUT_RAISE : OUT_LOWER, 1.0);
                        armFailsafe(context);
                        nudge(context, data, raise ? 1 : -1, 1);
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            }
            case RELEASE -> {
                disarm(context);
                context.setOutput(OUT_RAISE, 0.0);
                context.setOutput(OUT_LOWER, 0.0);
                return true;
            }
            case TURN -> {
                int notches = (int) Math.max(-MAX_NOTCHES, Math.min(MAX_NOTCHES, Math.round(value)));
                return notches != 0 && nudge(context, data, step(notches), Math.abs(notches));
            }
            case SET -> {
                // A program setting the station's demand: only the hand station takes it.
                if (data.auto) {
                    if (context.player() != null) {
                        context.player().displayClientMessage(Component.translatable("message.bwr.station.in_auto"), true);
                    }
                    return false;
                }
                setDemand(context, data, value);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void setMode(OperateContext context, ControllerStationData data, boolean auto) {
        PanelComponent component = context.component();
        if (!auto && data.auto) {
            // Back to hand: take over from the demand the bar was showing, so the output does not jump.
            double shown = shownDemand(component);
            if (!Double.isNaN(component.input(IN_AUTO_DEMAND))) {
                data.demand = shown;
                context.setOutput(OUT_DEMAND, shown);
            }
        }
        if (data.auto != auto) {
            data.auto = auto;
            markSaved(context);
        }
        context.setOutput(OUT_MODE, auto ? 1.0 : 0.0);
    }

    private static void setDemand(OperateContext context, ControllerStationData data, double demand) {
        data.demand = clamp(demand, 0.0, 100.0);
        markSaved(context);
        context.setOutput(OUT_DEMAND, data.demand);
    }

    /** RAISE / LOWER / the wheel: MAN moves the demand, AUTO with a local setpoint moves the setpoint, otherwise nothing. */
    private static boolean nudge(OperateContext context, ControllerStationData data, int direction, int count) {
        PanelComponent component = context.component();
        if (!data.auto) {
            setDemand(context, data, data.demand + direction * count * data.step);
            return true;
        }
        if (Double.isNaN(component.input(IN_SETPOINT))) {
            data.spFraction = clamp(data.spFraction + direction * count * data.spStep / 100.0, 0.0, 1.0);
            markSaved(context);
            context.setOutput(OUT_SETPOINT, data.setpointValue());
            return true;
        }
        return false;
    }
}
