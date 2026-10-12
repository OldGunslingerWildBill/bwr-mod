package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bwr_panel}: the whole panel group, seen from any of its blocks (spec
 * section 8). This is the read-only foundation; the CC step adds {@code set},
 * {@code setText}, the display drawing calls and the {@code bwr_panel_input}
 * event on top of the same component lookup.
 */
public final class PanelPeripheral implements IPeripheral {
    private final PanelBlockEntity block;

    public PanelPeripheral(PanelBlockEntity block) {
        this.block = block;
    }

    @Override
    public String getType() {
        return "bwr_panel";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof PanelPeripheral p && p.block == block;
    }

    /** Every component in the group by label: {@code {type, width, height}}. */
    @LuaFunction(mainThread = true)
    public Map<String, Object> list() {
        Map<String, Object> labels = new LinkedHashMap<>();
        PanelGroup group = block.group();
        if (group == null || block.getLevel() == null) {
            return labels;
        }
        for (BlockPos pos : group.members()) {
            if (block.getLevel().isLoaded(pos) && block.getLevel().getBlockEntity(pos) instanceof PanelBlockEntity be) {
                for (PanelComponent component : be.owned()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", component.type() == null ? "unknown" : component.type().id().toString());
                    entry.put("width", component.w());
                    entry.put("height", component.h());
                    labels.put(component.label(), entry);
                }
            }
        }
        return labels;
    }

    /** One component: its type, footprint and live input/output values (a port with no signal is absent). */
    @LuaFunction(mainThread = true)
    public Map<String, Object> get(String label) throws LuaException {
        PanelGroup group = block.group();
        if (group != null && block.getLevel() != null) {
            for (BlockPos pos : group.members()) {
                if (block.getLevel().isLoaded(pos) && block.getLevel().getBlockEntity(pos) instanceof PanelBlockEntity be) {
                    for (PanelComponent component : be.owned()) {
                        if (component.label().equals(label) && component.type() != null) {
                            return describe(component);
                        }
                    }
                }
            }
        }
        throw new LuaException("No component labelled " + label);
    }

    private static Map<String, Object> describe(PanelComponent component) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", component.type().id().toString());
        result.put("label", component.label());
        result.put("width", component.w());
        result.put("height", component.h());
        Map<String, Object> inputs = new LinkedHashMap<>();
        List<PortSpec> inputPorts = component.type().inputPorts(component.data());
        for (int i = 0; i < inputPorts.size(); i++) {
            double value = component.input(i);
            if (!Double.isNaN(value)) {
                inputs.put(inputPorts.get(i).key(), value);
            }
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<PortSpec> outputPorts = component.type().outputPorts(component.data());
        for (int i = 0; i < outputPorts.size(); i++) {
            outputs.put(outputPorts.get(i).key(), component.output(i));
        }
        result.put("inputs", inputs);
        result.put("outputs", outputs);
        return result;
    }
}
