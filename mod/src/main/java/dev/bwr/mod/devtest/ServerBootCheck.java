package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Boot check for a real dedicated server ({@code :mod:runServerBootCheck}, see build.gradle). Inert without
 * {@code -Dbwr.serverBootCheck=true}. Once the server has printed "Done" it lets 200 ticks run (so the tick handlers of
 * the mod execute on a dedicated server), then stops it the ordinary way. The verdict is given by the Gradle task,
 * which reads logs/latest.log and the JVM's class-load log afterwards.
 *
 * <p>It also links every class of the mod that is not in a client package (Class.forName without initialising, then
 * getDeclaredMethods/getDeclaredFields, which makes the JVM verify the class and resolve its signature types). The
 * dedicated server's Minecraft jar has no net.minecraft.client classes, so a common class that depends on one fails here
 * with NoClassDefFoundError / VerifyError, logged as an ERROR "LINK FAIL" line, even if the server never reaches it at boot.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class ServerBootCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean started;
    private static int ticks;

    private ServerBootCheck() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("bwr.serverBootCheck")) return;
        started = true;
        LOGGER.info("BWR SERVER BOOT CHECK: server started");
        linkSweep();
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!started || ++ticks < 200) return;
        started = false;
        LOGGER.info("BWR SERVER BOOT CHECK: {} ticks ran, stopping", 200);
        event.getServer().halt(false);
    }

    private static void linkSweep() {
        ClassLoader loader = ServerBootCheck.class.getClassLoader();
        int linked = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (String name : classNames()) {
                try {
                    Class<?> c = Class.forName(name, false, loader);
                    c.getDeclaredMethods();
                    c.getDeclaredFields();
                    c.getDeclaredConstructors();
                    linked++;
                } catch (Throwable t) {
                    failed.add(name);
                    LOGGER.error("BWR SERVER BOOT CHECK: LINK FAIL {}: {}", name, t.toString());
                }
            }
        } catch (IOException | URISyntaxException e) {
            LOGGER.error("BWR SERVER BOOT CHECK: LINK FAIL cannot list the mod classes: {}", e.toString());
        }
        LOGGER.info("BWR SERVER BOOT CHECK: link sweep, {} common classes linked, {} failed", linked, failed.size());
    }

    /** Names of the mod's classes outside the client and devtest packages, from the directory or jar they were loaded from. */
    private static List<String> classNames() throws IOException, URISyntaxException {
        Path root = Path.of(BwrMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> add(names, root.relativize(p).toString().replace(java.io.File.separatorChar, '/')));
            }
        } else {
            try (JarFile jar = new JarFile(root.toFile())) {
                jar.stream().forEach(e -> add(names, e.getName()));
            }
        }
        return names;
    }

    private static void add(List<String> names, String entry) {
        if (!entry.startsWith("dev/bwr/") || !entry.endsWith(".class") || entry.contains("/client/")
                || entry.startsWith("dev/bwr/mod/devtest/") || entry.endsWith("package-info.class")
                || entry.endsWith("module-info.class")
                || (entry.startsWith("dev/bwr/mod/peripheral/") && !ModList.get().isLoaded("computercraft"))
                || (entry.startsWith("dev/bwr/mod/mekanism/") && !ModList.get().isLoaded("mekanism"))
                || (entry.startsWith("dev/bwr/mod/compat/coldsweat/") && !ModList.get().isLoaded("cold_sweat"))) {
            return; // the optional-mod packages are only ever touched behind an isLoaded check
        }
        names.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
    }
}
