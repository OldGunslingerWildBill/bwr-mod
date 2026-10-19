package dev.bwr.mod.panel.trim;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * An end cap: a 2 px cheek with the cross-section of its panel, closing one open side of a run. It stands in the
 * block beside the run's last panel and has a {@link Side}: {@link Side#RIGHT} is the cap at the run's right end
 * (as the operator faces the panels), a plate on the left edge of its own block, against the panel.
 *
 * <p>The side is chosen at placement from what the cap is placed against: a panel block or a trim block with the
 * same facing on its left makes it a right cap, one on its right a left cap, and with neither it is a right cap.
 * Placing while sneaking takes the other side.
 */
public class PanelEndCapBlock extends PanelTrimBlock {
    public static final EnumProperty<Side> SIDE = EnumProperty.create("side", Side.class);

    public enum Side implements StringRepresentable {
        LEFT("left"),
        RIGHT("right");

        private final String serializedName;

        Side(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }

        public Side opposite() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    private static final Map<TrimPiece, MapCodec<PanelEndCapBlock>> CODECS = new EnumMap<>(TrimPiece.class);

    static {
        for (TrimPiece piece : TrimPiece.values()) {
            if (piece.isEndCap()) {
                CODECS.put(piece, simpleCodec(properties -> new PanelEndCapBlock(properties, piece)));
            }
        }
    }

    private final Map<Direction, Map<Side, VoxelShape>> capShapes = new EnumMap<>(Direction.class);

    public PanelEndCapBlock(Properties properties, TrimPiece piece) {
        super(properties, piece);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Map<Side, VoxelShape> bySide = new EnumMap<>(Side.class);
            bySide.put(Side.RIGHT, TrimGeometry.shape(piece, facing, true));
            bySide.put(Side.LEFT, TrimGeometry.shape(piece, facing, false));
            capShapes.put(facing, bySide);
        }
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SIDE, Side.RIGHT));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODECS.get(piece);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return capShapes.get(state.getValue(FACING)).get(state.getValue(SIDE));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Side side = sideAgainstNeighbour(context.getLevel(), context.getClickedPos(), facing);
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            side = side.opposite();
        }
        return defaultBlockState().setValue(FACING, facing).setValue(SIDE, side);
    }

    /** Which end of a run a cap at {@code pos} closes, judged by the panels beside it. */
    static Side sideAgainstNeighbour(LevelReader level, BlockPos pos, Direction facing) {
        if (continuesRun(level.getBlockState(pos.relative(facing.getClockWise())), facing)) {
            return Side.RIGHT;       // a run on the left: this is its right end
        }
        if (continuesRun(level.getBlockState(pos.relative(facing.getCounterClockWise())), facing)) {
            return Side.LEFT;
        }
        return Side.RIGHT;
    }

    private static boolean continuesRun(BlockState neighbour, Direction facing) {
        if (neighbour.getBlock() instanceof dev.bwr.mod.panel.PanelBlock) {
            return neighbour.getValue(dev.bwr.mod.panel.PanelBlock.FACING) == facing;
        }
        return neighbour.getBlock() instanceof PanelTrimBlock && !(neighbour.getBlock() instanceof PanelEndCapBlock)
                && neighbour.getValue(PanelTrimBlock.FACING) == facing;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState rotated = super.mirror(state, mirror);
        // a mirrored cap is on the other side of its (mirrored) facing
        return mirror == Mirror.NONE ? rotated : rotated.setValue(SIDE, state.getValue(SIDE).opposite());
    }
}
