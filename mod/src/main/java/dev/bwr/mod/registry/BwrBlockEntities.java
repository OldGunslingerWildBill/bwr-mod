package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.AdsControllerBlockEntity;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity types. Only blocks that carry state get one. */
public final class BwrBlockEntities {

    private BwrBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BwrMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ReactorControllerBlockEntity>>
            REACTOR_CONTROLLER = BLOCK_ENTITIES.register("reactor_controller",
            () -> BlockEntityType.Builder.of(ReactorControllerBlockEntity::new,
                    BwrBlocks.REACTOR_CONTROLLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlRodDriveBlockEntity>>
            CONTROL_ROD_DRIVE = BLOCK_ENTITIES.register("control_rod_drive",
            () -> BlockEntityType.Builder.of(ControlRodDriveBlockEntity::new,
                    BwrBlocks.CONTROL_ROD_DRIVE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RecirculationPumpBlockEntity>>
            RECIRCULATION_PUMP = BLOCK_ENTITIES.register("recirculation_pump",
            () -> BlockEntityType.Builder.of(RecirculationPumpBlockEntity::new,
                    BwrBlocks.RECIRCULATION_PUMP.get(), BwrBlocks.RIP_PUMP.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SafetyReliefValveBlockEntity>>
            SAFETY_RELIEF_VALVE = BLOCK_ENTITIES.register("safety_relief_valve",
            () -> BlockEntityType.Builder.of(SafetyReliefValveBlockEntity::new,
                    BwrBlocks.SAFETY_RELIEF_VALVE.get(), BwrBlocks.ADS_RELIEF_VALVE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MainSteamIsolationValveBlockEntity>>
            MSIV = BLOCK_ENTITIES.register("msiv",
            () -> BlockEntityType.Builder.of(MainSteamIsolationValveBlockEntity::new,
                    BwrBlocks.MSIV.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.bwr.mod.reactor.RpvWaterInjectionPortBlockEntity>>
            RPV_WATER_INJECTION_PORT = BLOCK_ENTITIES.register("rpv_water_injection_port",
            () -> BlockEntityType.Builder.of(dev.bwr.mod.reactor.RpvWaterInjectionPortBlockEntity::new,
                    BwrBlocks.RPV_WATER_INJECTION_PORT.get(), BwrBlocks.RECIRCULATION_OUTLET.get(), BwrBlocks.RECIRCULATION_INLET.get()).build(null));

    /**
     * The RPV main steam nozzle. It carries a stop position, so it needs state;
     * it carries no ticker, because the controller walks its own nozzles.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RpvSteamOutletBlockEntity>>
            RPV_STEAM_OUTLET = BLOCK_ENTITIES.register("rpv_steam_outlet",
            () -> BlockEntityType.Builder.of(RpvSteamOutletBlockEntity::new,
                    BwrBlocks.RPV_STEAM_OUTLET.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurbineSteamOutletBlockEntity>>
            TURBINE_STEAM_OUTLET = BLOCK_ENTITIES.register("turbine_steam_outlet",
            () -> BlockEntityType.Builder.of(TurbineSteamOutletBlockEntity::new,
                    BwrBlocks.TURBINE_STEAM_OUTLET.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.bwr.mod.steam.TurbineValveBlockEntity>> TURBINE_VALVE =
            BLOCK_ENTITIES.register("turbine_valve", () -> BlockEntityType.Builder.of(dev.bwr.mod.steam.TurbineValveBlockEntity::new,
                    BwrBlocks.STEAM_STOP_VALVE.get(), BwrBlocks.TURBINE_CONTROL_VALVE.get(), BwrBlocks.BYPASS_STEAM_VALVE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.bwr.mod.condenser.CondenserBlockEntity>> CONDENSER =
            BLOCK_ENTITIES.register("condenser", () -> BlockEntityType.Builder.of(dev.bwr.mod.condenser.CondenserBlockEntity::new,
                    BwrBlocks.ARABELLE_CONDENSER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.bwr.mod.cooling.CoolingBlockEntity>> COOLING =
            BLOCK_ENTITIES.register("cooling", () -> BlockEntityType.Builder.of(dev.bwr.mod.cooling.CoolingBlockEntity::new,
                    BwrBlocks.NATURAL_DRAFT_TOWER.get(), BwrBlocks.MECHANICAL_DRAFT_TOWER.get(), BwrBlocks.CIRCULATING_WATER_PUMP.get(),
                    BwrBlocks.MAKEUP_WATER_PUMP.get(), BwrBlocks.SCREENED_WATER_INTAKE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.bwr.mod.power.PowerModuleBlockEntity>> POWER_MODULE =
            BLOCK_ENTITIES.register("power_module", () -> BlockEntityType.Builder.of(dev.bwr.mod.power.PowerModuleBlockEntity::new,
                    BwrBlocks.HP_TURBINE.get(), BwrBlocks.LP_TURBINE.get(), BwrBlocks.NUCLEAR_GENERATOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FuelFabricatorBlockEntity>>
            FUEL_FABRICATOR = BLOCK_ENTITIES.register("fuel_fabricator",
            () -> BlockEntityType.Builder.of(FuelFabricatorBlockEntity::new,
                    BwrBlocks.FUEL_FABRICATOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SuppressionPoolBlockEntity>>
            SUPPRESSION_POOL = BLOCK_ENTITIES.register("suppression_pool",
            () -> BlockEntityType.Builder.of(SuppressionPoolBlockEntity::new,
                    BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<dev.bwr.mod.suppression.SuppressionPoolPortBlockEntity>> SUPPRESSION_POOL_PORT =
            BLOCK_ENTITIES.register("suppression_pool_port",()->BlockEntityType.Builder.of(dev.bwr.mod.suppression.SuppressionPoolPortBlockEntity::new,
                    BwrBlocks.SUPPRESSION_POOL_SUCTION.get(),BwrBlocks.SUPPRESSION_POOL_RETURN.get(),BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity>> RHR_HEAT_EXCHANGER =
            BLOCK_ENTITIES.register("rhr_heat_exchanger",()->BlockEntityType.Builder.of(dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity::new,
                    BwrBlocks.RHR_HEAT_EXCHANGER.get()).build(null));

    /**
     * One type for all six emergency injection machines. They differ only in
     * the {@code EccsDesign} their block carries, which the block entity reads
     * off its own block state, so there is nothing per-machine to register.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EccsPumpBlockEntity>>
            ECCS_PUMP = BLOCK_ENTITIES.register("eccs_pump",
            () -> BlockEntityType.Builder.of(EccsPumpBlockEntity::new,
                    BwrBlocks.RCIC_TWL.get(),
                    BwrBlocks.HPCI_TURBINE.get(),
                    BwrBlocks.RCIC_TURBINE_PUMP.get(),
                    BwrBlocks.HPCI_TURBINE_PUMP.get(),
                    BwrBlocks.HPCS_PUMP.get(),
                    BwrBlocks.LPCS_PUMP.get(),
                    BwrBlocks.RHR_PUMP.get(),
                    BwrBlocks.SLC_PUMP.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdsControllerBlockEntity>>
            ADS_CONTROLLER = BLOCK_ENTITIES.register("ads_controller",
            () -> BlockEntityType.Builder.of(AdsControllerBlockEntity::new,
                    BwrBlocks.ADS_CONTROLLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CondensateStorageTankBlockEntity>>
            CONDENSATE_STORAGE_TANK = BLOCK_ENTITIES.register("condensate_storage_tank",
            () -> BlockEntityType.Builder.of(CondensateStorageTankBlockEntity::new,
                    BwrBlocks.CONDENSATE_STORAGE_TANK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<dev.bwr.mod.eccs.SlcTankBlockEntity>> SLC_TANK =
            BLOCK_ENTITIES.register("slc_boron_tank", () -> BlockEntityType.Builder.of(dev.bwr.mod.eccs.SlcTankBlockEntity::new,BwrBlocks.SLC_BORON_TANK.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<dev.bwr.mod.water.WaterDischargeBlockEntity>> WATER_DISCHARGE =
            BLOCK_ENTITIES.register("water_discharge_port", () -> BlockEntityType.Builder.of(dev.bwr.mod.water.WaterDischargeBlockEntity::new,BwrBlocks.WATER_DISCHARGE_PORT.get()).build(null));

    /**
     * One type for both reactor feed pumps. They differ only in the
     * {@code FeedwaterDesign} their block carries, which the block entity reads
     * off its own block state, so there is nothing per-machine to register.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FeedwaterPumpBlockEntity>>
            FEEDWATER_PUMP = BLOCK_ENTITIES.register("feedwater_pump",
            () -> BlockEntityType.Builder.of(FeedwaterPumpBlockEntity::new,
                    BwrBlocks.MOTOR_FEED_PUMP.get(),
                    BwrBlocks.TURBINE_FEED_PUMP.get()).build(null));
}
