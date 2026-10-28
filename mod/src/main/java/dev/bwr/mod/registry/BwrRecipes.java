package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fabrication.FabricationRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Recipe types and serializers: the fabrication desk's {@code bwr:fabrication} (FABRICATION-DESK.md). */
public final class BwrRecipes {
    private BwrRecipes() {
    }

    public static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, BwrMod.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, BwrMod.MOD_ID);

    public static final DeferredHolder<RecipeType<?>, RecipeType<FabricationRecipe>> FABRICATION_TYPE =
            TYPES.register("fabrication", () -> FabricationRecipe.TYPE);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<FabricationRecipe>> FABRICATION_SERIALIZER =
            SERIALIZERS.register("fabrication", () -> FabricationRecipe.SERIALIZER);
}
