package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Schema;

/**
 * The area radiation monitor. Its dose rate and setpoints are in mSv/h (quantity DOSE_RATE, so a gauge that Measures
 * DOSE_RATE takes them and refuses a pressure); {@code high_radiation} and {@code radiation_alert} are booleans for an
 * annunciator or a lamp. The one command sets the alarm setpoint, a player's or a program's explicit choice.
 */
final class RadiationSignals {
    private RadiationSignals() {}

    static final Schema<AreaRadiationMonitorBlockEntity> MONITOR = new Schema<AreaRadiationMonitorBlockEntity>()
            .number("dose_rate", "Dose rate", "mSv/h", 0, 1000, AreaRadiationMonitorBlockEntity::doseRate)
            .bool("high_radiation", "High radiation", AreaRadiationMonitorBlockEntity::isHigh)
            .bool("radiation_alert", "Radiation alert", AreaRadiationMonitorBlockEntity::isAlert)
            .number("alarm_setpoint", "High radiation setpoint", "mSv/h", 0, 1000, AreaRadiationMonitorBlockEntity::highSetpoint)
            .number("alert_setpoint", "Radiation alert setpoint", "mSv/h", 0, 1000, AreaRadiationMonitorBlockEntity::alertSetpoint)
            .command("alarm_setpoint", "Set high radiation setpoint", Kind.NUMBER,
                    AreaRadiationMonitorBlockEntity.MIN_HIGH_MSV_PER_H, AreaRadiationMonitorBlockEntity.MAX_HIGH_MSV_PER_H,
                    AreaRadiationMonitorBlockEntity::setHighSetpoint);
}
