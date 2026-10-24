package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.refuelling.EquipmentPadBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The removable steam dryer and separator assembly (RPV-REFERENCE.md): fuel waits for both to be out, the dryer comes out first
 * and goes back last, both come out only from a vessel flooded to its flange, the head waits for both to be back, a lifted
 * assembly is a radiation source that water on its pad shields, the saves stay as they were while both are in, and the drawn
 * vessel's profile and water keep their promises. On the refuelling bridge fixture (a 7 x 7 x 10 vessel), with two
 * equipment pads in a pit between the reactor and the pool.
 */
@GameTestHolder("bwrinternals")
@PrefixGameTestTemplate(false)
public final class VesselInternalsTests {
    private VesselInternalsTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(76000 + 300 * i, 190, 76000);
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    /** The fixture with both assemblies back in the vessel, the vessel flooded to its flange and two empty pads in a pit. */
    static RefuellingBridgeFixture build(GameTestHelper h, int i) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at(i), 2);
        f.reactor.internalIn(VesselInternal.DRYER);
        f.reactor.internalIn(VesselInternal.SEPARATOR);
        f.reactor.core().getPressureVessel().setCollapsedLevelIn(60.0);
        for (BlockPos pad : pads(f)) {
            for (int y = 1; y <= 8; y++) f.level.setBlock(pad.above(y), Blocks.AIR.defaultBlockState(), 2);
            f.level.setBlock(pad, BwrBlocks.EQUIPMENT_PAD.get().defaultBlockState(), 3);
        }
        // The fixture's rails stand one block over the small vessel's roof: too low to lift a dryer clear of its lip. A second pair four
        // blocks higher, over the same reactor and pads, with its own console: the bridge of these tests.
        for (int x = -1; x <= 18; x++) {
            for (int z : new int[]{-3, 7}) {
                f.level.setBlock(f.o.offset(x, 13, z), BwrBlocks.REFUELLING_RAIL.get().defaultBlockState()
                        .setValue(dev.bwr.mod.refuelling.RefuellingRailBlock.AXIS, net.minecraft.core.Direction.Axis.X), 2);
            }
        }
        BlockPos console = f.o.offset(-1, 13, -4);
        f.level.setBlock(console, BwrBlocks.REFUELLING_BRIDGE_CONSOLE.get().defaultBlockState(), 2);
        f.bridge = (dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity) f.level.getBlockEntity(console);
        f.bridge.refreshSite();
        return f;
    }

    /** The rails of the fixture, one block over the roof, are too low for the dryer: the lift is refused with the height to build to. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void railsTooLowToClearTheVesselRefuseTheLift(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 6);
        try {
            var low = (dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity) f.level.getBlockEntity(f.console);
            low.refreshSite();
            Component why = low.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("too low"), "the low rails refuse: " + english(why));
            h.assertTrue(f.bridge.queueInternal(VesselInternal.DRYER, true) == null, "the high rails lift it");
            f.bridge.abort();
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    private static void clearHigh(RefuellingBridgeFixture f) {
        f.bridge.removalDrops();
        for (int x = -2; x <= 20; x++) for (int z = -5; z <= 8; z++) for (int y = 12; y <= 14; y++)
            f.level.setBlock(f.o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
    }

    private static List<BlockPos> pads(RefuellingBridgeFixture f) {
        return List.of(f.o.offset(6, 1, 5), f.o.offset(6, 1, -1));
    }

    private static EquipmentPadBlockEntity pad(RefuellingBridgeFixture f, BlockPos p) {
        return (EquipmentPadBlockEntity) f.level.getBlockEntity(p);
    }

    private static int run(GameTestHelper h, RefuellingBridgeFixture f) {
        int n = f.runUntilStill(40_000);
        h.assertTrue(!f.bridge.busy(), "the bridge never finished: " + f.bridge.phase() + " " + english(f.bridge.haltMessage()));
        h.assertTrue(f.bridge.phase() == Phase.IDLE, "the bridge halted: " + english(f.bridge.haltMessage()));
        return n;
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void fuelWaitsForTheDryerAndTheSeparatorsToBeOut(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 0);
        try {
            // fuel goes through the cattle chute, which only the fixture's own (low) rails reach
            var high = f.bridge;
            f.bridge = (dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity) f.level.getBlockEntity(f.console);
            f.bridge.refreshSite();
            high.refreshSite();
            f.fillPool(7.0);
            h.assertTrue(f.reactor.loadAssembly(f.corePosition(0), RefuellingBridgeFixture.fresh(20000.0)), "load");
            Move move = new Move(Loc.core(f.corePosition(0)), Loc.rack(f.pool.racks.get(0), 0));
            Component why = f.bridge.queue(move);
            h.assertTrue(why != null && english(why).contains("steam dryer"), "a fuel move is refused while the dryer is in: " + english(why));
            h.assertTrue(f.reactor.refuellingBlockedMessage() != null, "the reactor says why");
            f.reactor.internalOut(VesselInternal.DRYER, pads(f).get(0));
            why = f.bridge.queue(move);
            h.assertTrue(why != null && english(why).contains("separator"), "and while the separator assembly is in: " + english(why));
            f.reactor.internalOut(VesselInternal.SEPARATOR, pads(f).get(1));
            why = f.bridge.queue(move);
            h.assertTrue(why == null, "with both out the move is queued: " + english(why));
            f.bridge.abort();
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void theDryerComesOutFirstAndGoesBackLastOnTheBridge(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 1);
        try {
            Component why = f.bridge.queueInternal(VesselInternal.SEPARATOR, true);
            h.assertTrue(why != null && english(why).contains("dryer"), "the separators cannot come out under the dryer: " + english(why));
            why = f.bridge.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why == null, "the dryer lift is queued: " + english(why));
            f.phases.clear();
            int ticks = run(h, f);
            BlockPos first = f.reactor.internalLocation(VesselInternal.DRYER);
            h.assertTrue(first != null && pad(f, first) != null && pad(f, first).stored() == VesselInternal.DRYER,
                    "the dryer stands on a pad and the reactor knows where: " + first);
            h.assertTrue(f.phases.equals(List.of(Phase.TRAVEL, Phase.LOWER_EMPTY, Phase.LATCH, Phase.RAISE_LOADED, Phase.TRANSIT, Phase.LOWER_LOADED,
                    Phase.RELEASE, Phase.RAISE_EMPTY, Phase.IDLE)), "the lift is the bridge's own sequence: " + f.phases);
            h.assertTrue(ticks > 20 * 15, "a lift takes real time, not a click: " + ticks + " ticks");
            h.assertTrue(f.reactor.setVesselStateMessage(VesselState.SHUTDOWN) != null, "the head cannot go on with the dryer out");

            h.assertTrue(f.bridge.queueInternal(VesselInternal.SEPARATOR, true) == null, "now the separators come out");
            run(h, f);
            BlockPos second = f.reactor.internalLocation(VesselInternal.SEPARATOR);
            h.assertTrue(second != null && !second.equals(first) && pad(f, second).stored() == VesselInternal.SEPARATOR, "on the other pad: " + second);
            h.assertTrue(f.reactor.refuellingBlockedMessage() == null, "fuel can move now");

            why = f.bridge.queueInternal(VesselInternal.DRYER, false);
            h.assertTrue(why != null && english(why).contains("separator"), "the dryer cannot go back before the separators: " + english(why));
            h.assertTrue(f.bridge.queueInternal(VesselInternal.SEPARATOR, false) == null, "the separators go back");
            run(h, f);
            h.assertTrue(f.reactor.internalInstalled(VesselInternal.SEPARATOR) && pad(f, second).stored() == null, "the separators are in, the pad empty");
            h.assertTrue(f.bridge.queueInternal(VesselInternal.DRYER, false) == null, "then the dryer");
            run(h, f);
            h.assertTrue(f.reactor.internalInstalled(VesselInternal.DRYER) && pad(f, first).stored() == null, "the dryer is in, the pad empty");
            h.assertTrue(f.reactor.setVesselStateMessage(VesselState.SHUTDOWN) == null, "and the head goes back on");
            System.out.println("VESSEL INTERNALS: dryer out " + ticks + " ticks (" + ticks / 20 + " s) on the 7 x 7 x 10 fixture");
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void theAssembliesComeOutOnlyFromAVesselFloodedToItsFlange(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 2);
        try {
            f.floodCavity(3.0);
            Component why = f.bridge.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("flood"), "a lift from a vessel not flooded to its flange is refused: " + english(why));
            f.reactor.core().getPressureVessel().setCollapsedLevelIn(60.0);
            h.assertTrue(f.bridge.queueInternal(VesselInternal.DRYER, true) == null, "flooded, it is queued");
            f.bridge.abort();
            f.reactor.setVesselState(VesselState.SHUTDOWN);
            h.assertTrue(f.reactor.internalLiftBlocked(VesselInternal.DRYER) != null, "and never with the head on");
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void aLiftedAssemblySpikesTheDoseAndWaterOnItsPadShieldsIt(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 3);
        try {
            BlockPos padPos = pads(f).get(0);
            Vec3 observer = Vec3.atCenterOf(f.o.offset(2, 10, -2));
            double before = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(f.bridge.queueInternal(VesselInternal.SEPARATOR, true) != null, "dryer first");
            f.reactor.internalOut(VesselInternal.DRYER, f.console); // the dryer is elsewhere: this test is about the separators
            h.assertTrue(f.bridge.queueInternal(VesselInternal.SEPARATOR, true) == null, "the separator lift is queued");
            f.runUntil(Phase.TRANSIT, 40_000);
            double lifted = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(lifted > 1.0 && lifted > before * 100 + 1.0, "lifted out of the water the dose spikes: " + before + " -> " + lifted + " mSv/h");
            run(h, f);
            BlockPos on = f.reactor.internalLocation(VesselInternal.SEPARATOR);
            h.assertTrue(on != null && pad(f, on).stored() == VesselInternal.SEPARATOR, "it stands on a pad");
            double dry = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(dry > 1.0, "on a dry pad it is still a source: " + dry + " mSv/h");
            for (int y = 1; y <= 8; y++) f.level.setBlock(on.above(y), Blocks.WATER.defaultBlockState(), 2);
            double wet = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(wet < dry * 1e-3, "under water on its pad it is shielded: " + dry + " -> " + wet + " mSv/h, cover " + pad(f, on).coverM() + " m");
            h.assertTrue(padPos != null, "pads");
            // leave the floor as found: no water, the separators back in the vessel
            for (int y = 1; y <= 8; y++) f.level.setBlock(on.above(y), Blocks.AIR.defaultBlockState(), 2);
            pad(f, on).take();
            f.reactor.internalIn(VesselInternal.SEPARATOR);
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void savesKeepNoNewKeysWhileBothAreInAndRememberWhereTheyAre(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 4);
        try {
            var tag = f.reactor.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(!tag.contains("InternalsOut"), "with both installed the reactor writes no new key");
            var padTag = pad(f, pads(f).get(0)).saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(!padTag.contains("Stored"), "an empty pad writes nothing");
            f.reactor.internalOut(VesselInternal.DRYER, pads(f).get(0));
            tag = f.reactor.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(tag.contains("InternalsOut") && tag.getCompound("InternalsOut").getLong("dryer") == pads(f).get(0).asLong(),
                    "out, it is saved with where it is: " + tag.get("InternalsOut"));
            var state = f.reactor.getBlockState();
            var copy = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    f.reactor.getBlockPos(), state, tag, f.level.registryAccess());
            h.assertTrue(copy != null && !copy.internalInstalled(VesselInternal.DRYER) && copy.internalInstalled(VesselInternal.SEPARATOR)
                    && pads(f).get(0).equals(copy.internalLocation(VesselInternal.DRYER)), "and loads the same");
            tag.remove("InternalsOut");
            copy = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    f.reactor.getBlockPos(), state, tag, f.level.registryAccess());
            h.assertTrue(copy != null && copy.internalsOutMask() == 0, "an old save (no key) loads with both installed");
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void breakingTheConsoleUnderAHangingDryerSetsItBack(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = build(h, 5);
        try {
            h.assertTrue(f.bridge.queueInternal(VesselInternal.DRYER, true) == null, "queued");
            f.runUntil(Phase.TRANSIT, 40_000);
            h.assertTrue(f.bridge.carriedInternal() == VesselInternal.DRYER && !f.reactor.internalInstalled(VesselInternal.DRYER), "the hoist holds the dryer");
            h.assertTrue(f.bridge.getBlockPos().equals(f.reactor.internalLocation(VesselInternal.DRYER)), "the reactor knows the bridge holds it");
            f.bridge.dropCarried(f.level);
            h.assertTrue(f.reactor.internalInstalled(VesselInternal.DRYER) && f.bridge.carriedInternal() == null, "it is back in its vessel, not lost");
        } finally {
            clearHigh(f);
            f.dispose();
        }
        h.succeed();
    }

    /** The drawn vessel's promises: the profile and the water line agree with the fuel at every size, and the water is true to scale to +30 in. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 40)
    public static void theDrawnVesselProfileAndWaterLineHoldAtEverySize(GameTestHelper h) {
        for (int w = 7; w <= 23; w += 2) {
            for (int height : new int[]{10, 16, 27, 40, 131}) {
                var e = new dev.bwr.mod.reactor.VesselAppearance.Envelope(BlockPos.ZERO, new BlockPos(w - 1, height - 1, w - 1), List.of());
                var p = VesselProfile.of(e, false);
                h.assertTrue(Math.abs(p.rim - VesselWaterGeometry.rim(e)) < 1e-9, "the lip is the cavity seal's lip " + w + "x" + height);
                h.assertTrue(p.knuckle < p.flangeFace - 2 * p.flangeHeight, "a cylinder between the heads " + w + "x" + height);
                h.assertTrue(p.waterRadiusFraction(1.72) * w >= .43 * w * .442 / .448 - 1e-9, "the annulus has a floor at the jet-pump seat " + w + "x" + height);
                h.assertTrue(p.separatorBottom() > p.coreTop && p.dryerBottom() < p.up(VesselProfile.IN_WATER) && p.up(VesselProfile.IN_WATER) < p.dryerLift(),
                        "the dryer skirt dips into the operating water above the core " + w + "x" + height);
                h.assertTrue(p.dryerLift() < height, "the dryer's eyes stay inside the head " + w + "x" + height);
                double level = 30.0, in = dev.bwr.core.PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN + level;
                h.assertTrue(Math.abs(VesselWaterGeometry.height(e, level) - p.up(in)) < 1e-9, "+30 in is drawn true to scale " + w + "x" + height);
                h.assertTrue(Math.abs(VesselWaterGeometry.height(e, 10.0) - p.up(in - 20)) < 1e-9, "+10 in is drawn true to scale " + w + "x" + height);
                h.assertTrue(Math.abs(VesselWaterGeometry.height(e, 60.0) - VesselWaterGeometry.rim(e)) < 1e-9, "+60 in reaches the lip " + w + "x" + height);
                for (double y = 2.5; y < VesselWaterGeometry.rim(e); y += .37)
                    h.assertTrue(Math.abs(VesselWaterGeometry.height(e, VesselWaterGeometry.levelAtHeight(e, y)) - y) < 1e-6, "water roundtrip " + w + "x" + height + " y " + y);
            }
        }
        h.succeed();
    }

    /** The same through the computer faces, when CC:Tweaked is present. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 300)
    public static void theComputerFacesLiftTheDryerAndReportIt(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("VESSEL INTERNALS CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        RefuellingBridgeFixture f = build(h, 7);
        try {
            VesselInternalsCcChecks.run(h, f);
        } finally {
            clearHigh(f);
            f.dispose();
        }
        System.out.println("VESSEL INTERNALS CC: getInternals, queueInternalOut and queueInternalIn agree with the block entities");
        h.succeed();
    }
}
