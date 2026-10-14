package dev.bwr.mod.panel.fcd;

import dev.bwr.mod.reactor.CoreRodMap;

import java.util.Arrays;

/**
 * Everything a full core display or rod select matrix shows, in one compact immutable object that the server builds
 * once per sample and sends as one content patch only when it differs from the last one: the rod cells of the bound
 * core (so the client can lay the display out without the core), per rod the notch and two flag bits, the selected
 * rod, the scram latch, and per LPRM level the indication and four flag bits. Pure Java, no Minecraft types.
 *
 * <h2>Wire format (version 1)</h2>
 * <pre>
 *   u8 version | u8 flags (bit0 formed, bit1 scram) | u16 selected | u16 rods | u8 cols | u8 rows
 *   per rod:   u8 col | u8 row | u8 state (bits 0-4 notch index 0..24, bit5 drift, bit6 accumulator trouble)
 *   u8 strings, then per string and level A..D:  u8 indication (half percent, 0..250) | u8 flags
 *              (bit0 downscale, bit1 upscale, bit2 bypassed, bit3 failed)
 * </pre>
 * 3 bytes a rod and 8 a string: 1.5 kB for the largest core, far under {@code ComponentData.MAX_PATCH}. FULL IN
 * and FULL OUT are the end notches and SCRAM is "scram latched and not fully in", so none of them takes a bit; the
 * string count is the one {@link CoreRodMap} derives from the cells, and a decode that disagrees is refused.
 * {@link #DARK} is the snapshot of nothing: no binding, an unloaded device or a reactor that is not formed. The
 * display draws every lamp off for it.
 */
public final class FcdSnapshot {
    public static final int VERSION = 1;
    public static final int NOTCHES = 24;
    private static final int MAX_RODS = 500;

    /** The rod cells of a core and the map built from them (built on first use, shared by every snapshot with the same cells). */
    public static final class Cells {
        private final int[] col;
        private final int[] row;
        private CoreRodMap map;
        private boolean mapFailed;

        public Cells(int[] col, int[] row) {
            this.col = col;
            this.row = row;
        }

        public int rodCount() {
            return col.length;
        }

        /** The map, or null when the cells are not a displayable core. */
        public CoreRodMap map() {
            if (map == null && !mapFailed) {
                try {
                    map = CoreRodMap.of(col, row);
                } catch (IllegalArgumentException e) {
                    mapFailed = true;
                }
            }
            return map;
        }

        public boolean sameAs(Cells other) {
            return Arrays.equals(col, other.col) && Arrays.equals(row, other.row);
        }

        public static Cells of(CoreRodMap map) {
            int[] c = new int[map.rodCount()];
            int[] r = new int[map.rodCount()];
            for (int i = 0; i < c.length; i++) {
                c[i] = map.col(i);
                r[i] = map.row(i);
            }
            Cells cells = new Cells(c, r);
            cells.map = map;
            return cells;
        }
    }

    private static Cells reference;

    /**
     * The rod cells of the 185-rod reference core (15 x 15 interior, compact layout), which an unbound display draws
     * dark so the player sees what was placed. Built on first use.
     */
    public static synchronized Cells referenceCells() {
        if (reference == null) {
            dev.bwr.core.fuel.CompactCoreLayout layout = new dev.bwr.core.fuel.CompactCoreLayout(15, 15);
            int[] c = new int[layout.drives().size()];
            int[] r = new int[c.length];
            for (int i = 0; i < c.length; i++) {
                c[i] = layout.drives().get(i).x();
                r[i] = layout.drives().get(i).z();
            }
            reference = new Cells(c, r);
        }
        return reference;
    }

    /** The snapshot of nothing: every lamp off. */
    public static final FcdSnapshot DARK = new FcdSnapshot(false, false, 0, new Cells(new int[0], new int[0]),
            new byte[0], new byte[0], new byte[0]);

    private final boolean formed;
    private final boolean scram;
    private final int selected;
    private final Cells cells;
    private final byte[] rodState;
    private final byte[] lprmPercent;
    private final byte[] lprmFlags;

    public FcdSnapshot(boolean formed, boolean scram, int selected, Cells cells, byte[] rodState, byte[] lprmPercent,
                       byte[] lprmFlags) {
        this.formed = formed;
        this.scram = scram;
        this.selected = selected;
        this.cells = cells;
        this.rodState = rodState;
        this.lprmPercent = lprmPercent;
        this.lprmFlags = lprmFlags;
    }

    /** A snapshot with no core behind it that keeps drawing the tiles of {@code cells}: all lamps off. */
    public static FcdSnapshot dark(Cells cells) {
        if (cells == null || cells.rodCount() == 0) {
            return DARK;
        }
        CoreRodMap map = cells.map();
        int strings = map == null ? 0 : map.lprmStrings().size();
        return new FcdSnapshot(false, false, 0, cells, new byte[cells.rodCount()], new byte[strings * 4], new byte[strings * 4]);
    }

    // ---- reading

    /** Whether a formed core is behind the display; false draws everything off. */
    public boolean formed() {
        return formed;
    }

    public boolean scram() {
        return scram;
    }

    /** The selected rod, 1-based; 0 when none. */
    public int selected() {
        return selected;
    }

    public Cells cells() {
        return cells;
    }

    public int rodCount() {
        return cells.rodCount();
    }

    /** Notch index of a rod, 0 (fully in) to 24 (fully out); the digits read twice this. */
    public int notch(int rod) {
        return rodState[rod] & 0x1F;
    }

    public boolean drift(int rod) {
        return (rodState[rod] & 0x20) != 0;
    }

    public boolean accumulatorTrouble(int rod) {
        return (rodState[rod] & 0x40) != 0;
    }

    public boolean fullIn(int rod) {
        return notch(rod) == 0;
    }

    public boolean fullOut(int rod) {
        return notch(rod) == NOTCHES;
    }

    /** The scram lamp of a rod: the latch is set and the rod is not yet fully in. */
    public boolean scramLamp(int rod) {
        return scram && notch(rod) > 0;
    }

    public boolean selectedRod(int rod) {
        return selected == rod + 1;
    }

    public int lprmStrings() {
        return lprmFlags.length / 4;
    }

    /** What an LPRM level indicates, percent (resolution half a percent). */
    public double lprmPercent(int string, int level) {
        return (lprmPercent[string * 4 + level] & 0xFF) / 2.0;
    }

    public boolean lprmDownscale(int string, int level) {
        return (lprmFlags[string * 4 + level] & 1) != 0;
    }

    public boolean lprmUpscale(int string, int level) {
        return (lprmFlags[string * 4 + level] & 2) != 0;
    }

    public boolean lprmBypassed(int string, int level) {
        return (lprmFlags[string * 4 + level] & 4) != 0;
    }

    public boolean lprmFailed(int string, int level) {
        return (lprmFlags[string * 4 + level] & 8) != 0;
    }

    /**
     * What the lamp of an LPRM level shows, one of the {@code LAMP_} constants: dark for downscale or a core that is
     * not formed, red for a failed chamber, blue for a bypassed one, amber for upscale, white while in range.
     */
    public int lprmLamp(int string, int level) {
        if (!formed) {
            return LAMP_OFF;
        }
        int flags = lprmFlags[string * 4 + level];
        if ((flags & 8) != 0) {
            return LAMP_FAILED;
        }
        if ((flags & 4) != 0) {
            return LAMP_BYPASSED;
        }
        if ((flags & 2) != 0) {
            return LAMP_UPSCALE;
        }
        return (flags & 1) != 0 ? LAMP_OFF : LAMP_NORMAL;
    }

    /** LPRM lamp states: dark (downscale), white (in range), amber (upscale), blue (bypassed), red (failed). */
    public static final int LAMP_OFF = 0;
    public static final int LAMP_NORMAL = 1;
    public static final int LAMP_UPSCALE = 2;
    public static final int LAMP_BYPASSED = 3;
    public static final int LAMP_FAILED = 4;

    // ---- building

    /** A snapshot from raw parts: arrays are copied. Used by the server after it reads the core. */
    public static FcdSnapshot of(boolean scram, int selected, Cells cells, byte[] rodState, byte[] lprmPercent, byte[] lprmFlags) {
        return new FcdSnapshot(true, scram, selected, cells, rodState.clone(), lprmPercent.clone(), lprmFlags.clone());
    }

    /** Packs a rod's state byte. */
    public static byte pack(int notch, boolean drift, boolean accumulatorTrouble) {
        return (byte) (Math.max(0, Math.min(NOTCHES, notch)) | (drift ? 0x20 : 0) | (accumulatorTrouble ? 0x40 : 0));
    }

    /** Packs an LPRM indication, percent to half percents clamped to 0..250 (0 to 125 percent). */
    public static byte packPercent(double percent) {
        double clamped = Double.isNaN(percent) ? 0.0 : Math.max(0.0, Math.min(125.0, percent));
        return (byte) Math.round(clamped * 2.0);
    }

    public static byte packFlags(boolean downscale, boolean upscale, boolean bypassed, boolean failed) {
        return (byte) ((downscale ? 1 : 0) | (upscale ? 2 : 0) | (bypassed ? 4 : 0) | (failed ? 8 : 0));
    }

    // ---- wire

    public byte[] encode() {
        int rods = rodCount();
        int strings = lprmStrings();
        byte[] out = new byte[8 + rods * 3 + 1 + strings * 8];
        int at = 0;
        out[at++] = VERSION;
        out[at++] = (byte) ((formed ? 1 : 0) | (scram ? 2 : 0));
        out[at++] = (byte) (selected >> 8);
        out[at++] = (byte) selected;
        out[at++] = (byte) (rods >> 8);
        out[at++] = (byte) rods;
        CoreRodMap map = rods == 0 ? null : cells.map();
        out[at++] = (byte) (map == null ? 0 : map.cols());
        out[at++] = (byte) (map == null ? 0 : map.rows());
        for (int i = 0; i < rods; i++) {
            out[at++] = (byte) cells.col[i];
            out[at++] = (byte) cells.row[i];
            out[at++] = rodState[i];
        }
        out[at++] = (byte) strings;
        for (int i = 0; i < strings * 4; i++) {
            out[at++] = lprmPercent[i];
            out[at++] = lprmFlags[i];
        }
        return out;
    }

    /**
     * Reads a snapshot. Never throws: malformed input (wrong version, a length that does not match, rods in the
     * same cell, a string count other than the one the cells give) returns null and the caller keeps what it had.
     *
     * @param previous the snapshot being replaced, whose {@link Cells} are reused when the cells are unchanged
     */
    public static FcdSnapshot decode(byte[] bytes, FcdSnapshot previous) {
        try {
            if (bytes == null || bytes.length < 9 || bytes[0] != VERSION) {
                return null;
            }
            int at = 1;
            int flags = bytes[at++] & 0xFF;
            int selected = (bytes[at++] & 0xFF) << 8 | bytes[at++] & 0xFF;
            int rods = (bytes[at++] & 0xFF) << 8 | bytes[at++] & 0xFF;
            at += 2; // cols and rows: informative, the map derives them
            if (rods > MAX_RODS || bytes.length < at + rods * 3 + 1) {
                return null;
            }
            int[] col = new int[rods];
            int[] row = new int[rods];
            byte[] state = new byte[rods];
            for (int i = 0; i < rods; i++) {
                col[i] = bytes[at++] & 0xFF;
                row[i] = bytes[at++] & 0xFF;
                state[i] = bytes[at++];
                if ((state[i] & 0x1F) > NOTCHES) {
                    return null;
                }
            }
            int strings = bytes[at++] & 0xFF;
            if (bytes.length != at + strings * 8) {
                return null;
            }
            byte[] percent = new byte[strings * 4];
            byte[] lflags = new byte[strings * 4];
            for (int i = 0; i < strings * 4; i++) {
                percent[i] = bytes[at++];
                lflags[i] = bytes[at++];
            }
            Cells cells = new Cells(col, row);
            if (previous != null && previous.cells.sameAs(cells)) {
                cells = previous.cells;
            }
            if (rods > 0) {
                CoreRodMap map = cells.map();
                if (map == null || map.lprmStrings().size() != strings) {
                    return null;
                }
            } else if (strings != 0) {
                return null;
            }
            return new FcdSnapshot((flags & 1) != 0, (flags & 2) != 0, selected, cells, state, percent, lflags);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FcdSnapshot s && Arrays.equals(encode(), s.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }

    /** A key for the drawn state (everything but the exact LPRM percentages, which are not drawn). */
    public long visualKey() {
        long h = formed ? 1 : 0;
        h = h * 31 + (scram ? 1 : 0);
        h = h * 31 + selected;
        h = h * 31 + Arrays.hashCode(rodState);
        h = h * 31 + Arrays.hashCode(lprmFlags);
        h = h * 31 + cells.rodCount();
        return h;
    }
}
