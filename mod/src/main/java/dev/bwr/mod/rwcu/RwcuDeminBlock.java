package dev.bwr.mod.rwcu;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import java.util.List;

/**
 * The RWCU stainless steel nanofilter (id {@code rwcu_filter_demineralizer}, the unit that was the resin filter-demineraliser): a
 * consumable element in line that takes crud and dissolved ions at any water temperature. Right-click with the Stainless Steel
 * Nanofilter Element (item id {@code demineralizer_resin}) to change it; the old element comes out as a radioactive Spent
 * Nanofilter Element (id {@code spent_resin}).
 */
public class RwcuDeminBlock extends RwcuUnitBlock {
    public static final MapCodec<RwcuDeminBlock> CODEC = simpleCodec(RwcuDeminBlock::new);
    public RwcuDeminBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<RwcuDeminBlock> codec() { return CODEC; }
    @Override public Role roleAt(BlockState s, Direction f) {
        Direction front = s.getValue(FACING);
        return f == front ? Role.BED_IN : f == front.getOpposite() ? Role.BED_OUT : null;
    }
    @Override public Direction faceOf(BlockState s, Role r) {
        Direction front = s.getValue(FACING);
        return r == Role.BED_IN ? front : r == Role.BED_OUT ? front.getOpposite() : null;
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState s, Level l, BlockPos p, Player player,
                                                                          net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(dev.bwr.mod.registry.BwrItems.DEMINERALIZER_RESIN.get()))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!l.isClientSide() && l.getBlockEntity(p) instanceof RwcuDeminBlockEntity be) be.changeBed(player, stack);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RwcuDeminBlockEntity(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit) {
        if (!l.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) dev.bwr.mod.gui.RwcuUnitMenu.open(sp, p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.rwcu_demin.line1", "Inlet in front, outlet at the back. Holds 100 g of dissolved ions and 200 g of crud."));
        lines.add(BwrText.tr("tooltip.bwr.rwcu_demin.line2", "Works at reactor temperature. Change the element with a Stainless Steel Nanofilter Element."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
