package dev.bwr.mod.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * The two blocks of the 2 m control desk ({@link DeskPanelBlock}). The right half, as the operator sees the desk, is
 * the origin: the block the player places and the one whose loot table drops the item. The left half is the block to
 * the operator's left, like the second block of the room furniture's supervisor desk.
 */
public enum DeskHalf implements StringRepresentable {
    RIGHT("right", 0),
    LEFT("left", 1);

    private final String name;
    private final int left;

    DeskHalf(String name, int left) {
        this.name = name;
        this.left = left;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** This half's position in the desk whose origin (right half) is at {@code origin}. */
    public BlockPos at(BlockPos origin, Direction facing) {
        return origin.relative(facing.getClockWise(), left);
    }

    /** The origin of the desk in which this half is at {@code pos}. */
    public BlockPos origin(BlockPos pos, Direction facing) {
        return pos.relative(facing.getCounterClockWise(), left);
    }
}
