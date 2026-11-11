package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.AngledRunBlock;
import dev.bwr.mod.panel.AngledRunGeometry;
import dev.bwr.mod.panel.AngledRunGeometry.Family;
import dev.bwr.mod.panel.AngledRunGeometry.Role;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelFinishes;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelNetwork;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The modular 45 degree runs ({@link AngledRunBlock}, CONTROL-PANELS-SPEC section 14): bend A, the diagonal unit and bend B of
 * the desk and the tall panel, inside and outside, both hands. The plan (pieces partition a run of any length; every unit face
 * lies on the surface of its profile at the right angle, with the columns to the operator's right whichever the hand), the
 * shapes and the models of every part, placing every piece through the real item for every facing and hand, building a run
 * piece by piece with the item snapping into line and taking its facing and hand from what it is placed against, extending it
 * one unit at a time, breaking (one piece goes, with one drop), mounting on every face of every unit through a real block ray
 * cast and the real Place and Operate packet handlers, wiring, finishes and saves. Fixtures live around 150,000 / 151,000.
 */
@GameTestHolder("bwrcorner")
@PrefixGameTestTemplate(false)
public final class AngledRunTests {
    private AngledRunTests() {
    }

    private static final int ORIGIN_X = 150_000;
    private static final int ORIGIN_Z = 151_000;
    private static final int FLOOR_Y = 100;

    private record Run(Family family, boolean inside) {
        String name() {
            return family.id() + "-" + (inside ? "inside" : "outside");
        }

        AngledRunBlock block(Role role) {
            return BwrBlocks.ANGLED_RUNS.get("control_panel_" + family.id() + "_run_" + (inside ? "inside" : "outside") + "_" + role.id()).get();
        }

        Item item(Role role) {
            return block(role).asItem();
        }

        AngledRunGeometry.Piece piece(Role role) {
            return AngledRunGeometry.piece(family, inside, role);
        }
    }

    private static final List<Run> RUNS = new ArrayList<>();

    static {
        for (Family family : Family.values()) {
            RUNS.add(new Run(family, true));
            RUNS.add(new Run(family, false));
        }
    }

    // ---- fixtures

    private interface Body {
        void run(ServerLevel level, BlockPos floor);
    }

    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 40, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 12, FLOOR_Y, floor.getZ() - 12, floor.getX() + 12, FLOOR_Y + 8, floor.getZ() + 12);
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            clear(level, floor);
            try {
                body.run(level, floor);
            } finally {
                clear(level, floor);
            }
            h.succeed();
        });
    }

    private static void clear(ServerLevel level, BlockPos floor) {
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-11, 0, -11), floor.offset(11, 8, 11))) {
            BlockState want = pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != want) {
                level.setBlock(pos, want, 2);
            }
        }
        clearItems(level, floor);
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(13);
    }

    private static void clearItems(ServerLevel level, BlockPos floor) {
        level.getEntitiesOfClass(ItemEntity.class, area(floor)).forEach(ItemEntity::discard);
    }

    private static int dropped(ServerLevel level, BlockPos floor, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                .filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static int droppedComponents(ServerLevel level, BlockPos floor) {
        return level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                .filter(e -> e.getItem().getItem() instanceof ComponentItem).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static FakePlayer player(ServerLevel level, String name, GameType mode) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static PanelBlockEntity be(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    /** Blocks of any run piece in the fixture. */
    private static int runBlocks(ServerLevel level, BlockPos floor) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-11, 1, -11), floor.offset(11, 8, 11))) {
            if (level.getBlockState(pos).getBlock() instanceof AngledRunBlock) {
                n++;
            }
        }
        return n;
    }

    /**
     * Uses an item on a block face with the player standing four blocks out on the operator's side of {@code facing}, looking at
     * the block. {@code sneak} makes it a placement of the mirror image when nothing decides it.
     */
    private static boolean useOn(FakePlayer player, ItemStack stack, BlockPos clicked, Direction face, Direction look, boolean sneak) {
        player.setPos(Vec3.atBottomCenterOf(clicked.relative(look.getOpposite(), 4)));
        player.setYRot(look.toYRot());
        player.setXRot(0);
        player.setShiftKeyDown(sneak);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clicked).relative(face, 0.5), face, clicked, false);
        boolean ok = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
        player.setShiftKeyDown(false);
        return ok;
    }

    /** Places a piece free standing, its origin at {@code at}: aimed at the floor below, the player on the operator's side of {@code facing}. */
    private static boolean placeFree(FakePlayer player, ItemStack stack, BlockPos at, Direction facing, boolean mirror) {
        return useOn(player, stack, at.below(), Direction.UP, facing.getOpposite(), mirror);
    }

    // ---- the plan

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void thePiecesPartitionARunOfAnyLengthAndEveryFaceCoreIsABlockOfItsOwn(GameTestHelper h) {
        for (Run run : RUNS) {
            int sf = AngledRunGeometry.chainStep(run.inside())[1];
            AngledRunGeometry.Piece a = run.piece(Role.BEND_A);
            AngledRunGeometry.Piece unit = run.piece(Role.UNIT);
            AngledRunGeometry.Piece b = run.piece(Role.BEND_B);
            check(h, a.origin()[0] == 0 && a.origin()[1] == 0 && a.origin()[2] == 0, run.name() + ": bend A's origin is the reference");
            check(h, unit.origin()[0] == -1 && unit.origin()[1] == sf && b.origin()[0] == -1 && b.origin()[1] == sf,
                    run.name() + ": the origins of consecutive pieces are one chain step apart");
            check(h, a.parts().size() <= AngledRunGeometry.MAX_PARTS && unit.parts().size() <= AngledRunGeometry.MAX_PARTS
                    && b.parts().size() <= AngledRunGeometry.MAX_PARTS, run.name() + ": a piece has more blocks than the part property holds");
            for (Role role : Role.values()) {
                check(h, run.piece(role).parts().get(0).equals(new AngledRunGeometry.Part(0, 0, 0)), run.name() + " " + role + ": the origin comes first");
            }
            // the plan, recomputed from the depth formula by sampling cell centres as run_plan.py does
            for (int n = 0; n <= 3; n++) {
                Map<List<Integer>, String> owned = new HashMap<>();
                putPiece(h, owned, run, Role.BEND_A, 0, 0, 0, "A");
                for (int k = 1; k <= n; k++) {
                    putPiece(h, owned, run, Role.UNIT, unit.origin()[0] - (k - 1), unit.origin()[1] + (k - 1) * sf, unit.origin()[2], "U" + k);
                }
                putPiece(h, owned, run, Role.BEND_B, b.origin()[0] - n, b.origin()[1] + n * sf, 0, "B");
                double wall = run.inside() ? -n - 1 : -n;
                for (int bx = -n - 1; bx <= 0; bx++) {
                    for (int by = 0; by < run.family().layers(); by++) {
                        for (int bf = -n - 6; bf <= n + 6; bf++) {
                            boolean[] content = cubeContent(run, wall, bx, bf, by);
                            boolean has = content[0];
                            boolean pure = content[1];
                            boolean listed = owned.containsKey(List.of(bx, bf, by));
                            check(h, !has || listed || (bx == -n - 1 && pure),
                                    run.name() + " n=" + n + ": the cube " + bx + "," + bf + "," + by + " holds console no piece owns");
                            check(h, !listed || has, run.name() + " n=" + n + ": a piece owns the empty cube " + bx + "," + bf + "," + by);
                        }
                    }
                }
            }
            // faces: one per layer of the family, cores distinct blocks of the unit
            check(h, unit.faces().size() == run.family().layers(), run.name() + ": one face per layer");
            Set<AngledRunGeometry.Part> cores = new HashSet<>();
            for (AngledRunGeometry.Face face : unit.faces()) {
                check(h, unit.parts().contains(face.core()) && cores.add(face.core()), run.name() + ": a core holds no console or two faces share it");
                check(h, AngledRunGeometry.faceOfPart(run.family(), run.inside(), Role.UNIT, unit.parts().indexOf(face.core())) == face, "face of its core");
            }
            for (Role role : new Role[] {Role.BEND_A, Role.BEND_B}) {
                for (int i = 0; i < run.piece(role).parts().size(); i++) {
                    check(h, AngledRunGeometry.faceOfPart(run.family(), run.inside(), role, i) == null, run.name() + ": a bend has no faces");
                }
            }
        }
        h.succeed();
    }

    private static void putPiece(GameTestHelper h, Map<List<Integer>, String> owned, Run run, Role role, int ox, int of, int oy, String tag) {
        for (AngledRunGeometry.Part p : run.piece(role).parts()) {
            List<Integer> cube = List.of(ox + p.right(), of + p.deep(), oy + p.up());
            check(h, owned.put(cube, tag) == null, run.name() + ": " + tag + " overlaps another piece at " + cube);
        }
    }

    /** (has console, is pure straight run B) for a cube: the 16 x 16 cell centres, as run_plan.py samples. */
    private static boolean[] cubeContent(Run run, double wall, int bx, int bf, int by) {
        double layerDepth = run.family() == Family.DESK ? (by == 0 ? 0.75 : 0.0) : (by == 0 ? 1.0 : (by <= 3 ? 0.25 : 0.0));
        boolean any = false;
        boolean pure = true;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                double x = bx + (i + 0.5) / 16.0;
                double f = bf + (j + 0.5) / 16.0;
                double t = AngledRunGeometry.depth(run.family(), run.inside(), x, f, wall);
                if (t > 1.0e-9 && t < layerDepth - 1.0e-9) {
                    any = true;
                    double wallTerm = run.inside() ? x - wall : wall - x;
                    if (Math.abs(t - wallTerm) > 1.0e-9) {
                        pure = false;
                    }
                }
            }
        }
        return new boolean[] {any, any && pure};
    }

    /** World position of a local block of a piece for a facing and hand. */
    private static BlockPos at(BlockPos origin, Direction facing, boolean mirror, AngledRunGeometry.Part p) {
        return AngledRunBlock.partPos(origin, facing, mirror, p);
    }

    // ---- the faces

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void everyUnitFaceLiesOnItsProfileAtTheRightAngleWithItsColumnsToTheOperatorsRight(GameTestHelper h) {
        for (Run run : RUNS) {
            AngledRunGeometry.Piece unit = run.piece(Role.UNIT);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (boolean mirror : new boolean[] {false, true}) {
                    for (AngledRunGeometry.Face face : unit.faces()) {
                        PanelGeometry.Frame f = AngledRunGeometry.faceFrame(run.family(), run.inside(), face, facing, mirror);
                        String what = run.name() + " " + face.kind() + " " + facing + (mirror ? " mirrored" : "");
                        // the angle
                        double up = f.ny;
                        switch (face.kind()) {
                            case "desk" -> check(h, near(f.nx, 0) && near(f.ny, 1) && near(f.nz, 0), what + ": a level top");
                            case "bench" -> check(h, near(up, Math.sqrt(0.5)), what + ": 45 degrees like the straight benchboard: " + up);
                            case "vertical" -> check(h, near(up, 0), what + ": a vertical board");
                            default -> check(h, Math.abs(up - Math.sin(Math.atan(PanelGeometry.HEADER_TILT_TAN))) < 1.0e-9, what + ": the header leans back 5 degrees: " + up);
                        }
                        PanelGeometry.Frame straight = switch (face.kind()) {
                            case "desk" -> PanelGeometry.frame(PanelType.DESK, facing);
                            case "bench" -> PanelGeometry.frame(PanelType.BENCHBOARD, facing);
                            case "vertical" -> PanelGeometry.frame(PanelType.VERTICAL, facing);
                            default -> PanelGeometry.frame(PanelType.HEADER_TILT, facing);
                        };
                        check(h, near(f.ny, straight.ny) && near(f.vLength, straight.vLength) && near(f.uy, 0.0),
                                what + ": the angle and the cell shape are those of the straight panel (" + f.ny + " / " + straight.ny + ", " + f.vLength + " / " + straight.vLength + ")");
                        // orthonormal columns, rows and normal, the columns toward the operator's right, the normal toward the operator
                        check(h, near(f.rightX * f.nx + f.rightY * f.ny + f.rightZ * f.nz, 0) && near(f.downX * f.nx + f.downY * f.ny + f.downZ * f.nz, 0),
                                what + ": columns and rows lie in the face");
                        check(h, near(f.rightX * f.downX + f.rightY * f.downY + f.rightZ * f.downZ, 0), what + ": columns and rows are square");
                        Direction ccw = facing.getCounterClockWise();
                        check(h, f.rightX * ccw.getStepX() + f.rightZ * ccw.getStepZ() > 0.5, what + ": the columns do not run to the operator's right");
                        check(h, f.nx * facing.getStepX() + f.nz * facing.getStepZ() > -1.0e-9, what + ": the normal points away from the operator");
                        // handedness: the same as the straight panel (a mirrored hand must not mirror the pictures)
                        double hand = (f.rightY * f.downZ - f.rightZ * f.downY) * f.nx + (f.rightZ * f.downX - f.rightX * f.downZ) * f.ny
                                + (f.rightX * f.downY - f.rightY * f.downX) * f.nz;
                        double want = (straight.rightY * straight.downZ - straight.rightZ * straight.downY) * straight.nx
                                + (straight.rightZ * straight.downX - straight.rightX * straight.downZ) * straight.ny
                                + (straight.rightX * straight.downY - straight.rightY * straight.downX) * straight.nz;
                        check(h, hand * want > 0, what + ": the frame is mirror-handed");
                        // the lattice lies on the profile: depth and height from the plan formula
                        double wall = run.inside() ? -4.0 : -3.0;
                        int rows = face.rows();
                        for (int cu = 0; cu <= 16; cu += 4) {
                            for (int cv = 0; cv <= rows; cv += rows / 2) {
                                double bxw = f.x(cu, cv, 0);
                                double bzw = f.z(cu, cv, 0);
                                // back to the unmirrored plan: lx, f of the core block
                                double lx = mirror ? bxw : 0.0;
                                double lf;
                                if (facing == Direction.NORTH) {
                                    lx = mirror ? bxw : 1.0 - bxw;
                                    lf = 1.0 - bzw;
                                } else if (facing == Direction.SOUTH) {
                                    lx = mirror ? 1.0 - bxw : bxw;
                                    lf = bzw;
                                } else if (facing == Direction.EAST) {
                                    lx = mirror ? bzw : 1.0 - bzw;
                                    lf = bxw;
                                } else {
                                    lx = mirror ? 1.0 - bzw : bzw;
                                    lf = 1.0 - bxw;
                                }
                                double x = unit.origin()[0] + face.core().right() + lx;
                                double pf = unit.origin()[1] + face.core().deep() + lf;
                                double t = AngledRunGeometry.depth(run.family(), run.inside(), x, pf, wall);
                                double wantT = face.t0() + (face.t1() - face.t0()) * cv / rows;
                                double wantY = face.y0() + (face.y1() - face.y0()) * cv / rows;
                                check(h, Math.abs(t - wantT) < 1.0e-6, what + ": grid point " + cu + "," + cv + " is at depth " + t + ", not " + wantT);
                                check(h, Math.abs(unit.origin()[2] + face.core().up() + f.y(cu, cv, 0) - wantY) < 1.0e-6, what + ": grid point " + cu + "," + cv + " is at the wrong height");
                            }
                        }
                    }
                }
            }
        }
        h.succeed();
    }

    // ---- shapes and models

    /** The block-space point of a frame cell in the world, for a core block position. */
    private static Vec3 cellPoint(PanelGeometry.Frame f, BlockPos core, double cu, double cv, double cz) {
        return new Vec3(core.getX() + f.x(cu, cv, cz), core.getY() + f.y(cu, cv, cz), core.getZ() + f.z(cu, cv, cz));
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theShapesHoldEveryCellOfEveryFaceAndStayInTheirBlocks(GameTestHelper h) {
        BlockPos origin = BlockPos.ZERO;
        for (Run run : RUNS) {
            AngledRunGeometry.Piece unit = run.piece(Role.UNIT);
            for (boolean mirror : new boolean[] {false, true}) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.WEST}) {
                    for (AngledRunGeometry.Face face : unit.faces()) {
                        PanelGeometry.Frame f = AngledRunGeometry.faceFrame(run.family(), run.inside(), face, facing, mirror);
                        BlockPos core = at(origin, facing, mirror, face.core());
                        for (int cu = 0; cu < 16; cu++) {
                            for (int cv = 0; cv < face.rows(); cv++) {
                                Vec3 p = cellPoint(f, core, cu + 0.5, cv + 0.5, 0);
                                BlockPos block = BlockPos.containing(p);
                                int part = -1;
                                for (int i = 0; i < unit.parts().size(); i++) {
                                    if (at(origin, facing, mirror, unit.parts().get(i)).equals(block)) {
                                        part = i;
                                    }
                                }
                                // a cell may lie over a block of a neighbouring unit or bend (the faces reach 0.07 m, a header's lean): then it is not this unit's
                                if (part < 0) {
                                    continue;
                                }
                                VoxelShape shape = AngledRunGeometry.shape(run.family(), run.inside(), Role.UNIT, part, facing, mirror);
                                Vec3 from = p.add(f.nx * 1.5, f.ny * 1.5, f.nz * 1.5);
                                Vec3 to = p.add(-f.nx * 0.3, -f.ny * 0.3, -f.nz * 0.3);
                                BlockHitResult hit = shape.clip(from, to, block);
                                // the header strip's box is the whole block: up to 0.09 m proud of the face at the top of its 5 degree lean
                                check(h, hit != null && hit.getLocation().distanceTo(p) < (face.kind().equals("header") ? 0.2 : 0.1),
                                        run.name() + " " + face.kind() + " " + facing + (mirror ? " m" : "") + ": a ray onto cell " + cu + "," + cv + " finds "
                                                + (hit == null ? "nothing" : hit.getLocation().distanceTo(p) + " away"));
                            }
                        }
                    }
                }
            }
            for (Role role : Role.values()) {
                AngledRunGeometry.Piece piece = run.piece(role);
                for (int i = 0; i < piece.parts().size(); i++) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        VoxelShape shape = AngledRunGeometry.shape(run.family(), run.inside(), role, i, Direction.NORTH, mirror);
                        check(h, !shape.isEmpty() && shape.min(Direction.Axis.X) >= -1.0e-9 && shape.max(Direction.Axis.X) <= 1.0 + 1.0e-9
                                && shape.min(Direction.Axis.Z) >= -1.0e-9 && shape.max(Direction.Axis.Z) <= 1.0 + 1.0e-9
                                && shape.max(Direction.Axis.Y) <= 1.13, run.name() + " " + role + " part " + i + ": a shape outside its block");
                    }
                }
            }
        }
        h.succeed();
    }

    private record Poly(String material, List<double[]> corners) {
    }

    private static List<Poly> readObj(String path) throws IOException {
        List<double[]> vertices = new ArrayList<>();
        List<Poly> faces = new ArrayList<>();
        String material = "";
        try (InputStream in = AngledRunTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    vertices.add(new double[] {Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                } else if (line.startsWith("usemtl ")) {
                    material = line.substring(7).trim();
                } else if (line.startsWith("f ")) {
                    List<double[]> corners = new ArrayList<>();
                    for (String token : line.trim().substring(2).split("\\s+")) {
                        corners.add(vertices.get(Integer.parseInt(token.split("/")[0]) - 1));
                    }
                    faces.add(new Poly(material, corners));
                }
            }
        }
        return faces;
    }

    /** Whether a point lies on a planar convex polygon (within a tolerance of its plane and inside its outline). */
    private static boolean onPolygon(List<double[]> c, Vec3 p, double tolerance) {
        double[] a = c.get(0);
        double[] b = c.get(1);
        double[] d = c.get(2);
        double[] n = {(b[1] - a[1]) * (d[2] - a[2]) - (b[2] - a[2]) * (d[1] - a[1]), (b[2] - a[2]) * (d[0] - a[0]) - (b[0] - a[0]) * (d[2] - a[2]),
                (b[0] - a[0]) * (d[1] - a[1]) - (b[1] - a[1]) * (d[0] - a[0])};
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len < 1.0e-12) {
            return false;
        }
        double dist = ((p.x - a[0]) * n[0] + (p.y - a[1]) * n[1] + (p.z - a[2]) * n[2]) / len;
        if (Math.abs(dist) > tolerance) {
            return false;
        }
        double sign = 0;
        for (int i = 0; i < c.size(); i++) {
            double[] e0 = c.get(i);
            double[] e1 = c.get((i + 1) % c.size());
            double[] edge = {e1[0] - e0[0], e1[1] - e0[1], e1[2] - e0[2]};
            double[] to = {p.x - e0[0], p.y - e0[1], p.z - e0[2]};
            double[] cross = {edge[1] * to[2] - edge[2] * to[1], edge[2] * to[0] - edge[0] * to[2], edge[0] * to[1] - edge[1] * to[0]};
            double s = cross[0] * n[0] + cross[1] * n[1] + cross[2] * n[2];
            if (Math.abs(s) < 1.0e-9) {
                continue;
            }
            if (sign == 0) {
                sign = Math.signum(s);
            } else if (Math.signum(s) != sign) {
                return false;
            }
        }
        return true;
    }

    private static String modelName(Run run, Role role, int part, boolean mirror) {
        return "run_" + run.family().id() + "_" + (run.inside() ? "inside" : "outside") + "_" + role.id() + "_" + part + (mirror ? "_m" : "");
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void everyPartHasItsModelsInBothHandsAndTogetherTheyCoverEveryCellOfEveryFace(GameTestHelper h) throws IOException {
        for (Run run : RUNS) {
            Map<String, List<Poly>> models = new HashMap<>();
            for (Role role : Role.values()) {
                AngledRunGeometry.Piece piece = run.piece(role);
                for (int i = 0; i < piece.parts().size(); i++) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        String name = modelName(run, role, i, mirror);
                        List<Poly> polys = readObj("/assets/bwr/models/block/control_panel/" + name + ".obj");
                        check(h, !polys.isEmpty() && polys.size() <= 120, name + " is " + polys.size() + " polygons");
                        for (Poly poly : polys) {
                            check(h, poly.corners().size() <= 4, name + ": a polygon of " + poly.corners().size() + " corners (the game's loader drops it)");
                            for (double[] c : poly.corners()) {
                                check(h, c[0] >= -1.0e-5 && c[0] <= 1.00001 && c[2] >= -1.0e-5 && c[2] <= 1.00001 && c[1] >= -1.0e-5 && c[1] <= 1.1001,
                                        name + " " + poly.material() + " leaves its block: " + java.util.Arrays.toString(c));
                            }
                        }
                        models.put(name, polys);
                        try (InputStream in = AngledRunTests.class.getResourceAsStream("/assets/bwr/models/block/control_panel/" + name + ".json")) {
                            check(h, in != null, name + ": no block model json");
                        }
                    }
                }
                String block = "control_panel_" + run.family().id() + "_run_" + (run.inside() ? "inside" : "outside") + "_" + role.id();
                try (InputStream in = AngledRunTests.class.getResourceAsStream("/assets/bwr/blockstates/" + block + ".json")) {
                    check(h, in != null, block + ": no blockstate");
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        for (boolean mirror : new boolean[] {false, true}) {
                            for (int part = 0; part < AngledRunGeometry.MAX_PARTS; part++) {
                                check(h, json.contains("\"facing=" + facing.getName() + ",mirror=" + mirror + ",part=" + part + "\""),
                                        block + ": the blockstate has no variant " + facing + " " + mirror + " " + part);
                            }
                        }
                    }
                }
            }
            // the cells of every face lie on a polygon of the model of the block they are over (north-facing, both hands)
            AngledRunGeometry.Piece unit = run.piece(Role.UNIT);
            for (boolean mirror : new boolean[] {false, true}) {
                for (AngledRunGeometry.Face face : unit.faces()) {
                    PanelGeometry.Frame f = AngledRunGeometry.faceFrame(run.family(), run.inside(), face, Direction.NORTH, mirror);
                    BlockPos core = at(BlockPos.ZERO, Direction.NORTH, mirror, face.core());
                    for (int cu = 0; cu < 16; cu++) {
                        for (int cv = 0; cv < face.rows(); cv++) {
                            Vec3 p = cellPoint(f, core, cu + 0.5, cv + 0.5, 0);
                            BlockPos block = BlockPos.containing(p);
                            int part = -1;
                            Role owner = null;
                            for (int i = 0; i < unit.parts().size(); i++) {
                                if (at(BlockPos.ZERO, Direction.NORTH, mirror, unit.parts().get(i)).equals(block)) {
                                    part = i;
                                    owner = Role.UNIT;
                                }
                            }
                            if (part < 0) {
                                continue; // over a neighbouring piece's block, which carries the same surface
                            }
                            boolean covered = false;
                            Vec3 local = p.subtract(block.getX(), block.getY(), block.getZ());
                            for (Poly poly : models.get(modelName(run, owner, part, mirror))) {
                                if (poly.material().equals("face") && onPolygon(poly.corners(), local, 2.0e-3)) {
                                    covered = true;
                                }
                            }
                            check(h, covered, run.name() + " " + face.kind() + (mirror ? " mirrored" : "") + ": cell " + cu + "," + cv
                                    + " is not on a face polygon of the model of part " + part);
                        }
                    }
                }
            }
        }
        h.succeed();
    }

    // ---- placing

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void everyPieceIsItsBlocksOneBlockEntityPerFaceAndOneItemForEveryFacingAndHand(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "RunPlace", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Role role : Role.values()) {
                    AngledRunGeometry.Piece piece = run.piece(role);
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        for (boolean mirror : new boolean[] {false, true}) {
                            clear(level, floor);
                            String what = run.name() + " " + role + " " + facing + (mirror ? " mirrored" : "");
                            check(h, placeFree(player, new ItemStack(run.item(role)), at, facing, mirror), what + ": not placed");
                            check(h, player.getMainHandItem().isEmpty(), what + ": the item was not used up");
                            check(h, runBlocks(level, floor) == piece.parts().size(), what + ": " + runBlocks(level, floor) + " blocks, not " + piece.parts().size());
                            int entities = 0;
                            for (int i = 0; i < piece.parts().size(); i++) {
                                BlockPos pos = at(at, facing, mirror, piece.parts().get(i));
                                BlockState state = level.getBlockState(pos);
                                check(h, state.is(run.block(role)) && state.getValue(AngledRunBlock.PART) == i && state.getValue(PanelBlock.FACING) == facing
                                        && state.getValue(AngledRunBlock.MIRROR) == mirror, what + ": part " + i + " is not at " + pos + ": " + state);
                                check(h, AngledRunBlock.origin(pos, state).equals(at) && AngledRunBlock.piece(pos, state).size() == piece.parts().size(),
                                        what + ": part " + i + " does not find its origin and piece");
                                if (be(level, pos) != null) {
                                    entities++;
                                    check(h, AngledRunGeometry.faceOfPart(run.family(), run.inside(), role, i) != null, what + ": a block entity on a body block");
                                }
                            }
                            check(h, entities == (role == Role.UNIT ? run.piece(Role.UNIT).faces().size() : 0), what + ": " + entities + " block entities");
                            if (role == Role.UNIT) {
                                for (AngledRunGeometry.Face face : piece.faces()) {
                                    PanelBlockEntity panel = be(level, at(at, facing, mirror, face.core()));
                                    PanelGroup group = panel.group();
                                    check(h, panel.panelType() == AngledRunGeometry.faceType(face) && group.columns() == 1 && group.rows() == 1
                                            && group.members().size() == 1, what + ": face " + face.kind() + " is not a group of its own");
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void aPieceIsRefusedWholeWhenAnyOfItsBlocksIsTaken(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "RunBlocked", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Role role : Role.values()) {
                    List<AngledRunGeometry.Part> parts = run.piece(role).parts();
                    for (int taken : new int[] {1, parts.size() / 2, parts.size() - 1}) {
                        clear(level, floor);
                        BlockPos obstacle = at(at, Direction.NORTH, false, parts.get(taken));
                        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
                        ItemStack stack = new ItemStack(run.item(role));
                        check(h, !placeFree(player, stack, at, Direction.NORTH, false), run.name() + " " + role + " was placed over " + obstacle);
                        check(h, runBlocks(level, floor) == 0 && !stack.isEmpty(), run.name() + " " + role + ": a refused piece left blocks or ate the item");
                    }
                }
            }
        });
    }

    /** Uses an item on a block of a piece, on whichever of its faces has air beside it (the top first). */
    private static boolean placeAgainst(FakePlayer player, Item item, BlockPos clicked, Direction look) {
        for (Direction face : new Direction[] {Direction.UP, look.getOpposite(), look, look.getClockWise(), look.getCounterClockWise()}) {
            if (player.level().getBlockState(clicked.relative(face)).isAir()
                    && useOn(player, new ItemStack(item), clicked, face, look.getOpposite(), false)) {
                return true;
            }
        }
        return false;
    }

    /** One end block of a straight run for a snapping test: desks of {@code facing} at {@code positions}. */
    private static void straightRun(ServerLevel level, FakePlayer player, Run run, List<BlockPos> positions, Direction facing) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, (run.family() == Family.DESK ? BwrBlocks.CONTROL_PANEL_DESK.get() : BwrBlocks.CONTROL_PANEL_BENCHBOARD.get())
                    .defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
        }
    }

    private static BlockState stateOf(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos);
    }

    /**
     * Builds the run bend A, {@code units} units, bend B by clicking: a bend against the end of a straight run, then each piece
     * against the open end of the one before, clicking a different block of it each time. Returns the origin positions in order.
     */
    private static List<BlockPos> buildRun(GameTestHelper h, ServerLevel level, FakePlayer player, Run run, BlockPos start, Direction facing,
                                           boolean mirror, int units, boolean bendBFirst) {
        List<BlockPos> origins = new ArrayList<>();
        AngledRunGeometry.Piece a = run.piece(Role.BEND_A);
        Direction right = AngledRunGeometry.right(facing, mirror);
        // the straight run before bend A: two blocks of the family arriving from the piece's x side
        BlockPos desk1 = start.relative(right, 1);
        BlockPos desk2 = start.relative(right, 2);
        straightRun(level, player, run, List.of(desk1, desk2), facing);
        // the item's click: on the side of the straight run's end block that faces the open spot
        check(h, useOn(player, new ItemStack(run.item(Role.BEND_A)), desk1, right.getOpposite(), facing.getOpposite(), false),
                run.name() + ": bend A not placed against the straight run");
        BlockState first = stateOf(level, start);
        check(h, first.is(run.block(Role.BEND_A)) && first.getValue(PanelBlock.FACING) == facing && first.getValue(AngledRunBlock.MIRROR) == mirror
                        && first.getValue(AngledRunBlock.PART) == 0,
                run.name() + ": bend A took the wrong facing or hand from the straight run: " + first);
        origins.add(start);
        BlockPos previous = start;
        Role previousRole = Role.BEND_A;
        for (int k = 1; k <= units; k++) {
            BlockPos expect = AngledRunBlock.chainOrigin(start, facing, mirror, run.inside(), k);
            // click a block of the piece before that is not its origin, on its top
            List<AngledRunGeometry.Part> parts = run.piece(previousRole).parts();
            BlockPos clicked = at(previous, facing, mirror, parts.get(parts.size() - 1));
            check(h, placeAgainst(player, run.item(Role.UNIT), clicked, facing), run.name() + ": unit " + k + " not placed against " + previousRole);
            BlockState state = stateOf(level, expect);
            check(h, state.is(run.block(Role.UNIT)) && state.getValue(PanelBlock.FACING) == facing && state.getValue(AngledRunBlock.MIRROR) == mirror
                            && state.getValue(AngledRunBlock.PART) == 0,
                    run.name() + ": unit " + k + " did not snap one chain step on, to " + expect + ": " + state + " at " + expect);
            origins.add(expect);
            previous = expect;
            previousRole = Role.UNIT;
        }
        return origins;
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 3600)
    public static void aRunGrowsOnePieceAtATimeSnappingIntoLineWithTheFacingAndHandOfWhatItMeets(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "RunBuild", GameType.CREATIVE);
            for (Run run : RUNS) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        clear(level, floor);
                        String what = run.name() + " " + facing + (mirror ? " mirrored" : "");
                        BlockPos start = floor.above();
                        List<BlockPos> origins = buildRun(h, level, player, run, start, facing, mirror, 3, false);
                        check(h, runBlocks(level, floor) == run.piece(Role.BEND_A).parts().size() + 3 * run.piece(Role.UNIT).parts().size(),
                                what + ": the blocks of bend A and three units are not all there, nor only those");
                        // one more unit at the open end, aimed at through bend A's sibling, extends the run again
                        BlockPos lastUnit = origins.get(origins.size() - 1);
                        BlockPos expect = AngledRunBlock.chainOrigin(start, facing, mirror, run.inside(), 4);
                        check(h, placeAgainst(player, run.item(Role.UNIT), lastUnit, facing), what + ": a fourth unit was not placed");
                        check(h, stateOf(level, expect).is(run.block(Role.UNIT)) && stateOf(level, expect).getValue(AngledRunBlock.MIRROR) == mirror,
                                what + ": the fourth unit is not in line at " + expect);
                        // bend B against the last unit
                        BlockPos expectB = AngledRunBlock.chainOrigin(start, facing, mirror, run.inside(), 5);
                        check(h, placeAgainst(player, run.item(Role.BEND_B), expect, facing), what + ": bend B was not placed");
                        BlockState b = stateOf(level, expectB);
                        check(h, b.is(run.block(Role.BEND_B)) && b.getValue(PanelBlock.FACING) == facing && b.getValue(AngledRunBlock.MIRROR) == mirror,
                                what + ": bend B is not in line at " + expectB + ": " + b);
                        // every block of the finished run belongs to exactly the piece that placed it
                        int blocks = runBlocks(level, floor);
                        int want = run.piece(Role.BEND_A).parts().size() + 4 * run.piece(Role.UNIT).parts().size() + run.piece(Role.BEND_B).parts().size();
                        check(h, blocks == want, what + ": " + blocks + " blocks in the finished run, not " + want);
                        // the straight run B starts exactly where bend B says: the block at its straight offset is free to continue it
                        int[] straight = run.piece(Role.BEND_B).straight();
                        BlockPos continuation = at(expectB, facing, mirror, new AngledRunGeometry.Part(straight[0], straight[1], straight[2]));
                        check(h, level.getBlockState(continuation).isAir(), what + ": the block where run B continues is taken");
                        // the other hand of the click: clicking the origin block of an earlier piece, a unit goes to its open end all the same
                        // (here bend B ends the run, so a unit between is not placed again: it is refused once the spot is taken)
                        useOn(player, new ItemStack(run.item(Role.UNIT)), expectB, Direction.UP, facing.getOpposite(), false);
                        check(h, !level.getBlockState(AngledRunBlock.chainOrigin(start, facing, mirror, run.inside(), 6)).is(run.block(Role.UNIT)),
                                what + ": a unit went on past bend B");
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void aBendTakesItsFacingAndHandFromTheStraightRunItEndsAndBendBFromTheSecondRun(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "RunStraight", GameType.CREATIVE);
            for (Run run : RUNS) {
                for (Direction desk : Direction.Plane.HORIZONTAL) {
                    // bend B against a straight run that continues the second leg: the leg's facing is the piece's x (inside) or against it (outside)
                    for (boolean mirror : new boolean[] {false, true}) {
                        clear(level, floor);
                        Direction facing = desk;
                        int[] straight = run.piece(Role.BEND_B).straight();
                        Direction wanted = run.inside() ? AngledRunGeometry.right(facing, mirror) : AngledRunGeometry.right(facing, mirror).getOpposite();
                        BlockPos origin = floor.above();
                        BlockPos leg = at(origin, facing, mirror, new AngledRunGeometry.Part(straight[0], straight[1], straight[2]));
                        straightRun(level, player, run, List.of(leg), wanted);
                        Direction side = Direction.from2DDataValue((facing.get2DDataValue() + 2) % 4);
                        BlockPos bend = origin;
                        // click the leg block on the side facing the origin
                        Direction towardOrigin = Direction.getNearest(origin.getX() - leg.getX(), 0, origin.getZ() - leg.getZ());
                        check(h, useOn(player, new ItemStack(run.item(Role.BEND_B)), leg, towardOrigin, facing.getOpposite(), false),
                                run.name() + " " + facing + (mirror ? " m" : "") + ": bend B not placed against a straight run");
                        BlockState state = level.getBlockState(bend);
                        check(h, state.is(run.block(Role.BEND_B)) && AngledRunGeometry.right(state.getValue(PanelBlock.FACING), state.getValue(AngledRunBlock.MIRROR))
                                        == AngledRunGeometry.right(facing, mirror),
                                run.name() + " " + facing + (mirror ? " m" : "") + ": bend B took the wrong orientation from the straight run: " + state);
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void aLoneUnitFacesThePlacerAndSneakingMirrorsIt(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "RunLone", GameType.CREATIVE);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    for (boolean sneak : new boolean[] {false, true}) {
                        clear(level, floor);
                        check(h, useOn(player, new ItemStack(run.item(Role.UNIT)), at.below(), Direction.UP, facing.getOpposite(), sneak), "not placed");
                        BlockState state = level.getBlockState(at);
                        check(h, state.is(run.block(Role.UNIT)) && state.getValue(PanelBlock.FACING) == facing && state.getValue(AngledRunBlock.MIRROR) == sneak,
                                run.name() + " " + facing + (sneak ? " sneaking" : "") + ": " + state);
                    }
                }
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 3600)
    public static void breakingAnyBlockRemovesThatPieceAloneWithOneDropTheFinishAndItsComponents(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "RunBreak", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Role role : Role.values()) {
                    List<AngledRunGeometry.Part> parts = run.piece(role).parts();
                    for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                        for (int i = 0; i < parts.size(); i += Math.max(1, parts.size() / 3)) {
                            Direction facing = Direction.from2DDataValue(i % 4);
                            boolean mirror = i % 2 == 1;
                            clear(level, floor);
                            player.setGameMode(GameType.SURVIVAL);
                            check(h, placeFree(player, PanelFinish.withFinish(new ItemStack(run.item(role)), PanelFinish.CREAM), at, facing, mirror), "not placed");
                            // a neighbouring unit of the run that must survive the break of this piece
                            Set<BlockPos> own = new HashSet<>();
                            for (AngledRunGeometry.Part part : parts) {
                                own.add(at(at, facing, mirror, part));
                            }
                            check(h, placeAgainst(player, run.item(Role.UNIT), at, facing), "the neighbour was not placed");
                            int neighbours = runBlocks(level, floor) - parts.size();
                            int mounted = 0;
                            for (int p = 0; p < parts.size(); p++) {
                                BlockPos pos = at(at, facing, mirror, parts.get(p));
                                if (role == Role.UNIT && be(level, pos) != null && level.getBlockState(pos).is(run.block(role))) {
                                    check(h, PanelOps.placeDirect(level, pos, 0, 0, ComponentTypes.LAMP, Footprint.of(4, 4)) != null
                                            && PanelOps.placeDirect(level, pos, 6, 2, ComponentTypes.LAMP, Footprint.of(4, 4)) != null, "components not mounted");
                                    mounted += 2;
                                }
                            }
                            int before = runBlocks(level, floor);
                            clearItems(level, floor);
                            player.setGameMode(mode);
                            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                            BlockPos broken = at(at, facing, mirror, parts.get(i));
                            check(h, player.gameMode.destroyBlock(broken), "block " + i + " would not break");
                            int after = runBlocks(level, floor);
                            check(h, before - after == parts.size() && after == neighbours, run.name() + " " + role + ": breaking part " + i + " removed " + (before - after)
                                    + " blocks, not " + parts.size() + " (the neighbouring piece must stay)");
                            int items = dropped(level, floor, run.item(role));
                            check(h, items == (mode == GameType.SURVIVAL ? 1 : 0), run.name() + " " + role + " " + mode + ": breaking part " + i + " dropped " + items);
                            check(h, droppedComponents(level, floor) == mounted, mode + ": dropped " + droppedComponents(level, floor) + " of " + mounted + " components");
                            if (mode == GameType.SURVIVAL) {
                                check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream().filter(e -> e.getItem().is(run.item(role)))
                                        .allMatch(e -> PanelFinish.of(e.getItem()) == PanelFinish.CREAM), "the dropped piece lost its finish");
                            }
                            check(h, neighbours >= run.piece(Role.UNIT).parts().size(), "no neighbour to test");
                        }
                    }
                    // a piece that loses a block another way falls apart and gives its item back once
                    clear(level, floor);
                    player.setGameMode(GameType.SURVIVAL);
                    check(h, placeFree(player, new ItemStack(run.item(role)), at, Direction.SOUTH, false), "not placed");
                    level.setBlock(at(at, Direction.SOUTH, false, parts.get(parts.size() - 1)), Blocks.AIR.defaultBlockState(), 3);
                    check(h, runBlocks(level, floor) == 0, run.name() + " " + role + ": a broken piece left " + runBlocks(level, floor) + " blocks");
                    check(h, dropped(level, floor, run.item(role)) == 1, "a piece that fell apart gives one item back, not " + dropped(level, floor, run.item(role)));
                }
            }
        });
    }

    // ---- mounting, aiming, operating, wiring

    private static ItemStack component(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
        return stack;
    }

    /** Puts the player a metre out from a point of the face (along its normal, or from the operator's side above a level top), looking at it, and returns what a click would hit. */
    private static BlockHitResult aimAt(ServerLevel level, FakePlayer player, PanelGeometry.Frame f, BlockPos core, double cu, double cv) {
        Vec3 target = cellPoint(f, core, cu, cv, 0);
        Vec3 eye = f.ny > 0.9 ? target.add(f.downX * 1.0, 0.9, f.downZ * 1.0) : target.add(f.nx * 1.0, f.ny * 1.0, f.nz * 1.0);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        Vec3 look = player.getLookAngle();
        return level.clip(new ClipContext(player.getEyePosition(), player.getEyePosition().add(look.scale(6.0)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 3600)
    public static void everyFaceOfEveryUnitIsFoundThroughTheBlockUnderTheCrosshairAndTakesComponentsThroughThePlayerPath(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "RunMount", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        clear(level, floor);
                        // a whole run, bend A + two units + bend B (the faces reach into the blocks of the pieces beside them)
                        List<BlockPos> origins = buildRun(h, level, player, run, at, facing, mirror, 2, false);
                        BlockPos second = origins.get(2);
                        check(h, placeAgainst(player, run.item(Role.BEND_B), second, facing), "bend B not placed");
                        for (BlockPos origin : new BlockPos[] {origins.get(1), second}) {
                            for (AngledRunGeometry.Face face : run.piece(Role.UNIT).faces()) {
                                BlockPos core = at(origin, facing, mirror, face.core());
                                PanelBlockEntity panel = be(level, core);
                                PanelGeometry.Frame f = PanelGeometry.frame(panel);
                                String what = run.name() + " " + facing + (mirror ? " m" : "") + " " + face.kind() + " at " + core;
                                for (int[] want : new int[][] {{0, 0}, {15, face.rows() - 1}, {8, face.rows() / 2}, {3, 2}, {12, face.rows() - 3}}) {
                                    BlockHitResult hit = aimAt(level, player, f, core, want[0] + 0.5, want[1] + 0.5);
                                    check(h, hit.getType() == HitResult.Type.BLOCK, what + ": the look at " + want[0] + "," + want[1] + " hit nothing");
                                    PanelBlockEntity found = dev.bwr.mod.panel.DeskCornerBlock.faceEntity(level, hit.getBlockPos(), player.getEyePosition(), player.getLookAngle());
                                    int[] cell = new int[2];
                                    check(h, found == panel && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell)
                                                    && cell[0] == want[0] && cell[1] == want[1],
                                            what + ": aimed at " + want[0] + "," + want[1] + " through " + hit.getBlockPos() + ", found "
                                                    + (found == null ? "no face" : found.getBlockPos() + " " + cell[0] + "," + cell[1]));
                                }
                                BlockHitResult hit = aimAt(level, player, f, core, 3.5, 2.5);
                                int[] cell = new int[2];
                                PanelBlockEntity found = dev.bwr.mod.panel.DeskCornerBlock.faceEntity(level, hit.getBlockPos(), player.getEyePosition(), player.getLookAngle());
                                check(h, found == panel && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell), what + ": no cell");
                                player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                                check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, core, cell[0], cell[1]) == PanelGroup.Result.OK, what + ": a lamp on the face");
                                check(h, panel.claimAt(cell[0], cell[1]) != null && player.getMainHandItem().isEmpty(), what + ": the lamp does not hold its cell");
                                player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                                check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, core, cell[0], cell[1]) != PanelGroup.Result.OK, what + ": a second lamp on the first");
                                sendPlace(level, player, core, 0, 6);
                                check(h, panel.claimAt(0, 6) != null && panel.owned().size() == 2, what + ": the Place packet did not mount on cell 0,6");
                            }
                        }
                        // the body blocks hold no cells
                        for (AngledRunGeometry.Part part : run.piece(Role.UNIT).parts()) {
                            BlockPos p = at(origins.get(1), facing, mirror, part);
                            BlockState state = level.getBlockState(p);
                            if (be(level, p) == null) {
                                check(h, !((AngledRunBlock) state.getBlock()).cellUsable(state, 0, 0), "a body block with usable cells");
                            }
                        }
                    }
                }
            }
        });
    }

    private static IPayloadContext context(ServerPlayer player) {
        return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void sendPlace(ServerLevel level, ServerPlayer player, BlockPos pos, int cx, int cy) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        PanelPayloads.Place.STREAM_CODEC.encode(buffer, new PanelPayloads.Place(pos, cx, cy, false));
        PanelPayloads.Place decoded = PanelPayloads.Place.STREAM_CODEC.decode(buffer);
        invoke("handlePlace", PanelPayloads.Place.class, decoded, player);
    }

    private static int stamp;

    private static void sendOperate(ServerLevel level, ServerPlayer player, BlockPos pos, PanelComponent c, OperateAction action, double value) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        PanelPayloads.Operate.STREAM_CODEC.encode(buffer, new PanelPayloads.Operate(pos, c.id(), action.ordinal(), value, ++stamp));
        PanelPayloads.Operate decoded = PanelPayloads.Operate.STREAM_CODEC.decode(buffer);
        invoke("handleOperate", PanelPayloads.Operate.class, decoded, player);
    }

    private static void invoke(String name, Class<?> payload, Object decoded, ServerPlayer player) {
        try {
            var handler = PanelNetwork.class.getDeclaredMethod(name, payload, IPayloadContext.class);
            handler.setAccessible(true);
            handler.invoke(null, decoded, context(player));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + name, e);
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 3600)
    public static void aComponentOnAnAngledUnitFaceIsOperatedWiredAndReachedLikeOnAnyPanel(GameTestHelper h) {
        fixture(h, 7, (level, floor) -> {
            FakePlayer player = player(level, "RunOperate", GameType.SURVIVAL);
            FakePlayer far = player(level, "RunFar", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.WEST}) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        clear(level, floor);
                        check(h, placeFree(player, new ItemStack(run.item(Role.UNIT)), at, facing, mirror), "not placed");
                        // every face of the unit in turn: the button and the lamp are on one face (a face is a group of its own, so wiring does not cross faces)
                        List<AngledRunGeometry.Face> faces = run.piece(Role.UNIT).faces();
                        AngledRunGeometry.Face chosen = faces.get((facing.get2DDataValue() + (mirror ? 1 : 0)) % faces.size());
                        BlockPos coreA = at(at, facing, mirror, chosen.core());
                        BlockPos coreB = coreA;
                        PanelComponent button = PanelOps.placeDirect(level, coreA, 2, 1, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
                        PanelComponent lamp = PanelOps.placeDirect(level, coreB, 11, 4, ComponentTypes.LAMP, Footprint.of(4, 4));
                        check(h, button != null && lamp != null, "components not mounted");
                        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
                        player.setPos(Vec3.atBottomCenterOf(coreA.relative(facing, 1)));
                        WiringOps.Result wired = WiringOps.bind(player, new PanelPayloads.Bind(coreB, lamp.id(), false, 0,
                                WiringOps.BindKind.COMPONENT.ordinal(), BlockPos.ZERO, "", coreA, button.id(), 0, 1.0));
                        check(h, wired == WiringOps.Result.OK, run.name() + " " + facing + (mirror ? " m" : "") + ": the lamp bound to the button on the face: " + wired);
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        sendOperate(level, player, coreA, button, OperateAction.PRESS, 1.0);
                        check(h, button.output(0) == 1.0 && lamp.input(0) == 1.0, run.name() + ": the press did not reach the lamp");
                        sendOperate(level, player, coreA, button, OperateAction.RELEASE, 0.0);
                        check(h, button.output(0) == 0.0 && lamp.input(0) == 0.0, "the release did not reach the lamp");
                        far.setPos(Vec3.atBottomCenterOf(coreA.relative(facing, 20)));
                        sendOperate(level, far, coreA, button, OperateAction.PRESS, 1.0);
                        check(h, button.output(0) == 0.0, "a player out of reach pressed the button");
                        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
                        sendOperate(level, player, coreA, button, OperateAction.PRESS, 1.0);
                        check(h, button.output(0) == 0.0, "pressed through a wrench");
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    }
                }
            }
        });
    }

    // ---- joining, finishes, saves

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void everyFaceIsItsOwnGroupAndNeverJoinsTheStraightPanelsItContinues(GameTestHelper h) {
        fixture(h, 8, (level, floor) -> {
            FakePlayer player = player(level, "RunGroups", GameType.CREATIVE);
            for (Run run : RUNS) {
                clear(level, floor);
                Direction facing = Direction.NORTH;
                BlockPos start = floor.above();
                List<BlockPos> origins = buildRun(h, level, player, run, start, facing, false, 2, false);
                BlockPos desk = start.relative(AngledRunGeometry.right(facing, false), 1);
                PanelBlockEntity straight = be(level, desk);
                Set<PanelGroup> groups = new HashSet<>();
                for (int k = 1; k <= 2; k++) {
                    for (AngledRunGeometry.Face face : run.piece(Role.UNIT).faces()) {
                        PanelBlockEntity unit = be(level, at(origins.get(k), facing, false, face.core()));
                        check(h, unit.group().members().size() == 1 && unit.group() != straight.group(), "a face joined the straight run or another face");
                        groups.add(unit.group());
                    }
                }
                check(h, groups.size() == 2 * run.piece(Role.UNIT).faces().size(), run.name() + ": the faces are not one group each");
                check(h, straight.group().members().size() == 2 || straight.group().members().size() == 1, "the straight run took a piece in");
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void thePanelWrenchPaintsTheWholePieceAndItDropsAndPlacesWithThatFinish(GameTestHelper h) {
        fixture(h, 9, (level, floor) -> {
            FakePlayer player = player(level, "RunPaint", GameType.CREATIVE);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Role role : Role.values()) {
                    clear(level, floor);
                    Direction facing = Direction.EAST;
                    check(h, placeFree(player, new ItemStack(run.item(role)), at, facing, false), "not placed");
                    for (AngledRunGeometry.Part clicked : run.piece(role).parts()) {
                        PanelFinish before = PanelFinish.of(level.getBlockState(at));
                        check(h, PanelFinishes.cycle(level, at(at, facing, false, clicked), player), "finish did not change from " + clicked);
                        PanelFinish after = PanelFinish.of(level.getBlockState(at));
                        check(h, after != before, "the origin did not change");
                        for (AngledRunGeometry.Part each : run.piece(role).parts()) {
                            check(h, PanelFinish.of(level.getBlockState(at(at, facing, false, each))) == after, clicked + ": " + each + " did not take the finish");
                        }
                    }
                    check(h, run.block(role).getStateDefinition().getPossibleStates().size() == 4 * 2 * AngledRunGeometry.MAX_PARTS * 5,
                            "states: 4 facings, 2 hands, 10 parts, 5 finishes");
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void mountedComponentsOnEveryFaceAndEveryBlockStateSurviveASaveAndReload(GameTestHelper h) {
        fixture(h, 10, (level, floor) -> {
            var registries = level.registryAccess();
            FakePlayer player = player(level, "RunSave", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Run run : RUNS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        clear(level, floor);
                        check(h, placeFree(player, PanelFinish.withFinish(new ItemStack(run.item(Role.UNIT)), PanelFinish.GREY), at, facing, mirror), "not placed");
                        for (AngledRunGeometry.Face face : run.piece(Role.UNIT).faces()) {
                            BlockPos core = at(at, facing, mirror, face.core());
                            PanelBlockEntity panel = be(level, core);
                            PanelComponent lamp = PanelOps.placeDirect(level, core, 3, 2, ComponentTypes.LAMP, Footprint.of(4, 4));
                            PanelComponent dial = PanelOps.placeDirect(level, core, 8, 4, ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4));
                            check(h, lamp != null && dial != null, "components not mounted");
                            final String label = "RUN-" + face.kind();
                            lamp.setLabel(label);
                            CompoundTag saved = panel.saveWithoutMetadata(registries);
                            PanelBlockEntity reloaded = new PanelBlockEntity(core, level.getBlockState(core));
                            reloaded.loadWithComponents(saved, registries);
                            check(h, reloaded.panelType() == AngledRunGeometry.faceType(face) && reloaded.savePanel(false).equals(panel.savePanel(false)),
                                    run.name() + " " + facing + " " + face.kind() + ": the save is not a fixed point of load");
                            check(h, reloaded.owned().size() == 2 && reloaded.claimAt(3, 2) != null && reloaded.claimAt(10, 7) != null
                                    && reloaded.owned().stream().anyMatch(c -> label.equals(c.label())), "the reloaded face lost a component");
                            check(h, reloaded.claimAt(3, 2).owner().equals(core), "claims are absolute positions of the core");
                        }
                        for (AngledRunGeometry.Part part : run.piece(Role.UNIT).parts()) {
                            BlockState state = level.getBlockState(at(at, facing, mirror, part));
                            BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                            check(h, restored == state, part + " state " + state + " did not survive a save");
                        }
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theRunPiecesAreRegisteredWithItemsRecipesLootAndTheCreativeTab(GameTestHelper h) {
        for (Run run : RUNS) {
            for (Role role : Role.values()) {
                String path = "control_panel_" + run.family().id() + "_run_" + (run.inside() ? "inside" : "outside") + "_" + role.id();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", path);
                check(h, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id) == run.block(role), id + ": block not registered");
                check(h, net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) == run.item(role), id + ": item not registered");
                check(h, BwrItems.TAB.get().getDisplayItems().stream().anyMatch(s -> s.is(run.item(role))), id + " is not in the creative tab");
                var manager = h.getLevel().getServer().getRecipeManager();
                boolean vanilla = manager.byKey(id).isPresent();
                boolean mekanism = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", id.getPath() + "_mekanism")).isPresent();
                check(h, vanilla ^ mekanism, id + ": exactly one recipe must be live (vanilla " + vanilla + ", Mekanism " + mekanism + ")");
                check(h, h.getLevel().getServer().reloadableRegistries().getLootTable(net.minecraft.resources.ResourceKey.create(
                        Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath("bwr", "blocks/" + path))) != net.minecraft.world.level.storage.loot.LootTable.EMPTY,
                        id + ": no loot table");
            }
        }
        h.succeed();
    }
}
