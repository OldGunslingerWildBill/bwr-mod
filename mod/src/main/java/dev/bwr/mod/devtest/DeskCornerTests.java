package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.CornerPart;
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
 * The 45 degree desk corners ({@link DeskCornerBlock}, CONTROL-PANELS-SPEC section 14), inside and outside. The
 * geometry (the frame is a level diagonal top, the face lies on the console, the shapes hold every cell and leave the knee
 * space, the models cover the face), placing the four blocks through the real item for every facing and refusing the
 * whole piece when one is taken, breaking any block (one drop, the finish, the components), mounting a component on the
 * angled face through the real player path with the cell found by a real block ray cast (so a click on any block of the
 * corner finds the face), operating it through the real operate packet handler, wiring a component to another on the
 * face, the finishes, and save/load. Fixtures live around 150,000 / 150,000.
 */
@GameTestHolder("bwrcorner")
@PrefixGameTestTemplate(false)
public final class DeskCornerTests {
    private DeskCornerTests() {
    }

    private static final int ORIGIN_X = 150_000;
    private static final int ORIGIN_Z = 150_000;
    private static final int FLOOR_Y = 100;
    private static final boolean[] KINDS = {true, false};

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
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 0, -6), floor.offset(6, 6, 6))) {
            BlockState want = pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != want) {
                level.setBlock(pos, want, 2);
            }
        }
        clearItems(level, floor);
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(8);
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

    private static Block block(boolean inside) {
        return inside ? BwrBlocks.CONTROL_PANEL_DESK_CORNER_INSIDE.get() : BwrBlocks.CONTROL_PANEL_DESK_CORNER_OUTSIDE.get();
    }

    private static Item item(boolean inside) {
        return inside ? BwrItems.CONTROL_PANEL_DESK_CORNER_INSIDE.get() : BwrItems.CONTROL_PANEL_DESK_CORNER_OUTSIDE.get();
    }

    private static PanelType type(boolean inside) {
        return inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE;
    }

    private static String name(boolean inside) {
        return inside ? "inside" : "outside";
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

    private static BlockPos part(BlockPos at, Direction facing, boolean inside, CornerPart part) {
        return part.at(at, facing, inside);
    }

    private static BlockPos core(BlockPos at, Direction facing, boolean inside) {
        return part(at, facing, inside, DeskCornerGeometry.core(inside));
    }

    private static int cornerBlocks(ServerLevel level, BlockPos floor) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 6, 6))) {
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

    // ---- geometry

    /** Footprint coordinates (x, f) of a point of the core block's local frame under facing NORTH (x -> lx = 1 - x, z -> f = 1 - z). */
    private static double[] footprint(boolean inside, double blockX, double blockZ) {
        CornerPart core = DeskCornerGeometry.core(inside);
        return new double[] {(1.0 - blockX) + DeskCornerGeometry.blockX(core), (1.0 - blockZ) + DeskCornerGeometry.blockF(core, inside)};
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theCornerFramesAreLevelDiagonalTopsOnTheConsoleSixteenCellsAcrossAndTenDeep(GameTestHelper h) {
        for (boolean inside : KINDS) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame f = PanelGeometry.frame(type(inside), facing);
                String what = name(inside) + " " + facing;
                check(h, near(f.nx, 0) && near(f.ny, 1) && near(f.nz, 0), what + ": the face is level, normal straight up");
                check(h, Math.abs(f.vLength - 1.0) < 1.0e-9, what + ": a row is as long as a column: " + f.vLength);
                check(h, Math.abs(f.rightX * f.downX + f.rightZ * f.downZ) < 1.0e-9, what + ": columns and rows are square");
                // 45 degrees to the block grid
                check(h, Math.abs(Math.abs(f.rightX) - Math.sqrt(0.5)) < 1.0e-9 && Math.abs(Math.abs(f.rightZ) - Math.sqrt(0.5)) < 1.0e-9,
                        what + ": the columns run along a diagonal: " + f.rightX + "," + f.rightZ);
                check(h, near(f.y(0, 0, 0), PanelGeometry.DESK_TOP), what + ": the top is " + PanelGeometry.DESK_TOP + " up");
                check(h, !type(inside).hasRows() && !type(inside).hasColumns() && type(inside).nextRow(BlockPos.ZERO, facing) == null
                        && type(inside).previousRow(BlockPos.ZERO, facing) == null, what + ": a corner joins nothing");
                check(h, PanelGeometry.rows(type(inside)) == 10, what + ": ten rows");
                for (int cx = 0; cx < 16; cx++) {
                    for (int cy = 0; cy < 16; cy++) {
                        check(h, PanelGeometry.cellUsable(type(inside), cx, cy) == (cy < 10), what + ": cell " + cx + "," + cy);
                    }
                }
            }
            // every lattice point of the 16 x 10 grid lies on the top of the console, between the foot of the riser and the front edge
            PanelGeometry.Frame north = PanelGeometry.frame(type(inside), Direction.NORTH);
            for (int cu = 0; cu <= 16; cu++) {
                for (int cv = 0; cv <= 10; cv++) {
                    double[] p = footprint(inside, north.x(cu, cv, 0), north.z(cu, cv, 0));
                    double t = DeskCornerGeometry.depth(inside, p[0], p[1]);
                    check(h, t >= PanelGeometry.DESK_FACE_BACK - 1.0e-9 && t <= PanelGeometry.DESK_FRONT + 1.0e-9,
                            name(inside) + ": grid point " + cu + "," + cv + " is at depth " + t + ", off the top of the console");
                    if (cu % 16 == 0 && cv % 10 == 0) {
                        continue;
                    }
                }
            }
            // the rows run toward the operator: the front edge (row 10) is deeper than the foot of the riser (row 0)
            double[] back = footprint(inside, north.x(8, 0, 0), north.z(8, 0, 0));
            double[] front = footprint(inside, north.x(8, 10, 0), north.z(8, 10, 0));
            check(h, near(DeskCornerGeometry.depth(inside, back[0], back[1]), PanelGeometry.DESK_FACE_BACK)
                    && near(DeskCornerGeometry.depth(inside, front[0], front[1]), PanelGeometry.DESK_FRONT),
                    name(inside) + ": row 0 is the foot of the riser and row 10 the front edge");
            // and the other three facings are the first turned
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame f = PanelGeometry.frame(type(inside), facing);
                int turns = (facing.get2DDataValue() - Direction.NORTH.get2DDataValue() + 4) % 4;
                double ox = north.x(3, 4, 0) - 0.5;
                double oz = north.z(3, 4, 0) - 0.5;
                for (int i = 0; i < turns; i++) {
                    double nx = -oz;
                    oz = ox;
                    ox = nx;
                }
                check(h, near(f.x(3, 4, 0) - 0.5, ox) && near(f.z(3, 4, 0) - 0.5, oz), name(inside) + " " + facing + " is NORTH turned");
            }
        }
        h.succeed();
    }

    /** The block (relative to the origin at ZERO, facing north) of a corner that contains the plan point, as a part. */
    private static CornerPart partAt(boolean inside, double x, double z) {
        int left = (int) Math.floor(x);   // facing north: the left part is at +x
        int deep = inside ? -(int) Math.floor(z) : (int) Math.floor(z); // inside: toward the operator is -z; outside +z
        for (CornerPart part : CornerPart.values()) {
            if (part.left() == left && part.deep() == deep) {
                return part;
            }
        }
        return null;
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theShapesHoldEveryCellOfTheFaceAtItsHeightAndLeaveTheKneeSpace(GameTestHelper h) {
        for (boolean inside : KINDS) {
            PanelGeometry.Frame f = PanelGeometry.frame(type(inside), Direction.NORTH);
            BlockPos corePos = core(BlockPos.ZERO, Direction.NORTH, inside);
            for (int cu = 0; cu < 16; cu++) {
                for (int cv = 0; cv < 10; cv++) {
                    double x = corePos.getX() + f.x(cu + 0.5, cv + 0.5, 0);
                    double z = corePos.getZ() + f.z(cu + 0.5, cv + 0.5, 0);
                    BlockPos at = BlockPos.containing(x, 0, z);
                    CornerPart part = partAt(inside, x, z);
                    check(h, part != null && part.at(BlockPos.ZERO, Direction.NORTH, inside).equals(at),
                            name(inside) + ": cell " + cu + "," + cv + " is over " + at + ", not a block of the corner");
                    VoxelShape shape = DeskCornerGeometry.shape(inside, part, Direction.NORTH);
                    BlockHitResult hit = shape.clip(new Vec3(x, 2.0, z), new Vec3(x, -1.0, z), at);
                    check(h, hit != null && Math.abs(hit.getLocation().y - 0.75) < 1.0e-6 && hit.getDirection() == Direction.UP,
                            name(inside) + ": a ray down onto cell " + cu + "," + cv + " finds " + (hit == null ? "nothing" : hit.getLocation().y));
                }
            }
            // the knee space: at the middle of the face, 0.3 up and 0.55 from the back is air, but 0.3 up and 0.1 from the back is solid
            double[] centre = DeskCornerGeometry.faceCentre(inside);
            CornerPart core = DeskCornerGeometry.core(inside);
            for (double[] probe : new double[][] {{0.55, 0.3, 0}, {0.1, 0.3, 1}}) {
                double t = probe[0];
                // walk from the face centre (depth 0.4375) along the row direction to depth t
                double[] v = DeskCornerGeometry.rowDirection(inside);
                double px = centre[0] + v[0] * (t - 0.4375);
                double pf = centre[1] + v[1] * (t - 0.4375);
                double lx = px - DeskCornerGeometry.blockX(core);
                double lf = pf - DeskCornerGeometry.blockF(core, inside);
                double bx = 1.0 - lx;
                double bz = 1.0 - lf;
                VoxelShape shape = DeskCornerGeometry.shape(inside, partAt(inside, corePos.getX() + bx, corePos.getZ() + bz), Direction.NORTH);
                BlockPos at = BlockPos.containing(corePos.getX() + bx, 0, corePos.getZ() + bz);
                double ex = corePos.getX() + bx - at.getX();
                double ez = corePos.getZ() + bz - at.getZ();
                boolean solid = Shapes.joinIsNotEmpty(shape, Shapes.box(ex - 0.01, probe[1] - 0.01, ez - 0.01, ex + 0.01, probe[1] + 0.01, ez + 0.01),
                        BooleanOp.AND);
                check(h, solid == (probe[2] == 1), name(inside) + ": depth " + t + " at 0.3 up should be " + (probe[2] == 1 ? "solid" : "air"));
            }
            // the whole corner's shapes are bounded by the block and the shelf
            for (CornerPart part : CornerPart.values()) {
                VoxelShape shape = DeskCornerGeometry.shape(inside, part, Direction.NORTH);
                check(h, !shape.isEmpty() && shape.min(Direction.Axis.X) >= -1.0e-9 && shape.max(Direction.Axis.X) <= 1.0 + 1.0e-9
                        && shape.min(Direction.Axis.Z) >= -1.0e-9 && shape.max(Direction.Axis.Z) <= 1.0 + 1.0e-9
                        && shape.max(Direction.Axis.Y) <= 1.13, name(inside) + " " + part + ": shape out of its block");
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
    public static void theFourBlockModelsTogetherCoverEveryCellOfTheMountingFace(GameTestHelper h) throws IOException {
        for (boolean inside : KINDS) {
            Map<CornerPart, List<Face>> models = new HashMap<>();
            for (CornerPart part : CornerPart.values()) {
                List<Face> faces = readObj("/assets/bwr/models/block/control_panel/desk_corner_" + name(inside) + "_" + part.getSerializedName() + ".obj");
                check(h, !faces.isEmpty() && faces.size() <= 40, name(inside) + " " + part + " is " + faces.size() + " faces");
                for (Face face : faces) {
                    for (double[] c : face.corners()) {
                        check(h, c[0] >= -1.0e-5 && c[0] <= 1.00001 && c[2] >= -1.0e-5 && c[2] <= 1.00001 && c[1] >= -1.0e-5 && c[1] <= 1.1001,
                                name(inside) + " " + part + " " + face.material() + " leaves its block: " + java.util.Arrays.toString(c));
                    }
                }
                models.put(part, faces);
            }
            PanelGeometry.Frame f = PanelGeometry.frame(type(inside), Direction.NORTH);
            BlockPos corePos = core(BlockPos.ZERO, Direction.NORTH, inside);
            for (int cu = 0; cu < 16; cu++) {
                for (int cv = 0; cv < 10; cv++) {
                    double x = corePos.getX() + f.x(cu + 0.5, cv + 0.5, 0);
                    double z = corePos.getZ() + f.z(cu + 0.5, cv + 0.5, 0);
                    CornerPart part = partAt(inside, x, z);
                    BlockPos at = part.at(BlockPos.ZERO, Direction.NORTH, inside);
                    boolean covered = false;
                    for (Face face : models.get(part)) {
                        if (face.material().equals("face") && face.corners().stream().allMatch(c -> Math.abs(c[1] - 0.75) < 1.0e-5)
                                && inPlan(face.corners(), x - at.getX(), z - at.getZ())) {
                            covered = true;
                        }
                    }
                    check(h, covered, name(inside) + ": cell " + cu + "," + cv + " is not on the top of the model of " + part);
                }
            }
        }
        h.succeed();
    }

    // ---- placing

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void aCornerIsFourBlocksOneBlockEntityAndOneItemForEveryFacing(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "CornerPlace", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(item(inside)), at, facing), name(inside) + " not placed facing " + facing);
                    check(h, player.getMainHandItem().isEmpty(), "the item was not used up");
                    check(h, cornerBlocks(level, floor) == 4, name(inside) + " " + facing + ": " + cornerBlocks(level, floor) + " blocks, not 4");
                    Direction left = facing.getClockWise();
                    Direction deep = inside ? facing : facing.getOpposite();
                    BlockPos[] expected = {at, at.relative(left), at.relative(deep), at.relative(left).relative(deep)};
                    CornerPart[] parts = {CornerPart.ORIGIN, CornerPart.LEFT, CornerPart.DEEP, CornerPart.DEEP_LEFT};
                    int entities = 0;
                    for (int i = 0; i < 4; i++) {
                        BlockState state = level.getBlockState(expected[i]);
                        check(h, state.is(block(inside)) && state.getValue(DeskCornerBlock.PART) == parts[i]
                                && state.getValue(PanelBlock.FACING) == facing, name(inside) + " " + facing + ": " + parts[i] + " is not at "
                                + expected[i] + ": " + state);
                        check(h, DeskCornerBlock.origin(expected[i], state).equals(at) && DeskCornerBlock.corePos(expected[i], state).equals(core(at, facing, inside)),
                                parts[i] + " does not find its origin and core");
                        PanelBlockEntity entity = be(level, expected[i]);
                        if (entity != null) {
                            entities++;
                            check(h, expected[i].equals(core(at, facing, inside)), "the block entity is not on the core");
                        }
                        // a click on any block finds the one face
                        PanelBlockEntity found = DeskCornerBlock.faceEntity(level, expected[i]);
                        check(h, found != null && found.getBlockPos().equals(core(at, facing, inside)), parts[i] + " does not forward to the core");
                    }
                    check(h, entities == 1, name(inside) + ": " + entities + " block entities, not 1");
                    PanelBlockEntity panel = be(level, core(at, facing, inside));
                    PanelGroup group = panel.group();
                    check(h, panel.panelType() == type(inside) && group.columns() == 1 && group.rows() == 1 && group.members().size() == 1,
                            name(inside) + ": the face is a group of its own, one block");
                    check(h, level.getBlockState(at.relative(left, 2)).isAir() && level.getBlockState(at.relative(deep, -1)).isAir(),
                            "nothing outside the 2 x 2 footprint");
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void aCornerIsRefusedWholeWhenAnyOfItsBlocksIsTaken(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "CornerBlocked", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (CornerPart taken : new CornerPart[] {CornerPart.LEFT, CornerPart.DEEP, CornerPart.DEEP_LEFT}) {
                    for (Direction facing : new Direction[] {Direction.NORTH, Direction.WEST}) {
                        clear(level, floor);
                        BlockPos obstacle = part(at, facing, inside, taken);
                        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
                        check(h, !place(player, new ItemStack(item(inside)), at, facing), name(inside) + " placed over a block at " + taken);
                        check(h, cornerBlocks(level, floor) == 0, "left blocks behind when refused");
                        check(h, level.getBlockState(obstacle).is(Blocks.STONE), "replaced the obstacle");
                        check(h, player.getMainHandItem().getCount() == 1, "used up the item when refused");
                    }
                }
                // a block outside the footprint (to the operator's right of the origin, behind it) is not in the way
                clear(level, floor);
                level.setBlock(at.relative(Direction.NORTH.getCounterClockWise()), Blocks.STONE.defaultBlockState(), 3);
                check(h, place(player, new ItemStack(item(inside)), at, Direction.NORTH), name(inside) + ": a block beside the footprint stopped it");
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void breakingAnyBlockRemovesTheCornerWithOneDropTheFinishAndItsComponents(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "CornerBreak", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                    for (CornerPart broken : CornerPart.values()) {
                        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(net.minecraft.util.RandomSource.create(broken.ordinal()));
                        clear(level, floor);
                        player.setGameMode(GameType.SURVIVAL);
                        check(h, place(player, PanelFinish.withFinish(new ItemStack(item(inside)), PanelFinish.CREAM), at, facing), "not placed");
                        BlockPos corePos = core(at, facing, inside);
                        check(h, PanelOps.placeDirect(level, corePos, 0, 0, ComponentTypes.LAMP, Footprint.of(4, 4)) != null
                                && PanelOps.placeDirect(level, corePos, 6, 2, ComponentTypes.LAMP, Footprint.of(4, 4)) != null, "components not mounted");
                        clearItems(level, floor);
                        player.setGameMode(mode);
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        check(h, player.gameMode.destroyBlock(part(at, facing, inside, broken)), broken + " would not break");
                        check(h, cornerBlocks(level, floor) == 0, "breaking the " + broken + " left " + cornerBlocks(level, floor) + " blocks");
                        int items = dropped(level, floor, item(inside));
                        check(h, items == (mode == GameType.SURVIVAL ? 1 : 0), name(inside) + " " + mode + ": breaking the " + broken + " dropped " + items);
                        check(h, droppedComponents(level, floor) == 2, mode + ": breaking the " + broken + " dropped " + droppedComponents(level, floor)
                                + " of the 2 components");
                        if (mode == GameType.SURVIVAL) {
                            check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream().filter(e -> e.getItem().is(item(inside)))
                                    .allMatch(e -> PanelFinish.of(e.getItem()) == PanelFinish.CREAM), "the dropped corner lost its finish");
                        }
                    }
                }
                // a corner that loses a block another way falls apart and gives its item back once
                clear(level, floor);
                player.setGameMode(GameType.SURVIVAL);
                check(h, place(player, new ItemStack(item(inside)), at, Direction.SOUTH), "not placed");
                level.setBlock(part(at, Direction.SOUTH, inside, CornerPart.DEEP_LEFT), Blocks.AIR.defaultBlockState(), 3);
                check(h, cornerBlocks(level, floor) == 0, "a broken corner left " + cornerBlocks(level, floor) + " blocks");
                check(h, dropped(level, floor, item(inside)) == 1, "a corner that fell apart gives one item back, not " + dropped(level, floor, item(inside)));
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

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void theAngledFaceIsFoundThroughAnyBlockOfTheCornerAndTakesComponentsThroughThePlayerPath(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "CornerMount", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(item(inside)), at, facing), "not placed");
                    BlockPos corePos = core(at, facing, inside);
                    PanelBlockEntity panel = be(level, corePos);
                    PanelGeometry.Frame f = PanelGeometry.frame(type(inside), facing);
                    java.util.Set<BlockPos> blocksHit = new java.util.HashSet<>();
                    for (int[] want : new int[][] {{0, 0}, {15, 0}, {0, 9}, {15, 9}, {8, 5}, {3, 2}, {12, 7}, {7, 9}, {1, 4}, {14, 4}}) {
                        BlockHitResult hit = aimAt(level, player, f, corePos, want[0] + 0.5, want[1] + 0.5);
                        check(h, hit.getType() == HitResult.Type.BLOCK, name(inside) + " " + facing + ": the look at cell " + want[0] + "," + want[1] + " hit nothing");
                        blocksHit.add(hit.getBlockPos());
                        // the client path: the block under the crosshair, forwarded to the face, then the cell of the ray on the face's plane
                        PanelBlockEntity found = DeskCornerBlock.faceEntity(level, hit.getBlockPos());
                        int[] cell = new int[2];
                        check(h, found == panel && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell)
                                        && cell[0] == want[0] && cell[1] == want[1],
                                name(inside) + " " + facing + ": aimed at " + want[0] + "," + want[1] + " through " + hit.getBlockPos() + ", found "
                                        + (found == null ? "no face" : cell[0] + "," + cell[1]));
                    }
                    check(h, blocksHit.size() >= 2, name(inside) + " " + facing + ": the face was only ever reached through " + blocksHit
                            + "; it should reach into the other blocks");
                    // mount through the real player path (PanelOps.place, what the Place packet calls) at cells found by aiming
                    BlockHitResult hit = aimAt(level, player, f, corePos, 3.5, 2.5);
                    int[] cell = new int[2];
                    PanelBlockEntity found = DeskCornerBlock.faceEntity(level, hit.getBlockPos());
                    check(h, found != null && PanelOps.cellAt(found, player.getEyePosition(), player.getLookAngle(), cell), "no cell");
                    player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                    check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, found.getBlockPos(), cell[0], cell[1]) == PanelGroup.Result.OK,
                            name(inside) + " " + facing + ": a lamp on the angled face");
                    check(h, panel.claimAt(cell[0], cell[1]) != null && player.getMainHandItem().isEmpty(), "the lamp does not hold its cell or the item was kept");
                    // not past the ten rows, and not in a cell that is taken
                    player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                    check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, 8, 9) == PanelGroup.Result.BLOCKED,
                            "a lamp hanging off the front of the face");
                    check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, cell[0], cell[1]) != PanelGroup.Result.OK, "a second lamp on the first");
                    check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, corePos, 12, 4) == PanelGroup.Result.OK, "a lamp on free cells");
                    // the real packet: Place, encoded and decoded, given to the real handler, the body block named as the click
                    player.setItemInHand(InteractionHand.MAIN_HAND, component(BwrItems.LAMP.get()));
                    sendPlace(level, player, corePos, 0, 6);
                    check(h, panel.claimAt(0, 6) != null, name(inside) + " " + facing + ": the Place packet did not mount on cell 0,6");
                    check(h, panel.owned().size() == 3, "three lamps, not " + panel.owned().size());
                    // body blocks hold no cells and no block entity
                    for (CornerPart part : CornerPart.values()) {
                        BlockPos p = part(at, facing, inside, part);
                        if (!p.equals(corePos)) {
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

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 1200)
    public static void aComponentOnTheAngledFaceIsOperatedWiredAndReachedLikeOnAnyDesk(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "CornerOperate", GameType.SURVIVAL);
            FakePlayer far = player(level, "CornerFar", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                    clear(level, floor);
                    check(h, place(player, new ItemStack(item(inside)), at, facing), "not placed");
                    BlockPos corePos = core(at, facing, inside);
                    PanelBlockEntity panel = be(level, corePos);
                    PanelComponent button = PanelOps.placeDirect(level, corePos, 2, 1, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
                    PanelComponent lamp = PanelOps.placeDirect(level, corePos, 11, 5, ComponentTypes.LAMP, Footprint.of(4, 4));
                    check(h, button != null && lamp != null, "components not mounted");
                    // wire the lamp to the button with a linker in hand, the real bind
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
                    player.setPos(Vec3.atBottomCenterOf(corePos.relative(facing, 1)));
                    WiringOps.Result wired = WiringOps.bind(player, new PanelPayloads.Bind(corePos, lamp.id(), false, 0,
                            WiringOps.BindKind.COMPONENT.ordinal(), BlockPos.ZERO, "", corePos, button.id(), 0, 1.0));
                    check(h, wired == WiringOps.Result.OK, name(inside) + " " + facing + ": lamp bound to the button: " + wired);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    // press and release through the real operate packet handler
                    sendOperate(level, player, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 1.0 && lamp.input(0) == 1.0, name(inside) + " " + facing + ": the press did not reach the lamp: "
                            + button.output(0) + " / " + lamp.input(0));
                    sendOperate(level, player, corePos, button, OperateAction.RELEASE, 0.0);
                    check(h, button.output(0) == 0.0 && lamp.input(0) == 0.0, "the release did not reach the lamp");
                    // reach: a player twenty blocks away is refused (server side check), as on any panel
                    far.setPos(Vec3.atBottomCenterOf(corePos.relative(facing, 20)));
                    sendOperate(level, far, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 0.0, "a player out of reach pressed the button");
                    // a tool in hand takes the click for itself
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
                    sendOperate(level, player, corePos, button, OperateAction.PRESS, 1.0);
                    check(h, button.output(0) == 0.0, "pressed through a wrench");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    // a body block is not a panel: operating "on" it does nothing
                    BlockPos body = part(at, facing, inside, inside ? CornerPart.ORIGIN : CornerPart.ORIGIN);
                    check(h, !PanelOps.operate(player, body, button.id(), OperateAction.PRESS, 1.0), "a press addressed to a body block was accepted");
                    // the networking wrench line for the wired pair starts and ends on the face: centre of each component
                    check(h, panel.owned().size() == 2, "two components");
                }
            }
        });
    }

    // ---- joining, finishes, saves

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void aCornerNeverJoinsTheStraightDesksItContinuesOrAnotherCorner(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "CornerGroups", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                clear(level, floor);
                Direction facing = Direction.NORTH;
                check(h, place(player, new ItemStack(item(inside)), at, facing), "corner not placed");
                // a straight desk run on the corner's right where the run arrives, and one more corner of the other kind further on
                BlockPos run = at.relative(facing.getCounterClockWise());
                check(h, place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK.get()), run, facing)
                        && place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get()), run.relative(facing.getCounterClockWise(), 2), facing),
                        "desks not placed");
                PanelBlockEntity corner = be(level, core(at, facing, inside));
                PanelBlockEntity desk = be(level, run);
                check(h, corner.group().members().size() == 1 && corner.group() != desk.group(), "the corner joined the desk beside it");
                check(h, desk.group().members().size() == 3 && !desk.group().contains(corner.getBlockPos()), "the desk run took the corner in");
                // an adjacent corner of the same kind and facing is another group
                BlockPos other = at.relative(facing.getClockWise(), 3);
                check(h, place(player, new ItemStack(item(inside)), other, facing), "second corner not placed");
                PanelBlockEntity second = be(level, core(other, facing, inside));
                check(h, second.group() != corner.group() && second.group().members().size() == 1, "two corners joined");
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void thePanelWrenchPaintsTheWholeCornerAndItDropsAndPlacesWithThatFinish(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "CornerPaint", GameType.CREATIVE);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                clear(level, floor);
                Direction facing = Direction.EAST;
                check(h, place(player, new ItemStack(item(inside)), at, facing), "not placed");
                for (CornerPart clicked : CornerPart.values()) {
                    PanelFinish before = PanelFinish.of(level.getBlockState(at));
                    check(h, PanelFinishes.cycle(level, part(at, facing, inside, clicked), player), "finish did not change from " + clicked);
                    PanelFinish after = PanelFinish.of(level.getBlockState(at));
                    check(h, after != before, "the origin did not change");
                    for (CornerPart each : CornerPart.values()) {
                        check(h, PanelFinish.of(level.getBlockState(part(at, facing, inside, each))) == after,
                                clicked + ": " + each + " did not take the finish " + after);
                    }
                }
                check(h, block(inside).getStateDefinition().getPossibleStates().size() == 4 * 4 * 5, "states: 4 facings, 4 parts, 5 finishes");
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner", timeoutTicks = 600)
    public static void aMountedComponentOnTheAngledFaceAndEveryBlockStateSurviveASaveAndReload(GameTestHelper h) {
        fixture(h, 7, (level, floor) -> {
            var registries = level.registryAccess();
            FakePlayer player = player(level, "CornerSave", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (boolean inside : KINDS) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    check(h, place(player, PanelFinish.withFinish(new ItemStack(item(inside)), PanelFinish.GREY), at, facing), "not placed");
                    BlockPos corePos = core(at, facing, inside);
                    PanelBlockEntity panel = be(level, corePos);
                    PanelComponent lamp = PanelOps.placeDirect(level, corePos, 3, 2, ComponentTypes.LAMP, Footprint.of(4, 4));
                    PanelComponent dial = PanelOps.placeDirect(level, corePos, 8, 4, ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4));
                    check(h, lamp != null && dial != null, "components not mounted");
                    lamp.setLabel("ANG-1");
                    for (CornerPart part : CornerPart.values()) {
                        BlockState state = level.getBlockState(part(at, facing, inside, part));
                        BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                        check(h, restored == state && restored.getValue(DeskCornerBlock.PART) == part, part + " state " + state + " did not survive a save");
                    }
                    CompoundTag saved = panel.saveWithoutMetadata(registries);
                    PanelBlockEntity reloaded = new PanelBlockEntity(corePos, level.getBlockState(corePos));
                    reloaded.loadWithComponents(saved, registries);
                    check(h, reloaded.panelType() == type(inside), "reloaded as " + reloaded.panelType());
                    check(h, reloaded.savePanel(false).equals(panel.savePanel(false)), name(inside) + " " + facing + ": the save is not a fixed point of load");
                    check(h, reloaded.owned().size() == 2 && reloaded.claimAt(3, 2) != null && reloaded.claimAt(10, 7) != null
                            && reloaded.owned().stream().anyMatch(c -> "ANG-1".equals(c.label())), "the reloaded face lost a component");
                    check(h, reloaded.cellsUsable(0, 0, 16, 10) && !reloaded.cellsUsable(0, 0, 16, 11), "usable cells after reload");
                    // the same state placed and mounted again from the saved data lands the part on the same cells
                    check(h, reloaded.claimAt(3, 2).owner().equals(corePos), "claims are absolute positions of the core");
                }
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrcorner")
    public static void theCornersAreRegisteredWithItemsRecipesAndTheCreativeTab(GameTestHelper h) {
        for (boolean inside : KINDS) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", "control_panel_desk_corner_" + name(inside));
            check(h, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id) == block(inside), id + ": block not registered");
            check(h, net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) == item(inside), id + ": item not registered");
            check(h, BwrItems.TAB.get().getDisplayItems().stream().anyMatch(s -> s.is(item(inside))), id + " is not in the creative tab");
            var manager = h.getLevel().getServer().getRecipeManager();
            boolean vanilla = manager.byKey(id).isPresent();
            boolean mekanism = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", id.getPath() + "_mekanism")).isPresent();
            check(h, vanilla ^ mekanism, id + ": exactly one recipe must be live (vanilla " + vanilla + ", Mekanism " + mekanism + ")");
        }
        h.succeed();
    }
}
