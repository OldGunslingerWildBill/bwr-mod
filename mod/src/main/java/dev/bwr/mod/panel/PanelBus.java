package dev.bwr.mod.panel;

import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side notification seam for the wiring layer. The framework never acts
 * on the plant itself: when a player operates an input component its output
 * value changes, and listeners registered here (plant commands, redstone,
 * component-to-component wiring, the CC event) decide what, if anything,
 * follows.
 */
public final class PanelBus {
    private PanelBus() {
    }

    @FunctionalInterface
    public interface OutputListener {
        /** An output of {@code component} (owned by {@code owner}) just took a new value. */
        void outputChanged(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int index, double value);
    }

    private static final List<OutputListener> OUTPUT_LISTENERS = new CopyOnWriteArrayList<>();

    public static void addOutputListener(OutputListener listener) {
        OUTPUT_LISTENERS.add(listener);
    }

    static void fireOutput(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int index, double value) {
        for (OutputListener listener : OUTPUT_LISTENERS) {
            listener.outputChanged(level, owner, component, index, value);
        }
    }
}
