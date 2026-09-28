package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ItemAbility;

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
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty CORNER = net.minecraft.world.level.block.state.properties.BooleanProperty.create("corner");
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty MANUAL_ORIENTATION = net.minecraft.world.level.block.state.properties.BooleanProperty.create("manual_orientation");
    // Mekanism advertises this ability only in Rotate mode. No hard Mekanism dependency.
    private static final ItemAbility WRENCH_ROTATE = ItemAbility.get("wrench_rotate");

    public CoreSpraySpargerBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(LOOP, Loop.LPCS).setValue(FACING,net.minecraft.core.Direction.NORTH).setValue(CORNER,false).setValue(MANUAL_ORIENTATION,false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOOP,FACING,CORNER,MANUAL_ORIENTATION);
    }

    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c) {
        // Blender's north/south header has its spray branch pointing east:
        // FACING describes a header endpoint, so compensate by a quarter turn.
        var state=defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite().getCounterClockWise());
        // Extend the existing loop without reselecting HPCS on every segment.
        for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var neighbor=c.getLevel().getBlockState(c.getClickedPos().relative(d));
            if(neighbor.is(this)){state=state.setValue(LOOP,neighbor.getValue(LOOP));break;}
        }
        return connections(state,c.getLevel(),c.getClickedPos());
    }
    @Override protected BlockState rotate(BlockState s,net.minecraft.world.level.block.Rotation r) {return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,net.minecraft.world.level.block.Mirror m) {
        var a=m.mirror(s.getValue(FACING));
        if(!s.getValue(CORNER))return s.setValue(FACING,a);
        var b=m.mirror(s.getValue(FACING).getClockWise());
        return s.setValue(FACING,a.getClockWise()==b?a:b);
    }
    @Override protected BlockState updateShape(BlockState s,net.minecraft.core.Direction side,BlockState neighbor,
            net.minecraft.world.level.LevelAccessor world,BlockPos p,BlockPos other) {
        return connections(s,world,p);
    }
    private BlockState connections(BlockState s,net.minecraft.world.level.BlockGetter world,BlockPos p) {
        if(s.getValue(MANUAL_ORIENTATION))return s;
        var connected=new java.util.ArrayList<net.minecraft.core.Direction>(4);
        for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var neighbor=world.getBlockState(p.relative(d));
            if(neighbor.is(this)&&neighbor.getValue(LOOP)==s.getValue(LOOP))connected.add(d);
        }
        if(connected.size()==2&&connected.get(0).getOpposite()!=connected.get(1)) {
            var a=connected.get(0);var b=connected.get(1);
            return s.setValue(CORNER,true).setValue(FACING,a.getClockWise()==b?a:b);
        }
        // A neighboring header must not flip the spray branch away from the player.
        return s.setValue(CORNER,false);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.canPerformAction(WRENCH_ROTATE)) {
            if (!player.mayBuild()) return ItemInteractionResult.FAIL;
            if (!level.isClientSide()) {
                level.setBlockAndUpdate(pos, state
                        .setValue(FACING, state.getValue(FACING).getClockWise())
                        .setValue(MANUAL_ORIENTATION, true));
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        // Let held blocks be placed against a segment and leave other tool modes alone.
        // Only a genuinely empty hand should switch LPCS/HPCS.
        return stack.isEmpty() && player.getOffhandItem().isEmpty()
                ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                java.util.List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.bwr.sparger.placement"));
        tooltip.add(Component.translatable("tooltip.bwr.sparger.rotate"));
        tooltip.add(Component.translatable("tooltip.bwr.sparger.loop"));
    }

    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,net.minecraft.world.level.BlockGetter world,
            BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context) {
        var shape=net.minecraft.world.phys.shapes.Shapes.empty();
        var first=state.getValue(FACING);
        var second=state.getValue(CORNER)?first.getClockWise():first.getOpposite();
        for(var d:new net.minecraft.core.Direction[]{first,second}) {
            double x=.5+d.getStepX()*.5,z=.5+d.getStepZ()*.5;
            shape=net.minecraft.world.phys.shapes.Shapes.or(shape,net.minecraft.world.phys.shapes.Shapes.box(
                    Math.max(0,Math.min(.5,x)-.15),.29,Math.max(0,Math.min(.5,z)-.15),
                    Math.min(1,Math.max(.5,x)+.15),.67,Math.min(1,Math.max(.5,z)+.15)));
        }
        return shape;
    }

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
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Loop next = state.getValue(LOOP) == Loop.HPCS ? Loop.LPCS : Loop.HPCS;
        level.setBlockAndUpdate(pos, connections(state.setValue(LOOP, next),level,pos));
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
