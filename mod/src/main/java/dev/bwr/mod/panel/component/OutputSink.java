package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Where one output of an input component goes: the counterpart of {@link InputSource}. An output fans out to
 * any number of sinks (plant command, redstone, ...), saved per output in the component.
 *
 * <p>Sinks never run on their own. {@link #fire} is called only when a player (or a CC program the player
 * wrote) changed the output of an input component; loading a world, sampling and re-syncing never fire a
 * sink, so nothing on a panel acts automatically. Kinds are added with {@link OutputSinks#register}.
 */
public interface OutputSink {
    /** Stable kind id as saved ("plant", "redstone", ...). */
    String kind();

    /** Writes this sink's own fields; {@link OutputSinks} adds the {@code Kind} key. Positions are absolute. */
    void save(CompoundTag tag);

    /** The output just took {@code value} because it was operated. Server only. */
    default void fire(FireContext context, double value) {
    }

    /** What a sink may look at while firing. */
    record FireContext(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int outputIndex) {
    }
}
