package dev.bwr.mod.devtest;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Forced chunks for GameTest fixtures, with an owner.
 *
 * <p>{@code ServerLevel.setChunkForced} is a flag, not a count. The tests of one batch run at the same time on one
 * level, and some fixtures share a chunk (a panel stage forces 80 blocks around its origin at 64-block steps, a
 * containment room 112 blocks around its centre). When one of them released its chunks, every chunk it shared lost
 * its ticket for the others too, and a test that was still waiting for its own chunks to answer waited for ever (a
 * chunk nobody forces unloads, and nothing in a wait forces it again). It happened only when two such tests were
 * started in the same batch, so it came and went whenever the batches were cut differently (more tests, the other
 * integrations present or absent).
 *
 * <p>Here a chunk stays forced until every owner that forced it has released it. The owner is the object passed to
 * {@link #force(ServerLevel, Object, int, int, boolean)}: the fixture's own origin or instance, which also makes forcing
 * twice from one fixture count once. Without an owner the calling test class is the owner, which keeps different test
 * classes apart. A release by an owner that never forced the chunk changes nothing while another owner still holds it,
 * and a chunk nobody recorded (left over in a saved world by an earlier run) is released as before.
 *
 * <p>Two owners holding one chunk is not an error, but it means two fixtures overlap, so the first time a pair does it
 * a {@code FIXTURE CHUNKS SHARED} line says who and where.
 */
final class FixtureChunks {
    private record Key(ResourceKey<Level> dimension, long chunk) {
    }

    private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<Key, Set<Object>> HOLDERS = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();

    private FixtureChunks() {
    }

    /** Forces or releases a chunk for the calling test class. */
    static void force(ServerLevel level, int cx, int cz, boolean on) {
        force(level, CALLER.getCallerClass().getNestHost(), cx, cz, on);
    }

    /** Forces a chunk for {@code owner}, or lets go of it; it is released for real when no owner is left. */
    static void force(ServerLevel level, Object owner, int cx, int cz, boolean on) {
        Key key = new Key(level.dimension(), net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        if (on) {
            Set<Object> holders = HOLDERS.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (!holders.isEmpty() && !holders.contains(owner)) {
                reportSharing(holders.iterator().next(), owner, cx, cz);
            }
            holders.add(owner);
            level.setChunkForced(cx, cz, true);
            return;
        }
        Set<Object> holders = HOLDERS.get(key);
        if (holders != null) {
            holders.remove(owner);
            if (!holders.isEmpty()) {
                return;
            }
            HOLDERS.remove(key);
        }
        level.setChunkForced(cx, cz, false);
    }

    private static void reportSharing(Object first, Object second, int cx, int cz) {
        String pair = describe(first) + " and " + describe(second);
        if (REPORTED.add(pair)) {
            System.out.println("FIXTURE CHUNKS SHARED: " + pair + " both force chunk " + cx + "," + cz
                    + "; it stays forced until both have released it");
        }
    }

    private static String describe(Object owner) {
        return owner instanceof Class<?> type ? type.getSimpleName() : String.valueOf(owner);
    }
}
