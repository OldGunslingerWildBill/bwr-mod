package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.EnrichmentMassBalance;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The fuel fabricator's item route: what an assembly costs when it is made from
 * ingots, which ingots count, and what comes back.
 *
 * <h2>The cost is a mass balance, not a price list</h2>
 * An assembly is {@value #PRODUCT_INGOTS} ingots' worth of enriched product. The
 * natural uranium needed to make that much product at the target enrichment is
 * the enrichment plant's mass balance, {@code feed/product = (xp - xt)/(xf - xt)}
 * with natural feed (0.711 %) and 0.25 % tails, which lives in
 * {@link EnrichmentMassBalance} and nowhere else. A 3.5 % assembly therefore
 * costs 4 x 7.05 = 29 ingots, a 20 % one 172, and the uranium that did not
 * become product comes back as depleted uranium ingots (24 and 167). There is no
 * price table: change a fuel's nominal enrichment in a datapack and its cost
 * follows.
 *
 * <p>Steel is the cladding and structure, a fixed {@value #STEEL_PER_ASSEMBLY}
 * ingots whatever the enrichment. Time grows with the amount of uranium
 * processed.
 *
 * <h2>What counts as uranium</h2>
 * By item tag, so any mod's uranium works: an ingot ({@code c:ingots/uranium}) and
 * a raw ore ({@code c:raw_materials/uranium}, which smelts to one ingot) each
 * count as one ingot; Mekanism's yellow cake (the optional tag
 * {@code bwr:fabricator_feed/yellow_cake}) counts as half, because a Mekanism
 * enrichment chamber makes two of it from one ingot. Steel is
 * {@code c:ingots/steel}. Amounts are kept in half ingots so the cake needs no
 * fractions.
 *
 * <p>Nothing here names Mekanism, so the item route works with or without it
 * (given some other mod's uranium and steel). The chemical route is separate and
 * unchanged.
 */
public final class FuelItemRoute {

    private FuelItemRoute() {
    }

    /** Ingots of enriched product in one assembly: the base every cost is a multiple of. */
    public static final int PRODUCT_INGOTS = 4;

    /** Steel ingots of cladding and structure per assembly. */
    public static final int STEEL_PER_ASSEMBLY = 4;

    /** Half ingots in an ingot: the unit the machine counts uranium in. */
    public static final int UNITS_PER_INGOT = 2;

    /** Fabrication time: this plus {@link #TICKS_PER_INGOT} for every ingot of uranium processed. */
    public static final int BASE_WORK_TICKS = 100;
    public static final int TICKS_PER_INGOT = 3;

    /** The target a fabricator has before anyone picks one: the ordinary BWR fuel. */
    public static final String DEFAULT_TARGET = "leu";

    /**
     * The uranium ladder the selector offers, lowest enrichment first. The retired
     * 1.2 % and 1.4 % steps are not on it (see {@code FuelTypes#catalogue}), so the
     * machine does not start making a fuel the game has stopped offering.
     */
    public static final List<String> TARGETS = List.of(
            "natural_uranium", "uranium_27", "leu", "uranium_495", "uranium_8", "uranium_1975", "heu");

    public static final TagKey<Item> URANIUM_INGOTS = tag("c", "ingots/uranium");
    public static final TagKey<Item> RAW_URANIUM = tag("c", "raw_materials/uranium");
    public static final TagKey<Item> YELLOW_CAKE = tag(BwrMod.MOD_ID, "fabricator_feed/yellow_cake");
    public static final TagKey<Item> STEEL_INGOTS = tag("c", "ingots/steel");

    private static TagKey<Item> tag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /**
     * What one assembly of a target costs and returns.
     *
     * @param fuelType  the fuel type the assembly is stamped with
     * @param ingots    uranium ingots (or the same in raw ore) consumed
     * @param steel     steel ingots consumed
     * @param tails     depleted uranium ingots returned
     * @param workTicks ticks of work at full power
     */
    public record Cost(String fuelType, int ingots, int steel, int tails, int workTicks) {

        /** The uranium in half ingots. */
        public int units() {
            return ingots * UNITS_PER_INGOT;
        }
    }

    /** True for a name on the ladder. */
    public static boolean isTarget(String name) {
        return name != null && TARGETS.contains(name);
    }

    /**
     * The cost of an assembly of this fuel, or null if the name is not on the
     * ladder or its enrichment is one an enrichment plant could not make (a
     * datapack that retuned it below natural).
     */
    public static Cost cost(String targetName) {
        if (!isTarget(targetName)) {
            return null;
        }
        FuelType type = FuelTypes.byNameOrPreset(targetName);
        if (type == null) {
            return null;
        }
        double assay = type.nominalEnrichmentWeightFraction();
        try {
            int ingots = EnrichmentMassBalance.feedUnits(PRODUCT_INGOTS, assay);
            int tails = EnrichmentMassBalance.tailsUnits(PRODUCT_INGOTS, assay);
            return new Cost(type.name(), ingots, STEEL_PER_ASSEMBLY, tails,
                    BASE_WORK_TICKS + TICKS_PER_INGOT * ingots);
        } catch (IllegalArgumentException cannotMake) {
            return null;
        }
    }

    // --- what the inputs are worth ------------------------------------------

    /**
     * Test hooks. A GameTest run without Mekanism has no uranium or steel item
     * to put in, so a test can say which plain items stand in for them; null in
     * normal play. Never set outside a test.
     */
    private static volatile ToIntFunction<ItemStack> uraniumOverride;
    private static volatile Predicate<ItemStack> steelOverride;

    public static void overrideInputsForTest(ToIntFunction<ItemStack> uranium, Predicate<ItemStack> steel) {
        uraniumOverride = uranium;
        steelOverride = steel;
    }

    /** Half ingots of uranium one item of this stack is worth; 0 when it is not uranium. */
    public static int uraniumUnits(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        ToIntFunction<ItemStack> override = uraniumOverride;
        if (override != null) {
            return override.applyAsInt(stack);
        }
        if (stack.is(URANIUM_INGOTS) || stack.is(RAW_URANIUM)) {
            return UNITS_PER_INGOT;
        }
        return stack.is(YELLOW_CAKE) ? 1 : 0;
    }

    public static boolean isSteel(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Predicate<ItemStack> override = steelOverride;
        return override != null ? override.test(stack) : stack.is(STEEL_INGOTS);
    }
}
