package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.CoreInsert;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Locale;

public final class SpecialtyRodItem extends Item {
    public static final int TRITIUM_SAMPLES_PER_ROD = 1_250;
    public SpecialtyRodItem(Properties properties) { super(properties); }
    public static boolean isCoreItem(ItemStack stack) {
        return stack.is(BwrItems.FUEL_ASSEMBLY.get()) || stack.is(BwrItems.SPECIALTY_ROD.get());
    }
    public static CoreInsertData data(ItemStack stack) {
        return stack.getOrDefault(BwrDataComponents.CORE_INSERT.get(), new CoreInsertData(CoreInsert.Kind.BORON_ABSORBER, 0));
    }
    public static ItemStack stack(CoreInsertData data) {
        ItemStack result = new ItemStack(BwrItems.SPECIALTY_ROD.get());
        result.set(BwrDataComponents.CORE_INSERT.get(), data); return result;
    }
    @Override public Component getName(ItemStack stack) { return Component.translatable("insert.bwr." + data(stack).kind().id()); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var insert = data(stack).toCore();
        lines.add(Component.literal("Non-fuel cassette; occupies one refuelling position.").withStyle(ChatFormatting.GRAY));
        if (insert.kind().exposureSeconds > 0) {
            lines.add(Component.literal(String.format(Locale.ROOT, "%s %.1f%%", insert.kind().target() ? "Irradiation" : "Source activation", 100 * insert.progress())).withStyle(ChatFormatting.AQUA));
            lines.add(Component.literal("Progress requires fission flux; retained when removed.").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal(String.format(Locale.ROOT, "Rated-flux exposure: %.0f hours (running time).",
                    insert.kind().exposureSeconds / 3600)).withStyle(ChatFormatting.GRAY));
        }
        if (insert.kind().sourcePerSecond > 0) lines.add(Component.literal("Adds startup neutrons; no direct fission heat.").withStyle(ChatFormatting.GRAY));
        else lines.add(Component.literal("Absorbs neutrons without producing fission power.").withStyle(ChatFormatting.GRAY));
        if (insert.kind() == CoreInsert.Kind.TRITIUM_TARGET) {
            lines.add(Component.literal(String.format(Locale.ROOT, "Harvest: %,d sealed tritium samples + empty rod.",
                    TRITIUM_SAMPLES_PER_ROD)).withStyle(ChatFormatting.AQUA));
            lines.add(Component.literal("Experimental BWR target, adapted for gameplay.").withStyle(ChatFormatting.GRAY));
        }
        if (insert.complete()) lines.add(Component.literal("Use in hand to harvest the sample and recover the empty rod.").withStyle(ChatFormatting.GREEN));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        var insert = data(held).toCore();
        if (!insert.complete()) return InteractionResultHolder.pass(held);
        if (!level.isClientSide()) {
            Item product = switch (insert.kind()) {
                case COBALT_TARGET -> BwrItems.COBALT_SAMPLE.get();
                case TRITIUM_TARGET -> BwrItems.TRITIUM_SAMPLE.get();
                case SILICON_TARGET -> BwrItems.DOPED_SILICON.get();
                default -> throw new IllegalStateException("Not a harvestable target");
            };
            held.shrink(1);
            give(player, product, insert.kind() == CoreInsert.Kind.TRITIUM_TARGET ? TRITIUM_SAMPLES_PER_ROD : 1);
            give(player, BwrItems.IRRADIATION_CASING.get(), 1);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }
    private static void give(Player player, Item item, int count) {
        // Large harvests must use legal stack sizes, including any inventory overflow drops.
        while (count > 0) {
            ItemStack stack = new ItemStack(item);
            int batch = Math.min(count, stack.getMaxStackSize());
            stack.setCount(batch);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            count -= batch;
        }
    }
}
