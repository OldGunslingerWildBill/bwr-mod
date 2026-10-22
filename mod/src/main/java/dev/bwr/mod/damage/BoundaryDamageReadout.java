package dev.bwr.mod.damage;

import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

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
        out.put("interfacingSystemsLoca", boundary.isInterfacingSystemsLoca());
        out.put("terminalFailure", boundary.hasTerminalFailure());

        out.put("secondsAboveDesignPressure", boundary.getSecondsAboveDesignPressure());
        out.put("secondsAboveCodePressure", boundary.getSecondsAboveCodePressure());
        out.put("irradiatedFuelOverpressureSeconds",
                boundary.getIrradiatedFuelOverpressureSeconds());
        out.put("peakPressurePsig", boundary.getPeakPressurePsig());
        out.put("cooldownRateCPerHour", boundary.getLastCooldownRateCPerHour());
        out.put("cooldownLimitCPerHour", BoundaryStress.COOLDOWN_LIMIT_C_PER_HOUR);
        out.put("secondsAboveCooldownLimit", boundary.getSecondsAboveCooldownLimit());
        out.put("cooldownThermalStress", boundary.getCooldownThermalStress());

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
        return BwrText.english(statusComponents(boundary));
    }

    public static List<Component> statusComponents(BoundaryStress boundary) {
        List<Component> out = new ArrayList<>();
        if (boundary == null) {
            return out;
        }
        for (BoundaryComponent component : boundary.getPlantConfiguration().exposedComponents()) {
            if (boundary.isBroken(component)) {
                out.add(BwrText.tr(
                        "status.bwr.boundary_damage_readout.broken",
                        "%s: BROKEN%s",
                        dev.bwr.mod.text.StatusNames.boundary(component),
                        (component.isTerminal() ? BwrText.tr("status.bwr.boundary_damage_readout.terminal", " (terminal)") : Component.empty())));
            }
        }
        double peak = boundary.getPeakStress();
        if (peak > 0.0) {
            BoundaryComponent worst = boundary.getMostStressedComponent();
            out.add(BwrText.tr(
                    "status.bwr.boundary_damage_readout.pressure_boundary_f_life_used_on_the_s",
                    "Pressure boundary: %s%% of life used on the %s; %s s above %s psig",
                    String.format(java.util.Locale.ROOT, "%.0f", 100.0 * peak / BoundaryStress.FAILURE_STRESS),
                    (worst == null ? BwrText.tr("status.bwr.boundary_damage_readout.boundary", "boundary") : dev.bwr.mod.text.StatusNames.boundaryLower(worst)),
                    String.format(java.util.Locale.ROOT, "%.0f", boundary.getSecondsAboveDesignPressure()),
                    String.format(java.util.Locale.ROOT, "%.0f", BoundaryStress.DESIGN_PRESSURE_PSIG)));
        }
        if (boundary.getIrradiatedFuelOverpressureSeconds() > 0.0) {
            out.add(BwrText.tr(
                    "status.bwr.boundary_damage_readout.s_spent_above_psig_with_irradiated_fuel_in",
                    "%s s spent above %s psig with irradiated fuel in the vessel",
                    String.format(java.util.Locale.ROOT, "%.0f", boundary.getIrradiatedFuelOverpressureSeconds()),
                    String.format(java.util.Locale.ROOT, "%.0f", BoundaryStress.FUEL_PRESENT_PRESSURE_PSIG)));
        }
        if (boundary.getCooldownThermalStress() > 0.0) {
            out.add(BwrText.tr(
                    "status.bwr.boundary_damage_readout.vessel_cooldown_c_h_thermal_stress_life_used",
                    "Vessel cooldown: %s C/h; thermal-stress life used %s; %s s above %s C/h",
                    String.format(java.util.Locale.ROOT, "%.1f", boundary.getLastCooldownRateCPerHour()),
                    String.format(java.util.Locale.ROOT, "%.3f", boundary.getCooldownThermalStress()),
                    String.format(java.util.Locale.ROOT, "%.0f", boundary.getSecondsAboveCooldownLimit()),
                    String.format(java.util.Locale.ROOT, "%.1f", BoundaryStress.COOLDOWN_LIMIT_C_PER_HOUR)));
        }
        return out;
    }

    /**
     * One line per pressure-boundary break of a reactor, and one per break sealed in the last minute, for the
     * controller's status text and the reactor screen. Facts only: where it is and whether it is open to the vessel.
     */
    public static List<Component> breakComponents(dev.bwr.mod.reactor.ReactorBoundaryEffects effects, long now) {
        List<Component> out = new ArrayList<>();
        if (effects == null) return out;
        for (var b : effects.breaks()) {
            if (b.nozzle()) {
                out.add(BwrText.tr("status.bwr.boundary_damage_readout.nozzle_break",
                        "%s: open break at the vessel nozzle", b.label()));
            } else if (b.open()) {
                out.add(BwrText.tr("status.bwr.boundary_damage_readout.line_break_open",
                        "%s: open break at %s %s %s", b.label(), b.at().getX(), b.at().getY(), b.at().getZ()));
            } else {
                out.add(BwrText.tr("status.bwr.boundary_damage_readout.line_break_isolated",
                        "%s: break at %s %s %s, isolated from the vessel", b.label(), b.at().getX(), b.at().getY(), b.at().getZ()));
            }
        }
        for (var s : effects.recentlySealed(now)) {
            out.add(BwrText.tr("status.bwr.boundary_damage_readout.break_sealed", "%s: break sealed", s.label()));
        }
        return out;
    }

    /** The same breaks as Lua tables: {@code breaks} (open and isolated) and {@code sealed} (the last minute). */
    public static Map<String, Object> breaksToLua(dev.bwr.mod.reactor.ReactorBoundaryEffects effects, long now) {
        Map<String, Object> out = new HashMap<>();
        List<Map<String, Object>> breaks = new ArrayList<>();
        List<Map<String, Object>> sealed = new ArrayList<>();
        if (effects != null) {
            for (var b : effects.breaks()) {
                Map<String, Object> m = new HashMap<>();
                m.put("component", key(b.component()));
                m.put("label", BwrText.english(b.label()));
                m.put("kind", b.nozzle() ? "nozzle" : "line");
                m.put("open", b.open());
                m.put("x", b.at().getX()); m.put("y", b.at().getY()); m.put("z", b.at().getZ());
                m.put("portX", b.port().getX()); m.put("portY", b.port().getY()); m.put("portZ", b.port().getZ());
                breaks.add(m);
            }
            for (var s : effects.recentlySealed(now)) {
                Map<String, Object> m = new HashMap<>();
                m.put("component", key(s.component()));
                m.put("label", BwrText.english(s.label()));
                m.put("x", s.at().getX()); m.put("y", s.at().getY()); m.put("z", s.at().getZ());
                m.put("secondsAgo", (now - s.gameTime()) / 20.0);
                sealed.add(m);
            }
        }
        out.put("breaks", breaks);
        out.put("sealed", sealed);
        return out;
    }
}
