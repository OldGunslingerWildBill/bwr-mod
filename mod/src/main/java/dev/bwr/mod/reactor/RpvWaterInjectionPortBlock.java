package dev.bwr.mod.reactor;

import dev.bwr.mod.text.BwrText;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import java.util.List;

/** Pressure-boundary penetration. The facing flange must point out of the vessel. */
public class RpvWaterInjectionPortBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<RpvWaterInjectionPortBlock> CODEC = simpleCodec(RpvWaterInjectionPortBlock::new);
    public RpvWaterInjectionPortBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving) {
        super.onPlace(state,level,pos,old,moving);
        if(state!=old)dev.bwr.mod.piping.PipeTopology.changed(level,pos);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RpvWaterInjectionPortBlockEntity(pos, state); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState state, Rotation r) { return state.setValue(FACING, r.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror m) { return state.setValue(FACING, m.mirror(state.getValue(FACING))); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RpvWaterInjectionPortBlockEntity port) {
            var owner = port.controller();
            player.displayClientMessage((owner == null ? BwrText.tr(
                    "status.bwr.rpv_water_injection_port.port_disconnected_use_a_formed_vessel_wall_outward",
                    "Port disconnected: use a formed vessel wall, outward flange, and the required elevation.") : BwrText.tr(
                    "status.bwr.rpv_water_injection_port.water_port_connected_to_reactor_at",
                    "Water port connected to reactor at %s",
                    owner.getBlockPos().toShortString())), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.water_injection_port"));
    }
}
