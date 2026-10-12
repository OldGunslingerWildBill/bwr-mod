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
 * </ul>
 *
 * @param key       NBT key; also the suffix of the translation key
 *                  {@code component.bwr.field.<key>} unless {@code labelKey} is set
 * @param labelKey  translation key for the screen label
 */
public record ConfigField(String key, Kind kind, String labelKey, double min, double max,
                          int maxLength, List<String> options) {
    public enum Kind { TEXT, COLOR, BOOL, INT, DOUBLE, ENUM }

    public static ConfigField text(String key, String labelKey, int maxLength) {
        return new ConfigField(key, Kind.TEXT, labelKey, 0, 0, maxLength, List.of());
    }

    public static ConfigField color(String key, String labelKey) {
        return new ConfigField(key, Kind.COLOR, labelKey, 0, 0xFFFFFF, 0, List.of());
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
}
