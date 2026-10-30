package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.power.OilFilterCartridgeItem;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Cartridge grades, staged filtration and the bearing oil flow alarms (TURBINE-AUX.md): the coarse, standard and fine cartridge, filter
 * housings in series (a coarse one protecting a fine one) and in parallel (two fine ones feed a whole train, one does not), the oil's
 * cleanliness and what dirty oil does, and the flow readings a low tank, a loaded cartridge or a filter that cannot pass the flow bring.
 * Namespace {@code bwroil}, on the generator fixture of {@link TurbineAuxTests}; the piped layout is the one of {@link OilPipingTests}.
 */
@GameTestHolder("bwroil")
@PrefixGameTestTemplate(false)
public final class OilGradeTests {
    private OilGradeTests() {}

    @FunctionalInterface
    private interface Body {
        void run(TurbineAuxTests.Fx f) throws Exception;
    }

    private static void run(GameTestHelper h, int x, Body body) {
        var l = h.getLevel();
        var root = new BlockPos(x, 245, 81000);
        EntityLookupProbe.runWhenLive(h, TurbineAuxTests.Fx.bounds(root), () -> {
            long time = l.getGameTime();
            TurbineAuxTests.Fx f = null;
            try {
                f = new TurbineAuxTests.Fx(l, root);
                body.run(f);
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    // ------------------------------------------------------------------ layout helpers (the piped train of OilPipingTests)

    private static BlockPos at(TurbineAuxTests.Fx f, int dx, int dy, int dz) { return f.root.offset(dx, dy, dz); }

    private static void pipes(TurbineAuxTests.Fx f, BlockPos... points) {
        for (int i = 0; i + 1 < points.length; i++)
            for (var p : BlockPos.betweenClosed(points[i], points[i + 1])) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3);
        for (var p : points) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().stateWithConnections(f.l, p), 3);
    }

    /** Works out again which arms the pipes at these cells have, once what stands beside them has been placed. */
    private static void resolve(TurbineAuxTests.Fx f, BlockPos... cells) {
        for (var p : cells) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().stateWithConnections(f.l, p), 3);
    }

    private static TurbineAuxBlockEntity roomTank(TurbineAuxTests.Fx f, int litres) {
        f.l.setBlock(at(f, 0, 0, 8), BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
        TurbineAuxBlock.attach(f.l, at(f, 0, 0, 8));
        var tank = (TurbineAuxBlockEntity) f.l.getBlockEntity(at(f, 0, 0, 8));
        tank.addOil(litres);
        tank.setAcPump(true);
        tank.setDcPump(true);
        return tank;
    }

    /** The piped train: a tank in the other room (AC and DC pumps on), the supply line along z = 8 then north to the generator, the drain back. */
    private static TurbineAuxBlockEntity pipedTrain(TurbineAuxTests.Fx f, int litres) {
        var tank = roomTank(f, litres);
        f.gen.rpm = 1500;
        pipes(f, at(f, 0, 0, -3), at(f, 6, 0, -3), at(f, 6, 0, 8), at(f, 1, 0, 8));
        pipes(f, at(f, 0, 0, 3), at(f, -1, 0, 3), at(f, -1, 0, 8));
        return tank;
    }

    private static void feed(TurbineAuxBlockEntity tank) {
        tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
        tank.dcEnergy.receiveEnergy(TurbineAuxBlockEntity.DC_CAPACITY_FE, false);
    }

    private static ItemStack cartridge(int grade, double load) {
        var s = new ItemStack(grade == TurbineAux.GRADE_COARSE ? BwrItems.OIL_FILTER_CARTRIDGE_COARSE.get()
                : grade == TurbineAux.GRADE_FINE ? BwrItems.OIL_FILTER_CARTRIDGE_FINE.get() : BwrItems.OIL_FILTER_CARTRIDGE.get());
        OilFilterCartridgeItem.setLoad(s, load);
        return s;
    }

    /** A housing on the return line, the oil leaving it to the south (toward the tank) holding a cartridge of a grade in side A. */
    private static TurbineAuxBlockEntity housingAt(TurbineAuxTests.Fx f, BlockPos pos, int grade, double load) {
        f.l.setBlock(pos, BwrBlocks.OIL_FILTER_HOUSING.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.SOUTH), 3);
        var housing = (TurbineAuxBlockEntity) f.l.getBlockEntity(pos);
        housing.cartridgeSlot().setStackInSlot(0, cartridge(grade, load));
        return housing;
    }

    // ------------------------------------------------------------------ the standard path

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void theStandardCartridgeAndNoCartridgeLeaveTheOilAndTheFlowAsTheyWere(GameTestHelper h) {
        run(h, 87000, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var gs = SignalProviders.of(f.gen);
            f.tick(f.gen, 200);
            h.assertTrue(tank.cleanliness() == TurbineAux.CLEANLINESS_DEFAULT && tank.dirtExcess() == 0, "no housing: the oil sits at its default cleanliness, exactly");
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 1.0) < 0.02 && f.gen.aux().oilFlowGood && !f.gen.aux().oilFlowLowAlarm && !f.gen.aux().oilFlowVeryLowAlarm,
                    "the shaft pump alone at rated speed reads good (100 percent): " + f.gen.aux().oilFlow);
            h.assertTrue(gs.read("oil_flow_good").getAsDouble() == 1 && gs.read("oil_flow_low_alarm").getAsDouble() == 0 && gs.read("oil_flow_very_low_alarm").getAsDouble() == 0,
                    "and the signals say so");
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_STANDARD, 0));
            f.tick(f.gen, 3000);
            h.assertTrue(tank.cleanliness() == TurbineAux.CLEANLINESS_DEFAULT, "with a standard cartridge the oil stays at its default cleanliness, exactly: " + tank.cleanliness());
            h.assertTrue(!tank.flowLimited() && Double.isInfinite(tank.filterCapacity()) && !f.gen.aux().oilFlowRestricted, "a standard cartridge never holds the pumps back");
            h.assertTrue(f.gen.aux().oilFlowGood && f.gen.shaftDamage() == 0, "the flow still reads good and the shaft is not worn: " + f.gen.shaftDamage());
            h.assertTrue(f.gen.aux().oilCleanliness == TurbineAux.CLEANLINESS_DEFAULT && f.gen.aux().oilDirt == 0, "the train reads the default cleanliness");
            var tag = tank.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!tag.contains("OilDirt"), "and an oil at its default saves no key");
        });
    }

    // ------------------------------------------------------------------ the flow alarms and the return line

    /**
     * Housings in parallel on the return line, between the generator's drain and the tank (the drain run goes south along x = -1 from z 3 to z 8,
     * the oil leaving each housing toward the tank, so they face south): {@code n} housings at x = -1 .. -n, z = 5, holding cartridges of
     * {@code grade} at {@code load}, between a row of pipe at z = 4 and one at z = 6.
     */
    private static java.util.List<TurbineAuxBlockEntity> bank(TurbineAuxTests.Fx f, int n, int grade, double load) {
        var out = new java.util.ArrayList<TurbineAuxBlockEntity>();
        for (int i = 0; i < n; i++) {
            int x = -1 - i;
            if (i > 0) { pipes(f, at(f, x, 0, 4), at(f, x, 0, 4)); pipes(f, at(f, x, 0, 6), at(f, x, 0, 6)); }
            out.add(housingAt(f, at(f, x, 0, 5), grade, load));
        }
        for (int i = 0; i < n; i++) { resolve(f, at(f, -1 - i, 0, 4), at(f, -1 - i, 0, 6)); }
        return out;
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 800)
    public static void oneFineHousingStarvesAFullTrainAndItTakesFourInParallel(GameTestHelper h) {
        run(h, 87100, f -> {
            var tank = pipedTrain(f, 10_000);
            var gs = SignalProviders.of(f.gen);
            var ts = SignalProviders.of(tank);
            f.tick(f.gen, 80, () -> feed(tank));
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 1.15) < 0.01 && f.gen.aux().oilFlowGood, "the AC and DC pumps together read 115 percent with no housing: " + f.gen.aux().oilFlow);
            var fine1 = housingAt(f, at(f, -1, 0, 5), TurbineAux.GRADE_FINE, 0);
            f.tick(f.gen, 80, () -> feed(tank));
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 0.3) < 0.01, "one fine housing on the return line passes 30 percent of a train's rated flow: " + f.gen.aux().oilFlow);
            h.assertTrue(f.gen.aux().oilFlowLowAlarm && f.gen.aux().oilFlowVeryLowAlarm && !f.gen.aux().oilFlowGood && f.gen.aux().oilFlowRestricted, "low and very low alarms, held back");
            h.assertTrue(gs.read("oil_flow_low_alarm").getAsDouble() == 1 && gs.read("oil_flow_very_low_alarm").getAsDouble() == 1 && gs.read("oil_flow_good").getAsDouble() == 0
                    && gs.read("oil_flow_restricted").getAsDouble() == 1, "the section's signals");
            h.assertTrue(Math.abs(gs.read("oil_flow").getAsDouble() - 0.3) < 0.01 && ts.read("oil_flow").isEmpty(), "the flow signal on the section (a tank in another room is on no train: no signal there)");
            h.assertTrue(ts.read("flow_limited").getAsDouble() == 1 && Math.abs(ts.read("filter_flow_limit").getAsDouble() - 0.3) < 0.01, "the tank reads the filter's limit");
            h.assertTrue(f.gen.aux().oilPsig < 8 && f.gen.aux().oilFilm < 0.35, "the starved bearings see the pressure and the film fall: " + f.gen.aux().oilPsig + " " + f.gen.aux().oilFilm);
            // More fine housings in parallel: 2 pass 0.6, 3 pass 0.9, 4 pass 1.2, enough for the 1.15 the pumps deliver.
            double[] expect = {0.3, 0.6, 0.9, 1.15};
            for (int n = 2; n <= 4; n++) {
                var all = bank(f, n, TurbineAux.GRADE_FINE, 0);
                f.tick(f.gen, 80, () -> feed(tank));
                h.assertTrue(Math.abs(f.gen.aux().oilFlow - expect[n - 1]) < 0.02, n + " fine housings in parallel give " + expect[n - 1] + ": " + f.gen.aux().oilFlow);
            }
            h.assertTrue(f.gen.aux().oilFlowGood && !f.gen.aux().oilFlowLowAlarm && !f.gen.aux().oilFlowRestricted, "four feed the whole train");
            // Each carries about a quarter of the flow, so each loads about a quarter as fast as one alone would.
            var four = bank(f, 4, TurbineAux.GRADE_FINE, 0);
            f.tick(f.gen, 400, () -> feed(tank));
            double l0 = four.get(0).filterLoad(0), l3 = four.get(3).filterLoad(0);
            h.assertTrue(l3 > 0 && Math.abs(l0 - l3) < 0.2 * l3, "the last one loads, and about as much as the first: " + l0 + " " + l3);
            // Loading drives the flow down: four housings with their elements 90 percent full pass 0.39 between them.
            for (var each : four) each.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_FINE, 0.9));
            f.tick(f.gen, 80, () -> feed(tank));
            double loaded = TurbineAux.filterFlowCapacity(TurbineAux.GRADE_FINE, 0.9) * 4;
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - loaded) < 0.02 && f.gen.aux().oilFlowVeryLowAlarm, "loaded cartridges pass less and the alarm comes back: " + f.gen.aux().oilFlow + " against " + loaded);
            for (var each : four) each.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_STANDARD, 0));
            f.tick(f.gen, 80, () -> feed(tank));
            h.assertTrue(f.gen.aux().oilFlow > 1.1 && f.gen.aux().oilFlowGood && !f.gen.aux().oilFlowRestricted, "standard cartridges in the same housings do not limit: " + f.gen.aux().oilFlow);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aFineCartridgeBoltedOnTheTankHoldsBackTheShaftPumpsOilToo(GameTestHelper h) {
        run(h, 87200, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 80);
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 1.0) < 0.02 && f.gen.aux().oilFlowGood, "the shaft pump alone at rated speed reads 100 percent");
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_FINE, 0));
            f.tick(f.gen, 80);
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 0.3) < 0.01 && tank.flowLimited(), "every pump's oil goes back through the return line: a fine cartridge there holds even the shaft pump to 30 percent: " + f.gen.aux().oilFlow);
            // The transfer valve: a coarse cartridge on the other side, thrown into service, clears it.
            housing.cartridgeSlot().setStackInSlot(1, cartridge(TurbineAux.GRADE_COARSE, 0));
            housing.selectSide(true);
            f.tick(f.gen, 80);
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - 1.0) < 0.02 && !tank.flowLimited(), "thrown over to a coarse cartridge the full flow is back: " + f.gen.aux().oilFlow);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aLowTankLevelReducesTheBearingFlow(GameTestHelper h) {
        run(h, 87300, f -> {
            var tank = pipedTrain(f, 10_000);
            f.tick(f.gen, 80, () -> feed(tank));
            h.assertTrue(f.gen.aux().oilFlowGood && Math.abs(f.gen.aux().oilFlow - 1.15) < 0.01, "a full tank: good, " + f.gen.aux().oilFlow);
            tank.drainOil(9_700);
            f.tick(f.gen, 80, () -> feed(tank));
            double expect = 1.15 * TurbineAux.suction(tank.oilLitres());
            h.assertTrue(Math.abs(f.gen.aux().oilFlow - expect) < 0.02 && expect < 0.5, "300 L is 30 percent of the 5 percent suction level, so the pumps lose it: " + f.gen.aux().oilFlow + " against " + expect);
            h.assertTrue(f.gen.aux().oilFlowLowAlarm && f.gen.aux().oilFlowVeryLowAlarm && !f.gen.aux().oilFlowRestricted, "low and very low, and the filters are not why");
            h.assertTrue(SignalProviders.of(f.gen).read("oil_level_alarm").getAsDouble() == 1, "with the level alarm");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 500)
    public static void eachGradeHasItsOwnOilLossFactorAndTheBestOnTheWayThroughCounts(GameTestHelper h) {
        run(h, 87350, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            f.tick(f.gen, 40);
            h.assertTrue(tank.lossFactor() == 1, "an empty housing leaves all of the loss");
            double[] want = {0.2, 0.1, 0.03};
            for (int g = 0; g < 3; g++) {
                housing.cartridgeSlot().setStackInSlot(0, cartridge(g, 0));
                f.tick(f.gen, 5);
                h.assertTrue(tank.lossFactor() == want[g], TurbineAux.GRADE_NAMES[g] + " leaves " + want[g] + ": " + tank.lossFactor());
            }
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_STANDARD, 0));
            f.tick(f.gen, 5);
            h.assertTrue(tank.lossFactor() == TurbineAux.OIL_FILTER_FACTOR && tank.filterCapacity() == Double.POSITIVE_INFINITY, "the standard grade is the old factor to the bit and never limits");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_FINE, 1));
            f.tick(f.gen, 5);
            h.assertTrue(tank.lossFactor() == 1, "a full cartridge, bypassed, leaves all of it");
        });
    }

    // ------------------------------------------------------------------ series: coarse then fine

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 900)
    public static void aCoarseCartridgeInFrontProtectsAFineOneBehindItOnTheReturnLine(GameTestHelper h) {
        run(h, 87400, f -> {
            var tank = pipedTrain(f, 10_000);
            var ts = SignalProviders.of(tank);
            // The fine housing nearest the tank (z 6); the coarse one goes in front of it (z 5), the oil running south to the tank.
            var fine = housingAt(f, at(f, -1, 0, 6), TurbineAux.GRADE_FINE, 0);
            f.tick(f.gen, 6000, () -> feed(tank));
            double alone = fine.filterLoad(0);
            h.assertTrue(alone > 0.0005, "the fine cartridge alone loaded: " + alone);
            fine.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_FINE, 0));
            var coarse = housingAt(f, at(f, -1, 0, 5), TurbineAux.GRADE_COARSE, 0);
            h.assertTrue(OilLineNetwork.filterGraph(f.l, tank.getBlockPos(), Direction.WEST).edges.size() == 2, "the graph of the return run holds both housings");
            f.tick(f.gen, 6000, () -> feed(tank));
            double behind = fine.filterLoad(0);
            h.assertTrue(behind > 0 && behind < alone * 0.35, "behind a coarse one the fine cartridge loads under a third as fast: " + behind + " against " + alone);
            h.assertTrue(coarse.filterLoad(0) > 0, "and the coarse one takes the load: " + coarse.filterLoad(0));
            h.assertTrue(Math.abs(ts.read("filter_flow_limit").getAsDouble() - TurbineAux.filterFlowCapacity(TurbineAux.GRADE_FINE, behind)) < 0.01,
                    "the run passes what the tightest housing in it does");
            h.assertTrue(tank.lossFactor() == 0.03, "and leaves the best factor on the way through: " + tank.lossFactor());
            long builds = OilLineNetwork.graphBuildCount();
            f.tick(f.gen, 200, () -> feed(tank));
            h.assertTrue(OilLineNetwork.graphBuildCount() == builds, "an unchanged run is not walked again");
        });
    }

    // ------------------------------------------------------------------ cleanliness and what dirty oil does

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 800)
    public static void theGradeSetsWhereTheOilsCleanlinessGoes(GameTestHelper h) {
        run(h, 87500, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_COARSE, 0));
            f.tick(f.gen, 6000);
            double coarse = tank.cleanliness();
            h.assertTrue(coarse > TurbineAux.CLEANLINESS_DEFAULT + 0.01 && tank.dirtExcess() > 0, "a coarse cartridge alone lets the oil get dirtier: " + coarse);
            h.assertTrue(f.gen.aux().oilCleanliness == coarse && f.gen.aux().oilDirt > 0, "and the train reads it");
            h.assertTrue(f.gen.aux().oilCleanliness > TurbineAux.CLEANLINESS_DEFAULT && SignalProviders.of(f.gen).read("oil_cleanliness").getAsDouble() == coarse, "as a signal");
            h.assertTrue(tank.saveWithoutMetadata(f.l.registryAccess()).contains("OilDirt"), "dirty oil is a save key");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_FINE, 0));
            f.tick(f.gen, 6000);
            h.assertTrue(tank.cleanliness() < coarse - 0.005, "a fine cartridge turns it round: " + tank.cleanliness() + " from " + coarse);
            // From the default, a fine cartridge takes it under the default (cleaner than the plant has ever run).
            f.edit(tank, t -> t.remove("OilDirt"));
            f.tick(f.gen, 6000);
            h.assertTrue(tank.cleanliness() < TurbineAux.CLEANLINESS_DEFAULT - 0.003 && tank.dirtExcess() == 0, "from the default a fine one cleans the oil below it: " + tank.cleanliness());
            // A running conditioner behind a coarse cartridge: it takes the fines and the water, which is most of what a coarse one lets by.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_COARSE, 0));
            f.edit(tank, t -> t.remove("OilDirt"));
            var cond = f.skid(BwrBlocks.LUBE_OIL_CONDITIONER.get(), tank.getBlockPos().relative(Direction.EAST), Direction.EAST);
            cond.setConditioner(true);
            f.tick(f.gen, 6000, () -> cond.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            h.assertTrue(cond.conditionerRunning() && tank.cleanliness() < TurbineAux.CLEANLINESS_DEFAULT, "a running conditioner with the coarse cartridge keeps the oil under its default: " + tank.cleanliness());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 800)
    public static void dirtyOilWearsTheBearingsShakesTheShaftAndDegradesFaster(GameTestHelper h) {
        run(h, 87600, f -> {
            var hp = f.addHp();
            hp.markAuxRequired();
            f.gen.markAuxRequired();
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.rpm = 1500;
            hp.rpm = 1500;
            f.tick(hp, 100);
            double v0 = hp.vibration, d0 = f.gen.shaftDamage() + hp.shaftDamage();
            double before = tank.oilLitres();
            f.tick(hp, 600);
            double cleanLoss = before - tank.oilLitres();
            double dClean = f.gen.shaftDamage() + hp.shaftDamage();
            h.assertTrue(dClean - d0 < 1e-9 && cleanLoss > 0, "clean oil wears nothing: " + (dClean - d0));
            f.edit(tank, t -> t.putDouble("OilDirt", 0.6));
            f.tick(hp, 100);
            double v1 = hp.vibration;
            before = tank.oilLitres();
            f.tick(hp, 600);
            double dirtyLoss = before - tank.oilLitres();
            double dDirty = f.gen.shaftDamage() + hp.shaftDamage();
            h.assertTrue(v1 > v0 + 0.5, "dirty oil shakes the shaft more: " + v1 + " against " + v0);
            h.assertTrue(dDirty - dClean > 5e-6, "and wears the bearings: " + (dDirty - dClean));
            h.assertTrue(dirtyLoss > cleanLoss * 1.3, "and degrades faster: " + dirtyLoss + " against " + cleanLoss);
        });
    }

    // ------------------------------------------------------------------ the items, the slots and the saves

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void theThreeGradesAreCartridgesThatKeepTheirGradeInSlotsAndSaves(GameTestHelper h) {
        run(h, 87700, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            for (int g = 0; g < 3; g++) {
                var s = cartridge(g, 0);
                h.assertTrue(OilFilterCartridgeItem.isCartridge(s) && OilFilterCartridgeItem.gradeOf(s) == g && housing.cartridgeSlot().isItemValid(0, s), "grade " + g + " is a cartridge the slot takes");
            }
            h.assertTrue(!OilFilterCartridgeItem.isCartridge(new ItemStack(BwrItems.TURBINE_OIL_CAN.get())), "and an oil can is not");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(TurbineAux.GRADE_COARSE, 0.25));
            housing.cartridgeSlot().setStackInSlot(1, cartridge(TurbineAux.GRADE_FINE, 0.5));
            h.assertTrue(housing.grade(0) == TurbineAux.GRADE_COARSE && housing.grade(1) == TurbineAux.GRADE_FINE && housing.grade() == TurbineAux.GRADE_COARSE, "A coarse, B fine, A in service");
            h.assertTrue(Math.abs(housing.filterDpPsi(1) - TurbineAux.filterDpPsi(0.5, TurbineAux.GRADE_FINE)) < 1e-9 && housing.filterDpPsi(1) > 8, "the pressure drop is the grade's: " + housing.filterDpPsi(1));
            f.edit(housing, t -> { });
            h.assertTrue(housing.grade(0) == TurbineAux.GRADE_COARSE && housing.grade(1) == TurbineAux.GRADE_FINE
                    && Math.abs(housing.filterLoad(0) - 0.25) < 1e-9 && Math.abs(housing.filterLoad(1) - 0.5) < 1e-9, "a save and load keeps both grades and loads");
            var pipe = f.l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, housing.getBlockPos(), Direction.UP);
            housing.cartridgeSlot().setStackInSlot(1, ItemStack.EMPTY);
            h.assertTrue(pipe == null || pipe.insertItem(1, cartridge(TurbineAux.GRADE_FINE, 0), false).isEmpty() && housing.grade(1) == TurbineAux.GRADE_FINE, "a hopper puts a fine one into an empty slot");
            var dropped = housing.getBlockState();
            f.l.destroyBlock(housing.getBlockPos(), true);
            var items = f.l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(housing.getBlockPos()).inflate(3),
                    e -> OilFilterCartridgeItem.isCartridge(e.getItem()));
            h.assertTrue(items.size() == 2 && items.stream().anyMatch(e -> OilFilterCartridgeItem.gradeOf(e.getItem()) == TurbineAux.GRADE_COARSE)
                    && items.stream().anyMatch(e -> OilFilterCartridgeItem.gradeOf(e.getItem()) == TurbineAux.GRADE_FINE) && dropped != null, "a broken housing drops both, each its own grade");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void theCoarseAndFineCartridgesHaveARecipeInBothInstalls(GameTestHelper h) {
        boolean mek = net.neoforged.fml.ModList.get().isLoaded("mekanism");
        var manager = h.getLevel().getServer().getRecipeManager();
        for (String name : new String[] {"oil_filter_cartridge_coarse", "oil_filter_cartridge_fine"}) {
            var live = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name + "_mekanism" : name));
            var dead = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name : name + "_mekanism"));
            h.assertTrue(live.isPresent() && dead.isEmpty(), name + ": the " + (mek ? "Mekanism" : "vanilla") + " recipe is the live one and the other is not loaded");
            RecipeHolder<?> holder = live.get();
            var result = holder.value().getResultItem(h.getLevel().registryAccess());
            h.assertTrue(result.getItem() == (name.endsWith("coarse") ? BwrItems.OIL_FILTER_CARTRIDGE_COARSE.get() : BwrItems.OIL_FILTER_CARTRIDGE_FINE.get()) && result.getCount() == 1, name + " makes itself");
            boolean namesMek = holder.value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems())
                    .anyMatch(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("mekanism")));
            h.assertTrue(!mek || name.endsWith("coarse") || namesMek, name + " " + (mek ? "asks for Mekanism's materials where it has any" : "is vanilla"));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the conditioner's running lamp

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void theConditionersLampIsLitOnlyWhileItRuns(GameTestHelper h) {
        run(h, 87800, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var cond = f.skid(BwrBlocks.LUBE_OIL_CONDITIONER.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            var pos = cond.getBlockPos();
            h.assertTrue(!f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT), "a new conditioner's lamp is dark");
            cond.setConditioner(true);
            f.tick(f.gen, 5);
            h.assertTrue(!f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT) && !cond.conditionerRunning(), "switched on with no power it does not run, and the lamp stays dark");
            f.tick(f.gen, 5, () -> cond.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            h.assertTrue(cond.conditionerRunning() && f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT), "fed, it runs and the lamp is lit");
            f.tick(f.gen, 5, () -> cond.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            h.assertTrue(f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT), "and stays lit");
            cond.setConditioner(false);
            h.assertTrue(!f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT), "switched off: dark");
            cond.setConditioner(true);
            f.tick(f.gen, 5, () -> cond.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            h.assertTrue(f.l.getBlockState(pos).getValue(TurbineAuxBlock.LIT), "lit again");
        });
    }
}
