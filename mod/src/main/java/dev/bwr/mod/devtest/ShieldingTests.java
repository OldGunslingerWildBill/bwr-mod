package dev.bwr.mod.devtest;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.radiation.Shielding;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.isotope.CarriedIsotopes;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.radiation.BlockShielding;
import dev.bwr.mod.radiation.ImmersionDose;
import dev.bwr.mod.radiation.RadiationField;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Block radiation shielding (RADIATION.md, "Shielding"): a lead wall, a concrete bunker, glass and air between a source and the meter;
 * the materials as data; formed structures and the pool's water not counted twice; an unloaded chunk on the line counts as no shielding
 * and is never loaded; the cache follows block changes; the cost in a dense pool. Namespace {@code bwrshield}, batch {@code shielding}.
 * Every fixture is at its own absolute position from {@link #X0} and forces its own chunks.
 */
@GameTestHolder("bwrshield")
@PrefixGameTestTemplate(false)
public final class ShieldingTests {
    private ShieldingTests() {
    }

    private static final String NS = "bwrshield";
    static final int X0 = 452_000;
    static final int Z0 = 452_000;
    static final int Y = 200;

    private static BlockPos origin(int index) {
        return new BlockPos(X0 + 600 * index, Y, Z0); // 600 m apart: a pool of one fixture is out of the others' 256 m
    }

    /** Forces and loads the chunks of a box around {@code centre} for {@code owner}; returns the releaser. */
    private static Runnable force(ServerLevel level, Object owner, BlockPos lo, BlockPos hi) {
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) {
            for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
                FixtureChunks.force(level, owner, cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return () -> {
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                if (!level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) {
                for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
                    FixtureChunks.force(level, owner, cx, cz, false);
                }
            }
        };
    }

    private static boolean near(double got, double expected, double rel) {
        return expected == 0.0 ? got == 0.0 : Math.abs(got / expected - 1.0) <= rel;
    }

    /** The optical depth the core march gives a ray through the cells {@code solid} picks, each with attenuation {@code mu} per metre. */
    private static double depth(Vec3 a, Vec3 b, Predicate<BlockPos> solid, double mu) {
        double[] d = {0.0};
        Shielding.march(a.x, a.y, a.z, b.x, b.y, b.z, (x, y, z, l) -> {
            if (solid.test(new BlockPos(x, y, z))) {
                d[0] += mu * l;
            }
            return true;
        });
        return d[0];
    }

    private static double mu(double hvlCm) {
        return Shielding.attenuationPerM(hvlCm);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Materials
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 100)
    public static void theMaterialsAreDataWithTheCitedHalfValueLayers(GameTestHelper h) {
        Object[][] table = {
                {BwrBlocks.LEAD_BLOCK.get(), Shielding.LEAD_HVL_CM},
                {Blocks.IRON_BLOCK, Shielding.STEEL_HVL_CM},
                {Blocks.GOLD_BLOCK, Shielding.STEEL_HVL_CM},
                {Blocks.WHITE_CONCRETE, Shielding.CONCRETE_HVL_CM},
                {Blocks.GRAY_CONCRETE, Shielding.CONCRETE_HVL_CM},
                {Blocks.STONE, Shielding.CONCRETE_HVL_CM},
                {Blocks.DEEPSLATE, Shielding.CONCRETE_HVL_CM},
                {Blocks.COBBLESTONE, Shielding.CONCRETE_HVL_CM},
                {Blocks.IRON_ORE, Shielding.CONCRETE_HVL_CM},
                {Blocks.DEEPSLATE_DIAMOND_ORE, Shielding.CONCRETE_HVL_CM},
                {BwrBlocks.SHIELD_PLUG_CONCRETE.get(), Shielding.SHIELD_PLUG_HVL_CM},
                {BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get(), Shielding.HEAVY_SHIELD_PLUG_HVL_CM},
                {BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get(), Shielding.ULTRA_SHIELD_PLUG_HVL_CM},
                {Blocks.DIRT, Shielding.EARTH_HVL_CM},
                {Blocks.SAND, Shielding.EARTH_HVL_CM},
                {Blocks.OAK_PLANKS, Shielding.WOOD_HVL_CM},
                {Blocks.SPRUCE_LOG, Shielding.WOOD_HVL_CM},
                {Blocks.GLASS, Shielding.GLASS_HVL_CM / Shielding.GLASS_FILL},
                {Blocks.GLASS_PANE, Shielding.GLASS_HVL_CM / Shielding.GLASS_FILL},
                {Blocks.TINTED_GLASS, Shielding.GLASS_HVL_CM / Shielding.GLASS_FILL},
        };
        for (Object[] row : table) {
            Block block = (Block) row[0];
            double got = BlockShielding.effectiveHvlCm(block.defaultBlockState());
            h.assertTrue(near(got, (Double) row[1], 1e-12), block + " has a half-value layer of " + got + " cm, not " + row[1]);
        }
        h.assertTrue(BlockShielding.effectiveHvlCm(Blocks.OBSIDIAN.defaultBlockState(), new BlockShielding.Material(1.0, java.util.Optional.empty())) == 1.0,
                "fixture: a data map entry does not set a block's half-value layer");
        // a slab is half a block of its material; air, a torch, flowers and water have no solid shielding
        double slab = BlockShielding.effectiveHvlCm(Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        h.assertTrue(near(slab, 2.0 * Shielding.CONCRETE_HVL_CM, 1e-12), "a stone slab is half a block of concrete: " + slab);
        for (BlockState none : new BlockState[] {Blocks.AIR.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.POPPY.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState()}) {
            h.assertTrue(BlockShielding.effectiveHvlCm(none) == Double.POSITIVE_INFINITY, none + " shields as a solid");
        }
        // the formed structures' blocks never count, whatever the data says
        for (Block exempt : new Block[] {BwrBlocks.SPENT_FUEL_POOL_WALL.get(), BwrBlocks.SPENT_FUEL_RACK.get(), BwrBlocks.REACTOR_VESSEL.get(),
                BwrBlocks.RTG.get(), BwrBlocks.LARGE_RTG.get()}) {
            h.assertTrue(exempt.defaultBlockState().is(BlockShielding.EXEMPT), exempt + " is not in bwr:radiation_shielding_exempt");
            h.assertTrue(BlockShielding.effectiveHvlCm(exempt.defaultBlockState()) == Double.POSITIVE_INFINITY, exempt + " counts as shielding");
            // even when a pack's data map gives it an entry
            h.assertTrue(BlockShielding.effectiveHvlCm(exempt.defaultBlockState(), new BlockShielding.Material(1.0, java.util.Optional.empty()))
                    == Double.POSITIVE_INFINITY, exempt + " counts as shielding when the data map gives it an entry");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Walls
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 100)
    public static void aLeadWallBetweenAnRtgAndTheMeterCutsTheReadingByTheExpectedFactor(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = origin(0);
        Runnable release = force(l, o, o.offset(-4, -2, -6), o.offset(10, 4, 6));
        try {
            l.setBlock(o, BwrBlocks.RTG.get().defaultBlockState(), 3);
            RtgBlockEntity rtg = (RtgBlockEntity) l.getBlockEntity(o);
            rtg.fuel().setStackInSlot(0, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
            Vec3 source = Vec3.atCenterOf(o);
            Vec3 meter = source.add(4.0, 0.0, 0.0);
            double bare = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(bare > 0.0 && near(bare, DoseModel.pointSourceMsvPerH(1.0, 4.0, 1.0)
                    * rtg.readout().doseAtOneMetreMsvPerH() / DoseModel.pointSourceMsvPerH(1.0, 1.0, 1.0), 1e-9), "the bare RTG reads " + bare);
            h.assertTrue(near(RadiationField.doseRateMsvPerHour(l, meter), bare, 1e-12), "the meter does not read the source");

            // one block of lead straight across the line: 100 cm through a 1 cm half-value layer
            BlockPos lead = o.offset(2, 0, 0);
            l.setBlock(lead, BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            double shielded = RadiationSources.doseRateMsvPerHour(l, meter);
            double factor = Math.pow(2.0, -100.0 / Shielding.LEAD_HVL_CM);
            h.assertTrue(near(shielded, bare * factor, 1e-9), "one lead block lets through " + shielded / bare + ", not 2^-100 = " + factor);
            h.assertTrue(near(RadiationField.doseRateMsvPerHour(l, meter), shielded, 1e-12), "the meter reads the unshielded value");
            var path = BlockShielding.trace(l, source, meter, false, null);
            h.assertTrue(near(path.leadEquivalentCm(), 100.0, 1e-9), "a block of lead is " + path.leadEquivalentCm() + " cm lead-equivalent");

            // a wall three wide, crossed at a slant: the path is the true length through it, not a block count
            for (int z = -1; z <= 1; z++) {
                l.setBlock(o.offset(2, 0, z), BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            }
            Vec3 slant = source.add(4.0, 0.0, 1.5);
            double bareSlant = BlockShielding.pointSource(l, source, slant, 1.0, 1.0, false, null);
            double unshieldedSlant = DoseModel.pointSourceMsvPerH(1.0, source.distanceTo(slant), 1.0);
            double expectedDepth = depth(source, slant, p -> p.getX() == o.getX() + 2 && p.getY() == o.getY() && Math.abs(p.getZ() - o.getZ()) <= 1,
                    mu(Shielding.LEAD_HVL_CM));
            h.assertTrue(expectedDepth > mu(Shielding.LEAD_HVL_CM) * 1.0, "fixture: the slanted path is longer than a block");
            h.assertTrue(near(bareSlant, unshieldedSlant * Math.exp(-expectedDepth), 1e-9), "slanted through the wall: " + bareSlant / unshieldedSlant
                    + " against " + Math.exp(-expectedDepth) + " (path " + expectedDepth / mu(Shielding.LEAD_HVL_CM) + " m)");

            // a carried caesium-137 capsule behind the same kind of wall
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            player.getInventory().add(new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
            Vec3 carrier = player.position().add(0, 1, 0);
            Vec3 at = carrier.add(4.0, 0.0, 0.0);
            double carried = CarriedIsotopes.doseRateOf(player, at);
            BlockPos wall = BlockPos.containing(carrier.add(2.0, 0.0, 0.0));
            BlockState before = l.getBlockState(wall);
            l.setBlock(wall, BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            double carriedShielded = CarriedIsotopes.doseRateOf(player, at);
            l.setBlock(wall, before, 3);
            h.assertTrue(carried > 1.0 && near(carriedShielded, carried * factor, 1e-9), "a capsule behind lead: " + carriedShielded + " of " + carried);
            System.out.println("SHIELDING LEAD: RTG (Cs-137) at 4 m " + DoseModel.formatRate(bare) + " bare, " + shielded + " mSv/h behind one lead block (x "
                    + factor + "); slanted path " + expectedDepth / mu(Shielding.LEAD_HVL_CM) + " m");
            rtg.fuel().setStackInSlot(0, ItemStack.EMPTY);
        } finally {
            release.run();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 100)
    public static void aConcreteBunkerCutsTheReadingAndGlassBarelyMattersAndAirNothing(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = origin(1);
        Object owner = new Object();
        Runnable release = force(l, o, o.offset(-4, -4, -4), o.offset(8, 4, 4));
        try {
            Vec3 source = Vec3.atCenterOf(o);
            Vec3 meter = source.add(5.0, 0.0, 0.0);
            RadiationSources.emit(l, owner, source, 1000.0, 1.0);
            double bare = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(bare, DoseModel.pointSourceMsvPerH(1000.0, 5.0, 1.0), 1e-12), "air does nothing: " + bare);
            // non-solid blocks on the line do nothing either
            l.setBlock(o.offset(2, 0, 0), Blocks.TORCH.defaultBlockState(), 3);
            l.setBlock(o.offset(3, 0, 0), Blocks.SHORT_GRASS.defaultBlockState(), 3);
            h.assertTrue(RadiationSources.doseRateMsvPerHour(l, meter) == bare, "a torch and grass shield the source");
            l.setBlock(o.offset(2, 0, 0), Blocks.AIR.defaultBlockState(), 3);
            l.setBlock(o.offset(3, 0, 0), Blocks.AIR.defaultBlockState(), 3);

            // glass: a 1 cm panel per block
            l.setBlock(o.offset(3, 0, 0), Blocks.GLASS.defaultBlockState(), 3);
            double glass = RadiationSources.doseRateMsvPerHour(l, meter) / bare;
            h.assertTrue(near(glass, Math.pow(2.0, -100.0 * Shielding.GLASS_FILL / Shielding.GLASS_HVL_CM), 1e-9) && glass > 0.85,
                    "a glass block lets through " + glass);
            l.setBlock(o.offset(3, 0, 0), Blocks.AIR.defaultBlockState(), 3);

            // a closed bunker of one-block walls around the source, by material: the shield plug tiers shield progressively more
            Block[] walls = {Blocks.GRAY_CONCRETE, Blocks.STONE, BwrBlocks.SHIELD_PLUG_CONCRETE.get(), BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get(),
                    BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get(), Blocks.IRON_BLOCK};
            double[] hvl = {Shielding.CONCRETE_HVL_CM, Shielding.CONCRETE_HVL_CM, Shielding.SHIELD_PLUG_HVL_CM, Shielding.HEAVY_SHIELD_PLUG_HVL_CM,
                    Shielding.ULTRA_SHIELD_PLUG_HVL_CM, Shielding.STEEL_HVL_CM};
            double last = 1.0;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < walls.length; i++) {
                for (BlockPos p : BlockPos.betweenClosed(o.offset(-1, -1, -1), o.offset(1, 1, 1))) {
                    if (!p.equals(o)) {
                        l.setBlock(p, walls[i].defaultBlockState(), 3);
                    }
                }
                double t = RadiationSources.doseRateMsvPerHour(l, meter) / bare;
                double expected = Math.pow(2.0, -100.0 / hvl[i]);
                h.assertTrue(near(t, expected, 1e-9), walls[i] + " bunker lets through " + t + ", not " + expected);
                if (i >= 2 && i <= 4) {
                    h.assertTrue(t < last, "shield plug tier " + (i - 1) + " is no stronger than the one before: " + t + " vs " + last);
                }
                last = t;
                line.append(walls[i].getDescriptionId()).append(' ').append(t).append("; ");
            }
            System.out.println("SHIELDING BUNKER (one-block walls): " + line);

            // waterlogged: the slab's stone and the water in the other half, the water only for a source that does not hold its own
            for (BlockPos p : BlockPos.betweenClosed(o.offset(-1, -1, -1), o.offset(1, 1, 1))) {
                if (!p.equals(o)) {
                    l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            l.setBlock(o.offset(3, 0, 0), Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 3);
            double logged = BlockShielding.trace(l, source, meter, false, null).opticalDepth();
            double loggedPool = BlockShielding.trace(l, source, meter, true, null).opticalDepth();
            h.assertTrue(near(logged, 0.5 * mu(Shielding.CONCRETE_HVL_CM) + 0.5 * DoseModel.WATER_ATTENUATION_PER_M, 1e-9),
                    "a waterlogged slab is half concrete and half water: " + logged);
            h.assertTrue(near(loggedPool, 0.5 * mu(Shielding.CONCRETE_HVL_CM), 1e-9), "for a source that holds its own water the water is not counted: " + loggedPool);
            // a water block is the dose model's water, exactly (placed without updates so it does not flow; read uncached)
            l.setBlock(o.offset(3, 0, 0), Blocks.WATER.defaultBlockState(), 2);
            double water = BlockShielding.trace(l, source, meter, false, null).transmission();
            h.assertTrue(near(water, DoseModel.waterTransmission(1.0), 1e-9), "a water block lets through " + water + ", the dose model's metre of water " + DoseModel.waterTransmission(1.0));
        } finally {
            RadiationSources.clear(l, owner);
            release.run();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // No double counting
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 300)
    public static void formedStructuresAndThePoolsWaterAreNotCountedTwice(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = origin(2);
        Object owner = new Object();
        Runnable release = force(l, o, o.offset(-4, -4, -4), o.offset(8, 4, 4));
        SpentFuelPoolFixture f = null;
        try {
            // structure blocks on the line: nothing
            Vec3 source = Vec3.atCenterOf(o);
            Vec3 meter = source.add(5.0, 0.0, 0.0);
            RadiationSources.emit(l, owner, source, 1000.0, 1.0);
            double bare = RadiationSources.doseRateMsvPerHour(l, meter);
            l.setBlock(o.offset(1, 0, 0), BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            l.setBlock(o.offset(2, 0, 0), BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(), 2);
            l.setBlock(o.offset(3, 0, 0), BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState(), 2);
            h.assertTrue(BlockShielding.trace(l, source, meter, false, null).opticalDepth() == 0.0, "formed-structure blocks counted as shielding");
            for (int x = 1; x <= 3; x++) {
                l.setBlock(o.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 2);
            }
            // a source's own box (the Large RTG's footprint) is its housing: lead inside it is not counted, lead outside it is
            l.setBlock(o.offset(1, 0, 0), BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            BlockShielding.Box own = BlockShielding.Box.of(o.offset(-1, -1, -1), o.offset(1, 1, 1));
            RadiationSources.emit(l, owner, source, 1000.0, 1.0, false, own);
            h.assertTrue(RadiationSources.doseRateMsvPerHour(l, meter) == bare, "a block inside the source's own box was counted");
            RadiationSources.emit(l, owner, source, 1000.0, 1.0);
            double withoutBox = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(withoutBox, bare * Math.pow(2.0, -100.0), 1e-9), "without the box the lead counts: " + withoutBox / bare);
            RadiationSources.clear(l, owner);
            l.setBlock(o.offset(1, 0, 0), Blocks.AIR.defaultBlockState(), 3);

            // the spent fuel pool: four fresh assemblies under 3 m of water, the pool filled with real water blocks
            f = SpentFuelPoolFixture.build(l, o.offset(0, -2, 20), 9, 9, 8, 1);
            f.be.pool().restore(7.0 * 49_000.0, 25.0);
            long now = l.getGameTime();
            for (int c = 0; c < 4; c++) {
                f.be.insertAt(f.racks.get(0), c, SpentFuelPoolFixture.assembly(45000.0, 0.0, now));
            }
            f.be.simulateSeconds(1, now);
            double surface = f.be.surfaceY();
            double rackTop = f.racks.get(0).getY() + 4;
            double rx = f.racks.get(0).getX() + 0.5, rz = f.racks.get(0).getZ() + 0.5;
            for (int x = 1; x < 8; x++) {
                for (int z = 1; z < 8; z++) {
                    for (int y = 1; f.min.getY() + y < surface; y++) {
                        BlockPos p = f.min.offset(x, y, z);
                        if (l.getBlockState(p).isAir()) {
                            l.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
                        }
                    }
                }
            }
            SpentFuelPoolFixture pool = f;
            // the old figures, from the visitor: above the water the water over the rack top; in it the water on the line
            java.util.function.Function<Vec3, Double> old = at -> {
                boolean swimming = pool.be.inWater(at.x, at.y, at.z);
                double[] e = {0.0};
                pool.be.forEachRackSource((x, topY, z, decayW, cover) -> {
                    double d = at.distanceTo(new Vec3(x, topY, z));
                    double w = swimming ? DoseModel.waterPathM(d, topY, at.y, surface, Double.NEGATIVE_INFINITY) : cover;
                    e[0] += DoseModel.pointSourceMsvPerH(BridgeMotion.gammaWatts(decayW), d, BridgeMotion.transmission(w));
                });
                return e[0];
            };
            clearWall(l, f, rackTop, rz); // a wall left by an interrupted earlier run
            Vec3 beside = new Vec3(f.min.getX() - 3.0, rackTop + 0.5, rz);
            Vec3 deck = new Vec3(rx + 2.0, surface + 1.5, rz - 2.0);
            Vec3 diver = new Vec3(rx, rackTop + 1.0, rz);
            Vec3 swimmer = new Vec3(rx, surface - 0.5, rz);
            // lead in the pool, inside its walls, on the line from the rack to the deck: inside the pool's own box, so not counted
            List<BlockPos> line = new ArrayList<>();
            Shielding.march(rx, rackTop, rz, deck.x, deck.y, deck.z, (x, y, z, len) -> {
                line.add(new BlockPos(x, y, z));
                return line.size() < 3;
            });
            BlockPos inPool = line.get(2);
            h.assertTrue(!l.getBlockState(inPool).is(BwrBlocks.SPENT_FUEL_RACK.get()) && inPool.getY() < surface, "fixture: the in-pool lead cell " + inPool);
            l.setBlock(inPool, BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            for (Vec3 at : new Vec3[] {beside, deck, diver, swimmer}) {
                double got = RadiationSources.doseRateMsvPerHour(l, at);
                double expected = old.apply(at);
                h.assertTrue(got > 0.0 && Math.abs(got - expected) <= 1e-12 * expected, "pool reading at " + at + " is " + got + ", the old figure " + expected);
            }
            // the immersion sampler reads the same, water blocks and all
            FakePlayer p = NewFuelElevatorTests.player(l, "shield-dive", diver);
            p.setPos(diver.x, diver.y - p.getBbHeight() * 0.5, diver.z);
            h.assertTrue(ImmersionDose.immersed(p), "the diver is in the water");
            double sampled = ImmersionDose.sample(p);
            h.assertTrue(near(sampled, old.apply(diver), 1e-9), "the immersion dose is " + sampled + ", not " + old.apply(diver));
            p.removeAllEffects();
            // a wall outside the pool, between the rack and a point beside it, does shield
            int wx = wallX(f);
            Predicate<BlockPos> inWall = q -> q.getX() == wx && Math.abs(q.getY() - (int) Math.floor(rackTop)) <= 1 && Math.abs(q.getZ() - (int) Math.floor(rz)) <= 1;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    l.setBlock(new BlockPos(wx, (int) Math.floor(rackTop) + dy, (int) Math.floor(rz) + dz), Blocks.GRAY_CONCRETE.defaultBlockState(), 3);
                }
            }
            double[] expectedBehind = {0.0};
            pool.be.forEachRackSource((x, topY, z, decayW, cover) -> {
                Vec3 s = new Vec3(x, topY, z);
                expectedBehind[0] += DoseModel.pointSourceMsvPerH(BridgeMotion.gammaWatts(decayW), beside.distanceTo(s), BridgeMotion.transmission(cover))
                        * Math.exp(-depth(s, beside, inWall, mu(Shielding.CONCRETE_HVL_CM)));
            });
            double behind = RadiationSources.doseRateMsvPerHour(l, beside);
            h.assertTrue(near(behind, expectedBehind[0], 1e-9) && behind < old.apply(beside) * 1e-4, "behind a concrete wall outside the pool: " + behind
                    + " against " + expectedBehind[0] + " (open " + old.apply(beside) + ")");
            System.out.println("SHIELDING POOL: beside the pool " + old.apply(beside) + " mSv/h open, " + behind + " behind 1 m of concrete; diver " + sampled
                    + " mSv/h with the pool full of water blocks (unchanged)");
        } finally {
            RadiationSources.clear(l, owner);
            if (f != null) {
                clearWall(l, f, f.racks.get(0).getY() + 4, f.racks.get(0).getZ() + 0.5);
                f.dispose();
            }
            release.run();
        }
        h.succeed();
    }

    private static int wallX(SpentFuelPoolFixture f) {
        return f.min.getX() - 2;
    }

    /** The concrete wall beside the pool is outside the pool fixture's own clearing. */
    private static void clearWall(ServerLevel l, SpentFuelPoolFixture f, double rackTop, double rz) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                l.setBlock(new BlockPos(wallX(f), (int) Math.floor(rackTop) + dy, (int) Math.floor(rz) + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 400)
    public static void theCacheIsDroppedWhenABlockIsPlacedOrBrokenAndExpires(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = origin(3);
        Object owner = new Object();
        Runnable release = force(l, o, o.offset(-2, -2, -2), o.offset(8, 2, 2));
        Vec3 source = Vec3.atCenterOf(o);
        Vec3 meter = source.add(5.0, 0.0, 0.0);
        BlockPos wall = o.offset(2, 0, 0);
        double factor = Math.pow(2.0, -100.0 / Shielding.CONCRETE_HVL_CM);
        double[] bare = {0.0};
        Script script = new Script(h, 400, () -> {
        }, () -> {
            RadiationSources.clear(l, owner);
            release.run();
        });
        script.then(1, () -> {
            RadiationSources.emit(l, owner, source, 1000.0, 1.0);
            bare[0] = RadiationSources.doseRateMsvPerHour(l, meter);
            long[] before = BlockShielding.stats();
            double again = RadiationSources.doseRateMsvPerHour(l, meter);
            long[] after = BlockShielding.stats();
            h.assertTrue(again == bare[0] && after[0] == before[0] && after[2] == before[2] + 1, "a second sample is not a cache hit: marches "
                    + (after[0] - before[0]) + ", hits " + (after[2] - before[2]));
            // placed: seen at once
            l.setBlock(wall, Blocks.GRAY_CONCRETE.defaultBlockState(), 3);
            double placed = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(placed, bare[0] * factor, 1e-9), "the cache kept the reading after a block was placed: " + placed / bare[0]);
            // broken: seen at once
            l.destroyBlock(wall, false);
            double broken = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(broken == bare[0], "the cache kept the shielding after the block was broken: " + broken / bare[0]);
        });
        script.then(2, () -> {
            // a change that notifies no neighbour is not seen until the cached ray expires
            h.assertTrue(RadiationSources.doseRateMsvPerHour(l, meter) == bare[0], "the open line reads differently");
            l.setBlock(wall, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
            h.assertTrue(RadiationSources.doseRateMsvPerHour(l, meter) == bare[0], "fixture: a silent change was seen at once (the cache is not in use)");
        });
        script.then(BlockShielding.TTL_TICKS + 2, () -> {
            double expired = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(expired, bare[0] * factor, 1e-9), "the cached ray outlived its " + BlockShielding.TTL_TICKS + " ticks: " + expired / bare[0]);
        });
        script.start();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Unloaded chunks
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 1600)
    public static void anUnloadedChunkOnTheLineCountsAsUnshieldedAndIsNeverLoaded(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ChunkLab lab = new ChunkLab(l);
        int x0 = X0, z0 = Z0 + 4000;
        int cx0 = x0 >> 4, row = z0 >> 4;
        Object owner = new Object();
        Vec3 source = new Vec3(x0 + 8.5, Y + 0.5, z0 + 8.5);
        Vec3 meter = new Vec3(x0 + 6 * 16 + 8.5, Y + 0.5, z0 + 8.5);
        BlockPos lead = new BlockPos(x0 + 3 * 16 + 8, Y, z0 + 8);
        double[] bare = {0.0};
        Script script = new Script(h, 1600, () -> {
        }, () -> {
            RadiationSources.clear(l, owner);
            if (lab.loaded(lead)) {
                l.setBlock(lead, Blocks.AIR.defaultBlockState(), 2);
            }
            lab.close();
        });
        script.then(1, () -> {
            lab.forceRect(cx0, row, cx0 + 6, row);
            l.setBlock(lead, Blocks.AIR.defaultBlockState(), 3);
            RadiationSources.emit(l, owner, source, 1.0e6, 1.0);
            bare[0] = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(bare[0], DoseModel.pointSourceMsvPerH(1.0e6, 96.0, 1.0), 1e-12), "the open line reads " + bare[0]);
            l.setBlock(lead, BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
            double shielded = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(shielded, bare[0] * Math.pow(2.0, -100.0), 1e-9), "the lead in chunk 3 does not shield while loaded: " + shielded / bare[0]);
            lab.syncLoads.clear();
            for (int c = 1; c <= 5; c++) {
                lab.unforce(cx0 + c, row);
            }
        });
        script.await(() -> "chunk 3 to unload: " + lab.describe(cx0 + 3, row), 600, () -> !lab.loaded(cx0 + 3, row));
        script.then(5, () -> {
            double reading = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(reading == bare[0], "a line through an unloaded chunk is not read as unshielded: " + reading / bare[0]);
            var path = BlockShielding.trace(l, source, meter, false, null);
            h.assertTrue(path.unknown(), "the path through the unloaded chunk is not marked unknown");
            h.assertTrue(!lab.loaded(cx0 + 3, row), "reading the dose loaded chunk 3");
            h.assertTrue(lab.syncLoads.isEmpty(), "our code loaded a chunk: " + lab.syncLoads);
        });
        script.then(20, () -> {
            h.assertTrue(!lab.loaded(cx0 + 3, row), "chunk 3 came back without a ticket");
            RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(lab.syncLoads.isEmpty() && !lab.loaded(cx0 + 3, row), "a second reading loaded chunk 3: " + lab.syncLoads);
            lab.request(cx0 + 3, row);
        });
        script.awaitChunks(() -> "chunk 3 to come back: " + lab.describe(cx0 + 3, row), 600, () -> lab.loaded(cx0 + 3, row));
        script.then(2, () -> {
            double back = RadiationSources.doseRateMsvPerHour(l, meter);
            h.assertTrue(near(back, bare[0] * Math.pow(2.0, -100.0), 1e-9), "the lead does not shield again once its chunk is back: " + back / bare[0]);
            l.setBlock(lead, Blocks.AIR.defaultBlockState(), 2);
            lab.releaseAll();
        });
        script.start();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cost
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = NS, batch = "shielding", timeoutTicks = 600)
    public static void aSampleInADensePoolStaysCheap(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = origin(5);
        SpentFuelPoolFixture f = null;
        List<Object> owners = new ArrayList<>();
        Runnable release = force(l, o, o.offset(-30, -6, -30), o.offset(60, 30, 60));
        try {
            // the largest pool the fixture builds, both rows of racks full of fuel
            f = SpentFuelPoolFixture.build(l, o, 25, 25, 12, 46);
            f.be.pool().restore(10.0 * 23 * 23 * 1000.0, 25.0);
            long now = l.getGameTime();
            int filled = f.fill(46 * dev.bwr.core.pool.SpentFuelPool.ASSEMBLIES_PER_RACK, 45000.0, 86_400.0, now);
            f.be.simulateSeconds(1, now);
            int[] racks = {0};
            f.be.forEachRackSource((x, topY, z, decayW, cover) -> racks[0]++);
            // and a concrete room around the deck with walls between, so the rays have something to march
            for (BlockPos p : BlockPos.betweenClosed(o.offset(-6, 12, -6), o.offset(30, 12, 30))) {
                l.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
            }
            for (int i = 0; i < 25; i += 4) {
                for (BlockPos p : BlockPos.betweenClosed(o.offset(-4, 0, i), o.offset(-4, 11, i + 1))) {
                    l.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
                }
            }
            double surface = f.be.surfaceY();
            List<Vec3> points = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                switch (i % 4) {
                    case 0 -> points.add(new Vec3(o.getX() + 2.5 + i * 0.5, surface + 1.5, o.getZ() + 2.5 + i * 0.4)); // the deck
                    case 1 -> points.add(new Vec3(o.getX() + 3.5 + i * 0.4, surface - 0.5, o.getZ() + 12.5)); // swimming
                    case 2 -> points.add(new Vec3(o.getX() - 8.5, o.getY() + 2.5 + (i % 8), o.getZ() + i * 0.6)); // behind the walls
                    default -> points.add(new Vec3(o.getX() + 12.5, o.getY() + 16.5, o.getZ() + i * 0.6)); // the floor above
                }
            }
            BlockShielding.clearAll();
            BlockShielding.resetStats();
            long t0 = System.nanoTime();
            double sum = 0.0;
            for (Vec3 p : points) {
                sum += RadiationSources.doseRateMsvPerHour(l, p);
            }
            long cold = System.nanoTime() - t0;
            long[] coldStats = BlockShielding.stats();
            int warmRounds = 25;
            t0 = System.nanoTime();
            for (int r = 0; r < warmRounds; r++) {
                for (Vec3 p : points) {
                    sum += RadiationSources.doseRateMsvPerHour(l, p);
                }
            }
            long warm = System.nanoTime() - t0;
            long[] warmStats = BlockShielding.stats();
            double coldUs = cold / 1000.0 / points.size();
            double warmUs = warm / 1000.0 / (points.size() * warmRounds);
            double cellsPerMarch = coldStats[0] == 0 ? 0 : (double) coldStats[1] / coldStats[0];
            System.out.println("SHIELDING PERF: dense pool " + racks[0] + " rack sources (" + filled + " assemblies), " + points.size()
                    + " sample points: cold " + String.format("%.1f", coldUs) + " us/sample (" + coldStats[0] + " marches, "
                    + String.format("%.1f", cellsPerMarch) + " cells read per march), warm " + String.format("%.1f", warmUs) + " us/sample ("
                    + (warmStats[0] - coldStats[0]) + " marches, " + (warmStats[2] - coldStats[2]) + " cache hits); sum " + sum);
            h.assertTrue(racks[0] >= 40 && sum > 0.0, "fixture: the dense pool has " + racks[0] + " rack sources");
            h.assertTrue(coldStats[0] >= (long) racks[0] * points.size() / 2, "fixture: the cold pass did not march: " + coldStats[0]);
            h.assertTrue(warmStats[0] == coldStats[0], "a warm sample marched again: " + (warmStats[0] - coldStats[0]) + " marches");
            h.assertTrue(coldUs < 20_000.0, "a cold sample of a dense pool took " + coldUs + " us");
            h.assertTrue(warmUs < 2_000.0, "a warm sample of a dense pool took " + warmUs + " us");

            // 400 free point sources (a store of casks) in a grid around one meter, behind walls
            BlockPos s0 = o.offset(0, 0, 40);
            for (int i = 0; i < 20; i++) {
                for (int j = 0; j < 20; j++) {
                    Object owner = new Object();
                    owners.add(owner);
                    RadiationSources.emit(l, owner, Vec3.atCenterOf(s0.offset(i, 0, j)), 1.0, 1.0);
                }
            }
            for (int i = 0; i < 20; i += 3) {
                for (BlockPos p : BlockPos.betweenClosed(s0.offset(i, 0, 0), s0.offset(i, 1, 19))) {
                    if (!p.equals(s0.offset(i, 0, 0))) {
                        l.setBlock(p, BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 2);
                    }
                }
            }
            Vec3 meter = Vec3.atCenterOf(s0.offset(25, 0, 10));
            BlockShielding.resetStats();
            t0 = System.nanoTime();
            RadiationSources.doseRateMsvPerHour(l, meter);
            long many = System.nanoTime() - t0;
            t0 = System.nanoTime();
            for (int r = 0; r < 20; r++) {
                RadiationSources.doseRateMsvPerHour(l, meter);
            }
            long manyWarm = (System.nanoTime() - t0) / 20;
            System.out.println("SHIELDING PERF: 400 point sources behind lead walls, one meter: cold " + String.format("%.1f", many / 1000.0)
                    + " us/sample, warm " + String.format("%.1f", manyWarm / 1000.0) + " us/sample; stats " + java.util.Arrays.toString(BlockShielding.stats()));
            h.assertTrue(many / 1000.0 < 50_000.0 && manyWarm / 1000.0 < 5_000.0, "400 sources: cold " + many / 1000.0 + " us, warm " + manyWarm / 1000.0 + " us");
        } finally {
            for (Object owner : owners) {
                RadiationSources.clear(l, owner);
            }
            if (f != null) {
                f.dispose();
            }
            release.run();
        }
        h.succeed();
    }
}
