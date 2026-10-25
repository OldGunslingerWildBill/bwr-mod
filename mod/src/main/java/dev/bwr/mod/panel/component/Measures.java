package dev.bwr.mod.panel.component;

import dev.bwr.mod.signal.Quantity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The gauge setting {@code Measures}: the quantity a gauge-like component shows. {@code ANY} (the default, null here)
 * takes every signal, exactly as before the setting existed; any other value makes the binding screen list only the
 * signals of that quantity and makes {@code WiringOps.bind} refuse the rest. It is saved only when it is not ANY, so
 * an old save loads and re-saves unchanged.
 */
public final class Measures {
    private Measures() {
    }

    public static final String KEY = "Measures";
    public static final String ANY = "ANY";

    /** The quantities a gauge can be set to measure: everything that is a number (not BOOLEAN or TEXT). */
    private static final List<Quantity> CHOICES = List.of(Quantity.LEVEL, Quantity.PRESSURE, Quantity.TEMPERATURE,
            Quantity.RATE, Quantity.FLOW, Quantity.POWER, Quantity.ENERGY, Quantity.POSITION, Quantity.PERCENT,
            Quantity.SPEED, Quantity.CHEMISTRY, Quantity.COUNT, Quantity.DOSE_RATE, Quantity.VIBRATION);

    /** The option strings of the config field: ANY first, then each quantity. */
    public static List<String> options() {
        List<String> options = new ArrayList<>();
        options.add(ANY);
        for (Quantity quantity : CHOICES) {
            options.add(quantity.name());
        }
        return List.copyOf(options);
    }

    /** The config field a gauge-like type appends to its schema. */
    public static ConfigField field() {
        return ConfigField.choice(KEY, "component.bwr.field.measures", options());
    }

    /** The quantity named by an option string; null for ANY or anything unknown. */
    public static Quantity parse(String option) {
        Quantity quantity = Quantity.byName(option);
        return quantity != null && CHOICES.contains(quantity) ? quantity : null;
    }

    /** Writes the setting when it is not ANY. */
    public static void save(CompoundTag tag, Quantity measures) {
        if (measures != null) {
            tag.putString(KEY, measures.name());
        }
    }

    /** Reads the setting; a missing, unknown or ANY value is null (ANY). */
    public static Quantity load(CompoundTag tag) {
        return tag.contains(KEY, Tag.TAG_STRING) ? parse(tag.getString(KEY)) : null;
    }
}
