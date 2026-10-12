package dev.bwr.mod.containment;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Wall-mounted atmosphere equipment; the vent's front is the discharge side. */
public final class ContainmentEquipmentBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final MapCodec<ContainmentEquipmentBlock> NITROGEN_CODEC =
            simpleCodec(p -> new ContainmentEquipmentBlock(p, false));
    private static final MapCodec<ContainmentEquipmentBlock> VENT_CODEC =
            simpleCodec(p -> new ContainmentEquipmentBlock(p, true));
    private final boolean vent;

    public ContainmentEquipmentBlock(Properties properties, boolean vent) {
        super(properties);
        this.vent = vent;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public boolean isVent() { return vent; }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return vent ? VENT_CODEC : NITROGEN_CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public ContainmentEquipmentBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ContainmentEquipmentBlockEntity(pos, state);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }
    @Override protected ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (vent || !stack.is(BwrItems.NITROGEN_CANISTER.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ContainmentEquipmentBlockEntity equipment && equipment.refill()) {
            if (!player.getAbilities().instabuild) stack.shrink(1);
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
