package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.accident.HeadMissile;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.accident.AccidentBlast;
import dev.bwr.mod.accident.HeadMissileLauncher;
import dev.bwr.mod.accident.ReactorHeadEntity;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.reactor.ReactorBoundaryEffects;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The vessel head as a missile (see SEVERE-ACCIDENT-UPDATE.md, "The head as a missile"): whether the blocks above it
 * hold it is decided by their blast resistance and thickness, a head that gets through flies and lands and rests, a
 * head is saved and reloaded in flight and at rest, it is thrown once, and a protection mod can refuse a block.
 *
 * <p>Fixtures sit at x = 710,000 and east, 400 blocks apart; chunks around a fixture are force-loaded for the length of
 * the test so that the head entity is ticked by the game itself, and released at the end. The stack the failure meets is
 * built from the mod's own lined concrete (blast resistance 40, an ordinary concrete) or from obsidian (1,200), in
 * as many rows as the test needs.
 */
@GameTestHolder("bwrblast")
@PrefixGameTestTemplate(false)
public final class HeadMissileTests {
    private HeadMissileTests() {
    }

    private static final int W = 9, D = 9, H = 12;
    /** The vessel's outside size: the shell is a block thick all round. */
    private static final int OUT_W = W + 2, OUT_D = D + 2;
    private static final int FLIGHT_TICKS = 260;

    private static final class Site {
        final ServerLevel level;
        final BlockPos min, lo, hi;
        final ReactorControllerBlockEntity reactor;
        final int top;
        final double ax, az;

        Site(GameTestHelper h, BlockPos min) throws Exception {
            this(h, min, H);
        }

        /** A fixture vessel of this interior height: the steam space (and so the head's energy) follows the build. */
        Site(GameTestHelper h, BlockPos min, int height) throws Exception {
            this.level = h.getLevel();
            this.min = min;
            this.lo = min.offset(-14, -14, -14);
            this.hi = min.offset(W + 14, height + 50, D + 14);
            load(lo, hi);
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            BlockPos floorLo = min.offset(-44, -13, -44), floorHi = min.offset(W + 44, -9, D + 44);
            load(floorLo, floorHi);
            for (BlockPos p : BlockPos.betweenClosed(floorLo, floorHi)) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            reactor = CompactCoreRegressionTests.build(level, min, W, D, height, false);
            h.assertTrue(reactor.isFormed(), "fixture reactor did not form: " + reactor.statusLines());
            top = min.getY() + height + 1; // the first row above the shell
            ax = min.getX() + W / 2.0;
            az = min.getZ() + D / 2.0;
        }

        private void load(BlockPos a, BlockPos b) {
            for (int x = a.getX() >> 4; x <= b.getX() >> 4; x++)
                for (int z = a.getZ() >> 4; z <= b.getZ() >> 4; z++) level.getChunk(x, z);
        }

        /** {@code rows} rows of a block over the vessel and a margin, starting at the first row above the shell. */
        void stack(Block block, int rows) {
            for (int r = 0; r < rows; r++)
                for (int x = -3; x < W + 3; x++)
                    for (int z = -3; z < D + 3; z++)
                        level.setBlock(new BlockPos(min.getX() + x, top + r, min.getZ() + z), block.defaultBlockState(), 2);
        }

        boolean rowStands(int row) {
            return !level.getBlockState(new BlockPos(min.getX() + W / 2, top + row, min.getZ() + D / 2)).isAir();
        }

        void force(boolean on) {
            for (int x = (min.getX() - 64) >> 4; x <= (min.getX() + W + 64) >> 4; x++)
                for (int z = (min.getZ() - 64) >> 4; z <= (min.getZ() + D + 64) >> 4; z++) FixtureChunks.force(level, min, x, z, on);
        }

        void fail(double psig) {
            reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, psig);
            ReactorControllerBlockEntity.serverTick(level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
            reactor.severeEffects().blasts().runToCompletion(level, 200);
        }

        List<ReactorHeadEntity> heads() {
            return level.getEntitiesOfClass(ReactorHeadEntity.class, new AABB(Vec3.atLowerCornerOf(lo), Vec3.atLowerCornerOf(hi)));
        }

        /** The head in the world, which must be exactly one. */
        ReactorHeadEntity head(GameTestHelper h) {
            var heads = heads();
            h.assertTrue(heads.size() == 1, "expected one reactor head, found " + heads.size() + " (broken="
                    + reactor.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD) + ", state=" + reactor.vesselState() + ")");
            return heads.get(0);
        }

        double horizontalFromAxis(ReactorHeadEntity head) {
            return Math.hypot(head.getX() - ax, head.getZ() - az);
        }

        /** Rows of {@code block} that stop the head for a failure at {@code psig}: the row index that holds (so this many rows are enough). */
        int rowsThatHold(Block block, double psig) {
            double ke = HeadMissile.kineticEnergyJ(ReactorBoundaryEffects.headFailureEnergyJ(reactor, psig), psig, OUT_W, OUT_D);
            double capacity = HeadMissileLauncher.footprint(ax, az, OUT_W, OUT_D, 0).size()
                    * HeadMissile.crushJ(ContainmentStrength.contribution(level, min, block.defaultBlockState()));
            var climb = new HeadMissile.Climb(HeadMissile.massKg(OUT_W, OUT_D), ke);
            for (int row = 0; row < HeadMissile.MAX_CLIMB_BLOCKS; row++) {
                if (climb.next(capacity) == HeadMissile.Step.STOPPED) return row + 1;
            }
            return Integer.MAX_VALUE;
        }

        double launchEnergy(double psig) {
            return HeadMissile.kineticEnergyJ(ReactorBoundaryEffects.headFailureEnergyJ(reactor, psig), psig, OUT_W, OUT_D);
        }
    }

    private static Block concrete() {
        return BwrBlocks.SPENT_FUEL_POOL_WALL.get();
    }

    /** Run {@code later} after the chunks around every site have had a moment to become entity-ticking. */
    private static void after(GameTestHelper h, int ticks, Site[] sites, ThrowingRunnable later) {
        h.runAfterDelay(ticks, () -> {
            try {
                later.run();
            } catch (RuntimeException | Error e) {
                for (Site s : sites) s.force(false);
                throw e;
            } catch (Exception e) {
                for (Site s : sites) s.force(false);
                throw new IllegalStateException(e);
            }
        });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void release(Site... sites) {
        for (Site s : sites) s.force(false);
    }

    // ------------------------------------------------------------------
    // 1. The stack decides
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void theSameFailureIsHeldByAThickStackOfConcreteAndNotByAThinOne(GameTestHelper h) throws Exception {
        var thick = new Site(h, new BlockPos(710_000, 190, 700_000));
        var thin = new Site(h, new BlockPos(710_400, 190, 700_000));
        int enough = thick.rowsThatHold(concrete(), 1400.0);
        h.assertTrue(enough >= 3 && enough <= 30, "fixture sanity: " + enough + " rows of concrete hold a 1,400 psig failure");
        int tooFew = enough - 2;
        thick.stack(concrete(), enough);
        thin.stack(concrete(), tooFew);
        BlockPos roofThick = new BlockPos((int) thick.ax, thick.top + enough + 3, (int) thick.az);
        BlockPos roofThin = new BlockPos((int) thin.ax, thin.top + tooFew + 3, (int) thin.az);
        thick.level.setBlock(roofThick, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        thin.level.setBlock(roofThin, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        thick.force(true);
        thin.force(true);
        Site[] sites = {thick, thin};
        after(h, 5, sites, () -> {
            thick.fail(1400.0);
            thin.fail(1400.0);
            var held = thick.head(h);
            var loose = thin.head(h);
            LogUtils.getLogger().info("HEAD MISSILE: {} rows of concrete hold 1,400 psig (launch {} MJ, {} t head); {} rows do not",
                    enough, Math.round(thick.launchEnergy(1400.0) / 1e6), Math.round(held.massKg() / 100.0) / 10.0, tooFew);
            h.assertTrue(!Double.isNaN(held.landY()) || held.isResting(), "the thick stack did not hold the head");
            h.assertTrue(Double.isNaN(loose.landY()) && !loose.isResting(), "the thin stack held the head");
            // The thick stack: every row but the last that is needed was punched out, the stopping row and everything above stand.
            for (int r = 0; r < enough - 1; r++) h.assertTrue(!thick.rowStands(r), "row " + r + " of the held stack was not punched out");
            h.assertTrue(thick.rowStands(enough - 1), "the row that stopped the head was broken");
            h.assertTrue(thick.level.getBlockState(roofThick).is(Blocks.STONE_BRICKS), "the steam cone went through the stack that held the head");
            // The thin stack: all of it is gone, and so is the roof the cone reaches.
            for (int r = 0; r < tooFew; r++) h.assertTrue(!thin.rowStands(r), "row " + r + " of the thin stack stands");
            h.assertTrue(thin.level.getBlockState(roofThin).isAir(), "the cone did not reach the roof above a thin stack");
            h.runAfterDelay(FLIGHT_TICKS, () -> {
                try {
                    h.assertTrue(held.isResting() && !held.isRemoved(), "the held head did not come to rest");
                    h.assertTrue(thick.horizontalFromAxis(held) < 0.5, "the held head left the vessel's axis: " + thick.horizontalFromAxis(held));
                    h.assertTrue(held.getY() < thick.top + 2.0, "the held head is above the vessel: " + (held.getY() - thick.top));
                    h.assertTrue(loose.isResting(), "the loose head did not land and rest");
                    // It may come down on what is left of the margin of the thin stack, but never on the vessel (corner radius plus head radius).
                    h.assertTrue(thin.horizontalFromAxis(loose) > Math.hypot(OUT_W, OUT_D) / 2.0 + OUT_W / 2.0,
                            "the loose head came down on the vessel: " + thin.horizontalFromAxis(loose));
                } finally {
                    release(thick, thin);
                }
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void aLowerFailurePressureIsHeldByAThinnerStack(GameTestHelper h) throws Exception {
        var low = new Site(h, new BlockPos(710_800, 190, 700_000));
        var high = new Site(h, new BlockPos(711_200, 190, 700_000));
        int forLow = low.rowsThatHold(concrete(), 450.0);
        int forHigh = high.rowsThatHold(concrete(), 1400.0);
        h.assertTrue(forLow >= 2 && forLow < forHigh, "fixture sanity: " + forLow + " rows hold 450 psig, " + forHigh + " hold 1,400");
        low.stack(concrete(), forLow);
        high.stack(concrete(), forLow);   // the same thin stack under the higher pressure
        low.force(true);
        high.force(true);
        Site[] sites = {low, high};
        after(h, 5, sites, () -> {
            try {
                low.fail(450.0);
                high.fail(1400.0);
                var heldLow = low.head(h);
                var goneHigh = high.head(h);
                LogUtils.getLogger().info("HEAD MISSILE: {} rows hold a 450 psig failure ({} MJ), the same {} rows do not hold 1,400 psig ({} MJ); 1,400 needs {}",
                        forLow, Math.round(low.launchEnergy(450.0) / 1e6), forLow, Math.round(high.launchEnergy(1400.0) / 1e6), forHigh);
                h.assertTrue(low.launchEnergy(450.0) < high.launchEnergy(1400.0), "a lower pressure did not give a weaker launch");
                h.assertTrue(!Double.isNaN(heldLow.landY()) || heldLow.isResting(), "the thinner stack did not hold the 450 psig failure");
                h.assertTrue(Double.isNaN(goneHigh.landY()) && !goneHigh.isResting(), "the same stack held the 1,400 psig failure");
                h.assertTrue(low.rowStands(forLow - 1), "the row that stopped the weaker head was broken");
            } finally {
                release(low, high);
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void aStrongPlugLodgesTheHeadUnderItAndItStaysThere(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(711_600, 190, 700_000));
        double chosen = 1400.0;
        while (site.rowsThatHold(Blocks.OBSIDIAN, chosen) > 2) chosen *= 0.7;
        final double psig = chosen;
        int rows = site.rowsThatHold(Blocks.OBSIDIAN, psig);
        h.assertTrue(rows >= 1 && rows <= 2 && psig > 100.0, "fixture sanity: " + rows + " rows of obsidian hold the head at " + psig);
        site.stack(Blocks.OBSIDIAN, rows + 1);
        BlockPos roof = new BlockPos((int) site.ax, site.top + rows + 4, (int) site.az);
        site.level.setBlock(roof, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        site.force(true);
        after(h, 5, new Site[] {site}, () -> {
            try {
                site.fail(psig);
                var head = site.head(h);
                h.assertTrue(head.isResting(), "a head stopped within a row of the vessel top did not lodge at once");
                Vec3 where = head.position();
                h.assertTrue(where.y >= site.top - head.height() && where.y < site.top - head.height() + rows + 1.5,
                        "the lodged head is not just under the plug: y = " + where.y);
                h.assertTrue(site.rowStands(rows - 1) && site.rowStands(rows), "the plug was broken");
                h.assertTrue(site.level.getBlockState(roof).is(Blocks.STONE_BRICKS), "the cone blew through the plug");
                h.runAfterDelay(60, () -> {
                    try {
                        h.assertTrue(head.position().equals(where), "the lodged head moved");
                    } finally {
                        release(site);
                    }
                    h.succeed();
                });
            } catch (RuntimeException | Error e) {
                release(site);
                throw e;
            }
        });
    }

    // ------------------------------------------------------------------
    // 1b. The three shield plug concrete tiers
    // ------------------------------------------------------------------

    private static final double TIER_RATING_TOLERANCE_PSI = 0.2;

    /**
     * One tier, the same 1,400 psig failure twice: held by as many rows of the tier as it takes, and not held by one row
     * fewer of it (or, if one row holds it, by one row of the next weaker tier).
     */
    private static void tierHolds(GameTestHelper h, Block tier, double resistance, Block weaker, BlockPos heldAt, BlockPos looseAt) throws Exception {
        tierHolds(h, tier, resistance, weaker, heldAt, looseAt, H);
    }

    private static void tierHolds(GameTestHelper h, Block tier, double resistance, Block weaker, BlockPos heldAt, BlockPos looseAt, int height) throws Exception {
        var held = new Site(h, heldAt, height);
        var loose = new Site(h, looseAt, height);
        double rating = 5.8 * Math.sqrt(resistance);
        h.assertTrue(Math.abs(ContainmentStrength.contribution(held.level, held.min, tier.defaultBlockState()) - rating) < TIER_RATING_TOLERANCE_PSI,
                "the tier is not rated 5.8 * sqrt(" + resistance + ") = " + rating + " psi: "
                        + ContainmentStrength.contribution(held.level, held.min, tier.defaultBlockState()));
        int rows = held.rowsThatHold(tier, 1400.0);
        h.assertTrue(rows >= 1 && rows <= 3, "fixture sanity: " + rows + " rows of the tier hold a 1,400 psig failure");
        held.stack(tier, rows);
        Block looseBlock = rows >= 2 ? tier : weaker;
        int looseRows = rows >= 2 ? rows - 1 : 1;
        loose.stack(looseBlock, looseRows);
        BlockPos roofHeld = new BlockPos((int) held.ax, held.top + rows + 3, (int) held.az);
        held.level.setBlock(roofHeld, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        held.force(true);
        loose.force(true);
        Site[] sites = {held, loose};
        after(h, 5, sites, () -> {
            try {
                held.fail(1400.0);
                loose.fail(1400.0);
                var stopped = held.head(h);
                var thrown = loose.head(h);
                LogUtils.getLogger().info("HEAD MISSILE: {} rows of {} hold the 1,400 psig failure ({} MJ); {} row(s) of {} do not",
                        rows, tier.getDescriptionId(), Math.round(held.launchEnergy(1400.0) / 1e6), looseRows, looseBlock.getDescriptionId());
                h.assertTrue(!Double.isNaN(stopped.landY()) || stopped.isResting(), rows + " rows of the tier did not hold the head");
                h.assertTrue(Double.isNaN(thrown.landY()) && !thrown.isResting(), looseRows + " row(s) of " + looseBlock.getDescriptionId() + " held the head");
                h.assertTrue(held.rowStands(rows - 1), "the row that stopped the head was broken");
                h.assertTrue(held.level.getBlockState(roofHeld).is(Blocks.STONE_BRICKS), "the cone went through the tier that held the head");
            } finally {
                release(held, loose);
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void shieldPlugConcreteHoldsTheHeadWhereLinedConcreteDoesNot(GameTestHelper h) throws Exception {
        tierHolds(h, BwrBlocks.SHIELD_PLUG_CONCRETE.get(), 1800.0, concrete(), new BlockPos(714_400, 190, 700_000), new BlockPos(714_800, 190, 700_000));
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void heavilyReinforcedShieldPlugConcreteHoldsTheHead(GameTestHelper h) throws Exception {
        tierHolds(h, BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get(), 3600.0, BwrBlocks.SHIELD_PLUG_CONCRETE.get(),
                new BlockPos(715_200, 190, 700_000), new BlockPos(715_600, 190, 700_000));
    }

    /**
     * The steam space of the fixture follows its build now, so the 9 x 9 x 12 fixture's head carries less than it did (180 MJ at
     * 1,400 psig, not 250) and the heavily reinforced tier alone holds it. The ultra tier's test needs a failure that still
     * separates it from the tier below: a taller vessel, with a bigger dome and so a bigger failure.
     */
    private static final int ULTRA_FIXTURE_HEIGHT = 16;

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void ultraReinforcedShieldPlugConcreteHoldsTheHeadInOneRow(GameTestHelper h) throws Exception {
        tierHolds(h, BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get(), 7200.0, BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get(),
                new BlockPos(716_000, 190, 700_000), new BlockPos(716_400, 190, 700_000), ULTRA_FIXTURE_HEIGHT);
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 200)
    public static void eachTierIsStrongerThanTheOneBelowAndThanObsidianAndNeedsNoMoreRows(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(716_800, 190, 700_000));
        Block[] ladder = {BwrBlocks.SPENT_FUEL_POOL_WALL.get(), Blocks.OBSIDIAN, BwrBlocks.SHIELD_PLUG_CONCRETE.get(),
                BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get(), BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get()};
        double previousRating = 0.0;
        int previousRows = Integer.MAX_VALUE;
        for (Block block : ladder) {
            double rating = ContainmentStrength.contribution(site.level, site.min, block.defaultBlockState());
            int rows = site.rowsThatHold(block, 1400.0);
            LogUtils.getLogger().info("HEAD MISSILE: {} rates {} psi, {} rows hold a 1,400 psig failure", block.getDescriptionId(),
                    Math.round(rating * 10.0) / 10.0, rows);
            h.assertTrue(rating > previousRating, block.getDescriptionId() + " is not stronger than the block before it");
            h.assertTrue(rows <= previousRows, block.getDescriptionId() + " needs more rows than the weaker block before it");
            previousRating = rating;
            previousRows = rows;
        }
        double obsidian = ContainmentStrength.contribution(site.level, site.min, Blocks.OBSIDIAN.defaultBlockState());
        h.assertTrue(ContainmentStrength.contribution(site.level, site.min, BwrBlocks.SHIELD_PLUG_CONCRETE.get().defaultBlockState()) > obsidian,
                "the first tier is not stronger than obsidian (and so than a block of blast resistance 1,200)");
        h.succeed();
    }

    // ------------------------------------------------------------------
    // 2. Landing
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void aHeadThatGetsThroughFliesLandsDigsABoundedCraterAndSettles(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(712_000, 190, 700_000));
        site.force(true);
        int stoneBefore = stone(site);
        after(h, 5, new Site[] {site}, () -> {
            site.fail(1400.0);
            var head = site.head(h);
            h.assertTrue(!head.isResting() && Double.isNaN(head.landY()), "an open sky held the head");
            h.assertTrue(head.getDeltaMovement().y > 0.5, "the head was not thrown upward: " + head.getDeltaMovement());
            double apex = head.getY();
            double[] top = {apex};
            h.runAfterDelay(30, () -> top[0] = Math.max(top[0], head.getY()));
            h.runAfterDelay(FLIGHT_TICKS, () -> {
                try {
                    h.assertTrue(head.isResting(), "the head did not land and rest");
                    h.assertTrue(top[0] > site.top + 15 && top[0] <= site.top + HeadMissile.MAX_CLIMB_BLOCKS + 1.0,
                            "the flight did not rise within the cap: " + (top[0] - site.top));
                    double away = site.horizontalFromAxis(head);
                    h.assertTrue(away >= HeadMissile.driftDistanceBlocks(OUT_W, OUT_D, 0.0) - 1.0 && away <= 45.0, "landed " + away + " blocks from the axis");
                    int removed = stoneBefore - stone(site);
                    h.assertTrue(removed >= 1 && removed <= HeadMissile.MAX_CRATER_BLOCKS, "crater of " + removed + " blocks");
                    h.assertTrue(head.getY() >= site.min.getY() - 13 && head.getY() <= site.min.getY() - 8 + 1.5,
                            "the head did not rest on the floor: y = " + head.getY());
                    Vec3 where = head.position();
                    LogUtils.getLogger().info("HEAD MISSILE: thrown to {} blocks, landed {} blocks away, crater {} blocks, rests tilted {}/{} degrees",
                            Math.round(top[0] - site.top), Math.round(away), removed, Math.round(head.tiltXDegrees()), Math.round(head.tiltZDegrees()));
                    h.runAfterDelay(40, () -> {
                        try {
                            h.assertTrue(head.position().equals(where) && head.isResting(), "the resting head moved");
                        } finally {
                            release(site);
                        }
                        h.succeed();
                    });
                } catch (RuntimeException | Error e) {
                    release(site);
                    throw e;
                }
            });
        });
    }

    private static int stone(Site s) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(s.min.offset(-44, -13, -44), s.min.offset(W + 44, -9, D + 44)))
            if (s.level.getBlockState(p).is(Blocks.STONE)) n++;
        return n;
    }

    // ------------------------------------------------------------------
    // 3. Save and reload
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 900)
    public static void aFlyingAndARestingHeadSurviveASaveAndReload(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(712_400, 190, 700_000));
        site.force(true);
        after(h, 5, new Site[] {site}, () -> {
            site.fail(1400.0);
            var flying = site.head(h);
            h.runAfterDelay(25, () -> {
                try {
                    h.assertTrue(!flying.isResting() && flying.getY() > site.top, "fixture: the head should be in the air");
                    var tag = new CompoundTag();
                    h.assertTrue(flying.save(tag), "a flying head did not save");
                    var copy = (ReactorHeadEntity) EntityType.create(tag, site.level).orElseThrow();
                    copy.setUUID(java.util.UUID.randomUUID()); // the original is not gone yet; a real reload has no twin
                    h.assertTrue(copy.position().equals(flying.position()), "position changed over a save");
                    h.assertTrue(copy.getDeltaMovement().equals(flying.getDeltaMovement()), "velocity changed over a save");
                    h.assertTrue(copy.massKg() == flying.massKg() && copy.width() == flying.width() && copy.depth() == flying.depth()
                            && copy.height() == flying.height(), "size or mass changed over a save");
                    h.assertTrue(copy.driftX() == flying.driftX() && copy.driftZ() == flying.driftZ()
                            && copy.clearBaseY() == flying.clearBaseY(), "drift changed over a save");
                    h.assertTrue(!copy.isResting(), "a flying head reloaded at rest");
                    flying.discard();
                    h.assertTrue(site.level.addFreshEntity(copy), "the reloaded head was not added");
                    h.runAfterDelay(FLIGHT_TICKS, () -> {
                        try {
                            h.assertTrue(copy.isResting(), "the reloaded head did not resume its flight and land");
                            h.assertTrue(site.horizontalFromAxis(copy) >= HeadMissile.driftDistanceBlocks(OUT_W, OUT_D, 0.0) - 1.0,
                                    "the reloaded head came down in the wrong place: " + site.horizontalFromAxis(copy));
                            var restTag = new CompoundTag();
                            h.assertTrue(copy.save(restTag), "a resting head did not save");
                            var rested = (ReactorHeadEntity) EntityType.create(restTag, site.level).orElseThrow();
                            rested.setUUID(java.util.UUID.randomUUID());
                            h.assertTrue(rested.isResting() && rested.position().equals(copy.position())
                                    && rested.tiltXDegrees() == copy.tiltXDegrees() && rested.tiltZDegrees() == copy.tiltZDegrees()
                                    && rested.getDeltaMovement().equals(copy.getDeltaMovement()), "a resting head changed over a save");
                            Vec3 where = copy.position();
                            copy.discard();
                            h.assertTrue(site.level.addFreshEntity(rested), "the reloaded resting head was not added");
                            // 41 ticks, not 40: a panel sampler test further down this run only passes on one parity of the server tick
                            // at which its batch starts, and this is the longest test before it (see CHANGELOG).
                            h.runAfterDelay(41, () -> {
                                try {
                                    h.assertTrue(rested.position().equals(where) && rested.isResting(), "a reloaded resting head moved");
                                } finally {
                                    release(site);
                                }
                                h.succeed();
                            });
                        } catch (RuntimeException | Error e) {
                            release(site);
                            throw e;
                        }
                    });
                } catch (RuntimeException | Error e) {
                    release(site);
                    throw e;
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 4. Once, and nothing in an old save
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 600)
    public static void aHeadFailureThrowsOneHeadAndAReloadThrowsNone(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(712_800, 190, 700_000));
        site.force(true);
        after(h, 5, new Site[] {site}, () -> {
            try {
                site.fail(1400.0);
                h.assertTrue(site.heads().size() == 1, "the failure threw " + site.heads().size() + " heads");
                var root = site.reactor.getBlockPos();
                var saved = site.reactor.saveWithFullMetadata(site.level.registryAccess());
                site.level.removeBlockEntity(root);
                var reloaded = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, site.level.getBlockState(root), saved, site.level.registryAccess());
                site.level.setBlockEntity(reloaded);
                SpargerRegressionTests.validate(reloaded);
                for (int i = 0; i < 10; i++) ReactorControllerBlockEntity.serverTick(site.level, root, site.level.getBlockState(root), reloaded);
                h.assertTrue(reloaded.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD), "reload healed the head");
                h.assertTrue(site.heads().size() == 1, "a reload threw another head: " + site.heads().size());
            } finally {
                release(site);
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 600)
    public static void anOldSaveWithAFailedHeadSpawnsNothing(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(713_200, 190, 700_000));
        BlockPos above = new BlockPos((int) site.ax, site.top + 3, (int) site.az);
        site.level.setBlock(above, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        site.force(true);
        after(h, 5, new Site[] {site}, () -> {
            try {
                // A plant saved by the version before the missile: the head is failed and the world has been told.
                site.reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400.0);
                var old = site.reactor.saveWithFullMetadata(site.level.registryAccess());
                old.putInt("AppliedBoundaryFailures", old.getInt("AppliedBoundaryFailures") | ReactorBoundaryEffects.HEAD);
                var root = site.reactor.getBlockPos();
                site.level.removeBlockEntity(root);
                var loaded = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, site.level.getBlockState(root), old, site.level.registryAccess());
                site.level.setBlockEntity(loaded);
                SpargerRegressionTests.validate(loaded);
                h.assertTrue(loaded.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD), "the old save's head is not failed");
                for (int i = 0; i < 10; i++) ReactorControllerBlockEntity.serverTick(site.level, root, site.level.getBlockState(root), loaded);
                loaded.severeEffects().blasts().runToCompletion(site.level, 50);
                h.assertTrue(site.heads().isEmpty(), "an old save with a failed head threw a missile");
                h.assertTrue(site.level.getBlockState(above).is(Blocks.STONE_BRICKS), "an old save with a failed head blasted the world again");
                h.assertTrue((loaded.getUpdateTag(site.level.registryAccess()).getInt("VisualBoundaryMask") & ReactorBoundaryEffects.HEAD) != 0,
                        "the damaged head model is no longer flagged");
            } finally {
                release(site);
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------
    // 5. Protection
    // ------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 600)
    public static void aCancelledBreakEventStopsTheHeadAgainstTheBlockItProtects(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(713_600, 190, 700_000));
        site.stack(Blocks.OAK_PLANKS, 2);
        BlockPos protectedBlock = new BlockPos((int) site.ax + 1, site.top, (int) site.az);
        BlockPos roof = new BlockPos((int) site.ax, site.top + 6, (int) site.az);
        site.level.setBlock(roof, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        site.force(true);
        java.util.function.Consumer<BlockEvent.BreakEvent> claim = event -> {
            if (event.getLevel() == site.level && event.getPos().equals(protectedBlock)) event.setCanceled(true);
        };
        after(h, 5, new Site[] {site}, () -> {
            NeoForge.EVENT_BUS.addListener(claim);
            try {
                site.fail(1400.0);
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            try {
                var head = site.head(h);
                h.assertTrue(site.level.getBlockState(protectedBlock).is(Blocks.OAK_PLANKS), "a block whose break event was cancelled still broke");
                h.assertTrue(head.isResting() || !Double.isNaN(head.landY()), "the head went through a block a protection mod protects");
                h.assertTrue(site.level.getBlockState(roof).is(Blocks.STONE_BRICKS), "the cone went through the block that held the head");
            } finally {
                release(site);
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrblast", batch = "headmissile", timeoutTicks = 600)
    public static void theBlastsNeverBreakAndTheHeadIsStoppedByBedrock(GameTestHelper h) throws Exception {
        var site = new Site(h, new BlockPos(714_000, 190, 700_000));
        BlockPos bedrock = new BlockPos((int) site.ax, site.top + 2, (int) site.az);
        site.level.setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), 2);
        site.force(true);
        after(h, 5, new Site[] {site}, () -> {
            try {
                site.fail(1400.0);
                var head = site.head(h);
                h.assertTrue(site.level.getBlockState(bedrock).is(Blocks.BEDROCK), "bedrock broke");
                h.assertTrue(head.isResting() || !Double.isNaN(head.landY()), "the head flew through bedrock");
            } finally {
                release(site);
            }
            h.succeed();
        });
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_ACCIDENT_BLAST_LINKED = AccidentBlast.class;
}
