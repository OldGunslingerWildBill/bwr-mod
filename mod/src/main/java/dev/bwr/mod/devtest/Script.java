package dev.bwr.mod.devtest;

import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A tick-driven script for tests that wait on the server (a chunk to unload, a network to re-form): steps run after a
 * delay, awaits poll a condition every tick, and a per-tick hook (a flow meter, a power top-up) runs before either.
 * The cleanup runs exactly once, whether the script finishes, fails or times out, so a failing test never leaves its
 * chunks forced or its blocks behind.
 */
final class Script {
    private interface Item {
    }

    private record Step(int delay, Runnable action) implements Item {
    }

    private record Await(Supplier<String> what, BooleanSupplier condition, int maxTicks) implements Item {
    }

    private final GameTestHelper helper;
    private final Runnable cleanup;
    private final Runnable perTick;
    private final Deque<Item> queue = new ArrayDeque<>();
    private Item current;
    private int waited;
    private boolean finished;
    private boolean cleaned;

    Script(GameTestHelper helper, int timeoutTicks, Runnable perTick, Runnable cleanup) {
        this.helper = helper;
        this.cleanup = cleanup;
        this.perTick = perTick;
        // The framework stops ticking a test that has failed or timed out, so the last-resort cleanup is armed here.
        helper.runAfterDelay(Math.max(1, timeoutTicks - 3), this::clean);
    }

    /** Runs {@code action} {@code delay} ticks after the previous item completed. */
    Script then(int delay, Runnable action) {
        queue.add(new Step(delay, action));
        return this;
    }

    /** Waits (polling every tick) until the condition holds; fails the test after {@code maxTicks}. */
    Script await(String what, int maxTicks, BooleanSupplier condition) {
        return await(() -> what, maxTicks, condition);
    }

    /** As {@link #await(String, int, BooleanSupplier)}; the description is built when the wait times out. */
    Script await(Supplier<String> what, int maxTicks, BooleanSupplier condition) {
        queue.add(new Await(what, condition, maxTicks));
        return this;
    }

    /** Starts the script on the next tick. */
    void start() {
        helper.onEachTick(this::tick);
    }

    private void clean() {
        if (!cleaned) {
            cleaned = true;
            cleanup.run();
        }
    }

    private void tick() {
        if (finished) {
            return;
        }
        try {
            perTick.run();
            if (current == null) {
                if (queue.isEmpty()) {
                    finished = true;
                    clean();
                    helper.succeed();
                    return;
                }
                current = queue.poll();
                waited = 0;
            }
            if (current instanceof Step step) {
                if (waited++ >= step.delay()) {
                    current = null;
                    step.action().run();
                }
            } else if (current instanceof Await await) {
                if (await.condition().getAsBoolean()) {
                    current = null;
                } else if (waited++ >= await.maxTicks()) {
                    throw new IllegalStateException("timed out waiting for: " + await.what().get());
                }
            }
        } catch (RuntimeException | Error e) {
            finished = true;
            clean();
            throw e;
        }
    }
}
