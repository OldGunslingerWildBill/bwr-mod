package dev.bwr.mod.accident;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/**
 * Corium in the world: the molten core and the concrete it has dissolved.
 *
 * <p>Placed only by {@link CoriumPool} after the vessel lower head fails. While
 * {@link #HOT} it glows, burns anything standing on it (sneaking does not help;
 * this is not magma) and drops nothing, because there is nothing to pick up.
 * Once the pool is quenched it becomes a dark glassy slag: obsidian-hard, blast
 * resistant, and recoverable with a diamond pickaxe.
 *
 * <p>Always a full solid cube in both states, so a containment floor that the
 * melt has eaten into still counts as wall under the full-cube rule. The block
 * never ticks: hot to cold is driven from the reactor, never back.
 */
public final class CoriumBlock extends Block {

    public static final BooleanProperty HOT = BooleanProperty.create("hot");

    /** Light from molten corium; magma is 3, lava 15. */
    public static final int HOT_LIGHT = 12;

    /** Damage per step on molten corium, half-hearts. Magma does 1. */
    public static final float HOT_DAMAGE = 4.0f;

    /** Seconds a creature that touches molten corium stays alight. */
    public static final float HOT_FIRE_SECONDS = 8.0f;

    public CoriumBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(state -> state.getValue(HOT) ? MapColor.COLOR_ORANGE : MapColor.COLOR_BLACK)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .strength(50.0f, 1200.0f)
                .sound(SoundType.BASALT)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(HOT) ? HOT_LIGHT : 0)
                .emissiveRendering((state, level, pos) -> state.getValue(HOT))
                .isValidSpawn((state, level, pos, type) -> !state.getValue(HOT) || type.fireImmune()));
        registerDefaultState(stateDefinition.any().setValue(HOT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HOT);
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        // Fire-immune mobs (striders, blazes) walk across it like lava rock.
        if (state.getValue(HOT) && !level.isClientSide() && entity instanceof LivingEntity
                && !entity.fireImmune()) {
            entity.hurt(level.damageSources().hotFloor(), HOT_DAMAGE);
            entity.igniteForSeconds(HOT_FIRE_SECONDS);
        }
        super.stepOn(level, pos, state, entity);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(HOT) || random.nextInt(6) != 0) {
            return;
        }
        BlockPos above = pos.above();
        if (!level.getBlockState(above).isAir()) {
            return;
        }
        double x = pos.getX() + random.nextDouble(), z = pos.getZ() + random.nextDouble();
        level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.LAVA : ParticleTypes.SMOKE,
                x, pos.getY() + 1.0, z, 0.0, 0.02, 0.0);
    }
}
