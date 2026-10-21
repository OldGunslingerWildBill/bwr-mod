package dev.bwr.mod.panel.board;

import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.board.BoardTemplate.Frame;
import dev.bwr.mod.panel.board.BoardTemplate.Kind;
import dev.bwr.mod.panel.board.BoardTemplate.Surface;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link BoardTemplate} put on the map: which block states go to which positions for an origin and a facing, and
 * where on the map each cell of each surface is. Pure arithmetic over the template (no world), so the server places
 * from it, the client draws the ghost from it and the tests compare against it.
 *
 * <p>{@code facing} is the way the panel faces, toward the operator (the way a furnace faces the player who placed it).
 * The operator's right is {@code facing.getCounterClockWise()}, the same direction the panel groups run in.
 */
public final class BoardPlan {
    /** One block of the board. {@code item} is the item that pays for it (null for the body cells of a duplex column). */
    public record Block(BlockPos pos, BlockState state, Item item) {
    }

    /** The block a cell of a surface belongs to and the cell inside that block (0 to 15 each way). */
    public record Anchor(BlockPos pos, int cx, int cy) {
    }

    private final BoardTemplate template;
    private final BlockPos origin;
    private final Direction facing;
    private final List<Block> blocks = new ArrayList<>();

    public BoardPlan(BoardTemplate template, BlockPos origin, Direction facing) {
        this.template = template;
        this.origin = origin.immutable();
        this.facing = facing;
        for (Frame frame : template.frames()) {
            addFrame(frame);
        }
    }

    public BoardTemplate template() {
        return template;
    }

    public BlockPos origin() {
        return origin;
    }

    public Direction facing() {
        return facing;
    }

    /** Every block, in the order they are placed (a duplex column bench first). */
    public List<Block> blocks() {
        return blocks;
    }

    /** The operator's right. */
    public Direction right() {
        return facing.getCounterClockWise();
    }

    private BlockPos base(Frame frame) {
        return origin.relative(right(), frame.right()).above(frame.up()).relative(facing, frame.forward());
    }

    private void addFrame(Frame frame) {
        BlockPos base = base(frame);
        if (frame.kind() == Kind.DUPLEX) {
            BlockState column = BwrBlocks.CONTROL_PANEL_DUPLEX.get().defaultBlockState().setValue(PanelBlock.FACING, facing);
            for (int c = 0; c < frame.columns(); c++) {
                BlockPos bench = base.relative(right(), c);
                for (DuplexCell cell : DuplexCell.values()) {
                    blocks.add(new Block(cell.at(bench, facing), column.setValue(DuplexPanelBlock.CELL, cell),
                            cell == DuplexCell.BENCH ? BwrItems.CONTROL_PANEL_DUPLEX.get() : null));
                }
            }
            return;
        }
        BlockState state = switch (frame.kind()) {
            case VERTICAL -> BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState();
            case BENCHBOARD -> BwrBlocks.CONTROL_PANEL_BENCHBOARD.get().defaultBlockState();
            case CONSOLE -> BwrBlocks.CONTROL_PANEL_CONSOLE.get().defaultBlockState();
            default -> BwrBlocks.CONTROL_PANEL_BACK.get().defaultBlockState();
        };
        state = state.setValue(PanelBlock.FACING, facing);
        Item item = switch (frame.kind()) {
            case VERTICAL -> BwrItems.CONTROL_PANEL_VERTICAL.get();
            case BENCHBOARD -> BwrItems.CONTROL_PANEL_BENCHBOARD.get();
            case CONSOLE -> BwrItems.CONTROL_PANEL_CONSOLE.get();
            default -> BwrItems.CONTROL_PANEL_BACK.get();
        };
        for (int row = 0; row < frame.rows(); row++) {
            for (int c = 0; c < frame.columns(); c++) {
                blocks.add(new Block(blockOf(frame, base, c, row), state, item));
            }
        }
    }

    /** The block of column {@code c}, row {@code row} of a frame that is not a duplex. */
    private BlockPos blockOf(Frame frame, BlockPos base, int c, int row) {
        BlockPos pos = base.relative(right(), c).below(row);
        return frame.kind() == Kind.BENCHBOARD ? pos.relative(facing, row) : pos;
    }

    /** Which block, and which cell of it, the cell {@code (x, y)} of {@code surface} is. */
    public Anchor anchor(Surface surface, int x, int y) {
        Frame frame = surface.frame();
        BlockPos base = base(frame);
        int column = Math.floorDiv(x, PanelGeometry.GRID);
        int row = Math.floorDiv(y, PanelGeometry.GRID);
        BlockPos pos;
        if (frame.kind() == Kind.DUPLEX) {
            BlockPos bench = base.relative(right(), column);
            DuplexCell cell = switch (surface.part()) {
                case "header" -> DuplexCell.HEADER;
                case "bench" -> DuplexCell.BENCH;
                default -> row == 0 ? DuplexCell.BOARD_UPPER : DuplexCell.BOARD_LOWER;
            };
            pos = cell.at(bench, facing);
        } else {
            pos = blockOf(frame, base, column, row);
        }
        return new Anchor(pos, Math.floorMod(x, PanelGeometry.GRID), Math.floorMod(y, PanelGeometry.GRID));
    }

    /** The block at the top left of a surface (the anchor of the group a mimic path is placed through). */
    public BlockPos corner(Surface surface) {
        return anchor(surface, 0, 0).pos();
    }

    /** The panel blocks that carry a face (the body cells of a duplex column excluded). */
    public List<BlockPos> panelPositions() {
        List<BlockPos> list = new ArrayList<>();
        for (Block block : blocks) {
            if (!(block.state().getBlock() instanceof DuplexPanelBlock) || block.state().getValue(DuplexPanelBlock.CELL).panelType() != null) {
                list.add(block.pos());
            }
        }
        return list;
    }

    /** The smallest box around all the blocks. */
    public AABB bounds() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Block block : blocks) {
            BlockPos pos = block.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /** The panel style of a surface (for the aspect of a mimic path's length). */
    public static PanelType styleOf(Surface surface) {
        return surface.panelType();
    }
}
