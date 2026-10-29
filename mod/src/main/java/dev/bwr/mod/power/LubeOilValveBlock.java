package dev.bwr.mod.power;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The Lube Oil Valve (TURBINE-AUX.md): a quarter-turn full-bore <b>ball valve</b> in a lube oil line, with an actuator on its stem and a
 * <b>limit switch box</b> on top whose indicator shows the stem's position (the dome is green and the bar lies along the pipe when open,
 * red and across the pipe when shut). A ball valve because that is what a lube oil isolation is: quarter-turn, full bore (no pressure
 * drop open), tight shut-off, and a quarter-turn stem is exactly what a pair of limit switches reports.
 *
 * <p>Two opposed flanges (the facing axis) take Lube Oil Pipe; the actuator and the box are not ports. The position is the block state
 * {@link #OPEN} (so the client draws the indicator and the survey reads it with no block entity); the stroke of a ball valve is under a
 * second and is taken as instant, so the open and shut limit switches are never both off. Operated by hand (right-click), by redstone
 * (power opens it, losing the power shuts it; only a change counts, so a new valve is open) and by a computer or a panel. Nothing else
 * ever moves it: no alarm or trip closes it.
 */
public class LubeOilValveBlock extends BaseEntityBlock {
    public static final MapCodec<LubeOilValveBlock> CODEC = simpleCodec(LubeOilValveBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Open: oil passes. The limit switch box shows it. A valve placed new is open. */
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    public LubeOilValveBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, true));
    }

    @Override protected MapCodec<LubeOilValveBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, OPEN); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }

    /** The two flanges are the oil ports; the sides, the actuator and the switch box are not. */
    public boolean acceptsOilLineOn(BlockState state, Direction face) {
        return face != null && face.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(FACING).getAxis() == Direction.Axis.Z ? box(3, 3, 0, 13, 16, 16) : box(0, 3, 3, 16, 16, 13);
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new LubeOilValveBlockEntity(p, s); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state == old) return;
        OilLineNetwork.changed(level, pos);
        if (!level.isClientSide() && !old.is(this) && level.getBlockEntity(pos) instanceof LubeOilValveBlockEntity valve)
            valve.redstone(level.getBestNeighborSignal(pos) > 0);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if (!replacement.is(this)) OilLineNetwork.changed(level, pos);
    }

    /** Redstone: power opens, losing it shuts. Only a change of the power is a command. */
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos changed, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, changed, moving);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof LubeOilValveBlockEntity valve)
            valve.redstone(level.getBestNeighborSignal(pos) > 0);
    }

    /** By hand: a click turns the quarter-turn handle of the actuator the other way. */
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LubeOilValveBlockEntity valve)) return InteractionResult.PASS;
        boolean open = !state.getValue(OPEN);
        valve.setOpen(open);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.7F, open ? 1.2F : 0.8F);
        player.displayClientMessage(open
                ? BwrText.tr("status.bwr.lube_oil_valve.open", "Lube oil valve OPEN (limit switch: open)")
                : BwrText.tr("status.bwr.lube_oil_valve.shut", "Lube oil valve SHUT (limit switch: closed)"), true);
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.lube_oil_valve.1", "A quarter-turn ball valve for a Lube Oil Pipe run. The limit switch box on top shows the position: green and along the pipe is open, red and across it is shut."));
        lines.add(BwrText.tr("tooltip.bwr.lube_oil_valve.2", "Right-click to turn it. Redstone power opens it and losing the power shuts it; also a panel, the Networking Wrench or a computer."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
