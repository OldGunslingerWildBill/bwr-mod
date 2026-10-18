package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.FuelTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registry: block items, plus the creative tab that collects them. */
public final class BwrItems {

    private BwrItems() {
    }

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BwrMod.MOD_ID);
    public static final DeferredItem<BlockItem> FE_METER=ITEMS.registerSimpleBlockItem(BwrBlocks.FE_METER);
    public static final DeferredItem<BlockItem> GRID_TIE=ITEMS.registerSimpleBlockItem(BwrBlocks.GRID_TIE);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BwrMod.MOD_ID);

    public static final DeferredItem<BlockItem> REACTOR_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REACTOR_CONTROLLER);
    public static final DeferredItem<BlockItem> CONTAINMENT_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_CONTROLLER);
    public static final DeferredItem<BlockItem> CONTAINMENT_VAULT_DOOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_VAULT_DOOR);
    public static final DeferredItem<BlockItem> CONTAINMENT_VAULT_DOOR_4X4 =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4);
    public static final DeferredItem<BlockItem> CONTAINMENT_AIRLOCK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_AIRLOCK);
    public static final DeferredItem<BlockItem> CONTAINMENT_NITROGEN_SUPPLY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_NITROGEN_SUPPLY);
    public static final DeferredItem<BlockItem> CONTAINMENT_VENT_VALVE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_VENT_VALVE);
    public static final DeferredItem<BlockItem> CONTAINMENT_SPRAY_HEADER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_SPRAY_HEADER);
    public static final DeferredItem<Item> NITROGEN_CANISTER =
            ITEMS.registerSimpleItem("nitrogen_canister");
    public static final DeferredItem<BlockItem> CONTAINMENT_STEAM_PENETRATION =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_STEAM_PENETRATION);
    public static final DeferredItem<BlockItem> CONTAINMENT_WATER_PENETRATION =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_WATER_PENETRATION);
    public static final DeferredItem<BlockItem> CONTAINMENT_PASS_THROUGH =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_PASS_THROUGH);
    public static final DeferredItem<BlockItem> CONTAINMENT_POWER_PENETRATION =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_POWER_PENETRATION);
    public static final DeferredItem<BlockItem> CONTAINMENT_DATA_PENETRATION =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTAINMENT_DATA_PENETRATION);
    public static final DeferredItem<BlockItem> REACTOR_VESSEL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REACTOR_VESSEL);
    public static final DeferredItem<BlockItem> CORIUM =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CORIUM);

    // --- Control panels (CONTROL-PANELS-SPEC) ---------------------------------
    public static final DeferredItem<BlockItem> CONTROL_PANEL_VERTICAL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_VERTICAL);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_BENCHBOARD =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_BENCHBOARD);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CONSOLE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CONSOLE);
    public static final DeferredItem<dev.bwr.mod.panel.PanelWrenchItem> PANEL_WRENCH =
            ITEMS.register("panel_wrench", () -> new dev.bwr.mod.panel.PanelWrenchItem(new Item.Properties().stacksTo(1)));
    // One item per component type, registered under the type's own id. Add a line per new type.
    public static final DeferredItem<dev.bwr.mod.panel.PanelLinkerItem> PANEL_LINKER =
            ITEMS.register("panel_linker", () -> new dev.bwr.mod.panel.PanelLinkerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> NAMEPLATE =
            ITEMS.register("nameplate", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.NAMEPLATE.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> LAMP =
            ITEMS.register("lamp", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.LAMP.id()));
    // inputs and indicators (spec 2.1, first two tables)
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> PUSHBUTTON = ITEMS.register("pushbutton", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.PUSHBUTTON.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> TOGGLE_SWITCH = ITEMS.register("toggle_switch", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.TOGGLE_SWITCH.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> ROTARY_SELECTOR = ITEMS.register("rotary_selector", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.ROTARY_SELECTOR.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> CONTROL_SWITCH = ITEMS.register("control_switch", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.CONTROL_SWITCH.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> KEY_SWITCH = ITEMS.register("key_switch", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.KEY_SWITCH.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> SETPOINT_KNOB = ITEMS.register("setpoint_knob", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.SETPOINT_KNOB.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> THUMBWHEEL = ITEMS.register("thumbwheel", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.THUMBWHEEL.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> ANNUNCIATOR = ITEMS.register("annunciator", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.ANNUNCIATOR.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> BAR_GRAPH = ITEMS.register("bar_graph", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.BAR_GRAPH.id()));
    /** Draws flow lines with arrows on control boards (MIMIC-TAPE-SPEC.md). A stack of tape; each path costs some. */
    public static final DeferredItem<dev.bwr.mod.panel.mimic.MimicTapeItem> MIMIC_TAPE =
            ITEMS.register("mimic_tape", () -> new dev.bwr.mod.panel.mimic.MimicTapeItem(new Item.Properties()));
    public static final DeferredItem<dev.bwr.mod.panel.component.PanelKeyItem> PANEL_KEY =
            ITEMS.register("panel_key", () -> new dev.bwr.mod.panel.component.PanelKeyItem(new Item.Properties().stacksTo(1)));


    // Gauges, displays and mimic (spec 2.1, 2.2)
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> DIAL_GAUGE =
            ITEMS.register("dial_gauge", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.DIAL_GAUGE.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> EDGEWISE_GAUGE_VERTICAL =
            ITEMS.register("edgewise_gauge_vertical", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.EDGEWISE_GAUGE_VERTICAL.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> EDGEWISE_GAUGE_HORIZONTAL =
            ITEMS.register("edgewise_gauge_horizontal", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> STRIP_CHART =
            ITEMS.register("strip_chart", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.STRIP_CHART.id()));
    /** The Yokogawa DX1000: the paperless recorder at its one size, 16 cells (one block). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> PAPERLESS_RECORDER =
            ITEMS.register("paperless_recorder", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.PAPERLESS_RECORDER.id(), new int[] {0}));
    /** The Yokogawa DX2000: the same recorder at its one size, 32 cells (2 x 2 blocks). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> PAPERLESS_RECORDER_DX2000 =
            ITEMS.register("paperless_recorder_dx2000", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.PAPERLESS_RECORDER.id(), new int[] {1}));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> SEVEN_SEGMENT_DISPLAY =
            ITEMS.register("seven_segment_display", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.SEVEN_SEGMENT_DISPLAY.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> SIXTEEN_SEGMENT_DISPLAY =
            ITEMS.register("sixteen_segment_display", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.SIXTEEN_SEGMENT_DISPLAY.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> CHARACTER_LCD =
            ITEMS.register("character_lcd", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.CHARACTER_LCD.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> GRAPHIC_DISPLAY =
            ITEMS.register("graphic_display", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.GRAPHIC_DISPLAY.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> MIMIC_LINE =
            ITEMS.register("mimic_line", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.MIMIC_LINE.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> MIMIC_SYMBOL =
            ITEMS.register("mimic_symbol", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.MIMIC_SYMBOL.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> FULL_CORE_DISPLAY =
            ITEMS.register("full_core_display", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.FULL_CORE_DISPLAY.id()));
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> ROD_SELECT_MATRIX =
            ITEMS.register("rod_select_matrix", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.ROD_SELECT_MATRIX.id()));
    /** The owner's vertical bar gauge (NEW-HARDWARE-SPEC.md). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> BAR_GAUGE =
            ITEMS.register("bar_gauge", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.BAR_GAUGE.id()));
    /** The owner's two guarded RPS trip buttons, A and B (NEW-HARDWARE-SPEC.md). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> RPS_TRIP =
            ITEMS.register("rps_trip", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.RPS_TRIP.id()));
    /** The ADS manual activation push-to-turn switch (NEW-HARDWARE-SPEC.md). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> PUSH_TURN_SWITCH =
            ITEMS.register("push_turn_switch", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.PUSH_TURN_SWITCH.id()));
    public static final DeferredItem<BlockItem> CONTROL_ROD_DRIVE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_ROD_DRIVE);
    public static final DeferredItem<BlockItem> CORE_SPRAY_SPARGER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CORE_SPRAY_SPARGER);
    public static final DeferredItem<BlockItem> RECIRCULATION_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RECIRCULATION_PUMP);
    public static final DeferredItem<BlockItem> RIP_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RIP_PUMP);
    public static final DeferredItem<BlockItem> JET_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.JET_PUMP);
    public static final DeferredItem<BlockItem> SAFETY_RELIEF_VALVE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SAFETY_RELIEF_VALVE);
    public static final DeferredItem<BlockItem> MSIV =
            ITEMS.register("msiv", () -> new dev.bwr.mod.steam.MainSteamIsolationValveItem(BwrBlocks.MSIV.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> PRESSURISED_TUBE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.PRESSURISED_TUBE);
    public static final DeferredItem<BlockItem> HIGH_PRESSURE_WATER_PIPE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HIGH_PRESSURE_WATER_PIPE);
    public static final DeferredItem<BlockItem> RPV_WATER_INJECTION_PORT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RPV_WATER_INJECTION_PORT);
    public static final DeferredItem<BlockItem> RECIRCULATION_OUTLET = ITEMS.registerSimpleBlockItem(BwrBlocks.RECIRCULATION_OUTLET);
    public static final DeferredItem<BlockItem> RECIRCULATION_INLET = ITEMS.registerSimpleBlockItem(BwrBlocks.RECIRCULATION_INLET);
    public static final DeferredItem<BlockItem> RPV_STEAM_OUTLET =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RPV_STEAM_OUTLET);
    public static final DeferredItem<BlockItem> TURBINE_STEAM_OUTLET =
            ITEMS.registerSimpleBlockItem(BwrBlocks.TURBINE_STEAM_OUTLET);
    public static final DeferredItem<BlockItem> FUEL_FABRICATOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.FUEL_FABRICATOR);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_CONTROLLER);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_WALL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_WALL);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_SUCTION = ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_SUCTION);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_RETURN = ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_RETURN);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_DRAIN = ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_DRAIN);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_STEAM_INLET = ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_STEAM_INLET);
    public static final DeferredItem<BlockItem> RHR_HEAT_EXCHANGER = ITEMS.registerSimpleBlockItem(BwrBlocks.RHR_HEAT_EXCHANGER);
    public static final DeferredItem<BlockItem> SUPPRESSION_POOL_QUENCHER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SUPPRESSION_POOL_QUENCHER);
    public static final DeferredItem<BlockItem> RCIC_TURBINE_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RCIC_TURBINE_PUMP);
    public static final DeferredItem<BlockItem> HPCI_TURBINE_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPCI_TURBINE_PUMP);
    public static final DeferredItem<BlockItem> HPCS_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPCS_PUMP);
    public static final DeferredItem<BlockItem> LPCS_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.LPCS_PUMP);
    public static final DeferredItem<BlockItem> RHR_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RHR_PUMP);
    public static final DeferredItem<BlockItem> SLC_BORON_TANK = ITEMS.registerSimpleBlockItem(BwrBlocks.SLC_BORON_TANK);
    public static final DeferredItem<BlockItem> ADS_RELIEF_VALVE = ITEMS.registerSimpleBlockItem(BwrBlocks.ADS_RELIEF_VALVE);
    public static final DeferredItem<BlockItem> WATER_DISCHARGE_PORT = ITEMS.registerSimpleBlockItem(BwrBlocks.WATER_DISCHARGE_PORT);
    public static final DeferredItem<Item> BORATE_CHARGE = ITEMS.registerSimpleItem("borate_charge",new Item.Properties());
    public static final DeferredItem<BlockItem> SLC_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SLC_PUMP);
    public static final DeferredItem<BlockItem> RCIC_TWL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RCIC_TWL);
    public static final DeferredItem<BlockItem> HPCI_TURBINE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPCI_TURBINE);
    public static final DeferredItem<BlockItem> ADS_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.ADS_CONTROLLER);
    public static final DeferredItem<BlockItem> CONDENSATE_STORAGE_TANK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONDENSATE_STORAGE_TANK);
    public static final DeferredItem<BlockItem> MOTOR_FEED_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.MOTOR_FEED_PUMP);
    public static final DeferredItem<BlockItem> STEAM_STOP_VALVE = ITEMS.registerSimpleBlockItem(BwrBlocks.STEAM_STOP_VALVE);
    public static final DeferredItem<BlockItem> TURBINE_CONTROL_VALVE = ITEMS.registerSimpleBlockItem(BwrBlocks.TURBINE_CONTROL_VALVE);
    public static final DeferredItem<BlockItem> BYPASS_STEAM_VALVE = ITEMS.registerSimpleBlockItem(BwrBlocks.BYPASS_STEAM_VALVE);
    public static final DeferredItem<BlockItem> ARABELLE_CONDENSER = ITEMS.register("arabelle_condenser",
            () -> new dev.bwr.mod.condenser.CondenserItem(BwrBlocks.ARABELLE_CONDENSER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> NATURAL_DRAFT_TOWER = ITEMS.register("natural_draft_tower",()->new dev.bwr.mod.cooling.CoolingItem(BwrBlocks.NATURAL_DRAFT_TOWER.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> MECHANICAL_DRAFT_TOWER = ITEMS.register("mechanical_draft_tower",()->new dev.bwr.mod.cooling.CoolingItem(BwrBlocks.MECHANICAL_DRAFT_TOWER.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> CIRCULATING_WATER_PUMP = ITEMS.register("circulating_water_pump",()->new dev.bwr.mod.cooling.CoolingItem(BwrBlocks.CIRCULATING_WATER_PUMP.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> MAKEUP_WATER_PUMP = ITEMS.register("makeup_water_pump",()->new dev.bwr.mod.cooling.CoolingItem(BwrBlocks.MAKEUP_WATER_PUMP.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> SCREENED_WATER_INTAKE = ITEMS.register("screened_water_intake",()->new dev.bwr.mod.cooling.CoolingItem(BwrBlocks.SCREENED_WATER_INTAKE.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> HP_TURBINE = ITEMS.register("hp_turbine", () -> new dev.bwr.mod.power.PowerModuleItem(BwrBlocks.HP_TURBINE.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> LP_TURBINE = ITEMS.register("lp_turbine", () -> new dev.bwr.mod.power.PowerModuleItem(BwrBlocks.LP_TURBINE.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> NUCLEAR_GENERATOR = ITEMS.register("nuclear_generator", () -> new dev.bwr.mod.power.PowerModuleItem(BwrBlocks.NUCLEAR_GENERATOR.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> TURBINE_FEED_PUMP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.TURBINE_FEED_PUMP);

    /**
     * One fuel bundle. Not a block: assemblies live in inventories and get
     * shuffled between core positions, and everything that has happened to one
     * rides along in its {@code bwr:fuel_assembly} data component. Stack size 1
     * because two bundles with different exposure are not interchangeable — that
     * is the entire mechanic (SPEC 2.3).
     */
    public static final DeferredItem<FuelAssemblyItem> FUEL_ASSEMBLY =
            ITEMS.register("fuel_assembly",
                    () -> new FuelAssemblyItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<dev.bwr.mod.fuel.SpecialtyRodItem> SPECIALTY_ROD =
            ITEMS.register("specialty_rod", () -> new dev.bwr.mod.fuel.SpecialtyRodItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> IRRADIATION_CASING = ITEMS.registerSimpleItem("irradiation_casing");
    public static final DeferredItem<Item> COBALT_CHARGE = ITEMS.registerSimpleItem("cobalt_charge");
    public static final DeferredItem<Item> LITHIUM_CHARGE = ITEMS.registerSimpleItem("lithium_charge");
    public static final DeferredItem<Item> COBALT_SAMPLE = ITEMS.registerSimpleItem("cobalt_sample");
    public static final DeferredItem<Item> TRITIUM_SAMPLE = ITEMS.register("tritium_sample", () -> new dev.bwr.mod.fuel.TritiumSampleItem(new Item.Properties()));
    public static final DeferredItem<Item> DOPED_SILICON = ITEMS.registerSimpleItem("doped_silicon");

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FUEL_TAB = TABS.register("fuel", () ->
            CreativeModeTab.builder().title(Component.translatable("itemGroup.bwr.fuel"))
                    .icon(() -> FUEL_ASSEMBLY.get().getDefaultInstance()).displayItems((parameters, output) -> {
                        for (var type : FuelTypes.catalogue()) output.accept(FuelAssemblyItem.stackOf(FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(type)));
                        for (var kind : dev.bwr.core.fuel.CoreInsert.Kind.values()) output.accept(dev.bwr.mod.fuel.SpecialtyRodItem.stack(new dev.bwr.mod.fuel.CoreInsertData(kind, 0)));
                        for (var item : java.util.List.of(IRRADIATION_CASING, COBALT_CHARGE, LITHIUM_CHARGE, COBALT_SAMPLE, TRITIUM_SAMPLE, DOPED_SILICON)) output.accept(item.get());
                    }).build());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bwr"))
                    .icon(() -> REACTOR_CONTROLLER.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(REACTOR_CONTROLLER.get());
                        output.accept(CONTAINMENT_CONTROLLER.get());
                        output.accept(CONTAINMENT_VAULT_DOOR.get());
                        output.accept(CONTAINMENT_VAULT_DOOR_4X4.get());
                        output.accept(CONTAINMENT_AIRLOCK.get());
                        output.accept(CONTAINMENT_NITROGEN_SUPPLY.get());
                        output.accept(CONTAINMENT_VENT_VALVE.get());
                        output.accept(CONTAINMENT_SPRAY_HEADER.get());
                        output.accept(NITROGEN_CANISTER.get());
                        output.accept(CONTAINMENT_STEAM_PENETRATION.get());
                        output.accept(CONTAINMENT_WATER_PENETRATION.get());
                        output.accept(CONTAINMENT_PASS_THROUGH.get());
                        output.accept(CONTAINMENT_POWER_PENETRATION.get());
                        output.accept(CONTAINMENT_DATA_PENETRATION.get());
                        output.accept(REACTOR_VESSEL.get());
                        output.accept(CONTROL_ROD_DRIVE.get());
                        output.accept(CORE_SPRAY_SPARGER.get());
                        output.accept(RECIRCULATION_PUMP.get());
                        output.accept(JET_PUMP.get());
                        output.accept(RIP_PUMP.get());
                        output.accept(SAFETY_RELIEF_VALVE.get());
                        output.accept(MSIV.get());
                        output.accept(STEAM_STOP_VALVE.get());
                        output.accept(TURBINE_CONTROL_VALVE.get());
                        output.accept(BYPASS_STEAM_VALVE.get());
                        output.accept(ARABELLE_CONDENSER.get());
                        output.accept(NATURAL_DRAFT_TOWER.get());
                        output.accept(MECHANICAL_DRAFT_TOWER.get());
                        output.accept(CIRCULATING_WATER_PUMP.get());
                        output.accept(MAKEUP_WATER_PUMP.get());
                        output.accept(SCREENED_WATER_INTAKE.get());
                        output.accept(PRESSURISED_TUBE.get());
                        output.accept(HIGH_PRESSURE_WATER_PIPE.get());
                        output.accept(RPV_WATER_INJECTION_PORT.get());
                        output.accept(RECIRCULATION_OUTLET.get());
                        output.accept(RECIRCULATION_INLET.get());
                        output.accept(RPV_STEAM_OUTLET.get());
                        output.accept(TURBINE_STEAM_OUTLET.get());
                        output.accept(FUEL_FABRICATOR.get());
                        output.accept(SUPPRESSION_POOL_CONTROLLER.get());
                        output.accept(SUPPRESSION_POOL_WALL.get());
                        output.accept(SUPPRESSION_POOL_SUCTION.get());
                        output.accept(SUPPRESSION_POOL_RETURN.get());
                        output.accept(SUPPRESSION_POOL_STEAM_INLET.get());
            output.accept(SUPPRESSION_POOL_DRAIN.get());
                        output.accept(RHR_HEAT_EXCHANGER.get());
                        // Legacy quencher registry retained only for existing saves.
                        output.accept(HPCS_PUMP.get());
                        output.accept(LPCS_PUMP.get());
                        output.accept(RHR_PUMP.get());
                        output.accept(SLC_PUMP.get());
                        output.accept(SLC_BORON_TANK.get());
                        output.accept(BORATE_CHARGE.get());
                        output.accept(ADS_RELIEF_VALVE.get());
                        output.accept(WATER_DISCHARGE_PORT.get());
                        output.accept(RCIC_TWL.get());
                        output.accept(HPCI_TURBINE.get());
                        output.accept(ADS_CONTROLLER.get());
                        output.accept(CONDENSATE_STORAGE_TANK.get());
                        output.accept(MOTOR_FEED_PUMP.get());
                        output.accept(HP_TURBINE.get());
                        output.accept(LP_TURBINE.get());
                        output.accept(NUCLEAR_GENERATOR.get());
                        output.accept(GRID_TIE.get());
                        output.accept(FE_METER.get());
                        output.accept(TURBINE_FEED_PUMP.get());
                        output.accept(CORIUM.get());
                        output.accept(CONTROL_PANEL_VERTICAL.get());
                        output.accept(CONTROL_PANEL_BENCHBOARD.get());
                        output.accept(CONTROL_PANEL_CONSOLE.get());
                        output.accept(PANEL_WRENCH.get());
                        output.accept(PANEL_LINKER.get());
                        output.accept(MIMIC_TAPE.get());
                        output.accept(NAMEPLATE.get());
                        output.accept(LAMP.get());
                        output.accept(PUSHBUTTON.get());
                        output.accept(TOGGLE_SWITCH.get());
                        output.accept(ROTARY_SELECTOR.get());
                        output.accept(CONTROL_SWITCH.get());
                        output.accept(KEY_SWITCH.get());
                        output.accept(PANEL_KEY.get());
                        output.accept(SETPOINT_KNOB.get());
                        output.accept(THUMBWHEEL.get());
                        output.accept(ANNUNCIATOR.get());
                        output.accept(BAR_GRAPH.get());
                        output.accept(DIAL_GAUGE.get());
                        output.accept(EDGEWISE_GAUGE_VERTICAL.get());
                        output.accept(EDGEWISE_GAUGE_HORIZONTAL.get());
                        output.accept(STRIP_CHART.get());
                        output.accept(PAPERLESS_RECORDER.get());
                        output.accept(PAPERLESS_RECORDER_DX2000.get());
                        output.accept(SEVEN_SEGMENT_DISPLAY.get());
                        output.accept(SIXTEEN_SEGMENT_DISPLAY.get());
                        output.accept(CHARACTER_LCD.get());
                        output.accept(GRAPHIC_DISPLAY.get());
                        output.accept(MIMIC_LINE.get());
                        output.accept(MIMIC_SYMBOL.get());
                        output.accept(FULL_CORE_DISPLAY.get());
                        output.accept(ROD_SELECT_MATRIX.get());
                        output.accept(BAR_GAUGE.get());
                        output.accept(RPS_TRIP.get());
                        output.accept(PUSH_TURN_SWITCH.get());
                    })
                    .build());
}
