package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.component.RecorderAlarmLogic.Alarm;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.Event;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.Type;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Yokogawa DX1000 / DX2000 paperless recorder: configuration of every channel, group and display setting, the
 * operating state the front keys change, the min/max dot history the trend draws, the alarms and the alarm log.
 * The recorder only records and indicates: no alarm here drives anything, and nothing acts on the plant.
 *
 * <h3>Settings and saves</h3>
 * Everything is a flattened NBT key with a default ({@code Ch03Tag}, {@code Ch03Al2Type}, {@code G01C4},
 * {@code Interval}), in the style of the strip chart's {@code Pen1Color}. A key that equals its default is not
 * written, and a missing key loads as its default, so a recorder saved before a setting existed loads unchanged and
 * a mostly default 48 channel recorder is small. The settings are {@link #schema() declared fields} so the usual
 * {@code ConfigSchema.merge} validates every edit; the operating state, the history and the alarm states are latched
 * keys only the recorder's own code changes.
 *
 * <h3>History</h3>
 * A trend dot is {@link #DOTS_PER_DIVISION}th of a division of the trend interval. The recorder keeps, per channel,
 * the minimum and the maximum the signal took inside each dot in a ring of {@link #RING} dots. The server closes a dot
 * when the game clock passes its end ({@link #sample}, called by the panel sampler through
 * {@code sampleContent}, so no ticker of its own), and sends the closed dots to clients as small patches
 * ({@link #drainPatch}); clients never bucket on their own, so what they draw is what the server recorded. A dot in
 * which nothing was sampled holds the last value for short gaps and is a gap (NaN) after a long one, such as a
 * panel chunk that was unloaded. While memory sampling is stopped ({@code START}/{@code STOP}) no dot is closed and
 * nothing is logged; the alarms still indicate.
 *
 * <h3>Sync</h3>
 * The full state carries everything; a patch carries the newest dots (as absolute sequence numbers, so a client
 * that missed some fills the gap and one that already has them skips them), the alarm states, the log entries and
 * messages it has not yet seen (by sequence number too) and the operating state.
 */
public final class PaperlessRecorderData extends ComponentData {
    public static final int RING = 360;
    public static final int ALARMS = 4;
    public static final int MAX_CHANNELS = 48;
    public static final int MAX_GROUPS = 36;
    public static final int MAX_PER_GROUP = 10;
    public static final int TRIP_LINES = 4;
    public static final int LOG_MAX = 200;
    public static final int MESSAGES_MAX = 100;
    public static final int PRESETS = 10;
    public static final int DOTS_PER_DIVISION = 30;
    public static final int TAG_MAX = 16;
    public static final int COMMENT_MAX = 32;
    public static final int UNIT_MAX = 6;
    public static final int GROUP_NAME_MAX = 16;
    public static final int MESSAGE_MAX = 32;
    public static final double LIMIT = GaugeData.LIMIT;
    /** Trend interval per division, seconds (the DX's list: 5 s to 10 h). */
    public static final int[] INTERVAL_SECONDS = {5, 10, 15, 30, 60, 120, 300, 600, 900, 1200, 1800, 3600, 7200, 14400, 36000};
    public static final int DEFAULT_INTERVAL = 4;
    /** A gap of at most this many dots repeats the last value; a longer one is a break in the trace. */
    private static final int HOLD_GAP_DOTS = 3;
    public static final String LAYOUT_HORIZONTAL = "horizontal";
    public static final String LAYOUT_VERTICAL = "vertical";
    public static final String LAYOUT_WIDE = "wide";
    public static final List<String> LAYOUTS = List.of(LAYOUT_HORIZONTAL, LAYOUT_VERTICAL, LAYOUT_WIDE);
    public static final String BACKGROUND_WHITE = "white";
    public static final String BACKGROUND_BLACK = "black";
    public static final List<String> BACKGROUNDS = List.of(BACKGROUND_WHITE, BACKGROUND_BLACK);
    public static final String TAG_TAG = "tag";
    public static final String TAG_NUMBER = "number";
    public static final List<String> TAG_MODES = List.of(TAG_TAG, TAG_NUMBER);
    public static final int DEFAULT_ALARM_COLOR = 0xE00000;
    public static final int DEFAULT_TRIP_COLOR = 0xE00000;
    /** The 24 channel colours of the DX (manual 5.5), in its order; channels 1 to 10 default to the first ten. */
    public static final int[] PALETTE = {
            0xE00000, 0x009000, 0x0000F0, 0x8A2BE2, 0x964B00, 0xFF8C00, 0x9ACD32, 0x87CEEB,
            0xEE82EE, 0x808080, 0x80FF00, 0x00CED1, 0x000080, 0xE6C800, 0xC0C0C0, 0x800080,
            0x000000, 0xFF69B4, 0xC8A060, 0x90EE90, 0x404040, 0x808000, 0x008B8B, 0x00FF7F};
    /** What the keys do to a scale: a value beyond this fraction of the span reads "Over". */
    public static final double OVER_RANGE = 1.05;

    // ---- per channel and per group settings

    /** One alarm point of a channel. */
    public static final class AlarmCfg {
        public String type = "off";
        public double value;
        public double hyst;
        /** Delay in seconds for T and t, interval in scans for R and r. */
        public int time;
        public int level = 1;
        public int color = DEFAULT_ALARM_COLOR;
        public boolean hidden;

        /** The alarm type, or null when the point is off. */
        public Type kind() {
            return Type.of(type);
        }

        boolean sameAs(AlarmCfg other) {
            return type.equals(other.type) && Double.compare(value, other.value) == 0
                    && Double.compare(hyst, other.hyst) == 0 && time == other.time && hidden == other.hidden;
        }
    }

    /** One measurement channel. */
    public static final class Channel {
        public boolean on = true;
        public String tag = "";
        public String comment = "";
        public String unit = "";
        public double lo;
        public double hi = 100.0;
        public int dec = 1;
        public int color;
        public double zoneLo;
        public double zoneHi = 100.0;
        public final AlarmCfg[] alarms = new AlarmCfg[ALARMS];

        Channel(int index) {
            color = PALETTE[index % PALETTE.length];
            for (int i = 0; i < ALARMS; i++) {
                alarms[i] = new AlarmCfg();
            }
        }

        public double span() {
            return hi > lo ? hi - lo : 1.0;
        }
    }

    /** One display group: a name and channel slots (0 is empty, otherwise a channel number), and up to four trip lines. */
    public static final class Group {
        public String name = "";
        public final int[] slots = new int[MAX_PER_GROUP];
        public final boolean[] tripOn = new boolean[TRIP_LINES];
        /** Trip line position, percent of the trend height. */
        public final double[] tripPos = new double[TRIP_LINES];
        public final int[] tripColor = new int[TRIP_LINES];
        public final int[] tripWidth = new int[TRIP_LINES];

        Group(RecorderStyle style, int index) {
            for (int s = 0; s < MAX_PER_GROUP; s++) {
                slots[s] = defaultSlot(style, index, s);
            }
            Arrays.fill(tripPos, 50.0);
            Arrays.fill(tripColor, DEFAULT_TRIP_COLOR);
            Arrays.fill(tripWidth, 1);
        }
    }

    /** One alarm log entry. */
    public record LogEntry(long seq, long tick, long epochSeconds, int channel, int alarm, int kind) {
        public static final int ON = 0;
        public static final int OFF = 1;
        public static final int ACK = 2;
    }

    /** One message written on the trend. */
    public record Message(long seq, long dot, String text) {
    }

    // ---- settings

    public RecorderStyle style = RecorderStyle.DX1000;
    public int channels = RecorderStyle.DX1000.defaultChannels();
    public int interval = DEFAULT_INTERVAL;
    public String layout = LAYOUT_HORIZONTAL;
    public int grid = 5;
    public int lineWidth = 1;
    public String background = BACKGROUND_WHITE;
    public String tagMode = TAG_TAG;
    /** Seconds between automatic group changes, 0 for off (a client side display choice derived from the game clock). */
    public int autoSwitch;
    public boolean realClock;
    public boolean hold = true;
    public int ackGroup = AnnunciatorData.MIN_GROUP;
    public final String[] presets = new String[PRESETS];
    private final Channel[] channelCfg = new Channel[MAX_CHANNELS];
    private final Group[] groupCfg = new Group[MAX_GROUPS];

    // ---- operating state (saved; the front keys change it)

    public RecorderScreen screen = RecorderScreen.TREND;
    /** The group the trend, digital and bar screens show (0-based). */
    public int currentGroup;
    /** Memory sampling: whether closed dots and alarm events are recorded. */
    public boolean running = true;
    public boolean coverOpen;
    /** The four-panel layout: 0 MIX, 1 all trend, 2 all digital, 3 all bar. */
    public int quad;

    // ---- history

    private final float[][] dotMin = new float[MAX_CHANNELS][];
    private final float[][] dotMax = new float[MAX_CHANNELS][];
    private long dotSeq;
    /** Game tick the open dot began; valid while {@link #started}. */
    private double dotStart;
    private boolean started;
    private final float[] accMin = new float[MAX_CHANNELS];
    private final float[] accMax = new float[MAX_CHANNELS];
    private final float[] lastValue = new float[MAX_CHANNELS];
    private final double[] sampleBuffer = new double[MAX_CHANNELS];
    private final Alarm[][] alarmState = new Alarm[MAX_CHANNELS][];
    private final ArrayDeque<LogEntry> log = new ArrayDeque<>();
    private long logSeq;
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private long messageSeq;
    private long lastNow;
    /** True while {@link #dotStart} holds an age from a load, not yet a game tick. */
    private boolean rebasePending;

    // ---- change tracking

    private int version;
    private long sentDotSeq;
    private long sentLogSeq;
    private long sentMessageSeq;
    private boolean pendingAlarms;
    private boolean pendingOperating;

    public PaperlessRecorderData() {
        this(RecorderStyle.DX1000);
    }

    public PaperlessRecorderData(RecorderStyle style) {
        applyStyle(style);
        Arrays.fill(accMin, Float.NaN);
        Arrays.fill(accMax, Float.NaN);
        Arrays.fill(lastValue, Float.NaN);
        resetSettings();
    }

    private void applyStyle(RecorderStyle newStyle) {
        this.style = newStyle;
        this.channels = newStyle.defaultChannels();
    }

    /** Every setting back to its default (the style and channel count stay). */
    private void resetSettings() {
        interval = DEFAULT_INTERVAL;
        layout = LAYOUT_HORIZONTAL;
        grid = 5;
        lineWidth = 1;
        background = BACKGROUND_WHITE;
        tagMode = TAG_TAG;
        autoSwitch = 0;
        realClock = false;
        hold = true;
        ackGroup = AnnunciatorData.MIN_GROUP;
        for (int i = 0; i < PRESETS; i++) {
            presets[i] = defaultPreset(i);
        }
        for (int i = 0; i < MAX_CHANNELS; i++) {
            channelCfg[i] = new Channel(i);
        }
        for (int i = 0; i < MAX_GROUPS; i++) {
            groupCfg[i] = new Group(style, i);
        }
    }

    public static String defaultPreset(int index) {
        return "MESSAGE " + (index + 1);
    }

    /** The default channel of slot {@code slot} of group {@code group} (0-based), or 0 for none. */
    public static int defaultSlot(RecorderStyle style, int group, int slot) {
        int channel = group * style.perGroup() + slot + 1;
        return slot < style.perGroup() && group < style.groups() && channel <= style.maxChannels() ? channel : 0;
    }

    // ---- schema

    private static String two(int number) {
        return number < 10 ? "0" + number : Integer.toString(number);
    }

    public static String channelKey(int channel, String suffix) {
        return "Ch" + two(channel + 1) + suffix;
    }

    public static String alarmKey(int channel, int alarm, String suffix) {
        return channelKey(channel, "Al" + (alarm + 1) + suffix);
    }

    public static String groupKey(int group, String suffix) {
        return "G" + two(group + 1) + suffix;
    }

    public static String tripKey(int group, int line, String suffix) {
        return groupKey(group, "Trip" + (line + 1) + suffix);
    }

    private static final String L = "component.bwr.field.rec_";

    /** Every editable setting of every channel and group the largest make has; the style's limits are applied on merge. */
    public static List<ConfigField> schema() {
        List<ConfigField> f = new ArrayList<>();
        f.add(ConfigField.choice(RecorderStyle.KEY, L + "style", RecorderStyle.ids()));
        f.add(ConfigField.integer("Channels", L + "channels", 2, MAX_CHANNELS));
        f.add(ConfigField.integer("Interval", L + "interval", 0, INTERVAL_SECONDS.length - 1));
        f.add(ConfigField.choice("Layout", L + "layout", LAYOUTS));
        f.add(ConfigField.integer("Grid", L + "grid", 4, 12));
        f.add(ConfigField.integer("LineWidth", L + "line_width", 1, 3));
        f.add(ConfigField.choice("Background", L + "background", BACKGROUNDS));
        f.add(ConfigField.choice("TagMode", L + "tag_mode", TAG_MODES));
        f.add(ConfigField.integer("AutoSwitch", L + "auto_switch", 0, 60));
        f.add(ConfigField.bool("RealClock", L + "real_clock"));
        f.add(ConfigField.bool("Hold", L + "hold"));
        f.add(ConfigField.integer("AckGroup", L + "ack_group", AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP));
        for (int i = 0; i < PRESETS; i++) {
            f.add(ConfigField.text("Msg" + (i + 1), L + "message", MESSAGE_MAX));
        }
        for (int c = 0; c < MAX_CHANNELS; c++) {
            f.add(ConfigField.bool(channelKey(c, "On"), L + "ch_on"));
            f.add(ConfigField.text(channelKey(c, "Tag"), L + "ch_tag", TAG_MAX));
            f.add(ConfigField.text(channelKey(c, "Comment"), L + "ch_comment", COMMENT_MAX));
            f.add(ConfigField.text(channelKey(c, "Unit"), L + "ch_unit", UNIT_MAX));
            f.add(ConfigField.decimal(channelKey(c, "Lo"), L + "ch_lo", -LIMIT, LIMIT));
            f.add(ConfigField.decimal(channelKey(c, "Hi"), L + "ch_hi", -LIMIT, LIMIT));
            f.add(ConfigField.integer(channelKey(c, "Dec"), L + "ch_dec", 0, 4));
            f.add(ConfigField.color(channelKey(c, "Color"), L + "ch_color"));
            f.add(ConfigField.decimal(channelKey(c, "ZoneLo"), L + "ch_zone_lo", 0, 100));
            f.add(ConfigField.decimal(channelKey(c, "ZoneHi"), L + "ch_zone_hi", 0, 100));
            for (int a = 0; a < ALARMS; a++) {
                f.add(ConfigField.choice(alarmKey(c, a, "Type"), L + "al_type", RecorderAlarmLogic.TYPE_OPTIONS));
                f.add(ConfigField.decimal(alarmKey(c, a, "Value"), L + "al_value", -LIMIT, LIMIT));
                f.add(ConfigField.decimal(alarmKey(c, a, "Hyst"), L + "al_hyst", 0, LIMIT));
                f.add(ConfigField.integer(alarmKey(c, a, "Time"), L + "al_time", 0, 3600));
                f.add(ConfigField.integer(alarmKey(c, a, "Level"), L + "al_level", 1, 4));
                f.add(ConfigField.color(alarmKey(c, a, "Color"), L + "al_color"));
                f.add(ConfigField.bool(alarmKey(c, a, "Hidden"), L + "al_hidden"));
            }
        }
        for (int g = 0; g < MAX_GROUPS; g++) {
            f.add(ConfigField.text(groupKey(g, "Name"), L + "g_name", GROUP_NAME_MAX));
            for (int s = 0; s < MAX_PER_GROUP; s++) {
                f.add(ConfigField.integer(groupKey(g, "C" + (s + 1)), L + "g_slot", 0, MAX_CHANNELS));
            }
            for (int t = 0; t < TRIP_LINES; t++) {
                f.add(ConfigField.bool(tripKey(g, t, "On"), L + "trip_on"));
                f.add(ConfigField.decimal(tripKey(g, t, "Pos"), L + "trip_pos", 0, 100));
                f.add(ConfigField.color(tripKey(g, t, "Color"), L + "trip_color"));
                f.add(ConfigField.integer(tripKey(g, t, "Width"), L + "trip_width", 1, 3));
            }
        }
        return List.copyOf(f);
    }

    // ---- access

    public Channel channel(int index) {
        return channelCfg[Math.max(0, Math.min(MAX_CHANNELS - 1, index))];
    }

    public Group group(int index) {
        return groupCfg[Math.max(0, Math.min(MAX_GROUPS - 1, index))];
    }

    /** The group's 0-based channel at {@code slot}, or -1 when the slot is empty or names a channel this recorder does not have. */
    public int groupChannel(int group, int slot) {
        if (group < 0 || group >= style.groups() || slot < 0 || slot >= style.perGroup()) {
            return -1;
        }
        int number = groupCfg[group].slots[slot];
        return number >= 1 && number <= channels ? number - 1 : -1;
    }

    /** How many channels the group shows. */
    public int groupSize(int group) {
        int n = 0;
        for (int s = 0; s < style.perGroup(); s++) {
            if (groupChannel(group, s) >= 0) {
                n++;
            }
        }
        return n;
    }

    /** The group's channels in slot order. */
    public int[] groupChannels(int group) {
        int[] out = new int[groupSize(group)];
        int n = 0;
        for (int s = 0; s < style.perGroup(); s++) {
            int channel = groupChannel(group, s);
            if (channel >= 0) {
                out[n++] = channel;
            }
        }
        return out;
    }

    public String groupName(int group) {
        String name = group(group).name;
        return name.isEmpty() ? "GROUP " + (group + 1) : name;
    }

    /** Groups that show at least one channel, in order. */
    public int[] activeGroups() {
        int[] tmp = new int[style.groups()];
        int n = 0;
        for (int g = 0; g < style.groups(); g++) {
            if (groupSize(g) > 0) {
                tmp[n++] = g;
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    /** The label of a channel: its tag, or its number when the tag is empty or the recorder shows numbers. */
    public String channelLabel(int channel) {
        Channel c = channel(channel);
        if (tagMode.equals(TAG_NUMBER) || c.tag.isEmpty()) {
            return two(channel + 1);
        }
        return c.tag;
    }

    public double dotTicks() {
        return INTERVAL_SECONDS[Math.max(0, Math.min(INTERVAL_SECONDS.length - 1, interval))] * 20.0 / DOTS_PER_DIVISION;
    }

    public int intervalSeconds() {
        return INTERVAL_SECONDS[Math.max(0, Math.min(INTERVAL_SECONDS.length - 1, interval))];
    }

    /** Bumped on every change a renderer must repaint for: a patch, a load, an acknowledgement, a key. Not saved. */
    public int version() {
        return version;
    }

    public void touchVersion() {
        version++;
    }

    // ---- history access

    /** Dots closed so far (a sequence number: the newest closed dot is {@code dotCount() - 1}). */
    public long dotCount() {
        return dotSeq;
    }

    /** Dots the ring still holds. */
    public int storedDots() {
        return (int) Math.min(dotSeq, RING);
    }

    /** Minimum of the channel inside dot {@code seq}; NaN when the dot is gone, never recorded or had no signal. */
    public float dotMin(int channel, long seq) {
        float[] row = channel >= 0 && channel < MAX_CHANNELS ? dotMin[channel] : null;
        return row == null || seq < 0 || seq >= dotSeq || seq < dotSeq - RING ? Float.NaN : row[(int) (seq % RING)];
    }

    public float dotMax(int channel, long seq) {
        float[] row = channel >= 0 && channel < MAX_CHANNELS ? dotMax[channel] : null;
        return row == null || seq < 0 || seq >= dotSeq || seq < dotSeq - RING ? Float.NaN : row[(int) (seq % RING)];
    }

    /** The open dot's minimum so far, NaN when nothing was sampled in it yet. */
    public float openMin(int channel) {
        return channel >= 0 && channel < MAX_CHANNELS ? accMin[channel] : Float.NaN;
    }

    public float openMax(int channel) {
        return channel >= 0 && channel < MAX_CHANNELS ? accMax[channel] : Float.NaN;
    }

    private float[] row(float[][] rows, int channel) {
        if (rows[channel] == null) {
            rows[channel] = new float[RING];
            Arrays.fill(rows[channel], Float.NaN);
        }
        return rows[channel];
    }

    public List<LogEntry> logEntries() {
        return new ArrayList<>(log);
    }

    public int logSize() {
        return log.size();
    }

    public long logSequence() {
        return logSeq;
    }

    public List<Message> messageEntries() {
        return new ArrayList<>(messages);
    }

    public long messageSequence() {
        return messageSeq;
    }

    // ---- alarm access

    /** The alarm state of alarm {@code alarm} of channel {@code channel} (NORMAL when it never ran). */
    public RecorderAlarmLogic.State alarmStateOf(int channel, int alarm) {
        Alarm[] row = channel >= 0 && channel < MAX_CHANNELS ? alarmState[channel] : null;
        return row == null || row[alarm] == null ? RecorderAlarmLogic.State.NORMAL : row[alarm].state();
    }

    private Alarm alarm(int channel, int alarm) {
        if (alarmState[channel] == null) {
            alarmState[channel] = new Alarm[ALARMS];
        }
        if (alarmState[channel][alarm] == null) {
            alarmState[channel][alarm] = new Alarm();
        }
        return alarmState[channel][alarm];
    }

    /** The alarm of the channel that sets its indication: the active one of the lowest level (most urgent), or -1. */
    public int topAlarm(int channel) {
        int best = -1;
        for (int a = 0; a < ALARMS; a++) {
            RecorderAlarmLogic.State state = alarmStateOf(channel, a);
            if (state.condition() && (best < 0 || channel(channel).alarms[a].level < channel(channel).alarms[best].level)) {
                best = a;
            }
        }
        return best;
    }

    /** The channel's alarm that needs attention most: an unacknowledged one first, otherwise an active one; -1 for none. */
    public int markedAlarm(int channel) {
        int unack = -1;
        int active = -1;
        for (int a = 0; a < ALARMS; a++) {
            RecorderAlarmLogic.State state = alarmStateOf(channel, a);
            if (state == RecorderAlarmLogic.State.NORMAL) {
                continue;
            }
            if (state.unacknowledged() && (unack < 0 || channel(channel).alarms[a].level < channel(channel).alarms[unack].level)) {
                unack = a;
            } else if (state.condition() && active < 0) {
                active = a;
            }
        }
        return unack >= 0 ? unack : active;
    }

    /** Alarms that are on and not yet acknowledged, over every channel. */
    public int unacknowledgedCount() {
        int n = 0;
        for (int c = 0; c < channels; c++) {
            for (int a = 0; a < ALARMS; a++) {
                if (alarmStateOf(c, a) == RecorderAlarmLogic.State.ACTIVE_UNACK) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Alarms that have cleared but are not yet acknowledged. */
    public int clearedUnacknowledgedCount() {
        int n = 0;
        for (int c = 0; c < channels; c++) {
            for (int a = 0; a < ALARMS; a++) {
                if (alarmStateOf(c, a) == RecorderAlarmLogic.State.CLEARED_UNACK) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Alarms whose condition holds now, acknowledged or not. */
    public int activeCount() {
        int n = 0;
        for (int c = 0; c < channels; c++) {
            for (int a = 0; a < ALARMS; a++) {
                if (alarmStateOf(c, a).condition()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- server: sampling

    @Override
    public boolean syncsByPatch() {
        return true;
    }

    /**
     * Server: one sampler pass. Closes the dots whose time has passed, adds the channels' current values to the open
     * dot and runs the alarms. {@code values} is the channel's value (NaN for no signal) per channel.
     *
     * @return whether anything a client must be told changed (a dot closed, an alarm moved)
     */
    public boolean sample(long now, double[] values) {
        rebase(now);
        lastNow = now;
        boolean changed = advance(now);
        if (running && started) {
            for (int c = 0; c < channels && c < values.length; c++) {
                if (!channelCfg[c].on || Double.isNaN(values[c])) {
                    continue;
                }
                float v = (float) values[c];
                accMin[c] = Float.isNaN(accMin[c]) ? v : Math.min(accMin[c], v);
                accMax[c] = Float.isNaN(accMax[c]) ? v : Math.max(accMax[c], v);
                lastValue[c] = v;
            }
        }
        changed |= evaluate(now, values);
        if (changed) {
            version++;
        }
        return changed;
    }

    /** Server: {@link #sample(long, double[])} with the component's inputs, then reports content changes. */
    @Override
    public void sampleContent(net.minecraft.world.level.Level level, dev.bwr.mod.panel.PanelBlockEntity owner,
                              PanelComponent component) {
        long now = host() == null ? lastNow : host().gameTime();
        for (int c = 0; c < MAX_CHANNELS; c++) {
            sampleBuffer[c] = c < channels ? component.input(c) : Double.NaN;
        }
        if (sample(now, sampleBuffer)) {
            contentChanged();
        }
    }

    private void rebase(long now) {
        if (rebasePending) {
            rebasePending = false;
            dotStart = now - dotStart; // dotStart held the age of the open dot at save time
        }
    }

    private boolean advance(long now) {
        if (!running) {
            started = false;
            return false;
        }
        if (!started) {
            dotStart = now;
            started = true;
            clearOpen();
            Arrays.fill(lastValue, Float.NaN);
            return false;
        }
        double length = dotTicks();
        double elapsed = now - dotStart;
        if (elapsed < length) {
            return false;
        }
        long due = (long) (elapsed / length);
        long skipped = Math.max(0, due - RING);
        dotSeq += skipped; // dots older than the ring can hold are gone before they are written
        long remaining = due - skipped;
        for (long k = 0; k < remaining; k++) {
            boolean first = skipped == 0 && k == 0;
            for (int c = 0; c < channels; c++) {
                float min;
                float max;
                if (first && !Float.isNaN(accMin[c])) {
                    min = accMin[c];
                    max = accMax[c];
                } else if (first || due <= HOLD_GAP_DOTS) {
                    min = lastValue[c];
                    max = lastValue[c];
                } else {
                    min = Float.NaN;
                    max = Float.NaN;
                }
                int slot = (int) (dotSeq % RING);
                row(dotMin, c)[slot] = min;
                row(dotMax, c)[slot] = max;
            }
            dotSeq++;
        }
        dotStart += due * length;
        clearOpen();
        return true;
    }

    private void clearOpen() {
        Arrays.fill(accMin, Float.NaN);
        Arrays.fill(accMax, Float.NaN);
    }

    private boolean evaluate(long now, double[] values) {
        boolean changed = false;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            Alarm[] row = alarmState[c];
            boolean live = c < channels && channelCfg[c].on;
            for (int a = 0; a < ALARMS; a++) {
                AlarmCfg cfg = live ? channelCfg[c].alarms[a] : null;
                Type type = cfg == null || cfg.hidden ? null : cfg.kind();
                if (type == null) {
                    if (row != null && row[a] != null && row[a].clear()) {
                        changed = true;
                        pendingAlarms = true;
                    }
                    continue;
                }
                double value = c < values.length ? values[c] : Double.NaN;
                Event event = alarm(c, a).step(type, value, cfg.value, cfg.hyst, cfg.time, hold, now);
                if (event != Event.NONE) {
                    changed = true;
                    pendingAlarms = true;
                    logEvent(now, c, a, event == Event.ACTIVATED ? LogEntry.ON : LogEntry.OFF);
                }
            }
        }
        return changed;
    }

    private void logEvent(long now, int channel, int alarm, int kind) {
        if (!running) {
            return; // memory sampling is stopped: the indication works, the history is not written
        }
        log.addLast(new LogEntry(logSeq++, now, System.currentTimeMillis() / 1000L, channel, alarm, kind));
        while (log.size() > LOG_MAX) {
            log.removeFirst();
        }
    }

    // ---- server: operations

    /** ACK: acknowledges every alarm that wants it. Returns how many changed. */
    public int acknowledgeAll() {
        long now = host() == null ? lastNow : host().gameTime();
        int changed = 0;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            if (alarmState[c] == null) {
                continue;
            }
            for (int a = 0; a < ALARMS; a++) {
                if (alarmState[c][a] != null && alarmState[c][a].acknowledge() == Event.ACKNOWLEDGED) {
                    changed++;
                    logEvent(now, c, a, LogEntry.ACK);
                }
            }
        }
        if (changed > 0) {
            pendingAlarms = true;
            version++;
            contentChanged();
        }
        return changed;
    }

    public boolean setRunning(boolean on) {
        if (running == on) {
            return false;
        }
        running = on;
        if (on) {
            started = false; // a new dot starts now
            if (dotSeq > 0) {
                breakTrace();
            }
        }
        operatingChanged();
        return true;
    }

    /** One gap dot after a stop, so the trace shows the break. */
    private void breakTrace() {
        int slot = (int) (dotSeq % RING);
        for (int c = 0; c < channels; c++) {
            row(dotMin, c)[slot] = Float.NaN;
            row(dotMax, c)[slot] = Float.NaN;
        }
        dotSeq++;
    }

    public boolean setScreen(RecorderScreen next) {
        if (next == null || !next.availableOn(style) || next == screen) {
            return false;
        }
        screen = next;
        operatingChanged();
        return true;
    }

    public boolean setQuad(int layoutIndex) {
        if (style != RecorderStyle.DX2000) {
            return false;
        }
        int clamped = Math.max(0, Math.min(3, layoutIndex));
        boolean changed = quad != clamped || screen != RecorderScreen.FOUR_PANEL;
        quad = clamped;
        screen = RecorderScreen.FOUR_PANEL;
        if (changed) {
            operatingChanged();
        }
        return changed;
    }

    /** Up and down: trend, digital and bar in turn (up goes back toward trend); from another screen they start the list. */
    public boolean stepScreen(int direction) {
        RecorderScreen[] ring = {RecorderScreen.TREND, RecorderScreen.DIGITAL, RecorderScreen.BAR};
        int at = -1;
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == screen) {
                at = i;
            }
        }
        RecorderScreen next = at < 0 ? ring[0] : ring[Math.floorMod(at + direction, ring.length)];
        return setScreen(next);
    }

    /** Left and right: the next group that shows channels. */
    public boolean stepGroup(int direction) {
        int[] groups = activeGroups();
        if (groups.length == 0) {
            return false;
        }
        int at = -1;
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == currentGroup) {
                at = i;
            }
        }
        int next = at < 0 ? groups[0] : groups[Math.floorMod(at + direction, groups.length)];
        if (next == currentGroup) {
            return false;
        }
        currentGroup = next;
        operatingChanged();
        return true;
    }

    public boolean toggleCover() {
        if (!style.hasCover()) {
            return false;
        }
        coverOpen = !coverOpen;
        operatingChanged();
        return true;
    }

    private void operatingChanged() {
        pendingOperating = true;
        version++;
        contentChanged();
    }

    /** Writes {@code text} on the trend at the current position (the dot being drawn). Returns whether it was written. */
    public boolean addMessage(String text) {
        String clean = ConfigSchema.cleanText(text == null ? "" : text, MESSAGE_MAX).strip();
        if (clean.isEmpty() || !running) {
            return false;
        }
        messages.addLast(new Message(messageSeq++, dotSeq, clean));
        while (messages.size() > MESSAGES_MAX) {
            messages.removeFirst();
        }
        version++;
        contentChanged();
        return true;
    }

    /** Message {@code number} (1 to 10): written at the current position. */
    public boolean addPresetMessage(int number) {
        return number >= 1 && number <= PRESETS && addMessage(presets[number - 1]);
    }

    // ---- patches

    @Override
    public byte[] drainPatch() {
        boolean dots = dotSeq > sentDotSeq;
        boolean logs = logSeq > sentLogSeq;
        boolean msgs = messageSeq > sentMessageSeq;
        if (!dots && !logs && !msgs && !pendingAlarms && !pendingOperating) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(1);
            int flags = (dots ? 1 : 0) | (pendingAlarms ? 2 : 0) | (logs ? 4 : 0) | (msgs ? 8 : 0) | (pendingOperating ? 16 : 0);
            out.writeByte(flags);
            out.writeByte(channels);
            if (dots) {
                int budget = Math.max(1, (ComponentData.MAX_PATCH - 7000) / (channels * 8));
                int n = (int) Math.min(Math.min(dotSeq - sentDotSeq, RING), budget);
                out.writeLong(dotSeq);
                out.writeShort(n);
                for (int c = 0; c < channels; c++) {
                    for (int k = 0; k < n; k++) {
                        long seq = dotSeq - n + k;
                        out.writeFloat(dotMin(c, seq));
                        out.writeFloat(dotMax(c, seq));
                    }
                }
            }
            if (pendingAlarms) {
                for (int c = 0; c < channels; c++) {
                    for (int a = 0; a < ALARMS; a++) {
                        out.writeByte(alarmStateOf(c, a).ordinal());
                    }
                }
            }
            if (logs) {
                List<LogEntry> fresh = new ArrayList<>();
                for (LogEntry entry : log) {
                    if (entry.seq() >= sentLogSeq) {
                        fresh.add(entry);
                    }
                }
                int from = Math.max(0, fresh.size() - 100);
                out.writeShort(fresh.size() - from);
                for (int i = from; i < fresh.size(); i++) {
                    writeLog(out, fresh.get(i));
                }
                out.writeLong(logSeq);
            }
            if (msgs) {
                List<Message> fresh = new ArrayList<>();
                for (Message message : messages) {
                    if (message.seq() >= sentMessageSeq) {
                        fresh.add(message);
                    }
                }
                int from = Math.max(0, fresh.size() - 50);
                out.writeShort(fresh.size() - from);
                for (int i = from; i < fresh.size(); i++) {
                    out.writeLong(fresh.get(i).seq());
                    out.writeLong(fresh.get(i).dot());
                    out.writeUTF(fresh.get(i).text());
                }
                out.writeLong(messageSeq);
            }
            if (pendingOperating) {
                out.writeByte(screen.ordinal());
                out.writeByte(currentGroup);
                out.writeByte((running ? 1 : 0) | (coverOpen ? 2 : 0));
                out.writeByte(quad);
            }
        } catch (IOException e) {
            return null;
        }
        sentDotSeq = dotSeq;
        sentLogSeq = logSeq;
        sentMessageSeq = messageSeq;
        pendingAlarms = false;
        pendingOperating = false;
        byte[] patch = bytes.toByteArray();
        return patch.length <= ComponentData.MAX_PATCH ? patch : null;
    }

    private static void writeLog(DataOutputStream out, LogEntry entry) throws IOException {
        out.writeLong(entry.seq());
        out.writeLong(entry.tick());
        out.writeLong(entry.epochSeconds());
        out.writeShort(entry.channel());
        out.writeByte(entry.alarm());
        out.writeByte(entry.kind());
    }

    @Override
    public void applyPatch(byte[] patch) {
        if (patch == null || patch.length < 3 || patch.length > ComponentData.MAX_PATCH) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(patch))) {
            if (in.readByte() != 1) {
                return;
            }
            int flags = in.readUnsignedByte();
            int count = in.readUnsignedByte();
            if (count != channels) {
                return; // a setting changed that this client has not got yet; the full state that follows fixes it
            }
            if ((flags & 1) != 0) {
                long end = in.readLong();
                int n = in.readUnsignedShort();
                if (n < 0 || n > RING || end < n) {
                    return;
                }
                float[][] mins = new float[channels][n];
                float[][] maxs = new float[channels][n];
                for (int c = 0; c < channels; c++) {
                    for (int k = 0; k < n; k++) {
                        mins[c][k] = in.readFloat();
                        maxs[c][k] = in.readFloat();
                    }
                }
                storeDots(end, n, mins, maxs);
            }
            if ((flags & 2) != 0) {
                for (int c = 0; c < channels; c++) {
                    for (int a = 0; a < ALARMS; a++) {
                        int state = in.readUnsignedByte();
                        if (state != alarmStateOf(c, a).ordinal()) {
                            alarm(c, a).restore(state);
                        }
                    }
                }
            }
            if ((flags & 4) != 0) {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    LogEntry entry = new LogEntry(in.readLong(), in.readLong(), in.readLong(), in.readShort(),
                            in.readByte(), in.readByte());
                    if (entry.seq() >= logSeq) {
                        log.addLast(entry);
                    }
                }
                logSeq = Math.max(logSeq, in.readLong());
                while (log.size() > LOG_MAX) {
                    log.removeFirst();
                }
            }
            if ((flags & 8) != 0) {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    Message message = new Message(in.readLong(), in.readLong(), in.readUTF());
                    if (message.seq() >= messageSeq) {
                        messages.addLast(message);
                    }
                }
                messageSeq = Math.max(messageSeq, in.readLong());
                while (messages.size() > MESSAGES_MAX) {
                    messages.removeFirst();
                }
            }
            if ((flags & 16) != 0) {
                RecorderScreen[] screens = RecorderScreen.values();
                int s = in.readUnsignedByte();
                currentGroup = Math.max(0, Math.min(MAX_GROUPS - 1, in.readUnsignedByte()));
                int bits = in.readUnsignedByte();
                quad = Math.max(0, Math.min(3, in.readUnsignedByte()));
                screen = s < screens.length && screens[s].availableOn(style) ? screens[s] : RecorderScreen.TREND;
                running = (bits & 1) != 0;
                coverOpen = (bits & 2) != 0 && style.hasCover();
            }
            version++;
        } catch (IOException | RuntimeException e) {
            // a malformed patch changes nothing more than it already did
        }
    }

    /** Client: stores the newest {@code n} closed dots ending at sequence {@code end}; earlier ones the client already has are skipped. */
    private void storeDots(long end, int n, float[][] mins, float[][] maxs) {
        long first = end - n;
        if (end <= dotSeq) {
            return;
        }
        if (first > dotSeq) {
            // dots were missed: a gap in the trace
            long gap = Math.min(first - dotSeq, RING);
            for (long g = 0; g < gap; g++) {
                int slot = (int) ((first - gap + g) % RING);
                for (int c = 0; c < channels; c++) {
                    row(dotMin, c)[slot] = Float.NaN;
                    row(dotMax, c)[slot] = Float.NaN;
                }
            }
            dotSeq = first;
        }
        for (long seq = Math.max(first, dotSeq); seq < end; seq++) {
            int k = (int) (seq - first);
            int slot = (int) (seq % RING);
            for (int c = 0; c < channels; c++) {
                row(dotMin, c)[slot] = mins[c][k];
                row(dotMax, c)[slot] = maxs[c][k];
            }
        }
        dotSeq = end;
    }

    // ---- persistence

    private void putInt(CompoundTag tag, String key, int value, int fallback) {
        if (dense || value != fallback) {
            tag.putInt(key, value);
        }
    }

    private void putBool(CompoundTag tag, String key, boolean value, boolean fallback) {
        if (dense || value != fallback) {
            tag.putBoolean(key, value);
        }
    }

    private void putDouble(CompoundTag tag, String key, double value, double fallback) {
        if (dense || Double.compare(value, fallback) != 0) {
            tag.putDouble(key, value);
        }
    }

    private void putText(CompoundTag tag, String key, String value, String fallback) {
        if (dense || !value.equals(fallback)) {
            tag.putString(key, value);
        }
    }

    /** While true {@link #save} writes every setting, not only the ones that differ from their default. */
    private boolean dense;

    /**
     * Every setting as a key, defaults included (no history, state or log): what a setup screen compares to find
     * which settings an edit changed, so that it can send only those.
     */
    public CompoundTag denseConfigTag() {
        dense = true;
        try {
            CompoundTag tag = new CompoundTag();
            save(tag);
            for (String key : new ArrayList<>(tag.getAllKeys())) {
                if (!isSettingKey(key)) {
                    tag.remove(key);
                }
            }
            return tag;
        } finally {
            dense = false;
        }
    }

    private static boolean isSettingKey(String key) {
        return switch (key) {
            case "DotSeq", "DotAge", "DotStarted", "AlarmStates", "Log", "LogSeq", "Msgs", "MsgSeq", "Screen", "Group", "Running",
                    "CoverOpen", "Quad" -> false;
            default -> !key.endsWith("Dots");
        };
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString(RecorderStyle.KEY, style.id());
        tag.putInt("Channels", channels);
        putInt(tag, "Interval", interval, DEFAULT_INTERVAL);
        putText(tag, "Layout", layout, LAYOUT_HORIZONTAL);
        putInt(tag, "Grid", grid, 5);
        putInt(tag, "LineWidth", lineWidth, 1);
        putText(tag, "Background", background, BACKGROUND_WHITE);
        putText(tag, "TagMode", tagMode, TAG_TAG);
        putInt(tag, "AutoSwitch", autoSwitch, 0);
        putBool(tag, "RealClock", realClock, false);
        putBool(tag, "Hold", hold, true);
        putInt(tag, "AckGroup", ackGroup, AnnunciatorData.MIN_GROUP);
        for (int i = 0; i < PRESETS; i++) {
            putText(tag, "Msg" + (i + 1), presets[i], defaultPreset(i));
        }
        Channel base = new Channel(0);
        for (int c = 0; c < MAX_CHANNELS; c++) {
            Channel ch = channelCfg[c];
            int defaultColor = PALETTE[c % PALETTE.length];
            putBool(tag, channelKey(c, "On"), ch.on, true);
            putText(tag, channelKey(c, "Tag"), ch.tag, "");
            putText(tag, channelKey(c, "Comment"), ch.comment, "");
            putText(tag, channelKey(c, "Unit"), ch.unit, "");
            putDouble(tag, channelKey(c, "Lo"), ch.lo, base.lo);
            putDouble(tag, channelKey(c, "Hi"), ch.hi, base.hi);
            putInt(tag, channelKey(c, "Dec"), ch.dec, base.dec);
            putInt(tag, channelKey(c, "Color"), ch.color, defaultColor);
            putDouble(tag, channelKey(c, "ZoneLo"), ch.zoneLo, 0.0);
            putDouble(tag, channelKey(c, "ZoneHi"), ch.zoneHi, 100.0);
            for (int a = 0; a < ALARMS; a++) {
                AlarmCfg al = ch.alarms[a];
                putText(tag, alarmKey(c, a, "Type"), al.type, "off");
                putDouble(tag, alarmKey(c, a, "Value"), al.value, 0.0);
                putDouble(tag, alarmKey(c, a, "Hyst"), al.hyst, 0.0);
                putInt(tag, alarmKey(c, a, "Time"), al.time, 0);
                putInt(tag, alarmKey(c, a, "Level"), al.level, 1);
                putInt(tag, alarmKey(c, a, "Color"), al.color, DEFAULT_ALARM_COLOR);
                putBool(tag, alarmKey(c, a, "Hidden"), al.hidden, false);
            }
        }
        for (int g = 0; g < MAX_GROUPS; g++) {
            Group group = groupCfg[g];
            putText(tag, groupKey(g, "Name"), group.name, "");
            for (int s = 0; s < MAX_PER_GROUP; s++) {
                putInt(tag, groupKey(g, "C" + (s + 1)), group.slots[s], defaultSlot(style, g, s));
            }
            for (int t = 0; t < TRIP_LINES; t++) {
                putBool(tag, tripKey(g, t, "On"), group.tripOn[t], false);
                putDouble(tag, tripKey(g, t, "Pos"), group.tripPos[t], 50.0);
                putInt(tag, tripKey(g, t, "Color"), group.tripColor[t], DEFAULT_TRIP_COLOR);
                putInt(tag, tripKey(g, t, "Width"), group.tripWidth[t], 1);
            }
        }
        // operating state
        putText(tag, "Screen", screen.id(), RecorderScreen.TREND.id());
        putInt(tag, "Group", currentGroup, 0);
        putBool(tag, "Running", running, true);
        putBool(tag, "CoverOpen", coverOpen, false);
        putInt(tag, "Quad", quad, 0);
        // history
        tag.putLong("DotSeq", dotSeq);
        long now = host() == null ? lastNow : host().gameTime();
        double age = rebasePending ? dotStart : started ? now - dotStart : 0;
        tag.putInt("DotAge", (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(age))));
        tag.putBoolean("DotStarted", started);
        int stored = storedDots();
        for (int c = 0; c < channels; c++) {
            if (dotMin[c] == null || stored == 0) {
                continue;
            }
            int[] bits = new int[stored * 2];
            boolean any = false;
            for (int k = 0; k < stored; k++) {
                long seq = dotSeq - stored + k;
                float min = dotMin(c, seq);
                float max = dotMax(c, seq);
                any |= !Float.isNaN(min) || !Float.isNaN(max);
                bits[k * 2] = Float.floatToRawIntBits(min);
                bits[k * 2 + 1] = Float.floatToRawIntBits(max);
            }
            if (any) {
                tag.put(channelKey(c, "Dots"), new IntArrayTag(bits));
            }
        }
        byte[] states = new byte[MAX_CHANNELS * ALARMS];
        boolean anyState = false;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            for (int a = 0; a < ALARMS; a++) {
                states[c * ALARMS + a] = (byte) alarmStateOf(c, a).ordinal();
                anyState |= states[c * ALARMS + a] != 0;
            }
        }
        if (anyState) {
            tag.putByteArray("AlarmStates", states);
        }
        ListTag logList = new ListTag();
        for (LogEntry entry : log) {
            CompoundTag e = new CompoundTag();
            e.putLong("S", entry.seq());
            e.putLong("T", entry.tick());
            e.putLong("E", entry.epochSeconds());
            e.putShort("C", (short) entry.channel());
            e.putByte("A", (byte) entry.alarm());
            e.putByte("K", (byte) entry.kind());
            logList.add(e);
        }
        if (!logList.isEmpty()) {
            tag.put("Log", logList);
        }
        tag.putLong("LogSeq", logSeq);
        ListTag messageList = new ListTag();
        for (Message message : messages) {
            CompoundTag e = new CompoundTag();
            e.putLong("S", message.seq());
            e.putLong("D", message.dot());
            e.putString("X", message.text());
            messageList.add(e);
        }
        if (!messageList.isEmpty()) {
            tag.put("Msgs", messageList);
        }
        tag.putLong("MsgSeq", messageSeq);
    }

    private static int intOf(CompoundTag tag, String key, int fallback, int min, int max) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? Math.max(min, Math.min(max, tag.getInt(key))) : fallback;
    }

    private static boolean boolOf(CompoundTag tag, String key, boolean fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getBoolean(key) : fallback;
    }

    private static double doubleOf(CompoundTag tag, String key, double fallback, double min, double max) {
        return GaugeData.number(tag, key, fallback, min, max);
    }

    private static String textOf(CompoundTag tag, String key, String fallback, int max) {
        return tag.contains(key, Tag.TAG_STRING) ? ConfigSchema.cleanText(tag.getString(key), max) : fallback;
    }

    private static String optionOf(CompoundTag tag, String key, List<String> options, String fallback) {
        String value = tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback;
        return options.contains(value) ? value : fallback;
    }

    @Override
    public void load(CompoundTag tag) {
        style = RecorderStyle.byId(tag.getString(RecorderStyle.KEY));
        channels = style.snapChannels(intOf(tag, "Channels", style.defaultChannels(), 2, MAX_CHANNELS));
        interval = intOf(tag, "Interval", DEFAULT_INTERVAL, 0, INTERVAL_SECONDS.length - 1);
        layout = optionOf(tag, "Layout", LAYOUTS, LAYOUT_HORIZONTAL);
        grid = intOf(tag, "Grid", 5, 4, 12);
        lineWidth = intOf(tag, "LineWidth", 1, 1, 3);
        background = optionOf(tag, "Background", BACKGROUNDS, BACKGROUND_WHITE);
        tagMode = optionOf(tag, "TagMode", TAG_MODES, TAG_TAG);
        autoSwitch = intOf(tag, "AutoSwitch", 0, 0, 60);
        if (autoSwitch > 0 && autoSwitch < 5) {
            autoSwitch = 5;
        }
        realClock = boolOf(tag, "RealClock", false);
        hold = boolOf(tag, "Hold", true);
        ackGroup = intOf(tag, "AckGroup", AnnunciatorData.MIN_GROUP, AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP);
        for (int i = 0; i < PRESETS; i++) {
            presets[i] = textOf(tag, "Msg" + (i + 1), defaultPreset(i), MESSAGE_MAX);
        }
        for (int c = 0; c < MAX_CHANNELS; c++) {
            Channel ch = new Channel(c);
            ch.on = boolOf(tag, channelKey(c, "On"), true);
            ch.tag = textOf(tag, channelKey(c, "Tag"), "", TAG_MAX);
            ch.comment = textOf(tag, channelKey(c, "Comment"), "", COMMENT_MAX);
            ch.unit = textOf(tag, channelKey(c, "Unit"), "", UNIT_MAX);
            ch.lo = doubleOf(tag, channelKey(c, "Lo"), 0.0, -LIMIT, LIMIT);
            ch.hi = doubleOf(tag, channelKey(c, "Hi"), 100.0, -LIMIT, LIMIT);
            ch.dec = intOf(tag, channelKey(c, "Dec"), 1, 0, 4);
            ch.color = intOf(tag, channelKey(c, "Color"), PALETTE[c % PALETTE.length], Integer.MIN_VALUE, Integer.MAX_VALUE) & 0xFFFFFF;
            ch.zoneLo = doubleOf(tag, channelKey(c, "ZoneLo"), 0.0, 0.0, 100.0);
            ch.zoneHi = doubleOf(tag, channelKey(c, "ZoneHi"), 100.0, 0.0, 100.0);
            for (int a = 0; a < ALARMS; a++) {
                AlarmCfg al = ch.alarms[a];
                al.type = optionOf(tag, alarmKey(c, a, "Type"), RecorderAlarmLogic.TYPE_OPTIONS, "off");
                al.value = doubleOf(tag, alarmKey(c, a, "Value"), 0.0, -LIMIT, LIMIT);
                al.hyst = doubleOf(tag, alarmKey(c, a, "Hyst"), 0.0, 0.0, LIMIT);
                al.time = intOf(tag, alarmKey(c, a, "Time"), 0, 0, 3600);
                al.level = intOf(tag, alarmKey(c, a, "Level"), 1, 1, 4);
                al.color = intOf(tag, alarmKey(c, a, "Color"), DEFAULT_ALARM_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE) & 0xFFFFFF;
                al.hidden = boolOf(tag, alarmKey(c, a, "Hidden"), false);
            }
            channelCfg[c] = ch;
        }
        for (int g = 0; g < MAX_GROUPS; g++) {
            Group group = new Group(style, g);
            group.name = textOf(tag, groupKey(g, "Name"), "", GROUP_NAME_MAX);
            for (int s = 0; s < MAX_PER_GROUP; s++) {
                group.slots[s] = intOf(tag, groupKey(g, "C" + (s + 1)), defaultSlot(style, g, s), 0, MAX_CHANNELS);
            }
            for (int t = 0; t < TRIP_LINES; t++) {
                group.tripOn[t] = boolOf(tag, tripKey(g, t, "On"), false);
                group.tripPos[t] = doubleOf(tag, tripKey(g, t, "Pos"), 50.0, 0.0, 100.0);
                group.tripColor[t] = intOf(tag, tripKey(g, t, "Color"), DEFAULT_TRIP_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE) & 0xFFFFFF;
                group.tripWidth[t] = intOf(tag, tripKey(g, t, "Width"), 1, 1, 3);
            }
            groupCfg[g] = group;
        }
        RecorderScreen savedScreen = RecorderScreen.byId(tag.getString("Screen"));
        screen = tag.contains("Screen", Tag.TAG_STRING) && savedScreen.availableOn(style) ? savedScreen : RecorderScreen.TREND;
        currentGroup = intOf(tag, "Group", 0, 0, style.groups() - 1);
        running = boolOf(tag, "Running", true);
        coverOpen = style.hasCover() && boolOf(tag, "CoverOpen", false);
        quad = intOf(tag, "Quad", 0, 0, 3);
        loadHistory(tag);
        pendingAlarms = false;
        pendingOperating = false;
        version++;
    }

    private void loadHistory(CompoundTag tag) {
        Arrays.fill(dotMin, null);
        Arrays.fill(dotMax, null);
        dotSeq = Math.max(0L, tag.getLong("DotSeq"));
        int stored = (int) Math.min(dotSeq, RING);
        for (int c = 0; c < channels; c++) {
            int[] bits = tag.getIntArray(channelKey(c, "Dots"));
            int n = Math.min(stored, bits.length / 2);
            if (n == 0) {
                continue;
            }
            float[] mins = row(dotMin, c);
            float[] maxs = row(dotMax, c);
            for (int k = 0; k < n; k++) {
                // the saved ring holds the newest `stored` dots oldest first; keep the newest n if it is longer
                long seq = dotSeq - n + k;
                int source = (bits.length / 2 - n + k) * 2;
                mins[(int) (seq % RING)] = Float.intBitsToFloat(bits[source]);
                maxs[(int) (seq % RING)] = Float.intBitsToFloat(bits[source + 1]);
            }
        }
        started = tag.getBoolean("DotStarted") && running;
        dotStart = Math.max(0, tag.getInt("DotAge"));
        rebasePending = started;
        Arrays.fill(accMin, Float.NaN);
        Arrays.fill(accMax, Float.NaN);
        Arrays.fill(lastValue, Float.NaN);
        byte[] states = tag.getByteArray("AlarmStates");
        for (int c = 0; c < MAX_CHANNELS; c++) {
            alarmState[c] = null;
            for (int a = 0; a < ALARMS; a++) {
                int index = c * ALARMS + a;
                int state = index < states.length ? states[index] : 0;
                if (state != 0 && c < channels) {
                    alarm(c, a).restore(state);
                }
            }
        }
        log.clear();
        ListTag logList = tag.getList("Log", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, logList.size() - LOG_MAX); i < logList.size(); i++) {
            CompoundTag e = logList.getCompound(i);
            log.addLast(new LogEntry(e.getLong("S"), e.getLong("T"), e.getLong("E"),
                    Math.max(0, Math.min(MAX_CHANNELS - 1, e.getShort("C"))), Math.max(0, Math.min(ALARMS - 1, e.getByte("A"))),
                    Math.max(0, Math.min(2, e.getByte("K")))));
        }
        logSeq = Math.max(tag.getLong("LogSeq"), log.isEmpty() ? 0 : log.peekLast().seq() + 1);
        messages.clear();
        ListTag messageList = tag.getList("Msgs", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, messageList.size() - MESSAGES_MAX); i < messageList.size(); i++) {
            CompoundTag e = messageList.getCompound(i);
            messages.addLast(new Message(e.getLong("S"), e.getLong("D"), ConfigSchema.cleanText(e.getString("X"), MESSAGE_MAX)));
        }
        messageSeq = Math.max(tag.getLong("MsgSeq"), messages.isEmpty() ? 0 : messages.peekLast().seq() + 1);
        sentDotSeq = dotSeq;
        sentLogSeq = logSeq;
        sentMessageSeq = messageSeq;
    }

    // ---- config edits

    /**
     * The edit the type is about to apply ({@code proposed}, the edit merged into the current save): keeps it inside
     * the make's limits, and drops what an edit makes meaningless: the history when the trend interval changed, and
     * the state of every alarm whose definition changed.
     */
    public void adjust(CompoundTag proposed) {
        RecorderStyle next = RecorderStyle.byId(proposed.getString(RecorderStyle.KEY));
        int snapped = next.snapChannels(intOf(proposed, "Channels", next.defaultChannels(), 2, MAX_CHANNELS));
        proposed.putInt("Channels", snapped);
        int auto = intOf(proposed, "AutoSwitch", 0, 0, 60);
        proposed.putInt("AutoSwitch", auto > 0 && auto < 5 ? 5 : auto);
        if (intOf(proposed, "Interval", DEFAULT_INTERVAL, 0, INTERVAL_SECONDS.length - 1) != interval) {
            proposed.putLong("DotSeq", 0L);
            proposed.putBoolean("DotStarted", false);
            for (int c = 0; c < MAX_CHANNELS; c++) {
                proposed.remove(channelKey(c, "Dots"));
            }
            proposed.remove("Msgs");
        }
        PaperlessRecorderData probe = new PaperlessRecorderData();
        probe.load(proposed);
        byte[] states = proposed.contains("AlarmStates") ? proposed.getByteArray("AlarmStates") : new byte[MAX_CHANNELS * ALARMS];
        if (states.length < MAX_CHANNELS * ALARMS) {
            states = Arrays.copyOf(states, MAX_CHANNELS * ALARMS);
        }
        boolean touched = false;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            boolean gone = c >= snapped || !probe.channelCfg[c].on || next != style && c >= next.maxChannels();
            for (int a = 0; a < ALARMS; a++) {
                if (states[c * ALARMS + a] != 0 && (gone || !probe.channelCfg[c].alarms[a].sameAs(channelCfg[c].alarms[a])
                        || probe.hold != hold)) {
                    states[c * ALARMS + a] = 0;
                    touched = true;
                }
            }
        }
        if (touched) {
            proposed.putByteArray("AlarmStates", states);
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PaperlessRecorderData[%s %d ch, dots %d, log %d]", style.id(), channels, dotSeq, log.size());
    }
}
