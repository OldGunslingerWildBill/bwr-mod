package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Mimic / synoptic band: a flat board 3/16 deep for the wall, printed with a plain flow diagram, in two finishes: cream
 * and black (the EDF and VVER look). Decorative only. The mimic tape draws on the panel bodies, which carry the block
 * entity it needs, so it does not work on these boards.
 */
public final class MimicBandBlock extends AssemblyBlock {
    private static final MapCodec<MimicBandBlock> CODEC_CREAM = simpleCodec(p -> new MimicBandBlock(p, false));
    private static final MapCodec<MimicBandBlock> CODEC_BLACK = simpleCodec(p -> new MimicBandBlock(p, true));
    private static final VoxelShape[] SHAPE = FurnitureShapes.perFacing(new double[][] {{0, 0, 13.4, 16, 16, 16}}, false);

    private final boolean black;

    public MimicBandBlock(Properties properties, boolean black) {
        super(properties);
        this.black = black;
    }

    public boolean black() {
        return black;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return black ? CODEC_BLACK : CODEC_CREAM;
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[FurnitureShapes.index(state.getValue(FACING))];
    }
}
