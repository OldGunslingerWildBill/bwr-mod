package dev.bwr.mod.signal;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Derived device readouts that are more than a single getter, shared by the CC peripherals and the signal
 * adapters so both report the same number. Plain Java: no ComputerCraft types.
 */
public final class DeviceReadouts {
    private DeviceReadouts() {}

    /** Relief valve flow: the last computed flow while open, otherwise zero. */
    public static double srvFlow(SafetyReliefValveBlockEntity be) {
        return be.isOpen() ? be.getLastFlowKgPerS() : 0.0;
    }

    /** Whether steam is actually passing through the RPV nozzle. */
    public static boolean rpvOutletPassing(RpvSteamOutletBlockEntity be) {
        return be.getPosition() > 0.0 && be.isSteamLineAttached() && be.isPartOfFormedReactor();
    }

    public static double rpvOutletFlow(RpvSteamOutletBlockEntity be) {
        return rpvOutletPassing(be) ? be.getLastFlowKgPerS() : 0.0;
    }

    /** Steam buffer fill, 0..1. */
    public static double turbineOutletBufferFill(TurbineSteamOutletBlockEntity be) {
        long capacity = be.getBufferCapacityMilliBuckets();
        if (capacity <= 0L) return 0.0;
        return (double) be.getBufferedMilliBuckets() / (double) capacity;
    }

    /** The first linked reactor that is loaded and formed, or null. */
    public static ReactorControllerBlockEntity containmentReactor(ContainmentControllerBlockEntity room) {
        Level level = room.getLevel();
        if (level == null) return null;
        for (BlockPos pos : room.linkedReactors())
            if (dev.bwr.mod.world.LoadedWorld.blockEntity(level, pos) instanceof ReactorControllerBlockEntity reactor
                    && reactor.isFormed()) return reactor;
        return null;
    }

    /** True when every steam and water penetration of the room is closed and intact. */
    public static boolean containmentIsolated(ContainmentControllerBlockEntity room) {
        Level level = room.getLevel();
        if (level == null) return false;
        for (BlockPos at : room.openings()) {
            var state = level.getBlockState(at);
            if (!(state.getBlock() instanceof ContainmentPenetrationBlock penetration)
                    || penetration.service() != ContainmentPenetrationBlock.Service.STEAM
                    && penetration.service() != ContainmentPenetrationBlock.Service.WATER) continue;
            if (state.getValue(ContainmentPenetrationBlock.RUPTURED)
                    || !(level.getBlockEntity(at) instanceof ContainmentPenetrationBlockEntity valve)
                    || !valve.isIsolated()) return false;
        }
        return true;
    }

    /** Whether a recirculation pump motor belongs to a reactor controller (true while that chunk is unloaded). */
    public static boolean recirculationPumpAttached(RecirculationPumpBlockEntity be) {
        BlockPos controllerPos = be.getControllerPos();
        if (controllerPos == null) return false;
        Level level = be.getLevel();
        if (level == null || !level.isLoaded(controllerPos)) return true;
        return level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity;
    }
}
