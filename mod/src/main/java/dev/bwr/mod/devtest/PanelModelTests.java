package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.MeshBuilder;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.client.StaticMesh;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.KeySwitchData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Detents;
import dev.bwr.mod.panel.model.FlagState;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PanelModelSet;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Poses;
import dev.bwr.mod.panel.model.Recolour;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Baked panel component models: the loader (a valid file loads; malformed, out-of-bounds and hostile files are
 * rejected without a crash and without disturbing the rest), the fit of a model's footprint into a component's
 * cells, every state-to-pose transform (handle angle per position, selector detents, key, needle angle and
 * pointer travel at min, mid and max, cap travel, target flag colour), the legends drawn at the label anchors,
 * the arbitrary-normal quad path, and the style / variant config (table, save and reload, wrench edits,
 * fallback to classic when a model is missing) together with the control switch's last-operation latch.
 *
 * <p>Models come from {@link PanelModelFixtures}, written in the exporter's exact format, so these tests do not
 * depend on the real models. Most checks are pure; the ones that need a panel block entity build at their own
 * far-away fixture (origin 54_900 / 54_900 plus {@code slot * 64} in X), force-load its chunks, empty the volume
 * first (a persistent world can run this twice) and release the chunks in every outcome.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelModelTests {
    private PanelModelTests() {
    }

    // ---- helpers

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /** Records every quad a sink receives, with its bounds. */
    private static final class Recorder implements QuadSink {
        final List<float[]> quads = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<Boolean> emissive = new ArrayList<>();
        final List<Integer> enumNormals = new ArrayList<>();
        int glass;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;

        private void take(float[] corners) {
            quads.add(corners);
            for (int i = 0; i < 12; i += 3) {
                minX = Math.min(minX, corners[i]);
                maxX = Math.max(maxX, corners[i]);
                minY = Math.min(minY, corners[i + 1]);
                maxY = Math.max(maxY, corners[i + 1]);
                minZ = Math.min(minZ, corners[i + 2]);
                maxZ = Math.max(maxZ, corners[i + 2]);
            }
        }

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            take(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
            normals.add(null);
            enumNormals.add(normal);
            colors.add(rgb);
            this.emissive.add(emissive);
        }

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, float nx, float ny, float nz, boolean emissive) {
            take(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
            normals.add(new float[] {nx, ny, nz});
            enumNormals.add(-1);
            colors.add(rgb);
            this.emissive.add(emissive);
        }

        @Override
        public void glassQuad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, int rgb, int alpha, float nx, float ny, float nz) {
            glass++;
        }

        /** Bearing (clockwise from up) of the mean of the corners in the outer tenth of the reach from (px, py). */
        float outerBearing(float px, float py) {
            float best = 0;
            for (float[] q : quads) {
                for (int i = 0; i < 12; i += 3) {
                    best = Math.max(best, (float) Math.hypot(q[i] - px, q[i + 1] - py));
                }
            }
            double sx = 0;
            double sy = 0;
            for (float[] q : quads) {
                for (int i = 0; i < 12; i += 3) {
                    if (Math.hypot(q[i] - px, q[i + 1] - py) >= 0.9 * best) {
                        sx += q[i] - px;
                        sy += q[i + 1] - py;
                    }
                }
            }
            return (float) Math.toDegrees(Math.atan2(sx, -sy));
        }

        /** The corner of the topmost-on-screen (smallest y) or lowest quad edge, for travel checks. */
        float extremeY(boolean smallest) {
            return smallest ? minY : maxY;
        }
    }

    private static BakedPanelModel parse(String id, String json) {
        return PanelModelParser.parse(id, json);
    }

    private static Recorder draw(BakedPanelModel.Part part, Placement place, float angle, float slide) {
        Recorder recorder = new Recorder();
        PartDraw.emit(recorder, part, place, angle, slide, -1, 1.0f, false, new float[PartDraw.SCRATCH]);
        return recorder;
    }

    private static PanelComponent component(ComponentType type, int w, int h, String label) {
        return new PanelComponent(1, type, 0, 0, Footprint.of(w, h), label);
    }

    // ---- the loader

    @GameTest(template = "empty")
    public static void panelModelLoaderAcceptsValidFixtures(GameTestHelper helper) {
        Map<String, String> texts = PanelModelFixtures.forTable();
        PanelModelSet.Result result = PanelModelSet.loadAll(texts);
        helper.assertTrue(result.problems().isEmpty(), "every fixture loads: " + result.problems());
        helper.assertTrue(result.models().size() == texts.size() && texts.size() >= 14,
                "one model per id: " + result.models().size() + " of " + texts.size());

        BakedPanelModel sw = result.models().get("ge_sb_target_pistol");
        helper.assertTrue(sw != null && near(sw.footprintW, 2.4, 1e-6) && near(sw.footprintH, 3.6, 1e-6), "footprint");
        helper.assertTrue(sw.partCount() == 3 && sw.part("body") != null && sw.part("handle") != null, "parts");
        BakedPanelModel.Part target = sw.part("target");
        helper.assertTrue(target.role == BakedPanelModel.Role.FLAG && !target.moving() && target.faceCount == 1, "flag part");
        BakedPanelModel.Part handle = sw.part("handle");
        helper.assertTrue(handle.hasPivot && near(handle.pivotX, 1.2, 1e-5) && near(handle.pivotY, 1.5, 1e-5)
                && near(handle.axisX, 0, 1e-6) && near(handle.axisY, 0, 1e-6) && near(handle.axisZ, 1, 1e-6), "pivot and axis");
        helper.assertTrue(!handle.hasTravel && handle.role == BakedPanelModel.Role.PAINT, "handle rotates, does not slide");
        for (int p = 0; p < sw.partCount(); p++) {
            BakedPanelModel.Part part = sw.part(p);
            for (int f = 0; f < part.faceCount; f++) {
                float length = (float) Math.sqrt(part.normal(f, 0) * part.normal(f, 0) + part.normal(f, 1) * part.normal(f, 1)
                        + part.normal(f, 2) * part.normal(f, 2));
                helper.assertTrue(near(length, 1.0, 1e-4), "unit normal " + part.name + "#" + f);
            }
        }
        BakedPanelModel.Label left = sw.label("left");
        helper.assertTrue(left != null && near(left.rotation, -55, 1e-3) && left.align == BakedPanelModel.Align.CENTER
                && near(left.height, 0.17, 1e-5), "label anchor");
        helper.assertTrue(sw.label("caption") != null && sw.label("right").rotation == 55.0f && sw.label("nothing") == null,
                "labels by id");

        BakedPanelModel button = result.models().get("pb_flush");
        BakedPanelModel.Part cap = button.part("cap");
        helper.assertTrue(cap.hasTravel && near(cap.travel, 0.15, 1e-6) && !cap.hasPivot && near(cap.axisZ, -1, 1e-6)
                && "red".equals(cap.tint), "cap travel and tint");
        helper.assertTrue(result.models().get("pb_illuminated").part("lens").role == BakedPanelModel.Role.LAMP, "lamp role");
        BakedPanelModel dial = result.models().get("ge_ab40");
        helper.assertTrue(dial.part("needle").role == BakedPanelModel.Role.NEEDLE && dial.part("needle").hasPivot
                && dial.part("window").role == BakedPanelModel.Role.GLASS, "dial roles");
        helper.assertTrue(result.models().get("ge_180_vertical").part("needle").hasTravel
                && near(result.models().get("ge_180_vertical").part("needle").axisY, -1, 1e-6), "edgewise slides up");

        // A triangle repeats its last corner so every face is four corners.
        BakedPanelModel.Part knobBody = result.models().get("knob_fluted").part("knob");
        boolean triangle = false;
        for (int f = 0; f < knobBody.faceCount; f++) {
            if (knobBody.corner(f, 2, 0) == knobBody.corner(f, 3, 0) && knobBody.corner(f, 2, 1) == knobBody.corner(f, 3, 1)) {
                triangle = true;
            }
        }
        helper.assertTrue(triangle, "triangles repeat their last corner");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelModelLoaderRejectsMalformedFilesWithoutCrashing(GameTestHelper helper) {
        String good = PanelModelFixtures.light("good_light", false);
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("good_light", good);
        texts.put("not_json", "{ this is not json");
        texts.put("empty_file", "");
        texts.put("top_level_array", "[1,2,3]");
        texts.put("wrong_format", good.replace("\"format\":1", "\"format\":2"));
        texts.put("fractional_format", good.replace("\"format\":1", "\"format\":1.5"));
        texts.put("wrong_units", good.replace("\"units\":\"inch\"", "\"units\":\"mm\""));
        texts.put("no_footprint", good.replace("\"footprint\"", "\"foot\""));
        texts.put("negative_footprint", good.replace("\"w\":1.6000", "\"w\":-1.6"));
        texts.put("no_parts", good.replaceAll("\"parts\":\\[.*\\],\"labels\"", "\"parts\":[],\"labels\""));
        texts.put("unknown_role", good.replace("\"role\":\"lamp\"", "\"role\":\"sparkle\""));
        texts.put("bad_hex", good.replace("\"c\":\"B02828\"", "\"c\":\"GGGGGG\""));
        texts.put("short_hex", good.replace("\"c\":\"B02828\"", "\"c\":\"B028\""));
        texts.put("two_vertices", "{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\","
                + "\"faces\":[{\"v\":[[0,0,0],[1,1,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}]}]}");
        texts.put("five_vertices", "{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\","
                + "\"faces\":[{\"v\":[[0,0,0],[1,0,0],[1,1,0],[0,1,0],[0,0.5,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}]}]}");
        texts.put("string_coordinate", "{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\","
                + "\"faces\":[{\"v\":[[0,0,0],[1,\"x\",0],[1,1,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}]}]}");
        texts.put("infinite_coordinate", "{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\","
                + "\"faces\":[{\"v\":[[0,0,0],[1e999,0,0],[1,1,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}]}]}");
        texts.put("faces_not_array", "{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\",\"faces\":5}]}");
        texts.put("zero_axis", good.replace("\"pivot\":null,\"axis\":null", "\"pivot\":[0.5,0.5,0.1],\"axis\":[0,0,0]"));
        texts.put("bad_label_align", good.replaceAll("\"labels\":\\[.*\\]\\}$", "\"labels\":[{\"id\":\"a\",\"pos\":[0.5,0.5,0.1],\"h\":0.1,\"align\":\"up\",\"rot\":0}]}"));
        texts.put("duplicate_labels", good.replaceAll("\"labels\":\\[.*\\]\\}$", "\"labels\":[{\"id\":\"a\",\"pos\":[0.5,0.5,0.1],\"h\":0.1},"
                + "{\"id\":\"a\",\"pos\":[0.6,0.5,0.1],\"h\":0.1}]}"));
        texts.put("Bad-Id", good);
        texts.put("deeply_nested", "[".repeat(5000) + "]".repeat(5000));
        StringBuilder many = new StringBuilder("{\"format\":1,\"footprint\":{\"w\":1,\"h\":1},\"parts\":[{\"name\":\"a\",\"role\":\"paint\",\"faces\":[");
        for (int i = 0; i < PanelModelParser.MAX_FACES + 1; i++) {
            many.append(i == 0 ? "" : ",").append("{\"v\":[[0,0,0],[1,0,0],[1,1,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}");
        }
        texts.put("too_many_faces", many.append("]}]}").toString());

        PanelModelSet.Result result = PanelModelSet.loadAll(texts);
        helper.assertTrue(result.models().size() == 1 && result.models().containsKey("good_light"),
                "only the good file loads, got " + result.models().keySet());
        helper.assertTrue(result.problems().size() == texts.size() - 1,
                "every bad file is reported exactly once: " + result.problems().size() + " of " + (texts.size() - 1) + " "
                        + result.problems());
        for (String name : texts.keySet()) {
            if (!name.equals("good_light")) {
                boolean named = false;
                for (String problem : result.problems()) {
                    named |= problem.startsWith(name + ":");
                }
                helper.assertTrue(named, "the problem names " + name + ": " + result.problems());
            }
        }
        // Parsing a bad file directly raises the dedicated exception, never an arbitrary one.
        for (String name : List.of("not_json", "top_level_array", "wrong_format", "bad_hex", "five_vertices", "deeply_nested")) {
            try {
                PanelModelParser.parse(name, texts.get(name));
                helper.fail(name + " must be rejected");
            } catch (dev.bwr.mod.panel.model.ModelFormatException expected) {
                helper.assertTrue(expected.getMessage().startsWith(name + ":"), "message names the file: " + expected.getMessage());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelModelBoundsAreEnforced(GameTestHelper helper) {
        String template = "{\"format\":1,\"footprint\":{\"w\":2,\"h\":3},\"parts\":[{\"name\":\"a\",\"role\":\"paint\","
                + "\"pivot\":%s,\"axis\":null,\"travel\":null,\"faces\":[{\"v\":[[0,0,0],[%s,%s,%s],[1,1,0]],\"n\":[0,0,1],\"c\":\"FFFFFF\"}]}],"
                + "\"labels\":[{\"id\":\"l\",\"pos\":%s,\"h\":0.2,\"align\":\"center\",\"rot\":0}]}";
        Object[][] cases = {
                // x, y, z, pivot, label, accepted?
                {"2", "3", "0.3", "null", "[1,1,0.1]", true},                   // exactly the far corner
                {"2.01", "3", "0.3", "null", "[1,1,0.1]", true},                // inside the 0.02 inch tolerance
                {"2.5", "3", "0.3", "null", "[1,1,0.1]", false},                // too far right
                {"1", "3.5", "0.3", "null", "[1,1,0.1]", false},                // too far down
                {"-0.5", "1", "0.3", "null", "[1,1,0.1]", false},               // left of the footprint
                {"1", "-0.1", "0.3", "null", "[1,1,0.1]", false},               // above it
                {"1", "1", "-0.5", "null", "[1,1,0.1]", false},                 // behind the panel
                {"1", "1", "-0.05", "null", "[1,1,0.1]", true},                 // the exporter's own 0.05 allowance
                {"1", "1", "9", "null", "[1,1,0.1]", false},                    // absurdly proud of the face
                {"1", "1", "0.3", "[5,1,0.1]", "[1,1,0.1]", false},             // pivot off the model
                {"1", "1", "0.3", "[1,1,0.1]", "[1,1,0.1]", true},              // pivot inside
                {"1", "1", "0.3", "null", "[3,1,0.1]", false},                  // label off the model
        };
        for (Object[] c : cases) {
            String json = String.format(java.util.Locale.ROOT, template, c[3], "1", "1", "1", c[4])
                    .replace("[1,1,1]", "[" + c[0] + "," + c[1] + "," + c[2] + "]");
            boolean accepted;
            try {
                PanelModelParser.parse("bounds", json);
                accepted = true;
            } catch (dev.bwr.mod.panel.model.ModelFormatException e) {
                accepted = false;
            }
            helper.assertTrue(accepted == (Boolean) c[5], "case x=" + c[0] + " y=" + c[1] + " z=" + c[2] + " pivot=" + c[3]
                    + " label=" + c[4] + " expected " + c[5] + " got " + accepted);
        }
        helper.succeed();
    }

    // ---- scale and centring

    @GameTest(template = "empty")
    public static void panelModelFootprintFitsAndCentresInTheCellRectangle(GameTestHelper helper) {
        // 2:3 model into the 2:3 control switch: fills it exactly.
        Placement exact = Placement.fit(2.4f, 3.6f, 8, 12);
        helper.assertTrue(near(exact.scale, 8 / 2.4, 1e-5) && near(exact.offsetX, 0, 1e-5) && near(exact.offsetY, 0, 1e-5),
                "exact fit: " + exact.scale + " " + exact.offsetX + " " + exact.offsetY);
        // Same model into a square 12x12: height limits, centred across.
        Placement square = Placement.fit(2.4f, 3.6f, 12, 12);
        helper.assertTrue(near(square.scale, 12 / 3.6, 1e-5) && near(square.offsetX, (12 - 2.4 * 12 / 3.6) / 2, 1e-4)
                && near(square.offsetY, 0, 1e-5), "limited by height, centred in x");
        // A wide model into a tall footprint: width limits, centred down.
        Placement wide = Placement.fit(6.04f, 2.294f, 8, 16);
        helper.assertTrue(near(wide.scale, 8 / 6.04, 1e-5) && near(wide.offsetX, 0, 1e-5)
                && near(wide.offsetY, (16 - 2.294 * 8 / 6.04) / 2, 1e-4), "limited by width, centred in y");
        for (Placement p : new Placement[] {exact, square, wide}) {
            helper.assertTrue(p.scale > 0, "scale is positive");
        }
        // The footprint corners land inside the rectangle with equal margins; elevation uses the same scale.
        helper.assertTrue(near(square.x(0), square.offsetX, 1e-6) && near(square.x(2.4f), 12 - square.offsetX, 1e-4)
                && near(square.y(3.6f), 12, 1e-4), "corners map symmetrically");
        helper.assertTrue(near(square.z(1.0f), square.scale, 1e-6) && near(square.length(0.5f), 0.5 * square.scale, 1e-6),
                "uniform scale on z and lengths");
        // A larger footprint option scales everything up, never distorting.
        Placement big = Placement.fit(2.4f, 3.6f, 12, 16);
        helper.assertTrue(near(big.scale, Math.min(12 / 2.4, 16 / 3.6), 1e-5), "larger option: " + big.scale);

        // And the model itself: every fixture, drawn static, stays inside the component's cell rectangle.
        for (Map.Entry<String, BakedPanelModel> entry : PanelModelSet.loadAll(PanelModelFixtures.forTable()).models().entrySet()) {
            BakedPanelModel model = entry.getValue();
            int w = 12;
            int h = 12;
            Placement place = Placement.fit(model, w, h);
            Recorder all = new Recorder();
            for (int p = 0; p < model.partCount(); p++) {
                if (!model.part(p).moving()) {
                    PartDraw.emit(all, model.part(p), place, 0, 0, -1, 1.0f, false, new float[PartDraw.SCRATCH]);
                }
            }
            helper.assertTrue(all.minX >= -0.01 && all.minY >= -0.01 && all.maxX <= w + 0.01 && all.maxY <= h + 0.01,
                    entry.getKey() + " fits its cells: x " + all.minX + ".." + all.maxX + " y " + all.minY + ".." + all.maxY);
            helper.assertTrue(all.minX < 0.5 + (w - model.footprintW * place.scale) / 2 + 0.5
                    && all.maxX > w - 0.5 - (w - model.footprintW * place.scale) / 2 - 0.5, entry.getKey() + " fills its width");
        }
        helper.succeed();
    }

    // ---- state to pose

    @GameTest(template = "empty")
    public static void panelPoseAnglesPerPositionAreExact(GameTestHelper helper) {
        // The real models hang the grip straight down at NORMAL; CLOSE (-1) turns it clockwise, TRIP (the mod's OPEN, +1) counter-clockwise.
        helper.assertTrue(Poses.controlSwitchAngle(-1) == 45.0f && Poses.controlSwitchAngle(0) == 0.0f
                && Poses.controlSwitchAngle(1) == -45.0f, "CLOSE +45 clockwise, NORMAL 0, OPEN -45");
        helper.assertTrue(Poses.controlSwitchAngle(7) == -45.0f && Poses.controlSwitchAngle(-7) == 45.0f, "out of range clamps");
        helper.assertTrue(Poses.detentAngle(0, 3) == -45.0f && Poses.detentAngle(1, 3) == 0.0f && Poses.detentAngle(2, 3) == 45.0f,
                "three positions");
        helper.assertTrue(Poses.detentAngle(0, 4) == -67.5f && Poses.detentAngle(1, 4) == -22.5f
                && Poses.detentAngle(2, 4) == 22.5f && Poses.detentAngle(3, 4) == 67.5f, "four positions, 45 degree steps");
        helper.assertTrue(Poses.detentAngle(0, 2) == -45.0f && Poses.detentAngle(1, 2) == 45.0f, "two positions turn a quarter");
        helper.assertTrue(Poses.detentAngle(0, 6) == -112.5f && Poses.detentAngle(5, 6) == 112.5f, "six positions");
        helper.assertTrue(Poses.detentAngle(9, 3) == 45.0f && Poses.detentAngle(-3, 3) == -45.0f && Poses.detentAngle(0, 1) == 0.0f,
                "indices clamp");
        helper.assertTrue(Poses.keyAngle(false) == -45.0f && Poses.keyAngle(true) == 45.0f, "key switch");
        helper.assertTrue(Poses.knobAngle(0) == -135.0f && Poses.knobAngle(50) == 0.0f && Poses.knobAngle(100) == 135.0f
                && Poses.knobAngle(150) == 135.0f && Poses.knobAngle(-5) == -135.0f && Poses.knobAngle(Double.NaN) == -135.0f,
                "setpoint knob sweeps +-135 over 0..100, clamped");
        helper.assertTrue(Poses.needleAngle(0, Poses.DIAL_SWEEP_DEG) == -125.0f && Poses.needleAngle(0.5, Poses.DIAL_SWEEP_DEG) == 0.0f
                && Poses.needleAngle(1, Poses.DIAL_SWEEP_DEG) == 125.0f && Poses.needleAngle(2, Poses.DIAL_SWEEP_DEG) == 125.0f
                && Poses.needleAngle(Double.NaN, Poses.DIAL_SWEEP_DEG) == -125.0f, "dial needle +-125, no signal pegs low");
        helper.assertTrue(Poses.pointerSlide(0) == 0.0f && Poses.pointerSlide(0.25) == 0.25f && Poses.pointerSlide(1) == 1.0f
                && Poses.pointerSlide(9) == 1.0f && Poses.pointerSlide(Double.NaN) == 0.0f, "pointer travel fraction");
        helper.assertTrue(Poses.capSlide(false) == 0.0f && Poses.capSlide(true) == 1.0f, "cap travel");

        // The handle really turns: its tip moves by exactly the pose angle (clockwise positive), from hanging down.
        BakedPanelModel sw = parse("ge_sb_target_pistol", PanelModelFixtures.controlSwitch("ge_sb_target_pistol", 1.35, "1A1A1A"));
        Placement place = Placement.fit(sw, 8, 12);
        BakedPanelModel.Part handle = sw.part("handle");
        float px = place.x(handle.pivotX);
        float py = place.y(handle.pivotY);
        float rest = draw(handle, place, 0, 0).outerBearing(px, py);
        helper.assertTrue(near(Math.abs(rest), 180, 0.2), "modelled hanging straight down: " + rest);
        for (int position = -1; position <= 1; position++) {
            float bearing = draw(handle, place, Poses.controlSwitchAngle(position), 0).outerBearing(px, py);
            double turned = ((bearing - rest + 540.0) % 360.0) - 180.0;
            helper.assertTrue(near(turned, -45.0 * position, 0.2), "position " + position + " turned the tip " + turned);
        }
        // From hanging down, a clockwise turn (CLOSE) swings the tip to the operator's left, TRIP (OPEN) to the right.
        Recorder open = draw(handle, place, Poses.controlSwitchAngle(1), 0);
        Recorder close = draw(handle, place, Poses.controlSwitchAngle(-1), 0);
        Recorder normal = draw(handle, place, 0, 0);
        helper.assertTrue(open.maxX > normal.maxX + 0.5 && close.minX < normal.minX - 0.5, "OPEN tip right, CLOSE tip left");
        helper.assertTrue(near(open.maxY - open.minY, close.maxY - close.minY, 0.05), "symmetric swing");
        // Rotation about the pivot leaves the pivot's own point where it was, and normals turn with the part.
        Recorder quarter = new Recorder();
        BakedPanelModel flat = parse("turn", PanelModelFixtures.knob("turn"));
        PartDraw.emit(quarter, flat.part("knob"), Placement.fit(flat, 8, 8), 90, 0, -1, 1.0f, false, new float[PartDraw.SCRATCH]);
        boolean found = false;
        for (float[] n : quarter.normals) {
            if (near(n[0], 0, 1e-4) && near(n[1], 1, 1e-4) && near(n[2], 0, 1e-4)) {
                found = true; // a face that looked right (+x) now looks down (+y) after a clockwise quarter turn
            }
        }
        helper.assertTrue(found, "side normals rotate with the part");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelSelectorDetentsFollowTheLegendAnchors(GameTestHelper helper) {
        BakedPanelModel selector = parse("sel", PanelModelFixtures.selector("sel", 1.15, "1A1A1A"));
        // pos1..pos6 sit at bearings -112.5 + 45 i around the shaft and the handle was modelled pointing up, so the
        // handle of position i points at its own legend whatever the position count.
        float[] six = Detents.angles(selector, 6);
        for (int i = 0; i < 6; i++) {
            helper.assertTrue(near(six[i], -112.5 + 45.0 * i, 0.6), "six positions, detent " + i + " = " + six[i]);
        }
        float[] three = Detents.angles(selector, 3);
        helper.assertTrue(three.length == 3 && near(three[0], -112.5, 0.6) && near(three[1], -67.5, 0.6) && near(three[2], -22.5, 0.6),
                "three positions use the first three legends: " + three[0] + " " + three[1] + " " + three[2]);
        // Model without pos anchors: symmetric 45 degree steps.
        BakedPanelModel noAnchors = parse("sw", PanelModelFixtures.controlSwitch("sw", 1.35, "1A1A1A"));
        float[] fallback = Detents.angles(noAnchors, 3);
        helper.assertTrue(fallback[0] == -45.0f && fallback[1] == 0.0f && fallback[2] == 45.0f, "fallback symmetric");
        // More positions than anchors: also the fallback, never a half-and-half mix.
        float[] seven = Detents.angles(selector, 7);
        helper.assertTrue(seven.length == 7 && seven[0] == Poses.detentAngle(0, 7) && seven[6] == Poses.detentAngle(6, 7), "too few anchors");
        // The real Westinghouse numbering: pos1 at 7:30, pos4 at 12, clockwise, handle at 12 o'clock modelled.
        PanelModelFixtures.Model w2 = new PanelModelFixtures.Model("w2sel", 2.4, 3.0);
        w2.part("body", "paint").rect("141414", 0, 0, 2.4, 3.0, 0.1).done();
        w2.part("handle", "paint").pivot(1.2, 1.5, 0.1).rect("1A1A1A", 1.1, 0.6, 1.3, 1.5, 0.3).done();
        for (int i = 0; i < 8; i++) {
            double bearing = Math.toRadians(-135 + 45 * i);
            w2.label("pos" + (i + 1), 1.2 + 0.55 * Math.sin(bearing), 1.5 - 0.55 * Math.cos(bearing), 0.15, 0.065, "center", 0);
        }
        float[] w2Angles = Detents.angles(parse("w2sel", w2.json()), 5);
        helper.assertTrue(near(w2Angles[0], -135, 0.6) && near(w2Angles[1], -90, 0.6) && near(w2Angles[2], -45, 0.6)
                && near(w2Angles[3], 0, 0.6) && near(w2Angles[4], 45, 0.6), "W-2 positions start at 7:30 and pass 12 at pos4");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelMeterPointersFollowTheScaleAtMinMidMax(GameTestHelper helper) {
        GaugeData data = new GaugeData("dial_gauge");
        data.min = 0;
        data.max = 200;
        BakedPanelModel dial = parse("ge_ab40", PanelModelFixtures.dial("ge_ab40"));
        Placement place = Placement.fit(dial, 12, 12);
        BakedPanelModel.Part needle = dial.part("needle");
        float cx = place.x(needle.pivotX);
        float cy = place.y(needle.pivotY);
        double[] inputs = {0, 100, 200, 400, -50, Double.NaN};
        double[] expectedAngle = {-125, 0, 125, 125, -125, -125};
        for (int i = 0; i < inputs.length; i++) {
            float angle = Poses.needleAngle(data.fraction(inputs[i]), Poses.DIAL_SWEEP_DEG);
            helper.assertTrue(near(angle, expectedAngle[i], 1e-3), "dial angle for " + inputs[i] + " is " + angle);
            if (i < 3) {
                float rest = draw(needle, place, 0, 0).outerBearing(cx, cy);
                float turned = draw(needle, place, angle, 0).outerBearing(cx, cy);
                helper.assertTrue(near(turned - rest, expectedAngle[i], 0.3), "drawn pointer for " + inputs[i] + " turned " + (turned - rest));
            }
        }
        // The needle stays inside the cell rectangle at both ends of its sweep.
        for (double angle : new double[] {-125, 125}) {
            Recorder r = draw(needle, place, (float) angle, 0);
            helper.assertTrue(r.minX >= 0 && r.maxX <= 12 && r.minY >= 0 && r.maxY <= 12, "pointer inside at " + angle);
        }

        // Edgewise: the pointer slides exactly its travel (inches * scale) along its axis between min and max.
        for (boolean vertical : new boolean[] {true, false}) {
            BakedPanelModel strip = parse("strip", PanelModelFixtures.edgewise("strip", vertical));
            Placement p = vertical ? Placement.fit(strip, 6, 16) : Placement.fit(strip, 16, 6);
            BakedPanelModel.Part pointer = strip.part("needle");
            helper.assertTrue(pointer.hasTravel && !pointer.hasPivot && near(pointer.travel, 4.6, 1e-5), "pointer slides");
            Recorder low = draw(pointer, p, 0, Poses.pointerSlide(data.fraction(0)));
            Recorder mid = draw(pointer, p, 0, Poses.pointerSlide(data.fraction(100)));
            Recorder high = draw(pointer, p, 0, Poses.pointerSlide(data.fraction(200)));
            float lowAt = vertical ? low.minY : low.minX;
            float midAt = vertical ? mid.minY : mid.minX;
            float highAt = vertical ? high.minY : high.minX;
            double full = pointer.travel * p.scale;
            double direction = vertical ? -1 : 1; // up the strip is toward smaller y; along a horizontal one, larger x
            helper.assertTrue(near(midAt - lowAt, direction * full / 2, 0.01), (vertical ? "vertical" : "horizontal")
                    + " mid travel " + (midAt - lowAt) + " of " + full);
            helper.assertTrue(near(highAt - lowAt, direction * full, 0.01), "full travel " + (highAt - lowAt) + " of " + full);
            // The other axis never moves, and elevation does not change.
            helper.assertTrue(near(vertical ? high.minX : high.minY, vertical ? low.minX : low.minY, 1e-4)
                    && near(high.minZ, low.minZ, 1e-6), "slides along its axis only");
            Recorder none = draw(pointer, p, 0, Poses.pointerSlide(data.fraction(Double.NaN)));
            helper.assertTrue(near(vertical ? none.minY : none.minX, lowAt, 1e-4), "no signal pegs the pointer at the low end");
            helper.assertTrue(high.minX >= 0 && high.maxX <= (vertical ? 6 : 16) && high.minY >= 0 && high.maxY <= (vertical ? 16 : 6),
                    "pointer inside the cells at the top of the scale");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelPushbuttonCapTravelsOnlyWhileHeld(GameTestHelper helper) {
        BakedPanelModel button = parse("pb_flush", PanelModelFixtures.pushbutton("pb_flush", false));
        Placement place = Placement.fit(button, 6, 6);
        BakedPanelModel.Part cap = button.part("cap");
        Recorder up = draw(cap, place, 0, Poses.capSlide(false));
        Recorder down = draw(cap, place, 0, Poses.capSlide(true));
        helper.assertTrue(near(up.maxZ - down.maxZ, 0.15 * place.scale, 1e-4), "cap sinks 0.15 in * scale = "
                + 0.15 * place.scale + ", got " + (up.maxZ - down.maxZ));
        helper.assertTrue(near(up.minX, down.minX, 1e-5) && near(up.maxY, down.maxY, 1e-5), "no sideways motion");
        // A rotation request on a part that only slides (and the reverse) is ignored.
        Recorder spun = draw(cap, place, 90, 0);
        helper.assertTrue(near(spun.minX, up.minX, 1e-5) && near(spun.maxZ, up.maxZ, 1e-6), "a slider has no pivot to turn about");
        BakedPanelModel sw = parse("sw", PanelModelFixtures.controlSwitch("sw", 1.35, "1A1A1A"));
        Recorder still = draw(sw.part("handle"), Placement.fit(sw, 8, 12), 0, 1);
        Recorder rest = draw(sw.part("handle"), Placement.fit(sw, 8, 12), 0, 0);
        helper.assertTrue(near(still.minY, rest.minY, 1e-6), "a turner has no travel to slide along");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelTargetFlagShowsLampsThenLastOperation(GameTestHelper helper) {
        helper.assertTrue(FlagState.colour(true, false, 0) == FlagState.RED && FlagState.colour(false, true, 0) == FlagState.GREEN,
                "a lit lamp input decides");
        helper.assertTrue(FlagState.colour(true, false, -1) == FlagState.RED && FlagState.colour(false, true, 1) == FlagState.GREEN,
                "the lamp wins over the last operation");
        helper.assertTrue(FlagState.colour(false, false, -1) == FlagState.RED && FlagState.colour(false, false, 1) == FlagState.GREEN,
                "no lamp: CLOSE leaves it red, OPEN (TRIP) green, as on a GE breaker control switch");
        helper.assertTrue(FlagState.colour(true, true, -1) == FlagState.RED && FlagState.colour(true, true, 1) == FlagState.GREEN,
                "both lit says nothing: last operation");
        helper.assertTrue(FlagState.colour(false, false, 0) == FlagState.NEUTRAL, "never operated, no lamps: neutral");
        // The per-switch "Target red after: OPEN" setting reverses only the last-operation fallback.
        helper.assertTrue(FlagState.colour(false, false, 1, false) == FlagState.RED
                        && FlagState.colour(false, false, -1, false) == FlagState.GREEN,
                "red-after-OPEN setting: OPEN leaves it red, CLOSE green");
        helper.assertTrue(FlagState.colour(true, false, -1, false) == FlagState.RED
                        && FlagState.colour(false, true, 1, false) == FlagState.GREEN,
                "wired lamps still win under either setting");
        helper.assertTrue(FlagState.colour(false, false, 0, false) == FlagState.NEUTRAL, "never operated stays neutral");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelControlSwitchFlagSettingSavesAndDefaultsToClose(GameTestHelper helper) {
        var fresh = new dev.bwr.mod.panel.component.ControlSwitchData();
        helper.assertTrue(dev.bwr.mod.panel.component.ControlSwitchData.FLAG_RED_AFTER_CLOSE.equals(fresh.flagRed),
                "a new switch defaults to red after CLOSE");
        fresh.flagRed = dev.bwr.mod.panel.component.ControlSwitchData.FLAG_RED_AFTER_OPEN;
        var tag = new net.minecraft.nbt.CompoundTag();
        fresh.save(tag);
        var loaded = new dev.bwr.mod.panel.component.ControlSwitchData();
        loaded.load(tag);
        helper.assertTrue(dev.bwr.mod.panel.component.ControlSwitchData.FLAG_RED_AFTER_OPEN.equals(loaded.flagRed),
                "the setting round-trips");
        tag.remove("FlagRed");
        loaded.load(tag);
        helper.assertTrue(dev.bwr.mod.panel.component.ControlSwitchData.FLAG_RED_AFTER_CLOSE.equals(loaded.flagRed),
                "a save from before the setting loads as red after CLOSE");
        tag.putString("FlagRed", "purple");
        loaded.load(tag);
        helper.assertTrue(dev.bwr.mod.panel.component.ControlSwitchData.FLAG_RED_AFTER_CLOSE.equals(loaded.flagRed),
                "an unknown value falls back to red after CLOSE");
        helper.assertTrue(dev.bwr.mod.panel.component.ComponentTypes.CONTROL_SWITCH.configSchema().stream()
                        .anyMatch(field -> field.key().equals("FlagRed")),
                "the wrench screen offers the setting");
        helper.succeed();
    }

    // ---- legends

    @GameTest(template = "empty")
    public static void panelLegendsFollowTheComponentConfiguration(GameTestHelper helper) {
        BakedPanelModel sw = parse("ge_sb_target_pistol", PanelModelFixtures.controlSwitch("ge_sb_target_pistol", 1.35, "1A1A1A"));
        PanelComponent component = component(ComponentTypes.CONTROL_SWITCH, 8, 12, "MSIV-A");
        Placement place = Placement.fit(sw, 8, 12);
        List<Legends.Item> items = Legends.items(sw, place, "control_switch", component);
        helper.assertTrue(items.size() == 3, "caption, left and right: " + items.size());
        Legends.Item caption = find(items, "MSIV-A");
        helper.assertTrue(caption != null && near(caption.x(), place.x(1.2f), 1e-4) && near(caption.y(), place.y(0.25f), 1e-4)
                && near(caption.capCells(), place.length(0.2f), 1e-4) && caption.rotation() == 0.0f
                && caption.align() == BakedPanelModel.Align.CENTER, "the component label at the caption anchor, scaled by cap height");
        Legends.Item close = find(items, "CLOSE");
        Legends.Item open = find(items, "OPEN");
        // The grip turns clockwise to CLOSE, so CLOSE is the legend on the right (TRIP, the mod's OPEN, on the left).
        helper.assertTrue(close != null && close.rotation() == 55.0f && close.x() > caption.x(), "CLOSE at the right anchor, tilted +55");
        helper.assertTrue(open != null && open.rotation() == -55.0f && open.x() < caption.x(), "OPEN at the left anchor, tilted -55");
        helper.assertTrue(find(items, "NORMAL") == null && find(items, "MSIV-A") != null, "no NORMAL anchor, none drawn; the maker is not drawn");
        helper.assertTrue(caption.ink() == PanelModelParser.INK_LIGHT, "light ink on the black plate");
        ControlSwitchData data = (ControlSwitchData) component.data();
        data.closeText = "START";
        data.openText = "TRIP";
        items = Legends.items(sw, place, "control_switch", component);
        helper.assertTrue(find(items, "START").x() > find(items, "TRIP").x() && find(items, "CLOSE") == null,
                "the caption config is what is drawn: START right, TRIP left");

        // Key switches: OFF and ON at the anchors the style table names for the model.
        PanelStyles.KeyPose geKey = PanelStyles.resolve("key_switch", "ge", "key", id -> true).key();
        PanelStyles.KeyPose w2Key = PanelStyles.resolve("key_switch", "westinghouse", "key", id -> true).key();
        PanelStyles.KeyPose w2Tumbler = PanelStyles.resolve("key_switch", "westinghouse", "tumbler", id -> true).key();
        helper.assertTrue(geKey.offDegrees() == 0.0f && geKey.onDegrees() == 45.0f && geKey.offLegend().equals("pos3")
                && geKey.onLegend().equals("pos4"), "GE key: OFF at 12 o'clock, ON at +45");
        helper.assertTrue(w2Key.offDegrees() == -45.0f && w2Key.onDegrees() == 45.0f && w2Key.offLegend().equals("left")
                && w2Key.onLegend().equals("right") && w2Tumbler.equals(w2Key), "W-2 key and tumbler: OFF -45, ON +45");
        PanelComponent keyComponent = component(ComponentTypes.KEY_SWITCH, 6, 8, "KS-1");
        BakedPanelModel geKeyModel = parse("ge_sb_keyed", PanelModelFixtures.keySwitch("ge_sb_keyed", false, false));
        List<Legends.Item> geKeyItems = Legends.items(geKeyModel, Placement.fit(geKeyModel, 6, 8), "key_switch", keyComponent, geKey);
        helper.assertTrue(find(geKeyItems, "OFF") != null && find(geKeyItems, "ON") != null && find(geKeyItems, "OFF").x() < find(geKeyItems, "ON").x()
                && find(geKeyItems, "KS-1") != null && geKeyItems.size() == 3, "GE key legends: " + geKeyItems);
        BakedPanelModel w2KeyModel = parse("w2_key", PanelModelFixtures.keySwitch("w2_key", true, false));
        List<Legends.Item> w2KeyItems = Legends.items(w2KeyModel, Placement.fit(w2KeyModel, 6, 8), "key_switch", keyComponent, w2Key);
        helper.assertTrue(find(w2KeyItems, "OFF") != null && find(w2KeyItems, "ON") != null && find(w2KeyItems, "OFF").x() < find(w2KeyItems, "ON").x()
                && find(w2KeyItems, "OFF").align() == BakedPanelModel.Align.RIGHT && find(w2KeyItems, "ON").align() == BakedPanelModel.Align.LEFT,
                "W-2 key legends left and right of the lock: " + w2KeyItems);
        helper.assertTrue(Legends.items(geKeyModel, Placement.fit(geKeyModel, 6, 8), "key_switch", keyComponent, null).size() == 1,
                "without a key pose only the caption is known");

        // Selector: one legend per position, only as many as it has.
        BakedPanelModel selector = parse("sel", PanelModelFixtures.selector("sel", 1.15, "1A1A1A"));
        PanelComponent rotary = component(ComponentTypes.ROTARY_SELECTOR, 8, 10, "RS-1");
        RotarySelectorData rotaryData = (RotarySelectorData) rotary.data();
        rotaryData.positions = 3;
        rotaryData.names = "OFF,AUTO";
        List<Legends.Item> names = Legends.items(selector, Placement.fit(selector, 8, 10), "rotary_selector", rotary);
        helper.assertTrue(names.size() == 4 && find(names, "OFF") != null && find(names, "AUTO") != null && find(names, "3") != null
                && find(names, "RS-1") != null && find(names, "4") == null, "three position names plus the caption: " + names);

        // Dial: six numbered scale anchors (scale0..scale5), each numeral at its own anchor, plus units and caption.
        BakedPanelModel dial = parse("ge_ab40", PanelModelFixtures.dial("ge_ab40"));
        PanelComponent gauge = component(ComponentTypes.DIAL_GAUGE, 12, 12, "DG-1");
        GaugeData gaugeData = (GaugeData) gauge.data();
        gaugeData.min = 0;
        gaugeData.max = 100;
        gaugeData.units = "MPa";
        Placement dialPlace = Placement.fit(dial, 12, 12);
        List<Legends.Item> scale = Legends.items(dial, dialPlace, "dial_gauge", gauge);
        String[] numerals = {"0", "20", "40", "60", "80", "100"};
        for (int i = 0; i < numerals.length; i++) {
            Legends.Item numeral = find(scale, numerals[i]);
            BakedPanelModel.Label anchor = dial.label("scale" + i);
            helper.assertTrue(numeral != null, "numeral " + numerals[i]);
            helper.assertTrue(near(numeral.x(), dialPlace.x(anchor.x), 1e-4) && near(numeral.y(), dialPlace.y(anchor.y), 1e-4)
                            && near(numeral.capCells(), dialPlace.length(anchor.height), 1e-4),
                    "numeral " + numerals[i] + " sits at scale" + i + " (a radial scale is not nudged): " + numeral);
            helper.assertTrue(numeral.ink() == PanelModelParser.INK_DARK, "dark ink on the white dial");
        }
        helper.assertTrue(find(scale, "MPa") != null && find(scale, "DG-1") != null && scale.size() == 8, "units and caption: " + scale.size());
        gaugeData.min = -50;
        gaugeData.max = 50;
        helper.assertTrue(find(Legends.items(dial, dialPlace, "dial_gauge", gauge), "-50") != null
                && find(Legends.items(dial, dialPlace, "dial_gauge", gauge), "-30") != null, "numerals follow min and max");

        // A dial model with a single scale anchor spreads five numerals along the pointer's sweep instead.
        BakedPanelModel single = parse("dial1", PanelModelFixtures.dial("dial1", false, false));
        gaugeData.min = 0;
        gaugeData.max = 100;
        List<Legends.Item> arc = Legends.items(single, dialPlace, "dial_gauge", gauge);
        String[] five = {"0", "25", "50", "75", "100"};
        BakedPanelModel.Part needle = single.part("needle");
        for (int i = 0; i < five.length; i++) {
            Legends.Item numeral = find(arc, five[i]);
            helper.assertTrue(numeral != null, "arc numeral " + five[i]);
            double radius = 1.45 * dialPlace.scale;
            double angle = Math.toRadians(-125 + 62.5 * i);
            helper.assertTrue(near(numeral.x(), dialPlace.x(needle.pivotX) + radius * Math.sin(angle), 0.02)
                    && near(numeral.y(), dialPlace.y(needle.pivotY) - radius * Math.cos(angle), 0.02),
                    "numeral " + five[i] + " on the arc at " + (-125 + 62.5 * i) + " degrees: " + numeral);
        }

        // Edgewise with numbered anchors: scale0 low .. scale5 high; the end numerals move half a cap height inward.
        BakedPanelModel strip = parse("ge_180_vertical", PanelModelFixtures.edgewise("ge_180_vertical", true));
        PanelComponent meter = component(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 6, 16, "EV-1");
        GaugeData meterData = (GaugeData) meter.data();
        meterData.min = 0;
        meterData.max = 10;
        Placement stripPlace = Placement.fit(strip, 6, 16);
        List<Legends.Item> stripItems = Legends.items(strip, stripPlace, "edgewise_gauge_vertical", meter);
        String[] stripNumerals = {"0", "2", "4", "6", "8", "10"};
        for (int i = 0; i < stripNumerals.length; i++) {
            Legends.Item numeral = find(stripItems, stripNumerals[i]);
            BakedPanelModel.Label anchor = strip.label("scale" + i);
            helper.assertTrue(numeral != null && numeral.align() == BakedPanelModel.Align.RIGHT, "strip numeral " + stripNumerals[i] + " " + stripItems);
            float expectedY = anchor.y + (i == 0 ? -0.5f * anchor.height : i == 5 ? 0.5f * anchor.height : 0.0f);
            helper.assertTrue(near(numeral.x(), stripPlace.x(anchor.x), 1e-4) && near(numeral.y(), stripPlace.y(expectedY), 1e-4),
                    "numeral " + stripNumerals[i] + " at its anchor, ends nudged inward: " + numeral.y() + " vs " + stripPlace.y(expectedY));
        }
        Legends.Item units = find(stripItems, "");
        helper.assertTrue(units == null, "empty units draw nothing");
        meterData.units = "psi";
        Legends.Item psi = find(Legends.items(strip, stripPlace, "edgewise_gauge_vertical", meter), "psi");
        helper.assertTrue(psi != null && psi.rotation() == 90.0f, "units keep their anchor's 90 degree rotation");

        // Edgewise with a single scale anchor: three numerals marching along the pointer's travel.
        BakedPanelModel lone = parse("strip1", PanelModelFixtures.edgewise("strip1", true, false, false));
        List<Legends.Item> loneItems = Legends.items(lone, stripPlace, "edgewise_gauge_vertical", meter);
        Legends.Item low = find(loneItems, "0");
        Legends.Item mid = find(loneItems, "5");
        Legends.Item high = find(loneItems, "10");
        helper.assertTrue(low != null && mid != null && high != null, "three numerals: " + loneItems);
        helper.assertTrue(near(low.y() - mid.y(), 4.6 * stripPlace.scale / 2, 0.02) && near(low.y() - high.y(), 4.6 * stripPlace.scale, 0.02)
                && near(low.x(), high.x(), 1e-4), "numerals climb the strip by the pointer travel");
        // A label id the game does not know is simply not drawn.
        helper.assertTrue(Legends.text("lamp", "maker", component(ComponentTypes.LAMP, 4, 4, "LP-1")) == null, "unknown ids draw nothing");
        helper.succeed();
    }

    private static Legends.Item find(List<Legends.Item> items, String text) {
        for (Legends.Item item : items) {
            if (item.text().equals(text)) {
                return item;
            }
        }
        return null;
    }

    // ---- arbitrary normals

    @GameTest(template = "empty")
    public static void panelCapsAndLensesTakeTheChosenColourAndLensLegendsKnowTheirLamp(GameTestHelper helper) {
        BakedPanelModel button = parse("pb", PanelModelFixtures.pushbutton("pb", true));
        BakedPanelModel.Part cap = button.part("cap");
        BakedPanelModel.Part lens = button.part("lens");
        BakedPanelModel.Part bezel = button.part("bezel");
        PushbuttonData data = new PushbuttonData();
        helper.assertTrue(data.color == PushbuttonData.DEFAULT_COLOR, "the default cap colour");
        helper.assertTrue(Recolour.baseFor(cap, data, -1) == 0xE02020, "the default colour means the model's own tint (red)");
        helper.assertTrue(Recolour.baseFor(lens, data, -1) == 0xFFB000, "the lamp-role lens takes its amber tint");
        helper.assertTrue(Recolour.baseFor(bezel, data, -1) == Recolour.KEEP, "an untinted part keeps its colours");
        data.color = 0x20B040;
        helper.assertTrue(Recolour.baseFor(cap, data, -1) == 0x20B040 && Recolour.baseFor(lens, data, -1) == 0x20B040
                && Recolour.baseFor(bezel, data, -1) == Recolour.KEEP, "a chosen colour recolours the tinted parts only");
        // Lamps and selectors the same way; types without a colour setting fall back to the tint palette.
        LampData lamp = new LampData();
        BakedPanelModel light = parse("light", PanelModelFixtures.light("light", false));
        helper.assertTrue(Recolour.baseFor(light.part("lens"), lamp, -1) == 0xE02020, "default lens: the tint");
        lamp.color = 0x30D060;
        helper.assertTrue(Recolour.baseFor(light.part("lens"), lamp, -1) == 0x30D060, "chosen lens colour");
        helper.assertTrue(Recolour.baseFor(light.part("lens"), new ControlSwitchData(), -1) == 0xE02020, "no colour setting: the tint");
        // A pointer takes the variant's colour; a flag is never recoloured here.
        BakedPanelModel dial = parse("dial", PanelModelFixtures.dial("dial"));
        helper.assertTrue(Recolour.baseFor(dial.part("needle"), new GaugeData("dial_gauge"), PanelStyles.RED_POINTER) == PanelStyles.RED_POINTER
                && Recolour.baseFor(dial.part("needle"), new GaugeData("dial_gauge"), -1) == Recolour.KEEP, "pointer colour");
        BakedPanelModel sw = parse("sw", PanelModelFixtures.controlSwitch("sw", 1.35, "1A1A1A"));
        helper.assertTrue(Recolour.baseFor(sw.part("target"), new ControlSwitchData(), PanelStyles.RED_POINTER) == Recolour.KEEP, "flag");

        // A legend on a lamp lens knows it, so the renderer can print it dark on the lit lens.
        BakedPanelModel square = parse("sq", PanelModelFixtures.light("sq", true));
        PanelComponent lampComponent = component(ComponentTypes.LAMP, 6, 6, "LP-7");
        List<Legends.Item> legends = Legends.items(square, Placement.fit(square, 6, 6), "lamp", lampComponent);
        helper.assertTrue(legends.size() == 1 && legends.get(0).text().equals("LP-7") && legends.get(0).onLamp(), "legend on the lens: " + legends);
        helper.assertTrue(!Legends.items(sw, Placement.fit(sw, 8, 12), "control_switch", component(ComponentTypes.CONTROL_SWITCH, 8, 12, "X"))
                .get(0).onLamp(), "a plate legend is not on a lamp");
        // The same label id on a switch is not the component's label: models print other text there.
        helper.assertTrue(Legends.text("control_switch", "legend", component(ComponentTypes.CONTROL_SWITCH, 8, 12, "CS-1")) == null,
                "legend on a switch is the model's own printed text");

        // The setpoint knob prints its percent scale.
        BakedPanelModel knob = parse("knob", PanelModelFixtures.knob("knob"));
        PanelComponent knobComponent = component(ComponentTypes.SETPOINT_KNOB, 8, 8, "SK-1");
        List<Legends.Item> scale = Legends.items(knob, Placement.fit(knob, 8, 8), "setpoint_knob", knobComponent);
        helper.assertTrue(scale.size() == 4 && find(scale, "0") != null && find(scale, "50") != null && find(scale, "100") != null
                && find(scale, "SK-1") != null, "0, 50 and 100 and the caption: " + scale);
        helper.assertTrue(find(scale, "0").x() < find(scale, "50").x() && find(scale, "50").x() < find(scale, "100").x(), "left to right");
        // The knob's pointer rests at 50 percent pointing straight up and sweeps 0 at 7:30 to 100 at 4:30.
        BakedPanelModel.Part pointer = knob.part("knob");
        Placement knobPlace = Placement.fit(knob, 8, 8);
        float kx = knobPlace.x(pointer.pivotX);
        float ky = knobPlace.y(pointer.pivotY);
        float rest = draw(pointer, knobPlace, Poses.knobAngle(50), 0).outerBearing(kx, ky);
        float low = draw(pointer, knobPlace, Poses.knobAngle(0), 0).outerBearing(kx, ky);
        float high = draw(pointer, knobPlace, Poses.knobAngle(100), 0).outerBearing(kx, ky);
        helper.assertTrue(near(rest, 0, 4) && near(((low - rest + 540) % 360) - 180, -135, 0.5) && near(((high - rest + 540) % 360) - 180, 135, 0.5),
                "50 percent straight up, 0 at -135, 100 at +135: " + low + " " + rest + " " + high);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelQuadsKeepArbitraryNormalsThroughTheStaticMesh(GameTestHelper helper) {
        helper.assertTrue(QuadSink.nearestNormal(0, 0, 1) == QuadSink.NORMAL_FACE && QuadSink.nearestNormal(0.2f, 0.1f, -0.9f) == QuadSink.NORMAL_FACE
                && QuadSink.nearestNormal(-1, 0, 0) == QuadSink.NORMAL_LEFT && QuadSink.nearestNormal(0.9f, 0.3f, 0.1f) == QuadSink.NORMAL_RIGHT
                && QuadSink.nearestNormal(0, -1, 0) == QuadSink.NORMAL_UP && QuadSink.nearestNormal(0.1f, 0.8f, 0.2f) == QuadSink.NORMAL_DOWN,
                "the enum fallback picks the nearest axis");
        // A sink that only implements the enum method still receives the custom quad, with the nearest axis.
        int[] seen = {-1};
        QuadSink enumOnly = (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb, normal, emissive) -> seen[0] = normal;
        enumOnly.quad(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0xFFFFFF, 0.0f, 0.9f, 0.1f, false);
        helper.assertTrue(seen[0] == QuadSink.NORMAL_DOWN, "default routes to the enum overload: " + seen[0]);

        MeshBuilder builder = new MeshBuilder();
        builder.box(0, 0, 4, 4, 0, 1, 0x112233, false);                                        // five enum quads
        builder.quad(0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0x445566, 0.6f, 0.0f, 0.8f, true);   // one custom, emissive
        builder.rect(0, 0, 2, 2, 1.5f, 0x778899, false);                                       // enum again after custom
        builder.quad(2, 2, 1, 3, 2, 1, 3, 3, 1, 2, 3, 1, 0xAABBCC, -0.6f, 0.8f, 0.0f, false); // custom after enum
        StaticMesh mesh = builder.build();
        helper.assertTrue(mesh.quadCount() == 8, "all quads recorded: " + mesh.quadCount());
        Recorder replay = new Recorder();
        mesh.emit(replay);
        helper.assertTrue(replay.quads.size() == 8, "all quads replayed");
        int custom = 0;
        for (int i = 0; i < 8; i++) {
            float[] n = replay.normals.get(i);
            if (n == null) {
                helper.assertTrue(replay.enumNormals.get(i) >= 0 && replay.enumNormals.get(i) <= 4, "enum quad keeps its enum normal");
            } else {
                custom++;
                if (replay.colors.get(i) == 0x445566) {
                    helper.assertTrue(n[0] == 0.6f && n[1] == 0.0f && n[2] == 0.8f && replay.emissive.get(i), "custom normal and glow survive");
                } else {
                    helper.assertTrue(replay.colors.get(i) == 0xAABBCC && n[0] == -0.6f && n[1] == 0.8f && n[2] == 0.0f, "second custom quad");
                }
            }
        }
        helper.assertTrue(custom == 2, "exactly the two custom quads: " + custom);
        // A mesh with only enum quads has no normals to carry.
        MeshBuilder plain = new MeshBuilder();
        plain.rect(0, 0, 1, 1, 0, 0xFFFFFF, false);
        Recorder plainReplay = new Recorder();
        plain.build().emit(plainReplay);
        helper.assertTrue(plainReplay.normals.size() == 1 && plainReplay.normals.get(0) == null, "enum-only meshes replay enum normals");
        // Growth past the initial capacity keeps normals aligned with their quads.
        MeshBuilder grow = new MeshBuilder();
        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                grow.quad(0, 0, i, 1, 0, i, 1, 1, i, 0, 1, i, i, 0.0f, 0.0f, 1.0f, false);
            } else {
                grow.rect(0, 0, 1, 1, i, i, false);
            }
        }
        Recorder growReplay = new Recorder();
        grow.build().emit(growReplay);
        for (int i = 0; i < 100; i++) {
            helper.assertTrue((growReplay.normals.get(i) != null) == (i % 2 == 0) && growReplay.colors.get(i) == i, "growth keeps quad " + i);
        }
        helper.succeed();
    }

    // ---- style table, config, fallback

    @GameTest(template = "empty")
    public static void panelStyleTableIsConsistentAndHasEveryString(GameTestHelper helper) throws IOException {
        List<String> lang = new ArrayList<>();
        try (InputStream in = PanelModelTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            helper.assertTrue(in != null, "lang file on the classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:").matcher(text);
            while (m.find()) {
                lang.add(m.group(1));
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> typesWithRows = new java.util.TreeSet<>();
        for (PanelStyles.Entry entry : PanelStyles.TABLE) {
            ComponentType type = ComponentTypes.get(dev.bwr.mod.BwrMod.id(entry.type()));
            helper.assertTrue(type != null, "the table names a real component type: " + entry.type());
            helper.assertTrue(seen.add(entry.type() + "/" + entry.style() + "/" + entry.variant()), "no duplicate row " + entry);
            helper.assertTrue(PanelStyles.styles(entry.type()).contains(entry.style()) && !entry.style().equals(PanelStyles.CLASSIC),
                    "style is a listed non-classic style");
            helper.assertTrue(!entry.models().isEmpty(), "row has a model id");
            for (String id : entry.models()) {
                helper.assertTrue(PanelModelSet.validId(id), "model id is a valid file name: " + id);
            }
            helper.assertTrue(lang.contains("component.bwr.option.Variant." + entry.variant()), "lang for variant " + entry.variant());
            helper.assertTrue(lang.contains("component.bwr.option.Style." + entry.style()), "lang for style " + entry.style());
            typesWithRows.add(entry.type());
        }
        helper.assertTrue(lang.contains("component.bwr.option.Style.classic"), "lang for classic");
        helper.assertTrue(typesWithRows.equals(java.util.Set.of("control_switch", "rotary_selector", "key_switch", "pushbutton", "lamp",
                "setpoint_knob", "dial_gauge", "edgewise_gauge_vertical", "edgewise_gauge_horizontal")), "the nine types: " + typesWithRows);
        for (String type : typesWithRows) {
            // Every non-classic style has exactly one default row, which names its default variant.
            for (String style : PanelStyles.styles(type)) {
                if (style.equals(PanelStyles.CLASSIC)) {
                    continue;
                }
                int defaults = 0;
                for (PanelStyles.Entry entry : PanelStyles.TABLE) {
                    if (entry.type().equals(type) && entry.style().equals(style) && entry.isDefault()) {
                        defaults++;
                    }
                }
                helper.assertTrue(defaults == 1, type + "/" + style + " has one default row");
                helper.assertTrue(PanelStyles.entry(type, style, PanelStyles.defaultVariant(type, style)) != null, "default variant resolves");
            }
            ComponentType componentType = ComponentTypes.get(dev.bwr.mod.BwrMod.id(type));
            List<ConfigField> schema = componentType.configSchema();
            ConfigField styleField = null;
            ConfigField variantField = null;
            for (ConfigField field : schema) {
                if (field.key().equals("Style")) {
                    styleField = field;
                }
                if (field.key().equals("Variant")) {
                    variantField = field;
                }
                helper.assertTrue(lang.contains(field.labelKey()), "label for " + field.key() + " of " + type + ": " + field.labelKey());
            }
            helper.assertTrue(styleField != null && styleField.kind() == ConfigField.Kind.ENUM
                    && styleField.options().equals(PanelStyles.styles(type)), type + " offers its styles in the wrench screen");
            helper.assertTrue((variantField != null) == PanelStyles.hasVariantField(type), type + " offers variants only when it has several");
            if (variantField != null) {
                helper.assertTrue(variantField.options().equals(PanelStyles.variants(type)), "variant options");
            }
        }
        // The variants a player was promised.
        helper.assertTrue(PanelStyles.variants("control_switch").containsAll(List.of("pistol", "pistol_hd")), "control switch handles");
        helper.assertTrue(PanelStyles.variants("rotary_selector").containsAll(List.of("oval", "knurled", "lever", "round_notched", "mini_slim")),
                "selector handles");
        helper.assertTrue(PanelStyles.variants("pushbutton").containsAll(List.of("flush", "extended", "mushroom", "guarded", "illuminated")),
                "pushbuttons");
        helper.assertTrue(PanelStyles.variants("lamp").containsAll(List.of("round", "square")), "lenses");
        helper.assertTrue(PanelStyles.variants("dial_gauge").contains("red_pointer") && !PanelStyles.hasVariantField("setpoint_knob"), "pointers");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelStyleResolvesToAModelOrFallsBackToClassic(GameTestHelper helper) {
        // The documented examples of the table.
        helper.assertTrue(PanelStyles.resolve("control_switch", "ge", "pistol", id -> true).modelId().equals("ge_sb_target_pistol"), "ge switch");
        helper.assertTrue(PanelStyles.resolve("rotary_selector", "ge", "oval", id -> true).modelId().equals("ge_sb_standard_oval"), "ge selector");
        helper.assertTrue(PanelStyles.resolve("key_switch", "ge", "key", id -> true).modelId().equals("ge_sb_keyed"), "ge key");
        helper.assertTrue(PanelStyles.resolve("control_switch", "westinghouse", "pistol", id -> true).modelId().equals("w2_pistol"), "w2 switch");
        helper.assertTrue(PanelStyles.resolve("rotary_selector", "westinghouse", "oval", id -> true).modelId().equals("w2_oval"), "w2 selector");
        helper.assertTrue(PanelStyles.resolve("key_switch", "westinghouse", "key", id -> true).modelId().equals("w2_key"), "w2 key");
        helper.assertTrue(PanelStyles.resolve("dial_gauge", "ge", "standard", id -> true).modelId().equals("ge_ab40"), "dial");
        helper.assertTrue(PanelStyles.resolve("edgewise_gauge_vertical", "ge", "standard", id -> true).modelId().equals("ge_180_vertical"), "strip v");
        helper.assertTrue(PanelStyles.resolve("edgewise_gauge_horizontal", "ge", "standard", id -> true).modelId().equals("ge_180_horizontal"), "strip h");
        helper.assertTrue(PanelStyles.resolve("pushbutton", "ge", "flush", id -> true).modelId().equals("pb_flush"), "flush");
        helper.assertTrue(PanelStyles.resolve("pushbutton", "ge", "mushroom", id -> true).modelId().equals("pb_mushroom"), "mushroom");
        helper.assertTrue(PanelStyles.resolve("pushbutton", "ge", "illuminated", id -> true).modelId().equals("pb_illuminated"), "illuminated");
        helper.assertTrue(PanelStyles.resolve("lamp", "ge", "round", id -> true).modelId().equals("light_round"), "lamp");
        helper.assertTrue(PanelStyles.resolve("setpoint_knob", "ge", "fluted", id -> true).modelId().equals("knob_fluted"), "knob");
        // Classic, unknown styles and missing models all mean the procedural look.
        helper.assertTrue(PanelStyles.resolve("control_switch", "classic", "pistol", id -> true) == null, "classic is procedural");
        helper.assertTrue(PanelStyles.resolve("control_switch", "art_deco", "pistol", id -> true) == null, "unknown style");
        helper.assertTrue(PanelStyles.resolve("toggle_switch", "ge", "", id -> true) == null, "a type with no models");
        helper.assertTrue(PanelStyles.resolve("control_switch", "ge", "pistol", id -> false) == null, "model file missing: classic");
        helper.assertTrue(PanelStyles.resolve("control_switch", "westinghouse", "pistol_hd", id -> id.equals("w2_pistol")) == null,
                "only the exact model counts, the plain pistol is not substituted");
        // A variant that belongs to the other style shows the style's own default.
        helper.assertTrue(PanelStyles.resolve("rotary_selector", "westinghouse", "knurled", id -> true).modelId().equals("w2_oval"), "foreign variant");
        helper.assertTrue(PanelStyles.resolve("rotary_selector", "ge", "mini_slim", id -> true).modelId().equals("ge_sb_standard_oval"), "foreign variant 2");
        helper.assertTrue(PanelStyles.resolve("rotary_selector", "ge", "", id -> true).modelId().equals("ge_sb_standard_oval"), "no variant");
        // Alternatives: the first one that exists wins.
        PanelStyles.Resolved alt = PanelStyles.resolve("rotary_selector", "westinghouse", "mini_slim", id -> id.equals("w2_mini"));
        helper.assertTrue(alt != null && alt.modelId().equals("w2_mini"), "alternative id");
        // The red pointer is its own model when that ships; when it does not, the standard model with the pointer recoloured.
        PanelStyles.Resolved red = PanelStyles.resolve("dial_gauge", "ge", "red_pointer", id -> true);
        PanelStyles.Resolved black = PanelStyles.resolve("dial_gauge", "ge", "standard", id -> true);
        helper.assertTrue(red.modelId().equals("ge_ab40_red") && red.needleRgb() == PanelStyles.NO_COLOUR
                && black.modelId().equals("ge_ab40") && black.needleRgb() == PanelStyles.NO_COLOUR, "dedicated red-pointer model");
        for (String[] strip : new String[][] {{"dial_gauge", "ge_ab40"}, {"edgewise_gauge_vertical", "ge_180_vertical"},
                {"edgewise_gauge_horizontal", "ge_180_horizontal"}}) {
            PanelStyles.Resolved stand = PanelStyles.resolve(strip[0], "ge", "red_pointer", id -> id.equals(strip[1]));
            helper.assertTrue(stand != null && stand.modelId().equals(strip[1]) && stand.needleRgb() == PanelStyles.RED_POINTER,
                    strip[0] + ": without the red model the standard one is recoloured");
            PanelStyles.Resolved dedicated = PanelStyles.resolve(strip[0], "ge", "red_pointer", id -> id.equals(strip[1] + "_red"));
            helper.assertTrue(dedicated != null && dedicated.modelId().equals(strip[1] + "_red") && dedicated.needleRgb() == PanelStyles.NO_COLOUR,
                    strip[0] + ": the red model needs no recolouring");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelStyleDefaultsSaveLoadAndOldSavesAreClassic(GameTestHelper helper) {
        try {
            // New components start as GE exactly when their model ships.
            PanelStyles.overrideShipped(id -> false);
            helper.assertTrue(new ControlSwitchData().style.equals("classic") && new LampData().style.equals("classic")
                    && new GaugeData("dial_gauge").style.equals("classic"), "no model shipped: new components are classic");
            PanelStyles.overrideShipped(id -> true);
            ControlSwitchData fresh = new ControlSwitchData();
            helper.assertTrue(fresh.style.equals("ge") && fresh.variant.equals("pistol"), "model ships: new components are ge");
            helper.assertTrue(new RotarySelectorData().style.equals("ge") && new RotarySelectorData().variant.equals("oval")
                    && new PushbuttonData().variant.equals("flush") && new LampData().variant.equals("round")
                    && new SetpointKnobData().style.equals("ge") && new KeySwitchData().variant.equals("key")
                    && new GaugeData("edgewise_gauge_vertical").style.equals("ge"), "defaults per type");
            PanelStyles.overrideShipped(id -> id.equals("ge_sb_target_pistol"));
            helper.assertTrue(new ControlSwitchData().style.equals("ge") && new RotarySelectorData().style.equals("classic"),
                    "only types whose own model ships");

            // Save and reload: a fixed point, for every type.
            PanelStyles.overrideShipped(id -> true);
            for (ComponentType type : ComponentTypes.all()) {
                ComponentData data = type.createData();
                CompoundTag tag = data.toTag();
                if (!tag.contains("Style")) {
                    continue;
                }
                helper.assertTrue(tag.getString("Style").equals("ge"), type.id() + " saves its style");
                CompoundTag edited = tag.copy();
                String style = PanelStyles.styles(type.id().getPath()).contains("westinghouse") ? "westinghouse" : "ge";
                String variant = PanelStyles.variants(type.id().getPath()).get(PanelStyles.variants(type.id().getPath()).size() - 1);
                edited.putString("Style", style);
                edited.putString("Variant", variant);
                ComponentData loaded = type.createData();
                loaded.load(edited);
                CompoundTag saved = loaded.toTag();
                helper.assertTrue(saved.getString("Style").equals(style) && saved.getString("Variant").equals(variant),
                        type.id() + " keeps " + style + "/" + variant + ": " + saved);
                ComponentData again = type.createData();
                again.load(saved);
                helper.assertTrue(again.toTag().equals(saved), type.id() + " save is a fixed point of load");
            }
            // A component that starts classic (no model ships) holds a variant too, and its save is just as stable.
            PanelStyles.overrideShipped(id -> false);
            for (ComponentType type : ComponentTypes.all()) {
                ComponentData data = type.createData();
                CompoundTag tag = data.toTag();
                if (!tag.contains("Style")) {
                    continue;
                }
                helper.assertTrue(tag.getString("Style").equals("classic"), type.id() + " starts classic");
                ComponentData again = type.createData();
                again.load(tag);
                helper.assertTrue(again.toTag().equals(tag), type.id() + " classic save is a fixed point of load: " + tag + " vs " + again.toTag());
            }
            PanelStyles.overrideShipped(id -> true);
            // Old saves have no style: classic, whatever new components start as.
            for (ComponentType type : ComponentTypes.all()) {
                ComponentData data = type.createData();
                CompoundTag tag = data.toTag();
                if (!tag.contains("Style")) {
                    continue;
                }
                tag.remove("Style");
                tag.remove("Variant");
                ComponentData old = type.createData();
                old.load(tag);
                helper.assertTrue(old.toTag().getString("Style").equals("classic"), type.id() + ": a save without a style loads as classic");
            }
            // Garbage never gets through load.
            CompoundTag junk = new ControlSwitchData().toTag();
            junk.putString("Style", "../../etc");
            junk.putString("Variant", "<script>");
            ControlSwitchData cleaned = new ControlSwitchData();
            cleaned.load(junk);
            helper.assertTrue(cleaned.style.equals("classic") && cleaned.variant.equals("pistol"), "unknown style/variant: " + cleaned.style + " " + cleaned.variant);
            junk.putInt("Style", 5);
            cleaned.load(junk);
            helper.assertTrue(cleaned.style.equals("classic"), "a non-string style is ignored");
        } finally {
            PanelStyles.overrideShipped(null);
        }
        helper.succeed();
    }

    /**
     * Whatever models the mod ships (the ids of the style table that exist as files) must be accepted by the
     * loader, and every moving part must stay inside the footprint at both ends of its range. Passes trivially
     * while none are shipped; it is what catches a bad export when they arrive.
     */
    @GameTest(template = "empty")
    public static void panelShippedModelsLoadAndStayInsideTheirFootprintAtEveryPosition(GameTestHelper helper) throws IOException {
        java.util.Set<String> done = new java.util.HashSet<>();
        int checked = 0;
        for (PanelStyles.Entry entry : PanelStyles.TABLE) {
            for (String id : entry.models()) {
                if (!done.add(id)) {
                    continue;
                }
                String text;
                try (InputStream in = PanelModelTests.class.getResourceAsStream("/assets/bwr/models/panel/" + id + ".json")) {
                    if (in == null) {
                        continue;
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                BakedPanelModel model;
                try {
                    model = PanelModelParser.parse(id, text);
                } catch (dev.bwr.mod.panel.model.ModelFormatException e) {
                    helper.fail("shipped model is rejected by the loader: " + e.getMessage());
                    return;
                }
                float[] angles = switch (entry.type()) {
                    case "control_switch" -> new float[] {-45, 0, 45};
                    case "rotary_selector" -> {
                        List<Float> all = new ArrayList<>();
                        for (int n = 2; n <= 6; n++) {
                            for (float a : Detents.angles(model, n)) {
                                all.add(a);
                            }
                        }
                        float[] out = new float[all.size()];
                        for (int i = 0; i < out.length; i++) {
                            out[i] = all.get(i);
                        }
                        yield out;
                    }
                    case "key_switch" -> Detents.angles(model, 2);
                    case "setpoint_knob" -> new float[] {-135, 0, 135};
                    case "dial_gauge" -> new float[] {-125, 0, 125};
                    default -> new float[] {0};
                };
                Placement inches = Placement.fit(model, model.footprintW, model.footprintH);
                for (int p = 0; p < model.partCount(); p++) {
                    BakedPanelModel.Part part = model.part(p);
                    float[] slides = part.hasTravel ? new float[] {0, 1} : new float[] {0};
                    for (float angle : part.hasPivot ? angles : new float[] {0}) {
                        for (float slide : slides) {
                            Recorder r = draw(part, inches, angle, slide);
                            helper.assertTrue(r.minX >= -0.02 && r.minY >= -0.02 && r.maxX <= model.footprintW + 0.02
                                            && r.maxY <= model.footprintH + 0.02,
                                    id + ": part " + part.name + " leaves the " + model.footprintW + " x " + model.footprintH
                                            + " inch footprint at angle " + angle + " slide " + slide + ": x " + r.minX + ".." + r.maxX
                                            + " y " + r.minY + ".." + r.maxY);
                        }
                    }
                }
                checked++;
            }
        }
        com.mojang.logging.LogUtils.getLogger().info("PANEL MODELS shipped in the mod and checked: {}", checked);
        helper.succeed();
    }

    // ---- world: wrench edit, save and reload, last-operation latch

    private static final Direction FACING = Direction.NORTH;

    /** A cleared, force-loaded stage with one panel block and a fake player holding nothing. */
    private static final class Stage {
        final GameTestHelper helper;
        final ServerLevel level;
        /** The first panel block; {@link #second} is the one to its right (toward -X for a north facing wall). */
        final BlockPos origin;
        final BlockPos second;
        final FakePlayer player;

        Stage(GameTestHelper helper, int slot) {
            this.helper = helper;
            level = helper.getLevel();
            origin = new BlockPos(54_900 + slot * 64, 110, 54_900);
            second = origin.relative(FACING.getCounterClockWise());
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-model-test-" + slot).getBytes(StandardCharsets.UTF_8)), "panel-model-test-" + slot));
        }

        /**
         * Builds two clean, force-loaded panel blocks two ticks after forcing the chunks (so entities left by an
         * earlier run in the persistent world are visible), runs {@code body}, and releases the chunks in every
         * outcome. Anything the body throws that is not already a test assertion becomes one, so a defect fails
         * this test instead of taking the whole GameTest server down.
         */
        static void run(GameTestHelper helper, int slot, java.util.function.Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            helper.runAfterDelay(2, () -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() + 0.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
                    for (BlockPos pos : new BlockPos[] {s.origin, s.second}) {
                        s.level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                                .setValue(PanelBlock.FACING, FACING), 3);
                    }
                    body.accept(s);
                } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
                    throw e;
                } catch (RuntimeException | Error e) {
                    helper.fail("unexpected " + e);
                } finally {
                    s.release();
                }
                helper.succeed();
            });
        }

        void hold(boolean force) {
            for (int x = (origin.getX() - 8) >> 4; x <= (origin.getX() + 8) >> 4; x++) {
                for (int z = (origin.getZ() - 8) >> 4; z <= (origin.getZ() + 8) >> 4; z++) {
                    FixtureChunks.force(level, origin, x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        void clear() {
            for (int x = -3; x <= 3; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -3; z <= 3; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(origin).inflate(8))) {
                item.discard();
            }
        }

        void release() {
            hold(true);
            clear();
            hold(false);
        }

        PanelBlockEntity be() {
            return (PanelBlockEntity) level.getBlockEntity(origin);
        }

        PanelBlockEntity beSecond() {
            return (PanelBlockEntity) level.getBlockEntity(second);
        }

        PanelComponent place(BlockPos block, ComponentType type, int x, int y, Footprint size) {
            PanelComponent placed = PanelOps.placeDirect(level, block, x, y, type, size);
            helper.assertTrue(placed != null, "placement refused " + type.id() + " at " + x + "," + y);
            return placed;
        }

        PanelComponent place(ComponentType type, int x, int y, Footprint size) {
            return place(origin, type, x, y, size);
        }

        void wrench() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void panelStyleIsConfiguredByWrenchSavedAndSynced(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            PanelStyles.overrideShipped(id -> true);
            try {
                PanelComponent sw = s.place(ComponentTypes.CONTROL_SWITCH, 0, 0, Footprint.of(8, 12));
                PanelComponent gauge = s.place(s.second, ComponentTypes.DIAL_GAUGE, 0, 0, Footprint.of(12, 12));
                s.wrench();
                ControlSwitchData data = (ControlSwitchData) sw.data();
                helper.assertTrue(data.style.equals("ge") && data.variant.equals("pistol"), "a new switch starts as ge");

                CompoundTag edit = new CompoundTag();
                CompoundTag config = new CompoundTag();
                config.putString("Style", "westinghouse");
                config.putString("Variant", "pistol_hd");
                edit.put("Config", config);
                int revision = sw.revision();
                helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "configure");
                helper.assertTrue(data.style.equals("westinghouse") && data.variant.equals("pistol_hd"), "style applied: " + data.toTag());
                helper.assertTrue(sw.revision() > revision, "the look change redraws the static mesh");

                // Anything not offered is refused field by field; the rest of the edit still applies.
                CompoundTag bad = new CompoundTag();
                CompoundTag badConfig = new CompoundTag();
                badConfig.putString("Style", "bauhaus");
                badConfig.putString("Variant", "../x");
                badConfig.putString("CloseText", "TRIP");
                bad.put("Config", badConfig);
                PanelOps.configure(s.player, s.origin, sw.id(), bad);
                helper.assertTrue(data.style.equals("westinghouse") && data.variant.equals("pistol_hd") && data.closeText.equals("TRIP"),
                        "bad enum values are ignored: " + data.toTag());
                // A variant of the type that is not the style's own is accepted and stored (the model falls back at draw time).
                CompoundTag cross = new CompoundTag();
                CompoundTag crossConfig = new CompoundTag();
                crossConfig.putString("Style", "ge");
                cross.put("Config", crossConfig);
                PanelOps.configure(s.player, s.origin, sw.id(), cross);
                helper.assertTrue(data.style.equals("ge") && data.variant.equals("pistol_hd"), "variant is kept across a style change");
                crossConfig.putString("Style", "westinghouse");
                PanelOps.configure(s.player, s.origin, sw.id(), cross);

                GaugeData gaugeData = (GaugeData) gauge.data();
                CompoundTag pointer = new CompoundTag();
                CompoundTag pointerConfig = new CompoundTag();
                pointerConfig.putString("Variant", "red_pointer");
                pointer.put("Config", pointerConfig);
                PanelOps.configure(s.player, s.second, gauge.id(), pointer);
                helper.assertTrue(gaugeData.variant.equals("red_pointer") && gaugeData.style.equals("ge"), "dial pointer variant");

                // Persistence: world save and the network form both round trip.
                PanelBlockEntity be = s.be();
                for (boolean network : new boolean[] {false, true}) {
                    CompoundTag saved = be.savePanel(network);
                    be.loadPanel(saved);
                    PanelComponent reloadedSwitch = be.component(sw.id());
                    ControlSwitchData reloaded = (ControlSwitchData) reloadedSwitch.data();
                    helper.assertTrue(reloaded.style.equals("westinghouse") && reloaded.variant.equals("pistol_hd")
                            && reloaded.closeText.equals("TRIP"), (network ? "network" : "world") + " reload keeps the look: " + reloaded.toTag());
                    PanelBlockEntity gaugeBlock = s.beSecond();
                    gaugeBlock.loadPanel(gaugeBlock.savePanel(network));
                    GaugeData reloadedGauge = (GaugeData) gaugeBlock.component(gauge.id()).data();
                    helper.assertTrue(reloadedGauge.variant.equals("red_pointer") && reloadedGauge.type.equals("dial_gauge")
                            && reloadedGauge.style.equals("ge"), "gauge look reloads");
                }
                // A block entity saved before baked models (no Style anywhere) loads as classic.
                CompoundTag legacy = be.savePanel(false);
                var components = legacy.getList("Components", 10);
                for (int i = 0; i < components.size(); i++) {
                    CompoundTag dataTag = components.getCompound(i).getCompound("Data");
                    dataTag.remove("Style");
                    dataTag.remove("Variant");
                    dataTag.remove("LastOperated");
                }
                be.loadPanel(legacy);
                CompoundTag legacyGauge = s.beSecond().savePanel(false);
                var gaugeComponents = legacyGauge.getList("Components", 10);
                for (int i = 0; i < gaugeComponents.size(); i++) {
                    gaugeComponents.getCompound(i).getCompound("Data").remove("Style");
                    gaugeComponents.getCompound(i).getCompound("Data").remove("Variant");
                }
                s.beSecond().loadPanel(legacyGauge);
                helper.assertTrue(((ControlSwitchData) be.component(sw.id()).data()).style.equals("classic")
                        && ((GaugeData) s.beSecond().component(gauge.id()).data()).style.equals("classic"), "legacy save loads as classic");
            } finally {
                PanelStyles.overrideShipped(null);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void panelControlSwitchLatchesItsLastOperationForTheTargetFlag(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            PanelComponent held = s.place(ComponentTypes.CONTROL_SWITCH, 0, 0, Footprint.of(8, 12));
            PanelComponent spring = held;
            ControlSwitchData data = (ControlSwitchData) spring.data();
            ComponentType type = spring.type();
            PanelBlockEntity be = s.be();
            ComponentType.OperateContext context = new ComponentType.OperateContext(s.level, s.player, be, spring);
            helper.assertTrue(data.spring && data.lastOperated == 0, "a new switch has never been operated");
            helper.assertTrue(FlagState.colour(false, false, data.lastOperated) == FlagState.NEUTRAL, "so its flag is neutral");

            type.operate(context, OperateAction.PRESS, 1.0);
            helper.assertTrue(spring.output(0) == 1.0 && data.lastOperated == 1, "OPEN latches +1");
            type.operate(context, OperateAction.RELEASE, 0.0);
            helper.assertTrue(spring.output(0) == 0.0 && data.position == 0 && data.lastOperated == 1, "the spring return keeps the latch");
            helper.assertTrue(FlagState.colour(false, false, data.lastOperated) == FlagState.GREEN, "flag green after OPEN (TRIP)");
            type.operate(context, OperateAction.PRESS, -1.0);
            helper.assertTrue(data.lastOperated == -1, "CLOSE latches -1");
            type.operate(context, OperateAction.RELEASE, 0.0);
            helper.assertTrue(data.lastOperated == -1 && FlagState.colour(false, false, data.lastOperated) == FlagState.RED, "flag red after CLOSE");
            type.operate(context, OperateAction.TURN, 1.0);
            helper.assertTrue(data.lastOperated == 1, "a scroll flick counts");
            type.operate(context, OperateAction.RELEASE, 0.0);

            // It is saved with the data, in the world form and the network form, and survives a reload of the block.
            helper.assertTrue(data.toTag().getInt("LastOperated") == 1, "saved: " + data.toTag());
            for (boolean network : new boolean[] {false, true}) {
                be.loadPanel(be.savePanel(network));
                helper.assertTrue(((ControlSwitchData) be.component(spring.id()).data()).lastOperated == 1,
                        (network ? "network" : "world") + " reload keeps the latch");
            }

            // A client applying the output delta ends with the same latch, without any full sync.
            PanelComponent client = PanelComponent.load(spring.save());
            ControlSwitchData clientData = (ControlSwitchData) client.data();
            clientData.lastOperated = 0;
            client.storeOutput(0, -1.0);
            type.valuesChanged(client);
            helper.assertTrue(clientData.lastOperated == -1, "valuesChanged follows the output on a client");
            client.storeOutput(0, 0.0);
            type.valuesChanged(client);
            helper.assertTrue(clientData.lastOperated == -1, "returning to NORMAL does not clear it");
            // Input changes (the lamps) never touch the latch.
            client.storeInput(0, 1.0);
            type.valuesChanged(client);
            helper.assertTrue(clientData.lastOperated == -1, "lamp inputs leave it alone");

            // A held-position switch from before the latch existed reads its latch from where the handle was left.
            CompoundTag legacy = data.toTag();
            legacy.putBoolean("Spring", false);
            legacy.putInt("Position", 1);
            legacy.remove("LastOperated");
            ControlSwitchData old = new ControlSwitchData();
            old.load(legacy);
            helper.assertTrue(old.position == 1 && old.lastOperated == 0, "the data alone does not invent a latch");
            CompoundTag whole = spring.save();
            whole.put("Data", legacy);
            PanelComponent legacyComponent = PanelComponent.load(whole);
            type.onLoaded(legacyComponent);
            helper.assertTrue(((ControlSwitchData) legacyComponent.data()).lastOperated == 1, "onLoaded derives it from a held position");
            // Garbage in the save is clamped.
            legacy.putInt("LastOperated", 99);
            ControlSwitchData clamp = new ControlSwitchData();
            clamp.load(legacy);
            helper.assertTrue(clamp.lastOperated == 1, "clamped to OPEN");
        });
    }
}
