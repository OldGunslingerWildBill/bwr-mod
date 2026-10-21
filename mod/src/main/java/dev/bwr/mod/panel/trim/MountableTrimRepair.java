package dev.bwr.mod.panel.trim;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Old saves: the header strips and chamfer corners placed before they were mountable are in the world as blocks of
 * the same id and state but with no block entity. Asking the level for the entity of such a block creates one
 * (a block entity is made on demand for a block that has one), so the server and a client that targets the block
 * both get a panel the moment anything uses it; but a block entity that appears <em>after</em> the chunk was sent
 * is not part of the section the client drew, and its components would not show until the section is rebuilt. So
 * when a chunk finishes loading on the server, the missing entities are created then, and travel to the client in
 * the chunk packet like any other. A chunk without such blocks costs one palette check per section.
 */
@EventBusSubscriber(modid = "bwr")
public final class MountableTrimRepair {
    private MountableTrimRepair() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel && event.getChunk() instanceof LevelChunk chunk) {
            repair(chunk);
        }
    }

    /** Creates the block entity of every mountable trim block in the chunk that has none; returns how many. */
    public static int repair(LevelChunk chunk) {
        int created = 0;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof MountableTrimBlock)) {
                continue;
            }
            int minY = (chunk.getMinSection() + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getBlock() instanceof MountableTrimBlock) {
                            BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                            if (!chunk.getBlockEntities().containsKey(pos)) {
                                chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                                created++;
                            }
                        }
                    }
                }
            }
        }
        return created;
    }
}
