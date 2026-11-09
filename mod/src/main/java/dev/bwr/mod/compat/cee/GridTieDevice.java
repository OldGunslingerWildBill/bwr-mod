package dev.bwr.mod.compat.cee;

import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.BridgeCollector;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import dev.bwr.mod.grid.GridTieBlock;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.world.LoadedWorld;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The Grid Tie (generator circuit breaker) in CEE's circuit: six nodes on the controller cubicle's position, 0, 1, 2 the generator-end
 * flanges of poles X, Y, Z (phases A, B, C) and 3, 4, 5 the transformer-end flanges. While the breaker is closed each pole is a small resistor from
 * its generator-end flange to its transformer-end flange; open, nothing is bridged and the poles are isolated. The breaker state is the
 * block entity's own ({@link GridTieBlockEntity#closed()}); this device keeps none. In DC the outer poles X and Z are + and -, in AC all
 * three carry phases: the breaker needs no mode.
 *
 * <p>CEE gives every cell of a multiblock whose block is a device block its own device of this type (it cannot tell a cell from a block);
 * only the assembled controller cell has nodes and bridges anything, the other 104 do nothing.
 */
public class GridTieDevice extends SimpleElectricalDevice {
    /**
     * Resistance of one closed pole, generator flange to transformer flange.
     * TODO: the CEE session is sourcing ABB HEC 7/8's real main-circuit resistance (times 10, the in-game convention); replace this placeholder.
     */
    public static final double POLE_OHMS = 1.0e-5;

    /** Test hook: when set, called with the results of every solve in which a Grid Tie device is present (so a test can read any bridge in the circuit). */
    public static volatile Consumer<SimulationResults> testProbe;

    /** The current through each closed pole, in amps, from the last solve (0 for an open breaker). */
    public final double[] poleAmps = new double[3];

    public GridTieDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) {
        super(level, pos, sd, type);
    }

    /** The block entity of the assembled controller cell at this position, or null for any other cell. Never loads a chunk. */
    private GridTieBlockEntity tie() {
        var state = LoadedWorld.state(level, pos);
        if (state == null || !(state.getBlock() instanceof GridTieBlock) || !state.getValue(GridTieBlock.ASSEMBLED) || state.getValue(GridTieBlock.CELL) != GridTieBlock.CONTROLLER)
            return null;
        return LoadedWorld.blockEntity(level, pos) instanceof GridTieBlockEntity be && !be.isRemoved() ? be : null;
    }

    @Override
    public void preTick(BridgeCollector bridges) {
        var tie = tie();
        if (tie == null || !tie.closed())
            return;
        var builder = bridges.builder(pos);
        for (int i = 0; i < 3; i++)
            builder.resistor(i, i + 3, POLE_OHMS);
        closedLastTick = true;
    }

    private boolean closedLastTick;

    @Override
    public void postTick(SimulationResults results) {
        for (int i = 0; i < 3; i++) {
            double a = closedLastTick ? results.getCurrentThrough(pos, i, i + 3) : 0;
            poleAmps[i] = Double.isFinite(a) ? a : 0;
        }
        closedLastTick = false;
        var probe = testProbe;
        if (probe != null && tie() != null)
            probe.accept(results);
    }
}
