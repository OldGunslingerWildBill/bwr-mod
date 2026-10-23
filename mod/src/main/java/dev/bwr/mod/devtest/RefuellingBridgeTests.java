package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.radiation.RadiationField;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The refuelling bridge through its real blocks: a formed reactor with its head off, a formed spent fuel pool, a cattle chute
 * with a gate, two runs of rail and the console. The bridge is stepped a tick at a time the way its scheduled block tick would
 * ({@link RefuellingBridgeBlockEntity#scheduledTick}), so a move of minutes takes no time; one test lets the real scheduler
 * drive it. Own namespace ({@code bwrbridge}); {@code -PbwrOnlyBridge} runs just these.
 */
@GameTestHolder("bwrbridge")
@PrefixGameTestTemplate(false)
public final class RefuellingBridgeTests {

    private RefuellingBridgeTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(70000 + 300 * i, 190, 70000);
    }

    private static Move coreToCell(RefuellingBridgeFixture f, int coreIndex, int rack, int cell) {
        return new Move(Loc.core(f.corePosition(coreIndex)), Loc.rack(f.pool.racks.get(rack), cell));
    }

    private static Move cellToCore(RefuellingBridgeFixture f, int rack, int cell, int coreIndex) {
        return new Move(Loc.rack(f.pool.racks.get(rack), cell), Loc.core(f.corePosition(coreIndex)));
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    private static void ready(GameTestHelper h, RefuellingBridgeFixture f) {
        BridgeSite site = f.bridge.refreshSite();
        h.assertTrue(site.ok(), "the bridge did not survey its rails: " + english(site.problem));
        h.assertTrue(site.reactor(f.level) != null && site.pool(f.level) != null, "the bridge did not find the reactor and the pool: reactor "
                + site.reactorPos + " formed " + f.reactor.isFormed() + " " + f.reactor.statusLines() + "; pool " + site.poolPos + " formed " + f.pool.be.isFormed()
                + " " + (f.pool.be.problem() == null ? "" : english(f.pool.be.problem())) + "; rails a " + site.aLo + ".." + site.aHi + " c " + site.cLow + ".." + site.cHigh
                + " y " + site.railY + " axis " + site.axis + "; registry " + dev.bwr.mod.spentfuel.SpentFuelPoolRegistry.controllers(f.level).size()
                + " pools, layout " + (f.pool.be.layout() == null ? "null" : f.pool.be.layout().min() + ".." + f.pool.be.layout().max()) + " at " + f.pool.be.getBlockPos());
        h.assertTrue(f.bridge.chute().state() == BridgeSite.ChuteState.OPEN, "the chute is " + f.bridge.chute().state());
        double water = BridgeSite.cavityWaterM(site.reactor(f.level));
        h.assertTrue(water > 2.6, "the cavity has the reactor model's water over the fuel: " + water + " m");
    }

    /** Steps to the end of a move, checking that fuel is never duplicated or lost on the way. Returns the ticks used. */
    private static int run(GameTestHelper h, RefuellingBridgeFixture f, int expectedAssemblies) {
        int n = 0;
        while (f.bridge.busy() && n < 40_000) {
            h.assertTrue(f.assemblies() == expectedAssemblies, "tick " + n + " in " + f.bridge.phase() + ": " + f.assemblies() + " assemblies, expected " + expectedAssemblies);
            f.record();
            f.bridge.scheduledTick(f.level);
            n++;
        }
        f.record();
        h.assertTrue(n < 40_000, "the move never finished: " + f.bridge.phase());
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The whole job
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void aFullMoveFromTheCoreToThePoolAndBack(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(0), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            ItemStack in = RefuellingBridgeFixture.fresh(30000.0);
            h.assertTrue(f.reactor.loadAssembly(slot, in), "load");
            long stampBefore = SpentFuelHeat.dischargeTime(in);
            h.assertTrue(stampBefore == -1L, "a new assembly has no stamp");

            Component why = f.bridge.queue(coreToCell(f, 0, 0, 1));
            h.assertTrue(why == null, "the move was refused: " + english(why));
            h.assertTrue(f.bridge.busy() && f.bridge.phase() == Phase.TRAVEL, "it starts travelling: " + f.bridge.phase());
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot), "nothing has left the core yet");
            long latchedAt = f.level.getGameTime();
            int ticks = run(h, f, 1);

            h.assertTrue(!f.reactor.core().getCoreLoading().isOccupied(slot), "the core position is empty");
            ItemStack stored = f.pool.be.stackAt(f.pool.racks.get(0), 1);
            h.assertTrue(!stored.isEmpty() && stored.is(BwrItems.FUEL_ASSEMBLY.get()), "the assembly is in rack 1 cell 2");
            h.assertTrue(FuelAssemblies.dataOf(stored).burnupMwdPerTonne() == 30000.0, "burnup travels with it: " + FuelAssemblies.dataOf(stored));
            h.assertTrue(SpentFuelHeat.dischargeTime(stored) == latchedAt, "the discharge time is stamped on unload: " + SpentFuelHeat.dischargeTime(stored) + " at " + latchedAt);
            h.assertTrue(f.bridge.carried().isEmpty() && f.bridge.phase() == Phase.IDLE && f.bridge.queued() == 0, "the grapple is empty and the bridge idle");
            List<Phase> expected = List.of(Phase.TRAVEL, Phase.LOWER_EMPTY, Phase.LATCH, Phase.RAISE_LOADED, Phase.TRANSIT,
                    Phase.LOWER_LOADED, Phase.RELEASE, Phase.RAISE_EMPTY, Phase.IDLE);
            h.assertTrue(f.phases.equals(expected), "phases " + f.phases);
            h.assertTrue(ticks > 20 * 30 && ticks < 20 * 400, "a transfer takes the better part of a minute, not seconds or hours: " + ticks + " ticks");

            // and back, to another position, through the chute the other way
            f.phases.clear();
            why = f.bridge.queue(cellToCore(f, 0, 1, 3));
            h.assertTrue(why == null, "the return was refused: " + english(why));
            int back = run(h, f, 1);
            int slot3 = f.corePosition(3);
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot3), "it is in core position 3");
            h.assertTrue(f.pool.be.assemblyCount() == 0, "the rack is empty");
            h.assertTrue(f.reactor.core().getCoreLoading().assemblyAt(slot3).burnupMwdPerTonne() == 30000.0, "exposure intact");
            h.assertTrue(f.phases.get(0) == Phase.TRAVEL && f.phases.contains(Phase.TRANSIT), "phases back " + f.phases);
            h.assertTrue(back > 20 * 30, "the return takes time too: " + back);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void aClosedGateBlocksTheMoveAndOpeningItLetsTheBridgeContinue(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(1), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));

            f.closeGate();
            f.bridge.refreshSite();
            h.assertTrue(f.bridge.chute().state() == BridgeSite.ChuteState.CLOSED, "chute " + f.bridge.chute().state());
            Component why = f.bridge.queue(coreToCell(f, 0, 0, 0));
            h.assertTrue(why != null && english(why).contains("gate") && english(why).contains("closed"), "refused for the gate: " + english(why));
            h.assertTrue(!f.bridge.busy() && f.bridge.queued() == 0 && f.assemblies() == 1
                    && f.reactor.core().getCoreLoading().isOccupied(slot), "nothing moved, nothing queued");
            // a move that stays on one side of the gate is fine with it shut
            Component inside = f.bridge.queue(new Move(Loc.core(slot), Loc.core(f.corePosition(1))));
            h.assertTrue(inside == null, "a core to core move does not need the gate: " + english(inside));
            run(h, f, 1);
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(f.corePosition(1)) && !f.reactor.core().getCoreLoading().isOccupied(slot), "it moved within the core");

            // a gate shut during the transit stops the bridge with the assembly on the grapple
            f.openGate();
            f.bridge.refreshSite();
            h.assertTrue(f.bridge.queue(coreToCell(f, 1, 0, 0)) == null, "queued with the gate open");
            f.runUntil(Phase.TRANSIT, 20_000);
            h.assertTrue(f.bridge.phase() == Phase.TRANSIT && !f.bridge.carried().isEmpty(), "in transit holding the assembly: " + f.bridge.phase());
            f.closeGate();
            int more = run(h, f, 1);
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "the bridge halts at the shut gate: " + f.bridge.phase() + " after " + more);
            h.assertTrue(english(f.bridge.haltMessage()).contains("closed"), "and says so: " + english(f.bridge.haltMessage()));
            h.assertTrue(!f.bridge.carried().isEmpty() && f.assemblies() == 1, "the assembly is still on the grapple");
            for (int i = 0; i < 400; i++) {
                f.bridge.scheduledTick(f.level);
            }
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "a halted bridge waits for a player, however long: " + f.bridge.phase());
            f.bridge.resume();
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "resuming against a shut gate halts again");
            f.openGate();
            f.bridge.resume();
            h.assertTrue(f.bridge.busy(), "with the gate open it resumes: " + f.bridge.phase());
            run(h, f, 1);
            h.assertTrue(!f.pool.be.stackAt(f.pool.racks.get(0), 0).isEmpty() && f.bridge.carried().isEmpty(), "and finishes the job");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The hoist limit
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void theHoistLimitRefusesALiftWithTooLittleWaterUntilItIsOverridden(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(2), 4);
        try {
            ready(h, f);
            f.fillPool(5.0); // 1 m over the 4 m racks: a 1 m lift would leave none
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));
            h.assertTrue(f.bridge.queue(coreToCell(f, 0, 0, 0)) == null, "queued");
            run(h, f, 1);
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "the interlock halts the bridge: " + f.bridge.phase());
            h.assertTrue(english(f.bridge.haltMessage()).contains("Hoist limit"), "with the reason: " + english(f.bridge.haltMessage()));
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot) && f.bridge.carried().isEmpty(),
                    "the assembly was never lifted: it is still on its seat");
            h.assertTrue(f.assemblies() == 1, "one assembly");
            f.bridge.resume();
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "resume does not get past the interlock");

            f.bridge.setOverride(true);
            f.bridge.resume();
            h.assertTrue(f.bridge.busy(), "overridden, it goes on");
            run(h, f, 1);
            h.assertTrue(!f.pool.be.stackAt(f.pool.racks.get(0), 0).isEmpty() && !f.reactor.core().getCoreLoading().isOccupied(slot), "and the move completes");

            // water back: the interlock is satisfied without any override
            f.bridge.setOverride(false);
            f.fillPool(7.0);
            h.assertTrue(f.bridge.queue(cellToCore(f, 0, 0, 2)) == null, "queued back");
            run(h, f, 1);
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(f.corePosition(2)), "with 3 m over the racks it needs no override");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dose
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void theDoseRisesWhenTheAssemblyIsLiftedAndSpikesOutOfTheWater(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(3), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            long now = f.level.getGameTime();
            var rack = f.pool.racks.get(0);
            h.assertTrue(f.pool.be.insertAt(rack, 0, SpentFuelPoolFixture.assembly(45000.0, 0.0, now)), "stored a just-discharged assembly");
            f.pool.be.simulateSeconds(1, now);
            // an observer on the deck over the rack, 4.5 m above the rack top
            double[] col = BridgeSite.cellColumn(rack, 0);
            Vec3 probe = new Vec3(col[0], rack.getY() + 4 + 4.5, col[1]);
            double seated = RadiationField.doseRateMsvPerHour(f.level, probe);
            h.assertTrue(seated > 0.0, "the stored fuel reads on the meter: " + seated);

            // lifted 1 m inside the interlock: 2 m of water over the handle
            h.assertTrue(f.bridge.queue(new Move(Loc.rack(rack, 0), Loc.rack(rack, 1))) == null, "queued");
            f.runUntil(Phase.TRANSIT, 20_000);
            h.assertTrue(f.bridge.phase() == Phase.TRANSIT && !f.bridge.carried().isEmpty(), "lifted and carrying: " + f.bridge.phase());
            double lifted = RadiationField.doseRateMsvPerHour(f.level, probe);
            h.assertTrue(Math.abs(f.bridge.carriedCoverM() - 2.0) < 1e-6, "2 m of water over the handle: " + f.bridge.carriedCoverM());
            h.assertTrue(lifted > 100.0 * seated, "lifting one metre raised the dose from " + seated + " to " + lifted + " mSv/h");
            h.assertTrue(RadiationSources.pointCount(f.level) >= 1, "the carried assembly is registered as a source");
            run(h, f, 1);
            double after = RadiationField.doseRateMsvPerHour(f.level, probe);
            h.assertTrue(after < lifted / 100.0 && after > 0.0, "back on its seat the dose falls again: " + after);

            // the interlock overridden: the whole 4.5 m lift brings the handle out of the water
            f.bridge.setOverride(true);
            h.assertTrue(f.bridge.queue(new Move(Loc.rack(rack, 1), Loc.rack(rack, 0))) == null, "queued overridden");
            f.runUntil(Phase.TRANSIT, 20_000);
            double high = RadiationField.doseRateMsvPerHour(f.level, probe);
            h.assertTrue(f.bridge.carriedCoverM() == 0.0, "the handle is in the air: " + f.bridge.carriedCoverM());
            h.assertTrue(high > 1000.0 * lifted && high > 1000.0, "out of the water the meter spikes: " + high + " mSv/h against " + lifted);
            run(h, f, 1);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void aDrainedChuteStopsTheBridgeAndMakesTheMeterSpikeIfItIsOverridden(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(4), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(45000.0));
            f.drainCanal();
            f.bridge.refreshSite();
            h.assertTrue(f.bridge.chute().state() == BridgeSite.ChuteState.DRAINED, "chute " + f.bridge.chute().state());
            h.assertTrue(f.bridge.queue(coreToCell(f, 0, 0, 0)) == null, "a drained chute can be queued; the interlock answers it");
            run(h, f, 1);
            h.assertTrue(f.bridge.phase() == Phase.HALTED && english(f.bridge.haltMessage()).contains("drained"),
                    "halted for the drained canal: " + f.bridge.phase() + " " + english(f.bridge.haltMessage()));
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot), "still on its seat");
            // refilled by a player with a bucket (the block state), the same move goes through
            f.setCanalWater(true);
            f.bridge.resume();
            h.assertTrue(f.bridge.busy(), "with water in the chute it resumes");
            run(h, f, 1);
            h.assertTrue(!f.pool.be.stackAt(f.pool.racks.get(0), 0).isEmpty(), "and completes");

            // drained and overridden: through the chute with nothing over the handle
            f.drainCanal();
            f.bridge.setOverride(true);
            f.bridge.refreshSite();
            h.assertTrue(f.bridge.queue(cellToCore(f, 0, 0, 1)) == null, "queued");
            f.runUntil(Phase.TRANSIT, 20_000);
            // the second and third legs are the chute
            for (int i = 0; i < 4000 && f.bridge.busy(); i++) {
                f.bridge.scheduledTick(f.level);
                Vec3 tip = new Vec3(f.bridge.tip().x(), f.bridge.tip().y(), f.bridge.tip().z());
                if (f.bridge.site().bodyAt(f.level, tip.x, tip.z, f.bridge.chute()) == BridgeSite.Body.CHUTE) {
                    h.assertTrue(f.bridge.carriedCoverM() == 0.0, "no water in the drained chute: " + f.bridge.carriedCoverM());
                    double dose = RadiationField.doseRateMsvPerHour(f.level, tip.add(0.0, 3.0, 0.0));
                    h.assertTrue(dose > 1000.0, "in the drained chute the meter spikes: " + dose + " mSv/h");
                    break;
                }
            }
            run(h, f, 1);
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(f.corePosition(1)), "it arrives");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // A destination that fills up, save and reload, idling
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void aDestinationThatFillsUpHaltsWithTheAssemblyStillOnTheGrapple(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(5), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));
            var rack = f.pool.racks.get(0);
            h.assertTrue(f.bridge.queue(coreToCell(f, 0, 0, 2)) == null, "queued");
            f.runUntil(Phase.LOWER_LOADED, 20_000);
            // somebody drops an assembly into the cell by hand
            h.assertTrue(f.pool.be.insertAt(rack, 2, RefuellingBridgeFixture.fresh(1000.0)), "a hand fills the cell");
            run(h, f, 2);
            h.assertTrue(f.bridge.phase() == Phase.HALTED, "halted: " + f.bridge.phase());
            h.assertTrue(english(f.bridge.haltMessage()).contains("cannot be set down"), "says why: " + english(f.bridge.haltMessage()));
            h.assertTrue(!f.bridge.carried().isEmpty() && f.assemblies() == 2, "nothing lost, nothing doubled");
            // the blocking assembly leaves; the player resumes
            f.pool.be.extractFrom(rack, 2);
            f.bridge.resume();
            run(h, f, 1);
            h.assertTrue(!f.pool.be.stackAt(rack, 2).isEmpty() && f.bridge.carried().isEmpty(), "set down at last");
            // a held assembly can be set down elsewhere
            f.pool.be.extractFrom(rack, 2);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(2000.0));
            h.assertTrue(f.bridge.queue(coreToCell(f, 0, 0, 3)) == null, "queued again");
            f.runUntil(Phase.LOWER_LOADED, 20_000);
            f.pool.be.insertAt(rack, 3, RefuellingBridgeFixture.fresh(1000.0));
            run(h, f, 2);
            h.assertTrue(f.bridge.phase() == Phase.HALTED && !f.bridge.carried().isEmpty(), "held again");
            f.bridge.abort();
            h.assertTrue(f.bridge.phase() == Phase.IDLE && !f.bridge.carried().isEmpty() && f.bridge.queued() == 0, "aborted: idle, still holding");
            Component refused = f.bridge.queue(coreToCell(f, 1, 0, 1));
            h.assertTrue(refused != null && english(refused).contains("holds"), "a new pick up waits for the held assembly: " + english(refused));
            h.assertTrue(f.bridge.queue(new Move(Loc.HELD, Loc.rack(rack, 1))) == null, "set it down in a free cell");
            run(h, f, 2);
            h.assertTrue(f.bridge.carried().isEmpty() && !f.pool.be.stackAt(rack, 1).isEmpty(), "the held assembly is in cell 2");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void aMoveSavedHalfWayAndLoadedAgainFinishesTheSameJob(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(6), 4);
        try {
            ready(h, f);
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));
            f.reactor.loadAssembly(f.corePosition(1), RefuellingBridgeFixture.fresh(31000.0));
            h.assertTrue(f.bridge.queue(coreToCell(f, 0, 0, 1)) == null, "queued");
            h.assertTrue(f.bridge.queue(coreToCell(f, 1, 0, 2)) == null, "and a second behind it");
            f.runUntil(Phase.TRANSIT, 20_000);
            for (int i = 0; i < 100; i++) {
                f.bridge.scheduledTick(f.level);
            }
            h.assertTrue(f.bridge.phase() == Phase.TRANSIT && !f.bridge.carried().isEmpty(), "in transit: " + f.bridge.phase());
            double progress = f.bridge.progress();
            var tipBefore = f.bridge.tip();
            CompoundTag saved = f.bridge.saveWithoutMetadata(f.level.registryAccess());

            // the world is closed and opened again: a new block entity reads the saved tag
            BlockPos pos = f.console;
            f.level.removeBlockEntity(pos);
            var again = new RefuellingBridgeBlockEntity(pos, f.level.getBlockState(pos));
            again.setLevel(f.level);
            again.loadWithComponents(saved, f.level.registryAccess());
            f.level.setBlockEntity(again);
            f.bridge = again;
            h.assertTrue(again.phase() == Phase.TRANSIT && !again.carried().isEmpty(), "same phase after the reload: " + again.phase());
            h.assertTrue(again.queued() == 1, "the queue is back: " + again.queued());
            h.assertTrue(Math.abs(again.progress() - progress) < 1e-9, "same progress " + again.progress() + " against " + progress);
            h.assertTrue(Math.abs(again.tip().x() - tipBefore.x()) < 1e-9 && Math.abs(again.tip().y() - tipBefore.y()) < 1e-9, "the grapple is where it was");
            h.assertTrue(f.assemblies() == 2, "two assemblies in the world");
            run(h, f, 2);
            h.assertTrue(again.phase() == Phase.IDLE && again.queued() == 0, "both moves ran: " + again.phase());
            h.assertTrue(!f.pool.be.stackAt(f.pool.racks.get(0), 1).isEmpty() && !f.pool.be.stackAt(f.pool.racks.get(0), 2).isEmpty()
                            && f.reactor.core().getCoreLoading().loadedAssemblyCount() == 0,
                    "both assemblies went to the pool: the one saved on the grapple in cell 2, the one queued behind it in cell 3");
            h.assertTrue(FuelAssemblies.dataOf(f.pool.be.stackAt(f.pool.racks.get(0), 1)).burnupMwdPerTonne() == 30000.0
                    && FuelAssemblies.dataOf(f.pool.be.stackAt(f.pool.racks.get(0), 2)).burnupMwdPerTonne() == 31000.0, "each with its own exposure");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 3200)
    public static void aBridgeThatIsNotMovingHasNothingScheduledAndTicksOnlyWhileItMoves(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(7), 4);
        try {
            ready(h, f);
            f.reactor.loadAssembly(f.corePosition(0), RefuellingBridgeFixture.fresh(30000.0));
        } catch (Throwable t) {
            f.dispose();
            throw t;
        }
        int[] marks = new int[4];
        h.runAtTickTime(10, () -> {
            marks[0] = f.bridge.ticksRun();
            h.assertTrue(!f.level.getBlockTicks().hasScheduledTick(f.console, f.level.getBlockState(f.console).getBlock()), "nothing scheduled while idle");
            var type = BwrBlockEntities.REFUELLING_BRIDGE.get();
            h.assertTrue(((EntityBlock) f.level.getBlockState(f.console).getBlock()).getTicker(f.level, f.level.getBlockState(f.console), type) == null,
                    "the console has no block entity ticker");
        });
        h.runAtTickTime(130, () -> {
            h.assertTrue(f.bridge.ticksRun() == marks[0], "an idle bridge never ticked in 120 ticks: " + marks[0] + " -> " + f.bridge.ticksRun());
            h.assertTrue(f.bridge.queue(new Move(Loc.core(f.corePosition(0)), Loc.core(f.corePosition(1)))) == null, "queued a short move");
            marks[1] = f.bridge.ticksRun();
        });
        h.runAtTickTime(230, () -> {
            int ran = f.bridge.ticksRun() - marks[1];
            h.assertTrue(f.bridge.busy() && ran >= 95 && ran <= 101, "a moving bridge ticks once a tick: " + ran + " in 100");
        });
        h.runAtTickTime(3000, () -> {
            h.assertTrue(!f.bridge.busy() && f.reactor.core().getCoreLoading().isOccupied(f.corePosition(1)), "the move finished on the real scheduler: " + f.bridge.phase());
            marks[2] = f.bridge.ticksRun();
            h.assertTrue(!f.level.getBlockTicks().hasScheduledTick(f.console, f.level.getBlockState(f.console).getBlock()), "nothing scheduled once it is still");
        });
        h.runAtTickTime(3100, () -> {
            h.assertTrue(f.bridge.ticksRun() == marks[2], "and no ticks after: " + marks[2] + " -> " + f.bridge.ticksRun());
            f.dispose();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // The old instant map, and the control screen
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void theInstantMapMovesFuelOnlyWhenTheOptionIsOnAndOtherwisePointsToTheBridge(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(8), 4);
        var player = FakePlayerFactory.getMinecraft(f.level);
        boolean before = GameplayConfig.INSTANT_REFUELLING.get();
        try {
            h.assertTrue(GameplayConfig.SPEC.isLoaded(), "server gameplay config did not load");
            h.assertTrue(!GameplayConfig.INSTANT_REFUELLING.getDefault(), "the option defaults to off");
            var menu = new RefuellingMenu(1, player.getInventory(), f.reactor.getBlockPos());
            player.getInventory().clearContent();
            player.getInventory().setItem(0, RefuellingBridgeFixture.fresh(1000.0));
            player.getInventory().selected = 0;
            int slot = f.corePosition(0);

            GameplayConfig.INSTANT_REFUELLING.set(false);
            menu.handleCommand(player, RefuellingMenu.CMD_LOAD, 0, 0);
            h.assertTrue(!f.reactor.core().getCoreLoading().isOccupied(slot) && !player.getInventory().getItem(0).isEmpty(), "off: the map loads nothing");
            menu.handleBatch(player, true, new int[]{0});
            h.assertTrue(!f.reactor.core().getCoreLoading().isOccupied(slot), "off: the batch loads nothing");
            String text = BwrText.english(RefuellingMenu.instantOffMessage());
            h.assertTrue(text.contains("refuelling bridge") && text.contains("rail") && text.contains("canal") && text.contains("instantRefuelling"),
                    "the map says how to use the bridge: " + text);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            var write = RefuellingMenu.class.getDeclaredMethod("writeSnapshot", FriendlyByteBuf.class);
            var read = RefuellingMenu.class.getDeclaredMethod("readSnapshot", FriendlyByteBuf.class);
            write.setAccessible(true);
            read.setAccessible(true);
            write.invoke(menu, buf);
            read.invoke(menu, buf);
            h.assertTrue(!menu.instant && buf.readableBytes() == 0, "the screen is told the option is off");
            buf.release();

            GameplayConfig.INSTANT_REFUELLING.set(true);
            menu.handleCommand(player, RefuellingMenu.CMD_LOAD, 0, 0);
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot) && player.getInventory().getItem(0).isEmpty(), "on: the map loads at once");
            menu.handleCommand(player, RefuellingMenu.CMD_UNLOAD, 0, 0);
            h.assertTrue(!f.reactor.core().getCoreLoading().isOccupied(slot), "on: and unloads at once");
            GameplayConfig.INSTANT_REFUELLING.set(false);
        } finally {
            GameplayConfig.INSTANT_REFUELLING.set(before);
            player.getInventory().clearContent();
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 200)
    public static void theMapOpenedFromTheConsoleQueuesAndReportsThroughItsOwnCommands(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(9), 4);
        var player = FakePlayerFactory.getMinecraft(f.level);
        try {
            ready(h, f);
            f.fillPool(7.0);
            f.reactor.loadAssembly(f.corePosition(0), RefuellingBridgeFixture.fresh(30000.0));
            var menu = new RefuellingMenu(1, player.getInventory(), f.reactor.getBlockPos(), f.console);
            h.assertTrue(menu.bridgeMode && menu.slots.isEmpty(), "bridge mode carries no inventory slots");
            menu.handleCommand(player, RefuellingMenu.CMD_BRIDGE_TO_POOL, 0, 2);
            h.assertTrue(f.bridge.busy() && f.bridge.currentMove() != null && f.bridge.currentMove().to().cell() == 2
                    && f.bridge.currentMove().to().rack().equals(f.pool.racks.get(0)), "the command queued core 0 to rack 1 cell 3");
            // the snapshot a screen would read
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            var write = RefuellingMenu.class.getDeclaredMethod("writeSnapshot", FriendlyByteBuf.class);
            var read = RefuellingMenu.class.getDeclaredMethod("readSnapshot", FriendlyByteBuf.class);
            write.setAccessible(true);
            read.setAccessible(true);
            write.invoke(menu, buf);
            read.invoke(menu, buf);
            h.assertTrue(buf.readableBytes() == 0, "the snapshot is read to the end");
            buf.release();
            h.assertTrue(menu.bridgePhase.equals("TRAVEL") && menu.bridgeBusy && menu.bridgeChute.equals("OPEN") && menu.bridgePool
                    && menu.bridgeRacks.size() == 4 && menu.bridgeCavityWaterM > 1.0 && Math.abs(menu.bridgePoolCoverM - 3.0) < 1e-6,
                    "snapshot: " + menu.bridgePhase + " " + menu.bridgeChute + " " + menu.bridgeRacks.size() + " " + menu.bridgeCavityWaterM + " " + menu.bridgePoolCoverM);
            // overriding and aborting
            menu.handleCommand(player, RefuellingMenu.CMD_BRIDGE_OVERRIDE, 1, 0);
            h.assertTrue(f.bridge.override(), "override set from the screen");
            menu.handleCommand(player, RefuellingMenu.CMD_BRIDGE_OVERRIDE, 0, 0);
            menu.handleCommand(player, RefuellingMenu.CMD_BRIDGE_ABORT, 0, 0);
            h.assertTrue(!f.bridge.busy() && f.bridge.queued() == 0 && !f.bridge.override(), "aborted from the screen");
            // a move that cannot be done answers with the reason and queues nothing
            menu.handleCommand(player, RefuellingMenu.CMD_BRIDGE_TO_CORE, 3, 1);
            h.assertTrue(!f.bridge.busy() && f.bridge.queued() == 0, "a rack cell with nothing in it cannot be a source");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    /** The bridge as a computer sees it, when CC:Tweaked is present. */
    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 300)
    public static void theComputerFaceQueuesMovesAndReadsTheState(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("REFUELLING BRIDGE CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        RefuellingBridgeCcChecks.run(h, at(10));
        System.out.println("REFUELLING BRIDGE CC: bwr_refuelling_bridge getStatus, getRacks, queueCoreToPool, setGate and setOverride agree with the block entity");
        h.succeed();
    }
}
