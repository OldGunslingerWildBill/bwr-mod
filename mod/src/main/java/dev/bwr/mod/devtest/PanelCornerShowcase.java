package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.CornerPart;
import dev.bwr.mod.panel.DeskCornerBlock;
import dev.bwr.mod.panel.DeskCornerGeometry;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The 45 degree desk corners part of the panel client check (CONTROL-PANELS-SPEC section 14): an inside corner (the
 * console wrapping around the operator) and an outside corner (wrapping away), each with a straight desk run on both sides
 * and components mounted on the angled face, once from a distance for the run, corner and run, and once close to the face.
 * Every picture is 1920 x 1080 (see {@link CheckShots}). The check confirms that every block of each corner drew from its
 * model, that every component reached the client, and (the close pictures look straight at the middle of the face) that the
 * client's own aiming, through the block under the crosshair, finds the dial at the middle of the angled face.
 *
 * <p>Plan: the inside corner's origin block is at x 6210, z 5600, the outside one's at x 6210, z 5612; both face north
 * (the operator stands at smaller z), the floor is at y 125 and the desks stand on it at y 126. The straight runs are two
 * 1 m desks to the operator's right (west) of each corner and two to the way the corner turns.
 */
final class PanelCornerShowcase {
    private PanelCornerShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final int X = 6210;
    private static final int INSIDE_Z = 5600;
    private static final int OUTSIDE_Z = 5612;
    /** The dial of the middle of the face, covering cell (8, 5): what the crosshair of the close stages finds. */
    private static final int AIM_X = 8;
    private static final int AIM_Y = 5;

    static final String[] NAMES = {"corner-inside-run-corner-run", "corner-inside-face", "corner-outside-run-corner-run",
            "corner-outside-face"};

    static boolean night(int stage) {
        return false;
    }

    private static boolean inside(int stage) {
        return stage < 2;
    }

    private static boolean close(int stage) {
        return stage % 2 == 1;
    }

    private static BlockPos origin(boolean inside) {
        return new BlockPos(X, 126, inside ? INSIDE_Z : OUTSIDE_Z);
    }

    private static BlockPos core(boolean inside) {
        return DeskCornerGeometry.core(inside).at(origin(inside), FACING, inside);
    }

    private static Vec3 faceAt(boolean inside, double cu, double cv, double lift) {
        PanelGeometry.Frame f = PanelGeometry.frame(inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE, FACING);
        BlockPos core = core(inside);
        return new Vec3(core.getX() + f.x(cu, cv, lift), core.getY() + f.y(cu, cv, lift), core.getZ() + f.z(cu, cv, lift));
    }

    static Vec3 camera(int stage) {
        boolean inside = inside(stage);
        PanelGeometry.Frame f = PanelGeometry.frame(inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE, FACING);
        Vec3 target = target(stage);
        if (close(stage)) {
            // a metre and a bit out on the operator's side of the face, looking down at its middle
            return new Vec3(target.x + f.downX * 1.15, target.y + 0.9 - 1.62, target.z + f.downZ * 1.15);
        }
        // from the operator's side, above, far enough back to see both runs and the corner
        return new Vec3(target.x + f.downX * 3.4 - f.rightX * 2.2, target.y + 2.4 - 1.62, target.z + f.downZ * 3.4 - f.rightZ * 2.2);
    }

    static Vec3 target(int stage) {
        boolean inside = inside(stage);
        return close(stage) ? faceAt(inside, AIM_X + 0.5, AIM_Y + 0.5, 0) : faceAt(inside, 8, 4, 0).add(0, -0.15, 0);
    }

    // ---- building (server)

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static final List<BlockPos> CORES = new ArrayList<>();
    private static int mounted;
    private static int aimId;

    private static void desk(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlock(pos, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                .setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
    }

    private static void corner(ServerLevel level, boolean inside) {
        Block block = inside ? BwrBlocks.CONTROL_PANEL_DESK_CORNER_INSIDE.get() : BwrBlocks.CONTROL_PANEL_DESK_CORNER_OUTSIDE.get();
        BlockState state = block.defaultBlockState().setValue(PanelBlock.FACING, FACING).setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM);
        for (CornerPart part : CornerPart.values()) {
            BlockPos at = part.at(origin(inside), FACING, inside);
            level.setBlock(at, state.setValue(DeskCornerBlock.PART, part), 3);
            PLACED.add(at);
        }
        CORES.add(core(inside));
    }

    private static PanelComponent real(ServerLevel level, BlockPos pos, int cx, int cy, ComponentType type) {
        Footprint size = ShowcaseSizes.layoutSize(type);
        PanelComponent component = PanelOps.placeDirect(level, pos, cx, cy, type, size);
        if (component == null) {
            throw new AssertionError("Corner showcase: placement refused " + type.id() + " " + size.w() + "x" + size.h() + " at "
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

    /** The whole 16 x 10 face filled, a dial over the middle cell. */
    private static void loadFace(ServerLevel level, BlockPos pos, int c) {
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
        aimId = aimed.id();
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
                throw new AssertionError("Corner showcase: no pushbutton at " + pos + " " + (i * 2) + ",6");
            }
            mounted++;
            lamp(level, pos, i * 2, 8, c + i);
        }
        for (int i = 0; i < 3; i++) {
            PanelComponent sw = PanelOps.placeDirect(level, pos, 8 + i * 2, 6, ComponentTypes.CONTROL_SWITCH, Footprint.of(2, 3));
            if (sw == null) {
                throw new AssertionError("Corner showcase: no control switch at " + pos);
            }
            mounted++;
        }
    }

    static void build(ServerLevel level) {
        PLACED.clear();
        CORES.clear();
        mounted = 0;
        for (int x = 6196; x <= 6222; x++) {
            for (int z = 5590; z <= 5622; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 133; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (boolean inside : new boolean[] {true, false}) {
            BlockPos origin = origin(inside);
            corner(level, inside);
            // the run the corner continues arrives on the operator's right (west) of the origin, in the same row, facing the same way
            for (int i = 1; i <= 2; i++) {
                desk(level, origin.relative(FACING.getCounterClockWise(), i), FACING);
            }
            // the second run leaves at the far side of the footprint, turned: left column, beyond the second block
            Direction turned = inside ? FACING.getCounterClockWise() : FACING.getClockWise();
            Direction away = inside ? FACING : FACING.getOpposite();
            BlockPos second = origin.relative(FACING.getClockWise()).relative(away, 2);
            for (int i = 0; i < 2; i++) {
                desk(level, second.relative(away, i), turned);
            }
            loadFace(level, core(inside), inside ? 0 : 1);
        }
        for (BlockPos pos : CORES) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    // ---- checking (client)

    /**
     * Every block of both corners drew from its own model, the components of both faces are on the client and, for a close
     * stage (the camera looks at the middle of the face), the client's own aiming finds the dial there through the block
     * under the crosshair.
     */
    static String verify(Minecraft mc, int stage) {
        BakedModel missing = mc.getModelManager().getMissingModel();
        int quads = 0;
        int components = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : PLACED) {
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof DeskCornerBlock)) {
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
            throw new AssertionError("Corner showcase " + NAMES[stage] + ": missing or empty block models: " + wrong);
        }
        if (components != mounted) {
            throw new AssertionError("Corner showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        String aimed = "";
        if (close(stage)) {
            if (!(mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK)) {
                throw new AssertionError("Corner showcase " + NAMES[stage] + ": the crosshair is on no block");
            }
            var claim = PanelClientInput.hoveredClaim(mc);
            boolean inside = inside(stage);
            if (claim == null || !claim.owner().equals(core(inside)) || claim.id() != aimId) {
                throw new AssertionError("Corner showcase " + NAMES[stage] + ": the crosshair on the middle of the face (cell " + AIM_X + "," + AIM_Y
                        + ") found " + claim + " through " + ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos()
                        + ", expected the dial " + aimId + " of the core at " + core(inside));
            }
            aimed = ", the crosshair through " + ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos() + " finds the dial";
        }
        return PLACED.size() + " blocks, " + quads + " quads, " + components + " components" + aimed;
    }
}
