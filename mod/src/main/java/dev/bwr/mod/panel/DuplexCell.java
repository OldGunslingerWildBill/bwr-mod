package dev.bwr.mod.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * The six blocks of one duplex board column ({@link DuplexPanelBlock}), 1 wide, 2 deep and 4 tall. The front blocks
 * stand on the operator's side; the board's face is the plane between the back and the front blocks. Front rows 1 and
 * 2 are not part of the column: they are the operator's space above the bench.
 *
 * <p>The bench is the origin, the block the player places: the rest of the column goes back, away from the player,
 * and up. Declaration order is also the order the column is placed and removed in (the bench, which drops the item,
 * first).
 */
public enum DuplexCell implements StringRepresentable {
    BENCH("bench", 0, 0, PanelType.DUPLEX_BENCH),
    BASE("base", 1, 0, null),
    BOARD_LOWER("board_lower", 1, 1, PanelType.DUPLEX_BOARD),
    BOARD_UPPER("board_upper", 1, 2, PanelType.DUPLEX_BOARD),
    TOP("top", 1, 3, null),
    HEADER("header", 0, 3, PanelType.DUPLEX_HEADER);

    private final String name;
    private final int back;
    private final int up;
    private final PanelType panelType;

    DuplexCell(String name, int back, int up, PanelType panelType) {
        this.name = name;
        this.back = back;
        this.up = up;
        this.panelType = panelType;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** The style of this cell's mounting face, or {@code null} for the body cells nothing mounts on. */
    public PanelType panelType() {
        return panelType;
    }

    /** Blocks behind the front row: 1 for the back blocks. */
    public int back() {
        return back;
    }

    /** Blocks above the floor row. */
    public int up() {
        return up;
    }

    /** This cell's position in the column whose bench is at {@code origin}. */
    public BlockPos at(BlockPos origin, Direction facing) {
        return origin.relative(facing.getOpposite(), back).above(up);
    }

    /** The bench's position of the column in which this cell is at {@code pos}. */
    public BlockPos origin(BlockPos pos, Direction facing) {
        return pos.relative(facing, back).below(up);
    }
}
