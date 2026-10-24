package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.refuelling.EquipmentPadBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
 * vessel's profile and water keep their promises. The lifts are the reactor building crane's (the refuelling bridge moves fuel
 * only and refuses them, and still sets down a load already on its hoist). On the crane fixture (a 7 x 7 x 10 vessel), with two
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

    /**
     * The crane fixture with the head OFF (lifted the instant way: the dryer's and the separators' tests start with the vessel open), both
     * assemblies back in the vessel, the vessel flooded to its flange and two empty pads in a pit. The crane's rails are 14 blocks over the
     * reactor's floor, high enough to lift a dryer clear of the 7 x 7 x 10 vessel's lip.
     */
    static CraneFixture build(GameTestHelper h, int i) throws Exception {
        CraneFixture f = CraneFixture.build(h.getLevel(), at(i));
        f.reactor().setVesselState(VesselState.REFUELING);
        f.reactor().core().getPressureVessel().setCollapsedLevelIn(60.0);
        return f;
    }

    private static List<BlockPos> pads(CraneFixture f) {
        return f.pads;
    }

    private static EquipmentPadBlockEntity pad(CraneFixture f, BlockPos p) {
        return (EquipmentPadBlockEntity) f.level.getBlockEntity(p);
    }

    private static int run(GameTestHelper h, CraneFixture f) {
        int n = f.runCrane(60_000);
        h.assertTrue(!f.crane.busy(), "the crane never finished: " + f.crane.phase() + " " + english(f.crane.haltMessage()));
        h.assertTrue(f.crane.phase() == Phase.IDLE, "the crane halted: " + english(f.crane.haltMessage()));
        return n;
    }

    /** The runway of the fixture's first crane is too low for the dryer: the lift is refused with the height to build to, and rails 14 blocks over the floor lift it. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void railsTooLowToClearTheVesselRefuseTheLift(GameTestHelper h) throws Exception {
        CraneFixture low = CraneFixture.build(h.getLevel(), at(6), 9);
        CraneFixture high = null;
        try {
            low.reactor().setVesselState(VesselState.REFUELING);
            Component why = low.crane.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("too low"), "the low rails refuse: " + english(why));
            high = build(h, 14);
            h.assertTrue(high.crane.queueInternal(VesselInternal.DRYER, true) == null, "the high rails lift it");
            high.crane.abort();
        } finally {
            if (high != null) {
                high.dispose();
            }
            low.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void fuelWaitsForTheDryerAndTheSeparatorsToBeOut(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 0);
        try {
            // fuel goes through the cattle chute, which only the refuelling bridge's own rails reach
            var bridge = f.base.bridge;
            bridge.refreshSite();
            f.base.fillPool(7.0);
            f.reactor().core().getPressureVessel().setCollapsedLevelIn(60.0);
            h.assertTrue(f.reactor().loadAssembly(f.base.corePosition(0), RefuellingBridgeFixture.fresh(20000.0)), "load");
            dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move move = new dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move(
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.core(f.base.corePosition(0)),
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.rack(f.base.pool.racks.get(0), 0));
            Component why = bridge.queue(move);
            h.assertTrue(why != null && english(why).contains("steam dryer"), "a fuel move is refused while the dryer is in: " + english(why));
            h.assertTrue(f.reactor().refuellingBlockedMessage() != null, "the reactor says why");
            f.reactor().internalOut(VesselInternal.DRYER, pads(f).get(0));
            why = bridge.queue(move);
            h.assertTrue(why != null && english(why).contains("separator"), "and while the separator assembly is in: " + english(why));
            f.reactor().internalOut(VesselInternal.SEPARATOR, pads(f).get(1));
            why = bridge.queue(move);
            h.assertTrue(why == null, "with both out the move is queued: " + english(why));
            bridge.abort();
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void theDryerComesOutFirstAndGoesBackLastOnTheCrane(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 1);
        try {
            Component why = f.crane.queueInternal(VesselInternal.SEPARATOR, true);
            h.assertTrue(why != null && english(why).contains("dryer"), "the separators cannot come out under the dryer: " + english(why));
            why = f.crane.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why == null, "the dryer lift is queued: " + english(why));
            f.phases.clear();
            int ticks = run(h, f);
            BlockPos first = f.reactor().internalLocation(VesselInternal.DRYER);
            h.assertTrue(first != null && pad(f, first) != null && pad(f, first).stored() == VesselInternal.DRYER,
                    "the dryer stands on a pad and the reactor knows where: " + first);
            h.assertTrue(f.phases.equals(List.of(Phase.TRAVEL, Phase.LOWER_EMPTY, Phase.LATCH, Phase.RAISE_LOADED, Phase.TRANSIT, Phase.LOWER_LOADED,
                    Phase.RELEASE, Phase.RAISE_EMPTY, Phase.IDLE)), "the lift is the crane's own sequence: " + f.phases);
            h.assertTrue(ticks > 20 * 15, "a lift takes real time, not a click: " + ticks + " ticks");
            h.assertTrue(f.reactor().setVesselStateMessage(VesselState.SHUTDOWN) != null, "the head cannot go on with the dryer out");

            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, true) == null, "now the separators come out");
            run(h, f);
            BlockPos second = f.reactor().internalLocation(VesselInternal.SEPARATOR);
            h.assertTrue(second != null && !second.equals(first) && pad(f, second).stored() == VesselInternal.SEPARATOR, "on the other pad: " + second);
            h.assertTrue(f.reactor().refuellingBlockedMessage() == null, "fuel can move now");

            why = f.crane.queueInternal(VesselInternal.DRYER, false);
            h.assertTrue(why != null && english(why).contains("separator"), "the dryer cannot go back before the separators: " + english(why));
            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, false) == null, "the separators go back");
            run(h, f);
            h.assertTrue(f.reactor().internalInstalled(VesselInternal.SEPARATOR) && pad(f, second).stored() == null, "the separators are in, the pad empty");
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, false) == null, "then the dryer");
            run(h, f);
            h.assertTrue(f.reactor().internalInstalled(VesselInternal.DRYER) && pad(f, first).stored() == null, "the dryer is in, the pad empty");
            h.assertTrue(f.reactor().setVesselStateMessage(VesselState.SHUTDOWN) == null, "and the head goes back on");
            System.out.println("VESSEL INTERNALS: dryer out " + ticks + " ticks (" + ticks / 20 + " s) on the 7 x 7 x 10 fixture");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void theAssembliesComeOutOnlyFromAVesselFloodedToItsFlange(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 2);
        try {
            f.floodCavity(3.0);
            Component why = f.crane.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why != null && english(why).contains("flood"), "a lift from a vessel not flooded to its flange is refused: " + english(why));
            f.reactor().core().getPressureVessel().setCollapsedLevelIn(60.0);
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "flooded, it is queued");
            f.crane.abort();
            f.reactor().setVesselState(VesselState.SHUTDOWN);
            h.assertTrue(f.reactor().internalLiftBlocked(VesselInternal.DRYER) != null, "and never with the head on");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void aLiftedAssemblySpikesTheDoseAndWaterOnItsPadShieldsIt(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 3);
        try {
            BlockPos padPos = pads(f).get(0);
            Vec3 observer = Vec3.atCenterOf(f.o.offset(2, 10, -2));
            double before = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, true) != null, "dryer first");
            f.reactor().internalOut(VesselInternal.DRYER, f.console); // the dryer is elsewhere: this test is about the separators
            h.assertTrue(f.crane.queueInternal(VesselInternal.SEPARATOR, true) == null, "the separator lift is queued");
            f.runCraneUntil(Phase.TRANSIT, 40_000);
            double lifted = RadiationSources.doseRateMsvPerHour(f.level, observer);
            h.assertTrue(lifted > 1.0 && lifted > before * 100 + 1.0, "lifted out of the water the dose spikes: " + before + " -> " + lifted + " mSv/h");
            run(h, f);
            BlockPos on = f.reactor().internalLocation(VesselInternal.SEPARATOR);
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
            f.reactor().internalIn(VesselInternal.SEPARATOR);
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void savesKeepNoNewKeysWhileBothAreInAndRememberWhereTheyAre(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 4);
        try {
            var tag = f.reactor().saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(!tag.contains("InternalsOut"), "with both installed the reactor writes no new key");
            var padTag = pad(f, pads(f).get(0)).saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(!padTag.contains("Stored"), "an empty pad writes nothing");
            f.reactor().internalOut(VesselInternal.DRYER, pads(f).get(0));
            tag = f.reactor().saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(tag.contains("InternalsOut") && tag.getCompound("InternalsOut").getLong("dryer") == pads(f).get(0).asLong(),
                    "out, it is saved with where it is: " + tag.get("InternalsOut"));
            var state = f.reactor().getBlockState();
            var copy = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    f.reactor().getBlockPos(), state, tag, f.level.registryAccess());
            h.assertTrue(copy != null && !copy.internalInstalled(VesselInternal.DRYER) && copy.internalInstalled(VesselInternal.SEPARATOR)
                    && pads(f).get(0).equals(copy.internalLocation(VesselInternal.DRYER)), "and loads the same");
            tag.remove("InternalsOut");
            copy = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    f.reactor().getBlockPos(), state, tag, f.level.registryAccess());
            h.assertTrue(copy != null && copy.internalsOutMask() == 0, "an old save (no key) loads with both installed");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void breakingTheConsoleUnderAHangingDryerSetsItBack(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 5);
        try {
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "queued");
            f.runCraneUntil(Phase.TRANSIT, 40_000);
            h.assertTrue(f.crane.carriedInternal() == VesselInternal.DRYER && !f.reactor().internalInstalled(VesselInternal.DRYER), "the hook holds the dryer");
            h.assertTrue(f.crane.getBlockPos().equals(f.reactor().internalLocation(VesselInternal.DRYER)), "the reactor knows the crane holds it");
            f.crane.dropCarried(f.level);
            h.assertTrue(f.reactor().internalInstalled(VesselInternal.DRYER) && f.crane.carriedInternal() == null, "it is back in its vessel, not lost");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    /** The bridge moves fuel only: it refuses both lifts with a message pointing to the crane, and nothing moves. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void theRefuellingBridgeRefusesTheLiftsAndPointsToTheCrane(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 8);
        try {
            var bridge = f.base.bridge;
            bridge.refreshSite();
            for (VesselInternal which : VesselInternal.values()) {
                Component why = bridge.queueInternal(which, true);
                h.assertTrue(why != null && english(why).contains("crane") && english(why).contains("fuel only"), "the bridge refuses the " + which.title + ": " + english(why));
            }
            Component why = bridge.queue(new dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move(
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.internal(VesselInternal.DRYER),
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.pad(pads(f).get(0), VesselInternal.DRYER)));
            h.assertTrue(why != null && english(why).contains("crane"), "and the raw move: " + english(why));
            h.assertTrue(f.reactor().internalsOutMask() == 0 && !bridge.busy(), "nothing moved");
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, true) == null, "the crane takes it");
            f.crane.abort();
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    /** A world saved in alpha.53 with the dryer on the bridge's hoist: the bridge still sets it down on a pad, and then it is the crane's. */
    @GameTest(template = "empty", templateNamespace = "bwrinternals", timeoutTicks = 200)
    public static void aLoadAlreadyOnTheBridgeHoistCanStillBeSetDown(GameTestHelper h) throws Exception {
        CraneFixture f = build(h, 9);
        try {
            var bridge = f.base.bridge;
            var reg = f.level.registryAccess();
            bridge.refreshSite();
            // the saved state of a bridge holding the dryer over the pad column, as the alpha.53 bridge saved it
            CompoundTag tag = bridge.saveWithFullMetadata(reg);
            CompoundTag pos = new CompoundTag();
            BlockPos over = pads(f).get(0);
            pos.putDouble("X", over.getX() + 0.5);
            pos.putDouble("Y", bridge.site().homeY());
            pos.putDouble("Z", over.getZ() + 0.5);
            tag.put("Pos", pos);
            tag.putString("CarriedInternal", VesselInternal.DRYER.id);
            var vp = dev.bwr.mod.reactor.VesselProfile.of(f.reactor().vesselEnvelope(), false);
            tag.putIntArray("CarriedVessel", new int[]{vp.width, vp.depth, vp.height, 0});
            bridge.loadWithComponents(tag, reg);
            f.reactor().internalOut(VesselInternal.DRYER, bridge.getBlockPos());
            bridge.refreshSite();
            h.assertTrue(bridge.carriedInternal() == VesselInternal.DRYER, "the hoist holds the dryer");
            Component why = bridge.queueInternal(VesselInternal.SEPARATOR, true);
            h.assertTrue(why != null && english(why).contains("crane"), "anything else is refused: " + english(why));
            why = bridge.queueInternal(VesselInternal.DRYER, true);
            h.assertTrue(why == null, "but the dryer on the hoist is set down on a pad: " + english(why));
            f.base.runUntilStill(40_000);
            h.assertTrue(bridge.phase() == Phase.IDLE && bridge.carriedInternal() == null, "the bridge finished: " + english(bridge.haltMessage()));
            BlockPos on = f.reactor().internalLocation(VesselInternal.DRYER);
            h.assertTrue(on != null && pads(f).contains(on) && pad(f, on).stored() == VesselInternal.DRYER, "the dryer is on a pad, and the reactor knows which: " + on);
            // from there it is the crane's: it takes it back into the vessel
            h.assertTrue(f.crane.queueInternal(VesselInternal.DRYER, false) == null, "the crane takes the dryer back in from its pad");
            f.crane.abort();
        } finally {
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
        CraneFixture f = build(h, 7);
        try {
            VesselInternalsCcChecks.run(h, f);
        } finally {
            f.dispose();
        }
        System.out.println("VESSEL INTERNALS CC: getInternals, the crane's queueInternalOut and queueInternalIn agree with the block entities; the bridge refuses");
        h.succeed();
    }
}
