package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.EnrichmentMassBalance;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.fuel.MoxMassBalance;
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
 *
 * <h2>The MOX targets</h2>
 * Three more targets make mixed oxide from the reprocessing plant's output: plutonium nuggets, depleted uranium as
 * the matrix, and steel ({@link #MOX_TARGETS}). The plutonium fraction is the MOX fuel type's own nominal enrichment,
 * the plutonium and the matrix come from {@link MoxMassBalance}, and nothing is returned (the matrix is part of the
 * product). They are not on {@link #TARGETS}: that list is the uranium ladder, whose indexes the selector command and
 * the saves know, and a MOX target is selected by its own id ({@code mox_lean}, {@code mox_standard}, {@code mox_rich};
 * the middle one makes the fuel type {@code mox}). On a MOX target the machine's input slot takes plutonium and depleted
 * uranium instead of uranium.
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

    /**
     * The MOX targets, by id, lowest plutonium first, and the fuel type each makes. The id of the standard grade is not
     * its fuel type's name on purpose: {@code mox} itself is not a target (the uranium ladder's tests rely on that, and
     * so do saves).
     */
    public static final List<String> MOX_TARGETS = List.of("mox_lean", "mox_standard", "mox_rich");
    private static final List<String> MOX_FUELS = List.of("mox_lean", "mox", "mox_rich");

    /** Every target the selector offers, the uranium ladder first, then the MOX grades: the index the screen counts in. */
    public static final List<String> LADDER = java.util.stream.Stream.concat(TARGETS.stream(), MOX_TARGETS.stream()).toList();

    public static final TagKey<Item> URANIUM_INGOTS = tag("c", "ingots/uranium");
    /** The matrix of a MOX assembly, and what the uranium route returns. */
    public static final TagKey<Item> DEPLETED_INGOTS = tag("c", "ingots/depleted_uranium");
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
     * @param plutoniumNuggets plutonium nuggets consumed (a MOX target; 0 for a uranium one)
     * @param matrix    depleted uranium ingots consumed as the matrix (a MOX target; 0 for a uranium one)
     */
    public record Cost(String fuelType, int ingots, int steel, int tails, int workTicks, int plutoniumNuggets, int matrix) {

        /** A uranium target's cost. */
        public Cost(String fuelType, int ingots, int steel, int tails, int workTicks) {
            this(fuelType, ingots, steel, tails, workTicks, 0, 0);
        }

        /** True for a MOX target, whose inputs are plutonium and depleted uranium instead of uranium. */
        public boolean isMox() {
            return plutoniumNuggets > 0;
        }

        /** The uranium in half ingots. */
        public int units() {
            return ingots * UNITS_PER_INGOT;
        }
    }

    /** True for a name the selector offers: on the uranium ladder or one of the MOX grades. */
    public static boolean isTarget(String name) {
        return name != null && (TARGETS.contains(name) || MOX_TARGETS.contains(name));
    }

    /** True for one of the MOX targets. */
    public static boolean isMoxTarget(String name) {
        return name != null && MOX_TARGETS.contains(name);
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
        if (isMoxTarget(targetName)) {
            return moxCost(targetName);
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

    /** The cost of a MOX target: the fuel type's own plutonium fraction through {@link MoxMassBalance}; null if it cannot be made. */
    private static Cost moxCost(String targetName) {
        FuelType type = FuelTypes.byNameOrPreset(MOX_FUELS.get(MOX_TARGETS.indexOf(targetName)));
        if (type == null) {
            return null;
        }
        try {
            int nuggets = MoxMassBalance.plutoniumNuggets(PRODUCT_INGOTS, type.nominalEnrichmentWeightFraction());
            int matrix = MoxMassBalance.matrixIngots(PRODUCT_INGOTS, nuggets);
            return new Cost(type.name(), 0, STEEL_PER_ASSEMBLY, 0, BASE_WORK_TICKS + TICKS_PER_INGOT * matrix, nuggets, matrix);
        } catch (IllegalArgumentException cannotMake) {
            return null;
        }
    }

    /** Plutonium nuggets one item of this stack is worth (an ingot is nine); 0 when it is not plutonium. */
    public static int plutoniumNuggets(ItemStack stack) {
        return Reprocessing.plutoniumNuggets(stack);
    }

    /** True for depleted uranium, which is the matrix of a MOX assembly. */
    public static boolean isMatrix(ItemStack stack) {
        return !stack.isEmpty() && stack.is(DEPLETED_INGOTS);
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
