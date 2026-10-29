package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.gui.OilFilterMenu;
import dev.bwr.mod.power.LubeOilValveBlock;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The duplex Oil Filter Housing (sides A and B and a transfer valve) and the Lube Oil Conditioner (TURBINE-AUX.md): only the side in
 * service filters and loads, the other can be changed while the plant runs, the transfer valve by hand, redstone, signals and a
 * computer, old saves, hoppers; a running conditioner leaves 30 % of the oil's degradation bolted on the tank or piped to it, and
 * stopped, unfed or cut off leaves all of it. Namespace {@code bwroil}, on the generator fixture of {@link TurbineAuxTests}.
 */
@GameTestHolder("bwroil")
@PrefixGameTestTemplate(false)
public final class OilConditioningTests {
    private OilConditioningTests() {}

    @FunctionalInterface
    private interface Body {
        void run(TurbineAuxTests.Fx f) throws Exception;
    }

    private static void run(GameTestHelper h, int x, Body body) {
        var l = h.getLevel();
        var root = new BlockPos(x, 245, 80000);
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

    /** A pumped train at rated speed (the oil filter tests' fixture): a tank, the shaft pump, the shaft at 1,500 rpm. */
    private static TurbineAuxBlockEntity train(TurbineAuxTests.Fx f, int litres) {
        var tank = f.tank(litres);
        f.shaftPump();
        f.gen.markAuxRequired();
        f.gen.rpm = 1500;
        return tank;
    }

    private static TurbineAuxBlockEntity housing(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank) {
        return f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
    }

    private static ItemStack cartridge(double load) {
        var s = new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get());
        OilFilterCartridgeItem.setLoad(s, load);
        return s;
    }

    private static void feed(TurbineAuxBlockEntity skid) { skid.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false); }

    // ------------------------------------------------------------------ the duplex filter

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void onlyTheSideInServiceFiltersAndLoads(GameTestHelper h) {
        run(h, 86000, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0));
            housing.cartridgeSlot().setStackInSlot(1, cartridge(0.3));
            double b0 = housing.filterLoad(1);
            h.assertTrue(Math.abs(b0 - 0.3) < 0.001 && !housing.sideBInService() && housing.sideInService() == 0, "side A is in service by default");
            f.tick(f.gen, 1200);
            h.assertTrue(housing.filterLoad(0) > 0 && housing.filterLoad(1) == b0, "A loaded, B did not: " + housing.filterLoad(0) + " / " + housing.filterLoad(1));
            h.assertTrue(housing.filterLoad() == housing.filterLoad(0) && housing.filterFactor() == TurbineAux.OIL_FILTER_FACTOR, "the readings without a side are A's, and it filters");
            double a = housing.filterLoad(0);
            h.assertTrue(housing.selectSide(true) && housing.sideBInService() && !housing.selectSide(true), "the transfer valve moves once to B");
            h.assertTrue(f.l.getBlockState(housing.getBlockPos()).getValue(TurbineAuxBlock.SIDE_B), "and the block state shows the lever at B");
            f.tick(f.gen, 1200);
            h.assertTrue(housing.filterLoad(0) == a, "A is out of service and loads no more");
            h.assertTrue(housing.filterLoad(1) > b0, "B loads now: " + housing.filterLoad(1));
            h.assertTrue(housing.filterLoad() == housing.filterLoad(1) && Math.abs(housing.filterDpPsi() - TurbineAux.filterDpPsi(housing.filterLoad(1))) < 1e-9, "the readings follow B");
            // An empty side in service is the bypass, whatever the side out of service holds.
            housing.cartridgeSlot().setStackInSlot(1, ItemStack.EMPTY);
            h.assertTrue(housing.filterBypass() && housing.filterFactor() == 1 && !housing.filterBypass(0), "empty B in service: the oil goes round, A is not in the flow");
            double before = tank.oilLitres();
            f.tick(f.gen, 600);
            h.assertTrue(before - tank.oilLitres() > 20, "and the tank loses oil at the unfiltered rate: " + (before - tank.oilLitres()));
            h.assertTrue(housing.filterLoad(0) == a, "A still did not load");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void theSideOutOfServiceIsChangedWhilePlantRunsWithNoInterruption(GameTestHelper h) {
        run(h, 86100, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.5));
            f.tick(f.gen, 40);
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var menu = new OilFilterMenu(7, player.getInventory(), housing.getBlockPos());
            h.assertTrue(!menu.getSlot(0).mayPickup(player), "the cartridge in service cannot be taken out (the canister is under pressure)");
            h.assertTrue(menu.quickMoveStack(player, 0).isEmpty() && housing.hasCartridge(0), "shift-click leaves it where it is");
            h.assertTrue(menu.getSlot(1).mayPickup(player) && menu.getSlot(1).mayPlace(cartridge(0)), "side B, out of service, is open: an empty slot takes a cartridge");
            // A new cartridge goes into B while A keeps filtering: the oil never goes round.
            for (int i = 0; i < 30; i++) {
                f.tick(f.gen, 1);
                h.assertTrue(!housing.filterBypass() && housing.filterFactor() == TurbineAux.OIL_FILTER_FACTOR, "tick " + i + ": filtering");
                if (i == 10) menu.getSlot(1).set(cartridge(0));
            }
            h.assertTrue(housing.hasCartridge(1) && housing.filterLoad(1) == 0, "B holds a new cartridge");
            // A used B comes back out of the open side with its load.
            var out = menu.getSlot(1).getItem();
            h.assertTrue(menu.getSlot(1).mayPickup(player) && out.is(BwrItems.OIL_FILTER_CARTRIDGE.get()), "and can be taken out again");
            // Throw the valve: B is in service and A can be opened.
            housing.selectSide(true);
            var menu2 = new OilFilterMenu(8, player.getInventory(), housing.getBlockPos());
            h.assertTrue(menu2.getSlot(0).mayPickup(player) && !menu2.getSlot(1).mayPickup(player), "after the transfer A can be taken out and B cannot");
            var moved = menu2.quickMoveStack(player, 0);
            h.assertTrue(moved.is(BwrItems.OIL_FILTER_CARTRIDGE.get()) && Math.abs(OilFilterCartridgeItem.load(moved) - 0.5) < 0.01 && !housing.hasCartridge(0),
                    "A's used cartridge came out with its load: " + OilFilterCartridgeItem.load(moved));
            h.assertTrue(!housing.filterBypass() && housing.filterFactor() == TurbineAux.OIL_FILTER_FACTOR, "B went on filtering the whole time");
            // A cartridge from the inventory goes into the side out of service first.
            player.getInventory().setItem(9, cartridge(0.2));
            var inv = menu2.slots.stream().filter(s -> s.container == player.getInventory() && s.getContainerSlot() == 9).findFirst().orElseThrow();
            menu2.quickMoveStack(player, inv.index);
            h.assertTrue(housing.hasCartridge(0) && Math.abs(housing.filterLoad(0) - 0.2) < 0.01, "shift-click puts a cartridge into the side out of service");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void theTransferValveIsThrownByTheScreenSwitchRedstoneSignalsAndComputers(GameTestHelper h) {
        run(h, 86200, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            var pos = housing.getBlockPos();
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            // By hand the block itself is never clicked to throw it: a right-click anywhere (the top included) only opens the screen.
            for (Direction face : Direction.values())
                f.l.getBlockState(pos).useWithoutItem(f.l, player, new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
            h.assertTrue(!housing.sideBInService(), "no click on any face of the block throws the transfer valve");
            // The screen's A/B selector switch sends the command; the server throws the valve (and only for a player at the housing).
            var sp = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(f.l);
            sp.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            var menu = new OilFilterMenu(9, sp.getInventory(), pos);
            menu.handleCommand(sp, OilFilterMenu.CMD_TRANSFER, 1, 0);
            h.assertTrue(housing.sideBInService(), "the switch thrown to B puts side B in service");
            menu.handleCommand(sp, OilFilterMenu.CMD_TRANSFER, 1, 0);
            h.assertTrue(housing.sideBInService(), "thrown to B again changes nothing");
            menu.handleCommand(sp, OilFilterMenu.CMD_TRANSFER, 0, 0);
            h.assertTrue(!housing.sideBInService(), "thrown back to A");
            sp.setPos(pos.getX() + 40, pos.getY() + 1, pos.getZ() + 0.5);
            menu.handleCommand(sp, OilFilterMenu.CMD_TRANSFER, 1, 0);
            h.assertTrue(!housing.sideBInService(), "a player too far from the housing cannot throw it");
            // Redstone: power selects B, losing it A; only a change counts.
            var rs = pos.east();
            f.l.setBlock(rs, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            h.assertTrue(housing.sideBInService(), "power selects side B");
            f.l.setBlock(rs, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(!housing.sideBInService(), "losing it selects side A");
            housing.selectSide(true);
            f.l.setBlock(f.root.offset(0, 0, 10), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            f.l.setBlock(f.root.offset(0, 0, 10), Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(housing.sideBInService(), "a change elsewhere is no command");
            housing.selectSide(false);
            // Signals.
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.25));
            housing.cartridgeSlot().setStackInSlot(1, cartridge(0.8));
            var s = SignalProviders.of(housing);
            h.assertTrue(s.read("selected_side").getAsDouble() == 0 && s.read("a_cartridge").getAsDouble() == 1 && s.read("b_cartridge").getAsDouble() == 1, "signals: A in service, both held");
            h.assertTrue(Math.abs(s.read("a_life").getAsDouble() - 0.75) < 0.001 && Math.abs(s.read("b_life").getAsDouble() - 0.2) < 0.001, "each side's life");
            h.assertTrue(Math.abs(s.read("a_dp").getAsDouble() - TurbineAux.filterDpPsi(0.25)) < 0.01 && Math.abs(s.read("b_dp").getAsDouble() - TurbineAux.filterDpPsi(0.8)) < 0.01, "each side's pressure drop");
            h.assertTrue(s.read("a_change_due").getAsDouble() == 0 && s.read("b_change_due").getAsDouble() == 1 && s.read("change_due").getAsDouble() == 0, "B needs a change, A (in service) does not");
            h.assertTrue(s.command("selected_side", 1) && housing.sideBInService(), "the command throws it to B");
            h.assertTrue(s.read("selected_side").getAsDouble() == 1 && s.read("change_due").getAsDouble() == 1 && s.read("filter_bypass").getAsDouble() == 0, "the readings follow B: its change is due");
            h.assertTrue(s.command("selected_side", 0.2) && !housing.sideBInService(), "and back");
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) OilConditioningCcChecks.duplex(h, f.l, tank, housing);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void anOldHousingLoadsItsCartridgeIntoSideAAndSavesKeysOnlyWhenSet(GameTestHelper h) {
        run(h, 86300, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            var empty = housing.saveWithoutMetadata(f.l.registryAccess());
            for (String k : new String[] {"Cartridge", "CartridgeB", "SideB", "Powered"}) h.assertTrue(!empty.contains(k), "an empty housing saves no " + k);
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.4));
            var one = housing.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(one.contains("Cartridge") && !one.contains("CartridgeB") && !one.contains("SideB"), "one cartridge in A saves the old key and nothing else: " + one.getAllKeys());
            // An old save: the single-slot housing's tag, with its one cartridge.
            f.edit(housing, t -> { t.remove("SideB"); t.remove("CartridgeB"); });
            h.assertTrue(housing.hasCartridge(0) && !housing.hasCartridge(1) && !housing.sideBInService() && Math.abs(housing.filterLoad(0) - 0.4) < 0.01,
                    "the old cartridge is in side A with A in service");
            // Both sides, B in service: every set key comes back.
            housing.cartridgeSlot().setStackInSlot(1, cartridge(0.7));
            housing.selectSide(true);
            var both = housing.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(both.contains("Cartridge") && both.contains("CartridgeB") && both.contains("SideB"), "both sides and the valve are saved: " + both.getAllKeys());
            f.edit(housing, t -> { });
            h.assertTrue(housing.sideBInService() && Math.abs(housing.filterLoad(0) - 0.4) < 0.01 && Math.abs(housing.filterLoad(1) - 0.7) < 0.01, "and come back exactly");
            // B alone, A in service and empty: the B key without the A key.
            f.edit(housing, t -> { t.remove("Cartridge"); t.remove("SideB"); });
            h.assertTrue(!housing.hasCartridge(0) && housing.hasCartridge(1) && !housing.sideBInService(), "B alone is B");
            // Broken, a housing drops both cartridges.
            var at = housing.getBlockPos();
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0.1));
            f.l.removeBlock(at, false);
            var dropped = f.l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(2),
                    e -> e.getItem().is(BwrItems.OIL_FILTER_CARTRIDGE.get()));
            h.assertTrue(dropped.size() == 2, "the broken housing dropped both cartridges: " + dropped.size());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void hoppersFillTheEmptySideOutOfServiceAndTakeNothingOut(GameTestHelper h) {
        run(h, 86400, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            var pipe = f.l.getCapability(Capabilities.ItemHandler.BLOCK, housing.getBlockPos(), Direction.UP);
            h.assertTrue(pipe != null && pipe.getSlots() == 2, "two slots, A and B, for a hopper or a pipe");
            h.assertTrue(pipe.insertItem(1, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).isEmpty() && housing.hasCartridge(1), "an empty slot takes a cartridge: B, out of service");
            h.assertTrue(pipe.insertItem(1, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).getCount() == 1, "a full one does not");
            h.assertTrue(!pipe.insertItem(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()), false).isEmpty() && !housing.hasCartridge(0) && !pipe.isItemValid(0, cartridge(0)),
                    "an empty slot in service refuses one too: the canister in service is never opened");
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0));
            h.assertTrue(pipe.extractItem(0, 1, false).isEmpty() && pipe.extractItem(1, 1, false).isEmpty() && housing.hasCartridge(0) && housing.hasCartridge(1),
                    "and nothing is taken out of either side");
            h.assertTrue(!pipe.isItemValid(0, new ItemStack(BwrItems.TURBINE_OIL_DRUM.get())), "only a cartridge fits");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void theSideInServiceIsNeverOpenedByHandScreenOrHopperEvenWhenEmpty(GameTestHelper h) {
        run(h, 86450, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var menu = new OilFilterMenu(7, player.getInventory(), housing.getBlockPos());
            h.assertTrue(!housing.hasCartridge(0) && !housing.hasCartridge(1), "both sides empty, A in service");
            h.assertTrue(!menu.getSlot(0).mayPlace(cartridge(0)) && menu.getSlot(1).mayPlace(cartridge(0)), "the screen's slot in service takes nothing, the one out of service does");
            player.getInventory().setItem(9, cartridge(0));
            int index = 2;
            h.assertTrue(!menu.quickMoveStack(player, index).isEmpty() && housing.hasCartridge(1) && !housing.hasCartridge(0), "shift-click puts it in the side out of service, never the one in service");
            player.getInventory().setItem(10, cartridge(0));
            h.assertTrue(menu.quickMoveStack(player, 3).isEmpty() && !housing.hasCartridge(0), "with that one full, shift-click leaves the one in service empty and moves nothing");
            var pipe = f.l.getCapability(Capabilities.ItemHandler.BLOCK, housing.getBlockPos(), Direction.UP);
            h.assertTrue(!pipe.insertItem(0, cartridge(0), false).isEmpty() && pipe.insertItem(0, cartridge(0), true).getCount() == 1, "a hopper cannot fill the empty side in service, even simulated");
            // Thrown over, the roles swap: A is out of service and open, B (full) is in service and shut.
            housing.selectSide(true);
            h.assertTrue(pipe.insertItem(0, cartridge(0), false).isEmpty() && housing.hasCartridge(0) && !menu.getSlot(1).mayPlace(cartridge(0)), "thrown to B, A takes one and B takes none");
            // By hand: a cartridge goes into the side out of service; with it full the housing says so and keeps it in hand.
            var stack = new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get(), 2);
            var hit = new BlockHitResult(Vec3.atCenterOf(housing.getBlockPos()), Direction.UP, housing.getBlockPos(), false);
            housing.getBlockState().useItemOn(stack, f.l, player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            h.assertTrue(stack.getCount() == 2, "with the side out of service full a cartridge in hand is not taken");
            housing.cartridgeSlot().setStackInSlot(0, ItemStack.EMPTY);
            housing.selectSide(false);
            housing.cartridgeSlot().setStackInSlot(1, ItemStack.EMPTY);
            housing.getBlockState().useItemOn(stack, f.l, player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            h.assertTrue(stack.getCount() == 1 && housing.hasCartridge(1) && !housing.hasCartridge(0), "and with both empty it goes into the side out of service");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void theBlockStateShowsTheLeverAndTheOpenLid(GameTestHelper h) {
        run(h, 86500, f -> {
            var tank = train(f, 10_000);
            var housing = housing(f, tank);
            var pos = housing.getBlockPos();
            housing.cartridgeSlot().setStackInSlot(0, cartridge(0));
            var s = f.l.getBlockState(pos);
            h.assertTrue(!s.getValue(TurbineAuxBlock.SIDE_B) && s.getValue(TurbineAuxBlock.OFF_OPEN), "A in service, B (out of service) empty: its lid is open for a change");
            housing.cartridgeSlot().setStackInSlot(1, cartridge(0));
            s = f.l.getBlockState(pos);
            h.assertTrue(!s.getValue(TurbineAuxBlock.SIDE_B) && !s.getValue(TurbineAuxBlock.OFF_OPEN), "a cartridge in B and its lid is shut");
            housing.selectSide(true);
            s = f.l.getBlockState(pos);
            h.assertTrue(s.getValue(TurbineAuxBlock.SIDE_B) && !s.getValue(TurbineAuxBlock.OFF_OPEN), "lever at B, A holds a cartridge so its lid is shut");
            housing.cartridgeSlot().setStackInSlot(0, ItemStack.EMPTY);
            s = f.l.getBlockState(pos);
            h.assertTrue(s.getValue(TurbineAuxBlock.SIDE_B) && s.getValue(TurbineAuxBlock.OFF_OPEN) && s.getValue(TurbineAuxBlock.FACING) == Direction.SOUTH, "A taken out: its lid is open, the facing is kept");
        });
    }

    // ------------------------------------------------------------------ the conditioner

    /** The conditioner bolted against the tank's south side, facing south (its back, the inlet, on the tank). */
    private static TurbineAuxBlockEntity boltedConditioner(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank) {
        return f.skid(BwrBlocks.LUBE_OIL_CONDITIONER.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
    }

    /** Oil lost by the tank over {@code ticks} ticks. */
    private static double lost(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank, int ticks, Runnable supply) {
        double before = tank.oilLitres();
        f.tick(f.gen, ticks, supply);
        return before - tank.oilLitres();
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aRunningConditionerBoltedOnTheTankLeavesThirtyPercentOfTheDegradation(GameTestHelper h) {
        run(h, 86600, f -> {
            var tank = train(f, 10_000);
            f.tick(f.gen, 40);
            double bare = lost(f, tank, 600, () -> { });
            var cond = boltedConditioner(f, tank);
            h.assertTrue(cond.tank() == tank && tank.conditioner() == cond && cond.module() == null, "the conditioner is bolted on the tank and is not on the train");
            // Fitted but not switched on: the old loss.
            double idle = lost(f, tank, 600, () -> feed(cond));
            h.assertTrue(Math.abs(idle - bare) < bare * 0.02 && !cond.conditionerRunning(), "fitted and stopped it changes nothing: " + idle + " against " + bare);
            cond.setConditioner(true);
            // Per tick the loss is the law's with the conditioner's factor, to the bit.
            for (int i = 0; i < 40; i++) {
                feed(cond);
                double before = tank.oilLitres();
                f.tick(f.gen, 1);
                double expected = TurbineAux.oilLossLitres(tank.psig(), tank.oilC(), 0.05, 1.0, TurbineAux.CONDITIONER_FACTOR);
                h.assertTrue(Math.abs((before - tank.oilLitres()) - expected) < 1e-9, "tick " + i + ": the loss is the conditioned law's: " + (before - tank.oilLitres()) + " against " + expected);
            }
            h.assertTrue(cond.conditionerRunning(), "it runs");
            double running = lost(f, tank, 600, () -> feed(cond));
            h.assertTrue(Math.abs(running / bare - TurbineAux.CONDITIONER_FACTOR) < 0.01, "running it leaves its factor of the loss: " + running / bare);
            // With a clean cartridge in service too the two multiply.
            var filter = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.EAST), Direction.EAST);
            filter.cartridgeSlot().setStackInSlot(0, cartridge(0));
            double both = lost(f, tank, 600, () -> feed(cond));
            h.assertTrue(Math.abs(both / bare - TurbineAux.CONDITIONER_FACTOR * TurbineAux.OIL_FILTER_FACTOR) < 0.003, "with the filter too: " + both / bare);
            // No power: the oil is back on the filter-only rate.
            f.edit(cond, t -> t.putInt("AcFe", 0));
            double starved = lost(f, tank, 600, () -> { });
            h.assertTrue(!cond.conditionerRunning() && Math.abs(starved / bare - TurbineAux.OIL_FILTER_FACTOR) < 0.01, "without power it is the filter-only rate: " + starved / bare);
            // Stopped again, fed: the same.
            cond.setConditioner(false);
            double stopped = lost(f, tank, 600, () -> feed(cond));
            h.assertTrue(Math.abs(stopped / bare - TurbineAux.OIL_FILTER_FACTOR) < 0.01, "stopped it is the filter-only rate: " + stopped / bare);
            // It draws FE only while it runs.
            cond.setConditioner(true);
            feed(cond);
            int fe = cond.acFe();
            f.tick(f.gen, 1);
            h.assertTrue(fe - cond.acFe() == TurbineAuxBlockEntity.CONDITIONER_FE_PER_TICK, "it draws its power: " + (fe - cond.acFe()));
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aConditionerPipedToTheTankDrawsFromItsSuctionAndReturnsToItsFillAndAShutValveCutsItOff(GameTestHelper h) {
        run(h, 86700, f -> {
            var tank = train(f, 10_000);
            var tpos = tank.getBlockPos(); // (0, 0, 3), facing north
            var cpos = f.root.offset(4, -1, 8);
            f.l.setBlock(cpos, BwrBlocks.LUBE_OIL_CONDITIONER.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
            var cond = (TurbineAuxBlockEntity) f.l.getBlockEntity(cpos);
            cond.setConditioner(true);
            h.assertTrue(cond.tank() == null && tank.conditioner() == null, "with no pipe it serves no tank");
            // Suction: the tank's suction outlet (below it) round to the conditioner's inlet (its back, the west face).
            lay(f, tpos.below(), f.root.offset(0, -1, 8), f.root.offset(3, -1, 8));
            // Return: the conditioner's outlet (its front, the east face) up and back to the tank's fill connection (above it).
            lay(f, f.root.offset(5, -1, 8), f.root.offset(5, 1, 8), f.root.offset(0, 1, 8), tpos.above());
            h.assertTrue(cond.tank() == tank && tank.conditioner() == cond, "the runs join the conditioner to the tank: its suction and its fill");
            f.tick(f.gen, 40, () -> feed(cond));
            double bare = lostWith(f, tank, 600, false, cond);
            double running = lostWith(f, tank, 600, true, cond);
            h.assertTrue(cond.conditionerRunning() && Math.abs(running / bare - TurbineAux.CONDITIONER_FACTOR) < 0.02, "piped and running it leaves its factor: " + running / bare);
            // A shut valve in the suction run: no oil passes, so it does nothing, though it is on and fed.
            var vpos = f.root.offset(0, -1, 6);
            f.l.setBlock(vpos, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.SOUTH).setValue(LubeOilValveBlock.OPEN, false), 3);
            double cut = lostWith(f, tank, 600, true, cond);
            h.assertTrue(!cond.conditionerRunning() && Math.abs(cut / bare - 1) < 0.02, "a shut valve in its suction run puts the oil back on the filter-only rate (here none): " + cut / bare);
            h.assertTrue(cond.tank() == tank && tank.conditioner() == cond, "it is still the tank's conditioner (valves do not change what is piped)");
            // Open again; then a cut return: the same.
            f.l.setBlock(vpos, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.SOUTH).setValue(LubeOilValveBlock.OPEN, true), 3);
            h.assertTrue(lostWith(f, tank, 300, true, cond) / lostWith(f, tank, 300, false, cond) < 0.35, "opened again it runs");
            f.l.setBlock(f.root.offset(2, 1, 8), Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(cond.tank() == null && tank.conditioner() == null, "cut in its return run it serves nothing");
        });
    }

    private static void lay(TurbineAuxTests.Fx f, BlockPos... points) {
        for (int i = 0; i + 1 < points.length; i++)
            for (var p : BlockPos.betweenClosed(points[i], points[i + 1])) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3);
        for (var p : points) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().stateWithConnections(f.l, p), 3);
        for (int i = 0; i + 1 < points.length; i++)
            for (var p : BlockPos.betweenClosed(points[i], points[i + 1])) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().stateWithConnections(f.l, p), 3);
    }

    /** Loss over {@code ticks} ticks with the conditioner switched on or off and fed. */
    private static double lostWith(TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank, int ticks, boolean on, TurbineAuxBlockEntity cond) {
        cond.setConditioner(on);
        return lost(f, tank, ticks, () -> feed(cond));
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aConditionedTankKeepsItsOilAboutOneRefuellingCycle(GameTestHelper h) {
        run(h, 86800, f -> {
            // 20,000 L, the shaft pump alone at rated speed (25 psig), cool oil, a clean cartridge in service and the conditioner running.
            var tank = f.tank(20_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var filter = housing(f, tank);
            filter.cartridgeSlot().setStackInSlot(0, cartridge(0));
            var cond = f.skid(BwrBlocks.LUBE_OIL_CONDITIONER.get(), tank.getBlockPos().relative(Direction.EAST), Direction.EAST);
            cond.setConditioner(true);
            f.tick(f.gen, 40, () -> feed(cond));
            h.assertTrue(Math.abs(tank.psig() - 25) < 0.01 && tank.oilC() < 30, "the header is at 25 psig, the oil cool: " + tank.psig() + " psig, " + tank.oilC() + " C");
            double perTick = lost(f, tank, 1200, () -> feed(cond)) / 1200;
            double hours = tank.oilLitres() / (perTick * 20) / 3600;
            h.assertTrue(cond.conditionerRunning() && hours > 190 && hours < 220, "a 20,000 L tank at rated speed lasts about 8.5 days (204 h): " + hours + " h");
            h.assertTrue(hours > 7 * 24 && hours < 10 * 24, "inside one refuelling cycle of 7 to 10 days: " + hours / 24 + " days");
            // Without the conditioner the filter alone is today's 62 h.
            cond.setConditioner(false);
            double alone = lost(f, tank, 1200, () -> feed(cond)) / 1200;
            double aloneHours = tank.oilLitres() / (alone * 20) / 3600;
            h.assertTrue(aloneHours > 55 && aloneHours < 66, "the filter alone is as it was, about 62 h at full: " + aloneHours + " h");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void theConditionerHasSignalsAComputerFaceAndSavesKeysOnlyWhenSet(GameTestHelper h) {
        run(h, 86900, f -> {
            var tank = train(f, 10_000);
            var cond = boltedConditioner(f, tank);
            var pos = cond.getBlockPos();
            var tag = cond.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!tag.contains("CondOn") && !tag.contains("AcFe"), "an idle conditioner saves no key: " + tag.getAllKeys());
            var s = SignalProviders.of(cond);
            h.assertTrue(s.read("attached").getAsDouble() == 1 && s.read("on").getAsDouble() == 0 && s.read("running").getAsDouble() == 0
                    && s.read("degradation_factor").getAsDouble() == 1, "stopped: attached, off, not running, all of the degradation");
            h.assertTrue(s.command("on", 1) && cond.conditionerOn(), "the start command switches it on");
            f.tick(f.gen, 5, () -> feed(cond));
            h.assertTrue(s.read("running").getAsDouble() == 1 && Math.abs(s.read("degradation_factor").getAsDouble() - TurbineAux.CONDITIONER_FACTOR) < 1e-9, "running: its factor");
            h.assertTrue(f.l.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP) != null && f.l.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.SOUTH) == null
                    && f.l.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.EAST) != null, "FE on every face but its two oil flanges (back and front)");
            var ts = SignalProviders.of(tank);
            h.assertTrue(ts.read("conditioner").getAsDouble() == 1 && ts.read("conditioner_running").getAsDouble() == 1, "the tank reads its conditioner");
            var gs = SignalProviders.of(f.gen);
            h.assertTrue(gs.read("oil_conditioner_running").getAsDouble() == 1 && f.gen.aux().conditioner && f.gen.aux().conditionerRunning, "and so does the train");
            tag = cond.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(tag.contains("CondOn"), "a switched-on conditioner saves that");
            f.edit(cond, t -> { });
            h.assertTrue(cond.conditionerOn(), "and keeps it across a reload");
            f.l.removeBlock(pos, false);
            h.assertTrue(tank.conditioner() == null && ts.read("conditioner").getAsDouble() == 0 && ts.read("conditioner_running").isEmpty(), "without it the tank has none");
            f.tick(f.gen, 3);
            h.assertTrue(!f.gen.aux().conditioner && SignalProviders.of(f.gen).read("oil_conditioner_running").isEmpty(), "and the signal reads no signal");
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                var again = boltedConditioner(f, tank);
                OilConditioningCcChecks.conditioner(h, f.l, tank, again);
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 100)
    public static void everyConditionerPortAndPortFaceIsWhereItShouldBe(GameTestHelper h) {
        var b = BwrBlocks.LUBE_OIL_CONDITIONER.get();
        var s = b.defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST);
        h.assertTrue(b.oilPortAt(s, Direction.WEST) == dev.bwr.mod.piping.OilLineNetwork.Role.COND_INLET && b.oilPortAt(s, Direction.EAST) == dev.bwr.mod.piping.OilLineNetwork.Role.COND_OUTLET,
                "inlet on the back, outlet on the front");
        for (Direction d : new Direction[] {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH}) h.assertTrue(b.oilPortAt(s, d) == null, "no oil port on " + d);
        h.assertTrue(!b.acceptsWaterLineOn(s, Direction.NORTH), "no water flange");
        h.assertTrue(dev.bwr.mod.piping.OilLineNetwork.acceptsLineOn(s, Direction.WEST) && !dev.bwr.mod.piping.OilLineNetwork.acceptsLineOn(s, Direction.UP), "a pipe takes the two flanges only");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void theConditionerHasARecipeInBothInstalls(GameTestHelper h) {
        boolean mek = net.neoforged.fml.ModList.get().isLoaded("mekanism");
        var manager = h.getLevel().getServer().getRecipeManager();
        var live = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? "lube_oil_conditioner_mekanism" : "lube_oil_conditioner"));
        var dead = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? "lube_oil_conditioner" : "lube_oil_conditioner_mekanism"));
        h.assertTrue(live.isPresent() && dead.isEmpty(), "the " + (mek ? "Mekanism" : "vanilla") + " recipe is the live one and the other is not loaded");
        var result = live.get().value().getResultItem(h.getLevel().registryAccess());
        h.assertTrue(result.getItem() == BwrItems.LUBE_OIL_CONDITIONER.get() && result.getCount() == 1, "it makes the conditioner");
        boolean steel = live.get().value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems()).anyMatch(st ->
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().contains("steel")));
        boolean iron = live.get().value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems()).anyMatch(st ->
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith("iron_")));
        h.assertTrue(mek ? steel && !iron : iron, mek ? "with Mekanism it asks for steel and no iron" : "without it it is made of iron");
        h.succeed();
    }
}
