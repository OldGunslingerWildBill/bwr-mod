package dev.bwr.mod.fabrication;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

import java.util.List;

/**
 * What a fabrication recipe is matched against: the stacks a player could pay with (main inventory, hotbar and off hand;
 * never armour). Copies are not made, so a recipe must not change them: {@link FabricationRecipe#matches} only counts.
 */
public record FabricationInput(List<ItemStack> stacks) implements RecipeInput {
    @Override
    public ItemStack getItem(int index) {
        return stacks.get(index);
    }

    @Override
    public int size() {
        return stacks.size();
    }
}
