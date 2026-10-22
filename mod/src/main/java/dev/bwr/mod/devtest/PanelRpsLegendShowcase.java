package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.PanelRenderContext;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RpsTripData;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The RPS manual scram legends on every kind of panel face, part of the panel client check. The owner reported that the
 * legends of the scram control ("A1 B1 A2 B2" under the guarded buttons, "MAN SCRAM" on its plate) look slanted, like
 * italics. This stage set answers whether that is a fault of the text or just how a sloped face is seen.
 *
 * <p>Each of ten panel faces (vertical board, benchboard, console, back panel, duplex bench, duplex header, desk, header
 * strip, vertical chamfer, bench chamfer) gets the same fitting: a plate "MAN SCRAM" over two RPS trip assemblies with the
 * legends A1 B1 and A2 B2, as the owner's board had it (the duplex column also carries the board's "WHITE LAMP LIT" plate
 * on the vertical part above the bench). Every face is photographed twice at 1920 x 1080: straight on (the camera on the
 * normal of the face), and obliquely, from the operator's left and low, like the owner's picture.
 *
 * <p>What the check asserts, besides the usual block and component counts:
 * <ul>
 *   <li>{@link #verifyBasisMath}: for every panel type, facing, mount angle (rotation of the legend about its anchor), and
 *       tilt, the text basis the renderer builds is orthogonal with equal axis lengths, not mirrored, and lies in the face's
 *       plane (z into it). That rules out a sheared or squashed legend (a transform fault) and glyphs on a plane that is not
 *       the face.</li>
 *   <li>the same audit on every legend the renderer actually drew in the run ({@link PanelRenderContext#debugBasisAudit});</li>
 *   <li>the face the legends are laid on is a face of the block's own model: the plate corners lie on a model quad of the
 *       same orientation, not behind it by more than {@link #PLANE_TOLERANCE} and not in front of it by more than
 *       {@link #RECESS_ALLOWANCE}, so glyphs are on the surface that is drawn;</li>
 *   <li>the view: the lean of the glyph verticals on the screen is 0 when seen straight on, 0 on any upright face (a
 *       vertical line stays vertical in a camera that does not roll), and large on a sloped face seen obliquely. That lean is
 *       perspective: it is the lean of the plate's own edges.</li>
 * </ul>
 *
 * <p>Plan: every block faces north (the operator stands at smaller z), the floor is at y 120 under plinths up to the panels at y 126, one column of the row per
 * face at x 5900 + 14 * column, z 5400.
 */
final class PanelRpsLegendShowcase {
    private PanelRpsLegendShowcase() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction FACING = Direction.NORTH;
    private static final int BASE_X = 5900;
    private static final int Z = 5400;
    /** The floor is low so that the camera of the straight-on pictures, at the height of the panels, stands in air; each block stands on a plinth. */
    private static final int FLOOR = 120;
    private static final int PANEL_Y = 126;
    /** How far a plate corner may sink into its model's face, in blocks: under the text's own hair of elevation (0.06 / 16). */
    static final double PLANE_TOLERANCE = 0.003;
    /**
     * How far in front of a model face the mounting plane may stand: one texel (1/16 block), the depth of a recessed field in a
     * bezel (the header strip's, which is 0.05 deep). Standing behind the face is never allowed.
     */
    static final double RECESS_ALLOWANCE = 0.0625;
    /** Eye height of the player the camera is held for. */
    private static final double EYE_HEIGHT = 1.62;
    /** Field of view and picture of the panel client check. */
    private static final double FOV_DEGREES = 60.0;

    private static final PanelType[] TYPES = {PanelType.VERTICAL, PanelType.BENCHBOARD, PanelType.CONSOLE, PanelType.BACK_PANEL,
            PanelType.DUPLEX_BENCH, PanelType.DUPLEX_HEADER, PanelType.DESK, PanelType.HEADER_TILT, PanelType.CHAMFER_VERTICAL,
            PanelType.CHAMFER_BENCH};
    private static final String[] TYPE_NAMES = {"vertical", "benchboard", "console", "back", "duplex-bench", "duplex-header", "desk",
            "header-tilt", "chamfer-vertical", "chamfer-bench"};
    /** The column of the row each face is in: the duplex bench and its header are the same column. */
    private static final int[] COLUMN = {0, 1, 2, 3, 4, 4, 5, 6, 7, 8};
    /** Faces whose rows run straight down (a vertical line on them is a vertical line in the world). */
    private static boolean upright(PanelType type) {
        return type == PanelType.VERTICAL || type == PanelType.BACK_PANEL || type == PanelType.DUPLEX_BOARD
                || type == PanelType.CHAMFER_VERTICAL;
    }

    static final String[] NAMES = names();

    private static String[] names() {
        String[] all = new String[TYPES.length * 2];
        for (int i = 0; i < TYPES.length; i++) {
            all[i * 2] = "rps-legends-" + TYPE_NAMES[i] + "-front";
            all[i * 2 + 1] = "rps-legends-" + TYPE_NAMES[i] + "-oblique";
        }
        return all;
    }

    // ---- where things are

    private static BlockPos benchOrigin(int column) {
        return new BlockPos(BASE_X + 14 * column, PANEL_Y, Z);
    }

    /** The block that holds the mounting face of face {@code type}. */
    private static BlockPos holder(int type) {
        BlockPos origin = benchOrigin(COLUMN[type]);
        return switch (TYPES[type]) {
            case DUPLEX_BENCH -> origin;
            case DUPLEX_HEADER -> DuplexCell.HEADER.at(origin, FACING);
            default -> origin;
        };
    }

    /** Cell columns of the plate, the first assembly and the second assembly, for each face (a vertical chamfer has 11 columns). */
    private static int[] fitting(PanelType type) {
        return type == PanelType.CHAMFER_VERTICAL ? new int[] {1, 0, 5} : new int[] {4, 2, 10};
    }

    /** The cell column the camera looks at: the middle of the fitting. */
    private static double lookColumn(PanelType type) {
        return type == PanelType.CHAMFER_VERTICAL ? 4.5 : 8.0;
    }

    private static Vec3 lookPoint(int type) {
        PanelGeometry.Frame frame = PanelGeometry.frame(TYPES[type], FACING);
        BlockPos pos = holder(type);
        double cu = lookColumn(TYPES[type]);
        double cv = 3.5;
        return new Vec3(pos.getX() + frame.x(cu, cv, 0.0), pos.getY() + frame.y(cu, cv, 0.0), pos.getZ() + frame.z(cu, cv, 0.0));
    }

    /** Where the camera (eye) is: on the face's normal, or from the operator's left, low, about the owner's angle. */
    private static Vec3 eye(int stage) {
        int type = stage / 2;
        boolean oblique = stage % 2 == 1;
        PanelGeometry.Frame frame = PanelGeometry.frame(TYPES[type], FACING);
        Vec3 look = lookPoint(type);
        if (!oblique) {
            double distance = 0.85;
            double lean = 0.0;
            if (Math.abs(frame.ny) > 0.99) {
                // a level face (the desk top) seen along its normal looks straight down, where the camera's yaw, and so which way
                // the picture is up, is undefined: stand a little toward the operator so that the rows run up the picture
                lean = 0.25;
            }
            return look.add(frame.nx * distance + FACING.getStepX() * lean * distance, frame.ny * distance,
                    frame.nz * distance + FACING.getStepZ() * lean * distance);
        }
        double distance = 1.15;
        double bearing = Math.toRadians(60.0);
        double elevation = Math.toRadians(10.0);
        // 60 degrees to the left of the face's own horizontal normal (the operator's side for a board, bench or desk; a chamfer's
        // normal is diagonal), so the camera is always on the visible side of the face
        double nh = Math.hypot(frame.nx, frame.nz);
        double hnx = nh < 0.5 ? FACING.getStepX() : frame.nx / nh;
        double hnz = nh < 0.5 ? FACING.getStepZ() : frame.nz / nh;
        double rh = Math.hypot(frame.rightX, frame.rightZ);
        double hx = hnx * Math.cos(bearing) - frame.rightX / rh * Math.sin(bearing);
        double hz = hnz * Math.cos(bearing) - frame.rightZ / rh * Math.sin(bearing);
        return look.add(distance * Math.cos(elevation) * hx, distance * Math.sin(elevation), distance * Math.cos(elevation) * hz);
    }

    static boolean night(int stage) {
        return false;
    }

    static Vec3 camera(int stage) {
        Vec3 eye = eye(stage);
        return new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z);
    }

    static Vec3 target(int stage) {
        return lookPoint(stage / 2);
    }

    // ---- building (server)

    /** Every panel block placed, with the column of the row it is in; the components mounted in each column. */
    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static final List<Integer> PLACED_COLUMN = new ArrayList<>();
    private static final int[] MOUNTED = new int[9];
    private static int buildColumn;

    private static PanelComponent put(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type, Footprint size) {
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, size);
        if (component == null) {
            throw new AssertionError("RPS legend showcase: placement refused " + type.id() + " " + size.w() + "x" + size.h() + " at "
                    + pos + " " + cx + "," + cy);
        }
        MOUNTED[buildColumn]++;
        return component;
    }

    private static void plate(ServerLevel level, BlockPos pos, int cx, int cy, int w, int h, String text) {
        PanelComponent plate = put(level, pos, cx, cy, ComponentTypes.NAMEPLATE, Footprint.of(w, h));
        NameplateData data = (NameplateData) plate.data();
        data.text = text;
        // pale plate, dark engraving: the owner's picture
        data.plate = 0xE6E6DC;
        data.ink = 0x151515;
        plate.touch();
    }

    private static void rps(ServerLevel level, BlockPos pos, int cx, int cy, String a, String b) {
        PanelComponent trip = put(level, pos, cx, cy, ComponentTypes.RPS_TRIP, Footprint.of(4, 2));
        RpsTripData data = (RpsTripData) trip.data();
        data.legendA = a;
        data.legendB = b;
        trip.touch();
    }

    /** The scram fitting: the plate on rows 0 to 3, the A1 B1 and A2 B2 assemblies on rows 4 and 5. */
    private static void fit(ServerLevel level, BlockPos pos, PanelType type) {
        int[] fit = fitting(type);
        plate(level, pos, fit[0], 0, 8, 4, "MAN SCRAM");
        rps(level, pos, fit[1], 4, "A1", "B1");
        rps(level, pos, fit[2], 4, "A2", "B2");
    }

    private static Block block(PanelType type) {
        return switch (type) {
            case VERTICAL -> BwrBlocks.CONTROL_PANEL_VERTICAL.get();
            case BENCHBOARD -> BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
            case CONSOLE -> BwrBlocks.CONTROL_PANEL_CONSOLE.get();
            case BACK_PANEL -> BwrBlocks.CONTROL_PANEL_BACK.get();
            case DESK -> BwrBlocks.CONTROL_PANEL_DESK.get();
            case HEADER_TILT -> BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get();
            case CHAMFER_VERTICAL -> BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_CHAMFER.get();
            case CHAMFER_BENCH -> BwrBlocks.CONTROL_PANEL_CORNER_BENCH_CHAMFER.get();
            default -> throw new IllegalArgumentException(type.name());
        };
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        PLACED_COLUMN.clear();
        java.util.Arrays.fill(MOUNTED, 0);
        for (int x = BASE_X - 10; x <= BASE_X + 14 * 8 + 10; x++) {
            for (int z = Z - 12; z <= Z + 6; z++) {
                level.setBlock(new BlockPos(x, FLOOR, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = FLOOR + 1; y <= PANEL_Y + 10; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int column = 0; column < 9; column++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = FLOOR + 1; y < PANEL_Y; y++) {
                    level.setBlock(benchOrigin(column).atY(y).south(dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                }
            }
        }
        for (int i = 0; i < TYPES.length; i++) {
            PanelType type = TYPES[i];
            if (type == PanelType.DUPLEX_HEADER) {
                continue; // placed with the bench of the same column
            }
            BlockPos origin = benchOrigin(COLUMN[i]);
            buildColumn = COLUMN[i];
            if (type == PanelType.DUPLEX_BENCH) {
                BlockState column = BwrBlocks.CONTROL_PANEL_DUPLEX.get().defaultBlockState().setValue(PanelBlock.FACING, FACING);
                for (DuplexCell cell : DuplexCell.values()) {
                    BlockPos at = cell.at(origin, FACING);
                    level.setBlock(at, column.setValue(DuplexPanelBlock.CELL, cell), 3);
                    if (cell.panelType() != null) {
                        PLACED.add(at);
                        PLACED_COLUMN.add(buildColumn);
                    }
                }
                fit(level, origin, PanelType.DUPLEX_BENCH);
                fit(level, DuplexCell.HEADER.at(origin, FACING), PanelType.DUPLEX_HEADER);
                // the vertical part above the bench: the plate the owner's picture shows over the bench plate
                plate(level, DuplexCell.BOARD_LOWER.at(origin, FACING), 0, 6, 16, 4, "WHITE LAMP LIT");
                continue;
            }
            level.setBlock(origin, block(type).defaultBlockState().setValue(PanelBlock.FACING, FACING), 3);
            PLACED.add(origin);
            PLACED_COLUMN.add(buildColumn);
            fit(level, origin, type);
        }
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /**
     * The orthogonality guard, by arithmetic and for every panel type: the text basis the renderer builds
     * ({@link PanelRenderContext#textBasis} and {@link PanelRenderContext#orientLegend}, the very functions every legend is
     * drawn through) for each facing, each mount angle (the legend's rotation about its anchor, which is how a baked model
     * mounts a legend at an angle) and each surface tilt has orthogonal axes of one length that are not mirrored, and with no
     * tilt lie in the face's plane. Also the frames themselves: the columns, rows and normal are an orthonormal basis.
     */
    static String verifyBasisMath() {
        float[] rotations = {0.0f, 15.0f, -30.0f, 45.0f, 90.0f, 135.0f, 180.0f, -90.0f};
        float[] tilts = {0.0f, 8.0f, -12.0f};
        float[] scales = {0.0625f, 0.5f};
        Matrix4f m = new Matrix4f();
        int checks = 0;
        for (PanelType type : PanelType.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame frame = PanelGeometry.frame(type, facing);
                double[] u = {frame.rightX, frame.rightY, frame.rightZ};
                double[] v = {frame.downX, frame.downY, frame.downZ};
                double[] n = {frame.nx, frame.ny, frame.nz};
                if (Math.abs(len(u) - 1.0) > 1.0e-9 || Math.abs(len(v) - 1.0) > 1.0e-9 || Math.abs(len(n) - 1.0) > 1.0e-9
                        || Math.abs(dot(u, v)) > 1.0e-9 || Math.abs(dot(u, n)) > 1.0e-9 || Math.abs(dot(v, n)) > 1.0e-9) {
                    throw new AssertionError("Panel frame of " + type + " facing " + facing + " is not orthonormal: u.v=" + dot(u, v)
                            + " u.n=" + dot(u, n) + " v.n=" + dot(v, n) + " |u|=" + len(u) + " |v|=" + len(v) + " |n|=" + len(n));
                }
                for (float rotation : rotations) {
                    for (float tilt : tilts) {
                        for (float scale : scales) {
                            PanelRenderContext.textBasis(frame, m);
                            PanelRenderContext.orientLegend(m, (float) frame.vLength, rotation, tilt, scale);
                            String bad = PanelRenderContext.basisViolations(m, frame, tilt != 0.0f);
                            if (!bad.isEmpty()) {
                                throw new AssertionError("Legend text basis is not an orthogonal basis on the face: " + type + " facing "
                                        + facing + " mount angle " + rotation + " tilt " + tilt + " scale " + scale + ":" + bad);
                            }
                            checks++;
                        }
                    }
                }
            }
        }
        LOGGER.info("PANEL CLIENT CHECK legend basis math: {} bases (every panel type, 4 facings, {} mount angles, {} tilts, {} scales) "
                + "are orthogonal, uniform, unmirrored and in the face plane", checks, rotations.length, tilts.length, scales.length);
        return checks + " bases";
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** Every block drew from its own model, every component is on the client, the legends are on the surface, and the view is as it should be. */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        int own = COLUMN[stage / 2];
        int blocks = 0;
        for (int i = 0; i < PLACED.size(); i++) {
            if (PLACED_COLUMN.get(i) != own) {
                continue; // the other columns are 14 or more blocks away, beyond what the client has loaded
            }
            BlockPos pos = PLACED.get(i);
            blocks++;
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof PanelBlock)) {
                wrong.add(pos + " is " + state);
                continue;
            }
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            int count = model.getQuads(state, null, RandomSource.create()).size();
            if (model == missing || count == 0) {
                wrong.add(state.toString());
            }
            quads += count;
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
            }
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("RPS legend showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != MOUNTED[own]) {
            throw new AssertionError("RPS legend showcase " + NAMES[stage] + ": " + components + " of " + MOUNTED[own] + " components on the client");
        }
        if (PanelRenderContext.debugBasisChecked() == 0) {
            throw new AssertionError("RPS legend showcase " + NAMES[stage] + ": no legend was audited (the basis audit is off or no text was drawn)");
        }
        if (PanelRenderContext.debugBasisBad() != 0) {
            throw new AssertionError("RPS legend showcase " + NAMES[stage] + ": " + PanelRenderContext.debugBasisBad()
                    + " drawn legends have a text basis that is not orthogonal in the face: " + PanelRenderContext.debugBasisFirstBad());
        }
        int type = stage / 2;
        String plane = surface(mc, type);
        String view = view(stage);
        return blocks + " blocks, " + quads + " quads, " + components + " components, " + PanelRenderContext.debugBasisChecked()
                + " legends audited, " + plane + ", " + view;
    }

    // -- the surface: the model's own face under the legends

    /**
     * Whether the face the legends are laid on is a face of the block's model: the corners and centre of the plate's rectangle
     * (on the face, at elevation 0) must each lie within {@link #PLANE_TOLERANCE} of a model quad facing the same way and
     * inside it. Returns the worst distance from the face.
     */
    private static String surface(Minecraft mc, int type) {
        BlockPos pos = holder(type);
        BlockState state = mc.level.getBlockState(pos);
        if (!(mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
            throw new AssertionError("RPS legend showcase: no panel block entity at " + pos);
        }
        PanelGeometry.Frame frame = PanelGeometry.frame(be.panelType(), be.facing());
        if (be.panelType() != TYPES[type]) {
            throw new AssertionError("RPS legend showcase: " + pos + " is " + be.panelType() + ", wanted " + TYPES[type]);
        }
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction side : new Direction[] {null, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            quads.addAll(model.getQuads(state, side, RandomSource.create()));
        }
        int[] fit = fitting(TYPES[type]);
        double[][] cells = {{fit[0], 0}, {fit[0] + 8, 0}, {fit[0], 4}, {fit[0] + 8, 4}, {fit[0] + 4, 2},
                {fit[1], 4}, {fit[1] + 4, 6}, {fit[2], 4}, {fit[2] + 4, 6}};
        double deepest = 0.0;
        double proudest = 0.0;
        for (double[] cell : cells) {
            double[] p = {frame.x(cell[0], cell[1], 0.0), frame.y(cell[0], cell[1], 0.0), frame.z(cell[0], cell[1], 0.0)};
            double best = Double.NaN;
            for (BakedQuad quad : quads) {
                double d = offsetFromQuad(quad, frame, p);
                if (!Double.isNaN(d) && (Double.isNaN(best) || Math.abs(d) < Math.abs(best))) {
                    best = d;
                }
            }
            if (Double.isNaN(best) || best < -PLANE_TOLERANCE || best > RECESS_ALLOWANCE) {
                throw new AssertionError("RPS legend showcase: " + TYPES[type] + " (" + NAMES[type * 2] + "): the mounting face at cell " + cell[0] + ","
                        + cell[1] + " is " + (Double.isNaN(best) ? "on no model face of its orientation"
                        : best < 0 ? -best + " blocks behind the model's face (it would sink into it)"
                        : best + " blocks in front of the model's face (it would float)")
                        + " (allowed: " + PLANE_TOLERANCE + " behind, " + RECESS_ALLOWANCE + " in front)");
            }
            deepest = Math.min(deepest, best);
            proudest = Math.max(proudest, best);
        }
        return String.format("mounting face %.5f behind to %.5f in front of the model's face", -deepest, proudest);
    }

    /**
     * How far the point {@code p} of the mounting plane is in front of {@code quad}'s plane (negative: behind it), if the quad
     * faces the way the frame's normal does and {@code p} lies inside it (within the tolerance, whichever way the quad is
     * wound), else NaN.
     */
    private static double offsetFromQuad(BakedQuad quad, PanelGeometry.Frame frame, double[] p) {
        int[] v = quad.getVertices();
        double[][] c = new double[4][3];
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 3; k++) {
                c[i][k] = Float.intBitsToFloat(v[i * 8 + k]);
            }
        }
        double[] a = sub(c[2], c[0]);
        double[] b = sub(c[3], c[1]);
        double[] q = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        double ql = len(q);
        if (ql < 1.0e-12) {
            return Double.NaN;
        }
        double[] n = {frame.nx, frame.ny, frame.nz};
        double orientation = dot(q, n) / ql;
        if (Math.abs(orientation) < 0.9999) {
            return Double.NaN; // not parallel to the mounting face
        }
        // whichever way the quad is wound, measure toward the side the mounting face's normal points to
        double sign = Math.signum(orientation) / ql;
        q = new double[] {q[0] * sign, q[1] * sign, q[2] * sign};
        boolean allAhead = true;
        boolean allBehind = true;
        for (int i = 0; i < 4; i++) {
            double[] e = sub(c[(i + 1) % 4], c[i]);
            double el = len(e);
            if (el < 1.0e-9) {
                continue;
            }
            double[] w = sub(p, c[i]);
            double[] cross = {e[1] * w[2] - e[2] * w[1], e[2] * w[0] - e[0] * w[2], e[0] * w[1] - e[1] * w[0]};
            double side = dot(cross, q);
            allAhead &= side >= -PLANE_TOLERANCE * el;
            allBehind &= side <= PLANE_TOLERANCE * el;
        }
        return allAhead || allBehind ? dot(sub(p, c[0]), q) : Double.NaN;
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    // -- the view: what the camera makes of the glyph axes

    /**
     * The picture's own geometry: the glyph's vertical (down the rows) and horizontal (along the columns) as the camera
     * of the stage sees them at the look point, from a pinhole camera with the yaw, pitch and field of view the check
     * sets. {@code lean} is the angle of the projected glyph vertical from the screen's vertical, {@code axes} the angle
     * between the projected glyph axes (90 when the glyph looks square), {@code squash} the height to width ratio of a
     * projected square glyph. The asserts: straight on there is no lean; on an upright face there is none from any
     * side; on a sloped face seen obliquely the lean is large (perspective of the plate's own edge, not a fault of the text).
     */
    private static String view(int stage) {
        int type = stage / 2;
        boolean oblique = stage % 2 == 1;
        PanelGeometry.Frame frame = PanelGeometry.frame(TYPES[type], FACING);
        Vec3 eye = eye(stage);
        Vec3 look = lookPoint(type);
        Vec3 delta = look.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double[] forward = {-Math.cos(pr) * Math.sin(yr), -Math.sin(pr), Math.cos(pr) * Math.cos(yr)};
        double[] right = {-Math.cos(yr), 0.0, -Math.sin(yr)};
        double[] up = {right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2],
                right[0] * forward[1] - right[1] * forward[0]};
        double[] center = project(eye, look, forward, right, up);
        double h = 0.02;
        double[] across = project(eye, look.add(frame.rightX * h, frame.rightY * h, frame.rightZ * h), forward, right, up);
        double[] down = project(eye, look.add(frame.downX * h, frame.downY * h, frame.downZ * h), forward, right, up);
        double[] a = {across[0] - center[0], across[1] - center[1]};
        double[] d = {down[0] - center[0], down[1] - center[1]};
        // screen y grows upward here: the glyph's down is toward -y when upright
        double lean = Math.toDegrees(Math.atan2(d[0], -d[1]));
        double baseline = Math.toDegrees(Math.atan2(a[1], a[0]));
        double axes = Math.toDegrees(Math.acos((a[0] * d[0] + a[1] * d[1]) / (Math.hypot(a[0], a[1]) * Math.hypot(d[0], d[1]))));
        double squash = Math.hypot(d[0], d[1]) / Math.hypot(a[0], a[1]);
        String line = String.format("%s: glyph vertical leans %.1f deg off the screen vertical, baseline %.1f deg, axes %.1f deg apart (90 = square), "
                + "height/width %.2f", NAMES[stage], lean, baseline, axes, squash);
        LOGGER.info("PANEL CLIENT CHECK legend view {}", line);
        if (!oblique && (Math.abs(lean) > 0.5 || Math.abs(axes - 90.0) > 0.5)) {
            throw new AssertionError("Seen straight on the legends must not lean: " + line);
        }
        if (oblique && upright(TYPES[type]) && Math.abs(lean) > 0.5) {
            throw new AssertionError("A vertical line on an upright face must stay vertical in any view: " + line);
        }
        if (oblique && !upright(TYPES[type]) && Math.abs(lean) < 3.0) {
            throw new AssertionError("The oblique view of a sloped face is expected to lean its glyphs (perspective): " + line);
        }
        return String.format("lean %.1f deg", lean);
    }

    /** Screen position (pixels from the picture centre, y up) of a world point for the held camera. */
    private static double[] project(Vec3 eye, Vec3 p, double[] forward, double[] right, double[] up) {
        double[] r = {p.x - eye.x, p.y - eye.y, p.z - eye.z};
        double depth = dot(r, forward);
        double tan = Math.tan(Math.toRadians(FOV_DEGREES) / 2.0);
        double pixelsPerUnit = 540.0 / tan;
        return new double[] {dot(r, right) / depth * pixelsPerUnit, dot(r, up) / depth * pixelsPerUnit};
    }
}
