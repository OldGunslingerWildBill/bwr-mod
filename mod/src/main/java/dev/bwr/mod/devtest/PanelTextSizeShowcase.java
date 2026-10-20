package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.model.GaugeScale;
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
 * The gauges' Text size and Tick size sliders in the panel client check ({@code GaugeScale}): the same gauges side by
 * side at the default sizes, text 60 % and 160 %, ticks 60 % and 160 %, five columns left to right in that order.
 * <ul>
 *   <li>One block of real size gauges, a column per setting: a GE dial (2 x 2), a bar gauge and a GE edgewise meter
 *       (1 x 3 each), a GE horizontal meter (3 x 1), then the classic looks of the dial and the two edgewise meters.</li>
 *   <li>Five blocks to its right, one per setting, each a 8 x 16 bar gauge and a 6 x 16 GE edgewise meter.</li>
 * </ul>
 * Stages: the real size block from a block away, a close-up of its bar gauges and edgewise meters, and the big row.
 *
 * <p>Plan: the real size block at x 5490, the big row at x 5486 down to 5482 (left to right as seen from the north),
 * all at y 127 and z 5261, facing north, on a floor at y 125. The area x 5476..5494, z 5250..5266 is cleared first.
 */
final class PanelTextSizeShowcase {
    private PanelTextSizeShowcase() {
    }

    static final String[] NAMES = {"textsize-real", "textsize-real-bars", "textsize-big"};
    private static final BlockPos REAL = new BlockPos(5490, 127, 5261);
    private static final BlockPos FIRST_BIG = new BlockPos(5486, 127, 5261);
    /** The five settings, left to right: {text %, tick %}. */
    static final int[][] SETTINGS = {{100, 100}, {60, 100}, {160, 100}, {100, 60}, {100, 160}};
    private static final double[][] EYE = {{5490.5, 127.5, 5260.9}, {5490.5, 127.72, 5261.2}, {5484.5, 127.5, 5259.3}};
    private static final double[][] LOOK = {{5490.5, 127.5, 5262.0}, {5490.5, 127.72, 5262.0}, {5484.5, 127.5, 5262.0}};

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static int mounted;

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    private static PanelComponent put(ServerLevel level, BlockPos pos, int x, int y, ComponentType type, int w, int h, String style,
                                      int setting, double reading) {
        PanelComponent component = PanelOps.placeDirect(level, pos, x, y, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Text size showcase: placement refused " + type.id() + " " + w + "x" + h + " at " + pos + " " + x + "," + y);
        }
        GaugeData data = (GaugeData) component.data();
        if (style != null) {
            data.style = style;
            data.variant = PanelStyles.defaultVariant(data.type, style);
        }
        if (!data.isBarGauge()) {
            data.units = "MPa";
        }
        data.textSize = SETTINGS[setting][0];
        data.tickSize = SETTINGS[setting][1];
        component.setLabel(type == ComponentTypes.BAR_GAUGE ? "BG-" + (setting + 1) : component.label());
        component.setSource(0, InputSources.constant(reading));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
        component.touch();
        mounted++;
        return component;
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        mounted = 0;
        for (int x = 5476; x <= 5494; x++) {
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
        for (int i = 0; i < SETTINGS.length; i++) {
            blocks.add(FIRST_BIG.west(i));
        }
        for (BlockPos pos : blocks) {
            level.setBlock(pos, panel, 3);
            level.setBlock(pos.below(), panel, 3);
            PLACED.add(pos);
        }
        for (int s = 0; s < SETTINGS.length; s++) {
            int x = s * 3;
            put(level, REAL, x, 0, ComponentTypes.DIAL_GAUGE, 2, 2, PanelStyles.GE, s, 62);
            put(level, REAL, x, 3, ComponentTypes.BAR_GAUGE, 1, 3, null, s, 41);
            put(level, REAL, x + 2, 3, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 1, 3, PanelStyles.GE, s, 55);
            put(level, REAL, x, 7, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 3, 1, PanelStyles.GE, s, 70);
            put(level, REAL, x, 9, ComponentTypes.DIAL_GAUGE, 2, 2, PanelStyles.CLASSIC, s, 62);
            put(level, REAL, x, 12, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 1, 3, PanelStyles.CLASSIC, s, 55);
            put(level, REAL, x, 15, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 3, 1, PanelStyles.CLASSIC, s, 70);
            BlockPos big = FIRST_BIG.west(s);
            put(level, big, 0, 0, ComponentTypes.BAR_GAUGE, 8, 16, null, s, 41);
            put(level, big, 9, 0, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 6, 16, PanelStyles.GE, s, 55);
        }
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    /** Every component is on the client with its sizes: "blocks, components". */
    static String verify(Minecraft mc, int stage) {
        int components = 0;
        int sized = 0;
        for (BlockPos pos : PLACED) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                for (PanelComponent c : be.owned()) {
                    components++;
                    if (c.data() instanceof GaugeData d && (d.textSize != GaugeScale.DEFAULT_PERCENT || d.tickSize != GaugeScale.DEFAULT_PERCENT)) {
                        sized++;
                    }
                }
            }
        }
        if (components != mounted || sized != mounted / SETTINGS.length * (SETTINGS.length - 1)) {
            throw new AssertionError("Text size showcase " + NAMES[stage] + ": " + components + " of " + mounted
                    + " components on the client, " + sized + " with a size set");
        }
        return PLACED.size() + " blocks, " + components + " components";
    }
}
