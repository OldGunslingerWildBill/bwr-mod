package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.ShaftDynamics;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.core.turbine.TurbineFire;
import dev.bwr.mod.power.BlastSoundPayload;
import dev.bwr.mod.power.BlastSounds;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.power.TurbineExcursion;
import dev.bwr.mod.power.TurbineFires;
import dev.bwr.mod.power.TurbineMissile;
import dev.bwr.mod.power.TurbineSounds;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * What a turbine accident sounds like (TURBINE-MAINTENANCE.md, "Sounds"): a blade or disc thrown bangs at its wheel and lands with a
 * metallic impact where it stops, a shaft excursion's tearing, boom and roll start at its seat, the whine of a fast LP follows its speed,
 * and a burning section roars for as long as it burns. Every test listens for the real thing: NeoForge's {@link PlayLevelSoundEvent}, posted
 * by {@code ServerLevel.playSound} for each sound the game would send, with its position, volume and pitch, so a test fails when the
 * mod stops calling {@code playSound} as well as when it calls it with the wrong sound or at the wrong place. The same hall as
 * {@link TurbineMaintenanceTests}: an LP facing east and a generator on its shaft, stepped a tick at a time, at its own far-away origin.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class TurbineSoundTests {
    private TurbineSoundTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(64000 + 160 * (40 + i), 180, 64000);
    }

    @FunctionalInterface
    private interface Body {
        void run(TurbineMaintenanceTests.Hall f, Heard heard) throws Exception;
    }

    /** One sound the game was asked to play. */
    private record Sound(String name, SoundSource source, Vec3 at, float volume, float pitch, float range) {
    }

    /** Collects the mod's sounds played while a test runs. */
    public static final class Heard {
        final List<Sound> all = new ArrayList<>();

        @SubscribeEvent
        public void sound(PlayLevelSoundEvent.AtPosition e) {
            var sound = e.getSound().value();
            if (sound.getLocation().getNamespace().equals("bwr")) {
                all.add(new Sound(sound.getLocation().getPath(), e.getSource(), e.getPosition(), e.getNewVolume(), e.getNewPitch(), sound.getRange(e.getNewVolume())));
            }
        }

        List<Sound> of(String name) {
            return all.stream().filter(s -> s.name.equals(name)).toList();
        }

        void clear() {
            all.clear();
        }
    }

    private static void run(GameTestHelper h, BlockPos root, Body body) {
        var l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, TurbineMaintenanceTests.Hall.bounds(root), () -> {
            long time = l.getGameTime();
            TurbineMaintenanceTests.Hall f = null;
            var heard = new Heard();
            NeoForge.EVENT_BUS.register(heard);
            try {
                f = new TurbineMaintenanceTests.Hall(l, root);
                body.run(f, heard);
                h.succeed();
            } finally {
                NeoForge.EVENT_BUS.unregister(heard);
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    private static void floor(TurbineMaintenanceTests.Hall f) {
        for (var p : BlockPos.betweenClosed(f.root.offset(-20, -1, -14), f.root.offset(20, -1, 14))) {
            f.l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2 | 16);
        }
    }

    private static void near(GameTestHelper h, Vec3 expected, Vec3 actual, String what) {
        h.assertTrue(expected.distanceTo(actual) < 1e-6, what + ": expected " + expected + ", at " + actual);
    }

    /** Ticks until the next one is a whole second of game time (the tick the once-a-second steps run on), without running it. */
    private static void toTheSecond(TurbineMaintenanceTests.Hall f) {
        while ((f.t + 1) % 20 != 0) f.tick(1);
    }

    // ------------------------------------------------------------------------------------------------ the blasts (kilometres)

    /** What one fake player was told. */
    private record Told(ServerPlayer player, BlastSoundPayload payload) {
    }

    /** Players that are not in the world, at places of the test's choosing, and what {@link BlastSounds} sent them. */
    public static final class Blasts implements AutoCloseable {
        final ServerLevel level;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<Told> told = new ArrayList<>();
        private final java.util.function.BiConsumer<ServerPlayer, BlastSoundPayload> oldSender;
        private final java.util.function.Function<ServerLevel, ? extends java.util.Collection<ServerPlayer>> oldPlayers;

        Blasts(ServerLevel level) {
            this.level = level;
            oldSender = BlastSounds.setSender((p, payload) -> told.add(new Told(p, payload)));
            oldPlayers = BlastSounds.setPlayers(l -> l == level ? players : List.<ServerPlayer>of());
        }

        /** A player whose feet are at the given place (the eyes are 1.62 above). */
        ServerPlayer at(double x, double y, double z) {
            var p = new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "blast_test"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            p.setPos(x, y, z);
            players.add(p);
            return p;
        }

        List<BlastSoundPayload> to(ServerPlayer p) {
            return told.stream().filter(t -> t.player == p).map(Told::payload).toList();
        }

        BlastSoundPayload only(GameTestHelper h, ServerPlayer p, String what) {
            var list = to(p);
            h.assertTrue(list.size() == 1, what + ": expected one packet, got " + list.size());
            return list.get(0);
        }

        void clear() {
            told.clear();
        }

        @Override
        public void close() {
            BlastSounds.setSender(oldSender);
            BlastSounds.setPlayers(oldPlayers);
        }
    }

    @FunctionalInterface
    private interface BlastBody {
        void run(TurbineMaintenanceTests.Hall f, Heard heard, Blasts blasts) throws Exception;
    }

    private static void runBlast(GameTestHelper h, BlockPos root, BlastBody body) {
        run(h, root, (f, heard) -> {
            try (var blasts = new Blasts(f.l)) {
                body.run(f, heard, blasts);
            }
        });
    }

    private static double total(BlastSoundPayload p) {
        return p.clear() + p.distant() + p.muffled();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aBladeThrownBangsAtItsWheelAndImpactsWhereItStops(GameTestHelper h) {
        runBlast(h, at(0), (f, heard, blasts) -> {
            // bedrock 14 blocks across the floor to the south, in the line of a blade thrown level to the right of the facing
            for (int dx = -2; dx <= 6; dx++) {
                for (int dy = 0; dy <= 6; dy++) f.l.setBlock(f.root.offset(dx, dy, 14), Blocks.BEDROCK.defaultBlockState(), 2);
            }
            Vec3 wheel = TurbineMissile.wheel(f.lp, 0);
            var near = blasts.at(wheel.x, f.root.getY(), f.root.getZ() - 12.5);
            var mid = blasts.at(wheel.x, f.root.getY(), f.root.getZ() - 2000);
            var far = blasts.at(wheel.x, f.root.getY(), f.root.getZ() - 3200);
            f.rpm(ShaftDynamics.RATED_RPM);
            var r = PowerTrain.throwBlade(f.l, f.lp, 0, 0.0);
            h.assertTrue(r.stop() == TurbineMissile.Stop.UNBREAKABLE, "the blade stopped in the bedrock: " + r.stop());
            var bang = blasts.only(h, near, "a player in the hall");
            h.assertTrue(bang.kind() == BlastSounds.Kind.BANG.ordinal() && bang.pitch() == TurbineSounds.BANG_BLADE_PITCH, "a blade's bang: " + bang);
            near(h, wheel, new Vec3(bang.x(), bang.y(), bang.z()), "the bang is at the wheel the blade left");
            h.assertTrue(bang.clear() > 0.99 && bang.distant() == 0 && bang.muffled() == 0, "full and clear beside the turbine: " + bang);
            h.assertTrue(bang.delayTicks() == BlastSounds.delayTicks(near.getEyePosition().distanceTo(wheel)) && bang.delayTicks() <= 2, "a tick of delay: " + bang.delayTicks());
            var distant = blasts.only(h, mid, "a player 2 km away");
            h.assertTrue(distant.clear() == 0 && distant.distant() > 0.05 && distant.muffled() >= 0 && total(distant) < 0.2, "faint and dull at 2 km: " + distant);
            h.assertTrue(distant.delayTicks() == Math.round(mid.getEyePosition().distanceTo(wheel) / 343.0 * 20), "about six seconds later: " + distant.delayTicks());
            h.assertTrue(blasts.to(far).isEmpty(), "nothing is sent to a player past the bang's 3 km");
            var impact = heard.of("turbine_missile_impact");
            h.assertTrue(impact.size() == 1, "one impact: " + heard.all);
            near(h, r.end(), impact.get(0).at, "the impact is where the blade stopped");
            h.assertTrue(impact.get(0).at.z - wheel.z > 10, "well away from the bang: " + impact.get(0).at);
            h.assertTrue(impact.get(0).volume == TurbineSounds.IMPACT_VOLUME && impact.get(0).range == 80f, "a vanilla sound with a range of 80 blocks: " + impact.get(0));
            h.assertTrue(heard.all.stream().noneMatch(s -> s.name.startsWith("turbine_missile_bang")), "and the bang is not a vanilla sound: " + heard.all);
            f.tick(40);
            h.assertTrue(blasts.to(near).size() == 1, "a section throws, and bangs, once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aBurstDiscBangsDeeperAndAPieceThatNeverLandsMakesNoImpact(GameTestHelper h) {
        runBlast(h, at(1), (f, heard, blasts) -> {
            Vec3 wheel = TurbineMissile.wheel(f.lp, 0);
            var near = blasts.at(wheel.x, f.root.getY(), f.root.getZ() - 12.5);
            f.rpm(TurbineBlades.BURST_RPM + 10);
            var r = PowerTrain.burst(f.l, f.lp, 0, Math.PI / 2); // straight up, into the sky
            h.assertTrue(r.stop() == TurbineMissile.Stop.OUT_OF_WORLD || r.stop() == TurbineMissile.Stop.RANGE, "the fragment flew out of the world: " + r.stop());
            var bang = blasts.only(h, near, "a player in the hall");
            near(h, wheel, new Vec3(bang.x(), bang.y(), bang.z()), "at the wheel");
            h.assertTrue(bang.kind() == BlastSounds.Kind.BANG.ordinal() && bang.pitch() == TurbineSounds.BANG_DISC_PITCH && bang.pitch() < TurbineSounds.BANG_BLADE_PITCH,
                    "a disc bangs deeper: " + bang);
            h.assertTrue(heard.of("turbine_missile_impact").isEmpty(), "it landed nowhere we know of: no impact sound");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aShaftExcursionTearsOutWithOneLongSoundAtItsSeatHeardForTenKilometres(GameTestHelper h) {
        runBlast(h, at(2), (f, heard, blasts) -> {
            floor(f);
            Vec3 seat = TurbineExcursion.seat(f.lp, 0);
            var near = blasts.at(seat.x, f.root.getY(), f.root.getZ() - 12.5);
            var km3 = blasts.at(seat.x, f.root.getY(), f.root.getZ() - 3500);
            var km9 = blasts.at(seat.x, f.root.getY(), f.root.getZ() - 9000);
            var km11 = blasts.at(seat.x, f.root.getY(), f.root.getZ() - 10500);
            f.rpm(TurbineBlades.EXCURSION_RPM + 20);
            PowerTrain.excursion(f.l, f.lp, 0);
            var e = blasts.only(h, near, "a player in the hall");
            near(h, seat, new Vec3(e.x(), e.y(), e.z()), "at the rotor half's seat in the LP");
            h.assertTrue(e.kind() == BlastSounds.Kind.EXCURSION.ordinal() && e.pitch() == 1.0f && e.clear() > 0.99, "full and clear beside it: " + e);
            var a = blasts.only(h, km3, "a player 3.5 km away");
            var b = blasts.only(h, km9, "a player 9 km away");
            h.assertTrue(a.clear() == 0 && a.distant() > 0.1 && a.muffled() == 0, "3.5 km: the distant file, still clear of the thud: " + a);
            h.assertTrue(b.muffled() > b.distant() && total(b) > 0 && total(b) < 0.03, "9 km: very faint, mostly a low thud: " + b);
            h.assertTrue(b.delayTicks() == BlastSounds.MAX_DELAY_TICKS && a.delayTicks() == BlastSounds.MAX_DELAY_TICKS && e.delayTicks() < 4, "the delay is capped at 8 s past 2.7 km: " + a.delayTicks() + ", " + b.delayTicks());
            h.assertTrue(blasts.to(km11).isEmpty(), "nothing past 10 km");
            h.assertTrue(heard.all.stream().noneMatch(s -> s.name.startsWith("turbine_excursion") || s.name.startsWith("turbine_missile")), "no vanilla sound for it: " + heard.all);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theGainFallsWithDistanceAndEachVariantTakesOverWhereItShould(GameTestHelper h) {
        for (var kind : BlastSounds.Kind.values()) {
            double last = 2;
            for (double d : new double[]{1, 20, kind.nearBlocks, 100, 300, 600, 1000, 2000, 2900, 4000, 6000, 8000, 9000, 9900, 12000}) {
                double g = BlastSounds.distanceGain(kind, d);
                h.assertTrue(g >= 0 && g <= 1 && g <= last, kind + " falls with distance: " + g + " at " + d);
                last = g;
                var m = BlastSounds.mix(kind, d, 0);
                h.assertTrue(Math.abs(m.total() - g) < 1e-9 && m.clear() >= 0 && m.distant() >= 0 && m.muffled() >= 0, kind + " the variants sum to the gain at " + d);
            }
            h.assertTrue(BlastSounds.distanceGain(kind, 1) == 1 && BlastSounds.distanceGain(kind, kind.range) == 0 && BlastSounds.distanceGain(kind, kind.range * 2) == 0,
                    kind + ": full beside it, nothing at its range");
            h.assertTrue(BlastSounds.mix(kind, 100, 0).clear() > 0.4 && BlastSounds.mix(kind, 100, 0).clear() < BlastSounds.mix(kind, 20, 0).clear(), kind + ": clear near");
            h.assertTrue(BlastSounds.mix(kind, 1000, 0).clear() == 0 && BlastSounds.mix(kind, 1000, 0).distant() > 0.1,
                    kind + ": the distant file over about 600 m, in the open");
        }
        h.assertTrue(BlastSounds.distanceGain(BlastSounds.Kind.BANG, 1000) > 0.15 && BlastSounds.distanceGain(BlastSounds.Kind.BANG, 2900) < 0.03,
                "the bang: clearly heard at 1 km, faint at 3 km");
        h.assertTrue(BlastSounds.distanceGain(BlastSounds.Kind.EXCURSION, 1000) > 0.2 && BlastSounds.distanceGain(BlastSounds.Kind.EXCURSION, 7000) > 0.07
                && BlastSounds.distanceGain(BlastSounds.Kind.EXCURSION, 9500) < 0.015, "the excursion: clear at 1 km, still there at 7 km, very faint near 10 km");
        // the excursion keeps some clarity at 7 km (the distant file, none of the muffled one); at 9.5 km it is the low thud
        var seven = BlastSounds.mix(BlastSounds.Kind.EXCURSION, 7000, 0);
        var nine = BlastSounds.mix(BlastSounds.Kind.EXCURSION, 9500, 0);
        h.assertTrue(seven.muffled() == 0 && seven.distant() > 0.05 && nine.muffled() > nine.distant(), "the low-pass deepens only near the end: " + seven + " " + nine);
        h.assertTrue(BlastSounds.delayTicks(0) == 0 && BlastSounds.delayTicks(343) == 20 && BlastSounds.delayTicks(10000) == BlastSounds.MAX_DELAY_TICKS, "343 blocks a second, capped at 8 s");
        h.assertTrue(BlastSounds.occlusion(0) == 0 && BlastSounds.occlusion(1) < BlastSounds.occlusion(3) && BlastSounds.occlusion(3) < BlastSounds.occlusion(8) && BlastSounds.occlusion(60) > 0.99,
                "more blocks, more muffled");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aPlayerBehindAWallHearsTheMuffledVariantAndOneInTheOpenTheClearOne(GameTestHelper h) {
        runBlast(h, at(6), (f, heard, blasts) -> {
            Vec3 wheel = TurbineMissile.wheel(f.lp, 0);
            // a wall four blocks thick north of the LP, a player behind it, another the same distance south in the open
            for (int x = -4; x <= 9; x++) {
                for (int y = 0; y <= 7; y++) {
                    for (int z = -10; z <= -7; z++) f.l.setBlock(f.root.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            var behind = blasts.at(wheel.x, f.root.getY(), f.root.getZ() - 14.5);
            var open = blasts.at(wheel.x, f.root.getY(), f.root.getZ() + 14.5);
            int walls = BlastSounds.solidBlocks(f.l, wheel, behind.getEyePosition());
            h.assertTrue(walls >= 3 && walls <= 5 && BlastSounds.solidBlocks(f.l, wheel, open.getEyePosition()) == 0,
                    "the ray counts the wall's four blocks and none of the turbine's own casing: " + walls + ", open " + BlastSounds.solidBlocks(f.l, wheel, open.getEyePosition()));
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 0, Math.PI / 2); // up through the roof, so the wall stays whole
            var b = blasts.only(h, behind, "behind the wall");
            var o = blasts.only(h, open, "in the open");
            h.assertTrue(o.clear() > 0.99 && o.muffled() == 0 && o.distant() == 0, "the clear one in the open: " + o);
            h.assertTrue(b.muffled() > 0.4 && b.muffled() > b.clear() && b.distant() == 0, "the muffled one behind the wall: " + b);
            h.assertTrue(total(b) < total(o) && total(b) > 0.6, "and quieter, but still really good: " + total(b) + " against " + total(o));
            // the same wall at a distance: the distant file, muffled more
            var m = BlastSounds.mix(BlastSounds.Kind.BANG, 14.5, walls);
            h.assertTrue(Math.abs(m.muffled() - b.muffled()) < 1e-6, "the packet is the law's mix");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aPlayerTwoKilometresAwayIsToldWithNoChunkLoadedAndTheRayStopsAtUnloadedChunks(GameTestHelper h) {
        runBlast(h, at(7), (f, heard, blasts) -> {
            Vec3 wheel = TurbineMissile.wheel(f.lp, 0);
            // a wall in the loaded hall, south of the LP; the player is 2 km south of it, in chunks nobody loaded
            for (int x = -4; x <= 9; x++) {
                for (int y = 0; y <= 7; y++) f.l.setBlock(f.root.offset(x, y, 9), Blocks.STONE.defaultBlockState(), 2);
            }
            var far = blasts.at(wheel.x, f.root.getY(), f.root.getZ() + 2000);
            BlockPos ear = BlockPos.containing(far.getEyePosition());
            var chunks = f.l.getChunkSource();
            h.assertTrue(chunks.getChunkNow(ear.getX() >> 4, ear.getZ() >> 4) == null, "nobody loaded the chunk the player is in");
            int loaded = chunks.getLoadedChunksCount();
            int walls = BlastSounds.solidBlocks(f.l, wheel, far.getEyePosition());
            h.assertTrue(walls == 1, "the wall at the source's end counts (once) and the unloaded chunks add nothing: " + walls);
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 0, Math.PI / 2);
            var p = blasts.only(h, far, "a player 2 km away");
            h.assertTrue(p.delayTicks() == Math.round(far.getEyePosition().distanceTo(wheel) / 343.0 * 20) && p.delayTicks() > 100 && p.delayTicks() < BlastSounds.MAX_DELAY_TICKS,
                    "delayed by the sound's travel time: " + p.delayTicks());
            h.assertTrue(p.distant() + p.muffled() > 0.04 && p.clear() == 0, "dull and faint, never the clear one: " + p);
            h.assertTrue(chunks.getLoadedChunksCount() == loaded && chunks.getChunkNow(ear.getX() >> 4, ear.getZ() >> 4) == null, "no chunk was loaded to work it out");
            h.assertTrue(walls <= BlastSounds.MAX_SAMPLES, "the ray is bounded: at most " + BlastSounds.MAX_SAMPLES + " samples");
            // an open ray whose far part is all unloaded is open air
            int open = BlastSounds.solidBlocks(f.l, wheel, new Vec3(wheel.x, wheel.y, wheel.z - 5000));
            h.assertTrue(open == 0, "past the loaded chunks, open air: " + open);
        });
    }

    // ------------------------------------------------------------------------------------------------ the whine

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theWhineRisesInPitchAndVolumeWithTheShaftSpeedAndIsQuietAtRatedSpeed(GameTestHelper h) {
        run(h, at(3), (f, heard) -> {
            toTheSecond(f);
            heard.clear();
            f.rpm(ShaftDynamics.RATED_RPM);
            f.tick(1);
            var rated = heard.of("turbine_overspeed_whine");
            h.assertTrue(rated.size() == 1, "an LP at rated speed whines, once, and the generator does not: " + heard.all);
            Vec3 lp = new Vec3(f.root.getX() + 0.5, f.root.getY() + 2.5, f.root.getZ() + 0.5);
            near(h, lp, rated.get(0).at, "the whine is at the LP's shaft");
            h.assertTrue(Math.abs(rated.get(0).pitch - 1.0) < 0.02 && rated.get(0).volume < 0.15f, "subtle at rated speed: " + rated.get(0));
            h.assertTrue(rated.get(0).range == 16f, "short range at that volume: " + rated.get(0).range);
            float lastPitch = rated.get(0).pitch, lastVolume = rated.get(0).volume;
            for (double rpm : new double[]{1650, 1800, 1950}) {
                toTheSecond(f);
                heard.clear();
                f.rpm(rpm);
                f.tick(1);
                var w = heard.of("turbine_overspeed_whine");
                h.assertTrue(w.size() == 1, "one whine at " + rpm + " rpm: " + heard.all);
                float pitch = w.get(0).pitch, volume = w.get(0).volume;
                h.assertTrue(Math.abs(pitch - rpm / ShaftDynamics.RATED_RPM) < 0.02, "its pitch follows the speed at " + rpm + " rpm: " + pitch);
                h.assertTrue(pitch > lastPitch && volume > lastVolume, "higher and louder than at the speed before: " + w.get(0));
                lastPitch = pitch;
                lastVolume = volume;
            }
            h.assertTrue(TurbineSounds.whinePitch(0) == TurbineSounds.WHINE_MIN_PITCH && TurbineSounds.whinePitch(1e6) == TurbineSounds.WHINE_MAX_PITCH
                    && TurbineSounds.whineVolume(1.5 * ShaftDynamics.RATED_RPM) == TurbineSounds.WHINE_MAX_VOLUME, "the law's limits");
            toTheSecond(f);
            heard.clear();
            f.rpm(0.5 * ShaftDynamics.RATED_RPM);
            f.tick(1);
            h.assertTrue(heard.of("turbine_overspeed_whine").isEmpty(), "a slow shaft does not whine");
            f.rpm(ShaftDynamics.RATED_RPM);
            f.tick(5);
            h.assertTrue(heard.of("turbine_overspeed_whine").isEmpty(), "and the whine is played once a second, not on every tick");
        });
    }

    // ------------------------------------------------------------------------------------------------ fires

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void anOilFireRoarsEverySecondWhileItBurnsAndStopsWhenWaterPutsItOut(GameTestHelper h) {
        run(h, at(4), (f, heard) -> {
            floor(f);
            BlockPos tankAt = f.root.relative(Direction.SOUTH, 4);
            f.l.setBlock(tankAt, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.NORTH), 3);
            TurbineAuxBlock.attach(f.l, tankAt);
            ((TurbineAuxBlockEntity) f.l.getBlockEntity(tankAt)).addOil(5000);
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 0, -Math.PI / 2 + 0.2); // down through the lower casing: it cuts an oil line
            f.rpm(0);
            h.assertTrue(f.lp.burning() && f.lp.fire() == TurbineFire.Kind.OIL, "the oil caught: " + f.lp.fire());
            var first = heard.of("turbine_fire_oil");
            h.assertTrue(first.size() == 1, "the roar starts as it lights: " + heard.all);
            near(h, new Vec3(f.root.getX() + 0.5, f.root.getY() + 1.5, f.root.getZ() + 0.5), first.get(0).at, "at the section");
            h.assertTrue(first.get(0).volume == TurbineSounds.FIRE_OIL_VOLUME && first.get(0).source == SoundSource.BLOCKS, "its volume: " + first.get(0));
            toTheSecond(f);
            heard.clear();
            f.tick(60);
            h.assertTrue(heard.of("turbine_fire_oil").size() == 3 && heard.of("turbine_fire_hydrogen").isEmpty(),
                    "one oil roar for each second it burned: " + heard.of("turbine_fire_oil").size());
            h.assertTrue(heard.all.stream().noneMatch(s -> s.name.equals("turbine_overspeed_whine")), "the shaft is still: no whine");
            f.l.setBlock(TurbineFires.fireCells(f.lp).get(0), Blocks.WATER.defaultBlockState(), 3);
            toTheSecond(f);
            heard.clear();
            f.tick(1);
            h.assertTrue(!f.lp.burning(), "water put it out");
            f.tick(60);
            h.assertTrue(heard.all.isEmpty(), "and the roar stopped with it: " + heard.all);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void aHydrogenFireHissesWhileItBurnsAndStopsWhenItBurnsOff(GameTestHelper h) {
        run(h, at(5), (f, heard) -> {
            floor(f);
            f.l.setBlock(f.root.offset(8, 0, 3), BwrBlocks.GENERATOR_COOLING_SKID.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.NORTH), 3);
            TurbineAuxBlock.attach(f.l, f.root.offset(8, 0, 3));
            var cooling = (TurbineAuxBlockEntity) f.l.getBlockEntity(f.root.offset(8, 0, 3));
            cooling.addHydrogen(20);
            f.rpm(TurbineBlades.EXCURSION_RPM + 20);
            PowerTrain.excursion(f.l, f.lp, 0);
            h.assertTrue(f.gen.burning() && f.gen.fire() == TurbineFire.Kind.HYDROGEN, "the generator's hydrogen burns: " + f.gen.fire());
            var first = heard.of("turbine_fire_hydrogen");
            h.assertTrue(first.size() == 1 && heard.of("turbine_fire_oil").isEmpty(), "the hiss starts as it lights: " + heard.all);
            near(h, new Vec3(f.gen.getBlockPos().getX() + 0.5, f.gen.getBlockPos().getY() + 1.5, f.gen.getBlockPos().getZ() + 0.5), first.get(0).at, "at the generator");
            h.assertTrue(first.get(0).volume == TurbineSounds.FIRE_HYDROGEN_VOLUME, "its volume: " + first.get(0));
            toTheSecond(f);
            heard.clear();
            f.tick(40);
            h.assertTrue(heard.of("turbine_fire_hydrogen").size() == 2, "one hiss for each second it burned: " + heard.all);
            f.edit(f.gen, t -> t.putDouble("FireFuel", 0.1)); // less than the 0.2 kg a second it burns
            f.tick(40);
            h.assertTrue(!f.gen.burning(), "it burned off");
            heard.clear();
            f.tick(60);
            h.assertTrue(heard.of("turbine_fire_hydrogen").isEmpty(), "and the hiss stopped with it");
        });
    }
}
