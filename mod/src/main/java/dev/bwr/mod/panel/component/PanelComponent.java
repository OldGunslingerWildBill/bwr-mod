package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
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
    private InputSource[] sources = new InputSource[0];
    private double[] inputs = new double[0];
    private double[] outputs = new double[0];

    /** Client-only render cache (static mesh), dropped whenever {@link #revision()} changes. */
    public Object clientCache;
    public int clientCacheRevision = -1;
    private int revision;

    public PanelComponent(int id, ComponentType type, int x, int y, Footprint footprint, String label) {
        this.id = id;
        this.type = type;
        this.data = type.createData();
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
        tag.put("Data", data.toTag());
        ListTag list = new ListTag();
        for (InputSource source : sources) {
            list.add(InputSources.save(source));
        }
        tag.put("Inputs", list);
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
        component.data.load(tag.getCompound("Data"));
        component.syncPorts();
        ListTag list = tag.getList("Inputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < component.sources.length; i++) {
            component.sources[i] = InputSources.load(list.getCompound(i));
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
