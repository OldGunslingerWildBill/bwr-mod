package dev.bwr.mod.signal.adapters;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.mod.containment.ContainmentEquipmentRegistry;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.LprmReadout;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.rods.RodIndications;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;

/**
 * Reactor controller. Every getter is the one {@code ReactorPeripheral} calls. Per-rod signals are numbered
 * from 1 like the peripheral's rod arguments: {@code rod_<n>_position}, {@code rod_<n>_demand},
 * {@code rod_<n>_accumulator}, and the command {@code rod_<n>_position}.
 */
final class ReactorSignals {
    private ReactorSignals() {}

    private static final double MAX_ROD_LABEL = PhysicalConstants.ROD_NOTCH_MAX_LABEL;
    /** Upper bound of the rod count (a 21 x 21 interior holds 357 rods). */
    private static final double MAX_RODS = 400;

    private static boolean selectRod(ReactorControllerBlockEntity be, double value) {
        return Double.isFinite(value) && value == Math.rint(value) && value >= 0 && value <= MAX_RODS
                && DeviceCommands.reactorSelectRod(be, (int) value);
    }

    /** Reads from the live core; NaN (no signal) when the reactor is not formed. */
    private static ToDoubleFunction<ReactorControllerBlockEntity> core(ToDoubleFunction<ReactorCore> read) {
        return be -> {
            ReactorCore core = be.core();
            return core == null ? Double.NaN : read.applyAsDouble(core);
        };
    }

    private static double flag(boolean value) { return value ? 1.0 : 0.0; }

    static final Schema<ReactorControllerBlockEntity> SCHEMA = new Schema<ReactorControllerBlockEntity>()
            .bool("formed", "Reactor formed", ReactorControllerBlockEntity::isFormed)
            .number("power", "Total power", "fraction of rated", 0, 1.2, core(ReactorCore::getTotalPowerFractionOfRated))
            .number("neutron_power", "Neutron power", "fraction of rated", 0, 1.5, core(ReactorCore::getNeutronPowerFraction))
            .number("decay_heat", "Decay heat", "fraction of rated", 0, 0.1, core(ReactorCore::getDecayHeatFraction))
            .number("thermal_mw", "Thermal power", "MW", 0, PhysicalConstants.RATED_THERMAL_MW * 1.2,
                    core(c -> c.getTotalPowerFractionOfRated() * PhysicalConstants.RATED_THERMAL_MW))
            .number("period", "Reactor period", "s", -100, 100, core(c -> c.getSourceRangePeriodSeconds(1)))
            .number("aprm_percent", "Average power range", "%", 0, 125, core(c -> c.getAveragePowerRangePercent(1)))
            .number("reactivity_dollars", "Reactivity", "$", -5, 1, core(ReactorCore::getReactivityDollars))
            .number("pressure", "Dome pressure", "psig", 0, 1400, core(ReactorCore::getPressurePsig))
            .number("level", "Indicated water level", "in", -200, 100, core(ReactorCore::getIndicatedLevelIn))
            .number("core_flow", "Core flow", "fraction of rated", 0, 1.2, core(ReactorCore::getCoreFlowFraction))
            .number("void_fraction", "Core void fraction", "fraction", 0, 1, core(ReactorCore::getVoidFraction))
            .number("natural_circulation", "Natural circulation flow", "fraction of rated", 0, 1, core(ReactorCore::getNaturalCirculationFlowFraction))
            .number("recirc_flow_demand", "Recirculation flow demand", "fraction of rated", 0, 1.2, core(ReactorCore::getRecirculationFlowFractionDemand))
            .number("recirc_capacity", "Recirculation capacity", "fraction of rated", 0, 1.2, ReactorControllerBlockEntity::getRecirculationCapacityFraction)
            .number("steam_generation", "Steam generation", "kg/s", 0, 2500, core(ReactorCore::getSteamGenerationKgPerS))
            .number("steam_outlet_flow", "Steam outlet flow", "kg/s", 0, 2500,
                    be -> be.core() == null ? Double.NaN : be.steamOutletFlowKgPerS())
            .number("feedwater_flow", "Feedwater flow demand", "kg/s", 0, 2500, core(ReactorCore::getFeedwaterFlowKgPerS))
            .number("feedwater_delivered", "Feedwater flow delivered", "kg/s", 0, 2500, core(ReactorCore::getDeliveredFeedwaterFlowKgPerS))
            .number("injection_flow", "Injection flow", "kg/s", 0, 2500, core(ReactorCore::getInjectionFlowKgPerS))
            .number("break_steam_flow", "Break steam flow", "kg/s", 0, 2500, core(ReactorCore::getBreakSteamFlowKgPerS))
            .number("break_liquid_flow", "Break liquid flow", "kg/s", 0, 5000, core(ReactorCore::getBreakLiquidFlowKgPerS))
            .number("shutdown_cooling_mw", "RHR shutdown cooling", "MW", 0, 500, core(ReactorCore::getShutdownCoolingMW))
            .number("vessel_metal_temp_c", "Vessel steel temperature", "degC", 0, 600,
                    core(c -> c.getPressureVessel().getMetalTemperatureC()))
            .number("cooldown_rate_c_per_hour", "Vessel cooldown rate", "degC/h", 0, 500,
                    core(c -> c.getBoundaryStress().getLastCooldownRateCPerHour()))
            .number("cooldown_thermal_stress", "Cooldown thermal-stress life used", "fraction", 0, 5,
                    core(c -> c.getBoundaryStress().getCooldownThermalStress()))
            .boolOrNone("interfacing_systems_loca", "Interfacing systems LOCA", core(c -> flag(c.getBoundaryStress().isInterfacingSystemsLoca())))
            .number("coolant_temp_c", "Coolant temperature", "degC", 0, 400, core(ReactorCore::getCoolantTemperatureC))
            .number("fuel_temp_c", "Fuel temperature", "degC", 0, 3000, core(ReactorCore::getFuelTemperatureC))
            .number("clad_temp_c", "Cladding temperature", "degC", 0, 2500, core(ReactorCore::getCladTemperatureC))
            .number("peak_clad_temp_c", "Peak cladding temperature", "degC", 0, 2500, core(ReactorCore::getPeakCladTemperatureC))
            .number("oxidation", "Cladding oxidation", "fraction", 0, 1, core(ReactorCore::getOxidationFraction))
            .number("hydrogen_kg", "Hydrogen generated", "kg", 0, 5000, core(ReactorCore::getHydrogenGeneratedKg))
            .number("boron_ppm", "Boron concentration", "ppm", 0, 1000, core(ReactorCore::getBoronPpm))
            .level("core_damage_stage", "Core damage stage", "stage", 0, 8, core(c -> c.getSevereAccident().getStage().number()))
            .number("containment_h2_percent", "Containment hydrogen", "%", 0, 100, core(c -> c.getContainment().getHydrogenPercent()))
            .number("containment_o2_percent", "Containment oxygen", "%", 0, 25, core(c -> c.getContainment().getOxygenPercent()))
            .number("containment_pressure", "Containment pressure", "psig", -5, 150, core(c -> c.getContainment().getPressurePsig()))
            .number("containment_steam_percent", "Containment steam", "%", 0, 100, core(c -> c.getContainment().getSteamPercent()))
            .number("containment_gas_temp_c", "Containment gas temperature", "degC", 0, 400, core(c -> c.getContainment().getGasTemperatureC()))
            .number("containment_drywell_pressure", "Drywell pressure", "psig", -5, 150, core(c -> c.getContainment().getDrywellPressurePsig()))
            .number("containment_wetwell_pressure", "Wetwell pressure", "psig", -5, 150, core(c -> c.getContainment().getWetwellPressurePsig()))
            .number("containment_pressure_nodes", "Containment pressure nodes", "nodes", 0, 2,
                    core(c -> c.getContainment().getPressureNodeCount()))
            .number("containment_spray_flow", "Containment spray", "kg/s", 0, 2500, core(c -> c.getContainment().getLastSprayFlowKgPerS()))
            .boolOrNone("containment_inerted", "Containment inerted", core(c -> flag(c.getContainment().isInerted())))
            .boolOrNone("containment_vent_open", "Containment vent open", core(c -> flag(c.getContainment().isVentOpen())))
            .boolOrNone("containment_failed", "Containment failed", core(c -> flag(c.getContainment().isFailed())))
            .boolOrNone("scram", "Scram active", core(c -> flag(c.isScramActive())))
            .boolOrNone("end_of_cycle", "End of cycle", core(c -> flag(c.isEndOfCycle())))
            .boolOrNone("crd_powered", "Control rod drive powered", core(c -> flag(c.isCrdPowered())))
            .boolOrNone("crd_water_supplied", "Control rod drive water supplied", core(c -> flag(c.isCrdWaterSupplied())))
            .number("average_burnup", "Average burnup", "MWd/t", 0, 60000, core(ReactorCore::getAverageBurnupMwdPerTonne))
            .number("rod_count", "Control rod count", "rods", 0, 300, core(ReactorCore::getControlRodCount))
            .number("charged_accumulators", "Charged accumulators", "rods", 0, 300, core(ReactorCore::getChargedAccumulatorCount))
            .number("rods_not_at_demand", "Rods not at demand", "rods", 0, 300,
                    be -> be.rods() == null ? Double.NaN : be.rods().getRodsNotAtDemandCount())
            .text("vessel_state", "Vessel state", be -> be.vesselState().getSerializedName())
            .text("core_damage_stage_name", "Core damage stage name",
                    be -> be.core() == null ? null : be.core().getSevereAccident().getStage().displayName())
            .level("selected_rod", "Selected rod (0 = none)", "rod", 0, MAX_RODS,
                    be -> be.isFormed() ? be.selectedRod() : Double.NaN)
            .dynamicSignals(ReactorSignals::rodSignals, ReactorSignals::readRod)
            .dynamicText(ReactorSignals::readText)
            .pulse("scram", "Scram", (be, v) -> DeviceCommands.reactorScram(be))
            .pulse("reset_scram", "Reset scram", (be, v) -> DeviceCommands.reactorResetScram(be))
            .command("recirc_flow", "Recirculation flow demand", Kind.NUMBER, 0, 1.2,
                    DeviceCommands::reactorRecirculationFlow)
            .command("boron_injection", "Boron injection rate", Kind.NUMBER, 0, 100,
                    DeviceCommands::reactorBoronInjection)
            .toggle("containment_inerting", "Containment inerting", (be, v) -> ContainmentEquipmentRegistry.command(be, false, v >= 0.5))
            .toggle("containment_vent", "Containment vent", (be, v) -> ContainmentEquipmentRegistry.command(be, true, v >= 0.5))
            .command("select_rod", "Select rod (0 = none)", Kind.LEVEL, 0, MAX_RODS, ReactorSignals::selectRod)
            .pulse("selected_rod_insert", "Selected rod: insert one notch",
                    (be, v) -> DeviceCommands.reactorStepSelectedRod(be, -1))
            .pulse("selected_rod_withdraw", "Selected rod: withdraw one notch",
                    (be, v) -> DeviceCommands.reactorStepSelectedRod(be, 1))
            .toggle("selected_rod_insert_continuous", "Selected rod: insert continuously (1 holds, 0 releases)",
                    (be, v) -> DeviceCommands.reactorHoldSelectedRod(be, -1, v >= 0.5))
            .toggle("selected_rod_withdraw_continuous", "Selected rod: withdraw continuously (1 holds, 0 releases)",
                    (be, v) -> DeviceCommands.reactorHoldSelectedRod(be, 1, v >= 0.5))
            .dynamicCommands(ReactorSignals::rodCommands, ReactorSignals::rodCommand);

    private static List<SignalSpec> rodSignals(ReactorControllerBlockEntity be) {
        ReactorCore core = be.core();
        if (core == null) return List.of();
        int n = core.getControlRodCount();
        List<SignalSpec> specs = new ArrayList<>(n * 3);
        for (int i = 1; i <= n; i++) {
            specs.add(new SignalSpec("rod_" + i + "_position", "Rod " + i + " position", "notch", 0, MAX_ROD_LABEL, Kind.LEVEL));
            specs.add(new SignalSpec("rod_" + i + "_demand", "Rod " + i + " demand", "notch", 0, MAX_ROD_LABEL, Kind.LEVEL));
            specs.add(new SignalSpec("rod_" + i + "_accumulator", "Rod " + i + " accumulator", "fraction", 0, 1, Kind.NUMBER));
        }
        // The display indications follow the original per-rod signals, so those keep their place in a picker list.
        for (int i = 1; i <= n; i++) {
            for (String flag : ROD_FLAGS) {
                specs.add(new SignalSpec("rod_" + i + "_" + flag, "Rod " + i + " " + flag.replace('_', ' '), "", 0, 1, Kind.BOOLEAN));
            }
            specs.add(new SignalSpec("rod_" + i + "_lattice_x", "Rod " + i + " core x", "", 0, 99, Kind.LEVEL));
            specs.add(new SignalSpec("rod_" + i + "_lattice_y", "Rod " + i + " core y", "", 0, 99, Kind.LEVEL));
            specs.add(new SignalSpec("rod_" + i + "_label", "Rod " + i + " coordinate (XX-YY)", "", 0, 0, Kind.TEXT));
        }
        CoreRodMap map = be.coreMap();
        if (map != null) {
            specs.add(new SignalSpec("lprm_count", "LPRM strings", "strings", 0, 100, Kind.LEVEL));
            int strings = map.lprmStrings().size();
            for (int s = 1; s <= strings; s++) {
                specs.add(new SignalSpec("lprm_" + s + "_label", "LPRM string " + s + " coordinate (XX-YY)", "", 0, 0, Kind.TEXT));
                for (char level = 'a'; level < 'a' + LprmReadout.LEVELS; level++) {
                    String at = "LPRM " + s + " " + Character.toUpperCase(level);
                    specs.add(new SignalSpec("lprm_" + s + "_" + level + "_percent", at + " indication", "%", 0, 125, Kind.NUMBER));
                    for (String flag : LPRM_FLAGS) {
                        specs.add(new SignalSpec("lprm_" + s + "_" + level + "_" + flag, at + " " + flag, "", 0, 1, Kind.BOOLEAN));
                    }
                }
            }
        }
        for (var detector : core.getStartupDetectorLocations()) {
            String prefix = detector.range().toLowerCase(java.util.Locale.ROOT) + "_" + (detector.channel()+1) + "_";
            for (String field : new String[]{"lattice_x","lattice_y","lprm_string"})
                specs.add(new SignalSpec(prefix+field, detector.label()+" "+field,"",0,200,Kind.LEVEL));
        }
        return specs;
    }

    private static final String[] ROD_FLAGS = {"drift", "scram", "full_in", "full_out", "accum_trouble"};
    private static final String[] LPRM_FLAGS = {"bypassed", "failed", "downscale", "upscale"};

    private static List<CommandSpec> rodCommands(ReactorControllerBlockEntity be) {
        ReactorCore core = be.core();
        if (core == null) return List.of();
        List<CommandSpec> specs = new ArrayList<>();
        for (int i = 1; i <= core.getControlRodCount(); i++)
            specs.add(new CommandSpec("rod_" + i + "_position", "Rod " + i + " position", Kind.LEVEL, 0, MAX_ROD_LABEL));
        return specs;
    }

    /** Parses {@code rod_<n>_<field>}; returns {n, field} or null. */
    private static Object[] parseRod(String key) {
        if (!key.startsWith("rod_")) return null;
        int end = key.indexOf('_', 4);
        if (end < 0) return null;
        try {
            return new Object[]{Integer.parseInt(key.substring(4, end)), key.substring(end + 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OptionalDouble readRod(ReactorControllerBlockEntity be, String key) {
        if (key.startsWith("lprm_")) return readLprm(be, key);
        if (be.core()!=null && (key.startsWith("srm_")||key.startsWith("irm_"))) {
            for (var d:be.core().getStartupDetectorLocations()) {
                String prefix=d.range().toLowerCase(java.util.Locale.ROOT)+"_"+(d.channel()+1)+"_";
                if(key.equals(prefix+"lattice_x"))return OptionalDouble.of(d.x());
                if(key.equals(prefix+"lattice_y"))return OptionalDouble.of(d.y());
                if(key.equals(prefix+"lprm_string"))return OptionalDouble.of(d.stringIndex()+1);
            }
            return OptionalDouble.empty();
        }
        Object[] parsed = parseRod(key);
        ReactorCore core = be.core();
        if (parsed == null || core == null) return OptionalDouble.empty();
        int rod = (Integer) parsed[0];
        if (rod < 1 || rod > core.getControlRodCount()) return OptionalDouble.empty();
        return switch ((String) parsed[1]) {
            case "position" -> OptionalDouble.of(core.getRodNotchLabel(rod - 1));
            case "demand" -> be.rods() == null ? OptionalDouble.empty() : OptionalDouble.of(be.rods().getCommandedNotchLabel(rod - 1));
            case "accumulator" -> OptionalDouble.of(core.getAccumulatorCharge(rod - 1));
            // The display indications read nothing unless the reactor is formed (the core of a broken one is kept for its
            // restore, and the older signals above still read it).
            case "drift" -> !be.isFormed() ? OptionalDouble.empty() : OptionalDouble.of(flag(RodIndications.drift(core, be.rods(), rod - 1)));
            case "scram" -> !be.isFormed() ? OptionalDouble.empty() : OptionalDouble.of(flag(RodIndications.scram(core, rod - 1)));
            case "full_in" -> !be.isFormed() ? OptionalDouble.empty() : OptionalDouble.of(flag(RodIndications.fullIn(core, rod - 1)));
            case "full_out" -> !be.isFormed() ? OptionalDouble.empty() : OptionalDouble.of(flag(RodIndications.fullOut(core, rod - 1)));
            case "accum_trouble" -> !be.isFormed() ? OptionalDouble.empty() : OptionalDouble.of(flag(RodIndications.accumulatorTrouble(core,
                    rod - 1, RodIndications.DEFAULT_ACCUMULATOR_FRACTION)));
            case "lattice_x" -> be.coreMap() == null ? OptionalDouble.empty() : OptionalDouble.of(be.coreMap().latticeX(rod - 1));
            case "lattice_y" -> be.coreMap() == null ? OptionalDouble.empty() : OptionalDouble.of(be.coreMap().latticeY(rod - 1));
            default -> OptionalDouble.empty();
        };
    }

    private static Optional<String> readText(ReactorControllerBlockEntity be, String key) {
        CoreRodMap map = be.coreMap();
        if (map == null) return Optional.empty();
        if (key.startsWith("lprm_")) {
            int[] at = parseLprm(key);
            return at != null && key.endsWith("_label") && at[0] >= 1 && at[0] <= map.lprmStrings().size()
                    ? Optional.of(map.lprmStrings().get(at[0] - 1).label()) : Optional.empty();
        }
        Object[] parsed = parseRod(key);
        if (parsed == null || !"label".equals(parsed[1])) return Optional.empty();
        int rod = (Integer) parsed[0];
        return rod >= 1 && rod <= map.rodCount() ? Optional.of(map.label(rod - 1)) : Optional.empty();
    }

    /** Parses the string number of {@code lprm_<s>_...}; returns {s} or null. */
    private static int[] parseLprm(String key) {
        int end = key.indexOf('_', 5);
        if (end < 0) return null;
        try {
            return new int[]{Integer.parseInt(key.substring(5, end))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code lprm_count}, {@code lprm_<s>_<a-d>_<percent|bypassed|failed|downscale|upscale>}. */
    private static OptionalDouble readLprm(ReactorControllerBlockEntity be, String key) {
        ReactorCore core = be.core();
        CoreRodMap map = be.coreMap();
        if (core == null || map == null) return OptionalDouble.empty();
        if (key.equals("lprm_count")) return OptionalDouble.of(map.lprmStrings().size());
        int[] at = parseLprm(key);
        if (at == null || at[0] < 1 || at[0] > map.lprmStrings().size()) return OptionalDouble.empty();
        String rest = key.substring(key.indexOf('_', 5) + 1);
        if (rest.length() < 3 || rest.charAt(1) != '_' || rest.charAt(0) < 'a' || rest.charAt(0) >= 'a' + LprmReadout.LEVELS)
            return OptionalDouble.empty();
        int string = at[0] - 1;
        int level = rest.charAt(0) - 'a';
        String field = rest.substring(2);
        double percent = LprmReadout.percent(core, string, level);
        boolean bypassed = LprmReadout.bypassed(core, string, level);
        boolean failed = LprmReadout.failed(core, string, level);
        return switch (field) {
            case "percent" -> OptionalDouble.of(percent);
            case "bypassed" -> OptionalDouble.of(flag(bypassed));
            case "failed" -> OptionalDouble.of(flag(failed));
            case "downscale" -> OptionalDouble.of(flag(LprmReadout.downscale(percent, bypassed, failed,
                    LprmReadout.DEFAULT_DOWNSCALE_PERCENT)));
            case "upscale" -> OptionalDouble.of(flag(LprmReadout.upscale(percent, bypassed, failed,
                    LprmReadout.DEFAULT_UPSCALE_PERCENT)));
            default -> OptionalDouble.empty();
        };
    }

    private static boolean rodCommand(ReactorControllerBlockEntity be, String key, double value) {
        Object[] parsed = parseRod(key);
        if (parsed == null || !"position".equals(parsed[1]) || !Double.isFinite(value) || value != Math.rint(value)
                || value < 0 || value > MAX_ROD_LABEL) return false;
        return DeviceCommands.reactorRodPosition(be, (Integer) parsed[0] - 1, (int) value);
    }

    static SignalProvider of(ReactorControllerBlockEntity be) { return SCHEMA.bind(be); }
}
