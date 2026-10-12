package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.component.InputSource;
import net.minecraft.nbt.CompoundTag;

/**
 * Input source {@code redstone}: the signal strength (0 to 15) arriving at the back face of the panel block
 * that owns the component. See {@link PanelRedstone#inputAt}. Sampled by the panel sampler and re-read at once
 * when a neighbour of the block changes.
 */
public final class RedstoneSource implements InputSource {
    public static final String KIND = "redstone";
    public static final RedstoneSource INSTANCE = new RedstoneSource();

    private RedstoneSource() {
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public double sample(SampleContext context) {
        return PanelRedstone.inputAt(context.level(), context.owner().getBlockPos(), context.owner().facing());
    }

    @Override
    public void save(CompoundTag tag) {
    }
}
