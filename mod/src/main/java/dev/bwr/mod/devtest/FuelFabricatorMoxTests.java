package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.MoxMassBalance;
import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingItemView;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.gui.FuelFabricatorMenu;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The fuel fabricator's MOX targets: what they cost (from the MOX fuel type's own enrichment), that the machine consumes
 * exactly that much plutonium, depleted uranium and steel and nothing else, what its input slot takes on a MOX target
 * (and that it cannot be jammed), that old saves still load and the new state saves only when it holds something, the
 * selector command, and the whole cycle: spent fuel through the reprocessing plant into a fresh MOX assembly.
 *
 * <p>Steel is {@link Items#IRON_INGOT} here (a run without Mekanism has no steel, see
 * {@link FuelItemRoute#overrideInputsForTest}); plutonium and depleted uranium are the mod's own items.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FuelFabricatorMoxTests {

    private static final Item STEEL = Items.IRON_INGOT;
    private static final Item NUGGET = BwrItems.PLUTONIUM_NUGGET.get();

    private static void standIns() {
        FuelItemRoute.overrideInputsForTest(stack -> stack.is(Items.GOLD_INGOT) ? FuelItemRoute.UNITS_PER_INGOT : 0, stack -> stack.is(STEEL));
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

    private static void ticks(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be, int n) {
        for (int i = 0; i < n; i++) {
            tick(l, p, be);
        }
    }

    private static ItemStack feed(ServerLevel l, BlockPos p, int slot, ItemStack stack) {
        return l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP).insertItem(slot, stack, false);
    }

    /** What the machine holds of plutonium (nuggets), in its store and in the input slot. */
    private static int plutoniumHeld(FuelFabricatorBlockEntity be) {
        ItemStack slot = be.uraniumIn().getStackInSlot(0);
        return be.plutoniumHeldNuggets() + Reprocessing.plutoniumNuggets(slot) * slot.getCount();
    }

    private static int matrixHeld(FuelFabricatorBlockEntity be) {
        ItemStack slot = be.uraniumIn().getStackInSlot(0);
        return be.matrixHeldIngots() + (FuelItemRoute.isMatrix(slot) ? slot.getCount() : 0);
    }

    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void moxCostsFollowTheFuelTypesOwnEnrichment(GameTestHelper h) {
        Object[][] rows = {
                {"mox_lean", "mox_lean", 2, 4},
                {"mox_standard", "mox", 3, 4},
                {"mox_rich", "mox_rich", 4, 4},
        };
        for (Object[] row : rows) {
            String target = (String) row[0];
            h.assertTrue(FuelItemRoute.isTarget(target) && FuelItemRoute.isMoxTarget(target), target + " is not a MOX target");
            var cost = FuelItemRoute.cost(target);
            h.assertTrue(cost != null && cost.isMox(), target + " has no MOX cost");
            h.assertTrue(cost.fuelType().equals(row[1]), target + " makes " + cost.fuelType() + ", not " + row[1]);
            h.assertTrue(cost.plutoniumNuggets() == (int) row[2], target + " plutonium " + cost.plutoniumNuggets() + ", wanted " + row[2]);
            h.assertTrue(cost.matrix() == (int) row[3], target + " matrix " + cost.matrix() + ", wanted " + row[3]);
            h.assertTrue(cost.steel() == FuelItemRoute.STEEL_PER_ASSEMBLY && cost.ingots() == 0 && cost.tails() == 0, target + ": steel, uranium or returns");
            h.assertTrue(cost.workTicks() == FuelItemRoute.BASE_WORK_TICKS + FuelItemRoute.TICKS_PER_INGOT * cost.matrix(), target + " work time");
            // the plutonium is the fuel type's own enrichment through the core's balance, nothing else
            var type = FuelTypes.byNameOrPreset((String) row[1]);
            h.assertTrue(cost.plutoniumNuggets() == MoxMassBalance.plutoniumNuggets(4, type.nominalEnrichmentWeightFraction()), target + " does not follow the enrichment");
            // never less heavy metal than the assembly is stamped with
            h.assertTrue(cost.plutoniumNuggets() + 9 * cost.matrix() >= 36, target + " holds less than four ingots of heavy metal");
        }
        // the uranium ladder is as it was: mox itself is not a target, and the ladder's index range did not grow
        h.assertTrue(!FuelItemRoute.isTarget("mox") && FuelItemRoute.cost("mox") == null && FuelItemRoute.TARGETS.size() == 7, "the uranium ladder changed");
        h.assertTrue(FuelItemRoute.LADDER.size() == 10 && FuelItemRoute.LADDER.subList(0, 7).equals(FuelItemRoute.TARGETS), "the selector ladder is not the uranium ladder then the MOX grades");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void moxRouteConsumesExactlyThePlutoniumDepletedUraniumAndSteel(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(1200, 210, 1300);
            var be = place(l, p);
            h.assertTrue(be.setTarget("mox_standard") && be.isMoxTarget(), "target refused");
            h.assertTrue(feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 6)).isEmpty(), "steel not accepted");
            // two of the three nuggets, all the depleted uranium, all the steel: not enough, nothing is consumed
            h.assertTrue(feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 2)).isEmpty(), "plutonium not accepted");
            ticks(l, p, be, 3);
            h.assertTrue(feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4)).isEmpty(), "depleted uranium not accepted");
            ticks(l, p, be, 400);
            h.assertTrue(be.output().getStackInSlot(0).isEmpty() && be.itemWorkTicks() == 0, "an assembly from 2 of 3 plutonium nuggets");
            h.assertTrue(plutoniumHeld(be) == 2 && matrixHeld(be) == 4 && be.steelIn().getStackInSlot(0).getCount() == 6, "something was consumed by an assembly that did not happen");
            // short on the matrix: 3 nuggets but 3 depleted uranium (take one away by starting over)
            clear(l, p, be);
            var short1 = place(l, p);
            short1.setTarget("mox_standard");
            feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 4));
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 3));
            ticks(l, p, short1, 3);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 3));
            ticks(l, p, short1, 400);
            h.assertTrue(short1.output().getStackInSlot(0).isEmpty(), "an assembly from 3 of 4 depleted uranium");
            // and short on steel
            clear(l, p, short1);
            var short2 = place(l, p);
            short2.setTarget("mox_standard");
            feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 3));
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 3));
            ticks(l, p, short2, 3);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4));
            ticks(l, p, short2, 400);
            h.assertTrue(short2.output().getStackInSlot(0).isEmpty(), "an assembly from 3 of 4 steel");
            clear(l, p, short2);

            // the third nugget (and one over) completes it; extra depleted uranium and steel stay
            be = place(l, p);
            be.setTarget("mox_standard");
            feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 6));
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 2));
            ticks(l, p, be, 3);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4));
            ticks(l, p, be, 3);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 2));
            ticks(l, p, be, 3);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 2));
            int made = 0;
            for (int i = 0; i < 1000 && be.output().getStackInSlot(0).isEmpty(); i++) {
                tick(l, p, be);
                made++;
            }
            var assembly = be.output().getStackInSlot(0);
            h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()), "no assembly after " + made + " ticks");
            h.assertTrue(FuelAssemblies.dataOf(assembly).equals(FuelAssemblyData.fresh(FuelTypes.byNameOrPreset("mox"))), "not a fresh standard MOX assembly: " + FuelAssemblies.dataOf(assembly));
            // the work began on the first tick after the last nuggets went in, 3 ticks before the loop above started counting
            h.assertTrue(made + 3 >= FuelItemRoute.cost("mox_standard").workTicks(), "made in " + (made + 3) + " ticks, less than its work time");
            h.assertTrue(plutoniumHeld(be) == 4 - 3, "plutonium left " + plutoniumHeld(be) + ", wanted 1");
            h.assertTrue(matrixHeld(be) == 6 - 4, "depleted uranium left " + matrixHeld(be) + ", wanted 2");
            h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 6 - 4, "steel left " + be.steelIn().getStackInSlot(0).getCount() + ", wanted 2");
            h.assertTrue(be.tailsHeld() == 0 && be.uraniumHeldUnits() == 0, "a MOX assembly returned depleted uranium or held uranium");
            clear(l, p, be);

            // a plutonium ingot is nine nuggets: one ingot makes the assembly and six nuggets are left
            be = place(l, p);
            be.setTarget("mox_standard");
            feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 4));
            h.assertTrue(feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.PLUTONIUM_INGOT.get())).isEmpty(), "a plutonium ingot was refused");
            ticks(l, p, be, 3);
            h.assertTrue(plutoniumHeld(be) == 9, "an ingot is worth " + plutoniumHeld(be) + " nuggets");
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4));
            ticks(l, p, be, 400);
            h.assertTrue(be.output().getStackInSlot(0).is(BwrItems.FUEL_ASSEMBLY.get()) && plutoniumHeld(be) == 6 && matrixHeld(be) == 0, "an ingot did not make one assembly and leave six nuggets");
            clear(l, p, be);

            // the other two grades take their own amounts
            Object[][] grades = {{"mox_lean", "mox_lean", 2}, {"mox_rich", "mox_rich", 4}};
            for (Object[] grade : grades) {
                be = place(l, p);
                be.setTarget((String) grade[0]);
                feed(l, p, FabricatorItemView.STEEL, new ItemStack(STEEL, 4));
                feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, (int) grade[2] - 1));
                ticks(l, p, be, 3);
                feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4));
                ticks(l, p, be, 400);
                h.assertTrue(be.output().getStackInSlot(0).isEmpty(), grade[0] + ": an assembly from one nugget short");
                feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 1));
                ticks(l, p, be, 400);
                var made2 = be.output().getStackInSlot(0);
                h.assertTrue(made2.is(BwrItems.FUEL_ASSEMBLY.get()) && FuelAssemblies.dataOf(made2).fuelTypeName().equals(grade[1]), grade[0] + ": " + made2);
                h.assertTrue(plutoniumHeld(be) == 0 && matrixHeld(be) == 0 && be.steelIn().getStackInSlot(0).isEmpty(), grade[0] + ": inputs left over");
                clear(l, p, be);
            }
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theInputSlotTakesWhatTheTargetNeedsAndCannotBeJammed(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(1200, 210, 1310);
            var be = place(l, p);
            var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
            var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
            var north = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.NORTH);
            ItemStack gold = new ItemStack(Items.GOLD_INGOT);
            ItemStack nugget = new ItemStack(NUGGET);
            ItemStack ingot = new ItemStack(BwrItems.PLUTONIUM_INGOT.get());
            ItemStack du = new ItemStack(BwrItems.DEPLETED_URANIUM.get());
            // on a uranium target: uranium, and nothing of MOX
            for (var handler : java.util.List.of(up, north)) {
                h.assertTrue(handler.getSlots() == 4, "not four slots on a uranium target");
                h.assertTrue(handler.isItemValid(FabricatorItemView.URANIUM, gold), "uranium refused on a uranium target");
                h.assertTrue(!handler.isItemValid(FabricatorItemView.URANIUM, nugget) && !handler.isItemValid(FabricatorItemView.URANIUM, ingot)
                        && !handler.isItemValid(FabricatorItemView.URANIUM, du), "MOX feed accepted on a uranium target");
            }
            // on a MOX target: plutonium and depleted uranium, no uranium; still four slots, still from the top and the sides only
            be.setTarget("mox_rich");
            for (var handler : java.util.List.of(up, north)) {
                h.assertTrue(handler.getSlots() == 4, "not four slots on a MOX target");
                h.assertTrue(handler.isItemValid(FabricatorItemView.URANIUM, nugget) && handler.isItemValid(FabricatorItemView.URANIUM, ingot)
                        && handler.isItemValid(FabricatorItemView.URANIUM, du), "MOX feed refused on a MOX target");
                h.assertTrue(!handler.isItemValid(FabricatorItemView.URANIUM, gold), "uranium accepted on a MOX target");
                h.assertTrue(!handler.isItemValid(FabricatorItemView.STEEL, nugget), "plutonium accepted as steel");
            }
            h.assertTrue(down.insertItem(FabricatorItemView.URANIUM, nugget.copy(), false).getCount() == 1, "the bottom accepted plutonium");
            h.assertTrue(up.insertItem(FabricatorItemView.TAILS, du.copy(), false).getCount() == 1, "depleted uranium went into its output slot");
            h.assertTrue(up.extractItem(FabricatorItemView.URANIUM, 8, false).isEmpty(), "plutonium was pulled out by a pipe");
            // mox_rich: 4 plutonium (store held to 8) and 4 depleted uranium (held to 8). A full stack is taken only up to that.
            ItemStack rest = up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 64), false);
            h.assertTrue(rest.getCount() == 64 - 8, "a stack of depleted uranium was taken " + (64 - rest.getCount()) + " at a time, wanted 8");
            ticks(l, p, be, 2);
            h.assertTrue(be.matrixHeldIngots() == 8 && be.uraniumIn().getStackInSlot(0).isEmpty(), "the store did not take it out of the slot: " + be.matrixHeldIngots());
            // the matrix is full, so no more depleted uranium; but the slot is empty, so plutonium still gets in (it cannot jam)
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, du.copy(), false).getCount() == 1, "depleted uranium taken over the cap");
            h.assertTrue(be.uraniumIn().getStackInSlot(0).isEmpty(), "the slot is occupied by a refused item");
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, new ItemStack(NUGGET, 3), false).isEmpty(), "plutonium was refused with the matrix full");
            // while something sits in the slot, the other kind waits for it to be taken in
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, du.copy(), false).getCount() == 1, "depleted uranium taken with the matrix full");
            ticks(l, p, be, 2);
            h.assertTrue(plutoniumHeld(be) == 3, "plutonium held " + plutoniumHeld(be));
            // switching back to uranium: the MOX feed is no longer valid, what was held stays (it is dropped with the block)
            be.setTarget("leu");
            h.assertTrue(!up.isItemValid(FabricatorItemView.URANIUM, nugget) && up.isItemValid(FabricatorItemView.URANIUM, gold), "the slot did not go back to uranium");
            h.assertTrue(be.plutoniumHeldNuggets() == 3 && be.matrixHeldIngots() == 8, "switching target lost the stores");
            var drops = be.removalDrops();
            h.assertTrue(drops.stream().filter(s -> s.is(NUGGET)).mapToInt(ItemStack::getCount).sum() == 3
                    && drops.stream().filter(s -> s.is(BwrItems.DEPLETED_URANIUM.get())).mapToInt(ItemStack::getCount).sum() == 8, "breaking it did not drop the plutonium and depleted uranium: " + drops);
            h.assertTrue(be.plutoniumHeldNuggets() == 0 && be.matrixHeldIngots() == 0, "the stores were not emptied");
            l.removeBlock(p, false);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oldFabricatorSavesLoadAndTheMoxStateIsSavedOnlyWhenHeld(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = new BlockPos(1200, 210, 1320);
            var be = place(l, p);
            // a fabricator as alpha.48 saved it, using the uranium route: every key the item route had, none of MOX's
            var alpha48 = new CompoundTag();
            alpha48.putDouble("BatchHeavyMetalKg", 12.5);
            alpha48.putInt("Energy", 31_000);
            alpha48.putString("Target", "uranium_8");
            alpha48.putInt("UraniumUnits", 100);
            alpha48.putInt("TailsPending", 5);
            alpha48.putInt("WorkTicks", 7);
            var holder = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            holder.steelIn().setStackInSlot(0, new ItemStack(STEEL, 3));
            alpha48.put("SteelIn", holder.steelIn().serializeNBT(l.registryAccess()));
            alpha48.put("Feed", be.saveWithFullMetadata(l.registryAccess()).getCompound("Feed"));
            var loaded = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            loaded.loadWithComponents(alpha48, l.registryAccess());
            h.assertTrue(loaded.target().equals("uranium_8") && loaded.uraniumHeldUnits() == 100 && loaded.itemWorkTicks() == 7 && loaded.tailsHeld() == 5,
                    "an alpha.48 item route save did not load: " + loaded.target() + " " + loaded.uraniumHeldUnits());
            h.assertTrue(loaded.plutoniumHeldNuggets() == 0 && loaded.matrixHeldIngots() == 0 && !loaded.isMoxTarget(), "an old save came up with MOX state");
            h.assertTrue(loaded.steelIn().getStackInSlot(0).getCount() == 3 && loaded.energy().getEnergyStored() == 31_000 && loaded.batchHeavyMetalKg() == 12.5, "an old save lost its steel, energy or batch");
            var again = loaded.saveWithFullMetadata(l.registryAccess());
            h.assertTrue(!again.contains("PlutoniumNuggets") && !again.contains("MatrixIngots"), "an old fabricator wrote the MOX keys on re-save");
            h.assertTrue("uranium_8".equals(again.getString("Target")) && again.getInt("UraniumUnits") == 100 && again.getInt("WorkTicks") == 7, "re-save changed the uranium route's keys");

            // the fuel name is not a target, in a save as everywhere: it loads as the default
            for (String bad : new String[] {"mox", "plutonium", "MOX_STANDARD", "mox_extra"}) {
                var edited = new CompoundTag();
                edited.putString("Target", bad);
                var odd = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
                odd.loadWithComponents(edited, l.registryAccess());
                h.assertTrue(odd.target().equals("leu"), "a save with target '" + bad + "' loaded as " + odd.target());
            }

            // a fabricator on a MOX target with held plutonium and matrix saves them, and loads them back
            h.assertTrue(be.setTarget("mox_rich"), "mox_rich refused");
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 3));
            ticks(l, p, be, 2);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 5));
            ticks(l, p, be, 2);
            var tag = be.saveWithFullMetadata(l.registryAccess());
            h.assertTrue("mox_rich".equals(tag.getString("Target")) && tag.getInt("PlutoniumNuggets") == 3 && tag.getInt("MatrixIngots") == 5, "MOX state not saved: " + tag);
            var fresh = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
            fresh.loadWithComponents(tag, l.registryAccess());
            h.assertTrue(fresh.target().equals("mox_rich") && fresh.plutoniumHeldNuggets() == 3 && fresh.matrixHeldIngots() == 5 && fresh.isMoxTarget(), "MOX state did not load");
            h.assertTrue(fresh.itemCost().plutoniumNuggets() == 4 && fresh.itemCost().isMox(), "the loaded target's cost");
            // back on a uranium target with nothing held, the MOX keys are gone again
            fresh.removalDrops();
            fresh.setTarget("leu");
            var clean = fresh.saveWithFullMetadata(l.registryAccess());
            h.assertTrue(!clean.contains("PlutoniumNuggets") && !clean.contains("MatrixIngots") && !clean.contains("Target"), "empty MOX state was saved: " + clean);
            clear(l, p, be);
            h.succeed();
        } finally {
            real();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theSelectorCommandIsRangeCheckedAndTheLadderCommandKeepsItsRange(GameTestHelper h) throws Exception {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1330);
        var be = place(l, p);
        var player = FakePlayerFactory.getMinecraft(l);
        var menu = new FuelFabricatorMenu(8, player.getInventory(), p);
        for (int i = 0; i < FuelItemRoute.LADDER.size(); i++) {
            menu.handleCommand(player, FuelFabricatorMenu.CMD_SELECT, i, 0);
            h.assertTrue(be.target().equals(FuelItemRoute.LADDER.get(i)), "CMD_SELECT " + i + " selected " + be.target());
        }
        h.assertTrue(be.target().equals("mox_rich"), "the last step is not mox_rich");
        for (int bad : new int[] {-1, 10, 11, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            menu.handleCommand(player, FuelFabricatorMenu.CMD_SELECT, bad, 0);
            h.assertTrue(be.target().equals("mox_rich"), "forged select " + bad + " changed the target to " + be.target());
        }
        // the old command keeps its range: the uranium ladder only, 7 is still out of range
        menu.handleCommand(player, FuelFabricatorMenu.CMD_TARGET, 7, 0);
        h.assertTrue(be.target().equals("mox_rich"), "CMD_TARGET 7 selected " + be.target());
        menu.handleCommand(player, FuelFabricatorMenu.CMD_TARGET, 2, 0);
        h.assertTrue(be.target().equals("leu"), "CMD_TARGET 2 selected " + be.target());

        // the snapshot carries the MOX state to the client menu, and a uranium target carries none
        var method = FuelFabricatorMenu.class.getDeclaredMethod("writeSnapshot", FriendlyByteBuf.class);
        method.setAccessible(true);
        be.setTarget("mox_standard");
        standIns();
        try {
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(NUGGET, 2));
            ticks(l, p, be, 2);
            feed(l, p, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 1));
            ticks(l, p, be, 2);
        } finally {
            real();
        }
        var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        method.invoke(menu, buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        var client = new FuelFabricatorMenu(9, player.getInventory(), p);
        client.acceptSnapshot(bytes);
        h.assertTrue(client.present && client.moxTarget && client.targetIndex == FuelItemRoute.LADDER.indexOf("mox_standard"), "snapshot: " + client.moxTarget + " " + client.targetIndex);
        h.assertTrue(client.moxPlutonium == 3 && client.moxMatrix == 4 && client.moxPlutoniumHeld == 2 && client.moxMatrixHeld == 1, "snapshot MOX numbers: "
                + client.moxPlutonium + " " + client.moxMatrix + " " + client.moxPlutoniumHeld + " " + client.moxMatrixHeld);
        h.assertTrue(client.hasCost && client.costIngots == 0 && client.costSteel == 4 && client.costTails == 0 && client.costWorkTicks == 112, "snapshot cost");
        be.setTarget("uranium_8");
        buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        method.invoke(menu, buf);
        bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        client.acceptSnapshot(bytes);
        h.assertTrue(!client.moxTarget && client.targetIndex == 4 && client.costIngots == 68 && client.moxPlutonium == 0 && client.moxMatrixHeld == 0, "a uranium target's snapshot carries MOX state");
        be.removalDrops();
        l.removeBlock(p, false);
        h.succeed();
    }

    /**
     * The cycle closes: fourteen spent LEU assemblies go through the plant, the plutonium and depleted uranium it hands
     * back (as a hopper would carry them) go into the fabricator with steel, and a fresh MOX assembly comes out.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void spentFuelBecomesMoxFuelThroughThePlantAndTheFabricator(GameTestHelper h) {
        standIns();
        try {
            ServerLevel l = h.getLevel();
            BlockPos pp = new BlockPos(1200, 210, 1340);
            BlockPos fp = new BlockPos(1200, 210, 1345);
            l.getChunk(pp);
            l.removeBlock(pp, false);
            l.setBlock(pp, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 2);
            var plant = (ReprocessingPlantBlockEntity) l.getBlockEntity(pp);
            var fab = place(l, fp);
            fab.setTarget("mox_standard");
            var plantDown = l.getCapability(Capabilities.ItemHandler.BLOCK, pp, Direction.DOWN);
            var plantUp = l.getCapability(Capabilities.ItemHandler.BLOCK, pp, Direction.UP);
            int nuggets = 0;
            int depleted = 0;
            for (int n = 0; n < 14; n++) {
                ItemStack assembly = dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),
                        new FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0));
                h.assertTrue(plantUp.insertItem(ReprocessingItemView.INPUT, assembly, false).isEmpty(), "assembly " + n + " refused");
                for (int i = 0; i < 1000 && !plant.input().getStackInSlot(0).isEmpty(); i++) {
                    plant.energy().receiveEnergy(10_000, false);
                    ReprocessingPlantBlockEntity.serverTick(l, pp, l.getBlockState(pp), plant);
                }
                nuggets += plantDown.extractItem(ReprocessingItemView.PLUTONIUM, 64, false).getCount();
                depleted += plantDown.extractItem(ReprocessingItemView.URANIUM, 64, false).getCount();
                plantDown.extractItem(ReprocessingItemView.WASTE, 64, false);
            }
            h.assertTrue(nuggets == 3, "14 spent LEU assemblies gave " + nuggets + " plutonium nuggets, wanted 3 (265,255 grains each)");
            h.assertTrue(depleted >= 4, "and " + depleted + " depleted uranium");
            // carried to the fabricator
            feed(l, fp, FabricatorItemView.STEEL, new ItemStack(STEEL, 4));
            h.assertTrue(feed(l, fp, FabricatorItemView.URANIUM, new ItemStack(NUGGET, nuggets)).isEmpty(), "the fabricator refused the plant's plutonium");
            ticks(l, fp, fab, 3);
            h.assertTrue(feed(l, fp, FabricatorItemView.URANIUM, new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 4)).isEmpty(), "the fabricator refused the plant's depleted uranium");
            int made = 0;
            while (made < 1000 && fab.output().getStackInSlot(0).isEmpty()) {
                tick(l, fp, fab);
                made++;
            }
            var mox = fab.output().getStackInSlot(0);
            h.assertTrue(mox.is(BwrItems.FUEL_ASSEMBLY.get()) && FuelAssemblies.dataOf(mox).equals(FuelAssemblyData.fresh(FuelTypes.byNameOrPreset("mox"))),
                    "no fresh MOX assembly from the plant's output: " + mox);
            // and it is plain fuel: loaded into a core it is a MOX assembly like any other
            h.assertTrue(FuelAssemblies.toCore(FuelAssemblies.dataOf(mox)).fuelType().name().equals("mox"), "the assembly is not the mox fuel type");
            System.out.println("MOX CYCLE: 14 spent LEU assemblies -> " + nuggets + " plutonium nuggets (+" + plant.plutoniumGrains() / (double) Reprocessing.GRAINS_PER_NUGGET
                    + " held), " + depleted + " depleted uranium -> one MOX assembly");
            plant.removalDrops();
            l.removeBlock(pp, false);
            clear(l, fp, fab);
            h.succeed();
        } finally {
            real();
        }
    }
}
