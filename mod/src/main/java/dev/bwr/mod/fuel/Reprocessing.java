package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * The reprocessing plant's rules in one place: which assemblies it takes, how a spent one splits, what the plutonium
 * and waste items are, and how long and how much power it takes.
 *
 * <p>The split itself is {@link SpentFuel} in the core module (burnup, enrichment and fuel type in, whole grains
 * out); this class decides which fuels count as plutonium-started or are refused and maps the grains onto items.
 * Three items come out, all counted in ninths of an ingot (a nugget of plutonium, a nugget's worth of waste) except
 * the uranium, which comes back as ingots:
 * <ul>
 *   <li>{@code bwr:plutonium_nugget}: the plutonium left;</li>
 *   <li>{@code bwr:depleted_uranium}: the uranium left. Reprocessed uranium keeps a little more U-235 than the 0.25 %
 *       tails of the fabricator's mass balance, so returning it as depleted uranium throws that surplus away
 *       (it can only lose, never gain), which keeps the loop from minting fissile and keeps one uranium item;</li>
 *   <li>{@code bwr:fission_product_waste}: the fissioned mass, never craftable and used by nothing.</li>
 * </ul>
 */
public final class Reprocessing {

    private Reprocessing() {
    }

    /** Ticks of work for one assembly at full power. */
    public static final int WORK_TICKS = 200;

    /** Energy per tick while working, FE: 30,000 FE an assembly. */
    public static final int ENERGY_PER_TICK = 150;

    /** Grains in one nugget, one waste item and, times nine, one ingot (see {@link SpentFuel}). */
    public static final long GRAINS_PER_NUGGET = SpentFuel.GRAINS_PER_NINTH;
    public static final long GRAINS_PER_INGOT = SpentFuel.GRAINS_PER_INGOT;

    public static final TagKey<Item> PLUTONIUM_INGOTS = tag("c", "ingots/plutonium");
    public static final TagKey<Item> PLUTONIUM_NUGGETS = tag("c", "nuggets/plutonium");

    private static TagKey<Item> tag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** What a fuel is, as far as reprocessing is concerned. */
    public enum FuelClass {
        /** Uranium start: the plutonium is all bred. */
        URANIUM,
        /** Mixed oxide or plutonium fuel: it starts with plutonium. */
        PLUTONIUM,
        /** Thorium and U-233: no plutonium chain; the plant does not take it. */
        THORIUM
    }

    /**
     * Classifies a fuel type by name. The fuel names are the item layer's business ({@code FuelType} documents that):
     * the mixed-oxide and plutonium families are {@code mox*} and {@code plutonium*}, thorium is {@code thorium*},
     * everything else (including a datapack's own fuel) is a uranium fuel.
     */
    public static FuelClass classOf(String fuelTypeName) {
        String name = fuelTypeName == null ? "" : fuelTypeName.toLowerCase(Locale.ROOT);
        if (name.startsWith("thorium")) {
            return FuelClass.THORIUM;
        }
        return name.startsWith("mox") || name.startsWith("plutonium") ? FuelClass.PLUTONIUM : FuelClass.URANIUM;
    }

    /** True for a fuel assembly the plant will take: irradiated (burnup above zero) and not thorium. */
    public static boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return false;
        }
        return accepts(FuelAssemblies.dataOf(stack));
    }

    public static boolean accepts(FuelAssemblyData data) {
        return data.isIrradiated() && classOf(data.fuelTypeName()) != FuelClass.THORIUM;
    }

    /** What a spent assembly splits into, in grains; the sum is always {@link SpentFuel#ASSEMBLY_GRAINS}. */
    public static SpentFuel.Split split(FuelAssemblyData data) {
        FuelAssembly assembly = FuelAssemblies.toCore(data);
        return SpentFuel.split(assembly, classOf(data.fuelTypeName()) == FuelClass.PLUTONIUM);
    }

    /** Plutonium nuggets one stack item is worth: a nugget 1, an ingot 9, 0 for anything else. */
    public static int plutoniumNuggets(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(PLUTONIUM_INGOTS)) {
            return SpentFuel.NINTHS_PER_INGOT;
        }
        return stack.is(PLUTONIUM_NUGGETS) ? 1 : 0;
    }
}
