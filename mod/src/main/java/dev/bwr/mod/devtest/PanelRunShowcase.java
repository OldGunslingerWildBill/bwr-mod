package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.AngledRunBlock;
import dev.bwr.mod.panel.AngledRunGeometry;
import dev.bwr.mod.panel.AngledRunGeometry.Family;
import dev.bwr.mod.panel.AngledRunGeometry.Role;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.PanelClientInput;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The modular 45 degree runs part of the panel client check (CONTROL-PANELS-SPEC section 14): a desk run and a tall panel
 * run, inside and outside, each built piece by piece (straight blocks, bend A, three units, bend B, straight blocks) with
 * components on every unit face, once from a distance for the whole run and once close to a face, and a room corner in which
 * the angled tall panel fills the corner of two tall-panel runs along the walls. Every picture is 1920 x 1080 (see
 * {@link CheckShots}). The check confirms that every block drew from its model, that every component reached the client, and
 * (the close pictures look straight at the middle of a face) that the client's own aiming, through the block under the
 * crosshair, finds the dial at the middle of the face.
 *
 * <p>Plan: each run faces north (the operator at smaller z), the floor is at y 125 and the pieces stand on it at y 126; bend A's
 * origin is the Spec's block, the straight run A arrives to the west of it, the run turns toward the operator (inside, north) or
 * away (outside, south) and east.
 */
final class PanelRunShowcase {
    private PanelRunShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final int UNITS = 3;
    private static final int STRAIGHT = 3;

    private record Spec(Family family, boolean inside, int x, int z, boolean walls, boolean face) {
    }

    private static final Spec[] SPECS = {
            new Spec(Family.DESK, true, 6310, 5600, false, false), new Spec(Family.DESK, true, 6310, 5600, false, true),
            new Spec(Family.DESK, false, 6310, 5625, false, false), new Spec(Family.DESK, false, 6310, 5625, false, true),
            new Spec(Family.TALL, true, 6350, 5600, false, false), new Spec(Family.TALL, true, 6350, 5600, false, true),
            new Spec(Family.TALL, false, 6350, 5625, false, false), new Spec(Family.TALL, false, 6350, 5625, false, true),
            new Spec(Family.TALL, true, 6390, 5640, true, false)};
    /** The dial of the middle of the face, covering cell (8, 5): what the crosshair of the close stages finds. */
    private static final int AIM_X = 8;
    private static final int AIM_Y = 5;

    static final String[] NAMES = {"angled-run-desk-inside-3-piece-by-piece", "angled-run-desk-inside-3-face",
            "angled-run-desk-outside-3-piece-by-piece", "angled-run-desk-outside-3-face",
            "angled-run-tall-inside-3-piece-by-piece", "angled-run-tall-inside-3-face",
            "angled-run-tall-outside-3-piece-by-piece", "angled-run-tall-outside-3-face",
            "angled-run-room-tall-inside-fills-the-corner"};

    static boolean night(int stage) {
        return false;
    }

    private static BlockPos origin(Spec spec) {
        return new BlockPos(spec.x(), 126, spec.z());
    }

    private static BlockPos unitOrigin(Spec spec, int k) {
        return AngledRunBlock.chainOrigin(origin(spec), FACING, false, spec.inside(), k);
    }

    /** The face the close stages look at: the middle unit's board for the tall run (its first vertical row), the desk top. */
    private static AngledRunGeometry.Face aimFace(Spec spec) {
        List<AngledRunGeometry.Face> faces = AngledRunGeometry.piece(spec.family(), spec.inside(), Role.UNIT).faces();
        return faces.get(spec.family() == Family.TALL ? 1 : 0);
    }

    private static BlockPos core(Spec spec, int unit, AngledRunGeometry.Face face) {
        return AngledRunBlock.partPos(unitOrigin(spec, unit), FACING, false, face.core());
    }

    private static PanelGeometry.Frame frameOf(Spec spec, AngledRunGeometry.Face face) {
        return AngledRunGeometry.faceFrame(spec.family(), spec.inside(), face, FACING, false);
    }

    private static Vec3 faceAt(Spec spec, int unit, AngledRunGeometry.Face face, double cu, double cv) {
        PanelGeometry.Frame f = frameOf(spec, face);
        BlockPos core = core(spec, unit, face);
        return new Vec3(core.getX() + f.x(cu, cv, 0), core.getY() + f.y(cu, cv, 0), core.getZ() + f.z(cu, cv, 0));
    }

    static Vec3 camera(int stage) {
        Spec spec = SPECS[stage];
        AngledRunGeometry.Face face = aimFace(spec);
        PanelGeometry.Frame f = frameOf(spec, face);
        Vec3 target = target(stage);
        if (spec.face()) {
            if (spec.family() == Family.DESK) {
                // a metre and a bit out on the operator side of the face, looking down at its middle
                return new Vec3(target.x + f.downX * 1.15, target.y + 0.9 - 1.62, target.z + f.downZ * 1.15);
            }
            // along the normal of a board, a little less than two metres out
            return new Vec3(target.x + f.nx * 1.7, target.y + f.ny * 1.7 - 1.62, target.z + f.nz * 1.7);
        }
        // from the operator side of the diagonal, back and above enough to see both straight runs and the whole angled section
        // (the horizontal direction toward the operator: a board's normal, a desk top's rows)
        double back = spec.walls() ? 5.4 : (spec.family() == Family.DESK ? 7.5 : 8.5);
        double up = spec.family() == Family.TALL ? (spec.walls() ? 1.62 : 3.6) : 5.0;
        double hx = spec.family() == Family.TALL ? f.nx : f.downX;
        double hz = spec.family() == Family.TALL ? f.nz : f.downZ;
        Vec3 centre = faceAt(spec, 2, aimFace(spec), 8, 8);
        return new Vec3(centre.x + hx * back, centre.y + up - 1.62, centre.z + hz * back);
    }

    static Vec3 target(int stage) {
        Spec spec = SPECS[stage];
        AngledRunGeometry.Face face = aimFace(spec);
        if (spec.face()) {
            return faceAt(spec, 2, face, AIM_X + 0.5, AIM_Y + 0.5);
        }
        Vec3 point = faceAt(spec, 2, face, 8, face.rows() / 2.0);
        return spec.walls() ? point.add(0, -0.4, 0) : point.add(0, spec.family() == Family.TALL ? -0.2 : -0.15, 0);
    }

    // ---- building (server)

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static final List<BlockPos> CORES = new ArrayList<>();
    private static final java.util.Map<BlockPos, Integer> AIM_IDS = new java.util.HashMap<>();
    private static int mounted;

    private static void straight(ServerLevel level, Family family, BlockPos pos, Direction facing) {
        if (family == Family.DESK) {
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                    .setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
            return;
        }
        level.setBlock(pos, BwrBlocks.CONTROL_PANEL_BENCHBOARD.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                .setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
        for (int y = 1; y <= 2; y++) {
            level.setBlock(pos.above(y), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                    .setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
        }
        level.setBlock(pos.above(3), BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                .setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
    }

    private static void piece(ServerLevel level, Spec spec, Role role, BlockPos origin) {
        AngledRunBlock block = BwrBlocks.ANGLED_RUNS.get("control_panel_" + spec.family().id() + "_run_" + (spec.inside() ? "inside" : "outside")
                + "_" + role.id()).get();
        BlockState state = block.defaultBlockState().setValue(PanelBlock.FACING, FACING).setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM);
        List<AngledRunGeometry.Part> parts = AngledRunGeometry.piece(spec.family(), spec.inside(), role).parts();
        for (int i = 0; i < parts.size(); i++) {
            BlockPos at = AngledRunBlock.partPos(origin, FACING, false, parts.get(i));
            level.setBlock(at, state.setValue(AngledRunBlock.PART, i), 3);
            PLACED.add(at);
        }
    }

    private static PanelComponent real(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type) {
        Footprint size = ShowcaseSizes.layoutSize(type);
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, size);
        if (component == null) {
            throw new AssertionError("Run showcase: placement refused " + type.id() + " " + size.w() + "x" + size.h() + " at "
                    + pos + " " + cx + "," + cy);
        }
        mounted++;
        return component;
    }

    private static void reading(ServerLevel level, BlockPos pos, PanelComponent component, double value) {
        component.setSource(0, InputSources.constant(value));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
    }

    private static void lamp(ServerLevel level, BlockPos pos, int cx, int cy, int n) {
        PanelComponent lamp = real(level, pos, cx, cy, ComponentTypes.LAMP);
        ((LampData) lamp.data()).color = n % 2 == 0 ? 0xE02020 : 0x20C040;
        reading(level, pos, lamp, 1);
    }

    private static final String[][] ALARMS = {{"RPV LEVEL", "LOW L3"}, {"RPV PRESS", "HIGH"}, {"MSIV", "NOT OPEN"},
            {"RECIRC A", "TRIP"}, {"RFP A", "TRIP"}, {"HPCS", "INITIATED"}, {"APRM", "UPSCALE"}, {"TURB", "TRIP"}};

    /** A face filled: a row of annunciators, three dials (the middle one over cell (8, 5)), switches, pushbuttons and lamps; a tall face (16 rows) gets a second row of buttons. */
    private static void loadFace(ServerLevel level, BlockPos pos, int c, int rows) {
        for (int i = 0; i < 8; i++) {
            PanelComponent tile = real(level, pos, i * 2, 0, ComponentTypes.ANNUNCIATOR);
            AnnunciatorData d = (AnnunciatorData) tile.data();
            String[] text = ALARMS[(c * 3 + i) % ALARMS.length];
            d.line1 = text[0];
            d.line2 = text[1];
            int state = (i * 3 + c) % 5;
            if (state == 0) {
                d.logic.update(true);
            } else if (state == 1) {
                d.logic.update(true);
                d.logic.acknowledge();
            }
            tile.touch();
        }
        PanelComponent aimed = real(level, pos, 6, 2, ComponentTypes.DIAL_GAUGE);
        reading(level, pos, aimed, 64);
        AIM_IDS.put(pos.immutable(), aimed.id());
        PanelComponent dial = real(level, pos, 2, 2, ComponentTypes.DIAL_GAUGE);
        reading(level, pos, dial, 31);
        PanelComponent third = real(level, pos, 10, 2, ComponentTypes.DIAL_GAUGE);
        reading(level, pos, third, 82);
        for (int i = 0; i < 2; i++) {
            PanelComponent selector = PanelOps.placeDirect(level, pos, 14, 2 + i * 4, ComponentTypes.KEY_SWITCH, Footprint.of(2, 4));
            if (selector != null) {
                mounted++;
            }
        }
        for (int i = 0; i < 4; i++) {
            PanelComponent button = PanelOps.placeDirect(level, pos, i * 2, 6, ComponentTypes.PUSHBUTTON, Footprint.of(2, 2));
            if (button == null) {
                throw new AssertionError("Run showcase: no pushbutton at " + pos + " " + (i * 2) + ",6");
            }
            mounted++;
            lamp(level, pos, i * 2, 8, c + i);
        }
        for (int i = 0; i < 3; i++) {
            PanelComponent sw = PanelOps.placeDirect(level, pos, 8 + i * 2, 6, ComponentTypes.CONTROL_SWITCH, Footprint.of(2, 3));
            if (sw == null) {
                throw new AssertionError("Run showcase: no control switch at " + pos);
            }
            mounted++;
        }
        if (rows > 10) {
            for (int i = 0; i < 6; i++) {
                PanelComponent button = PanelOps.placeDirect(level, pos, i * 2 + 2, 11, ComponentTypes.PUSHBUTTON, Footprint.of(2, 2));
                if (button == null) {
                    throw new AssertionError("Run showcase: no lower pushbutton at " + pos);
                }
                mounted++;
                lamp(level, pos, i * 2 + 2, 13, c + i + 1);
            }
        }
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        CORES.clear();
        AIM_IDS.clear();
        mounted = 0;
        for (int x = 6296; x <= 6420; x++) {
            for (int z = 5580; z <= 5664; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 134; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (Spec spec : SPECS) {
            if (spec.face()) {
                continue; // the close stage looks at the same run
            }
            BlockPos origin = origin(spec);
            Direction right = FACING.getCounterClockWise();
            int sf = AngledRunGeometry.chainStep(spec.inside())[1];
            // the straight run A arrives from the operator's right (west of the origin)
            for (int i = 1; i <= STRAIGHT; i++) {
                straight(level, spec.family(), origin.relative(right, i), FACING);
            }
            piece(level, spec, Role.BEND_A, origin);
            for (int k = 1; k <= UNITS; k++) {
                piece(level, spec, Role.UNIT, unitOrigin(spec, k));
            }
            BlockPos bendB = unitOrigin(spec, UNITS + 1);
            piece(level, spec, Role.BEND_B, bendB);
            // the straight run B: its blocks face along the piece's x (inside) or against it (outside)
            int[] straight = AngledRunGeometry.piece(spec.family(), spec.inside(), Role.BEND_B).straight();
            Direction legFacing = spec.inside() ? right : right.getOpposite();
            for (int i = 0; i < STRAIGHT; i++) {
                straight(level, spec.family(), bendB.relative(FACING, straight[1] * (1 + i)), legFacing);
            }
            for (int k = 1; k <= UNITS; k++) {
                for (AngledRunGeometry.Face face : AngledRunGeometry.piece(spec.family(), spec.inside(), Role.UNIT).faces()) {
                    BlockPos core = core(spec, k, face);
                    CORES.add(core);
                    loadFace(level, core, k + face.by() + (spec.inside() ? 0 : 1), face.rows());
                }
            }
            if (spec.walls()) {
                // a room corner behind the two runs: the wall behind run A (south of it) and the wall behind run B (east of it)
                BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
                int eastWall = origin.getX() + UNITS + 2;
                for (int y = 126; y <= 132; y++) {
                    for (int x = origin.getX() - STRAIGHT; x <= eastWall; x++) {
                        level.setBlock(new BlockPos(x, y, origin.getZ() + 1), brick, 2);
                    }
                    for (int z = origin.getZ() + 1; z >= origin.getZ() - UNITS - 1 - STRAIGHT - 1; z--) {
                        level.setBlock(new BlockPos(eastWall, y, z), brick, 2);
                    }
                }
            }
        }
        for (BlockPos pos : CORES) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /**
     * Every block of every run drew from its own model, the components of every face are on the client and, for a close stage
     * (the camera looks at the middle of a face), the client's own aiming finds the dial there through the block under the
     * crosshair.
     */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof AngledRunBlock)) {
                wrong.add(pos + " is " + state);
                continue;
            }
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            int count = model.getQuads(state, null, RandomSource.create()).size();
            if (model == missing || count == 0) {
                wrong.add(state.toString());
            }
            quads += count;
        }
        for (BlockPos pos : CORES) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
            }
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("Run showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != mounted) {
            throw new AssertionError("Run showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        String aimed = "";
        if (SPECS[stage].face()) {
            if (!(mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK)) {
                throw new AssertionError("Run showcase " + NAMES[stage] + ": the crosshair is on no block");
            }
            var claim = PanelClientInput.hoveredClaim(mc);
            Spec spec = SPECS[stage];
            BlockPos expected = core(spec, 2, aimFace(spec));
            if (claim == null || !claim.owner().equals(expected) || claim.id() != AIM_IDS.get(expected)) {
                throw new AssertionError("Run showcase " + NAMES[stage] + ": the crosshair on the middle of the face (cell " + AIM_X + "," + AIM_Y
                        + ") found " + claim + " through " + ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos()
                        + ", expected the dial " + AIM_IDS.get(expected) + " of the core at " + expected);
            }
            aimed = ", the crosshair through " + ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos() + " finds the dial";
        }
        return PLACED.size() + " blocks, " + quads + " quads, " + components + " components" + aimed;
    }
}
