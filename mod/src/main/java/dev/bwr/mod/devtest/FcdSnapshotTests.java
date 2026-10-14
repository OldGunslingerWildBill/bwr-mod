package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.CompactCoreLayout;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RodSelectMatrixData;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The data path of the core displays (spec item 4): one compact snapshot of the bound reactor per sample, sent as ONE
 * content patch and only when something changed. The codec is tested pure (round trip, the size of the largest
 * core against {@link ComponentData#MAX_PATCH}, malformed input), the rest on a real formed core with a display bound
 * to it: the first patch, the sample that changes nothing and sends nothing, a rod that moves, the late-joining
 * client that gets the same bytes in the full state, and the snapshot of an unformed core.
 *
 * <p>Fixture origin 81_900 / 81_900, {@code slot * 64} blocks apart in X (see {@link FcdTestSupport}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdSnapshotTests {
    static final int ORIGIN = 81_900;

    private static FcdSnapshot synthetic(int width, int depth, boolean formed) {
        CompactCoreLayout layout = new CompactCoreLayout(width, depth);
        int[] col = new int[layout.drives().size()];
        int[] row = new int[col.length];
        for (int i = 0; i < col.length; i++) {
            col[i] = layout.drives().get(i).x();
            row[i] = layout.drives().get(i).z();
        }
        CoreRodMap map = CoreRodMap.of(col, row);
        FcdSnapshot.Cells cells = FcdSnapshot.Cells.of(map);
        byte[] state = new byte[col.length];
        for (int i = 0; i < state.length; i++) {
            state[i] = FcdSnapshot.pack(i % 25, i % 7 == 0, i % 11 == 0);
        }
        int strings = map.lprmStrings().size();
        byte[] percent = new byte[strings * 4];
        byte[] flags = new byte[strings * 4];
        for (int i = 0; i < percent.length; i++) {
            percent[i] = FcdSnapshot.packPercent(i * 3.3);
            flags[i] = FcdSnapshot.packFlags(i % 5 == 0, i % 7 == 0, i % 11 == 0, i % 13 == 0);
        }
        return formed ? FcdSnapshot.of(true, 33, cells, state, percent, flags) : FcdSnapshot.dark(cells);
    }

    // ---- codec

    @GameTest(template = "empty", batch = "fcd")
    public static void snapshotRoundTripsAndStaysFarUnderTheLimit(GameTestHelper h) {
        for (int size : new int[] {5, 9, 15, 21}) {
            FcdSnapshot snap = synthetic(size, size, true);
            byte[] bytes = snap.encode();
            FcdSnapshot back = FcdSnapshot.decode(bytes, null);
            h.assertTrue(back != null && back.equals(snap) && back.rodCount() == snap.rodCount(), size + ": round trip");
            h.assertTrue(java.util.Arrays.equals(back.encode(), bytes), size + ": encoding is stable");
            h.assertTrue(bytes.length < 2500 && bytes.length <= ComponentData.MAX_PATCH,
                    size + ": " + snap.rodCount() + " rods encode to " + bytes.length + " bytes");
            for (int r = 0; r < snap.rodCount(); r++) {
                h.assertTrue(back.notch(r) == snap.notch(r) && back.drift(r) == snap.drift(r)
                        && back.accumulatorTrouble(r) == snap.accumulatorTrouble(r), "rod bits");
            }
            h.assertTrue(back.selected() == 33 && back.formed() && back.lprmStrings() == snap.lprmStrings(), "header");
            for (int i = 0; i < snap.lprmStrings(); i++) {
                for (int level = 0; level < 4; level++) {
                    h.assertTrue(back.lprmPercent(i, level) == snap.lprmPercent(i, level)
                            && back.lprmLamp(i, level) == snap.lprmLamp(i, level), "lprm bits");
                }
            }
        }
        FcdSnapshot dark = synthetic(15, 15, false);
        FcdSnapshot darkBack = FcdSnapshot.decode(dark.encode(), null);
        h.assertTrue(darkBack != null && !darkBack.formed() && darkBack.rodCount() == 185 && darkBack.lprmLamp(0, 0) == FcdSnapshot.LAMP_OFF,
                "a dark snapshot keeps its tiles");
        h.assertTrue(FcdSnapshot.decode(FcdSnapshot.DARK.encode(), null).rodCount() == 0, "nothing round trips");
        // The cells are shared with the snapshot they replace when they did not change.
        FcdSnapshot a = synthetic(15, 15, true);
        FcdSnapshot b = FcdSnapshot.decode(a.encode(), a);
        h.assertTrue(b.cells() == a.cells(), "unchanged cells are reused");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void malformedSnapshotsAreRefusedNotApplied(GameTestHelper h) {
        byte[] good = synthetic(9, 9, true).encode();
        h.assertTrue(FcdSnapshot.decode(null, null) == null && FcdSnapshot.decode(new byte[0], null) == null
                && FcdSnapshot.decode(new byte[] {1, 2, 3}, null) == null, "short input");
        byte[] wrongVersion = good.clone();
        wrongVersion[0] = 9;
        h.assertTrue(FcdSnapshot.decode(wrongVersion, null) == null, "wrong version");
        h.assertTrue(FcdSnapshot.decode(java.util.Arrays.copyOf(good, good.length - 1), null) == null, "truncated");
        h.assertTrue(FcdSnapshot.decode(java.util.Arrays.copyOf(good, good.length + 1), null) == null, "trailing bytes");
        byte[] badNotch = good.clone();
        badNotch[8 + 2] = 31;
        h.assertTrue(FcdSnapshot.decode(badNotch, null) == null, "a notch above 24");
        byte[] twoInOneCell = good.clone();
        twoInOneCell[8 + 3] = twoInOneCell[8];
        twoInOneCell[8 + 4] = twoInOneCell[8 + 1];
        h.assertTrue(FcdSnapshot.decode(twoInOneCell, null) == null, "two rods in one cell");
        byte[] manyRods = good.clone();
        manyRods[4] = (byte) 0xFF;
        manyRods[5] = (byte) 0xFF;
        h.assertTrue(FcdSnapshot.decode(manyRods, null) == null, "an absurd rod count");
        // The data object ignores a bad patch and keeps what it showed.
        FullCoreDisplayData data = new FullCoreDisplayData();
        data.applyPatch(good);
        FcdSnapshot shown = data.snapshot();
        data.applyPatch(wrongVersion);
        data.applyPatch(new byte[] {1});
        h.assertTrue(shown.rodCount() > 0, "the good patch applied");
        h.assertTrue(data.snapshot() == shown, "bad patches changed nothing");
        h.succeed();
    }

    // ---- on a real core

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 500)
    public static void onePatchOnlyWhenSomethingChanged(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 0, 15, 15, run -> {
            run.wall(6, 6);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent display = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(96, 96));
            FullCoreDisplayData data = (FullCoreDisplayData) display.data();
            h.assertTrue(data.syncsByPatch(), "a core display sends its content as patches");
            SignalProvider rp = SignalProviders.of(run.be);
            h.assertTrue(data.drainPatch() == null && !block.contentPending(), "unbound and untouched: nothing to send");
            // Everything below happens inside one tick, so the real sampler and the sync cannot take a patch first.
            h.assertTrue(run.bindReactor(block, display, run.be.getBlockPos()).result() == dev.bwr.mod.panel.wiring.WiringOps.Result.OK, "bound");
            h.assertTrue(block.contentPending(), "binding queued the first snapshot");
            byte[] first = data.drainPatch();
            h.assertTrue(first != null && first.length > 100 && first.length <= ComponentData.MAX_PATCH, "one patch, bounded");
            h.assertTrue(data.drainPatch() == null, "the patch is taken once");
            // A late client gets the same bytes in the full state, and a patch lands on a client copy.
            PanelComponent client = PanelComponent.load(display.saveWithValues());
            h.assertTrue(((FullCoreDisplayData) client.data()).snapshot().equals(data.snapshot()), "full state carries the snapshot");
            FullCoreDisplayData clientData = new FullCoreDisplayData();
            clientData.applyPatch(first);
            h.assertTrue(clientData.snapshot().equals(data.snapshot()), "the patch rebuilds the snapshot");
            int revision = display.revision();
            // Nothing changed: samples send nothing and touch nothing.
            block.sampleLive();
            block.sampleLive();
            h.assertTrue(data.drainPatch() == null, "a sample that finds no change sends no patch");
            h.assertTrue(display.revision() == revision, "and does not redraw the static geometry");
            // A rod moves (stepped by hand, as the controller would over the next ticks): a sample after it queues
            // exactly one patch, without touching the revision.
            h.assertTrue(rp.command("rod_20_position", 4), "demand rod 20 out two notches");
            for (int i = 0; i < 60; i++) {
                run.be.rods().preStep(0.05);
                run.be.core().step();
                run.be.rods().postStep();
            }
            block.sampleLive();
            h.assertTrue(block.contentPending(), "a rod moved: a patch is queued");
            FcdSnapshot moved = FcdSnapshot.decode(data.drainPatch(), data.snapshot());
            h.assertTrue(moved != null && moved.notch(19) >= 1, "the patch shows rod 20 on its way out");
            h.assertTrue(data.drainPatch() == null, "one patch for one change");
            block.sampleLive();
            h.assertTrue(data.drainPatch() == null, "and no second one for the same state");
            h.assertTrue(display.revision() == revision, "the layout did not change, so the drawing is not rebuilt");
            // Selecting a rod and a scram show in the next patch.
            h.assertTrue(rp.command("select_rod", 20) && rp.command("scram", 1), "select and scram");
            block.sampleLive();
            FcdSnapshot after = FcdSnapshot.decode(data.drainPatch(), data.snapshot());
            h.assertTrue(after != null && after.selected() == 20 && after.scram() && after.selectedRod(19) && after.scramLamp(19),
                    "selected rod and scram lamp");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 300)
    public static void matrixAndUnformedSnapshots(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 1, 15, 15, run -> {
            run.wall(4, 4);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent matrix = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.ROD_SELECT_MATRIX,
                    Footprint.of(64, 64));
            RodSelectMatrixData data = (RodSelectMatrixData) matrix.data();
            h.assertTrue(run.bindReactor(block, matrix, run.be.getBlockPos()).result() == dev.bwr.mod.panel.wiring.WiringOps.Result.OK, "bound");
            h.assertTrue(data.drainPatch() != null, "the first snapshot is a patch");
            h.assertTrue(run.be.selectRod(5), "selected");
            block.sampleLive();
            FcdSnapshot snap = FcdSnapshot.decode(data.drainPatch(), data.snapshot());
            h.assertTrue(snap != null && snap.selected() == 5 && snap.selectedRod(4), "the selection reaches the matrix");
            // The reactor unforms: the next sample queues one dark snapshot that keeps the tiles.
            run.level.removeBlock(run.be.structure().crdPositions().get(0), false);
            FcdRodTests.revalidate(run.be);
            block.sampleLive();
            FcdSnapshot dark = FcdSnapshot.decode(data.drainPatch(), data.snapshot());
            h.assertTrue(dark != null && !dark.formed() && dark.rodCount() == 185 && dark.selected() == 0, "unformed: dark, same tiles");
            block.sampleLive();
            h.assertTrue(data.drainPatch() == null, "and nothing more while it stays dark");
        });
    }
}
