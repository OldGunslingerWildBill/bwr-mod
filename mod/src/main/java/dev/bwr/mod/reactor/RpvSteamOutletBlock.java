package dev.bwr.mod.reactor;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import dev.bwr.mod.steam.SteamLinePort;

/**
 * The RPV main steam nozzle block — a piece of the vessel shell that carries
 * steam out of it.
 *
 * <p>Unlike the turbine steam outlet, which is a satellite standing off in the
 * turbine hall and binding to the nearest controller, this block <b>is</b> the
 * pressure boundary: {@link ReactorStructure#isShell} accepts it as wall, and
 * breaking one leaves a hole in the vessel exactly as breaking any other wall
 * block does. That is the whole point of it. Steam leaves a real reactor
 * through a penetration welded into the vessel, not through a machine parked
 * nearby, and until this block existed there was no penetration to weld
 * anything to.
 *
 * <h2>No blockstate properties, on purpose</h2>
 * Same reasoning the turbine steam outlet gives: the nozzle's condition is a
 * continuous stop position, not an on/off pose, and a boolean state property
 * would be a lie at every position between the two ends as well as an asset
 * somebody has to model for no visual gain.
 *
 * <h2>No block entity ticker either</h2>
 * Every nozzle on a vessel is walked once a tick by
 * {@link ReactorControllerBlockEntity}, which is the only thing that wants a
 * flow figure out of one and the only thing that holds the dome pressure the
 * figure depends on. A ticker here would either duplicate that walk or compute
 * a number nobody reads.
 *
 * @see RpvSteamOutletBlockEntity for the physics and for what "actuator" means
 *      here
 */
public class RpvSteamOutletBlock extends BaseEntityBlock implements SteamLinePort {

    public static final MapCodec<RpvSteamOutletBlock> CODEC =
            simpleCodec(RpvSteamOutletBlock::new);

    public RpvSteamOutletBlock(Properties properties) {
        super(properties);
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
        return new RpvSteamOutletBlockEntity(pos, state);
    }

    /** Answer "is there a line on me" the moment the nozzle is built. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        // Answer "is there a line on me" straight away rather than leaving the
        // player looking at a nozzle that reports no steam line for up to a
        // second after they have plainly just built one.
        if (level.getBlockEntity(pos) instanceof RpvSteamOutletBlockEntity nozzle) {
            nozzle.refreshAttachment(level);
        }
    }

    /**
     * Analogue redstone commands the stop valve, linearly from shut at signal 0
     * to fully open at signal 15.
     *
     * <p>A valve position, not a flow demand: the same signal always means the
     * same opening, and what that opening passes depends on what the vessel is
     * at. Matching the two is the player's control loop. A nozzle under computer
     * control ignores redstone entirely, the same way the SRVs, the MSIVs and
     * the turbine steam outlet do.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof RpvSteamOutletBlockEntity nozzle) {
            // A neighbour change is exactly when a steam line may have appeared
            // or gone away, so this is the cheap and timely place to re-check.
            nozzle.refreshAttachment(level);
            if (!nozzle.isComputerControlled()) {
                nozzle.acceptRedstoneSignal(level.getBestNeighborSignal(pos));
            }
        }
    }

    /**
     * Bare right-click throws the stop valve fully open or fully shut; sneaking
     * reads the nozzle without touching it.
     *
     * <p>The split is the one {@code ReactorControllerBlock} already uses — act
     * on a plain click, report on a sneaking one — so a player who wants to know
     * what a hot nozzle is doing has a way to ask that does not move it. Opening
     * a nozzle by hand is an operator action on plant hardware, in the same
     * sense as switching a sparger segment between loops, and it is the only
     * steam control a player has before they have built a lever or written any
     * Lua.
     *
     * <p>Intermediate positions come from redstone or from Lua. A hand is a
     * hand: it gets the two ends of the stroke.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof RpvSteamOutletBlockEntity nozzle) {
            nozzle.refreshAttachment(level);
            if (!player.isShiftKeyDown()) {
                nozzle.setPosition(nozzle.getPosition() > 0.0 ? 0.0 : 1.0);
            }
            for (String line : nozzle.statusLines()) {
                player.displayClientMessage(Component.literal(line), false);
            }
        }
        return InteractionResult.CONSUME;
    }
}
