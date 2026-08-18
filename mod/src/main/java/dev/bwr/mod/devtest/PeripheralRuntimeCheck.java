package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
 * <h2>No Mekanism types here either, and that is the point</h2>
 * {@code dev.bwr.mod.mekanism} is the only package in this project permitted to
 * name a {@code mekanism.*} type, and this package is not it. So
 * {@link #reportMekanismBoundary} asks its questions entirely by <i>name</i> —
 * a {@link ResourceLocation}, a {@link BlockCapability} held as a wildcard, and
 * a handler that never leaves the {@code Object} it came back as. Nothing here
 * so much as mentions a Mekanism class, let alone loads one.
 *
 * <p>That constraint is not an obstacle, it is what makes the check worth
 * running. {@code :mod:runPeripheralCheck} is executed twice — once with
 * Mekanism on the runtime classpath and once with {@code -PbwrNoMekanism} —
 * and the boundary report is the one place where those two runs are supposed to
 * print visibly different things. See its verdict line.
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

    /**
     * The name of Mekanism's own block chemical-handler capability, and the only
     * thing this class knows about Mekanism.
     *
     * <p>Verified against {@code Mekanism-1.21.1-10.7.19.85.jar}: the static
     * initialiser of {@code mekanism.common.capabilities.Capabilities} builds
     * {@code CHEMICAL} as {@code new MultiTypeCapability<>(Mekanism.rl(
     * "chemical_handler"), IChemicalHandler.class)}, and that constructor calls
     * {@code BlockCapability.createSided(name, type)} with the name unchanged.
     * So the block capability really is registered under exactly this
     * {@link ResourceLocation}, which is what
     * {@code dev.bwr.mod.mekanism.MekanismSteam.CHEMICAL_HANDLER} recreates in
     * order to get the very instance Mekanism registered rather than a second
     * one nothing looks at.
     *
     * <p>A string, not a class reference, so a JVM with no Mekanism on it can
     * still ask the question and get the honest answer "there is no such
     * capability here".
     */
    private static final ResourceLocation MEKANISM_CHEMICAL_HANDLER =
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler");

    // Layout. Everything sits inside chunks (0,0)..(2,2), which the harness
    // force-loads so the block entities actually tick.
    private static final BlockPos INTERIOR_MIN = new BlockPos(8, 150, 8);
    /**
     * 5x8x5 of interior, and the 8 is the part that is not free to change.
     *
     * <p>It was 7 ({@code y=156}), which is what {@code ReactorStructure} accepted
     * when this harness was written. It does not any more:
     * {@code MIN_INTERIOR_HEIGHT} is 8 and {@code DOME_BLOCKS_ABOVE_ACTIVE_FUEL}
     * is 5, so a 7-tall interior now fails validation twice over — on the height
     * floor, and again on "no room for active fuel between the lower plenum and
     * the steam dome". The harness went on building the same vessel and the
     * reactor quietly stopped forming.
     *
     * <p>Nothing here noticed, because the probe pass never asked whether the
     * plant it had built was assembled: a peripheral on an unformed reactor still
     * answers, still returns a clean value and still passes every check the probe
     * makes, so the run reported PASS on a plant that was not there. That is the
     * same shape of hole as a harness that exits 0 on failure, and it is why
     * {@link #reportStructures} now prints the nozzle's own formed answer as well
     * as the controller's — see the structure lines it emits, which are the first
     * place a reader should look.
     */
    private static final BlockPos INTERIOR_MAX = new BlockPos(12, 157, 12);
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
     * The RPV main steam nozzle, and it is <b>in the vessel wall</b>, not beside
     * it.
     *
     * <p>That is the whole difference between this block and every other satellite
     * the harness stands up. {@code ReactorStructure.isShell} accepts an
     * {@code rpv_steam_outlet} as pressure boundary and
     * {@code ReactorStructure.isInteriorBlock} refuses it as open space, so the
     * nozzle is a wall block in both directions: put one next to the reactor and
     * the shell it should have been part of has a hole in it, and the plant fails
     * to form with "gap in the reactor vessel shell". It therefore goes on top of
     * a {@code reactor_vessel} block that {@link #fillShell} has already written,
     * exactly as {@link #CONTROLLER} does.
     *
     * <p>Where in the wall, and why here:
     * <ul>
     *   <li><b>West face</b> ({@code x = INTERIOR_MIN.getX() - 1 = 7}),
     *       deliberately the opposite wall from the controller on the east face.
     *       {@code ReactorStructure.findInteriorSeed} walks the controller's own
     *       six neighbours and the order it walks them in is load-bearing — see
     *       the note on {@link #CONTROLLER} — so a second shell substitution is
     *       kept well away from those six rather than left to interact with
     *       them.</li>
     *   <li><b>y=156</b>, one row below the top of the interior and four above
     *       the top of active fuel, which for this vessel is
     *       {@code INTERIOR_MAX.getY() - 5 = 152}. That puts the penetration in
     *       the steam dome, where a real main steam nozzle is. It is also, as it
     *       happens, the elevation {@code checkSpargerRings} wants HPCS segments
     *       at, and that costs nothing: both sparger passes walk {@code x} and
     *       {@code z} strictly <i>inside</i> the interior — 8..12 here — and this
     *       block is at {@code x=7}, in the wall.</li>
     *   <li><b>z=10</b>, the middle of the face, so it is a face block and never
     *       an edge or a corner: {@code checkShellClosed} only requires the six
     *       faces to be shell and skips the outer corners entirely, so a nozzle
     *       parked on one would never be found and would never join
     *       {@code steamOutletPositions()}.</li>
     * </ul>
     */
    private static final BlockPos STEAM_OUTLET = new BlockPos(7, 156, 10);

    /**
     * One pressurised tube welded onto the nozzle's outer face, in open air
     * outside the vessel.
     *
     * <p>For the reason {@link #RELIEF_VALVE} sits over standing water rather
     * than over air: a nozzle with nothing on its flange is a hole with no pipe,
     * it passes nothing, {@code isSteamLineAttached()} answers false and
     * {@code ReactorStructure.reportSteamOutlets} degrades the plant. Welding one
     * tube on exercises the connected path — the peripheral's
     * {@code isSteamLineAttached} and the block entity's line survey — instead of
     * only its disconnected one.
     *
     * <p>It cannot be mistaken for part of the shell. {@code checkShellClosed}
     * only ever visits {@code x} in {@code [7, 13]}, and this is at {@code x=6}.
     */
    private static final BlockPos STEAM_OUTLET_TUBE = new BlockPos(6, 156, 10);

    /**
     * A nozzle in mid-air with no vessel round it, out among the other unattached
     * satellites and inside the force-loaded chunks.
     *
     * <p>The unformed half of the pair, in the same sense as
     * {@link #LONE_CONTROLLER} and {@link #LONE_POOL_CONTROLLER}, and it is not
     * ceremony. Only the controller of a vessel that has actually assembled walks
     * its nozzles, so {@code lastPolledGameTime} on this one is never written and
     * stays at its {@code Long.MIN_VALUE} sentinel — which is precisely the input
     * that used to break {@code isPartOfFormedReactor()}. The test was a
     * subtraction, {@code getGameTime() - lastPolledGameTime}, and that overflows
     * for any ordinary game time and wraps to a large negative number, so the
     * never-polled case answered <i>true</i> and a nozzle lying on the floor
     * claimed to be part of a formed reactor and went on quoting the last flow it
     * ever passed. It is a test now, and a probe of a never-polled nozzle is what
     * would catch it coming back.
     */
    private static final BlockPos LONE_STEAM_OUTLET = new BlockPos(36, 150, 40);

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
            STEAM_OUTLET, LONE_STEAM_OUTLET,
    };

    private static final String[] PROBE_LABELS = {
            "reactor (formed)", "reactor (NEVER FORMED)",
            "suppression pool (formed)", "suppression pool (NEVER FORMED)",
            "turbine steam outlet (unattached)", "RCIC pump (unattached)",
            "ADS controller (unattached)", "condensate storage tank (unattached)",
            "safety relief valve (over the pool)", "MSIV (unattached)",
            "recirculation pump (bound to the formed reactor)",
            "RPV steam nozzle (in the formed vessel shell, steam line welded on)",
            "RPV steam nozzle (NEVER FORMED, no vessel and no line)",
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

        // The soft-dependency split, exercised on whichever side of it this run
        // is on. Kept in its own try so that a throw here costs one counted
        // problem instead of aborting the peripheral pass that follows it — the
        // two are independent and losing the second to a fault in the first
        // would report far less than the run actually proved.
        int boundaryFailures;
        try {
            boundaryFailures = reportMekanismBoundary(server.overworld());
        } catch (Throwable t) {
            LOGGER.error("=== BWR PERIPHERAL RUNTIME CHECK: THE MEKANISM BOUNDARY REPORT ITSELF "
                    + "FAILED ===", t);
            boundaryFailures = 1;
        }

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

        // Added after the block above, never before it: onThreadFailures is
        // ASSIGNED in there, so a boundary finding folded in earlier would be
        // overwritten and the run would report a clean pass on a dead steam
        // boundary. The catch increments rather than assigns, so this is correct
        // on both paths.
        onThreadFailures += boundaryFailures;

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
     *
     * <p>These lines are the harness's only statement about the world it built,
     * as opposed to about the peripherals hanging off it, and the probe pass does
     * not read them: a peripheral on an unformed multiblock answers perfectly
     * cleanly and passes every check the probe makes. So an unformed plant here
     * is not a failure the run will count — it is a run that proved less than it
     * looks like it did, and it has to be read rather than assumed. See
     * {@link #INTERIOR_MAX} for the time that mattered.
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
                // Whether the nozzle welded into the wall was read as pressure
                // boundary or as a hole in it. A nozzle only reaches
                // steamOutletPositions() by surviving the shell walk, so a count
                // of zero on a formed vessel means it was put somewhere the walk
                // does not visit — an outer corner, or outside the six faces —
                // and the "in the formed vessel shell" probe below is quietly
                // probing a nozzle that is part of nothing.
                if (r.structure() != null) {
                    LOGGER.info("   RPV steam nozzles in this shell: {} at {}",
                            r.structure().steamOutletCount(),
                            r.structure().steamOutletPositions());
                }
            } else if (be instanceof dev.bwr.mod.suppression.SuppressionPoolBlockEntity s) {
                LOGGER.info("   formed={} status={}", s.isFormed(), s.statusLines());
            } else if (be instanceof dev.bwr.mod.reactor.RpvSteamOutletBlockEntity n) {
                // The nozzle's own answer, and the two halves of the pair are
                // supposed to disagree: true for the one in the shell, false for
                // the one lying on the floor. Both true is the signed-overflow
                // defect described on LONE_STEAM_OUTLET coming back.
                LOGGER.info("   partOfFormedReactor={} steamLineAttached={} status={}",
                        n.isPartOfFormedReactor(), n.isSteamLineAttached(), n.statusLines());
            }
        }
    }

    // -----------------------------------------------------------------
    // The Mekanism steam boundary
    // -----------------------------------------------------------------

    /**
     * What the turbine steam outlet looks like from Mekanism's side, and the one
     * part of this harness whose output is supposed to differ between the two
     * runs it is executed in.
     *
     * <p>Three questions, in the order a player who says "the steam line isn't
     * connecting" needs them answered:
     *
     * <ol>
     *   <li><b>Is Mekanism loaded?</b> {@code BwrMod.isMekanismPresent()} — the
     *       {@code ModList} answer taken at mod construction, which is the flag
     *       every Mekanism-facing branch in the mod is gated on.</li>
     *   <li><b>Did the chemical capability actually resolve on the block
     *       entity?</b> Not "is it registered" — resolved, at the outlet's own
     *       position, through the real capability lookup, from every side. That
     *       is the only thing that proves {@code BwrMekanismSupport
     *       .registerCapabilities} both ran and attached to the right block
     *       entity type. A registration that silently missed would leave the
     *       outlet looking perfect from Lua and invisible to every pipe in the
     *       world, which is precisely the failure that was reported.</li>
     *   <li><b>Is an acceptor attached?</b> Every neighbour of the outlet is
     *       asked for the same capability from the face that looks back at us.
     *       A Mekanism pressurized tube or turbine valve answers; our own
     *       pressurised tube does not, and is not meant to.</li>
     * </ol>
     *
     * <h2>What counts as a failure and what does not</h2>
     * The capability being absent with Mekanism absent is the <i>correct</i>
     * result and is reported as the guarded path, not as a problem. The two
     * genuine failures are the two ways the soft-dependency split can be wrong:
     * Mekanism present and the capability not resolving (the boundary is dead
     * and nothing can ever connect to it), or Mekanism absent and a
     * {@code mekanism:}-named capability existing anyway (something loaded
     * {@code dev.bwr.mod.mekanism} without Mekanism, which on a player's install
     * is a {@code NoClassDefFoundError} waiting for the first tick).
     *
     * <p>Finding <i>no</i> acceptor is not a failure. The harness deliberately
     * places no Mekanism block: doing so would mean naming one, and this package
     * may not. The scan is here so that a human who runs the harness world and
     * puts a real pipe on the outlet by hand gets a straight answer, and so that
     * the count is stated rather than assumed.
     *
     * @return problems found, to be added to the run's failure count
     */
    private static int reportMekanismBoundary(ServerLevel level) {
        LOGGER.info("=== BWR PERIPHERAL RUNTIME CHECK: the Mekanism steam boundary at {} ===",
                TURBINE_OUTLET);

        boolean loaded = BwrMod.isMekanismPresent();
        LOGGER.info("  ModList says mekanism is loaded: {}", loaded);

        // Our side of the boundary first, so that a dead capability can be told
        // apart from an outlet that was never built or never bound. None of these
        // are Mekanism types; they are plain kilograms and millibuckets.
        if (level.getBlockEntity(TURBINE_OUTLET) instanceof TurbineSteamOutletBlockEntity outlet) {
            LOGGER.info("  outlet: attached={} commanded={} kg/s delivered={} kg/s "
                            + "buffer={}/{} mB",
                    outlet.isAttached(), outlet.getCommandedFlowKgPerS(),
                    outlet.getDeliveredFlowKgPerS(), outlet.getBufferedMilliBuckets(),
                    outlet.getBufferCapacityMilliBuckets());
        } else {
            LOGGER.error("FAIL Mekanism boundary: there is no turbine steam outlet block entity "
                    + "at {}, so there is nothing for the capability to attach to", TURBINE_OUTLET);
            return 1;
        }

        BlockCapability<?, ?> capability = findChemicalHandlerCapability();

        if (capability == null) {
            if (loaded) {
                LOGGER.error("FAIL Mekanism boundary: mekanism is loaded but no block capability "
                        + "named '{}' exists in this JVM. Mekanism renames it, or its "
                        + "Capabilities class never initialised; either way nothing this mod "
                        + "offers steam through can ever be found by a pipe",
                        MEKANISM_CHEMICAL_HANDLER);
                return 1;
            }
            LOGGER.info("  no block capability named '{}' exists in this JVM, which is correct: "
                            + "nothing created one, so dev.bwr.mod.mekanism was never loaded and "
                            + "no mekanism.* type was ever resolved",
                    MEKANISM_CHEMICAL_HANDLER);
            LOGGER.info("  VERDICT: Mekanism ABSENT -- this is the -PbwrNoMekanism run and the "
                    + "guarded path held. The outlet places, ticks, binds and reports; its "
                    + "buffer fills and nothing drains it, so the plant behaves exactly as it "
                    + "would with the turbine stop valves shut. That is the designed behaviour, "
                    + "not a fault.");
            return 0;
        }

        LOGGER.info("  block capability '{}' exists, declared over {} with context {}",
                capability.name(), capability.typeClass().getName(),
                capability.contextClass().getName());

        if (!loaded) {
            LOGGER.error("FAIL Mekanism boundary: ModList says mekanism is NOT loaded, yet a "
                    + "capability named '{}' has been created in this JVM. Creating it requires "
                    + "resolving {}, so something outside the isMekanismPresent() branch has "
                    + "touched a Mekanism type. On a player's install that is a "
                    + "NoClassDefFoundError, not a warning",
                    MEKANISM_CHEMICAL_HANDLER, capability.typeClass().getName());
            return 1;
        }

        if (capability.contextClass() != Direction.class) {
            LOGGER.error("FAIL Mekanism boundary: '{}' is not a sided capability -- its context "
                    + "type is {}, not Direction -- so the sided lookup this mod registers "
                    + "against it cannot be the same capability",
                    MEKANISM_CHEMICAL_HANDLER, capability.contextClass().getName());
            return 1;
        }

        int failures = 0;

        // Every side, plus the null "no particular side" context, because that is
        // the set BwrMekanismSupport claims to answer on and a registration that
        // quietly covered only some of them would still look right from Lua.
        List<Direction> sides = sidesIncludingNull();
        List<String> missing = new ArrayList<>();
        String handlerClass = null;
        for (Direction side : sides) {
            Object handler = resolveChemicalHandler(level, capability, TURBINE_OUTLET, side);
            if (handler == null) {
                missing.add(side == null ? "no side" : side.getName());
            } else if (handlerClass == null) {
                handlerClass = handler.getClass().getName();
            }
        }
        int resolved = sides.size() - missing.size();
        if (missing.isEmpty()) {
            LOGGER.info("  the chemical capability RESOLVED on the outlet from all {} side(s), "
                    + "handler = {}", sides.size(), handlerClass);
        } else {
            LOGGER.error("FAIL Mekanism boundary: the chemical capability resolved on the outlet "
                            + "at {} from only {} of {} side(s); it returned nothing for: {}. A "
                            + "pipe on one of those faces sees no tank at all and the steam never "
                            + "leaves the plant",
                    TURBINE_OUTLET, resolved, sides.size(), missing);
            failures++;
        }

        // ...and now the other half of the player's question: is anything there
        // to take it? The neighbour is asked from the face that looks back at the
        // outlet, which is the side a pipe presents to us.
        int acceptors = 0;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = TURBINE_OUTLET.relative(side);
            // Never ask about a block in an unloaded chunk: the lookup would go
            // through getBlockState and generate terrain to answer. Everything
            // this harness builds is inside chunks it force-loads, so this is
            // belt and braces against a future layout change rather than a case
            // that arises today.
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            Object handler =
                    resolveChemicalHandler(level, capability, neighbour, side.getOpposite());
            if (handler != null) {
                acceptors++;
                LOGGER.info("  acceptor on the {} face: {} at {} exposes {}",
                        side.getName(), level.getBlockState(neighbour).getBlock(), neighbour,
                        handler.getClass().getName());
            }
        }
        LOGGER.info("  acceptors adjacent to the outlet: {}", acceptors);
        if (acceptors == 0) {
            LOGGER.info("  ...which is expected here and is NOT counted as a problem: this "
                    + "harness places no Mekanism block, because naming one is not permitted "
                    + "outside dev.bwr.mod.mekanism. It is also, note, exactly the state a "
                    + "player is in when they report that the steam line will not connect -- "
                    + "our pressurised tube is structural and exposes no chemical handler, so "
                    + "running one at a turbine leaves this count at zero. A Mekanism "
                    + "pressurized tube has to touch this block itself.");
        }

        LOGGER.info("  VERDICT: Mekanism PRESENT -- the chemical capability resolved on the "
                        + "outlet from {} of {} side(s), handler = {}, with {} acceptor(s) "
                        + "adjacent. Compare this whole block against the -PbwrNoMekanism run, "
                        + "where none of it exists.",
                resolved, sides.size(), handlerClass, acceptors);
        return failures;
    }

    /**
     * Mekanism's block chemical-handler capability if anything in this JVM has
     * created it, else null.
     *
     * <p>Found by walking the capability registry and comparing names, which is
     * the only way to get hold of it without naming {@code IChemicalHandler} —
     * see the class comment. {@code BlockCapability.create*} is memoised per
     * name, so there is at most one entry to find.
     */
    private static BlockCapability<?, ?> findChemicalHandlerCapability() {
        for (BlockCapability<?, ?> capability : BlockCapability.getAll()) {
            if (MEKANISM_CHEMICAL_HANDLER.equals(capability.name())) {
                return capability;
            }
        }
        return null;
    }

    /**
     * Resolve a wildcard-typed sided capability at a position, keeping the result
     * as a bare {@link Object}.
     *
     * <p>The cast is unchecked and erased: at run time it compiles to a
     * {@code checkcast} against {@code BlockCapability} itself, so this method
     * never names, references or loads the {@code mekanism.api} interface the
     * capability is declared over. Callers must have established that the
     * context type really is {@link Direction} — {@link #reportMekanismBoundary}
     * does, and reports it as a failure when it is not.
     *
     * <p>The value that comes back is the mod's own
     * {@code TurbineSteamOutletChemicalHandler} on our block, or somebody else's
     * handler on a neighbour. It is only ever asked for its class name, so
     * nothing here has to know what it can do.
     */
    @SuppressWarnings("unchecked")
    private static Object resolveChemicalHandler(ServerLevel level, BlockCapability<?, ?> capability,
                                                 BlockPos pos, Direction side) {
        BlockCapability<Object, Direction> sided = (BlockCapability<Object, Direction>) capability;
        return level.getCapability(sided, pos, side);
    }

    /**
     * The six faces plus the null context, in one list.
     *
     * <p>Null is a real and distinct argument to a sided capability lookup — it
     * means "no particular side" — and {@code BwrMekanismSupport} registers a
     * provider that ignores the side entirely, so all seven have to answer. A
     * plain {@code Direction[]} cannot carry the null, hence the list.
     */
    private static List<Direction> sidesIncludingNull() {
        List<Direction> sides = new ArrayList<>();
        sides.add(null);
        for (Direction d : Direction.values()) {
            sides.add(d);
        }
        return sides;
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

        // ...and one more becomes the main steam nozzle, written straight over
        // the vessel block fillShell has just put there. A nozzle IS the wall —
        // see STEAM_OUTLET — so this is the only way to build one into a plant;
        // standing it off beside the reactor leaves the hole it should have
        // filled and validation rejects the whole vessel.
        //
        // Then a tube on its outer face, so the connected path is the one that
        // gets probed. Placed second and with the neighbour-update flag, which is
        // what fires RpvSteamOutletBlock.neighborChanged on the nozzle and gets
        // isSteamLineAttached() answered before the first tick rather than up to
        // a second later.
        level.setBlock(STEAM_OUTLET, BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 3);
        level.setBlock(STEAM_OUTLET_TUBE, BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(), 3);

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

        // ...and a nozzle in mid-air, which nothing will ever poll. See
        // LONE_STEAM_OUTLET for the overflow this one is standing guard over.
        level.setBlock(LONE_STEAM_OUTLET,
                BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 3);

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
                        + "relief valve at {}, MSIV at {}, recirculation pump at {}, "
                        + "RPV steam nozzle in the vessel wall at {} with a tube on {}, "
                        + "lone RPV steam nozzle at {}",
                CONTROLLER, LONE_CONTROLLER, POOL_CONTROLLER, LONE_POOL_CONTROLLER,
                TURBINE_OUTLET, RELIEF_VALVE, MSIV, RECIRCULATION_PUMP,
                STEAM_OUTLET, STEAM_OUTLET_TUBE, LONE_STEAM_OUTLET);
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
