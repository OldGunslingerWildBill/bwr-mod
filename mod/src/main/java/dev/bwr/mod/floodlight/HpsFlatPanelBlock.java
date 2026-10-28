package dev.bwr.mod.floodlight;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The high-pressure sodium flat panel: a flat rectangular industrial fixture for a ceiling or a wall, golden white (the HPS
 * colour, #FFCE80). Its 120-degree main beam floods the area in front of it at full light; a spill zone out to 160 degrees
 * lights what lies past the beam at four fifths of that, and the whole reaches 40 blocks (see {@link XenonBeam}).
 *
 * <p>An HPS arc warms up for minutes. Scaled like the HPS lamps (20 s), the panel strikes at light 4 and steps to 8, 12 and
 * 15 on three scheduled ticks about 6.7 s apart. The projected light follows the same steps (the lights are re-levelled
 * in place, never moved; the spill lights follow at four fifths of the beam's level), so the room brightens in four steps. There is no hot-restrike wait: switched off, it strikes
 * again at once.
 */
public class HpsFlatPanelBlock extends FacingBeamLampBlock {
    public static final MapCodec<HpsFlatPanelBlock> CODEC = simpleCodec(HpsFlatPanelBlock::new);
    /** Warm-up stage of a lit panel: 0 is light 4, 3 is light 15. */
    public static final IntegerProperty WARMUP = IntegerProperty.create("warmup", 0, 3);
    /** The warm-up stages, as the HPS lamps have them: light 4, 8, 12, 15. */
    private static final int[] LIGHT = {4, 8, 12, 15};
    /** Ticks between two warm-up stages: 20 s over three steps, as the HPS lamps. */
    public static final int STEP_TICKS = 400 / 3;
    private static final VoxelShape[] SHAPES = FacingLampShapes.of(new double[] {0.5, 0.5, 12.25}, new double[] {15.5, 15.5, 16});

    public HpsFlatPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN).setValue(LIT, false).setValue(WARMUP, 0));
    }

    public static BlockBehaviour.Properties lampProperties() {
        return BlockBehaviour.Properties.of().pushReaction(PushReaction.BLOCK).mapColor(MapColor.METAL).strength(3.0F, 6.0F)
                .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
                .lightLevel(state -> state.getValue(LIT) ? lightAt(state.getValue(WARMUP)) : 0);
    }

    /** The light level at a warm-up stage. */
    public static int lightAt(int stage) {
        return LIGHT[Math.max(0, Math.min(3, stage))];
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT, WARMUP);
    }

    @Override
    protected VoxelShape[] shapes() {
        return SHAPES;
    }

    @Override
    protected double lensOffset() {
        return 0.25;
    }

    @Override
    public double range() {
        return 40;
    }

    @Override
    public double halfAngleDegrees() {
        return 60;
    }

    @Override
    public double spillAngleDegrees() {
        return 80;
    }

    @Override
    public int lightStep() {
        return 2;
    }

    @Override
    public int projectedLevel(BlockState state) {
        return lightAt(state.getValue(WARMUP));
    }

    @Override
    public BlockState switched(BlockState state, boolean lit) {
        return state.setValue(LIT, lit).setValue(WARMUP, 0);
    }

    @Override
    protected int nextTickDelay(BlockState state) {
        return state.getValue(WARMUP) < 3 ? STEP_TICKS : REFRESH_TICKS;
    }

    @Override
    protected BlockState advance(ServerLevel level, BlockPos pos, BlockState state) {
        int stage = state.getValue(WARMUP);
        return stage < 3 ? state.setValue(WARMUP, stage + 1) : state;
    }
}
