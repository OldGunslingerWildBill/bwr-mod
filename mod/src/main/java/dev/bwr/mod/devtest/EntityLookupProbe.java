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
 * counts as live until {@link #forget}.
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
        wanted.discard();
        probes.remove(chunk);
        live.add(chunk);
        return true;
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
