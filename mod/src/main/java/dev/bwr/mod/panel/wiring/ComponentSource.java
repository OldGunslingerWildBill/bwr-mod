package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Input source {@code component}: an output of another component in the same panel group (a lamp that shows a
 * switch's position). The reference is the owner block position, the component id (stable across renames) and
 * the output index, all absolute.
 *
 * <p>Not polled: {@link PanelWiring} pushes the value whenever the referenced output changes or the owner
 * loads. A missing component, an unloaded owner or a different group reads NaN.
 */
public final class ComponentSource implements InputSource {
    public static final String KIND = "component";

    private final BlockPos owner;
    private final int componentId;
    private final int output;

    public ComponentSource(BlockPos owner, int componentId, int output) {
        this.owner = owner.immutable();
        this.componentId = componentId;
        this.output = output;
    }

    public BlockPos owner() {
        return owner;
    }

    public int componentId() {
        return componentId;
    }

    public int output() {
        return output;
    }

    public boolean refersTo(BlockPos ownerPos, int id, int index) {
        return output == index && componentId == id && owner.equals(ownerPos);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public double sample(SampleContext context) {
        if (!context.level().isLoaded(owner)
                || !(context.level().getBlockEntity(owner) instanceof PanelBlockEntity source)) {
            return Double.NaN;
        }
        PanelGroup group = context.owner().group();
        if (group == null || !group.contains(owner)) {
            return Double.NaN;
        }
        PanelComponent component = source.component(componentId);
        if (component == null || output < 0 || output >= component.outputCount()) {
            return Double.NaN;
        }
        return component.output(output);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("X", owner.getX());
        tag.putInt("Y", owner.getY());
        tag.putInt("Z", owner.getZ());
        tag.putInt("Id", componentId);
        tag.putInt("Output", output);
    }

    static InputSource load(CompoundTag tag) {
        if (!tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC) || !tag.contains("Id", Tag.TAG_ANY_NUMERIC)) {
            return InputSources.preserved(tag);
        }
        return new ComponentSource(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getInt("Id"), tag.getInt("Output"));
    }
}
