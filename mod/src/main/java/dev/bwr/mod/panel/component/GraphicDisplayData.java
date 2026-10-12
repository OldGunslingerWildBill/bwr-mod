package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.display.LcdFont;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;
import java.util.List;

/**
 * Pixel framebuffer of a graphic display: {@link #PX_PER_BLOCK} (32) pixels per
 * block edge, i.e. 2 per cell, so a 4x4-block display is 128 x 128. Every pixel
 * is an index into a 16 entry palette (a nibble), so the whole buffer is at most
 * 8 KiB however it is saved or sent.
 *
 * <h3>Java API for a CC wrapper</h3>
 * Coordinates are 0-based pixels from the top-left; everything is clipped to the
 * display and every mutator returns whether a pixel or palette entry changed.
 * Colours are palette indices 0..15 (out-of-range colours are refused).
 * <ul>
 *   <li>{@link #getSize()}, {@link #width()}, {@link #height()}</li>
 *   <li>{@link #clear(int)}, {@link #setPixel(int, int, int)}, {@link #getPixel(int, int)},
 *       {@link #fill(int, int, int, int, int)}</li>
 *   <li>{@link #drawText(int, int, String, int)}: the 5x7 LCD font on a 6x8 advance, lit dots only.</li>
 *   <li>{@link #blit(int, int, List)} / {@link #blit(List)}: rows of hex digits, one pixel per digit;
 *       any other character leaves that pixel unchanged (transparent).</li>
 *   <li>{@link #setPalette(int, int)} / {@link #getPalette(int)}: palette entries as 0xRRGGBB.</li>
 * </ul>
 * Mutations are batched: each tick the server sends one patch holding only the
 * 16x16 tiles that changed (or the whole frame when more than half changed) plus
 * changed palette entries, never more than {@link #MAX_PATCH} bytes. A program may
 * therefore draw as much as it likes per tick; only the net change is sent.
 */
public final class GraphicDisplayData extends ComponentData {
    public static final int PX_PER_BLOCK = 32;
    public static final int PX_PER_CELL = PX_PER_BLOCK / 16;
    public static final int MAX_PX = PX_PER_BLOCK * 4;
    public static final int PALETTE_SIZE = 16;
    public static final int TILE = 16;
    public static final int BACKGROUND = 15;
    public static final int DEFAULT_BEZEL = 0x15181A;
    /** The ComputerCraft colour order: index 0 white ... 15 black. */
    public static final int[] DEFAULT_PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111};

    private static final int FLAG_FULL = 1;
    private static final int FLAG_PALETTE = 2;

    public int bezel = DEFAULT_BEZEL;

    private int width;
    private int height;
    private byte[] pixels = new byte[0];
    private final int[] palette = DEFAULT_PALETTE.clone();
    private long dirtyTiles;
    private boolean dirtyAll;
    private int dirtyPalette;
    private int version;

    public static List<ConfigField> schema() {
        return List.of(ConfigField.color("Bezel", "component.bwr.field.bezel_color"));
    }

    // ---- size

    private void ensureSize() {
        if (width == 0 && host() != null) {
            resize(host().cellsWide() * PX_PER_CELL, host().cellsHigh() * PX_PER_CELL);
        }
    }

    private void resize(int w, int h) {
        width = Math.max(0, Math.min(MAX_PX, w));
        height = Math.max(0, Math.min(MAX_PX, h));
        pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) BACKGROUND);
        dirtyTiles = 0;
        dirtyAll = false;
        version++;
    }

    @Override
    protected void attached() {
        ensureSize();
    }

    public int width() {
        ensureSize();
        return width;
    }

    public int height() {
        ensureSize();
        return height;
    }

    /** {@code {width, height}} in pixels. */
    public int[] getSize() {
        return new int[] {width(), height()};
    }

    /** Bumped on every change to pixels or palette; a renderer rebuilds its texture when it differs. */
    public int version() {
        return version;
    }

    // ---- reading

    public int getPixel(int x, int y) {
        ensureSize();
        return x >= 0 && y >= 0 && x < width && y < height ? pixels[y * width + x] & 15 : -1;
    }

    public int getPalette(int index) {
        return index >= 0 && index < PALETTE_SIZE ? palette[index] : 0;
    }

    // ---- drawing (server side API)

    public boolean clear(int color) {
        ensureSize();
        if (!validColor(color) || pixels.length == 0) {
            return false;
        }
        boolean changed = false;
        byte value = (byte) color;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] != value) {
                pixels[i] = value;
                changed = true;
            }
        }
        if (changed) {
            dirtyAll = true;
        }
        return done(changed);
    }

    public boolean setPixel(int x, int y, int color) {
        ensureSize();
        return validColor(color) && done(put(x, y, color));
    }

    public boolean fill(int x, int y, int w, int h, int color) {
        ensureSize();
        if (!validColor(color) || w <= 0 || h <= 0) {
            return false;
        }
        boolean changed = false;
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = (int) Math.min((long) width, (long) x + w);
        int y1 = (int) Math.min((long) height, (long) y + h);
        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                changed |= put(px, py, color);
            }
        }
        return done(changed);
    }

    public boolean drawText(int x, int y, String text, int color) {
        ensureSize();
        if (text == null || !validColor(color)) {
            return false;
        }
        boolean changed = false;
        int cx = x;
        int cy = y;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                cx = x;
                cy += LcdFont.LINE;
                continue;
            }
            if (cx < width && cx + LcdFont.WIDTH > 0 && cy < height && cy + LcdFont.HEIGHT > 0) {
                for (int col = 0; col < LcdFont.WIDTH; col++) {
                    for (int row = 0; row < LcdFont.HEIGHT; row++) {
                        if (LcdFont.pixel(c, col, row)) {
                            changed |= put(cx + col, cy + row, color);
                        }
                    }
                }
            }
            cx += LcdFont.ADVANCE;
            if (cx >= width) {
                break;
            }
        }
        return done(changed);
    }

    public boolean blit(List<String> rows) {
        return blit(0, 0, rows);
    }

    public boolean blit(int x, int y, List<String> rows) {
        ensureSize();
        if (rows == null) {
            return false;
        }
        boolean changed = false;
        for (int r = 0; r < rows.size() && y + r < height; r++) {
            String row = rows.get(r);
            if (row == null || y + r < 0) {
                continue;
            }
            for (int i = 0; i < row.length() && x + i < width; i++) {
                int digit = Character.digit(row.charAt(i), 16);
                if (digit >= 0 && x + i >= 0) {
                    changed |= put(x + i, y + r, digit);
                }
            }
        }
        return done(changed);
    }

    public boolean setPalette(int index, int rgb) {
        if (index < 0 || index >= PALETTE_SIZE || palette[index] == (rgb & 0xFFFFFF)) {
            return false;
        }
        palette[index] = rgb & 0xFFFFFF;
        dirtyPalette |= 1 << index;
        return done(true);
    }

    private static boolean validColor(int color) {
        return color >= 0 && color < PALETTE_SIZE;
    }

    private boolean put(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        int i = y * width + x;
        if (pixels[i] == (byte) color) {
            return false;
        }
        pixels[i] = (byte) color;
        dirtyTiles |= 1L << ((y / TILE) * 8 + x / TILE);
        return true;
    }

    private boolean done(boolean changed) {
        if (changed) {
            version++;
            contentChanged();
        }
        return changed;
    }

    // ---- patches

    @Override
    public byte[] drainPatch() {
        if (!dirtyAll && dirtyTiles == 0 && dirtyPalette == 0) {
            return null;
        }
        boolean full = dirtyAll || Long.bitCount(dirtyTiles) > 32;
        int flags = (full ? FLAG_FULL : 0) | (dirtyPalette != 0 ? FLAG_PALETTE : 0);
        byte[] out = new byte[MAX_PATCH];
        int n = 0;
        out[n++] = (byte) flags;
        if (dirtyPalette != 0) {
            out[n++] = (byte) (dirtyPalette >> 8);
            out[n++] = (byte) dirtyPalette;
            for (int i = 0; i < PALETTE_SIZE; i++) {
                if ((dirtyPalette >> i & 1) != 0) {
                    out[n++] = (byte) (palette[i] >> 16);
                    out[n++] = (byte) (palette[i] >> 8);
                    out[n++] = (byte) palette[i];
                }
            }
        }
        if (full) {
            n = packRegion(out, n, 0, 0, width, height);
        } else if (dirtyTiles != 0) {
            for (int b = 7; b >= 0; b--) {
                out[n++] = (byte) (dirtyTiles >> (b * 8));
            }
            for (int t = 0; t < 64; t++) {
                if ((dirtyTiles >> t & 1) != 0) {
                    int tx = (t % 8) * TILE;
                    int ty = (t / 8) * TILE;
                    n = packRegion(out, n, tx, ty, Math.min(TILE, width - tx), Math.min(TILE, height - ty));
                }
            }
        }
        if (!full && dirtyTiles == 0) {
            // palette only: the tile mask is still present so the reader has one layout
            for (int b = 0; b < 8; b++) {
                out[n++] = 0;
            }
        }
        dirtyTiles = 0;
        dirtyAll = false;
        dirtyPalette = 0;
        return Arrays.copyOf(out, n);
    }

    private int packRegion(byte[] out, int at, int x0, int y0, int w, int h) {
        int n = at;
        boolean high = true;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int value = pixels[y * width + x] & 15;
                if (high) {
                    out[n] = (byte) (value << 4);
                } else {
                    out[n++] |= (byte) value;
                }
                high = !high;
            }
        }
        return high ? n : n + 1;
    }

    @Override
    public void applyPatch(byte[] patch) {
        ensureSize();
        if (patch == null || patch.length < 1 || pixels.length == 0) {
            return;
        }
        int n = 0;
        int flags = patch[n++];
        int newPalette = 0;
        int[] colors = null;
        if ((flags & FLAG_PALETTE) != 0) {
            if (patch.length < n + 2) {
                return;
            }
            newPalette = (patch[n] & 0xFF) << 8 | (patch[n + 1] & 0xFF);
            n += 2;
            colors = new int[PALETTE_SIZE];
            for (int i = 0; i < PALETTE_SIZE; i++) {
                if ((newPalette >> i & 1) != 0) {
                    if (patch.length < n + 3) {
                        return;
                    }
                    colors[i] = (patch[n] & 0xFF) << 16 | (patch[n + 1] & 0xFF) << 8 | (patch[n + 2] & 0xFF);
                    n += 3;
                }
            }
        }
        byte[] next = pixels.clone();
        if ((flags & FLAG_FULL) != 0) {
            if (patch.length - n < (width * height + 1) / 2) {
                return;
            }
            unpackRegion(patch, n, next, 0, 0, width, height);
        } else {
            if (patch.length < n + 8) {
                return;
            }
            long mask = 0;
            for (int b = 0; b < 8; b++) {
                mask = mask << 8 | (patch[n++] & 0xFF);
            }
            for (int t = 0; t < 64; t++) {
                if ((mask >> t & 1) == 0) {
                    continue;
                }
                int tx = (t % 8) * TILE;
                int ty = (t / 8) * TILE;
                if (tx >= width || ty >= height) {
                    return;
                }
                int w = Math.min(TILE, width - tx);
                int h = Math.min(TILE, height - ty);
                int size = (w * h + 1) / 2;
                if (patch.length - n < size) {
                    return;
                }
                unpackRegion(patch, n, next, tx, ty, w, h);
                n += size;
            }
        }
        pixels = next;
        for (int i = 0; i < PALETTE_SIZE; i++) {
            if ((newPalette >> i & 1) != 0) {
                palette[i] = colors[i];
            }
        }
        version++;
    }

    private void unpackRegion(byte[] in, int at, byte[] out, int x0, int y0, int w, int h) {
        int n = at;
        boolean high = true;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int value = high ? (in[n] >> 4) & 15 : in[n++] & 15;
                out[y * width + x] = (byte) value;
                high = !high;
            }
        }
    }

    // ---- persistence

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Bezel", bezel);
        tag.putInt("W", width);
        tag.putInt("H", height);
        tag.put("Palette", new IntArrayTag(palette.clone()));
        byte[] packed = new byte[(pixels.length + 1) / 2];
        packRegion(packed, 0, 0, 0, width, height);
        tag.putByteArray("Pixels", packed);
    }

    @Override
    public void load(CompoundTag tag) {
        bezel = tag.contains("Bezel", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Bezel") & 0xFFFFFF : DEFAULT_BEZEL;
        int[] saved = tag.getIntArray("Palette");
        for (int i = 0; i < PALETTE_SIZE; i++) {
            palette[i] = i < saved.length ? saved[i] & 0xFFFFFF : DEFAULT_PALETTE[i];
        }
        int w = tag.getInt("W");
        int h = tag.getInt("H");
        if (w > 0 && h > 0 && w <= MAX_PX && h <= MAX_PX) {
            resize(w, h);
            byte[] packed = tag.getByteArray("Pixels");
            if (packed.length >= (w * h + 1) / 2) {
                unpackRegion(packed, 0, pixels, 0, 0, w, h);
            }
        } else if (width == 0) {
            ensureSize();
        }
        dirtyTiles = 0;
        dirtyAll = false;
        dirtyPalette = 0;
        version++;
    }

    /** Pixel (palette index) without bounds checks beyond the array; for renderers. */
    public int rawPixel(int index) {
        return pixels[index] & 15;
    }

    /** Palette entry without range check; for renderers. */
    public int rawPalette(int index) {
        return palette[index];
    }
}
