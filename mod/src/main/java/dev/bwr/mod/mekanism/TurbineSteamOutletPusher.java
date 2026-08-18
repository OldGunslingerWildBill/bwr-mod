package dev.bwr.mod.mekanism;

import dev.bwr.mod.steam.SteamExport;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands the turbine steam outlet's buffer to whatever Mekanism hardware is
 * bolted to it, once per server tick.
 *
 * <p>{@link TurbineSteamOutletChemicalHandler} is the other half of this
 * boundary and remains exactly what it was: a tank something else may extract
 * from. This class is the half that does not wait to be asked. Between them the
 * outlet behaves like any Mekanism machine with an output tank — it can be
 * drained, and it also empties itself into its neighbours — which is what makes
 * a turbine valve laid flat against it work, and what makes a freshly placed
 * pressurised tube work without the player first having to set that face to
 * <i>pull</i> with a Configurator.
 *
 * <h2>All six faces</h2>
 * The outlet has no {@code FACING} property and its model draws no nozzle, so
 * there is no face to privilege — the same argument {@code TurbineSteamOutletBlock}
 * and {@code SteamLinePort} already make about where a steam line may land. The
 * pull capability is registered on all six sides too, and pushing on fewer would
 * mean the identical neighbour worked or did not depending on which direction
 * the steam happened to move, which is exactly the kind of invisible rule that
 * produced the original bug report.
 *
 * <p>A face selector was considered and rejected. It would need a blockstate
 * property, a model for each pose and a wrench interaction, and it would change
 * the behaviour of every outlet already placed in a world the moment the default
 * pointed somewhere other than where the player's turbine is. The control the
 * player actually needs already exists on both sides of the boundary: they
 * choose what to put against the block, they choose the commanded flow, and
 * Mekanism's own side configuration decides what its tube or valve does with
 * what it is offered.
 *
 * <h2>Never loaded without Mekanism</h2>
 * This class names Mekanism types directly. {@link BwrMekanismSupport} installs
 * it, and that class is only referenced from the
 * {@code ModList.get().isLoaded("mekanism")} branch in {@code BwrMod}. The check
 * and the reference are in different classes, so a Mekanism-less JVM never has
 * to resolve any of this.
 */
final class TurbineSteamOutletPusher implements SteamExport.Pusher {

    /**
     * Cached because this runs twenty times a second per outlet and
     * {@code Direction.values()} hands out a fresh array on every call.
     */
    private static final Direction[] FACES = Direction.values();

    /**
     * The smallest offer worth making, millibuckets. Used to ask a neighbour
     * whether it would take steam at all without committing any: a simulated
     * insert that comes back refused means the acceptor is full, or is carrying
     * something that is not steam, and that distinction is the whole difference
     * between "nothing is connected" and "the turbine is backed up" in the
     * outlet's status text.
     */
    private static final long PROBE_MB = 1L;

    @Override
    public SteamExport.Push push(TurbineSteamOutletBlockEntity outlet) {
        Level level = outlet.getLevel();
        if (level == null || level.isClientSide()) {
            return SteamExport.Push.NONE;
        }
        Holder<Chemical> steam = MekanismSteam.steam();
        if (steam == null) {
            // This Mekanism has no chemical registered as mekanism:steam. Not a
            // crash and not a guess at a substitute: the outlet offers nothing
            // and says so, which is the one failure a player could not otherwise
            // tell apart from a pipe that is not connected.
            return SteamExport.Push.NONE;
        }

        BlockPos pos = outlet.getBlockPos();
        List<IChemicalHandler> takers = new ArrayList<>(FACES.length);
        int acceptors = 0;
        for (Direction face : FACES) {
            BlockPos neighbour = pos.relative(face);
            // Never ask about a block in an unloaded chunk: Level.getBlockState
            // will generate terrain to answer, and a capability lookup goes
            // straight through it. The same rule the neighbourhood scan in
            // TurbineSteamOutletBlockEntity documents at length.
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            // The context is the neighbour's face pointing back at us, which is
            // the side it decides its own behaviour by.
            IChemicalHandler handler = level.getCapability(
                    MekanismSteam.CHEMICAL_HANDLER, neighbour, face.getOpposite());
            if (handler == null) {
                continue;
            }
            acceptors++;
            if (wouldTakeSteam(handler, steam)) {
                takers.add(handler);
            }
        }
        if (takers.isEmpty()) {
            return new SteamExport.Push(true, acceptors, 0, 0L);
        }

        // Even split, with the share recomputed against the number of acceptors
        // still to be offered. Two turbines on two faces get half each rather
        // than the first one the direction order happens to reach taking
        // everything, and an acceptor that takes less than its share leaves the
        // rest to those after it instead of stranding it. This is the same
        // policy Mekanism uses to feed several pipes off one machine.
        long moved = 0L;
        long remaining = outlet.getBufferedMilliBuckets();
        int shares = takers.size();
        for (IChemicalHandler taker : takers) {
            if (remaining <= 0L) {
                break;
            }
            long took = offer(outlet, taker, steam, remaining / shares);
            shares--;
            remaining -= took;
            moved += took;
        }

        // Whatever the even split left over, offered again to everyone in turn.
        // Without this a single full acceptor would permanently halve the
        // throughput of the working one beside it: its share would go nowhere
        // every tick and the steam would sit in the buffer as back-pressure the
        // plant has not actually earned.
        if (remaining > 0L && takers.size() > 1) {
            for (IChemicalHandler taker : takers) {
                if (remaining <= 0L) {
                    break;
                }
                long took = offer(outlet, taker, steam, remaining);
                remaining -= took;
                moved += took;
            }
        }

        return new SteamExport.Push(true, acceptors, takers.size(), moved);
    }

    /**
     * Would this handler take steam right now? Asked with a simulated insert of
     * a single millibucket, which mutates nothing.
     *
     * <p>A second turbine steam outlet answers no, because
     * {@link TurbineSteamOutletChemicalHandler#insertChemical} hands back
     * everything it is offered. Two outlets face to face therefore do not push
     * steam into each other, which is worth knowing given that both of them are
     * sources and neither is a sink.
     */
    private static boolean wouldTakeSteam(IChemicalHandler handler, Holder<Chemical> steam) {
        ChemicalStack refused = handler.insertChemical(
                new ChemicalStack(steam, PROBE_MB), Action.SIMULATE);
        return refused == null || refused.isEmpty() || refused.getAmount() < PROBE_MB;
    }

    /**
     * Give one acceptor up to {@code amountMb} and return what it actually took.
     *
     * <h2>The order here is the whole correctness argument</h2>
     * The offer is clamped to what the buffer is holding <i>at this instant</i>,
     * then inserted, then — and only for the amount the acceptor kept — drained.
     * Draining through
     * {@link TurbineSteamOutletBlockEntity#drainMilliBuckets} rather than by any
     * private route is what makes a push and a pull the same withdrawal from the
     * same account: steam handed to a neighbour has already stopped being
     * available to anything that extracts later in the tick, so the mass leaving
     * the vessel and the mass arriving in Mekanism stay equal to the last
     * millibucket. Insert-then-drain is also the order Mekanism's own
     * {@code ChemicalUtil.emit} uses, and the clamp is what guarantees the drain
     * can honour what the insert gave away.
     *
     * <p>An acceptor that keeps nothing costs one refused stack and no drain, so
     * a full turbine is cheap to discover and the steam stays in the buffer,
     * where the shrinking headroom becomes the back-pressure the reactor feels.
     * That is the same fate steam already had when nobody pulled it, which is
     * the point: this changes who moves the steam, not what happens when nobody
     * will.
     */
    private static long offer(TurbineSteamOutletBlockEntity outlet, IChemicalHandler handler,
                              Holder<Chemical> steam, long amountMb) {
        long offered = Math.min(amountMb, outlet.getBufferedMilliBuckets());
        if (offered <= 0L) {
            return 0L;
        }
        ChemicalStack refused = handler.insertChemical(
                new ChemicalStack(steam, offered), Action.EXECUTE);
        long kept = offered - (refused == null || refused.isEmpty() ? 0L : refused.getAmount());
        if (kept <= 0L) {
            return 0L;
        }
        return outlet.drainMilliBuckets(kept, false);
    }
}
