package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.FuelFabricatorMenu;
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

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.PumpControlMenu>> PUMP_CONTROL =
            MENUS.register("pump_control", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.PumpControlMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CondensateTankMenu>> CONDENSATE_TANK =
            MENUS.register("condensate_tank", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CondensateTankMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.PowerModuleMenu>> POWER_MODULE =
            MENUS.register("power_module", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.PowerModuleMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.TurbineValveMenu>> TURBINE_VALVE =
            MENUS.register("turbine_valve", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.TurbineValveMenu::new));

    public static final DeferredHolder<MenuType<?>,MenuType<dev.bwr.mod.gui.ServiceMenu>> SERVICE =
            MENUS.register("service",()->IMenuTypeExtension.create(dev.bwr.mod.gui.ServiceMenu::new));
    private BwrMenus() {
    }
    public static final DeferredHolder<MenuType<?>,MenuType<dev.bwr.mod.gui.RhrHeatExchangerMenu>> RHR_HEAT_EXCHANGER =
            MENUS.register("rhr_heat_exchanger",()->IMenuTypeExtension.create(dev.bwr.mod.gui.RhrHeatExchangerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CondenserMenu>> CONDENSER =
            MENUS.register("condenser", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CondenserMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.bwr.mod.gui.CoolingMenu>> COOLING =
            MENUS.register("cooling", () -> IMenuTypeExtension.create(dev.bwr.mod.gui.CoolingMenu::new));
}
