package dev.bwr.mod.devtest;

import dev.bwr.mod.TickProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server tick cost of a whole plant. Builds {@link PlantKit}, lets it settle, then measures a window of ticks with
 * {@link TickProbe} and prints one "TICK PROBE" line per system. Runs in its own batch so no other test is ticking
 * beside it. The microsecond figures only exist when the JVM runs with {@code -Dbwr.tickProbe=true}
 * ({@code -PbwrTickProbe}); without it the test still builds and runs the plant and checks the counters.
 */
@GameTestHolder("bwrtick")
@PrefixGameTestTemplate(false)
public final class TickBudgetTests {
    private TickBudgetTests() {
    }

    static final BlockPos ORIGIN = new BlockPos(305_000, 190, 305_000);

    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "tickprobe", timeoutTicks = 1500)
    public static void plantTickProfile(GameTestHelper helper) {
        var level = helper.getLevel();
        PlantKit kit = new PlantKit(level, ORIGIN);
        Script script = new Script(helper, 1500, () -> {
            if (kit.reactor != null && level.getGameTime() % 20 == 0) {
                kit.energise();
            }
        }, () -> {
            kit.unwatch();
            kit.clear();
            kit.hold(false);
        });
        script.then(1, () -> kit.hold(true));
        script.then(3, () -> {
            try {
                kit.clear();
                kit.build();
                kit.watch(kit.min);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.then(300, () -> {
            kit.status().forEach(l -> System.out.println("TICK PLANT settled: " + l));
            TickProbe.reset();
        });
        script.then(700, () -> {
            TickProbe.report("plant 700 ticks");
            kit.status().forEach(l -> System.out.println("TICK PLANT end: " + l));
        });
        script.start();
    }
}
