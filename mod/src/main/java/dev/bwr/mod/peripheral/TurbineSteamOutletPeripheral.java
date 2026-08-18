package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
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
 * <h2>"The steam line isn't connecting": what to ask, and in what order</h2>
 * This block is the one boundary between our physics and Mekanism, so when a
 * player's turbine sits there getting nothing, this peripheral is where the
 * answer is. The chain has six links and each one has a readout, so a program —
 * or a player at a Lua prompt — can walk it end to end instead of guessing:
 *
 * <ol>
 *   <li>Is the reactor boiling at all? {@link #getSteamProduction()}.</li>
 *   <li>Is this outlet even bound to a formed reactor? {@link #isAttached()}.</li>
 *   <li>Is there a steam path from the vessel to here, or is this outlet
 *       drawing on the vessel on its own? {@link #isNozzleFed()},
 *       {@link #getFeedNozzleCount()}, and {@link #getNozzleFlow()} for the
 *       ceiling those nozzles actually pass.</li>
 *   <li>Is something in the line shut? {@link #getIsolationValveCount()} and
 *       {@link #getIsolationValveOpenFraction()}.</li>
 *   <li>Has the player commanded any flow, and is it arriving?
 *       {@link #getCommandedFlow()} against {@link #getActualFlow()}.</li>
 *   <li>Is anything on the Mekanism side <i>taking</i> it?
 *       {@link #isMekanismPresent()} and {@link #getBufferFill()}. <b>This is
 *       the link the player's report was about.</b> A buffer pinned at 1.0 with
 *       a healthy commanded flow and a collapsed actual flow means steam is
 *       being made and offered and nothing is drawing it off — which is exactly
 *       what a Mekanism turbine that is not piped to this block looks like from
 *       in here.</li>
 * </ol>
 *
 * <p>{@link #getStatusText()} is the short way round all six: it hands Lua the
 * same prose the block prints when a player right-clicks it, so a diagnosis
 * added to the block is a diagnosis Lua gets, with nothing here to keep in step.
 *
 * <p><b>It is also, at the moment, the only way to reach two of the readings.</b>
 * The block entity measures how many Mekanism acceptors are bolted to the outlet,
 * how many of them will take a push, and how many millibuckets actually left the
 * buffer on the last tick — and it keeps all four figures private, publishing
 * them only as sentences from {@code statusLines()}. So a Lua program can
 * <i>read</i> them but cannot compare them to a number. Deliberately not worked
 * around here: this class may not name a Mekanism type and must not grow a
 * second, drifting copy of a measurement the block already takes. What it wants
 * is four accessors on {@code TurbineSteamOutletBlockEntity} — an adjacent
 * acceptor count, a count of those accepting a push, millibuckets pushed on the
 * last tick, and millibuckets drained by any route on the last tick — at which
 * point they belong in {@link #getStatus()} beside the rest.
 *
 * <p>Nothing in that list is a judgement about the plant. Every one of them is a
 * measurement of what has been built and what was commanded, which is the only
 * kind of statement this mod makes.
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
 * {@link #isMekanismPresent} correctly on an install that has neither. That is
 * also why the acceptor question is asked of the block entity rather than
 * answered here: only {@code dev.bwr.mod.mekanism} may name a Mekanism type, so
 * the capability lookup lives behind that package and this class only reports
 * what comes back.
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
     * Steam that crossed into the outlet buffer on the last tick, kg/s.
     *
     * <p>Below the commanded figure whenever the buffer is backed up — which is
     * what a turbine refusing steam looks like from this side. Note the precise
     * meaning, because it is the difference between two diagnoses: this is what
     * the <i>reactor</i> handed the outlet, not what <i>Mekanism</i> took out of
     * it. Steam that arrives here and is never drawn off simply fills the buffer,
     * and once the buffer is full this figure collapses to zero of its own
     * accord. So a low actual flow says the outlet is backed up; it does not by
     * itself say whether the blockage is a shut valve upstream or an unpiped
     * turbine downstream. {@link #getBufferFill()} separates the two.
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
     * How full the outlet buffer is, 0.0 empty to 1.0 backed right up.
     *
     * <p><b>The single most useful number on this interface for the problem the
     * player actually had.</b> The buffer is deliberately about two and a half
     * ticks of rated flow, so on a plant whose turbine is drawing steam it sits
     * low and jitters. Pinned at 1.0 means steam is being made, has been offered
     * at the flange, and nothing on the Mekanism side has come to take it — which
     * is what an outlet with no Mekanism pipe on it looks like, and also what an
     * outlet feeding a turbine that has stopped accepting looks like. Both are
     * the same fact from here, and both end the same way: the vessel is no longer
     * being relieved, and pressure is the player's to deal with.
     *
     * <p>Divided defensively rather than trustingly. The capacity is a compile-
     * time constant today, but it is read through an instance method, and a zero
     * would hand Lua a NaN — which arithmetic in a control program then spreads
     * silently through every number it touches. See the harness's own non-finite
     * warning, which exists because that has happened here before.
     */
    @LuaFunction(mainThread = true)
    public final double getBufferFill() {
        long capacity = be.getBufferCapacityMilliBuckets();
        if (capacity <= 0L) {
            return 0.0;
        }
        return (double) be.getBufferedMilliBuckets() / (double) capacity;
    }

    // -----------------------------------------------------------------
    // Upstream of the buffer: is any steam even reaching this block?
    // -----------------------------------------------------------------

    /**
     * True when this outlet's steam line runs back to an RPV steam nozzle, so it
     * is the downstream end of one steam path rather than a second, independent
     * draw on the vessel.
     *
     * <p>Both modes work and neither is a fault — see the block entity's class
     * comment for why an unfed outlet is still honoured — but they are fed
     * completely differently, and a program trying to work out why the turbine is
     * short needs to know which one it is looking at. Nozzle-fed, the ceiling is
     * {@link #getNozzleFlow()} and the fix is at the nozzles. Unfed, the ceiling
     * is the vessel itself and the isolation valves are the only restriction.
     */
    @LuaFunction(mainThread = true)
    public final boolean isNozzleFed() {
        return be.isNozzleFed();
    }

    /** How many RPV steam nozzles this outlet's line reaches. Zero when unfed. */
    @LuaFunction(mainThread = true)
    public final int getFeedNozzleCount() {
        return be.getFeedNozzleCount();
    }

    /**
     * Steam every nozzle upstream is passing, kg/s — the ceiling on this outlet
     * rather than the part of it that was taken. Zero for an unfed outlet, which
     * has no nozzles upstream to pass anything.
     */
    @LuaFunction(mainThread = true)
    public final double getNozzleFlow() {
        return be.nozzleFlowKgPerS();
    }

    /**
     * Steam the nozzles upstream actually handed <i>this outlet</i> on the last
     * tick, kg/s.
     *
     * <p>Read against {@link #getNozzleFlow()}: if the supply is well below the
     * flow the nozzles are passing, this outlet is not asking for it — the
     * commanded flow is low, or its own buffer is full. If the two are equal and
     * both are below what was commanded, the nozzles are the ceiling and the fix
     * is to open them further or fit more of them.
     */
    @LuaFunction(mainThread = true)
    public final double getNozzleSupply() {
        return be.getNozzleSupplyKgPerS();
    }

    /** Isolation valves this outlet has found on its line. */
    @LuaFunction(mainThread = true)
    public final int getIsolationValveCount() {
        return be.getIsolationValveCount();
    }

    /**
     * How much of the line the isolation valves are leaving open, 0.0 to 1.0.
     *
     * <p>The most closed valve governs; a line with no MSIV on it reads 1.0,
     * which is what an unisolable line genuinely is. On a nozzle-fed outlet the
     * throttling has already happened at the nozzle, upstream of here, so this is
     * reported for the player's information and is deliberately not applied to
     * the flow a second time.
     */
    @LuaFunction(mainThread = true)
    public final double getIsolationValveOpenFraction() {
        return be.getMainSteamLineOpenFraction();
    }

    /**
     * The same prose a player gets for right-clicking the block, as a table of
     * lines.
     *
     * <p>Deliberately a passthrough of the block's own readout and not a second
     * rendering of the same facts. The block is where the diagnosis is written
     * and where it will keep being extended; anything added there arrives here
     * with nothing to keep in step, and there is no second copy to drift.
     *
     * <p>A {@code List<String>} is a table on the Lua side — CC's value converter
     * handles {@code Collection} the same way it handles {@code Map} — so this
     * reads as {@code for _, line in ipairs(outlet.getStatusText()) do}.
     */
    @LuaFunction(mainThread = true)
    public final List<String> getStatusText() {
        // An immutable snapshot rather than the block's own list. statusLines()
        // builds a fresh ArrayList on every call today, so nothing is shared —
        // but this value crosses to a computer's ownership and a peripheral has
        // no business making another class's allocation habits load-bearing.
        //
        // List.copyOf also refuses a null element, and that is wanted rather than
        // tolerated: CC renders a null as nil, a nil in a Lua array truncates the
        // table at that index, and a status readout that silently loses its last
        // three lines is far worse to debug than a Lua error naming the call.
        return List.copyOf(be.statusLines());
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

    /**
     * Every reading above in one table, so a control loop pays one tick of
     * latency instead of a dozen.
     *
     * <p>Keys are only ever added here, never renamed or removed: a player's
     * program indexes them by name and a rename is a silent {@code nil} in
     * somebody's arithmetic rather than an error they can see.
     */
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
        // The steam path, in the order the class comment walks it. These are the
        // readings the "my turbine gets nothing" question is answered with, and
        // they belong in the one table a control program actually pulls rather
        // than only on individual calls it would have to know to make.
        m.put("bufferFill", getBufferFill());
        m.put("nozzleFed", be.isNozzleFed());
        m.put("feedNozzles", be.getFeedNozzleCount());
        m.put("nozzleFlow", be.nozzleFlowKgPerS());
        m.put("nozzleSupply", be.getNozzleSupplyKgPerS());
        m.put("isolationValves", be.getIsolationValveCount());
        m.put("isolationValveOpen", be.getMainSteamLineOpenFraction());
        return m;
    }
}
