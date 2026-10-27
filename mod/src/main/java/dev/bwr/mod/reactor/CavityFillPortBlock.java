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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * A fill line into the reactor well: the "condensate transfer to the cavity" connection of a real plant (which floods the cavity with
 * RHR in LPCI mode or condensate transfer). It is the mirror of the {@link CavityDrainPortBlock}: set in the well wall or floor at or
 * above the cavity seal, its flange (FACING) out of the well, the face behind it wet. A pump's discharge piped to it (feed, condensate,
 * RHR or core spray pumps) delivers straight into the well, and a Condensate Storage Tank higher than the water fills it by gravity
 * through a Motor-Operated Water Valve. The formed reactor steps it (see {@link CavityFillPortBlockEntity}); it has no ticker.
 */
public class CavityFillPortBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<CavityFillPortBlock> CODEC = simpleCodec(CavityFillPortBlock::new);

    public CavityFillPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CavityFillPortBlockEntity(pos, state); }

    /** Placed looking out of the well, the pipe flange points away from the placer. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }
    @Override protected BlockState rotate(BlockState state, Rotation r) { return state.setValue(FACING, r.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror m) { return state.setValue(FACING, m.mirror(state.getValue(FACING))); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state != old) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
        // listed with the drain ports, so the reactor finds it without scanning (a chunk load lists it through the block entity's onLoad)
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
        if (level.getBlockEntity(pos) instanceof CavityFillPortBlockEntity port) {
            for (Component line : port.statusComponents()) player.displayClientMessage(line, false);
        }
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.cavity_fill_port.placement",
                "Set in the wall or floor of the reactor well, at or above the cavity seal. The flange points out of the well; place it looking out."));
        lines.add(BwrText.tr("tooltip.bwr.cavity_fill_port.line",
                "Pipe a pump's discharge to it, or a higher Condensate Storage Tank through a Motor-Operated Water Valve: the water goes straight into the well."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
