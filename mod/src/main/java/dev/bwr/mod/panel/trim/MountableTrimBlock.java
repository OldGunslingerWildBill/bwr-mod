package dev.bwr.mod.panel.trim;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.panel.PanelBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * A trim block whose angled face takes control-panel components: the header strip and canopy (an annunciator panel
 * leaning back 5 degrees) and the two chamfer corners (a 45 degree facet). It is a {@link PanelBlock}, so it has the
 * panel block entity, groups with its neighbours, and drops what is mounted on it when it breaks, like any panel; the
 * shape, model, drop and registry id are those the decorative block had, and the placed block is the same state
 * (a facing and nothing else), so a world saved before they were mountable loads: the block entity they lacked is
 * created the first time anything asks for it (Level.getBlockEntity creates a missing one for a block that has an
 * entity), see {@code PanelAngledMountTests.anOldSavedTrimBlockLoadsAndBecomesMountable}.
 *
 * <p>Which face and which neighbours it joins are {@link TrimPiece#mountType()}'s {@link dev.bwr.mod.panel.PanelType}.
 */
public final class MountableTrimBlock extends PanelBlock {
    private static final Map<TrimPiece, MapCodec<MountableTrimBlock>> CODECS = new EnumMap<>(TrimPiece.class);

    static {
        for (TrimPiece piece : TrimPiece.values()) {
            if (piece.mountType() != null) {
                CODECS.put(piece, simpleCodec(properties -> new MountableTrimBlock(properties, piece)));
            }
        }
    }

    private final TrimPiece piece;
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public MountableTrimBlock(Properties properties, TrimPiece piece) {
        super(properties, piece.mountType());
        this.piece = piece;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            shapes.put(facing, TrimGeometry.shape(piece, facing, true));
        }
    }

    public TrimPiece piece() {
        return piece;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODECS.get(piece);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    /** A canopy's hood covers the top row of cells (it stands 0.42 out over the face there), so nothing mounts in it. */
    @Override
    public boolean cellUsable(BlockState state, int cx, int cy) {
        return super.cellUsable(state, cx, cy) && !(piece == TrimPiece.HEADER_CANOPY && cy == 0);
    }
}
