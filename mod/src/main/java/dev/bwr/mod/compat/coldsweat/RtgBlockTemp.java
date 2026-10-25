package dev.bwr.mod.compat.coldsweat;

import com.momosoftworks.coldsweat.api.temperature.block_temp.BlockTemp;
import dev.bwr.mod.isotope.RtgHeat;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The RTG and the Large RTG as Cold Sweat heat sources. The temperature a block adds is its thermal watts (see
 * {@link RtgHeat}): an empty RTG returns 0, which Cold Sweat skips. Cold Sweat fades it with distance and caps the total.
 * {@code getTemperature} is Cold Sweat's own per-cell call; every cell of the Large RTG answers with its share.
 */
final class RtgBlockTemp extends BlockTemp {

    RtgBlockTemp() {
        super(BwrBlocks.RTG.get(), BwrBlocks.LARGE_RTG.get());
    }

    @Override
    public double getTemperature(Level level, @Nullable LivingEntity entity, BlockState state, BlockPos pos, double distance) {
        return RtgHeat.mcUnits(RtgHeat.cellWatts(level, pos, state));
    }

    @Override
    public double getMaxEffect(@Nullable LivingEntity entity, Level level, BlockPos pos, BlockState state) {
        return RtgHeat.MAX_EFFECT_MC_UNITS;
    }

    @Override
    public double getMinEffect(@Nullable LivingEntity entity, Level level, BlockPos pos, BlockState state) {
        return 0.0;
    }

    @Override
    public double getMaxTemp(@Nullable LivingEntity entity, Level level, BlockPos pos, BlockState state) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double getMinTemp(@Nullable LivingEntity entity, Level level, BlockPos pos, BlockState state) {
        return Double.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isValid(Level level, BlockPos pos, BlockState state) {
        return RtgHeat.cellWatts(level, pos, state) > 0.0;
    }
}
