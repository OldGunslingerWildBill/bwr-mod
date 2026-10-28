package dev.bwr.mod.floodlight;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a beam lamp (xenon floodlight, xenon high-bay, HPS flat panel) puts its light: vanilla light blocks on the surfaces
 * its beam lands on. Pure geometry; the world is read through {@link World}, which never loads a chunk, so the same planner
 * runs in the server and in the tests.
 *
 * <p>The beam is a fan of rays spread evenly over the lamp's cone (a Fibonacci spiral on the cap, so the centre rays come
 * first). Each ray walks the grid cell by cell from the lens. It stops at the first opaque block, and the last free cell
 * before that block (within {@link #LANDING_REACH} of it) is where the ray lands: that is where a light goes, so the lit pool
 * is on the floor, wall or water the beam actually reaches. Rules, all enforced here:
 * <ul>
 *   <li>a cell is free only when it is plain air, cave air, a still water source (the light is waterlogged into it, so the
 *       water stays), or a light this lamp already placed;</li>
 *   <li>a ray that reaches a cell in an unloaded chunk stops there and lands nowhere (nothing is read beyond it, nothing is
 *       loaded);</li>
 *   <li>a ray that reaches its range without hitting anything lands nowhere (there is nothing there to light);</li>
 *   <li>the landing cells are thinned to one per lattice box of {@code step} blocks, the step growing from the lamp's own
 *       until at most {@link #MAX_LIGHTS} remain; past {@link #MAX_STEP} the cells of the centre rays win.</li>
 * </ul>
 *
 * <p>A lamp with a <b>spill zone</b> (the high-bay and the HPS flat panel) casts its rays over a wider cone than its bright
 * main beam. The cells that the rays outside the main beam land on are the spill: the caller lights them at a lower level
 * ({@link #spillLevel}), so the main beam reads as the bright centre and the pool has no hard dark edge. Such a lamp also
 * reaches far (its light is wanted on a floor tens of blocks away), and a lattice of equal boxes would spend all of
 * {@link #MAX_LIGHTS} on the near floor or leave the far floor bare. Its boxes grow with the distance from the lens instead,
 * from the lamp's own step up to {@link #MAX_STEP}, as coarse as the budget needs and no coarser: dense under the lamp,
 * sparse far off, like the light it casts. A lamp without a spill zone (the floodlight) plans exactly as it always did.
 */
public final class XenonBeam {
    /** Most light blocks one lamp may hold. */
    public static final int MAX_LIGHTS = 256;
    /** Largest lattice step the planner widens to before it truncates. */
    public static final int MAX_STEP = 6;
    /** Most rays one plan casts. */
    public static final int MAX_RAYS = 2048;
    /** A ray lands in its last free cell only if that cell is this close (blocks) to the block it hit. */
    public static final double LANDING_REACH = 2.5;
    /** Ray density: one ray per this many square blocks of the cone's cross-section at full range. */
    private static final double BLOCKS_PER_RAY = 1.5;
    /**
     * Rays a lamp with a spill zone casts. They are spread thicker towards the edge of the cone (density proportional to one
     * over the cosine of the angle off the axis), which keeps about one ray per few cells of a flat floor out to the far end
     * of the reach, so half of {@link #MAX_RAYS} lands the far floor that an even spread would need all of it for.
     */
    public static final int SPILL_RAYS = 1024;
    /**
     * Blocks of distance from the lens per lattice step of a lamp with a spill zone, finest first: the planner takes the
     * first that fits {@link #MAX_LIGHTS}. A cell at distance t gets boxes of {@code round(t / this)} blocks, kept between
     * the lamp's own step and {@link #MAX_STEP}.
     */
    private static final double[] SPREAD = {1000, 48, 36, 28, 24, 20, 16, 14, 12, 10, 9, 8, 7, 6, 5, 4, 3, 2.5, 2, 1};
    /** A spill light's level is this share (4/5) of the main beam's: 15 becomes 12, the HPS warm-up level 12 becomes 10. */
    private static final int SPILL_NUM = 4, SPILL_DEN = 5;
    /** What the planner has not read yet. */
    private static final int UNREAD = Integer.MIN_VALUE;
    /**
     * Rays share cells only near the lens: next to each other they are about a block apart 14 blocks out, and wider apart
     * beyond. A cell read within this distance is remembered for the other rays; farther out it is read again if another ray
     * comes by (one ray in a few), which costs less than remembering every cell of every ray.
     */
    private static final double SHARED_READS = 16;

    /** What a cell held before a light went into it. */
    public static final byte AIR = 0;
    public static final byte CAVE_AIR = 1;
    public static final byte WATER = 2;

    private XenonBeam() {
    }

    /** What the planner may read: whether a cell's chunk is loaded, and its state and opacity when it is. */
    public interface World {
        boolean loaded(BlockPos pos);

        /** Only called for a loaded position. */
        BlockState state(BlockPos pos);

        /** Whether the block stops a beam. Only called for a loaded position. */
        boolean opaque(BlockState state, BlockPos pos);
    }

    /** What a cell held before a light went into it: {@link #AIR}, {@link #CAVE_AIR} or {@link #WATER}; -1 when no light may go there. */
    public static int kindOf(BlockState state) {
        if (state.is(Blocks.AIR)) {
            return AIR;
        }
        if (state.is(Blocks.CAVE_AIR)) {
            return CAVE_AIR;
        }
        if (state.is(Blocks.WATER) && state.getFluidState().isSource() && state.getFluidState().is(Fluids.WATER)) {
            return WATER;
        }
        return -1;
    }

    /** How many rays a cone of this size gets (a lamp without a spill zone). */
    public static int rayCount(double range, double halfAngleDegrees) {
        double cap = 2 * Math.PI * (1 - Math.cos(Math.toRadians(halfAngleDegrees)));
        return Mth.clamp((int) Math.ceil(cap * range * range / BLOCKS_PER_RAY), 16, MAX_RAYS);
    }

    /** What a plan lights: the cells (packed position to what it holds now) and which of them are spill (the lower level). */
    public record Plan(Map<Long, Byte> cells, Set<Long> spill) {
        public static final Plan NONE = new Plan(Map.of(), Set.of());

        /** Whether the cell is in the spill zone, to be lit at {@link XenonBeam#spillLevel}. */
        public boolean isSpill(long key) {
            return spill.contains(key);
        }
    }

    /** The level of a spill light when the main beam is at {@code level}. */
    public static int spillLevel(int level) {
        return (level * SPILL_NUM + SPILL_DEN / 2) / SPILL_DEN;
    }

    /** One landing cell: what it holds, how far the ray went to its last free cell, and whether it was a spill ray. */
    private record Landing(byte kind, double distance, boolean spill) {
    }

    /**
     * The cells to light: packed block position to what the cell holds now ({@link #kindOf}); for a cell the lamp already lit
     * the value from {@code current} is kept. A lamp with no spill zone.
     */
    public static Map<Long, Byte> plan(World world, BlockPos lamp, Vec3 origin, Vec3 direction, double range,
                                       double halfAngleDegrees, int step, Map<Long, Byte> current) {
        return plan(world, lamp, origin, direction, range, halfAngleDegrees, halfAngleDegrees, step, current).cells();
    }

    /**
     * The plan of a lamp whose main beam has the half angle {@code halfAngleDegrees} and whose spill zone reaches out to
     * {@code spillAngleDegrees} (there is no spill zone when that is not wider than the beam).
     */
    public static Plan plan(World world, BlockPos lamp, Vec3 origin, Vec3 direction, double range, double halfAngleDegrees,
                            double spillAngleDegrees, int step, Map<Long, Byte> current) {
        Vec3 dir = direction.normalize();
        // An orthonormal frame around the axis.
        Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = dir.cross(helper).normalize();
        Vec3 v = dir.cross(u).normalize();
        boolean spilling = spillAngleDegrees > halfAngleDegrees;
        double cosMain = Math.cos(Math.toRadians(halfAngleDegrees));
        double cosMax = Math.cos(Math.toRadians(spilling ? spillAngleDegrees : halfAngleDegrees));
        int rays = spilling ? SPILL_RAYS : rayCount(range, halfAngleDegrees);
        double golden = Math.PI * (3 - Math.sqrt(5));
        // Landing cells in ray order (centre first), each once.
        Long2ObjectLinkedOpenHashMap<Landing> landed = new Long2ObjectLinkedOpenHashMap<>();
        Long2IntOpenHashMap reads = new Long2IntOpenHashMap(1 << 12);
        reads.defaultReturnValue(UNREAD);
        for (int i = 0; i < rays; i++) {
            double f = (i + 0.5) / rays;
            // An even spread over the cone; with a spill zone, thicker towards its edge.
            double cos = spilling ? Math.pow(cosMax, f) : 1 - f * (1 - cosMax);
            double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
            double phi = i * golden;
            Vec3 d = dir.scale(cos).add(u.scale(Math.cos(phi) * sin)).add(v.scale(Math.sin(phi) * sin));
            castRay(world, lamp, origin, d, range, current, reads, landed, spilling && cos < cosMain);
        }
        if (!spilling) {
            return new Plan(thinFixed(landed, step), Set.of());
        }
        return thinGraded(landed, Math.max(1, step));
    }

    /** The lattice of a lamp without a spill zone: one step, widened from the lamp's own until the plan fits. */
    private static Map<Long, Byte> thinFixed(Long2ObjectLinkedOpenHashMap<Landing> landed, int step) {
        for (int s = Math.max(1, step); ; s++) {
            Map<Long, Byte> thinned = thin(landed, s);
            if (thinned.size() <= MAX_LIGHTS) {
                return thinned;
            }
            if (s >= MAX_STEP) {
                Map<Long, Byte> out = new LinkedHashMap<>();
                for (var e : thinned.entrySet()) {
                    if (out.size() == MAX_LIGHTS) {
                        break;
                    }
                    out.put(e.getKey(), e.getValue());
                }
                return out;
            }
        }
    }

    /**
     * The lattice of a lamp with a spill zone: boxes that grow with the distance, the finest growth that fits (a binary
     * search down {@link #SPREAD}: a coarser spread never keeps more cells). The cells of the first rays (the centre) win a
     * box, and win the budget when even the coarsest lattice has too many.
     */
    private static Plan thinGraded(Long2ObjectLinkedOpenHashMap<Landing> landed, int base) {
        int lo = 0, hi = SPREAD.length - 1;
        List<Map.Entry<Long, Landing>> kept = null;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            List<Map.Entry<Long, Landing>> at = thinGraded(landed, base, SPREAD[mid]);
            if (at.size() <= MAX_LIGHTS) {
                hi = mid;
                kept = at;
            } else {
                lo = mid + 1;
            }
        }
        if (kept == null) {
            kept = thinGraded(landed, base, SPREAD[lo]);
        }
        Map<Long, Byte> cells = new LinkedHashMap<>();
        Set<Long> spill = new HashSet<>();
        for (var e : kept) {
            if (cells.size() == MAX_LIGHTS) {
                break;
            }
            cells.put(e.getKey(), e.getValue().kind());
            if (e.getValue().spill()) {
                spill.add(e.getKey());
            }
        }
        return new Plan(cells, spill);
    }

    private static List<Map.Entry<Long, Landing>> thinGraded(Long2ObjectLinkedOpenHashMap<Landing> landed, int base, double spread) {
        List<Map.Entry<Long, Landing>> out = new ArrayList<>();
        LongOpenHashSet[] boxes = new LongOpenHashSet[MAX_STEP + 1];
        int lowest = Math.min(base, MAX_STEP);
        for (var e : landed.long2ObjectEntrySet()) {
            Landing landing = e.getValue();
            int s = Mth.clamp((int) Math.round(landing.distance() / spread), lowest, MAX_STEP);
            long key = e.getLongKey();
            if (boxes[s] == null) {
                boxes[s] = new LongOpenHashSet();
            }
            long box = BlockPos.asLong(Math.floorDiv(BlockPos.getX(key), s), Math.floorDiv(BlockPos.getY(key), s),
                    Math.floorDiv(BlockPos.getZ(key), s));
            if (boxes[s].add(box)) {
                out.add(Map.entry(key, landing));
            }
        }
        return out;
    }

    /** One cell as the planner reads it: -2 unloaded, -3 opaque, -1 passable but not free, else the free kind. */
    private static int read(World world, BlockPos p, Map<Long, Byte> current, Long2IntOpenHashMap reads, boolean remember) {
        long key = p.asLong();
        if (remember) {
            int r = reads.get(key);
            if (r != UNREAD) {
                return r;
            }
        }
        int code;
        if (!world.loaded(p)) {
            code = -2;
        } else {
            BlockState state = world.state(p);
            if (state.is(Blocks.LIGHT) && current.containsKey(key)) {
                code = current.get(key);
            } else if (world.opaque(state, p)) {
                code = -3;
            } else {
                code = kindOf(state);
            }
        }
        if (remember) {
            reads.put(key, code);
        }
        return code;
    }

    /** Walks one ray through the grid (Amanatides and Woo) and records where it lands. */
    private static void castRay(World world, BlockPos lamp, Vec3 origin, Vec3 d, double range, Map<Long, Byte> current,
                                Long2IntOpenHashMap reads, Long2ObjectLinkedOpenHashMap<Landing> landed, boolean spill) {
        int x = Mth.floor(origin.x), y = Mth.floor(origin.y), z = Mth.floor(origin.z);
        int sx = d.x > 0 ? 1 : -1, sy = d.y > 0 ? 1 : -1, sz = d.z > 0 ? 1 : -1;
        double tdx = d.x == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x);
        double tdy = d.y == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y);
        double tdz = d.z == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
        double tmx = d.x == 0 ? Double.MAX_VALUE : ((d.x > 0 ? x + 1 - origin.x : origin.x - x) * tdx);
        double tmy = d.y == 0 ? Double.MAX_VALUE : ((d.y > 0 ? y + 1 - origin.y : origin.y - y) * tdy);
        double tmz = d.z == 0 ? Double.MAX_VALUE : ((d.z > 0 ? z + 1 - origin.z : origin.z - z) * tdz);
        double t = 0;
        long lastFree = Long.MIN_VALUE;
        byte lastKind = 0;
        double lastFreeT = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (t <= range) {
            p.set(x, y, z);
            if (!(x == lamp.getX() && y == lamp.getY() && z == lamp.getZ())) {
                int code = read(world, p, current, reads, t <= SHARED_READS);
                if (code == -2) {
                    return;
                }
                if (code == -3) {
                    if (lastFree != Long.MIN_VALUE && t - lastFreeT <= LANDING_REACH && !landed.containsKey(lastFree)) {
                        landed.put(lastFree, new Landing(lastKind, lastFreeT, spill));
                    }
                    return;
                }
                if (code >= 0) {
                    lastFree = p.asLong();
                    lastKind = (byte) code;
                    lastFreeT = t;
                }
            }
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                t = tmx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                t = tmy;
                tmy += tdy;
            } else {
                z += sz;
                t = tmz;
                tmz += tdz;
            }
        }
    }

    /** Keeps the first landing cell of each lattice box of {@code step} blocks. */
    private static Map<Long, Byte> thin(Long2ObjectLinkedOpenHashMap<Landing> landed, int step) {
        Map<Long, Byte> out = new LinkedHashMap<>();
        LongOpenHashSet boxes = new LongOpenHashSet();
        for (var e : landed.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            long box = BlockPos.asLong(Math.floorDiv(BlockPos.getX(key), step), Math.floorDiv(BlockPos.getY(key), step),
                    Math.floorDiv(BlockPos.getZ(key), step));
            if (boxes.add(box)) {
                out.put(key, e.getValue().kind());
            }
        }
        return out;
    }

    /** The state a placed light goes back to: water when the light is waterlogged now, else the air it replaced. */
    public static BlockState restoredState(BlockState light, byte kind) {
        if (light.hasProperty(LightBlock.WATERLOGGED) && light.getValue(LightBlock.WATERLOGGED)) {
            return Blocks.WATER.defaultBlockState();
        }
        return kind == CAVE_AIR ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    /** The state of a placed light at {@code level}, waterlogged when it goes into water. */
    public static BlockState lightState(byte kind, int level) {
        return Blocks.LIGHT.defaultBlockState()
                .setValue(LightBlock.LEVEL, Mth.clamp(level, 0, 15))
                .setValue(LightBlock.WATERLOGGED, kind == WATER);
    }

    /** All landing cells of a plan as a list (tests). */
    public static List<BlockPos> positions(Map<Long, Byte> plan) {
        List<BlockPos> out = new ArrayList<>();
        plan.keySet().forEach(k -> out.add(BlockPos.of(k)));
        return out;
    }
}
