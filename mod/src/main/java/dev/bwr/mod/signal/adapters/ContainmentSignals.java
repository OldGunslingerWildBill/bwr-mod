package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentEquipmentBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.containment.ContainmentSprayHeaderBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.signal.DeviceReadouts;
import dev.bwr.mod.signal.Schema;

import java.util.Locale;
import java.util.function.ToDoubleFunction;

/** Containment room controller, penetrations, vault door, airlock and atmosphere equipment. */
final class ContainmentSignals {
    private ContainmentSignals() {}

    static final Schema<ContainmentSprayHeaderBlockEntity> SPRAY_HEADER = new Schema<ContainmentSprayHeaderBlockEntity>()
            .number("water", "Spray header water", "kg", 0, ContainmentSprayHeaderBlockEntity.CAPACITY_KG,
                    ContainmentSprayHeaderBlockEntity::getWaterKg)
            .number("water_temperature", "Spray water temperature", "degC", 0, 300,
                    ContainmentSprayHeaderBlockEntity::getWaterTemperatureC)
            .number("flow", "Spray flow", "kg/s", 0, ContainmentSprayHeaderBlockEntity.MAX_FLOW_KG_PER_S,
                    ContainmentSprayHeaderBlockEntity::getLastFlowKgPerS);

    /** Reads from the first linked, formed reactor; NaN (no signal) when the room has none. */
    private static ToDoubleFunction<ContainmentControllerBlockEntity> reactor(ToDoubleFunction<ReactorControllerBlockEntity> read) {
        return room -> {
            ReactorControllerBlockEntity reactor = DeviceReadouts.containmentReactor(room);
            return reactor == null ? Double.NaN : read.applyAsDouble(reactor);
        };
    }

    static final Schema<ContainmentControllerBlockEntity> CONTROLLER = new Schema<ContainmentControllerBlockEntity>()
            .bool("sealed", "Room sealed", room -> room.isSealed() && !room.isStale())
            .number("free_volume", "Free volume", "m3", 0, 1e6, ContainmentControllerBlockEntity::freeVolume)
            .number("failure_pressure", "Failure pressure", "psig", 0, 300, ContainmentControllerBlockEntity::failurePressurePsig)
            .bool("isolated", "Steam and water isolated", DeviceReadouts::containmentIsolated)
            .number("openings", "Openings", "openings", 0, 256, room -> room.openings().size())
            .number("bypass_flow", "Bypass flow", "fraction", 0, 1, room -> {
                ReactorControllerBlockEntity reactor = DeviceReadouts.containmentReactor(room);
                return reactor == null ? Double.NaN : room.bypassFlowFraction(reactor);
            })
            .number("h2_percent", "Hydrogen", "%", 0, 100, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getHydrogenPercent()))
            .number("o2_percent", "Oxygen", "%", 0, 25, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getOxygenPercent()))
            .number("pressure", "Containment pressure", "psig", -5, 150, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getPressurePsig()))
            .number("steam_percent", "Steam", "%", 0, 100, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getSteamPercent()))
            .number("gas_temperature", "Gas temperature", "degC", 0, 400, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getGasTemperatureC()))
            .number("drywell_pressure", "Drywell pressure", "psig", -5, 150, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getDrywellPressurePsig()))
            .number("wetwell_pressure", "Wetwell pressure", "psig", -5, 150, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getWetwellPressurePsig()))
            .number("pressure_nodes", "Pressure nodes", "nodes", 0, 2,
                    reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getPressureNodeCount()))
            .number("spray_flow", "Containment spray", "kg/s", 0, 2500, reactor(r -> r.core() == null ? Double.NaN : r.core().getContainment().getLastSprayFlowKgPerS()));

    static final Schema<ContainmentPenetrationBlockEntity> PENETRATION = new Schema<ContainmentPenetrationBlockEntity>()
            .number("position", "Isolation valve position", "fraction open", 0, 1, ContainmentPenetrationBlockEntity::position)
            .bool("isolated", "Isolated", ContainmentPenetrationBlockEntity::isIsolated)
            .bool("demand_open", "Demanded open", ContainmentPenetrationBlockEntity::demandOpen)
            .bool("ruptured", "Ruptured", be -> be.getBlockState().getValue(ContainmentPenetrationBlock.RUPTURED))
            .number("energy_fe", "Stored energy", "FE", 0, 1e5, be -> be.energy().getEnergyStored())
            .bool("computer_controlled", "Computer controlled", ContainmentPenetrationBlockEntity::isComputerControlled)
            .text("service", "Service", be -> ((ContainmentPenetrationBlock) be.getBlockState().getBlock()).service().name().toLowerCase(Locale.ROOT))
            .toggle("open", "Open / close", (be, v) -> DeviceCommands.penetrationOpen(be, v >= 0.5));

    static final Schema<ContainmentVaultDoorBlockEntity> VAULT_DOOR = new Schema<ContainmentVaultDoorBlockEntity>()
            .number("open_fraction", "Door opening", "fraction open", 0, 1, be -> be.swing(0))
            .bool("sealed", "Sealed", be -> be.state() == ContainmentVaultDoorBlockEntity.State.CLOSED_SEALED)
            .number("energy_fe", "Stored energy", "FE", 0, 1e6, be -> be.energy().getEnergyStored())
            .bool("computer_controlled", "Computer controlled", ContainmentVaultDoorBlockEntity::isComputerControlled)
            .text("state", "State", be -> be.state().name())
            .toggle("open", "Open / close", (be, v) -> DeviceCommands.vaultDoorOpen(be, v >= 0.5));

    static final Schema<ContainmentAirlockBlockEntity> AIRLOCK = new Schema<ContainmentAirlockBlockEntity>()
            .bool("inner_open", "Inner door open", ContainmentAirlockBlockEntity::innerOpen)
            .bool("outer_open", "Outer door open", ContainmentAirlockBlockEntity::outerOpen)
            .bool("forced_override", "Interlock override", ContainmentAirlockBlockEntity::override)
            .bool("sealed", "Sealed", be -> !be.innerOpen() || !be.outerOpen())
            .bool("damaged", "Damaged", ContainmentAirlockBlockEntity::damaged)
            .bool("computer_controlled", "Computer controlled", ContainmentAirlockBlockEntity::isComputerControlled)
            .toggle("inner", "Inner door open / close", (be, v) -> DeviceCommands.airlockInnerOpen(be, v >= 0.5))
            .toggle("outer", "Outer door open / close", (be, v) -> DeviceCommands.airlockOuterOpen(be, v >= 0.5))
            .toggle("override", "Interlock override", (be, v) -> { DeviceCommands.airlockOverride(be, v >= 0.5); return true; });

    static final Schema<ContainmentEquipmentBlockEntity> EQUIPMENT = new Schema<ContainmentEquipmentBlockEntity>()
            .bool("demand", "Demanded active", ContainmentEquipmentBlockEntity::demand)
            .bool("supplied", "Powered and supplied", ContainmentEquipmentBlockEntity::supplied)
            .number("energy_fe", "Stored energy", "FE", 0, 1e6, be -> be.energy().getEnergyStored())
            .number("nitrogen_ticks", "Nitrogen supply", "ticks", 0, 1e6, ContainmentEquipmentBlockEntity::nitrogenTicks)
            .toggle("active", "Start / stop", (be, v) -> DeviceCommands.equipmentActive(be, v >= 0.5));
}
