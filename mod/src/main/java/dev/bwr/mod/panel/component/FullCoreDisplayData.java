package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.LprmReadout;
import dev.bwr.mod.rods.RodIndications;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * The full core display: its look ({@code columbia} lamp matrix or {@code browns_ferry} digital), the accumulator
 * and LPRM alarm thresholds, and (from {@link CoreDisplayData}) the tile size and the current snapshot. The LPRM
 * and accumulator thresholds are gameplay defaults, see {@link LprmReadout} and {@link RodIndications}.
 */
public final class FullCoreDisplayData extends CoreDisplayData {
    public static final String COLUMBIA = FcdLayout.Style.COLUMBIA.id();
    public static final String BROWNS_FERRY = FcdLayout.Style.BROWNS_FERRY.id();
    public static final List<String> STYLES = List.of(COLUMBIA, BROWNS_FERRY);

    /**
     * NBT key of the look. Not {@code Style}: that key belongs to {@link PanelStyles} (classic, ge, westinghouse) and
     * the types that have baked looks; the display has its own choice of what it draws.
     */
    public static final String KEY_STYLE = "FcdStyle";

    /** Edge row and column numbers: {@code auto} is on for Browns Ferry and off for Columbia. */
    public static final String EDGE_AUTO = "auto";
    public static final String EDGE_ON = "on";
    public static final String EDGE_OFF = "off";
    public static final List<String> EDGE_MODES = List.of(EDGE_AUTO, EDGE_ON, EDGE_OFF);

    public String style = COLUMBIA;
    public String edgeNumbers = EDGE_AUTO;

    /** Whether the GE row and column numbers are drawn along the edges (with the grid lines of Browns Ferry). */
    public boolean edgeNumbersOn() {
        return EDGE_ON.equals(edgeNumbers) || EDGE_AUTO.equals(edgeNumbers) && layoutStyle() == FcdLayout.Style.BROWNS_FERRY;
    }
    public double accumFraction = RodIndications.DEFAULT_ACCUMULATOR_FRACTION;
    public double downscale = LprmReadout.DEFAULT_DOWNSCALE_PERCENT;
    public double upscale = LprmReadout.DEFAULT_UPSCALE_PERCENT;

    public FcdLayout.Style layoutStyle() {
        FcdLayout.Style found = FcdLayout.Style.byId(style);
        return found == null ? FcdLayout.Style.COLUMBIA : found;
    }

    @Override
    public int[] neededCells() {
        return needed(displayMap(), layoutStyle(), tileCells);
    }

    @Override
    public int[] neededCells(CoreRodMap map, CompoundTag config) {
        FcdLayout.Style proposed = config.contains(KEY_STYLE, Tag.TAG_STRING) && FcdLayout.Style.byId(config.getString(KEY_STYLE)) != null
                ? FcdLayout.Style.byId(config.getString(KEY_STYLE)) : layoutStyle();
        int tile = config.contains("TileCells", Tag.TAG_ANY_NUMERIC) ? FcdLayout.clampTile(config.getInt("TileCells")) : tileCells;
        return needed(map, proposed, tile);
    }

    private static int[] needed(CoreRodMap map, FcdLayout.Style style, int tile) {
        FcdLayout layout = FcdLayout.of(map, style, tile);
        return new int[] {layout.neededWidth(), layout.neededHeight()};
    }

    @Override
    protected double accumulatorFraction() {
        return accumFraction;
    }

    @Override
    protected double lprmDownscale() {
        return downscale;
    }

    @Override
    protected double lprmUpscale() {
        return upscale;
    }

    @Override
    protected void saveOwn(CompoundTag tag) {
        tag.putString(KEY_STYLE, style);
        tag.putString("EdgeNumbers", edgeNumbers);
        tag.putDouble("AccumFraction", accumFraction);
        tag.putDouble("Downscale", downscale);
        tag.putDouble("Upscale", upscale);
    }

    @Override
    protected void loadOwn(CompoundTag tag) {
        String saved = tag.getString(KEY_STYLE);
        style = STYLES.contains(saved) ? saved : COLUMBIA;
        String edge = tag.getString("EdgeNumbers");
        edgeNumbers = EDGE_MODES.contains(edge) ? edge : EDGE_AUTO;
        accumFraction = finite(tag, "AccumFraction", RodIndications.DEFAULT_ACCUMULATOR_FRACTION, 0.1, 1.0);
        downscale = finite(tag, "Downscale", LprmReadout.DEFAULT_DOWNSCALE_PERCENT, 0.0, 100.0);
        upscale = finite(tag, "Upscale", LprmReadout.DEFAULT_UPSCALE_PERCENT, 0.0, 200.0);
    }

    private static double finite(CompoundTag tag, String key, double fallback, double min, double max) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return fallback;
        }
        double value = tag.getDouble(key);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
