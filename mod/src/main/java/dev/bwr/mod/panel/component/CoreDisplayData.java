package dev.bwr.mod.panel.component;

import dev.bwr.core.ReactorCore;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.LprmReadout;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.rods.RodIndications;
import dev.bwr.mod.signal.DeviceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The part the full core display and the rod select matrix share: the one reactor they read, the {@code tile_cells}
 * setting and the current {@link FcdSnapshot}.
 *
 * <p>Neither component has a port per rod. Its single input is bound with the linker like any plant input (any
 * signal of the reactor controller, {@code formed} is the natural one); the binding only says which reactor, and
 * on every sample of the panel the server reads that reactor's rods and LPRM strings into one snapshot
 * ({@link #sampleContent}). The snapshot is what the client draws, and the layout of the core travels inside it.
 * With no binding, a device that is not a reactor, an unloaded chunk or a reactor that is not formed the snapshot
 * is {@link FcdSnapshot#DARK}: the frame and dark tiles only.
 *
 * <h3>Data path</h3>
 * The snapshot reaches clients as ONE content patch (the whole snapshot, bit-packed, see {@link FcdSnapshot}), queued
 * only when the new snapshot differs from the last: a sample that finds nothing changed sends nothing and does not
 * touch the component, so a quiet core costs no traffic. A change of the drawn state does not bump the component's
 * revision either (the static geometry stays); only a change of layout does. The full-state packet and the save carry
 * the same bytes, so a client that joins late draws the right lamps at once.
 *
 * <p>Nothing here acts: the snapshot only reads, and the matrix's one output is operated by a player.
 */
public abstract class CoreDisplayData extends ComponentData implements PanelTextTarget {
    public static final int DEFAULT_TILE = FcdLayout.DEFAULT_TILE;
    /** Longest caption, characters. */
    public static final int MAX_CAPTION = 32;
    private static final java.util.List<String> TEXT_FIELDS = java.util.List.of("caption");
    /** First byte of a patch that carries the caption as well as the snapshot (a snapshot patch starts with its version, 1). */
    private static final int CAPTION_PATCH = 0x7F;

    /** Optional caption drawn above the picture ("FULL CORE DISPLAY", "ROD SELECT"); empty draws none. */
    public String caption = "";
    /** Whether the rod coordinates (18-31) are printed on the tiles, modules or buttons. The LPRM labels always are. */
    public boolean coordLegends = true;
    /** Server: the caption changed since the last {@link #drainPatch}. */
    private boolean captionPending;

    public int tileCells = DEFAULT_TILE;
    private FcdSnapshot snapshot = FcdSnapshot.DARK;
    /** Server: the snapshot changed since the last {@link #drainPatch}. */
    private boolean patchPending;

    /** What the component currently shows. */
    public final FcdSnapshot snapshot() {
        return snapshot;
    }

    /** The rod cells the component draws: those of the bound core, or the dark 185-rod reference layout when there is none. */
    public final FcdSnapshot.Cells displayCells() {
        return snapshot.cells().rodCount() > 0 ? snapshot.cells() : FcdSnapshot.referenceCells();
    }

    public final CoreRodMap displayMap() {
        return displayCells().map();
    }

    /** Replaces the snapshot; returns whether it changed. The caller sends or redraws. */
    protected final boolean storeSnapshot(FcdSnapshot next) {
        if (next.equals(snapshot)) {
            return false;
        }
        boolean layoutChanged = !next.cells().sameAs(snapshot.cells());
        snapshot = next;
        if (layoutChanged && host() != null) {
            host().touch(); // static geometry (tiles, labels) follows the layout
        }
        return true;
    }

    /** The drawing size this component needs for the layout it draws (a bound core's, else the reference one), in cells. */
    public abstract int[] neededCells();

    /** The same for a layout and tile setting that is not stored yet (a binding or config edit being checked). */
    public abstract int[] neededCells(CoreRodMap map, CompoundTag config);

    /** Whether the drawing fits a component of this size. */
    public final boolean fits(int width, int height) {
        int[] needed = neededCells();
        return width >= needed[0] && height >= needed[1];
    }

    /** Offset of the drawing inside a component of {@code size} cells, centred. */
    public static int centre(int size, int needed) {
        return Math.max(0, (size - needed) / 2);
    }

    // ---- sampling (server)

    /**
     * Reads the bound reactor into a snapshot and stores it. Called with the panel's other inputs: when the
     * binding changes, when the block loads, and on every sample while the input is live.
     */
    @Override
    public final void sampleContent(Level level, PanelBlockEntity owner, PanelComponent component) {
        FcdSnapshot next = read(level, component);
        if (storeSnapshot(next)) {
            changed(owner, component);
        }
    }

    /**
     * Sets the caption (the CC {@code setText} path): printable characters only, at most {@link #MAX_CAPTION}. It is
     * saved and sent to watching clients in the next patch, with the snapshot.
     */
    @Override
    public boolean setText(String text) {
        return setField("caption", text);
    }

    @Override
    public java.util.List<String> textFields() {
        return TEXT_FIELDS;
    }

    @Override
    public boolean setField(String field, String text) {
        if (!"caption".equals(field)) {
            return false;
        }
        String clean = ConfigSchema.cleanText(text == null ? "" : text, MAX_CAPTION);
        if (clean.equals(caption)) {
            return false;
        }
        caption = clean;
        captionPending = true;
        if (host() != null) {
            host().touch();
        }
        contentChanged();
        return true;
    }

    /** The snapshot changed: queue it as one content patch (and the save). */
    private void changed(PanelBlockEntity owner, PanelComponent component) {
        patchPending = true;
        contentChanged();
    }

    @Override
    public final boolean syncsByPatch() {
        return true;
    }

    @Override
    public final byte[] drainPatch() {
        if (!patchPending && !captionPending) {
            return null;
        }
        patchPending = false;
        byte[] bytes = snapshot.encode();
        if (captionPending) {
            // A changed caption travels with the snapshot, in one patch: marker, snapshot length, snapshot, caption.
            captionPending = false;
            byte[] text = caption.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] both = new byte[3 + bytes.length + text.length];
            both[0] = CAPTION_PATCH;
            both[1] = (byte) (bytes.length >> 8);
            both[2] = (byte) bytes.length;
            System.arraycopy(bytes, 0, both, 3, bytes.length);
            System.arraycopy(text, 0, both, 3 + bytes.length, text.length);
            return both.length <= MAX_PATCH ? both : null;
        }
        return bytes.length <= MAX_PATCH ? bytes : null;
    }

    @Override
    public final void applyPatch(byte[] patch) {
        if (patch != null && patch.length >= 3 && (patch[0] & 0xFF) == CAPTION_PATCH) {
            int length = (patch[1] & 0xFF) << 8 | patch[2] & 0xFF;
            if (3 + length > patch.length) {
                return;
            }
            FcdSnapshot decoded = FcdSnapshot.decode(java.util.Arrays.copyOfRange(patch, 3, 3 + length), snapshot);
            if (decoded == null) {
                return;
            }
            storeSnapshot(decoded);
            caption = ConfigSchema.cleanText(new String(patch, 3 + length, patch.length - 3 - length,
                    java.nio.charset.StandardCharsets.UTF_8), MAX_CAPTION);
            if (host() != null) {
                host().touch();
            }
            return;
        }
        FcdSnapshot next = FcdSnapshot.decode(patch, snapshot);
        if (next != null) {
            storeSnapshot(next);
        }
    }

    /** The reactor controller the component is bound to, or null (no binding, not a reactor, not loaded). */
    public static ReactorControllerBlockEntity boundReactor(Level level, PanelComponent component) {
        if (component.inputCount() == 0 || !(component.source(0) instanceof PlantSource source)) {
            return null;
        }
        BlockPos device = source.device();
        if (level == null || level.isClientSide() || !level.isLoaded(device)) {
            return null;
        }
        BlockEntity be = DeviceResolver.resolve(level, device);
        return be instanceof ReactorControllerBlockEntity reactor ? reactor : null;
    }

    private FcdSnapshot read(Level level, PanelComponent component) {
        ReactorControllerBlockEntity reactor = boundReactor(level, component);
        if (reactor == null || !reactor.isFormed() || reactor.rods() == null) {
            return FcdSnapshot.dark(snapshot.cells());
        }
        CoreRodMap map = reactor.coreMap();
        if (map == null) {
            return FcdSnapshot.dark(snapshot.cells());
        }
        return build(reactor, map, snapshot.cells());
    }

    /** The accumulator trouble fraction and LPRM alarm thresholds of this component. */
    protected double accumulatorFraction() {
        return RodIndications.DEFAULT_ACCUMULATOR_FRACTION;
    }

    protected double lprmDownscale() {
        return LprmReadout.DEFAULT_DOWNSCALE_PERCENT;
    }

    protected double lprmUpscale() {
        return LprmReadout.DEFAULT_UPSCALE_PERCENT;
    }

    private FcdSnapshot build(ReactorControllerBlockEntity reactor, CoreRodMap map, FcdSnapshot.Cells previous) {
        ReactorCore core = reactor.core();
        int rods = Math.min(map.rodCount(), core.getControlRodCount());
        FcdSnapshot.Cells cells = FcdSnapshot.Cells.of(map);
        if (previous.sameAs(cells)) {
            cells = previous;
        }
        byte[] state = new byte[map.rodCount()];
        double accumulator = accumulatorFraction();
        for (int r = 0; r < rods; r++) {
            state[r] = FcdSnapshot.pack(core.getRodNotchIndex(r), RodIndications.drift(core, reactor.rods(), r),
                    RodIndications.accumulatorTrouble(core, r, accumulator));
        }
        int strings = map.lprmStrings().size();
        byte[] percent = new byte[strings * 4];
        byte[] flags = new byte[strings * 4];
        double down = lprmDownscale();
        double up = lprmUpscale();
        for (int s = 0; s < strings; s++) {
            for (int level = 0; level < LprmReadout.LEVELS; level++) {
                double value = LprmReadout.percent(core, s, level);
                boolean bypassed = LprmReadout.bypassed(core, s, level);
                boolean failed = LprmReadout.failed(core, s, level);
                percent[s * 4 + level] = FcdSnapshot.packPercent(value);
                flags[s * 4 + level] = FcdSnapshot.packFlags(LprmReadout.downscale(value, bypassed, failed, down),
                        LprmReadout.upscale(value, bypassed, failed, up), bypassed, failed);
            }
        }
        return FcdSnapshot.of(core.isScramActive(), reactor.selectedRod(), cells, state, percent, flags);
    }

    // ---- saving

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("TileCells", tileCells);
        if (!caption.isEmpty()) {
            tag.putString("Caption", caption);
        }
        tag.putBoolean("CoordLegends", coordLegends);
        if (snapshot.formed()) {
            tag.putByteArray("Snap", snapshot.encode()); // a dark display is rebuilt from its binding on load
        }
        saveOwn(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        tileCells = tag.contains("TileCells", Tag.TAG_ANY_NUMERIC) ? FcdLayout.clampTile(tag.getInt("TileCells")) : DEFAULT_TILE;
        caption = ConfigSchema.cleanText(tag.getString("Caption"), MAX_CAPTION);
        coordLegends = !tag.contains("CoordLegends", Tag.TAG_ANY_NUMERIC) || tag.getBoolean("CoordLegends");
        FcdSnapshot loaded = tag.contains("Snap", Tag.TAG_BYTE_ARRAY) ? FcdSnapshot.decode(tag.getByteArray("Snap"), snapshot) : null;
        snapshot = loaded == null ? FcdSnapshot.DARK : loaded;
        loadOwn(tag);
    }

    protected abstract void saveOwn(CompoundTag tag);

    protected abstract void loadOwn(CompoundTag tag);
}
