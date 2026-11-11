package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.AnnunciatorControlData;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.AnnunciatorBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The client's view of the annunciator boxes of each panel group: which windows touch (so they draw as one grouped box,
 * see {@link AnnunciatorBox}) and which groups have a TEST button held (the lamp test). Both come from what the client
 * already has, the components of the group's loaded blocks, and nothing is sent.
 *
 * <p>Once a frame per group (the first block of the group to draw asks) the group's annunciators are folded into a
 * signature without allocating; only when it changed, a window was added, moved, resized, replaced or given other
 * settings, are the edge words worked out again and written into each window's {@link RenderCache#boxEdges}, together
 * with its place in its box ({@link RenderCache#boxTile}, {@link AnnunciatorBox#layout}) and the name on the box's
 * nameplate ({@link RenderCache#boxName}), which the Columbia style draws from. The lamp test is read on every scan, as a
 * bit per group number.
 */
public final class AnnunciatorBoxes {
    private AnnunciatorBoxes() {
    }

    /** What is known about one panel group; hung on each window's render cache so the model reads it without a lookup. */
    static final class State {
        float stamp = Float.NaN;
        long signature;
        /** Bit {@code n - 1} set while a TEST button of annunciator group {@code n} is held. */
        int testing;
    }

    private static final Map<PanelGroup, State> STATES = new WeakHashMap<>();

    /** Folds a value into a signature (the same mixing as the block buffers use, kept here so no render class loads). */
    private static long mix(long h, long value) {
        h ^= value + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h * 0xFF51AFD7ED558CCDL;
    }

    /** Brings the edge words of the annunciators in {@code be}'s group up to date for this frame's {@code time}; cheap when current. */
    public static void refresh(PanelBlockEntity be, float time) {
        boolean any = false;
        List<PanelComponent> own = be.owned();
        for (int i = 0; i < own.size(); i++) {
            if (own.get(i).type() == ComponentTypes.ANNUNCIATOR) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        PanelGroup group = be.group();
        Level level = be.getLevel();
        if (group == null || level == null) {
            return;
        }
        State state = STATES.computeIfAbsent(group, key -> new State());
        if (state.stamp == time) {
            return;
        }
        state.stamp = time;
        List<BlockPos> members = group.members();
        long signature = 0x4A11C0DEL;
        int testing = 0;
        int windows = 0;
        for (int m = 0; m < members.size(); m++) {
            BlockPos pos = members.get(m);
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity block)) {
                continue;
            }
            List<PanelComponent> components = block.owned();
            for (int i = 0; i < components.size(); i++) {
                PanelComponent c = components.get(i);
                if (c.type() == ComponentTypes.ANNUNCIATOR) {
                    windows++;
                    signature = mix(signature, System.identityHashCode(c));
                    signature = mix(signature, ((long) group.columnOf(pos) << 48) ^ ((long) group.rowOf(pos) << 40)
                            ^ ((long) c.x() << 24) ^ ((long) c.y() << 16) ^ ((long) c.w() << 8) ^ c.h());
                    // A window's settings too: the name on a box's nameplate is a setting of one of its windows.
                    signature = mix(signature, c.revision());
                } else if (c.type() == ComponentTypes.ANNUNCIATOR_CONTROL && c.output(AnnunciatorControlType.TEST) > 0.5
                        && c.data() instanceof AnnunciatorControlData data) {
                    testing |= 1 << (data.group - AnnunciatorData.MIN_GROUP);
                }
            }
        }
        state.testing = testing;
        if (signature == state.signature && windows > 0) {
            return;
        }
        state.signature = signature;
        rebuild(group, level, state, windows);
    }

    /** Works out the edge word of every window of the group (rare: only when the set of windows changed). */
    private static void rebuild(PanelGroup group, Level level, State state, int windows) {
        List<PanelComponent> found = new ArrayList<>(windows);
        List<AnnunciatorBox.Rect> rects = new ArrayList<>(windows);
        for (BlockPos pos : group.members()) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity block)) {
                continue;
            }
            for (PanelComponent c : block.owned()) {
                if (c.type() == ComponentTypes.ANNUNCIATOR) {
                    found.add(c);
                    rects.add(new AnnunciatorBox.Rect(group.columnOf(pos) * PanelGeometry.GRID + c.x(),
                            group.rowOf(pos) * PanelGeometry.GRID + c.y(), c.w(), c.h()));
                }
            }
        }
        AnnunciatorBox.Rect[] windowRects = rects.toArray(new AnnunciatorBox.Rect[0]);
        int[] edges = AnnunciatorBox.edges(windowRects);
        // How each box is dressed in the Columbia style (rows, columns, split tiles, the nameplate and the latch). It is
        // decoration: should it ever fail, every window draws as a box by itself rather than the panel not drawing.
        AnnunciatorBox.Tile[] tiles = null;
        try {
            tiles = AnnunciatorBox.layout(windowRects);
        } catch (RuntimeException e) {
            tiles = null;
        }
        if (tiles != null && tiles.length != found.size()) {
            tiles = null;
        }
        String[] names = tiles == null ? null : boxNames(found, windowRects, tiles);
        for (int i = 0; i < found.size(); i++) {
            RenderCache cache = RenderCache.of(found.get(i));
            cache.boxEdges = edges[i];
            cache.boxState = state;
            AnnunciatorBox.Tile tile = tiles == null ? null : tiles[i];
            cache.boxTile = tile;
            cache.boxName = tile != null && names != null && tile.box() >= 0 && tile.box() < names.length ? names[tile.box()] : "";
        }
    }

    /**
     * The nameplate name of each box, by box number: the Box name of the first window of the box, in reading order, that
     * has one; "" for a box with none.
     */
    private static String[] boxNames(List<PanelComponent> found, AnnunciatorBox.Rect[] rects, AnnunciatorBox.Tile[] tiles) {
        String[] names = new String[found.size()]; // a group has at most as many boxes as windows
        int[] from = new int[found.size()];
        java.util.Arrays.fill(names, "");
        java.util.Arrays.fill(from, -1);
        for (int i = 0; i < found.size(); i++) {
            if (tiles[i] == null || !(found.get(i).data() instanceof AnnunciatorData data) || data.boxName.isEmpty()) {
                continue;
            }
            int box = tiles[i].box();
            if (box < 0 || box >= names.length) {
                continue;
            }
            int best = from[box];
            if (best < 0 || rects[i].y() < rects[best].y() || rects[i].y() == rects[best].y() && rects[i].x() < rects[best].x()) {
                from[box] = i;
                names[box] = data.boxName;
            }
        }
        return names;
    }

    /** Whether a lamp test is on for annunciator group {@code number}, as of the last scan of the window's panel group. */
    public static boolean testing(RenderCache cache, int number) {
        return cache.boxState != null && (cache.boxState.testing >> (number - AnnunciatorData.MIN_GROUP) & 1) != 0;
    }
}
