package dev.bwr.mod.panel.display;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.PaperlessRecorderData.Channel;
import dev.bwr.mod.panel.component.RecorderAlarmLogic;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.State;
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.component.RecorderUi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Paints the screen of a paperless recorder (DX1000 320 x 240, DX2000 640 x 480) into a {@link RecorderCanvas}: the
 * status bar, the trend (horizontal, vertical, wide), digital, bar, overview and alarm summary displays, the DX2000's
 * four-panel and circular displays, and the FUNC soft-key strip and display selection menu. No Minecraft types: the
 * client paints a texture from it only when {@link #stamp} changes (a new dot, a one second value refresh, an alarm
 * blink, a key), and the tests render it to image files.
 *
 * <p>Everything is laid out in logical 320 x 240 units; the DX2000 is the same screen at scale 2, and each quadrant
 * of its four-panel display is a 320 x 220 screen at scale 1.
 */
public final class RecorderPainter {
    public static final int W = 320;
    public static final int H = 240;
    public static final int STATUS_H = 20;
    private static final int BLUE = 0x000090;
    private static final int TEAL = 0x00A890;
    private static final int GREEN = 0x00A000;
    private static final int RED = 0xE00000;
    private static final int NUM_ROW_H = 26;
    private static final int SCALE_H = 20;
    private static final DateTimeFormatter REAL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter REAL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final RecorderCanvas c;
    private final PaperlessRecorderData d;
    private final PanelComponent comp;
    private final RecorderUi ui;
    private final long now;
    private final long wall;
    private final boolean black;
    private final boolean blink;
    private final int bg;
    private final int fg;
    private final int grid;
    private final int stripBg;
    private final int numBg;
    private final int normalValue;
    private final int alarmValue;

    private RecorderPainter(RecorderCanvas canvas, PaperlessRecorderData data, PanelComponent component, RecorderUi ui,
                            long gameTime, long wallSeconds) {
        this.c = canvas;
        this.d = data;
        this.comp = component;
        this.ui = ui;
        this.now = gameTime;
        this.wall = wallSeconds;
        this.black = data.background.equals(PaperlessRecorderData.BACKGROUND_BLACK);
        this.blink = blinkOn(gameTime);
        this.bg = black ? 0x000000 : 0xFFFFFF;
        this.fg = black ? 0xFFFFFF : 0x000000;
        this.grid = black ? 0x484848 : 0xC8C8C8;
        this.stripBg = black ? 0x303030 : 0xFFFFC8;
        this.numBg = black ? 0x202020 : 0xE0E0E0;
        this.normalValue = black ? 0x50B0FF : 0x0000E0;
        this.alarmValue = black ? 0xFF4040 : 0xFF0000;
    }

    /** Whether a blinking mark is lit: on for half a second, off for half. */
    public static boolean blinkOn(long gameTime) {
        return (gameTime / 10 & 1) == 0;
    }

    /** The group the group screens show: the chosen one, or the one the automatic switching has reached. */
    public static int effectiveGroup(PaperlessRecorderData data, long gameTime) {
        if (data.autoSwitch <= 0) {
            return data.currentGroup;
        }
        int[] groups = data.activeGroups();
        if (groups.length < 2) {
            return data.currentGroup;
        }
        int at = 0;
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == data.currentGroup) {
                at = i;
            }
        }
        return groups[(int) ((at + gameTime / (data.autoSwitch * 20L)) % groups.length)];
    }

    /**
     * A number that changes exactly when the screen must be painted again: the data's version (patches, loads,
     * acknowledgements, keys), the menu's, the clock's second, the blink phase while anything blinks, the group the
     * automatic switching shows and the component's revision.
     */
    public static int stamp(PaperlessRecorderData data, PanelComponent component, RecorderUi ui, long gameTime) {
        int h = data.version();
        h = h * 31 + ui.version();
        h = h * 31 + component.revision();
        h = h * 31 + (int) (gameTime / 20);
        h = h * 31 + effectiveGroup(data, gameTime);
        boolean blinks = data.unacknowledgedCount() + data.clearedUnacknowledgedCount() > 0;
        h = h * 31 + (blinks && blinkOn(gameTime) ? 1 : 0);
        return h;
    }

    /** Paints the whole screen of {@code data}. {@code wallSeconds} is the real clock, shown when the recorder is set to it. */
    public static void paint(RecorderCanvas canvas, PaperlessRecorderData data, PanelComponent component, RecorderUi ui,
                             long gameTime, long wallSeconds) {
        new RecorderPainter(canvas, data, component, ui, gameTime, wallSeconds).paintAll();
    }

    // ---- structure

    private void paintAll() {
        int k = d.style.scale();
        c.origin(0, 0, k);
        c.clear(bg);
        RecorderScreen screen = d.screen;
        int group = effectiveGroup(d, now);
        statusBar(titleOf(screen, group));
        if (screen == RecorderScreen.FOUR_PANEL && d.style == RecorderStyle.DX2000) {
            fourPanel();
        } else {
            c.origin(0, 0, k);
            content(screen, group, 0, STATUS_H, W, H - STATUS_H, false);
        }
        c.origin(0, 0, k);
        c.noClip();
        overlays();
    }

    private String titleOf(RecorderScreen screen, int group) {
        return switch (screen) {
            case OVERVIEW -> "OVERVIEW";
            case ALARM_SUMMARY -> "ALARM SUMMARY";
            case FOUR_PANEL -> switch (d.quad) {
                case 0 -> "MIX";
                case 1 -> "ALL TREND";
                case 2 -> "ALL DIGITAL";
                default -> "ALL BAR";
            };
            default -> groupTitle(group);
        };
    }

    private String groupTitle(int group) {
        String name = d.group(group).name;
        return "GROUP " + (group + 1) + (name.isEmpty() ? "" : " " + name);
    }

    private void content(RecorderScreen screen, int group, int x, int y, int w, int h, boolean compact) {
        c.clip(x, y, w, h);
        switch (screen) {
            case TREND -> trend(group, x, y, w, h, compact);
            case DIGITAL -> digital(group, x, y, w, h, compact);
            case BAR -> bar(group, x, y, w, h, compact);
            case OVERVIEW -> overview(x, y, w, h);
            case ALARM_SUMMARY -> alarmSummary(x, y, w, h);
            case CIRCULAR -> circular(group, x, y, w, h);
            default -> trend(group, x, y, w, h, compact);
        }
        c.noClip();
    }

    // ---- status bar

    private String clock(long tick) {
        if (d.realClock) {
            long seconds = wall - (now - tick) / 20;
            return REAL_DATE.format(Instant.ofEpochSecond(seconds));
        }
        long day = tick / 24000 + 1;
        long secOfDay = ((tick % 24000) * 86400 / 24000 + 6 * 3600) % 86400;
        return String.format(Locale.ROOT, "Day %d %02d:%02d:%02d", day, secOfDay / 3600, secOfDay / 60 % 60, secOfDay % 60);
    }

    private String shortClock(long tick) {
        if (d.realClock) {
            return REAL_TIME.format(Instant.ofEpochSecond(wall - (now - tick) / 20));
        }
        long secOfDay = ((tick % 24000) * 86400 / 24000 + 6 * 3600) % 86400;
        return String.format(Locale.ROOT, "%02d:%02d", secOfDay / 3600, secOfDay / 60 % 60);
    }

    private static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "min";
        }
        if (seconds < 86400) {
            return String.format(Locale.ROOT, "%.1fh", seconds / 3600.0);
        }
        return String.format(Locale.ROOT, "%.1fd", seconds / 86400.0);
    }

    private void statusBar(String title) {
        c.fill(0, 0, W, STATUS_H, BLUE);
        c.text(4, 2, RecorderCanvas.fit(title, 118, 1), 0xFFFFFF, 1);
        c.text(4, 11, RecorderCanvas.fit(clock(now), 118, 1), 0xFFFFFF, 1);
        // memory sampling: the icon, DISP and the span of history held
        c.fill(126, 3, 12, 13, d.running ? TEAL : RED);
        c.text(141, 2, "DISP", 0xFFFFFF, 1);
        c.fill(166, 2, 92, 7, 0xD0D0D0);
        double filled = d.storedDots() / (double) PaperlessRecorderData.RING;
        c.fill(166, 2, (int) Math.round(92 * filled), 7, 0x30D030);
        long covered = (long) (d.storedDots() * d.dotTicks() / 20.0);
        String label = d.running ? duration(covered) : "STOP";
        c.textRight(256, 2, label, 0x000000, 1);
        c.fill(166, 11, 92, 6, 0xB0B0B0);
        // the alarm icon: flashing red while any alarm waits to be acknowledged, green when they have all cleared
        int unack = d.unacknowledgedCount();
        int cleared = d.clearedUnacknowledgedCount();
        int active = d.activeCount();
        if (unack > 0 && blink || unack == 0 && active > 0) {
            c.disc(306, 10, 8, RED);
            c.textCentered(306, 6, "!", 0xFFFFFF, 1);
        } else if (unack == 0 && cleared > 0 && blink) {
            c.disc(306, 10, 8, GREEN);
            c.textCentered(306, 6, "!", 0xFFFFFF, 1);
        }
    }

    // ---- value helpers

    private static int contrast(int rgb) {
        double l = (0.299 * (rgb >> 16 & 0xFF) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF)) / 255.0;
        return l > 0.6 ? 0x000000 : 0xFFFFFF;
    }

    private String valueText(int ch) {
        double v = comp.input(ch);
        if (Double.isNaN(v)) {
            return "-----";
        }
        Channel channel = d.channel(ch);
        double margin = (channel.hi - channel.lo) * (PaperlessRecorderData.OVER_RANGE - 1.0);
        if (v > channel.hi + margin) {
            return "+Over";
        }
        if (v < channel.lo - margin) {
            return "-Over";
        }
        return format(v, channel.dec);
    }

    private static String format(double v, int dec) {
        return String.format(Locale.ROOT, "%." + dec + "f", v);
    }

    private String tagOf(int ch) {
        return String.format(Locale.ROOT, "%02d", ch + 1) + (d.channel(ch).tag.isEmpty() || d.tagMode.equals(PaperlessRecorderData.TAG_NUMBER)
                ? "" : " " + d.channel(ch).tag);
    }

    private int valueColor(int ch) {
        for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
            if (d.alarmStateOf(ch, a).condition()) {
                return alarmValue;
            }
        }
        return normalValue;
    }

    /** One 8 x 8 alarm mark: red with the letter while on (flashing while unacknowledged), green when normal or cleared. */
    private void alarmMark(int x, int y, int ch, int a) {
        PaperlessRecorderData.AlarmCfg cfg = d.channel(ch).alarms[a];
        RecorderAlarmLogic.Type type = cfg.kind();
        if (type == null || cfg.hidden) {
            return;
        }
        State state = d.alarmStateOf(ch, a);
        int color = GREEN;
        boolean letter = false;
        boolean lit = true;
        switch (state) {
            case ACTIVE, ACTIVE_ACK -> {
                color = cfg.color;
                letter = true;
            }
            case ACTIVE_UNACK -> {
                color = cfg.color;
                letter = true;
                lit = blink;
            }
            case CLEARED_UNACK -> {
                letter = true;
                lit = blink;
            }
            default -> {
            }
        }
        if (lit) {
            c.fill(x, y, 8, 8, color);
            if (letter) {
                c.text(x + 1, y + 1, type.mark(), 0xFFFFFF, 1);
            }
        } else {
            c.fill(x, y, 8, 8, numBg);
        }
    }

    private void alarmMarks(int rightX, int y, int ch) {
        int x = rightX - 9;
        for (int a = PaperlessRecorderData.ALARMS - 1; a >= 0; a--) {
            PaperlessRecorderData.AlarmCfg cfg = d.channel(ch).alarms[a];
            if (cfg.kind() != null && !cfg.hidden) {
                alarmMark(x, y, ch, a);
                x -= 9;
            }
        }
    }

    // ---- numeric cells (under the trend, beside the circle)

    private void numericCell(int x, int y, int w, int h, int ch) {
        Channel channel = d.channel(ch);
        c.fill(x, y, w, h, numBg);
        c.fill(x, y, w, 9, channel.color);
        int ink = contrast(channel.color);
        c.text(x + 2, y + 1, RecorderCanvas.fit(tagOf(ch), w - 4 - alarmWidth(ch), 1), ink, 1);
        alarmMarks(x + w - 1, y + 1, ch);
        String value = valueText(ch);
        int size = 2;
        while (size > 1 && RecorderCanvas.textWidth(value, size) > w - 6 || size > 1 && RecorderCanvas.textHeight(size) > h - 11) {
            size--;
        }
        c.text(x + 3, y + 10, value, valueColor(ch), size);
        String unit = channel.unit;
        if (!unit.isEmpty() && w - 6 - RecorderCanvas.textWidth(value, size) > RecorderCanvas.textWidth(unit, 1)) {
            c.textRight(x + w - 2, y + h - 8, unit, fg, 1);
        }
        c.outline(x, y, w, h, 0x909090);
    }

    private int alarmWidth(int ch) {
        int n = 0;
        for (PaperlessRecorderData.AlarmCfg cfg : d.channel(ch).alarms) {
            if (cfg.kind() != null && !cfg.hidden) {
                n++;
            }
        }
        return n * 9;
    }

    // ---- trend

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Position of a value on its channel's scale, 0 to 1 (zone applied), or NaN for no value. */
    private static double fraction(Channel ch, double value) {
        if (Double.isNaN(value)) {
            return Double.NaN;
        }
        double f = Math.max(-0.02, Math.min(1.02, (value - ch.lo) / ch.span()));
        double zoneLo = Math.min(ch.zoneLo, ch.zoneHi);
        double zoneHi = Math.max(ch.zoneLo, ch.zoneHi);
        if (zoneHi - zoneLo < 0.5) {
            zoneLo = 0;
            zoneHi = 100;
        }
        return (zoneLo + f * (zoneHi - zoneLo)) / 100.0;
    }

    private void trend(int group, int x, int y, int w, int h, boolean compact) {
        int[] channels = d.groupChannels(group);
        int n = channels.length;
        boolean vertical = d.layout.equals(PaperlessRecorderData.LAYOUT_VERTICAL) && !compact;
        boolean wide = d.layout.equals(PaperlessRecorderData.LAYOUT_WIDE);
        int cols = n <= 3 ? Math.max(1, n) : n <= 6 ? 3 : 5;
        if (wide) {
            cols = Math.max(1, Math.min(n, n > 6 ? 5 : 6));
        }
        int rows = (n + cols - 1) / cols;
        int numH = compact || n == 0 ? 0 : rows * NUM_ROW_H;
        int plotX = x;
        int plotW = w;
        int plotTop = y + SCALE_H + 2;
        int plotH = h - SCALE_H - 2 - (vertical ? 0 : numH);
        if (vertical) {
            plotW = w - 104;
        }
        // scale strip
        c.fill(x, y, plotW, SCALE_H, stripBg);
        c.fill(x, y + SCALE_H, plotW, 2, GREEN);
        if (n > 0) {
            scaleStrip(x, y, plotW, channels);
        }
        // grid
        int nv = vertical ? plotH : plotW;
        for (int i = 0; i <= d.grid; i++) {
            if (vertical) {
                c.vline(plotX + Math.min(plotW - 1, i * (plotW - 1) / d.grid), plotTop, plotTop + plotH - 1, grid);
            } else {
                c.hline(plotX, plotX + plotW - 1, plotTop + Math.min(plotH - 1, i * (plotH - 1) / d.grid), grid);
            }
        }
        long total = d.dotCount();
        for (int age = 0; age < nv; age++) {
            long seq = total - age;
            if (seq >= 0 && seq % PaperlessRecorderData.DOTS_PER_DIVISION == 0) {
                int p = vertical ? plotTop + age : plotX + nv - 1 - age;
                if (vertical) {
                    c.hline(plotX, plotX + plotW - 1, p, grid);
                } else {
                    c.vline(p, plotTop, plotTop + plotH - 1, grid);
                }
                if (seq % (2L * PaperlessRecorderData.DOTS_PER_DIVISION) == 0 && !compact) {
                    String label = shortClock(now - (long) (age * d.dotTicks()));
                    if (vertical) {
                        c.text(plotX + 2, p + 1, label, black ? 0x909090 : 0x808080, 1);
                    } else if (p > plotX + 32) {
                        c.text(p - 15, plotTop + plotH - 9, label, black ? 0x909090 : 0x808080, 1);
                    }
                }
            }
        }
        // trip lines
        PaperlessRecorderData.Group g = d.group(group);
        for (int t = 0; t < PaperlessRecorderData.TRIP_LINES; t++) {
            if (!g.tripOn[t]) {
                continue;
            }
            double f = g.tripPos[t] / 100.0;
            if (vertical) {
                c.dashedV(plotX + (int) Math.round(f * (plotW - 1)), plotTop, plotTop + plotH - 1, g.tripWidth[t], 4, 3, g.tripColor[t]);
            } else {
                c.dashedH(plotX, plotX + plotW - 1, plotTop + (int) Math.round((1 - f) * (plotH - 1)), g.tripWidth[t], 4, 3, g.tripColor[t]);
            }
        }
        c.clip(plotX, plotTop, plotW, plotH);
        // messages
        for (PaperlessRecorderData.Message m : d.messageEntries()) {
            long age = total - m.dot();
            if (age < 0 || age >= nv) {
                continue;
            }
            int p = vertical ? plotTop + (int) age : plotX + nv - 1 - (int) age;
            int ink = black ? 0x6080FF : 0x0000F0;
            if (vertical) {
                c.hline(plotX, plotX + plotW - 1, p, ink);
                c.text(plotX + 4, p + 2, RecorderCanvas.fit(m.text(), plotW - 8, 1), ink, 1);
            } else {
                c.vline(p, plotTop, plotTop + plotH - 1, ink);
                c.text(Math.min(p + 3, plotX + plotW - RecorderCanvas.textWidth(m.text(), 1) - 2), plotTop + 18, m.text(), ink, 1);
            }
        }
        for (int i = 0; i < n; i++) {
            trace(channels[i], plotX, plotTop, plotW, plotH, vertical, nv);
        }
        c.clip(x, y, w, h);
        // numeric section
        if (!compact && n > 0) {
            if (vertical) {
                int cellH = Math.min(40, (h) / n);
                for (int i = 0; i < n; i++) {
                    numericCell(x + w - 104, y + i * cellH, 104, cellH, channels[i]);
                }
            } else {
                int cellW = w / cols;
                for (int i = 0; i < n; i++) {
                    numericCell(x + (i % cols) * cellW, y + h - numH + (i / cols) * NUM_ROW_H, cellW, NUM_ROW_H, channels[i]);
                }
            }
        }
        // interval label
        String span = intervalLabel();
        if (vertical) {
            c.textRight(plotX + plotW - 3, plotTop + 2, span, black ? 0x909090 : 0x808080, 1);
        } else {
            c.textRight(plotX + plotW - 3, plotTop + 2, span, black ? 0x909090 : 0x808080, 1);
        }
    }

    private String intervalLabel() {
        int s = d.intervalSeconds();
        if (s < 60) {
            return s + "s/div";
        }
        if (s < 3600) {
            return s / 60 + "min/div";
        }
        return s / 3600 + "h/div";
    }

    private void scaleStrip(int x, int y, int w, int[] channels) {
        Channel first = d.channel(channels[0]);
        for (int i = 0; i <= 10; i++) {
            int tx = x + Math.min(w - 1, i * (w - 1) / 10);
            c.vline(tx, y + SCALE_H - (i % 5 == 0 ? 6 : 3), y + SCALE_H - 1, fg);
        }
        String lo = format(first.lo, first.dec);
        String mid = format((first.lo + first.hi) / 2, first.dec);
        String hi = format(first.hi, first.dec);
        c.text(x + 2, y + 1, lo, fg, 1);
        c.textCentered(x + w / 2, y + 1, mid, fg, 1);
        c.textRight(x + w - 2, y + 1, hi, fg, 1);
        for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
            PaperlessRecorderData.AlarmCfg cfg = first.alarms[a];
            RecorderAlarmLogic.Type type = cfg.kind();
            if (type != null && !cfg.hidden && !type.rate()) {
                double f = (cfg.value - first.lo) / first.span();
                if (f >= 0 && f <= 1) {
                    c.triangleUp(x + (int) Math.round(f * (w - 1)) - 2, y + SCALE_H - 5, 5, 4, cfg.color);
                }
            }
        }
        for (int channel : channels) {
            double f = fraction(d.channel(channel), comp.input(channel));
            if (Double.isNaN(f)) {
                continue;
            }
            int tx = x + clampInt((int) Math.round(f * (w - 1)), 5, w - 6);
            Channel ch = d.channel(channel);
            c.fill(tx - 5, y + 9, 11, 9, ch.color);
            c.text(tx - 5 + (channel + 1 < 10 ? 3 : 0), y + 10, Integer.toString(channel + 1), contrast(ch.color), 1);
            c.vline(tx, y + 18, y + 19, ch.color);
        }
    }

    /** One channel's trace: per dot the span between its minimum and maximum, joined to the dot before it. */
    private void trace(int ch, int px, int py, int pw, int ph, boolean vertical, int nv) {
        Channel channel = d.channel(ch);
        if (!channel.on) {
            return;
        }
        long total = d.dotCount();
        int lw = d.lineWidth;
        int valueExtent = vertical ? pw : ph;
        double prevMid = Double.NaN;
        for (int age = nv - 1; age >= 0; age--) {
            double fMin;
            double fMax;
            if (age == 0) {
                double v = comp.input(ch);
                fMin = fraction(channel, v);
                fMax = fMin;
            } else {
                long seq = total - age;
                if (seq < 0) {
                    continue;
                }
                fMin = fraction(channel, d.dotMin(ch, seq));
                fMax = fraction(channel, d.dotMax(ch, seq));
            }
            if (Double.isNaN(fMin) || Double.isNaN(fMax)) {
                prevMid = Double.NaN;
                continue;
            }
            double lowPos = fMin * (valueExtent - 1);
            double highPos = fMax * (valueExtent - 1);
            double mid = (lowPos + highPos) / 2.0;
            if (!Double.isNaN(prevMid)) {
                lowPos = Math.min(lowPos, prevMid);
                highPos = Math.max(highPos, prevMid);
            }
            int a = (int) Math.round(lowPos);
            int b = (int) Math.round(highPos);
            if (vertical) {
                int tx = px + a;
                c.fill(tx, py + age, b - a + lw, lw, channel.color);
            } else {
                int ty = py + ph - 1 - b;
                c.fill(px + nv - 1 - age, ty, lw, b - a + lw, channel.color);
            }
            prevMid = mid;
        }
    }

    // ---- digital

    private static int[] gridFor(int n, boolean compact) {
        if (compact) {
            int cols = n <= 1 ? 1 : 2;
            return new int[] {cols, Math.max(1, (n + cols - 1) / cols)};
        }
        int cols = n <= 1 ? 1 : n <= 2 ? 2 : n <= 4 ? 2 : n <= 6 ? 3 : n <= 8 ? 4 : 5;
        return new int[] {cols, Math.max(1, (n + cols - 1) / cols)};
    }

    private void digital(int group, int x, int y, int w, int h, boolean compact) {
        int[] channels = d.groupChannels(group);
        int n = channels.length;
        if (n == 0) {
            c.text(x + 8, y + 8, "NO CHANNELS", fg, 1);
            return;
        }
        int[] g = gridFor(n, compact);
        int cellW = w / g[0];
        int cellH = h / g[1];
        for (int i = 0; i < n; i++) {
            int ch = channels[i];
            int cx = x + (i % g[0]) * cellW;
            int cy = y + (i / g[0]) * cellH;
            c.outline(cx, cy, cellW, cellH, fg);
            c.text(cx + 4, cy + 3, RecorderCanvas.fit(tagOf(ch), cellW - 8, 1), fg, 1);
            String value = valueText(ch);
            int size = 4;
            while (size > 1 && (RecorderCanvas.textWidth(value, size) > cellW - 12 || RecorderCanvas.textHeight(size) > cellH - 26)) {
                size--;
            }
            int vy = cy + Math.max(12, (cellH - RecorderCanvas.textHeight(size)) / 2 - 2);
            c.textCentered(cx + cellW / 2, vy, value, valueColor(ch), size);
            String unit = d.channel(ch).unit;
            if (!unit.isEmpty()) {
                c.textRight(cx + cellW - 4, cy + cellH - 10, unit, fg, 1);
            }
            int mx = cx + 4;
            for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
                PaperlessRecorderData.AlarmCfg cfg = d.channel(ch).alarms[a];
                if (cfg.kind() != null && !cfg.hidden) {
                    alarmMark(mx, cy + cellH - 11, ch, a);
                    mx += 9;
                }
            }
        }
    }

    // ---- bar

    private void bar(int group, int x, int y, int w, int h, boolean compact) {
        int[] channels = d.groupChannels(group);
        int n = channels.length;
        if (n == 0) {
            c.text(x + 8, y + 8, "NO CHANNELS", fg, 1);
            return;
        }
        int slot = w / n;
        int barW = Math.min(24, slot / 2);
        int top = y + 14;
        int bottom = y + h - (compact ? 14 : 34);
        for (int i = 0; i < n; i++) {
            int ch = channels[i];
            Channel channel = d.channel(ch);
            int bx = x + i * slot + (slot - barW) / 2 - 3;
            c.textCentered(bx + barW / 2, y + 3, Integer.toString(ch + 1), fg, 1);
            c.fill(bx, top, barW, bottom - top, bg);
            double f = fraction(channel, comp.input(ch));
            if (!Double.isNaN(f)) {
                int fill = (int) Math.round(Math.max(0, Math.min(1, f)) * (bottom - top - 2));
                c.fill(bx + 1, bottom - 1 - fill, barW - 2, fill, channel.color);
            }
            c.outline(bx, top, barW, bottom - top, 0x808080);
            for (int t = 0; t <= 10; t++) {
                int ty = bottom - 1 - t * (bottom - top - 2) / 10;
                c.hline(bx + barW + 1, bx + barW + (t % 5 == 0 ? 6 : 3), ty, fg);
            }
            if (!compact) {
                c.textCentered(bx + barW / 2, bottom + 3, RecorderCanvas.fit(valueText(ch), slot - 2, 1), valueColor(ch), 1);
            }
            int markY = bottom + (compact ? 2 : 14);
            int mx = bx;
            for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
                PaperlessRecorderData.AlarmCfg cfg = channel.alarms[a];
                if (cfg.kind() != null && !cfg.hidden && d.alarmStateOf(ch, a) != State.NORMAL) {
                    alarmMark(mx, markY, ch, a);
                    mx += 9;
                }
            }
        }
    }

    // ---- overview

    private void overview(int x, int y, int w, int h) {
        int n = d.channels;
        int cols = n <= 6 ? 3 : n <= 12 ? 4 : n <= 20 ? 5 : n <= 30 ? 6 : 8;
        int rows = (n + cols - 1) / cols;
        int tileW = w / cols;
        int tileH = h / rows;
        for (int ch = 0; ch < n; ch++) {
            int tx = x + (ch % cols) * tileW;
            int ty = y + (ch / cols) * tileH;
            Channel channel = d.channel(ch);
            int marked = d.markedAlarm(ch);
            int color = GREEN;
            boolean lit = true;
            if (!channel.on) {
                color = 0x808080;
            } else if (marked >= 0) {
                State state = d.alarmStateOf(ch, marked);
                if (state == State.CLEARED_UNACK) {
                    lit = blink;
                } else {
                    color = channel.alarms[marked].color;
                    lit = state != State.ACTIVE_UNACK || blink;
                }
            }
            c.fill(tx, ty, tileW, tileH, bg);
            c.fill(tx + 1, ty + 1, tileW - 2, tileH - 2, lit ? color : (black ? 0x300000 : 0x400000));
            int ink = lit ? contrast(color) : 0xFFFFFF;
            c.text(tx + 3, ty + 3, RecorderCanvas.fit(tagOf(ch), tileW - 6, 1), ink, 1);
            if (tileH >= 30) {
                c.text(tx + 3, ty + 14, RecorderCanvas.fit(valueText(ch), tileW - 6, 1), ink, 1);
            }
            if (marked >= 0 && channel.on) {
                c.textRight(tx + tileW - 4, ty + tileH - 10, channel.alarms[marked].kind() == null ? "" : channel.alarms[marked].kind().mark(), ink, 1);
            }
        }
    }

    // ---- alarm summary

    private void alarmSummary(int x, int y, int w, int h) {
        c.fill(x, y, w, 10, black ? 0x303030 : 0xD0D0D0);
        c.text(x + 2, y + 1, " NO TIME      CH    T    EVENT", fg, 1);
        List<PaperlessRecorderData.LogEntry> log = d.logEntries();
        if (log.isEmpty()) {
            c.text(x + 6, y + 18, "NO ALARM LOG", fg, 1);
            return;
        }
        int rowsFit = (h - 12) / 9;
        for (int i = 0; i < rowsFit && i < log.size(); i++) {
            PaperlessRecorderData.LogEntry e = log.get(log.size() - 1 - i);
            int ry = y + 12 + i * 9;
            String kind = switch (e.kind()) {
                case PaperlessRecorderData.LogEntry.ON -> "ON ";
                case PaperlessRecorderData.LogEntry.OFF -> "OFF";
                default -> "ACK";
            };
            RecorderAlarmLogic.Type type = d.channel(e.channel()).alarms[e.alarm()].kind();
            String time = d.realClock ? REAL_TIME.format(Instant.ofEpochSecond(e.epochSeconds())) : shortClockSeconds(e.tick());
            int ink = e.kind() == PaperlessRecorderData.LogEntry.ON ? alarmValue : e.kind() == PaperlessRecorderData.LogEntry.ACK ? normalValue : fg;
            c.text(x + 2, ry, String.format(Locale.ROOT, "%3d %s  %s  %s    %s", log.size() - i, time,
                    String.format(Locale.ROOT, "CH%02d", e.channel() + 1), type == null ? "-" : type.mark(), kind), ink, 1);
        }
    }

    private String shortClockSeconds(long tick) {
        long secOfDay = ((tick % 24000) * 86400 / 24000 + 6 * 3600) % 86400;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", secOfDay / 3600, secOfDay / 60 % 60, secOfDay % 60);
    }

    // ---- four panel (DX2000)

    private void fourPanel() {
        int[] groups = d.activeGroups();
        int at = 0;
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == d.currentGroup) {
                at = i;
            }
        }
        int selected = d.quad == 0 ? 3 : 0;
        for (int q = 0; q < 4; q++) {
            int qx = (q % 2) * 320;
            int qy = STATUS_H * 2 + (q / 2) * 220;
            RecorderScreen screen;
            int group;
            if (d.quad == 0) {
                screen = new RecorderScreen[] {RecorderScreen.DIGITAL, RecorderScreen.OVERVIEW, RecorderScreen.BAR, RecorderScreen.TREND}[q];
                group = d.currentGroup;
            } else {
                screen = new RecorderScreen[] {RecorderScreen.TREND, RecorderScreen.DIGITAL, RecorderScreen.BAR}[d.quad - 1];
                group = groups.length == 0 ? d.currentGroup : groups[(at + q) % groups.length];
            }
            c.origin(qx, qy, 1);
            c.fill(0, 0, 320, 220, bg);
            boolean isSelected = q == selected;
            c.fill(0, 0, 320, 11, isSelected ? BLUE : 0xC0C0C0);
            String title = screen == RecorderScreen.OVERVIEW ? "OVERVIEW" : groupTitle(group);
            c.text(3, 2, RecorderCanvas.fit(title, 300, 1), isSelected ? 0xFFFFFF : 0x000000, 1);
            content(screen, group, 0, 11, 320, 209, true);
            c.outline(0, 0, 320, 220, 0x808080);
        }
    }

    // ---- circular (DX2000)

    private void circular(int group, int x, int y, int w, int h) {
        int[] channels = d.groupChannels(group);
        int n = channels.length;
        int colW = 105;
        int cx = x + (w - colW) / 2;
        int cy = y + h / 2;
        int radius = Math.min((w - colW) / 2 - 6, h / 2 - 8);
        int inner = 8;
        c.fill(x, y, w - colW, h, black ? 0x101820 : 0xFFFFD8);
        for (int i = 1; i <= d.grid; i++) {
            c.circle(cx, cy, inner + (radius - inner) * i / d.grid, grid);
        }
        for (int a = 0; a < 360; a += 30) {
            double t = Math.toRadians(a);
            c.line(cx + (int) Math.round(Math.sin(t) * inner), cy - (int) Math.round(Math.cos(t) * inner),
                    cx + (int) Math.round(Math.sin(t) * radius), cy - (int) Math.round(Math.cos(t) * radius), 1, grid);
        }
        c.circle(cx, cy, radius, fg);
        long total = d.dotCount();
        int ring = PaperlessRecorderData.RING;
        for (int i = 0; i < n; i++) {
            int ch = channels[i];
            Channel channel = d.channel(ch);
            int lastX = 0;
            int lastY = 0;
            boolean have = false;
            long first = Math.max(0, total - ring + 1);
            for (long seq = first; seq <= total; seq++) {
                double lo;
                double hi;
                if (seq == total) {
                    lo = fraction(channel, comp.input(ch));
                    hi = lo;
                } else {
                    lo = fraction(channel, d.dotMin(ch, seq));
                    hi = fraction(channel, d.dotMax(ch, seq));
                }
                if (Double.isNaN(lo) || Double.isNaN(hi)) {
                    have = false;
                    continue;
                }
                double t = Math.toRadians(seq % ring);
                double mid = (lo + hi) / 2;
                int ox = cx + (int) Math.round(Math.sin(t) * (inner + mid * (radius - inner)));
                int oy = cy - (int) Math.round(Math.cos(t) * (inner + mid * (radius - inner)));
                if (hi - lo > 0.01) {
                    c.line(cx + (int) Math.round(Math.sin(t) * (inner + lo * (radius - inner))),
                            cy - (int) Math.round(Math.cos(t) * (inner + lo * (radius - inner))),
                            cx + (int) Math.round(Math.sin(t) * (inner + hi * (radius - inner))),
                            cy - (int) Math.round(Math.cos(t) * (inner + hi * (radius - inner))), d.lineWidth, channel.color);
                }
                if (have) {
                    c.line(lastX, lastY, ox, oy, d.lineWidth, channel.color);
                }
                lastX = ox;
                lastY = oy;
                have = true;
            }
        }
        double t = Math.toRadians(total % ring);
        c.line(cx, cy, cx + (int) Math.round(Math.sin(t) * radius), cy - (int) Math.round(Math.cos(t) * radius), 1, black ? 0xFFFF60 : 0x606060);
        long revolution = (long) (ring * d.dotTicks() / 20.0);
        c.textRight(x + w - colW - 3, y + h - 9, duration(revolution) + "/rev", black ? 0x909090 : 0x808080, 1);
        if (n > 0) {
            int cellH = Math.min(36, h / n);
            for (int i = 0; i < n; i++) {
                numericCell(x + w - colW, y + i * cellH, colW, cellH, channels[i]);
            }
        }
    }

    // ---- overlays

    private void overlays() {
        RecorderStyle style = d.style;
        if (ui.mode == RecorderUi.Mode.MENU) {
            List<RecorderUi.MenuItem> items = RecorderUi.menu(style);
            int rowH = 10;
            int mw = 122;
            int mh = (items.size() + 1) * rowH + 4;
            c.fill(4, STATUS_H + 4, mw, mh, 0xF0F0F0);
            c.outline(4, STATUS_H + 4, mw, mh, 0x303030);
            c.text(8, STATUS_H + 7, "DISPLAY", 0x303030, 1);
            for (int i = 0; i < items.size(); i++) {
                int ry = STATUS_H + 4 + (i + 1) * rowH + 2;
                boolean cursor = i == ui.cursor;
                if (cursor) {
                    c.fill(6, ry - 1, mw - 4, rowH, BLUE);
                }
                c.text(10, ry, items.get(i).label(), cursor ? 0xFFFFFF : 0x000000, 1);
            }
        }
        if (ui.mode == RecorderUi.Mode.FUNC || ui.mode == RecorderUi.Mode.MESSAGE) {
            String[] labels = ui.softLabels(style);
            int cell = W / labels.length;
            c.fill(0, H - 13, W, 13, 0x404040);
            for (int i = 0; i < labels.length; i++) {
                int lx = i * cell;
                boolean live = !labels[i].isEmpty();
                c.fill(lx + 1, H - 12, cell - 2, 11, live ? 0xE8E8E8 : 0x707070);
                if (live) {
                    c.textCentered(lx + cell / 2, H - 10, RecorderCanvas.fit(labels[i], cell - 4, 1), 0x000000, 1);
                }
            }
        }
    }
}
