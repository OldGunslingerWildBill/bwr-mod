package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.DeskCornerBlock;
import dev.bwr.mod.panel.DeskCornerGeometry;
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
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 45 degree desk corners ({@link DeskCornerBlock}, CONTROL-PANELS-SPEC section 14), inside and outside, one to four
 * angled units long (each unit its own face). The geometry (every face's frame is a level diagonal top on the console, the
 * shapes hold every cell and leave the knee space, the models cover every cell), placing the blocks through the real item
 * for every facing and refusing the whole piece when one is taken, breaking any block (one drop, the finish, the
 * components), mounting a component on every angled face through the real player path with the cell found by a real block
 * ray cast (so a click on any block of the corner finds the face under the ray), operating through the real operate packet
 * handler, wiring a component to another on the face, the finishes, and save/load. Fixtures live around 150,000 / 150,000.
 */
@GameTestHolder("bwrcorner")
@PrefixGameTestTemplate(false)
public final class DeskCornerTests {
    private DeskCornerTests() {
    }

    private static final int ORIGIN_X = 150_000;
    private static final int ORIGIN_Z = 150_000;
    private static final int FLOOR_Y = 100;

    /** One kind of corner: inside or outside, and its length. */
    private record Kind(boolean inside, int length) {
        String name() {
            return (inside ? "inside" : "outside") + "-" + length;
        }

        Block block() {
            return BwrBlocks.BLOCKS.getEntries().stream().map(e -> e.get()).filter(b -> b instanceof DeskCornerBlock c
                    && c.inside() == inside && c.length() == length).findFirst().orElseThrow();
        }

        Item item() {
            return block().asItem();
        }

        PanelType type() {
            return inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE;
        }

        int size() {
            return DeskCornerGeometry.size(length);
        }

        BlockPos part(BlockPos origin, Direction facing, DeskCornerGeometry.Part part) {
            return DeskCornerBlock.partPos(origin, facing, inside, part.left(), part.deep());
        }

        BlockPos core(BlockPos origin, Direction facing, int face) {
            return part(origin, facing, DeskCornerGeometry.core(inside, length, face));
        }

        List<DeskCornerGeometry.Part> parts() {
            return DeskCornerGeometry.parts(inside, length);
        }

        /** The part of a point of a north-facing corner whose origin is at ZERO (north: the operator's left is +x, deep is -z inside, +z outside). */
        DeskCornerGeometry.Part partAt(double x, double z) {
            int left = (int) Math.floor(x);
            int deep = inside ? -(int) Math.floor(z) : (int) Math.floor(z);
            return new DeskCornerGeometry.Part(left, deep);
        }
    }

    private static final List<Kind> KINDS = new ArrayList<>();

    static {
        for (int n = 1; n <= DeskCornerGeometry.MAX_LENGTH; n++) {
            KINDS.add(new Kind(true, n));
            KINDS.add(new Kind(false, n));
        }
    }

    private interface Body {
        void run(ServerLevel level, BlockPos floor);
    }

    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 32, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 8, FLOOR_Y, floor.getZ() - 8, floor.getX() + 8, FLOOR_Y + 7, floor.getZ() + 8);
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
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-7, 0, -7), floor.offset(7, 6, 7))) {
            BlockState want = pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != want) {
                level.setBlock(pos, want, 2);
            }
        }
        clearItems(level, floor);
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(9);
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

    /** The corner's origin block is at {@code at}, the player four blocks out on the operator's side looking back. */
    private static boolean place(FakePlayer player, ItemStack stack, BlockPos at, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(at.relative(facing, 4)));
        player.setYRot(facing.getOpposite().toYRot());
        player.setXRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floor = at.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    private static int cornerBlocks(ServerLevel level, BlockPos floor) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-7, 1, -7), floor.offset(7, 6, 7))) {
            if (level.getBlockState(pos).getBlock() instanceof DeskCornerBlock) {
                n++;
            }
        }
        return n;
    }

    private static PanelBlockEntity be(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    /** The frame of face {@code face} of a corner, facing {@code facing}. */
    private static PanelGeometry.Frame frameOf(PanelBlockEntity be) {
        return PanelGeometry.frame(be);
    }

    // ---- geometry

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theFacesAreLevelDiagonalTopsOnTheConsoleOneBlockApartAlongTheDiagonal(GameTestHelper h) {
        for (Kind kind : KINDS) {
            List<DeskCornerGeometry.Part> cores = new ArrayList<>();
            for (int face = 0; face < kind.length(); face++) {
                cores.add(DeskCornerGeometry.core(kind.inside(), kind.length(), face));
                check(h, DeskCornerGeometry.faceOf(kind.inside(), kind.length(), cores.get(face).left(), cores.get(face).deep()) == face,
                        kind.name() + ": face " + face + " is not the face of its core");
                check(h, kind.parts().contains(cores.get(face)), kind.name() + ": the core of face " + face + " holds no console");
            }
            check(h, new java.util.HashSet<>(cores).size() == kind.length(), kind.name() + ": two faces share a core block");
            check(h, kind.parts().get(0).left() == 0 && kind.parts().get(0).deep() == 0, kind.name() + ": the origin comes first");
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (int face = 0; face < kind.length(); face++) {
                    PanelGeometry.Frame f = PanelGeometry.cornerFrame(kind.inside(), kind.length(), face, facing);
                    String what = kind.name() + " " + facing + " face " + face;
                    check(h, near(f.nx, 0) && near(f.ny, 1) && near(f.nz, 0), what + ": level, normal straight up");
                    check(h, Math.abs(f.vLength - 1.0) < 1.0e-9, what + ": square cells: " + f.vLength);
                    check(h, Math.abs(f.rightX * f.downX + f.rightZ * f.downZ) < 1.0e-9, what + ": columns and rows are square");
                    check(h, Math.abs(Math.abs(f.rightX) - Math.sqrt(0.5)) < 1.0e-9 && Math.abs(Math.abs(f.rightZ) - Math.sqrt(0.5)) < 1.0e-9,
                            what + ": the columns run along a diagonal");
                    check(h, near(f.y(0, 0, 0), PanelGeometry.DESK_TOP), what + ": the top is 0.75 up");
                }
            }
            // every lattice point of every face lies on the top of the console (north-facing, in footprint coordinates)
            for (int face = 0; face < kind.length(); face++) {
                PanelGeometry.Frame north = PanelGeometry.cornerFrame(kind.inside(), kind.length(), face, Direction.NORTH);
                DeskCornerGeometry.Part core = cores.get(face);
                int bx = DeskCornerGeometry.blockX(kind.length(), core.left());
                int bf = DeskCornerGeometry.blockF(kind.inside(), core.deep());
                for (int cu = 0; cu <= 16; cu++) {
                    for (int cv = 0; cv <= 10; cv++) {
                        double x = (1.0 - north.x(cu, cv, 0)) + bx;
                        double f = (1.0 - north.z(cu, cv, 0)) + bf;
                        double t = DeskCornerGeometry.depth(kind.inside(), kind.length(), x, f);
                        check(h, t >= PanelGeometry.DESK_FACE_BACK - 1.0e-9 && t <= PanelGeometry.DESK_FRONT + 1.0e-9,
                                kind.name() + " face " + face + ": grid point " + cu + "," + cv + " is at depth " + t);
                    }
                }
            }
            // consecutive faces are one block apart along the diagonal
            for (int face = 1; face < kind.length(); face++) {
                double[] a = DeskCornerGeometry.faceCentre(kind.inside(), kind.length(), face - 1);
                double[] b = DeskCornerGeometry.faceCentre(kind.inside(), kind.length(), face);
                check(h, near(Math.abs(b[0] - a[0]), 1.0) && near(Math.abs(b[1] - a[1]), 1.0), kind.name() + ": faces not a block apart");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theShapesHoldEveryCellOfEveryFaceAtItsHeight(GameTestHelper h) {
        for (Kind kind : KINDS) {
            for (int face = 0; face < kind.length(); face++) {
                PanelGeometry.Frame f = PanelGeometry.cornerFrame(kind.inside(), kind.length(), face, Direction.NORTH);
                BlockPos corePos = kind.core(BlockPos.ZERO, Direction.NORTH, face);
                for (int cu = 0; cu < 16; cu++) {
                    for (int cv = 0; cv < 10; cv++) {
                        double x = corePos.getX() + f.x(cu + 0.5, cv + 0.5, 0);
                        double z = corePos.getZ() + f.z(cu + 0.5, cv + 0.5, 0);
                        BlockPos at = BlockPos.containing(x, 0, z);
                        DeskCornerGeometry.Part part = kind.partAt(x, z);
                        check(h, kind.part(BlockPos.ZERO, Direction.NORTH, part).equals(at) && kind.parts().contains(part),
                                kind.name() + " face " + face + ": cell " + cu + "," + cv + " is over " + at + ", not a block of the corner");
                        VoxelShape shape = DeskCornerGeometry.shape(kind.inside(), kind.length(), part.left(), part.deep(), Direction.NORTH);
                        BlockHitResult hit = shape.clip(new Vec3(x, 2.0, z), new Vec3(x, -1.0, z), at);
                        check(h, hit != null && Math.abs(hit.getLocation().y - 0.75) < 1.0e-6 && hit.getDirection() == Direction.UP,
                                kind.name() + " face " + face + ": a ray down onto cell " + cu + "," + cv + " finds "
                                        + (hit == null ? "nothing" : hit.getLocation().y));
                    }
                }
            }
            for (DeskCornerGeometry.Part part : kind.parts()) {
                VoxelShape shape = DeskCornerGeometry.shape(kind.inside(), kind.length(), part.left(), part.deep(), Direction.NORTH);
                check(h, !shape.isEmpty() && shape.min(Direction.Axis.X) >= -1.0e-9 && shape.max(Direction.Axis.X) <= 1.0 + 1.0e-9
                        && shape.min(Direction.Axis.Z) >= -1.0e-9 && shape.max(Direction.Axis.Z) <= 1.0 + 1.0e-9
                        && shape.max(Direction.Axis.Y) <= 1.13, kind.name() + " " + part + ": shape out of its block");
            }
        }
        // the knee space: 0.3 up at depth 0.55 under the middle of a face is air, at depth 0.1 it is solid
        for (Kind kind : KINDS) {
            double[] centre = DeskCornerGeometry.faceCentre(kind.inside(), kind.length(), 0);
            double[] v = DeskCornerGeometry.rowDirection(kind.inside());
            for (double[] probe : new double[][] {{0.55, 0}, {0.1, 1}}) {
                double px = centre[0] + v[0] * (probe[0] - 0.4375);
                double pf = centre[1] + v[1] * (probe[0] - 0.4375);
                int bx = (int) Math.floor(px);
                int bf = (int) Math.floor(pf);
                DeskCornerGeometry.Part part = new DeskCornerGeometry.Part(kind.size() - 1 - bx, kind.inside() ? bf : -bf);
                VoxelShape shape = DeskCornerGeometry.shape(kind.inside(), kind.length(), part.left(), part.deep(), Direction.NORTH);
                double ex = 1.0 - (px - bx);
                double ez = 1.0 - (pf - bf);
                boolean solid = Shapes.joinIsNotEmpty(shape, Shapes.box(ex - 0.01, 0.29, ez - 0.01, ex + 0.01, 0.31, ez + 0.01), BooleanOp.AND);
                check(h, solid == (probe[1] == 1), kind.name() + ": depth " + probe[0] + " at 0.3 up should be " + (probe[1] == 1 ? "solid" : "air"));
            }
        }
        h.succeed();
    }

    // ---- models

    private record Face(String material, List<double[]> corners) {
    }

    private static List<Face> readObj(String path) throws IOException {
        List<double[]> vertices = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        String material = "";
        try (InputStream in = DeskCornerTests.class.getResourceAsStream(path)) {
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
                    faces.add(new Face(material, corners));
                }
            }
        }
        return faces;
    }

    private static boolean inPlan(List<double[]> polygon, double x, double z) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double[] a = polygon.get(i);
            double[] b = polygon.get(j);
            if ((a[2] > z) != (b[2] > z) && x < (b[0] - a[0]) * (z - a[2]) / (b[2] - a[2]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theBlockModelsTogetherCoverEveryCellOfEveryMountingFace(GameTestHelper h) throws IOException {
        for (Kind kind : KINDS) {
            Map<DeskCornerGeometry.Part, List<Face>> models = new HashMap<>();
            for (DeskCornerGeometry.Part part : kind.parts()) {
                List<Face> faces = readObj("/assets/bwr/models/block/control_panel/desk_corner_" + (kind.inside() ? "inside" : "outside") + "_"
                        + kind.length() + "_" + part.left() + "_" + part.deep() + ".obj");
                check(h, !faces.isEmpty() && faces.size() <= 40, kind.name() + " " + part + " is " + faces.size() + " faces");
                for (Face face : faces) {
                    for (double[] c : face.corners()) {
                        check(h, c[0] >= -1.0e-5 && c[0] <= 1.00001 && c[2] >= -1.0e-5 && c[2] <= 1.00001 && c[1] >= -1.0e-5 && c[1] <= 1.1001,
                                kind.name() + " " + part + " " + face.material() + " leaves its block: " + java.util.Arrays.toString(c));
                    }
                }
                models.put(part, faces);
            }
            for (int face = 0; face < kind.length(); face++) {
                PanelGeometry.Frame f = PanelGeometry.cornerFrame(kind.inside(), kind.length(), face, Direction.NORTH);
                BlockPos corePos = kind.core(BlockPos.ZERO, Direction.NORTH, face);
                for (int cu = 0; cu < 16; cu++) {
                    for (int cv = 0; cv < 10; cv++) {
                        double x = corePos.getX() + f.x(cu + 0.5, cv + 0.5, 0);
                        double z = corePos.getZ() + f.z(cu + 0.5, cv + 0.5, 0);
                        DeskCornerGeometry.Part part = kind.partAt(x, z);
                        BlockPos at = kind.part(BlockPos.ZERO, Direction.NORTH, part);
                        boolean covered = false;
                        for (Face model : models.get(part)) {
                            if (model.material().equals("face") && model.corners().stream().allMatch(c -> Math.abs(c[1] - 0.75) < 1.0e-5)
                                    && inPlan(model.corners(), x - at.getX(), z - at.getZ())) {
                                covered = true;
                            }
                        }
                        check(h, covered, kind.name() + " face " + face + ": cell " + cu + "," + cv + " is not on the top of the model of " + part);
                    }
                }
            }
        }
        h.succeed();
    }

    // ---- placing

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void aCornerIsItsBlocksOneBlockEntityPerFaceAndOneItemForEveryFacing(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "CornerPlace", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : KINDS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(kind.item()), at, facing), kind.name() + " not placed facing " + facing);
                    check(h, player.getMainHandItem().isEmpty(), "the item was not used up");
                    check(h, cornerBlocks(level, floor) == kind.parts().size(), kind.name() + " " + facing + ": " + cornerBlocks(level, floor)
                            + " blocks, not " + kind.parts().size());
                    int entities = 0;
                    for (DeskCornerGeometry.Part part : kind.parts()) {
                        BlockPos pos = kind.part(at, facing, part);
                        BlockState state = level.getBlockState(pos);
                        check(h, state.is(kind.block()) && state.getValue(DeskCornerBlock.LEFT) == part.left()
                                && state.getValue(DeskCornerBlock.DEEP) == part.deep() && state.getValue(PanelBlock.FACING) == facing,
                                kind.name() + " " + facing + ": " + part + " is not at " + pos + ": " + state);
                        check(h, DeskCornerBlock.origin(pos, state).equals(at) && DeskCornerBlock.piece(pos, state).size() == kind.parts().size(),
                                part + " does not find its origin and piece");
                        if (be(level, pos) != null) {
                            entities++;
                            check(h, DeskCornerGeometry.faceOf(kind.inside(), kind.length(), part.left(), part.deep()) >= 0, "a block entity on a body block");
                        }
                        check(h, DeskCornerBlock.faceEntity(level, pos) != null, part + " forwards to no face");
                    }
                    check(h, entities == kind.length(), kind.name() + ": " + entities + " block entities, not " + kind.length());
                    for (int face = 0; face < kind.length(); face++) {
                        PanelBlockEntity panel = be(level, kind.core(at, facing, face));
                        PanelGroup group = panel.group();
                        check(h, panel.panelType() == kind.type() && group.columns() == 1 && group.rows() == 1 && group.members().size() == 1,
                                kind.name() + " face " + face + " is a group of its own");
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void aCornerIsRefusedWholeWhenAnyOfItsBlocksIsTaken(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "CornerBlocked", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : KINDS) {
                List<DeskCornerGeometry.Part> parts = kind.parts();
                for (DeskCornerGeometry.Part taken : new DeskCornerGeometry.Part[] {parts.get(1), parts.get(parts.size() / 2), parts.get(parts.size() - 1)}) {
                    for (Direction facing : new Direction[] {Direction.NORTH, Direction.WEST}) {
                        clear(level, floor);
                        BlockPos obstacle = kind.part(at, facing, taken);
                        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
                        check(h, !place(player, new ItemStack(kind.item()), at, facing), kind.name() + " placed over a block at " + taken);
                        check(h, cornerBlocks(level, floor) == 0, "left blocks behind when refused");
                        check(h, level.getBlockState(obstacle).is(Blocks.STONE), "replaced the obstacle");
                        check(h, player.getMainHandItem().getCount() == 1, "used up the item when refused");
                    }
                }
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void breakingAnyBlockRemovesTheCornerWithOneDropTheFinishAndItsComponents(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "CornerBreak", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : new Kind[] {new Kind(true, 1), new Kind(false, 1)}) {
                for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                    List<DeskCornerGeometry.Part> parts = kind.parts();
                    for (int i = 0; i < parts.size(); i += Math.max(1, parts.size() / 4)) {
                        DeskCornerGeometry.Part broken = parts.get(i);
                        Direction facing = Direction.from2DDataValue(i % 4);
                        clear(level, floor);
                        player.setGameMode(GameType.SURVIVAL);
                        check(h, place(player, PanelFinish.withFinish(new ItemStack(kind.item()), PanelFinish.CREAM), at, facing), "not placed");
                        int mounted = 0;
                        for (int face = 0; face < kind.length(); face++) {
                            BlockPos corePos = kind.core(at, facing, face);
                            check(h, PanelOps.placeDirect(level, corePos, 0, 0, ComponentTypes.LAMP, Footprint.of(4, 4)) != null
                                    && PanelOps.placeDirect(level, corePos, 6, 2, ComponentTypes.LAMP, Footprint.of(4, 4)) != null, "components not mounted");
                            mounted += 2;
                        }
                        clearItems(level, floor);
                        player.setGameMode(mode);
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        check(h, player.gameMode.destroyBlock(kind.part(at, facing, broken)), broken + " would not break");
                        check(h, cornerBlocks(level, floor) == 0, "breaking " + broken + " left " + cornerBlocks(level, floor) + " blocks");
                        int items = dropped(level, floor, kind.item());
                        check(h, items == (mode == GameType.SURVIVAL ? 1 : 0), kind.name() + " " + mode + ": breaking " + broken + " dropped " + items);
                        check(h, droppedComponents(level, floor) == mounted, mode + ": breaking " + broken + " dropped " + droppedComponents(level, floor)
                                + " of the " + mounted + " components");
                        if (mode == GameType.SURVIVAL) {
                            check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream().filter(e -> e.getItem().is(kind.item()))
                                    .allMatch(e -> PanelFinish.of(e.getItem()) == PanelFinish.CREAM), "the dropped corner lost its finish");
                        }
                    }
                }
                // a corner that loses a block another way falls apart and gives its item back once
                clear(level, floor);
                player.setGameMode(GameType.SURVIVAL);
                check(h, place(player, new ItemStack(kind.item()), at, Direction.SOUTH), "not placed");
                level.setBlock(kind.part(at, Direction.SOUTH, kind.parts().get(kind.parts().size() - 1)), Blocks.AIR.defaultBlockState(), 3);
                check(h, cornerBlocks(level, floor) == 0, "a broken corner left " + cornerBlocks(level, floor) + " blocks");
                check(h, dropped(level, floor, kind.item()) == 1, "a corner that fell apart gives one item back, not " + dropped(level, floor, kind.item()));
            }
        });
    }

    // ---- mounting, aiming, operating, wiring

    private static ItemStack component(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
        return stack;
    }

    /** Puts the player a metre out from a point of the face on the operator's side, looking at it, and returns what a click would hit. */
    private static BlockHitResult aimAt(ServerLevel level, FakePlayer player, PanelGeometry.Frame f, BlockPos corePos, double cu, double cv) {
        Vec3 target = new Vec3(corePos.getX() + f.x(cu, cv, 0), corePos.getY() + f.y(cu, cv, 0), corePos.getZ() + f.z(cu, cv, 0));
        Vec3 eye = target.add(f.downX * 1.0, 0.9, f.downZ * 1.0);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        Vec3 look = player.getLookAngle();
        return level.clip(new ClipContext(player.getEyePosition(), player.getEyePosition().add(look.scale(6.0)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void everyAngledFaceIsFoundThroughAnyBlockOfTheCornerAndTakesComponentsThroughThePlayerPath(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "CornerMount", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : KINDS) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(kind.item()), at, facing), kind.name() + " not placed");
                    java.util.Set<BlockPos> blocksHit = new java.util.HashSet<>();
                    for (int face = 0; face < kind.length(); face++) {
                        BlockPos corePos = kind.core(at, facing, face);
                        PanelBlockEntity panel = be(level, corePos);
                        PanelGeometry.Frame f = frameOf(panel);
                        for (int[] want : new int[][] {{0, 0}, {15, 9}, {8, 5}, {3, 2}, {12, 7}}) {
                            BlockHitResult hit = aimAt(level, player, f, corePos, want[0] + 0.5, want[1] + 0.5);
                            check(h, hit.getType() == HitResult.Type.BLOCK, kind.name() + " " + facing + " face " + face + ": the look at " + want[0] + "," + want[1] + " hit nothing");
                            blocksHit.add(hit.getBlockPos());
                            // the client path: the block under the crosshair, resolved to the face under the ray, then the cell
                            PanelBlockEntity found = DeskCornerBlock.faceEntity(level, hit.getBlockPos(), player.getEyePosition(), player.getLookAngle());
                            int[] cell = new int[2];
                            check(h, found == panel && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell)
                                            && cell[0] == want[0] && cell[1] == want[1],
                                    kind.name() + " " + facing + " face " + face + ": aimed at " + want[0] + "," + want[1] + " through " + hit.getBlockPos()
                                            + ", found " + (found == null ? "no face" : found.getBlockPos() + " " + cell[0] + "," + cell[1]));
                        }
                        // mount through the real player path at a cell found by aiming, and with the real Place packet
                        BlockHitResult hit = aimAt(level, player, f, corePos, 3.5, 2.5);
                        int[] cell = new int[2];
                        PanelBlockEntity found = DeskCornerBlock.faceEntity(level, hit.getBlockPos(), player.getEyePosition(), player.getLookAngle());
                        check(h, found == panel && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell), "no cell");
                        player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                        check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, cell[0], cell[1]) == PanelGroup.Result.OK,
                                kind.name() + " " + facing + " face " + face + ": a lamp on the angled face");
                        check(h, panel.claimAt(cell[0], cell[1]) != null && player.getMainHandItem().isEmpty(), "the lamp does not hold its cell");
                        player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                        check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, 8, 9) == PanelGroup.Result.BLOCKED, "a lamp hanging off the front of the face");
                        check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, cell[0], cell[1]) != PanelGroup.Result.OK, "a second lamp on the first");
                        sendPlace(level, player, corePos, 0, 6);
                        check(h, panel.claimAt(0, 6) != null, kind.name() + " " + facing + " face " + face + ": the Place packet did not mount on cell 0,6");
                        check(h, panel.owned().size() == 2, "two lamps on the face, not " + panel.owned().size());
                    }
                    check(h, blocksHit.size() >= Math.min(2, kind.length() + 1), kind.name() + " " + facing + ": the faces were only reached through " + blocksHit);
                    for (DeskCornerGeometry.Part part : kind.parts()) {
                        BlockPos p = kind.part(at, facing, part);
                        if (DeskCornerGeometry.faceOf(kind.inside(), kind.length(), part.left(), part.deep()) < 0) {
                            check(h, be(level, p) == null && !((DeskCornerBlock) level.getBlockState(p).getBlock()).cellUsable(level.getBlockState(p), 0, 0),
                                    part + " is a body block: no block entity, no cells");
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

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 2400)
    public static void aComponentOnAnAngledFaceIsOperatedWiredAndReachedLikeOnAnyDesk(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "CornerOperate", GameType.SURVIVAL);
            FakePlayer far = player(level, "CornerFar", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : new Kind[] {new Kind(true, 1), new Kind(false, 1)}) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST}) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(kind.item()), at, facing), "not placed");
                    BlockPos corePos = kind.core(at, facing, kind.length() - 1);
                    PanelComponent button = PanelOps.placeDirect(level, corePos, 2, 1, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
                    PanelComponent lamp = PanelOps.placeDirect(level, corePos, 11, 5, ComponentTypes.LAMP, Footprint.of(4, 4));
                    check(h, button != null && lamp != null, "components not mounted");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
                    player.setPos(Vec3.atBottomCenterOf(corePos.relative(facing, 1)));
                    WiringOps.Result wired = WiringOps.bind(player, new PanelPayloads.Bind(corePos, lamp.id(), false, 0,
                            WiringOps.BindKind.COMPONENT.ordinal(), BlockPos.ZERO, "", corePos, button.id(), 0, 1.0));
                    check(h, wired == WiringOps.Result.OK, kind.name() + " " + facing + ": lamp bound to the button: " + wired);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    sendOperate(level, player, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 1.0 && lamp.input(0) == 1.0, kind.name() + " " + facing + ": the press did not reach the lamp");
                    sendOperate(level, player, corePos, button, OperateAction.RELEASE, 0.0);
                    check(h, button.output(0) == 0.0 && lamp.input(0) == 0.0, "the release did not reach the lamp");
                    far.setPos(Vec3.atBottomCenterOf(corePos.relative(facing, 20)));
                    sendOperate(level, far, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 0.0, "a player out of reach pressed the button");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
                    sendOperate(level, player, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 0.0, "pressed through a wrench");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    BlockPos body = at; // the origin holds no face (the straight run arrives there)
                    if (DeskCornerGeometry.faceOf(kind.inside(), kind.length(), 0, 0) < 0) {
                        check(h, !PanelOps.operate(player, body, button.id(), OperateAction.PRESS, 1.0), "a press addressed to a body block was accepted");
                    }
                }
            }
        });
    }

    // ---- joining, finishes, saves

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void everyFaceIsItsOwnGroupAndNeverJoinsTheStraightDesksItContinues(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "CornerGroups", GameType.SURVIVAL);
            BlockPos at = floor.above().relative(Direction.NORTH.getClockWise(), 3);
            for (Kind kind : new Kind[] {new Kind(true, 1), new Kind(false, 1)}) {
                clear(level, floor);
                Direction facing = Direction.NORTH;
                check(h, place(player, new ItemStack(kind.item()), at, facing), "corner not placed");
                BlockPos run = at.relative(facing.getCounterClockWise());
                check(h, place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK.get()), run, facing)
                        && place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get()), run.relative(facing.getCounterClockWise(), 2), facing),
                        "desks not placed");
                PanelBlockEntity desk = be(level, run);
                java.util.Set<PanelGroup> groups = new java.util.HashSet<>();
                for (int face = 0; face < kind.length(); face++) {
                    PanelBlockEntity corner = be(level, kind.core(at, facing, face));
                    check(h, corner.group().members().size() == 1 && corner.group() != desk.group(), "a face joined the desk or another face");
                    groups.add(corner.group());
                }
                check(h, groups.size() == kind.length(), "the faces are not one group each");
                check(h, desk.group().members().size() == 3, "the desk run took a corner in");
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void thePanelWrenchPaintsTheWholeCornerAndItDropsAndPlacesWithThatFinish(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "CornerPaint", GameType.CREATIVE);
            BlockPos at = floor.above();
            for (Kind kind : new Kind[] {new Kind(true, 1), new Kind(false, 1)}) {
                clear(level, floor);
                Direction facing = Direction.EAST;
                check(h, place(player, new ItemStack(kind.item()), at, facing), "not placed");
                for (DeskCornerGeometry.Part clicked : kind.parts()) {
                    PanelFinish before = PanelFinish.of(level.getBlockState(at));
                    check(h, PanelFinishes.cycle(level, kind.part(at, facing, clicked), player), "finish did not change from " + clicked);
                    PanelFinish after = PanelFinish.of(level.getBlockState(at));
                    check(h, after != before, "the origin did not change");
                    for (DeskCornerGeometry.Part each : kind.parts()) {
                        check(h, PanelFinish.of(level.getBlockState(kind.part(at, facing, each))) == after, clicked + ": " + each + " did not take the finish");
                    }
                }
                check(h, kind.block().getStateDefinition().getPossibleStates().size() == 4 * 2 * 2 * 5, "states: 4 facings, 2 x 2 parts, 5 finishes");
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void mountedComponentsOnEveryFaceAndEveryBlockStateSurviveASaveAndReload(GameTestHelper h) {
        fixture(h, 7, (level, floor) -> {
            var registries = level.registryAccess();
            FakePlayer player = player(level, "CornerSave", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Kind kind : new Kind[] {new Kind(true, 1), new Kind(false, 1)}) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    check(h, place(player, PanelFinish.withFinish(new ItemStack(kind.item()), PanelFinish.GREY), at, facing), "not placed");
                    for (int face = 0; face < kind.length(); face++) {
                        BlockPos corePos = kind.core(at, facing, face);
                        PanelBlockEntity panel = be(level, corePos);
                        PanelComponent lamp = PanelOps.placeDirect(level, corePos, 3, 2, ComponentTypes.LAMP, Footprint.of(4, 4));
                        PanelComponent dial = PanelOps.placeDirect(level, corePos, 8, 4, ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4));
                        check(h, lamp != null && dial != null, "components not mounted");
                        final String label = "ANG-" + face;
                        lamp.setLabel(label);
                        CompoundTag saved = panel.saveWithoutMetadata(registries);
                        PanelBlockEntity reloaded = new PanelBlockEntity(corePos, level.getBlockState(corePos));
                        reloaded.loadWithComponents(saved, registries);
                        check(h, reloaded.panelType() == kind.type() && reloaded.savePanel(false).equals(panel.savePanel(false)),
                                kind.name() + " " + facing + " face " + face + ": the save is not a fixed point of load");
                        check(h, reloaded.owned().size() == 2 && reloaded.claimAt(3, 2) != null && reloaded.claimAt(10, 7) != null
                                && reloaded.owned().stream().anyMatch(c -> label.equals(c.label())), "the reloaded face lost a component");
                        check(h, reloaded.claimAt(3, 2).owner().equals(corePos), "claims are absolute positions of the core");
                    }
                    for (DeskCornerGeometry.Part part : kind.parts()) {
                        BlockState state = level.getBlockState(kind.part(at, facing, part));
                        BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                        check(h, restored == state, part + " state " + state + " did not survive a save");
                    }
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theCornersAreRegisteredWithItemsRecipesAndTheCreativeTab(GameTestHelper h) {
        // The tab's display list is filled only when it is built; build it here so the check does not depend on test order.
        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(h.getLevel().enabledFeatures(), true, h.getLevel().registryAccess());
        for (Kind kind : KINDS) {
            String path = "control_panel_desk_corner_" + (kind.inside() ? "inside" : "outside") + (kind.length() == 1 ? "" : "_" + kind.length());
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", path);
            check(h, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id) == kind.block(), id + ": block not registered");
            check(h, net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) == kind.item(), id + ": item not registered");
            check(h, BwrItems.TAB.get().getDisplayItems().stream().anyMatch(s -> s.is(kind.item())), id + " is not in the creative tab");
            var manager = h.getLevel().getServer().getRecipeManager();
            boolean vanilla = manager.byKey(id).isPresent();
            boolean mekanism = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", id.getPath() + "_mekanism")).isPresent();
            check(h, vanilla ^ mekanism, id + ": exactly one recipe must be live (vanilla " + vanilla + ", Mekanism " + mekanism + ")");
        }
        h.succeed();
    }
}
