package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;

/**
 * Reports which optional render mods a client check is running under, so a screenshot can never be mistaken for the
 * wrong configuration. Sodium and Iris are run-only test dependencies the mod neither needs nor references: this goes
 * through the mod list and reflection only, never a compile-time type. Part of the stripped devtest package.
 */
final class RenderEnvironment {
    private static boolean logged;

    private RenderEnvironment() {}

    /** Logs {@code RENDER ENV ...} once per run: Sodium and Iris versions, and whether a shader pack is really in use. */
    static void log(String check) {
        if (logged) return;
        logged = true;
        LogUtils.getLogger().info("RENDER ENV check={} sodium={} iris={} shaderPackInUse={}", check,
                version("sodium"), version("iris"), shaderPackInUse());
    }

    static String version(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("none");
    }

    /** Iris's own public API, by reflection: {@code IrisApi.getInstance().isShaderPackInUse()}; false when Iris is absent. */
    static boolean shaderPackInUse() {
        if (!ModList.get().isLoaded("iris")) return false;
        try {
            Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LogUtils.getLogger().warn("RENDER ENV could not ask Iris whether a pack is in use", ex);
            return false;
        }
    }

    // ---- a sampling profiler for the render thread, opt in with -Dbwr.renderProfile=true (diagnosis only)

    private static final java.util.Map<String, Integer> TOP = new java.util.HashMap<>(), MINE = new java.util.HashMap<>(),
            PACK = new java.util.HashMap<>(), CALLER = new java.util.HashMap<>();
    private static int samples;
    private static Thread sampler;

    /** Starts sampling the render thread every 2 ms (once). */
    static void startProfile() {
        if (!Boolean.getBoolean("bwr.renderProfile") || sampler != null) return;
        Thread render = Thread.currentThread();
        sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
                StackTraceElement[] stack = render.getStackTrace();
                if (stack.length == 0) continue;
                synchronized (TOP) {
                    samples++;
                    TOP.merge(stack[0].getClassName() + "." + stack[0].getMethodName(), 1, Integer::sum);
                    String mine = null, pack = null, caller = null;
                    for (StackTraceElement e : stack) {
                        String c = e.getClassName();
                        if (mine == null && c.startsWith("dev.bwr")) mine = c + "." + e.getMethodName();
                        if (pack == null && (c.startsWith("net.irisshaders") || c.startsWith("net.caffeinemc"))) pack = c + "." + e.getMethodName();
                        if (caller == null && c.startsWith("net.minecraft.client.renderer") && !c.contains("$")) caller = c + "." + e.getMethodName();
                    }
                    if (mine != null) MINE.merge(mine, 1, Integer::sum);
                    if (pack != null) PACK.merge(pack, 1, Integer::sum);
                    if (caller != null) CALLER.merge(caller, 1, Integer::sum);
                }
            }
        }, "bwr-render-profile");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** Logs the busiest places seen since the last call, and clears the tallies. */
    static void dumpProfile(String label) {
        if (sampler == null) return;
        synchronized (TOP) {
            log(label, "top of stack", TOP);
            log(label, "first dev.bwr frame", MINE);
            log(label, "first iris/sodium frame", PACK);
            log(label, "first renderer frame", CALLER);
            LogUtils.getLogger().info("RENDER PROFILE {}: {} samples", label, samples);
            TOP.clear();
            MINE.clear();
            PACK.clear();
            CALLER.clear();
            samples = 0;
        }
    }

    private static void log(String label, String what, java.util.Map<String, Integer> tally) {
        StringBuilder text = new StringBuilder();
        tally.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                .forEach(e -> text.append(" | ").append(e.getValue()).append("  ").append(e.getKey()));
        LogUtils.getLogger().info("RENDER PROFILE {} {}:{}", label, what, text);
    }
}
