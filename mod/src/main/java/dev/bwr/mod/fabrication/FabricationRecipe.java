package dev.bwr.mod.fabrication;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.util.List;

/**
 * A part made at the fabrication desk ({@code bwr:fabrication}, FABRICATION-DESK.md): a result, a category (the desk's tab)
 * and a list of ingredients with counts. There is no grid and no shape; the desk takes the materials out of the player's
 * inventory and gives the result. A recipe is data, so the recipe sets with and without Mekanism are two sets of files with
 * mutually exclusive {@code neoforge:conditions}, like every other recipe in the mod.
 *
 * <pre>{@code
 * { "type": "bwr:fabrication", "category": "gauges",
 *   "ingredients": [ { "tag": "c:ingots/steel", "count": 4 }, { "item": "minecraft:glass_pane", "count": 1 } ],
 *   "result": { "id": "bwr:dial_gauge", "count": 1 } }
 * }</pre>
 *
 * <p>An ingredient is an item or a tag with a count (NeoForge's flat sized ingredient). The ingredients of one recipe must not
 * overlap (no item matched by two of them): the desk pays them in order, and an overlap would make the result depend on the
 * order. A test checks every recipe.
 */
public final class FabricationRecipe implements Recipe<FabricationInput> {
    /** The most crafts one request may ask for (a shift-click). */
    public static final int MAX_BATCH = 8;

    /** The recipe type; registered by {@link dev.bwr.mod.registry.BwrRecipes}. */
    public static final RecipeType<FabricationRecipe> TYPE = RecipeType.simple(BwrMod.id("fabrication"));

    public static final MapCodec<FabricationRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            FabricationCategory.CODEC.fieldOf("category").forGetter(FabricationRecipe::category),
            ExtraCodecs.nonEmptyList(SizedIngredient.FLAT_CODEC.listOf()).fieldOf("ingredients").forGetter(FabricationRecipe::inputs),
            ItemStack.STRICT_CODEC.fieldOf("result").forGetter(r -> r.result))
            .apply(i, FabricationRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, FabricationRecipe> STREAM_CODEC = StreamCodec.composite(
            FabricationCategory.STREAM_CODEC, FabricationRecipe::category,
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), FabricationRecipe::inputs,
            ItemStack.STREAM_CODEC, r -> r.result,
            FabricationRecipe::new);

    public static final RecipeSerializer<FabricationRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public MapCodec<FabricationRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, FabricationRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    };

    private final FabricationCategory category;
    private final List<SizedIngredient> inputs;
    private final ItemStack result;

    public FabricationRecipe(FabricationCategory category, List<SizedIngredient> inputs, ItemStack result) {
        this.category = category;
        this.inputs = List.copyOf(inputs);
        this.result = result;
    }

    public FabricationCategory category() {
        return category;
    }

    /** The materials, in the order the desk pays them. */
    public List<SizedIngredient> inputs() {
        return inputs;
    }

    /** A copy of what one craft gives. */
    public ItemStack output() {
        return result.copy();
    }

    // ---- paying

    /**
     * How many times the stacks can pay for this recipe, up to {@code limit}. Pure arithmetic over the counts: an item is
     * spent by the first ingredient that matches it, and a stack is never spent twice.
     */
    public int affordable(List<ItemStack> stacks, int limit) {
        for (int times = limit; times >= 1; times--) {
            if (plan(stacks, times) != null) {
                return times;
            }
        }
        return 0;
    }

    /**
     * Which stack gives how much for {@code times} crafts: {@code plan[i]} is the count taken from {@code stacks.get(i)}, or
     * null when the stacks cannot pay. Does not change the stacks.
     */
    public int[] plan(List<ItemStack> stacks, int times) {
        int[] left = new int[stacks.size()];
        for (int i = 0; i < left.length; i++) {
            left[i] = stacks.get(i).isEmpty() ? 0 : stacks.get(i).getCount();
        }
        int[] taken = new int[left.length];
        for (SizedIngredient input : inputs) {
            long need = (long) input.count() * times;
            for (int i = 0; i < left.length && need > 0; i++) {
                if (left[i] > 0 && input.ingredient().test(stacks.get(i))) {
                    int take = (int) Math.min(left[i], need);
                    left[i] -= take;
                    taken[i] += take;
                    need -= take;
                }
            }
            if (need > 0) {
                return null;
            }
        }
        return taken;
    }

    /** How many of each ingredient the stacks hold (not capped), in the recipe's order: the screen's have counts. */
    public int[] held(List<ItemStack> stacks) {
        int[] have = new int[inputs.size()];
        for (int n = 0; n < have.length; n++) {
            long sum = 0;
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty() && inputs.get(n).ingredient().test(stack)) {
                    sum += stack.getCount();
                }
            }
            have[n] = (int) Math.min(Integer.MAX_VALUE, sum);
        }
        return have;
    }

    // ---- Recipe

    /** True when the stacks pay for one craft. */
    @Override
    public boolean matches(FabricationInput input, Level level) {
        return plan(input.stacks(), 1) != null;
    }

    @Override
    public ItemStack assemble(FabricationInput input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> out = NonNullList.create();
        for (SizedIngredient input : inputs) {
            out.add(input.ingredient());
        }
        return out;
    }

    /** Never part of a recipe book or a crafting grid. */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public RecipeType<?> getType() {
        return TYPE;
    }
}
