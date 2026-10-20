package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorControlData;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
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
 * The annunciator box restyle and the text fade in the panel client check.
 * <ul>
 *   <li>One block of real size windows (1 x 1 cell, 6 cm): an 8 x 4 box of four line legends in white, amber and red, lit
 *       steady, flashing and dark, with the control module (2 x 1) under it; a box of 2 x 2 windows with the 4 x 2 module; a
 *       lone window and an L of three, to show the frame of a window on its own and the inside corner.</li>
 *   <li>A box of 4 x 3 big windows (12 x 8 cells), three blocks wide, with the 24 x 6 module under it: the shape of the
 *       reference photos.</li>
 *   <li>The same real size box with the TEST button held (every window lit).</li>
 *   <li>The real size duplex board of {@link PanelRealScaleShowcase} from 6 and from 14 blocks, with the text fade on and
 *       off (a stage whose name ends in {@code -nofade} turns the client option off).</li>
 * </ul>
 *
 * <p>Plan: the real size block at x 5518, the big box at x 5516 down to 5514 (left to right as seen from the north) with
 * its lower row below, the test box at x 5512, all at y 127 and z 5261 facing north, on a floor at y 125. The area
 * x 5504..5522, z 5250..5266 is cleared first.
 */
final class PanelAnnunciatorShowcase {
    private PanelAnnunciatorShowcase() {
    }

    static final String[] NAMES = {"annun-box-real-close", "annun-box-real", "annun-box-big", "annun-box-test",
            "annun-mid-fade", "annun-mid-nofade", "annun-far-fade", "annun-far-nofade"};
    private static final BlockPos REAL = new BlockPos(5518, 127, 5261);
    private static final BlockPos BIG = new BlockPos(5516, 127, 5261);
    private static final BlockPos TEST = new BlockPos(5512, 127, 5261);
    /** The real size duplex board's first bench (see {@link PanelRealScaleShowcase}); the fade stages look at it. */
    private static final double BOARD_X = 5443.0;
    private static final double[][] EYE = {{5518.5, 128.2, 5260.0}, {5518.5, 128.1, 5258.6}, {5514.5, 128.1, 5258.0},
            {5512.5, 128.1, 5259.0}, {BOARD_X, 128.4, 5252.0}, {BOARD_X, 128.4, 5252.0}, {BOARD_X, 128.4, 5244.0}, {BOARD_X, 128.4, 5244.0}};
    private static final double[][] LOOK = {{5518.5, 128.1, 5262.0}, {5518.5, 128.1, 5262.0}, {5514.5, 127.6, 5262.0},
            {5512.5, 128.1, 5262.0}, {BOARD_X, 128.9, 5261.0}, {BOARD_X, 128.9, 5261.0}, {BOARD_X, 128.9, 5261.0}, {BOARD_X, 128.9, 5261.0}};

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static int mounted;

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    static boolean night(int stage) {
        return false;
    }

    /** Whether the stage draws with the text fade (the client option) on. */
    static boolean fadeOn(int stage) {
        return !NAMES[stage].endsWith("-nofade");
    }

    /** Whether the stage looks at the realscale board, and so needs that showcase built. */
    static boolean looksAtBoard(int stage) {
        return NAMES[stage].contains("fade");
    }

    private static final String[][] LEGENDS = {
            {"RPV", "LEVEL", "LOW", "L3"}, {"MSIV", "A", "NOT", "OPEN"}, {"HPCS", "INIT"}, {"DRYWELL", "PRESS", "HIGH"},
            {"SRV", "OPEN"}, {"RECIRC", "PUMP A", "TRIP"}, {"APRM", "UPSCALE", "TRIP"}, {"TURBINE", "TRIP"},
            {"BUS 4B", "UNDER", "VOLT"}, {"RCIC", "RUN"}, {"GEN", "LOCKOUT"}, {"SBGT", "START"}};
    /** The colour schemes of the references: lit white, amber and red. */
    private static final int[] SCHEMES = {0xFFF6D8, 0xFFDC50, 0xFF4A32};

    /** A window: state 0 dark, 1 flashing (unacknowledged), 2 steady (acknowledged). */
    private static PanelComponent window(ServerLevel level, BlockPos pos, int x, int y, int w, int h, int scheme, int state,
                                         String[] legend) {
        PanelComponent c = PanelOps.placeDirect(level, pos, x, y, ComponentTypes.ANNUNCIATOR, Footprint.of(w, h));
        if (c == null) {
            throw new AssertionError("Annunciator showcase: placement refused " + w + "x" + h + " at " + pos + " " + x + "," + y);
        }
        AnnunciatorData d = (AnnunciatorData) c.data();
        d.color = SCHEMES[scheme % SCHEMES.length];
        d.line1 = legend.length > 0 ? legend[0] : "";
        d.line2 = legend.length > 1 ? legend[1] : "";
        d.line3 = legend.length > 2 ? legend[2] : "";
        d.line4 = legend.length > 3 ? legend[3] : "";
        c.setSource(0, InputSources.constant(state == 0 ? 0.0 : 1.0));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(c, true);
        if (state == 2) {
            d.logic.acknowledge();
        }
        c.touch();
        mounted++;
        return c;
    }

    private static PanelComponent module(ServerLevel level, BlockPos pos, int x, int y, int w, int h, int pressed) {
        PanelComponent c = PanelOps.placeDirect(level, pos, x, y, ComponentTypes.ANNUNCIATOR_CONTROL, Footprint.of(w, h));
        if (c == null) {
            throw new AssertionError("Annunciator showcase: module refused at " + pos + " " + x + "," + y);
        }
        if (pressed >= 0) {
            ((PanelBlockEntity) level.getBlockEntity(pos)).setOutput(c, pressed, 1.0);
        }
        mounted++;
        return c;
    }

    private static int stateOf(int i) {
        int n = i * 7 % 11;
        return n < 5 ? 2 : n < 7 ? 1 : 0;
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        for (int x = 5504; x <= 5522; x++) {
            for (int z = 5250; z <= 5266; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 132; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        var panel = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH);
        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(REAL);
        blocks.add(TEST);
        for (int c = 0; c < 3; c++) {
            blocks.add(BIG.west(c));
            blocks.add(BIG.west(c).below());
        }
        for (BlockPos pos : blocks) {
            level.setBlock(pos, panel, 3);
            PLACED.add(pos);
        }
        realBox(level, REAL, -1, true);
        realBox(level, TEST, AnnunciatorControlType.TEST, false);
        bigBox(level);
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    /** An 8 x 4 box of real size windows with its module, a box of four 2 x 2 windows and the 4 x 2 module, a lone window, an L. */
    private static void realBox(ServerLevel level, BlockPos pos, int pressed, boolean extras) {
        for (int i = 0; i < 32; i++) {
            window(level, pos, 4 + i % 8, 2 + i / 8, 1, 1, i * 5 % 3 == 0 ? 2 : i * 5 % 3 == 1 ? 0 : 1, stateOf(i), LEGENDS[i % LEGENDS.length]);
        }
        module(level, pos, 4, 7, 2, 1, pressed);
        if (!extras) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            window(level, pos, 4 + i * 2, 9, 2, 2, i % 3, stateOf(i + 3), LEGENDS[(i + 4) % LEGENDS.length]);
        }
        module(level, pos, 4, 12, 4, 2, -1);
        window(level, pos, 13, 2, 1, 1, 2, 1, LEGENDS[0]);
        window(level, pos, 13, 6, 1, 1, 0, 2, LEGENDS[1]);
        window(level, pos, 14, 6, 1, 1, 1, 2, LEGENDS[2]);
        window(level, pos, 13, 7, 1, 1, 2, 2, LEGENDS[3]);
    }

    /** Four columns by three rows of 12 x 8 windows, three blocks wide, the 24 x 6 module under it. */
    private static void bigBox(ServerLevel level) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                int i = row * 4 + col;
                BlockPos owner = BIG.west(col * 12 / 16);
                int x = col * 12 % 16;
                int y = row * 8;
                if (y >= 16) {
                    owner = owner.below();
                    y -= 16;
                }
                window(level, owner, x, y, 12, 8, (row + col) % 3, stateOf(i), LEGENDS[i % LEGENDS.length]);
            }
        }
        module(level, BIG.below(), 0, 10, 24, 6, -1);
    }

    /** Every component is on the client: "blocks, components". */
    static String verify(Minecraft mc, int stage) {
        int components = 0;
        int modules = 0;
        for (BlockPos pos : PLACED) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                for (PanelComponent c : be.owned()) {
                    components++;
                    if (c.data() instanceof AnnunciatorControlData) {
                        modules++;
                    }
                }
            }
        }
        if (components != mounted || modules != 4) {
            throw new AssertionError("Annunciator showcase " + NAMES[stage] + ": " + components + " of " + mounted
                    + " components on the client, " + modules + " modules");
        }
        return PLACED.size() + " blocks, " + components + " components";
    }
}
