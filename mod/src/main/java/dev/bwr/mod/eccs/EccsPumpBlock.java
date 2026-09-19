package dev.bwr.mod.eccs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.eccs.EccsDesign;
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
 * One emergency core cooling machine — RCIC, HPCI, HPCS, LPCS, RHR or SLC.
 *
 * <p>All six are the same block class carrying a different
 * {@link EccsDesign}, because everything that distinguishes them is a number:
 * a pump curve, a drive, a motor rating, a delivery path. There is no
 * behavioural special-casing anywhere, which is what makes the differences
 * between them believable rather than asserted.
 *
 * <p>Satellite blocks, in the pattern the recirculation pumps already use: they
 * find a reactor when placed and drop out when broken, and never invalidate the
 * multiblock either way. Losing an emergency system is a loss of capability,
 * not a structural fault.
 *
 * <p>Nothing about this block starts itself. There is no initiation signal, no
 * level permissive and no automatic actuation of any kind — {@code SPEC.md}
 * section 9 is explicit that there is no hardwired backstop and that players
 * build their own redundancy in Lua.
 */
public class EccsPumpBlock extends BaseEntityBlock {

    public static final MapCodec<EccsPumpBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    propertiesCodec(),
                    Codec.STRING.fieldOf("design").forGetter(b -> b.design.id())
            ).apply(instance, (properties, id) -> new EccsPumpBlock(properties, designOrDefault(id))));

    private final EccsDesign design;

    public EccsPumpBlock(Properties properties, EccsDesign design) {
        super(properties);
        this.design = design;
    }

    private static EccsDesign designOrDefault(String id) {
        EccsDesign d = EccsDesign.byId(id);
        return d != null ? d : EccsDesign.RCIC;
    }

    /** The nameplate this block was registered with. */
    public EccsDesign design() {
        return design;
    }

    /** The design of whichever ECCS machine a block state belongs to. */
    public static EccsDesign designOf(BlockState state) {
        if (state.getBlock() instanceof PumpAssemblyBlock p) return switch(p.kind()) {
            case HPCS -> EccsDesign.HPCS; case LPCS -> EccsDesign.LPCS; case RHR -> EccsDesign.RHR;
            default -> throw new IllegalArgumentException("Not an ECCS pump");
        };
        return state.getBlock() instanceof EccsPumpBlock b ? b.design() : EccsDesign.RCIC;
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
        return new EccsPumpBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, dev.bwr.mod.registry.BwrBlockEntities.ECCS_PUMP.get(),
                EccsPumpBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof EccsPumpBlockEntity be) {
            be.markBindingDirty();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof EccsPumpBlockEntity be) {
            // Stop the water arriving the moment the machine stops existing,
            // rather than waiting for the contribution to go stale.
            be.detach();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Redstone is a first-class actuation path, exactly as it is for the relief
     * valves, so a player who has written no Lua at all can still start an
     * emergency pump from a lever. A computer-controlled machine ignores
     * redstone so that the two cannot fight over the same command.
     *
     * <p>This is a wire, not a protection system: the signal says run, and the
     * machine runs. Whatever produced the signal is the player's design.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof EccsPumpBlockEntity be) {
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
