package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.NetworkingWrenchItem;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PanelNetworkIndex;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The Networking Wrench's lines, drawn through walls while it is held. Everything comes from data the client already
 * has (the loaded panels' full state and the client block entities); nothing is asked of the server per frame.
 *
 * <ol>
 *   <li>A signal-providing device under the crosshair is outlined white (linkable); the selected device is yellow.</li>
 *   <li>Hovering a bound component draws a line from its face centre to each device it reads (cyan) or commands
 *       (orange). The existing outline of those devices ({@link LinkerHighlight}) is drawn as well.</li>
 *   <li>Hovering a device draws a line to every component whose line link names it, from the reverse index in
 *       {@link PanelNetworkIndex}, at most {@link PanelNetworkIndex#MAX_LINES}.</li>
 * </ol>
 * Only line links (plant sources and sinks) get lines; a binding that is not a line link gets none.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class NetworkLines {
    private NetworkLines() {
    }

    private static long debugLines;
    private static long debugGlows;

    /** Lines drawn since the game started, for the opt-in client check. */
    public static long debugLines() { return debugLines; }

    /** Outlines (linkable or selected) drawn since the game started, for the opt-in client check. */
    public static long debugGlows() { return debugGlows; }

    static final float[] READ = {0.3f, 0.9f, 1.0f};
    static final float[] COMMAND = {1.0f, 0.6f, 0.2f};
    private static final float[] WHITE = {1.0f, 1.0f, 1.0f};
    private static final float[] YELLOW = {1.0f, 0.85f, 0.1f};

    /** Translucent lines for other players' shared lines, a little fainter than your own. */
    private static final class Faint extends RenderType {
        private Faint() {
            super("bwr_network_faint", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false,
                    false, () -> {
                    }, () -> {
                    });
        }

        static final RenderType THROUGH_WALLS = create("bwr_network_faint", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, 256, false, true, RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(2.0)))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<NetworkShareClient.Shared> shared = NetworkShareClient.visible(minecraft);
        boolean holding = NetworkHover.holdingWrench(minecraft);
        if (!holding && shared.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        if (holding) {
            VertexConsumer lines = buffers.getBuffer(LinkerHighlight.Types.THROUGH_WALLS);
            drawOwn(minecraft, lines, pose, camera);
            buffers.endBatch(LinkerHighlight.Types.THROUGH_WALLS);
        }
        if (!shared.isEmpty()) {
            VertexConsumer faint = buffers.getBuffer(Faint.THROUGH_WALLS);
            for (NetworkShareClient.Shared other : shared) {
                drawTarget(minecraft.level, other.hover(), faint, pose, camera, other.tint(), 0.55f,
                        PanelConfig.linkRange(), minecraft.player.position());
            }
            buffers.endBatch(Faint.THROUGH_WALLS);
        }
    }

    private static void drawOwn(Minecraft minecraft, VertexConsumer lines, PoseStack pose, Vec3 camera) {
        NetworkHover hover = NetworkHover.current(minecraft);
        // The selected device stays yellow until it is cleared.
        ItemStack tool = LinkTools.heldWrench(minecraft.player);
        GlobalPos selected = NetworkingWrenchItem.selected(tool);
        BlockPos selectedPos = null;
        if (selected != null && selected.dimension().equals(minecraft.level.dimension())) {
            selectedPos = selected.pos();
            box(pose, lines, camera, selectedPos, YELLOW);
            debugGlows++;
        }
        if (hover.kind() == NetworkHover.DEVICE && (selectedPos == null
                || !NetworkingWrenchItem.sameDevice(minecraft.level, selectedPos, hover.pos()))) {
            box(pose, lines, camera, hover.pos(), WHITE);
            debugGlows++;
        }
        drawTarget(minecraft.level, hover, lines, pose, camera, null, 1.0f, PanelConfig.linkRange(),
                minecraft.player.position());
    }

    /**
     * Draws the lines of one hover target. {@code tint} (or null) mixes the sharing player's colour into the line
     * colour. Nothing farther than {@code reach} blocks from {@code from} is drawn.
     */
    static void drawTarget(Level level, NetworkHover hover, VertexConsumer lines, PoseStack pose, Vec3 camera,
                           float[] tint, float alpha, int reach, Vec3 from) {
        if (hover.none()) {
            return;
        }
        double reachSq = (double) reach * reach;
        if (hover.kind() == NetworkHover.COMPONENT) {
            if (!level.isLoaded(hover.pos()) || !(level.getBlockEntity(hover.pos()) instanceof PanelBlockEntity owner)) {
                return;
            }
            PanelComponent component = owner.component(hover.id());
            if (component == null || component.type() == null) {
                return;
            }
            Vec3 start = faceCenter(owner, component);
            for (int i = 0; i < component.inputCount(); i++) {
                if (component.source(i) instanceof PlantSource plant) {
                    line(pose, lines, camera, start, Vec3.atCenterOf(plant.device()), READ, tint, alpha, reachSq, from);
                }
            }
            for (int i = 0; i < component.outputCount(); i++) {
                for (OutputSink sink : component.sinks(i)) {
                    if (sink instanceof PlantSink plant) {
                        line(pose, lines, camera, start, Vec3.atCenterOf(plant.device()), COMMAND, tint, alpha, reachSq,
                                from);
                    }
                }
            }
        } else {
            Vec3 end = Vec3.atCenterOf(hover.pos());
            for (PanelNetworkIndex.Ref ref : PanelNetworkIndex.refsFor(level, hover.pos())) {
                if (level.getBlockEntity(ref.panel()) instanceof PanelBlockEntity owner) {
                    PanelComponent component = owner.component(ref.componentId());
                    if (component != null) {
                        line(pose, lines, camera, faceCenter(owner, component), end, ref.command() ? COMMAND : READ, tint,
                                alpha, reachSq, from);
                    }
                }
            }
        }
    }

    /** The middle of a component's face, lifted off it a little, in world coordinates. */
    public static Vec3 faceCenter(PanelBlockEntity owner, PanelComponent component) {
        PanelGeometry.Frame frame = PanelGeometry.frame(owner.panelType(), owner.facing());
        double cu = component.x() + component.w() / 2.0;
        double cv = component.y() + component.h() / 2.0;
        BlockPos pos = owner.getBlockPos();
        return new Vec3(pos.getX() + frame.x(cu, cv, 0.5), pos.getY() + frame.y(cu, cv, 0.5),
                pos.getZ() + frame.z(cu, cv, 0.5));
    }

    private static void line(PoseStack pose, VertexConsumer out, Vec3 camera, Vec3 a, Vec3 b, float[] color, float[] tint,
                             float alpha, double reachSq, Vec3 from) {
        if (a.distanceToSqr(from) > reachSq || b.distanceToSqr(from) > reachSq) {
            return;
        }
        float r = color[0];
        float g = color[1];
        float bl = color[2];
        if (tint != null) {
            r = (r + tint[0]) * 0.5f;
            g = (g + tint[1]) * 0.5f;
            bl = (bl + tint[2]) * 0.5f;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6) {
            return;
        }
        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        PoseStack.Pose last = pose.last();
        out.addVertex(last, (float) (a.x - camera.x), (float) (a.y - camera.y), (float) (a.z - camera.z))
                .setColor(r, g, bl, alpha).setNormal(last, nx, ny, nz);
        out.addVertex(last, (float) (b.x - camera.x), (float) (b.y - camera.y), (float) (b.z - camera.z))
                .setColor(r, g, bl, alpha).setNormal(last, nx, ny, nz);
        debugLines++;
    }

    private static void box(PoseStack pose, VertexConsumer lines, Vec3 camera, BlockPos device, float[] color) {
        AABB box = new AABB(device).inflate(0.015).move(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, lines, box, color[0], color[1], color[2], 1.0f);
    }
}
