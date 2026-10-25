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

/** The regenerative heat exchanger: reactor water from the pump (hot side, front to back) gives its heat to the cleaned water returning to the feedwater line (cold side, left to right). */
public class RwcuRegenBlock extends RwcuUnitBlock {
    public static final MapCodec<RwcuRegenBlock> CODEC = simpleCodec(RwcuRegenBlock::new);
    public RwcuRegenBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<RwcuRegenBlock> codec() { return CODEC; }
    @Override public Role roleAt(BlockState s, Direction f) {
        Direction front = s.getValue(FACING);
        if (f == front) return Role.HOT_IN;
        if (f == front.getOpposite()) return Role.HOT_OUT;
        if (f == front.getCounterClockWise()) return Role.COLD_IN;
        if (f == front.getClockWise()) return Role.COLD_OUT;
        return null;
    }
    @Override public Direction faceOf(BlockState s, Role r) {
        Direction front = s.getValue(FACING);
        return switch (r) {
            case HOT_IN -> front; case HOT_OUT -> front.getOpposite();
            case COLD_IN -> front.getCounterClockWise(); case COLD_OUT -> front.getClockWise();
            default -> null;
        };
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RwcuRegenBlockEntity(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit) {
        if (!l.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) dev.bwr.mod.gui.RwcuUnitMenu.open(sp, p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.rwcu_regen.line1", "Hot side: reactor water in front, out back. Cold side: cleaned water in on the left, out on the right."));
        lines.add(BwrText.tr("tooltip.bwr.rwcu_regen.line2", "Returns about 92 percent of the heat to the water going back to feedwater. Optional."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
