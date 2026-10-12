package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.display.PanelFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;

/**
 * State of a character LCD: up to {@link #MAX_ROWS} rows of up to
 * {@link #MAX_COLS} characters (the size comes from the footprint: one character
 * per cell across, two cells per row down), a backlight colour and a dot colour.
 *
 * <h3>Content</h3>
 * Either the {@link #format} config (rows separated by {@code |}, each row run
 * through {@link PanelFormat} with inputs 0..3) or, when the format is empty,
 * the latched character grid a CC program wrote with {@link #write}.
 *
 * <h3>Java API for a CC wrapper (all coordinates 0-based, text clipped to the display)</h3>
 * <ul>
 *   <li>{@link #rows()}, {@link #cols()}: the size, i.e. {@code getSize}.</li>
 *   <li>{@link #write(int, int, String)}: overwrite characters at (row, col). Clears the format.</li>
 *   <li>{@link #clear()}: blank every row. Clears the format.</li>
 *   <li>{@link #setText(String)}: replace the grid, rows separated by newline. Clears the format.</li>
 *   <li>{@link #line(int)}: what the grid holds in a row.</li>
 * </ul>
 * Each mutator returns whether anything changed and queues one small patch, sent at most once per tick.
 * Printable ASCII only; anything else is stored as {@code ?}.
 */
public final class LcdData extends ComponentData {
    public static final int MAX_ROWS = 4;
    public static final int MAX_COLS = 64;
    public static final int MAX_FORMAT = 96;
    public static final int DEFAULT_BACKLIGHT = 0x9ACD32;
    public static final int DEFAULT_INK = 0x1B2A0C;

    public String format = "";
    public int backlight = DEFAULT_BACKLIGHT;
    public int ink = DEFAULT_INK;

    private final char[][] grid = new char[MAX_ROWS][MAX_COLS];
    private final String[] shownRows = new String[MAX_ROWS];
    private boolean stale = true;
    private boolean patchPending;
    private int version;

    public LcdData() {
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        Arrays.fill(shownRows, "");
    }

    /** Characters per row. */
    public int cols() {
        return host() == null ? 16 : Math.max(1, Math.min(MAX_COLS, host().cellsWide()));
    }

    /** Number of rows (two cells tall each). */
    public int rows() {
        return host() == null ? 2 : Math.max(1, Math.min(MAX_ROWS, host().cellsHigh() / 2));
    }

    /** The latched grid row as text, trailing blanks removed. */
    public String line(int row) {
        if (row < 0 || row >= MAX_ROWS) {
            return "";
        }
        return new String(grid[row], 0, MAX_COLS).stripTrailing();
    }

    /** Overwrites characters of a row starting at {@code col}; clipped at the edges. Clears the format. */
    public boolean write(int row, int col, String text) {
        if (text == null || row < 0 || row >= rows() || col >= cols()) {
            return false;
        }
        boolean changed = !format.isEmpty();
        int start = Math.max(0, col);
        int limit = cols();
        for (int i = 0; i < text.length(); i++) {
            int x = col + i;
            if (x < start) {
                continue;
            }
            if (x >= limit) {
                break;
            }
            char c = printable(text.charAt(i));
            if (grid[row][x] != c) {
                grid[row][x] = c;
                changed = true;
            }
        }
        return commit(changed);
    }

    /** Blanks every row. Clears the format. */
    public boolean clear() {
        boolean changed = !format.isEmpty();
        for (char[] row : grid) {
            for (int x = 0; x < MAX_COLS; x++) {
                if (row[x] != ' ') {
                    row[x] = ' ';
                    changed = true;
                }
            }
        }
        return commit(changed);
    }

    /** Replaces the whole grid: rows separated by newline, anything beyond the display is dropped. Clears the format. */
    public boolean setText(String text) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        boolean changed = !format.isEmpty();
        int rows = rows();
        for (int row = 0; row < MAX_ROWS; row++) {
            String line = row < lines.length && row < rows ? lines[row] : "";
            for (int x = 0; x < MAX_COLS; x++) {
                char c = x < line.length() && x < cols() ? printable(line.charAt(x)) : ' ';
                if (grid[row][x] != c) {
                    grid[row][x] = c;
                    changed = true;
                }
            }
        }
        return commit(changed);
    }

    private boolean commit(boolean changed) {
        if (!changed) {
            return false;
        }
        format = "";
        stale = true;
        patchPending = true;
        contentChanged();
        return true;
    }

    private static char printable(char c) {
        return c >= 32 && c <= 126 ? c : '?';
    }

    /** Called when an input changed. */
    public void invalidate() {
        stale = true;
    }

    /** Bumped whenever what the display shows may have changed. */
    public int version() {
        return version;
    }

    /** The text of each row as shown (reused array of {@link #MAX_ROWS}; rows past {@link #rows()} are empty). */
    public String[] shownRows(PanelComponent component) {
        if (stale) {
            stale = false;
            version++;
            if (format.isEmpty()) {
                for (int row = 0; row < MAX_ROWS; row++) {
                    shownRows[row] = new String(grid[row], 0, MAX_COLS);
                }
            } else {
                Arrays.fill(shownRows, "");
                String[] parts = format.split("\\|", -1);
                for (int row = 0; row < MAX_ROWS && row < parts.length; row++) {
                    shownRows[row] = PanelFormat.format(parts[row], component::input);
                }
            }
        }
        return shownRows;
    }

    @Override
    protected void attached() {
        stale = true;
    }

    @Override
    public byte[] drainPatch() {
        if (!patchPending) {
            return null;
        }
        patchPending = false;
        return ContentPatches.encode(toTag());
    }

    @Override
    public void applyPatch(byte[] patch) {
        CompoundTag tag = ContentPatches.decode(patch);
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Format", format);
        tag.putInt("Backlight", backlight);
        tag.putInt("Ink", ink);
        ListTag lines = new ListTag();
        for (int row = 0; row < MAX_ROWS; row++) {
            lines.add(StringTag.valueOf(line(row)));
        }
        tag.put("Lines", lines);
    }

    @Override
    public void load(CompoundTag tag) {
        format = ConfigSchema.cleanText(tag.getString("Format"), MAX_FORMAT);
        backlight = tag.contains("Backlight", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Backlight") & 0xFFFFFF : DEFAULT_BACKLIGHT;
        ink = tag.contains("Ink", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Ink") & 0xFFFFFF : DEFAULT_INK;
        ListTag lines = tag.getList("Lines", Tag.TAG_STRING);
        for (int row = 0; row < MAX_ROWS; row++) {
            String line = row < lines.size() ? lines.getString(row) : "";
            for (int x = 0; x < MAX_COLS; x++) {
                grid[row][x] = x < line.length() ? printable(line.charAt(x)) : ' ';
            }
        }
        stale = true;
    }
}
