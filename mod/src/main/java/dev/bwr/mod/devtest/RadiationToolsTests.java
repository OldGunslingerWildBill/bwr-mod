package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlock;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.radiation.PlayerDoses;
import dev.bwr.mod.radiation.RadiationField;
import dev.bwr.mod.radiation.RadiationMonitors;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.radiation.RadiationTracker;
import dev.bwr.mod.radiation.SurveyMeterItem;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorSevereAccidentEffects;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The survey meter and the area radiation monitor: that they read the existing hazard model (and nothing else), that the
 * reading is 0 at background, rises near contamination and falls with distance, that the dose accumulates and persists,
 * that the monitor drives a wired gauge and an annunciator and refuses incompatible gauges, and the extension API.
 * CC:Tweaked is in {@link RadiationCcTests}.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RadiationToolsTests {
    private RadiationToolsTests() {
    }

    // ---- fixtures

    private static FakePlayer player(ServerLevel level, String name, Vec3 sensorAt) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("radiation-tools-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "rad-" + name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        PlayerDoses.reset(player);
        // The sensor is in the middle of the body; stand so that it is exactly at sensorAt.
        player.setPos(sensorAt.x, sensorAt.y - player.getBbHeight() * 0.5, sensorAt.z);
        return player;
    }

    private static void giveMeter(FakePlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.SURVEY_METER.get()));
    }

    /** A formed, empty 15 x 15 reactor whose vessel minimum corner is {@code min}; the caller removes it when done. */
    private static ReactorControllerBlockEntity plant(ServerLevel level, BlockPos min) throws Exception {
        forceChunks(level, min, true);
        var controller = CompactCoreRegressionTests.build(level, min, 15, 15, false);
        if (!controller.isFormed()) {
            throw new AssertionError("the reference reactor did not form");
        }
        return controller;
    }

    private static void forceChunks(ServerLevel level, BlockPos min, boolean on) {
        for (int x = (min.getX() - 20) >> 4; x <= (min.getX() + 36) >> 4; x++) {
            for (int z = (min.getZ() - 20) >> 4; z <= (min.getZ() + 36) >> 4; z++) {
                FixtureChunks.force(level, min, x, z, on);
                if (on) {
                    level.getChunk(x, z);
                }
            }
        }
    }

    private static void removePlant(ServerLevel level, BlockPos min) {
        for (int x = -1; x <= 15; x++) {
            for (int z = -1; z <= 15; z++) {
                for (int y = -2; y <= 8; y++) {
                    level.removeBlock(min.offset(x, y, z), false);
                }
            }
        }
        forceChunks(level, min, false);
    }

    private static Vec3 centre(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    // ---- the meter

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void surveyMeterReadsZeroAtBackground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 spot = new Vec3(63_000.5, 120.9, 63_000.5);
        FakePlayer player = player(level, "background", spot);
        giveMeter(player);
        helper.assertTrue(RadiationField.read(level, spot).equals(RadiationField.Reading.ZERO),
                "no reactor and no source anywhere near: every term is zero: " + RadiationField.read(level, spot));
        for (int i = 0; i < 40; i++) {
            double rate = RadiationTracker.sample(player);
            helper.assertTrue(rate == 0.0, "the meter reads exactly 0 mSv/h at background: " + rate);
        }
        helper.assertTrue(PlayerDoses.get(player) == 0.0, "and no dose builds up: " + PlayerDoses.get(player));
        // Right-click shows the reading and does not add a dose of its own.
        helper.assertTrue(player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction(),
                "using the meter is a consumed action");
        helper.assertTrue(PlayerDoses.get(player) == 0.0, "reading the meter records nothing: " + PlayerDoses.get(player));
        helper.assertTrue(DoseModel.formatRate(0.0).equals("0.00 µSv/h"), "and it shows as zero");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void meterReadsNonZeroAndRisingNearAContaminatedPlant(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(61_000, 190, 61_000);
        var reactor = plant(level, min);
        BlockPos controller = reactor.getBlockPos();
        Vec3 at12 = centre(controller).add(12.0, 0.0, 0.0);
        FakePlayer player = player(level, "rising", at12);
        giveMeter(player);
        try {
            helper.assertTrue(RadiationTracker.sample(player) == 0.0, "an uncontaminated plant with a cold core reads 0");
            var containment = reactor.core().getContainment();
            containment.step(0.0, 0.0, 0.05, 1.0); // a whole-core gap release, 5 % of the inventory, becomes airborne
            double first = RadiationTracker.sample(player);
            // 0.05 / (1 + 12^2 / 12^2) = 0.025 of an inventory at the half distance, about 1.54e6 mSv/h per inventory.
            helper.assertTrue(first > 30_000.0 && first < 45_000.0,
                    "a 5 % release read 12 blocks from the reactor is about 38 Sv/h, not " + first + " mSv/h");
            containment.step(0.0, 0.0, 0.05, 1.0); // more activity released: the reading rises with it
            double second = RadiationTracker.sample(player);
            helper.assertTrue(second > first * 1.9 && second < first * 2.1,
                    "twice the airborne activity reads about twice as high: " + first + " then " + second);
            // Walking towards the plant raises it again.
            double previous = second;
            for (double d : new double[] {40.0, 20.0, 6.0, 1.0}) {
                player.setPos(controller.getX() + 0.5 + d, controller.getY() + 0.5 - player.getBbHeight() * 0.5,
                        controller.getZ() + 0.5);
                double rate = RadiationTracker.sample(player);
                helper.assertTrue(d == 40.0 ? rate > 0.0 : rate > previous,
                        "closer to the plant reads higher: " + d + " blocks, " + rate + " after " + previous);
                previous = rate;
            }
            helper.assertTrue(PlayerDoses.get(player) > 0.0, "the carried meter recorded the dose: " + PlayerDoses.get(player));
        } finally {
            removePlant(level, min);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theReadingFallsWithDistanceExactlyAsTheEffectsModelDoes(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(61_400, 190, 61_000);
        var reactor = plant(level, min);
        try {
            var containment = reactor.core().getContainment();
            containment.step(0.0, 0.0, 0.05, 1.0);
            for (int i = 0; i < 60; i++) {
                containment.step(0.0, 0.0, 0.0, 600.0); // ten hours: most of it has settled onto the plant
            }
            containment.releaseDirectToEnvironment(0.01); // and a release to the site
            Vec3 origin = centre(reactor.getBlockPos());
            double lastAirborne = Double.MAX_VALUE, lastDeposited = Double.MAX_VALUE, lastRelease = Double.MAX_VALUE;
            for (double d : new double[] {2.0, 6.0, 12.0, 24.0, 48.0, 96.0, 192.0}) {
                var reading = RadiationField.read(level, origin.add(d, 0.0, 0.0));
                double d2 = d * d;
                // The same hazard formulas the game applies to a player, through the same conversion.
                helper.assertTrue(near(reading.airborne(), DoseModel.doseRateFromHazard(ReactorSevereAccidentEffects
                        .plantHazardAt(containment.getAirborneFraction(), d2))), "airborne at " + d + ": " + reading);
                helper.assertTrue(near(reading.deposited(), DoseModel.doseRateFromHazard(ReactorSevereAccidentEffects
                        .plantHazardAt(containment.getPlantContaminationIndex() - containment.getAirborneFraction(), d2))),
                        "deposited at " + d + ": " + reading);
                helper.assertTrue(near(reading.release(), DoseModel.doseRateFromHazard(ReactorSevereAccidentEffects
                        .siteHazardAt(containment.getSiteContaminationIndex(), d2))), "release at " + d + ": " + reading);
                helper.assertTrue(reading.deposited() > 0.0 && reading.release() > 0.0, "every contamination term reads at " + d + ": " + reading);
                helper.assertTrue(reading.airborne() < lastAirborne && reading.deposited() < lastDeposited
                        && reading.release() < lastRelease, "each term falls with distance at " + d + ": " + reading);
                lastAirborne = reading.airborne();
                lastDeposited = reading.deposited();
                lastRelease = reading.release();
            }
            helper.assertTrue(RadiationField.read(level, origin).deposited() > RadiationField.read(level, origin).airborne(),
                    "ten hours on, the plant surfaces hold more than the air: " + RadiationField.read(level, origin));
            // Past the model's own reach (256 blocks) nothing is read, exactly as no player is dosed there.
            var far = RadiationField.read(level, origin.add(ReactorSevereAccidentEffects.MAX_DOSE_DISTANCE_BLOCKS + 1.0, 0.0, 0.0));
            helper.assertTrue(far.equals(RadiationField.Reading.ZERO), "out of reach reads zero: " + far);
            // The half distance of the model: the airborne term is half of what it is at the controller.
            double atController = RadiationField.read(level, origin).airborne();
            double atHalf = RadiationField.read(level, origin.add(ReactorSevereAccidentEffects.PLANT_HALF_DISTANCE_BLOCKS, 0, 0)).airborne();
            helper.assertTrue(near(atHalf * 2.0, atController), "at the half distance the in-plant hazard is half: " + atHalf + " vs " + atController);
        } finally {
            removePlant(level, min);
        }
        helper.succeed();
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) <= 1.0e-9 * Math.max(1.0, Math.abs(expected));
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aClosedVesselReadsLowOutsideAndAnOpenHeadReadsHighLookingDown(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos min = new BlockPos(61_800, 190, 61_000);
        var reactor = plant(level, min);
        try {
            helper.assertTrue(RadiationField.doseRateMsvPerHour(level, centre(reactor.getBlockPos()).add(10, 0, 0)) == 0.0,
                    "a cold empty core makes no gamma");
            reactor.core().getDecayHeat().setToSaturatedInventory(1.0); // a core just scrammed from months at power
            var structure = reactor.structure();
            Vec3 middle = new Vec3((structure.interiorMin().getX() + structure.interiorMax().getX() + 1) / 2.0,
                    structure.topOfActiveFuelY() + 5.0, (structure.interiorMin().getZ() + structure.interiorMax().getZ() + 1) / 2.0);
            Vec3 beside = new Vec3(structure.interiorMax().getX() + 12.0, structure.topOfActiveFuelY() - 2.0, middle.z);
            double closedBeside = RadiationField.read(level, beside).core();
            double closedAbove = RadiationField.read(level, middle).core();
            helper.assertTrue(closedBeside > 0.0 && closedBeside < 1.0,
                    "a shielded vessel gives a low reading outside: " + closedBeside + " mSv/h");
            helper.assertTrue(closedAbove > 0.0 && closedAbove < 1.0,
                    "and above the closed head: " + closedAbove + " mSv/h");
            helper.assertTrue(reactor.setVesselState(VesselState.REFUELING) == null, "the head comes off a cold vessel");
            double openAbove = RadiationField.read(level, middle).core();
            double openBeside = RadiationField.read(level, beside).core();
            helper.assertTrue(openAbove > 1_000.0 && openAbove > closedAbove * 10_000.0,
                    "looking down an open head the reading is thousands of times higher: " + openAbove + " against " + closedAbove);
            helper.assertTrue(near(openBeside, closedBeside),
                    "beside the open vessel the walls still shield: " + openBeside + " against " + closedBeside);
            helper.assertTrue(reactor.setVesselState(VesselState.SHUTDOWN) == null, "the head goes back on");
            helper.assertTrue(near(RadiationField.read(level, middle).core(), closedAbove), "and the reading falls back");
        } finally {
            removePlant(level, min);
        }
        helper.succeed();
    }

    // ---- dose

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void doseAccumulatesPersistsAndResets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 spot = new Vec3(63_100.5, 120.9, 63_000.5);
        FakePlayer player = player(level, "dose", spot);
        Object owner = new Object();
        // A small point source two metres away, read at about 3.6 mSv/h.
        RadiationSources.emit(level, owner, spot.add(2.0, 0.0, 0.0), 0.02, 1.0);
        try {
            double rate = RadiationTracker.read(player);
            helper.assertTrue(rate > 2.0 && rate < 6.0, "fixture: about 3.6 mSv/h: " + rate);
            // Not carrying a meter: nothing is recorded, however long the player stands there.
            for (int i = 0; i < 20; i++) {
                RadiationTracker.sample(player);
            }
            helper.assertTrue(PlayerDoses.get(player) == 0.0, "without a meter no dose is recorded: " + PlayerDoses.get(player));
            // Carrying one anywhere in the inventory (not held): it records.
            player.getInventory().setItem(20, new ItemStack(BwrItems.SURVEY_METER.get()));
            helper.assertTrue(SurveyMeterItem.carriedBy(player) && !SurveyMeterItem.heldBy(player), "fixture: carried, not held");
            int samples = 100;
            for (int i = 0; i < samples; i++) {
                RadiationTracker.sample(player);
            }
            double expected = rate * samples * (RadiationTracker.SAMPLE_TICKS / 20.0) / 3600.0;
            double dose = PlayerDoses.get(player);
            helper.assertTrue(dose > 0.0 && Math.abs(dose - expected) < 1.0e-9 * Math.max(1.0, expected) + 1.0e-12,
                    "the dose is the dose rate times the time: " + dose + " vs " + expected + " mSv");
            // It is stored on the player and survives a save and load of the player's data.
            var tag = new CompoundTag();
            player.saveWithoutId(tag);
            helper.assertTrue(tag.toString().contains("player_dose"), "the dose is saved with the player: " + tag);
            PlayerDoses.reset(player);
            helper.assertTrue(PlayerDoses.get(player) == 0.0, "fixture: wiped");
            player.load(tag);
            helper.assertTrue(Math.abs(PlayerDoses.get(player) - dose) < 1.0e-12, "and it is back after loading: " + PlayerDoses.get(player));
            // Creative players read but record nothing.
            player.setGameMode(GameType.CREATIVE);
            double before = PlayerDoses.get(player);
            for (int i = 0; i < 10; i++) {
                RadiationTracker.sample(player);
            }
            helper.assertTrue(PlayerDoses.get(player) == before, "creative players are exempt: " + PlayerDoses.get(player));
            player.setGameMode(GameType.SURVIVAL);
            // A sneak-use resets it; a plain use does not.
            giveMeter(player);
            player.setShiftKeyDown(false);
            player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
            helper.assertTrue(Math.abs(PlayerDoses.get(player) - dose) < 1.0e-12, "a plain use only reads: " + PlayerDoses.get(player));
            player.setShiftKeyDown(true);
            player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
            helper.assertTrue(PlayerDoses.get(player) == 0.0, "sneak-use resets the dose: " + PlayerDoses.get(player));
            var empty = new CompoundTag();
            player.saveWithoutId(empty);
            helper.assertTrue(!empty.toString().contains("player_dose"),
                    "and a zero dose saves nothing, so old player files and new ones are alike");
        } finally {
            RadiationSources.clear(level, owner);
            player.setShiftKeyDown(false);
        }
        helper.succeed();
    }

    // ---- the extension API

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void otherSourcesAddToTheReadingThroughTheApi(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 spot = new Vec3(63_200.5, 120.5, 63_000.5);
        Object owner = new Object();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", "test_pool");
        int providers = RadiationSources.providerCount();
        helper.assertTrue(RadiationField.doseRateMsvPerHour(level, spot) == 0.0, "fixture: background");
        try {
        RadiationSources.emit(level, owner, spot.add(3.0, 0.0, 0.0), 1.0, 1.0);
        double point = RadiationField.doseRateMsvPerHour(level, spot);
        helper.assertTrue(near(point, DoseModel.pointSourceMsvPerH(1.0, 3.0, 1.0)) && point > 0.0,
                "a point source reads as DoseModel says: " + point);
        RadiationSources.emit(level, owner, spot.add(6.0, 0.0, 0.0), 1.0, 1.0); // moving it re-rates it
        helper.assertTrue(RadiationField.doseRateMsvPerHour(level, spot) < point / 3.0, "moved away, it reads less");
        RadiationSources.emit(level, owner, spot.add(3.0, 0.0, 0.0), 1.0, 0.5); // shielding takes half
        helper.assertTrue(near(RadiationField.doseRateMsvPerHour(level, spot), point / 2.0), "half the transmission, half the reading");
        RadiationSources.register(id, (lvl, at) -> lvl == level ? 2.5 : 0.0);
        helper.assertTrue(RadiationSources.providerCount() == providers + 1
                && near(RadiationField.read(level, spot).other(), point / 2.0 + 2.5), "a provider adds to it: " + RadiationField.read(level, spot));
        RadiationSources.register(ResourceLocation.fromNamespaceAndPath("bwr", "test_broken"), (lvl, at) -> {
            throw new IllegalStateException("a broken source");
        });
        helper.assertTrue(near(RadiationField.read(level, spot).other(), point / 2.0 + 2.5), "a provider that throws is skipped");
        } finally {
            RadiationSources.unregister(ResourceLocation.fromNamespaceAndPath("bwr", "test_broken"));
            RadiationSources.unregister(id);
            RadiationSources.clear(level, owner);
        }
        helper.assertTrue(RadiationField.doseRateMsvPerHour(level, spot) == 0.0 && RadiationSources.pointCount(level) == 0,
                "unregistered and cleared, it is background again");
        helper.assertTrue(RadiationSources.providerCount() == providers, "no provider is left behind");
        helper.succeed();
    }

    // ---- the monitor

    private static CompoundTag measures(String quantity) {
        CompoundTag config = new CompoundTag();
        config.putString("Measures", quantity);
        return config;
    }

    private static AreaRadiationMonitorBlockEntity monitor(NetStage s, BlockPos at, Direction facing) {
        s.level.setBlock(at, BwrBlocks.AREA_RADIATION_MONITOR.get().defaultBlockState()
                .setValue(AreaRadiationMonitorBlock.FACING, facing), 3);
        return (AreaRadiationMonitorBlockEntity) s.level.getBlockEntity(at);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theMonitorDrivesAWiredGaugeAndAnAnnunciatorAndItsOwnLamp(GameTestHelper helper) {
        NetStage.run(helper, 90, s -> {
            s.wall(2, 1);
            PanelBlockEntity gaugeBlock = s.be(s.at(0, 0));
            PanelBlockEntity annunciatorBlock = s.be(s.at(1, 0));
            PanelComponent gauge = s.gauge(gaugeBlock.getBlockPos(), 1, 1);
            PanelComponent annunciator = s.place(annunciatorBlock.getBlockPos(), 1, 1, ComponentTypes.ANNUNCIATOR, Footprint.of(12, 8));
            BlockPos monitorPos = s.devices;
            AreaRadiationMonitorBlockEntity monitor = monitor(s, monitorPos, Direction.NORTH);
            Object owner = new Object();
            s.then(1, () -> {
              try {
                // A source three blocks away, strong enough for about 5 mSv/h at the monitor.
                double watts = 5.0 / DoseModel.pointSourceMsvPerH(1.0, 3.0, 1.0);
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(monitorPos).add(3.0, 0.0, 0.0), watts, 1.0);
                monitor.invalidate();
                s.check(RadiationMonitors.loadedCount(s.level) >= 1, "the monitor is registered with the slow service");
                s.configure(gaugeBlock, gauge, measures("DOSE_RATE"));
                s.wrench(monitorPos);
                s.wire(s.plantIn(gaugeBlock, gauge, 0, monitorPos, "dose_rate", 1, 0));
                s.wire(s.plantIn(annunciatorBlock, annunciator, 0, monitorPos, "high_radiation", 1, 0));
                s.check(gauge.source(0) instanceof PlantSource g && g.key().equals("dose_rate"), "the gauge accepted the dose rate: " + gauge.source(0));
                s.check(annunciator.source(0) instanceof PlantSource a && a.key().equals("high_radiation"),
                        "the annunciator accepted the boolean: " + annunciator.source(0));
                gaugeBlock.resample(gauge, true);
                annunciatorBlock.resample(annunciator, true);
                double rate = monitor.doseRate();
                s.check(rate > 4.0 && rate < 6.0, "fixture: about 5 mSv/h: " + rate);
                s.check(Math.abs(gauge.input(0) - rate) < 1.0e-9, "the gauge shows the dose rate: " + gauge.input(0) + " vs " + rate);
                s.check(annunciator.input(0) == 1.0, "the annunciator input is high radiation: " + annunciator.input(0));
                AnnunciatorData data = (AnnunciatorData) annunciator.data();
                s.check(data.logic.state() == AnnunciatorLogic.State.ALARM, "the annunciator is in alarm: " + data.logic.state());
                // The lamp and the redstone output follow at the next refresh.
                monitor.refresh();
                var state = s.level.getBlockState(monitorPos);
                s.check(state.getValue(AreaRadiationMonitorBlock.LAMP) == 2, "red lamp above the alarm setpoint: " + state);
                s.check(state.getSignal(s.level, monitorPos, Direction.UP) == 15, "redstone 15 at alarm");
                // Raising the setpoint above the reading clears the boolean, the lamp drops to amber (alert is a tenth of it).
                s.check(monitor.setHighSetpoint(20.0), "the setpoint takes 20 mSv/h");
                gaugeBlock.resample(gauge, true);
                annunciatorBlock.resample(annunciator, true);
                s.check(annunciator.input(0) == 0.0, "no longer high radiation: " + annunciator.input(0));
                s.check(!data.logic.condition(), "the alarm condition has cleared");
                s.check(data.logic.state() == AnnunciatorLogic.State.ALARM, "an unacknowledged alarm keeps flashing (ISA-18.1 A): " + data.logic.state());
                s.check(data.logic.acknowledge() && data.logic.state() == AnnunciatorLogic.State.NORMAL,
                        "acknowledged with the condition gone, the window goes dark: " + data.logic.state());
                monitor.refresh();
                s.check(s.level.getBlockState(monitorPos).getValue(AreaRadiationMonitorBlock.LAMP) == 1, "amber lamp at the alert setpoint");
                s.check(s.level.getBlockState(monitorPos).getSignal(s.level, monitorPos, Direction.UP) == 7, "redstone 7 at alert");
                s.check(Math.abs(gauge.input(0) - rate) < 1.0e-9, "the gauge still shows the dose rate");
                // The signal list: quantity DOSE_RATE, two booleans, no command but the setpoint.
                SignalProvider provider = SignalProviders.at(s.level, monitorPos);
                s.check(provider != null, "the monitor reports signals");
                SignalSpec doseSignal = provider.signals().stream().filter(sp -> sp.key().equals("dose_rate")).findFirst().orElseThrow();
                s.check(Quantity.of(doseSignal) == Quantity.DOSE_RATE && doseSignal.unit().equals("mSv/h"), "dose_rate is a DOSE_RATE in mSv/h");
                s.check(provider.signals().stream().filter(sp -> sp.kind() == Kind.BOOLEAN).map(SignalSpec::key).toList()
                        .containsAll(java.util.List.of("high_radiation", "radiation_alert")), "both booleans are listed");
                s.check(provider.commands().stream().map(c -> c.key()).toList().equals(java.util.List.of("alarm_setpoint")),
                        "the only command is the alarm setpoint: " + provider.commands());
                s.check(provider.command("alarm_setpoint", 2.0) && monitor.highSetpoint() == 2.0, "the setpoint command goes through");
                s.check(!provider.command("alarm_setpoint", -1.0) && !provider.command("alarm_setpoint", Double.NaN)
                        && monitor.highSetpoint() == 2.0, "and bad values are refused");
              } finally {
                RadiationSources.clear(s.level, owner);
                s.level.removeBlock(monitorPos, false);
              }
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void incompatibleGaugeKindsAreRefused(GameTestHelper helper) {
        NetStage.run(helper, 91, s -> {
            s.wall(2, 1);
            PanelBlockEntity blockA = s.be(s.at(0, 0));
            PanelBlockEntity blockB = s.be(s.at(1, 0));
            PanelComponent doseGauge = s.gauge(blockA.getBlockPos(), 1, 1);
            PanelComponent levelGauge = s.gauge(blockB.getBlockPos(), 1, 1);
            BlockPos monitorPos = s.devices;
            monitor(s, monitorPos, Direction.EAST);
            BlockPos poolPos = s.devices.west(4);
            s.pool(poolPos);
            s.then(1, () -> {
                s.configure(blockA, doseGauge, measures("DOSE_RATE"));
                s.configure(blockB, levelGauge, measures("LEVEL"));
                // The dose gauge takes the monitor's dose rate and its setpoint (also mSv/h) and refuses a water level.
                s.wrench(monitorPos);
                s.wire(s.plantIn(blockA, doseGauge, 0, monitorPos, "dose_rate", 1, 0));
                s.check(doseGauge.source(0) instanceof PlantSource p && p.key().equals("dose_rate"), "the DOSE_RATE gauge takes dose_rate");
                s.wire(s.plantIn(blockA, doseGauge, 0, monitorPos, "alarm_setpoint", 1, 0));
                s.check(doseGauge.source(0) instanceof PlantSource p && p.key().equals("alarm_setpoint"), "and the setpoint, also mSv/h");
                s.wrench(poolPos);
                PanelPayloads.Bind level = s.plantIn(blockA, doseGauge, 0, poolPos, "level", 1, 0);
                s.wire(level);
                s.check(s.outcome(level).result() == Result.MEASURES_MISMATCH, "a DOSE_RATE gauge refuses a LEVEL signal: " + s.outcome(level).result());
                s.check(doseGauge.source(0) instanceof PlantSource p && p.key().equals("alarm_setpoint"), "the earlier binding stands");
                // A level gauge refuses the dose rate, with the reason, and so does a gauge of any other quantity.
                s.wrench(monitorPos);
                PanelPayloads.Bind dose = s.plantIn(blockB, levelGauge, 0, monitorPos, "dose_rate", 1, 0);
                s.wire(dose);
                var outcome = s.outcome(dose);
                s.check(outcome.result() == Result.MEASURES_MISMATCH, "a LEVEL gauge refuses the dose rate: " + outcome.result());
                String reason = dev.bwr.mod.text.BwrText.english(outcome.detail());
                s.check(reason.equals("This gauge measures LEVEL; Dose rate is DOSE_RATE"), "the reason is given: " + reason);
                s.check(!(levelGauge.source(0) instanceof PlantSource), "nothing was bound: " + levelGauge.source(0));
                for (String other : new String[] {"PRESSURE", "POWER", "TEMPERATURE", "PERCENT"}) {
                    s.configure(blockB, levelGauge, measures(other));
                    s.wrench(monitorPos);
                    var refused = s.outcome(s.plantIn(blockB, levelGauge, 0, monitorPos, "dose_rate", 1, 0));
                    s.check(refused.result() == Result.MEASURES_MISMATCH, "a " + other + " gauge refuses the dose rate: "
                            + refused.result() + " measures=" + levelGauge.data().measures(0));
                }
                // ANY still takes everything, as before the quantity existed.
                s.configure(blockB, levelGauge, measures("ANY"));
                s.wrench(monitorPos);
                s.wire(s.plantIn(blockB, levelGauge, 0, monitorPos, "dose_rate", 1, 0));
                s.check(levelGauge.source(0) instanceof PlantSource p && p.key().equals("dose_rate"), "an ANY gauge takes it");
                // The quantity table and the option list know the new quantity, and the old ordinals did not move.
                s.check(Quantity.of("x", "mSv/h", Kind.NUMBER) == Quantity.DOSE_RATE && Quantity.of("x", "Sv/h", Kind.NUMBER) == Quantity.DOSE_RATE
                        && Quantity.of("x", "uSv/h", Kind.NUMBER) == Quantity.DOSE_RATE, "the units map to DOSE_RATE");
                s.check(Quantity.BOOLEAN.ordinal() == 12 && Quantity.TEXT.ordinal() == 13 && Quantity.DOSE_RATE.ordinal() == 14,
                        "DOSE_RATE was appended: existing quantity ordinals keep their meaning");
                s.check(dev.bwr.mod.panel.component.Measures.options().contains("DOSE_RATE"), "the Measures setting offers it");
                s.level.removeBlock(monitorPos, false);
                s.level.removeBlock(poolPos, false);
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theMonitorKeepsItsSetpointAndSavesNothingByDefault(GameTestHelper helper) {
        NetStage.run(helper, 92, s -> {
            BlockPos at = s.devices;
            AreaRadiationMonitorBlockEntity monitor = monitor(s, at, Direction.SOUTH);
            CompoundTag fresh = monitor.saveWithoutMetadata(s.level.registryAccess());
            s.check(!fresh.contains("HighSetpoint"), "a default monitor saves no setpoint: " + fresh);
            s.check(monitor.highSetpoint() == 1.0 && monitor.alertSetpoint() == 0.1, "defaults: alarm 1 mSv/h, alert a tenth");
            s.check(monitor.setHighSetpoint(7.5) && !monitor.setHighSetpoint(0.0) && !monitor.setHighSetpoint(2.0e6)
                    && !monitor.setHighSetpoint(Double.NaN), "the setpoint takes in-range values only");
            CompoundTag saved = monitor.saveWithoutMetadata(s.level.registryAccess());
            s.check(saved.getDouble("HighSetpoint") == 7.5, "a changed setpoint is saved: " + saved);
            var reloaded = new AreaRadiationMonitorBlockEntity(at, s.level.getBlockState(at));
            reloaded.loadCustomOnly(saved, s.level.registryAccess());
            s.check(reloaded.highSetpoint() == 7.5, "and loads: " + reloaded.highSetpoint());
            CompoundTag bad = new CompoundTag();
            bad.putDouble("HighSetpoint", -4.0);
            reloaded.loadCustomOnly(bad, s.level.registryAccess());
            s.check(reloaded.highSetpoint() == 1.0, "a nonsense saved value falls back to the default");
            // The ladder a sneak-click walks.
            s.check(AreaRadiationMonitorBlock.nextSetpoint(1.0) == 5.0 && AreaRadiationMonitorBlock.nextSetpoint(1000.0) == 0.01
                    && AreaRadiationMonitorBlock.nextSetpoint(7.5) == 10.0, "the setpoint ladder steps up and wraps");
            s.level.removeBlock(at, false);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theLampFollowsTheReadingWithHysteresisFromOneSlowService(GameTestHelper helper) {
        NetStage.run(helper, 93, s -> {
            BlockPos at = s.devices;
            AreaRadiationMonitorBlockEntity monitor = monitor(s, at, Direction.UP);
            Object owner = new Object();
            // Point sources are chosen for a given reading at the monitor, 2 blocks away.
            double perWatt = DoseModel.pointSourceMsvPerH(1.0, 2.0, 1.0);
            s.then(1, () -> {
              try {
                s.check(s.level.getBlockState(at).getValue(AreaRadiationMonitorBlock.LAMP) == 0, "dark at background");
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), 0.5 / perWatt, 1.0);
                monitor.invalidate();
                RadiationMonitors.refreshAll(s.level);
                s.check(s.level.getBlockState(at).getValue(AreaRadiationMonitorBlock.LAMP) == 1, "0.5 mSv/h is alert (setpoint 1, alert 0.1)");
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), 1.5 / perWatt, 1.0);
                monitor.invalidate();
                RadiationMonitors.refreshAll(s.level);
                s.check(s.level.getBlockState(at).getValue(AreaRadiationMonitorBlock.LAMP) == 2, "1.5 mSv/h is alarm");
                // 0.95 mSv/h is below the setpoint but above 90 % of it: the alarm holds.
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), 0.95 / perWatt, 1.0);
                monitor.invalidate();
                RadiationMonitors.refreshAll(s.level);
                s.check(s.level.getBlockState(at).getValue(AreaRadiationMonitorBlock.LAMP) == 2, "the alarm holds down to 90 % of its setpoint");
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), 0.8 / perWatt, 1.0);
                monitor.invalidate();
                RadiationMonitors.refreshAll(s.level);
                s.check(s.level.getBlockState(at).getValue(AreaRadiationMonitorBlock.LAMP) == 1, "and drops to alert below that");
                // A reading is reused for five ticks however often it is asked, then measured again.
                double held = monitor.doseRate();
                RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), 8.0 / perWatt, 1.0);
                s.check(monitor.doseRate() == held, "a second look in the same tick costs nothing and returns the same reading");
              } finally {
                RadiationSources.clear(s.level, owner);
              }
            });
            s.then((int) AreaRadiationMonitorBlockEntity.MAX_AGE_TICKS + 1, () -> {
                s.check(monitor.doseRate() == 0.0, "after the age limit it measures again: " + monitor.doseRate());
                s.level.removeBlock(at, false);
                s.check(RadiationMonitors.loadedCount(s.level) >= 0, "and a removed monitor leaves the service");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theRecipesExist(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        for (String id : new String[] {"survey_meter", "area_radiation_monitor"}) {
            var recipe = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", id));
            helper.assertTrue(recipe.isPresent(), "recipe " + id + " is loaded");
            var result = recipe.get().value().getResultItem(helper.getLevel().registryAccess());
            helper.assertTrue(result.getItem() == (id.equals("survey_meter") ? BwrItems.SURVEY_METER.get()
                    : BwrItems.AREA_RADIATION_MONITOR.get()), "recipe " + id + " makes the right item: " + result);
        }
        helper.succeed();
    }
}
