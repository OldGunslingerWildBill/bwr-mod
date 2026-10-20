package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.trim.PanelEndCapBlock;
import dev.bwr.mod.panel.trim.PanelTrimBlock;
import dev.bwr.mod.panel.trim.TrimGeometry;
import dev.bwr.mod.panel.trim.TrimPiece;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The control-room trim blocks and the remodelled panel bodies.
 *
 * <ul>
 *   <li>Registration: every {@link TrimPiece} has its block, its item, its loot table (it drops itself), its place in
 *       the creative tab, no block entity and no ticking.</li>
 *   <li>Placement: the facing is the operator's side, like a panel; an end cap takes the side of the run it is
 *       placed against and sneaking takes the other.</li>
 *   <li>Shapes: the collision shapes follow the cross-sections of the panels they trim.</li>
 *   <li>Models: the shipped OBJ files are read back. <b>The mounting face of the three panel bodies is where
 *       {@link PanelGeometry} puts it</b>: the 'face' polygon of each body has exactly the four corners of the
 *       frame, and nothing else of the model reaches in front of that plane, so no added detail can take a cell of
 *       the 16 x 16 grid away or stand in a component's way. The trim models stay inside their block, light.</li>
 * </ul>
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelTrimTests {
    private PanelTrimTests() {
    }

    private static final BlockPos ORIGIN = new BlockPos(46_900, 110, 46_900);
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static Block blockOf(TrimPiece piece) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("bwr", piece.registryName()));
    }

    /** A far-away stage in force-loaded chunks with a stone floor, emptied again at the end. Synchronous: no entities. */
    private static final class Stage implements AutoCloseable {
        final ServerLevel level;
        final FakePlayer player;

        Stage(GameTestHelper helper) {
            level = helper.getLevel();
            for (int x = (ORIGIN.getX() - 16) >> 4; x <= (ORIGIN.getX() + 16) >> 4; x++) {
                for (int z = (ORIGIN.getZ() - 16) >> 4; z <= (ORIGIN.getZ() + 16) >> 4; z++) {
                    level.setChunkForced(x, z, true);
                    level.getChunk(x, z);
                }
            }
            clear();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    level.setBlock(ORIGIN.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes("panel-trim-test".getBytes(StandardCharsets.UTF_8)), "panel-trim-test"));
            player.setGameMode(GameType.SURVIVAL);
            player.setShiftKeyDown(false);
            player.getInventory().clearContent();
        }

        private void clear() {
            for (int x = -8; x <= 8; x++) {
                for (int y = -1; y <= 4; y++) {
                    for (int z = -8; z <= 8; z++) {
                        BlockPos pos = ORIGIN.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }

        /** The block a player standing north of the target, looking at it from {@code direction}, would place. */
        BlockState placement(Block block, BlockPos target, Direction looking) {
            player.setYRot(looking.toYRot());
            ItemStack stack = new ItemStack(block.asItem());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target.below()).add(0, 0.5, 0), Direction.UP,
                    target.below(), false);
            return block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
        }

        @Override
        public void close() {
            clear();
            for (int x = (ORIGIN.getX() - 16) >> 4; x <= (ORIGIN.getX() + 16) >> 4; x++) {
                for (int z = (ORIGIN.getZ() - 16) >> 4; z <= (ORIGIN.getZ() + 16) >> 4; z++) {
                    level.setChunkForced(x, z, false);
                }
            }
        }
    }

    // ---- registration

    @GameTest(template = "empty")
    public static void everyTrimPieceIsRegisteredWithItsItemAndDropsItself(GameTestHelper helper) {
        CreativeModeTabs.tryRebuildTabContents(helper.getLevel().enabledFeatures(), true, helper.getLevel().registryAccess());
        Set<Block> seen = new HashSet<>();
        for (TrimPiece piece : TrimPiece.values()) {
            Block block = blockOf(piece);
            boolean mountable = piece.mountType() != null;
            helper.assertTrue(block != Blocks.AIR && (mountable || block instanceof PanelTrimBlock), piece + " is not registered as a trim block");
            helper.assertTrue(seen.add(block), piece + " shares its block");
            if (mountable) {
                // the four angled pieces are panel blocks: the panel block entity of their own panel type
                helper.assertTrue(block instanceof PanelBlock panelBlock
                                && panelBlock.panelType(block.defaultBlockState()) == piece.mountType()
                                && block.defaultBlockState().hasBlockEntity()
                                && panelBlock.newBlockEntity(ORIGIN, block.defaultBlockState())
                                instanceof dev.bwr.mod.panel.PanelBlockEntity panelEntity
                                && panelEntity.panelType() == piece.mountType(),
                        piece + " is not a panel block with a panel block entity of " + piece.mountType());
                helper.assertTrue(((dev.bwr.mod.panel.trim.MountableTrimBlock) block).piece() == piece, piece + " block carries another piece");
            } else {
                helper.assertTrue(((PanelTrimBlock) block).piece() == piece, piece + " block carries another piece");
            }
            helper.assertTrue(block instanceof PanelEndCapBlock == piece.isEndCap(), piece + ": end cap class mismatch");
            if (!mountable) {
                helper.assertTrue(!(block instanceof EntityBlock), piece + " must have no block entity");
            }
            BlockState state = block.defaultBlockState();
            helper.assertTrue(!state.isRandomlyTicking() && (mountable || !state.hasBlockEntity()), piece + " must not tick or hold an entity");
            helper.assertTrue(state.hasProperty(PanelTrimBlock.FACING), piece + " has no facing");
            helper.assertTrue(state.hasProperty(PanelEndCapBlock.SIDE) == piece.isEndCap(), piece + ": side property mismatch");
            helper.assertTrue(block.getStateDefinition().getPossibleStates().size() == (piece.isEndCap() ? 8 : 4),
                    piece + ": states " + block.getStateDefinition().getPossibleStates().size());
            helper.assertTrue(block.asItem() instanceof BlockItem, piece + " has no block item");
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(block.asItem()).getPath().equals(piece.registryName()),
                    piece + " item has another id");
            helper.assertTrue(BwrItems.TAB.get().contains(new ItemStack(block.asItem())), piece + " is not in the creative tab");
            helper.assertTrue(helper.getLevel().getServer().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)
                            .stream().anyMatch(r -> r.value().getResultItem(helper.getLevel().registryAccess()).is(block.asItem())),
                    piece + " has no crafting recipe");
            // the loot table gives back exactly the block's own item, for every state
            for (BlockState each : block.getStateDefinition().getPossibleStates()) {
                var drops = Block.getDrops(each, helper.getLevel(), ORIGIN, null);
                helper.assertTrue(drops.size() == 1 && drops.get(0).is(block.asItem()) && drops.get(0).getCount() == 1,
                        piece + " " + each + " drops " + drops);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelBodiesKeepTheirIdsAndStates(GameTestHelper helper) {
        for (Block block : new Block[] {BwrBlocks.CONTROL_PANEL_VERTICAL.get(), BwrBlocks.CONTROL_PANEL_BENCHBOARD.get(),
                BwrBlocks.CONTROL_PANEL_CONSOLE.get()}) {
            helper.assertTrue(block.getStateDefinition().getProperties().size() == 1
                            && block.getStateDefinition().getProperties().iterator().next() == PanelBlock.FACING,
                    block + " must keep the single facing property so old saves load");
            helper.assertTrue(block.getStateDefinition().getPossibleStates().size() == 4, block + " must keep its 4 states");
        }
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(BwrBlocks.CONTROL_PANEL_VERTICAL.get()).equals(
                ResourceLocation.fromNamespaceAndPath("bwr", "control_panel_vertical")), "vertical id");
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(BwrBlocks.CONTROL_PANEL_BENCHBOARD.get()).equals(
                ResourceLocation.fromNamespaceAndPath("bwr", "control_panel_benchboard")), "benchboard id");
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(BwrBlocks.CONTROL_PANEL_CONSOLE.get()).equals(
                ResourceLocation.fromNamespaceAndPath("bwr", "control_panel_console")), "console id");
        helper.succeed();
    }

    // ---- placement

    @GameTest(template = "empty")
    public static void trimBlocksFaceTheOperatorLikePanelsDo(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            BlockPos target = ORIGIN;
            for (TrimPiece piece : TrimPiece.values()) {
                Block block = blockOf(piece);
                for (Direction looking : HORIZONTAL) {
                    BlockState state = stage.placement(block, target, looking);
                    helper.assertTrue(state != null && state.getValue(PanelTrimBlock.FACING) == looking.getOpposite(),
                            piece + " placed looking " + looking + " faces " + (state == null ? null : state.getValue(PanelTrimBlock.FACING)));
                    // and a panel placed the same way agrees
                    BlockState panel = stage.placement(BwrBlocks.CONTROL_PANEL_VERTICAL.get(), target, looking);
                    helper.assertTrue(panel.getValue(PanelBlock.FACING) == state.getValue(PanelTrimBlock.FACING),
                            piece + " and a panel face different ways looking " + looking);
                }
            }
            // a real placement through the item puts the block in the world and uses one item
            Block header = BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get();
            stage.player.setYRot(Direction.NORTH.toYRot());
            ItemStack stack = new ItemStack(header.asItem(), 3);
            stage.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target.below()).add(0, 0.5, 0), Direction.UP, target.below(), false);
            InteractionResult result = stack.useOn(new UseOnContext(stage.player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(result.consumesAction(), "placing a header strip returned " + result);
            helper.assertTrue(stage.level.getBlockState(target).is(header)
                    && stage.level.getBlockState(target).getValue(PanelTrimBlock.FACING) == Direction.SOUTH,
                    "the placed header strip is " + stage.level.getBlockState(target));
            helper.assertTrue(stack.getCount() == 2, "one item used, " + stack.getCount() + " left");
            helper.assertTrue(stage.level.getBlockEntity(target) instanceof dev.bwr.mod.panel.PanelBlockEntity panelEntity
                    && panelEntity.panelType() == PanelType.HEADER_TILT, "a header strip has a panel block entity of HEADER_TILT");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEndCapTakesTheSideOfTheRunItClosesAndSneakFlipsIt(GameTestHelper helper) {
        try (Stage stage = new Stage(helper)) {
            Block cap = BwrBlocks.CONTROL_PANEL_END_CAP_BENCH.get();
            Block bench = BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
            for (Direction facing : HORIZONTAL) {
                // the operator looks toward -facing; a run on the cap's left (clockwise of the facing) makes a right cap
                Direction looking = facing.getOpposite();
                BlockPos target = ORIGIN;
                for (BlockPos neighbour : new BlockPos[] {target.relative(facing.getClockWise()), target.relative(facing.getCounterClockWise())}) {
                    stage.level.setBlock(neighbour, Blocks.AIR.defaultBlockState(), 2);
                }
                stage.level.setBlock(target.relative(facing.getClockWise()),
                        bench.defaultBlockState().setValue(PanelBlock.FACING, facing), 2);
                BlockState right = stage.placement(cap, target, looking);
                helper.assertTrue(right.getValue(PanelEndCapBlock.SIDE) == PanelEndCapBlock.Side.RIGHT,
                        facing + ": a run on the left should give a right cap, got " + right.getValue(PanelEndCapBlock.SIDE));
                stage.player.setShiftKeyDown(true);
                helper.assertTrue(stage.placement(cap, target, looking).getValue(PanelEndCapBlock.SIDE) == PanelEndCapBlock.Side.LEFT,
                        facing + ": sneaking takes the other side");
                stage.player.setShiftKeyDown(false);
                stage.level.setBlock(target.relative(facing.getClockWise()), Blocks.AIR.defaultBlockState(), 2);
                stage.level.setBlock(target.relative(facing.getCounterClockWise()),
                        bench.defaultBlockState().setValue(PanelBlock.FACING, facing), 2);
                helper.assertTrue(stage.placement(cap, target, looking).getValue(PanelEndCapBlock.SIDE) == PanelEndCapBlock.Side.LEFT,
                        facing + ": a run on the right should give a left cap");
                // a panel facing another way is not a run to close
                stage.level.setBlock(target.relative(facing.getCounterClockWise()),
                        bench.defaultBlockState().setValue(PanelBlock.FACING, facing.getOpposite()), 2);
                helper.assertTrue(stage.placement(cap, target, looking).getValue(PanelEndCapBlock.SIDE) == PanelEndCapBlock.Side.RIGHT,
                        facing + ": with no run alongside the cap is a right cap");
                stage.level.setBlock(target.relative(facing.getCounterClockWise()), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        helper.succeed();
    }

    // ---- shapes

    /** The height of a shape over a point of its footprint (x, z in block space), or -1 where it has nothing. */
    private static double heightAt(VoxelShape shape, double x, double z) {
        double top = -1.0;
        for (AABB box : shape.toAabbs()) {
            if (x >= box.minX - 1.0e-9 && x <= box.maxX + 1.0e-9 && z >= box.minZ - 1.0e-9 && z <= box.maxZ + 1.0e-9) {
                top = Math.max(top, box.maxY);
            }
        }
        return top;
    }

    @GameTest(template = "empty")
    public static void trimShapesAreSolidInsideTheirBlockAndMatchThePanelsTheyTrim(GameTestHelper helper) {
        for (TrimPiece piece : TrimPiece.values()) {
            Block block = blockOf(piece);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                VoxelShape shape = state.getShape(helper.getLevel(), ORIGIN);
                helper.assertTrue(!shape.isEmpty(), piece + " " + state + " has no shape");
                AABB bounds = shape.bounds();
                helper.assertTrue(bounds.minX >= -1.0e-9 && bounds.minY >= -1.0e-9 && bounds.minZ >= -1.0e-9
                        && bounds.maxX <= 1.0 + 1.0e-9 && bounds.maxY <= 1.0 + 1.0e-9 && bounds.maxZ <= 1.0 + 1.0e-9,
                        piece + " " + state + " leaves its block: " + bounds);
                helper.assertTrue(state.getCollisionShape(helper.getLevel(), ORIGIN).equals(shape), piece + " collision differs");
            }
        }
        // north-facing frame: x = 1 - lx, z = 1 - f. Heights along the depth of a panel body and of its end cap agree.
        VoxelShape bench = PanelGeometry.shape(PanelType.BENCHBOARD, Direction.NORTH);
        VoxelShape console = PanelGeometry.shape(PanelType.CONSOLE, Direction.NORTH);
        VoxelShape vertical = PanelGeometry.shape(PanelType.VERTICAL, Direction.NORTH);
        VoxelShape benchCap = TrimGeometry.shape(TrimPiece.END_CAP_BENCH, Direction.NORTH, true);
        VoxelShape consoleCap = TrimGeometry.shape(TrimPiece.END_CAP_CONSOLE, Direction.NORTH, true);
        VoxelShape flatCap = TrimGeometry.shape(TrimPiece.END_CAP_FLAT, Direction.NORTH, true);
        VoxelShape leftCap = TrimGeometry.shape(TrimPiece.END_CAP_BENCH, Direction.NORTH, false);
        for (int k = 0; k < 8; k++) {
            double z = 1.0 - (k + 0.5) / 8.0;
            helper.assertTrue(Math.abs(heightAt(benchCap, 0.95, z) - heightAt(bench, 0.5, z)) < 1.0e-9,
                    "the bench cap does not have the bench's cross-section at slice " + k);
            helper.assertTrue(Math.abs(heightAt(consoleCap, 0.95, z) - heightAt(console, 0.5, z)) < 1.0e-9,
                    "the console cap does not have the console's cross-section at slice " + k);
            // a right cap (a plate on the left edge of its block) is at x = 1 - lx, so near x = 0.95 for north;
            // a left cap is on the other edge
            helper.assertTrue(heightAt(leftCap, 0.05, z) > 0.0 && heightAt(leftCap, 0.95, z) < 0.0, "a left cap is on the other edge");
        }
        helper.assertTrue(Math.abs(heightAt(flatCap, 0.95, 0.875) - heightAt(vertical, 0.5, 0.875)) < 1.0e-9
                && heightAt(flatCap, 0.95, 0.5) < 0.0, "the flat cap has the vertical board's cross-section");
        // an inside bench corner continues the benchboard along its two open edges
        VoxelShape inside = TrimGeometry.shape(TrimPiece.CORNER_BENCH_INSIDE, Direction.NORTH, true);
        for (int k = 0; k < 8; k++) {
            double z = 1.0 - (k + 0.5) / 8.0;
            helper.assertTrue(Math.abs(heightAt(inside, 0.03, z) - heightAt(bench, 0.5, z)) < 1.0e-9,
                    "the inside bench corner does not meet a benchboard on its open side at slice " + k);
        }
        // an annunciator panel sits on a vertical board: a full block tall, flush with its face at the bottom, leaning back
        VoxelShape strip = TrimGeometry.shape(TrimPiece.HEADER_STRIP, Direction.NORTH, true);
        helper.assertTrue(Math.abs(strip.max(net.minecraft.core.Direction.Axis.Y) - 1.0) < 1.0e-9, "an annunciator panel is a block tall");
        helper.assertTrue(Math.abs(strip.min(net.minecraft.core.Direction.Axis.Z) - 0.75) < 1.0e-9,
                "an annunciator panel is as deep as a vertical board at its foot: " + strip.bounds());
        helper.assertTrue(heightAt(strip, 0.5, 0.80) < 0.95 && heightAt(strip, 0.5, 0.80) > 0.3,
                "an annunciator panel leans back: the top is not as deep as the foot");
        VoxelShape canopy = TrimGeometry.shape(TrimPiece.HEADER_CANOPY, Direction.NORTH, true);
        helper.assertTrue(canopy.min(net.minecraft.core.Direction.Axis.Z) < strip.min(net.minecraft.core.Direction.Axis.Z) - 0.15,
                "the canopy projects a hood in front of the panel");
        helper.succeed();
    }

    // ---- models read back from the shipped files

    private record Face(String material, List<double[]> corners) {
    }

    private static List<Face> readObj(String path) throws IOException {
        List<double[]> vertices = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        String material = "";
        try (InputStream in = PanelTrimTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts[0].equals("v")) {
                    vertices.add(new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])});
                } else if (parts[0].equals("usemtl")) {
                    material = parts[1];
                } else if (parts[0].equals("f")) {
                    List<double[]> corners = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        corners.add(vertices.get(Integer.parseInt(parts[i].split("/")[0]) - 1));
                    }
                    faces.add(new Face(material, corners));
                }
            }
        }
        return faces;
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0e-5 && Math.abs(a[1] - b[1]) < 1.0e-5 && Math.abs(a[2] - b[2]) < 1.0e-5;
    }

    @GameTest(template = "empty")
    public static void theMountingFacePlaneOfTheRemodelledBodiesIsUnchanged(GameTestHelper helper) throws IOException {
        for (PanelType type : PanelType.values()) {
            String name = switch (type) {
                case VERTICAL -> "vertical";
                case BENCHBOARD -> "benchboard";
                case CONSOLE -> "console";
                case BACK_PANEL -> "back";
                case DUPLEX_BOARD -> "duplex_board";
                case DUPLEX_BENCH -> "duplex_bench";
                case DUPLEX_HEADER -> "duplex_header";
                case HEADER_TILT -> "header_tilt";
                case CHAMFER_VERTICAL -> "chamfer_vertical";
                case CHAMFER_BENCH -> "chamfer_bench";
            };
            PanelGeometry.Frame frame = PanelGeometry.frame(type, Direction.NORTH);
            double[][] expected = {
                    {frame.x(0, 0, 0), frame.y(0, 0, 0), frame.z(0, 0, 0)},
                    {frame.x(16, 0, 0), frame.y(16, 0, 0), frame.z(16, 0, 0)},
                    {frame.x(16, 16, 0), frame.y(16, 16, 0), frame.z(16, 16, 0)},
                    {frame.x(0, 16, 0), frame.y(0, 16, 0), frame.z(0, 16, 0)}};
            List<Face> faces = readObj("/assets/bwr/models/block/control_panel/" + name + ".obj");
            List<Face> mounting = faces.stream().filter(f -> f.material().equals("face")).toList();
            helper.assertTrue(mounting.size() == 1 && mounting.get(0).corners().size() == 4,
                    name + ": exactly one four-corner 'face' polygon, got " + mounting.size());
            for (double[] corner : expected) {
                helper.assertTrue(mounting.get(0).corners().stream().anyMatch(c -> same(c, corner)),
                        name + ": the face lost the corner " + java.util.Arrays.toString(corner) + " the mounting grid is anchored to");
            }
            // nothing else of the model is in front of the plane (the normal points to the operator): detail may only
            // sit on or behind it, so every cell of the grid is still mounted on a face
            for (Face face : faces) {
                for (double[] c : face.corners()) {
                    double above = (c[0] - frame.ox) * frame.nx + (c[1] - frame.oy) * frame.ny + (c[2] - frame.oz) * frame.nz;
                    helper.assertTrue(above < 1.0e-5, name + " " + face.material() + " reaches " + above + " in front of the mounting plane");
                    if (face.material().equals("face")) {
                        helper.assertTrue(Math.abs(above) < 1.0e-5, name + " face corner off the plane by " + above);
                    }
                }
            }
            helper.assertTrue(faces.size() <= 24, name + " is " + faces.size() + " faces; these blocks tile whole rooms");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void trimModelsAreLightInsideTheirBlockAndUseKnownMaterials(GameTestHelper helper) throws IOException {
        Set<String> materials = new HashSet<>();
        try (InputStream in = PanelTrimTests.class.getResourceAsStream("/assets/bwr/models/block/control_panel_trim/materials.mtl")) {
            helper.assertTrue(in != null, "materials.mtl is missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("newmtl ")) {
                    materials.add(line.substring(7).trim());
                } else if (line.startsWith("map_Kd ")) {
                    String texture = line.substring(7).trim().replace("bwr:", "");
                    helper.assertTrue(PanelTrimTests.class.getResource("/assets/bwr/textures/" + texture + ".png") != null,
                            "texture " + texture + " is missing");
                }
            }
        }
        Set<String> models = new HashSet<>();
        for (TrimPiece piece : TrimPiece.values()) {
            String name = piece.registryName().substring("control_panel_".length());
            if (piece.isEndCap()) {
                models.add(name + "_left");
                models.add(name + "_right");
            } else {
                models.add(name);
            }
        }
        for (String model : models) {
            List<Face> faces = readObj("/assets/bwr/models/block/control_panel_trim/" + model + ".obj");
            helper.assertTrue(!faces.isEmpty() && faces.size() <= 28, model + " has " + faces.size() + " faces");
            for (Face face : faces) {
                helper.assertTrue(face.corners().size() <= 4, model + " has a face with more than four corners");
                helper.assertTrue(materials.contains(face.material()), model + " uses the unknown material " + face.material());
                for (double[] c : face.corners()) {
                    helper.assertTrue(c[0] >= -1.0e-5 && c[0] <= 1.0 + 1.0e-5 && c[1] >= -1.0e-5 && c[1] <= 1.0 + 1.0e-5
                            && c[2] >= -1.0e-5 && c[2] <= 1.0 + 1.0e-5, model + " leaves its block");
                }
            }
            helper.assertTrue(PanelTrimTests.class.getResource("/assets/bwr/models/block/control_panel_trim/" + model + ".json") != null,
                    model + " has no model json");
        }
        helper.succeed();
    }
}
