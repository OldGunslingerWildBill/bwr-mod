package dev.bwr.mod.fabrication;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.gui.FabricationDeskMenu;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The fabrication desk: a bench for the control-room parts (FABRICATION-DESK.md). Right-click opens its screen, where a part
 * is picked from a list and made out of materials in the player's inventory. No block entity, no ticker, nothing stored in the
 * block: the screen and the server's crafting check are the whole of it.
 */
public final class FabricationDeskBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<FabricationDeskBlock> CODEC = simpleCodec(FabricationDeskBlock::new);

    /** Written for facing north (the operator stands at -z), in sixteenths: the bench and the pegboard at its back. */
    private static final double[][] BOXES = {{0, 0, 0, 16, 14, 16}, {0, 14, 14, 16, 16, 15}};
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : BOXES) {
                double x0 = b[0], x1 = b[3], z0 = b[2], z1 = b[5];
                double[] r = switch (facing) {
                    case EAST -> new double[] {16 - z1, 16 - z0, x0, x1};
                    case SOUTH -> new double[] {16 - x1, 16 - x0, 16 - z1, 16 - z0};
                    case WEST -> new double[] {z0, z1, 16 - x1, 16 - x0};
                    default -> new double[] {x0, x1, z0, z1};
                };
                shape = Shapes.or(shape, Block.box(r[0], b[1], r[2], r[1], b[4], r[3]));
            }
            SHAPES[facing.get2DDataValue()] = shape;
        }
    }

    public FabricationDeskBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.NORMAL));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp && mayUse(sp)) {
            FabricationDeskMenu.open(sp, pos);
        }
        return InteractionResult.CONSUME;
    }

    /** The same gate as operating any control: everyone but a spectator, so adventure maps work. The server checks it again at every craft. */
    public static boolean mayUse(Player player) {
        return AssemblyAccess.mayOperate(player);
    }
}
