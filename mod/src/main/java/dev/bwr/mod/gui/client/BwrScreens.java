package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Binds each menu type to its screen.
 *
 * <p>Client only, and the {@link Dist#CLIENT} on the subscriber is what keeps
 * it that way: this is the only class in the mod that names a
 * {@code net.minecraft.client} type from outside the {@code gui.client}
 * package, and a dedicated server never loads it, so it never loads the screens
 * either. The menus themselves are common code and run on both sides.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BwrScreens {

    private BwrScreens() {
    }

    // No bus() on the annotation: RegisterMenuScreensEvent is an IModBusEvent
    // and NeoForge routes it by type. Naming a bus is deprecated for removal.
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(BwrMenus.REACTOR_PANEL.get(), ReactorPanelScreen::new);
        event.register(BwrMenus.REFUELLING.get(), RefuellingScreen::new);
        event.register(BwrMenus.RECIRCULATION_PUMP.get(), RecirculationPumpScreen::new);
        event.register(BwrMenus.SUPPRESSION_POOL.get(), SuppressionPoolScreen::new);
        event.register(BwrMenus.FUEL_FABRICATOR.get(), FuelFabricatorScreen::new);
        event.register(BwrMenus.POWER_MODULE.get(), PowerModuleScreen::new);
        event.register(BwrMenus.TURBINE_VALVE.get(), TurbineValveScreen::new);
        event.register(BwrMenus.PUMP_CONTROL.get(), PumpControlScreen::new);
        event.register(BwrMenus.CONDENSATE_TANK.get(), CondensateTankScreen::new);
        event.register(BwrMenus.CONDENSER.get(), CondenserScreen::new);
        event.register(BwrMenus.COOLING.get(), CoolingScreen::new);
        event.register(BwrMenus.RHR_HEAT_EXCHANGER.get(), RhrHeatExchangerScreen::new);
    }
}
