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

/**
 * Pressurised steam tube — {@code SPEC.md} section 6.4.
 *
 * <p>Mekanism's pipes move gas by amount and have no concept of pressure, so a
 * custom tube is needed. But this tube deliberately does <b>not</b> solve
 * pressure per segment: a per-block pressure network is heavy, prone to
 * oscillation, and buys almost no visible fidelity.
 *
 * <p>Instead the pressure model uses two lumped volumes — the RPV steam dome
 * and the main steam header — and these blocks provide <b>connectivity and
 * validation only</b>. Their job is to answer structural questions: is this SRV
 * on a valid steam line, is the turbine actually connected, does this discharge
 * reach water.
 *
 * <h2>The connections used to never happen</h2>
 * This class declared the six directional booleans of {@link PipeBlock} and
 * registered a default state with every one of them false, and that was all it
 * did. Vanilla {@code PipeBlock} is only a shape and a property set — it
 * contains no connection logic whatsoever, which is why
 * {@code ChorusPlantBlock} and the fence/pane family each write their own. With
 * neither {@link #getStateForPlacement} nor {@link #updateShape} overridden,
 * every tube in the world was placed all-false and stayed all-false forever: the
 * multipart blockstate had seven complete parts and six of them could never be
 * selected, so a line of tubes rendered as a row of disconnected stubs and no
 * structural question about the steam line could be answered at all. The first
 * in-world playtest found it immediately; nothing static could, because the
 * assets and the state definition were both correct in isolation.
 *
 * <h2>What a tube connects to, and why that list is short</h2>
 * Tube to tube, always — that is block identity and needs no data behind it.
 * Beyond that, the rule is the {@link SteamLinePort} interface with the
 * {@code #bwr:steam_line} block tag as a data-driven fallback, so that steam
 * hardware declares itself rather than the tube enumerating it. Today that set
 * is the MSIV, the safety/relief valve and the turbine steam outlet: the main
 * steam system and nothing else.
 *
 * <p>Three near misses were considered and rejected on purpose.
 *
 * <p><b>The reactor vessel shell.</b> Tempting, because the steam has to leave
 * the vessel somehow — but {@code ReactorStructure.isShell} already accepts a
 * pressurised tube <i>as</i> shell material, so a tube built into the wall is
 * surrounded by vessel blocks on up to five sides. Connecting to the shell would
 * make that tube sprout arms into solid steel in every direction, and worse, it
 * would assert that any point on a two-hundred-block vessel wall is a steam tap.
 * The vessel gets one defined tap point instead, and that is what a dedicated
 * outlet block is for.
 *
 * <p><b>The condensate storage tank.</b> It holds water for emergency pump
 * suction and it talks to the world through NeoForge fluid handlers and buckets.
 * A steam tube landing on it would draw a path that nothing implements.
 *
 * <p><b>The turbine-driven emergency pumps, RCIC and HPCI.</b> A real plant runs
 * a steam line to both of them, and one day this mod might require it. It does
 * not today: their drive steam is bookkeeping through {@code ReactorEccsBus},
 * with no structural requirement anywhere, so connecting to them would be
 * decoration that looks like a claim. When that changes it is one line each in
 * the tag file, which is the point of having the tag.
 *
 * <h2>No waterlogging, deliberately</h2>
 * Nothing in this mod is waterloggable and the tube is the worst possible place
 * to start. It is a pressure boundary component that doubles as reactor vessel
 * shell material, so a waterloggable tube would put a water source block inside
 * the wall of the pressure vessel. It would also add a seventh property that no
 * part of the multipart blockstate consumes, doubling the state count to 128 for
 * no visual gain.
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
