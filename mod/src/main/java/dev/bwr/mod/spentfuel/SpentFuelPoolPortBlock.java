package dev.bwr.mod.spentfuel;

import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * A water flange in the wall of a spent fuel pool: the makeup port (water in), the cooling-water inlet (cold water in
 * for the cooling train) or the cooling-water outlet (warmed water out). Front face only. No block entity: the
 * pool that owns the port answers for it ({@link SpentFuelPoolRegistry#ownerOf}), and the fluid capability is
 * registered by {@link SpentFuelPoolCapabilities}.
 */
public class SpentFuelPoolPortBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public enum Kind {
        /** Pool makeup water in (amber). */
        MAKEUP,
        /** Cold secondary water in for the cooling train (blue). */
        COOLING_IN,
        /** Warmed secondary water out of the cooling train (cyan). */
        COOLING_OUT
    }

    private final Kind kind;

    public SpentFuelPoolPortBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return defaultBlockState().setValue(FACING,
                face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (state != old) {
            dev.bwr.mod.piping.PipeTopology.changed(level, pos);
        }
        if (state.getBlock() != old.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp) {
            SpentFuelPoolBlockEntity owner = SpentFuelPoolRegistry.ownerOf(level, pos);
            if (owner == null) {
                sp.displayClientMessage(BwrText.tr("status.bwr.spent_fuel_pool.port_unformed",
                        "Pool not formed. Complete the shell; sneak-click the pool controller for details."), true);
            } else {
                dev.bwr.mod.gui.SpentFuelPoolMenu.open(sp, owner);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(switch (kind) {
            case MAKEUP -> BwrText.tr("status.bwr.spent_fuel_pool.tip_makeup",
                    "Amber flange: makeup water IN. The pool never fills itself; supply it.");
            case COOLING_IN -> BwrText.tr("status.bwr.spent_fuel_pool.tip_cooling_in",
                    "Blue flange: cold cooling water IN to the pool's cooling train.");
            case COOLING_OUT -> BwrText.tr("status.bwr.spent_fuel_pool.tip_cooling_out",
                    "Cyan flange: warmed cooling water OUT of the cooling train.");
        });
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.tip_port_place",
                "Place in a pool wall above the floor, facing outward."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
