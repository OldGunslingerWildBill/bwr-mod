package dev.bwr.mod.spentfuel;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Surveys the structure around a pool controller: a lined box, open at the top, of pool wall blocks (the floor, and
 * four walls from the floor to the rim), with fuel racks standing inside. Read only; a survey never changes a block
 * and never loads a chunk.
 *
 * <p>The rules are the suppression pool's ({@code ConcreteBasin}) with the roof taken away: outside width and depth
 * {@value #MIN_OUTSIDE_SIDE} to {@value #MAX_OUTSIDE_SIDE} blocks, outside height {@value #MIN_OUTSIDE_HEIGHT} to
 * {@value #MAX_OUTSIDE_HEIGHT} (a rack is {@value #RACK_HEIGHT} m tall and the pool must hold
 * {@value #MIN_WATER_ABOVE_RACKS} m of water over it), the whole shell made of pool blocks, exactly one controller, ports
 * facing outward from a side wall above the floor, and nothing but racks inside, at most {@value #MAX_RACKS} of them (a
 * real plant's pool holds thousands of assemblies; the largest pool's 62 x 62 m floor has room for 3,844 racks). A rack is
 * a column of exactly {@value #RACK_HEIGHT} rack blocks on the floor, never on another rack. The water fills the interior
 * up to the rim.
 *
 * <h2>Cost</h2>
 * The largest pool is a flood of about 10,000 shell blocks and a sweep of 64 x 64 x 24 = 98,304 positions. A survey reads
 * the blocks straight from the chunk sections it has already found loaded (never through a chunk lookup per block, never
 * loading one), and the controller surveys only when a block of a pool changes near it, or, while it is not formed, every
 * five seconds once the block that stopped the last survey has changed ({@link Layout#blocker()}).
 */
public final class SpentFuelPoolLayout {

    private SpentFuelPoolLayout() {
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    public static final int MAX_RACKS = 2048;

    /** Smallest and largest outside width and depth, blocks. */
    public static final int MIN_OUTSIDE_SIDE = 5;
    public static final int MAX_OUTSIDE_SIDE = 64;

    /** Largest outside height, blocks: floor and 23 m of interior. */
    public static final int MAX_OUTSIDE_HEIGHT = 24;

    /**
     * A rack is one tier, {@value #RACK_HEIGHT} blocks tall (a real assembly is about 4.5 m and stands upright): a column of
     * exactly that many rack blocks standing on the pool floor, never on another rack.
     */
    public static final int RACK_HEIGHT = 4;

    /** Water over the top of the racks the pool must be deep enough to hold: shielding (the real figure is about 7 m, scaled). */
    public static final int MIN_WATER_ABOVE_RACKS = 3;

    /** Interior depth the pool needs, metres: the racks and the water over them. */
    public static final int MIN_INTERIOR_DEPTH = RACK_HEIGHT + MIN_WATER_ABOVE_RACKS;

    /** Outside height: interior depth and the floor. */
    public static final int MIN_OUTSIDE_HEIGHT = MIN_INTERIOR_DEPTH + 1;
    /**
     * The flood fill gives up past this many shell blocks: the largest legal shell (a 64 x 64 floor and walls 24 high) is
     * 4,096 + 252 x 23 = 9,892 blocks, and a shell that shares a wall with a neighbouring pool may be somewhat larger.
     */
    public static final int MAX_SHELL_BLOCKS = 16_384;

    /**
     * What a survey found. {@code problem} is null for a pool that can form. {@code footprintCells} is the interior
     * area in square metres and {@code heightCells} the interior depth, floor to rim, in metres.
     */
    public record Layout(BlockPos min, BlockPos max, int footprintCells, int heightCells, List<BlockPos> racks,
                         List<BlockPos> makeupPorts, List<BlockPos> coolingIn, List<BlockPos> coolingOut,
                         Component problem, BlockPos blocker, Kind blockerKind) {
        public Layout(BlockPos min, BlockPos max, int footprintCells, int heightCells, List<BlockPos> racks,
                      List<BlockPos> makeupPorts, List<BlockPos> coolingIn, List<BlockPos> coolingOut, Component problem) {
            this(min, max, footprintCells, heightCells, racks, makeupPorts, coolingIn, coolingOut, problem, null, null);
        }

        public boolean ok() {
            return problem == null;
        }

        /** Height of the top of every rack, metres above the pool floor: a rack is one full-height tier. */
        public double rackTopM(BlockPos rack) {
            return RACK_HEIGHT;
        }
    }

    /** True for a block that may be part of the shell. */
    public static boolean shell(BlockState state) {
        return state.is(BwrBlocks.SPENT_FUEL_POOL_WALL.get()) || state.is(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get())
                || state.getBlock() instanceof SpentFuelPoolPortBlock;
    }

    /**
     * What stopped a survey at one block: a gap in the shell or something in the interior that is not water or a rack.
     * Neither of those has to be a pool block, so neither change is announced to the controller; a retry looks at that one
     * block first ({@link #stillBlocked}) and surveys again only when it has changed.
     */
    public enum Kind { SHELL_GAP, INTERIOR }

    private static Layout failure(BlockPos min, BlockPos max, Component problem) {
        return failure(min, max, problem, null, null);
    }

    private static Layout failure(BlockPos min, BlockPos max, Component problem, BlockPos blocker, Kind kind) {
        return new Layout(min, max, 0, 0, List.of(), List.of(), List.of(), List.of(), problem,
                blocker == null ? null : blocker.immutable(), kind);
    }

    private static boolean interiorOk(BlockState s) {
        return s.isAir() || s.is(BwrBlocks.SPENT_FUEL_RACK.get()) || (s.getFluidState().is(Fluids.WATER) && s.getFluidState().isSource());
    }

    /**
     * Whether the block that stopped the last survey still stops a pool forming (one block read, no chunk loaded; an
     * unloaded block counts as changed, so the retry surveys and says it is waiting for chunks).
     */
    public static boolean stillBlocked(Level level, Layout last) {
        if (last == null || last.ok() || last.blocker() == null || last.blockerKind() == null || !level.isLoaded(last.blocker())) {
            return false;
        }
        BlockState s = level.getBlockState(last.blocker());
        return last.blockerKind() == Kind.SHELL_GAP ? !shell(s) : !interiorOk(s);
    }

    /** A chunk-section view of the level that remembers the last section, for the sweep. Never loads a chunk. */
    private static final class Sections {
        private final Level level;
        private long key = Long.MIN_VALUE;
        private LevelChunkSection section;

        Sections(Level level) {
            this.level = level;
        }

        BlockState get(int x, int y, int z) {
            long k = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            if (k != key) {
                key = k;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                int index = chunk == null ? -1 : chunk.getSectionIndex(y);
                section = chunk == null || index < 0 || index >= chunk.getSectionsCount() ? null : chunk.getSection(index);
                if (chunk == null) {
                    key = Long.MIN_VALUE;
                    return null;
                }
            }
            return section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
        }
    }

    public static Layout inspect(Level level, BlockPos controller) {
        Sections sections = new Sections(level);
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(controller.asLong());
        seen.add(controller.asLong());
        int minX = controller.getX(), minY = controller.getY(), minZ = controller.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        boolean missing = false;
        while (!queue.isEmpty()) {
            long p = queue.dequeueLong();
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            if (seen.size() > MAX_SHELL_BLOCKS) {
                return failure(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
                        BwrText.tr("status.bwr.spent_fuel_pool.too_large", "The pool shell is too large."));
            }
            for (Direction d : DIRECTIONS) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                // as Level.isLoaded: outside the build height counts as not loaded
                BlockState state = level.isOutsideBuildHeight(ny) ? null : sections.get(nx, ny, nz);
                if (state == null) {
                    missing = true;
                    continue;
                }
                if (shell(state)) {
                    long n = BlockPos.asLong(nx, ny, nz);
                    if (seen.add(n)) {
                        queue.enqueue(n);
                    }
                }
            }
        }
        BlockPos min = new BlockPos(minX, minY, minZ);
        BlockPos max = new BlockPos(maxX, maxY, maxZ);
        if (missing) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.waiting_for_chunks",
                    "The pool is waiting for its chunks to load."));
        }
        int w = max.getX() - min.getX() + 1;
        int d = max.getZ() - min.getZ() + 1;
        int h = max.getY() - min.getY() + 1;
        if (w < MIN_OUTSIDE_SIDE || w > MAX_OUTSIDE_SIDE || d < MIN_OUTSIDE_SIDE || d > MAX_OUTSIDE_SIDE
                || h < MIN_OUTSIDE_HEIGHT || h > MAX_OUTSIDE_HEIGHT) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.size",
                    "Build a pool 5 to 64 blocks wide and deep and 8 to 24 high, outside: racks are 4 m tall and need 3 m of water over them."));
        }
        List<BlockPos> rackBlocks = new ArrayList<>();
        List<BlockPos> makeup = new ArrayList<>();
        List<BlockPos> coolingIn = new ArrayList<>();
        List<BlockPos> coolingOut = new ArrayList<>();
        int controllers = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        // the order of BlockPos.betweenClosed: x fastest, then y, then z (the first problem found is the one reported)
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState s = sections.get(x, y, z);
                    at.set(x, y, z);
                    if (s == null) {
                        // every chunk of the box held a shell block the flood found loaded; one that went away since is waited for
                        return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.waiting_for_chunks",
                                "The pool is waiting for its chunks to load."));
                    }
                    boolean wall = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (wall || y == minY) {
                        if (!shell(s)) {
                            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.shell_gap",
                                    "Complete the floor and the four walls up to the rim; missing at %s", at.toShortString()), at, Kind.SHELL_GAP);
                        }
                        if (s.is(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get())) {
                            controllers++;
                        }
                        if (s.getBlock() instanceof SpentFuelPoolPortBlock port) {
                            Direction face = s.getValue(SpentFuelPoolPortBlock.FACING);
                            int ox = x + face.getStepX();
                            int oz = z + face.getStepZ();
                            boolean inside = ox >= minX && ox <= maxX && oz >= minZ && oz <= maxZ;
                            if (!wall || y == minY || inside) {
                                return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.port_placement",
                                        "Ports go in a side wall above the floor, facing outward; check %s", at.toShortString()));
                            }
                            switch (port.kind()) {
                                case MAKEUP -> makeup.add(at.immutable());
                                case COOLING_IN -> coolingIn.add(at.immutable());
                                case COOLING_OUT -> coolingOut.add(at.immutable());
                            }
                        }
                    } else if (s.is(BwrBlocks.SPENT_FUEL_RACK.get())) {
                        rackBlocks.add(at.immutable());
                    } else if (!interiorOk(s)) {
                        return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.clear_interior",
                                "Clear the pool interior at %s; only racks may stand in it", at.toShortString()), at, Kind.INTERIOR);
                    }
                }
            }
        }
        // racks: each is a column of exactly RACK_HEIGHT blocks standing on the floor; anything else is refused
        Set<BlockPos> loose = new HashSet<>(rackBlocks);
        List<BlockPos> racks = new ArrayList<>();
        for (BlockPos p : rackBlocks) {
            if (p.getY() != min.getY() + 1) {
                continue;
            }
            boolean whole = true;
            for (int k = 1; k < RACK_HEIGHT; k++) {
                whole &= loose.contains(p.above(k));
            }
            if (whole && !loose.contains(p.above(RACK_HEIGHT))) {
                racks.add(p);
                for (int k = 0; k < RACK_HEIGHT; k++) {
                    loose.remove(p.above(k));
                }
            }
        }
        if (!loose.isEmpty()) {
            BlockPos bad = loose.stream().min(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).get();
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.rack_support",
                    "A rack is one tier of exactly %s blocks standing on the pool floor, never on another rack; check %s",
                    RACK_HEIGHT, bad.toShortString()));
        }
        if (controllers != 1) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.one_controller",
                    "Use exactly one pool controller in the shell."));
        }
        if (racks.size() > MAX_RACKS) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.too_many_racks",
                    "A pool holds at most %s racks.", MAX_RACKS));
        }
        racks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return new Layout(min, max, (w - 2) * (d - 2), h - 1, List.copyOf(racks), List.copyOf(makeup), List.copyOf(coolingIn),
                List.copyOf(coolingOut), null);
    }

    /** Positions the pool claims in the registry: its racks and its ports. */
    public static Set<BlockPos> claimed(Layout layout) {
        Set<BlockPos> set = new HashSet<>();
        for (BlockPos rack : layout.racks()) {
            for (int k = 0; k < RACK_HEIGHT; k++) {
                set.add(rack.above(k));
            }
        }
        set.addAll(layout.makeupPorts());
        set.addAll(layout.coolingIn());
        set.addAll(layout.coolingOut());
        return set;
    }
}
