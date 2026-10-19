package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.trim.PanelEndCapBlock;
import dev.bwr.mod.panel.trim.PanelTrimBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The control-room trim part of the panel client check: a small U-shaped control room (a back wall run and two side
 * runs of vertical boards on base cabinets, with a benchboard run in front, headers on top, corners where they meet,
 * end caps, aprons and a console island) and a free-standing L-shaped partition with an outside corner, photographed
 * from several places. It is what the trim blocks are for, in one picture, and the check confirms that every trim
 * block's model loaded (a missing model would draw as the purple and black cube).
 *
 * <p>Plan (north is up, the operator stands in the north looking south; x grows to the east):
 * <pre>
 *   x 5300 .. 5309, back wall at z 5211; vertical boards and bases on z 5210 (back run, x 5301..5308), on x 5300 and
 *   x 5309 (side runs, z 5206..5209); inside corners on the two back corners (vertical, and the 45 degree filler on
 *   the east one); benchboards one cell in front of the bases (back run z 5209, side runs x 5301 and x 5308) with the
 *   benchboard inside corners where they meet; aprons in front of those; a console island at z 5203.
 * </pre>
 */
final class PanelTrimShowcase {
    private PanelTrimShowcase() {
    }

    /** The first floor-standing layer; the floor itself is one block lower. */
    private static final int Y0 = 126;
    private static final int XW = 5300;
    private static final int XE = 5309;
    private static final int ZB = 5210;
    private static final int ISLAND_X = 5320;
    private static final int ISLAND_Z = 5205;

    static final String[] NAMES = {"trim-room-wide", "trim-room-dark", "trim-room-corner-west", "trim-room-bench-low",
            "trim-room-annunciators", "trim-room-console", "trim-island-corner", "trim-island-close", "trim-island-ends",
            "trim-island-rear"};
    private static final double[][] EYE = {
            {5304.5, 129.4, 5198.0}, {5304.5, 129.0, 5200.0}, {5303.2, 128.4, 5204.5}, {5306.5, 127.1, 5200.5},
            {5305.0, 128.8, 5207.6}, {5304.0, 127.7, 5198.5}, {5313.0, 128.6, 5199.5}, {5316.5, 128.0, 5202.0},
            {5326.0, 127.9, 5202.5}, {5323.0, 128.2, 5211.0}};
    private static final double[][] LOOK = {
            {5304.5, 128.0, 5208.5}, {5304.5, 128.0, 5208.5}, {5300.8, 128.2, 5209.0}, {5305.5, 127.5, 5208.0},
            {5305.0, 128.6, 5210.5}, {5304.5, 126.6, 5203.5}, {5321.0, 127.4, 5206.0}, {5320.8, 127.2, 5205.8},
            {5324.0, 127.4, 5205.4}, {5321.5, 127.4, 5207.0}};

    static boolean night(int stage) {
        return stage == 1;
    }

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    // ---- building (server)

    private static final List<BlockPos> PLACED = new ArrayList<>();

    private static void put(ServerLevel level, int x, int y, int z, Block block, Direction facing) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(PanelTrimBlock.FACING)) {
            state = state.setValue(PanelTrimBlock.FACING, facing);
        } else {
            state = state.setValue(PanelBlock.FACING, facing);
        }
        level.setBlock(pos, state, 3);
        PLACED.add(pos);
    }

    private static void cap(ServerLevel level, int x, int y, int z, Block block, Direction facing, PanelEndCapBlock.Side side) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState().setValue(PanelTrimBlock.FACING, facing)
                .setValue(PanelEndCapBlock.SIDE, side), 3);
        PLACED.add(new BlockPos(x, y, z));
    }

    /** Mounts one component on a panel block at a cell, drawn as the default style, with a constant reading. */
    private static void mount(ServerLevel level, BlockPos pos, int cx, int cy, int w, int h, ComponentType type,
                              String variant, double value) {
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Trim showcase: placement refused " + type.id() + " at " + pos);
        }
        PanelModelShowcase.look(component, PanelStyles.GE, variant);
        if (component.data() instanceof ControlSwitchData d) {
            d.spring = false;
            d.position = (int) value;
            component.type().onLoaded(component);
        } else if (component.inputCount() > 0) {
            component.setSource(0, InputSources.constant(value));
            ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
        }
        component.touch();
    }

    /** A few instruments on the boards so the trim is seen around a finished room, not bare blocks. */
    private static void instruments(ServerLevel level) {
        double[] readings = {68, 30, 55, 80, 45, 62, 25, 74};
        for (int i = 0; i < 8; i++) {
            BlockPos board = new BlockPos(XW + 1 + i, Y0 + 1, ZB);
            mount(level, board, 0, 0, 16, 16, ComponentTypes.DIAL_GAUGE, i % 2 == 0 ? "standard" : "red_pointer", readings[i]);
            BlockPos desk = new BlockPos(XW + 2 + i, Y0, ZB - 1);
            if (i < 6) {
                mount(level, desk, 2, 0, 12, 16, ComponentTypes.CONTROL_SWITCH, "pistol", i % 3 - 1);
            }
        }
        for (int z = ZB - 4; z <= ZB - 1; z++) {
            mount(level, new BlockPos(XW, Y0 + 1, z), 0, 0, 16, 16, ComponentTypes.DIAL_GAUGE, "standard", 40 + 10 * (z - ZB));
            mount(level, new BlockPos(XE, Y0 + 1, z), 0, 0, 16, 16, ComponentTypes.DIAL_GAUGE, "red_pointer", 60 - 10 * (z - ZB));
        }
        for (int i = 0; i < 4; i++) {
            mount(level, new BlockPos(XW + 3 + i, Y0, ZB - 7), 2, 2, 12, 12, ComponentTypes.DIAL_GAUGE, "standard", 30 + 15 * i);
        }
        for (int i = 1; i <= 3; i++) {
            mount(level, new BlockPos(ISLAND_X + i, Y0 + 1, ISLAND_Z), 0, 0, 16, 16, ComponentTypes.DIAL_GAUGE,
                    i % 2 == 0 ? "red_pointer" : "standard", 30 + 20 * i);
            mount(level, new BlockPos(ISLAND_X, Y0 + 1, ISLAND_Z + i), 0, 0, 16, 16, ComponentTypes.DIAL_GAUGE,
                    i % 2 == 0 ? "standard" : "red_pointer", 80 - 20 * i);
        }
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(XW, Y0, ZB - 7), new BlockPos(XE, Y0 + 1, ZB))) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(ISLAND_X, Y0 + 1, ISLAND_Z), new BlockPos(ISLAND_X + 3, Y0 + 1, ISLAND_Z + 3))) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        // the floor and the walls
        for (int x = XW - 4; x <= ISLAND_X + 8; x++) {
            for (int z = 5196; z <= 5215; z++) {
                level.setBlock(new BlockPos(x, Y0 - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = Y0; y <= Y0 + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int y = Y0; y <= Y0 + 5; y++) {
            for (int x = XW - 1; x <= XE + 1; x++) {
                level.setBlock(new BlockPos(x, y, ZB + 1), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            }
            for (int z = 5196; z <= ZB + 1; z++) {
                level.setBlock(new BlockPos(XW - 1, y, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(XE + 1, y, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            }
        }
        Block vertical = BwrBlocks.CONTROL_PANEL_VERTICAL.get();
        Block bench = BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
        Block console = BwrBlocks.CONTROL_PANEL_CONSOLE.get();
        Block base = BwrBlocks.CONTROL_PANEL_PLINTH_BASE.get();
        Block apron = BwrBlocks.CONTROL_PANEL_PLINTH_APRON.get();
        Block kick = BwrBlocks.CONTROL_PANEL_PLINTH_KICK.get();
        Block strip = BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get();
        Block canopy = BwrBlocks.CONTROL_PANEL_HEADER_CANOPY.get();
        Block vent = BwrBlocks.CONTROL_PANEL_HEADER_VENT.get();
        Block cornerIn = BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_INSIDE.get();
        Block cornerChamfer = BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_CHAMFER.get();
        Block cornerOut = BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE.get();
        Block benchIn = BwrBlocks.CONTROL_PANEL_CORNER_BENCH_INSIDE.get();
        Block capFlat = BwrBlocks.CONTROL_PANEL_END_CAP_FLAT.get();
        Block capBench = BwrBlocks.CONTROL_PANEL_END_CAP_BENCH.get();
        Block capConsole = BwrBlocks.CONTROL_PANEL_END_CAP_CONSOLE.get();
        Block capRound = BwrBlocks.CONTROL_PANEL_END_CAP_ROUND.get();

        // back run: base cabinet, vertical board, header
        for (int x = XW + 1; x <= XE - 1; x++) {
            put(level, x, Y0, ZB, base, Direction.NORTH);
            put(level, x, Y0 + 1, ZB, vertical, Direction.NORTH);
            put(level, x, Y0 + 2, ZB, switch (x - XW - 1) {
                case 2, 5 -> vent;
                case 4 -> canopy;
                default -> strip;
            }, Direction.NORTH);
        }
        // side runs; their headers
        for (int z = ZB - 4; z <= ZB - 1; z++) {
            put(level, XW, Y0, z, base, Direction.EAST);
            put(level, XW, Y0 + 1, z, vertical, Direction.EAST);
            put(level, XW, Y0 + 2, z, z == ZB - 2 ? vent : strip, Direction.EAST);
            put(level, XE, Y0, z, base, Direction.WEST);
            put(level, XE, Y0 + 1, z, vertical, Direction.WEST);
            put(level, XE, Y0 + 2, z, z == ZB - 2 ? vent : strip, Direction.WEST);
        }
        // the two back corners, on their own bases (the corner of a facing sits at its back left)
        put(level, XW, Y0, ZB, base, Direction.EAST);
        put(level, XW, Y0 + 1, ZB, cornerIn, Direction.EAST);
        put(level, XE, Y0, ZB, base, Direction.NORTH);
        put(level, XE, Y0 + 1, ZB, cornerChamfer, Direction.NORTH);
        // flat end caps closing the open ends of the side runs
        cap(level, XW, Y0 + 1, ZB - 5, capFlat, Direction.EAST, PanelEndCapBlock.Side.RIGHT);
        cap(level, XE, Y0 + 1, ZB - 5, capFlat, Direction.WEST, PanelEndCapBlock.Side.LEFT);
        // benchboards in front of the bases: the back run, the two side runs and their inside corners
        for (int x = XW + 2; x <= XE - 2; x++) {
            put(level, x, Y0, ZB - 1, bench, Direction.NORTH);
            put(level, x, Y0, ZB - 2, apron, Direction.NORTH);
        }
        for (int z = ZB - 4; z <= ZB - 2; z++) {
            put(level, XW + 1, Y0, z, bench, Direction.EAST);
            put(level, XE - 1, Y0, z, bench, Direction.WEST);
        }
        put(level, XW + 1, Y0, ZB - 1, benchIn, Direction.EAST);
        put(level, XE - 1, Y0, ZB - 1, benchIn, Direction.NORTH);
        for (int z = ZB - 4; z <= ZB - 3; z++) {
            put(level, XW + 2, Y0, z, apron, Direction.EAST);
            put(level, XE - 2, Y0, z, apron, Direction.WEST);
        }
        cap(level, XW + 1, Y0, ZB - 5, capBench, Direction.EAST, PanelEndCapBlock.Side.RIGHT);
        cap(level, XE - 1, Y0, ZB - 5, capBench, Direction.WEST, PanelEndCapBlock.Side.LEFT);
        // skirting along the side walls beyond the runs
        for (int z = ZB - 9; z <= ZB - 6; z++) {
            put(level, XW, Y0, z, kick, Direction.EAST);
            put(level, XE, Y0, z, kick, Direction.WEST);
        }
        // a console island in front of the room, closed at both ends, with an apron
        for (int x = XW + 3; x <= XW + 6; x++) {
            put(level, x, Y0, ZB - 7, console, Direction.NORTH);
            put(level, x, Y0, ZB - 8, apron, Direction.NORTH);
        }
        cap(level, XW + 2, Y0, ZB - 7, capConsole, Direction.NORTH, PanelEndCapBlock.Side.RIGHT);
        cap(level, XW + 7, Y0, ZB - 7, capConsole, Direction.NORTH, PanelEndCapBlock.Side.LEFT);

        // the free-standing L: an outside corner, a run to the east and a run to the south, each closed by a cap
        put(level, ISLAND_X, Y0, ISLAND_Z, base, Direction.NORTH);
        put(level, ISLAND_X, Y0 + 1, ISLAND_Z, cornerOut, Direction.NORTH);
        for (int i = 1; i <= 3; i++) {
            put(level, ISLAND_X + i, Y0, ISLAND_Z, base, Direction.NORTH);
            put(level, ISLAND_X + i, Y0 + 1, ISLAND_Z, vertical, Direction.NORTH);
            put(level, ISLAND_X + i, Y0 + 2, ISLAND_Z, i == 2 ? canopy : strip, Direction.NORTH);
            put(level, ISLAND_X, Y0, ISLAND_Z + i, base, Direction.WEST);
            put(level, ISLAND_X, Y0 + 1, ISLAND_Z + i, vertical, Direction.WEST);
            put(level, ISLAND_X, Y0 + 2, ISLAND_Z + i, strip, Direction.WEST);
        }
        cap(level, ISLAND_X + 4, Y0 + 1, ISLAND_Z, capRound, Direction.NORTH, PanelEndCapBlock.Side.LEFT);
        cap(level, ISLAND_X, Y0 + 1, ISLAND_Z + 4, capFlat, Direction.WEST, PanelEndCapBlock.Side.RIGHT);
        instruments(level);
    }

    // ---- checking (client)

    /** Confirms every trim block placed drew from its own model, not the missing model, and returns "blocks/quads". */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof PanelTrimBlock)) {
                continue;
            }
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            int count = model.getQuads(state, null, RandomSource.create()).size();
            if (model == missing || count == 0) {
                wrong.add(state.toString());
            }
            quads += count;
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("Trim showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        return PLACED.size() + " blocks, " + quads + " quads";
    }
}
