package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for a suppression pool.
 *
 * <p>Publishes the pool's thermal condition and accepts an RHR duty command.
 * There is no heat capacity temperature limit exposed as a boolean, because
 * that limit is an administrative number a plant chooses rather than a physical
 * fact — the player picks their own and enforces it in Lua.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method is {@code @LuaFunction(mainThread = true)}, and it must stay that
 * way. CC runs a plain {@code @LuaFunction} on the computer thread:
 * {@link #setRhrDuty} reaches {@code BlockEntity.setChanged()}, which walks the
 * chunk map and dispatches neighbour updates to adjacent comparators — server
 * thread work, from a thread with none of the server's guarantees — and the
 * readouts below would otherwise sample {@link SuppressionPool}'s plain double
 * fields while the tick thread is halfway through {@code condenseSteam}, so a
 * program could see a temperature written before the mass it belongs to.
 */
public class SuppressionPoolPeripheral implements IPeripheral {

    private final SuppressionPoolBlockEntity be;

    public SuppressionPoolPeripheral(SuppressionPoolBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_suppression_pool";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SuppressionPoolPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    @LuaFunction(mainThread = true)
    public final boolean isFormed() {
        return be.isFormed();
    }

    @LuaFunction(mainThread = true)
    public final double getTemperature() {
        return be.pool().getTemperatureC();
    }

    /** Saturation temperature at containment pressure — where boiling begins. */
    @LuaFunction(mainThread = true)
    public final double getSaturationTemperature() {
        return be.pool().getSaturationTemperatureC();
    }

    /** Margin to boiling. This is what actually governs whether steam condenses. */
    @LuaFunction(mainThread = true)
    public final double getSubcooling() {
        return be.pool().getSubcoolingC();
    }

    /** Fraction of arriving steam the pool can condense right now, 0..1. */
    @LuaFunction(mainThread = true)
    public final double getCondensationEffectiveness() {
        return be.pool().condensationEffectiveness();
    }

    /** Steam passing straight through to containment because the pool is spent. */
    @LuaFunction(mainThread = true)
    public final double getUncondensedSteam() {
        return be.pool().getUncondensedSteamKgPerS();
    }

    @LuaFunction(mainThread = true)
    public final boolean isBoiling() {
        return be.pool().isBoiling();
    }

    /** How much heat the pool can still absorb before it saturates, in MJ. */
    @LuaFunction(mainThread = true)
    public final double getRemainingHeatCapacity() {
        return be.pool().getRemainingHeatCapacityMJ();
    }

    @LuaFunction(mainThread = true)
    public final double getMass() {
        return be.pool().getMassKg();
    }

    @LuaFunction(mainThread = true)
    public final double getLevel() {
        return be.pool().getLevelFraction();
    }

    /** RHR duty commanded on this controller, 0..1. */
    @LuaFunction(mainThread = true)
    public final double getRhrDuty() {
        return be.getRhrDuty();
    }

    /**
     * Duty the heat exchangers are actually running at: what was commanded here
     * plus whatever any RHR loop lined up for pool cooling is supplying.
     *
     * <p>This is the number that governs the cooling, and it is the one a
     * program balancing pool heat should read. {@link #getRhrDuty()} on its own
     * under-reports the moment an RHR loop is lined up for pool cooling, and a
     * control loop that trims against it would fight the loop it cannot see.
     */
    @LuaFunction(mainThread = true)
    public final double getEffectiveRhrDuty() {
        return be.getEffectiveRhrDuty();
    }

    /**
     * Commanded by the player, 0 to 1. RHR never starts itself.
     *
     * <p>Non-finite is refused rather than quietly coerced.
     * {@link SuppressionPoolBlockEntity#setRhrDuty(double)} maps a non-finite
     * duty to zero before storing it, and the load path sanitises the persisted
     * field the same way, so a NaN arriving here can no longer be stored or
     * survive a reload. What that floor cannot do is tell anyone. A duty of
     * {@code 0/0} out of a control loop would land as a silent zero — RHR idle,
     * the pool heating, and a program that still believes it commanded cooling.
     * A Lua error naming the value is the only way the player learns their
     * control loop produced a NaN. A value outside 0..1 still clamps, because a
     * duty knob has stops.
     */
    @LuaFunction(mainThread = true)
    public final void setRhrDuty(double duty) throws LuaException {
        if (!Double.isFinite(duty)) {
            throw new LuaException("RHR duty must be a finite number, got " + duty);
        }
        be.setRhrDuty(duty);
    }

    @LuaFunction(mainThread = true)
    public final int getDischargingValveCount() {
        return be.dischargingValveCount();
    }

    @LuaFunction(mainThread = true)
    public final String getFillMode() { return be.pool().isSprayMode()?"spray":"fill"; }

    @LuaFunction(mainThread = true)
    public final void setFillMode(String mode) throws LuaException {
        if(!"fill".equals(mode)&&!"spray".equals(mode))throw new LuaException("Fill mode must be 'fill' or 'spray'.");
        if(!be.isConcreteBasin())throw new LuaException("Fill mode requires a concrete basin.");
        be.setSprayMode("spray".equals(mode));
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        SuppressionPool p = be.pool();
        m.put("fillMode",getFillMode());m.put("capacityKg",p.getDesignMassKg());
        m.put("sprayHeaderKg",p.getSprayWaterKg());m.put("sprayFlowKgPerS",p.getSprayKgPerS());
        m.put("sprayCondensedKgPerS",p.getSprayCondensedKgPerS());
        m.put("formed", be.isFormed());
        m.put("enclosed",be.isEnclosedTank());m.put("steamPorts",be.steamPortCount());
        m.put("steamInKgPerS",be.steamInKgPerS());m.put("steamBufferKg",p.inletSteam.mass());
        m.put("temperature", p.getTemperatureC());
        m.put("saturation", p.getSaturationTemperatureC());
        m.put("subcooling", p.getSubcoolingC());
        m.put("effectiveness", p.condensationEffectiveness());
        m.put("uncondensed", p.getUncondensedSteamKgPerS());
        m.put("boiling", p.isBoiling());
        m.put("remainingCapacityMJ", p.getRemainingHeatCapacityMJ());
        m.put("massKg", p.getMassKg());
        m.put("rhrDuty", be.getRhrDuty());
        m.put("effectiveRhrDuty", be.getEffectiveRhrDuty());
        m.put("valves", be.dischargingValveCount());
        m.put("heatInMJ", p.getCumulativeHeatInputMJ());
        m.put("heatRemovedMJ", p.getCumulativeRhrRemovedMJ());
        m.put("passiveCoolingMW", be.passiveCoolingMW());
        m.put("ambientTemperatureC", SuppressionPool.AMBIENT_TEMPERATURE_C);
        return m;
    }
}
