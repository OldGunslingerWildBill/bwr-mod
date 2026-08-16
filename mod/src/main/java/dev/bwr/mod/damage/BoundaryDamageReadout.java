package dev.bwr.mod.damage;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.boundary.BoundaryFailure;
import dev.bwr.core.boundary.BoundaryStress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes the overpressure damage model to Lua and to the controller's
 * status text — {@code SPEC.md} section 7.
 *
 * <h2>Why this is published at all</h2>
 * Because it is the only thing in this plant that happens without the player
 * asking, and because {@code SPEC.md} section 7 requires accumulated stress to
 * be a measurement so that a player can write their own alarms against it. If
 * a number is not exposed here, the control function that needs it is
 * literally unbuildable.
 *
 * <h2>Measurements, never judgements</h2>
 * Every value below is a fact about metal: how much of a pipe's life has been
 * used, how long the vessel has spent over its design pressure, what the
 * current per-second failure rate is, and what is already broken. None of them
 * says whether that is alright, and nothing in the mod reacts to any of them.
 * There is no threshold in this file. Deciding that 0.4 of a steam line's life
 * is worth an alarm, and what to do about it, is Lua's job — and the reason
 * the raw stress fraction is exposed rather than a traffic light is precisely
 * so the player picks the number.
 */
public final class BoundaryDamageReadout {

    private BoundaryDamageReadout() {
    }

    /** Stable Lua key for a component. Lowercase enum name. */
    public static String key(BoundaryComponent component) {
        return component.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The whole damage model as a Lua table.
     *
     * <pre>
     *   local d = reactor.getBoundaryDamage()
     *   if d.stress.recirculation_line &gt; 0.5 then ... end   -- your alarm
     *   if d.failureRatePerSecond &gt; 0 then ... end          -- your alarm
     * </pre>
     */
    public static Map<String, Object> toLua(BoundaryStress boundary) {
        Map<String, Object> out = new HashMap<>();

        Map<String, Object> stress = new HashMap<>();
        Map<String, Object> broken = new HashMap<>();
        Map<String, Object> weight = new HashMap<>();
        for (BoundaryComponent component : boundary.getPlantConfiguration().exposedComponents()) {
            stress.put(key(component), boundary.getStressFraction(component));
            broken.put(key(component), boundary.isBroken(component));
            weight.put(key(component), component.exposureWeight());
        }
        out.put("stress", stress);
        out.put("broken", broken);
        out.put("exposureWeight", weight);

        out.put("configuration", boundary.getPlantConfiguration().name().toLowerCase(Locale.ROOT));
        out.put("peakStress", boundary.getPeakStress());
        BoundaryComponent worst = boundary.getMostStressedComponent();
        out.put("mostStressed", worst == null ? null : key(worst));
        out.put("failureRatePerSecond", boundary.getFailureRatePerSecond());
        out.put("failureProbabilityPerTick", boundary.getFailureProbabilityOver(0.05));
        out.put("anyFailed", boundary.hasFailed());
        out.put("terminalFailure", boundary.hasTerminalFailure());

        out.put("secondsAboveDesignPressure", boundary.getSecondsAboveDesignPressure());
        out.put("secondsAboveCodePressure", boundary.getSecondsAboveCodePressure());
        out.put("irradiatedFuelOverpressureSeconds",
                boundary.getIrradiatedFuelOverpressureSeconds());
        out.put("peakPressurePsig", boundary.getPeakPressurePsig());

        // The hardware envelope, so a control program does not have to hardcode
        // it. These are properties of the vessel, not setpoints: what to do
        // about them is not supplied and never will be.
        out.put("designPressurePsig", BoundaryStress.DESIGN_PRESSURE_PSIG);
        out.put("codePressurePsig", BoundaryStress.CODE_PRESSURE_PSIG);
        out.put("fuelPresentPressurePsig", BoundaryStress.FUEL_PRESENT_PRESSURE_PSIG);

        BoundaryFailure last = boundary.getLastFailure();
        if (last != null) {
            Map<String, Object> l = new HashMap<>();
            l.put("component", key(last.component()));
            l.put("atSeconds", last.atSeconds());
            l.put("pressurePsig", last.pressurePsig());
            l.put("accumulatedStress", last.accumulatedStress());
            l.put("terminal", last.isTerminal());
            out.put("lastFailure", l);
        }
        return out;
    }

    /**
     * Lines for the controller's right-click status text. Reports the condition
     * of the boundary in plain language and stops there — it does not advise,
     * and there is nothing here that would not be equally true printed on a
     * clipboard by somebody walking the plant.
     */
    public static List<String> statusLines(BoundaryStress boundary) {
        List<String> out = new ArrayList<>();
        if (boundary == null) {
            return out;
        }
        for (BoundaryComponent component : boundary.getPlantConfiguration().exposedComponents()) {
            if (boundary.isBroken(component)) {
                out.add(String.format(Locale.ROOT, "%s: BROKEN%s",
                        component.displayName(), component.isTerminal() ? " (terminal)" : ""));
            }
        }
        double peak = boundary.getPeakStress();
        if (peak > 0.0) {
            BoundaryComponent worst = boundary.getMostStressedComponent();
            out.add(String.format(Locale.ROOT,
                    "Pressure boundary: %.0f%% of life used on the %s; %.0f s above %.0f psig",
                    100.0 * peak / BoundaryStress.FAILURE_STRESS,
                    worst == null ? "boundary" : worst.displayName().toLowerCase(Locale.ROOT),
                    boundary.getSecondsAboveDesignPressure(),
                    BoundaryStress.DESIGN_PRESSURE_PSIG));
        }
        if (boundary.getIrradiatedFuelOverpressureSeconds() > 0.0) {
            out.add(String.format(Locale.ROOT,
                    "%.0f s spent above %.0f psig with irradiated fuel in the vessel",
                    boundary.getIrradiatedFuelOverpressureSeconds(),
                    BoundaryStress.FUEL_PRESENT_PRESSURE_PSIG));
        }
        return out;
    }
}
