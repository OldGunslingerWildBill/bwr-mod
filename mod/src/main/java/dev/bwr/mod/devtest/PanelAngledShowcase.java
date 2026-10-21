package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
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
 * The angled trim part of the panel client check: real-size annunciator windows on a run of header strips and
 * canopies (the 5 degree lean) over a vertical board, and real-size gauges on the 45 degree chamfer corners of a
 * U-shaped room and on a bench chamfer between two benchboards. The check confirms that every block drew from its own
 * model and that every component reached the client.
 *
 * <p>Plan (all facing the operator at smaller z unless noted; the floor is at y 125):
 * <ul>
 *   <li>header run: five vertical boards three high at x 5700 to 5704, z 5310 (columns run west), five header blocks
 *       on top (strip, strip, canopy, strip, strip);</li>
 *   <li>U room: south wall z 5330 (x 5720 to 5724), east wall x 5725 and west wall x 5719 (z 5325 to 5329), open to
 *       the north, a stack of three vertical chamfers in each of the two south corners;</li>
 *   <li>bench corner: benchboards at x 5741 to 5743, z 5330 and at x 5744, z 5327 to 5329, a bench chamfer between
 *       them at x 5744, z 5330.</li>
 * </ul>
 */
final class PanelAngledShowcase {
    private PanelAngledShowcase() {
    }

    private static final Direction NORTH = Direction.NORTH;
    private static final int FLOOR = 125;

    static final String[] NAMES = {"angled-header-front", "angled-header-oblique", "angled-header-closeup",
            "angled-room", "angled-chamfer-closeup", "angled-chamfer-oblique", "angled-bench-chamfer"};
    private static final double[][] EYE = {
            {5702.5, 129.4, 5304.0}, {5707.5, 130.4, 5306.5}, {5702.5, 129.6, 5308.0},
            {5722.5, 128.3, 5320.5}, {5723.2, 127.6, 5328.2}, {5721.5, 128.0, 5325.0},
            {5742.9, 128.1, 5328.9}};
    private static final double[][] LOOK = {
            {5702.5, 129.5, 5310.8}, {5702.0, 129.4, 5310.8}, {5702.5, 129.5, 5310.8},
            {5722.5, 127.2, 5330.8}, {5725.5, 127.5, 5330.5}, {5725.3, 127.5, 5330.3},
            {5744.33, 126.67, 5330.33}};

    static boolean night(int stage) {
        return false;
    }

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
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
            {"GEN", "LOCKOUT"}, {"ROD", "DRIFT"}, {"CRD", "ACCUM LOW"}, {"SCRAM", "RESET"}};

    private static PanelComponent real(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type) {
        Footprint size = type.footprints().get(type.realDefaultIndex());
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, size);
        if (component == null) {
            throw new AssertionError("Angled showcase: placement refused " + type.id() + " " + size.w() + "x" + size.h() + " at "
                    + pos + " " + cx + "," + cy);
        }
        mounted++;
        index++;
        return component;
    }

    private static void reading(ServerLevel level, BlockPos pos, PanelComponent component, double value) {
        component.setSource(0, InputSources.constant(value));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
    }

    private static void put(ServerLevel level, BlockPos pos, Block block, Direction facing) {
        level.setBlock(pos, block.defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
        PLACED.add(pos);
    }

    private static void ground(ServerLevel level, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, FLOOR, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = FLOOR + 1; y <= FLOOR + 9; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** A grid of real-size dials on whatever of it the face takes: how many went on. */
    private static int dials(ServerLevel level, BlockPos pos, int pitch, int rows, int columns, int firstX, int firstY) {
        int placed = 0;
        ComponentType type = ComponentTypes.DIAL_GAUGE;
        Footprint size = type.footprints().get(type.realDefaultIndex());
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                PanelComponent dial = PanelOps.placeDirect(level, pos, firstX + column * pitch, firstY + row * pitch, type, size);
                if (dial == null) {
                    continue;
                }
                mounted++;
                index++;
                placed++;
                PanelModelShowcase.look(dial, PanelStyles.GE, (row + column) % 3 == 0 ? "red_pointer" : "standard");
                reading(level, pos, dial, 18 + (index * 37) % 70);
            }
        }
        return placed;
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        index = 0;
        buildHeader(level);
        buildRoom(level);
        buildBenchCorner(level);
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    private static void buildHeader(ServerLevel level) {
        ground(level, 5694, 5712, 5296, 5316);
        // five columns at x 5704 .. 5700 (columns run west for a north-facing board); three board rows, header on top
        for (int c = 0; c < 5; c++) {
            int x = 5704 - c;
            for (int row = 0; row < 3; row++) {
                BlockPos pos = new BlockPos(x, FLOOR + 1 + row, 5310);
                put(level, pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get(), NORTH);
            }
        }
        for (int c = 0; c < 5; c++) {
            for (int row = 0; row < 3; row++) {
                BlockPos pos = new BlockPos(5704 - c, FLOOR + 1 + row, 5310);
                dials(level, pos, 5, 2, 3, 0, 1 + row);
            }
        }
        Block[] run = {BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get(), BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get(),
                BwrBlocks.CONTROL_PANEL_HEADER_CANOPY.get(), BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get(),
                BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get()};
        for (int c = 0; c < 5; c++) {
            BlockPos pos = new BlockPos(5704 - c, FLOOR + 4, 5310);
            put(level, pos, run[c], NORTH);
            boolean canopy = run[c] == BwrBlocks.CONTROL_PANEL_HEADER_CANOPY.get();
            for (int row = canopy ? 1 : 0; row < (canopy ? 13 : 14); row++) {
                for (int i = 0; i < 16; i++) {
                    PanelComponent tile = real(level, pos, i, row, ComponentTypes.ANNUNCIATOR);
                    AnnunciatorData d = (AnnunciatorData) tile.data();
                    String[] text = ALARMS[(c * 53 + row * 16 + i) % ALARMS.length];
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
    }

    private static void buildRoom(ServerLevel level) {
        ground(level, 5712, 5732, 5316, 5336);
        Block board = BwrBlocks.CONTROL_PANEL_VERTICAL.get();
        Block chamfer = BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_CHAMFER.get();
        for (int row = 0; row < 3; row++) {
            int y = FLOOR + 1 + row;
            for (int x = 5720; x <= 5724; x++) {
                put(level, new BlockPos(x, y, 5330), board, Direction.NORTH);
            }
            for (int z = 5325; z <= 5329; z++) {
                put(level, new BlockPos(5725, y, z), board, Direction.WEST);
                put(level, new BlockPos(5719, y, z), board, Direction.EAST);
            }
            put(level, new BlockPos(5725, y, 5330), chamfer, Direction.NORTH);
            put(level, new BlockPos(5719, y, 5330), chamfer, Direction.EAST);
        }
        // gauges on the walls (a few) and a full grid on the facets of the chamfers
        for (int x = 5720; x <= 5724; x++) {
            for (int row = 0; row < 3; row++) {
                dials(level, new BlockPos(x, FLOOR + 1 + row, 5330), 5, 1, 3, 0, 5);
            }
        }
        for (int row = 0; row < 3; row++) {
            int y = FLOOR + 1 + row;
            for (BlockPos pos : new BlockPos[] {new BlockPos(5725, y, 5330), new BlockPos(5719, y, 5330)}) {
                int placed = dials(level, pos, 3, 5, 4, 0, 0);
                if (placed != 20) {
                    throw new AssertionError("Angled showcase: " + placed + " of 20 dials fit the chamfer at " + pos);
                }
            }
        }
    }

    private static void buildBenchCorner(ServerLevel level) {
        ground(level, 5734, 5752, 5320, 5336);
        Block bench = BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
        for (int x = 5741; x <= 5743; x++) {
            put(level, new BlockPos(x, FLOOR + 1, 5330), bench, Direction.NORTH);
        }
        for (int z = 5327; z <= 5329; z++) {
            put(level, new BlockPos(5744, FLOOR + 1, z), bench, Direction.WEST);
        }
        BlockPos corner = new BlockPos(5744, FLOOR + 1, 5330);
        put(level, corner, BwrBlocks.CONTROL_PANEL_CORNER_BENCH_CHAMFER.get(), Direction.NORTH);
        int placed = dials(level, corner, 4, 4, 4, 0, 0);
        if (placed < 6) {
            throw new AssertionError("Angled showcase: only " + placed + " dials fit the bench chamfer");
        }
    }

    // ---- checking (client)

    /** Every block drew from its own model and every component is on the client: "blocks/quads/components". */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof PanelBlock)) {
                wrong.add(pos + " is " + state);
                continue;
            }
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            int count = model.getQuads(state, null, RandomSource.create()).size();
            if (model == missing || count == 0) {
                wrong.add(state.toString());
            }
            quads += count;
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
            }
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("Angled showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != mounted) {
            throw new AssertionError("Angled showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        return PLACED.size() + " blocks, " + quads + " quads, " + components + " components";
    }
}
