package dev.bwr.mod.panel.fcd;

import dev.bwr.mod.reactor.CoreRodMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of a full core display or rod select matrix, worked out from the rod lattice of the bound core
 * ({@link CoreRodMap}) and the component's {@code tile_cells} setting. Pure Java: positions are in panel cells (1/16
 * block) measured from the top-left of the drawing, and the sizes are what the component needs; the component is
 * larger or equal, and its client centres the drawing inside it.
 *
 * <h2>Columbia (lamp matrix)</h2>
 * Every rod is a 2 x 2 block of square tiles of {@code t} cells ({@code t} = {@code tile_cells}). Tile A holds the
 * rod coordinate over DRIFT, B FULL IN over FULL OUT, C ACCUM over SCRAM, and D is a blank black tile; A is always
 * diagonally opposite D, B beside A in the row, C below or above A. The blocks are mirrored by parity so the blanks of
 * four neighbouring rods meet in a 2 x 2 black cluster, which is where the LPRM strings stand and where the display
 * puts a small yellow dot, as on the photographed board. The mirroring is two global phases, one per direction,
 * chosen to match the strings of the bound core; a core with no strings uses phase 0 (an even column reads A B over
 * C D, its neighbour B A over D C, and the rows mirror in pairs). The backplate is the union of the rod blocks
 * grown by one tile.
 *
 * <h2>Browns Ferry (digital)</h2>
 * Each rod is a module (digit readout over a lamp cluster) of {@code 2t x 1.6t} cells at its grid cell; a narrow
 * strip of {@code 1.4t x 2.7t} cells for each LPRM string stands in a gap between two module columns, centred on the
 * boundary of the two row pairs it sits between. Row and column numbers (the GE coordinates) are drawn in margins at
 * the left and the bottom, with black lines running to the modules. There is no backplate.
 *
 * <h2>Rod select matrix</h2>
 * One square button of {@code t} cells per rod, in core layout, with a margin of one button around the array.
 */
public final class FcdLayout {
    public static final int MIN_TILE = 2;
    public static final int MAX_TILE = 6;
    public static final int DEFAULT_TILE = 3;

    /** What the component draws. */
    public enum Style {
        COLUMBIA("columbia"), BROWNS_FERRY("browns_ferry");

        private final String id;

        Style(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Style byId(String id) {
            for (Style style : values()) {
                if (style.id.equals(id)) {
                    return style;
                }
            }
            return null;
        }
    }

    /** Tile letters of the Columbia rod block. */
    public static final int TILE_A = 0;
    public static final int TILE_B = 1;
    public static final int TILE_C = 2;
    public static final int TILE_D = 3;

    /**
     * The LPRM level (0 = A, the lowest detector, to 3 = D, the highest) that the lamp in {@code slot} of a strip shows,
     * slot 0 being the TOP lamp. A real Browns Ferry strip has level D at the top and A at the bottom, while the model
     * names its lamps {@code lvl_a} to {@code lvl_d} from the top, so the top lamp ({@code lvl_a}) is driven by level D.
     * Every place that draws a strip goes through this one mapping.
     */
    public static int lprmLevelAtSlot(int slot) {
        return LPRM_LEVELS - 1 - slot;
    }

    private static final int LPRM_LEVELS = 4;

    /** A rectangle in cells. */
    public record Rect(float x, float y, float w, float h) {
    }

    /** A yellow dot or a strip: a position in cells and, for a strip, the string it belongs to (0-based). */
    public record Spot(float x, float y, int string) {
    }

    // BF module proportions, in units of one tile.
    private static final float BF_MODULE_W = 2.0f;
    private static final float BF_MODULE_H = 1.6f;
    private static final float BF_STRIP_W = 1.4f;
    private static final float BF_STRIP_H = 2.7f;
    private static final float BF_LEFT = 1.6f;
    private static final float BF_RIGHT = 0.4f;
    private static final float BF_TOP = 0.4f;
    private static final float BF_BOTTOM = 1.0f;

    private final CoreRodMap map;
    private final Style style;
    private final int tile;
    private final boolean mirrorColumns;
    private final boolean mirrorRows;
    private final boolean[] stripGapAfter;
    private final float[] columnX;

    private FcdLayout(CoreRodMap map, Style style, int tile) {
        this.map = map;
        this.style = style;
        this.tile = Math.max(MIN_TILE, Math.min(MAX_TILE, tile));
        int odd = 0;
        int even = 0;
        int oddRows = 0;
        int evenRows = 0;
        stripGapAfter = new boolean[map.cols()];
        for (CoreRodMap.Lprm string : map.lprmStrings()) {
            if (string.leftCol() % 2 != 0) {
                odd++;
            } else {
                even++;
            }
            if (string.topRow() % 2 != 0) {
                oddRows++;
            } else {
                evenRows++;
            }
            stripGapAfter[string.leftCol()] = true;
        }
        // phase = the parity of the column (row) on the near side of each LPRM point
        this.mirrorColumns = odd > even;
        this.mirrorRows = oddRows > evenRows;
        columnX = new float[map.cols()];
        float x = BF_LEFT * this.tile;
        for (int c = 0; c < map.cols(); c++) {
            columnX[c] = x;
            x += BF_MODULE_W * this.tile + (stripGapAfter[c] ? BF_STRIP_W * this.tile : 0.0f);
        }
    }

    public static FcdLayout of(CoreRodMap map, Style style, int tileCells) {
        return new FcdLayout(map, style, tileCells);
    }

    public CoreRodMap map() {
        return map;
    }

    public Style style() {
        return style;
    }

    public int tile() {
        return tile;
    }

    // ---- size

    /** Cells the drawing needs across, rounded up. */
    public int neededWidth() {
        return switch (style) {
            case COLUMBIA -> (2 * map.cols() + 2) * tile;
            case BROWNS_FERRY -> (int) Math.ceil(columnX[map.cols() - 1] + BF_MODULE_W * tile + BF_RIGHT * tile);
        };
    }

    /** Cells the drawing needs down, rounded up. */
    public int neededHeight() {
        return switch (style) {
            case COLUMBIA -> (2 * map.rows() + 2) * tile;
            case BROWNS_FERRY -> (int) Math.ceil((BF_TOP + BF_MODULE_H * map.rows() + BF_BOTTOM) * tile);
        };
    }

    /** The matrix needs one button across per column, plus a button of margin each side. */
    public static int matrixWidth(CoreRodMap map, int tileCells) {
        return (map.cols() + 2) * clampTile(tileCells);
    }

    public static int matrixHeight(CoreRodMap map, int tileCells) {
        return (map.rows() + 2) * clampTile(tileCells);
    }

    public static int clampTile(int tileCells) {
        return Math.max(MIN_TILE, Math.min(MAX_TILE, tileCells));
    }

    public boolean fits(int width, int height) {
        return width >= neededWidth() && height >= neededHeight();
    }

    // ---- Columbia

    /** Whether the blank tile of this rod is on the left (the block is mirrored left to right). */
    public boolean blankOnLeft(int rod) {
        return (map.col(rod) % 2 == 1) != mirrorColumns;
    }

    /** Whether the blank tile of this rod is on top (mirrored top to bottom). */
    public boolean blankOnTop(int rod) {
        return (map.row(rod) % 2 == 1) != mirrorRows;
    }

    /** Left edge of a rod's 2t x 2t block. */
    public float blockX(int rod) {
        return (1 + 2 * map.col(rod)) * tile;
    }

    public float blockY(int rod) {
        return (1 + 2 * map.row(rod)) * tile;
    }

    /** Left edge of tile {@code which} ({@link #TILE_A}..{@link #TILE_D}) of a rod. */
    public float tileX(int rod, int which) {
        boolean right = (which == TILE_B || which == TILE_D) != blankOnLeft(rod);
        // A and C are in the column away from the blank, B and D in the column beside it (D is the blank)
        return blockX(rod) + (columnIndex(which, blankOnLeft(rod))) * tile;
    }

    public float tileY(int rod, int which) {
        return blockY(rod) + (rowIndex(which, blankOnTop(rod))) * tile;
    }

    /** Column (0 left, 1 right) of a tile within its block: A, C away from the blank; B, D on the blank's side. */
    private static int columnIndex(int which, boolean blankLeft) {
        boolean blankSide = which == TILE_B || which == TILE_D;
        return blankSide == blankLeft ? 0 : 1;
    }

    /** Row (0 top, 1 bottom): A, B in the row away from the blank; C, D in the blank's row. */
    private static int rowIndex(int which, boolean blankTop) {
        boolean blankRow = which == TILE_C || which == TILE_D;
        return blankRow == blankTop ? 0 : 1;
    }

    /** The yellow dots: one at the centre of each LPRM string. */
    public List<Spot> dots() {
        List<Spot> dots = new ArrayList<>();
        List<CoreRodMap.Lprm> strings = map.lprmStrings();
        for (int s = 0; s < strings.size(); s++) {
            CoreRodMap.Lprm string = strings.get(s);
            dots.add(new Spot((2 + 2 * string.leftCol()) * tile + tile, (2 + 2 * string.topRow()) * tile + tile, s));
        }
        return dots;
    }

    /** Whether the four blank tiles around a string meet at its dot (all four rods have their blank toward it). */
    public boolean blanksMeetAt(CoreRodMap.Lprm string) {
        int left = string.leftCol();
        int top = string.topRow();
        int[] corners = {map.rodAt(left, top), map.rodAt(left + 1, top), map.rodAt(left, top + 1), map.rodAt(left + 1, top + 1)};
        return corners[0] >= 0 && corners[1] >= 0 && corners[2] >= 0 && corners[3] >= 0
                && !blankOnLeft(corners[0]) && blankOnLeft(corners[1]) && !blankOnLeft(corners[2]) && blankOnLeft(corners[3])
                && !blankOnTop(corners[0]) && !blankOnTop(corners[1]) && blankOnTop(corners[2]) && blankOnTop(corners[3]);
    }

    /** The backplate: each row's runs of rod blocks, grown by one tile all round. Rectangles overlap; all are the same colour. */
    public List<Rect> backplate() {
        List<Rect> plates = new ArrayList<>();
        for (int r = 0; r < map.rows(); r++) {
            int c = 0;
            while (c < map.cols()) {
                if (map.rodAt(c, r) < 0) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < map.cols() && map.rodAt(c, r) >= 0) {
                    c++;
                }
                plates.add(new Rect(2 * start * tile, 2 * r * tile, (2 * (c - start) + 2) * tile, 4 * tile));
            }
        }
        return plates;
    }

    // ---- Browns Ferry

    public float moduleWidth() {
        return BF_MODULE_W * tile;
    }

    public float moduleHeight() {
        return BF_MODULE_H * tile;
    }

    public float stripWidth() {
        return BF_STRIP_W * tile;
    }

    public float stripHeight() {
        return BF_STRIP_H * tile;
    }

    /** Left edge of a rod's module. */
    public float moduleX(int rod) {
        return columnX[map.col(rod)];
    }

    public float moduleY(int rod) {
        return (BF_TOP + BF_MODULE_H * map.row(rod)) * tile;
    }

    /** Left edge of the strip of LPRM string {@code s} (0-based). */
    public float stripX(int string) {
        int left = map.lprmStrings().get(string).leftCol();
        return columnX[left] + BF_MODULE_W * tile;
    }

    /** Top edge of the strip: centred on the boundary between the two module rows it stands between. */
    public float stripY(int string) {
        int top = map.lprmStrings().get(string).topRow();
        return (BF_TOP + BF_MODULE_H * (top + 1)) * tile - BF_STRIP_H * tile / 2.0f;
    }

    /** Width of the left margin that holds the row numbers. */
    public float leftMargin() {
        return BF_LEFT * tile;
    }

    /** Top of the bottom margin that holds the column numbers. */
    public float bottomMarginY() {
        return (BF_TOP + BF_MODULE_H * map.rows()) * tile;
    }

    public float bottomMargin() {
        return BF_BOTTOM * tile;
    }

    /** The centre of a grid row's modules, for its number and its line. */
    public float rowCentreY(int row) {
        return (BF_TOP + BF_MODULE_H * (row + 0.5f)) * tile;
    }

    public float columnCentreX(int col) {
        return columnX[col] + BF_MODULE_W * tile / 2.0f;
    }

    // ---- select matrix

    /** Left edge of the matrix button of a rod. */
    public static float buttonX(CoreRodMap map, int tileCells, int rod) {
        return (1 + map.col(rod)) * clampTile(tileCells);
    }

    public static float buttonY(CoreRodMap map, int tileCells, int rod) {
        return (1 + map.row(rod)) * clampTile(tileCells);
    }

    /** The rod (0-based) whose matrix button covers the point {@code (x, y)} cells from the drawing's top-left, or -1. */
    public static int buttonAt(CoreRodMap map, int tileCells, double x, double y) {
        int t = clampTile(tileCells);
        double col = x / t - 1.0;
        double row = y / t - 1.0;
        if (col < 0 || row < 0) {
            return -1;
        }
        return map.rodAt((int) Math.floor(col), (int) Math.floor(row));
    }
}
