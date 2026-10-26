package dev.bwr.mod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only choice; no server physics or network field depends on it. */
public final class VisualEffectsConfig {
    public enum Mode { AUTO, SHADER, CLASSIC }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.EnumValue<Mode> VISUAL_EFFECTS = BUILDER
            .comment("GPU visual effects: AUTO chooses the measured default, SHADER requests GPU effects, CLASSIC uses the original renderers.")
            .defineEnum("visualEffects", Mode.AUTO);
    public static final ModConfigSpec.BooleanValue PANEL_GPU_CACHE = BUILDER
            .comment("Control panels keep the solid geometry of each panel block in a vertex buffer on the GPU, which is much faster with detailed component models. Turn off if panels draw wrongly with a render mod or shader pack (the buffers are drawn through the game's own entity render type, so a pack's program shades them like any other block entity).")
            .define("panelGpuCache", true);
    public static final ModConfigSpec.IntValue NETWORK_LINE_VIEW_DISTANCE = BUILDER
            .comment("How far away, in blocks, other players' Networking Wrench lines are drawn for you. Lower it, or set 0 to hide them. The smaller of this and the server's networkLineShareDistance applies.")
            .defineInRange("networkLineViewDistance", 32, 0, 1024);
    public static final ModConfigSpec.BooleanValue PANEL_TEXT_FADE = BUILDER
            .comment("Printed text on control panels fades out with distance: once a glyph is only a few screen pixels tall it turns to speckle, so it is replaced by a soft strip in the text's colour, like ink seen from afar. Turn off to always draw the text, however small.")
            .define("panelTextFade", true);
    public static final ModConfigSpec.EnumValue<dev.bwr.core.display.DisplayUnits.Mode> DISPLAY_UNITS = BUILDER
            .comment("Units on the screens' readouts (display only: the physics, saved data, network and computer values never change). METRIC is what the screens have always shown, and is the default. IMPERIAL: F, lb/s, gpm for water, ft, gal, lb. PLANT: the US control-room convention, F, Mlb/hr for steam and feed flow, gpm for pumps, inches for water level, MWt and MWe. SI: degrees C, kPa(g) and kPa(a) (MPa above 1,000 kPa), metres, kg/s, m3/h for water. Control panel gauges keep their own scales.")
            .defineEnum("displayUnits", dev.bwr.core.display.DisplayUnits.Mode.METRIC);
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The units the readouts show; METRIC (today's text) until the file is loaded. */
    public static dev.bwr.core.display.DisplayUnits.Mode displayUnits() {
        return SPEC.isLoaded() ? DISPLAY_UNITS.get() : dev.bwr.core.display.DisplayUnits.Mode.METRIC;
    }

    /** Whether panel text fades with distance; on until the file is loaded. */
    public static boolean panelTextFade() {
        return !SPEC.isLoaded() || PANEL_TEXT_FADE.get();
    }

    /** The configured view distance; the default before the file is loaded. */
    public static int networkLineViewDistance() {
        return SPEC.isLoaded() ? NETWORK_LINE_VIEW_DISTANCE.get() : 32;
    }

    private VisualEffectsConfig() {}
}
