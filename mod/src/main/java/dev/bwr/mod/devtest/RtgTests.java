package dev.bwr.mod.devtest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.core.isotope.IsotopeExtraction;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.isotope.CarriedIsotopes;
import dev.bwr.mod.isotope.IsotopeSeparatorBlockEntity;
import dev.bwr.mod.isotope.Isotopes;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.isotope.RtgFuel;
import dev.bwr.mod.isotope.RtgFuels;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The RTG and the isotope separator (RTG.md): extraction from real spent fuel with the mass accounted to the grain, the
 * RTG's power into a real neighbour (and nothing without capsules), decay in world time, the dose of a carried capsule and
 * of the block, the saves, old reprocessing saves, the fuel list as data, and the recipes. Tests that need the block's own
 * ticker are asynchronous and use relative positions; the others are synchronous and use their own absolute positions.
 */
@GameTestHolder("bwrrtg")
@PrefixGameTestTemplate(false)
public final class RtgTests {

    private static final String NS = "bwrrtg";
    /** Their own batch: they register radiation sources and the shared game clock is moved by other tests, so they do not run beside them. */
    private static final String BATCH = "bwrrtg";

    private static ItemStack spent(String fuel, double enrichment, double burnup) {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData(fuel, enrichment, 180.0, burnup, 1.0));
    }

    private static <T> T place(ServerLevel l, BlockPos p, net.minecraft.world.level.block.Block block, Class<T> type) {
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, block.defaultBlockState(), 2);
        return type.cast(l.getBlockEntity(p));
    }

    private static void tickPlant(ServerLevel l, BlockPos p, ReprocessingPlantBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    private static void tickSeparator(ServerLevel l, BlockPos p, IsotopeSeparatorBlockEntity be) {
        be.energy().receiveEnergy(4_000, false);
        IsotopeSeparatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    /** Grains in everything the separator holds and has given out. */
    private static long grainsAccounted(IsotopeSeparatorBlockEntity be) {
        long cap = Isotopes.CAPSULE_GRAINS;
        return be.items().getStackInSlot(IsotopeSeparatorBlockEntity.STRONTIUM).getCount() * cap + be.strontiumGrains()
                + be.items().getStackInSlot(IsotopeSeparatorBlockEntity.CAESIUM).getCount() * cap + be.caesiumGrains()
                + be.items().getStackInSlot(IsotopeSeparatorBlockEntity.AMERICIUM).getCount() * cap + be.americiumGrains()
                + be.items().getStackInSlot(IsotopeSeparatorBlockEntity.STRIPPED).getCount() * Isotopes.GRAINS_PER_WASTE_ITEM + be.strippedGrains();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Extraction

    /** Real spent LEU through the real plant; its waste through the separator; the mass adds up to the grain. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 200)
    public static void extractionFromRealSpentFuelYieldsCapsulesAndConservesMass(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos pp = new BlockPos(16000, 210, 16000);
        var plant = place(l, pp, BwrBlocks.REPROCESSING_PLANT.get(), ReprocessingPlantBlockEntity.class);
        var topView = l.getCapability(Capabilities.ItemHandler.BLOCK, pp, Direction.UP);
        for (int i = 0; i < 14; i++) {
            h.assertTrue(topView.insertItem(0, spent("leu", 0.035, 45000.0), false).isEmpty(), "the plant refused spent fuel");
            int guard = 0;
            while (!plant.input().getStackInSlot(0).isEmpty() && guard++ < 1000) {
                tickPlant(l, pp, plant);
            }
        }
        int wasteItems = plant.wasteOut().getStackInSlot(0).getCount();
        long wasteGrainsMade = 14 * (long) Reprocessing.split(new FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0)).fissionProducts();
        h.assertTrue(wasteItems == wasteGrainsMade / Isotopes.GRAINS_PER_WASTE_ITEM, "the plant made " + wasteItems + " waste items from " + wasteGrainsMade + " grains");
        h.assertTrue(wasteItems >= 20, "too little waste to account: " + wasteItems);
        ItemStack waste = plant.wasteOut().extractItem(0, 64, false);
        h.assertTrue(waste.is(BwrItems.FISSION_PRODUCT_WASTE.get()), "the plant's waste is not waste");
        plant.removalDrops();
        l.removeBlock(pp, false);

        BlockPos sp = new BlockPos(16010, 210, 16000);
        var sep = place(l, sp, BwrBlocks.ISOTOPE_SEPARATOR.get(), IsotopeSeparatorBlockEntity.class);
        var sepTop = l.getCapability(Capabilities.ItemHandler.BLOCK, sp, Direction.UP);
        // the separator takes waste and nothing else, and not its own stripped waste
        h.assertTrue(!sepTop.insertItem(0, new ItemStack(BwrItems.STRIPPED_WASTE.get()), false).isEmpty(), "stripped waste was taken back in");
        h.assertTrue(!sepTop.insertItem(0, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get()), false).isEmpty(), "plutonium was taken");
        h.assertTrue(sepTop.insertItem(0, waste, false).isEmpty(), "the separator refused waste");
        int ticks = 0;
        while (!sep.items().getStackInSlot(0).isEmpty() && ticks < 20_000) {
            tickSeparator(l, sp, sep);
            ticks++;
        }
        h.assertTrue(ticks == wasteItems * Isotopes.WORK_TICKS, "took " + ticks + " ticks for " + wasteItems + " items, not " + wasteItems * Isotopes.WORK_TICKS);
        long in = wasteItems * Isotopes.GRAINS_PER_WASTE_ITEM;
        long out = grainsAccounted(sep);
        h.assertTrue(out == in, "mass: " + in + " grains in, " + out + " accounted");
        IsotopeExtraction.Split expected = IsotopeExtraction.split(in);
        int sr = sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.STRONTIUM).getCount();
        int cs = sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.CAESIUM).getCount();
        int am = sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.AMERICIUM).getCount();
        h.assertTrue(sr == expected.strontium() / Isotopes.CAPSULE_GRAINS && sr >= 1, "strontium capsules " + sr + " for " + expected.strontium() + " grains");
        h.assertTrue(cs == expected.caesium() / Isotopes.CAPSULE_GRAINS && cs >= 1, "caesium capsules " + cs + " for " + expected.caesium() + " grains");
        h.assertTrue(am == expected.americium() / Isotopes.CAPSULE_GRAINS && am >= 1, "americium capsules " + am + " for " + expected.americium() + " grains");
        h.assertTrue(sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.STRONTIUM).is(BwrItems.STRONTIUM_90_CAPSULE.get())
                && sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.CAESIUM).is(BwrItems.CAESIUM_137_CAPSULE.get())
                && sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.AMERICIUM).is(BwrItems.AMERICIUM_241_CAPSULE.get()), "wrong capsule kinds");
        // the sides: a hopper below takes the outputs; the input cannot be pulled out
        var bottom = l.getCapability(Capabilities.ItemHandler.BLOCK, sp, Direction.DOWN);
        h.assertTrue(bottom.extractItem(IsotopeSeparatorBlockEntity.STRONTIUM, 64, false).getCount() == sr, "a hopper below did not collect the strontium");
        h.assertTrue(bottom.insertItem(0, new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get()), false).getCount() == 1, "the bottom took waste");
        System.out.println("RTG extraction: " + wasteItems + " waste items -> " + sr + " Sr-90, " + cs + " Cs-137, " + am + " Am-241 capsules, "
                + "stripped " + sep.items().getStackInSlot(IsotopeSeparatorBlockEntity.STRIPPED).getCount() + " + fractions; " + in + " grains in, " + out + " accounted");
        sep.removalDrops();
        l.removeBlock(sp, false);
        h.succeed();
    }

    /** The fractions carry from item to item and survive a save; empty saves write nothing extra. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 200)
    public static void separatorFractionsCarryAndSave(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos sp = new BlockPos(16020, 210, 16000);
        var sep = place(l, sp, BwrBlocks.ISOTOPE_SEPARATOR.get(), IsotopeSeparatorBlockEntity.class);
        var tag = sep.saveWithFullMetadata(l.registryAccess());
        for (String key : new String[] {"WorkTicks", "StrontiumGrains", "CaesiumGrains", "AmericiumGrains", "StrippedGrains", "Items"}) {
            h.assertTrue(!tag.contains(key), "an empty separator wrote " + key);
        }
        sep.items().setStackInSlot(0, new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get(), 3));
        for (int i = 0; i < 2 * Isotopes.WORK_TICKS + 40; i++) {
            tickSeparator(l, sp, sep);
        }
        long sr = sep.strontiumGrains();
        h.assertTrue(sr == 2 * 21_945L && sep.items().getStackInSlot(0).getCount() == 1 && sep.workTicks() == 40, "state " + sr + " " + sep.items().getStackInSlot(0) + " " + sep.workTicks());
        tag = sep.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(tag.getLong("StrontiumGrains") == sr && tag.getInt("WorkTicks") == 40 && tag.contains("Items"), "keys missing: " + tag);
        var fresh = new IsotopeSeparatorBlockEntity(sp, l.getBlockState(sp));
        fresh.loadWithComponents(tag, l.registryAccess());
        h.assertTrue(fresh.strontiumGrains() == sr && fresh.caesiumGrains() == sep.caesiumGrains() && fresh.americiumGrains() == sep.americiumGrains()
                && fresh.strippedGrains() == sep.strippedGrains() && fresh.workTicks() == 40 && fresh.items().getStackInSlot(0).getCount() == 1, "fractions did not load");
        while (!fresh.items().getStackInSlot(0).isEmpty()) {
            fresh.energy().receiveEnergy(4_000, false);
            IsotopeSeparatorBlockEntity.serverTick(l, sp, l.getBlockState(sp), fresh);
        }
        h.assertTrue(grainsAccounted(fresh) == 3 * Isotopes.GRAINS_PER_WASTE_ITEM, "a reloaded separator lost mass: " + grainsAccounted(fresh));
        var odd = new CompoundTag();
        odd.putLong("StrontiumGrains", -5);
        odd.putInt("WorkTicks", -3);
        var oddSep = new IsotopeSeparatorBlockEntity(sp, l.getBlockState(sp));
        oddSep.loadWithComponents(odd, l.registryAccess());
        h.assertTrue(oddSep.strontiumGrains() == 0 && oddSep.workTicks() == 0, "a negative saved value loaded");
        sep.removalDrops();
        l.removeBlock(sp, false);
        h.succeed();
    }

    /** A reprocessing plant saved before this change loads and works exactly as it did: four slots, three products, no stripped waste. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 200)
    public static void oldReprocessingSavesLoad(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos pp = new BlockPos(16030, 210, 16000);
        var plant = place(l, pp, BwrBlocks.REPROCESSING_PLANT.get(), ReprocessingPlantBlockEntity.class);
        var old = new CompoundTag();
        old.putInt("Energy", 12_345);
        old.putInt("WorkTicks", 77);
        old.putLong("PlutoniumGrains", 400_000L);
        old.putLong("UraniumGrains", 5_000_000L);
        old.putLong("WasteGrains", 1_500_000L);
        old.put("Input", spentHandlerTag(l, spent("leu", 0.035, 45000.0)));
        var loaded = new ReprocessingPlantBlockEntity(pp, l.getBlockState(pp));
        loaded.loadWithComponents(old, l.registryAccess());
        h.assertTrue(loaded.energy().getEnergyStored() == 12_345 && loaded.workTicks() == 77 && loaded.plutoniumGrains() == 400_000L
                && loaded.uraniumGrains() == 5_000_000L && loaded.wasteGrains() == 1_500_000L, "an old plant's numbers did not load");
        h.assertTrue(Reprocessing.accepts(loaded.input().getStackInSlot(0)), "an old plant's assembly did not load");
        h.assertTrue(loaded.itemView(null).getSlots() == 4, "the plant's slot layout changed");
        h.assertTrue(loaded.wasteOut().getStackInSlot(0).isEmpty() || loaded.wasteOut().getStackInSlot(0).is(BwrItems.FISSION_PRODUCT_WASTE.get()), "waste output changed");
        for (int i = 0; i < 200; i++) {
            loaded.energy().receiveEnergy(10_000, false);
            ReprocessingPlantBlockEntity.serverTick(l, pp, l.getBlockState(pp), loaded);
        }
        h.assertTrue(loaded.input().getStackInSlot(0).isEmpty() && loaded.wasteOut().getStackInSlot(0).is(BwrItems.FISSION_PRODUCT_WASTE.get())
                && loaded.wasteOut().getStackInSlot(0).getCount() == 3, "the old plant did not finish as before: " + loaded.wasteOut().getStackInSlot(0));
        h.assertTrue(loaded.plutoniumGrains() + loaded.uraniumGrains() + loaded.wasteGrains() + loaded.plutoniumOut().getStackInSlot(0).getCount() * SpentFuel.GRAINS_PER_NINTH
                + loaded.uraniumOut().getStackInSlot(0).getCount() * SpentFuel.GRAINS_PER_INGOT + loaded.wasteOut().getStackInSlot(0).getCount() * SpentFuel.GRAINS_PER_NINTH
                == 400_000L + 5_000_000L + 1_500_000L + SpentFuel.ASSEMBLY_GRAINS, "the plant's mass changed");
        l.removeBlock(pp, false);
        h.succeed();
    }

    private static CompoundTag spentHandlerTag(ServerLevel l, ItemStack stack) {
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(1);
        handler.setStackInSlot(0, stack);
        return handler.serializeNBT(l.registryAccess());
    }

    // ------------------------------------------------------------------------------------------------------------
    // The RTG

    /** Six strontium capsules beside a real consumer: what is delivered plus what waits is exactly rate times the ticks. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 300)
    public static void anRtgPowersAnAdjacentConsumer(GameTestHelper h) {
        BlockPos rtgPos = new BlockPos(1, 2, 1);
        BlockPos plantPos = new BlockPos(2, 2, 1);
        h.setBlock(rtgPos, BwrBlocks.RTG.get());
        h.setBlock(plantPos, BwrBlocks.REPROCESSING_PLANT.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(rtgPos);
        var plant = (ReprocessingPlantBlockEntity) h.getBlockEntity(plantPos);
        for (int i = 0; i < RtgBlockEntity.SLOTS; i++) {
            rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        }
        var r = rtg.readout();
        double rate = 69.0 / 13.4;
        h.assertTrue(Math.abs(r.thermalWatts() - 1380.0) < 1e-3 && Math.abs(r.electricWatts() - 69.0) < 1e-4 && Math.abs(r.fePerTick() - rate) < 1e-6,
                "readout " + r);
        long t0 = h.getLevel().getGameTime();
        h.runAfterDelay(100, () -> {
            try {
                long elapsed = h.getLevel().getGameTime() - t0;
                double made = rate * elapsed;
                int delivered = plant.energy().getEnergyStored();
                double waiting = rtg.bufferFe();
                h.assertTrue(delivered > 0.8 * made, "the neighbour got " + delivered + " FE of " + made);
                h.assertTrue(Math.abs(delivered + waiting - made) < 1e-6 * made, "accounting: delivered " + delivered + " + waiting " + waiting + " vs made " + made);
                System.out.println("RTG power: " + elapsed + " ticks, 6 Sr-90 capsules, " + delivered + " FE delivered to a plant, " + String.format("%.2f", waiting) + " FE waiting; " + String.format("%.4f", rate) + " FE/t");
            } finally {
                tidy(h, rtgPos, plantPos);
            }
            // the RTG's point source went with its block: nothing is left in the shared level for other tests to count
            h.assertTrue(RadiationSources.doseRateMsvPerHour(h.getLevel(), Vec3.atCenterOf(h.absolutePos(rtgPos)).add(1.0, 0.0, 0.0)) == 0.0,
                    "the RTG's radiation source outlived its test");
            h.succeed();
        });
    }

    /** Takes the capsules out and removes the blocks a test placed: the point sources and the block entities must not outlive it. */
    private static void tidy(GameTestHelper h, BlockPos... rel) {
        for (BlockPos p : rel) {
            if (h.getBlockEntity(p) instanceof RtgBlockEntity rtg) {
                rtg.removalDrops();
            }
            h.setBlock(p, net.minecraft.world.level.block.Blocks.AIR);
        }
    }

    /**
     * The RTG's clock only moves forward. Other tests move the shared game clock (and put it back); a rewind must not make
     * the RTG bank the same ticks again when the clock returns. Done in one synchronous stretch, so no real tick runs between.
     */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void aRewoundClockDoesNotMakeEnergy(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1, 2, 1);
        h.setBlock(p, BwrBlocks.RTG.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(p);
        var data = (net.minecraft.world.level.storage.ServerLevelData) l.getLevelData();
        long original = l.getGameTime();
        try {
            for (int i = 0; i < RtgBlockEntity.SLOTS; i++) {
                rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
            }
            double rate = 69.0 / 13.4;
            double before = rtg.bufferFe();
            data.setGameTime(original + 10);
            double afterForward = rtg.bufferFe();
            h.assertTrue(Math.abs(afterForward - before - 10 * rate) < 1e-6, "ten ticks made " + (afterForward - before));
            data.setGameTime(original + 5);
            h.assertTrue(rtg.bufferFe() == afterForward, "a rewound clock changed the buffer: " + rtg.bufferFe() + " vs " + afterForward);
            data.setGameTime(original + 10);
            h.assertTrue(Math.abs(rtg.bufferFe() - afterForward) < 1e-9, "the same ten ticks were banked twice: " + rtg.bufferFe() + " vs " + afterForward);
            data.setGameTime(original + 15);
            h.assertTrue(Math.abs(rtg.bufferFe() - afterForward - 5 * rate) < 1e-6, "five more ticks made " + (rtg.bufferFe() - afterForward));
        } finally {
            data.setGameTime(original);
            tidy(h, p);
        }
        h.succeed();
    }

    /** No capsules, no power: nothing reaches the neighbour and nothing can be extracted. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 300)
    public static void anRtgWithNoFuelGivesZero(GameTestHelper h) {
        BlockPos rtgPos = new BlockPos(1, 2, 1);
        BlockPos plantPos = new BlockPos(2, 2, 1);
        h.setBlock(rtgPos, BwrBlocks.RTG.get());
        h.setBlock(plantPos, BwrBlocks.REPROCESSING_PLANT.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(rtgPos);
        var plant = (ReprocessingPlantBlockEntity) h.getBlockEntity(plantPos);
        h.assertTrue(rtg.readout().equals(RtgBlockEntity.Readout.NONE), "an empty RTG reads " + rtg.readout());
        h.runAfterDelay(80, () -> {
            try {
                h.assertTrue(plant.energy().getEnergyStored() == 0, "an unfuelled RTG gave " + plant.energy().getEnergyStored() + " FE");
                h.assertTrue(rtg.energy().extractEnergy(1000, true) == 0 && rtg.energy().getEnergyStored() == 0 && rtg.bufferFe() == 0.0, "an unfuelled RTG held energy");
                // a stripped waste item or plain waste is not fuel: it is refused by the well's own filter
                h.assertTrue(!rtg.fuel().isItemValid(0, new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get())) && !rtg.fuel().isItemValid(0, new ItemStack(Items.COAL)), "a non-fuel was accepted");
            } finally {
                tidy(h, rtgPos, plantPos);
            }
            h.succeed();
        });
    }

    /** The decay clock is world time and the half-life is the data map's: one half-life later the power is half. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void decayFollowsTheHalfLifeInTheBlock(GameTestHelper h) {
        BlockPos p = new BlockPos(1, 2, 1);
        h.setBlock(p, BwrBlocks.RTG.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(p);
        rtg.fuel().setStackInSlot(0, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        rtg.fuel().setStackInSlot(1, new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get()));
        double fresh = rtg.readout().thermalWatts();
        h.assertTrue(Math.abs(fresh - (230.0 + 250.0 * 0.1147)) < 1e-6, "fresh thermal power " + fresh);
        long now = h.getLevel().getGameTime();
        long ticksPerHalfLife = (long) (28.9 * dev.bwr.core.isotope.RadioisotopePower.SECONDS_PER_YEAR * 20.0);
        rtg.setLoadedAt(0, now - ticksPerHalfLife);
        double aged = rtg.readout().thermalWatts();
        double expected = 115.0 + 250.0 * 0.1147; // only the strontium well was put back in time; the americium capsule is fresh
        h.assertTrue(Math.abs(aged - expected) < 1e-3, "after one Sr-90 half-life " + aged + ", wanted " + expected);
        // and a session of 100 hours changes nothing you could see
        rtg.setLoadedAt(0, now - 100L * 3600L * 20L);
        h.assertTrue(rtg.readout().thermalWatts() > 0.9997 * fresh, "100 hours cost more than 0.03 %");
        rtg.removalDrops();
        tidy(h, p);
        h.succeed();
    }

    /** Contents, clocks and the buffer save and load; an empty RTG writes nothing; a hand-edited save cannot mint. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void rtgSavesAndReloads(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1, 2, 1);
        h.setBlock(p, BwrBlocks.RTG.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(p);
        var empty = rtg.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(!empty.contains("Fuel") && !empty.contains("LoadedAt") && !empty.contains("Buffer"), "an empty RTG wrote " + empty);
        rtg.fuel().setStackInSlot(2, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        rtg.fuel().setStackInSlot(4, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        long stamp = l.getGameTime() - 5000;
        rtg.setLoadedAt(2, stamp);
        var before = rtg.readout();
        var tag = rtg.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(tag.contains("Fuel") && tag.contains("LoadedAt"), "a loaded RTG did not write its fuel: " + tag);
        var fresh = new RtgBlockEntity(rtg.getBlockPos(), rtg.getBlockState());
        fresh.loadWithComponents(tag, l.registryAccess());
        h.assertTrue(fresh.fuel().getStackInSlot(2).is(BwrItems.CAESIUM_137_CAPSULE.get()) && fresh.fuel().getStackInSlot(4).is(BwrItems.STRONTIUM_90_CAPSULE.get())
                && fresh.fuel().getStackInSlot(0).isEmpty(), "the capsules did not load into their wells");
        h.assertTrue(fresh.loadedAt(2) == stamp, "the decay clock did not load");
        var odd = new CompoundTag();
        odd.putDouble("Buffer", 1.0e12);
        var oddRtg = new RtgBlockEntity(rtg.getBlockPos(), rtg.getBlockState());
        oddRtg.loadWithComponents(odd, l.registryAccess());
        h.assertTrue(oddRtg.readout().equals(RtgBlockEntity.Readout.NONE), "an unfuelled loaded RTG makes something");
        h.assertTrue(before.capsules() == 2, "readout capsules " + before.capsules());
        rtg.removalDrops();
        tidy(h, p);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dose

    /** A carried capsule is a gamma source scaled by isotope, grams and count; the block is one too, behind its housing. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void capsulesDoseACarrierAndTheBlockIsASource(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 at = player.position().add(1, 1, 0);
        h.assertTrue(CarriedIsotopes.doseRateOf(player, at) == 0.0, "an empty-handed player reads a dose");
        player.getInventory().add(new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        double sr = CarriedIsotopes.doseRateOf(player, at);
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        double cs = CarriedIsotopes.doseRateOf(player, at);
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get(), 2));
        double cs2 = CarriedIsotopes.doseRateOf(player, at);
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get()));
        double am = CarriedIsotopes.doseRateOf(player, at);
        player.getInventory().clearContent();
        h.assertTrue(cs > 1.0e4, "a caesium-137 capsule reads only " + cs + " mSv/h beside the carrier");
        h.assertTrue(sr > 100.0 && sr < cs, "a strontium-90 capsule reads " + sr + " mSv/h");
        h.assertTrue(am > 0.0 && am < 100.0, "an americium-241 capsule reads " + am + " mSv/h");
        h.assertTrue(Math.abs(cs2 / cs - 2.0) < 1e-9, "two capsules read " + cs2 / cs + " times one");
        double expectedCs = DoseModel.pointSourceMsvPerH(250.0 * 0.29 * 0.85, player.position().add(0, 1, 0).distanceTo(at), 1.0);
        h.assertTrue(Math.abs(cs / expectedCs - 1.0) < 1e-9, "caesium dose " + cs + " vs " + expectedCs);
        System.out.println("RTG carried dose at about 1 m: Sr-90 " + DoseModel.formatRate(sr) + ", Cs-137 " + DoseModel.formatRate(cs) + ", Am-241 " + DoseModel.formatRate(am));

        // the block: a point source from its capsules, read at a metre, cleared when the capsules or the block go
        BlockPos rtgAbs = new BlockPos(16100, 210, 16000);
        var rtg = place(l, rtgAbs, BwrBlocks.RTG.get(), RtgBlockEntity.class);
        Vec3 centre = Vec3.atCenterOf(rtgAbs);
        Vec3 metre = centre.add(1.0, 0.0, 0.0);
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "an empty RTG reads a dose");
        rtg.fuel().setStackInSlot(0, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        double dose = RadiationSources.doseRateMsvPerHour(l, metre);
        h.assertTrue(Math.abs(dose / rtg.readout().doseAtOneMetreMsvPerH() - 1.0) < 1e-9 && dose > 1.0, "an RTG with caesium reads " + dose + ", its readout " + rtg.readout().doseAtOneMetreMsvPerH());
        rtg.fuel().setStackInSlot(0, ItemStack.EMPTY);
        rtg.fuel().setStackInSlot(1, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        double srBlock = RadiationSources.doseRateMsvPerHour(l, metre);
        h.assertTrue(srBlock > 0.0 && srBlock < dose, "a strontium RTG reads " + srBlock + " against caesium " + dose);
        rtg.fuel().setStackInSlot(1, ItemStack.EMPTY);
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "the source stayed after the capsules went");
        rtg.fuel().setStackInSlot(1, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        l.removeBlock(rtgAbs, false);
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "the source stayed after the block went");
        System.out.println("RTG block dose at 1 m: Cs-137 " + DoseModel.formatRate(dose) + ", Sr-90 " + DoseModel.formatRate(srBlock));
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The fuel list, the recipes, the signals

    /** The list is data: the capsules' numbers are what the docs say, they match the constants, and a radium line parses. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void theFuelListIsDataAndAnotherLineParses(GameTestHelper h) {
        RtgFuel sr = RtgFuels.of(new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        RtgFuel cs = RtgFuels.of(new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        RtgFuel am = RtgFuels.of(new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get()));
        h.assertTrue(sr != null && cs != null && am != null, "a capsule is not in the fuel list");
        h.assertTrue(sr.specificPowerWPerG() == 0.92 && sr.halfLifeYears() == 28.9 && sr.gramsPerItem() == Isotopes.CAPSULE_GRAMS, "strontium " + sr);
        h.assertTrue(cs.specificPowerWPerG() == 0.42 && cs.halfLifeYears() == 30.04 && cs.gramsPerItem() == Isotopes.CAPSULE_GRAMS, "caesium " + cs);
        h.assertTrue(am.specificPowerWPerG() == 0.1147 && am.halfLifeYears() == 432.6 && am.gramsPerItem() == Isotopes.CAPSULE_GRAMS, "americium " + am);
        h.assertTrue(RtgFuels.of(new ItemStack(Items.COAL)) == null && RtgFuels.of(new ItemStack(BwrItems.STRIPPED_WASTE.get())) == null
                && RtgFuels.of(new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get())) == null && RtgFuels.of(ItemStack.EMPTY) == null, "a non-fuel is in the list");
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("bwr", "rtg")), "no rtg item");
        // the line documented in RTG.md for radium parses with the real codec (the item it is keyed by is another branch's)
        String radiumLine = "{\"isotope\":\"Ra-226 with daughters\",\"specific_power_w_per_g\":0.16,\"half_life_years\":1600,\"grams_per_item\":10,"
                + "\"gamma_w_per_g\":0.0093,\"capsule_transmission\":0.5}";
        var parsed = RtgFuel.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(radiumLine));
        h.assertTrue(parsed.result().isPresent() && parsed.result().get().halfLifeYears() == 1600.0, "the radium line did not parse: " + parsed);
        var minimal = RtgFuel.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"specific_power_w_per_g\":0.5,\"grams_per_item\":3}"));
        h.assertTrue(minimal.result().isPresent() && minimal.result().get().capsuleTransmission() == 1.0 && minimal.result().get().gammaWPerG() == 0.0, "the optional fields have wrong defaults");
        var bad = RtgFuel.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"specific_power_w_per_g\":-1,\"grams_per_item\":3}"));
        h.assertTrue(bad.result().isEmpty(), "a negative specific power parsed");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void recipesCraftTheBlocksAndTheAmBeRodFromACapsule(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        for (String id : new String[] {"rtg", "isotope_separator", "americium_beryllium_source_from_capsule", "americium_beryllium_source"}) {
            h.assertTrue(manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", id)).isPresent(), "no recipe bwr:" + id);
        }
        var amBe = (net.minecraft.world.item.crafting.CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", "americium_beryllium_source_from_capsule")).get().value();
        var grid = net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
        grid.set(0, new ItemStack(BwrItems.IRRADIATION_CASING.get()));
        grid.set(1, new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get()));
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid);
        h.assertTrue(amBe.matches(input, h.getLevel()), "a casing and an americium capsule did not craft the rod");
        var viaCapsule = amBe.assemble(input, h.getLevel().registryAccess());
        var viaEcho = ((net.minecraft.world.item.crafting.CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", "americium_beryllium_source")).get().value())
                .getResultItem(h.getLevel().registryAccess());
        h.assertTrue(viaCapsule.is(BwrItems.SPECIALTY_ROD.get()) && net.minecraft.world.item.ItemStack.isSameItemSameComponents(viaCapsule, viaEcho), "the capsule route makes another rod than the old route");
        grid.set(1, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        h.assertTrue(!amBe.matches(net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid), h.getLevel()), "a strontium capsule crafted an americium source");
        h.succeed();
    }

    /** The signal layer: a Networking Wrench sees the RTG's power. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 100)
    public static void theNetworkingSignalsReadTheRtg(GameTestHelper h) {
        BlockPos p = new BlockPos(1, 2, 1);
        h.setBlock(p, BwrBlocks.RTG.get());
        var rtg = (RtgBlockEntity) h.getBlockEntity(p);
        var provider = SignalProviders.of(rtg);
        h.assertTrue(provider != null, "no signal provider for the RTG");
        h.assertTrue(provider.read("electric_power").orElse(-1) == 0.0 && provider.read("fuelled").orElse(-1) == 0.0, "an empty RTG signals power");
        for (int i = 0; i < 3; i++) {
            rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        }
        h.assertTrue(Math.abs(provider.read("thermal_power").orElse(-1) - 690.0) < 1e-6, "thermal_power " + provider.read("thermal_power"));
        h.assertTrue(Math.abs(provider.read("electric_power").orElse(-1) - 34.5 / 13.4) < 1e-9, "electric_power " + provider.read("electric_power"));
        h.assertTrue(provider.read("capsules").orElse(-1) == 3.0 && provider.read("fuelled").orElse(-1) == 1.0 && provider.read("dose_rate").orElse(-1) > 0.0, "capsules, fuelled or dose_rate wrong");
        h.assertTrue(provider.commands().isEmpty(), "the RTG has a command");
        rtg.removalDrops();
        tidy(h, p);
        h.succeed();
    }

    /** The computer face (CC:Tweaked) of the RTG and the separator; says it was skipped without CC:Tweaked. */
    @GameTest(template = "empty", templateNamespace = NS, batch = BATCH, timeoutTicks = 200)
    public static void computerFaces(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("RTG CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        RtgCcChecks.run(h);
        System.out.println("RTG CC: bwr_rtg and bwr_isotope_separator read through getStatus");
        h.succeed();
    }
}
