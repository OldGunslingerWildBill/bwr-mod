package dev.bwr.mod.devtest;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches every chunk load of a development run and reports the ones that were started by our own code.
 *
 * <p>A chunk that loads while a {@code dev.bwr.mod} frame outside the test classes is on the stack was loaded
 * synchronously by a read of a block or block entity in it (the load blocks the reading thread, and the event fires on
 * the same stack). A load requested by a ticket arrives on the server's own task stack and is not reported, and neither
 * are the loads tests make themselves ({@code dev.bwr.mod.devtest} frames only). Printed once per distinct call path
 * as {@code SYNC LOAD AUDIT}, with a count of the rest at the end of the run: the whole GameTest suite is thereby an
 * audit of every place our code reads a position it did not check.
 */
@EventBusSubscriber(modid = "bwr")
public final class SyncLoadAudit {
    private SyncLoadAudit() {
    }

    private static final Map<String, AtomicInteger> PATHS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onLoad(ChunkEvent.Load event) {
        List<String> ours = new ArrayList<>();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("dev.bwr.mod.") && !cls.startsWith("dev.bwr.mod.devtest.") && !cls.equals(SyncLoadAudit.class.getName())) {
                ours.add(cls.substring("dev.bwr.mod.".length()) + "." + frame.getMethodName() + ":" + frame.getLineNumber());
                if (ours.size() == 4) {
                    break;
                }
            }
        }
        if (ours.isEmpty()) {
            return;
        }
        String path = String.join(" <- ", ours);
        if (PATHS.computeIfAbsent(path, k -> new AtomicInteger()).getAndIncrement() == 0) {
            System.out.println("SYNC LOAD AUDIT chunk " + event.getChunk().getPos() + " loaded by " + path);
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        if (PATHS.isEmpty()) {
            System.out.println("SYNC LOAD AUDIT: no chunk was loaded by our code during this run");
            return;
        }
        System.out.println("SYNC LOAD AUDIT summary: " + PATHS.size() + " call path(s)");
        new TreeMap<>(PATHS).forEach((path, count) -> System.out.println("SYNC LOAD AUDIT   " + count + " x " + path));
    }
}
