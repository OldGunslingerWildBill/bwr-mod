package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * Where one component input gets its value: the seam between the panel
 * framework and the wiring/signal layer.
 *
 * <p>The foundation ships exactly two kinds, {@code none} ("no signal") and
 * {@code constant}. The wiring layer adds plant-signal, component, redstone and
 * CC kinds by implementing this interface and calling
 * {@link InputSources#register}; nothing in the framework has to change. Rules
 * for implementors:
 * <ul>
 *   <li>{@link #sample} runs on the server only, never loads a chunk, and
 *       returns {@link Double#NaN} when there is no signal (unloaded device,
 *       missing device, nothing set). It is called when the source is
 *       (re)configured and, for live kinds, by the group's sampler every
 *       {@link dev.bwr.mod.panel.PanelConfig#sampleTicks()} ticks.</li>
 *   <li>Push the result into the panel with
 *       {@link PanelBlockEntity#setInput}; that is the only path that stores the
 *       value, syncs the delta to watching clients and notifies the type.</li>
 *   <li>{@link #save} writes the kind's own fields only; {@link InputSources}
 *       adds the {@code Kind} key. Positions are saved absolute.</li>
 * </ul>
 */
public interface InputSource {
    /** Stable kind id as saved ("none", "constant", ...). */
    String kind();

    /** Current value of this source, or NaN for "no signal". */
    double sample(SampleContext context);

    /** Writes this source's own fields. */
    void save(CompoundTag tag);

    /** Whether the wiring layer must keep re-sampling this source (false for none/constant). */
    default boolean isLive() {
        return false;
    }

    /** What a source may look at while sampling. */
    record SampleContext(Level level, PanelBlockEntity owner, PanelComponent component, int inputIndex) {
    }
}
