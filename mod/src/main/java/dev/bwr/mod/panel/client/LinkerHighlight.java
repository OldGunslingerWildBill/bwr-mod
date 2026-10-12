package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.OptionalDouble;

/**
 * While a panel linker is held and the crosshair is on a bound component, draws a see-through outline around
 * every plant device that component reads from (cyan) or commands (orange), so a long wiring run can be
 * followed to its end across a room or a wall. Client only; reads the bindings the full-state packet already
 * carries.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class LinkerHighlight {
    private LinkerHighlight() {
    }

    /** Lines that ignore depth, so the outline shows through blocks. Subclassing only reaches the shared states. */
    private static final class Types extends RenderType {
        private Types() {
            super("bwr_linker_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false,
                    false, () -> {
                    }, () -> {
                    });
        }

        static final RenderType THROUGH_WALLS = create("bwr_linker_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, 256, false, false, RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(3.0)))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.player.getMainHandItem().getItem() instanceof PanelLinkerItem
                || minecraft.player.getOffhandItem().getItem() instanceof PanelLinkerItem)) {
            return;
        }
        PanelComponent hovered = PanelClientInput.hoveredComponent(minecraft);
        if (hovered == null || hovered.type() == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(Types.THROUGH_WALLS);
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        boolean drew = false;
        for (int i = 0; i < hovered.inputCount(); i++) {
            if (hovered.source(i) instanceof PlantSource plant) {
                box(pose, lines, camera, plant.device(), 0.3f, 0.9f, 1.0f);
                drew = true;
            }
        }
        for (int i = 0; i < hovered.outputCount(); i++) {
            for (OutputSink sink : hovered.sinks(i)) {
                if (sink instanceof PlantSink plant) {
                    box(pose, lines, camera, plant.device(), 1.0f, 0.6f, 0.2f);
                    drew = true;
                }
            }
        }
        if (drew) {
            buffers.endBatch(Types.THROUGH_WALLS);
        }
    }

    private static void box(PoseStack pose, VertexConsumer lines, Vec3 camera, BlockPos device, float r, float g, float b) {
        AABB box = new AABB(device).inflate(0.01).move(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1.0f);
    }
}
