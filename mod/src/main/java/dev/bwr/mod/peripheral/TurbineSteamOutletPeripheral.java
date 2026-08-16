package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the turbine steam outlet.
 *
 * <p>Publishes what the boundary is doing and accepts the one command it takes:
 * how much steam to send. There is no pressure regulator and no load-following
 * here — matching turbine demand to what the core is boiling is the player's
 * control loop, and the numbers needed to write it are all on this interface.
 *
 * <p>Notably {@link #setFlow} is the actuator that owns
 * {@code ReactorCore.setTurbineSteamFlowKgPerS} whenever an outlet is attached:
 * the outlet writes the pooled total of every outlet on its reactor every tick,
 * so a competing {@code reactor.setTurbineSteamFlow} call is overwritten on the
 * next tick. Command the outlet, not the reactor, once one exists.
 *
 * <h2>Everything that touches the world runs on the server thread</h2>
 * Every method here except the three that return constants or the mod-load-time
 * Mekanism flag is {@code @LuaFunction(mainThread = true)}, and it must stay
 * that way. See {@link #isAttached()} for the case where it is the difference
 * between a working readout and a permanently false one.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 * It deliberately references no Mekanism type at all, so it answers
 * {@link #isMekanismPresent} correctly on an install that has neither.
 */
public class TurbineSteamOutletPeripheral implements IPeripheral {

    private final TurbineSteamOutletBlockEntity be;

    public TurbineSteamOutletPeripheral(TurbineSteamOutletBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_turbine_steam_outlet";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof TurbineSteamOutletPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    /**
     * True when this outlet is bound to a reactor that has formed.
     *
     * <p><b>{@code mainThread = true} is load-bearing on this one and not merely
     * prudent.</b> {@code isAttached()} walks
     * {@code TurbineSteamOutletBlockEntity.controller(level)} into
     * {@code Level.getBlockEntity(pos)}, which in 1.21.1 opens with
     * {@code return !this.isClientSide && Thread.currentThread() != this.thread
     * ? null : ...}. Off the server thread that guard fires every time, so the
     * lookup returns null, the outlet reports itself unattached on a perfectly
     * good binding, and a program gating its steam demand on this never commands
     * any steam at all. It is not a race — it is deterministic.
     */
    @LuaFunction(mainThread = true)
    public final boolean isAttached() {
        return be.isAttached();
    }

    /**
     * Whether Mekanism is installed. With it absent the outlet is inert: the
     * buffer fills once and nothing ever drains it, so actual flow sits at zero
     * no matter what is commanded.
     *
     * <p>Off-thread deliberately: this reads a static flag written once during
     * mod construction, before any world or any computer exists, and touches
     * neither the level nor the block entity.
     */
    @LuaFunction
    public final boolean isMekanismPresent() {
        return BwrMod.isMekanismPresent();
    }

    /** Steam flow commanded to the turbine, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getCommandedFlow() {
        return be.getCommandedFlowKgPerS();
    }

    /**
     * Command steam flow to the turbine, kg/s. The mod never calls this.
     *
     * <p><b>Claims computer control first</b>, exactly as {@code AdsPeripheral}
     * and {@code EccsPumpPeripheral} do. Without that claim the block keeps
     * ownership of the demand: {@code TurbineSteamOutletBlock.neighborChanged}
     * takes the {@code !isComputerControlled()} branch, reads
     * {@code getBestNeighborSignal} — zero on an outlet with no redstone near it
     * — and rewrites the commanded flow to 0 kg/s. So a Lua steam demand used to
     * survive only until somebody placed a block next to the outlet, at which
     * point the vessel stopped being relieved with no error and no indication.
     * Hand the outlet back to redstone with
     * {@link #setComputerControlled(boolean) setComputerControlled(false)}.
     */
    @LuaFunction(mainThread = true)
    public final void setFlow(double kgPerS) throws LuaException {
        if (!Double.isFinite(kgPerS)) {
            throw new LuaException("steam flow must be a finite number, got " + kgPerS);
        }
        be.setComputerControlled(true);
        be.setCommandedFlowKgPerS(kgPerS);
    }

    /**
     * Command as a fraction of rated steam flow. Not capped at 1 — overdrawing a
     * vessel on purpose is a legitimate thing to want. Claims computer control,
     * for the reason on {@link #setFlow}.
     */
    @LuaFunction(mainThread = true)
    public final void setFlowFraction(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("steam flow fraction must be a finite number, got " + fraction);
        }
        be.setComputerControlled(true);
        be.setCommandedFlowFractionOfRated(fraction);
    }

    /**
     * Steam actually handed over on the last tick, kg/s. Below the commanded
     * figure whenever the buffer is backed up — which is what a turbine
     * refusing steam looks like from this side.
     */
    @LuaFunction(mainThread = true)
    public final double getActualFlow() {
        return be.getDeliveredFlowKgPerS();
    }

    /** What the core is boiling right now, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getSteamProduction() {
        return be.getSteamProductionKgPerS();
    }

    /** Steam waiting in the outlet buffer, Mekanism millibuckets. */
    @LuaFunction(mainThread = true)
    public final double getBufferedSteam() {
        return be.getBufferedMilliBuckets();
    }

    @LuaFunction(mainThread = true)
    public final double getBufferCapacity() {
        return be.getBufferCapacityMilliBuckets();
    }

    /**
     * The documented exchange rate: Mekanism millibuckets per kilogram of steam.
     * A compile-time constant, so it stays off the server thread.
     */
    @LuaFunction
    public final double getMillibucketsPerKilogram() {
        return TurbineSteamOutletBlockEntity.MILLIBUCKETS_PER_KILOGRAM;
    }

    /**
     * Rated steam flow for this plant, kg/s. The scale a redstone 15 commands.
     * A compile-time constant, so it stays off the server thread.
     */
    @LuaFunction
    public final double getRatedFlow() {
        return TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S;
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    /**
     * While true the block's redstone input is ignored and Lua owns the demand.
     * {@link #setFlow} and {@link #setFlowFraction} set it for you; call this
     * with false to hand the outlet back to redstone.
     */
    @LuaFunction(mainThread = true)
    public final void setComputerControlled(boolean computerControlled) {
        be.setComputerControlled(computerControlled);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("attached", be.isAttached());
        m.put("mekanism", BwrMod.isMekanismPresent());
        m.put("commandedFlow", be.getCommandedFlowKgPerS());
        m.put("actualFlow", be.getDeliveredFlowKgPerS());
        m.put("steamProduction", be.getSteamProductionKgPerS());
        m.put("bufferedMb", (double) be.getBufferedMilliBuckets());
        m.put("bufferCapacityMb", (double) be.getBufferCapacityMilliBuckets());
        m.put("mbPerKg", TurbineSteamOutletBlockEntity.MILLIBUCKETS_PER_KILOGRAM);
        m.put("ratedFlow", TurbineSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S);
        m.put("computerControlled", be.isComputerControlled());
        return m;
    }
}
