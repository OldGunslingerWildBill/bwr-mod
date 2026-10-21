package dev.bwr.mod.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The three panel body styles (CONTROL-PANELS-SPEC section 1). A style fixes the
 * shape of the one mounting face every panel block carries; everything about
 * that face lives in {@link PanelGeometry}.
 *
 * <p>The style also decides which neighbours can join a {@link PanelGroup}: the
 * face plane has to continue unbroken from block to block.
 * <ul>
 *   <li>{@link #VERTICAL}: a flat board; blocks may continue sideways and
 *       up/down.</li>
 *   <li>{@link #BENCHBOARD}: a 45 degree desk; blocks continue sideways, and the
 *       next row of the same slope is one block down and one block toward the
 *       operator.</li>
 *   <li>{@link #CONSOLE}: a 15 degree desk; the slope does not land on the block
 *       grid, so a console group is a single row.</li>
 *   <li>{@link #BACK_PANEL}: a full-depth upright board (the owner's 2 x 2 x 1 m back panel is four of these);
 *       it joins like {@link #VERTICAL}, with the face at the front of the block.</li>
 * </ul>
 *
 * <p>The control desks ({@link #DESK}, the owner's two small Fusion desks) are a body of their own: a level top 0.40 up
 * and 0.375 deep (the grid is 16 columns by {@link PanelGeometry#DESK_ROWS} rows, not 16 by 16), with a raised shelf
 * along the back. They join sideways like the vertical board, but are a single row: there is no block further down
 * the face.
 *
 * <p>The three surfaces of the duplex board ({@link DuplexPanelBlock}, the owner's Fusion control board, one item per
 * 1 x 2 x 4 column) are types of their own, so each joins only the same surface of the neighbouring columns:
 * <ul>
 *   <li>{@link #DUPLEX_BOARD}: the vertical board, two rows per column, face at the front of the block like
 *       {@link #BACK_PANEL};</li>
 *   <li>{@link #DUPLEX_BENCH}: the bench in front of it, falling from 1.0 at the board to 0.75 at the operator
 *       (about 14 degrees), a single row;</li>
 *   <li>{@link #DUPLEX_HEADER}: the tilted annunciator face overhanging the operator, from the board's top edge to
 *       half a block forward one block up (facing forward and down, 26.6 degrees from vertical), a single row.</li>
 * </ul>
 *
 * <p>The angled trim blocks ({@link dev.bwr.mod.panel.trim.MountableTrimBlock}) mount components too, on faces that
 * are neither level nor square to the block:
 * <ul>
 *   <li>{@link #HEADER_TILT}: the annunciator panel of the header strip and the canopy, a full block tall and leaning
 *       back 5 degrees as it rises (normal 5 degrees above the horizon). It joins sideways, like the vertical board,
 *       but is a single row: the next block up would be 0.0875 further back, which is not on the block grid (the same
 *       reason the console is a single row), so a strip never grows upward into another strip;</li>
 *   <li>{@link #CHAMFER_VERTICAL}: the 45 degree facet that fills the inside corner of two vertical boards. The
 *       facet is vertical, so a stack of corner blocks is one tall face (rows), but the next facet sideways is not
 *       on its plane (the facets of a diagonal run of blocks are 0.71 apart along it), so it never joins sideways;
 *       only 11 of the 16 columns lie on the facet ({@link PanelGeometry#cellUsable});</li>
 *   <li>{@link #CHAMFER_BENCH}: the triangular facet that fills the inside corner of two benchboards (normal
 *       pointing up, toward the operator and to the operator's right). A single block: it joins nothing, and only
 *       the cells that lie wholly on the triangle can be used.</li>
 * </ul>
 */
public enum PanelType {
    VERTICAL("control_panel_vertical", "PNL"),
    BENCHBOARD("control_panel_benchboard", "BNC"),
    CONSOLE("control_panel_console", "CON"),
    BACK_PANEL("control_panel_back", "BCK"),
    DUPLEX_BOARD("control_panel_duplex", "DXB"),
    DUPLEX_BENCH("control_panel_duplex", "DXN"),
    DUPLEX_HEADER("control_panel_duplex", "DXH"),
    DESK("control_panel_desk", "DSK"),
    HEADER_TILT("control_panel_header_strip", "HDR"),
    CHAMFER_VERTICAL("control_panel_corner_vertical_chamfer", "CHV"),
    CHAMFER_BENCH("control_panel_corner_bench_chamfer", "CHB");

    private final String registryName;
    private final String shortName;

    PanelType(String registryName, String shortName) {
        this.registryName = registryName;
        this.shortName = shortName;
    }

    public String registryName() {
        return registryName;
    }

    public String shortName() {
        return shortName;
    }

    /** Whether blocks of this style can stack into more than one row. */
    public boolean hasRows() {
        return this != CONSOLE && this != DUPLEX_BENCH && this != DUPLEX_HEADER && this != HEADER_TILT
                && this != CHAMFER_BENCH && this != DESK;
    }

    /**
     * Whether the neighbour to the operator's right or left can continue the face. False for the chamfers, whose face
     * is a diagonal facet that does not meet the facet of the block beside it.
     */
    public boolean hasColumns() {
        return this != CHAMFER_VERTICAL && this != CHAMFER_BENCH;
    }

    /**
     * The block one group row further down the face, or {@code null} when this
     * style is single-row. Row 0 is the top of the face.
     */
    public BlockPos nextRow(BlockPos pos, Direction facing) {
        return switch (this) {
            case VERTICAL, BACK_PANEL, DUPLEX_BOARD, CHAMFER_VERTICAL -> pos.below();
            case BENCHBOARD -> pos.below().relative(facing);
            case CONSOLE, DUPLEX_BENCH, DUPLEX_HEADER, HEADER_TILT, CHAMFER_BENCH, DESK -> null;
        };
    }

    /** The block one group row further up the face, or {@code null}. */
    public BlockPos previousRow(BlockPos pos, Direction facing) {
        return switch (this) {
            case VERTICAL, BACK_PANEL, DUPLEX_BOARD, CHAMFER_VERTICAL -> pos.above();
            case BENCHBOARD -> pos.above().relative(facing.getOpposite());
            case CONSOLE, DUPLEX_BENCH, DUPLEX_HEADER, HEADER_TILT, CHAMFER_BENCH, DESK -> null;
        };
    }

    /** The block one group column to the right, as the operator faces the panel. */
    public static BlockPos nextColumn(BlockPos pos, Direction facing) {
        return pos.relative(facing.getCounterClockWise());
    }

    public static BlockPos previousColumn(BlockPos pos, Direction facing) {
        return pos.relative(facing.getClockWise());
    }
}
