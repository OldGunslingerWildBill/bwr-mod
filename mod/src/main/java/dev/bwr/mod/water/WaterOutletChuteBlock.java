package dev.bwr.mod.water;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.*;

/**
 * Concrete outfall chute: cooling or service water arrives through the back flange and runs down the trough into the
 * river or lake in front of it. It is the outfall port with a stricter mouth, so every water line, the containment
 * drain test and the hand tools treat it as the same kind of block; the difference is that it only discharges while
 * its mouth really is over or in open water ({@link WaterOutletChuteBlockEntity#waterState}). Passive: transfers happen
 * on fill requests and there is no ticker.
 */
public final class WaterOutletChuteBlock extends WaterDischargeBlock implements SimpleWaterloggedBlock {
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final MapCodec<WaterOutletChuteBlock> CODEC = simpleCodec(WaterOutletChuteBlock::new);
    /** Apron, headwall and trough walls, north-facing: x1,y1,z1,x2,y2,z2 in sixteenths. */
    private static final double[][] BOXES = {{0, 0, 0, 16, 2, 16}, {1, 2, 8.3, 15, 13, 16}, {1, 2, 0, 2.7, 7.4, 8.3}, {13.3, 2, 0, 15, 7.4, 8.3}};
    private static final VoxelShape[] SHAPES = new VoxelShape[4];
    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape s = Shapes.empty();
            for (double[] b : BOXES) {
                double x = b[0] / 16, y = b[1] / 16, z = b[2] / 16, X = b[3] / 16, Y = b[4] / 16, Z = b[5] / 16;
                s = Shapes.or(s, switch (d) {
                    case EAST -> Shapes.box(1 - Z, y, x, 1 - z, Y, X);
                    case SOUTH -> Shapes.box(1 - X, y, 1 - Z, 1 - x, Y, 1 - z);
                    case WEST -> Shapes.box(z, y, 1 - X, Z, Y, 1 - x);
                    default -> Shapes.box(x, y, z, X, Y, Z);
                });
            }
            SHAPES[d.get2DDataValue()] = s;
        }
    }

    public WaterOutletChuteBlock(Properties p) {
        super(p);
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(WATERLOGGED); }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES[s.getValue(FACING).get2DDataValue()]; }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new WaterOutletChuteBlockEntity(p, s); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        return super.getStateForPlacement(c).setValue(WATERLOGGED, c.getLevel().getFluidState(c.getClickedPos()).is(Fluids.WATER));
    }
    @Override protected FluidState getFluidState(BlockState s) { return s.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(s); }
    @Override protected BlockState updateShape(BlockState s, Direction d, BlockState n, LevelAccessor l, BlockPos p, BlockPos np) {
        if (s.getValue(WATERLOGGED)) l.scheduleTick(p, Fluids.WATER, Fluids.WATER.getTickDelay(l));
        return super.updateShape(s, d, n, l, p, np);
    }
    /** A warning at placement: the chute will refuse to discharge until its mouth is over or in open water. */
    @Override public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity who, ItemStack stack) {
        super.setPlacedBy(l, p, s, who, stack);
        if (!l.isClientSide() && who instanceof net.minecraft.server.level.ServerPlayer sp
                && l.getBlockEntity(p) instanceof WaterOutletChuteBlockEntity be && be.waterState() != WaterOutletChuteBlockEntity.WaterState.OPEN)
            sp.displayClientMessage(BwrText.tr("status.bwr.water_outlet_chute.not_facing_water",
                    "Water outlet chute: its mouth is not over or in open water, so it will refuse to discharge."), true);
    }
    @Override public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
        lines.add(BwrText.tr("status.bwr.water_outlet_chute.tip_water_in_back", "Water in at the back flange; the mouth must face open river or lake water."));
        lines.add(BwrText.tr("status.bwr.water_outlet_chute.tip_no_sources", "Returns water; makes and removes no source blocks."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
