package dev.bwr.mod.grid;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

final class GridItems {
    private GridItems() {}
    static List<ItemStack> retain(List<ItemStack> drops,LootParams.Builder params){
        if(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof GridDevice device)
            for(var stack:drops){var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.put("BwrGridCondition",device.retainedCondition());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));}
        return drops;
    }
    static void restore(Level level,BlockPos pos,ItemStack stack){
        if(level.getBlockEntity(pos) instanceof GridDevice device){
            var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            if(data.contains("BwrGridCondition"))device.restoreCondition(data.getCompound("BwrGridCondition"));
        }
    }
}
