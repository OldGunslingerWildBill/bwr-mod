package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.plant.PlantBuiltin;
import dev.bwr.mod.panel.plant.PlantElement;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantLimits;
import dev.bwr.mod.panel.plant.PlantState;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The state of one plant computer ({@code bwr:plant_computer}): what it shows and how it gets it.
 *
 * <h3>Two sources, one display</h3>
 * {@link #mode} {@code program} shows the pages a CC program defines with the {@code bwr_plant_computer} calls
 * ({@code PlantApi}); with none it shows a static "NO PROGRAM" screen. {@code overview}, {@code trends} and {@code flows}
 * show a built-in page ({@link PlantBuiltin}) that reads the reactor the {@code reactor} input is bound to, for a player
 * with no code. Both are a {@link PlantState}, drawn, patched and saved by the same code. Nothing here acts on the plant:
 * the display has no outputs, and a touched soft key only changes the page shown or raises a CC event.
 *
 * <h3>Network</h3>
 * Changes reach clients as delta patches ({@link PlantState#drainPatch}): what changed since the last one, from the current
 * state, in at most {@code ComponentData.MAX_PATCH} bytes (the full state when the changes do not fit). A display sends at
 * most one patch every {@link PlantLimits#PUBLISH_TICKS} ticks however often a program draws; {@code flush()} asks for the
 * next one sooner. Clients within the chunk watch distance get them, like every panel patch, and a client that joins later
 * gets the full state in the panel's usual full sync (which {@link #save} is part of).
 *
 * <h3>Save</h3>
 * Keys are written only when they are not the default: {@code Mode}, {@code TextSize}, {@code TrendSeconds}, the program's
 * pages ({@code Program}) and, in a built-in mode, the built-in pages with their trend history ({@code Builtin}). A save
 * from before the plant computer existed has none of them, and a display with nothing on it writes nothing.
 */
public final class PlantComputerData extends ComponentData {
    public static final String KEY_MODE = "Mode";
    public static final String KEY_TEXT_SIZE = GaugeScale.TEXT_SIZE;
    public static final String KEY_TREND_SECONDS = "TrendSeconds";
    public static final int DEFAULT_TREND_SECONDS = 5;
    public static final int MIN_TREND_SECONDS = 1;
    public static final int MAX_TREND_SECONDS = 60;
    public static final List<String> MODES = List.of(PlantBuiltin.PROGRAM, "overview", "trends", "flows");

    public String mode = PlantBuiltin.PROGRAM;
    public int textSize = GaugeScale.DEFAULT_PERCENT;
    public int trendSeconds = DEFAULT_TREND_SECONDS;

    private PlantState program = new PlantState();
    private PlantState builtin = new PlantState();
    /** The grid the built-in pages were built for ("31x15"); they are built again when the grid changes. */
    private String builtinGrid = "";
    private long lastTrendPush = Long.MIN_VALUE / 2;

    // publishing and limits (server)
    private long lastPublish = Long.MIN_VALUE / 2;
    private long lastFlush = Long.MIN_VALUE / 2;
    private boolean forceNext;
    private double tokens = PlantLimits.CALL_BURST;
    private long tokenTime;
    private long lastTouch = Long.MIN_VALUE / 2;
    /** Patches handed out by {@link #drainPatch}, for checks. */
    private int patches;

    /** Size used until the data is attached to a component (tests). */
    private int fallbackW = 64;
    private int fallbackH = 48;

    public static List<ConfigField> schema() {
        return List.of(
                ConfigField.choice(KEY_MODE, "component.bwr.field.plant_mode", MODES),
                ConfigField.slider(KEY_TEXT_SIZE, "component.bwr.field.text_size", GaugeScale.MIN_PERCENT, GaugeScale.MAX_PERCENT,
                        GaugeScale.STEP_PERCENT, GaugeScale.DEFAULT_PERCENT),
                ConfigField.integer(KEY_TREND_SECONDS, "component.bwr.field.plant_trend_seconds", MIN_TREND_SECONDS, MAX_TREND_SECONDS));
    }

    // ---- reading

    public boolean programMode() {
        return PlantBuiltin.PROGRAM.equals(mode);
    }

    public PlantState program() {
        return program;
    }

    public PlantState builtin() {
        return builtin;
    }

    /** What is drawn: the program's pages or the built-in ones. */
    public PlantState shown() {
        return programMode() ? program : builtin;
    }

    /** Test hook: the size of a component this data is not attached to. */
    public void setFallbackSize(int w, int h) {
        fallbackW = w;
        fallbackH = h;
    }

    public int cellsWide() {
        return host() != null ? host().cellsWide() : fallbackW;
    }

    public int cellsHigh() {
        return host() != null ? host().cellsHigh() : fallbackH;
    }

    /** The text grid at the current size and Text size. */
    public PlantLayout layout() {
        return PlantLayout.of(cellsWide(), cellsHigh(), textSize);
    }

    /** The clock used while the data is not attached to a component: tests set it. */
    private long clock;

    public void setClock(long ticks) {
        clock = ticks;
    }

    private long now() {
        return host() != null ? host().gameTime() : clock;
    }

    /** Patches handed out so far (checks). */
    public int patchesSent() {
        return patches;
    }

    // ---- program calls

    /** Takes one token of the call rate limit; throws {@link PlantState.Rejected} when there is none. */
    public void takeCall() {
        long time = now();
        if (time > tokenTime) {
            tokens = Math.min(PlantLimits.CALL_BURST, tokens + (time - tokenTime) * PlantLimits.CALL_REFILL_PER_TICK);
        }
        tokenTime = time;
        if (tokens < 1.0) {
            throw new PlantState.Rejected("too many plant computer calls (at most " + (int) (PlantLimits.CALL_REFILL_PER_TICK * 20)
                    + " a second); draw less often or only what changed");
        }
        tokens -= 1.0;
    }

    /** A program call changed (or did not change) the program's pages: queue the save and the patch. */
    public boolean changed(boolean changed) {
        if (changed) {
            contentChanged();
        }
        return changed;
    }

    /**
     * Sends the pending changes with the next tick's patch instead of waiting for the usual interval, if there are any
     * and the last such request was at least {@link PlantLimits#FLUSH_MIN_TICKS} ago. Returns whether one was requested.
     */
    public boolean flush() {
        long time = now();
        if (!shown().pending() || time - lastFlush < PlantLimits.FLUSH_MIN_TICKS) {
            return false;
        }
        lastFlush = time;
        forceNext = true;
        contentChanged();
        return true;
    }

    // ---- touch

    /** The soft key under the click value {@code clicked} ({@code row * 256 + col}, 0-based), or 0. */
    public int keyAtClick(double clicked) {
        int packed = (int) clicked;
        int col = packed & 255;
        int row = packed >> 8;
        PlantLayout layout = layout();
        int key = layout.keyAt(col, row);
        if (key == 0) {
            return 0;
        }
        for (PlantElement e : shown().elements(shown().selected())) {
            if (e.kind == PlantElement.Kind.KEY && e.state == key) {
                return key;
            }
        }
        return 0;
    }

    /** Whether a touch may be handled now (one per {@link PlantLimits#TOUCH_TICKS}). */
    public boolean acceptTouch() {
        long time = now();
        if (time - lastTouch < PlantLimits.TOUCH_TICKS) {
            return false;
        }
        lastTouch = time;
        return true;
    }

    /** A built-in page's soft key: shows page {@code key} (1-based). Returns whether the page changed. */
    public boolean showBuiltinPage(int key) {
        if (key < 1 || key > PlantBuiltin.PAGES.size()) {
            return false;
        }
        String next = PlantBuiltin.PAGES.get(key - 1);
        if (next.equals(mode)) {
            return false;
        }
        mode = next;
        return true;
    }

    // ---- sampling (server)

    /** The readings of a reactor controller (null: no reactor bound). */
    public static PlantBuiltin.Readings readings(ReactorControllerBlockEntity reactor) {
        return new ReactorReadings(reactor);
    }

    private static final class ReactorReadings implements PlantBuiltin.Readings {
        private final ReactorControllerBlockEntity reactor;
        private final SignalProvider provider;

        ReactorReadings(ReactorControllerBlockEntity reactor) {
            this.reactor = reactor;
            this.provider = reactor == null ? null : SignalProviders.of(reactor);
        }

        @Override
        public boolean bound() {
            return reactor != null;
        }

        @Override
        public boolean formed() {
            return reactor != null && reactor.isFormed();
        }

        @Override
        public float read(String key) {
            if (provider == null) {
                return Float.NaN;
            }
            OptionalDouble value = provider.read(key);
            return value.isPresent() ? (float) value.getAsDouble() : Float.NaN;
        }
    }

    /**
     * Called with the panel's other inputs (a binding changed, the block loaded, a sample every few ticks): updates the
     * built-in pages from the bound reactor, and asks for a patch when anything is waiting.
     */
    @Override
    public void sampleContent(Level level, PanelBlockEntity owner, PanelComponent component) {
        if (!programMode()) {
            syncBuiltin(new ReactorReadings(CoreDisplayData.boundReactor(level, component)));
        }
        if (shown().pending()) {
            contentChanged();
        }
    }

    /** Updates the built-in pages from {@code readings}; builds them first if the grid changed. Server. */
    public void syncBuiltin(PlantBuiltin.Readings readings) {
        long time = now();
        boolean push = time - lastTrendPush >= trendSeconds * 20L;
        syncBuiltin(readings, push);
    }

    /** As {@link #syncBuiltin(PlantBuiltin.Readings)}, saying whether this call adds a point to the trends (tests and checks). */
    public void syncBuiltin(PlantBuiltin.Readings readings, boolean pushTrends) {
        PlantLayout layout = layout();
        String grid = layout.cols() + "x" + layout.rows();
        int page = Math.max(0, PlantBuiltin.pageOf(mode));
        if (!grid.equals(builtinGrid) || builtin.definedPages() == 0) {
            builtin = PlantBuiltin.build(layout, page);
            builtinGrid = grid;
            lastTrendPush = Long.MIN_VALUE / 2;
        }
        // The key colours name the page shown; they follow a page change made by touch.
        if (builtin.select(page)) {
            builtin.markAllChanged();
        }
        if (pushTrends) {
            lastTrendPush = now();
        }
        PlantBuiltin.update(builtin, readings, pushTrends);
    }

    @Override
    public boolean syncsByPatch() {
        return true;
    }

    /**
     * The changes of the shown state, or null when there are none or the last patch was less than
     * {@link PlantLimits#PUBLISH_TICKS} ticks ago (they stay pending and go out with a later one).
     */
    @Override
    public byte[] drainPatch() {
        PlantState state = shown();
        if (!state.pending()) {
            return null;
        }
        long time = now();
        if (!forceNext && time - lastPublish < PlantLimits.PUBLISH_TICKS) {
            return null;
        }
        forceNext = false;
        lastPublish = time;
        byte[] bytes = state.drainPatch(MAX_PATCH);
        if (bytes != null) {
            patches++;
        }
        return bytes;
    }

    /** Client: applies a patch of the shown state. */
    @Override
    public void applyPatch(byte[] patch) {
        shown().applyPatch(patch);
        if (host() != null) {
            host().touch();
        }
    }

    // ---- persistence

    @Override
    public void save(CompoundTag tag) {
        if (!programMode()) {
            tag.putString(KEY_MODE, mode);
        }
        if (textSize != GaugeScale.DEFAULT_PERCENT) {
            tag.putInt(KEY_TEXT_SIZE, textSize);
        }
        if (trendSeconds != DEFAULT_TREND_SECONDS) {
            tag.putInt(KEY_TREND_SECONDS, trendSeconds);
        }
        if (!program.isEmpty() || program.selected() != 0) {
            tag.putByteArray("Program", program.encodeFull());
        }
        if (!programMode() && !builtin.isEmpty()) {
            tag.putByteArray("Builtin", builtin.encodeFull());
            tag.putString("BuiltinGrid", builtinGrid);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        String saved = tag.getString(KEY_MODE);
        mode = MODES.contains(saved) ? saved : PlantBuiltin.PROGRAM;
        textSize = tag.contains(KEY_TEXT_SIZE, Tag.TAG_ANY_NUMERIC) ? GaugeScale.clampPercent(tag.getInt(KEY_TEXT_SIZE))
                : GaugeScale.DEFAULT_PERCENT;
        trendSeconds = tag.contains(KEY_TREND_SECONDS, Tag.TAG_ANY_NUMERIC)
                ? Math.max(MIN_TREND_SECONDS, Math.min(MAX_TREND_SECONDS, tag.getInt(KEY_TREND_SECONDS))) : DEFAULT_TREND_SECONDS;
        PlantState loadedProgram = new PlantState();
        if (tag.contains("Program", Tag.TAG_BYTE_ARRAY)) {
            // Malformed bytes leave an empty program, never a half-read one.
            loadedProgram.loadFull(tag.getByteArray("Program"));
        }
        program = loadedProgram;
        PlantState loadedBuiltin = new PlantState();
        builtinGrid = "";
        if (tag.contains("Builtin", Tag.TAG_BYTE_ARRAY) && loadedBuiltin.loadFull(tag.getByteArray("Builtin"))) {
            builtinGrid = tag.getString("BuiltinGrid");
        }
        builtin = loadedBuiltin;
        if (host() != null) {
            host().touch();
        }
    }
}
