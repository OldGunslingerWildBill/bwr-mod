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
    /** Liquid in the steam this module was fed on its last working tick, percent of its mass: carryover from the vessel's separators. */
    public double inletMoisturePercent;
    public int hpCount,lpCount,generatorCount;
    public String status="Stopped";
    private double energyFe,waterKg;
    private double damage;
    private UUID deviceId=UUID.randomUUID();
    private boolean identityReady;
    private final dev.bwr.core.grid.EnergyLedger delivered=new dev.bwr.core.grid.EnergyLedger();
    private final dev.bwr.core.grid.EnergyLedger generated=new dev.bwr.core.grid.EnergyLedger();
    private long accountingTick=Long.MIN_VALUE;
    private double startingFE,generatedFE,generatedCarry,lastGeneratedFE,lastBufferDelta,reservedFE;
    public double trainLossKJ;
    public PowerModuleBlockEntity(BlockPos pos,BlockState state) { super(BwrBlockEntities.POWER_MODULE.get(),pos,state); }
    public PowerModuleBlock block() { return (PowerModuleBlock)getBlockState().getBlock(); }
    public double energyStored() { return energyFe; }
    public double waterStored() { return waterKg; }
    public double inletMass() { return inlet.mass(); }
    public double exhaustMass() { return exhaust.mass(); }
    public double shaftInertia() { return ShaftDynamics.REFERENCE_INERTIA*(block().generator()?.4:.1); }
    public double kineticEnergyKJ() { return ShaftDynamics.energyKJ(shaftInertia(),rpm); }
    public UUID deviceId(){return deviceId;}
    boolean identityReady(){return identityReady;}
    void establishIdentity(){identityReady=true;}
    public dev.bwr.core.grid.EnergyLedger delivered(){return delivered;}
    public double generatedMW1s(){return dev.bwr.core.grid.EnergyUnits.mw(generated.averageFE(20));}
    public double generatedFELastTick(){return lastGeneratedFE;}
    public double bufferDeltaFELastTick(){return lastBufferDelta;}
    public double damage() { return damage; }
    public boolean live(){return level!=null&&!isRemoved()&&dev.bwr.mod.world.LoadedWorld.blockEntity(level, worldPosition)==this;}
    void beginAccounting(){
        long now=level.getGameTime();if(now==accountingTick)return;
        if(accountingTick!=Long.MIN_VALUE){
            lastGeneratedFE=now==accountingTick+1?generatedFE:0;
            lastBufferDelta=now==accountingTick+1?energyFe-startingFE:0;
        }
        delivered.beginTick(now);generated.beginTick(now);
        accountingTick=now;startingFE=energyFe;generatedFE=0;
    }
    private void recordDelivery(int n){
        delivered.record(n);setChanged();
        dev.bwr.mod.advancement.PlantMilestones.energyDelivered(this,n);
    }
    public CompoundTag retainedCondition(){
        var t=new CompoundTag();t.putDouble("ShaftDamage",damage);
        t.putLong("DeliveredFE",delivered.totalFE());t.putBoolean("CounterSaturated",delivered.saturated());return t;
    }
    public void restoreCondition(CompoundTag t){
        damage=finite(t.getDouble("ShaftDamage"),1);
        delivered.restore(t.getLong("DeliveredFE"),t.getBoolean("CounterSaturated"));setChanged();
    }
    public boolean failed() { return damage>=1; }
    public double speedPercent() { return rpm/ShaftDynamics.RATED_RPM*100; }
    public boolean overspeed() { return rpm>ShaftDynamics.OVERSPEED_RPM; }
    public void addShaftDamage(double amount) {
        if(Double.isFinite(amount)&&amount>0){damage=Math.min(1,damage+amount);setChanged();}
    }
    /** One compact condenser seats six blocks directly below a matching LP foundation. */
    public dev.bwr.mod.condenser.CondenserBlockEntity condenser(){
        if(level==null||block().generator()||block().highPressure())return null;
        var p=worldPosition.below(6);
        return dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof dev.bwr.mod.condenser.CondenserBlockEntity c
                &&c.owner()==c&&c.layout()!=dev.bwr.mod.condenser.CondenserLayout.LEGACY
                &&c.getBlockState().getValue(dev.bwr.mod.condenser.CondenserBlock.FACING)==c.layout().facingForTurbine(getBlockState().getValue(PumpAssemblyBlock.FACING))
                &&c.ready()?c:null;
    }
    private double outletRoom(){var c=condenser();return block().highPressure()?exhaust.space():c==null?0:c.plant().steam.space();}
    public static void serverTick(Level level,BlockPos pos,BlockState state,PowerModuleBlockEntity be) { PowerTrain.tick(be); }
    void resetReadouts() { running=false;flowKgPerS=shaftMW=electricMW=trainMW=rejectedMW=trainLossKJ=inletMoisturePercent=0;inletPsia=outletPsia=inletC=outletC=0; }
    double headerDemandKg() {
        if(!hasWorkLoad||block().generator())return 0;
        double room=outletRoom();
        double rate=admissionCapacityKgPerS()/20;
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
        if(failed())return 0;
        // Passive generator characteristic, not a speed controller: available
        // conversion rises with shaft speed; zero speed cannot produce power.
        double speedFactor=Math.min(1,Math.pow(rpm/ShaftDynamics.RATED_RPM,2));
        return Math.max(0,Math.min(PowerTurbine.GENERATOR_RATING_W*speedFactor/20/1000,
                (ENERGY_CAPACITY-energyFe)*EccsPower.WATTS_PER_FE_PER_TICK/20/1000))/PowerTurbine.GENERATOR_EFFICIENCY;
    }
    void generate(double workKJ) {
        double watts=workKJ*20*1000*PowerTurbine.GENERATOR_EFFICIENCY;
        beginAccounting();double before=energyFe;
        energyFe=Math.min(ENERGY_CAPACITY,energyFe+watts/EccsPower.WATTS_PER_FE_PER_TICK);
        double created=energyFe-before;generatedFE+=created;
        double whole=created+generatedCarry;long counted=(long)Math.floor(whole);
        generatedCarry=whole-counted;generated.record(counted);
        electricMW=watts/1e6;running=watts>0; // a generator runs when it is producing power; it has no steam flow of its own
        status=watts>0?"Generating":energyFe>=ENERGY_CAPACITY-.001?"Energy buffer full":"No turbine work";
    }
    double expand(double workBudgetKJ) {
        if(workBudgetKJ<=0)return 0;
        var stage=block().highPressure()?PowerTurbine.Stage.HP:PowerTurbine.Stage.LP;
        var condenser=condenser();
        double room=outletRoom();
        if(room<1e-9){status=block().highPressure()?"HP exhaust buffer full":condenser==null?"Fit a condenser six blocks below, same facing":"Condenser steam buffer full";return 0;}
        double rate=admissionCapacityKgPerS();
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
                if(pos!=null&&dev.bwr.mod.world.LoadedWorld.blockEntity(level, pos) instanceof ReactorControllerBlockEntity c&&c.isFormed()) {
                    double pressure=Saturation.psiaFromPsig(c.core().getPressurePsig());
                    // The vessel already applied its downstream valve opening. Do not square it here.
                    double raw=nozzle.getLineOpenFraction()>0?nozzle.getLastFlowKgPerS()/nozzle.getLineOpenFraction()/20:0;
                    double limit=source.limit(Math.min(need,Math.min(nozzle.getLastFlowKgPerS()/20,raw*source.opening())*source.share()),raw);
                    double mass=nozzle.claimFlowKgPerS(level.getGameTime(),limit*20)/20;source.record(mass);
                    // The steam is as wet as the vessel's separators leave it (VESSEL-HEIGHT.md). Only the moisture over the
                    // design 0.1 % lowers the enthalpy: the rated steam already contains that.
                    double moisture=c.core().getSteamMoistureFraction();
                    double wet=Math.max(0,moisture-PowerTurbine.DESIGN_MOISTURE);
                    double enthalpy=Saturation.vapourEnthalpyKJPerKg(pressure)-wet*Saturation.latentHeatKJPerKg(pressure);
                    inlet.offer(new SteamInventory.Packet(mass,enthalpy,pressure,moisture));
                }
            }
        }
        inletPsia=inlet.pressure();inletC=inlet.mass()>0?Saturation.temperatureCelsiusFromPsia(inletPsia):0;
        double inletMoisture=inlet.moisture();
        var result=PowerTurbine.expand(stage,inlet,stage==PowerTurbine.Stage.HP?exhaust:condenser.plant().steam,wanted,workBudgetKJ,stage==PowerTurbine.Stage.HP?PowerTurbine.outletPressure(stage,inlet.pressure()):condenser.plant().backpressurePsia());
        if(stage==PowerTurbine.Stage.HP && exhaustLedgerTick==level.getGameTime())exhaustOfferedKg+=result.mass();
        if(stage==PowerTurbine.Stage.LP&&result.mass()>0)condenser.setChanged();
        flowKgPerS=result.mass()*20;shaftMW=result.workKJ()*20/1000;
        if(result.mass()>0){
            inletMoisturePercent=inletMoisture*100;
            // Blade wear is the turbine's existing damage (the one overspeed adds to), not a second counter.
            addShaftDamage(PowerTurbine.erosionIncrement(result.mass(),inletMoisture));
        }
        outletPsia=result.outletPressure();outletC=result.mass()>0?Saturation.temperatureCelsiusFromPsia(outletPsia):0;
        rejectedMW=result.rejectedHeatKJ()*20/1000;
        running=flowKgPerS>0;
        status=running?"Steam flowing":sources.isEmpty()?"Steam path closed or disconnected":"Waiting for steam";
        return result.workKJ();
    }
    // Valve opening and pressure determine admission. Keep the existing rated
    // capacities as game calibration; remove the former RPM-dependent demand
    // multiplier which created positive-feedback steam admission on overspeed.
    private double admissionCapacityKgPerS(){return block().highPressure()?2200:750;}
    public final IEnergyStorage energy=new IEnergyStorage() {
        public int receiveEnergy(int max,boolean simulate){return 0;}
        public int extractEnergy(int max,boolean simulate){
            int n=Math.min(Math.max(0,max),getEnergyStored());
            if(!simulate&&n>0){beginAccounting();energyFe-=n;recordDelivery(n);}return n;
        }
        public int getEnergyStored(){return live()?(int)Math.floor(Math.max(0,energyFe-reservedFE)):0;}
        public int getMaxEnergyStored(){return ENERGY_CAPACITY;}
        public boolean canExtract(){return true;}
        public boolean canReceive(){return false;}
    };
    void pushOutputs() {
        var s=getBlockState();
        if(block().generator()) {
            var face=block().terminalFace(s);var next=block().terminal(worldPosition,s).relative(face);
            if(level.isLoaded(next)){var target=level.getCapability(Capabilities.EnergyStorage.BLOCK,next,face.getOpposite());
                if(target!=null&&target.canReceive())pushEnergy(target);}
        } else if(!block().highPressure()&&waterKg>0) {
            var c=condenser();if(c!=null){double moved=c.plant().acceptLegacyCondensate(waterKg);waterKg-=moved;if(moved>0){setChanged();c.setChanged();}}
        }
    }
    /** Shared by the real terminal push and the reentrant-receiver regression. */
    int pushEnergy(IEnergyStorage target) {
        if(!live())return 0;
        beginAccounting();int offered=energy.getEnergyStored();reservedFE+=offered;
        try {
            int n=Math.max(0,Math.min(offered,target.receiveEnergy(offered,false)));
            if(n>0){energyFe-=n;recordDelivery(n);}return n;
        } finally {reservedFE-=offered;}
    }
    @Override public void onLoad() { super.onLoad(); dev.bwr.mod.advancement.PlantMilestones.loaded(this); }
    @Override public void setRemoved() { dev.bwr.mod.advancement.PlantMilestones.unloaded(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { dev.bwr.mod.advancement.PlantMilestones.unloaded(this); super.onChunkUnloaded(); }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider registries) {
        super.saveAdditional(t,registries);t.putDouble("RPM",rpm);
        identityReady=true;
        t.putInt("ShaftVersion",1);t.putDouble("ShaftDamage",damage);
        t.putInt("GridVersion",1);t.putUUID("DeviceId",deviceId);
        t.putLong("DeliveredFE",delivered.totalFE());t.putBoolean("CounterSaturated",delivered.saturated());
        t.putDouble("Energy",energyFe);t.putDouble("Water",waterKg);
        saveSteam(t,"Inlet",inlet);saveSteam(t,"Exhaust",exhaust);
    }
    private static void saveSteam(CompoundTag t,String key,SteamInventory s){var p=new CompoundTag();p.putDouble("Mass",s.mass());p.putDouble("Enthalpy",s.enthalpy());p.putDouble("Pressure",s.pressure());if(s.moisture()>0)p.putDouble("Moisture",s.moisture());t.put(key,p);}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider registries) {
        super.loadAdditional(t,registries);running=false; // Legacy local Running/Valve commands are superseded by physical steam valves.
        // Numerical corruption bound, not a rated-speed clamp or operating limit.
        rpm=finite(t.getDouble("RPM"),1e6);damage=finite(t.getDouble("ShaftDamage"),1);trainLossKJ=0;
        if(t.hasUUID("DeviceId"))deviceId=t.getUUID("DeviceId");
        identityReady=true;
        delivered.restore(t.getLong("DeliveredFE"),t.getBoolean("CounterSaturated"));generated.restore(0,false);
        accountingTick=Long.MIN_VALUE;startingFE=generatedFE=generatedCarry=lastGeneratedFE=lastBufferDelta=reservedFE=0;
        energyFe=finite(t.getDouble("Energy"),ENERGY_CAPACITY);waterKg=finite(t.getDouble("Water"),WATER_CAPACITY);
        loadSteam(t,"Inlet",inlet);loadSteam(t,"Exhaust",exhaust);lastTick=Long.MIN_VALUE;exhaustLedgerTick=Long.MIN_VALUE;exhaustClaims.clear();hasWorkLoad=false;
    }
    private static void loadSteam(CompoundTag t,String key,SteamInventory s){var p=t.getCompound(key);s.restore(p.getDouble("Mass"),p.getDouble("Enthalpy"),p.getDouble("Pressure"),p.getDouble("Moisture"));}
    private static double finite(double v,double max){return Double.isFinite(v)?Math.max(0,Math.min(max,v)):0;}
}
