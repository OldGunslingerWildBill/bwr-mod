package dev.bwr.mod.registry;

import dev.bwr.core.eccs.EccsDesign;
import dev.bwr.core.feedwater.FeedwaterDesign;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.water.HighPressureWaterPipeBlock;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.reactor.RecirculationPortBlock;
import dev.bwr.mod.eccs.AdsControllerBlock;
import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import dev.bwr.mod.eccs.EccsPumpBlock;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.feedwater.FeedwaterPumpBlock;
import dev.bwr.mod.flow.JetPumpBlock;
import dev.bwr.mod.flow.RecirculationPumpBlock;
import dev.bwr.mod.fuel.FuelFabricatorBlock;
import dev.bwr.mod.reactor.CoreSpraySpargerBlock;
import dev.bwr.mod.reactor.ReactorControllerBlock;
import dev.bwr.mod.reactor.ReactorVesselBlock;
import dev.bwr.mod.reactor.RpvSteamOutletBlock;
import dev.bwr.mod.rods.ControlRodDriveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.PressurisedTubeBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.TurbineSteamOutletBlock;
import dev.bwr.mod.suppression.SuppressionPoolControllerBlock;
import dev.bwr.mod.suppression.SuppressionPoolQuencherBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block registry. Every block here is hardware — a vessel wall, a valve body, a
 * pump casing. None of them make decisions.
 */
public final class BwrBlocks {

    private BwrBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BwrMod.MOD_ID);

    /** Heavy steel-ish properties shared by the pressure boundary. */
    private static BlockBehaviour.Properties vesselSteel() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(5.0F, 1200.0F)
                .sound(SoundType.NETHERITE_BLOCK)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(4.0F, 60.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    // --- Reactor multiblock ------------------------------------------

    /** Owns the ReactorCore instance and ticks the physics. One per reactor. */
    public static final DeferredBlock<ReactorControllerBlock> REACTOR_CONTROLLER =
            BLOCKS.register("reactor_controller", () -> new ReactorControllerBlock(machine()));

    /** Reactor pressure vessel shell. Forms the pressure boundary. */
    public static final DeferredBlock<ReactorVesselBlock> REACTOR_VESSEL =
            BLOCKS.register("reactor_vessel", () -> new ReactorVesselBlock(vesselSteel()));

    /**
     * One per control rod, mounted on the vessel bottom head directly beneath
     * its rod. A missing drive means the multiblock does not form.
     */
    public static final DeferredBlock<ControlRodDriveBlock> CONTROL_ROD_DRIVE =
            BLOCKS.register("control_rod_drive", () -> new ControlRodDriveBlock(machine()));

    /**
     * Core spray sparger segment. Assembled into a ring around the core
     * perimeter; spray capacity scales with how complete that ring is.
     */
    public static final DeferredBlock<CoreSpraySpargerBlock> CORE_SPRAY_SPARGER =
            BLOCKS.register("core_spray_sparger", () -> new CoreSpraySpargerBlock(machine()));

    // --- Flow ---------------------------------------------------------

    /** External recirculation pump. A satellite block: announces itself to the controller. */
    public static final DeferredBlock<RecirculationPumpBlock> RECIRCULATION_PUMP =
            BLOCKS.register("recirculation_pump", () -> new RecirculationPumpBlock(machine()));

    /** Jet pump, placed in the downcomer annulus. */
    public static final DeferredBlock<JetPumpBlock> JET_PUMP =
            BLOCKS.register("jet_pump", () -> new JetPumpBlock(machine()));

    public static final DeferredBlock<PumpAssemblyBlock> RIP_PUMP =
            BLOCKS.register("rip_pump", () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.RIP));

    public static final DeferredBlock<HighPressureWaterPipeBlock> HIGH_PRESSURE_WATER_PIPE =
            BLOCKS.register("high_pressure_water_pipe", () -> new HighPressureWaterPipeBlock(machine()));
    public static final DeferredBlock<RpvWaterInjectionPortBlock> RPV_WATER_INJECTION_PORT =
            BLOCKS.register("rpv_water_injection_port", () -> new RpvWaterInjectionPortBlock(vesselSteel()));

    public static final DeferredBlock<RecirculationPortBlock> RECIRCULATION_OUTLET =
            BLOCKS.register("recirculation_outlet", () -> new RecirculationPortBlock(vesselSteel(),true));
    public static final DeferredBlock<RecirculationPortBlock> RECIRCULATION_INLET =
            BLOCKS.register("recirculation_inlet", () -> new RecirculationPortBlock(vesselSteel(),false));

    // --- Steam --------------------------------------------------------

    /**
     * Safety/relief valve. Player-actuated via CC:Tweaked or redstone — it does
     * NOT self-open on pressure. Must be validated as discharging underwater
     * into the suppression pool.
     */
    public static final DeferredBlock<SafetyReliefValveBlock> SAFETY_RELIEF_VALVE =
            BLOCKS.register("safety_relief_valve", () -> new SafetyReliefValveBlock(machine()));

    /** Main steam isolation valve. Also player-actuated. */
    public static final DeferredBlock<MainSteamIsolationValveBlock> MSIV =
            BLOCKS.register("msiv", () -> new MainSteamIsolationValveBlock(machine()));

    /**
     * Pressurised steam tube. Provides connectivity and validation only — the
     * pressure model uses two lumped volumes, never per-segment solving.
     */
    public static final DeferredBlock<PressurisedTubeBlock> PRESSURISED_TUBE =
            BLOCKS.register("pressurised_tube", () -> new PressurisedTubeBlock(machine()));

    /**
     * RPV main steam nozzle — the penetration steam actually leaves the vessel
     * through, and part of the pressure boundary rather than a satellite of it.
     *
     * <p>Not to be confused with the turbine steam outlet below, which is a
     * different piece of plant at the other end of the steam line: this one is
     * welded into the vessel wall and is where the line starts, that one is
     * where the line ends and hands its steam to Mekanism. A plant wants both,
     * and four of these, per SPEC section 6.4's split between the RPV steam dome
     * and the main steam header.
     *
     * <p>Vessel steel rather than machine casing, because that is what it is.
     */
    public static final DeferredBlock<RpvSteamOutletBlock> RPV_STEAM_OUTLET =
            BLOCKS.register("rpv_steam_outlet", () -> new RpvSteamOutletBlock(vesselSteel()));

    /**
     * Turbine steam outlet — the boundary where our physics hands steam to
     * Mekanism (SPEC section 13). The only place in the mod where Mekanism steam
     * exists; everything upstream is kg/s of ours. Registers and works fine with
     * Mekanism absent, it just has nothing to hand steam to.
     */
    public static final DeferredBlock<TurbineSteamOutletBlock> TURBINE_STEAM_OUTLET =
            BLOCKS.register("turbine_steam_outlet", () -> new TurbineSteamOutletBlock(machine()));

    // --- Fuel cycle -----------------------------------------------------

    /**
     * Turns Mekanism's enrichment products into fuel assemblies. The enrichment
     * of the bundle is whatever ratio the player's plumbing supplied, so LEU and
     * HEU come out of the same machine with nothing configured differently.
     */
    public static final DeferredBlock<FuelFabricatorBlock> FUEL_FABRICATOR =
            BLOCKS.register("fuel_fabricator", () -> new FuelFabricatorBlock(machine()));

    // --- Emergency core cooling (SPEC section 9) ------------------------
    //
    // Six injection machines, all the same block class carrying a different
    // nameplate, because everything that separates them is a number: a pump
    // curve, a drive, a motor rating, a delivery path. None of them start
    // themselves and none of them contain a setpoint.

    /**
     * Reactor Core Isolation Cooling. Steam turbine driven, needs no AC at all,
     * 700 gpm. Enough to hold level against decay heat boiloff, nowhere near
     * enough for a large break. The station blackout workhorse.
     */
    public static final DeferredBlock<EccsPumpBlock> RCIC_TURBINE_PUMP =
            BLOCKS.register("rcic_turbine_pump",
                    () -> new EccsPumpBlock(machine(), EccsDesign.RCIC));

    /**
     * High Pressure Coolant Injection. Also turbine driven and also AC-free,
     * seven times the flow — and it takes an order of magnitude more steam out
     * of the vessel to do it, so running it moves the pressure.
     */
    public static final DeferredBlock<EccsPumpBlock> HPCI_TURBINE_PUMP =
            BLOCKS.register("hpci_turbine_pump",
                    () -> new EccsPumpBlock(machine(), EccsDesign.HPCI));

    /**
     * High Pressure Core Spray. Motor driven at 2.6 MW, so it is scrap metal in
     * a blackout unless emergency power was built and starts.
     */
    public static final DeferredBlock<PumpAssemblyBlock> HPCS_PUMP =
            BLOCKS.register("hpcs_pump", () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.HPCS));

    /**
     * Low Pressure Core Spray. Enormous volume, 300 psi of shutoff head. Useless
     * until the vessel has been blown down.
     */
    public static final DeferredBlock<PumpAssemblyBlock> LPCS_PUMP =
            BLOCKS.register("lpcs_pump", () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.LPCS));

    /**
     * Residual Heat Removal, in LPCI mode. The largest flow and the softest
     * pump in the plant, and the same loop is the only suppression pool cooling
     * there is — it cannot do both at once.
     */
    public static final DeferredBlock<PumpAssemblyBlock> RHR_PUMP =
            BLOCKS.register("rhr_pump", () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.RHR));

    /**
     * Standby Liquid Control. Positive displacement, 43 gpm of sodium
     * pentaborate, about three quarters of an hour to a shutdown concentration.
     * The answer to an ATWS, and slow on purpose.
     */
    public static final DeferredBlock<EccsPumpBlock> SLC_PUMP =
            BLOCKS.register("slc_pump", () -> new EccsPumpBlock(machine(), EccsDesign.SLC));

    /**
     * Automatic Depressurisation System — automatic in name only. Holds the
     * relief valves open on command so the low pressure systems can inject.
     */
    public static final DeferredBlock<AdsControllerBlock> ADS_CONTROLLER =
            BLOCKS.register("ads_controller", () -> new AdsControllerBlock(machine()));

    /**
     * Condensate storage tank: the cold, finite alternative to pool suction.
     * One millibucket is one kilogram, so it holds 2000 tonnes.
     */
    public static final DeferredBlock<CondensateStorageTankBlock> CONDENSATE_STORAGE_TANK =
            BLOCKS.register("condensate_storage_tank",
                    () -> new CondensateStorageTankBlock(vesselSteel()));

    // --- Feedwater (SPEC section 15) ------------------------------------
    //
    // The normal level control path, and a different system from the emergency
    // machines above: feedwater is what holds inventory every second the plant
    // is running, and losing it is how most level transients start. Two pumps,
    // the same block class carrying a different nameplate, half capacity each so
    // that losing one halves feedwater rather than ending it. Neither of them
    // contains a level element, a setpoint or a trip.

    /**
     * Motor-driven reactor feed pump. Simple, controllable and indifferent to
     * what the reactor is doing — and at 13 MW it is the largest single
     * electrical load in the mod, roughly two recirculation pumps. In a station
     * blackout it is scrap metal.
     */
    public static final DeferredBlock<PumpAssemblyBlock> MOTOR_FEED_PUMP =
            BLOCKS.register("motor_feed_pump",
                    () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.MOTOR_FEED));

    /**
     * Turbine-driven reactor feed pump — the RFPT. No electrical load at all,
     * because its motive force is the reactor's own steam, which is exactly why
     * real plants use them: feed pumping is an enormous parasitic load. In
     * exchange it is hostage to the reactor, cannot start on a cold plant, and
     * fades as the vessel depressurises. That coupling between level control and
     * steam production is the point of the whole system.
     */
    public static final DeferredBlock<PumpAssemblyBlock> TURBINE_FEED_PUMP =
            BLOCKS.register("turbine_feed_pump",
                    () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.TURBINE_FEED));

    /** Full-size exterior assemblies imported from the supplied STEP files. */
    public static final DeferredBlock<TurbineAssemblyBlock> RCIC_TWL =
            BLOCKS.register("rcic_twl", () -> new TurbineAssemblyBlock(machine(), false));
    public static final DeferredBlock<TurbineAssemblyBlock> HPCI_TURBINE =
            BLOCKS.register("hpci_turbine", () -> new TurbineAssemblyBlock(machine(), true));

    // --- Suppression pool ---------------------------------------------

    /** Owns the SuppressionPool model and validates the water volume around it. */
    public static final DeferredBlock<SuppressionPoolControllerBlock> SUPPRESSION_POOL_CONTROLLER =
            BLOCKS.register("suppression_pool_controller",
                    () -> new SuppressionPoolControllerBlock(machine()));

    /** Structural wall of the suppression pool. */
    public static final DeferredBlock<Block> SUPPRESSION_POOL_WALL =
            BLOCKS.register("suppression_pool_wall", () -> new Block(vesselSteel()));

    /**
     * T-quencher — the submerged termination of a relief valve discharge line,
     * and the pool's inlet. The counterpart at the pool end of what
     * {@link #RPV_STEAM_OUTLET} is at the vessel end: before it there was no
     * built path between the two, only a search for water underneath the valve.
     *
     * <p>Machine casing rather than vessel steel. A quencher sits in the pool
     * at containment pressure with water on both sides of it; it is not part of
     * the reactor pressure boundary and nothing about it should read as though
     * it were.
     */
    public static final DeferredBlock<SuppressionPoolQuencherBlock> SUPPRESSION_POOL_QUENCHER =
            BLOCKS.register("suppression_pool_quencher",
                    () -> new SuppressionPoolQuencherBlock(machine()));
}
