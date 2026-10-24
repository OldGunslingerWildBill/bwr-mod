package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.StudTensionerBlockEntity;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.refuelling.client.BuildingCraneRenderer;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the stud tensioner's tool on the stud it is working, on the closure flange of the vessel, with a hydraulic hose back to the
 * power pack. Only while a pass is under way: the tool sits on the head's flange over the nut (the studs go round the flange at
 * 0.472 of the vessel's width, the same ring the vessel model puts them on), turned so its drive faces outward, and moves on to the
 * next stud when the server says so. The nuts it has taken off are gone from the head (see {@code ReactorVesselRenderer}).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class StudTensionerRenderer implements BlockEntityRenderer<StudTensionerBlockEntity> {

    private record Tool(String name) {
    }

    private static final int HOSE_SEGMENTS = 14;

    public StudTensionerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.STUD_TENSIONER.get(), StudTensionerRenderer::new);
    }

    /** The tool's place on the flange now: x, y (the top of the nut), z, and the angle round the flange; null if the reactor is not there. */
    private static double[] toolAt(StudTensionerBlockEntity be, VesselProfile p, ReactorControllerBlockEntity r) {
        var e = r.clientVesselEnvelope();
        if (e == null || be.clientStudTotal() <= 0) {
            return null;
        }
        double cx = e.min().getX() + e.width() / 2.0, cz = e.min().getZ() + e.depth() / 2.0;
        double a = (be.clientStud() + .5) * Math.PI * 2 / be.clientStudTotal();
        double fl = VesselProfile.flangeHeight(p.headRise);
        return new double[]{cx + .472 * e.width() * Math.cos(a), e.min().getY() + p.flangeFace + fl + 1.9 * p.studRadius(), cz + .472 * e.depth() * Math.sin(a), a};
    }

    @Override
    public void render(StudTensionerBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var level = be.getLevel();
        VesselProfile p = be.clientProfile();
        if (level == null || p == null || !be.running()) {
            return;
        }
        if (!(level.getBlockEntity(be.clientReactor()) instanceof ReactorControllerBlockEntity r)) {
            return;
        }
        double[] at = toolAt(be, p, r);
        if (at == null) {
            return;
        }
        BlockPos root = be.getBlockPos();
        double rs = p.studRadius();
        // the tool works under its own lamps
        int lit = LevelRenderer.getLightColor(level, BlockPos.containing(at[0], at[1] + 1, at[2]));
        lit = net.minecraft.client.renderer.LightTexture.pack(Math.max(net.minecraft.client.renderer.LightTexture.block(lit), 11),
                Math.max(net.minecraft.client.renderer.LightTexture.sky(lit), 6));
        pose.pushPose();
        pose.translate(at[0] - root.getX(), at[1] - root.getY(), at[2] - root.getZ());
        pose.mulPose(Axis.YP.rotation((float) -at[3]));
        // a stroke of the puller while the stud is in hand
        double stroke = Math.sin((level.getGameTime() + partial) * 0.25) * 0.15 * rs;
        pose.translate(0, stroke, 0);
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale((float) rs, (float) rs, (float) rs);
        pose.last().normal().set(normal);
        MachineMeshCache.draw(new Tool("tensioner_tool"), b -> b.part(BuildingCraneRenderer.model("tensioner_tool"), 0, 0, 0, 1, 1, 1), pose, buffers, lit, overlay);
        pose.popPose();
        // the hose: from the power pack's top, sagging, to the tool's fitting (4.1 radii out and 1.8 up on the tool, which faces outward)
        double fx = at[0] + Math.cos(at[3]) * 4.1 * rs, fz = at[2] + Math.sin(at[3]) * 4.1 * rs, fy = at[1] + 1.8 * rs;
        Vec3 from = new Vec3(root.getX() + .5, root.getY() + 12.0 / 16.0, root.getZ() + .5);
        Vec3 to = new Vec3(fx, fy, fz);
        hose(pose, buffers, from, to, root, lit, overlay);
    }

    private static void hose(PoseStack pose, MultiBufferSource buffers, Vec3 from, Vec3 to, BlockPos root, int light, int overlay) {
        double sag = Math.min(3.0, 0.25 * from.distanceTo(to) + 0.3);
        Vec3 prev = from;
        for (int i = 1; i <= HOSE_SEGMENTS; i++) {
            double t = i / (double) HOSE_SEGMENTS;
            Vec3 next = from.lerp(to, t).add(0, -sag * 4 * t * (1 - t) + 0.4 * 4 * t * (1 - t), 0);
            Vec3 d = next.subtract(prev);
            double len = d.length();
            if (len > 1e-4) {
                pose.pushPose();
                pose.translate(prev.x - root.getX(), prev.y - root.getY(), prev.z - root.getZ());
                pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) d.x, (float) d.y, (float) d.z).normalize()));
                Matrix3f normal = new Matrix3f(pose.last().normal());
                pose.scale(0.06f, (float) len, 0.06f);
                pose.last().normal().set(normal);
                MachineMeshCache.draw(new Tool("sling"), b -> b.part(BuildingCraneRenderer.model("sling"), 0, 0, 0, 1, 1, 1), pose, buffers, light, overlay);
                pose.popPose();
            }
            prev = next;
        }
    }

    @Override
    public AABB getRenderBoundingBox(StudTensionerBlockEntity be) {
        VesselProfile p = be.clientProfile();
        var pos = be.getBlockPos();
        if (p == null || !be.running()) {
            return new AABB(pos);
        }
        var r = be.clientReactor();
        double reach = Math.max(p.width, p.depth) / 2.0 + StudTensionerBlockEntity.REACH + 2;
        return new AABB(Math.min(pos.getX(), r.getX()) - reach, pos.getY() - 4 - p.height, Math.min(pos.getZ(), r.getZ()) - reach,
                Math.max(pos.getX(), r.getX()) + reach, pos.getY() + 4, Math.max(pos.getZ(), r.getZ()) + reach);
    }

    @Override
    public boolean shouldRenderOffScreen(StudTensionerBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRender(StudTensionerBlockEntity be, Vec3 eye) {
        return be.running() && getRenderBoundingBox(be).distanceToSqr(eye) < 128.0 * 128.0;
    }
}
