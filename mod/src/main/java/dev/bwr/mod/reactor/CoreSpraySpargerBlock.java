package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;

/**
 * One segment of a core spray sparger ring — {@code SPEC.md} section 9.4.
 *
 * <p>Spargers are <b>assembled rings</b>, built segment by segment around the
 * core perimeter, not single blocks. Two payoffs: they look right when the head
 * is off, and spray capacity scales with how complete the ring is. An
 * incomplete ring is <i>degraded</i>, never invalid — a ring at 60% delivers
 * 60% of its spray, so losing segments to damage has graded consequences
 * instead of flipping the multiblock off.
 *
 * <p>Which system a segment belongs to determines the elevation it must sit at,
 * and the validator checks it: HPCS four blocks above the top of active fuel,
 * LPCS one block above. <b>Bare right-click switches a segment between the two
 * loops.</b> Without it every segment stayed on its default of LPCS forever —
 * the block state had two variants, two models and two textures, and nothing in
 * the game could ever select the second one, so an HPCS ring was unbuildable and
 * the HPCS loop delivered no core spray on any plant ever built. It is a piece
 * of construction, like choosing which header to weld a pipe onto; there is no
 * permissive on it and it does not care what the reactor is doing.
 */
public class CoreSpraySpargerBlock extends Block {

    public enum Loop implements StringRepresentable {
        /** High pressure core spray. Four blocks above top of active fuel. */
        HPCS("hpcs", 4),
        /** Low pressure core spray. One block above top of active fuel. */
        LPCS("lpcs", 1);

        private final String name;
        private final int blocksAboveTaf;

        Loop(String name, int blocksAboveTaf) {
            this.name = name;
            this.blocksAboveTaf = blocksAboveTaf;
        }

        /** Required elevation above the top of active fuel, in blocks. */
        public int blocksAboveTaf() {
            return blocksAboveTaf;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Loop> LOOP = EnumProperty.create("loop", Loop.class);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

    public CoreSpraySpargerBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(LOOP, Loop.LPCS).setValue(FACING,net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOOP,FACING);
    }

    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState s,net.minecraft.world.level.block.Rotation r) {return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,net.minecraft.world.level.block.Mirror m) {return s.rotate(m.getRotation(s.getValue(FACING)));}

    /**
     * Switch this segment to the other loop, and say which one it is now.
     *
     * <p>The reactor controller picks the change up on its next structure
     * sweep — a sparger is almost never a direct neighbour of the controller, so
     * it cannot announce itself.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Loop next = state.getValue(LOOP) == Loop.HPCS ? Loop.LPCS : Loop.HPCS;
        level.setBlockAndUpdate(pos, state.setValue(LOOP, next));
        player.displayClientMessage(Component.literal(
                "Sparger segment set to " + next.getSerializedName().toUpperCase(Locale.ROOT)
                        + ", which belongs " + next.blocksAboveTaf()
                        + " block(s) above the top of active fuel"), true);
        return InteractionResult.CONSUME;
    }

    /** The elevation this segment should occupy, given the top of active fuel. */
    public static int requiredY(Loop loop, int topOfActiveFuelY) {
        return topOfActiveFuelY + loop.blocksAboveTaf();
    }

    /** True when this segment sits at the elevation its loop requires. */
    public static boolean isAtCorrectElevation(BlockState state, BlockPos pos, int topOfActiveFuelY) {
        if (!(state.getBlock() instanceof CoreSpraySpargerBlock)) {
            return false;
        }
        return pos.getY() == requiredY(state.getValue(LOOP), topOfActiveFuelY);
    }
}
