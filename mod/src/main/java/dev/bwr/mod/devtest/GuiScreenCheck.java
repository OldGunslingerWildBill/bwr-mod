package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.*;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrTab;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Opens every machine screen of the mod with fabricated client-side menu data (no machine, no server round trip: the
 * layout of a screen is a function of the menu's public fields), at GUI scale 2, 3 and 4 in a 1920 x 1080 window, saves a
 * shot of each and checks that nothing a screen puts on the screen lies outside it.
 *
 * <p>Run it with {@code :mod:runTurbineModelCheck -PbwrGuiScreenCheck}. {@code -Dbwr.guiCheckLenient=true} (Gradle:
 * {@code -PbwrGuiCheckLenient}) only logs the violations instead of failing, which is how the audit of the old
 * screens was taken. The pictures land in {@code gui-check/scaleN/} of the run directory.
 *
 * <p>Besides the two real scales the screens are also laid out once at 320 x 240 and 427 x 240, the smallest GUI
 * size the game ever gives a screen (it picks the largest scale that leaves at least 320 x 240), by resizing the
 * screen directly; that run is checked, not photographed.
 *
 * <p>What is asserted, per screen and size:
 * <ul>
 *   <li>no visible widget, panel or slot lies outside the window, no widget outside its panel, no two widgets overlap
 *       and no widget covers a slot;</li>
 *   <li>every string the style draws lies inside the panel (horizontally always, vertically unless it is scrolled
 *       content, which is clipped by design) - text is cut with an ellipsis and a tooltip, never left to run over;</li>
 *   <li>(once per screen) every enabled control can be reached with Tab, has a tooltip and a narration title, and a
 *       focused tab is activated by Space.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class GuiScreenCheck {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final int[] SCALES = {2, 3, 4};
    private static final int[][] SMALL = {{320, 240}, {427, 240}};

    /** One screen in one state. {@code setup} may click a tab or move the mouse once the screen is open. */
    private record Scenario(String name, Function<Inventory, Screen> make, Consumer<Screen> setup, Consumer<Screen> late, boolean interactive) {
        Scenario(String name, Function<Inventory, Screen> make) {
            this(name, make, s -> {
            }, s -> {
            }, false);
        }

        Scenario(String name, Function<Inventory, Screen> make, Consumer<Screen> setup) {
            this(name, make, setup, s -> {
            }, false);
        }
    }

    /** Screens also photographed at the smallest GUI sizes (laid out for 320 x 240 or 427 x 240, drawn at scale 2 in the corner). */
    private static final Set<String> FLOOR_PICTURES = Set.of("reactor-flux", "reactor-info", "pump-rhr", "terry", "condenser", "refuelling-burnup", "fabricator", "pool-spray");

    private static boolean started, built;
    private static int age, joined, stage, scaleIndex, wait;
    private static boolean opened;
    private static boolean resized;
    /** Where the pointer is for the pictures, in GUI coordinates: off the screen unless a scenario puts it on a control. */
    private static int pointerX = -1000, pointerY = -1000;
    private static List<Scenario> scenarios;
    private static final List<String> violations = new ArrayList<>();
    private static Path directory;

    private GuiScreenCheck() {
    }

    /** Draws the open screen with the fake pointer, so a picture never shows whatever the real pointer happens to hover. */
    @SubscribeEvent
    public static void render(ScreenEvent.Render.Pre event) {
        if (Boolean.getBoolean("bwr.guiScreenCheck") && built && event.getScreen() == Minecraft.getInstance().screen) {
            event.setCanceled(true);
            event.getScreen().renderWithTooltip(event.getGuiGraphics(), pointerX, pointerY, event.getPartialTick());
        }
    }

    // -----------------------------------------------------------------
    // Fabricated data
    // -----------------------------------------------------------------

    private static final BlockPos P = BlockPos.ZERO;

    private static Component t(String s) {
        return Component.literal(s);
    }

    private static CoreMapSnapshot map(int lattice, int radius, boolean worn) {
        CoreMapSnapshot m = new CoreMapSnapshot();
        List<Integer> idx = new ArrayList<>();
        int c = lattice / 2;
        for (int r = 0; r < lattice; r++) {
            for (int q = 0; q < lattice; q++) {
                double d = Math.hypot(q - c, r - c);
                if (d <= radius) {
                    idx.add(r * lattice + q);
                }
            }
        }
        int n = idx.size();
        m.latticeWidth = lattice;
        m.coreSlotCount = n;
        m.fuelTypeNames = new String[]{"GE14 10x10", "Compact 8x8"};
        m.latticeIndex = new int[n];
        m.fuelTypeIndex = new int[n];
        m.burnupMwdPerTonne = new double[n];
        m.enrichmentWeightFraction = new double[n];
        m.kInf = new double[n];
        m.relativeFlux = new double[n];
        m.insertProgress = new double[n];
        for (int i = 0; i < n; i++) {
            int v = idx.get(i);
            m.latticeIndex[i] = v;
            double d = Math.hypot(v % lattice - c, v / lattice - c) / radius;
            boolean empty = worn && i % 11 == 3;
            m.fuelTypeIndex[i] = empty ? -1 : (i % 7 == 0 ? 1 : 0);
            m.burnupMwdPerTonne[i] = worn ? 42000 * (1 - d * 0.8) : 0;
            m.enrichmentWeightFraction[i] = 0.035 + (i % 5) * 0.002;
            m.kInf[i] = 1.28 - 0.30 * (worn ? 1 - d : 0.2);
            m.relativeFlux[i] = Math.max(0.05, 1.0 - 0.7 * d * d);
        }
        m.peakWeight = 0.011;
        m.coreThermalMW = 3300;
        return m;
    }

    private static void reactorLive(ReactorPanelMenu m, boolean alarms) {
        m.formed = true;
        m.vesselState = VesselState.SHUTDOWN;
        m.powerFractionOfRated = alarms ? 1.12 : 0.874;
        m.thermalMW = alarms ? 3714.2 : 2905.3;
        m.decayHeatFraction = 0.0231;
        m.pressurePsig = alarms ? 1301 : 1008;
        m.pressureRatePsiPerS = alarms ? 4.8 : 0.1;
        m.indicatedLevelIn = alarms ? -12.4 : 33.2;
        m.coreFlowFraction = 0.82;
        m.coreFlowKgPerS = 11210;
        m.steamKgPerS = 1612;
        m.feedwaterKgPerS = 1604;
        m.reactivityDollars = alarms ? 1.04 : 0.002;
        m.srmPeriodSeconds = Double.POSITIVE_INFINITY;
        m.irmPeriodSeconds = alarms ? -38.2 : 94.7;
        m.betaEffective = 0.00552;
        m.fuelTemperatureC = 702;
        m.peakCladTemperatureC = alarms ? 1214 : 318;
        m.coolantTemperatureC = 286;
        m.voidFraction = 0.412;
        m.xenonFraction = 0.12;
        m.averageBurnup = 21500;
        m.aggregateKInf = 1.1032;
        m.oxidationFraction = alarms ? 0.03 : 0;
        m.hydrogenKg = alarms ? 4.4 : 0;
        m.uncoveredFuelFraction = alarms ? 0.08 : 0;
        m.sprayRingCompleteness = 1;
        m.aprmPercent = alarms ? 112 : 87.4;
        m.srmCountsPerSecond = 3.4e4;
        m.chargedAccumulators = alarms ? 118 : 137;
        m.rodCount = 137;
        m.poweredDrives = 137;
        m.waterSuppliedDrives = 137;
        m.loadedAssemblies = 764;
        m.assemblyCount = 764;
        m.pumpCount = 4;
        m.scramActive = alarms;
        m.map = map(31, 14, true);
        int rods = 45;
        m.rodNotchLabels = new int[rods];
        m.rodDemandLabels = new int[rods];
        m.rodLatticeIndices = new int[rods];
        m.rodsPerX = 9;
        m.rodsPerZ = 5;
        for (int i = 0; i < rods; i++) {
            m.rodNotchLabels[i] = (i * 5) % 49;
            m.rodDemandLabels[i] = i % 9 == 0 ? 48 : m.rodNotchLabels[i];
            m.rodLatticeIndices[i] = i;
        }
        m.configuration = new ReactorConfigurationInfo(3293, 3300, 1612, 0.85, 12100, 51.7, 20, 0, 4, 0, 14000, 20, 4, 14000);
    }

    private static PumpControlMenu pump(Inventory inv, boolean computer, String variant) {
        PumpControlMenu m = new PumpControlMenu(99, inv, P, P);
        m.present = true;
        m.running = true;
        m.control = computer ? 2 : 0;
        m.target = 0.65;
        m.actual = 0.612;
        m.flow = 1480.3;
        m.pressure = 412.7;
        m.temperature = 38.4;
        m.energy = 8_400_000;
        m.capacity = 10_000_000;
        m.buffer = 1450;
        m.drive = "Electric motor";
        m.connection = "Suction piped, discharge connected to the reactor";
        switch (variant) {
            case "rhr" -> {
                m.eccs = true;
                m.rhr = true;
                m.poolSuction = true;
                m.shutdownCooling = true;
            }
            case "slc" -> {
                m.eccs = true;
                m.slc = true;
            }
            case "recirc" -> {
                m.recirculation = true;
                m.drive = "Electric recirculation";
                m.connection = "Recirculation loop connected";
                m.pressure = 1008;
                m.temperature = 286;
            }
            case "feed" -> {
                m.drive = "Steam turbine";
                m.turbine = true;
            }
            default -> m.eccs = true;
        }
        return m;
    }

    private static TerryTurbineMenu terry(Inventory inv, boolean computer) {
        TerryTurbineMenu m = new TerryTurbineMenu(99, inv, P, P);
        m.present = true;
        m.running = true;
        m.eccs = true;
        m.turbine = true;
        m.control = computer ? 2 : 0;
        m.target = 0.65;
        m.actual = 0.64;
        m.flow = 38.4;
        m.pressure = 1088.2;
        m.temperature = 31.0;
        m.steamFlow = 5.82;
        m.inletPressure = 1008.4;
        m.exhaustPressure = 18.6;
        m.portLocations[0] = "north  -1203, 64, 4417";
        m.portLocations[1] = "south  -1203, 64, 4423";
        m.portLocations[2] = "east  -1199, 62, 4420";
        m.portLocations[3] = "west  -1207, 62, 4420";
        m.portReady[0] = true;
        m.portReady[1] = true;
        m.portReady[2] = false;
        m.portReady[3] = true;
        return m;
    }

    private static List<Scenario> build() {
        List<Scenario> s = new ArrayList<>();
        s.add(new Scenario("reactor-flux", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, false);
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }));
        s.add(new Scenario("reactor-rods", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, true);
            m.vesselState = VesselState.REFUELING;
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }, screen -> click(screen, "RODS")));
        s.add(new Scenario("reactor-info", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, false);
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }, screen -> click(screen, "INFO")));
        s.add(new Scenario("reactor-unformed", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            m.formed = false;
            m.statusLines = new ArrayList<>(List.of(t("Vessel shell incomplete: 3 blocks missing at the upper ring."),
                    t("Fuel pool not found. Place the controller against the vessel."), t("Control rod drives: 0 of 137 connected.")));
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }));
        s.add(new Scenario("refuelling-burnup", i -> {
            var m = new RefuellingMenu(99, i, P);
            refuel(m);
            return new RefuellingScreen(m, i, t("Refuelling Floor"));
        }));
        s.add(new Scenario("refuelling-kinf", i -> {
            var m = new RefuellingMenu(99, i, P);
            refuel(m);
            return new RefuellingScreen(m, i, t("Refuelling Floor"));
        }, screen -> click(screen, "K-INF")));
        s.add(new Scenario("refuelling-waiting", i -> {
            var m = new RefuellingMenu(99, i, P);
            m.open = false;
            return new RefuellingScreen(m, i, t("Refuelling Floor"));
        }));
        s.add(new Scenario("pump-rhr", i -> new PumpControlScreen(pump(i, false, "rhr"), i, t("RHR / LPCI Pump"))));
        s.add(new Scenario("pump-rhr-computer", i -> new PumpControlScreen(pump(i, true, "rhr"), i, t("RHR / LPCI Pump"))));
        s.add(new Scenario("pump-hpcs", i -> new PumpControlScreen(pump(i, false, "eccs"), i, t("HPCS Pump"))));
        s.add(new Scenario("pump-slc", i -> new PumpControlScreen(pump(i, false, "slc"), i, t("SLC Injection Pump"))));
        s.add(new Scenario("pump-recirc", i -> new PumpControlScreen(pump(i, false, "recirc"), i, t("DVSS Recirculation Pump"))));
        s.add(new Scenario("pump-feed", i -> new PumpControlScreen(pump(i, false, "feed"), i, t("Turbine-Driven Reactor Feed Pump"))));
        s.add(new Scenario("pump-absent", i -> {
            var m = new PumpControlMenu(99, i, P, P);
            return new PumpControlScreen(m, i, t("RHR / LPCI Pump"));
        }));
        s.add(new Scenario("terry", i -> new TerryTurbineScreen(terry(i, false), i, t("HPCI Terry Turbine Pump"))));
        s.add(new Scenario("terry-computer", i -> new TerryTurbineScreen(terry(i, true), i, t("HPCI Terry Turbine Pump"))));
        s.add(new Scenario("recirc-legacy", i -> {
            var m = new RecirculationPumpMenu(99, i, P);
            m.present = true;
            m.targetSpeedFraction = 0.8;
            m.actualSpeedFraction = 0.5;
            m.maxAchievableSpeedFraction = 0.5;
            m.powerLimited = true;
            m.attachedToReactor = true;
            m.energyStoredFe = 640000;
            m.energyCapacityFe = 2_000_000;
            return new RecirculationPumpScreen(m, i, t("Recirculation Pump"));
        }));
        s.add(new Scenario("recirc-legacy-computer", i -> {
            var m = new RecirculationPumpMenu(99, i, P);
            m.present = true;
            m.targetSpeedFraction = 0.4;
            m.actualSpeedFraction = 0.4;
            m.maxAchievableSpeedFraction = 1;
            m.computerControlled = true;
            m.energyStoredFe = 1_900_000;
            m.energyCapacityFe = 2_000_000;
            return new RecirculationPumpScreen(m, i, t("Recirculation Pump"));
        }));
        s.add(new Scenario("condenser", i -> {
            var m = new CondenserMenu(99, i, P, P);
            m.present = true;
            m.ready = true;
            m.cold = 640000;
            m.hot = 212000;
            m.condensate = 88000;
            m.steam = 41.2;
            m.steamRate = 1612.4;
            m.coolingRate = 28000;
            m.rejectedMW = 2204.6;
            m.coldC = 18.2;
            m.hotC = 31.9;
            m.condensateC = 38.4;
            m.pressure = 1.62;
            m.vacuum = 27.8;
            return new CondenserScreen(m, i, t("Arabelle-style 1700 MW Condenser"));
        }));
        s.add(new Scenario("condenser-waiting", i -> new CondenserScreen(new CondenserMenu(99, i, P, P), i, t("Arabelle-style 1700 MW Condenser"))));
        s.add(new Scenario("cooling-tower", i -> new CoolingScreen(cooling(i, Design.MECHANICAL), i, t("Mechanical-Draft Cooling Tower"))));
        s.add(new Scenario("cooling-natural", i -> new CoolingScreen(cooling(i, Design.NATURAL), i, t("Natural-Draft Cooling Tower"))));
        s.add(new Scenario("cooling-pump", i -> new CoolingScreen(cooling(i, Design.CIRCULATING), i, t("Circulating Water Pump"))));
        s.add(new Scenario("cooling-intake", i -> new CoolingScreen(cooling(i, Design.INTAKE), i, t("Screened Water Intake"))));
        s.add(new Scenario("tank-assembled", i -> {
            var m = new CondensateTankMenu(99, i, P);
            m.present = true;
            m.assembled = true;
            m.ready = true;
            m.stored = 812000;
            m.capacity = 1_480_000;
            m.temperature = 38;
            m.diameter = 11;
            m.height = 14;
            m.blocks = 1230;
            return new CondensateTankScreen(m, i, t("Condensate Storage Tank"));
        }));
        s.add(new Scenario("tank-unbuilt", i -> {
            var m = new CondensateTankMenu(99, i, P);
            m.present = true;
            m.capacity = 0;
            return new CondensateTankScreen(m, i, t("Condensate Storage Tank"));
        }));
        s.add(new Scenario("power-hp", i -> new PowerModuleScreen(power(i, 0), i, t("TX-10 Style HP Turbine"))));
        s.add(new Scenario("power-lp", i -> new PowerModuleScreen(power(i, 1), i, t("TX-10 Style LP Turbine"))));
        s.add(new Scenario("power-generator", i -> new PowerModuleScreen(power(i, 2), i, t("Turbine Generator"))));
        s.add(new Scenario("valve-control", i -> new TurbineValveScreen(valve(i, false), i, t("Turbine Control Valve"))));
        s.add(new Scenario("valve-stop", i -> new TurbineValveScreen(valve(i, true), i, t("Turbine Stop Valve"))));
        s.add(new Scenario("fabricator", i -> {
            var m = new FuelFabricatorMenu(99, i, P);
            m.present = true;
            m.batchHeavyMetalKg = 61.3;
            m.batchTargetKg = 180;
            m.batchEnrichmentWeightFraction = 0.0412;
            m.energyStored = 740000;
            m.energyCapacity = 1_000_000;
            m.wouldFabricate = "LEU 4.1% UO2 assembly";
            m.feedLines = new ArrayList<>(List.of("Uranium oxide feed: 0.82 kg/s from Mekanism pipe"));
            m.getSlot(FuelFabricatorMenu.SLOT_ASSEMBLY).set(new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
            // the item route: 3.5 % selected (29 uranium, 4 steel, 24 depleted uranium back), part way through
            m.targetIndex = 2;
            m.hasCost = true;
            m.costIngots = 29;
            m.costSteel = 4;
            m.costTails = 24;
            m.costWorkTicks = 187;
            m.uraniumHeldUnits = 58;
            m.workTicks = 92;
            m.tailsHeld = 24;
            var uranium = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("mekanism:ingot_uranium"));
            var steel = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("mekanism:ingot_steel"));
            m.getSlot(FuelFabricatorMenu.SLOT_URANIUM).set(new ItemStack(uranium == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.Items.GOLD_INGOT : uranium, 41));
            m.getSlot(FuelFabricatorMenu.SLOT_STEEL).set(new ItemStack(steel == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.Items.IRON_INGOT : steel, 6));
            m.getSlot(FuelFabricatorMenu.SLOT_TAILS).set(new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 24));
            return new FuelFabricatorScreen(m, i, t("Fuel Fabricator"));
        }));
        s.add(new Scenario("fabricator-hot", i -> {
            // the top of the ladder: 20 % (172 uranium, more than a stack), nothing in the output
            var m = new FuelFabricatorMenu(99, i, P);
            m.present = true;
            m.energyStored = 0;
            m.energyCapacity = 100_000;
            m.targetIndex = FuelItemRoute.TARGETS.size() - 1;
            m.hasCost = true;
            m.costIngots = 172;
            m.costSteel = 4;
            m.costTails = 167;
            m.costWorkTicks = 616;
            m.uraniumHeldUnits = 344;
            m.workTicks = 0;
            m.tailsHeld = 167;
            m.getSlot(FuelFabricatorMenu.SLOT_TAILS).set(new ItemStack(BwrItems.DEPLETED_URANIUM.get(), 64));
            return new FuelFabricatorScreen(m, i, t("Fuel Fabricator"));
        }));
        s.add(new Scenario("fabricator-feed", i -> {
            // what a real Mekanism feed writes (FuelFabricatorBlockEntity.statusLines): the longest feed line, the 20 % grade, a type id
            var m = new FuelFabricatorMenu(99, i, P);
            m.present = true;
            m.batchHeavyMetalKg = 142.5;
            m.batchTargetKg = 180;
            m.batchEnrichmentWeightFraction = 0.2;
            m.energyStored = 99_999;
            m.energyCapacity = 100_000;
            m.wouldFabricate = "plutonium_rich";
            m.feedLines = new ArrayList<>(List.of("Matrix tank: 10,000 / 10,000 mB of uranium_oxide (12.3 kg HM)", "Fissile tank empty"));
            m.targetIndex = FuelItemRoute.TARGETS.size() - 1;
            m.hasCost = true;
            m.costIngots = 172;
            m.costSteel = 4;
            m.costTails = 167;
            m.costWorkTicks = 616;
            m.uraniumHeldUnits = 344;
            m.workTicks = 300;
            m.tailsHeld = 167;
            return new FuelFabricatorScreen(m, i, t("Fuel Fabricator"));
        }));
        s.add(new Scenario("fabricator-absent", i -> new FuelFabricatorScreen(new FuelFabricatorMenu(99, i, P), i, t("Fuel Fabricator"))));
        s.add(new Scenario("rhr-hx", i -> {
            var m = new RhrHeatExchangerMenu(99, i, P);
            m.present = true;
            m.connected = true;
            m.cold = 40000;
            m.hot = 22000;
            m.primaryFlow = 612.4;
            m.secondaryFlow = 801.2;
            m.heat = 18.34;
            m.hotTemperature = 63.2;
            m.primaryOut = 54.1;
            m.coldTemperature = 22.5;
            return new RhrHeatExchangerScreen(m, i, t("RHR Heat Exchanger"));
        }));
        s.add(new Scenario("rhr-hx-idle", i -> new RhrHeatExchangerScreen(new RhrHeatExchangerMenu(99, i, P), i, t("RHR Heat Exchanger"))));
        s.add(new Scenario("pool-spray", i -> new SuppressionPoolScreen(pool(i, true, true), i, t("Suppression Tank"))));
        s.add(new Scenario("pool-legacy", i -> new SuppressionPoolScreen(pool(i, false, false), i, t("Suppression Tank"))));
        s.add(new Scenario("pool-unformed", i -> {
            var m = new SuppressionPoolMenu(99, i, P);
            m.present = true;
            return new SuppressionPoolScreen(m, i, t("Suppression Tank"));
        }));
        // the keyboard, pointer and alarm states of the style
        s.add(new Scenario("reactor-flux-alarms", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, true);
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }));
        s.add(new Scenario("reactor-map-focus", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, false);
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }, screen -> {
        }, screen -> focusGrid(screen, 3), true));
        s.add(new Scenario("pump-hover-and-focus", i -> new PumpControlScreen(pump(i, false, "rhr"), i, t("RHR / LPCI Pump")),
                screen -> focusButton(screen, "Apply"), screen -> hoverButton(screen, "Controlled from this panel"), true));
        s.add(new Scenario("pump-invalid-entry", i -> new PumpControlScreen(pump(i, false, "rhr"), i, t("RHR / LPCI Pump")),
                screen -> typeInto(screen, "140"), screen -> {
        }, true));
        s.add(new Scenario("terry-ports", i -> new TerryTurbineScreen(terry(i, false), i, t("HPCI Terry Turbine Pump")),
                screen -> click(screen, "PORTS")));
        s.add(new Scenario("reactor-info-scrolled", i -> {
            var m = new ReactorPanelMenu(99, i, P);
            reactorLive(m, true);
            return new ReactorPanelScreen(m, i, t("Reactor Control Panel"));
        }, screen -> {
            click(screen, "INFO");
        }, screen -> screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, -6), true));
        s.add(new Scenario("refuelling-selection", i -> {
            var m = new RefuellingMenu(99, i, P);
            refuel(m);
            return new RefuellingScreen(m, i, t("Refuelling Floor"));
        }, screen -> {
        }, screen -> focusGrid(screen, 6), true));
        s.add(new Scenario("refuelling-drag", i -> {
            var m = new RefuellingMenu(99, i, P);
            refuel(m);
            return new RefuellingScreen(m, i, t("Refuelling Floor"));
        }, screen -> {
        }, GuiScreenCheck::dragSelect, true));
        s.add(new Scenario("service-slc-tank", i -> service(i, 1, 0, false, "Boron tank assembled", "Solution: 12,400.00 / 15,000 kg",
                "Dissolved borate: 1,340.25 kg", "Elemental boron: 21,300.00 ppm", "Water temperature: 31.20 C", "Fill water through the top flange.",
                "Use borate charges on the tank to dissolve them.", "Bottom front flange feeds the SLC pump.")));
        s.add(new Scenario("service-ads", i -> service(i, 2, 2, true, "Division: 2 | OPEN command", "Valves: 4 open / 8 connected",
                "Nitrogen charge: 82.50%", "Commands: Panel / computer", "Only matching division valves are commanded.", "Actuation is manual or player-programmed.")));
        s.add(new Scenario("service-srv", i -> service(i, 3, 1, false, "Division: 1 | Closed", "Commands: Redstone",
                "Relief outlet: downward to suppression pool", "Steam inlet: left-side flange")));
        s.add(new Scenario("service-discharge", i -> service(i, 4, 0, true, "Outfall mouth clear", "Water discharged: 1,204,500.00 kg",
                "Flow: 22,310.00 / 60,000 kg/s", "Outlet temperature: 41.20 C", "Water leaves the plant into the environment.")));
        return s;
    }

    private static void refuel(RefuellingMenu m) {
        m.open = true;
        m.assemblyCount = 764;
        m.loadedAssemblies = 722;
        m.aggregateKInf = 1.0731;
        m.kEffAllRodsOut = 1.0412;
        m.averageBurnup = 21500;
        m.peakBurnup = 41800;
        m.effectiveBeta = 0.00552;
        m.endOfCycle = true;
        m.bundlesInInventory = 8;
        m.map = map(31, 14, true);
    }

    private static CoolingMenu cooling(Inventory i, Design d) {
        var m = new CoolingMenu(99, i, P, P);
        m.present = true;
        m.ready = true;
        m.design = d;
        m.submerged = true;
        m.input = 120000;
        m.output = 340000;
        m.flow = 8600.5;
        m.loss = 41.7;
        m.heat = 1720.4;
        m.target = 0.8;
        m.actual = 0.78;
        m.inputC = 38.4;
        m.outputC = 24.1;
        m.energy = 800000;
        m.draw = 6200;
        m.connectedSources = 3;
        return m;
    }

    private static PowerModuleMenu power(Inventory i, int kind) {
        var m = new PowerModuleMenu(99, i, P, P);
        m.present = true;
        m.running = true;
        m.generator = kind == 2;
        m.hp = kind == 0;
        m.rpm = 1500;
        m.flow = 1612.3;
        m.shaftMW = 412.55;
        m.trainMW = 1788.2;
        m.electricMW = 1760.44;
        m.inletP = 962.1;
        m.outletP = 188.4;
        m.inletC = 278.4;
        m.outletC = 118.2;
        m.water = 120;
        m.energy = 1_250_000_000;
        m.hpCount = 2;
        m.lpCount = 4;
        m.generatorCount = 1;
        m.status = "Train coupled; steam admission steady; no cross-tie faults reported";
        return m;
    }

    private static TurbineValveMenu valve(Inventory i, boolean stop) {
        var m = new TurbineValveMenu(99, i, P);
        m.present = true;
        m.stop = stop;
        m.target = stop ? 1.0 : 0.65;
        m.position = stop ? 1.0 : 0.62;
        m.flow = 1522.4;
        return m;
    }

    private static SuppressionPoolMenu pool(Inventory i, boolean concrete, boolean spray) {
        var m = new SuppressionPoolMenu(99, i, P);
        m.present = true;
        m.formed = true;
        m.concrete = concrete;
        m.sprayMode = spray;
        m.massKg = 2_400_000;
        m.capacityKg = 3_100_000;
        m.temperatureC = 71.45;
        m.subcoolingC = 28.6;
        m.condensationEffectiveness = 0.94;
        m.uncondensedSteamKgPerS = 0.8;
        m.physicalCoolingMW = 14.2;
        m.rhrDuty = 0.6;
        m.rhrCapacityMW = 22;
        m.sprayHeaderKg = 5200;
        m.sprayFlow = 340.2;
        m.sprayCondensed = 255.1;
        m.passiveCoolingMW = 0.0141;
        m.steamIn = 41.62;
        m.steamBuffer = 84.5;
        m.boiling = false;
        return m;
    }

    private static Screen service(Inventory i, int kind, int division, boolean active, String... lines) {
        var m = new ServiceMenu(99, i, P);
        m.kind = kind;
        m.division = division;
        m.active = active;
        m.lines = List.of(lines);
        return new ServiceScreen(m, i, t(switch (kind) {
            case 1 -> "SLC Boron Solution Tank";
            case 2 -> "Division 2 ADS Controller";
            case 3 -> "Safety/Relief Valve";
            default -> "Water Discharge Port";
        }));
    }

    private static void click(Screen screen, String label) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(label)) {
                b.onPress();
                return;
            }
        }
        LOG.warn("GUI SCREEN CHECK: no button '{}' on {}", label, screen.getClass().getSimpleName());
    }

    /**
     * A left drag across three neighbouring positions selects all three in drag order, through the screen's own mouse
     * handlers; a drag that starts on a selected position deselects what it passes over.
     */
    private static void dragSelect(Screen screen) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof LatticeGridWidget grid && grid.visible) {
                grid.clearSelection();
                int start = -1;
                double[] at = null;
                for (int cell = 0; cell < 2000 && start < 0; cell++) {
                    try {
                        double[] centre = grid.cellCentre(cell);
                        int b = grid.cellAtPoint(centre[0] + centre[2], centre[1]);
                        int d = grid.cellAtPoint(centre[0] + 2 * centre[2], centre[1]);
                        if (grid.cellAtPoint(centre[0], centre[1]) == cell && b >= 0 && d >= 0 && b != cell && d != b) {
                            start = cell;
                            at = centre;
                        }
                    } catch (IndexOutOfBoundsException e) {
                        break;
                    }
                }
                if (start < 0) {
                    violations.add("refuelling-drag: no three neighbouring positions to drag over");
                    return;
                }
                int second = grid.cellAtPoint(at[0] + at[2], at[1]);
                int third = grid.cellAtPoint(at[0] + 2 * at[2], at[1]);
                drag(screen, at[0], at[1], at[2]);
                int[] picked = grid.selectedCells();
                if (!java.util.Arrays.equals(picked, new int[] {start, second, third})) {
                    violations.add("refuelling-drag: dragging over " + start + "," + second + "," + third + " selected "
                            + java.util.Arrays.toString(picked));
                }
                drag(screen, at[0], at[1], at[2]); // starts on a selected position: removes as it goes
                if (grid.selectedCells().length != 0) {
                    violations.add("refuelling-drag: a drag from a selected position left " + java.util.Arrays.toString(grid.selectedCells()));
                }
                drag(screen, at[0], at[1], at[2]); // select them again for the picture
                return;
            }
        }
        violations.add("refuelling-drag: no core map on the screen");
    }

    private static void drag(Screen screen, double x, double y, double cell) {
        screen.mouseClicked(x, y, 0);
        for (double step = 1; step <= 2 * cell; step++) {
            screen.mouseDragged(x + step, y, 0, 1, 0);
        }
        screen.mouseReleased(x + 2 * cell, y, 0);
    }

    private static void focusGrid(Screen screen, int steps) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof LatticeGridWidget grid && grid.visible) {
                screen.setFocused(grid);
                for (int i = 0; i < steps; i++) {
                    grid.keyPressed(i % 3 == 2 ? 264 : 262, 0, 0);
                }
                grid.keyPressed(257, 0, 0);
                return;
            }
        }
    }

    private static void focusButton(Screen screen, String label) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof BwrButton b && b.getMessage().getString().equals(label)) {
                screen.setFocused(b);
                return;
            }
        }
    }

    private static void hoverButton(Screen screen, String label) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof BwrButton b && b.getMessage().getString().equals(label)) {
                pointerX = b.getX() + b.getWidth() / 2;
                pointerY = b.getY() + b.getHeight() / 2;
                return;
            }
        }
    }

    private static void typeInto(Screen screen, String text) {
        for (GuiEventListener c : screen.children()) {
            if (c instanceof BwrEditBox box) {
                box.setValue(text);
                screen.setFocused(box);
                return;
            }
        }
    }

    // -----------------------------------------------------------------
    // Checks
    // -----------------------------------------------------------------

    /** Everything the screen draws that has a rectangle must lie inside the window, and widgets and slots must not collide. */
    static List<String> bounds(String name, Screen screen, int w, int h) {
        List<String> out = new ArrayList<>();
        List<AbstractWidget> widgets = new ArrayList<>();
        for (GuiEventListener c : screen.children()) {
            if (c instanceof AbstractWidget a && a.visible) {
                widgets.add(a);
                if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > w || a.getY() + a.getHeight() > h) {
                    out.add(name + ": widget '" + a.getMessage().getString() + "' " + a.getX() + "," + a.getY() + " "
                            + a.getWidth() + "x" + a.getHeight() + " outside " + w + "x" + h);
                }
            }
        }
        for (int i = 0; i < widgets.size(); i++) {
            for (int j = i + 1; j < widgets.size(); j++) {
                AbstractWidget p = widgets.get(i);
                AbstractWidget q = widgets.get(j);
                if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                    out.add(name + ": widgets '" + p.getMessage().getString() + "' and '" + q.getMessage().getString() + "' overlap");
                }
            }
        }
        if (screen instanceof AbstractContainerScreen<?> a) {
            int x = a.getGuiLeft(), y = a.getGuiTop(), pw = a.getXSize(), ph = a.getYSize();
            if (x < 0 || y < 0 || x + pw > w || y + ph > h) {
                out.add(name + ": panel " + x + "," + y + " " + pw + "x" + ph + " outside " + w + "x" + h);
            }
            for (AbstractWidget wd : widgets) {
                if (wd.getX() < x || wd.getY() < y || wd.getX() + wd.getWidth() > x + pw || wd.getY() + wd.getHeight() > y + ph) {
                    out.add(name + ": widget '" + wd.getMessage().getString() + "' is outside its panel");
                }
            }
            for (var slot : a.getMenu().slots) {
                if (slot.x < 0 || slot.y < 0 || slot.x + 16 > pw || slot.y + 16 > ph) {
                    out.add(name + ": slot " + slot.index + " at " + slot.x + "," + slot.y + " outside its panel " + pw + "x" + ph);
                }
                for (AbstractWidget wd : widgets) {
                    int sx = x + slot.x, sy = y + slot.y;
                    if (wd.getX() < sx + 16 && sx < wd.getX() + wd.getWidth() && wd.getY() < sy + 16 && sy < wd.getY() + wd.getHeight()) {
                        out.add(name + ": widget '" + wd.getMessage().getString() + "' covers slot " + slot.index);
                    }
                }
            }
        }
        return out;
    }

    /** Every string the style drew lies inside the panel: across always, down unless it was clipped scrolled content. */
    static List<String> textInsidePanel(String name, Screen screen, List<Ui.DrawnText> drawn) {
        List<String> out = new ArrayList<>();
        if (!(screen instanceof AbstractContainerScreen<?> a)) {
            return out;
        }
        int left = a.getGuiLeft(), top = a.getGuiTop(), right = left + a.getXSize(), bottom = top + a.getYSize();
        for (Ui.DrawnText t : drawn) {
            boolean across = t.x() >= left && t.x() + t.width() <= right;
            boolean down = t.clipped() || (t.y() >= top && t.y() + t.height() <= bottom);
            if (!across || !down) {
                out.add(name + ": text '" + t.text() + "' at " + t.x() + "," + t.y() + " " + t.width() + "x" + t.height()
                        + " leaves its panel " + left + "," + top + " " + a.getXSize() + "x" + a.getYSize());
            }
        }
        return out;
    }

    /**
     * A body that needs scrolling is a fallback for a window too small for the screen. The screens fit the smallest 16:9
     * GUI size (427 x 240) and every real scale of a 1080p window without it; at 320 x 240 (4:3 monitors) they may scroll.
     */
    static List<String> noBodyScroll(String name, Screen screen, boolean required) {
        List<String> out = new ArrayList<>();
        if (required && screen instanceof BwrScreen<?> b && b.bodyScrolls()) {
            out.add(name + ": the body does not fit and scrolls");
        }
        return out;
    }

    /** Collects what a widget says to a screen reader. */
    private static final class Say implements NarrationElementOutput {
        final List<String> titles = new ArrayList<>();
        boolean any;

        @Override
        public void add(NarratedElementType type, NarrationThunk<?> thunk) {
            thunk.getText(text -> {
                any = true;
                if (type == NarratedElementType.TITLE) {
                    titles.add(text);
                }
            });
        }

        @Override
        public NarrationElementOutput nest() {
            return this;
        }
    }

    /** Keyboard and narration: every enabled control is reachable with Tab, has a tooltip and says its name. */
    static List<String> accessibility(String name, Screen screen) {
        List<String> out = new ArrayList<>();
        List<AbstractWidget> controls = new ArrayList<>();
        for (GuiEventListener c : screen.children()) {
            if (c instanceof AbstractWidget a && a.visible && a.active) {
                controls.add(a);
            }
        }
        for (AbstractWidget control : controls) {
            Say say = new Say();
            control.updateNarration(say);
            if (say.titles.stream().allMatch(String::isBlank)) {
                out.add(name + ": control " + control.getClass().getSimpleName() + " at " + control.getX() + "," + control.getY() + " has no narration title");
            }
            boolean map = control instanceof LatticeGridWidget;
            if (!map && control.getTooltip() == null) {
                out.add(name + ": control '" + control.getMessage().getString() + "' has no tooltip");
            }
        }
        Set<GuiEventListener> reached = new HashSet<>();
        screen.clearFocus();
        for (int i = 0; i < controls.size() + 2; i++) {
            screen.keyPressed(258, 0, 0);
            reached.add(screen.getFocused());
        }
        for (AbstractWidget control : controls) {
            if (!reached.contains(control)) {
                out.add(name + ": control '" + control.getMessage().getString() + "' cannot be reached with Tab");
            }
        }
        screen.clearFocus();
        // a focused tab is activated by Space
        for (AbstractWidget control : controls) {
            if (control instanceof BwrTab tab && !tab.isSelected()) {
                screen.setFocused(tab);
                screen.keyPressed(32, 0, 0);
                if (!tab.isSelected()) {
                    out.add(name + ": Space did not select tab '" + tab.getMessage().getString() + "'");
                }
                screen.clearFocus();
                break;
            }
        }
        return out;
    }

    private static void fill(Inventory inv) {
        inv.clearContent();
        for (int i = 0; i < 9; i += 2) {
            inv.setItem(i, new ItemStack(BwrItems.FUEL_ASSEMBLY.get(), 1 + i));
        }
        inv.setItem(12, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 32));
        inv.setItem(20, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
    }

    // -----------------------------------------------------------------
    // Driver
    // -----------------------------------------------------------------

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.guiScreenCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++age > 12000) {
            throw new IllegalStateException("GUI screen check timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.createWorldOpenFlows().createFreshLevel("bwr-gui-check-" + System.currentTimeMillis(),
                    new LevelSettings("GUI check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || ++joined < 60) {
            return;
        }
        if (!built) {
            built = true;
            scenarios = build();
            directory = mc.gameDirectory.toPath().resolve("gui-check");
            clearPictures();
            fill(mc.player.getInventory());
            mc.options.hideGui = true;
            applyScale(mc, SCALES[0]);
        }
        boolean small = scaleIndex >= SCALES.length;
        if (stage >= scenarios.size()) {
            stage = 0;
            opened = false;
            scaleIndex++;
            if (scaleIndex >= SCALES.length + SMALL.length) {
                finish(mc);
            } else if (scaleIndex < SCALES.length) {
                applyScale(mc, SCALES[scaleIndex]);
            } else if (scaleIndex == SCALES.length) {
                applyScale(mc, 2);
            }
            return;
        }
        Scenario sc = scenarios.get(stage);
        if (!opened) {
            opened = true;
            wait = 0;
            mc.setScreen(sc.make().apply(mc.player.getInventory()));
            pointerX = -1000;
            pointerY = -1000;
            resized = false;
            sc.setup().accept(mc.screen);
            Ui.startProbe();
            return;
        }
        if (small) {
            int[] size = SMALL[scaleIndex - SCALES.length];
            Ui.endProbe();
            if (sc.interactive()) {
                next();
                return;
            }
            String at = sc.name() + " @" + size[0] + "x" + size[1];
            if (!resized) {
                resized = true;
                wait = 0;
                mc.screen.resize(mc, size[0], size[1]);
                Ui.startProbe();
                violations.addAll(bounds(at, mc.screen, size[0], size[1]));
                violations.addAll(noBodyScroll(at, mc.screen, size[0] >= 400));
                if (!FLOOR_PICTURES.contains(sc.name())) {
                    Ui.endProbe();
                    next();
                }
                return;
            }
            if (++wait < 4) {
                return;
            }
            violations.addAll(textInsidePanel(at, mc.screen, Ui.endProbe()));
            try {
                Path folder = directory.resolve("floor");
                Files.createDirectories(folder);
                CheckShots.save(mc, folder.resolve(String.format("%dx%d-%s.png", size[0], size[1], sc.name())));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            next();
            return;
        }
        if (++wait == 5) {
            sc.late().accept(mc.screen);
        }
        if (wait < 9) {
            return;
        }
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        String label = sc.name() + " @scale" + SCALES[scaleIndex];
        violations.addAll(bounds(label, mc.screen, w, h));
        violations.addAll(noBodyScroll(label, mc.screen, true));
        violations.addAll(textInsidePanel(label, mc.screen, Ui.endProbe()));
        try {
            Path folder = directory.resolve("scale" + SCALES[scaleIndex]);
            Files.createDirectories(folder);
            CheckShots.save(mc, folder.resolve(String.format("%02d-%s.png", stage, sc.name())));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (scaleIndex == 0 && !sc.interactive()) {
            violations.addAll(accessibility(label, mc.screen));
        }
        next();
    }

    /** Removes the pictures of an earlier run, so a folder never mixes two runs. */
    private static void clearPictures() {
        for (int scale : SCALES) {
            Path folder = directory.resolve("scale" + scale);
            if (Files.isDirectory(folder)) {
                try (var files = Files.list(folder)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void next() {
        stage++;
        opened = false;
    }

    private static void applyScale(Minecraft mc, int scale) {
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
        LOG.info("GUI SCREEN CHECK: GUI scale {} -> {}x{} scaled", scale, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        wait = 0;
    }

    /** Escape closes a machine screen, with an edit box focused or not. */
    private static void escapeCloses(Minecraft mc) {
        Inventory inventory = mc.player.getInventory();
        for (boolean typing : new boolean[]{false, true}) {
            Screen screen = new PumpControlScreen(pump(inventory, false, "rhr"), inventory, t("RHR / LPCI Pump"));
            mc.setScreen(screen);
            if (typing) {
                typeInto(screen, "50");
            }
            screen.keyPressed(256, 0, 0);
            if (mc.screen == screen) {
                violations.add("pump panel: Escape did not close the screen" + (typing ? " while the speed field was focused" : ""));
                mc.setScreen(null);
            }
        }
    }

    private static void finish(Minecraft mc) {
        escapeCloses(mc);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("violations.txt"), String.join("\n", violations) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (String v : violations) {
            LOG.warn("GUI SCREEN CHECK VIOLATION {}", v);
        }
        boolean lenient = Boolean.getBoolean("bwr.guiCheckLenient");
        if (!violations.isEmpty() && !lenient) {
            mc.stop();
            throw new AssertionError("GUI SCREEN CHECK FAIL: " + violations.size() + " violations, first: " + violations.get(0));
        }
        mc.setScreen(null);
        LOG.info("GUI SCREEN CHECK {}: {} screens x 3 scales + 2 minimum sizes, {} violations", violations.isEmpty() ? "PASS" : "DONE (lenient)",
                scenarios.size(), violations.size());
        mc.stop();
    }
}
