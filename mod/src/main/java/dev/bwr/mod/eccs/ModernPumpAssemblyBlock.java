package dev.bwr.mod.eccs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import java.util.List;

/** Centered flange layouts; existing assembled and compact saves keep their original cells. */
public final class ModernPumpAssemblyBlock extends PumpAssemblyBlock {
    public static final BooleanProperty MODERN = BooleanProperty.create("modern");
    public static final MapCodec<ModernPumpAssemblyBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), Codec.STRING.fieldOf("kind").forGetter(b -> b.kind().name())
    ).apply(i, (p, k) -> new ModernPumpAssemblyBlock(p, Kind.valueOf(k))));
    private final Layout modern;

    public ModernPumpAssemblyBlock(Properties properties, Kind kind) {
        super(properties, kind);
        String id = switch (kind) {
            case LPCS -> "lpcs_pump";
            case HPCS -> "hpcs_pump";
            case RHR -> "rhr_pump";
            case MOTOR_FEED -> "motor_feed_pump";
            case TURBINE_FEED -> "turbine_feed_pump";
            default -> throw new IllegalArgumentException("No modern layout for " + kind);
        };
        modern = loadLayout(id + "_modern");
        registerDefaultState(defaultBlockState().setValue(MODERN, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MODERN);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected Layout layout(BlockState state) { return state.getValue(MODERN) ? modern : super.layout(state); }
    @Override public BlockState placementState() {
        return super.placementState().setValue(MODERN, true).setValue(CELL, modern.controller);
    }
    @Override public int controllerCell() { return modern.controller; }
    @Override public int cellCount() { return modern.count(); }
    @Override public BlockPos cellOffset(int cell) { return modern.offset(cell); }
    @Override public List<Port> ports() { return List.copyOf(modern.ports); }
    @Override protected boolean owned(BlockState actual, BlockState expected, int cell) {
        return super.owned(actual, expected, cell) && actual.getValue(MODERN) == expected.getValue(MODERN);
    }
}
