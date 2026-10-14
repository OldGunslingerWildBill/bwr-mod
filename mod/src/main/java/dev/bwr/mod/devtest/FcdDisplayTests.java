package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.CompactCoreLayout;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RodSelectMatrixData;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.reactor.CoreRodMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * The full core display and rod select matrix (spec items 3 and 7): the layout read from a real formed core, the
 * mirrored blanks around the LPRM points, the sizes and the span of up to 8 blocks and its refusals, what the
 * components show unbound, unformed and loaded from a save without the new keys, and a matrix press selecting a
 * rod through the normal output path. The layout math itself is pure ({@link FcdLayout}, {@link CoreRodMap}).
 *
 * <p>Fixture origin 79_900 / 79_900, {@code slot * 64} blocks apart in X (see {@link FcdTestSupport}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdDisplayTests {
    static final int ORIGIN = 79_900;

    private static CoreRodMap map(int width, int depth) {
        CompactCoreLayout layout = new CompactCoreLayout(width, depth);
        int[] col = new int[layout.drives().size()];
        int[] row = new int[col.length];
        for (int i = 0; i < col.length; i++) {
            col[i] = layout.drives().get(i).x();
            row[i] = layout.drives().get(i).z();
        }
        return CoreRodMap.of(col, row);
    }

    // ---- pure layout math

    @GameTest(template = "empty", batch = "fcd")
    public static void mirroringPutsTheBlanksAroundEveryLprmPoint(GameTestHelper h) {
        CoreRodMap map = map(15, 15);
        FcdLayout layout = FcdLayout.of(map, FcdLayout.Style.COLUMBIA, 3);
        h.assertTrue(map.lprmStrings().size() == 39, "39 strings");
        for (CoreRodMap.Lprm string : map.lprmStrings()) {
            h.assertTrue(layout.blanksMeetAt(string), "the four blank tiles do not meet at " + string.label());
        }
        h.assertTrue(layout.dots().size() == 39, "one yellow dot per LPRM string, none elsewhere");
        float t = layout.tile();
        for (int s = 0; s < 39; s++) {
            CoreRodMap.Lprm string = map.lprmStrings().get(s);
            FcdLayout.Spot dot = layout.dots().get(s);
            int topLeft = map.rodAt(string.leftCol(), string.topRow());
            h.assertTrue(dot.x() == layout.blockX(topLeft) + 2 * t && dot.y() == layout.blockY(topLeft) + 2 * t,
                    "the dot of " + string.label() + " sits where the four rod blocks meet");
        }
        // Every block is A B over C D (or a mirror): A diagonal to D, B beside A in the row, C below or above A.
        for (int rod = 0; rod < map.rodCount(); rod++) {
            float ax = layout.tileX(rod, FcdLayout.TILE_A);
            float ay = layout.tileY(rod, FcdLayout.TILE_A);
            h.assertTrue(layout.tileX(rod, FcdLayout.TILE_D) != ax && layout.tileY(rod, FcdLayout.TILE_D) != ay, "D is diagonal to A");
            h.assertTrue(layout.tileY(rod, FcdLayout.TILE_B) == ay && layout.tileX(rod, FcdLayout.TILE_B) != ax, "B beside A");
            h.assertTrue(layout.tileX(rod, FcdLayout.TILE_C) == ax && layout.tileY(rod, FcdLayout.TILE_C) != ay, "C above or below A");
            float bx = layout.blockX(rod);
            float by = layout.blockY(rod);
            for (int which = 0; which < 4; which++) {
                h.assertTrue(layout.tileX(rod, which) >= bx && layout.tileX(rod, which) < bx + 2 * t
                        && layout.tileY(rod, which) >= by && layout.tileY(rod, which) < by + 2 * t, "tiles stay in their block");
            }
        }
        // The smallest supported core has the same guarantees: a dot at each of its strings, blanks meeting there.
        CoreRodMap smallest = map(5, 5);
        FcdLayout smallestLayout = FcdLayout.of(smallest, FcdLayout.Style.COLUMBIA, 3);
        h.assertTrue(smallestLayout.dots().size() == smallest.lprmStrings().size(), "a dot per string, none elsewhere");
        for (CoreRodMap.Lprm string : smallest.lprmStrings()) {
            h.assertTrue(smallestLayout.blanksMeetAt(string), "blanks meet at " + string.label() + " in the smallest core");
        }
        h.assertTrue(!smallestLayout.backplate().isEmpty() && smallest.rodCount() >= 1, "the smallest core has a backplate");
        // A core with no LPRM point (one row of rods) has no dot, and uses the plain parity: an even column is A B over C D.
        CoreRodMap row = CoreRodMap.of(new int[] {0, 1, 2, 3}, new int[] {0, 0, 0, 0});
        FcdLayout rowLayout = FcdLayout.of(row, FcdLayout.Style.COLUMBIA, 3);
        h.assertTrue(row.lprmStrings().isEmpty() && rowLayout.dots().isEmpty(), "no strings, no dots");
        for (int rod = 0; rod < row.rodCount(); rod++) {
            h.assertTrue(rowLayout.blankOnLeft(rod) == (row.col(rod) % 2 == 1)
                    && rowLayout.blankOnTop(rod) == (row.row(rod) % 2 == 1), "plain parity mirroring");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void footprintIsRodsAcrossTimesTileAndFitsEightBlocks(GameTestHelper h) {
        CoreRodMap full = map(15, 15);
        for (int tile = FcdLayout.MIN_TILE; tile <= 3; tile++) {
            FcdLayout columbia = FcdLayout.of(full, FcdLayout.Style.COLUMBIA, tile);
            FcdLayout digital = FcdLayout.of(full, FcdLayout.Style.BROWNS_FERRY, tile);
            h.assertTrue(columbia.neededWidth() == (2 * 15 + 2) * tile && columbia.neededHeight() == (2 * 15 + 2) * tile,
                    "Columbia is 2 tiles a rod plus a tile of margin: " + columbia.neededWidth());
            h.assertTrue(columbia.neededWidth() <= 8 * PanelGeometry.GRID && digital.neededWidth() <= 8 * PanelGeometry.GRID
                    && digital.neededHeight() <= 8 * PanelGeometry.GRID,
                    "a 185-rod core fits 8 blocks at " + tile + " cells: " + digital.neededWidth() + " x " + digital.neededHeight());
            h.assertTrue(FcdLayout.matrixWidth(full, tile) == 17 * tile, "matrix: a button per rod and a margin");
        }
        h.assertTrue(FcdLayout.of(full, FcdLayout.Style.COLUMBIA, 3).neededWidth() == 96, "96 cells, 6 blocks");
        h.assertTrue(FcdLayout.of(full, FcdLayout.Style.COLUMBIA, 4).neededWidth() == 128, "128 cells, exactly 8 blocks");
        h.assertTrue(FcdLayout.of(full, FcdLayout.Style.COLUMBIA, 6).neededWidth() > 128, "6 cells a tile is too large for 8 blocks");
        h.assertTrue(FcdLayout.of(full, FcdLayout.Style.BROWNS_FERRY, 4).neededWidth() > 128, "Browns Ferry at 4 does not fit");
        CoreRodMap wide = map(21, 21);
        h.assertTrue(wide.rodCount() == 357, "largest core");
        h.assertTrue(FcdLayout.of(wide, FcdLayout.Style.COLUMBIA, 2).neededWidth() <= 128
                && FcdLayout.of(wide, FcdLayout.Style.COLUMBIA, 3).neededWidth() > 128, "the largest core needs 2 cells a tile");
        // The matrix hit test returns each rod at the middle of its button and nothing between or beside them.
        for (int rod = 0; rod < full.rodCount(); rod++) {
            double x = FcdLayout.buttonX(full, 3, rod) + 1.5;
            double y = FcdLayout.buttonY(full, 3, rod) + 1.5;
            h.assertTrue(FcdLayout.buttonAt(full, 3, x, y) == rod, "button of rod " + rod);
        }
        h.assertTrue(FcdLayout.buttonAt(full, 3, 0.5, 0.5) == -1 && FcdLayout.buttonAt(full, 3, -4, 10) == -1
                && FcdLayout.buttonAt(full, 3, 500, 500) == -1, "off the buttons");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void everyDisplaySizeIsWithinTheTypesSpanAndOtherTypesKeepFour(GameTestHelper h) {
        for (ComponentType type : new ComponentType[] {ComponentTypes.FULL_CORE_DISPLAY, ComponentTypes.ROD_SELECT_MATRIX}) {
            h.assertTrue(type.maxSpanBlocks() == 8, type.id() + " spans up to 8 blocks");
            for (Footprint footprint : type.footprints()) {
                h.assertTrue(footprint.w() <= type.maxSpanBlocks() * PanelGeometry.GRID
                        && footprint.h() <= type.maxSpanBlocks() * PanelGeometry.GRID, type.id() + " " + footprint);
            }
        }
        for (ComponentType type : ComponentTypes.all()) {
            if (type != ComponentTypes.FULL_CORE_DISPLAY && type != ComponentTypes.ROD_SELECT_MATRIX) {
                h.assertTrue(type.maxSpanBlocks() == PanelGeometry.MAX_SPAN_BLOCKS, type.id() + " keeps the limit of 4 blocks");
                for (Footprint footprint : type.footprints()) {
                    h.assertTrue(footprint.w() <= 4 * PanelGeometry.GRID && footprint.h() <= 4 * PanelGeometry.GRID,
                            type.id() + " " + footprint + " exceeds 4 x 4 blocks");
                }
            }
        }
        h.succeed();
    }

    // ---- placement and span, on a real wall

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 100)
    public static void spanOfEightBlocksOnlyForTheCoreDisplays(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 0, 9, 9, run -> {
            run.wall(8, 8);
            BlockPos anchor = run.wallAt(0, 0);
            Footprint big = Footprint.of(128, 128);
            h.assertTrue(PanelGroup.checkPlacement(run.level, anchor, 0, 0, big).result() == PanelGroup.Result.TOO_LARGE,
                    "8 blocks is too large for the default span of 4");
            h.assertTrue(PanelGroup.checkPlacement(run.level, anchor, 0, 0, big, 8).ok(), "8 blocks fits where the type allows 8");
            h.assertTrue(PanelGroup.checkPlacement(run.level, anchor, 8, 0, big, 8).result() == PanelGroup.Result.TOO_LARGE,
                    "starting half way along the wall, 9 blocks is too many");
            h.assertTrue(PanelOps.placeDirect(run.level, anchor, 0, 0, ComponentTypes.LAMP, Footprint.of(80, 80)) == null,
                    "a lamp may not take 5 blocks");
            PanelComponent display = PanelOps.placeDirect(run.level, anchor, 0, 0, ComponentTypes.FULL_CORE_DISPLAY, big);
            h.assertTrue(display != null && display.w() == 128 && display.h() == 128, "a display takes 8 x 8 blocks");
            // It saves and loads at that size, and its claims cover the whole wall.
            CompoundTag saved = display.save();
            PanelComponent loaded = PanelComponent.load(saved);
            h.assertTrue(loaded.w() == 128 && loaded.h() == 128, "the size survives a save");
            h.assertTrue(run.panel(7, 7).claimAt(15, 15) != null && run.panel(7, 7).claimAt(15, 15).id() == display.id(),
                    "the far corner block holds a claim");
            h.assertTrue(PanelOps.placeDirect(run.level, run.wallAt(3, 3), 0, 0, ComponentTypes.LAMP, Footprint.of(4, 4)) == null,
                    "the wall under the display is taken");
        });
    }

    // ---- layout from a real formed core, and the three states

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void layoutFromARealCoreMatchesItsRodsAndCoordinates(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 1, 15, 15, run -> {
            run.wall(6, 6);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent display = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(96, 96));
            h.assertTrue(display != null, "96 x 96 display placed");
            FullCoreDisplayData data = (FullCoreDisplayData) display.data();
            // Unbound: dark, drawing the reference core, and it fits the size it was placed at.
            h.assertTrue(!data.snapshot().formed() && data.displayCells().rodCount() == 185 && data.fits(96, 96),
                    "an unbound display shows the dark reference layout");
            h.assertTrue(run.bindReactor(block, display, run.be.getBlockPos()).result() == WiringOps.Result.OK, "bound");
            FcdSnapshot snap = data.snapshot();
            h.assertTrue(snap.formed() && snap.rodCount() == 185, "the snapshot has the 185 rods of the core");
            CompactCoreLayout layout = new CompactCoreLayout(15, 15);
            for (int r = 0; r < 185; r++) {
                h.assertTrue(snap.cells().map().col(r) == layout.drives().get(r).x() && snap.cells().map().row(r) == layout.drives().get(r).z(),
                        "rod " + (r + 1) + " is at the cell of its drive");
            }
            h.assertTrue(snap.cells().map().lprmStrings().size() == run.be.coreMap().lprmStrings().size(), "the same LPRM strings");
            int[] needed = data.neededCells();
            h.assertTrue(needed[0] == 96 && needed[1] == 96, "Columbia at 3 cells a tile is 96 x 96, got " + needed[0]);
            for (int r = 0; r < 185; r++) {
                h.assertTrue(snap.notch(r) == 0 && snap.fullIn(r) && !snap.fullOut(r) && !snap.drift(r) && !snap.scramLamp(r)
                        && !snap.accumulatorTrouble(r), "a cold core: every rod fully in, nothing else lit");
            }
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void spanRefusalNamesTheNeededSize(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 2, 15, 15, run -> {
            run.wall(8, 4);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent small = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(64, 64));
            h.assertTrue(small != null, "64 x 64 display placed");
            WiringOps.Outcome refused = run.bindReactor(block, small, run.be.getBlockPos());
            h.assertTrue(refused.result() == WiringOps.Result.REFUSED && refused.detail() != null, "a 64 x 64 display cannot show 185 rods");
            String text = dev.bwr.mod.text.BwrText.english(refused.detail());
            h.assertTrue(text.contains("96 x 96") && text.contains("6 x 6 blocks"), "the refusal names the needed size: " + text);
            h.assertTrue(small.source(0) instanceof PlantSource == false, "nothing was bound");
            h.assertTrue(!((FullCoreDisplayData) small.data()).snapshot().formed(), "and nothing is shown");
            // The matrix needs 51 x 51 cells for 185 rods: a 48 x 48 one is refused too.
            PanelBlockEntity matrixBlock = run.panel(4, 0);
            PanelComponent matrix = PanelOps.placeDirect(run.level, run.wallAt(4, 0), 0, 0, ComponentTypes.ROD_SELECT_MATRIX,
                    Footprint.of(48, 48));
            h.assertTrue(matrix != null, "48 x 48 matrix placed");
            WiringOps.Outcome matrixRefused = run.bindReactor(matrixBlock, matrix, run.be.getBlockPos());
            String matrixText = matrixRefused.detail() == null ? "" : dev.bwr.mod.text.BwrText.english(matrixRefused.detail());
            h.assertTrue(matrixRefused.result() == WiringOps.Result.REFUSED && matrixText.contains("51 x 51")
                    && matrixText.contains("4 x 4 blocks"), "the matrix refusal names its size: " + matrixText);
            // A device that is not a reactor is refused with its own message.
            BlockPos valve = run.min.offset(-3, 2, 4);
            var msiv = dev.bwr.mod.registry.BwrBlocks.MSIV.get();
            var state = msiv.placementState().setValue(dev.bwr.mod.steam.MainSteamIsolationValveBlock.FACING,
                    net.minecraft.core.Direction.NORTH);
            run.level.setBlock(valve, state, 3);
            msiv.setPlacedBy(run.level, valve, state, null, new net.minecraft.world.item.ItemStack(msiv));
            PanelComponent roomy = PanelOps.placeDirect(run.level, run.wallAt(4, 3), 0, 0, ComponentTypes.ROD_SELECT_MATRIX,
                    Footprint.of(16, 16));
            h.assertTrue(roomy != null, "a small matrix for the wrong device");
            WiringOps.Outcome wrong = run.bindReactor(run.panel(4, 3), roomy, valve, "position");
            String wrongText = wrong.detail() == null ? "" : dev.bwr.mod.text.BwrText.english(wrong.detail());
            h.assertTrue(wrong.result() == WiringOps.Result.REFUSED && wrongText.contains("reactor"), "not a reactor: " + wrongText);
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void configAndStyleRefusedWhenTheyDoNotFit(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 3, 15, 15, run -> {
            run.wall(6, 6);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent display = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(96, 96));
            h.assertTrue(run.bindReactor(block, display, run.be.getBlockPos()).result() == WiringOps.Result.OK, "bound at 96 x 96");
            FullCoreDisplayData data = (FullCoreDisplayData) display.data();
            h.assertTrue(configure(run, block, display, "TileCells", 4) && data.tileCells == 3, "4 cells a tile needs 128: refused, stays 3");
            h.assertTrue(configureStyle(run, block, display, "browns_ferry") && data.style.equals("columbia"),
                    "the Browns Ferry style is wider than 96 cells: refused");
            h.assertTrue(configure(run, block, display, "TileCells", 2) && data.tileCells == 2, "2 cells a tile fits: accepted");
            h.assertTrue(data.neededCells()[0] == 64, "needs 64 now");
        });
    }

    private static boolean configure(FcdTestSupport.Run run, PanelBlockEntity block, PanelComponent component, String key, int value) {
        run.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(dev.bwr.mod.registry.BwrItems.PANEL_WRENCH.get()));
        CompoundTag config = new CompoundTag();
        config.putInt(key, value);
        CompoundTag edit = new CompoundTag();
        edit.put("Config", config);
        return PanelOps.configure(run.player, block.getBlockPos(), component.id(), edit);
    }

    private static boolean configureStyle(FcdTestSupport.Run run, PanelBlockEntity block, PanelComponent component, String style) {
        run.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(dev.bwr.mod.registry.BwrItems.PANEL_WRENCH.get()));
        CompoundTag config = new CompoundTag();
        config.putString(FullCoreDisplayData.KEY_STYLE, style);
        CompoundTag edit = new CompoundTag();
        edit.put("Config", config);
        return PanelOps.configure(run.player, block.getBlockPos(), component.id(), edit);
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void unboundUnformedAndOldSaves(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 4, 15, 15, run -> {
            run.wall(6, 6);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent display = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(96, 96));
            FullCoreDisplayData data = (FullCoreDisplayData) display.data();
            h.assertTrue(run.bindReactor(block, display, run.be.getBlockPos()).result() == WiringOps.Result.OK, "bound");
            h.assertTrue(data.snapshot().formed(), "formed");
            // Saved and loaded again, the snapshot comes back identical.
            PanelComponent copy = PanelComponent.load(display.save());
            h.assertTrue(((FullCoreDisplayData) copy.data()).snapshot().equals(data.snapshot()), "the snapshot round trips");
            // Unformed: every lamp dark, the tiles stay.
            run.level.removeBlock(run.be.structure().crdPositions().get(0), false);
            run.then(5, () -> {
                FcdRodTests.revalidate(run.be);
                block.resample(display, true);
                FcdSnapshot dark = data.snapshot();
                h.assertTrue(!dark.formed() && dark.rodCount() == 185, "unformed: dark, still 185 tiles");
                h.assertTrue(dark.lprmStrings() == 39 && dark.lprmLamp(0, 0) == FcdSnapshot.LAMP_OFF, "LPRM lamps dark");
                // Binding gone: dark too.
                display.setSource(0, dev.bwr.mod.panel.component.InputSources.NONE);
                block.resample(display, true);
                h.assertTrue(!data.snapshot().formed(), "no binding: dark");
                // Saves from before the feature: no keys at all, unknown style, wild numbers, junk bytes.
                CompoundTag old = display.save();
                CompoundTag oldData = new CompoundTag();
                old.put("Data", oldData);
                FullCoreDisplayData loadedOld = (FullCoreDisplayData) PanelComponent.load(old).data();
                h.assertTrue(loadedOld.tileCells == 3 && loadedOld.style.equals("columbia") && !loadedOld.snapshot().formed(),
                        "an empty data compound loads the defaults");
                oldData.putString(FullCoreDisplayData.KEY_STYLE, "bogus");
                oldData.putInt("TileCells", 99);
                oldData.putByteArray("Snap", new byte[] {1, 2, 3});
                oldData.putDouble("AccumFraction", Double.NaN);
                loadedOld = (FullCoreDisplayData) PanelComponent.load(old).data();
                h.assertTrue(loadedOld.style.equals("columbia") && loadedOld.tileCells == 6 && !loadedOld.snapshot().formed()
                        && loadedOld.accumFraction == 0.9, "bad values fall back or clamp, junk bytes are ignored");
            });
        });
    }

    // ---- the matrix

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void matrixPressSelectsTheRodThroughTheOutputSink(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 5, 9, 9, run -> {
            run.wall(4, 4);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent matrix = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.ROD_SELECT_MATRIX,
                    Footprint.of(64, 64));
            h.assertTrue(matrix != null, "matrix placed");
            RodSelectMatrixData data = (RodSelectMatrixData) matrix.data();
            h.assertTrue(run.bindReactor(block, matrix, run.be.getBlockPos()).result() == WiringOps.Result.OK, "bound");
            matrix.addSink(0, new PlantSink(run.be.getBlockPos(), "select_rod", 1.0));
            int rods = run.be.core().getControlRodCount();
            int[] needed = data.neededCells();
            h.assertTrue(needed[0] <= 64 && needed[1] <= 64, "fits at 3 cells a button: " + needed[0]);
            // The click maps a cell to a rod and a miss to none.
            ComponentType type = matrix.type();
            double cx = CoreDisplayData.centre(64, needed[0]) + FcdLayout.buttonX(data.displayMap(), 3, 6) + 1.5;
            double cy = CoreDisplayData.centre(64, needed[1]) + FcdLayout.buttonY(data.displayMap(), 3, 6) + 1.5;
            h.assertTrue(type.clickValue(matrix, cx, cy) == 7.0, "the button of rod index 6 is rod 7");
            h.assertTrue(type.clickValue(matrix, 0.2, 0.2) == 0.0, "the margin is no button");
            // Bad presses are refused and change nothing.
            h.assertTrue(!press(run, block, matrix, 0) && !press(run, block, matrix, rods + 1) && !press(run, block, matrix, -3),
                    "a press must name a rod of the core");
            h.assertTrue(run.be.selectedRod() == 0 && matrix.output(0) == 0.0, "nothing selected");
            h.assertTrue(press(run, block, matrix, 7) && run.be.selectedRod() == 7 && matrix.output(0) == 7.0, "a press selects rod 7");
            // Every press is a command: select another rod from outside, press 7 again.
            h.assertTrue(run.be.selectRod(3) && run.be.selectedRod() == 3, "selected from CC");
            h.assertTrue(press(run, block, matrix, 7) && run.be.selectedRod() == 7, "the second press of the same rod still selects it");
            // The output is saved and comes back.
            RodSelectMatrixData reloaded = (RodSelectMatrixData) PanelComponent.load(matrix.save()).data();
            h.assertTrue(reloaded.lastPressed == 7, "the last press is saved");
            // Nothing moved.
            for (int r = 0; r < rods; r++) {
                h.assertTrue(run.be.rods().getCommandedNotchIndex(r) == run.be.core().getRodNotchIndex(r), "selecting moves no rod");
            }
        });
    }

    // ---- default tile sizes: the largest at which the reference core fits the 8 block span

    @GameTest(template = "empty", batch = "fcd")
    public static void defaultTileSizesAreTheLargestThatFillTheEightBlockSpan(GameTestHelper h) {
        CoreRodMap reference = FcdSnapshot.referenceCells().map();
        h.assertTrue(reference.rodCount() == 185, "the reference core has 185 rods");
        int columbia = FcdLayout.defaultTile(FcdLayout.Style.COLUMBIA);
        int browns = FcdLayout.defaultTile(FcdLayout.Style.BROWNS_FERRY);
        int matrix = FcdLayout.defaultMatrixTile();
        h.assertTrue(columbia == 4 && FcdLayout.of(reference, FcdLayout.Style.COLUMBIA, 4).fits(128, 128)
                && !FcdLayout.of(reference, FcdLayout.Style.COLUMBIA, 5).fits(128, 128), "Columbia: 4 fits 128 x 128, 5 does not: " + columbia);
        h.assertTrue(browns == 3 && FcdLayout.of(reference, FcdLayout.Style.BROWNS_FERRY, 3).fits(128, 128)
                && !FcdLayout.of(reference, FcdLayout.Style.BROWNS_FERRY, 4).fits(128, 128), "Browns Ferry: 3 fits, 4 does not: " + browns);
        h.assertTrue(matrix == FcdLayout.MAX_TILE && FcdLayout.matrixWidth(reference, matrix) <= 128
                && FcdLayout.matrixHeight(reference, matrix) <= 128, "matrix: the largest allowed size fits: " + matrix);
        // A new display's data starts there, and the saved-without-a-key default stays the old 3.
        h.assertTrue(new FullCoreDisplayData().tileCells == 4 && new RodSelectMatrixData().tileCells == FcdLayout.MAX_TILE,
                "a new component's data starts at the style's default");
        FullCoreDisplayData browned = new FullCoreDisplayData();
        CompoundTag keyless = new CompoundTag();
        browned.load(keyless);
        h.assertTrue(browned.tileCells == 3 && ((RodSelectMatrixData) loadKeyless()).tileCells == 3,
                "a save without TileCells loads as 3, as before");
        CompoundTag chosen = new CompoundTag();
        chosen.putInt("TileCells", 5);
        browned.load(chosen);
        h.assertTrue(browned.tileCells == 5, "a saved size is kept as saved");
        // Placed with a footprint, the default is the largest the footprint holds, never below the old 3.
        h.assertTrue(tileAt(ComponentTypes.FULL_CORE_DISPLAY, 128, 128) == 4 && tileAt(ComponentTypes.FULL_CORE_DISPLAY, 96, 96) == 3
                && tileAt(ComponentTypes.FULL_CORE_DISPLAY, 112, 112) == 3 && tileAt(ComponentTypes.FULL_CORE_DISPLAY, 48, 48) == 3,
                "display: 128 holds 4, smaller footprints keep 3");
        h.assertTrue(tileAt(ComponentTypes.ROD_SELECT_MATRIX, 128, 128) == 6 && tileAt(ComponentTypes.ROD_SELECT_MATRIX, 112, 112) == 6
                && tileAt(ComponentTypes.ROD_SELECT_MATRIX, 80, 80) == 4 && tileAt(ComponentTypes.ROD_SELECT_MATRIX, 64, 64) == 3
                && tileAt(ComponentTypes.ROD_SELECT_MATRIX, 16, 16) == 3, "matrix: the largest the footprint holds, at least 3");
        h.succeed();
    }

    private static CoreDisplayData loadKeyless() {
        RodSelectMatrixData data = new RodSelectMatrixData();
        data.load(new CompoundTag());
        return data;
    }

    private static int tileAt(ComponentType type, int w, int h) {
        return ((CoreDisplayData) new PanelComponent(1, type, 0, 0, Footprint.of(w, h), "T-1").data()).tileCells;
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 200)
    public static void switchingTheLookKeepsAChosenTileAndMovesADefaultOne(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 6, 9, 9, run -> {
            run.wall(8, 8);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent display = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.FULL_CORE_DISPLAY,
                    Footprint.of(128, 128));
            FullCoreDisplayData data = (FullCoreDisplayData) display.data();
            h.assertTrue(data.tileCells == 4 && data.style.equals("columbia"), "placed at 128 x 128: Columbia, 4 cells");
            h.assertTrue(configureStyle(run, block, display, "browns_ferry") && data.style.equals("browns_ferry") && data.tileCells == 3,
                    "the default follows the new look: 3 cells for Browns Ferry, which fits");
            h.assertTrue(data.fits(128, 128), "and it fits the component");
            h.assertTrue(configureStyle(run, block, display, "columbia") && data.style.equals("columbia") && data.tileCells == 4,
                    "and back: 4 cells for Columbia");
            h.assertTrue(configure(run, block, display, "TileCells", 2) && data.tileCells == 2, "a player's choice of 2 cells");
            h.assertTrue(configureStyle(run, block, display, "browns_ferry") && data.style.equals("browns_ferry") && data.tileCells == 2,
                    "a chosen size is not touched by a change of look");
        });
    }

    private static boolean press(FcdTestSupport.Run run, PanelBlockEntity block, PanelComponent component, double value) {
        return PanelOps.operate(run.player, block.getBlockPos(), component.id(), OperateAction.PRESS, value);
    }
}
