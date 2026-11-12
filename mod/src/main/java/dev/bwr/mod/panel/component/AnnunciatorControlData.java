package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Annunciator control module configuration: the annunciator group its ACK and RESET buttons act on. No latched
 * state: every button is 1 only while held. Saved only when the group is not 1, so a module at the defaults saves
 * nothing.
 */
public final class AnnunciatorControlData extends ComponentData {
    /** Id of the control station model ({@code assets/bwr/models/panel/ann_control.json}). */
    public static final String STATION_MODEL = "ann_control";
    /**
     * The look drawn from the station model: four round black pushbuttons in bright bezel rings set in the panel face,
     * a legend plate under each.
     */
    public static final String STYLE_STATION = "station";
    /** The looks the module offers: the procedural one first, which is the default. */
    public static final List<String> STYLES = List.of(PanelStyles.CLASSIC, STYLE_STATION);

    public int group = AnnunciatorData.MIN_GROUP;
    /** Look: classic (the procedural module) or the station model; saved only when it is not classic, as the group is. */
    public String style = PanelStyles.CLASSIC;

    @Override
    public void save(CompoundTag tag) {
        if (group != AnnunciatorData.MIN_GROUP) {
            tag.putInt("AnnGroup", group);
        }
        if (!PanelStyles.CLASSIC.equals(style)) {
            tag.putString(PanelStyles.KEY_STYLE, style);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        group = tag.contains("AnnGroup", Tag.TAG_ANY_NUMERIC)
                ? Math.max(AnnunciatorData.MIN_GROUP, Math.min(AnnunciatorData.MAX_GROUP, tag.getInt("AnnGroup")))
                : AnnunciatorData.MIN_GROUP;
        String savedStyle = tag.getString(PanelStyles.KEY_STYLE);
        style = STYLES.contains(savedStyle) ? savedStyle : PanelStyles.CLASSIC;
    }
}
