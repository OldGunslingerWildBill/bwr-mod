package dev.bwr.mod.flow;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** A paired, passive jet assembly. Its connected drive circuit limits core flow. */
public class JetPumpBlock extends dev.bwr.mod.eccs.PumpAssemblyBlock {
    public static final com.mojang.serialization.MapCodec<JetPumpBlock> CODEC = simpleCodec(JetPumpBlock::new);

    @Override protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec() { return CODEC; }

    public enum Size implements StringRepresentable {
        /** Normal paired assembly. */
        SMALL("small", 1.0),
        /** Retained saved-state rating; uses the same supplied paired geometry. */
        LARGE("large", 1.5);

        private final String name;
        private final double flowMultiplier;

        Size(String name, double flowMultiplier) {
            this.name = name;
            this.flowMultiplier = flowMultiplier;
        }

        public double flowMultiplier() {
            return flowMultiplier;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Size> SIZE = EnumProperty.create("size", Size.class);

    public JetPumpBlock(Properties properties) {
        super(properties, Kind.JET);
        registerDefaultState(defaultBlockState().setValue(SIZE, Size.SMALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIZE);
    }
}
