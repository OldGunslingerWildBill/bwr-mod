package dev.bwr.mod.client;

import dev.bwr.mod.BwrMod;
import java.lang.reflect.InvocationTargetException;
import java.util.EnumMap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Per-effect fallback decision. Shader failures are sticky only until the next resource reload. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class VisualEffectsMode {
    public enum Effect { VAPOR, CHERENKOV, WATER }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EnumMap<Effect, Boolean> READY = new EnumMap<>(Effect.class);
    private static final EnumMap<Effect, Boolean> FAILED = new EnumMap<>(Effect.class);
    private static boolean packNotice;
    private static int failureLogCount;
    private static VisualEffectsConfig.Mode override;
    private static Boolean simulatedPack;

    private VisualEffectsMode() {}

    public static void loaded(Effect effect) {
        READY.put(effect, true);
        FAILED.remove(effect);
    }

    public static void failed(Effect effect, Throwable error) {
        READY.put(effect, false);
        if (FAILED.put(effect, true) == null) {
            failureLogCount++;
            LOGGER.warn("BWR {} shader failed; using classic renderer until next resource reload", effect, error);
        }
    }

    public static void reload() {
        READY.clear();
        FAILED.clear();
    }

    /** The override is used only by the disposable client regression harness. */
    public static void testOverride(VisualEffectsConfig.Mode mode, Boolean shaderPack) {
        override = mode;
        simulatedPack = shaderPack;
    }

    public static boolean gpu(Effect effect, boolean normalWorldPass) {
        if (!normalWorldPass) return false;
        var configured = override != null ? override : VisualEffectsConfig.VISUAL_EFFECTS.get();
        if (configured == VisualEffectsConfig.Mode.CLASSIC || !Boolean.TRUE.equals(READY.get(effect)) || FAILED.containsKey(effect)) return false;
        if (shaderPackInUse()) {
            if (!packNotice) {
                LOGGER.info("BWR shader pack active; classic visual effects take priority");
                packNotice = true;
            }
            return false;
        }
        // AUTO vapor stays classic unless a same-machine benchmark proves it faster.
        return configured == VisualEffectsConfig.Mode.SHADER || effect != Effect.VAPOR;
    }

    public static boolean shaderPackInUse() {
        if (simulatedPack != null) return simulatedPack;
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) return false;
        try {
            var type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var api = type.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(type.getMethod("isShaderPackInUse").invoke(api));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException | LinkageError error) {
            // If a shader mod changed its API, prefer the safe classic path.
            return true;
        }
    }

    public static int failureLogCount() { return failureLogCount; }

    @SubscribeEvent public static void debug(CustomizeGuiOverlayEvent.DebugText event) {
        event.getLeft().add("BWR visuals: vapor=" + label(Effect.VAPOR) + " cherenkov=" + label(Effect.CHERENKOV)
                + " water=" + label(Effect.WATER) + (shaderPackInUse() ? " (shader pack)" : ""));
    }

    private static String label(Effect effect) { return gpu(effect, true) ? "GPU" : "classic"; }
}
