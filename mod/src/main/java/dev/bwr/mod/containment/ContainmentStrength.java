package dev.bwr.mod.containment;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.bwr.core.accident.ContainmentWallStrength;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ratings for continuous columns of airtight wall blocks. */
public final class ContainmentStrength {
    private ContainmentStrength() {}

    public record Column(ContainmentControllerBlockEntity.BoundaryFace face,
                         List<BlockPos> layers, BlockPos frontier,
                         String material, double ratingPsig, boolean deep) {
        public BlockPos position() { return face.wall(); }
        public int layerCount() { return layers.size(); }
    }

    private static volatile Map<ResourceLocation, Double> blockOverrides = Map.of();
    private static volatile Map<TagKey<Block>, Double> tagOverrides = Map.of();

    public static double contribution(Level level, BlockPos pos, BlockState state) {
        Double direct = blockOverrides.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (direct != null) return direct;
        for (var override : tagOverrides.entrySet()) if (state.is(override.getKey())) return override.getValue();
        return ContainmentWallStrength.psi(state.getExplosionResistance(level, pos, null));
    }

    public static Column rate(Level level, ContainmentControllerBlockEntity.BoundaryFace face, int depth) {
        List<BlockPos> layers = new ArrayList<>(depth);
        BlockPos frontier = null;
        String material = "minecraft:air";
        double psi = 0.0;
        Direction outward = face.normal();
        for (int layer = 0; layer < depth; layer++) {
            BlockPos pos = face.wall().relative(outward, layer);
            if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                frontier = pos.immutable();
                break;
            }
            if (!level.isLoaded(pos)) return null;
            BlockState state = level.getBlockState(pos);
            if (!ContainmentWall.isWall(level, pos, state)) {
                frontier = pos.immutable();
                break;
            }
            if (layer == 0) material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            layers.add(pos.immutable());
            // An opening has its own sleeve rating. Exterior wall layers do not
            // make the sleeve any stronger than its 150-psig qualification.
            if (layer == 0 && state.getBlock() instanceof ContainmentPenetrationBlock)
                psi = ContainmentPenetrationRatings.containmentPsig();
            else if (layer == 0 && state.getBlock() instanceof ContainmentVaultDoorBlock)
                psi = ContainmentConfig.VAULT_DOOR_CONTAINMENT_PSIG.get();
            else if (layer == 0 && state.getBlock() instanceof ContainmentAirlockBlock)
                psi = ContainmentConfig.AIRLOCK_CONTAINMENT_PSIG.get();
            else if (!(level.getBlockState(face.wall()).getBlock() instanceof ContainmentPenetrationBlock)
                    && !(level.getBlockState(face.wall()).getBlock() instanceof ContainmentVaultDoorBlock)
                    && !(level.getBlockState(face.wall()).getBlock() instanceof ContainmentAirlockBlock))
                psi += contribution(level, pos, state);
        }
        return new Column(face, List.copyOf(layers), frontier, material, psi,
                layers.size() == depth);
    }

    /** Reloads data/<namespace>/bwr_containment/strength.json on server datapack reload. */
    public static final class Loader extends SimpleJsonResourceReloadListener {
        private static final Logger LOGGER = LogUtils.getLogger();

        public Loader() { super(new Gson(), "bwr_containment"); }

        @Override protected void apply(Map<ResourceLocation, JsonElement> entries,
                                       ResourceManager resources, ProfilerFiller profiler) {
            Map<ResourceLocation, Double> blocks = new LinkedHashMap<>();
            Map<TagKey<Block>, Double> tags = new LinkedHashMap<>();
            entries.entrySet().stream().sorted(Map.Entry.comparingByKey(
                    java.util.Comparator.comparing(ResourceLocation::toString))).forEach(entry -> {
                if (!entry.getKey().getPath().equals("strength")) return;
                if (!(entry.getValue() instanceof JsonObject object)) {
                    LOGGER.warn("Ignoring {}: containment strength must be a JSON object", entry.getKey());
                    return;
                }
                for (var member : object.entrySet()) {
                    try {
                        double psi = member.getValue().getAsDouble();
                        if (!Double.isFinite(psi) || psi < 0.0) throw new IllegalArgumentException("psi must be finite and nonnegative");
                        String key = member.getKey();
                        if (key.startsWith("#"))
                            tags.put(TagKey.create(Registries.BLOCK, ResourceLocation.parse(key.substring(1))), psi);
                        else blocks.put(ResourceLocation.parse(key), psi);
                    } catch (RuntimeException error) {
                        LOGGER.warn("Ignoring containment strength {} in {}: {}",
                                member.getKey(), entry.getKey(), error.getMessage());
                    }
                }
            });
            blockOverrides = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
            tagOverrides = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            ContainmentControllerRegistry.strengthsReloaded();
        }
    }
}
