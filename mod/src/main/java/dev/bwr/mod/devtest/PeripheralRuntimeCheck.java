package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dev-runtime harness that builds a real reactor in a real world and then
 * exercises the CC:Tweaked peripheral surface through the real capability
 * lookup. It exists because that surface is the entire control interface of
 * this mod and, until this was written, it had never been constructed once.
 *
 * <h2>Not shipped</h2>
 * The whole {@code dev.bwr.mod.devtest} package is excluded from the mod jar by
 * {@code mod/build.gradle}, and every entry point here is inert unless the JVM
 * is started with {@code -Dbwr.peripheralRuntimeCheck=true}. The
 * {@code :mod:runPeripheralCheck} run configuration is the only thing that sets
 * it.
 *
 * <h2>No CC types here</h2>
 * This class names no CC:Tweaked type, exactly like {@code BwrMod}: the
 * CC-facing half lives in {@link PeripheralRuntimeCheckCC} and is reached only
 * through a {@code BwrMod.isComputerCraftPresent()} branch, so a CC-less JVM
 * never resolves a {@code dan200.*} class. Running the check with
 * {@code -PbwrNoCC} therefore also proves the guard works.
 *
 * <h2>The exit status is the result</h2>
 * {@code :mod:runPeripheralCheck} is a {@code JavaExec}, so Gradle fails the
 * task if and only if this JVM exits non-zero. For a long time it could not:
 * the harness counted failures, logged "FAIL with N problem(s)" and then called
 * {@code server.halt(false)} exactly as it does on a pass, and
 * {@code DedicatedServer.onServerExit()} does not set a status, so the process
 * ended 0 whatever was found. A harness that always passes is worse than no
 * harness, because HANDOFF.md cites its green run as evidence.
 *
 * <p>The status is now failing from the moment this class loads and is dropped
 * only by a run that reaches {@link #finish} with nothing wrong. See
 * {@link #FAILURE_EXIT_HOOK} for why it is a shutdown hook and not
 * {@code System.exit}, and {@link #clearFailureExitStatus()} for why it works
 * in that direction.
 *
 * <h2>This is not a protection system</h2>
 * Nothing here runs in a player's game, nothing here decides anything about the
 * plant, and nothing here is wired to any block. It reads measurements and
 * pokes actuators to see that they do not explode. The design rule stands.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class PeripheralRuntimeCheck {

    public static final String PROPERTY = "bwr.peripheralRuntimeCheck";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Process exit status for a run that found at least one problem. */
    private static final int EXIT_FAILURE = 1;

    /**
     * How long the exit-status shutdown hook lets the other shutdown hooks run
     * before it takes the JVM down. See {@link #FAILURE_EXIT_HOOK}.
     */
    private static final long SHUTDOWN_FLUSH_GRACE_MILLIS = 750L;

    /**
     * Wall clock, not ticks: the off-thread pass is bounded by how long the
     * server keeps ticking, and a server that has stopped ticking is exactly the
     * failure this deadline is here to catch, so counting its ticks would be
     * counting the thing under test.
     */
    private static final long OFF_THREAD_DEADLINE_NANOS = 60L * 1_000_000_000L;

    /** Ticks to let the multiblocks validate and settle before probing. */
    private static final int SETTLE_TICKS = 60;

    // Layout. Everything sits inside chunks (0,0)..(2,2), which the harness
    // force-loads so the block entities actually tick.
    private static final BlockPos INTERIOR_MIN = new BlockPos(8, 150, 8);
    private static final BlockPos INTERIOR_MAX = new BlockPos(12, 156, 12);
    /**
     * On the EAST wall, and that is not arbitrary. {@code ReactorStructure
     * .findInteriorSeed} takes the first neighbour of the controller that is not
     * shell, walking {@code Direction.values()} = DOWN, UP, NORTH, SOUTH, WEST,
     * EAST. A controller on the west wall has open air to its west (checked
     * fifth) and the vessel interior to its east (checked sixth), so the seed
     * lands outside the vessel and validation fails with "interior is not a
     * closed rectangular volume". A controller on the east wall finds the
     * interior first. See the defect note in the task report.
     */
    private static final BlockPos CONTROLLER = new BlockPos(13, 153, 10);
    private static final BlockPos LONE_CONTROLLER = new BlockPos(40, 150, 40);
    private static final BlockPos POOL_CONTROLLER = new BlockPos(30, 150, 10);
    private static final BlockPos LONE_POOL_CONTROLLER = new BlockPos(40, 150, 44);
    private static final BlockPos TURBINE_OUTLET = new BlockPos(10, 150, 24);
    private static final BlockPos ECCS_PUMP = new BlockPos(40, 150, 48);
    private static final BlockPos ADS_CONTROLLER = new BlockPos(44, 150, 40);
    private static final BlockPos CONDENSATE_TANK = new BlockPos(44, 150, 44);
    /**
     * Above the suppression pool, so {@code revalidateDischarge} walks down and
     * finds standing water — the valve reports itself submerged and the happy
     * path of the peripheral is exercised rather than only its "vents to air"
     * path.
     */
    private static final BlockPos RELIEF_VALVE = new BlockPos(24, 155, 10);
    private static final BlockPos MSIV = new BlockPos(48, 150, 40);
    /** Within the pump's 12-block search of {@link #CONTROLLER}, and bound by hand. */
    private static final BlockPos RECIRCULATION_PUMP = new BlockPos(16, 150, 10);

    /**
     * Everything that gets probed, and what to call it in the log. One list, in
     * one place: these used to be written out three times — once for the
     * structure report and twice for the probe call — and a list that exists
     * three times is a list that will drift.
     */
    private static final BlockPos[] PROBE_POSITIONS = {
            CONTROLLER, LONE_CONTROLLER, POOL_CONTROLLER, LONE_POOL_CONTROLLER,
            TURBINE_OUTLET, ECCS_PUMP, ADS_CONTROLLER, CONDENSATE_TANK,
            RELIEF_VALVE, MSIV, RECIRCULATION_PUMP,
    };

    private static final String[] PROBE_LABELS = {
            "reactor (formed)", "reactor (NEVER FORMED)",
            "suppression pool (formed)", "suppression pool (NEVER FORMED)",
            "turbine steam outlet (unattached)", "RCIC pump (unattached)",
            "ADS controller (unattached)", "condensate storage tank (unattached)",
            "safety relief valve (over the pool)", "MSIV (unattached)",
            "recirculation pump (bound to the formed reactor)",
    };

    static {
        // Positions and labels are paired by index. A mismatch mislabels every
        // probe past the short one, or throws deep inside the probe loop where
        // it reads like a peripheral defect. Fail here instead, where the cause
        // is obvious.
        if (PROBE_POSITIONS.length != PROBE_LABELS.length) {
            throw new IllegalStateException("PROBE_POSITIONS and PROBE_LABELS are not paired: "
                    + PROBE_POSITIONS.length + " vs " + PROBE_LABELS.length);
        }
    }

    private static int tick = -1;

    /** Problems found by the pass that runs on the server tick thread. */
    private static int onThreadFailures;

    /** The second pass, off the tick thread. Null when CC:Tweaked is absent. */
    private static OffThreadProbe offThread;

    private static long offThreadDeadlineNanos;

    /**
     * Set once {@link #finish} has run. {@code halt(false)} does not stop the
     * tick that is already in flight, so without this the harness could report
     * twice.
     *
     * <p>Volatile because {@link #FAILURE_EXIT_HOOK} reads it from the shutdown
     * thread to decide whether to say the run never reported, and the tick
     * thread is what writes it.
     */
    private static volatile boolean finished;

    private PeripheralRuntimeCheck() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean(PROPERTY) || finished) {
            return;
        }
        MinecraftServer server = event.getServer();
        tick++;

        // Nothing outside startProbes had a catch before, so a throw while
        // building the plant escaped into MinecraftServer, produced a crash
        // report, stopped the server and reported nothing at all. The exit hook
        // would fail that run anyway now, but a run deserves a banner saying
        // what happened rather than a silent crash report.
        try {
            if (tick == 0) {
                LOGGER.info("=== BWR PERIPHERAL RUNTIME CHECK: building plant ===");
                build(server.overworld());
                return;
            }
            if (tick < SETTLE_TICKS) {
                return;
            }
            if (tick == SETTLE_TICKS) {
                startProbes(server);
            } else {
                pollOffThreadProbe(server);
            }
        } catch (Throwable t) {
            LOGGER.error("=== BWR PERIPHERAL RUNTIME CHECK: HARNESS ITSELF FAILED at tick {} ===",
                    tick, t);
            finish(server, onThreadFailures + 1);
        }
    }

    /**
     * The pass that runs here, on the tick thread, plus the launch of the second
     * pass that does not. Nothing in this method waits on the off-thread pass —
     * see {@link #pollOffThreadProbe} for why that matters.
     */
    private static void startProbes(MinecraftServer server) {
        reportStructures(server.overworld());

        try {
            if (BwrMod.isComputerCraftPresent()) {
                onThreadFailures = PeripheralRuntimeCheckCC.probeAll(
                        server.overworld(), PROBE_POSITIONS, PROBE_LABELS);
                offThread = PeripheralRuntimeCheckCC.startOffThreadProbe(
                        server, server.overworld(), PROBE_POSITIONS, PROBE_LABELS);
                offThreadDeadlineNanos = System.nanoTime() + OFF_THREAD_DEADLINE_NANOS;
            } else {
                LOGGER.info("=== BWR PERIPHERAL RUNTIME CHECK: CC:Tweaked is ABSENT "
                        + "(ModList says computercraft is not loaded), so no peripheral was "
                        + "registered and none can be looked up. This is the guarded path. ===");
                onThreadFailures = 0;
            }
        } catch (Throwable t) {
            LOGGER.error("=== BWR PERIPHERAL RUNTIME CHECK: HARNESS ITSELF FAILED ===", t);
            // Increment rather than assign: probeAll may already have counted
            // real findings before whatever threw, and losing those would turn
            // "9 problems" into "1 problem" in the banner.
            onThreadFailures++;
        }

        if (offThread == null) {
            finish(server, onThreadFailures);
        }
    }

    /**
     * Waits for the off-tick-thread pass by <em>returning</em>, tick after tick,
     * rather than by blocking.
     *
     * <p>This is the whole safety argument for the second pass and it must not
     * be undone. That pass calls peripheral methods the way CC:Tweaked really
     * does — from a thread that is not the server thread — and those calls reach
     * things like {@code ServerChunkCache.getChunk}, which, off the server
     * thread, enqueues onto the server's main-thread task queue and blocks the
     * caller until the server drains it. The methods declared
     * {@code mainThread = true} are deliberately handed to that same queue. So
     * the off-thread pass only completes while the server keeps ticking. If the
     * tick thread ever joined it, waited on it, or slept on it, the server would
     * stop draining the queue the probe is waiting on and both threads would sit
     * there until the run was killed. It polls instead, and it always has a
     * deadline, so the harness terminates even when the thing it is testing
     * does not.
     */
    private static void pollOffThreadProbe(MinecraftServer server) {
        if (offThread == null) {
            return;
        }
        if (offThread.isDone()) {
            finish(server, onThreadFailures + offThread.failures());
            return;
        }
        if (System.nanoTime() - offThreadDeadlineNanos >= 0L) {
            LOGGER.error("FAIL: the off-tick-thread pass did not finish within {} seconds. "
                            + "That is a finding and not a harness bug: a peripheral method "
                            + "that never returns when called off the server thread is a method "
                            + "a player's Lua will hang on.",
                    OFF_THREAD_DEADLINE_NANOS / 1_000_000_000L);
            finish(server, onThreadFailures + offThread.failures() + 1);
        }
    }

    /**
     * Report, set the process exit status, and stop the server. Called exactly
     * once.
     */
    private static void finish(MinecraftServer server, int failures) {
        if (finished) {
            return;
        }
        finished = true;
        if (offThread != null && !offThread.isDone()) {
            // Stop the straggler logging FAIL lines after the banner has already
            // been printed; the run is being failed for the timeout regardless.
            offThread.abandon();
        }

        boolean pass = failures == 0;
        if (pass) {
            // Before halting, not after: once halt() returns the server thread
            // walks straight out of the tick loop and there is no later point on
            // this thread that is guaranteed to run.
            clearFailureExitStatus();
        }
        banner(pass, failures);
        server.halt(false);
    }

    /**
     * The PASS/FAIL line, framed, and repeated on stdout.
     *
     * <p>This is the one line anybody reading a 4000-line dev-server log is
     * looking for, and it used to be a single {@code LOGGER.info} that looked
     * like the two hundred info lines above it. A failure goes to
     * {@code LOGGER.error} so it also lands on stderr, where Gradle shows it
     * without {@code --info}.
     */
    private static void banner(boolean pass, int failures) {
        String headline = pass
                ? "BWR PERIPHERAL RUNTIME CHECK: PASS -- 0 problems -- exit status 0"
                : "BWR PERIPHERAL RUNTIME CHECK: FAIL -- " + failures
                        + " problem(s) -- exit status " + EXIT_FAILURE;
        String framed = "##  " + headline + "  ##";
        String rule = "#".repeat(framed.length());

        for (String line : new String[]{"", rule, framed, rule, ""}) {
            if (pass) {
                LOGGER.info(line);
            } else {
                LOGGER.error(line);
            }
            // Straight at the process's own stdout as well. Whether that is the
            // real console or a stream log4j has taken over depends on how the
            // run was launched, so this is a second chance and not a guarantee —
            // but it costs nothing, and on a failing run the JVM is about to be
            // halted out from under the logging appenders.
            System.out.println(line);
        }
        System.out.flush();
    }

    /**
     * Drop the failing exit status, on a clean pass only.
     *
     * <p>The status is set the other way round from how it reads: the run starts
     * out failing and only a PASS takes that away. That is not stylistic. If the
     * status were installed at the end, on the failure branch, then every way of
     * never reaching the end would report success — the harness throwing while
     * it builds the plant, the server crashing during world generation, a
     * multiblock never forming so the probe never runs. Those are all
     * <em>worse</em> outcomes than a counted failure and every one of them would
     * have printed BUILD SUCCESSFUL. A run that produced no result did not pass.
     *
     * @see #FAILURE_EXIT_HOOK
     */
    private static void clearFailureExitStatus() {
        try {
            Runtime.getRuntime().removeShutdownHook(FAILURE_EXIT_HOOK);
        } catch (IllegalStateException e) {
            // Shutdown is already under way, so the hook is already running or
            // about to. Nothing to remove and nothing to do: at that point the
            // JVM is going down on somebody else's terms anyway.
            LOGGER.warn("could not drop the failing exit status; the JVM was already "
                    + "shutting down", e);
        }
    }

    /**
     * Sets the process exit status, and is the reason a failing run now fails
     * the Gradle task.
     *
     * <p><b>Why a shutdown hook.</b> The two obvious alternatives both hang the
     * run:
     *
     * <ul>
     *   <li>{@code System.exit()} from the server thread. It starts the shutdown
     *       sequence and blocks until every shutdown hook has finished, and one
     *       of those hooks is Minecraft's own "Server Shutdown Thread", which
     *       calls {@code halt(true)} and joins the server thread — the very
     *       thread blocked inside {@code System.exit}. Deadlock; the run never
     *       ends and Gradle waits forever.</li>
     *   <li>{@code System.exit()} from inside a shutdown hook. Documented to
     *       block indefinitely once shutdown has already begun. Same outcome.</li>
     * </ul>
     *
     * <p>So the server is stopped the ordinary way with {@code halt(false)},
     * which returns immediately; the server thread finishes its tick, leaves the
     * loop, saves the world and stops; the last non-daemon thread ends; the JVM
     * begins shutdown; and this hook runs. {@code Runtime.halt} is the only call
     * that can still set a status at that point. {@code DedicatedServer
     * .onServerExit()} sets none, which is exactly why a failing run used to
     * exit 0 and report PASS to Gradle.
     *
     * <p><b>Why the sleep.</b> Application shutdown hooks all run at once, so
     * this one races the logging framework's hook, and {@code Runtime.halt}
     * stops the JVM dead — including any appender still draining. A bounded
     * sleep gives the FAIL banner time to reach {@code logs/latest.log}. It
     * cannot deadlock: it waits on a clock, not on another hook or another
     * thread. Only a run that is already failing pays for it.
     */
    private static final Thread FAILURE_EXIT_HOOK = new Thread(() -> {
        if (!finished) {
            String line = "BWR PERIPHERAL RUNTIME CHECK: the harness never reported a result. "
                    + "Treating that as a failure -- exit status " + EXIT_FAILURE;
            LOGGER.error(line);
            System.err.println(line);
            System.err.flush();
        }
        try {
            Thread.sleep(SHUTDOWN_FLUSH_GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().halt(EXIT_FAILURE);
    }, "bwr-peripheral-check-exit-status");

    static {
        // Only on a harness run. Every other run configuration leaves this class
        // completely inert, exactly as the class comment promises.
        if (Boolean.getBoolean(PROPERTY)) {
            Runtime.getRuntime().addShutdownHook(FAILURE_EXIT_HOOK);
        }
    }

    /**
     * Handle on the off-tick-thread pass, held by the tick thread and written by
     * the probe thread.
     *
     * <p>It names no CC:Tweaked type on purpose. {@link PeripheralRuntimeCheck}
     * has to be loadable in a JVM with no CC:Tweaked on the classpath, so the
     * type of the field it keeps this in cannot be a CC type, exactly as the
     * class comment says of everything else here.
     */
    static final class OffThreadProbe {
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicInteger failures = new AtomicInteger();

        boolean isDone() {
            return done.get();
        }

        int failures() {
            return failures.get();
        }

        void addFailures(int n) {
            failures.addAndGet(n);
        }

        void markDone() {
            done.set(true);
        }

        void abandon() {
            abandoned.set(true);
        }

        boolean isAbandoned() {
            return abandoned.get();
        }
    }

    /**
     * Multiblock state before any peripheral is touched, so that a peripheral
     * reporting "not formed" can be told apart from a harness that built the
     * plant wrong.
     */
    private static void reportStructures(ServerLevel level) {
        for (BlockPos p : PROBE_POSITIONS) {
            var be = level.getBlockEntity(p);
            LOGGER.info("structure at {}: chunkTicking={} be={} ", p,
                    level.getChunkSource().isPositionTicking(
                            net.minecraft.world.level.ChunkPos.asLong(p)),
                    be == null ? "MISSING" : be.getClass().getSimpleName());
            if (be instanceof dev.bwr.mod.reactor.ReactorControllerBlockEntity r) {
                LOGGER.info("   formed={} status={}", r.isFormed(), r.statusLines());
            } else if (be instanceof dev.bwr.mod.suppression.SuppressionPoolBlockEntity s) {
                LOGGER.info("   formed={} status={}", s.isFormed(), s.statusLines());
            }
        }
    }

    // -----------------------------------------------------------------
    // Plant construction
    // -----------------------------------------------------------------

    private static void build(ServerLevel level) {
        for (int cx = 0; cx <= 2; cx++) {
            for (int cz = 0; cz <= 2; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        BlockState vessel = BwrBlocks.REACTOR_VESSEL.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // Clear the interior, then wrap it in a closed shell.
        fill(level, INTERIOR_MIN, INTERIOR_MAX, air);
        fillShell(level, INTERIOR_MIN.offset(-1, -1, -1), INTERIOR_MAX.offset(1, 1, 1), vessel);

        // One shell block becomes the controller.
        level.setBlock(CONTROLLER, BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 3);

        // One control rod drive under every rod. The lattice rule puts a rod at
        // every odd offset from the interior minimum, and the drive two below
        // the interior floor, i.e. one below the vessel bottom.
        int driveY = INTERIOR_MIN.getY() - 2;
        BlockState drive = BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState();
        for (int x = INTERIOR_MIN.getX(); x <= INTERIOR_MAX.getX(); x++) {
            for (int z = INTERIOR_MIN.getZ(); z <= INTERIOR_MAX.getZ(); z++) {
                boolean rodHere = ((x - INTERIOR_MIN.getX()) % 2 == 1)
                        && ((z - INTERIOR_MIN.getZ()) % 2 == 1);
                if (rodHere) {
                    level.setBlock(new BlockPos(x, driveY, z), drive, 3);
                }
            }
        }

        // A deliberately unformed reactor: a controller in mid-air with nothing
        // around it. Every readback has to behave on this one.
        level.setBlock(LONE_CONTROLLER,
                BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 3);

        // Suppression pool: a walled box of water plus its controller outside.
        BlockState wall = BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState();
        BlockPos waterMin = new BlockPos(22, 149, 8);
        BlockPos waterMax = new BlockPos(26, 151, 12);
        fillShell(level, waterMin.offset(-1, -1, -1), waterMax.offset(1, 1, 1), wall);
        fill(level, waterMin, waterMax, Blocks.WATER.defaultBlockState());
        level.setBlock(POOL_CONTROLLER,
                BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(), 3);

        // ...and an unformed one, far from any water.
        level.setBlock(LONE_POOL_CONTROLLER,
                BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(), 3);

        // Turbine steam outlet, bound to nothing.
        level.setBlock(TURBINE_OUTLET,
                BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(), 3);

        // Emergency systems, all unattached. Their peripherals still have to
        // answer without a reactor behind them.
        level.setBlock(ECCS_PUMP, BwrBlocks.RCIC_TURBINE_PUMP.get().defaultBlockState(), 3);
        level.setBlock(ADS_CONTROLLER, BwrBlocks.ADS_CONTROLLER.get().defaultBlockState(), 3);
        level.setBlock(CONDENSATE_TANK,
                BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);

        // The two player-actuated valves. Both had a documented Lua path and no
        // peripheral registered for it until now, so neither had ever been
        // constructed once.
        level.setBlock(RELIEF_VALVE, BwrBlocks.SAFETY_RELIEF_VALVE.get().defaultBlockState(), 3);
        level.setBlock(MSIV, BwrBlocks.MSIV.get().defaultBlockState(), 3);

        // The recirculation pump binds itself in setPlacedBy, which setBlock
        // does not call, so the binding is made by hand here. A bound pump
        // exercises the controller's satellite path as well as the peripheral.
        level.setBlock(RECIRCULATION_PUMP,
                BwrBlocks.RECIRCULATION_PUMP.get().defaultBlockState(), 3);
        if (level.getBlockEntity(RECIRCULATION_PUMP)
                instanceof dev.bwr.mod.flow.RecirculationPumpBlockEntity pump
                && level.getBlockEntity(CONTROLLER)
                instanceof dev.bwr.mod.reactor.ReactorControllerBlockEntity controller) {
            pump.bindController(controller);
        }

        LOGGER.info("built: reactor controller at {}, unformed controller at {}, "
                        + "pool controller at {}, unformed pool controller at {}, outlet at {}, "
                        + "relief valve at {}, MSIV at {}, recirculation pump at {}",
                CONTROLLER, LONE_CONTROLLER, POOL_CONTROLLER, LONE_POOL_CONTROLLER,
                TURBINE_OUTLET, RELIEF_VALVE, MSIV, RECIRCULATION_PUMP);
    }

    private static void fill(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            level.setBlock(p, state, 2);
        }
    }

    /** Fill only the six outer faces of a box, leaving its interior untouched. */
    private static void fillShell(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            boolean face = p.getX() == min.getX() || p.getX() == max.getX()
                    || p.getY() == min.getY() || p.getY() == max.getY()
                    || p.getZ() == min.getZ() || p.getZ() == max.getZ();
            if (face) {
                level.setBlock(p, state, 2);
            }
        }
    }
}
