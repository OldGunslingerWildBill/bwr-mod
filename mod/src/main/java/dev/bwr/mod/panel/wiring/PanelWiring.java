package dev.bwr.mod.panel.wiring;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.OutputSinks;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * The wiring layer's entry point: registers the source and sink kinds, listens on {@link PanelBus} and fans an
 * operated output out to its sinks and to the component inputs that read it.
 *
 * <p>Nothing here acts on its own. {@link #outputChanged} runs only because {@link PanelBlockEntity#setOutput}
 * ran, which only types call from {@code operate}/{@code use} (a player) so a load, a sample or a resync
 * never reaches a sink.
 */
public final class PanelWiring {
    private PanelWiring() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean bootstrapped;

    /** Registers the kinds and the bus listener. Idempotent; called from mod setup before any world loads. */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        InputSources.register(PlantSource.KIND, PlantSource::load);
        InputSources.register(ComponentSource.KIND, ComponentSource::load);
        InputSources.register(RedstoneSource.KIND, tag -> RedstoneSource.INSTANCE);
        InputSources.register(CcSource.KIND, CcSource::load);
        InputSources.register(CcPortSource.KIND, CcPortSource::load);
        OutputSinks.register(PlantSink.KIND, PlantSink::load);
        OutputSinks.register(RedstoneSink.KIND, RedstoneSink::load);
        PanelBus.addOutputListener(PanelWiring::outputChanged);
    }

    private static void outputChanged(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int index,
                                      double value) {
        pushToReaders(owner, component, index, value);
        boolean redstone = false;
        OutputSink.FireContext context = new OutputSink.FireContext(level, owner, component, index);
        for (OutputSink sink : component.sinks(index)) {
            if (sink instanceof RedstoneSink) {
                redstone = true;
            }
            try {
                sink.fire(context, value);
            } catch (RuntimeException e) {
                LOGGER.warn("Panel sink {} of {} failed", sink.kind(), component.label(), e);
            }
        }
        if (redstone) {
            owner.updateRedstoneOutput();
        }
    }

    // ---- component to component

    /** Pushes the current value of output {@code index} to every input in the group that reads it. */
    public static void pushToReaders(PanelBlockEntity owner, PanelComponent component, int index, double value) {
        forEachReader(owner, owner.getBlockPos(), component.id(), index, (be, reader, input) -> be.setInput(reader, input, value));
    }

    /** Re-reads every input in the group that reads any output of component {@code id} (it was removed, or loaded). */
    public static void refreshReaders(PanelBlockEntity owner, BlockPos ownerPos, int id) {
        dev.bwr.mod.advancement.PlantMilestones.beginPanelEdit();
        try {
            forEachReader(owner, ownerPos, id, -1, (be, reader, input) -> be.resample(reader, true));
        } finally {
            dev.bwr.mod.advancement.PlantMilestones.endPanelEdit();
        }
    }

    @FunctionalInterface
    private interface ReaderVisitor {
        void visit(PanelBlockEntity block, PanelComponent reader, int input);
    }

    private static void forEachReader(PanelBlockEntity owner, BlockPos ownerPos, int id, int index, ReaderVisitor visitor) {
        PanelGroup group = owner.group();
        if (group == null || owner.getLevel() == null) {
            return;
        }
        for (BlockPos pos : group.members()) {
            if (!owner.getLevel().isLoaded(pos) || !(owner.getLevel().getBlockEntity(pos) instanceof PanelBlockEntity be)) {
                continue;
            }
            for (PanelComponent reader : be.owned()) {
                for (int input = 0; input < reader.inputCount(); input++) {
                    if (reader.source(input) instanceof ComponentSource source
                            && source.owner().equals(ownerPos) && source.componentId() == id
                            && (index < 0 || source.output() == index)) {
                        visitor.visit(be, reader, input);
                    }
                }
            }
        }
    }

    // ---- CC

    /**
     * Sets the value of a CC-sourced input.
     *
     * @return false when the input does not exist or is not CC-sourced
     */
    public static boolean setCc(PanelBlockEntity owner, PanelComponent component, int input, double value) {
        if (input < 0 || input >= component.inputCount() || !(component.source(input) instanceof CcSource source)
                || !Double.isFinite(value)) {
            return false;
        }
        source.set(value);
        owner.setInput(component, input, value);
        owner.setChanged();
        return true;
    }
}
