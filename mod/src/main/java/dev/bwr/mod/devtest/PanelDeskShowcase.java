package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.DeskHalf;
import dev.bwr.mod.panel.DeskPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
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
 * The control desk part of the panel client check: the owner's 1 m and 2 m desks, each empty and with parts mounted, in
 * seafoam and in tan, a run of a 1 m, a 2 m and a 1 m desk in light blue (one face, with parts across the seams), and the
 * side of a desk for its profile. Every picture is 1920 x 1080 (see {@link CheckShots}). The check confirms that every
 * desk block drew from its model and that every part reached the client.
 *
 * <p>Plan: every desk faces north (the operator stands at smaller z), the floor is at y 125 and the desks stand on it:
 * the seafoam row at z 5262 has a 1 m desk (empty) at x 5800, a 1 m desk (loaded) at x 5802, a 2 m desk (empty) at
 * x 5805 and 5806 and a 2 m desk (loaded) at x 5808 and 5809; the tan row is the same at z 5254; the light blue run is a
 * 1 m desk at x 5800, a 2 m desk at x 5801 and 5802 and a 1 m desk at x 5803, at z 5258, and a lone seafoam 1 m desk at x 5815 for the side view. The left half of a 2 m desk is
 * the block to the operator's left, which is east for a desk facing north.
 */
final class PanelDeskShowcase {
    private PanelDeskShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final int SEAFOAM_Z = 5262;
    private static final int TAN_Z = 5254;
    private static final int RUN_Z = 5258;

    static final String[] NAMES = {"desk-1m-seafoam", "desk-2m-seafoam", "desk-1m-tan", "desk-2m-tan", "desk-run-light-blue",
            "desk-side-seafoam"};
    // eye position and look target of each picture (feet are 1.62 below the eye)
    private static final double[][] EYE = {{5801.5, 127.6, 5260.6}, {5807.5, 127.9, 5260.0}, {5801.5, 127.6, 5252.6},
            {5807.5, 127.9, 5252.0}, {5802.0, 127.7, 5256.2}, {5817.8, 127.9, 5258.2}};
    private static final double[][] LOOK = {{5801.5, 126.25, 5262.7}, {5807.5, 126.25, 5262.7}, {5801.5, 126.25, 5254.7},
            {5807.5, 126.25, 5254.7}, {5802.0, 126.25, 5258.7}, {5815.5, 126.3, 5258.7}};

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

    private static void desk(ServerLevel level, int x, int z, PanelFinish finish) {
        BlockPos pos = new BlockPos(x, 126, z);
        level.setBlock(pos, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, FACING)
                .setValue(PanelFinish.FINISH, finish), 3);
        PLACED.add(pos);
    }

    private static void wide(ServerLevel level, int x, int z, PanelFinish finish) {
        BlockPos right = new BlockPos(x, 126, z);
        BlockState state = BwrBlocks.CONTROL_PANEL_DESK_WIDE.get().defaultBlockState().setValue(PanelBlock.FACING, FACING)
                .setValue(PanelFinish.FINISH, finish);
        for (DeskHalf half : DeskHalf.values()) {
            BlockPos at = half.at(right, FACING);
            level.setBlock(at, state.setValue(DeskPanelBlock.HALF, half), 3);
            PLACED.add(at);
        }
    }

    private static PanelComponent real(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type) {
        Footprint size = type.footprints().get(type.realDefaultIndex());
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, size);
        if (component == null) {
            throw new AssertionError("Desk showcase: placement refused " + type.id() + " " + size.w() + "x" + size.h() + " at "
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

    private static final String[][] ALARMS = {{"RPV LEVEL", "LOW L3"}, {"RPV PRESS", "HIGH"}, {"MSIV", "NOT OPEN"},
            {"RECIRC A", "TRIP"}, {"RFP A", "TRIP"}, {"HPCS", "INITIATED"}, {"APRM", "UPSCALE"}, {"TURB", "TRIP"}};

    /**
     * One 16 x 6 block of desk, filled: a row of annunciator windows, then dials, selectors, a key switch, lamps and
     * buttons. {@code seam} 1 is the left block of a pair and puts a horizontal edgewise meter across the seam (6 columns,
     * 2 rows, the last 4 columns on the block to the operator's right); {@code seam} 2 is that block, with lamps where its
     * first dial would be.
     */
    private static void loadBlock(ServerLevel level, BlockPos pos, int c, int seam) {
        for (int i = 0; i < 8; i++) {
            PanelComponent tile = real(level, pos, i * 2, 0, ComponentTypes.ANNUNCIATOR);
            AnnunciatorData d = (AnnunciatorData) tile.data();
            String[] text = ALARMS[(c * 3 + i) % ALARMS.length];
            d.line1 = text[0];
            d.line2 = text[1];
            int state = (i * 3 + c) % 5;
            if (state == 0) {
                d.logic.update(true);
            } else if (state == 1) {
                d.logic.update(true);
                d.logic.acknowledge();
            }
            tile.touch();
        }
        for (int i = seam == 2 ? 1 : 0; i < 2; i++) {
            PanelComponent dial = real(level, pos, i * 4, 2, ComponentTypes.DIAL_GAUGE);
            PanelModelShowcase.look(dial, PanelStyles.GE, (i + c) % 2 == 0 ? "standard" : "red_pointer");
            reading(level, pos, dial, 22 + (index * 37) % 65);
        }
        if (seam == 2) {
            for (int i = 0; i < 2; i++) {
                lamp(level, pos, i * 2, 2, c + i);
            }
        }
        for (int i = 0; i < 2; i++) {
            real(level, pos, 8 + i * 2, 2, ComponentTypes.ROTARY_SELECTOR);
        }
        real(level, pos, 12, 2, ComponentTypes.KEY_SWITCH);
        lamp(level, pos, 14, 2, c);
        if (seam == 1) {
            PanelComponent strip = real(level, pos, 14, 4, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL);
            reading(level, pos, strip, 64);
        } else {
            lamp(level, pos, 14, 4, c + 1);
        }
    }

    private static void lamp(ServerLevel level, BlockPos pos, int cx, int cy, int n) {
        PanelComponent lamp = real(level, pos, cx, cy, ComponentTypes.LAMP);
        ((LampData) lamp.data()).color = n % 2 == 0 ? 0xE02020 : 0x20C040;
        reading(level, pos, lamp, 1);
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        index = 0;
        for (int x = 5788; x <= 5818; x++) {
            for (int z = 5246; z <= 5268; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 133; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int row = 0; row < 2; row++) {
            int z = row == 0 ? SEAFOAM_Z : TAN_Z;
            PanelFinish finish = row == 0 ? PanelFinish.SEAFOAM : PanelFinish.TAN;
            desk(level, 5800, z, finish);
            desk(level, 5802, z, finish);
            wide(level, 5805, z, finish);
            wide(level, 5808, z, finish);
            loadBlock(level, new BlockPos(5802, 126, z), row, 0);
            loadBlock(level, DeskHalf.LEFT.at(new BlockPos(5808, 126, z), FACING), row + 1, 1);
            loadBlock(level, new BlockPos(5808, 126, z), row + 2, 2);
        }
        // the run: one face of a 1 m, a 2 m and a 1 m desk, with parts across both seams
        desk(level, 5800, RUN_Z, PanelFinish.LIGHT_BLUE);
        wide(level, 5801, RUN_Z, PanelFinish.LIGHT_BLUE);
        desk(level, 5803, RUN_Z, PanelFinish.LIGHT_BLUE);
        for (int x = 5800; x <= 5803; x++) {
            // east is the operator's left: 5803 and 5801 are the left blocks of their pairs
            loadBlock(level, new BlockPos(x, 126, RUN_Z), x - 5800, x % 2 == 1 ? 1 : 2);
        }
        desk(level, 5815, RUN_Z, PanelFinish.SEAFOAM); // alone, for the side view
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /** Every desk block drew from its own model and every part is on the client: "blocks, quads, components". */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof PanelBlock block) || block.panelType(state) != dev.bwr.mod.panel.PanelType.DESK) {
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
            throw new AssertionError("Desk showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != mounted) {
            throw new AssertionError("Desk showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        return PLACED.size() + " blocks, " + quads + " quads, " + components + " components";
    }
}
