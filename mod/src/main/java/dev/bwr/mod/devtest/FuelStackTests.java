package dev.bwr.mod.devtest;

import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.fuel.CarriedFuel;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.ReprocessingItemView;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.ReprocessingPlantMenu;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Fuel assemblies as stacks: they stack to 64 wherever items are ordinarily kept (a pocket, a chest, a hopper), only
 * identical ones merge, a stack is as hot and as radioactive as all the assemblies in it, and every position that holds
 * one physical assembly takes exactly one from a stack and leaves the rest where it was, so that no path duplicates or
 * loses fuel. Each test counts the assemblies it started with against the items and positions it ends with.
 */
@GameTestHolder("bwrpool")
@PrefixGameTestTemplate(false)
public final class FuelStackTests {

    private static final double BURNUP = 30_000.0;

    private static ItemStack fresh(int count) {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 0.0, 1.0)).copyWithCount(count);
    }

    /** A spent assembly with no discharge stamp: long-cooled, so the reprocessing plant takes it. */
    private static ItemStack longCooled(int count) {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 20_000.0, 1.0)).copyWithCount(count);
    }

    /**
     * A fresh discharge: out of the core at most twenty seconds before {@code now} (a GameTest server is young, so the
     * stamp is clamped at tick 0 rather than dropped), a few hundred kilowatts of decay heat.
     */
    private static ItemStack hot(long now, int count) {
        return stampedAt(Math.max(0L, now - 20L * SpentFuelHeat.TICKS_PER_SECOND), count);
    }

    private static ItemStack stampedAt(long tick, int count) {
        ItemStack stack = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, BURNUP, 1.0));
        SpentFuelHeat.stamp(stack, tick);
        return stack.copyWithCount(count);
    }

    private static int assemblies(Inventory inventory) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.is(BwrItems.FUEL_ASSEMBLY.get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int stacks(Inventory inventory) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(BwrItems.FUEL_ASSEMBLY.get())) {
                n++;
            }
        }
        return n;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void freshAssembliesStackInAPocketAndAChestAndAHopperMovesTheStack(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.assertTrue(new ItemStack(BwrItems.FUEL_ASSEMBLY.get()).getMaxStackSize() == 64, "an assembly stacks to 64");
        h.assertTrue(new ItemStack(BwrItems.SPECIALTY_ROD.get()).getMaxStackSize() == 64, "a specialty rod stacks to 64");

        // a pocket: two identical fresh assemblies become one stack of two, a different grade stays apart
        FakePlayer player = NewFuelElevatorTests.player(level, "stack", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(0, 1, 0))));
        Inventory inventory = player.getInventory();
        h.assertTrue(inventory.add(fresh(1)) && inventory.add(fresh(1)), "both taken");
        h.assertTrue(inventory.getItem(0).getCount() == 2 && stacks(inventory) == 1, "one stack of two: " + inventory.getItem(0));
        ItemStack heu = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("heu", 0.20, 180.0, 0.0, 1.0));
        h.assertTrue(inventory.add(heu) && stacks(inventory) == 2 && assemblies(inventory) == 3, "a different grade is its own stack");
        h.assertTrue(inventory.add(fresh(62)) && inventory.getItem(0).getCount() == 64, "up to 64 in a slot: " + inventory.getItem(0));
        h.assertTrue(inventory.add(fresh(1)) && stacks(inventory) == 3 && assemblies(inventory) == 66, "the 65th starts a new stack");
        inventory.clearContent();

        // a chest: the same, through the container merge every hopper and pipe uses
        BlockPos bottom = h.absolutePos(new BlockPos(0, 2, 0));
        BlockPos hopper = bottom.above();
        BlockPos top = hopper.above();
        level.setBlock(bottom, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(hopper, Blocks.HOPPER.defaultBlockState(), 3);
        level.setBlock(top, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity upper = (ChestBlockEntity) level.getBlockEntity(top);
        ChestBlockEntity lower = (ChestBlockEntity) level.getBlockEntity(bottom);
        h.assertTrue(HopperBlockEntity.addItem(null, upper, fresh(1), null).isEmpty()
                && HopperBlockEntity.addItem(null, upper, fresh(3), null).isEmpty(), "the chest took both");
        h.assertTrue(upper.getItem(0).getCount() == 4 && upper.getItem(1).isEmpty(), "merged into one stack of four: " + upper.getItem(0));

        // a hopper: moves the stack chest to chest, one at a time, into one stack
        h.runAfterDelay(120, () -> {
            try {
                int below = 0;
                int stacksBelow = 0;
                for (int i = 0; i < lower.getContainerSize(); i++) {
                    if (lower.getItem(i).is(BwrItems.FUEL_ASSEMBLY.get())) {
                        below += lower.getItem(i).getCount();
                        stacksBelow++;
                    }
                }
                h.assertTrue(upper.isEmpty(), "the hopper emptied the upper chest: " + upper.getItem(0));
                h.assertTrue(below == 4 && stacksBelow == 1, "the lower chest holds the four as one stack: " + below + " in " + stacksBelow + " stacks");
                h.assertTrue(level.getBlockEntity(hopper) instanceof HopperBlockEntity hb && hb.isEmpty(), "nothing is left in the hopper");
            } finally {
                lower.clearContent();
                level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(hopper, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(bottom, Blocks.AIR.defaultBlockState(), 3);
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 100)
    public static void twoIdenticalHotAssembliesStackAndTheStackIsTwiceTheSourceOfOne(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long now = level.getGameTime();
        Vec3 feet = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(0, 1, 0)));
        FakePlayer player = NewFuelElevatorTests.player(level, "hot", feet);
        Inventory inventory = player.getInventory();
        h.assertTrue(inventory.add(hot(now, 1)) && inventory.add(hot(now, 1)), "both taken");
        h.assertTrue(inventory.getItem(0).getCount() == 2 && stacks(inventory) == 1, "two identical hot assemblies are one stack: " + inventory.getItem(0));
        h.assertTrue(SpentFuelHeat.dischargeTime(inventory.getItem(0)) >= 0L, "the pair carries a discharge stamp: " + inventory.getItem(0));
        h.assertTrue(inventory.add(stampedAt(now, 1)) && stacks(inventory) == 2, "one discharged just now is not the same assembly");

        // heat: a stack of two is twice one
        double one = SpentFuelHeat.decayWatts(hot(now, 1), now);
        double two = SpentFuelHeat.decayWatts(hot(now, 2), now);
        h.assertTrue(one > 100_000.0 && close(two, 2.0 * one), "decay heat of a stack of two: " + two + " W against " + one + " W for one");
        h.assertTrue(close(SpentFuelHeat.decayWattsEach(hot(now, 2), now), one), "the per-assembly figure does not change with the count");
        h.assertTrue(close(new SpentFuelHeat.Curve(hot(now, 2)).watts(now), two), "the kept curve counts the stack too");
        h.assertTrue(SpentFuelHeat.decayWatts(fresh(64), now) == 0.0 && CarriedFuel.gammaWatts(fresh(64), now) == 0.0, "fresh fuel has no decay heat and reads nothing");

        // carried: the player's own stack read at three metres, with one and with two
        Vec3 at = feet.add(3.0, 1.0, 0.0);
        inventory.clearContent();
        inventory.setItem(0, hot(now, 1));
        double carriedOne = CarriedFuel.doseRateOf(player, at);
        inventory.setItem(0, hot(now, 2));
        double carriedTwo = CarriedFuel.doseRateOf(player, at);
        h.assertTrue(carriedOne > 1000.0, "a fresh discharge in a pocket reads sieverts an hour at three metres: " + carriedOne + " mSv/h");
        h.assertTrue(close(carriedTwo, 2.0 * carriedOne), "a carried stack of two reads twice one: " + carriedTwo + " against " + carriedOne);
        inventory.setItem(0, hot(now, 1));
        inventory.setItem(1, hot(now, 1));
        h.assertTrue(close(CarriedFuel.doseRateOf(player, at), carriedTwo), "two stacks of one read the same as one stack of two");
        inventory.clearContent();

        // dropped: an item entity holding the stack, read directly and through the registered source
        Vec3 spot = feet.add(-3.0, 0.5, 0.0);
        ItemEntity item = new ItemEntity(level, spot.x, spot.y, spot.z, hot(now, 1));
        item.setNoPickUpDelay();
        item.setNeverPickUp();
        level.addFreshEntity(item);
        try {
            double droppedOne = CarriedFuel.doseRateOf(item, at);
            double totalOne = RadiationSources.doseRateMsvPerHour(level, at);
            item.setItem(hot(now, 2));
            double droppedTwo = CarriedFuel.doseRateOf(item, at);
            double totalTwo = RadiationSources.doseRateMsvPerHour(level, at);
            h.assertTrue(droppedOne > 1000.0 && close(droppedTwo, 2.0 * droppedOne), "a dropped stack of two reads twice one: " + droppedTwo + " against " + droppedOne);
            h.assertTrue(Math.abs((totalTwo - totalOne) - droppedOne) <= 1e-6 * droppedOne,
                    "the second assembly of the dropped stack adds its own dose to the level's reading: " + totalOne + " -> " + totalTwo + ", one is " + droppedOne);
        } finally {
            item.discard();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 400)
    public static void theRackCellTheElevatorTheReprocessingPlantAndTheFabricatorTakeOneAssemblyFromAStack(GameTestHelper h) {
        NewFuelElevatorTests.Site s = new NewFuelElevatorTests.Site(h, 20, 2, 7.0);
        ServerLevel level = s.level;
        long now = level.getGameTime();
        FakePlayer player = NewFuelElevatorTests.player(level, "stacker", Vec3.atBottomCenterOf(s.rim.above(2)));
        BlockPos plantPos = s.pool.min.offset(4, 10, 4);
        try {
            var pool = s.pool.be;
            BlockPos rack = s.pool.racks.get(0);
            int started = 0;

            // the rack through the pool's item handler (a hopper or a pipe): one goes into the cell, the rest comes back
            ItemStack four = hot(now, 4);
            started += 4;
            ItemStack rest = pool.itemHandler().insertItem(0, four, false);
            h.assertTrue(rest.getCount() == 3 && pool.stackAt(rack, 0).getCount() == 1, "the handler took one of four: " + rest + ", cell " + pool.stackAt(rack, 0));
            h.assertTrue(pool.itemHandler().insertItem(0, rest, false).getCount() == 3, "a full cell takes none");
            h.assertTrue(pool.itemHandler().getSlotLimit(0) == 1, "a cell says it holds one");

            // the rack by right-click with the rest in hand: one more, the hand keeps two
            player.setItemInHand(InteractionHand.MAIN_HAND, rest);
            BlockPos top = rack.above(3);
            BlockHitResult hit = new BlockHitResult(new Vec3(top.getX() + 0.75, top.getY() + 1.0, top.getZ() + 0.25), Direction.UP, top, false);
            level.getBlockState(top).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(player.getMainHandItem().getCount() == 2 && pool.stackAt(rack, 1).getCount() == 1 && pool.assemblyCount() == 2,
                    "a right-click put one in cell 2 and kept two: hand " + player.getMainHandItem() + ", pool " + pool.assemblyCount());
            h.assertTrue(pool.assemblyCount() + player.getMainHandItem().getCount() == started, "nothing lost or made");

            // the elevator's hatch through its handler: one in, the carriage takes it down, the rest comes back
            ItemStack two = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ItemStack left = s.be.itemHandler().insertItem(0, two, false);
            h.assertTrue(left.getCount() == 1 && s.be.busy(), "the hatch took one of two and the carriage is on its way: " + left);
            h.assertTrue(s.be.itemHandler().getSlotLimit(0) == 1, "the hatch says it holds one");
            s.advance(s.be.currentTrip().totalTicks() + 5);
            h.assertTrue(!s.be.busy() && pool.assemblyCount() == 3, "it went into a cell: " + pool.assemblyCount() + " in the pool, " + s.be.status());

            // the elevator by right-click with the last one: it goes down too, so the whole four are in the pool
            player.setItemInHand(InteractionHand.MAIN_HAND, left);
            BlockHitResult rimHit = new BlockHitResult(Vec3.atCenterOf(s.rim), Direction.UP, s.rim, false);
            level.getBlockState(s.rim).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, rimHit);
            h.assertTrue(player.getMainHandItem().isEmpty() && s.be.busy(), "the right-click put the last one in the hatch");
            s.advance(s.be.currentTrip().totalTicks() + 5);
            h.assertTrue(pool.assemblyCount() == started, "all four are in the pool, one to a cell: " + pool.assemblyCount());
            for (int k = 0; k < 4; k++) {
                h.assertTrue(pool.stackAt(rack, k).getCount() == 1, "cell " + k + " holds one: " + pool.stackAt(rack, k));
            }
            h.assertTrue(pool.itemHandler().extractItem(0, 64, false).getCount() == 1 && pool.assemblyCount() == 3, "a cell gives one out");

            // the reprocessing plant: a pipe offering three leaves one in the slot; splitting it consumes that one only
            level.setBlock(plantPos, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 2);
            ReprocessingPlantBlockEntity plant = (ReprocessingPlantBlockEntity) level.getBlockEntity(plantPos);
            ItemStack three = longCooled(3);
            ItemStack back = level.getCapability(Capabilities.ItemHandler.BLOCK, plantPos, Direction.UP).insertItem(ReprocessingItemView.INPUT, three, false);
            h.assertTrue(back.getCount() == 2 && plant.input().getStackInSlot(0).getCount() == 1, "the plant took one of three: " + back + ", slot " + plant.input().getStackInSlot(0));
            h.assertTrue(plant.input().getSlotLimit(0) == 1, "the slot says it holds one");
            int ticks = 0;
            while (ticks < 600 && !plant.input().getStackInSlot(0).isEmpty()) {
                plant.energy().receiveEnergy(10_000, false);
                ReprocessingPlantBlockEntity.serverTick(level, plantPos, level.getBlockState(plantPos), plant);
                ticks++;
            }
            h.assertTrue(plant.input().getStackInSlot(0).isEmpty() && ticks < 600, "the one in the slot was split in " + ticks + " ticks");
            h.assertTrue(back.getCount() == 2, "the two that came back are untouched");

            // and by shift-click from the plant's screen: one of the two goes in, the other stays in the pocket
            player.getInventory().clearContent();
            player.getInventory().setItem(9, back);
            ReprocessingPlantMenu menu = new ReprocessingPlantMenu(1, player.getInventory(), plantPos);
            int firstPlayerSlot = menu.slots.size() - 36;
            menu.quickMoveStack(player, firstPlayerSlot);
            h.assertTrue(plant.input().getStackInSlot(0).getCount() == 1 && assemblies(player.getInventory()) == 1,
                    "shift-click moved one: slot " + plant.input().getStackInSlot(0) + ", pocket " + assemblies(player.getInventory()));
            menu.quickMoveStack(player, firstPlayerSlot);
            h.assertTrue(plant.input().getStackInSlot(0).getCount() == 1 && assemblies(player.getInventory()) == 1,
                    "a second shift-click finds the slot full and moves nothing");
            List<ItemStack> drops = plant.removalDrops();
            int dropped = 0;
            for (ItemStack d : drops) {
                if (d.is(BwrItems.FUEL_ASSEMBLY.get())) {
                    dropped += d.getCount();
                }
            }
            h.assertTrue(dropped == 1 && assemblies(player.getInventory()) == 1, "three went in: one split, one dropped, one in the pocket");

            // the fabricator's output holds the one assembly it made
            h.assertTrue(new FuelFabricatorBlockEntity(plantPos, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState()).output().getSlotLimit(0) == 1,
                    "the fabricator's output slot says it holds one");
        } finally {
            player.getInventory().clearContent();
            level.removeBlock(plantPos, false);
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 400)
    public static void theCoreMapAndTheBridgeTakeOneAssemblyFromAStackAndAFullPocketDropsAnUnload(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), new BlockPos(79_000, 190, 70_000), 4);
        FakePlayer player = NewFuelElevatorTests.player(f.level, "map", Vec3.atBottomCenterOf(f.o.offset(-6, 9, -8)));
        boolean before = GameplayConfig.INSTANT_REFUELLING.get();
        try {
            GameplayConfig.INSTANT_REFUELLING.set(true);
            f.fillPool(7.0);
            Inventory inventory = player.getInventory();
            RefuellingMenu menu = new RefuellingMenu(1, inventory, f.reactor.getBlockPos());
            var loading = f.reactor.core().getCoreLoading();
            inventory.setItem(0, fresh(4));
            inventory.selected = 0;

            // load: one of the four from the held stack
            menu.handleCommand(player, RefuellingMenu.CMD_LOAD, 0, 0);
            h.assertTrue(loading.isOccupied(f.corePosition(0)) && inventory.getItem(0).getCount() == 3, "the map loaded one and left three: " + inventory.getItem(0));
            h.assertTrue(f.assemblies() + assemblies(inventory) == 4, "four in all");

            // batch load: one per selected position, from the same stack
            menu.handleBatch(player, true, new int[]{1, 2, 1});
            h.assertTrue(loading.isOccupied(f.corePosition(1)) && loading.isOccupied(f.corePosition(2)) && inventory.getItem(0).getCount() == 1,
                    "the batch loaded two more and left one: " + inventory.getItem(0));
            h.assertTrue(f.assemblies() == 3 && assemblies(inventory) == 1, "three in the core, one in the pocket");

            // shift-click in the map moves a stack between the hotbar and the pocket, never into the core
            inventory.setItem(0, fresh(2));
            menu.quickMoveStack(player, 27);
            h.assertTrue(inventory.getItem(0).isEmpty() && assemblies(inventory) == 2 && f.assemblies() == 3, "a shift-clicked stack of two is still two, in the main inventory");
            inventory.clearContent();

            // unload into a pocket with room: the assembly comes out as one, with its discharge stamp
            menu.handleCommand(player, RefuellingMenu.CMD_UNLOAD, 0, 0);
            h.assertTrue(!loading.isOccupied(f.corePosition(0)) && assemblies(inventory) == 1 && stacks(inventory) == 1, "unloaded one into the pocket");
            ItemStack out = inventory.getItem(0);
            h.assertTrue(out.getCount() == 1 && SpentFuelHeat.dischargeTime(out) == f.level.getGameTime(), "one, stamped now: " + out);
            inventory.clearContent();

            // unload into a full pocket: the assembly is dropped at the player, never lost
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            menu.handleCommand(player, RefuellingMenu.CMD_UNLOAD, 1, 0);
            List<ItemEntity> dropped = f.level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6.0), e -> e.getItem().is(BwrItems.FUEL_ASSEMBLY.get()));
            int onTheFloor = 0;
            for (ItemEntity e : dropped) {
                onTheFloor += e.getItem().getCount();
                e.discard();
            }
            h.assertTrue(!loading.isOccupied(f.corePosition(1)) && assemblies(inventory) == 0 && onTheFloor == 1, "a full pocket: the assembly lies on the floor as one: " + onTheFloor);
            inventory.clearContent();

            // the bridge: the grapple takes the one assembly at a position and sets it down in a cell as one
            h.assertTrue(f.assemblies() == 1, "one left in the core");
            BlockPos rack = f.pool.racks.get(0);
            h.assertTrue(f.bridge.queue(new Move(Loc.core(f.corePosition(2)), Loc.rack(rack, 0))) == null, "queued to the pool");
            int n = 0;
            while (f.bridge.busy() && n < 40_000) {
                h.assertTrue(f.assemblies() == 1, "tick " + n + " in " + f.bridge.phase() + ": " + f.assemblies() + " assemblies");
                f.bridge.scheduledTick(f.level);
                n++;
            }
            h.assertTrue(n < 40_000, "the move never finished: " + f.bridge.phase());
            h.assertTrue(f.bridge.carried().isEmpty() && f.pool.be.stackAt(rack, 0).getCount() == 1 && !loading.isOccupied(f.corePosition(2)),
                    "the grapple is empty and the cell holds one: " + f.pool.be.stackAt(rack, 0));
        } finally {
            GameplayConfig.INSTANT_REFUELLING.set(before);
            player.getInventory().clearContent();
            f.dispose();
        }
        h.succeed();
    }
}
