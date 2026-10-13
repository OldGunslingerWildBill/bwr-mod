package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Baked panel models on sloped panels. A cell is one column wide but {@code vLength} columns long down the face
 * of a benchboard (45 degrees, 1.41) or console (15 degrees, 1.04), so a model scaled into cells without that
 * factor comes out stretched along its rows with its elevation no longer square to what it stands on. These
 * tests take geometry the way the renderer does (a model's parts through {@link PartDraw} and its
 * {@link Placement}, then every corner through the {@link PanelGeometry.Frame} of the panel type and facing) and
 * check that what lands in the world is the model: every edge scaled by the same factor, every angle kept, the
 * model's elevation axis along the face normal, its face plane parallel to the face, and each face's normal still
 * the normal of the face it belongs to. All pure: no world, no client.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelSlopeTests {
    private PanelSlopeTests() {
    }

    /**
     * Tolerances: the geometry is float, the frame double. Lengths are in blocks; a length may be off by this
     * fraction plus a few float steps of a cell coordinate, which matters for the tiny edges of a bevel.
     */
    private static final double LENGTH_TOLERANCE = 2.0e-4;
    private static final double LENGTH_FLOOR = 4.0e-6;
    private static final double ANGLE_TOLERANCE = 2.0e-3;
    /** The face normal check is looser: float noise in a sliver face moves its normal a little. */
    private static final double NORMAL_TOLERANCE = 5.0e-3;
    /** Edges shorter than this (blocks) are too small for float noise not to swamp an angle. */
    private static final double TINY_EDGE = 2.0e-3;

    /** Records the quads a part sends: corners in component space and the component-space normal. */
    private static final class Capture implements QuadSink {
        final List<float[]> corners = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            throw new AssertionError("a baked model sends arbitrary normals, not the five axis ones");
        }

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, float nx, float ny, float nz, boolean emissive) {
            corners.add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
            normals.add(new float[] {nx, ny, nz});
        }
    }

    /**
     * A probe with known geometry (inches, y down): a plate 1.6 x 2.4 x 0.5 standing 0.2 in from the footprint's
     * edges, a lens disc that turns about its centre, and a block that slides 0.5 in down the face.
     */
    private static BakedPanelModel probe() {
        PanelModelFixtures.Model m = new PanelModelFixtures.Model("slope_probe", 2.0, 3.0);
        m.part("body", "paint").box("202020", 0.2, 0.3, 0.0, 1.8, 2.7, 0.5).done();
        m.part("knob", "paint").pivot(1.0, 1.0, 0.5).disc("404040", "505050", 1.0, 1.0, 0.45, 0.5, 0.8, 10).done();
        m.part("slider", "paint").travel(0.5, 0, 1, 0).box("808080", 0.4, 1.8, 0.5, 1.0, 2.2, 0.7).done();
        return PanelModelParser.parse("slope_probe", m.json());
    }

    /** What is measured against the model for one draw of one part. */
    private record Verdict(boolean ok, String why) {
    }

    /**
     * Draws every part of {@code model} (the moving ones turned and slid too) fitted into a {@code w x h} cell
     * rectangle with {@code fitAspect}, maps it through the frame of {@code type} and {@code facing}, and
     * compares it with the model. {@code fitAspect} is what the renderer passes ({@code frame.vLength}); a test
     * can pass another to see that the check notices a model that was fitted as if the cells were square.
     */
    private static Verdict undistorted(BakedPanelModel model, PanelType type, Direction facing, int w, int h, float fitAspect) {
        PanelGeometry.Frame frame = PanelGeometry.frame(type, facing);
        Placement place = Placement.fit(model, w, h, fitAspect);
        // Every model inch is this many blocks, on every axis.
        double blocksPerInch = place.scale / PanelGeometry.GRID;
        float[] scratch = new float[PartDraw.SCRATCH];
        for (int p = 0; p < model.partCount(); p++) {
            BakedPanelModel.Part part = model.part(p);
            float[] angles = part.hasPivot ? new float[] {0, 37, -90} : new float[] {0};
            float[] slides = part.hasTravel ? new float[] {0, 1} : new float[] {0};
            for (float angle : angles) {
                for (float slide : slides) {
                    Capture capture = new Capture();
                    PartDraw.emit(capture, part, place, angle, slide, -1, 1.0f, false, scratch);
                    String at = type + "/" + facing + " " + model.id + "." + part.name + " angle " + angle + " slide " + slide;
                    for (int f = 0; f < capture.corners.size(); f++) {
                        double[][] world = world(frame, capture.corners.get(f));
                        double[][] original = new double[4][3];
                        for (int c = 0; c < 4; c++) {
                            for (int axis = 0; axis < 3; axis++) {
                                original[c][axis] = part.corner(f, c, axis);
                            }
                        }
                        // Edges: same factor everywhere (a rigid motion of the model keeps its edge lengths).
                        for (int c = 0; c < 4; c++) {
                            double modelLength = length(sub(original[(c + 1) % 4], original[c]));
                            double worldLength = length(sub(world[(c + 1) % 4], world[c]));
                            if (modelLength < 1.0e-5) {
                                continue;
                            }
                            double expected = modelLength * blocksPerInch;
                            if (Math.abs(worldLength - expected) > LENGTH_TOLERANCE * expected + LENGTH_FLOOR) {
                                return new Verdict(false, at + " face " + f + " edge " + c + " is " + worldLength / expected
                                        + " times its true length (" + worldLength + " blocks, want " + expected + ")");
                            }
                        }
                        // Angles between consecutive edges are the model's.
                        for (int c = 0; c < 4; c++) {
                            double[] a = sub(original[(c + 1) % 4], original[c]);
                            double[] b = sub(original[(c + 2) % 4], original[(c + 1) % 4]);
                            double[] wa = sub(world[(c + 1) % 4], world[c]);
                            double[] wb = sub(world[(c + 2) % 4], world[(c + 1) % 4]);
                            if (length(wa) < TINY_EDGE || length(wb) < TINY_EDGE) {
                                continue;
                            }
                            double want = dot(a, b) / (length(a) * length(b));
                            double got = dot(wa, wb) / (length(wa) * length(wb));
                            if (Math.abs(want - got) > ANGLE_TOLERANCE) {
                                return new Verdict(false, at + " face " + f + " corner " + c + " has cosine " + got
                                        + ", the model's is " + want);
                            }
                        }
                        // An edge along the model's z (elevation) runs along the face normal; one in the face
                        // plane lies in the face (square to the normal).
                        double[] normal = {frame.nx, frame.ny, frame.nz};
                        for (int c = 0; c < 4; c++) {
                            double[] model3 = sub(original[(c + 1) % 4], original[c]);
                            double[] w3 = sub(world[(c + 1) % 4], world[c]);
                            double wl = length(w3);
                            if (wl < TINY_EDGE) {
                                continue;
                            }
                            // The motion of a part is a rotation about z and a slide along an axis in the face
                            // plane, so a model edge's z extent is what decides along or across the normal.
                            if (angle == 0 && slide == 0) {
                                double alongNormal = dot(w3, normal) / wl;
                                if (Math.abs(model3[0]) < 1.0e-6 && Math.abs(model3[1]) < 1.0e-6
                                        && Math.abs(Math.abs(alongNormal) - 1.0) > ANGLE_TOLERANCE) {
                                    return new Verdict(false, at + " face " + f + " edge " + c
                                            + " is a model elevation edge but is " + alongNormal + " along the face normal");
                                }
                                if (Math.abs(model3[2]) < 1.0e-6 && Math.abs(alongNormal) > ANGLE_TOLERANCE) {
                                    return new Verdict(false, at + " face " + f + " edge " + c
                                            + " is in the model's face plane but is " + alongNormal + " along the face normal");
                                }
                            }
                        }
                        // The normal the renderer uses (the model's, mapped by the frame's axes) is the normal of
                        // the face as it lands in the world.
                        float[] cn = capture.normals.get(f);
                        double[] mapped = {frame.directionX(cn[0], cn[1], cn[2]), frame.directionY(cn[0], cn[1], cn[2]),
                                frame.directionZ(cn[0], cn[1], cn[2])};
                        double[] geometric = cross(sub(world[2], world[0]), sub(world[3], world[1]));
                        double gl = length(geometric);
                        if (gl > 4.0 * TINY_EDGE * TINY_EDGE) {
                            double agree = Math.abs(dot(mapped, geometric) / (length(mapped) * gl));
                            if (Math.abs(agree - 1.0) > NORMAL_TOLERANCE) {
                                return new Verdict(false, at + " face " + f + " is lit as if its normal were "
                                        + java.util.Arrays.toString(mapped) + " but it faces " + java.util.Arrays.toString(geometric));
                            }
                        }
                    }
                }
            }
        }
        return new Verdict(true, "");
    }

    /** Block-space corners of a recorded quad: component space through {@link PanelGeometry.Frame}. */
    private static double[][] world(PanelGeometry.Frame frame, float[] corners) {
        double[][] out = new double[4][3];
        for (int c = 0; c < 4; c++) {
            out[c][0] = frame.x(corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2]);
            out[c][1] = frame.y(corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2]);
            out[c][2] = frame.z(corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2]);
        }
        return out;
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    // ---- the tests

    @GameTest(template = "empty")
    public static void panelFramesAreOrthonormalSoNormalsMapCleanly(GameTestHelper helper) {
        for (PanelType type : PanelType.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame f = PanelGeometry.frame(type, facing);
                double[] right = {f.rightX, f.rightY, f.rightZ};
                double[] down = {f.downX, f.downY, f.downZ};
                double[] normal = {f.nx, f.ny, f.nz};
                String at = type + "/" + facing;
                helper.assertTrue(Math.abs(length(right) - 1) < 1e-9 && Math.abs(length(down) - 1) < 1e-9
                        && Math.abs(length(normal) - 1) < 1e-9, at + ": unit axes");
                helper.assertTrue(Math.abs(dot(right, down)) < 1e-9 && Math.abs(dot(right, normal)) < 1e-9
                        && Math.abs(dot(down, normal)) < 1e-9, at + ": mutually square");
                // A cell is a column wide and vLength columns long; elevation is in the same unit as a column.
                double column = length(new double[] {f.ux, f.uy, f.uz});
                double row = length(new double[] {f.vx, f.vy, f.vz});
                helper.assertTrue(Math.abs(row / column - f.vLength) < 1e-9, at + ": vLength is the row to column ratio");
                helper.assertTrue(Math.abs(length(new double[] {f.x(0, 0, 1) - f.x(0, 0, 0), f.y(0, 0, 1) - f.y(0, 0, 0),
                        f.z(0, 0, 1) - f.z(0, 0, 0)}) - column) < 1e-9, at + ": one unit of elevation is one column wide");
                // The mapping of a component-space direction.
                helper.assertTrue(Math.abs(f.directionX(0, 0, 1) - f.nx) < 1e-12 && Math.abs(f.directionY(0, 1, 0) - f.downY) < 1e-12
                        && Math.abs(f.directionZ(1, 0, 0) - f.rightZ) < 1e-12, at + ": direction mapping");
            }
        }
        helper.assertTrue(Math.abs(PanelGeometry.frame(PanelType.VERTICAL, Direction.NORTH).vLength - 1.0) < 1e-9
                && Math.abs(PanelGeometry.frame(PanelType.BENCHBOARD, Direction.NORTH).vLength - Math.sqrt(2.0)) < 1e-9
                && Math.abs(PanelGeometry.frame(PanelType.CONSOLE, Direction.NORTH).vLength
                - 1.0 / Math.cos(Math.toRadians(15.0))) < 1e-9, "row aspect of the three boards");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelFitKeepsRealProportionsOnSlopedFaces(GameTestHelper helper) {
        // A vertical board is the plain uniform fit, unchanged by the aspect argument.
        Placement square = Placement.fit(2.4f, 3.6f, 8, 12);
        Placement viaAspect = Placement.fit(2.4f, 3.6f, 8, 12, 1.0f);
        helper.assertTrue(square.scale == viaAspect.scale && square.rowScale == viaAspect.rowScale
                && square.offsetX == viaAspect.offsetX && square.offsetY == viaAspect.offsetY
                && square.rowScale == square.scale, "aspect 1 is the old fit");
        // A 1:1 model into a 12 x 12 cell rectangle on a 45 degree desk: the rectangle is 12 columns wide and
        // 17 columns long, so the width limits, the model is 12 columns each way (8.5 rows down), centred.
        float aspect = (float) Math.sqrt(2.0);
        Placement bench = Placement.fit(4.0f, 4.0f, 12, 12, aspect);
        helper.assertTrue(near(bench.scale, 3.0, 1e-5) && near(bench.rowScale, 3.0 / aspect, 1e-5)
                && near(bench.offsetX, 0, 1e-5) && near(bench.offsetY, (12 - 4 * 3.0 / aspect) / 2, 1e-4), "bench: " + bench.scale
                + " " + bench.rowScale + " " + bench.offsetX + " " + bench.offsetY);
        // Square lengths: a model inch across and a model inch down are the same real length.
        helper.assertTrue(near(bench.x(1) - bench.x(0), (bench.y(1) - bench.y(0)) * aspect, 1e-5), "inch across = inch down");
        // A tall model into a tall rectangle: the height limits and fills the rows exactly.
        Placement tall = Placement.fit(2.0f, 3.0f, 16, 16, aspect);
        helper.assertTrue(near(tall.y(3) - tall.y(0), 16, 1e-4) && near(tall.offsetY, 0, 1e-4)
                && tall.offsetX > 0, "tall model fills the rows and is centred across: " + tall.offsetX + " " + tall.offsetY);
        // Elevation and text size follow the column scale.
        helper.assertTrue(near(tall.z(1), tall.scale, 1e-6) && near(tall.length(1), tall.scale, 1e-6), "z and length use the column scale");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelBakedGeometryIsUndistortedOnEveryPanelType(GameTestHelper helper) {
        BakedPanelModel model = probe();
        int[][] rectangles = {{12, 12}, {8, 12}, {16, 16}, {6, 16}, {16, 6}};
        for (PanelType type : PanelType.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                float aspect = (float) PanelGeometry.frame(type, facing).vLength;
                for (int[] rect : rectangles) {
                    Verdict verdict = undistorted(model, type, facing, rect[0], rect[1], aspect);
                    helper.assertTrue(verdict.ok(), "fitted with the panel's row aspect: " + verdict.why());
                }
            }
        }
        // The check is not vacuous: a model fitted as if the cells were square (the old behaviour) is stretched.
        for (PanelType type : new PanelType[] {PanelType.BENCHBOARD, PanelType.CONSOLE}) {
            Verdict stretched = undistorted(model, type, Direction.NORTH, 12, 12, 1.0f);
            helper.assertTrue(!stretched.ok(), type + " with square-cell fitting must be reported as stretched");
        }
        helper.assertTrue(undistorted(model, PanelType.VERTICAL, Direction.NORTH, 12, 12, 1.0f).ok(),
                "a vertical board is square either way");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelShippedModelsAreUndistortedOnEveryPanelTypeAndStayInTheirCells(GameTestHelper helper) throws IOException {
        Set<String> done = new HashSet<>();
        int checked = 0;
        for (PanelStyles.Entry entry : PanelStyles.TABLE) {
            for (String id : entry.models()) {
                if (!done.add(id)) {
                    continue;
                }
                String text;
                try (InputStream in = PanelSlopeTests.class.getResourceAsStream("/assets/bwr/models/panel/" + id + ".json")) {
                    if (in == null) {
                        continue;
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                BakedPanelModel model = PanelModelParser.parse(id, text);
                for (PanelType type : PanelType.values()) {
                    PanelGeometry.Frame frame = PanelGeometry.frame(type, Direction.NORTH);
                    float aspect = (float) frame.vLength;
                    Verdict verdict = undistorted(model, type, Direction.NORTH, 12, 16, aspect);
                    helper.assertTrue(verdict.ok(), verdict.why());
                    // Still inside the component's cells: the fit is into the rectangle's real size.
                    Placement place = Placement.fit(model, 12, 16, aspect);
                    helper.assertTrue(place.offsetX >= -1e-4 && place.offsetY >= -1e-4
                            && place.x(model.footprintW) <= 12 + 1e-3 && place.y(model.footprintH) <= 16 + 1e-3,
                            id + " on " + type + " leaves its cell rectangle");
                }
                checked++;
            }
        }
        com.mojang.logging.LogUtils.getLogger().info("PANEL MODELS checked undistorted on every panel type: {}", checked);
        helper.succeed();
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }
}
