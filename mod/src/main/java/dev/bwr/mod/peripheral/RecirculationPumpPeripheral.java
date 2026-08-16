package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for one external recirculation pump — {@code SPEC.md}
 * section 4.2.
 *
 * <h2>This is where Lua controls core flow on a plant that has pumps</h2>
 * {@code ReactorPeripheral.setRecirculationFlow} writes the core's flow demand
 * directly, and the controller writes the same field from its satellite pumps.
 * Two writers, one field: the pumps take the channel back whenever they actually
 * move, because they are the plant's own hardware, and between moves whatever
 * wrote last stands. So a demand set through the reactor survives only until a
 * pump changes speed, and a flow-controlled power manoeuvre that has to hold is
 * written against <i>this</i> peripheral — which commands the hardware rather
 * than racing it. On a plant with no pump blocks at all, the reactor peripheral
 * is the only handle there is and it holds.
 *
 * <h2>Hardware, not a controller</h2>
 * There is no flow control loop here and no power runback. The pump takes a
 * commanded speed and follows it through a first-order lag with an
 * <b>asymmetric</b> time constant — three seconds spinning up, eleven coasting
 * down, which is flywheel inertia and the reason a loss of power is a survivable
 * flow decay rather than an instant one. If the electrical supply cannot carry
 * the commanded speed the pump runs at whatever it can reach and
 * {@link #isPowerLimited()} says so; nothing reduces the demand on the pump's
 * behalf, and deciding what to do about a brownout is Lua's job.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method is {@code @LuaFunction(mainThread = true)} and must stay that
 * way. CC runs a plain {@code @LuaFunction} on the computer thread; the setters
 * reach {@code BlockEntity.setChanged()}, which walks the chunk map and
 * dispatches neighbour updates, and the readouts sample plain double fields the
 * controller's tick is integrating.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
public class RecirculationPumpPeripheral implements IPeripheral {

    private final RecirculationPumpBlockEntity be;

    public RecirculationPumpPeripheral(RecirculationPumpBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_recirculation_pump";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof RecirculationPumpPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // --- Actuators ------------------------------------------------------

    /**
     * Command pump speed, 0 to 1. Claims computer control, which greys out the
     * GUI slider so the two authorities cannot fight over one demand.
     *
     * <p>Out of range clamps — the speed controller has stops. Non-finite is
     * refused, because {@code setTargetSpeedFraction} clamps with
     * {@code Math.min}/{@code Math.max}, both of which propagate NaN, and a NaN
     * demand would leave the pump permanently unable to reach a target that
     * compares false against everything.
     */
    @LuaFunction(mainThread = true)
    public final void setSpeed(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("pump speed fraction must be a finite number, got " + fraction);
        }
        be.setComputerControlled(true);
        be.setTargetSpeedFraction(fraction);
    }

    /** Hand the pump back to the GUI slider. */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

    // --- Measurements ---------------------------------------------------

    /** Speed the pump has been told to run at, 0 to 1. */
    @LuaFunction(mainThread = true)
    public final double getSpeed() {
        return be.getTargetSpeedFraction();
    }

    /**
     * Speed the pump is actually turning at, 0 to 1. Lags the command, and lags
     * it asymmetrically: this is the coastdown curve.
     */
    @LuaFunction(mainThread = true)
    public final double getActualSpeed() {
        return be.getActualSpeedFraction();
    }

    /** The fastest this pump could turn on the energy it is being given, 0 to 1. */
    @LuaFunction(mainThread = true)
    public final double getAchievableSpeed() {
        return be.maxAchievableSpeedFraction();
    }

    /**
     * True when the pump cannot reach what it was told to do. A measurement of
     * the electrical supply against the demand, not a permissive: the pump keeps
     * running at whatever it can manage.
     */
    @LuaFunction(mainThread = true)
    public final boolean isPowerLimited() {
        return be.isPowerLimited();
    }

    /** Energy in the pump's buffer, FE. */
    @LuaFunction(mainThread = true)
    public final double getEnergy() {
        return be.getEnergyStoredFe();
    }

    @LuaFunction(mainThread = true)
    public final double getEnergyCapacity() {
        return be.getEnergyCapacityFe();
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    /** Draw at full commanded speed, FE per tick. Nameplate. */
    @LuaFunction
    public final double getRatedPowerDraw() {
        return RecirculationPumpBlockEntity.MAX_FE_PER_TICK;
    }

    /** True when this pump found a reactor controller to report its flow to. */
    @LuaFunction(mainThread = true)
    public final boolean isAttached() {
        return be.getControllerPos() != null;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("speed", be.getTargetSpeedFraction());
        m.put("actualSpeed", be.getActualSpeedFraction());
        m.put("achievableSpeed", be.maxAchievableSpeedFraction());
        m.put("powerLimited", be.isPowerLimited());
        m.put("energy", be.getEnergyStoredFe());
        m.put("energyCapacity", be.getEnergyCapacityFe());
        m.put("ratedPowerDraw", RecirculationPumpBlockEntity.MAX_FE_PER_TICK);
        m.put("computerControlled", be.isComputerControlled());
        m.put("attached", be.getControllerPos() != null);
        return m;
    }
}
