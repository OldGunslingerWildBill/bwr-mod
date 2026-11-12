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
    /** NBT and wrench-screen key of {@link #address}: five digits, present only when the window has an address. */
    public static final String KEY_ADDRESS = "Address";
    /** NBT key of the horn bit ({@link AnnunciatorLogic#audible()}), present only while the window is sounding. */
    public static final String KEY_AUDIBLE = "Audible";
    /** NBT and wrench-screen keys of the alarm sound: the choice (present only when it is not the panel alarm) and the custom id. */
    public static final String KEY_SOUND = "AlarmSound";
    public static final String KEY_CUSTOM_SOUND = "CustomSound";
    /** {@link #address} of a window that has none. */
    public static final int NO_ADDRESS = -1;
    public static final int MIN_ADDRESS = 0;
    public static final int MAX_ADDRESS = 10000;
    /** The lens colours the wrench screen offers as swatches (any other RGB is still accepted and kept). */
    public static final int COLOR_WHITE = 0xF0F0E0;
    public static final int COLOR_YELLOW = 0xFFD000;
    public static final int COLOR_RED = 0xE02020;
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
    /**
     * The light address a computer drives this window by ({@code setAlarm}, {@code setValue} and {@code getAlarm} of the
     * {@code bwr_panel} peripheral): {@link #MIN_ADDRESS} to {@link #MAX_ADDRESS}, or {@link #NO_ADDRESS}. Several
     * windows may share one (they are then driven together). Saved only when set, as five digits.
     */
    public int address = NO_ADDRESS;
    /**
     * What the horn is when this window sounds it ({@link AnnunciatorSounds}): the panel alarm, the synthesised horn, or
     * {@link #customSound}. Saved only when it is not the panel alarm.
     */
    public String alarmSound = AnnunciatorSounds.PANEL;
    /** The sound id of the custom choice, as typed ({@code namespace:path}); kept whatever the choice is, saved only when not empty. */
    public String customSound = "";
    public String condition = AnnunciatorLogic.ABOVE;
    public double setpoint = 0.5;
    public int group = MIN_GROUP;
    public String resetMode = RESET_AUTO;
    public final AnnunciatorLogic logic = new AnnunciatorLogic();

    /** Whether this window draws outside its own cells (the plate margin of its box): the Columbia style does. */
    public boolean overhangs() {
        return STYLE_COLUMBIA.equals(style);
    }

    /**
     * The id of the sound this window's horn is, by its setting; the panel alarm for anything that cannot be played as
     * chosen. A client still asks its own sound manager whether it knows a custom sound.
     */
    public String soundId(boolean customAllowed) {
        return AnnunciatorSounds.resolve(alarmSound, customSound, customAllowed);
    }

    /**
     * Server, on what is sent to clients while custom alarm sounds are switched off: a window's custom choice is left
     * out of its entry, so every client takes the window for one that sounds the panel alarm. The save keeps it.
     *
     * @param entry a component's network entry, with the window's data under {@code Data}
     */
    public static void withoutCustomSound(CompoundTag entry) {
        if (entry == null || !entry.contains("Data", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag data = entry.getCompound("Data");
        if (AnnunciatorSounds.CUSTOM.equals(data.getString(KEY_SOUND))) {
            data.remove(KEY_SOUND);
            data.remove(KEY_CUSTOM_SOUND);
        }
    }

    /** {@link #address} as the wrench screen and the save hold it: five digits, or empty when there is none. */
    public String addressText() {
        return address < MIN_ADDRESS ? "" : formatAddress(address);
    }

    /** An address as five digits ({@code 00042}). */
    public static String formatAddress(int address) {
        return String.format(java.util.Locale.ROOT, "%05d", address);
    }

    /**
     * Reads an address as typed or saved: digits only (surrounding blanks ignored), {@link #MIN_ADDRESS} to
     * {@link #MAX_ADDRESS}. Anything else, an empty text included, is {@link #NO_ADDRESS}.
     */
    public static int parseAddress(String text) {
        String digits = text == null ? "" : text.strip();
        if (digits.isEmpty() || digits.length() > 5) {
            return NO_ADDRESS;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return NO_ADDRESS;
            }
            value = value * 10 + (c - '0');
        }
        return value <= MAX_ADDRESS ? value : NO_ADDRESS;
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
        if (address >= MIN_ADDRESS) {
            tag.putString(KEY_ADDRESS, addressText());
        }
        if (!AnnunciatorSounds.PANEL.equals(alarmSound)) {
            tag.putString(KEY_SOUND, alarmSound);
        }
        if (!customSound.isEmpty()) {
            tag.putString(KEY_CUSTOM_SOUND, customSound);
        }
        tag.putString("Condition", condition);
        tag.putDouble("Setpoint", setpoint);
        tag.putInt("AnnGroup", group);
        tag.putString("Reset", resetMode);
        tag.putInt("AlarmState", logic.stateId());
        tag.putBoolean("AlarmCondition", logic.condition());
        if (logic.audible()) {
            tag.putBoolean(KEY_AUDIBLE, true);
        }
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
        address = parseAddress(tag.getString(KEY_ADDRESS));
        String savedSound = tag.getString(KEY_SOUND);
        alarmSound = AnnunciatorSounds.CHOICES.contains(savedSound) ? savedSound : AnnunciatorSounds.PANEL;
        customSound = AnnunciatorSounds.cleanId(ConfigSchema.cleanText(tag.getString(KEY_CUSTOM_SOUND), AnnunciatorSounds.MAX_ID));
        String savedCondition = tag.getString("Condition");
        condition = CONDITIONS.contains(savedCondition) ? savedCondition : AnnunciatorLogic.ABOVE;
        double savedSetpoint = tag.getDouble("Setpoint");
        setpoint = tag.contains("Setpoint", Tag.TAG_ANY_NUMERIC) && Double.isFinite(savedSetpoint)
                ? Math.max(-LIMIT, Math.min(LIMIT, savedSetpoint)) : 0.5;
        group = tag.contains("AnnGroup", Tag.TAG_ANY_NUMERIC) ? Math.max(MIN_GROUP, Math.min(MAX_GROUP, tag.getInt("AnnGroup"))) : MIN_GROUP;
        String savedReset = tag.getString("Reset");
        resetMode = RESET_MODES.contains(savedReset) ? savedReset : RESET_AUTO;
        logic.setManualReset(RESET_MANUAL.equals(resetMode));
        logic.restore(tag.getInt("AlarmState"), tag.getBoolean("AlarmCondition"), tag.getBoolean(KEY_AUDIBLE));
    }
}
