package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PowerUnitsState;
import dev.bwr.mod.signal.PowerUnits;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * What picking a gauge power unit does on the server (part 6). It does two things, and costs nothing at run time:
 * <ol>
 *   <li>it sets the scale of the gauge's plant bindings (the existing {@link PlantSource} transform
 *       {@code raw x scale + offset}), so 100 FE/t reads 5.0 kW;</li>
 *   <li>it sets the Units text of the gauge to match.</li>
 * </ol>
 * It never changes Min and Max by itself; "Fit range" does that, on request, from the signal's own range.
 */
public final class PowerUnitsOps {
    private PowerUnitsOps() {
    }

    /**
     * Called by {@code PanelOps.configure} with the merged config before it is loaded. When the Power units or Per tick
     * / Per second choice changed, rescales every line-linked input and sets the Units text in {@code merged}. A choice
     * that does not fit the bound signal (W for stored energy) is dropped: {@code merged} keeps the old one. Returns a
     * reason for the player when it refused, else null.
     */
    public static Component apply(ServerLevel level, dev.bwr.mod.panel.PanelBlockEntity panel, PanelComponent component,
                                  CompoundTag merged) {
        PowerUnitsState state = component.data().powerState();
        if (state == null) {
            return null;
        }
        String units = merged.contains(PowerUnitsState.UNITS_KEY) ? merged.getString(PowerUnitsState.UNITS_KEY) : PowerUnits.RAW;
        String rate = merged.contains(PowerUnitsState.RATE_KEY) ? merged.getString(PowerUnitsState.RATE_KEY) : PowerUnits.PER_TICK;
        if (!PowerUnits.OPTIONS.contains(units) || !PowerUnits.RATES.contains(rate)) {
            return null;
        }
        if (units.equals(state.units) && rate.equals(state.rate)) {
            return null;
        }
        boolean perSecond = PowerUnits.PER_SECOND.equals(rate);
        int[] inputs = new int[component.inputCount()];
        SignalSpec[] specs = new SignalSpec[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            {
                specs[i] = WiringOps.boundSpec(level, panel, component.source(i));
                if (specs[i] != null && !PowerUnits.offers(Quantity.of(specs[i]), units)) {
                    merged.putString(PowerUnitsState.UNITS_KEY, state.units);
                    merged.putString(PowerUnitsState.RATE_KEY, state.rate);
                    return dev.bwr.mod.text.BwrText.tr("message.bwr.power_units.refused", "%s cannot be shown in %s", specs[i].label(),
                            units);
                }
            }
        }
        boolean first = true;
        for (int i = 0; i < inputs.length; i++) {
            if (specs[i] == null) {
                continue;
            }
            double scale = PowerUnits.scale(specs[i].unit(), specs[i].key(), units, perSecond, PanelConfig.feToJoules());
            component.setSource(i, WiringOps.rescaled(component.source(i), scale));
            if (first && merged.contains("Units")) {
                merged.putString("Units", PowerUnits.unitsText(units, specs[i].unit()));
            }
            first = false;
        }
        return null;
    }

    /**
     * The scale a new plant binding of {@code spec} should get when the component already has a power unit picked and the
     * request carries the default transform; the request's own scale otherwise (a custom scale is never overridden).
     */
    public static double scaleForBind(PanelComponent component, SignalSpec spec, double requested, double offset) {
        PowerUnitsState state = component.data().powerState();
        if (state == null || PowerUnits.RAW.equals(state.units) || requested != 1.0 || offset != 0.0
                || !PowerUnits.offers(Quantity.of(spec), state.units)) {
            return requested;
        }
        return PowerUnits.scale(spec.unit(), spec.key(), state.units, state.perSecond(), PanelConfig.feToJoules());
    }

    /**
     * "Fit range": sets the Min and Max of the component from the range of the signal it is line-linked to, converted
     * the same way as the reading (range x scale + offset). Returns whether a range was set.
     */
    public static boolean fitRange(ServerLevel level, dev.bwr.mod.panel.PanelBlockEntity panel, PanelComponent component) {
        for (int i = 0; i < component.inputCount(); i++) {
            SignalSpec spec = WiringOps.boundSpec(level, panel, component.source(i));
            if (spec == null) {
                continue;
            }
            double scale = WiringOps.scaleOf(component.source(i));
            double offset = WiringOps.offsetOf(component.source(i));
            double a = spec.min() * scale + offset;
            double b = spec.max() * scale + offset;
            double low = Math.min(a, b);
            double high = Math.max(a, b);
            if (Double.isFinite(low) && Double.isFinite(high) && low < high && component.data().setRange(low, high)) {
                component.touch();
                return true;
            }
            return false;
        }
        return false;
    }
}
