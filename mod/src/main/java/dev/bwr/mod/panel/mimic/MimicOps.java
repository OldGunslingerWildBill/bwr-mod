package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The authoritative, validated server side of the mimic layer (spec 3), reached from the tape's packets, from the
 * vanilla right-click and from the GameTests, so there is one set of rules:
 * <ul>
 *   <li>the player holds the tape (or, for a config edit, the wrench), is in reach of the block and permitted on
 *       every block the path touches;</li>
 *   <li>a path lies on the board: every point, and every segment between them, is over a loaded block of the
 *       clicked block's group (the same type, facing and slope, neighbours in the wall); a path never crosses a
 *       non-coplanar boundary and never loads a chunk;</li>
 *   <li>at most {@link #MAX_POINTS} points to a path and {@link #MAX_PATHS_PER_BLOCK} paths touching a block; a
 *       request over a limit is refused with a message and changes nothing;</li>
 *   <li>a path costs one tape per 32 cells of length (at least one) in survival, taken when it is finished.</li>
 * </ul>
 * Paths are indication only. Nothing here reads a plant value into anything but a colour.
 */
public final class MimicOps {
    private MimicOps() {
    }

    public static final int MAX_POINTS = 64;
    public static final int MAX_PATHS_PER_BLOCK = 128;
    /** Coordinates beyond this many blocks from the first point are refused (a hostile packet, not a board). */
    private static final int MAX_COORDINATE = PanelGeometry.GRID * 64;

    /** What a request did. */
    public enum Result {
        /** A path was placed, or an edit applied. */
        OK,
        /** A point was added to the path being drawn. */
        POINT_ADDED,
        /** The path being drawn was committed. */
        FINISHED,
        /** The path being drawn was dropped (fewer than two points, or its board is gone). */
        CANCELLED,
        /** The edit mode opened a path's config screen on the client. */
        OPENED,
        NOT_HOLDING_TAPE, OUT_OF_REACH, NOT_PERMITTED, NOT_A_PANEL, BAD_ARGUMENTS, NEED_TAPE, TOO_MANY_POINTS,
        TOO_MANY_PATHS, OFF_BOARD, NOT_LOADED, NO_PATH, SAME_POINT
    }

    /** The outcome of placing a path: the verdict and, when OK, its shared id and how many blocks got a copy. */
    public record Placed(Result result, long pathId, int blocks) {
    }

    // ---- the tape on a panel face

    /**
     * The tape in {@code hand} used on the panel block at {@code clicked}, with the cursor at {@code (cu, cv)} cells
     * from the top-left of that block's face (continuous, may be a little outside 0..16 at an edge).
     */
    public static Result use(ServerPlayer player, InteractionHand hand, BlockPos clicked, double cu, double cv) {
        ServerLevel level = player.serverLevel();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MimicTapeItem)) {
            return Result.NOT_HOLDING_TAPE;
        }
        if (!Double.isFinite(cu) || !Double.isFinite(cv) || cu < -0.5 || cv < -0.5
                || cu > PanelGeometry.GRID + 0.5 || cv > PanelGeometry.GRID + 0.5) {
            return Result.BAD_ARGUMENTS;
        }
        if (!PanelOps.inReach(player, clicked) || !level.isLoaded(clicked)) {
            return Result.OUT_OF_REACH;
        }
        if (!AssemblyAccess.permitted(level, player, clicked)) {
            say(player, BwrText.tr("message.bwr.mimic_tape.not_permitted", "You cannot modify this board"));
            return Result.NOT_PERMITTED;
        }
        if (!(level.getBlockEntity(clicked) instanceof PanelBlockEntity block)) {
            return Result.NOT_A_PANEL;
        }
        return switch (MimicTapeItem.mode(stack)) {
            case DRAW -> draw(player, stack, block, cu, cv);
            case ERASE -> erase(player, block, cu, cv);
            case EDIT -> edit(player, block, cu, cv);
        };
    }

    private static Result draw(ServerPlayer player, ItemStack stack, PanelBlockEntity block, double cu, double cv) {
        ServerLevel level = player.serverLevel();
        PanelGroup group = block.group();
        if (group == null || group.oversize()) {
            return Result.NOT_A_PANEL;
        }
        MimicDraft draft = MimicTapeItem.draft(stack);
        if (draft != null && !(level.isLoaded(draft.anchor()) && group.contains(draft.anchor()))) {
            // Another plane (another board, another slope, another wall): it finishes the path being drawn.
            return finish(player, stack);
        }
        BlockPos anchor = draft != null ? draft.anchor() : block.getBlockPos();
        boolean hasLast = draft != null;
        int lastX = hasLast ? draft.x(draft.pointCount() - 1) : 0;
        int lastY = hasLast ? draft.y(draft.pointCount() - 1) : 0;
        int[] point = target(group, block.getBlockPos(), draft, cu, cv, player.isShiftKeyDown());
        if (hasLast && point[0] == lastX && point[1] == lastY) {
            if (player.isShiftKeyDown()) {
                return finish(player, stack);
            }
            return Result.SAME_POINT;
        }
        if (hasLast && draft.pointCount() >= MAX_POINTS) {
            say(player, BwrText.tr("message.bwr.mimic_tape.too_many_points", "A path has at most %s points; finish it", MAX_POINTS));
            return Result.TOO_MANY_POINTS;
        }
        boolean onBoard = hasLast ? segmentOnBoard(group, anchor, lastX, lastY, point[0], point[1])
                : pointOnBoard(group, anchor, point[0], point[1]);
        if (!onBoard) {
            say(player, BwrText.tr("message.bwr.mimic_tape.off_board", "That point is off the board"));
            return Result.OFF_BOARD;
        }
        MimicDraft next = hasLast ? draft.with(point[0], point[1]) : new MimicDraft(anchor, List.of(point[0], point[1]));
        stack.set(BwrDataComponents.MIMIC_DRAFT.get(), next);
        double aspect = PanelGeometry.frame(block.panelType(), block.facing()).vLength;
        say(player, BwrText.tr("message.bwr.mimic_tape.point", "Point %s, %s cells long", next.pointCount(),
                Math.round(MimicGeometry.length(next.toArray(), aspect))));
        return Result.POINT_ADDED;
    }

    /**
     * The grid point a click at {@code (cu, cv)} of the block at {@code clicked} lands on: in the cell space of the
     * draft's first block (of the clicked block when nothing is being drawn), snapped as {@link MimicGeometry#snap}
     * says. The client's preview calls this too, so it shows the point the server will take.
     */
    public static int[] target(PanelGroup group, BlockPos clicked, MimicDraft draft, double cu, double cv, boolean free) {
        BlockPos anchor = draft != null ? draft.anchor() : clicked;
        int columnOffset = group.columnOf(clicked) - group.columnOf(anchor);
        int rowOffset = group.rowOf(clicked) - group.rowOf(anchor);
        double ax = cu + PanelGeometry.GRID * columnOffset;
        double ay = cv + PanelGeometry.GRID * rowOffset;
        boolean hasLast = draft != null;
        int lastX = hasLast ? draft.x(draft.pointCount() - 1) : 0;
        int lastY = hasLast ? draft.y(draft.pointCount() - 1) : 0;
        return MimicGeometry.snap(hasLast, lastX, lastY, ax, ay, free);
    }

    /**
     * Finishes the path being drawn with {@code stack}: it is placed (and tape taken in survival) when it has two
     * points or more, otherwise dropped. A path that cannot be placed now (not enough tape, a block over its limit)
     * stays in the stack so the player can fix that and finish it.
     */
    public static Result finish(ServerPlayer player, ItemStack stack) {
        MimicDraft draft = MimicTapeItem.draft(stack);
        if (draft == null) {
            return Result.CANCELLED;
        }
        ServerLevel level = player.serverLevel();
        int[] points = draft.toArray();
        if (points.length < 4) {
            stack.remove(BwrDataComponents.MIMIC_DRAFT.get());
            say(player, BwrText.tr("message.bwr.mimic_tape.cancelled", "Path cancelled"));
            return Result.CANCELLED;
        }
        if (!level.isLoaded(draft.anchor()) || !(level.getBlockEntity(draft.anchor()) instanceof PanelBlockEntity anchor)) {
            stack.remove(BwrDataComponents.MIMIC_DRAFT.get());
            say(player, BwrText.tr("message.bwr.mimic_tape.not_loaded", "The board is not loaded; path cancelled"));
            return Result.NOT_LOADED;
        }
        double aspect = PanelGeometry.frame(anchor.panelType(), anchor.facing()).vLength;
        int cost = MimicGeometry.cost(MimicGeometry.length(points, aspect));
        boolean free = player.getAbilities().instabuild;
        if (!free && stack.getCount() < cost) {
            say(player, BwrText.tr("message.bwr.mimic_tape.need_tape", "This path needs %s tape (you hold %s)", cost, stack.getCount()));
            return Result.NEED_TAPE;
        }
        Placed placed = place(level, player, draft.anchor(), points, new MimicStyle());
        if (placed.result() != Result.OK) {
            return placed.result();
        }
        stack.remove(BwrDataComponents.MIMIC_DRAFT.get());
        if (!free) {
            stack.shrink(cost);
        }
        say(player, BwrText.tr("message.bwr.mimic_tape.finished", "Path drawn: %s points, %s cells, %s tape", points.length / 2,
                Math.round(MimicGeometry.length(points, aspect)), free ? 0 : cost));
        return Result.FINISHED;
    }

    /** Drops the unfinished path of a stack (a mode switch). */
    public static void cancelDraft(ServerPlayer player, ItemStack stack) {
        if (MimicTapeItem.draft(stack) != null) {
            stack.remove(BwrDataComponents.MIMIC_DRAFT.get());
            say(player, BwrText.tr("message.bwr.mimic_tape.cancelled", "Path cancelled"));
        }
    }

    // ---- placing

    /**
     * Puts a finished path on the board whose block is {@code anchor}: {@code points} are flat grid coordinates in
     * that block's cell space. A copy goes into every block of the group the path touches (its width and decorations
     * included). Nothing changes when any check fails. {@code player} may be null (tests and tools): then only the
     * geometry and the limits are checked.
     */
    public static Placed place(ServerLevel level, ServerPlayer player, BlockPos anchor, int[] points, MimicStyle style) {
        if (points.length < 4 || points.length % 2 != 0 || points.length > MAX_POINTS * 2
                || MimicGeometry.hasZeroSegment(points)) {
            return new Placed(Result.BAD_ARGUMENTS, 0L, 0);
        }
        for (int value : points) {
            if (Math.abs(value) > MAX_COORDINATE) {
                return new Placed(Result.BAD_ARGUMENTS, 0L, 0);
            }
        }
        if (!level.isLoaded(anchor) || !(level.getBlockEntity(anchor) instanceof PanelBlockEntity anchorBlock)) {
            return new Placed(Result.NOT_A_PANEL, 0L, 0);
        }
        PanelGroup group = anchorBlock.group();
        if (group == null || group.oversize()) {
            return new Placed(Result.NOT_A_PANEL, 0L, 0);
        }
        for (int i = 0; i < points.length; i += 2) {
            boolean ok = i == 0 ? pointOnBoard(group, anchor, points[0], points[1])
                    : segmentOnBoard(group, anchor, points[i - 2], points[i - 1], points[i], points[i + 1]);
            if (!ok) {
                say(player, BwrText.tr("message.bwr.mimic_tape.off_board", "That point is off the board"));
                return new Placed(Result.OFF_BOARD, 0L, 0);
            }
        }
        List<PanelBlockEntity> touched = touchedBlocks(level, group, anchor, points, style.width);
        if (touched.isEmpty()) {
            return new Placed(Result.OFF_BOARD, 0L, 0);
        }
        for (PanelBlockEntity block : touched) {
            if (player != null && !AssemblyAccess.permitted(level, player, block.getBlockPos())) {
                say(player, BwrText.tr("message.bwr.mimic_tape.not_permitted", "You cannot modify this board"));
                return new Placed(Result.NOT_PERMITTED, 0L, 0);
            }
            if (block.mimicCount() >= MAX_PATHS_PER_BLOCK) {
                say(player, BwrText.tr("message.bwr.mimic_tape.too_many_paths", "A panel block holds at most %s paths", MAX_PATHS_PER_BLOCK));
                return new Placed(Result.TOO_MANY_PATHS, 0L, 0);
            }
        }
        long pathId = 0L;
        while (pathId == 0L) {
            pathId = level.getRandom().nextLong();
        }
        int anchorColumn = group.columnOf(anchor);
        int anchorRow = group.rowOf(anchor);
        for (PanelBlockEntity block : touched) {
            int dx = PanelGeometry.GRID * (anchorColumn - group.columnOf(block.getBlockPos()));
            int dy = PanelGeometry.GRID * (anchorRow - group.rowOf(block.getBlockPos()));
            block.addMimicPath(pathId, MimicGeometry.shifted(points, dx, dy), style.copy());
        }
        for (PanelBlockEntity block : touched) {
            block.syncFull();
        }
        return new Placed(Result.OK, pathId, touched.size());
    }

    /** Every loaded block of the group that a path with these points and this width draws on. */
    private static List<PanelBlockEntity> touchedBlocks(ServerLevel level, PanelGroup group, BlockPos anchor, int[] points,
                                                        int width) {
        int[] bounds = MimicGeometry.bounds(points);
        int reach = (int) Math.ceil(MimicStyle.reach(width));
        int anchorColumn = group.columnOf(anchor);
        int anchorRow = group.rowOf(anchor);
        int firstColumn = Math.floorDiv(bounds[0] - reach, PanelGeometry.GRID);
        int lastColumn = Math.floorDiv(bounds[2] + reach, PanelGeometry.GRID);
        int firstRow = Math.floorDiv(bounds[1] - reach, PanelGeometry.GRID);
        int lastRow = Math.floorDiv(bounds[3] + reach, PanelGeometry.GRID);
        List<PanelBlockEntity> touched = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                BlockPos pos = group.blockAt(anchorColumn + column, anchorRow + row);
                if (pos != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity block) {
                    touched.add(block);
                }
            }
        }
        return touched;
    }

    /** Whether the grid point lies over a block of the group (a point on a shared edge counts for either block). */
    static boolean pointOnBoard(PanelGroup group, BlockPos anchor, double x, double y) {
        int anchorColumn = group.columnOf(anchor);
        int anchorRow = group.rowOf(anchor);
        int column = (int) Math.floor(x / PanelGeometry.GRID);
        int row = (int) Math.floor(y / PanelGeometry.GRID);
        boolean onVerticalEdge = x == Math.rint(x / PanelGeometry.GRID) * PanelGeometry.GRID;
        boolean onHorizontalEdge = y == Math.rint(y / PanelGeometry.GRID) * PanelGeometry.GRID;
        for (int c = onVerticalEdge ? column - 1 : column; c <= column; c++) {
            for (int r = onHorizontalEdge ? row - 1 : row; r <= row; r++) {
                if (group.blockAt(anchorColumn + c, anchorRow + r) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a straight segment between two grid points stays over blocks of the group all the way. */
    static boolean segmentOnBoard(PanelGroup group, BlockPos anchor, int ax, int ay, int bx, int by) {
        if (!pointOnBoard(group, anchor, bx, by) || !pointOnBoard(group, anchor, ax, ay)) {
            return false;
        }
        double length = Math.sqrt((double) (bx - ax) * (bx - ax) + (double) (by - ay) * (by - ay));
        int steps = (int) Math.ceil(length * 2.0);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            if (!pointOnBoard(group, anchor, ax + (bx - ax) * t, ay + (by - ay) * t)) {
                return false;
            }
        }
        return true;
    }

    // ---- erase and edit

    private static Result erase(ServerPlayer player, PanelBlockEntity block, double cu, double cv) {
        MimicPath path = pick(block, cu, cv);
        if (path == null) {
            say(player, BwrText.tr("message.bwr.mimic_tape.nothing_here", "No path there"));
            return Result.NO_PATH;
        }
        return erasePath(player, block, path.pathId());
    }

    /** Removes every copy of a path from the loaded blocks of the group, if the player may touch all of them. */
    public static Result erasePath(ServerPlayer player, PanelBlockEntity block, long pathId) {
        ServerLevel level = player.serverLevel();
        List<PanelBlockEntity> copies = copies(level, block, pathId);
        if (copies.isEmpty()) {
            return Result.NO_PATH;
        }
        for (PanelBlockEntity copy : copies) {
            if (!AssemblyAccess.permitted(level, player, copy.getBlockPos())) {
                say(player, BwrText.tr("message.bwr.mimic_tape.not_permitted", "You cannot modify this board"));
                return Result.NOT_PERMITTED;
            }
        }
        for (PanelBlockEntity copy : copies) {
            copy.removeMimicPath(pathId);
            copy.syncFull();
        }
        say(player, BwrText.tr("message.bwr.mimic_tape.erased", "Path erased"));
        return Result.OK;
    }

    private static Result edit(ServerPlayer player, PanelBlockEntity block, double cu, double cv) {
        MimicPath path = pick(block, cu, cv);
        if (path == null) {
            say(player, BwrText.tr("message.bwr.mimic_tape.nothing_here", "No path there"));
            return Result.NO_PATH;
        }
        PacketDistributor.sendToPlayer(player, new MimicPayloads.OpenConfig(block.getBlockPos(), path.localId()));
        return Result.OPENED;
    }

    /** The path under a cursor on this block, by the same rule the client uses for its highlight. */
    public static MimicPath pick(PanelBlockEntity block, double cu, double cv) {
        double aspect = PanelGeometry.frame(block.panelType(), block.facing()).vLength;
        return MimicGeometry.pick(block.mimicPaths(), cu, cv, aspect);
    }

    /** The loaded blocks of the block's group that hold a copy of the path, the given block first. */
    public static List<PanelBlockEntity> copies(ServerLevel level, PanelBlockEntity block, long pathId) {
        List<PanelBlockEntity> copies = new ArrayList<>();
        if (block.mimicByPathId(pathId) != null) {
            copies.add(block);
        }
        PanelGroup group = block.group();
        if (group != null) {
            for (BlockPos pos : group.members()) {
                if (!pos.equals(block.getBlockPos()) && level.isLoaded(pos)
                        && level.getBlockEntity(pos) instanceof PanelBlockEntity other && other.mimicByPathId(pathId) != null) {
                    copies.add(other);
                }
            }
        }
        return copies;
    }

    // ---- style and binding

    /** Applies a config edit to every copy of a path. The player holds the tape or the wrench. */
    public static boolean configure(ServerPlayer player, BlockPos pos, int localId, CompoundTag edit) {
        ServerLevel level = player.serverLevel();
        boolean holding = !MimicTapeItem.held(player).isEmpty() || !PanelOps.heldWrench(player).isEmpty();
        if (!holding || !PanelOps.inReach(player, pos) || !level.isLoaded(pos)
                || !AssemblyAccess.permitted(level, player, pos)
                || !(level.getBlockEntity(pos) instanceof PanelBlockEntity block)) {
            return false;
        }
        MimicPath path = block.mimicPath(localId);
        if (path == null) {
            return false;
        }
        List<PanelBlockEntity> copies = copies(level, block, path.pathId());
        for (PanelBlockEntity copy : copies) {
            if (!AssemblyAccess.permitted(level, player, copy.getBlockPos())) {
                return false;
            }
        }
        for (PanelBlockEntity copy : copies) {
            copy.mimicByPathId(path.pathId()).style().apply(edit);
            copy.mimicChanged();
            copy.syncFull();
        }
        return true;
    }

    /** A bind request addressed to a path ({@code -localId}); see {@link WiringOps#bind}. */
    public static WiringOps.Result bind(ServerPlayer player, ServerLevel level, ItemStack linker, PanelBlockEntity owner,
                                        PanelPayloads.Bind request) {
        MimicPath path = owner.mimicPath(-request.id());
        if (path == null) {
            return WiringOps.Result.NO_SUCH_COMPONENT;
        }
        if (request.output() || request.port() != 0) {
            return WiringOps.Result.BAD_PORT;
        }
        WiringOps.BindKind kind = WiringOps.BindKind.byId(request.kind());
        PlantSource source;
        if (kind == WiringOps.BindKind.NONE) {
            source = null;
        } else if (kind == WiringOps.BindKind.PLANT) {
            WiringOps.Result checked = WiringOps.checkDevice(player, level, linker, owner.getBlockPos(), request, false);
            if (checked != WiringOps.Result.OK) {
                return checked;
            }
            source = new PlantSource(request.device(), request.key());
        } else {
            // A path is lit by a plant signal only: a redstone input would read a different block's back face in
            // each copy of a path that crosses blocks, and a component or CC value has no use on a line.
            return WiringOps.Result.BAD_VALUE;
        }
        List<PanelBlockEntity> copies = copies(level, owner, path.pathId());
        for (PanelBlockEntity copy : copies) {
            if (!AssemblyAccess.permitted(level, player, copy.getBlockPos())) {
                return WiringOps.Result.NOT_PERMITTED;
            }
        }
        for (PanelBlockEntity copy : copies) {
            MimicPath target = copy.mimicByPathId(path.pathId());
            target.setSource(source == null ? dev.bwr.mod.panel.component.InputSources.NONE : source);
            copy.resampleMimic(target);
            copy.mimicChanged();
            copy.syncFull();
        }
        return WiringOps.Result.OK;
    }

    // ---- the tape's mode

    /** Moves the mode of the tape in {@code hand} on by {@code steps}; an unfinished path is dropped. */
    public static boolean cycleMode(ServerPlayer player, InteractionHand hand, int steps) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MimicTapeItem)) {
            return false;
        }
        MimicMode next = MimicTapeItem.mode(stack).step(steps);
        cancelDraft(player, stack);
        MimicTapeItem.setMode(stack, next);
        say(player, BwrText.tr("message.bwr.mimic_tape.mode", "Mimic tape: %s", Component.translatable(next.translationKey())));
        return true;
    }

    private static void say(ServerPlayer player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }
}
