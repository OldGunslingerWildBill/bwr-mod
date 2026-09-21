package dev.bwr.mod.piping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import java.util.List;

/** Shared dye interaction, with blockstate persistence and no per-segment block entity. */
public abstract class PaintedPipeBlock extends PipeBlock {
    public static final EnumProperty<PipePaint> PAINT = EnumProperty.create("paint", PipePaint.class);

    protected PaintedPipeBlock(Properties properties) {
        super(.25F, properties.noOcclusion());
        BlockState state = stateDefinition.any().setValue(PAINT, PipePaint.NONE);
        for (Direction d : Direction.values()) state = state.setValue(PROPERTY_BY_DIRECTION.get(d), false);
        registerDefaultState(state);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction d : Direction.values()) builder.add(PROPERTY_BY_DIRECTION.get(d));
        builder.add(PAINT);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                                       BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof DyeItem dye)) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        PipePaint target = PipePaint.of(dye.getDyeColor());
        if (!level.isClientSide() && state.getValue(PAINT) != target && player.mayBuild()) {
            if (level.setBlock(pos, state.setValue(PAINT, target), 3) && !player.getAbilities().instabuild) stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.pipe.dye"));
    }
}
