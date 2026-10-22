package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderActions;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.State;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The paperless recorder component (Yokogawa DX1000 / DX2000 styles): the dot history with its per-dot minimum and
 * maximum, the alarms with their log and acknowledgement (from the FUNC soft key and from a bound acknowledge
 * pushbutton), settings and their validation, saves (a ring that survives, old saves, sparse keys), the patches that
 * keep a client's copy equal to the server's, the port counts of each make, and the rule that a recorder changes
 * nothing in the plant.
 *
 * <p>The first group runs on the data class alone, with a synthetic game clock and no world. The rest build a wall
 * at a fixture of their own (origin 88_900 and {@code slot * 64} blocks east, a block of chunks each), force-load
 * it, and release it in every outcome, pass, fail or timeout.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PaperlessRecorderTests {
    private PaperlessRecorderTests() {
    }

    static final int ORIGIN = 88_900;
    private static final Direction FACING = Direction.NORTH;

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static void same(GameTestHelper h, Object actual, Object expected, String what) {
        h.assertTrue(actual.equals(expected), what + ": expected " + expected + " but was " + actual);
    }

    /** Values for {@code channels} channels, NaN everywhere but {@code first...}. */
    private static double[] values(double... first) {
        double[] out = new double[PaperlessRecorderData.MAX_CHANNELS];
        Arrays.fill(out, Double.NaN);
        System.arraycopy(first, 0, out, 0, first.length);
        return out;
    }

    private static PaperlessRecorderData sixtySecondRecorder() {
        PaperlessRecorderData data = new PaperlessRecorderData();
        data.interval = 4; // 60 s per division: 40 ticks a dot
        return data;
    }

    // ---- history

    @GameTest(template = "empty")
    public static void dotsHoldTheMinimumAndMaximumOfTheirTime(GameTestHelper h) {
        PaperlessRecorderData data = sixtySecondRecorder();
        for (long t = 0; t <= 120; t += 2) {
            data.sample(t, values(t, 5.0, Double.NaN));
        }
        same(h, data.dotCount(), 3L, "dots closed at 40, 80 and 120");
        check(h, data.dotMin(0, 0) == 0.0f && data.dotMax(0, 0) == 38.0f, "dot 0 saw 0 to 38: " + data.dotMin(0, 0) + " " + data.dotMax(0, 0));
        check(h, data.dotMin(0, 1) == 40.0f && data.dotMax(0, 1) == 78.0f, "dot 1 saw 40 to 78: " + data.dotMin(0, 1) + " " + data.dotMax(0, 1));
        check(h, data.dotMin(0, 2) == 80.0f && data.dotMax(0, 2) == 118.0f, "dot 2 saw 80 to 118");
        check(h, data.dotMin(1, 1) == 5.0f && data.dotMax(1, 1) == 5.0f, "a constant is its own minimum and maximum");
        check(h, Float.isNaN(data.dotMin(2, 1)) && Float.isNaN(data.dotMax(2, 1)), "no signal is a gap, not a zero");
        check(h, Float.isNaN(data.dotMin(0, 3)) && Float.isNaN(data.dotMin(0, -1)), "dots that do not exist read NaN");
        check(h, data.openMax(0) == 120.0f, "the open dot holds what has been sampled in it so far");

        // A short gap holds the last value; a long one is a break in the trace; more than the ring is simply gone.
        data.sample(205, values(7.0));
        same(h, data.dotCount(), 5L, "two dots closed by one late sample");
        check(h, data.dotMin(0, 3) == 120.0f && data.dotMax(0, 3) == 120.0f, "the dot that was open closes with what it saw");
        check(h, data.dotMin(0, 4) == 120.0f && data.dotMax(0, 4) == 120.0f, "a short gap holds the last value");
        data.sample(605, values(9.0));
        same(h, data.dotCount(), 15L, "ten dots later");
        check(h, data.dotMin(0, 5) == 7.0f, "the dot with the 7");
        for (long dot = 6; dot < 15; dot++) {
            check(h, Float.isNaN(data.dotMin(0, dot)), "a long gap is a break at dot " + dot);
        }
        data.sample(605 + 20_000, values(1.0));
        same(h, data.dotCount(), 515L, "500 dots later");
        check(h, Float.isNaN(data.dotMin(0, 514)) && Float.isNaN(data.dotMin(0, 154)), "inside the ring and a gap");
        check(h, Float.isNaN(data.dotMin(0, 5)), "dots older than the ring are gone");
        check(h, data.storedDots() == PaperlessRecorderData.RING, "the ring holds " + PaperlessRecorderData.RING);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void stoppedMemoryRecordsNothingButAlarmsStillIndicate(GameTestHelper h) {
        PaperlessRecorderData data = sixtySecondRecorder();
        data.channel(0).alarms[0].type = "H";
        data.channel(0).alarms[0].value = 10.0;
        for (long t = 0; t < 100; t += 2) {
            data.sample(t, values(1.0));
        }
        long dots = data.dotCount();
        check(h, dots == 2, "two dots so far: " + dots);
        check(h, data.setRunning(false), "STOP");
        for (long t = 100; t < 300; t += 2) {
            data.sample(t, values(t < 150 ? 1.0 : 50.0));
        }
        same(h, data.dotCount(), dots, "no dot is closed while memory sampling is stopped");
        same(h, data.alarmStateOf(0, 0), State.ACTIVE_UNACK, "the alarm still indicates");
        same(h, data.logSize(), 0, "but nothing is logged");
        check(h, data.setRunning(true), "START");
        same(h, data.dotCount(), dots + 1, "one gap dot marks the restart");
        check(h, Float.isNaN(data.dotMin(0, dots)), "and it is a break in the trace");
        for (long t = 300; t < 400; t += 2) {
            data.sample(t, values(50.0));
        }
        check(h, data.dotCount() > dots + 1, "dots are closed again");
        check(h, !data.setRunning(true), "START while started is no change");
        h.succeed();
    }

    // ---- alarms and the log

    @GameTest(template = "empty")
    public static void alarmsLogOnOffAndAcknowledgeInOrder(GameTestHelper h) {
        PaperlessRecorderData data = new PaperlessRecorderData();
        data.channel(2).alarms[1].type = "H";
        data.channel(2).alarms[1].value = 10.0;
        data.sample(0, values(0, 0, 5.0));
        same(h, data.alarmStateOf(2, 1), State.NORMAL, "below the limit");
        data.sample(2, values(0, 0, 11.0));
        same(h, data.alarmStateOf(2, 1), State.ACTIVE_UNACK, "above the limit, hold indication");
        same(h, data.unacknowledgedCount(), 1, "one alarm waits");
        same(h, data.markedAlarm(2), 1, "the alarm that sets the channel's mark");
        same(h, data.acknowledgeAll(), 1, "ACK");
        same(h, data.alarmStateOf(2, 1), State.ACTIVE_ACK, "steady");
        same(h, data.acknowledgeAll(), 0, "a second ACK finds nothing");
        data.sample(4, values(0, 0, 5.0));
        same(h, data.alarmStateOf(2, 1), State.NORMAL, "acknowledged, then gone");
        data.sample(6, values(0, 0, 12.0));
        data.sample(8, values(0, 0, 5.0));
        same(h, data.alarmStateOf(2, 1), State.CLEARED_UNACK, "cleared before the ACK");
        same(h, data.clearedUnacknowledgedCount(), 1, "counted apart");
        data.acknowledgeAll();
        same(h, data.alarmStateOf(2, 1), State.NORMAL, "acknowledged");
        List<PaperlessRecorderData.LogEntry> log = data.logEntries();
        int[] kinds = {PaperlessRecorderData.LogEntry.ON, PaperlessRecorderData.LogEntry.ACK, PaperlessRecorderData.LogEntry.OFF,
                PaperlessRecorderData.LogEntry.ON, PaperlessRecorderData.LogEntry.OFF, PaperlessRecorderData.LogEntry.ACK};
        same(h, log.size(), kinds.length, "log length");
        for (int i = 0; i < kinds.length; i++) {
            check(h, log.get(i).kind() == kinds[i] && log.get(i).channel() == 2 && log.get(i).alarm() == 1 && log.get(i).seq() == i,
                    "entry " + i + ": " + log.get(i));
        }

        // Hidden and off alarms are neither indicated nor logged.
        data.channel(2).alarms[1].hidden = true;
        data.sample(10, values(0, 0, 50.0));
        same(h, data.alarmStateOf(2, 1), State.NORMAL, "a hidden alarm does not indicate");
        same(h, data.logSize(), kinds.length, "and is not logged");

        // The log keeps the newest 200.
        PaperlessRecorderData busy = new PaperlessRecorderData();
        busy.channel(0).alarms[0].type = "H";
        busy.channel(0).alarms[0].value = 10.0;
        busy.hold = false;
        for (int i = 0; i < 150; i++) {
            busy.sample(i * 4L, values(50.0));
            busy.sample(i * 4L + 2, values(0.0));
        }
        same(h, busy.logSize(), PaperlessRecorderData.LOG_MAX, "bounded log");
        same(h, busy.logSequence(), 300L, "but the sequence counts every entry");
        same(h, busy.logEntries().get(0).seq(), 100L, "the oldest entries went");
        h.succeed();
    }

    // ---- patches

    private static void assertSameRecorder(GameTestHelper h, PaperlessRecorderData server, PaperlessRecorderData client, String when) {
        same(h, client.dotCount(), server.dotCount(), when + ": dot count");
        for (int c = 0; c < server.channels; c++) {
            for (long dot = Math.max(0, server.dotCount() - 40); dot < server.dotCount(); dot++) {
                check(h, Float.floatToIntBits(client.dotMin(c, dot)) == Float.floatToIntBits(server.dotMin(c, dot))
                        && Float.floatToIntBits(client.dotMax(c, dot)) == Float.floatToIntBits(server.dotMax(c, dot)),
                        when + ": dot " + dot + " of channel " + c);
            }
            for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
                same(h, client.alarmStateOf(c, a), server.alarmStateOf(c, a), when + ": alarm " + c + "/" + a);
            }
        }
        same(h, client.logEntries(), server.logEntries(), when + ": log");
        same(h, client.messageEntries(), server.messageEntries(), when + ": messages");
        same(h, client.screen, server.screen, when + ": screen");
        same(h, client.currentGroup, server.currentGroup, when + ": group");
        same(h, client.running, server.running, when + ": running");
    }

    @GameTest(template = "empty")
    public static void patchesKeepAClientCopyEqualToTheServer(GameTestHelper h) {
        PaperlessRecorderData server = sixtySecondRecorder();
        server.channel(0).alarms[0].type = "H";
        server.channel(0).alarms[0].value = 10.0;
        PaperlessRecorderData client = new PaperlessRecorderData();
        client.load(server.toTag());
        check(h, server.drainPatch() == null, "nothing to send yet");
        for (long t = 0; t < 130; t += 2) {
            server.sample(t, values(t < 60 ? 1.0 : 50.0, t));
        }
        server.addPresetMessage(3);
        server.setScreen(RecorderScreen.DIGITAL);
        server.stepGroup(1);
        server.acknowledgeAll();
        byte[] patch = server.drainPatch();
        check(h, patch != null && patch.length <= PaperlessRecorderData.MAX_PATCH, "a bounded patch");
        client.applyPatch(patch);
        assertSameRecorder(h, server, client, "after one patch");
        same(h, server.messageEntries().get(0).text(), "MESSAGE 3", "the preset message");
        check(h, server.drainPatch() == null, "drained");

        // The same patch again changes nothing (a full state and a patch can carry the same entries).
        int logBefore = client.logSize();
        client.applyPatch(patch);
        assertSameRecorder(h, server, client, "after the same patch twice");
        same(h, client.logSize(), logBefore, "no log entry is doubled");

        // A client that missed a patch fills the gap (a break in the trace) and has every dot of the next.
        for (long t = 130; t < 330; t += 2) {
            server.sample(t, values(2.0, t));
        }
        server.drainPatch(); // lost
        for (long t = 330; t < 450; t += 2) {
            server.sample(t, values(3.0, t));
        }
        client.applyPatch(server.drainPatch());
        same(h, client.dotCount(), server.dotCount(), "caught up");
        long newest = server.dotCount() - 1;
        check(h, client.dotMax(1, newest) == server.dotMax(1, newest), "the newest dot arrived");

        // Garbage and truncations are ignored without a throw.
        long dots = client.dotCount();
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) {
            byte[] junk = new byte[random.nextInt(60)];
            random.nextBytes(junk);
            client.applyPatch(junk);
        }
        byte[] good = server.drainPatch();
        server.sample(450, values(4.0));
        server.sample(500, values(4.0));
        byte[] more = server.drainPatch();
        for (int cut = 0; cut < more.length; cut += 3) {
            client.applyPatch(Arrays.copyOf(more, cut));
        }
        check(h, client.dotCount() >= dots, "the dot count never went backwards");

        // Largest case: 48 channels, a full log, a full patch still fits the cap.
        PaperlessRecorderData big = new PaperlessRecorderData(RecorderStyle.DX2000);
        big.channels = 48;
        big.interval = 0;
        for (int c = 0; c < 48; c++) {
            big.channel(c).alarms[0].type = "H";
            big.channel(c).alarms[0].value = 10.0;
        }
        double[] all = new double[48];
        for (long t = 0; t < 2000; t += 2) {
            Arrays.fill(all, t % 8 < 4 ? 50.0 : 0.0);
            big.sample(t, all);
        }
        byte[] huge = big.drainPatch();
        check(h, huge != null && huge.length <= PaperlessRecorderData.MAX_PATCH, "the worst patch: " + (huge == null ? -1 : huge.length));
        h.succeed();
    }

    // ---- saves and settings

    @GameTest(template = "empty")
    public static void settingsRoundTripAndOldSavesLoadUnchanged(GameTestHelper h) {
        PaperlessRecorderData data = sixtySecondRecorder();
        data.channels = 6;
        data.layout = PaperlessRecorderData.LAYOUT_VERTICAL;
        data.background = PaperlessRecorderData.BACKGROUND_BLACK;
        data.grid = 8;
        data.channel(2).tag = "RPV LEVEL";
        data.channel(2).unit = "in";
        data.channel(2).alarms[3].type = "T";
        data.channel(2).alarms[3].value = 42.5;
        data.channel(2).alarms[3].time = 30;
        data.group(0).name = "RECIRC";
        data.group(0).slots[1] = 0;
        data.group(0).tripOn[2] = true;
        data.group(0).tripPos[2] = 77.0;
        data.presets[4] = "PUMP A TRIP";
        for (long t = 0; t < 200; t += 2) {
            data.sample(t, values(t, 7.5));
        }
        data.addMessage("HELLO");
        data.setScreen(RecorderScreen.BAR);
        CompoundTag saved = data.toTag();
        PaperlessRecorderData loaded = new PaperlessRecorderData();
        loaded.load(saved);
        check(h, loaded.toTag().equals(saved), "load(save) is a fixed point");
        same(h, loaded.channel(2).tag, "RPV LEVEL", "tag");
        same(h, loaded.channel(2).alarms[3].type, "T", "alarm type");
        same(h, loaded.group(0).name, "RECIRC", "group name");
        same(h, loaded.dotCount(), data.dotCount(), "the ring is saved");
        check(h, loaded.dotMax(0, 1) == data.dotMax(0, 1) && loaded.dotMin(1, 2) == 7.5f, "with its dots");
        same(h, loaded.screen, RecorderScreen.BAR, "screen");
        same(h, loaded.messageEntries().size(), 1, "messages");
        same(h, loaded.channels, 6, "channels");

        // The open dot is saved as an age: after a load the clock carries on from where it was.
        loaded.sample(200, values(3.0));
        loaded.sample(240, values(3.0));
        check(h, loaded.dotCount() > data.dotCount(), "the loaded recorder keeps closing dots");

        // Only what is not a default is written.
        PaperlessRecorderData plain = new PaperlessRecorderData();
        CompoundTag small = plain.toTag();
        check(h, small.size() <= 8, "a default 12 channel recorder saves a handful of keys: " + small.getAllKeys());
        PaperlessRecorderData fortyEight = new PaperlessRecorderData(RecorderStyle.DX2000);
        fortyEight.channels = 48;
        check(h, fortyEight.toTag().size() <= 8, "and so does a default 48 channel one");
        plain.channel(2).tag = "X";
        CompoundTag one = plain.toTag();
        check(h, one.contains("Ch03Tag") && one.size() == small.size() + 1, "one setting is one key: " + one.getAllKeys());

        // Old saves: an empty compound, one with only the original keys, and junk in the keys.
        PaperlessRecorderData old = new PaperlessRecorderData();
        old.load(new CompoundTag());
        check(h, old.toTag().equals(new PaperlessRecorderData().toTag()), "an empty save is a default recorder");
        CompoundTag minimal = new CompoundTag();
        minimal.putString("RecorderStyle", "yokogawa_dx1000");
        minimal.putInt("Channels", 12);
        PaperlessRecorderData minimalLoaded = new PaperlessRecorderData();
        minimalLoaded.load(minimal);
        check(h, minimalLoaded.toTag().equals(new PaperlessRecorderData().toTag()), "a save from before any other key existed");
        CompoundTag junk = new CompoundTag();
        junk.putString("RecorderStyle", "../../etc");
        junk.putString("Interval", "fast");
        junk.putInt("Channels", 7);
        junk.putString("Layout", "zigzag");
        junk.putDouble("Ch01Lo", Double.NaN);
        junk.putInt("Ch01Dec", 99);
        junk.putString("Ch01Al1Type", "Z");
        junk.putInt("Group", 500);
        junk.putString("Screen", "circular");
        PaperlessRecorderData cleaned = new PaperlessRecorderData();
        cleaned.load(junk);
        same(h, cleaned.style, RecorderStyle.DX1000, "an unknown make is the DX1000");
        same(h, cleaned.channels, 12, "7 channels is not a DX1000: the next size up");
        same(h, cleaned.interval, PaperlessRecorderData.DEFAULT_INTERVAL, "a non-number interval");
        same(h, cleaned.layout, PaperlessRecorderData.LAYOUT_HORIZONTAL, "unknown layout");
        check(h, cleaned.channel(0).lo == 0.0 && cleaned.channel(0).dec == 4 && cleaned.channel(0).alarms[0].type.equals("off"), "clamped fields");
        same(h, cleaned.screen, RecorderScreen.TREND, "a DX2000 screen is not on a DX1000");
        check(h, cleaned.currentGroup <= 9, "group in range");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyEditIsValidatedAndTheMakesLimitsApply(GameTestHelper h) {
        List<dev.bwr.mod.panel.component.ConfigField> schema = ComponentTypes.PAPERLESS_RECORDER.configSchema();
        PaperlessRecorderData data = new PaperlessRecorderData();
        CompoundTag current = data.toTag();
        CompoundTag edit = new CompoundTag();
        edit.putInt("Interval", 999);
        edit.putString("Ch01Al1Type", "X");
        edit.putString("Ch02Al1Type", "H");
        edit.putString("Ch99Tag", "ghost");
        edit.putString("Ch01Tag", "ABCDEFGHIJKLMNOPQRSTUVWXYZ\u0007§");
        edit.putDouble("Ch01Lo", Double.NaN);
        edit.putDouble("Ch01Hi", 1.0e30);
        edit.putInt("Ch01Color", 0x1FFFFFF);
        edit.putInt("Ch01Al1Level", 9);
        edit.putInt("AutoSwitch", 3);
        edit.putInt("Channels", 9);
        edit.putInt("Grid", 1);
        edit.putString("Background", "purple");
        edit.putInt("G01C1", 99);
        edit.putString("DotSeq", "x"); // latched keys are never taken from an edit
        CompoundTag merged = ConfigSchema.merge(schema, current, edit);
        data.adjust(merged);
        PaperlessRecorderData result = new PaperlessRecorderData();
        result.load(merged);
        same(h, result.interval, 14, "interval clamped");
        same(h, result.channel(0).alarms[0].type, "off", "bad alarm type ignored");
        same(h, result.channel(1).alarms[0].type, "H", "good alarm type taken");
        check(h, !merged.contains("Ch99Tag"), "an unknown key is dropped");
        same(h, result.channel(0).tag, "ABCDEFGHIJKLMNOP", "tag truncated to 16 and stripped of control characters");
        same(h, result.channel(0).lo, 0.0, "NaN refused");
        same(h, result.channel(0).hi, PaperlessRecorderData.LIMIT, "huge span clamped");
        same(h, result.channel(0).color, 0xFFFFFF, "colour masked");
        same(h, result.channel(0).alarms[0].level, 4, "level clamped");
        same(h, result.autoSwitch, 5, "auto switching is off or at least 5 s");
        same(h, result.channels, 12, "9 channels is not a DX1000 size");
        same(h, result.grid, 4, "grid clamped");
        same(h, result.background, "white", "bad background ignored");
        same(h, result.group(0).slots[0], 48, "group slot clamped to the largest make");
        check(h, result.dotCount() == 0, "no edit writes the history");

        // The make changes the limits.
        CompoundTag toDx2000 = data.toTag();
        CompoundTag styleEdit = new CompoundTag();
        styleEdit.putString("RecorderStyle", "yokogawa_dx2000");
        styleEdit.putInt("Channels", 9);
        CompoundTag mergedStyle = ConfigSchema.merge(schema, toDx2000, styleEdit);
        data.adjust(mergedStyle);
        PaperlessRecorderData dx2000 = new PaperlessRecorderData();
        dx2000.load(mergedStyle);
        same(h, dx2000.style, RecorderStyle.DX2000, "style");
        same(h, dx2000.channels, 10, "9 channels is a DX2010");
        same(h, ComponentTypes.PAPERLESS_RECORDER.inputPorts(dx2000).size(), 10, "ports follow the channel count");
        CompoundTag backEdit = new CompoundTag();
        backEdit.putString("RecorderStyle", "yokogawa_dx1000");
        backEdit.putInt("Channels", 48);
        CompoundTag mergedBack = ConfigSchema.merge(schema, dx2000.toTag(), backEdit);
        dx2000.adjust(mergedBack);
        PaperlessRecorderData dx1000 = new PaperlessRecorderData();
        dx1000.load(mergedBack);
        same(h, dx1000.channels, 12, "a DX1000 has at most 12");

        // A changed trend interval clears the history; a changed alarm clears its state.
        PaperlessRecorderData running = sixtySecondRecorder();
        running.channel(0).alarms[0].type = "H";
        running.channel(0).alarms[0].value = 10.0;
        for (long t = 0; t < 100; t += 2) {
            running.sample(t, values(50.0));
        }
        check(h, running.dotCount() > 0 && running.alarmStateOf(0, 0) == State.ACTIVE_UNACK, "history and an alarm");
        CompoundTag intervalEdit = new CompoundTag();
        intervalEdit.putInt("Interval", 2);
        CompoundTag mergedInterval = ConfigSchema.merge(schema, running.toTag(), intervalEdit);
        running.adjust(mergedInterval);
        PaperlessRecorderData reinterval = new PaperlessRecorderData();
        reinterval.load(mergedInterval);
        same(h, reinterval.dotCount(), 0L, "a new trend interval starts a new history");
        same(h, reinterval.alarmStateOf(0, 0), State.ACTIVE_UNACK, "but leaves the alarm alone");
        CompoundTag alarmEdit = new CompoundTag();
        alarmEdit.putDouble("Ch01Al1Value", 99.0);
        CompoundTag mergedAlarm = ConfigSchema.merge(schema, running.toTag(), alarmEdit);
        running.adjust(mergedAlarm);
        PaperlessRecorderData realarm = new PaperlessRecorderData();
        realarm.load(mergedAlarm);
        same(h, realarm.alarmStateOf(0, 0), State.NORMAL, "an alarm whose limit changed starts over");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void makesHaveTheirOwnGroupsAndChannelLimits(GameTestHelper h) {
        PaperlessRecorderData dx1000 = new PaperlessRecorderData();
        same(h, dx1000.style, RecorderStyle.DX1000, "style");
        same(h, dx1000.channels, 12, "a DX1012 by default");
        same(h, Arrays.toString(dx1000.groupChannels(0)), "[0, 1, 2, 3, 4, 5]", "group 1 holds six channels");
        same(h, Arrays.toString(dx1000.groupChannels(1)), "[6, 7, 8, 9, 10, 11]", "group 2 the next six");
        same(h, dx1000.activeGroups().length, 2, "only two groups have channels");
        same(h, RecorderStyle.DX1000.groups(), 10, "ten groups");
        dx1000.channels = 6;
        same(h, dx1000.groupSize(1), 0, "channels the recorder does not have are not shown");

        PaperlessRecorderData dx2000 = new PaperlessRecorderData(RecorderStyle.DX2000);
        same(h, dx2000.channels, 10, "a DX2010 by default");
        dx2000.channels = 48;
        same(h, dx2000.groupSize(0), 10, "ten channels per group");
        same(h, Arrays.toString(dx2000.groupChannels(4)), "[40, 41, 42, 43, 44, 45, 46, 47]", "group 5 holds the last eight");
        same(h, RecorderStyle.DX2000.groups(), 36, "36 groups");
        same(h, RecorderStyle.DX2000.textureWidth() * 1000 + RecorderStyle.DX2000.textureHeight(), 640_480, "640 x 480");
        same(h, RecorderStyle.DX1000.textureWidth() * 1000 + RecorderStyle.DX1000.textureHeight(), 320_240, "320 x 240");
        same(h, Arrays.toString(RecorderStyle.DX1000.channelOptions()), "[2, 4, 6, 12]", "DX1000 sizes");
        same(h, Arrays.toString(RecorderStyle.DX2000.channelOptions()), "[4, 8, 10, 20, 30, 40, 48]", "DX2000 sizes");
        same(h, RecorderStyle.forFootprint(Footprint.of(16, 16)), RecorderStyle.DX1000, "1 x 1 block");
        same(h, RecorderStyle.forFootprint(Footprint.of(24, 24)), RecorderStyle.DX1000, "24 cells");
        same(h, RecorderStyle.forFootprint(Footprint.of(32, 32)), RecorderStyle.DX2000, "2 x 2 blocks");
        same(h, ComponentTypes.PAPERLESS_RECORDER.createData(Footprint.of(32, 32)) instanceof PaperlessRecorderData d
                ? d.style : null, RecorderStyle.DX2000, "a recorder placed at 2 x 2 is a DX2000");
        same(h, ComponentTypes.PAPERLESS_RECORDER.footprints().get(0), Footprint.of(16, 16), "the default size is one block (a DX1000)");
        same(h, ComponentTypes.PAPERLESS_RECORDER.footprints().get(1), Footprint.of(32, 32), "and the next is a DX2000, twice as large");
        for (Footprint f : ComponentTypes.PAPERLESS_RECORDER.footprints()) {
            h.assertTrue(f.w() == f.h() && f.w() >= 12 && f.w() <= 48, "square, 12 to 48 cells: " + f);
        }
        for (int channels : RecorderStyle.DX2000.channelOptions()) {
            PaperlessRecorderData d = new PaperlessRecorderData(RecorderStyle.DX2000);
            d.channels = channels;
            same(h, ComponentTypes.PAPERLESS_RECORDER.inputPorts(d).size(), channels, "ports of a " + channels + " channel recorder");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void keysDriveTheMenuTheFuncStripAndTheCover(GameTestHelper h) {
        PaperlessRecorderData data = new PaperlessRecorderData();
        RecorderUi ui = new RecorderUi();
        // DISP/ENTER opens the menu, down moves, DISP/ENTER chooses.
        check(h, ui.press(RecorderKey.DISP, data).action() == RecorderActions.NONE && ui.mode == RecorderUi.Mode.MENU, "menu opens");
        check(h, ui.press(RecorderKey.DOWN, data).action() == RecorderActions.NONE && ui.cursor == 1, "cursor moves in the menu");
        RecorderUi.Result chosen = ui.press(RecorderKey.DISP, data);
        same(h, chosen.action(), RecorderActions.screen(RecorderScreen.DIGITAL), "DISP/ENTER chooses digital");
        RecorderActions.apply(data, chosen.action());
        same(h, data.screen, RecorderScreen.DIGITAL, "applied");
        same(h, ui.mode, RecorderUi.Mode.NONE, "menu closed");
        // ESC closes the menu without choosing.
        ui.press(RecorderKey.DISP, data);
        ui.press(RecorderKey.DOWN, data);
        ui.press(RecorderKey.ESC, data);
        same(h, ui.mode, RecorderUi.Mode.NONE, "ESC closes");
        same(h, data.screen, RecorderScreen.DIGITAL, "and chooses nothing");
        // Outside the menu: arrows change group and step the screens.
        RecorderActions.apply(data, ui.press(RecorderKey.RIGHT, data).action());
        same(h, data.currentGroup, 1, "right is the next group");
        RecorderActions.apply(data, ui.press(RecorderKey.RIGHT, data).action());
        same(h, data.currentGroup, 0, "and wraps among groups that have channels");
        RecorderActions.apply(data, ui.press(RecorderKey.LEFT, data).action());
        same(h, data.currentGroup, 1, "left is the previous");
        RecorderActions.apply(data, ui.press(RecorderKey.DOWN, data).action());
        same(h, data.screen, RecorderScreen.BAR, "down steps trend, digital, bar");
        RecorderActions.apply(data, ui.press(RecorderKey.UP, data).action());
        RecorderActions.apply(data, ui.press(RecorderKey.UP, data).action());
        same(h, data.screen, RecorderScreen.TREND, "up steps back");
        // FUNC: the strip, the soft keys, Next.
        ui.press(RecorderKey.FUNC, data);
        same(h, ui.mode, RecorderUi.Mode.FUNC, "FUNC opens the soft key strip");
        same(h, String.join(",", ui.softLabels(RecorderStyle.DX1000)), "ALM ACK,MESSAGE,FREE,START,NEXT", "strip labels");
        ui.press(RecorderKey.SOFT5, data);
        same(h, String.join(",", ui.softLabels(RecorderStyle.DX1000)), "STOP,,,,NEXT", "the next page");
        RecorderActions.apply(data, ui.press(RecorderKey.SOFT1, data).action());
        check(h, !data.running, "STOP on the second page");
        ui.press(RecorderKey.FUNC, data);
        ui.press(RecorderKey.SOFT2, data);
        same(h, ui.mode, RecorderUi.Mode.MESSAGE, "Message opens the message keys");
        data.setRunning(true);
        RecorderUi.Result message = ui.press(RecorderKey.SOFT2, data);
        same(h, message.action(), RecorderActions.MESSAGE_BASE + 2, "soft 2 is message 2");
        RecorderActions.apply(data, message.action());
        same(h, data.messageEntries().get(0).text(), "MESSAGE 2", "written at the current position");
        ui.press(RecorderKey.FUNC, data);
        same(h, ui.press(RecorderKey.SOFT3, data).effect(), RecorderUi.Effect.OPEN_FREE_MESSAGE, "Free message opens a text box");
        same(h, ui.press(RecorderKey.MENU, data).effect(), RecorderUi.Effect.OPEN_SETUP, "MENU opens the setup screen");
        same(h, ui.press(RecorderKey.USER, data).action(), RecorderActions.NONE, "USER does nothing but click");
        check(h, !RecorderActions.valid(9) && !RecorderActions.valid(-1) && !RecorderActions.valid(111) && !RecorderActions.valid(100) && !RecorderActions.valid(34),
                "the server refuses codes that mean nothing");

        // The DX2000's cover: closed, only the soft keys and the cover tab answer.
        PaperlessRecorderData big = new PaperlessRecorderData(RecorderStyle.DX2000);
        RecorderUi bigUi = new RecorderUi();
        check(h, !bigUi.press(RecorderKey.FUNC, big).handled() && !bigUi.press(RecorderKey.SOFT1, big).handled()
                && !bigUi.press(RecorderKey.MENU, big).handled(), "FUNC, the soft keys and MENU are behind the closed cover");
        check(h, bigUi.press(RecorderKey.DISP, big).handled() && bigUi.mode == RecorderUi.Mode.MENU, "the DISP/ENTER ring is not");
        bigUi.mode = RecorderUi.Mode.NONE;
        RecorderActions.apply(big, bigUi.press(RecorderKey.COVER_TAB, big).action());
        check(h, big.coverOpen, "the tab opens the cover");
        check(h, bigUi.press(RecorderKey.FUNC, big).handled() && bigUi.mode == RecorderUi.Mode.FUNC, "and the keys answer");
        bigUi.mode = RecorderUi.Mode.NONE;
        same(h, RecorderUi.menu(RecorderStyle.DX2000).size(), 10, "the DX2000 menu has circular and four 4-panel layouts");
        RecorderActions.apply(big, RecorderActions.QUAD_BASE + 2);
        same(h, big.screen, RecorderScreen.FOUR_PANEL, "four-panel");
        same(h, big.quad, 2, "all digital");
        RecorderActions.apply(big, RecorderActions.screen(RecorderScreen.CIRCULAR));
        same(h, big.screen, RecorderScreen.CIRCULAR, "circular");
        RecorderActions.apply(data, RecorderActions.screen(RecorderScreen.CIRCULAR));
        check(h, data.screen != RecorderScreen.CIRCULAR, "a DX1000 has no circular trend");
        RecorderActions.apply(data, RecorderActions.COVER_TOGGLE);
        check(h, !data.coverOpen, "a DX1000 has no cover");
        same(h, RecorderUi.itemsPerPage(RecorderStyle.DX2000), 5, "DX2000 soft keys");
        h.succeed();
    }

    // ---- fixture for the tests with a world

    private static final class Stage {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final FakePlayer player;
        /** Where devices stand: inside the force-loaded area and 8 blocks below the wall. */
        final BlockPos devices;
        private final Runnable release;
        private final List<Runnable> steps = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();

        private Stage(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.origin = new BlockPos(ORIGIN + slot * 64, 110, ORIGIN);
            this.devices = origin.offset(-12, -8, 0);
            // Installed before anything is acquired: runs on pass, failure and timeout, once.
            this.release = EntityLookupProbe.onFinished(helper, () -> {
                clear();
                hold(false);
            });
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("paperless-recorder-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "recorder-" + slot));
        }

        static void run(GameTestHelper helper, int slot, Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            helper.runAfterDelay(2, () -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                    body.accept(s);
                } catch (RuntimeException | Error e) {
                    s.release.run();
                    throw e;
                }
                s.next();
            });
        }

        void then(int delay, Runnable step) {
            delays.add(delay);
            steps.add(step);
        }

        private void next() {
            if (steps.isEmpty()) {
                release.run();
                helper.succeed();
                return;
            }
            int delay = delays.remove(0);
            Runnable step = steps.remove(0);
            helper.runAfterDelay(delay, () -> {
                try {
                    step.run();
                } catch (RuntimeException | Error e) {
                    release.run();
                    throw e;
                }
                next();
            });
        }

        private void hold(boolean force) {
            // just what the wall, the valve and the player need: a handful of chunks, so releasing them is cheap
            for (int x = (origin.getX() - 26) >> 4; x <= (origin.getX() + 6) >> 4; x++) {
                for (int z = (origin.getZ() - 8) >> 4; z <= (origin.getZ() + 8) >> 4; z++) {
                    FixtureChunks.force(level, origin, x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        private void clear() {
            for (int x = -24; x <= 4; x++) {
                for (int y = -12; y <= 12; y++) {
                    for (int z = -6; z <= 6; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(24, 12, 12))) {
                item.discard();
            }
        }

        BlockPos at(int column, int row) {
            return origin.relative(FACING.getCounterClockWise(), column).below(row);
        }

        void wall(int columns, int rows) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    level.setBlock(at(c, r), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, FACING), 3);
                }
            }
        }

        PanelBlockEntity be(BlockPos pos) {
            return (PanelBlockEntity) level.getBlockEntity(pos);
        }

        PanelComponent recorder(PanelBlockEntity block, Footprint footprint) {
            PanelComponent component = PanelOps.placeDirect(level, block.getBlockPos(), 0, 0, ComponentTypes.PAPERLESS_RECORDER, footprint);
            helper.assertTrue(component != null, "the recorder did not fit");
            return component;
        }

        MainSteamIsolationValveBlockEntity msiv(BlockPos root) {
            var block = BwrBlocks.MSIV.get();
            var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, new ItemStack(block));
            var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
            valve.setDemandOpen(false);
            valve.tickValve(5);
            return valve;
        }

        ItemStack linker(BlockPos device) {
            ItemStack stack = new ItemStack(BwrItems.PANEL_LINKER.get());
            if (device != null) {
                stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return stack;
        }

        Result bindPlant(PanelBlockEntity block, PanelComponent component, int port, String key, double scale) {
            linker(devices);
            return WiringOps.bind(player, new PanelPayloads.Bind(block.getBlockPos(), component.id(), false, port,
                    BindKind.PLANT.ordinal(), devices, key, BlockPos.ZERO, 0, 0, scale));
        }

        void configure(PanelBlockEntity block, PanelComponent component, CompoundTag config) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(player, block.getBlockPos(), component.id(), edit), "configure refused");
        }

        void press(PanelBlockEntity block, PanelComponent component, int code) {
            helper.assertTrue(PanelOps.operate(player, block.getBlockPos(), component.id(), OperateAction.PRESS, code),
                    "the key press " + code + " was refused");
        }

        PanelBlockEntity copyOf(PanelBlockEntity be) {
            PanelBlockEntity copy = (PanelBlockEntity) BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(),
                    be.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
            helper.assertTrue(copy != null, "block entity reload");
            return copy;
        }
    }

    private static int sampleWait() {
        return dev.bwr.mod.panel.PanelConfig.sampleTicks() + 3;
    }

    private static PaperlessRecorderData dataOf(PanelComponent component) {
        return (PaperlessRecorderData) component.data();
    }

    // ---- with a world

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aBoundPlantSignalRecordsDotsAndTheRecorderChangesNothingInThePlant(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            PaperlessRecorderData data = dataOf(rec);
            var valve = s.msiv(s.devices);
            valve.energy().receiveEnergy(1_000_000, false); // electrical energy is what opens it
            CompoundTag config = new CompoundTag();
            config.putInt("Interval", 0); // 5 s per division: a dot every 3.3 ticks
            s.configure(block, rec, config);
            check(helper, data.interval == 0 && data.channels == 12 && rec.inputCount() == 12, "twelve ports on a DX1012");
            check(helper, s.bindPlant(block, rec, 0, "position", 100.0) == Result.OK, "binding a plant signal to channel 1");
            check(helper, rec.source(0) instanceof PlantSource, "the source is a plant source");
            check(helper, PanelSampler.isTracked(block), "a recorder with a bound input is sampled by the panel sampler");
            check(helper, rec.input(0) == 0.0, "closed reads 0: " + rec.input(0));

            s.then(60, () -> {
                check(helper, data.dotCount() >= 10, "about 18 dots in 60 ticks: " + data.dotCount());
                check(helper, data.dotMin(0, 0) == 0.0f && data.dotMax(0, 0) == 0.0f, "the first dot saw the closed valve");
                check(helper, Float.isNaN(data.dotMin(1, 3)), "an unbound channel records no signal");
                valve.setDemandOpen(true);
                for (int i = 0; i < 90; i++) {
                    valve.tickValve(.05);
                }
            });
            s.then(sampleWait() + 2, () -> {
                long newest = data.dotCount() - 1;
                boolean sawRise = false;
                for (long dot = 0; dot <= newest; dot++) {
                    sawRise |= data.dotMax(0, dot) == 100.0f; // the valve may already be closing again: the dot keeps the peak
                }
                check(helper, sawRise, "the open valve reads 100 and a dot holds that peak: input " + rec.input(0) + " position " + valve.getPosition() + " dots " + data.dotCount() + " last " + data.dotMax(0, newest) + " tracked " + PanelSampler.isTracked(block));
                // Nothing in the plant, nothing on the panel, was changed by recording.
                check(helper, valve.isDemandOpen(), "the valve demand is exactly where the test left it");
                check(helper, rec.outputCount() == 0 && rec.type().outputPorts(data).isEmpty(), "a recorder has no outputs");
                check(helper, block.redstonePower() == 0, "and drives no redstone");
                check(helper, rec.sinks(0).isEmpty(), "no sinks");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aRecorderWiredToAConstantStillKeepsItsClock(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            PaperlessRecorderData data = dataOf(rec);
            CompoundTag config = new CompoundTag();
            config.putInt("Interval", 0);
            s.configure(block, rec, config);
            check(helper, !PanelSampler.isTracked(block), "nothing bound: nothing sampled");
            CompoundTag constant = new CompoundTag();
            constant.putString("Kind", InputSources.KIND_CONSTANT);
            constant.putDouble("Value", 3.5);
            ListTag inputs = new ListTag();
            inputs.add(constant);
            CompoundTag edit = new CompoundTag();
            edit.put("Inputs", inputs);
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            check(helper, PanelOps.configure(s.player, block.getBlockPos(), rec.id(), edit), "a constant on channel 1");
            check(helper, rec.input(0) == 3.5, "the constant is read: " + rec.input(0));
            check(helper, PanelSampler.isTracked(block), "a recorder with a constant wired is still sampled for its clock");
            s.then(60, () -> {
                check(helper, data.dotCount() >= 10, "dots are closed: " + data.dotCount());
                check(helper, data.dotMin(0, 2) == 3.5f && data.dotMax(0, 2) == 3.5f, "of the constant");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theRingAndTheSettingsSurviveSaveLoadAndANetworkCopy(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            PaperlessRecorderData data = dataOf(rec);
            CompoundTag config = new CompoundTag();
            config.putInt("Interval", 0);
            config.putString("Ch03Tag", "RPV LEVEL");
            config.putString("Ch01Al1Type", "H");
            config.putDouble("Ch01Al1Value", 10.0);
            s.configure(block, rec, config);
            for (int i = 0; i < 20; i++) {
                int step = i;
                s.then(2, () -> {
                    block.setInput(rec, 0, step < 10 ? 1.0 + step : 40.0 - step);
                    block.setInput(rec, 1, 5.0);
                    block.sampleLive();
                });
            }
            s.then(2, () -> {
                check(helper, data.dotCount() >= 8, "dots were recorded: " + data.dotCount());
                CompoundTag saved = block.savePanel(false);
                PanelBlockEntity copy = s.copyOf(block);
                check(helper, copy.savePanel(false).equals(saved), "the block saves as a fixed point");
                PaperlessRecorderData reloaded = dataOf(copy.component(rec.id()));
                check(helper, reloaded.dotCount() == data.dotCount(), "the ring survives: " + reloaded.dotCount() + " vs " + data.dotCount());
                for (long dot = 0; dot < data.dotCount(); dot++) {
                    check(helper, reloaded.dotMin(0, dot) == data.dotMin(0, dot) && reloaded.dotMax(1, dot) == data.dotMax(1, dot),
                            "dot " + dot + " survives");
                }
                check(helper, reloaded.channel(2).tag.equals("RPV LEVEL") && reloaded.alarmStateOf(0, 0) == data.alarmStateOf(0, 0),
                        "settings and the alarm state survive");
                check(helper, reloaded.logSize() == data.logSize() && data.logSize() > 0, "and the log: " + data.logSize());
                // The network form (full state to a client) carries the same ring.
                PanelBlockEntity client = s.copyOf(block);
                client.applyNetworkState(block.savePanel(true));
                PaperlessRecorderData onClient = dataOf(client.component(rec.id()));
                check(helper, onClient.dotCount() == data.dotCount() && onClient.dotMax(0, 3) == data.dotMax(0, 3), "a client's copy");
                // A recorder saved without any of its keys loads as a default one with the ports of a DX1012.
                CompoundTag bare = block.savePanel(false);
                ListTag comps = bare.getList("Components", net.minecraft.nbt.Tag.TAG_COMPOUND);
                comps.getCompound(0).put("Data", new CompoundTag());
                PanelBlockEntity fresh = s.copyOf(block);
                fresh.loadPanel(bare);
                PanelComponent old = fresh.component(rec.id());
                check(helper, old.inputCount() == 12 && dataOf(old).interval == PaperlessRecorderData.DEFAULT_INTERVAL
                        && dataOf(old).dotCount() == 0, "an old save with no recorder keys loads as defaults");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anAlarmReachesAckThroughTheFuncSoftKeyAndThroughABoundPushbutton(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            s.wall(3, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            PaperlessRecorderData data = dataOf(rec);
            PanelBlockEntity pbBlock = s.be(s.at(2, 0));
            PanelComponent button = PanelOps.placeDirect(s.level, pbBlock.getBlockPos(), 2, 2, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
            helper.assertTrue(button != null, "the pushbutton did not fit");
            PanelComponent otherButton = PanelOps.placeDirect(s.level, pbBlock.getBlockPos(), 9, 2, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
            helper.assertTrue(otherButton != null, "the second pushbutton did not fit");
            CompoundTag buttonConfig = new CompoundTag();
            buttonConfig.putString("Role", "ack");
            buttonConfig.putInt("AnnGroup", 1);
            s.configure(pbBlock, button, buttonConfig);
            CompoundTag otherConfig = new CompoundTag();
            otherConfig.putString("Role", "ack");
            otherConfig.putInt("AnnGroup", 2);
            s.configure(pbBlock, otherButton, otherConfig);
            var valve = s.msiv(s.devices);
            CompoundTag config = new CompoundTag();
            config.putString("Ch01Al1Type", "H");
            config.putDouble("Ch01Al1Value", 0.5);
            s.configure(block, rec, config);
            check(helper, s.bindPlant(block, rec, 0, "demand_open", 1.0) == Result.OK, "demand_open on channel 1");
            check(helper, data.alarmStateOf(0, 0) == State.NORMAL, "closed: no alarm");
            valve.setDemandOpen(true);
            RecorderUi ui = new RecorderUi();

            s.then(sampleWait(), () -> {
                check(helper, data.alarmStateOf(0, 0) == State.ACTIVE_UNACK, "open: the high alarm is on and flashing: " + data.alarmStateOf(0, 0));
                // FUNC, then soft key 1 (AlarmACK): the same keys a player presses, through the key logic and the server.
                ui.press(RecorderKey.FUNC, data);
                RecorderUi.Result ack = ui.press(RecorderKey.SOFT1, data);
                check(helper, ack.action() == RecorderActions.ACK, "FUNC then soft key 1 asks for ACK");
                s.press(block, rec, ack.action());
                check(helper, data.alarmStateOf(0, 0) == State.ACTIVE_ACK, "ACK from the FUNC soft key: steady: " + data.alarmStateOf(0, 0));
                check(helper, data.logEntries().stream().map(e -> e.kind()).toList().equals(List.of(0, 2)), "ON then ACK in the log: " + data.logEntries());
                valve.setDemandOpen(false);
            });
            s.then(sampleWait(), () -> {
                check(helper, data.alarmStateOf(0, 0) == State.NORMAL, "acknowledged and cleared: normal");
                valve.setDemandOpen(true);
            });
            s.then(sampleWait(), () -> {
                check(helper, data.alarmStateOf(0, 0) == State.ACTIVE_UNACK, "on again");
                valve.setDemandOpen(false);
            });
            s.then(sampleWait(), () -> {
                check(helper, data.alarmStateOf(0, 0) == State.CLEARED_UNACK, "cleared before ACK: blinking green: " + data.alarmStateOf(0, 0));
                // A pushbutton with the ack role for another group does nothing to this recorder.
                s.press(pbBlock, otherButton, 1);
                s.press(pbBlock, otherButton, 0);
                check(helper, data.alarmStateOf(0, 0) == State.CLEARED_UNACK, "another group's ack button does not acknowledge it");
                // The ack pushbutton of its group does, through AnnunciatorGroups.
                helper.assertTrue(PanelOps.operate(s.player, pbBlock.getBlockPos(), button.id(), OperateAction.PRESS, 1.0), "pressing the button");
                check(helper, data.alarmStateOf(0, 0) == State.NORMAL, "the bound ack pushbutton acknowledged it: " + data.alarmStateOf(0, 0));
                helper.assertTrue(PanelOps.operate(s.player, pbBlock.getBlockPos(), button.id(), OperateAction.RELEASE, 0.0), "releasing");
                // Keys and acknowledgements touched nothing in the plant.
                check(helper, !valve.isDemandOpen(), "the valve is as the test left it");
                check(helper, block.redstonePower() == 0 && rec.outputCount() == 0, "no output of any kind");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theMakeAndChannelCountSizeThePortsAndEveryPortCanBeBound(GameTestHelper helper) {
        Stage.run(helper, 4, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            s.msiv(s.devices);
            check(helper, rec.inputCount() == 12, "a DX1012 has 12 ports");
            CompoundTag five = new CompoundTag();
            five.putInt("Channels", 5);
            s.configure(block, rec, five);
            check(helper, rec.inputCount() == 6 && dataOf(rec).channels == 6, "5 channels is the DX1006: " + rec.inputCount());
            CompoundTag dx2000 = new CompoundTag();
            dx2000.putString("RecorderStyle", "yokogawa_dx2000");
            dx2000.putInt("Channels", 40);
            s.configure(block, rec, dx2000);
            check(helper, dataOf(rec).style == RecorderStyle.DX2000 && rec.inputCount() == 40, "a DX2040 has 40 ports: " + rec.inputCount());
            check(helper, s.bindPlant(block, rec, 39, "position", 1.0) == Result.OK, "the 40th port binds");
            check(helper, rec.source(39) instanceof PlantSource, "and holds the binding");
            check(helper, s.bindPlant(block, rec, 40, "position", 1.0) != Result.OK && rec.inputCount() == 40,
                    "a 41st port does not exist");
            CompoundTag eight = new CompoundTag();
            eight.putInt("Channels", 8);
            s.configure(block, rec, eight);
            check(helper, rec.inputCount() == 8, "back to 8: " + rec.inputCount());
            CompoundTag backToDx1000 = new CompoundTag();
            backToDx1000.putString("RecorderStyle", "yokogawa_dx1000");
            backToDx1000.putInt("Channels", 48);
            s.configure(block, rec, backToDx1000);
            check(helper, rec.inputCount() == 12, "a DX1000 never has more than 12: " + rec.inputCount());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void keyPressesAndFreeMessagesAreValidatedByTheServer(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(3, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent rec = s.recorder(block, Footprint.of(16, 16));
            PaperlessRecorderData data = dataOf(rec);
            BlockPos pos = block.getBlockPos();
            // Free message: text is cleaned of control characters and cut to 32, blanks are refused.
            check(helper, PanelOps.recorderMessage(s.player, pos, rec.id(), "  PUMP A TRIP  "), "a free message");
            same(helper, data.messageEntries().get(0).text(), "PUMP A TRIP", "cleaned and stripped");
            check(helper, PanelOps.recorderMessage(s.player, pos, rec.id(), "X".repeat(100)), "a long message");
            same(helper, data.messageEntries().get(1).text().length(), 32, "cut to 32 characters");
            check(helper, !PanelOps.recorderMessage(s.player, pos, rec.id(), "   "), "a blank message is refused");
            check(helper, !PanelOps.recorderMessage(s.player, pos, rec.id() + 50, "HELLO"), "an unknown component is refused");
            PanelComponent pushbutton = PanelOps.placeDirect(s.level, s.be(s.at(2, 0)).getBlockPos(), 2, 2, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
            check(helper, pushbutton != null && !PanelOps.recorderMessage(s.player, s.at(2, 0), pushbutton.id(), "HELLO"),
                    "another component type is refused");
            // Keys: only codes the recorder knows, only a number, only a press, only within reach.
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, 0), "code 0 means nothing");
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, 9), "code 9 means nothing");
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, 2.5), "a fraction is refused");
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, Double.NaN), "NaN is refused");
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.TURN, RecorderActions.STOP), "only a press is a key");
            check(helper, data.running, "none of that stopped it");
            check(helper, PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, RecorderActions.STOP) && !data.running, "STOP");
            check(helper, !PanelOps.recorderMessage(s.player, pos, rec.id(), "WHILE STOPPED"), "nothing is written while memory sampling is stopped");
            check(helper, PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, RecorderActions.START) && data.running, "START");
            s.player.setPos(s.origin.getX() + 80, s.origin.getY(), s.origin.getZ());
            check(helper, !PanelOps.operate(s.player, pos, rec.id(), OperateAction.PRESS, RecorderActions.STOP) && data.running,
                    "a player out of reach cannot press a key");
            check(helper, !PanelOps.recorderMessage(s.player, pos, rec.id(), "TOO FAR"), "or write a message");
        });
    }

    @GameTest(template = "empty")
    public static void theRecorderHasAnItemARecipeAndNamesForEveryPort(GameTestHelper helper) {
        var level = helper.getLevel();
        check(helper, BwrItems.PAPERLESS_RECORDER.get().type() == ComponentTypes.PAPERLESS_RECORDER, "the item places the recorder type");
        var recipe = level.getRecipeManager().byKey(dev.bwr.mod.BwrMod.id("paperless_recorder"));
        check(helper, recipe.isPresent() && recipe.get().value().getResultItem(level.registryAccess()).is(BwrItems.PAPERLESS_RECORDER.get()),
                "the recorder can be crafted");
        PaperlessRecorderData dx2000 = new PaperlessRecorderData(RecorderStyle.DX2000);
        dx2000.channels = 48;
        var ports = ComponentTypes.PAPERLESS_RECORDER.inputPorts(dx2000);
        same(helper, ports.size(), 48, "a DX2048 has 48 ports");
        same(helper, ports.get(0).key(), "ch01", "first port key");
        same(helper, ports.get(47).key(), "ch48", "last port key");
        for (var port : ports) {
            check(helper, port.labelKey().startsWith("component.bwr.port.ch"), "port label key " + port.labelKey());
        }
        helper.succeed();
    }
}
