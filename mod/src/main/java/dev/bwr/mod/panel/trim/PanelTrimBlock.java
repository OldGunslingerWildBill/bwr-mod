package dev.bwr.mod.panel.trim;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.panel.PanelFinish;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * A decorative control-room trim block: a vent header, a plinth or a corner filler (end caps are
 * {@link PanelEndCapBlock}; the header strip, canopy and chamfer corners are {@link MountableTrimBlock}s). It has a
 * facing and nothing else: no block entity, no ticker, no mounting face and no interaction, so a whole room of them
 * costs only its model.
 *
 * <p>The facing is the direction the operator stands in, as on a panel block, so placing one looks like placing a
 * panel: the model's front is toward the player.
 */
public class PanelTrimBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final Map<TrimPiece, MapCodec<PanelTrimBlock>> CODECS = new EnumMap<>(TrimPiece.class);

    static {
        for (TrimPiece piece : TrimPiece.values()) {
            CODECS.put(piece, simpleCodec(properties -> new PanelTrimBlock(properties, piece)));
        }
    }

    protected final TrimPiece piece;
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public PanelTrimBlock(Properties properties, TrimPiece piece) {
        super(properties.noOcclusion());
        this.piece = piece;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            shapes.put(facing, TrimGeometry.shape(piece, facing, true));
        }
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public TrimPiece piece() {
        return piece;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODECS.get(piece);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(PanelFinish.FINISH);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos,
                                           CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Pick block gives the item with this block's finish. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target,
                                                                net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, net.minecraft.world.entity.player.Player player) {
        return PanelFinish.withFinish(super.getCloneItemStack(state, target, level, pos, player), PanelFinish.of(state));
    }
}
