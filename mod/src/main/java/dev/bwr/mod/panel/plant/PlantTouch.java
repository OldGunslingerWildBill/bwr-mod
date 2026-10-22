package dev.bwr.mod.panel.plant;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a touched soft key goes. The plant computer is read-only towards the plant: a touch changes nothing but the
 * display's own page (a built-in page) or raises an event for a CC program, which does what its author wrote. This is the
 * seam between the CC-free panel code and the CC peripheral, like {@code PanelBus} is for the controls.
 */
public final class PlantTouch {
    private PlantTouch() {
    }

    /** Receives every soft key touch of a program page. */
    public interface Listener {
        /** {@code page} is the page shown (1-based), {@code key} the soft key (1 to {@link PlantLimits#MAX_KEYS}). */
        void touched(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int page, int key);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void fire(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int page, int key) {
        for (Listener listener : LISTENERS) {
            listener.touched(level, owner, component, page, key);
        }
    }
}
