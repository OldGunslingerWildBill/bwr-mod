package dev.bwr.mod.panel.component;

import java.util.List;

/**
 * One editable setting of a component type: the schema half of "config schema
 * and serialization". The wrench screen is generated from the list of fields a
 * type declares, and the server validates every incoming value against the same
 * list ({@link ConfigSchema#merge}), so a type never has to hand-write either.
 *
 * <p>Values live in the component's {@link ComponentData} and travel as an NBT
 * compound keyed by {@link #key()}:
 * <ul>
 *   <li>{@link Kind#TEXT}: string, at most {@link #maxLength()} chars</li>
 *   <li>{@link Kind#COLOR}: int {@code 0xRRGGBB}</li>
 *   <li>{@link Kind#BOOL}: boolean</li>
 *   <li>{@link Kind#INT}: int in {@code [min, max]}</li>
 *   <li>{@link Kind#DOUBLE}: finite double in {@code [min, max]}</li>
 *   <li>{@link Kind#ENUM}: string, one of {@link #options()}</li>
 *   <li>{@link Kind#SLIDER}: int in {@code [min, max]} set with a slider, {@link #defaultValue()} when the data does not
 *       hold one (a slider setting is saved only when it is not its default)</li>
 * </ul>
 * A colour field may also carry named choices ({@link #color(String, String, List)}): {@link #options()} then holds
 * {@code name=RRGGBB} swatches the wrench screen offers beside the colour box.
 *
 * @param key       NBT key; also the suffix of the translation key
 *                  {@code component.bwr.field.<key>} unless {@code labelKey} is set
 * @param labelKey  translation key for the screen label
 * @param defaultValue what a {@link Kind#SLIDER} reads when the data has no value for it; 0 for every other kind
 */
public record ConfigField(String key, Kind kind, String labelKey, double min, double max,
                          int maxLength, List<String> options, double defaultValue) {
    public enum Kind { TEXT, COLOR, BOOL, INT, DOUBLE, ENUM, SLIDER }

    public ConfigField(String key, Kind kind, String labelKey, double min, double max, int maxLength, List<String> options) {
        this(key, kind, labelKey, min, max, maxLength, options, 0.0);
    }

    /** The choice a colour with swatches shows when its colour is none of them; its name is {@code component.bwr.swatch.custom}. */
    public static final String SWATCH_CUSTOM = "custom";

    public static ConfigField text(String key, String labelKey, int maxLength) {
        return new ConfigField(key, Kind.TEXT, labelKey, 0, 0, maxLength, List.of());
    }

    public static ConfigField color(String key, String labelKey) {
        return new ConfigField(key, Kind.COLOR, labelKey, 0, 0xFFFFFF, 0, List.of());
    }

    /**
     * A colour with named choices: {@code swatches} are {@code name=RRGGBB} entries (see {@link #swatch}), shown in the
     * wrench screen as {@code component.bwr.swatch.<name>}. The value is still any colour and is saved exactly as a
     * typed code would be: picking a name only fills its code in.
     */
    public static ConfigField color(String key, String labelKey, List<String> swatches) {
        return new ConfigField(key, Kind.COLOR, labelKey, 0, 0xFFFFFF, 0, List.copyOf(swatches));
    }

    /** One swatch entry for {@link #color(String, String, List)}. */
    public static String swatch(String name, int rgb) {
        return name + "=" + String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    /** The names of a colour field's swatches, in order; empty for any other field. */
    public List<String> swatchNames() {
        if (kind != Kind.COLOR) {
            return List.of();
        }
        return options.stream().map(entry -> entry.substring(0, entry.indexOf('='))).toList();
    }

    /** The colour of the swatch {@code name}, or -1 when this field has no such swatch. */
    public int swatchColor(String name) {
        if (kind == Kind.COLOR) {
            for (String entry : options) {
                int at = entry.indexOf('=');
                if (entry.substring(0, at).equals(name)) {
                    return Integer.parseInt(entry.substring(at + 1), 16);
                }
            }
        }
        return -1;
    }

    /** The name of the swatch whose colour is {@code rgb}, or null when none is. */
    public String swatchName(int rgb) {
        for (String name : swatchNames()) {
            if (swatchColor(name) == (rgb & 0xFFFFFF)) {
                return name;
            }
        }
        return null;
    }

    public static ConfigField bool(String key, String labelKey) {
        return new ConfigField(key, Kind.BOOL, labelKey, 0, 1, 0, List.of());
    }

    public static ConfigField integer(String key, String labelKey, int min, int max) {
        return new ConfigField(key, Kind.INT, labelKey, min, max, 0, List.of());
    }

    public static ConfigField decimal(String key, String labelKey, double min, double max) {
        return new ConfigField(key, Kind.DOUBLE, labelKey, min, max, 0, List.of());
    }

    public static ConfigField choice(String key, String labelKey, List<String> options) {
        return new ConfigField(key, Kind.ENUM, labelKey, 0, options.size() - 1, 0, List.copyOf(options));
    }

    /**
     * A whole number from {@code min} to {@code max} in steps of {@code step}, set with a slider in the wrench screen and
     * reading {@code defaultValue} when the data does not hold it. The step is kept in {@link #maxLength()}.
     */
    public static ConfigField slider(String key, String labelKey, int min, int max, int step, int defaultValue) {
        return new ConfigField(key, Kind.SLIDER, labelKey, min, max, Math.max(1, step), List.of(), defaultValue);
    }

    /** The step of a {@link Kind#SLIDER}; 1 for any other field. */
    public int step() {
        return kind == Kind.SLIDER ? Math.max(1, maxLength) : 1;
    }
}
