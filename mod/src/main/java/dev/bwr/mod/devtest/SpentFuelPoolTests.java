package dev.bwr.mod.devtest;

import dev.bwr.core.pool.SpentFuelPool;
import dev.bwr.core.pool.SpentFuelPoolCooler;
import dev.bwr.core.thermal.AssemblyDecayHeat;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock;
import dev.bwr.mod.spentfuel.SpentFuelRackBlock;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The spent fuel pool through its real blocks and block entity: forming and refusing a bad structure, the heat balance
 * (cooling holds the level and the temperature, no cooling heats to boiling and the level falls at the rate the energy
 * balance gives), uncovered fuel heating and burning, the discharge time stamped when an assembly leaves a reactor,
 * the reprocessing plant refusing hot fuel, the item and water connections, and a save and reload. Its own namespace
 * ({@code bwrpool}) so {@code -PbwrOnlyPool} runs just these.
 *
 * <p>Every test is synchronous (it surveys, fills and steps the pool itself, with a virtual game time where it needs
 * hours to pass, through {@link SpentFuelPoolBlockEntity#simulateSeconds}) except the one that waits for a real
 * assembly to cool, and builds at its own origin.
 */
@GameTestHolder("bwrpool")
@PrefixGameTestTemplate(false)
public final class SpentFuelPoolTests {

    private SpentFuelPoolTests() {
    }

    /** The virtual "now" of the long simulations, ticks; the stamps are made relative to it. */
    private static final long T0 = 50_000_000L;
    private static final double BURNUP = 45000.0;

    private static final BlockPos FORM = new BlockPos(60000, 190, 60000);
    private static final BlockPos BALANCE = new BlockPos(60040, 190, 60000);
    private static final BlockPos UNCOVER = new BlockPos(60080, 190, 60000);
    private static final BlockPos CONNECT = new BlockPos(60120, 190, 60000);
    private static final BlockPos RELOAD = new BlockPos(60160, 190, 60000);
    private static final BlockPos REACTOR = new BlockPos(60200, 190, 60000);
    private static final BlockPos PLANT = new BlockPos(60400, 210, 60000);

    private static SpentFuelPoolFixture pool(GameTestHelper h, BlockPos at, int racks) {
        return SpentFuelPoolFixture.build(h.getLevel(), at, 9, 9, 8, racks);
    }

    private static IFluidHandler port(SpentFuelPoolFixture f, BlockPos at) {
        return f.level.getCapability(Capabilities.FluidHandler.BLOCK, at, Direction.NORTH);
    }

    private static void addWater(SpentFuelPoolFixture f, int kg, double celsius) {
        IFluidHandler makeup = port(f, f.makeup);
        int accepted = makeup.fill(ThermalWater.atTemperature(kg, celsius), IFluidHandler.FluidAction.EXECUTE);
        if (accepted != kg) {
            throw new AssertionError("the makeup port took " + accepted + " of " + kg + " kg");
        }
    }

    private static String problem(SpentFuelPoolFixture f) {
        return f.be.problem() == null ? "" : BwrText.english(f.be.problem());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 200)
    public static void aLinedBoxWithRacksFormsAndABadOneSaysWhatIsWrong(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, FORM, 6);
        try {
            h.assertTrue(f.be.isFormed(), "the pool did not form: " + problem(f));
            h.assertTrue(f.be.layout().footprintCells() == 49 && f.be.layout().heightCells() == 7, "interior " + f.be.layout());
            h.assertTrue(f.be.pool().capacityKg() == 343_000.0, "capacity " + f.be.pool().capacityKg());
            h.assertTrue(f.be.layout().rackTopM(f.racks.get(0)) == 4.0, "a rack is 4 m tall");
            h.assertTrue(f.be.cellCount() == 24, "cells " + f.be.cellCount());
            h.assertTrue(f.be.pool().massKg() == 0.0, "a new pool starts empty: " + f.be.pool().massKg());

            // junk in the interior
            BlockPos junk = FORM.offset(4, 3, 4);
            f.level.setBlock(junk, Blocks.STONE.defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("Clear the pool interior"), "stone in the pool: " + problem(f));
            f.level.setBlock(junk, Blocks.AIR.defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed(), "the pool did not re-form: " + problem(f));

            // a hole in the wall
            BlockPos wall = FORM.offset(8, 3, 4);
            f.level.setBlock(wall, Blocks.AIR.defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("Complete the floor"), "hole in the wall: " + problem(f));
            f.level.setBlock(wall, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed(), "the repaired pool did not form: " + problem(f));

            // a floating rack
            BlockPos floating = FORM.offset(3, 4, 3);
            f.level.setBlock(floating, BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("exactly 4 blocks"), "floating rack: " + problem(f));
            f.level.setBlock(floating, Blocks.AIR.defaultBlockState(), 2);
            // a rack on a rack is refused with the reason (a fifth block on a column), and so is a column that is too short
            BlockPos fifth = FORM.offset(1, 5, 7);
            f.level.setBlock(fifth, BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("never on another rack"), "five-block column: " + problem(f));
            f.level.setBlock(fifth, Blocks.AIR.defaultBlockState(), 2);
            BlockPos topBlock = FORM.offset(1, 4, 7);
            f.level.setBlock(topBlock, Blocks.AIR.defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("exactly 4 blocks"), "three-block column: " + problem(f));
            f.level.setBlock(topBlock, BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed() && f.be.cellCount() == 24, "the repaired rack: " + problem(f));

            // a port that faces into the pool
            BlockPos badPort = FORM.offset(2, 3, 8);
            f.level.setBlock(badPort, BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT.get().defaultBlockState()
                    .setValue(SpentFuelPoolPortBlock.FACING, Direction.NORTH), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("Ports go in a side wall"), "port facing in: " + problem(f));
            f.level.setBlock(badPort, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);

            // two controllers
            BlockPos second = FORM.offset(8, 2, 3);
            f.level.setBlock(second, BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && problem(f).contains("exactly one"), "two controllers: " + problem(f));
            f.level.setBlock(second, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed(), "the pool did not form again: " + problem(f));
        } finally {
            f.dispose();
        }
        // too small to be a pool
        // too shallow for the 4 m racks and the 3 m of shielding water over them: 7 high outside is one short, 8 is enough
        SpentFuelPoolFixture shallow = SpentFuelPoolFixture.build(h.getLevel(), FORM, 9, 9, 7, 0);
        try {
            h.assertTrue(!shallow.be.isFormed() && problem(shallow).contains("8 to 24 high"), "a 7-high box formed: " + problem(shallow));
        } finally {
            shallow.dispose();
        }
        SpentFuelPoolFixture deep = SpentFuelPoolFixture.build(h.getLevel(), FORM, 9, 9, 8, 0);
        try {
            h.assertTrue(deep.be.isFormed() && deep.be.layout().heightCells() == SpentFuelPoolLayout.MIN_INTERIOR_DEPTH, "an 8-high box must form: " + problem(deep));
        } finally {
            deep.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Heat balance
    // ------------------------------------------------------------------------------------------------------------

    /** The passive loss of the 7 x 7 pool at 100 C and this level, as the core model has it, W. */
    private static double passiveAt100(double levelM) {
        double wetted = 49.0 + 4.0 * 7.0 * levelM;
        return (SpentFuelPool.SURFACE_W_PER_M2_K * 49.0 + SpentFuelPool.WALL_W_PER_M2_K * wetted) * (100.0 - SpentFuelPool.AMBIENT_C);
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void withoutCoolingTheWaterBoilsAndTheLevelFallsAtTheEnergyBalanceRate(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, BALANCE, 14);
        try {
            addWater(f, 343_000, 25.0);
            h.assertTrue(f.be.pool().levelM() == 7.0, "a full pool is 7 m deep: " + f.be.pool().levelM());
            int stored = f.fill(56, BURNUP, 6 * 3600, T0);
            h.assertTrue(stored == 56, "stored " + stored);
            // start at the boil so that the rate is the whole balance: 56 assemblies, 6 hours old
            f.be.pool().restore(343_000, 100.0);
            double massBefore = f.be.pool().massKg();
            int seconds = 3600;
            // the same balance, worked out here one second at a time from the decay curve
            double expectedKg = 0.0;
            double mass = massBefore;
            for (int i = 0; i < seconds; i++) {
                double decay = 56.0 * AssemblyDecayHeat.powerWatts(BURNUP, 180.0, 6 * 3600 + i);
                double level = mass / 49_000.0;
                double boil = Math.max(0.0, decay - passiveAt100(level)) / (SpentFuelPool.LATENT_BOIL_KJ_PER_KG * 1000.0);
                expectedKg += boil;
                mass -= boil;
            }
            f.be.simulateSeconds(seconds, T0);
            double lost = massBefore - f.be.pool().massKg();
            h.assertTrue(lost > 1000.0, "the pool did not boil off: lost " + lost + " kg");
            h.assertTrue(Math.abs(lost - expectedKg) < 0.002 * expectedKg,
                    "boil-off in an hour: " + lost + " kg, the energy balance gives " + expectedKg + " kg");
            h.assertTrue(f.be.pool().temperatureC() == 100.0, "a boiling pool stays at 100 C: " + f.be.pool().temperatureC());
            var v = f.be.view();
            h.assertTrue(Math.abs(v.levelM() - f.be.pool().massKg() / 49_000.0) < 1e-9, "level");
            h.assertTrue(v.secondsToBoil() == 0.0 && v.secondsToUncover() > 3600.0 && !Double.isInfinite(v.secondsToUncover()),
                    "time to boil " + v.secondsToBoil() + ", to uncover " + v.secondsToUncover());
            h.assertTrue(!v.coolingEnabled() && !v.coolingRunning() && v.coolingW() == 0.0, "the pool must not start cooling by itself");
            h.assertTrue(v.waterAboveFuelM() > 2.9, "the fuel is still well covered after an hour: " + v.waterAboveFuelM());
            System.out.println("SPENT FUEL POOL: 56 assemblies, 6 h old, boiling: " + String.format("%.0f", lost) + " kg lost in an hour (balance "
                    + String.format("%.0f", expectedKg) + " kg), time to uncover " + String.format("%.1f", v.secondsToUncover() / 3600.0) + " h");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void aCoolingLoopHoldsTheTemperatureAndTheLevelWhereNothingDoesNot(GameTestHelper h) {
        double[] cooled = hourAtLoad(h, BALANCE.offset(0, 0, 40), true);
        double[] bare = hourAtLoad(h, BALANCE.offset(0, 0, 80), false);
        // [temperature C, mass kg, heat removed MJ, pump seconds]
        h.assertTrue(cooled[0] < 25.0, "a 6 MW loop on a 2 MW pool must not leave it heating: " + cooled[0] + " C");
        h.assertTrue(bare[0] > cooled[0] + 5.0, "without the loop the pool is much hotter: " + bare[0] + " against " + cooled[0]);
        h.assertTrue(bare[0] > 28.0, "two megawatts for an hour in 343 tonnes is about 5 K: " + bare[0]);
        h.assertTrue(bare[2] == 0.0 && bare[3] == 0.0, "nothing removed heat and the pump never ran by itself: " + bare[2] + " MJ, " + bare[3] + " s");
        h.assertTrue(cooled[2] > 5000.0 && cooled[3] == 3600.0, "the loop removed " + cooled[2] + " MJ over " + cooled[3] + " s");
        h.assertTrue(cooled[1] > 342_000.0 && bare[1] > 342_000.0 && cooled[1] <= 343_000.0, "level held: " + cooled[1] + " / " + bare[1] + " kg");
        System.out.println("SPENT FUEL POOL: an hour at about 2 MW: with the cooling loop " + String.format("%.1f", cooled[0]) + " C ("
                + String.format("%.0f", cooled[2]) + " MJ removed), without " + String.format("%.1f", bare[0]) + " C");
        h.succeed();
    }

    /** An hour of a 56-assembly pool at 25 C; the cooling pump switched on by the "player" or not. */
    private static double[] hourAtLoad(GameTestHelper h, BlockPos at, boolean cooling) {
        SpentFuelPoolFixture f = pool(h, at, 14);
        try {
            addWater(f, 343_000, 25.0);
            f.fill(56, BURNUP, 3 * 3600, T0);
            IFluidHandler coldIn = port(f, f.coolingIn);
            IFluidHandler hotOut = port(f, f.coolingOut);
            if (cooling) {
                f.be.setCoolingEnabled(true);
            }
            double removedMJ = 0.0;
            double pumpSeconds = 0.0;
            for (int i = 0; i < 3600; i++) {
                if (cooling) {
                    // the player's plumbing: a supply of 13 C water at the blue port, and a drain at the cyan one
                    f.be.energy().receiveEnergy(20_000, false);
                    coldIn.fill(ThermalWater.atTemperature(400, 13.0), IFluidHandler.FluidAction.EXECUTE);
                    hotOut.drain(100_000, IFluidHandler.FluidAction.EXECUTE);
                }
                f.be.simulateSeconds(1, T0 + (long) i * 20);
                removedMJ += f.be.pool().coolingW() / 1.0e6;
                if (f.be.coolingRunning()) {
                    pumpSeconds += 1.0;
                }
            }
            return new double[] {f.be.pool().temperatureC(), f.be.pool().massKg(), removedMJ, pumpSeconds};
        } finally {
            f.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Uncovered fuel
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void uncoveredFuelHeatsBurstsAndBurnsAndWaterQuenchesIt(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, UNCOVER, 8);
        try {
            addWater(f, 24_500, 25.0);   // half a metre: far under the 4 m racks
            BlockPos rack = f.racks.get(0);
            h.assertTrue(f.be.insertAt(rack, 0, SpentFuelPoolFixture.assembly(BURNUP, 6 * 3600, T0)), "old assembly");
            h.assertTrue(f.be.insertAt(rack, 1, SpentFuelPoolFixture.assembly(BURNUP, 20, T0)), "fresh assembly");
            h.assertTrue(f.be.insertAt(f.racks.get(1), 0, SpentFuelPoolFixture.assembly(BURNUP, 3 * 86_400, T0)), "cool assembly");
            f.be.simulateSeconds(1, T0);
            var v = f.be.view();
            h.assertTrue(v.uncoveredDecayW() > 100_000.0 && v.coveredDecayW() == 0.0, "all three are out of the water: " + v.uncoveredDecayW());
            h.assertTrue(v.waterAboveFuelM() < 0.0, "the water is below the highest fuel: " + v.waterAboveFuelM());
            h.assertTrue(v.secondsToUncover() == 0.0, "already uncovered: " + v.secondsToUncover());
            double old0 = f.be.slotAt(rack, 0).fuelC;
            f.be.simulateSeconds(120, T0 + 20);
            double oldAfter = f.be.slotAt(rack, 0).fuelC;
            double freshAfter = f.be.slotAt(rack, 1).fuelC;
            h.assertTrue(oldAfter > old0 + 15.0 && freshAfter > oldAfter, "uncovered fuel heats, the fresher faster: " + old0 + " -> " + oldAfter + " / " + freshAfter);
            f.be.simulateSeconds(1800, T0 + 20 * 121L);
            h.assertTrue(f.be.slotAt(rack, 1).damaged, "the fresh assembly's cladding failed");
            h.assertTrue(f.be.slotAt(rack, 1).fuelC > 700.0, "and it is very hot: " + f.be.slotAt(rack, 1).fuelC);
            h.assertTrue(!f.be.slotAt(rack, 0).damaged || f.be.slotAt(rack, 0).fuelC < 900.0, "six hours old does not ignite");
            h.assertTrue(f.be.hazard() > 0.0, "a failed assembly releases: " + f.be.hazard());
            h.assertTrue(f.be.view().damaged() >= 1, "damaged assemblies are counted");
            // the cool one stays far below the burst temperature
            h.assertTrue(f.be.slotAt(f.racks.get(1), 0).fuelC < 565.0 && !f.be.slotAt(f.racks.get(1), 0).damaged,
                    "three days old: " + f.be.slotAt(f.racks.get(1), 0).fuelC);
            System.out.println("SPENT FUEL POOL: uncovered, after 32 min: 20 s old " + String.format("%.0f", f.be.slotAt(rack, 1).fuelC)
                    + " C, 6 h old " + String.format("%.0f", f.be.slotAt(rack, 0).fuelC) + " C, 3 d old "
                    + String.format("%.0f", f.be.slotAt(f.racks.get(1), 0).fuelC) + " C, hazard " + String.format("%.2e", f.be.hazard()));
            // makeup water covers it again: quenched
            addWater(f, 230_000, 20.0);   // 5.2 m: over the 4 m racks
            f.be.simulateSeconds(2, T0 + 20 * 2000L);
            var back = f.be.view();
            h.assertTrue(back.uncoveredDecayW() == 0.0 && back.waterAboveFuelM() > 1.0, "covered again: " + back.waterAboveFuelM());
            h.assertTrue(f.be.slotAt(rack, 1).fuelC < 60.0 && !f.be.slotAt(rack, 1).ignited, "quenched: " + f.be.slotAt(rack, 1).fuelC);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void coveredFuelStaysAtTheWaterTemperature(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, UNCOVER.offset(0, 0, 40), 8);
        try {
            addWater(f, 245_000, 30.0);
            f.fill(8, BURNUP, 3600, T0);
            f.be.simulateSeconds(600, T0);
            var v = f.be.view();
            h.assertTrue(v.uncoveredDecayW() == 0.0 && v.coveredDecayW() > 100_000.0, "covered: " + v.coveredDecayW());
            for (BlockPos rack : f.racks) {
                for (int cell = 0; cell < 4; cell++) {
                    var slot = f.be.slotAt(rack, cell);
                    if (!f.be.stackAt(rack, cell).isEmpty()) {
                        h.assertTrue(slot.covered && Math.abs(slot.fuelC - f.be.pool().temperatureC()) < 1e-9 && !slot.damaged, "covered fuel at " + slot.fuelC);
                    }
                }
            }
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Discharge time
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void unloadingFromTheCoreRecordsTheDischargeTimeAndItSurvivesSavesAndThePool(GameTestHelper h) throws Exception {
        ServerLevel l = h.getLevel();
        var reactor = CompactCoreRegressionTests.build(l, REACTOR, 5, 5, false);
        h.assertTrue(reactor.isFormed(), "reactor fixture did not form");
        int slot = reactor.corePositions()[0];
        ItemStack loaded = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 21000.0, 1.0));
        h.assertTrue(SpentFuelHeat.dischargeTime(loaded) == -1L, "a new bundle carries no stamp");
        h.assertTrue(reactor.loadAssembly(slot, loaded), "load");
        long now = l.getGameTime();
        ItemStack out = reactor.unloadAssembly(slot);
        h.assertTrue(!out.isEmpty() && SpentFuelHeat.dischargeTime(out) == now, "stamp " + SpentFuelHeat.dischargeTime(out) + " at tick " + now);
        h.assertTrue(FuelAssemblies.dataOf(out).burnupMwdPerTonne() == FuelAssemblies.dataOf(loaded).burnupMwdPerTonne()
                || FuelAssemblies.dataOf(out).burnupMwdPerTonne() >= 21000.0, "burnup kept");
        // the item survives being written and read, and the clock keeps running while it is only an item
        ItemStack again = ItemStack.parse(l.registryAccess(), out.save(l.registryAccess())).orElseThrow();
        h.assertTrue(SpentFuelHeat.dischargeTime(again) == now, "stamp lost in the item NBT");
        double hot = SpentFuelHeat.decayWatts(again, now);
        double later = SpentFuelHeat.decayWatts(again, now + 3600L * 20);
        h.assertTrue(hot > 5.0 * later && later > 0.0, "decay heat must fall while it is an item: " + hot + " -> " + later);
        h.assertTrue(Math.abs(hot - AssemblyDecayHeat.powerWatts(21000.0, 180.0, 0.0)) < 1e-6 * hot, "heat at the instant of discharge");
        // an old assembly: no stamp, long-cooled
        ItemStack old = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0));
        h.assertTrue(SpentFuelHeat.coolingSeconds(old, now) == AssemblyDecayHeat.LONG_COOLED_SECONDS, "long-cooled");
        double oldWatts = SpentFuelHeat.decayWatts(old, now);
        h.assertTrue(oldWatts > 0.0 && oldWatts < 5_000.0 && !SpentFuelHeat.tooHotToReprocess(old, now), "an unstamped assembly is a few kW at most: " + oldWatts);
        // breaking the reactor also stamps what it hands back
        reactor.loadAssembly(slot, again);
        var drops = reactor.takeFuelForRemoval();
        h.assertTrue(drops.size() == 1 && SpentFuelHeat.dischargeTime(drops.get(0)) == now, "removal stamps too");
        h.assertTrue(FuelAssemblies.dataOf(drops.get(0)).equals(FuelAssemblies.dataOf(again)), "and leaves the bundle's own record exactly as it was");

        // into a pool and out again: the stamp is untouched, and the pool's heat follows it
        SpentFuelPoolFixture f = pool(h, RELOAD.offset(0, 0, 40), 2);
        try {
            addWater(f, 245_000, 25.0);
            h.assertTrue(f.be.insertAnywhere(drops.get(0)), "stored");
            h.assertTrue(f.be.assemblyCount() == 1, "one stored");
            f.be.simulateSeconds(1, now);
            h.assertTrue(Math.abs(f.be.view().decayW() - hot) < 1e-6 * hot, "the pool sees " + f.be.view().decayW() + " W, the item " + hot);
            f.be.simulateSeconds(1, now + 3600L * 20);
            h.assertTrue(Math.abs(f.be.view().decayW() - later) < 1e-6 * later, "an hour later " + f.be.view().decayW() + " W, the item " + later);
            ItemStack taken = f.be.extractFrom(f.racks.get(0), -1);
            h.assertTrue(SpentFuelHeat.dischargeTime(taken) == now && FuelAssemblies.dataOf(taken).equals(FuelAssemblies.dataOf(again)),
                    "the assembly came back out as it went in");
        } finally {
            f.dispose();
        }
        FixtureCleanup.drainAt(l, reactor.getBlockPos());
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reprocessing
    // ------------------------------------------------------------------------------------------------------------

    private static ReprocessingPlantBlockEntity plant(ServerLevel l, BlockPos p) {
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 2);
        return (ReprocessingPlantBlockEntity) l.getBlockEntity(p);
    }

    private static void tickPlant(ServerLevel l, BlockPos p, ReprocessingPlantBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void theReprocessingPlantRefusesHotFuelAndTakesCoolFuel(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = PLANT.offset(0, 0, 10);
        l.getChunk(p);
        ReprocessingPlantBlockEntity be = plant(l, p);
        long now = l.getGameTime();
        // a fresh discharge: hundreds of kW against a limit of 50
        ItemStack hot = SpentFuelPoolFixture.assembly(BURNUP, 0, now);
        h.assertTrue(Reprocessing.accepts(hot), "a spent assembly is still accepted into the slot");
        h.assertTrue(SpentFuelHeat.tooHotToReprocess(hot, now), "hot: " + SpentFuelHeat.decayWatts(hot, now));
        be.input().setStackInSlot(0, hot);
        for (int i = 0; i < 400; i++) {
            tickPlant(l, p, be);
        }
        h.assertTrue(!be.input().getStackInSlot(0).isEmpty() && be.workTicks() == 0, "the plant started on hot fuel: work " + be.workTicks());
        h.assertTrue(be.isTooHot() && be.inputDecayWatts() > 200_000.0, "decay heat " + be.inputDecayWatts());
        h.assertTrue(be.statusLines().stream().anyMatch(s -> s.contains("Too hot")), "the status says so: " + be.statusLines());
        h.assertTrue(be.plutoniumGrains() == 0 && be.uraniumGrains() == 0 && be.wasteGrains() == 0, "nothing was made");
        h.assertTrue(be.secondsUntilCool() > 1800.0, "about two hours to go: " + be.secondsUntilCool());
        // the same assembly with no discharge record is long-cooled and goes in at once
        be.input().setStackInSlot(0, FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, BURNUP, 1.0)));
        int ticks = 0;
        while (ticks < 400 && !be.input().getStackInSlot(0).isEmpty()) {
            tickPlant(l, p, be);
            ticks++;
        }
        h.assertTrue(ticks == Reprocessing.WORK_TICKS, "a long-cooled assembly took " + ticks + " ticks");
        be.removalDrops();
        l.removeBlock(p, false);
        h.succeed();
    }

    /** Burnup whose assembly takes about {@code seconds} to cool below the plant's limit, found by bisection on the core curve. */
    private static double burnupCoolingIn(double seconds) {
        double lo = 1.0e-6;
        double hi = 1.0;
        double limit = SpentFuelHeat.reprocessingLimitWatts();
        for (int i = 0; i < 80; i++) {
            double mid = 0.5 * (lo + hi);
            if (AssemblyDecayHeat.secondsToCoolBelow(mid, 180.0, limit) > seconds) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 700)
    public static void hotFuelWaitsInThePlantAndIsTakenApartOnceItHasCooled(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // inside the test's own structure, in a chunk the game is already ticking: no chunk is forced, so none can be left forced
        BlockPos p = h.absolutePos(new BlockPos(0, 2, 0));
        ReprocessingPlantBlockEntity be = plant(l, p);
        for (int i = 0; i < 12; i++) {
            be.energy().receiveEnergy(10_000, false);
        }
        long now = l.getGameTime();
        double burnup = burnupCoolingIn(3.0);
        ItemStack stack = SpentFuelPoolFixture.assembly(burnup, 0, now);
        double wait = SpentFuelHeat.secondsUntilReprocessable(stack, now);
        h.assertTrue(wait > 2.0 && wait < 4.0, "this assembly should need about 3 s to cool: " + wait);
        be.input().setStackInSlot(0, stack);
        // while it is hot it is left alone ...
        h.runAfterDelay(20, () -> {
            h.assertTrue(!be.input().getStackInSlot(0).isEmpty() && be.workTicks() == 0 && be.isTooHot(),
                    "after a second the plant had started on it: work " + be.workTicks() + ", heat " + be.inputDecayWatts());
        });
        // ... and once it has cooled the plant starts by itself and finishes it
        h.succeedWhen(() -> {
            h.assertTrue(be.input().getStackInSlot(0).isEmpty(), "still in the plant, work " + be.workTicks() + ", heat " + be.inputDecayWatts());
            be.removalDrops();
            l.removeBlock(p, false);
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Connections and blocks
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void racksAndPortsTakeOnlyWhatTheyShould(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, CONNECT, 3);
        try {
            var items = f.level.getCapability(Capabilities.ItemHandler.BLOCK, f.controller, Direction.EAST);
            h.assertTrue(items != null && items.getSlots() == 12, "item handler: " + (items == null ? "none" : items.getSlots()));
            ItemStack rod = new ItemStack(Items.IRON_INGOT);
            h.assertTrue(items.insertItem(0, rod, false) == rod, "an iron ingot was taken");
            ItemStack bundle = SpentFuelPoolFixture.assembly(BURNUP, 600, T0);
            ItemStack left = items.insertItem(5, bundle.copyWithCount(2), false);
            h.assertTrue(left.getCount() == 1 && !items.getStackInSlot(5).isEmpty() && items.getStackInSlot(5).getCount() == 1, "one per cell");
            h.assertTrue(items.insertItem(5, bundle, false) == bundle, "a taken cell refuses");
            h.assertTrue(items.extractItem(5, 1, true).getCount() == 1 && !items.getStackInSlot(5).isEmpty(), "simulated extraction changes nothing");
            ItemStack out = items.extractItem(5, 1, false);
            h.assertTrue(SpentFuelHeat.dischargeTime(out) == SpentFuelHeat.dischargeTime(bundle) && items.getStackInSlot(5).isEmpty(), "extraction");
            h.assertTrue(f.level.getBlockState(f.racks.get(1).above(3)).getValue(SpentFuelRackBlock.CELL[0]) == false, "the rack model shows an empty cell again");

            // the rack's own interaction: the cell under the cursor, else the first free cell; the coolest comes out
            // these two are stamped on the level's own clock, which is what the rack's extraction reads: one at tick 0,
            // one just now, so the first has cooled for as long as the level has run
            long real = f.level.getGameTime();
            ItemStack older = bundle.copy();
            SpentFuelHeat.stamp(older, 0L);
            h.assertTrue(f.be.insertAt(f.racks.get(0), 2, older.copy()), "insert into cell 2");
            h.assertTrue(f.level.getBlockState(f.racks.get(0).above(3)).getValue(SpentFuelRackBlock.CELL[2]), "the rack shows an assembly head in cell 2");
            h.assertTrue(!f.level.getBlockState(f.racks.get(0).above(3)).getValue(SpentFuelRackBlock.CELL[0]), "and not in cell 0");
            ItemStack younger = bundle.copy();
            SpentFuelHeat.stamp(younger, real);
            h.assertTrue(f.be.insertAt(f.racks.get(0), 2, younger), "a taken preferred cell falls back to a free one");
            h.assertTrue(f.be.stackAt(f.racks.get(0), 0).isEmpty() == false || f.be.stackAt(f.racks.get(0), 1).isEmpty() == false, "went to the first free cell");
            ItemStack coolest = f.be.extractFrom(f.racks.get(0), -1);
            h.assertTrue(real > 0 && SpentFuelHeat.dischargeTime(coolest) == 0L, "the assembly cooled longest comes out first: stamp " + SpentFuelHeat.dischargeTime(coolest));

            // water: makeup takes water, nothing else, and only through the front face
            IFluidHandler makeup = port(f, f.makeup);
            h.assertTrue(makeup.fill(new FluidStack(net.minecraft.world.level.material.Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE) == 0, "lava refused");
            h.assertTrue(makeup.fill(ThermalWater.atTemperature(1000, 20.0), IFluidHandler.FluidAction.SIMULATE) == 1000
                    && f.be.pool().massKg() == 0.0, "a simulated fill changes nothing");
            addWater(f, 100_000, 10.0);
            addWater(f, 100_000, 30.0);
            h.assertTrue(Math.abs(f.be.pool().temperatureC() - 20.0) < 1e-9, "mixed by energy: " + f.be.pool().temperatureC());
            h.assertTrue(makeup.fill(ThermalWater.atTemperature(200_000, 20.0), IFluidHandler.FluidAction.EXECUTE) == 143_000, "only to the rim");
            h.assertTrue(f.level.getCapability(Capabilities.FluidHandler.BLOCK, f.makeup, Direction.EAST) == null, "no flange on the side faces");
            h.assertTrue(makeup.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "the makeup port gives nothing back");
            IFluidHandler coldIn = port(f, f.coolingIn);
            h.assertTrue(coldIn.fill(ThermalWater.atTemperature(5000, 13.0), IFluidHandler.FluidAction.EXECUTE) == 5000
                    && Math.abs(f.be.cooler().coldKg() - 5000.0) < 1e-9, "cold water into the exchanger, not the pool");
            h.assertTrue(f.be.pool().massKg() == 343_000.0, "and not into the pool");
            IFluidHandler hotOut = port(f, f.coolingOut);
            h.assertTrue(hotOut.fill(ThermalWater.atTemperature(1000, 13.0), IFluidHandler.FluidAction.EXECUTE) == 0 && hotOut.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(),
                    "nothing in the cyan port, and nothing in the exchanger to drain yet");

            // only the controller ticks
            for (var block : List.of(BwrBlocks.SPENT_FUEL_POOL_WALL.get(), BwrBlocks.SPENT_FUEL_RACK.get(), BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT.get(),
                    BwrBlocks.SPENT_FUEL_POOL_COOLING_IN.get(), BwrBlocks.SPENT_FUEL_POOL_COOLING_OUT.get())) {
                h.assertTrue(!(block instanceof net.minecraft.world.level.block.EntityBlock), block + " must have no block entity, so no ticker");
            }
            h.assertTrue(f.level.getBlockEntity(f.makeup) == null && f.level.getBlockEntity(f.racks.get(0)) == null, "no block entities on the ports and racks");

            // breaking any block of a rack takes the rack out of the pool and hands back what was in it (the drop itself is
            // Containers.dropItemStack at the rack, which obeys doTileDrops; entities added to a fixture chunk that is not ticking
            // are hidden from every entity query, so the count is taken from what the pool returns)
            h.assertTrue(f.be.insertAt(f.racks.get(2), 0, SpentFuelPoolFixture.assembly(BURNUP, 600, T0)) && !f.be.stackAt(f.racks.get(2), 0).isEmpty(), "insert into the third rack");
            f.level.destroyBlock(f.racks.get(2).above(1), false);
            h.assertTrue(f.be.cellCount() == 8 && f.be.stackAt(f.racks.get(2), 0).isEmpty(), "mining a block of the rack took the rack and its assembly out of the pool: " + f.be.cellCount());
            h.assertTrue(f.be.insertAt(f.racks.get(1), 0, SpentFuelPoolFixture.assembly(BURNUP, 600, T0)) && f.be.insertAt(f.racks.get(1), 1, SpentFuelPoolFixture.assembly(BURNUP, 700, T0)), "two assemblies into the second rack");
            var fromRack = f.be.rackBroken(f.racks.get(1).above(2));
            h.assertTrue(fromRack.size() == 2 && fromRack.stream().allMatch(st -> st.is(BwrItems.FUEL_ASSEMBLY.get()) && SpentFuelHeat.dischargeTime(st) >= 0L),
                    "the rack handed back " + fromRack.size() + " stamped assemblies, two were in it");
            h.assertTrue(f.be.cellCount() == 4, "one rack left");
            // breaking the controller empties the pool of what is left (the assembly still in the first rack)
            f.level.destroyBlock(f.controller, false);
            h.assertTrue(f.be.assemblyCount() == 0, "the controller kept " + f.be.assemblyCount() + " assemblies when it was broken");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Save and reload
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void aPoolSavesAndReloadsWithItsWaterItsFuelAndItsPump(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, RELOAD, 6);
        try {
            // an empty, untouched controller writes none of the optional keys
            var plain = f.be.saveWithoutMetadata(f.level.registryAccess());
            for (String key : new String[] {"WaterKg", "WaterC", "Cooling", "Energy", "ColdKg", "HotKg", "Hazard", "Racks"}) {
                h.assertTrue(!plain.contains(key), "an empty pool saved " + key);
            }
            addWater(f, 300_000, 27.5);
            f.fill(9, BURNUP, 4 * 3600, T0);
            f.be.insertAt(f.racks.get(5), 3, SpentFuelPoolFixture.assembly(1234.0, 90, T0));
            f.be.setCoolingEnabled(true);
            f.be.energy().receiveEnergy(12_345, false);
            port(f, f.coolingIn).fill(ThermalWater.atTemperature(3000, 14.0), IFluidHandler.FluidAction.EXECUTE);
            f.be.simulateSeconds(30, T0);
            var before = f.be.view();
            var tag = f.be.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(tag.contains("Racks") && tag.contains("WaterKg") && tag.getBoolean("Cooling"), "keys written when there is something to say");

            // a fresh controller loaded from the tag, as after a restart
            f.level.removeBlockEntity(f.controller);
            var restored = (SpentFuelPoolBlockEntity) BlockEntity.loadStatic(f.controller, f.level.getBlockState(f.controller), tag, f.level.registryAccess());
            h.assertTrue(restored != null, "load");
            f.level.setBlockEntity(restored);
            h.assertTrue(!restored.isFormed(), "formed is surveyed, not saved");
            restored.scanNow();
            h.assertTrue(restored.isFormed(), "re-formed: " + BwrText.english(restored.problem() == null ? net.minecraft.network.chat.Component.empty() : restored.problem()));
            restored.simulateSeconds(1, T0 + 30 * 20);
            f.be = restored;
            var after = restored.view();
            h.assertTrue(restored.assemblyCount() == 10 && after.assemblies() == 10, "assemblies " + restored.assemblyCount());
            h.assertTrue(Math.abs(after.massKg() - before.massKg()) < 1.0, "water " + after.massKg() + " against " + before.massKg());
            h.assertTrue(Math.abs(after.temperatureC() - before.temperatureC()) < 0.05, "temperature " + after.temperatureC() + " against " + before.temperatureC());
            h.assertTrue(restored.coolingEnabled() && after.coolingEnabled(), "the pump switch");
            h.assertTrue(Math.abs(after.coolerColdKg() - before.coolerColdKg()) < 1.0, "cooling water held " + after.coolerColdKg() + " against " + before.coolerColdKg());
            double expectedHeat = 9 * AssemblyDecayHeat.powerWatts(BURNUP, 180.0, 4 * 3600 + 30)
                    + AssemblyDecayHeat.powerWatts(1234.0, 180.0, 90 + 30);
            h.assertTrue(Math.abs(after.decayW() - expectedHeat) < 1.0, "decay heat from the saved stamps: " + after.decayW() + " against " + expectedHeat);
            ItemStack kept = restored.stackAt(f.racks.get(5), 3);
            h.assertTrue(!kept.isEmpty() && FuelAssemblies.dataOf(kept).burnupMwdPerTonne() == 1234.0
                    && SpentFuelHeat.dischargeTime(kept) == T0 - 90L * 20, "the stored assembly and its stamp: " + kept);
            h.assertTrue(restored.energy().getEnergyStored() > 0, "stored energy");
            // an old save, written before any of this: empty tag
            var old = new SpentFuelPoolBlockEntity(f.controller, f.level.getBlockState(f.controller));
            old.loadWithComponents(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess());
            h.assertTrue(old.assemblyCount() == 0 && !old.coolingEnabled() && old.hazard() == 0.0, "an old controller loads empty");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 200)
    public static void anUnformedPoolKeepsItsFuelAndWaterUntilItIsRepaired(GameTestHelper h) {
        SpentFuelPoolFixture f = pool(h, RELOAD.offset(0, 0, 80), 4);
        try {
            addWater(f, 100_000, 25.0);
            f.fill(5, BURNUP, 3600, T0);
            BlockPos wall = RELOAD.offset(0, 0, 80).offset(8, 3, 5);
            f.level.setBlock(wall, Blocks.AIR.defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(!f.be.isFormed() && f.be.assemblyCount() == 5, "unformed but the fuel is still in the controller: " + f.be.assemblyCount());
            h.assertTrue(f.be.itemHandler().getSlots() == 0, "and not reachable through the handler");
            h.assertTrue(port(f, f.makeup) == null || port(f, f.makeup).fill(ThermalWater.atTemperature(100, 20), IFluidHandler.FluidAction.EXECUTE) == 0, "a broken pool takes no water");
            f.level.setBlock(wall, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed() && f.be.assemblyCount() == 5 && Math.abs(f.be.pool().massKg() - 100_000.0) < 1.0,
                    "repaired: " + f.be.assemblyCount() + " assemblies, " + f.be.pool().massKg() + " kg");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    /** The pool's numbers as a computer sees them, when CC:Tweaked is present. */
    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void theComputerFaceReadsTheSameNumbers(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("SPENT FUEL POOL CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        SpentFuelPoolCcChecks.run(h, RELOAD.offset(0, 0, 120));
        System.out.println("SPENT FUEL POOL CC: bwr_spent_fuel_pool getStatus, getRacks, getAssemblies and setCooling agree with the block entity");
        h.succeed();
    }
}
