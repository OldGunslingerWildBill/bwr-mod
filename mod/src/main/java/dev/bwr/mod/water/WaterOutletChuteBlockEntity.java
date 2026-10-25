package dev.bwr.mod.water;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The outlet chute's water-facing test and discharge effects. The test is cheap, read-only and cached for one game tick:
 * it looks at no more than the mouth column and a 3 x 3 patch of water, and never loads a chunk. The chute puts water
 * back where it came from; it makes no source blocks and removes none.
 */
public final class WaterOutletChuteBlockEntity extends WaterDischargeBlockEntity {
    /** How far below the mouth the discharge may fall onto the water surface. */
    public static final int MAX_DROP = 4;
    /** Water source blocks needed in the 3 x 3 patch around, and the layer below, the receiving cell: a river or lake, not a puddle. */
    public static final int MIN_SOURCES = 4;
    /** The river temperature the intake draws, and the water the chute returns to, in degrees Celsius. */
    public static final double RIVER_C = ThermalWater.AMBIENT_C;
    public enum WaterState {
        OPEN("Faces open water"),
        UNLOADED("Mouth not loaded"),
        BLOCKED("Mouth blocked by a solid block"),
        DRY("No water in front of the mouth"),
        SHALLOW("Too little water: needs open river or lake water");
        public final String text;
        WaterState(String text) { this.text = text; }
    }
    private long checkedTick = Long.MIN_VALUE;
    private WaterState state = WaterState.DRY;
    private BlockPos receiving;

    public WaterOutletChuteBlockEntity(BlockPos p, BlockState s) { super(BwrBlockEntities.WATER_OUTLET_CHUTE.get(), p, s); }

    /** Result of the water-facing test (cached per game tick). */
    public WaterState waterState() {
        if (level == null || isRemoved()) return WaterState.UNLOADED;
        long now = level.getGameTime();
        if (checkedTick != now) { checkedTick = now; survey(); }
        return state;
    }
    /** The water cell the discharge lands in, or null while the chute does not face open water. */
    public BlockPos receivingCell() { waterState(); return state == WaterState.OPEN ? receiving : null; }
    @Override public boolean clearMouth() { return waterState() == WaterState.OPEN; }
    /** Heat the discharge adds to the river, in MW, from the flow and its rise over river temperature. */
    public double heatToRiverMW() { return flowKgPerS() * 4.184 * Math.max(0, temperatureC() - RIVER_C) / 1000; }

    private void survey() {
        receiving = null;
        BlockPos mouth = worldPosition.relative(getBlockState().getValue(WaterDischargeBlock.FACING));
        if (!level.isLoaded(mouth)) { state = WaterState.UNLOADED; return; }
        BlockPos cell = null;
        for (int k = 0; k <= MAX_DROP; k++) {
            BlockPos c = mouth.below(k);
            if (!level.isLoaded(c)) { state = WaterState.UNLOADED; return; }
            var s = level.getBlockState(c);
            if (s.getFluidState().is(FluidTags.WATER)) { cell = c; break; }
            if (!s.getCollisionShape(level, c).isEmpty()) { state = k == 0 ? WaterState.BLOCKED : WaterState.DRY; return; }
        }
        if (cell == null) { state = WaterState.DRY; return; }
        int sources = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 0; dy++) {
            BlockPos n = cell.offset(dx, dy, dz);
            if (!level.isLoaded(n)) continue;
            var f = level.getFluidState(n);
            if (f.isSource() && f.is(FluidTags.WATER)) sources++;
        }
        if (sources < MIN_SOURCES) { state = WaterState.SHALLOW; return; }
        receiving = cell.immutable();
        state = WaterState.OPEN;
    }

    /** A falling stream from the lip and a splash where it meets the river, on the fills that carry the water. */
    @Override protected void emit(ServerLevel server) {
        var d = getBlockState().getValue(WaterDischargeBlock.FACING);
        BlockPos cell = receiving;
        double lipX = worldPosition.getX() + .5 + d.getStepX() * .52, lipZ = worldPosition.getZ() + .5 + d.getStepZ() * .52, lipY = worldPosition.getY() + .22;
        server.sendParticles(ParticleTypes.FALLING_WATER, lipX, lipY, lipZ, 6, .22 * Math.abs(d.getStepZ()) + .02, .02, .22 * Math.abs(d.getStepX()) + .02, 0);
        if (cell != null) {
            double sx = cell.getX() + .5, sz = cell.getZ() + .5, sy = cell.getY() + .9;
            server.sendParticles(ParticleTypes.SPLASH, sx, sy, sz, 8, .3, .05, .3, .03);
            server.sendParticles(ParticleTypes.BUBBLE, sx, sy - .3, sz, 3, .25, .1, .25, .02);
        }
    }
}
