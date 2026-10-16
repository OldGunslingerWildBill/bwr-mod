package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;

/**
 * One drawn flow line on the face of one panel block (spec 3): a polyline of grid points in this block's cell space
 * ({@code x} to the right, {@code y} down, 16 cells to a block, so a point may lie outside 0..16 when the path runs
 * on into a coplanar neighbour), a {@link MimicStyle} and an optional live input.
 *
 * <p>A path that crosses blocks is stored in every block it touches, each copy expressed in that block's own cell
 * space and all sharing {@link #pathId()}; erasing or editing one copy updates the others (see {@code MimicOps}).
 * Paths are not components: they reserve no cells, so a component can be placed over one, and they draw on the face
 * just above the paint, below the components.
 *
 * <p>The live input is indication only: it dims or lights the path and moves its arrows on the client. It drives
 * nothing. The sampled value is not saved (it is re-read on load and travels in the network form).
 */
public final class MimicPath {
    private final long pathId;
    private final int localId;
    private final int[] points;
    private final MimicStyle style;
    private InputSource source = InputSources.NONE;
    private double value = Double.NaN;

    public MimicPath(long pathId, int localId, int[] points, MimicStyle style) {
        this.pathId = pathId;
        this.localId = localId;
        this.points = points.clone();
        this.style = style;
    }

    /** Shared by every copy of the same path, in whatever block. Never 0. */
    public long pathId() {
        return pathId;
    }

    /** Identifies this copy within its block (packets and the bind screen address it as {@code -localId}). */
    public int localId() {
        return localId;
    }

    public int pointCount() {
        return points.length / 2;
    }

    public int x(int index) {
        return points[index * 2];
    }

    public int y(int index) {
        return points[index * 2 + 1];
    }

    /** A copy of the flat {@code x0, y0, x1, y1, ...} array. */
    public int[] points() {
        return points.clone();
    }

    public MimicStyle style() {
        return style;
    }

    public InputSource source() {
        return source;
    }

    public void setSource(InputSource source) {
        this.source = source;
        this.value = Double.NaN;
    }

    public boolean bound() {
        return source != InputSources.NONE;
    }

    /** The sampled input, or NaN for no signal (or unbound). */
    public double value() {
        return value;
    }

    /** Stores a sampled value; returns whether it changed (NaN equals NaN). */
    public boolean storeValue(double next) {
        if (Double.doubleToLongBits(next) == Double.doubleToLongBits(value) || (Double.isNaN(next) && Double.isNaN(value))) {
            return false;
        }
        value = next;
        return true;
    }

    /** Whether a bound path is drawn in its colour: the signal is a number above the style's threshold. */
    public boolean lit() {
        return !Double.isNaN(value) && value > style.threshold;
    }

    // ---- persistence

    public CompoundTag save(boolean network) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("PathId", pathId);
        tag.putInt("Id", localId);
        tag.put("Points", new IntArrayTag(points));
        style.save(tag);
        if (bound()) {
            tag.put("Source", InputSources.save(source));
        }
        if (network && bound() && !Double.isNaN(value)) {
            tag.putDouble("Value", value);
        }
        return tag;
    }

    /**
     * Reads a saved path, or null when it is unusable (no points, an odd count, fewer than two points). A source of an
     * unknown kind is kept verbatim by {@link InputSources} so it survives a save.
     */
    public static MimicPath load(CompoundTag tag, int fallbackLocalId) {
        if (!tag.contains("Points", Tag.TAG_INT_ARRAY)) {
            return null;
        }
        int[] raw = tag.getIntArray("Points");
        if (raw.length < 4 || raw.length % 2 != 0 || raw.length > MimicOps.MAX_POINTS * 2) {
            return null;
        }
        long id = tag.getLong("PathId");
        if (id == 0L) {
            id = (long) fallbackLocalId << 1 | 1L;
        }
        MimicStyle style = new MimicStyle();
        style.load(tag);
        int localId = tag.contains("Id", Tag.TAG_ANY_NUMERIC) && tag.getInt("Id") > 0 ? tag.getInt("Id") : fallbackLocalId;
        MimicPath path = new MimicPath(id, localId, raw, style);
        if (tag.contains("Source", Tag.TAG_COMPOUND)) {
            path.source = InputSources.load(tag.getCompound("Source"));
        }
        if (tag.contains("Value", Tag.TAG_ANY_NUMERIC)) {
            path.value = tag.getDouble("Value");
        }
        return path;
    }

    @Override
    public String toString() {
        return "MimicPath#" + localId + "(" + Long.toHexString(pathId) + " " + Arrays.toString(points) + ")";
    }
}
