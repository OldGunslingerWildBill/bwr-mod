package dev.bwr.mod.signal;

import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentEquipmentBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The actuator paths for every device command, shared by the CC peripherals and the signal adapters so
 * a panel switch and a Lua program reach exactly the same code. Plain Java: no ComputerCraft types.
 *
 * <p>Each method returns false when the command is refused (device not ready, interlock, bad value) and
 * performs no side effect in that case. Nothing here decides anything; each method does what it is told.
 */
public final class DeviceCommands {
    private DeviceCommands() {}

    // --- Reactor ---------------------------------------------------------------------------------

    /** Bare scram through the rod-drive network when it exists, otherwise the core. False if not formed. */
    public static boolean reactorScram(ReactorControllerBlockEntity be) {
        var rods = be.rods();
        if (rods != null) { rods.scram(); return true; }
        var core = be.core();
        if (core == null) return false;
        core.scram();
        return true;
    }

    public static boolean reactorResetScram(ReactorControllerBlockEntity be) {
        var rods = be.rods();
        if (rods != null) { rods.resetScram(); return true; }
        var core = be.core();
        if (core == null) return false;
        core.resetScram();
        return true;
    }

    public static boolean reactorRecirculationFlow(ReactorControllerBlockEntity be, double fraction) {
        if (be.core() == null || !Double.isFinite(fraction)) return false;
        be.setRecirculationDemand(fraction);
        return true;
    }

    /** @param rodIndex zero-based */
    public static boolean reactorRodPosition(ReactorControllerBlockEntity be, int rodIndex, int notchLabel) {
        var core = be.core();
        var rods = be.rods();
        if (core == null || rods == null || rodIndex < 0 || rodIndex >= core.getControlRodCount()) return false;
        try {
            rods.setNotchLabelDemand(rodIndex, notchLabel);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean reactorBoronInjection(ReactorControllerBlockEntity be, double ppmPerMinute) {
        var core = be.core();
        if (core == null || !Double.isFinite(ppmPerMinute)) return false;
        core.setBoronInjectionPpmPerMinute(ppmPerMinute);
        return true;
    }

    // --- Steam valves ----------------------------------------------------------------------------

    /** What both the MSIV peripheral's open/close/setOpen do: take computer control, set the demand. */
    public static void msivDemand(MainSteamIsolationValveBlockEntity be, boolean open) {
        be.setComputerControlled(true);
        be.setDemandOpen(open);
    }

    /**
     * A panel command for an MSIV: identical to the CC setter, so the demand is always recorded (an open
     * demand on an unpowered valve is kept and the valve opens when power returns).
     *
     * @return whether the valve can move toward the demand right now: closing always can (the spring),
     *         opening needs actuator power. A false result is information, not a refusal; the demand stands.
     */
    public static boolean msivCommand(MainSteamIsolationValveBlockEntity be, boolean open) {
        msivDemand(be, open);
        return !open || be.isPowered();
    }

    public static void srvOpen(SafetyReliefValveBlockEntity be, boolean open) {
        be.setComputerControlled(true);
        be.setOpen(open);
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        BlockState state = level.getBlockState(be.getBlockPos());
        if (state.hasProperty(SafetyReliefValveBlock.OPEN) && state.getValue(SafetyReliefValveBlock.OPEN) != open)
            level.setBlock(be.getBlockPos(), state.setValue(SafetyReliefValveBlock.OPEN, open), Block.UPDATE_CLIENTS);
    }

    /** Turbine control, bypass and stop valves. Stop valves accept only 0 or 1. */
    public static boolean turbineValvePosition(TurbineValveBlockEntity valve, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return false;
        if (valve.stopValve() && fraction != 0 && fraction != 1) return false;
        valve.setTarget(fraction);
        return true;
    }

    public static boolean turbineOutletFlow(TurbineSteamOutletBlockEntity be, double kgPerS) {
        if (!Double.isFinite(kgPerS)) return false;
        be.setComputerControlled(true);
        be.setCommandedFlowKgPerS(kgPerS);
        return true;
    }

    public static boolean turbineOutletFlowFraction(TurbineSteamOutletBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction)) return false;
        be.setComputerControlled(true);
        be.setCommandedFlowFractionOfRated(fraction);
        return true;
    }

    // --- Pumps -----------------------------------------------------------------------------------

    public static boolean eccsSpeed(EccsPumpBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return false;
        be.setComputerControlled(true);
        be.setSpeedDemandFraction(fraction);
        return true;
    }

    public static void eccsRunning(EccsPumpBlockEntity be, boolean running) {
        be.setComputerControlled(true);
        be.setRunning(running);
    }

    public static boolean eccsFlow(EccsPumpBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction)) return false;
        be.setComputerControlled(true);
        be.setFlowDemandFraction(fraction);
        return true;
    }

    public static void eccsMode(EccsPumpBlockEntity be, boolean poolCooling) {
        be.setMode(poolCooling ? EccsPumpBlockEntity.Mode.POOL_COOLING : EccsPumpBlockEntity.Mode.INJECTION);
    }

    public static boolean feedwaterSpeed(FeedwaterPumpBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return false;
        PlantActuators.run(be, () -> { be.setComputerControlled(true); be.setSpeedDemandFraction(fraction); });
        return true;
    }

    public static void feedwaterRunning(FeedwaterPumpBlockEntity be, boolean running) {
        PlantActuators.run(be, () -> { be.setComputerControlled(true); be.setRunning(running); });
    }

    public static boolean feedwaterFlowDemand(FeedwaterPumpBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction)) return false;
        PlantActuators.run(be, () -> { be.setComputerControlled(true); be.setFlowDemandFraction(fraction); });
        return true;
    }

    public static boolean recirculationPumpSpeed(RecirculationPumpBlockEntity be, double fraction) {
        if (!Double.isFinite(fraction)) return false;
        be.setComputerControlled(true);
        be.setTargetSpeedFraction(fraction);
        return true;
    }

    public static boolean coolingSpeed(CoolingBlockEntity be, double speed) {
        if (!Double.isFinite(speed) || speed < 0 || speed > 1 || be.design().watts == 0) return false;
        be.setTarget(speed);
        return true;
    }

    // --- Containment -----------------------------------------------------------------------------

    public static boolean penetrationOpen(ContainmentPenetrationBlockEntity be, boolean open) {
        var block = (ContainmentPenetrationBlock) be.getBlockState().getBlock();
        if (!block.hasValve() || be.getBlockState().getValue(ContainmentPenetrationBlock.RUPTURED)) return false;
        if (open && !be.hasPowerToOpen()) return false;
        be.setComputerControlled(true);
        be.setDemandOpen(open);
        return true;
    }

    public static boolean vaultDoorOpen(ContainmentVaultDoorBlockEntity door, boolean open) {
        boolean started = door.setOpen(open, true);
        if (started) door.setComputerControlled(true);
        return started;
    }

    public static boolean airlockInnerOpen(ContainmentAirlockBlockEntity door, boolean open) {
        boolean moved = door.setInnerOpen(open, false);
        if (moved) door.setComputerControlled(true);
        return moved;
    }

    public static boolean airlockOuterOpen(ContainmentAirlockBlockEntity door, boolean open) {
        boolean moved = door.setOuterOpen(open, false);
        if (moved) door.setComputerControlled(true);
        return moved;
    }

    public static void airlockOverride(ContainmentAirlockBlockEntity door, boolean enabled) {
        door.setComputerControlled(true);
        door.setOverride(enabled);
    }

    public static boolean equipmentActive(ContainmentEquipmentBlockEntity equipment, boolean active) {
        if (active && !equipment.supplied()) return false;
        equipment.setDemand(active);
        return true;
    }
}
