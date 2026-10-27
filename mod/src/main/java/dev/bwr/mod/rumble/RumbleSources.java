package dev.bwr.mod.rumble;

import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The plant's own rumble sources, fed from tickers that already exist (RUMBLE.md). Each hook reads a quantity the physics has
 * already worked out and passes it on; none of them adds a ticker, reads a chunk, or acts on the plant.
 */
public final class RumbleSources {
    private RumbleSources() {}

    /** How often the hooks refresh a source, ticks. The source lapses after {@link Rumble#LAPSE_TICKS}. */
    public static final int REFRESH_TICKS = 5;

    /**
     * Steam condensing in a suppression pool: the pool's inflow of steam enthalpy, W (relief valve discharge through quenchers or open
     * water, steam wall inlets, and turbine-driven pump exhaust, as the pool already sums them). Called from the pool controller's tick.
     */
    public static void pool(Level level, BlockPos controller, long gameTime, double enthalpyFlowKw) {
        if (!(level instanceof ServerLevel server) || gameTime % REFRESH_TICKS != 0) return;
        double w = enthalpyFlowKw * 1000.0;
        if (w > 1e5) Rumble.sustain(server, controller, Rumble.Kind.POOL, w);
        else Rumble.release(server, controller, Rumble.Kind.POOL);
    }

    /**
     * A turbine train: its shaft vibration (mm/s, as the auxiliaries publish it) and speed. Called from the auxiliaries' step with the
     * sections it advanced. The source sits at the centre of those sections.
     */
    public static void turbine(List<PowerModuleBlockEntity> active, double vibrationMmPerS, double rpm, long gameTime) {
        if (active.isEmpty() || gameTime % REFRESH_TICKS != 0) return;
        Level level = active.get(0).getLevel();
        if (!(level instanceof ServerLevel server)) return;
        double w = RumbleLaw.turbinePowerW(vibrationMmPerS, rpm);
        // Keyed by the lowest position of the train, so the order of the list cannot make two sources of one train.
        BlockPos first = active.get(0).getBlockPos();
        for (var m : active) if (m.getBlockPos().asLong() < first.asLong()) first = m.getBlockPos();
        if (w <= 1e3) {
            Rumble.release(server, first, Rumble.Kind.TURBINE);
            return;
        }
        double x = 0, y = 0, z = 0;
        for (var m : active) {
            BlockPos p = m.getBlockPos();
            x += p.getX() + 0.5;
            y += p.getY() + 0.5;
            z += p.getZ() + 0.5;
        }
        int n = active.size();
        Rumble.sustain(server, Rumble.keyOf(first, Rumble.Kind.TURBINE), new Vec3(x / n, y / n, z / n), Rumble.Kind.TURBINE, w);
    }
}
