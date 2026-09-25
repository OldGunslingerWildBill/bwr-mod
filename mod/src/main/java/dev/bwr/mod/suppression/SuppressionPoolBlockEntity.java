package dev.bwr.mod.suppression;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.mod.eccs.EccsNetwork;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.eccs.ReactorEccsBus;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ValidationResult;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.steam.SteamLineNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suppression pool multiblock — {@code SPEC.md} section 12.
 *
 * <p>A thin Minecraft shell around {@link SuppressionPool}, which holds all the
 * thermodynamics and is plain Java. This class finds the water volume, finds the
 * SRVs discharging into it, and moves heat between them.
 *
 * <h2>Both halves of the relief path are accounted here</h2>
 * Steam that goes into this pool has to come out of the vessel, and the two used
 * to be the responsibility of two unrelated block entities: the pool condensed
 * whatever the valves it could see were passing, while only an
 * {@code AdsControllerBlockEntity} ever told the vessel it had lost anything. A
 * plant with a pool and a redstone-actuated relief valve and no ADS therefore
 * gained mass and energy out of nothing — the pool heated at a hundred-odd
 * megawatts while dome pressure did not fall by one psi. The pool now reports
 * each valve on the relief channel itself, keyed on the valve so that an ADS
 * controller watching the same bank overwrites the identical figure rather than
 * adding a second copy of it. Whatever the pool absorbs, the vessel loses.
 *
 * <h2>How big the pool is</h2>
 * The design inventory is the basin the player dug, not a constant, and
 * {@link SuppressionPool} measures heat capacity, level and the suction floor
 * against it — so the block count below is physics and a wrong one is a wrong
 * plant. It is found by a bounded flood fill from the controller
 * ({@link #surveyBasin}) rather than by counting every water block in range,
 * because counting everything in range means a controller set down beside an
 * ocean, a lake or a flooded cave is metering a heat sink with tens of
 * thousands of tonnes in it that the player never built.
 *
 * <h2>Steam gets in through an inlet, and there are two of them</h2>
 * Relief steam reaches this water by one of two routes and the pool separates
 * them, because they are not equally good at putting steam into water.
 *
 * <ul>
 *   <li>A <b>quencher</b> — {@link SuppressionPoolQuencherBlock} submerged in
 *       the basin with a pressurised tube run from the valve down to it. This
 *       is the real plant: the discharge is split across the quencher's holes
 *       and condenses in full, so everything the valve passes is admitted.</li>
 *   <li><b>Open water</b> — the valve simply standing above the pool, which is
 *       how this mod worked before quenchers existed and how every plant built
 *       so far is plumbed. A single unbroken jet out of one pipe bore, so only
 *       {@link #BARE_DISCHARGE_ADMISSION} of it is taken up by the water and
 *       the rest reaches the containment airspace uncondensed.</li>
 * </ul>
 *
 * <p><b>The bare route is not being taken away and is not deprecated.</b> A
 * plant already built goes on relieving, goes on depressurising at exactly the
 * rate it did, and goes on heating its pool — it simply does less of the last
 * one, and {@link #statusLines()} says so in as many words, names the block
 * that fixes it, and says so from the structure alone rather than waiting for a
 * transient to make it visible. What the de-rating costs is not free: steam
 * that is not condensed is water the pool does not get back, so a plant riding
 * out a long transient on pool suction with bare discharges will watch its own
 * level run down towards the pump intake. That is the honest consequence of
 * discharging into open water and it is exactly why the hardware exists.
 *
 * <h2>What it does not do</h2>
 * There is no heat capacity temperature limit here, no alarm, and no automatic
 * RHR start. Pool temperature, subcooling and condensation effectiveness are
 * published; what counts as too hot is a number a real plant sets
 * administratively, so in this mod it is the player's to choose and act on in
 * Lua. Nothing here decides when steam should flow either: a quencher is a
 * perforated pipe under water and the admission figures below are properties of
 * that pipework, not permissives.
 */
public class SuppressionPoolBlockEntity extends BlockEntity {

    /** How far out the controller looks for its water volume and its valves. */
    private static final int SEARCH_RADIUS = 24;
    /** Litres of pool water represented by one water block. */
    private static final double KG_PER_WATER_BLOCK = 1000.0;
    /**
     * Below this many water blocks it is a puddle, not a suppression pool.
     *
     * <p>Applied twice, and both are load bearing. {@link #surveyBasin} uses it
     * to decide that a body it has walked is not worth being the pool, so the
     * survey keeps looking instead of stopping at the first stray source block
     * near the controller; {@link #revalidate} applies it to whatever the survey
     * finally handed back and writes the message. The second is where the rule
     * is <i>stated</i> to the player.
     */
    private static final int MIN_WATER_BLOCKS = 64;

    /**
     * How far from the controller a water block can be and still be taken as
     * the start of the pool, blocks.
     *
     * <p>The controller has to be <i>at</i> the basin it meters. This is a
     * small tolerance rather than strict face-adjacency so that a controller
     * set into the rim wall, or standing a block or two proud of the water
     * line, still finds its pool — but it is nowhere near the search radius,
     * because "there is water somewhere within 24 blocks" is exactly the rule
     * that let a controller adopt the sea.
     */
    private static final int SEED_RADIUS = 4;

    /**
     * Largest basin one controller will survey, water blocks.
     *
     * <p>This is a limit on the survey, not a safety rule. 32,768 blocks is
     * 32,768 tonnes, nearly ten times a BWR/6 pool and a 32-cube of water to
     * dig; a contiguous body larger than that inside the search radius is a
     * flooded cave system rather than anything anyone built as a basin, and
     * sizing the physics from it would hand the player a heat sink that never
     * warms. Reported as a failure with the count, so a player who genuinely
     * dug something enormous can see what happened rather than guess.
     */
    private static final int MAX_WATER_BLOCKS = 32_768;

    /**
     * Water blocks the survey will walk in bodies it has <i>refused</i>, across
     * the whole survey, before it gives up on finding a basin at all.
     *
     * <p>Only refused walks are charged against this, never the walk that finds
     * the pool, so it is a ceiling on wasted work and not on the size of a
     * basin. It exists because a refused body may be walked more than once —
     * see {@link #surveyBasin} on why a refused body must never be remembered
     * as though it were a wall. Two full oversize bodies' worth is enough that
     * no plausible shoreline build exhausts it, and it holds the worst case to
     * roughly three times the fluid lookups the hardware pass above already
     * does on every survey.
     */
    private static final int SURVEY_WORK_LIMIT = 2 * MAX_WATER_BLOCKS;

    /** The six faces, hoisted: {@code Direction.values()} clones its array. */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Shortest interval between structure scans, ticks.
     *
     * <p>The scan reads block and fluid state at all 117,649 positions of a
     * 49-cube. A neighbour change fires on every redstone edge, so an
     * unthrottled rescan is millions of lookups a second for a controller whose
     * neighbour merely blinked.
     */
    private static final int MIN_REVALIDATE_INTERVAL_TICKS = 20;

    /**
     * How often an unformed pool re-checks, ticks.
     *
     * <p>Revalidation used to happen only when a block directly against the
     * controller changed. Water placed anywhere else in the search radius fires
     * no neighbour change at all, so a player who set the controller down first
     * and then dug the basin was told "found 0 water blocks" forever with no
     * way to know they had to poke the block.
     */
    private static final int UNFORMED_REVALIDATE_INTERVAL_TICKS = 100;

    /** How often a formed pool re-checks, ticks. Catches a pool being drained. */
    private static final int FORMED_REVALIDATE_INTERVAL_TICKS = 600;

    /** Reports older than this are treated as gone, ticks. Matches the ECCS bus. */
    private static final long STALE_TICKS = 3L;

    /**
     * Fraction of a bare open-water discharge the pool actually takes up.
     *
     * <p>A model calibration constant in the same sense as
     * {@code SuppressionPool.FULL_CONDENSATION_SUBCOOLING_C}, not a plant
     * setpoint and not a threshold anybody chose to protect anything.
     *
     * <p>What it stands for: a discharge with no quencher on it leaves one pipe
     * bore as a single coherent steam jet, and a coherent jet condenses at its
     * own surface only. A T-quencher splits the identical mass flow across
     * hundreds of small holes spread along two arms, which is roughly an order
     * of magnitude more steam-water interface, and that is the entire reason
     * real plants have them — early Mark I units discharged through plain
     * straight pipes and were retrofitted with quenchers after the containment
     * loads programme.
     *
     * <p>0.40 is deliberately generous to the bare case. The physically honest
     * figure for a bare jet at a full valve lift would be harsher, but this
     * path is what every plant built before this block existed is using, and a
     * degradation a player is told about should leave their plant recognisable
     * rather than crippled. Steam relieved at 42 kg/s through a bare discharge
     * still puts about 45 MW into the water.
     */
    private static final double BARE_DISCHARGE_ADMISSION = 0.40;

    private SuppressionPool pool = new SuppressionPool();
    private SuppressionBasinData basinData;

    private ValidationResult lastValidation = new ValidationResult();
    private boolean structureDirty = true;
    private volatile boolean formed;
    private volatile int waterBlocks;
    private int ticksSinceRevalidate = UNFORMED_REVALIDATE_INTERVAL_TICKS;

    /** RHR duty commanded directly on this controller, 0..1. The player's. */
    private volatile double rhrDuty;
    private double rhrCapacityMW = 30.0;
    private double heatSinkC = 30.0;
    private double passiveCoolingMW;
    private double legacySurfaceAreaM2, legacyShellAreaM2;

    public double passiveCoolingMW() { return isFormed() ? passiveCoolingMW : 0; }

    /** Geometry is in metres at one metre per block; water level sets wetted wall area. */
    private void coolPassively(double dt) {
        double surface = legacySurfaceAreaM2;
        double shell = legacyShellAreaM2 * Math.min(1, pool.getLevelFraction());
        if (concreteLayout != null) {
            double width = concreteLayout.max().getX() - concreteLayout.min().getX() - 1;
            double depth = concreteLayout.max().getZ() - concreteLayout.min().getZ() - 1;
            surface = width * depth;
            double waterDepth = Math.max(0, surfaceY() - concreteLayout.min().getY() - 1);
            shell = surface + 2 * (width + depth) * waterDepth;
            if(closedTank){shell+=surface;surface=0;} // Roof/walls reject heat; no exposed free surface.
        }
        passiveCoolingMW = pool.coolPassively(SuppressionPool.AMBIENT_TEMPERATURE_C,
                surface, shell, dt) / dt;
    }

    /** Cache legacy pool boundary areas during structure scans, never on every tick. */
    private void measureLegacyCoolingAreas() {
        legacySurfaceAreaM2 = legacyShellAreaM2 = 0;
        if (concreteMode) return;
        for (long cell : basinWater) {
            BlockPos p = BlockPos.of(cell);
            for (Direction face : DIRECTIONS) {
                if (basinWater.contains(p.relative(face).asLong())) continue;
                if (face == Direction.UP) legacySurfaceAreaM2++;
                else legacyShellAreaM2++;
            }
        }
    }

    /** Sum of the duties RHR loops reported this tick. Transient by design. */
    private volatile double machineRhrDuty;

    private final List<BlockPos> dischargingValves = new ArrayList<>();

    /**
     * Which of {@link #dischargingValves} arrive through a quencher of this
     * pool's, rather than by falling into open water.
     *
     * <p>A subset of that list and never a separate population of valves, so
     * {@link #dischargingValveCount()} goes on meaning what the GUI and the
     * peripheral have always shown it meaning: how many valves discharge into
     * this pool at all.
     */
    private final Set<BlockPos> quencheredValves = new HashSet<>();

    /** Submerged quenchers found in this pool's search box. */
    private final List<BlockPos> quenchers = new ArrayList<>();
    private final LongOpenHashSet basinWater = new LongOpenHashSet();
    private boolean concreteMode;
    private boolean closedTank;
    private double steamInKgPerS;
    public boolean isEnclosedTank(){return closedTank;}
    public double steamInKgPerS(){return isFormed()?steamInKgPerS:0;}
    public int steamPortCount(){return concreteLayout==null||level==null?0:(int)concreteLayout.ports().stream().filter(p->level.getBlockState(p).is(BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get())).count();}
    public static final double STEAM_INLET_KG_PER_S=2_000;
    private long steamBudgetTick=Long.MIN_VALUE;
    private double steamAcceptedKg;
    /** Shared by all wall inlets and both BWR and Mekanism transports. Simulation does not reserve. */
    public double receiveSteam(double kg,double h,double psia,boolean simulate){
        if(level==null||level.isClientSide()||isRemoved()||level.getBlockEntity(worldPosition)!=this||!isFormed()
                ||!Double.isFinite(kg)||!Double.isFinite(h)||!Double.isFinite(psia)||kg<=0||h<=0||h>5000||psia<=0||psia>3208)return 0;
        double used=steamBudgetTick==level.getGameTime()?steamAcceptedKg:0;
        double accepted=Math.max(0,Math.min(kg,Math.min(pool.inletSteam.space(),STEAM_INLET_KG_PER_S/20-used)));
        if(!simulate&&accepted>0){
            accepted=pool.inletSteam.offer(new dev.bwr.core.turbine.SteamInventory.Packet(accepted,h,psia));
            steamBudgetTick=level.getGameTime();steamAcceptedKg=used+accepted;inventoryChanged();
        }
        return accepted;
    }
    private void pullInletSteam(){
        if(concreteLayout==null)return;
        for(var inlet:concreteLayout.ports()){
            if(!level.getBlockState(inlet).is(BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get()))continue;
            var graph=dev.bwr.mod.piping.PipeTopology.get(level,inlet,null,true,false);
            for(var route:dev.bwr.mod.piping.PipeTopology.routes(level,graph,true)){
                // Relief valves meter their own discharge. Never claim a second parallel allocation through them.
                if(route.opening()<=0||!level.isLoaded(route.node().pos())
                        ||!(level.getBlockEntity(route.node().pos()) instanceof dev.bwr.mod.reactor.RpvSteamOutletBlockEntity nozzle))continue;
                var cp=nozzle.getControllerPos();
                if(cp==null||!level.isLoaded(cp)||!(level.getBlockEntity(cp) instanceof ReactorControllerBlockEntity reactor)||!reactor.isFormed())continue;
                double psia=dev.bwr.core.thermal.Saturation.psiaFromPsig(reactor.core().getPressurePsig());
                double h=dev.bwr.core.thermal.Saturation.vapourEnthalpyKJPerKg(psia);
                double wanted=receiveSteam(STEAM_INLET_KG_PER_S/20,h,psia,true);
                if(wanted<=0)return;
                double actual=dev.bwr.mod.steam.SteamValveRouting.claimWithoutRelief(level,inlet,nozzle,wanted*20)/20;
                if(actual>0)receiveSteam(actual,h,psia,false);
            }
        }
    }
    private boolean inventoryRestored;
    public BlockPos visualMin, visualMax;
    public boolean visualFormed;
    public double visualSpray;
    public long[] visualReturns=new long[0];
    public double surfaceY() {
        if(concreteLayout==null)return Double.NEGATIVE_INFINITY;
        return concreteLayout.min().getY()+1+(concreteLayout.max().getY()-concreteLayout.min().getY()-1)*Math.min(1,pool.getLevelFraction());
    }
    public boolean containsConcrete(BlockPos p) {
        return concreteLayout!=null&&concreteLayout.problem()==null&&concreteLayout.cells().contains(p.asLong());
    }
    public boolean submergedConcrete(BlockPos p) {
        return isFormed()&&containsConcrete(p)&&surfaceY()>p.getY()+1.05;
    }
    public void setSprayMode(boolean spray) {
        if(level==null||level.isClientSide()||isRemoved()||level.getBlockEntity(worldPosition)!=this||!concreteMode)return;
        pool.setSprayMode(spray);inventoryChanged();
    }
    private void inventoryChanged() {
        if(basinData!=null)basinData.setDirty();
        setChanged();
    }
    private void syncVisual() {
        if(level!=null&&!level.isClientSide())level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
    }
    private ConcreteBasin.Layout concreteLayout;
    private final Map<BlockPos,DutyReport> physicalCooling = new HashMap<>();
    public boolean isConcreteBasin() { return concreteMode; }
    public boolean touchesConcreteBounds(BlockPos p) {
        if(concreteLayout==null)return true;
        var min=concreteLayout.min();var max=concreteLayout.max();
        return p.getX()>=min.getX()-1&&p.getX()<=max.getX()+1
                &&p.getY()>=min.getY()-1&&p.getY()<=max.getY()+1
                &&p.getZ()>=min.getZ()-1&&p.getZ()<=max.getZ()+1;
    }
    public void invalidateConcrete() { formed=false;markStructureDirty();syncVisual(); }
    public boolean ownsPort(BlockPos p) { return formed&&concreteLayout!=null&&concreteLayout.ports().contains(p); }
    public void reportPhysicalCooling(BlockPos p,long tick,double mw) {
        if(Double.isFinite(mw)&&mw>=0)physicalCooling.put(p.immutable(),new DutyReport(tick,mw));
    }
    public double physicalCoolingMW() {
        if(level==null)return 0;
        physicalCooling.entrySet().removeIf(e->level.getGameTime()-e.getValue().tick()>1||!level.isLoaded(e.getKey())
                ||!(level.getBlockEntity(e.getKey()) instanceof RhrHeatExchangerBlockEntity));
        return physicalCooling.values().stream().mapToDouble(DutyReport::duty).sum();
    }
    private final net.neoforged.neoforge.fluids.capability.IFluidHandler suctionWater=poolWater(true),returnWater=poolWater(false);
    public net.neoforged.neoforge.fluids.capability.IFluidHandler water(boolean suction) { return suction?suctionWater:returnWater; }
    private net.neoforged.neoforge.fluids.capability.IFluidHandler poolWater(boolean suction) {
        return new net.neoforged.neoforge.fluids.capability.IFluidHandler(){
            private boolean live(){return level!=null&&!level.isClientSide()&&!isRemoved()&&isFormed()&&level.getBlockEntity(worldPosition)==SuppressionPoolBlockEntity.this;}
            public int getTanks(){return 1;}
            public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank){int n=tank==0&&live()?(int)Math.floor(pool.getMassKg()):0;return n>0?dev.bwr.mod.water.ThermalWater.atTemperature(n,pool.getTemperatureC()):net.neoforged.neoforge.fluids.FluidStack.EMPTY;}
            public int getTankCapacity(int tank){return tank==0?(int)Math.floor(pool.getDesignMassKg()):0;}
            public boolean isFluidValid(int tank,net.neoforged.neoforge.fluids.FluidStack s){return tank==0&&!suction&&s.is(Fluids.WATER);}
            public int fill(net.neoforged.neoforge.fluids.FluidStack s,FluidAction action){
                if(!live()||!isFluidValid(0,s))return 0;int n=(int)Math.floor(Math.max(0,Math.min(s.getAmount(),pool.getFillSpaceKg())));
                if(n>0&&action.execute()){pool.receiveWaterKg(n,dev.bwr.mod.water.ThermalWater.temperature(s,dev.bwr.mod.water.ThermalWater.AMBIENT_C));inventoryChanged();}return n;
            }
            public net.neoforged.neoforge.fluids.FluidStack drain(net.neoforged.neoforge.fluids.FluidStack s,FluidAction a){return s.is(Fluids.WATER)?drain(s.getAmount(),a):net.neoforged.neoforge.fluids.FluidStack.EMPTY;}
            public net.neoforged.neoforge.fluids.FluidStack drain(int amount,FluidAction action){
                if(!live()||!suction)return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                int n=(int)Math.floor(Math.max(0,Math.min(amount,pool.getAvailableSuctionKg())));
                if(n<=0)return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                if(action.execute()){pool.drawSuctionKg(n,1);inventoryChanged();}return dev.bwr.mod.water.ThermalWater.atTemperature(n,pool.getTemperatureC());
            }
        };
    }

    /** Membership of the basin this controller actually measured. */
    public boolean ownsQuencher(BlockPos pos) {
        if(level!=null&&level.isLoaded(pos)&&level.getBlockState(pos).is(BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get()))
            return isFormed()&&ownsPort(pos)&&pool.getLevelFraction()>SuppressionPool.SUCTION_FLOOR_FRACTION;
        return isFormed() && level != null && level.isLoaded(pos) && level.isLoaded(pos.above())
                && (concreteMode ? submergedConcrete(pos) : basinWater.contains(pos.above().asLong())
                && SuppressionPoolQuencherBlock.isSubmerged(level, pos));
    }

    /** Remove a disconnected machine's exhaust without waiting for report expiry. */
    public void withdrawSteam(BlockPos source) { steamReports.remove(source); }

    /**
     * Relief steam that reached the pool this tick but was not taken up by the
     * water, kg/s — the shortfall of the bare discharges.
     *
     * <p>Distinct from {@code SuppressionPool.getUncondensedSteamKgPerS()},
     * which is steam the water refused for want of subcooling. This is steam
     * the <i>inlet</i> never got into the water in the first place, and the two
     * add up: a hot pool fed through bare discharges is losing steam at both
     * ends of the same path.
     */
    private double bypassedSteamKgPerS;
    public double getBypassedSteamKgPerS(){return bypassedSteamKgPerS;}

    private BlockPos reactorPos;

    /** Steam a machine says it is discharging into this pool. */
    private record SteamReport(long tick, double kgPerS, double pressurePsig) {
    }

    /** Pool-cooling duty a machine says it is supplying. */
    private record DutyReport(long tick, double duty) {
    }

    private final Map<BlockPos, SteamReport> steamReports = new HashMap<>();
    private final Map<BlockPos, DutyReport> dutyReports = new HashMap<>();

    public SuppressionPoolBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.SUPPRESSION_POOL.get(), pos, state);
        EccsNetwork.ensureListenerRegistered();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SuppressionPoolBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        maybeRevalidate(level);
        if (!isFormed()) {
            passiveCoolingMW = 0;
            if(level.getGameTime()%10==0)syncVisual();
            return;
        }

        double dt = 0.05;
        long gameTime = level.getGameTime();

        // No reactor, no steam. This used to default to RATED_DOME_PRESSURE_PSIG
        // and only replace it when a controller could be resolved, so a pool that
        // had lost its reactor sized every valve at full rated conditions and
        // poured 113 MW into itself from a plant that did not exist. A missing
        // reactor is an absence of steam, not steam at rated pressure.
        ReactorControllerBlockEntity reactor = reactor(level);
        ReactorCore core = reactor != null ? reactor.core() : null;
        double domePressurePsig = core != null ? core.getPressurePsig() : 0.0;
        double containmentPsia = pool.getContainmentPressurePsia();

        ReactorEccsBus bus = (core != null && reactorPos != null)
                ? EccsNetwork.busFor(level, reactorPos) : null;

        // Everything the SRVs are passing arrives here as steam to condense —
        // and leaves the vessel by the same accounting, see the class comment.
        // How much of it the water actually takes up depends on what it arrived
        // through, so the two inlets are summed apart.
        double quencheredKgPerS = 0.0;
        double bareKgPerS = 0.0;
        var it = dischargingValves.iterator();
        while (it.hasNext()) {
            BlockPos vp = it.next();
            // A valve reached through a quencher's line can be a long way from
            // this controller and therefore in a chunk that is not loaded.
            // Skipping is not the same as dropping: getBlockEntity answers null
            // for an unloaded chunk exactly as it does for a broken block, and
            // removing on that answer is how a valve across a chunk border
            // deregisters itself for good. It stays on the list and is looked
            // at again when its chunk comes back.
            if (!level.isLoaded(vp)) {
                continue;
            }
            if (!(level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)) {
                it.remove();
                quencheredValves.remove(vp);
                continue;
            }
            if(closedTank&&quencheredValves.contains(vp)){
                // Pumped water does not send neighbour updates to a distant valve.
                // Check live water level and the cached discharge route before metering it.
                srv.revalidateDischarge(level);
                var exit=srv.getBlockState().getBlock() instanceof dev.bwr.mod.steam.AdsReliefValveBlock?Direction.DOWN:null;
                var graph=dev.bwr.mod.piping.PipeTopology.get(level,vp,exit,true,false);
                boolean connected=dev.bwr.mod.piping.PipeTopology.routes(level,graph).stream()
                        .anyMatch(r->r.opening()>0&&quenchers.contains(r.node().pos())&&ownsQuencher(r.node().pos()));
                if(!connected)continue;
            }
            // With no reactor domePressurePsig is 0.0, which is at or below
            // containment, so the valve correctly passes nothing and its cached
            // reading is zeroed rather than left at its last discharge.
            double flow = srv.flowKgPerS(domePressurePsig, containmentPsia);
            srv.reportRelief(bus,gameTime,flow);
            // Only the pool that owns the discharge condenses it. Two pool
            // controllers can sit within range of one valve, and without this
            // each would put the whole flow into its own water.
            if (flow > 0.0 && srv.claimCondensation(getBlockPos(), gameTime)) {
                if (quencheredValves.contains(vp)) {
                    quencheredKgPerS += flow;
                } else {
                    bareKgPerS += flow;
                }
            }
        }

        // The inlet takes all of a quenchered discharge and part of a bare one.
        // Written every tick including the zero case, so the reading cannot
        // latch at its last value the way the uncondensed figure once did.
        double admittedKgPerS = quencheredKgPerS + bareKgPerS * BARE_DISCHARGE_ADMISSION;
        bypassedSteamKgPerS = bareKgPerS * (1.0 - BARE_DISCHARGE_ADMISSION);

        pullInletSteam();
        condense(admittedKgPerS, domePressurePsig, gameTime, dt);

        machineRhrDuty = expireAndSumDuties(gameTime);
        double duty = getEffectiveRhrDuty();
        if (duty > 0.0) {
            pool.coolWithRhr(duty, rhrCapacityMW, heatSinkC, dt);
        }
        coolPassively(dt);

        if(basinData!=null)basinData.setDirty();
        setChanged();
        if(gameTime%5==0)syncVisual();
    }

    /**
     * Condense this tick's steam from every source in a single call.
     *
     * <p>One call, unconditionally, and both of those matter.
     * {@code SuppressionPool} publishes exactly one uncondensed-steam figure and
     * one condensation effectiveness per call, so the turbine-driven pumps'
     * exhaust and the relief valves each used to overwrite the other's reading
     * and the published number was never the total of the two. And guarding the
     * call on {@code steam &gt; 0} meant the zero-flow branch — the one that
     * resets the uncondensed reading — was unreachable from the mod, so after a
     * long discharge the figure latched at its last value forever and a Lua
     * alarm written against it could never be cleared.
     *
     * <p>Sources arrive at different pressures: relief steam at dome pressure,
     * turbine exhaust at containment. {@code condenseSteam} takes one pressure,
     * so a mass-weighted mean is used. Saturated vapour enthalpy only moves from
     * about 2675 to 2778 kJ/kg across that entire span, so the approximation
     * costs under two per cent of the deposited heat in the one case where the
     * two flows are comparable, and nothing at all when either dominates. That
     * is a far smaller error than publishing a steam reading that is not the
     * total.
     */
    private void condense(double admittedSrvKgPerS, double domePressurePsig, long gameTime,
                          double dt) {
        double total = Math.max(0.0, admittedSrvKgPerS);
        double pressureMoment = total * domePressurePsig;
        double energyRate=total*dev.bwr.core.thermal.Saturation.vapourEnthalpyKJPerKg(dev.bwr.core.thermal.Saturation.psiaFromPsig(domePressurePsig));

        Iterator<Map.Entry<BlockPos, SteamReport>> reports = steamReports.entrySet().iterator();
        while (reports.hasNext()) {
            SteamReport r = reports.next().getValue();
            if (gameTime - r.tick() > STALE_TICKS) {
                reports.remove();
                continue;
            }
            total += r.kgPerS();
            pressureMoment += r.kgPerS() * r.pressurePsig();
            energyRate+=r.kgPerS()*dev.bwr.core.thermal.Saturation.vapourEnthalpyKJPerKg(dev.bwr.core.thermal.Saturation.psiaFromPsig(r.pressurePsig()));
        }

        var packet=pool.inletSteam.take(STEAM_INLET_KG_PER_S*dt);
        if(packet.mass()>0){
            total+=packet.mass()/dt;energyRate+=packet.energyKJ()/dt;
            pressureMoment+=packet.mass()/dt*dev.bwr.core.thermal.Saturation.psigFromPsia(packet.pressurePsia());
        }
        steamInKgPerS=total;
        double meanPsig = total > 0.0 ? pressureMoment / total : 0.0;
        pool.condenseSteamAtEnthalpy(total,total>0?energyRate/total:0,dt);
        double bulkEscaped=pool.getUncondensedSteamKgPerS();
        double sprayCaptured=pool.spraySteam(bulkEscaped+bypassedSteamKgPerS,meanPsig,dt);
        // Account for spray capturing the bare-discharge portion as well.
        bypassedSteamKgPerS=Math.max(0,bypassedSteamKgPerS-Math.max(0,sprayCaptured-bulkEscaped));
        // Steam the pool could not condense goes on to pressurise containment.
        // Containment is not modelled yet (SPEC section 16); the quantity is
        // published so a player can see it and so the model can consume it later.
    }

    // --- Reports from machines discharging into this pool -----------------

    /**
     * Post steam a machine is discharging into this pool, kg/s.
     *
     * <p>A rate, not a quantity, refreshed every tick by the machine and summed
     * once by {@link #condense}. A report that stops being refreshed — the
     * machine broken, or its chunk unloaded — expires on its own, so nothing can
     * leave phantom steam arriving in the water.
     */
    public void reportSteamKgPerS(BlockPos source, long gameTime, double kgPerS,
                                  double pressurePsig) {
        if (source == null) {
            return;
        }
        double flow = Double.isFinite(kgPerS) && kgPerS > 0.0 ? kgPerS : 0.0;
        steamReports.put(source.immutable(),
                new SteamReport(gameTime, flow, Double.isFinite(pressurePsig) ? pressurePsig : 0.0));
    }

    /**
     * Post the pool-cooling duty an RHR loop is supplying, 0..1.
     *
     * <p>Summed and staleness-checked for the same reason the ECCS contributions
     * are. Writing the duty straight in left it latched when the machine stopped
     * ticking, so a pool went on rejecting 30 MW through a heat exchanger the
     * player had already mined — persisted to NBT, and restored on reload.
     * Assignment also lost the second of two loops lined up for pool cooling.
     */
    public void reportRhrDuty(BlockPos source, long gameTime, double duty) {
        if (source == null) {
            return;
        }
        double d = Double.isFinite(duty) ? Math.min(1.0, Math.max(0.0, duty)) : 0.0;
        dutyReports.put(source.immutable(), new DutyReport(gameTime, d));
    }

    /** Drop a machine's duty at once, rather than waiting for it to go stale. */
    public void withdrawRhrDuty(BlockPos source) {
        if (source != null) {
            dutyReports.remove(source);
            machineRhrDuty = 0.0;
        }
    }

    private double expireAndSumDuties(long gameTime) {
        double total = 0.0;
        Iterator<Map.Entry<BlockPos, DutyReport>> it = dutyReports.entrySet().iterator();
        while (it.hasNext()) {
            DutyReport r = it.next().getValue();
            if (gameTime - r.tick() > STALE_TICKS) {
                it.remove();
                continue;
            }
            total += r.duty();
        }
        return total;
    }

    // --- Structure -----------------------------------------------------

    public void markStructureDirty() {
        structureDirty = true;
    }

    /**
     * Re-survey now, because somebody has just asked this controller what it
     * can see.
     *
     * <p>A formed pool rescans on {@link #FORMED_REVALIDATE_INTERVAL_TICKS},
     * which is half a minute, and nothing about laying a discharge line fires a
     * neighbour change here — the quencher goes in the basin, the tube runs
     * away towards the valve, and not one of those blocks need touch the
     * controller. So a player who has just finished plumbing a quencher and
     * walks over to read the board would, without this, be told for the next
     * thirty seconds that their valves still discharge into open water. That is
     * the one message this class most needs to get right, and a stale answer
     * here is exactly the kind that sends somebody looking for a fault in
     * pipework they have just finished building.
     *
     * <p>Routed through {@link #maybeRevalidate} rather than calling
     * {@link #revalidate} directly, so the existing
     * {@link #MIN_REVALIDATE_INTERVAL_TICKS} floor still applies and a player
     * leaning on the use key cannot run the full structure scan faster than the
     * tick path would.
     */
    public void refreshForReport(Level level) {
        markStructureDirty();
        maybeRevalidate(level);
    }

    /**
     * Rescan the structure when something says it changed, and on an interval
     * regardless.
     *
     * <p>The interval is the fix for a pool that can never notice its own water:
     * blocks placed away from the controller fire no neighbour change here. The
     * floor on the dirty path is the fix for the opposite problem — a redstone
     * clock beside the controller running the full scan twenty times a second.
     */
    private void maybeRevalidate(Level level) {
        if (ticksSinceRevalidate < Integer.MAX_VALUE) {
            ticksSinceRevalidate++;
        }
        int interval;
        if (structureDirty) {
            interval = MIN_REVALIDATE_INTERVAL_TICKS;
        } else if (formed) {
            interval = FORMED_REVALIDATE_INTERVAL_TICKS;
        } else {
            interval = UNFORMED_REVALIDATE_INTERVAL_TICKS;
        }
        if (ticksSinceRevalidate < interval) {
            return;
        }
        revalidate(level);
        structureDirty = false;
        ticksSinceRevalidate = 0;
    }

    private void revalidate(Level level) {
        try { revalidateLoaded(level); }
        catch(dev.bwr.mod.world.LoadedWorld.MissingChunk missing) {
            formed=false; lastValidation=new ValidationResult();
            lastValidation.fail(missing.pos,"Suppression basin validation is waiting for the remaining chunks to load.");
        }
    }

    private void revalidateLoaded(Level level) {
        ValidationResult result = new ValidationResult();
        dischargingValves.clear();
        quencheredValves.clear();
        quenchers.clear();
        // Cleared before the scan, like every other binding in the mod. Leaving
        // the old value meant a broken reactor controller left a stale position
        // behind that resolved to nothing, which is the state that used to make
        // the pool fall back to rated dome pressure.
        reactorPos = null;
        waterBlocks = 0;

        BlockPos min = getBlockPos().offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS);
        BlockPos max = getBlockPos().offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);

        // Hardware only in this pass. Water is surveyed separately, by
        // surveyBasin, and deliberately not counted here: this loop used to
        // test the fluid first and reach the valve and controller tests only
        // through an else-if, so any position holding water was never examined
        // for a block at all.
        //
        // The valves are only gathered here, not judged: a valve piped to a
        // quencher may be well outside this box and is found below instead, and
        // until the quenchers are known there is no way to tell which of the
        // ones standing in the box are the far end of somebody else's line.
        concreteLayout=ConcreteBasin.inspect(level,getBlockPos(),concreteMode,closedTank);
        if(concreteLayout!=null&&concreteLayout.problem()==null)closedTank=concreteLayout.enclosed();
        List<BlockPos> boxValves = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if(!level.isLoaded(p)) continue;
            BlockState found = level.getBlockState(p);
            if ((found.getBlock() instanceof dev.bwr.mod.steam.SafetyReliefValveBlock)
                    && level.getBlockEntity(p) instanceof SafetyReliefValveBlockEntity) {
                boxValves.add(p.immutable());
            } else if (found.is(BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get())&&concreteLayout!=null&&concreteLayout.ports().contains(p)) {
                quenchers.add(p.immutable());
            } else if (found.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get())) {
                if (containsConcrete(p) || SuppressionPoolQuencherBlock.isSubmerged(level, p)) {
                    quenchers.add(p.immutable());
                } else {
                    result.degrade(p.immutable(),
                            "this quencher has no water above it, so it admits steam to the"
                                    + " containment airspace rather than to the pool");
                }
            } else if (found.is(BwrBlocks.REACTOR_CONTROLLER.get())) {
                reactorPos = p.immutable();
            }
        }

        Basin basin;
        if(concreteLayout!=null) {
            concreteMode=true;basinWater.clear();basinWater.addAll(concreteLayout.water());
            basin=new Basin(concreteLayout.water().size(),concreteLayout.problem());
        } else basin=surveyBasin(level);
        waterBlocks = basin.waterBlocks();

        if (basin.problem() != null) {
            result.fail(getBlockPos(), basin.problem());
            formed = false;
            lastValidation = result;
            return;
        }

        if (concreteLayout==null && waterBlocks < MIN_WATER_BLOCKS) {
            result.fail(getBlockPos(), "suppression pool needs at least " + MIN_WATER_BLOCKS
                    + " water blocks in one basin, found " + waterBlocks);
            formed = false;
            lastValidation = result;
            return;
        }

        quenchers.removeIf(q -> concreteMode ? !containsConcrete(q)&&!concreteLayout.ports().contains(q) : !basinWater.contains(q.above().asLong()));
        gatherDischarges(level,boxValves,result);
        if (dischargingValves.isEmpty()&&steamPortCount()==0) {
            result.degrade("no relief valves discharge into this pool; it is a heat sink with nothing attached");
        }

        pool.setContainmentPressurePsia(dev.bwr.core.PhysicalConstants.ATMOSPHERIC_PSI);
        // Dry concrete shells begin empty. Legacy dug pools migrate their placed
        // water once. Saved claims only resize capacity; they never refill a basin.
        if(basinData==null && !inventoryRestored) {
            if(concreteMode)pool=SuppressionPool.empty(structureMassKg());
            else pool.resizeToDesignMassKg(waterBlocks*KG_PER_WATER_BLOCK,SuppressionPool.DEFAULT_TEMPERATURE_C);
        }
        if(level instanceof net.minecraft.server.level.ServerLevel server) {
            basinData=SuppressionBasinData.get(server);
            var claim=basinData.claim(server,getBlockPos(),concreteLayout==null?basinWater:concreteLayout.cells(),pool,structureMassKg());
            pool=claim.pool();
            if(claim.problem()!=null) {
                result.fail(getBlockPos(),claim.problem());formed=false;lastValidation=result;return;
            }
        }
        formed = true;inventoryRestored=true;
        measureLegacyCoolingAreas();
        if(concreteLayout!=null)for(var p:concreteLayout.ports())
            if(level.getBlockEntity(p) instanceof SuppressionPoolPortBlockEntity port)port.bind(getBlockPos());
        lastValidation = result;
        syncVisual();
    }

    /**
     * Work out which relief valves discharge into this pool, and by which
     * route.
     *
     * <h2>Why the valves cannot all be found by looking around the controller</h2>
     * A quenchered valve is meant to be nowhere near this block. That is the
     * point of running a discharge line: the relief valves belong up on the
     * main steam line at vessel elevation, and the tube carries what they pass
     * down to the water. Gathering valves by proximity alone — which is all
     * this class could do while the only discharge was a search for water
     * underneath — would have made the new hardware useless for exactly the
     * builds it exists to allow, because a valve on the vessel is routinely
     * further than {@link #SEARCH_RADIUS} from a controller standing at the
     * pool.
     *
     * <p>So the pipework is followed instead, from each of this pool's own
     * quenchers outwards, which is what {@link SteamLineNetwork} is for. That
     * also settles ownership without any extra rule: a valve is this pool's
     * business if it can be reached along the line from a quencher submerged in
     * this pool. A valve piped to a quencher in somebody else's basin is not
     * reached, is not counted here, and is left to the controller that is
     * standing at the water it actually discharges into.
     *
     * <p>The walk from a quencher and the walk from a valve are the same walk
     * over the same connectivity — {@code SteamLineNetwork.joined} is symmetric
     * and both stop at the same kinds of end — so "this quencher reaches that
     * valve" and "that valve reaches this quencher" are one fact, and no
     * agreement has to be negotiated between the two block entities.
     */
    private void gatherDischarges(Level level, List<BlockPos> boxValves, ValidationResult result) {
        // Valves on the discharge lines of this pool's own submerged quenchers.
        Set<BlockPos> quencherFed = new LinkedHashSet<>();
        for (BlockPos q : quenchers) {
            quencherFed.addAll(SteamLineNetwork.survey(level, q).reliefValves());
        }

        // Both populations, deduplicated, in an order that does not vary
        // between runs — these positions end up in a list the status text
        // reports from.
        Set<BlockPos> candidates = new LinkedHashSet<>(boxValves);
        candidates.addAll(quencherFed);

        for (BlockPos vp : candidates) {
            if (!(level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)) {
                continue;
            }
            if(concreteMode&&quencherFed.contains(vp)) {
                dischargingValves.add(vp);quencheredValves.add(vp);continue;
            }
            srv.revalidateDischarge(level);
            switch (srv.dischargePath()) {
                case QUENCHER -> {
                    if (quencherFed.contains(vp)) {
                        dischargingValves.add(vp);
                        quencheredValves.add(vp);
                    } else {
                        // Piped to a quencher, but not to one of this pool's.
                        // Condensing it here would put another basin's steam
                        // into this water.
                        result.degrade(vp, "this relief valve discharges through a quencher"
                                + " that is not submerged in this basin, so its steam is not"
                                + " condensed here");
                    }
                }
                case OPEN_WATER -> {
                    for(int depth=1;depth<=24;depth++) {
                        BlockPos water=vp.below(depth);
                        if(!level.isLoaded(water))break;
                        var fluid=level.getFluidState(water);
                        if(fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                            if(basinWater.contains(water.asLong()))dischargingValves.add(vp);
                            break;
                        }
                    }
                }
                case NONE -> result.degrade(vp, "this relief valve has no discharge path:"
                        + " no water below it and no submerged quencher on its steam line,"
                        + " so it suppresses nothing");
            }
        }
    }

    // --- Finding the basin ------------------------------------------------

    /**
     * What the basin survey found: the size of the pool in water blocks, and
     * the reason there is no pool when there is not one. {@code problem} is
     * null exactly when a basin was found.
     */
    private record Basin(int waterBlocks, String problem) {
    }

    /**
     * Find the body of water this controller is standing at.
     *
     * <p><b>Do not put this back to counting every water block in the search
     * box.</b> That is what it used to do, and it was only ever cosmetic
     * because the pool was hardcoded to a BWR/6 inventory. Now that
     * {@code SuppressionPool} is sized from the count — heat capacity, level
     * fraction and the ECCS suction floor all scale with it — a controller
     * placed near an ocean, a lake or a flooded cave counted tens of thousands
     * of blocks it had nothing to do with and handed the player a heat sink
     * that could absorb a full-power blowdown without warming measurably. The
     * pool has to be the pool the player built.
     *
     * <p>Four things bound it, and each of them is a property of the
     * structure rather than a rule about the plant:
     *
     * <ul>
     *   <li><b>Contiguity.</b> The pool is one connected body of water, walked
     *       face to face from a seed within {@link #SEED_RADIUS} of the
     *       controller. Water on the other side of a wall is a different pool
     *       and belongs to whatever controller is standing at <i>it</i>.</li>
     *   <li><b>Enclosure.</b> If the body reaches past the survey box the
     *       basin has no far wall inside the radius this controller can see,
     *       so it is open water and is refused rather than truncated. A dug
     *       basin is stopped by its own walls and by its free surface long
     *       before the box; the sea is not. This is also what stops the
     *       cheapest cheese there is — cutting a one-block channel from a
     *       small pool to the ocean.</li>
     *   <li><b>Size, above.</b> {@link #MAX_WATER_BLOCKS}, above which it is
     *       not a basin anyone dug.</li>
     *   <li><b>Size, below.</b> {@link #MIN_WATER_BLOCKS}, below which it is a
     *       puddle and the survey keeps looking. See the next paragraph — this
     *       one is not cosmetic.</li>
     * </ul>
     *
     * <p>Seeds are tried nearest first. That matters for the case this exists
     * to handle well: a legitimate pool built on a shoreline seeds both its own
     * basin and the sea, and the sea being refused must not take the basin down
     * with it.
     *
     * <p><b>A body too small to be a pool is not a pool, and finding one must
     * not end the survey.</b> The minimum was tested only by the caller, on
     * whatever body this method happened to return first — and since seeds are
     * tried nearest first, "first" means "nearest the controller", not
     * "largest". So a single stray source block within {@link #SEED_RADIUS} —
     * a bucket set down while plumbing the valves, a rained-in footprint, a
     * one-block spring in the rim wall — was walked, came back clean at a size
     * of one, and was handed to the caller as <i>the</i> basin. The controller
     * then reported "needs at least 64 water blocks, found 1" while standing on
     * the four thousand block pool the player had just finished digging, and
     * nothing about the message pointed at the puddle. Water is placed by
     * bucket, so spilled sources next to the controller are not an exotic case;
     * they are how the pool gets built. A body under the minimum is therefore
     * refused the same way an oversize one is and the walk moves to the next
     * seed, with the largest such body remembered so the caller's message still
     * names the biggest thing there actually was.
     *
     * <p><b>What is deliberately still accepted: a natural pond.</b> A body of
     * water the world generated, small enough to sit entirely inside the survey
     * box, passes every test here, and it is left passing them. Every rule that
     * would catch it is a rule about <i>provenance</i> — who put the water
     * there — and nothing in a {@code BlockState} records that. A material
     * requirement on the basin walls and strict controller adjacency were both
     * considered and both break ordinary builds: people line basins in whatever
     * they have, and people put the controller where the wiring reaches. What
     * is left is the observation that the physics is already right about this
     * case. The pool is sized from the count, so a 300-block pond is 300 tonnes
     * of heat sink and behaves like 300 tonnes: it heats about twelve times
     * faster than a BWR/6 pool, loses its subcooling early, stops condensing,
     * and hits the {@code SuppressionPool} suction floor while the low pressure
     * pumps are still calling for water. A player who takes the free pond gets
     * a pool that is worth exactly what it is, which is the outcome a provenance
     * test would be trying to produce anyway — without a heuristic that tells
     * someone who built a real basin that they did not.
     *
     * <p><b>Each attempt gets its own set of walked positions, and the ones a
     * refused body walked are never reused as though they were solid.</b> They
     * were, once, to save re-walking the sea — and the result was the worst
     * kind of wrong answer this class can give. The walk that gives up on
     * reaching open water gives up part way, so the positions it happened to
     * visit form an arbitrary blob; the next seed then flood filled the sea
     * <i>bounded by that blob</i>, terminated after a handful of blocks, and
     * reported a perfectly formed three-block suppression pool in the middle of
     * an ocean. What stops the re-walking now is {@code refused}, which only
     * ever skips a seed already known to sit in a refused body, and
     * {@link #SURVEY_WORK_LIMIT}, which bounds the total work outright.
     *
     * <h2>Why the walked positions are held as primitive longs</h2>
     * These two sets used to be {@code HashSet<Long>}, and every position the
     * survey touched was boxed into a {@code Long} on the heap —
     * {@code Long.valueOf} only caches -128..127 and a packed {@code BlockPos}
     * is nowhere near that range, so every single one was a fresh allocation,
     * plus a {@code HashMap.Node} to hold it. {@link #SURVEY_WORK_LIMIT} allows
     * 65,536 walked positions before the survey gives up, and each of them was
     * boxed twice over — once into {@code body} and again when {@code body} was
     * copied into {@code refused} — so a controller sitting beside open water
     * produced several megabytes of immediately-dead objects, and did it again
     * every {@link #UNFORMED_REVALIDATE_INTERVAL_TICKS} for as long as the pool
     * stayed unformed. That is a controller the player set down and walked away
     * from quietly running the garbage collector for them.
     *
     * <p>{@code LongOpenHashSet} stores the packed positions in a primitive
     * array, so the walk allocates nothing per position at all. It is fastutil,
     * which Minecraft already ships and depends on heavily; this adds no
     * dependency. The traversal itself is unchanged — same seeds, same order,
     * same answers.
     */
    private Basin surveyBasin(Level level) {
        basinWater.clear();
        BlockPos origin = getBlockPos();
        List<BlockPos> seeds = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                origin.offset(-SEED_RADIUS, -SEED_RADIUS, -SEED_RADIUS),
                origin.offset(SEED_RADIUS, SEED_RADIUS, SEED_RADIUS))) {
            if (level.isLoaded(p) && isPoolWater(level, p)) {
                seeds.add(p.immutable());
            }
        }
        if (seeds.isEmpty()) {
            return new Basin(0, "no water within " + SEED_RADIUS
                    + " blocks of this controller; a pool controller has to stand at the"
                    + " basin it meters");
        }
        seeds.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));

        LongOpenHashSet refused = new LongOpenHashSet();
        // Positions of bodies that were walked all the way to their own walls
        // and turned out to be too small to be a pool.
        //
        // Remembering these is sound in a way that remembering a refused body
        // is NOT, and the difference is the whole reason they are two sets. A
        // walk that gave up part way visited an arbitrary blob, so treating its
        // positions as walls would let a later seed flood fill the rest of the
        // sea bounded by that blob — the three-block ocean pool this method's
        // javadoc describes, and the reason `refused` only ever skips seeds.
        // A walk that finished enumerated its body exactly, so every later seed inside it
        // is guaranteed to reproduce the identical answer and skipping it
        // changes nothing but the work. That is what keeps the survey linear in
        // the water present: completed bodies are disjoint, each is walked once,
        // so all of them together cost at most one pass over the water in the
        // box — no more than the hardware scan above already spends.
        LongOpenHashSet completed = new LongOpenHashSet();
        // One body set, cleared between attempts rather than reallocated. The
        // previous attempt's contents have already been folded into `refused`
        // or `completed` by the bottom of the loop, so clearing is exactly
        // equivalent to a fresh set, and it means a shoreline build that walks
        // the sea several times grows the backing table once instead of once
        // per seed.
        LongOpenHashSet body = new LongOpenHashSet();
        String firstProblem = null;
        int largestSmall = 0;
        int walked = 0;
        for (BlockPos seed : seeds) {
            long packed = seed.asLong();
            if (refused.contains(packed) || completed.contains(packed)) {
                continue;
            }
            body.clear();
            Basin basin = fillBasin(level, origin, seed, body);
            if (basin.problem() == null) {
                if (basin.waterBlocks() >= MIN_WATER_BLOCKS) {
                    basinWater.addAll(body);
                    return basin;
                }
                // A complete body, and too small to be anybody's suppression
                // pool. Keep looking; the caller still gets the largest of them
                // so its "found N" message names the biggest water there was.
                completed.addAll(body);
                largestSmall = Math.max(largestSmall, basin.waterBlocks());
                continue;
            }
            if (firstProblem == null) {
                firstProblem = basin.problem();
            }
            refused.addAll(body);
            walked += basin.waterBlocks();
            if (walked > SURVEY_WORK_LIMIT) {
                return new Basin(0, tooMuchWaterMessage());
            }
        }
        // A refusal names something the player can act on — open water, or a
        // flooded cave — so it wins over "that was too small". With no refusal
        // at all the count goes back and the caller's minimum check writes the
        // message, which keeps the size rule stated in exactly one place.
        return firstProblem != null ? new Basin(0, firstProblem) : new Basin(largestSmall, null);
    }

    /**
     * Walk one connected body of water from {@code seed}, adding every position
     * it visits to {@code body}.
     *
     * <p>Depth first, not breadth first, and that is deliberate: on open water
     * a depth-first walk runs more or less straight at the wall of the survey
     * box and gives up after a few dozen positions, where a breadth-first walk
     * would first enumerate every block within twenty-odd steps of the seed. A
     * genuine basin is walked in full either way.
     *
     * <p>The count in the returned {@link Basin} is the size of the basin when
     * there is no problem, and how far the walk got before giving up when there
     * is. Only {@link #surveyBasin} sees the second kind, and only to charge it
     * against the work limit.
     *
     * <p>The stack holds packed positions rather than {@code BlockPos} objects,
     * for the same reason {@code body} does — see {@link #surveyBasin}. Every
     * position that went on it used to be a {@code next.immutable()} copy, so a
     * full 32,768-block basin allocated 32,768 {@code BlockPos} on top of the
     * boxing. Two mutable cursors do the whole walk now: one for the position
     * being expanded and one for the six neighbour probes.
     */
    private Basin fillBasin(Level level, BlockPos origin, BlockPos seed, LongOpenHashSet body) {
        LongArrayList stack = new LongArrayList();
        stack.push(seed.asLong());
        body.add(seed.asLong());
        int found = 0;
        // One cursor for the position being expanded, one for the six neighbour
        // probes. Most of the probes are not water, and a basin this size would
        // otherwise throw away a couple of hundred thousand BlockPos objects per
        // survey to find that out.
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (!stack.isEmpty()) {
            // LongArrayList.push/popLong append and remove at the end, so this
            // is the same last-in-first-out order ArrayDeque.push/pop gave and
            // the walk stays depth first, which the paragraph above depends on.
            long packed = stack.popLong();
            current.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            found++;
            if (found > MAX_WATER_BLOCKS) {
                return new Basin(found, tooMuchWaterMessage());
            }
            for (Direction d : DIRECTIONS) {
                next.setWithOffset(current, d);
                if (!isPoolWater(level, next)) {
                    continue;
                }
                if (outsideSurvey(origin, next)) {
                    // The body carries on past the far side of the box, so
                    // there is no wall in it that this controller can see.
                    return new Basin(found, "the water at this controller is open to more water"
                            + " than it can survey (it runs past " + SEARCH_RADIUS + " blocks);"
                            + " a suppression pool has to be an enclosed basin, not an ocean,"
                            + " a lake or a flooded cave");
                }
                long neighbour = next.asLong();
                if (body.add(neighbour)) {
                    stack.push(neighbour);
                }
            }
        }
        return new Basin(found, null);
    }

    private static String tooMuchWaterMessage() {
        return "this body of water is larger than the " + MAX_WATER_BLOCKS
                + " blocks one controller surveys; a suppression pool is a basin, not a"
                + " flooded cave system";
    }

    /**
     * Whether a position holds pool water.
     *
     * <p>Source blocks only, which is both what the old count did and what the
     * physics wants: a flowing block is a partial volume and a transient one,
     * and treating a waterfall as pool water would let the survey walk down it
     * into whatever it lands in. A waterlogged block is a source and counts,
     * so a basin faced with slabs or stairs is still a basin.
     */
    private static boolean isPoolWater(Level level, BlockPos pos) {
        return dev.bwr.mod.world.LoadedWorld.fluid(level,pos).getType() == Fluids.WATER;
    }

    /** Whether a position is past the survey box this controller can see. */
    private static boolean outsideSurvey(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) > SEARCH_RADIUS
                || Math.abs(pos.getY() - origin.getY()) > SEARCH_RADIUS
                || Math.abs(pos.getZ() - origin.getZ()) > SEARCH_RADIUS;
    }

    private ReactorControllerBlockEntity reactor(Level level) {
        if (reactorPos == null || !level.isLoaded(reactorPos)) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    // --- Measurements and commands --------------------------------------

    public SuppressionPool pool() {
        if(basinData!=null)basinData.setDirty();
        return pool;
    }

    public boolean isFormed() {
        if(formed&&concreteLayout!=null&&level!=null) {
            for(int x=concreteLayout.min().getX()>>4;x<=concreteLayout.max().getX()>>4;x++)
                for(int z=concreteLayout.min().getZ()>>4;z<=concreteLayout.max().getZ()>>4;z++)
                    if(!level.isLoaded(new BlockPos(x*16,worldPosition.getY(),z*16)))return false;
        }
        return formed;
    }

    public int waterBlocks() {
        return waterBlocks;
    }

    /**
     * Water blocks converted to kilograms — the design inventory of the pool the
     * player actually built, and what {@link SuppressionPool} is sized to.
     *
     * <p>"Actually built" is load-bearing and is the whole reason
     * {@link #surveyBasin} walks a connected body instead of counting water in
     * range. This figure sets the pool's heat capacity, so water the player did
     * not put there is thermal mass the plant did not earn.
     *
     * <p>{@code SuppressionPool} measures its suction floor and its level
     * fraction against its own design inventory, so a 64-block minimum pool is a
     * small pool and behaves like one. It used to measure both against the
     * BWR/6 {@code DEFAULT_MASS_KG}, and while it did, sizing the pool from the
     * structure was not possible: any pool under 3400 blocks would have sat
     * permanently below a 170,000 kg suction floor and could never have supplied
     * ECCS at all. That is why this figure was published and then ignored.
     */
    public double structureMassKg() {
        return (concreteLayout!=null&&concreteLayout.problem()==null?concreteLayout.volume():waterBlocks)*KG_PER_WATER_BLOCK;
    }

    public int dischargingValveCount() {
        return dischargingValves.size();
    }

    /** RHR duty commanded directly on this controller, 0..1. */
    public double getRhrDuty() {
        return rhrDuty;
    }

    /**
     * Duty the heat exchangers are actually running at: what the player asked
     * for here, plus what any RHR loop lined up for pool cooling is supplying.
     */
    public double getEffectiveRhrDuty() {
        if(concreteMode)return 0; // Physical four-port exchangers own heat removal for concrete basins.
        return Math.min(1.0, Math.max(0.0, rhrDuty + machineRhrDuty));
    }

    /** Commanded by the player. There is no automatic start. */
    public void setRhrDuty(double duty) {
        // Marshalled onto the server thread: Lua calls this from a CC computer
        // thread and setChanged() dispatches neighbour updates. See
        // PlantActuators.
        PlantActuators.run(this, () -> {
            this.rhrDuty = clampFraction(duty);
            setChanged();
        });
    }

    /**
     * A duty as a usable 0..1, with NaN mapped to zero rather than propagated.
     *
     * <p>{@code Math.min(1, Math.max(0, NaN))} is NaN — both comparisons fail
     * and the argument comes straight back out. So clamping is not on its own a
     * guard against a non-finite duty, and this is the one place that says so.
     *
     * <p>{@code SuppressionPoolPeripheral.setRhrDuty} still refuses a non-finite
     * duty with a {@code LuaException} and should go on doing so. This is not a
     * replacement for it: silently taking a NaN as zero would leave a player
     * whose control loop divided by zero staring at a pool that will not cool
     * and no reason why. This is the floor under every other way in — the load
     * path above, and any caller added later.
     */
    private static double clampFraction(double duty) {
        if (!Double.isFinite(duty)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, duty));
    }

    public double getRhrCapacityMW() {
        return rhrCapacityMW;
    }

    /**
     * What the pool's steam inlets are, and — if any of them is a bare
     * discharge — what that is costing.
     *
     * <p>This is the whole of the mod's obligation to a plant that was built
     * before quenchers existed, so it is written to be read by somebody who has
     * never heard of one. It reports from the <b>structure</b>, not from flow:
     * a player whose valves are all shut still gets told, at the moment they
     * next look at the controller, that their discharges are bare and what to
     * build. Waiting for a transient to reveal it would be exactly the silent
     * degradation this must not be.
     */
    private List<String> inletLines() {
        List<String> out = new ArrayList<>();
        if(steamPortCount()>0){
            out.add(String.format("%d steam wall inlet(s): %.2f kg/s received, %.1f kg buffered. Fill the tank before admitting steam.",steamPortCount(),steamInKgPerS(),pool.inletSteam.mass()));
            return out;
        }
        int quenchered = quencheredValves.size();
        int bare = dischargingValves.size() - quenchered;
        if (quenchered > 0) {
            out.add(String.format(
                    "%d valve line(s) connected through %d quencher(s); %d currently submerged. Condensation depends on pool temperature.",
                    quenchered, quenchers.size(),quenchers.stream().filter(this::ownsQuencher).count()));
        } else if (!quenchers.isEmpty()) {
            // Built but not plumbed. Saying nothing here would leave a player
            // who has just set the quenchers in the water with a board that
            // does not acknowledge them at all.
            out.add(String.format(
                    "%d quencher(s) in this basin, with no relief valve piped to any"
                            + " of them. Run pressurised tube from the valve to the quencher.",
                    quenchers.size()));
        }
        if (bare > 0) {
            out.add(String.format(
                    "%d valve(s) discharge into open water with no quencher. A bare discharge is"
                            + " one coherent jet, so only %.0f%% of what it passes is taken up by"
                            + " the water; the rest reaches containment uncondensed and does not"
                            + " return to the pool as inventory.",
                    bare, BARE_DISCHARGE_ADMISSION * 100.0));
            out.add("To fix: place a Suppression Pool Quencher in the basin with water directly"
                    + " above it, and run pressurised tube from the relief valve to it.");
        }
        if (bypassedSteamKgPerS > 0.0) {
            out.add(String.format("%.1f kg/s is bypassing the water through bare discharges.",
                    bypassedSteamKgPerS));
        }
        return out;
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (!isFormed()) {
            out.add("Suppression pool not formed.");
            out.addAll(lastValidation.messages());
            return out;
        }
        out.add(concreteMode?String.format((closedTank?"Enclosed tank":"Concrete basin")+" formed: %,.0f kg capacity, %d relief lines connected",
                pool.getDesignMassKg(),dischargingValves.size()):String.format("Pool formed: %d water blocks, %d relief valves discharging",waterBlocks,dischargingValves.size()));
        out.addAll(inletLines());
        if (concreteMode) {
            out.add(String.format("Inlet mode: %s; spray %.1f kg/s, steam captured %.1f kg/s",pool.isSprayMode()?"spray":"regular fill",pool.getSprayKgPerS(),pool.getSprayCondensedKgPerS()));
            if(pool.getMassKg()<=0)out.add("Basin is empty. Pump water into an amber Return / Fill Port.");
            out.add(String.format("Concrete basin: physical exchanger cooling %.2f MW", physicalCoolingMW()));
            out.add("Suction -> LPCI/RHR -> exchanger primary inlet; primary outlet -> basin return.");
            out.add("Direct return circulates water; supply the exchanger's separate cooling-water ports to remove heat.");
        }
        // The basin size is physics now, not decoration, so it is on the board:
        // a small pool really does heat faster and lose suction sooner.
        out.add(String.format("%,.0f kg of %,.0f kg design inventory (%.0f%% level), "
                        + "%,.0f kg reachable by suction",
                pool.getMassKg(), pool.getDesignMassKg(), pool.getLevelFraction() * 100.0,
                pool.getAvailableSuctionKg()));
        out.add(String.format("%.2f degC, subcooling %.2f degC, condensing at %.0f%%, %.0f MJ capacity left",
                pool.getTemperatureC(), pool.getSubcoolingC(),
                pool.condensationEffectiveness() * 100.0,
                pool.getRemainingHeatCapacityMJ()));
        out.add(String.format("Natural cooling %.1f kW toward %.0f degC ambient",
                passiveCoolingMW() * 1000, SuppressionPool.AMBIENT_TEMPERATURE_C));
        double effective = getEffectiveRhrDuty();
        if (effective > 0.0) {
            out.add(String.format("RHR cooling at %.0f%% duty (%.0f%% commanded here, %.0f%% from loops)",
                    effective * 100.0, rhrDuty * 100.0, machineRhrDuty * 100.0));
        }
        if (pool.isBoiling()) {
            out.add("Pool is boiling; steam is passing straight through to containment.");
        }
        if (reactorPos == null&&steamPortCount()==0) {
            out.add("No reactor controller found within " + SEARCH_RADIUS
                    + " blocks; the relief valves have nothing to relieve.");
        }
        out.addAll(lastValidation.messages());
        return out;
    }

    // --- Persistence -----------------------------------------------------

    @Override public void setChanged() {
        super.setChanged();
        if(basinData!=null)basinData.setDirty();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        dev.bwr.mod.reactor.ReactorStateNbt.putDoubles(tag, "Pool", pool.toArray());
        // Only the duty the player commanded on this block is saved. Duty
        // supplied by an RHR loop is that machine's to re-report every tick; it
        // was persisting the machine's figure that let a mined pump go on
        // cooling the pool across a save.
        tag.putDouble("RhrDuty", rhrDuty);
        tag.putDouble("RhrCapacityMW", rhrCapacityMW);
        tag.putDouble("HeatSinkC", heatSinkC);
        tag.putInt("WaterBlocks", waterBlocks);
        tag.putBoolean("ConcreteBasin",concreteMode);
        tag.putBoolean("ClosedTank",closedTank);
        tag.putBoolean("MeteredInventory",inventoryRestored);
        if (reactorPos != null) {
            tag.putLong("Reactor", reactorPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Pool")) {
            inventoryRestored=tag.contains("MeteredInventory")?tag.getBoolean("MeteredInventory"):tag.getInt("WaterBlocks")>=MIN_WATER_BLOCKS;
            pool.fromArray(dev.bwr.mod.reactor.ReactorStateNbt.getDoubles(tag, "Pool"));
        }
        // Sanitised on the way in, not merely on the way out.
        //
        // The Lua entry point rejects a non-finite duty now, but that only stops
        // new ones being made: a NaN written to disk before that fix reloads as
        // NaN, and NaN fails every comparison it meets. `duty > 0.0` is false, so
        // coolWithRhr is never even called, and the pool silently has no RHR for
        // the rest of that world's life with a status line saying nothing is
        // wrong. Worse, a NaN reaching coolWithRhr would poison pool temperature
        // and from there every subcooling and condensation reading downstream.
        //
        // The same three fields feed the same call, so all three are checked. A
        // heat sink is allowed to be cold, so it is checked for finiteness only;
        // a capacity is a rating and cannot be negative.
        rhrDuty = clampFraction(tag.getDouble("RhrDuty"));
        if (tag.contains("RhrCapacityMW")) {
            double saved = tag.getDouble("RhrCapacityMW");
            rhrCapacityMW = Double.isFinite(saved) && saved >= 0.0 ? saved : rhrCapacityMW;
        }
        if (tag.contains("HeatSinkC")) {
            double saved = tag.getDouble("HeatSinkC");
            heatSinkC = Double.isFinite(saved) ? saved : heatSinkC;
        }
        waterBlocks = tag.getInt("WaterBlocks");
        concreteMode=tag.getBoolean("ConcreteBasin");concreteLayout=null;
        closedTank=tag.getBoolean("ClosedTank");steamBudgetTick=Long.MIN_VALUE;steamAcceptedKg=0;steamInKgPerS=0;
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        structureDirty = true;
        visualFormed=tag.getBoolean("VisualFormed");
        visualMin=tag.contains("VisualMin")?BlockPos.of(tag.getLong("VisualMin")):null;
        visualMax=tag.contains("VisualMax")?BlockPos.of(tag.getLong("VisualMax")):null;
        visualSpray=tag.getDouble("VisualSpray");
        visualReturns=tag.getLongArray("VisualReturns");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag=saveWithoutMetadata(registries);
        tag.putBoolean("VisualFormed",isFormed()&&concreteMode);
        if(concreteLayout!=null) {
            tag.putLong("VisualMin",concreteLayout.min().asLong());tag.putLong("VisualMax",concreteLayout.max().asLong());
            tag.putLongArray("VisualReturns",concreteLayout.ports().stream().filter(p->level.getBlockState(p).is(BwrBlocks.SUPPRESSION_POOL_RETURN.get())).mapToLong(BlockPos::asLong).toArray());
        }
        tag.putDouble("VisualSpray",pool.getSprayKgPerS());return tag;
    }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    public static void clientTick(Level l,BlockPos p,BlockState s,SuppressionPoolBlockEntity be) {
        if(!be.visualFormed||be.visualSpray<=0||be.visualMin==null||l.getGameTime()%3!=0)return;
        double surface=be.visualMin.getY()+1+(be.visualMax.getY()-be.visualMin.getY()-1)*Math.min(1,be.pool.getLevelFraction());
        double top=be.closedTank?be.visualMax.getY()-.40:be.visualMax.getY()+1.20;
        for(long packed:be.visualReturns) {
            var port=BlockPos.of(packed);var state=l.getBlockState(port);
            if(!state.is(BwrBlocks.SUPPRESSION_POOL_RETURN.get()))continue;
            var side=state.getValue(SuppressionPoolPortBlock.FACING);var inside=port.relative(side.getOpposite());
            boolean alongX=side.getAxis()==net.minecraft.core.Direction.Axis.Z;
            int first=alongX?be.visualMin.getX()+1:be.visualMin.getZ()+1;
            int last=alongX?be.visualMax.getX():be.visualMax.getZ();
            for(int n=first;n<last;n++)for(int j=0;j<2;j++) {
                double xx=alongX?n+.25+j*.5:inside.getX()+.5;
                double zz=alongX?inside.getZ()+.5:n+.25+j*.5;
                if(top>surface)l.addParticle(net.minecraft.core.particles.ParticleTypes.FALLING_WATER,xx,top,zz,(l.random.nextDouble()-.5)*.08,-.2,(l.random.nextDouble()-.5)*.08);
            }
        }
    }
}
