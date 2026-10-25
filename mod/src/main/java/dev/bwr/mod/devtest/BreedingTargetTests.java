package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.BreedingTargets;
import dev.bwr.core.fuel.CoreInsert;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.fuel.CoreInsertData;
import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.fuel.SpecialtyRodItem;
import dev.bwr.mod.isotope.CarriedIsotopes;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.isotope.RtgFuels;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * The reactor-bred isotopes (BREEDING-TARGETS.md): every new target irradiated in a real controller's core (the exposure is
 * started 2 s from done, which is how the older target tests accelerate it) and harvested into its product; the products dosing
 * a carrier; the new RTG fuels powering a real RTG; the fuel list's masses against the core's physics; the recipes; a save.
 * Every test is in the batch {@code breeding}, so none of them runs beside another batch's radiation-source tests, and every
 * fixture has its own origin.
 */
@GameTestHolder("bwrbreeding")
@PrefixGameTestTemplate(false)
public final class BreedingTargetTests {

    private static final String NS = "bwrbreeding";
    private static final String BATCH = "breeding";

    private record Route(CoreInsert.Kind kind, Supplier<Item> product) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route(CoreInsert.Kind.BISMUTH_TARGET, BwrItems.POLONIUM_210_INGOT),
            new Route(CoreInsert.Kind.THORIUM_TARGET, BwrItems.URANIUM_233_INGOT),
            new Route(CoreInsert.Kind.URANIUM_TARGET, BwrItems.NEPTUNIUM_237_INGOT),
            new Route(CoreInsert.Kind.NEPTUNIUM_TARGET, BwrItems.PLUTONIUM_238_INGOT),
            new Route(CoreInsert.Kind.RADIUM_TARGET, BwrItems.ACTINIUM_227_INGOT),
            new Route(CoreInsert.Kind.THULIUM_TARGET, BwrItems.THULIUM_170_INGOT));

    // ------------------------------------------------------------------------------------------------------------
    // A target in a real core, to its product

    /**
     * Builds a real 5 x 5 controller core, fuels it, puts the target rod in the position {@code at}, runs the core until the rod is
     * complete and takes it out like a player does, then uses it. Its own fixture origin per route.
     */
    private static void irradiateAndHarvest(GameTestHelper h, int route) throws Exception {
        var spec = ROUTES.get(route);
        var level = h.getLevel();
        var origin = new BlockPos(80000 + 200 * route, 190, 80000);
        var be = CompactCoreRegressionTests.build(level, origin, 5, 5, 8, false);
        h.assertTrue(be.isFormed(), "the fixture reactor did not form: " + be.statusLines());
        int[] positions = be.corePositions();
        int at = positions[positions.length / 2];
        double left = 2.0;
        var rod = SpecialtyRodItem.stack(new CoreInsertData(spec.kind(), spec.kind().exposureSeconds - left));
        for (int p : positions) {
            h.assertTrue(p == at ? be.loadAssembly(p, rod) : be.loadAssembly(p, new ItemStack(BwrItems.FUEL_ASSEMBLY.get())), "load refused at " + p);
        }
        be.core().initialiseAtTotalPowerFraction(0.8);
        be.rods().setCore(be.core());
        var insert = be.core().getCoreLoading().insertAt(at);
        h.assertTrue(insert != null && insert.kind() == spec.kind() && !insert.complete(), "the target is not in the core, unfinished");
        double before = insert.exposureSeconds();
        be.core().step();
        h.assertTrue(insert.exposureSeconds() > before, spec.kind() + " gained no exposure from a core step of fission flux");
        h.assertTrue(be.core().getCoreLoading().assemblyThermalMW(be.core().getThermalPowerMW())[at] == 0.0, spec.kind() + " counted as fuel power");
        int steps = 1;
        while (!insert.complete() && steps < 4000) {
            be.core().step();
            steps++;
        }
        h.assertTrue(insert.complete(), spec.kind() + " did not complete in " + steps + " steps; exposure " + insert.exposureSeconds());
        h.assertTrue(insert.exposureSeconds() <= spec.kind().exposureSeconds, "exposure ran past the rated time");

        ItemStack out = be.unloadAssembly(at);
        h.assertTrue(out.is(BwrItems.SPECIALTY_ROD.get()) && SpecialtyRodItem.data(out).kind() == spec.kind() && SpecialtyRodItem.data(out).toCore().complete(),
                "the rod that came out of the core is not the finished " + spec.kind());

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        // a rod that is not finished yields nothing
        var partial = SpecialtyRodItem.stack(new CoreInsertData(spec.kind(), spec.kind().exposureSeconds * 0.5));
        player.setItemInHand(InteractionHand.MAIN_HAND, partial);
        partial.getItem().use(level, player, InteractionHand.MAIN_HAND);
        h.assertTrue(player.getInventory().countItem(spec.product().get()) == 0 && !partial.isEmpty(), "an unfinished " + spec.kind() + " was harvested");
        player.setItemInHand(InteractionHand.MAIN_HAND, out);
        BwrItems.SPECIALTY_ROD.get().use(level, player, InteractionHand.MAIN_HAND);
        h.assertTrue(player.getInventory().countItem(spec.product().get()) == 1, spec.kind() + " did not give exactly one product");
        h.assertTrue(player.getInventory().countItem(BwrItems.IRRADIATION_CASING.get()) == 1, spec.kind() + " did not return the casing");
        h.assertTrue(player.getMainHandItem().isEmpty() || !player.getMainHandItem().is(BwrItems.SPECIALTY_ROD.get()), "the rod was not consumed");
        System.out.println("BREEDING " + spec.kind() + ": complete after " + steps + " steps of the last 2 s; product " + spec.product().get().getDescriptionId());
        player.getInventory().clearContent();
        FixtureCleanup.drainFuel(level, origin.offset(-1, -2, -1), origin.offset(5, 8, 5));
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void bismuthTargetBreedsPolonium(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 0);
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void thoriumTargetBreedsUranium233(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 1);
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void depletedUraniumTargetBreedsNeptunium(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 2);
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void neptuniumTargetBreedsPlutonium238(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 3);
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void radiumTargetBreedsActinium(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 4);
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void thuliumTargetBreedsThulium170(GameTestHelper h) throws Exception {
        irradiateAndHarvest(h, 5);
    }

    /** A new kind's exposure, saved with the reactor and read back, is what it was; an old kind beside it too. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 300)
    public static void aNewTargetSurvivesSaveAndReload(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var origin = new BlockPos(80000, 190, 80400);
        var be = CompactCoreRegressionTests.build(l, origin, 5, 5, 8, false);
        int a = be.corePositions()[0];
        int b = be.corePositions()[1];
        var data = new CoreInsertData(CoreInsert.Kind.THULIUM_TARGET, 1234.5);
        var old = new CoreInsertData(CoreInsert.Kind.COBALT_TARGET, 321.0);
        h.assertTrue(be.loadAssembly(a, SpecialtyRodItem.stack(data)) && be.loadAssembly(b, SpecialtyRodItem.stack(old)), "target rejected from refuelling slot");
        var saved = be.saveWithFullMetadata(l.registryAccess());
        var pos = be.getBlockPos();
        var state = be.getBlockState();
        l.removeBlockEntity(pos);
        var restored = (ReactorControllerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state, saved, l.registryAccess());
        l.setBlockEntity(restored);
        var validate = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", net.minecraft.world.level.Level.class);
        validate.setAccessible(true);
        validate.invoke(restored, l);
        h.assertTrue(restored.isFormed(), "the core no longer forms");
        h.assertTrue(SpecialtyRodItem.data(restored.unloadAssembly(a)).equals(data), "the thulium target's exposure changed on reload");
        h.assertTrue(SpecialtyRodItem.data(restored.unloadAssembly(b)).equals(old), "the cobalt target's exposure changed on reload");
        FixtureCleanup.drainFuel(l, origin.offset(-1, -2, -1), origin.offset(5, 8, 5));
        // the stack codec used on the wire and the item component codec both carry every new kind
        for (var route : ROUTES) {
            var d = new CoreInsertData(route.kind(), 777.0);
            var restoredStack = ItemStack.parse(l.registryAccess(), SpecialtyRodItem.stack(d).save(l.registryAccess())).orElseThrow();
            h.assertTrue(SpecialtyRodItem.data(restoredStack).equals(d), route.kind() + " lost its exposure in an item save");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The products: dose, fuel list, RTG

    /** A carried product doses its carrier by grams x photon power: polonium hardly at all, the actinium's daughters strongly. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void productsDoseACarrierByTheirOwnGamma(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 at = player.position().add(1, 1, 0);
        double dist = player.position().add(0, 1, 0).distanceTo(at);
        h.assertTrue(CarriedIsotopes.doseRateOf(player, at) == 0.0, "an empty-handed player reads a dose");
        double[] dose = new double[4];
        Item[] items = {BwrItems.POLONIUM_210_INGOT.get(), BwrItems.PLUTONIUM_238_INGOT.get(), BwrItems.ACTINIUM_227_INGOT.get(), BwrItems.THULIUM_170_INGOT.get()};
        double[] gamma = {0.0002, 0.0002 * 0.05, 0.13, 0.08};
        double[] grams = {BreedingTargets.PO210_INGOT_GRAMS, BreedingTargets.PU238_INGOT_GRAMS, BreedingTargets.AC227_INGOT_GRAMS, BreedingTargets.TM170_INGOT_GRAMS};
        for (int i = 0; i < 4; i++) {
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(items[i]));
            dose[i] = CarriedIsotopes.doseRateOf(player, at);
            double expected = DoseModel.pointSourceMsvPerH(grams[i] * gamma[i], dist, 1.0);
            h.assertTrue(dose[i] > 0 && Math.abs(dose[i] / expected - 1.0) < 1e-9, items[i].getDescriptionId() + " reads " + dose[i] + " against " + expected);
        }
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(BwrItems.POLONIUM_210_INGOT.get(), 3));
        h.assertTrue(Math.abs(CarriedIsotopes.doseRateOf(player, at) / dose[0] - 3.0) < 1e-9, "three ingots do not read three times one");
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(BwrItems.POLONIUM_210_BLOCK.get()));
        h.assertTrue(Math.abs(CarriedIsotopes.doseRateOf(player, at) / dose[0] - 9.0) < 1e-9, "a block of polonium does not read nine ingots");
        // polonium is nearly gamma-free: a whole ingot is far below a carried radium source's dose, and the actinium daughters are strong per gram
        h.assertTrue(dose[0] < 0.1, "a polonium ingot reads " + dose[0] + " mSv/h");
        h.assertTrue(dose[2] / BreedingTargets.AC227_INGOT_GRAMS > 100 * dose[0] / BreedingTargets.PO210_INGOT_GRAMS, "actinium's daughters are not much stronger per gram than polonium");
        for (var plain : new Item[] {BwrItems.URANIUM_233_INGOT.get(), BwrItems.NEPTUNIUM_237_INGOT.get(), BwrItems.BISMUTH_NUGGET.get()}) {
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(plain));
            h.assertTrue(CarriedIsotopes.doseRateOf(player, at) == 0.0, plain.getDescriptionId() + " is not on the fuel list but dosed");
        }
        player.getInventory().clearContent();
        System.out.println("BREEDING carried dose at about 1 m: Po-210 ingot " + DoseModel.formatRate(dose[0]) + ", Pu-238 " + DoseModel.formatRate(dose[1])
                + ", Ac-227 " + DoseModel.formatRate(dose[2]) + ", Tm-170 " + DoseModel.formatRate(dose[3]));
        h.succeed();
    }

    /** The fuel list: each heat source has an ingot line and a block of nine, the masses are the core's physics, and the others are not fuel. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void theFuelListMassesAreTheCoresPhysics(GameTestHelper h) {
        Item[] ingots = {BwrItems.POLONIUM_210_INGOT.get(), BwrItems.PLUTONIUM_238_INGOT.get(), BwrItems.ACTINIUM_227_INGOT.get(), BwrItems.THULIUM_170_INGOT.get()};
        Item[] blocks = {BwrItems.POLONIUM_210_BLOCK.get(), BwrItems.PLUTONIUM_238_BLOCK.get(), BwrItems.ACTINIUM_227_BLOCK.get(), BwrItems.THULIUM_170_BLOCK.get()};
        CoreInsert.Kind[] kinds = {CoreInsert.Kind.BISMUTH_TARGET, CoreInsert.Kind.NEPTUNIUM_TARGET, CoreInsert.Kind.RADIUM_TARGET, CoreInsert.Kind.THULIUM_TARGET};
        double[] power = {140.0, 0.5674, 14.6, 11.5};
        for (int i = 0; i < 4; i++) {
            var ingot = RtgFuels.of(new ItemStack(ingots[i]));
            var block = RtgFuels.of(new ItemStack(blocks[i]));
            h.assertTrue(ingot != null && block != null, "no fuel line for " + ingots[i].getDescriptionId());
            h.assertTrue(Math.abs(block.gramsPerItem() / ingot.gramsPerItem() - 9.0) < 1e-6, "the block is not nine ingots: " + block.gramsPerItem() + " / " + ingot.gramsPerItem());
            h.assertTrue(ingot.specificPowerWPerG() == power[i] && block.specificPowerWPerG() == power[i] && ingot.halfLifeYears() > 0 && !ingot.isotope().isEmpty(), "the line's numbers: " + ingot);
            double made = BreedingTargets.cassetteYieldGrams(kinds[i]);
            h.assertTrue(Math.abs(ingot.gramsPerItem() / made - 1.0) < 0.03, ingots[i].getDescriptionId() + ": " + ingot.gramsPerItem() + " g in the list, the core's cassette makes " + made + " g");
        }
        double u233 = BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.THORIUM_TARGET);
        h.assertTrue(Math.abs(u233 / BreedingTargets.U233_INGOT_GRAMS - 1.0) < 0.03, "the U-233 ingot is not a thorium cassette's yield: " + u233);
        for (var plain : new Item[] {BwrItems.URANIUM_233_INGOT.get(), BwrItems.NEPTUNIUM_237_INGOT.get(), BwrItems.BISMUTH_NUGGET.get()}) {
            h.assertTrue(!RtgFuels.isFuel(new ItemStack(plain)), plain.getDescriptionId() + " is on the RTG fuel list");
        }
        h.succeed();
    }

    /** Each new fuel, six to an RTG: thermal power is six times grams x specific power, and the RTG makes electricity from it. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void newFuelsPowerAnRtg(GameTestHelper h) {
        Item[] fuels = {BwrItems.POLONIUM_210_INGOT.get(), BwrItems.PLUTONIUM_238_INGOT.get(), BwrItems.ACTINIUM_227_INGOT.get(), BwrItems.THULIUM_170_INGOT.get(),
                BwrItems.POLONIUM_210_BLOCK.get(), BwrItems.THULIUM_170_BLOCK.get()};
        BlockPos p = new BlockPos(1, 2, 1);
        h.setBlock(p, BwrBlocks.RTG.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(p);
        for (Item fuel : fuels) {
            var line = RtgFuels.of(new ItemStack(fuel));
            for (int i = 0; i < RtgBlockEntity.SLOTS; i++) {
                rtg.fuel().setStackInSlot(i, new ItemStack(fuel));
            }
            var r = rtg.readout();
            double thermal = 6 * line.gramsPerItem() * line.specificPowerWPerG();
            h.assertTrue(Math.abs(r.thermalWatts() / thermal - 1.0) < 1e-3, fuel.getDescriptionId() + ": " + r.thermalWatts() + " W thermal, expected " + thermal);
            h.assertTrue(Math.abs(r.electricWatts() / (thermal * 0.05) - 1.0) < 1e-3 && r.fePerTick() > 0, fuel.getDescriptionId() + ": electricity " + r);
            System.out.println("BREEDING RTG, six " + fuel.getDescriptionId() + ": " + String.format("%.3f", r.thermalWatts()) + " W thermal, " + String.format("%.4f", r.fePerTick()) + " FE/t, " + String.format("%.4g", r.doseAtOneMetreMsvPerH()) + " mSv/h at 1 m");
        }
        h.assertTrue(rtg.readout().thermalWatts() > 0, "no power");
        for (int i = 0; i < RtgBlockEntity.SLOTS; i++) {
            rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.URANIUM_233_INGOT.get()));
        }
        h.assertTrue(rtg.readout().thermalWatts() == 0.0, "uranium-233 made RTG heat");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // U-233 in the thorium fuel route

    private static FuelFabricatorBlockEntity placeFabricator(ServerLevel l, BlockPos p) {
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        return (FuelFabricatorBlockEntity) l.getBlockEntity(p);
    }

    private static void tickFabricator(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    /**
     * A thorium assembly from bred U-233: its cost is the driver mass in whole U-233 ingots, no uranium goes in and no depleted
     * uranium comes out, and the stamped fuel is the same as the other route stamps. Eight, twelve and nineteen ingots at 2, 3 and 5 %.
     */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void u233ReplacesTheEnrichedDriverInTheThoriumRoute(GameTestHelper h) {
        FuelItemRoute.overrideInputsForTest(null, stack -> stack.is(Items.IRON_INGOT));
        try {
            ServerLevel l = h.getLevel();
            String[][] rows = {{"thorium_lean", "thorium_lean", "8"}, {"thorium_standard", "thorium", "12"}, {"thorium_rich", "thorium_rich", "19"}};
            int at = 0;
            for (String[] row : rows) {
                var alt = FuelItemRoute.u233Cost(row[0]);
                var usual = FuelItemRoute.cost(row[0]);
                h.assertTrue(alt != null && alt.u233Ingots() == Integer.parseInt(row[2]) && alt.thoriumIngots() == 4 && alt.steel() == 4, row[0] + ": U-233 cost " + alt);
                h.assertTrue(usual.isThorium() && usual.ingots() > 0, row[0] + ": the usual cost changed: " + usual);
                BlockPos p = new BlockPos(80000 + 4 * at, 210, 80800);
                var be = placeFabricator(l, p);
                h.assertTrue(be.setTarget(row[0]) && be.isThoriumTarget(), row[0] + " refused");
                var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
                h.assertTrue(up.insertItem(FabricatorItemView.STEEL, new ItemStack(Items.IRON_INGOT, alt.steel() + 1), false).isEmpty(), "steel refused");
                int extra = 2, fedU = 0, fedT = 0, ticks = 0;
                while (ticks < 6000 && be.output().getStackInSlot(0).isEmpty()) {
                    if (fedT < alt.thoriumIngots() + 1 && up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.THORIUM_INGOT.get()), false).isEmpty()) {
                        fedT++;
                    }
                    if (fedU < alt.u233Ingots() + extra && up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_233_INGOT.get()), false).isEmpty()) {
                        fedU++;
                    }
                    tickFabricator(l, p, be);
                    ticks++;
                }
                var assembly = be.output().getStackInSlot(0);
                h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()), row[0] + ": no assembly after " + ticks + " ticks (fed " + fedT + " thorium, " + fedU + " U-233)");
                var type = FuelTypes.byNameOrPreset(row[1]);
                h.assertTrue(FuelAssemblies.dataOf(assembly).equals(dev.bwr.mod.fuel.FuelAssemblyData.fresh(type)), row[0] + ": not the fresh bundle the other route stamps");
                h.assertTrue(ticks >= alt.workTicks(), row[0] + ": made in " + ticks + " ticks, under its work time " + alt.workTicks());
                ItemStack slot = be.uraniumIn().getStackInSlot(0);
                int u233Left = be.u233HeldIngots() + (slot.is(BwrItems.URANIUM_233_INGOT.get()) ? slot.getCount() : 0);
                int thoriumLeft = be.thoriumHeldIngots() + (slot.is(BwrItems.THORIUM_INGOT.get()) ? slot.getCount() : 0);
                h.assertTrue(fedU == alt.u233Ingots() + extra && u233Left == extra, row[0] + ": U-233 left " + u233Left + ", wanted " + extra);
                h.assertTrue(thoriumLeft == 1, row[0] + ": thorium left " + thoriumLeft);
                h.assertTrue(be.uraniumHeldUnits() == 0 && be.tailsHeld() == 0, row[0] + ": uranium was taken or depleted uranium made");
                h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 1, row[0] + ": steel left " + be.steelIn().getStackInSlot(0).getCount());
                System.out.println("BREEDING U-233 route " + row[0] + ": " + alt.u233Ingots() + " U-233 + " + alt.thoriumIngots() + " thorium + " + alt.steel() + " steel in " + ticks + " ticks, no depleted uranium");
                be.removalDrops();
                l.removeBlock(p, false);
                at++;
            }
            h.succeed();
        } finally {
            FuelItemRoute.overrideInputsForTest(null, null);
        }
    }

    /** The U-233 store saves only when it holds some, an old save has none, the uranium ladder refuses U-233, and a thorium target never takes more than two assemblies' worth. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 200)
    public static void u233StoreSavesRefusesOffRouteAndNeverJams(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(80000, 210, 80900);
        var be = placeFabricator(l, p);
        var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
        h.assertTrue(!up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_233_INGOT.get()), true).isEmpty(), "U-233 went into the uranium ladder's slot");
        h.assertTrue(be.setTarget("thorium_lean"), "target refused");
        h.assertTrue(!be.saveWithFullMetadata(l.registryAccess()).contains("U233Ingots"), "an empty store was saved");
        int cap = 2 * FuelItemRoute.u233Cost("thorium_lean").u233Ingots();
        int taken = 0;
        for (int i = 0; i < 100; i++) {
            var rest = up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_233_INGOT.get(), 5), false);
            taken += 5 - rest.getCount();
            tickFabricator(l, p, be);
        }
        h.assertTrue(be.u233HeldIngots() + be.uraniumIn().getStackInSlot(0).getCount() == taken && taken <= cap && taken >= cap - 5, "took " + taken + " U-233 against a store of " + cap);
        var saved = be.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(saved.getInt("U233Ingots") == be.u233HeldIngots() && be.u233HeldIngots() > 0, "the store was not saved: " + saved.getInt("U233Ingots"));
        var fresh = (FuelFabricatorBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p, l.getBlockState(p), saved, l.registryAccess());
        h.assertTrue(fresh.u233HeldIngots() == be.u233HeldIngots() && fresh.target().equals("thorium_lean"), "the store did not load");
        saved.remove("U233Ingots");
        var old = (FuelFabricatorBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p, l.getBlockState(p), saved, l.registryAccess());
        h.assertTrue(old.u233HeldIngots() == 0, "an old save loaded U-233");
        be.removalDrops();
        be.setTarget("leu");
        h.assertTrue(!up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_233_INGOT.get()), true).isEmpty(), "U-233 was taken on the uranium ladder");
        l.removeBlock(p, false);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Recipes

    private static CraftingRecipe recipe(GameTestHelper h, String id) {
        var r = h.getLevel().getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("bwr", id));
        h.assertTrue(r.isPresent(), "no recipe bwr:" + id);
        return (CraftingRecipe) r.get().value();
    }

    private static ItemStack craft(GameTestHelper h, String id, ItemStack... items) {
        var grid = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i = 0; i < items.length; i++) {
            grid.set(i, items[i]);
        }
        var input = CraftingInput.of(3, 3, grid);
        var recipe = recipe(h, id);
        h.assertTrue(recipe.matches(input, h.getLevel()), "bwr:" + id + " does not match its own ingredients");
        return recipe.assemble(input, h.getLevel().registryAccess());
    }

    /** A casing and the ore element make the rod; the rod is the right kind with no exposure; blocks and ingots go both ways. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void recipesMakeEveryTargetAndTheBlocks(GameTestHelper h) {
        ItemStack casing = new ItemStack(BwrItems.IRRADIATION_CASING.get());
        ItemStack[][] inputs = {
                withCasing(BwrItems.BISMUTH_NUGGET.get(), 2),
                withCasing(BwrItems.THORIUM_INGOT.get(), 1),
                withCasing(BwrItems.DEPLETED_URANIUM.get(), 1),
                withCasing(BwrItems.NEPTUNIUM_237_INGOT.get(), 8),
                withCasing(BwrItems.RADIUM.get(), 8),
                withCasing(BwrItems.RARE_EARTH_CONCENTRATE.get(), 8)};
        for (int i = 0; i < ROUTES.size(); i++) {
            var kind = ROUTES.get(i).kind();
            ItemStack made = craft(h, kind.id(), inputs[i]);
            h.assertTrue(made.is(BwrItems.SPECIALTY_ROD.get()) && SpecialtyRodItem.data(made).equals(new CoreInsertData(kind, 0)), kind.id() + " made " + made);
        }
        // a missing ingredient is not enough, and the wrong element is not the right one
        var grid = NonNullList.withSize(9, ItemStack.EMPTY);
        grid.set(0, casing);
        grid.set(1, new ItemStack(BwrItems.BISMUTH_NUGGET.get()));
        h.assertTrue(!recipe(h, "bismuth_target").matches(CraftingInput.of(3, 3, grid), h.getLevel()), "one bismuth nugget made a rod");
        grid.set(1, new ItemStack(BwrItems.LEAD_NUGGET.get()));
        grid.set(2, new ItemStack(BwrItems.LEAD_NUGGET.get()));
        h.assertTrue(!recipe(h, "bismuth_target").matches(CraftingInput.of(3, 3, grid), h.getLevel()), "two lead nuggets made a bismuth rod");
        for (String iso : new String[] {"polonium_210", "plutonium_238", "actinium_227", "thulium_170"}) {
            var ingot = BuiltInRegistriesItem(iso + "_ingot");
            var block = BuiltInRegistriesItem(iso + "_block");
            ItemStack nine = craft(h, iso + "_ingot_from_block", new ItemStack(block));
            h.assertTrue(nine.is(ingot) && nine.getCount() == 9, iso + ": a block is not nine ingots");
            ItemStack[] ingots = new ItemStack[9];
            java.util.Arrays.fill(ingots, new ItemStack(ingot));
            h.assertTrue(craft(h, iso + "_block", ingots).is(block), iso + ": nine ingots are not a block");
        }
        h.succeed();
    }

    private static Item BuiltInRegistriesItem(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("bwr", id));
    }

    private static ItemStack[] withCasing(Item item, int n) {
        ItemStack[] out = new ItemStack[n + 1];
        out[0] = new ItemStack(BwrItems.IRRADIATION_CASING.get());
        for (int i = 1; i <= n; i++) {
            out[i] = new ItemStack(item);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Bismuth in uranium ore

    private static java.util.List<ItemStack> mine(ServerLevel l, net.minecraft.world.level.block.state.BlockState state, ItemStack tool) {
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(l)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, state)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, tool)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK);
        return l.getServer().reloadableRegistries().getLootTable(state.getBlock().getLootTable()).getRandomItems(params);
    }

    private static ItemStack enchanted(ServerLevel l, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key, int level) {
        ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
        stack.enchant(l.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key), level);
        return stack;
    }

    private static double bismuthShare(ServerLevel l, net.minecraft.world.level.block.state.BlockState s, ItemStack tool, int draws) {
        int hits = 0;
        for (int i = 0; i < draws; i++) {
            if (mine(l, s, tool).stream().anyMatch(d -> d.is(BwrItems.BISMUTH_NUGGET.get()))) {
                hits++;
            }
        }
        return hits / (double) draws;
    }

    /** Bismuth lies with uranium in the Bi-Co-Ni-Ag-U veins: a chance of one or two nuggets, raised by Fortune, never with Silk Touch. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 400)
    public static void uraniumOreGivesBismuthByChanceFortuneHelpsAndSilkTouchSkipsIt(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
        ItemStack silk = enchanted(l, net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH, 1);
        ItemStack fortune = enchanted(l, net.minecraft.world.item.enchantment.Enchantments.FORTUNE, 3);
        for (var ore : new net.minecraft.world.level.block.Block[] {BwrBlocks.URANIUM_ORE.get(), BwrBlocks.DEEPSLATE_URANIUM_ORE.get()}) {
            var s = ore.defaultBlockState();
            double plain = bismuthShare(l, s, iron, 6000);
            double fort = bismuthShare(l, s, fortune, 6000);
            h.assertTrue(Math.abs(plain - 0.05) < 0.02, ore + ": bismuth drops " + plain + " of the time, not about 0.05");
            h.assertTrue(Math.abs(fort - 0.10) < 0.025, ore + ": with Fortune III bismuth drops " + fort + " of the time, not about 0.10");
            h.assertTrue(fort > plain, ore + ": Fortune did not raise the chance of bismuth");
            for (int i = 0; i < 500; i++) {
                var drops = mine(l, s, silk);
                h.assertTrue(drops.size() == 1 && drops.get(0).is(ore.asItem()), ore + ": Silk Touch gave " + drops);
            }
            for (int i = 0; i < 3000; i++) {
                for (var d : mine(l, s, iron)) {
                    if (d.is(BwrItems.BISMUTH_NUGGET.get())) {
                        h.assertTrue(d.getCount() >= 1 && d.getCount() <= 2, "a bismuth drop of " + d.getCount());
                    }
                }
            }
        }
        h.succeed();
    }
}
