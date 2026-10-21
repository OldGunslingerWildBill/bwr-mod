package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Client side reverse index for the Networking Wrench lines: for a device, which components on the loaded panels
 * read it or command it through a line link ({@link PlantSource} / {@link PlantSink}). It holds no copy of any
 * binding: it remembers which panel block entities the client has, and a generation that changes whenever a panel
 * state packet arrives or a panel goes, so the lines are recomputed then and never per frame.
 *
 * <p>This class touches no client-only type, so the common panel code can call {@link #invalidate} on either side.
 */
public final class PanelNetworkIndex {
    private PanelNetworkIndex() {
    }

    /** The most lines the wrench draws for one hovered device. */
    public static final int MAX_LINES = 64;

    /** One component bound to a device: the panel block that owns it, the component, and whether it commands. */
    public record Ref(BlockPos panel, int componentId, boolean command, int port, BlockPos device) {
    }

    private static final Set<PanelBlockEntity> CLIENT_PANELS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile int generation;

    private static int cachedGeneration = -1;
    private static BlockPos cachedFor;
    private static Object cachedLevel;
    private static List<Ref> cachedRefs = List.of();

    /** The panel set or a panel's state changed: drop what was computed. */
    public static void invalidate() {
        generation++;
    }

    /** A client side panel block entity came into the level. */
    public static void attached(PanelBlockEntity be) {
        synchronized (CLIENT_PANELS) {
            CLIENT_PANELS.add(be);
        }
        invalidate();
    }

    /** A panel block entity left the level (or its chunk unloaded). */
    public static void detached(PanelBlockEntity be) {
        synchronized (CLIENT_PANELS) {
            CLIENT_PANELS.remove(be);
        }
        invalidate();
    }

    /**
     * Forgets every panel and what was computed from them: the client left its world (disconnect, level unload).
     * Vanilla drops a client level without calling {@code setRemoved} on its block entities, so this set would keep
     * every panel, and through it the whole old level, alive.
     */
    public static void clear() {
        synchronized (CLIENT_PANELS) {
            CLIENT_PANELS.clear();
        }
        cachedFor = null;
        cachedLevel = null;
        cachedRefs = List.of();
        invalidate();
    }

    /** Panels held, for tests. */
    public static int tracked() {
        synchronized (CLIENT_PANELS) {
            return CLIENT_PANELS.size();
        }
    }

    /** The generation, so a caller can tell whether anything it computed from the index is stale. */
    public static int generation() {
        return generation;
    }

    /**
     * The line-linked components that read or command the device whose block entity is {@code resolved} (the device
     * a position belongs to, or null to match the position itself), at most {@link #MAX_LINES}. Recomputed only when
     * the generation or the question changed.
     */
    public static List<Ref> refsFor(Level level, BlockPos hovered) {
        if (cachedGeneration == generation && hovered.equals(cachedFor) && cachedLevel == level) {
            return cachedRefs;
        }
        cachedGeneration = generation;
        cachedFor = hovered.immutable();
        cachedLevel = level;
        List<Ref> found = new ArrayList<>();
        List<PanelBlockEntity> panels;
        synchronized (CLIENT_PANELS) {
            panels = new ArrayList<>(CLIENT_PANELS);
        }
        for (PanelBlockEntity panel : panels) {
            if (panel.isRemoved() || panel.getLevel() != level) {
                continue;
            }
            for (PanelComponent component : panel.owned()) {
                if (component.type() == null) {
                    continue;
                }
                for (int i = 0; i < component.inputCount() && found.size() < MAX_LINES; i++) {
                    if (component.source(i) instanceof PlantSource plant
                            && dev.bwr.mod.panel.NetworkingWrenchItem.sameDevice(level, plant.device(), hovered)) {
                        found.add(new Ref(panel.getBlockPos(), component.id(), false, i, plant.device()));
                    }
                }
                for (int i = 0; i < component.outputCount(); i++) {
                    for (OutputSink sink : component.sinks(i)) {
                        if (sink instanceof PlantSink plant && found.size() < MAX_LINES
                                && dev.bwr.mod.panel.NetworkingWrenchItem.sameDevice(level, plant.device(), hovered)) {
                            found.add(new Ref(panel.getBlockPos(), component.id(), true, i, plant.device()));
                        }
                    }
                }
            }
        }
        cachedRefs = List.copyOf(found);
        return cachedRefs;
    }
}
