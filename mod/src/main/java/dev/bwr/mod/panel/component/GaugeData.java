package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.model.GaugeScale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Scale configuration shared by the round dial and the two edgewise meters:
 * range, units, up to {@link #BANDS} coloured bands and the needle damping.
 * No latched state. Input 0 is the measured value; the edgewise meters have an
 * optional input 1 for a demand marker.
 *
 * <p>A band is drawn when its high end is above its low end, so the default
 * "unused" band (0..0) costs nothing.
 */
public final class GaugeData extends ComponentData {
    public static final int BANDS = 3;
    public static final double LIMIT = 1.0e9;
    public static final int MAX_UNITS = 12;
    public static final double MAX_DAMPING = 5.0;
    /** The bar gauge draws a fourth band (the narrow range scale has red, yellow, yellow, red zones). */
    public static final int MAX_BANDS = 4;
    /** Component type path of the bar gauge: no style table row, one fixed model, scale presets. */
    public static final String BAR_GAUGE = "bar_gauge";
    /** Scale presets of the bar gauge: what the wrench's Preset field fills in. */
    public static final String PRESET_CUSTOM = "custom";
    public static final String PRESET_NARROW = "narrow";
    public static final String PRESET_WIDE = "wide";
    public static final List<String> PRESETS = List.of(PRESET_CUSTOM, PRESET_NARROW, PRESET_WIDE);
    /** Danger and caution colours of the presets (the dark red and the olive yellow of the plant's switchboard scales). */
    public static final int BAND_RED = 0xA80E0E;
    public static final int BAND_YELLOW = 0xC4B02A;
    /** Nominal colour: white (on the presets a nominal range is simply the white face showing between the bands). */
    public static final int BAND_WHITE = 0xFFFFFF;
    /**
     * The named band colours the bar gauge's wrench offers beside the colour code: NOMINAL ranges white, CAUTION ranges
     * yellow, DANGER ranges red. A band still saves its colour as a plain number, so picking one saves what typing its
     * code would, and old saves keep the colours they had.
     */
    public static final String NOMINAL = "nominal";
    public static final String CAUTION = "caution";
    public static final String DANGER = "danger";
    public static final List<String> BAND_SWATCHES = List.of(ConfigField.swatch(NOMINAL, BAND_WHITE),
            ConfigField.swatch(CAUTION, BAND_YELLOW), ConfigField.swatch(DANGER, BAND_RED));

    public double min = 0.0;
    public double max = 100.0;
    public String units = "";
    /** Needle time constant in seconds on the client (0 = the needle follows instantly). */
    public double damping = 0.4;
    public final double[] bandLow = {80.0, 90.0, 0.0, 0.0};
    public final double[] bandHigh = {90.0, 100.0, 0.0, 0.0};
    public final int[] bandColor = {0xFFB000, 0xE02020, 0x30C030, BAND_RED};
    /** The component type path this data belongs to ({@code dial_gauge} or an edgewise meter): picks the model table rows. */
    public final String type;
    /** Look: {@link PanelStyles} style and pointer variant. */
    public String style;
    public String variant;
    /** Bar gauge only: the scale preset last chosen ({@link #PRESETS}); a preset fills the range, units and bands in when it is chosen. */
    public String preset = PRESET_CUSTOM;
    /** What the gauge measures; null is ANY (every signal, as before the setting existed). Saved only when set. */
    public dev.bwr.mod.signal.Quantity measures;
    /** The Power units setting; saved only when not the default. */
    public final PowerUnitsState power = new PowerUnitsState();
    /**
     * The wrench's Text size and Tick size, percent ({@link GaugeScale}): they multiply the printed numbers, units and
     * labels, and the tick marks' thickness and length. Saved only when not 100, so older saves load and save unchanged.
     */
    public int textSize = GaugeScale.DEFAULT_PERCENT;
    public int tickSize = GaugeScale.DEFAULT_PERCENT;

    /** Data for a gauge of type path {@code type}, with the look a new component of that type starts with. */
    public GaugeData(String type) {
        this.type = type;
        this.style = PanelStyles.newStyle(type);
        this.variant = PanelStyles.defaultVariant(type, style);
        if (isBarGauge()) {
            apply(PRESET_NARROW);
        }
    }

    /** Whether this is a bar gauge: four bands, a preset, and one fixed look instead of a style and variant. */
    public boolean isBarGauge() {
        return BAR_GAUGE.equals(type);
    }

    /** Bands this gauge has: the bar gauge a fourth, every other type three. */
    public int bandCount() {
        return isBarGauge() ? MAX_BANDS : BANDS;
    }

    /** One scale preset: range, units and the colour bands. A band listed 0 to 0 stays unused. */
    private record Preset(double min, double max, String units, double[][] bands, int[] colors) {
    }

    private static Preset preset(String name) {
        return switch (name) {
            // RPV level, narrow range: 0 to 60 inches, red 0 to 10 and 55 to 60, yellow 10 to 20 and 45 to 55.
            case PRESET_NARROW -> new Preset(0.0, 60.0, "INCHES",
                    new double[][] {{0.0, 10.0}, {10.0, 20.0}, {45.0, 55.0}, {55.0, 60.0}},
                    new int[] {BAND_RED, BAND_YELLOW, BAND_YELLOW, BAND_RED});
            // RPV level, wide range: -150 to +60 inches, red -150 to +13 and 55 to 60.
            case PRESET_WIDE -> new Preset(-150.0, 60.0, "INCHES",
                    new double[][] {{-150.0, 13.0}, {55.0, 60.0}, {0.0, 0.0}, {0.0, 0.0}},
                    new int[] {BAND_RED, BAND_RED, BAND_YELLOW, BAND_RED});
            default -> null;
        };
    }

    /** Fills the range, units and bands in from the preset {@code name} (nothing for custom or an unknown name). */
    public void apply(String name) {
        Preset p = preset(name);
        if (p == null) {
            return;
        }
        min = p.min;
        max = p.max;
        units = p.units;
        for (int i = 0; i < MAX_BANDS; i++) {
            bandLow[i] = p.bands[i][0];
            bandHigh[i] = p.bands[i][1];
            bandColor[i] = p.colors[i];
        }
        preset = name;
    }

    /**
     * A config edit that names a preset other than the one the gauge holds (the wrench's Preset field was changed)
     * fills that preset's range, units and bands into {@code proposed}, replacing whatever the same edit carried.
     */
    public void adjustForPreset(CompoundTag proposed) {
        if (!isBarGauge() || !proposed.contains("Preset", Tag.TAG_STRING)) {
            return;
        }
        String next = proposed.getString("Preset");
        Preset p = preset(next);
        if (p == null || next.equals(preset)) {
            return;
        }
        proposed.putDouble("Min", p.min);
        proposed.putDouble("Max", p.max);
        proposed.putString("Units", p.units);
        for (int i = 0; i < MAX_BANDS; i++) {
            String key = "Band" + (i + 1);
            proposed.putDouble(key + "Low", p.bands[i][0]);
            proposed.putDouble(key + "High", p.bands[i][1]);
            proposed.putInt(key + "Color", p.colors[i]);
        }
    }

    /** Config fields for a gauge of type path {@code type}: scale, units, damping, bands, then the look. */
    public static List<ConfigField> schema(String type) {
        List<ConfigField> fields = new ArrayList<>();
        boolean bar = BAR_GAUGE.equals(type);
        if (bar) {
            fields.add(ConfigField.choice("Preset", "component.bwr.field.preset", PRESETS));
        }
        fields.add(ConfigField.decimal("Min", "component.bwr.field.min", -LIMIT, LIMIT));
        fields.add(ConfigField.decimal("Max", "component.bwr.field.max", -LIMIT, LIMIT));
        fields.add(ConfigField.text("Units", "component.bwr.field.units", MAX_UNITS));
        fields.add(ConfigField.decimal("Damping", "component.bwr.field.damping", 0.0, MAX_DAMPING));
        for (int i = 1; i <= (bar ? MAX_BANDS : BANDS); i++) {
            fields.add(ConfigField.decimal("Band" + i + "Low", "component.bwr.field.band" + i + "_low", -LIMIT, LIMIT));
            fields.add(ConfigField.decimal("Band" + i + "High", "component.bwr.field.band" + i + "_high", -LIMIT, LIMIT));
            String colour = "Band" + i + "Color";
            String label = "component.bwr.field.band" + i + "_color";
            fields.add(bar ? ConfigField.color(colour, label, BAND_SWATCHES) : ConfigField.color(colour, label));
        }
        fields.add(ConfigField.slider(GaugeScale.TEXT_SIZE, "component.bwr.field.text_size", GaugeScale.MIN_PERCENT,
                GaugeScale.MAX_PERCENT, GaugeScale.STEP_PERCENT, GaugeScale.DEFAULT_PERCENT));
        fields.add(ConfigField.slider(GaugeScale.TICK_SIZE, "component.bwr.field.tick_size", GaugeScale.MIN_PERCENT,
                GaugeScale.MAX_PERCENT, GaugeScale.STEP_PERCENT, GaugeScale.DEFAULT_PERCENT));
        if (!bar) {
            fields.addAll(PanelStyles.fields(type));
        }
        fields.add(Measures.field());
        fields.addAll(PowerUnitsState.fields());
        return List.copyOf(fields);
    }

    /** What the Text size setting multiplies the printed text by (1 at 100 %). */
    public float textFactor() {
        return GaugeScale.factor(textSize);
    }

    /** What the Tick size setting multiplies the tick marks' thickness and length by (1 at 100 %). */
    public float tickFactor() {
        return GaugeScale.factor(tickSize);
    }

    /** The scale length; never zero, so a mis-set range cannot divide by zero. */
    public double span() {
        return max > min ? max - min : 1.0;
    }

    /** Position of {@code value} along the scale, 0..1. No signal pegs low (0). */
    public double fraction(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (value - min) / span()));
    }

    /** No signal: the meter pegs low and shows the off-scale flag. */
    public static boolean offScale(double value) {
        return Double.isNaN(value);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        tag.putString("Units", units);
        tag.putDouble("Damping", damping);
        for (int i = 0; i < bandCount(); i++) {
            tag.putDouble("Band" + (i + 1) + "Low", bandLow[i]);
            tag.putDouble("Band" + (i + 1) + "High", bandHigh[i]);
            tag.putInt("Band" + (i + 1) + "Color", bandColor[i]);
        }
        if (textSize != GaugeScale.DEFAULT_PERCENT) {
            tag.putInt(GaugeScale.TEXT_SIZE, textSize);
        }
        if (tickSize != GaugeScale.DEFAULT_PERCENT) {
            tag.putInt(GaugeScale.TICK_SIZE, tickSize);
        }
        if (isBarGauge()) {
            // One fixed look: no style or variant. The preset is saved only once the player has picked one.
            if (!PRESET_CUSTOM.equals(preset)) {
                tag.putString("Preset", preset);
            }
            return;
        }
        PanelStyles.save(tag, style, variant);
        Measures.save(tag, measures);
        power.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        min = number(tag, "Min", 0.0, -LIMIT, LIMIT);
        max = number(tag, "Max", 100.0, -LIMIT, LIMIT);
        units = ConfigSchema.cleanText(tag.getString("Units"), MAX_UNITS);
        damping = number(tag, "Damping", 0.4, 0.0, MAX_DAMPING);
        double[] defaultLow = {80.0, 90.0, 0.0, 0.0};
        double[] defaultHigh = {90.0, 100.0, 0.0, 0.0};
        int[] defaultColor = {0xFFB000, 0xE02020, 0x30C030, BAND_RED};
        for (int i = 0; i < bandCount(); i++) {
            String key = "Band" + (i + 1);
            bandLow[i] = number(tag, key + "Low", defaultLow[i], -LIMIT, LIMIT);
            bandHigh[i] = number(tag, key + "High", defaultHigh[i], -LIMIT, LIMIT);
            bandColor[i] = tag.contains(key + "Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt(key + "Color") & 0xFFFFFF
                    : defaultColor[i];
        }
        textSize = percent(tag, GaugeScale.TEXT_SIZE);
        tickSize = percent(tag, GaugeScale.TICK_SIZE);
        if (isBarGauge()) {
            String saved = tag.getString("Preset");
            preset = PRESETS.contains(saved) ? saved : PRESET_CUSTOM;
            return;
        }
        style = PanelStyles.loadStyle(type, tag);
        variant = PanelStyles.loadVariant(type, tag, style);
        measures = Measures.load(tag);
        power.load(tag);
    }

    @Override
    public PowerUnitsState powerState() {
        return power;
    }

    @Override
    public void setUnitsText(String text) {
        units = ConfigSchema.cleanText(text, MAX_UNITS);
    }

    @Override
    public boolean setRange(double newMin, double newMax) {
        min = Math.max(-LIMIT, Math.min(LIMIT, newMin));
        max = Math.max(-LIMIT, Math.min(LIMIT, newMax));
        return true;
    }

    @Override
    public dev.bwr.mod.signal.Quantity measures(int inputIndex) {
        return measures;
    }

    /** A slider setting: the default when the tag has none, else brought into the sliders' range. */
    private static int percent(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? GaugeScale.clampPercent(tag.getInt(key)) : GaugeScale.DEFAULT_PERCENT;
    }

    static double number(CompoundTag tag, String key, double fallback, double low, double high) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return fallback;
        }
        double value = tag.getDouble(key);
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : fallback;
    }
}
