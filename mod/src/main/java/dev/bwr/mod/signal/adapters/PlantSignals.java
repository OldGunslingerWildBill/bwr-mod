package dev.bwr.mod.signal.adapters;

import dev.bwr.core.turbine.SurfaceCondenser;
import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.power.TurbineAuxTrain;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;

/** Suppression pool, RHR heat exchanger, condenser, condensate tank, cooling units and power modules. */
final class PlantSignals {
    private PlantSignals() {}

    static final Schema<SuppressionPoolBlockEntity> POOL = new Schema<SuppressionPoolBlockEntity>()
            .bool("formed", "Pool formed", SuppressionPoolBlockEntity::isFormed)
            .number("temperature", "Pool temperature", "degC", 0, 200, be -> be.pool().getTemperatureC())
            .number("saturation_temperature", "Saturation temperature", "degC", 0, 200, be -> be.pool().getSaturationTemperatureC())
            .number("subcooling", "Subcooling", "degC", 0, 100, be -> be.pool().getSubcoolingC())
            .number("condensation_effectiveness", "Condensation effectiveness", "fraction", 0, 1, be -> be.pool().condensationEffectiveness())
            .number("uncondensed_steam", "Uncondensed steam", "kg/s", 0, 2500, be -> be.pool().getUncondensedSteamKgPerS())
            .bool("boiling", "Boiling", be -> be.pool().isBoiling())
            .number("remaining_heat_capacity", "Remaining heat capacity", "MJ", 0, 1e6, be -> be.pool().getRemainingHeatCapacityMJ())
            .number("mass", "Pool mass", "kg", 0, 1e7, be -> be.pool().getMassKg())
            .number("level", "Pool level", "fraction", 0, 1, be -> be.pool().getLevelFraction())
            .number("rhr_duty", "RHR duty demand", "fraction", 0, 1, SuppressionPoolBlockEntity::getRhrDuty)
            .number("rhr_duty_effective", "RHR duty effective", "fraction", 0, 1, SuppressionPoolBlockEntity::getEffectiveRhrDuty)
            .number("discharging_valves", "Discharging relief valves", "valves", 0, 64, SuppressionPoolBlockEntity::dischargingValveCount)
            .number("steam_in", "Steam inflow", "kg/s", 0, 2500, SuppressionPoolBlockEntity::steamInKgPerS)
            .number("passive_cooling", "Passive cooling", "MW", 0, 100, SuppressionPoolBlockEntity::passiveCoolingMW)
            .bool("spray_mode", "Spray mode", be -> be.pool().isSprayMode())
            .command("rhr_duty", "RHR duty demand", Kind.NUMBER, 0, 1, (be, v) -> { be.setRhrDuty(v); return true; })
            .toggle("drains_open", "Drain valves open", (be, v) -> { be.setDrainsOpen(v >= 0.5); return true; });

    static final Schema<RhrHeatExchangerBlockEntity> HEAT_EXCHANGER = new Schema<RhrHeatExchangerBlockEntity>()
            .bool("pool_connected", "Pool connected", be -> be.returnPool() != null)
            .number("cold_water", "Cold side water", "kg", 0, 1e6, be -> be.plant().cold())
            .number("hot_water", "Hot side water", "kg", 0, 1e6, be -> be.plant().hot())
            .number("heat_rejected", "Heat rejected", "MW", 0, 500, be -> be.plant().heatMW())
            .number("hot_water_temp", "Hot side temperature", "degC", 0, 200, be -> be.plant().hotTemperatureC())
            .number("cold_water_temp", "Cold side temperature", "degC", 0, 200, be -> be.plant().coldTemperatureC())
            .number("primary_flow", "Primary flow", "kg/s", 0, 2000, be -> be.plant().primaryFlow())
            .number("secondary_flow", "Secondary flow", "kg/s", 0, 2000, be -> be.plant().secondaryFlow())
            .number("primary_outlet_temp", "Primary outlet temperature", "degC", 0, 200, be -> be.plant().primaryOutC())
            .number("primary_inlet_temp", "Tube side inlet temperature", "degC", 0, 200, be -> be.plant().primaryInC())
            .number("secondary_inlet_temp", "Shell side inlet temperature", "degC", 0, 200, be -> be.plant().secondaryInC())
            .number("secondary_outlet_temp", "Shell side outlet temperature", "degC", 0, 200, be -> be.plant().secondaryOutC())
            .bool("shell_outlet_backed_up", "Shell outlet backed up", be -> be.plant().hotFill() >= 0.95);

    static final Schema<dev.bwr.mod.water.WaterOutletChuteBlockEntity> WATER_OUTLET_CHUTE = new Schema<dev.bwr.mod.water.WaterOutletChuteBlockEntity>()
            .bool("facing_water", "Facing open water", dev.bwr.mod.water.WaterOutletChuteBlockEntity::clearMouth)
            .bool("enabled", "Chute open", dev.bwr.mod.water.WaterOutletChuteBlockEntity::enabled)
            .number("flow", "Discharge flow", "kg/s", 0, 60000, dev.bwr.mod.water.WaterOutletChuteBlockEntity::flowKgPerS)
            .number("discharge_temp", "Discharge temperature", "degC", 0, 200, dev.bwr.mod.water.WaterOutletChuteBlockEntity::temperatureC)
            .number("heat_to_river", "Heat returned to the river", "MW", 0, 500, dev.bwr.mod.water.WaterOutletChuteBlockEntity::heatToRiverMW)
            .number("returned", "Water returned", "kg", 0, 1e12, dev.bwr.mod.water.WaterOutletChuteBlockEntity::totalKg)
            .toggle("enabled", "Chute open", (be, v) -> { be.setEnabled(v >= 0.5); return true; });

    static final Schema<CondenserBlockEntity> CONDENSER = new Schema<CondenserBlockEntity>()
            .bool("ready", "Ready", CondenserBlockEntity::ready)
            .number("cold_water", "Cold water", "kg", 0, SurfaceCondenser.COOLING_CAPACITY, be -> be.plant().cold())
            .number("hot_water", "Hot water", "kg", 0, SurfaceCondenser.COOLING_CAPACITY, be -> be.plant().hot())
            .number("condensate", "Hotwell condensate", "kg", 0, SurfaceCondenser.CONDENSATE_CAPACITY, be -> be.plant().condensate())
            .number("steam", "Steam in condenser", "kg", 0, 1e6, be -> be.plant().steam.mass())
            .number("hotwell_temp", "Hotwell temperature", "degC", 0, 150, be -> be.plant().condensateC())
            .number("makeup_space", "Hotwell makeup space", "kg", 0, SurfaceCondenser.CONDENSATE_CAPACITY,
                    be -> SurfaceCondenser.CONDENSATE_CAPACITY - be.plant().condensate())
            .number("steam_rate", "Steam inflow", "kg/s", 0, 2500, be -> be.plant().steamRate())
            .number("cooling_rate", "Cooling water flow", "kg/s", 0, 20000, be -> be.plant().coolingRate())
            .number("heat_rejected", "Heat rejected", "MW", 0, 4000, be -> be.plant().rejectedMW())
            .number("cold_water_temp", "Cold water temperature", "degC", 0, 100, be -> be.plant().coldC())
            .number("hot_water_temp", "Hot water temperature", "degC", 0, 100, be -> be.plant().hotC())
            .number("backpressure", "Backpressure", "psia", 0, 30, be -> be.plant().backpressurePsia())
            .number("vacuum", "Vacuum", "inHg", 0, 30, be -> be.plant().vacuumInHg());

    static final Schema<CondensateStorageTankBlockEntity> CONDENSATE_TANK = new Schema<CondensateStorageTankBlockEntity>()
            .number("stored", "Stored water", "kg", 0, 1e7, CondensateStorageTankBlockEntity::storedKg)
            .number("capacity", "Capacity", "kg", 0, 1e7, CondensateStorageTankBlockEntity::capacityKg)
            .number("level", "Level", "fraction", 0, 1, CondensateStorageTankBlockEntity::levelFraction)
            .number("temperature", "Temperature", "degC", 0, 150, CondensateStorageTankBlockEntity::temperatureC)
            .bool("ready", "Ready", CondensateStorageTankBlockEntity::ready);

    static final Schema<CoolingBlockEntity> COOLING = new Schema<CoolingBlockEntity>()
            .bool("ready", "Ready", CoolingBlockEntity::ready)
            .number("speed_demand", "Speed demand", "fraction", 0, 1, CoolingBlockEntity::target)
            .number("speed", "Actual speed", "fraction", 0, 1, CoolingBlockEntity::actual)
            .number("input", "Inlet water", "kg", 0, 1e6, be -> be.plant().input())
            .number("output", "Outlet water", "kg", 0, 1e6, be -> be.plant().output())
            .number("flow", "Water flow", "kg/s", 0, 20000, be -> be.plant().flow())
            .number("rated_flow", "Rated flow", "kg/s", 0, 20000, be -> be.design().flow)
            .number("makeup", "Makeup rate", "kg/s", 0, 1000, be -> be.plant().loss())
            .number("heat_rejected", "Heat rejected", "MW", 0, 4000, be -> be.plant().heatMW())
            .number("energy_fe", "Stored energy", "FE", 0, 1e7, CoolingBlockEntity::storedFE)
            .number("draw_fe_per_tick", "Electrical draw", "FE/t", 0, 1e5, CoolingBlockEntity::draw)
            .number("inlet_temp", "Inlet temperature", "degC", 0, 100, be -> be.plant().inputC())
            .number("outlet_temp", "Outlet temperature", "degC", 0, 100, be -> be.plant().outputC())
            .command("speed", "Speed demand", Kind.NUMBER, 0, 1, DeviceCommands::coolingSpeed);

    static final Schema<PowerModuleBlockEntity> POWER_MODULE = new Schema<PowerModuleBlockEntity>()
            .bool("running", "Running", be -> be.running)
            .number("rpm", "Shaft speed", "rpm", 0, 4000, be -> be.rpm)
            .number("speed_percent", "Rated shaft speed", "%", 0, 300, PowerModuleBlockEntity::speedPercent)
            .bool("overspeed", "Shaft overspeed", PowerModuleBlockEntity::overspeed)
            .number("kinetic_energy_mj", "Module rotor energy", "MJ", 0, 1e6, be -> be.kineticEnergyKJ()/1000)
            .number("shaft_loss_mw", "Train mechanical losses", "MW", 0, 1e5, be -> be.trainLossKJ*20/1000)
            .number("damage", "Shaft damage", "fraction", 0, 1, PowerModuleBlockEntity::damage)
            .bool("failed", "Generator failed", PowerModuleBlockEntity::failed)
            .number("delivered_mw_now", "Delivered power", "MW", 0, 1e6, be -> dev.bwr.core.grid.EnergyUnits.mw(be.delivered().lastTickFE()))
            .number("delivered_mw_1s", "Delivered power 1 second", "MW", 0, 1e6, be -> dev.bwr.core.grid.EnergyUnits.mw(be.delivered().averageFE(20)))
            .number("delivered_mw_1min", "Delivered power 1 minute", "MW", 0, 1e6, be -> dev.bwr.core.grid.EnergyUnits.mw(be.delivered().averageFE(1200)))
            .number("delivered_mwh", "Delivered energy", "MWh", 0, Double.MAX_VALUE, be -> dev.bwr.core.grid.EnergyUnits.mwh(be.delivered().totalFE()))
            .number("generated_mw_1s", "Generated power 1 second", "MW", 0, 1500, PowerModuleBlockEntity::generatedMW1s)
            .number("generated_fe_tick", "Generated FE last tick", "FE/t", 0, Double.MAX_VALUE, PowerModuleBlockEntity::generatedFELastTick)
            .number("delivered_fe_tick", "Delivered FE last tick", "FE/t", 0, Double.MAX_VALUE, be -> be.delivered().lastTickFE())
            .number("buffer_delta_fe_tick", "Buffer change last tick", "FE/t", -2e9, 2e9, PowerModuleBlockEntity::bufferDeltaFELastTick)
            .number("sample_ticks", "Completed samples", "ticks", 0, 1200, be -> be.delivered().sampleTicks())
            .bool("window_ready_1s", "One second window ready", be -> be.delivered().sampleTicks()>=20)
            .bool("window_ready_1min", "One minute window ready", be -> be.delivered().sampleTicks()>=1200)
            .bool("counter_saturated", "Energy counter saturated", be -> be.delivered().saturated())
            .number("flow", "Steam flow", "kg/s", 0, 2500, be -> be.flowKgPerS)
            .number("shaft_mw", "Shaft power", "MW", 0, 1500, be -> be.shaftMW)
            .number("electric_mw", "Electrical output", "MW", 0, 1500, be -> be.electricMW)
            .number("train_mw", "Train power", "MW", 0, 1500, be -> be.trainMW)
            .number("inlet_pressure", "Inlet pressure", "psia", 0, 1200, be -> be.inletPsia)
            .number("outlet_pressure", "Outlet pressure", "psia", 0, 1200, be -> be.outletPsia)
            .number("inlet_temp", "Inlet temperature", "degC", 0, 350, be -> be.inletC)
            .number("outlet_temp", "Outlet temperature", "degC", 0, 350, be -> be.outletC)
            .number("water", "Water stored", "kg", 0, PowerModuleBlockEntity.WATER_CAPACITY, PowerModuleBlockEntity::waterStored)
            .number("stored_fe", "Stored energy", "FE", 0, PowerModuleBlockEntity.ENERGY_CAPACITY, PowerModuleBlockEntity::energyStored)
            .text("status", "Status", be -> be.status)
            // Turbine auxiliaries (TURBINE-AUX.md). A system the train does not have reads "no signal"; the alarms are readings, nothing trips.
            .bool("aux_governed", "Auxiliaries govern this train", be -> be.aux().lubeGoverned || be.aux().bowGoverned || be.aux().genGoverned)
            .number("oil_pressure", "Lube oil pressure", "psig", 0, 40, be -> be.aux().lubeGoverned ? be.aux().oilPsig : Double.NaN)
            .number("oil_temp", "Lube oil temperature", "degC", 0, 120, be -> be.aux().lubeGoverned ? be.aux().oilC : Double.NaN)
            .number("oil_level", "Lube oil level", "fraction", 0, 1, be -> be.aux().lubeGoverned ? be.aux().oilLevel : Double.NaN)
            .number("oil_film", "Bearing oil film", "fraction", 0, 1, be -> be.aux().lubeGoverned ? be.aux().oilFilm : Double.NaN)
            .number("bearing_temp", "Bearing temperature", "degC", 0, 200, be -> be.aux().lubeGoverned ? be.bearingC : Double.NaN)
            .number("vibration", "Shaft vibration", "mm/s", 0, 30, be -> be.aux().bowGoverned ? be.vibration : Double.NaN)
            .number("rotor_temp", "Rotor temperature", "degC", 0, 400, be -> be.aux().bowGoverned && !be.block().generator() ? be.rotorC : Double.NaN)
            .number("rotor_bow", "Rotor bow", "fraction", 0, 1, be -> be.aux().bowGoverned && !be.block().generator() ? be.bow() : Double.NaN)
            .boolOrNone("turning_gear", "Turning gear engaged", be -> be.block().generator() ? (be.turningGear() ? 1 : 0) : Double.NaN)
            .boolOrNone("turning_gear_rolling", "Turning gear rolling the shaft", be -> be.block().generator() ? (be.aux().gearRolling ? 1 : 0) : Double.NaN)
            .number("winding_temp", "Generator winding temperature", "degC", 0, 250, be -> be.block().generator() && be.aux().genGoverned ? be.windingC : Double.NaN)
            .number("stator_flow", "Stator water flow", "fraction", 0, 1, be -> be.block().generator() && be.aux().genGoverned ? be.aux().statorFlow : Double.NaN)
            .number("h2_pressure", "Hydrogen pressure", "psig", 0, 40, be -> be.block().generator() && be.aux().genGoverned ? be.aux().h2Psig : Double.NaN)
            .number("coolant_temp", "Generator coolant temperature", "degC", 0, 150, be -> be.block().generator() && be.aux().genGoverned ? be.aux().coolantC : Double.NaN)
            .boolOrNone("oil_pressure_alarm", "Oil pressure alarm", be -> be.aux().lubeGoverned ? (be.aux().oilPressureAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("oil_level_alarm", "Oil level alarm", be -> be.aux().lubeGoverned ? (be.aux().oilLevelAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("oil_temp_alarm", "Oil temperature alarm", be -> be.aux().lubeGoverned ? (be.aux().oilTempAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("bearing_alarm", "Bearing temperature alarm", be -> be.aux().lubeGoverned ? (be.aux().bearingAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("vibration_alarm", "Vibration alarm", be -> be.aux().bowGoverned ? (be.aux().vibrationAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("winding_alarm", "Winding temperature alarm", be -> be.block().generator() && be.aux().genGoverned ? (be.aux().windingAlarm ? 1 : 0) : Double.NaN)
            .boolOrNone("h2_alarm", "Hydrogen pressure alarm", be -> be.block().generator() && be.aux().genGoverned ? (be.aux().h2Alarm ? 1 : 0) : Double.NaN)
            // A wrecked casing and a fire (TURBINE-MAINTENANCE.md): readings for a panel or a program; nothing acts on them.
            .bool("fire", "Turbine fire", PowerModuleBlockEntity::burning)
            .number("fire_fuel", "Fire fuel left (oil L, hydrogen kg)", "", 0, 20_000, PowerModuleBlockEntity::fireFuel)
            .bool("wrecked", "Casing wrecked (burst or shaft out)", PowerModuleBlockEntity::wrecked)
            .number("wreck", "Wreck: 0 sound, 1 holed, 2 burst, 3 shaft out", "", 0, 3, PowerModuleBlockEntity::wreck)
            .toggle("turning_gear", "Turning gear on / off", (be, v) -> be.setTurningGear(v >= 0.5));

    /** The Main Oil Tank. Its pumps are the player's switches. */
    static final Schema<TurbineAuxBlockEntity> OIL_TANK = new Schema<TurbineAuxBlockEntity>()
            .bool("attached", "Attached to a train", be -> be.module() != null)
            .bool("ac_pump", "AC pump switched on", TurbineAuxBlockEntity::acPumpOn)
            .bool("dc_pump", "DC pump switched on", TurbineAuxBlockEntity::dcPumpOn)
            .bool("ac_running", "AC pump running", TurbineAuxBlockEntity::acRunning)
            .bool("dc_running", "DC pump running", TurbineAuxBlockEntity::dcRunning)
            .number("oil_level", "Oil level", "fraction", 0, 1, TurbineAuxBlockEntity::oilFraction)
            .number("oil_litres", "Oil in the tank", "L", 0, dev.bwr.core.turbine.TurbineAux.OIL_TANK_L, TurbineAuxBlockEntity::oilLitres)
            .number("oil_temp", "Oil temperature", "degC", 0, 120, TurbineAuxBlockEntity::oilC)
            .number("oil_pressure", "Header pressure", "psig", 0, 40, TurbineAuxBlockEntity::psig)
            .number("oil_film", "Bearing oil film", "fraction", 0, 1, TurbineAuxBlockEntity::film)
            .number("cooler_duty", "Oil cooler duty", "kW", 0, 5000, TurbineAuxBlockEntity::coolerKw)
            .number("cold_water", "Cold cooling water", "kg", 0, TurbineAuxBlockEntity.WATER_CAPACITY, TurbineAuxBlockEntity::coldKg)
            .number("hot_water", "Warmed cooling water", "kg", 0, TurbineAuxBlockEntity.WATER_CAPACITY, TurbineAuxBlockEntity::hotKg)
            .number("ac_fe", "AC bus stored", "FE", 0, TurbineAuxBlockEntity.AC_CAPACITY_FE, TurbineAuxBlockEntity::acFe)
            .number("dc_fe", "DC bus stored", "FE", 0, TurbineAuxBlockEntity.DC_CAPACITY_FE, TurbineAuxBlockEntity::dcFe)
            .toggle("ac_pump", "AC pump on / off", (be, v) -> { be.setAcPump(v >= 0.5); return true; })
            .toggle("dc_pump", "DC pump on / off", (be, v) -> { be.setDcPump(v >= 0.5); return true; });

    /** The Shaft-Driven Main Oil Pump: no switch, it turns with the shaft. */
    static final Schema<TurbineAuxBlockEntity> SHAFT_PUMP = new Schema<TurbineAuxBlockEntity>()
            .bool("attached", "On a generator shaft end", be -> be.module() != null)
            .number("delivery", "Delivery", "fraction", 0, 1, be -> be.module() == null ? 0 : dev.bwr.core.turbine.TurbineAux.shaftPumpFlow(be.module().rpm));

    /** The Generator Cooling Skid. */
    static final Schema<TurbineAuxBlockEntity> GEN_COOLING = new Schema<TurbineAuxBlockEntity>()
            .bool("attached", "Attached to a generator", be -> be.module() != null)
            .bool("stator_pumps", "Stator pumps switched on", TurbineAuxBlockEntity::statorPumpsOn)
            .bool("stator_running", "Stator pumps running", TurbineAuxBlockEntity::statorRunning)
            .number("stator_flow", "Stator water flow", "fraction", 0, 1, TurbineAuxBlockEntity::statorFlow)
            .number("h2_pressure", "Hydrogen pressure", "psig", 0, 40, TurbineAuxBlockEntity::h2Psig)
            .number("h2_kg", "Hydrogen in the casing", "kg", 0, dev.bwr.core.turbine.TurbineAux.H2_FULL_KG, TurbineAuxBlockEntity::h2Kg)
            .number("coolant_temp", "Coolant temperature", "degC", 0, 150, TurbineAuxBlockEntity::coolantC)
            .number("winding_temp", "Winding temperature", "degC", 0, 250, be -> be.module() == null ? Double.NaN : be.module().windingC)
            .number("cooler_duty", "Cooler duty", "kW", 0, 30000, TurbineAuxBlockEntity::coolerKw)
            .number("cold_water", "Cold cooling water", "kg", 0, TurbineAuxBlockEntity.WATER_CAPACITY, TurbineAuxBlockEntity::coldKg)
            .number("hot_water", "Warmed cooling water", "kg", 0, TurbineAuxBlockEntity.WATER_CAPACITY, TurbineAuxBlockEntity::hotKg)
            .number("supply_fe", "Pump supply stored", "FE", 0, TurbineAuxBlockEntity.AC_CAPACITY_FE, TurbineAuxBlockEntity::acFe)
            .toggle("stator_pumps", "Stator pumps on / off", (be, v) -> { be.setStatorPumps(v >= 0.5); return true; });

    static dev.bwr.mod.signal.SignalProvider aux(TurbineAuxBlockEntity be) {
        return switch (be.kind()) {
            case LUBE_OIL -> OIL_TANK.bind(be);
            case SHAFT_PUMP -> SHAFT_PUMP.bind(be);
            case GEN_COOLING -> GEN_COOLING.bind(be);
        };
    }
}
