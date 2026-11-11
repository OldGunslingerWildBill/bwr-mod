package dev.bwr.mod.panel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.panel.AngledRunGeometry.Family;
import dev.bwr.mod.panel.AngledRunGeometry.Role;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One piece of a modular 45 degree run (CONTROL-PANELS-SPEC section 14): bend A, a diagonal unit or bend B, of the desk or
 * the tall panel family, inside or outside. Twelve blocks, one item each; the geometry is {@link AngledRunGeometry}. A piece
 * is a handful of blocks ({@code part} property: the index in {@link AngledRunGeometry.Piece#parts}, 0 the origin) placed
 * whole or not at all; the blocks that hold the middle of a unit's face (its {@link AngledRunGeometry.Face cores}) are panel
 * blocks with a block entity and a mounting face, the rest are body only, like the duplex board's body cells and the fixed
 * corners ({@link DeskCornerBlock}): a click on any block goes to the face the view ray lands on ({@link #faceEntity}),
 * and the faces of a unit are each a group of one block, not joined to the straight runs or to each other.
 *
 * <p><b>A run is extended one piece at a time.</b> The origins of consecutive pieces are one chain step apart
 * ({@link AngledRunGeometry#chainStep}), so placing a unit against the open end of a bend or of another unit puts it
 * exactly in line ({@link #resolve}): the item snaps to the end whichever block of the piece is clicked, takes the
 * facing and hand from it, and a bend placed against the end of a straight desk or bench takes its facing from the
 * straight blocks. The hand ({@code mirror}) is the mirror image of the whole piece: the plan runs to the operator's left
 * instead of right.
 *
 * <p>Breaking any block removes the piece with one drop (the origin's loot) and releases the components of its faces; a
 * piece that loses a block another way falls apart. No ticker; nothing saved beyond the cores' components.
 */
public final class AngledRunBlock extends PanelBlock {
    public static final BooleanProperty MIRROR = BooleanProperty.create("mirror");
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 9);
    private static final List<MapCodec<AngledRunBlock>> CODECS = new ArrayList<>();
    private static final Map<String, AngledRunBlock> UNITS = new HashMap<>();

    static {
        // the property range is a literal (tools-audit-assets.py reads it)
        if (AngledRunGeometry.MAX_PARTS != 10) {
            throw new IllegalStateException("the part property covers 0..9");
        }
        for (Family family : Family.values()) {
            for (int i = 0; i < 2; i++) {
                for (Role role : Role.values()) {
                    boolean inside = i == 0;
                    CODECS.add(simpleCodec(p -> new AngledRunBlock(p, family, inside, role)));
                }
            }
        }
    }

    private final Family family;
    private final boolean inside;
    private final Role role;

    public AngledRunBlock(Properties properties, Family family, boolean inside, Role role) {
        super(properties.pushReaction(PushReaction.BLOCK), family == Family.DESK ? PanelType.DESK_RUN : PanelType.RUN_BENCH);
        this.family = family;
        this.inside = inside;
        this.role = role;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(MIRROR, false).setValue(PART, 0));
        if (role == Role.UNIT) {
            UNITS.put(family.id() + inside, this);
        }
    }

    public Family family() {
        return family;
    }

    public boolean inside() {
        return inside;
    }

    public Role role() {
        return role;
    }

    public AngledRunGeometry.Piece piece() {
        return AngledRunGeometry.piece(family, inside, role);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODECS.get(family.ordinal() * 6 + (inside ? 0 : 3) + role.ordinal());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MIRROR, PART);
    }

    // ---- the faces

    private int part(BlockState state) {
        return Math.min(state.getValue(PART), piece().parts().size() - 1);
    }

    /** The face of a unit whose middle is in the block of this state, or null for a block that holds none. */
    public AngledRunGeometry.Face faceOf(BlockState state) {
        return AngledRunGeometry.faceOfPart(family, inside, role, part(state));
    }

    public boolean isCore(BlockState state) {
        return faceOf(state) != null;
    }

    public PanelGeometry.Frame faceFrame(BlockState state) {
        AngledRunGeometry.Face face = faceOf(state);
        return AngledRunGeometry.faceFrame(family, inside, face, state.getValue(FACING), state.getValue(MIRROR));
    }

    @Override
    public PanelType panelType(BlockState state) {
        AngledRunGeometry.Face face = faceOf(state);
        return face == null ? null : AngledRunGeometry.faceType(face);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isCore(state) ? new PanelBlockEntity(pos, state) : null;
    }

    @Override
    public boolean cellUsable(BlockState state, int cx, int cy) {
        return isCore(state) && super.cellUsable(state, cx, cy);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return AngledRunGeometry.shape(family, inside, role, part(state), state.getValue(FACING), state.getValue(MIRROR));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    // ---- positions

    /** Position of a part of a piece whose origin block is {@code origin}. */
    public static BlockPos partPos(BlockPos origin, Direction facing, boolean mirror, AngledRunGeometry.Part part) {
        return origin.relative(AngledRunGeometry.right(facing, mirror), part.right()).relative(facing, part.deep()).above(part.up());
    }

    /** The origin block of the piece the block at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        AngledRunBlock block = (AngledRunBlock) state.getBlock();
        AngledRunGeometry.Part part = block.piece().parts().get(block.part(state));
        Direction facing = state.getValue(FACING);
        return pos.relative(AngledRunGeometry.right(facing, state.getValue(MIRROR)), -part.right())
                .relative(facing, -part.deep()).below(part.up());
    }

    /** The positions of every block of the piece the block at {@code pos} belongs to. */
    public static List<BlockPos> piece(BlockPos pos, BlockState state) {
        AngledRunBlock block = (AngledRunBlock) state.getBlock();
        BlockPos origin = origin(pos, state);
        List<BlockPos> out = new ArrayList<>();
        for (AngledRunGeometry.Part part : block.piece().parts()) {
            out.add(partPos(origin, state.getValue(FACING), state.getValue(MIRROR), part));
        }
        return out;
    }

    /**
     * The origin of the piece of the chain that is {@code steps} chain steps from a piece with the given origin (the chain runs
     * from bend A toward bend B; every piece's origin is on the same line), for a facing and a hand.
     */
    public static BlockPos chainOrigin(BlockPos origin, Direction facing, boolean mirror, boolean inside, int steps) {
        int[] step = AngledRunGeometry.chainStep(inside);
        return origin.relative(AngledRunGeometry.right(facing, mirror), step[0] * steps).relative(facing, step[1] * steps);
    }

    private boolean isPart(BlockState state, Direction facing, boolean mirror, int part) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(MIRROR) == mirror && state.getValue(PART) == part;
    }

    // ---- a click on any block is a click on the face under the view ray

    /**
     * The panel block entity a click on the block at {@code pos} is meant for: the core of the face the view ray lands on
     * (the faces of a unit reach into the blocks of the units beside it, so those are looked at too, and the nearest hit
     * wins); with no ray ({@code eye} null) a unit's first face. Null for a piece with no face (the bends).
     */
    public static PanelBlockEntity faceEntity(BlockGetter level, BlockPos pos, Vec3 eye, Vec3 look) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AngledRunBlock block)) {
            return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
        }
        Direction facing = state.getValue(FACING);
        boolean mirror = state.getValue(MIRROR);
        BlockPos origin = origin(pos, state);
        AngledRunBlock unitBlock = UNITS.get(block.family.id() + block.inside);
        PanelBlockEntity first = null;
        PanelBlockEntity best = null;
        double bestT = Double.MAX_VALUE;
        double[] cell = new double[2];
        for (int step = -1; step <= 1; step++) {
            BlockPos unitOrigin = chainOrigin(origin, facing, mirror, block.inside, step);
            BlockState there = level.getBlockState(unitOrigin);
            if (unitBlock == null || !there.is(unitBlock) || there.getValue(FACING) != facing || there.getValue(MIRROR) != mirror
                    || there.getValue(PART) != 0) {
                continue;
            }
            for (AngledRunGeometry.Face face : unitBlock.piece().faces()) {
                BlockPos core = partPos(unitOrigin, facing, mirror, face.core());
                if (!(level.getBlockEntity(core) instanceof PanelBlockEntity be)) {
                    continue;
                }
                if (first == null) {
                    first = be;
                }
                if (eye == null) {
                    continue;
                }
                PanelGeometry.Frame frame = PanelGeometry.frame(be);
                if (PanelGeometry.rayToCell(frame, core.getX(), core.getY(), core.getZ(), eye.x, eye.y, eye.z, look.x, look.y, look.z, cell)
                        && cell[0] >= -0.5 && cell[0] <= PanelGeometry.GRID + 0.5
                        && cell[1] >= -0.5 && cell[1] <= PanelGeometry.rows(be.panelType()) + 0.5) {
                    double t = distance(frame, core, eye, look);
                    if (t < bestT) {
                        bestT = t;
                        best = be;
                    }
                }
            }
        }
        return best != null ? best : first;
    }

    private static double distance(PanelGeometry.Frame frame, BlockPos core, Vec3 eye, Vec3 look) {
        double denom = look.x * frame.nx + look.y * frame.ny + look.z * frame.nz;
        double ex = eye.x - core.getX();
        double ey = eye.y - core.getY();
        double ez = eye.z - core.getZ();
        return Math.abs(denom) < 1.0e-9 ? Double.MAX_VALUE
                : ((frame.ox - ex) * frame.nx + (frame.oy - ey) * frame.ny + (frame.oz - ez) * frame.nz) / denom;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        PanelBlockEntity face = faceEntity(level, pos, player.getEyePosition(), player.getLookAngle());
        if (face == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return super.useItemOn(stack, face.getBlockState(), level, face.getBlockPos(), player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        PanelBlockEntity face = faceEntity(level, pos, player.getEyePosition(), player.getLookAngle());
        if (face == null) {
            return InteractionResult.PASS;
        }
        return super.useWithoutItem(face.getBlockState(), level, face.getBlockPos(), player, hit);
    }

    // ---- placing: snap to the end of what is there

    /** Where and how a piece goes. */
    public record Placement(BlockPos origin, Direction facing, boolean mirror) {
    }

    private static final int[][] STEPS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Whether a piece of this block fits at a placement: every block free (or replaceable) and allowed. */
    public boolean fits(BlockPlaceContext context, Placement at) {
        Level level = context.getLevel();
        BlockState first = defaultBlockState().setValue(FACING, at.facing).setValue(MIRROR, at.mirror);
        CollisionContext collision = context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        List<AngledRunGeometry.Part> parts = piece().parts();
        for (int i = 0; i < parts.size(); i++) {
            BlockPos pos = partPos(at.origin, at.facing, at.mirror, parts.get(i));
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                return false;
            }
            if (i != 0 && (!AssemblyAccess.permitted(level, context.getPlayer(), pos) || !level.getBlockState(pos).canBeReplaced(context)
                    || !level.isUnobstructed(first.setValue(PART, i), pos, collision))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The placement of this piece for a placement context: against the open end of a piece of the same run (another
     * piece of the same family and kind: the origin one chain step along, the facing and hand taken from it, whichever
     * block of it was clicked); else for a bend against the end of a straight desk or bench of the same facing; else free
     * standing, facing the placer, the mirror image when sneaking. The first of these that fits wins.
     */
    public Placement resolve(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        Direction faceClicked = context.getClickedFace();
        Vec3 click = context.getClickLocation();
        // 1. the pieces of the same run around the target
        Map<BlockPos, BlockState> seen = new HashMap<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos at = target.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    if (state.getBlock() instanceof AngledRunBlock other && other.family == family && other.inside == inside) {
                        seen.put(origin(at, state), state);
                    }
                }
            }
        }
        BlockPos clickedOrigin = null;
        if (!context.replacingClickedOnBlock()) {
            BlockPos clicked = target.relative(faceClicked.getOpposite());
            BlockState clickedState = level.getBlockState(clicked);
            if (clickedState.getBlock() instanceof AngledRunBlock other && other.family == family && other.inside == inside) {
                clickedOrigin = origin(clicked, clickedState);
            }
        }
        Placement best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : seen.entrySet()) {
            AngledRunBlock other = (AngledRunBlock) entry.getValue().getBlock();
            Direction facing = entry.getValue().getValue(FACING);
            boolean mirror = entry.getValue().getValue(MIRROR);
            for (int steps : stepsFrom(other.role, role)) {
                Placement option = new Placement(chainOrigin(entry.getKey(), facing, mirror, inside, steps), facing, mirror);
                boolean exact = option.origin.equals(target);
                if (!exact && !entry.getKey().equals(clickedOrigin)) {
                    continue; // a piece nobody clicked: only an exact fit counts
                }
                if (!fits(context, option)) {
                    continue;
                }
                double score = exact ? -1.0 : Math.hypot(option.origin.getX() + 0.5 - click.x, option.origin.getZ() + 0.5 - click.z);
                if (score < bestScore) {
                    bestScore = score;
                    best = option;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // 2. a bend against the end of a straight run
        if (role != Role.UNIT) {
            Placement straight = againstStraight(context, target, faceClicked, click);
            if (straight != null) {
                return straight;
            }
        }
        // 3. free standing
        Direction facing = context.getHorizontalDirection().getOpposite();
        return new Placement(target, facing, context.getPlayer() != null && context.getPlayer().isShiftKeyDown());
    }

    /** Chain steps from a placed piece of one role to where a new piece of another role goes, along the chain. */
    private static int[] stepsFrom(Role placed, Role held) {
        return switch (placed) {
            case BEND_A -> held == Role.UNIT || held == Role.BEND_B ? new int[] {1} : new int[0];
            case UNIT -> switch (held) {
                case UNIT -> new int[] {1, -1};
                case BEND_A -> new int[] {-1};
                case BEND_B -> new int[] {1};
            };
            case BEND_B -> held == Role.UNIT || held == Role.BEND_A ? new int[] {-1} : new int[0];
        };
    }

    /** A bend next to the last block of a straight desk (or bench) of the same family, the facing taken from it. */
    private Placement againstStraight(BlockPlaceContext context, BlockPos target, Direction faceClicked, Vec3 click) {
        Level level = context.getLevel();
        if (context.replacingClickedOnBlock()) {
            return null;
        }
        BlockPos clicked = target.relative(faceClicked.getOpposite());
        BlockPos straight = straightBlock(level, clicked);
        if (straight == null) {
            return null;
        }
        BlockState state = level.getBlockState(straight);
        Direction desk = state.getValue(FACING);
        Placement best = null;
        double bestScore = Double.MAX_VALUE;
        int[] offset = piece().straight();
        for (boolean mirror : new boolean[] {false, true}) {
            Direction facing;
            if (role == Role.BEND_A) {
                facing = desk;
            } else {
                // the straight run B faces along the piece's x (inside) or against it (outside)
                Direction wanted = inside ? desk : desk.getOpposite();
                facing = mirror ? wanted.getCounterClockWise() : wanted.getClockWise();
                if (AngledRunGeometry.right(facing, mirror) != wanted) {
                    continue;
                }
            }
            // the straight block is origin + straight offset, in the piece's own frame
            BlockPos origin = straight.relative(AngledRunGeometry.right(facing, mirror), -offset[0])
                    .relative(facing, -offset[1]).below(offset[2]);
            Placement option = new Placement(origin, facing, mirror);
            if (!fits(context, option)) {
                continue;
            }
            double score = origin.equals(target) ? -1.0 : Math.hypot(origin.getX() + 0.5 - click.x, origin.getZ() + 0.5 - click.z);
            if (score < bestScore) {
                bestScore = score;
                best = option;
            }
        }
        return best;
    }

    /** The position of the straight desk or bench block (at floor level of the run) that a click on {@code clicked} means, or null. */
    private BlockPos straightBlock(Level level, BlockPos clicked) {
        BlockState state = level.getBlockState(clicked);
        if (family == Family.DESK) {
            if (state.getBlock() instanceof PanelBlock block && (block.panelType(state) == PanelType.DESK
                    || state.getBlock() instanceof DeskPanelBlock) && state.hasProperty(FACING)) {
                return clicked;
            }
            return null;
        }
        for (int down = 0; down <= 3; down++) {
            BlockPos at = clicked.below(down);
            BlockState there = level.getBlockState(at);
            if (there.getBlock() instanceof PanelBlock block && block.panelType(there) == PanelType.BENCHBOARD) {
                return at;
            }
            if (!(there.getBlock() instanceof PanelBlock b2) || (b2.panelType(there) != PanelType.VERTICAL
                    && b2.panelType(there) != PanelType.HEADER_TILT && down > 0)) {
                return null;
            }
        }
        return null;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Placement placement = resolve(context);
        if (placement == null || !placement.origin.equals(context.getClickedPos()) || !fits(context, placement)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, placement.facing).setValue(MIRROR, placement.mirror).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(PART) == 0) {
            Direction facing = state.getValue(FACING);
            boolean mirror = state.getValue(MIRROR);
            List<AngledRunGeometry.Part> parts = piece().parts();
            for (int i = 1; i < parts.size(); i++) {
                level.setBlock(partPos(pos, facing, mirror, parts.get(i)), state.setValue(PART, i), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        boolean mirror = state.getValue(MIRROR);
        BlockPos origin = origin(pos, state);
        List<AngledRunGeometry.Part> parts = piece().parts();
        for (int i = 0; i < parts.size(); i++) {
            if (partPos(origin, facing, mirror, parts.get(i)).equals(neighbourPos) && !isPart(neighbour, facing, mirror, i)) {
                return Blocks.AIR.defaultBlockState(); // a block of the piece went: the piece falls apart
            }
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            boolean mirror = state.getValue(MIRROR);
            BlockPos origin = origin(pos, state);
            List<BlockPos> others = new ArrayList<>();
            List<AngledRunGeometry.Part> parts = piece().parts();
            for (int i = 0; i < parts.size(); i++) {
                BlockPos other = partPos(origin, facing, mirror, parts.get(i));
                BlockState there = level.getBlockState(other);
                if (other.equals(pos) || !isPart(there, facing, mirror, i)) {
                    continue;
                }
                if (i == 0 && !player.isCreative()) {
                    // The one item of the piece: the origin's loot, whichever block the player broke.
                    Block.dropResources(there, level, other, level.getBlockEntity(other), player, player.getMainHandItem());
                }
                others.add(other);
            }
            for (BlockPos other : others) {
                BlockState there = level.getBlockState(other);
                // Known shape: no shape updates, so the rest of the piece is not also torn down (and dropped) by them.
                // Removing a core releases its components (PanelBlock.onRemove).
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, other, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
