package dev.bwr.mod.devtest;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.concurrent.locks.LockSupport;

/**
 * Waiting for the chunk system without racing it.
 *
 * <p>The GameTest server does not hold the 20 ticks a second of a real one: with nothing to do a tick takes about half
 * a millisecond. Loading and unloading a chunk and reading its entities are different: they run on the IO and worker
 * threads and finish in wall-clock time, tens to thousands of milliseconds on a busy machine. A test that waits "at
 * most 80 ticks" for a chunk therefore waits 40 ms, and fails whenever the disk is slower than that, which was
 * measured as 150 ticks (75 ms) for 20 fresh chunks of one fixture. A chunk's entities were not available while the
 * chunk was already forced and entity ticking, and the same wait of the same fixtures succeeded or failed with the
 * load of the machine.
 *
 * <p>{@link #pace} gives a polling wait the real tick rate: when it is called in a new server tick it sleeps until 50 ms
 * after the previous call, once per tick however many tests are waiting. A fixture that is ready does not call it, so
 * nothing else is slowed. A bound of N ticks is then a bound of N / 20 seconds, as in the game, and the test timeouts
 * (also in ticks) mean what they say. What was needed is printed at the end of the run as {@code FIXTURE WAIT}.
 */
@EventBusSubscriber(modid = "bwr")
public final class FixtureWait {
    private static final long TICK_NANOS = 50_000_000L;

    private static long pacedTick = Long.MIN_VALUE;
    private static long pacedAt;

    private static int finished;
    private static final java.util.Map<String, int[]> MOST_POLLS = new java.util.TreeMap<>();
    private static long slowestNanos;
    private static String slowestWhat = "";

    private FixtureWait() {
    }

    /** Sleeps the server thread up to one real tick, once per server tick; call it from a wait whose condition is not met yet. */
    static void pace(ServerLevel level) {
        long tick = level.getServer().getTickCount();
        if (tick == pacedTick) {
            return;
        }
        long due = pacedAt + TICK_NANOS;
        boolean interrupted = false;
        for (long left = due - System.nanoTime(); left > 0; left = due - System.nanoTime()) {
            LockSupport.parkNanos(left);
            // A park returns at once when the thread's interrupt flag is set, which would make the pause no pause.
            interrupted |= Thread.interrupted();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        pacedTick = tick;
        pacedAt = System.nanoTime();
    }

    /** One wait, for the summary: how many polls and how much wall-clock time it took. */
    static final class Timer {
        private final String what;
        private final long start = System.nanoTime();
        private int polls;

        private Timer(String what) {
            this.what = what;
        }

        /** The condition was not met on this poll: waits out the rest of the tick. */
        void notReady(ServerLevel level) {
            polls++;
            pace(level);
        }

        /** The condition was met. */
        void ready() {
            long nanos = System.nanoTime() - start;
            finished++;
            int[] most = MOST_POLLS.computeIfAbsent(what, k -> new int[1]);
            most[0] = Math.max(most[0], polls);
            if (nanos > slowestNanos) {
                slowestNanos = nanos;
                slowestWhat = what;
            }
        }

        int polls() {
            return polls;
        }
    }

    static Timer start(String what) {
        return new Timer(what);
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        System.out.println("FIXTURE WAIT: " + finished + " waits for the chunk system finished; the longest took "
                + slowestNanos / 1_000_000 + " ms (" + slowestWhat + ")");
        MOST_POLLS.forEach((what, polls) -> System.out.println("FIXTURE WAIT   most polls, " + what + ": " + polls[0]));
    }
}
