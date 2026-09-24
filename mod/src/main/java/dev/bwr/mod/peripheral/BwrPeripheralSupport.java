package dev.bwr.mod.peripheral;

import dan200.computercraft.api.peripheral.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.feedwater.*;
import dev.bwr.mod.flow.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.suppression.*;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.condenser.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import java.util.*;

/** Optional CC integration. Every exterior cell has a connection face, independent of fluid ports. */
public final class BwrPeripheralSupport {
    private BwrPeripheralSupport() {}
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        int count=0;
        for(var entry:BwrBlocks.BLOCKS.getEntries()) {
            var block=entry.get();var state=block.defaultBlockState();
            // Fixed schemas are discovered once. Prototypes have no level and are never exposed as inventories.
            BlockEntity prototype;
            if(block instanceof PowerModuleBlock)prototype=new PowerModuleBlockEntity(BlockPos.ZERO,state);
            else if(block instanceof PumpAssemblyBlock pump)prototype=switch(pump.kind()) {
                case JET -> null;
                case SLC_TANK -> new SlcTankBlockEntity(BlockPos.ZERO,state);
                case RIP,RCP -> new RecirculationPumpBlockEntity(BlockPos.ZERO,state);
                case MOTOR_FEED,TURBINE_FEED -> new FeedwaterPumpBlockEntity(BlockPos.ZERO,state);
                default -> new EccsPumpBlockEntity(BlockPos.ZERO,state);
            };
            else prototype=block instanceof EntityBlock entity?entity.newBlockEntity(BlockPos.ZERO,state):null;
            IPeripheral sample=block instanceof JetPumpBlock?jet(null,BlockPos.ZERO,state):typed(prototype);
            if(sample==null)continue;
            var schema=sample.getClass();String type=sample.getType();
            event.registerBlock(PeripheralCapability.get(),(level,pos,s,unused,side)->
                    new LivePeripheral(level,pos,s.getBlock(),type,schema,()->resolve(level,pos)),block);
            count++;
        }
        com.mojang.logging.LogUtils.getLogger().info("CC:Tweaked: live computer faces registered on {} machine block types",count);
    }
    private static IPeripheral resolve(Level level,BlockPos pos) {
        if(!level.isLoaded(pos))return null;
        var state=level.getBlockState(pos);
        if(state.getBlock() instanceof JetPumpBlock)return jet(level,pos,state);
        BlockEntity be;
        if(state.getBlock() instanceof ProcessAssembly)be=PumpAssemblyCapabilities.controller(level,pos,state);
        else if(state.getBlock() instanceof MainSteamIsolationValveBlock msiv)be=msiv.controller(level,pos,state);
        else be=level.getBlockEntity(pos);
        if(be==null||be.isRemoved())return null;
        if(be instanceof CondenserBlockEntity part)be=part.owner();
        else if(be instanceof CoolingBlockEntity part)be=part.owner();
        else if(be instanceof CondensateStorageTankBlockEntity part)be=part.owner();
        else if(be instanceof SuppressionPoolPortBlockEntity part)be=part.owner();
        return typed(be);
    }
    private static IPeripheral typed(BlockEntity be) {
        if(be instanceof PowerModuleBlockEntity b)return new PowerModulePeripheral(b);
        if(be instanceof SlcTankBlockEntity b)return new HardwarePeripheral("bwr_slc_tank",()->Map.of(
                "connected",b.ready(),"solutionKg",b.solution().massKg(),"borateKg",b.solution().borateKg(),"boronPpm",b.solution().boronFraction()*1e6,"temperatureC",b.solution().temperatureC(),"capacityKg",dev.bwr.core.eccs.BoronSolution.CAPACITY_KG));
        if(be instanceof dev.bwr.mod.water.WaterDischargeBlockEntity b)return new WaterDischargePeripheral(b);
        if(be instanceof EccsPumpBlockEntity b)return new EccsPumpPeripheral(b);
        if(be instanceof FeedwaterPumpBlockEntity b)return new FeedwaterPumpPeripheral(b);
        if(be instanceof RecirculationPumpBlockEntity b)return new RecirculationPumpPeripheral(b);
        if(be instanceof ReactorControllerBlockEntity b)return new ReactorPeripheral(b);
        if(be instanceof SuppressionPoolBlockEntity b)return new SuppressionPoolPeripheral(b);
        if(be instanceof SuppressionPoolPortBlockEntity)return new SuppressionPoolPeripheral(null);
        if(be instanceof TurbineSteamOutletBlockEntity b)return new TurbineSteamOutletPeripheral(b);
        if(be instanceof AdsControllerBlockEntity b)return new AdsPeripheral(b);
        if(be instanceof CondensateStorageTankBlockEntity b)return new CondensateStorageTankPeripheral(b);
        if(be instanceof SafetyReliefValveBlockEntity b)return new SafetyReliefValvePeripheral(b);
        if(be instanceof MainSteamIsolationValveBlockEntity b)return new MainSteamIsolationValvePeripheral(b);
        if(be instanceof TurbineValveBlockEntity b)return new TurbineValvePeripheral(b);
        if(be instanceof CoolingBlockEntity b)return new CoolingPeripheral(b);
        if(be instanceof CondenserBlockEntity b)return new CondenserPeripheral(b);
        if(be instanceof RhrHeatExchangerBlockEntity b)return new HeatExchangerPeripheral(b);
        if(be instanceof RpvSteamOutletBlockEntity b)return new RpvSteamOutletPeripheral(b);
        if(be instanceof RpvWaterInjectionPortBlockEntity b)return new HardwarePeripheral("bwr_rpv_water_port",()->{
            var c=b.controller();var m=new LinkedHashMap<String,Object>();m.put("connected",c!=null&&c.isFormed());
            if(c!=null&&c.isFormed()){m.put("pressurePsig",c.core().getPressurePsig());m.put("waterC",c.core().getCoolantTemperatureC());}return m;
        });
        if(be instanceof dev.bwr.mod.fuel.FuelFabricatorBlockEntity b)return new HardwarePeripheral("bwr_fuel_fabricator",()->Map.of(
                "batchHeavyMetalKg",b.batchHeavyMetalKg(),"enrichment",b.batchEnrichmentWeightFraction(),"energyFE",b.energy().getEnergyStored(),"outputCount",b.output().getStackInSlot(0).getCount()));
        if(be instanceof dev.bwr.mod.rods.ControlRodDriveBlockEntity b)return new HardwarePeripheral("bwr_control_rod_drive",()->Map.of(
                "attached",b.isAttached(),"rodIndex",b.rodIndex(),"notch",b.notchLabel(),"accumulatorCharge",b.accumulatorCharge(),"energyFE",b.energyStoredFe(),"waterKg",b.waterStoredMb(),"health",b.health()));
        return null;
    }
    private static IPeripheral jet(Level level,BlockPos pos,BlockState state) {
        return new HardwarePeripheral("bwr_jet_pump",()->Map.of("passive",true,"size",state.getValue(JetPumpBlock.SIZE).getSerializedName(),
                "complete",level!=null&&((JetPumpBlock)state.getBlock()).complete(level,((JetPumpBlock)state.getBlock()).origin(pos,state),state)));
    }
}
