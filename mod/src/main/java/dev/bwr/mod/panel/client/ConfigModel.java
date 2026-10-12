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
                case INT -> {
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

        InputRow(PortSpec port, boolean constant, String valueText, Component bound) {
            this.port = port;
            this.constant = constant;
            this.valueText = valueText;
            this.bound = bound;
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
    public final List<FieldRow> fields = new ArrayList<>();
    public final List<InputRow> inputs = new ArrayList<>();

    public ConfigModel(PanelComponent component) {
        this.type = component.type();
        this.label = component.label();
        CompoundTag data = component.data().toTag();
        for (ConfigField field : type.configSchema()) {
            fields.add(new FieldRow(field, textOf(field, data)));
        }
        List<PortSpec> ports = type.inputPorts(component.data());
        for (int i = 0; i < ports.size(); i++) {
            CompoundTag source = InputSources.save(component.source(i));
            String kind = source.getString("Kind");
            boolean constant = InputSources.KIND_CONSTANT.equals(kind);
            boolean editable = constant || InputSources.KIND_NONE.equals(kind);
            inputs.add(new InputRow(ports.get(i), constant, constant ? format(source.getDouble("Value")) : "0",
                    editable ? null : BindModel.describeSource(component.source(i))));
        }
    }

    public int rowCount() {
        return 1 + fields.size() + inputs.size();
    }

    private static String textOf(ConfigField field, CompoundTag data) {
        String key = field.key();
        return switch (field.kind()) {
            case TEXT, ENUM -> data.getString(key);
            case COLOR -> String.format(Locale.ROOT, "#%06X", data.getInt(key) & 0xFFFFFF);
            case BOOL -> Boolean.toString(data.getBoolean(key));
            case INT -> Integer.toString(data.getInt(key));
            case DOUBLE -> format(data.getDouble(key));
        };
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1.0e9 ? Long.toString((long) value) : Double.toString(value);
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
        if (label.isBlank() || label.length() > PanelConfig.maxLabelLength()) {
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
        CompoundTag config = new CompoundTag();
        for (FieldRow row : fields) {
            Object value = row.parsed();
            switch (row.field.kind()) {
                case TEXT, ENUM -> config.putString(row.field.key(), (String) value);
                case COLOR, INT -> config.putInt(row.field.key(), (Integer) value);
                case BOOL -> config.putBoolean(row.field.key(), (Boolean) value);
                case DOUBLE -> config.putDouble(row.field.key(), (Double) value);
            }
        }
        edit.put("Config", config);
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
