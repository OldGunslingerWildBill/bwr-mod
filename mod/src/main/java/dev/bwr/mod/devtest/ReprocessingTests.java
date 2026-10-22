package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingItemView;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.gui.ReprocessingPlantMenu;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The reprocessing plant through its real block entity: what a spent assembly splits into, that a fresh one is
 * refused, that the fractions of plutonium (and of the other two products) carry from assembly to assembly without
 * anything appearing or disappearing, the sides a pipe sees, and the save. The numbers in {@link #TABLE} are worked
 * independently of the code (a separate implementation of the model, grain by grain), so they check the arithmetic and
 * not only that two calls agree.
 *
 * <p>Every test is synchronous (it drives {@code serverTick} itself, with energy topped up) so no fixture is left
 * ticking behind it, and each places its plant at its own position and empties it again.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ReprocessingTests {

    /** {fuel, enrichment, burnup MWd/t, waste grains, plutonium grains, uranium grains}: a grain is a millionth of a nugget. */
    private static final Object[][] TABLE = {
            {"leu", 0.035, 20000.0, 748217L, 202007L, 35049776L},
            {"leu", 0.035, 45000.0, 1683489L, 265255L, 34051256L},
            {"natural_uranium", 0.00711, 10000.0, 374108L, 121429L, 35504463L},
            {"heu", 0.20, 40000.0, 1496434L, 91642L, 34411924L},
            {"depleted_uranium", 0.0025, 20000.0, 748217L, 85067L, 35166716L},
            {"mox", 0.07, 45000.0, 1654886L, 998041L, 33347073L},
    };

    private static final long NUGGET = Reprocessing.GRAINS_PER_NUGGET;
    private static final long INGOT = Reprocessing.GRAINS_PER_INGOT;

    private static ItemStack spent(String fuel, double enrichment, double burnup) {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData(fuel, enrichment, 180.0, burnup, 1.0));
    }

    private static ReprocessingPlantBlockEntity place(ServerLevel l, BlockPos p) {
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 2);
        return (ReprocessingPlantBlockEntity) l.getBlockEntity(p);
    }

    private static void clear(ServerLevel l, BlockPos p, ReprocessingPlantBlockEntity be) {
        be.removalDrops();
        l.removeBlock(p, false);
    }

    /** One tick with power, as a generator cable would keep the buffer topped up. */
    private static void tick(ServerLevel l, BlockPos p, ReprocessingPlantBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    /** Ticks until the assembly in the slot has been consumed (or the limit); returns the ticks it took. */
    private static int runUntilConsumed(ServerLevel l, BlockPos p, ReprocessingPlantBlockEntity be, int limit) {
        int ticks = 0;
        while (ticks < limit && !be.input().getStackInSlot(0).isEmpty()) {
            tick(l, p, be);
            ticks++;
        }
        return ticks;
    }

    private static ItemStack insertFromTop(ServerLevel l, BlockPos p, ItemStack stack) {
        return l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP).insertItem(ReprocessingItemView.INPUT, stack, false);
    }

    /** What a hopper underneath takes from the three output slots. */
    private static long[] collect(ServerLevel l, BlockPos p) {
        var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
        long[] out = new long[3];
        ItemStack[] kinds = {new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()), new ItemStack(BwrItems.DEPLETED_URANIUM.get()),
                new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get())};
        for (int slot = 1; slot <= 3; slot++) {
            ItemStack got = down.extractItem(slot, 64, false);
            if (!got.isEmpty() && !got.is(kinds[slot - 1].getItem())) {
                throw new AssertionError("slot " + slot + " gave " + got);
            }
            out[slot - 1] += got.getCount();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aSpentAssemblyGivesExactlyTheWorkedOutputs(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int at = 0;
        for (Object[] row : TABLE) {
            String fuel = (String) row[0];
            long waste = (long) row[3];
            long plutonium = (long) row[4];
            long uranium = (long) row[5];
            h.assertTrue(waste + plutonium + uranium == SpentFuel.ASSEMBLY_GRAINS, fuel + ": the table row does not add up to an assembly");
            BlockPos p = new BlockPos(1200 + 3 * at++, 210, 1200);
            var be = place(l, p);
            ItemStack assembly = spent(fuel, (double) row[1], (double) row[2]);
            h.assertTrue(insertFromTop(l, p, assembly).isEmpty(), fuel + ": the spent assembly was refused");
            int ticks = runUntilConsumed(l, p, be, 1000);
            h.assertTrue(ticks == Reprocessing.WORK_TICKS, fuel + ": took " + ticks + " ticks, not " + Reprocessing.WORK_TICKS);
            h.assertTrue(be.input().getStackInSlot(0).isEmpty(), fuel + ": the assembly was not consumed");

            // the items that are whole, in the slots; the rest held as grains
            int nuggets = (int) (plutonium / NUGGET);
            int ingots = (int) (uranium / INGOT);
            int wasteItems = (int) (waste / NUGGET);
            h.assertTrue(be.plutoniumOut().getStackInSlot(0).getCount() == nuggets
                            && (nuggets == 0 || be.plutoniumOut().getStackInSlot(0).is(BwrItems.PLUTONIUM_NUGGET.get())),
                    fuel + ": plutonium nuggets " + be.plutoniumOut().getStackInSlot(0) + ", wanted " + nuggets);
            h.assertTrue(be.uraniumOut().getStackInSlot(0).getCount() == ingots
                            && (ingots == 0 || be.uraniumOut().getStackInSlot(0).is(BwrItems.DEPLETED_URANIUM.get())),
                    fuel + ": depleted uranium " + be.uraniumOut().getStackInSlot(0) + ", wanted " + ingots);
            h.assertTrue(be.wasteOut().getStackInSlot(0).getCount() == wasteItems
                            && (wasteItems == 0 || be.wasteOut().getStackInSlot(0).is(BwrItems.FISSION_PRODUCT_WASTE.get())),
                    fuel + ": waste " + be.wasteOut().getStackInSlot(0) + ", wanted " + wasteItems);
            h.assertTrue(be.plutoniumGrains() == plutonium % NUGGET, fuel + ": plutonium held " + be.plutoniumGrains() + ", wanted " + plutonium % NUGGET);
            h.assertTrue(be.uraniumGrains() == uranium % INGOT, fuel + ": uranium held " + be.uraniumGrains() + ", wanted " + uranium % INGOT);
            h.assertTrue(be.wasteGrains() == waste % NUGGET, fuel + ": waste held " + be.wasteGrains() + ", wanted " + waste % NUGGET);
            // every grain of heavy metal is somewhere
            long total = (nuggets + wasteItems) * NUGGET + ingots * INGOT + be.plutoniumGrains() + be.uraniumGrains() + be.wasteGrains();
            h.assertTrue(total == SpentFuel.ASSEMBLY_GRAINS, fuel + ": " + total + " grains out of " + SpentFuel.ASSEMBLY_GRAINS);
            clear(l, p, be);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theCostInEnergyIsExactAndNothingIsConsumedWithoutIt(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1210);
        var be = place(l, p);
        int needed = Reprocessing.WORK_TICKS * Reprocessing.ENERGY_PER_TICK;
        h.assertTrue(needed == 30_000, "the documented 30,000 FE an assembly is " + needed);
        insertFromTop(l, p, spent("leu", 0.035, 30000.0));
        // one tick short of the energy: it works for 199 ticks and the assembly is not consumed
        int given = 0;
        while (given < needed - Reprocessing.ENERGY_PER_TICK) {
            given += be.energy().receiveEnergy(needed - Reprocessing.ENERGY_PER_TICK - given, false);
        }
        for (int i = 0; i < 600; i++) {
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        }
        h.assertTrue(be.workTicks() == Reprocessing.WORK_TICKS - 1, "worked " + be.workTicks() + " ticks on 29,850 FE");
        h.assertTrue(!be.input().getStackInSlot(0).isEmpty() && be.uraniumGrains() == 0 && be.plutoniumOut().getStackInSlot(0).isEmpty(),
                "the assembly was consumed without the energy for it");
        h.assertTrue(be.energy().getEnergyStored() == 0, "energy left: " + be.energy().getEnergyStored());
        // the last tick's worth
        be.energy().receiveEnergy(Reprocessing.ENERGY_PER_TICK, false);
        ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        h.assertTrue(be.input().getStackInSlot(0).isEmpty() && be.energy().getEnergyStored() == 0, "the last 150 FE did not finish it");
        clear(l, p, be);

        // no power at all: nothing moves
        be = place(l, p);
        insertFromTop(l, p, spent("leu", 0.035, 30000.0));
        for (int i = 0; i < 500; i++) {
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        }
        h.assertTrue(be.workTicks() == 0 && !be.input().getStackInSlot(0).isEmpty(), "the plant worked without power");
        clear(l, p, be);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aFreshAssemblyIsRefusedEverywhere(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1220);
        var be = place(l, p);
        ItemStack fresh = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(FuelTypes.byNameOrPreset("leu")));
        ItemStack noComponent = new ItemStack(BwrItems.FUEL_ASSEMBLY.get());
        ItemStack thorium = spent("thorium", 0.03, 30000.0);
        h.assertTrue(!Reprocessing.accepts(fresh) && !Reprocessing.accepts(noComponent), "Reprocessing.accepts takes a fresh assembly");
        h.assertTrue(!Reprocessing.accepts(thorium), "Reprocessing.accepts takes a thorium assembly");
        h.assertTrue(Reprocessing.accepts(spent("leu", 0.035, 1.0e-9)), "an assembly with the least burnup is refused");
        for (Direction side : new Direction[] {Direction.UP, Direction.NORTH, Direction.EAST, null}) {
            var handler = l.getCapability(Capabilities.ItemHandler.BLOCK, p, side);
            h.assertTrue(!handler.isItemValid(ReprocessingItemView.INPUT, fresh), side + ": a fresh assembly is valid");
            h.assertTrue(handler.insertItem(ReprocessingItemView.INPUT, fresh.copy(), false).getCount() == 1, side + ": a fresh assembly was inserted");
            h.assertTrue(handler.insertItem(ReprocessingItemView.INPUT, thorium.copy(), false).getCount() == 1, side + ": a thorium assembly was inserted");
            h.assertTrue(handler.insertItem(ReprocessingItemView.INPUT, new ItemStack(Items.DIRT), false).getCount() == 1, side + ": dirt was inserted");
            h.assertTrue(handler.isItemValid(ReprocessingItemView.INPUT, spent("leu", 0.035, 30000.0)), side + ": a spent assembly is not valid");
        }
        h.assertTrue(be.input().getStackInSlot(0).isEmpty(), "something got into the slot");

        // the screen's slot agrees
        var player = FakePlayerFactory.getMinecraft(l);
        var menu = new ReprocessingPlantMenu(11, player.getInventory(), p);
        h.assertTrue(!menu.getSlot(ReprocessingPlantMenu.SLOT_INPUT).mayPlace(fresh), "the screen slot takes a fresh assembly");
        h.assertTrue(menu.getSlot(ReprocessingPlantMenu.SLOT_INPUT).mayPlace(spent("leu", 0.035, 30000.0)), "the screen slot refuses a spent assembly");
        for (int slot = ReprocessingPlantMenu.SLOT_PLUTONIUM; slot <= ReprocessingPlantMenu.SLOT_WASTE; slot++) {
            h.assertTrue(!menu.getSlot(slot).mayPlace(spent("leu", 0.035, 30000.0)) && !menu.getSlot(slot).mayPlace(new ItemStack(BwrItems.PLUTONIUM_NUGGET.get())),
                    "an output slot takes items");
        }

        // a fresh assembly that is in the slot some other way (an old save, a command) is never worked on
        be.input().setStackInSlot(0, fresh.copy());
        be.energy().receiveEnergy(10_000, false);
        be.energy().receiveEnergy(10_000, false);
        for (int i = 0; i < 1000; i++) {
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        }
        h.assertTrue(be.workTicks() == 0 && !be.input().getStackInSlot(0).isEmpty(), "the plant worked on a fresh assembly");
        h.assertTrue(be.plutoniumGrains() == 0 && be.uraniumGrains() == 0 && be.wasteGrains() == 0
                && be.uraniumOut().getStackInSlot(0).isEmpty() && be.wasteOut().getStackInSlot(0).isEmpty(), "a fresh assembly gave something");
        // and it did not use any energy getting nowhere
        int stored = be.energy().getEnergyStored();
        h.assertTrue(stored == 20_000, "energy was spent on a fresh assembly: " + stored);
        clear(l, p, be);
        h.succeed();
    }

    /**
     * Sixty spent assemblies of five fuels at different burnups through one plant, the outputs emptied from below as a
     * hopper would. Plutonium is a quarter of a nugget or less an assembly, so this only balances if the fractions
     * carry. The expected totals are the sum of the core's own splits, added up independently of the plant's carry.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void fractionsOfPlutoniumAreConservedAcrossManyAssemblies(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1230);
        var be = place(l, p);
        String[] fuels = {"leu", "natural_uranium", "depleted_uranium", "uranium_495", "mox"};
        double[] enrichments = new double[fuels.length];
        for (int i = 0; i < fuels.length; i++) {
            enrichments[i] = FuelTypes.byNameOrPreset(fuels[i]).nominalEnrichmentWeightFraction();
        }
        int count = 60;
        long expectedPlutonium = 0;
        long expectedUranium = 0;
        long expectedWaste = 0;
        long[] out = new long[3];
        for (int n = 0; n < count; n++) {
            int k = n % fuels.length;
            double burnup = 4000.0 + 1000.0 * ((n * 7) % 55);
            ItemStack assembly = spent(fuels[k], enrichments[k], burnup);
            var split = Reprocessing.split(FuelAssemblies.dataOf(assembly));
            h.assertTrue(split.total() == SpentFuel.ASSEMBLY_GRAINS, "the split of assembly " + n + " does not add up");
            expectedPlutonium += split.plutonium();
            expectedUranium += split.uranium();
            expectedWaste += split.fissionProducts();
            h.assertTrue(insertFromTop(l, p, assembly).isEmpty(), "assembly " + n + " was refused");
            runUntilConsumed(l, p, be, 1000);
            h.assertTrue(be.input().getStackInSlot(0).isEmpty(), "assembly " + n + " was not consumed");
            long[] got = collect(l, p);
            for (int i = 0; i < 3; i++) {
                out[i] += got[i];
            }
            // what the plant holds is always less than one whole item of each product (nothing whole is kept back)
            h.assertTrue(be.plutoniumGrains() < NUGGET && be.uraniumGrains() < INGOT && be.wasteGrains() < NUGGET,
                    "after assembly " + n + " the plant holds a whole item: " + be.plutoniumGrains() + " " + be.uraniumGrains() + " " + be.wasteGrains());
        }
        h.assertTrue(expectedPlutonium < count * NUGGET, "the fixture is not a fractional one: " + expectedPlutonium / (double) NUGGET + " nuggets in all");
        // nuggets out are exactly the whole nuggets of the plutonium that went through; the remainder is held to the grain
        h.assertTrue(out[0] == expectedPlutonium / NUGGET, "plutonium nuggets out " + out[0] + ", wanted " + expectedPlutonium / NUGGET);
        h.assertTrue(be.plutoniumGrains() == expectedPlutonium % NUGGET, "plutonium held " + be.plutoniumGrains() + ", wanted " + expectedPlutonium % NUGGET);
        h.assertTrue(out[1] == expectedUranium / INGOT && be.uraniumGrains() == expectedUranium % INGOT,
                "depleted uranium " + out[1] + " + " + be.uraniumGrains() + " grains, wanted " + expectedUranium / INGOT + " + " + expectedUranium % INGOT);
        h.assertTrue(out[2] == expectedWaste / NUGGET && be.wasteGrains() == expectedWaste % NUGGET,
                "waste " + out[2] + " + " + be.wasteGrains() + " grains, wanted " + expectedWaste / NUGGET + " + " + expectedWaste % NUGGET);
        // all of it: items out and fractions held are the heavy metal that went in, to the grain
        long total = (out[0] + out[2]) * NUGGET + out[1] * INGOT + be.plutoniumGrains() + be.uraniumGrains() + be.wasteGrains();
        h.assertTrue(total == count * SpentFuel.ASSEMBLY_GRAINS, "heavy metal in " + count * SpentFuel.ASSEMBLY_GRAINS + " grains, accounted for " + total);
        System.out.println("REPROCESSING: " + count + " assemblies -> " + out[0] + " plutonium nuggets (+" + be.plutoniumGrains() / (double) NUGGET + "), "
                + out[1] + " depleted uranium (+" + be.uraniumGrains() / (double) INGOT + "), " + out[2] + " waste (+" + be.wasteGrains() / (double) NUGGET + ")");
        clear(l, p, be);
        h.succeed();
    }

    /** Outputs nobody collects: the plant stops starting assemblies instead of voiding anything, and everything is still accounted for. */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void uncollectedOutputsBackUpTheMachineAndNothingIsLost(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1240);
        var be = place(l, p);
        int consumed = 0;
        // a heavily burned assembly each time: lots of waste per assembly
        for (int n = 0; n < 80; n++) {
            ItemStack assembly = spent("leu", 0.035, 60000.0);
            if (!insertFromTop(l, p, assembly).isEmpty()) {
                break;
            }
            runUntilConsumed(l, p, be, 1000);
            if (!be.input().getStackInSlot(0).isEmpty()) {
                break; // backed up: it does not start another
            }
            consumed++;
        }
        // the slot is full (64 of the depleted uranium) and the plant has stopped
        h.assertTrue(be.uraniumOut().getStackInSlot(0).getCount() == 64, "the depleted uranium slot holds " + be.uraniumOut().getStackInSlot(0).getCount());
        h.assertTrue(consumed >= 20 && consumed < 80, "consumed " + consumed + " assemblies before backing up");
        // the plant holds at most one more stack of an item it could not place
        h.assertTrue(be.uraniumGrains() / INGOT <= 64 + 4, "held more than a stack of uranium: " + be.uraniumGrains() / INGOT);
        long total = (be.plutoniumOut().getStackInSlot(0).getCount() + be.wasteOut().getStackInSlot(0).getCount()) * NUGGET
                + be.uraniumOut().getStackInSlot(0).getCount() * INGOT + be.plutoniumGrains() + be.uraniumGrains() + be.wasteGrains();
        h.assertTrue(total == consumed * SpentFuel.ASSEMBLY_GRAINS, "grains in " + consumed * SpentFuel.ASSEMBLY_GRAINS + ", accounted for " + total);
        // a stuck plant says so, and empties as the slots are emptied, going on with the next assembly
        h.assertTrue(be.statusLines().stream().anyMatch(s -> s.contains("piling up")), "no backed-up status: " + be.statusLines());
        long before = be.uraniumGrains();
        collect(l, p);
        insertFromTop(l, p, spent("leu", 0.035, 60000.0));
        runUntilConsumed(l, p, be, 1000);
        h.assertTrue(be.input().getStackInSlot(0).isEmpty(), "the plant did not go on after the outputs were emptied");
        h.assertTrue(be.uraniumGrains() <= before + SpentFuel.ASSEMBLY_GRAINS, "uranium appeared");
        // breaking it drops the whole items it holds, not a grain of fraction
        long wholeHeld = be.uraniumOut().getStackInSlot(0).getCount() + be.uraniumGrains() / INGOT;
        long droppedUranium = be.removalDrops().stream().filter(s -> s.is(BwrItems.DEPLETED_URANIUM.get())).mapToLong(ItemStack::getCount).sum();
        h.assertTrue(droppedUranium == wholeHeld, "breaking dropped " + droppedUranium + " depleted uranium, the plant held " + wholeHeld);
        h.assertTrue(be.plutoniumGrains() == 0 && be.uraniumGrains() == 0 && be.wasteGrains() == 0, "the stores were not emptied");
        l.removeBlock(p, false);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void pipesSeeTheRightSlotsFromEachSide(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1250);
        var be = place(l, p);
        var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
        var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
        var north = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.NORTH);
        var unsided = l.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
        h.assertTrue(up != null && down != null && north != null && unsided != null, "a side has no item handler");
        for (var handler : List.of(up, down, north, unsided)) {
            h.assertTrue(handler.getSlots() == 4, "not four slots");
        }
        h.assertTrue(l.getCapability(Capabilities.EnergyStorage.BLOCK, p, Direction.NORTH) != null, "no energy capability");

        ItemStack assembly = spent("leu", 0.035, 30000.0);
        // the bottom never takes the assembly; the top and the sides and the unsided query do
        h.assertTrue(!down.isItemValid(ReprocessingItemView.INPUT, assembly) && down.insertItem(ReprocessingItemView.INPUT, assembly.copy(), false).getCount() == 1, "the bottom accepted an assembly");
        h.assertTrue(north.insertItem(ReprocessingItemView.INPUT, assembly.copy(), true).isEmpty(), "a side refused an assembly");
        h.assertTrue(up.insertItem(ReprocessingItemView.INPUT, assembly.copy(), false).isEmpty(), "the top refused an assembly");
        h.assertTrue(be.input().getStackInSlot(0).is(BwrItems.FUEL_ASSEMBLY.get()), "the assembly is not in the slot");
        // the input cannot be pulled back out by a pipe, from anywhere
        for (var handler : List.of(up, down, north, unsided)) {
            h.assertTrue(handler.extractItem(ReprocessingItemView.INPUT, 1, false).isEmpty(), "the assembly was extracted");
        }
        h.assertTrue(!be.input().getStackInSlot(0).isEmpty(), "the assembly went missing");
        // nothing can be put into an output slot, from anywhere
        for (var handler : List.of(up, down, north, unsided)) {
            for (int slot = 1; slot <= 3; slot++) {
                h.assertTrue(handler.insertItem(slot, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()), false).getCount() == 1, "an output slot took an item");
                h.assertTrue(!handler.isItemValid(slot, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get())), "an output slot is valid for an item");
            }
        }

        be.plutoniumOut().setStackInSlot(0, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get(), 5));
        be.uraniumOut().setStackInSlot(0, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 6));
        be.wasteOut().setStackInSlot(0, new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get(), 7));
        // the top shows the input only: no output is visible or extractable
        h.assertTrue(up.getStackInSlot(ReprocessingItemView.INPUT).is(BwrItems.FUEL_ASSEMBLY.get()), "the top hides the input");
        for (int slot = 1; slot <= 3; slot++) {
            h.assertTrue(up.getStackInSlot(slot).isEmpty() && up.extractItem(slot, 5, true).isEmpty(), "the top shows output slot " + slot);
        }
        // the bottom shows the outputs only
        h.assertTrue(down.getStackInSlot(ReprocessingItemView.INPUT).isEmpty(), "the bottom shows the input");
        h.assertTrue(down.getStackInSlot(ReprocessingItemView.PLUTONIUM).getCount() == 5 && down.getStackInSlot(ReprocessingItemView.URANIUM).getCount() == 6
                && down.getStackInSlot(ReprocessingItemView.WASTE).getCount() == 7, "the bottom hides an output");
        h.assertTrue(down.extractItem(ReprocessingItemView.PLUTONIUM, 2, false).getCount() == 2, "the bottom cannot take plutonium");
        // the sides and the unsided query show all four
        h.assertTrue(north.getStackInSlot(ReprocessingItemView.INPUT).is(BwrItems.FUEL_ASSEMBLY.get()) && north.getStackInSlot(ReprocessingItemView.URANIUM).getCount() == 6, "a side hides a slot");
        h.assertTrue(north.extractItem(ReprocessingItemView.URANIUM, 6, false).getCount() == 6, "a side cannot take depleted uranium");
        h.assertTrue(unsided.extractItem(ReprocessingItemView.WASTE, 7, false).getCount() == 7, "unsided cannot take waste");
        h.assertTrue(unsided.extractItem(ReprocessingItemView.PLUTONIUM, 64, false).getCount() == 3, "unsided cannot take the rest of the plutonium");
        h.assertTrue(unsided.extractItem(ReprocessingItemView.PLUTONIUM, 64, false).isEmpty(), "plutonium from nothing");
        h.assertTrue(unsided.extractItem(9, 1, false).isEmpty() && unsided.getStackInSlot(-1).isEmpty() && unsided.getSlotLimit(9) == 0, "an out-of-range slot answered");
        clear(l, p, be);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void saveWritesKeysOnlyWhenNonEmptyAndLoadsBack(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1260);
        var be = place(l, p);
        // an untouched plant saves only what a machine always has
        var tag = be.saveWithFullMetadata(l.registryAccess());
        for (String key : new String[] {"WorkTicks", "PlutoniumGrains", "UraniumGrains", "WasteGrains", "Input", "PlutoniumOut", "UraniumOut", "WasteOut"}) {
            h.assertTrue(!tag.contains(key), "an empty plant wrote " + key);
        }
        h.assertTrue(tag.contains("Energy"), "no energy key");

        // work in progress with fractions held and an output waiting
        insertFromTop(l, p, spent("leu", 0.035, 45000.0));
        for (int i = 0; i < 200; i++) {
            tick(l, p, be);
        }
        insertFromTop(l, p, spent("depleted_uranium", 0.0025, 20000.0));
        for (int i = 0; i < 77; i++) {
            tick(l, p, be);
        }
        h.assertTrue(be.workTicks() == 77, "work in progress is " + be.workTicks());
        long pu = be.plutoniumGrains();
        long u = be.uraniumGrains();
        long w = be.wasteGrains();
        h.assertTrue(pu > 0 && u > 0 && w > 0, "no fractions held");
        tag = be.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(tag.getLong("PlutoniumGrains") == pu && tag.getLong("UraniumGrains") == u && tag.getLong("WasteGrains") == w
                && tag.getInt("WorkTicks") == 77 && tag.contains("Input") && tag.contains("UraniumOut") && tag.contains("WasteOut"), "keys missing from a working plant: " + tag);
        var fresh = new ReprocessingPlantBlockEntity(p, l.getBlockState(p));
        fresh.loadWithComponents(tag, l.registryAccess());
        h.assertTrue(fresh.plutoniumGrains() == pu && fresh.uraniumGrains() == u && fresh.wasteGrains() == w && fresh.workTicks() == 77, "fractions or work did not load");
        h.assertTrue(fresh.energy().getEnergyStored() == be.energy().getEnergyStored(), "energy did not load");
        h.assertTrue(FuelAssemblies.dataOf(fresh.input().getStackInSlot(0)).equals(FuelAssemblies.dataOf(be.input().getStackInSlot(0))), "the assembly did not load");
        h.assertTrue(fresh.uraniumOut().getStackInSlot(0).getCount() == be.uraniumOut().getStackInSlot(0).getCount(), "the outputs did not load");
        // the loaded plant carries on exactly like the original
        while (!fresh.input().getStackInSlot(0).isEmpty()) {
            fresh.energy().receiveEnergy(10_000, false);
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), fresh);
        }
        runUntilConsumed(l, p, be, 300);
        h.assertTrue(fresh.plutoniumGrains() == be.plutoniumGrains() && fresh.uraniumGrains() == be.uraniumGrains() && fresh.wasteGrains() == be.wasteGrains(),
                "the loaded plant ended with other fractions");

        // an odd save (hand-edited negatives) loads as empty fractions rather than minting or crashing
        var odd = new CompoundTag();
        odd.putLong("PlutoniumGrains", -5);
        odd.putLong("UraniumGrains", Long.MIN_VALUE);
        odd.putInt("WorkTicks", -3);
        odd.putInt("Energy", -9);
        var oddPlant = new ReprocessingPlantBlockEntity(p, l.getBlockState(p));
        oddPlant.loadWithComponents(odd, l.registryAccess());
        h.assertTrue(oddPlant.plutoniumGrains() == 0 && oddPlant.uraniumGrains() == 0 && oddPlant.workTicks() == 0 && oddPlant.energy().getEnergyStored() == 0, "a negative saved value loaded");
        clear(l, p, be);
        h.succeed();
    }

    /** The computer face (CC:Tweaked) of the plant and of the fabricator's MOX stores; says it was skipped without CC:Tweaked. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void computerFaces(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("REPROCESSING CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        ReprocessingCcChecks.run(h);
        System.out.println("REPROCESSING CC: bwr_reprocessing_plant and the fabricator's MOX fields read through getStatus");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theItemsAndTheRecipesAreRegisteredAndNeedNoOtherMod(GameTestHelper h) {
        for (String name : new String[] {"plutonium_nugget", "plutonium_ingot", "fission_product_waste", "reprocessing_plant"}) {
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("bwr", name)), name + " is not registered");
        }
        h.assertTrue(new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()).is(Reprocessing.PLUTONIUM_NUGGETS) && !new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()).is(Reprocessing.PLUTONIUM_INGOTS), "nugget tags");
        h.assertTrue(new ItemStack(BwrItems.PLUTONIUM_INGOT.get()).is(Reprocessing.PLUTONIUM_INGOTS) && !new ItemStack(BwrItems.PLUTONIUM_INGOT.get()).is(Reprocessing.PLUTONIUM_NUGGETS), "ingot tags");
        h.assertTrue(Reprocessing.plutoniumNuggets(new ItemStack(BwrItems.PLUTONIUM_INGOT.get())) == 9 && Reprocessing.plutoniumNuggets(new ItemStack(BwrItems.PLUTONIUM_NUGGET.get())) == 1
                && Reprocessing.plutoniumNuggets(new ItemStack(BwrItems.DEPLETED_URANIUM.get())) == 0, "plutonium worth");
        h.assertTrue(new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get()).getMaxStackSize() == 64 && new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()).getMaxStackSize() == 64, "stack sizes");

        var manager = h.getLevel().getServer().getRecipeManager();
        var plantRecipe = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", "reprocessing_plant"));
        h.assertTrue(plantRecipe.isPresent(), "no recipe bwr:reprocessing_plant (it must not depend on a mod)");
        var recipe = (CraftingRecipe) plantRecipe.get().value();
        h.assertTrue(recipe.getResultItem(h.getLevel().registryAccess()).is(BwrItems.REPROCESSING_PLANT.get()), "the recipe does not make the plant");
        // obsidian, a reactor vessel, hoppers, a blast furnace and iron: only vanilla items and our own vessel
        var grid = net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
        ItemStack obsidian = new ItemStack(Items.OBSIDIAN);
        ItemStack vessel = new ItemStack(BwrItems.REACTOR_VESSEL.get());
        ItemStack hopper = new ItemStack(Items.HOPPER);
        ItemStack furnace = new ItemStack(Items.BLAST_FURNACE);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        grid.set(0, obsidian);
        grid.set(1, vessel);
        grid.set(2, obsidian.copy());
        grid.set(3, hopper);
        grid.set(4, furnace);
        grid.set(5, hopper.copy());
        grid.set(6, iron);
        grid.set(7, iron.copy());
        grid.set(8, iron.copy());
        h.assertTrue(recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "obsidian, a vessel, two hoppers, a blast furnace and iron did not craft it");
        grid.set(8, ItemStack.EMPTY);
        h.assertTrue(!recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "two iron ingots crafted it");

        for (String[] pair : new String[][] {{"plutonium_ingot", "9"}, {"plutonium_nugget", "1"}}) {
            var holder = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", pair[0]));
            h.assertTrue(holder.isPresent(), "no recipe " + pair[0]);
            var r = (CraftingRecipe) holder.get().value();
            var in = net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            if (pair[0].equals("plutonium_ingot")) {
                for (int i = 0; i < 9; i++) {
                    in.set(i, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()));
                }
                h.assertTrue(r.matches(CraftingInput.of(3, 3, in), h.getLevel()) && r.getResultItem(h.getLevel().registryAccess()).is(BwrItems.PLUTONIUM_INGOT.get()), "nine nuggets do not make an ingot");
                in.set(8, ItemStack.EMPTY);
                h.assertTrue(!r.matches(CraftingInput.of(3, 3, in), h.getLevel()), "eight nuggets made an ingot");
            } else {
                in.set(4, new ItemStack(BwrItems.PLUTONIUM_INGOT.get()));
                var result = r.getResultItem(h.getLevel().registryAccess());
                h.assertTrue(r.matches(CraftingInput.of(3, 3, in), h.getLevel()) && result.is(BwrItems.PLUTONIUM_NUGGET.get()) && result.getCount() == 9, "an ingot does not make nine nuggets");
            }
        }
        h.succeed();
    }
}
