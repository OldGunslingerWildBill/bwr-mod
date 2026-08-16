package dev.bwr.mod.steam;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
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

    /** Fraction of the line the isolation valves are leaving open, 0..1. */
    private volatile double mainSteamLineOpenFraction = 1.0;

    private BlockPos controllerPos;

    /** Isolation valves on this outlet's line, refound periodically. */
    private final List<BlockPos> msivPositions = new ArrayList<>();
    private int ticksSinceScan = REBIND_INTERVAL_TICKS;

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

        double dtSeconds = core.getConfig().tickSeconds;

        // Draw down what the pipes took since last tick, then fill from the
        // vessel to the commanded rate, as far as the buffer has room. The
        // headroom limit IS the back-pressure: an outlet nobody is draining
        // stops passing steam, and the reactor feels that as pressure.
        //
        // The isolation valves throttle the demand before the buffer sees it. A
        // shut MSIV is a shut pipe: the commanded flow is irrelevant, nothing
        // leaves, and the vessel expresses that as the pressurisation transient
        // of SPEC section 6.3 — pressure up, voids collapse, moderation up,
        // power surges. None of that sequence is scripted anywhere; it is the
        // consequence of this one multiplication.
        long headroomMb = Math.max(0L, BUFFER_CAPACITY_MB - bufferedMilliBuckets);
        double wantMb = Math.max(0.0, commandedFlowKgPerS) * mainSteamLineOpenFraction
                * dtSeconds * MILLIBUCKETS_PER_KILOGRAM;
        long deliverMb = (long) Math.min(headroomMb, Math.floor(wantMb));

        bufferedMilliBuckets += deliverMb;

        // Report exactly the mass that crossed the boundary, so the kilograms
        // the vessel loses and the millibuckets Mekanism gains are the same
        // steam to the last millibucket.
        deliveredFlowKgPerS = (deliverMb / MILLIBUCKETS_PER_KILOGRAM) / dtSeconds;

        core.setTurbineSteamFlowKgPerS(
                pooledFlowKgPerS(controller, level.getGameTime(), getBlockPos(), deliveredFlowKgPerS));

        setChanged();
    }

    /**
     * Refind the controller and the isolation valves, at most every
     * {@link #REBIND_INTERVAL_TICKS}.
     *
     * <p>The controller half exists because binding used to happen once, in
     * {@code setPlacedBy}, and nowhere else — so an outlet placed before the
     * reactor controller was orphaned permanently, and so was one whose
     * controller block was broken and replaced. The scan only runs when there is
     * no live controller to be found at {@code controllerPos}.
     *
     * <p>The valve half is a plain position cache. Positions only change when
     * blocks are placed or broken, so refinding them twice a second is ample;
     * the valve <i>stroke</i> is read live from the cached block entities on
     * every tick, so closing an MSIV takes effect immediately.
     */
    private void maybeRescan(Level level) {
        if (ticksSinceScan < Integer.MAX_VALUE) {
            ticksSinceScan++;
        }
        if (ticksSinceScan < REBIND_INTERVAL_TICKS) {
            return;
        }
        ticksSinceScan = 0;

        msivPositions.clear();
        boolean needController = controller(level) == null;
        if (needController) {
            controllerPos = null;
        }

        int r = SEARCH_RADIUS;
        BlockPos from = getBlockPos();
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-r, -r, -r), from.offset(r, r, r))) {
            BlockState s = level.getBlockState(p);
            if (s.is(BwrBlocks.MSIV.get())) {
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
     */
    private double isolationValveOpenFraction(Level level) {
        double fraction = 1.0;
        var it = msivPositions.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!(level.getBlockEntity(p) instanceof MainSteamIsolationValveBlockEntity valve)) {
                it.remove();
                continue;
            }
            fraction = Math.min(fraction, Math.max(0.0, Math.min(1.0, valve.getPosition())));
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
     * Take steam out of the buffer. Called by the Mekanism chemical handler, and
     * by nothing else.
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
        if (!isAttached()) {
            out.add("Turbine steam outlet: not attached to a formed reactor.");
            return out;
        }
        out.add(String.format("Turbine steam outlet: commanded %.1f kg/s, delivering %.1f kg/s",
                commandedFlowKgPerS, deliveredFlowKgPerS));
        out.add(String.format("Core boiling %.1f kg/s; buffer %d/%d mB",
                steamProductionKgPerS, bufferedMilliBuckets, BUFFER_CAPACITY_MB));
        if (!msivPositions.isEmpty()) {
            out.add(String.format("%d isolation valve(s) on the line, %.0f%% open",
                    msivPositions.size(), mainSteamLineOpenFraction * 100.0));
        }
        out.add(String.format("Exchange rate %.0f mB per kg (%.0f mB/t per kg/s)",
                MILLIBUCKETS_PER_KILOGRAM, MILLIBUCKETS_PER_TICK_PER_KG_PER_S));
        if (!dev.bwr.mod.BwrMod.isMekanismPresent()) {
            out.add("Mekanism is not installed; this outlet is inert.");
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
        commandedFlowKgPerS = tag.getDouble("Commanded");
        computerControlled = tag.getBoolean("ComputerControlled");
        bufferedMilliBuckets = Math.min(BUFFER_CAPACITY_MB, Math.max(0L, tag.getLong("BufferMb")));
        controllerPos = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
    }
}
