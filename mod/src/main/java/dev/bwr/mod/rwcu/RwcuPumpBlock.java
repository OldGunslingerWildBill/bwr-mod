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

/** The RWCU pump: an electric centrifugal pump. Suction on the back, discharge on the front. */
public class RwcuPumpBlock extends RwcuUnitBlock {
    public static final MapCodec<RwcuPumpBlock> CODEC = simpleCodec(RwcuPumpBlock::new);
    public RwcuPumpBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<RwcuPumpBlock> codec() { return CODEC; }
    @Override public Role roleAt(BlockState s, Direction f) {
        Direction front = s.getValue(FACING);
        return f == front ? Role.PUMP_DISCHARGE : f == front.getOpposite() ? Role.PUMP_SUCTION : null;
    }
    @Override public Direction faceOf(BlockState s, Role r) {
        Direction front = s.getValue(FACING);
        return r == Role.PUMP_DISCHARGE ? front : r == Role.PUMP_SUCTION ? front.getOpposite() : null;
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block neighbor,
                                             BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RwcuPumpBlockEntity be
                && !be.isComputerControlled() && !be.isPanelControlled()) {
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != be.isRunning()) be.setRunning(powered);
        }
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RwcuPumpBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        return l.isClientSide() ? null : dev.bwr.mod.TickProbe.wrap(createTickerHelper(t, BwrBlockEntities.RWCU_PUMP.get(), RwcuPumpBlockEntity::serverTick));
    }
    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit) {
        if (!l.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) dev.bwr.mod.gui.PumpControlMenu.open(sp, p, p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.rwcu_pump.line1", "Suction: back. Discharge: front. Draws reactor water from a recirculation outlet or the bottom drain port."));
        lines.add(BwrText.tr("tooltip.bwr.rwcu_pump.line2", "Electric: 60 kg/s at 130 psid, 120 kW. Starts only when you tell it to."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
