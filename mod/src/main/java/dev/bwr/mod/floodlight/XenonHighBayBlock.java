package dev.bwr.mod.floodlight;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The xenon high-bay: a ceiling fixture for a large room, cold white and instant on. Its 110-degree, 24-block beam lands on
 * the floor and the lower walls of the room under it (see {@link XenonBeam}).
 */
public class XenonHighBayBlock extends FacingBeamLampBlock {
    public static final MapCodec<XenonHighBayBlock> CODEC = simpleCodec(XenonHighBayBlock::new);
    private static final VoxelShape[] SHAPES = FacingLampShapes.of(new double[] {1.5, 1.5, 8.5}, new double[] {14.5, 14.5, 16});

    public XenonHighBayBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN).setValue(LIT, false));
    }

    public static BlockBehaviour.Properties lampProperties() {
        return BeamLampBlock.lampProperties(12);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    protected VoxelShape[] shapes() {
        return SHAPES;
    }

    @Override
    protected double lensOffset() {
        return 0.0;
    }

    @Override
    public double range() {
        return 24;
    }

    @Override
    public double halfAngleDegrees() {
        return 55;
    }

    @Override
    public int lightStep() {
        return 2;
    }
}
