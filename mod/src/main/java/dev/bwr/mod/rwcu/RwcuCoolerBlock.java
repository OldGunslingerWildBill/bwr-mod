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

/** The non-regenerative heat exchanger: cools the cleanup water to about 120 F with cooling water piped to its side flanges. */
public class RwcuCoolerBlock extends RwcuUnitBlock {
    public static final MapCodec<RwcuCoolerBlock> CODEC = simpleCodec(RwcuCoolerBlock::new);
    public RwcuCoolerBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<RwcuCoolerBlock> codec() { return CODEC; }
    @Override public Role roleAt(BlockState s, Direction f) {
        Direction front = s.getValue(FACING);
        if (f == front) return Role.PROCESS_IN;
        if (f == front.getOpposite()) return Role.PROCESS_OUT;
        if (f == front.getCounterClockWise()) return Role.COOLING_IN;
        if (f == front.getClockWise()) return Role.COOLING_OUT;
        return null;
    }
    @Override public Direction faceOf(BlockState s, Role r) {
        Direction front = s.getValue(FACING);
        return switch (r) {
            case PROCESS_IN -> front; case PROCESS_OUT -> front.getOpposite();
            case COOLING_IN -> front.getCounterClockWise(); case COOLING_OUT -> front.getClockWise();
            default -> null;
        };
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RwcuCoolerBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        return l.isClientSide() ? null : dev.bwr.mod.TickProbe.wrap(createTickerHelper(t, BwrBlockEntities.RWCU_COOLER.get(), RwcuCoolerBlockEntity::serverTick));
    }
    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit) {
        if (!l.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) dev.bwr.mod.gui.RwcuUnitMenu.open(sp, p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.rwcu_cooler.line1", "Process water: in front, out back. Cooling water: blue in on the left, cyan out on the right."));
        lines.add(BwrText.tr("tooltip.bwr.rwcu_cooler.line2", "Up to 8 MW. With no cooling water the demineraliser sees hot water."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
