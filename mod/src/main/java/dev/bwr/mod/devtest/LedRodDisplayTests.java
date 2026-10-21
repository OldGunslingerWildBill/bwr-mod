package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.LedRodDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The LED rod pattern display ({@code bwr:led_rod_display}): it is a core display (one {@code core} input, the same
 * snapshot as the full core display), the LEDs of each pattern, tiles of 1 to 4 cells and what a size gives, the config round
 * trip, the refusal of a binding that is not a reactor, and that the other core displays are unchanged. No reactor is needed:
 * the snapshots are built here and handed to the data as the server would.
 *
 * <p>World fixture at 182_600 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class LedRodDisplayTests {
    private LedRodDisplayTests() {
    }

    static final int ORIGIN_X = 182_600;
    static final int ORIGIN_Z = 181_000;

    /** Panel blocks beside and below the stage's one, so that a component of 17 cells has the two blocks it needs each way. */
    private static void widen(HardwareStage s) {
        var panel = dev.bwr.mod.registry.BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                .setValue(dev.bwr.mod.panel.PanelBlock.FACING, HardwareStage.FACING);
        var west = s.origin.relative(HardwareStage.FACING.getCounterClockWise());
        s.level.setBlock(west, panel, 3);
        s.level.setBlock(s.origin.below(), panel, 3);
        s.level.setBlock(west.below(), panel, 3);
    }

    /** A snapshot of the reference core with rod 0 fully in, 1 half out, 2 fully out, 3 out and drifting, 4 with accumulator trouble, 5 selected. */
    private static FcdSnapshot snapshot() {
        // (normalised cells, as the server builds them: the reference cells keep raw drive coordinates, which a byte cannot hold)
        FcdSnapshot.Cells cells = FcdSnapshot.Cells.of(FcdSnapshot.referenceCells().map());
        int rods = cells.rodCount();
        byte[] state = new byte[rods];
        state[0] = FcdSnapshot.pack(0, false, false);
        state[1] = FcdSnapshot.pack(12, false, false);
        state[2] = FcdSnapshot.pack(FcdSnapshot.NOTCHES, false, false);
        state[3] = FcdSnapshot.pack(FcdSnapshot.NOTCHES, true, false);
        state[4] = FcdSnapshot.pack(0, false, true);
        state[5] = FcdSnapshot.pack(1, false, false);
        int strings = cells.map().lprmStrings().size();
        return FcdSnapshot.of(false, 6, cells, state, new byte[strings * 4], new byte[strings * 4]);
    }

    @GameTest(template = "empty")
    public static void displayIsACoreDisplayWithOneCoreInputAndNoOutputs(GameTestHelper helper) {
        ComponentType type = ComponentTypes.LED_ROD_DISPLAY;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("led_rod_display"), "registered as led_rod_display");
        helper.assertTrue(BwrItems.LED_ROD_DISPLAY.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> in = type.inputPorts(type.createData()).stream().map(PortSpec::key).toList();
        helper.assertTrue(in.equals(List.of("core")) && type.outputPorts(type.createData()).isEmpty() && !type.isInteractive(),
                "one core input like the full core display, no outputs, nothing to operate: " + in);
        List<Footprint> sizes = type.footprints();
        helper.assertTrue(type.realSizes() == 3 && sizes.get(type.realDefaultIndex()).equals(Footprint.of(34, 34))
                && sizes.contains(Footprint.of(17, 17)) && sizes.contains(Footprint.of(26, 26)),
                "a new item starts at twice the real size, 34 x 34, with the exact 17 x 17 and 26 x 26 listed: " + sizes);
        for (Footprint size : sizes) {
            helper.assertTrue(size.w() <= 64 && size.h() <= 64, "within the 4 block span of an ordinary type: " + size);
        }
        CoreRodMap reference = FcdSnapshot.referenceCells().map();
        int[] needed = LedRodDisplayData.needed(reference, 1);
        helper.assertTrue(needed[0] <= 17 && needed[1] <= 17, "the reference core needs " + needed[0] + " x " + needed[1] + " at one cell a rod");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theLedsShowThePositionPatternOrTheFaultMap(GameTestHelper helper) {
        LedRodDisplayData data = new LedRodDisplayData();
        FcdSnapshot snap = snapshot();
        helper.assertTrue(data.mode.equals("position"), "the default pattern is the rod position");
        boolean[] amber = {false, true, false, false, false, true};
        boolean[] red = {false, false, true, true, false, false};
        for (int rod = 0; rod < 6; rod++) {
            helper.assertTrue(data.amber(snap, rod) == amber[rod] && data.red(snap, rod) == red[rod],
                    "position pattern, rod " + rod + ": amber " + data.amber(snap, rod) + " red " + data.red(snap, rod));
        }
        data.mode = LedRodDisplayData.FAULT;
        boolean[] accumulator = {false, false, false, false, true, false};
        boolean[] drift = {false, false, false, true, false, false};
        for (int rod = 0; rod < 6; rod++) {
            helper.assertTrue(data.amber(snap, rod) == accumulator[rod] && data.red(snap, rod) == drift[rod], "fault map, rod " + rod);
        }
        FcdSnapshot dark = FcdSnapshot.dark(FcdSnapshot.referenceCells());
        for (int rod = 0; rod < 6; rod++) {
            helper.assertTrue(!data.amber(dark, rod) && !data.red(dark, rod), "a dark (unbound) display lights nothing");
        }
        helper.assertTrue(!data.amber(snap, -1) && !data.red(snap, snap.rodCount()), "out of range lights nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSizeGivesItsTileAndTheSnapshotAndConfigSurviveSaveAndReload(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "led-save", s -> {
            widen(s);
            PanelComponent display = s.place(ComponentTypes.LED_ROD_DISPLAY, 0, 0, Footprint.of(17, 17));
            LedRodDisplayData data = (LedRodDisplayData) display.data();
            helper.assertTrue(data.tileCells == 1, "17 x 17 is one cell a rod: " + data.tileCells);
            helper.assertTrue(((LedRodDisplayData) ComponentTypes.LED_ROD_DISPLAY.createData(Footprint.of(34, 34))).tileCells == 2,
                    "a new item at its default 34 x 34 is two cells a rod");
            helper.assertTrue(LedRodDisplayData.fittingTileFor(34, 34) == 2 && LedRodDisplayData.fittingTileFor(51, 51) == 3
                    && LedRodDisplayData.fittingTileFor(64, 64) == 3 && LedRodDisplayData.fittingTileFor(26, 26) == 1
                    && LedRodDisplayData.fittingTileFor(5, 5) == 1, "a bigger component starts at a bigger tile");
            FcdSnapshot snap = snapshot();
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("LedMode", "fault");
            config.putString("Caption", "RWM ROD PATTERN");
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, display.id(), edit), "the wrench edit was applied");
            helper.assertTrue(data.mode.equals("fault") && data.caption.equals("RWM ROD PATTERN") && data.tileCells == 1, "config applied: " + data.toTag());
            // (A config edit re-reads the display's binding, and an unbound display is dark again: the snapshot comes now.)
            data.applyPatch(snap.encode());
            helper.assertTrue(data.snapshot().equals(snap) && data.amber(data.snapshot(), 4) && data.red(data.snapshot(), 3) && !data.amber(data.snapshot(), 1),
                    "the snapshot arrives as a patch (and the display is in its fault pattern now)");
            // A tile the drawing does not fit in is refused, the display left as it was.
            CompoundTag bigger = new CompoundTag();
            CompoundTag tile = new CompoundTag();
            tile.putInt("TileCells", 3);
            bigger.put("Config", tile);
            PanelOps.configure(s.player, s.origin, display.id(), bigger);
            helper.assertTrue(data.tileCells == 1, "a tile that does not fit the 17 x 17 component is refused: " + data.tileCells);
            data.applyPatch(snap.encode());
            CompoundTag saved = display.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            LedRodDisplayData again = (LedRodDisplayData) reloaded.data();
            helper.assertTrue(reloaded.type() == ComponentTypes.LED_ROD_DISPLAY && again.mode.equals("fault") && again.tileCells == 1
                    && again.snapshot().equals(snap) && again.caption.equals("RWM ROD PATTERN") && reloaded.save().equals(saved),
                    "reloads with its snapshot, pattern, tile and caption, and re-saves unchanged");
            LedRodDisplayData old = new LedRodDisplayData();
            old.load(new CompoundTag());
            helper.assertTrue(old.mode.equals("position") && old.tileCells == 1 && old.snapshot() == FcdSnapshot.DARK, "an empty tag loads as the defaults");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void onlyAReactorCanBeBoundAndTheOtherCoreDisplaysKeepTheirTiles(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "led-bind", s -> {
            widen(s);
            PanelComponent display = s.place(ComponentTypes.LED_ROD_DISPLAY, 0, 0, Footprint.of(17, 17));
            var refusal = display.type().refuseBinding(s.level, display, 0, s.origin.above(2));
            helper.assertTrue(refusal != null, "a device that is not a reactor controller is refused");
            // The full core display and the matrix still clamp their tile to 2 or more, and start where they did.
            FullCoreDisplayData fcd = new FullCoreDisplayData();
            CompoundTag tag = new CompoundTag();
            tag.putInt("TileCells", 1);
            fcd.load(tag);
            helper.assertTrue(fcd.tileCells == 2, "a full core display still has at least 2 cells a tile: " + fcd.tileCells);
        });
    }
}
