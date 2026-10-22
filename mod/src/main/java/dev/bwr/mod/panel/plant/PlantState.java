package dev.bwr.mod.panel.plant;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a plant computer shows: up to {@link PlantLimits#MAX_PAGES} pages of elements and which page is shown. The same
 * class is the server's state, the client's copy, the save form and the patch codec, so none of them can drift apart.
 *
 * <h3>Compact</h3>
 * An element is a few bytes ({@link PlantElement#write}); a trend keeps 16 bit points; text is UTF-8 with a length byte.
 * The whole state, at every limit at once, fits one {@code ComponentData.MAX_PATCH}.
 *
 * <h3>Deltas</h3>
 * The server does not record what a program did, only <i>which</i> element changed and how: an element redefined or
 * removed (re-sent whole, or removed), a value changed, points pushed to a trend (only the new points), a page cleared or
 * retitled, the shown page changed. {@link #drainPatch} writes those from the current state, so a program that rewrites
 * the same field a hundred times between two patches sends it once, and a value that does not change what is shown is not
 * a change at all ({@link PlantElement#setValue}). If the changes are more than a patch holds, or the whole state was
 * replaced, the patch is the full state. The client applies a patch with {@link #applyPatch}, which ignores malformed input.
 */
public final class PlantState {
    /** A limit of {@link PlantLimits} would be exceeded. The message says which. */
    public static final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    public static final class Page {
        public String title = "";
        public final LinkedHashMap<String, PlantElement> elements = new LinkedHashMap<>();
    }

    private static final int FULL = 0x46;
    private static final int DELTA = 0x44;
    private static final int VERSION = 1;
    private static final int OP_SET = 1;
    private static final int OP_REMOVE = 2;
    private static final int OP_VALUE = 3;
    private static final int OP_PUSH = 4;
    private static final int OP_CLEAR_PAGE = 5;
    private static final int OP_TITLE = 6;
    private static final int OP_SELECT = 7;

    private static final int D_SET = 0;
    private static final int D_VALUE = 1;
    private static final int D_PUSH = 2;

    private final Page[] pages = new Page[PlantLimits.MAX_PAGES];
    private int selected;
    private int elementCount;
    private int trendCount;

    // ---- change tracking (server)
    private boolean needFull;
    private int clearedPages;
    private int titledPages;
    private boolean selectionDirty;
    /** (page, id) -> how it changed; insertion order is the order of the patch. */
    private final LinkedHashMap<String, Integer> dirty = new LinkedHashMap<>();
    private final Map<String, Integer> pushed = new LinkedHashMap<>();

    // ---- reading

    public int selected() {
        return selected;
    }

    public int elementCount() {
        return elementCount;
    }

    public int trendCount() {
        return trendCount;
    }

    /** The page, or null when nothing was ever put on it. */
    public Page page(int index) {
        return index >= 0 && index < pages.length ? pages[index] : null;
    }

    /** How many pages hold something (elements or a title). */
    public int definedPages() {
        int n = 0;
        for (Page page : pages) {
            if (page != null && (!page.elements.isEmpty() || !page.title.isEmpty())) {
                n++;
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return definedPages() == 0;
    }

    public PlantElement element(int page, String id) {
        Page p = page(page);
        return p == null ? null : p.elements.get(id);
    }

    public Collection<PlantElement> elements(int page) {
        Page p = page(page);
        return p == null ? List.of() : p.elements.values();
    }

    /** Whether any change is waiting to be sent. */
    public boolean pending() {
        return needFull || clearedPages != 0 || titledPages != 0 || selectionDirty || !dirty.isEmpty();
    }

    // ---- writing (each returns whether what is shown changed)

    private Page pageFor(int index) {
        if (index < 0 || index >= pages.length) {
            throw new Rejected("page must be 1 to " + PlantLimits.MAX_PAGES);
        }
        if (pages[index] == null) {
            pages[index] = new Page();
        }
        return pages[index];
    }

    private static String key(int page, String id) {
        return (char) ('0' + page) + id;
    }

    /** Shows page {@code index} (0-based). */
    public boolean select(int index) {
        if (index < 0 || index >= pages.length) {
            throw new Rejected("page must be 1 to " + PlantLimits.MAX_PAGES);
        }
        if (index == selected) {
            return false;
        }
        selected = index;
        selectionDirty = true;
        return true;
    }

    public boolean title(int index, String title) {
        Page page = pageFor(index);
        if (page.title.equals(title)) {
            return false;
        }
        page.title = title;
        titledPages |= 1 << index;
        return true;
    }

    /** Defines {@code element} on a page, replacing the one with its id. Throws {@link Rejected} at a limit. */
    public boolean put(int index, PlantElement element) {
        Page page = pageFor(index);
        PlantElement old = page.elements.get(element.id);
        if (old == null) {
            if (page.elements.size() >= PlantLimits.MAX_ELEMENTS_PER_PAGE) {
                throw new Rejected("page " + (index + 1) + " already has " + PlantLimits.MAX_ELEMENTS_PER_PAGE + " elements");
            }
            if (elementCount >= PlantLimits.MAX_ELEMENTS) {
                throw new Rejected("the display already has " + PlantLimits.MAX_ELEMENTS + " elements");
            }
        }
        boolean trends = element.kind == PlantElement.Kind.TREND;
        int trendsAfter = trendCount - (old != null && old.kind == PlantElement.Kind.TREND ? 1 : 0) + (trends ? 1 : 0);
        if (trendsAfter > PlantLimits.MAX_TRENDS) {
            throw new Rejected("the display already has " + PlantLimits.MAX_TRENDS + " trends");
        }
        if (element.kind == PlantElement.Kind.KEY) {
            for (PlantElement other : page.elements.values()) {
                if (other.kind == PlantElement.Kind.KEY && other.state == element.state && !other.id.equals(element.id)) {
                    throw new Rejected("page " + (index + 1) + " already has soft key " + element.state + " (" + other.id + ")");
                }
            }
        }
        if (old != null && same(old, element)) {
            return false;
        }
        if (old == null) {
            elementCount++;
        }
        trendCount = trendsAfter;
        page.elements.put(element.id, element);
        dirty.put(key(index, element.id), D_SET);
        pushed.remove(key(index, element.id));
        return true;
    }

    private static boolean same(PlantElement a, PlantElement b) {
        if (a.kind != b.kind) {
            return false;
        }
        try {
            ByteArrayOutputStream x = new ByteArrayOutputStream();
            ByteArrayOutputStream y = new ByteArrayOutputStream();
            a.write(new DataOutputStream(x));
            b.write(new DataOutputStream(y));
            return java.util.Arrays.equals(x.toByteArray(), y.toByteArray());
        } catch (IOException e) {
            return false;
        }
    }

    public boolean remove(int index, String id) {
        Page page = page(index);
        PlantElement old = page == null ? null : page.elements.remove(id);
        if (old == null) {
            return false;
        }
        elementCount--;
        if (old.kind == PlantElement.Kind.TREND) {
            trendCount--;
        }
        dirty.put(key(index, id), D_SET);
        pushed.remove(key(index, id));
        return true;
    }

    public boolean clearPage(int index) {
        Page page = page(index);
        if (page == null || page.elements.isEmpty() && page.title.isEmpty()) {
            return false;
        }
        for (PlantElement e : page.elements.values()) {
            elementCount--;
            if (e.kind == PlantElement.Kind.TREND) {
                trendCount--;
            }
        }
        String prefix = String.valueOf((char) ('0' + index));
        dirty.keySet().removeIf(k -> k.startsWith(prefix));
        pushed.keySet().removeIf(k -> k.startsWith(prefix));
        pages[index] = null;
        clearedPages |= 1 << index;
        titledPages &= ~(1 << index);
        return true;
    }

    /** Clears every page and shows page 1 again. */
    public boolean clearAll() {
        if (isEmpty() && selected == 0) {
            return false;
        }
        for (int i = 0; i < pages.length; i++) {
            pages[i] = null;
        }
        elementCount = 0;
        trendCount = 0;
        selected = 0;
        dirty.clear();
        pushed.clear();
        clearedPages = 0;
        titledPages = 0;
        selectionDirty = false;
        needFull = true;
        return true;
    }

    /** Sets the value of a field or bar, or the state of a tile. */
    public boolean setValue(int index, String id, float value) {
        PlantElement e = element(index, id);
        if (e == null) {
            throw new Rejected("no element " + id + " on page " + (index + 1));
        }
        if (e.kind != PlantElement.Kind.FIELD && e.kind != PlantElement.Kind.BAR) {
            throw new Rejected(id + " is a " + e.kind.name().toLowerCase(java.util.Locale.ROOT) + ", which has no number");
        }
        boolean changed = e.setValue(value);
        if (changed) {
            dirty.putIfAbsent(key(index, id), D_VALUE);
        }
        return changed;
    }

    /** Sets the state of a tile. */
    public boolean setTile(int index, String id, int state) {
        PlantElement e = element(index, id);
        if (e == null) {
            throw new Rejected("no element " + id + " on page " + (index + 1));
        }
        if (e.kind != PlantElement.Kind.TILE) {
            throw new Rejected(id + " is not a tile");
        }
        if (e.state == state) {
            return false;
        }
        e.state = state;
        dirty.putIfAbsent(key(index, id), D_VALUE);
        return true;
    }

    /** Sets the text of a text line, a caption, a tile or key label. */
    public boolean setText(int index, String id, String text) {
        PlantElement e = element(index, id);
        if (e == null) {
            throw new Rejected("no element " + id + " on page " + (index + 1));
        }
        if (e.text.equals(text)) {
            return false;
        }
        e.text = text;
        dirty.put(key(index, id), D_SET);
        pushed.remove(key(index, id));
        return true;
    }

    /** Adds a point to a trend. Always a change. */
    public boolean push(int index, String id, float value) {
        PlantElement e = element(index, id);
        if (e == null) {
            throw new Rejected("no element " + id + " on page " + (index + 1));
        }
        if (e.kind != PlantElement.Kind.TREND) {
            throw new Rejected(id + " is not a trend");
        }
        e.push(value);
        String k = key(index, id);
        Integer mode = dirty.get(k);
        if (mode == null) {
            dirty.put(k, D_PUSH);
            pushed.put(k, 1);
        } else if (mode == D_PUSH) {
            pushed.merge(k, 1, Integer::sum);
        }
        return true;
    }

    /** Marks everything as changed: the next patch is the full state. */
    public void markAllChanged() {
        needFull = true;
    }

    // ---- wire

    /** The full state, as saved and as sent. */
    public byte[] encodeFull() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try {
            writeFull(new DataOutputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private void writeFull(DataOutputStream out) throws IOException {
        out.writeByte(FULL);
        out.writeByte(VERSION);
        out.writeByte(selected);
        int defined = 0;
        for (Page page : pages) {
            if (page != null && (!page.elements.isEmpty() || !page.title.isEmpty())) {
                defined++;
            }
        }
        out.writeByte(defined);
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            if (page == null || page.elements.isEmpty() && page.title.isEmpty()) {
                continue;
            }
            out.writeByte(i);
            PlantElement.writeText(out, page.title);
            out.writeByte(page.elements.size());
            for (PlantElement e : page.elements.values()) {
                e.write(out);
            }
        }
    }

    /**
     * The changes since the last call as a patch of at most {@code maxBytes} (the full state when it is flagged or the
     * changes do not fit), or null when there are none. Clears the change flags.
     */
    public byte[] drainPatch(int maxBytes) {
        if (!pending()) {
            return null;
        }
        byte[] out = null;
        if (!needFull) {
            out = encodeDelta();
            if (out != null && out.length > maxBytes) {
                out = null;
            }
        }
        if (out == null) {
            out = encodeFull();
        }
        needFull = false;
        clearedPages = 0;
        titledPages = 0;
        selectionDirty = false;
        dirty.clear();
        pushed.clear();
        return out;
    }

    private byte[] encodeDelta() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(DELTA);
            for (int i = 0; i < pages.length; i++) {
                if ((clearedPages >> i & 1) != 0) {
                    out.writeByte(OP_CLEAR_PAGE);
                    out.writeByte(i);
                }
                if ((titledPages >> i & 1) != 0) {
                    out.writeByte(OP_TITLE);
                    out.writeByte(i);
                    PlantElement.writeText(out, pages[i] == null ? "" : pages[i].title);
                }
            }
            for (Map.Entry<String, Integer> entry : dirty.entrySet()) {
                int page = entry.getKey().charAt(0) - '0';
                String id = entry.getKey().substring(1);
                PlantElement e = element(page, id);
                switch (entry.getValue()) {
                    case D_SET -> {
                        if (e == null) {
                            out.writeByte(OP_REMOVE);
                            out.writeByte(page);
                            PlantElement.writeId(out, id);
                        } else {
                            out.writeByte(OP_SET);
                            out.writeByte(page);
                            e.write(out);
                        }
                    }
                    case D_VALUE -> {
                        if (e != null) {
                            out.writeByte(OP_VALUE);
                            out.writeByte(page);
                            PlantElement.writeId(out, id);
                            if (e.kind == PlantElement.Kind.TILE) {
                                out.writeFloat(e.state);
                            } else {
                                out.writeFloat(e.value);
                            }
                        }
                    }
                    default -> {
                        if (e != null) {
                            out.writeByte(OP_PUSH);
                            out.writeByte(page);
                            PlantElement.writeId(out, id);
                            e.writePush(out, pushed.getOrDefault(entry.getKey(), 1));
                        }
                    }
                }
            }
            if (selectionDirty) {
                out.writeByte(OP_SELECT);
                out.writeByte(selected);
            }
        } catch (IOException e) {
            return null;
        }
        return bytes.toByteArray();
    }

    /** Replaces this state with the one in {@code bytes} (a full state). False, and unchanged, when it is malformed. */
    public boolean loadFull(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        PlantState fresh = new PlantState();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readUnsignedByte() != FULL) {
                return false;
            }
            fresh.readFull(in);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        copyFrom(fresh);
        return true;
    }

    private void readFull(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            throw new IOException("version");
        }
        int sel = in.readUnsignedByte();
        if (sel >= PlantLimits.MAX_PAGES) {
            throw new IOException("page");
        }
        selected = sel;
        int defined = in.readUnsignedByte();
        for (int n = 0; n < defined; n++) {
            int index = in.readUnsignedByte();
            if (index >= PlantLimits.MAX_PAGES || pages[index] != null) {
                throw new IOException("page");
            }
            Page page = new Page();
            page.title = PlantElement.readText(in, PlantLimits.MAX_TITLE);
            int count = in.readUnsignedByte();
            if (count > PlantLimits.MAX_ELEMENTS_PER_PAGE) {
                throw new IOException("too many elements");
            }
            pages[index] = page;
            for (int i = 0; i < count; i++) {
                PlantElement e = PlantElement.read(in);
                if (page.elements.put(e.id, e) != null) {
                    throw new IOException("duplicate id");
                }
                elementCount++;
                if (e.kind == PlantElement.Kind.TREND) {
                    trendCount++;
                }
            }
        }
        if (elementCount > PlantLimits.MAX_ELEMENTS || trendCount > PlantLimits.MAX_TRENDS) {
            throw new IOException("too many");
        }
    }

    private void copyFrom(PlantState other) {
        System.arraycopy(other.pages, 0, pages, 0, pages.length);
        selected = other.selected;
        elementCount = other.elementCount;
        trendCount = other.trendCount;
        dirty.clear();
        pushed.clear();
        needFull = false;
        clearedPages = 0;
        titledPages = 0;
        selectionDirty = false;
    }

    /**
     * Applies a patch made by {@link #drainPatch}: a full state replaces this one, a delta changes what it names.
     * Malformed input leaves what was applied before the fault and stops. Returns whether it was understood to the end.
     */
    public boolean applyPatch(byte[] patch) {
        if (patch == null || patch.length == 0) {
            return false;
        }
        if ((patch[0] & 0xFF) == FULL) {
            return loadFull(patch);
        }
        if ((patch[0] & 0xFF) != DELTA) {
            return false;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(patch, 1, patch.length - 1));
            while (in.available() > 0) {
                int op = in.readUnsignedByte();
                int page = in.readUnsignedByte();
                if (page >= PlantLimits.MAX_PAGES) {
                    return false;
                }
                switch (op) {
                    case OP_SET -> {
                        PlantElement e = PlantElement.read(in);
                        Page p = pageFor(page);
                        PlantElement old = p.elements.put(e.id, e);
                        if (old == null) {
                            elementCount++;
                        } else if (old.kind == PlantElement.Kind.TREND) {
                            trendCount--;
                        }
                        if (e.kind == PlantElement.Kind.TREND) {
                            trendCount++;
                        }
                    }
                    case OP_REMOVE -> {
                        String id = PlantElement.readId(in);
                        Page p = pages[page];
                        PlantElement old = p == null ? null : p.elements.remove(id);
                        if (old != null) {
                            elementCount--;
                            if (old.kind == PlantElement.Kind.TREND) {
                                trendCount--;
                            }
                        }
                    }
                    case OP_VALUE -> {
                        String id = PlantElement.readId(in);
                        float value = in.readFloat();
                        PlantElement e = element(page, id);
                        if (e != null) {
                            if (e.kind == PlantElement.Kind.TILE) {
                                int state = Math.round(value);
                                if (state >= 0 && state < PlantColors.STATES.size()) {
                                    e.state = state;
                                }
                            } else {
                                e.value = value;
                            }
                        }
                    }
                    case OP_PUSH -> {
                        String id = PlantElement.readId(in);
                        PlantElement e = element(page, id);
                        if (e == null || e.kind != PlantElement.Kind.TREND) {
                            return false;
                        }
                        e.readPush(in);
                    }
                    case OP_CLEAR_PAGE -> {
                        Page p = pages[page];
                        if (p != null) {
                            for (PlantElement e : p.elements.values()) {
                                elementCount--;
                                if (e.kind == PlantElement.Kind.TREND) {
                                    trendCount--;
                                }
                            }
                            pages[page] = null;
                        }
                    }
                    case OP_TITLE -> pageFor(page).title = PlantElement.readText(in, PlantLimits.MAX_TITLE);
                    case OP_SELECT -> selected = page;
                    default -> {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A deep copy, for tests. */
    public PlantState copy() {
        PlantState copy = new PlantState();
        copy.loadFull(encodeFull());
        return copy;
    }

    /** Every element of every page, in order, for tests and the renderer. */
    public List<PlantElement> all() {
        List<PlantElement> list = new ArrayList<>();
        for (Page page : pages) {
            if (page != null) {
                list.addAll(page.elements.values());
            }
        }
        return list;
    }
}
