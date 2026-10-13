package dev.bwr.mod.panel.client;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PanelModelSet;
import dev.bwr.mod.panel.model.PanelModels;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Client resource reload listener for baked panel component models: reads every
 * {@code assets/bwr/models/panel/*.json} (the format of {@code art/models/panel/panel_export.py}) into the
 * immutable {@link dev.bwr.mod.panel.model.BakedPanelModel}s of {@link PanelModels}.
 *
 * <p>A file that is malformed, outside its footprint, or too big is logged as a warning and skipped; the
 * component then draws with the classic procedural model, so a bad or missing model never crashes the client or
 * leaves a hole in a panel. Reading happens off the render thread; the finished map is swapped in on the main
 * thread, and {@link PanelModels#generation()} changes so cached component meshes rebuild.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelModelLoader extends SimplePreparableReloadListener<Map<String, String>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LISTER = FileToIdConverter.json("models/panel");

    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PanelModelLoader());
    }

    @Override
    protected Map<String, String> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, String> texts = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : LISTER.listMatchingResources(manager).entrySet()) {
            ResourceLocation location = entry.getKey();
            if (!BwrMod.MOD_ID.equals(location.getNamespace())) {
                continue;
            }
            String id = LISTER.fileToId(location).getPath();
            try (InputStream in = entry.getValue().open()) {
                byte[] bytes = in.readNBytes(PanelModelParser.MAX_FILE_BYTES + 1);
                if (bytes.length > PanelModelParser.MAX_FILE_BYTES) {
                    LOGGER.warn("Skipping panel model {}: larger than {} bytes", location, PanelModelParser.MAX_FILE_BYTES);
                    continue;
                }
                texts.put(id, new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Skipping panel model {}: could not be read ({})", location, e.toString());
            }
        }
        return texts;
    }

    @Override
    protected void apply(Map<String, String> texts, ResourceManager manager, ProfilerFiller profiler) {
        PanelModelSet.Result result = PanelModelSet.loadAll(texts);
        for (String problem : result.problems()) {
            LOGGER.warn("Skipping panel model {}", problem);
        }
        PanelModels.replace(result.models());
        LOGGER.info("Loaded {} panel component models ({} skipped)", result.models().size(), result.problems().size());
    }
}
