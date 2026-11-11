package dev.bwr.mod.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * The four blocks of a 2 x 2 desk corner ({@link DeskCornerBlock}). The origin is the block the player places (the one
 * whose loot table drops the item): the block at the operator's right of the footprint, on the run side of it. The
 * footprint grows from there toward the operator's left ({@code left} 1) and one block {@code deep}: toward the
 * operator for an inside corner, away from the operator (toward the back) for an outside one. The geometry of each
 * block is in {@link DeskCornerGeometry}.
 */
public enum CornerPart implements StringRepresentable {
    ORIGIN("origin", 0, 0),
    LEFT("left", 1, 0),
    DEEP("deep", 0, 1),
    DEEP_LEFT("deep_left", 1, 1);

    private final String name;
    private final int left;
    private final int deep;

    CornerPart(String name, int left, int deep) {
        this.name = name;
        this.left = left;
        this.deep = deep;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public int left() {
        return left;
    }

    public int deep() {
        return deep;
    }

    /** The direction of the {@code deep} step of a corner facing {@code facing}. */
    static Direction deepDirection(Direction facing, boolean inside) {
        return inside ? facing : facing.getOpposite();
    }

    /** This block's position in the corner whose origin is at {@code origin}. */
    public BlockPos at(BlockPos origin, Direction facing, boolean inside) {
        return origin.relative(facing.getClockWise(), left).relative(deepDirection(facing, inside), deep);
    }

    /** The origin of the corner in which this block is at {@code pos}. */
    public BlockPos origin(BlockPos pos, Direction facing, boolean inside) {
        return pos.relative(facing.getCounterClockWise(), left).relative(deepDirection(facing, inside).getOpposite(), deep);
    }
}
