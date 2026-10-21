package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.EnrichmentMassBalance;
import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelFeed;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.gui.FuelFabricatorMenu;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The fuel fabricator's item route, through the real block entity: what an assembly costs and returns at each
 * enrichment, that nothing happens without enough input, the slots a pipe sees, the saved target, a fabricator saved
 * before the route existed, and that all of it works with no Mekanism.
 *
 * <p>Uranium and steel are items other mods supply by tag. A run without Mekanism has none, so the tests that need an
 * ingot use {@link FuelItemRoute#overrideInputsForTest}: gold ingots and raw gold stand for uranium and iron ingots for
 * steel. {@link #mekanismTagsAndChemicalRoute} uses the real Mekanism items and the real chemical tanks when Mekanism is
 * loaded, and says it was skipped when it is not.
 *
 * <p>Every test is synchronous (it drives {@code serverTick} itself with the energy topped up), so no fixture is left
 * ticking behind it, and each places its fabricator at its own position and empties it again.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FuelFabricatorItemRouteTests {

    private static final Item URANIUM = Items.GOLD_INGOT;
    private static final Item RAW_URANIUM = Items.RAW_GOLD;
    private static final Item STEEL = Items.IRON_INGOT;

    /** {fuel type, ingots in, steel, depleted uranium back}: worked by hand from (xp - 0.25%) / (0.711% - 0.25%), four units of product. */
    private static final Object[][] TABLE = {
            {"natural_uranium", 4, 4, 0},
            {"uranium_27", 22, 4, 17},
            {"leu", 29, 4, 24},
            {"uranium_495", 41, 4, 36},
            {"uranium_8", 68, 4, 63},
            {"uranium_1975", 170, 4, 165},
            {"heu", 172, 4, 167},
    };

    private static void standIns() {
        FuelItemRoute.overrideInputsForTest(
                stack -> stack.is(URANIUM) || stack.is(RAW_URANIUM) ? FuelItemRoute.UNITS_PER_INGOT : 0,
                stack -> stack.is(STEEL));
    }

    private static void real() {
        FuelItemRoute.overrideInputsForTest(null, null);
    }

    private static FuelFabricatorBlockEntity place(ServerLevel l, BlockPos p) {
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        return (FuelFabricatorBlockEntity) l.getBlockEntity(p);
    }

    private static void clear(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        be.removalDrops();
        l.removeBlock(p, false);
    }

    private static void tick(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    /** Ticks with power, as a hopper would keep the uranium slot filled and empty the outputs, until {@code stop} or the limit. */
    private static int runFed(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be, int uranium, Item ore, int limit) {
        var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
        int remaining = uranium;
        int ticks = 0;
        while (ticks < limit) {
            if (remaining > 0) {
                int offer = Math.min(64, remaining);
                ItemStack rest = up.insertItem(FabricatorItemView.URANIUM, new ItemStack(ore, offer), false);
                remaining -= offer - rest.getCount();
            }
            tick(l, p, be);
            ticks++;
            if (remaining == 0 && !be.output().getStackInSlot(0).isEmpty()) {
                break;
            }
        }
        return ticks;
    }

    /** Takes everything out of the depleted uranium slot from underneath, as a hopper would, ticking until the machine holds none. */
    private static int collectTails(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
        int total = 0;
        for (int i = 0; i < 20 && (be.tailsHeld() > 0); i++) {
            ItemStack got = down.extractItem(FabricatorItemView.TAILS, 64, false);
            if (!got.isEmpty() && !got.is(BwrItems.DEPLETED_URANIUM.get())) {
                throw new AssertionError("the depleted uranium slot gave " + got);
            }
            total += got.getCount();
            tick(l, p, be);
        }
        return total;
    }

    private static int steelIn(ServerLevel l, BlockPos p, int n) {
        var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
        ItemStack rest = up.insertItem(FabricatorItemView.STEEL, new ItemStack(STEEL, n), false);
        return n - rest.getCount();
    }

    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void massBalanceTable(GameTestHelper h) {
        for (Object[] row : TABLE) {
            String name = (String) row[0];
            var cost = FuelItemRoute.cost(name);
            h.assertTrue(cost != null, name + " has no cost");
            h.assertTrue(cost.fuelType().equals(name), name + " costs a different fuel: " + cost.fuelType());
            h.assertTrue(cost.ingots() == (int) row[1], name + " uranium: " + cost.ingots() + " not " + row[1]);
            h.assertTrue(cost.steel() == (int) row[2], name + " steel: " + cost.steel() + " not " + row[2]);
            h.assertTrue(cost.tails() == (int) row[3], name + " depleted uranium: " + cost.tails() + " not " + row[3]);
            h.assertTrue(cost.workTicks() == FuelItemRoute.BASE_WORK_TICKS + FuelItemRoute.TICKS_PER_INGOT * cost.ingots(),
                    name + " work time");
            // the formula really is the plant's: feed >= product x ratio, and the ratio is the one in the docs
            double ratio = EnrichmentMassBalance.feedPerProduct(FuelTypes.byNameOrPreset(name).nominalEnrichmentWeightFraction());
            h.assertTrue(cost.ingots() >= FuelItemRoute.PRODUCT_INGOTS * ratio - 1e-9
                            && cost.ingots() < FuelItemRoute.PRODUCT_INGOTS * ratio + 1.0,
                    name + " does not follow the mass balance: " + cost.ingots() + " vs " + FuelItemRoute.PRODUCT_INGOTS * ratio);
            h.assertTrue(cost.units() == cost.ingots() * 2, name + " units");
        }
        h.assertTrue(FuelItemRoute.TARGETS.size() == TABLE.length, "the ladder has " + FuelItemRoute.TARGETS.size() + " steps");
        for (String retired : new String[] {"uranium_12", "uranium_14", "gadolinia_uranium", "thorium", "mox", "plutonium", "bogus"}) {
            h.assertTrue(!FuelItemRoute.isTarget(retired) && FuelItemRoute.cost(retired) == null, retired + " is offered");
        }
        h.assertTrue(FuelItemRoute.isTarget(FuelItemRoute.DEFAULT_TARGET), "the default target is not on the ladder");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fabricatesNaturalLeuAndHeuThroughTheBlockEntity(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            int at = 0;
            for (Object[] row : new Object[][] {TABLE[0], TABLE[2], TABLE[6]}) {
                String name = (String) row[0];
                int uranium = (int) row[1];
                int steel = (int) row[2];
                int tails = (int) row[3];
                BlockPos p = new BlockPos(600 + 4 * at++, 210, 640);
                var be = place(l, p);
                h.assertTrue(be.setTarget(name), "target " + name + " refused");
                // extra steel proves only the cost is taken; a half stack of uranium over proves the same
                h.assertTrue(steelIn(l, p, steel + 3) == steel + 3, "steel not accepted");
                int extra = 5;
                int ticks = runFed(l, p, be, uranium + extra, URANIUM, 5000);

                var assembly = be.output().getStackInSlot(0);
                h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()), name + ": no assembly after " + ticks + " ticks");
                var data = FuelAssemblies.dataOf(assembly);
                var type = FuelTypes.byNameOrPreset(name);
                h.assertTrue(data.fuelTypeName().equals(name), name + ": stamped " + data.fuelTypeName());
                h.assertTrue(data.enrichmentWeightFraction() == type.nominalEnrichmentWeightFraction(), name + ": enrichment");
                h.assertTrue(data.equals(FuelAssemblyData.fresh(type)), name + ": not the data the chemical route stamps on a fresh bundle");
                h.assertTrue(ticks >= FuelItemRoute.cost(name).workTicks(), name + ": made in " + ticks + " ticks, less than its work time");

                int uraniumLeft = be.uraniumIn().getStackInSlot(0).getCount() + be.uraniumHeldUnits() / 2;
                h.assertTrue(uraniumLeft == extra, name + ": uranium left " + uraniumLeft + ", wanted " + extra + " (took " + (uranium + extra - uraniumLeft) + ", cost " + uranium + ")");
                h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 3, name + ": steel left " + be.steelIn().getStackInSlot(0).getCount());
                int collected = collectTails(l, p, be);
                h.assertTrue(collected == tails, name + ": depleted uranium " + collected + ", wanted " + tails);
                h.assertTrue(be.tailsHeld() == 0, name + ": depleted uranium left behind");

                // the finished assembly blocks the machine rather than being overwritten: with steel and uranium for another
                // (natural: enough on its own), 400 more ticks change nothing
                steelIn(l, p, steel);
                be.uraniumIn().insertItem(0, new ItemStack(URANIUM, 64), false);
                for (int i = 0; i < 400; i++) {
                    tick(l, p, be);
                }
                h.assertTrue(be.output().getStackInSlot(0).getCount() == 1 && be.tailsHeld() == 0, name + ": made a second assembly over the first");
                h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 3 + steel, name + ": steel taken while the output was full");
                // and a pipe can take it from underneath
                var taken = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN).extractItem(FabricatorItemView.ASSEMBLY, 1, false);
                h.assertTrue(taken.is(BwrItems.FUEL_ASSEMBLY.get()) && be.output().getStackInSlot(0).isEmpty(), name + ": assembly not extractable");
                clear(l, p, be);
            }
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rawUraniumAndHalfIngotsCountSensibly(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(600, 210, 650);
            var be = place(l, p);
            h.assertTrue(be.setTarget("natural_uranium"), "target refused");
            steelIn(l, p, 4);
            // raw ore counts one ingot each: 4 of it make a natural assembly
            runFed(l, p, be, 4, RAW_URANIUM, 1000);
            h.assertTrue(be.output().getStackInSlot(0).is(BwrItems.FUEL_ASSEMBLY.get()), "raw uranium did not make an assembly");
            clear(l, p, be);
            // yellow cake is half an ingot: 3 ingots and 2 cakes are 4 ingots' worth
            FuelItemRoute.overrideInputsForTest(
                    stack -> stack.is(URANIUM) ? 2 : stack.is(Items.WHEAT) ? 1 : 0, stack -> stack.is(STEEL));
            be = place(l, p);
            be.setTarget("natural_uranium");
            steelIn(l, p, 4);
            be.uraniumIn().insertItem(0, new ItemStack(URANIUM, 3), false);
            for (int i = 0; i < 400; i++) {
                tick(l, p, be);
            }
            h.assertTrue(be.output().getStackInSlot(0).isEmpty(), "3 ingots made a 4-ingot assembly");
            be.uraniumIn().insertItem(0, new ItemStack(Items.WHEAT, 2), false);
            for (int i = 0; i < 400; i++) {
                tick(l, p, be);
            }
            h.assertTrue(be.output().getStackInSlot(0).is(BwrItems.FUEL_ASSEMBLY.get()), "3 ingots and 2 yellow cake (4 ingots) did not make one");
            clear(l, p, be);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void notEnoughInputDoesNothing(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(600, 210, 660);
            var be = place(l, p);
            be.setTarget("leu"); // 29 uranium, 4 steel
            // one ingot short
            steelIn(l, p, 8);
            runFed(l, p, be, 28, URANIUM, 1500);
            h.assertTrue(be.output().getStackInSlot(0).isEmpty(), "an assembly from 28 of 29 uranium");
            h.assertTrue(be.tailsHeld() == 0 && be.tailsOut().getStackInSlot(0).isEmpty(), "depleted uranium from nothing");
            h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 8, "steel consumed by an assembly that did not happen");
            h.assertTrue(be.itemWorkTicks() == 0, "work under way without enough uranium");
            h.assertTrue(be.uraniumIn().getStackInSlot(0).getCount() + be.uraniumHeldUnits() / 2 == 28, "uranium went missing");
            // the last ingot completes it
            runFed(l, p, be, 1, URANIUM, 1500);
            h.assertTrue(be.output().getStackInSlot(0).is(BwrItems.FUEL_ASSEMBLY.get()), "the 29th ingot did not complete it");
            h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 4, "steel not taken for the assembly");
            clear(l, p, be);

            // enough uranium, one steel short
            be = place(l, p);
            be.setTarget("leu");
            steelIn(l, p, 3);
            runFed(l, p, be, 29, URANIUM, 1500);
            h.assertTrue(be.output().getStackInSlot(0).isEmpty() && be.steelIn().getStackInSlot(0).getCount() == 3,
                    "an assembly from 3 of 4 steel");
            clear(l, p, be);

            // no power: nothing moves
            be = place(l, p);
            be.setTarget("natural_uranium");
            steelIn(l, p, 4);
            be.uraniumIn().insertItem(0, new ItemStack(URANIUM, 4), false);
            for (int i = 0; i < 500; i++) {
                FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
            }
            h.assertTrue(be.output().getStackInSlot(0).isEmpty() && be.uraniumIn().getStackInSlot(0).getCount() == 4,
                    "the machine worked without power");
            clear(l, p, be);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void pipesSeeTheRightSlotsFromEachSide(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(600, 210, 670);
            var be = place(l, p);
            var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
            var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
            var north = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.NORTH);
            var unsided = l.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            h.assertTrue(up != null && down != null && north != null && unsided != null, "a side has no item handler");
            for (var handler : List.of(up, down, north, unsided)) {
                h.assertTrue(handler.getSlots() == 4, "not four slots");
            }

            // inputs: only uranium and steel, only from the top and the sides
            ItemStack gold = new ItemStack(URANIUM, 3);
            h.assertTrue(up.isItemValid(FabricatorItemView.URANIUM, gold) && !up.isItemValid(FabricatorItemView.STEEL, gold), "uranium slot validity");
            h.assertTrue(up.isItemValid(FabricatorItemView.STEEL, new ItemStack(STEEL)) && !up.isItemValid(FabricatorItemView.URANIUM, new ItemStack(STEEL)), "steel slot validity");
            h.assertTrue(!up.isItemValid(FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get())), "depleted uranium accepted as feed");
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, new ItemStack(Items.DIRT), false).getCount() == 1, "dirt accepted as uranium");
            h.assertTrue(down.insertItem(FabricatorItemView.URANIUM, gold.copy(), false).getCount() == 3, "the bottom accepted uranium");
            h.assertTrue(north.insertItem(FabricatorItemView.URANIUM, gold.copy(), true).isEmpty(), "a side refused uranium");
            h.assertTrue(unsided.insertItem(FabricatorItemView.STEEL, new ItemStack(STEEL, 2), false).isEmpty(), "unsided refused steel");
            h.assertTrue(up.insertItem(FabricatorItemView.ASSEMBLY, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()), false).getCount() == 1, "an assembly was put in the assembly slot");
            h.assertTrue(up.insertItem(FabricatorItemView.TAILS, new ItemStack(BwrItems.DEPLETED_URANIUM.get()), false).getCount() == 1, "depleted uranium was put in its output slot");

            // inputs cannot be pulled back out by a pipe, from anywhere
            for (var handler : List.of(up, down, north, unsided)) {
                h.assertTrue(handler.extractItem(FabricatorItemView.STEEL, 8, false).isEmpty()
                        && handler.extractItem(FabricatorItemView.URANIUM, 8, false).isEmpty(), "an input was extracted");
            }
            h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 2, "steel went missing");

            // outputs: visible from the bottom and the sides, never the top
            be.output().setStackInSlot(0, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
            be.tailsOut().setStackInSlot(0, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 5));
            h.assertTrue(up.getStackInSlot(FabricatorItemView.ASSEMBLY).isEmpty() && up.extractItem(FabricatorItemView.TAILS, 5, true).isEmpty(), "the top shows outputs");
            h.assertTrue(down.getStackInSlot(FabricatorItemView.ASSEMBLY).is(BwrItems.FUEL_ASSEMBLY.get()), "the bottom hides the assembly");
            h.assertTrue(north.extractItem(FabricatorItemView.TAILS, 5, false).getCount() == 5, "a side cannot take depleted uranium");
            h.assertTrue(unsided.extractItem(FabricatorItemView.ASSEMBLY, 1, false).is(BwrItems.FUEL_ASSEMBLY.get()), "unsided cannot take the assembly");
            // slot 0 is still the assembly: a pipe set up for the old single slot keeps working
            h.assertTrue(FabricatorItemView.ASSEMBLY == 0, "the assembly moved from slot 0");
            clear(l, p, be);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void targetIsValidatedSavedOnlyWhenNotDefaultAndLoads(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(600, 210, 680);
        var be = place(l, p);
        h.assertTrue(be.target().equals(FuelItemRoute.DEFAULT_TARGET), "a new fabricator is not on the default target");
        var tag = be.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(!tag.contains("Target"), "the default target was saved");

        for (String bad : new String[] {"uranium_12", "mox", "", "minecraft:stone", "LEU"}) {
            h.assertTrue(!be.setTarget(bad) && be.target().equals("leu"), "target '" + bad + "' accepted");
        }
        h.assertTrue(!be.setTarget(null), "null accepted");
        h.assertTrue(be.setTarget("uranium_8") && be.target().equals("uranium_8"), "uranium_8 refused");
        tag = be.saveWithFullMetadata(l.registryAccess());
        h.assertTrue("uranium_8".equals(tag.getString("Target")), "target not saved");

        // loaded back into a fresh block entity
        var fresh = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
        fresh.loadWithComponents(tag, l.registryAccess());
        h.assertTrue(fresh.target().equals("uranium_8"), "target did not load: " + fresh.target());
        h.assertTrue(fresh.itemCost().ingots() == 68, "cost did not follow the loaded target");

        // back on the default, the key disappears again
        h.assertTrue(be.setTarget("leu"), "leu refused");
        h.assertTrue(!be.saveWithFullMetadata(l.registryAccess()).contains("Target"), "the default target was saved after being re-selected");

        // a hand-edited save with a target that is not on the ladder loads as the default
        var edited = new CompoundTag();
        edited.putString("Target", "uranium_12");
        var odd = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
        odd.loadWithComponents(edited, l.registryAccess());
        h.assertTrue(odd.target().equals("leu"), "a retired target loaded");
        edited.putString("Target", "plutonium");
        odd.loadWithComponents(edited, l.registryAccess());
        h.assertTrue(odd.target().equals("leu"), "a foreign target loaded");

        // the menu command: range checked, and a forged index changes nothing
        var player = FakePlayerFactory.getMinecraft(l);
        var menu = new FuelFabricatorMenu(7, player.getInventory(), p);
        menu.handleCommand(player, FuelFabricatorMenu.CMD_TARGET, 4, 0);
        h.assertTrue(be.target().equals(FuelItemRoute.TARGETS.get(4)), "the command did not select index 4: " + be.target());
        for (int[] forged : new int[][] {{FuelFabricatorMenu.CMD_TARGET, -1}, {FuelFabricatorMenu.CMD_TARGET, 7}, {FuelFabricatorMenu.CMD_TARGET, Integer.MAX_VALUE},
                {FuelFabricatorMenu.CMD_TARGET, Integer.MIN_VALUE}, {0, 1}, {99, 1}}) {
            String before = be.target();
            menu.handleCommand(player, forged[0], forged[1], 0);
            h.assertTrue(be.target().equals(before), "forged command " + forged[0] + "/" + forged[1] + " changed the target");
        }
        clear(l, p, be);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aFabricatorSavedBeforeTheItemRouteLoadsEmptyOnTheDefaultTarget(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(600, 210, 690);
            var be = place(l, p);
            // an old save carries exactly the keys the fabricator wrote before: batch, energy, output, feed
            var old = new CompoundTag();
            old.putDouble("BatchHeavyMetalKg", 61.25);
            old.putDouble("BatchFissileKg", 2.5);
            old.putDouble("BatchPlutoniumKg", 0.0);
            old.putInt("Energy", 42_000);
            var made = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            var heldAssembly = dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(FuelTypes.byNameOrPreset("heu")));
            made.output().setStackInSlot(0, heldAssembly);
            old.put("Output", made.output().serializeNBT(l.registryAccess()));
            old.put("Feed", be.saveWithFullMetadata(l.registryAccess()).getCompound("Feed"));
            for (String key : new String[] {"Target", "UraniumUnits", "TailsPending", "WorkTicks", "UraniumIn", "SteelIn", "TailsOut"}) {
                h.assertTrue(!old.contains(key), "the old save fixture has " + key);
            }

            var loaded = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            loaded.loadWithComponents(old, l.registryAccess());
            h.assertTrue(loaded.target().equals(FuelItemRoute.DEFAULT_TARGET), "old save: target " + loaded.target());
            h.assertTrue(loaded.uraniumIn().getStackInSlot(0).isEmpty() && loaded.steelIn().getStackInSlot(0).isEmpty()
                    && loaded.tailsOut().getStackInSlot(0).isEmpty() && loaded.uraniumHeldUnits() == 0 && loaded.tailsHeld() == 0
                    && loaded.itemWorkTicks() == 0, "old save: item route is not empty");
            h.assertTrue(loaded.batchHeavyMetalKg() == 61.25 && Math.abs(loaded.batchEnrichmentWeightFraction() - 2.5 / 61.25) < 1e-12, "old save: chemical batch changed");
            h.assertTrue(loaded.energy().getEnergyStored() == 42_000, "old save: energy changed");
            h.assertTrue(FuelAssemblies.dataOf(loaded.output().getStackInSlot(0)).equals(FuelAssemblyData.fresh(FuelTypes.byNameOrPreset("heu"))), "old save: assembly changed");

            // saved again it still writes none of the new keys, and the feed compound is byte for byte what it was
            var again = loaded.saveWithFullMetadata(l.registryAccess());
            for (String key : new String[] {"Target", "UraniumUnits", "TailsPending", "WorkTicks", "UraniumIn", "SteelIn", "TailsOut"}) {
                h.assertTrue(!again.contains(key), "re-saved old fabricator wrote " + key);
            }
            h.assertTrue(again.getCompound("Feed").equals(old.getCompound("Feed")), "old save: the feed (chemical tanks) changed");
            h.assertTrue(again.getDouble("BatchHeavyMetalKg") == 61.25 && again.getInt("Energy") == 42_000, "old save: batch or energy changed on re-save");

            clear(l, p, be);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void saveKeepsInputsHeldUraniumAndWorkInProgress(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(600, 210, 700);
            var be = place(l, p);
            be.setTarget("heu"); // 172 ingots: more than a stack, so the machine has to hold some
            steelIn(l, p, 6);
            var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
            up.insertItem(FabricatorItemView.URANIUM, new ItemStack(URANIUM, 64), false);
            for (int i = 0; i < 3; i++) {
                tick(l, p, be);
            }
            h.assertTrue(be.uraniumHeldUnits() == 128, "the machine did not take in the first stack: " + be.uraniumHeldUnits());
            up.insertItem(FabricatorItemView.URANIUM, new ItemStack(URANIUM, 64), false);
            for (int i = 0; i < 3; i++) {
                tick(l, p, be);
            }
            up.insertItem(FabricatorItemView.URANIUM, new ItemStack(URANIUM, 44), false);
            for (int i = 0; i < 40; i++) {
                tick(l, p, be);
            }
            h.assertTrue(be.itemWorkTicks() > 0 && be.itemWorkTicks() < 616, "work not under way: " + be.itemWorkTicks());
            int worked = be.itemWorkTicks();
            int held = be.uraniumHeldUnits();
            var tag = be.saveWithFullMetadata(l.registryAccess());
            var fresh = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            fresh.loadWithComponents(tag, l.registryAccess());
            h.assertTrue(fresh.target().equals("heu") && fresh.uraniumHeldUnits() == held && fresh.itemWorkTicks() == worked,
                    "held uranium or work not restored: " + fresh.uraniumHeldUnits() + " " + fresh.itemWorkTicks());
            h.assertTrue(fresh.steelIn().getStackInSlot(0).getCount() == 6, "steel not restored");
            // breaking the block gives the held uranium back, as ingots of whatever the tag names (none without a mod: nothing to give)
            var drops = be.removalDrops();
            long dropped = drops.stream().mapToLong(ItemStack::getCount).sum();
            h.assertTrue(drops.stream().anyMatch(s -> s.is(STEEL)), "breaking it kept the steel");
            h.assertTrue(be.uraniumHeldUnits() == 0 && be.steelIn().getStackInSlot(0).isEmpty() && be.uraniumIn().getStackInSlot(0).isEmpty(),
                    "breaking it left items inside");
            h.assertTrue(dropped >= 6, "nothing dropped");
            l.removeBlock(p, false);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fabricatorWorksWithoutMekanismChemistry(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            boolean mekanism = ModList.get().isLoaded("mekanism");
            BlockPos p = new BlockPos(600, 210, 710);
            var be = place(l, p);
            if (!mekanism) {
                h.assertTrue(be.feed() == FuelFeed.NONE, "no Mekanism, but a chemical feed");
            }
            h.assertTrue(be.energy().getMaxEnergyStored() > 0, "no energy buffer");
            be.setTarget("uranium_495");
            steelIn(l, p, 4);
            runFed(l, p, be, 41, URANIUM, 2000);
            var assembly = be.output().getStackInSlot(0);
            h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()) && FuelAssemblies.dataOf(assembly).fuelTypeName().equals("uranium_495"),
                    "no 4.95 % assembly from 41 uranium and 4 steel (Mekanism " + (mekanism ? "loaded" : "absent") + ")");
            h.assertTrue(collectTails(l, p, be) == 36, "depleted uranium count");
            clear(l, p, be);
            System.out.println("FUEL FABRICATOR ITEM ROUTE: made a 4.95% assembly with Mekanism " + (mekanism ? "loaded" : "absent"));
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depletedUraniumIsAnIngotTheFabricatorDoesNotTakeBack(GameTestHelper h) {
        real();
        Item du = BwrItems.DEPLETED_URANIUM.get();
        h.assertTrue(BuiltInRegistries.ITEM.getKey(du).equals(ResourceLocation.fromNamespaceAndPath("bwr", "depleted_uranium")), "registry name");
        h.assertTrue(new ItemStack(du).getMaxStackSize() == 64, "stack size");
        h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(du)) == 0 && !FuelItemRoute.isSteel(new ItemStack(du)), "depleted uranium counts as feed");
        h.assertTrue(new ItemStack(du).is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("c", "ingots/depleted_uranium"))), "not tagged c:ingots/depleted_uranium");
        h.assertTrue(!new ItemStack(du).is(FuelItemRoute.URANIUM_INGOTS), "depleted uranium is tagged as a uranium ingot");
        h.succeed();
    }

    /**
     * The recipe set as the server loaded it: every bwr item has a crafting recipe except the ones that are not meant to be
     * crafted (the same list, with reasons, as NO_RECIPE in tools-audit-recipes.py), and nothing on that list has one.
     * The Mekanism-gated recipes only exist with Mekanism.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void everyCraftableItemHasARecipeAndTheRestDoNot(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var made = new java.util.HashSet<ResourceLocation>();
        for (var holder : server.getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)) {
            made.add(BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(h.getLevel().registryAccess()).getItem()));
        }
        var notCrafted = new java.util.HashSet<String>(List.of("cobalt_sample", "tritium_sample", "doped_silicon", "corium", "depleted_uranium",
                "rcic_turbine_pump", "hpci_turbine_pump", "suppression_pool_quencher"));
        if (!ModList.get().isLoaded("mekanism")) {
            notCrafted.add("fuel_assembly");
            notCrafted.add("fuel_fabricator");
        }
        for (var id : BuiltInRegistries.ITEM.keySet()) {
            if (!id.getNamespace().equals("bwr")) {
                continue;
            }
            boolean has = made.contains(id);
            boolean should = !notCrafted.contains(id.getPath());
            h.assertTrue(has == should, id + (should ? " has no crafting recipe" : " has a crafting recipe but is not meant to be crafted"));
        }
        h.succeed();
    }

    /** Real Mekanism items, tags and chemical tanks; reports that it was skipped when Mekanism is absent. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void mekanismTagsAndChemicalRoute(GameTestHelper h) {
        if (!ModList.get().isLoaded("mekanism")) {
            System.out.println("FUEL FABRICATOR MEKANISM: skipped, Mekanism is not loaded");
            h.succeed();
            return;
        }
        real();
        FuelFabricatorMekanismChecks.run(h);
    }
}
