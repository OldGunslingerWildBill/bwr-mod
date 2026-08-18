package dev.bwr.mod.steam;

/**
 * Indirection that lets the turbine steam outlet <i>push</i> steam into whatever
 * is bolted to it, without this package ever naming a Mekanism class.
 *
 * <h2>Why a push had to exist</h2>
 * The outlet's chemical handler is output-only, and for a long time extraction
 * was the only live path: something adjacent had to come and take steam, or none
 * moved. That is not how most of Mekanism actually works, and the consequence was
 * a boundary that failed silently in the two most obvious builds a player tries.
 *
 * <p>A turbine valve laid flat against the outlet does nothing at all. Mekanism
 * machines are fed; they do not forage through their neighbours looking for
 * chemicals to extract. And a Mekanism pressurised tube only pulls from a face
 * the player has explicitly set to <i>pull</i> with a Configurator —
 * {@code PressurizedTube.pullFromAcceptors} returns immediately unless the side
 * is {@code ConnectionType.PULL}, which is not the connection a tube makes when
 * you place it. So the default, correct-looking build moved nothing, and the
 * player's report was the entirely reasonable "the steam line isn't connecting".
 *
 * <p>Pushing is the missing half, and it is what every Mekanism machine with an
 * output tank does: offer the contents to the neighbours once a tick. Pulling
 * still works exactly as before — a tube set to pull, or any machine that
 * extracts, drains the same buffer through the same method — so nothing that
 * worked before this existed behaves differently now.
 *
 * <h2>This is hardware, not judgement</h2>
 * The steam in the buffer is steam the player commanded out of the vessel
 * through valves they opened. Moving it along the pipe they built is what the
 * pipe is for. Nothing here decides whether steam <i>should</i> flow: that was
 * settled upstream by the commanded flow and by the isolation valves, and this
 * class can change neither. If the player wants the steam to stop, they shut a
 * valve or command zero, and this code has nothing left to push.
 *
 * <h2>Soft dependency, the same shape as FuelFeeds</h2>
 * {@code dev.bwr.mod.mekanism} is the only package allowed to name a Mekanism
 * type, so the outlet cannot call into it directly. It calls this class instead,
 * which knows nothing but a {@link Pusher} interface built from primitives.
 * {@code BwrMod} tests {@code ModList.get().isLoaded("mekanism")} and only then
 * touches {@code BwrMekanismSupport}, which installs the implementation. Because
 * the test and the reference live in different classes, a JVM without Mekanism
 * never resolves a single Mekanism type — the field stays null, {@link #push}
 * answers {@link Push#NONE}, and the outlet is inert exactly as it has always
 * been on an install without Mekanism.
 */
public final class SteamExport {

    private SteamExport() {
    }

    /**
     * What one tick's worth of pushing found and moved.
     *
     * <p>Every field is here because the block's status text needs it to tell
     * one failure apart from another. A player reporting "it isn't connecting"
     * has given us no information at all; these four numbers turn that into a
     * question with an answer.
     *
     * @param steamKnown        whether Mekanism's steam chemical could be
     *                          resolved at all. False means either that nothing
     *                          is installed to push with, or that this Mekanism
     *                          has no chemical registered as {@code mekanism:steam}
     * @param acceptors         adjacent blocks offering Mekanism's chemical
     *                          handler on the face pointing back at the outlet
     * @param taking            how many of those would accept steam right now.
     *                          The difference between this and {@code acceptors}
     *                          is a full turbine, or a pipe carrying something
     *                          else
     * @param movedMilliBuckets millibuckets actually handed over
     */
    public record Push(boolean steamKnown, int acceptors, int taking, long movedMilliBuckets) {

        /** Nothing installed, nothing found, nothing moved. */
        public static final Push NONE = new Push(false, 0, 0, 0L);
    }

    /** The Mekanism-facing half. One implementation, installed when Mekanism is present. */
    public interface Pusher {

        /**
         * Offer the outlet's buffer to its neighbours and report what happened.
         *
         * <p>An implementation must take steam out of the outlet through
         * {@link TurbineSteamOutletBlockEntity#drainMilliBuckets} and by no other
         * route, so that steam handed to a neighbour is the same steam that stops
         * being available to a puller. That single choke point is what keeps the
         * mass leaving the vessel equal to the mass arriving in Mekanism.
         */
        Push push(TurbineSteamOutletBlockEntity outlet);
    }

    /**
     * Volatile because it is written once during mod construction and read from
     * the server thread thereafter. The two are ordered in practice — mod
     * loading completes long before a level exists — but this is a static
     * handed between threads and saying so costs nothing.
     */
    private static volatile Pusher pusher;

    /** Installs the Mekanism push. Called only when Mekanism is present. */
    public static void install(Pusher installed) {
        pusher = installed;
    }

    /** Push what the outlet is holding into its neighbours. {@link Push#NONE} if nothing can. */
    public static Push push(TurbineSteamOutletBlockEntity outlet) {
        Pusher p = pusher;
        return p == null ? Push.NONE : p.push(outlet);
    }
}
