package dev.bwr.mod.panel.plant;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in pages of a plant computer, for a player with no CC program: a reactor overview, trends and the flows. They
 * are made of the same elements as a program's pages, so they are drawn, patched and saved by the same code, and they read
 * the one reactor the display's {@code reactor} input is bound to (a Networking Wrench binding, like the core displays).
 *
 * <p>Read-only, and without judgement: they show the reactor's own signals, in plain colours. There are no alarm limits and
 * no safe / unsafe colouring here, because what counts as acceptable belongs to the plant's operator; a program that wants
 * it (the safety function grid of {@code docs/lua/spds.lua}) draws its own. Touching a soft key only changes the page shown.
 */
public final class PlantBuiltin {
    private PlantBuiltin() {
    }

    /** The modes of a plant computer: a program's pages, or one of the built-in pages ({@code PAGES}, in order). */
    public static final String PROGRAM = "program";
    public static final List<String> PAGES = List.of("overview", "trends", "flows");
    private static final String[] TITLES = {"REACTOR OVERVIEW", "TRENDS", "FLOWS AND HEAT"};
    private static final String[] KEYS = {"HOME", "PLOT", "FLOW"};

    /** What the built-in pages read: the bound reactor's signals. */
    public interface Readings {
        /** Whether the display is bound to a reactor controller that is loaded. */
        boolean bound();

        boolean formed();

        /** A signal by its key ({@code SIGNALS.md}), NaN when there is none. */
        float read(String key);
    }

    /** One number of a built-in page: where it comes from and how it is shown. */
    private record Metric(String key, String caption, String unit, float scale, int decimals, float min, float max) {
    }

    private static final Metric POWER = new Metric("power", "POWER", "%", 100.0f, 1, 0.0f, 120.0f);
    private static final Metric LEVEL = new Metric("level", "LEVEL", "in", 1.0f, 1, -200.0f, 100.0f);
    private static final Metric PRESSURE = new Metric("pressure", "PRESSURE", "psig", 1.0f, 0, 0.0f, 1400.0f);
    private static final Metric FLOW = new Metric("core_flow", "CORE FLOW", "%", 100.0f, 0, 0.0f, 120.0f);
    private static final Metric CONTAINMENT = new Metric("containment_pressure", "CONT PRESS", "psig", 1.0f, 1, -5.0f, 150.0f);
    private static final Metric STEAM_OUT = new Metric("steam_outlet_flow", "STEAM OUT", "kg/s", 1.0f, 0, 0.0f, 2500.0f);

    private static final Metric[] OVERVIEW = {POWER, LEVEL, PRESSURE, FLOW, CONTAINMENT};
    private static final Metric[] TRENDS = {POWER, PRESSURE, LEVEL, FLOW, CONTAINMENT, STEAM_OUT};
    private static final Metric[] FLOWS = {
            new Metric("thermal_mw", "THERMAL", "MW", 1.0f, 0, 0.0f, 4295.0f),
            new Metric("steam_generation", "STEAM GEN", "kg/s", 1.0f, 0, 0.0f, 2500.0f),
            STEAM_OUT,
            new Metric("feedwater_flow", "FEED DEMAND", "kg/s", 1.0f, 0, 0.0f, 2500.0f),
            new Metric("feedwater_delivered", "FEED DELIV", "kg/s", 1.0f, 0, 0.0f, 2500.0f),
            new Metric("injection_flow", "INJECTION", "kg/s", 1.0f, 0, 0.0f, 2500.0f),
            new Metric("recirc_flow_demand", "RECIRC DEM", "%", 100.0f, 0, 0.0f, 120.0f),
            new Metric("void_fraction", "VOID", "%", 100.0f, 1, 0.0f, 100.0f),
            new Metric("boron_ppm", "BORON", "ppm", 1.0f, 0, 0.0f, 1000.0f)};

    /** The page index of a built-in mode, or -1 for {@link #PROGRAM} or an unknown name. */
    public static int pageOf(String mode) {
        return PAGES.indexOf(mode);
    }

    // ---- building

    /** The three pages for a text grid, with no values yet: only what fits the grid is made. */
    public static PlantState build(PlantLayout layout, int selectedPage) {
        PlantState state = new PlantState();
        for (int p = 0; p < PAGES.size(); p++) {
            state.title(p, TITLES[p]);
        }
        buildOverview(state, layout);
        buildTrends(state, layout);
        buildFlows(state, layout);
        for (int p = 0; p < PAGES.size(); p++) {
            for (int k = 0; k < KEYS.length; k++) {
                PlantElement key = new PlantElement(PlantElement.Kind.KEY, "k" + (k + 1));
                key.state = k + 1;
                key.text = KEYS[k];
                key.colour = k == p ? PlantColors.CYAN : PlantColors.GREY;
                key.col = 1 + layout.keyStart(key.state);
                key.row = layout.rows();
                if (layout.keyWidth(key.state) > 0 && layout.rows() >= 4) {
                    state.put(p, key);
                }
            }
        }
        state.select(Math.max(0, Math.min(PAGES.size() - 1, selectedPage)));
        state.markAllChanged();
        return state;
    }

    private static PlantElement field(String id, Metric m, int col, int row, int w) {
        PlantElement e = new PlantElement(PlantElement.Kind.FIELD, id);
        e.col = col;
        e.row = row;
        e.w = w;
        e.text = m.caption;
        e.unit = m.unit;
        e.decimals = m.decimals;
        return e;
    }

    private static boolean room(PlantLayout layout, int row, int height) {
        // The last row is the soft keys'.
        return row >= 1 && row + height - 1 <= layout.rows() - 1;
    }

    private static void buildOverview(PlantState state, PlantLayout layout) {
        if (room(layout, 2, 1)) {
            PlantElement status = new PlantElement(PlantElement.Kind.TEXT, "st");
            status.col = 1;
            status.row = 2;
            status.w = 1;
            status.text = "";
            state.put(0, status);
        }
        int step = layout.rows() >= 15 ? 2 : 1;
        int start = step == 2 ? 4 : 3;
        for (int i = 0; i < OVERVIEW.length; i++) {
            int row = start + step * i;
            if (!room(layout, row, step)) {
                break;
            }
            Metric m = OVERVIEW[i];
            state.put(0, field("f" + i, m, 1, row, layout.cols()));
            if (step == 2) {
                PlantElement bar = new PlantElement(PlantElement.Kind.BAR, "b" + i);
                bar.col = 1;
                bar.row = row + 1;
                bar.w = layout.cols();
                bar.min = m.min;
                bar.max = m.max;
                bar.colour = PlantColors.CYAN;
                bar.state = 1;
                state.put(0, bar);
            }
        }
    }

    private static void buildTrends(PlantState state, PlantLayout layout) {
        int available = layout.rows() - 2;
        int bands = Math.min(3, available / 5);
        if (bands < 1 || layout.cols() < 14) {
            return;
        }
        int height = available / bands;
        int width = layout.cols() / 2;
        for (int i = 0; i < Math.min(TRENDS.length, bands * 2); i++) {
            Metric m = TRENDS[i];
            PlantElement t = new PlantElement(PlantElement.Kind.TREND, "t" + i);
            t.col = 1 + (i % 2) * width;
            t.row = 2 + (i / 2) * height;
            t.w = i % 2 == 1 ? layout.cols() - width : width;
            t.h = height;
            t.text = m.caption;
            t.unit = m.unit;
            t.decimals = m.decimals;
            t.min = m.min;
            t.max = m.max;
            t.len = PlantLimits.MAX_POINTS;
            t.points = new char[t.len];
            t.colour = PlantColors.CYAN;
            state.put(1, t);
        }
    }

    private static void buildFlows(PlantState state, PlantLayout layout) {
        boolean two = layout.cols() >= 40;
        int width = two ? layout.cols() / 2 : layout.cols();
        int perColumn = layout.rows() - 3;
        for (int i = 0; i < FLOWS.length; i++) {
            int column = two ? i / Math.max(1, perColumn) : 0;
            int row = 3 + (two ? i % Math.max(1, perColumn) : i);
            if (column > 1 || !room(layout, row, 1)) {
                continue;
            }
            state.put(2, field("g" + i, FLOWS[i], 1 + column * width, row, column == 1 ? layout.cols() - width : width));
        }
    }

    // ---- updating

    private static float value(Readings r, Metric m) {
        if (!r.bound() || !r.formed()) {
            return Float.NaN;
        }
        float raw = r.read(m.key);
        return Float.isNaN(raw) || Float.isInfinite(raw) ? Float.NaN : raw * m.scale;
    }

    /**
     * Writes the readings into the built-in pages: the numbers every call (a number that does not change what is shown
     * queues nothing), a point on each trend only when {@code pushTrends}. Returns whether anything changed.
     */
    public static boolean update(PlantState state, Readings r, boolean pushTrends) {
        boolean changed = false;
        for (int i = 0; i < OVERVIEW.length; i++) {
            float v = value(r, OVERVIEW[i]);
            if (state.element(0, "f" + i) != null) {
                changed |= state.setValue(0, "f" + i, v);
            }
            if (state.element(0, "b" + i) != null) {
                changed |= state.setValue(0, "b" + i, v);
            }
        }
        for (int i = 0; i < FLOWS.length; i++) {
            if (state.element(2, "g" + i) != null) {
                changed |= state.setValue(2, "g" + i, value(r, FLOWS[i]));
            }
        }
        if (pushTrends) {
            for (int i = 0; i < TRENDS.length; i++) {
                float v = value(r, TRENDS[i]);
                if (state.element(1, "t" + i) != null && !Float.isNaN(v)) {
                    changed |= state.push(1, "t" + i, v);
                }
            }
        }
        PlantElement old = state.element(0, "st");
        if (old != null) {
            PlantElement status = old.copy();
            if (!r.bound()) {
                status.text = "NO REACTOR BOUND";
                status.colour = PlantColors.AMBER;
            } else if (!r.formed()) {
                status.text = "REACTOR NOT FORMED";
                status.colour = PlantColors.AMBER;
            } else if (r.read("scram") > 0.5f) {
                status.text = "SCRAM ACTIVE";
                status.colour = PlantColors.RED;
            } else {
                status.text = "SCRAM NOT ACTIVE";
                status.colour = PlantColors.GREY;
            }
            status.w = Math.max(1, status.text.length());
            changed |= state.put(0, status);
        }
        return changed;
    }

    /** The ids of the numbers on the built-in pages (for tests): overview fields, trends and flow fields. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < OVERVIEW.length; i++) {
            ids.add("f" + i);
        }
        for (int i = 0; i < TRENDS.length; i++) {
            ids.add("t" + i);
        }
        for (int i = 0; i < FLOWS.length; i++) {
            ids.add("g" + i);
        }
        return ids;
    }

    /** The signal key behind a built-in element id, or null. */
    public static String keyOf(String id) {
        int i = id.length() > 1 && Character.isDigit(id.charAt(1)) ? Integer.parseInt(id.substring(1)) : -1;
        return switch (id.charAt(0)) {
            case 'f', 'b' -> i >= 0 && i < OVERVIEW.length ? OVERVIEW[i].key : null;
            case 't' -> i >= 0 && i < TRENDS.length ? TRENDS[i].key : null;
            case 'g' -> i >= 0 && i < FLOWS.length ? FLOWS[i].key : null;
            default -> null;
        };
    }
}
