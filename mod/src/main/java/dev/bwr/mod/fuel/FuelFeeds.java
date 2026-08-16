package dev.bwr.mod.fuel;

import java.util.function.Supplier;

/**
 * Indirection that lets the fabricator acquire a Mekanism-backed
 * {@link FuelFeed} without ever naming a Mekanism class.
 *
 * <p>Same shape as {@code BwrPeripheralSupport}: the
 * {@code ModList.get().isLoaded(...)} test happens in {@link dev.bwr.mod.BwrMod},
 * which is Mekanism-free, and only if it passes does anything reference
 * {@code MekanismFuelFeed}. Because the check and the reference live in
 * different classes, a JVM running without Mekanism never has to resolve a
 * single Mekanism type — which is the actual requirement, stronger than merely
 * not calling into them.
 */
public final class FuelFeeds {

    private FuelFeeds() {
    }

    private static Supplier<FuelFeed> factory = () -> FuelFeed.NONE;
    private static boolean chemicalFeedInstalled;

    /** Installs the chemical-backed feed. Called only when Mekanism is present. */
    public static void install(Supplier<FuelFeed> supplier) {
        factory = supplier;
        chemicalFeedInstalled = true;
    }

    /** A feed for one fabricator. {@link FuelFeed#NONE} unless something installed better. */
    public static FuelFeed create() {
        return factory.get();
    }

    /** Whether a real chemical feed is available. Drives the readout, not any behaviour. */
    public static boolean hasChemicalFeed() {
        return chemicalFeedInstalled;
    }
}
