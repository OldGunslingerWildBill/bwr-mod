package dev.bwr.mod.panel.model;

import java.util.List;

/**
 * The ids and part names of the baked models of the full core display family (FULL-CORE-DISPLAY-SPEC.md section 6),
 * in one place: the style table ({@code PanelStyles}) names the selector, light and button models, the core display
 * models on the client name the tiles, modules and strips, and the tests check that every shipped file has the parts
 * the code draws. Common code, no client types. A model that is not loaded is simply not used: every one of these has
 * a procedural fallback, so the code never waits on a file.
 */
public final class FcdModels {
    private FcdModels() {
    }

    /** One square lamp tile (parts {@code bezel}, {@code upper}, {@code lower}; anchors {@code upper}, {@code lower}). */
    public static final String TILE_COLUMBIA = "fcd_tile_columbia";
    /** The blank black tile (parts {@code bezel}, {@code face}). */
    public static final String TILE_BLANK = "fcd_tile_blank";
    /**
     * Browns Ferry rod module: {@code body}, the {@code digits} window (glass, drawn here as a black plate with the
     * digits over it) and the lamps {@code accum}, {@code scram}, {@code drift}, {@code full_in}, {@code full_out},
     * {@code select}.
     */
    public static final String BF_ROD_MODULE = "fcd_bf_rod_module";
    /** Browns Ferry LPRM strip: {@code body} and the lamps {@code lvl_a} to {@code lvl_d}, listed TOP to bottom. */
    public static final String BF_LPRM_STRIP = "fcd_bf_lprm_strip";
    /** Rod select button: {@code bezel} and {@code cap} (a lamp with 0.08 inch of travel). */
    public static final String ROD_SELECT_BUTTON = "rod_select_button";
    /** Large yellow illuminated pushbutton under a clear guard: {@code bezel}, {@code cap} (travel), {@code guard} (glass). */
    public static final String GUARDED_YELLOW = "pb_guarded_yellow";
    /** 30.5 mm oiltight 3-position selector with a black knob, and with a chrome wing lever; handle pivot at the shaft. */
    public static final String SELECTOR_KNOB = "oiltight_selector_knob";
    public static final String SELECTOR_LEVER = "oiltight_selector_lever";
    /** Indicating light with a smooth dome lens, and with a ribbed one; chrome bezel; the lens is the lamp part. */
    public static final String DOME_SMOOTH = "light_dome_smooth";
    public static final String DOME_RIBBED = "light_dome_ribbed";

    /** All ten ids of the family. */
    public static final List<String> ALL = List.of(TILE_COLUMBIA, TILE_BLANK, BF_ROD_MODULE, BF_LPRM_STRIP, ROD_SELECT_BUTTON,
            GUARDED_YELLOW, SELECTOR_KNOB, SELECTOR_LEVER, DOME_SMOOTH, DOME_RIBBED);

    /** The parts the core display code draws from each of its own models (the ones it does not take through the style table). */
    public static List<String> partsDrawn(String id) {
        return switch (id) {
            case TILE_COLUMBIA -> List.of("bezel", "upper", "lower");
            case TILE_BLANK -> List.of("bezel", "face");
            case BF_ROD_MODULE -> List.of("body", "digits", "accum", "scram", "drift", "full_in", "full_out", "select");
            case BF_LPRM_STRIP -> List.of("body", "lvl_a", "lvl_b", "lvl_c", "lvl_d");
            case ROD_SELECT_BUTTON -> List.of("bezel", "cap");
            default -> List.of();
        };
    }
}
