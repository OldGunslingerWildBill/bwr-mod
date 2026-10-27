package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.accident.ShieldPlugConcreteBlock;
import dev.bwr.mod.reactor.CavitySeal;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the reactor building crane sees of the shield plug (BUILDING-CRANE.md): which plug blocks make up a liftable section, where a
 * section can be set down, and whether any plug lies over the reactor well. Read only, on demand (a click, a computer's call, the
 * start and the hook-on of a lift), never per tick, and it never loads a chunk: a block in an unloaded chunk is a problem to report,
 * not something to read.
 *
 * <h2>A section</h2>
 * The plug blocks connected, face to face, to a block that carries a {@link CraneAnchorBlock}, flood-filled, stopping at a
 * <b>seam</b> (a joint marked on either block, see {@link PlugSeamMarkerItem}) and at anything that is not shield plug concrete. The
 * anchors standing on the top of those blocks ride with the section. A section with exactly {@value PlugLift#ANCHORS_REQUIRED}
 * anchors is a four-point lift; any other number is refused with the count.
 */
public final class PlugSections {
    private PlugSections() {
    }

    /** The most plug blocks one survey follows before it gives up (a section that big is far over the crane's rating anyway). */
    public static final int FLOOD_LIMIT = 2048;
    /** Laydown search: how far above and below the section's own level a place may be (the plug sits in a recess in the floor). */
    public static final int LAYDOWN_DY_MIN = -4, LAYDOWN_DY_MAX = 8;

    /** The blocks of a section: plug blocks and the anchors on them, sorted bottom to top. */
    public record Section(BlockPos min, BlockPos max, List<BlockPos> blocks, List<BlockPos> anchors, int[] tiers, double massT) {
        public int blockCount() {
            return blocks.size();
        }

        public int sizeX() {
            return max.getX() - min.getX() + 1;
        }

        public int sizeY() {
            return max.getY() - min.getY() + 1;
        }

        public int sizeZ() {
            return max.getZ() - min.getZ() + 1;
        }

        /** Every cell the section carries: the plug blocks, then the anchors. */
        public List<BlockPos> cells() {
            List<BlockPos> all = new ArrayList<>(blocks);
            all.addAll(anchors);
            return all;
        }

        /** The pick point: the middle of the anchors (the hook hangs over it), x then z; the middle of the section with no anchors. */
        public double[] pick() {
            if (anchors.isEmpty()) {
                return new double[]{(min.getX() + max.getX() + 1) / 2.0, (min.getZ() + max.getZ() + 1) / 2.0};
            }
            double x = 0, z = 0;
            for (BlockPos a : anchors) {
                x += a.getX() + 0.5;
                z += a.getZ() + 0.5;
            }
            return new double[]{x / anchors.size(), z / anchors.size()};
        }

        public boolean rigged() {
            return PlugLift.rigged(anchors.size());
        }
    }

    /** A square on the floor the laydown stays off: a centre (x, z) and how many blocks it reaches each way (a head lying on a stand). */
    public record Keep(int x, int z, int half) {
    }

    /** A survey: the section, or why there is none. */
    public record Survey(@Nullable Section section, @Nullable Component problem, boolean unloaded) {
        public Survey(@Nullable Section section, @Nullable Component problem) {
            this(section, problem, false);
        }
    }

    /** Which tier a state is (1..3), or 0 for anything that is not shield plug concrete. */
    public static int tierOf(BlockState state) {
        if (!(state.getBlock() instanceof ShieldPlugConcreteBlock)) {
            return 0;
        }
        if (state.is(BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get())) {
            return 3;
        }
        return state.is(BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get()) ? 2 : 1;
    }

    /** True when two adjacent plug blocks are one piece: both plug, neither marked as a seam across the joint. */
    public static boolean joined(BlockState a, Direction toB, BlockState b) {
        return a.getBlock() instanceof ShieldPlugConcreteBlock && b.getBlock() instanceof ShieldPlugConcreteBlock
                && !ShieldPlugConcreteBlock.seamed(a, toB) && !ShieldPlugConcreteBlock.seamed(b, toB.getOpposite());
    }

    private static final Comparator<BlockPos> BOTTOM_UP = Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)
            .thenComparingInt(BlockPos::getX);

    /** True when there is a crane anchor on top of this plug block. */
    public static boolean anchored(Level level, BlockPos plug) {
        return level.getBlockState(plug.above()).getBlock() instanceof CraneAnchorBlock;
    }

    /** The refusal for a section (or a coordinate) that lies in a chunk that is not loaded. */
    public static Component unloadedProblem() {
        return BwrText.tr("status.bwr.plug.unloaded", "Part of the shield plug lies in a chunk that is not loaded; the crane does not load chunks.");
    }

    /** Flood-fills the section that contains the plug block {@code seed}. */
    public static Survey survey(Level level, BlockPos seed) {
        if (!level.isLoaded(seed)) {
            return new Survey(null, unloadedProblem(), true);
        }
        if (tierOf(level.getBlockState(seed)) == 0) {
            return new Survey(null, BwrText.tr("status.bwr.plug.not_plug", "There is no shield plug concrete there."));
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> blocks = new ArrayList<>();
        seen.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            blocks.add(p);
            if (blocks.size() > FLOOD_LIMIT) {
                return new Survey(null, BwrText.tr("status.bwr.plug.too_big_to_survey",
                        "The plug piece is over %s blocks with no seam to split it. Mark seams with the Plug Seam Marker.", FLOOD_LIMIT));
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.contains(n)) {
                    continue;
                }
                if (!level.isLoaded(n)) {
                    return new Survey(null, unloadedProblem(), true);
                }
                if (joined(state, d, level.getBlockState(n))) {
                    seen.add(n);
                    queue.add(n);
                }
            }
        }
        blocks.sort(BOTTOM_UP);
        List<BlockPos> anchors = new ArrayList<>();
        int[] tiers = new int[3];
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            tiers[tierOf(level.getBlockState(p)) - 1]++;
            BlockPos above = p.above();
            if (level.isLoaded(above) && level.getBlockState(above).getBlock() instanceof CraneAnchorBlock) {
                anchors.add(above);
            }
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        for (BlockPos a : anchors) {
            maxY = Math.max(maxY, a.getY());
        }
        anchors.sort(BOTTOM_UP);
        // the mass is the sum of the blocks' own masses (the data map bwr:plug_mass) and the anchors'
        double mass = 0.0;
        for (BlockPos p : blocks) {
            mass += PlugMass.tonnes(level.getBlockState(p));
        }
        for (BlockPos a : anchors) {
            mass += PlugMass.tonnes(level.getBlockState(a));
        }
        return new Survey(new Section(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), List.copyOf(blocks), List.copyOf(anchors), tiers, mass), null);
    }

    /** What a scan of the crane's reach found: every section with at least one anchor, and the first thing that could not be read. */
    public record Scan(List<Section> sections, @Nullable Component problem) {
    }

    /**
     * Every anchored section standing in the columns the crane reaches, between two heights (inclusive), nearest to ({@code wellX}, {@code wellZ})
     * first. Columns in unloaded chunks are skipped (a section reaching into one reports the problem).
     */
    public static Scan scan(Level level, BridgeSite site, int yLo, int yHi, double wellX, double wellZ) {
        List<Section> found = new ArrayList<>();
        Set<BlockPos> covered = new HashSet<>();
        Component problem = null;
        int lo = Math.min(Math.min(site.aLo, site.cLow), Math.min(site.aHi, site.cHigh)) - 1;
        int hi = Math.max(Math.max(site.aLo, site.cLow), Math.max(site.aHi, site.cHigh)) + 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = lo; x <= hi; x++) {
            for (int z = lo; z <= hi; z++) {
                if (!site.reaches(x + 0.5, z + 0.5)) {
                    continue;
                }
                p.set(x, yLo, z);
                if (!level.isLoaded(p)) {
                    continue;
                }
                for (int y = yLo; y <= yHi; y++) {
                    p.set(x, y, z);
                    if (!(level.getBlockState(p).getBlock() instanceof CraneAnchorBlock)) {
                        continue;
                    }
                    BlockPos seed = p.below().immutable();
                    if (covered.contains(seed) || tierOf(level.getBlockState(seed)) == 0) {
                        continue;
                    }
                    Survey s = survey(level, seed);
                    if (s.section() == null) {
                        problem = problem == null ? s.problem() : problem;
                        continue;
                    }
                    covered.addAll(s.section().blocks());
                    found.add(s.section());
                }
            }
        }
        found.sort(Comparator.<Section>comparingDouble(s -> {
            double[] c = s.pick();
            return Math.hypot(c[0] - wellX, c[1] - wellZ);
        }).thenComparing(s -> s.min(), BOTTOM_UP));
        return new Scan(found, problem);
    }

    // -----------------------------------------------------------------------------------------------------------
    // The well
    // -----------------------------------------------------------------------------------------------------------

    /** True when the column is over the reactor well: inside the vessel's footprint, or one of the well's own cells from the cavity survey. */
    public static boolean overWellColumn(VesselAppearance.Envelope e, int x, int y, int z) {
        if (x >= e.min().getX() && x <= e.max().getX() && z >= e.min().getZ() && z <= e.max().getZ()) {
            return true;
        }
        CavitySeal.Visual v = e.cavity();
        return v != null && v.well() != null && v.well().cell(y, x, z);
    }

    /**
     * The shield plug blocks over the reactor well (the vessel's footprint and the well the cavity survey found), from the first row above the
     * vessel to the rails: the head cannot move through them.
     */
    public static List<BlockPos> plugOverWell(Level level, VesselAppearance.Envelope e, int topY) {
        List<BlockPos> over = new ArrayList<>();
        int lo = e.min().getX() - CavitySeal.WELL_EXTENT, hi = e.max().getX() + CavitySeal.WELL_EXTENT;
        int zlo = e.min().getZ() - CavitySeal.WELL_EXTENT, zhi = e.max().getZ() + CavitySeal.WELL_EXTENT;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = lo; x <= hi; x++) {
            for (int z = zlo; z <= zhi; z++) {
                for (int y = e.max().getY() + 1; y <= topY && y < e.max().getY() + 1 + CavitySeal.MAX_WELL_LAYERS; y++) {
                    p.set(x, y, z);
                    if (!overWellColumn(e, x, y, z) || !level.isLoaded(p)) {
                        continue;
                    }
                    if (level.getBlockState(p).getBlock() instanceof ShieldPlugConcreteBlock) {
                        over.add(p.immutable());
                    }
                }
            }
        }
        return over;
    }

    /** True when any cell of the section (the cells moved by {@code delta}) lies over the reactor well. */
    public static boolean coversWell(VesselAppearance.Envelope e, List<BlockPos> blocks, BlockPos delta) {
        for (BlockPos b : blocks) {
            if (overWellColumn(e, b.getX() + delta.getX(), b.getY() + delta.getY(), b.getZ() + delta.getZ())) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------------------------------
    // The laydown
    // -----------------------------------------------------------------------------------------------------------

    /** Why a place cannot take the section (the cells moved by {@code delta}), or null when it can: every cell air, the lowest layer on solid floor. */
    @Nullable
    public static Component placeProblem(Level level, List<BlockPos> cells, int lowestY, BlockPos delta) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (BlockPos c : cells) {
            p.set(c.getX() + delta.getX(), c.getY() + delta.getY(), c.getZ() + delta.getZ());
            if (!level.isLoaded(p)) {
                return BwrText.tr("status.bwr.plug.place_unloaded", "The place at %s is in a chunk that is not loaded; the crane does not load chunks.", p.toShortString());
            }
            if (!level.getBlockState(p).isAir()) {
                return BwrText.tr("status.bwr.plug.place_occupied", "The place for the section is not clear: there is a block at %s. The crane never replaces a block.",
                        p.toShortString());
            }
        }
        for (BlockPos c : cells) {
            if (c.getY() != lowestY) {
                continue;
            }
            p.set(c.getX() + delta.getX(), c.getY() + delta.getY() - 1, c.getZ() + delta.getZ());
            if (!level.isLoaded(p) || !level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) {
                return BwrText.tr("status.bwr.plug.place_no_floor", "There is no solid floor under the section at %s.", p.toShortString());
            }
        }
        return null;
    }

    /**
     * The laydown rule: the clear spot nearest to where the section stands. Every place within the crane's reach is a candidate, a whole number of
     * blocks away in x and z and at most {@value #LAYDOWN_DY_MAX} up or {@code -LAYDOWN_DY_MIN} down; it must hold the whole section
     * (every cell air, never a replaced block), rest the section's lowest layer on solid floor, lie wholly in the crane's reach, stay
     * off the reactor well and the reach of a head stand holding the head, and be in loaded chunks. Nearest by the distance of the pick
     * points, then the smaller drop, then x, then z. Returns the offset (add it to every cell), or null with the reason in {@code why[0]}.
     */
    @Nullable
    public static BlockPos findLaydown(Level level, BridgeSite site, Section s, @Nullable VesselAppearance.Envelope env, List<Keep> keepClear, Component[] why) {
        List<BlockPos> cells = s.cells();
        double[] pick = s.pick();
        int reach = Math.max(site.aHi - site.aLo, site.cHigh - site.cLow) + 2;
        record Cand(int dx, int dz, double d) {
        }
        List<Cand> cands = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                cands.add(new Cand(dx, dz, Math.hypot(dx, dz)));
            }
        }
        cands.sort(Comparator.<Cand>comparingDouble(Cand::d).thenComparingInt(Cand::dx).thenComparingInt(Cand::dz));
        Component first = BwrText.tr("status.bwr.plug.no_laydown",
                "There is no clear place on the refuel floor to set the section down: it needs %s x %s blocks of clear floor between the crane's rails, off the well.",
                s.sizeX(), s.sizeZ());
        for (Cand c : cands) {
            // the whole footprint, and the pick point, in the crane's reach
            if (!site.reaches(pick[0] + c.dx(), pick[1] + c.dz()) || !site.reaches(s.min().getX() + c.dx() + 0.5, s.min().getZ() + c.dz() + 0.5)
                    || !site.reaches(s.max().getX() + c.dx() + 0.5, s.max().getZ() + c.dz() + 0.5)
                    || !site.reaches(s.min().getX() + c.dx() + 0.5, s.max().getZ() + c.dz() + 0.5)
                    || !site.reaches(s.max().getX() + c.dx() + 0.5, s.min().getZ() + c.dz() + 0.5)) {
                continue;
            }
            if (nearAny(keepClear, s, c.dx(), c.dz())) {
                continue;
            }
            for (int k = 0; k <= Math.max(-LAYDOWN_DY_MIN, LAYDOWN_DY_MAX); k++) {
                for (int sign : new int[]{1, -1}) {
                    int dy = k * sign;
                    if ((sign == -1 && k == 0) || dy < LAYDOWN_DY_MIN || dy > LAYDOWN_DY_MAX) {
                        continue;
                    }
                    BlockPos delta = new BlockPos(c.dx(), dy, c.dz());
                    if (env != null && coversWell(env, s.blocks(), delta)) {
                        continue;
                    }
                    if (placeProblem(level, cells, s.min().getY(), delta) == null) {
                        return delta;
                    }
                }
            }
        }
        why[0] = first;
        return null;
    }

    /** True when the shifted footprint of the section lies on any of the given keep-clear squares (see {@link Keep}). */
    private static boolean nearAny(List<Keep> keepClear, Section s, int dx, int dz) {
        for (Keep k : keepClear) {
            int half = k.half();
            int x0 = s.min().getX() + dx, x1 = s.max().getX() + dx, z0 = s.min().getZ() + dz, z1 = s.max().getZ() + dz;
            if (k.x() + half >= x0 && k.x() - half <= x1 && k.z() + half >= z0 && k.z() - half <= z1) {
                return true;
            }
        }
        return false;
    }
}
