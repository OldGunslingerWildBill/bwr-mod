package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;

/** Water line hardware: the motor-operated water valve. */
final class WaterSignals {
    private WaterSignals() {}

    static final Schema<MotorOperatedWaterValveBlockEntity> WATER_VALVE = new Schema<MotorOperatedWaterValveBlockEntity>()
            .number("target", "Demanded opening", "fraction open", 0, 1, MotorOperatedWaterValveBlockEntity::target)
            .number("position", "Valve position", "fraction open", 0, 1, MotorOperatedWaterValveBlockEntity::position)
            .bool("open", "Fully open", MotorOperatedWaterValveBlockEntity::isOpen)
            .bool("closed", "Fully closed", MotorOperatedWaterValveBlockEntity::isClosed)
            .number("flow", "Water flow", "kg/s", 0, 2500, MotorOperatedWaterValveBlockEntity::flowKgPerS)
            .bool("powered", "Motor powered", MotorOperatedWaterValveBlockEntity::isPowered)
            .number("energy_fe", "Actuator energy", "FE", 0, MotorOperatedWaterValveBlockEntity.ENERGY_CAPACITY_FE,
                    be -> be.energy().getEnergyStored())
            .level("fail_mode", "Fail position (0 as-is, 1 open, 2 closed)", "mode", 0, 2, be -> be.failMode().ordinal())
            .number("rating_psi", "Pressure rating", "psi", 0, 5000, be -> MotorOperatedWaterValveBlockEntity.RATING_PSI)
            .number("wear_above_psig", "Wear and failure above", "psig", 0, 5000, be -> MotorOperatedWaterValveBlockEntity.wearAbovePsig())
            .command("position", "Opening demand", Kind.NUMBER, 0, 1, DeviceCommands::waterValvePosition)
            .toggle("open", "Open / close", (be, v) -> DeviceCommands.waterValveOpen(be, v >= 0.5))
            .command("fail_mode", "Fail position", Kind.LEVEL, 0, 2, DeviceCommands::waterValveFailMode);
}
