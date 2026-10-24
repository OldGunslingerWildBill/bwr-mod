package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.StudTensionerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity.Loc;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity.Move;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The reactor building crane, the stud tensioner and the head stand through their real blocks (BUILDING-CRANE.md): detension the closure
 * studs, lift the head onto its stand, the dryer and then the separators onto equipment pads, move fuel with the refuelling bridge, and
 * put everything back in the other order and retension; the head that will not lift while a stud is tensioned; the bridge that refuses the
 * lifts; the instant option on and off; saves in the middle of a lift and of a pass, and an old world with the head off; and nothing
 * ticking while idle. The crane and the tensioner are stepped a tick at a time the way their scheduled block ticks would
 * ({@link BuildingCraneBlockEntity#scheduledTick}, {@link StudTensionerBlockEntity#scheduledTick}), so minutes take no time; one test lets
 * the real scheduler drive them. Own namespace ({@code bwrcrane}); {@code -PbwrOnlyCrane} runs just these.
 */
@GameTestHolder("bwrcrane")
@PrefixGameTestTemplate(false)
public final class BuildingCraneTests {

    private BuildingCraneTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(78000 + 300 * i, 190, 78000);
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    /** Runs the crane until it is still and checks that it finished instead of halting. */
    private static int run(GameTestHelper h, CraneFixture f) {
        int n = f.runCrane(60_000);
        h.assertTrue(!f.crane.busy(), "the crane never finished: " + f.crane.phase() + " " + english(f.crane.haltMessage()));
        h.assertTrue(f.crane.phase() == Phase.IDLE, "the crane halted: " + english(f.crane.haltMessage()));
        return n;
    }

    private static void detensionAll(GameTestHelper h, CraneFixture f) {
        h.assertTrue(f.tensioner.start(true) == null, "the tensioner starts");
        f.runTensioner(20_000, true);
        h.assertTrue(!f.tensioner.running() && f.reactor().studsOff() == f.reactor().studTotal(), "every stud is off: " + f.reactor().studsOff());
    }

    private static void retensionAll(GameTestHelper h, CraneFixture f) {
        h.assertTrue(f.tensioner.start(false) == null, "the tensioner starts the other way: " + english(f.tensioner.message()));
        f.runTensioner(30_000, true);
        h.assertTrue(!f.tensioner.running() && f.reactor().studsOff() == 0, "every stud is at load: " + f.reactor().studsOff());
    }

    // ------------------------------------------------------------------------------------------------------------
    // The whole sequence
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void theFullSequenceDetensionsLiftsTheHeadAndTheInternalsMovesFuelAndPutsEverythingBack(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(0));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            h.assertTrue(r.studTotal() == 74, "the reference vessel has 74 studs: " + r.studTotal());
            h.assertTrue(r.vesselState() == VesselState.SHUTDOWN && r.studsOff() == 0 && r.headSealed(), "head on, every stud at load, sealed");

            // 1. nothing lifts while the studs are tensioned
            Component why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("74 of the 74 closure studs are still tensioned"), "refused: " + english(why));

            // 2. the tensioner takes every nut off: 74 studs of 4 s each, the studs stay in the flange
            long before = f.tensioner.energyFe();
            h.assertTrue(f.tensioner.start(true) == null, "started");
            h.assertTrue(f.tensioner.running(), "running");
            int ticks = f.runTensioner(20_000, true);
            h.assertTrue(ticks == 74 * CraneMotion.DETENSION_TICKS_PER_STUD, "296 s for 74 studs: " + ticks + " ticks");
            long used = before + f.feDelivered - f.tensioner.energyFe();
            h.assertTrue(used == 74L * CraneMotion.DETENSION_FE_PER_STUD, "222,000 FE: " + used);
            h.assertTrue(r.studsOff() == 74 && !r.headSealed() && r.vesselState() == VesselState.SHUTDOWN, "every nut off, the head still on, the vessel cannot hold pressure");
            h.assertTrue(r.headLiftBlocked() == null, "the head is liftable: " + english(r.headLiftBlocked()));

            // 3. the crane lifts the head to the stand
            h.assertTrue(f.crane.queueHead(true) == null, "queued");
            int toTransit = f.runCraneUntil(Phase.TRANSIT, 60_000);
            h.assertTrue(f.crane.carriedHead() && r.vesselState() == VesselState.REFUELING && f.crane.getBlockPos().equals(r.headLocation()),
                    "in transit the hook holds the head and the reactor knows the crane has it");
            int headOut = toTransit + f.runCrane(60_000);
            h.assertTrue(f.crane.phase() == Phase.IDLE, "the crane halted: " + english(f.crane.haltMessage()));
            h.assertTrue(r.vesselState() == VesselState.REFUELING && f.stand.equals(r.headLocation()) && f.standBe.holdsHead() && !f.crane.holding(),
                    "the head is on its stand and the hook is empty");
            for (Phase p : new Phase[]{Phase.TRAVEL, Phase.LOWER_EMPTY, Phase.LATCH, Phase.RAISE_LOADED, Phase.TRANSIT, Phase.LOWER_LOADED, Phase.RELEASE, Phase.RAISE_EMPTY}) {
                h.assertTrue(f.phases.contains(p), "the move went through " + p);
            }

            // 4. the dryer first, then the separators, onto the pads
            why = f.crane.queueInternal(VesselInternal.SEPARATOR, true);
            h.assertTrue(why != null && english(why).contains("dryer"), "the dryer comes out first: " + english(why));
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "dryer queued");
            int dryerOut = run(h, f);
            h.assertTrue(!r.internalInstalled(VesselInternal.DRYER) && f.pads.contains(r.internalLocation(VesselInternal.DRYER)), "the dryer is on a pad");
            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, true) == null, "separators queued");
            int sepsOut = run(h, f);
            h.assertTrue(!r.internalInstalled(VesselInternal.SEPARATOR) && f.pads.contains(r.internalLocation(VesselInternal.SEPARATOR))
                    && !r.internalLocation(VesselInternal.SEPARATOR).equals(r.internalLocation(VesselInternal.DRYER)), "the separators are on the other pad");

            // 5. fuel moves by the refuelling bridge, which is now allowed to
            f.floodCavity(3.5);
            f.base.fillPool(7.0);
            int slot = f.base.corePosition(0);
            h.assertTrue(r.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0)), "fuel loaded");
            var bridge = f.base.bridge;
            h.assertTrue(bridge.queue(new RefuellingBridgeBlockEntity.Move(RefuellingBridgeBlockEntity.Loc.core(slot),
                    RefuellingBridgeBlockEntity.Loc.rack(f.base.pool.racks.get(0), 0))) == null, "the bridge takes the fuel move");
            f.base.runUntilStill(60_000);
            h.assertTrue(bridge.phase() == Phase.IDLE, "the bridge finished: " + english(bridge.haltMessage()));
            h.assertTrue(!r.core().getCoreLoading().isOccupied(slot) && !f.base.pool.be.stackAt(f.base.pool.racks.get(0), 0).isEmpty(), "the assembly is in the pool");
            h.assertTrue(bridge.queue(new RefuellingBridgeBlockEntity.Move(RefuellingBridgeBlockEntity.Loc.rack(f.base.pool.racks.get(0), 0),
                    RefuellingBridgeBlockEntity.Loc.core(slot))) == null, "and back");
            f.base.runUntilStill(60_000);
            h.assertTrue(r.core().getCoreLoading().isOccupied(slot) && f.base.pool.be.stackAt(f.base.pool.racks.get(0), 0).isEmpty(), "the assembly is in the core again");
            // the fuel the bridge moved is the bridge's: it never lifted the dryer or the separators
            h.assertTrue(!f.base.bridge.holding(), "the bridge's hoist is empty");

            // 6. reverse: the head cannot go on with an assembly out; the separators first, then the dryer
            r.core().getPressureVessel().setCollapsedLevelIn(60.0);
            why = f.crane.queueHead(false);
            h.assertTrue(why != null && english(why).contains("out of the vessel"), "the head waits for both: " + english(why));
            why = f.crane.queueInternal(VesselInternal.DRYER, false);
            h.assertTrue(why != null && english(why).contains("separator"), "the separators go back first: " + english(why));
            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, false) == null, "separators back");
            run(h, f);
            h.assertTrue(r.internalInstalled(VesselInternal.SEPARATOR), "separators in");
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, false) == null, "dryer back");
            run(h, f);
            h.assertTrue(r.internalsOutMask() == 0, "both in");

            // 7. the head back on the flange, then the studs to load
            h.assertTrue(f.crane.queueHead(false) == null, "head back queued");
            int headIn = run(h, f);
            h.assertTrue(r.vesselState() == VesselState.SHUTDOWN && r.headLocation() == null && !f.standBe.holdsHead(), "the head is on the vessel again");
            h.assertTrue(r.studsOff() == 74 && !r.headSealed(), "on, but every nut is off: it cannot hold pressure yet");
            before = f.tensioner.energyFe();
            long delivered = f.feDelivered;
            h.assertTrue(f.tensioner.start(false) == null, "the tensioner goes back round");
            ticks = f.runTensioner(30_000, true);
            h.assertTrue(ticks == 74 * CraneMotion.RETENSION_TICKS_PER_STUD, "444 s: " + ticks);
            used = before + (f.feDelivered - delivered) - f.tensioner.energyFe();
            h.assertTrue(used == 74L * CraneMotion.RETENSION_FE_PER_STUD, "444,000 FE: " + used);
            h.assertTrue(r.studsOff() == 0 && r.headSealed() && r.vesselState() == VesselState.SHUTDOWN, "every stud at load: sealed");
            System.out.printf("BUILDING CRANE TIMES: head to the stand %.0f s, dryer out %.0f s, separators out %.0f s, head back on %.0f s; 74 studs: %.0f s and %,d FE to detension, %.0f s and %,d FE to retension%n",
                    headOut / 20.0, dryerOut / 20.0, sepsOut / 20.0, headIn / 20.0, 74 * CraneMotion.DETENSION_TICKS_PER_STUD / 20.0, 74 * CraneMotion.DETENSION_FE_PER_STUD,
                    74 * CraneMotion.RETENSION_TICKS_PER_STUD / 20.0, 74 * CraneMotion.RETENSION_FE_PER_STUD);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The head cannot lift while a stud is tensioned; the tensioner's own rules
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void theHeadCannotLiftWhileAnyStudIsTensionedAndTheStudsWaitForLowPressure(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(1));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            r.setStudsOff(73);
            Component why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("1 of the 74 closure studs are still tensioned"), "one stud left: " + english(why));
            h.assertTrue(r.headLiftBlocked() != null && f.crane.queue(new Move(Loc.SEAT, Loc.stand(f.stand))) != null, "and the move itself is refused too");
            r.setStudsOff(74);
            h.assertTrue(f.crane.queueHead(true) == null, "every stud off: it lifts");
            f.crane.abort();
            r.setStudsOff(0);

            // the studs are backed off only at 25 psig or less
            r.core().getPressureVessel().restorePressurePsig(300);
            why = f.tensioner.start(true);
            h.assertTrue(why != null && english(why).contains("psig"), "no detensioning under pressure: " + english(why));
            h.assertTrue(!f.tensioner.running() && r.studsOff() == 0, "nothing moved");
            r.core().getPressureVessel().restorePressurePsig(0);
            h.assertTrue(f.tensioner.start(true) == null, "depressurised, it starts");
            h.assertTrue(f.tensioner.start(true) != null, "and cannot be started twice");
            f.tensioner.stop();
            h.assertTrue(!f.tensioner.running(), "stopped");

            // a tensioner that stands too far from the flange finds no reactor
            f.level.setBlock(f.o.offset(-20, 8, 2), BwrBlocks.STUD_TENSIONER.get().defaultBlockState(), 2);
            var far = (StudTensionerBlockEntity) f.level.getBlockEntity(f.o.offset(-20, 8, 2));
            why = far.start(true);
            h.assertTrue(why != null && english(why).contains("No formed reactor flange"), "out of reach: " + english(why));
            f.level.setBlock(f.o.offset(-20, 8, 2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);

            // the head on and tight cannot be re-tensioned, and with the head off the studs are bare
            h.assertTrue(f.tensioner.start(false) != null, "all studs are already at load");
            r.setVesselState(VesselState.REFUELING);
            h.assertTrue(f.tensioner.start(true) != null && f.tensioner.start(false) != null, "head off: neither pass makes sense");
            h.assertTrue(r.studsOff() == r.studTotal(), "with the head off every stud is bare");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void theTensionerTakesRealTimeAndEnergyAndStopsWhenThePowerRunsOut(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(2));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            // no power: it starts, and stops at the first stud, saying why
            h.assertTrue(f.tensioner.start(true) == null, "started");
            f.runTensioner(10, false);
            h.assertTrue(!f.tensioner.running() && english(f.tensioner.message()).contains("out of power"), "no power: " + english(f.tensioner.message()));
            h.assertTrue(r.studsOff() == 0, "no stud was done");

            // exactly one stud's energy: one stud done after exactly 4 s, then it stops for power again
            f.tensioner.fill(CraneMotion.DETENSION_FE_PER_STUD);
            h.assertTrue(f.tensioner.start(true) == null, "started again");
            int n = f.runTensioner(1000, false);
            h.assertTrue(n == CraneMotion.DETENSION_TICKS_PER_STUD + 1, "a stud takes 80 ticks (and the next is refused for power on the 81st): " + n);
            h.assertTrue(r.studsOff() == 1 && f.tensioner.energyFe() == 0, "one stud off for 3,000 FE: " + r.studsOff() + " " + f.tensioner.energyFe());
            h.assertTrue(english(f.tensioner.message()).contains("out of power"), "and it says it ran out");

            // stopped halfway through a stud: that stud starts again, the done ones stay done
            f.tensioner.fill(StudTensionerBlockEntity.FE_CAPACITY);
            h.assertTrue(f.tensioner.start(true) == null, "started");
            for (int i = 0; i < 40; i++) {
                f.tensioner.scheduledTick(f.level);
            }
            f.tensioner.stop();
            h.assertTrue(r.studsOff() == 1 && !f.tensioner.running(), "stopped mid-stud: still 1");

            // the pack takes FE through its capability, up to its buffer
            var cap = f.level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, f.tensionerPos, null);
            h.assertTrue(cap != null && cap.canReceive() && !cap.canExtract(), "the energy capability is there and takes only");
            f.tensioner.fill(0);
            h.assertTrue(cap.receiveEnergy(1_000_000, false) == StudTensionerBlockEntity.FE_RECEIVE_PER_TICK, "at most one tick's share at a time");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The bridge moves fuel only
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void theBridgeRefusesTheInternalLiftsAndPointsToTheCrane(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(3));
        boolean before = GameplayConfig.BRIDGE_LIFTS_INTERNALS.get();
        try {
            ReactorControllerBlockEntity r = f.reactor();
            r.setStudsOff(74);
            h.assertTrue(f.crane.queueHead(true) == null, "the crane lifts the head");
            run(h, f);
            var bridge = f.base.bridge;
            bridge.refreshSite();
            GameplayConfig.BRIDGE_LIFTS_INTERNALS.set(false);
            Component why = bridge.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("crane") && english(why).contains("fuel only"), "the bridge refuses and points to the crane: " + english(why));
            why = bridge.queue(new RefuellingBridgeBlockEntity.Move(RefuellingBridgeBlockEntity.Loc.internal(VesselInternal.DRYER),
                    RefuellingBridgeBlockEntity.Loc.pad(f.pads.get(0), VesselInternal.DRYER)));
            h.assertTrue(why != null && english(why).contains("crane"), "and the raw move too: " + english(why));
            h.assertTrue(r.internalInstalled(VesselInternal.DRYER) && !bridge.busy(), "nothing moved");
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "the crane takes it");
            f.crane.abort();
            // the legacy option brings the bridge's lift back
            GameplayConfig.BRIDGE_LIFTS_INTERNALS.set(true);
            h.assertTrue(GameplayConfig.BRIDGE_LIFTS_INTERNALS.getDefault(), "the legacy option defaults to on, until the owner decides");
        } finally {
            GameplayConfig.BRIDGE_LIFTS_INTERNALS.set(before);
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // instantHeadRemoval
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void theInstantOptionLiftsTheHeadAtOnceAndOffTheButtonExplainsTheProcedure(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(4));
        boolean before = GameplayConfig.INSTANT_HEAD_REMOVAL.get();
        try {
            h.assertTrue(GameplayConfig.SPEC.isLoaded(), "server gameplay config did not load");
            h.assertTrue(!GameplayConfig.INSTANT_HEAD_REMOVAL.getDefault(), "the option defaults to off");
            ReactorControllerBlockEntity r = f.reactor();
            var player = FakePlayerFactory.getMinecraft(f.level);
            var menu = new ReactorPanelMenu(1, player.getInventory(), r.getBlockPos());

            GameplayConfig.INSTANT_HEAD_REMOVAL.set(false);
            menu.handleCommand(player, ReactorPanelMenu.CMD_SET_VESSEL_STATE, VesselState.REFUELING.ordinal(), 0);
            h.assertTrue(r.vesselState() == VesselState.SHUTDOWN && r.headSealed(), "off: the button does not lift the head");
            String text = english(ReactorPanelMenu.headProcedure(false));
            h.assertTrue(text.contains("Stud Tensioner") && text.contains("crane") && text.contains("Head Stand") && text.contains("instantHeadRemoval"),
                    "and says how it is done: " + text);
            h.assertTrue(english(ReactorPanelMenu.headProcedure(true)).contains("crane"), "the same for seating it");

            GameplayConfig.INSTANT_HEAD_REMOVAL.set(true);
            menu.handleCommand(player, ReactorPanelMenu.CMD_SET_VESSEL_STATE, VesselState.REFUELING.ordinal(), 0);
            h.assertTrue(r.vesselState() == VesselState.REFUELING && r.headLocation() == null && r.studsOff() == r.studTotal(),
                    "on: the old button lifts it at once, as before");
            menu.handleCommand(player, ReactorPanelMenu.CMD_SET_VESSEL_STATE, VesselState.SHUTDOWN.ordinal(), 0);
            h.assertTrue(r.vesselState() == VesselState.SHUTDOWN && r.headSealed() && r.studsOff() == 0, "and puts it back, tight, at once");

            // off again, with the head off by the button: it stays off, and only the crane (or the option) seats it
            GameplayConfig.INSTANT_HEAD_REMOVAL.set(false);
            r.setVesselState(VesselState.REFUELING);
            menu.handleCommand(player, ReactorPanelMenu.CMD_SET_VESSEL_STATE, VesselState.SHUTDOWN.ordinal(), 0);
            h.assertTrue(r.vesselState() == VesselState.REFUELING, "off: the button does not seat it either");
        } finally {
            GameplayConfig.INSTANT_HEAD_REMOVAL.set(before);
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saves
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void aLiftSavedMidwayWithTheHeadOnTheHookAndLoadedAgainFinishesTheJob(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(5));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            r.setStudsOff(74);
            h.assertTrue(f.crane.queueHead(true) == null, "queued");
            f.runCraneUntil(Phase.TRANSIT, 60_000);
            for (int i = 0; i < 100; i++) {
                f.crane.scheduledTick(f.level);
            }
            h.assertTrue(f.crane.phase() == Phase.TRANSIT && f.crane.carriedHead(), "in transit with the head: " + f.crane.phase());
            double progress = f.crane.progress();
            var tipBefore = f.crane.tip();
            CompoundTag saved = f.crane.saveWithoutMetadata(f.level.registryAccess());
            CompoundTag reactorSaved = r.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(reactorSaved.getLong("HeadAt") == f.console.asLong(), "the reactor saved where the head is: the crane");

            // the world is closed and opened again: new block entities read the saved tags
            f.level.removeBlockEntity(f.console);
            var again = new BuildingCraneBlockEntity(f.console, f.level.getBlockState(f.console));
            again.setLevel(f.level);
            again.loadWithComponents(saved, f.level.registryAccess());
            f.level.setBlockEntity(again);
            f.crane = again;
            h.assertTrue(again.phase() == Phase.TRANSIT && again.carriedHead() && again.carriedProfile() != null, "same phase and the head still on the hook: " + again.phase());
            h.assertTrue(Math.abs(again.progress() - progress) < 1e-9, "same progress " + again.progress() + " against " + progress);
            h.assertTrue(Math.abs(again.tip().x() - tipBefore.x()) < 1e-9 && Math.abs(again.tip().y() - tipBefore.y()) < 1e-9, "the hook is where it was");
            run(h, f);
            h.assertTrue(f.standBe.holdsHead() && r.vesselState() == VesselState.REFUELING && f.stand.equals(r.headLocation()), "the saved lift finished: the head is on its stand");

            // the stand holds the head across a save too, and the reactor remembers the stand
            CompoundTag standSaved = f.standBe.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(standSaved.getBoolean("Holding") && standSaved.getLong("Reactor") == r.getBlockPos().asLong(), "the stand saved the head");
            CompoundTag rSaved = r.saveWithFullMetadata(f.level.registryAccess());
            var copy = (ReactorControllerBlockEntity) BlockEntity.loadStatic(r.getBlockPos(), r.getBlockState(), rSaved, f.level.registryAccess());
            h.assertTrue(copy != null && copy.vesselState() == VesselState.REFUELING && f.stand.equals(copy.headLocation()), "the reactor loads with the head on its stand");

            // a pass of the tensioner saved halfway resumes at the same stud
            r.setVesselState(VesselState.SHUTDOWN);
            h.assertTrue(f.tensioner.start(true) == null, "tensioner started");
            f.runTensioner(30 * CraneMotion.DETENSION_TICKS_PER_STUD + 17, true);
            int done = r.studsOff();
            h.assertTrue(done == 30 && f.tensioner.running(), "30 studs done and still going: " + done);
            CompoundTag tensionerSaved = f.tensioner.saveWithoutMetadata(f.level.registryAccess());
            f.level.removeBlockEntity(f.tensionerPos);
            var tAgain = new StudTensionerBlockEntity(f.tensionerPos, f.level.getBlockState(f.tensionerPos));
            tAgain.setLevel(f.level);
            tAgain.loadWithComponents(tensionerSaved, f.level.registryAccess());
            f.level.setBlockEntity(tAgain);
            f.tensioner = tAgain;
            h.assertTrue(tAgain.running() && tAgain.mode() == StudTensionerBlockEntity.Mode.LOOSEN, "the pass is back");
            f.runTensioner(30_000, true);
            h.assertTrue(r.studsOff() == r.studTotal() && !tAgain.running(), "the saved pass finished the same job");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void anOldWorldWithTheHeadOffStaysOffAndTheCraneCanSeatItAndNewKeysAreWrittenOnlyWhenNotDefault(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(6));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            var regs = f.level.registryAccess();
            // head on, tight, nothing out: not one new key anywhere
            CompoundTag tag = r.saveWithFullMetadata(regs);
            h.assertTrue(!tag.contains("StudsOff") && !tag.contains("HeadAt") && !tag.contains("InternalsOut"), "an ordinary reactor writes no new key");
            h.assertTrue(!f.standBe.saveWithFullMetadata(regs).contains("Holding"), "an empty stand writes nothing");
            h.assertTrue(!f.tensioner.saveWithFullMetadata(regs).contains("Mode") && !f.tensioner.saveWithFullMetadata(regs).contains("Energy"), "an idle, empty tensioner writes nothing");
            CompoundTag craneTag = f.crane.saveWithFullMetadata(regs);
            h.assertTrue(!craneTag.contains("Phase") && !craneTag.contains("CarriedHead") && !craneTag.contains("CarriedInternal") && !craneTag.contains("Move")
                    && !craneTag.contains("Queue"), "an idle crane writes no state of a lift");
            // an old world with the head on: tight
            var copy = (ReactorControllerBlockEntity) BlockEntity.loadStatic(r.getBlockPos(), r.getBlockState(), tag, regs);
            h.assertTrue(copy != null && copy.vesselState() == VesselState.SHUTDOWN && copy.headSealed(), "an old world with the head on loads with every stud at load");

            // an old world with the head off: the head the instant way, laid down somewhere (the old state, no new key)
            r.setVesselState(VesselState.REFUELING);
            tag = r.saveWithFullMetadata(regs);
            h.assertTrue(tag.getString("VesselState").equals("refueling") && !tag.contains("StudsOff") && !tag.contains("HeadAt"), "head off, laid down: the saved state is the old one");
            var restored = (ReactorControllerBlockEntity) BlockEntity.loadStatic(r.getBlockPos(), r.getBlockState(), tag, regs);
            f.level.setBlockEntity(restored);
            SpargerRegressionTests.validate(restored);
            f.base.reactor = restored;
            h.assertTrue(restored.isFormed() && restored.vesselState() == VesselState.REFUELING && restored.headLocation() == null,
                    "it stays off and treats the head as laid down: " + restored.vesselState());
            h.assertTrue(restored.studsOff() == restored.studTotal() && restored.studsTensioned() == 0, "with the studs bare");
            h.assertTrue(english(restored.headSummary()).contains("laid down"), "the INFO tab says so: " + english(restored.headSummary()));

            // the crane reinstalls it from where it lies, with no head stand block needed
            f.crane.refreshSite();
            h.assertTrue(f.crane.queueHead(false) == null, "queued from the laid-down head: " + english(f.crane.queueHead(false)));
            run(h, f);
            h.assertTrue(restored.vesselState() == VesselState.SHUTDOWN && restored.studsOff() == restored.studTotal() && !restored.headSealed(),
                    "the head is on, every nut off");
            tag = restored.saveWithFullMetadata(regs);
            h.assertTrue(tag.getInt("StudsOff") == restored.studTotal(), "and the reactor now writes the studs count: " + tag.getInt("StudsOff"));
            retensionAll(h, f);
            h.assertTrue(restored.headSealed() && !restored.saveWithFullMetadata(regs).contains("StudsOff"), "tight again: the key is gone");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The stand, the rails, the console
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void aHeadNeedsAFreeStandBetweenTheRailsAndClearOfTheVessel(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(7));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            r.setStudsOff(74);
            // with the stand taken away there is nowhere to put the head
            f.level.setBlock(f.stand, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            Component why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("No free head stand"), "no stand: " + english(why));
            // a stand on top of the vessel's footprint is refused with the reason
            var near = f.o.offset(2, 8, 6);
            f.level.setBlock(near, BwrBlocks.HEAD_STAND.get().defaultBlockState(), 2);
            why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("hang over the edge"), "a stand too close to the rail: " + english(why));
            f.level.setBlock(near, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            var onVessel = f.o.offset(4, 8, 4);
            f.level.setBlock(onVessel, BwrBlocks.HEAD_STAND.get().defaultBlockState(), 2);
            why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("too close to the vessel"), "a stand on the well: " + english(why));
            f.level.setBlock(onVessel, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            // the stand back: it lifts, and a second head would find the stand taken
            f.level.setBlock(f.stand, BwrBlocks.HEAD_STAND.get().defaultBlockState(), 2);
            f.standBe = (dev.bwr.mod.refuelling.HeadStandBlockEntity) f.level.getBlockEntity(f.stand);
            h.assertTrue(f.crane.queueHead(true) == null, "a stand again: it lifts");
            run(h, f);
            h.assertTrue(f.standBe.holdsHead(), "the stand holds the head");
            var player = FakePlayerFactory.getMinecraft(f.level);
            h.assertTrue(f.level.getBlockState(f.stand).getDestroyProgress(player, f.level, f.stand) == 0.0F, "a stand holding a head is not broken by hand");

            // a creative player removing the stand under the head lays the head down instead of losing it
            f.level.setBlock(f.stand, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(r.vesselState() == VesselState.REFUELING && r.headLocation() == null, "the head is laid down: still off, no location");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void lowRunwayRailsRefuseTheHeadLiftWithTheHeightToBuildTo(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(8), 9);
        try {
            f.reactor().setStudsOff(74);
            Component why = f.crane.queueHead(true);
            h.assertTrue(why != null && english(why).contains("too low") && english(why).contains("Raise the rails to y"), "too low: " + english(why));
            f.reactor().setVesselState(VesselState.REFUELING);
            why = f.crane.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("too low") && english(why).contains("dryer"), "and the dryer: " + english(why));
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 200)
    public static void breakingTheConsoleUnderAHangingHeadOrDryerLaysItDownOrSetsItBack(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(9));
        try {
            ReactorControllerBlockEntity r = f.reactor();
            r.setStudsOff(74);
            h.assertTrue(f.crane.queueHead(true) == null, "queued");
            f.runCraneUntil(Phase.TRANSIT, 60_000);
            h.assertTrue(f.crane.carriedHead() && f.console.equals(r.headLocation()), "the hook holds the head");
            f.crane.dropCarried(f.level);
            h.assertTrue(!f.crane.holding() && r.vesselState() == VesselState.REFUELING && r.headLocation() == null, "the head is laid down, not lost");
            f.crane.abort();
            // and with the head laid down the crane can put it back from where it lies
            f.crane.refreshSite();
            h.assertTrue(f.crane.queueHead(false) == null, "reinstall from the floor");
            run(h, f);
            h.assertTrue(r.vesselState() == VesselState.SHUTDOWN, "head on");

            // a hanging dryer goes back in its vessel
            r.setVesselState(VesselState.REFUELING);
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "dryer queued");
            f.runCraneUntil(Phase.TRANSIT, 60_000);
            h.assertTrue(f.crane.carriedInternal() == VesselInternal.DRYER && f.console.equals(r.internalLocation(VesselInternal.DRYER)), "the hook holds the dryer");
            f.crane.dropCarried(f.level);
            h.assertTrue(r.internalInstalled(VesselInternal.DRYER) && !f.crane.holding(), "back in the vessel");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Nothing idle ticks
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 600)
    public static void anIdleCraneAndAnIdleTensionerNeverTickAndAWorkingOneTicksOnceATick(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(10));
        int[] marks = new int[4];
        var crane = f.crane;
        var tensioner = f.tensioner;
        try {
            f.reactor().setStudsOff(74);
            marks[0] = crane.ticksRun();
            marks[1] = tensioner.ticksRun();
            h.assertTrue(!f.level.getBlockTicks().hasScheduledTick(f.tensionerPos, f.level.getBlockState(f.tensionerPos).getBlock()),
                    "an idle tensioner has nothing scheduled");
            h.assertTrue(!crane.busy(), "an idle crane is not busy");
        } catch (Throwable t) {
            f.dispose();
            throw t;
        }
        h.runAtTickTime(130, () -> {
            h.assertTrue(crane.ticksRun() <= marks[0] + 1 && tensioner.ticksRun() == marks[1],
                    "idle for 130 ticks: the crane took at most its one start-up tick, the tensioner none: " + (crane.ticksRun() - marks[0]) + " " + (tensioner.ticksRun() - marks[1]));
            marks[0] = crane.ticksRun();
            // real scheduler: the tensioner (the studs are all off, so tighten them) and the crane each tick once a tick
            h.assertTrue(f.crane.queueHead(true) == null, "the crane is told to lift the head");
            f.reactor().setStudsOff(f.reactor().studTotal());
            marks[2] = crane.ticksRun();
        });
        h.runAtTickTime(230, () -> {
            int ran = crane.ticksRun() - marks[2];
            h.assertTrue(crane.busy() && ran >= 95 && ran <= 101, "a moving crane ticks once a tick: " + ran + " in 100");
            crane.abort();
            marks[3] = crane.ticksRun();
        });
        h.runAtTickTime(300, () -> {
            h.assertTrue(crane.ticksRun() <= marks[3] + 1, "and once aborted it stops ticking: " + marks[3] + " -> " + crane.ticksRun());
            h.assertTrue(!f.level.getBlockTicks().hasScheduledTick(f.console, f.level.getBlockState(f.console).getBlock()), "nothing scheduled once it is still");
            f.dispose();
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 400)
    public static void aWorkingTensionerTicksOnceATickOnTheRealSchedulerAndStopsWhenDone(GameTestHelper h) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(11));
        int[] marks = new int[3];
        var tensioner = f.tensioner;
        try {
            tensioner.fill(StudTensionerBlockEntity.FE_CAPACITY);
            marks[0] = tensioner.ticksRun();
            h.assertTrue(tensioner.start(true) == null, "started");
        } catch (Throwable t) {
            f.dispose();
            throw t;
        }
        h.runAtTickTime(100, () -> {
            int ran = tensioner.ticksRun() - marks[0];
            h.assertTrue(tensioner.running() && ran >= 95 && ran <= 101, "a working tensioner ticks once a tick: " + ran + " in 100");
            tensioner.stop();
            marks[1] = tensioner.ticksRun();
        });
        h.runAtTickTime(200, () -> {
            h.assertTrue(tensioner.ticksRun() <= marks[1] + 1, "stopped: no more ticks: " + marks[1] + " -> " + tensioner.ticksRun());
            h.assertTrue(!f.level.getBlockTicks().hasScheduledTick(f.tensionerPos, f.level.getBlockState(f.tensionerPos).getBlock()), "nothing scheduled");
            f.dispose();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // The computer faces
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcrane", timeoutTicks = 300)
    public static void theComputerFacesRunTheWholeLiftAndReportIt(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("BUILDING CRANE CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        CraneFixture f = CraneFixture.build(h.getLevel(), at(12));
        try {
            BuildingCraneCcChecks.run(h, f);
        } finally {
            f.dispose();
        }
        System.out.println("BUILDING CRANE CC: the crane, the tensioner and getHead agree with the block entities");
        h.succeed();
    }
}
