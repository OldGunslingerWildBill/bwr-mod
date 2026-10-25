package dev.bwr.mod.rwcu;

import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.ProcessAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * A one-block machine of the reactor water cleanup skid. Every flange is on a horizontal face; {@link #roleAt} says which
 * (the model for each unit puts its flanges on the same faces). Water pipes meet the flanges like any other machine
 * face, so the pipe survey ({@code PipeTopology}) finds the machine at the end of a line.
 */
public abstract class RwcuUnitBlock extends BaseEntityBlock implements ProcessAssembly {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** What a flange is for. Cooling-water flanges are not on the process circuit. */
    public enum Role {
        PUMP_SUCTION(false), PUMP_DISCHARGE(true),
        HOT_IN(false), HOT_OUT(true), COLD_IN(false), COLD_OUT(true),
        PROCESS_IN(false), PROCESS_OUT(true), COOLING_IN(false), COOLING_OUT(true),
        BED_IN(false), BED_OUT(true);
        private final boolean outlet;
        Role(boolean outlet) { this.outlet = outlet; }
        public boolean outlet() { return outlet; }
    }

    protected RwcuUnitBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state != old) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
    }

    /** The role of the flange on {@code face}, or null when that face has none. */
    public abstract Role roleAt(BlockState state, Direction face);
    /** The face a role is on. */
    public abstract Direction faceOf(BlockState state, Role role);

    @Override public BlockPos origin(BlockPos p, BlockState s) { return p; }
    @Override public boolean hasPort(AssemblyPort role) { return !role.isSteam(); }
    @Override public BlockPos portPosition(BlockPos p, BlockState s, AssemblyPort role) { return p; }
    @Override public Direction portFace(BlockState s, AssemblyPort role) {
        for (Role r : Role.values()) {
            Direction d = faceOf(s, r);
            if (d != null && r.outlet() == (role == AssemblyPort.WATER_DISCHARGE) && r != Role.COOLING_IN && r != Role.COOLING_OUT) return d;
        }
        return s.getValue(FACING);
    }
    @Override public AssemblyPort portAt(BlockState s, Direction face) {
        Role r = roleAt(s, face);
        return r == null ? null : r.outlet() ? AssemblyPort.WATER_DISCHARGE : AssemblyPort.WATER_SUCTION;
    }
    @Override public boolean complete(Level l, BlockPos p, BlockState s) { return l.isLoaded(p) && l.getBlockState(p) == s; }

    @Override public abstract BlockEntity newBlockEntity(BlockPos pos, BlockState state);
}
