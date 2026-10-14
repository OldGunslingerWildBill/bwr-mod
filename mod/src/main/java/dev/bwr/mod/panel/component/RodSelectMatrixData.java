package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.reactor.CoreRodMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * The rod select matrix: one pushbutton per rod. The one output, {@code rod}, holds the rod number (1-based) of the
 * last button pressed (0 before any), saved so a loaded panel reports it again; the buttons show the reactor's selected
 * rod, not this value, so a rod selected from CC lights its button too.
 */
public final class RodSelectMatrixData extends CoreDisplayData {
    /** The rod number the last press selected, 0 for none. */
    public int lastPressed;

    @Override
    public int[] neededCells() {
        CoreRodMap map = displayMap();
        return new int[] {FcdLayout.matrixWidth(map, tileCells), FcdLayout.matrixHeight(map, tileCells)};
    }

    @Override
    public int[] neededCells(CoreRodMap map, CompoundTag config) {
        int tile = config.contains("TileCells", Tag.TAG_ANY_NUMERIC) ? FcdLayout.clampTile(config.getInt("TileCells")) : tileCells;
        return new int[] {FcdLayout.matrixWidth(map, tile), FcdLayout.matrixHeight(map, tile)};
    }

    /** The rod (1-based) whose button covers the point {@code (x, y)} cells from the component's top-left, or 0. */
    public int rodAt(int componentW, int componentH, double x, double y) {
        CoreRodMap map = displayMap();
        int[] needed = neededCells();
        int rod = FcdLayout.buttonAt(map, tileCells, x - centre(componentW, needed[0]), y - centre(componentH, needed[1]));
        return rod + 1;
    }

    @Override
    protected void saveOwn(CompoundTag tag) {
        if (lastPressed > 0) {
            tag.putInt("LastPressed", lastPressed);
        }
    }

    @Override
    protected void loadOwn(CompoundTag tag) {
        lastPressed = Math.max(0, Math.min(1000, tag.getInt("LastPressed")));
    }
}
