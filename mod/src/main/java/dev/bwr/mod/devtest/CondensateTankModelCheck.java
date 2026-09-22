package dev.bwr.mod.devtest;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.eccs.client.CondensateTankRenderer;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
public final class CondensateTankModelCheck {
    public static void run(ModelEvent.BakingCompleted e){
        int count=0;for(var name:CondensateTankRenderer.PARTS){var m=e.getModels().get(CondensateTankRenderer.model(name));if(m==null||m==e.getModelManager().getMissingModel())throw new AssertionError("Missing tank component "+name);
            var faces=m.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null);count+=faces.size();for(var q:faces)if(q.getSprite().contents().name().getPath().equals("missingno"))throw new AssertionError("Missing tank texture");}
        var renderer=new CondensateTankRenderer(null);for(int[] size:new int[][]{{3,3},{7,8},{15,24}}){
            var s=BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState().setValue(CondensateStorageTankBlock.ASSEMBLED,true);var be=new CondensateStorageTankBlockEntity(BlockPos.ZERO,s);be.configure(size[0],size[1],1);
            var capture=new CondenserModelCheck.Capture(renderer.getRenderBoundingBox(be));renderer.render(be,0,new PoseStack(),type->capture,15728880,0);
            if(capture.count<20000||capture.colors.size()<3)throw new AssertionError("Incomplete/uncolored tank renderer");
            var child=new CondensateStorageTankBlockEntity(BlockPos.ZERO,s.setValue(CondensateStorageTankBlock.CONTROLLER,false));int before=capture.count;renderer.render(child,0,new PoseStack(),type->capture,15728880,0);if(before!=capture.count)throw new AssertionError("Tank rendered per child");
        }
        LogUtils.getLogger().info("CONDENSATE TANK MODEL CHECK PASS: {} component quads; min/mid/max scaling, material RGB and bounds",count);
    }
}
