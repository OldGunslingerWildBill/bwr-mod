package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Annunciator window configuration (engraved text, tile colour, alarm
 * condition, group, reset mode) and its latched {@link AnnunciatorLogic} state,
 * which is saved so an unacknowledged alarm survives a restart.
 */
public final class AnnunciatorData extends ComponentData {
    public static final int MAX_TEXT = 12;
    public static final int MIN_GROUP = 1;
    public static final int MAX_GROUP = 8;
    public static final String RESET_AUTO = "auto";
    public static final String RESET_MANUAL = "manual";
    public static final List<String> RESET_MODES = List.of(RESET_AUTO, RESET_MANUAL);
    public static final List<String> CONDITIONS = List.of(AnnunciatorLogic.ABOVE, AnnunciatorLogic.BELOW, AnnunciatorLogic.EQUALS);
    public static final int DEFAULT_COLOR = 0xFFB000;
    /**
     * The look after the annunciator boxes of Columbia Generating Station's P800 boards: ivory tray tiles on a black
     * plate, each tile split into two alarms, with row and column numerals, a nameplate and a latch round the box
     * (the tile model of {@code art/models/panel/annunciator}; the client's {@code AnnunciatorTileBox}).
     */
    public static final String STYLE_COLUMBIA = "columbia";
    /** The looks an annunciator window offers: the procedural one first, which is the default. */
    public static final List<String> STYLES = List.of(PanelStyles.CLASSIC, STYLE_COLUMBIA);
    /** Id of the tile model ({@code assets/bwr/models/panel/ann_window.json}). */
    public static final String WINDOW_MODEL = "ann_window";
    /** NBT key of {@link #boxName}. */
    public static final String KEY_BOX_NAME = "BoxName";
    public static final double LIMIT = 1.0e9;

    public String line1 = "";
    public String line2 = "";
    /** Lines 3 and 4 of the legend (a window prints up to four); saved only when not empty, so an old save is unchanged. */
    public String line3 = "";
    public String line4 = "";
    public int color = DEFAULT_COLOR;
    /**
     * Look: {@link PanelStyles#CLASSIC} (the procedural window) or {@link #STYLE_COLUMBIA}. Saved only when it is not
     * classic, so a window saved before the model existed, and one nobody restyled, saves exactly what it always did.
     */
    public String style = PanelStyles.CLASSIC;
    /**
     * The second line of the nameplate of the box this window is in (the Columbia style draws one under each box): the
     * first one that is not empty among the box's windows, in reading order, is shown, so it can be set on any of them.
     * Saved only when not empty.
     */
    public String boxName = "";
    public String condition = AnnunciatorLogic.ABOVE;
    public double setpoint = 0.5;
    public int group = MIN_GROUP;
    public String resetMode = RESET_AUTO;
    public final AnnunciatorLogic logic = new AnnunciatorLogic();

    /** Whether this window draws outside its own cells (the plate margin of its box): the Columbia style does. */
    public boolean overhangs() {
        return STYLE_COLUMBIA.equals(style);
    }

    /** The legend's non-empty lines, top to bottom (at most four); allocates, so call it once per revision. */
    public String[] legend() {
        return java.util.stream.Stream.of(line1, line2, line3, line4).filter(line -> !line.isEmpty()).toArray(String[]::new);
    }

    /** Whether the alarm condition holds for this input value. */
    public boolean alarming(double input) {
        return AnnunciatorLogic.evaluate(condition, input, setpoint);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Line1", line1);
        tag.putString("Line2", line2);
        if (!line3.isEmpty()) {
            tag.putString("Line3", line3);
        }
        if (!line4.isEmpty()) {
            tag.putString("Line4", line4);
        }
        tag.putInt("Color", color);
        if (!PanelStyles.CLASSIC.equals(style)) {
            tag.putString(PanelStyles.KEY_STYLE, style);
        }
        if (!boxName.isEmpty()) {
            tag.putString(KEY_BOX_NAME, boxName);
        }
        tag.putString("Condition", condition);
        tag.putDouble("Setpoint", setpoint);
        tag.putInt("AnnGroup", group);
        tag.putString("Reset", resetMode);
        tag.putInt("AlarmState", logic.stateId());
        tag.putBoolean("AlarmCondition", logic.condition());
    }

    @Override
    public void load(CompoundTag tag) {
        line1 = ConfigSchema.cleanText(tag.getString("Line1"), MAX_TEXT);
        line2 = ConfigSchema.cleanText(tag.getString("Line2"), MAX_TEXT);
        line3 = ConfigSchema.cleanText(tag.getString("Line3"), MAX_TEXT);
        line4 = ConfigSchema.cleanText(tag.getString("Line4"), MAX_TEXT);
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        String savedStyle = tag.getString(PanelStyles.KEY_STYLE);
        style = STYLES.contains(savedStyle) ? savedStyle : PanelStyles.CLASSIC;
        boxName = ConfigSchema.cleanText(tag.getString(KEY_BOX_NAME), MAX_TEXT);
        String savedCondition = tag.getString("Condition");
        condition = CONDITIONS.contains(savedCondition) ? savedCondition : AnnunciatorLogic.ABOVE;
        double savedSetpoint = tag.getDouble("Setpoint");
        setpoint = tag.contains("Setpoint", Tag.TAG_ANY_NUMERIC) && Double.isFinite(savedSetpoint)
                ? Math.max(-LIMIT, Math.min(LIMIT, savedSetpoint)) : 0.5;
        group = tag.contains("AnnGroup", Tag.TAG_ANY_NUMERIC) ? Math.max(MIN_GROUP, Math.min(MAX_GROUP, tag.getInt("AnnGroup"))) : MIN_GROUP;
        String savedReset = tag.getString("Reset");
        resetMode = RESET_MODES.contains(savedReset) ? savedReset : RESET_AUTO;
        logic.setManualReset(RESET_MANUAL.equals(resetMode));
        logic.restore(tag.getInt("AlarmState"), tag.getBoolean("AlarmCondition"));
    }
}
