package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Manual/auto controller station: its scale (the span the process variable and the setpoint are shown over), the units
 * text, how far one press of RAISE or LOWER moves things, and the latched state: the mode, the manual demand and the
 * local setpoint.
 *
 * <p>The local setpoint is kept as a fraction of the span ({@link #spFraction}), so changing the span does not move the
 * pointer; the output reports it in the scale's own units ({@link #setpointValue}). Everything is saved only when it is not
 * its default, and a missing key loads as the default, so the data of a station placed before a field existed (and the
 * wrench's merge of the whole saved tag) loads back identically.
 */
public final class ControllerStationData extends ComponentData {
    public static final String TYPE = "controller_station";
    public static final double LIMIT = GaugeData.LIMIT;
    public static final int MAX_UNITS = 8;
    public static final double DEFAULT_MIN = 0.0;
    public static final double DEFAULT_MAX = 100.0;
    /** Percent of the demand per press of RAISE / LOWER (and per scroll notch) in MAN. */
    public static final double DEFAULT_STEP = 1.0;
    public static final double MIN_STEP = 0.1;
    public static final double MAX_STEP = 25.0;
    /** Percent of the span per press (and per scroll notch) in AUTO with a local setpoint. */
    public static final double DEFAULT_SP_STEP = 1.0;
    public static final double DEFAULT_SP_FRACTION = 0.5;
    /** PID settings (AUTO): proportional gain, integral time in seconds, derivative time in seconds, the action, the output limits. */
    public static final double DEFAULT_KP = 0.5;
    public static final double DEFAULT_TI = 20.0;
    public static final double DEFAULT_TD = 0.0;
    public static final double DEFAULT_OUT_MIN = 0.0;
    public static final double DEFAULT_OUT_MAX = 100.0;
    public static final double MIN_KP = 0.01;
    public static final double MAX_KP = 100.0;
    public static final double MIN_TI = 0.5;
    public static final double MAX_TI = 3600.0;
    public static final double MAX_TD = 600.0;

    public double min = DEFAULT_MIN;
    public double max = DEFAULT_MAX;
    public String units = "";
    public double step = DEFAULT_STEP;
    public double spStep = DEFAULT_SP_STEP;

    public double kp = DEFAULT_KP;
    public double ti = DEFAULT_TI;
    public double td = DEFAULT_TD;
    /** Reverse action: the output rises when the process variable is above the setpoint (a drain valve, a cooler). */
    public boolean reverse;
    public double outMin = DEFAULT_OUT_MIN;
    public double outMax = DEFAULT_OUT_MAX;

    // The PID's memory of its last step: never saved (a reload or a switch to AUTO starts it afresh, which is what makes the
    // transfer bumpless).
    public boolean pidReady;
    public double pidPv;
    public double pidFiltered;
    public double pidFiltered1;
    public long pidLastStep = Long.MIN_VALUE;

    /** Latched: true in AUTO, false in MAN (the default). */
    public boolean auto;
    /** Latched: the station's own demand, 0..100 percent (what RAISE and LOWER move in MAN). */
    public double demand;
    /** Latched: the local setpoint as a fraction of the span, 0..1. */
    public double spFraction = DEFAULT_SP_FRACTION;

    public double span() {
        return max > min ? max - min : 1.0;
    }

    /** Position of {@code value} on the scale, 0..1 (NaN: 0). */
    public double fraction(double value) {
        return Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min) / span()));
    }

    /** The local setpoint in the scale's units. */
    public double setpointValue() {
        return min + Math.max(0.0, Math.min(1.0, spFraction)) * span();
    }

    /**
     * AUTO, on every sample of the panel: an outside demand bound to {@code auto_demand} is followed (the {@code demand} output
     * follows it too); otherwise the PID moves the demand so that the process variable follows the setpoint. Nothing runs in MAN.
     */
    @Override
    public void sampleContent(net.minecraft.world.level.Level level, dev.bwr.mod.panel.PanelBlockEntity owner, PanelComponent component) {
        if (!auto || level == null || level.isClientSide()) {
            pidReady = false;
            return;
        }
        double external = component.input(ControllerStationType.IN_AUTO_DEMAND);
        if (!Double.isNaN(external)) {
            pidReady = false;
            followDemand(owner, component, Math.max(0.0, Math.min(100.0, external)));
            return;
        }
        long now = level.getGameTime();
        if (now == pidLastStep) {
            return; // a second look in the same tick (a binding edit) is not a step
        }
        double dt = pidLastStep == Long.MIN_VALUE ? 0.0 : (now - pidLastStep) / 20.0;
        pidLastStep = now;
        double next = StationPid.step(this, ControllerStationType.shownSetpoint(component), component.input(ControllerStationType.IN_PV), dt);
        if (!Double.isNaN(next)) {
            followDemand(owner, component, next);
        }
    }

    /** Sets the demand to {@code value} as the station's own output (the sinks and the clients see it). */
    void followDemand(dev.bwr.mod.panel.PanelBlockEntity owner, PanelComponent component, double value) {
        if (value == demand) {
            return;
        }
        demand = value;
        owner.setOutput(component, ControllerStationType.OUT_DEMAND, value);
        owner.setChanged();
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        if (!units.isEmpty()) {
            tag.putString("Units", units);
        }
        tag.putDouble("Step", step);
        tag.putDouble("SpStep", spStep);
        if (kp != DEFAULT_KP) {
            tag.putDouble("Kp", kp);
        }
        if (ti != DEFAULT_TI) {
            tag.putDouble("Ti", ti);
        }
        if (td != DEFAULT_TD) {
            tag.putDouble("Td", td);
        }
        if (reverse) {
            tag.putBoolean("Reverse", true);
        }
        if (outMin != DEFAULT_OUT_MIN) {
            tag.putDouble("OutMin", outMin);
        }
        if (outMax != DEFAULT_OUT_MAX) {
            tag.putDouble("OutMax", outMax);
        }
        if (auto) {
            tag.putBoolean("Auto", true);
        }
        if (demand != 0.0) {
            tag.putDouble("Demand", demand);
        }
        if (spFraction != DEFAULT_SP_FRACTION) {
            tag.putDouble("SpFraction", spFraction);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        min = GaugeData.number(tag, "Min", DEFAULT_MIN, -LIMIT, LIMIT);
        max = GaugeData.number(tag, "Max", DEFAULT_MAX, -LIMIT, LIMIT);
        units = ConfigSchema.cleanText(tag.getString("Units"), MAX_UNITS);
        step = GaugeData.number(tag, "Step", DEFAULT_STEP, MIN_STEP, MAX_STEP);
        spStep = GaugeData.number(tag, "SpStep", DEFAULT_SP_STEP, MIN_STEP, MAX_STEP);
        kp = GaugeData.number(tag, "Kp", DEFAULT_KP, MIN_KP, MAX_KP);
        ti = GaugeData.number(tag, "Ti", DEFAULT_TI, MIN_TI, MAX_TI);
        td = GaugeData.number(tag, "Td", DEFAULT_TD, 0.0, MAX_TD);
        reverse = tag.getBoolean("Reverse");
        outMin = GaugeData.number(tag, "OutMin", DEFAULT_OUT_MIN, 0.0, 100.0);
        outMax = GaugeData.number(tag, "OutMax", DEFAULT_OUT_MAX, 0.0, 100.0);
        if (outMax - outMin < 1.0) {
            // Limits that leave no room are not trusted.
            outMin = DEFAULT_OUT_MIN;
            outMax = DEFAULT_OUT_MAX;
        }
        pidReady = false;
        pidLastStep = Long.MIN_VALUE;
        auto = tag.getBoolean("Auto");
        demand = GaugeData.number(tag, "Demand", 0.0, 0.0, 100.0);
        spFraction = GaugeData.number(tag, "SpFraction", DEFAULT_SP_FRACTION, 0.0, 1.0);
    }
}
