package dev.bwr.mod.steam;

import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What is actually joined to what along the main steam line.
 *
 * <h2>Why this had to exist</h2>
 * The RPV steam nozzle and the turbine steam outlet were two <b>parallel</b>
 * paths off one vessel rather than one path in series. The nozzle sat in the
 * shell and removed steam through {@code ReactorCore.setSteamLeakKgPerS}; the
 * turbine outlet found a controller by looking in a 25-block cube around itself
 * and removed steam again through {@code setTurbineSteamFlowKgPerS}. A player
 * who built both got steam leaving the vessel twice, by two independent routes,
 * with no relationship between them and no pipe required between them either —
 * an outlet parked in the turbine hall with nothing but air between it and the
 * reactor drew just as hard as one welded to a nozzle.
 *
 * <p>Physically there is one path: the nozzle is the vessel penetration, the
 * line runs from it, the isolation valves are in that line, and the turbine
 * outlet is the far end of it. Putting them in series needs one thing that did
 * not exist until {@link PressurisedTubeBlock} grew connection logic and the
 * {@code #bwr:steam_line} tag was written — a way to ask <i>what is this pipe
 * actually connected to</i>. That is this class, and nothing else.
 *
 * <h2>It answers geometry, and only geometry</h2>
 * A survey reports which nozzles, which isolation valves and which turbine
 * outlets are reachable from a starting block by following joined steam
 * hardware. It says nothing about whether steam should flow, whether the plant
 * is in a fit state to flow it, or whether anything ought to be opened or shut.
 * Every one of those is the player's judgement. The rule it walks by is exactly
 * the rule {@link PressurisedTubeBlock#connectsTo} renders an arm for, so the
 * connectivity the physics uses and the connectivity the player can see are the
 * same connectivity — a line that looks joined is joined, and one that looks
 * broken is broken.
 *
 * <h2>Nozzles, turbine outlets and quenchers are ends of a line</h2>
 * The walk records them and does not continue through them. A nozzle is a hole
 * in the pressure vessel, so routing <i>through</i> one would let a line pass in
 * at one penetration and out at another and make two separate steam systems read
 * as one; a turbine outlet is where the mod hands steam to Mekanism, which is
 * the end of our side of it; a quencher is submerged in the pool with its holes
 * open to the water, which is where a discharge line stops being a pipe. Real
 * plants do cross-tie their main steam lines, and a player who wants that runs
 * the tube around the outside — which this walk follows perfectly well, because
 * tube is a conduit and a cross-tie is made of tube.
 *
 * <p>Relief valves are the opposite case and are recorded <i>without</i> ending
 * the walk. A safety/relief valve is mounted in the line and is a piece of it,
 * so a discharge line with two valves teed into it is one line; stopping at the
 * first would hide the second from the quencher it discharges through.
 *
 * <h2>Bounded, and it never loads a chunk</h2>
 * {@link #MAX_LINE_BLOCKS} caps the walk, so a player who builds a tube maze
 * cannot make a survey expensive; the truncation is reported rather than
 * hidden. Positions in unloaded chunks are skipped instead of read, because
 * {@code Level.getBlockState} will happily generate terrain to answer a question
 * about a pipe.
 */
public final class SteamLineNetwork {

    /**
     * How many pieces of line one survey will walk before it stops.
     *
     * <p>256 is a very long main steam line — a BWR/6 run from the vessel through
     * the drywell penetration to the turbine hall is a couple of dozen blocks —
     * and it bounds the cost at roughly fifteen hundred block lookups for the
     * pathological case. Surveys run on a multi-second timer, never per tick, so
     * even the worst case is not a per-tick cost. A truncated survey is still a
     * usable answer: it found everything within 256 blocks of line, which is
     * everything that matters on any plant anybody will build.
     */
    public static final int MAX_LINE_BLOCKS = 256;

    /** Nothing found. Shared, because it is immutable and surveys often fail. */
    private static final Survey EMPTY = new Survey(
            List.of(), List.of(), List.of(), List.of(), List.of(), 0, false);

    private SteamLineNetwork() {
    }

    /**
     * What one walk of the steam line found.
     *
     * @param nozzles         RPV steam nozzles reachable along the line
     * @param isolationValves MSIVs in the line itself, not merely nearby
     * @param reliefValves    safety/relief valves in the line itself
     * @param turbineOutlets  turbine steam outlets reachable along the line
     * @param quenchers       suppression pool quenchers the line terminates in
     * @param lineBlocks      pieces of line walked through, excluding the ends
     * @param truncated       true when {@link #MAX_LINE_BLOCKS} stopped the walk
     */
    public record Survey(List<BlockPos> nozzles,
                         List<BlockPos> isolationValves,
                         List<BlockPos> reliefValves,
                         List<BlockPos> turbineOutlets,
                         List<BlockPos> quenchers,
                         int lineBlocks,
                         boolean truncated) {
    }

    /**
     * Walk the steam line outward from {@code start} and report what is on it.
     *
     * <p>{@code start} is not itself reported, whatever it is — an outlet
     * surveying its own line does not want to find itself, and a nozzle does not
     * want to be told it is welded to itself.
     */
    public static Survey survey(Level level, BlockPos start) {
        if (level == null || start == null) {
            return EMPTY;
        }
        BlockPos origin = start.immutable();
        if (!level.isLoaded(origin)) {
            return EMPTY;
        }

        List<BlockPos> nozzles = new ArrayList<>();
        List<BlockPos> valves = new ArrayList<>();
        List<BlockPos> reliefValves = new ArrayList<>();
        List<BlockPos> outlets = new ArrayList<>();
        List<BlockPos> quenchers = new ArrayList<>();
        int lineBlocks = 0;
        boolean truncated = false;

        Set<BlockPos> seen = new HashSet<>();
        seen.add(origin);
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(origin);

        while (!frontier.isEmpty()) {
            BlockPos here = frontier.poll();
            BlockState hereState = level.getBlockState(here);
            for (Direction d : Direction.values()) {
                // relative() on an immutable BlockPos allocates an immutable one,
                // so every position that goes into a set or a result list here is
                // already a copy nobody else is walking. There is no cursor in
                // this method and there must not be one: the lists outlive it.
                BlockPos next = here.relative(d);
                if (seen.contains(next) || !level.isLoaded(next)) {
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                if (!joined(hereState, nextState, d)) {
                    // Not marked seen. A block that refuses the line on one of
                    // its faces may accept it on another, and marking it here
                    // would make whichever face the walk happened to try first
                    // decide the answer for all six. Nothing overrides
                    // acceptsSteamLineOn today, so the two orderings agree today
                    // — but the interface exists precisely so that something can,
                    // and this is the loop that would quietly be wrong when it
                    // does. The re-test costs one block lookup per rejected face.
                    continue;
                }
                seen.add(next);
                if (nextState.getBlock() instanceof dev.bwr.mod.eccs.ProcessAssembly) {
                    continue; // each machine port terminates its own circuit
                }
                if (isVesselNozzle(nextState)) {
                    nozzles.add(next);
                    continue; // an end of the line, not a way through it
                }
                if (isTurbineOutlet(nextState)) {
                    outlets.add(next);
                    continue; // likewise
                }
                if (isQuencher(nextState)) {
                    quenchers.add(next);
                    continue; // likewise: the line ends in the water
                }
                lineBlocks++;
                if (nextState.is(BwrBlocks.MSIV.get())) {
                    valves.add(next);
                }
                if (nextState.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())) {
                    reliefValves.add(next);
                }
                if (lineBlocks >= MAX_LINE_BLOCKS) {
                    truncated = true;
                    frontier.clear();
                    break;
                }
                frontier.add(next);
            }
        }

        return new Survey(Collections.unmodifiableList(nozzles),
                Collections.unmodifiableList(valves),
                Collections.unmodifiableList(reliefValves),
                Collections.unmodifiableList(outlets),
                Collections.unmodifiableList(quenchers),
                lineBlocks, truncated);
    }

    /**
     * True when a piece of steam line — anything but another vessel penetration
     * — sits on one of the six faces of {@code pos}.
     *
     * <p>The cheap question, for callers that only want to know whether there is
     * a pipe on the flange rather than where it goes. A second nozzle does not
     * count: two penetrations bolted face to face are two holes in the vessel
     * wall, not a steam line, and treating them as one would let a player pass
     * rated flow out of a vessel with no pipework at all.
     */
    public static boolean isLineWeldedTo(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        for (Direction d : Direction.values()) {
            BlockPos neighbour = pos.relative(d);
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            BlockState neighbourState = level.getBlockState(neighbour);
            if (isVesselNozzle(neighbourState)) {
                continue;
            }
            if (joined(state, neighbourState, d)) {
                return true;
            }
        }
        return false;
    }

    /** An RPV main steam nozzle: the vessel penetration, and an end of a line. */
    private static boolean isVesselNozzle(BlockState state) {
        return state.is(BwrBlocks.RPV_STEAM_OUTLET.get());
    }

    /** The Mekanism boundary: the far end of a line. */
    private static boolean isTurbineOutlet(BlockState state) {
        return state.is(BwrBlocks.TURBINE_STEAM_OUTLET.get());
    }

    /** A suppression pool quencher: the far end of a relief discharge line. */
    private static boolean isQuencher(BlockState state) {
        return state.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get());
    }

    /**
     * Whether two adjacent blocks are joined as steam line, {@code towards}
     * pointing from the first to the second.
     *
     * <p>Both ends have to agree, which is what makes the answer symmetric and
     * therefore what makes the walk the same set of blocks whichever end it
     * starts from. It is also exactly the test {@link PressurisedTubeBlock} draws
     * an arm for, so the line the physics walks is the line the player can see.
     */
    private static boolean joined(BlockState from, BlockState to, Direction towards) {
        return acceptsLineOn(from, towards) && acceptsLineOn(to, towards.getOpposite());
    }

    /**
     * Whether {@code state} reads as steam hardware on the given face of itself.
     *
     * <p>Interface first, tag second, for the reason
     * {@link PressurisedTubeBlock#connectsTo} gives: a block that has bothered to
     * model a nozzle knows more about its own faces than a list in a datapack
     * does, and a tag cannot express "this face only" at all.
     */
    public static boolean acceptsLineOn(BlockState state, Direction face) {
        if (state.getBlock() instanceof dev.bwr.mod.eccs.ProcessAssembly assembly) {
            var port = assembly.portAt(state, face);
            return port != null && port.isSteam();
        }
        if (state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()) || state.is(BwrBlocks.RECIRCULATION_PUMP.get())
                || state.is(BwrBlocks.REACTOR_CONTROLLER.get()) || dev.bwr.mod.eccs.AssemblyPlumbing.isWaterEndpoint(state)) return false;
        if (state.getBlock() instanceof SteamLinePort port) {
            return port.acceptsSteamLineOn(state, face);
        }
        return state.is(PressurisedTubeBlock.STEAM_LINE);
    }
}
