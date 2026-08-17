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
 * provide.
 *
 * <h2>Not wired up yet, and this comment used to claim otherwise</h2>
 * It read "the controller counts them during validation and scales flow". It
 * does not. {@code ReactorStructure.isShell} accepts this block as a legal
 * piece of vessel wall and that is the entire extent of its participation:
 * nothing anywhere counts jet pumps, nothing reads {@link #SIZE}, and nothing
 * calls {@link Size#flowMultiplier()}. Core flow today comes from the
 * recirculation pumps alone, so building twenty of these changes nothing a
 * player can measure. Saying so is the point — a comment that describes
 * behaviour the code does not have is worse than no comment, because the next
 * person reads it instead of the code.
 *
 * <p>What is missing is one number crossing one boundary:
 * {@code ReactorStructure} would have to count these while it is already
 * walking the shell (summing {@code SIZE}'s multiplier rather than the blocks,
 * so a large pump is worth its 1.5), publish the total the way it publishes
 * sparger completeness, and {@code ReactorControllerBlockEntity.gatherPumpFlow}
 * would have to scale by it. Both of those live outside this package.
 *
 * <p>{@link Size#LARGE} is additionally unreachable in a running game: there is
 * no {@code getStateForPlacement} override and no second item, so every jet
 * pump a player can place is {@link Size#SMALL}.
 *
 * <p>Having <b>no</b> jet pumps at all is valid — flow would then come only
 * from direct pump push and be significantly reduced, which is what every plant
 * is getting at the moment.
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
