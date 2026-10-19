package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.client.BarGaugeFace;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The curved bar gauge (the owner's CAD part bar-gauge.obj, laid out like the owner's simulator picture): the shipped
 * case and channel models agree with the layout {@link BarGaugeFace} places them by, the pointer rides the arc of the
 * face and reads exactly against the scale at every position, everything printed lies on the curved face and leans with
 * it, and the band colours offer Nominal (white), Caution (yellow) and Danger (red) without changing what is saved.
 * All pure: no world.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class CurvedBarGaugeTests {
    private CurvedBarGaugeTests() {
    }

    private static final float[] ASPECTS = {1.0f, 1.41f};

    /** Records quads with their colour and normal. */
    private static final class Recorder implements QuadSink {
        final List<float[]> quads = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3}, rgb, null);
        }

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, float nx, float ny, float nz, boolean emissive) {
            add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3}, rgb, new float[] {nx, ny, nz});
        }

        private void add(float[] quad, int rgb, float[] normal) {
            quads.add(quad);
            colours.add(rgb);
            normals.add(normal);
        }
    }

    private static GaugeData bar(String preset) {
        GaugeData data = (GaugeData) ComponentTypes.BAR_GAUGE.createData();
        data.apply(preset);
        return data;
    }

    /** A component-space point (cells, rows, elevation) as channel model inches {down, elevation}. */
    private static double[] inches(Placement place, float row, float z) {
        return new double[] {(row - place.offsetY) / place.rowScale, z / place.scale};
    }

    /** Distance of a channel-model point from the arc's axis. */
    private static double radius(BarGaugeFace face, double[] p) {
        return Math.hypot(p[0] - face.arcY(), p[1] - face.arcZ());
    }

    // ---- the models

    @GameTest(template = "empty")
    public static void shippedCaseAndChannelModelsMatchTheLayoutTheGamePlacesThemBy(GameTestHelper helper) {
        BakedPanelModel channel = HardwareStage.shipped("bar_gauge");
        BakedPanelModel caseModel = HardwareStage.shipped(BarGaugeFace.CASE_MODEL);
        helper.assertTrue(Math.abs(caseModel.footprintW - BarGaugeFace.CASE_W) < 1.0e-3f
                        && Math.abs(caseModel.footprintH - BarGaugeFace.CASE_FOOTPRINT_H) < 1.0e-3f,
                "the case is the CAD's 5 cm by 20 cm and the tag plate: " + caseModel);
        helper.assertTrue(BarGaugeFace.CASE_W / BarGaugeFace.CASE_FOOTPRINT_H < 0.25f, "slim, as the CAD part and the picture");
        int faces = caseModel.faceTotal() + channel.faceTotal();
        helper.assertTrue(faces >= 200 && faces <= 600, "a few hundred faces in all: " + faces);
        // The white face lies on the CAD face's arc about the axis behind the panel, curved through more than 50
        // degrees, and leaves the channel's slot open.
        float arcY = BarGaugeFace.ARC_Y;
        float arcZ = BarGaugeFace.ARC_Z;
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        double steepest = 0.0;
        int white = 0;
        for (int i = 0; i < caseModel.partCount(); i++) {
            BakedPanelModel.Part part = caseModel.part(i);
            for (int f = 0; f < part.faceCount; f++) {
                if (part.faceColor(f) != 0xF6F5EE) {
                    continue;
                }
                white++;
                for (int c = 0; c < 4; c++) {
                    float x = part.corner(f, c, 0);
                    float y = part.corner(f, c, 1);
                    float z = part.corner(f, c, 2);
                    double r = Math.hypot(y - arcY, z - arcZ);
                    helper.assertTrue(Math.abs(r - BarGaugeFace.FACE_R) < 2.0e-3, "a face corner off the arc: radius " + r);
                    helper.assertTrue(x <= BarGaugeFace.CHANNEL_X + 1.0e-4f || x >= BarGaugeFace.CHANNEL_X + channel.footprintW - 1.0e-4f,
                            "the face leaves the channel's slot open: x " + x);
                    lowest = Math.min(lowest, z);
                    highest = Math.max(highest, z);
                    steepest = Math.max(steepest, Math.toDegrees(Math.asin(Math.abs(arcY - y) / r)));
                }
            }
        }
        helper.assertTrue(white >= 2 * 16, "the face is faceted finely along the arc: " + white + " white quads");
        helper.assertTrue(highest - lowest > 1.8 / 2.54 && steepest > 25.0,
                "the face curves: " + (highest - lowest) * 2.54 + " cm from the middle to the ends, out to " + steepest + " degrees");
        // The channel follows the same arc: its walls' top edges meet the face's edges.
        int onFace = 0;
        for (int i = 0; i < channel.partCount(); i++) {
            BakedPanelModel.Part part = channel.part(i);
            if (part.role == BakedPanelModel.Role.NEEDLE) {
                continue;
            }
            for (int f = 0; f < part.faceCount; f++) {
                for (int c = 0; c < 4; c++) {
                    double r = Math.hypot(part.corner(f, c, 1) + BarGaugeFace.CHANNEL_Y - arcY, part.corner(f, c, 2) - arcZ);
                    helper.assertTrue(r <= BarGaugeFace.FACE_R + 1.0e-3, "the channel is no higher than the face: " + r);
                    onFace += Math.abs(r - BarGaugeFace.FACE_R) < 2.0e-3 ? 1 : 0;
                }
            }
        }
        helper.assertTrue(onFace > 40, "the channel's walls rise to the face's arc: " + onFace);
        helper.succeed();
    }

    // ---- the pointer

    @GameTest(template = "empty")
    public static void pointerRidesTheArcAndReadsExactlyAtEveryPosition(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        BakedPanelModel.Part needle = model.part("needle");
        float[] scratch = new float[PartDraw.SCRATCH];
        for (Footprint footprint : ComponentTypes.BAR_GAUGE.footprints()) {
            for (float aspect : ASPECTS) {
                BarGaugeFace face = new BarGaugeFace(model, bar(GaugeData.PRESET_NARROW), footprint.w(), footprint.h(), aspect);
                String where = footprint + " aspect " + aspect + ": ";
                double tipRadius = -1.0;
                float[] elevation = new float[3];
                for (double fraction : new double[] {0.0, 0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0}) {
                    Recorder recorder = new Recorder();
                    PartDraw.emitTurnedAboutX(recorder, needle, face.place, face.pointerTurn(fraction), face.arcY(), face.arcZ(),
                            -1, 1.0f, false, scratch);
                    // The tip is the pointer's leftmost corner: it reads the scale.
                    float tipX = Float.MAX_VALUE;
                    float tipRow = 0.0f;
                    float tipZ = 0.0f;
                    for (float[] q : recorder.quads) {
                        for (int c = 0; c < 12; c += 3) {
                            if (q[c] < tipX) {
                                tipX = q[c];
                                tipRow = q[c + 1];
                                tipZ = q[c + 2];
                            }
                            helper.assertTrue(q[c] >= face.place.x(0.0f) - 1.0e-3f && q[c] <= face.place.x(model.footprintW) + 1.0e-3f,
                                    where + "the pointer stays in the channel");
                        }
                    }
                    helper.assertTrue(Math.abs(tipRow - face.row(fraction)) < 1.0e-3f,
                            where + "at " + fraction + " the tip is on row " + tipRow + ", the scale at " + face.row(fraction));
                    double r = radius(face, inches(face.place, tipRow, tipZ));
                    if (tipRadius < 0.0) {
                        tipRadius = r;
                    }
                    helper.assertTrue(Math.abs(r - tipRadius) < 1.0e-3, where + "the tip rides the arc: radius " + r + " not " + tipRadius);
                    if (fraction == 0.0 || fraction == 0.5 || fraction == 1.0) {
                        elevation[(int) Math.round(fraction * 2)] = tipZ;
                    }
                }
                helper.assertTrue(tipRadius > BarGaugeFace.FACE_R - 0.12 / 2.54 && tipRadius < BarGaugeFace.FACE_R + 0.05 / 2.54,
                        where + "the tip is in the channel just under the face: " + tipRadius);
                // It turns, it does not slide: half way up the face it stands well proud of where it is at the ends.
                float sag = elevation[1] - Math.max(elevation[0], elevation[2]);
                helper.assertTrue(sag > face.place.z(0.8f / 2.54f), where + "the pointer follows the curve: " + sag + " cells");
                helper.assertTrue(face.pointerTurn(1.0) - face.pointerTurn(0.0) > 45.0f && Math.abs(face.pointerTurn(0.0)) < 0.05f,
                        where + "it turns through the arc from where it is modelled: " + face.pointerTurn(0.0) + ".." + face.pointerTurn(1.0));
            }
        }
        helper.succeed();
    }

    // ---- the printed scale

    @GameTest(template = "empty")
    public static void ticksBandsNumbersAndUnitsLieOnTheCurvedFace(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("bar_gauge");
        for (String preset : new String[] {GaugeData.PRESET_NARROW, GaugeData.PRESET_WIDE}) {
            for (float aspect : ASPECTS) {
                GaugeData data = bar(preset);
                BarGaugeFace face = new BarGaugeFace(model, data, 12, 24, aspect);
                String where = preset + " aspect " + aspect + ": ";
                Recorder recorder = new Recorder();
                face.emitStatic(recorder);
                int bandPieces = 0;
                for (int i = 0; i < recorder.quads.size(); i++) {
                    float[] q = recorder.quads.get(i);
                    float[] n = recorder.normals.get(i);
                    helper.assertTrue(n != null, where + "every mark carries the face's normal");
                    double lift = recorder.colours.get(i) == BarGaugeFace.INK ? face.radius(BarGaugeFace.TICK_LIFT) : face.radius(BarGaugeFace.BAND_LIFT);
                    double[] first = inches(face.place, q[1], q[2]);
                    double[] last = inches(face.place, q[7], q[8]);
                    for (int c = 0; c < 12; c += 3) {
                        double r = radius(face, inches(face.place, q[c + 1], q[c + 2]));
                        helper.assertTrue(Math.abs(r - lift) < 1.0e-3 && r > BarGaugeFace.FACE_R,
                                where + "a mark is on the face, just above it: radius " + r + " for " + lift);
                    }
                    // Its normal points away from the arc's axis at its middle: it lies on the tangent there.
                    double my = (first[0] + last[0]) / 2.0 - face.arcY();
                    double mz = (first[1] + last[1]) / 2.0 - face.arcZ();
                    double length = Math.hypot(my, mz);
                    double dot = n[1] * my / length + n[2] * mz / length;
                    helper.assertTrue(Math.abs(n[0]) < 1.0e-6 && dot > 0.9995, where + "a mark lies on the face's tangent: " + dot);
                    if (recorder.colours.get(i) != BarGaugeFace.INK) {
                        bandPieces++;
                        double span = Math.abs(Math.atan2(first[0] - face.arcY(), first[1] - face.arcZ())
                                - Math.atan2(last[0] - face.arcY(), last[1] - face.arcZ()));
                        helper.assertTrue(Math.toDegrees(span) <= BarGaugeFace.MAX_BAND_STEP_DEG + 1.0e-3,
                                where + "a band piece spans " + Math.toDegrees(span) + " degrees");
                    }
                }
                // The wide range red band runs over most of the scale: many pieces, so it follows the curve.
                helper.assertTrue(bandPieces >= (preset.equals(GaugeData.PRESET_WIDE) ? 15 : 8), where + "band pieces: " + bandPieces);
                // Ticks at the scale positions: a major tick's middle on the row of its value.
                double step = face.majorStep();
                for (double value = data.min; value <= data.max + 1.0e-9; value += step) {
                    float want = face.row(data.fraction(value));
                    boolean found = false;
                    for (int i = 0; i < recorder.quads.size(); i++) {
                        float[] q = recorder.quads.get(i);
                        if (recorder.colours.get(i) == BarGaugeFace.INK && Math.abs((q[1] + q[7]) / 2.0f - want) < 1.0e-3f
                                && q[0] <= face.casePlace.x(BarGaugeFace.TICK_LEFT) + 1.0e-3f) {
                            found = true;
                        }
                    }
                    helper.assertTrue(found, where + "a major tick at " + value + " on row " + want);
                }
                // Numbers and unit letters: on the face at their rows, leaning with it (back above the crown, forward below).
                double textRadius = face.radius(BarGaugeFace.TEXT_LIFT);
                int above = 0;
                int below = 0;
                for (Legends.Item item : face.legends("BG-1")) {
                    if (item.text().equals("BG-1")) {
                        helper.assertTrue(item.tilt() == 0.0f, where + "the tag plate is flat");
                        continue;
                    }
                    double[] p = inches(face.place, item.y(), item.z());
                    helper.assertTrue(Math.abs(radius(face, p) - textRadius) < 1.0e-3, where + item.text() + " is on the face");
                    double angle = Math.toDegrees(Math.atan2(face.arcY() - p[0], p[1] - face.arcZ()));
                    helper.assertTrue(Math.abs(item.tilt() - angle) < 0.01, where + item.text() + " leans " + item.tilt() + " on a face at " + angle);
                    above += item.tilt() > 5.0f ? 1 : 0;
                    below += item.tilt() < -5.0f ? 1 : 0;
                    if (item.align() == BakedPanelModel.Align.RIGHT) {
                        double value = Double.parseDouble(item.text());
                        helper.assertTrue(Math.abs(item.y() - face.row(data.fraction(value))) < 1.0e-4f, where + "number " + item.text() + " on its row");
                    }
                }
                helper.assertTrue(above >= 2 && below >= 2, where + "text leans both ways round the crown: " + above + " up, " + below + " down");
            }
        }
        helper.succeed();
    }

    // ---- the band colours

    @GameTest(template = "empty")
    public static void bandColoursOfferNominalCautionAndDangerAndSaveAsBefore(GameTestHelper helper) {
        List<ConfigField> schema = ComponentTypes.BAR_GAUGE.configSchema();
        int colourFields = 0;
        for (ConfigField field : schema) {
            if (!field.key().matches("Band[1-4]Color")) {
                continue;
            }
            colourFields++;
            helper.assertTrue(field.kind() == ConfigField.Kind.COLOR
                    && field.swatchNames().equals(List.of(GaugeData.NOMINAL, GaugeData.CAUTION, GaugeData.DANGER)),
                    field.key() + " offers nominal, caution and danger: " + field.options());
            helper.assertTrue(field.swatchColor(GaugeData.NOMINAL) == GaugeData.BAND_WHITE
                    && field.swatchColor(GaugeData.CAUTION) == GaugeData.BAND_YELLOW
                    && field.swatchColor(GaugeData.DANGER) == GaugeData.BAND_RED, field.key() + ": white, yellow and red");
            helper.assertTrue(GaugeData.DANGER.equals(field.swatchName(GaugeData.BAND_RED)) && field.swatchName(0x123456) == null
                    && field.swatchColor("bogus") == -1, field.key() + ": any other colour is no name, and stays allowed");
        }
        helper.assertTrue(colourFields == 4, "four band colours: " + colourFields);
        // The other gauges' colour fields are exactly as they were: a colour code and nothing more.
        for (String type : new String[] {"dial_gauge", "edgewise_gauge_vertical", "edgewise_gauge_horizontal"}) {
            for (ConfigField field : GaugeData.schema(type)) {
                helper.assertTrue(field.kind() != ConfigField.Kind.COLOR || field.options().isEmpty(), type + " " + field.key() + " has no names");
            }
        }
        // Picking a name sends its colour code, which saves under the same key as ever: a fresh narrow gauge's keys, no more.
        GaugeData data = bar(GaugeData.PRESET_NARROW);
        CompoundTag before = data.toTag();
        helper.assertTrue(before.getInt("Band1Color") == GaugeData.BAND_RED && before.getInt("Band2Color") == GaugeData.BAND_YELLOW,
                "the narrow preset's bands are danger red and caution yellow: " + before);
        CompoundTag incoming = new CompoundTag();
        incoming.putInt("Band2Color", ConfigField.color("Band2Color", "x", GaugeData.BAND_SWATCHES).swatchColor(GaugeData.NOMINAL));
        data.load(dev.bwr.mod.panel.component.ConfigSchema.merge(schema, before, incoming));
        CompoundTag after = data.toTag();
        helper.assertTrue(data.bandColor[1] == GaugeData.BAND_WHITE && after.getAllKeys().equals(before.getAllKeys()),
                "a nominal band saves white under the old key: " + after);
        // ... and is drawn white on the face, from 10 to 20.
        BarGaugeFace face = new BarGaugeFace(HardwareStage.shipped("bar_gauge"), data, 12, 24, 1.0f);
        Recorder recorder = new Recorder();
        face.emitStatic(recorder);
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (int i = 0; i < recorder.quads.size(); i++) {
            if (recorder.colours.get(i) == GaugeData.BAND_WHITE) {
                float[] q = recorder.quads.get(i);
                top = Math.min(top, Math.min(q[1], q[7]));
                bottom = Math.max(bottom, Math.max(q[1], q[7]));
            }
        }
        helper.assertTrue(Math.abs(bottom - face.row(10.0 / 60.0)) < 1.0e-4f && Math.abs(top - face.row(20.0 / 60.0)) < 1.0e-4f,
                "the white band runs from 10 to 20: rows " + top + ".." + bottom);
        // The names the wrench shows.
        String lang;
        try (InputStream in = CurvedBarGaugeTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new AssertionError("en_us.json missing", e);
        }
        var names = com.google.gson.JsonParser.parseString(lang).getAsJsonObject();
        String[][] want = {{GaugeData.NOMINAL, "white"}, {GaugeData.CAUTION, "yellow"}, {GaugeData.DANGER, "red"},
                {ConfigField.SWATCH_CUSTOM, "colour"}};
        for (String[] pair : want) {
            String key = "component.bwr.swatch." + pair[0];
            helper.assertTrue(names.has(key) && names.get(key).getAsString().toLowerCase(java.util.Locale.ROOT).contains(pair[1]),
                    "the wrench names " + key + " (" + pair[1] + ")");
        }
        helper.succeed();
    }
}
