package dev.bwr.mod.fuel;

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
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        FuelAssemblyData data = FuelAssemblies.dataOf(stack);
        FuelAssembly assembly = FuelAssemblies.toCore(data);
        FuelType type = assembly.fuelType();

        tooltip.add(line(String.format(Locale.ROOT, "%s, %.2f%% fissile, %.0f kg HM",
                type.name(), data.enrichmentWeightFraction() * 100.0, data.heavyMetalMassKg())));

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
            tooltip.add(Component.literal("fuel type '" + data.fuelTypeName()
                            + "' is not loaded; substituted " + type.name())
                    .withStyle(ChatFormatting.RED));
        } else if (!FuelTypes.isKnown(data.fuelTypeName())) {
            tooltip.add(Component.literal("fuel type '" + data.fuelTypeName()
                            + "' is not in this world's fuel table; showing built-in data")
                    .withStyle(ChatFormatting.YELLOW));
        }

        tooltip.add(line(String.format(Locale.ROOT, "Burnup %,.0f MWd/t  (%,.0f MWd produced)",
                data.burnupMwdPerTonne(), FuelAssemblies.energyProducedMwd(assembly))));

        tooltip.add(line(String.format(Locale.ROOT, "k-inf %.4f  (fresh %.4f)",
                assembly.kInf(), assembly.baseKInf())));

        double remaining = FuelAssemblies.exposureRemainingMwdPerTonne(assembly);
        if (remaining <= 0.0) {
            tooltip.add(line("Below k-inf 1.0 on its own"));
        } else if (remaining >= FuelAssemblies.EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE) {
            // A very highly enriched bundle can outlast the scan. Saying so is
            // honest; printing the scan limit as if it were the answer is not.
            tooltip.add(line(String.format(Locale.ROOT,
                    "Exposure to k-inf 1.0: over %,.0f MWd/t",
                    FuelAssemblies.EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE)));
        } else {
            tooltip.add(line(String.format(Locale.ROOT,
                    "Exposure to k-inf 1.0: %,.0f MWd/t", remaining)));
        }

        if (type.gadContentWeightFraction() > 0.0) {
            tooltip.add(line(String.format(Locale.ROOT, "Gadolinia %.0f%% remaining (holding %.4f dk)",
                    data.gadoliniaRemainingFraction() * 100.0, assembly.gadPenaltyKInf())));
        }

        // Beta is the number that decides how much control margin the core has.
        // It is on the tooltip because a player loading MOX into a high-flux
        // position deserves to have seen 0.0035 first, not because anything in
        // the mod reacts to it.
        tooltip.add(line(String.format(Locale.ROOT, "beta %.5f, prompt lifetime %.1e s",
                type.beta(), type.promptLifetimeSeconds())));
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }
}
