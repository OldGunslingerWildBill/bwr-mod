package dev.bwr.mod.devtest;

import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.worldgen.ThoriumVeinFeature;
import dev.bwr.mod.worldgen.UraniumVeinConfig;
import dev.bwr.mod.worldgen.UraniumVeins;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The thorium half of the worldgen check ({@code :mod:runWorldgenCheck}); {@link UraniumWorldgenCheck} runs it after its own
 * steps, on the same dedicated server and the same world seed, and the verdict line is shared. It measures:
 * <ol>
 *   <li><b>Density by biome group.</b> One flat dimension per thorium group ({@code bwr_check:flat_th_highland},
 *       {@code _placer}, {@code _lowland}, {@code _ocean}; stone and deepslate, with sand on top in the placer one, the
 *       group's biome, normal decoration with the biome modifiers applied). The same 64 x 64 chunks are generated in each and the
 *       thorium ore blocks and the monazite sand counted, so the difference between the dimensions is the biome table's alone.</li>
 *   <li><b>Old chunks are never touched.</b> With the option {@code thoriumOreGeneration} off a block of chunks over a vein is
 *       generated (no thorium at all: the option works on live generation); with it on again the chunks round them are generated
 *       (they get thorium) and every block of the first block is compared with what it was (nothing changed).</li>
 * </ol>
 */
final class ThoriumWorldgenCheck {

    static final List<String> GROUPS = List.of("highland", "placer", "lowland", "ocean");
    private static final int SIDE = 64;
    private static final int OLD = 8, RING = 2;

    private ThoriumWorldgenCheck() {
    }

    private static boolean isOre(BlockState s) {
        return s.is(BwrBlocks.THORIUM_ORE.get()) || s.is(BwrBlocks.DEEPSLATE_THORIUM_ORE.get()) || s.is(BwrBlocks.RAW_THORIUM_BLOCK.get());
    }

    private static boolean isOurs(BlockState s) {
        return isOre(s) || s.is(BwrBlocks.MONAZITE_SAND.get());
    }

    private static final class Tally {
        long chunks, withThorium, ore, raw, sand, veinOre, veinRaw, veinBody, veinSlices;

        long thorium() {
            return ore + raw;
        }
    }

    private static void generate(ServerLevel level, int cx, int cz, Tally t) {
        ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
        t.chunks++;
        long found = 0;
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir() || !section.getStates().maybeHas(ThoriumWorldgenCheck::isOurs)) continue;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState s = section.getBlockState(x, y, z);
                        if (!isOurs(s)) continue;
                        found++;
                        if (s.is(BwrBlocks.MONAZITE_SAND.get())) t.sand++;
                        else if (s.is(BwrBlocks.RAW_THORIUM_BLOCK.get())) t.raw++;
                        else t.ore++;
                    }
                }
            }
        }
        if (found > 0) t.withThorium++;
    }

    private static void generateRow(ServerLevel level, int cx0, int cx1, int cz, Tally t) {
        var source = level.getChunkSource();
        for (int cx = cx0; cx <= cx1; cx++) {
            source.getChunkFuture(cx, cz, ChunkStatus.FULL, true);
        }
        for (int cx = cx0; cx <= cx1; cx++) {
            generate(level, cx, cz, t);
        }
    }

    private static ServerLevel dimension(MinecraftServer server, String path) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("bwr_check", path)));
    }

    private static UraniumVeinConfig config(ServerLevel level, String group) {
        var holder = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("bwr", "thorium_vein_" + group)));
        return (UraniumVeinConfig) holder.value().config();
    }

    /** Adds the thorium steps to {@code work}; failures go to {@code failures}. */
    static void plan(MinecraftServer server, Deque<Runnable> work, List<String> failures, Logger log, String tag) {
        Map<String, Tally> density = new LinkedHashMap<>();
        for (String group : GROUPS) {
            ServerLevel level = dimension(server, "flat_th_" + group);
            if (level == null) {
                failures.add("the check dimension bwr_check:flat_th_" + group + " does not exist (the datapack did not load)");
                continue;
            }
            Tally tally = new Tally();
            density.put(group, tally);
            long[] before = new long[4];
            long[] startedAt = new long[1];
            work.add(() -> {
                before[0] = ThoriumVeinFeature.ORE.sum();
                before[1] = ThoriumVeinFeature.RAW.sum();
                before[2] = ThoriumVeinFeature.BODY.sum();
                before[3] = ThoriumVeinFeature.SLICES.sum();
                startedAt[0] = System.nanoTime();
                log.info(tag + "thorium {}: generating {} x {} chunks", group, SIDE, SIDE);
            });
            for (int row = 0; row < SIDE; row++) {
                int cz = row - SIDE / 2;
                work.add(() -> generateRow(level, -SIDE / 2, SIDE / 2 - 1, cz, tally));
            }
            work.add(() -> {
                tally.veinOre = ThoriumVeinFeature.ORE.sum() - before[0];
                tally.veinRaw = ThoriumVeinFeature.RAW.sum() - before[1];
                tally.veinBody = ThoriumVeinFeature.BODY.sum() - before[2];
                tally.veinSlices = ThoriumVeinFeature.SLICES.sum() - before[3];
                long ms = (System.nanoTime() - startedAt[0]) / 1_000_000;
                log.info(tag + "thorium {}: {} chunks in {} ms: {} ore, {} raw, {} monazite sand, {} chunks with thorium; veins: {} slices, {} body, {} ore, {} raw",
                        group, tally.chunks, ms, tally.ore, tally.raw, tally.sand, tally.withThorium,
                        tally.veinSlices, tally.veinBody, tally.veinOre, tally.veinRaw);
            });
        }
        work.add(() -> judgeDensity(density, server, failures, log, tag));
        work.add(() -> oldChunks(server, work, failures, log, tag));
    }

    private static double perChunk(Map<String, Tally> d, String g) {
        return d.get(g).thorium() / (double) d.get(g).chunks;
    }

    private static void judgeDensity(Map<String, Tally> d, MinecraftServer server, List<String> failures, Logger log, String tag) {
        if (d.size() != GROUPS.size()) return;
        StringBuilder table = new StringBuilder("thorium density per group, " + SIDE * SIDE + " chunks each (ore + raw blocks, in veins and blobs; monazite sand apart):\n");
        for (var e : d.entrySet()) {
            Tally t = e.getValue();
            table.append(String.format("  %-10s %8d thorium blocks  %8.2f a chunk  %4d chunks with thorium  %8d monazite sand  (veins: %d body, %d ore, %d raw)%n",
                    e.getKey(), t.thorium(), t.thorium() / (double) t.chunks, t.withThorium, t.sand, t.veinBody, t.veinOre, t.veinRaw));
        }
        log.info(tag + table);
        double hi = perChunk(d, "highland"), pl = perChunk(d, "placer"), lo = perChunk(d, "lowland"), oc = perChunk(d, "ocean");
        if (!(hi > pl && pl > lo && lo >= oc)) {
            failures.add("thorium density does not fall highland > placer > lowland >= ocean: " + hi + ", " + pl + ", " + lo + ", " + oc);
        }
        if (!(hi >= 3 * lo)) failures.add("thorium highland (" + hi + " a chunk) is not at least 3 times lowland (" + lo + ")");
        Tally th = d.get("highland"), tp = d.get("placer"), tl = d.get("lowland"), to = d.get("ocean");
        if (th.veinBody < 5_000) failures.add("the highland square has only " + th.veinBody + " blocks of thorium vein body");
        if (to.veinBody > tl.veinBody) failures.add("ocean has more thorium vein body than lowland");
        // the monazite sand placers are where the sand is: in the placer group, and nowhere else
        if (tp.sand < 500) failures.add("the placer square has only " + tp.sand + " monazite sand blocks");
        if (th.sand != 0 || tl.sand != 0 || to.sand != 0) failures.add("monazite sand outside the placer group: " + th.sand + ", " + tl.sand + ", " + to.sand);
        // thorium super veins are rarer than uranium's: the chance of every group is lower than uranium's group of the same kind
        var level = dimension(server, "flat_th_highland");
        if (level != null) {
            float thHighland = config(level, "highland").veinChance();
            var u = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("bwr", "uranium_vein_highland")));
            float uHighland = ((UraniumVeinConfig) u.value().config()).veinChance();
            if (!(thHighland < uHighland)) failures.add("thorium highland vein chance " + thHighland + " is not below uranium's " + uHighland);
            log.info(tag + "vein chance in the highland group: thorium {}, uranium {}", thHighland, uHighland);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void oldChunks(MinecraftServer server, Deque<Runnable> work, List<String> failures, Logger log, String tag) {
        ServerLevel level = dimension(server, "flat_th_highland");
        if (level == null) return;
        UraniumVeins.Geometry geometry = config(level, "highland").geometry();
        UraniumVeins.Vein vein = null;
        for (int cx = 6; cx < 60 && vein == null; cx++) {
            var v = UraniumVeins.vein(level.getSeed(), cx, 0, geometry);
            if (v.roll < 0.2) vein = v;
        }
        if (vein == null) {
            failures.add("no thorium vein cell for the old-chunk test");
            return;
        }
        double[] middle = vein.centreLine[vein.centreLine.length / 2];
        int mx = Math.floorDiv((int) middle[0], 16) - OLD / 2, mz = Math.floorDiv((int) middle[2], 16) - OLD / 2;
        boolean option = GameplayConfig.THORIUM_ORE_GENERATION.get();
        Tally oldTally = new Tally();
        long[] hashes = new long[OLD * OLD];
        long[] bodyBefore = new long[1];
        int cell = vein.cellX;
        work.add(() -> {
            GameplayConfig.THORIUM_ORE_GENERATION.set(false);
            bodyBefore[0] = ThoriumVeinFeature.BODY.sum();
            for (int i = 0; i < OLD; i++) for (int j = 0; j < OLD; j++) generate(level, mx + i, mz + j, oldTally);
            for (int i = 0; i < OLD; i++) for (int j = 0; j < OLD; j++) hashes[i * OLD + j] = hash(level.getChunk(mx + i, mz + j, ChunkStatus.FULL, true));
            log.info(tag + "thorium old chunks: {} x {} chunks at {}, {} generated with the option OFF over the vein of cell {}: {} thorium blocks, {} vein body blocks",
                    OLD, OLD, mx * 16, mz * 16, cell, oldTally.thorium(), ThoriumVeinFeature.BODY.sum() - bodyBefore[0]);
            if (oldTally.thorium() != 0) failures.add("thoriumOreGeneration is off and " + oldTally.thorium() + " thorium blocks were generated");
            if (ThoriumVeinFeature.BODY.sum() != bodyBefore[0]) failures.add("thoriumOreGeneration is off and the vein feature still placed blocks");
            GameplayConfig.THORIUM_ORE_GENERATION.set(true);
            bodyBefore[0] = ThoriumVeinFeature.BODY.sum();
        });
        Tally ring = new Tally();
        work.add(() -> {
            try {
                for (int i = -RING; i < OLD + RING; i++) {
                    for (int j = -RING; j < OLD + RING; j++) {
                        if (i >= 0 && i < OLD && j >= 0 && j < OLD) continue;
                        generate(level, mx + i, mz + j, ring);
                    }
                }
            } finally {
                GameplayConfig.THORIUM_ORE_GENERATION.set(option);
            }
            long changed = 0;
            for (int i = 0; i < OLD; i++) {
                for (int j = 0; j < OLD; j++) {
                    if (hash(level.getChunk(mx + i, mz + j, ChunkStatus.FULL, true)) != hashes[i * OLD + j]) changed++;
                }
            }
            log.info(tag + "thorium old chunks: with the option ON again the {} chunks round them were generated: {} thorium blocks ({} vein body); {} of {} old chunks changed",
                    ring.chunks, ring.thorium(), ThoriumVeinFeature.BODY.sum() - bodyBefore[0], changed, OLD * OLD);
            if (changed != 0) failures.add(changed + " already generated chunks changed when their neighbours were generated (thorium)");
            if (ring.thorium() == 0) failures.add("the chunks round the old ones, generated with thoriumOreGeneration on, have no thorium");
        });
    }

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
}
