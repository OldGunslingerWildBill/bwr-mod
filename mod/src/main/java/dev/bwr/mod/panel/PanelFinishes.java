package dev.bwr.mod.panel;

import dev.bwr.mod.furniture.AssemblyBlock;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Changing the finish of a placed block (the panel wrench, sneak-click on a bare spot of a panel or on any furniture or
 * trim block). Every block of a multi-block piece (a duplex column, a 2 high cabinet, the supervisor desk, a wall
 * display) changes together: the piece is one thing to the eye. A plain panel or trim block changes alone, so a run
 * can be painted in mixed finishes, and grouping and mounting never look at the finish.
 */
public final class PanelFinishes {
    private PanelFinishes() {
    }

    /** Whether the block of this state has a finish to change. */
    public static boolean hasFinish(BlockState state) {
        return state.hasProperty(PanelFinish.FINISH);
    }

    /** The positions of every block of the piece that the block at {@code pos} belongs to ({@code pos} itself for a single block). */
    public static List<BlockPos> pieceOf(Level level, BlockPos pos, BlockState state) {
        List<BlockPos> cells = new ArrayList<>();
        Block block = state.getBlock();
        if (block instanceof DuplexPanelBlock) {
            Direction facing = state.getValue(PanelBlock.FACING);
            BlockPos origin = DuplexPanelBlock.origin(pos, state);
            for (DuplexCell cell : DuplexCell.values()) {
                cells.add(cell.at(origin, facing));
            }
        } else if (block instanceof DeskPanelBlock) {
            Direction facing = state.getValue(PanelBlock.FACING);
            BlockPos origin = DeskPanelBlock.origin(pos, state);
            for (DeskHalf half : DeskHalf.values()) {
                cells.add(half.at(origin, facing));
            }
        } else if (block instanceof AssemblyBlock assembly && assembly.cellCount() > 1) {
            Direction facing = state.getValue(AssemblyBlock.FACING);
            BlockPos origin = assembly.origin(pos, state);
            for (int i = 0; i < assembly.cellCount(); i++) {
                cells.add(assembly.cellPos(origin, facing, i));
            }
        } else {
            cells.add(pos.immutable());
        }
        return cells;
    }

    /**
     * Sets the finish of the piece at {@code pos} (server side). Only the blocks that really are part of the piece (the
     * same block) change; a protected position anywhere in the piece stops the whole change.
     *
     * @return whether anything changed
     */
    public static boolean set(Level level, BlockPos pos, PanelFinish finish, Player player) {
        BlockState state = level.getBlockState(pos);
        if (level.isClientSide() || !hasFinish(state)) {
            return false;
        }
        List<BlockPos> piece = pieceOf(level, pos, state);
        for (BlockPos cell : piece) {
            BlockState there = level.getBlockState(cell);
            if (there.is(state.getBlock()) && !AssemblyAccess.permitted(level, player, cell)) {
                return false;
            }
        }
        boolean changed = false;
        for (BlockPos cell : piece) {
            BlockState there = level.getBlockState(cell);
            if (there.is(state.getBlock()) && there.getValue(PanelFinish.FINISH) != finish) {
                // UPDATE_CLIENTS only: no neighbour or shape update, so a multi-block piece cannot tear itself down.
                level.setBlock(cell, there.setValue(PanelFinish.FINISH, finish), Block.UPDATE_CLIENTS);
                changed = true;
            }
        }
        return changed;
    }

    /** The wrench gesture: the clicked piece takes the finish after the one of the clicked block. Server side. */
    public static boolean cycle(Level level, BlockPos pos, Player player) {
        BlockState state = level.getBlockState(pos);
        if (!hasFinish(state)) {
            return false;
        }
        PanelFinish next = state.getValue(PanelFinish.FINISH).next();
        boolean changed = set(level, pos, next, player);
        if (changed) {
            player.displayClientMessage(BwrText.tr("message.bwr.panel_finish", "Finish: %s", next.displayName()), true);
        }
        return changed;
    }
}
