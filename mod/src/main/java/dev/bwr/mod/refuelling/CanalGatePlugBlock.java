package dev.bwr.mod.refuelling;

import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * One block of a canal gate plug: the steel gate of a real plant's transfer canal, a tall plug with seals down both edges that drops into
 * the guides of the slot and is lifted out whole by the reactor building crane (GATE OUT, laid on a {@link GateLaydownStandBlock} or the
 * floor) and set back the same way (GATE IN). A gate is the whole vertical stack of these blocks in one canal column, from the rim down;
 * the top block carries the two lifting eyes. While it stands in the canal the canal is closed (the pool and the well are apart, and the
 * bridge does not pass); with it out the column is open canal. It is a canal block for every survey (the pool's slot, the cavity seal, the
 * well, the bridge's chute), so a gate in a slot keeps the pool formed. Nothing opens it by hand: no hand, no redstone, no program moves a
 * plug except the crane, by the player's command (see {@link CanalGateLift}).
 */
public class CanalGatePlugBlock extends TransferCanalBlock {

    /** The plug across the trough: 6/16 thick along the canal, the full width across (it seals on the trough walls). */
    private static final VoxelShape BODY_X = Block.box(5, 0, 0, 11, 16, 16);
    private static final VoxelShape BODY_Z = Block.box(0, 0, 5, 16, 16, 11);

    public CanalGatePlugBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // the plug alone: it stands in the guides of the canal (the walls beside it are recessed for them) and keeps its shape on a stand
        return state.getValue(AXIS) == Direction.Axis.X ? BODY_X : BODY_Z;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.displayClientMessage(BwrText.tr("status.bwr.canal_gate_plug.crane_only",
                    "This gate is a steel plug: lift it out with the reactor building crane (GATE OUT on the crane console), and set it back with GATE IN."), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.canal_gate_plug.build",
                "Stack it in one canal column from the rim down, turned like the canal: the whole stack is one gate. In the canal it closes the slot."));
        lines.add(BwrText.tr("tooltip.bwr.canal_gate_plug.crane",
                "The building crane lifts it out (GATE OUT) onto a Gate Laydown Stand and puts it back (GATE IN). The water on both sides must be within 0.3 m."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
