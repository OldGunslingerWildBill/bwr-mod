package dev.bwr.mod.panel.component;

import java.util.List;
import java.util.Locale;

/**
 * Which sound an annunciator window's horn is, as pure text rules (no Minecraft types, so it is tested directly). A
 * window's <b>Alarm sound</b> is the panel alarm (the default), the synthesised horn shipped with the mod, or a custom
 * sound named by its id, meant for a sound a resource pack adds. Whatever cannot be played as chosen is the panel
 * alarm: a custom id that is not an id, custom sounds switched off, and (the client's part, it alone knows its sounds)
 * an id its sound manager has never heard of. A horn is never silent because of its setting.
 */
public final class AnnunciatorSounds {
    public static final String PANEL = "panel";
    public static final String SYNTH = "synth";
    public static final String CUSTOM = "custom";
    /** The choices of the wrench screen; the first is the default. */
    public static final List<String> CHOICES = List.of(PANEL, SYNTH, CUSTOM);
    /** The sound ids of the two shipped horns. */
    public static final String PANEL_ID = "bwr:annunciator_horn";
    public static final String SYNTH_ID = "bwr:annunciator_horn_synth";
    /** The longest custom sound id. */
    public static final int MAX_ID = 64;

    private AnnunciatorSounds() {
    }

    /** A typed id as it is kept: without blanks at either end, in lower case, at most {@link #MAX_ID} characters. */
    public static String cleanId(String raw) {
        String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return id.length() <= MAX_ID ? id : id.substring(0, MAX_ID);
    }

    /**
     * Whether {@code id} is a sound id as the game writes them, {@code namespace:path}: one colon, a namespace of
     * {@code a-z 0-9 _ . -} and a path of those and {@code /}, neither empty, at most {@link #MAX_ID} characters.
     */
    public static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID) {
            return false;
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean plain = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '.' || c == '-';
            if (i != colon && !plain && !(c == '/' && i > colon)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The sound id a window's setting stands for: the panel alarm, the synthesised horn, or the custom id when it is
     * {@linkplain #validId an id} and custom sounds are allowed. Anything else, an unknown choice included, is the
     * panel alarm. Never null.
     */
    public static String resolve(String choice, String custom, boolean customAllowed) {
        if (SYNTH.equals(choice)) {
            return SYNTH_ID;
        }
        if (CUSTOM.equals(choice) && customAllowed && validId(custom)) {
            return custom;
        }
        return PANEL_ID;
    }
}
