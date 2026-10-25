package dev.bwr.mod.fuel;

import dev.bwr.mod.text.BwrText;

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
        lines.add(BwrText.tr(
                "status.bwr.specialty_rod_item.non_fuel_cassette_occupies_one_refuelling_position",
                "Non-fuel cassette; occupies one refuelling position.").withStyle(ChatFormatting.GRAY));
        if (insert.kind().exposureSeconds > 0) {
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.value",
                    "%s %s%%",
                    (insert.kind().target() ? BwrText.tr("status.bwr.specialty_rod_item.irradiation", "Irradiation") : BwrText.tr("status.bwr.specialty_rod_item.source_activation", "Source activation")),
                    String.format(java.util.Locale.ROOT, "%.1f", 100 * insert.progress())).withStyle(ChatFormatting.AQUA));
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.progress_requires_fission_flux_retained_when_removed",
                    "Progress requires fission flux; retained when removed.").withStyle(ChatFormatting.GRAY));
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.rated_flux_exposure_hours_running_time",
                    "Rated-flux exposure: %s hours (running time).",
                    String.format(java.util.Locale.ROOT, "%.0f", insert.kind().exposureSeconds / 3600)).withStyle(ChatFormatting.GRAY));
        }
        if (insert.kind().sourcePerSecond > 0) lines.add(BwrText.tr(
                "status.bwr.specialty_rod_item.adds_startup_neutrons_no_direct_fission_heat",
                "Adds startup neutrons; no direct fission heat.").withStyle(ChatFormatting.GRAY));
        else lines.add(BwrText.tr(
                "status.bwr.specialty_rod_item.absorbs_neutrons_without_producing_fission_power",
                "Absorbs neutrons without producing fission power.").withStyle(ChatFormatting.GRAY));
        if (insert.kind() == CoreInsert.Kind.TRITIUM_TARGET) {
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.harvest_sealed_tritium_samples_empty_rod",
                    "Harvest: %s sealed tritium samples + empty rod.",
                    String.format(java.util.Locale.ROOT, "%,d", dev.bwr.mod.config.GameplayConfig.tritiumSamplesPerRod())).withStyle(ChatFormatting.AQUA));
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.experimental_bwr_target_adapted_for_gameplay",
                    "Experimental BWR target, adapted for gameplay.").withStyle(ChatFormatting.GRAY));
        }
        if (insert.kind().ordinal() >= CoreInsert.Kind.BISMUTH_TARGET.ordinal() && insert.kind().target()) {
            lines.add(BwrText.tr(
                    "status.bwr.specialty_rod_item.harvest_bred_isotope",
                    "Harvest: one %s + empty rod.", new ItemStack(productOf(insert.kind())).getHoverName()).withStyle(ChatFormatting.AQUA));
        }
        if (insert.complete()) lines.add(BwrText.tr(
                "status.bwr.specialty_rod_item.use_in_hand_to_harvest_the_sample_and",
                "Use in hand to harvest the sample and recover the empty rod.").withStyle(ChatFormatting.GREEN));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        var insert = data(held).toCore();
        if (!insert.complete()) return InteractionResultHolder.pass(held);
        if (!level.isClientSide()) {
            Item product = productOf(insert.kind());
            if (product == null) throw new IllegalStateException("Not a harvestable target");
            held.shrink(1);
            give(player, product, insert.kind() == CoreInsert.Kind.TRITIUM_TARGET
                    ? dev.bwr.mod.config.GameplayConfig.tritiumSamplesPerRod() : 1);
            give(player, BwrItems.IRRADIATION_CASING.get(), 1);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }
    /** What a finished target of this kind is harvested into, or null for a kind that is not a target (BREEDING-TARGETS.md for the reactor-bred ones). */
    public static Item productOf(CoreInsert.Kind kind) {
        return switch (kind) {
            case COBALT_TARGET -> BwrItems.COBALT_SAMPLE.get();
            case TRITIUM_TARGET -> BwrItems.TRITIUM_SAMPLE.get();
            case SILICON_TARGET -> BwrItems.DOPED_SILICON.get();
            case BISMUTH_TARGET -> BwrItems.POLONIUM_210_INGOT.get();
            case THORIUM_TARGET -> BwrItems.URANIUM_233_INGOT.get();
            case URANIUM_TARGET -> BwrItems.NEPTUNIUM_237_INGOT.get();
            case NEPTUNIUM_TARGET -> BwrItems.PLUTONIUM_238_INGOT.get();
            case RADIUM_TARGET -> BwrItems.ACTINIUM_227_INGOT.get();
            case THULIUM_TARGET -> BwrItems.THULIUM_170_INGOT.get();
            default -> null;
        };
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
