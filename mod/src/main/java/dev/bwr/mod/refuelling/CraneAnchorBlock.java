package dev.bwr.mod.refuelling;

import dev.bwr.mod.accident.ShieldPlugConcreteBlock;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A crane anchor: a steel lifting eye bolted to the top face of a shield plug concrete block. It marks the plug block under it as
 * part of a liftable section, and the reactor building crane rigs four of them to lift that section (BUILDING-CRANE.md). It is
 * an ordinary block (no block entity, no ticker) that can only stand on a plug block: placed on anything else it is refused, and
 * it breaks (as an item) if the plug block under it goes.
 */
public class CraneAnchorBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(4.5, 0, 4.5, 11.5, 8.5, 11.5);

    public CraneAnchorBlock(Properties properties) {
        super(properties);
    }

    /** True when {@code pos} has a shield plug concrete block under it. */
    public static boolean standsOnPlug(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof ShieldPlugConcreteBlock;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return standsOnPlug(level, pos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!standsOnPlug(context.getLevel(), context.getClickedPos())) {
            if (context.getPlayer() != null && !context.getLevel().isClientSide()) {
                context.getPlayer().displayClientMessage(BwrText.tr("status.bwr.crane_anchor.plug_only",
                        "A crane anchor bolts to the top face of a shield plug concrete block, and nothing else."), true);
            }
            return null;
        }
        return defaultBlockState();
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }
}
