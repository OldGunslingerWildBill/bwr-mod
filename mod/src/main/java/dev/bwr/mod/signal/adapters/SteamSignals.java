package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.DeviceReadouts;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.mod.steam.TurbineValveBlockEntity;

/** Main steam path: MSIV, SRV, turbine valves, turbine steam outlet, RPV steam outlet. */
final class SteamSignals {
    private SteamSignals() {}

    static final Schema<MainSteamIsolationValveBlockEntity> MSIV = new Schema<MainSteamIsolationValveBlockEntity>()
            .bool("demand_open", "Demanded open", MainSteamIsolationValveBlockEntity::isDemandOpen)
            .number("position", "Valve position", "fraction open", 0, 1, MainSteamIsolationValveBlockEntity::getPosition)
            .bool("powered", "Actuator powered", MainSteamIsolationValveBlockEntity::isPowered)
            .number("energy_fe", "Actuator energy", "FE", 0, MainSteamIsolationValveBlockEntity.ENERGY_CAPACITY_FE,
                    be -> be.energy().getEnergyStored())
            .bool("computer_controlled", "Computer controlled", MainSteamIsolationValveBlockEntity::isComputerControlled)
            .toggle("open", "Open / close", (be, v) -> DeviceCommands.msivCommand(be, v >= 0.5));

    static final Schema<SafetyReliefValveBlockEntity> SRV = new Schema<SafetyReliefValveBlockEntity>()
            .bool("open", "Open", SafetyReliefValveBlockEntity::isOpen)
            .bool("computer_controlled", "Computer controlled", SafetyReliefValveBlockEntity::isComputerControlled)
            .bool("discharge_submerged", "Discharge submerged", SafetyReliefValveBlockEntity::isDischargeSubmerged)
            .number("flow", "Relief flow", "kg/s", 0, SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S, DeviceReadouts::srvFlow)
            .level("division", "ADS division", "division", 0, 4, SafetyReliefValveBlockEntity::getDivision)
            .toggle("open", "Open / close", (be, v) -> { DeviceCommands.srvOpen(be, v >= 0.5); return true; })
            .command("division", "ADS division", Kind.LEVEL, 0, 4, (be, v) -> {
                if (v != Math.rint(v)) return false;
                be.setDivision((int) v);
                return true;
            })
            .bool("ads_allowed", "ADS may open this valve", SafetyReliefValveBlockEntity::isAdsAllowed)
            .toggle("ads_allow", "ADS may open this valve", (be, v) -> {
                be.setAdsAllowed(v >= 0.5);
                return true;
            });

    static final Schema<TurbineValveBlockEntity> TURBINE_VALVE = new Schema<TurbineValveBlockEntity>()
            .number("target", "Demanded opening", "fraction open", 0, 1, TurbineValveBlockEntity::target)
            .number("position", "Valve position", "fraction open", 0, 1, TurbineValveBlockEntity::position)
            .number("flow", "Steam flow", "kg/s", 0, 2500, TurbineValveBlockEntity::flowKgPerS)
            .bool("stop_valve", "Is a stop valve", TurbineValveBlockEntity::stopValve)
            .command("position", "Opening demand", Kind.NUMBER, 0, 1, DeviceCommands::turbineValvePosition);

    static final Schema<TurbineSteamOutletBlockEntity> TURBINE_OUTLET = new Schema<TurbineSteamOutletBlockEntity>()
            .bool("attached", "Attached", TurbineSteamOutletBlockEntity::isAttached)
            .number("commanded_flow", "Commanded flow", "kg/s", 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    TurbineSteamOutletBlockEntity::getCommandedFlowKgPerS)
            .number("actual_flow", "Delivered flow", "kg/s", 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    TurbineSteamOutletBlockEntity::getDeliveredFlowKgPerS)
            .number("steam_production", "Steam production", "kg/s", 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    TurbineSteamOutletBlockEntity::getSteamProductionKgPerS)
            .number("buffer_fill", "Steam buffer fill", "fraction", 0, 1, DeviceReadouts::turbineOutletBufferFill)
            .number("buffered_mb", "Buffered steam", "mB", 0, 1e9, TurbineSteamOutletBlockEntity::getBufferedMilliBuckets)
            .bool("nozzle_fed", "Nozzle fed", TurbineSteamOutletBlockEntity::isNozzleFed)
            .number("feed_nozzles", "Feed nozzle count", "nozzles", 0, 64, TurbineSteamOutletBlockEntity::getFeedNozzleCount)
            .number("nozzle_flow", "Nozzle flow", "kg/s", 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    TurbineSteamOutletBlockEntity::nozzleFlowKgPerS)
            .number("nozzle_supply", "Nozzle supply", "kg/s", 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    TurbineSteamOutletBlockEntity::getNozzleSupplyKgPerS)
            .number("isolation_valve_open", "Isolation valve open fraction", "fraction open", 0, 1,
                    TurbineSteamOutletBlockEntity::getMainSteamLineOpenFraction)
            .bool("computer_controlled", "Computer controlled", TurbineSteamOutletBlockEntity::isComputerControlled)
            .command("flow", "Steam flow demand", Kind.NUMBER, 0, TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S,
                    DeviceCommands::turbineOutletFlow)
            .command("flow_fraction", "Steam flow demand (fraction of rated)", Kind.NUMBER, 0, 1,
                    DeviceCommands::turbineOutletFlowFraction);

    static final Schema<RpvSteamOutletBlockEntity> RPV_OUTLET = new Schema<RpvSteamOutletBlockEntity>()
            .number("position", "Nozzle position", "fraction open", 0, 1, RpvSteamOutletBlockEntity::getPosition)
            .number("flow", "Steam flow", "kg/s", 0, RpvSteamOutletBlockEntity.CAPACITY_KG_PER_S, DeviceReadouts::rpvOutletFlow)
            .bool("steam_line_attached", "Steam line attached", RpvSteamOutletBlockEntity::isSteamLineAttached)
            .bool("part_of_formed_reactor", "Part of formed reactor", RpvSteamOutletBlockEntity::isPartOfFormedReactor)
            .bool("computer_controlled", "Computer controlled", RpvSteamOutletBlockEntity::isComputerControlled);
}
