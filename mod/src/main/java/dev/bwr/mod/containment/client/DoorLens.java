package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * The lit lens of a door status light. The housing and its dark recesses are part of the door's frame model; the one lens that
 * is lit is drawn over its recess at full brightness: red open, amber moving, green shut and latched.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class DoorLens {
    private DoorLens() {}

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr", "block/containment/" + name));
    }

    public static final ModelResourceLocation RED = model("door_lens_red"), AMBER = model("door_lens_amber"), GREEN = model("door_lens_green");

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        event.register(RED);
        event.register(AMBER);
        event.register(GREEN);
    }

    /** The lens for a leaf at stroke position x (0 shut, 1 open, between them moving). */
    public static ModelResourceLocation forPosition(double x) {
        return x <= 0.0005 ? GREEN : x >= 0.9995 ? RED : AMBER;
    }

    /** Row of a lens in a column of three (top red, middle amber, bottom green): the offset from the column's centre. */
    public static double rowOffset(double x, double spacing) {
        return x <= 0.0005 ? -spacing : x >= 0.9995 ? spacing : 0;
    }

    /** Draws one lens at a block-local point, facing -z, or +z when {@code facesPositiveZ}. */
    public static void draw(ModelResourceLocation lens, PoseStack pose, MultiBufferSource buffers, int overlay,
                            double x, double y, double z, boolean facesPositiveZ) {
        pose.pushPose();
        pose.translate(x, y, z);
        if (facesPositiveZ) pose.mulPose(Axis.YP.rotationDegrees(180));
        MachineMeshCache.model(lens, pose, buffers, LightTexture.FULL_BRIGHT, overlay);
        pose.popPose();
    }
}
