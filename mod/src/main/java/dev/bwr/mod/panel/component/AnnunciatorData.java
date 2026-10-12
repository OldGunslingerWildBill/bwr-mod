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
    public static final double LIMIT = 1.0e9;

    public String line1 = "";
    public String line2 = "";
    public int color = DEFAULT_COLOR;
    public String condition = AnnunciatorLogic.ABOVE;
    public double setpoint = 0.5;
    public int group = MIN_GROUP;
    public String resetMode = RESET_AUTO;
    public final AnnunciatorLogic logic = new AnnunciatorLogic();

    /** Whether the alarm condition holds for this input value. */
    public boolean alarming(double input) {
        return AnnunciatorLogic.evaluate(condition, input, setpoint);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Line1", line1);
        tag.putString("Line2", line2);
        tag.putInt("Color", color);
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
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
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
