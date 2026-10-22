package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One component instance on a panel: type, place on the grid, label, the
 * type's {@link ComponentData}, its input {@link InputSource}s and the live
 * input and output values.
 *
 * <p>Ownership: a component belongs to the {@link dev.bwr.mod.panel.PanelBlockEntity}
 * of the block containing its anchor cell ({@link #x()}, {@link #y()} are
 * 0..15 inside that block). {@link #id()} is unique within that block entity;
 * {@code (owner pos, id)} identifies a component everywhere (packets, CC).
 *
 * <h3>Live values</h3>
 * <ul>
 *   <li>{@link #input(int)}: what the input currently reads. {@code NaN} is "no
 *       signal". Set through {@code PanelBlockEntity#setInput} only.</li>
 *   <li>{@link #output(int)}: what an input component (switch, knob) is
 *       currently producing. Set through {@code PanelBlockEntity#setOutput}
 *       only; that also notifies {@link dev.bwr.mod.panel.PanelBus} listeners.</li>
 * </ul>
 * Neither is saved. Inputs are recomputed from their sources on load; outputs
 * are restored by {@link ComponentType#onLoaded} from latched {@link ComponentData}.
 *
 * <p>A component whose type id is not registered (a removed add-on) is kept as
 * raw NBT, still reserving its cells, and written back unchanged.
 */
public final class PanelComponent {
    private final int id;
    private final ComponentType type;
    private final ComponentData data;
    private final CompoundTag unknownTag;
    private int x;
    private int y;
    private int w;
    private int h;
    private String label;
    /** Printed caption under the component (empty draws none); set from a machine name, saved only when not empty. */
    private String caption = "";
    /**
     * Client: per input, the {@link dev.bwr.mod.signal.Quantity} ordinal of the signal it is wired to when that is not
     * the quantity the component is set to measure, else -1. Sent by the server with the full state, never saved.
     */
    private int[] mismatch = new int[0];
    /** Client: per input, the quantity ordinal and unit of the signal it is line-linked to (power units need them); -1 and "". */
    private int[] boundQuantity = new int[0];
    private String[] boundUnit = new String[0];
    private InputSource[] sources = new InputSource[0];
    private double[] inputs = new double[0];
    private double[] outputs = new double[0];
    /** Per output index, where that output goes (fan-out); see {@link OutputSink}. */
    private final List<List<OutputSink>> sinks = new ArrayList<>();

    /** Server-only, never saved: who let go of this control last and the stamp of that RELEASE (see {@link #staleHold}). */
    private java.util.UUID releasedBy;
    private int releasedSeq;
    /** ...and who pressed it last and the stamp of that PRESS (see {@link #staleRelease}). */
    private java.util.UUID pressedBy;
    private int pressedSeq;

    /** Remembers the PRESS that {@code player} sent with stamp {@code seq}. */
    public void notePress(java.util.UUID player, int seq) {
        if (!player.equals(pressedBy) || seq - pressedSeq > 0) {
            pressedBy = player;
            pressedSeq = seq;
        }
    }

    /** Whether a RELEASE stamped {@code seq} by {@code player} was made before that player's last PRESS (it arrived late). */
    public boolean staleRelease(java.util.UUID player, int seq) {
        return player.equals(pressedBy) && seq - pressedSeq < 0;
    }

    /** Remembers the RELEASE that {@code player} sent with stamp {@code seq}. */
    public void noteRelease(java.util.UUID player, int seq) {
        if (!player.equals(releasedBy) || seq - releasedSeq > 0) {
            releasedBy = player;
            releasedSeq = seq;
        }
    }

    /** Whether a PRESS stamped {@code seq} by {@code player} was made before that player's last RELEASE (it arrived late). */
    public boolean staleHold(java.util.UUID player, int seq) {
        return player.equals(releasedBy) && seq - releasedSeq <= 0;
    }

    /** Client-only render cache (static mesh), dropped whenever {@link #revision()} changes. */
    public Object clientCache;
    public int clientCacheRevision = -1;
    private int revision;

    public PanelComponent(int id, ComponentType type, int x, int y, Footprint footprint, String label) {
        this.id = id;
        this.type = type;
        this.data = type.createData(footprint);
        this.unknownTag = null;
        this.x = x;
        this.y = y;
        this.w = footprint.w();
        this.h = footprint.h();
        this.label = label;
        syncPorts();
    }

    private PanelComponent(int id, int x, int y, int w, int h, CompoundTag unknown) {
        this.id = id;
        this.type = null;
        this.data = null;
        this.unknownTag = unknown;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = unknown.getString("Label");
    }

    private PanelComponent(int id, ComponentType type, int x, int y, int w, int h) {
        this.id = id;
        this.type = type;
        this.data = type.createData();
        this.unknownTag = null;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = "";
    }

    public int id() {
        return id;
    }

    /** The registered type, or null when the saved type id is unknown to this build. */
    public ComponentType type() {
        return type;
    }

    public ComponentData data() {
        return data;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int w() {
        return w;
    }

    public int h() {
        return h;
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
        touch();
    }

    /** The quantity ordinal input {@code index} is wired to against its Measures setting, or -1 when it agrees. */
    public int mismatch(int index) {
        return index >= 0 && index < mismatch.length ? mismatch[index] : -1;
    }

    /** The {@code Quantity} ordinal of the signal input {@code index} is line-linked to, or -1 (not known to this client). */
    public int boundQuantity(int index) {
        return index >= 0 && index < boundQuantity.length ? boundQuantity[index] : -1;
    }

    /** The unit of the signal input {@code index} is line-linked to, or "". */
    public String boundUnit(int index) {
        return index >= 0 && index < boundUnit.length ? boundUnit[index] : "";
    }

    /** The printed caption, or "" for none. */
    public String caption() {
        return caption;
    }

    /** Sets the caption (cleaned and cut by the caller); returns whether it changed. */
    public boolean setCaption(String text) {
        String clean = text == null ? "" : text;
        if (clean.equals(caption)) {
            return false;
        }
        caption = clean;
        touch();
        return true;
    }

    /** Bumped on every change that affects how the component draws. */
    public int revision() {
        return revision;
    }

    public void touch() {
        revision++;
    }

    public int inputCount() {
        return inputs.length;
    }

    public int outputCount() {
        return outputs.length;
    }

    /** Current input value; NaN for no signal or an index out of range. */
    public double input(int index) {
        return index >= 0 && index < inputs.length ? inputs[index] : Double.NaN;
    }

    public double output(int index) {
        return index >= 0 && index < outputs.length ? outputs[index] : 0.0;
    }

    public InputSource source(int index) {
        return index >= 0 && index < sources.length ? sources[index] : InputSources.NONE;
    }

    public List<InputSource> sources() {
        return List.of(sources);
    }

    /** Stores a value; returns whether it changed (NaN equals NaN). No notification, see PanelBlockEntity. */
    public boolean storeInput(int index, double value) {
        if (index < 0 || index >= inputs.length || same(inputs[index], value)) {
            return false;
        }
        inputs[index] = value;
        return true;
    }

    public boolean storeOutput(int index, double value) {
        if (index < 0 || index >= outputs.length || same(outputs[index], value)) {
            return false;
        }
        outputs[index] = value;
        return true;
    }

    public void setSource(int index, InputSource source) {
        if (index >= 0 && index < sources.length) {
            sources[index] = source;
            touch();
        }
    }

    /** The sinks output {@code index} fans out to; empty for an index out of range. */
    public List<OutputSink> sinks(int index) {
        return index >= 0 && index < sinks.size() ? Collections.unmodifiableList(sinks.get(index)) : List.of();
    }

    public void addSink(int index, OutputSink sink) {
        if (index >= 0 && index < sinks.size()) {
            sinks.get(index).add(sink);
            touch();
        }
    }

    /** Removes the sink at {@code position} of output {@code index}; returns whether it existed. */
    public boolean removeSink(int index, int position) {
        if (index < 0 || index >= sinks.size() || position < 0 || position >= sinks.get(index).size()) {
            return false;
        }
        sinks.get(index).remove(position);
        touch();
        return true;
    }

    public void clearSinks(int index) {
        if (index >= 0 && index < sinks.size() && !sinks.get(index).isEmpty()) {
            sinks.get(index).clear();
            touch();
        }
    }

    private static boolean same(double a, double b) {
        return a == b || Double.isNaN(a) && Double.isNaN(b);
    }

    /**
     * Resizes the input/output arrays to what the type declares for the current
     * config (a thumbwheel with more digits has more outputs). Existing entries
     * keep their values.
     */
    public void syncPorts() {
        if (type == null) {
            return;
        }
        int in = type.inputPorts(data).size();
        int out = type.outputPorts(data).size();
        if (sources.length != in) {
            InputSource[] resized = new InputSource[in];
            Arrays.fill(resized, InputSources.NONE);
            System.arraycopy(sources, 0, resized, 0, Math.min(sources.length, in));
            sources = resized;
            double[] values = new double[in];
            Arrays.fill(values, Double.NaN);
            System.arraycopy(inputs, 0, values, 0, Math.min(inputs.length, in));
            inputs = values;
        }
        if (outputs.length != out) {
            outputs = Arrays.copyOf(outputs, out);
        }
        while (sinks.size() < out) {
            sinks.add(new ArrayList<>());
        }
        while (sinks.size() > out) {
            sinks.remove(sinks.size() - 1);
        }
    }

    // ---- persistence (also the S2C wire form)

    public CompoundTag save() {
        if (type == null) {
            return unknownTag.copy();
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putString("Type", type.id().toString());
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putInt("W", w);
        tag.putInt("H", h);
        tag.putString("Label", label);
        if (!caption.isEmpty()) {
            tag.putString("Caption", caption);
        }
        tag.put("Data", data.toTag());
        ListTag list = new ListTag();
        for (InputSource source : sources) {
            list.add(InputSources.save(source));
        }
        tag.put("Inputs", list);
        ListTag sinkList = new ListTag();
        for (int output = 0; output < sinks.size(); output++) {
            for (OutputSink sink : sinks.get(output)) {
                CompoundTag entry = OutputSinks.save(sink);
                entry.putInt("Output", output);
                sinkList.add(entry);
            }
        }
        if (!sinkList.isEmpty()) {
            tag.put("Sinks", sinkList);
        }
        return tag;
    }

    /** {@link #save()} plus the live input and output values, for the S2C full-state packet. */
    public CompoundTag saveWithValues() {
        CompoundTag tag = save();
        if (type != null) {
            ListTag in = new ListTag();
            for (double value : inputs) {
                in.add(net.minecraft.nbt.DoubleTag.valueOf(value));
            }
            ListTag out = new ListTag();
            for (double value : outputs) {
                out.add(net.minecraft.nbt.DoubleTag.valueOf(value));
            }
            tag.put("InValues", in);
            tag.put("OutValues", out);
        }
        return tag;
    }

    /** Never throws on bad data: a malformed entry becomes an unknown-type component or is skipped by the caller. */
    public static PanelComponent load(CompoundTag tag) {
        int id = tag.getInt("Id");
        int x = Math.max(0, Math.min(15, tag.getInt("X")));
        int y = Math.max(0, Math.min(15, tag.getInt("Y")));
        int w = Math.max(1, Math.min(Footprint.MAX_CELLS, tag.getInt("W")));
        int h = Math.max(1, Math.min(Footprint.MAX_CELLS, tag.getInt("H")));
        ResourceLocation typeId = ResourceLocation.tryParse(tag.getString("Type"));
        ComponentType type = typeId == null ? null : ComponentTypes.get(typeId);
        if (type == null) {
            return new PanelComponent(id, x, y, w, h, tag.copy());
        }
        PanelComponent component = new PanelComponent(id, type, x, y, w, h);
        component.label = tag.getString("Label");
        component.caption = ConfigSchema.cleanText(tag.getString("Caption"), dev.bwr.mod.panel.MachineNames.MAX_CAPTION);
        component.data.load(tag.getCompound("Data"));
        component.syncPorts();
        ListTag list = tag.getList("Inputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < component.sources.length; i++) {
            component.sources[i] = InputSources.load(list.getCompound(i));
        }
        ListTag sinkList = tag.getList("Sinks", Tag.TAG_COMPOUND);
        for (int i = 0; i < sinkList.size(); i++) {
            CompoundTag entry = sinkList.getCompound(i);
            int output = entry.getInt("Output");
            if (output >= 0 && output < component.sinks.size()) {
                CompoundTag fields = entry.copy();
                fields.remove("Output");
                component.sinks.get(output).add(OutputSinks.load(fields));
            }
        }
        component.mismatch = tag.getIntArray("Mismatch");
        component.boundQuantity = tag.getIntArray("BoundQuantity");
        ListTag units = tag.getList("BoundUnit", Tag.TAG_STRING);
        component.boundUnit = new String[units.size()];
        for (int i = 0; i < units.size(); i++) {
            component.boundUnit[i] = units.getString(i);
        }
        ListTag in = tag.getList("InValues", Tag.TAG_DOUBLE);
        for (int i = 0; i < in.size() && i < component.inputs.length; i++) {
            component.inputs[i] = in.getDouble(i);
        }
        ListTag out = tag.getList("OutValues", Tag.TAG_DOUBLE);
        for (int i = 0; i < out.size() && i < component.outputs.length; i++) {
            component.outputs[i] = out.getDouble(i);
        }
        return component;
    }
}
