package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.containment.client.AirlockRenderer;
import dev.bwr.mod.containment.client.DoorLens;
import dev.bwr.mod.containment.client.VaultDoorRenderer;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Client acceptance check for the actual OBJ loader, sprites and rotations. */
public final class ContainmentModelCheck {
    private ContainmentModelCheck(){}
    private static void check(net.minecraft.client.resources.model.BakedModel model,ModelEvent.BakingCompleted event,String id){
        if(model==null||model==event.getModelManager().getMissingModel())throw new AssertionError("Missing containment model "+id);
        var quads=new java.util.ArrayList<net.minecraft.client.renderer.block.model.BakedQuad>();
        quads.addAll(model.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null));
        for(Direction side:Direction.values())quads.addAll(model.getQuads(null,side,RandomSource.create(0),ModelData.EMPTY,null));
        if(quads.isEmpty())throw new AssertionError("Empty containment model "+id);
        for(var quad:quads)if(quad.getSprite().contents().name().getPath().equals("missingno"))
            throw new AssertionError("Missing containment texture "+id);
    }
    public static void run(ModelEvent.BakingCompleted event){
        var parts=new java.util.ArrayList<>(java.util.List.of(VaultDoorRenderer.FRAME3,VaultDoorRenderer.LEAF3,
                VaultDoorRenderer.FRAME4,VaultDoorRenderer.LEAF4,AirlockRenderer.FRAME,AirlockRenderer.INNER,AirlockRenderer.OUTER,
                AirlockRenderer.WHEEL,AirlockRenderer.VALVE_WHEEL,DoorLens.RED,DoorLens.AMBER,DoorLens.GREEN));
        for(int i=0;i<2;i++){
            parts.add(VaultDoorRenderer.DOGS_RIGHT[i]);parts.add(VaultDoorRenderer.DOGS_TOP[i]);
            parts.add(VaultDoorRenderer.DOGS_BOTTOM[i]);parts.add(VaultDoorRenderer.WHEEL[i]);
        }
        for(var id:parts)
            check(event.getModels().get(id),event,id.toString());
        for(var block:new net.minecraft.world.level.block.Block[]{BwrBlocks.CONTAINMENT_VAULT_DOOR.get(),
                BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get(),BwrBlocks.CONTAINMENT_AIRLOCK.get(),
                BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get(),BwrBlocks.CONTAINMENT_WATER_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_PASS_THROUGH.get(),BwrBlocks.CONTAINMENT_POWER_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_DATA_PENETRATION.get()}){
            var id=BuiltInRegistries.BLOCK.getKey(block);
            check(event.getModels().get(ModelResourceLocation.inventory(id)),event,id+" item");
            if(!(block instanceof dev.bwr.mod.containment.ContainmentPenetrationBlock))continue;
            for(Direction facing:Direction.values()){
                var state=block.defaultBlockState().setValue(dev.bwr.mod.containment.ContainmentPenetrationBlock.FACING,facing);
                check(event.getModels().get(BlockModelShaper.stateToModelLocation(state)),event,id+" facing "+facing);
            }
        }
        LogUtils.getLogger().info("CONTAINMENT MODEL CHECK PASS: vault, airlock, their latch dogs, wheels and lenses, and five Blender sleeves with all facings");
    }
}
