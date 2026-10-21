package dev.bwr.mod.devtest;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.nodal.FluxSolveExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The 1 Hz nodal flux solve on a worker thread gives the reactor exactly what the same solve on the server thread
 * gives, on the whole plant ({@link PlantKit}) running for {@link #SECONDS} seconds (burnup, flow, pressure and level
 * moving). The plant's reactor runs {@code DEFERRED_ASYNC_CHECKED}: every solve iterates on the {@code bwr-flux-N}
 * pool, as the mod runs it, and a second time on the server thread, as {@code -Dbwr.fluxAsync=false} runs it, from
 * the same assembled problem; the apply compares the two answers in every bit and installs the pool's. The reactor
 * state is a function of those answers and of nothing else that differs between the two modes (both apply on the same
 * tick), so identical answers on every refresh are identical reactor states after any number of seconds. The core
 * acceptance test {@code DeferredNodalRefreshTest} also runs a pool core and an inline core side by side and compares
 * their whole records after 60 s.
 *
 * <p>(The direct comparison, the plant built twice and run once per mode, is not usable here: two runs of this
 * fixture already differ with both runs inline, by about 0.01 % of power after 30 s. That is the fixture and the
 * plant machines, not the flux solve.)
 */
@GameTestHolder("bwrtick")
@PrefixGameTestTemplate(false)
public final class FluxAsyncTests {
    private FluxAsyncTests() {
    }

    static final int SECONDS = 30;
    static final BlockPos ORIGIN = new BlockPos(340_000, 190, 340_000);

    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "fluxasync", timeoutTicks = 900)
    public static void asyncAndInlineFluxSolvesGiveTheSameReactor(GameTestHelper helper) {
        var level = helper.getLevel();
        PlantKit kit = new PlantKit(level, ORIGIN);
        long[] tasksAtStart = new long[1];
        int[] inFlight = new int[1];
        Script script = new Script(helper, 900, () -> {
            if (kit.reactor != null) {
                if (level.getGameTime() % 20 == 0) {
                    kit.energise();
                }
                if (kit.reactor.core().hasPendingNodalRefresh()) {
                    inFlight[0]++;
                }
            }
        }, () -> {
            kit.clear();
            kit.hold(false);
        });
        script.then(1, () -> kit.hold(true));
        script.then(3, () -> {
            try {
                kit.clear();
                kit.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            kit.reactor.core().setNodalRefresh(ReactorCore.NodalRefresh.DEFERRED_ASYNC_CHECKED);
            tasksAtStart[0] = FluxSolveExecutor.tasksRun();
        });
        script.then(SECONDS * 20, () -> {
            helper.assertTrue(kit.reactor.isFormed(), "the plant reactor is not formed");
            var core = kit.reactor.core();
            long tasks = FluxSolveExecutor.tasksRun() - tasksAtStart[0];
            System.out.printf(java.util.Locale.ROOT,
                    "FLUX ASYNC after %d s on the plant: %d solves compared, %d differed; %d iterated on the pool "
                            + "(%d threads); a refresh in flight on %d of %d ticks; power %.3f MW, pressure %.2f psig, "
                            + "last solve %d iterations%n",
                    SECONDS, core.getNodalRefreshChecks(), core.getNodalRefreshCheckMismatches(), tasks,
                    FluxSolveExecutor.THREADS, inFlight[0], SECONDS * 20, core.getThermalPowerMW(), core.getPressurePsig(),
                    core.getFluxSolution().iterations());
            helper.assertTrue(core.getNodalRefreshChecks() >= SECONDS - 2,
                    "only " + core.getNodalRefreshChecks() + " solves were compared in " + SECONDS + " s");
            helper.assertTrue(core.getNodalRefreshCheckMismatches() == 0,
                    core.getNodalRefreshCheckMismatches() + " of " + core.getNodalRefreshChecks()
                            + " pool solves differed from the same solve on the server thread");
            helper.assertTrue(tasks >= SECONDS - 2, "the pool ran only " + tasks + " solves in " + SECONDS + " s");
        });
        script.start();
    }
}
