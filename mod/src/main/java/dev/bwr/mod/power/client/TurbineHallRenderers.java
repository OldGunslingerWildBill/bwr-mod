package dev.bwr.mod.power.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.TurbineLoadItem;
import dev.bwr.mod.power.TurbineStandBlock;
import dev.bwr.mod.power.TurbineStandBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The turbine hall's drawings (TURBINE-MAINTENANCE.md): an LP blade section (procedural: the drum, three stages of discs and blades,
 * the last stage longest; a damaged one rust-dark with blades missing), an LP hood (the LP's own upper cells, as its baked models), the
 * open casing with its sections in it, the two stands with what they hold, and the loads hanging from the crane's hook. Every mesh is a
 * cached vertex buffer ({@link MachineMeshCache}); nothing is rebuilt per frame.
 *
 * <h2>Wrecks (TURBINE-MAINTENANCE.md)</h2>
 * A wrecked casing is drawn from the Blender parts of art/models/power_turbines/build_wreck.py, in the module's own coordinates: the
 * holes thrown blades tore (a petal of plate at the point {@link dev.bwr.mod.power.TurbineMissile#casingExit} gives), an LP's hood
 * burst open with its stripped, bent rotor showing (the hood cells are open), the end a rotor half broke out of, and an HP's torn upper
 * half with its bent rotor. A shaft wreck on the floor is a stripped, bent section lying along its axis with its sheared coupling.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineHallRenderers {
    private TurbineHallRenderers() {
    }

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("block/white_concrete");
    private static final float[] DRUM = {0.42f, 0.44f, 0.47f}, DISC = {0.55f, 0.57f, 0.60f}, BLADE = {0.78f, 0.80f, 0.83f},
            SHIELD = {0.90f, 0.86f, 0.62f}, RUST = {0.45f, 0.30f, 0.20f}, SADDLE = {0.85f, 0.65f, 0.10f}, FRAME = {0.30f, 0.32f, 0.35f};
    /**
     * Stage axial positions (from the section's middle, the last stage at +), disc radius, blade tip radius, blade count. A section sits
     * 1.5 from the LP's middle, so its last-stage wheel is 2.5 from it, where {@link dev.bwr.mod.power.TurbineMissile#wheel} throws from;
     * the LP model's inner casing (art/models/power_turbines/build_models.py) clears the first two stages, the last runs in the open hood.
     */
    private static final double[][] STAGES = {{-0.95, 0.85, 1.55, 28}, {0.05, 0.85, 1.90, 24}, {1.00, 0.85, 2.30, 20}};
    private static final double HALF_LENGTH = 1.5, DRUM_R = 0.55;

    /** How a blade section looks: new or lightly worn, damaged (rust-dark, a few blades gone), or stripped and bent (a wreck). */
    public enum Look { GOOD, DAMAGED, STRIPPED }

    private record SectionKey(Look look) {
    }

    private record PartKey(String part) {
    }

    /** The wrecked-turbine parts (tools-export-turbine-wreck.py). */
    private static final String[] PARTS = {"lp_hood_burst", "lp_end_blown_a", "lp_end_blown_b", "tear_petal", "hp_upper_burst"};

    private static net.minecraft.client.resources.model.ModelResourceLocation part(String name) {
        return net.minecraft.client.resources.model.ModelResourceLocation.standalone(BwrMod.id("block/turbine_wreck/" + name + "/body"));
    }

    @SubscribeEvent
    public static void models(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        for (String p : PARTS) {
            event.register(part(p));
        }
    }

    private static void drawPart(String name, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        MachineMeshCache.draw(new PartKey(name), b -> b.model(part(name)), pose, buffers, light, overlay);
    }

    /**
     * Turns the pose from a module's controller block corner into the module's own model coordinates (build_models.py: x right, y up,
     * z along the shaft, the controller cell at {@code (cx, 0, cz)}), as its baked cells are turned for its facing.
     */
    private static void moduleFrame(PoseStack pose, Direction facing, int cx, int cz) {
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
        pose.translate(-cx - 0.5, 0, -cz - 0.5);
    }

    private record HoodKey(Direction facing) {
    }

    private record SaddleKey(int n) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.POWER_MODULE.get(), OpenCasing::new);
        event.registerBlockEntityRenderer(BwrBlockEntities.TURBINE_STAND.get(), Stand::new);
        event.registerBlockEntityRenderer(BwrBlockEntities.TURBINE_SHAFT_WRECK.get(), Wreck::new);
    }

    private static TextureAtlasSprite sprite() {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(TEXTURE);
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** A box from (x0,y0,z0) to (x1,y1,z1) in the builder's pose. */
    private static void box(MachineMeshCache.Builder b, TextureAtlasSprite s, float[] c, double x0, double y0, double z0, double x1, double y1, double z1) {
        float u0 = s.getU0(), u1 = s.getU1(), v0 = s.getV0(), v1 = s.getV1();
        // +Y, -Y, +X, -X, +Z, -Z, counter-clockwise from outside
        q(b, c, u0, v0, u1, v1, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        q(b, c, u0, v0, u1, v1, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        q(b, c, u0, v0, u1, v1, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        q(b, c, u0, v0, u1, v1, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        q(b, c, u0, v0, u1, v1, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        q(b, c, u0, v0, u1, v1, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
    }

    private static void q(MachineMeshCache.Builder b, float[] c, float u0, float v0, float u1, float v1, float nx, float ny, float nz,
                          double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double dx, double dy, double dz) {
        b.vertex(ax, ay, az, u0, v0, nx, ny, nz, c[0], c[1], c[2]);
        b.vertex(bx, by, bz, u0, v1, nx, ny, nz, c[0], c[1], c[2]);
        b.vertex(cx, cy, cz, u1, v1, nx, ny, nz, c[0], c[1], c[2]);
        b.vertex(dx, dy, dz, u1, v0, nx, ny, nz, c[0], c[1], c[2]);
    }

    /** A cylinder along z from z0 to z1, radius r, n sides, with end caps. */
    private static void cylinder(MachineMeshCache.Builder b, TextureAtlasSprite s, float[] c, double r, double z0, double z1, int n) {
        float u0 = s.getU0(), u1 = s.getU1(), v0 = s.getV0(), v1 = s.getV1();
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n, am = (a0 + a1) / 2;
            double x0 = r * Math.cos(a0), y0 = r * Math.sin(a0), x1 = r * Math.cos(a1), y1 = r * Math.sin(a1);
            q(b, c, u0, v0, u1, v1, (float) Math.cos(am), (float) Math.sin(am), 0, x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1);
            q(b, c, u0, v0, u1, v1, 0, 0, 1, 0, 0, z1, x0, y0, z1, x1, y1, z1, 0, 0, z1);
            q(b, c, u0, v0, u1, v1, 0, 0, -1, 0, 0, z0, 0, 0, z0, x1, y1, z0, x0, y0, z0);
        }
    }

    /**
     * One LP blade section, its axis along +z through the origin, 3 m long; the last (longest) stage towards +z. Blades are thin boxes
     * set radially, twisted a little; their tips carry the erosion shield (a pale band). A damaged section is rust-dark and every
     * fifth blade of its last stage is missing or broken short. A stripped one (a wrecked rotor) is bent at its middle, its last stage
     * all but gone and the rest broken short or missing.
     */
    static void buildSection(MachineMeshCache.Builder b, Look look) {
        var s = sprite();
        var pose = b.pose;
        boolean damaged = look != Look.GOOD, stripped = look == Look.STRIPPED;
        if (stripped) {
            // the bent shaft: each half of the drum and journal turned a few degrees about its middle
            for (int half : new int[]{-1, 1}) {
                pose.pushPose();
                pose.mulPose(Axis.XP.rotationDegrees(half * 3.5f));
                cylinder(b, s, RUST, DRUM_R, half < 0 ? -HALF_LENGTH : 0, half < 0 ? 0 : HALF_LENGTH, 20);
                cylinder(b, s, DRUM, 0.32, half < 0 ? -HALF_LENGTH - 0.45 : 0, half < 0 ? 0 : HALF_LENGTH + 0.45, 12);
                pose.popPose();
            }
        } else {
            cylinder(b, s, damaged ? RUST : DRUM, DRUM_R, -HALF_LENGTH, HALF_LENGTH, 20);
            cylinder(b, s, DRUM, 0.32, -HALF_LENGTH - 0.45, HALF_LENGTH + 0.45, 12); // the journals the slings go round
        }
        for (double[] st : STAGES) {
            double z = st[0], disc = st[1], tip = st[2];
            int n = (int) st[3];
            boolean last = st == STAGES[STAGES.length - 1];
            pose.pushPose();
            if (stripped) {
                pose.mulPose(Axis.XP.rotationDegrees(z < 0 ? -3.5f : 3.5f));
            }
            pose.translate(0, 0, z);
            cylinder(b, s, damaged ? RUST : DISC, stripped && last ? disc * 0.8 : disc, -0.12, 0.12, 24);
            for (int i = 0; i < n; i++) {
                double length = tip - disc;
                if (stripped) {
                    if (last ? i % 4 != 0 : i % 3 == 0) {
                        continue; // gone
                    }
                    length *= last ? 0.18 : 0.35 + 0.1 * (i % 3); // broken short
                } else if (damaged && last && i % 5 == 0) {
                    if (i % 10 == 0) {
                        continue; // gone
                    }
                    length *= 0.45; // broken short
                }
                pose.pushPose();
                pose.mulPose(Axis.ZP.rotation((float) (2 * Math.PI * i / n)));
                pose.translate(0, disc - 0.02, 0);
                pose.mulPose(Axis.YP.rotationDegrees(28));
                double w = 0.09 + 0.04 * (tip - 1.5), t = 0.025;
                box(b, s, damaged ? RUST : BLADE, -t, 0, -w, t, length * 0.82, w);
                box(b, s, damaged ? RUST : SHIELD, -t * 1.1, length * 0.82, -w * 0.9, t * 1.1, length, w * 0.9);
                pose.popPose();
            }
            pose.popPose();
        }
    }

    /** Draws a blade section whose axis runs along {@code axis} through the pose's origin; flow B (the other end) turned half a turn. */
    public static void drawSection(PoseStack pose, MultiBufferSource buffers, Direction axis, boolean flipped, boolean damaged, int light, int overlay) {
        drawSection(pose, buffers, axis, flipped, damaged ? Look.DAMAGED : Look.GOOD, light, overlay);
    }

    public static void drawSection(PoseStack pose, MultiBufferSource buffers, Direction axis, boolean flipped, Look look, int light, int overlay) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-axis.toYRot() + (flipped ? 180 : 0)));
        MachineMeshCache.draw(new SectionKey(look), b -> buildSection(b, look), pose, buffers, light, overlay);
        pose.popPose();
    }

    /** The hood of an LP facing {@code facing}: its own upper cells as baked, the origin at the LP's controller block's corner. */
    static void buildHood(MachineMeshCache.Builder b, Direction facing) {
        var block = (PowerModuleBlock) BwrBlocks.LP_TURBINE.get();
        var base = block.placementState().setValue(PumpAssemblyBlock.FACING, facing).setValue(PowerModuleBlock.OPEN, false);
        for (int i = 0; i < block.cellCount(); i++) {
            var s = base.setValue(PumpAssemblyBlock.CELL, i);
            if (!block.hoodCell(s)) {
                continue;
            }
            BlockPos o = TurbineAssemblyBlock.turn(block.cellOffset(s, i), facing);
            b.pose.pushPose();
            b.pose.translate(o.getX(), o.getY(), o.getZ());
            b.model(BlockModelShaper.stateToModelLocation(s));
            b.pose.popPose();
        }
    }

    /** Draws an LP hood with the middle of its bottom (the horizontal joint) at the pose's origin. */
    public static void drawHood(PoseStack pose, MultiBufferSource buffers, Direction facing, int light, int overlay) {
        pose.pushPose();
        pose.translate(-0.5, -PowerModuleBlock.HOOD_FROM_Y, -0.5);
        MachineMeshCache.draw(new HoodKey(facing), b -> buildHood(b, facing), pose, buffers, light, overlay);
        pose.popPose();
    }

    /**
     * A hood load, whole or wrecked, with the middle of its bottom at the pose's origin: a burst one torn open, a holed one with the
     * petals of the holes blades tore through it.
     */
    public static void drawHoodItem(PoseStack pose, MultiBufferSource buffers, ItemStack hood, Direction facing, int light, int overlay) {
        if (TurbineLoadItem.hoodBurst(hood)) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
            pose.translate(-3.5, -PowerModuleBlock.HOOD_FROM_Y, -3.5);
            drawPart("lp_hood_burst", pose, buffers, light, overlay);
            pose.popPose();
            return;
        }
        drawHood(pose, buffers, facing, light, overlay);
        double[] holes = TurbineLoadItem.hoodHoles(hood);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
        pose.translate(-3.5, -PowerModuleBlock.HOOD_FROM_Y, -3.5);
        for (int flow = 0; flow < holes.length; flow++) {
            petal(pose, buffers, holes[flow], flow, true, light, overlay);
        }
        pose.popPose();
    }

    /** The petal of a hole a blade tore at release angle {@code angle} out of flow {@code flow}'s wheel, in LP model coordinates; only those in the hood, or only those below it. */
    private static void petal(PoseStack pose, MultiBufferSource buffers, double angle, int flow, boolean hood, int light, int overlay) {
        if (!Double.isFinite(angle)) {
            return;
        }
        var e = dev.bwr.mod.power.TurbineMissile.casingExit(angle);
        if (e == null || e.hood() != hood) {
            return;
        }
        pose.pushPose();
        pose.translate(3.5 + e.u(), e.v(), flow == 0 ? 1.0 : 6.0);
        pose.mulPose(Axis.ZP.rotation((float) Math.atan2(e.nv(), e.nu())));
        pose.mulPose(Axis.YP.rotationDegrees(90));
        drawPart("tear_petal", pose, buffers, light, overlay);
        pose.popPose();
    }

    /** A crane load (a hood or a blade section) hung from the hook at (x, y, z) world, with its slings. */
    public static void drawHanging(PoseStack pose, MultiBufferSource buffers, ItemStack load, double x, double y, double z, int light, int overlay,
                                   SlingDrawer slings) {
        if (TurbineLoadItem.isHood(load)) {
            Direction f = TurbineLoadItem.hoodFacing(load, Direction.NORTH);
            double bottom = y - 2.0 - 1.3;
            pose.pushPose();
            pose.translate(x, bottom, z);
            drawHoodItem(pose, buffers, load, f, light, overlay);
            pose.popPose();
            Direction side = f.getClockWise();
            for (int i = 0; i < 4; i++) {
                double a = (i & 1) == 0 ? 2.2 : -2.2, c = (i & 2) == 0 ? 2.2 : -2.2;
                slings.sling(x, y - 0.74, z, x + f.getStepX() * a + side.getStepX() * c, bottom + 2.0, z + f.getStepZ() * a + side.getStepZ() * c);
            }
        } else if (TurbineLoadItem.isBladeSection(load)) {
            double axis = y - 3.6;
            pose.pushPose();
            pose.translate(x, axis, z);
            drawSection(pose, buffers, Direction.NORTH, false, !TurbineLoadItem.isGoodSection(load), light, overlay);
            pose.popPose();
            for (double end : new double[]{-1.85, 1.85}) {
                slings.sling(x, y - 0.74, z, x, axis + 0.32, z + end);
            }
        }
    }

    /** Draws one sling between two world points (the crane renderer's own). */
    @FunctionalInterface
    public interface SlingDrawer {
        void sling(double x0, double y0, double z0, double x1, double y1, double z1);
    }

    // ------------------------------------------------------------------------------------------------ the open casing

    /**
     * Draws what a turbine section's baked cells do not: the blade sections in an LP with its cover off or burst open, and a wrecked
     * casing's parts (the holes blades tore, a burst hood, the end a rotor half broke out of, an HP's torn upper half). Nothing at all
     * for a sound, closed section or a generator.
     */
    public static final class OpenCasing implements BlockEntityRenderer<PowerModuleBlockEntity> {
        public OpenCasing(BlockEntityRendererProvider.Context context) {
        }

        @Override
        public void render(PowerModuleBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            int wreck = be.wreck();
            if (!be.coverOff() && wreck == PowerModuleBlockEntity.WRECK_NONE) {
                return;
            }
            Direction f = be.getBlockState().getValue(PumpAssemblyBlock.FACING);
            int lit = net.minecraft.client.renderer.LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above(4));
            if (!be.lp()) {
                if (be.wrecked()) {
                    pose.pushPose();
                    moduleFrame(pose, f, 2, 4);
                    drawPart("hp_upper_burst", pose, buffers, lit, overlay);
                    pose.popPose();
                }
                return;
            }
            if (be.coverOff() || be.wrecked()) {
                for (int flow = 0; flow < 2; flow++) {
                    if (!be.bladeIn(flow)) {
                        continue;
                    }
                    double s = flow == 0 ? 1.5 : -1.5;
                    Look look = be.wrecked() ? Look.STRIPPED : be.bladeThrown(flow) || be.blade(flow) >= 0.5 ? Look.DAMAGED : Look.GOOD;
                    pose.pushPose();
                    pose.translate(0.5 + f.getStepX() * s, 2.5, 0.5 + f.getStepZ() * s);
                    drawSection(pose, buffers, f, flow == 1, look, lit, overlay);
                    pose.popPose();
                }
            }
            if (wreck == PowerModuleBlockEntity.WRECK_NONE) {
                return;
            }
            pose.pushPose();
            moduleFrame(pose, f, 3, 3);
            if (be.wrecked() && !be.coverOff()) {
                drawPart("lp_hood_burst", pose, buffers, lit, overlay);
            }
            if (be.sheared()) {
                drawPart(be.brokenFlow() == 0 ? "lp_end_blown_a" : "lp_end_blown_b", pose, buffers, lit, overlay);
            }
            for (int flow = 0; flow < 2; flow++) {
                petal(pose, buffers, be.hole(flow), flow, false, lit, overlay);
                if (!be.coverOff() && !be.wrecked()) {
                    petal(pose, buffers, be.hole(flow), flow, true, lit, overlay);
                }
            }
            pose.popPose();
        }

        @Override
        public AABB getRenderBoundingBox(PowerModuleBlockEntity be) {
            BlockPos p = be.getBlockPos();
            return new AABB(p.getX() - 5, p.getY(), p.getZ() - 5, p.getX() + 6, p.getY() + 6, p.getZ() + 6);
        }

        @Override
        public boolean shouldRenderOffScreen(PowerModuleBlockEntity be) {
            return be.coverOff() || be.wreck() != PowerModuleBlockEntity.WRECK_NONE;
        }
    }

    // ------------------------------------------------------------------------------------------------ a shaft wreck on the floor

    /** The rotor half a shaft excursion left on the floor: a stripped, bent section lying on its discs along its axis, and its sheared coupling stub. */
    public static final class Wreck implements BlockEntityRenderer<dev.bwr.mod.power.TurbineWreckBlockEntity> {
        public Wreck(BlockEntityRendererProvider.Context context) {
        }

        @Override
        public void render(dev.bwr.mod.power.TurbineWreckBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            Direction along = be.axis() == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            int lit = net.minecraft.client.renderer.LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above());
            pose.pushPose();
            pose.translate(0.5, 0.82, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(-along.toYRot()));
            pose.mulPose(Axis.ZP.rotationDegrees(14)); // rolled over onto its stubs
            pose.mulPose(Axis.XP.rotationDegrees(2.5f)); // and lying a little nose-down
            pose.mulPose(Axis.YP.rotationDegrees(along.toYRot()));
            drawSection(pose, buffers, along, be.flow() == 1, Look.STRIPPED, lit, overlay);
            pose.popPose();
        }

        @Override
        public AABB getRenderBoundingBox(dev.bwr.mod.power.TurbineWreckBlockEntity be) {
            BlockPos p = be.getBlockPos();
            return new AABB(p.getX() - 3, p.getY() - 1, p.getZ() - 3, p.getX() + 4, p.getY() + 3, p.getZ() + 4);
        }
    }

    // ------------------------------------------------------------------------------------------------ the stands

    /** A blade stand's two saddles (with what lies on them) or a casing cradle's hood. */
    public static final class Stand implements BlockEntityRenderer<TurbineStandBlockEntity> {
        public Stand(BlockEntityRendererProvider.Context context) {
        }

        @Override
        public void render(TurbineStandBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            Direction f = be.facing();
            if (be.kind() == TurbineStandBlock.Kind.CASING) {
                ItemStack hood = be.items().getStackInSlot(0);
                if (!hood.isEmpty()) {
                    pose.pushPose();
                    pose.translate(0.5, 0.5, 0.5);
                    drawHoodItem(pose, buffers, hood, TurbineLoadItem.hoodFacing(hood, f), light, overlay);
                    pose.popPose();
                }
                return;
            }
            for (int slot = 0; slot < 2; slot++) {
                double[] xz = be.slotXZ(slot);
                double dx = xz[0] - be.getBlockPos().getX(), dz = xz[1] - be.getBlockPos().getZ();
                pose.pushPose();
                pose.translate(dx, 0, dz);
                pose.mulPose(Axis.YP.rotationDegrees(-f.toYRot() + 180));
                MachineMeshCache.draw(new SaddleKey(0), TurbineHallRenderers::buildSaddles, pose, buffers, light, overlay);
                pose.popPose();
                ItemStack s = be.items().getStackInSlot(slot);
                if (!s.isEmpty()) {
                    pose.pushPose();
                    pose.translate(dx, 2.7, dz);
                    drawSection(pose, buffers, f, false, !TurbineLoadItem.isGoodSection(s), light, overlay);
                    pose.popPose();
                }
            }
        }

        @Override
        public AABB getRenderBoundingBox(TurbineStandBlockEntity be) {
            BlockPos p = be.getBlockPos();
            return new AABB(p.getX() - 5, p.getY(), p.getZ() - 5, p.getX() + 6, p.getY() + 6, p.getZ() + 6);
        }

        @Override
        public boolean shouldRenderOffScreen(TurbineStandBlockEntity be) {
            return true;
        }
    }

    /** One saddle of a blade stand, along +z: two yellow V-cradles under the journals on a dark base frame. */
    static void buildSaddles(MachineMeshCache.Builder b) {
        var s = sprite();
        b.pose.pushPose();
        b.pose.translate(-0.5 + 0.5, 0, -0.5 + 0.5);
        box(b, s, FRAME, -0.6, 0, -2.1, 0.6, 0.2, 2.1);
        for (double z : new double[]{-1.95, 1.95}) {
            box(b, s, FRAME, -0.15, 0.2, z - 0.15, 0.15, 2.0, z + 0.15);
            box(b, s, SADDLE, -0.45, 2.0, z - 0.2, 0.45, 2.38, z + 0.2);
        }
        b.pose.popPose();
    }
}
