package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The real size part of the panel client check: one 4 m duplex board filled the way a real control board is, every
 * instrument and control at its real size (see CONTROL-PANELS.md, "Real sizes"). Vertical board with rows of dials,
 * edgewise meters and bar gauges, a tilted header block of annunciator windows, and a bench of control switches,
 * selectors, pushbuttons and lamps. Stages: the whole board from the front, the header, the bench, a close-up from
 * about a block away to show the small dials can be read, and the size picker open over the board.
 *
 * <p>Plan: the same as {@link PanelDuplexShowcase}, 40 blocks further east: the bench of the leftmost column is at
 * x 5444, z 5260, the columns run west to x 5441, the back blocks are at z 5261 and the floor is at y 125.
 *
 * <p>The bar gauges are 1 x 3 cells, which is not on the bar gauge's list of sizes yet (its tests assume a case at
 * least 11 cells tall): they are placed directly to show what the real size will look like.
 */
final class PanelRealScaleShowcase {
    private PanelRealScaleShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final BlockPos FIRST_BENCH = new BlockPos(5444, 126, 5260);
    private static final int COLUMNS = 4;

    static final String[] NAMES = {"realscale-board", "realscale-header", "realscale-bench", "realscale-closeup",
            "realscale-closeup-switches", "realscale-closeup-annunciators", "realscale-picker", "realscale-picker-scrolled"};
    private static final double[][] EYE = {{5443.0, 128.4, 5257.0}, {5443.0, 128.3, 5257.9}, {5443.0, 128.5, 5258.5},
            {5444.6, 128.9, 5260.0}, {5444.3, 128.1, 5259.2}, {5443.9, 129.0, 5259.9}, {5443.0, 128.4, 5257.0}, {5443.0, 128.4, 5257.0}};
    private static final double[][] LOOK = {{5443.0, 128.3, 5261.0}, {5443.0, 129.6, 5260.7}, {5443.0, 126.7, 5260.5},
            {5444.6, 128.85, 5261.0}, {5444.3, 126.75, 5260.3}, {5443.9, 129.75, 5260.4}, {5443.0, 128.3, 5261.0}, {5443.0, 128.3, 5261.0}};

    static boolean night(int stage) {
        return false;
    }

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    private static BlockPos bench(int column) {
        BlockPos pos = FIRST_BENCH;
        for (int i = 0; i < column; i++) {
            pos = PanelType.nextColumn(pos, FACING);
        }
        return pos;
    }

    // ---- building (server)

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static int mounted;
    private static int index;

    private static final String[][] ALARMS = {
            {"RPV LEVEL", "LOW L3"}, {"RPV LEVEL", "HIGH L8"}, {"RPV PRESS", "HIGH"}, {"DRYWELL", "PRESS HI"},
            {"MSIV", "NOT OPEN"}, {"RECIRC A", "TRIP"}, {"RECIRC B", "TRIP"}, {"RFP A", "TRIP"},
            {"HPCS", "INITIATED"}, {"RCIC", "INITIATED"}, {"APRM", "UPSCALE"}, {"IRM", "DOWNSCALE"},
            {"SRV", "OPEN"}, {"SUPP POOL", "TEMP HI"}, {"COND VAC", "LOW"}, {"TURB", "TRIP"},
            {"GEN", "LOCKOUT"}, {"ROD", "DRIFT"}, {"CRD", "ACCUM LOW"}, {"SCRAM", "RESET"},
            {"RHR A", "RUNNING"}, {"RHR B", "RUNNING"}, {"SBGT", "START"}, {"RPS A", "TRIP"}};

    private static PanelComponent put(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type, int w, int h) {
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Real scale showcase: placement refused " + type.id() + " " + w + "x" + h + " at " + pos
                    + " " + cx + "," + cy);
        }
        mounted++;
        index++;
        return component;
    }

    /** A component at the first (real) size of its type. */
    private static PanelComponent real(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type) {
        Footprint size = type.footprints().get(type.realDefaultIndex());
        return put(level, pos, cx, cy, type, size.w(), size.h());
    }

    private static void reading(ServerLevel level, BlockPos pos, PanelComponent component, double value) {
        component.setSource(0, InputSources.constant(value));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        index = 0;
        for (int x = 5432; x <= 5452; x++) {
            for (int z = 5246; z <= 5268; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 133; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BlockState column = BwrBlocks.CONTROL_PANEL_DUPLEX.get().defaultBlockState().setValue(PanelBlock.FACING, FACING);
        for (int c = 0; c < COLUMNS; c++) {
            for (DuplexCell cell : DuplexCell.values()) {
                BlockPos pos = cell.at(bench(c), FACING);
                level.setBlock(pos, column.setValue(DuplexPanelBlock.CELL, cell), 3);
                PLACED.add(pos);
            }
        }
        for (int c = 0; c < COLUMNS; c++) {
            vertical(level, DuplexCell.BOARD_UPPER.at(bench(c), FACING), c, true);
            vertical(level, DuplexCell.BOARD_LOWER.at(bench(c), FACING), c, false);
            header(level, DuplexCell.HEADER.at(bench(c), FACING), c);
            bench(level, bench(c), c);
        }
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    /** Rows of 2 x 2 dials, 1 x 3 edgewise meters and bar gauges, 3 x 1 horizontal edgewise meters, displays and knobs. */
    private static void vertical(ServerLevel level, BlockPos pos, int c, boolean upper) {
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 5; i++) {
                PanelComponent dial = real(level, pos, i * 3, row * 3, ComponentTypes.DIAL_GAUGE);
                PanelModelShowcase.look(dial, PanelStyles.GE, (i + row + c) % 3 == 0 ? "red_pointer" : "standard");
                reading(level, pos, dial, 18 + (index * 37) % 70);
            }
        }
        if (upper) {
            for (int i = 0; i < 8; i++) {
                if (i % 2 == 0) {
                    PanelComponent strip = real(level, pos, i * 2, 6, ComponentTypes.EDGEWISE_GAUGE_VERTICAL);
                    PanelModelShowcase.look(strip, PanelStyles.GE, i % 4 == 0 ? "red_pointer" : "standard");
                    reading(level, pos, strip, 20 + (index * 29) % 70);
                } else {
                    PanelComponent bar = put(level, pos, i * 2, 6, ComponentTypes.BAR_GAUGE, 1, 3);
                    reading(level, pos, bar, 15 + (index * 31) % 75);
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int i = 0; i < 4; i++) {
                    PanelComponent strip = real(level, pos, i * 4, 10 + row * 2, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL);
                    reading(level, pos, strip, 10 + (index * 23) % 85);
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                PanelComponent seven = real(level, pos, i * 5, 6, ComponentTypes.SEVEN_SEGMENT_DISPLAY);
                reading(level, pos, seven, 1000 + 337 * (index % 7));
            }
            for (int i = 0; i < 4; i++) {
                real(level, pos, i * 4, 9, ComponentTypes.THUMBWHEEL);
            }
            for (int i = 0; i < 8; i++) {
                real(level, pos, i * 2, 12, ComponentTypes.SETPOINT_KNOB);
            }
            for (int i = 0; i < 8; i++) {
                PanelComponent graph = real(level, pos, i * 2, 13, ComponentTypes.BAR_GRAPH);
                reading(level, pos, graph, 5 + (index * 13) % 90);
            }
        }
    }

    /** The tilted header: a block of annunciator windows, 2.8 in each, some in alarm, some acknowledged. */
    private static void header(ServerLevel level, BlockPos pos, int c) {
        for (int row = 0; row < 8; row++) {
            for (int i = 0; i < 16; i++) {
                PanelComponent tile = real(level, pos, i, row, ComponentTypes.ANNUNCIATOR);
                AnnunciatorData d = (AnnunciatorData) tile.data();
                String[] text = ALARMS[(c * 128 + row * 16 + i) % ALARMS.length];
                d.line1 = text[0];
                d.line2 = text[1];
                int state = (row * 7 + i * 3 + c) % 11;
                if (state == 0) {
                    d.logic.update(true);
                } else if (state == 1) {
                    d.logic.update(true);
                    d.logic.acknowledge();
                }
                tile.touch();
            }
        }
    }

    /** The bench: breaker switches, lamps, pushbuttons, selectors, key switches, toggles. */
    private static void bench(ServerLevel level, BlockPos pos, int c) {
        for (int i = 0; i < 8; i++) {
            PanelComponent sw = real(level, pos, i * 2, 0, ComponentTypes.CONTROL_SWITCH);
            ControlSwitchData d = (ControlSwitchData) sw.data();
            d.spring = false;
            d.position = (i + c) % 3 - 1;
            sw.type().onLoaded(sw);
            sw.touch();
        }
        for (int i = 0; i < 16; i++) {
            PanelComponent lamp = real(level, pos, i, 3, ComponentTypes.LAMP);
            LampData d = (LampData) lamp.data();
            d.color = (i + c) % 3 == 0 ? 0xE02020 : (i + c) % 3 == 1 ? 0x20C040 : 0xF0A000;
            reading(level, pos, lamp, (i + c) % 4 == 0 ? 0 : 1);
        }
        for (int i = 0; i < 16; i++) {
            PanelComponent button = real(level, pos, i, 4, ComponentTypes.PUSHBUTTON);
            PanelModelShowcase.look(button, PanelStyles.GE, i % 5 == 0 ? "mushroom" : i % 3 == 0 ? "extended" : "flush");
        }
        for (int i = 0; i < 8; i++) {
            real(level, pos, i * 2, 6, ComponentTypes.ROTARY_SELECTOR);
        }
        for (int i = 0; i < 4; i++) {
            real(level, pos, i * 2, 9, ComponentTypes.KEY_SWITCH);
        }
        for (int i = 0; i < 4; i++) {
            real(level, pos, 8 + i * 2, 9, ComponentTypes.PUSH_TURN_SWITCH);
        }
        for (int i = 0; i < 4; i++) {
            real(level, pos, 8 + i * 2, 11, ComponentTypes.RPS_TRIP);
        }
        for (int i = 0; i < 8; i++) {
            real(level, pos, i * 2, 12, ComponentTypes.TOGGLE_SWITCH);
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
            throw new AssertionError("Real scale showcase " + NAMES[stage] + ": " + components + " of " + mounted
                    + " components on the client");
        }
        return PLACED.size() + " blocks, " + components + " components";
    }
}
