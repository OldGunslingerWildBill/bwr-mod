package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.client.BandOverlay;
import dev.bwr.mod.panel.client.BarGaugeFace;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Poses;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The bar gauge (NEW-HARDWARE-SPEC.md, part 1): its data (range, units, four bands, the two scale presets), the
 * printed scale and bands that are drawn around the owner's model, the pointer against that scale at the minimum, the
 * middle and the maximum, and the colour bands that the baked GE meters now draw.
 *
 * <p>The world fixture is at 95_900 / 95_900 (see {@link HardwareStage}); the rest is pure.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class BarGaugeTests {
    private BarGaugeTests() {
    }

    static final int ORIGIN_X = 95_900;
    static final int ORIGIN_Z = 95_900;

    /** Records the quads a component model sends to a sink. */
    private static final class Recorder implements QuadSink {
        final List<float[]> quads = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            quads.add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
            colours.add(rgb);
        }

        /** Quads of one colour. */
        List<float[]> of(int rgb) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < quads.size(); i++) {
                if (colours.get(i) == rgb) {
                    out.add(quads.get(i));
                }
            }
            return out;
        }

        static float minY(float[] q) {
            return Math.min(Math.min(q[1], q[4]), Math.min(q[7], q[10]));
        }

        static float maxY(float[] q) {
            return Math.max(Math.max(q[1], q[4]), Math.max(q[7], q[10]));
        }

        /**
         * Unbroken runs of quads along the scale, as {min row, max row}, the lowest on the scale (largest rows) first: a
         * band on the curved face is several quads end to end, and two bands of one colour are separate runs.
         */
        static List<float[]> runs(List<float[]> quads) {
            List<float[]> spans = new ArrayList<>();
            for (float[] q : quads) {
                spans.add(new float[] {minY(q), maxY(q)});
            }
            spans.sort((a, b) -> Float.compare(b[1], a[1]));
            List<float[]> out = new ArrayList<>();
            for (float[] span : spans) {
                float[] last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && span[1] >= last[0] - 1.0e-4f) {
                    last[0] = Math.min(last[0], span[0]);
                } else {
                    out.add(span.clone());
                }
            }
            return out;
        }
    }

    private static GaugeData bar() {
        return (GaugeData) ComponentTypes.BAR_GAUGE.createData();
    }

    // ---- data

    @GameTest(template = "empty")
    public static void barGaugeBandsUnitsAndPresetsRoundTrip(GameTestHelper helper) {
        GaugeData fresh = bar();
        helper.assertTrue(fresh.min == 0 && fresh.max == 60 && fresh.units.equals("INCHES") && fresh.preset.equals("narrow"),
                "a new bar gauge is the narrow range scale: " + fresh.toTag());
        double[][] want = {{0, 10}, {10, 20}, {45, 55}, {55, 60}};
        int[] colours = {GaugeData.BAND_RED, GaugeData.BAND_YELLOW, GaugeData.BAND_YELLOW, GaugeData.BAND_RED};
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(fresh.bandLow[i] == want[i][0] && fresh.bandHigh[i] == want[i][1] && fresh.bandColor[i] == colours[i],
                    "narrow band " + (i + 1));
        }
        CompoundTag tag = fresh.toTag();
        helper.assertTrue(tag.contains("Band4Low") && tag.contains("Band4High") && tag.contains("Band4Color"), "the fourth band is saved");
        helper.assertTrue(!tag.contains("Style") && !tag.contains("Variant"), "one fixed look: no style or variant is saved");
        GaugeData again = bar();
        again.load(tag);
        helper.assertTrue(again.toTag().equals(tag), "load(save()) is the identity for the narrow preset");

        GaugeData wide = bar();
        wide.apply(GaugeData.PRESET_WIDE);
        helper.assertTrue(wide.min == -150 && wide.max == 60 && wide.bandLow[0] == -150 && wide.bandHigh[0] == 13
                && wide.bandLow[1] == 55 && wide.bandHigh[1] == 60 && wide.bandColor[0] == GaugeData.BAND_RED
                && wide.bandHigh[2] <= wide.bandLow[2] && wide.bandHigh[3] <= wide.bandLow[3], "wide preset: " + wide.toTag());
        CompoundTag wideTag = wide.toTag();
        GaugeData loaded = bar();
        loaded.load(wideTag);
        helper.assertTrue(loaded.toTag().equals(wideTag) && loaded.preset.equals("wide"), "the wide preset survives a reload");

        // A custom scale with the longest units text, a free band colour and every field off its preset.
        GaugeData custom = bar();
        custom.preset = GaugeData.PRESET_CUSTOM;
        custom.min = 0;
        custom.max = 12;
        custom.units = "PSIG x 100";
        custom.bandLow[3] = 11;
        custom.bandHigh[3] = 12;
        custom.bandColor[3] = 0x123456;
        CompoundTag customTag = custom.toTag();
        helper.assertTrue(!customTag.contains("Preset"), "a custom scale saves no preset (saved only when non-default)");
        GaugeData customLoaded = bar();
        customLoaded.load(customTag);
        helper.assertTrue(customLoaded.units.equals("PSIG x 100") && customLoaded.bandColor[3] == 0x123456
                && customLoaded.bandLow[3] == 11 && customLoaded.preset.equals("custom") && customLoaded.toTag().equals(customTag),
                "custom scale, units and fourth band survive: " + customLoaded.toTag());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oldSavesWithoutTheNewFieldsLoadAndSaveUnchanged(GameTestHelper helper) {
        // The other gauges keep exactly the keys they always saved: no fourth band, no preset.
        for (String type : new String[] {"dial_gauge", "edgewise_gauge_vertical", "edgewise_gauge_horizontal"}) {
            GaugeData data = new GaugeData(type);
            CompoundTag tag = data.toTag();
            helper.assertTrue(!tag.contains("Band4Low") && !tag.contains("Band4High") && !tag.contains("Band4Color")
                    && !tag.contains("Preset"), type + " still saves three bands and no preset: " + tag);
            GaugeData loaded = new GaugeData(type);
            loaded.load(tag);
            helper.assertTrue(loaded.toTag().equals(tag), type + " save is a fixed point of load");
            // units saved before the length limit grew (8) still load whole
            tag.putString("Units", "MPa");
            loaded.load(tag);
            helper.assertTrue(loaded.units.equals("MPa"), type + " keeps short units");
            helper.assertTrue(ComponentTypes.get(dev.bwr.mod.BwrMod.id(type)).configSchema().stream().map(ConfigField::key)
                    .noneMatch(key -> key.startsWith("Band4") || key.equals("Preset")), type + " has no fourth band field");
        }
        // A bar gauge saved by a build that knew nothing of it (a bare tag) loads as a new one: the defaults.
        GaugeData bare = bar();
        bare.load(new CompoundTag());
        helper.assertTrue(bare.min == 0 && bare.max == 100 && bare.units.isEmpty() && bare.preset.equals("custom")
                && bare.bandLow[3] == 0 && bare.bandHigh[3] == 0, "an empty tag loads with the plain defaults: " + bare.toTag());
        CompoundTag saved = bare.toTag();
        GaugeData again = bar();
        again.load(saved);
        helper.assertTrue(again.toTag().equals(saved), "and re-saves unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void presetFieldFillsInRangeUnitsAndBandsButOnlyWhenChanged(GameTestHelper helper) {
        ComponentType type = ComponentTypes.BAR_GAUGE;
        PanelComponent component = new PanelComponent(1, type, 0, 0, Footprint.of(10, 16), "BG-1");
        GaugeData data = (GaugeData) component.data();
        helper.assertTrue(type.configSchema().stream().map(ConfigField::key).toList().contains("Preset"), "the schema has a Preset field");
        CompoundTag incoming = new CompoundTag();
        incoming.putString("Preset", "wide");
        CompoundTag merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        type.adjustConfig(component, merged);
        data.load(merged);
        helper.assertTrue(data.min == -150 && data.max == 60 && data.bandHigh[0] == 13 && data.preset.equals("wide"),
                "choosing wide fills the wide range scale in: " + data.toTag());
        // The same preset again with a hand edit: the edit stands.
        incoming = new CompoundTag();
        incoming.putString("Preset", "wide");
        incoming.putDouble("Min", -100.0);
        merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        type.adjustConfig(component, merged);
        data.load(merged);
        helper.assertTrue(data.min == -100 && data.preset.equals("wide"), "an edit under the same preset is kept: " + data.min);
        // A preset that does not exist is refused by the enum field, and custom changes nothing.
        incoming = new CompoundTag();
        incoming.putString("Preset", "bogus");
        merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        type.adjustConfig(component, merged);
        data.load(merged);
        helper.assertTrue(data.min == -100 && data.preset.equals("wide"), "an unknown preset is ignored");
        incoming = new CompoundTag();
        incoming.putString("Preset", "custom");
        merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        type.adjustConfig(component, merged);
        data.load(merged);
        helper.assertTrue(data.min == -100 && data.preset.equals("custom"), "custom keeps the scale as it is");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void barGaugeIsRegisteredWithItsItemInputAndSizes(GameTestHelper helper) {
        ComponentType type = ComponentTypes.BAR_GAUGE;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("bar_gauge"), "registered as bar_gauge");
        helper.assertTrue(BwrItems.BAR_GAUGE.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        helper.assertTrue(type.inputPorts(type.createData()).size() == 1 && type.outputPorts(type.createData()).isEmpty()
                && type.inputPorts(type.createData()).get(0).key().equals("value"), "one input (value), no outputs");
        helper.assertTrue(!type.isInteractive(), "a display is not operated");
        helper.assertTrue(type.footprints().get(0).h() == PanelGeometry.GRID, "about one block tall at the default size");
        for (Footprint footprint : type.footprints()) {
            helper.assertTrue(footprint.w() < footprint.h(), "a vertical instrument: " + footprint);
        }
        helper.succeed();
    }

    // ---- the printed scale and the pointer

    @GameTest(template = "empty")
    public static void pointerReadsAgainstTheScaleAtMinMidAndMax(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        BakedPanelModel.Part needle = model.part("needle");
        helper.assertTrue(needle != null && needle.hasTravel && needle.axisY < -0.99f, "the needle slides up the channel");
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int f = 0; f < needle.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                low = Math.min(low, needle.corner(f, c, 1));
                high = Math.max(high, needle.corner(f, c, 1));
            }
        }
        float centre = (low + high) / 2.0f;
        for (Footprint footprint : ComponentTypes.BAR_GAUGE.footprints()) {
            for (float aspect : new float[] {1.0f, 1.41f}) {
                GaugeData data = bar();
                BarGaugeFace face = new BarGaugeFace(model, data, footprint.w(), footprint.h(), aspect);
                double[] fractions = {0.0, 0.5, 1.0};
                float previous = Float.MAX_VALUE;
                for (double fraction : fractions) {
                    // Where the model puts the needle's centre when the game slides it to this fraction of its travel.
                    float inches = centre + needle.axisY * needle.travel * Poses.pointerSlide(fraction);
                    float pointerRow = face.place.y(inches);
                    helper.assertTrue(Math.abs(pointerRow - face.row(fraction)) < 1.0e-4f,
                            footprint + " aspect " + aspect + ": pointer at " + fraction + " is on row " + pointerRow + " but the scale is at "
                                    + face.row(fraction));
                    helper.assertTrue(face.row(fraction) < previous, "the scale rises: row of " + fraction);
                    previous = face.row(fraction);
                }
                // The scale and the channel sit inside the component, with the caption zone under them.
                helper.assertTrue(face.row(0.0) > 0 && face.row(0.0) < footprint.h() - 1.5f && face.row(1.0) > 0.5f,
                        footprint + ": scale rows " + face.row(1.0) + ".." + face.row(0.0));
                helper.assertTrue(face.place.offsetX > 1.5f && face.place.offsetX + model.footprintW * face.place.scale < footprint.w(),
                        footprint + ": the channel fits to the right of the scale");
            }
        }
        // No signal and a value outside the range peg the pointer, as the other gauges do.
        GaugeData data = bar();
        helper.assertTrue(data.fraction(Double.NaN) == 0.0 && data.fraction(-5.0) == 0.0 && data.fraction(500.0) == 1.0
                && data.fraction(30.0) == 0.5, "fraction pegs low for no signal and clamps");
        helper.succeed();
    }

    /** A real-size gauge is the big one scaled down: every inch ticked, and hairline ticks, not blobs as thick as the gaps. */
    @GameTest(template = "empty")
    public static void aRealSizeGaugeKeepsThinTicks(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        BarGaugeFace big = new BarGaugeFace(model, bar(), 10, 16, 1.0f);
        helper.assertTrue(big.sizeFactor() == 1.0f, "the 10x16 gauge keeps its cell minimums: " + big.sizeFactor());
        BarGaugeFace small = new BarGaugeFace(model, bar(), 1, 3, 1.0f);
        Recorder recorder = new Recorder();
        small.emitStatic(recorder);
        List<float[]> ticks = recorder.of(BarGaugeFace.INK);
        helper.assertTrue(ticks.size() == 61, "a 1x3 gauge still ticks every inch: " + ticks.size());
        // Its ticks are no fatter for their spacing than the big gauge's: the same picture, smaller.
        Recorder bigTicks = new Recorder();
        big.emitStatic(bigTicks);
        float bigWorst = worstThickness(bigTicks.of(BarGaugeFace.INK), Math.abs(big.row(1.0 / 60.0) - big.row(0.0)));
        float smallWorst = worstThickness(ticks, Math.abs(small.row(1.0 / 60.0) - small.row(0.0)));
        helper.assertTrue(smallWorst <= bigWorst * 1.05f,
                "a 1x3 tick is " + smallWorst + " of its spacing thick, the 10x16 gauge's " + bigWorst);
        helper.succeed();
    }

    /** The thickest tick, as a share of the tick spacing. */
    private static float worstThickness(List<float[]> ticks, float spacing) {
        float worst = 0.0f;
        for (float[] tick : ticks) {
            worst = Math.max(worst, (Recorder.maxY(tick) - Recorder.minY(tick)) / spacing);
        }
        return worst;
    }

    @GameTest(template = "empty")
    public static void scaleTicksBandsAndNumbersMatchTheReferencePictures(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        // Narrow range, 0 to 60 inches: 61 ticks (one per inch), red 0-10 and 55-60, yellow 10-20 and 45-55, numbers every 10.
        GaugeData narrow = bar();
        BarGaugeFace face = new BarGaugeFace(model, narrow, 10, 16, 1.0f);
        Recorder recorder = new Recorder();
        face.emitStatic(recorder);
        helper.assertTrue(recorder.of(BarGaugeFace.INK).size() == 61, "narrow scale ticks: " + recorder.of(BarGaugeFace.INK).size());
        // Each band follows the curved face in several quads: count unbroken runs of a colour along the scale.
        List<float[]> redRuns = Recorder.runs(recorder.of(GaugeData.BAND_RED));
        helper.assertTrue(redRuns.size() == 2 && Recorder.runs(recorder.of(GaugeData.BAND_YELLOW)).size() == 2,
                "narrow bands: 2 red, 2 yellow");
        float[] firstRed = redRuns.get(0);
        helper.assertTrue(Math.abs(firstRed[1] - face.row(0.0)) < 1.0e-4f
                        && Math.abs(firstRed[0] - face.row(10.0 / 60.0)) < 1.0e-4f,
                "the first red band runs from 0 to 10 inches: " + firstRed[0] + ".." + firstRed[1]);
        List<String> numbers = numerals(face);
        helper.assertTrue(numbers.equals(List.of("0", "10", "20", "30", "40", "50", "60")), "narrow numbers: " + numbers);
        helper.assertTrue(units(face).equals("INCHES"), "units one letter per line: " + units(face));

        // Wide range, -150 to +60: numbers every 30, two red bands, 71 ticks.
        GaugeData wide = bar();
        wide.apply(GaugeData.PRESET_WIDE);
        BarGaugeFace wideFace = new BarGaugeFace(model, wide, 12, 24, 1.0f);
        Recorder wideRecorder = new Recorder();
        wideFace.emitStatic(wideRecorder);
        helper.assertTrue(numerals(wideFace).equals(List.of("-150", "-120", "-90", "-60", "-30", "0", "30", "60")),
                "wide numbers: " + numerals(wideFace));
        helper.assertTrue(Recorder.runs(wideRecorder.of(GaugeData.BAND_RED)).size() == 2 && wideRecorder.of(GaugeData.BAND_YELLOW).isEmpty(),
                "wide bands: two red");
        helper.assertTrue(wideRecorder.of(BarGaugeFace.INK).size() == 71, "wide scale ticks: " + wideRecorder.of(BarGaugeFace.INK).size());
        // The nice steps for other ranges, including a 0 to 12 pressure scale numbered every 2 as in the reference picture.
        helper.assertTrue(BarGaugeFace.majorStep(0, 12) == 2.0 && BarGaugeFace.majorStep(0, 100) == 20.0
                && BarGaugeFace.majorStep(0, 1) == 0.2 && BarGaugeFace.majorStep(-150, 60) == 30.0
                && BarGaugeFace.majorStep(0, 60) == 10.0 && Math.abs(BarGaugeFace.majorStep(0, 500) - 100.0) < 1.0e-9,
                "nice steps");
        // A mis-set range cannot break the scale.
        GaugeData broken = bar();
        broken.min = 5;
        broken.max = 5;
        new BarGaugeFace(model, broken, 10, 16, 1.0f).emitStatic(new Recorder());
        helper.succeed();
    }

    /**
     * The look of the owner's simulator (references/gauges/owner-bar-gauge-target-look.png): the numbers on the left,
     * the ticks and bands right of them up against a narrow black channel a little right of the middle, the grey
     * pointer inside the channel pointing left at the scale, the units right of the channel, and the label on a tag
     * plate above the case. (The first version had it the other way round: a wide curved channel filling the face, the
     * pointer on its left edge pointing right, and the label under the gauge.)
     */
    @GameTest(template = "empty")
    public static void barGaugeIsLaidOutLikeTheOwnersSimulator(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        BakedPanelModel.Part needle = model.part("needle");
        float tipX = Float.MAX_VALUE;
        float tipY = 0.0f;
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (int f = 0; f < needle.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                float x = needle.corner(f, c, 0);
                float y = needle.corner(f, c, 1);
                if (x < tipX) {
                    tipX = x;
                    tipY = y;
                }
                right = Math.max(right, x);
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
        }
        helper.assertTrue(tipX >= -1.0e-4f && right <= model.footprintW + 1.0e-4f, "the pointer lies inside the channel: x " + tipX + ".." + right);
        helper.assertTrue(Math.abs(tipY - (low + high) / 2.0f) < 0.02f && right - tipX > 0.5f * model.footprintW,
                "the pointer's tip is its leftmost corner, on its middle row: it points left at the scale");
        for (Footprint footprint : ComponentTypes.BAR_GAUGE.footprints()) {
            for (float aspect : new float[] {1.0f, 1.41f}) {
                GaugeData data = bar();
                data.apply(GaugeData.PRESET_WIDE);
                BarGaugeFace face = new BarGaugeFace(model, data, footprint.w(), footprint.h(), aspect);
                String where = footprint + " aspect " + aspect + ": ";
                float channelLeft = face.place.x(0.0f);
                float channelRight = face.place.x(model.footprintW);
                float middle = (channelLeft + channelRight) / 2.0f;
                helper.assertTrue(channelRight - channelLeft <= 0.2f * footprint.w(), where + "a narrow channel: " + (channelRight - channelLeft));
                helper.assertTrue(middle > 0.5f * footprint.w() && middle < 0.7f * footprint.w(), where + "a little right of the middle: " + middle);
                Recorder recorder = new Recorder();
                face.emitStatic(recorder);
                float scaleLeft = Float.MAX_VALUE;
                for (int colour : new int[] {BarGaugeFace.INK, GaugeData.BAND_RED}) {
                    for (float[] q : recorder.of(colour)) {
                        float minX = Math.min(Math.min(q[0], q[3]), Math.min(q[6], q[9]));
                        float maxX = Math.max(Math.max(q[0], q[3]), Math.max(q[6], q[9]));
                        helper.assertTrue(maxX <= channelLeft + 1.0e-3f, where + "ticks and bands end at the channel's left edge: " + maxX);
                        scaleLeft = Math.min(scaleLeft, minX);
                    }
                }
                for (Legends.Item item : face.legends("BG-1")) {
                    if (item.align() == BakedPanelModel.Align.RIGHT) {
                        helper.assertTrue(item.x() <= scaleLeft, where + "number " + item.text() + " is left of the ticks");
                    } else if (item.text().length() == 1) {
                        helper.assertTrue(item.x() > channelRight, where + "unit letter " + item.text() + " is right of the channel");
                    } else {
                        helper.assertTrue(item.text().equals("BG-1") && item.y() < face.row(1.0) && item.y() > 0.0f,
                                where + "the label is on the tag plate above the gauge: row " + item.y());
                    }
                }
            }
        }
        helper.succeed();
    }

    private static List<String> numerals(BarGaugeFace face) {
        List<String> out = new ArrayList<>();
        for (Legends.Item item : face.legends("BG-1")) {
            if (item.align() == BakedPanelModel.Align.RIGHT) {
                out.add(item.text());
            }
        }
        return out;
    }

    private static String units(BarGaugeFace face) {
        StringBuilder out = new StringBuilder();
        for (Legends.Item item : face.legends("BG-1")) {
            if (item.align() == BakedPanelModel.Align.CENTER && item.text().length() == 1) {
                out.append(item.text());
            }
        }
        return out.toString();
    }

    // ---- colour bands on the baked GE meters

    @GameTest(template = "empty")
    public static void bakedGeMetersDrawTheirColourBands(GameTestHelper helper) {
        // Edgewise: a strip between the numerals and the pointer, between the scale's ends.
        for (String id : new String[] {"ge_180_vertical", "ge_180_horizontal"}) {
            BakedPanelModel model = HardwareStage.shipped(id);
            GaugeData data = new GaugeData(id.endsWith("vertical") ? "edgewise_gauge_vertical" : "edgewise_gauge_horizontal");
            data.min = 0;
            data.max = 100;
            Placement place = Placement.fit(model, 8, 24);
            Recorder recorder = new Recorder();
            BandOverlay overlay = BandOverlay.of(model, place, data, false);
            helper.assertTrue(overlay != null, id + ": the default bands (80-90 amber, 90-100 red) are drawn");
            overlay.emit(recorder);
            helper.assertTrue(recorder.quads.size() == 2 && recorder.colours.get(0) == 0xFFB000 && recorder.colours.get(1) == 0xE02020,
                    id + ": two bands in their own colours: " + recorder.colours);
            // The red band ends at the scale's top end (the last numeral) and starts where the amber one ends.
            float[] amber = recorder.quads.get(0);
            float[] red = recorder.quads.get(1);
            boolean vertical = id.endsWith("vertical");
            float amberLow = vertical ? Recorder.maxY(amber) : Math.min(Math.min(amber[0], amber[3]), Math.min(amber[6], amber[9]));
            float amberHigh = vertical ? Recorder.minY(amber) : Math.max(Math.max(amber[0], amber[3]), Math.max(amber[6], amber[9]));
            float redLow = vertical ? Recorder.maxY(red) : Math.min(Math.min(red[0], red[3]), Math.min(red[6], red[9]));
            helper.assertTrue(Math.abs(amberHigh - redLow) < 1.0e-4f && Math.abs(amberLow - amberHigh) > 0.2f,
                    id + ": 80-90 then 90-100 are neighbours: " + amberLow + " " + amberHigh + " " + redLow);
            // No band in use: nothing to draw.
            for (int i = 0; i < GaugeData.BANDS; i++) {
                data.bandLow[i] = 0;
                data.bandHigh[i] = 0;
            }
            helper.assertTrue(BandOverlay.of(model, place, data, false) == null, id + ": unused bands cost nothing");
        }
        // Dial: an arc of quads over the tick ring.
        BakedPanelModel dial = HardwareStage.shipped("ge_ab40");
        GaugeData data = new GaugeData("dial_gauge");
        data.min = 0;
        data.max = 100;
        Placement place = Placement.fit(dial, 16, 16);
        Recorder recorder = new Recorder();
        BandOverlay overlay = BandOverlay.of(dial, place, data, true);
        helper.assertTrue(overlay != null, "dial: bands are drawn");
        overlay.emit(recorder);
        helper.assertTrue(recorder.of(0xFFB000).size() >= 2 && recorder.of(0xE02020).size() >= 2, "dial: amber and red arcs: " + recorder.colours.size());
        for (float[] quad : recorder.quads) {
            for (int i = 0; i < 12; i += 3) {
                helper.assertTrue(quad[i] >= 0 && quad[i] <= 16 && quad[i + 1] >= 0 && quad[i + 1] <= 16, "the arc stays on the dial");
            }
        }
        helper.succeed();
    }

    // ---- in the world

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void barGaugeOnAPanelTakesItsValueConfigAndSurvivesASaveReload(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "bar", s -> {
            PanelComponent gauge = s.place(ComponentTypes.BAR_GAUGE, 2, 0, Footprint.of(10, 16));
            PanelBlockEntity panel = s.be();
            gauge.setSource(0, InputSources.constant(42.0));
            panel.resample(gauge, true);
            helper.assertTrue(gauge.input(0) == 42.0, "the pointer input follows its source: " + gauge.input(0));
            // Wrench: choose the wide range preset.
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("Preset", "wide");
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, gauge.id(), edit), "the wrench edit was applied");
            GaugeData data = (GaugeData) gauge.data();
            helper.assertTrue(data.min == -150 && data.max == 60 && data.preset.equals("wide"), "preset applied through the wrench: " + data.toTag());
            helper.assertTrue(gauge.input(0) == 42.0, "the wiring is untouched by a scale change");
            // Save and reload through the component's own tag, as a world save does.
            CompoundTag saved = gauge.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            helper.assertTrue(reloaded.type() == ComponentTypes.BAR_GAUGE && reloaded.data().toTag().equals(data.toTag())
                    && reloaded.save().equals(saved), "the bar gauge reloads and re-saves unchanged");
            helper.assertTrue(reloaded.w() == 10 && reloaded.h() == 16, "its footprint is kept");
        });
    }
}
