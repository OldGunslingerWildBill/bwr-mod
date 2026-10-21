package dev.bwr.mod.panel;

import dev.bwr.mod.panel.component.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A panel group: every loaded panel block of the same type and facing that is
 * connected through coplanar faces (spec section 1). The group is a set of
 * blocks laid on one (column, row) grid, column 0 / row 0 being the top-left
 * block as the operator sees it; the mounting grid is then
 * {@code columns * 16} by {@code rows * 16} cells.
 *
 * <p>Groups are computed lazily from any member by flood fill, shared by all
 * members (each {@link PanelBlockEntity} points at the same object) and
 * invalidated, never edited: when a panel block loads, unloads or is removed,
 * {@link PanelBlockEntity#invalidateGroups} flips {@link #valid} on the affected
 * groups and each member recomputes on next use. The flood fill never loads a
 * chunk; an unloaded neighbour is simply not a member until it loads.
 *
 * <p>The size cap ({@link PanelConfig}) is enforced when a block is placed. A
 * group that is over the cap anyway (blocks placed by commands or after the
 * config was lowered) is flagged {@link #oversize()} and accepts no new
 * components, but keeps working.
 */
public final class PanelGroup {
    /** Flood fill stops here so a runaway wall cannot stall the server. */
    private static final int HARD_LIMIT = 2048;

    private final PanelType type;
    private final Direction facing;
    private final List<BlockPos> members;
    private final Map<BlockPos, Integer> cellOf;
    private final Map<Integer, BlockPos> blockAt;
    private final int columns;
    private final int rows;
    private final boolean oversize;
    private boolean valid = true;

    private PanelGroup(PanelType type, Direction facing, List<BlockPos> members, Map<BlockPos, Integer> cellOf,
                       Map<Integer, BlockPos> blockAt, int columns, int rows, boolean oversize) {
        this.type = type;
        this.facing = facing;
        this.members = members;
        this.cellOf = cellOf;
        this.blockAt = blockAt;
        this.columns = columns;
        this.rows = rows;
        this.oversize = oversize;
    }

    private static int pack(int col, int row) {
        return (col & 0xFFFF) << 16 | (row & 0xFFFF);
    }

    public PanelType type() {
        return type;
    }

    public Direction facing() {
        return facing;
    }

    /** Member block positions, row-major from the top-left. */
    public List<BlockPos> members() {
        return members;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public boolean oversize() {
        return oversize;
    }

    public boolean isValid() {
        return valid;
    }

    void invalidate() {
        valid = false;
    }

    public boolean contains(BlockPos pos) {
        return cellOf.containsKey(pos);
    }

    /** Column of a member block, 0 at the operator's left; -1 if not a member. */
    public int columnOf(BlockPos pos) {
        Integer packed = cellOf.get(pos);
        return packed == null ? -1 : packed >>> 16;
    }

    public int rowOf(BlockPos pos) {
        Integer packed = cellOf.get(pos);
        return packed == null ? -1 : packed & 0xFFFF;
    }

    /** The member block at a column/row, or null when the group has a hole or ends there. */
    public BlockPos blockAt(int column, int row) {
        if (column < 0 || row < 0 || column >= columns || row >= rows) {
            return null;
        }
        return blockAt.get(pack(column, row));
    }

    // ---- construction

    /** Whether a block at {@code pos} would belong to a group of this type and facing. */
    static boolean isMember(Level level, BlockPos pos, PanelType type, Direction facing, Set<BlockPos> assumed) {
        if (assumed.contains(pos)) {
            return true;
        }
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PanelBlock block && block.panelType(state) == type
                && state.getValue(PanelBlock.FACING) == facing;
    }

    /**
     * Flood fills the group containing {@code start}.
     *
     * @param assumed a position to treat as a member even if no panel block is
     *                there yet (used to ask "would this placement be too big");
     *                may be null
     */
    public static PanelGroup compute(Level level, BlockPos start, PanelType type, Direction facing, BlockPos assumed) {
        return computeAssuming(level, start, type, facing, assumed == null ? Set.of() : Set.of(assumed));
    }

    /** {@link #compute} with several positions treated as members (a multi-block piece that is about to be placed). */
    public static PanelGroup computeAssuming(Level level, BlockPos start, PanelType type, Direction facing,
                                             Set<BlockPos> assumed) {
        Map<BlockPos, int[]> found = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos origin = start.immutable();
        found.put(origin, new int[] {0, 0});
        queue.add(origin);
        boolean truncated = false;
        int minCol = 0;
        int minRow = 0;
        int maxCol = 0;
        int maxRow = 0;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            int[] at = found.get(pos);
            for (int step = 0; step < 4; step++) {
                BlockPos next;
                int col = at[0];
                int row = at[1];
                switch (step) {
                    case 0 -> {
                        next = PanelType.nextColumn(pos, facing);
                        col++;
                    }
                    case 1 -> {
                        next = PanelType.previousColumn(pos, facing);
                        col--;
                    }
                    case 2 -> {
                        next = type.nextRow(pos, facing);
                        row++;
                    }
                    default -> {
                        next = type.previousRow(pos, facing);
                        row--;
                    }
                }
                if (next == null || (step < 2 && !type.hasColumns()) || found.containsKey(next)
                        || !isMember(level, next, type, facing, assumed)) {
                    continue;
                }
                if (found.size() >= HARD_LIMIT) {
                    truncated = true;
                    continue;
                }
                BlockPos fixed = next.immutable();
                found.put(fixed, new int[] {col, row});
                queue.add(fixed);
                minCol = Math.min(minCol, col);
                minRow = Math.min(minRow, row);
                maxCol = Math.max(maxCol, col);
                maxRow = Math.max(maxRow, row);
            }
        }
        int columns = maxCol - minCol + 1;
        int rows = maxRow - minRow + 1;
        Map<BlockPos, Integer> cellOf = new HashMap<>();
        Map<Integer, BlockPos> blockAt = new HashMap<>();
        List<BlockPos> ordered = new ArrayList<>(found.keySet());
        for (Map.Entry<BlockPos, int[]> entry : found.entrySet()) {
            int packed = pack(entry.getValue()[0] - minCol, entry.getValue()[1] - minRow);
            cellOf.put(entry.getKey(), packed);
            blockAt.put(packed, entry.getKey());
        }
        ordered.sort((a, b) -> {
            int pa = cellOf.get(a);
            int pb = cellOf.get(b);
            int ra = pa & 0xFFFF;
            int rb = pb & 0xFFFF;
            return ra != rb ? Integer.compare(ra, rb) : Integer.compare(pa >>> 16, pb >>> 16);
        });
        boolean oversize = truncated || columns > PanelConfig.maxColumns() || rows > PanelConfig.maxRows();
        return new PanelGroup(type, facing, List.copyOf(ordered), cellOf, blockAt, columns, rows, oversize);
    }

    /**
     * Whether a block next to a member, in the board's own directions, is in a chunk that is not loaded. The group only
     * holds loaded blocks, so the board may well go on there; an edit that has to reach every copy of a mimic path
     * cannot know it did.
     */
    public boolean bordersUnloaded(Level level) {
        for (BlockPos pos : members) {
            for (int step = 0; step < 4; step++) {
                BlockPos next = switch (step) {
                    case 0 -> PanelType.nextColumn(pos, facing);
                    case 1 -> PanelType.previousColumn(pos, facing);
                    case 2 -> type.nextRow(pos, facing);
                    default -> type.previousRow(pos, facing);
                };
                if (next == null || (step < 2 && !type.hasColumns()) || level.isOutsideBuildHeight(next)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether placing a panel block at {@code pos} keeps its group within the configured size. */
    public static boolean placementAllowed(Level level, BlockPos pos, PanelType type, Direction facing) {
        return placementAllowed(level, Set.of(pos), type, facing);
    }

    /**
     * {@link #placementAllowed(Level, BlockPos, PanelType, Direction)} for a piece that puts several blocks of one panel
     * type into the same group (the two halves of a desk, the two board blocks of a duplex column): the group is measured
     * once with all of them in it. Testing each alone lets two halves that are each one under the cap make a group one
     * over it. All {@code positions} must be adjacent in the board's directions.
     */
    public static boolean placementAllowed(Level level, java.util.Collection<BlockPos> positions, PanelType type,
                                           Direction facing) {
        Set<BlockPos> assumed = Set.copyOf(positions);
        return !computeAssuming(level, assumed.iterator().next(), type, facing, assumed).oversize();
    }

    // ---- component queries

    /** Why a placement is or is not possible. */
    public enum Result {
        OK, NOT_A_PANEL, OVERSIZE_GROUP, BAD_ARGUMENTS, TOO_LARGE, OUTSIDE_GROUP, BLOCKED, TOO_MANY
    }

    /** One block's share of a footprint: cells {@code [x0,x1) x [y0,y1)} of the block at {@code pos}. */
    public record Rect(BlockPos pos, int x0, int y0, int x1, int y1) {
    }

    /** The outcome of {@link #checkPlacement}: a verdict and, when OK, the per-block rectangles. */
    public record Placement(Result result, List<Rect> rects) {
        public boolean ok() {
            return result == Result.OK;
        }
    }

    private static final Placement NOT_A_PANEL = new Placement(Result.NOT_A_PANEL, List.of());

    /**
     * Decides whether a footprint anchored at cell {@code (cx, cy)} of the block
     * at {@code anchor} fits: the anchor is a panel block of an acceptable
     * group, the footprint spans at most 4 x 4 blocks, every block it touches is
     * a loaded member of the same group, and none of the cells it covers is
     * taken. Shared by the server (authority) and the client (ghost colour), so
     * what the player sees is what the server decides.
     */
    public static Placement checkPlacement(Level level, BlockPos anchor, int cx, int cy, Footprint footprint) {
        return checkPlacement(level, anchor, cx, cy, footprint, PanelGeometry.MAX_SPAN_BLOCKS);
    }

    /** {@link #checkPlacement(Level, BlockPos, int, int, Footprint)} for a type that may span up to {@code maxSpan} blocks. */
    public static Placement checkPlacement(Level level, BlockPos anchor, int cx, int cy, Footprint footprint, int maxSpan) {
        if (!level.isLoaded(anchor) || !(level.getBlockEntity(anchor) instanceof PanelBlockEntity be)) {
            return NOT_A_PANEL;
        }
        if (cx < 0 || cy < 0 || cx >= PanelGeometry.GRID || cy >= PanelGeometry.GRID) {
            return new Placement(Result.BAD_ARGUMENTS, List.of());
        }
        PanelGroup group = be.group();
        if (group == null || group.oversize()) {
            return new Placement(Result.OVERSIZE_GROUP, List.of());
        }
        int spanX = (cx + footprint.w() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        int spanY = (cy + footprint.h() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        if (spanX > maxSpan || spanY > maxSpan) {
            return new Placement(Result.TOO_LARGE, List.of());
        }
        if (be.ownedCount() >= PanelConfig.maxComponentsPerBlock()) {
            return new Placement(Result.TOO_MANY, List.of());
        }
        int column = group.columnOf(anchor);
        int row = group.rowOf(anchor);
        List<Rect> rects = new ArrayList<>(spanX * spanY);
        for (int j = 0; j < spanY; j++) {
            for (int i = 0; i < spanX; i++) {
                BlockPos pos = group.blockAt(column + i, row + j);
                if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity target)) {
                    return new Placement(Result.OUTSIDE_GROUP, List.of());
                }
                int x0 = Math.max(cx - i * PanelGeometry.GRID, 0);
                int y0 = Math.max(cy - j * PanelGeometry.GRID, 0);
                int x1 = Math.min(cx + footprint.w() - i * PanelGeometry.GRID, PanelGeometry.GRID);
                int y1 = Math.min(cy + footprint.h() - j * PanelGeometry.GRID, PanelGeometry.GRID);
                if (!target.isFree(x0, y0, x1, y1) || !target.cellsUsable(x0, y0, x1, y1)) {
                    return new Placement(Result.BLOCKED, List.of());
                }
                rects.add(new Rect(pos, x0, y0, x1, y1));
            }
        }
        return new Placement(Result.OK, rects);
    }

    // ---- labels

    /** Every label currently used by a loaded member, for uniqueness checks. */
    public Set<String> labelsInUse(Level level) {
        Set<String> labels = new HashSet<>();
        for (BlockPos pos : members) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.collectLabels(labels);
            }
        }
        return labels;
    }

    /** The first unused {@code PREFIX-n}, n from 1. */
    public String nextLabel(Level level, String prefix) {
        Set<String> used = labelsInUse(level);
        for (int n = 1; ; n++) {
            String candidate = prefix + "-" + n;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    // ---- label uniqueness

    /** A component with its owning block, as listed by {@link #components}. */
    public record Entry(PanelBlockEntity owner, dev.bwr.mod.panel.component.PanelComponent component) {
    }

    /**
     * Every component owned by a loaded member, in the one canonical order used for renaming and for CC lookups:
     * owner block position ({@code BlockPos} order: y, z, x), then component id.
     */
    public List<Entry> components(Level level) {
        List<PanelBlockEntity> owners = new ArrayList<>();
        for (BlockPos pos : members) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                owners.add(be);
            }
        }
        owners.sort((a, b) -> a.getBlockPos().compareTo(b.getBlockPos()));
        List<Entry> result = new ArrayList<>();
        for (PanelBlockEntity be : owners) {
            List<dev.bwr.mod.panel.component.PanelComponent> owned = new ArrayList<>(be.owned());
            owned.sort((a, b) -> Integer.compare(a.id(), b.id()));
            for (dev.bwr.mod.panel.component.PanelComponent component : owned) {
                result.add(new Entry(be, component));
            }
        }
        return result;
    }

    /**
     * Makes labels unique among the loaded members after a group forms or merges. In {@link #components} order the
     * first holder of a label keeps it; each later holder is renamed to the next free {@code PREFIX-n} when its
     * label has that form, otherwise to {@code label-2}, {@code label-3}... (the base is shortened to fit the label
     * length limit). Only loaded members take part and renames only ever move away from a clash, so a member
     * that is unloaded now and loads later is simply handled when its group next forms; nothing is renamed back.
     * Server side only.
     *
     * @return how many components were renamed
     */
    int resolveDuplicateLabels(Level level) {
        if (level.isClientSide()) {
            return 0;
        }
        List<Entry> entries = components(level);
        Set<String> all = new HashSet<>();
        for (Entry entry : entries) {
            all.add(entry.component().label());
        }
        if (all.size() == entries.size()) {
            return 0;
        }
        Set<String> claimed = new HashSet<>();
        Set<PanelBlockEntity> changed = new java.util.LinkedHashSet<>();
        int renamed = 0;
        for (Entry entry : entries) {
            var component = entry.component();
            String label = component.label();
            if (claimed.add(label)) {
                continue;
            }
            String fresh = freshLabel(component, label, all, claimed);
            component.setLabel(fresh);
            component.touch();
            all.add(fresh);
            claimed.add(fresh);
            changed.add(entry.owner());
            renamed++;
        }
        for (PanelBlockEntity be : changed) {
            be.syncFull();
        }
        return renamed;
    }

    private static String freshLabel(dev.bwr.mod.panel.component.PanelComponent component, String label,
                                     Set<String> all, Set<String> claimed) {
        String base = label.isEmpty() ? "X" : label;
        if (component.type() != null) {
            String prefix = component.type().labelPrefix();
            if (label.startsWith(prefix + "-") && label.length() > prefix.length() + 1
                    && label.substring(prefix.length() + 1).chars().allMatch(Character::isDigit)) {
                base = prefix;
            }
        }
        int limit = PanelConfig.maxLabelLength();
        for (int n = base.equals(label) ? 2 : 1; ; n++) {
            String suffix = "-" + n;
            String stem = base.length() + suffix.length() > limit ? base.substring(0, Math.max(0, limit - suffix.length())) : base;
            String candidate = stem + suffix;
            if (!all.contains(candidate) && !claimed.contains(candidate)) {
                return candidate;
            }
        }
    }
}
