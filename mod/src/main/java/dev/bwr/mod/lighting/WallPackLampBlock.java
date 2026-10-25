package dev.bwr.mod.lighting;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Industrial wall pack: a box on a wall with its lens angled down over the floor. Its facing is the way the lens looks,
 * away from the wall; it mounts only on a solid block behind it. The shape is authored for facing north (the lens at the
 * north, the wall to the south) and turned here.
 */
public final class WallPackLampBlock extends IndustrialLampBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Boxes for facing north, sixteenths: mounting plate, housing, angled lens. */
    private static final double[][] BOXES = {{3, 6, 15, 13, 12, 16}, {2, 5, 8, 14, 13, 15}, {3, 3, 4, 13, 6, 8}};
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        for (int turns = 0; turns < 4; turns++) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : BOXES) {
                shape = Shapes.or(shape, turned(b, turns));
            }
            SHAPES[turns] = shape;
        }
    }

    private final LampType lampType;

    public WallPackLampBlock(Properties properties, LampType type) {
        super(properties, type);
        this.lampType = type;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(p -> new WallPackLampBlock(p, lampType));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    protected boolean hasSupport(LevelReader level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos wall = pos.relative(facing.getOpposite());
        return level.getBlockState(wall).isFaceSturdy(level, wall, facing);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        if (!clicked.getAxis().isHorizontal()) {
            return null;
        }
        BlockState state = defaultBlockState().setValue(FACING, clicked);
        return canSurvive(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // get2DDataValue is south 0, west 1, north 2, east 3; SHAPES is north 0 (the quarter turns from north).
        return SHAPES[(state.getValue(FACING).get2DDataValue() + 2) % 4];
    }

    /** The box turned a number of quarter turns about the block's centre (north to east to south to west). */
    private static VoxelShape turned(double[] b, int turns) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double x : new double[] {b[0], b[3]}) {
            for (double z : new double[] {b[2], b[5]}) {
                double dx = x - 8;
                double dz = z - 8;
                for (int t = 0; t < turns; t++) {
                    double nx = -dz;
                    dz = dx;
                    dx = nx;
                }
                minX = Math.min(minX, dx + 8);
                maxX = Math.max(maxX, dx + 8);
                minZ = Math.min(minZ, dz + 8);
                maxZ = Math.max(maxZ, dz + 8);
            }
        }
        return Block.box(minX, b[1], minZ, maxX, b[4], maxZ);
    }
}
