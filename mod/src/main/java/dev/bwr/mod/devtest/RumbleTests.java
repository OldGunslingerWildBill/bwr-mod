package dev.bwr.mod.devtest;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.rumble.Rumble;
import dev.bwr.mod.rumble.RumblePayload;
import dev.bwr.mod.rumble.RumbleServer;
import dev.bwr.mod.steam.AdsReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;

/**
 * The rumble (RUMBLE.md): the distance law and its calibration, delivery to players (any distance, same dimension only, bounded packet
 * rate, bounded cost), and the two real hooks, the suppression pool and the turbine auxiliaries. The camera and the sound are checked
 * by the client check {@code RumbleClientCheck}.
 *
 * <p>Every test that sends packets replaces the sender with a recorder for its own length and puts it back; none sends a real packet.
 */
@GameTestHolder("bwrrumble")
@PrefixGameTestTemplate(false)
public final class RumbleTests {
    private RumbleTests() {}

    private static final int SHIFT = Math.floorMod(java.util.UUID.randomUUID().hashCode(), 100_000) * 64;

    private record Sent(ServerPlayer player, RumblePayload payload, long tick) {}

    /** Runs a body with a recording sender and a clean source table, restoring both. */
    private static void withRecorder(List<Sent> sent, long[] clock, Runnable body) {
        BiConsumer<ServerPlayer, RumblePayload> old = RumbleServer.setSender((p, pl) -> sent.add(new Sent(p, pl, clock[0])));
        RumbleServer.clearAll();
        try {
            body.run();
        } finally {
            RumbleServer.setSender(old);
            RumbleServer.clearAll();
        }
    }

    /** A server player that was never logged in (the game's own mock player fires the login events, which a test server cannot answer). */
    private static ServerPlayer fakePlayer(ServerLevel level) {
        return new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "rumble_test"),
                net.minecraft.server.level.ClientInformation.createDefault());
    }

    private static double reliefValveW() {
        return RumbleLaw.RELIEF_VALVE_KG_PER_S == SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S
                ? SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S
                        * Saturation.vapourEnthalpyKJPerKg(Saturation.psiaFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG)) * 1000.0
                : Double.NaN;
    }

    // ------------------------------------------------------------------------------------------------ the law

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void intensityRisesWithPowerAndFallsWithDistance(GameTestHelper h) {
        for (Rumble.Kind kind : Rumble.Kind.values()) {
            double last = -1;
            for (double w = 1e5; w < 1e11; w *= 2) {
                double v = Rumble.intensityAt(kind, w, 30);
                h.assertTrue(v >= last, kind + " fell with power at " + w);
                last = v;
            }
            last = Double.MAX_VALUE;
            for (double r = 1; r < 30_000; r *= 1.5) {
                double v = Rumble.intensityAt(kind, 5e8, r);
                h.assertTrue(v <= last, kind + " rose with distance at " + r);
                last = v;
            }
            h.assertTrue(Rumble.intensityAt(kind, 0, 1) == 0 && Rumble.intensityAt(kind, -1, 1) == 0 && Rumble.intensityAt(kind, 5e8, 1e12) == 0,
                    kind + " rumbles with no power, or from the end of the world");
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void everyReliefValveOpenIsAtPerceptionAt8000Blocks(GameTestHelper h) {
        double one = reliefValveW();
        h.assertTrue(Double.isFinite(one) && one > 5e7 && one < 2e8, "one relief valve at rated capacity condenses " + one + " W");
        double bank = RumbleLaw.LARGE_PLANT_RELIEF_VALVES * one;
        double at8000 = Rumble.Kind.POOL.profile.raw(bank, 8000);
        h.assertTrue(Math.abs(at8000 - RumbleLaw.PERCEPTION) <= 0.05 * RumbleLaw.PERCEPTION,
                "the whole bank (" + bank / 1e6 + " MW) is " + at8000 + " at 8,000 blocks, the limit is " + RumbleLaw.PERCEPTION);
        h.assertTrue(Rumble.intensityAt(Rumble.Kind.POOL, bank, 7600) > 0 && Rumble.intensityAt(Rumble.Kind.POOL, bank, 8400) == 0,
                "the bank should be felt at 7,600 and not at 8,400 blocks");
        // And through the delivery path, with a player that far away and a source nowhere near them (no chunk is loaded there).
        var l = h.getLevel();
        var player = fakePlayer(h.getLevel());
        List<Sent> sent = new ArrayList<>();
        withRecorder(sent, new long[1], () -> {
            player.setPos(8000.0 * 0.95 + SHIFT, 100, 0);
            long now = l.getGameTime();
            Rumble.sustainAt(l, 1, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, bank, now);
            RumbleServer.step(now, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1 && sent.get(0).payload().pool() > 0, "a player 7,600 blocks away was not told: " + sent.size());
            h.assertTrue(sent.get(0).payload().distance() > 7500 && sent.get(0).payload().dx() < -0.99f, "direction/distance wrong: " + sent.get(0).payload());
            sent.clear();
            player.setPos(8000.0 * 1.05 + SHIFT, 100, 0);
            RumbleServer.step(now + 5, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1 && sent.get(0).payload().isNone(), "a player 8,400 blocks away should be told it stopped");
        });
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void oneReliefValveIsFeltForHundredsOfBlocksAndClearlyAtThePool(GameTestHelper h) {
        double one = reliefValveW();
        double range = Rumble.Kind.POOL.profile.range(one);
        h.assertTrue(range > 300 && range < 700, "one relief valve is felt out to " + range + " blocks");
        double near = Rumble.intensityAt(Rumble.Kind.POOL, one, 10);
        h.assertTrue(RumbleLaw.level(near) > 0.3 && RumbleLaw.shakeAmplitude(near) > 0.2, "10 blocks from one open valve the level is " + RumbleLaw.level(near));
        double bankNear = Rumble.intensityAt(Rumble.Kind.POOL, 18 * one, 10);
        h.assertTrue(RumbleLaw.shakeAmplitude(bankNear) > 0.9, "beside the whole bank the shake is " + RumbleLaw.shakeAmplitude(bankNear));
        double turbineHall = Rumble.intensityAt(Rumble.Kind.TURBINE, RumbleLaw.turbinePowerW(7.1 + 2.25, 1500), 20);
        h.assertTrue(RumbleLaw.level(turbineHall) > 0.3, "a turbine at the 7.1 mm/s level shakes its hall: " + RumbleLaw.level(turbineHall));
        double turbineRange = Rumble.Kind.TURBINE.profile.range(RumbleLaw.turbinePowerW(15.5, 525));
        h.assertTrue(turbineRange > 30 && turbineRange < 150, "a badly bowed turbine is heard " + turbineRange + " blocks");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ delivery

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void whenTheSourcesStopEveryoneIsToldOnceAndThenNothing(GameTestHelper h) {
        var l = h.getLevel();
        var player = fakePlayer(h.getLevel());
        List<Sent> sent = new ArrayList<>();
        withRecorder(sent, new long[1], () -> {
            player.setPos(SHIFT + 40, 100, 0);
            long now = l.getGameTime();
            Rumble.sustainAt(l, 7, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, 2e8, now);
            RumbleServer.step(now, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1 && !sent.get(0).payload().isNone(), "no state for a player 40 blocks away: " + sent.size());
            // The owner stops calling; the source lapses.
            long later = now + Rumble.LAPSE_TICKS + 10;
            for (long t = now + 5; t <= later + 40; t += 5) RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            long nonzero = sent.stream().filter(s -> !s.payload().isNone()).count();
            h.assertTrue(nonzero <= 2 && sent.size() == nonzero + 1 && sent.get(sent.size() - 1).payload().isNone(),
                    "after the source lapsed the player should be sent exactly one zero: " + sent.size() + " packets, " + nonzero + " non-zero");
            h.assertTrue(RumbleServer.sourceCount(l) == 0, "a lapsed source stayed in the table");
            int count = sent.size();
            for (long t = later + 45; t < later + 200; t += 5) RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == count, "packets kept coming after the zero");
            // Released at once.
            Rumble.sustain(l, new BlockPos(SHIFT, 100, 0), Rumble.Kind.TURBINE, 1e7);
            h.assertTrue(RumbleServer.sourceCount(l) == 1, "sustain did not register");
            Rumble.release(l, new BlockPos(SHIFT, 100, 0), Rumble.Kind.TURBINE);
            h.assertTrue(RumbleServer.sourceCount(l) == 0, "release did not remove");
            Rumble.sustain(l, new BlockPos(SHIFT, 100, 0), Rumble.Kind.TURBINE, 0);
            h.assertTrue(RumbleServer.sourceCount(l) == 0, "zero power registered a source");
        });
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void otherDimensionsGetNothing(GameTestHelper h) {
        var l = h.getLevel();
        ServerLevel nether = l.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "this server has no nether to test with");
        var inNether = fakePlayer(h.getLevel());
        var inOverworld = fakePlayer(h.getLevel());
        List<Sent> sent = new ArrayList<>();
        withRecorder(sent, new long[1], () -> {
            inNether.setPos(SHIFT + 5, 100, 0);
            inOverworld.setPos(SHIFT + 5, 100, 0);
            long now = l.getGameTime();
            Rumble.sustainAt(l, 1, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, 5e9, now);
            RumbleServer.step(now, lv -> lv == l ? List.of(inOverworld) : lv == nether ? List.of(inNether) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1 && sent.get(0).player() == inOverworld, "only the overworld player may hear an overworld source: " + sent.size());
            // The player now in the overworld steps through a portal: the next pass has them in the nether list only.
            sent.clear();
            RumbleServer.step(now + 5, lv -> lv == l ? List.<ServerPlayer>of() : lv == nether ? List.of(inOverworld) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1 && sent.get(0).player() == inOverworld && sent.get(0).payload().isNone(),
                    "a player who left the dimension should be told it stopped, and nothing else");
            // And a source in the nether is not felt in the overworld.
            sent.clear();
            RumbleServer.clearAll();
            Rumble.sustainAt(nether, 2, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, 5e9, now);
            RumbleServer.step(now + 10, lv -> lv == l ? List.of(inOverworld) : lv == nether ? List.<ServerPlayer>of() : List.<ServerPlayer>of());
            h.assertTrue(sent.isEmpty(), "a nether source was sent to an overworld player");
        });
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 40)
    public static void packetRateIsBoundedAndOnlyOnChange(GameTestHelper h) {
        var l = h.getLevel();
        var player = fakePlayer(h.getLevel());
        List<Sent> sent = new ArrayList<>();
        long[] clock = new long[1];
        withRecorder(sent, clock, () -> {
            player.setPos(SHIFT + 60, 100, 0);
            long start = l.getGameTime();
            // A steady source for ten seconds: one packet to begin and one heartbeat every two seconds, not one per pass.
            for (long t = start; t < start + 200; t += 5) {
                clock[0] = t;
                Rumble.sustainAt(l, 1, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, 3e8, t);
                RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            }
            h.assertTrue(sent.size() >= 1 && sent.size() <= 1 + 200 / RumbleServer.HEARTBEAT_TICKS, "a steady source sent " + sent.size() + " packets in 200 ticks");
            // A source that wobbles by a quarter every pass cannot be told faster than four times a second.
            sent.clear();
            long base = start + 200;
            for (long t = base; t < base + 200; t += 5) {
                clock[0] = t;
                Rumble.sustainAt(l, 1, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, ((t / 5) % 2 == 0) ? 1.5e8 : 4.5e8, t);
                RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            }
            h.assertTrue(sent.size() <= 200 / RumbleServer.PERIOD_TICKS, "a wobbling source sent " + sent.size() + " packets in 200 ticks");
            for (int i = 0; i < sent.size(); i++) {
                int inWindow = 0;
                for (var s : sent) if (s.tick() >= sent.get(i).tick() && s.tick() < sent.get(i).tick() + 20) inWindow++;
                h.assertTrue(inWindow <= 4, "more than four packets in one second: " + inWindow);
            }
            // A change in the source is told at once.
            sent.clear();
            long t = base + 200;
            clock[0] = t;
            Rumble.sustainAt(l, 1, new Vec3(SHIFT, 100, 0), Rumble.Kind.POOL, 9e9, t);
            RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1, "a thirty-fold change in power was not sent at once");
        });
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 100)
    public static void serverCostIsBoundedWithManyPlayersAndSources(GameTestHelper h) {
        var l = h.getLevel();
        List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            var p = fakePlayer(h.getLevel());
            p.setPos(SHIFT + i * 300.0, 100, i * 17.0);
            players.add(p);
        }
        int[] packets = {0};
        BiConsumer<ServerPlayer, RumblePayload> old = RumbleServer.setSender((p, pl) -> packets[0]++);
        RumbleServer.clearAll();
        try {
            long now = l.getGameTime();
            for (int i = 0; i < 1500; i++)
                Rumble.sustainAt(l, i, new Vec3(SHIFT + (i % 40) * 500.0, 100, (i / 40) * 90.0), i % 3 == 0 ? Rumble.Kind.TURBINE : Rumble.Kind.POOL, 1e7 * (1 + i % 50), now);
            h.assertTrue(RumbleServer.sourceCount(l) == RumbleServer.MAX_SOURCES_PER_LEVEL, "the table is not capped: " + RumbleServer.sourceCount(l));
            long t0 = System.nanoTime();
            int passes = 40;
            for (int i = 0; i < passes; i++) {
                for (var p : players) p.setPos(p.getX() + 1.5, 100, p.getZ());
                RumbleServer.step(now + i, lv -> lv == l ? players : List.<ServerPlayer>of());
            }
            double perPassMs = (System.nanoTime() - t0) / 1e6 / passes;
            h.assertTrue(perPassMs < 25, "one delivery pass with 64 players and " + RumbleServer.MAX_SOURCES_PER_LEVEL + " sources took " + perPassMs + " ms");
            h.assertTrue(packets[0] <= 64 * passes, "more than one packet per player per pass: " + packets[0]);
            // Nothing active costs nothing: no allocation of a pass at all.
            RumbleServer.clearAll();
            long t1 = System.nanoTime();
            for (int i = 0; i < 100_000; i++) RumbleServer.step(now, lv -> players);
            h.assertTrue((System.nanoTime() - t1) / 1e6 < 100, "an idle delivery pass is not free");
        } finally {
            RumbleServer.setSender(old);
            RumbleServer.clearAll();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 20)
    public static void impulsesDieAwayAndAreFeltFartherWithMoreEnergy(GameTestHelper h) {
        var l = h.getLevel();
        var player = fakePlayer(h.getLevel());
        List<Sent> sent = new ArrayList<>();
        withRecorder(sent, new long[1], () -> {
            long now = l.getGameTime();
            player.setPos(SHIFT + 200, 100, 0);
            Rumble.impulse(l, new Vec3(SHIFT, 100, 0), 1e6);
            RumbleServer.step(now, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.isEmpty(), "1 MJ should not reach 200 blocks");
            RumbleServer.clearAll();
            Rumble.impulse(l, new Vec3(SHIFT, 100, 0), 1.25e8);
            RumbleServer.step(now, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.size() == 1, "a 100 t body at 50 m/s (125 MJ) should reach 200 blocks");
            float first = sent.get(0).payload().pool();
            for (long t = now + 5; t < now + 400; t += 5) RumbleServer.step(t, lv -> lv == l ? List.of(player) : List.<ServerPlayer>of());
            h.assertTrue(sent.get(sent.size() - 1).payload().isNone() && first > 0, "an impulse should die away");
            h.assertTrue(RumbleServer.sourceCount(l) == 0, "an impulse should leave the table");
        });
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the hooks

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 200)
    public static void aPoolTakingSteamRumblesByTheEnthalpyItCondenses(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var o = new BlockPos(41000 + SHIFT, 190, 41000);
        var pool = SuppressionTankRegressionTests.build(l, o);
        pool.water(false).fill(new FluidStack(Fluids.WATER, 50_000), EXECUTE);
        RumbleServer.clearAll();
        var data = l.getServer().getWorldData().overworldData();
        long real = l.getGameTime();
        try {
            long t = (real / 5 + 2) * 5;
            data.setGameTime(t);
            pool.reportSteamKgPerS(o.offset(30, 0, 0), t, 30.0, 1000.0);
            dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
            double w = RumbleServer.powerOf(l, Rumble.keyOf(pool.getBlockPos(), Rumble.Kind.POOL));
            double expect = RumbleLaw.condensingPowerW(30.0, Saturation.vapourEnthalpyKJPerKg(Saturation.psiaFromPsig(1000.0)));
            h.assertTrue(w > 0 && Math.abs(w - expect) < 0.02 * expect, "a pool taking 30 kg/s at 1000 psig rumbles at " + w + " W, expected " + expect);
            // No steam: the next refresh removes the source.
            t += 5;
            data.setGameTime(t);
            dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
            // (the report from the earlier tick is still fresh for a few ticks, so look again once it has gone stale)
            t += 200;
            data.setGameTime(t - t % 5);
            dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
            h.assertTrue(RumbleServer.powerOf(l, Rumble.keyOf(pool.getBlockPos(), Rumble.Kind.POOL)) == 0, "a pool with no steam still rumbles");
        } finally {
            data.setGameTime(real);
            RumbleServer.clearAll();
        }
        h.succeed();
    }

    /** The real chain: an open relief valve passes steam, the pool condenses it, the pool rumbles by what the valve passed. */
    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble", timeoutTicks = 300)
    public static void anOpenReliefValveMakesItsPoolRumble(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var o = new BlockPos(42000 + SHIFT, 190, 42000);
        var pool = SuppressionTankRegressionTests.build(l, o);
        pool.water(false).fill(new FluidStack(Fluids.WATER, 50_000), EXECUTE);
        var vo = o.offset(15, 0, -5);
        for (int x = (vo.getX() - 2) >> 4; x <= (vo.getX() + 28) >> 4; x++) for (int z = (vo.getZ() - 5) >> 4; z <= (vo.getZ() + 18) >> 4; z++) l.getChunk(x, z);
        var build = PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel", ServerLevel.class, BlockPos.class, int.class);
        build.setAccessible(true);
        build.invoke(null, l, vo, 11);
        var cp = vo.offset(-1, 3, 2);
        l.setBlock(cp, BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 2);
        var np = vo.offset(11, 6, 5);
        l.setBlock(np, BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 2);
        var r = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) l.getBlockEntity(cp);
        var validate = r.getClass().getDeclaredMethod("revalidate", Level.class);
        validate.setAccessible(true);
        validate.invoke(r, l);
        h.assertTrue(r.isFormed(), "steam source fixture incomplete");
        var n = (dev.bwr.mod.reactor.RpvSteamOutletBlockEntity) l.getBlockEntity(np);
        n.noteController(cp);
        n.setPosition(1);
        var f = r.core().getClass().getDeclaredField("vessel");
        f.setAccessible(true);
        ((dev.bwr.core.thermal.PressureVessel) f.get(r.core())).restorePressurePsig(1000);
        pipe(l, np.east(), np.east(2));
        pipe(l, np.east(2), new BlockPos(np.getX() + 2, np.getY(), o.getZ() - 5));
        pipe(l, new BlockPos(np.getX() + 2, np.getY(), o.getZ() - 5), o.offset(2, 6, -5));
        pipe(l, o.offset(2, 6, -5), o.offset(2, 3, -5));
        pipe(l, o.offset(2, 3, -4), o.offset(2, 3, -4));
        var vp = o.offset(2, 3, -3);
        l.setBlock(vp, BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState().setValue(AdsReliefValveBlock.FACING, Direction.EAST), 3);
        pipe(l, o.offset(2, 2, -3), o.offset(2, 2, -1));
        var relief = (SafetyReliefValveBlockEntity) l.getBlockEntity(vp);
        n.setComputerControlled(true);
        n.setPosition(1);
        n.refreshAttachment(l);
        SuppressionBasinRegressionTests.validate(pool);
        relief.revalidateDischarge(l);
        h.assertTrue(relief.isDischargeSubmerged(), "relief valve outlet does not reach the tank: " + relief.dischargePath());
        long real = l.getGameTime();
        var data = l.getServer().getWorldData().overworldData();
        RumbleServer.clearAll();
        try {
            long t = (real / 5 + 2) * 5;
            relief.setComputerControlled(true);
            relief.setOpen(true);
            data.setGameTime(t);
            n.flowKgPerS(1000);
            dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
            double flow = relief.getLastFlowKgPerS();
            h.assertTrue(flow > 1, "the open valve passed " + flow + " kg/s");
            double w = RumbleServer.powerOf(l, Rumble.keyOf(pool.getBlockPos(), Rumble.Kind.POOL));
            double psig = r.core().getPressurePsig();
            double expect = RumbleLaw.condensingPowerW(flow, Saturation.vapourEnthalpyKJPerKg(Saturation.psiaFromPsig(psig)));
            h.assertTrue(w > 0 && Math.abs(w - expect) < 0.03 * expect, "the pool rumbles at " + w + " W for a valve passing " + flow + " kg/s at " + psig + " psig (expected " + expect + ")");
            // Shut: the rumble goes at the next refresh.
            relief.setOpen(false);
            t += 5;
            data.setGameTime(t);
            n.flowKgPerS(1000);
            dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
            h.assertTrue(RumbleServer.powerOf(l, Rumble.keyOf(pool.getBlockPos(), Rumble.Kind.POOL)) == 0, "a shut valve's pool still rumbles");
        } finally {
            data.setGameTime(real);
            RumbleServer.clearAll();
        }
        h.succeed();
    }

    private static void pipe(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(), 3);
    }

    @GameTest(template = "empty", templateNamespace = "bwrrumble", batch = "rumble_turbine", timeoutTicks = 400)
    public static void aBowedRotorMakesItsTrainRumbleAndAStraightOneOnlyHums(GameTestHelper h) {
        var root = new BlockPos(88000 + SHIFT, 245, 88000);
        var l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, TurbineAuxTests.Fx.bounds(root), () -> {
            // The clock is restored to what it is now, when the body starts: the wait for the chunks has gone by on the real clock,
            // and putting back the time from before it would turn the level clock back by that many ticks for every other GameTest
            // of the batch (their delays and waits run on it, and items they dropped keep falling on the real ticks).
            long time = l.getGameTime();
            TurbineAuxTests.Fx f = null;
            RumbleServer.clearAll();
            try {
                f = new TurbineAuxTests.Fx(l, root);
                var hp = f.addHp();
                hp.markAuxRequired();
                var tank = f.tank(10_000);
                f.shaftPump();
                tank.setAcPump(true);
                long key = Rumble.keyOf(hp.getBlockPos().asLong() < f.gen.getBlockPos().asLong() ? hp.getBlockPos() : f.gen.getBlockPos(), Rumble.Kind.TURBINE);
                // Straight rotor at speed: a hum, in its own hall only.
                double straight = 0;
                for (int i = 0; i < 40; i++) {
                    tank.acEnergy.receiveEnergy(10_000, false);
                    f.gen.rpm = 1400;
                    hp.rpm = 1400;
                    f.tick(f.gen, 1);
                    straight = Math.max(straight, RumbleServer.powerOf(l, key));
                }
                h.assertTrue(straight > 0 && Rumble.Kind.TURBINE.profile.range(straight) < 25, "a healthy turning train should hum in its hall only: " + straight + " W, range "
                        + Rumble.Kind.TURBINE.profile.range(straight));
                // Fully bowed through the critical band.
                f.edit(hp, t -> t.putDouble("Bow", 1.0));
                double worst = 0, worstVib = 0;
                for (int rpm = 300; rpm <= 700; rpm += 1) {
                    tank.acEnergy.receiveEnergy(10_000, false);
                    f.gen.rpm = rpm;
                    hp.rpm = rpm;
                    f.tick(f.gen, 1);
                    worst = Math.max(worst, RumbleServer.powerOf(l, key));
                    worstVib = Math.max(worstVib, hp.vibration);
                }
                h.assertTrue(worstVib > 7.1, "the bowed rotor did not shake: " + worstVib);
                h.assertTrue(worst > 10 * Math.max(straight, 1) && Rumble.Kind.TURBINE.profile.range(worst) > 50,
                        "a bowed rotor at the critical speed should rumble far harder: " + worst + " W vs " + straight + " W straight, range " + Rumble.Kind.TURBINE.profile.range(worst));
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
                RumbleServer.clearAll();
            }
        });
    }
}
