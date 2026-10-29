package dev.bwr.mod.piping;

import dev.bwr.mod.power.LubeOilValveBlock;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The lube oil lines (TURBINE-AUX.md): which oil ports a pipe run connects. It is a network of its own: Lube Oil Pipes, Lube Oil Valves
 * and Oil Filter Housings carry the oil; the oil equipment (Main Oil Tank, Shaft-Driven Main Oil Pump, every turbine and generator section)
 * are the ends. Nothing here knows of water or steam: those pipes accept their own blocks only and this accepts none of theirs.
 *
 * <p>Like {@link PipeTopology}: a survey is made from one port when it is first asked for, kept, and thrown away only when a block at or
 * beside the run changes (a capability invalidation, which pipes, valves and the oil equipment raise when placed, removed or toggled) or
 * a chunk of the run becomes available or unavailable. No ticker, no read of an unloaded chunk (a run that crosses one is not connected
 * past it, and the survey is redone when that chunk arrives), no chunk is ever loaded for it. A run of more than {@value #MAX_LINE_BLOCKS}
 * blocks is treated as not connected.
 *
 * <p>Every survey keeps two answers: what the run joins in the pipework (valves taken as open, {@link Survey#topo}) and what oil can
 * actually pass today ({@link Survey#live}, a closed valve cuts the run). The equipment uses the first to decide what is <i>piped</i>
 * and the second to decide what <i>flows</i>, so closing a valve starves what is behind it and never makes some other rule apply.
 */
public final class OilLineNetwork {
    private OilLineNetwork() {}

    public static final int MAX_LINE_BLOCKS = 256;

    /** What an oil port on a piece of equipment is for. A line joins any of them: the equipment reads the roles it expects. */
    public enum Role {
        /** The tank's discharge: what its AC and DC pumps deliver, to the bearing header. */
        TANK_DISCHARGE,
        /** The tank's return inlet, where the bearing drains come back. */
        TANK_RETURN,
        /** The tank's suction outlet, to the shaft-driven pump. */
        TANK_SUCTION,
        /** The tank's fill connection (a drum or a filling line may feed it). */
        TANK_FILL,
        /** The shaft-driven pump's suction inlet. */
        PUMP_SUCTION,
        /** The shaft-driven pump's discharge, to the bearing header. */
        PUMP_DISCHARGE,
        /** A turbine or generator section's bearing supply inlet. */
        SECTION_SUPPLY,
        /** A section's bearing drain outlet. */
        SECTION_DRAIN,
        /** The conditioner's inlet: oil from the tank's suction outlet. */
        COND_INLET,
        /** The conditioner's outlet: cleaned oil back to the tank's fill connection. */
        COND_OUTLET
    }

    private enum Conduit { NONE, PIPE, VALVE, FILTER }

    private static Conduit conduit(BlockState s) {
        var b = s.getBlock();
        if (b instanceof LubeOilPipeBlock) return Conduit.PIPE;
        if (b instanceof LubeOilValveBlock) return Conduit.VALVE;
        if (b instanceof TurbineAuxBlock a && a.kind() == TurbineAuxBlock.Kind.OIL_FILTER) return Conduit.FILTER;
        return Conduit.NONE;
    }

    /** The role of the oil port on {@code face} of a piece of equipment, or null: pipes, valves and housings have none (they are conduits). */
    public static Role roleAt(BlockState s, Direction face) {
        if (face == null) return null;
        if (s.getBlock() instanceof TurbineAuxBlock a) return a.oilPortAt(s, face);
        if (s.getBlock() instanceof PowerModuleBlock m) return m.oilPortAt(s, face);
        return null;
    }

    /** Whether a lube oil line takes oil on {@code face} of this block: the one connection rule, for the pipe's arms and for the survey. */
    public static boolean acceptsLineOn(BlockState s, Direction face) {
        if (face == null) return false;
        return switch (conduit(s)) {
            case PIPE -> true;
            case VALVE -> ((LubeOilValveBlock) s.getBlock()).acceptsOilLineOn(s, face);
            case FILTER -> ((TurbineAuxBlock) s.getBlock()).acceptsOilLineOn(s, face);
            case NONE -> roleAt(s, face) != null;
        };
    }

    /** An oil port a run reached: the equipment's position, the face of it the run met, and what that port is for. */
    public record End(BlockPos pos, Direction face, Role role) {}

    /** What one run joins. {@code topo}: with every valve taken as open; {@code live}: with closed valves cutting the run. */
    public static final class Survey {
        public static final Survey NONE = new Survey(List.of(), List.of(), List.of(), List.of(), false);
        public final List<End> topo, live;
        /** The filter housings the run enters by their inlet face, nearest first (the first is the one that filters what this port sends). */
        public final List<BlockPos> filters;
        /** The valves of the run (open or not). */
        public final List<BlockPos> valves;
        public final boolean truncated;

        Survey(List<End> topo, List<End> live, List<BlockPos> filters, List<BlockPos> valves, boolean truncated) {
            this.topo = topo; this.live = live; this.filters = filters; this.valves = valves; this.truncated = truncated;
        }

        public boolean joins(Role role) {
            for (End e : topo) if (e.role == role) return true;
            return false;
        }

        public boolean reaches(Role role) {
            for (End e : live) if (e.role == role) return true;
            return false;
        }

        public boolean reaches(Role role, BlockPos at) {
            for (End e : live) if (e.role == role && e.pos.equals(at)) return true;
            return false;
        }
    }

    // ---- the cache

    private record Key(BlockPos pos, Direction face) {}

    private static final class Entry {
        boolean dirty;
        Survey survey;
        /** The filter housings of the run in series and parallel, built the first time something asks (most runs never do). */
        FilterGraph graph;
        final Set<Long> chunks = new HashSet<>();
        // NeoForge keeps listeners weakly: one strong reference while the entry lives.
        final ICapabilityInvalidationListener listener = () -> { dirty = true; return false; };
    }

    private static final Map<Level, LinkedHashMap<Key, Entry>> CACHE = new WeakHashMap<>();
    private static long builds;

    /** How many surveys have been built (tests: an unchanged run must not be surveyed again). */
    public static long buildCount() { return builds; }

    /** The survey held for a port, or null; never builds and never reads the world (tests and diagnostics). */
    public static Survey cached(Level level, BlockPos pos, Direction face) {
        var cache = CACHE.get(level);
        var entry = cache == null ? null : cache.get(new Key(pos.immutable(), face));
        return entry == null || entry.dirty ? null : entry.survey;
    }

    /** A structural change at {@code pos} (a pipe, valve or machine placed, removed or toggled): the surveys that ran by it are redone. */
    public static void changed(Level level, BlockPos pos) { PipeTopology.changed(level, pos); }

    static void availabilityChanged(Level level, ChunkPos chunk) {
        var cache = CACHE.get(level);
        if (cache == null) return;
        for (var entry : cache.values()) if (entry.chunks.contains(chunk.toLong())) entry.dirty = true;
    }

    static void unloaded(Level level) {
        var gone = CACHE.remove(level);
        if (gone != null) gone.values().forEach(e -> e.dirty = true);
    }

    /**
     * What the lube oil run leaving {@code face} of the equipment at {@code pos} joins. {@link Survey#NONE} when nothing connects there
     * (the usual case of a train that is not piped, answered with one block read and no entry kept).
     */
    public static Survey survey(Level level, BlockPos pos, Direction face) {
        if (level == null || level.isClientSide()) return Survey.NONE;
        var first = LoadedWorld.state(level, pos.relative(face));
        if (first == null || !acceptsLineOn(first, face.getOpposite())) return Survey.NONE;
        var key = new Key(pos.immutable(), face);
        var cache = CACHE.computeIfAbsent(level, l -> new LinkedHashMap<>(32, .75f, true));
        var entry = cache.get(key);
        if (entry != null && !entry.dirty) return entry.survey;
        entry = new Entry();
        entry.survey = build(level, key, entry);
        builds++;
        var replaced = cache.put(key, entry);
        if (replaced != null) replaced.dirty = true;
        if (cache.size() > 512) {
            var eldest = cache.keySet().iterator().next();
            cache.remove(eldest).dirty = true;
        }
        return entry.survey;
    }

    private static void watch(Level level, BlockPos pos, Entry entry, Set<BlockPos> watched) {
        entry.chunks.add(ChunkPos.asLong(pos));
        if (watched.add(pos.immutable()) && level instanceof ServerLevel server) server.registerCapabilityListener(pos, entry.listener);
    }

    private record Step(BlockPos pos, Direction entered) {}

    private static Survey build(Level level, Key key, Entry entry) {
        Set<BlockPos> watched = new HashSet<>();
        watch(level, key.pos, entry, watched);
        List<BlockPos> filters = new ArrayList<>();
        List<BlockPos> valves = new ArrayList<>();
        boolean[] truncated = {false};
        List<End> topo = walk(level, key, entry, watched, false, filters, valves, truncated);
        List<End> live = walk(level, key, entry, watched, true, new ArrayList<>(), new ArrayList<>(), truncated);
        if (truncated[0]) return new Survey(List.of(), List.of(), List.of(), List.copyOf(valves), true);
        return new Survey(List.copyOf(topo), List.copyOf(live), List.copyOf(filters), List.copyOf(valves), false);
    }

    private static List<End> walk(Level level, Key key, Entry entry, Set<BlockPos> watched, boolean respectValves,
                                  List<BlockPos> filters, List<BlockPos> valves, boolean[] truncated) {
        List<End> ends = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        var queue = new ArrayDeque<Step>();
        BlockPos firstCell = key.pos.relative(key.face);
        watch(level, firstCell, entry, watched);
        queue.add(new Step(firstCell, key.face.getOpposite()));
        while (!queue.isEmpty()) {
            var step = queue.remove();
            // Only a chunk that is completely available is read; a run that reaches one that is not stops there.
            BlockState s = LoadedWorld.state(level, step.pos);
            if (s == null || !acceptsLineOn(s, step.entered)) continue;
            switch (conduit(s)) {
                case PIPE -> {
                    if (!visited.add(step.pos)) continue;
                    if (visited.size() > MAX_LINE_BLOCKS) { truncated[0] = true; return ends; }
                    for (Direction d : Direction.values()) {
                        BlockPos next = step.pos.relative(d);
                        watch(level, next, entry, watched);
                        if (!next.equals(key.pos) || d.getOpposite() != key.face) queue.add(new Step(next, d.getOpposite()));
                    }
                }
                case VALVE -> {
                    if (!visited.add(step.pos)) continue;
                    if (visited.size() > MAX_LINE_BLOCKS) { truncated[0] = true; return ends; }
                    if (!respectValves) valves.add(step.pos.immutable());
                    if (respectValves && !s.getValue(LubeOilValveBlock.OPEN)) continue;
                    Direction out = step.entered.getOpposite();
                    BlockPos next = step.pos.relative(out);
                    watch(level, next, entry, watched);
                    queue.add(new Step(next, step.entered));
                }
                case FILTER -> {
                    if (!visited.add(step.pos)) continue;
                    if (visited.size() > MAX_LINE_BLOCKS) { truncated[0] = true; return ends; }
                    var housing = (TurbineAuxBlock) s.getBlock();
                    if (step.entered == housing.oilInFace(s) && !respectValves) filters.add(step.pos.immutable());
                    Direction out = step.entered.getOpposite();
                    BlockPos next = step.pos.relative(out);
                    watch(level, next, entry, watched);
                    queue.add(new Step(next, step.entered));
                }
                case NONE -> {
                    Role role = roleAt(s, step.entered);
                    if (role == null) continue;
                    if (step.pos.equals(key.pos) && step.entered == key.face) continue; // the port the run started from
                    var end = new End(step.pos.immutable(), step.entered, role);
                    if (!ends.contains(end)) ends.add(end);
                }
            }
        }
        return ends;
    }

    // ---- staged filtration: the filter housings on a tank's return line, in series and in parallel

    /** One housing in a run: the oil goes through it from the junction {@code from} (its inlet side, the far side from the tank) to {@code to} (its outlet side). */
    public record FilterEdge(BlockPos housing, int from, int to) {}

    /**
     * The topology of the Lube Oil Pipe run that leaves a tank's <b>return</b> port, for its filter housings (kept with the survey,
     * invalidated with it). Filters belong on the return line: every pump's oil passes the bearings and drains back to the tank, so
     * every drop is filtered there. The pipes and open valves of the run form junctions; each housing entered by its <b>outlet</b> face from
     * the tank's side is an edge from the junction at its inlet (the sections' side) to the one at its outlet (the tank's side); the junctions
     * that touch a bearing drain are the <b>sources</b> (a source appears once for every drain port that touches it), the junction the run
     * starts in is the <b>sink</b>. Housings in a row are in series; branches that leave a junction and rejoin are in parallel. A housing that
     * is entered by its inlet face from the tank's side is installed backwards: a plain conduit that filters nothing. What each housing
     * holds is read live ({@link #evaluate}).
     */
    public static final class FilterGraph {
        public static final FilterGraph NONE = new FilterGraph(0, List.of(), List.of(), 0);
        public final int comps;
        public final List<FilterEdge> edges;
        public final List<Integer> sources;
        public final int sink;

        FilterGraph(int comps, List<FilterEdge> edges, List<Integer> sources, int sink) {
            this.comps = comps; this.edges = edges; this.sources = sources; this.sink = sink;
        }
    }

    /**
     * What one housing is, for the evaluation: whether a cartridge in service filters, the flow it passes (in units of a train's rated
     * bearing flow), what it lets of the large and the fine particles and the share of the oil's loss that remains behind it.
     */
    public record Stage(boolean effective, double capacity, double passLarge, double passFine, double lossFactor) {
        public static final Stage OPEN = new Stage(false, Double.POSITIVE_INFINITY, 1, 1, 1);
    }

    /**
     * The answer of a filter graph: the flow its housings let through (in units of a train's rated bearing flow), the share of the large and
     * fine particles that gets past them, the share of the oil's loss that remains, how many housings filter, and for each housing what
     * reaches it: {the large share, the fine share, the share of the run's flow through it}.
     */
    public record FilterFlow(double capacity, double largePass, double finePass, double lossFactor, int effective, Map<BlockPos, double[]> inflow) {
        public static final FilterFlow NONE = new FilterFlow(Double.POSITIVE_INFINITY, 1, 1, 1, 0, Map.of());
    }

    /** A run of one housing and nothing else: what it does is what the run does. */
    public static FilterFlow single(BlockPos housing, Stage stage) {
        return new FilterFlow(stage.effective() ? stage.capacity() : Double.POSITIVE_INFINITY, stage.passLarge(), stage.passFine(), stage.lossFactor(),
                stage.effective() ? 1 : 0, Map.of(housing.immutable(), new double[] {1, 1, 1}));
    }

    /** The run {@code rest} (NONE: nothing before it) followed, nearest the tank, by a housing bolted on the tank. */
    public static FilterFlow thenBolted(FilterFlow rest, BlockPos housing, Stage last) {
        if (rest.inflow().isEmpty()) return single(housing, last);
        Map<BlockPos, double[]> inflow = new HashMap<>(rest.inflow());
        inflow.put(housing.immutable(), new double[] {rest.largePass(), rest.finePass(), 1});
        return new FilterFlow(Math.min(rest.capacity(), last.effective() ? last.capacity() : Double.POSITIVE_INFINITY), rest.largePass() * last.passLarge(),
                rest.finePass() * last.passFine(), Math.min(rest.lossFactor(), last.lossFactor()), rest.effective() + (last.effective() ? 1 : 0), inflow);
    }

    private static long graphBuilds;

    /** How many filter graphs have been built (tests: an unchanged run must not be walked again). */
    public static long graphBuildCount() { return graphBuilds; }

    /** The filter graph of the run that leaves the return port on {@code face} of the tank at {@code pos}: {@link FilterGraph#NONE} when nothing is piped there. Cached with the survey. */
    public static FilterGraph filterGraph(Level level, BlockPos pos, Direction face) {
        if (level == null || level.isClientSide()) return FilterGraph.NONE;
        survey(level, pos, face);
        var cache = CACHE.get(level);
        var key = new Key(pos.immutable(), face);
        var entry = cache == null ? null : cache.get(key);
        if (entry == null || entry.dirty) return FilterGraph.NONE;
        if (entry.graph == null) { entry.graph = buildGraph(level, key); graphBuilds++; }
        return entry.graph;
    }

    private static FilterGraph buildGraph(Level level, Key key) {
        BlockPos first = key.pos.relative(key.face);
        Direction firstEntered = key.face.getOpposite();
        var firstState = LoadedWorld.state(level, first);
        if (firstState == null || !acceptsLineOn(firstState, firstEntered)) return FilterGraph.NONE;
        var g = new GraphBuild();
        int start = g.compFor(level, first, firstEntered);
        int guard = 0;
        while (!g.queue.isEmpty() && guard++ < 512) {
            var job = g.queue.poll();
            int comp = (Integer) job[0];
            BlockPos cell = (BlockPos) job[1];
            Direction entered = (Direction) job[2];
            var s = LoadedWorld.state(level, cell);
            if (s == null) continue;
            switch (conduit(s)) {
                case PIPE, VALVE -> {
                    // Flood the junction: pipes and open valves joined to this one (a cell another junction already holds joins the two).
                    var stack = new ArrayDeque<BlockPos>();
                    stack.add(cell);
                    while (!stack.isEmpty() && g.pipeComp.size() <= MAX_LINE_BLOCKS) {
                        BlockPos p = stack.poll();
                        var ps = LoadedWorld.state(level, p);
                        if (ps == null) continue;
                        for (Direction d : Direction.values()) {
                            if (conduit(ps) == Conduit.VALVE && d.getAxis() != ps.getValue(LubeOilValveBlock.FACING).getAxis()) continue;
                            BlockPos n = p.relative(d);
                            var ns = LoadedWorld.state(level, n);
                            if (ns == null || !acceptsLineOn(ns, d.getOpposite())) continue;
                            switch (conduit(ns)) {
                                case PIPE -> g.join(n, comp, stack);
                                case VALVE -> { if (ns.getValue(LubeOilValveBlock.OPEN)) g.join(n, comp, stack); }
                                case FILTER -> g.housing(level, ns, n, d.getOpposite(), comp, stack);
                                case NONE -> { if (!n.equals(key.pos) && roleAt(ns, d.getOpposite()) == Role.SECTION_DRAIN) g.sources.add(comp); }
                            }
                        }
                    }
                }
                case FILTER -> g.housing(level, s, cell, entered, comp, null);
                case NONE -> { if (!cell.equals(key.pos) && roleAt(s, entered) == Role.SECTION_DRAIN) g.sources.add(comp); }
            }
        }
        // The junctions that turned out to be one (two branches that rejoin) get one number each.
        Map<Integer, Integer> number = new HashMap<>();
        int sink = number.computeIfAbsent(g.find(start), k -> number.size());
        List<FilterEdge> edges = new ArrayList<>();
        for (int[] e : g.edges) {
            int from = number.computeIfAbsent(g.find(e[1]), k -> number.size());
            int to = number.computeIfAbsent(g.find(e[2]), k -> number.size());
            edges.add(new FilterEdge(g.housings.get(e[0]), from, to));
        }
        List<Integer> sources = new ArrayList<>();
        for (int c : g.sources) sources.add(number.computeIfAbsent(g.find(c), k -> number.size()));
        return new FilterGraph(number.size(), List.copyOf(edges), List.copyOf(sources), sink);
    }

    /** The working state of one graph walk: junctions (pipes and open valves joined together, other cells on their own) with a union-find to merge the ones that meet. */
    private static final class GraphBuild {
        final Map<BlockPos, Integer> pipeComp = new HashMap<>(), virtual = new HashMap<>();
        final List<int[]> edges = new ArrayList<>();
        final List<BlockPos> housings = new ArrayList<>();
        final List<Integer> sources = new ArrayList<>();
        final Set<BlockPos> seenHousings = new HashSet<>();
        final ArrayDeque<Object[]> queue = new ArrayDeque<>();   // {junction, start cell, entered face}
        final List<Integer> parent = new ArrayList<>();

        int find(int i) {
            while (parent.get(i) != i) { parent.set(i, parent.get(parent.get(i))); i = parent.get(i); }
            return i;
        }

        /** A pipe or open valve at {@code n} joins the junction {@code comp}: new to it, it is flooded on; held by another junction, the two are one. */
        void join(BlockPos n, int comp, ArrayDeque<BlockPos> stack) {
            Integer held = pipeComp.putIfAbsent(n.immutable(), comp);
            if (held == null) { if (stack != null) stack.add(n); }
            else if (find(held) != find(comp)) parent.set(find(held), find(comp));
        }

        /**
         * A housing met on the face {@code touched} from the junction {@code comp} (the tank's side). Entered by its <b>outlet</b> it is a
         * stage, an edge from the junction at its inlet; entered by its inlet it is installed backwards and the pipe beyond its outlet is
         * part of this junction (unless it was already taken as a stage: then this is just its far end).
         */
        void housing(Level level, BlockState hs, BlockPos pos, Direction touched, int comp, ArrayDeque<BlockPos> stack) {
            var housing = (TurbineAuxBlock) hs.getBlock();
            Direction in = housing.oilInFace(hs), out = housing.oilOutFace(hs);
            if (touched == out) {
                if (seenHousings.add(pos.immutable())) {
                    int from = compFor(level, pos.relative(in), out);
                    edges.add(new int[] {housings.size(), from, comp});
                    housings.add(pos.immutable());
                }
            } else if (touched == in && stack != null && seenHousings.add(pos.immutable())) {
                BlockPos beyond = pos.relative(out);
                var bs = LoadedWorld.state(level, beyond);
                if (bs != null && acceptsLineOn(bs, in)) {
                    Conduit bc = conduit(bs);
                    if (bc == Conduit.PIPE || bc == Conduit.VALVE && bs.getValue(LubeOilValveBlock.OPEN)) join(beyond, comp, stack);
                }
            }
        }

        /** The junction a cell belongs to, made (and queued to be flooded) when it is new: pipes and open valves share junctions; anything else is its own point. */
        int compFor(Level level, BlockPos cell, Direction entered) {
            Integer known = pipeComp.get(cell);
            if (known != null) return known;
            var s = LoadedWorld.state(level, cell);
            boolean pipe = s != null && (conduit(s) == Conduit.PIPE || conduit(s) == Conduit.VALVE && s.getValue(LubeOilValveBlock.OPEN));
            known = pipe ? null : virtual.get(cell);
            if (known != null) return known;
            int id = parent.size();
            parent.add(id);
            (pipe ? pipeComp : virtual).put(cell.immutable(), id);
            queue.add(new Object[] {id, cell.immutable(), entered});
            return id;
        }
    }

    /**
     * What the housings of a graph do now: {@code stages} says, per housing, whether it filters and what it passes. The flow a run carries is
     * the most that can go from the drains to the tank (a max-flow): the sum over parallel branches, limited by the tightest housing in each
     * branch in series. The oil goes the way the housings point (from the sections' drains toward the tank), so the particles that get past
     * are the flow-weighted share over the branches, the product of the housings along each; the share of the oil's loss that remains is
     * likewise the mean over the branches of the best (lowest) housing along each. A housing that is not filtering (no cartridge, a full
     * one) passes everything. With no housing in the run the answer is {@link FilterFlow#NONE}.
     */
    public static FilterFlow evaluate(FilterGraph graph, java.util.function.Function<BlockPos, Stage> stages) {
        if (graph.edges.isEmpty() || graph.sources.isEmpty()) return FilterFlow.NONE;
        int n = graph.comps, m = graph.edges.size();
        double big = 1e9;
        double[] cap = new double[m];
        Stage[] st = new Stage[m];
        int effective = 0;
        for (int i = 0; i < m; i++) {
            st[i] = stages.apply(graph.edges.get(i).housing());
            if (st[i].effective()) effective++;
            cap[i] = Double.isInfinite(st[i].capacity()) ? big : st[i].capacity();
        }
        // Max flow, drains to the tank (a super source feeds every drain junction), Edmonds-Karp with residuals on the few junctions.
        int superSource = n, tank = graph.sink;
        double[][] res = new double[n + 1][n + 1];
        for (int i = 0; i < m; i++) { var e = graph.edges.get(i); res[e.from()][e.to()] += cap[i]; }
        for (int s : graph.sources) res[superSource][s] = big;
        double total = 0;
        for (int iter = 0; iter < 200; iter++) {
            int[] prev = new int[n + 1];
            java.util.Arrays.fill(prev, -1);
            prev[superSource] = superSource;
            var q = new ArrayDeque<Integer>();
            q.add(superSource);
            while (!q.isEmpty() && prev[tank] < 0) {
                int u = q.poll();
                for (int v = 0; v <= n; v++) if (prev[v] < 0 && res[u][v] > 1e-12) { prev[v] = u; q.add(v); }
            }
            if (prev[tank] < 0) break;
            double push = big;
            for (int v = tank; v != superSource; v = prev[v]) push = Math.min(push, res[prev[v]][v]);
            for (int v = tank; v != superSource; v = prev[v]) { res[prev[v]][v] -= push; res[v][prev[v]] += push; }
            total += push;
        }
        double capacity = total >= big * 0.5 ? Double.POSITIVE_INFINITY : total;
        // Particles, junction by junction in the order the oil reaches them (an edge splits by what it can pass: a branch that passes more
        // takes more); a junction takes the flow-weighted mean of what arrives (the first arrival exactly, so a lone housing is exact).
        boolean[] reached = new boolean[n];
        var bfs = new ArrayDeque<Integer>();
        double[] share = new double[n];
        for (int s : graph.sources) { share[s] += 1.0 / graph.sources.size(); if (!reached[s]) { reached[s] = true; bfs.add(s); } }
        while (!bfs.isEmpty()) {
            int u = bfs.poll();
            for (var e : graph.edges) if (e.from() == u && !reached[e.to()]) { reached[e.to()] = true; bfs.add(e.to()); }
        }
        int[] indeg = new int[n];
        for (var e : graph.edges) if (reached[e.from()]) indeg[e.to()]++;
        double[] outL = new double[n], outF = new double[n], outLoss = new double[n], outW = new double[n], sumOut = new double[n];
        java.util.Arrays.fill(outL, 1); java.util.Arrays.fill(outF, 1); java.util.Arrays.fill(outLoss, 1);
        for (int i = 0; i < m; i++) if (reached[graph.edges.get(i).from()]) sumOut[graph.edges.get(i).from()] += Math.min(cap[i], 1.5);
        Map<BlockPos, double[]> inflow = new HashMap<>();
        var topo = new ArrayDeque<Integer>();
        boolean[] done = new boolean[n];
        for (int s : new java.util.TreeSet<>(graph.sources)) if (indeg[s] == 0 && !done[s]) { done[s] = true; topo.add(s); }
        if (topo.isEmpty()) for (int s : graph.sources) { done[s] = true; topo.add(s); }   // a loop through every drain: take them as they are
        boolean[] first = new boolean[n];
        java.util.Arrays.fill(first, true);
        while (!topo.isEmpty()) {
            int u = topo.poll();
            for (int i = 0; i < m; i++) {
                var e = graph.edges.get(i);
                if (e.from() != u) continue;
                double w = Math.min(cap[i], 1.5);
                int to = e.to();
                double through = sumOut[u] > 0 ? share[u] * w / sumOut[u] : 0;
                inflow.put(e.housing(), new double[] {outL[u], outF[u], through});
                share[to] += through;
                double l = outL[u] * st[i].passLarge(), f = outF[u] * st[i].passFine(), loss = Math.min(outLoss[u], st[i].lossFactor());
                double newW = outW[to] + w;
                if (first[to]) { outL[to] = l; outF[to] = f; outLoss[to] = loss; first[to] = false; }
                else if (newW > 0) {
                    double r = w / newW;
                    outL[to] += (l - outL[to]) * r;
                    outF[to] += (f - outF[to]) * r;
                    outLoss[to] += (loss - outLoss[to]) * r;
                }
                outW[to] = newW;
                if (--indeg[to] == 0 && !done[to]) { done[to] = true; topo.add(to); }
            }
        }
        if (!reached[tank] || first[tank]) return new FilterFlow(capacity, 1, 1, 1, effective, inflow);
        return new FilterFlow(capacity, outL[tank], outF[tank], outLoss[tank], effective, inflow);
    }
}
