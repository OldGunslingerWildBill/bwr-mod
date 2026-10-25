package dev.bwr.mod.signal.adapters;

import dev.bwr.core.turbine.SurfaceCondenser;
import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.power.PowerModuleBlockEntity;
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
            .text("status", "Status", be -> be.status);
}
