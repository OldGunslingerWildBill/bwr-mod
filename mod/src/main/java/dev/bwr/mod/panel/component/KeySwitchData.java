package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;

/** Key switch: whether a key is needed, which key code it takes, and the latched position. */
public final class KeySwitchData extends ComponentData {
    public static final String TYPE = "key_switch";
    public static final int MAX_CODE = 24;

    public boolean requireKey = true;
    /** The custom name a {@link PanelKeyItem} must carry; empty matches an unnamed key. */
    public String keyCode = "";
    public boolean on;
    /** Look: {@link PanelStyles} style and handle variant. */
    public String style = PanelStyles.newStyle(TYPE);
    public String variant = PanelStyles.defaultVariant(TYPE, style);

    @Override
    public void save(CompoundTag tag) {
        tag.putBoolean("RequireKey", requireKey);
        tag.putString("KeyCode", keyCode);
        tag.putBoolean("On", on);
        PanelStyles.save(tag, style, variant);
    }

    @Override
    public void load(CompoundTag tag) {
        requireKey = !tag.contains("RequireKey") || tag.getBoolean("RequireKey");
        keyCode = ConfigSchema.cleanText(tag.getString("KeyCode"), MAX_CODE);
        on = tag.getBoolean("On");
        style = PanelStyles.loadStyle(TYPE, tag);
        variant = PanelStyles.loadVariant(TYPE, tag, style);
    }
}
