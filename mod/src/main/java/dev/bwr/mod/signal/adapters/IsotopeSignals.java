package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.signal.Schema;

/**
 * The RTG. Read-only: it has no switch and takes no command. {@code thermal_power} is in W and {@code electric_power} in
 * FE/t (the mod's own unit for generation, so a gauge that Measures POWER takes both); {@code dose_rate} is the reading a
 * metre from the block in mSv/h (quantity DOSE_RATE), {@code capsules} the wells loaded and {@code fuelled} a boolean for a lamp.
 */
final class IsotopeSignals {
    private IsotopeSignals() {}

    static final Schema<RtgBlockEntity> RTG = new Schema<RtgBlockEntity>()
            .number("thermal_power", "Thermal power", "W", 0, 100_000, be -> be.readout().thermalWatts())
            .number("electric_power", "Electric power", "FE/t", 0, 1_000, be -> be.readout().fePerTick())
            .number("dose_rate", "Dose rate at 1 m", "mSv/h", 0, 1000, be -> be.readout().doseAtOneMetreMsvPerH())
            .number("capsules", "Capsules loaded", "", 0, RtgBlockEntity.SLOTS, be -> be.readout().capsules())
            .number("buffer", "Stored energy", "FE", 0, RtgBlockEntity.BUFFER_FE, RtgBlockEntity::bufferFe)
            .bool("fuelled", "Fuelled", be -> be.readout().capsules() > 0);
}
