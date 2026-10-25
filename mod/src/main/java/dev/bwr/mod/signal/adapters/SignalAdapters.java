package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentEquipmentBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.containment.ContainmentSprayHeaderBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;

/** Registers every built-in adapter, one per block-entity class. Called once by {@link SignalProviders}. */
public final class SignalAdapters {
    private SignalAdapters() {}

    public static void registerAll() {
        SignalProviders.register(dev.bwr.mod.grid.GridTieBlockEntity.class, GridSignals.TIE::bind);
        SignalProviders.register(dev.bwr.mod.grid.FeMeterBlockEntity.class, GridSignals.METER::bind);
        SignalProviders.register(ReactorControllerBlockEntity.class, ReactorSignals.SCHEMA::bind);
        SignalProviders.register(MainSteamIsolationValveBlockEntity.class, SteamSignals.MSIV::bind);
        SignalProviders.register(SafetyReliefValveBlockEntity.class, SteamSignals.SRV::bind);
        SignalProviders.register(TurbineValveBlockEntity.class, SteamSignals.TURBINE_VALVE::bind);
        SignalProviders.register(TurbineSteamOutletBlockEntity.class, SteamSignals.TURBINE_OUTLET::bind);
        SignalProviders.register(RpvSteamOutletBlockEntity.class, SteamSignals.RPV_OUTLET::bind);
        SignalProviders.register(dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity.class, WaterSignals.WATER_VALVE::bind);
        SignalProviders.register(FeedwaterPumpBlockEntity.class, PumpSignals.FEEDWATER::bind);
        SignalProviders.register(RecirculationPumpBlockEntity.class, PumpSignals.RECIRCULATION::bind);
        SignalProviders.register(EccsPumpBlockEntity.class, PumpSignals.ECCS::bind);
        SignalProviders.register(SuppressionPoolBlockEntity.class, PlantSignals.POOL::bind);
        SignalProviders.register(RhrHeatExchangerBlockEntity.class, PlantSignals.HEAT_EXCHANGER::bind);
        SignalProviders.register(dev.bwr.mod.water.WaterOutletChuteBlockEntity.class, PlantSignals.WATER_OUTLET_CHUTE::bind);
        SignalProviders.register(CondenserBlockEntity.class, PlantSignals.CONDENSER::bind);
        SignalProviders.register(CondensateStorageTankBlockEntity.class, PlantSignals.CONDENSATE_TANK::bind);
        SignalProviders.register(CoolingBlockEntity.class, PlantSignals.COOLING::bind);
        SignalProviders.register(PowerModuleBlockEntity.class, PlantSignals.POWER_MODULE::bind);
        SignalProviders.register(dev.bwr.mod.power.TurbineAuxBlockEntity.class, PlantSignals::aux);
        SignalProviders.register(ContainmentControllerBlockEntity.class, ContainmentSignals.CONTROLLER::bind);
        SignalProviders.register(ContainmentSprayHeaderBlockEntity.class, ContainmentSignals.SPRAY_HEADER::bind);
        SignalProviders.register(ContainmentPenetrationBlockEntity.class, ContainmentSignals.PENETRATION::bind);
        SignalProviders.register(ContainmentVaultDoorBlockEntity.class, ContainmentSignals.VAULT_DOOR::bind);
        SignalProviders.register(ContainmentAirlockBlockEntity.class, ContainmentSignals.AIRLOCK::bind);
        SignalProviders.register(ContainmentEquipmentBlockEntity.class, ContainmentSignals.EQUIPMENT::bind);
        SignalProviders.register(dev.bwr.mod.eccs.AdsControllerBlockEntity.class, AdsSignals.CONTROLLER::bind);
        SignalProviders.register(dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity.class, RadiationSignals.MONITOR::bind);
    }
}
