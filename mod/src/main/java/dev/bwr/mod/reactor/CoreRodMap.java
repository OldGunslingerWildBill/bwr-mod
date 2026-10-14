package dev.bwr.mod.reactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where each control rod of a formed core sits on the display grid, and what the General Electric full core display
 * calls it. Pure Java (no Minecraft types), so the layout math is testable on its own.
 *
 * <h2>Grid</h2>
 * Rods are placed by a {@code (col, row)} pair: column 0 is the west edge and row 0 the north edge, the way the
 * display draws the core (north up, east right). The grid is the bounding box of the rods, so an octagonal core has
 * absent cells in its corners.
 *
 * <h2>GE coordinates</h2>
 * A full size BWR numbers its rods on a grid of 4 units: columns {@code 02, 06, ... 58} from west to east, rows
 * {@code 03, 07, ... 59} from south to north (the number rises up the core, as on the Browns Ferry display), so rod
 * {@code 18-31} is the 5th column from the west and the 8th row from the south, written {@code XX-YY}. A core
 * narrower than the 15 rods of that grid is centred in it, so its middle rod is {@code 30-31} like the real one;
 * a wider one starts at {@code 02} and runs on. {@link #label} gives that name.
 *
 * <h2>LPRM strings</h2>
 * An LPRM string stands in the water gap between four control cells, so it takes its coordinate from the middle of
 * the four rods around it. On the GE grid those points are {@code x} a multiple of 8 and {@code y} one more than a
 * multiple of 8 ({@code 08-49}, {@code 16-33} ...), one for every two rods in each direction. The mod's strings
 * are those points that have a rod on all four sides, which gives 39 strings in the 185-rod core. They are
 * numbered in reading order, top row first and west to east, from 1. Nothing else in the mod depends on where
 * they are: it is the display's map of where an LPRM string would stand.
 */
public final class CoreRodMap {
    /** Rods across of the reference core (a 764-assembly BWR/4) that the label numbering is centred on. */
    public static final int REFERENCE_ACROSS = 15;

    /** The widest a core can be on the display: the interior limit of a vessel is 21 rods. */
    public static final int MAX_ACROSS = 24;

    private final int[] col;
    private final int[] row;
    private final int cols;
    private final int rows;
    private final int[] cell;
    private final int shiftX;
    private final int shiftY;
    private final List<Lprm> strings;

    /**
     * One LPRM string: the GE coordinates and the grid cells around it. {@code leftCol} and {@code topRow} are the
     * west and north of its four rods, so the string sits between columns {@code leftCol, leftCol + 1} and rows
     * {@code topRow, topRow + 1}.
     */
    public record Lprm(int x, int y, int leftCol, int topRow) {
        /** {@code XX-YY}. */
        public String label() {
            return format(x, y);
        }
    }

    private CoreRodMap(int[] col, int[] row, int cols, int rows) {
        this.col = col;
        this.row = row;
        this.cols = cols;
        this.rows = rows;
        this.cell = new int[cols * rows];
        Arrays.fill(cell, -1);
        for (int rod = 0; rod < col.length; rod++) {
            cell[row[rod] * cols + col[rod]] = rod;
        }
        this.shiftX = Math.max(0, (REFERENCE_ACROSS - 1) / 2 - (cols - 1) / 2);
        this.shiftY = Math.max(0, (REFERENCE_ACROSS - 1) / 2 - (rows - 1) / 2);
        this.strings = List.copyOf(findStrings());
    }

    /**
     * A map for rods at the given cells, one entry per rod in rod order. The cells are shifted so the smallest
     * column and row are 0; two rods in one cell, a core wider than {@link #MAX_ACROSS} or no rods at all are
     * refused.
     *
     * @throws IllegalArgumentException for those cases
     */
    public static CoreRodMap of(int[] col, int[] row) {
        if (col == null || row == null || col.length != row.length || col.length == 0) {
            throw new IllegalArgumentException("a core map needs one column and one row per rod, and at least one rod");
        }
        int minCol = Integer.MAX_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        int maxRow = Integer.MIN_VALUE;
        for (int i = 0; i < col.length; i++) {
            minCol = Math.min(minCol, col[i]);
            minRow = Math.min(minRow, row[i]);
            maxCol = Math.max(maxCol, col[i]);
            maxRow = Math.max(maxRow, row[i]);
        }
        if (maxCol - minCol >= MAX_ACROSS || maxRow - minRow >= MAX_ACROSS) {
            throw new IllegalArgumentException("a core map spans at most " + MAX_ACROSS + " rods each way");
        }
        int[] c = new int[col.length];
        int[] r = new int[col.length];
        boolean[] taken = new boolean[MAX_ACROSS * MAX_ACROSS];
        for (int i = 0; i < col.length; i++) {
            c[i] = col[i] - minCol;
            r[i] = row[i] - minRow;
            if (taken[r[i] * MAX_ACROSS + c[i]]) {
                throw new IllegalArgumentException("two rods in the cell " + col[i] + ", " + row[i]);
            }
            taken[r[i] * MAX_ACROSS + c[i]] = true;
        }
        return new CoreRodMap(c, r, maxCol - minCol + 1, maxRow - minRow + 1);
    }

    /**
     * The map of a formed core: each rod's cell from its drive position inside the vessel interior, one cell per
     * block for the compact layout and one per two blocks (the rods stand at odd offsets) for the legacy one.
     */
    public static CoreRodMap fromStructure(ReactorStructure structure) {
        var rods = structure.rodPositions();
        var min = structure.interiorMin();
        boolean compact = structure.isCompactLayout();
        int[] c = new int[rods.size()];
        int[] r = new int[rods.size()];
        for (int i = 0; i < rods.size(); i++) {
            int dx = rods.get(i).getX() - min.getX();
            int dz = rods.get(i).getZ() - min.getZ();
            c[i] = compact ? dx : (dx - 1) / 2;
            r[i] = compact ? dz : (dz - 1) / 2;
        }
        return of(c, r);
    }

    public int rodCount() {
        return col.length;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    /** Column of a rod (0-based rod index), 0 at the west. */
    public int col(int rod) {
        return col[rod];
    }

    /** Row of a rod, 0 at the north. */
    public int row(int rod) {
        return row[rod];
    }

    /** The rod index at a cell, or -1 for no rod there or a cell outside the grid. */
    public int rodAt(int c, int r) {
        return c < 0 || r < 0 || c >= cols || r >= rows ? -1 : cell[r * cols + c];
    }

    /** GE {@code XX} coordinate of a column (not necessarily one with a rod). */
    public int labelX(int c) {
        return 2 + 4 * (c + shiftX);
    }

    /** GE {@code YY} coordinate of a row (not necessarily one with a rod); it rises toward the north. */
    public int labelY(int r) {
        return 3 + 4 * (rows - 1 - r + shiftY);
    }

    /** The GE name of a rod, e.g. {@code 18-31}. */
    public String label(int rod) {
        return format(labelX(col[rod]), labelY(row[rod]));
    }

    /** The whole-number x of a rod ({@code rod_<n>_lattice_x}). */
    public int latticeX(int rod) {
        return labelX(col[rod]);
    }

    /** The whole-number y of a rod ({@code rod_<n>_lattice_y}). */
    public int latticeY(int rod) {
        return labelY(row[rod]);
    }

    /** The LPRM strings in number order (the string number is the index plus 1). */
    public List<Lprm> lprmStrings() {
        return strings;
    }

    public static String format(int x, int y) {
        return String.format(Locale.ROOT, "%02d-%02d", x, y);
    }

    private List<Lprm> findStrings() {
        List<Lprm> found = new ArrayList<>();
        int topY = labelY(0) + 4;
        int maxX = labelX(cols - 1) + 4;
        for (int ly = topY; ly >= 1; ly--) {
            if (ly % 8 != 1) {
                continue;
            }
            for (int lx = 8; lx <= maxX; lx += 8) {
                int left = lx / 4 - 1 - shiftX;
                int lowerFromBottom = (ly - 5) / 4 - shiftY;
                int top = rows - 1 - (lowerFromBottom + 1);
                if (left < 0 || top < 0 || lowerFromBottom < 0) {
                    continue;
                }
                if (rodAt(left, top) >= 0 && rodAt(left + 1, top) >= 0 && rodAt(left, top + 1) >= 0
                        && rodAt(left + 1, top + 1) >= 0) {
                    found.add(new Lprm(lx, ly, left, top));
                }
            }
        }
        return found;
    }
}
