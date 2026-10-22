package dev.bwr.mod.spentfuel;

import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * A fuel rack: one single tier, four blocks tall (assemblies are about 4.5 m and stand upright) holding four assemblies, built as
 * a column of exactly four of these blocks on the floor of a spent fuel pool. It never stands on another rack.
 * It has no block entity; the pool controller holds what is in it and writes the four booleans so the model shows
 * an assembly head in each occupied cell.
 *
 * <p>Right-click with a fuel assembly puts it in a free cell (the cell under the cursor on the top face if it is
 * free). Sneak with an empty hand takes one out (the cell under the cursor if it is occupied, otherwise the one that
 * has cooled longest). A plain right-click with an empty hand reports the rack. Hoppers and pipes use the
 * controller's item handler instead.
 */
public class SpentFuelRackBlock extends Block {

    /** Whether cell k holds an assembly: set by the pool controller, read by the model (one assembly head per cell). */
    public static final BooleanProperty CELL_A = BooleanProperty.create("cell_a");
    public static final BooleanProperty CELL_B = BooleanProperty.create("cell_b");
    public static final BooleanProperty CELL_C = BooleanProperty.create("cell_c");
    public static final BooleanProperty CELL_D = BooleanProperty.create("cell_d");
    /** Set by the pool controller on the top block of a rack: the one that shows the assembly heads. */
    public static final BooleanProperty TOP = BooleanProperty.create("top");
    public static final BooleanProperty[] CELL = {CELL_A, CELL_B, CELL_C, CELL_D};

    public SpentFuelRackBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(TOP, false);
        for (BooleanProperty cell : CELL) {
            state = state.setValue(cell, false);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP);
        builder.add(CELL);
    }

    /** The cell (0 to 3) a hit on the top face is over, or -1 for any other face. Cell k is x = k & 1, z = k >> 1. */
    static int cellAt(BlockPos pos, BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP) {
            return -1;
        }
        double dx = hit.getLocation().x - pos.getX();
        double dz = hit.getLocation().z - pos.getZ();
        return (dx >= 0.5 ? 1 : 0) | (dz >= 0.5 ? 2 : 0);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (state.getBlock() != old.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
    }

    /** Breaking a rack drops what is in it; the pool rescans. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock() && !level.isClientSide()) {
            SpentFuelPoolBlockEntity owner = SpentFuelPoolRegistry.ownerOf(level, pos);
            if (owner != null) {
                owner.rackBroken(pos);
            }
            SpentFuelPoolRegistry.changed(level, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        SpentFuelPoolBlockEntity owner = SpentFuelPoolRegistry.ownerOf(level, pos);
        if (owner == null) {
            player.displayClientMessage(BwrText.tr("status.bwr.spent_fuel_pool.rack_unformed",
                    "This rack is not in a formed pool."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (owner.insertAt(pos, cellAt(pos, hit), stack.copyWithCount(1))) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        } else {
            player.displayClientMessage(BwrText.tr("status.bwr.spent_fuel_pool.rack_full",
                    "The rack is full."), true);
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        SpentFuelPoolBlockEntity owner = SpentFuelPoolRegistry.ownerOf(level, pos);
        if (owner == null) {
            player.displayClientMessage(BwrText.tr("status.bwr.spent_fuel_pool.rack_unformed",
                    "This rack is not in a formed pool."), true);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            ItemStack taken = owner.extractFrom(pos, cellAt(pos, hit));
            if (!taken.isEmpty() && !player.getInventory().add(taken)) {
                player.drop(taken, false);
            }
        } else {
            for (Component line : owner.rackReport(pos)) {
                player.displayClientMessage(line, false);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.tip_rack",
                "Four cells in a 4 m column. Right-click the top with a fuel assembly to store it; sneak with an empty hand to take one out."));
        lines.add(BwrText.tr("status.bwr.spent_fuel_pool.tip_rack_place",
                "Stack exactly four on the floor of a spent fuel pool to make one rack; a rack never stands on another rack."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
