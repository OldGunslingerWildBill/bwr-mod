package dev.bwr.mod.power;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Turbine Shaft Wreck (TURBINE-MAINTENANCE.md): the rotor half a shaft excursion threw out of an LP, lying on the floor with its axis
 * along {@link #AXIS} (the turbine's shaft). One block where it rests; the renderer draws the stripped, bent section and its sheared
 * coupling around it. The turbine hall crane lifts it onto a blade stand (BLADES OUT), or it is cut up where it lies: broken with a
 * pickaxe it drops as a Damaged LP Blade Section. It has no ticker and is placed only by an excursion (no item).
 */
public class TurbineWreckBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final MapCodec<TurbineWreckBlock> CODEC = simpleCodec(TurbineWreckBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 14, 16);

    public TurbineWreckBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(AXIS);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TurbineWreckBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.displayClientMessage(BwrText.tr("status.bwr.turbine_wreck.describe",
                    "A rotor half thrown out of an LP turbine (62 t). The turbine hall crane lifts it onto a blade stand with BLADES OUT, or break it up with a pickaxe for a Damaged LP Blade Section."), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
