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

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BwrMod.MOD_ID);

    public static final DeferredItem<BlockItem> REACTOR_CONTROLLER =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REACTOR_CONTROLLER);
    public static final DeferredItem<BlockItem> REACTOR_VESSEL =
            ITEMS.registerSimpleBlockItem(BwrBlocks.REACTOR_VESSEL);
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

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bwr"))
                    .icon(() -> REACTOR_CONTROLLER.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(REACTOR_CONTROLLER.get());
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
                        output.accept(SUPPRESSION_POOL_QUENCHER.get());
                        output.accept(HPCS_PUMP.get());
                        output.accept(LPCS_PUMP.get());
                        output.accept(RHR_PUMP.get());
                        output.accept(SLC_PUMP.get());
                        output.accept(RCIC_TWL.get());
                        output.accept(HPCI_TURBINE.get());
                        output.accept(ADS_CONTROLLER.get());
                        output.accept(CONDENSATE_STORAGE_TANK.get());
                        output.accept(MOTOR_FEED_PUMP.get());
                        output.accept(HP_TURBINE.get());
                        output.accept(LP_TURBINE.get());
                        output.accept(NUCLEAR_GENERATOR.get());
                        output.accept(TURBINE_FEED_PUMP.get());
                        // A fresh bundle of every fuel the install knows, each
                        // at its own nominal enrichment. Creative-mode players
                        // get the whole fuel table without having to build the
                        // enrichment chain first; survival players get these
                        // out of the fabricator at whatever enrichment they
                        // actually managed to feed it.
                        for (dev.bwr.core.fuel.FuelType type : FuelTypes.all()) {
                            output.accept(FuelAssemblyItem.stackOf(
                                    FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(type)));
                        }
                    })
                    .build());
}
