package dev.bwr.mod.devtest;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.gui.CavityInfo;
import dev.bwr.mod.reactor.CavitySeal;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BridgeSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The cavity seal and the well above the open vessel, through real blocks: a formed reactor with stone walls round it, the seal found
 * (or not) by the gap rule, water raised over the flange by the feedwater path, the leak into the drywell without a seal, the refuelling
 * bridge's cover reading the well, and a save and reload. Own fixture origins. No CC types.
 */
@GameTestHolder("bwrcavity")
@PrefixGameTestTemplate(false)
public final class CavitySealTests {

    private CavitySealTests() {
    }

    /** Interior 5 x 5 x 8: envelope 7 x 7 x 10, top row bottom + 9, flange row bottom + 8, deepest seal row top - round(10 / 3) = bottom + 6. */
    private static BlockPos origin(int i) {
        return new BlockPos(94000 + 120 * i, 190, 94000);
    }

    private static void validate(ReactorControllerBlockEntity be) throws Exception {
        var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
        method.setAccessible(true);
        method.invoke(be, be.getLevel());
        // the game steps the cavity survey a few thousand reads a tick; the tests that want its answer now take it
        be.finishCavitySurvey();
    }

    /** A formed reactor and its walls to place. */
    static final class Site {
        final ServerLevel level;
        final BlockPos min;
        final int width;
        final int depth;
        final int height;
        ReactorControllerBlockEntity be;

        Site(ServerLevel level, BlockPos min, int width, int depth, int height) throws Exception {
            this.level = level;
            this.min = min;
            this.width = width;
            this.depth = depth;
            this.height = height;
            // a clear field round the vessel: earlier runs leave walls
            for (int x = -12; x <= width + 12; x++) {
                for (int z = -12; z <= depth + 12; z++) {
                    for (int y = -1; y <= height + 22; y++) {
                        level.setBlock(min.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            this.be = CompactCoreRegressionTests.build(level, min, width, depth, height, false);
        }

        VesselAppearance.Envelope env() {
            return be.vesselEnvelope();
        }

        int bottom() {
            return env().min().getY();
        }

        /** A ring of stone round the footprint at {@code offset} blocks, over the rows bottom + from .. bottom + to. */
        void ring(int offset, int from, int to) {
            ring(offset, offset, offset, offset, from, to);
        }

        /** Offsets north (-z), south (+z), west (-x), east (+x). */
        void ring(int n, int s, int w, int e, int from, int to) {
            placeRing(level, env(), n, s, w, e, from, to);
        }

        void survey() throws Exception {
            validate(be);
        }

        CavitySeal.Result seal() {
            return be.cavity();
        }

        /** Rows built above the vessel by the test beyond the 22 the field clears (the worst-case survey test). */
        int extraRows = 22;

        void clear() {
            dev.bwr.mod.devtest.FixtureCleanup.drainAt(level, be.getBlockPos());
            for (int x = -12; x <= width + 12; x++) {
                for (int z = -12; z <= depth + 12; z++) {
                    for (int y = -3; y <= height + extraRows + 4; y++) {
                        level.setBlock(min.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }

        /** One game tick of the controller, as the server runs it. */
        void tick() {
            ReactorControllerBlockEntity.serverTick(level, be.getBlockPos(), be.getBlockState(), be);
        }
    }

    /** Stone in the air round the footprint: a rectangle at the given offsets, rows bottom + from .. bottom + to. Blocks already there stay. */
    static void placeRing(ServerLevel level, VesselAppearance.Envelope env, int n, int s, int w, int e, int from, int to) {
        placeRing(level, env, n, s, w, e, from, to, Blocks.STONE);
    }

    static void placeRing(ServerLevel level, VesselAppearance.Envelope env, int n, int s, int w, int e, int from, int to, net.minecraft.world.level.block.Block block) {
        int x0 = env.min().getX();
        int x1 = env.max().getX();
        int z0 = env.min().getZ();
        int z1 = env.max().getZ();
        for (int row = env.min().getY() + from; row <= env.min().getY() + to; row++) {
            for (int x = x0 - w; x <= x1 + e; x++) {
                stone(level, x, row, z0 - n, block);
                stone(level, x, row, z1 + s, block);
            }
            for (int z = z0 - n; z <= z1 + s; z++) {
                stone(level, x0 - w, row, z, block);
                stone(level, x1 + e, row, z, block);
            }
        }
    }

    private static void stone(ServerLevel level, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        BlockPos p = new BlockPos(x, y, z);
        if (level.getBlockState(p).isAir()) {
            level.setBlock(p, block.defaultBlockState(), 2);
        }
    }

    private static Site site(GameTestHelper h, int i) throws Exception {
        return new Site(h.getLevel(), origin(i), 5, 5, 8);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The gap rule
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void aSealFormsWithTheWallTouchingTheVessel(GameTestHelper h) throws Exception {
        Site s = site(h, 0);
        try {
            h.assertTrue(s.be.isFormed(), "the reactor did not form: " + s.be.statusLines());
            s.ring(1, 3, 16);
            s.survey();
            var seal = s.seal();
            h.assertTrue(seal != null && seal.present(), "no seal with the wall touching: " + (seal == null ? "no survey" : seal.english()));
            h.assertTrue(seal.sealRow() == seal.flangeRow() && seal.flangeRow() == s.bottom() + 8,
                    "the seal forms at the highest closed row, the flange: seal y " + seal.sealRow() + " flange y " + seal.flangeRow());
            h.assertTrue(seal.gapMask() == 0, "no side has a gap: " + seal.gapMask());
            h.assertTrue(s.env().cavity() != null && s.env().cavity().sealRow() == seal.sealRow(), "the seal is part of the vessel's picture");
            h.assertTrue(seal.height() == 10 && seal.lowestRow() == s.bottom() + 6 && seal.topY() == s.bottom() + 9,
                    "H " + seal.height() + " deepest " + (seal.lowestRow() - s.bottom()));
            h.assertTrue(seal.wellUsable() && seal.wellTopRow() == s.bottom() + 16, "the shaft is the well: top row " + (seal.wellTopRow() - s.bottom()));
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void aSealFormsWithAOneBlockGap(GameTestHelper h) throws Exception {
        Site s = site(h, 1);
        try {
            s.ring(2, 3, 16);
            s.survey();
            var seal = s.seal();
            h.assertTrue(seal != null && seal.present(), "no seal with a one block gap: " + (seal == null ? "no survey" : seal.english()));
            h.assertTrue(seal.gapMask() == 15, "every side has its gap: " + seal.gapMask());
            h.assertTrue(seal.sealRow() == s.bottom() + 8, "at the flange: " + (seal.sealRow() - s.bottom()));
            // mixed: touching to the north and east, a gap to the south and west
            s.clear();
            s = site(h, 1);
            s.ring(1, 2, 2, 1, 3, 16);
            s.survey();
            h.assertTrue(s.seal().present() && s.seal().gapMask() == 0b0110, "south and west carry a gap: " + s.seal().gapMask());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void noSealWhenOneSideIsTwoBlocksAway(GameTestHelper h) throws Exception {
        Site s = site(h, 2);
        try {
            s.ring(1, 1, 3, 1, 3, 16);
            s.survey();
            var seal = s.seal();
            h.assertTrue(seal != null && !seal.present(), "a seal formed with a wall two blocks away on the west: " + (seal == null ? "no survey" : seal.english()));
            h.assertTrue(seal.failure() == CavitySeal.Failure.WALL_TOO_FAR && seal.badSide() == Direction.WEST && seal.badGap() == 2,
                    "the report names the west side and the two blocks: " + seal.badSide() + " " + seal.badGap());
            h.assertTrue(seal.english().contains("west") && seal.english().contains("2 blocks"), "the text says so: " + seal.english());
            h.assertTrue(s.env().cavity() == null, "no seal is drawn");
            // the water inventory sees no well: nothing above the flange can be held
            h.assertTrue(s.be.core().getPressureVessel().getWell() != null && s.be.core().getPressureVessel().getWell().capacityM3() == 0.0,
                    "an unsealed vessel holds nothing over its flange");
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void theSealFormsBelowTheFlangeWhereTheWallFirstCloses(GameTestHelper h) throws Exception {
        Site s = site(h, 3);
        try {
            // touching walls from row 3 to 7; above them the shaft widens to three blocks out (too wide for a seal, still a shaft)
            s.ring(1, 3, 7);
            s.ring(3, 8, 16);
            s.survey();
            var seal = s.seal();
            h.assertTrue(seal != null && seal.present(), "no seal below the flange: " + (seal == null ? "no survey" : seal.english()));
            h.assertTrue(seal.sealRow() == s.bottom() + 7 && seal.flangeRow() == s.bottom() + 8, "the seal is a row under the flange: " + (seal.sealRow() - s.bottom()));
            h.assertTrue(seal.wellUsable(), "the wider shaft above is still closed: " + seal.english());
            var profile = seal.profile();
            h.assertTrue(profile != null && profile.annulusM3() > 40.0, "the annulus from the plate to the lip holds water: " + (profile == null ? 0 : profile.annulusM3()));
            // the same walls with the wide part missing: the shaft does not reach the flange
            s.clear();
            s = site(h, 3);
            s.ring(1, 3, 7);
            s.survey();
            h.assertTrue(s.seal().present() && s.seal().sealRow() == s.bottom() + 7 && !s.seal().wellUsable(),
                    "a seal under an open wall is a seal but the well cannot hold water: " + s.seal().english());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void theLowestAllowedRowFormsAndOneRowLowerDoesNot(GameTestHelper h) throws Exception {
        Site s = site(h, 4);
        try {
            s.ring(1, 6, 6);
            s.survey();
            h.assertTrue(s.seal().present() && s.seal().sealRow() == s.bottom() + 6 && s.seal().lowestRow() == s.bottom() + 6,
                    "a ring only at top - round(10 / 3) = 3 rows below the top (bottom + 6) forms: " + s.seal().english());
            s.clear();
            s = site(h, 4);
            s.ring(1, 5, 5);
            s.survey();
            h.assertTrue(!s.seal().present(), "a ring one row deeper (bottom + 5) is below the upper third: " + s.seal().english());
            h.assertTrue(s.seal().english().contains("deepest allowed"), "and the reason says so: " + s.seal().english());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 800)
    public static void anOddVesselHeightRoundsTheLowestRowToTheNearestBlock(GameTestHelper h) throws Exception {
        // H = interior height + 2, depth below the top = round(H / 3): 11 / 3 = 3.67 -> 4; 13 / 3 = 4.33 -> 4; 14 / 3 = 4.67 -> 5.
        int[][] cases = {{9, 4}, {11, 4}, {12, 5}};
        for (int i = 0; i < cases.length; i++) {
            Site s = new Site(h.getLevel(), origin(5 + i), 5, 5, cases[i][0]);
            try {
                int height = cases[i][0] + 2;
                int lowest = height - 1 - cases[i][1];
                s.ring(1, lowest, lowest);
                s.survey();
                h.assertTrue(s.seal().height() == height && s.seal().lowestRow() == s.bottom() + lowest
                                && s.seal().topY() - s.seal().lowestRow() == cases[i][1],
                        "H " + height + ": deepest row " + (s.seal().lowestRow() - s.bottom()) + ", expected " + lowest);
                h.assertTrue(s.seal().present() && s.seal().sealRow() == s.bottom() + lowest, "H " + height + ": a ring at row " + lowest + " forms: " + s.seal().english());
                s.clear();
                s = new Site(h.getLevel(), origin(5 + i), 5, 5, cases[i][0]);
                s.ring(1, lowest - 1, lowest - 1);
                s.survey();
                h.assertTrue(!s.seal().present(), "H " + height + ": a ring at row " + (lowest - 1) + " does not: " + s.seal().english());
            } finally {
                s.clear();
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void breakingAWallBlockOfTheRingRemovesTheSealOnTheNextSurvey(GameTestHelper h) throws Exception {
        Site s = site(h, 8);
        try {
            s.ring(1, 3, 16);
            s.survey();
            h.assertTrue(s.seal().present(), "the ring seals: " + s.seal().english());
            var e = s.env();
            // hole in every row of the ring on the east side: no row closes
            for (int row = 3; row <= 8; row++) {
                s.level.setBlock(new BlockPos(e.max().getX() + 1, s.bottom() + row, e.min().getZ() + 3), Blocks.AIR.defaultBlockState(), 2);
            }
            s.survey();
            h.assertTrue(!s.seal().present(), "a hole in the ring in every allowed row: " + s.seal().english());
            h.assertTrue(s.seal().badSide() == Direction.EAST, "the east side: " + s.seal().badSide());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Flooding
    // ------------------------------------------------------------------------------------------------------------

    private static void flood(Site s, double kgPerS, int ticks) {
        s.be.core().setFeedwaterFlowKgPerS(kgPerS);
        for (int i = 0; i < ticks; i++) {
            s.tick();
        }
        s.be.core().setFeedwaterFlowKgPerS(0.0);
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void floodingRaisesTheLevelIntoTheWellWhenSealed(GameTestHelper h) throws Exception {
        Site s = site(h, 9);
        try {
            s.ring(1, 3, 16);
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 1.0);
            var well = vessel.getWell();
            h.assertTrue(well != null && well.capacityM3() > 300.0, "the sealed well has room: " + (well == null ? 0 : well.capacityM3()) + " m3");
            // the lip is 1 inch away: a few seconds of feed reach it, then the level keeps rising over it
            flood(s, 1200.0, 400);
            double level = vessel.getCollapsedLevelIn();
            h.assertTrue(level > PressureVessel.FLANGE_LEVEL_IN + 0.5, "the level rose over the flange into the well: " + level + " in");
            double rise = (level - PressureVessel.FLANGE_LEVEL_IN) * PressureVessel.METRES_PER_INCH;
            h.assertTrue(vessel.getWellWaterM3() > rise * 40.0, "the well's cross-section is far larger than the vessel's: " + vessel.getWellWaterM3() + " m3 for " + rise + " m");
            h.assertTrue(vessel.getWellSpillKgPerS() == 0.0, "nothing is lost while the well has room: " + vessel.getWellSpillKgPerS());
            h.assertTrue(s.be.wellSurfaceLocal() > VesselWaterGeometryHelper.rim(s.env()), "the clients draw the water above the lip: " + s.be.wellSurfaceLocal());
            // the inventory is the whole truth: the same mass in a vessel without the well reads far higher
            double mass = vessel.getLiquidMassKg();
            var plain = new PressureVessel(new dev.bwr.core.CoreConfig());
            plain.restoreLiquidMassKg(mass);
            h.assertTrue(plain.getCollapsedLevelIn() > level + 15.0, "the well changes the mapping: single area " + plain.getCollapsedLevelIn() + " in, with the well " + level + " in");
            // the info the screen and the computers read
            var info = CavityInfo.capture(s.be);
            h.assertTrue(info.known() && info.present() && info.wellUsable() && info.wellWaterM3() > 0 && info.spillKgPerS() == 0.0, "info " + info);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aSealBelowTheFlangeFillsTheAnnulusWhileTheVesselLevelHoldsAtTheLip(GameTestHelper h) throws Exception {
        Site s = site(h, 10);
        try {
            s.ring(1, 3, 7);
            s.ring(3, 8, 16);
            s.survey();
            h.assertTrue(s.seal().present() && s.seal().sealRow() == s.bottom() + 7 && s.seal().wellUsable(), "setup: " + s.seal().english());
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 0.1);
            double annulus = s.seal().profile().annulusM3();
            double plate = s.seal().profile().plateTop();
            double lip = s.seal().profile().lip();
            flood(s, 1000.0, 60);
            h.assertTrue(vessel.getWellWaterM3() > 0 && vessel.getWellWaterM3() < annulus, "part of the annulus is full: " + vessel.getWellWaterM3() + " of " + annulus);
            h.assertTrue(Math.abs(vessel.getCollapsedLevelIn() - PressureVessel.FLANGE_LEVEL_IN) < 1.0e-6,
                    "the level in the vessel stands at the lip while the annulus fills: " + vessel.getCollapsedLevelIn());
            double surface = s.be.wellSurfaceLocal();
            h.assertTrue(surface > plate && surface < lip, "the water in the annulus is between the plate and the lip: " + plate + " < " + surface + " < " + lip);
            flood(s, 3000.0, 1400);
            h.assertTrue(vessel.getCollapsedLevelIn() > PressureVessel.FLANGE_LEVEL_IN + 0.01, "once the annulus is full the level rises: " + vessel.getCollapsedLevelIn());
            h.assertTrue(s.be.wellSurfaceLocal() > lip, "and the surface is over the lip: " + s.be.wellSurfaceLocal());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void withoutASealTheLevelHoldsAtTheFlangeAndTheDrywellLeakShows(GameTestHelper h) throws Exception {
        Site s = site(h, 11);
        try {
            s.survey();
            h.assertTrue(s.seal() != null && !s.seal().present(), "no walls, no seal: " + s.seal().english());
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 0.5);
            s.be.core().setFeedwaterFlowKgPerS(1200.0);
            double worst = 0;
            double leak = 0;
            for (int i = 0; i < 400; i++) {
                s.tick();
                worst = Math.max(worst, vessel.getCollapsedLevelIn());
                leak = Math.max(leak, vessel.getWellSpillKgPerS());
            }
            h.assertTrue(worst <= PressureVessel.FLANGE_LEVEL_IN + 1.0e-6, "the level never passes the flange: " + worst);
            h.assertTrue(leak > 1000.0 && leak < 1400.0, "the leak is what the feed brings (kg/s): " + leak);
            var info = CavityInfo.capture(s.be);
            h.assertTrue(info.known() && !info.present() && info.spillsToDrywell() && info.spillKgPerS() > 500.0, "the screen shows a leak to the drywell: " + info);
            // with the head on, nothing leaks, whatever the feed does: the vessel is closed
            s.be.setVesselState(VesselState.SHUTDOWN);
            for (int i = 0; i < 40; i++) {
                s.tick();
            }
            h.assertTrue(vessel.getWellSpillKgPerS() == 0.0, "no leak with the head on: " + vessel.getWellSpillKgPerS());
            h.assertTrue(vessel.getCollapsedLevelIn() > PressureVessel.FLANGE_LEVEL_IN + 0.01, "and the level goes on rising: " + vessel.getCollapsedLevelIn());
            s.be.core().setFeedwaterFlowKgPerS(0.0);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFullWellOverflowsOntoTheRefuelFloorNotIntoTheDrywell(GameTestHelper h) throws Exception {
        Site s = site(h, 12);
        try {
            s.ring(1, 3, 12);
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            var well = vessel.getWell();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + (well.riseM().length > 0 ? 0.5 / PressureVessel.METRES_PER_INCH : 0));
            s.be.core().setFeedwaterFlowKgPerS(3000.0);
            for (int i = 0; i < 2400; i++) {
                s.tick();
            }
            double top = PressureVessel.FLANGE_LEVEL_IN;
            for (double r : well.riseM()) {
                top += r / PressureVessel.METRES_PER_INCH;
            }
            h.assertTrue(Math.abs(vessel.getCollapsedLevelIn() - top) < 0.2, "the well is full to its top: " + vessel.getCollapsedLevelIn() + " of " + top);
            var info = CavityInfo.capture(s.be);
            h.assertTrue(info.present() && info.wellUsable() && !info.spillsToDrywell() && info.spillKgPerS() > 1000.0, "it overflows onto the floor, not into the drywell: " + info);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void closingTheHeadOverAFloodedWellLeavesTheWaterWhereItIs(GameTestHelper h) throws Exception {
        Site s = site(h, 13);
        try {
            s.ring(1, 3, 16);
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + 1.0 / PressureVessel.METRES_PER_INCH);
            double mass = vessel.getLiquidMassKg();
            double level = vessel.getCollapsedLevelIn();
            s.be.setVesselState(VesselState.SHUTDOWN);
            for (int i = 0; i < 40; i++) {
                s.tick();
            }
            h.assertTrue(Math.abs(vessel.getLiquidMassKg() - mass) < 5.0, "the head goes on and nothing is drained: " + (vessel.getLiquidMassKg() - mass) + " kg");
            h.assertTrue(Math.abs(vessel.getCollapsedLevelIn() - level) < 0.05, "the level is where it was: " + vessel.getCollapsedLevelIn() + " vs " + level);
            // it drains as the level falls: the player's own drain (a leak) takes it down through the well
            s.be.core().getPressureVessel().restoreLiquidMassKg(mass - 20.0 * mass / 400.0);
            h.assertTrue(vessel.getCollapsedLevelIn() < level, "less water, lower level: " + vessel.getCollapsedLevelIn());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The bridge
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void theBridgesCoverReadsTheLevelOfTheWell(GameTestHelper h) throws Exception {
        Site s = site(h, 14);
        try {
            s.ring(1, 3, 16);
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN);
            double atTheLip = BridgeSite.cavityWaterM(s.be);
            h.assertTrue(Math.abs(atTheLip - (PressureVessel.FLANGE_LEVEL_IN - PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN) * 0.0254) < 1.0e-9, "cover at the lip: " + atTheLip);
            // raise the water 1.5 m up the well by the feed
            double target = PressureVessel.FLANGE_LEVEL_IN + 1.5 / PressureVessel.METRES_PER_INCH;
            s.be.core().setFeedwaterFlowKgPerS(1500.0);
            for (int i = 0; i < 4000 && vessel.getCollapsedLevelIn() < target; i++) {
                s.tick();
            }
            s.be.core().setFeedwaterFlowKgPerS(0.0);
            double cover = BridgeSite.cavityWaterM(s.be);
            double rise = (vessel.getCollapsedLevelIn() - PressureVessel.FLANGE_LEVEL_IN) * PressureVessel.METRES_PER_INCH;
            h.assertTrue(rise >= 1.49, "the well was filled 1.5 m: " + rise);
            h.assertTrue(Math.abs(cover - (atTheLip + rise)) < 1.0e-9, "the bridge's cover is the cover at the lip plus the well's rise: " + cover + " vs " + (atTheLip + rise));
            h.assertTrue(cover > atTheLip + 1.4, "a flooded well adds to the water over the fuel: " + cover + " > " + atTheLip);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFloodedWellSatisfiesTheHoistLimitAndTheBridgeWorksOverIt(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), origin(20), 4);
        try {
            var vessel = f.reactor.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN + 1.2 / 0.0254);
            f.bridge.refreshSite();
            f.fillPool(7.0);
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));
            var move = new dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move(
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.core(slot),
                    dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc.rack(f.pool.racks.get(0), 1));
            h.assertTrue(f.bridge.queue(move) == null, "queued");
            runBridge(h, f);
            String why = dev.bwr.mod.text.BwrText.english(f.bridge.haltMessage());
            h.assertTrue(f.bridge.phase() == dev.bwr.core.refuelling.BridgeMotion.Phase.HALTED
                    && (why.contains("Hoist limit") || why.contains("too shallow")),
                    "1.2 m over the fuel: the water holds the bridge: " + f.bridge.phase() + " / " + dev.bwr.mod.text.BwrText.english(f.bridge.haltMessage()));
            h.assertTrue(f.reactor.core().getCoreLoading().isOccupied(slot), "the assembly is still on its seat");

            // the player builds the well walls and floods it with the feed pumps
            placeRing(f.level, f.reactor.vesselEnvelope(), 1, 1, 1, 1, 3, 11);
            validate(f.reactor);
            h.assertTrue(f.reactor.cavity() != null && f.reactor.cavity().present() && f.reactor.cavity().wellUsable(),
                    "the walls seal the cavity: " + (f.reactor.cavity() == null ? "no survey" : f.reactor.cavity().english()));
            f.reactor.core().setFeedwaterFlowKgPerS(3000.0);
            for (int i = 0; i < 6000 && vessel.getCollapsedLevelIn() < PressureVessel.FLANGE_LEVEL_IN + 0.5 / 0.0254; i++) {
                ReactorControllerBlockEntity.serverTick(f.level, f.reactor.getBlockPos(), f.reactor.getBlockState(), f.reactor);
            }
            f.reactor.core().setFeedwaterFlowKgPerS(0.0);
            double cover = BridgeSite.cavityWaterM(f.reactor);
            h.assertTrue(cover > 6.2, "the well is flooded: the cover reads " + cover + " m");
            f.bridge.refreshSite();
            f.bridge.resume();
            h.assertTrue(f.bridge.busy(), "with the well flooded it resumes without an override: " + f.bridge.phase());
            runBridge(h, f);
            h.assertTrue(!f.reactor.core().getCoreLoading().isOccupied(slot)
                    && !f.pool.be.stackAt(f.pool.racks.get(0), 1).isEmpty(), "and the assembly is in the rack");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    private static void runBridge(GameTestHelper h, RefuellingBridgeFixture f) {
        int n = 0;
        while (f.bridge.busy() && n < 40_000) {
            f.bridge.scheduledTick(f.level);
            n++;
        }
        h.assertTrue(n < 40_000, "the move never finished: " + f.bridge.phase());
    }

    // ------------------------------------------------------------------------------------------------------------
    // The worst case: the survey is spread over ticks
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 2000)
    public static void theWorstCaseSurveyIsSpreadOverTicksAndCostsLittleInAny(GameTestHelper h) throws Exception {
        // 21 x 21 interior = a 23-wide vessel, the widest there is; 60 tall inside (H = 62, close to the 64 the structure walk allows); the shaft of
        // touching walls goes far above it, so the well is as deep as a survey counts: 48 layers.
        Site s = new Site(h.getLevel(), new BlockPos(94000 + 120 * 30, 40, 94300), 21, 21, 60);
        s.extraRows = 60;
        try {
            h.assertTrue(s.be.isFormed(), "the reactor did not form: " + s.be.statusLines());
            var env = s.env();
            h.assertTrue(env.width() == 23 && env.height() == 62, "the largest vessel: " + env.width() + " x " + env.height());
            s.ring(1, 5, 61 + 55);
            // warm up, then the whole survey in one go: what one tick used to pay on every sweep
            CavitySeal.survey(s.level, env);
            var whole = new CavitySeal.Survey(s.level, env);
            long t0 = System.nanoTime();
            whole.step(Integer.MAX_VALUE);
            long wholeNs = System.nanoTime() - t0;
            var all = whole.result();
            h.assertTrue(all != null && all.present() && all.wellUsable(), "the worst case seals: " + (all == null ? "null" : all.english()));
            int layers = all.wellTopRow() - all.sealRow() + 1;
            h.assertTrue(layers >= 40, "a deep well (layers " + layers + ")");
            // the same survey, a budget a tick
            var spread = new CavitySeal.Survey(s.level, env);
            int steps = 0;
            long maxNs = 0;
            while (!spread.done() && steps < 2000) {
                long a = System.nanoTime();
                spread.step(CavitySeal.READS_PER_TICK);
                maxNs = Math.max(maxNs, System.nanoTime() - a);
                steps++;
            }
            var part = spread.result();
            h.assertTrue(part != null && part.sealRow() == all.sealRow() && part.wellTopRow() == all.wellTopRow()
                    && part.profile().toWell().equals(all.profile().toWell()) && part.visual().equals(all.visual()),
                    "stepping gives the same answer as the whole survey");
            h.assertTrue(whole.totalReads() == spread.totalReads(), "and reads the same blocks: " + whole.totalReads() + " vs " + spread.totalReads());
            // no step began a read past its budget by more than one row of the ring (or one cell of the well)
            h.assertTrue(spread.maxStepReads() <= CavitySeal.READS_PER_TICK + 400, "the most reads in one step: " + spread.maxStepReads());
            h.assertTrue(steps >= whole.totalReads() / (CavitySeal.READS_PER_TICK + 400), "it took " + steps + " steps");
            System.out.println("CAVITY SURVEY worst case (23 x 23 x 62, " + layers + " layers): " + whole.totalReads() + " reads; all at once "
                    + wholeNs / 1000 + " us; spread over " + steps + " ticks at up to " + spread.maxStepReads() + " reads, the dearest tick "
                    + maxNs / 1000 + " us");
            h.assertTrue(maxNs < 8_000_000L, "one tick of the spread survey cost " + maxNs / 1000 + " us");
            // and through the reactor's own tick: it finishes by itself, one budget a tick
            s.be.markStructureDirty();
            int ticks = 0;
            s.tick();
            while (!s.be.cavitySurveyDone() && ticks < 400) {
                s.tick();
                ticks++;
            }
            h.assertTrue(s.be.cavitySurveyDone() && s.be.cavity().present(), "the reactor's own ticks finish it: " + ticks);
            h.assertTrue(ticks >= steps - 3, "and it takes the reactor as many ticks as the budget says (" + steps + "), not one: " + ticks);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Save and reload, old saves
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFloodedWellSurvivesASaveAndReload(GameTestHelper h) throws Exception {
        Site s = site(h, 15);
        try {
            s.ring(1, 3, 7);
            s.ring(3, 8, 16);
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 0.1);
            // into the annulus (level flat at the lip) and then up the well
            flood(s, 1000.0, 120);
            double inAnnulus = vessel.getWellWaterM3();
            double annulusLevel = vessel.getCollapsedLevelIn();
            CompoundTag saved = s.be.saveWithFullMetadata(s.level.registryAccess());
            h.assertTrue(saved.contains("WellInventoryKg"), "water over the flange is saved as inventory");
            reload(h, s, saved);
            vessel = s.be.core().getPressureVessel();
            h.assertTrue(Math.abs(vessel.getWellWaterM3() - inAnnulus) < 1.0e-6 * Math.max(1, inAnnulus) + 1.0e-6,
                    "the annulus water is the same after a reload: " + vessel.getWellWaterM3() + " vs " + inAnnulus);
            h.assertTrue(Math.abs(vessel.getCollapsedLevelIn() - annulusLevel) < 1.0e-6, "and the level: " + vessel.getCollapsedLevelIn() + " vs " + annulusLevel);
            h.assertTrue(s.seal() != null && s.seal().present() && s.seal().sealRow() == s.bottom() + 7, "the seal is surveyed again from the blocks: " + (s.seal() == null ? "none" : s.seal().english()));
            flood(s, 3000.0, 1600);
            double high = vessel.getCollapsedLevelIn();
            h.assertTrue(high > PressureVessel.FLANGE_LEVEL_IN + 0.05, "up the well: " + high);
            double mass = vessel.getLiquidMassKg();
            saved = s.be.saveWithFullMetadata(s.level.registryAccess());
            reload(h, s, saved);
            vessel = s.be.core().getPressureVessel();
            h.assertTrue(Math.abs(vessel.getLiquidMassKg() - mass) < 1.0e-6 * mass, "the inventory is exact after a reload: " + (vessel.getLiquidMassKg() - mass));
            h.assertTrue(Math.abs(vessel.getCollapsedLevelIn() - high) < 1.0e-6, "and so is the level: " + vessel.getCollapsedLevelIn() + " vs " + high);
            // nothing is saved while there is no water over the flange
            vessel.setCollapsedLevelIn(8.0);
            h.assertTrue(!s.be.saveWithFullMetadata(s.level.registryAccess()).contains("WellInventoryKg"), "no key for a vessel that holds nothing over its flange");
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void anOldSaveWithNoCavityKeysLoadsAndSurveysItsSealOnTheFirstTick(GameTestHelper h) throws Exception {
        Site s = site(h, 16);
        try {
            s.ring(2, 3, 16);
            s.survey();
            h.assertTrue(s.seal().present(), "setup: " + s.seal().english());
            CompoundTag saved = s.be.saveWithFullMetadata(s.level.registryAccess());
            // an old save has no cavity key at all (a seal is never saved), nor any visual keys
            h.assertTrue(!saved.contains("WellInventoryKg") && !saved.contains("VisualSeal"), "a seal is not saved: " + saved.getAllKeys());
            // loaded as the game loads it, then only ticked: the survey is stepped a few thousand reads a tick and finishes by itself
            BlockPos pos = s.be.getBlockPos();
            var state = s.be.getBlockState();
            s.level.removeBlockEntity(pos);
            s.be = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, state, saved, s.level.registryAccess());
            s.level.setBlockEntity(s.be);
            int ticks = 0;
            while (s.be.cavity() == null && ticks < 600) {
                s.tick();
                ticks++;
            }
            h.assertTrue(s.be.cavity() != null, "the first ticks survey the seal: none after " + ticks);
            h.assertTrue(s.be.isFormed(), "it forms on load");
            h.assertTrue(s.seal() != null && s.seal().present() && s.seal().gapMask() == 15, "and surveys its seal: " + (s.seal() == null ? "none" : s.seal().english()));
            // the update tag a client gets carries the seal and a client reads it back
            CompoundTag tag = s.be.getUpdateTag(s.level.registryAccess());
            var read = VesselAppearance.read(tag);
            h.assertTrue(read != null && read.cavity() != null && read.cavity().equals(s.env().cavity()), "the clients' picture equals the server's");
            // an old client tag without the keys reads as no seal
            tag.remove("VisualSeal");
            var old = VesselAppearance.read(tag);
            h.assertTrue(old != null && old.cavity() == null, "an update tag without the keys is a vessel without a seal");
        } finally {
            s.clear();
        }
        h.succeed();
    }

    private static void reload(GameTestHelper h, Site s, CompoundTag saved) throws Exception {
        BlockPos pos = s.be.getBlockPos();
        var state = s.be.getBlockState();
        s.level.removeBlockEntity(pos);
        BlockEntity restored = BlockEntity.loadStatic(pos, state, saved, s.level.registryAccess());
        s.level.setBlockEntity(restored);
        s.be = (ReactorControllerBlockEntity) restored;
        validate(s.be);
        h.assertTrue(s.be.isFormed(), "the reloaded reactor forms: " + s.be.statusLines());
    }

    /** Small shim so the helpers read the reloaded entity. */
    private static final class VesselWaterGeometryHelper {
        static double rim(VesselAppearance.Envelope e) {
            return dev.bwr.mod.reactor.VesselWaterGeometry.rim(e);
        }
    }
}
