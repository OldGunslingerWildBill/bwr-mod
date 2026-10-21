package dev.bwr.mod;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Developer-only server tick profiler: how many microseconds of each server tick each of our systems used.
 *
 * <p>Off unless the JVM is started with {@code -Dbwr.tickProbe=true} (Gradle: {@code -PbwrTickProbe}, see
 * {@code mod/build.gradle}). When off, {@link #wrap} returns the ticker it was given and {@link #start} returns 0, so
 * a production server runs exactly the code it ran before: no wrapper object, no clock read, no listener.
 *
 * <p>When on, the time spent inside each block entity ticker is added to a per-class bucket and the time spent inside
 * our server-wide tick handlers to a per-handler bucket. At the end of each server tick the buckets are folded into
 * per-system statistics (mean and worst microseconds per server tick), next to the length of the whole server tick.
 * Read them with {@link #report}; clear them with {@link #reset}. Server thread only.
 */
public final class TickProbe {
    private TickProbe() {
    }

    public static final boolean ENABLED = Boolean.getBoolean("bwr.tickProbe");

    private static final class Stat {
        long currentBytes;
        long totalBytes;
        long current;
        long total;
        long worst;
        long second;
        long third;
        long ticks;
        long calls;
        long currentCalls;
    }

    /** One finished measurement window of one system. */
    public record Line(String system, double meanMicros, double worstMicros, double secondMicros, double thirdMicros,
                       double callsPerTick, double kilobytesPerTick) {
    }

    /** A system that used more than this in one server tick is printed as it happens. */
    private static final long SPIKE_NANOS = 8_000_000L;
    private static final ConcurrentHashMap<String, Stat> STATS = new ConcurrentHashMap<>();
    private static long serverTickStart;
    private static long serverTickStartBytes;
    private static long serverTickBytes;
    private static long serverTickTotal;
    private static long serverTickWorst;
    private static long serverTicks;
    private static boolean registered;

    /** A ticker that adds its own time to the bucket of the block entity class it ran on. */
    public static <T extends BlockEntity> BlockEntityTicker<T> wrap(BlockEntityTicker<T> ticker) {
        if (!ENABLED || ticker == null) {
            return ticker;
        }
        register();
        return (level, pos, state, be) -> {
            long t = start();
            try {
                ticker.tick(level, pos, state, be);
            } finally {
                stop(be.getClass().getSimpleName(), t);
            }
        };
    }

    /**
     * Start of a measured section; pair with {@link #stop}. Zero when the probe is off. Sections may nest; they are
     * balanced by try/finally at every call site, and only the server thread uses them.
     */
    public static long start() {
        if (!ENABLED) {
            return 0L;
        }
        if (depth < ALLOC_STACK.length) {
            ALLOC_STACK[depth] = allocatedBytes();
        }
        depth++;
        return System.nanoTime();
    }

    public static void stop(String system, long start) {
        if (!ENABLED) {
            return;
        }
        long nanos = System.nanoTime() - start;
        depth = Math.max(0, depth - 1);
        long bytes = depth < ALLOC_STACK.length ? allocatedBytes() - ALLOC_STACK[depth] : 0L;
        register();
        Stat s = STATS.computeIfAbsent(system, k -> new Stat());
        s.current += nanos;
        s.currentBytes += Math.max(0L, bytes);
        s.currentCalls++;
    }

    private static final long[] ALLOC_STACK = new long[64];
    private static int depth;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    private static long allocatedBytes() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    private static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ServerTickEvent.Pre e) -> {
            serverTickStartBytes = allocatedBytes();
            serverTickStart = System.nanoTime();
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerTickEvent.Post e) -> endTick());
    }

    private static void endTick() {
        long whole = System.nanoTime() - serverTickStart;
        serverTickBytes += Math.max(0L, allocatedBytes() - serverTickStartBytes);
        serverTicks++;
        serverTickTotal += whole;
        serverTickWorst = Math.max(serverTickWorst, whole);
        for (var entry : STATS.entrySet()) {
            Stat s = entry.getValue();
            if (s.current > SPIKE_NANOS) {
                System.out.printf(Locale.ROOT, "TICK PROBE SPIKE %s %.1f ms at probe tick %d (whole tick %.1f ms)%n",
                        entry.getKey(), s.current / 1e6, serverTicks, whole / 1e6);
            }
            s.ticks++;
            s.total += s.current;
            s.totalBytes += s.currentBytes;
            s.currentBytes = 0;
            s.calls += s.currentCalls;
            if (s.current > s.worst) {
                s.third = s.second;
                s.second = s.worst;
                s.worst = s.current;
            } else if (s.current > s.second) {
                s.third = s.second;
                s.second = s.current;
            } else if (s.current > s.third) {
                s.third = s.current;
            }
            s.current = 0;
            s.currentCalls = 0;
        }
    }

    /** Forgets everything measured so far. */
    public static void reset() {
        STATS.clear();
        serverTickTotal = 0;
        serverTickBytes = 0;
        serverTickWorst = 0;
        serverTicks = 0;
    }

    /** Number of server ticks measured since the last reset. */
    public static long ticks() {
        return serverTicks;
    }

    /** The finished window, whole server tick first, then systems from the most expensive down. */
    public static List<Line> lines() {
        List<Line> out = new ArrayList<>();
        double n = Math.max(1, serverTicks);
        out.add(new Line("SERVER TICK (all)", serverTickTotal / n / 1000.0, serverTickWorst / 1000.0, 0, 0, 1, serverTickBytes / n / 1024.0));
        List<Line> systems = new ArrayList<>();
        for (var e : STATS.entrySet()) {
            Stat s = e.getValue();
            systems.add(new Line(e.getKey(), s.total / n / 1000.0, s.worst / 1000.0, s.second / 1000.0, s.third / 1000.0, s.calls / n,
                    s.totalBytes / n / 1024.0));
        }
        systems.sort(Comparator.comparingDouble(Line::meanMicros).reversed());
        out.addAll(systems);
        return out;
    }

    /** Prints the window as one log block (stdout, so it lands in the Gradle log). */
    public static void report(String label) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "TICK PROBE [%s] %d server ticks%n", label, serverTicks));
        for (Line l : lines()) {
            b.append(String.format(Locale.ROOT, "TICK PROBE [%s] %-44s mean %9.1f us/tick  worst %9.1f us (then %.1f, %.1f)  calls/tick %7.2f  alloc %8.1f KB/tick%n",
                    label, l.system(), l.meanMicros(), l.worstMicros(), l.secondMicros(), l.thirdMicros(), l.callsPerTick(),
                    l.kilobytesPerTick()));
        }
        System.out.print(b);
    }
}
