package dev.bwr.mod.devtest;

import dev.bwr.core.pool.SpentFuelPool;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.SpentFuelPoolMenu;
import dev.bwr.mod.gui.net.MenuSyncPayload;
import dev.bwr.mod.radiation.BlockShielding;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.water.ThermalWater;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pools of the size a real plant has: 2,048 racks, 8,192 assemblies, a 64 x 64 x 24 shell. That the largest pool forms and
 * the 2,049th rack and a 65th block of width or 25th of height are refused; that a full pool takes fuel through its item
 * handler, cools, saves and loads; what a tick, a survey, a dose reading and the client and panel packets cost at that
 * size (printed as {@code POOL SCALE} lines and bounded); and that the survey of the largest pool never loads a chunk.
 *
 * <p>Namespace {@code bwrpool} ({@code -PbwrOnlyPool} runs them with the other pool tests), batch {@code poolscale} of their own
 * so the pools of other tests are never in range of these pools' fuel (they read the dose of their own fuel exactly), and
 * the pools stand far apart from each other and from every other fixture.
 */
@GameTestHolder("bwrpool")
@PrefixGameTestTemplate(false)
public final class SpentFuelPoolScaleTests {

    private SpentFuelPoolScaleTests() {
    }

    /** Chunk-aligned corners, 800 blocks apart: no pool's fuel is in dose range of another's. */
    private static final BlockPos LIMITS = new BlockPos(-176_000, 200, 144_000);
    private static final BlockPos FULL = new BlockPos(-175_200, 200, 144_000);
    private static final BlockPos CHUNKS = new BlockPos(-174_400, 200, 144_000);

    private static final int SIDE = SpentFuelPoolLayout.MAX_OUTSIDE_SIDE;
    private static final int HIGH = SpentFuelPoolLayout.MAX_OUTSIDE_HEIGHT;
    private static final int RACKS = SpentFuelPoolLayout.MAX_RACKS;

    /** The virtual "now" of the timed runs, ticks; the assemblies' discharge stamps are made relative to it. */
    private static final long T0 = 60_000_000L;

    private static String problem(SpentFuelPoolBlockEntity be) {
        return be.problem() == null ? "" : BwrText.english(be.problem());
    }

    private static void rack(ServerLevel level, BlockPos at, boolean place) {
        for (int k = 0; k < SpentFuelPoolLayout.RACK_HEIGHT; k++) {
            level.setBlock(at.above(k), place ? BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static double ms(long nanos) {
        return nanos / 1.0e6;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Forming and refusing
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "poolscale", timeoutTicks = 400)
    public static void theLargestPoolFormsWith2048RacksAndRefusesThe2049th(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SpentFuelPoolFixture f = SpentFuelPoolFixture.buildFull(level, LIMITS, SIDE, SIDE, HIGH, RACKS);
        try {
            SpentFuelPoolBlockEntity be = f.be;
            h.assertTrue(f.racks.size() == RACKS, "fixture: " + f.racks.size() + " racks placed");
            h.assertTrue(be.isFormed(), "a 64 x 64 x 24 pool of 2,048 racks did not form: " + problem(be));
            h.assertTrue(be.rackCount() == RACKS && be.cellCount() == RACKS * SpentFuelPool.ASSEMBLIES_PER_RACK && be.layout().racks().size() == RACKS,
                    "racks " + be.rackCount() + ", cells " + be.cellCount());
            h.assertTrue(be.layout().footprintCells() == 62 * 62 && be.layout().heightCells() == 23, "interior " + be.layout().footprintCells() + " m2, " + be.layout().heightCells() + " m");
            h.assertTrue(be.pool().capacityKg() == 62.0 * 62.0 * 23.0 * 1000.0, "capacity " + be.pool().capacityKg());
            h.assertTrue(be.itemHandler().getSlots() == 8192, "item handler slots " + be.itemHandler().getSlots());

            // what a survey of the largest pool costs (the flood of 9,892 shell blocks and the sweep of 98,304 positions)
            long best = Long.MAX_VALUE;
            long total = 0;
            int runs = 6;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                SpentFuelPoolLayout.Layout found = SpentFuelPoolLayout.inspect(level, f.controller);
                long dt = System.nanoTime() - t0;
                h.assertTrue(found.ok() && found.racks().size() == RACKS, "survey " + i + ": " + (found.problem() == null ? "" : BwrText.english(found.problem())));
                if (i > 0) {
                    best = Math.min(best, dt);
                    total += dt;
                }
            }
            double mean = ms(total) / (runs - 1);
            System.out.println(String.format(Locale.ROOT, "POOL SCALE: survey of a 64 x 64 x 24 pool with 2048 racks: best %.2f ms, mean %.2f ms", ms(best), mean));
            h.assertTrue(mean < 60.0, "a survey of the largest pool took " + mean + " ms");

            // the 2,049th rack: the next square of the next row
            BlockPos extra = f.min.offset(1 + RACKS % (SIDE - 2), 1, SIDE - 2 - RACKS / (SIDE - 2));
            h.assertTrue(level.getBlockState(extra).isAir(), "fixture: the square for the 2,049th rack is free");
            rack(level, extra, true);
            be.scanNow();
            h.assertTrue(!be.isFormed() && problem(be).contains("at most 2048 racks"), "2,049 racks: " + problem(be));
            rack(level, extra, false);
            be.scanNow();
            h.assertTrue(be.isFormed() && be.cellCount() == 8192, "back to 2,048: " + problem(be));

            // a 25th block of height and a 65th of width are refused with the new limits in the message
            BlockPos high = f.min.offset(0, HIGH, 5);
            level.setBlock(high, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            be.scanNow();
            h.assertTrue(!be.isFormed() && problem(be).contains("8 to 24 high") && problem(be).contains("5 to 64 blocks wide"), "25 high: " + problem(be));
            level.setBlock(high, Blocks.AIR.defaultBlockState(), 2);
            BlockPos wide = f.min.offset(-1, 0, 5);
            level.setBlock(wide, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            be.scanNow();
            h.assertTrue(!be.isFormed() && problem(be).contains("5 to 64 blocks wide"), "65 wide: " + problem(be));
            level.setBlock(wide, Blocks.AIR.defaultBlockState(), 2);
            be.scanNow();
            h.assertTrue(be.isFormed(), "the largest pool again: " + problem(be));

            // while a block that is not a pool block stands in the way, the retry looks at that block and does not survey
            BlockPos junk = f.min.offset(31, 12, 31);
            level.setBlock(junk, Blocks.STONE.defaultBlockState(), 2);
            be.scanNow();
            h.assertTrue(!be.isFormed() && problem(be).contains("Clear the pool interior"), "stone in the pool: " + problem(be));
            int surveys = be.surveyCount();
            long now = level.getGameTime() + 1_000;
            for (int i = 0; i < 5; i++) {
                be.tickForTest(now + 200L * i);
            }
            h.assertTrue(be.surveyCount() == surveys && !be.isFormed(), "retries surveyed again with the stone still there: " + (be.surveyCount() - surveys));
            level.setBlock(junk, Blocks.AIR.defaultBlockState(), 2);
            be.tickForTest(now + 2_000);
            h.assertTrue(be.surveyCount() == surveys + 1 && be.isFormed(), "the retry after the stone went: " + (be.surveyCount() - surveys) + " surveys, " + problem(be));
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // A full pool: fuel, cooling, cost, packets, save, dose
    // ------------------------------------------------------------------------------------------------------------

    /** Ages, seconds, the assemblies are given in turn: fresh enough to glow, old enough to be most of a real pool. */
    private static final double[] AGES = {3_600.0, 86_400.0, 30 * 86_400.0, 365.25 * 86_400.0, 5 * 365.25 * 86_400.0};

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "poolscale", timeoutTicks = 400)
    public static void aFull2048RackPoolTakesFuelCoolsAndStaysCheap(GameTestHelper h) throws java.io.IOException {
        ServerLevel level = h.getLevel();
        SpentFuelPoolFixture f = SpentFuelPoolFixture.buildFull(level, FULL, SIDE, SIDE, HIGH, RACKS);
        try {
            SpentFuelPoolBlockEntity be = f.be;
            h.assertTrue(be.isFormed() && be.rackCount() == RACKS, "the pool did not form: " + problem(be));
            be.pool().restore(0.9 * be.pool().capacityKg(), 30.0);

            // fill every cell through the item handler, as a hopper would
            var handler = be.itemHandler();
            int cells = handler.getSlots();
            long t0 = System.nanoTime();
            int stored = 0;
            for (int slot = 0; slot < cells; slot++) {
                ItemStack one = SpentFuelPoolFixture.assembly(45_000.0, AGES[slot % AGES.length], T0);
                if (handler.insertItem(slot, one, false).isEmpty()) {
                    stored++;
                }
            }
            double fillUs = (System.nanoTime() - t0) / 1000.0 / cells;
            h.assertTrue(stored == 8192 && be.assemblyCount() == 8192 && be.freeCells() == 0 && be.firstFreeCell() == null,
                    "stored " + stored + ", count " + be.assemblyCount());
            ItemStack spare = SpentFuelPoolFixture.assembly(45_000.0, 3_600.0, T0);
            h.assertTrue(handler.insertItem(cells - 1, spare, false).getCount() == 1 && !be.insertAnywhere(spare), "a full pool took another assembly");
            ItemStack last = handler.extractItem(cells - 1, 1, false);
            h.assertTrue(!last.isEmpty() && be.assemblyCount() == 8191 && be.firstFreeCell() != null, "the last cell gave nothing back");
            h.assertTrue(handler.insertItem(cells - 1, last, false).isEmpty() && be.assemblyCount() == 8192, "the last cell did not take it back");

            // the cooling train on a full pool
            IFluidHandler coldIn = f.level.getCapability(Capabilities.FluidHandler.BLOCK, f.coolingIn, Direction.NORTH);
            IFluidHandler hotOut = f.level.getCapability(Capabilities.FluidHandler.BLOCK, f.coolingOut, Direction.NORTH);
            be.setCoolingEnabled(true);
            Runnable plumbing = () -> {
                be.energy().receiveEnergy(20_000, false);
                coldIn.fill(ThermalWater.atTemperature(400, 13.0), IFluidHandler.FluidAction.EXECUTE);
                hotOut.drain(100_000, IFluidHandler.FluidAction.EXECUTE);
            };

            // warm up (the JIT), then time every tick of 100 seconds of a full pool
            long now = T0;
            for (int i = 0; i < 400; i++, now++) {
                if (i % 20 == 0) {
                    plumbing.run();
                }
                be.tickForTest(now);
            }
            int ticks = 2_000;
            long[] cost = new long[ticks];
            List<Long> stepCost = new ArrayList<>();
            boolean cooled = false;
            for (int i = 0; i < ticks; i++, now++) {
                if (i % 20 == 0) {
                    plumbing.run();
                }
                long s = System.nanoTime();
                be.tickForTest(now);
                cost[i] = System.nanoTime() - s;
                if (be.lastStepAt() == now) {
                    stepCost.add(cost[i]);
                    cooled |= be.coolingRunning() && be.pool().coolingW() > 0.0;
                }
            }
            long[] sorted = cost.clone();
            Arrays.sort(sorted);
            double mean = ms(Arrays.stream(cost).sum()) / ticks;
            double p99 = ms(sorted[(int) (ticks * 0.99)]);
            long[] steps = stepCost.stream().mapToLong(Long::longValue).sorted().toArray();
            double stepMedian = ms(steps[steps.length / 2]);
            double other = (Arrays.stream(cost).sum() - stepCost.stream().mapToLong(Long::longValue).sum()) / 1.0e6 / (ticks - steps.length);
            System.out.println(String.format(Locale.ROOT,
                    "POOL SCALE: full 2048-rack pool (8192 assemblies, cooling on): tick mean %.3f ms, p99 %.3f ms, max %.3f ms; step tick median %.3f ms, other ticks mean %.3f ms (%d steps); item handler insert %.1f us",
                    mean, p99, ms(sorted[ticks - 1]), stepMedian, other, steps.length, fillUs));
            h.assertTrue(steps.length == ticks / 20, "steps " + steps.length);
            h.assertTrue(cooled, "the cooling train never ran on the full pool");
            h.assertTrue(mean < 0.5, "a full pool costs " + mean + " ms a tick on average");
            h.assertTrue(stepMedian < 3.0, "the step of a full pool costs " + stepMedian + " ms");
            h.assertTrue(other < 0.4, "a tick between steps costs " + other + " ms");

            // the physics is unchanged: the step's decay heat is the plain formula at the step's time, cell by cell and summed in order
            long at = be.lastStepAt();
            double sum = 0.0;
            int exact = 0;
            for (int r = 0; r < be.rackCount(); r++) {
                BlockPos p = be.rackPos(r);
                for (int k = 0; k < SpentFuelPool.ASSEMBLIES_PER_RACK; k++) {
                    ItemStack stack = be.stackAt(p, k);
                    if (!stack.isEmpty()) {
                        double w = SpentFuelHeat.decayWatts(stack, at);
                        sum += w;
                        if (Double.doubleToLongBits(be.slotAt(p, k).decayW) == Double.doubleToLongBits(w)) {
                            exact++;
                        }
                    }
                }
            }
            h.assertTrue(exact == 8192, "cells whose decay heat is not the plain formula's: " + (8192 - exact));
            h.assertTrue(be.view().decayW() == sum, "pool decay heat " + be.view().decayW() + " W against the plain sum " + sum + " W");

            // what goes to a client, a panel and the bridge console
            int tagBytes = be.updateTagBytes(level.registryAccess());
            var viewer = NewFuelElevatorTests.player(level, "pool-scale", Vec3.atCenterOf(f.controller.above(HIGH)));
            var menu = new SpentFuelPoolMenu(1, viewer.getInventory(), f.controller);
            int firstPage = menu.encodeSnapshot().length;
            menu.handleCommand(viewer, SpentFuelPoolMenu.CMD_RACK_PAGE, RACKS - 1, 0);
            byte[] lastPageBytes = menu.encodeSnapshot();
            var reader = new SpentFuelPoolMenu(2, viewer.getInventory(), f.controller);
            reader.acceptSnapshot(lastPageBytes);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            int bridgeBytes;
            try {
                RefuellingMenu.writePoolCells(buf, be);
                bridgeBytes = buf.readableBytes();
            } finally {
                buf.release();
            }
            int oldRackLines = RACKS * (8 + 8 + 1 + 8 + 8 + 1 + 1 + 1);
            System.out.println(String.format(Locale.ROOT,
                    "POOL SCALE: client picture %d bytes (positions and bytes would be %d); panel snapshot %d bytes (one line a rack would be about %d); bridge console pool cells %d bytes",
                    tagBytes, RACKS * 8 + RACKS * 4, Math.max(firstPage, lastPageBytes.length), oldRackLines, bridgeBytes));
            h.assertTrue(tagBytes < 8_192, "the client picture of a full pool is " + tagBytes + " bytes");
            h.assertTrue(firstPage < 4_096 && lastPageBytes.length < 4_096, "the panel snapshot is " + firstPage + " / " + lastPageBytes.length + " bytes");
            h.assertTrue(bridgeBytes < 24_576 && bridgeBytes < MenuSyncPayload.MAX_BYTES / 2, "the bridge's pool cells are " + bridgeBytes + " bytes");
            h.assertTrue(reader.rackCount == RACKS && reader.rackFirst == RACKS - SpentFuelPoolMenu.PAGE_RACKS && reader.racks.size() == SpentFuelPoolMenu.PAGE_RACKS
                    && reader.racks.get(SpentFuelPoolMenu.PAGE_RACKS - 1).pos().equals(be.rackPos(RACKS - 1)),
                    "the last page: first " + reader.rackFirst + ", " + reader.racks.size() + " lines of " + reader.rackCount);

            // the client reads the picture back: every rack where it is, every cell's glow
            be.handleUpdateTag(be.getUpdateTag(level.registryAccess()), level.registryAccess());
            h.assertTrue(be.visRacks.length == RACKS && be.visGlow.length == 8192, "client picture: " + be.visRacks.length + " racks, " + be.visGlow.length + " cells");
            java.util.Set<Long> wanted = new java.util.HashSet<>();
            for (BlockPos p : be.layout().racks()) {
                wanted.add(p.asLong());
            }
            int glowing = 0;
            for (int r = 0; r < RACKS; r++) {
                h.assertTrue(wanted.remove(be.visRacks[r]), "client picture has a rack at " + BlockPos.of(be.visRacks[r]) + " that is not one");
                BlockPos p = BlockPos.of(be.visRacks[r]);
                for (int k = 0; k < 4; k++) {
                    var slot = be.slotAt(p, k);
                    double fraction = slot.decayW / dev.bwr.core.thermal.AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W;
                    int expected = (int) Math.round(dev.bwr.mod.reactor.CherenkovAppearance.strength(fraction, true, slot.covered, slot.covered ? 12.0 : 0.0) * 15.0);
                    h.assertTrue(be.visGlow[4 * r + k] == expected, "glow of " + p + " cell " + k + ": " + be.visGlow[4 * r + k] + " against " + expected);
                    glowing += expected > 0 ? 1 : 0;
                }
            }
            h.assertTrue(glowing > 0, "nothing glows in a pool of fresh fuel");

            // a save and a load as after a restart: the same format as ever
            t0 = System.nanoTime();
            var tag = be.saveWithFullMetadata(level.registryAccess());
            long coldSave = System.nanoTime() - t0;
            t0 = System.nanoTime();
            tag = be.saveWithFullMetadata(level.registryAccess());
            long warmSave = System.nanoTime() - t0;
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            System.out.println(String.format(Locale.ROOT, "POOL SCALE: saving the full pool: first %.1f ms, again %.1f ms, %d bytes compressed",
                    ms(coldSave), ms(warmSave), bytes.size()));
            h.assertTrue(ms(warmSave) < 50.0, "saving a full pool took " + ms(warmSave) + " ms");
            level.removeBlockEntity(f.controller);
            var restored = (SpentFuelPoolBlockEntity) BlockEntity.loadStatic(f.controller, level.getBlockState(f.controller), tag, level.registryAccess());
            h.assertTrue(restored != null, "load");
            level.setBlockEntity(restored);
            f.be = restored;
            restored.scanNow();
            h.assertTrue(restored.isFormed() && restored.assemblyCount() == 8192 && Math.abs(restored.pool().massKg() - be.pool().massKg()) < 1.0,
                    "reloaded: " + restored.assemblyCount() + " assemblies, " + restored.pool().massKg() + " kg, " + problem(restored));
            restored.simulateSeconds(1, now);
            double reloaded = 0.0;
            for (int r = 0; r < restored.rackCount(); r++) {
                for (int k = 0; k < 4; k++) {
                    ItemStack stack = restored.stackAt(restored.rackPos(r), k);
                    reloaded += stack.isEmpty() ? 0.0 : SpentFuelHeat.decayWatts(stack, now);
                }
            }
            h.assertTrue(restored.view().decayW() == reloaded, "decay heat after the reload " + restored.view().decayW() + " against " + reloaded);

            doseAtScale(h, f, restored, now);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    /** Dose readings over a full 2,048-rack pool: close to the rack-by-rack sum, and cheap. */
    private static void doseAtScale(GameTestHelper h, SpentFuelPoolFixture f, SpentFuelPoolBlockEntity be, long now) {
        ServerLevel level = f.level;
        // 3 m of water over the racks, as a pool is run
        be.pool().restore(62.0 * 62.0 * 7.0 * 1000.0, 30.0);
        be.simulateSeconds(1, now + 20);
        double surface = be.surfaceY();
        double rackTop = f.min.getY() + 1 + SpentFuelPoolLayout.RACK_HEIGHT;
        int[] fuelled = {0};
        be.forEachRackSource((x, topY, z, decayW, cover) -> fuelled[0]++);
        h.assertTrue(fuelled[0] == RACKS && fuelled[0] > SpentFuelPoolBlockEntity.EXACT_DOSE_SOURCES, "fixture: " + fuelled[0] + " racks with fuel");
        double cx = f.min.getX() + SIDE / 2.0;
        double cz = f.min.getZ() + SIDE / 2.0;
        Vec3 deck = new Vec3(cx, surface + 1.5, cz);
        Vec3 edge = new Vec3(f.min.getX() + 3.5, surface + 1.5, f.min.getZ() + SIDE - 3.5);
        Vec3 beside = new Vec3(f.min.getX() - 3.0, rackTop + 0.5, cz);
        Vec3 above = new Vec3(cx + 7.0, surface + 12.0, cz - 9.0);
        Vec3 far = new Vec3(f.min.getX() - 70.0, surface + 20.0, cz + 40.0);
        Vec3 swimmer = new Vec3(cx + 0.3, surface - 0.5, f.min.getZ() + SIDE - 6.7);
        // accuracy: the lumped field against every rack on its own (nothing around the pool to shield, its own walls not counted)
        for (Vec3 at : new Vec3[] {deck, edge, beside, above, far, swimmer}) {
            boolean swimming = be.inWater(at.x, at.y, at.z);
            double[] exact = {0.0};
            be.forEachRackSource((x, topY, z, decayW, cover) -> {
                double d = at.distanceTo(new Vec3(x, topY, z));
                double w = swimming ? DoseModel.waterPathM(d, topY, at.y, surface, Double.NEGATIVE_INFINITY) : cover;
                exact[0] += DoseModel.pointSourceMsvPerH(BridgeMotion.gammaWatts(decayW), d, BridgeMotion.transmission(w));
            });
            int[] sources = {0};
            be.forEachDoseSource(at.x, at.y, at.z, (x, topY, z, decayW, cover) -> sources[0]++);
            double got = RadiationSources.doseRateMsvPerHour(level, at);
            System.out.println(String.format(Locale.ROOT, "POOL SCALE: dose at %s: %.4g mSv/h from %d sources, rack by rack %.4g (%.2f %%)",
                    at, got, sources[0], exact[0], (got / exact[0] - 1.0) * 100.0));
            h.assertTrue(exact[0] > 0.0 && Math.abs(got / exact[0] - 1.0) < 0.05, "dose at " + at + ": " + got + " against rack by rack " + exact[0]);
            h.assertTrue(sources[0] < 700, "a reading at " + at + " used " + sources[0] + " sources");
        }
        // cost: forty points about the hall, cold (no cached rays) and warm
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            switch (i % 4) {
                case 0 -> points.add(new Vec3(f.min.getX() + 2.5 + i * 1.4, surface + 1.5, f.min.getZ() + 2.5 + i * 1.3)); // the deck
                case 1 -> points.add(new Vec3(f.min.getX() + 3.5 + i * 1.3, surface - 0.5, cz)); // swimming
                case 2 -> points.add(new Vec3(f.min.getX() - 8.5, rackTop + (i % 8), f.min.getZ() + i * 1.5)); // beside the pool
                default -> points.add(new Vec3(cx, surface + 16.5, f.min.getZ() + i * 1.5)); // the floor above
            }
        }
        BlockShielding.clearAll();
        long t0 = System.nanoTime();
        double sum = 0.0;
        for (Vec3 p : points) {
            sum += RadiationSources.doseRateMsvPerHour(level, p);
        }
        double coldUs = (System.nanoTime() - t0) / 1000.0 / points.size();
        int rounds = 10;
        t0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            for (Vec3 p : points) {
                sum += RadiationSources.doseRateMsvPerHour(level, p);
            }
        }
        double warmUs = (System.nanoTime() - t0) / 1000.0 / (points.size() * rounds);
        System.out.println(String.format(Locale.ROOT, "POOL SCALE: dose sample over a full 2048-rack pool: cold %.1f us, warm %.1f us (sum %.4g)", coldUs, warmUs, sum));
        h.assertTrue(sum > 0.0, "no dose from a full pool");
        h.assertTrue(coldUs < 20_000.0, "a cold dose sample over a full pool took " + coldUs + " us");
        h.assertTrue(warmUs < 2_000.0, "a warm dose sample over a full pool took " + warmUs + " us");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chunks
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The largest pool across five chunk columns with its far column unloaded: the survey says it is waiting for its chunks,
     * reads nothing in the missing chunk and loads none (the five-second retries neither), and the pool forms again when the
     * chunks are back.
     */
    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "poolscale", timeoutTicks = 1200)
    public static void theSurveyOfTheLargestPoolNeverLoadsAChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SpentFuelPoolFixture f = SpentFuelPoolFixture.buildFull(level, CHUNKS, SIDE, SIDE, SpentFuelPoolLayout.MIN_OUTSIDE_HEIGHT, 3 * (SIDE - 2));
        ChunkLab lab = new ChunkLab(level);
        int controllerX = ChunkLab.cx(f.controller);
        int controllerZ = ChunkLab.cz(f.controller);
        int farX = (CHUNKS.getX() >> 4) + 3;
        int[] surveys = new int[1];
        Script script = new Script(h, 1200, () -> {
        }, () -> {
            try {
                f.forceChunks();
                f.dispose();
            } finally {
                lab.close();
            }
        });
        script.then(1, () -> {
            h.assertTrue(f.be.isFormed() && f.be.rackCount() == 3 * (SIDE - 2), "the pool did not form: " + problem(f.be));
            h.assertTrue(controllerX == CHUNKS.getX() >> 4, "fixture: the controller is in the west column");
            // only the controller's chunk stays wanted: the chunks three columns east of it unload
            lab.force(controllerX, controllerZ);
            f.releaseChunks();
        });
        script.awaitChunks(() -> "the east column of the pool to unload: " + lab.describe(farX, controllerZ), 600, () -> !lab.loaded(farX, controllerZ));
        script.then(1, () -> {
            lab.syncLoads.clear();
            surveys[0] = f.be.surveyCount();
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f.be).contains("waiting for its chunks"), "with a column gone: " + problem(f.be));
            h.assertTrue(lab.syncLoads.isEmpty(), "the survey loaded chunks: " + lab.syncLoads);
            h.assertTrue(!lab.loaded(farX, controllerZ), "the east column is loaded again after the survey");
        });
        script.then(130, () -> {
            h.assertTrue(f.be.surveyCount() > surveys[0] + 1, "the unformed pool did not retry: " + (f.be.surveyCount() - surveys[0]) + " surveys");
            h.assertTrue(!f.be.isFormed() && lab.syncLoads.isEmpty() && !lab.loaded(farX, controllerZ),
                    "the retries loaded a chunk: " + lab.syncLoads + ", east column loaded " + lab.loaded(farX, controllerZ));
            f.forceChunks();
        });
        script.awaitChunks(() -> "the pool's chunks to come back", 300, () -> lab.loaded(farX, controllerZ));
        script.then(1, () -> {
            f.be.scanNow();
            h.assertTrue(f.be.isFormed() && f.be.rackCount() == 3 * (SIDE - 2), "the pool did not form again: " + problem(f.be));
        });
        script.start();
    }
}
