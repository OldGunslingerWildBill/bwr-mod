package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Loads the real client models and checks every cell and rotation, then closes the test client. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineModelCheck {
    private TurbineModelCheck() {}

    @SubscribeEvent
    public static void baked(ModelEvent.BakingCompleted event) {
        if (!Boolean.getBoolean("bwr.turbineModelCheck")) return;
        PumpModelCheck.run(event);
        int checked = 0;
        for (TurbineAssemblyBlock block : new TurbineAssemblyBlock[]{BwrBlocks.RCIC_TWL.get(), BwrBlocks.HPCI_TURBINE.get()}) {
            int faces = 0;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (int cell = 0; cell < block.cellCount(); cell++) {
                    var state = block.defaultBlockState().setValue(TurbineAssemblyBlock.CELL, cell).setValue(TurbineAssemblyBlock.FACING, facing);
                    var model = event.getModels().get(BlockModelShaper.stateToModelLocation(state));
                    if (model == null || model == event.getModelManager().getMissingModel()) throw new IllegalStateException("Missing turbine model " + state);
                    var quads = model.getQuads(state, null, RandomSource.create(0), net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                    for (var quad : quads) {
                        if (quad.getSprite().contents().name().getPath().equals("missingno")) throw new IllegalStateException("Missing turbine material " + state);
                        int[] data = quad.getVertices();
                        int stride = data.length / 4;
                        for (int vertex = 0; vertex < 4; vertex++) for (int axis = 0; axis < 3; axis++) {
                            float p = Float.intBitsToFloat(data[vertex * stride + axis]);
                            if (!Float.isFinite(p) || p < -0.001F || p > 1.001F) throw new IllegalStateException("Turbine cell exceeds its block: " + state + " coordinate=" + p);
                        }
                    }
                    faces += quads.size();
                    checked++;
                }
            }
            if (faces < 1000) throw new IllegalStateException("Turbine mesh unexpectedly empty: " + block + " quads=" + faces);
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            var item = event.getModels().get(ModelResourceLocation.inventory(id));
            if (item == null || item == event.getModelManager().getMissingModel() || item.getQuads(null, null, RandomSource.create(0), net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null).isEmpty())
                throw new IllegalStateException("Missing assembly inventory model " + id);
            LogUtils.getLogger().info("TURBINE MODEL CHECK: {} has {} baked quads across four rotations", id, faces);
        }
        LogUtils.getLogger().info("TURBINE MODEL CHECK PASS: {} cell states and both inventory models", checked);
        if(!Boolean.getBoolean("bwr.pumpPanelCheck") && !Boolean.getBoolean("bwr.powerPanelCheck") && !Boolean.getBoolean("bwr.condenserPanelCheck") && !Boolean.getBoolean("bwr.coolingPanelCheck") && !Boolean.getBoolean("bwr.tankPanelCheck") && !Boolean.getBoolean("bwr.compactCorePanelCheck") && !Boolean.getBoolean("bwr.vesselModelCheck")) Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
