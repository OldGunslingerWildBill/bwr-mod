package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for one RPV main steam nozzle.
 *
 * <h2>The last actuator on the plant Lua could not reach</h2>
 * {@code RpvSteamOutletBlockEntity} was written with a stop valve, a
 * {@code setPosition(double)} and a {@code setComputerControlled(boolean)}, and
 * its own class comment says the position "is moved by the player's hand, by an
 * analogue redstone signal, or by Lua". The first two worked. The third did not,
 * because no peripheral was ever registered for the block, so
 * {@code setComputerControlled} had zero callers and the
 * {@code !isComputerControlled()} guard in {@code RpvSteamOutletBlock
 * .neighborChanged} was a condition that could never be false.
 *
 * <p>That mattered more here than it would anywhere else. The nozzles are the
 * <b>main</b> steam path out of the vessel — {@code ReactorControllerBlockEntity
 * .gatherSteamOutletFlow} sums them into the one discharge scalar the controller
 * owns — so a program running the plant on Lua alone had no handle on the
 * principal steam removal path at all. Pressure control had to be done through
 * the relief valves, the turbine outlet, or a lever.
 *
 * <h2>Hardware, and nothing above it</h2>
 * There is no pressure regulator here and no setpoint. {@link #setPosition} moves
 * a stop valve because a program said so; what that opening passes depends on
 * what the vessel is at, and matching the two is the player's control loop. Hold
 * it wide open on a cold vessel and the plant depressurises; hold it shut at
 * power and pressure climbs until the metal gives up. Both are the player's
 * doing, and nothing in this class or the block behind it forms an opinion about
 * either.
 *
 * <h2>Claiming control</h2>
 * Every actuator claims computer control first, exactly as
 * {@code SafetyReliefValvePeripheral} and {@code TurbineSteamOutletPeripheral}
 * do. Without the claim the block owns the position: any neighbour update
 * reaching a Lua-opened nozzle takes the redstone branch and drives the stop
 * valve to whatever {@code getBestNeighborSignal} says — zero, on a nozzle
 * nobody has wired — so a Lua-commanded steam path would close the first time
 * somebody placed a block next to it, with no error and no indication.
 *
 * <p>{@link #releaseControl()} hands it back. The nozzle keeps its present
 * position until a redstone <i>change</i> reaches it, because
 * {@code acceptRedstoneSignal} is edge-triggered on purpose — see its comment —
 * so releasing beside a steady lever does not immediately snap the valve to the
 * lever's position. That is what a hardware selector switch does, and it is the
 * same behaviour the SRV and MSIV peripherals have.
 *
 * <h2>Everything that touches the nozzle runs on the server thread</h2>
 * Every method except the three nameplate constants is
 * {@code @LuaFunction(mainThread = true)} and must stay that way. The actuators
 * reach {@code BlockEntity.setChanged()}, which walks the chunk map and
 * dispatches neighbour updates to adjacent comparators, and the readouts sample
 * fields the controller's tick is writing inside {@code gatherSteamOutletFlow}.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
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

    // --- Actuators ------------------------------------------------------

    /**
     * Move the stop valve, 0.0 shut to 1.0 fully open. Claims computer control.
     *
     * <p>Out of range clamps, because a valve cannot be more than open or less
     * than shut and the handle has stops. <b>Non-finite is refused</b>, and that
     * asymmetry is deliberate: {@code RpvSteamOutletBlockEntity.setPosition}
     * maps a non-finite argument to {@code 0.0}, so a control loop that divided
     * by zero would <i>slam the main steam nozzle shut</i> and be told nothing
     * at all. Lua has one number type and produces NaN trivially — {@code 0/0},
     * or any unguarded division inside a pressure controller — and on a plant at
     * power an unannounced full closure of a steam path is the start of the
     * MSIV-closure transient. A Lua error naming the value is the only way the
     * player finds out their control loop produced a NaN. This is a judgement
     * about the <i>argument</i>, never about the plant.
     */
    @LuaFunction(mainThread = true)
    public final void setPosition(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("stop valve position must be a finite number, got " + fraction);
        }
        be.setComputerControlled(true);
        be.setPosition(fraction);
    }

    /** Throw the stop valve fully open. Unconditional; nothing checks it. */
    @LuaFunction(mainThread = true)
    public final void open() {
        be.setComputerControlled(true);
        be.setPosition(1.0);
    }

    /**
     * Shut the stop valve. Equally unconditional — and on a vessel at power this
     * is one quarter of an MSIV closure, so the pressure transient that follows
     * is the player's to anticipate.
     */
    @LuaFunction(mainThread = true)
    public final void close() {
        be.setComputerControlled(true);
        be.setPosition(0.0);
    }

    /**
     * Hand the nozzle back to redstone and to the player's hand. See the class
     * comment for why the valve does not move until a redstone change arrives.
     */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

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
