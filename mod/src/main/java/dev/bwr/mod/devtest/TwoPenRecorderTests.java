package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.component.StripChartData;
import dev.bwr.mod.panel.component.TwoPenRecorderData;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The two pen strip recorder ({@code bwr:two_pen_recorder}): two inputs recorded on change into the strip chart's history,
 * each pen on its own scale, the config round trip, the history across a save and reload, that the older strip chart is
 * unchanged, and that its header text scales with it.
 *
 * <p>World fixture at 181_800 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class TwoPenRecorderTests {
    private TwoPenRecorderTests() {
    }

    static final int ORIGIN_X = 181_800;
    static final int ORIGIN_Z = 181_000;

    @GameTest(template = "empty")
    public static void recorderIsRegisteredWithTwoPenInputsRedAndGreen(GameTestHelper helper) {
        ComponentType type = ComponentTypes.TWO_PEN_RECORDER;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("two_pen_recorder"), "registered as two_pen_recorder");
        helper.assertTrue(BwrItems.TWO_PEN_RECORDER.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> in = type.inputPorts(type.createData()).stream().map(PortSpec::key).toList();
        helper.assertTrue(in.equals(List.of("pen1", "pen2")) && type.outputPorts(type.createData()).isEmpty(), "inputs pen1 and pen2, no outputs: " + in);
        TwoPenRecorderData data = (TwoPenRecorderData) type.createData();
        helper.assertTrue(data.pens == 2 && data.penColor[0] == 0xD02020 && data.penColor[1] == 0x108010, "two pens, red and green");
        List<Footprint> sizes = type.footprints();
        helper.assertTrue(type.realSizes() == 4 && sizes.get(type.realDefaultIndex()).equals(Footprint.of(6, 6))
                && sizes.contains(Footprint.of(3, 3)) && sizes.contains(Footprint.of(2, 2)),
                "a new item starts at twice the real size, 6 x 6, with the exact 3 x 3 and 2 x 2 listed: " + sizes);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void eachPenRecordsItsOwnInputOnChangeOnly(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "tp-record", s -> {
            PanelComponent rec = s.place(ComponentTypes.TWO_PEN_RECORDER, 0, 0, Footprint.of(3, 3));
            PanelBlockEntity panel = s.be();
            TwoPenRecorderData data = (TwoPenRecorderData) rec.data();
            helper.assertTrue(data.points(0) == 0 && data.points(1) == 0, "no history yet");
            panel.setInput(rec, 0, 40.0);
            helper.assertTrue(data.points(0) == 1 && data.points(1) == 0, "pen 1 recorded, pen 2 untouched");
            panel.setInput(rec, 1, 7.0);
            helper.assertTrue(data.points(0) == 1 && data.points(1) == 1, "pen 2 recorded on its own");
            panel.setInput(rec, 0, 40.0);
            helper.assertTrue(data.points(0) == 1, "an unchanged value adds no point");
            panel.setInput(rec, 0, 55.0);
            helper.assertTrue(data.value(0, data.points(0) - 1) == 55.0 && data.value(1, 0) == 7.0, "values recorded as set");
            // Each pen has its own scale.
            data.min = 0.0;
            data.max = 100.0;
            data.min2 = 0.0;
            data.max2 = 10.0;
            helper.assertTrue(Math.abs(data.fraction(0, 5.0) - 0.05) < 1.0e-9 && Math.abs(data.fraction(1, 5.0) - 0.5) < 1.0e-9
                    && data.fraction(1, 50.0) == 1.0 && data.fraction(1, Double.NaN) == 0.0, "pen 2 reads its own span");
            // A bound source feeds a pen like any input.
            rec.setSource(1, InputSources.constant(3.0));
            panel.resample(rec, true);
            helper.assertTrue(rec.input(1) == 3.0, "pen 2 follows its binding");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void configAndHistoryRoundTripAndAlwaysTwoPens(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "tp-config", s -> {
            PanelComponent rec = s.place(ComponentTypes.TWO_PEN_RECORDER, 0, 0, Footprint.of(3, 3));
            PanelBlockEntity panel = s.be();
            panel.setInput(rec, 0, 12.0);
            panel.setInput(rec, 1, 88.0);
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putInt("Span", 120);
            config.putDouble("Min", -10.0);
            config.putDouble("Max", 50.0);
            config.putDouble("Min2", 100.0);
            config.putDouble("Max2", 900.0);
            config.putInt("Pen2Color", 0x2050D0);
            config.putInt("Pens", 4); // not a field of this type: dropped
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, rec.id(), edit), "the wrench edit was applied");
            TwoPenRecorderData data = (TwoPenRecorderData) rec.data();
            helper.assertTrue(data.spanSeconds == 120 && data.min == -10.0 && data.max == 50.0 && data.min2 == 100.0 && data.max2 == 900.0
                    && data.penColor[1] == 0x2050D0 && data.pens == 2, "config applied, Pens left alone: " + data.toTag());
            helper.assertTrue(rec.inputCount() == 2, "still two inputs");
            // (The edit re-reads the unbound inputs, which have no signal: that is a gap point after the two values.)
            helper.assertTrue(data.points(0) >= 1 && data.points(1) >= 1 && data.value(0, 0) == 12.0f && data.value(1, 0) == 88.0f,
                    "the history survives a config edit: " + data.points(0) + ", " + data.points(1));
            CompoundTag saved = rec.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            TwoPenRecorderData again = (TwoPenRecorderData) reloaded.data();
            helper.assertTrue(reloaded.type() == ComponentTypes.TWO_PEN_RECORDER && again.points(0) == data.points(0) && again.points(1) == data.points(1)
                    && again.value(0, 0) == 12.0f && again.value(1, 0) == 88.0f && again.min2 == 100.0 && again.max2 == 900.0 && reloaded.inputCount() == 2, "reloads with both pens' history and scales");
            // A hand-edited tag with another pen count still gives a two pen recorder.
            CompoundTag bad = rec.save();
            bad.getCompound("Data").putInt("Pens", 4);
            PanelComponent forced = PanelComponent.load(bad);
            helper.assertTrue(((TwoPenRecorderData) forced.data()).pens == 2 && forced.inputCount() == 2, "always two pens");
            // An old tag (an empty one) loads as the defaults.
            TwoPenRecorderData blank = new TwoPenRecorderData();
            blank.load(new CompoundTag());
            helper.assertTrue(blank.pens == 2 && blank.penColor[0] == 0xD02020 && blank.penColor[1] == 0x108010 && blank.max2 == 100.0,
                    "an empty tag loads as the defaults");
        });
    }

    @GameTest(template = "empty")
    public static void theOlderStripChartIsUnchanged(GameTestHelper helper) {
        ComponentType chart = ComponentTypes.STRIP_CHART;
        StripChartData data = (StripChartData) chart.createData();
        helper.assertTrue(data.pens == 1 && data.penColor[1] == 0x2050D0 && chart.footprints().equals(List.of(Footprint.of(16, 16), Footprint.of(24, 16), Footprint.of(32, 24)))
                && chart.inputPorts(data).size() == 1, "the strip chart keeps its pens, colours, sizes and ports");
        CompoundTag old = data.toTag();
        StripChartData loaded = new StripChartData();
        loaded.load(old);
        helper.assertTrue(loaded.toTag().equals(old), "and round trips as before");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void headerTextScalesWithTheCase(GameTestHelper helper) {
        PanelComponent small = new PanelComponent(1, ComponentTypes.TWO_PEN_RECORDER, 0, 0, Footprint.of(3, 3), "TP-1");
        PanelComponent big = new PanelComponent(2, ComponentTypes.TWO_PEN_RECORDER, 0, 0, Footprint.of(24, 24), "TP-2");
        var model = ComponentModels.get(ComponentTypes.TWO_PEN_RECORDER.id());
        List<Legends.Item> a = model.textLayout(small, 1.0f);
        List<Legends.Item> b = model.textLayout(big, 1.0f);
        helper.assertTrue(a.size() == 3 && b.size() == 3, "pen numbers 1 and 2 and the 2-PEN mark");
        for (int i = 0; i < a.size(); i++) {
            float ratio = b.get(i).capCells() / a.get(i).capCells();
            helper.assertTrue(Math.abs(ratio - 8.0f) < 0.3f, "\"" + a.get(i).text() + "\" is eight times as big on the eight times bigger case: " + ratio);
            Legends.Item x = a.get(i);
            helper.assertTrue(x.x() > 0 && x.x() <= small.w() && x.y() - x.capCells() / 2 >= 0 && x.y() + x.capCells() / 2 <= small.h() * 0.2f,
                    "\"" + x.text() + "\" lies in the header of the small recorder: " + x);
        }
        helper.succeed();
    }
}
