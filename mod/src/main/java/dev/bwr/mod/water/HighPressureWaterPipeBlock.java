package dev.bwr.mod.water;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import java.util.List;

/** Six-way water pipe; its NeoForge capability forwards incoming condensate to suction tanks. */
public class HighPressureWaterPipeBlock extends PipeBlock {
    public static final MapCodec<HighPressureWaterPipeBlock> CODEC = simpleCodec(HighPressureWaterPipeBlock::new);
    public HighPressureWaterPipeBlock(Properties properties) {
        super(.25F, properties);
        BlockState state = stateDefinition.any();
        for (Direction d : Direction.values()) state = state.setValue(PROPERTY_BY_DIRECTION.get(d), false);
        registerDefaultState(state);
    }
    @Override protected MapCodec<? extends PipeBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction d : Direction.values()) builder.add(PROPERTY_BY_DIRECTION.get(d));
    }
    private boolean connects(BlockGetter world, BlockPos pos, Direction face) {
        return world instanceof Level level ? WaterLineNetwork.connectsTo(level, pos, face)
                : WaterLineNetwork.acceptsLineOn(world.getBlockState(pos), face);
    }
    public BlockState stateWithConnections(BlockGetter level, BlockPos pos) {
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values()) state = state.setValue(PROPERTY_BY_DIRECTION.get(d), connects(level, pos.relative(d), d.getOpposite()));
        return state;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return stateWithConnections(context.getLevel(), context.getClickedPos());
    }
    @Override protected BlockState updateShape(BlockState state, Direction d, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(d), connects(level, neighbourPos, d.getOpposite()));
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
        lines.add(Component.translatable("tooltip.bwr.water_pipe.rating"));
        lines.add(Component.translatable("tooltip.bwr.water_pipe.service"));
    }
}
