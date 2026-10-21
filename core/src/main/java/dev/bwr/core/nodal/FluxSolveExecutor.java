package dev.bwr.core.nodal;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pool every reactor's deferred nodal solve iterates on ({@link NodalFluxSolver.Deferred}):
 * daemon threads named {@code bwr-flux-N}, one fewer than the machine has cores and at
 * least one, shared by all reactors. Idle threads end after half a minute, so a JVM that
 * has stopped its server keeps none, and a daemon thread never holds a JVM open.
 *
 * <p>Only the iteration runs here. It reads nothing but its own copy of the problem, so a
 * task is never waiting on, or touching, anything of the world's.
 */
public final class FluxSolveExecutor {
    private FluxSolveExecutor() {
    }

    /** Worker threads: available cores minus one, at least one. */
    public static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final AtomicLong RUN = new AtomicLong();

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS,
            30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "bwr-flux-" + NAMES.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private static final Executor SHARED = task -> POOL.execute(() -> {
        task.run();
        RUN.incrementAndGet();
    });

    public static Executor shared() {
        return SHARED;
    }

    /** Tasks this pool has finished since the JVM started; for tests. */
    public static long tasksRun() {
        return RUN.get();
    }
}
