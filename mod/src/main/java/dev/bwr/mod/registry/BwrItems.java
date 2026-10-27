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
    public static final DeferredItem<BlockItem> AREA_RADIATION_MONITOR = ITEMS.register("area_radiation_monitor",
            () -> new dev.bwr.mod.radiation.AreaRadiationMonitorItem(BwrBlocks.AREA_RADIATION_MONITOR.get(), new Item.Properties()));
    public static final DeferredItem<dev.bwr.mod.radiation.SurveyMeterItem> SURVEY_METER =
            ITEMS.register("survey_meter", () -> new dev.bwr.mod.radiation.SurveyMeterItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.bwr.mod.guide.GuideBookItem> GUIDE_BOOK =
            ITEMS.register("guide_book", () -> new dev.bwr.mod.guide.GuideBookItem(new Item.Properties().stacksTo(1)));
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
    public static final DeferredItem<BlockItem> CONTROL_PANEL_BACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_BACK);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_DUPLEX =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_DUPLEX);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_DESK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_DESK);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_DESK_WIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_DESK_WIDE);
    public static final DeferredItem<dev.bwr.mod.panel.PanelWrenchItem> PANEL_WRENCH =
            ITEMS.register("panel_wrench", () -> new dev.bwr.mod.panel.PanelWrenchItem(new Item.Properties().stacksTo(1)));
    // One item per component type, registered under the type's own id. Add a line per new type.
    public static final DeferredItem<dev.bwr.mod.panel.PanelLinkerItem> PANEL_LINKER =
            ITEMS.register("panel_linker", () -> new dev.bwr.mod.panel.PanelLinkerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.bwr.mod.panel.NetworkingWrenchItem> NETWORKING_WRENCH =
            ITEMS.register("networking_wrench", () -> new dev.bwr.mod.panel.NetworkingWrenchItem(new Item.Properties().stacksTo(1)));
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
    /** The manual/auto controller station (a hand-auto station: PV and setpoint pointers, demand bar, AUTO/MAN, RAISE/LOWER). */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> CONTROLLER_STATION =
            ITEMS.register("controller_station", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.CONTROLLER_STATION.id()));
    /** The synchroscope: a round phase meter with two synchronising lamps. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> SYNCHROSCOPE =
            ITEMS.register("synchroscope", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.SYNCHROSCOPE.id()));
    /** The two pen strip recorder: the strip chart with two coloured pens, each on its own scale. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> TWO_PEN_RECORDER =
            ITEMS.register("two_pen_recorder", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.TWO_PEN_RECORDER.id()));
    /** The joystick bypass switch: a Square-D style four way lever with up, down, left, right and centre. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> JOYSTICK_SWITCH =
            ITEMS.register("joystick_switch", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.JOYSTICK_SWITCH.id()));
    /** The LED rod pattern display: two small LEDs, amber and red, for every rod, in core layout. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> LED_ROD_DISPLAY =
            ITEMS.register("led_rod_display", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.LED_ROD_DISPLAY.id()));
    /** The plant computer: a safety parameter display for a CC program, or with built-in reactor pages. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> PLANT_COMPUTER =
            ITEMS.register("plant_computer", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.PLANT_COMPUTER.id()));
    /** The ACK, RESET, SILENCE and TEST pushbuttons of an annunciator box in one part. */
    public static final DeferredItem<dev.bwr.mod.panel.component.ComponentItem> ANNUNCIATOR_CONTROL =
            ITEMS.register("annunciator_control", () -> new dev.bwr.mod.panel.component.ComponentItem(new Item.Properties(),
                    dev.bwr.mod.panel.component.ComponentTypes.ANNUNCIATOR_CONTROL.id()));
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
    public static final DeferredItem<BlockItem> RPV_BOTTOM_DRAIN_PORT = ITEMS.registerSimpleBlockItem(BwrBlocks.RPV_BOTTOM_DRAIN_PORT);
    public static final DeferredItem<BlockItem> RWCU_PUMP = ITEMS.registerSimpleBlockItem(BwrBlocks.RWCU_PUMP);
    public static final DeferredItem<BlockItem> RWCU_REGENERATIVE_HEAT_EXCHANGER = ITEMS.registerSimpleBlockItem(BwrBlocks.RWCU_REGENERATIVE_HEAT_EXCHANGER);
    public static final DeferredItem<BlockItem> RWCU_NONREGENERATIVE_HEAT_EXCHANGER = ITEMS.registerSimpleBlockItem(BwrBlocks.RWCU_NONREGENERATIVE_HEAT_EXCHANGER);
    public static final DeferredItem<BlockItem> RWCU_FILTER_DEMINERALIZER = ITEMS.registerSimpleBlockItem(BwrBlocks.RWCU_FILTER_DEMINERALIZER);
    /** A new element for the RWCU stainless steel nanofilter (the id is the old resin bed's, kept so recipes and worlds load). */
    public static final DeferredItem<Item> DEMINERALIZER_RESIN = ITEMS.registerSimpleItem("demineralizer_resin");
    /** Radioactive waste: what a bed that has been in service is. Carries its activity. */
    public static final DeferredItem<Item> SPENT_RESIN = ITEMS.register("spent_resin", () -> new dev.bwr.mod.rwcu.SpentResinItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<BlockItem> RECIRCULATION_OUTLET = ITEMS.registerSimpleBlockItem(BwrBlocks.RECIRCULATION_OUTLET);
    public static final DeferredItem<BlockItem> RECIRCULATION_INLET = ITEMS.registerSimpleBlockItem(BwrBlocks.RECIRCULATION_INLET);
    public static final DeferredItem<BlockItem> RPV_STEAM_OUTLET =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RPV_STEAM_OUTLET);
    public static final DeferredItem<BlockItem> TURBINE_STEAM_OUTLET =
            ITEMS.registerSimpleBlockItem(BwrBlocks.TURBINE_STEAM_OUTLET);
    public static final DeferredItem<BlockItem> FUEL_FABRICATOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.FUEL_FABRICATOR);
    public static final DeferredItem<BlockItem> REPROCESSING_PLANT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REPROCESSING_PLANT);
    public static final DeferredItem<BlockItem> ISOTOPE_SEPARATOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.ISOTOPE_SEPARATOR);
    public static final DeferredItem<BlockItem> RTG =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RTG);
    /** The Large RTG: one item places the whole 2 x 2 x 3 assembly (RTG.md). */
    public static final DeferredItem<BlockItem> LARGE_RTG =
            ITEMS.registerSimpleBlockItem(BwrBlocks.LARGE_RTG);
    /** The xenon floodlight and high-bay items. */
    public static final DeferredItem<BlockItem> XENON_FLOODLIGHT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.XENON_FLOODLIGHT);
    public static final DeferredItem<BlockItem> XENON_HIGH_BAY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.XENON_HIGH_BAY);
    public static final DeferredItem<BlockItem> HPS_FLAT_PANEL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPS_FLAT_PANEL);
    public static final DeferredItem<BlockItem> SHIELD_PLUG_CONCRETE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SHIELD_PLUG_CONCRETE);
    public static final DeferredItem<BlockItem> HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE);
    public static final DeferredItem<BlockItem> ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE);
    public static final DeferredItem<BlockItem> SPENT_FUEL_POOL_WALL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_POOL_WALL);
    public static final DeferredItem<BlockItem> SPENT_FUEL_POOL_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER);
    public static final DeferredItem<BlockItem> SPENT_FUEL_RACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_RACK);
    public static final DeferredItem<BlockItem> SPENT_FUEL_POOL_MAKEUP_PORT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT);
    public static final DeferredItem<BlockItem> SPENT_FUEL_POOL_COOLING_IN =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_POOL_COOLING_IN);
    public static final DeferredItem<BlockItem> SPENT_FUEL_POOL_COOLING_OUT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SPENT_FUEL_POOL_COOLING_OUT);
    public static final DeferredItem<BlockItem> REFUELLING_RAIL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REFUELLING_RAIL);
    public static final DeferredItem<BlockItem> TRANSFER_CANAL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.TRANSFER_CANAL);
    public static final DeferredItem<BlockItem> CANAL_GATE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CANAL_GATE);
    public static final DeferredItem<BlockItem> CANAL_GATE_PLUG =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CANAL_GATE_PLUG);
    public static final DeferredItem<BlockItem> GATE_LAYDOWN_STAND =
            ITEMS.registerSimpleBlockItem(BwrBlocks.GATE_LAYDOWN_STAND);
    public static final DeferredItem<BlockItem> NEW_FUEL_ELEVATOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.NEW_FUEL_ELEVATOR);
    public static final DeferredItem<BlockItem> EQUIPMENT_PAD =
            ITEMS.registerSimpleBlockItem(BwrBlocks.EQUIPMENT_PAD);
    public static final DeferredItem<BlockItem> REFUELLING_BRIDGE_CONSOLE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REFUELLING_BRIDGE_CONSOLE);
    public static final DeferredItem<BlockItem> BUILDING_CRANE_CONSOLE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.BUILDING_CRANE_CONSOLE);
    public static final DeferredItem<BlockItem> CRANE_RUNWAY_RAIL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CRANE_RUNWAY_RAIL);
    public static final DeferredItem<BlockItem> HEAD_STAND =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HEAD_STAND);
    public static final DeferredItem<BlockItem> CRANE_ANCHOR =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CRANE_ANCHOR);
    /** Marks the joints that cut a shield plug into sections the crane lifts one at a time. */
    public static final DeferredItem<Item> PLUG_SEAM_MARKER =
            ITEMS.register("plug_seam_marker", () -> new dev.bwr.mod.refuelling.PlugSeamMarkerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> STUD_TENSIONER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.STUD_TENSIONER);
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
    public static final DeferredItem<BlockItem> WATER_OUTLET_CHUTE = ITEMS.registerSimpleBlockItem(BwrBlocks.WATER_OUTLET_CHUTE);
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
    public static final DeferredItem<BlockItem> MOTOR_OPERATED_WATER_VALVE = ITEMS.registerSimpleBlockItem(BwrBlocks.MOTOR_OPERATED_WATER_VALVE);
    public static final DeferredItem<BlockItem> MAIN_OIL_TANK = ITEMS.registerSimpleBlockItem(BwrBlocks.MAIN_OIL_TANK);
    public static final DeferredItem<BlockItem> SHAFT_OIL_PUMP = ITEMS.registerSimpleBlockItem(BwrBlocks.SHAFT_OIL_PUMP);
    public static final DeferredItem<BlockItem> GENERATOR_COOLING_SKID = ITEMS.registerSimpleBlockItem(BwrBlocks.GENERATOR_COOLING_SKID);
    /** Refills a Main Oil Tank: 1,000 L of turbine oil. */
    public static final DeferredItem<Item> TURBINE_OIL_DRUM = ITEMS.registerSimpleItem("turbine_oil_drum", new Item.Properties().stacksTo(16));
    /** Recharges a Generator Cooling Skid with 5 kg of hydrogen. */
    public static final DeferredItem<Item> HYDROGEN_CYLINDER = ITEMS.registerSimpleItem("hydrogen_cylinder", new Item.Properties().stacksTo(16));
    /** The turbine hall (TURBINE-MAINTENANCE.md): blade sections and the hood are crane loads; as items they sit on stands or the hook. */
    public static final DeferredItem<dev.bwr.mod.power.TurbineLoadItem> LP_BLADE_SECTION = ITEMS.register("lp_blade_section",
            () -> new dev.bwr.mod.power.TurbineLoadItem(new Item.Properties(), dev.bwr.mod.power.TurbineLoadItem.Kind.BLADE_SECTION));
    public static final DeferredItem<dev.bwr.mod.power.TurbineLoadItem> DAMAGED_LP_BLADE_SECTION = ITEMS.register("damaged_lp_blade_section",
            () -> new dev.bwr.mod.power.TurbineLoadItem(new Item.Properties(), dev.bwr.mod.power.TurbineLoadItem.Kind.DAMAGED_BLADE_SECTION));
    public static final DeferredItem<dev.bwr.mod.power.TurbineLoadItem> LP_TURBINE_HOOD = ITEMS.register("lp_turbine_hood",
            () -> new dev.bwr.mod.power.TurbineLoadItem(new Item.Properties(), dev.bwr.mod.power.TurbineLoadItem.Kind.HOOD));
    public static final DeferredItem<BlockItem> LP_BLADE_STAND = ITEMS.registerSimpleBlockItem(BwrBlocks.LP_BLADE_STAND);
    public static final DeferredItem<BlockItem> CASING_LAYDOWN_STAND = ITEMS.registerSimpleBlockItem(BwrBlocks.CASING_LAYDOWN_STAND);
    public static final DeferredItem<BlockItem> CAVITY_DRAIN_PORT = ITEMS.registerSimpleBlockItem(BwrBlocks.CAVITY_DRAIN_PORT);
    public static final DeferredItem<BlockItem> CAVITY_FILL_PORT = ITEMS.registerSimpleBlockItem(BwrBlocks.CAVITY_FILL_PORT);
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
     * rides along in its {@code bwr:fuel_assembly} data component. Stacks to 64 for
     * bulk moving (chests, hoppers, pipes, a pocket), and only identical bundles
     * merge: vanilla merges stacks with equal components, so two bundles with
     * different exposure or discharge time stay apart — that is the entire
     * mechanic (SPEC 2.3). Every place that holds one physical assembly (a core
     * position, a rack cell, the grapple, the elevator, a machine slot) takes
     * exactly one from a stack. A stack gives off the dose and heat of all its
     * bundles ({@link dev.bwr.mod.fuel.CarriedFuel}).
     */
    public static final DeferredItem<FuelAssemblyItem> FUEL_ASSEMBLY =
            ITEMS.register("fuel_assembly",
                    () -> new FuelAssemblyItem(new Item.Properties()));

    /** A non-fuel cassette; stacks to 64 like the assemblies, and only cassettes with the same kind and progress merge. */
    public static final DeferredItem<dev.bwr.mod.fuel.SpecialtyRodItem> SPECIALTY_ROD =
            ITEMS.register("specialty_rod", () -> new dev.bwr.mod.fuel.SpecialtyRodItem(new Item.Properties()));
    public static final DeferredItem<Item> IRRADIATION_CASING = ITEMS.registerSimpleItem("irradiation_casing");
    public static final DeferredItem<Item> COBALT_CHARGE = ITEMS.registerSimpleItem("cobalt_charge");
    public static final DeferredItem<Item> LITHIUM_CHARGE = ITEMS.registerSimpleItem("lithium_charge");
    /** The sealed Co-60 a cobalt target makes: 100 g, an RTG fuel (its line is in the fuel list). Crafts 1:1 into a cobalt-60 ingot (RTG.md). */
    public static final DeferredItem<Item> COBALT_SAMPLE = ITEMS.register("cobalt_sample",
            () -> new dev.bwr.mod.isotope.IsotopeCapsuleItem(new Item.Properties()));
    public static final DeferredItem<Item> TRITIUM_SAMPLE = ITEMS.register("tritium_sample", () -> new dev.bwr.mod.fuel.TritiumSampleItem(new Item.Properties()));
    public static final DeferredItem<Item> DOPED_SILICON = ITEMS.registerSimpleItem("doped_silicon");
    /**
     * What the fuel fabricator's item route returns from the uranium that did not become enriched product
     * (the tails of the mass balance in {@code EnrichmentMassBalance}). It is a plain ingot, deliberately not
     * tagged as a uranium ingot, so the fabricator will not take it back as feed.
     */
    public static final DeferredItem<Item> DEPLETED_URANIUM = ITEMS.registerSimpleItem("depleted_uranium");
    /**
     * The mod's own uranium: raw ore from the ore blocks and veins, smelted to an ingot. Both carry the convention tags
     * the fuel fabricator's item route reads ({@code c:raw_materials/uranium}, {@code c:ingots/uranium}), so the route
     * works with no other mod; another mod's uranium counts exactly the same.
     */
    public static final DeferredItem<Item> RAW_URANIUM = ITEMS.registerSimpleItem("raw_uranium");
    public static final DeferredItem<Item> URANIUM_INGOT = ITEMS.registerSimpleItem("uranium_ingot");
    public static final DeferredItem<BlockItem> URANIUM_ORE = ITEMS.registerSimpleBlockItem(BwrBlocks.URANIUM_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_URANIUM_ORE = ITEMS.registerSimpleBlockItem(BwrBlocks.DEEPSLATE_URANIUM_ORE);
    public static final DeferredItem<BlockItem> RAW_URANIUM_BLOCK = ITEMS.registerSimpleBlockItem(BwrBlocks.RAW_URANIUM_BLOCK);
    public static final DeferredItem<BlockItem> URANIUM_BLOCK = ITEMS.registerSimpleBlockItem(BwrBlocks.URANIUM_BLOCK);
    /**
     * Plutonium out of a reprocessing plant, in ninths of an ingot (a nugget), which is the unit the plant's
     * fractional accounting and the fabricator's MOX route both count in. Tagged {@code c:nuggets/plutonium} and
     * {@code c:ingots/plutonium}; nine nuggets and an ingot are crafted into each other.
     */
    /** The mod's own thorium (THORIUM.md): raw monazite concentrate from the ore, an ingot (smelted), a nugget (a ninth). The tags the fabricator reads are on them. */
    public static final DeferredItem<Item> RAW_THORIUM = ITEMS.registerSimpleItem("raw_thorium");
    public static final DeferredItem<Item> THORIUM_INGOT = ITEMS.registerSimpleItem("thorium_ingot");
    public static final DeferredItem<Item> THORIUM_NUGGET = ITEMS.registerSimpleItem("thorium_nugget");
    /** What monazite carries besides thorium (cerium, lanthanum, neodymium phosphate): a byproduct of mining thorium ore and monazite sand. Makes a flint and steel. */
    public static final DeferredItem<Item> RARE_EARTH_CONCENTRATE = ITEMS.registerSimpleItem("rare_earth_concentrate");
    /**
     * Lead, the end of both decay chains: a byproduct of mining thorium ore. Tagged {@code c:nuggets/lead} and {@code c:ingots/lead}, so any mod
     * whose recipes use those tags (Mekanism, Immersive Engineering) takes it, with or without that mod installed. This mod has no use for it.
     */
    public static final DeferredItem<Item> LEAD_NUGGET = ITEMS.registerSimpleItem("lead_nugget");
    public static final DeferredItem<Item> LEAD_INGOT = ITEMS.registerSimpleItem("lead_ingot");
    public static final DeferredItem<BlockItem> LEAD_BLOCK = ITEMS.registerSimpleBlockItem(BwrBlocks.LEAD_BLOCK);
    /** Radium: a rare byproduct of mining thorium ore, clearly radioactive (a gamma source when carried, see RadiumItem). */
    public static final DeferredItem<Item> RADIUM = ITEMS.register("radium", () -> new dev.bwr.mod.radiation.RadiumItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<BlockItem> THORIUM_ORE = ITEMS.registerSimpleBlockItem(BwrBlocks.THORIUM_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_THORIUM_ORE = ITEMS.registerSimpleBlockItem(BwrBlocks.DEEPSLATE_THORIUM_ORE);
    public static final DeferredItem<BlockItem> MONAZITE_SAND = ITEMS.registerSimpleBlockItem(BwrBlocks.MONAZITE_SAND);
    public static final DeferredItem<BlockItem> RAW_THORIUM_BLOCK = ITEMS.registerSimpleBlockItem(BwrBlocks.RAW_THORIUM_BLOCK);
    public static final DeferredItem<BlockItem> THORIUM_BLOCK = ITEMS.registerSimpleBlockItem(BwrBlocks.THORIUM_BLOCK);
    // --- Reactor-bred isotopes (BREEDING-TARGETS.md): what a finished irradiation target yields. Their power, half-life and dose are the RTG fuel list's. ---
    /** Bismuth, a byproduct of mining uranium ore (the Erzgebirge Bi-Co-Ni-Ag-U veins): 5 kg of bismuth, a ninth of an ingot-unit. */
    public static final DeferredItem<Item> BISMUTH_NUGGET = ITEMS.registerSimpleItem("bismuth_nugget");
    public static final DeferredItem<Item> POLONIUM_210_INGOT = ITEMS.register("polonium_210_ingot", () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> PLUTONIUM_238_INGOT = ITEMS.register("plutonium_238_ingot", () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> ACTINIUM_227_INGOT = ITEMS.register("actinium_227_ingot", () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> THULIUM_170_INGOT = ITEMS.register("thulium_170_ingot", () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    /** Fissile fuel bred from thorium, 480 g: the driver of a thorium assembly in place of enriched uranium (a heat source it is not). */
    public static final DeferredItem<Item> URANIUM_233_INGOT = ITEMS.registerSimpleItem("uranium_233_ingot");
    /** What a depleted uranium target makes at low yield, 0.2 g: the target of the plutonium-238 route. */
    public static final DeferredItem<Item> NEPTUNIUM_237_INGOT = ITEMS.registerSimpleItem("neptunium_237_ingot");
    public static final DeferredItem<BlockItem> POLONIUM_210_BLOCK = ITEMS.register("polonium_210_block", () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.POLONIUM_210_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> PLUTONIUM_238_BLOCK = ITEMS.register("plutonium_238_block", () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.PLUTONIUM_238_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> ACTINIUM_227_BLOCK = ITEMS.register("actinium_227_block", () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.ACTINIUM_227_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> THULIUM_170_BLOCK = ITEMS.register("thulium_170_block", () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.THULIUM_170_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<Item> PLUTONIUM_NUGGET = ITEMS.registerSimpleItem("plutonium_nugget");
    public static final DeferredItem<Item> PLUTONIUM_INGOT = ITEMS.registerSimpleItem("plutonium_ingot");
    /**
     * The fission products of a spent assembly, in ninths of an ingot of heavy metal that was fissioned. Nothing
     * uses it and there is no recipe for it: it is what burning fuel leaves, kept as an item so the plant has
     * somewhere to put it instead of deleting it.
     */
    public static final DeferredItem<Item> FISSION_PRODUCT_WASTE = ITEMS.registerSimpleItem("fission_product_waste");
    /**
     * The isotope separator's capsules, 250 g of the isotope each, sealed (see RTG.md). Their power, half-life and
     * dose are the RTG fuel list's ({@code data/bwr/data_maps/item/rtg_fuel.json}), not fields of the item.
     */
    public static final DeferredItem<Item> STRONTIUM_90_CAPSULE = ITEMS.register("strontium_90_capsule",
            () -> new dev.bwr.mod.isotope.IsotopeCapsuleItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> CAESIUM_137_CAPSULE = ITEMS.register("caesium_137_capsule",
            () -> new dev.bwr.mod.isotope.IsotopeCapsuleItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> AMERICIUM_241_CAPSULE = ITEMS.register("americium_241_capsule",
            () -> new dev.bwr.mod.isotope.IsotopeCapsuleItem(new Item.Properties().stacksTo(16)));
    /** What the isotope separator leaves of waste: the same mass without its strontium, caesium and americium. It is not separated again. */
    public static final DeferredItem<Item> STRIPPED_WASTE = ITEMS.registerSimpleItem("stripped_waste");

    // --- RTG isotope ingots and storage blocks (RTG.md, "The ingot and block pattern") ---
    // <isotope>_ingot is the form the RTGs take; <isotope>_block is nine of them. Their numbers are the RTG fuel list's.
    public static final DeferredItem<Item> STRONTIUM_90_INGOT = ITEMS.register("strontium_90_ingot",
            () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> CAESIUM_137_INGOT = ITEMS.register("caesium_137_ingot",
            () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> AMERICIUM_241_INGOT = ITEMS.register("americium_241_ingot",
            () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<Item> COBALT_60_INGOT = ITEMS.register("cobalt_60_ingot",
            () -> new dev.bwr.mod.isotope.BredIsotopeItem(new Item.Properties()));
    public static final DeferredItem<BlockItem> STRONTIUM_90_BLOCK = ITEMS.register("strontium_90_block",
            () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.STRONTIUM_90_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> CAESIUM_137_BLOCK = ITEMS.register("caesium_137_block",
            () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.CAESIUM_137_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> AMERICIUM_241_BLOCK = ITEMS.register("americium_241_block",
            () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.AMERICIUM_241_BLOCK.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> COBALT_60_BLOCK = ITEMS.register("cobalt_60_block",
            () -> new dev.bwr.mod.isotope.BredIsotopeBlockItem(BwrBlocks.COBALT_60_BLOCK.get(), new Item.Properties()));

    // --- Control-room furniture (ROOM-FURNITURE.md) ---
    public static final DeferredItem<BlockItem> RELAY_CABINET =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RELAY_CABINET);
    public static final DeferredItem<BlockItem> RELAY_CABINET_PLAIN =
            ITEMS.registerSimpleBlockItem(BwrBlocks.RELAY_CABINET_PLAIN);
    public static final DeferredItem<BlockItem> OPERATOR_DESK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.OPERATOR_DESK);
    public static final DeferredItem<BlockItem> OPERATOR_DESK_ROUND =
            ITEMS.registerSimpleBlockItem(BwrBlocks.OPERATOR_DESK_ROUND);
    public static final DeferredItem<BlockItem> OPERATOR_DESK_CORNER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.OPERATOR_DESK_CORNER);
    public static final DeferredItem<BlockItem> SUPERVISOR_DESK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.SUPERVISOR_DESK);
    public static final DeferredItem<BlockItem> MIMIC_BAND_CREAM =
            ITEMS.registerSimpleBlockItem(BwrBlocks.MIMIC_BAND_CREAM);
    public static final DeferredItem<BlockItem> MIMIC_BAND_BLACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.MIMIC_BAND_BLACK);
    public static final DeferredItem<BlockItem> WALL_DISPLAY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.WALL_DISPLAY);
    public static final DeferredItem<BlockItem> WALL_DISPLAY_WIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.WALL_DISPLAY_WIDE);
    public static final DeferredItem<BlockItem> CONTROL_STAND =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_STAND);
    // Industrial lamps (LIGHTING.md).
    public static final DeferredItem<BlockItem> LPS_HIGH_BAY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.LPS_HIGH_BAY);
    public static final DeferredItem<BlockItem> LPS_WALL_PACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.LPS_WALL_PACK);
    public static final DeferredItem<BlockItem> HPS_HIGH_BAY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPS_HIGH_BAY);
    public static final DeferredItem<BlockItem> HPS_WALL_PACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.HPS_WALL_PACK);
    public static final DeferredItem<BlockItem> MERCURY_HIGH_BAY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.MERCURY_HIGH_BAY);
    public static final DeferredItem<BlockItem> MERCURY_WALL_PACK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.MERCURY_WALL_PACK);

    // Control panel trim (the blocks of BwrBlocks, listed after the component items).
    public static final DeferredItem<BlockItem> CONTROL_PANEL_END_CAP_FLAT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_END_CAP_FLAT);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_END_CAP_BENCH =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_END_CAP_BENCH);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_END_CAP_CONSOLE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_END_CAP_CONSOLE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_END_CAP_ROUND =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_END_CAP_ROUND);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_HEADER_STRIP =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_HEADER_STRIP);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_HEADER_CANOPY =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_HEADER_CANOPY);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_PLINTH_KICK =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_PLINTH_KICK);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_PLINTH_APRON =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_PLINTH_APRON);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_PLINTH_BASE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_PLINTH_BASE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_VERTICAL_INSIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_INSIDE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_VERTICAL_CHAMFER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_CHAMFER);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_BENCH_INSIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_BENCH_INSIDE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_BENCH_OUTSIDE =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_BENCH_OUTSIDE);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_CORNER_BENCH_CHAMFER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_CORNER_BENCH_CHAMFER);
    public static final DeferredItem<BlockItem> CONTROL_PANEL_HEADER_VENT =
            ITEMS.registerSimpleBlockItem(BwrBlocks.CONTROL_PANEL_HEADER_VENT);
    /** The board blueprint: one item for every premade board (data/<ns>/panel_board), the board a data component of the stack. */
    public static final DeferredItem<dev.bwr.mod.panel.board.BoardBlueprintItem> BOARD_BLUEPRINT =
            ITEMS.register("board_blueprint", () -> new dev.bwr.mod.panel.board.BoardBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FUEL_TAB = TABS.register("fuel", () ->
            CreativeModeTab.builder().title(Component.translatable("itemGroup.bwr.fuel"))
                    .icon(() -> FUEL_ASSEMBLY.get().getDefaultInstance()).displayItems((parameters, output) -> {
                        for (var type : FuelTypes.catalogue()) output.accept(FuelAssemblyItem.stackOf(FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(type)));
                        for (var kind : dev.bwr.core.fuel.CoreInsert.Kind.values()) output.accept(dev.bwr.mod.fuel.SpecialtyRodItem.stack(new dev.bwr.mod.fuel.CoreInsertData(kind, 0)));
                        for (var item : java.util.List.<net.neoforged.neoforge.registries.DeferredItem<? extends Item>>of(URANIUM_ORE, DEEPSLATE_URANIUM_ORE, RAW_URANIUM, RAW_URANIUM_BLOCK, URANIUM_INGOT, URANIUM_BLOCK, THORIUM_ORE, DEEPSLATE_THORIUM_ORE, MONAZITE_SAND, RAW_THORIUM, RAW_THORIUM_BLOCK, THORIUM_INGOT, THORIUM_NUGGET, THORIUM_BLOCK, RARE_EARTH_CONCENTRATE, LEAD_NUGGET, LEAD_INGOT, LEAD_BLOCK, RADIUM, IRRADIATION_CASING, COBALT_CHARGE, LITHIUM_CHARGE, TRITIUM_SAMPLE, DOPED_SILICON, DEPLETED_URANIUM, PLUTONIUM_NUGGET, PLUTONIUM_INGOT, FISSION_PRODUCT_WASTE, STRIPPED_WASTE, STRONTIUM_90_INGOT, STRONTIUM_90_BLOCK, CAESIUM_137_INGOT, CAESIUM_137_BLOCK, AMERICIUM_241_INGOT, AMERICIUM_241_BLOCK, COBALT_60_INGOT, COBALT_60_BLOCK, BISMUTH_NUGGET, POLONIUM_210_INGOT, POLONIUM_210_BLOCK, PLUTONIUM_238_INGOT, PLUTONIUM_238_BLOCK, ACTINIUM_227_INGOT, ACTINIUM_227_BLOCK, THULIUM_170_INGOT, THULIUM_170_BLOCK, URANIUM_233_INGOT, NEPTUNIUM_237_INGOT)) output.accept(item.get());
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
                        // SAFETY_RELIEF_VALVE (the old cube) stays registered for saves but is no longer offered.
                        output.accept(MSIV.get());
                        output.accept(STEAM_STOP_VALVE.get());
                        output.accept(TURBINE_CONTROL_VALVE.get());
                        output.accept(BYPASS_STEAM_VALVE.get());
                        output.accept(MOTOR_OPERATED_WATER_VALVE.get());
                        output.accept(CAVITY_DRAIN_PORT.get());
                        output.accept(CAVITY_FILL_PORT.get());
                        output.accept(MAIN_OIL_TANK.get());
                        output.accept(SHAFT_OIL_PUMP.get());
                        output.accept(GENERATOR_COOLING_SKID.get());
                        output.accept(TURBINE_OIL_DRUM.get());
                        output.accept(HYDROGEN_CYLINDER.get());
                        output.accept(LP_BLADE_SECTION.get());
                        output.accept(DAMAGED_LP_BLADE_SECTION.get());
                        output.accept(LP_TURBINE_HOOD.get());
                        output.accept(LP_BLADE_STAND.get());
                        output.accept(CASING_LAYDOWN_STAND.get());
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
                        output.accept(RPV_BOTTOM_DRAIN_PORT.get());
                        output.accept(RWCU_PUMP.get());
                        output.accept(RWCU_REGENERATIVE_HEAT_EXCHANGER.get());
                        output.accept(RWCU_NONREGENERATIVE_HEAT_EXCHANGER.get());
                        output.accept(RWCU_FILTER_DEMINERALIZER.get());
                        output.accept(DEMINERALIZER_RESIN.get());
                        output.accept(SPENT_RESIN.get());
                        output.accept(RPV_STEAM_OUTLET.get());
                        output.accept(TURBINE_STEAM_OUTLET.get());
                        output.accept(FUEL_FABRICATOR.get());
                        output.accept(REPROCESSING_PLANT.get());
                        output.accept(ISOTOPE_SEPARATOR.get());
                        output.accept(RTG.get());
                        output.accept(LARGE_RTG.get());
                        output.accept(SHIELD_PLUG_CONCRETE.get());
                        output.accept(HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get());
                        output.accept(ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get());
                        output.accept(SPENT_FUEL_POOL_WALL.get());
                        output.accept(SPENT_FUEL_POOL_CONTROLLER.get());
                        output.accept(SPENT_FUEL_RACK.get());
                        output.accept(NEW_FUEL_ELEVATOR.get());
                        output.accept(SPENT_FUEL_POOL_MAKEUP_PORT.get());
                        output.accept(SPENT_FUEL_POOL_COOLING_IN.get());
                        output.accept(SPENT_FUEL_POOL_COOLING_OUT.get());
                        output.accept(REFUELLING_RAIL.get());
                        output.accept(TRANSFER_CANAL.get());
                        output.accept(CANAL_GATE.get());
                        output.accept(CANAL_GATE_PLUG.get());
                        output.accept(GATE_LAYDOWN_STAND.get());
                        output.accept(REFUELLING_BRIDGE_CONSOLE.get());
                        output.accept(EQUIPMENT_PAD.get());
                        output.accept(CRANE_RUNWAY_RAIL.get());
                        output.accept(BUILDING_CRANE_CONSOLE.get());
                        output.accept(HEAD_STAND.get());
                        output.accept(CRANE_ANCHOR.get());
                        output.accept(PLUG_SEAM_MARKER.get());
                        output.accept(STUD_TENSIONER.get());
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
                        output.accept(WATER_OUTLET_CHUTE.get());
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
                        output.accept(SURVEY_METER.get());
                        output.accept(GUIDE_BOOK.get());
                        output.accept(AREA_RADIATION_MONITOR.get());
                        output.accept(XENON_FLOODLIGHT.get());
                        output.accept(XENON_HIGH_BAY.get());
                        output.accept(HPS_FLAT_PANEL.get());
                        output.accept(TURBINE_FEED_PUMP.get());
                        output.accept(CORIUM.get());
                        output.accept(CONTROL_PANEL_VERTICAL.get());
                        output.accept(CONTROL_PANEL_BENCHBOARD.get());
                        output.accept(CONTROL_PANEL_CONSOLE.get());
                        output.accept(CONTROL_PANEL_BACK.get());
                        output.accept(CONTROL_PANEL_DUPLEX.get());
                        output.accept(CONTROL_PANEL_DESK.get());
                        output.accept(CONTROL_PANEL_DESK_WIDE.get());
                        output.accept(PANEL_WRENCH.get());
                        output.accept(PANEL_LINKER.get());
                        output.accept(NETWORKING_WRENCH.get());
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
                        output.accept(CONTROLLER_STATION.get());
                        output.accept(SYNCHROSCOPE.get());
                        output.accept(TWO_PEN_RECORDER.get());
                        output.accept(JOYSTICK_SWITCH.get());
                        output.accept(LED_ROD_DISPLAY.get());
                        output.accept(PLANT_COMPUTER.get());
                        output.accept(ANNUNCIATOR_CONTROL.get());
                        output.accept(RELAY_CABINET.get());
                        output.accept(RELAY_CABINET_PLAIN.get());
                        output.accept(OPERATOR_DESK.get());
                        output.accept(OPERATOR_DESK_ROUND.get());
                        output.accept(OPERATOR_DESK_CORNER.get());
                        output.accept(SUPERVISOR_DESK.get());
                        output.accept(MIMIC_BAND_CREAM.get());
                        output.accept(MIMIC_BAND_BLACK.get());
                        output.accept(WALL_DISPLAY.get());
                        output.accept(WALL_DISPLAY_WIDE.get());
                        output.accept(CONTROL_STAND.get());
                        output.accept(LPS_HIGH_BAY.get());
                        output.accept(LPS_WALL_PACK.get());
                        output.accept(HPS_HIGH_BAY.get());
                        output.accept(HPS_WALL_PACK.get());
                        output.accept(MERCURY_HIGH_BAY.get());
                        output.accept(MERCURY_WALL_PACK.get());

                        output.accept(CONTROL_PANEL_END_CAP_FLAT.get());
                        output.accept(CONTROL_PANEL_END_CAP_BENCH.get());
                        output.accept(CONTROL_PANEL_END_CAP_CONSOLE.get());
                        output.accept(CONTROL_PANEL_END_CAP_ROUND.get());
                        output.accept(CONTROL_PANEL_HEADER_STRIP.get());
                        output.accept(CONTROL_PANEL_HEADER_CANOPY.get());
                        output.accept(CONTROL_PANEL_PLINTH_KICK.get());
                        output.accept(CONTROL_PANEL_PLINTH_APRON.get());
                        output.accept(CONTROL_PANEL_PLINTH_BASE.get());
                        output.accept(CONTROL_PANEL_CORNER_VERTICAL_INSIDE.get());
                        output.accept(CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE.get());
                        output.accept(CONTROL_PANEL_CORNER_VERTICAL_CHAMFER.get());
                        output.accept(CONTROL_PANEL_CORNER_BENCH_INSIDE.get());
                        output.accept(CONTROL_PANEL_CORNER_BENCH_OUTSIDE.get());
                        output.accept(CONTROL_PANEL_CORNER_BENCH_CHAMFER.get());
                        output.accept(CONTROL_PANEL_HEADER_VENT.get());
                        for (var board : dev.bwr.mod.panel.board.BoardTemplates.ordered()) output.accept(dev.bwr.mod.panel.board.BoardBlueprintItem.stackFor(board.id()));
                        if (dev.bwr.mod.panel.board.BoardTemplates.all().isEmpty()) output.accept(BOARD_BLUEPRINT.get());
                    })
                    .build());
}
