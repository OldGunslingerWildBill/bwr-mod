package dev.bwr.mod.fuel;

import dev.bwr.mod.text.BwrText;

import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/**
 * A fuel bundle. One item, one assembly, ~180 kg of heavy metal, and a full
 * irradiation history in its {@link FuelAssemblyData} component.
 *
 * <p>The tooltip exists so a player can read a bundle <i>before</i> deciding
 * where to put it. That is the entire skill of fuel management: a fresh bundle
 * in the high-flux centre burns fast and peaks the power distribution, the same
 * bundle two cycles later belongs on the periphery where it can finish its life
 * cheaply, and knowing which is which requires seeing the exposure. Every line
 * below is a measurement of the bundle in hand.
 *
 * <h2>No advice</h2>
 * The tooltip never says a bundle is spent, safe, dangerous, ready, or where it
 * should go. It reports exposure, enrichment, k-infinity and remaining
 * gadolinia, and the player decides what those mean. In particular the
 * "exposure remaining" line is the fuel's own physics — the burnup at which
 * this bundle alone would stop multiplying — and is not a refuelling
 * recommendation; the core it is going into may well have run out of margin
 * long before, or have plenty left.
 */
public class FuelAssemblyItem extends Item {

    public FuelAssemblyItem(Properties properties) {
        super(properties);
    }

    /**
     * Creative-menu and {@code /give} stacks come out as fresh LEU rather than
     * componentless. The component default is applied here rather than through
     * {@code Properties#component} so that item registration never has to
     * resolve the data component type, which is filled by a different registry
     * pass.
     */
    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();
        FuelAssemblies.setData(stack, FuelAssemblyData.fresh(FuelTypes.fallback()));
        return stack;
    }

    /** A fresh bundle of a given fuel at a given enrichment. Used by the fabricator and the creative tab. */
    public static ItemStack stackOf(Item item, FuelAssemblyData data) {
        ItemStack stack = new ItemStack(item);
        FuelAssemblies.setData(stack, data);
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        String id = FuelAssemblies.dataOf(stack).fuelTypeName();
        return Component.translatableWithFallback("fuel.bwr." + id, "Fuel Assembly: " + id);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        FuelAssemblyData data = FuelAssemblies.dataOf(stack);
        FuelAssembly assembly = FuelAssemblies.toCore(data);
        FuelType type = assembly.fuelType();

        tooltip.add(line(BwrText.tr(
                "status.bwr.fuel_assembly_item.issile_kg_hm",
                "%s, %s%% fissile, %s kg HM",
                type.name(),
                String.format(java.util.Locale.ROOT, "%.2f", data.enrichmentWeightFraction() * 100.0),
                String.format(java.util.Locale.ROOT, "%.0f", data.heavyMetalMassKg()))));

        if (type.name().equals("natural_uranium")) tooltip.add(line(BwrText.tr(
                "status.bwr.fuel_assembly_item.experimental_bwr_loading_cannot_sustain_a_chain_reaction",
                "Experimental BWR loading; cannot sustain a chain reaction alone.")));
        if (data.enrichmentWeightFraction() >= 0.05 || type.name().startsWith("plutonium") || type.name().startsWith("thorium"))
            tooltip.add(line(BwrText.tr(
                    "status.bwr.fuel_assembly_item.experimental_fuel_option_simplified_nuclear_data",
                    "Experimental fuel option; simplified nuclear data.")));
        if (type.name().startsWith("mox") || type.name().startsWith("plutonium"))
            tooltip.add(line(BwrText.tr(
                    "status.bwr.fuel_assembly_item.effective_fissile_fraction_not_total_plutonium_content",
                    "Effective fissile fraction; not total plutonium content.")));

        // Two different things can have gone wrong, and conflating them is how
        // the old check managed to stay silent in the case that mattered.
        //
        // A substitution is when the bundle's own fuel type could not be
        // resolved at all and something else is being reported in its place --
        // every number below then belongs to a different material, so it is red.
        // Comparing the resolved name against the stored one is the direct test
        // for that; asking whether the name is in the registry is not, because
        // FuelTypes#byNameOrFallback also resolves through the compiled presets.
        //
        // Falling back to the presets is milder: the numbers are this fuel's,
        // they simply are not this world's fuel table's. That is provenance
        // worth stating and not an error.
        if (!type.name().equals(data.fuelTypeName())) {
            tooltip.add(BwrText.tr(
                    "status.bwr.fuel_assembly_item.fuel_type_is_not_loaded_substituted",
                    "fuel type '%s' is not loaded; substituted %s",
                    data.fuelTypeName(),
                    type.name())
                    .withStyle(ChatFormatting.RED));
        } else if (!FuelTypes.isKnown(data.fuelTypeName())) {
            tooltip.add(BwrText.tr(
                    "status.bwr.fuel_assembly_item.fuel_type_is_not_in_this_world_s",
                    "fuel type '%s' is not in this world's fuel table; showing built-in data",
                    data.fuelTypeName())
                    .withStyle(ChatFormatting.YELLOW));
        }

        tooltip.add(line(BwrText.tr(
                "status.bwr.fuel_assembly_item.burnup_mwd_t_mwd_produced",
                "Burnup %s MWd/t  (%s MWd produced)",
                String.format(java.util.Locale.ROOT, "%,.0f", data.burnupMwdPerTonne()),
                String.format(java.util.Locale.ROOT, "%,.0f", FuelAssemblies.energyProducedMwd(assembly)))));

        tooltip.add(line(BwrText.tr(
                "status.bwr.fuel_assembly_item.k_inf_fresh",
                "k-inf %s  (fresh %s)",
                String.format(java.util.Locale.ROOT, "%.4f", assembly.kInf()),
                String.format(java.util.Locale.ROOT, "%.4f", assembly.baseKInf()))));

        double remaining = FuelAssemblies.exposureRemainingMwdPerTonne(assembly);
        if (remaining <= 0.0) {
            tooltip.add(line(BwrText.tr("status.bwr.fuel_assembly_item.below_k_inf_1_0_on_its_own", "Below k-inf 1.0 on its own")));
        } else if (remaining >= FuelAssemblies.EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE) {
            // A very highly enriched bundle can outlast the scan. Saying so is
            // honest; printing the scan limit as if it were the answer is not.
            tooltip.add(line(BwrText.tr(
                    "status.bwr.fuel_assembly_item.exposure_to_k_inf_1_0_over_mwd",
                    "Exposure to k-inf 1.0: over %s MWd/t",
                    String.format(java.util.Locale.ROOT, "%,.0f", FuelAssemblies.EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE))));
        } else {
            tooltip.add(line(BwrText.tr(
                    "status.bwr.fuel_assembly_item.exposure_to_k_inf_1_0_mwd_t",
                    "Exposure to k-inf 1.0: %s MWd/t",
                    String.format(java.util.Locale.ROOT, "%,.0f", remaining))));
        }

        if (type.gadContentWeightFraction() > 0.0) {
            tooltip.add(line(BwrText.tr(
                    "status.bwr.fuel_assembly_item.gadolinia_emaining_holding_dk",
                    "Gadolinia %s%% remaining (holding %s dk)",
                    String.format(java.util.Locale.ROOT, "%.0f", data.gadoliniaRemainingFraction() * 100.0),
                    String.format(java.util.Locale.ROOT, "%.4f", assembly.gadPenaltyKInf()))));
        }

        // Beta is the number that decides how much control margin the core has.
        // It is on the tooltip because a player loading MOX into a high-flux
        // position deserves to have seen 0.0035 first, not because anything in
        // the mod reacts to it.
        tooltip.add(line(BwrText.tr(
                "status.bwr.fuel_assembly_item.beta_prompt_lifetime_s",
                "beta %s, prompt lifetime %s s",
                String.format(java.util.Locale.ROOT, "%.5f", type.beta()),
                String.format(java.util.Locale.ROOT, "%.1e", type.promptLifetimeSeconds()))));
    }

    private static Component line(Component text) {
        return text.copy().withStyle(ChatFormatting.GRAY);
    }
}
