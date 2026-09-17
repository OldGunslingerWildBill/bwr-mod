package dev.bwr.mod.peripheral;

import com.mojang.logging.LogUtils;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

/**
 * Attaches the peripherals to their block entities.
 *
 * <h2>Soft dependency, deliberately</h2>
 * This class references CC:Tweaked types directly, so it must never be loaded
 * unless CC:Tweaked is installed. {@code BwrClientHooks} checks
 * {@code BwrMod.isComputerCraftPresent()} before touching it, and because the
 * check happens in a different class the JVM never has to resolve these types
 * on a CC-less install.
 *
 * <p>CC:Tweaked is a <b>compileOnly</b> dependency and is never bundled. Parts
 * of its API — {@code IPeripheral} among them — remain under LicenseRef-CCPL,
 * which permits redistribution only "unmodified and in full". Players install
 * CC:Tweaked themselves.
 */
public final class BwrPeripheralSupport {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BwrPeripheralSupport() {
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(PeripheralCapability.get(), (level,pos,state,unused,side) -> {
            var be=dev.bwr.mod.eccs.PumpAssemblyCapabilities.controller(level,pos,state);
            if(be instanceof dev.bwr.mod.eccs.EccsPumpBlockEntity p) return new EccsPumpPeripheral(p);
            if(be instanceof dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity p) return new FeedwaterPumpPeripheral(p);
            if(be instanceof dev.bwr.mod.flow.RecirculationPumpBlockEntity p) return new RecirculationPumpPeripheral(p);
            return null;
        }, dev.bwr.mod.eccs.PumpAssemblyCapabilities.blocks());
        // Deliberately at INFO. This branch was dead for the whole life of the
        // project — CC:Tweaked was compileOnly and on no run configuration, so
        // nothing here had ever executed. The one line is how anyone confirms,
        // from a log alone, that the peripheral surface is actually attached.
        LOGGER.info("BwrPeripheralSupport: CC:Tweaked detected, attaching peripherals to "
                + "reactor_controller, suppression_pool, turbine_steam_outlet, "
                + "eccs_pump, ads_controller, condensate_storage_tank, "
                + "safety_relief_valve, msiv, recirculation_pump, rpv_steam_outlet, "
                + "feedwater_pump");

        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.REACTOR_CONTROLLER.get(),
                (be, side) -> new ReactorPeripheral(be));

        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.SUPPRESSION_POOL.get(),
                (be, side) -> new SuppressionPoolPeripheral(be));

        // The steam boundary. Registered whether or not Mekanism is installed:
        // the peripheral names no Mekanism type, and reporting that Mekanism is
        // absent is more useful than the peripheral itself being absent.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.TURBINE_STEAM_OUTLET.get(),
                (be, side) -> new TurbineSteamOutletPeripheral(be));

        // Emergency core cooling (SPEC section 9). One block entity type covers
        // all six injection machines; the peripheral type string carries the
        // system name, so peripheral.find("bwr_rcic") finds RCIC and nothing
        // else. This is the surface the entire emergency response of the plant
        // is written against, because the mod ships none of it.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.ECCS_PUMP.get(),
                (be, side) -> new EccsPumpPeripheral(be));

        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.ADS_CONTROLLER.get(),
                (be, side) -> new AdsPeripheral(be));

        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(),
                (be, side) -> new CondensateStorageTankPeripheral(be));

        // The two player-actuated valves. Both block entities have documented
        // "called from redstone or from Lua" since they were written, but
        // neither was registered here, so setComputerControlled had zero callers
        // anywhere in the mod, the redstone-override guards in both blocks were
        // conditions that could never be false, and peripheral.find("bwr_msiv")
        // returned nothing. Redstone worked and Lua did not. SPEC section 6.3's
        // headline transient — MSIV closure into pressurisation, void collapse
        // and a power surge — was reachable only by hand-built redstone.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.SAFETY_RELIEF_VALVE.get(),
                (be, side) -> new SafetyReliefValvePeripheral(be));

        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.MSIV.get(),
                (be, side) -> new MainSteamIsolationValvePeripheral(be));

        // Core flow. The reactor controller sums its satellite recirculation
        // pumps into ReactorCore.setRecirculationFlowFraction on every tick
        // before the physics runs, so the pumps — not the reactor peripheral —
        // are the authority on forced flow, and with no peripheral here Lua had
        // no working handle on core flow at all.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.RECIRCULATION_PUMP.get(),
                (be, side) -> new RecirculationPumpPeripheral(be));

        // The main steam path. The RPV steam nozzles are what the reactor
        // controller sums into the one steam-discharge scalar it owns, so they
        // are the principal way steam leaves a vessel — and with no peripheral
        // here they were the only actuator on the plant a Lua program could not
        // reach at all. The block entity was built for this: it has carried
        // setPosition, setComputerControlled and a comment saying the stop valve
        // is moved "by the player's hand, by an analogue redstone signal, or by
        // Lua" since the day it was written, and the Lua half of that sentence
        // was not true until this line existed.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.RPV_STEAM_OUTLET.get(),
                (be, side) -> new RpvSteamOutletPeripheral(be));

        // Feedwater (SPEC section 15). The normal level control path, and the
        // surface a player's level control program is written against — because
        // the mod ships no level control at all. One block entity type covers
        // both feed pumps; the peripheral type string carries the drive, so
        // peripheral.find("bwr_turbine_feed_pump") finds the pumps that survive
        // a blackout and not the ones that do not.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                BwrBlockEntities.FEEDWATER_PUMP.get(),
                (be, side) -> new FeedwaterPumpPeripheral(be));
    }
}
