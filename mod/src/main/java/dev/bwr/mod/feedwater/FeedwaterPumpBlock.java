package dev.bwr.mod.feedwater;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.feedwater.FeedwaterDesign;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * One reactor feed pump — motor-driven or turbine-driven, {@code SPEC.md}
 * section 15.
 *
 * <p>Both are the same block class carrying a different {@link FeedwaterDesign},
 * for the same reason the six emergency machines are: everything that separates
 * them is a number. There is no behavioural special-casing, which is what makes
 * the difference between them believable rather than asserted.
 *
 * <p>Satellite blocks, in the pattern the recirculation pumps and the ECCS
 * machines already use: they find a reactor when placed and drop out when
 * broken, and never invalidate the multiblock either way. Losing feedwater is a
 * loss of capability — a serious one — not a structural fault.
 *
 * <h2>Nothing here controls level</h2>
 * There is no level element, no steam flow element, no three-element controller
 * and no feed pump trip. A feed pump takes a commanded flow demand from a
 * redstone signal, from the player's Lua, or from nowhere at all, and delivers
 * what its curve and its drive allow against the pressure it is pushing into.
 * Holding vessel level is the player's program, and if nobody wrote one the
 * level does whatever the boil-off and the demand between them produce.
 */
public class FeedwaterPumpBlock extends BaseEntityBlock {

    public static final MapCodec<FeedwaterPumpBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    propertiesCodec(),
                    Codec.STRING.fieldOf("design").forGetter(b -> b.design.id())
            ).apply(instance, (properties, id) -> new FeedwaterPumpBlock(properties, designOrDefault(id))));

    private final FeedwaterDesign design;

    public FeedwaterPumpBlock(Properties properties, FeedwaterDesign design) {
        super(properties);
        this.design = design;
    }

    private static FeedwaterDesign designOrDefault(String id) {
        FeedwaterDesign d = FeedwaterDesign.byId(id);
        return d != null ? d : FeedwaterDesign.MOTOR_FEED_PUMP;
    }

    /** The nameplate this block was registered with. */
    public FeedwaterDesign design() {
        return design;
    }

    /** The design of whichever feed pump a block state belongs to. */
    public static FeedwaterDesign designOf(BlockState state) {
        if (state.getBlock() instanceof dev.bwr.mod.eccs.PumpAssemblyBlock p)
            return p.kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.TURBINE_FEED
                    ? FeedwaterDesign.TURBINE_FEED_PUMP : FeedwaterDesign.MOTOR_FEED_PUMP;
        return state.getBlock() instanceof FeedwaterPumpBlock b
                ? b.design() : FeedwaterDesign.MOTOR_FEED_PUMP;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FeedwaterPumpBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.FEEDWATER_PUMP.get(),
                FeedwaterPumpBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof FeedwaterPumpBlockEntity be) {
            be.markBindingDirty();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof FeedwaterPumpBlockEntity be) {
            // Stop the water arriving the moment the pump stops existing, rather
            // than waiting three ticks for the contribution to go stale.
            be.detach();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Redstone is a first-class actuation path, exactly as it is for the ECCS
     * machines and the relief valves, so a player who has written no Lua at all
     * can still start feedwater from a lever. A computer-controlled pump ignores
     * redstone so that the two cannot fight over the same command.
     *
     * <p>This is a wire, not a control system: the signal says run, and the pump
     * runs at whatever demand it was last given. Whatever produced the signal —
     * including a level switch the player built out of comparators — is the
     * player's design and not ours.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof FeedwaterPumpBlockEntity be) {
            be.markBindingDirty();
            if (!be.isComputerControlled() && !be.isPanelControlled()) {
                boolean powered = level.hasNeighborSignal(pos);
                if (powered != be.isRunning()) {
                    be.setRunning(powered);
                }
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer sp)
            dev.bwr.mod.gui.PumpControlMenu.open(sp, pos, pos);
        return InteractionResult.CONSUME;
    }
}
