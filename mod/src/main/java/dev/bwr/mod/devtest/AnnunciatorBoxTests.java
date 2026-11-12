package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.AnnunciatorBoxes;
import dev.bwr.mod.panel.client.ComponentModel;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.client.RenderCache;
import dev.bwr.mod.panel.component.AnnunciatorControlData;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.AnnunciatorLayout;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.TextFade;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The annunciator box restyle and the distance fade of panel text: the edge detection that makes touching windows one
 * grouped box ({@link AnnunciatorBox}, a pure function), how it is read from a panel group, the four line legend layout
 * ({@link AnnunciatorLayout}), the window colours, the annunciator control module's momentary outputs and what they do,
 * and the fade function ({@link TextFade}) with its no-flicker blend.
 *
 * <p>World fixtures at 142_300 / 143_700 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AnnunciatorBoxTests {
    private AnnunciatorBoxTests() {
    }

    static final int ORIGIN_X = 142_300;
    static final int ORIGIN_Z = 143_700;

    private static AnnunciatorBox.Rect r(int x, int y, int w, int h) {
        return new AnnunciatorBox.Rect(x, y, w, h);
    }

    private static boolean has(int word, int bits) {
        return (word & bits) == bits;
    }

    // ---- edge detection (pure)

    @GameTest(template = "empty")
    public static void aLoneWindowHasNoJoinedSides(GameTestHelper helper) {
        int[] edges = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(5, 5, 12, 8)});
        helper.assertTrue(edges.length == 1 && edges[0] == 0, "a lone window keeps its whole frame: " + edges[0]);
        helper.assertTrue(AnnunciatorBox.edges(new AnnunciatorBox.Rect[0]).length == 0, "no windows, no edges");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBlockOfRealSizeWindowsIsOneBoxWithAFrameOnlyOnTheOutside(GameTestHelper helper) {
        // 4 x 3 windows of 1 x 1 cell, touching, at an offset that straddles a block boundary (columns 14..17).
        AnnunciatorBox.Rect[] rects = new AnnunciatorBox.Rect[12];
        for (int i = 0; i < 12; i++) {
            rects[i] = r(14 + i % 4, 3 + i / 4, 1, 1);
        }
        int[] edges = AnnunciatorBox.edges(rects);
        for (int i = 0; i < 12; i++) {
            int column = i % 4;
            int row = i / 4;
            int expect = (column > 0 ? AnnunciatorBox.LEFT : 0) | (column < 3 ? AnnunciatorBox.RIGHT : 0)
                    | (row > 0 ? AnnunciatorBox.TOP : 0) | (row < 2 ? AnnunciatorBox.BOTTOM : 0);
            helper.assertTrue(edges[i] == expect, "window " + column + "," + row + " joins " + Integer.toBinaryString(expect)
                    + " but reads " + Integer.toBinaryString(edges[i]));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGapOrAnotherPartBreaksTheBoxAndAnLShapeHasAnInsideCorner(GameTestHelper helper) {
        // Two windows with a one cell gap are two boxes.
        int[] apart = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(0, 0, 2, 2), r(3, 0, 2, 2)});
        helper.assertTrue(apart[0] == 0 && apart[1] == 0, "a gap of one cell is not a box: " + apart[0] + "," + apart[1]);
        // An L: the corner window touches the one on its right and the one below, and no window is diagonal to it.
        int[] l = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(0, 0, 1, 1), r(1, 0, 1, 1), r(0, 1, 1, 1)});
        helper.assertTrue(l[0] == (AnnunciatorBox.RIGHT | AnnunciatorBox.BOTTOM | AnnunciatorBox.NOTCH_BOTTOM_RIGHT),
                "the corner of the L has an inside corner: " + l[0]);
        helper.assertTrue(l[1] == AnnunciatorBox.LEFT && l[2] == AnnunciatorBox.TOP, "the arms of the L join the corner only");
        // Fill the diagonal and the inside corner is gone.
        int[] square = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(0, 0, 1, 1), r(1, 0, 1, 1), r(0, 1, 1, 1), r(1, 1, 1, 1)});
        helper.assertTrue(square[0] == (AnnunciatorBox.RIGHT | AnnunciatorBox.BOTTOM), "a filled square has no inside corner: " + square[0]);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSideIsJoinedOnlyWhereTheWholeSideIsCoveredAndSizesMix(GameTestHelper helper) {
        // A big window with two small ones along its right side: half covered is not joined, fully covered is.
        int[] half = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(0, 0, 4, 4), r(4, 0, 2, 2)});
        helper.assertTrue(!has(half[0], AnnunciatorBox.RIGHT) && has(half[1], AnnunciatorBox.LEFT) && !has(half[1], AnnunciatorBox.BOTTOM),
                "a window half covered along its side keeps its frame there: " + half[0] + "," + half[1]);
        int[] full = AnnunciatorBox.edges(new AnnunciatorBox.Rect[] {r(0, 0, 4, 4), r(4, 0, 2, 2), r(4, 2, 2, 2)});
        helper.assertTrue(has(full[0], AnnunciatorBox.RIGHT) && has(full[1], AnnunciatorBox.LEFT | AnnunciatorBox.BOTTOM)
                && has(full[2], AnnunciatorBox.LEFT | AnnunciatorBox.TOP), "two small windows cover the big one's side: " + full[0]);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insetsLeaveAHairGapBetweenWindowsAndTheFrameOnTheOutside(GameTestHelper helper) {
        for (float[] size : new float[][] {{1, 1}, {2, 2}, {12, 8}, {16, 12}}) {
            float w = size[0];
            float h = size[1];
            float[] lone = AnnunciatorBox.insets(0, w, h);
            float[] joined = AnnunciatorBox.insets(AnnunciatorBox.SIDES, w, h);
            for (int side = 0; side < 4; side++) {
                helper.assertTrue(lone[side] > joined[side], w + "x" + h + ": the frame is wider than half the gap on side " + side);
                helper.assertTrue(Math.abs(lone[side] - AnnunciatorBox.frame(w, h)) < 1.0e-6f
                        && Math.abs(joined[side] * 2.0f - AnnunciatorBox.gap(w, h)) < 1.0e-6f, "frame and half gap as defined");
            }
            helper.assertTrue(AnnunciatorBox.gap(w, h) <= 0.5f && AnnunciatorBox.gap(w, h) >= 0.06f, w + "x" + h + ": the gap is thin");
            // The tile never closes up, however small the window.
            helper.assertTrue(w - lone[0] - lone[2] > 0.5f * w && h - lone[1] - lone[3] > 0.5f * h, w + "x" + h + ": a lone tile fills most of the window");
        }
        helper.succeed();
    }

    // ---- reading the box off a panel group

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void windowsInTwoBlocksOfOneGroupAreOneBoxAndTheMeshFollowsIt(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "ab-group", s -> {
            BlockPos other = s.origin.east();
            s.level.setBlock(other, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, HardwareStage.FACING), 3);
            PanelGroup group = s.be().group();
            helper.assertTrue(group != null && group.members().size() == 2, "the two blocks are one group");
            boolean originIsLeft = group.columnOf(s.origin) < group.columnOf(other);
            BlockPos left = originIsLeft ? s.origin : other;
            BlockPos right = originIsLeft ? other : s.origin;
            PanelComponent a = PanelOps.placeDirect(s.level, left, 15, 4, ComponentTypes.ANNUNCIATOR, Footprint.of(1, 1));
            PanelComponent b = PanelOps.placeDirect(s.level, right, 0, 4, ComponentTypes.ANNUNCIATOR, Footprint.of(1, 1));
            PanelComponent below = PanelOps.placeDirect(s.level, left, 15, 5, ComponentTypes.ANNUNCIATOR, Footprint.of(1, 1));
            helper.assertTrue(a != null && b != null && below != null, "windows placed");
            ComponentModel model = ComponentModels.get(ComponentTypes.ANNUNCIATOR.id());
            PanelBlockEntity leftBe = (PanelBlockEntity) s.level.getBlockEntity(left);
            AnnunciatorBoxes.refresh(leftBe, 1.0f);
            RenderCache ca = RenderCache.of(a);
            RenderCache cb = RenderCache.of(b);
            RenderCache cBelow = RenderCache.of(below);
            helper.assertTrue(has(ca.boxEdges, AnnunciatorBox.RIGHT | AnnunciatorBox.BOTTOM) && !has(ca.boxEdges, AnnunciatorBox.LEFT),
                    "a window at the right edge of a block joins the one in the next block: " + ca.boxEdges);
            helper.assertTrue(cb.boxEdges == AnnunciatorBox.LEFT, "and it joins back: " + cb.boxEdges);
            helper.assertTrue(cBelow.boxEdges == AnnunciatorBox.TOP, "the window below joins upwards only: " + cBelow.boxEdges);
            long before = model.staticKey(a);
            // Remove the neighbour: the next scan of the group drops the joined side, and the mesh key follows.
            s.wrench();
            helper.assertTrue(PanelOps.remove(s.player, right, b.id()), "the neighbour is removed");
            AnnunciatorBoxes.refresh(leftBe, 2.0f);
            helper.assertTrue(!has(ca.boxEdges, AnnunciatorBox.RIGHT) && model.staticKey(a) != before,
                    "with the neighbour gone the side is open again and the mesh is rebuilt: " + ca.boxEdges);
        });
    }

    // ---- the legend sits on its window

    /**
     * The owner's report: legends hovered in front of the tiles. The legend's elevation must be a hair above the face it is
     * printed on, at every window size (the small ones scale their elevation, the text must scale with it) and on every
     * panel frame (vertical, sloped, the duplex header): measured as the world distance between the point of the legend and
     * the point of the lit face above the same cell, through the real model layout and the real frames.
     */
    @GameTest(template = "empty")
    public static void legendsSitAHairAboveTheirWindowAtEverySizeAndOnEveryPanelFrame(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        double epsilonBlocks = AnnunciatorBox.TEXT_EPSILON / 16.0 + 1.0e-9;
        ComponentModel model = ComponentModels.get(ComponentTypes.ANNUNCIATOR.id());
        int checked = 0;
        for (dev.bwr.mod.panel.PanelType type : dev.bwr.mod.panel.PanelType.values()) {
            var frame = dev.bwr.mod.panel.PanelGeometry.frame(type, net.minecraft.core.Direction.NORTH);
            for (Footprint size : ComponentTypes.ANNUNCIATOR.footprints()) {
                PanelComponent window = new PanelComponent(1, ComponentTypes.ANNUNCIATOR, 0, 0, size, "");
                AnnunciatorData data = (AnnunciatorData) window.data();
                data.line1 = "RPV";
                data.line2 = "LEVEL";
                data.line3 = "LOW";
                float factor = dev.bwr.mod.panel.model.Placement.elevationFactor(size.w(), size.h());
                float faceZ = dev.bwr.mod.panel.model.Placement.fit(size.w(), size.h(), size.w(), size.h(), (float) frame.vLength)
                        .withElevation(factor).z(AnnunciatorBox.LIT_Z);
                for (var item : model.textLayout(window, (float) frame.vLength)) {
                    double u = item.x();
                    double v = item.y();
                    double dx = frame.x(u, v, item.z()) - frame.x(u, v, faceZ);
                    double dy = frame.y(u, v, item.z()) - frame.y(u, v, faceZ);
                    double dz = frame.z(u, v, item.z()) - frame.z(u, v, faceZ);
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    boolean above = item.z() > faceZ;
                    checked++;
                    if (!above || distance > epsilonBlocks * factor) {
                        failures.add(type + " " + size + ": legend " + item.text() + " stands " + (above ? "" : "BELOW and ")
                                + distance + " blocks from the face (at most " + epsilonBlocks * factor + ")");
                    }
                }
            }
        }
        helper.assertTrue(checked >= 7 * 4 * 3, "the loop looked at every size and frame: " + checked);
        helper.assertTrue(failures.isEmpty(), failures.size() + " legends float: " + failures.subList(0, Math.min(5, failures.size())));
        // The legend also clears the flag and the face under it, and lies under the bezel pieces.
        helper.assertTrue(AnnunciatorBox.TEXT_Z > AnnunciatorBox.LIT_Z && AnnunciatorBox.TEXT_Z > AnnunciatorBox.FLAG_Z
                && AnnunciatorBox.TEXT_Z < AnnunciatorBox.LIP_Z, "the legend is on top of the face and flag, under the bezel lip");
        helper.succeed();
    }

    // ---- legend layout (pure)

    private static final int[][] LINE_WIDTHS = {{30}, {30, 22}, {66, 40, 12}, {66, 66, 66, 66}, {6, 6, 6, 6}, {1}, {72, 5, 61, 44}};

    @GameTest(template = "empty")
    public static void legendsOfOneToFourLinesFitCentredInTheTileAtEverySize(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        int[][] sizes = {{1, 1}, {2, 2}, {12, 8}, {16, 12}, {4, 3}, {1, 2}, {3, 1}};
        for (int[] size : sizes) {
            for (int edges : new int[] {0, AnnunciatorBox.SIDES, AnnunciatorBox.LEFT | AnnunciatorBox.TOP}) {
                for (float aspect : new float[] {1.0f, 1.41f}) {
                    float[] in = AnnunciatorBox.insets(edges, size[0], size[1]);
                    for (int[] widths : LINE_WIDTHS) {
                        AnnunciatorLayout.Fit fit = AnnunciatorLayout.fit(size[0], size[1], in, widths, aspect);
                        String at = size[0] + "x" + size[1] + " edges " + edges + " aspect " + aspect + " lines " + widths.length;
                        if (fit.lines() != widths.length || !(fit.scale() > 0.0f)) {
                            failures.add(at + ": " + fit.lines() + " lines at scale " + fit.scale());
                            continue;
                        }
                        float tileL = in[0];
                        float tileR = size[0] - in[2];
                        float tileT = in[1];
                        float tileB = size[1] - in[3];
                        float eps = 1.0e-4f;
                        for (int i = 0; i < fit.lines(); i++) {
                            float left = fit.x()[i];
                            float right = left + widths[i] * fit.scale();
                            float top = fit.y()[i];
                            float bottom = top + AnnunciatorLayout.LINE_PIXELS * fit.scale() * aspect;
                            if (left < tileL - eps || right > tileR + eps || top < tileT - eps || bottom > tileB + eps) {
                                failures.add(at + ": line " + i + " [" + left + ".." + right + ", " + top + ".." + bottom
                                        + "] leaves the tile [" + tileL + ".." + tileR + ", " + tileT + ".." + tileB + "]");
                            }
                            if (Math.abs((left + right) / 2.0f - (tileL + tileR) / 2.0f) > eps) {
                                failures.add(at + ": line " + i + " is not centred");
                            }
                            if (i > 0 && fit.y()[i] < fit.y()[i - 1] + fit.lineHeight() * 0.999f) {
                                failures.add(at + ": line " + i + " overlaps the line above");
                            }
                        }
                        float used = fit.y()[fit.lines() - 1] + fit.lineHeight() - fit.y()[0];
                        float freeAbove = fit.y()[0] - tileT;
                        float freeBelow = tileB - (fit.y()[0] + used);
                        if (Math.abs(freeAbove - freeBelow) > eps) {
                            failures.add(at + ": the legend is not centred down the tile");
                        }
                    }
                }
            }
        }
        helper.assertTrue(failures.isEmpty(), failures.size() + " layouts do not fit: " + failures.subList(0, Math.min(6, failures.size())));
        // More than four lines are not placed.
        helper.assertTrue(AnnunciatorLayout.fit(12, 8, AnnunciatorBox.insets(0, 12, 8), new int[] {10, 10, 10, 10, 10, 10}, 1.0f).lines()
                == AnnunciatorLayout.MAX_LINES, "at most four lines");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void windowColoursAreDarkOffAndBrightLitWithLettersThatContrast(GameTestHelper helper) {
        int white = 0xFFF6D8;
        int amber = 0xFFB000;
        int red = 0xFF4A32;
        for (int color : new int[] {white, amber, red}) {
            int off = AnnunciatorLayout.offBody(color);
            helper.assertTrue(AnnunciatorLayout.luma(off) < AnnunciatorLayout.luma(color), Integer.toHexString(color) + ": dim when off");
            float bodyLit = AnnunciatorLayout.luma(color);
            float bodyOff = AnnunciatorLayout.luma(off);
            float inkLit = AnnunciatorLayout.luma(AnnunciatorLayout.ink(color, true));
            float inkOff = AnnunciatorLayout.luma(AnnunciatorLayout.ink(color, false));
            helper.assertTrue(Math.abs(inkLit - bodyLit) > 0.3f, Integer.toHexString(color) + ": lit lettering contrasts with the lit tile");
            helper.assertTrue(Math.abs(inkOff - bodyOff) > 0.15f, Integer.toHexString(color) + ": off lettering still reads on the dim tile");
        }
        helper.assertTrue(AnnunciatorLayout.lightBody(white) && AnnunciatorLayout.lightBody(amber) && !AnnunciatorLayout.lightBody(red),
                "white and amber take dark lettering, red takes light");
        helper.assertTrue(AnnunciatorLayout.luma(AnnunciatorLayout.ink(red, true)) > AnnunciatorLayout.luma(AnnunciatorLayout.ink(red, false)),
                "red lettering is brighter lit than off");
        helper.succeed();
    }

    // ---- data

    @GameTest(template = "empty")
    public static void fourLegendLinesAreSavedOnlyWhenUsedAndOldSavesLoad(GameTestHelper helper) {
        AnnunciatorData plain = new AnnunciatorData();
        plain.line1 = "RPV LEVEL";
        plain.line2 = "LOW";
        CompoundTag tag = new CompoundTag();
        plain.save(tag);
        helper.assertTrue(!tag.contains("Line3") && !tag.contains("Line4"), "two lines save exactly what they always did: " + tag);
        AnnunciatorData four = new AnnunciatorData();
        four.line1 = "A";
        four.line3 = "C";
        four.line4 = "D";
        CompoundTag fourTag = new CompoundTag();
        four.save(fourTag);
        AnnunciatorData loaded = new AnnunciatorData();
        loaded.load(fourTag);
        helper.assertTrue(loaded.line3.equals("C") && loaded.line4.equals("D") && loaded.line2.isEmpty(), "lines 3 and 4 round trip");
        helper.assertTrue(java.util.Arrays.equals(loaded.legend(), new String[] {"A", "C", "D"}), "the legend is the non-empty lines in order");
        AnnunciatorData old = new AnnunciatorData();
        old.load(tag);
        helper.assertTrue(old.line3.isEmpty() && old.line4.isEmpty() && old.line1.equals("RPV LEVEL"), "an old save loads with empty lines 3 and 4");
        // The wrench schema takes the new lines like the others.
        CompoundTag edit = new CompoundTag();
        edit.putString("Line3", "THIRD");
        edit.putString("Line4", "x".repeat(40));
        CompoundTag merged = ConfigSchema.merge(ComponentTypes.ANNUNCIATOR.configSchema(), new CompoundTag(), edit);
        helper.assertTrue(merged.getString("Line3").equals("THIRD") && merged.getString("Line4").length() == AnnunciatorData.MAX_TEXT,
                "lines 3 and 4 are config fields, cut to the same length as the others");
        helper.succeed();
    }

    // ---- the control module

    private static PanelComponent window(HardwareStage s, int x, int y, int group) {
        PanelComponent w = s.place(ComponentTypes.ANNUNCIATOR, x, y, Footprint.of(1, 1));
        AnnunciatorData data = (AnnunciatorData) w.data();
        data.group = group;
        s.be().setInput(w, 0, 0.0);
        return w;
    }

    private static AnnunciatorLogic.Display display(PanelComponent w) {
        return ((AnnunciatorData) w.data()).logic.display();
    }

    @GameTest(template = "empty")
    public static void controlModuleIsRegisteredWithFourMomentaryOutputs(GameTestHelper helper) {
        var type = ComponentTypes.ANNUNCIATOR_CONTROL;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("annunciator_control"), "registered");
        helper.assertTrue(BwrItems.ANNUNCIATOR_CONTROL.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        var outputs = type.outputPorts(type.createData());
        helper.assertTrue(outputs.size() == 4 && outputs.get(0).key().equals("ack") && outputs.get(1).key().equals("reset")
                && outputs.get(2).key().equals("silence") && outputs.get(3).key().equals("test"), "outputs: ack, reset, silence, test");
        helper.assertTrue(type.inputPorts(type.createData()).isEmpty() && type.isInteractive() && type.holdable(), "held like a pushbutton, no inputs");
        helper.assertTrue(type.footprints().get(type.realDefaultIndex()).equals(Footprint.of(32, 8)) && type.footprints().contains(Footprint.of(2, 1)),
                "a new item is 32 x 8 cells (alpha.60 size table; the real size 2 x 1 stays on the list, retired)");
        // A click picks the quarter of the face it lands on.
        for (double width : new double[] {2, 4, 24}) {
            helper.assertTrue(AnnunciatorControlType.buttonAt(width, 0.01) == 0 && AnnunciatorControlType.buttonAt(width, width * 0.3) == 1
                    && AnnunciatorControlType.buttonAt(width, width * 0.6) == 2 && AnnunciatorControlType.buttonAt(width, width * 0.99) == 3
                    && AnnunciatorControlType.buttonAt(width, -3) == 0 && AnnunciatorControlType.buttonAt(width, width + 5) == 3,
                    "button under a click at width " + width);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlModuleAcksAndResetsItsGroupAndNothingElseHappensByItself(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "ab-module", s -> {
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(2, 1));
            PanelComponent g1 = window(s, 0, 0, 1);
            PanelComponent g1manual = window(s, 1, 0, 1);
            ((AnnunciatorData) g1manual.data()).resetMode = AnnunciatorData.RESET_MANUAL;
            ((AnnunciatorData) g1manual.data()).logic.setManualReset(true);
            PanelComponent g2 = window(s, 2, 0, 2);
            for (PanelComponent w : List.of(g1, g1manual, g2)) {
                s.be().setInput(w, 0, 1.0); // above the 0.5 setpoint: alarm
            }
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.FLASH && display(g2) == AnnunciatorLogic.Display.FLASH, "all flash");

            // Pressing with the module on its default group acknowledges group 1 only, on the press.
            helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.ACK), "ACK pressed");
            helper.assertTrue(module.output(AnnunciatorControlType.ACK) == 1.0 && module.output(1) == 0.0 && module.output(2) == 0.0
                    && module.output(3) == 0.0, "only the ACK output is high");
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.STEADY && display(g1manual) == AnnunciatorLogic.Display.STEADY
                    && display(g2) == AnnunciatorLogic.Display.FLASH, "group 1 acknowledged, group 2 untouched");
            // Holding it (the client repeats PRESS) is not another acknowledge: a window that alarms now stays flashing.
            s.be().setInput(g1, 0, 0.0);
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.OFF, "cleared and acknowledged goes out by itself");
            s.be().setInput(g1, 0, 1.0);
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.FLASH, "a new alarm flashes");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.ACK);
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.FLASH, "a repeat of the held press is not a new acknowledge");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0) && module.output(0) == 0.0,
                    "released: the output drops");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.ACK);
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.STEADY, "pressed again, acknowledged");

            // RESET: the manual window, acknowledged and cleared, stays steady until RESET.
            s.be().setInput(g1manual, 0, 0.0);
            helper.assertTrue(display(g1manual) == AnnunciatorLogic.Display.STEADY, "manual reset window stays steady when cleared");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.RESET)
                    && module.output(AnnunciatorControlType.RESET) == 1.0, "RESET pressed");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
            helper.assertTrue(display(g1manual) == AnnunciatorLogic.Display.OFF, "RESET clears it");
            helper.assertTrue(display(g1) == AnnunciatorLogic.Display.STEADY, "RESET never silences an alarm that is still there");
            helper.assertTrue(display(g2) == AnnunciatorLogic.Display.FLASH, "group 2 still alarming and unacknowledged");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void silenceAndTestDoNothingToTheAlarmSequenceAndTheModuleHoldsOneButtonAtATime(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "ab-silence", s -> {
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(4, 2));
            PanelComponent flashing = window(s, 0, 0, 1);
            PanelComponent dark = window(s, 1, 0, 1);
            s.be().setInput(flashing, 0, 1.0);
            for (int button : new int[] {AnnunciatorControlType.SILENCE, AnnunciatorControlType.TEST}) {
                helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, button)
                        && module.output(button) == 1.0, "button " + button + " held");
                helper.assertTrue(display(flashing) == AnnunciatorLogic.Display.FLASH && display(dark) == AnnunciatorLogic.Display.OFF
                        && ((AnnunciatorData) flashing.data()).logic.stateId() == AnnunciatorLogic.State.ALARM.ordinal(),
                        "button " + button + " changes no alarm state");
                helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0) && module.output(button) == 0.0,
                        "released");
            }
            // Sliding from one button to another lets the first go.
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.TEST);
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.SILENCE);
            helper.assertTrue(module.output(AnnunciatorControlType.TEST) == 0.0 && module.output(AnnunciatorControlType.SILENCE) == 1.0,
                    "only one button is held at a time");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
            // A value that is not a button is clamped to a real one, a NaN is refused.
            helper.assertTrue(!PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, Double.NaN)
                    && module.output(0) == 0.0 && module.output(1) == 0.0 && module.output(2) == 0.0 && module.output(3) == 0.0,
                    "a press with no button is refused and nothing is held");
            // TEST lights the lamps of its group on the client: the scan reads the held output.
            RenderCache cache = RenderCache.of(dark);
            AnnunciatorBoxes.refresh(s.be(), 1.0f);
            helper.assertTrue(!AnnunciatorBoxes.testing(cache, 1), "no lamp test while nothing is held");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.TEST);
            AnnunciatorBoxes.refresh(s.be(), 2.0f);
            helper.assertTrue(AnnunciatorBoxes.testing(cache, 1) && !AnnunciatorBoxes.testing(cache, 2), "TEST held: group 1 is lit, group 2 is not");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
            AnnunciatorBoxes.refresh(s.be(), 3.0f);
            helper.assertTrue(!AnnunciatorBoxes.testing(cache, 1), "released: the lamp test ends");
            helper.assertTrue(display(dark) == AnnunciatorLogic.Display.OFF, "and the window's own state was never touched");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlModuleGroupIsConfigurableAndSavedOnlyWhenNotTheDefault(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 192, ORIGIN_Z, "ab-save", s -> {
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(2, 1));
            AnnunciatorControlData data = (AnnunciatorControlData) module.data();
            helper.assertTrue(data.toTag().isEmpty(), "a module at the defaults saves nothing: " + data.toTag());
            PanelComponent g2 = window(s, 0, 0, 2);
            s.be().setInput(g2, 0, 1.0);
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putInt("AnnGroup", 2);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, module.id(), edit) && data.group == 2, "group 2 configured");
            helper.assertTrue(data.toTag().getInt("AnnGroup") == 2, "saved when not 1");
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, AnnunciatorControlType.ACK);
            PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
            helper.assertTrue(display(g2) == AnnunciatorLogic.Display.STEADY, "it acknowledges the group it is set to");
            AnnunciatorControlData loaded = new AnnunciatorControlData();
            loaded.load(new CompoundTag());
            helper.assertTrue(loaded.group == 1, "an old tag with nothing loads as group 1");
        });
    }

    // ---- the Columbia style: boxes of split tiles (a pure layout), the settings, the models and the mesh

    /** How many of {@code tiles} in box {@code box} draw the nameplate ({@code latch} false) or the latch. */
    private static int count(AnnunciatorBox.Tile[] tiles, int box, boolean latch) {
        int n = 0;
        for (AnnunciatorBox.Tile tile : tiles) {
            if (tile.box() == box && (latch ? tile.latch() : tile.nameplate())) {
                n++;
            }
        }
        return n;
    }

    @GameTest(template = "empty")
    public static void aGridOfHalfWindowsIsSplitTilesWithRowsColumnsOneNameplateAndOneLatch(GameTestHelper helper) {
        // Columbia's box: 7 tiles across and 5 down, every tile two alarms, so 7 x 10 windows of 8 x 3 cells.
        int left = 3;
        int top = 2;
        AnnunciatorBox.Rect[] rects = new AnnunciatorBox.Rect[70];
        for (int i = 0; i < 70; i++) {
            rects[i] = r(left + i % 7 * 8, top + i / 7 * 3, 8, 3);
        }
        AnnunciatorBox.Tile[] tiles = AnnunciatorBox.layout(rects);
        helper.assertTrue(tiles.length == 70, "a place for every window");
        for (int i = 0; i < 70; i++) {
            AnnunciatorBox.Tile tile = tiles[i];
            int row = i / 7;
            int column = i % 7;
            helper.assertTrue(tile.box() == 0 && tile.row() == row && tile.column() == column && tile.unit() == 8,
                    "window " + column + "," + row + " is " + tile);
            helper.assertTrue(tile.role() == (row % 2 == 0 ? AnnunciatorBox.UPPER : AnnunciatorBox.LOWER),
                    "even rows are the upper alarm of a tile, odd rows the lower: " + column + "," + row + " is " + tile.role());
            // The nameplate: the bottom window of the middle column, centred on the box. The latch: the right column, at
            // the box's mid height, which is the line between rows 4 and 5.
            helper.assertTrue(tile.nameplate() == (row == 9 && column == 3), "nameplate at " + column + "," + row + ": " + tile.nameplate());
            helper.assertTrue(tile.latch() == (row == 4 && column == 6), "latch at " + column + "," + row + ": " + tile.latch());
            if (tile.nameplate()) {
                helper.assertTrue(2 * rects[i].x() + tile.nameX2() == 2 * left + 56, "the nameplate is centred under the box: " + tile.nameX2());
            }
            if (tile.latch()) {
                helper.assertTrue(2 * rects[i].y() + tile.latchY2() == 2 * top + 30, "the latch is half way up the box: " + tile.latchY2());
            }
        }
        helper.assertTrue(count(tiles, 0, false) == 1 && count(tiles, 0, true) == 1, "exactly one nameplate and one latch");
        // The order the windows are given in does not matter.
        AnnunciatorBox.Rect[] reversed = new AnnunciatorBox.Rect[70];
        for (int i = 0; i < 70; i++) {
            reversed[i] = rects[69 - i];
        }
        AnnunciatorBox.Tile[] again = AnnunciatorBox.layout(reversed);
        for (int i = 0; i < 70; i++) {
            helper.assertTrue(again[i].equals(tiles[69 - i]), "the same layout whatever the order: " + again[i] + " / " + tiles[69 - i]);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLoneWindowAndAGridOfWholeTilesAreWholeTiles(GameTestHelper helper) {
        AnnunciatorBox.Tile[] lone = AnnunciatorBox.layout(new AnnunciatorBox.Rect[] {r(5, 5, 8, 6)});
        helper.assertTrue(lone.length == 1 && lone[0].equals(AnnunciatorBox.Tile.lone(8, 6)), "a lone window is a box by itself: " + lone[0]);
        helper.assertTrue(lone[0].role() == AnnunciatorBox.WHOLE && lone[0].nameplate() && lone[0].latch() && lone[0].nameX2() == 8
                && lone[0].latchY2() == 6 && lone[0].row() == 0 && lone[0].column() == 0, "with its own nameplate and latch, centred on it");
        // A half height window with nothing under it is a whole (flat) tile, not half of one.
        AnnunciatorBox.Tile[] flat = AnnunciatorBox.layout(new AnnunciatorBox.Rect[] {r(0, 0, 8, 3)});
        helper.assertTrue(flat[0].role() == AnnunciatorBox.WHOLE, "a lone half height window is a whole tile");
        helper.assertTrue(AnnunciatorBox.layout(new AnnunciatorBox.Rect[0]).length == 0, "no windows, no layout");
        helper.assertTrue(AnnunciatorBox.halfShaped(8, 3) && AnnunciatorBox.halfShaped(12, 4) && AnnunciatorBox.halfShaped(16, 6)
                && !AnnunciatorBox.halfShaped(8, 6) && !AnnunciatorBox.halfShaped(12, 8) && !AnnunciatorBox.halfShaped(1, 1),
                "the half height sizes are halves, the others are not");
        // Whole tiles in a grid stay whole and are numbered one row each.
        AnnunciatorBox.Rect[] rects = new AnnunciatorBox.Rect[6];
        for (int i = 0; i < 6; i++) {
            rects[i] = r(i % 3 * 8, i / 3 * 6, 8, 6);
        }
        AnnunciatorBox.Tile[] whole = AnnunciatorBox.layout(rects);
        for (int i = 0; i < 6; i++) {
            helper.assertTrue(whole[i].role() == AnnunciatorBox.WHOLE && whole[i].row() == i / 3 && whole[i].column() == i % 3,
                    "whole tile " + i + ": " + whole[i]);
        }
        helper.assertTrue(count(whole, 0, false) == 1 && count(whole, 0, true) == 1 && whole[4].nameplate() && whole[2].latch(),
                "one nameplate under the middle column, one latch in the right column");
        // The mesh key tells the halves, a whole tile and the window with the nameplate apart.
        AnnunciatorBox.Tile upper = new AnnunciatorBox.Tile(0, 0, 0, AnnunciatorBox.UPPER, 8, false, 8, false, 3);
        AnnunciatorBox.Tile lower = new AnnunciatorBox.Tile(0, 1, 0, AnnunciatorBox.LOWER, 8, false, 8, false, 3);
        AnnunciatorBox.Tile named = new AnnunciatorBox.Tile(0, 1, 0, AnnunciatorBox.LOWER, 8, true, 8, false, 3);
        AnnunciatorBox.Tile wide = new AnnunciatorBox.Tile(0, 1, 0, AnnunciatorBox.LOWER, 12, false, 8, false, 3);
        helper.assertTrue(upper.key() != lower.key() && lower.key() != named.key() && lower.key() != wide.key()
                && upper.key() >= 0 && named.key() >= 0, "the key follows the half, the nameplate and the tile width");
        helper.assertTrue(lower.key() == new AnnunciatorBox.Tile(3, 7, 2, AnnunciatorBox.LOWER, 8, false, 8, false, 3).key(),
                "and not the row and column, which only the numerals show");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void separateBoxesAreNumberedIndependently(GameTestHelper helper) {
        // Two 2 x 2 boxes of half windows a cell apart, the second a row lower, and a lone window: three boxes, numbered
        // in reading order of their first windows (the lone window starts on the top row, so it comes second).
        AnnunciatorBox.Rect[] rects = {r(0, 0, 8, 3), r(8, 0, 8, 3), r(0, 3, 8, 3), r(8, 3, 8, 3),
                r(17, 1, 8, 3), r(25, 1, 8, 3), r(17, 4, 8, 3), r(25, 4, 8, 3), r(40, 0, 12, 8)};
        AnnunciatorBox.Tile[] tiles = AnnunciatorBox.layout(rects);
        for (int i = 0; i < 8; i++) {
            int box = i < 4 ? 0 : 2;
            helper.assertTrue(tiles[i].box() == box && tiles[i].row() == i % 4 / 2 && tiles[i].column() == i % 2,
                    "window " + i + " is in box " + box + " at its own row and column: " + tiles[i]);
            helper.assertTrue(tiles[i].role() == (i % 4 < 2 ? AnnunciatorBox.UPPER : AnnunciatorBox.LOWER), "window " + i + ": " + tiles[i]);
        }
        helper.assertTrue(tiles[8].box() == 1 && tiles[8].row() == 0 && tiles[8].column() == 0 && tiles[8].unit() == 12
                && tiles[8].role() == AnnunciatorBox.WHOLE, "the lone window is a box of its own: " + tiles[8]);
        for (int box = 0; box < 3; box++) {
            helper.assertTrue(count(tiles, box, false) == 1 && count(tiles, box, true) == 1, "box " + box + " has one nameplate and one latch");
        }
        // Two windows that touch only at a corner are not one box.
        AnnunciatorBox.Tile[] corner = AnnunciatorBox.layout(new AnnunciatorBox.Rect[] {r(0, 0, 8, 3), r(8, 3, 8, 3)});
        helper.assertTrue(corner[0].box() != corner[1].box(), "a shared corner does not join two windows");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anLShapeAnOddNumberOfRowsAndMixedSizesStillLayOut(GameTestHelper helper) {
        // An L: a column of three half windows and one more beside the top one.
        AnnunciatorBox.Rect[] l = {r(0, 0, 8, 3), r(8, 0, 8, 3), r(0, 3, 8, 3), r(0, 6, 8, 3)};
        AnnunciatorBox.Tile[] tiles = AnnunciatorBox.layout(l);
        helper.assertTrue(tiles[0].role() == AnnunciatorBox.UPPER && tiles[2].role() == AnnunciatorBox.LOWER,
                "the first two of the column are one split tile: " + tiles[0] + " " + tiles[2]);
        helper.assertTrue(tiles[3].role() == AnnunciatorBox.WHOLE && tiles[1].role() == AnnunciatorBox.WHOLE,
                "the odd one out and the one with nothing under it are whole tiles: " + tiles[3] + " " + tiles[1]);
        helper.assertTrue(tiles[0].box() == 0 && tiles[1].box() == 0 && tiles[2].box() == 0 && tiles[3].box() == 0, "the L is one box");
        helper.assertTrue(tiles[3].row() == 2 && tiles[1].column() == 1 && tiles[1].row() == 0, "rows and columns of the L");
        helper.assertTrue(count(tiles, 0, false) == 1 && count(tiles, 0, true) == 1 && tiles[3].nameplate() && tiles[1].latch(),
                "the nameplate goes under the bottom window, the latch beside the right one");
        helper.assertTrue(tiles[3].nameX2() == 8 && tiles[1].latchY2() == 3,
                "each centred on its own window where the box's centre line misses it");
        // Five rows: two split tiles and a whole one at the bottom.
        AnnunciatorBox.Rect[] five = new AnnunciatorBox.Rect[5];
        for (int i = 0; i < 5; i++) {
            five[i] = r(4, 1 + i * 4, 12, 4);
        }
        AnnunciatorBox.Tile[] odd = AnnunciatorBox.layout(five);
        int[] expect = {AnnunciatorBox.UPPER, AnnunciatorBox.LOWER, AnnunciatorBox.UPPER, AnnunciatorBox.LOWER, AnnunciatorBox.WHOLE};
        for (int i = 0; i < 5; i++) {
            helper.assertTrue(odd[i].role() == expect[i] && odd[i].row() == i && odd[i].unit() == 12, "row " + i + " of five: " + odd[i]);
        }
        // Sizes mixed in one box, a window beside the tall side of another, windows offset by half a tile: every window
        // still gets a place, and each box exactly one nameplate and one latch.
        AnnunciatorBox.Rect[] mixed = {r(0, 0, 8, 3), r(0, 3, 8, 3), r(8, 0, 8, 6), r(16, 0, 12, 4), r(16, 4, 12, 4), r(16, 8, 1, 1),
                r(28, 2, 8, 3), r(28, 5, 8, 3), r(60, 60, 2, 2), r(62, 61, 16, 6)};
        AnnunciatorBox.Tile[] any = AnnunciatorBox.layout(mixed);
        int boxes = 0;
        for (AnnunciatorBox.Tile tile : any) {
            helper.assertTrue(tile != null && tile.row() >= 0 && tile.column() >= 0 && tile.unit() >= 1, "a place for every window: " + tile);
            boxes = Math.max(boxes, tile.box() + 1);
        }
        for (int box = 0; box < boxes; box++) {
            helper.assertTrue(count(any, box, false) == 1 && count(any, box, true) == 1, "mixed box " + box + " has one nameplate and one latch");
        }
        helper.assertTrue(boxes == 2 && any[2].role() == AnnunciatorBox.WHOLE && any[0].role() == AnnunciatorBox.UPPER
                && any[6].role() == AnnunciatorBox.UPPER && any[7].role() == AnnunciatorBox.LOWER, "two boxes; the offset column pairs off by itself");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void columbiaStyleAndBoxNameAreSavedOnlyWhenSetAndTheModelsLoad(GameTestHelper helper) throws java.io.IOException {
        AnnunciatorData window = new AnnunciatorData();
        helper.assertTrue(window.style.equals(PanelStyles.CLASSIC) && !window.overhangs() && !window.toTag().contains("Style")
                && !window.toTag().contains("BoxName"), "a new window is classic and saves neither a style nor a box name: " + window.toTag());
        helper.assertTrue(AnnunciatorData.STYLES.equals(List.of("classic", "columbia"))
                && AnnunciatorControlData.STYLES.equals(List.of("classic", "station")), "the styles on offer, classic first");
        CompoundTag edit = new CompoundTag();
        edit.putString("Style", AnnunciatorData.STYLE_COLUMBIA);
        edit.putString("BoxName", "P800-C5");
        window.load(ConfigSchema.merge(ComponentTypes.ANNUNCIATOR.configSchema(), window.toTag(), edit));
        helper.assertTrue(window.style.equals("columbia") && window.overhangs() && window.boxName.equals("P800-C5")
                && window.toTag().getString("Style").equals("columbia") && window.toTag().getString("BoxName").equals("P800-C5"),
                "the wrench sets the Columbia style and the box name, and both are saved: " + window.toTag());
        AnnunciatorData reloaded = new AnnunciatorData();
        reloaded.load(window.toTag());
        helper.assertTrue(reloaded.style.equals("columbia") && reloaded.boxName.equals("P800-C5") && reloaded.toTag().equals(window.toTag()),
                "and they round trip");
        // A style this version does not know (the lamp box of an unreleased build, or anything else) loads as classic.
        for (String unknownStyle : new String[] {"lampbox", "ge", "no_such_style"}) {
            CompoundTag odd = window.toTag();
            odd.putString("Style", unknownStyle);
            AnnunciatorData unknown = new AnnunciatorData();
            unknown.load(odd);
            helper.assertTrue(unknown.style.equals(PanelStyles.CLASSIC) && !unknown.overhangs(), unknownStyle + " loads as classic");
        }
        CompoundTag back = new CompoundTag();
        back.putString("Style", PanelStyles.CLASSIC);
        back.putString("BoxName", "");
        window.load(ConfigSchema.merge(ComponentTypes.ANNUNCIATOR.configSchema(), window.toTag(), back));
        helper.assertTrue(window.style.equals(PanelStyles.CLASSIC) && !window.toTag().contains("Style") && !window.toTag().contains("BoxName"),
                "classic again, and no name, save neither key");
        CompoundTag longName = new CompoundTag();
        longName.putString("BoxName", "x".repeat(40));
        helper.assertTrue(ConfigSchema.merge(ComponentTypes.ANNUNCIATOR.configSchema(), new CompoundTag(), longName).getString("BoxName").length()
                == AnnunciatorData.MAX_TEXT, "the box name is cut like a legend line");

        AnnunciatorControlData module = new AnnunciatorControlData();
        CompoundTag station = new CompoundTag();
        station.putString("Style", AnnunciatorControlData.STYLE_STATION);
        module.load(ConfigSchema.merge(ComponentTypes.ANNUNCIATOR_CONTROL.configSchema(), module.toTag(), station));
        helper.assertTrue(module.style.equals("station") && module.toTag().getString("Style").equals("station")
                && !module.toTag().contains("AnnGroup"), "the control module takes the station style: " + module.toTag());
        AnnunciatorControlData moduleAgain = new AnnunciatorControlData();
        moduleAgain.load(module.toTag());
        helper.assertTrue(moduleAgain.style.equals("station") && moduleAgain.group == 1, "and reloads with it");
        module.load(ConfigSchema.merge(ComponentTypes.ANNUNCIATOR_CONTROL.configSchema(), module.toTag(), edit));
        helper.assertTrue(module.style.equals("station"), "a window's style is not one of the module's: " + module.style);

        // The half height sizes are on the list, after everything that was there.
        List<Footprint> sizes = ComponentTypes.ANNUNCIATOR.footprints();
        helper.assertTrue(sizes.subList(sizes.size() - 3, sizes.size()).equals(List.of(Footprint.of(8, 3), Footprint.of(12, 4), Footprint.of(16, 6)))
                && sizes.indexOf(Footprint.of(8, 6)) == 4 && sizes.indexOf(Footprint.of(12, 8)) == 0, "the half height windows are appended: " + sizes);

        // The words the wrench screen and the nameplate show.
        String lang;
        try (java.io.InputStream in = AnnunciatorBoxTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            helper.assertTrue(in != null, "lang file on the classpath");
            lang = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String key : new String[] {"component.bwr.option.Style.columbia", "component.bwr.option.Style.station",
                "component.bwr.field.box_name", "text.bwr.annunciator.box_title"}) {
            helper.assertTrue(lang.contains("\"" + key + "\""), "lang has " + key);
        }

        // The two model files are accepted by the loader and have the parts and anchors the renderer looks for.
        BakedPanelModel tile = shipped(helper, AnnunciatorData.WINDOW_MODEL);
        int whole = 1; // the plate under the window
        int half = 2;  // the plate, and half the dividing line
        for (String part : List.of("face", "rim_top", "rim_bottom", "rim_left", "rim_right", "corner_tl", "corner_tr", "corner_bl", "corner_br")) {
            helper.assertTrue(tile.part(part) != null && tile.part(part).faceCount > 0, "the tile model has " + part);
            whole += tile.part(part).faceCount;
            if (!part.endsWith("bottom") && !part.startsWith("corner_b")) {
                half += tile.part(part).faceCount;
            }
        }
        helper.assertTrue(tile.part("plate") != null && tile.part("face").role == BakedPanelModel.Role.LAMP && tile.part("face").faceCount == 1,
                "the tile has its plate, and its face is one quad that lights");
        helper.assertTrue(whole <= 40 && half <= 24, "a tile is placed by the hundred: " + whole + " quads whole, " + half + " as one alarm of a split tile");
        BakedPanelModel stationModel = shipped(helper, AnnunciatorControlData.STATION_MODEL);
        helper.assertTrue(Math.abs(stationModel.footprintW / stationModel.footprintH - 4.0f) < 1.0e-3f,
                "the station is four buttons wide, like the module's sizes: " + stationModel);
        String[] buttons = {"ack", "reset", "silence", "test"};
        for (int i = 0; i < buttons.length; i++) {
            BakedPanelModel.Part cap = stationModel.part("cap_" + buttons[i]);
            BakedPanelModel.Label label = stationModel.label(buttons[i]);
            helper.assertTrue(cap != null && cap.hasTravel && cap.travel > 0.0f && label != null, "the station has the " + buttons[i] + " cap and legend");
            // Each cap and its legend lie in their own quarter of the width: the quarter a click picks.
            float quarter = stationModel.footprintW / buttons.length;
            for (int f = 0; f < cap.faceCount; f++) {
                for (int c = 0; c < 4; c++) {
                    helper.assertTrue(cap.corner(f, c, 0) > quarter * i && cap.corner(f, c, 0) < quarter * (i + 1),
                            buttons[i] + " cap leaves its quarter");
                }
            }
            helper.assertTrue(label.x > quarter * i && label.x < quarter * (i + 1), buttons[i] + " legend is under its cap");
        }
        helper.assertTrue(stationModel.faceTotal() <= 800, "the station is within the face budget: " + stationModel.faceTotal());
        helper.succeed();
    }

    private static BakedPanelModel shipped(GameTestHelper helper, String id) throws java.io.IOException {
        try (java.io.InputStream in = AnnunciatorBoxTests.class.getResourceAsStream("/assets/bwr/models/panel/" + id + ".json")) {
            helper.assertTrue(in != null, "the model " + id + " ships in the mod");
            return PanelModelParser.parse(id, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** A sink that keeps what it is given: the twelve coordinates of each quad. */
    private static final class Quads implements QuadSink {
        final List<float[]> quads = new ArrayList<>();

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            quads.add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
        }

        /** {least x, least y, greatest x, greatest y, greatest z} of everything recorded. */
        float[] bounds() {
            float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (float[] q : quads) {
                for (int c = 0; c < 4; c++) {
                    b[0] = Math.min(b[0], q[c * 3]);
                    b[1] = Math.min(b[1], q[c * 3 + 1]);
                    b[2] = Math.max(b[2], q[c * 3]);
                    b[3] = Math.max(b[3], q[c * 3 + 1]);
                    b[4] = Math.max(b[4], q[c * 3 + 2]);
                }
            }
            return b;
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0e-3f;
    }

    /**
     * The mesh of a Columbia window through the registered model, with the shipped tile model: inside a box it is the
     * tile on its own cells, no heavier than the budget; by itself it is a whole box, with the plate margins outside its
     * cells; and the classic window is what it always was.
     */
    @GameTest(template = "empty")
    public static void aColumbiaWindowDrawsItsTileAndThePlateMarginsOfItsBox(GameTestHelper helper) {
        PanelModels.setFixtures(java.util.Map.of(AnnunciatorData.WINDOW_MODEL, HardwareStage.shipped(AnnunciatorData.WINDOW_MODEL)));
        try {
            ComponentModel model = ComponentModels.get(ComponentTypes.ANNUNCIATOR.id());
            PanelComponent window = new PanelComponent(1, ComponentTypes.ANNUNCIATOR, 0, 0, Footprint.of(8, 3), "");
            AnnunciatorData data = (AnnunciatorData) window.data();
            data.line1 = "BUS 8";
            data.line2 = "LOSS OF VOLT";
            RenderCache cache = RenderCache.of(window);
            // Classic, in the middle of a box: the dark base and the tile, as ever, and the mesh key is the edge word.
            cache.boxEdges = AnnunciatorBox.SIDES;
            Quads classic = new Quads();
            model.buildStatic(classic, window, 1.0f);
            long classicKey = model.staticKey(window);
            helper.assertTrue(classic.quads.size() == 10 && classicKey == AnnunciatorBox.SIDES,
                    "the classic window is untouched: " + classic.quads.size() + " quads, key " + classicKey);

            // Columbia, in the middle of a box, the upper alarm of a split tile.
            data.style = AnnunciatorData.STYLE_COLUMBIA;
            cache.boxTile = new AnnunciatorBox.Tile(0, 2, 1, AnnunciatorBox.UPPER, 8, false, 8, false, 3);
            Quads upper = new Quads();
            model.buildStatic(upper, window, 1.0f);
            float[] ub = upper.bounds();
            helper.assertTrue(upper.quads.size() > classic.quads.size() && upper.quads.size() <= 24,
                    "one alarm of a split tile is at most 24 quads: " + upper.quads.size());
            helper.assertTrue(ub[0] > -1.0e-3f && ub[1] > -1.0e-3f && ub[2] < 8.001f && ub[3] < 3.001f,
                    "a window inside a box draws on its own cells only: " + java.util.Arrays.toString(ub));
            helper.assertTrue(near(ub[4], AnnunciatorBox.LIP_Z), "the rim stands where the classic bezel lip does: " + ub[4]);
            long upperKey = model.staticKey(window);
            cache.boxTile = new AnnunciatorBox.Tile(0, 3, 1, AnnunciatorBox.LOWER, 8, false, 8, false, 3);
            helper.assertTrue(upperKey != classicKey && model.staticKey(window) != upperKey,
                    "the mesh is rebuilt when the window becomes the other half of its tile");
            Quads lower = new Quads();
            model.buildStatic(lower, window, 1.0f);
            helper.assertTrue(lower.quads.size() == upper.quads.size(), "the lower alarm mirrors the upper: " + lower.quads.size());
            // The legend is laid out in the face of the tile: inside the rim, clear of the dividing line.
            cache.boxTile = new AnnunciatorBox.Tile(0, 2, 1, AnnunciatorBox.UPPER, 8, false, 8, false, 3);
            List<Legends.Item> legend = model.textLayout(window, 1.0f);
            helper.assertTrue(legend.size() == 2, "both legend lines are placed: " + legend.size());
            for (Legends.Item item : legend) {
                float[] box = Legends.box(item, 1.0, 1.0f);
                helper.assertTrue(box[0] > 0.5f && box[2] < 7.5f && box[1] > 0.5f && box[3] < 3.0f,
                        "'" + item.text() + "' lies in the tile's face: " + java.util.Arrays.toString(box));
            }

            // By itself (and before its panel group has been scanned): a whole box, its plate overhanging the window.
            cache.boxEdges = 0;
            cache.boxTile = null;
            Quads lone = new Quads();
            model.buildStatic(lone, window, 1.0f);
            float[] lb = lone.bounds();
            helper.assertTrue(near(lb[0], -AnnunciatorBox.MARGIN_LEFT * 8) && near(lb[1], -AnnunciatorBox.MARGIN_TOP * 8)
                    && near(lb[2], 8 + AnnunciatorBox.MARGIN_RIGHT * 8) && near(lb[3], 3 + AnnunciatorBox.MARGIN_BOTTOM * 8),
                    "the plate margins of a lone window: " + java.util.Arrays.toString(lb));
            helper.assertTrue(-lb[0] <= AnnunciatorBox.MAX_OVERHANG && lb[3] - 3 <= AnnunciatorBox.MAX_OVERHANG,
                    "the overhang is within what the render bounds allow for");
            helper.assertTrue(lone.quads.size() > upper.quads.size() && lone.quads.size() <= 70,
                    "a lone window carries its tile, margins, nameplate and latch: " + lone.quads.size());
            helper.assertTrue(model.staticKey(window) != upperKey, "and its mesh key is its own");
        } finally {
            PanelModels.setFixtures(java.util.Map.of());
        }
        // Without the tile model the style draws the classic window rather than nothing.
        if (!PanelModels.has(AnnunciatorData.WINDOW_MODEL)) {
            ComponentModel model = ComponentModels.get(ComponentTypes.ANNUNCIATOR.id());
            PanelComponent window = new PanelComponent(2, ComponentTypes.ANNUNCIATOR, 0, 0, Footprint.of(8, 3), "");
            ((AnnunciatorData) window.data()).style = AnnunciatorData.STYLE_COLUMBIA;
            RenderCache.of(window).boxEdges = AnnunciatorBox.SIDES;
            Quads fallback = new Quads();
            model.buildStatic(fallback, window, 1.0f);
            helper.assertTrue(fallback.quads.size() == 10, "no tile model: the classic window is drawn: " + fallback.quads.size());
        }
        helper.succeed();
    }

    // ---- the distance fade (pure)

    @GameTest(template = "empty")
    public static void textFadesFromFullToNothingAndAStripTakesOver(GameTestHelper helper) {
        helper.assertTrue(TextFade.textAlpha(TextFade.FULL_PX) == 1.0f && TextFade.textAlpha(40.0f) == 1.0f, "full size text is untouched");
        helper.assertTrue(TextFade.textAlpha(TextFade.GONE_PX) == 0.0f && TextFade.textAlpha(0.0f) == 0.0f, "tiny text is gone");
        helper.assertTrue(TextFade.stripAlpha(TextFade.FULL_PX) == 0.0f && TextFade.stripAlpha(40.0f) == 0.0f, "no strip over readable text");
        helper.assertTrue(TextFade.stripAlpha(TextFade.GONE_PX) > 0.3f, "the strip is nearly full where the text has gone: " + TextFade.stripAlpha(TextFade.GONE_PX));
        helper.assertTrue(TextFade.stripAlpha(0.0f) == 0.0f && TextFade.stripAlpha(0.1f) < 0.05f, "and ink under a fraction of a pixel is not drawn");
        float peak = 0.0f;
        for (float px = 0.0f; px <= 6.0f; px += 0.01f) {
            peak = Math.max(peak, TextFade.stripAlpha(px));
        }
        helper.assertTrue(peak <= TextFade.STRIP_ALPHA + 1.0e-6f && peak > 0.4f, "the strip is a soft grey, never solid: " + peak);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theFadeHasNoFlickerBandBecauseItIsContinuousAndMonotonic(GameTestHelper helper) {
        // Walk away from a real size legend (a 7 pixel cap of 0.16 cells) in steps of a centimetre: no step may jump.
        float capCells = 0.16f;
        float focal = 540.0f * 1.43f; // 1080p, field of view 70
        float prevText = 2.0f;
        float prevStrip = 2.0f;
        float maxStep = 0.0f;
        float lastCap = Float.MAX_VALUE;
        for (float d = 0.3f; d <= 60.0f; d += 0.01f) {
            float cap = TextFade.capPixels(capCells, focal, d);
            helper.assertTrue(cap <= lastCap, "the glyph shrinks with distance");
            lastCap = cap;
            float text = TextFade.textAlpha(cap);
            float strip = TextFade.stripAlpha(cap);
            if (prevText <= 1.0f) {
                helper.assertTrue(text <= prevText + 1.0e-6f, "text only fades as you walk away, at " + d);
                maxStep = Math.max(maxStep, Math.max(Math.abs(text - prevText), Math.abs(strip - prevStrip)));
            }
            prevText = text;
            prevStrip = strip;
        }
        helper.assertTrue(maxStep < 0.03f, "the largest change of either opacity between two steps of 1 cm is " + maxStep);
        // The same walk the other way gives the same values: nothing remembers which way you came (no hysteresis to stick).
        for (float cap = 0.0f; cap <= 8.0f; cap += 0.05f) {
            helper.assertTrue(TextFade.textAlpha(cap) == TextFade.textAlpha(cap) && TextFade.textAlpha(cap + 0.05f) >= TextFade.textAlpha(cap),
                    "monotonic at " + cap);
        }
        // Halving the distance doubles the size on the screen.
        helper.assertTrue(Math.abs(TextFade.capPixels(1.0f, focal, 4.0f) - 2.0f * TextFade.capPixels(1.0f, focal, 8.0f)) < 1.0e-3f,
                "size falls as one over the distance");
        // A real size legend (7 pixel cap of a 4 line window, 0.16 cells) is readable at arm's length and a strip from across the room.
        helper.assertTrue(TextFade.textAlpha(TextFade.capPixels(capCells, focal, 1.0f)) == 1.0f
                && TextFade.textAlpha(TextFade.capPixels(capCells, focal, 12.0f)) == 0.0f, "readable near, a strip far");
        helper.succeed();
    }
}
