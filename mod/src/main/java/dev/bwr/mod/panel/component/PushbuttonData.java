package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Pushbutton configuration: cap colour, and an optional role as the
 * acknowledge or reset button of an annunciator group. No latched state: a
 * pushbutton is 1 only while held.
 */
public final class PushbuttonData extends ComponentData {
    public static final String TYPE = "pushbutton";
    public static final String ROLE_NONE = "none";
    public static final String ROLE_ACK = "ack";
    public static final String ROLE_RESET = "reset";
    public static final List<String> ROLES = List.of(ROLE_NONE, ROLE_ACK, ROLE_RESET);
    public static final int DEFAULT_COLOR = 0xC02020;

    public int color = DEFAULT_COLOR;
    public String role = ROLE_NONE;
    /** The annunciator group this button acknowledges or resets when it has a role. */
    public int group = AnnunciatorData.MIN_GROUP;
    /** Look: {@link PanelStyles} style and button variant. */
    public String style = PanelStyles.newStyle(TYPE);
    public String variant = PanelStyles.defaultVariant(TYPE, style);

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Color", color);
        tag.putString("Role", role);
        tag.putInt("AnnGroup", group);
        PanelStyles.save(tag, style, variant);
    }

    @Override
    public void load(CompoundTag tag) {
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        String saved = tag.getString("Role");
        role = ROLES.contains(saved) ? saved : ROLE_NONE;
        group = tag.contains("AnnGroup", Tag.TAG_ANY_NUMERIC)
                ? Math.max(AnnunciatorData.MIN_GROUP, Math.min(AnnunciatorData.MAX_GROUP, tag.getInt("AnnGroup")))
                : AnnunciatorData.MIN_GROUP;
        style = PanelStyles.loadStyle(TYPE, tag);
        variant = PanelStyles.loadVariant(TYPE, tag, style);
    }
}
