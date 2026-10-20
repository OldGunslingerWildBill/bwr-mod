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
 * Then the gauge polish: classic dials (12 x 12 with a caption in the lower block, and in the upper block the real size 2 x 2 and 3 x 3, and
 * bar gauges, with captions, at Text size 60, 100 and 160 % in the three columns x 5481..5479), a close-up of the real size captions, and the bar gauge at 100 % beside the owner's
 * reference (references/gauges/owner-bar-gauge-text-proportions.png): the big 10 x 16 and two real sizes in the block
 * x 5478, from a block away and close up.
 *
 * <p>Plan: the real size block at x 5490, the big row at x 5486 down to 5482 (left to right as seen from the north),
 * all at y 127 and z 5261, facing north, on a floor at y 125. The area x 5476..5494, z 5250..5266 is cleared first.
 */
final class PanelTextSizeShowcase {
    private PanelTextSizeShowcase() {
    }

    static final String[] NAMES = {"textsize-real", "textsize-real-bars", "textsize-big", "textsize-classic-dials",
            "textsize-captions-real", "textsize-bar-reference", "textsize-bar-reference-real"};
    private static final BlockPos REAL = new BlockPos(5490, 127, 5261);
    private static final BlockPos FIRST_BIG = new BlockPos(5486, 127, 5261);
    /** The five settings, left to right: {text %, tick %}. */
    static final int[][] SETTINGS = {{100, 100}, {60, 100}, {160, 100}, {100, 60}, {100, 160}};
    /** The polish blocks: three dial blocks (Text size 60, 100, 160 %), left to right x 5481, 5480, 5479, and the bar gauge block. */
    private static final BlockPos FIRST_DIALS = new BlockPos(5481, 127, 5261);
    private static final BlockPos BAR_REFERENCE = new BlockPos(5478, 127, 5261);
    private static final int[] DIAL_TEXT = {60, 100, 160};
    private static final double[][] EYE = {{5490.5, 127.5, 5260.9}, {5490.5, 127.72, 5261.2}, {5484.5, 127.5, 5259.3},
            {5480.5, 126.5, 5260.2}, {5480.31, 127.7, 5261.4}, {5478.56, 127.5, 5260.9}, {5478.22, 127.9, 5261.6}};
    private static final double[][] LOOK = {{5490.5, 127.5, 5262.0}, {5490.5, 127.72, 5262.0}, {5484.5, 127.5, 5262.0},
            {5480.5, 126.5, 5262.0}, {5480.31, 127.7, 5262.0}, {5478.56, 127.5, 5262.0}, {5478.22, 127.9, 5262.0}};

    /** The gauges of the polish blocks, and how many of them have a Text size set (every dial block but the 100 % one: 10). */
    private static final int POLISH = 3 * 5 + 3;
    private static final int POLISH_SIZED = 2 * 5;
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

    /** A gauge of the polish blocks: its own Text size, caption and (for a bar gauge) scale preset. */
    private static void putPolish(ServerLevel level, BlockPos pos, int x, int y, ComponentType type, int w, int h, String style,
                                  int text, String caption, String preset, double reading) {
        PanelComponent component = PanelOps.placeDirect(level, pos, x, y, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Text size showcase: placement refused " + type.id() + " " + w + "x" + h + " at " + pos + " " + x + "," + y);
        }
        GaugeData data = (GaugeData) component.data();
        if (style != null) {
            data.style = style;
            data.variant = PanelStyles.defaultVariant(data.type, style);
        }
        if (preset != null) {
            data.apply(preset);
        } else if (!data.isBarGauge()) {
            data.units = "MPa";
        }
        data.textSize = text;
        component.setLabel(type == ComponentTypes.BAR_GAUGE ? "RFW-LI" : component.label());
        component.setCaption(caption);
        component.setSource(0, InputSources.constant(reading));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
        component.touch();
        mounted++;
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
        for (int i = 0; i < DIAL_TEXT.length; i++) {
            blocks.add(FIRST_DIALS.west(i));
        }
        blocks.add(BAR_REFERENCE);
        for (BlockPos pos : blocks) {
            level.setBlock(pos, panel, 3);
            level.setBlock(pos.below(), panel, 3);
            PLACED.add(pos);
        }
        for (int i = 0; i < DIAL_TEXT.length; i++) {
            PLACED.add(FIRST_DIALS.west(i).below());
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
        // Polish: a classic 12 x 12 dial with a caption, the real size classic dials and a real size bar gauge with captions,
        // and a big bar gauge with one, at Text size 60, 100 and 160 %.
        for (int i = 0; i < DIAL_TEXT.length; i++) {
            BlockPos pos = FIRST_DIALS.west(i);
            int text = DIAL_TEXT[i];
            // The upper block: a big bar gauge, and beside it the real size parts, each with a caption; the block below it:
            // the 12 x 12 dial, three rows down so the caption above it does not run into it.
            putPolish(level, pos, 0, 0, ComponentTypes.BAR_GAUGE, 8, 16, null, text, "RX LEVEL", null, 41);
            putPolish(level, pos, 9, 0, ComponentTypes.DIAL_GAUGE, 2, 2, PanelStyles.CLASSIC, text, "RPV", null, 62);
            putPolish(level, pos, 12, 0, ComponentTypes.DIAL_GAUGE, 3, 3, PanelStyles.CLASSIC, text, "CORE FLOW", null, 62);
            putPolish(level, pos, 9, 5, ComponentTypes.BAR_GAUGE, 1, 3, null, text, "LEVEL", null, 41);
            putPolish(level, pos.below(), 0, 3, ComponentTypes.DIAL_GAUGE, 12, 12, PanelStyles.CLASSIC, text, "PRESSURE", null, 62);
        }
        // The bar gauge at 100 % next to the owner's reference: the big 10 x 16, a real size narrow range and wide range gauge.
        putPolish(level, BAR_REFERENCE, 0, 0, ComponentTypes.BAR_GAUGE, 10, 16, null, 100, "REACTOR LEVEL NARROW RANGE", GaugeData.PRESET_NARROW, 41);
        putPolish(level, BAR_REFERENCE, 11, 0, ComponentTypes.BAR_GAUGE, 1, 3, null, 100, "NARROW", GaugeData.PRESET_NARROW, 41);
        putPolish(level, BAR_REFERENCE, 13, 0, ComponentTypes.BAR_GAUGE, 1, 3, null, 100, "WIDE", GaugeData.PRESET_WIDE, -40);
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
        // (The polish blocks have Text size 60 and 160 % on two blocks of three: 10 of their 16 gauges are sized.)
        if (components != mounted || sized != (mounted - POLISH) / SETTINGS.length * (SETTINGS.length - 1) + POLISH_SIZED) {
            throw new AssertionError("Text size showcase " + NAMES[stage] + ": " + components + " of " + mounted
                    + " components on the client, " + sized + " with a size set");
        }
        return PLACED.size() + " blocks, " + components + " components";
    }
}
