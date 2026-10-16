package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderStyle;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where the parts of a recorder's face are, in the model's own inches, and the {@link Placement} that lays them on
 * the component's cells. The screen rectangle (where the 320 x 240 or 640 x 480 texture goes) and every key's hit
 * box come from the part bounds of the baked bezel model by part name ({@code screen}, {@code key_*},
 * {@code key_cover}, {@code cover_tab}, {@code power_led}; the contract of {@code DX1000-RECORDER-SPEC.md} section 7).
 * When the model is not loaded the same rectangles come from a built in table with the numbers of the shipped
 * models, so the procedural bezel works and a model that arrives later takes over with no code change.
 */
public final class RecorderLayout {
    /** An axis aligned rectangle in model inches. */
    public record Box(float x0, float y0, float x1, float y1) {
        public boolean contains(float x, float y) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1;
        }

        public float centreX() {
            return (x0 + x1) / 2.0f;
        }

        public float centreY() {
            return (y0 + y1) / 2.0f;
        }
    }

    /** Footprint of the shipped models, inches (144 mm and 288 mm squares). */
    public static final float DX1000_INCHES = 5.66929f;
    public static final float DX2000_INCHES = 11.33858f;

    public final RecorderStyle style;
    /** Whether the rectangles came from a loaded model (false: the built in table). */
    public final boolean baked;
    public final BakedPanelModel model;
    public final Placement place;
    public final Box screen;
    /** Model z of the screen, inches. */
    public final float screenZ;
    /** Model z of the bezel face, inches (where the keys stand). */
    public final float faceZ;
    public final Box cover;
    public final Box coverTab;
    public final Box led;
    private final Map<RecorderKey, Box> keys;

    private RecorderLayout(RecorderStyle style, boolean baked, BakedPanelModel model, Placement place, Box screen,
                           float screenZ, float faceZ, Box cover, Box coverTab, Box led, Map<RecorderKey, Box> keys) {
        this.style = style;
        this.baked = baked;
        this.model = model;
        this.place = place;
        this.screen = screen;
        this.screenZ = screenZ;
        this.faceZ = faceZ;
        this.cover = cover;
        this.coverTab = coverTab;
        this.led = led;
        this.keys = keys;
    }

    /**
     * The layout of {@code style} on a {@code cellsW x cellsH} component. {@code model} is the loaded bezel model
     * for the style, or null.
     *
     * @param rowAspect real length of a row relative to a column on the panel (see {@link Placement})
     */
    public static RecorderLayout of(RecorderStyle style, BakedPanelModel model, int cellsW, int cellsH, float rowAspect) {
        if (model != null && model.part("screen") != null) {
            Box screen = bounds(model.part("screen"));
            Map<RecorderKey, Box> keys = new EnumMap<>(RecorderKey.class);
            for (RecorderKey key : RecorderKey.values()) {
                BakedPanelModel.Part part = model.part(key.part());
                if (part != null) {
                    keys.put(key, bounds(part));
                }
            }
            BakedPanelModel.Part coverPart = model.part("key_cover");
            BakedPanelModel.Part ledPart = model.part("power_led");
            return new RecorderLayout(style, true, model, Placement.fit(model, cellsW, cellsH, rowAspect), screen,
                    zOf(model.part("screen")), faceOf(model), coverPart == null ? null : bounds(coverPart),
                    keys.get(RecorderKey.COVER_TAB), ledPart == null ? null : bounds(ledPart), keys);
        }
        float inches = style == RecorderStyle.DX2000 ? DX2000_INCHES : DX1000_INCHES;
        Placement place = Placement.fit(inches, inches, cellsW, cellsH, rowAspect);
        return style == RecorderStyle.DX2000 ? table2000(place) : table1000(place);
    }

    private static Box bounds(BakedPanelModel.Part part) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        for (int f = 0; f < part.faceCount; f++) {
            for (int corner = 0; corner < 4; corner++) {
                x0 = Math.min(x0, part.corner(f, corner, 0));
                x1 = Math.max(x1, part.corner(f, corner, 0));
                y0 = Math.min(y0, part.corner(f, corner, 1));
                y1 = Math.max(y1, part.corner(f, corner, 1));
            }
        }
        return new Box(x0, y0, x1, y1);
    }

    private static float zOf(BakedPanelModel.Part part) {
        float z = -Float.MAX_VALUE;
        for (int f = 0; f < part.faceCount; f++) {
            for (int corner = 0; corner < 4; corner++) {
                z = Math.max(z, part.corner(f, corner, 2));
            }
        }
        return z;
    }

    private static float faceOf(BakedPanelModel model) {
        BakedPanelModel.Part bezel = model.part("bezel");
        return bezel == null ? 1.0f : zOf(bezel);
    }

    private static Box box(float x0, float x1, float y0, float y1) {
        return new Box(x0, y0, x1, y1);
    }

    private static RecorderLayout table1000(Placement place) {
        Map<RecorderKey, Box> k = new EnumMap<>(RecorderKey.class);
        float[][] soft = {{0.628f, 1.194f}, {1.316f, 1.883f}, {2.004f, 2.571f}, {2.692f, 3.259f}, {3.365f, 3.932f}};
        for (int i = 0; i < soft.length; i++) {
            k.put(RecorderKey.values()[i], box(soft[i][0], soft[i][1], 4.604f, 4.844f));
        }
        k.put(RecorderKey.START, box(0.657f, 1.106f, 5.113f, 5.322f));
        k.put(RecorderKey.STOP, box(1.283f, 1.527f, 5.095f, 5.339f));
        k.put(RecorderKey.USER, box(1.694f, 2.072f, 5.113f, 5.322f));
        k.put(RecorderKey.FUNC, box(2.143f, 2.521f, 5.113f, 5.322f));
        k.put(RecorderKey.ESC, box(2.697f, 3.075f, 5.113f, 5.322f));
        k.put(RecorderKey.MENU, box(3.161f, 3.539f, 5.113f, 5.322f));
        k.put(RecorderKey.FAV, box(3.694f, 4.112f, 5.056f, 5.379f));
        k.put(RecorderKey.DISP, box(4.398f, 4.965f, 4.605f, 5.172f));
        k.put(RecorderKey.UP, box(4.394f, 4.968f, 4.347f, 4.585f));
        k.put(RecorderKey.DOWN, box(4.394f, 4.968f, 5.191f, 5.430f));
        k.put(RecorderKey.LEFT, box(4.140f, 4.378f, 4.601f, 5.175f));
        k.put(RecorderKey.RIGHT, box(4.984f, 5.223f, 4.601f, 5.175f));
        return new RecorderLayout(RecorderStyle.DX1000, false, null, place, box(0.555f, 5.114f, 0.669f, 4.091f), 0.669f, 0.984f,
                null, null, box(0.477f, 0.567f, 4.516f, 4.602f), k);
    }

    private static RecorderLayout table2000(Placement place) {
        Map<RecorderKey, Box> k = new EnumMap<>(RecorderKey.class);
        float[][] soft = {{1.661f, 2.331f}, {2.681f, 3.350f}, {3.701f, 4.370f}, {4.720f, 5.390f}, {5.740f, 6.409f},
                {6.760f, 7.429f}, {7.780f, 8.449f}};
        for (int i = 0; i < soft.length; i++) {
            k.put(RecorderKey.values()[i], box(soft[i][0], soft[i][1], 8.913f, 9.252f));
        }
        k.put(RecorderKey.START, box(1.754f, 2.435f, 9.661f, 9.984f));
        k.put(RecorderKey.STOP, box(1.839f, 2.272f, 10.185f, 10.618f));
        k.put(RecorderKey.ESC, box(2.799f, 3.461f, 9.661f, 9.984f));
        k.put(RecorderKey.MENU, box(3.610f, 4.272f, 9.661f, 9.984f));
        k.put(RecorderKey.USER, box(2.799f, 3.461f, 10.465f, 10.787f));
        k.put(RecorderKey.FUNC, box(3.610f, 4.272f, 10.465f, 10.787f));
        k.put(RecorderKey.FAV, box(7.874f, 8.543f, 10.325f, 10.817f));
        k.put(RecorderKey.DISP, box(9.181f, 10.307f, 9.083f, 10.209f));
        k.put(RecorderKey.UP, box(9.196f, 10.292f, 8.612f, 9.067f));
        k.put(RecorderKey.DOWN, box(9.196f, 10.292f, 10.224f, 10.680f));
        k.put(RecorderKey.LEFT, box(8.710f, 9.165f, 9.098f, 10.194f));
        k.put(RecorderKey.RIGHT, box(10.323f, 10.778f, 9.098f, 10.194f));
        Box tab = box(5.000f, 6.339f, 8.031f, 8.661f);
        k.put(RecorderKey.COVER_TAB, tab);
        return new RecorderLayout(RecorderStyle.DX2000, false, null, place, box(1.468f, 9.870f, 1.516f, 7.815f), 0.484f, 1.075f,
                box(1.319f, 8.583f, 8.386f, 10.957f), tab, box(0.772f, 0.921f, 9.102f, 9.244f), k);
    }

    // ---- geometry

    public Box key(RecorderKey key) {
        return keys.get(key);
    }

    /** Cell column of a model x (inches). */
    public float cellX(float inches) {
        return place.x(inches);
    }

    public float cellY(float inches) {
        return place.y(inches);
    }

    /** Model x (inches) of a cell column. */
    public float inchX(double cellX) {
        return (float) ((cellX - place.offsetX) / place.scale);
    }

    public float inchY(double cellY) {
        return (float) ((cellY - place.offsetY) / place.rowScale);
    }

    /**
     * The key under a click at component cell position ({@code cellX}, {@code cellY}), or null. The cover tab comes
     * first, a closed cover then swallows the click that would reach a key behind it, and otherwise the keys (whose
     * boxes are disjoint) are tried in turn.
     */
    public RecorderKey keyAt(double cellX, double cellY, boolean coverClosed) {
        float x = inchX(cellX);
        float y = inchY(cellY);
        Box tab = keys.get(RecorderKey.COVER_TAB);
        if (tab != null && tab.contains(x, y)) {
            return RecorderKey.COVER_TAB;
        }
        if (coverClosed && cover != null && cover.contains(x, y)) {
            return null;
        }
        for (Map.Entry<RecorderKey, Box> entry : keys.entrySet()) {
            if (entry.getKey() != RecorderKey.COVER_TAB && entry.getValue().contains(x, y)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
