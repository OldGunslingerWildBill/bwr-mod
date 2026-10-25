package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.core.refuelling.FuelElevatorMotion;
import dev.bwr.core.refuelling.FuelElevatorMotion.Kind;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlock;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelRackBlock;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The new fuel elevator on a real pool: it takes an assembly in its hatch (a right-click, a handler call, a real hopper) and lowers it down
 * the guide into the first free rack cell, refuses with the reason when the racks are full, the water is too low or it is not on a formed
 * pool, raises an assembly back on command, makes a hot assembly out of the water a radiation source, and keeps a trip through a save.
 * Namespace {@code bwrpool}. The block has no ticker: a trip is two scheduled ticks, one for the moment the assembly changes hands and one
 * for the carriage coming home; the synchronous tests use {@link NewFuelElevatorBlockEntity#advanceForTest} to let the clock run, and one
 * test lets the real scheduler do it.
 */
@GameTestHolder("bwrpool")
@PrefixGameTestTemplate(false)
public final class NewFuelElevatorTests {

    private NewFuelElevatorTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(66000 + 60 * i, 190, 66000);
    }

    /** A 9 x 9 x 8 pool with {@code racks} racks, water to {@code levelM}, and an elevator on the middle of its east wall. */
    static final class Site {
        final ServerLevel level;
        final SpentFuelPoolFixture pool;
        final BlockPos rim;
        final NewFuelElevatorBlockEntity be;

        Site(GameTestHelper h, int index, int racks, double levelM) {
            this.level = h.getLevel();
            this.pool = SpentFuelPoolFixture.build(level, at(index), 9, 9, 8, racks);
            if (!pool.be.isFormed()) {
                throw new AssertionError("the pool did not form: " + pool.be.problem());
            }
            if (levelM > 0.0) {
                pool.be.pool().restore(levelM * 49_000.0, 25.0);
            }
            this.rim = pool.min.offset(8, 8, 4);
            level.setBlock(rim, BwrBlocks.NEW_FUEL_ELEVATOR.get().defaultBlockState(), 3);
            this.be = NewFuelElevatorBlockEntity.at(level, rim);
            if (be == null) {
                throw new AssertionError("no block entity");
            }
        }

        void dispose() {
            be.removalDrops();
            level.setBlock(rim, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(rim.above(), Blocks.AIR.defaultBlockState(), 2);
            pool.dispose();
        }

        /** Lets this much game time pass for the trip. */
        void advance(long ticks) {
            be.advanceForTest(level, ticks);
        }

        Vec3 hatch() {
            return new Vec3(rim.getX() + 0.5 - 0.95, rim.getY() + FuelElevatorMotion.HATCH_ABOVE_BLOCK_M, rim.getZ() + 0.5);
        }
    }

    static ItemStack fresh() {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 0.0, 1.0));
    }

    static FakePlayer player(ServerLevel level, String name, Vec3 feet) {
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("new-fuel-elevator-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "elev-" + name));
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        p.setPos(feet.x, feet.y, feet.z);
        return p;
    }

    private static String english(NewFuelElevatorBlockEntity.Refusal why) {
        return why == null ? "" : why.english();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lowering
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 300)
    public static void aFreshAssemblyIsLoweredDownTheGuideIntoTheFirstFreeCellUnderWater(GameTestHelper h) {
        Site s = new Site(h, 0, 2, 7.0);
        try {
            var be = s.be;
            var link = be.link();
            h.assertTrue(link != null && link.pool() == s.pool.be, "the elevator found its pool: " + be.status().problem());
            h.assertTrue(link.inward() == Direction.WEST, "on the east wall the pool is to the west: " + link.inward());
            h.assertTrue(be.inwardIndex() == Direction.WEST.get2DDataValue() && be.floorY() == s.pool.min.getY() + 1, "the picture knows the pool and its floor");
            var in = fresh();
            h.assertTrue(be.offer(in, false) == null, "a fresh assembly is taken");
            h.assertTrue(be.busy() && be.tripKind() == Kind.LOWER && be.status().state().equals("lowering"), "the carriage is on its way down: " + be.status());
            h.assertTrue(s.pool.be.assemblyCount() == 0, "nothing is in a rack yet");
            var trip = be.currentTrip();
            h.assertTrue(trip != null && trip.handoffTicks() > 100 && trip.totalTicks() > trip.handoffTicks(), "a trip of real length: " + trip);
            BlockPos rack = s.pool.racks.get(0);
            h.assertTrue(be.tripCell().rack().equals(rack) && be.tripCell().cell() == 0, "it goes to the first free cell: " + be.tripCell());
            // the ticks pass, up to the moment before the assembly is seated
            s.advance(trip.handoffTicks() - 1);
            h.assertTrue(s.pool.be.assemblyCount() == 0 && be.busy(), "not yet seated one tick before");
            s.advance(1);
            h.assertTrue(s.pool.be.assemblyCount() == 1, "seated at the end of the leg: " + s.pool.be.assemblyCount());
            ItemStack seated = s.pool.be.stackAt(rack, 0);
            h.assertTrue(!seated.isEmpty() && seated.is(BwrItems.FUEL_ASSEMBLY.get()), "in the first cell of the first rack: " + seated);
            h.assertTrue(s.level.getBlockState(rack.above(3)).getValue(SpentFuelRackBlock.CELL[0]), "and its head shows in the rack");
            h.assertTrue(be.busy(), "the carriage is still coming home");
            s.advance(trip.totalTicks());
            h.assertTrue(!be.busy() && be.status().state().equals("idle"), "home and idle: " + be.status());
            // a second assembly takes the next cell
            h.assertTrue(be.offer(fresh(), false) == null, "another is taken");
            s.advance(be.currentTrip().totalTicks() + 5);
            h.assertTrue(s.pool.be.assemblyCount() == 2 && !s.pool.be.stackAt(rack, 1).isEmpty(), "it goes into the next cell");
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 900)
    public static void aTripRunsOnTheBlockSchedulerWithOnlyItsTwoMomentsScheduled(GameTestHelper h) {
        Site s = new Site(h, 1, 2, 7.0);
        var state = s.level.getBlockState(s.rim);
        h.assertTrue(((NewFuelElevatorBlock) state.getBlock()).getTicker(s.level, state, dev.bwr.mod.registry.BwrBlockEntities.NEW_FUEL_ELEVATOR.get()) == null,
                "the block has no ticker");
        h.assertTrue(s.be.offer(fresh(), false) == null, "taken");
        var trip = s.be.currentTrip();
        h.assertTrue(s.level.getBlockTicks().hasScheduledTick(s.rim, state.getBlock()), "the moment the assembly changes hands is scheduled");
        int handoff = trip.handoffTicks();
        int total = trip.totalTicks();
        // nothing happens until then: halfway the pool is still empty and the one scheduled tick is still pending
        h.runAfterDelay(handoff / 2, () -> {
            h.assertTrue(s.pool.be.assemblyCount() == 0 && s.be.busy(), "half way down nothing has changed hands");
            h.assertTrue(s.level.getBlockTicks().hasScheduledTick(s.rim, state.getBlock()), "and it is still the one scheduled tick");
        });
        h.runAfterDelay(handoff + 3, () -> {
            h.assertTrue(s.pool.be.assemblyCount() == 1, "the real scheduler seated the assembly: " + s.pool.be.assemblyCount());
            h.assertTrue(s.level.getBlockTicks().hasScheduledTick(s.rim, state.getBlock()), "and scheduled the carriage coming home");
        });
        h.runAfterDelay(total + 5, () -> {
            try {
                h.assertTrue(!s.be.busy() && s.pool.be.assemblyCount() == 1, "home: " + s.be.status());
                h.assertTrue(!s.level.getBlockTicks().hasScheduledTick(s.rim, state.getBlock()), "an idle elevator has nothing scheduled");
            } finally {
                s.dispose();
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 200)
    public static void aRightClickLoadsTheHatchAndShowsTheReasonWhenItRefuses(GameTestHelper h) {
        Site s = new Site(h, 2, 1, 7.0);
        try {
            FakePlayer p = player(s.level, "click", Vec3.atBottomCenterOf(s.rim.above(2)));
            p.setItemInHand(InteractionHand.MAIN_HAND, fresh());
            var hit = new BlockHitResult(Vec3.atCenterOf(s.rim), Direction.UP, s.rim, false);
            var state = s.level.getBlockState(s.rim);
            var result = state.useItemOn(p.getMainHandItem(), s.level, p, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(result.consumesAction() && p.getMainHandItem().isEmpty(), "the assembly went into the hatch: " + result);
            h.assertTrue(s.be.busy(), "and the carriage is away");
            // fill the pool: one rack holds four, one is on its way, three more go in by hand until it says no
            s.advance(s.be.currentTrip().totalTicks() + 5);
            for (int i = 0; i < 3; i++) {
                p.setItemInHand(InteractionHand.MAIN_HAND, fresh());
                state.useItemOn(p.getMainHandItem(), s.level, p, InteractionHand.MAIN_HAND, hit);
                h.assertTrue(p.getMainHandItem().isEmpty(), "loaded " + i);
                s.advance(s.be.currentTrip().totalTicks() + 5);
            }
            h.assertTrue(s.pool.be.assemblyCount() == 4, "the rack is full: " + s.pool.be.assemblyCount());
            p.setItemInHand(InteractionHand.MAIN_HAND, fresh());
            state.useItemOn(p.getMainHandItem(), s.level, p, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(!p.getMainHandItem().isEmpty() && !s.be.busy(), "refused: the assembly stays in the hand");
            h.assertTrue(english(s.be.lastRefusal()).contains("Every rack cell is taken"), "and the reason is kept: " + english(s.be.lastRefusal()));
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 200)
    public static void itRefusesWhenTheRacksAreFullCountingWhatIsAlreadyWaiting(GameTestHelper h) {
        Site s = new Site(h, 3, 1, 7.0);
        try {
            long now = s.level.getGameTime();
            // three of the four cells hold fuel; one assembly is promised the last one, so a second is refused
            for (int c = 0; c < 3; c++) {
                h.assertTrue(s.pool.be.insertAt(s.pool.racks.get(0), c, SpentFuelPoolFixture.assembly(0.0, 0, now)), "fixture: cell " + c);
            }
            h.assertTrue(s.be.offer(fresh(), false) == null, "the last free cell is promised to the first");
            var second = s.be.offer(fresh(), false);
            h.assertTrue(second != null && second.why() == NewFuelElevatorBlockEntity.Why.POOL_FULL && second.english().contains("Every rack cell is taken"),
                    "the second is refused with the reason: " + english(second));
            s.advance(s.be.currentTrip().totalTicks() + 5);
            h.assertTrue(s.pool.be.assemblyCount() == 4, "the first arrived");
            var third = s.be.offer(fresh(), true);
            h.assertTrue(third != null && third.why() == NewFuelElevatorBlockEntity.Why.POOL_FULL, "full: " + english(third));
            h.assertTrue(s.be.offer(new ItemStack(net.minecraft.world.item.Items.STICK), false).why() == NewFuelElevatorBlockEntity.Why.NOT_AN_ASSEMBLY,
                    "only assemblies");
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 200)
    public static void itRefusesWhenTheWaterIsTooLowForTheHoistAndAcceptsOnceItIsDeepEnough(GameTestHelper h) {
        Site s = new Site(h, 4, 2, 6.4);
        try {
            // racks are 4 m tall; an assembly crosses them 1 m up and needs 1.5 m of water over its handle there: 6.5 m of water
            var low = s.be.offer(fresh(), false);
            h.assertTrue(low != null && low.why() == NewFuelElevatorBlockEntity.Why.WATER_TOO_LOW && low.english().contains("too low")
                    && low.english().contains("1.5"), "6.4 m is too shallow: " + english(low));
            h.assertTrue(!s.be.busy() && s.be.status().queued() == 0, "nothing went in");
            s.pool.be.pool().restore(0.0, 25.0);
            var dry = s.be.offer(fresh(), false);
            h.assertTrue(dry != null && dry.why() == NewFuelElevatorBlockEntity.Why.WATER_TOO_LOW, "a dry pool: " + english(dry));
            s.pool.be.pool().restore(6.6 * 49_000.0, 25.0);
            h.assertTrue(s.be.offer(fresh(), false) == null, "6.6 m is enough");
            h.assertTrue(s.be.status().waterOverTransferM() > 1.5, "the screen reads the water over the handle: " + s.be.status().waterOverTransferM());
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 200)
    public static void itRefusesWhenItIsNotOnTheRimOfAFormedPool(GameTestHelper h) {
        Site s = new Site(h, 5, 2, 7.0);
        BlockPos corner = s.pool.min.offset(8, 8, 8);
        BlockPos floating = s.pool.min.offset(8, 12, 4);
        BlockPos ground = s.pool.min.offset(20, 0, 4);
        try {
            for (BlockPos p : new BlockPos[] {corner, floating, ground}) {
                if (!p.equals(corner)) {
                    // the corner already stands on a lining block of the pool: the others get a stone to stand on
                    s.level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 2);
                }
                s.level.setBlock(p, BwrBlocks.NEW_FUEL_ELEVATOR.get().defaultBlockState(), 3);
            }
            var onCorner = NewFuelElevatorBlockEntity.at(s.level, corner).offer(fresh(), false);
            h.assertTrue(onCorner != null && onCorner.why() == NewFuelElevatorBlockEntity.Why.NOT_ON_POOL && onCorner.english().contains("rim"),
                    "a corner is not a straight run: " + english(onCorner));
            h.assertTrue(NewFuelElevatorBlockEntity.at(s.level, floating).offer(fresh(), false).why() == NewFuelElevatorBlockEntity.Why.NOT_ON_POOL, "not on the rim row");
            h.assertTrue(NewFuelElevatorBlockEntity.at(s.level, ground).offer(fresh(), false).why() == NewFuelElevatorBlockEntity.Why.NOT_ON_POOL, "on the ground");
            // the right place, but the pool is broken
            BlockPos hole = s.pool.min.offset(8, 3, 2);
            s.level.setBlock(hole, Blocks.AIR.defaultBlockState(), 2);
            s.pool.be.scanNow();
            var unformed = s.be.offer(fresh(), false);
            h.assertTrue(unformed != null && unformed.why() == NewFuelElevatorBlockEntity.Why.POOL_UNFORMED && unformed.english().contains("not formed"),
                    "an unformed pool: " + english(unformed));
            h.assertTrue(!s.be.status().linked() && s.be.status().state().equals("unlinked"), "the screen says unlinked: " + s.be.status());
        } finally {
            for (BlockPos p : new BlockPos[] {corner, floating, ground}) {
                var be = NewFuelElevatorBlockEntity.at(s.level, p);
                if (be != null) {
                    be.removalDrops();
                }
                s.level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                if (!p.equals(corner)) {
                    s.level.setBlock(p.below(), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            s.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hopper and handler
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 400)
    public static void aHopperFeedsTheHatchAndTheHandlerTakesOneAtATime(GameTestHelper h) {
        Site s = new Site(h, 6, 2, 7.0);
        BlockPos hopperAt = s.rim.above();
        s.level.setBlock(hopperAt, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), 3);
        BlockEntity hopper = s.level.getBlockEntity(hopperAt);
        h.assertTrue(hopper instanceof HopperBlockEntity, "fixture: a hopper");
        var handler = s.level.getCapability(Capabilities.ItemHandler.BLOCK, s.rim, null);
        h.assertTrue(handler != null && handler.getSlots() == NewFuelElevatorBlockEntity.HATCH_SLOTS + 1, "the elevator is an item handler: " + handler);
        ((HopperBlockEntity) hopper).setItem(0, fresh());
        ((HopperBlockEntity) hopper).setItem(1, fresh());
        h.runAfterDelay(40, () -> {
            try {
                h.assertTrue(s.be.busy() && s.be.queued() == 2, "the hopper fed the hatch with both: " + s.be.status());
                h.assertTrue(((HopperBlockEntity) hopper).isEmpty(), "and is empty");
                // the carriage takes them one after the other
                for (int i = 0; i < 4 && (s.be.busy() || s.be.queued() > 0); i++) {
                    s.advance(s.be.currentTrip().totalTicks() + 5);
                }
                h.assertTrue(s.pool.be.assemblyCount() == 2 && !s.be.busy(), "both are in the racks: " + s.pool.be.assemblyCount());
                // a full hatch says no to the handler
                for (int i = 0; i < NewFuelElevatorBlockEntity.HATCH_SLOTS + 1; i++) {
                    handler.insertItem(0, fresh(), false);
                }
                h.assertTrue(s.be.queued() <= NewFuelElevatorBlockEntity.HATCH_SLOTS + 1, "the hatch holds four and one is on its way: " + s.be.queued());
                h.assertTrue(!handler.insertItem(0, fresh(), true).isEmpty(), "a full hatch refuses what is offered");
            } finally {
                s.level.setBlock(hopperAt, Blocks.AIR.defaultBlockState(), 2);
                s.dispose();
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Raising and the dose
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 300)
    public static void anAssemblyIsRaisedToTheHatchOnCommandAndOnlyThenTakenOutOfItsCell(GameTestHelper h) {
        Site s = new Site(h, 7, 2, 7.0);
        try {
            long now = s.level.getGameTime();
            var none = s.be.raise(null);
            h.assertTrue(none != null && none.why() == NewFuelElevatorBlockEntity.Why.NOTHING_TO_RAISE, "nothing to raise: " + english(none));
            // a hot one (discharged now) and a long-cooled one (no discharge record: five years); the one that has cooled longest comes up
            s.pool.be.insertAt(s.pool.racks.get(0), 0, SpentFuelPoolFixture.assembly(45000.0, 0.0, now));
            s.pool.be.insertAt(s.pool.racks.get(0), 1, FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),
                    new FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0)));
            s.pool.be.simulateSeconds(1, now);
            h.assertTrue(s.be.raise(null) == null && s.be.tripKind() == Kind.RAISE, "the carriage goes down empty: " + s.be.status());
            h.assertTrue(english(s.be.raise(null)).contains("busy"), "a second command while it is busy is refused");
            var trip = s.be.currentTrip();
            s.advance(trip.handoffTicks() - 1);
            h.assertTrue(s.pool.be.assemblyCount() == 2, "the assembly stays in its cell until the carriage has arrived");
            s.advance(1);
            h.assertTrue(s.pool.be.assemblyCount() == 1 && s.pool.be.stackAt(s.pool.racks.get(0), 1).isEmpty()
                    && !s.pool.be.stackAt(s.pool.racks.get(0), 0).isEmpty(), "the coolest was taken: cell 2 is empty, the hot one still there: count "
                    + s.pool.be.assemblyCount() + ", cell 1 " + s.pool.be.stackAt(s.pool.racks.get(0), 0).isEmpty() + ", cell 2 "
                    + s.pool.be.stackAt(s.pool.racks.get(0), 1).isEmpty() + ", trip " + s.be.tripCell() + ", " + s.be.status());
            s.advance(trip.totalTicks());
            h.assertTrue(!s.be.busy() && !s.be.landing().isEmpty(), "it waits at the hatch: " + s.be.status());
            h.assertTrue(english(s.be.raise(null)).contains("take it out"), "a second raise is refused while one waits");
            var out = s.be.takeLanding();
            h.assertTrue(!out.isEmpty() && dev.bwr.mod.fuel.FuelAssemblies.dataOf(out).burnupMwdPerTonne() == 45000.0
                    && dev.bwr.mod.fuel.SpentFuelHeat.dischargeTime(out) == -1L, "the same assembly, burnup and (lack of) stamp kept: " + out);
            h.assertTrue(s.be.landing().isEmpty(), "taken");
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 300)
    public static void aHotAssemblyOutOfTheWaterIsARadiationSourceAndUnderItIsNot(GameTestHelper h) {
        Site s = new Site(h, 8, 2, 7.0);
        try {
            long now = s.level.getGameTime();
            // fresh fuel is no source at all, in the hatch or on its way
            Vec3 probe = s.hatch().add(0.0, 3.0, 0.0);
            s.be.offer(fresh(), false);
            h.assertTrue(RadiationSources.doseRateMsvPerHour(s.level, probe) == 0.0, "new fuel makes no dose: " + RadiationSources.doseRateMsvPerHour(s.level, probe));
            s.advance(s.be.currentTrip().totalTicks() + 5);
            h.assertTrue(!s.pool.be.extractFrom(s.pool.racks.get(0), 0).isEmpty(), "fixture: the fresh assembly is taken out again");

            // a just-discharged assembly raised out of its cell: under the water on the way down, the handle in the air at the hatch
            ItemStack hot = SpentFuelPoolFixture.assembly(45000.0, 0.0, now);
            s.pool.be.insertAt(s.pool.racks.get(0), 3, hot);
            s.pool.be.simulateSeconds(1, now);
            double seated = RadiationSources.doseRateMsvPerHour(s.level, probe);
            h.assertTrue(s.be.raise(new dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity.CellRef(s.pool.racks.get(0), 3)) == null, "raise");
            var trip = s.be.currentTrip();
            s.advance(trip.handoffTicks());
            double underWater = RadiationSources.doseRateMsvPerHour(s.level, probe);
            s.advance(trip.legTicks()[1] + trip.legTicks()[2]);
            // ascending, about a third of the way up: still under the water
            s.advance(trip.legTicks()[3] / 3);
            double ascending = RadiationSources.doseRateMsvPerHour(s.level, probe);
            s.advance(trip.totalTicks());
            double atHatch = RadiationSources.doseRateMsvPerHour(s.level, probe);
            h.assertTrue(!s.be.landing().isEmpty(), "it landed");
            h.assertTrue(atHatch > 1.0e5, "out of the water at the hatch the meter spikes: " + atHatch + " mSv/h");
            h.assertTrue(atHatch > 1.0e4 * underWater, "under the water the same assembly reads far lower: " + underWater + " against " + atHatch);
            h.assertTrue(ascending > underWater && ascending < atHatch, "and it rises as the carriage does: " + underWater + " < " + ascending + " < " + atHatch);
            h.assertTrue(seated < atHatch / 1.0e4, "seated in its cell it reads low: " + seated);
            System.out.println("NEW FUEL ELEVATOR DOSE: seated " + seated + " mSv/h, in flight under the water " + underWater + ", rising " + ascending + ", at the hatch " + atHatch);
            // taking it away takes the source away
            s.be.takeLanding();
            h.assertTrue(RadiationSources.doseRateMsvPerHour(s.level, probe) == 0.0, "gone with the assembly");
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saves and the old paths
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 300)
    public static void aTripSurvivesASaveAndReloadAndAnOldElevatorLoadsEmpty(GameTestHelper h) {
        Site s = new Site(h, 9, 2, 7.0);
        try {
            var plain = s.be.saveWithoutMetadata(s.level.registryAccess());
            for (String key : new String[] {"Hatch", "Landing", "Trip"}) {
                h.assertTrue(!plain.contains(key), "an idle elevator saved " + key);
            }
            h.assertTrue(s.be.offer(fresh(), false) == null && s.be.offer(fresh(), false) == null, "two taken: one on the way, one waiting");
            var trip = s.be.currentTrip();
            s.advance(trip.legTicks()[0] + 10);
            var tag = s.be.saveWithFullMetadata(s.level.registryAccess());
            h.assertTrue(tag.contains("Trip") && tag.contains("Hatch") && tag.getCompound("Trip").contains("Flying"), "the trip and the waiting assembly are saved: " + tag);
            s.level.removeBlockEntity(s.rim);
            var restored = (NewFuelElevatorBlockEntity) BlockEntity.loadStatic(s.rim, s.level.getBlockState(s.rim), tag, s.level.registryAccess());
            h.assertTrue(restored != null, "load");
            s.level.setBlockEntity(restored);
            h.assertTrue(restored.busy() && restored.tripKind() == Kind.LOWER && restored.queued() == 2, "still on its way down with one waiting: " + restored.status());
            h.assertTrue(restored.currentTrip().totalTicks() == trip.totalTicks() && restored.tripStart() == s.be.tripStart(), "the same trip");
            restored.advanceForTest(s.level, restored.currentTrip().totalTicks() + 5);
            restored.advanceForTest(s.level, restored.currentTrip().totalTicks() + 5);
            h.assertTrue(s.pool.be.assemblyCount() == 2 && !restored.busy(), "both arrived after the reload: " + s.pool.be.assemblyCount());
            // an old world: an elevator block entity with nothing saved
            var old = new NewFuelElevatorBlockEntity(s.rim, s.level.getBlockState(s.rim));
            old.loadWithComponents(new CompoundTag(), s.level.registryAccess());
            h.assertTrue(!old.busy() && old.queued() == 0 && old.landing().isEmpty(), "an empty tag loads as an idle elevator");
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 200)
    public static void theExistingRackAndControllerPathsStillWork(GameTestHelper h) {
        Site s = new Site(h, 10, 2, 7.0);
        try {
            long now = s.level.getGameTime();
            // the controller's own handler (hoppers and pipes)
            var handler = s.level.getCapability(Capabilities.ItemHandler.BLOCK, s.pool.controller, null);
            h.assertTrue(handler != null && handler.getSlots() == 8, "the controller is still an item handler of one slot a cell: " + (handler == null ? -1 : handler.getSlots()));
            h.assertTrue(handler.insertItem(0, SpentFuelPoolFixture.assembly(1000.0, 0, now), false).isEmpty(), "a pipe fills a cell");
            // a plain insert and an insert into a chosen rack and cell
            h.assertTrue(s.pool.be.insertAnywhere(SpentFuelPoolFixture.assembly(1000.0, 0, now)), "insertAnywhere");
            h.assertTrue(s.pool.be.insertAt(s.pool.racks.get(1), 2, SpentFuelPoolFixture.assembly(1000.0, 0, now)), "insertAt");
            // a right-click on the controller with an assembly in hand
            FakePlayer p = player(s.level, "controller", Vec3.atBottomCenterOf(s.pool.controller.west(3)));
            p.setItemInHand(InteractionHand.MAIN_HAND, SpentFuelPoolFixture.assembly(1000.0, 0, now));
            var hit = new BlockHitResult(Vec3.atCenterOf(s.pool.controller), Direction.WEST, s.pool.controller, false);
            var state = s.level.getBlockState(s.pool.controller);
            state.useItemOn(p.getMainHandItem(), s.level, p, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(p.getMainHandItem().isEmpty() && s.pool.be.assemblyCount() == 4, "a right-click on the controller still loads a cell: " + s.pool.be.assemblyCount());
            // and on a rack
            p.setItemInHand(InteractionHand.MAIN_HAND, SpentFuelPoolFixture.assembly(1000.0, 0, now));
            BlockPos rackTop = s.pool.racks.get(1).above(3);
            var rackHit = new BlockHitResult(Vec3.atCenterOf(rackTop), Direction.UP, rackTop, false);
            s.level.getBlockState(rackTop).useItemOn(p.getMainHandItem(), s.level, p, InteractionHand.MAIN_HAND, rackHit);
            h.assertTrue(p.getMainHandItem().isEmpty() && s.pool.be.assemblyCount() == 5, "a right-click on a rack still loads it: " + s.pool.be.assemblyCount());
            h.assertTrue(BwrText.english(s.pool.be.statusComponents().get(0)).contains("5 of 8"), "the pool counts them: " + BwrText.english(s.pool.be.statusComponents().get(0)));
        } finally {
            s.dispose();
        }
        h.succeed();
    }

    /** The computer face of the elevator, when CC:Tweaked is present. */
    @GameTest(template = "empty", templateNamespace = "bwrpool", batch = "bwrelevator", timeoutTicks = 300)
    public static void theComputerFaceOfTheElevatorReadsAndRaises(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("NEW FUEL ELEVATOR CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        NewFuelElevatorCcChecks.run(h, new Site(h, 11, 2, 7.0));
        System.out.println("NEW FUEL ELEVATOR CC: bwr_new_fuel_elevator getStatus, raise, raiseCell and retry agree with the block entity");
        h.succeed();
    }
}
