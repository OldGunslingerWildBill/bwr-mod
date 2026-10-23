package dev.bwr.mod.devtest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether entity lookups work in a chunk yet. A chunk that is loaded and force-loaded is not necessarily visible to
 * {@code getEntitiesOfClass} at once: an item added to it right away is alive but not found for a tick or more (a
 * logged trace: added at tick 0, found at tick 2), and longer on a busy machine, so a test that spawns or counts item
 * drops a fixed few ticks after forcing its fixture reads "got 0" now and then. A probe item (bedrock, which no test
 * counts) is added to the chunk and the answer is whether a lookup finds it; once it does it is removed and the chunk
 * counts as live until {@link #forget}. A chunk is only live once its entities are loaded and it is block ticking too:
 * block entities and scheduled ticks wait for that.
 */
final class EntityLookupProbe {
    private final ServerLevel level;
    private final double probeY;
    private final Set<ChunkPos> live = new HashSet<>();
    private final Map<ChunkPos, ItemEntity> probes = new HashMap<>();

    /** {@code probeY} is where the probes hover: above whatever the fixture builds. */
    EntityLookupProbe(ServerLevel level, double probeY) {
        this.level = level;
        this.probeY = probeY;
    }

    /** Whether an entity spawned at {@code pos} can be found by a lookup now (false while the chunk is not loaded). */
    boolean live(BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        ChunkPos chunk = new ChunkPos(pos);
        if (live.contains(chunk)) {
            return true;
        }
        ItemEntity probe = probes.get(chunk);
        if (probe == null || probe.isRemoved()) {
            probe = new ItemEntity(level, pos.getX() + 0.5, probeY, pos.getZ() + 0.5, new ItemStack(Items.BEDROCK));
            probe.setNoGravity(true);
            probe.setDeltaMovement(Vec3.ZERO);
            probe.setUnlimitedLifetime();
            probe.setPickUpDelay(32767);
            if (level.addFreshEntity(probe)) {
                probes.put(chunk, probe);
            }
            return false;
        }
        ItemEntity wanted = probe;
        if (level.getEntitiesOfClass(ItemEntity.class, wanted.getBoundingBox().inflate(0.5), e -> e == wanted).isEmpty()) {
            return false;
        }
        // Entity lookups can work before the chunk's own entities are back; until they are, its block entities do not tick
        // and its scheduled ticks do not run (ServerLevel.isPositionTickingWithEntitiesLoaded).
        var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded == null || !loaded.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING)
                || !level.areEntitiesLoaded(chunk.toLong())) {
            return false;
        }
        wanted.discard();
        probes.remove(chunk);
        live.add(chunk);
        return true;
    }

    @FunctionalInterface
    interface CheckedAction { void run() throws Exception; }

    /** Returns an idempotent cleanup and installs it before the fixture acquires any resources. */
    static Runnable onFinished(net.minecraft.gametest.framework.GameTestHelper helper, Runnable cleanup) {
        boolean[] finished = {false};
        Runnable release = () -> {
            if (finished[0]) return;
            finished[0] = true;
            cleanup.run();
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            @Override public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) { }
            @Override public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { release.run(); }
            @Override public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { release.run(); }
            @Override public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo before, net.minecraft.gametest.framework.GameTestInfo after, net.minecraft.gametest.framework.GameTestRunner runner) { release.run(); }
        });
        return release;
    }

    /** Runs a synchronous fixture only after every chunk in its bounds answers a real entity lookup. */
    static void runWhenLive(net.minecraft.gametest.framework.GameTestHelper helper,
                            net.minecraft.world.phys.AABB bounds, CheckedAction action) {
        ServerLevel level = helper.getLevel();
        var chunks = new java.util.ArrayList<ChunkPos>();
        EntityLookupProbe probe = new EntityLookupProbe(level, bounds.maxY + 2);
        boolean[] released = {false};
        Runnable release = onFinished(helper, () -> {
            released[0] = true;
            try { probe.forget(); } finally {
                chunks.forEach(c -> FixtureChunks.force(level, probe, c.x, c.z, false));
            }
        });
        try {
            for (int x = net.minecraft.util.Mth.floor(bounds.minX) >> 4; x <= net.minecraft.util.Mth.floor(bounds.maxX) >> 4; x++) {
                for (int z = net.minecraft.util.Mth.floor(bounds.minZ) >> 4; z <= net.minecraft.util.Mth.floor(bounds.maxZ) >> 4; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    chunks.add(chunk);
                    FixtureChunks.force(level, probe, x, z, true);
                    level.getChunk(x, z);
                }
            }
            await(helper, chunks, probe, action, release, released, FixtureWait.start("entity lookups of " + chunks.size() + " chunks"));
        } catch (RuntimeException | Error e) {
            release.run();
            throw e;
        }
    }

    private static void await(net.minecraft.gametest.framework.GameTestHelper helper,
                              java.util.List<ChunkPos> chunks, EntityLookupProbe probe, CheckedAction action,
                              Runnable release, boolean[] released, FixtureWait.Timer wait) {
        if (released[0]) return; // A timeout, failure or rerun must never restart the fixture.
        try {
            boolean ready = true;
            for (ChunkPos chunk : chunks) {
                ready &= probe.live(new BlockPos((chunk.x << 4) + 8, (int) probe.probeY, (chunk.z << 4) + 8));
            }
            if (!ready && wait.polls() < 160) {
                wait.notReady(probe.level);
                helper.runAfterDelay(1, () -> await(helper, chunks, probe, action, release, released, wait));
                return;
            }
            helper.assertTrue(ready, "fixture chunks never answered entity lookups");
            wait.ready();
            action.run();
            // An action that goes on after it returns (a delay, a tick hook) still needs its chunks: they go when the test ends.
            if (helper.testInfo.isDone()) {
                release.run();
            }
        } catch (RuntimeException | Error e) {
            release.run();
            throw e;
        } catch (Exception e) {
            release.run();
            throw new RuntimeException(e);
        }
    }

    /** Forgets what was seen (the chunks were unloaded and loaded again) and removes any probe still waiting. */
    void forget() {
        live.clear();
        for (ItemEntity probe : probes.values()) {
            probe.discard();
        }
        probes.clear();
    }
}
