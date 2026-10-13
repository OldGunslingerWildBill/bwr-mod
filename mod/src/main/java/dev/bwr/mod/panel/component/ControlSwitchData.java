package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * GE style pistol-grip control switch: handle labels, lamp captions, whether it
 * springs back to NORMAL, and the latched position (only meaningful without
 * the spring; a spring-return switch always loads at NORMAL).
 */
public final class ControlSwitchData extends ComponentData {
    public static final String TYPE = "control_switch";
    public static final int MAX_TEXT = 8;
    public static final int CLOSE = -1;
    public static final int NORMAL = 0;
    public static final int OPEN = 1;

    public boolean spring = true;
    public String closeText = "CLOSE";
    public String normalText = "NORMAL";
    public String openText = "OPEN";
    /** Caption under the red lamp (open / running, US convention). */
    public String redText = "OPEN";
    /** Caption under the green lamp (closed / stopped). */
    public String greenText = "CLOSED";
    /** -1 CLOSE, 0 NORMAL, +1 OPEN. */
    public int position;
    /**
     * Latched: the last position the handle was moved to that was not NORMAL (-1 CLOSE, +1 OPEN, 0 never
     * operated). A baked model's target flag shows it when the lamp inputs say nothing; saved, and kept in step
     * on clients because every output change runs {@link ControlSwitchType#valuesChanged}.
     */
    public int lastOperated;
    /** Look: {@link PanelStyles} style and handle variant. */
    public String style = PanelStyles.newStyle(TYPE);
    public String variant = PanelStyles.defaultVariant(TYPE, style);

    @Override
    public void save(CompoundTag tag) {
        tag.putBoolean("Spring", spring);
        tag.putString("CloseText", closeText);
        tag.putString("NormalText", normalText);
        tag.putString("OpenText", openText);
        tag.putString("RedText", redText);
        tag.putString("GreenText", greenText);
        tag.putInt("Position", position);
        tag.putInt("LastOperated", lastOperated);
        PanelStyles.save(tag, style, variant);
    }

    @Override
    public void load(CompoundTag tag) {
        spring = !tag.contains("Spring") || tag.getBoolean("Spring");
        closeText = text(tag, "CloseText", "CLOSE");
        normalText = text(tag, "NormalText", "NORMAL");
        openText = text(tag, "OpenText", "OPEN");
        redText = text(tag, "RedText", "OPEN");
        greenText = text(tag, "GreenText", "CLOSED");
        position = spring ? NORMAL : Math.max(CLOSE, Math.min(OPEN, tag.getInt("Position")));
        lastOperated = Math.max(CLOSE, Math.min(OPEN, tag.getInt("LastOperated")));
        style = PanelStyles.loadStyle(TYPE, tag);
        variant = PanelStyles.loadVariant(TYPE, tag, style);
    }

    private static String text(CompoundTag tag, String key, String fallback) {
        return tag.contains(key, Tag.TAG_STRING) ? ConfigSchema.cleanText(tag.getString(key), MAX_TEXT) : fallback;
    }
}
