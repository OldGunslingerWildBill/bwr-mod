package dev.bwr.mod.flow;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A jet pump in the downcomer annulus — {@code SPEC.md} section 4.3.
 *
 * <p>Jet pumps have no state of their own worth a block entity: they are
 * passive geometry that multiplies the driving head the recirculation pumps
 * provide. The controller counts them during validation and scales flow.
 *
 * <p>Having <b>no</b> jet pumps at all is valid — flow then comes only from
 * direct pump push and is significantly reduced.
 *
 * <p>On a real BWR/6 the twenty jet pumps are arranged in ten assemblies of two
 * sharing a common inlet riser, which is why they come in pairs.
 */
public class JetPumpBlock extends Block {

    public enum Size implements StringRepresentable {
        /** Two blocks long. Fits confined spaces. */
        SMALL("small", 1.0),
        /** Three blocks long. Roughly 1.5x the flow contribution of a small one. */
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
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SIZE, Size.SMALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SIZE);
    }
}
