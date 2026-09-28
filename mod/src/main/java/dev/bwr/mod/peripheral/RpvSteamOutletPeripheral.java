package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/** Readouts for an always-open vessel nozzle. Use an MSIV for steam isolation. */
public class RpvSteamOutletPeripheral implements IPeripheral {

    private final RpvSteamOutletBlockEntity be;

    public RpvSteamOutletPeripheral(RpvSteamOutletBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_rpv_steam_outlet";
    }

    /**
     * Peripheral identity is the block entity, not this wrapper.
     *
     * <p>CC compares the peripheral it is holding against a fresh capability
     * lookup on every block update, and a peripheral that is not {@code equal}
     * to that fresh lookup detaches and re-attaches forever — twenty
     * {@code peripheral_detach} events a second in the player's Lua. Every other
     * peripheral in this package does the same thing for the same reason.
     *
     * <p>This is {@code IPeripheral.equals(IPeripheral)}, an overload CC declares
     * on its own interface — not {@code Object.equals(Object)} — so there is
     * deliberately no matching {@code hashCode()} here, exactly as in every other
     * peripheral in this package. Overriding {@code hashCode} alone would pair it
     * with an {@code Object.equals} that is still identity, which is the wrong
     * half of the contract to strengthen.
     */
    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof RpvSteamOutletPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // Legacy method names fail clearly instead of pretending to shut an open nozzle.
    @LuaFunction(mainThread = true)
    public final void setPosition(double fraction) throws LuaException {
        if (fraction != 1.0) throw new LuaException("RPV nozzle is always open; control the downstream MSIV.");
    }
    @LuaFunction(mainThread = true) public final void open() {}
    @LuaFunction(mainThread = true) public final void close() throws LuaException {
        throw new LuaException("RPV nozzle is always open; close the downstream MSIV.");
    }
    @LuaFunction(mainThread = true) public final void releaseControl() {}

    // --- Measurements ---------------------------------------------------

    /** Stop valve position, 0.0 shut to 1.0 fully open. */
    @LuaFunction(mainThread = true)
    public final double getPosition() {
        return be.getPosition();
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    /**
     * Steam this nozzle is passing, kg/s.
     *
     * <p>The figure the reactor controller computed on its last walk of the
     * shell, which is the only thing that ever meters a nozzle: the controller
     * holds the dome pressure the flow depends on, and the block carries no
     * ticker of its own.
     *
     * <p>Reported as zero for a nozzle that is shut, has no steam line welded to
     * it, or is not in a formed vessel, rather than quoting the last figure it
     * was ever given. Those three are the conditions under which
     * {@code flowKgPerS} itself returns zero or is never called at all, so this
     * is the same fact restated — not a threshold, and not this class deciding
     * anything. Without it a nozzle whose vessel had since been broken open went
     * on quoting the last flow it ever passed, which is the most misleading
     * thing a readout can say to a program trying to work out why the plant
     * stopped removing steam.
     */
    @LuaFunction(mainThread = true)
    public final double getFlow() {
        return isPassing() ? be.getLastFlowKgPerS() : 0.0;
    }

    private boolean isPassing() {
        return be.getPosition() > 0.0
                && be.isSteamLineAttached()
                && be.isPartOfFormedReactor();
    }

    /**
     * Whether a main steam line block is welded to any face of this nozzle.
     *
     * <p>A nozzle discharging into open world is a hole with no pipe on it and
     * passes nothing. That is a fact about what has been built, not a
     * permissive: the nozzle still opens when told, it simply has nowhere to
     * discharge to. A pressurised tube, an MSIV, an SRV or a turbine steam
     * outlet all count.
     */
    @LuaFunction(mainThread = true)
    public final boolean isSteamLineAttached() {
        return be.isSteamLineAttached();
    }

    /**
     * Whether a formed reactor's controller has asked this nozzle for a flow
     * within the last few ticks.
     *
     * <p>Only the controller of a vessel that has actually assembled walks its
     * nozzles, so this is the honest test for "this block is part of a working
     * multiblock". A nozzle in a vessel with a hole in it reads false and passes
     * nothing however far open its stop valve is.
     */
    @LuaFunction(mainThread = true)
    public final boolean isPartOfFormedReactor() {
        return be.isPartOfFormedReactor();
    }

    /**
     * Steam one fully open nozzle passes at rated dome pressure, kg/s. Nameplate.
     *
     * <p>A compile-time constant, so it stays off the server thread.
     */
    @LuaFunction
    public final double getCapacity() {
        return RpvSteamOutletBlockEntity.CAPACITY_KG_PER_S;
    }

    /**
     * What this nozzle would pass fully open at a stated dome pressure, kg/s.
     *
     * <p>The sizing calculation, published so a program can work out how far to
     * open a nozzle to pass a wanted flow at the pressure it is actually at,
     * instead of rediscovering the choked-flow relation in Lua. Choked mass flow
     * goes linearly with upstream absolute pressure, so this is well under the
     * nameplate figure on a depressurised vessel.
     *
     * <p>Non-finite is refused for the reason {@link #setPosition} gives — a
     * pressure computed from a division by zero would otherwise come back as a
     * plausible-looking zero capacity.
     *
     * @param domePressurePsig vessel pressure to size against, psig
     */
    @LuaFunction(mainThread = true)
    public final double getCapacityAtPressure(double domePressurePsig) throws LuaException {
        if (!Double.isFinite(domePressurePsig)) {
            throw new LuaException("dome pressure must be a finite number, got "
                    + domePressurePsig);
        }
        return be.capacityKgPerS(domePressurePsig);
    }

    /**
     * Rated steam flow for the whole plant, kg/s. A compile-time constant, so it
     * stays off the server thread.
     */
    @LuaFunction
    public final double getRatedSteamFlow() {
        return RpvSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S;
    }

    /**
     * How many nozzles it takes to pass rated steam flow — four, which is what
     * the real plant has. A compile-time constant, so it stays off the server
     * thread.
     */
    @LuaFunction
    public final int getMainSteamLines() {
        return RpvSteamOutletBlockEntity.MAIN_STEAM_LINES;
    }

    /**
     * Everything above in one call, so a control loop can pull one table rather
     * than nine scalars and pay one tick of latency instead of nine.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("position", be.getPosition());
        m.put("computerControlled", be.isComputerControlled());
        m.put("flow", isPassing() ? be.getLastFlowKgPerS() : 0.0);
        m.put("steamLineAttached", be.isSteamLineAttached());
        m.put("partOfFormedReactor", be.isPartOfFormedReactor());
        m.put("capacity", RpvSteamOutletBlockEntity.CAPACITY_KG_PER_S);
        m.put("ratedSteamFlow", RpvSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S);
        m.put("mainSteamLines", RpvSteamOutletBlockEntity.MAIN_STEAM_LINES);
        return m;
    }
}
