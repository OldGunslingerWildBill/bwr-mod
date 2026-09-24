package dev.bwr.mod.gui.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/** Static Blender renders prevent inventory grids from submitting entire plant meshes per frame. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class InventoryModels {
    private static final List<String> IDS = loadIds();
    private static List<String> loadIds() {
        try (var in = InventoryModels.class.getResourceAsStream("/assets/bwr/inventory_icons.json")) {
            if (in == null) throw new IllegalStateException("Missing inventory icon manifest");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()
                    .asList().stream().map(e -> e.getAsString()).toList();
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private static ModelResourceLocation icon(String id) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr", "inventory/" + id));
    }
    @SubscribeEvent public static void register(ModelEvent.RegisterAdditional event) {
        IDS.forEach(id -> event.register(icon(id)));
    }
    @SubscribeEvent public static void bake(ModelEvent.ModifyBakingResult event) {
        for (String id : IDS) {
            var key = ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath("bwr", id));
            BakedModel original = event.getModels().get(key), flat = event.getModels().get(icon(id));
            if (original == null || flat == null) throw new IllegalStateException("Unbaked inventory icon: " + id);
            event.getModels().put(key, new BakedModelWrapper<BakedModel>(original) {
                @Override public boolean usesBlockLight() { return false; }
                @Override public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
                    return (context == ItemDisplayContext.GUI ? flat : original).applyTransform(context, pose, leftHand);
                }
            });
        }
    }
    private InventoryModels() {}
}
