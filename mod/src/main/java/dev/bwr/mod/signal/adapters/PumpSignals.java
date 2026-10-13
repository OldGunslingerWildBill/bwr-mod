package dev.bwr.mod.signal.adapters;

import dev.bwr.core.eccs.PumpDesign;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.DeviceReadouts;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;

/**
 * Pumps. Flow and head ranges differ per design, so the generic range here is an upper bound and each
 * pump also reports its {@code rated_flow} for scaling.
 */
final class PumpSignals {
    private PumpSignals() {}

    static final Schema<EccsPumpBlockEntity> ECCS = new Schema<EccsPumpBlockEntity>()
            .bool("running", "Running", EccsPumpBlockEntity::isRunning)
            .number("speed", "Speed", "fraction", 0, 1, be -> be.pump().getSpeedFraction())
            .number("speed_demand", "Speed demand", "fraction", 0, 1, be -> be.pump().getSpeedDemandFraction())
            .number("flow", "Delivered flow", "kg/s", 0, 1500, EccsPumpBlockEntity::getDeliveredFlowKgPerS)
            .number("demanded_flow", "Demanded flow", "kg/s", 0, 1500, be -> be.pump().getDemandedFlowKgPerS())
            .number("available_flow", "Available flow", "kg/s", 0, 1500, be -> be.pump().getAvailableFlowKgPerS())
            .number("rated_flow", "Rated flow", "kg/s", 0, 1500, be -> be.design().ratedFlowKgPerS())
            .number("differential_pressure", "Differential pressure", "psi", 0, 1500, be -> be.pump().differentialPressurePsi())
            .number("developed_head", "Developed head", "psi", 0, 1500, be -> be.pump().getDevelopedHeadPsi())
            .bool("above_shutoff_head", "Above shutoff head", be -> be.pump().isAboveShutoffHead())
            .bool("drive_limited", "Drive limited", be -> be.pump().isDriveLimited())
            .number("suction_temperature", "Suction temperature", "degC", 0, 300, be -> be.pump().getSuctionTemperatureC())
            .number("suction_shortfall", "Suction shortfall", "kg/s", 0, 1500, EccsPumpBlockEntity::getSuctionShortfallKgPerS)
            .number("steam_consumption", "Drive steam consumption", "kg/s", 0, 100, be -> be.pump().getSteamDemandKgPerS())
            .number("energy_fe", "Stored energy", "FE", 0, 1e6, be -> be.energy().getEnergyStored())
            .number("energy_capacity_fe", "Energy capacity", "FE", 0, 1e6, be -> be.energy().getMaxEnergyStored())
            .number("boron_rate", "Boron injection rate", "ppm/min", 0, 20, EccsPumpBlockEntity::deliveredBoronPpmPerMinute)
            .bool("pool_cooling_mode", "Lined up for pool cooling", be -> be.getMode() == EccsPumpBlockEntity.Mode.POOL_COOLING)
            .bool("shutdown_cooling_mode", "Lined up for shutdown cooling", be -> be.getMode() == EccsPumpBlockEntity.Mode.SHUTDOWN_COOLING)
            .text("mode", "Lineup mode", be -> be.getMode().name())
            .toggle("run", "Start / stop", (be, v) -> { DeviceCommands.eccsRunning(be, v >= 0.5); return true; })
            .command("speed", "Speed demand", Kind.NUMBER, 0, 1, DeviceCommands::eccsSpeed)
            .command("flow_demand", "Flow demand", Kind.NUMBER, 0, 1, DeviceCommands::eccsFlow)
            .toggle("pool_cooling", "Pool cooling lineup", (be, v) -> { DeviceCommands.eccsMode(be, v >= 0.5); return true; })
            .toggle("shutdown_cooling", "Shutdown cooling lineup", (be, v) -> { DeviceCommands.eccsShutdownMode(be, v >= 0.5); return true; });

    static final Schema<FeedwaterPumpBlockEntity> FEEDWATER = new Schema<FeedwaterPumpBlockEntity>()
            .bool("running", "Running", FeedwaterPumpBlockEntity::isRunning)
            .number("speed", "Speed", "fraction", 0, 1, be -> be.pump().getSpeedFraction())
            .number("speed_demand", "Speed demand", "fraction", 0, 1, be -> be.pump().getSpeedDemandFraction())
            .number("achievable_speed", "Achievable speed", "fraction", 0, 1, be -> be.pump().getAchievableSpeedFraction())
            .number("flow_demand", "Flow demand", "fraction", 0, 1, FeedwaterPumpBlockEntity::getFlowDemandFraction)
            .number("flow", "Delivered flow", "kg/s", 0, 1500, FeedwaterPumpBlockEntity::getDeliveredFlowKgPerS)
            .number("rated_flow", "Rated flow", "kg/s", 0, 1500, be -> be.design().ratedFlowKgPerS())
            .number("differential_pressure", "Differential pressure", "psi", 0, 1500, be -> be.pump().differentialPressurePsi())
            .number("developed_head", "Developed head", "psi", 0, 1500, be -> be.pump().getDevelopedHeadPsi())
            .bool("above_shutoff_head", "Above shutoff head", be -> be.pump().isAboveShutoffHead())
            .number("suction_shortfall", "Suction shortfall", "kg/s", 0, 1500, FeedwaterPumpBlockEntity::getSuctionShortfallKgPerS)
            .number("suction_buffer", "Suction buffer", "kg", 0, 1e5, FeedwaterPumpBlockEntity::getSuctionBufferKg)
            .number("drive_steam", "Drive steam", "kg/s", 0, 100, FeedwaterPumpBlockEntity::getSteamDrawKgPerS)
            .number("power_demand", "Electrical demand", "W", 0, 1e7, be -> be.pump().getElectricalDemandWatts())
            .number("energy_fe", "Stored energy", "FE", 0, 1e6, be -> be.energy().getEnergyStored())
            .number("energy_capacity_fe", "Energy capacity", "FE", 0, 1e6, be -> be.energy().getMaxEnergyStored())
            .bool("computer_controlled", "Computer controlled", FeedwaterPumpBlockEntity::isComputerControlled)
            .bool("attached", "Attached", FeedwaterPumpBlockEntity::isAttached)
            .bool("turbine_driven", "Turbine driven", be -> be.design().drive() == PumpDesign.Drive.STEAM_TURBINE)
            .toggle("run", "Start / stop", (be, v) -> { DeviceCommands.feedwaterRunning(be, v >= 0.5); return true; })
            .command("speed", "Speed demand", Kind.NUMBER, 0, 1, DeviceCommands::feedwaterSpeed)
            .command("flow_demand", "Flow demand", Kind.NUMBER, 0, 1, DeviceCommands::feedwaterFlowDemand);

    static final Schema<RecirculationPumpBlockEntity> RECIRCULATION = new Schema<RecirculationPumpBlockEntity>()
            .number("speed_demand", "Speed demand", "fraction", 0, 1, RecirculationPumpBlockEntity::getTargetSpeedFraction)
            .number("speed", "Actual speed", "fraction", 0, 1, RecirculationPumpBlockEntity::getActualSpeedFraction)
            .number("achievable_speed", "Achievable speed", "fraction", 0, 1, RecirculationPumpBlockEntity::maxAchievableSpeedFraction)
            .bool("power_limited", "Power limited", RecirculationPumpBlockEntity::isPowerLimited)
            .number("energy_fe", "Stored energy", "FE", 0, 1e6, RecirculationPumpBlockEntity::getEnergyStoredFe)
            .number("energy_capacity_fe", "Energy capacity", "FE", 0, 1e6, RecirculationPumpBlockEntity::getEnergyCapacityFe)
            .bool("computer_controlled", "Computer controlled", RecirculationPumpBlockEntity::isComputerControlled)
            .bool("attached", "Attached to reactor", DeviceReadouts::recirculationPumpAttached)
            .command("speed", "Speed demand", Kind.NUMBER, 0, 1, DeviceCommands::recirculationPumpSpeed);
}
