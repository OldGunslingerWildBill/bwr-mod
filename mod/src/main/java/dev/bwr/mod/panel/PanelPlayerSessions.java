package dev.bwr.mod.panel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The login session of each online player, as a number that is new every time the player logs in. The press and release
 * stamps of a held control ({@code PanelComponent.notePress} and friends) are counted by the client from 1 on every game
 * launch, so a stamp left by an earlier session of the same player (remembered by a panel whose chunk stayed loaded) must
 * never be compared with the new session's: the control would stay dead for that player until their new counter passed
 * the old one. Stamps are therefore stored together with the session they were sent in.
 *
 * <p>A player who is not logged in (a test's fake player that never fired the login event) has session 0, which is
 * simply one more session. Entries are dropped on logout.
 */
public final class PanelPlayerSessions {
    private static final Map<UUID, Integer> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private PanelPlayerSessions() {
    }

    /** The current session of {@code player}, 0 when there is none. */
    public static int of(UUID player) {
        return SESSIONS.getOrDefault(player, 0);
    }

    /** Starts a new session for {@code player}: every stamp remembered before it stops mattering. */
    public static void begin(UUID player) {
        SESSIONS.put(player, COUNTER.incrementAndGet());
    }

    /** Ends the session of {@code player} (logout). */
    public static void end(UUID player) {
        SESSIONS.remove(player);
    }
}
