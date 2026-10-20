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
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The duplex board part of the panel client check: the owner's 4 m board as four columns, gauges on the vertical
 * board, annunciators on the tilted header and switches on the bench, photographed from the front, from a three-quarter
 * view and from the side (the views of the owner's reference renders). The check confirms that every block of the
 * board drew from its own model and that every component reached the client.
 *
 * <p>Plan: the board faces north (the operator stands at smaller z); the bench of the operator's leftmost column is at
 * x 5404, z 5260, the columns run west to x 5401, the back blocks are at z 5261 and the floor is at y 125.
 */
final class PanelDuplexShowcase {
    private PanelDuplexShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final BlockPos FIRST_BENCH = new BlockPos(5404, 126, 5260);
    private static final int COLUMNS = 4;

    static final String[] NAMES = {"duplex-front", "duplex-three-quarter", "duplex-side"};
    private static final double[][] EYE = {{5403.0, 128.3, 5253.5}, {5397.6, 129.6, 5254.2}, {5394.5, 128.2, 5260.4}};
    private static final double[][] LOOK = {{5403.0, 128.1, 5261.0}, {5403.0, 128.0, 5260.6}, {5403.0, 128.0, 5260.4}};

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

    private static PanelComponent mount(ServerLevel level, BlockPos pos, int cx, int cy, int w, int h, ComponentType type,
                                        String variant, double value) {
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Duplex showcase: placement refused " + type.id() + " at " + pos + " " + cx + "," + cy);
        }
        PanelModelShowcase.look(component, PanelStyles.GE, variant);
        if (component.data() instanceof ControlSwitchData d) {
            d.spring = false;
            d.position = (int) value;
            component.type().onLoaded(component);
        } else if (component.data() instanceof AnnunciatorData d) {
            d.line1 = "PUMP " + (char) ('A' + mounted % 4);
            d.line2 = "TRIP";
            if (value > 0) {
                d.logic.update(true);
                if (value > 1) {
                    d.logic.acknowledge();
                }
            }
        } else if (component.inputCount() > 0) {
            component.setSource(0, InputSources.constant(value));
            ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
        }
        component.touch();
        mounted++;
        return component;
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        for (int x = 5392; x <= 5412; x++) {
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
        // The vertical board: a row of gauges on each of its two rows (64 x 32 cells over the four columns).
        double[] readings = {68, 30, 55, 80, 45, 62, 25, 74};
        for (int c = 0; c < COLUMNS; c++) {
            mount(level, DuplexCell.BOARD_UPPER.at(bench(c), FACING), 2, 3, 12, 12, ComponentTypes.DIAL_GAUGE,
                    c % 2 == 0 ? "standard" : "red_pointer", readings[c]);
            mount(level, DuplexCell.BOARD_LOWER.at(bench(c), FACING), 2, 1, 12, 12, ComponentTypes.DIAL_GAUGE,
                    c % 2 == 0 ? "red_pointer" : "standard", readings[c + 4]);
        }
        // The tilted header: two rows of annunciator windows per column, some in alarm, some acknowledged.
        for (int c = 0; c < COLUMNS; c++) {
            BlockPos header = DuplexCell.HEADER.at(bench(c), FACING);
            mount(level, header, 2, 0, 12, 8, ComponentTypes.ANNUNCIATOR, null, (c % 3 == 0) ? 1 : 0);
            mount(level, header, 2, 8, 12, 8, ComponentTypes.ANNUNCIATOR, null, (c % 2 == 1) ? 2 : 0);
        }
        // The bench: two control switches per column, in all three positions.
        for (int c = 0; c < COLUMNS; c++) {
            mount(level, bench(c), 0, 2, 8, 12, ComponentTypes.CONTROL_SWITCH, "pistol", (c * 2) % 3 - 1);
            mount(level, bench(c), 8, 2, 8, 12, ComponentTypes.CONTROL_SWITCH, "pistol", (c * 2 + 1) % 3 - 1);
        }
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /** Every block of the board drew from its own model, and every component is on the client: "blocks/quads/components". */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof DuplexPanelBlock)) {
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
            throw new AssertionError("Duplex showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != mounted) {
            throw new AssertionError("Duplex showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        return PLACED.size() + " blocks, " + quads + " quads, " + components + " components";
    }
}
