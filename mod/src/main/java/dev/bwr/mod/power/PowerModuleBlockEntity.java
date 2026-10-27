package dev.bwr.mod.power;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.turbine.*;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
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
    // ---- turbine auxiliaries (TURBINE-AUX.md). Everything here is absent from an old save and written only when it is not its default.
    private final LinkedHashSet<BlockPos> auxLinks=new LinkedHashSet<>();
    private boolean auxRequired,turningGear;
    private double bow;
    /** Bearing metal, rotor metal and winding temperatures, C; the shaft vibration of the last tick, mm/s. */
    public double bearingC=dev.bwr.core.turbine.TurbineAux.AMBIENT_C,rotorC=dev.bwr.core.turbine.TurbineAux.AMBIENT_C,windingC=dev.bwr.core.turbine.TurbineAux.AMBIENT_C,vibration;
    private TurbineAuxTrain.Readout aux=new TurbineAuxTrain.Readout();
    // ---- LP blade sections, reverse power and the casing (TURBINE-MAINTENANCE.md). Written only when not the default, except an LP's Blades.
    private final double[] blades=new double[TurbineBlades.FLOWS];
    /** Bit per flow: a blade of that section has been thrown; a blade section lifted out of the casing. */
    private int bladesThrown,bladesOut;
    private boolean coverOff;
    /** The LP exhaust hood temperature, C; the reverse power the grid drives the train with this tick, MW. */
    public double hoodC=dev.bwr.core.turbine.TurbineAux.AMBIENT_C,reverseMW;
    public boolean motoring;
    private boolean hasCloseShock;
    private double closeAngle,closeTorquePu;
    long gridHeldTick=Long.MIN_VALUE;
    // ---- a wrecked casing and a fire (TURBINE-MAINTENANCE.md). Absent from an old save; written only when not the default.
    /** How badly a turbine section is wrecked: a blade torn out through its casing, a burst casing, a rotor half broken loose. */
    public static final int WRECK_NONE=0,WRECK_HOLED=1,WRECK_BURST=2,WRECK_SHAFT_OUT=3;
    private int wreck;
    /** The flow whose rotor half broke loose (with {@link #WRECK_SHAFT_OUT}): where the casing is blown out. */
    private int brokenFlow;
    /** This LP's own disc burst (not just a casing split by the train coming apart): its rotor carries the lost third's unbalance. */
    private boolean discBurst;
    /** Per flow, the release angle of the blade that tore out of the casing (radians in the plane of the wheel), NaN for none. */
    private final double[] holes={Double.NaN,Double.NaN};
    private TurbineFire.Kind fire=TurbineFire.Kind.NONE;
    /** What the fire has left to burn: the oil pool, L, or the hydrogen, kg. */
    private double fireFuel;
    private static final Map<Level,Set<BlockPos>> LP_SECTIONS=new WeakHashMap<>();
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
    /** The section's condition, 0..1: the shaft counter, and on an LP the worse of it and its blade sections. */
    public double damage() { return lp()?Math.max(damage,bladeMax()):damage; }
    /** The shaft, bearing and winding counter alone (overspeed, bearings, vibration, winding, a bad close). */
    public double shaftDamage() { return damage; }
    public boolean lp() { return getBlockState().getBlock() instanceof PowerModuleBlock b&&b.kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.LP_TURBINE; }
    // ---- LP blade sections (TURBINE-MAINTENANCE.md)
    /** The condition of one flow's bladed section, 0 new to 1 failed; 0 for a section that is out of the casing or a module that is not an LP. */
    public double blade(int flow){return lp()&&bladeIn(flow)?blades[flow]:0;}
    public double bladeMax(){double m=0;for(int f=0;f<blades.length;f++)m=Math.max(m,blade(f));return m;}
    public boolean bladeIn(int flow){return (bladesOut&(1<<flow))==0;}
    public boolean bladeThrown(int flow){return (bladesThrown&(1<<flow))!=0;}
    public int bladesThrownCount(){return Integer.bitCount(bladesThrown);}
    public boolean coverOff(){return coverOff&&lp();}
    public boolean allBladesIn(){return bladesOut==0;}
    public void addBladeDamage(int flow,double amount){
        if(lp()&&bladeIn(flow)&&Double.isFinite(amount)&&amount>0){blades[flow]=Math.min(1,blades[flow]+amount);setChanged();}
    }
    public void addBladeDamageAll(double amount){for(int f=0;f<blades.length;f++)addBladeDamage(f,amount);}
    /** The flow whose section is in worst condition (the first on a tie); -1 with none in. */
    public int worstFlow(){int w=-1;for(int f=0;f<blades.length;f++)if(bladeIn(f)&&(w<0||blades[f]>blades[w]))w=f;return w;}
    void markThrown(int flow){bladesThrown|=1<<flow;blades[flow]=1;setChanged();syncMaintenance();}
    /** The cover (the upper casing half) is lifted off by the crane, or set back. Only the turbine hall crane calls this. */
    public void setCoverOff(boolean off){
        if(!lp()||coverOff==off)return;coverOff=off;setChanged();
        refreshOpen();
        syncMaintenance();
    }
    /** The upper cells are open while the hood is off or the casing is burst (an HP's only by a burst); loaded cells only. */
    private void refreshOpen(){
        if(level!=null&&!level.isClientSide())PowerModuleBlock.setOpen(level,worldPosition,getBlockState(),coverOff()||wrecked());
    }
    // ---- a wrecked casing (TURBINE-MAINTENANCE.md)
    /** {@link #WRECK_NONE} to {@link #WRECK_SHAFT_OUT}; always none for a generator. */
    public int wreck(){return block().generator()?WRECK_NONE:wreck;}
    /** The casing is burst open or its rotor broke loose: it holds no steam and does no work until it is rebuilt. */
    public boolean wrecked(){return wreck()>=WRECK_BURST;}
    /** The rotor broke loose and its coupling sheared: the section is coupled to nothing. */
    public boolean sheared(){return wreck()==WRECK_SHAFT_OUT;}
    public boolean discBurst(){return discBurst&&lp();}
    /** The release angle of the blade torn out of flow {@code flow}'s casing, or NaN. */
    public double hole(int flow){return flow>=0&&flow<holes.length?holes[flow]:Double.NaN;}
    public boolean anyHole(){for(double h:holes)if(Double.isFinite(h))return true;return false;}
    /** Raises the wreck to at least {@code level} (it never lowers; only a rebuild or a new hood does). An HP is burst at worst. */
    void wreckAtLeast(int level){
        if(block().generator())return;
        int w=lp()?level:Math.min(level,WRECK_BURST);
        if(w<=wreck)return;
        wreck=w;setChanged();refreshOpen();syncMaintenance();
    }
    void markDiscBurst(){discBurst=true;setChanged();}
    /** The rotor half of {@code flow} broke loose: the coupling is sheared and the casing blown out at that end. */
    void shaftOut(int flow){if(!lp())return;brokenFlow=flow;wreckAtLeast(WRECK_SHAFT_OUT);}
    public int brokenFlow(){return brokenFlow;}
    /** A thrown blade tore out of the casing at this release angle. */
    void markHole(int flow,double angle){
        if(!lp()||!Double.isFinite(angle))return;
        holes[flow]=angle;setChanged();wreckAtLeast(WRECK_HOLED);syncMaintenance();
    }
    /** A good hood is back on (the crane's COVER ON): the holes a blade tore were patched with the casing open, and a holed casing is whole. */
    public void fitHood(){
        if(!lp())return;
        java.util.Arrays.fill(holes,Double.NaN);if(wreck==WRECK_HOLED)wreck=WRECK_NONE;setChanged();syncMaintenance();
    }
    /** Why the section cannot be rebuilt with a Turbine Rebuild Kit now, or null. */
    public net.minecraft.network.chat.Component rebuildProblem(){
        if(wreck()==WRECK_NONE&&damage<=0&&bow<=0)
            return BwrText.tr("status.bwr.turbine_rebuild.nothing","This section's shaft, bearings and casing are sound: there is nothing to rebuild.");
        if(burning())return BwrText.tr("status.bwr.turbine_rebuild.burning","The section is on fire: put it out first.");
        if(rpm>TurbineBlades.OPEN_MAX_RPM)
            return BwrText.tr("status.bwr.turbine_rebuild.turning","The shaft is turning (%s rpm): stop it first, with the turning gear off.",String.format(java.util.Locale.ROOT,"%.1f",rpm));
        for(var m:PowerTrain.members(this))if(m.block().generator()&&m.turningGear())
            return BwrText.tr("status.bwr.turbine_rebuild.turning_gear","The turning gear is engaged: switch it off on the generator's screen first.");
        if(lp()){
            if(!coverOff())return BwrText.tr("status.bwr.turbine_rebuild.cover_on","Take the hood off first (the crane's COVER OFF): an LP is rebuilt open, with its rotor out.");
            if(bladeIn(0)||bladeIn(1))
                return BwrText.tr("status.bwr.turbine_rebuild.rotor_in","Lift both blade sections out first (BLADES OUT): the bearings, couplings and glands are rebuilt with the rotor out.");
        }else{
            if(flowKgPerS>0||inlet.mass()>1)return BwrText.tr("status.bwr.turbine_rebuild.steam","Steam is in the casing: close the valves and let it clear first.");
            if(rotorC>TurbineBlades.OPEN_MAX_C)return BwrText.tr("status.bwr.turbine_rebuild.hot","The casing is too hot to work on: %s C, it must be at %s C or less.",
                    String.format(java.util.Locale.ROOT,"%.0f",rotorC),String.format(java.util.Locale.ROOT,"%.0f",TurbineBlades.OPEN_MAX_C));
        }
        return null;
    }
    /** Rebuilds the section with a kit (the caller checked {@link #rebuildProblem}): casing, bearings, couplings and glands as new; the blade sections are the crane's. */
    public void rebuild(){
        wreck=WRECK_NONE;discBurst=false;java.util.Arrays.fill(holes,Double.NaN);
        damage=0;bow=0;bearingC=TurbineAux.AMBIENT_C;
        setChanged();refreshOpen();syncMaintenance();
    }
    // ---- a fire (TURBINE-MAINTENANCE.md)
    public TurbineFire.Kind fire(){return fire;}
    public boolean burning(){return fire!=TurbineFire.Kind.NONE;}
    public double fireFuel(){return fireFuel;}
    /** Lights a fire, or adds fuel to the one of the same kind burning; an oil fire does not replace a hydrogen one. True if it is a new fire. */
    boolean ignite(TurbineFire.Kind kind,double fuel){
        if(kind==TurbineFire.Kind.NONE||!Double.isFinite(fuel)||fuel<=0)return false;
        boolean fresh=!burning();
        if(fresh||fire==kind){fire=kind;fireFuel+=fuel;setChanged();}
        return fresh;
    }
    void setFireFuel(double fuel){fireFuel=Math.max(0,fuel);setChanged();}
    /** The fire goes out: water reached it or its fuel ran out ({@link TurbineFires}); public for a check that stages a scene. */
    public void extinguish(){if(burning()){fire=TurbineFire.Kind.NONE;fireFuel=0;setChanged();}}
    /** A blade section lifted out by the crane: its condition (1 if it threw a blade), which this flow then no longer has. NaN if it is not there. */
    public double takeBladeSection(int flow){
        if(!lp()||!bladeIn(flow))return Double.NaN;
        double d=bladeThrown(flow)?1:blades[flow];
        bladesOut|=1<<flow;blades[flow]=0;bladesThrown&=~(1<<flow);setChanged();syncMaintenance();return d;
    }
    /** A blade section set into an empty flow by the crane, with its condition. */
    public boolean installBladeSection(int flow,double condition){
        if(!lp()||bladeIn(flow)||!Double.isFinite(condition))return false;
        bladesOut&=~(1<<flow);blades[flow]=Math.max(0,Math.min(1,condition));setChanged();syncMaintenance();return true;
    }
    public boolean hasCloseShock(){return hasCloseShock;}
    public double closeAngle(){return closeAngle;}
    public double closeTorquePu(){return closeTorquePu;}
    void recordClose(double angle,double pu){hasCloseShock=true;closeAngle=angle;closeTorquePu=pu;setChanged();}
    /** The breaker of a grid tie bound to this generator is closed this tick (the tie says so each tick it is closed). */
    public void markGridHeld(long now){gridHeldTick=now;}
    boolean gridHeld(long now){return gridHeldTick!=Long.MIN_VALUE&&gridHeldTick>=now-1&&gridHeldTick<=now;}
    /** Every loaded LP section of a level (the turbine hall crane's survey), server side. */
    public static synchronized Set<BlockPos> lpSections(Level level){var s=LP_SECTIONS.get(level);return s==null?Set.of():Set.copyOf(s);}
    private void rememberLp(){if(level!=null&&!level.isClientSide()&&!isRemoved()&&lp())synchronized(PowerModuleBlockEntity.class){LP_SECTIONS.computeIfAbsent(level,k->new HashSet<>()).add(worldPosition.immutable());}}
    private void forgetLp(){if(level!=null&&!level.isClientSide())synchronized(PowerModuleBlockEntity.class){var s=LP_SECTIONS.get(level);if(s!=null)s.remove(worldPosition);}}
    private void syncMaintenance(){if(level!=null&&!level.isClientSide())level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
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
        var t=new CompoundTag();t.putDouble("ShaftDamage",damage);if(auxRequired)t.putBoolean("AuxRequired",true);
        if(lp())saveBlades(t);
        saveWreck(t);
        t.putLong("DeliveredFE",delivered.totalFE());t.putBoolean("CounterSaturated",delivered.saturated());return t;
    }
    public void restoreCondition(CompoundTag t){
        damage=finite(t.getDouble("ShaftDamage"),1);auxRequired=t.getBoolean("AuxRequired");
        loadBlades(t);
        loadWreck(t);
        if((coverOff()||wrecked())&&level!=null&&!level.isClientSide())PowerModuleBlock.setOpen(level,worldPosition,getBlockState(),true);
        delivered.restore(t.getLong("DeliveredFE"),t.getBoolean("CounterSaturated"));setChanged();
    }
    public boolean failed() { return damage()>=1; }
    // ---- auxiliaries
    public TurbineAuxTrain.Readout aux(){return aux;}
    void setAux(TurbineAuxTrain.Readout r){aux=r;}
    /** Whether the player placed this module new, so its train needs the auxiliaries from the start (the server option turbineAuxRequired). */
    public boolean auxRequired(){return auxRequired;}
    public void markAuxRequired(){if(!auxRequired){auxRequired=true;setChanged();}}
    public boolean turningGear(){return turningGear;}
    public double bow(){return bow;}
    /** Switches the turning gear on a generator. On is refused above the gear's overrun speed; off always works. Nothing else ever calls this. */
    public boolean setTurningGear(boolean on){
        if(!block().generator())return false;
        if(on&&rpm>dev.bwr.core.turbine.TurbineAux.GEAR_MAX_RPM)return false;
        if(turningGear!=on){turningGear=on;setChanged();}
        return true;
    }
    void advanceRotor(double steamC){rotorC=dev.bwr.core.turbine.TurbineAux.rotorStepC(rotorC,steamC,TurbineAuxTrain.DT);}
    void advanceBow(double rpm,boolean rolling){bow=dev.bwr.core.turbine.TurbineAux.bowStep(bow,rotorC,rpm,rolling,TurbineAuxTrain.DT);}
    public boolean hasAux(BlockPos pos){return auxLinks.contains(pos);}
    void attachAux(BlockPos pos){if(auxLinks.add(pos.immutable()))setChanged();}
    void detachAux(BlockPos pos){if(auxLinks.remove(pos))setChanged();}
    /** The skids attached and loaded; a link whose skid is gone (or now serves another module) is dropped. */
    public List<TurbineAuxBlockEntity> auxEntities(){
        if(auxLinks.isEmpty()||level==null)return List.of();
        var out=new ArrayList<TurbineAuxBlockEntity>(auxLinks.size());
        for(var it=auxLinks.iterator();it.hasNext();){
            var p=it.next();
            if(!dev.bwr.mod.world.LoadedWorld.available(level,p))continue;
            if(dev.bwr.mod.world.LoadedWorld.blockEntity(level,p) instanceof TurbineAuxBlockEntity a&&!a.isRemoved()&&worldPosition.equals(a.host()))out.add(a);
            else{it.remove();setChanged();}
        }
        return out;
    }
    public List<TurbineAuxBlockEntity> genCoolingSkids(){
        if(!block().generator())return List.of();
        var all=auxEntities();if(all.isEmpty())return all;
        return all.stream().filter(a->a.kind()==TurbineAuxBlock.Kind.GEN_COOLING).toList();
    }
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
        double room=coverOff()||wrecked()?admissionCapacityKgPerS()/20:outletRoom();
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
        // An open casing (the crane has its hood off) holds no steam: what the valves admit vents into the hall, doing no work.
        // Nor does a wrecked one (burst open, or its rotor broken loose), until it is rebuilt.
        boolean open=coverOff()||wrecked();
        double room=open?admissionCapacityKgPerS()/20:outletRoom();
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
        if(open){
            var vented=inlet.take(Math.min(inlet.mass(),wanted));
            flowKgPerS=vented.mass()*20;shaftMW=0;outletPsia=0;outletC=0;running=false;setChanged();
            status=wrecked()?(flowKgPerS>0?"Wrecked: the burst casing vents the steam into the hall":"Wrecked: the casing is burst open")
                    :flowKgPerS>0?"Casing open: the steam vents into the hall":"Casing open (cover off)";
            return 0;
        }
        double inletMoisture=inlet.moisture();
        var result=PowerTurbine.expand(stage,inlet,stage==PowerTurbine.Stage.HP?exhaust:condenser.plant().steam,wanted,workBudgetKJ,stage==PowerTurbine.Stage.HP?PowerTurbine.outletPressure(stage,inlet.pressure()):condenser.plant().backpressurePsia());
        if(stage==PowerTurbine.Stage.HP && exhaustLedgerTick==level.getGameTime())exhaustOfferedKg+=result.mass();
        if(stage==PowerTurbine.Stage.LP&&result.mass()>0)condenser.setChanged();
        flowKgPerS=result.mass()*20;shaftMW=result.workKJ()*20/1000;
        if(result.mass()>0){
            inletMoisturePercent=inletMoisture*100;
            // Blade wear is the turbine's existing damage (the one overspeed adds to), not a second counter. On an LP it is
            // the condition of its two blade sections (both flows see the same steam), which damage() reports with the shaft.
            double wear=PowerTurbine.erosionIncrement(result.mass(),inletMoisture);
            if(lp())addBladeDamageAll(wear);else addShaftDamage(wear);
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
    @Override public void onLoad() { super.onLoad(); dev.bwr.mod.advancement.PlantMilestones.loaded(this); rememberLp(); }
    @Override public void setLevel(Level level) { super.setLevel(level); rememberLp(); }
    @Override public void setRemoved() { forgetLp(); dev.bwr.mod.advancement.PlantMilestones.unloaded(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { forgetLp(); dev.bwr.mod.advancement.PlantMilestones.unloaded(this); super.onChunkUnloaded(); }
    // ---- client sync: only what the open casing's drawing needs (the cover, which sections are in, their condition)
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){
        var t=new CompoundTag();if(lp()){if(coverOff)t.putBoolean("CoverOff",true);if(bladesOut!=0)t.putByte("BladesOut",(byte)bladesOut);
            if(bladesThrown!=0)t.putByte("Thrown",(byte)bladesThrown);t.put("Blades",doubles(blades));}
        saveWreck(t);
        return t;
    }
    @Override public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket(){
        return !block().generator()?net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this):null;
    }
    @Override public void handleUpdateTag(CompoundTag t,HolderLookup.Provider registries){readMaintenance(t);}
    @Override public void onDataPacket(net.minecraft.network.Connection net,net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,HolderLookup.Provider registries){readMaintenance(pkt.getTag());}
    private void readMaintenance(CompoundTag t){
        coverOff=t.getBoolean("CoverOff");bladesOut=t.getByte("BladesOut")&3;bladesThrown=t.getByte("Thrown")&3;
        var l=t.getList("Blades",6);for(int f=0;f<blades.length;f++)blades[f]=f<l.size()?finite(l.getDouble(f),1):0;
        loadWreck(t);
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider registries) {
        super.saveAdditional(t,registries);t.putDouble("RPM",rpm);
        identityReady=true;
        t.putInt("ShaftVersion",1);t.putDouble("ShaftDamage",damage);
        t.putInt("GridVersion",1);t.putUUID("DeviceId",deviceId);
        t.putLong("DeliveredFE",delivered.totalFE());t.putBoolean("CounterSaturated",delivered.saturated());
        t.putDouble("Energy",energyFe);t.putDouble("Water",waterKg);
        saveSteam(t,"Inlet",inlet);saveSteam(t,"Exhaust",exhaust);
        saveAux(t);
        if(lp())saveBlades(t);
        double ambient=dev.bwr.core.turbine.TurbineAux.AMBIENT_C;
        if(hoodC>ambient+.01)t.putDouble("HoodC",hoodC);
        if(hasCloseShock){t.putDouble("CloseAngle",closeAngle);t.putDouble("ClosePu",closeTorquePu);}
        saveWreck(t);
        if(burning()){t.putString("Fire",fire.name());t.putDouble("FireFuel",fireFuel);}
    }
    /** The wreck, the disc burst and the holes, only when not the default (TURBINE-MAINTENANCE.md). */
    private void saveWreck(CompoundTag t){
        if(wreck!=WRECK_NONE)t.putByte("Wreck",(byte)wreck);
        if(wreck==WRECK_SHAFT_OUT&&brokenFlow!=0)t.putByte("BrokenFlow",(byte)brokenFlow);
        if(discBurst)t.putBoolean("DiscBurst",true);
        if(anyHole())t.put("Holes",doubles(holes));
    }
    private void loadWreck(CompoundTag t){
        wreck=Math.max(WRECK_NONE,Math.min(WRECK_SHAFT_OUT,t.getByte("Wreck")));discBurst=t.getBoolean("DiscBurst");brokenFlow=t.getByte("BrokenFlow")&1;
        java.util.Arrays.fill(holes,Double.NaN);
        var l=t.getList("Holes",6);for(int f=0;f<holes.length&&f<l.size();f++){double a=l.getDouble(f);holes[f]=Double.isFinite(a)?a:Double.NaN;}
    }
    private static net.minecraft.nbt.ListTag doubles(double[] v){var l=new net.minecraft.nbt.ListTag();for(double d:v)l.add(net.minecraft.nbt.DoubleTag.valueOf(d));return l;}
    /** An LP always writes its Blades (the mark of a save made since the blade sections exist); the rest only when not the default. */
    private void saveBlades(CompoundTag t){
        t.put("Blades",doubles(blades));
        if(bladesThrown!=0)t.putByte("BladesThrown",(byte)bladesThrown);
        if(bladesOut!=0)t.putByte("BladesOut",(byte)bladesOut);
        if(coverOff)t.putBoolean("CoverOff",true);
    }
    /**
     * Reads the blade sections. An LP saved before they existed kept its blade wear in the one damage counter (wet steam was the
     * LP's only wear of its own): that wear becomes the condition of both sections, at most 0.95, so an old failed LP reads as
     * blades about to fail rather than throwing one the moment the world loads. HP sections and generators keep everything.
     */
    private void loadBlades(CompoundTag t){
        java.util.Arrays.fill(blades,0);bladesThrown=bladesOut=0;coverOff=false;
        if(!lp())return;
        if(t.contains("Blades",9)){
            var l=t.getList("Blades",6);for(int f=0;f<blades.length;f++)blades[f]=f<l.size()?finite(l.getDouble(f),1):0;
            bladesThrown=t.getByte("BladesThrown")&3;bladesOut=t.getByte("BladesOut")&3;coverOff=t.getBoolean("CoverOff");
        }else if(damage>0){
            double old=Math.min(.95,damage);java.util.Arrays.fill(blades,old);damage=0;
        }
    }
    private void saveAux(CompoundTag t){
        if(!auxLinks.isEmpty())t.putLongArray("AuxLinks",auxLinks.stream().mapToLong(BlockPos::asLong).toArray());
        if(auxRequired)t.putBoolean("AuxRequired",true);
        if(turningGear)t.putBoolean("TurningGear",true);
        if(bow>0)t.putDouble("Bow",bow);
        double ambient=dev.bwr.core.turbine.TurbineAux.AMBIENT_C;
        if(bearingC>ambient+.01)t.putDouble("BearingC",bearingC);
        if(rotorC>ambient+.01)t.putDouble("RotorC",rotorC);
        if(windingC>ambient+.01)t.putDouble("WindingC",windingC);
    }
    private void loadAux(CompoundTag t){
        auxLinks.clear();for(long l:t.getLongArray("AuxLinks"))auxLinks.add(BlockPos.of(l));
        auxRequired=t.getBoolean("AuxRequired");turningGear=t.getBoolean("TurningGear");
        double ambient=dev.bwr.core.turbine.TurbineAux.AMBIENT_C;
        bow=finite(t.getDouble("Bow"),1);
        bearingC=t.contains("BearingC")?Math.max(ambient,finite(t.getDouble("BearingC"),1000)):ambient;
        rotorC=t.contains("RotorC")?Math.max(ambient,finite(t.getDouble("RotorC"),1000)):ambient;
        windingC=t.contains("WindingC")?Math.max(ambient,finite(t.getDouble("WindingC"),1000)):ambient;
        vibration=0;aux=new TurbineAuxTrain.Readout();
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
        loadAux(t);
        loadBlades(t);
        double ambient=dev.bwr.core.turbine.TurbineAux.AMBIENT_C;
        hoodC=t.contains("HoodC")?Math.max(ambient,finite(t.getDouble("HoodC"),1000)):ambient;reverseMW=0;motoring=false;gridHeldTick=Long.MIN_VALUE;
        hasCloseShock=t.contains("CloseAngle");closeAngle=hasCloseShock?wrapped(t.getDouble("CloseAngle")):0;closeTorquePu=hasCloseShock?finite(t.getDouble("ClosePu"),100):0;
        loadWreck(t);
        fire=TurbineFire.Kind.NONE;fireFuel=0;
        if(t.contains("Fire")){
            try{fire=TurbineFire.Kind.valueOf(t.getString("Fire"));}catch(IllegalArgumentException e){fire=TurbineFire.Kind.NONE;}
            fireFuel=finite(t.getDouble("FireFuel"),1e6);
            if(fireFuel<=0)fire=TurbineFire.Kind.NONE;
        }
    }
    private static void loadSteam(CompoundTag t,String key,SteamInventory s){var p=t.getCompound(key);s.restore(p.getDouble("Mass"),p.getDouble("Enthalpy"),p.getDouble("Pressure"),p.getDouble("Moisture"));}
    private static double wrapped(double v){return Double.isFinite(v)?dev.bwr.core.grid.Synchronizer.wrap(v):0;}
    private static double finite(double v,double max){return Double.isFinite(v)?Math.max(0,Math.min(max,v)):0;}
}
