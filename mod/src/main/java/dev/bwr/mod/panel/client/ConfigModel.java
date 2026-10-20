package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editing state behind the wrench screen, with no widgets and no
 * Minecraft client types: what is being edited, how text becomes values, and
 * what edit goes over the wire. {@link ConfigScreen} only displays this and
 * {@link ConfigLayout} only places it, so a later GUI overhaul can replace
 * either without touching the logic.
 */
public final class ConfigModel {
    /** One editable config field with its working text. */
    public static final class FieldRow {
        public final ConfigField field;
        public String text;

        FieldRow(ConfigField field, String text) {
            this.field = field;
            this.text = text;
        }

        /** The working text as a valid value for the field, or null if it does not parse. */
        public Object parsed() {
            return switch (field.kind()) {
                case TEXT, ENUM -> text;
                case COLOR -> parseColor(text);
                case BOOL -> Boolean.parseBoolean(text);
                case INT, SLIDER -> {
                    try {
                        yield (int) Math.max(field.min(), Math.min(field.max(), Integer.parseInt(text.trim())));
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                case DOUBLE -> {
                    try {
                        double value = Double.parseDouble(text.trim());
                        yield Double.isFinite(value) ? Math.max(field.min(), Math.min(field.max(), value)) : null;
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
            };
        }
    }

    /**
     * One input port with its source being edited (none or a constant). A port wired by the linker (plant,
     * component, redstone, CC) is {@link #bound}: this screen shows it read-only and sends "keep" so saving the
     * wrench screen never unwires it.
     */
    public static final class InputRow {
        public final PortSpec port;
        public boolean constant;
        public String valueText;
        /** Translated description of a linker-made source, or null when this row is editable here. */
        public final Component bound;
        /** Yellow warning that the wired signal is not what the component measures, or null (old saves can hold one). */
        public final Component warning;

        InputRow(PortSpec port, boolean constant, String valueText, Component bound, Component warning) {
            this.port = port;
            this.constant = constant;
            this.valueText = valueText;
            this.bound = bound;
            this.warning = warning;
        }

        public Double parsedValue() {
            try {
                double value = Double.parseDouble(valueText.trim());
                return Double.isFinite(value) ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final ComponentType type;
    public String label;
    /** The printed caption under the component (see {@code MachineNames}); empty draws none. */
    public String caption;
    /** Whether the component has a line link (a plant source or sink), so "Use machine name" applies. */
    public final boolean lineLinked;
    /** Whether the Power units setting applies: the component has it and input 0 is line-linked to a power or energy signal. */
    public final boolean powerApplicable;
    /** "Fit range" was pressed: the server sets Min and Max from the signal range on Done. */
    public boolean fitRange;
    private final PanelComponent component;
    public final List<FieldRow> fields = new ArrayList<>();
    public final List<InputRow> inputs = new ArrayList<>();

    public ConfigModel(PanelComponent component) {
        this.type = component.type();
        this.component = component;
        this.label = component.label();
        this.caption = component.caption();
        this.lineLinked = dev.bwr.mod.panel.networking.NetworkingOps.firstLineLink(component) != null;
        CompoundTag data = component.data().toTag();
        dev.bwr.mod.signal.Quantity bound = dev.bwr.mod.signal.Quantity.byId(component.boundQuantity(0));
        boolean power = component.data().powerState() != null
                && (bound == dev.bwr.mod.signal.Quantity.POWER || bound == dev.bwr.mod.signal.Quantity.ENERGY);
        this.powerApplicable = power;
        boolean ambiguous = power && bound == dev.bwr.mod.signal.Quantity.POWER && component.boundUnit(0).equals("FE");
        for (ConfigField field : type.configSchema()) {
            if (field.key().equals(dev.bwr.mod.panel.component.PowerUnitsState.UNITS_KEY)) {
                if (!power) {
                    continue; // the setting shows only when a power or energy signal is wired
                }
                // Only the units that fit: W is never offered for stored energy.
                field = ConfigField.choice(field.key(), field.labelKey(), dev.bwr.mod.signal.PowerUnits.offeredFor(bound));
            } else if (field.key().equals(dev.bwr.mod.panel.component.PowerUnitsState.RATE_KEY) && !ambiguous) {
                continue; // FE/t and FE/s say their own time base; only an ambiguous FE rate gets the selector
            }
            String text = textOf(field, data);
            if (field.kind() == ConfigField.Kind.ENUM && !field.options().contains(text) && !field.options().isEmpty()) {
                text = field.options().get(0); // an enum saved only when not its default reads as the first option
            }
            fields.add(new FieldRow(field, text));
        }
        List<PortSpec> ports = type.inputPorts(component.data());
        for (int i = 0; i < ports.size(); i++) {
            CompoundTag source = InputSources.save(component.source(i));
            String kind = source.getString("Kind");
            boolean constant = InputSources.KIND_CONSTANT.equals(kind);
            boolean editable = constant || InputSources.KIND_NONE.equals(kind);
            dev.bwr.mod.signal.Quantity measured = component.data().measures(i);
            dev.bwr.mod.signal.Quantity wired = dev.bwr.mod.signal.Quantity.byId(component.mismatch(i));
            Component warning = measured != null && wired != null
                    ? Component.translatable("screen.bwr.panel_config.measures_warning", measured.name(), wired.name()) : null;
            inputs.add(new InputRow(ports.get(i), constant, constant ? format(source.getDouble("Value")) : "0",
                    editable ? null : BindModel.describeSource(component.source(i)), warning));
        }
    }

    public int rowCount() {
        int warnings = 0;
        for (InputRow row : inputs) {
            warnings += row.warning != null ? 1 : 0;
        }
        return 2 + fields.size() + inputs.size() + warnings + (lineLinked ? 1 : 0) + (powerApplicable ? 2 : 0);
    }

    private static String textOf(ConfigField field, CompoundTag data) {
        String key = field.key();
        return switch (field.kind()) {
            case TEXT, ENUM -> data.getString(key);
            case COLOR -> String.format(Locale.ROOT, "#%06X", data.getInt(key) & 0xFFFFFF);
            case BOOL -> Boolean.toString(data.getBoolean(key));
            case INT -> Integer.toString(data.getInt(key));
            // A slider setting is saved only when it is not its default, so a missing key reads as the default.
            case SLIDER -> Integer.toString(data.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? data.getInt(key)
                    : (int) field.defaultValue());
            case DOUBLE -> format(data.getDouble(key));
        };
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1.0e9 ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * The note under the power units: the range as it stands and what one unit of the source reads, from the scale the
     * binding has now (so it is right on a server with its own FE ratio). Empty when the setting does not apply.
     */
    public Component powerNote() {
        if (!powerApplicable) {
            return Component.empty();
        }
        String rawUnit = component.boundUnit(0);
        String shown = rawUnit;
        var state = component.data().powerState();
        if (state != null && !dev.bwr.mod.signal.PowerUnits.RAW.equals(state.units)) {
            shown = state.units;
        }
        String low = "?";
        String high = "?";
        for (FieldRow row : fields) {
            if (row.field.key().equals("Min")) {
                low = row.text;
            } else if (row.field.key().equals("Max")) {
                high = row.text;
            }
        }
        return Component.translatable("screen.bwr.panel_config.power_note", low, high, rawUnit, format(dev.bwr.mod.panel.wiring.WiringOps.scaleOf(component.source(0))), shown);
    }

    /** The value a {@link ConfigField.Kind#SLIDER} at {@code position} (0 at its left end .. 1 at its right) stands for, on its step. */
    public static int sliderValue(ConfigField field, double position) {
        double raw = field.min() + (field.max() - field.min()) * Math.max(0.0, Math.min(1.0, position));
        int step = field.step();
        long snapped = Math.round((raw - field.min()) / step) * step + (long) field.min();
        return (int) Math.max((long) field.min(), Math.min((long) field.max(), snapped));
    }

    /** Where a {@link ConfigField.Kind#SLIDER} showing {@code value} sits, 0 .. 1. */
    public static double sliderPosition(ConfigField field, int value) {
        double span = field.max() - field.min();
        return span <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, (value - field.min()) / span));
    }

    /** Accepts {@code #RRGGBB} or {@code RRGGBB}; null if it is not six hex digits. */
    public static Integer parseColor(String text) {
        String hex = text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether every field and input currently parses (the Done button's enabled state). */
    public boolean valid() {
        if (label.isBlank() || label.length() > PanelConfig.maxLabelLength()
                || caption.length() > dev.bwr.mod.panel.MachineNames.MAX_CAPTION) {
            return false;
        }
        for (FieldRow row : fields) {
            if (row.parsed() == null) {
                return false;
            }
        }
        for (InputRow row : inputs) {
            if (row.bound == null && row.constant && row.parsedValue() == null) {
                return false;
            }
        }
        return true;
    }

    /** The edit compound for the configure packet. Only call when {@link #valid()}. */
    public CompoundTag toEdit() {
        CompoundTag edit = new CompoundTag();
        edit.putString("Label", label.strip());
        edit.putString("Caption", caption.strip());
        CompoundTag config = new CompoundTag();
        for (FieldRow row : fields) {
            Object value = row.parsed();
            switch (row.field.kind()) {
                case TEXT, ENUM -> config.putString(row.field.key(), (String) value);
                case COLOR, INT, SLIDER -> config.putInt(row.field.key(), (Integer) value);
                case BOOL -> config.putBoolean(row.field.key(), (Boolean) value);
                case DOUBLE -> config.putDouble(row.field.key(), (Double) value);
            }
        }
        edit.put("Config", config);
        if (fitRange) {
            edit.putBoolean("FitRange", true);
        }
        ListTag sources = new ListTag();
        for (InputRow row : inputs) {
            CompoundTag source = new CompoundTag();
            if (row.bound != null) {
                source.putString("Kind", "keep");
            } else if (row.constant) {
                source.putString("Kind", InputSources.KIND_CONSTANT);
                source.putDouble("Value", row.parsedValue());
            } else {
                source.putString("Kind", InputSources.KIND_NONE);
            }
            sources.add(source);
        }
        edit.put("Inputs", sources);
        return edit;
    }
}
