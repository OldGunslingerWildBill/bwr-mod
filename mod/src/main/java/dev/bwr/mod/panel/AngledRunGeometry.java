package dev.bwr.mod.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The modular 45 degree runs (CONTROL-PANELS-SPEC section 14): a console that turns through two 45 degree bends, built
 * one block-piece at a time. A run is <b>bend A</b>, {@code n >= 0} <b>diagonal units</b> and <b>bend B</b>; the straight
 * run before bend A and the one after bend B are the ordinary straight blocks. Two families share the plan: the
 * {@link Family#DESK} (a control desk's console, 0.75 deep, one block high, the level top of each unit a 16 x 10 face) and
 * the {@link Family#TALL} panel (a benchboard's bench at the floor, two vertical boards and a leaning header strip above
 * it: four blocks high, one 16 x 16 face per layer of each unit). Pure maths, no world access; art/models/control_desk/
 * run_plan.py and build_angled_runs.py draw the same numbers, and {@code data/bwr/angled_runs/pieces.json} (written by
 * run_plan.py) holds the cubes of every piece, which this class reads.
 *
 * <h3>The plan</h3>
 * Coordinates are {@code x} to the operator's right, {@code f} from the back toward the operator and {@code y} up, in
 * blocks, for the <b>unmirrored</b> run; a mirrored run (the other hand) is the same plan with {@code x} to the
 * operator's left. The straight run A arrives from {@code +x} (cubes {@code bx >= 1}); bend A is the column
 * {@code bx = 0}; unit {@code k} is the column {@code bx = -k}; bend B is the column {@code bx = -n - 1} down to where the
 * straight run B starts. The diagonal advances one block in {@code x} and one in {@code f} per unit (toward the operator
 * for an <b>inside</b> run, away for an <b>outside</b> one), so a unit is the part of the console between two planes
 * {@code x = const}: the same shape every time, which is what makes a run extendable one unit at a time. The console is a
 * band of constant depth {@code t} (back at 0), the profile a function of {@code t}:
 * <pre>
 *   inside : t = min(f, (x + f - PHI) / sqrt 2, x - xwall)                    xwall = -n - 1
 *   outside: t = max(f, (PHI + tan(22.5) W - (x - f)) / sqrt 2, xwall - x)    xwall = -n
 * </pre>
 * Each piece is a handful of blocks ({@link Part}s, offsets from the piece's origin block); the origin of bend A is
 * the block next to the straight run, a unit's is the block that holds the middle of its lowest face, and the origins of
 * consecutive pieces of a run are exactly {@link #chainStep one chain step} apart.
 */
public final class AngledRunGeometry {
    private AngledRunGeometry() {
    }

    private static final double SQ2 = Math.sqrt(2.0);
    private static final double T22 = Math.tan(Math.toRadians(22.5));
    /** The header strip leans back 5 degrees (the tangent), as the trim's header does. */
    private static final double TB = PanelGeometry.HEADER_TILT_TAN;
    public static final int MAX_PARTS = 10;

    public enum Family {
        DESK("desk", 0.75, 1),
        TALL("tall", 1.0, 4);

        private final String id;
        private final double width;
        private final int layers;

        Family(String id, double width, int layers) {
            this.id = id;
            this.width = width;
            this.layers = layers;
        }

        public String id() {
            return id;
        }

        /** The depth the mitre geometry is made for: the deepest part of the profile. */
        public double width() {
            return width;
        }

        public int layers() {
            return layers;
        }

        /** Depth of the solid in block layer {@code by}. */
        double layerDepth(int by) {
            if (this == DESK) {
                return by == 0 ? 0.75 : 0.0;
            }
            return by == 0 ? 1.0 : (by <= 3 ? 0.25 : 0.0);
        }
    }

    public enum Role {
        BEND_A("a", "bend_a"),
        UNIT("unit", "unit"),
        BEND_B("b", "bend_b");

        private final String json;
        private final String id;

        Role(String json, String id) {
            this.json = json;
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** A block of a piece: blocks to the operator's right (to the left for a mirrored piece), blocks toward the operator, blocks up, from the origin. */
    public record Part(int right, int deep, int up) {
    }

    /** A mounting face of a unit (see {@link #faceFrame}). */
    public record Face(String kind, int rows, int by, double t0, double t1, double y0, double y1, double[] centre, Part core) {
    }

    /** One piece: where its origin lies in the reference chain, its blocks (origin first), the first straight block beyond it, the faces. */
    public record Piece(int[] origin, List<Part> parts, int[] straight, List<Face> faces) {
        public int partIndex(Part part) {
            return parts.indexOf(part);
        }
    }

    // ---- the data

    private static final Map<String, Map<Role, Piece>> PIECES = new ConcurrentHashMap<>();
    private static final Map<String, int[]> CHAINS = new ConcurrentHashMap<>();

    static {
        try (InputStream in = AngledRunGeometry.class.getResourceAsStream("/data/bwr/angled_runs/pieces.json")) {
            if (in == null) {
                throw new IllegalStateException("data/bwr/angled_runs/pieces.json is missing");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject run = entry.getValue().getAsJsonObject();
                Map<Role, Piece> pieces = new EnumMap<>(Role.class);
                for (Role role : Role.values()) {
                    pieces.put(role, readPiece(run.getAsJsonObject(role.json)));
                }
                PIECES.put(entry.getKey(), pieces);
                CHAINS.put(entry.getKey(), ints(run.getAsJsonObject("chain").getAsJsonArray("step")));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsInt();
        }
        return out;
    }

    private static Part part(JsonArray a) {
        return new Part(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static Piece readPiece(JsonObject o) {
        List<Part> parts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("parts")) {
            parts.add(part(e.getAsJsonArray()));
        }
        List<Face> faces = new ArrayList<>();
        if (o.has("faces")) {
            for (JsonElement e : o.getAsJsonArray("faces")) {
                JsonObject f = e.getAsJsonObject();
                JsonArray c = f.getAsJsonArray("centre");
                faces.add(new Face(f.get("kind").getAsString(), f.get("rows").getAsInt(), f.get("by").getAsInt(),
                        f.get("t0").getAsDouble(), f.get("t1").getAsDouble(), f.get("y0").getAsDouble(),
                        f.get("y1").getAsDouble(), new double[] {c.get(0).getAsDouble(), c.get(1).getAsDouble(),
                        c.get(2).getAsDouble()}, part(f.getAsJsonArray("core"))));
            }
        }
        return new Piece(ints(o.getAsJsonArray("origin")), List.copyOf(parts),
                o.has("straight") ? ints(o.getAsJsonArray("straight")) : null, List.copyOf(faces));
    }

    private static String key(Family family, boolean inside) {
        return family.id + "_" + (inside ? "inside" : "outside");
    }

    public static Piece piece(Family family, boolean inside, Role role) {
        return PIECES.get(key(family, inside)).get(role);
    }

    /** Offset {@code (bx, bf)} between the origins of consecutive pieces of a run, from bend A toward bend B. */
    public static int[] chainStep(boolean inside) {
        return new int[] {-1, inside ? 1 : -1};
    }

    /** Where the mitre starts (the x of its leftmost point) in the unmirrored plan. */
    public static double phi(Family family, boolean inside) {
        if (family == Family.DESK) {
            return inside ? 6.0 / 16.0 : 5.0 / 16.0;
        }
        return inside ? 8.0 / 16.0 : 1.0 / 16.0;
    }

    /** Depth of the console at a plan point: negative or past the family's width when the point is not on it. */
    public static double depth(Family family, boolean inside, double x, double f, double wall) {
        double phi = phi(family, inside);
        if (inside) {
            return Math.min(Math.min(f, (x + f - phi) / SQ2), x - wall);
        }
        return Math.max(Math.max(f, (phi + T22 * family.width - (x - f)) / SQ2), wall - x);
    }

    /** The straight run B's wall for the pieces of the reference chain: bend B is at {@code n = 0}, the others far from it. */
    private static double wall(boolean inside, Role role) {
        if (role == Role.BEND_B) {
            return inside ? -1.0 : 0.0;
        }
        return inside ? -4.0 : -3.0;
    }

    /** Absolute cube {@code (bx, bf, by)} of a part of a piece in the reference chain. */
    public static int[] cube(Family family, boolean inside, Role role, int part) {
        Piece piece = piece(family, inside, role);
        Part p = piece.parts.get(part);
        return new int[] {piece.origin[0] + p.right, piece.origin[1] + p.deep, piece.origin[2] + p.up};
    }

    /** The world direction a piece's local {@code x} (to the operator's right unmirrored) points. */
    public static Direction right(Direction facing, boolean mirror) {
        return mirror ? facing.getClockWise() : facing.getCounterClockWise();
    }

    // ---- the faces

    private static final Map<String, PanelGeometry.Frame> FRAMES = new ConcurrentHashMap<>();

    public static PanelType faceType(Face face) {
        return switch (face.kind) {
            case "desk" -> PanelType.DESK_RUN;
            case "bench" -> PanelType.RUN_BENCH;
            case "vertical" -> PanelType.RUN_VERTICAL;
            default -> PanelType.RUN_HEADER;
        };
    }

    /** The face of a unit whose middle lies in part {@code part}, or {@code null} for a block with none. */
    public static Face faceOfPart(Family family, boolean inside, Role role, int part) {
        if (role != Role.UNIT) {
            return null;
        }
        Part p = piece(family, inside, role).parts.get(part);
        for (Face face : piece(family, inside, role).faces) {
            if (face.core.equals(p)) {
                return face;
            }
        }
        return null;
    }

    /** The plan unit steps in {@code (x, f)}: along the columns (before mirroring) and toward the operator (the face's plan normal). */
    public static double[][] planDirections(boolean inside) {
        double r = Math.sqrt(0.5);
        return inside ? new double[][] {{r, -r}, {r, r}} : new double[][] {{r, r}, {-r, r}};
    }

    /**
     * The frame of a face of a unit in the local frame of the block holding its middle (it reaches into other blocks), for a
     * facing and a hand: the columns run to the operator's right, the rows down the face (toward the operator along the plan
     * normal, and down by the face's height step), cell {@code (0, 0)} at the top left.
     */
    public static PanelGeometry.Frame faceFrame(Family family, boolean inside, Face face, Direction facing, boolean mirror) {
        return FRAMES.computeIfAbsent(key(family, inside) + ":" + face.by + ":" + facing + ":" + mirror, k -> {
            Piece unit = piece(family, inside, Role.UNIT);
            double[][] dirs = planDirections(inside);
            double sign = mirror ? -1.0 : 1.0;                       // a mirrored hand reads its columns the other way along the diagonal
            double ux = dirs[0][0] * sign / PanelGeometry.GRID;
            double uf = dirs[0][1] * sign / PanelGeometry.GRID;
            double dt = (face.t1 - face.t0) / face.rows;
            double dy = (face.y1 - face.y0) / face.rows;
            double vx = dirs[1][0] * dt;
            double vf = dirs[1][1] * dt;
            // the normal: horizontal part toward the operator, up by the depth step
            double nh = -dy;
            double nu = dt;
            double len = Math.sqrt(nh * nh + nu * nu);
            nh /= len;
            nu /= len;
            double cx = face.centre[0] - (unit.origin[0] + face.core.right);
            double cf = face.centre[1] - (unit.origin[1] + face.core.deep);
            double cy = face.centre[2] - (unit.origin[2] + face.core.up);
            double half = PanelGeometry.GRID / 2.0;
            double rowHalf = face.rows / 2.0;
            double lx = cx - ux * half - vx * rowHalf;
            double lf = cf - uf * half - vf * rowHalf;
            double ly = cy - dy * rowHalf;
            Direction right = right(facing, mirror);
            return new PanelGeometry.Frame(
                    0.5 + (lx - 0.5) * right.getStepX() + (lf - 0.5) * facing.getStepX(), ly,
                    0.5 + (lx - 0.5) * right.getStepZ() + (lf - 0.5) * facing.getStepZ(),
                    ux * right.getStepX() + uf * facing.getStepX(), 0.0, ux * right.getStepZ() + uf * facing.getStepZ(),
                    vx * right.getStepX() + vf * facing.getStepX(), dy, vx * right.getStepZ() + vf * facing.getStepZ(),
                    nh * (dirs[1][0] * right.getStepX() + dirs[1][1] * facing.getStepX()), nu,
                    nh * (dirs[1][0] * right.getStepZ() + dirs[1][1] * facing.getStepZ()));
        });
    }

    // ---- shapes

    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    /** The collision and outline shape of one block of a piece, for a facing and a hand (built on first use, then kept). */
    public static VoxelShape shape(Family family, boolean inside, Role role, int part, Direction facing, boolean mirror) {
        return SHAPES.computeIfAbsent(key(family, inside) + ":" + role + ":" + part + ":" + facing + ":" + mirror,
                k -> buildShape(family, inside, role, part, facing, mirror));
    }

    private static VoxelShape buildShape(Family family, boolean inside, Role role, int part, Direction facing, boolean mirror) {
        int n = 16;
        int[] cube = cube(family, inside, role, part);
        int bx = cube[0];
        int bf = cube[1];
        int by = cube[2];
        double wall = wall(inside, role);
        double layerDepth = family.layerDepth(by);
        double[][] lo = new double[n][n];
        double[][] hi = new double[n][n];
        boolean[][] on = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double tMin = Double.MAX_VALUE;
                boolean any = false;
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        double t = depth(family, inside, bx + (i + a / 2.0) / n, bf + (j + b / 2.0) / n, wall);
                        if (t >= -1.0e-9 && t <= layerDepth + 1.0e-9) {
                            tMin = Math.min(tMin, Math.max(0.0, t));
                            any = true;
                        }
                    }
                }
                if (!any) {
                    continue;
                }
                on[i][j] = true;
                if (family == Family.DESK) {
                    double ylo = Double.MAX_VALUE;
                    for (int a = 0; a < 3; a++) {
                        for (int b = 0; b < 3; b++) {
                            double t = depth(family, inside, bx + (i + a / 2.0) / n, bf + (j + b / 2.0) / n, wall);
                            if (t >= -1.0e-9 && t <= layerDepth + 1.0e-9) {
                                ylo = Math.min(ylo, DeskCornerGeometry.yLow(Math.max(0, t)));
                            }
                        }
                    }
                    double tMid = depth(family, inside, bx + (i + 0.5) / n, bf + (j + 0.5) / n, wall);
                    lo[i][j] = Math.floor(ylo * 16.0 - 1.0e-9) / 16.0;
                    hi[i][j] = Math.ceil(DeskCornerGeometry.yHigh(Math.min(family.width, Math.max(0, tMid))) * 16.0 - 1.0e-9) / 16.0;
                } else if (by == 0) {
                    // the bench: the wedge under the 45 degree face, up to the face at the back of the cell
                    lo[i][j] = 0.0;
                    hi[i][j] = Math.ceil((1.0 - Math.min(1.0, tMin)) * 16.0 - 1.0e-9) / 16.0;
                } else {
                    lo[i][j] = 0.0;
                    hi[i][j] = 1.0;
                }
            }
        }
        List<double[]> boxes = new ArrayList<>();
        boolean[][] used = new boolean[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (!on[i][j] || used[i][j]) {
                    continue;
                }
                int i1 = i + 1;
                while (i1 < n && on[i1][j] && !used[i1][j] && lo[i1][j] == lo[i][j] && hi[i1][j] == hi[i][j]) {
                    i1++;
                }
                int j1 = j + 1;
                outer:
                while (j1 < n) {
                    for (int k = i; k < i1; k++) {
                        if (!on[k][j1] || used[k][j1] || lo[k][j1] != lo[i][j] || hi[k][j1] != hi[i][j]) {
                            break outer;
                        }
                    }
                    j1++;
                }
                for (int k = i; k < i1; k++) {
                    for (int m = j; m < j1; m++) {
                        used[k][m] = true;
                    }
                }
                if (hi[i][j] > lo[i][j]) {
                    boxes.add(new double[] {i / (double) n, lo[i][j], j / (double) n, i1 / (double) n, hi[i][j], j1 / (double) n});
                }
            }
        }
        Direction right = right(facing, mirror);
        VoxelShape shape = Shapes.empty();
        for (double[] box : boxes) {
            double xa = wx(right, facing, box[0], box[2]);
            double xb = wx(right, facing, box[3], box[5]);
            double za = wz(right, facing, box[0], box[2]);
            double zb = wz(right, facing, box[3], box[5]);
            shape = Shapes.joinUnoptimized(shape, Shapes.box(Math.min(xa, xb), box[1], Math.min(za, zb),
                    Math.max(xa, xb), box[4], Math.max(za, zb)), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static double wx(Direction right, Direction facing, double lx, double f) {
        return 0.5 + (lx - 0.5) * right.getStepX() + (f - 0.5) * facing.getStepX();
    }

    private static double wz(Direction right, Direction facing, double lx, double f) {
        return 0.5 + (lx - 0.5) * right.getStepZ() + (f - 0.5) * facing.getStepZ();
    }
}
