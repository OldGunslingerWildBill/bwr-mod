package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.component.PlantComputerType;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.plant.PlantApi;
import dev.bwr.mod.panel.plant.PlantBuiltin;
import dev.bwr.mod.panel.plant.PlantColors;
import dev.bwr.mod.panel.plant.PlantElement;
import dev.bwr.mod.panel.plant.PlantFormat;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantLimits;
import dev.bwr.mod.panel.plant.PlantScreen;
import dev.bwr.mod.panel.plant.PlantState;
import dev.bwr.mod.panel.plant.PlantTouch;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The plant computer ({@code bwr:plant_computer}, PLANT-COMPUTER.md): its limits, its state and patch codec, its
 * save, the built-in pages that read a real formed reactor, the touch event, and that nothing it does can act on the
 * plant. The CC peripheral calls are checked in {@code PlantComputerCcChecks}, which references CC types and so is only
 * reached when CC:Tweaked is loaded.
 *
 * <p>Namespace {@code bwrplant}, so {@code -PbwrOnlyPlant} runs just these. World fixtures at 400_000 / 400_000 and east
 * (see {@link HardwareStage}, {@link FcdTestSupport}).
 */
@GameTestHolder("bwrplant")
@PrefixGameTestTemplate(false)
public final class PlantComputerTests {
    private PlantComputerTests() {
    }

    static final int ORIGIN = 400_000;
    private static final String EMPTY = "empty";

    // ---- helpers

    /** Standalone data (no panel), 64 x 48 cells: a 31 x 15 character grid at 100 percent. */
    static PlantComputerData data() {
        PlantComputerData data = new PlantComputerData();
        data.setFallbackSize(64, 48);
        return data;
    }

    static Map<String, Object> spec(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static boolean rejects(Runnable call) {
        try {
            call.run();
            return false;
        } catch (PlantState.Rejected expected) {
            return true;
        }
    }

    private static String rejection(Runnable call) {
        try {
            call.run();
            return null;
        } catch (PlantState.Rejected expected) {
            return expected.getMessage();
        }
    }

    /** A page that uses every kind of element. */
    static void richProgram(PlantComputerData data) {
        PlantApi.title(data, 1, "SPDS");
        PlantApi.set(data, 1, "t1", spec("type", "text", "col", 2, "row", 2, "text", "REACTOR STATUS", "color", "cyan"));
        PlantApi.set(data, 1, "pw", spec("type", "field", "col", 2, "row", 3, "w", 20, "text", "POWER", "unit", "%", "value", 87.4,
                "warnHigh", 100.0, "alarmHigh", 110.0));
        PlantApi.set(data, 1, "lv", spec("type", "bar", "col", 2, "row", 4, "w", 28, "text", "LEVEL", "unit", "in", "min", -200.0,
                "max", 100.0, "value", 12.5, "alarmLow", -150.0, "warnLow", -100.0));
        PlantApi.set(data, 1, "tr", spec("type", "trend", "col", 2, "row", 6, "w", 28, "h", 6, "text", "PRESSURE", "unit", "psig",
                "min", 0.0, "max", 1400.0, "history", 60));
        for (int i = 0; i < 40; i++) {
            PlantApi.push(data, 1, "tr", 900 + 8 * Math.sin(i / 3.0) * 10);
        }
        PlantApi.set(data, 1, "s1", spec("type", "tile", "col", 2, "row", 13, "w", 9, "h", 2, "text", "CORE COOLING", "state", "ok"));
        PlantApi.set(data, 1, "s2", spec("type", "tile", "col", 12, "row", 13, "w", 9, "h", 2, "text", "HEAT SINK", "state", "warn"));
        PlantApi.set(data, 1, "k1", spec("type", "key", "key", 1, "text", "NEXT"));
        PlantApi.set(data, 1, "k2", spec("type", "key", "key", 2, "text", "BACK"));
        PlantApi.title(data, 2, "SECOND");
        PlantApi.set(data, 2, "t2", spec("type", "text", "col", 1, "row", 3, "text", "page two", "color", "amber"));
        PlantApi.setPage(data, 2);
    }

    // ---- registration

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void plantComputerIsRegisteredWithOneInputNoOutputsAndAFourBlockSpan(GameTestHelper helper) {
        ComponentType type = ComponentTypes.PLANT_COMPUTER;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("plant_computer"), "registered");
        helper.assertTrue(BwrItems.PLANT_COMPUTER.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> in = type.inputPorts(type.createData()).stream().map(p -> p.key()).toList();
        helper.assertTrue(in.equals(List.of("reactor")), "one input, reactor: " + in);
        helper.assertTrue(type.outputPorts(type.createData()).isEmpty(), "no outputs");
        helper.assertTrue(type.isInteractive() && type.keepsSampling(null), "it takes touches and keeps sampling");
        for (Footprint size : type.footprints()) {
            helper.assertTrue(size.w() <= 64 && size.h() <= 64, "within the 4 block span: " + size);
        }
        helper.assertTrue(type.footprints().get(0).equals(Footprint.of(64, 48)), "a new item is 4 x 3 blocks");
        helper.assertTrue(type.maxSpanBlocks() == 4, "the ordinary span");
        helper.succeed();
    }

    // ---- the state and its patches

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theStateRoundTripsAndEveryPatchConvergesTheClientCopy(GameTestHelper helper) {
        PlantComputerData data = data();
        richProgram(data);
        PlantState server = data.program();
        byte[] full = server.encodeFull();
        PlantState copy = new PlantState();
        helper.assertTrue(copy.loadFull(full) && java.util.Arrays.equals(copy.encodeFull(), full), "full state round trips");
        helper.assertTrue(copy.selected() == 1 && copy.element(0, "tr").count == 40 && copy.element(0, "pw").valueText().equals("87.4"),
                "selection, trend points and values arrive");

        PlantState client = new PlantState();
        byte[] first = server.drainPatch(ComponentData.MAX_PATCH);
        helper.assertTrue(first != null && client.applyPatch(first), "the first patch applies");
        helper.assertTrue(java.util.Arrays.equals(client.encodeFull(), server.encodeFull()), "the client has the state");
        helper.assertTrue(server.drainPatch(ComponentData.MAX_PATCH) == null && !server.pending(), "nothing pending after a drain");

        // A value, five trend points and a selection: a small delta, not the whole state.
        PlantApi.value(data, 1, "pw", 91.0);
        for (int i = 0; i < 5; i++) {
            PlantApi.push(data, 1, "tr", 950 + i);
        }
        PlantApi.setPage(data, 1);
        byte[] delta = server.drainPatch(ComponentData.MAX_PATCH);
        helper.assertTrue(delta != null && delta.length < 60 && (delta[0] & 0xFF) == 0x44, "a delta of " + (delta == null ? -1 : delta.length) + " bytes");
        helper.assertTrue(client.applyPatch(delta) && java.util.Arrays.equals(client.encodeFull(), server.encodeFull()),
                "the delta converges the client");

        // Hundreds of random edits, patched one by one.
        Random random = new Random(20261022L);
        String[] ids = {"a", "b", "c", "d", "e", "f"};
        for (int round = 0; round < 400; round++) {
            data.setClock(round * 100L); // tokens refill: this test is about the codec, not the rate limit
            int page = 1 + random.nextInt(4);
            String id = ids[random.nextInt(ids.length)];
            try {
                switch (random.nextInt(9)) {
                    case 0, 1 -> PlantApi.set(data, page, id, spec("type", "field", "col", 1 + random.nextInt(10), "row", 1 + random.nextInt(14),
                            "w", 12, "text", "F" + random.nextInt(99), "value", random.nextInt(1000) / 10.0));
                    case 2 -> PlantApi.set(data, page, id, spec("type", "trend", "col", 1, "row", 1 + random.nextInt(8), "w", 20, "h", 5,
                            "text", "T", "min", 0.0, "max", 100.0, "history", 8 + random.nextInt(40)));
                    case 3 -> PlantApi.set(data, page, id, spec("type", "tile", "col", 1 + random.nextInt(20), "row", 1 + random.nextInt(12),
                            "w", 6, "h", 2, "text", "TILE " + random.nextInt(9), "state", PlantColors.STATES.get(random.nextInt(4))));
                    case 4 -> PlantApi.value(data, page, id, random.nextBoolean() ? (Object) (random.nextInt(2000) / 10.0) : null);
                    case 5, 6 -> PlantApi.push(data, page, id, random.nextInt(100));
                    case 7 -> PlantApi.remove(data, page, id);
                    default -> {
                        if (random.nextInt(8) == 0) {
                            PlantApi.clearPage(data, page);
                        } else {
                            PlantApi.title(data, page, "P" + random.nextInt(50));
                        }
                    }
                }
            } catch (PlantState.Rejected wrongKind) {
                // an update of an element of the wrong kind, or of one that is not there: refused, and nothing changed
            }
            if (round % 7 == 0) {
                PlantApi.setPage(data, 1 + random.nextInt(4));
            }
            byte[] patch = server.drainPatch(ComponentData.MAX_PATCH);
            if (patch != null) {
                helper.assertTrue(client.applyPatch(patch), "round " + round + ": the patch applies");
            }
            helper.assertTrue(java.util.Arrays.equals(client.encodeFull(), server.encodeFull()), "round " + round + ": the client converged");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void aValueThatShowsTheSameTextSendsNothingAndABurstOfWritesSendsOne(GameTestHelper helper) {
        PlantComputerData data = data();
        PlantApi.set(data, 1, "pw", spec("type", "field", "col", 1, "row", 1, "text", "P", "unit", "%", "decimals", 1, "value", 50.0));
        PlantApi.set(data, 1, "br", spec("type", "bar", "col", 1, "row", 2, "w", 20, "min", 0.0, "max", 100.0, "decimals", 0.0, "value", 50.0));
        data.program().drainPatch(ComponentData.MAX_PATCH);
        data.setClock(1000);
        helper.assertTrue(!PlantApi.value(data, 1, "pw", 50.04) && !data.program().pending(), "50.04 shows as 50.0: not a change");
        helper.assertTrue(!PlantApi.value(data, 1, "br", 50.2) && !data.program().pending(), "a bar moving by less than half a percent: not a change");
        helper.assertTrue(PlantApi.value(data, 1, "pw", 50.06) && data.program().pending(), "50.06 shows as 50.1: a change");
        data.program().drainPatch(ComponentData.MAX_PATCH);
        // A thousand writes to one field between two patches are one value in the patch.
        for (int t = 0; t < 20; t++) {
            data.setClock(2000 + t * 100L);
            for (int i = 0; i < 50; i++) {
                PlantApi.value(data, 1, "pw", 10 + t * 50 + i);
            }
        }
        byte[] patch = data.program().drainPatch(ComponentData.MAX_PATCH);
        helper.assertTrue(patch != null && patch.length < 24, "1000 writes, one value: " + (patch == null ? -1 : patch.length) + " bytes");
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theLimitsKeepTheWholeStateInOnePatch(GameTestHelper helper) {
        PlantComputerData data = data();
        data.setFallbackSize(64, 64);
        PlantState state = data.program();
        String longest = "ABCDEFGHIJKLMNOP";
        int made = 0;
        // 12 trends of 120 points each, then bars with the longest captions, units and ids, up to the limit of the display.
        for (int i = 0; i < PlantLimits.MAX_TRENDS; i++) {
            PlantElement t = new PlantElement(PlantElement.Kind.TREND, "TREND" + String.format("%05d", i));
            t.col = 1;
            t.row = 1;
            t.w = 10;
            t.h = 5;
            t.text = longest;
            t.unit = "ABCDEF";
            t.len = PlantLimits.MAX_POINTS;
            t.points = new char[t.len];
            state.put(i / 2, t);
            for (int p = 0; p < PlantLimits.MAX_POINTS; p++) {
                state.push(i / 2, t.id, p);
            }
            made++;
        }
        for (int i = 0; made < PlantLimits.MAX_ELEMENTS; i++) {
            PlantElement bar = new PlantElement(PlantElement.Kind.BAR, "B" + String.format("%09d", i));
            bar.col = 1;
            bar.row = 1;
            bar.w = 10;
            bar.text = longest;
            bar.unit = "ABCDEF";
            bar.value = 1.0f;
            bar.warnLow = 1;
            bar.warnHigh = 2;
            bar.alarmLow = 3;
            bar.alarmHigh = 4;
            state.put(i % PlantLimits.MAX_PAGES, bar);
            made++;
        }
        helper.assertTrue(state.elementCount() == PlantLimits.MAX_ELEMENTS && state.trendCount() == PlantLimits.MAX_TRENDS,
                "the display is full: " + state.elementCount() + " elements, " + state.trendCount() + " trends");
        for (int page = 0; page < PlantLimits.MAX_PAGES; page++) {
            state.title(page, "ABCDEFGHIJKLMNOPQRSTUVWX");
        }
        byte[] full = state.encodeFull();
        helper.assertTrue(full.length <= ComponentData.MAX_PATCH, "the fullest state is " + full.length + " bytes; a patch holds " + ComponentData.MAX_PATCH);
        byte[] patch = state.drainPatch(ComponentData.MAX_PATCH);
        helper.assertTrue(patch != null && patch.length <= ComponentData.MAX_PATCH, "and so is the patch that sends it");
        PlantState client = new PlantState();
        helper.assertTrue(client.applyPatch(patch) && java.util.Arrays.equals(client.encodeFull(), full), "which a client applies");
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void malformedStatesAndPatchesAreIgnoredAndNeverThrow(GameTestHelper helper) {
        PlantComputerData data = data();
        richProgram(data);
        byte[] full = data.program().encodeFull();
        Random random = new Random(7);
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            byte[] bytes = full.clone();
            for (int k = 0; k < 1 + random.nextInt(4); k++) {
                bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
            }
            if (random.nextInt(3) == 0) {
                bytes = java.util.Arrays.copyOf(bytes, 1 + random.nextInt(bytes.length - 1));
            }
            PlantState target = new PlantState();
            PlantState before = new PlantState();
            if (target.loadFull(bytes)) {
                accepted++;
                helper.assertTrue(target.elementCount() <= PlantLimits.MAX_ELEMENTS && target.trendCount() <= PlantLimits.MAX_TRENDS,
                        "whatever loads is within the limits");
            } else {
                helper.assertTrue(java.util.Arrays.equals(target.encodeFull(), before.encodeFull()), "a refused state leaves an empty one");
            }
            target.applyPatch(bytes); // must not throw either
            target.applyPatch(java.util.Arrays.copyOfRange(bytes, 1, bytes.length));
        }
        helper.assertTrue(accepted < 3000, "some of the damaged states were refused");
        helper.assertTrue(!new PlantState().loadFull(new byte[0]) && !new PlantState().applyPatch(null) && !new PlantState().applyPatch(new byte[] {9}),
                "empty and unknown input are refused");
        // Text on the wire is printable ASCII, whatever the bytes say.
        PlantComputerData text = data();
        PlantApi.set(text, 1, "x", spec("type", "text", "col", 1, "row", 1, "text", "café §\u0001"));
        helper.assertTrue(text.program().element(0, "x").text.equals("caf? ??"), "non-ASCII text is cleaned: " + text.program().element(0, "x").text);
        helper.succeed();
    }

    // ---- the program API: bounds and validation

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theApiRefusesWhatIsOutsideItsBounds(GameTestHelper helper) {
        PlantComputerData data = data();
        PlantLayout layout = data.layout();
        helper.assertTrue(layout.cols() == 31 && layout.rows() == 15, "a 64 x 48 display is a 31 x 15 grid: " + layout.cols() + " x " + layout.rows());
        int[] tick = {0};
        // each call is refused: the message says what
        Map<String, Runnable> refused = new LinkedHashMap<>();
        refused.put("page 0", () -> PlantApi.setPage(data, 0));
        refused.put("page 9", () -> PlantApi.setPage(data, PlantLimits.MAX_PAGES + 1));
        refused.put("title on page 9", () -> PlantApi.title(data, 9, "X"));
        refused.put("empty id", () -> PlantApi.set(data, 1, "", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        refused.put("long id", () -> PlantApi.set(data, 1, "ABCDEFGHIJK", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        refused.put("id with a space", () -> PlantApi.set(data, 1, "a b", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        refused.put("no type", () -> PlantApi.set(data, 1, "a", spec("col", 1, "row", 1)));
        refused.put("unknown type", () -> PlantApi.set(data, 1, "a", spec("type", "gauge", "col", 1, "row", 1)));
        refused.put("unknown setting", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 1, "row", 1, "text", "x", "size", 3.0)));
        refused.put("col 0", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 0, "row", 1, "text", "x")));
        refused.put("col past the grid", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 32, "row", 1, "text", "x")));
        refused.put("row past the grid", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 1, "row", 16, "text", "x")));
        refused.put("fractional col", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 1.5, "row", 1, "text", "x")));
        refused.put("field too wide for the grid", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 20, "row", 1, "w", 22, "text", "x")));
        refused.put("trend too low for the grid", () -> PlantApi.set(data, 1, "a", spec("type", "trend", "col", 1, "row", 12, "w", 10, "h", 6, "min", 0.0, "max", 1.0)));
        refused.put("no col", () -> PlantApi.set(data, 1, "a", spec("type", "field", "row", 1)));
        refused.put("text that is not a string", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 1, "row", 1, "text", 5.0)));
        refused.put("two texts", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 1, "row", 1, "text", "a", "caption", "b")));
        refused.put("unknown colour", () -> PlantApi.set(data, 1, "a", spec("type", "text", "col", 1, "row", 1, "text", "x", "color", "pink")));
        refused.put("decimals 5", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 1, "row", 1, "decimals", 5.0)));
        refused.put("a value that is not finite", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 1, "row", 1, "value", Double.POSITIVE_INFINITY)));
        refused.put("a limit that is not finite", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 1, "row", 1, "alarmHigh", Double.NaN)));
        refused.put("a value of 1e12", () -> PlantApi.set(data, 1, "a", spec("type", "field", "col", 1, "row", 1, "value", 1.0e12)));
        refused.put("a bar with max below min", () -> PlantApi.set(data, 1, "a", spec("type", "bar", "col", 1, "row", 1, "min", 5.0, "max", 5.0)));
        refused.put("a trend with history 7", () -> PlantApi.set(data, 1, "a", spec("type", "trend", "col", 1, "row", 1, "h", 5, "history", 7.0)));
        refused.put("a trend with history 121", () -> PlantApi.set(data, 1, "a", spec("type", "trend", "col", 1, "row", 1, "h", 5, "history", 121.0)));
        refused.put("a trend of 2 rows", () -> PlantApi.set(data, 1, "a", spec("type", "trend", "col", 1, "row", 1, "h", 2)));
        refused.put("a tile state that does not exist", () -> PlantApi.set(data, 1, "a", spec("type", "tile", "col", 1, "row", 1, "state", "blue")));
        refused.put("key 0", () -> PlantApi.set(data, 1, "a", spec("type", "key", "key", 0)));
        refused.put("key 7", () -> PlantApi.set(data, 1, "a", spec("type", "key", "key", PlantLimits.MAX_KEYS + 1)));
        refused.put("a key with no number", () -> PlantApi.set(data, 1, "a", spec("type", "key", "text", "x")));
        refused.put("a value for an element that is not there", () -> PlantApi.value(data, 1, "nothing", 1.0));
        refused.put("a push to an element that is not there", () -> PlantApi.push(data, 1, "nothing", 1.0));
        refused.put("a point that is not finite", () -> {
            PlantApi.set(data, 1, "tp", spec("type", "trend", "col", 1, "row", 1, "h", 5));
            PlantApi.push(data, 1, "tp", Double.NaN);
        });
        for (Map.Entry<String, Runnable> entry : refused.entrySet()) {
            data.setClock(++tick[0] * 100L);
            String message = rejection(entry.getValue());
            helper.assertTrue(message != null && !message.isEmpty(), "refused with a message: " + entry.getKey());
        }
        helper.assertTrue(data.program().elementCount() <= 1 && data.program().element(0, "a") == null, "nothing refused was kept: " + data.program().elementCount());
        PlantApi.remove(data, 1, "tp");

        // The right call after the wrong one works, and text is cut to its limit.
        helper.assertTrue(PlantApi.set(data, 1, "ok", spec("type", "text", "col", 1, "row", 1,
                "text", "0123456789012345678901234567890123456789")), "a long text is accepted ...");
        helper.assertTrue(data.program().element(0, "ok").text.length() == 31, "... and cut to the room left on the row (31 columns): " + data.program().element(0, "ok").text.length());
        PlantApi.set(data, 1, "cap", spec("type", "field", "col", 1, "row", 2, "w", 20, "text", "A CAPTION OF MANY MORE CHARACTERS", "unit", "KILOGRAMS"));
        PlantElement cap = data.program().element(0, "cap");
        helper.assertTrue(cap.text.length() == PlantLimits.MAX_LABEL && cap.unit.length() == PlantLimits.MAX_UNIT, "caption and unit are cut: " + cap.text + " / " + cap.unit);
        helper.assertTrue(PlantApi.title(data, 1, "A TITLE THAT IS WAY TOO LONG FOR A PAGE") && data.program().page(0).title.length() == PlantLimits.MAX_TITLE, "titles are cut");
        PlantApi.set(data, 1, "t1", spec("type", "trend", "col", 1, "row", 3, "w", 10, "h", 5, "min", 0.0, "max", 10.0));
        PlantApi.set(data, 1, "t8", spec("type", "trend", "col", 1, "row", 8, "w", 10, "h", 5, "min", 0.0, "max", 10.0, "history", 8.0));
        PlantApi.set(data, 1, "t9", spec("type", "trend", "col", 12, "row", 8, "w", 10, "h", 5, "min", 0.0, "max", 10.0, "history", 120.0));
        helper.assertTrue(data.program().element(0, "t8").len == 8 && data.program().element(0, "t9").len == 120 && data.program().element(0, "t1").len == 60,
                "history 8, 120 and the default 60");
        for (int i = 0; i < 200; i++) {
            data.setClock(100_000L + i * 100L);
            PlantApi.push(data, 1, "t8", i);
        }
        helper.assertTrue(data.program().element(0, "t8").count == 8, "a trend keeps its history and no more: " + data.program().element(0, "t8").count);
        helper.assertTrue(PlantApi.value(data, 1, "ok", "shorter") && data.program().element(0, "ok").text.equals("shorter"), "a text can be changed in place");
        helper.assertTrue(rejects(() -> PlantApi.value(data, 1, "t1", 3.0)), "a trend takes points, not a value");
        helper.assertTrue(rejects(() -> PlantApi.push(data, 1, "ok", 3.0)), "a text takes no points");
        helper.assertTrue(rejects(() -> PlantApi.value(data, 1, "ok", 3.0)), "a text takes a string");
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theApiRefusesWhatWouldExceedAPageOrTheDisplay(GameTestHelper helper) {
        PlantComputerData data = data();
        int made = 0;
        for (int page = 1; page <= 3; page++) {
            for (int i = 0; i < PlantLimits.MAX_ELEMENTS_PER_PAGE; i++) {
                data.setClock(++made * 100L);
                PlantApi.set(data, page, "e" + i, spec("type", "text", "col", 1, "row", 1 + i % 15, "text", "x"));
            }
        }
        int pageThree = page(data, 3);
        helper.assertTrue(pageThree == PlantLimits.MAX_ELEMENTS_PER_PAGE, "a page holds " + PlantLimits.MAX_ELEMENTS_PER_PAGE);
        data.setClock(1_000_000);
        String perPage = rejection(() -> PlantApi.set(data, 3, "extra", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        helper.assertTrue(perPage != null && perPage.contains("48"), "the 49th element of a page is refused: " + perPage);
        helper.assertTrue(PlantApi.set(data, 3, "e0", spec("type", "text", "col", 2, "row", 1, "text", "y")), "but an element of the page can be redefined");
        // page 4 holds the remaining 16 of the 160 of the display
        for (int i = 0; i < PlantLimits.MAX_ELEMENTS - 3 * PlantLimits.MAX_ELEMENTS_PER_PAGE; i++) {
            data.setClock(2_000_000L + i * 100L);
            PlantApi.set(data, 4, "e" + i, spec("type", "text", "col", 1, "row", 1, "text", "x"));
        }
        helper.assertTrue(data.program().elementCount() == PlantLimits.MAX_ELEMENTS, "160 elements: " + data.program().elementCount());
        data.setClock(3_000_000);
        String total = rejection(() -> PlantApi.set(data, 5, "one", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        helper.assertTrue(total != null && total.contains("160"), "the 161st element of the display is refused: " + total);
        PlantApi.remove(data, 4, "e0");
        helper.assertTrue(PlantApi.set(data, 5, "one", spec("type", "text", "col", 1, "row", 1, "text", "x")), "a removed element makes room");

        // trends: 12, and a thirteenth is refused; redefining one of the twelve is fine
        PlantComputerData trends = data();
        for (int i = 0; i < PlantLimits.MAX_TRENDS; i++) {
            trends.setClock((i + 1) * 100L);
            PlantApi.set(trends, 1 + i % 3, "tr" + i, spec("type", "trend", "col", 1, "row", 1 + (i / 3) * 3, "w", 10, "h", 3, "min", 0.0, "max", 1.0));
        }
        trends.setClock(10_000);
        String trendLimit = rejection(() -> PlantApi.set(trends, 4, "tr12", spec("type", "trend", "col", 1, "row", 1, "w", 10, "h", 3, "min", 0.0, "max", 1.0)));
        helper.assertTrue(trendLimit != null && trendLimit.contains("12"), "the 13th trend is refused: " + trendLimit);
        helper.assertTrue(PlantApi.set(trends, 1, "tr0", spec("type", "trend", "col", 1, "row", 1, "w", 10, "h", 3, "min", 0.0, "max", 2.0)),
                "a trend can be redefined");
        helper.assertTrue(PlantApi.set(trends, 4, "txt", spec("type", "text", "col", 1, "row", 1, "text", "x")), "other kinds are not limited by trends");

        // a soft key number once per page
        PlantComputerData keys = data();
        PlantApi.set(keys, 1, "ka", spec("type", "key", "key", 3, "text", "A"));
        helper.assertTrue(rejects(() -> PlantApi.set(keys, 1, "kb", spec("type", "key", "key", 3, "text", "B"))), "a second key 3 on a page is refused");
        helper.assertTrue(PlantApi.set(keys, 2, "kb", spec("type", "key", "key", 3, "text", "B")), "another page may use key 3");
        helper.assertTrue(PlantApi.set(keys, 1, "ka", spec("type", "key", "key", 4, "text", "A")), "a key can move to another number");

        // a built-in page is not drawn on by a program
        PlantComputerData builtin = data();
        builtin.mode = "overview";
        String mode = rejection(() -> PlantApi.set(builtin, 1, "a", spec("type", "text", "col", 1, "row", 1, "text", "x")));
        helper.assertTrue(mode != null && mode.contains("built-in"), "a display showing a built-in page refuses program calls: " + mode);
        helper.succeed();
    }

    private static int page(PlantComputerData data, int page) {
        return data.program().page(page - 1).elements.size();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theCallRateLimitRefillsAndNeverBanks(GameTestHelper helper) {
        PlantComputerData data = data();
        data.setClock(10);
        int allowed = 0;
        while (allowed < 10_000 && !rejects(() -> PlantApi.setPage(data, 1))) {
            allowed++;
        }
        helper.assertTrue(allowed == PlantLimits.CALL_BURST - 1 || allowed == PlantLimits.CALL_BURST,
                "a burst of " + PlantLimits.CALL_BURST + " calls in one tick: " + allowed);
        helper.assertTrue(rejects(() -> PlantApi.setPage(data, 1)), "the next one in the same tick is refused");
        String message = rejection(() -> PlantApi.setPage(data, 1));
        helper.assertTrue(message != null && message.contains("a second"), "the message names the rate: " + message);
        data.setClock(11);
        int refilled = 0;
        while (refilled < 1000 && !rejects(() -> PlantApi.setPage(data, 1))) {
            refilled++;
        }
        helper.assertTrue(refilled == 32, "one tick later " + (int) PlantLimits.CALL_REFILL_PER_TICK + " more: " + refilled);
        data.setClock(10_000); // however long it waits, the bucket holds one burst
        int again = 0;
        while (again < 10_000 && !rejects(() -> PlantApi.setPage(data, 1))) {
            again++;
        }
        helper.assertTrue(again == PlantLimits.CALL_BURST, "a long wait banks no more than one burst: " + again);
        // a refused call is not a change
        helper.assertTrue(data.program().isEmpty() && data.program().selected() == 0, "nothing was drawn");
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void patchesAreRateLimitedCoalescedAndFlushable(GameTestHelper helper) {
        PlantComputerData data = data();
        data.setClock(100);
        PlantApi.set(data, 1, "pw", spec("type", "field", "col", 1, "row", 1, "text", "P", "value", 1.0));
        byte[] first = data.drainPatch();
        helper.assertTrue(first != null, "the first change is sent at once");
        data.setClock(101);
        PlantApi.value(data, 1, "pw", 2.0);
        helper.assertTrue(data.drainPatch() == null && data.program().pending(), "a change one tick later waits (it stays pending)");
        data.setClock(103);
        helper.assertTrue(data.drainPatch() == null, "still inside the " + PlantLimits.PUBLISH_TICKS + " tick interval");
        data.setClock(104);
        byte[] second = data.drainPatch();
        helper.assertTrue(second != null && !data.program().pending(), "sent when the interval is over");
        PlantState client = new PlantState();
        helper.assertTrue(client.applyPatch(first) && client.applyPatch(second) && client.element(0, "pw").valueText().equals("2.0"), "and it applies");

        // flush asks for the next one early, and not twice within two ticks
        data.setClock(105);
        PlantApi.value(data, 1, "pw", 3.0);
        helper.assertTrue(data.drainPatch() == null, "waiting");
        helper.assertTrue(data.flush(), "flush asks for it");
        helper.assertTrue(data.drainPatch() != null, "and it is sent inside the interval");
        data.setClock(106);
        PlantApi.value(data, 1, "pw", 4.0);
        helper.assertTrue(!data.flush(), "a second flush within two ticks is not honoured");
        data.setClock(108);
        helper.assertTrue(data.flush() && data.drainPatch() != null, "a flush two ticks later is");
        helper.assertTrue(!data.flush(), "nothing pending: nothing to flush");

        // a program that changes a value every tick for 400 ticks sends one patch every 4 ticks, no more
        PlantComputerData busy = data();
        busy.setClock(0);
        PlantApi.set(busy, 1, "pw", spec("type", "field", "col", 1, "row", 1, "text", "P", "value", 0.0));
        int sent = 0;
        for (int tick = 0; tick < 400; tick++) {
            busy.setClock(tick);
            PlantApi.value(busy, 1, "pw", tick + 1);
            if (busy.drainPatch() != null) {
                sent++;
            }
        }
        helper.assertTrue(sent <= 400 / PlantLimits.PUBLISH_TICKS + 1 && sent >= 90, "400 ticks of changes: " + sent + " patches");
        helper.succeed();
    }

    // ---- the screen

    /** Records what a screen draws. */
    private static final class Recorder implements PlantScreen.Sink {
        record Text(String text, float x, float y, float scale, float z, int rgb) {
            float right() {
                return x + (dev.bwr.mod.panel.model.GaugeScale.textPixels(text) - 1.0f) * scale;
            }

            float bottom() {
                return y + dev.bwr.mod.panel.model.Legends.CAP_PIXELS * scale;
            }
        }

        final List<float[]> rects = new ArrayList<>();
        final List<float[]> lines = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();

        @Override
        public void rect(float x, float y, float w, float h, float z, int rgb, boolean emissive) {
            rects.add(new float[] {x, y, x + w, y + h, z});
        }

        @Override
        public void line(float x0, float y0, float x1, float y1, float thickness, float z, int rgb, boolean emissive) {
            lines.add(new float[] {x0, y0, x1, y1, thickness, z});
        }

        @Override
        public void text(String text, float x, float y, float scale, float z, int rgb) {
            texts.add(new Text(text, x, y, scale, z, rgb));
        }
    }

    private static Recorder record(PlantState state, PlantComputerData data, int w, int h, boolean program) {
        Recorder recorder = new Recorder();
        PlantScreen.draw(state, PlantLayout.of(w, h, data.textSize), w, h, program, "NO PROGRAM",
                "Run a CC program (bwr_plant_computer), or choose a built-in page with the wrench", recorder);
        return recorder;
    }

    private static String outside(Recorder r, int w, int h) {
        float eps = 0.01f;
        for (float[] rect : r.rects) {
            if (rect[0] < -eps || rect[1] < -eps || rect[2] > w + eps || rect[3] > h + eps) {
                return "rect " + java.util.Arrays.toString(rect);
            }
        }
        for (float[] line : r.lines) {
            if (line[0] < 0 || line[1] < 0 || line[2] > w || line[3] > h) {
                return "line " + java.util.Arrays.toString(line);
            }
        }
        for (Recorder.Text t : r.texts) {
            if (t.x < -eps || t.y < -eps || t.right() > w + eps || t.bottom() > h + eps) {
                return "text '" + t.text + "' at " + t.x + "," + t.y + " to " + t.right() + "," + t.bottom();
            }
            if (t.z <= PlantScreen.SCREEN_Z) {
                return "text '" + t.text + "' below the screen";
            }
        }
        return null;
    }

    private static PlantBuiltin.Readings readings(boolean bound, boolean formed) {
        return new PlantBuiltin.Readings() {
            @Override
            public boolean bound() {
                return bound;
            }

            @Override
            public boolean formed() {
                return formed;
            }

            @Override
            public float read(String key) {
                return switch (key) {
                    case "power" -> 0.874f;
                    case "level" -> -23.4f;
                    case "pressure" -> 1017.0f;
                    case "core_flow" -> 0.92f;
                    case "containment_pressure" -> 0.4f;
                    case "scram" -> 0.0f;
                    default -> 1234.5f;
                };
            }
        };
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void everyScreenKeepsItsShapesAndTextOnTheFaceAtEverySizeAndTextSize(GameTestHelper helper) {
        ComponentType type = ComponentTypes.PLANT_COMPUTER;
        List<String> failures = new ArrayList<>();
        int screens = 0;
        for (Footprint size : type.footprints()) {
            for (int percent : new int[] {GaugeScale.MIN_PERCENT, GaugeScale.DEFAULT_PERCENT, 150, GaugeScale.MAX_PERCENT}) {
                PlantComputerData data = data();
                data.setFallbackSize(size.w(), size.h());
                data.textSize = percent;
                PlantLayout layout = data.layout();
                float base = PlantScreen.baseScale(layout.cellW());
                // a program with elements that fit this grid
                PlantState program = data.program();
                try {
                    PlantApi.title(data, 1, "SAFETY PARAMETER DISPLAY");
                    int cols = layout.cols();
                    int rows = layout.rows();
                    PlantApi.set(data, 1, "tx", spec("type", "text", "col", 1, "row", Math.min(2, rows), "text", "A LONG LINE OF TEXT WITH MANY CHARACTERS IN IT",
                            "color", "amber"));
                    if (rows >= 6 && cols >= 14) {
                        PlantApi.set(data, 1, "fd", spec("type", "field", "col", 1, "row", 3, "w", cols, "text", "POWER", "unit", "MW", "value", 3456.7,
                                "alarmHigh", 3000.0));
                        PlantApi.set(data, 1, "br", spec("type", "bar", "col", 1, "row", 4, "w", cols, "text", "LEVEL", "unit", "in", "min", -200.0,
                                "max", 100.0, "value", -20.0, "warnLow", -50.0));
                    }
                    if (rows >= 12 && cols >= 14) {
                        PlantApi.set(data, 1, "tr", spec("type", "trend", "col", 1, "row", 5, "w", cols, "h", rows - 5, "text", "PRESSURE", "unit", "psig",
                                "min", 0.0, "max", 1400.0));
                        for (int i = 0; i < 60; i++) {
                            data.setClock(i * 100L);
                            PlantApi.push(data, 1, "tr", 700 + 600 * Math.sin(i / 5.0));
                        }
                    }
                    if (rows >= 4 && cols >= 12) {
                        PlantApi.set(data, 1, "t1", spec("type", "tile", "col", 1, "row", 2, "w", 6, "h", 2, "text", "CORE COOLING", "state", "alarm"));
                        PlantApi.set(data, 1, "k1", spec("type", "key", "key", 1, "text", "A LONG KEY"));
                        PlantApi.set(data, 1, "k6", spec("type", "key", "key", 6, "text", "END"));
                    }
                } catch (PlantState.Rejected e) {
                    failures.add(size + " at " + percent + "%: the test program was refused: " + e.getMessage());
                    continue;
                }
                List<Recorder> screensHere = new ArrayList<>();
                screensHere.add(record(program, data, size.w(), size.h(), true));
                screensHere.add(record(new PlantState(), data, size.w(), size.h(), true)); // NO PROGRAM
                for (int page = 0; page < PlantBuiltin.PAGES.size(); page++) {
                    PlantState built = PlantBuiltin.build(layout, page);
                    PlantBuiltin.update(built, readings(true, true), true);
                    PlantBuiltin.update(built, readings(true, true), true);
                    screensHere.add(record(built, data, size.w(), size.h(), false));
                }
                PlantState unbound = PlantBuiltin.build(layout, 0);
                PlantBuiltin.update(unbound, readings(false, false), true);
                screensHere.add(record(unbound, data, size.w(), size.h(), false));
                for (Recorder r : screensHere) {
                    screens++;
                    String problem = outside(r, size.w(), size.h());
                    if (problem != null) {
                        failures.add(size + " at " + percent + "%: " + problem);
                    }
                    for (Recorder.Text t : r.texts) {
                        // the text of the screen is one size (a share of the character cell), only ever made smaller to fit
                        float most = base * 2.4f + 0.001f;
                        float least = base * GaugeScale.MIN_TEXT_SHARE * 0.8f - 0.001f;
                        if (t.scale > most || t.scale < least) {
                            failures.add(size + " at " + percent + "%: '" + t.text + "' is at scale " + t.scale + ", base " + base);
                        }
                    }
                }
            }
        }
        helper.assertTrue(failures.isEmpty(), screens + " screens checked, " + failures.size() + " problems: "
                + failures.subList(0, Math.min(6, failures.size())));

        // Text scales with the wrench's Text size: half the size, half the cell, half the text.
        PlantComputerData half = data();
        half.textSize = 50;
        PlantComputerData full = data();
        full.textSize = 100;
        PlantApi.set(half, 1, "f", spec("type", "field", "col", 1, "row", 1, "w", 10, "text", "A", "value", 1.0));
        PlantApi.set(full, 1, "f", spec("type", "field", "col", 1, "row", 1, "w", 10, "text", "A", "value", 1.0));
        float scaleHalf = record(half.program(), half, 64, 48, true).texts.get(0).scale;
        float scaleFull = record(full.program(), full, 64, 48, true).texts.get(0).scale;
        helper.assertTrue(Math.abs(scaleFull / scaleHalf - 2.0f) < 0.05f, "the same field at 50 and 100 percent: " + scaleHalf + " and " + scaleFull);
        helper.assertTrue(half.layout().cols() == 62 && full.layout().cols() == 31, "and twice the columns");

        // A line too long for its room is made smaller first (one size for the caption and the number), and cut only when
        // even the least size would not hold it.
        PlantComputerData tight = data();
        PlantApi.set(tight, 1, "f", spec("type", "field", "col", 1, "row", 3, "w", 12, "text", "CAPTION TEXT", "unit", "kg/s", "value", 1234.5));
        Recorder fitted = record(tight.program(), tight, 64, 48, true);
        float base = PlantScreen.baseScale(tight.layout().cellW());
        Recorder.Text caption = fitted.texts.stream().filter(t -> t.text.startsWith("CAPTION")).findFirst().orElseThrow();
        Recorder.Text number = fitted.texts.stream().filter(t -> t.text.startsWith("1234.5")).findFirst().orElseThrow();
        helper.assertTrue(caption.text.equals("CAPTION TEXT") && number.text.equals("1234.5 kg/s") && caption.scale == number.scale
                        && caption.scale < base * 0.95f && caption.scale >= base * GaugeScale.MIN_TEXT_SHARE - 0.001f,
                "a field 12 characters wide shrinks its text instead of cutting it: '" + caption.text + "' / '" + number.text + "' at " + caption.scale + " of " + base);
        helper.succeed();
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void theNoProgramScreenAndTheBuiltinPagesShowWhatTheyShouldWithoutJudging(GameTestHelper helper) {
        PlantComputerData data = data();
        Recorder none = record(data.shown(), data, 64, 48, true);
        helper.assertTrue(none.texts.stream().anyMatch(t -> t.text.equals("NO PROGRAM")), "an empty program shows NO PROGRAM");
        PlantApi.set(data, 1, "x", spec("type", "text", "col", 1, "row", 1, "text", "hello"));
        helper.assertTrue(record(data.shown(), data, 64, 48, true).texts.stream().noneMatch(t -> t.text.equals("NO PROGRAM")), "and not once it has a page");
        PlantApi.clear(data);
        helper.assertTrue(record(data.shown(), data, 64, 48, true).texts.stream().anyMatch(t -> t.text.equals("NO PROGRAM")), "clear brings it back");

        PlantComputerData builtin = data();
        builtin.mode = "overview";
        builtin.syncBuiltin(readings(true, true));
        PlantState state = builtin.builtin();
        helper.assertTrue(state.element(0, "f0").valueText().equals("87.4") && state.element(0, "f1").valueText().equals("-23.4")
                        && state.element(0, "f2").valueText().equals("1017") && state.element(0, "f3").valueText().equals("92")
                        && state.element(0, "f4").valueText().equals("0.4"),
                "power %, level in, pressure psig, core flow %, containment psig: " + state.element(0, "f0").valueText() + " "
                        + state.element(0, "f1").valueText() + " " + state.element(0, "f2").valueText() + " " + state.element(0, "f3").valueText()
                        + " " + state.element(0, "f4").valueText());
        helper.assertTrue(state.element(0, "st").text.equals("SCRAM NOT ACTIVE") && state.element(0, "b0").fraction() > 0.7f, "scram line and bars");
        for (PlantElement e : state.all()) {
            helper.assertTrue(Float.isNaN(e.warnHigh) && Float.isNaN(e.alarmHigh) && Float.isNaN(e.warnLow) && Float.isNaN(e.alarmLow),
                    "the built-in pages carry no alarm limits (no judgement): " + e.id);
            helper.assertTrue(e.kind != PlantElement.Kind.TILE, "and no safe / unsafe tiles: " + e.id);
        }
        // no reactor, then an unformed one
        builtin.syncBuiltin(readings(false, false));
        helper.assertTrue(builtin.builtin().element(0, "st").text.equals("NO REACTOR BOUND") && builtin.builtin().element(0, "f0").valueText().equals(PlantFormat.NO_DATA),
                "unbound: no reactor, dashes");
        builtin.syncBuiltin(readings(true, false));
        helper.assertTrue(builtin.builtin().element(0, "st").text.equals("REACTOR NOT FORMED"), "unformed");
        helper.succeed();
    }

    // ---- save and reload

    private static void wall(HardwareStage s, int cols, int rows) {
        var panel = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, HardwareStage.FACING);
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                s.level.setBlock(s.origin.relative(HardwareStage.FACING.getCounterClockWise(), c).below(r), panel, 3);
            }
        }
    }

    @GameTest(template = EMPTY, templateNamespace = "bwrplant", timeoutTicks = 200)
    public static void theProgramSurvivesSaveReloadAndTheNetworkAndADefaultDisplayWritesNothing(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN, ORIGIN, "plant-save", s -> {
            wall(s, 4, 3);
            PanelComponent component = s.place(ComponentTypes.PLANT_COMPUTER, 0, 0, Footprint.of(64, 48));
            PlantComputerData data = (PlantComputerData) component.data();
            helper.assertTrue(data.toTag().isEmpty(), "a new, empty display writes no keys: " + data.toTag());
            PanelBlockEntity owner = s.be();
            // an older save: no plant keys at all
            PlantComputerData old = new PlantComputerData();
            old.load(new CompoundTag());
            helper.assertTrue(old.programMode() && old.textSize == 100 && old.trendSeconds == 5 && old.program().isEmpty(), "an empty tag is the defaults");

            richProgram(data);
            var registries = s.level.registryAccess();
            PanelBlockEntity reloaded = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            reloaded.loadWithComponents(owner.saveWithoutMetadata(registries), registries);
            PlantComputerData copy = (PlantComputerData) reloaded.component(component.id()).data();
            helper.assertTrue(java.util.Arrays.equals(copy.program().encodeFull(), data.program().encodeFull()), "the pages survive a world save");
            helper.assertTrue(copy.program().selected() == 1 && copy.program().element(0, "tr").count == 40, "page shown and trend points too");
            helper.assertTrue(reloaded.component(component.id()).save().equals(component.save()), "and the save is a fixed point");

            // a client that already has the empty display receives the drawing as the panel's full state, then patches
            PanelBlockEntity client = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            client.applyNetworkState(owner.savePanel(true));
            PlantComputerData remote = (PlantComputerData) client.component(component.id()).data();
            helper.assertTrue(java.util.Arrays.equals(remote.program().encodeFull(), data.program().encodeFull()), "the full state carries the pages");
            data.drainPatch();
            PlantApi.value(data, 1, "pw", 12.5);
            PlantApi.push(data, 1, "tr", 1200);
            data.flush();
            byte[] patch = data.drainPatch();
            helper.assertTrue(patch != null && patch.length < 40, "a small edit is a small patch: " + (patch == null ? -1 : patch.length));
            client.applyContent(component.id(), patch);
            helper.assertTrue(java.util.Arrays.equals(remote.program().encodeFull(), data.program().encodeFull()), "applying it converges");

            // damaged program bytes load as an empty program, not an exception or half a page
            CompoundTag damaged = component.save();
            CompoundTag damagedData = damaged.getCompound("Data");
            byte[] bytes = damagedData.getByteArray("Program").clone();
            helper.assertTrue(bytes.length > 100, "the saved pages are in the component's Data: " + bytes.length + " bytes");
            bytes[bytes.length / 2] ^= (byte) 0xFF;
            bytes[3] = (byte) 0x7F;
            damagedData.putByteArray("Program", bytes);
            damaged.put("Data", damagedData);
            PanelComponent broken = PanelComponent.load(damaged);
            PlantComputerData brokenData = (PlantComputerData) broken.data();
            helper.assertTrue(brokenData.program().isEmpty() || brokenData.program().elementCount() <= PlantLimits.MAX_ELEMENTS, "a damaged save loads within the limits");

            // the config round trip: mode, text size and trend step are saved when not the default
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("Mode", "trends");
            config.putInt("TextSize", 70);
            config.putInt("TrendSeconds", 12);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, component.id(), edit), "the wrench edit was applied");
            helper.assertTrue(data.mode.equals("trends") && data.textSize == 70 && data.trendSeconds == 12, "config applied: " + data.toTag());
            CompoundTag saved = component.save();
            PanelComponent again = PanelComponent.load(saved);
            PlantComputerData loaded = (PlantComputerData) again.data();
            helper.assertTrue(loaded.mode.equals("trends") && loaded.textSize == 70 && loaded.trendSeconds == 12
                    && java.util.Arrays.equals(loaded.program().encodeFull(), data.program().encodeFull()), "all of it reloads");
            CompoundTag bad = new CompoundTag();
            bad.putString("Mode", "<script>");
            bad.putInt("TextSize", 9999);
            bad.putInt("TrendSeconds", -4);
            PlantComputerData cleaned = new PlantComputerData();
            cleaned.load(bad);
            helper.assertTrue(cleaned.programMode() && cleaned.textSize == GaugeScale.MAX_PERCENT && cleaned.trendSeconds == 1, "junk is clamped: " + cleaned.mode + " " + cleaned.textSize);
            // and a display shown as a default page keeps its pages and trend history
            CompoundTag defaults = new PlantComputerData().toTag();
            helper.assertTrue(defaults.isEmpty(), "the defaults write nothing");
        });
    }

    // ---- the built-in pages, on a real reactor

    @GameTest(template = EMPTY, templateNamespace = "bwrplant", batch = "plant", timeoutTicks = 400)
    public static void theBuiltinPagesBindToTheReactorAndReadItsSignals(GameTestHelper helper) {
        FcdTestSupport.Run.start(helper, ORIGIN + 8_000, 0, 9, 9, run -> {
            run.wall(4, 3);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent component = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.PLANT_COMPUTER, Footprint.of(64, 48));
            helper.assertTrue(component != null, "a 4 x 3 block plant computer fits the wall");
            PlantComputerData data = (PlantComputerData) component.data();
            helper.assertTrue(data.programMode() && data.shown().isEmpty(), "a new display has no program: the NO PROGRAM screen");
            run.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(BwrItems.PANEL_WRENCH.get()));
            CompoundTag config = new CompoundTag();
            config.putString("Mode", "overview");
            config.putInt("TrendSeconds", 1);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(run.player, block.getBlockPos(), component.id(), edit) && data.mode.equals("overview"),
                    "the wrench chose the overview page");
            helper.assertTrue(data.builtin().element(0, "st") != null && data.builtin().element(0, "st").text.equals("NO REACTOR BOUND"),
                    "unbound, it says so: " + (data.builtin().element(0, "st") == null ? "no status line" : data.builtin().element(0, "st").text));

            // only a reactor can be bound
            BlockPos valve = run.min.offset(-3, 2, 4);
            var msiv = BwrBlocks.MSIV.get();
            var state = msiv.placementState().setValue(dev.bwr.mod.steam.MainSteamIsolationValveBlock.FACING, net.minecraft.core.Direction.NORTH);
            run.level.setBlock(valve, state, 3);
            msiv.setPlacedBy(run.level, valve, state, null, new net.minecraft.world.item.ItemStack(msiv));
            WiringOps.Outcome wrong = run.bindReactor(block, component, valve, "position");
            helper.assertTrue(wrong.result() == WiringOps.Result.REFUSED && wrong.detail() != null
                    && dev.bwr.mod.text.BwrText.english(wrong.detail()).contains("reactor"), "a valve is refused: " + wrong.result());
            helper.assertTrue(run.bindReactor(block, component, run.be.getBlockPos()).result() == WiringOps.Result.OK, "the reactor binds");

            run.then(5, () -> {
                block.sampleLive();
                var signals = dev.bwr.mod.signal.SignalProviders.of(run.be);
                PlantState shown = data.builtin();
                helper.assertTrue(shown.element(0, "st").text.equals("SCRAM NOT ACTIVE") || shown.element(0, "st").text.equals("SCRAM ACTIVE"),
                        "bound and formed: " + shown.element(0, "st").text);
                String[] keys = {"power", "level", "pressure", "core_flow", "containment_pressure"};
                float[] scale = {100, 1, 1, 100, 1};
                int[] decimals = {1, 1, 0, 0, 1};
                int read = 0;
                for (int i = 0; i < keys.length; i++) {
                    var raw = signals.read(keys[i]);
                    String expected = raw.isPresent() ? PlantFormat.number((float) raw.getAsDouble() * scale[i], decimals[i]) : PlantFormat.NO_DATA;
                    read += raw.isPresent() ? 1 : 0;
                    helper.assertTrue(shown.element(0, "f" + i).valueText().equals(expected),
                            keys[i] + " reads " + expected + ", the display shows " + shown.element(0, "f" + i).valueText());
                }
                helper.assertTrue(read >= 4, "the reactor reports its own signals: " + read + " of 5");
                // a client that joins now has the pages in the full state
                PanelBlockEntity client = new PanelBlockEntity(block.getBlockPos(), block.getBlockState());
                client.applyNetworkState(block.savePanel(true));
                PlantComputerData remote = (PlantComputerData) client.component(component.id()).data();
                helper.assertTrue(remote.mode.equals("overview") && java.util.Arrays.equals(remote.builtin().encodeFull(), data.builtin().encodeFull()),
                        "the client shows the same");
            });
            run.then(25, () -> {
                block.sampleLive();
                int points = data.builtin().element(1, "t0").count;
                helper.assertTrue(points >= 1, "a point of the trend per step: " + points);
            });
            run.then(25, () -> {
                block.sampleLive();
                helper.assertTrue(data.builtin().element(1, "t0").count >= 2, "history grows: " + data.builtin().element(1, "t0").count);
                // (the panel's own sync sends the patches to the real clients; the full state is what a late one gets)
                PanelBlockEntity late = new PanelBlockEntity(block.getBlockPos(), block.getBlockState());
                late.applyNetworkState(block.savePanel(true));
                helper.assertTrue(java.util.Arrays.equals(((PlantComputerData) late.component(component.id()).data()).builtin().encodeFull(),
                        data.builtin().encodeFull()), "a client that joins later is in step");
                // soft keys switch the built-in page and do nothing else
                helper.assertTrue(data.showBuiltinPage(2) && data.mode.equals("trends"), "the PLOT key shows the trends");
                helper.assertTrue(data.builtin().selected() == 0, "(the page itself follows on the next sample)");
                block.sampleLive();
                helper.assertTrue(data.builtin().selected() == 1, "and then it does");
                CompoundTag saved = component.save();
                helper.assertTrue(saved.getCompound("Data").contains("Builtin") && saved.getCompound("Data").getString("Mode").equals("trends"),
                        "the built-in pages and the mode are saved");
                PlantComputerData reloaded = (PlantComputerData) PanelComponent.load(saved).data();
                helper.assertTrue(reloaded.builtin().element(1, "t0").count == data.builtin().element(1, "t0").count, "with their trend history");
            });
        });
    }

    // ---- touch

    @GameTest(template = EMPTY, templateNamespace = "bwrplant")
    public static void aTouchedSoftKeyRaisesTheEventOncePerIntervalAndOnlyForAKeyThatIsThere(GameTestHelper helper) {
        PlantComputerType type = (PlantComputerType) ComponentTypes.PLANT_COMPUTER;
        PanelComponent component = new PanelComponent(1, type, 0, 0, Footprint.of(64, 48), "PC-1");
        PlantComputerData data = (PlantComputerData) component.data();
        data.setClock(100);
        PlantApi.set(data, 1, "ka", spec("type", "key", "key", 1, "text", "NEXT"));
        PlantApi.set(data, 1, "kc", spec("type", "key", "key", 3, "text", "MENU"));
        PlantApi.set(data, 2, "kb", spec("type", "key", "key", 2, "text", "HOME"));
        PlantLayout layout = PlantLayout.of(64, 48, 100);
        List<int[]> events = new ArrayList<>();
        PlantTouch.Listener listener = (level, owner, c, page, key) -> events.add(new int[] {page, key});
        PlantTouch.addListener(listener);
        try {
            double key1 = type.clickValue(component, layout.left() + 0.5 * layout.cellW(), layout.top() + (layout.rows() - 0.5) * layout.cellH());
            double key2 = type.clickValue(component, layout.left() + (layout.keyStart(2) + 0.5) * layout.cellW(), layout.top() + (layout.rows() - 0.5) * layout.cellH());
            double key3 = type.clickValue(component, layout.left() + (layout.keyStart(3) + 0.5) * layout.cellW(), layout.top() + (layout.rows() - 0.5) * layout.cellH());
            double above = type.clickValue(component, layout.left() + 0.5 * layout.cellW(), layout.top() + (layout.rows() - 1.5) * layout.cellH());
            double outside = type.clickValue(component, -3.0, 5.0);
            helper.assertTrue(outside < 0, "a click off the screen has no cell");
            var context = new ComponentType.OperateContext(null, null, null, component);
            helper.assertTrue(type.operate(context, OperateAction.PRESS, key1), "a press on key 1 is handled");
            helper.assertTrue(events.size() == 1 && events.get(0)[0] == 1 && events.get(0)[1] == 1, "page 1, key 1");
            helper.assertTrue(type.operate(context, OperateAction.PRESS, key1) && events.size() == 1, "a second press the same tick raises nothing more");
            data.setClock(101);
            type.operate(context, OperateAction.PRESS, key1);
            helper.assertTrue(events.size() == 1, "nor one tick later");
            data.setClock(104);
            helper.assertTrue(type.operate(context, OperateAction.PRESS, key3) && events.size() == 2 && events.get(1)[1] == 3, "key 3 after the interval");
            data.setClock(110);
            helper.assertTrue(!type.operate(context, OperateAction.PRESS, key2) && events.size() == 2, "key 2 is not defined on page 1: nothing");
            helper.assertTrue(!type.operate(context, OperateAction.PRESS, above) && !type.operate(context, OperateAction.PRESS, -1.0), "above the key row: nothing");
            helper.assertTrue(!type.operate(context, OperateAction.RELEASE, key1) && events.size() == 2, "a release is not a touch");
            PlantApi.setPage(data, 2);
            data.setClock(120);
            helper.assertTrue(type.operate(context, OperateAction.PRESS, key2) && events.size() == 3 && events.get(2)[0] == 2 && events.get(2)[1] == 2,
                    "the page shown is the event's page");
        } finally {
            PlantTouch.removeListener(listener);
        }
        helper.succeed();
    }

    // ---- no plant actuation

    @GameTest(template = EMPTY, templateNamespace = "bwrplant", batch = "plant", timeoutTicks = 400)
    public static void aPlantComputerCannotActOnThePlant(GameTestHelper helper) {
        FcdTestSupport.Run.start(helper, ORIGIN + 8_000, 1, 9, 9, run -> {
            run.wall(4, 3);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent component = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.PLANT_COMPUTER, Footprint.of(64, 48));
            helper.assertTrue(component != null, "placed");
            helper.assertTrue(run.bindReactor(block, component, run.be.getBlockPos()).result() == WiringOps.Result.OK, "bound to the reactor");
            PlantComputerData data = (PlantComputerData) component.data();
            AtomicInteger outputs = new AtomicInteger();
            PanelBus.addOutputListener((level, owner, c, index, value) -> outputs.incrementAndGet());

            boolean scram = run.be.core().isScramActive();
            int notch = run.be.rods().getNotchLabel(0);
            int demand = run.be.rods().getCommandedNotchLabel(0);
            double flowDemand = run.be.core().getCoreFlowFraction();

            // structurally: no outputs, no sinks, no redstone
            helper.assertTrue(component.outputCount() == 0 && component.type().outputPorts(data).isEmpty(), "the component has no outputs");
            helper.assertTrue(block.redstonePower() == 0, "and no redstone");

            // everything a program can do, and every touch
            richProgram(data);
            PlantApi.setPage(data, 1);
            data.setClock(0);
            var context = new ComponentType.OperateContext(run.level, run.player, block, component);
            PlantLayout layout = data.layout();
            for (int key = 1; key <= PlantLimits.MAX_KEYS; key++) {
                data.setClock(100L * key);
                double click = PlantComputerType.class.cast(component.type()).clickValue(component,
                        layout.left() + (layout.keyStart(key) + 0.5) * layout.cellW(), layout.top() + (layout.rows() - 0.5) * layout.cellH());
                component.type().operate(context, OperateAction.PRESS, click);
            }
            for (String mode : new String[] {"overview", "trends", "flows"}) {
                data.mode = mode;
                data.sampleContent(run.level, block, component);
                for (int key = 1; key <= 3; key++) {
                    data.setClock(1_000L + 100L * key);
                    component.type().operate(context, OperateAction.PRESS, component.type().clickValue(component,
                            layout.left() + (layout.keyStart(key) + 0.5) * layout.cellW(), layout.top() + (layout.rows() - 0.5) * layout.cellH()));
                }
            }
            block.sampleLive();
            helper.assertTrue(outputs.get() == 0, "no panel output was ever set: " + outputs.get());
            helper.assertTrue(run.be.core().isScramActive() == scram && run.be.rods().getNotchLabel(0) == notch
                    && run.be.rods().getCommandedNotchLabel(0) == demand && run.be.core().getCoreFlowFraction() == flowDemand,
                    "the reactor's scram, rod demand and flow are as they were");
            helper.assertTrue(block.redstonePower() == 0, "still no redstone");

            // and the code: nothing in the plant computer's classes can reach an output, a sink, a command or a redstone line
            String[] banned = {"command", "setOutput", "PanelBus", "DeviceCommands", "RedstoneSink", "PlantSink", "storeOutput"};
            String[] classes = {"panel/component/PlantComputerData", "panel/component/PlantComputerType", "panel/plant/PlantApi", "panel/plant/PlantBuiltin",
                    "panel/plant/PlantState", "panel/plant/PlantElement", "panel/plant/PlantScreen", "panel/plant/PlantLayout", "panel/plant/PlantTouch",
                    "panel/plant/PlantColors", "panel/plant/PlantFormat", "panel/plant/PlantLimits", "panel/component/PlantComputerData$ReactorReadings"};
            for (String name : classes) {
                try (InputStream in = PlantComputerTests.class.getResourceAsStream("/dev/bwr/mod/" + name + ".class")) {
                    helper.assertTrue(in != null, name + " is on the class path");
                    String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                    for (String word : banned) {
                        // PlantComputerType.operate may name the input listener seam but not the output one
                        helper.assertTrue(!bytes.contains(word), name + " mentions " + word);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    // ---- CC

    @GameTest(template = EMPTY, templateNamespace = "bwrplant", timeoutTicks = 200)
    public static void thePeripheralCallsValidateBoundAndRaiseTheTouchEvent(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        HardwareStage.run(helper, ORIGIN + 4_000, ORIGIN, "plant-cc", s -> {
            wall(s, 4, 3);
            PanelComponent component = s.place(ComponentTypes.PLANT_COMPUTER, 0, 0, Footprint.of(64, 48));
            PlantComputerCcChecks.run(helper, s.level, s.be(), component);
        });
    }
}
