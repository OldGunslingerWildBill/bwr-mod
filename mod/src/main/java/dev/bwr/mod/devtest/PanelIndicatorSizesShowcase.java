package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The sizes of the alpha.60 size table for the buttons, lamps and indicators (see CONTROL-PANELS.md, "Component sizes"), on
 * one vertical board of 4 x 2 blocks: the new annunciator windows (8x6, 8x8) with the 16x4 and 32x8 control modules, the
 * 3x3 lamp, the 3x3 and 4x4 pushbuttons and the 8x4 and 12x6 RPS trips, the 6 tall and 4 tall segment displays, and the new
 * gauges (6x6 and 8x8 dials, the 3x8 edgewise meter, the 4x16 bar gauge, the 6x2 bar graph). Every part is placed at a
 * size of the new table, so the pictures show the legends, digits and scales at those sizes.
 *
 * <p>Plan: the board's blocks run west from x 6000 (column 0) at z 5500, the top row at y 128 and the second at y 127; the
 * floor is at y 125. The camera stands to the north of it (the panels face north).
 */
final class PanelIndicatorSizesShowcase {
    private PanelIndicatorSizesShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final int X0 = 6000;
    private static final int Y0 = 128;
    private static final int Z0 = 5500;
    private static final int COLUMNS = 4;
    private static final int ROWS = 2;

    static final String[] NAMES = {"indsizes-board", "indsizes-annunciators", "indsizes-lamps-buttons", "indsizes-displays",
            "indsizes-gauges"};
    private static final double[][] EYE = {{5999.0, 128.2, Z0 - 3.0}, {5999.4, 128.55, Z0 - 2.1}, {5998.0, 128.7, Z0 - 1.6},
            {5999.4, 127.6, Z0 - 2.1}, {5998.0, 127.6, Z0 - 1.6}};
    private static final double[][] LOOK = {{5999.0, 128.0, Z0}, {5999.4, 128.5, Z0}, {5998.0, 128.6, Z0}, {5999.4, 127.5, Z0},
            {5998.0, 127.5, Z0}};

    static boolean night(int stage) {
        return false;
    }

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    private static BlockPos block(int column, int row) {
        return new BlockPos(X0 - column, Y0 - row, Z0);
    }

    // ---- building (server)

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static int mounted;

    private static PanelComponent put(ServerLevel level, int column, int row, int cx, int cy, ComponentType type, int w, int h) {
        PanelComponent component = PanelOps.placeDirect(level, block(column, row), cx, cy, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Indicator sizes showcase: placement refused " + type.id() + " " + w + "x" + h + " at block "
                    + column + "," + row + " cell " + cx + "," + cy);
        }
        mounted++;
        return component;
    }

    private static void reading(ServerLevel level, int column, int row, PanelComponent component, double value) {
        component.setSource(0, InputSources.constant(value));
        ((PanelBlockEntity) level.getBlockEntity(block(column, row))).resample(component, true);
    }

    private static void window(ServerLevel level, int column, int row, int cx, int cy, int w, int h, String[] lines, int state) {
        PanelComponent tile = put(level, column, row, cx, cy, ComponentTypes.ANNUNCIATOR, w, h);
        AnnunciatorData d = (AnnunciatorData) tile.data();
        d.line1 = lines[0];
        d.line2 = lines[1];
        if (lines.length > 2) {
            d.line3 = lines[2];
        }
        if (lines.length > 3) {
            d.line4 = lines[3];
        }
        if (state == 0) {
            d.logic.update(true);
        } else if (state == 1) {
            d.logic.update(true);
            d.logic.acknowledge();
        }
        tile.touch();
    }

    private static void digits(ServerLevel level, int column, int row, int cx, int cy, int w, int h, boolean sixteen, String text) {
        PanelComponent display = put(level, column, row, cx, cy,
                sixteen ? ComponentTypes.SIXTEEN_SEGMENT_DISPLAY : ComponentTypes.SEVEN_SEGMENT_DISPLAY, w, h);
        ((SegmentDisplayData) display.data()).setText(text);
        display.touch();
    }

    private static void lamp(ServerLevel level, int column, int row, int cx, int cy, int size, int color, boolean on) {
        PanelComponent lamp = put(level, column, row, cx, cy, ComponentTypes.LAMP, size, size);
        ((LampData) lamp.data()).color = color;
        reading(level, column, row, lamp, on ? 1 : 0);
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        for (int x = X0 - 12; x <= X0 + 8; x++) {
            for (int z = Z0 - 10; z <= Z0 + 6; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 132; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        var state = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, FACING);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                level.setBlock(block(column, row), state, 3);
                PLACED.add(block(column, row));
            }
        }

        // Top row, blocks 0 and 1: annunciator windows at the new sizes, then the control modules.
        window(level, 0, 0, 0, 0, 8, 6, new String[] {"RPV LEVEL", "LOW L3"}, 0);
        window(level, 0, 0, 8, 0, 8, 6, new String[] {"RPV PRESS", "HIGH"}, 1);
        window(level, 0, 0, 0, 6, 8, 6, new String[] {"MSIV", "NOT OPEN"}, 2);
        window(level, 0, 0, 8, 6, 8, 6, new String[] {"SCRAM", "RESET"}, 0);
        put(level, 0, 0, 0, 12, ComponentTypes.ANNUNCIATOR_CONTROL, 16, 4);
        window(level, 1, 0, 0, 0, 8, 8, new String[] {"RECIRC A", "DRIVE FLOW", "LOW", "CHECK RFP"}, 0);
        window(level, 1, 0, 8, 0, 8, 8, new String[] {"SUPP POOL", "TEMP HI"}, 1);
        put(level, 1, 0, 0, 8, ComponentTypes.ANNUNCIATOR_CONTROL, 32, 8);

        // Top row, blocks 2 and 3: lamps, pushbuttons and the RPS trips.
        int[] colours = {0xE02020, 0x20C040, 0xF0A000, 0xE02020};
        for (int i = 0; i < 4; i++) {
            lamp(level, 2, 0, i * 4, 0, 3, colours[i], i != 1);
        }
        for (int i = 0; i < 3; i++) {
            put(level, 2, 0, i * 5, 4, ComponentTypes.PUSHBUTTON, 4, 4);
        }
        for (int i = 0; i < 4; i++) {
            put(level, 3, 0, i * 4, 0, ComponentTypes.PUSHBUTTON, 3, 3);
        }
        for (int i = 0; i < 4; i++) {
            lamp(level, 3, 0, i * 4, 3, 2, colours[3 - i], i != 2);
        }
        put(level, 3, 0, 0, 6, ComponentTypes.RPS_TRIP, 12, 6);
        put(level, 3, 0, 0, 12, ComponentTypes.RPS_TRIP, 8, 4);

        // Second row, blocks 0 and 1: segment displays, 6 tall and 4 tall.
        digits(level, 0, 1, 0, 0, 9, 6, false, "98.6");
        digits(level, 0, 1, 10, 0, 6, 4, false, "42");
        digits(level, 0, 1, 0, 7, 12, 6, true, "TRIP");
        digits(level, 1, 1, 0, 0, 18, 6, true, "RPV LV");
        digits(level, 1, 1, 0, 7, 12, 4, false, "1234.5");
        digits(level, 1, 1, 0, 12, 8, 4, false, "7.25");

        // Second row, blocks 2 and 3: dials, an edgewise meter, a bar gauge and a bar graph.
        PanelComponent dial = put(level, 2, 1, 2, 0, ComponentTypes.DIAL_GAUGE, 6, 6);
        reading(level, 2, 1, dial, 62);
        PanelComponent big = put(level, 2, 1, 8, 0, ComponentTypes.DIAL_GAUGE, 8, 8);
        reading(level, 2, 1, big, 35);
        PanelComponent edge = put(level, 2, 1, 2, 8, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 3, 8);
        reading(level, 2, 1, edge, 55);
        PanelComponent flat = put(level, 2, 1, 6, 9, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 8, 3);
        reading(level, 2, 1, flat, 70);
        PanelComponent graph = put(level, 2, 1, 6, 13, ComponentTypes.BAR_GRAPH, 6, 2);
        reading(level, 2, 1, graph, 60);
        PanelComponent bar = put(level, 3, 1, 1, 0, ComponentTypes.BAR_GAUGE, 4, 16);
        reading(level, 3, 1, bar, 48);
        PanelComponent dial4 = put(level, 3, 1, 7, 0, ComponentTypes.DIAL_GAUGE, 6, 6);
        reading(level, 3, 1, dial4, 20);

        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /** Every component is on the client: "blocks/components". */
    static String verify(Minecraft mc, int stage) {
        int components = 0;
        for (BlockPos pos : PLACED) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
            }
        }
        if (components != mounted) {
            throw new AssertionError("Indicator sizes showcase " + NAMES[stage] + ": " + components + " of " + mounted
                    + " components on the client");
        }
        return PLACED.size() + " blocks, " + components + " components";
    }
}
