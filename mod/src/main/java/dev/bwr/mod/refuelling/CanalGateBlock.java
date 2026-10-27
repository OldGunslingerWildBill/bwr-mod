package dev.bwr.mod.refuelling;

import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A gate in the cattle chute: a canal block with a plate across the trough. Open, the plate is sunk into the floor and a
 * refuelling bridge may carry an assembly through; closed, the plate stands across the trough and isolates the pool from
 * the cavity (the bridge will not start a move through it). A player opens or closes it with an empty hand, or a
 * computer through the bridge peripheral; nothing else ever moves a gate.
 */
public class CanalGateBlock extends TransferCanalBlock {

    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    private static final VoxelShape PLATE_X = Block.box(7, 0, 0, 9, 16, 16);
    private static final VoxelShape PLATE_Z = Block.box(0, 0, 7, 16, 16, 9);

    public CanalGateBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    /**
     * True when this block shuts the canal it stands in: a closed gate, or a {@link CanalGatePlugBlock} (a gate plug is closed while it is in the
     * canal; it opens the canal only by being lifted out by the crane).
     */
    public static boolean closes(BlockState state) {
        return state.getBlock() instanceof CanalGatePlugBlock || state.getBlock() instanceof CanalGateBlock && !state.getValue(OPEN);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape trough = trough(state);
        if (state.getValue(OPEN)) {
            return trough;
        }
        return Shapes.or(trough, state.getValue(AXIS) == Direction.Axis.X ? PLATE_X : PLATE_Z);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            boolean open = !state.getValue(OPEN);
            level.setBlock(pos, state.setValue(OPEN, open), 3);
            level.playSound(null, pos, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8f, 0.6f);
            player.displayClientMessage(open
                    ? BwrText.tr("status.bwr.refuelling_bridge.gate_opened", "Gate open: fuel can pass between the cavity and the pool.")
                    : BwrText.tr("status.bwr.refuelling_bridge.gate_closed", "Gate closed: the pool is isolated from the cavity."), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.canal_gate.plug_hint",
                "The old hand-worked gate. A real canal gate is a steel plug the crane lifts out: build a Canal Gate Plug stack and use GATE OUT / GATE IN on the crane console."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
