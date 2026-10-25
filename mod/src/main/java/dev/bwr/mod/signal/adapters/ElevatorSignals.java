package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.signal.Schema;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity;

/**
 * The new fuel elevator for panels, the Networking Wrench and computers on a wired network: where it stands, what the carriage is doing, how many
 * assemblies wait, how many rack cells are free and the water over an assembly at the transfer height. The one command, {@code raise}, is a player's or a
 * program's explicit choice (it brings the assembly that has cooled longest up to the hatch); loading is items, through the hatch.
 */
final class ElevatorSignals {
    private ElevatorSignals() {}

    static final Schema<NewFuelElevatorBlockEntity> ELEVATOR = new Schema<NewFuelElevatorBlockEntity>()
            .bool("linked", "On a formed pool", be -> be.status().linked())
            .bool("busy", "Carriage moving", NewFuelElevatorBlockEntity::busy)
            .bool("assembly_at_hatch", "Assembly waiting at the hatch", be -> !be.landing().isEmpty())
            .bool("water_ok", "Enough water to lower", be -> {
                var s = be.status();
                return s.linked() && s.waterOverTransferM() + 1.0e-9 >= s.minCoverM();
            })
            .number("waiting", "Assemblies waiting in the hatch", "", 0, NewFuelElevatorBlockEntity.HATCH_SLOTS + 1, NewFuelElevatorBlockEntity::queued)
            .number("free_cells", "Free rack cells", "", 0, 192, be -> be.status().freeCells())
            .number("water_over_handle", "Water over the handle at the transfer height", "m", 0, 12, be -> Math.max(0.0, be.status().waterOverTransferM()))
            .number("trip_progress", "Trip progress", "fraction", 0, 1, be -> be.status().progress())
            .pulse("raise", "Raise the coolest assembly", (be, value) -> be.raise(null) == null);
}
