package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.power.OilFilterCartridgeItem;
import dev.bwr.mod.power.PowerModuleBlockEntity;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Turbine lube oil filtration (TURBINE-AUX.md): the Oil Filter Housing against a Main Oil Tank and the cartridge in its slot. A
 * clean cartridge lets a tenth of the tank's oil loss through, it loads up with use, and when it is full (or the slot is empty) the
 * oil goes round it at today's rate. A tank with no housing behaves exactly as it did before there was one. Namespace {@code bwraux},
 * on the generator fixture of {@link TurbineAuxTests}.
 */
@GameTestHolder("bwraux")
@PrefixGameTestTemplate(false)
public final class OilFilterTests {
    private OilFilterTests() {}

    @FunctionalInterface
    private interface Body {
        void run(TurbineAuxTests.Fx f) throws Exception;
    }

    private static void run(GameTestHelper h, int x, Body body) {
        var l = h.getLevel();
        var root = new BlockPos(x, 245, 78000);
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

    /** A pumped train at rated speed: a 10,000 L tank, the shaft pump, the shaft turning at 1,500 rpm. */
    private static TurbineAuxBlockEntity train(TurbineAuxTests.Fx f) {
        var tank = f.tank(10_000);
        f.shaftPump();
        f.gen.markAuxRequired();
        f.gen.rpm = 1500;
        return tank;
    }

    /** The housing bolted on the back of the tank (south of it, facing away). */
    private static TurbineAuxBlockEntity housing(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank) {
        return f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
    }

    private static ItemStack cartridge(double load) {
        var s = new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get());
        OilFilterCartridgeItem.setLoad(s, load);
        return s;
    }

    /** Oil lost by the tank over {@code ticks} ticks of running the train. */
    private static double lost(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank, int ticks) {
        double before = tank.oilLitres();
        f.tick(f.gen, ticks);
        return before - tank.oilLitres();
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aFilteredTankLosesFarLessOilThanAnUnfilteredOneOverTheSameRun(GameTestHelper h) {
        run(h, 82000, f -> {
            var tank = train(f);
            f.tick(f.gen, 40); // settle: the pump delivers, the header is at pressure
            double bare = lost(f, tank, 1200);
            h.assertTrue(bare > 45 && bare < 65, "a minute at rated pressure and cool oil costs about 54 L unfiltered: " + bare);
            var housing = housing(f, tank);
            h.assertTrue(housing.tank() == tank && tank.oilFilter() == housing, "the housing stands against the tank and the tank has it");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0));
            double filtered = lost(f, tank, 1200);
            h.assertTrue(filtered > 0 && filtered < bare * 0.13 && filtered > bare * 0.07,
                    "the same minute through a clean cartridge costs a tenth: " + filtered + " L against " + bare + " L");
            // The loss is the law's, with the cartridge's factor: the tank's own step and the core agree.
            double expected = TurbineAux.oilLossLitres(tank.psig(), tank.oilC(), 0.05, TurbineAux.OIL_FILTER_FACTOR);
            double before = tank.oilLitres();
            f.tick(f.gen, 1);
            h.assertTrue(Math.abs((before - tank.oilLitres()) - expected) < 1e-9, "one tick loses what the filter law says: " + (before - tank.oilLitres()) + " against " + expected);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theCartridgeLoadsUpItsPressureDropRisesAndAFullOneIsBypassed(GameTestHelper h) {
        run(h, 82100, f -> {
            var tank = train(f);
            var housing = housing(f, tank);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.5));
            h.assertTrue(!housing.filterBypass() && Math.abs(housing.filterDpPsi() - TurbineAux.filterDpPsi(0.5)) < 1e-9, "half full: " + housing.filterDpPsi() + " psi, no bypass");
            double life = housing.filterLife(), dp = housing.filterDpPsi();
            f.tick(f.gen, 1200);
            double used = life - housing.filterLife();
            h.assertTrue(Math.abs(used - 60.0 / TurbineAux.FILTER_LIFE_S) < 0.0005, "a minute at rated pressure uses a 360th of the cartridge: " + used);
            h.assertTrue(housing.filterDpPsi() > dp, "the pressure drop rose as it loaded: " + dp + " -> " + housing.filterDpPsi());
            h.assertTrue(f.gen.aux().filterHousing && Math.abs(f.gen.aux().filterLife - housing.filterLife()) < 1e-9, "the train reports it");
            // Nearly full: it fills, the bypass opens, and the oil goes round it at today's rate.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(1 - 2.0 / TurbineAux.FILTER_LIFE_S)); // two seconds of life left
            f.tick(f.gen, 100);
            h.assertTrue(housing.filterBypass() && housing.filterLoad() >= 1 && housing.filterFactor() == 1, "full: the bypass is open");
            h.assertTrue(f.gen.aux().filterBypass && f.gen.aux().filterAlarm && Math.abs(housing.filterDpPsi() - TurbineAux.FILTER_DP_FULL_PSI) < 1e-9,
                    "and the train reads bypass and change due at the full pressure drop");
            double spent = lost(f, tank, 1200);
            h.assertTrue(spent > 45 && spent < 65, "a full cartridge loses oil at the unfiltered rate again: " + spent + " L a minute");
            h.assertTrue(housing.filterLoad() == 1, "and a full cartridge goes no fuller");
            // Hot oil loads it faster: the same minute with the oil 15 K above 50 C costs twice the life.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.2));
            f.edit(tank, t -> t.putDouble("OilC", 65));
            double lifeBefore = housing.filterLife();
            f.tick(f.gen, 1200);
            h.assertTrue(lifeBefore - housing.filterLife() > 1.6 * 60.0 / TurbineAux.FILTER_LIFE_S, "hot oil loads the cartridge faster: " + (lifeBefore - housing.filterLife()));
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void changingTheCartridgeRestoresFiltrationAndAnEmptySlotIsTheSameAsNoFilter(GameTestHelper h) {
        run(h, 82200, f -> {
            var tank = train(f);
            var housing = housing(f, tank);
            f.tick(f.gen, 40);
            double bare = lost(f, tank, 600);
            // An empty slot: the same as no housing at all, with the bypass open.
            h.assertTrue(!housing.hasCartridge() && housing.filterBypass() && housing.filterFactor() == 1, "an empty slot is a bypass");
            double empty = lost(f, tank, 600);
            h.assertTrue(Math.abs(empty - bare) < bare * 0.02, "an empty housing loses what no housing does: " + empty + " against " + bare);
            // By hand: a cartridge in hand goes into the empty side OUT of service, one at a time: the canister in service is under pressure and is
            // never opened, even when it is empty (it keeps running on its bypass) - then the transfer valve is thrown to it.
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var stack = new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get(), 3);
            var hit = new BlockHitResult(Vec3.atCenterOf(housing.getBlockPos()), Direction.UP, housing.getBlockPos(), false);
            housing.getBlockState().useItemOn(stack, f.l, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(!housing.hasCartridge() && housing.hasCartridge(1) && stack.getCount() == 2 && housing.filterFactor() == 1,
                    "a cartridge in hand is refused by the empty side in service: it fills the side out of service, and the oil still goes round the bypass");
            housing.getBlockState().useItemOn(stack, f.l, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(stack.getCount() == 2, "with one in already a second is not taken");
            housing.selectSide(true);
            h.assertTrue(housing.hasCartridge() && housing.filterFactor() == TurbineAux.OIL_FILTER_FACTOR, "thrown over to it, it filters");
            double filtered = lost(f, tank, 600);
            h.assertTrue(filtered < bare * 0.13, "filtering: " + filtered + " against " + bare);
            // Back to side A, as the rest of the test has it (A in service, B empty).
            housing.selectSide(false);
            housing.cartridgeSlot().setStackInSlot(1, ItemStack.EMPTY);
            // It fills; taken out it is a used cartridge that knows how full it is, and the oil goes round again.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(1 - 2.0 / TurbineAux.FILTER_LIFE_S)); // two seconds of life left
            f.tick(f.gen, 100);
            var out = housing.cartridgeSlot().extractItem(0, 1, false);
            h.assertTrue(out.is(BwrItems.OIL_FILTER_CARTRIDGE.get()) && OilFilterCartridgeItem.load(out) >= 1 && !housing.hasCartridge(), "a spent cartridge comes out spent");
            h.assertTrue(lost(f, tank, 600) > bare * 0.9, "and the oil goes round");
            // A used one goes back where it left off; a fresh one filters again.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.3));
            f.tick(f.gen, 20);
            var part = housing.cartridgeSlot().extractItem(0, 1, false);
            double load = OilFilterCartridgeItem.load(part);
            h.assertTrue(load > 0.3 && load < 0.302, "a partly used cartridge comes out with its load: " + load);
            housing.cartridgeSlot().insertItem(0, part, false);
            h.assertTrue(Math.abs(housing.filterLoad() - load) < 1e-12, "and goes back at the same load: " + housing.filterLoad());
            housing.cartridgeSlot().extractItem(0, 1, false);
            housing.cartridgeSlot().insertItem(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false);
            h.assertTrue(housing.filterLoad() == 0 && housing.filterFactor() == TurbineAux.OIL_FILTER_FACTOR, "a new cartridge is clean and filters");
            h.assertTrue(lost(f, tank, 600) < bare * 0.13, "changing the cartridge restored filtration");
            // Only a cartridge fits; hoppers put one into an empty slot and cannot take one out.
            h.assertTrue(!housing.cartridgeSlot().isItemValid(0, new ItemStack(BwrItems.TURBINE_OIL_DRUM.get())), "a drum does not fit the slot");
            var pipe = f.l.getCapability(Capabilities.ItemHandler.BLOCK, housing.getBlockPos(), Direction.UP);
            h.assertTrue(pipe != null && pipe.extractItem(0, 1, false).isEmpty() && !pipe.insertItem(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).isEmpty(),
                    "a pipe or hopper can take nothing out, and cannot add to a full slot");
            housing.cartridgeSlot().extractItem(0, 1, false);
            h.assertTrue(!pipe.insertItem(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).isEmpty() && !housing.hasCartridge(),
                    "an empty slot in service refuses it too: the canister in service is never opened");
            h.assertTrue(pipe.insertItem(1, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).isEmpty() && housing.hasCartridge(1), "but it fills an empty one out of service");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aTankWithNoHousingLosesOilExactlyAsItAlwaysDidAndSavesNoNewKeys(GameTestHelper h) {
        run(h, 82300, f -> {
            var tank = train(f);
            f.tick(f.gen, 40);
            h.assertTrue(tank.oilFilter() == null, "no housing");
            for (int i = 0; i < 100; i++) {
                double before = tank.oilLitres();
                f.tick(f.gen, 1);
                double expected = TurbineAux.oilLossLitres(tank.psig(), tank.oilC(), 0.05);
                h.assertTrue(Math.abs((before - tank.oilLitres()) - expected) < 1e-12, "tick " + i + ": the loss is the old law's: " + (before - tank.oilLitres()) + " against " + expected);
            }
            var tag = tank.saveWithoutMetadata(f.l.registryAccess());
            for (String k : tag.getAllKeys()) h.assertTrue(!k.toLowerCase().contains("filter") && !k.equals("Cartridge"), "the tank saves no filter key: " + k);
            h.assertTrue(!f.gen.aux().filterHousing && Double.isNaN(f.gen.aux().filterLife), "the train reports no housing");
            // An old tank (a save from before: none of the keys) loads and runs the same.
            f.edit(tank, t -> { t.remove("Cartridge"); });
            double before = tank.oilLitres();
            f.tick(f.gen, 1);
            h.assertTrue(Math.abs((before - tank.oilLitres()) - TurbineAux.oilLossLitres(tank.psig(), tank.oilC(), 0.05)) < 1e-12, "still the old law after a reload");
            // A housing that stands against no tank filters nothing, reads that, and changes nothing about a train without one.
            var lone = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), f.root.offset(0, 0, -6), Direction.SOUTH);
            lone.cartridgeSlot().setStackInSlot(0, cartridge(0));
            f.tick(f.gen, 5);
            h.assertTrue(lone.tank() == null && tank.oilFilter() == null && lone.module() == null && lone.filterLoad() == 0, "a housing with no tank beside it does nothing: " + lone.filterLoad());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theHousingSavesTheCartridgeOnlyWhenThereIsOneAndKeepsHowFullItIs(GameTestHelper h) {
        run(h, 82400, f -> {
            var tank = train(f);
            var housing = housing(f, tank);
            var empty = housing.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!empty.contains("Cartridge"), "an empty housing saves no cartridge key: " + empty.getAllKeys());
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0));
            f.tick(f.gen, 2400);
            double load = housing.filterLoad();
            h.assertTrue(load > 0, "it loaded");
            var tag = housing.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(tag.contains("Cartridge"), "a loaded housing saves its cartridge");
            f.edit(housing, t -> { });
            h.assertTrue(housing.hasCartridge() && Math.abs(housing.filterLoad() - load) < 1e-12, "and keeps exactly how full it is across a reload: " + housing.filterLoad() + " against " + load);
            f.edit(housing, t -> t.remove("Cartridge"));
            h.assertTrue(!housing.hasCartridge() && housing.filterLoad() == 0, "a save with no cartridge key is an empty slot");
            // Broken, a housing drops the cartridge it held, with its load.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.4));
            var at = housing.getBlockPos();
            f.l.removeBlock(at, false);
            h.assertTrue(tank.oilFilter() == null, "with the housing gone the tank has none");
            var dropped = f.l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(2),
                    e -> e.getItem().is(BwrItems.OIL_FILTER_CARTRIDGE.get()));
            h.assertTrue(dropped.size() == 1 && Math.abs(OilFilterCartridgeItem.load(dropped.get(0).getItem()) - 0.4) < 1e-9, "the broken housing dropped its cartridge, 0.4 full: " + dropped.size());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theFilterIsReadOnTheSignalsAndByAComputer(GameTestHelper h) {
        run(h, 82500, f -> {
            var tank = train(f);
            f.tick(f.gen, 5);
            var gs = SignalProviders.of(f.gen);
            for (String k : new String[] {"oil_filter_life", "oil_filter_dp", "oil_filter_bypass", "oil_filter_alarm"})
                h.assertTrue(gs.read(k).isEmpty(), "with no housing " + k + " reads no signal");
            var ts = SignalProviders.of(tank);
            h.assertTrue(ts.read("filter_housing").getAsDouble() == 0 && ts.read("filter_life").isEmpty() && ts.read("filter_bypass").isEmpty(), "and so does the tank");
            var housing = housing(f, tank);
            f.tick(f.gen, 5);
            h.assertTrue(gs.read("oil_filter_bypass").getAsDouble() == 1 && gs.read("oil_filter_life").getAsDouble() == 0 && gs.read("oil_filter_alarm").getAsDouble() == 1,
                    "an empty slot reads bypass open, no life and change due");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.25));
            f.tick(f.gen, 5);
            h.assertTrue(Math.abs(gs.read("oil_filter_life").getAsDouble() - 0.75) < 0.001 && Math.abs(gs.read("oil_filter_dp").getAsDouble() - TurbineAux.filterDpPsi(0.25)) < 0.01
                    && gs.read("oil_filter_bypass").getAsDouble() == 0 && gs.read("oil_filter_alarm").getAsDouble() == 0, "a quarter used reads three quarters life, its pressure drop, no bypass, no alarm");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.8));
            f.tick(f.gen, 5);
            h.assertTrue(gs.read("oil_filter_alarm").getAsDouble() == 1 && gs.read("oil_filter_bypass").getAsDouble() == 0, "past the change pressure drop it alarms with the bypass still shut");
            var hs = SignalProviders.of(housing);
            h.assertTrue(hs.read("cartridge").getAsDouble() == 1 && Math.abs(hs.read("filter_life").getAsDouble() - 0.2) < 0.001 && hs.read("attached").getAsDouble() == 1
                    && hs.read("change_due").getAsDouble() == 1, "the housing's own signals");
            h.assertTrue(ts.read("filter_housing").getAsDouble() == 1 && Math.abs(ts.read("filter_life").getAsDouble() - 0.2) < 0.001, "and the tank's");

            if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) return;
            OilFilterCcChecks.verify(h, tank, housing);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void theCartridgeAndTheHousingHaveARecipeInBothInstalls(GameTestHelper h) {
        boolean mek = net.neoforged.fml.ModList.get().isLoaded("mekanism");
        var manager = h.getLevel().getServer().getRecipeManager();
        for (String name : new String[] {"oil_filter_cartridge", "oil_filter_housing"}) {
            var live = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name + "_mekanism" : name));
            var dead = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name : name + "_mekanism"));
            h.assertTrue(live.isPresent() && dead.isEmpty(), name + ": the " + (mek ? "Mekanism" : "vanilla") + " recipe is the live one and the other is not loaded");
            RecipeHolder<?> holder = live.get();
            var result = holder.value().getResultItem(h.getLevel().registryAccess());
            h.assertTrue(result.getItem() == (name.endsWith("cartridge") ? BwrItems.OIL_FILTER_CARTRIDGE.get() : BwrItems.OIL_FILTER_HOUSING.get()) && result.getCount() == 1, name + " makes itself");
            boolean namesMek = holder.value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems())
                    .anyMatch(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("mekanism")));
            h.assertTrue(namesMek == mek || (mek && namesMek), name + " " + (mek ? "asks for Mekanism's materials" : "is vanilla"));
        }
        h.succeed();
    }
}
