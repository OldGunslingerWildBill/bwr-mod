package dev.bwr.mod.lighting;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Industrial high-bay pendant: a dome hung from a ceiling on a short canopy (turbine halls, reactor buildings). It mounts
 * only under a solid block. Shape in sixteenths of a block; the model is authored the same way.
 */
public final class HighBayLampBlock extends IndustrialLampBlock {
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(6, 12, 6, 10, 16, 10),
            Block.box(3, 4, 3, 13, 12, 13),
            Block.box(4, 2, 4, 12, 4, 12));

    private final LampType lampType;

    public HighBayLampBlock(Properties properties, LampType type) {
        super(properties, type);
        this.lampType = type;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(p -> new HighBayLampBlock(p, lampType));
    }

    @Override
    protected boolean hasSupport(LevelReader level, BlockPos pos, BlockState state) {
        BlockPos above = pos.above();
        return level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        return canSurvive(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
