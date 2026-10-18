package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.ComponentSource;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.RedstoneSink;
import dev.bwr.mod.panel.wiring.RedstoneSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.signal.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The state behind the linker's binding screen: which port is being wired, to what, and the packet that
 * results. No widgets and no layout, so a later GUI overhaul can replace {@link BindScreen} and
 * {@link BindLayout} without touching this. Everything it sends is re-validated by the server
 * ({@code WiringOps}); this class only builds sensible requests.
 */
public final class BindModel {
    /** What to wire a port to. Outputs offer PLANT and REDSTONE; inputs offer all four. */
    public enum Mode {
        PLANT, COMPONENT, REDSTONE, CC;

        public String translationKey() {
            return "screen.bwr.panel_bind.mode." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** One selectable port: an input or an output of the component. */
    public record PortChoice(boolean output, int index, PortSpec spec) {
        public Component text() {
            return Component.translatable(output ? "screen.bwr.panel_bind.outputs" : "screen.bwr.panel_bind.inputs")
                    .append(": ").append(Component.translatable(spec.labelKey()));
        }
    }

    /** One line of the selection list. */
    public record Entry(String key, Component label, BlockPos refOwner, int refId, int refOutput) {
    }

    public final BlockPos owner;
    public final int componentId;
    public final String componentLabel;
    public final PanelPayloads.LinkerScreen device;
    public final List<PortChoice> choices = new ArrayList<>();
    private final PanelBlockEntity block;
    private final PanelComponent component;

    public PortChoice port;
    public Mode mode;
    public String filter = "";
    public String scaleText = "1";
    /** Plant input transform: displayed = raw x scale + offset. */
    public String offsetText = "0";
    public Entry selected;
    /** Plant output sink mode: false follows the output (value x scale), true sends {@link #fixedText} on a rise. */
    public boolean momentary;
    public String fixedText = "1";
    /** Whether changing port or mode preselects the best fitting entry again (set by {@link #preselect}). */
    private boolean autoSelect;

    public BindModel(PanelBlockEntity block, PanelComponent component, PanelPayloads.LinkerScreen device) {
        this.block = block;
        this.component = component;
        this.owner = block.getBlockPos();
        this.componentId = component.id();
        this.componentLabel = component.label();
        this.device = device;
        List<PortSpec> inputs = component.type().inputPorts(component.data());
        for (int i = 0; i < inputs.size(); i++) {
            choices.add(new PortChoice(false, i, inputs.get(i)));
        }
        List<PortSpec> outputs = component.type().outputPorts(component.data());
        for (int i = 0; i < outputs.size(); i++) {
            choices.add(new PortChoice(true, i, outputs.get(i)));
        }
        choose(choices.isEmpty() ? null : choices.get(0));
    }

    public boolean hasPorts() {
        return port != null;
    }

    public boolean deviceUsable() {
        return device.status() == PanelPayloads.LINK_OK;
    }

    public void choose(PortChoice choice) {
        this.port = choice;
        this.selected = null;
        resetMomentary();
        List<Mode> modes = modes();
        if (mode == null || !modes.contains(mode)) {
            mode = modes.get(0);
        }
        scaleText = defaultScale();
        resetTransform();
        if (autoSelect) {
            preselect();
        }
    }

    public List<Mode> modes() {
        if (port != null && port.output()) {
            return List.of(Mode.PLANT, Mode.REDSTONE);
        }
        return List.of(Mode.PLANT, Mode.COMPONENT, Mode.REDSTONE, Mode.CC);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.selected = null;
        resetMomentary();
        scaleText = defaultScale();
        resetTransform();
        if (autoSelect) {
            preselect();
        }
    }

    /** An input's transform starts at the current plant binding's (so editing keeps it) or at identity. */
    private void resetTransform() {
        offsetText = "0";
        if (port != null && !port.output() && mode == Mode.PLANT && component.source(port.index()) instanceof PlantSource plant) {
            scaleText = trim(plant.scale());
            offsetText = trim(plant.offset());
        }
    }

    /** Preset: the raw signal unchanged. */
    public void presetRaw() {
        scaleText = "1";
        offsetText = "0";
    }

    /** Preset: a 0 to 1 fraction shown as 0 to 100 percent. */
    public void presetFractionToPercent() {
        scaleText = "100";
        offsetText = "0";
    }

    private void resetMomentary() {
        momentary = false;
        fixedText = "1";
    }

    /** Selects a list entry; the momentary default applies here, and the player may change it afterwards. */
    public void select(Entry entry) {
        selected = entry;
        resetMomentary();
        if (mode == Mode.PLANT && port != null && port.output() && entry != null) {
            PanelPayloads.CommandEntry command = command(entry.key());
            String type = component.type().id().getPath();
            String output = port.spec().key();
            boolean switchPort = type.equals("control_switch") && (output.equals("open") || output.equals("close"));
            boolean button = type.equals("pushbutton");
            if (command != null && (switchPort || button) && !isHeldControl(command.key())
                    && (command.kind() == Kind.BOOLEAN.ordinal() || command.kind() == Kind.LEVEL.ordinal())) {
                momentary = true;
                // CLOSE sends the "off" end of the command; OPEN and a pushbutton send "on".
                fixedText = output.equals("close") ? "0" : "1";
            }
        }
    }

    /**
     * Commands that start on 1 and stop on 0 (the continuous rod motion controls): they need the follow mode, which
     * sends the release, so the momentary default is not offered for them.
     */
    static boolean isHeldControl(String key) {
        return key.endsWith("_continuous");
    }

    private PanelPayloads.CommandEntry command(String key) {
        for (PanelPayloads.CommandEntry command : device.commands()) {
            if (command.key().equals(key)) {
                return command;
            }
        }
        return null;
    }

    /** Whether the follow / momentary choice is offered: a plant command on an output. */
    public boolean usesSinkMode() {
        return port != null && port.output() && mode == Mode.PLANT;
    }

    public boolean usesFixed() {
        return usesSinkMode() && momentary;
    }

    /** The fixed value, or null when it is not a finite number inside the selected command's range. */
    public Double fixed() {
        try {
            double value = Double.parseDouble(fixedText.trim());
            if (!Double.isFinite(value)) {
                return null;
            }
            PanelPayloads.CommandEntry command = selected == null ? null : command(selected.key());
            return command == null || value >= command.min() && value <= command.max() ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A redstone sink usually wants a full-strength signal from a 0/1 switch; plant commands default to 1:1. */
    private String defaultScale() {
        return port != null && port.output() && mode == Mode.REDSTONE ? "15" : "1";
    }

    /** Whether the list of choices is shown for the current mode. */
    public boolean listed() {
        return mode == Mode.PLANT && deviceUsable() || mode == Mode.COMPONENT;
    }

    /** Whether the Scale and Offset fields of a plant input (displayed = raw x scale + offset) are shown. */
    public boolean usesTransform() {
        return port != null && !port.output() && mode == Mode.PLANT;
    }

    public boolean usesScale() {
        return port != null && port.output() && (mode == Mode.PLANT && !momentary || mode == Mode.REDSTONE);
    }

    /** The entries of the selection list for the current port and mode, narrowed by the filter. */
    public List<Entry> entries() {
        List<Entry> all = new ArrayList<>();
        if (port == null) {
            return all;
        }
        String needle = filter.toLowerCase(Locale.ROOT).strip();
        if (mode == Mode.PLANT && deviceUsable()) {
            if (port.output()) {
                for (PanelPayloads.CommandEntry command : device.commands()) {
                    all.add(new Entry(command.key(), Component.literal(command.label() + (command.pulse() ? " (pulse)" : "")
                            + "  [" + command.key() + "]"), BlockPos.ZERO, 0, 0));
                }
            } else {
                dev.bwr.mod.signal.Quantity wanted = measures();
                for (PanelPayloads.SignalEntry signal : device.signals()) {
                    if (wanted != null && signal.quantity() != wanted.ordinal()) {
                        continue; // a gauge that measures one quantity is offered only the signals of that quantity
                    }
                    all.add(new Entry(signal.key(), Component.literal(signal.label()
                            + (signal.unit().isEmpty() ? "" : " (" + signal.unit() + ")") + "  [" + signal.key() + "]"),
                            BlockPos.ZERO, 0, 0));
                }
            }
        } else if (mode == Mode.COMPONENT && !port.output()) {
            PanelGroup group = block.group();
            if (group != null && block.getLevel() != null) {
                for (BlockPos pos : group.members()) {
                    if (block.getLevel().isLoaded(pos) && block.getLevel().getBlockEntity(pos) instanceof PanelBlockEntity be) {
                        for (PanelComponent other : be.owned()) {
                            if (other == component || other.type() == null) {
                                continue;
                            }
                            List<PortSpec> outputs = other.type().outputPorts(other.data());
                            for (int i = 0; i < outputs.size(); i++) {
                                all.add(new Entry(other.label() + "." + outputs.get(i).key(),
                                        Component.literal(other.label() + "." + outputs.get(i).key()), pos, other.id(), i));
                            }
                        }
                    }
                }
            }
        }
        if (needle.isEmpty()) {
            return all;
        }
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : all) {
            if (entry.label().getString().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(entry);
            }
        }
        return matching;
    }

    /** What the selected input is set to measure (the gauge setting {@code Measures}), or null for ANY. */
    public dev.bwr.mod.signal.Quantity measures() {
        return port != null && !port.output() ? component.data().measures(port.index()) : null;
    }

    /**
     * Preselects the best fitting entry of the current port and mode, so one click on Apply confirms (the Networking
     * Wrench does this; the Panel Linker does not). A lamp or annunciator input prefers a BOOLEAN signal, any other
     * input the first numeric one that the component's Measures setting accepts; an output prefers the first command
     * a switch or button can send. Nothing is bound until Apply.
     */
    public void preselect() {
        autoSelect = true;
        if (port == null || mode != Mode.PLANT || !deviceUsable()) {
            return;
        }
        List<Entry> all = entries();
        if (all.isEmpty()) {
            return;
        }
        if (port.output()) {
            for (Entry entry : all) {
                PanelPayloads.CommandEntry command = command(entry.key());
                if (command != null && command.kind() != Kind.TEXT.ordinal()) {
                    select(entry);
                    return;
                }
            }
            return;
        }
        if (component.source(port.index()) instanceof PlantSource bound && bound.device().equals(device.device())) {
            for (Entry entry : all) {
                if (entry.key().equals(bound.key())) {
                    select(entry); // editing a binding to this device keeps its signal
                    return;
                }
            }
        }
        String type = component.type().id().getPath();
        boolean wantsBoolean = type.equals("lamp") || type.equals("annunciator");
        int boolQuantity = dev.bwr.mod.signal.Quantity.BOOLEAN.ordinal();
        Entry best = null;
        for (Entry entry : all) {
            int quantity = -1;
            for (PanelPayloads.SignalEntry signal : device.signals()) {
                if (signal.key().equals(entry.key())) {
                    quantity = signal.quantity();
                }
            }
            if (wantsBoolean == (quantity == boolQuantity)) {
                best = entry;
                break;
            }
        }
        select(best != null ? best : all.get(0));
    }

    /** Whether Apply has everything it needs. */
    public boolean canApply() {
        if (port == null) {
            return false;
        }
        if ((usesScale() || usesTransform()) && scale() == null || usesTransform() && offset() == null
                || usesFixed() && fixed() == null) {
            return false;
        }
        return switch (mode) {
            case PLANT, COMPONENT -> selected != null;
            case REDSTONE, CC -> true;
        };
    }

    public Double scale() {
        try {
            double value = Double.parseDouble(scaleText.trim());
            return Double.isFinite(value) && value != 0.0 && Math.abs(value) <= 1.0e6 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Double offset() {
        try {
            double value = Double.parseDouble(offsetText.trim());
            return Double.isFinite(value) && Math.abs(value) <= 1.0e6 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The bind packet for the current selection; only call when {@link #canApply()}. */
    public PanelPayloads.Bind toBind() {
        WiringOps.BindKind kind = switch (mode) {
            case PLANT -> WiringOps.BindKind.PLANT;
            case COMPONENT -> WiringOps.BindKind.COMPONENT;
            case REDSTONE -> WiringOps.BindKind.REDSTONE;
            case CC -> WiringOps.BindKind.CC;
        };
        Entry entry = selected;
        boolean plant = mode == Mode.PLANT && entry != null;
        return new PanelPayloads.Bind(owner, componentId, port.output(), port.index(), kind.ordinal(),
                plant ? device.device() : BlockPos.ZERO, plant ? entry.key() : "",
                mode == Mode.COMPONENT && entry != null ? entry.refOwner() : BlockPos.ZERO,
                mode == Mode.COMPONENT && entry != null ? entry.refId() : 0,
                mode == Mode.COMPONENT && entry != null ? entry.refOutput() : 0,
                (usesScale() || usesTransform()) && scale() != null ? scale() : 1.0,
                usesFixed() ? PlantSink.Mode.MOMENTARY.ordinal() : PlantSink.Mode.FOLLOW.ordinal(),
                usesFixed() && fixed() != null ? fixed() : 0.0,
                usesTransform() && offset() != null ? offset() : 0.0);
    }

    /** Clears the current port (input to no signal, output to no sinks). */
    public PanelPayloads.Bind toClear() {
        return new PanelPayloads.Bind(owner, componentId, port.output(), port.index(), WiringOps.BindKind.NONE.ordinal(),
                BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0);
    }

    public PanelPayloads.Bind toRemoveSink(int position) {
        return new PanelPayloads.Bind(owner, componentId, true, port.index(), WiringOps.BindKind.REMOVE_SINK.ordinal(),
                BlockPos.ZERO, "", BlockPos.ZERO, 0, position, 1.0);
    }

    /** How the current port is wired now: the source of an input, or the sinks of an output. */
    public List<Component> current() {
        List<Component> lines = new ArrayList<>();
        if (port == null) {
            return lines;
        }
        if (port.output()) {
            for (OutputSink sink : component.sinks(port.index())) {
                lines.add(describeSink(sink));
            }
            if (lines.isEmpty()) {
                lines.add(Component.translatable("sink.bwr.panel.none"));
            }
            lines.add(Component.translatable("sink.bwr.panel.cc"));
        } else {
            lines.add(describeSource(component.source(port.index())));
        }
        return lines;
    }

    public int sinkCount() {
        return port != null && port.output() ? component.sinks(port.index()).size() : 0;
    }

    public Component sinkText(int position) {
        return describeSink(component.sinks(port.index()).get(position));
    }

    // ---- descriptions (also used by the wrench screen)

    public static Component describeSource(InputSource source) {
        if (source instanceof PlantSource plant) {
            BlockPos device = plant.device();
            if (plant.scale() != 1.0 || plant.offset() != 0.0) {
                return Component.translatable("source.bwr.panel.plant_scaled", plant.key(), device.getX(), device.getY(),
                        device.getZ(), trim(plant.scale()), trim(plant.offset()));
            }
            return Component.translatable("source.bwr.panel.plant", plant.key(), device.getX(), device.getY(), device.getZ());
        }
        if (source instanceof ComponentSource reference) {
            return Component.translatable("source.bwr.panel.component", referenceLabel(reference));
        }
        if (source instanceof RedstoneSource) {
            return Component.translatable("source.bwr.panel.redstone");
        }
        if (source instanceof CcSource cc) {
            return Component.translatable(cc.awaiting() ? "source.bwr.panel.cc_awaiting" : "source.bwr.panel.cc");
        }
        if (InputSources.KIND_NONE.equals(source.kind())) {
            return Component.translatable("source.bwr.panel.none");
        }
        return Component.translatable("source.bwr.panel.other", source.kind());
    }

    public static Component describeSink(OutputSink sink) {
        if (sink instanceof PlantSink plant) {
            BlockPos device = plant.device();
            if (plant.mode() == PlantSink.Mode.MOMENTARY) {
                return Component.translatable("sink.bwr.panel.plant_momentary", plant.key(), device.getX(), device.getY(),
                        device.getZ(), trim(plant.fixed()));
            }
            return Component.translatable("sink.bwr.panel.plant", plant.key(), device.getX(), device.getY(), device.getZ(),
                    trim(plant.scale()));
        }
        if (sink instanceof RedstoneSink redstone) {
            return Component.translatable("sink.bwr.panel.redstone", trim(redstone.scale()));
        }
        return Component.translatable("sink.bwr.panel.other", sink.kind());
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.3g", value);
    }

    /** "LABEL.port" of a referenced component, or its raw id when the client does not know it. */
    private static String referenceLabel(ComponentSource reference) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.isLoaded(reference.owner())
                && minecraft.level.getBlockEntity(reference.owner()) instanceof PanelBlockEntity be) {
            PanelComponent target = be.component(reference.componentId());
            if (target != null && target.type() != null) {
                List<PortSpec> outputs = target.type().outputPorts(target.data());
                String port = reference.output() >= 0 && reference.output() < outputs.size()
                        ? outputs.get(reference.output()).key() : Integer.toString(reference.output());
                return target.label() + "." + port;
            }
        }
        return "#" + reference.componentId() + "." + reference.output();
    }
}
