package dev.bwr.mod.steam;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The turbine steam outlet — the single point where this mod's physics hands
 * steam over to Mekanism ({@code SPEC.md} section 13: "output Mekanism steam at
 * the turbine interface so existing turbines and generators keep working.
 * Everything upstream is ours.").
 *
 * <h2>This class contains no Mekanism types on purpose</h2>
 * Everything here is plain kilograms, kilograms per second and millibuckets —
 * millibuckets being nothing more than a number until something interprets it.
 * The Mekanism-facing half lives in {@code dev.bwr.mod.mekanism} and is only
 * ever loaded when Mekanism is installed. With Mekanism absent this block still
 * places, ticks, saves and answers its peripheral; nothing drains its buffer, so
 * the buffer fills, delivered flow falls to zero and the outlet is simply inert.
 *
 * <p>Steam leaves the buffer two ways, and both of them go through
 * {@link #drainMilliBuckets}. Something adjacent may extract — that is the
 * chemical handler, and it is all there used to be — or this block may push into
 * an adjacent acceptor on its own tick, through {@link SteamExport}. The push
 * exists because extraction alone left the two commonest builds silently dead: a
 * Mekanism turbine valve never extracts from anything, and a freshly placed
 * Mekanism tube only pulls on a face the player has explicitly configured to
 * pull. {@code SteamExport} carries the argument in full.
 *
 * <h2>Flow is commanded, never chosen</h2>
 * The outlet does not decide how much steam to send to the turbine. It passes
 * exactly what it is told, by Lua or by an analogue redstone signal, and the
 * consequence of that command — the vessel depressurising because too much is
 * being drawn, or pressurising because too little is — is left entirely to the
 * physics and to the player. There is no pressure regulator here, and there is
 * not going to be one.
 *
 * <h2>Back-pressure is real</h2>
 * The buffer is deliberately tiny. A turbine that stops accepting steam backs
 * this outlet up within a couple of ticks, the delivered flow collapses to what
 * the pipes actually take, and the reactor sees that reduction as the pressure
 * rise it physically is. That coupling is the whole reason for the buffer to be
 * small rather than generous.
 *
 * <h2>Fed by a nozzle, or drawing on its own: the two modes and why there are two</h2>
 * This outlet used to be a <b>parallel</b> steam path off the vessel rather than
 * the downstream end of one. It found a reactor controller by looking in a
 * 25-block cube around itself and wrote its own figure into
 * {@code ReactorCore.setTurbineSteamFlowKgPerS}, while
 * {@code RpvSteamOutletBlockEntity} — the actual vessel penetration, welded into
 * the shell — removed steam quite separately through
 * {@code setSteamLeakKgPerS}. A player who built both got steam leaving the
 * vessel twice by two independent routes, and needed no pipe between them: an
 * outlet parked in the turbine hall with nothing but air between it and the
 * reactor drew exactly as hard as one welded to a nozzle.
 *
 * <p><b>Nozzle-fed.</b> When {@link SteamLineNetwork} can follow joined steam
 * hardware from this block back to an RPV steam nozzle, this outlet is what it
 * physically is: the far end of that line. The nozzle has already taken the
 * steam out of the vessel this tick, so the outlet <i>claims a share of it</i>
 * through {@code RpvSteamOutletBlockEntity.claimFlowKgPerS} and contributes
 * <b>zero</b> to the turbine channel — the same steam is not removed twice. Its
 * reactor is found by following the pipe rather than by proximity, and its
 * isolation valves are the ones actually <i>in</i> its line rather than any MSIV
 * that happens to be within twelve blocks. The valves throttle at the nozzle
 * where they belong, so they are deliberately not applied a second time here.
 *
 * <p><b>Unfed.</b> When no nozzle is reachable along a line, nothing changes at
 * all: the outlet binds by proximity and draws directly on the vessel exactly as
 * it always has. That is not a grudging compatibility shim, it is the only
 * honest thing to do — every plant built before the nozzle existed has an outlet
 * with no line to a nozzle, those plants work today, and quietly cutting their
 * steam off would be a far worse bug than the one being fixed. The mode is
 * reported in {@link #statusLines()} so a player can see which one they are in
 * and run a pipe if they want the other.
 *
 * <p>The keying is per-outlet and per-line, not per-vessel. An outlet that can
 * reach a nozzle is fed by it; one that cannot is not, even on a vessel that has
 * four nozzles on the other side of it. That is the answer that never breaks a
 * working plant and never silently changes one either.
 */
public class TurbineSteamOutletBlockEntity extends BlockEntity {

    // -----------------------------------------------------------------
    // The conversion constant
    // -----------------------------------------------------------------

    /**
     * Millibuckets of Mekanism steam per kilogram of real steam. <b>This is the
     * single number that sets the exchange rate between our physics and
     * Mekanism's economy, and it is meant to be rebalanced deliberately rather
     * than drifted into.</b>
     *
     * <p>The derivation is mass, not energy. Mekanism's own boilers convert
     * water to steam one millibucket for one millibucket, so a millibucket of
     * Mekanism steam is best read as the millibucket of water it came from. One
     * millibucket of water is one cubic centimetre, which is one gram, so a
     * kilogram of steam is 1000 mB. Nothing here is fudged to hit a power
     * target; the exchange rate falls out of taking Mekanism's own water
     * bookkeeping at face value.
     *
     * <p>An energy-matched constant was rejected. Steam at rated BWR conditions
     * carries about 2.78 MJ/kg, and Mekanism pays a default 10 J per mB of
     * steam through a turbine, so energy parity would demand a rate on the order
     * of 10^5 mB/kg. That would drown any turbine a player can build and would
     * make the number an arbitrary balance knob dressed up as physics. Mass
     * parity is honest and it happens to land somewhere sane.
     *
     * <p>Where it lands: rated steam flow for this plant is
     * {@link #RATED_STEAM_FLOW_KG_PER_S} (about 1940 kg/s, from
     * {@link PhysicalConstants#RATED_STEAM_FLOW_LB_PER_HR}), which at 1000 mB/kg
     * is about 97,000 mB/t. Mekanism's default turbine vent passes 32,000 mB/t,
     * so a reactor at rated power needs a genuinely large industrial turbine —
     * but nowhere near a maximum one, which caps around 1,024,000 mB/t. That is
     * the intended feel: the turbine hall is a real build, not a formality, and
     * there is headroom left for whoever wants to overpower the plant.
     *
     * <p>To rebalance, change this one constant. Everything downstream is
     * derived from it and mass stays conserved across the boundary.
     */
    public static final double MILLIBUCKETS_PER_KILOGRAM = 1000.0;

    /**
     * The same constant expressed the way it is actually used: millibuckets per
     * game tick for each kilogram per second of steam. 1000 mB/kg over 20 ticks
     * per second is 50.
     */
    public static final double MILLIBUCKETS_PER_TICK_PER_KG_PER_S =
            MILLIBUCKETS_PER_KILOGRAM / 20.0;

    /** Rated steam flow for this plant, kg/s. About 1940 kg/s. */
    public static final double RATED_STEAM_FLOW_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * 0.45359237 / 3600.0;

    /**
     * Outlet buffer, millibuckets. Roughly two and a half ticks of rated flow —
     * enough to decouple the tick that produces steam from the tick a pipe pulls
     * it, and small enough that a turbine refusing steam is felt in the vessel
     * almost immediately.
     */
    public static final long BUFFER_CAPACITY_MB = 256_000L;

    /** How far an outlet looks for a controller and for the isolation valves. */
    static final int SEARCH_RADIUS = 12;

    /** Shortest interval between full neighbourhood scans, ticks. */
    private static final int REBIND_INTERVAL_TICKS = 40;

    // -----------------------------------------------------------------
    // Aggregation across outlets
    // -----------------------------------------------------------------

    /**
     * Every outlet attached to a given reactor, and the flow each delivered most
     * recently.
     *
     * <p>{@code ReactorCore.setTurbineSteamFlowKgPerS} takes one total, so with
     * several outlets on one reactor each writing its own figure the last writer
     * would win and the rest of the steam would vanish from the pressure
     * balance. Contributions are pooled here instead and the sum is written.
     *
     * <p>Weakly keyed on the controller block entity, so an unloaded reactor
     * takes its pool with it. Entries whose stamp has gone stale — an outlet
     * broken, unloaded or no longer bound — drop out on their own.
     *
     * <p><b>Nothing reachable from a pool may reference the controller it is
     * keyed on.</b> A {@link WeakHashMap} holds its values strongly, so a value
     * with a path back to its own key keeps that key permanently alive and the
     * weak keying becomes decoration — the whole reactor, its {@link Level} and
     * every loaded chunk stay reachable from a static field for the life of the
     * JVM. Today the pool is {@code BlockPos} to a record of two primitives, and
     * that is the property to preserve: if a contribution ever needs to know
     * which outlet or which reactor it came from, it stores a position, never a
     * block entity.
     */
    private static final Map<ReactorControllerBlockEntity, Map<BlockPos, Contribution>>
            CONTRIBUTIONS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final int CONTRIBUTION_STALE_TICKS = 4;

    private record Contribution(long gameTime, double flowKgPerS) {
    }

    // -----------------------------------------------------------------
    // State
    // -----------------------------------------------------------------

    // Volatile because Lua reads them from a CC computer thread; the matching
    // writes are marshalled onto the server thread by PlantActuators.

    /** The actuator: steam the player wants sent to the turbine, kg/s. */
    private volatile double commandedFlowKgPerS;

    /** When true the redstone input is ignored and Lua owns the demand. */
    private volatile boolean computerControlled;

    /** Steam actually handed over last tick, kg/s. Never more than commanded. */
    private volatile double deliveredFlowKgPerS;

    /** Last reading of what the core is boiling, kg/s. A measurement, nothing more. */
    private volatile double steamProductionKgPerS;

    private volatile long bufferedMilliBuckets;
    // Persistent physical mode: disconnecting an exhaust pipe must not turn this
    // receiver into a second independent vessel steam source.
    private boolean pumpExhaustReceiver;
    private double pendingExhaustMb;
    private long exhaustTick=Long.MIN_VALUE;
    private double acceptedExhaustKgPerS;

    public void connectPumpExhaust() {
        if(pumpExhaustReceiver) return;
        pumpExhaustReceiver=true;
        if(level!=null) {
            var old=controller(level);
            if(old!=null && old.core()!=null) old.core().setTurbineSteamFlowKgPerS(pooledFlowKgPerS(old,level.getGameTime(),getBlockPos(),0));
        }
        setChanged();
    }
    public double pumpExhaustCapacityKgPerS(long tick) {
        double received=tick==exhaustTick?acceptedExhaustKgPerS:0;
        return Math.max(0,Math.min(commandedFlowKgPerS-received,
                (BUFFER_CAPACITY_MB-bufferedMilliBuckets-pendingExhaustMb)/MILLIBUCKETS_PER_TICK_PER_KG_PER_S));
    }
    public void receivePumpExhaustKgPerS(long tick,double flow) {
        if(!(flow>0) || !Double.isFinite(flow)) return;
        if(flow>pumpExhaustCapacityKgPerS(tick)+1e-8) throw new IllegalStateException("Exhaust exceeded reserved receiver capacity");
        if(exhaustTick!=tick) { exhaustTick=tick; acceptedExhaustKgPerS=0; }
        acceptedExhaustKgPerS+=flow;
        pendingExhaustMb+=flow*MILLIBUCKETS_PER_TICK_PER_KG_PER_S;
        long whole=(long)Math.floor(pendingExhaustMb);
        bufferedMilliBuckets+=whole; pendingExhaustMb-=whole;
        setChanged();
    }

    /** Fraction of the line the isolation valves are leaving open, 0..1. */
    private volatile double mainSteamLineOpenFraction = 1.0;

    private BlockPos controllerPos;

    /** Isolation valves on this outlet's line, refound periodically. */
    private final List<BlockPos> msivPositions = new ArrayList<>();
    private int ticksSinceScan = REBIND_INTERVAL_TICKS;

    /**
     * RPV steam nozzles this outlet's line runs back to, refound on the same
     * timer. Empty means unfed — see the class javadoc for the two modes.
     */
    private final List<BlockPos> feedNozzles = new ArrayList<>();

    /** True while at least one nozzle is reachable along the steam line. */
    private volatile boolean nozzleFed;

    /** Steam the nozzles actually handed over on the last tick, kg/s. */
    private volatile double nozzleSupplyKgPerS;

    // -----------------------------------------------------------------
    // What the Mekanism side of the boundary looked like on the last tick
    // -----------------------------------------------------------------
    //
    // Measurements, and nothing reads them but the status text. They exist
    // because the only symptom a player can report from outside this block is
    // "it isn't connecting", and that one sentence covers at least four
    // unrelated faults. Volatile for the same reason as the flows above: every
    // published measurement on this class has ended up being read from a
    // computer thread sooner or later.

    /** Adjacent blocks offering Mekanism's chemical handler towards this one. */
    private volatile int adjacentAcceptors;

    /** How many of those would take steam. The rest are full, or holding something else. */
    private volatile int acceptorsTakingSteam;

    /** Millibuckets pushed into them on the last tick. */
    private volatile long pushedMilliBuckets;

    /** False when Mekanism is installed but no chemical is registered as its steam. */
    private volatile boolean mekanismSteamKnown;

    /**
     * Millibuckets that left the buffer over the last tick by <i>any</i> route:
     * the push, and anything that extracted between ticks. The single number
     * that answers "is something actually taking this steam", which neither the
     * push figure nor the buffer level answers on its own — a tube configured to
     * pull empties this outlet without the push moving a millibucket.
     */
    private volatile long drainedLastTickMb;

    /**
     * The accumulator behind it. Server thread only, and
     * {@link #drainMilliBuckets} is its only writer, because that method is the
     * only way steam leaves this block.
     */
    private long drainedThisTickMb;

    public TurbineSteamOutletBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.TURBINE_STEAM_OUTLET.get(), pos, state);
    }

    // -----------------------------------------------------------------
    // Ticking
    // -----------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  TurbineSteamOutletBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        // Offer the buffer to whatever is bolted on, before anything else.
        //
        // Before, for the same reason the class comment gives for drawing the
        // buffer down and then filling it: what has already left decides how
        // much room there is for more, so an acceptor that takes steam relieves
        // the vessel in this tick rather than the next one. Ahead of every early
        // return below, too — a buffer left standing by a reactor that has come
        // apart is still steam that belongs to whatever is piped to it, and
        // extraction has always worked in that state, so pushing must as well.
        //
        // With Mekanism absent this is one null check. See SteamExport.
        SteamExport.Push pushed = SteamExport.push(this);
        adjacentAcceptors = pushed.acceptors();
        acceptorsTakingSteam = pushed.taking();
        pushedMilliBuckets = pushed.movedMilliBuckets();
        mekanismSteamKnown = pushed.steamKnown();

        // Everything that has left the buffer since this was last read: the push
        // immediately above, plus anything that extracted between ticks.
        // Snapshotted after the push so the two figures describe the same
        // instant instead of being a tick out of step with each other.
        drainedLastTickMb = drainedThisTickMb;
        drainedThisTickMb = 0L;

        if(pumpExhaustReceiver) {
            ReactorControllerBlockEntity old=controller(level);
            if(old!=null && old.core()!=null) old.core().setTurbineSteamFlowKgPerS(pooledFlowKgPerS(old,level.getGameTime(),getBlockPos(),0));
            deliveredFlowKgPerS=level.getGameTime()-exhaustTick<=1?acceptedExhaustKgPerS:0;
            nozzleSupplyKgPerS=0;
            steamProductionKgPerS=0;
            return;
        }
        maybeRescan(level);
        mainSteamLineOpenFraction = isolationValveOpenFraction(level);

        ReactorControllerBlockEntity controller = controller(level);
        if (controller == null || !controller.isFormed()) {
            deliveredFlowKgPerS = 0.0;
            steamProductionKgPerS = 0.0;
            return;
        }
        ReactorCore core = controller.core();
        if (core == null) {
            deliveredFlowKgPerS = 0.0;
            steamProductionKgPerS = 0.0;
            return;
        }

        // A measurement, published so the player's Lua can size its own demand.
        // Nothing in this class compares it to anything.
        steamProductionKgPerS = core.getSteamGenerationKgPerS();

        // A zero or non-finite tick length would turn every rate below into an
        // infinity and hand it to the vessel. It is a compile-time 0.05 today;
        // it is read through a config object, so it is checked once here rather
        // than assumed four times.
        double dtSeconds = core.getConfig().tickSeconds;
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            deliveredFlowKgPerS = 0.0;
            return;
        }

        // Draw down what the pipes took since last tick, then fill to the
        // commanded rate, as far as the buffer has room. The headroom limit IS
        // the back-pressure: an outlet nobody is draining stops passing steam,
        // and the reactor feels that as pressure.
        long headroomMb = Math.max(0L, BUFFER_CAPACITY_MB - bufferedMilliBuckets);
        double headroomKgPerS = (headroomMb / MILLIBUCKETS_PER_KILOGRAM) / dtSeconds;
        // Capped by headroom before anything else, so that a backed-up outlet
        // does not reserve steam at a shared nozzle that it cannot actually take
        // and thereby starve a second outlet on the same line.
        double wantKgPerS = Math.min(Math.max(0.0, commandedFlowKgPerS), headroomKgPerS);

        if (nozzleFed) {
            // Series. The nozzle has already removed this steam from the vessel
            // on the controller's tick, so all that happens here is that some of
            // it is caught on the way past instead of being lost to the
            // condenser. The isolation valves are NOT applied again: they are in
            // the line upstream of this point and the nozzle has already
            // throttled by them, so multiplying here would count one shut valve
            // twice and a half-shut one as a quarter.
            wantKgPerS = claimFromNozzles(level, wantKgPerS);
            nozzleSupplyKgPerS = wantKgPerS;
        } else {
            // No nozzle upstream: the outlet is its own vessel penetration, the
            // way every outlet was before nozzles existed. Here the isolation
            // valves are the only restriction there is, so they throttle the
            // demand before the buffer sees it. A shut MSIV is a shut pipe: the
            // commanded flow is irrelevant, nothing leaves, and the vessel
            // expresses that as the pressurisation transient of SPEC section 6.3
            // — pressure up, voids collapse, moderation up, power surges. None
            // of that sequence is scripted anywhere; it is the consequence of
            // this one multiplication.
            wantKgPerS *= mainSteamLineOpenFraction;
            nozzleSupplyKgPerS = 0.0;
        }

        double wantMb = wantKgPerS * dtSeconds * MILLIBUCKETS_PER_KILOGRAM;
        long deliverMb = wantMb > 0.0 ? (long) Math.min(headroomMb, Math.floor(wantMb)) : 0L;

        bufferedMilliBuckets += deliverMb;

        // Report exactly the mass that crossed the boundary, so the kilograms
        // the vessel loses and the millibuckets Mekanism gains are the same
        // steam to the last millibucket.
        deliveredFlowKgPerS = (deliverMb / MILLIBUCKETS_PER_KILOGRAM) / dtSeconds;

        // Zero when nozzle-fed. The vessel has already lost this steam down the
        // steam-leak channel the controller owns, and writing it here as well is
        // exactly the double removal this whole arrangement exists to end. The
        // contribution is still recorded, at zero, so that the pool's staleness
        // bookkeeping and any unfed outlet sharing the same reactor keep working.
        double contribution = nozzleFed ? 0.0 : deliveredFlowKgPerS;
        core.setTurbineSteamFlowKgPerS(
                pooledFlowKgPerS(controller, level.getGameTime(), getBlockPos(), contribution));

        setChanged();
    }

    /**
     * Take as much of {@code wantKgPerS} as the nozzles upstream are actually
     * passing, kg/s.
     *
     * <p>Round-robin in survey order, each nozzle answering with whatever no
     * other consumer has claimed on this tick. A nozzle in an unloaded chunk, or
     * one whose vessel has come apart, hands over nothing — it is
     * {@code RpvSteamOutletBlockEntity.claimFlowKgPerS} that refuses, on the
     * grounds that a nozzle nobody has polled this tick is not passing steam
     * whatever its last reading said.
     *
     * <p>Entries are pruned when the block behind them has gone, exactly as the
     * valve list is. They are re-found on the next survey either way.
     */
    private double claimFromNozzles(Level level, double wantKgPerS) {
        if (!(wantKgPerS > 0.0)) {
            return 0.0;
        }
        long gameTime = level.getGameTime();
        double got = 0.0;
        var it = feedNozzles.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!level.isLoaded(p)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof RpvSteamOutletBlockEntity nozzle)) {
                it.remove(); // the nozzle was broken or replaced
                continue;
            }
            got += SteamValveRouting.claim(level,worldPosition,nozzle,wantKgPerS-got);
            if (!(wantKgPerS - got > 0.0)) {
                break;
            }
        }
        return got;
    }

    /**
     * Refind the nozzles, the controller and the isolation valves, at most every
     * {@link #REBIND_INTERVAL_TICKS}.
     *
     * <p>The line is walked first, because what it finds decides everything
     * else. A line that runs back to an RPV steam nozzle gives this outlet both
     * its reactor — through the nozzle, which the controller stamps on every one
     * of its own ticks — and its isolation valves, which are the ones genuinely
     * in the pipe rather than merely nearby. That is the whole difference between
     * a steam path and two blocks that happen to be within twelve blocks of each
     * other.
     *
     * <p>The neighbourhood scan is kept for the outlet that has no nozzle behind
     * it. It exists because binding used to happen once, in {@code setPlacedBy},
     * and nowhere else — so an outlet placed before the reactor controller was
     * orphaned permanently, and so was one whose controller block was broken and
     * replaced. It also runs when a nozzle-fed outlet cannot yet name its
     * controller, which is the case for the tick or two after a chunk loads and
     * before the controller has polled its nozzles.
     *
     * <p>Either way it is a plain position cache. Positions only change when
     * blocks are placed or broken, so refinding them twice a second is ample;
     * the valve <i>stroke</i> is read live from the positions on every tick, so
     * closing an MSIV takes effect immediately.
     */
    private void maybeRescan(Level level) {
        if (ticksSinceScan < Integer.MAX_VALUE) {
            ticksSinceScan++;
        }
        if (ticksSinceScan < REBIND_INTERVAL_TICKS) {
            return;
        }
        ticksSinceScan = 0;

        SteamLineNetwork.Survey survey = SteamLineNetwork.survey(level, getBlockPos());
        feedNozzles.clear();
        feedNozzles.addAll(survey.nozzles());
        nozzleFed = !feedNozzles.isEmpty();

        msivPositions.clear();
        if (nozzleFed) {
            msivPositions.addAll(survey.isolationValves());
            BlockPos throughTheLine = controllerFromNozzles(level);
            if (throughTheLine != null) {
                controllerPos = throughTheLine;
                setChanged();
                return;
            }
        }
        scanNeighbourhood(level, !nozzleFed);
    }

    /**
     * The controller of the reactor whose shell these nozzles are welded into.
     *
     * <p>The nozzles do not look it up; the controller stamps itself on them as
     * it walks them, so this only reads back a fact a formed reactor has already
     * published. A nozzle in a vessel that has come apart has no controller to
     * offer, which is the correct answer rather than a missing one.
     */
    private BlockPos controllerFromNozzles(Level level) {
        for (BlockPos p : feedNozzles) {
            if (!level.isLoaded(p)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof RpvSteamOutletBlockEntity nozzle)) {
                continue;
            }
            BlockPos candidate = nozzle.getControllerPos();
            if (candidate != null && level.isLoaded(candidate)
                    && level.getBlockEntity(candidate) instanceof ReactorControllerBlockEntity) {
                return candidate.immutable();
            }
        }
        return null;
    }

    /**
     * The proximity scan an unfed outlet has always used.
     *
     * <p>{@code BlockPos.betweenClosed} hands out one recycled cursor, so every
     * position that goes into {@code msivPositions} is copied with
     * {@code immutable()} on the way in. Storing the cursor would fill the list
     * with as many references to the last block of the sweep as there are valves.
     *
     * @param collectValves whether to gather isolation valves as well; false when
     *                      the line survey has already answered that question
     *                      better than a box around the outlet can
     */
    private void scanNeighbourhood(Level level, boolean collectValves) {
        boolean needController = controller(level) == null;
        if (needController) {
            controllerPos = null;
        }
        if (!needController && !collectValves) {
            return;
        }

        int r = SEARCH_RADIUS;
        BlockPos from = getBlockPos();
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-r, -r, -r), from.offset(r, r, r))) {
            // Never read a block out of an unloaded chunk: Level.getBlockState
            // will generate terrain to answer, and a 25-block cube around an
            // outlet reaches into four chunk columns.
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (collectValves && s.is(BwrBlocks.MSIV.get())) {
                msivPositions.add(p.immutable());
            } else if (needController && controllerPos == null
                    && s.is(BwrBlocks.REACTOR_CONTROLLER.get())
                    && level.getBlockEntity(p) instanceof ReactorControllerBlockEntity c) {
                bindController(c);
            }
        }
    }

    /**
     * How much of the main steam line the isolation valves are leaving open.
     *
     * <p>The <i>most closed</i> valve governs, not the product of them all.
     * Valves in series are one restriction each in the same pipe, and to the
     * accuracy this model works at the tightest one sets the flow; multiplying
     * would make a second, fully open valve halve the line, which is not what a
     * second valve does. No MSIV on the line at all means an unisolable line,
     * which is exactly what a plant built without them has.
     *
     * <p>A valve whose chunk is not loaded is skipped, not dropped.
     * {@code Level.getBlockEntity} answers null for an unloaded chunk exactly as
     * it does for a broken block, and treating the two the same made a valve
     * across a chunk border deregister itself — the failure
     * {@code ReactorControllerBlockEntity.gatherPumpFlow} documents at length for
     * the pumps.
     */
    private double isolationValveOpenFraction(Level level) {
        double fraction = 1.0;
        var it = msivPositions.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!level.isLoaded(p)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof MainSteamIsolationValveBlockEntity valve)) {
                it.remove();
                continue;
            }
            double open = valve.getPosition();
            // Clamped rather than trusted: a stroke read out of a hand-edited
            // save could be anything, and a NaN here would propagate into the
            // vessel's steam balance through the multiplication in tick().
            fraction = Math.min(fraction,
                    Double.isFinite(open) ? Math.max(0.0, Math.min(1.0, open)) : 0.0);
        }
        return fraction;
    }

    /** Fraction of the line the isolation valves are leaving open, 0..1. */
    public double getMainSteamLineOpenFraction() {
        return mainSteamLineOpenFraction;
    }

    /** Isolation valves this outlet has found on its line. */
    public int getIsolationValveCount() {
        return msivPositions.size();
    }

    /**
     * True when this outlet's steam line runs back to an RPV steam nozzle, and
     * it is therefore the downstream end of one steam path rather than a
     * separate draw on the vessel. See the class javadoc for the two modes.
     */
    public boolean isNozzleFed() {
        return nozzleFed;
    }

    /** RPV steam nozzles this outlet's line reaches. */
    public int getFeedNozzleCount() {
        return feedNozzles.size();
    }

    /**
     * Steam the nozzles upstream actually handed <i>this outlet</i> on the last
     * tick, kg/s — what it claimed, not what they were passing. Zero for an
     * unfed outlet, which has no nozzles to hand it anything.
     */
    public double getNozzleSupplyKgPerS() {
        return nozzleSupplyKgPerS;
    }

    /**
     * Steam every nozzle upstream is passing, kg/s — the ceiling on this outlet
     * rather than the part of it that was taken.
     *
     * <p>Computed on demand rather than cached, because the only caller is a
     * player right-clicking the block and a per-tick figure nobody reads twenty
     * times a second is the dead wiring this codebase keeps finding in itself.
     */
    public double nozzleFlowKgPerS() {
        if (level == null || feedNozzles.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (BlockPos p : feedNozzles) {
            if (!level.isLoaded(p)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof RpvSteamOutletBlockEntity nozzle
                    && nozzle.isPartOfFormedReactor()) {
                total += nozzle.getLastFlowKgPerS();
            }
        }
        return total;
    }

    /**
     * Record this outlet's delivered flow and return the total across every
     * outlet on the same reactor.
     */
    private static double pooledFlowKgPerS(ReactorControllerBlockEntity controller, long gameTime,
                                           BlockPos self, double flowKgPerS) {
        synchronized (CONTRIBUTIONS) {
            Map<BlockPos, Contribution> pool =
                    CONTRIBUTIONS.computeIfAbsent(controller, k -> new HashMap<>());
            pool.put(self, new Contribution(gameTime, flowKgPerS));

            double total = 0.0;
            Iterator<Map.Entry<BlockPos, Contribution>> it = pool.entrySet().iterator();
            while (it.hasNext()) {
                Contribution c = it.next().getValue();
                if (gameTime - c.gameTime() > CONTRIBUTION_STALE_TICKS) {
                    it.remove();
                    continue;
                }
                total += c.flowKgPerS();
            }
            return total;
        }
    }

    /**
     * Withdraw this outlet from the pool and write the reduced total back
     * immediately.
     *
     * <p>Waiting for the contribution to go stale is not good enough. If this
     * was the <i>last</i> outlet on the reactor then nothing is left to rewrite
     * the total, and the vessel would go on venting steam down a pipe that no
     * longer exists. Breaking the outlet has to stop the steam in the same tick,
     * and the pressure rise that follows is the consequence the player earns.
     */
    private void forgetContribution() {
        if (level == null || level.isClientSide() || controllerPos == null) {
            return;
        }
        if (!(level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity controller)) {
            return;
        }
        long gameTime = level.getGameTime();
        double remaining;
        synchronized (CONTRIBUTIONS) {
            Map<BlockPos, Contribution> pool = CONTRIBUTIONS.get(controller);
            if (pool == null) {
                return;
            }
            pool.remove(getBlockPos());
            remaining = 0.0;
            Iterator<Map.Entry<BlockPos, Contribution>> it = pool.entrySet().iterator();
            while (it.hasNext()) {
                Contribution c = it.next().getValue();
                if (gameTime - c.gameTime() > CONTRIBUTION_STALE_TICKS) {
                    it.remove();
                    continue;
                }
                remaining += c.flowKgPerS();
            }
        }
        ReactorCore core = controller.core();
        if (core != null) {
            core.setTurbineSteamFlowKgPerS(remaining);
        }
    }

    @Override
    public void setRemoved() {
        forgetContribution();
        super.setRemoved();
    }

    // -----------------------------------------------------------------
    // The actuator
    // -----------------------------------------------------------------

    public double getCommandedFlowKgPerS() {
        return commandedFlowKgPerS;
    }

    /**
     * Command steam to the turbine, kg/s. Written by Lua or by redstone; never
     * by the mod. Negative is clamped to zero because negative flow is not a
     * thing, not because it would be dangerous.
     */
    public void setCommandedFlowKgPerS(double kgPerS) {
        // Marshalled onto the server thread: Lua calls this from a CC computer
        // thread and setChanged() dispatches neighbour updates. See
        // PlantActuators.
        PlantActuators.run(this, () -> {
            this.commandedFlowKgPerS = Double.isFinite(kgPerS) ? Math.max(0.0, kgPerS) : 0.0;
            setChanged();
        });
    }

    /**
     * Command as a fraction of rated steam flow. Pure convenience over
     * {@link #setCommandedFlowKgPerS}; the fraction is not capped at 1, because
     * a player may well want to overdraw a vessel on purpose.
     */
    public void setCommandedFlowFractionOfRated(double fraction) {
        setCommandedFlowKgPerS(fraction * RATED_STEAM_FLOW_KG_PER_S);
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        PlantActuators.run(this, () -> {
            this.computerControlled = computerControlled;
            setChanged();
        });
    }

    // -----------------------------------------------------------------
    // Measurements
    // -----------------------------------------------------------------

    /** Steam handed to Mekanism on the last tick, kg/s. */
    public double getDeliveredFlowKgPerS() {
        return deliveredFlowKgPerS;
    }

    /** What the core was boiling on the last tick, kg/s. */
    public double getSteamProductionKgPerS() {
        return steamProductionKgPerS;
    }

    public long getBufferedMilliBuckets() {
        return bufferedMilliBuckets;
    }

    public long getBufferCapacityMilliBuckets() {
        return BUFFER_CAPACITY_MB;
    }

    /**
     * Take steam out of the buffer. Called by the Mekanism chemical handler when
     * something extracts, and by the push when something adjacent will accept;
     * by nothing else.
     *
     * <p>That it is the only way out is what keeps the two paths honest. Steam
     * pushed to a neighbour has left the buffer by the time anything can extract
     * it, and steam extracted has left before the next push can offer it, so no
     * millibucket is ever handed over twice however many consumers are bolted to
     * the block.
     *
     * @return millibuckets actually removed
     */
    public long drainMilliBuckets(long requested, boolean simulate) {
        if (requested <= 0L || bufferedMilliBuckets <= 0L) {
            return 0L;
        }
        long drained = Math.min(requested, bufferedMilliBuckets);
        if (!simulate) {
            bufferedMilliBuckets -= drained;
            drainedThisTickMb += drained;
            setChanged();
        }
        return drained;
    }

    // -----------------------------------------------------------------
    // Controller association
    // -----------------------------------------------------------------

    public void bindController(ReactorControllerBlockEntity controller) {
        this.controllerPos = controller.getBlockPos();
        setChanged();
    }

    public BlockPos getControllerPos() {
        return controllerPos;
    }

    public ReactorControllerBlockEntity controller(Level level) {
        if (level == null || controllerPos == null) {
            return null;
        }
        return level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    /** True when this outlet is bound to a reactor that has actually formed. */
    public boolean isAttached() {
        ReactorControllerBlockEntity c = controller(level);
        return c != null && c.isFormed();
    }

    /** Lines shown when a player right-clicks the outlet. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if(pumpExhaustReceiver) {
            out.add(String.format("Feed-pump exhaust receiver: commanded %.1f kg/s, received %.1f kg/s; buffer %d/%d mB",
                    commandedFlowKgPerS,deliveredFlowKgPerS,bufferedMilliBuckets,BUFFER_CAPACITY_MB));
            out.add("Receives only piped pump exhaust. Pick up and place again to return this outlet to main-steam service.");
            out.addAll(mekanismBoundaryLines());
            return out;
        }
        if (!isAttached()) {
            out.add("Turbine steam outlet: not attached to a formed reactor.");
            // The downstream half is still worth reporting. An outlet with no
            // reactor behind it and an outlet with no pipe in front of it are
            // different problems with the same symptom, and a player who has
            // just built both ends deserves to be told which one they have.
            out.addAll(mekanismBoundaryLines());
            return out;
        }
        out.add(String.format("Turbine steam outlet: commanded %.1f kg/s, delivering %.1f kg/s",
                commandedFlowKgPerS, deliveredFlowKgPerS));
        out.add(String.format("Core boiling %.1f kg/s; buffer %d/%d mB",
                steamProductionKgPerS, bufferedMilliBuckets, BUFFER_CAPACITY_MB));
        // Which of the two modes this outlet is in, because it decides where its
        // steam comes from and it is not visible from outside the block.
        if (isNozzleFed()) {
            // Two different numbers, and the difference between them is the whole
            // diagnosis. What the nozzles are passing is the ceiling on this
            // outlet; what it took is how much of that ceiling the commanded flow
            // and the buffer actually reached. A player whose turbine is starved
            // needs to know which of the two is short.
            out.add(String.format(
                    "Fed by %d RPV steam nozzle(s) along the steam line, which are passing"
                            + " %.1f kg/s; took %.1f kg/s of it. Open the nozzles further or"
                            + " fit more of them to raise the ceiling.",
                    getFeedNozzleCount(), nozzleFlowKgPerS(), getNozzleSupplyKgPerS()));
        } else {
            out.add("No RPV steam nozzle on this outlet's steam line, so it draws on the"
                    + " vessel directly. Run pressurised steam tube from a nozzle in the"
                    + " vessel shell to put the two in series.");
        }
        if (getIsolationValveCount() > 0) {
            out.add(String.format("%d isolation valve(s) on the line, %.0f%% open%s",
                    getIsolationValveCount(), getMainSteamLineOpenFraction() * 100.0,
                    isNozzleFed() ? " (throttling at the nozzle, upstream of here)" : ""));
        }
        // Whether the reactor end is doing anything at all, which is the half of
        // "it isn't connecting" that has nothing to do with Mekanism. Only the
        // case that is actually in front of the player is printed; the figures
        // that separate the causes are all on the lines above.
        if (!(commandedFlowKgPerS > 0.0)) {
            out.add("Commanded flow is zero, so nothing is being asked of this outlet."
                    + " Give it a redstone signal, or command a flow from a computer.");
        } else if (!(deliveredFlowKgPerS > 0.0)) {
            out.add("Commanded steam is not reaching the buffer: either the buffer is full"
                    + " because nothing downstream is taking any, or the isolation valves"
                    + " are shut, or the nozzles on this line are passing nothing."
                    + " The figures above say which.");
        }
        out.add(String.format("Exchange rate %.0f mB per kg (%.0f mB/t per kg/s)",
                MILLIBUCKETS_PER_KILOGRAM, MILLIBUCKETS_PER_TICK_PER_KG_PER_S));
        out.addAll(mekanismBoundaryLines());
        return out;
    }

    /**
     * The Mekanism side of the boundary, as measured on the last tick.
     *
     * <p>Written for one specific bug report — "the steam line isn't connecting"
     * — which is everything a player can see from outside this block and which
     * covers at least four unrelated faults. The lines below separate them in
     * the order they have to be eliminated: is Mekanism installed, does it have
     * the chemical we hand over, is anything bolted to the outlet, would that
     * thing take steam, and is any steam actually crossing. Every one of them is
     * a measurement of what happened; not one of them decides anything.
     */
    private List<String> mekanismBoundaryLines() {
        List<String> out = new ArrayList<>();
        if (!dev.bwr.mod.BwrMod.isMekanismPresent()) {
            out.add("Mekanism is not installed; this outlet is inert.");
            return out;
        }
        if (!mekanismSteamKnown) {
            // Nothing has pushed yet, or this Mekanism registers no steam. Either
            // way the boundary cannot name what it would hand over, and saying so
            // is far better than the "nothing is connected" the counts below
            // would otherwise report.
            out.add("Mekanism is installed but this outlet has not yet found a chemical"
                    + " registered as mekanism:steam, so it has nothing it can hand over.");
            return out;
        }
        if (adjacentAcceptors == 0) {
            out.add("No Mekanism chemical acceptor on any of the six faces. Put a Mekanism"
                    + " pressurised tube or a turbine valve flat against this block — our own"
                    + " pressurised steam tube is structural and carries nothing, so it is the"
                    + " wrong pipe for this end of the line.");
        } else if (acceptorsTakingSteam == 0) {
            // Deliberately "will not accept a push" rather than "is not taking
            // steam". Something that only ever extracts would be counted here
            // and would still be emptying the buffer, so the claim is kept to
            // what was actually measured and the next line settles it.
            out.add(String.format(
                    "%d Mekanism acceptor(s) against this block, none of which will accept a"
                            + " push right now: full, holding something that is not steam, or"
                            + " built to extract rather than be fed.",
                    adjacentAcceptors));
        } else {
            out.add(String.format(
                    "%d of %d Mekanism acceptor(s) accepting a push; pushed %d mB last tick.",
                    acceptorsTakingSteam, adjacentAcceptors, pushedMilliBuckets));
        }
        // Push and extraction drain the same buffer, so this one figure covers a
        // tube set to pull just as well as it covers what the outlet pushed out.
        // It is the line that answers "is any steam actually crossing", and it
        // is the only one of these that cannot be fooled by how it crossed.
        out.add(String.format("Steam leaving the buffer %d mB/t (%.1f kg/s).",
                drainedLastTickMb, drainedLastTickMb / MILLIBUCKETS_PER_TICK_PER_KG_PER_S));
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("PumpExhaustReceiver",pumpExhaustReceiver);
        tag.putDouble("PendingExhaustMb",pendingExhaustMb);
        tag.putDouble("Commanded", commandedFlowKgPerS);
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putLong("BufferMb", bufferedMilliBuckets);
        if (controllerPos != null) {
            tag.putLong("Controller", controllerPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pumpExhaustReceiver=tag.getBoolean("PumpExhaustReceiver");
        double pending=tag.getDouble("PendingExhaustMb");
        pendingExhaustMb=Double.isFinite(pending)?Math.max(0,Math.min(.999999999,pending)):0;
        // Clamped the same way the setter clamps. A commanded flow read straight
        // out of NBT is the one path into this field that does not go through
        // setCommandedFlowKgPerS, so a hand-edited or corrupted save was the one
        // way to get a NaN or a negative demand into the steam balance.
        double commanded = tag.getDouble("Commanded");
        commandedFlowKgPerS = Double.isFinite(commanded) ? Math.max(0.0, commanded) : 0.0;
        computerControlled = tag.getBoolean("ComputerControlled");
        bufferedMilliBuckets = Math.min(BUFFER_CAPACITY_MB, Math.max(0L, tag.getLong("BufferMb")));
        controllerPos = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
        // What the line looks like is a fact about the world, and the world has
        // not been asked yet. Until the first survey the outlet is unfed, which
        // is the direction that draws no steam it has not earned.
        feedNozzles.clear();
        msivPositions.clear();
        nozzleFed = false;
        nozzleSupplyKgPerS = 0.0;
        deliveredFlowKgPerS = 0.0;
        ticksSinceScan = REBIND_INTERVAL_TICKS;
    }
}
