package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * What the full core display and the rod select matrix have in common as component types: one input that names the
 * reactor, a span of up to 8 blocks (a 185-rod core is 96 cells across at the default tile size), and the refusal of
 * a binding or a tile size whose drawing would not fit the component.
 */
public abstract class CoreDisplayType extends PanelInputType {
    /** The largest span of either type, in blocks: 8 x 8 blocks is 128 x 128 cells. */
    public static final int SPAN_BLOCKS = 8;
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("core"));

    protected CoreDisplayType(ResourceLocation id) {
        super(id);
    }

    @Override
    public final int maxSpanBlocks() {
        return SPAN_BLOCKS;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public Component refuseBinding(ServerLevel level, PanelComponent component, int inputIndex, BlockPos device) {
        if (inputIndex != 0) {
            return null;
        }
        if (!level.isLoaded(device)
                || !(dev.bwr.mod.signal.DeviceResolver.resolve(level, device) instanceof ReactorControllerBlockEntity reactor)) {
            return dev.bwr.mod.text.BwrText.tr("message.bwr.core_display.not_a_reactor",
                    "A core display reads a reactor controller: link the reactor, not another device");
        }
        CoreRodMap map = reactor.isFormed() ? reactor.coreMap() : null;
        return map == null ? null : tooSmall(component, (CoreDisplayData) component.data(), map, new CompoundTag());
    }

    @Override
    public Component refuseConfig(ServerLevel level, PanelComponent component, CompoundTag proposed) {
        CoreDisplayData data = (CoreDisplayData) component.data();
        CoreRodMap map = data.snapshot().formed() ? data.snapshot().cells().map() : null;
        return map == null ? null : tooSmall(component, data, map, proposed);
    }

    private static Component tooSmall(PanelComponent component, CoreDisplayData data, CoreRodMap map, CompoundTag config) {
        int[] needed = data.neededCells(map, config);
        if (component.w() >= needed[0] && component.h() >= needed[1]) {
            return null;
        }
        int blocksW = (needed[0] + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        int blocksH = (needed[1] + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        return dev.bwr.mod.text.BwrText.tr("message.bwr.core_display.too_small",
                "This core needs %s x %s cells (%s x %s blocks) at this tile size; the component is %s x %s. Use a larger size or fewer cells per tile",
                needed[0], needed[1], blocksW, blocksH, component.w(), component.h());
    }
}
