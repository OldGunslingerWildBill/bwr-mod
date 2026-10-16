package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.mimic.MimicDraft;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicStyle;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The live preview of the mimic tape, as the linker's outline is a preview of the wiring: while the tape is held, the
 * path being drawn (its points and lines, in the colour a new path gets), a ghost segment from its last point to the
 * grid point the next click will take, and the marker for that point. In erase mode the path under the cursor is
 * outlined red, in edit mode cyan. It reads the unfinished path from the stack in hand, which the server keeps, and
 * snaps with {@link MimicOps#target}, the code the server runs, so the preview shows what a click will do.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class MimicPreview {
    private MimicPreview() {
    }

    private static final float LIFT = 0.45f;
    private static final MimicMesher MESHER = new MimicMesher();
    private static final PreviewSink SINK = new PreviewSink();
    /** What the last frame drew, for checks: the number of preview quads. */
    private static int debugQuads;

    public static int debugQuads() {
        return debugQuads;
    }

    /** Quads that map to the face of a panel block, translucent, in camera-relative world space. */
    private static final class PreviewSink implements QuadSink {
        VertexConsumer out;
        PoseStack.Pose pose;
        PanelGeometry.Frame frame;
        double ox;
        double oy;
        double oz;
        float alpha;

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            corner(x0, y0, z0, rgb);
            corner(x1, y1, z1, rgb);
            corner(x2, y2, z2, rgb);
            corner(x3, y3, z3, rgb);
            debugQuads++;
        }

        private void corner(float u, float v, float z, int rgb) {
            float lifted = z + LIFT;
            out.addVertex(pose, (float) (ox + frame.x(u, v, lifted)), (float) (oy + frame.y(u, v, lifted)),
                    (float) (oz + frame.z(u, v, lifted)))
                    .setColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (int) (alpha * 255.0f));
        }

        void target(PanelBlockEntity block, Vec3 camera, VertexConsumer out, PoseStack.Pose pose, float alpha) {
            BlockPos pos = block.getBlockPos();
            this.frame = PanelGeometry.frame(block.panelType(), block.facing());
            this.ox = pos.getX() - camera.x;
            this.oy = pos.getY() - camera.y;
            this.oz = pos.getZ() - camera.z;
            this.out = out;
            this.pose = pose;
            this.alpha = alpha;
        }
    }

    /** The vanilla block outline of a panel is replaced by the preview while the tape is held. */
    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!MimicClientInput.tape(minecraft).isEmpty() && MimicClientInput.aim(minecraft) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack tape = MimicClientInput.tape(minecraft);
        if (tape.isEmpty() || minecraft.level == null) {
            return;
        }
        MimicMode mode = MimicTapeItem.mode(tape);
        MimicDraft draft = MimicTapeItem.draft(tape);
        MimicClientInput.Aim aim = MimicClientInput.aim(minecraft);
        // Copy what the shared aim holds: the helpers below may look again.
        PanelBlockEntity aimed = aim == null ? null : aim.block;
        double cu = aim == null ? 0.0 : aim.cu;
        double cv = aim == null ? 0.0 : aim.cv;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.debugQuads());
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        debugQuads = 0;
        boolean drew = false;

        if (mode == MimicMode.DRAW) {
            PanelBlockEntity anchor = draft != null && minecraft.level.getBlockEntity(draft.anchor()) instanceof PanelBlockEntity a
                    ? a : null;
            PanelGroup group = aimed == null ? null : aimed.group();
            boolean sameBoard = anchor != null && group != null && group.contains(draft.anchor());
            if (sameBoard) {
                drew |= drawDraft(anchor, draft, camera, out, pose);
            }
            if (aimed != null && group != null) {
                MimicDraft effective = sameBoard ? draft : null;
                int[] point = MimicOps.target(group, aimed.getBlockPos(), effective, cu, cv, minecraft.player.isShiftKeyDown());
                PanelBlockEntity frameBlock = effective != null ? anchor : aimed;
                drew |= drawGhost(frameBlock, effective, point, camera, out, pose);
            }
        } else if (aimed != null) {
            MimicPath path = MimicOps.pick(aimed, cu, cv);
            if (path != null) {
                drew |= highlightPath(aimed, path, mode == MimicMode.ERASE ? 0xFF4040 : 0x40D0FF, camera, out, pose);
            }
        }
        if (drew) {
            buffers.endBatch(RenderType.debugQuads());
        }
    }

    private static float aspect(PanelBlockEntity block) {
        return (float) PanelGeometry.frame(block.panelType(), block.facing()).vLength;
    }

    /** The points and lines so far, in the colour a new path gets. */
    private static boolean drawDraft(PanelBlockEntity anchor, MimicDraft draft, Vec3 camera, VertexConsumer out,
                                     PoseStack.Pose pose) {
        float aspect = aspect(anchor);
        SINK.target(anchor, camera, out, pose, 0.85f);
        MESHER.begin(SINK, aspect, false);
        int[] points = draft.toArray();
        MimicStyle style = new MimicStyle();
        if (points.length >= 4) {
            PathGeometry geometry = new PathGeometry(points, aspect);
            MESHER.paint(style.color, 0.0f);
            MESHER.body(geometry, style);
        }
        MESHER.paint(0xFFFFFF, 0.1f);
        for (int i = 0; i + 1 < points.length; i += 2) {
            MESHER.circle(points[i], points[i + 1] * aspect, 1.0f);
        }
        return true;
    }

    /** The marker at the grid point a click will take, and a ghost segment to it from the last point. */
    private static boolean drawGhost(PanelBlockEntity frameBlock, MimicDraft draft, int[] point, Vec3 camera,
                                     VertexConsumer out, PoseStack.Pose pose) {
        float aspect = aspect(frameBlock);
        SINK.target(frameBlock, camera, out, pose, 0.6f);
        MESHER.begin(SINK, aspect, false);
        if (draft != null) {
            int lastX = draft.x(draft.pointCount() - 1);
            int lastY = draft.y(draft.pointCount() - 1);
            if (lastX != point[0] || lastY != point[1]) {
                PathGeometry geometry = new PathGeometry(new int[] {lastX, lastY, point[0], point[1]}, aspect);
                MimicStyle ghost = new MimicStyle();
                ghost.dashed = true;
                ghost.endEnd = MimicStyle.END_NONE;
                ghost.width = 1;
                MESHER.paint(0xFFFFFF, 0.0f);
                MESHER.body(geometry, ghost);
            }
        }
        MESHER.paint(0xFFFF60, 0.2f);
        MESHER.circle(point[0], point[1] * aspect, 1.2f);
        return true;
    }

    /** An outline around the path in the aimed block, a little wider than the path itself. */
    private static boolean highlightPath(PanelBlockEntity block, MimicPath path, int rgb, Vec3 camera, VertexConsumer out,
                                         PoseStack.Pose pose) {
        float aspect = aspect(block);
        SINK.target(block, camera, out, pose, 0.55f);
        MESHER.begin(SINK, aspect, false);
        MimicStyle style = path.style().copy();
        style.width = Math.min(MimicStyle.MAX_WIDTH + 2, style.width + 2);
        style.dashed = false;
        style.flowEvery = 0;
        PathGeometry geometry = new PathGeometry(path, aspect);
        MESHER.paint(rgb, 0.0f);
        MESHER.body(geometry, style);
        return true;
    }
}
