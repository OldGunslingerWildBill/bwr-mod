package dev.bwr.mod.isotope;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What an RTG does to a room, as numbers with no mod types in them (so the compat code and the tests share them and a
 * run without Cold Sweat never needs to know what Cold Sweat is). An RTG is a heater: the body always runs at its
 * material's thermal power, and the heat-only switch changes nothing here.
 *
 * <h2>The scale</h2>
 * Cold Sweat's body-temperature unit is the "MC unit": 1.0 is 25 C, so 0.1 is 2.5 C. A source adds
 * {@link #MC_UNITS_PER_KILOWATT} per kilowatt of thermal power at its own block, fading linearly to nothing at Cold
 * Sweat's block range (7 blocks by default) as for any other heat block, and capped at {@link #MAX_EFFECT_MC_UNITS} in
 * total. A small strontium RTG (1.38 kW) adds 0.21 units (5 C) beside it; a furnace is 0.33. A Large RTG's power is shared
 * across its twelve cells, so its total at the same distance is the same figure for the same watts; it is the 18 MW of a
 * full cobalt unit that reaches the cap, which is also what a unit that size would do to a room.
 */
public final class RtgHeat {

    private RtgHeat() {
    }

    /** MC units added at the source block per kilowatt of thermal power. */
    public static final double MC_UNITS_PER_KILOWATT = 0.15;
    /** The most any number of RTGs can add, MC units (1.5 is 37.5 C). */
    public static final double MAX_EFFECT_MC_UNITS = 1.5;

    /** Thermal watts of the RTG that the block {@code pos} belongs to, shared across its cells; 0 when empty or not loaded. */
    public static double cellWatts(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LargeRtgBlock) {
            LargeRtgBlockEntity be = LargeRtgBlock.controller(level, pos, state);
            return be == null ? 0.0 : be.thermalWatts() / LargeRtgBlock.CELLS;
        }
        if (level.getBlockEntity(pos) instanceof RtgBlockEntity be) {
            return be.readout().thermalWatts();
        }
        return 0.0;
    }

    /** MC units this block adds at its own position (before distance), from its thermal watts. */
    public static double mcUnits(double thermalWatts) {
        return thermalWatts > 0.0 ? thermalWatts / 1000.0 * MC_UNITS_PER_KILOWATT : 0.0;
    }
}
