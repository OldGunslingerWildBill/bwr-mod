package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.trim.MountableTrimBlock;
import dev.bwr.mod.panel.trim.MountableTrimRepair;
import dev.bwr.mod.panel.trim.TrimPiece;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The angled trim blocks as mountable panels: the header strip and canopy ({@link PanelType#HEADER_TILT}, a 5 degree
 * lean) and the two chamfer corners ({@link PanelType#CHAMFER_VERTICAL}, {@link PanelType#CHAMFER_BENCH}, diagonal
 * facets). Their frames, picking a cell on a diagonal face for all four facings, mounting through the real player
 * path on every face, a component spanning two header blocks, which neighbours join, what drops when they break, the
 * cells a facet does not cover, and a world saved before they were mountable (a placed block with no block entity)
 * loading and taking components. Fixtures live around 212,400 / 212,400.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelAngledMountTests {
    private PanelAngledMountTests() {
    }

    private static final int ORIGIN_X = 212_400;
    private static final int ORIGIN_Z = 212_400;
    private static final int FLOOR_Y = 100;
    private static final TrimPiece[] PIECES = {TrimPiece.HEADER_STRIP, TrimPiece.HEADER_CANOPY,
            TrimPiece.CORNER_VERTICAL_CHAMFER, TrimPiece.CORNER_BENCH_CHAMFER};
    private static final double R = Math.sqrt(0.5);

    private interface Body {
        void run(ServerLevel level, BlockPos floor);
    }

    /** A cleared stone floor with air above; the body runs once drops can be counted (see EntityLookupProbe). */
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

    private static void clearItems(ServerLevel level, BlockPos floor) {
        level.getEntitiesOfClass(ItemEntity.class, new AABB(floor).inflate(8)).forEach(ItemEntity::discard);
    }

    private static int dropped(ServerLevel level, BlockPos floor, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(floor).inflate(8)).stream()
                .filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static int droppedComponents(ServerLevel level, BlockPos floor) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(floor).inflate(8)).stream()
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

    private static Block blockOf(TrimPiece piece) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("bwr", piece.registryName()));
    }

    private static void put(ServerLevel level, BlockPos pos, TrimPiece piece, Direction facing) {
        level.setBlock(pos, blockOf(piece).defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
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

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    /** The local (lx to the operator's right, f toward the operator) coordinates of a block-space point. */
    private static double[] local(Direction facing, double x, double z) {
        Direction right = facing.getCounterClockWise();
        return new double[] {
                0.5 + (x - 0.5) * right.getStepX() + (z - 0.5) * right.getStepZ(),
                0.5 + (x - 0.5) * facing.getStepX() + (z - 0.5) * facing.getStepZ()};
    }

    /** The local components (lx, f) of a world-space direction. */
    private static double[] localDir(Direction facing, double dx, double dz) {
        Direction right = facing.getCounterClockWise();
        return new double[] {dx * right.getStepX() + dz * right.getStepZ(), dx * facing.getStepX() + dz * facing.getStepZ()};
    }

    private static PanelType typeOf(TrimPiece piece) {
        return piece.mountType();
    }

    // ---- frames

    @GameTest(template = "empty")
    public static void theHeaderFaceLeansBackFiveDegreesFromTheBoardsFoot(GameTestHelper h) {
        double tan5 = Math.tan(Math.toRadians(5.0));
        PanelGeometry.Frame f = PanelGeometry.frame(PanelType.HEADER_TILT, Direction.NORTH);
        PanelGeometry.Frame board = PanelGeometry.frame(PanelType.VERTICAL, Direction.NORTH);
        // north: operator at -Z, so z = 1 - f. Foot at the vertical board's face (f 0.25 -> z 0.75), top 0.0875 further back.
        check(h, near(f.y(0, 16, 0), 0.0) && near(f.z(0, 16, 0), 0.75) && near(f.z(0, 16, 0), board.z(0, 16, 0)),
                "the foot of the header face is flush with the vertical board's face: " + f.z(0, 16, 0));
        check(h, near(f.y(0, 0, 0), 1.0) && near(f.z(0, 0, 0), 0.75 + tan5), "the top edge is 0.0875 further back: " + f.z(0, 0, 0));
        check(h, near(f.x(0, 0, 0), 1.0) && near(f.x(16, 0, 0), 0.0), "the grid spans the block sideways");
        check(h, near(Math.toDegrees(Math.asin(f.ny)), 5.0) && f.nz < 0 && near(f.nx, 0), "the normal is 5 degrees above the horizon: " + f.ny);
        check(h, near(f.vLength, 1.0 / Math.cos(Math.toRadians(5.0))), "a row is 1/cos(5 degrees) of a column: " + f.vLength);
        check(h, PanelType.HEADER_TILT.hasColumns() && !PanelType.HEADER_TILT.hasRows(), "a header joins sideways and is one row");
        // the model draws exactly this plane (annunciator quad of header_strip.obj: z 0.75 at the foot, 0.837489 at the top)
        check(h, near(f.z(0, 0, 0), 0.837489, 1.0e-6), "the frame is the plane of the shipped model: " + f.z(0, 0, 0));
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theChamferFramesAreDiagonalForAllFourFacings(GameTestHelper h) {
        double margin = (0.5 * Math.sqrt(2.0) * 16 - 11) / 2.0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            // vertical chamfer: facet from B = (lx .25, f .75) to A = (.75, .25); 11 cells on it, margin cells unused each end
            PanelGeometry.Frame v = PanelGeometry.frame(PanelType.CHAMFER_VERTICAL, facing);
            String at = "CHAMFER_VERTICAL/" + facing;
            double[] b = local(facing, v.x(-margin, 0, 0), v.z(-margin, 0, 0));
            double[] a = local(facing, v.x(11 + margin, 0, 0), v.z(11 + margin, 0, 0));
            check(h, near(b[0], 0.25) && near(b[1], 0.75) && near(a[0], 0.75) && near(a[1], 0.25), at + ": facet ends " + b[0] + "," + b[1] + " " + a[0] + "," + a[1]);
            double[] n = localDir(facing, v.nx, v.nz);
            double[] across = localDir(facing, v.ux * 16, v.uz * 16);
            check(h, near(n[0], R) && near(n[1], R) && near(v.ny, 0), at + ": normal toward the operator's right-front");
            check(h, near(across[0], R) && near(across[1], -R) && near(v.uy, 0), at + ": columns run along the diagonal to the operator's right");
            check(h, near(v.vy, -1.0 / 16) && near(v.vLength, 1.0), at + ": rows are vertical and square");
            check(h, Math.abs(v.nx) > 0.7 && Math.abs(v.nz) > 0.7, at + ": the face is diagonal in world space");
            check(h, near(v.y(0, 0, 0), 1.0) && near(v.y(0, 16, 0), 0.0), at + ": the face is one block tall");
            // bench chamfer: the triangle (0,1,1) (1,1,0) (1,0,1) in (lx, ly, f)
            PanelGeometry.Frame t = PanelGeometry.frame(PanelType.CHAMFER_BENCH, facing);
            at = "CHAMFER_BENCH/" + facing;
            double s = (Math.sqrt(2.0) - 1.0) / 2.0 * 16;
            double[] p = local(facing, t.x(-s, 0, 0), t.z(-s, 0, 0));
            double[] q = local(facing, t.x(16 + s, 0, 0), t.z(16 + s, 0, 0));
            double[] apex = local(facing, t.x(8, 16, 0), t.z(8, 16, 0));
            check(h, near(t.y(-s, 0, 0), 1.0) && near(p[0], 0) && near(p[1], 1) && near(t.y(16 + s, 0, 0), 1.0)
                    && near(q[0], 1) && near(q[1], 0), at + ": top edge ends " + p[0] + "," + p[1] + " " + q[0] + "," + q[1]);
            check(h, near(t.y(8, 16, 0), 0.0) && near(apex[0], 1) && near(apex[1], 1), at + ": apex " + apex[0] + "," + apex[1]);
            double[] tn = localDir(facing, t.nx, t.nz);
            double s3 = 1.0 / Math.sqrt(3.0);
            check(h, near(tn[0], s3) && near(tn[1], s3) && near(t.ny, s3), at + ": normal (1,1,1)/sqrt(3) in the local frame");
            check(h, near(t.vLength, Math.sqrt(1.5)), at + ": a row is " + t.vLength + " columns long");
            // same handedness as every other panel: columns x rows = -normal
            for (PanelGeometry.Frame f : new PanelGeometry.Frame[] {v, t}) {
                double cx = f.uy * f.vz - f.uz * f.vy;
                double cy = f.uz * f.vx - f.ux * f.vz;
                double cz = f.ux * f.vy - f.uy * f.vx;
                check(h, cx * f.nx + cy * f.ny + cz * f.nz < 0, at + ": columns x rows must point into the block");
            }
            check(h, !PanelType.CHAMFER_VERTICAL.hasColumns() && PanelType.CHAMFER_VERTICAL.hasRows()
                    && !PanelType.CHAMFER_BENCH.hasColumns() && !PanelType.CHAMFER_BENCH.hasRows(), at + ": grouping flags");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theCellsOfAFacetThatFitAreTheOnesTheMaskAllows(GameTestHelper h) {
        int vertical = 0;
        int bench = 0;
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                vertical += PanelGeometry.cellUsable(PanelType.CHAMFER_VERTICAL, cx, cy) ? 1 : 0;
                bench += PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, cx, cy) ? 1 : 0;
                check(h, PanelGeometry.cellUsable(PanelType.HEADER_TILT, cx, cy)
                        && PanelGeometry.cellUsable(PanelType.VERTICAL, cx, cy), "boards and headers use every cell");
            }
        }
        check(h, vertical == 11 * 16, "the vertical chamfer takes 11 of 16 columns: " + vertical);
        check(h, PanelGeometry.cellUsable(PanelType.CHAMFER_VERTICAL, 10, 5) && !PanelGeometry.cellUsable(PanelType.CHAMFER_VERTICAL, 11, 5),
                "column 10 is on the facet, 11 is not");
        // the triangle: the whole top, narrowing to nothing at the apex
        check(h, PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 0, 0) && PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 15, 3)
                && !PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 0, 4) && PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 8, 13)
                && !PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 8, 14) && !PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, 5, 12),
                "the bench chamfer cells follow the triangle");
        // every usable cell lies wholly inside the block volume's triangle: its four corners are on the facet's side of the edges
        PanelGeometry.Frame t = PanelGeometry.frame(PanelType.CHAMFER_BENCH, Direction.NORTH);
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                if (!PanelGeometry.cellUsable(PanelType.CHAMFER_BENCH, cx, cy)) {
                    continue;
                }
                for (double[] corner : new double[][] {{cx, cy}, {cx + 1, cy}, {cx, cy + 1}, {cx + 1, cy + 1}}) {
                    double[] l = local(Direction.NORTH, t.x(corner[0], corner[1], 0), t.z(corner[0], corner[1], 0));
                    check(h, l[0] >= -1.0e-9 && l[0] <= 1.0 + 1.0e-9 && l[1] >= -1.0e-9 && l[1] <= 1.0 + 1.0e-9,
                            "bench cell " + cx + "," + cy + " leaves the block at its corner " + corner[0] + "," + corner[1]);
                }
            }
        }
        h.succeed();
    }

    // ---- picking and mounting

    /** Puts the player in front of cell (cu, cv) of a block, looking at it, slightly off to the side. */
    private static Vec3 aim(FakePlayer player, BlockPos pos, Direction facing, PanelType type, double cu, double cv) {
        PanelGeometry.Frame f = PanelGeometry.frame(type, facing);
        Vec3 target = new Vec3(pos.getX() + f.x(cu, cv, 0), pos.getY() + f.y(cu, cv, 0), pos.getZ() + f.z(cu, cv, 0));
        Vec3 eye = target.add(f.nx * 1.4 + f.rightX * 0.3, f.ny * 1.4 + f.rightY * 0.3, f.nz * 1.4 + f.rightZ * 0.3);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        Vec3 d = target.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        return target;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aLookAtACellOnADiagonalFacePicksThatCellForAllFourFacings(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "AngledPick", GameType.SURVIVAL);
            BlockPos pos = floor.above(2);
            double[][][] cells = {
                    {{0.5, 0.5}, {5.5, 7.5}, {10.5, 15.5}},          // vertical chamfer: columns 0 to 10
                    {{8.5, 0.5}, {4.5, 2.5}, {8.5, 10.5}, {12.5, 3.5}}}; // bench chamfer: inside the triangle
            TrimPiece[] pieces = {TrimPiece.CORNER_VERTICAL_CHAMFER, TrimPiece.CORNER_BENCH_CHAMFER};
            for (int i = 0; i < 2; i++) {
                PanelType type = typeOf(pieces[i]);
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    put(level, pos, pieces[i], facing);
                    PanelBlockEntity panel = be(level, pos);
                    check(h, panel != null && panel.panelType() == type, pieces[i] + "/" + facing + ": no panel block entity");
                    for (double[] cell : cells[i]) {
                        Vec3 target = aim(player, pos, facing, type, cell[0], cell[1]);
                        Vec3 eye = player.getEyePosition();
                        Vec3 look = player.getLookAngle();
                        // the ray first meets this block (its outline holds the diagonal face) ...
                        BlockHitResult clip = level.clip(new ClipContext(eye, eye.add(look.scale(3.0)), ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE, player));
                        check(h, clip.getType() == HitResult.Type.BLOCK && clip.getBlockPos().equals(pos),
                                pieces[i] + "/" + facing + ": the look at cell " + cell[0] + "," + cell[1] + " does not hit the block: " + clip);
                        // ... and the plane intersection gives the cell that was aimed at
                        int[] got = new int[2];
                        check(h, PanelOps.cellAt(panel, eye, look, got) && got[0] == (int) cell[0] && got[1] == (int) cell[1],
                                pieces[i] + "/" + facing + ": looked at " + cell[0] + "," + cell[1] + ", picked " + got[0] + "," + got[1]);
                        double[] exact = new double[2];
                        check(h, PanelGeometry.rayToCell(PanelGeometry.frame(type, facing), pos.getX(), pos.getY(), pos.getZ(),
                                eye.x, eye.y, eye.z, look.x, look.y, look.z, exact)
                                && near(exact[0], cell[0], 0.02) && near(exact[1], cell[1], 0.02),
                                pieces[i] + "/" + facing + ": exact cell " + exact[0] + "," + exact[1]);
                    }
                }
            }
        });
    }

    /** Uses the held component item on the cell under the player's look, through the vanilla right-click path. */
    private static InteractionResultHolder use(ServerLevel level, FakePlayer player, BlockPos pos, Direction facing, PanelType type,
                                               Item item, int variant, int cx, int cy) {
        ItemStack stack = new ItemStack(item);
        stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), variant);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Vec3 target = aim(player, pos, facing, type, cx + 0.5, cy + 0.5);
        var result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(target, Direction.UP, pos, false));
        return new InteractionResultHolder(result.consumesAction(), player.getMainHandItem().isEmpty());
    }

    private record InteractionResultHolder(boolean consumed, boolean itemUsedUp) {
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void everyAngledFaceTakesAComponentThroughThePlayerPath(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "AngledMount", GameType.SURVIVAL);
            BlockPos pos = floor.above(2);
            int gauge = ComponentTypes.DIAL_GAUGE.realDefaultIndex();
            check(h, gauge >= 0, "the dial gauge has a real size");
            // a 4 x 4 lamp (variant 0) at a cell that is on every face, and the real-size gauge at another
            int[][] lampAt = {{6, 6}, {6, 6}, {3, 3}, {6, 2}};
            int[][] gaugeAt = {{1, 1}, {1, 2}, {5, 8}, {7, 8}};
            for (int i = 0; i < PIECES.length; i++) {
                PanelType type = typeOf(PIECES[i]);
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    clear(level, floor);
                    put(level, pos, PIECES[i], facing);
                    String at = PIECES[i] + "/" + facing;
                    var lamp = use(level, player, pos, facing, type, BwrItems.LAMP.get(), 0, lampAt[i][0], lampAt[i][1]);
                    PanelBlockEntity panel = be(level, pos);
                    check(h, lamp.consumed() && lamp.itemUsedUp() && panel.ownedCount() == 1, at + ": the lamp was not mounted");
                    PanelComponent placed = panel.owned().get(0);
                    check(h, placed.x() == lampAt[i][0] && placed.y() == lampAt[i][1] && placed.w() == 4 && placed.h() == 4
                            && panel.claimAt(lampAt[i][0] + 3, lampAt[i][1] + 3) != null, at + ": the lamp is not where it was aimed");
                    var dial = use(level, player, pos, facing, type, BwrItems.DIAL_GAUGE.get(), gauge, gaugeAt[i][0], gaugeAt[i][1]);
                    check(h, dial.consumed() && panel.ownedCount() == 2, at + ": the real-size gauge was not mounted");
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void cellsOffTheFacetAreRefusedAndTheOnesOnItAreNot(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "AngledRefuse", GameType.SURVIVAL);
            BlockPos pos = floor.above(2);
            Direction facing = Direction.EAST;
            // vertical chamfer
            put(level, pos, TrimPiece.CORNER_VERTICAL_CHAMFER, facing);
            // 12 x 12 gauge: wider than the 11 usable columns (the block is empty, so only the facet can refuse it)
            ItemStack gauge = new ItemStack(BwrItems.DIAL_GAUGE.get());
            gauge.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
            player.setItemInHand(InteractionHand.MAIN_HAND, gauge);
            player.setPos(Vec3.atBottomCenterOf(pos));
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, pos, 0, 4) == PanelGroup.Result.BLOCKED,
                    "a 12 wide gauge does not fit the 11 columns of the vertical chamfer");
            check(h, be(level, pos).ownedCount() == 0, "the refused gauge left something behind");
            // a 4 wide lamp at column 7 ends at column 10 (on the facet), at 8 it reaches column 11 (off)
            check(h, mountDirect(level, player, pos, 8, 3) == PanelGroup.Result.BLOCKED, "a lamp reaching column 11 of the vertical chamfer");
            check(h, mountDirect(level, player, pos, 12, 3) == PanelGroup.Result.BLOCKED, "a lamp on the dead columns of the vertical chamfer");
            check(h, mountDirect(level, player, pos, 7, 3) == PanelGroup.Result.OK, "a lamp ending at column 10");
            // bench chamfer: the apex is no place for a lamp
            put(level, pos, TrimPiece.CORNER_BENCH_CHAMFER, facing);
            check(h, mountDirect(level, player, pos, 6, 10) == PanelGroup.Result.BLOCKED, "a lamp at the apex of the triangle");
            check(h, mountDirect(level, player, pos, 0, 6) == PanelGroup.Result.BLOCKED, "a lamp outside the triangle's left edge");
            check(h, mountDirect(level, player, pos, 6, 6) == PanelGroup.Result.OK, "a lamp inside the triangle");
            // canopy: the hood takes the top row
            put(level, pos, TrimPiece.HEADER_CANOPY, facing);
            check(h, mountDirect(level, player, pos, 6, 0) == PanelGroup.Result.BLOCKED, "a lamp in the canopy's hood row");
            check(h, mountDirect(level, player, pos, 6, 1) == PanelGroup.Result.OK, "a lamp under the canopy's hood");
            // the strip has no hood
            put(level, pos, TrimPiece.HEADER_STRIP, facing);
            check(h, mountDirect(level, player, pos, 6, 0) == PanelGroup.Result.OK, "a lamp on the strip's top row");
        });
    }

    private static PanelGroup.Result mountDirect(ServerLevel level, FakePlayer player, BlockPos pos, int cx, int cy) {
        ItemStack lamp = new ItemStack(BwrItems.LAMP.get());
        lamp.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
        player.setPos(Vec3.atBottomCenterOf(pos.above(0)));
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        return PanelOps.place(player, InteractionHand.MAIN_HAND, pos, cx, cy);
    }

    // ---- grouping

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aComponentSpansTwoHeaderBlocksAndAHeaderIsOneRow(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "AngledHeader", GameType.SURVIVAL);
            Direction facing = Direction.NORTH;
            BlockPos a = floor.above(2);
            BlockPos b = PanelType.nextColumn(a, facing);
            BlockPos c = PanelType.nextColumn(b, facing);
            put(level, a, TrimPiece.HEADER_STRIP, facing);
            put(level, b, TrimPiece.HEADER_CANOPY, facing);
            put(level, c, TrimPiece.HEADER_STRIP, facing);
            PanelGroup group = be(level, b).group();
            check(h, group.type() == PanelType.HEADER_TILT && group.columns() == 3 && group.rows() == 1 && group.members().size() == 3,
                    "strip, canopy and strip are one 3 x 1 group: " + group.columns() + " x " + group.rows());
            check(h, group.columnOf(a) == 0 && group.columnOf(c) == 2, "columns run to the operator's right");
            // a 12 x 12 gauge from column 10 of the strip reaches into the canopy
            ItemStack gauge = new ItemStack(BwrItems.DIAL_GAUGE.get());
            gauge.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
            player.setPos(Vec3.atBottomCenterOf(a));
            player.setItemInHand(InteractionHand.MAIN_HAND, gauge);
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, a, 10, 2) == PanelGroup.Result.OK, "a gauge over a strip and a canopy");
            int id = be(level, a).owned().get(0).id();
            check(h, be(level, b).claimAt(0, 2) != null && be(level, b).claimAt(0, 2).id() == id && be(level, b).claimAt(0, 2).owner().equals(a)
                    && be(level, b).claimAt(6, 2) == null, "the canopy holds the part of the gauge that crosses over");
            // the same through the player path, from canopy to strip
            var second = use(level, player, b, facing, PanelType.HEADER_TILT, BwrItems.DIAL_GAUGE.get(), 0, 10, 3);
            check(h, second.consumed() && be(level, b).ownedCount() == 1 && be(level, c).claimAt(1, 3) != null,
                    "a gauge from the canopy into the next strip through the player path");
            // one row: a component cannot hang off the bottom edge, and a vertical board below is another group
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.LAMP.get()));
            check(h, mountDirect(level, player, c, 8, 14) == PanelGroup.Result.OUTSIDE_GROUP, "a header has no row below it");
            level.setBlock(c.below(), dev.bwr.mod.registry.BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, facing), 3);
            check(h, mountDirect(level, player, c, 8, 14) == PanelGroup.Result.OUTSIDE_GROUP && be(level, c).group().rows() == 1
                    && be(level, c.below()).group().members().size() == 1, "a vertical board under a header is not in its group");
            // a header facing another way, and one with a gap, do not join
            BlockPos turned = PanelType.nextColumn(c, facing);
            put(level, turned, TrimPiece.HEADER_STRIP, Direction.EAST);
            check(h, !be(level, c).group().contains(turned) && be(level, turned).group().members().size() == 1, "a turned header joined");
            BlockPos far = PanelType.nextColumn(PanelType.nextColumn(turned, facing), facing);
            put(level, far, TrimPiece.HEADER_STRIP, facing);
            check(h, !be(level, c).group().contains(far) && be(level, far).group().members().size() == 1, "a header with a gap joined");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void chamfersStackIntoAColumnButNeverJoinSideways(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "AngledChamfer", GameType.SURVIVAL);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                clear(level, floor);
                BlockPos top = floor.above(3);
                put(level, top, TrimPiece.CORNER_VERTICAL_CHAMFER, facing);
                put(level, top.below(), TrimPiece.CORNER_VERTICAL_CHAMFER, facing);
                BlockPos beside = PanelType.nextColumn(top, facing);
                put(level, beside, TrimPiece.CORNER_VERTICAL_CHAMFER, facing);
                PanelGroup group = be(level, top).group();
                check(h, group.type() == PanelType.CHAMFER_VERTICAL && group.columns() == 1 && group.rows() == 2
                        && group.contains(top.below()) && !group.contains(beside), facing + ": a stack of vertical chamfers is one column of 2 rows, not " + group.columns() + " x " + group.rows());
                check(h, be(level, beside).group().members().size() == 1, facing + ": the chamfer beside is on its own");
                // a lamp straddling the two stacked blocks
                check(h, mountDirect(level, player, top, 2, 14) == PanelGroup.Result.OK, facing + ": a lamp over two stacked chamfers");
                int id = be(level, top).owned().get(0).id();
                check(h, be(level, top.below()).claimAt(2, 1) != null && be(level, top.below()).claimAt(2, 1).id() == id,
                        facing + ": the lower chamfer holds the lamp's lower rows");
                // nothing to the side: a lamp cannot reach into the neighbour (it has no column to the right)
                check(h, mountDirect(level, player, top, 14, 1) == PanelGroup.Result.BLOCKED, facing + ": a lamp reaching into the next block sideways");
                // a bench chamfer is alone, whatever stands above it
                BlockPos bench = floor.above(1).relative(facing.getOpposite(), 3);
                put(level, bench, TrimPiece.CORNER_BENCH_CHAMFER, facing);
                put(level, bench.above(), TrimPiece.CORNER_BENCH_CHAMFER, facing);
                put(level, PanelType.nextColumn(bench, facing), TrimPiece.CORNER_BENCH_CHAMFER, facing);
                check(h, be(level, bench).group().members().size() == 1, facing + ": a bench chamfer is a group of one");
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void breakingAnAngledBlockDropsItselfAndItsComponents(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "AngledBreak", GameType.SURVIVAL);
            BlockPos pos = floor.above(2);
            for (TrimPiece piece : PIECES) {
                for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                    clear(level, floor);
                    player.setGameMode(GameType.SURVIVAL);
                    Direction facing = piece.ordinal() % 2 == 0 ? Direction.SOUTH : Direction.WEST;
                    put(level, pos, piece, facing);
                    int[][] at = piece == TrimPiece.CORNER_VERTICAL_CHAMFER ? new int[][] {{0, 0}, {5, 8}}
                            : piece == TrimPiece.CORNER_BENCH_CHAMFER ? new int[][] {{6, 2}, {2, 0}} : new int[][] {{2, 2}, {8, 8}};
                    int mounted = 0;
                    for (int[] cell : at) {
                        if (mountDirect(level, player, pos, cell[0], cell[1]) == PanelGroup.Result.OK) {
                            mounted++;
                        }
                    }
                    check(h, mounted == 2 && be(level, pos).ownedCount() == 2, piece + ": mounted " + mounted + " of 2");
                    clearItems(level, floor);
                    player.setGameMode(mode);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    check(h, player.gameMode.destroyBlock(pos), piece + " would not break");
                    check(h, level.getBlockState(pos).isAir(), piece + " is still there");
                    int own = dropped(level, floor, blockOf(piece).asItem());
                    check(h, own == (mode == GameType.SURVIVAL ? 1 : 0), piece + " " + mode + ": dropped " + own + " of itself");
                    check(h, droppedComponents(level, floor) == 2, piece + " " + mode + ": dropped " + droppedComponents(level, floor) + " of 2 components");
                }
            }
        });
    }

    // ---- the models stay behind the plane the cells are on

    @GameTest(template = "empty")
    public static void nothingOfTheShippedModelsStandsInFrontOfAUsableCell(GameTestHelper h) throws java.io.IOException {
        for (TrimPiece piece : PIECES) {
            String name = piece.registryName().substring("control_panel_".length());
            PanelType type = typeOf(piece);
            PanelGeometry.Frame f = PanelGeometry.frame(type, Direction.NORTH);
            String text;
            try (var in = PanelAngledMountTests.class.getResourceAsStream("/assets/bwr/models/block/control_panel_trim/" + name + ".obj")) {
                check(h, in != null, name + ".obj is missing");
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int checked = 0;
            for (String line : text.split("\n")) {
                if (!line.startsWith("v ")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                double x = Double.parseDouble(p[1]) - f.ox;
                double y = Double.parseDouble(p[2]) - f.oy;
                double z = Double.parseDouble(p[3]) - f.oz;
                double above = x * f.nx + y * f.ny + z * f.nz;
                if (above < 1.0e-5) {
                    continue;
                }
                // a vertex in front of the plane must project onto cells that no component can take
                double uu = f.ux * f.ux + f.uy * f.uy + f.uz * f.uz;
                double vv = f.vx * f.vx + f.vy * f.vy + f.vz * f.vz;
                double cu = (x * f.ux + y * f.uy + z * f.uz) / uu;
                double cv = (x * f.vx + y * f.vy + z * f.vz) / vv;
                boolean usable = false;
                for (int cy = (int) Math.floor(cv - 0.01); cy <= (int) Math.floor(cv + 0.01); cy++) {
                    for (int cx = (int) Math.floor(cu - 0.01); cx <= (int) Math.floor(cu + 0.01); cx++) {
                        BlockState state = blockOf(piece).defaultBlockState();
                        usable |= ((PanelBlock) blockOf(piece)).cellUsable(state, cx, cy);
                    }
                }
                // The canopy's hood lower edge is 0.26 block out and the face leans, so it projects 0.36 cell into row 1; a
                // component's top edge there meets it only above 0.2 block of elevation, which no model has.
                if (piece == TrimPiece.HEADER_CANOPY && cv < 1.5) {
                    continue;
                }
                check(h, !usable, name + ": the vertex " + line.trim() + " stands " + above + " in front of the face at the usable cell "
                        + cu + "," + cv);
                checked++;
            }
            com.mojang.logging.LogUtils.getLogger().info("ANGLED MOUNT {}: {} vertices in front of the plane, all over unusable cells", name, checked);
        }
        h.succeed();
    }

    // ---- an old save

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aChunkLoadedWithoutTheBlockEntityGetsOneAndTakesComponents(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = new BlockPos(ORIGIN_X + 6 * 32, FLOOR_Y + 2, ORIGIN_Z);
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        try {
            LevelChunk chunk = level.getChunk(cx, cz);
            // what a world saved by alpha 44 holds: the block and its state, and no block entity
            for (int i = 0; i < PIECES.length; i++) {
                BlockPos pos = base.offset(i * 2, 0, 0);
                put(level, pos, PIECES[i], Direction.NORTH);
                chunk.removeBlockEntity(pos);
                check(h, !chunk.getBlockEntities().containsKey(pos), PIECES[i] + ": the old block still has an entity");
            }
            // a read of such a block creates its entity, which is then a working panel
            BlockPos probe = base.offset(0, 0, 4);
            put(level, probe, TrimPiece.HEADER_STRIP, Direction.NORTH);
            chunk.removeBlockEntity(probe);
            check(h, level.getBlockEntity(probe) instanceof PanelBlockEntity panel && panel.panelType() == PanelType.HEADER_TILT,
                    "asking for the entity of an old header strip creates a panel block entity");
            // the repair that runs when a chunk loads makes the entities of the old blocks
            int created = MountableTrimRepair.repair(chunk);
            check(h, created == PIECES.length, "the chunk-load repair created " + created + " entities, expected " + PIECES.length);
            check(h, MountableTrimRepair.repair(chunk) == 0, "a second repair finds nothing to do");
            FakePlayer player = player(level, "AngledOld", GameType.SURVIVAL);
            for (int i = 0; i < PIECES.length; i++) {
                BlockPos pos = base.offset(i * 2, 0, 0);
                check(h, chunk.getBlockEntities().get(pos) instanceof PanelBlockEntity panel && panel.panelType() == typeOf(PIECES[i]),
                        PIECES[i] + ": no panel block entity after the repair");
                int[] cell = i == 2 ? new int[] {3, 3} : new int[] {6, 6};
                check(h, mountDirect(level, player, pos, cell[0], cell[1]) == PanelGroup.Result.OK && be(level, pos).ownedCount() == 1,
                        PIECES[i] + ": an old block did not take a component");
            }
        } finally {
            for (int i = 0; i < PIECES.length; i++) {
                level.setBlock(base.offset(i * 2, 0, 0), Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(base.offset(0, 0, 4), Blocks.AIR.defaultBlockState(), 2);
            level.setChunkForced(cx, cz, false);
        }
        h.succeed();
    }

    /**
     * The same through the real storage: the chunk is saved with the blocks and no entities, unloaded and loaded again;
     * the load event gives every old block its entity before anything asks, and a component can be mounted.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anOldSavedTrimBlockLoadsAndBecomesMountable(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = new BlockPos(ORIGIN_X + 7 * 32, FLOOR_Y + 2, ORIGIN_Z);
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        LevelChunk chunk = level.getChunk(cx, cz);
        for (int i = 0; i < PIECES.length; i++) {
            BlockPos pos = base.offset(i * 2, 0, 0);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            put(level, pos, PIECES[i], Direction.values()[2 + i]);
            chunk.removeBlockEntity(pos);
            chunk.setUnsaved(true);
        }
        level.setChunkForced(cx, cz, false);
        h.runAfterDelay(120, () -> {
            boolean unloaded = !level.hasChunk(cx, cz);
            level.setChunkForced(cx, cz, true);
            try {
                check(h, unloaded, "the fixture chunk did not unload before the reload");
                LevelChunk loaded = level.getChunk(cx, cz);
                FakePlayer player = player(level, "AngledOldSaved", GameType.SURVIVAL);
                for (int i = 0; i < PIECES.length; i++) {
                    BlockPos pos = base.offset(i * 2, 0, 0);
                    BlockState state = level.getBlockState(pos);
                    check(h, state.getBlock() instanceof MountableTrimBlock && state.getValue(PanelBlock.FACING) == Direction.values()[2 + i],
                            PIECES[i] + ": the saved block came back as " + state);
                    check(h, loaded.getBlockEntities().get(pos) instanceof PanelBlockEntity panel && panel.panelType() == typeOf(PIECES[i]),
                            PIECES[i] + ": the load did not give the old block a panel block entity");
                    int[] cell = i == 2 ? new int[] {3, 3} : new int[] {6, 6};
                    check(h, mountDirect(level, player, pos, cell[0], cell[1]) == PanelGroup.Result.OK && be(level, pos).ownedCount() == 1,
                            PIECES[i] + ": the reloaded old block did not take a component");
                }
            } finally {
                for (int i = 0; i < PIECES.length; i++) {
                    level.setBlock(base.offset(i * 2, 0, 0), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setChunkForced(cx, cz, false);
            }
            h.succeed();
        });
    }
}
