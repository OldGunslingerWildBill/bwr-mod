package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the condensate storage tank.
 *
 * <p>Publishes how much cold water is left. Nothing here switches an emergency
 * pump over to pool suction when it runs low, and nothing warns that it is
 * running low — {@code SPEC.md} section 9.2 names that exact script as one the
 * player has to write.
 *
 * <h2>Everything that reads the tank runs on the server thread</h2>
 * The three level readouts are {@code @LuaFunction(mainThread = true)} because
 * they walk a {@code FluidTank} the server tick is draining. Only
 * {@link #getTemperature()} is off-thread, and only because its whole body is a
 * compile-time constant.
 */
public class CondensateStorageTankPeripheral implements IPeripheral {

    private final CondensateStorageTankBlockEntity be;

    public CondensateStorageTankPeripheral(CondensateStorageTankBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_condensate_tank";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof CondensateStorageTankPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    /** Water in the tank, kg. One millibucket is one kilogram. */
    @LuaFunction(mainThread = true)
    public final double getStored() {
        return be.storedKg();
    }

    @LuaFunction(mainThread = true)
    public final double getCapacity() {
        return be.capacityKg();
    }

    @LuaFunction(mainThread = true)
    public final double getLevel() {
        return be.levelFraction();
    }

    /** Stored condensate temperature, degrees C. A constant, so it is off-thread. */
    @LuaFunction
    public final double getTemperature() {
        return CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("stored", be.storedKg());
        m.put("capacity", be.capacityKg());
        m.put("level", be.levelFraction());
        m.put("temperature", CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C);
        var owner=be.owner();m.put("assembled",owner!=null&&owner.assembled());m.put("ready",be.ready());
        m.put("diameter",owner==null?0:owner.diameter);m.put("height",owner==null?0:owner.height);
        return m;
    }
}
