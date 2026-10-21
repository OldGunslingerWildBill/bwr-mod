package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.reactor.CoreRodMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * The LED rod pattern display: what it shows ({@link #mode}), and (from {@link CoreDisplayData}) the tile size and the
 * snapshot of the bound reactor that it shares with the full core display and the rod select matrix. One tile per rod in
 * core layout, each with two small LEDs, amber over red.
 *
 * <h3>What the LEDs mean</h3>
 * <ul>
 *   <li>{@code position} (the default), the rod pattern: <b>amber</b> is a rod that is withdrawn but not fully out (notch 1 to
 *       23), <b>red</b> is a rod that is fully out (notch 24); a fully inserted rod shows nothing.</li>
 *   <li>{@code fault}, the rod fault map: <b>amber</b> is a rod with accumulator trouble, <b>red</b> is a rod drifting.</li>
 * </ul>
 * The rod the reactor has selected is framed in white. Every LED comes straight from the reactor's rod state through the
 * {@link FcdSnapshot}; nothing is judged here (a rod is not "out of sequence", only in or out), and with no reactor, an
 * unformed one or an unloaded chunk every LED is dark. It is read only: no outputs.
 *
 * <p>Its tile is 1 to 3 cells, the real size panel is 1 cell a rod (the reference 185 rod core is 17 cells across).
 */
public final class LedRodDisplayData extends CoreDisplayData {
    public static final String POSITION = "position";
    public static final String FAULT = "fault";
    public static final List<String> MODES = List.of(POSITION, FAULT);
    public static final int MIN_TILE = 1;
    public static final int MAX_TILE = 3;
    public static final int REAL_TILE = 1;
    public static final String KEY_MODE = "LedMode";

    public String mode = POSITION;

    public LedRodDisplayData() {
        tileCells = defaultTile();
    }

    @Override
    public int defaultTile() {
        return REAL_TILE;
    }

    public static int clampTile(int tile) {
        return Math.max(MIN_TILE, Math.min(MAX_TILE, tile));
    }

    /** The drawing size of {@code map} at {@code tile} cells a rod: one tile of margin all round. */
    public static int[] needed(CoreRodMap map, int tile) {
        int t = clampTile(tile);
        return new int[] {(map.cols() + 2) * t, (map.rows() + 2) * t};
    }

    /** The largest tile (up to {@link #MAX_TILE}) at which the reference core fits {@code width x height}; at least 1. */
    public static int fittingTileFor(int width, int height) {
        CoreRodMap map = FcdSnapshot.referenceCells().map();
        for (int tile = MAX_TILE; tile > MIN_TILE; tile--) {
            int[] size = needed(map, tile);
            if (width >= size[0] && height >= size[1]) {
                return tile;
            }
        }
        return MIN_TILE;
    }

    @Override
    protected int[] neededAt(CoreRodMap map, int tile) {
        return needed(map, tile);
    }

    @Override
    public int[] neededCells() {
        return needed(displayMap(), tileCells);
    }

    @Override
    public int[] neededCells(CoreRodMap map, CompoundTag config) {
        int tile = config.contains("TileCells", Tag.TAG_ANY_NUMERIC) ? clampTile(config.getInt("TileCells")) : tileCells;
        return needed(map, tile);
    }

    /** Whether the amber LED of {@code rod} (0-based) is lit in the snapshot {@code snap}. */
    public boolean amber(FcdSnapshot snap, int rod) {
        if (!snap.formed() || rod < 0 || rod >= snap.rodCount()) {
            return false;
        }
        return FAULT.equals(mode) ? snap.accumulatorTrouble(rod) : snap.notch(rod) > 0 && !snap.fullOut(rod);
    }

    /** Whether the red LED of {@code rod} is lit. */
    public boolean red(FcdSnapshot snap, int rod) {
        if (!snap.formed() || rod < 0 || rod >= snap.rodCount()) {
            return false;
        }
        return FAULT.equals(mode) ? snap.drift(rod) : snap.fullOut(rod);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // CoreDisplayData keeps a tile of at least 2 (the other displays'); this one goes down to 1.
        tileCells = tag.contains("TileCells", Tag.TAG_ANY_NUMERIC) ? clampTile(tag.getInt("TileCells")) : defaultTile();
    }

    @Override
    protected void saveOwn(CompoundTag tag) {
        tag.putString(KEY_MODE, mode);
    }

    @Override
    protected void loadOwn(CompoundTag tag) {
        String saved = tag.getString(KEY_MODE);
        mode = MODES.contains(saved) ? saved : POSITION;
    }
}
