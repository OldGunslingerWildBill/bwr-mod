package dev.bwr.mod.devtest;

import dev.bwr.mod.TickProbe;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.rods.ControlRodDriveSupplies;
import dev.bwr.mod.world.BlockScan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server tick cost of a whole plant. Builds {@link PlantKit}, lets it settle, then measures a window of ticks.
 *
 * <p>Two kinds of result. With {@code -PbwrTickProbe} ({@code -Dbwr.tickProbe=true}) {@link TickProbe} prints one
 * "TICK PROBE" line per system: mean and worst microseconds per server tick and allocation per tick. Those are for
 * reading, not for asserting: they move with the machine. What the test <em>asserts</em> is work, which does not:
 * how many times a pipe network was rebuilt, how many control rod banks were re-surveyed, how many blocks the
 * sectioned scans read, over the whole window. A machine that goes back to rescanning every tick, or a network
 * that rebuilds on every tick, fails here on any computer, loaded or not.
 *
 * <p>Runs in its own batch so no other test is ticking beside it.
 */
@GameTestHolder("bwrtick")
@PrefixGameTestTemplate(false)
public final class TickBudgetTests {
    private TickBudgetTests() {
    }

    static final BlockPos ORIGIN = new BlockPos(305_000, 190, 305_000);

    /** Ticks measured, after the plant has settled. */
    static final int WINDOW = 700;

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    /**
     * What one panel input costs the sampler: every signal of each kind of plant device is read a few hundred times
     * through the same {@code SignalProviders.at} the panels use; the table is the dearest signals per device.
     */
    private static void signalCost(PlantKit kit) {
        var devices = new java.util.LinkedHashMap<String, BlockPos>();
        devices.put("reactor", kit.reactor.getBlockPos());
        if (!kit.recirculation.isEmpty()) {
            devices.put("recirculation pump", kit.recirculation.get(0).getBlockPos());
        }
        if (!kit.outlets.isEmpty()) {
            devices.put("turbine steam outlet", kit.outlets.get(0).getBlockPos());
        }
        if (!kit.msivs.isEmpty()) {
            devices.put("msiv", kit.msivs.get(0));
        }
        if (!kit.eccs.isEmpty()) {
            devices.put("eccs pump", kit.eccs.get(kit.eccs.size() - 1).getBlockPos());
        }
        if (kit.pool != null) {
            devices.put("suppression pool", kit.pool.getBlockPos());
        }
        for (var e : devices.entrySet()) {
            var provider = dev.bwr.mod.signal.SignalProviders.at(kit.level, e.getValue());
            if (provider == null) {
                continue;
            }
            record Row(String key, double micros) {
            }
            var rows = new java.util.ArrayList<Row>();
            for (var spec : provider.signals()) {
                for (int i = 0; i < 20; i++) {
                    dev.bwr.mod.signal.SignalProviders.at(kit.level, e.getValue()).read(spec.key());
                }
                long t = System.nanoTime();
                for (int i = 0; i < 200; i++) {
                    dev.bwr.mod.signal.SignalProviders.at(kit.level, e.getValue()).read(spec.key());
                }
                rows.add(new Row(spec.key(), (System.nanoTime() - t) / 1000.0 / 200));
            }
            rows.sort(java.util.Comparator.comparingDouble(Row::micros).reversed());
            StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "SIGNAL COST %-22s %3d signals, median %.2f us per read;",
                    e.getKey(), rows.size(), rows.isEmpty() ? 0 : rows.get(rows.size() / 2).micros()));
            for (int i = 0; i < Math.min(4, rows.size()); i++) {
                b.append(String.format(java.util.Locale.ROOT, " %s %.1f", rows.get(i).key(), rows.get(i).micros()));
            }
            System.out.println(b);
        }
    }

    /**
     * What a cable costs the server thread when it feeds a machine: the four calls an energy network makes on an
     * acceptor each tick, through the capability the machine exposes, timed per machine.
     */
    private static void energyCapabilityCost(PlantKit kit) {
        var machines = new java.util.LinkedHashMap<String, BlockPos>();
        if (!kit.recirculation.isEmpty()) {
            machines.put("recirculation pump", kit.recirculation.get(0).getBlockPos());
        }
        for (var e : kit.eccs) {
            machines.put("eccs " + e.design().displayName(), e.getBlockPos());
        }
        for (var f : kit.feed) {
            machines.put("feedwater pump", f.getBlockPos());
        }
        for (var m : kit.msivs) {
            machines.put("msiv", m.above(2));
            break;
        }
        for (var e : machines.entrySet()) {
            for (var side : net.minecraft.core.Direction.values()) {
                var cap = kit.level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, e.getValue(), side);
                if (cap == null) {
                    continue;
                }
                for (int i = 0; i < 50; i++) {
                    cap.receiveEnergy(0, true);
                }
                long t = System.nanoTime();
                for (int i = 0; i < 300; i++) {
                    cap.canReceive();
                    cap.receiveEnergy(1, true);
                    cap.getEnergyStored();
                    cap.getMaxEnergyStored();
                }
                System.out.printf(java.util.Locale.ROOT, "CABLE COST %-24s %.2f us per tick of cable traffic (canReceive, receiveEnergy, stored, capacity)%n",
                        e.getKey(), (System.nanoTime() - t) / 1000.0 / 300);
                break;
            }
        }
    }

    /**
     * The cost of one rescan of a machine's search cube, the way it used to be done (every block of the cube through
     * isLoaded and getBlockState) against {@link BlockScan}, on the same cubes of the same plant. Radius 24 is the
     * pumps, the ADS controller and the suppression pool; 12 is the turbine outlet.
     */
    private static void scanCost(PlantKit kit) {
        var level = kit.level;
        for (int radius : new int[]{24, 12}) {
            for (var around : new BlockPos[]{kit.reactor.getBlockPos(), kit.min.offset(8, 4, 30)}) {
                BlockPos lo = around.offset(-radius, -radius, -radius);
                BlockPos hi = around.offset(radius, radius, radius);
                java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> candidate = st ->
                        st.is(dev.bwr.mod.registry.BwrBlocks.REACTOR_CONTROLLER.get())
                                || st.is(dev.bwr.mod.registry.BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get())
                                || st.is(dev.bwr.mod.registry.BwrBlocks.CONDENSATE_STORAGE_TANK.get());
                int[] found = new int[2];
                for (int round = 0; round < 3; round++) {
                    found[0] = 0;
                    found[1] = 0;
                    long t = System.nanoTime();
                    for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                        if (!level.isLoaded(p)) {
                            continue;
                        }
                        if (candidate.test(level.getBlockState(p))) {
                            found[0]++;
                        }
                    }
                    long old = System.nanoTime() - t;
                    t = System.nanoTime();
                    BlockScan.scan(level, lo, hi, candidate, (p, st) -> {
                        found[1]++;
                        return true;
                    });
                    long now = System.nanoTime() - t;
                    if (round == 2) {
                        System.out.printf(java.util.Locale.ROOT, "SCAN COST radius %d around %s: block by block %.0f us, by section %.0f us (%d / %d found)%n",
                                radius, around.toShortString(), old / 1000.0, now / 1000.0, found[0], found[1]);
                        helperCheck(found[0] == found[1], "the sectioned scan found " + found[1] + " blocks, the block-by-block scan " + found[0]);
                    }
                }
            }
        }
    }

    private static void helperCheck(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "tickprobe", timeoutTicks = 1700)
    public static void plantTickProfileAndWorkBudget(GameTestHelper helper) {
        var level = helper.getLevel();
        PlantKit kit = new PlantKit(level, ORIGIN);
        long[] before = new long[6];
        Script script = new Script(helper, 1700, () -> {
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
            helper.assertTrue(kit.reactor.isFormed(), "plant reactor is not formed");
            helper.assertTrue(kit.outlets.stream().allMatch(o -> o.isNozzleFed() && o.isAttached()), "a steam line is not connected");
            helper.assertTrue(kit.boundInputs > 0, "no panel input could be bound");
            TickProbe.reset();
            before[0] = PipeTopology.buildCount();
            before[1] = ControlRodDriveSupplies.buildCount();
            before[2] = BlockScan.scanCount();
            before[3] = BlockScan.cellsRead();
            before[4] = allocated();
            before[5] = dev.bwr.mod.reactor.ReactorBoundaryEffects.lineEvaluations();
        });
        script.then(WINDOW, () -> {
            long bytes = allocated() - before[4];
            TickProbe.report("plant " + WINDOW + " ticks");
            System.out.printf(java.util.Locale.ROOT, "TICK WORK server thread allocated %.1f KB/tick, pipe graphs built %d, rod banks built %d, sectioned scans %d reading %d blocks%n",
                    bytes / 1024.0 / WINDOW, PipeTopology.buildCount() - before[0], ControlRodDriveSupplies.buildCount() - before[1],
                    BlockScan.scanCount() - before[2], BlockScan.cellsRead() - before[3]);
            long lineEvaluations = dev.bwr.mod.reactor.ReactorBoundaryEffects.lineEvaluations() - before[5];
            System.out.printf(java.util.Locale.ROOT, "TICK WORK pressure-boundary line evaluations %d in %d ticks%n", lineEvaluations, WINDOW);
            kit.status().forEach(l -> System.out.println("TICK PLANT end: " + l));
            // Work, not time: none of these may grow with the number of ticks in a steady plant.
            long graphs = PipeTopology.buildCount() - before[0];
            helper.assertTrue(graphs <= 40, "pipe networks were rebuilt " + graphs + " times in " + WINDOW + " ticks of a steady plant");
            long banks = ControlRodDriveSupplies.buildCount() - before[1];
            helper.assertTrue(banks <= 5, "rod banks were re-surveyed " + banks + " times in " + WINDOW + " ticks of a steady plant");
            long cells = BlockScan.cellsRead() - before[3];
            helper.assertTrue(cells <= 200_000, "sectioned scans read " + cells + " blocks in " + WINDOW + " ticks of a steady plant");
            // Each line is re-evaluated only after its cached pipe graph was replaced; a steady plant replaces none.
            helper.assertTrue(lineEvaluations <= 40, "pressure-boundary lines were re-evaluated " + lineEvaluations + " times in " + WINDOW + " ticks of a steady plant");
        });
        script.then(1, () -> {
            signalCost(kit);
            energyCapabilityCost(kit);
            scanCost(kit);
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                PeripheralCost.all(kit);
            }
        });
        script.start();
    }
}
