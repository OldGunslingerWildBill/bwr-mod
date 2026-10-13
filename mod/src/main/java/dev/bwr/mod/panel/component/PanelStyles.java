package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The look of a panel component: a {@code Style} (classic procedural placeholder, GE, Westinghouse) and, for a
 * type that has several models in a style, a {@code Variant} (the handle, the button, the lens, the pointer).
 * This class is the one table that maps (component type, style, variant) to the id of the baked model that
 * draws it, plus the validation, defaults and config fields built from that table. Common code: servers
 * validate and save the choice, clients draw it.
 *
 * <h3>Classic and fallback</h3>
 * {@link #CLASSIC} is the procedural placeholder every type had before baked models, and it is what a save
 * without a {@code Style} key loads as. A choice whose model file is missing (a resource pack without it, a
 * model not delivered yet) draws classic too: {@link #resolve} returns null and the renderer falls back, so
 * nothing ever disappears.
 *
 * <h3>New components</h3>
 * A newly made component starts as {@link #GE} when the model for its default variant ships in the mod
 * ({@link #modelShips}), otherwise classic. The choice is then stored explicitly, so models arriving later
 * never change an existing component under its owner.
 *
 * <h3>Adding a model</h3>
 * Add a row to {@link #TABLE}. The first id of a row that exists wins, so a row may list alternatives. A row
 * flagged as default is what the style shows when the stored variant is not one of its own (switching style
 * from the wrench keeps the variant field, which may belong to the other style).
 */
public final class PanelStyles {
    private PanelStyles() {
    }

    public static final String CLASSIC = "classic";
    public static final String GE = "ge";
    public static final String WESTINGHOUSE = "westinghouse";

    /** NBT keys of the two config fields. */
    public static final String KEY_STYLE = "Style";
    public static final String KEY_VARIANT = "Variant";

    /** Day-glo red pointer of the switchboard meters (PANEL-MODELS-SPEC.md palette). */
    public static final int RED_POINTER = 0xE8402A;
    public static final int NO_COLOUR = -1;

    /**
     * What a key switch model needs beyond its handle: which label anchors carry the OFF and ON legends, and the
     * handle angles of the two positions (clockwise degrees from the modelled pose).
     */
    public record KeyPose(String offLegend, String onLegend, float offDegrees, float onDegrees) {
    }

    /**
     * One row of the table.
     *
     * @param type      component type path ({@code control_switch})
     * @param models    candidate model ids in preference order (file names under {@code models/panel/})
     * @param needleRgb colour forced onto the model's pointer parts, or {@link #NO_COLOUR}. It applies only when a
     *                  model after the first candidate is the one used: the first is the variant's own model, which
     *                  already has the right colour, and the later ones stand in for it (the plain model with its
     *                  pointer recoloured when the dedicated red-pointer model is missing).
     * @param key       key switch legends and angles, or null for other types (a key switch without it turns a
     *                  quarter turn about its modelled pose, legends "OFF" and "ON" nowhere)
     * @param footprint index into the type's footprint list of the size this model reads best at: 0 for the
     *                  default size; 1 for a model whose frame is much larger than its plate (the heavy duty and
     *                  target pistols), which looks small at the default size. A hint for placing and for the
     *                  showcase; the footprint of a placed component never changes under it.
     */
    public record Entry(String type, String style, String variant, boolean isDefault, int needleRgb, List<String> models,
                        KeyPose key, int footprint) {
        Entry needle(int rgb) {
            return new Entry(type, style, variant, isDefault, rgb, models, key, footprint);
        }

        Entry keyed(KeyPose pose) {
            return new Entry(type, style, variant, isDefault, needleRgb, models, pose, footprint);
        }

        Entry larger() {
            return new Entry(type, style, variant, isDefault, needleRgb, models, key, 1);
        }
    }

    /** The outcome of resolving a (style, variant): the model to draw, any pointer recolouring, key switch poses. */
    public record Resolved(String modelId, int needleRgb, KeyPose key) {
    }

    private static Entry row(String type, String style, String variant, boolean isDefault, String... models) {
        return new Entry(type, style, variant, isDefault, NO_COLOUR, List.of(models), null, 0);
    }

    /** GE keyed escutcheon: OFF at 12 o'clock (pos3), ON at 1:30 (pos4). */
    private static final KeyPose GE_KEY = new KeyPose("pos3", "pos4", 0.0f, 45.0f);
    /** Westinghouse key: OFF 45 degrees left (the "left" legend), ON 45 degrees right. */
    private static final KeyPose W2_KEY = new KeyPose("left", "right", -45.0f, 45.0f);

    /**
     * (type, style, variant) to model id: the single table. Ids that the modelling work has already named come
     * first; later ids are alternatives for names that were not fixed when this was written.
     *
     * <p>Conventions the models follow (see the model READMEs) and the game relies on:
     * <ul>
     *   <li>Control switch: the grip hangs straight down at NORMAL; CLOSE turns it 45 degrees clockwise and
     *       TRIP (the mod's OPEN) 45 degrees counter-clockwise, so the handle angle is -45 x position. The
     *       legends are the model's {@code right} anchor for CLOSE and {@code left} for TRIP.</li>
     *   <li>Rotary selector: the pointer is modelled at 12 o'clock; position k (from 0) sits at the label
     *       {@code pos(k+1)}, and the handle turns to that label's bearing from the shaft (GE: pos1 at 9 o'clock;
     *       Westinghouse: pos1 at 7:30, pos4 at 12).</li>
     *   <li>Key switch: see {@link KeyPose}; the tumbler lock's {@code plug} part turns 0 locked, +90 unlocked.</li>
     * </ul>
     */
    public static final List<Entry> TABLE = List.of(
            // control switch: breaker control with a target flag
            row("control_switch", GE, "pistol", true, "ge_sb_target_pistol").larger(),
            row("control_switch", WESTINGHOUSE, "pistol", true, "w2_pistol"),
            row("control_switch", WESTINGHOUSE, "pistol_hd", false, "w2_pistol_hd", "w2_pistol_heavy", "w2_pistol_heavy_duty").larger(),
            // rotary selector
            row("rotary_selector", GE, "oval", true, "ge_sb_standard_oval"),
            row("rotary_selector", GE, "knurled", false, "ge_sb_standard_knurled", "ge_sb_knurled"),
            row("rotary_selector", GE, "lever", false, "ge_sb_standard_lever", "ge_sb_lever"),
            row("rotary_selector", WESTINGHOUSE, "oval", true, "w2_oval"),
            row("rotary_selector", WESTINGHOUSE, "round_notched", false, "w2_round_notched", "w2_notched"),
            row("rotary_selector", WESTINGHOUSE, "mini_slim", false, "w2_mini_slim", "w2_mini_slimline", "w2_mini"),
            // key switch
            row("key_switch", GE, "key", true, "ge_sb_keyed").keyed(GE_KEY),
            row("key_switch", WESTINGHOUSE, "key", true, "w2_key").keyed(W2_KEY),
            row("key_switch", WESTINGHOUSE, "tumbler", false, "w2_tumbler", "w2_key_tumbler", "w2_lock").keyed(W2_KEY),
            // 30.5 mm oiltight pushbutton, indicating light, fluted knob
            row("pushbutton", GE, "flush", true, "pb_flush"),
            row("pushbutton", GE, "extended", false, "pb_extended"),
            row("pushbutton", GE, "mushroom", false, "pb_mushroom"),
            row("pushbutton", GE, "guarded", false, "pb_guarded"),
            row("pushbutton", GE, "illuminated", false, "pb_illuminated"),
            row("lamp", GE, "round", true, "light_round"),
            row("lamp", GE, "round_plate", false, "light_round_plate"),
            row("lamp", GE, "et16", false, "light_et16"),
            row("lamp", GE, "square", false, "light_square"),
            row("setpoint_knob", GE, "fluted", true, "knob_fluted"),
            // switchboard meters: standard pointer, or the Day-glo red one (its own model, else the standard
            // model with the pointer recoloured)
            row("dial_gauge", GE, "standard", true, "ge_ab40"),
            row("dial_gauge", GE, "red_pointer", false, "ge_ab40_red", "ge_ab40").needle(RED_POINTER),
            row("edgewise_gauge_vertical", GE, "standard", true, "ge_180_vertical"),
            row("edgewise_gauge_vertical", GE, "red_pointer", false, "ge_180_vertical_red", "ge_180_vertical").needle(RED_POINTER),
            row("edgewise_gauge_horizontal", GE, "standard", true, "ge_180_horizontal"),
            row("edgewise_gauge_horizontal", GE, "red_pointer", false, "ge_180_horizontal_red", "ge_180_horizontal").needle(RED_POINTER));

    // ---- queries over the table

    /** Whether any model is mapped for this type. */
    public static boolean hasModels(String type) {
        for (Entry entry : TABLE) {
            if (entry.type().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The styles this type offers, classic first. */
    public static List<String> styles(String type) {
        Set<String> styles = new LinkedHashSet<>();
        styles.add(CLASSIC);
        for (Entry entry : TABLE) {
            if (entry.type().equals(type)) {
                styles.add(entry.style());
            }
        }
        return List.copyOf(styles);
    }

    /** Every variant this type has in any style, in table order. */
    public static List<String> variants(String type) {
        Set<String> variants = new LinkedHashSet<>();
        for (Entry entry : TABLE) {
            if (entry.type().equals(type)) {
                variants.add(entry.variant());
            }
        }
        return List.copyOf(variants);
    }

    /** Whether the type is offered a Variant field: it has more than one model to choose between. */
    public static boolean hasVariantField(String type) {
        return variants(type).size() > 1;
    }

    /** The variant a style shows by default, or "" if the style has no rows for the type. */
    public static String defaultVariant(String type, String style) {
        Entry fallback = null;
        for (Entry entry : TABLE) {
            if (entry.type().equals(type) && entry.style().equals(style)) {
                if (entry.isDefault()) {
                    return entry.variant();
                }
                if (fallback == null) {
                    fallback = entry;
                }
            }
        }
        return fallback == null ? "" : fallback.variant();
    }

    /** The footprint index {@link Entry#footprint} the (style, variant) reads best at; 0 when there is no such row. */
    public static int preferredFootprint(String type, String style, String variant) {
        Entry entry = entry(type, style, variant);
        return entry == null ? 0 : entry.footprint();
    }

    /** The row for (type, style, variant); the style's default row if that variant is not one of its own; else null. */
    public static Entry entry(String type, String style, String variant) {
        Entry defaultRow = null;
        for (Entry entry : TABLE) {
            if (!entry.type().equals(type) || !entry.style().equals(style)) {
                continue;
            }
            if (entry.variant().equals(variant)) {
                return entry;
            }
            if (entry.isDefault()) {
                defaultRow = entry;
            }
        }
        return defaultRow;
    }

    /**
     * The model that draws (type, style, variant), or null for the classic procedural look: the style is
     * classic or unknown, the table has no row, or none of the row's model ids satisfies {@code exists}.
     */
    public static Resolved resolve(String type, String style, String variant, Predicate<String> exists) {
        if (CLASSIC.equals(style)) {
            return null;
        }
        Entry entry = entry(type, style, variant);
        if (entry == null) {
            return null;
        }
        for (int i = 0; i < entry.models().size(); i++) {
            String id = entry.models().get(i);
            if (exists.test(id)) {
                return new Resolved(id, i == 0 ? NO_COLOUR : entry.needleRgb(), entry.key());
            }
        }
        return null;
    }

    // ---- shipped-model check (common side: the model JSONs travel in the mod jar)

    private static final Map<String, Boolean> SHIPPED = new ConcurrentHashMap<>();
    private static volatile Predicate<String> shippedOverride;

    /** Whether a model JSON with this id ships in the mod's resources (checked once per id). */
    public static boolean modelShips(String id) {
        Predicate<String> override = shippedOverride;
        if (override != null) {
            return override.test(id);
        }
        return SHIPPED.computeIfAbsent(id, key ->
                PanelStyles.class.getResource("/assets/bwr/models/panel/" + key + ".json") != null);
    }

    /** Test hook: replaces the shipped-model check (null restores the real one). */
    public static void overrideShipped(Predicate<String> exists) {
        shippedOverride = exists;
    }

    /** The style a new component of this type starts with: GE when its default GE model ships, else classic. */
    public static String newStyle(String type) {
        return resolve(type, GE, defaultVariant(type, GE), PanelStyles::modelShips) != null ? GE : CLASSIC;
    }

    // ---- saving and loading (keys "Style" and "Variant" in the component's data)

    public static void save(CompoundTag tag, String style, String variant) {
        tag.putString(KEY_STYLE, style);
        tag.putString(KEY_VARIANT, variant);
    }

    /** The saved style; a save without one (made before baked models) is classic, and so is any unknown value. */
    public static String loadStyle(String type, CompoundTag tag) {
        if (!tag.contains(KEY_STYLE, Tag.TAG_STRING)) {
            return CLASSIC;
        }
        String saved = tag.getString(KEY_STYLE);
        return styles(type).contains(saved) ? saved : CLASSIC;
    }

    /** The saved variant, or the style's default when missing or not one this type has. */
    public static String loadVariant(String type, CompoundTag tag, String style) {
        String saved = tag.contains(KEY_VARIANT, Tag.TAG_STRING) ? tag.getString(KEY_VARIANT) : "";
        return variants(type).contains(saved) ? saved : defaultVariant(type, style.equals(CLASSIC) ? GE : style);
    }

    // ---- config fields (the wrench screen shows these)

    /** The Style field, and the Variant field when the type has several models. */
    public static List<ConfigField> fields(String type) {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.choice(KEY_STYLE, "component.bwr.field.style", styles(type)));
        if (hasVariantField(type)) {
            fields.add(ConfigField.choice(KEY_VARIANT, variantLabelKey(type), variants(type)));
        }
        return List.copyOf(fields);
    }

    /** Label of the Variant field: what a player calls the choice for this kind of device. */
    public static String variantLabelKey(String type) {
        return switch (type) {
            case "control_switch", "rotary_selector", "key_switch" -> "component.bwr.field.handle";
            case "pushbutton" -> "component.bwr.field.button_type";
            case "lamp" -> "component.bwr.field.lens";
            case "dial_gauge", "edgewise_gauge_vertical", "edgewise_gauge_horizontal" -> "component.bwr.field.pointer";
            default -> "component.bwr.field.variant";
        };
    }

    /** {@code existing} followed by this type's look fields, for a type's schema. */
    public static List<ConfigField> withLook(String type, List<ConfigField> existing) {
        List<ConfigField> all = new ArrayList<>(existing);
        all.addAll(fields(type));
        return List.copyOf(all);
    }
}
