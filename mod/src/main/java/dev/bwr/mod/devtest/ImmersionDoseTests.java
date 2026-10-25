package dev.bwr.mod.devtest;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.radiation.ImmersionDose;
import dev.bwr.mod.radiation.PlayerDoses;
import dev.bwr.mod.radiation.RadiationField;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.radiation.RadiationTracker;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What the water of a spent fuel pool and of a flooded reactor well does to a person in it: the water between you and the fuel is the
 * shield (a diver 1 m over fresh fuel reads hundreds of Sv/h, a swimmer at the surface over deep water a trifle), the dose accumulates with no
 * meter carried, the mod's own effects apply at the mod's own thresholds, the first high dose is announced, creative players are exempt, and
 * a point above the water reads exactly as it always did. Namespace {@code bwrpool}.
 */
@GameTestHolder("bwrpool")
@PrefixGameTestTemplate(false)
public final class ImmersionDoseTests {

    private ImmersionDoseTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(68000 + 60 * i, 190, 68000);
    }

    /** A player whose sensor (the middle of the body, where the meter measures) is at {@code sensor}. */
    private static FakePlayer player(ServerLevel level, String name, Vec3 sensor) {
        FakePlayer p = NewFuelElevatorTests.player(level, "dive-" + name, sensor);
        p.setPos(sensor.x, sensor.y - p.getBbHeight() * 0.5, sensor.z);
        p.removeAllEffects();
        PlayerDoses.reset(p);
        return p;
    }

    /** A 9 x 9 x 8 pool at 7 m with one rack of four assemblies just discharged: {@code rackTop} is the rack top, {@code x}, {@code z} its middle. */
    private static final class Pool {
        final SpentFuelPoolFixture f;
        final double rackTop;
        final double x;
        final double z;

        Pool(GameTestHelper h, int index, int assemblies) {
            f = SpentFuelPoolFixture.build(h.getLevel(), at(index), 9, 9, 8, 1);
            f.be.pool().restore(7.0 * 49_000.0, 25.0);
            long now = h.getLevel().getGameTime();
            for (int c = 0; c < assemblies; c++) {
                f.be.insertAt(f.racks.get(0), c, SpentFuelPoolFixture.assembly(45000.0, 0.0, now));
            }
            f.be.simulateSeconds(1, now);
            rackTop = f.racks.get(0).getY() + 4;
            x = f.racks.get(0).getX() + 0.5;
            z = f.racks.get(0).getZ() + 0.5;
        }

        double surface() {
            return f.be.surfaceY();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The pool
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void aDiverNearFreshFuelIsHurtAndASwimmerAtTheSurfaceIsNot(GameTestHelper h) {
        Pool pool = new Pool(h, 0, 4);
        ServerLevel level = h.getLevel();
        try {
            double surface = pool.surface();
            h.assertTrue(Math.abs(surface - (pool.rackTop + 3.0)) < 1e-3, "fixture: 3 m of water over the racks: " + surface);
            FakePlayer deep = player(level, "deep", new Vec3(pool.x, pool.rackTop + 1.0, pool.z));
            FakePlayer top = player(level, "top", new Vec3(pool.x, surface - 0.5, pool.z));
            h.assertTrue(ImmersionDose.immersed(deep) && ImmersionDose.immersed(top), "both are in the water");

            double deepRate = RadiationTracker.read(deep);
            double topRate = RadiationTracker.read(top);
            h.assertTrue(deepRate > 1.0e5, "a metre over four fresh assemblies reads hundreds of Sv/h: " + deepRate + " mSv/h");
            h.assertTrue(topRate < 10.0 && topRate > 0.0, "at the surface over 2.5 m of water it is a trifle: " + topRate + " mSv/h");
            h.assertTrue(deepRate > 1.0e4 * topRate, "the depth is the difference: " + deepRate + " against " + topRate);
            // what the old rule gave the diver: the 3 m of water over the rack top, which is above him
            double gamma = BridgeMotion.gammaWatts(4 * 315_000.0);
            double old = DoseModel.pointSourceMsvPerH(gamma, 1.0, BridgeMotion.transmission(3.0));
            h.assertTrue(deepRate > 1.0e5 * old, "the old figure ignored that the diver is under 1 m of water, not 3: " + deepRate + " against " + old);
            System.out.println("IMMERSION POOL: diver 1 m over the racks " + deepRate + " mSv/h, swimmer at the surface " + topRate + " mSv/h, old rule " + old);

            // the harm: the sample reads the same function the meter reads, and applies the mod's effects at the mod's thresholds
            double sampled = ImmersionDose.sample(deep);
            h.assertTrue(Math.abs(sampled - deepRate) < 1e-9 * deepRate, "the sample is the meter's reading: " + sampled + " vs " + deepRate);
            h.assertTrue(deep.hasEffect(MobEffects.WITHER) && deep.hasEffect(MobEffects.WEAKNESS) && deep.hasEffect(MobEffects.POISON)
                    && deep.hasEffect(MobEffects.HUNGER), "hunger, weakness, poison and wither at hundreds of Sv/h");
            h.assertTrue(deep.getEffect(MobEffects.WITHER).getAmplifier() >= 2, "wither III above 150 Sv/h: " + deep.getEffect(MobEffects.WITHER));
            double expectedDose = deepRate * (ImmersionDose.SAMPLE_TICKS / 20.0) / 3600.0;
            h.assertTrue(Math.abs(PlayerDoses.get(deep) - expectedDose) < 1e-9 * expectedDose, "the dose accumulated with no meter carried: " + PlayerDoses.get(deep) + " vs " + expectedDose);
            h.assertTrue(ImmersionDose.warned(deep), "and the player was told");

            double surfaceSample = ImmersionDose.sample(top);
            h.assertTrue(surfaceSample > 0.0 && !top.hasEffect(MobEffects.WITHER) && !top.hasEffect(MobEffects.HUNGER) && !top.hasEffect(MobEffects.WEAKNESS),
                    "no effects at the surface: " + top.getActiveEffects());
            h.assertTrue(PlayerDoses.get(top) > 0.0 && PlayerDoses.get(top) < PlayerDoses.get(deep) / 1.0e4, "a trifle of dose: " + PlayerDoses.get(top));
            h.assertTrue(!ImmersionDose.warned(top), "and nobody is alarmed");

            // out of the water the same player is not sampled at all
            deep.setPos(pool.x, surface + 1.0, pool.z);
            deep.removeAllEffects();
            h.assertTrue(!ImmersionDose.immersed(deep) && ImmersionDose.sample(deep) < 0.0 && deep.getActiveEffects().isEmpty(), "above the water: not immersed");
            h.assertTrue(!ImmersionDose.warned(deep), "leaving the water re-arms the warning");
        } finally {
            pool.f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void theHarmFollowsTheDoseRateThroughTheModsThresholdsAndAMeterIsNotCountedTwice(GameTestHelper h) {
        Pool pool = new Pool(h, 1, 1);
        ServerLevel level = h.getLevel();
        try {
            // one fresh assembly: walk down from the surface and the effects arrive in the mod's order
            double surface = pool.surface();
            boolean sawHunger = false;
            boolean sawWither = false;
            double last = -1.0;
            for (double y = surface - 0.2; y > pool.rackTop + 0.4; y -= 0.1) {
                FakePlayer p = player(level, "walk", new Vec3(pool.x + 0.3, y, pool.z));
                double rate = ImmersionDose.sample(p);
                h.assertTrue(rate > last, "deeper is hotter: " + y + " " + rate + " after " + last);
                last = rate;
                double hazard = DoseModel.hazardFromDoseRate(rate);
                h.assertTrue(p.hasEffect(MobEffects.HUNGER) == (hazard > 1.0e-4), "hunger exactly above hazard 1e-4 at " + rate + " mSv/h");
                h.assertTrue(p.hasEffect(MobEffects.WEAKNESS) == (hazard > 1.0e-3), "weakness exactly above 1e-3 at " + rate);
                h.assertTrue(p.hasEffect(MobEffects.WITHER) == (hazard > 1.0e-2), "wither exactly above 1e-2 at " + rate);
                sawHunger |= p.hasEffect(MobEffects.HUNGER);
                sawWither |= p.hasEffect(MobEffects.WITHER);
            }
            h.assertTrue(sawHunger && sawWither, "the walk down crossed the thresholds");

            // a player who carries a meter: the meter's own sampler records the dose, so the immersion sample does not add it again
            FakePlayer carrier = player(level, "carrier", new Vec3(pool.x, pool.rackTop + 1.0, pool.z));
            carrier.getInventory().setItem(20, new ItemStack(BwrItems.SURVEY_METER.get()));
            ImmersionDose.sample(carrier);
            h.assertTrue(PlayerDoses.get(carrier) == 0.0, "the immersion sample leaves the dose to the meter: " + PlayerDoses.get(carrier));
            double rate = RadiationTracker.sample(carrier);
            h.assertTrue(Math.abs(PlayerDoses.get(carrier) - rate * (RadiationTracker.SAMPLE_TICKS / 20.0) / 3600.0) < 1e-12, "and the meter records it once");

            // the dose persists with the player
            FakePlayer diver = player(level, "persist", new Vec3(pool.x, pool.rackTop + 1.0, pool.z));
            ImmersionDose.sample(diver);
            double dose = PlayerDoses.get(diver);
            h.assertTrue(dose > 0.0, "fixture: some dose");
            var tag = new CompoundTag();
            diver.saveWithoutId(tag);
            PlayerDoses.reset(diver);
            diver.load(tag);
            h.assertTrue(Math.abs(PlayerDoses.get(diver) - dose) < 1e-12, "the dose a diver took is still on the player after a save and load: " + PlayerDoses.get(diver));
        } finally {
            pool.f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void creativeAndSpectatorPlayersAreExemptAndNobodyOutsideTheWaterIsSampled(GameTestHelper h) {
        Pool pool = new Pool(h, 2, 4);
        ServerLevel level = h.getLevel();
        try {
            Vec3 deep = new Vec3(pool.x, pool.rackTop + 1.0, pool.z);
            for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
                FakePlayer p = player(level, "exempt-" + mode.getName(), deep);
                p.setGameMode(mode);
                h.assertTrue(ImmersionDose.sample(p) < 0.0, mode + " is not sampled");
                h.assertTrue(p.getActiveEffects().isEmpty() && PlayerDoses.get(p) == 0.0 && !ImmersionDose.warned(p), mode + " takes nothing");
            }
            // on the rim, beside the pool, in a corridor under it: not in the water
            Vec3[] dry = {new Vec3(pool.x, pool.surface() + 0.5, pool.z), new Vec3(pool.f.min.getX() - 2.0, pool.rackTop + 1.0, pool.z),
                    new Vec3(pool.f.min.getX() + 0.5, pool.rackTop + 1.0, pool.z), new Vec3(pool.x, pool.f.min.getY() + 0.5, pool.z)};
            for (int i = 0; i < dry.length; i++) {
                FakePlayer p = player(level, "dry" + i, dry[i]);
                h.assertTrue(!ImmersionDose.immersed(p) && ImmersionDose.sample(p) < 0.0 && p.getActiveEffects().isEmpty(), "point " + i + " " + dry[i] + " is not in the water");
            }
            // an empty pool has no water to be in
            pool.f.be.pool().restore(0.0, 25.0);
            FakePlayer p = player(level, "empty", deep);
            h.assertTrue(!ImmersionDose.immersed(p), "a dry pool has no one in the water");
        } finally {
            pool.f.dispose();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 300)
    public static void readingsAboveTheWaterAreExactlyWhatTheyAlwaysWere(GameTestHelper h) {
        Pool pool = new Pool(h, 3, 4);
        ServerLevel level = h.getLevel();
        try {
            // the old rule, worked out here from the visitor: the water straight over the rack top, however the point is placed
            Vec3[] points = {new Vec3(pool.x, pool.surface() + 4.5, pool.z), new Vec3(pool.x + 2.0, pool.surface() + 0.01, pool.z - 3.0),
                    new Vec3(pool.f.min.getX() - 3.0, pool.rackTop + 0.5, pool.z), new Vec3(pool.f.min.getX() + 20.0, pool.surface() + 8.0, pool.z + 5.0)};
            for (Vec3 at : points) {
                double[] expected = {0.0};
                pool.f.be.forEachRackSource((x, topY, z, decayW, cover) -> expected[0] += DoseModel.pointSourceMsvPerH(BridgeMotion.gammaWatts(decayW),
                        at.distanceTo(new Vec3(x, topY, z)), BridgeMotion.transmission(cover)));
                double got = RadiationSources.doseRateMsvPerHour(level, at);
                h.assertTrue(got > 0.0 && Math.abs(got - expected[0]) <= 1e-12 * expected[0], "at " + at + ": " + got + " against the old figure " + expected[0]);
            }
        } finally {
            pool.f.dispose();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The flooded well
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrpool", timeoutTicks = 4000)
    public static void aDiverInAFloodedWellOverAnOpenVesselIsHurtAndOneAtTheSurfaceIsNot(GameTestHelper h) throws Exception {
        CavitySealTests.Site s = new CavitySealTests.Site(h.getLevel(), new BlockPos(72000, 190, 72000), 5, 5, 8);
        ServerLevel level = h.getLevel();
        try {
            s.ring(1, 3, 16);
            s.survey();
            ReactorControllerBlockEntity reactor = s.be;
            reactor.core().getDecayHeat().setToSaturatedInventory(1.0); // a core just scrammed from months at power
            h.assertTrue(reactor.setVesselState(VesselState.REFUELING) == null, "the head comes off");
            var vessel = reactor.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 1.0);
            reactor.core().setFeedwaterFlowKgPerS(1200.0);
            for (int i = 0; i < 400; i++) {
                s.tick();
            }
            reactor.core().setFeedwaterFlowKgPerS(0.0);
            var structure = reactor.structure();
            double top = structure.topOfActiveFuelY() + 1.0;
            double water = Math.max(0.0, (reactor.core().getCollapsedLevelIn() - PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN) * 0.0254);
            double surface = top + water;
            h.assertTrue(water > 4.5, "the well is flooded: " + water + " m over the fuel");
            double cx = (structure.interiorMin().getX() + structure.interiorMax().getX() + 1) / 2.0;
            double cz = (structure.interiorMin().getZ() + structure.interiorMax().getZ() + 1) / 2.0;

            // above the water the open head reads as it always did: the water straight over the fuel
            Vec3 above = new Vec3(cx, Math.max(surface, reactor.drawnWaterSurfaceY()) + 2.0, cz);
            double gamma = DoseModel.coreGammaWatts(Math.max(0.0, reactor.core().getThermalPowerMW() - reactor.core().getDecayHeatMW()), reactor.core().getDecayHeatMW());
            Vec3 centre = new Vec3(cx, top - PressureVessel.ACTIVE_FUEL_HEIGHT_IN * 0.0254 / 2.0, cz);
            double expectedAbove = DoseModel.pointSourceMsvPerH(gamma, above.distanceTo(centre), DoseModel.openHeadTransmission(water));
            double gotAbove = RadiationField.read(level, above).core();
            h.assertTrue(gotAbove > 0.0 && Math.abs(gotAbove - expectedAbove) <= 1e-12 * expectedAbove, "above the water: " + gotAbove + " against the old figure " + expectedAbove);

            FakePlayer deep = player(level, "well-deep", new Vec3(cx, top + 1.0, cz));
            FakePlayer shallow = player(level, "well-top", new Vec3(cx, surface - 0.5, cz));
            FakePlayer dry = player(level, "well-dry", above);
            h.assertTrue(RadiationField.inOpenWater(reactor, new Vec3(cx, top + 1.0, cz)) && ImmersionDose.immersed(deep) && ImmersionDose.immersed(shallow)
                    && !ImmersionDose.immersed(dry), "in the water, in the water, above it");
            double deepRate = RadiationTracker.read(deep);
            double shallowRate = RadiationTracker.read(shallow);
            double wrong = DoseModel.pointSourceMsvPerH(gamma, deep.position().add(0, deep.getBbHeight() / 2, 0).distanceTo(centre), DoseModel.openHeadTransmission(water));
            h.assertTrue(deepRate > 1.0e5 * wrong, "a metre over the fuel is under 1 m of water, not the " + water + " m above it: " + deepRate + " against " + wrong);
            h.assertTrue(deepRate > 1.0e4 * shallowRate && deepRate > DoseModel.doseRateFromHazard(0.1), "much hotter at the bottom: " + deepRate + " against " + shallowRate);
            System.out.println("IMMERSION WELL: diver 1 m over the fuel " + deepRate + " mSv/h, swimmer at the surface " + shallowRate + " mSv/h, over the water " + gotAbove);

            h.assertTrue(ImmersionDose.sample(deep) > 0.0 && deep.hasEffect(MobEffects.WITHER) && deep.hasEffect(MobEffects.WEAKNESS)
                    && PlayerDoses.get(deep) > 0.0 && ImmersionDose.warned(deep), "the diver is hurt, dosed and told");
            ImmersionDose.sample(shallow);
            h.assertTrue(shallow.getActiveEffects().isEmpty() || shallowRate > DoseModel.doseRateFromHazard(1.0e-4), "the swimmer at the surface takes the effects of its reading and no more: " + shallowRate);
            deep.setGameMode(GameType.CREATIVE);
            deep.removeAllEffects();
            PlayerDoses.reset(deep);
            h.assertTrue(ImmersionDose.sample(deep) < 0.0 && deep.getActiveEffects().isEmpty() && PlayerDoses.get(deep) == 0.0, "creative is exempt in the well too");

            // with the head back on nobody is in the open water
            h.assertTrue(reactor.setVesselState(VesselState.SHUTDOWN) == null, "the head goes back on");
            h.assertTrue(!RadiationField.inOpenWater(reactor, new Vec3(cx, top + 1.0, cz)), "a closed vessel has no one in its water");
        } finally {
            s.clear();
        }
        h.succeed();
    }
}
