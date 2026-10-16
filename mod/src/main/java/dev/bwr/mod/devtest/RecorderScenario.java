package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderStyle;

import java.util.Random;

/**
 * A made up plant for the recorder's pictures: a recirculation pump trip. Channel tags, spans and alarms are set up
 * like a real control room's, and a history of 430 dots is recorded with the pump tripping at dot 230, so a trend
 * has a level, a flat line, a ramp and a new steady state, and two alarms are on and waiting to be acknowledged.
 * Used by the headless screen tests and by the real client check; development only.
 */
final class RecorderScenario {
    private RecorderScenario() {
    }

    static final String[] TAGS = {"RPV LEVEL", "RX PRESS", "FW FLOW", "RRC A SPD", "MS FLOW", "CST LEVEL", "DW TEMP 1", "DW TEMP 2",
            "SP TEMP A", "SP TEMP B", "COND VAC", "SP LEVEL"};
    static final String[] UNITS = {"in", "psig", "Mlb/h", "%", "Mlb/h", "%", "degF", "degF", "degF", "degF", "inHg", "in"};
    static final double[] HI = {60, 1200, 12, 100, 12, 100, 300, 300, 200, 200, 30, 20};

    /** Tags, spans, the alarms and the trip line of the scenario. */
    static void configure(PaperlessRecorderData data, int channels, String background, int interval) {
        data.channels = channels;
        data.interval = interval;
        data.background = background;
        data.group(0).name = data.style == RecorderStyle.DX2000 ? "CONTAINMENT" : "RECIRC";
        for (int c = 0; c < Math.min(TAGS.length, channels); c++) {
            data.channel(c).tag = TAGS[c];
            data.channel(c).unit = UNITS[c];
            data.channel(c).lo = 0;
            data.channel(c).hi = HI[c];
            data.channel(c).dec = c == 1 ? 0 : 1;
        }
        data.channel(0).alarms[0].type = "L";
        data.channel(0).alarms[0].value = 38.0;
        data.channel(0).alarms[1].type = "H";
        data.channel(0).alarms[1].value = 55.0;
        data.channel(3).alarms[0].type = "L";
        data.channel(3).alarms[0].value = 30.0;
        data.channel(1).alarms[0].type = "H";
        data.channel(1).alarms[0].value = 1100.0;
        data.group(0).tripOn[0] = true;
        data.group(0).tripPos[0] = 80.0;
    }

    /** The value of channel {@code c} at dot {@code dot} of the scenario (the pump trips at dot 230). */
    static double value(int c, double dot, Random noise) {
        double trip = Math.max(0.0, Math.min(1.0, (dot - 230) / 25.0));
        double base = switch (c % 6) {
            case 0 -> 40.0 - 8.0 * trip;
            case 1 -> 1015.0 + 5.0 * trip;
            case 2 -> 8.8 - 0.4 * trip;
            case 3 -> 70.0 - 42.0 * trip;
            case 4 -> 8.9;
            default -> 68.0 + 3.0 * trip;
        };
        double scaled = c % 6 == 1 || c < 6 ? base : base * (0.3 + 0.1 * (c % 7));
        double span = HI[c % HI.length];
        return scaled + (noise.nextDouble() - 0.5) * span * 0.006;
    }

    /**
     * Records the 430 dots ending at game tick {@code endTick} and returns the tick after the last sample. The
     * recorder must not have sampled later than the first of them.
     */
    static long fill(PaperlessRecorderData data, int channels, long endTick) {
        // A recorder that already sampled (placing one on a panel does) has its dot clock at "now": start it again.
        data.setRunning(false);
        data.setRunning(true);
        double[] v = new double[PaperlessRecorderData.MAX_CHANNELS];
        Random noise = new Random(11);
        int perDot = (int) Math.max(1, data.dotTicks() / 2) + 1;
        long total = 430L * perDot;
        long tick = endTick - total * 2;
        for (long dot = 0; dot < 430; dot++) {
            for (int step = 0; step < perDot; step++) {
                for (int c = 0; c < channels; c++) {
                    v[c] = value(c, dot, noise);
                }
                data.sample(tick, v);
                tick += 2;
            }
        }
        return tick;
    }
}
