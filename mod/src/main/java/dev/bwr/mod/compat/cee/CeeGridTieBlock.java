package dev.bwr.mod.compat.cee;

import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.bwr.mod.grid.GcbConnections;
import dev.bwr.mod.grid.GridTieBlock;
import dev.bwr.mod.world.LoadedWorld;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The Grid Tie with CEE's wire nodes: the six isolated-phase-bus flanges. Only the assembled controller cell (the control cubicle) has
 * nodes, at the flange points of {@link GcbConnections} as offsets from its position (they lie outside the block); every other cell, and a
 * Grid Tie saved as one block, has none. Implementing {@link ElectricalDeviceBlock} registers nothing by itself, so the lifecycle of CEE's
 * {@code SimpleElectricalDeviceBlock} is copied here: nodes are registered when the controller is placed and ticked (it ticks every two
 * seconds already, for unload recovery), the wires come off when a player breaks the breaker (from any cell) or a Create wrench is
 * used sneaking, and every node and wire goes when the multiblock is broken by any cause or loses its assembled flag. CEE's mixin
 * ({@code InWorldNodeMixin} here) sends a click on any cell to the controller's nodes.
 */
public class CeeGridTieBlock extends GridTieBlock implements ElectricalDeviceBlock<GridTieDevice>, IWrenchable {
    /** Node size of a flange: the IPB-45 flange of CEE Grid, 1.3 m. */
    public static final float NODE_SIZE = 1.3f;
    private static final String[] LABELS = {"X gen", "Y gen", "Z gen", "X xfmr", "Y xfmr", "Z xfmr"};

    public CeeGridTieBlock(Properties properties) {
        super(properties);
    }

    @Override
    public SimulatedDeviceType<GridTieDevice> getDevice() {
        return CeeCompat.GRID_TIE_DEVICE.get();
    }

    private static boolean hasNodes(BlockState state) {
        return state.getValue(ASSEMBLED) && state.getValue(CELL) == CONTROLLER;
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        Map<Integer, Vec3> nodes = new LinkedHashMap<>();
        if (hasNodes(state))
            for (int id = 0; id < 6; id++)
                nodes.put(id, offset(pos, state, id));
        return nodes;
    }

    @Override
    public @Nullable Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return hasNodes(state) && id >= 0 && id < 6 ? offset(pos, state, id) : null;
    }

    /** Flange {@code id} (0-2 the generator end of poles X, Y, Z; 3-5 the transformer end) as an offset from the block position. */
    private static Vec3 offset(BlockPos pos, BlockState state, int id) {
        var end = id < 3 ? GcbConnections.End.GENERATOR : GcbConnections.End.TRANSFORMER;
        var phase = GcbConnections.Phase.values()[id % 3];
        return GcbConnections.flange(pos, state.getValue(FACING), phase, end).subtract(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return Component.literal(id >= 0 && id < 6 ? LABELS[id] : "?");
    }

    @Override
    public float getNodeSize(Level level, BlockPos pos, BlockState state, int id) {
        return NODE_SIZE;
    }

    // --- lifecycle: the four behaviours of CEE's SimpleElectricalDeviceBlock, adapted to a 72-cell block ---

    @Override
    protected void onPlace(BlockState s, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(s, level, pos, old, moving); // schedules the unload-recovery tick of every cell
        if (level instanceof ServerLevel sl && !old.is(this) && hasNodes(s))
            registerNodes(sl, pos, s);
    }

    @Override
    protected void tick(BlockState s, ServerLevel level, BlockPos pos, RandomSource random) {
        super.tick(s, level, pos, random);
        if (hasNodes(s) && level.getBlockState(pos).is(this))
            registerNodes(level, pos, s);
    }

    private void registerNodes(ServerLevel level, BlockPos pos, BlockState s) {
        InfrastructureSavedData.load(level).registerOrUpdateNodes(pos, new ArrayList<>(getNodePositions(level, pos, s).keySet()));
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState s, Player player) {
        // CEE's own blocks take the wires off a player who is not creative here
        if (level instanceof ServerLevel sl && !player.isCreative() && s.getValue(ASSEMBLED) && ownsController(level, pos, s))
            removeConnections(sl, origin(pos, s));
        return super.playerWillDestroy(level, pos, s, player);
    }

    private static void removeConnections(ServerLevel level, BlockPos controller) {
        var sd = InfrastructureSavedData.load(level);
        for (var node : sd.getNodesAt(controller))
            for (var connection : sd.getConnections(node))
                sd.removeConnection(connection);
    }

    @Override
    protected void onRemove(BlockState s, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        // Broken by anything (any cell, an explosion, /setblock) or no longer assembled: the nodes and their wires go, the wires dropping as items
        if (level instanceof ServerLevel sl && s.getValue(ASSEMBLED) && (!replacement.is(this) || !replacement.getValue(ASSEMBLED)) && ownsController(level, pos, s))
            InfrastructureSavedData.load(sl).registerOrUpdateNodes(origin(pos, s), List.of());
        super.onRemove(s, level, pos, replacement, moving);
    }

    /** Whether the cell at {@code pos} (of state {@code s}) belongs to a tie whose controller is still there: an orphan must not touch what stands at its root. */
    private boolean ownsController(Level level, BlockPos pos, BlockState s) {
        var root = origin(pos, s);
        if (root.equals(pos))
            return s.getValue(CELL) == CONTROLLER;
        var rootState = LoadedWorld.state(level, root);
        return rootState != null && rootState.is(this) && rootState.getValue(ASSEMBLED) && rootState.getValue(CELL) == CONTROLLER && rootState.getValue(FACING) == s.getValue(FACING);
    }

    // --- Create's wrench ---

    /** The wrench must not turn one cell of the breaker on its own (the default rotates the clicked blockstate). */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Sneaking with the wrench takes the bus wires off (as on any CEE device); the breaker is broken like any block, not picked up. */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        if (state.getValue(ASSEMBLED) && context.getPlayer() != null && ownsController(context.getLevel(), context.getClickedPos(), state)) {
            removeWiresByPlayer(context.getPlayer(), context.getLevel(), origin(context.getClickedPos(), state));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
