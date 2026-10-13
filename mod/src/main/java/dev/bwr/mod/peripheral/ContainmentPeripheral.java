package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.containment.*;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Room-wide CC readout and isolation controls. All data resolves from the live controller. */
public final class ContainmentPeripheral implements IPeripheral {
    private final ContainmentControllerBlockEntity room;
    public ContainmentPeripheral(ContainmentControllerBlockEntity room) { this.room = room; }
    @Override public String getType() { return "bwr_containment"; }
    @Override public Object getTarget() { return room; }
    @Override public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ContainmentPeripheral p && p.room == room;
    }
    private Level level() { return room.getLevel(); }
    private ReactorControllerBlockEntity reactor() {
        return dev.bwr.mod.signal.DeviceReadouts.containmentReactor(room);
    }
    private static Map<String, Object> pos(BlockPos pos) {
        return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }
    @LuaFunction(mainThread = true) public final boolean isSealed() { return room.isSealed() && !room.isStale(); }
    @LuaFunction(mainThread = true) @Nullable public final Map<String, Object> getLeak() {
        return room.leak() == null ? null : pos(room.leak());
    }
    @LuaFunction(mainThread = true) public final int getFreeVolume() { return room.freeVolume(); }
    @LuaFunction(mainThread = true) public final double getFailurePressure() { return room.failurePressurePsig(); }
    @LuaFunction(mainThread = true) @Nullable public final Map<String, Object> getWeakestSpot() {
        var spot = room.weakestSpot();
        if (spot == null) return null;
        var result = new LinkedHashMap<String, Object>();
        result.put("pos", pos(spot.position())); result.put("material", spot.material());
        result.put("layers", spot.layers()); result.put("ratingPsig", spot.ratingPsig());
        result.put("rating", spot.ratingPsig());
        result.put("deep", spot.deep());
        return result;
    }
    @LuaFunction(mainThread = true) public final List<Map<String, Object>> getOpenings() {
        var openings = new ArrayList<Map<String, Object>>();
        if (level() == null) return openings;
        for (BlockPos at : room.openings()) {
            var state = level().getBlockState(at);
            var item = new LinkedHashMap<String, Object>();
            item.put("pos", pos(at));
            if (state.getBlock() instanceof ContainmentPenetrationBlock penetration) {
                var service = penetration.service();
                item.put("type", "penetration");
                item.put("service", service.name().toLowerCase(Locale.ROOT));
                item.put("carriedService", service.name().toLowerCase(Locale.ROOT));
                item.put("nameplate", ContainmentPenetrationRatings.nameplate(service));
                item.put("containmentRatingPsig", ContainmentPenetrationRatings.containmentPsig());
                item.put("ruptured", state.getValue(ContainmentPenetrationBlock.RUPTURED));
                if (level().getBlockEntity(at) instanceof ContainmentPenetrationBlockEntity valve) {
                    item.put("isolated", valve.isIsolated());
                    item.put("position", valve.position());
                    item.put("state", state.getValue(ContainmentPenetrationBlock.RUPTURED)
                            ? "ruptured" : valve.isIsolated() ? "closed" : "open");
                } else item.put("state", "unavailable");
            } else if (state.getBlock() instanceof ContainmentVaultDoorBlock door) {
                var owner = door.owner(level(), at, state);
                item.put("type", "vault_door");
                item.put("state", owner == null ? "unavailable" : owner.state().name().toLowerCase(Locale.ROOT));
                item.put("containmentRatingPsig", ContainmentConfig.VAULT_DOOR_CONTAINMENT_PSIG.get());
            } else if (state.getBlock() instanceof ContainmentAirlockBlock airlock) {
                var owner = airlock.owner(level(), at, state);
                item.put("type", "airlock");
                item.put("state", owner == null ? "unavailable"
                        : owner.innerOpen() && owner.outerOpen() ? "both_open" : "interlocked");
                item.put("containmentRatingPsig", ContainmentConfig.AIRLOCK_CONTAINMENT_PSIG.get());
            }
            openings.add(item);
        }
        return openings;
    }
    @LuaFunction(mainThread = true) public final List<Map<String, Object>> getLinkedReactors() {
        return room.linkedReactors().stream().map(ContainmentPeripheral::pos).toList();
    }
    @LuaFunction(mainThread = true) public final boolean setIsolation(int x, int y, int z, boolean closed) {
        if (level() == null) return false;
        BlockPos at = new BlockPos(x, y, z);
        if (!room.openings().contains(at) || !level().isLoaded(at)
                || !(level().getBlockState(at).getBlock() instanceof ContainmentPenetrationBlock penetration)
                || !penetration.hasValve() || level().getBlockState(at).getValue(ContainmentPenetrationBlock.RUPTURED)
                || !(level().getBlockEntity(at) instanceof ContainmentPenetrationBlockEntity valve)
                || !valve.hasPowerToOpen()) return false;
        valve.setComputerControlled(true);
        valve.setDemandOpen(!closed);
        return true;
    }
    @LuaFunction(mainThread = true) public final boolean isIsolated() {
        return dev.bwr.mod.signal.DeviceReadouts.containmentIsolated(room);
    }
    @LuaFunction(mainThread = true) public final double getBypassFlow() {
        var reactor = reactor();
        return reactor == null ? 0.0 : room.bypassFlowFraction(reactor);
    }
    @LuaFunction(mainThread = true) public final Map<String, Object> getContainment() throws LuaException {
        var reactor = reactor();
        return reactor == null ? Map.of() : new ReactorPeripheral(reactor).getContainment();
    }
    @LuaFunction(mainThread = true) public final double getHydrogenPercent() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getHydrogenPercent();
    }
    @LuaFunction(mainThread = true) public final double getOxygenPercent() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getOxygenPercent();
    }
    @LuaFunction(mainThread = true) public final double getPressurePsig() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getPressurePsig();
    }
    @LuaFunction(mainThread = true) public final double getSteamPercent() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getSteamPercent();
    }
    @LuaFunction(mainThread = true) public final double getGasTemperatureC() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getGasTemperatureC();
    }
    @LuaFunction(mainThread = true) public final double getDrywellPressurePsig() { return getPressurePsig(); }
    @LuaFunction(mainThread = true) public final double getWetwellPressurePsig() { return getPressurePsig(); }
    @LuaFunction(mainThread = true) public final double getSprayFlowKgPerS() {
        var reactor = reactor(); return reactor == null ? 0 : reactor.core().getContainment().getLastSprayFlowKgPerS();
    }
    @LuaFunction(mainThread = true) public final Map<String, Object> getStatus() throws LuaException {
        var status = new LinkedHashMap<String, Object>();
        status.put("sealed", isSealed()); status.put("stale", room.isStale());
        status.put("freeVolumeM3", getFreeVolume()); status.put("failurePressurePsig", getFailurePressure());
        status.put("leak", getLeak() == null ? Map.of() : getLeak());
        status.put("weakestSpot", getWeakestSpot() == null ? Map.of() : getWeakestSpot());
        status.put("openings", getOpenings()); status.put("linkedReactors", getLinkedReactors());
        status.put("isolated", isIsolated()); status.put("bypassFlow", getBypassFlow());
        status.put("atmosphere", getContainment());
        return status;
    }
}
