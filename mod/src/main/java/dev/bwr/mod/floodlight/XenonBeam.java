package dev.bwr.mod.floodlight;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
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

    /** What a free cell holds now ({@link #AIR}, {@link #CAVE_AIR} or {@link #WATER}), or -1 when no light may go there. */
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

    /** How many rays a cone of this size gets. */
    public static int rayCount(double range, double halfAngleDegrees) {
        double cap = 2 * Math.PI * (1 - Math.cos(Math.toRadians(halfAngleDegrees)));
        return Mth.clamp((int) Math.ceil(cap * range * range / BLOCKS_PER_RAY), 16, MAX_RAYS);
    }

    /**
     * The cells to light: packed block position to what the cell holds now ({@link #kindOf}); for a cell the lamp already lit
     * the value from {@code current} is kept.
     */
    public static Map<Long, Byte> plan(World world, BlockPos lamp, Vec3 origin, Vec3 direction, double range,
                                       double halfAngleDegrees, int step, Map<Long, Byte> current) {
        Vec3 dir = direction.normalize();
        // An orthonormal frame around the axis.
        Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = dir.cross(helper).normalize();
        Vec3 v = dir.cross(u).normalize();
        double cosMax = Math.cos(Math.toRadians(halfAngleDegrees));
        int rays = rayCount(range, halfAngleDegrees);
        double golden = Math.PI * (3 - Math.sqrt(5));
        // Landing cells in ray order (centre first), each once.
        LinkedHashMap<Long, Byte> landed = new LinkedHashMap<>();
        Map<Long, CellRead> reads = new HashMap<>();
        for (int i = 0; i < rays; i++) {
            double cos = 1 - (i + 0.5) / rays * (1 - cosMax);
            double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
            double phi = i * golden;
            Vec3 d = dir.scale(cos).add(u.scale(Math.cos(phi) * sin)).add(v.scale(Math.sin(phi) * sin));
            castRay(world, lamp, origin, d, range, current, reads, landed);
        }
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

    /** One cell as the planner read it: -2 unloaded, -3 opaque, -1 passable but not free, else the free kind. */
    private record CellRead(int code) {
    }

    private static CellRead read(World world, BlockPos p, Map<Long, Byte> current, Map<Long, CellRead> reads) {
        long key = p.asLong();
        CellRead r = reads.get(key);
        if (r != null) {
            return r;
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
        r = new CellRead(code);
        reads.put(key, r);
        return r;
    }

    /** Walks one ray through the grid (Amanatides and Woo) and records where it lands. */
    private static void castRay(World world, BlockPos lamp, Vec3 origin, Vec3 d, double range, Map<Long, Byte> current,
                                Map<Long, CellRead> reads, Map<Long, Byte> landed) {
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
                int code = read(world, p, current, reads).code();
                if (code == -2) {
                    return;
                }
                if (code == -3) {
                    if (lastFree != Long.MIN_VALUE && t - lastFreeT <= LANDING_REACH) {
                        landed.putIfAbsent(lastFree, lastKind);
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
    private static Map<Long, Byte> thin(LinkedHashMap<Long, Byte> landed, int step) {
        Map<Long, Byte> out = new LinkedHashMap<>();
        Set<Long> boxes = new HashSet<>();
        for (var e : landed.entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            long box = BlockPos.asLong(Math.floorDiv(p.getX(), step), Math.floorDiv(p.getY(), step), Math.floorDiv(p.getZ(), step));
            if (boxes.add(box)) {
                out.put(e.getKey(), e.getValue());
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
