package dev.bwr.mod.floodlight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A lamp that projects its light with {@link XenonBeam}: the xenon floodlight, the xenon high-bay and the HPS flat panel.
 * Everything that touches the world is in {@link BeamLampBlockEntity#reconcile()}; this class says when it runs.
 *
 * <p>Nothing ticks per block. A redstone change re-plans at once (from {@link #neighborChanged}). While a lamp is lit, one
 * scheduled block tick every {@link #REFRESH_TICKS} re-plans it (that catches a block placed or removed in the beam) and
 * schedules the next; an unlit lamp schedules nothing. Scheduled ticks run only in ticking chunks and never during a save
 * or a shutdown, so the re-plan never runs where its neighbours might be unloaded or while chunks are being unloaded.
 * Breaking or replacing the block (a player, an explosion, a command: every path goes through {@link #onRemove}) takes
 * every light it placed away again.
 */
public abstract class BeamLampBlock extends BaseEntityBlock {
    /** Lit while a redstone signal reaches it. */
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    /** Ticks between two re-plans of a lit lamp: 5 seconds. */
    public static final int REFRESH_TICKS = 100;

    protected BeamLampBlock(Properties properties) {
        super(properties);
    }

    /** A metal fixture; its own block glows at {@code ownLight} while lit. */
    protected static BlockBehaviour.Properties lampProperties(int ownLight) {
        return BlockBehaviour.Properties.of().pushReaction(PushReaction.BLOCK).mapColor(MapColor.METAL).strength(3.0F, 6.0F)
                .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
                .lightLevel(state -> state.getValue(LIT) ? ownLight : 0);
    }

    // ---- what the beam is, per lamp

    /** How far the light reaches, blocks. */
    public abstract double range();

    /** Half the cone angle, degrees. */
    public abstract double halfAngleDegrees();

    /** Smallest spacing of the lights, blocks. */
    public abstract int lightStep();

    /** The light level of the projected lights in a lit state. */
    public int projectedLevel(BlockState state) {
        return 15;
    }

    /** The unit direction the lens points. */
    public abstract Vec3 beamDirection(BlockState state, BeamLampBlockEntity lamp);

    /** Where the beam leaves the lens. */
    public abstract Vec3 beamOrigin(BlockState state, BeamLampBlockEntity lamp);

    /** The state with the lamp switched on or off (a subclass also resets what goes with it, such as a warm-up). */
    public BlockState switched(BlockState state, boolean lit) {
        return state.setValue(LIT, lit);
    }

    /** The next scheduled tick of a lit lamp, ticks from now. */
    protected int nextTickDelay(BlockState state) {
        return REFRESH_TICKS;
    }

    /** One scheduled tick of a lit lamp, before the re-plan (a warm-up step). Returns the state to re-plan with. */
    protected BlockState advance(ServerLevel level, BlockPos pos, BlockState state) {
        return state;
    }

    /** The compass or block direction a north-facing model was turned to. */
    public abstract Direction facing(BlockState state);

    // ---- when the lamp acts

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BeamLampBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && !state.is(oldState.getBlock())) {
            // The first plan runs a tick later, when the block entity is in place.
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BeamLampBlockEntity lamp) {
            Boolean powered = BeamLampBlockEntity.powered(server, pos);
            if (powered != null && powered != state.getValue(LIT)) {
                lamp.reconcile();
            }
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof BeamLampBlockEntity lamp)) {
            return;
        }
        if (state.getValue(LIT)) {
            BlockState next = advance(level, pos, state);
            if (next != state) {
                level.setBlock(pos, next, Block.UPDATE_CLIENTS);
            }
        }
        lamp.reconcile();
    }

    /** Called by the block entity after a re-plan: keeps one scheduled tick pending while the lamp is lit. */
    void afterReconcile(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getValue(LIT) && !level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, nextTickDelay(state));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel && level.getBlockEntity(pos) instanceof BeamLampBlockEntity lamp) {
            lamp.clearAll();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
        String key = getDescriptionId().replaceFirst("^block[.]", "tooltip.");
        lines.add(net.minecraft.network.chat.Component.translatable(key + ".light").withStyle(net.minecraft.ChatFormatting.GRAY));
        if (this instanceof XenonFloodlightBlock) {
            lines.add(net.minecraft.network.chat.Component.translatable(key + ".use").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        // A held item is never intercepted, so blocks can still be placed against the lamp.
        return stack.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }
}
