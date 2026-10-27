package dev.bwr.mod.grid;

import dev.bwr.core.grid.*;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** External load with a player-operated export breaker, no electrical inventory. */
public final class GridTieBlockEntity extends GridDevice {
    private GridDemand settings=new GridDemand(0,0,0,1050,1500,24000);
    private DeviceReference generator;
    private final java.util.List<DeviceReference> meters=new java.util.ArrayList<>();
    private final DemandBudget budget=new DemandBudget();
    private long tick=Long.MIN_VALUE,curvePhase;
    private boolean closed,hasClose,lastCloseFault;
    private double phase,lastAngle,lastSlip,demand;
    private final double[] requested=new double[20];
    private int requestCursor;
    public GridTieBlockEntity(BlockPos pos,BlockState state){super(BwrBlockEntities.GRID_TIE.get(),pos,state);}
    public PowerModuleBlockEntity generator(){
        if(generator==null)return null;
        return generator.resolve(level) instanceof PowerModuleBlockEntity g&&g.deviceId().equals(generator.id())&&g.live()
                &&g.block().generator()&&g.block().complete(level,g.getBlockPos(),g.getBlockState())?g:null;
    }
    public DeviceReference binding(){return generator;}
    public java.util.List<DeviceReference> meters(){return java.util.List.copyOf(meters);}
    public FeMeterBlockEntity meter(DeviceReference ref){return ref.resolve(level) instanceof FeMeterBlockEntity m&&m.live()&&m.deviceId().equals(ref.id())?m:null;}
    public boolean setMeters(java.util.List<BlockPos> positions){
        if(!live()||positions==null||positions.size()>32)return false;
        var next=new java.util.ArrayList<DeviceReference>();var ids=new java.util.HashSet<java.util.UUID>();
        for(var pos:positions){
            if(pos==null||!(dev.bwr.mod.world.LoadedWorld.blockEntity(level, pos) instanceof FeMeterBlockEntity m)||!m.live()||!ids.add(m.deviceId()))return false;
            next.add(new DeviceReference(level.dimension().location().toString(),pos,m.deviceId()));
        }
        meters.clear();meters.addAll(next);setChanged();return true;
    }
    public boolean toggleMeter(FeMeterBlockEntity meter){
        if(!live()||meter==null||!meter.live()||meter.getLevel()!=level)return false;
        if(meters.removeIf(r->r.id().equals(meter.deviceId()))){setChanged();return true;}
        if(meters.size()>=32)return false;meters.add(new DeviceReference(level.dimension().location().toString(),meter.getBlockPos(),meter.deviceId()));setChanged();return true;
    }
    public boolean sampleValid(GridDevice d){return d!=null&&d.live()&&d.ledger().sampleTicks()>=20&&d.ledger().completedTick()==level.getGameTime()-1;}
    public int validMeterCount(){int n=0;for(var r:meters)if(sampleValid(meter(r)))n++;return n;}
    public boolean aggregateValid(){var g=generator();return sampleValid(this)&&g!=null&&g.delivered().sampleTicks()>=20&&g.delivered().completedTick()==ledger.completedTick()&&validMeterCount()==meters.size();}
    public double observedBaseMW(){if(!aggregateValid())return Double.NaN;double sum=0;for(var r:meters)sum+=EnergyUnits.mw(meter(r).ledger().averageFE(20));return sum;}
    public double requestedTotalMW(){return observedBaseMW()+demandMW1s();}
    public double mwError(){var g=generator();return g==null?Double.NaN:requestedTotalMW()-g.generatedMW1s();}
    public boolean closed(){return closed;}
    public boolean exportEnabled(){return closed&&generator()!=null;}
    public boolean syncValid(){return !closed&&generator()!=null;}
    public double phase(){return phase;}
    public double slip(){var g=generator();return g==null?Double.NaN:Synchronizer.slip(g.rpm);}
    public boolean withinWindow(){return syncValid()&&Synchronizer.withinWindow(phase,slip());}
    public long curvePhase(){return curvePhase;}
    public double demandMW(){return demand;}
    public double demandMW1s(){double sum=0;for(double n:requested)sum+=n;return sum/20;}
    public boolean hasClose(){return hasClose;}
    public double lastAngle(){return lastAngle;}
    public double lastSlip(){return lastSlip;}
    public boolean lastCloseFault(){return lastCloseFault;}
    public GridDemand settings(){
        double max=GridConfig.maximumMW();
        return new GridDemand(settings.mode(),Math.min(max,settings.fixedMW()),Math.min(max,settings.remoteMW()),
                Math.min(max,settings.minimumMW()),Math.min(max,settings.maximumMW()),settings.periodTicks());
    }
    public boolean configure(GridDemand next){
        if(next==null)return false;
        double max=GridConfig.maximumMW();
        if(next.fixedMW()>max||next.remoteMW()>max||next.minimumMW()>max||next.maximumMW()>max)return false;
        if(live())tickDevice();settings=next;setChanged();return true;
    }
    public void resetCurve(){if(live())tickDevice();curvePhase=0;setChanged();}
    public boolean bindGenerator(BlockPos pos){
        if(!live()||closed||!level.isLoaded(pos))return false;
        var be=dev.bwr.mod.signal.DeviceResolver.resolve(level,pos);
        if(!(be instanceof PowerModuleBlockEntity g)||!g.block().generator()||!g.live()||!g.block().complete(level,g.getBlockPos(),g.getBlockState()))return false;
        tickDevice();generator=new DeviceReference(level.dimension().location().toString(),g.getBlockPos(),g.deviceId());setChanged();return true;
    }
    public boolean clearGenerator(){if(closed)return false;if(live())tickDevice();generator=null;setChanged();return true;}
    public boolean closeBreaker(){
        if(!live())return false;tickDevice();if(closed)return true;
        var g=generator();if(g==null)return false;
        lastAngle=phase;lastSlip=Synchronizer.slip(g.rpm);hasClose=true;
        double damage=Synchronizer.closingDamage(lastAngle,lastSlip);lastCloseFault=damage>0;
        g.addShaftDamage(damage);
        // The same transient goes through the turbine sections of the train (TURBINE-MAINTENANCE.md); the generator's damage stays the law above.
        dev.bwr.mod.power.PowerTrain.closingShock(g,lastAngle,lastSlip);
        closed=true;setChanged();g.markGridHeld(level.getGameTime());return true;
    }
    public void tripBreaker(){if(live())tickDevice();if(closed){closed=false;phase=0;setChanged();}}
    @Override public void tickDevice(){
        if(!live())return;long now=level.getGameTime();if(now==tick)return;
        if(tick!=Long.MIN_VALUE){
            requested[requestCursor]=demand;requestCursor=(requestCursor+1)%20;
            for(long n=1;n<Math.min(now>tick?now-tick:1,21);n++){requested[requestCursor]=0;requestCursor=(requestCursor+1)%20;}
            curvePhase=(curvePhase+1)%settings.periodTicks();
            var g=generator();if(!closed&&g!=null)phase=Synchronizer.advance(phase,g.rpm,.05);
            if(closed&&g!=null)g.markGridHeld(now);
        }
        settings=settings();curvePhase=Math.floorMod(curvePhase,settings.periodTicks());
        demand=settings.mw(curvePhase,GridConfig.maximumMW());ledger.beginTick(now);
        budget.beginTick(now,demand,exportEnabled());tick=now;setChanged();
    }
    public final IEnergyStorage energy=new IEnergyStorage(){
        public int receiveEnergy(int offered,boolean simulate){
            if(!live()||!exportEnabled()||offered<=0)return 0;
            long now=level.getGameTime();
            if(!simulate)tickDevice();
            // A simulation before the shared service computes the next sample locally.
            long previewPhase=tick==Long.MIN_VALUE?curvePhase:tick==now?curvePhase:(curvePhase+1)%settings.periodTicks();
            double preview=tick==now?demand:settings().mw(previewPhase,GridConfig.maximumMW());
            int n=budget.accept(now,preview,true,offered,simulate);
            if(!simulate&&n>0){ledger.record(n);setChanged();}return n;
        }
        public int extractEnergy(int n,boolean simulate){return 0;}
        public int getEnergyStored(){return 0;}public int getMaxEnergyStored(){return 0;}
        public boolean canReceive(){return live()&&exportEnabled();}public boolean canExtract(){return false;}
    };
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putBoolean("Closed",closed);if(generator!=null)t.put("Generator",generator.save());
        var list=new net.minecraft.nbt.ListTag();for(var meter:meters)list.add(meter.save());t.put("Meters",list);
        t.putInt("Mode",settings.mode());t.putDouble("FixedMW",settings.fixedMW());t.putDouble("RemoteMW",settings.remoteMW());
        t.putDouble("MinMW",settings.minimumMW());t.putDouble("MaxMW",settings.maximumMW());t.putLong("Period",settings.periodTicks());
        t.putLong("CurvePhase",curvePhase);t.putDouble("Carry",budget.carry());t.putDouble("Phase",phase);
        t.putBoolean("HasClose",hasClose);t.putDouble("LastAngle",lastAngle);t.putDouble("LastSlip",lastSlip);t.putBoolean("LastCloseFault",lastCloseFault);
    }
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);closed=t.getBoolean("Closed");generator=DeviceReference.load(t.getCompound("Generator"));
        meters.clear();var list=t.getList("Meters",10);var ids=new java.util.HashSet<java.util.UUID>();
        for(int i=0;i<Math.min(32,list.size());i++){var ref=DeviceReference.load(list.getCompound(i));if(ref!=null&&ids.add(ref.id()))meters.add(ref);}
        long period=Math.max(20,Math.min(1_728_000,t.contains("Period")?t.getLong("Period"):24000));
        double min=bounded(t.getDouble("MinMW"),48000),max=Math.max(min,bounded(t.getDouble("MaxMW"),48000));
        settings=new GridDemand(Math.max(0,Math.min(2,t.getInt("Mode"))),bounded(t.getDouble("FixedMW"),48000),bounded(t.getDouble("RemoteMW"),48000),min,max,period);
        curvePhase=Math.floorMod(t.getLong("CurvePhase"),period);budget.restore(t.getDouble("Carry"));
        phase=Double.isFinite(t.getDouble("Phase"))?Synchronizer.wrap(t.getDouble("Phase")):0;
        hasClose=t.getBoolean("HasClose");lastAngle=finite(t.getDouble("LastAngle"));lastSlip=finite(t.getDouble("LastSlip"));lastCloseFault=t.getBoolean("LastCloseFault");
        tick=Long.MIN_VALUE;demand=0;java.util.Arrays.fill(requested,0);requestCursor=0;
    }
    private static double finite(double n){return Double.isFinite(n)?n:0;}
    private static double bounded(double n,double max){return Math.max(0,Math.min(max,finite(n)));}
}
