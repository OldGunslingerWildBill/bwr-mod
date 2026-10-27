package dev.bwr.mod.registry;

import dev.bwr.core.eccs.EccsDesign;
import dev.bwr.core.feedwater.FeedwaterDesign;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentControllerBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentEquipmentBlock;
import dev.bwr.mod.steam.TurbineValveBlock;
import dev.bwr.mod.condenser.CondenserBlock;
import dev.bwr.mod.cooling.CoolingBlock;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.water.HighPressureWaterPipeBlock;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.reactor.RecirculationPortBlock;
import dev.bwr.mod.eccs.AdsControllerBlock;
import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import dev.bwr.mod.eccs.EccsPumpBlock;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.ModernPumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.feedwater.FeedwaterPumpBlock;
import dev.bwr.mod.flow.JetPumpBlock;
import dev.bwr.mod.flow.RecirculationPumpBlock;
import dev.bwr.mod.fuel.FuelFabricatorBlock;
import dev.bwr.mod.fuel.ReprocessingPlantBlock;
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
import dev.bwr.mod.power.PowerModuleBlock;
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

    // Uranium: the mod's own ore, so the fuel fabricator's item route does not depend on another mod for uranium.
    // All four need an iron pickaxe (tag minecraft:needs_iron_tool); the ores drop raw uranium, with Fortune, and
    // themselves with Silk Touch (data/bwr/loot_table/blocks). See URANIUM-ORE.md.
    public static final DeferredBlock<Block> URANIUM_ORE =
            BLOCKS.register("uranium_ore", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(3.0f, 3.0f)));
    public static final DeferredBlock<Block> DEEPSLATE_URANIUM_ORE =
            BLOCKS.register("deepslate_uranium_ore", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(4.5f, 3.0f).sound(SoundType.DEEPSLATE)));
    public static final DeferredBlock<Block> RAW_URANIUM_BLOCK =
            BLOCKS.register("raw_uranium_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f)));
    public static final DeferredBlock<Block> URANIUM_BLOCK =
            BLOCKS.register("uranium_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    // Thorium: the mod's own thorium, for the thorium fuel route. Monazite and thorite in rock (the two ores and the veins),
    // and monazite sand, a placer in beach and river sand. Same mining level as the uranium blocks. See THORIUM.md.
    public static final DeferredBlock<Block> THORIUM_ORE =
            BLOCKS.register("thorium_ore", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(3.0f, 3.0f)));
    public static final DeferredBlock<Block> DEEPSLATE_THORIUM_ORE =
            BLOCKS.register("deepslate_thorium_ore", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(4.5f, 3.0f).sound(SoundType.DEEPSLATE)));
    /** Heavy-mineral sand rich in monazite. Falls like sand, is dug with a shovel (any), drops raw thorium. */
    public static final DeferredBlock<net.minecraft.world.level.block.ColoredFallingBlock> MONAZITE_SAND =
            BLOCKS.register("monazite_sand", () -> new net.minecraft.world.level.block.ColoredFallingBlock(
                    new net.minecraft.util.ColorRGBA(0xA98462),
                    BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE)
                            .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.SNARE)
                            .strength(0.6f).sound(SoundType.SAND)));
    public static final DeferredBlock<Block> RAW_THORIUM_BLOCK =
            BLOCKS.register("raw_thorium_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f)));
    public static final DeferredBlock<Block> THORIUM_BLOCK =
            BLOCKS.register("thorium_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    /** Nine lead ingots: the best radiation shield per block (RADIATION.md, "Shielding": a half-value layer of 1 cm). */
    public static final DeferredBlock<Block> LEAD_BLOCK =
            BLOCKS.register("lead_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    /** A block of nine ingots of a reactor-bred heat-source isotope (BREEDING-TARGETS.md). */
    public static final DeferredBlock<Block> POLONIUM_210_BLOCK =
            BLOCKS.register("polonium_210_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    /** A block of nine ingots of a reactor-bred heat-source isotope (BREEDING-TARGETS.md). */
    public static final DeferredBlock<Block> PLUTONIUM_238_BLOCK =
            BLOCKS.register("plutonium_238_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    /** A block of nine ingots of a reactor-bred heat-source isotope (BREEDING-TARGETS.md). */
    public static final DeferredBlock<Block> ACTINIUM_227_BLOCK =
            BLOCKS.register("actinium_227_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    /** A block of nine ingots of a reactor-bred heat-source isotope (BREEDING-TARGETS.md). */
    public static final DeferredBlock<Block> THULIUM_170_BLOCK =
            BLOCKS.register("thulium_170_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL)));
    public static final DeferredBlock<dev.bwr.mod.grid.GridTieBlock> GRID_TIE=BLOCKS.register("grid_tie",()->new dev.bwr.mod.grid.GridTieBlock(vesselSteel()));

    /** Wall-mounted area radiation monitor (a reading, a lamp, two booleans); it never acts on the plant. */
    public static final DeferredBlock<dev.bwr.mod.radiation.AreaRadiationMonitorBlock> AREA_RADIATION_MONITOR =
            BLOCKS.register("area_radiation_monitor", () -> new dev.bwr.mod.radiation.AreaRadiationMonitorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(2.5F, 6.0F)
                            .sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()
                            .lightLevel(state -> switch (state.getValue(dev.bwr.mod.radiation.AreaRadiationMonitorBlock.LAMP)) {
                                case 2 -> 8;
                                case 1 -> 4;
                                default -> 0;
                            })));

    public static final DeferredBlock<dev.bwr.mod.grid.FeMeterBlock> FE_METER=BLOCKS.register("fe_meter",()->new dev.bwr.mod.grid.FeMeterBlock(vesselSteel()));

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

    public static final DeferredBlock<CoolingBlock> NATURAL_DRAFT_TOWER = BLOCKS.register("natural_draft_tower",()->new CoolingBlock(machine(),Design.NATURAL));
    public static final DeferredBlock<CoolingBlock> MECHANICAL_DRAFT_TOWER = BLOCKS.register("mechanical_draft_tower",()->new CoolingBlock(machine(),Design.MECHANICAL));
    public static final DeferredBlock<CoolingBlock> CIRCULATING_WATER_PUMP = BLOCKS.register("circulating_water_pump",()->new CoolingBlock(machine(),Design.CIRCULATING));
    public static final DeferredBlock<CoolingBlock> MAKEUP_WATER_PUMP = BLOCKS.register("makeup_water_pump",()->new CoolingBlock(machine(),Design.MAKEUP));
    public static final DeferredBlock<CoolingBlock> SCREENED_WATER_INTAKE = BLOCKS.register("screened_water_intake",()->new CoolingBlock(machine(),Design.INTAKE));

    // --- Reactor multiblock ------------------------------------------

    /** Owns the ReactorCore instance and ticks the physics. One per reactor. */
    public static final DeferredBlock<ReactorControllerBlock> REACTOR_CONTROLLER =
            BLOCKS.register("reactor_controller", () -> new ReactorControllerBlock(machine()));

    public static final DeferredBlock<ContainmentControllerBlock> CONTAINMENT_CONTROLLER =
            BLOCKS.register("containment_controller", () -> new ContainmentControllerBlock(machine()));
    public static final DeferredBlock<ContainmentVaultDoorBlock> CONTAINMENT_VAULT_DOOR =
            BLOCKS.register("containment_vault_door", () -> new ContainmentVaultDoorBlock(vesselSteel(),3));
    public static final DeferredBlock<ContainmentVaultDoorBlock> CONTAINMENT_VAULT_DOOR_4X4 =
            BLOCKS.register("containment_vault_door_4x4", () -> new ContainmentVaultDoorBlock(vesselSteel(),4));
    public static final DeferredBlock<ContainmentAirlockBlock> CONTAINMENT_AIRLOCK =
            BLOCKS.register("containment_airlock", () -> new ContainmentAirlockBlock(vesselSteel()));
    public static final DeferredBlock<ContainmentEquipmentBlock> CONTAINMENT_NITROGEN_SUPPLY =
            BLOCKS.register("containment_nitrogen_supply", () -> new ContainmentEquipmentBlock(machine(), false));
    public static final DeferredBlock<ContainmentEquipmentBlock> CONTAINMENT_VENT_VALVE =
            BLOCKS.register("containment_vent_valve", () -> new ContainmentEquipmentBlock(machine(), true));
    public static final DeferredBlock<ContainmentPenetrationBlock> CONTAINMENT_STEAM_PENETRATION =
            BLOCKS.register("containment_steam_penetration", () -> new ContainmentPenetrationBlock(vesselSteel(), ContainmentPenetrationBlock.Service.STEAM));
    public static final DeferredBlock<ContainmentPenetrationBlock> CONTAINMENT_WATER_PENETRATION =
            BLOCKS.register("containment_water_penetration", () -> new ContainmentPenetrationBlock(vesselSteel(), ContainmentPenetrationBlock.Service.WATER));
    public static final DeferredBlock<ContainmentPenetrationBlock> CONTAINMENT_PASS_THROUGH =
            BLOCKS.register("containment_pass_through", () -> new ContainmentPenetrationBlock(vesselSteel(), ContainmentPenetrationBlock.Service.PASS_THROUGH));
    public static final DeferredBlock<ContainmentPenetrationBlock> CONTAINMENT_POWER_PENETRATION =
            BLOCKS.register("containment_power_penetration", () -> new ContainmentPenetrationBlock(vesselSteel(), ContainmentPenetrationBlock.Service.POWER));
    public static final DeferredBlock<ContainmentPenetrationBlock> CONTAINMENT_DATA_PENETRATION =
            BLOCKS.register("containment_data_penetration", () -> new ContainmentPenetrationBlock(vesselSteel(), ContainmentPenetrationBlock.Service.DATA));

    /** Reactor pressure vessel shell. Forms the pressure boundary. */
    public static final DeferredBlock<ReactorVesselBlock> REACTOR_VESSEL =
            BLOCKS.register("reactor_vessel", () -> new ReactorVesselBlock(vesselSteel()));

    /**
     * One per control rod, mounted on the vessel bottom head directly beneath
     * its rod. A missing drive means the multiblock does not form.
     */
    public static final DeferredBlock<ControlRodDriveBlock> CONTROL_ROD_DRIVE =
            BLOCKS.register("control_rod_drive", () -> new ControlRodDriveBlock(machine().noOcclusion()));

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

    /** The bottom head drain: a small vessel port in the lowest wall row, the RWCU pump's second suction. */
    public static final DeferredBlock<dev.bwr.mod.reactor.RpvBottomDrainPortBlock> RPV_BOTTOM_DRAIN_PORT =
            BLOCKS.register("rpv_bottom_drain_port", () -> new dev.bwr.mod.reactor.RpvBottomDrainPortBlock(vesselSteel()));
    public static final DeferredBlock<dev.bwr.mod.rwcu.RwcuPumpBlock> RWCU_PUMP =
            BLOCKS.register("rwcu_pump", () -> new dev.bwr.mod.rwcu.RwcuPumpBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.rwcu.RwcuRegenBlock> RWCU_REGENERATIVE_HEAT_EXCHANGER =
            BLOCKS.register("rwcu_regenerative_heat_exchanger", () -> new dev.bwr.mod.rwcu.RwcuRegenBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.rwcu.RwcuCoolerBlock> RWCU_NONREGENERATIVE_HEAT_EXCHANGER =
            BLOCKS.register("rwcu_nonregenerative_heat_exchanger", () -> new dev.bwr.mod.rwcu.RwcuCoolerBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.rwcu.RwcuDeminBlock> RWCU_FILTER_DEMINERALIZER =
            BLOCKS.register("rwcu_filter_demineralizer", () -> new dev.bwr.mod.rwcu.RwcuDeminBlock(machine()));
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

    /**
     * Takes a spent fuel assembly apart: plutonium, depleted uranium and fission-product waste.
     * The other half of the fuel cycle the fabricator starts.
     */
    public static final DeferredBlock<ReprocessingPlantBlock> REPROCESSING_PLANT =
            BLOCKS.register("reprocessing_plant", () -> new ReprocessingPlantBlock(machine()));

    /**
     * The next stage after the reprocessing plant: fission-product waste in, strontium-90, caesium-137 and americium-241
     * capsules and stripped waste out (see RTG.md).
     */
    public static final DeferredBlock<dev.bwr.mod.isotope.IsotopeSeparatorBlock> ISOTOPE_SEPARATOR =
            BLOCKS.register("isotope_separator", () -> new dev.bwr.mod.isotope.IsotopeSeparatorBlock(machine()));

    /** The radioisotope thermoelectric generator: sealed isotope capsules in, a small steady FE supply out (see RTG.md). */
    public static final DeferredBlock<dev.bwr.mod.isotope.RtgBlock> RTG =
            BLOCKS.register("rtg", () -> new dev.bwr.mod.isotope.RtgBlock(machine().noOcclusion()));

    /** The Large RTG: a 2 x 2 x 3 multiblock that holds up to 1,000 kg of isotope material (see RTG.md). One item places it. */
    public static final DeferredBlock<dev.bwr.mod.isotope.LargeRtgBlock> LARGE_RTG =
            BLOCKS.register("large_rtg", () -> new dev.bwr.mod.isotope.LargeRtgBlock(machine().noOcclusion()));

    /** The xenon floodlight: an aimable fixture that lights the area its beam points at while it has a redstone signal. */
    public static final DeferredBlock<dev.bwr.mod.floodlight.XenonFloodlightBlock> XENON_FLOODLIGHT =
            BLOCKS.register("xenon_floodlight", () -> new dev.bwr.mod.floodlight.XenonFloodlightBlock(
                    dev.bwr.mod.floodlight.XenonFloodlightBlock.lampProperties()));

    /** The xenon high-bay: a ceiling fixture that lights a large room while it has a redstone signal. */
    public static final DeferredBlock<dev.bwr.mod.floodlight.XenonHighBayBlock> XENON_HIGH_BAY =
            BLOCKS.register("xenon_high_bay", () -> new dev.bwr.mod.floodlight.XenonHighBayBlock(
                    dev.bwr.mod.floodlight.XenonHighBayBlock.lampProperties()));

    /** The high-pressure sodium flat panel: a flat golden-white ceiling or wall fixture that floods an area while powered. */
    public static final DeferredBlock<dev.bwr.mod.floodlight.HpsFlatPanelBlock> HPS_FLAT_PANEL =
            BLOCKS.register("hps_flat_panel", () -> new dev.bwr.mod.floodlight.HpsFlatPanelBlock(
                    dev.bwr.mod.floodlight.HpsFlatPanelBlock.lampProperties()));

    // RTG isotope storage blocks: nine ingots each, 2,250 g (RTG.md). Carried like the ingots; placed they are inert storage.
    private static BlockBehaviour.Properties isotopeBlock(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour)
                .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE)
                .requiresCorrectToolForDrops().strength(5.0f, 6.0f).sound(SoundType.METAL);
    }
    public static final DeferredBlock<Block> STRONTIUM_90_BLOCK = BLOCKS.register("strontium_90_block", () -> new Block(isotopeBlock(MapColor.COLOR_RED)));
    public static final DeferredBlock<Block> CAESIUM_137_BLOCK = BLOCKS.register("caesium_137_block", () -> new Block(isotopeBlock(MapColor.COLOR_BLUE)));
    public static final DeferredBlock<Block> AMERICIUM_241_BLOCK = BLOCKS.register("americium_241_block", () -> new Block(isotopeBlock(MapColor.COLOR_GREEN)));
    public static final DeferredBlock<Block> COBALT_60_BLOCK = BLOCKS.register("cobalt_60_block", () -> new Block(isotopeBlock(MapColor.COLOR_PURPLE)));

    // --- Spent fuel pool (the fuel cycle: fabricator, reactor, pool, reprocessing plant) ----------

    /** Lined concrete floor and walls of a spent fuel pool. */
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelPoolWallBlock> SPENT_FUEL_POOL_WALL =
            BLOCKS.register("spent_fuel_pool_wall", () -> new dev.bwr.mod.spentfuel.SpentFuelPoolWallBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(4.0F, 40.0F)
                            .sound(SoundType.STONE).requiresCorrectToolForDrops()));
    /** The controller: one block of the shell, the pool's only ticker. */
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelPoolControllerBlock> SPENT_FUEL_POOL_CONTROLLER =
            BLOCKS.register("spent_fuel_pool_controller", () -> new dev.bwr.mod.spentfuel.SpentFuelPoolControllerBlock(machine()));
    /** A rack of four cells that stands on the floor of the pool. */
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelRackBlock> SPENT_FUEL_RACK =
            BLOCKS.register("spent_fuel_rack", () -> new dev.bwr.mod.spentfuel.SpentFuelRackBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 30.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock> SPENT_FUEL_POOL_MAKEUP_PORT =
            BLOCKS.register("spent_fuel_pool_makeup_port", () -> new dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock(
                    machine(), dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock.Kind.MAKEUP));
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock> SPENT_FUEL_POOL_COOLING_IN =
            BLOCKS.register("spent_fuel_pool_cooling_in", () -> new dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock(
                    machine(), dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock.Kind.COOLING_IN));
    public static final DeferredBlock<dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock> SPENT_FUEL_POOL_COOLING_OUT =
            BLOCKS.register("spent_fuel_pool_cooling_out", () -> new dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock(
                    machine(), dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock.Kind.COOLING_OUT));

    // --- Refuelling bridge and the cattle chute (REFUELLING-BRIDGE.md) ----------------------------

    /** A length of rail for the refuelling bridge. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.RefuellingRailBlock> REFUELLING_RAIL =
            BLOCKS.register("refuelling_rail", () -> new dev.bwr.mod.refuelling.RefuellingRailBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 30.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** A lined trough of the cattle chute between the reactor cavity and the spent fuel pool. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.TransferCanalBlock> TRANSFER_CANAL =
            BLOCKS.register("transfer_canal", () -> new dev.bwr.mod.refuelling.TransferCanalBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(4.0F, 40.0F)
                            .sound(SoundType.STONE).requiresCorrectToolForDrops().noOcclusion()));
    /** A gate in the cattle chute. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.CanalGateBlock> CANAL_GATE =
            BLOCKS.register("canal_gate", () -> new dev.bwr.mod.refuelling.CanalGateBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 40.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** One block of a canal gate plug: a stack of them in a canal column is a gate the building crane lifts out whole (GATE OUT / GATE IN). */
    public static final DeferredBlock<dev.bwr.mod.refuelling.CanalGatePlugBlock> CANAL_GATE_PLUG =
            BLOCKS.register("canal_gate_plug", () -> new dev.bwr.mod.refuelling.CanalGatePlugBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 40.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** The rack on the refuel floor a canal gate is laid in. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.GateLaydownStandBlock> GATE_LAYDOWN_STAND =
            BLOCKS.register("gate_laydown_stand", () -> new dev.bwr.mod.refuelling.GateLaydownStandBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 30.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** Where the bridge sets the steam dryer or the separator assembly down while the vessel is open. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.EquipmentPadBlock> EQUIPMENT_PAD =
            BLOCKS.register("equipment_pad", () -> new dev.bwr.mod.refuelling.EquipmentPadBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 40.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** The new fuel elevator: stood on the rim of a spent fuel pool wall, it lowers fresh fuel down a guide under the water into the racks. */
    public static final DeferredBlock<dev.bwr.mod.spentfuel.NewFuelElevatorBlock> NEW_FUEL_ELEVATOR =
            BLOCKS.register("new_fuel_elevator", () -> new dev.bwr.mod.spentfuel.NewFuelElevatorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 40.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** The reactor building crane's pendant console; the block entity is the crane. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.BuildingCraneConsoleBlock> BUILDING_CRANE_CONSOLE =
            BLOCKS.register("building_crane_console", () -> new dev.bwr.mod.refuelling.BuildingCraneConsoleBlock(machine().noOcclusion()));
    /** A length of runway beam and crane rail, high on a wall of the reactor hall. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.CraneRunwayRailBlock> CRANE_RUNWAY_RAIL =
            BLOCKS.register("crane_runway_rail", () -> new dev.bwr.mod.refuelling.CraneRunwayRailBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 30.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** Where the crane lays the vessel head down while the vessel is open. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.HeadStandBlock> HEAD_STAND =
            BLOCKS.register("head_stand", () -> new dev.bwr.mod.refuelling.HeadStandBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 40.0F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    /** The hydraulic power pack of the stud tensioner that detensions and retensions the closure studs. */
    public static final DeferredBlock<dev.bwr.mod.reactor.StudTensionerBlock> STUD_TENSIONER =
            BLOCKS.register("stud_tensioner", () -> new dev.bwr.mod.reactor.StudTensionerBlock(machine().noOcclusion()));
    /** The bridge's controls; the block entity is the bridge. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.RefuellingBridgeConsoleBlock> REFUELLING_BRIDGE_CONSOLE =
            BLOCKS.register("refuelling_bridge_console", () -> new dev.bwr.mod.refuelling.RefuellingBridgeConsoleBlock(machine().noOcclusion()));

    /** A steel lifting eye bolted to the top of a shield plug block; four of them rig a section for the crane. */
    public static final DeferredBlock<dev.bwr.mod.refuelling.CraneAnchorBlock> CRANE_ANCHOR =
            BLOCKS.register("crane_anchor", () -> new dev.bwr.mod.refuelling.CraneAnchorBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 20.0F)
                            .sound(SoundType.METAL).noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));

    // --- Shield plug concrete (stops a thrown vessel head; see SEVERE-ACCIDENT-UPDATE.md) ------------

    /** Concrete and steel; blast resistance 1,800, stronger than Immersive Engineering's reinforced concrete (about 1,200). */
    public static final DeferredBlock<dev.bwr.mod.accident.ShieldPlugConcreteBlock> SHIELD_PLUG_CONCRETE =
            BLOCKS.register("shield_plug_concrete", () -> new dev.bwr.mod.accident.ShieldPlugConcreteBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(dev.bwr.mod.accident.ShieldPlugConcreteBlock.HARDNESS_1,
                            dev.bwr.mod.accident.ShieldPlugConcreteBlock.RESISTANCE_1).sound(SoundType.STONE).requiresCorrectToolForDrops()));
    /** Shield plug concrete with more steel and netherite; blast resistance 3,600. */
    public static final DeferredBlock<dev.bwr.mod.accident.ShieldPlugConcreteBlock> HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE =
            BLOCKS.register("heavily_reinforced_shield_plug_concrete", () -> new dev.bwr.mod.accident.ShieldPlugConcreteBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(dev.bwr.mod.accident.ShieldPlugConcreteBlock.HARDNESS_2,
                            dev.bwr.mod.accident.ShieldPlugConcreteBlock.RESISTANCE_2).sound(SoundType.STONE).requiresCorrectToolForDrops()));
    /** The heaviest tier, built from netherite; blast resistance 7,200. */
    public static final DeferredBlock<dev.bwr.mod.accident.ShieldPlugConcreteBlock> ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE =
            BLOCKS.register("ultra_reinforced_shield_plug_concrete", () -> new dev.bwr.mod.accident.ShieldPlugConcreteBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(dev.bwr.mod.accident.ShieldPlugConcreteBlock.HARDNESS_3,
                            dev.bwr.mod.accident.ShieldPlugConcreteBlock.RESISTANCE_3).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()));

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
            BLOCKS.register("hpcs_pump", () -> new ModernPumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.HPCS));

    /**
     * Low Pressure Core Spray. Enormous volume, 300 psi of shutoff head. Useless
     * until the vessel has been blown down.
     */
    public static final DeferredBlock<PumpAssemblyBlock> LPCS_PUMP =
            BLOCKS.register("lpcs_pump", () -> new ModernPumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.LPCS));

    /**
     * Residual Heat Removal, in LPCI mode. The largest flow and the softest
     * pump in the plant, and the same loop is the only suppression pool cooling
     * there is — it cannot do both at once.
     */
    public static final DeferredBlock<PumpAssemblyBlock> RHR_PUMP =
            BLOCKS.register("rhr_pump", () -> new ModernPumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.RHR));

    /**
     * Standby Liquid Control. Positive displacement, 43 gpm of sodium
     * pentaborate, about three quarters of an hour to a shutdown concentration.
     * The answer to an ATWS, and slow on purpose.
     */
    public static final DeferredBlock<PumpAssemblyBlock> SLC_PUMP =
            BLOCKS.register("slc_pump", () -> new PumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.SLC));

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

    public static final DeferredBlock<dev.bwr.mod.eccs.SlcTankBlock> SLC_BORON_TANK =
            BLOCKS.register("slc_boron_tank", () -> new dev.bwr.mod.eccs.SlcTankBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.steam.AdsReliefValveBlock> ADS_RELIEF_VALVE =
            BLOCKS.register("ads_relief_valve", () -> new dev.bwr.mod.steam.AdsReliefValveBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.water.WaterDischargeBlock> WATER_DISCHARGE_PORT =
            BLOCKS.register("water_discharge_port", () -> new dev.bwr.mod.water.WaterDischargeBlock(machine()));
    /** Concrete outfall for the heat exchanger's river cooling water; discharges only over or into open water. */
    public static final DeferredBlock<dev.bwr.mod.water.WaterOutletChuteBlock> WATER_OUTLET_CHUTE =
            BLOCKS.register("water_outlet_chute", () -> new dev.bwr.mod.water.WaterOutletChuteBlock(machine().noOcclusion()));

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
                    () -> new ModernPumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.MOTOR_FEED));

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
                    () -> new ModernPumpAssemblyBlock(machine(), PumpAssemblyBlock.Kind.TURBINE_FEED));

    /** Full-size exterior assemblies imported from the supplied STEP files. */
    public static final DeferredBlock<TurbineAssemblyBlock> RCIC_TWL =
            BLOCKS.register("rcic_twl", () -> new TurbineAssemblyBlock(machine(), false));
    public static final DeferredBlock<TurbineAssemblyBlock> HPCI_TURBINE =
            BLOCKS.register("hpci_turbine", () -> new TurbineAssemblyBlock(machine(), true));

    public static final DeferredBlock<PowerModuleBlock> HP_TURBINE =
            BLOCKS.register("hp_turbine", () -> new PowerModuleBlock(machine(), PumpAssemblyBlock.Kind.HP_TURBINE));
    public static final DeferredBlock<PowerModuleBlock> LP_TURBINE =
            BLOCKS.register("lp_turbine", () -> new PowerModuleBlock(machine(), PumpAssemblyBlock.Kind.LP_TURBINE));
    public static final DeferredBlock<PowerModuleBlock> NUCLEAR_GENERATOR =
            BLOCKS.register("nuclear_generator", () -> new PowerModuleBlock(machine(), PumpAssemblyBlock.Kind.GENERATOR));

    public static final DeferredBlock<TurbineValveBlock> STEAM_STOP_VALVE =
            BLOCKS.register("steam_stop_valve", () -> new TurbineValveBlock(machine()));
    public static final DeferredBlock<TurbineValveBlock> TURBINE_CONTROL_VALVE =
            BLOCKS.register("turbine_control_valve", () -> new TurbineValveBlock(machine()));
    public static final DeferredBlock<TurbineValveBlock> BYPASS_STEAM_VALVE =
            BLOCKS.register("bypass_steam_valve", () -> new TurbineValveBlock(machine()));
    /** A motor-operated valve for BWR water lines; it throttles the line and counts as an isolation point. */
    public static final DeferredBlock<dev.bwr.mod.water.MotorOperatedWaterValveBlock> MOTOR_OPERATED_WATER_VALVE =
            BLOCKS.register("motor_operated_water_valve", () -> new dev.bwr.mod.water.MotorOperatedWaterValveBlock(machine()));
    /** A drain through the wall or floor of the reactor well, piped to the Condensate Storage Tank through a valve. */
    public static final DeferredBlock<dev.bwr.mod.reactor.CavityDrainPortBlock> CAVITY_DRAIN_PORT =
            BLOCKS.register("cavity_drain_port", () -> new dev.bwr.mod.reactor.CavityDrainPortBlock(vesselSteel()));
    /** A fill line into the reactor well: a pump's discharge, or a higher Condensate Storage Tank through a valve, floods the well through it. */
    public static final DeferredBlock<dev.bwr.mod.reactor.CavityFillPortBlock> CAVITY_FILL_PORT =
            BLOCKS.register("cavity_fill_port", () -> new dev.bwr.mod.reactor.CavityFillPortBlock(vesselSteel()));
    /** Turbine auxiliaries (TURBINE-AUX.md): the oil tank with its pumps and cooler, the shaft-driven oil pump, the generator cooling skid. */
    public static final DeferredBlock<dev.bwr.mod.power.TurbineAuxBlock> MAIN_OIL_TANK =
            BLOCKS.register("main_oil_tank", () -> new dev.bwr.mod.power.TurbineAuxBlock(machine(), dev.bwr.mod.power.TurbineAuxBlock.Kind.LUBE_OIL));
    public static final DeferredBlock<dev.bwr.mod.power.TurbineAuxBlock> SHAFT_OIL_PUMP =
            BLOCKS.register("shaft_oil_pump", () -> new dev.bwr.mod.power.TurbineAuxBlock(machine(), dev.bwr.mod.power.TurbineAuxBlock.Kind.SHAFT_PUMP));
    public static final DeferredBlock<dev.bwr.mod.power.TurbineAuxBlock> GENERATOR_COOLING_SKID =
            BLOCKS.register("generator_cooling_skid", () -> new dev.bwr.mod.power.TurbineAuxBlock(machine(), dev.bwr.mod.power.TurbineAuxBlock.Kind.GEN_COOLING));
    /** The turbine hall (TURBINE-MAINTENANCE.md): a rack for spare and damaged LP blade sections, and the cradle an LP hood is set on. */
    public static final DeferredBlock<dev.bwr.mod.power.TurbineStandBlock> LP_BLADE_STAND =
            BLOCKS.register("lp_blade_stand", () -> new dev.bwr.mod.power.TurbineStandBlock(machine().noOcclusion(), dev.bwr.mod.power.TurbineStandBlock.Kind.BLADES));
    public static final DeferredBlock<dev.bwr.mod.power.TurbineStandBlock> CASING_LAYDOWN_STAND =
            BLOCKS.register("casing_laydown_stand", () -> new dev.bwr.mod.power.TurbineStandBlock(machine().noOcclusion(), dev.bwr.mod.power.TurbineStandBlock.Kind.CASING));
    /** The rotor half a shaft excursion left on the floor (TURBINE-MAINTENANCE.md): no item, placed only by the excursion, slow to cut up. */
    public static final DeferredBlock<dev.bwr.mod.power.TurbineWreckBlock> TURBINE_SHAFT_WRECK =
            BLOCKS.register("turbine_shaft_wreck", () -> new dev.bwr.mod.power.TurbineWreckBlock(machine().strength(12.0F, 60.0F).noOcclusion()));
    public static final DeferredBlock<CondenserBlock> ARABELLE_CONDENSER =
            BLOCKS.register("arabelle_condenser", () -> new CondenserBlock(machine()));

    // --- Suppression pool ---------------------------------------------

    /** Owns the SuppressionPool model and validates the water volume around it. */
    public static final DeferredBlock<SuppressionPoolControllerBlock> SUPPRESSION_POOL_CONTROLLER =
            BLOCKS.register("suppression_pool_controller",
                    () -> new SuppressionPoolControllerBlock(machine()));

    /** Structural wall of the suppression pool. */
    public static final DeferredBlock<dev.bwr.mod.suppression.SuppressionPoolWallBlock> SUPPRESSION_POOL_WALL =
            BLOCKS.register("suppression_pool_wall", () -> new dev.bwr.mod.suppression.SuppressionPoolWallBlock(BlockBehaviour.Properties.of().strength(4.0f,30.0f).sound(SoundType.STONE).requiresCorrectToolForDrops()));
    public static final DeferredBlock<dev.bwr.mod.suppression.SuppressionPoolPortBlock> SUPPRESSION_POOL_SUCTION =
            BLOCKS.register("suppression_pool_suction",()->new dev.bwr.mod.suppression.SuppressionPoolPortBlock(machine(),true));
    public static final DeferredBlock<dev.bwr.mod.suppression.SuppressionPoolPortBlock> SUPPRESSION_POOL_RETURN =
            BLOCKS.register("suppression_pool_return",()->new dev.bwr.mod.suppression.SuppressionPoolPortBlock(machine(),false));
    public static final DeferredBlock<dev.bwr.mod.suppression.SuppressionPoolDrainBlock> SUPPRESSION_POOL_DRAIN =
            BLOCKS.register("suppression_pool_drain",()->new dev.bwr.mod.suppression.SuppressionPoolDrainBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.suppression.SuppressionPoolSteamPortBlock> SUPPRESSION_POOL_STEAM_INLET =
            BLOCKS.register("suppression_pool_steam_inlet",()->new dev.bwr.mod.suppression.SuppressionPoolSteamPortBlock(machine()));
    public static final DeferredBlock<dev.bwr.mod.suppression.RhrHeatExchangerBlock> RHR_HEAT_EXCHANGER =
            BLOCKS.register("rhr_heat_exchanger",()->new dev.bwr.mod.suppression.RhrHeatExchangerBlock(machine().noOcclusion()));
    public static final DeferredBlock<dev.bwr.mod.containment.ContainmentSprayHeaderBlock> CONTAINMENT_SPRAY_HEADER =
            BLOCKS.register("containment_spray_header", () -> new dev.bwr.mod.containment.ContainmentSprayHeaderBlock(machine().noOcclusion()));

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

    // --- Severe accident ------------------------------------------------

    /**
     * Corium: molten core and dissolved concrete under a failed vessel. Placed
     * by the reactor, never crafted; see dev.bwr.mod.accident.CoriumPool.
     */
    public static final DeferredBlock<dev.bwr.mod.accident.CoriumBlock> CORIUM =
            BLOCKS.register("corium", () -> new dev.bwr.mod.accident.CoriumBlock());
    // --- Control panels --------------------------------------------------

    /** Painted board for control rooms; breaks with any tool. */
    private static BlockBehaviour.Properties panelBoard() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .strength(1.5F, 6.0F)
                .sound(SoundType.METAL);
    }

    public static final DeferredBlock<dev.bwr.mod.panel.PanelBlock> CONTROL_PANEL_VERTICAL =
            BLOCKS.register("control_panel_vertical",
                    () -> new dev.bwr.mod.panel.PanelBlock(panelBoard(), dev.bwr.mod.panel.PanelType.VERTICAL));
    public static final DeferredBlock<dev.bwr.mod.panel.PanelBlock> CONTROL_PANEL_BENCHBOARD =
            BLOCKS.register("control_panel_benchboard",
                    () -> new dev.bwr.mod.panel.PanelBlock(panelBoard(), dev.bwr.mod.panel.PanelType.BENCHBOARD));
    public static final DeferredBlock<dev.bwr.mod.panel.PanelBlock> CONTROL_PANEL_CONSOLE =
            BLOCKS.register("control_panel_console",
                    () -> new dev.bwr.mod.panel.PanelBlock(panelBoard(), dev.bwr.mod.panel.PanelType.CONSOLE));
    public static final DeferredBlock<dev.bwr.mod.panel.PanelBlock> CONTROL_PANEL_BACK =
            BLOCKS.register("control_panel_back",
                    () -> new dev.bwr.mod.panel.PanelBlock(panelBoard(), dev.bwr.mod.panel.PanelType.BACK_PANEL));
    /** The owner's control desks: a 1 m desk (one block) and a 2 m desk (two blocks, see DeskPanelBlock), a level top 0.40 up. */
    public static final DeferredBlock<dev.bwr.mod.panel.PanelBlock> CONTROL_PANEL_DESK =
            BLOCKS.register("control_panel_desk",
                    () -> new dev.bwr.mod.panel.PanelBlock(panelBoard(), dev.bwr.mod.panel.PanelType.DESK));
    public static final DeferredBlock<dev.bwr.mod.panel.DeskPanelBlock> CONTROL_PANEL_DESK_WIDE =
            BLOCKS.register("control_panel_desk_wide",
                    () -> new dev.bwr.mod.panel.DeskPanelBlock(panelBoard()));
    /** The duplex board: one item places a 1 x 2 x 4 column (bench, board, tilted header); see DuplexPanelBlock. */
    public static final DeferredBlock<dev.bwr.mod.panel.DuplexPanelBlock> CONTROL_PANEL_DUPLEX =
            BLOCKS.register("control_panel_duplex",
                    () -> new dev.bwr.mod.panel.DuplexPanelBlock(panelBoard()));

    // --- Control-room furniture (ROOM-FURNITURE.md): decorative, no block entities ---------------------

    /** Painted steel furniture for control rooms; breaks with any tool. */
    private static BlockBehaviour.Properties furniture() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .strength(1.5F, 6.0F)
                .sound(SoundType.METAL);
    }

    public static final DeferredBlock<dev.bwr.mod.furniture.RelayCabinetBlock> RELAY_CABINET =
            BLOCKS.register("relay_cabinet",
                    () -> new dev.bwr.mod.furniture.RelayCabinetBlock(furniture(), false));
    public static final DeferredBlock<dev.bwr.mod.furniture.RelayCabinetBlock> RELAY_CABINET_PLAIN =
            BLOCKS.register("relay_cabinet_plain",
                    () -> new dev.bwr.mod.furniture.RelayCabinetBlock(furniture(), true));
    public static final DeferredBlock<dev.bwr.mod.furniture.DeskBlock> OPERATOR_DESK =
            BLOCKS.register("operator_desk",
                    () -> new dev.bwr.mod.furniture.DeskBlock(furniture()));
    public static final DeferredBlock<dev.bwr.mod.furniture.RoundDeskBlock> OPERATOR_DESK_ROUND =
            BLOCKS.register("operator_desk_round",
                    () -> new dev.bwr.mod.furniture.RoundDeskBlock(furniture()));
    public static final DeferredBlock<dev.bwr.mod.furniture.CornerDeskBlock> OPERATOR_DESK_CORNER =
            BLOCKS.register("operator_desk_corner",
                    () -> new dev.bwr.mod.furniture.CornerDeskBlock(furniture()));
    public static final DeferredBlock<dev.bwr.mod.furniture.SupervisorDeskBlock> SUPERVISOR_DESK =
            BLOCKS.register("supervisor_desk",
                    () -> new dev.bwr.mod.furniture.SupervisorDeskBlock(furniture()));
    public static final DeferredBlock<dev.bwr.mod.furniture.MimicBandBlock> MIMIC_BAND_CREAM =
            BLOCKS.register("mimic_band_cream",
                    () -> new dev.bwr.mod.furniture.MimicBandBlock(furniture(), false));
    public static final DeferredBlock<dev.bwr.mod.furniture.MimicBandBlock> MIMIC_BAND_BLACK =
            BLOCKS.register("mimic_band_black",
                    () -> new dev.bwr.mod.furniture.MimicBandBlock(furniture(), true));
    public static final DeferredBlock<dev.bwr.mod.furniture.WallDisplayBlock> WALL_DISPLAY =
            BLOCKS.register("wall_display",
                    () -> new dev.bwr.mod.furniture.WallDisplayBlock(furniture(), 2));
    public static final DeferredBlock<dev.bwr.mod.furniture.WallDisplayBlock> WALL_DISPLAY_WIDE =
            BLOCKS.register("wall_display_wide",
                    () -> new dev.bwr.mod.furniture.WallDisplayBlock(furniture(), 3));
    public static final DeferredBlock<dev.bwr.mod.furniture.ControlStandBlock> CONTROL_STAND =
            BLOCKS.register("control_stand",
                    () -> new dev.bwr.mod.furniture.ControlStandBlock(furniture()));

    // --- Industrial lamps (LIGHTING.md): redstone-controlled HID lamps, warm-up and restrike on scheduled ticks ---
    /** Lamp housing: painted steel that does not take the panel finish. The light is the lens. */
    private static BlockBehaviour.Properties lamp() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.0F, 6.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .lightLevel(dev.bwr.mod.lighting.IndustrialLampBlock::emission);
    }

    public static final DeferredBlock<dev.bwr.mod.lighting.HighBayLampBlock> LPS_HIGH_BAY =
            BLOCKS.register("lps_high_bay",
                    () -> new dev.bwr.mod.lighting.HighBayLampBlock(lamp(), dev.bwr.mod.lighting.LampType.LOW_PRESSURE_SODIUM));
    public static final DeferredBlock<dev.bwr.mod.lighting.WallPackLampBlock> LPS_WALL_PACK =
            BLOCKS.register("lps_wall_pack",
                    () -> new dev.bwr.mod.lighting.WallPackLampBlock(lamp(), dev.bwr.mod.lighting.LampType.LOW_PRESSURE_SODIUM));
    public static final DeferredBlock<dev.bwr.mod.lighting.HighBayLampBlock> HPS_HIGH_BAY =
            BLOCKS.register("hps_high_bay",
                    () -> new dev.bwr.mod.lighting.HighBayLampBlock(lamp(), dev.bwr.mod.lighting.LampType.HIGH_PRESSURE_SODIUM));
    public static final DeferredBlock<dev.bwr.mod.lighting.WallPackLampBlock> HPS_WALL_PACK =
            BLOCKS.register("hps_wall_pack",
                    () -> new dev.bwr.mod.lighting.WallPackLampBlock(lamp(), dev.bwr.mod.lighting.LampType.HIGH_PRESSURE_SODIUM));
    public static final DeferredBlock<dev.bwr.mod.lighting.HighBayLampBlock> MERCURY_HIGH_BAY =
            BLOCKS.register("mercury_high_bay",
                    () -> new dev.bwr.mod.lighting.HighBayLampBlock(lamp(), dev.bwr.mod.lighting.LampType.MERCURY));
    public static final DeferredBlock<dev.bwr.mod.lighting.WallPackLampBlock> MERCURY_WALL_PACK =
            BLOCKS.register("mercury_wall_pack",
                    () -> new dev.bwr.mod.lighting.WallPackLampBlock(lamp(), dev.bwr.mod.lighting.LampType.MERCURY));

    // --- Control panel trim (decoration, except the header strip, canopy and chamfers: MountableTrimBlock) ----
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelEndCapBlock> CONTROL_PANEL_END_CAP_FLAT =
            BLOCKS.register("control_panel_end_cap_flat",
                    () -> new dev.bwr.mod.panel.trim.PanelEndCapBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.END_CAP_FLAT));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelEndCapBlock> CONTROL_PANEL_END_CAP_BENCH =
            BLOCKS.register("control_panel_end_cap_bench",
                    () -> new dev.bwr.mod.panel.trim.PanelEndCapBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.END_CAP_BENCH));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelEndCapBlock> CONTROL_PANEL_END_CAP_CONSOLE =
            BLOCKS.register("control_panel_end_cap_console",
                    () -> new dev.bwr.mod.panel.trim.PanelEndCapBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.END_CAP_CONSOLE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelEndCapBlock> CONTROL_PANEL_END_CAP_ROUND =
            BLOCKS.register("control_panel_end_cap_round",
                    () -> new dev.bwr.mod.panel.trim.PanelEndCapBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.END_CAP_ROUND));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.MountableTrimBlock> CONTROL_PANEL_HEADER_STRIP =
            BLOCKS.register("control_panel_header_strip",
                    () -> new dev.bwr.mod.panel.trim.MountableTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.HEADER_STRIP));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.MountableTrimBlock> CONTROL_PANEL_HEADER_CANOPY =
            BLOCKS.register("control_panel_header_canopy",
                    () -> new dev.bwr.mod.panel.trim.MountableTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.HEADER_CANOPY));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_PLINTH_KICK =
            BLOCKS.register("control_panel_plinth_kick",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.PLINTH_KICK));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_PLINTH_APRON =
            BLOCKS.register("control_panel_plinth_apron",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.PLINTH_APRON));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_PLINTH_BASE =
            BLOCKS.register("control_panel_plinth_base",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.PLINTH_BASE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_CORNER_VERTICAL_INSIDE =
            BLOCKS.register("control_panel_corner_vertical_inside",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_VERTICAL_INSIDE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE =
            BLOCKS.register("control_panel_corner_vertical_outside",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_VERTICAL_OUTSIDE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.MountableTrimBlock> CONTROL_PANEL_CORNER_VERTICAL_CHAMFER =
            BLOCKS.register("control_panel_corner_vertical_chamfer",
                    () -> new dev.bwr.mod.panel.trim.MountableTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_VERTICAL_CHAMFER));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_CORNER_BENCH_INSIDE =
            BLOCKS.register("control_panel_corner_bench_inside",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_BENCH_INSIDE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_CORNER_BENCH_OUTSIDE =
            BLOCKS.register("control_panel_corner_bench_outside",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_BENCH_OUTSIDE));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.MountableTrimBlock> CONTROL_PANEL_CORNER_BENCH_CHAMFER =
            BLOCKS.register("control_panel_corner_bench_chamfer",
                    () -> new dev.bwr.mod.panel.trim.MountableTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.CORNER_BENCH_CHAMFER));
    public static final DeferredBlock<dev.bwr.mod.panel.trim.PanelTrimBlock> CONTROL_PANEL_HEADER_VENT =
            BLOCKS.register("control_panel_header_vent",
                    () -> new dev.bwr.mod.panel.trim.PanelTrimBlock(panelBoard(), dev.bwr.mod.panel.trim.TrimPiece.HEADER_VENT));
}
