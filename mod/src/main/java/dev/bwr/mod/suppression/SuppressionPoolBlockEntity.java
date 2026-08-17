package dev.bwr.mod.suppression;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.mod.eccs.EccsNetwork;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.eccs.ReactorEccsBus;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ValidationResult;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The suppression pool multiblock — {@code SPEC.md} section 12.
 *
 * <p>A thin Minecraft shell around {@link SuppressionPool}, which holds all the
 * thermodynamics and is plain Java. This class finds the water volume, finds the
 * SRVs discharging into it, and moves heat between them.
 *
 * <h2>Both halves of the relief path are accounted here</h2>
 * Steam that goes into this pool has to come out of the vessel, and the two used
 * to be the responsibility of two unrelated block entities: the pool condensed
 * whatever the valves it could see were passing, while only an
 * {@code AdsControllerBlockEntity} ever told the vessel it had lost anything. A
 * plant with a pool and a redstone-actuated relief valve and no ADS therefore
 * gained mass and energy out of nothing — the pool heated at a hundred-odd
 * megawatts while dome pressure did not fall by one psi. The pool now reports
 * each valve on the relief channel itself, keyed on the valve so that an ADS
 * controller watching the same bank overwrites the identical figure rather than
 * adding a second copy of it. Whatever the pool absorbs, the vessel loses.
 *
 * <h2>How big the pool is</h2>
 * The design inventory is the basin the player dug, not a constant, and
 * {@link SuppressionPool} measures heat capacity, level and the suction floor
 * against it — so the block count below is physics and a wrong one is a wrong
 * plant. It is found by a bounded flood fill from the controller
 * ({@link #surveyBasin}) rather than by counting every water block in range,
 * because counting everything in range means a controller set down beside an
 * ocean, a lake or a flooded cave is metering a heat sink with tens of
 * thousands of tonnes in it that the player never built.
 *
 * <h2>What it does not do</h2>
 * There is no heat capacity temperature limit here, no alarm, and no automatic
 * RHR start. Pool temperature, subcooling and condensation effectiveness are
 * published; what counts as too hot is a number a real plant sets
 * administratively, so in this mod it is the player's to choose and act on in
 * Lua.
 */
public class SuppressionPoolBlockEntity extends BlockEntity {

    /** How far out the controller looks for its water volume and its valves. */
    private static final int SEARCH_RADIUS = 24;
    /** Litres of pool water represented by one water block. */
    private static final double KG_PER_WATER_BLOCK = 1000.0;
    /** Below this many water blocks it is a puddle, not a suppression pool. */
    private static final int MIN_WATER_BLOCKS = 64;

    /**
     * How far from the controller a water block can be and still be taken as
     * the start of the pool, blocks.
     *
     * <p>The controller has to be <i>at</i> the basin it meters. This is a
     * small tolerance rather than strict face-adjacency so that a controller
     * set into the rim wall, or standing a block or two proud of the water
     * line, still finds its pool — but it is nowhere near the search radius,
     * because "there is water somewhere within 24 blocks" is exactly the rule
     * that let a controller adopt the sea.
     */
    private static final int SEED_RADIUS = 4;

    /**
     * Largest basin one controller will survey, water blocks.
     *
     * <p>This is a limit on the survey, not a safety rule. 32,768 blocks is
     * 32,768 tonnes, nearly ten times a BWR/6 pool and a 32-cube of water to
     * dig; a contiguous body larger than that inside the search radius is a
     * flooded cave system rather than anything anyone built as a basin, and
     * sizing the physics from it would hand the player a heat sink that never
     * warms. Reported as a failure with the count, so a player who genuinely
     * dug something enormous can see what happened rather than guess.
     */
    private static final int MAX_WATER_BLOCKS = 32_768;

    /**
     * Water blocks the survey will walk in bodies it has <i>refused</i>, across
     * the whole survey, before it gives up on finding a basin at all.
     *
     * <p>Only refused walks are charged against this, never the walk that finds
     * the pool, so it is a ceiling on wasted work and not on the size of a
     * basin. It exists because a refused body may be walked more than once —
     * see {@link #surveyBasin} on why a refused body must never be remembered
     * as though it were a wall. Two full oversize bodies' worth is enough that
     * no plausible shoreline build exhausts it, and it holds the worst case to
     * roughly three times the fluid lookups the hardware pass above already
     * does on every survey.
     */
    private static final int SURVEY_WORK_LIMIT = 2 * MAX_WATER_BLOCKS;

    /** The six faces, hoisted: {@code Direction.values()} clones its array. */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Shortest interval between structure scans, ticks.
     *
     * <p>The scan reads block and fluid state at all 117,649 positions of a
     * 49-cube. A neighbour change fires on every redstone edge, so an
     * unthrottled rescan is millions of lookups a second for a controller whose
     * neighbour merely blinked.
     */
    private static final int MIN_REVALIDATE_INTERVAL_TICKS = 20;

    /**
     * How often an unformed pool re-checks, ticks.
     *
     * <p>Revalidation used to happen only when a block directly against the
     * controller changed. Water placed anywhere else in the search radius fires
     * no neighbour change at all, so a player who set the controller down first
     * and then dug the basin was told "found 0 water blocks" forever with no
     * way to know they had to poke the block.
     */
    private static final int UNFORMED_REVALIDATE_INTERVAL_TICKS = 100;

    /** How often a formed pool re-checks, ticks. Catches a pool being drained. */
    private static final int FORMED_REVALIDATE_INTERVAL_TICKS = 600;

    /** Reports older than this are treated as gone, ticks. Matches the ECCS bus. */
    private static final long STALE_TICKS = 3L;

    private final SuppressionPool pool = new SuppressionPool();

    private ValidationResult lastValidation = new ValidationResult();
    private boolean structureDirty = true;
    private volatile boolean formed;
    private volatile int waterBlocks;
    private int ticksSinceRevalidate = UNFORMED_REVALIDATE_INTERVAL_TICKS;

    /** RHR duty commanded directly on this controller, 0..1. The player's. */
    private volatile double rhrDuty;
    private double rhrCapacityMW = 30.0;
    private double heatSinkC = 30.0;

    /** Sum of the duties RHR loops reported this tick. Transient by design. */
    private volatile double machineRhrDuty;

    private final List<BlockPos> dischargingValves = new ArrayList<>();
    private BlockPos reactorPos;

    /** Steam a machine says it is discharging into this pool. */
    private record SteamReport(long tick, double kgPerS, double pressurePsig) {
    }

    /** Pool-cooling duty a machine says it is supplying. */
    private record DutyReport(long tick, double duty) {
    }

    private final Map<BlockPos, SteamReport> steamReports = new HashMap<>();
    private final Map<BlockPos, DutyReport> dutyReports = new HashMap<>();

    public SuppressionPoolBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.SUPPRESSION_POOL.get(), pos, state);
        EccsNetwork.ensureListenerRegistered();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SuppressionPoolBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        maybeRevalidate(level);
        if (!formed) {
            return;
        }

        double dt = 0.05;
        long gameTime = level.getGameTime();

        // No reactor, no steam. This used to default to RATED_DOME_PRESSURE_PSIG
        // and only replace it when a controller could be resolved, so a pool that
        // had lost its reactor sized every valve at full rated conditions and
        // poured 113 MW into itself from a plant that did not exist. A missing
        // reactor is an absence of steam, not steam at rated pressure.
        ReactorControllerBlockEntity reactor = reactor(level);
        ReactorCore core = reactor != null ? reactor.core() : null;
        double domePressurePsig = core != null ? core.getPressurePsig() : 0.0;
        double containmentPsia = pool.getContainmentPressurePsia();

        ReactorEccsBus bus = (core != null && reactorPos != null)
                ? EccsNetwork.busFor(level, reactorPos) : null;

        // Everything the SRVs are passing arrives here as steam to condense —
        // and leaves the vessel by the same accounting, see the class comment.
        double srvKgPerS = 0.0;
        var it = dischargingValves.iterator();
        while (it.hasNext()) {
            BlockPos vp = it.next();
            if (!(level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)) {
                it.remove();
                continue;
            }
            // With no reactor domePressurePsig is 0.0, which is at or below
            // containment, so the valve correctly passes nothing and its cached
            // reading is zeroed rather than left at its last discharge.
            double flow = srv.flowKgPerS(domePressurePsig, containmentPsia);
            if (bus != null) {
                bus.report(vp, gameTime, 0.0, 0.0, 0.0, 0.0, flow, 0.0, false, true);
            }
            // Only the pool that owns the discharge condenses it. Two pool
            // controllers can sit within range of one valve, and without this
            // each would put the whole flow into its own water.
            if (flow > 0.0 && srv.claimCondensation(getBlockPos(), gameTime)) {
                srvKgPerS += flow;
            }
        }

        condense(srvKgPerS, domePressurePsig, gameTime, dt);

        machineRhrDuty = expireAndSumDuties(gameTime);
        double duty = getEffectiveRhrDuty();
        if (duty > 0.0) {
            pool.coolWithRhr(duty, rhrCapacityMW, heatSinkC, dt);
        }

        setChanged();
    }

    /**
     * Condense this tick's steam from every source in a single call.
     *
     * <p>One call, unconditionally, and both of those matter.
     * {@code SuppressionPool} publishes exactly one uncondensed-steam figure and
     * one condensation effectiveness per call, so the turbine-driven pumps'
     * exhaust and the relief valves each used to overwrite the other's reading
     * and the published number was never the total of the two. And guarding the
     * call on {@code steam &gt; 0} meant the zero-flow branch — the one that
     * resets the uncondensed reading — was unreachable from the mod, so after a
     * long discharge the figure latched at its last value forever and a Lua
     * alarm written against it could never be cleared.
     *
     * <p>Sources arrive at different pressures: relief steam at dome pressure,
     * turbine exhaust at containment. {@code condenseSteam} takes one pressure,
     * so a mass-weighted mean is used. Saturated vapour enthalpy only moves from
     * about 2675 to 2778 kJ/kg across that entire span, so the approximation
     * costs under two per cent of the deposited heat in the one case where the
     * two flows are comparable, and nothing at all when either dominates. That
     * is a far smaller error than publishing a steam reading that is not the
     * total.
     */
    private void condense(double srvKgPerS, double domePressurePsig, long gameTime, double dt) {
        double total = Math.max(0.0, srvKgPerS);
        double pressureMoment = total * domePressurePsig;

        Iterator<Map.Entry<BlockPos, SteamReport>> reports = steamReports.entrySet().iterator();
        while (reports.hasNext()) {
            SteamReport r = reports.next().getValue();
            if (gameTime - r.tick() > STALE_TICKS) {
                reports.remove();
                continue;
            }
            total += r.kgPerS();
            pressureMoment += r.kgPerS() * r.pressurePsig();
        }

        double meanPsig = total > 0.0 ? pressureMoment / total : 0.0;
        pool.condenseSteam(total, meanPsig, dt);
        // Steam the pool could not condense goes on to pressurise containment.
        // Containment is not modelled yet (SPEC section 16); the quantity is
        // published so a player can see it and so the model can consume it later.
    }

    // --- Reports from machines discharging into this pool -----------------

    /**
     * Post steam a machine is discharging into this pool, kg/s.
     *
     * <p>A rate, not a quantity, refreshed every tick by the machine and summed
     * once by {@link #condense}. A report that stops being refreshed — the
     * machine broken, or its chunk unloaded — expires on its own, so nothing can
     * leave phantom steam arriving in the water.
     */
    public void reportSteamKgPerS(BlockPos source, long gameTime, double kgPerS,
                                  double pressurePsig) {
        if (source == null) {
            return;
        }
        double flow = Double.isFinite(kgPerS) && kgPerS > 0.0 ? kgPerS : 0.0;
        steamReports.put(source.immutable(),
                new SteamReport(gameTime, flow, Double.isFinite(pressurePsig) ? pressurePsig : 0.0));
    }

    /**
     * Post the pool-cooling duty an RHR loop is supplying, 0..1.
     *
     * <p>Summed and staleness-checked for the same reason the ECCS contributions
     * are. Writing the duty straight in left it latched when the machine stopped
     * ticking, so a pool went on rejecting 30 MW through a heat exchanger the
     * player had already mined — persisted to NBT, and restored on reload.
     * Assignment also lost the second of two loops lined up for pool cooling.
     */
    public void reportRhrDuty(BlockPos source, long gameTime, double duty) {
        if (source == null) {
            return;
        }
        double d = Double.isFinite(duty) ? Math.min(1.0, Math.max(0.0, duty)) : 0.0;
        dutyReports.put(source.immutable(), new DutyReport(gameTime, d));
    }

    /** Drop a machine's duty at once, rather than waiting for it to go stale. */
    public void withdrawRhrDuty(BlockPos source) {
        if (source != null) {
            dutyReports.remove(source);
            machineRhrDuty = 0.0;
        }
    }

    private double expireAndSumDuties(long gameTime) {
        double total = 0.0;
        Iterator<Map.Entry<BlockPos, DutyReport>> it = dutyReports.entrySet().iterator();
        while (it.hasNext()) {
            DutyReport r = it.next().getValue();
            if (gameTime - r.tick() > STALE_TICKS) {
                it.remove();
                continue;
            }
            total += r.duty();
        }
        return total;
    }

    // --- Structure -----------------------------------------------------

    public void markStructureDirty() {
        structureDirty = true;
    }

    /**
     * Rescan the structure when something says it changed, and on an interval
     * regardless.
     *
     * <p>The interval is the fix for a pool that can never notice its own water:
     * blocks placed away from the controller fire no neighbour change here. The
     * floor on the dirty path is the fix for the opposite problem — a redstone
     * clock beside the controller running the full scan twenty times a second.
     */
    private void maybeRevalidate(Level level) {
        if (ticksSinceRevalidate < Integer.MAX_VALUE) {
            ticksSinceRevalidate++;
        }
        int interval;
        if (structureDirty) {
            interval = MIN_REVALIDATE_INTERVAL_TICKS;
        } else if (formed) {
            interval = FORMED_REVALIDATE_INTERVAL_TICKS;
        } else {
            interval = UNFORMED_REVALIDATE_INTERVAL_TICKS;
        }
        if (ticksSinceRevalidate < interval) {
            return;
        }
        revalidate(level);
        structureDirty = false;
        ticksSinceRevalidate = 0;
    }

    private void revalidate(Level level) {
        ValidationResult result = new ValidationResult();
        dischargingValves.clear();
        // Cleared before the scan, like every other binding in the mod. Leaving
        // the old value meant a broken reactor controller left a stale position
        // behind that resolved to nothing, which is the state that used to make
        // the pool fall back to rated dome pressure.
        reactorPos = null;
        waterBlocks = 0;

        BlockPos min = getBlockPos().offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS);
        BlockPos max = getBlockPos().offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);

        // Hardware only in this pass. Water is surveyed separately, by
        // surveyBasin, and deliberately not counted here: this loop used to
        // test the fluid first and reach the valve and controller tests only
        // through an else-if, so any position holding water was never examined
        // for a block at all.
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState found = level.getBlockState(p);
            if (found.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())
                    && level.getBlockEntity(p) instanceof SafetyReliefValveBlockEntity srv) {
                if (srv.revalidateDischarge(level)) {
                    dischargingValves.add(p.immutable());
                } else {
                    result.degrade(p.immutable(),
                            "this relief valve does not discharge underwater, so it suppresses nothing");
                }
            } else if (found.is(BwrBlocks.REACTOR_CONTROLLER.get())) {
                reactorPos = p.immutable();
            }
        }

        Basin basin = surveyBasin(level);
        waterBlocks = basin.waterBlocks();

        if (basin.problem() != null) {
            result.fail(getBlockPos(), basin.problem());
            formed = false;
            lastValidation = result;
            return;
        }

        if (waterBlocks < MIN_WATER_BLOCKS) {
            result.fail(getBlockPos(), "suppression pool needs at least " + MIN_WATER_BLOCKS
                    + " water blocks in one basin, found " + waterBlocks);
            formed = false;
            lastValidation = result;
            return;
        }

        if (dischargingValves.isEmpty()) {
            result.degrade("no relief valves discharge into this pool; it is a heat sink with nothing attached");
        }

        pool.setContainmentPressurePsia(dev.bwr.core.PhysicalConstants.ATMOSPHERIC_PSI);
        // The pool is as large as the basin, and it is re-imposed on every scan
        // so that digging the basin out further, or draining part of it, is felt
        // in the physics. Only the change in capacity moves water, so a survey
        // that finds the same basin costs nothing and a pool drawn down by hours
        // of ECCS suction is not quietly refilled by being looked at.
        pool.resizeToDesignMassKg(structureMassKg(), SuppressionPool.DEFAULT_TEMPERATURE_C);
        formed = true;
        lastValidation = result;
    }

    // --- Finding the basin ------------------------------------------------

    /**
     * What the basin survey found: the size of the pool in water blocks, and
     * the reason there is no pool when there is not one. {@code problem} is
     * null exactly when a basin was found.
     */
    private record Basin(int waterBlocks, String problem) {
    }

    /**
     * Find the body of water this controller is standing at.
     *
     * <p><b>Do not put this back to counting every water block in the search
     * box.</b> That is what it used to do, and it was only ever cosmetic
     * because the pool was hardcoded to a BWR/6 inventory. Now that
     * {@code SuppressionPool} is sized from the count — heat capacity, level
     * fraction and the ECCS suction floor all scale with it — a controller
     * placed near an ocean, a lake or a flooded cave counted tens of thousands
     * of blocks it had nothing to do with and handed the player a heat sink
     * that could absorb a full-power blowdown without warming measurably. The
     * pool has to be the pool the player built.
     *
     * <p>Three things bound it, and each of them is a property of the
     * structure rather than a rule about the plant:
     *
     * <ul>
     *   <li><b>Contiguity.</b> The pool is one connected body of water, walked
     *       face to face from a seed within {@link #SEED_RADIUS} of the
     *       controller. Water on the other side of a wall is a different pool
     *       and belongs to whatever controller is standing at <i>it</i>.</li>
     *   <li><b>Enclosure.</b> If the body reaches past the survey box the
     *       basin has no far wall inside the radius this controller can see,
     *       so it is open water and is refused rather than truncated. A dug
     *       basin is stopped by its own walls and by its free surface long
     *       before the box; the sea is not. This is also what stops the
     *       cheapest cheese there is — cutting a one-block channel from a
     *       small pool to the ocean.</li>
     *   <li><b>Size.</b> {@link #MAX_WATER_BLOCKS}, above which it is not a
     *       basin anyone dug.</li>
     * </ul>
     *
     * <p>Seeds are tried nearest first. That matters for the case this exists
     * to handle well: a legitimate pool built on a shoreline seeds both its own
     * basin and the sea, and the sea being refused must not take the basin down
     * with it.
     *
     * <p><b>Each attempt gets its own set of walked positions, and the ones a
     * refused body walked are never reused as though they were solid.</b> They
     * were, once, to save re-walking the sea — and the result was the worst
     * kind of wrong answer this class can give. The walk that gives up on
     * reaching open water gives up part way, so the positions it happened to
     * visit form an arbitrary blob; the next seed then flood filled the sea
     * <i>bounded by that blob</i>, terminated after a handful of blocks, and
     * reported a perfectly formed three-block suppression pool in the middle of
     * an ocean. What stops the re-walking now is {@code refused}, which only
     * ever skips a seed already known to sit in a refused body, and
     * {@link #SURVEY_WORK_LIMIT}, which bounds the total work outright.
     *
     * <h2>Why the walked positions are held as primitive longs</h2>
     * These two sets used to be {@code HashSet<Long>}, and every position the
     * survey touched was boxed into a {@code Long} on the heap —
     * {@code Long.valueOf} only caches -128..127 and a packed {@code BlockPos}
     * is nowhere near that range, so every single one was a fresh allocation,
     * plus a {@code HashMap.Node} to hold it. {@link #SURVEY_WORK_LIMIT} allows
     * 65,536 walked positions before the survey gives up, and each of them was
     * boxed twice over — once into {@code body} and again when {@code body} was
     * copied into {@code refused} — so a controller sitting beside open water
     * produced several megabytes of immediately-dead objects, and did it again
     * every {@link #UNFORMED_REVALIDATE_INTERVAL_TICKS} for as long as the pool
     * stayed unformed. That is a controller the player set down and walked away
     * from quietly running the garbage collector for them.
     *
     * <p>{@code LongOpenHashSet} stores the packed positions in a primitive
     * array, so the walk allocates nothing per position at all. It is fastutil,
     * which Minecraft already ships and depends on heavily; this adds no
     * dependency. The traversal itself is unchanged — same seeds, same order,
     * same answers.
     */
    private Basin surveyBasin(Level level) {
        BlockPos origin = getBlockPos();
        List<BlockPos> seeds = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                origin.offset(-SEED_RADIUS, -SEED_RADIUS, -SEED_RADIUS),
                origin.offset(SEED_RADIUS, SEED_RADIUS, SEED_RADIUS))) {
            if (isPoolWater(level, p)) {
                seeds.add(p.immutable());
            }
        }
        if (seeds.isEmpty()) {
            return new Basin(0, "no water within " + SEED_RADIUS
                    + " blocks of this controller; a pool controller has to stand at the"
                    + " basin it meters");
        }
        seeds.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));

        LongOpenHashSet refused = new LongOpenHashSet();
        // One body set, cleared between attempts rather than reallocated. The
        // previous attempt's contents have already been folded into `refused`
        // by the bottom of the loop, so clearing is exactly equivalent to a
        // fresh set, and it means a shoreline build that walks the sea several
        // times grows the backing table once instead of once per seed.
        LongOpenHashSet body = new LongOpenHashSet();
        String firstProblem = null;
        int walked = 0;
        for (BlockPos seed : seeds) {
            if (refused.contains(seed.asLong())) {
                continue;
            }
            body.clear();
            Basin basin = fillBasin(level, origin, seed, body);
            if (basin.problem() == null) {
                return basin;
            }
            if (firstProblem == null) {
                firstProblem = basin.problem();
            }
            refused.addAll(body);
            walked += basin.waterBlocks();
            if (walked > SURVEY_WORK_LIMIT) {
                return new Basin(0, tooMuchWaterMessage());
            }
        }
        return new Basin(0, firstProblem);
    }

    /**
     * Walk one connected body of water from {@code seed}, adding every position
     * it visits to {@code body}.
     *
     * <p>Depth first, not breadth first, and that is deliberate: on open water
     * a depth-first walk runs more or less straight at the wall of the survey
     * box and gives up after a few dozen positions, where a breadth-first walk
     * would first enumerate every block within twenty-odd steps of the seed. A
     * genuine basin is walked in full either way.
     *
     * <p>The count in the returned {@link Basin} is the size of the basin when
     * there is no problem, and how far the walk got before giving up when there
     * is. Only {@link #surveyBasin} sees the second kind, and only to charge it
     * against the work limit.
     *
     * <p>The stack holds packed positions rather than {@code BlockPos} objects,
     * for the same reason {@code body} does — see {@link #surveyBasin}. Every
     * position that went on it used to be a {@code next.immutable()} copy, so a
     * full 32,768-block basin allocated 32,768 {@code BlockPos} on top of the
     * boxing. Two mutable cursors do the whole walk now: one for the position
     * being expanded and one for the six neighbour probes.
     */
    private Basin fillBasin(Level level, BlockPos origin, BlockPos seed, LongOpenHashSet body) {
        LongArrayList stack = new LongArrayList();
        stack.push(seed.asLong());
        body.add(seed.asLong());
        int found = 0;
        // One cursor for the position being expanded, one for the six neighbour
        // probes. Most of the probes are not water, and a basin this size would
        // otherwise throw away a couple of hundred thousand BlockPos objects per
        // survey to find that out.
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (!stack.isEmpty()) {
            // LongArrayList.push/popLong append and remove at the end, so this
            // is the same last-in-first-out order ArrayDeque.push/pop gave and
            // the walk stays depth first, which the paragraph above depends on.
            long packed = stack.popLong();
            current.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            found++;
            if (found > MAX_WATER_BLOCKS) {
                return new Basin(found, tooMuchWaterMessage());
            }
            for (Direction d : DIRECTIONS) {
                next.setWithOffset(current, d);
                if (!isPoolWater(level, next)) {
                    continue;
                }
                if (outsideSurvey(origin, next)) {
                    // The body carries on past the far side of the box, so
                    // there is no wall in it that this controller can see.
                    return new Basin(found, "the water at this controller is open to more water"
                            + " than it can survey (it runs past " + SEARCH_RADIUS + " blocks);"
                            + " a suppression pool has to be an enclosed basin, not an ocean,"
                            + " a lake or a flooded cave");
                }
                long neighbour = next.asLong();
                if (body.add(neighbour)) {
                    stack.push(neighbour);
                }
            }
        }
        return new Basin(found, null);
    }

    private static String tooMuchWaterMessage() {
        return "this body of water is larger than the " + MAX_WATER_BLOCKS
                + " blocks one controller surveys; a suppression pool is a basin, not a"
                + " flooded cave system";
    }

    /**
     * Whether a position holds pool water.
     *
     * <p>Source blocks only, which is both what the old count did and what the
     * physics wants: a flowing block is a partial volume and a transient one,
     * and treating a waterfall as pool water would let the survey walk down it
     * into whatever it lands in. A waterlogged block is a source and counts,
     * so a basin faced with slabs or stairs is still a basin.
     */
    private static boolean isPoolWater(Level level, BlockPos pos) {
        return level.getFluidState(pos).getType() == Fluids.WATER;
    }

    /** Whether a position is past the survey box this controller can see. */
    private static boolean outsideSurvey(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) > SEARCH_RADIUS
                || Math.abs(pos.getY() - origin.getY()) > SEARCH_RADIUS
                || Math.abs(pos.getZ() - origin.getZ()) > SEARCH_RADIUS;
    }

    private ReactorControllerBlockEntity reactor(Level level) {
        if (reactorPos == null) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    // --- Measurements and commands --------------------------------------

    public SuppressionPool pool() {
        return pool;
    }

    public boolean isFormed() {
        return formed;
    }

    public int waterBlocks() {
        return waterBlocks;
    }

    /**
     * Water blocks converted to kilograms — the design inventory of the pool the
     * player actually built, and what {@link SuppressionPool} is sized to.
     *
     * <p>"Actually built" is load-bearing and is the whole reason
     * {@link #surveyBasin} walks a connected body instead of counting water in
     * range. This figure sets the pool's heat capacity, so water the player did
     * not put there is thermal mass the plant did not earn.
     *
     * <p>{@code SuppressionPool} measures its suction floor and its level
     * fraction against its own design inventory, so a 64-block minimum pool is a
     * small pool and behaves like one. It used to measure both against the
     * BWR/6 {@code DEFAULT_MASS_KG}, and while it did, sizing the pool from the
     * structure was not possible: any pool under 3400 blocks would have sat
     * permanently below a 170,000 kg suction floor and could never have supplied
     * ECCS at all. That is why this figure was published and then ignored.
     */
    public double structureMassKg() {
        return waterBlocks * KG_PER_WATER_BLOCK;
    }

    public int dischargingValveCount() {
        return dischargingValves.size();
    }

    /** RHR duty commanded directly on this controller, 0..1. */
    public double getRhrDuty() {
        return rhrDuty;
    }

    /**
     * Duty the heat exchangers are actually running at: what the player asked
     * for here, plus what any RHR loop lined up for pool cooling is supplying.
     */
    public double getEffectiveRhrDuty() {
        return Math.min(1.0, Math.max(0.0, rhrDuty + machineRhrDuty));
    }

    /** Commanded by the player. There is no automatic start. */
    public void setRhrDuty(double duty) {
        // Marshalled onto the server thread: Lua calls this from a CC computer
        // thread and setChanged() dispatches neighbour updates. See
        // PlantActuators.
        PlantActuators.run(this, () -> {
            this.rhrDuty = clampFraction(duty);
            setChanged();
        });
    }

    /**
     * A duty as a usable 0..1, with NaN mapped to zero rather than propagated.
     *
     * <p>{@code Math.min(1, Math.max(0, NaN))} is NaN — both comparisons fail
     * and the argument comes straight back out. So clamping is not on its own a
     * guard against a non-finite duty, and this is the one place that says so.
     *
     * <p>{@code SuppressionPoolPeripheral.setRhrDuty} still refuses a non-finite
     * duty with a {@code LuaException} and should go on doing so. This is not a
     * replacement for it: silently taking a NaN as zero would leave a player
     * whose control loop divided by zero staring at a pool that will not cool
     * and no reason why. This is the floor under every other way in — the load
     * path above, and any caller added later.
     */
    private static double clampFraction(double duty) {
        if (!Double.isFinite(duty)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, duty));
    }

    public double getRhrCapacityMW() {
        return rhrCapacityMW;
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (!formed) {
            out.add("Suppression pool not formed.");
            out.addAll(lastValidation.messages());
            return out;
        }
        out.add(String.format("Pool formed: %d water blocks, %d relief valves discharging",
                waterBlocks, dischargingValves.size()));
        // The basin size is physics now, not decoration, so it is on the board:
        // a small pool really does heat faster and lose suction sooner.
        out.add(String.format("%,.0f kg of %,.0f kg design inventory (%.0f%% level), "
                        + "%,.0f kg reachable by suction",
                pool.getMassKg(), pool.getDesignMassKg(), pool.getLevelFraction() * 100.0,
                pool.getAvailableSuctionKg()));
        out.add(String.format("%.2f degC, subcooling %.2f degC, condensing at %.0f%%, %.0f MJ capacity left",
                pool.getTemperatureC(), pool.getSubcoolingC(),
                pool.condensationEffectiveness() * 100.0,
                pool.getRemainingHeatCapacityMJ()));
        double effective = getEffectiveRhrDuty();
        if (effective > 0.0) {
            out.add(String.format("RHR cooling at %.0f%% duty (%.0f%% commanded here, %.0f%% from loops)",
                    effective * 100.0, rhrDuty * 100.0, machineRhrDuty * 100.0));
        }
        if (pool.isBoiling()) {
            out.add("Pool is boiling; steam is passing straight through to containment.");
        }
        if (reactorPos == null) {
            out.add("No reactor controller found within " + SEARCH_RADIUS
                    + " blocks; the relief valves have nothing to relieve.");
        }
        out.addAll(lastValidation.messages());
        return out;
    }

    // --- Persistence -----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        dev.bwr.mod.reactor.ReactorStateNbt.putDoubles(tag, "Pool", pool.toArray());
        // Only the duty the player commanded on this block is saved. Duty
        // supplied by an RHR loop is that machine's to re-report every tick; it
        // was persisting the machine's figure that let a mined pump go on
        // cooling the pool across a save.
        tag.putDouble("RhrDuty", rhrDuty);
        tag.putDouble("RhrCapacityMW", rhrCapacityMW);
        tag.putDouble("HeatSinkC", heatSinkC);
        tag.putInt("WaterBlocks", waterBlocks);
        if (reactorPos != null) {
            tag.putLong("Reactor", reactorPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Pool")) {
            pool.fromArray(dev.bwr.mod.reactor.ReactorStateNbt.getDoubles(tag, "Pool"));
        }
        // Sanitised on the way in, not merely on the way out.
        //
        // The Lua entry point rejects a non-finite duty now, but that only stops
        // new ones being made: a NaN written to disk before that fix reloads as
        // NaN, and NaN fails every comparison it meets. `duty > 0.0` is false, so
        // coolWithRhr is never even called, and the pool silently has no RHR for
        // the rest of that world's life with a status line saying nothing is
        // wrong. Worse, a NaN reaching coolWithRhr would poison pool temperature
        // and from there every subcooling and condensation reading downstream.
        //
        // The same three fields feed the same call, so all three are checked. A
        // heat sink is allowed to be cold, so it is checked for finiteness only;
        // a capacity is a rating and cannot be negative.
        rhrDuty = clampFraction(tag.getDouble("RhrDuty"));
        if (tag.contains("RhrCapacityMW")) {
            double saved = tag.getDouble("RhrCapacityMW");
            rhrCapacityMW = Double.isFinite(saved) && saved >= 0.0 ? saved : rhrCapacityMW;
        }
        if (tag.contains("HeatSinkC")) {
            double saved = tag.getDouble("HeatSinkC");
            heatSinkC = Double.isFinite(saved) ? saved : heatSinkC;
        }
        waterBlocks = tag.getInt("WaterBlocks");
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        structureDirty = true;
    }
}
