package dev.bwr.mod.water;

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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * A motor-operated valve for a BWR water line (feedwater, condensate, ECCS, RHR, cooling, a cavity drain). Two opposed
 * flanges take High-Pressure Water Pipe; the actuator on top is not a port. It is a <i>valve</i> in the pipe survey: a
 * route through it is open to the extent the valve is, and a closed one isolates what is behind it, the line-break code
 * included. Nothing moves it but the player (redstone, a panel, a computer, or sneak-clicking its fail position).
 */
public class MotorOperatedWaterValveBlock extends BaseEntityBlock {
    public static final MapCodec<MotorOperatedWaterValveBlock> CODEC = simpleCodec(MotorOperatedWaterValveBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MotorOperatedWaterValveBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<MotorOperatedWaterValveBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }

    /** The two flanges are the water ports; the sides and the actuator are not. */
    public boolean acceptsWaterLineOn(BlockState state, Direction face) {
        return face != null && face.getAxis() == state.getValue(FACING).getAxis();
    }

    /** The faces that take FE: everything but the two flanges. */
    public static boolean energyFace(BlockState state, Direction side) {
        return side != null && side.getAxis() != state.getValue(FACING).getAxis();
    }

    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(FACING).getAxis() == Direction.Axis.Z ? box(3, 0, 0, 13, 16, 16) : box(0, 0, 3, 16, 16, 13);
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new MotorOperatedWaterValveBlockEntity(p, s); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state == old) return;
        dev.bwr.mod.piping.PipeTopology.changed(level, pos);
        if (!level.isClientSide() && !old.is(this) && level.getBlockEntity(pos) instanceof MotorOperatedWaterValveBlockEntity valve)
            valve.redstone(level.getBestNeighborSignal(pos));
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if (!replacement.is(this)) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
    }

    /** Redstone is proportional: strength 0 demands closed, 15 fully open, in between strength / 15. Only a change counts. */
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos changed, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, changed, moving);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MotorOperatedWaterValveBlockEntity valve)
            valve.redstone(level.getBestNeighborSignal(pos));
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MotorOperatedWaterValveBlockEntity valve)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            // Sneak-click sets the fail position, the valve's one configuration: AS-IS, FAIL OPEN, FAIL CLOSED, round again.
            valve.setFailMode(valve.failMode().next());
            player.displayClientMessage(BwrText.tr("status.bwr.water_valve.fail_set", "Fail position: %s", valve.failMode().label()), true);
            return InteractionResult.CONSUME;
        }
        for (Component line : valve.statusComponents()) player.displayClientMessage(line, false);
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.water_pipe.rating"));
        lines.add(MotorOperatedWaterValveBlockEntity.ratingComponent());
        lines.add(BwrText.tr("tooltip.bwr.water_valve.stroke", "Full stroke 20 s, 50 FE/t while it drives. Holds its position with no power."));
        lines.add(BwrText.tr("tooltip.bwr.water_valve.control",
                "Redstone: strength 0 closed, 15 open, proportional. Also a panel, the Networking Wrench or a computer. Sneak-click: fail position (as-is, open, closed)."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
