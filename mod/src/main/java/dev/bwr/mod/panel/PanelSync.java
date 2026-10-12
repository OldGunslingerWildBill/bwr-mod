package dev.bwr.mod.panel;

import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server to client sync for panels: full state when a chunk starts being
 * watched and whenever a block's structure or config changes, and value deltas
 * batched into one packet per chunk and tick.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelSync {
    private PanelSync() {
    }

    private static final Set<PanelBlockEntity> DIRTY = Collections.newSetFromMap(new IdentityHashMap<>());

    static void markDirty(PanelBlockEntity be) {
        DIRTY.add(be);
    }

    static void sendFull(ServerLevel level, PanelBlockEntity be) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(be.getBlockPos()),
                new PanelPayloads.State(be.getBlockPos(), be.savePanel(true)));
    }

    static void sendOpenConfig(ServerPlayer player, BlockPos owner, int id) {
        PacketDistributor.sendToPlayer(player, new PanelPayloads.OpenConfig(owner, id));
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Sent event) {
        for (BlockEntity be : event.getChunk().getBlockEntities().values()) {
            if (be instanceof PanelBlockEntity panel && panel.ownedCount() + panel.foreignClaims().size() > 0) {
                PacketDistributor.sendToPlayer(event.getPlayer(),
                        new PanelPayloads.State(panel.getBlockPos(), panel.savePanel(true)));
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DIRTY.isEmpty()) {
            return;
        }
        Map<Level, Map<ChunkPos, List<PanelPayloads.Entry>>> batches = new HashMap<>();
        for (PanelBlockEntity be : DIRTY) {
            List<PanelBlockEntity.ContentPatch> patches = be.drainContent();
            if (!be.isRemoved() && be.getLevel() instanceof ServerLevel patchLevel) {
                for (PanelBlockEntity.ContentPatch patch : patches) {
                    PacketDistributor.sendToPlayersTrackingChunk(patchLevel, new ChunkPos(be.getBlockPos()),
                            new PanelPayloads.Content(be.getBlockPos(), patch.id(), patch.bytes()));
                }
            }
            List<PanelBlockEntity.ValueDelta> deltas = be.drainDeltas();
            if (be.isRemoved() || !(be.getLevel() instanceof ServerLevel level) || deltas.isEmpty()) {
                continue;
            }
            List<PanelPayloads.Entry> list = batches.computeIfAbsent(level, k -> new HashMap<>())
                    .computeIfAbsent(new ChunkPos(be.getBlockPos()), k -> new ArrayList<>());
            for (PanelBlockEntity.ValueDelta delta : deltas) {
                list.add(new PanelPayloads.Entry(be.getBlockPos(), delta.id(), delta.output(), delta.index(), delta.value()));
            }
        }
        DIRTY.clear();
        for (Map.Entry<Level, Map<ChunkPos, List<PanelPayloads.Entry>>> perLevel : batches.entrySet()) {
            ServerLevel level = (ServerLevel) perLevel.getKey();
            for (Map.Entry<ChunkPos, List<PanelPayloads.Entry>> perChunk : perLevel.getValue().entrySet()) {
                List<PanelPayloads.Entry> all = perChunk.getValue();
                for (int from = 0; from < all.size(); from += PanelPayloads.MAX_DELTAS) {
                    List<PanelPayloads.Entry> part = new ArrayList<>(
                            all.subList(from, Math.min(all.size(), from + PanelPayloads.MAX_DELTAS)));
                    PacketDistributor.sendToPlayersTrackingChunk(level, perChunk.getKey(), new PanelPayloads.Delta(part));
                }
            }
        }
    }
}
