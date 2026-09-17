package dev.bwr.mod.steam;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** High-pressure steam piping. Both rendered arms and flow routing accept steam ports only.
 * Pressure remains a lumped vessel/header model, not a per-segment solver.
 */
public class PressurisedTubeBlock extends PipeBlock implements SteamLinePort {

    public static final MapCodec<PressurisedTubeBlock> CODEC =
            simpleCodec(PressurisedTubeBlock::new);

    /**
     * Blocks a pressurised tube treats as steam hardware, for anything that does
     * not implement {@link SteamLinePort} in code.
     *
     * <p>Defined at {@code data/bwr/tags/block/steam_line.json}. A tag is the
     * right shape for this because membership is a fact about the plant, not
     * about our source tree: a pack author adding a decorative header, or
     * another mod shipping a compatible valve, can join the steam line without
     * anyone recompiling. An undefined or emptied tag simply matches nothing —
     * tube-to-tube connection is an identity test and does not depend on it, so
     * a broken datapack degrades the line rather than deleting it.
     */
    public static final TagKey<Block> STEAM_LINE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "steam_line"));

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    public PressurisedTubeBlock(Properties properties) {
        super(0.25F, properties);
        BlockState base = stateDefinition.any();
        for (Direction d : Direction.values()) {
            base = base.setValue(PROPERTY_BY_DIRECTION.get(d), false);
        }
        registerDefaultState(base);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction d : Direction.values()) {
            builder.add(PROPERTY_BY_DIRECTION.get(d));
        }
    }

    // -----------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------

    /**
     * Whether a tube should grow an arm from {@code pos} towards the neighbour
     * that {@code towardsNeighbour} points at.
     *
     * <p>Protected and overridable rather than private, so a future tube variant
     * — an insulated run, a smaller-bore line — can narrow or widen the rule
     * without this class growing a special case for it.
     *
     * <p>The interface is consulted before the tag, and a block that implements
     * it has the final say on its own faces. That ordering is the honest one: a
     * block that has bothered to model a single nozzle knows more about where
     * its pipe lands than a list in a datapack does, and a tag cannot express
     * "this face only" at all.
     *
     * <p>The rule itself lives in {@link SteamLineNetwork#acceptsLineOn} rather
     * than here, and that is deliberate. {@code SteamLineNetwork} walks the line
     * to answer where a nozzle's steam actually goes; this method decides which
     * arms are drawn. Two copies of "is this steam hardware" would eventually
     * disagree, and the day they did, the line a player can see and the line the
     * physics uses would be different lines — the worst possible bug in a build
     * the player debugs by looking at it.
     *
     * @param neighbour        the state of the block being connected to
     * @param towardsNeighbour direction from the tube to that block
     */
    protected boolean connectsTo(BlockState neighbour, Direction towardsNeighbour) {
        return SteamLineNetwork.acceptsLineOn(neighbour, towardsNeighbour.getOpposite());
    }

    /**
     * A tube's own state with every arm resolved against what is currently
     * around {@code pos}.
     *
     * <p>Public because it is the honest way for anything that places a tube
     * without going through an item — a structure, a command, a test harness —
     * to get a tube that is already joined up, rather than one that only looks
     * right after something else happens to poke its neighbours.
     */
    public BlockState stateWithConnections(BlockGetter level, BlockPos pos) {
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(d),
                    connectsTo(level.getBlockState(pos.relative(d)), d));
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return stateWithConnections(context.getLevel(), context.getClickedPos());
    }

    /**
     * Re-resolve the one arm that faces the neighbour which just changed.
     *
     * <p>Only that arm, not all six: vanilla hands us the new neighbour state
     * directly and re-scanning the other five would be five block lookups per
     * neighbour update on every tube in the world, for an answer that cannot
     * have changed. This is also what keeps the connection symmetric with no
     * extra work — when a valve is placed next to a tube, the tube gets this
     * call for the face pointing at the valve, and when the valve is broken it
     * gets it again and the arm retracts.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction towardsNeighbour,
                                     BlockState neighbour, LevelAccessor level, BlockPos pos,
                                     BlockPos neighbourPos) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(towardsNeighbour),
                connectsTo(neighbour, towardsNeighbour));
    }

    // -----------------------------------------------------------------
    // Rotation and mirroring
    // -----------------------------------------------------------------
    //
    // PipeBlock inherits the no-op implementations from Block, which would leave
    // a rotated structure or a /clone with its arms pointing the way they did
    // before the rotation. The arms are the whole visible state of this block,
    // so permuting them is not optional once they can be anything but false.

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState out = state;
        for (Direction d : Direction.values()) {
            out = out.setValue(PROPERTY_BY_DIRECTION.get(rotation.rotate(d)),
                    state.getValue(PROPERTY_BY_DIRECTION.get(d)));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state;
        for (Direction d : Direction.values()) {
            out = out.setValue(PROPERTY_BY_DIRECTION.get(mirror.mirror(d)),
                    state.getValue(PROPERTY_BY_DIRECTION.get(d)));
        }
        return out;
    }
}
