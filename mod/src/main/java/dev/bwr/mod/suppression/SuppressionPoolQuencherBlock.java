package dev.bwr.mod.suppression;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.SteamLineNetwork;
import dev.bwr.mod.steam.SteamLinePort;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A T-quencher — the submerged end of a safety/relief valve discharge line.
 *
 * <h2>What this block is for</h2>
 * Until it existed there was no inlet to the suppression pool at all. A relief
 * valve was judged to be discharging into the pool by
 * {@link SafetyReliefValveBlockEntity#revalidateDischarge} looking <i>straight
 * down</i> for any water block, stepping past solids on the way, and the pool
 * then condensed whatever such valves passed. So steam crossed from the vessel
 * to the pool through no pipework whatsoever: one end of the relief path was a
 * welded penetration ({@code bwr:rpv_steam_outlet}) and the other was a fluid
 * raycast. This block is the other end of that path made real.
 *
 * <p>On a BWR/6 each SRV has its own discharge line running down inside the
 * containment and terminating below the pool surface in a T-quencher: a pair of
 * horizontal arms drilled with hundreds of small holes. The holes are the whole
 * point. A steam jet leaving one open pipe bore is a single coherent plume that
 * punches into the water and condenses unstably; split across hundreds of small
 * holes the same mass flow presents an order of magnitude more steam-water
 * interface and condenses quietly. Early Mark I plants discharged through plain
 * straight pipes, the containment loads programme found the resulting
 * condensation oscillation unacceptable, and quenchers were retrofitted. That
 * history is exactly the compatibility story this mod is in: the bare discharge
 * works, it is worse, and the fix is a piece of hardware you bolt on the end.
 *
 * <h2>Passive hardware, and it has no block entity</h2>
 * A quencher decides nothing. It does not open, does not close, holds no
 * setpoint and carries no state of its own — whether it is submerged is a fact
 * about the block above it, and which valves discharge through it is a fact
 * about the pipework, both of which are read from the level at the moment
 * somebody asks. There is therefore nothing to persist, nothing to tick and no
 * cached answer that can go stale, which is why this is a plain {@link Block}
 * and not a {@code BaseEntityBlock}. A block entity here would exist only to
 * hold copies of things the world already knows.
 *
 * <h2>Submerged means water directly above, and nothing cleverer</h2>
 * {@link #isSubmerged} asks one question: is there a water source block on top
 * of this one. That is the honest reading of "the discharge is under water", it
 * is one fluid lookup, and — importantly — it is answered fresh every time.
 *
 * <p>Waterlogging was the obvious alternative and is a trap. A waterlogged
 * block takes its {@code WATERLOGGED} value at the instant it is placed, so a
 * player who lays the quenchers on the basin floor and <i>then</i> floods the
 * basin — which is how anybody builds a pool — would end up with quenchers
 * permanently marked dry, with no symptom except a pool that will not condense
 * and no way to fix it but to break and replace every one of them. A test that
 * reads the world each time cannot fail that way, and it also makes draining
 * the pool show up immediately and correctly as a lost discharge path.
 *
 * <h2>All six faces take a steam line</h2>
 * {@link SteamLinePort} defaults to accepting every face and this block leaves
 * it that way, for the reason the interface itself gives: an override should
 * express a nozzle the model actually shows, and restricting connections on the
 * strength of geometry that is not modelled would only mean a player who runs
 * the tailpipe along the basin floor instead of down from above gets a stub
 * they cannot explain.
 */
public class SuppressionPoolQuencherBlock extends Block implements SteamLinePort {

    public static final MapCodec<SuppressionPoolQuencherBlock> CODEC =
            simpleCodec(SuppressionPoolQuencherBlock::new);

    public SuppressionPoolQuencherBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Whether the quencher at {@code pos} has water over it.
     *
     * <p>Source blocks only, matching {@code SuppressionPoolBlockEntity}'s own
     * test for pool water: flowing water is a partial volume and a transient
     * one, and a quencher standing under a waterfall is not standing in a pool.
     *
     * <p>Static, and takes the position rather than reading a field, because
     * there is no instance state to read — the two callers are the relief valve
     * deciding whether it has a discharge path and the pool controller deciding
     * whether a quencher in its basin is usable, and both hold a level and a
     * position already.
     */
    public static boolean isSubmerged(BlockGetter level, BlockPos pos) {
        return level.getFluidState(pos.above()).getType() == Fluids.WATER;
    }

    /**
     * Right-click reports what this quencher is: submerged or not, and what is
     * on the line behind it.
     *
     * <p>Report only — there is nothing here to operate. The survey is run on
     * the click rather than cached, which costs one walk of one line on one
     * deliberate press and is the same trade {@code RpvSteamOutletBlock} makes
     * for the same reason: a stale answer sends somebody looking for a fault in
     * pipework they have just finished building.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (isSubmerged(level, pos)) {
            player.displayClientMessage(Component.literal(
                    "Quencher submerged; steam admitted to the pool through it condenses in full."),
                    false);
        } else {
            player.displayClientMessage(Component.literal(
                    "Quencher is not submerged: no water directly above it. A quencher above the"
                            + " pool surface admits steam to the containment airspace, not to the"
                            + " water."), false);
        }
        SteamLineNetwork.Survey survey = SteamLineNetwork.survey(level, pos);
        int valves = survey.reliefValves().size();
        if (valves == 0) {
            player.displayClientMessage(Component.literal(
                    "No relief valve is on this quencher's steam line; run pressurised tube from"
                            + " the valve down to it."), false);
        } else {
            player.displayClientMessage(Component.literal(String.format(
                    "%d relief valve(s) discharge through this quencher, over %d blocks of line.",
                    valves, survey.lineBlocks())), false);
        }
        if (survey.truncated()) {
            player.displayClientMessage(Component.literal(
                    "The line is longer than " + SteamLineNetwork.MAX_LINE_BLOCKS
                            + " blocks; only the first " + SteamLineNetwork.MAX_LINE_BLOCKS
                            + " were walked."), false);
        }
        return InteractionResult.CONSUME;
    }
}
