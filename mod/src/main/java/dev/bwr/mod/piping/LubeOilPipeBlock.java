package dev.bwr.mod.piping;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * The Lube Oil Pipe: a small-bore, flanged, brown line for the turbine lube oil system (TURBINE-AUX.md, PIPES.md). It is a network of its
 * own: it joins other lube oil pipes, valves, filter housings and the oil ports of the oil equipment, and nothing else, so it never
 * touches a water or steam line ({@link OilLineNetwork#acceptsLineOn} is the one rule, here for the arms and in the survey for the
 * flow). Dye works as on the other pipes (the {@link PaintedPipeBlock} base): only the paint changes colour.
 */
public class LubeOilPipeBlock extends PaintedPipeBlock implements SimpleWaterloggedBlock {
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final MapCodec<LubeOilPipeBlock> CODEC = simpleCodec(LubeOilPipeBlock::new);

    public LubeOilPipeBlock(Properties properties) {
        super(properties);
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values()) state = state.setValue(PROPERTY_BY_DIRECTION.get(d), false);
        registerDefaultState(state.setValue(WATERLOGGED, false));
    }

    @Override protected MapCodec<? extends PipeBlock> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WATERLOGGED);
    }

    private static boolean connects(BlockState neighbour, Direction towards) {
        return OilLineNetwork.acceptsLineOn(neighbour, towards.getOpposite());
    }

    /** A pipe's own state with every arm resolved against what is around {@code pos}. */
    public BlockState stateWithConnections(BlockGetter level, BlockPos pos) {
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values())
            state = state.setValue(PROPERTY_BY_DIRECTION.get(d), connects(level.getBlockState(pos.relative(d)), d));
        return state.setValue(WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return stateWithConnections(context.getLevel(), context.getClickedPos());
    }

    @Override protected BlockState updateShape(BlockState state, Direction d, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return state.setValue(PROPERTY_BY_DIRECTION.get(d), connects(neighbour, d));
    }

    @Override protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState out = state;
        for (Direction d : Direction.values()) out = out.setValue(PROPERTY_BY_DIRECTION.get(rotation.rotate(d)), state.getValue(PROPERTY_BY_DIRECTION.get(d)));
        return out;
    }

    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state;
        for (Direction d : Direction.values()) out = out.setValue(PROPERTY_BY_DIRECTION.get(mirror.mirror(d)), state.getValue(PROPERTY_BY_DIRECTION.get(d)));
        return out;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.lube_oil_pipe.service"));
        lines.add(Component.translatable("tooltip.bwr.lube_oil_pipe.network"));
        super.appendHoverText(stack, context, lines, flag);
    }
}
