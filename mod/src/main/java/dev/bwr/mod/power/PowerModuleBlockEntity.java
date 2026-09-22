package dev.bwr.mod.power;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.turbine.*;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** One inventory and one simulation per module; all child cells forward to this entity. */
public class PowerModuleBlockEntity extends BlockEntity {
    public static final int ENERGY_CAPACITY=2_000_000_000, WATER_CAPACITY=20_000;
    final SteamInventory inlet=new SteamInventory(200), exhaust=new SteamInventory(400);
    long lastTick=Long.MIN_VALUE;
    boolean hasWorkLoad;
    private long exhaustLedgerTick=Long.MIN_VALUE;
    private double exhaustOfferedKg;
    private final Map<BlockPos,Double> exhaustClaims=new HashMap<>();
    public boolean running;
    public double rpm,flowKgPerS,shaftMW,electricMW,trainMW,inletPsia,outletPsia,inletC,outletC,rejectedMW;
    public int hpCount,lpCount,generatorCount;
    public String status="Stopped";
    private double energyFe,waterKg;
    public PowerModuleBlockEntity(BlockPos pos,BlockState state) { super(BwrBlockEntities.POWER_MODULE.get(),pos,state); }
    public PowerModuleBlock block() { return (PowerModuleBlock)getBlockState().getBlock(); }
    public double energyStored() { return energyFe; }
    public double waterStored() { return waterKg; }
    public double inletMass() { return inlet.mass(); }
    public double exhaustMass() { return exhaust.mass(); }
    /** One compact condenser seats six blocks directly below a matching LP foundation. */
    public dev.bwr.mod.condenser.CondenserBlockEntity condenser(){
        if(level==null||block().generator()||block().highPressure())return null;
        var p=worldPosition.below(6);
        return level.isLoaded(p)&&level.getBlockEntity(p) instanceof dev.bwr.mod.condenser.CondenserBlockEntity c
                &&c.owner()==c&&c.layout()==dev.bwr.mod.condenser.CondenserLayout.INSTANCE
                &&c.getBlockState().getValue(dev.bwr.mod.condenser.CondenserBlock.FACING)==getBlockState().getValue(PumpAssemblyBlock.FACING)
                &&c.ready()?c:null;
    }
    private double outletRoom(){var c=condenser();return block().highPressure()?exhaust.space():c==null?0:c.plant().steam.space();}
    public static void serverTick(Level level,BlockPos pos,BlockState state,PowerModuleBlockEntity be) { PowerTrain.tick(be); }
    void resetReadouts() { running=false;flowKgPerS=shaftMW=electricMW=trainMW=rejectedMW=0;inletPsia=outletPsia=inletC=outletC=0; }
    double headerDemandKg() {
        if(!hasWorkLoad||block().generator())return 0;
        double room=outletRoom();
        double rate=(block().highPressure()?2200:750)*(.1+.9*Math.pow(rpm/1500,2))/20;
        return Math.max(0,Math.min(room,rate)-inlet.mass());
    }
    private double exhaustOffered() {
        long now=level.getGameTime();
        if(exhaustLedgerTick!=now){exhaustLedgerTick=now;exhaustOfferedKg=exhaust.mass();exhaustClaims.clear();}
        return exhaustOfferedKg;
    }
    private SteamInventory.Packet claimExhaust(BlockPos consumer,double wanted,double share) {
        exhaustOffered();
        double already=exhaustClaims.getOrDefault(consumer,0.0);
        var packet=exhaust.take(Math.min(wanted,Math.max(0,exhaustOfferedKg*share-already)));
        exhaustClaims.put(consumer,already+packet.mass());setChanged();return packet;
    }
    double workRoomKJ() {
        return Math.max(0,Math.min(PowerTurbine.GENERATOR_RATING_W/20/1000,
                (ENERGY_CAPACITY-energyFe)*EccsPower.WATTS_PER_FE_PER_TICK/20/1000))/PowerTurbine.GENERATOR_EFFICIENCY;
    }
    void generate(double workKJ) {
        double watts=workKJ*20*1000*PowerTurbine.GENERATOR_EFFICIENCY;
        energyFe=Math.min(ENERGY_CAPACITY,energyFe+watts/EccsPower.WATTS_PER_FE_PER_TICK);
        electricMW=watts/1e6;status=watts>0?"Generating":energyFe>=ENERGY_CAPACITY-.001?"Energy buffer full":"No turbine work";
    }
    double expand(double workBudgetKJ) {
        if(workBudgetKJ<=0)return 0;
        var stage=block().highPressure()?PowerTurbine.Stage.HP:PowerTurbine.Stage.LP;
        var condenser=condenser();
        double room=outletRoom();
        if(room<1e-9){status=block().highPressure()?"HP exhaust buffer full":condenser==null?"Fit a condenser six blocks below, same facing":"Condenser steam buffer full";return 0;}
        double rate=(block().highPressure()?2200:750)*(.1+.9*Math.pow(rpm/1500,2));
        double wanted=Math.min(rate/20,room);
        var sources=PowerSteamNetwork.sources(this);
        // Rotate the first source each tick when several HPs or nozzles share a header.
        for(int i=0;i<sources.size();i++) {
            var source=sources.get((i+(int)Math.floorMod(level.getGameTime(),sources.size()))%sources.size());
            double need=Math.min(inlet.space(),Math.max(0,wanted-inlet.mass()));
            if(need<=0)break;
            if(source.entity() instanceof PowerModuleBlockEntity hp) {
                double raw=hp.exhaustOffered();
                var packet=hp.claimExhaust(worldPosition,source.limit(Math.min(need,raw*source.share()*source.opening()),raw),source.share());
                inlet.offer(packet);source.record(packet.mass());
            } else if(source.entity() instanceof RpvSteamOutletBlockEntity nozzle) {
                var pos=nozzle.getControllerPos();
                if(pos!=null&&level.isLoaded(pos)&&level.getBlockEntity(pos) instanceof ReactorControllerBlockEntity c&&c.isFormed()) {
                    double pressure=Saturation.psiaFromPsig(c.core().getPressurePsig());
                    // The vessel already applied its downstream valve opening. Do not square it here.
                    double raw=nozzle.getLineOpenFraction()>0?nozzle.getLastFlowKgPerS()/nozzle.getLineOpenFraction()/20:0;
                    double limit=source.limit(Math.min(need,Math.min(nozzle.getLastFlowKgPerS()/20,raw*source.opening())*source.share()),raw);
                    double mass=nozzle.claimFlowKgPerS(level.getGameTime(),limit*20)/20;source.record(mass);
                    inlet.offer(new SteamInventory.Packet(mass,Saturation.vapourEnthalpyKJPerKg(pressure),pressure));
                }
            }
        }
        inletPsia=inlet.pressure();inletC=inlet.mass()>0?Saturation.temperatureCelsiusFromPsia(inletPsia):0;
        var result=PowerTurbine.expand(stage,inlet,stage==PowerTurbine.Stage.HP?exhaust:condenser.plant().steam,wanted,workBudgetKJ);
        if(stage==PowerTurbine.Stage.HP && exhaustLedgerTick==level.getGameTime())exhaustOfferedKg+=result.mass();
        if(stage==PowerTurbine.Stage.LP&&result.mass()>0)condenser.setChanged();
        flowKgPerS=result.mass()*20;shaftMW=result.workKJ()*20/1000;
        outletPsia=result.outletPressure();outletC=result.mass()>0?Saturation.temperatureCelsiusFromPsia(outletPsia):0;
        rejectedMW=result.rejectedHeatKJ()*20/1000;
        running=flowKgPerS>0;
        status=running?"Steam flowing":sources.isEmpty()?"Steam path closed or disconnected":"Waiting for steam";
        return result.workKJ();
    }
    public final IEnergyStorage energy=new IEnergyStorage() {
        public int receiveEnergy(int max,boolean simulate){return 0;}
        public int extractEnergy(int max,boolean simulate){int n=Math.min(Math.max(0,max),getEnergyStored());if(!simulate&&n>0){energyFe-=n;setChanged();}return n;}
        public int getEnergyStored(){return (int)Math.floor(energyFe);}
        public int getMaxEnergyStored(){return ENERGY_CAPACITY;}
        public boolean canExtract(){return true;}
        public boolean canReceive(){return false;}
    };
    void pushOutputs() {
        var s=getBlockState();
        if(block().generator()) {
            var face=block().terminalFace(s);var next=block().terminal(worldPosition,s).relative(face);
            if(level.isLoaded(next)){var target=level.getCapability(Capabilities.EnergyStorage.BLOCK,next,face.getOpposite());
                if(target!=null&&target.canReceive()){int n=target.receiveEnergy(energy.getEnergyStored(),false);energy.extractEnergy(n,false);}}
        } else if(!block().highPressure()&&waterKg>0) {
            var c=condenser();if(c!=null){double moved=c.plant().acceptLegacyCondensate(waterKg);waterKg-=moved;if(moved>0){setChanged();c.setChanged();}}
        }
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider registries) {
        super.saveAdditional(t,registries);t.putDouble("RPM",rpm);
        t.putDouble("Energy",energyFe);t.putDouble("Water",waterKg);
        saveSteam(t,"Inlet",inlet);saveSteam(t,"Exhaust",exhaust);
    }
    private static void saveSteam(CompoundTag t,String key,SteamInventory s){var p=new CompoundTag();p.putDouble("Mass",s.mass());p.putDouble("Enthalpy",s.enthalpy());p.putDouble("Pressure",s.pressure());t.put(key,p);}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider registries) {
        super.loadAdditional(t,registries);running=false; // Legacy local Running/Valve commands are superseded by physical steam valves.
        rpm=finite(t.getDouble("RPM"),1500);energyFe=finite(t.getDouble("Energy"),ENERGY_CAPACITY);waterKg=finite(t.getDouble("Water"),WATER_CAPACITY);
        loadSteam(t,"Inlet",inlet);loadSteam(t,"Exhaust",exhaust);lastTick=Long.MIN_VALUE;exhaustLedgerTick=Long.MIN_VALUE;exhaustClaims.clear();hasWorkLoad=false;
    }
    private static void loadSteam(CompoundTag t,String key,SteamInventory s){var p=t.getCompound(key);s.restore(p.getDouble("Mass"),p.getDouble("Enthalpy"),p.getDouble("Pressure"));}
    private static double finite(double v,double max){return Double.isFinite(v)?Math.max(0,Math.min(max,v)):0;}
}
