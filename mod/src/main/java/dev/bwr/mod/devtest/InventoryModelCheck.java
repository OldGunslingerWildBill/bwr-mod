package dev.bwr.mod.devtest;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.event.ModelEvent;
/** Prevent detailed meshes or missing textures from silently returning to creative inventory rendering. */
final class InventoryModelCheck {
    static void run(ModelEvent.BakingCompleted event){
        try(var in=InventoryModelCheck.class.getResourceAsStream("/assets/bwr/inventory_icons.json")){
            int count=0;
            for(var entry:JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()){
                String id=entry.getAsString();var model=event.getModels().get(ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath("bwr",id)));
                var gui=model.applyTransform(ItemDisplayContext.GUI,new PoseStack(),false);
                var quads=gui.getQuads(null,null,RandomSource.create(0));
                if(quads.size()!=2)throw new IllegalStateException("Heavy/missing GUI icon: "+id+" quads="+quads.size());
                for(var q:quads)if(q.getSprite().contents().name().getPath().equals("missingno"))throw new IllegalStateException("Missing icon texture: "+id);
                if(model.applyTransform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,new PoseStack(),false).getQuads(null,null,RandomSource.create(0)).isEmpty())throw new IllegalStateException("Held model lost: "+id);
                count++;
            }
            com.mojang.logging.LogUtils.getLogger().info("INVENTORY MODEL CHECK PASS: {} two-quad icons, held meshes preserved",count);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
