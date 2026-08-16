package dev.bwr.mod.fuel;

import dev.bwr.mod.registry.BwrBlockEntities;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.BasicChemicalTank;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.IChemicalTank;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Mekanism side of the fuel fabricator. SPEC 2.4's integration point.
 *
 * <h2>Never loaded without Mekanism</h2>
 * This class names Mekanism types directly, so the JVM must never be asked to
 * resolve it on an install that lacks them. The {@code isLoaded("mekanism")}
 * test therefore lives in {@link dev.bwr.mod.BwrMod}, which is Mekanism-free,
 * and only a passing test causes anything to reference this class — the same
 * arrangement {@code BwrPeripheralSupport} uses for CC:Tweaked, and for the same
 * reason.
 *
 * <h2>Two tanks, and why</h2>
 * A matrix tank takes the bulk heavy metal coming off the enrichment chain
 * (uranium oxide, uranium hexafluoride) and a fissile tank takes the enriched
 * product (fissile fuel, plutonium). The fabricator draws from both in
 * proportion to what is stored, so the enrichment of the bundle is the ratio the
 * player's plumbing maintains between the two pipes. Trickle fissile fuel in and
 * you get LEU; run it hard and you get HEU. There is no recipe and no setting
 * separating those two outcomes, which is precisely the point of hanging off
 * Mekanism's enrichment chain rather than writing our own.
 *
 * <h2>The numbers below are conversion factors, not balance</h2>
 * {@code heavyMetalKgPerMb} converts Mekanism's millibuckets into kilograms of
 * heavy metal; {@code fissileWeightFraction} says how much of that heavy metal
 * is fissile. Natural uranium is 0.72% U-235 because that is what natural
 * uranium is. Nothing here scales reactivity, and nothing here makes one
 * feedstock better or worse than another except by what it physically contains.
 */
public final class MekanismFuelFeed implements FuelFeed, IChemicalHandler, IContentsListener {

    /**
     * Mekanism's own block capability, looked up by the name Mekanism registers
     * it under so that its pipes and pumps recognise our tanks as a destination.
     *
     * <p>{@code BlockCapability.createSided} is idempotent per name, so whichever
     * of us runs first creates the instance and the other gets the same one.
     * Resolving it by name rather than by referencing {@code Capabilities}
     * directly is not a choice: that class lives in Mekanism's common package,
     * and we compile against the api classifier only.
     *
     * <p>Null if Mekanism ever registers this name against a different interface.
     * A fabricator that cannot be piped into is a bad day; a crash on world load
     * for everyone with Mekanism installed is a worse one, so the failure is
     * logged and degraded rather than thrown.
     */
    private static final BlockCapability<IChemicalHandler, Direction> CHEMICAL_HANDLER =
            createChemicalHandlerCapability();

    private static BlockCapability<IChemicalHandler, Direction> createChemicalHandlerCapability() {
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath(
                MekanismAPI.MEKANISM_MODID, "chemical_handler");
        try {
            return BlockCapability.createSided(name, IChemicalHandler.class);
        } catch (RuntimeException e) {
            MekanismAPI.logger.error(
                    "bwr: could not obtain the {} block capability, so the fuel fabricator "
                            + "cannot be piped into. Feed it by hand.", name, e);
            return null;
        }
    }

    /** Per-tank capacity, mB. */
    private static final long TANK_CAPACITY_MB = 64_000L;

    /** Below this fissile weight fraction a feedstock is matrix, above it is enrichment product. */
    private static final double FISSILE_TANK_THRESHOLD = 0.5;

    /**
     * What one millibucket of a Mekanism chemical is worth as fuel.
     *
     * @param heavyMetalKgPerMb    kilograms of heavy metal per millibucket
     * @param fissileWeightFraction fissile share of that heavy metal
     * @param plutonium            true when the heavy metal is plutonium rather than uranium
     */
    private record Feedstock(double heavyMetalKgPerMb, double fissileWeightFraction, boolean plutonium) {

        boolean isFissileProduct() {
            return fissileWeightFraction >= FISSILE_TANK_THRESHOLD;
        }
    }

    /** Natural uranium: 0.72% U-235, the isotopic abundance, not a game number. */
    private static final double NATURAL_URANIUM_ENRICHMENT = 0.0072;

    /**
     * One bundle is 180 kg of heavy metal and 9000 mB of feed at this rate, which
     * puts a fabricator squarely within reach of a mid-game Mekanism uranium
     * line without trivialising it.
     */
    private static final double HEAVY_METAL_KG_PER_MB = 0.02;

    private static final Map<ResourceLocation, Feedstock> FEEDSTOCKS = feedstocks();

    private static Map<ResourceLocation, Feedstock> feedstocks() {
        Map<ResourceLocation, Feedstock> map = new LinkedHashMap<>();
        // Bulk heavy metal, still at natural isotopic composition.
        map.put(mek("uranium_oxide"),
                new Feedstock(HEAVY_METAL_KG_PER_MB, NATURAL_URANIUM_ENRICHMENT, false));
        map.put(mek("uranium_hexafluoride"),
                new Feedstock(HEAVY_METAL_KG_PER_MB, NATURAL_URANIUM_ENRICHMENT, false));
        // The enrichment chain's product: fissile, and what the player rations.
        map.put(mek("fissile_fuel"),
                new Feedstock(HEAVY_METAL_KG_PER_MB, 1.0, false));
        map.put(mek("plutonium"),
                new Feedstock(HEAVY_METAL_KG_PER_MB, 1.0, true));
        return Map.copyOf(map);
    }

    private static ResourceLocation mek(String path) {
        return ResourceLocation.fromNamespaceAndPath(MekanismAPI.MEKANISM_MODID, path);
    }

    // -----------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------

    /** Makes chemical-backed feeds available to every fabricator built from now on. */
    public static void install() {
        FuelFeeds.install(MekanismFuelFeed::new);
    }

    /** Puts our tanks on Mekanism's chemical capability so their pipes can fill us. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        if (CHEMICAL_HANDLER == null) {
            return;
        }
        event.registerBlockEntity(
                CHEMICAL_HANDLER,
                BwrBlockEntities.FUEL_FABRICATOR.get(),
                (be, side) -> be.feed() instanceof MekanismFuelFeed mek ? mek : null);
    }

    // -----------------------------------------------------------------
    // State
    // -----------------------------------------------------------------

    // The "modern" tank factories are the stack-based ones; the Chemical-based
    // overloads are deprecated for removal in Mekanism 10.7.
    private final IChemicalTank matrixTank =
            BasicChemicalTank.inputModern(TANK_CAPACITY_MB, MekanismFuelFeed::isMatrixFeed, this);
    private final IChemicalTank fissileTank =
            BasicChemicalTank.inputModern(TANK_CAPACITY_MB, MekanismFuelFeed::isFissileFeed, this);

    private final List<IChemicalTank> tanks = List.of(matrixTank, fissileTank);

    private Runnable onChanged = () -> {
    };

    private static Feedstock feedstockOf(ChemicalStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation name = MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical());
        return name == null ? null : FEEDSTOCKS.get(name);
    }

    private static boolean isMatrixFeed(ChemicalStack stack) {
        Feedstock feedstock = feedstockOf(stack);
        return feedstock != null && !feedstock.isFissileProduct();
    }

    private static boolean isFissileFeed(ChemicalStack stack) {
        Feedstock feedstock = feedstockOf(stack);
        return feedstock != null && feedstock.isFissileProduct();
    }

    @Override
    public void onContentsChanged() {
        onChanged.run();
    }

    @Override
    public void setChangeListener(Runnable listener) {
        this.onChanged = listener != null ? listener : () -> {
        };
    }

    // -----------------------------------------------------------------
    // FuelFeed
    // -----------------------------------------------------------------

    /**
     * Splits the requested draw between the two tanks in proportion to the
     * heavy metal each is holding. That proportion is the enrichment: the
     * machine has no opinion about what it should be, it simply consumes what
     * the player supplied at the ratio they supplied it.
     */
    @Override
    public Draw draw(double maxHeavyMetalKg) {
        if (!(maxHeavyMetalKg > 0.0)) {
            return Draw.NOTHING;
        }
        double availableMatrix = availableHeavyMetalKg(matrixTank);
        double availableFissile = availableHeavyMetalKg(fissileTank);
        double available = availableMatrix + availableFissile;
        if (!(available > 0.0)) {
            return Draw.NOTHING;
        }
        double take = Math.min(maxHeavyMetalKg, available);
        double fromMatrix = take * (availableMatrix / available);
        double fromFissile = take - fromMatrix;
        return drawFrom(matrixTank, fromMatrix, MATRIX)
                .plus(drawFrom(fissileTank, fromFissile, FISSILE));
    }

    private static double availableHeavyMetalKg(IChemicalTank tank) {
        Feedstock feedstock = feedstockOf(tank.getStack());
        return feedstock == null ? 0.0 : tank.getStored() * feedstock.heavyMetalKgPerMb();
    }

    private static final int MATRIX = 0;
    private static final int FISSILE = 1;

    /**
     * Fractional millibuckets owed to each tank, carried between draws.
     *
     * <p>Chemicals come in whole millibuckets and a tick's share of the minority
     * tank is routinely less than one, so rounding it up every tick would draw
     * far more of it than the player is actually supplying — a 30:1 feed would
     * fabricate 4.5% enriched fuel instead of 3.2%, and the machine would be
     * quietly generous with the most expensive input in the game. Carrying the
     * remainder makes the long-run draw ratio exactly the supply ratio.
     *
     * <p>Deliberately not serialised: the whole carry is under one millibucket
     * per tank, which is 0.02 kg out of a 180 kg bundle.
     */
    private final double[] carryMb = new double[2];

    private Draw drawFrom(IChemicalTank tank, double heavyMetalKg, int index) {
        if (!(heavyMetalKg > 0.0)) {
            return Draw.NOTHING;
        }
        Feedstock feedstock = feedstockOf(tank.getStack());
        if (feedstock == null) {
            carryMb[index] = 0.0;
            return Draw.NOTHING;
        }
        double wantedMb = heavyMetalKg / feedstock.heavyMetalKgPerMb() + carryMb[index];
        long whole = (long) Math.floor(wantedMb);
        if (whole <= 0L) {
            carryMb[index] = wantedMb;
            return Draw.NOTHING;
        }
        long available = tank.getStored();
        if (whole >= available) {
            // Draining the tank dry ends the debt: there is nothing to owe it.
            whole = available;
            carryMb[index] = 0.0;
        } else {
            carryMb[index] = wantedMb - whole;
        }
        ChemicalStack taken = tank.extract(whole, Action.EXECUTE, AutomationType.INTERNAL);
        if (taken.isEmpty()) {
            return Draw.NOTHING;
        }
        double drawnKg = taken.getAmount() * feedstock.heavyMetalKgPerMb();
        return new Draw(drawnKg,
                drawnKg * feedstock.fissileWeightFraction(),
                feedstock.plutonium() ? drawnKg : 0.0);
    }

    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(describe("Matrix", matrixTank));
        out.add(describe("Fissile", fissileTank));
        return out;
    }

    private static String describe(String label, IChemicalTank tank) {
        if (tank.isEmpty()) {
            return label + " tank empty";
        }
        ResourceLocation name = MekanismAPI.CHEMICAL_REGISTRY.getKey(tank.getStack().getChemical());
        return String.format(Locale.ROOT, "%s tank: %,d / %,d mB of %s (%.1f kg HM)",
                label, tank.getStored(), tank.getCapacity(),
                name == null ? "?" : name.getPath(), availableHeavyMetalKg(tank));
    }

    @Override
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Matrix", matrixTank.serializeNBT(registries));
        tag.put("Fissile", fissileTank.serializeNBT(registries));
        return tag;
    }

    @Override
    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        if (tag.contains("Matrix")) {
            matrixTank.deserializeNBT(registries, tag.getCompound("Matrix"));
        }
        if (tag.contains("Fissile")) {
            fissileTank.deserializeNBT(registries, tag.getCompound("Fissile"));
        }
    }

    // -----------------------------------------------------------------
    // IChemicalHandler — what Mekanism's pipes see
    // -----------------------------------------------------------------

    @Override
    public int getChemicalTanks() {
        return tanks.size();
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
        return inRange(tank) ? tanks.get(tank).getStack() : ChemicalStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {
        if (inRange(tank)) {
            tanks.get(tank).setStack(stack);
        }
    }

    @Override
    public long getChemicalTankCapacity(int tank) {
        return inRange(tank) ? tanks.get(tank).getCapacity() : 0L;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
        return inRange(tank) && tanks.get(tank).isValid(stack);
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        return inRange(tank)
                ? tanks.get(tank).insert(stack, action, AutomationType.EXTERNAL)
                : stack;
    }

    /**
     * Always refuses. Feed goes in and bundles come out; there is no path back
     * out of the tanks, so an automation mistake cannot quietly drain a player's
     * enriched uranium into a waste barrel.
     */
    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
        return ChemicalStack.EMPTY;
    }

    private boolean inRange(int tank) {
        return tank >= 0 && tank < tanks.size();
    }
}
