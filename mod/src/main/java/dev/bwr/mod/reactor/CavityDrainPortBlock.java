package dev.bwr.mod.reactor;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * A drain through the wall or floor of the reactor well. Its outward face (FACING) takes High-Pressure Water Pipe; the face
 * behind it is the wet face and must touch a cell of the well's water. It is a plain port: the formed reactor steps it (see
 * {@link CavityDrainPortBlockEntity}); it has no ticker. It passes water only through a motor-operated valve on its line.
 */
public class CavityDrainPortBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<CavityDrainPortBlock> CODEC = simpleCodec(CavityDrainPortBlock::new);

    public CavityDrainPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CavityDrainPortBlockEntity(pos, state); }

    /** Placed looking out of the well, the pipe flange points away from the placer. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }
    @Override protected BlockState rotate(BlockState state, Rotation r) { return state.setValue(FACING, r.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror m) { return state.setValue(FACING, m.mirror(state.getValue(FACING))); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state != old) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
        // listed as soon as it is placed (a chunk load lists it through the block entity's onLoad)
        if (!level.isClientSide()) CavityDrains.add(level, pos);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if (!replacement.is(this)) {
            dev.bwr.mod.piping.PipeTopology.changed(level, pos);
            CavityDrains.remove(level, pos);
        }
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof CavityDrainPortBlockEntity port) {
            for (Component line : port.statusComponents()) player.displayClientMessage(line, false);
        }
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.cavity_drain_port.placement",
                "Set in the wall or floor of the reactor well, at or above the cavity seal. The flange points out of the well; place it looking out."));
        lines.add(BwrText.tr("tooltip.bwr.cavity_drain_port.line",
                "Pipe it to a Condensate Storage Tank through a Motor-Operated Water Valve. Gravity drains the well down to the port's sill."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
