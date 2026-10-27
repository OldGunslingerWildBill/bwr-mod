package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
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
 * Draws the reactor building crane from the console's synced state, as Blender-authored parts ({@code art/models/building_crane},
 * exported by {@code tools-export-building-crane.py}) drawn through {@link MachineMeshCache}: two end carriages on the runway rails,
 * the two box girders between them with their walkway and the operator's cab (one mesh for the whole span: the plain section
 * stretched to it and the detailed one tiled every metre), the trolley with its hoist drum, the two falls of wire rope and the hook
 * block, and what hangs from the hook: the head on its strongback (hook ring, spreader and four slings to the lifting lugs) or the
 * dryer or the separator assembly by its lifting eyes. Nothing is a block and nothing is an entity: the pose is the leg the server
 * started (from, to, ticks done, ticks in the leg) advanced by the client clock, so a moving crane costs one packet per phase.
 *
 * <p>The parts are authored for a runway along X (the girders running to +Z from the first rail); for a runway along Z the frame is
 * turned a quarter turn. The crane is 2.7 m along the runway and as long across as the rails are apart.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BuildingCraneRenderer implements BlockEntityRenderer<BuildingCraneBlockEntity> {

    public static final String[] PARTS = {"end_carriage", "girder_plain", "girder_tile", "cab", "trolley", "hook_block", "rope", "strongback_hook",
            "spreader", "sling", "stand_cradle", "tensioner_tool"};

    /** Cache key of a part that does not depend on the span. */
    private record Part(String name) {
    }

    /** Cache key of the girder mesh: the plain section stretched and the tile repeated over this many blocks. */
    private record Span(int blocks) {
    }

    /** Where the two falls of rope leave the drum and where they meet the hook block, metres over the runway block's floor / over the hook point. */
    private static final double ROPE_TOP = 3.10;
    private static final double HOOK_TOP = 1.05;
    private static final double[] FALLS = {-0.2, 0.2};
    /** The lowest point of the spreader's shackles under the hook point (the model's -0.74). */
    private static final double SHACKLE_DROP = 0.74;

    public static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/building_crane/" + part + "/body"));
    }

    public BuildingCraneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        for (String part : PARTS) {
            event.register(model(part));
        }
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.BUILDING_CRANE.get(), BuildingCraneRenderer::new);
    }

    @Override
    public void render(BuildingCraneBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BuildingCraneBlockEntity.Geom g = be.geom();
        Point tip = be.tip(partial);
        if (g == null || tip == null || be.getLevel() == null) {
            return;
        }
        var level = be.getLevel();
        BlockPos root = be.getBlockPos();
        pose.pushPose();
        pose.translate(-root.getX(), -root.getY(), -root.getZ());

        double a = g.alongX() ? tip.x() : tip.z();
        double c = g.alongX() ? tip.z() : tip.x();
        double ry = g.railY();
        double c0 = g.cLow() + 0.5;
        int span = Math.max(1, g.cHigh() - g.cLow());
        double across = c - c0;
        double tipLocal = tip.y() - ry;
        int work = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() + 0.5, tip.z())));
        int trolleyLit = LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), ry + 3.2, tip.z()));
        double mid = c0 + span / 2.0;
        int girderLit = LevelRenderer.getLightColor(level, BlockPos.containing(g.alongX() ? a : mid, ry + 2.0, g.alongX() ? mid : a));

        // girders, walkway and the cab: one mesh for the whole span
        pose.pushPose();
        frame(pose, g, a, c0, ry);
        MachineMeshCache.draw(new Span(span), b -> buildGirders(b, span), pose, buffers, girderLit, overlay);
        pose.popPose();

        // the two end carriages: the second is the same part turned half a turn at the other rail
        for (int end = 0; end < 2; end++) {
            double rail = c0 + (end == 0 ? 0 : span);
            int lit = LevelRenderer.getLightColor(level, BlockPos.containing(g.alongX() ? a : rail, ry + 1.2, g.alongX() ? rail : a));
            pose.pushPose();
            frame(pose, g, a, c0, ry);
            if (end == 1) {
                pose.translate(0, 0, span);
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            part(pose, buffers, "end_carriage", lit, overlay);
            pose.popPose();
        }

        // the trolley, the falls of rope, the hook block
        pose.pushPose();
        frame(pose, g, a, c0, ry);
        pose.translate(0, 0, across);
        part(pose, buffers, "trolley", trolleyLit, overlay);
        double hookTop = tipLocal + HOOK_TOP;
        double fall = Math.max(0.05, ROPE_TOP - hookTop);
        for (double x : FALLS) {
            scaled(pose, x, hookTop, 1.0, fall, 1.0);
            part(pose, buffers, "rope", work, overlay);
            pose.popPose();
        }
        pose.translate(0, tipLocal, 0);
        part(pose, buffers, "hook_block", work, overlay);
        pose.popPose();

        // what hangs from the hook, drawn in world coordinates as it sat in its vessel: the head with its strongback, or the dryer or separators
        VesselProfile vessel = be.carriedProfile();
        if (!be.carriedTurbine().isEmpty()) {
            // the turbine hall: an LP hood on four slings to its lugs, or a blade section slung round its two journals
            int lit = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() - 2, tip.z())));
            pose.pushPose();
            pose.translate(tip.x(), tip.y(), tip.z());
            part(pose, buffers, "strongback_hook", lit, overlay);
            pose.popPose();
            dev.bwr.mod.power.client.TurbineHallRenderers.drawHanging(pose, buffers, be.carriedTurbine(), tip.x(), tip.y(), tip.z(), lit, overlay,
                    (x0, y0, z0, x1, y1, z1) -> slingBetween(pose, buffers, x0, y0, z0, x1, y1, z1, lit, overlay));
        } else if (be.carriedPlug() && be.plugPlan() != null) {
            hangPlugRig(pose, buffers, tip, be.plugPlan(), level, overlay);
        } else if (vessel != null && be.carriedHead()) {
            hangHead(pose, buffers, tip, vessel, level, overlay);
        } else if (vessel != null && be.carriedInternal() != null) {
            var internal = be.carriedInternal();
            double lift = internal == VesselInternal.DRYER ? vessel.dryerLift() : vessel.separatorLift();
            int lit = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() - 1, tip.z())));
            hangRig(pose, buffers, tip, vessel, internal == VesselInternal.DRYER ? vessel.dryerEyeRing() : vessel.separatorEyeRing(), lit, overlay);
            pose.pushPose();
            pose.translate(tip.x(), tip.y() - lift - BuildingCraneBlockEntity.RIG_M, tip.z());
            if (internal == VesselInternal.DRYER) {
                ReactorVesselRenderer.drawDryer(vessel, pose, buffers, lit, overlay);
            } else {
                ReactorVesselRenderer.drawSeparators(vessel, pose, buffers, lit, overlay);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * A shield plug section on its four-point rig: the hook's ring and one sling from its shackle to each of the four crane anchors on the section
     * (the section itself is the {@code PlugSectionEntity}, drawn under the hook by {@code PlugSectionRenderer}).
     */
    private static void hangPlugRig(PoseStack pose, MultiBufferSource buffers, Point tip, BuildingCraneBlockEntity.PlugPlan plan,
                                    net.minecraft.world.level.Level level, int overlay) {
        int lit = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() - 1, tip.z())));
        pose.pushPose();
        pose.translate(tip.x(), tip.y(), tip.z());
        part(pose, buffers, "strongback_hook", lit, overlay);
        pose.popPose();
        double baseY = tip.y() - plan.hookAboveBase();
        for (int i = 0; i + 2 < plan.anchors().length; i += 3) {
            double ax = tip.x() + plan.anchors()[i] + 0.5 - plan.px();
            double ay = baseY + plan.anchors()[i + 1] + 0.42;
            double az = tip.z() + plan.anchors()[i + 2] + 0.5 - plan.pz();
            slingBetween(pose, buffers, tip.x(), tip.y() - SHACKLE_DROP, tip.z(), ax, ay, az, lit, overlay);
        }
    }

    /** A sling between two points: the unit vertical rope turned and stretched to join them. */
    private static void slingBetween(PoseStack pose, MultiBufferSource buffers, double x0, double y0, double z0, double x1, double y1, double z1, int lit, int overlay) {
        // the unit rope runs up from its origin: start it at the lower end (the anchor) and turn it towards the shackle
        double dx = x0 - x1, dy = y0 - y1, dz = z0 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.02) {
            return;
        }
        pose.pushPose();
        pose.translate(x1, y1, z1);
        pose.mulPose(new org.joml.Quaternionf().rotationTo(0f, 1f, 0f, (float) (dx / length), (float) (dy / length), (float) (dz / length)));
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale(0.04f, (float) length, 0.04f);
        pose.last().normal().set(normal);
        part(pose, buffers, "sling", lit, overlay);
        pose.popPose();
    }

    /** The hook's ring, a spreader over the ring of the four lifting eyes and four slings down to them (the eyes are RIG_M under the hook). */
    private static void hangRig(PoseStack pose, MultiBufferSource buffers, Point tip, VesselProfile v, double ring, int lit, int overlay) {
        pose.pushPose();
        pose.translate(tip.x(), tip.y(), tip.z());
        part(pose, buffers, "strongback_hook", lit, overlay);
        double rx = ring, rz = ring * v.ey;
        scaled(pose, 0.0, 0.0, rx, 1.0, rz);
        part(pose, buffers, "spreader", lit, overlay);
        pose.popPose();
        pose.popPose();
        double top = -SHACKLE_DROP;
        double bottom = -BuildingCraneBlockEntity.RIG_M;
        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(45 + 90 * i);
            double length = top - bottom;
            if (length > 0.02) {
                scaled(pose, tip.x() + rx * Math.cos(ang), tip.y() + bottom, 0.04, length, 0.04, tip.z() + rz * Math.sin(ang));
                part(pose, buffers, "sling", lit, overlay);
                pose.popPose();
            }
        }
    }

    /** The head, hung on the strongback: the lifting ring on the hook, the spreader over the lug ring, four slings down to the lugs. */
    private static void hangHead(PoseStack pose, MultiBufferSource buffers, Point tip, VesselProfile v, net.minecraft.world.level.Level level, int overlay) {
        int lit = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() - v.headLift() * 0.5, tip.z())));
        pose.pushPose();
        pose.translate(tip.x(), tip.y(), tip.z());
        part(pose, buffers, "strongback_hook", lit, overlay);
        // the spreader is a unit over the radius of the lug ring (it is longer on a wider vessel)
        double rx = v.headLugRadius();
        double rz = v.headLugRadius() * v.ey;
        scaled(pose, 0.0, 0.0, rx, 1.0, rz);
        part(pose, buffers, "spreader", lit, overlay);
        pose.popPose();
        pose.popPose();
        // the slings from the spreader's shackles (0.74 under the hook) to the top of each lug
        double lug = Math.max(.35, .09 * Math.min(v.width, v.depth));
        double lugBase = v.headRise * Math.sqrt(1 - .55 * .55) - lug * .1;
        double attach = lugBase + .8 * lug;
        double top = -SHACKLE_DROP;
        double bottom = -v.headLift() + attach;
        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(45 + 90 * i);
            double sx = rx * Math.cos(ang), sz = rz * Math.sin(ang);
            double length = top - bottom;
            if (length > 0.02) {
                scaled(pose, tip.x() + sx, tip.y() + bottom, 0.045, length, 0.045, tip.z() + sz);
                part(pose, buffers, "sling", lit, overlay);
                pose.popPose();
            }
        }
        // the head itself: its mating face is headLift under the hook
        pose.pushPose();
        pose.translate(tip.x(), tip.y() - v.headLift(), tip.z());
        ReactorVesselRenderer.drawHead(v, pose, buffers, lit, overlay);
        pose.popPose();
    }

    /** One cached part at the current pose. */
    private static void part(PoseStack pose, MultiBufferSource buffers, String name, int light, int overlay) {
        MachineMeshCache.draw(new Part(name), b -> b.part(model(name), 0, 0, 0, 1, 1, 1), pose, buffers, light, overlay);
    }

    /** The crane's frame: origin on the first rail's centre line at the crane's position, X along the runway, Z across to the second rail. */
    private static void frame(PoseStack pose, BuildingCraneBlockEntity.Geom g, double a, double c0, double ry) {
        pose.translate(g.alongX() ? a : c0, ry, g.alongX() ? c0 : a);
        if (!g.alongX()) {
            // local +Z (across) points along world +X, local +X (along the runway) along world -Z
            pose.mulPose(Axis.YP.rotationDegrees(90));
        }
    }

    /** Pushes a pose at (x, y, 0) scaled by (sx, sy, sz), whose normal matrix stays the unscaled one (the cached shader does not renormalise). */
    private static void scaled(PoseStack pose, double x, double y, double sx, double sy, double sz) {
        pose.pushPose();
        pose.translate(x, y, 0);
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale((float) sx, (float) sy, (float) sz);
        pose.last().normal().set(normal);
    }

    /** As above at a place in all three axes. */
    private static void scaled(PoseStack pose, double x, double y, double sx, double sy, double sz, double z) {
        pose.pushPose();
        pose.translate(x, y, z);
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale((float) sx, (float) sy, (float) sz);
        pose.last().normal().set(normal);
    }

    private static void buildGirders(MachineMeshCache.Builder b, int span) {
        b.part(model("girder_plain"), 0, 0, 0, 1, 1, span);
        for (int k = 0; k < span; k++) {
            b.part(model("girder_tile"), 0, 0, k, 1, 1, 1);
        }
        b.part(model("cab"), 0, 0, 0, 1, 1, 1);
    }

    /** The crane carries its own work lights: never darker than a lit room, so the hook can be seen inside the dark well. */
    private static int work(int packed) {
        return LightTexture.pack(Math.max(LightTexture.block(packed), 11), Math.max(LightTexture.sky(packed), 6));
    }

    @Override
    public AABB getRenderBoundingBox(BuildingCraneBlockEntity be) {
        BuildingCraneBlockEntity.Geom g = be.geom();
        if (g == null) {
            return new AABB(be.getBlockPos());
        }
        double x0 = g.alongX() ? g.aLo() - 3 : g.cLow() - 3;
        double x1 = g.alongX() ? g.aHi() + 4 : g.cHigh() + 4;
        double z0 = g.alongX() ? g.cLow() - 3 : g.aLo() - 3;
        double z1 = g.alongX() ? g.cHigh() + 4 : g.aHi() + 4;
        return new AABB(x0, g.railY() - 96, z0, x1, g.railY() + 8, z1);
    }

    @Override
    public int getViewDistance() {
        return 160;
    }

    @Override
    public boolean shouldRenderOffScreen(BuildingCraneBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(BuildingCraneBlockEntity be, Vec3 eye) {
        return be.geom() != null && getRenderBoundingBox(be).distanceToSqr(eye) < (double) getViewDistance() * getViewDistance();
    }
}
