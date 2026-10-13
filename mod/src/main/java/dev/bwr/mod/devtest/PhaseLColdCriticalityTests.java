package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** L3-fix2: fuel alone must not turn an all-rods-in cold reactor into first criticality. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseLColdCriticalityTests {
    private PhaseLColdCriticalityTests() { }

    @GameTest(template = "empty", batch = "l3_cold_criticality", timeoutTicks = 300)
    public static void fullColdCoreWithAllRodsInsertedDoesNotAwardCriticality(GameTestHelper h) throws Exception {
        // Slot 11 is disjoint from the earlier advancement fixtures, including both panel regressions.
        try (var f = new PhaseLAdvancementTests.Fixture(h, 11)) {
            var reactor = f.reactor(true);
            var core = reactor.core();
            h.assertTrue(reactor.setVesselState(VesselState.SHUTDOWN) == null, "cold vessel head did not close");
            h.assertTrue(core.getCoreLoading().loadedAssemblyCount() == reactor.assemblyCount()
                    && reactor.assemblyCount() > 0, "cold fixture is not fully fuelled");
            h.assertTrue(core.getTickCount() == 0 && core.getElapsedSeconds() == 0,
                    "fixture already advanced before the first post-formation tick");
            h.assertTrue(core.getFuelTemperatureC() < 100, "fixture was initialized hot");
            assertEveryRodInserted(h, reactor, 0);
            h.assertTrue(!f.has(f.owner, "first_criticality"), "formation/fuelling awarded first criticality");

            // The ordinary BE ticker applies rod-network demands and physics, then the real observer.
            // No direct observer calls, reactivity overrides, scram, or hot initialiser are used.
            for (int tick = 1; tick <= 41; tick++) {
                ReactorControllerBlockEntity.serverTick(f.level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
                h.assertTrue(reactor.isFormed() && reactor.core() == core, "normal tick rebuilt or lost the cold core");
                h.assertTrue(core.getTickCount() == tick, "normal BE tick did not advance reactor physics exactly once");
                assertEveryRodInserted(h, reactor, tick);
                h.assertTrue(!f.has(f.owner, "first_criticality") && !f.has(f.other, "first_criticality"),
                        (tick == 1 ? "first tick after forming" : "cold all-rods-in tick " + tick)
                                + " awarded criticality; rho=" + core.getReactivityDkOverK()
                                + ", neutron=" + core.getNeutronPowerFraction());
            }
            h.assertTrue(core.getElapsedSeconds() >= 40 * core.getConfig().tickSeconds,
                    "fewer than forty normal physics ticks elapsed");
            com.mojang.logging.LogUtils.getLogger().info(
                    "L3 COLD CRITICALITY: {} ordinary BE ticks, all rods at notch 0, rho={}, neutron={}, advancement=false",
                    core.getTickCount(), core.getReactivityDkOverK(), core.getNeutronPowerFraction());
        }
        h.succeed();
    }

    private static void assertEveryRodInserted(GameTestHelper h, ReactorControllerBlockEntity reactor, int tick) {
        var core = reactor.core();
        h.assertTrue(core.getControlRodCount() > 0, "fixture contains no control rods");
        for (int rod = 0; rod < core.getControlRodCount(); rod++) {
            h.assertTrue(core.getRodNotchDemand(rod) == 0 && Math.abs(core.getRodPositionNotches(rod)) <= 1e-9,
                    "rod " + rod + " was not held at notch 0 on tick " + tick);
        }
    }
}
