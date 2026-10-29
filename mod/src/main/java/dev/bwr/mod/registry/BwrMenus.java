package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.FuelFabricatorMenu;
import dev.bwr.mod.gui.ReprocessingPlantMenu;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.gui.RecirculationPumpMenu;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.SuppressionPoolMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Menu types for every screen in the mod.
 *
 * <p>All of them are created through {@link IMenuTypeExtension#create}, the
 * network-aware factory, because every one of these screens is opened for a
 * particular block and the block's position is written into the open packet.
 * A menu that had to look up its own block entity by guessing would break the
 * moment two reactors existed.
 */
public final class BwrMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, BwrMod.MOD_ID);

    /** The control room panel: core map, plant readouts, scram, rods, head. */
    public static final DeferredHolder<MenuType<?>, MenuType<ReactorPanelMenu>> REACTOR_PANEL =
            MENUS.register("reactor_panel",
                    () -> IMenuTypeExtension.create(ReactorPanelMenu::new));

    /** The refuelling floor: the same lattice, showing identity and burnup. */
    public static final DeferredHolder<MenuType<?>, MenuType<RefuellingMenu>> REFUELLING =
            MENUS.register("refuelling",
                    () -> IMenuTypeExtension.create(RefuellingMenu::new));

    /** One recirculation pump: slider, CC toggle, power limit. */
    public static final DeferredHolder<MenuType<?>, MenuType<RecirculationPumpMenu>> RECIRCULATION_PUMP =
            MENUS.register("recirculation_pump",
                    () -> IMenuTypeExtension.create(RecirculationPumpMenu::new));

    /** Pool temperature, subcooling, condensation, remaining capacity, RHR duty. */
    public static final DeferredHolder<MenuType<?>, MenuType<SuppressionPoolMenu>> SUPPRESSION_POOL =
            MENUS.register("suppression_pool",
                    () -> IMenuTypeExtension.create(SuppressionPoolMenu::new));

    /** Fuel fabricator: feed status, batch progress, output slot. */
    public static final DeferredHolder<MenuType<?>, MenuType<FuelFabricatorMenu>> FUEL_FABRICATOR =
            MENUS.register("fuel_fabricator",
                    () -> IMenuTypeExtension.create(FuelFabricatorMenu::new));

    /** Reprocessing plant: the spent assembly, what it will yield, what the plant holds, progress. */
    public static final DeferredHolder<MenuType<?>, MenuType<ReprocessingPlantMenu>> REPROCESSING_PLANT =
            MENUS.register("reprocessing_plant",
                    () -> IMenuTypeExtension.create(ReprocessingPlantMenu::new));

    /** Isotope separator: the waste slot, four product slots, what one waste item yields, progress and energy. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.IsotopeSeparatorMenu>> ISOTOPE_SEPARATOR =
            MENUS.register("isotope_separator",
                    () -> IMenuTypeExtension.create(dev.bwr.mod.gui.IsotopeSeparatorMenu::new));

    /** RTG: six capsule wells and what they make. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.RtgMenu>> RTG =
            MENUS.register("rtg",
                    () -> IMenuTypeExtension.create(dev.bwr.mod.gui.RtgMenu::new));
    /** Large RTG: stored mass by isotope, power, heat-only switch, dose. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.LargeRtgMenu>> LARGE_RTG =
            MENUS.register("large_rtg",
                    () -> IMenuTypeExtension.create(dev.bwr.mod.gui.LargeRtgMenu::new));

    /** Spent fuel pool: per-rack heat, water level and temperature, time to boil and to uncover, the cooling pump. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.SpentFuelPoolMenu>> SPENT_FUEL_POOL =
            MENUS.register("spent_fuel_pool", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.SpentFuelPoolMenu::new));

    /** The new fuel elevator: its pool, the carriage, the waiting assemblies, the water it needs, and raise, try again and take. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.NewFuelElevatorMenu>> NEW_FUEL_ELEVATOR =
            MENUS.register("new_fuel_elevator", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.NewFuelElevatorMenu::new));

    /** The reactor building crane's pendant: the crane's state, the head, the dryer and the separators, and the lifts. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CraneMenu>> BUILDING_CRANE =
            MENUS.register("building_crane", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CraneMenu::new));
    /** The stud tensioner: the closure studs at load, the tool, the pack's power, and detension, retension and stop. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.StudTensionerMenu>> STUD_TENSIONER =
            MENUS.register("stud_tensioner", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.StudTensionerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.PumpControlMenu>> PUMP_CONTROL =
            MENUS.register("pump_control", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.PumpControlMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.TerryTurbineMenu>> TERRY_TURBINE =
            MENUS.register("terry_turbine", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.TerryTurbineMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CondensateTankMenu>> CONDENSATE_TANK =
            MENUS.register("condensate_tank", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CondensateTankMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.PowerModuleMenu>> POWER_MODULE =
            MENUS.register("power_module", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.PowerModuleMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.TurbineAuxMenu>> TURBINE_AUX =
            MENUS.register("turbine_aux", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.TurbineAuxMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.OilFilterMenu>> OIL_FILTER =
            MENUS.register("oil_filter", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.OilFilterMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.TurbineValveMenu>> TURBINE_VALVE =
            MENUS.register("turbine_valve", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.TurbineValveMenu::new));

    public static final DeferredHolder<MenuType<?>,MenuType<dev.bwr.mod.gui.ServiceMenu>> SERVICE =
            MENUS.register("service",()->IMenuTypeExtension.create(dev.bwr.mod.gui.ServiceMenu::new));
    private BwrMenus() {
    }
    public static final DeferredHolder<MenuType<?>,MenuType<dev.bwr.mod.gui.RhrHeatExchangerMenu>> RHR_HEAT_EXCHANGER =
            MENUS.register("rhr_heat_exchanger",()->IMenuTypeExtension.create(dev.bwr.mod.gui.RhrHeatExchangerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.RwcuUnitMenu>> RWCU_UNIT =
            MENUS.register("rwcu_unit", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.RwcuUnitMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CondenserMenu>> CONDENSER =
            MENUS.register("condenser", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CondenserMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CoolingMenu>> COOLING =
            MENUS.register("cooling", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CoolingMenu::new));
    /** The fabrication desk: pick a control-room part, see its materials with the player's counts, make it. */
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.FabricationDeskMenu>> FABRICATION_DESK =
            MENUS.register("fabrication_desk", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.FabricationDeskMenu::new));
}
