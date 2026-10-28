package dev.bwr.mod.fabrication;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The server's side of the fabrication desk (FABRICATION-DESK.md): every craft is decided here, from scratch, whatever the screen
 * showed. A request names a recipe and a number of crafts; nothing the client says about its inventory is believed.
 *
 * <p>{@link #craft} checks, in this order: the player may operate things (not a spectator) and is alive; the desk is a loaded
 * fabrication desk in the player's level; the player is within reach of it; the recipe is one the server has loaded (so a
 * recipe that the current mod set does not load cannot be asked for); and the player's inventory pays for at least one craft.
 * It then takes the materials from the inventory and gives the result, in one go on the server thread: the payment is planned
 * before anything is touched, so it cannot fail halfway; what does not fit in the inventory is dropped at the player's feet,
 * never deleted (not even for a creative player, for whom a plain {@code Inventory#add} would delete it).
 *
 * <p>A creative player pays like anyone else. The mod has no free-items rule anywhere else, and a creative player can take
 * the part from the creative tab anyway.
 */
public final class FabricationCrafting {
    private FabricationCrafting() {
    }

    /**
     * How far from the desk a craft is still allowed, on top of the player's block interaction range: the same slack a
     * container screen gets in vanilla ({@code AbstractContainerMenu.stillValid}), so a screen that is open is never refused
     * for distance, and a player beyond it is.
     */
    public static final double REACH_SLACK = 4.0;

    /** What a request came to. */
    public enum Outcome {
        /** Materials were taken and the result given (some of it may have been dropped: see {@link Result#dropped}). */
        CRAFTED,
        /** The inventory cannot pay for one craft. */
        SHORT,
        /** The player is beyond reach of the desk. */
        TOO_FAR,
        /** A spectator, or a player who is dead. */
        NOT_ALLOWED,
        /** The position is not a loaded fabrication desk. */
        NO_DESK,
        /** No loaded recipe has that key. */
        UNKNOWN_RECIPE,
        /** Too many requests too fast (decided by the menu, before it gets here). */
        RATE_LIMITED
    }

    /** The outcome, the number of crafts made and how many items of the result had no room and were dropped. */
    public record Result(Outcome outcome, int crafts, int dropped) {
        static Result refused(Outcome outcome) {
            return new Result(outcome, 0, 0);
        }
    }

    /** All loaded fabrication recipes, in a fixed order (by id). */
    public static List<RecipeHolder<FabricationRecipe>> recipes(RecipeManager manager) {
        List<RecipeHolder<FabricationRecipe>> all = new ArrayList<>(manager.getAllRecipesFor(FabricationRecipe.TYPE));
        all.sort(Comparator.comparing(h -> h.id().toString()));
        return all;
    }

    /** The loaded desk recipes that make {@code item} (in an install there is exactly one for each part the desk makes). */
    public static List<RecipeHolder<FabricationRecipe>> making(RecipeManager manager, net.minecraft.core.HolderLookup.Provider access,
                                                               net.minecraft.world.item.Item item) {
        List<RecipeHolder<FabricationRecipe>> out = new ArrayList<>();
        for (RecipeHolder<FabricationRecipe> holder : recipes(manager)) {
            if (holder.value().getResultItem(access).is(item)) {
                out.add(holder);
            }
        }
        return out;
    }

    /** The number that names a recipe on the wire: the hash of its id. A test requires the loaded recipes to have distinct ones. */
    public static int key(ResourceLocation id) {
        return id.toString().hashCode();
    }

    /** The loaded recipe with this key, or null. */
    public static RecipeHolder<FabricationRecipe> find(RecipeManager manager, int key) {
        for (RecipeHolder<FabricationRecipe> holder : recipes(manager)) {
            if (key(holder.id()) == key) {
                return holder;
            }
        }
        return null;
    }

    /** The inventory slots a craft may take from: the 36 main slots and the off hand. Never armour. */
    private static final int[] PAYING_SLOTS = new int[37];

    static {
        for (int i = 0; i < 36; i++) {
            PAYING_SLOTS[i] = i;
        }
        PAYING_SLOTS[36] = Inventory.SLOT_OFFHAND;
    }

    /** The stacks (the inventory's own, not copies) that pay, in a fixed order. */
    public static List<ItemStack> payingStacks(Inventory inventory) {
        List<ItemStack> out = new ArrayList<>(PAYING_SLOTS.length);
        for (int slot : PAYING_SLOTS) {
            out.add(inventory.getItem(slot));
        }
        return out;
    }

    /** True when {@code player} is within reach of the desk at {@code pos}. */
    public static boolean inReach(ServerPlayer player, BlockPos pos) {
        return player.canInteractWithBlock(pos, REACH_SLACK);
    }

    /**
     * Makes {@code times} (at most {@link FabricationRecipe#MAX_BATCH}, and at most one stack of the result) crafts of the
     * recipe with this key for {@code player} at the desk at {@code desk}, as many as the inventory pays for. Everything is
     * re-checked here; see the class comment.
     */
    public static Result craft(ServerPlayer player, BlockPos desk, int recipeKey, int times) {
        Outcome refusal = refusal(player, desk);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        RecipeHolder<FabricationRecipe> holder = find(player.serverLevel().getServer().getRecipeManager(), recipeKey);
        if (holder == null) {
            return Result.refused(Outcome.UNKNOWN_RECIPE);
        }
        return pay(player, holder.value(), times);
    }

    /** As {@link #craft(ServerPlayer, BlockPos, int, int)} for a recipe object (the desk's checks are the same). */
    public static Result craft(ServerPlayer player, BlockPos desk, FabricationRecipe recipe, int times) {
        Outcome refusal = refusal(player, desk);
        return refusal != null ? Result.refused(refusal) : pay(player, recipe, times);
    }

    /** Why the player may not use the desk at {@code desk} now, or null. */
    private static Outcome refusal(ServerPlayer player, BlockPos desk) {
        if (!AssemblyAccess.mayOperate(player) || !player.isAlive()) {
            return Outcome.NOT_ALLOWED;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(desk) || !level.getBlockState(desk).is(BwrBlocks.FABRICATION_DESK.get())) {
            return Outcome.NO_DESK;
        }
        if (!inReach(player, desk)) {
            return Outcome.TOO_FAR;
        }
        return null;
    }

    private static Result pay(ServerPlayer player, FabricationRecipe recipe, int times) {
        ItemStack unit = recipe.output();
        int perStack = Math.max(1, unit.getMaxStackSize() / Math.max(1, unit.getCount()));
        int limit = Math.max(1, Math.min(Math.min(times, FabricationRecipe.MAX_BATCH), perStack));

        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = payingStacks(inventory);
        int crafts = recipe.affordable(stacks, limit);
        if (crafts == 0) {
            return Result.refused(Outcome.SHORT);
        }
        int[] plan = recipe.plan(stacks, crafts);
        // Pay. The plan was made from these very stacks a moment ago on this thread, so every take is there.
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0) {
                ItemStack stack = stacks.get(i);
                stack.shrink(plan[i]);
                if (stack.isEmpty()) {
                    inventory.setItem(PAYING_SLOTS[i], ItemStack.EMPTY);
                }
            }
        }
        // Give. What does not fit is dropped, never lost.
        int total = unit.getCount() * crafts;
        int dropped = 0;
        while (total > 0) {
            ItemStack part = unit.copyWithCount(Math.min(total, unit.getMaxStackSize()));
            total -= part.getCount();
            ItemStack rest = insert(inventory, part);
            if (!rest.isEmpty()) {
                dropped += rest.getCount();
                player.drop(rest, false);
            }
        }
        inventory.setChanged();
        return new Result(Outcome.CRAFTED, crafts, dropped);
    }

    /**
     * Puts the stack into the main inventory (onto matching stacks first, then into the first empty slot) and returns what did
     * not fit. {@code Inventory#add} is not used: it counts an item that did not fit as taken for a creative player.
     */
    static ItemStack insert(Inventory inventory, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < 36 && !rest.isEmpty(); i++) {
            ItemStack there = inventory.getItem(i);
            if (!there.isEmpty() && there.getCount() < there.getMaxStackSize() && ItemStack.isSameItemSameComponents(there, rest)) {
                int move = Math.min(rest.getCount(), there.getMaxStackSize() - there.getCount());
                there.grow(move);
                rest.shrink(move);
            }
        }
        for (int i = 0; i < 36 && !rest.isEmpty(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                int move = Math.min(rest.getCount(), rest.getMaxStackSize());
                inventory.setItem(i, rest.copyWithCount(move));
                rest.shrink(move);
            }
        }
        return rest;
    }
}
