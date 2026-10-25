package dev.bwr.mod.spentfuel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.core.refuelling.FuelElevatorMotion;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.refuelling.client.RefuellingBridgeRenderer;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlock;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Matrix3f;

/**
 * Draws the new fuel elevator from Blender-authored parts ({@code art/models/new_fuel_elevator}, exported by
 * {@code tools-export-new-fuel-elevator.py}) through {@link MachineMeshCache}: the hoist stand on the rim, the guide frame down the inside of
 * the pool wall (one mesh per depth), the carriage on its rope, and the assembly it carries. Nothing is an entity and nothing ticks: the pose
 * is the trip the server started (its start tick and legs, {@link FuelElevatorMotion}) advanced by the client clock, so a trip costs the
 * network one packet when it begins and one when it changes hands.
 *
 * <p>The parts are authored with the pool towards +X; the frame is turned so that +X is the direction the elevator found its pool in (or, until it
 * has found one, the way the block faces away from the player). The assembly sliding over the racks is drawn in world axes: it is four-fold symmetric.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class NewFuelElevatorRenderer implements BlockEntityRenderer<NewFuelElevatorBlockEntity> {

    public static final String[] PARTS = {"head", "guide", "carriage", "cable"};

    /** The guide axis, in the frame of the block (art/models/new_fuel_elevator/build_models.py). */
    private static final double GX = 1.45;
    private static final double GZ = 0.5;
    /** The bottom of the cable drum on the hoist: where the rope leaves it. */
    private static final double DRUM_BOTTOM = 2.015;
    /** The eye of the rope on the carriage, above the hook. */
    private static final double EYE_ABOVE_HOOK = 0.55;

    private record Part(String name) {
    }

    private record Depth(int blocks) {
    }

    public static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/new_fuel_elevator/" + part + "/body"));
    }

    public NewFuelElevatorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        for (String part : PARTS) {
            event.register(model(part));
        }
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.NEW_FUEL_ELEVATOR.get(), NewFuelElevatorRenderer::new);
    }

    /** Degrees of turn about Y that take the +X of a part to the world direction {@code d}. */
    private static float turn(Direction d) {
        return switch (d) {
            case NORTH -> 90.0f;
            case WEST -> 180.0f;
            case SOUTH -> 270.0f;
            default -> 0.0f;
        };
    }

    private static Direction inward(NewFuelElevatorBlockEntity be) {
        if (NewFuelElevatorBlockEntity.validInward(be.inwardIndex())) {
            return Direction.from2DDataValue(be.inwardIndex());
        }
        return be.getBlockState().getValue(NewFuelElevatorBlock.FACING).getOpposite();
    }

    @Override
    public void render(NewFuelElevatorBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var level = be.getLevel();
        if (level == null) {
            return;
        }
        BlockPos root = be.getBlockPos();
        Direction in = inward(be);
        boolean linked = NewFuelElevatorBlockEntity.validInward(be.inwardIndex());
        int depth = linked ? (int) Math.round(root.getY() - be.floorY()) : 0;

        // the carriage and what it carries
        double hookLocal = FuelElevatorMotion.HATCH_ABOVE_BLOCK_M;
        FuelElevatorMotion.Pose p = null;
        var trip = be.currentTrip();
        if (trip != null) {
            double elapsed = level.getGameTime() + partial - be.tripStart();
            p = FuelElevatorMotion.poseAt(trip, elapsed);
            hookLocal = p.carriageY() - root.getY();
        }

        // the frame: origin at the corner of the block, +X into the pool
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(turn(in)));
        pose.translate(-0.5, 0.0, -0.5);
        part(pose, buffers, "head", light, overlay);
        if (depth > 0) {
            int lit = LevelRenderer.getLightColor(level, root.relative(in).below(Math.max(1, depth / 2)));
            MachineMeshCache.draw(new Depth(depth), b -> {
                for (int k = 0; k < depth; k++) {
                    b.part(model("guide"), 0, -depth + k, 0, 1, 1, 1);
                }
            }, pose, buffers, lit, overlay);
        }
        int carriageLight = work(LevelRenderer.getLightColor(level, root.offset(0, (int) Math.floor(hookLocal), 0).relative(in)));
        pose.pushPose();
        pose.translate(GX, hookLocal, GZ);
        part(pose, buffers, "carriage", carriageLight, overlay);
        pose.popPose();
        // the rope from the drum to the eye of the carriage: a unit rope stretched, its normals kept unscaled
        double eye = hookLocal + EYE_ABOVE_HOOK;
        double length = DRUM_BOTTOM - eye;
        if (length > 0.01) {
            pose.pushPose();
            pose.translate(GX, eye, GZ);
            Matrix3f normal = new Matrix3f(pose.last().normal());
            pose.scale(1.0f, (float) length, 1.0f);
            pose.last().normal().set(normal);
            part(pose, buffers, "cable", carriageLight, overlay);
            pose.popPose();
        }
        pose.popPose();

        // the assembly: on the carriage, over the racks, or waiting in the hatch (world axes)
        double ax = 0.0;
        double ay = 0.0;
        double az = 0.0;
        boolean show = false;
        if (p != null && p.hasAssembly()) {
            ax = p.ax();
            ay = p.ay();
            az = p.az();
            show = true;
        } else if (trip == null && be.visAtHatch()) {
            Vec3 hatch = Vec3.atBottomCenterOf(root).add(in.getStepX() * 0.95, FuelElevatorMotion.HATCH_ABOVE_BLOCK_M, in.getStepZ() * 0.95);
            ax = hatch.x;
            ay = hatch.y;
            az = hatch.z;
            show = true;
        }
        if (show) {
            pose.pushPose();
            pose.translate(ax - root.getX(), ay - root.getY(), az - root.getZ());
            part(pose, buffers, "assembly", work(LevelRenderer.getLightColor(level, BlockPos.containing(ax, ay - 1.0, az))), overlay);
            pose.popPose();
        }
    }

    private static void part(PoseStack pose, MultiBufferSource buffers, String name, int light, int overlay) {
        ModelResourceLocation m = name.equals("assembly") ? RefuellingBridgeRenderer.model("assembly") : model(name);
        MachineMeshCache.draw(new Part(name), b -> b.part(m, 0, 0, 0, 1, 1, 1), pose, buffers, light, overlay);
    }

    /** The carriage carries its own lamp: never darker than a lit room. */
    private static int work(int packed) {
        return LightTexture.pack(Math.max(LightTexture.block(packed), 10), Math.max(LightTexture.sky(packed), 6));
    }

    @Override
    public AABB getRenderBoundingBox(NewFuelElevatorBlockEntity be) {
        BlockPos p = be.getBlockPos();
        double depth = Math.max(0.0, p.getY() - be.floorY()) + 2.0;
        AABB box = new AABB(p).inflate(3.0, 0.0, 3.0).expandTowards(0.0, -depth, 0.0).expandTowards(0.0, 3.0, 0.0);
        var trip = be.currentTrip();
        if (trip != null) {
            box = box.minmax(new AABB(trip.cellX() - 1.0, trip.seatY() - 5.0, trip.cellZ() - 1.0, trip.cellX() + 1.0, trip.hatchY() + 1.0, trip.cellZ() + 1.0));
        }
        return box;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRenderOffScreen(NewFuelElevatorBlockEntity be) {
        return true;
    }
}
