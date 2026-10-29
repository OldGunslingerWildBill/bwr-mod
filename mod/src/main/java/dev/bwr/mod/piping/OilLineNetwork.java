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
}
