package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.worldgen.UraniumVeinFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worldgen check on a real dedicated server ({@code :mod:runWorldgenCheck}, see build.gradle and URANIUM-ORE.md). Inert
 * without {@code -Dbwr.worldgenCheck=true}. It generates fresh chunks and measures what the uranium features put in them:
 *
 * <ol>
 *   <li><b>Density by biome group.</b> The check world has one flat dimension per biome group ({@code bwr_check:flat_arid},
 *       {@code _highland}, {@code _semi_arid}, {@code _lowland}, {@code _aquatic}; flat ground of stone and deepslate, the
 *       group's biome, normal biome decoration with the biome modifiers applied, the same world seed). The same 64 x 64
 *       chunks (a square 1,024 blocks on a side, nearly three vein cells each way) are generated in each and the uranium
 *       blocks counted, so the only thing that differs between the dimensions is the biome. Veins and blobs are told apart
 *       by the feature's own counters. The largest connected ore body is measured in the arid one.</li>
 *   <li><b>A natural world.</b> The overworld has the ordinary noise terrain and real biomes. A vein whose centre line is in
 *       the arid group is located (from the seed and the biome source only, nothing generated) and the chunks around it
 *       are generated: the caves, ravines and aquifers of the real terrain cut across it, and the largest connected body
 *       is measured.</li>
 *   <li><b>Old chunks are never touched.</b> With the option off a block of chunks over a vein is generated (it has no
 *       uranium: this is also the proof the option works on live worldgen); with the option on again the chunks round
 *       them are generated (they get uranium: new chunks are served) and every block of the first block is compared with
 *       what it was before (nothing changed, and nothing was written across the border).</li>
 * </ol>
 * The verdict is the line {@code BWR WORLDGEN CHECK: PASS}; the Gradle task fails the build without it.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class UraniumWorldgenCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "BWR WORLDGEN CHECK: ";

    /** Chunks on a side of the density square. */
    private static final int SIDE = 64;

    private static final Deque<Runnable> WORK = new ArrayDeque<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static boolean started, finished;

    private UraniumWorldgenCheck() {}

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("bwr.worldgenCheck")) return;
        started = true;
        MinecraftServer server = event.getServer();
        LOGGER.info(TAG + "server started; uranium option {}, seed {}", GameplayConfig.uraniumOreGeneration(), server.overworld().getSeed());
        plan(server);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!started || finished) return;
        Runnable next = WORK.poll();
        if (next != null) {
            try {
                next.run();
            } catch (Throwable t) {
                LOGGER.error(TAG + "a step threw", t);
                FAILURES.add("a step threw: " + t);
                WORK.clear();
            }
        }
        if (WORK.isEmpty()) {
            finished = true;
            LOGGER.info(TAG + (FAILURES.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", FAILURES)));
            event.getServer().halt(false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static boolean isOurs(BlockState s) {
        return s.is(BwrBlocks.URANIUM_ORE.get()) || s.is(BwrBlocks.DEEPSLATE_URANIUM_ORE.get()) || s.is(BwrBlocks.RAW_URANIUM_BLOCK.get());
    }

    /** What was counted in some chunks. */
    private static final class Tally {
        long chunks, withUranium, ore, raw;
        long veinOre, veinRaw, veinBody, veinSlices, veinNanos, ms;
        final List<Long> positions = new ArrayList<>();
        /** Every block of a vein that was placed (filler, ore and raw), when the probe was on. */
        final java.util.concurrent.ConcurrentLinkedQueue<Long> body = new java.util.concurrent.ConcurrentLinkedQueue<>();

        long uranium() {
            return ore + raw;
        }
    }

    /** Generates the chunk (to FULL) and adds its uranium blocks to the tally. */
    private static void generate(ServerLevel level, int cx, int cz, Tally t, boolean keepPositions) {
        ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
        t.chunks++;
        long found = 0;
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir() || !section.getStates().maybeHas(UraniumWorldgenCheck::isOurs)) continue;
            int baseY = chunk.getMinBuildHeight() + 16 * i;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState s = section.getBlockState(x, y, z);
                        if (!isOurs(s)) continue;
                        found++;
                        if (s.is(BwrBlocks.RAW_URANIUM_BLOCK.get())) t.raw++;
                        else t.ore++;
                        if (keepPositions) t.positions.add(UraniumStats.pack(cx * 16 + x, baseY + y, cz * 16 + z));
                    }
                }
            }
        }
        if (found > 0) t.withUranium++;
    }

    private static void counters(Tally t, long[] before) {
        t.veinOre = UraniumVeinFeature.ORE.sum() - before[0];
        t.veinRaw = UraniumVeinFeature.RAW.sum() - before[1];
        t.veinBody = UraniumVeinFeature.BODY.sum() - before[2];
        t.veinSlices = UraniumVeinFeature.SLICES.sum() - before[3];
        t.veinNanos = UraniumVeinFeature.NANOS.sum() - before[4];
    }

    private static long[] counterSnapshot() {
        return new long[] {UraniumVeinFeature.ORE.sum(), UraniumVeinFeature.RAW.sum(), UraniumVeinFeature.BODY.sum(), UraniumVeinFeature.SLICES.sum(),
                UraniumVeinFeature.NANOS.sum()};
    }

    /** Asks for a whole row of chunks at once (so the generator works on them in parallel), then counts each. */
    private static void generateRow(ServerLevel level, int cx0, int cx1, int cz, Tally t, boolean keepPositions) {
        var source = level.getChunkSource();
        for (int cx = cx0; cx <= cx1; cx++) {
            source.getChunkFuture(cx, cz, ChunkStatus.FULL, true);
        }
        for (int cx = cx0; cx <= cx1; cx++) {
            generate(level, cx, cz, t, keepPositions);
        }
    }

    private static void fail(String why) {
        LOGGER.error(TAG + "FAILED: {}", why);
        FAILURES.add(why);
    }

    private static ServerLevel dimension(MinecraftServer server, String path) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("bwr_check", path)));
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void plan(MinecraftServer server) {
        Map<String, Tally> density = new LinkedHashMap<>();
        // 1. density by biome group, one row of chunks a tick
        for (String group : UraniumVeinFinder.GROUPS) {
            ServerLevel level = dimension(server, "flat_" + group);
            if (level == null) {
                fail("the check dimension bwr_check:flat_" + group + " does not exist (the datapack did not load)");
                continue;
            }
            Tally tally = new Tally();
            density.put(group, tally);
            long[][] before = new long[1][];
            long[] startedAt = new long[1];
            WORK.add(() -> {
                before[0] = counterSnapshot();
                startedAt[0] = System.nanoTime();
                if (group.equals("arid")) UraniumVeinFeature.probe = (x, y, z) -> tally.body.add(UraniumStats.pack(x, y, z));
                LOGGER.info(TAG + "{}: generating {} x {} chunks", group, SIDE, SIDE);
            });
            for (int row = 0; row < SIDE; row++) {
                int cz = row - SIDE / 2;
                WORK.add(() -> generateRow(level, -SIDE / 2, SIDE / 2 - 1, cz, tally, group.equals("arid")));
            }
            WORK.add(() -> {
                UraniumVeinFeature.probe = null;
                counters(tally, before[0]);
                tally.ms = (System.nanoTime() - startedAt[0]) / 1_000_000;
                LOGGER.info(TAG + "{}: {} chunks in {} ms ({} ms a chunk): {} ore, {} raw, {} chunks with uranium; veins: {} slices, {} body, {} ore, {} raw; the vein feature took {} ms in all, {} us a slice",
                        group, tally.chunks, tally.ms, String.format("%.2f", tally.ms / (double) tally.chunks), tally.ore, tally.raw, tally.withUranium,
                        tally.veinSlices, tally.veinBody, tally.veinOre, tally.veinRaw, tally.veinNanos / 1_000_000,
                        tally.veinSlices == 0 ? 0 : tally.veinNanos / 1000 / tally.veinSlices);
            });
        }
        WORK.add(() -> judgeDensity(density));

        // 2. a vein in real terrain
        ServerLevel overworld = server.overworld();
        Tally natural = new Tally();
        UraniumVeinFinder.Found[] found = new UraniumVeinFinder.Found[1];
        long[][] naturalBefore = new long[1][];
        WORK.add(() -> {
            found[0] = UraniumVeinFinder.find(overworld, "arid", 0.5, 14);
            if (found[0] == null) {
                fail("no arid vein within 14 cells of the origin in this world");
                return;
            }
            var f = found[0];
            LOGGER.info(TAG + "natural world: vein of cell ({}, {}), middle at {}, {}, {} chunks {}..{} x {}..{}", f.vein().cellX, f.vein().cellZ,
                    (int) f.middle()[0], (int) f.middle()[2], (f.maxChunkX() - f.minChunkX() + 1) * (f.maxChunkZ() - f.minChunkZ() + 1),
                    f.minChunkX(), f.maxChunkX(), f.minChunkZ(), f.maxChunkZ());
            naturalBefore[0] = counterSnapshot();
            long startedAt = System.nanoTime();
            UraniumVeinFeature.probe = (x, y, z) -> natural.body.add(UraniumStats.pack(x, y, z));
            for (int cz = f.minChunkZ(); cz <= f.maxChunkZ(); cz++) {
                int row = cz;
                WORK.add(() -> generateRow(overworld, f.minChunkX(), f.maxChunkX(), row, natural, true));
            }
            WORK.add(() -> {
                UraniumVeinFeature.probe = null;
                counters(natural, naturalBefore[0]);
                natural.ms = (System.nanoTime() - startedAt) / 1_000_000;
                judgeNatural(natural, f);
            });
            WORK.add(() -> oldChunks(server));
        });
    }

    private static void judgeDensity(Map<String, Tally> density) {
        if (density.size() != UraniumVeinFinder.GROUPS.size()) return;
        StringBuilder table = new StringBuilder("density per group, " + SIDE * SIDE + " chunks each (ore + raw blocks, in veins and blobs):\n");
        for (var e : density.entrySet()) {
            Tally t = e.getValue();
            table.append(String.format("  %-10s %8d uranium blocks  %8.2f a chunk  %4d chunks with uranium  (veins: %d blocks of body, %d ore, %d raw)%n",
                    e.getKey(), t.uranium(), t.uranium() / (double) t.chunks, t.withUranium, t.veinBody, t.veinOre, t.veinRaw));
        }
        LOGGER.info(TAG + table);
        double arid = perChunk(density, "arid"), high = perChunk(density, "highland"), semi = perChunk(density, "semi_arid");
        double low = perChunk(density, "lowland"), aqua = perChunk(density, "aquatic");
        if (!(arid > high && high > semi && semi > low && low >= aqua)) {
            fail("density does not fall arid > highland > semi_arid > lowland >= aquatic: " + arid + ", " + high + ", " + semi + ", " + low + ", " + aqua);
        }
        if (!(arid >= 5 * low)) fail("arid (" + arid + " a chunk) is not at least 5 times lowland (" + low + ")");
        Tally ta = density.get("arid"), th = density.get("highland"), tl = density.get("lowland"), tq = density.get("aquatic");
        if (!(ta.veinOre + ta.veinRaw >= 1.5 * (th.veinOre + th.veinRaw))) fail("arid vein ore (" + (ta.veinOre + ta.veinRaw) + ") is not at least 1.5 times highland (" + (th.veinOre + th.veinRaw) + ")");
        if (!(ta.veinOre + ta.veinRaw >= 8 * (tl.veinOre + tl.veinRaw))) fail("arid vein ore is not at least 8 times lowland (" + (tl.veinOre + tl.veinRaw) + ")");
        if (tq.veinBody > tl.veinBody) fail("aquatic has more vein body than lowland");
        if (ta.veinBody < 10_000) fail("the arid square has only " + ta.veinBody + " blocks of vein body");
        LOGGER.info(TAG + String.format("vein ore per chunk: arid %.2f, highland %.2f, semi_arid %.2f, lowland %.2f, aquatic %.2f; blobs per chunk: arid %.2f, highland %.2f, semi_arid %.2f, lowland %.2f, aquatic %.2f",
                veinPerChunk(density, "arid"), veinPerChunk(density, "highland"), veinPerChunk(density, "semi_arid"), veinPerChunk(density, "lowland"), veinPerChunk(density, "aquatic"),
                blobPerChunk(density, "arid"), blobPerChunk(density, "highland"), blobPerChunk(density, "semi_arid"), blobPerChunk(density, "lowland"), blobPerChunk(density, "aquatic")));

        // the largest connected vein body in the arid square: exactly what generation wrote (filler, ore and raw), 26-neighbour joined
        var body = UraniumStats.components(new ArrayList<>(ta.body), 1);
        LOGGER.info(TAG + "arid flat square, vein body (every block of a vein that was placed, exact 26-neighbour connection): " + UraniumVeinCheckSummary.describeBody(body, ta.veinBody));
        var ore = UraniumStats.components(ta.positions, 2);
        LOGGER.info(TAG + "arid flat square, ore only, joined within 3 blocks: " + UraniumVeinCheckSummary.describe(ore));
        if (body.isEmpty() || body.get(0).horizontalSpan() < 150 || body.get(0).blocks() < 5000) fail("the largest connected vein body in the arid square is " + (body.isEmpty() ? "absent" : body.get(0)));
        ta.positions.clear();
        ta.body.clear();
    }

    private static double perChunk(Map<String, Tally> density, String group) {
        Tally t = density.get(group);
        return t.uranium() / (double) t.chunks;
    }

    private static double veinPerChunk(Map<String, Tally> density, String group) {
        Tally t = density.get(group);
        return (t.veinOre + t.veinRaw) / (double) t.chunks;
    }

    private static double blobPerChunk(Map<String, Tally> density, String group) {
        Tally t = density.get(group);
        return (t.uranium() - t.veinOre - t.veinRaw) / (double) t.chunks;
    }

    private static void judgeNatural(Tally natural, UraniumVeinFinder.Found f) {
        var parts = UraniumStats.components(natural.positions, 2);
        var body = UraniumStats.components(new ArrayList<>(natural.body), 1);
        LOGGER.info(TAG + "natural terrain: {} chunks in {} ms ({} ms a chunk); {} ore, {} raw uranium blocks; vein feature: {} slices, {} body blocks, {} ore, {} raw",
                natural.chunks, natural.ms, String.format("%.1f", natural.ms / (double) natural.chunks), natural.ore, natural.raw,
                natural.veinSlices, natural.veinBody, natural.veinOre, natural.veinRaw);
        LOGGER.info(TAG + "natural terrain, vein body (exact 26-neighbour connection): " + UraniumVeinCheckSummary.describeBody(body, natural.veinBody));
        LOGGER.info(TAG + "natural terrain, ore only, joined within 3 blocks: " + UraniumVeinCheckSummary.describe(parts));
        if (natural.veinSlices == 0) fail("the vein feature made no slice in the natural terrain");
        if (natural.uranium() < 500) fail("only " + natural.uranium() + " uranium blocks in the natural area of the vein");
        if (body.isEmpty() || body.get(0).horizontalSpan() < 100) fail("the largest connected vein body in real terrain is " + (body.isEmpty() ? "absent" : body.get(0)));
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final int OLD = 8, RING = 2;

    private static void oldChunks(MinecraftServer server) {
        ServerLevel level = dimension(server, "flat_arid");
        if (level == null) return;
        // a vein of the flat dimension's seed, away from the density square (cells at x >= 5): the first cell with a vein at 0.5
        UraniumVeinFinder.Found vein = null;
        for (int cx = 6; cx < 40 && vein == null; cx++) {
            var v = dev.bwr.mod.worldgen.UraniumVeins.vein(level.getSeed(), cx, 0, UraniumVeinFinder.geometry(level));
            if (v.roll < 0.5) {
                vein = new UraniumVeinFinder.Found(v, "arid", 0, 0, 0, 0, v.centreLine[v.centreLine.length / 2]);
            }
        }
        if (vein == null) {
            fail("no vein cell for the old-chunk test");
            return;
        }
        int mx = Math.floorDiv((int) vein.middle()[0], 16) - OLD / 2, mz = Math.floorDiv((int) vein.middle()[2], 16) - OLD / 2;
        UraniumVeinFinder.Found chosen = vein;
        boolean option = GameplayConfig.URANIUM_ORE_GENERATION.get();
        Tally oldTally = new Tally();
        long[] hashes = new long[OLD * OLD];
        long[] before = new long[1];
        long[] bodyBefore = new long[1];
        WORK.add(() -> {
            GameplayConfig.URANIUM_ORE_GENERATION.set(false);
            bodyBefore[0] = UraniumVeinFeature.BODY.sum();
            for (int i = 0; i < OLD; i++) for (int j = 0; j < OLD; j++) generate(level, mx + i, mz + j, oldTally, false);
            for (int i = 0; i < OLD; i++) for (int j = 0; j < OLD; j++) hashes[i * OLD + j] = hash(level.getChunk(mx + i, mz + j, ChunkStatus.FULL, true));
            LOGGER.info(TAG + "old chunks: {} x {} chunks at {}, {} generated with the option OFF over the vein of cell {}: {} uranium blocks, {} vein body blocks",
                    OLD, OLD, mx * 16, mz * 16, chosen.vein().cellX, oldTally.uranium(), UraniumVeinFeature.BODY.sum() - bodyBefore[0]);
            if (oldTally.uranium() != 0) fail("the option is off and " + oldTally.uranium() + " uranium blocks were generated");
            if (UraniumVeinFeature.BODY.sum() != bodyBefore[0]) fail("the option is off and the vein feature still placed blocks");
            GameplayConfig.URANIUM_ORE_GENERATION.set(true);
            before[0] = UraniumVeinFeature.BODY.sum();
        });
        Tally ring = new Tally();
        WORK.add(() -> {
            try {
                for (int i = -RING; i < OLD + RING; i++) {
                    for (int j = -RING; j < OLD + RING; j++) {
                        if (i >= 0 && i < OLD && j >= 0 && j < OLD) continue;
                        generate(level, mx + i, mz + j, ring, false);
                    }
                }
            } finally {
                GameplayConfig.URANIUM_ORE_GENERATION.set(option);
            }
            long changed = 0;
            for (int i = 0; i < OLD; i++) {
                for (int j = 0; j < OLD; j++) {
                    if (hash(level.getChunk(mx + i, mz + j, ChunkStatus.FULL, true)) != hashes[i * OLD + j]) changed++;
                }
            }
            LOGGER.info(TAG + "old chunks: with the option ON again the {} chunks round them were generated: {} uranium blocks ({} vein body); {} of {} old chunks changed",
                    ring.chunks, ring.uranium(), UraniumVeinFeature.BODY.sum() - before[0], changed, OLD * OLD);
            if (changed != 0) fail(changed + " already generated chunks changed when their neighbours were generated");
            if (ring.uranium() == 0) fail("the chunks round the old ones, generated with the option on, have no uranium");
        });
    }

    /** A hash of every block state of a chunk. */
    private static long hash(ChunkAccess chunk) {
        long h = 1125899906842597L;
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir()) {
                h = h * 31 + 7;
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        h = h * 31 + Block.getId(section.getBlockState(x, y, z));
                    }
                }
            }
        }
        return h;
    }

    /** Formats the connected bodies for the log. */
    private static final class UraniumVeinCheckSummary {
        static String describeBody(List<UraniumStats.Component> parts, long placed) {
            if (parts.isEmpty()) return "no vein body";
            StringBuilder sb = new StringBuilder(placed + " blocks placed in " + parts.size() + " connected bodies; the largest: " + parts.get(0));
            for (int i = 1; i < Math.min(4, parts.size()); i++) sb.append("; next: ").append(parts.get(i));
            return sb.toString();
        }

        static String describe(List<UraniumStats.Component> parts) {
            if (parts.isEmpty()) return "no uranium";
            StringBuilder sb = new StringBuilder(parts.size() + " connected bodies of ore (blocks within 3 of each other count as joined); the largest: " + parts.get(0));
            for (int i = 1; i < Math.min(4, parts.size()); i++) sb.append("; next: ").append(parts.get(i));
            return sb.toString();
        }
    }
}
