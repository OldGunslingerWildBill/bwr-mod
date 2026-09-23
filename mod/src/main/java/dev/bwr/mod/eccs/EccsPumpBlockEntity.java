package dev.bwr.mod.eccs;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.eccs.EccsDesign;
import dev.bwr.core.eccs.EccsPump;
import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The block entity behind every emergency injection machine.
 *
 * <p>Thin: all the pump behaviour is in {@link EccsPump} in the physics module,
 * which has no Minecraft on its classpath. This class finds the reactor, finds
 * a suction source, moves energy and water across the boundary, and posts what
 * it delivered to the {@link ReactorEccsBus}.
 *
 * <h2>What it does not do</h2>
 * It never starts itself. There is no initiation signal, no level permissive,
 * no automatic transfer to pool suction when the tank empties, no minimum flow
 * protection and no trip of any kind. {@link #setRunning} is driven by a
 * redstone signal or by the player's Lua, and by nothing else. If nobody wrote
 * the control program, the machine sits there while the core uncovers.
 *
 * <h2>The couplings that make the choice of machine matter</h2>
 * All of these fall out of the physics rather than being written as rules:
 * a turbine drive takes its steam out of the vessel and puts the heat in the
 * suppression pool, so running HPCI depressurises the plant and warms the heat
 * sink; a motor drive stops dead without a bus; pool suction recirculates and
 * heats, tank suction is cold and runs out; and a core spray machine delivers
 * only as much as its sparger ring can distribute.
 */
public class EccsPumpBlockEntity extends BlockEntity {

    /** How far a machine looks for the reactor, the pool and the tank. */
    private static final int SEARCH_RADIUS = 24;

    /** Differential a pool cooling loop works against, psi. A closed circuit is easy. */
    private static final double POOL_COOLING_LOOP_PSI = 20.0;

    /** Shortest interval between full neighbourhood scans, ticks. */
    private static final int REBIND_INTERVAL_TICKS = 40;

    /**
     * Interval between rescans once the machine has its reactor, ticks.
     *
     * <p>The binding was refreshed only while the <b>reactor</b> was missing or
     * something had set {@link #bindingDirty}, and neither of those notices the
     * two bindings a player almost always makes second. A suppression pool
     * controller or a condensate storage tank placed twenty blocks away fires no
     * neighbour change here, so an ECCS machine that found its reactor first â€”
     * which is the ordinary build order, because the reactor is the thing you
     * build around â€” held {@code poolPos} and {@code tankPos} at null forever.
     * {@link #availableSuctionKg} then returns zero from a pool that is full,
     * the pump delivers nothing with every readout saying it is running, and the
     * only way out is to break and replace the machine. The same silence hid the
     * reverse case: a pool controller broken and rebuilt one block over left
     * every machine on the plant bound to a position with nothing in it.
     *
     * <p>Thirty seconds, matching the suppression pool's own formed
     * revalidation, and it costs the same 49-cube of block states.
     */
    private static final int BOUND_REBIND_INTERVAL_TICKS = 600;

    /** What an RHR loop is lined up to do. Only RHR has a choice. */
    public enum Mode {
        /** Take suction and put water in the vessel. */
        INJECTION,
        /** Circulate the suppression pool through the heat exchangers instead. */
        POOL_COOLING
    }

    private final EccsDesign design;
    private final EccsPump pump;
    private final MachineEnergy energy;
    private final AssemblyPlumbing.Cache plumbing = new AssemblyPlumbing.Cache();

    // Commands and published measurements are volatile because Lua reads them
    // from a CC computer thread. Writes are marshalled onto the server thread by
    // PlantActuators; these give the reads the matching visibility guarantee.
    private volatile SuctionSource suctionSource = SuctionSource.SUPPRESSION_POOL;
    private final dev.bwr.mod.water.WaterSuctionBuffer incomingWater = new dev.bwr.mod.water.WaterSuctionBuffer(this::setChanged);
    public net.neoforged.neoforge.fluids.capability.IFluidHandler waterInlet() { return incomingWater.tank(); }
    private volatile Mode mode = Mode.INJECTION;

    /** When true, redstone is ignored and Lua owns the start command. */
    private volatile boolean computerControlled;
    private boolean panelControlled;
    public boolean isPanelControlled() { return panelControlled; }
    public void setPanelControlled(boolean enabled) {
        panelControlled = enabled;
        if (enabled) computerControlled = false;
        setChanged();
    }
    public void setSpeedDemandFraction(double fraction) {
        PlantActuators.run(this, () -> {pump.setSpeedDemandFraction(fraction); setChanged();});
    }


    private BlockPos reactorPos;
    private BlockPos poolPos;
    private BlockPos tankPos;
    private BlockPos assemblyExhaustPoolPos;
    private volatile String assemblyConnectionStatus = "Awaiting pipe survey";
    private volatile double assemblySteamDrawKgPerS;
    private boolean bindingDirty = true;
    private int ticksSinceRebind = REBIND_INTERVAL_TICKS;
    private boolean wasCoolingPool;

    /** Last tick's delivered figures, kept for the panel and the peripheral. */
    private volatile double deliveredFlowKgPerS;
    private volatile double suctionShortfallKgPerS;

    public EccsPumpBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.ECCS_PUMP.get(), pos, state);
        this.design = EccsPumpBlock.designOf(state);
        this.pump = new EccsPump(design);
        this.energy = new MachineEnergy(EccsPower.fePerTickFromWatts(design.motorRatingWatts()));
        EccsNetwork.ensureListenerRegistered();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  EccsPumpBlockEntity be) {
        be.tick(level);
    }

    // -----------------------------------------------------------------
    // The tick
    // -----------------------------------------------------------------

    private void tick(Level level) {
        if (getBlockState().getBlock() instanceof TurbineAssemblyBlock assembly) {
            tickAssembly(level, assembly);
            return;
        }
        if (getBlockState().getBlock() instanceof PumpAssemblyBlock assembly && assembly.isFull(getBlockState())) {
            tickMotorAssembly(level,assembly);
            return;
        }
        maybeRebind(level);

        final double dt = 0.05;
        ReactorControllerBlockEntity reactor = reactor(level);
        ReactorCore core = reactor != null ? reactor.core() : null;
        SuppressionPoolBlockEntity poolBe = pool(level);
        CondensateStorageTankBlockEntity tankBe = tank(level);

        double vesselPsig = core != null ? core.getPressurePsig() : 0.0;
        double containmentPsig = poolBe != null
                ? Saturation.psigFromPsia(poolBe.pool().getContainmentPressurePsia()) : 0.0;
        boolean poolCooling = isPoolCooling();

        // What the pump is discharging into. A pool cooling lineup is a closed
        // circuit through a heat exchanger and back, so the vessel's pressure
        // is nothing to do with it.
        pump.setVesselPressurePsig(poolCooling ? POOL_COOLING_LOOP_PSI : vesselPsig);
        pump.setSuctionPressurePsig(0.0);
        pump.setExhaustPressurePsig(containmentPsig);

        double suctionTemperatureC = suctionTemperatureC(poolBe);
        pump.setSuctionTemperatureC(suctionTemperatureC);
        pump.setSuctionFlowLimitKgPerS(
                poolCooling ? Double.MAX_VALUE : availableSuctionKg(poolBe, tankBe) / dt);

        pump.setElectricalPowerAvailableWatts(Math.min(
                EccsPower.wattsFromFePerTick(energy.getEnergyStored()),
                design.motorRatingWatts()));

        pump.step(dt);

        // Spend what the motor actually drew. Turbine machines draw nothing and
        // their buffer never moves, which is the whole reason they survive a
        // station blackout.
        int spend = EccsPower.fePerTickFromWatts(pump.getElectricalDemandWatts());
        if (spend > 0) {
            energy.drain(spend);
        }

        double pumpedKgPerS = pump.getFlowKgPerS();
        double ringFactor = (design.delivery() == EccsDesign.Delivery.CORE_SPRAY && reactor != null)
                ? reactor.sprayRingCompleteness() : 1.0;

        // No reactor to deliver into means no water leaves the source. A pump
        // running on nothing recirculates through its own min-flow line.
        boolean delivering = !poolCooling && reactor != null && core != null;
        double wantedKgPerS = delivering ? pumpedKgPerS * ringFactor : 0.0;
        double gotKg = drawSuction(poolBe, tankBe, wantedKgPerS, dt);
        deliveredFlowKgPerS = dt > 0.0 ? gotKg / dt : 0.0;
        suctionShortfallKgPerS = Math.max(0.0, wantedKgPerS - deliveredFlowKgPerS);

        // A turbine drive's exhaust condenses in the suppression pool, carrying
        // the heat the vessel just lost into the heat sink. The mass goes with
        // it, which is what closes the loop: steam out of the vessel, water
        // into the pool, pool water back into the vessel.
        //
        // Reported to the pool rather than condensed here. SuppressionPool
        // publishes one uncondensed-steam figure per condenseSteam call, so two
        // callers in the same tick â€” this exhaust and the relief valves â€” would
        // each overwrite the other's reading and the published number would
        // never be the total. The pool sums every source and condenses once.
        double steamKgPerS = pump.getSteamDemandKgPerS();
        if (poolBe != null) {
            poolBe.reportSteamKgPerS(getBlockPos(), level.getGameTime(),
                    steamKgPerS, containmentPsig);
        }

        applyPoolCoolingDuty(poolBe, poolCooling);

        if (reactor != null && core != null) {
            boolean spray = design.delivery() == EccsDesign.Delivery.CORE_SPRAY;
            double boron = design.boronPpmPerMinuteAtRatedFlow() > 0.0
                    ? design.boronPpmPerMinuteAtRatedFlow()
                            * (deliveredFlowKgPerS / design.ratedFlowKgPerS())
                    : 0.0;
            EccsNetwork.busFor(level, reactorPos).report(getBlockPos(), level.getGameTime(),
                    spray ? 0.0 : deliveredFlowKgPerS, suctionTemperatureC,
                    spray ? deliveredFlowKgPerS : 0.0, suctionTemperatureC,
                    steamKgPerS, boron,
                    true,
                    design.drive() == EccsDesign.Drive.STEAM_TURBINE);
        }

        setChanged();
    }

    private void tickMotorAssembly(Level level, PumpAssemblyBlock assembly) {
        final double dt=.05;
        var state=getBlockState();
        boolean complete=assembly.complete(level,getBlockPos(),state);
        var suction=plumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_SUCTION);
        var outlet=plumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_DISCHARGE);
        var tank=complete ? AssemblyPlumbing.endpoint(level,suction,CondensateStorageTankBlockEntity.class) : null;
        var pool=complete ? AssemblyPlumbing.endpoint(level,suction,SuppressionPoolBlockEntity.class) : null;
        var delivery=complete ? AssemblyPlumbing.waterReceiver(level,outlet) : null;
        if(delivery!=null && !delivery.isFormed()) delivery=null;
        if(pool!=null && !pool.isFormed()) pool=null;
        var exchanger=complete?AssemblyPlumbing.endpoint(level,outlet,dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity.class):null;
        boolean directReturn=pool!=null&&AssemblyPlumbing.endpoint(level,outlet,SuppressionPoolBlockEntity.class)==pool;
        boolean exchangerReturn=pool!=null&&exchanger!=null&&exchanger.returnPool()==pool;
        boolean cooling=isPoolCooling() && pool!=null && suctionSource==SuctionSource.SUPPRESSION_POOL
                && (directReturn||exchangerReturn);
        BlockPos next=delivery==null || isPoolCooling() ? null : delivery.getBlockPos();
        if(!java.util.Objects.equals(reactorPos,next) || !java.util.Objects.equals(poolPos,pool==null?null:pool.getBlockPos())) detach();
        reactorPos=next; poolPos=pool==null?null:pool.getBlockPos(); tankPos=tank==null?null:tank.getBlockPos();
        double opening=Math.min(suction.opening(),outlet.opening());
        boolean path=complete && suction.valid() && outlet.valid() && (isPoolCooling()?cooling:delivery!=null);
        double temperature=suctionTemperatureC(pool);
        pump.setVesselPressurePsig(isPoolCooling()?POOL_COOLING_LOOP_PSI:delivery==null?0:delivery.core().getPressurePsig());
        pump.setSuctionPressurePsig(0); pump.setSuctionTemperatureC(temperature);
        pump.setSuctionFlowLimitKgPerS(path?Math.min(availableSuctionKg(pool,tank)/dt,design.ratedFlowKgPerS()*opening):0);
        pump.setElectricalPowerAvailableWatts(Math.min(EccsPower.wattsFromFePerTick(energy.getEnergyStored()),design.motorRatingWatts()));
        pump.step(dt);
        energy.drain(EccsPower.fePerTickFromWatts(pump.getElectricalDemandWatts()));
        boolean spray=design.delivery()==EccsDesign.Delivery.CORE_SPRAY;
        double wanted=path && !isPoolCooling()?pump.getFlowKgPerS()*(spray?delivery.sprayRingCompleteness():1):0;
        deliveredFlowKgPerS=drawSuction(pool,tank,wanted,dt)/dt;
        if(cooling&&path)deliveredFlowKgPerS=pump.getFlowKgPerS();
        suctionShortfallKgPerS=Math.max(0,wanted-deliveredFlowKgPerS);
        if(reactorPos!=null) EccsNetwork.busFor(level,reactorPos).report(getBlockPos(),level.getGameTime(),
                spray?0:deliveredFlowKgPerS,temperature,spray?deliveredFlowKgPerS:0,temperature,0,0,true,false);
        // A closed valve or dry source cannot reject heat through an absent water circuit.
        if(cooling && path && pump.getFlowKgPerS()>0 && exchangerReturn) {
            applyPoolCoolingDuty(pool,false);
            exchanger.circulate(pool,pump.getFlowKgPerS());
        } else if(cooling && path && pump.getFlowKgPerS()>0 && !pool.isConcreteBasin()) {
            pool.reportRhrDuty(getBlockPos(),level.getGameTime(),Math.min(pump.getSpeedFraction(),pump.getFlowKgPerS()/design.ratedFlowKgPerS()));
            wasCoolingPool=true;
        } else applyPoolCoolingDuty(pool,false);
        assemblyConnectionStatus=path?(cooling?(exchangerReturn?"Pool loop through heat exchanger":"Direct pool circulation (no external heat exchanger)"):"Water circuit connected"):"Water circuit incomplete, crossed, or missing its selected source";
        setChanged();
    }

    private void tickAssembly(Level level, TurbineAssemblyBlock assembly) {
        final double dt=.05;
        BlockState state=getBlockState();
        if (!assembly.complete(level,getBlockPos(),state)) {
            detach();
            reactorPos=null; poolPos=null; tankPos=null;
            pump.setSteamSupplyLimitKgPerS(0);
            pump.setSuctionFlowLimitKgPerS(0);
            pump.step(dt);
            deliveredFlowKgPerS=0;
            suctionShortfallKgPerS=0;
            assemblyConnectionStatus="Assembly incomplete or a required chunk is unloaded";
            setChanged();
            return;
        }
        var inlet=plumbing.trace(level,getBlockPos(),state,AssemblyPort.STEAM_INLET);
        var exhaust=plumbing.trace(level,getBlockPos(),state,AssemblyPort.STEAM_EXHAUST);
        var nozzles=AssemblyPlumbing.nozzles(level,inlet);
        var steamPort=assembly.portPosition(getBlockPos(),state,AssemblyPort.STEAM_INLET);
        var source=AssemblyPlumbing.source(level,nozzles);
        if (source!=null) nozzles.removeIf(n -> !source.getBlockPos().equals(n.getControllerPos()));
        var exhaustPool=AssemblyPlumbing.exhaustPool(level,exhaust);
        var suction=plumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_SUCTION);
        var discharge=plumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_DISCHARGE);
        var suctionTank=AssemblyPlumbing.endpoint(level,suction,CondensateStorageTankBlockEntity.class);
        var suctionPool=AssemblyPlumbing.endpoint(level,suction,SuppressionPoolBlockEntity.class);
        var delivery=AssemblyPlumbing.waterReceiver(level,discharge);
        double waterOpening=suction.valid() ? Math.min(suction.opening(),discharge.opening()) : 0;
        if (delivery!=null && !delivery.isFormed()) delivery=null;
        if (suctionPool!=null && !suctionPool.isFormed()) suctionPool=null;
        BlockPos newReactor=delivery==null ? null : delivery.getBlockPos();
        if (reactorPos!=null && !reactorPos.equals(newReactor)) EccsNetwork.withdraw(level,reactorPos,getBlockPos());
        reactorPos=newReactor;
        poolPos=suctionPool==null ? null : suctionPool.getBlockPos();
        tankPos=suctionTank==null ? null : suctionTank.getBlockPos();
        BlockPos newExhaust=exhaustPool==null ? null : exhaustPool.getBlockPos();
        if (assemblyExhaustPoolPos!=null && !assemblyExhaustPoolPos.equals(newExhaust)
                && level.isLoaded(assemblyExhaustPoolPos)
                && level.getBlockEntity(assemblyExhaustPoolPos) instanceof SuppressionPoolBlockEntity oldPool)
            oldPool.withdrawSteam(getBlockPos());
        assemblyExhaustPoolPos=newExhaust;

        double inletPressure=source==null ? 0 : source.core().getPressurePsig();
        double exhaustPressure=exhaustPool==null ? inletPressure
                : Saturation.psigFromPsia(exhaustPool.pool().getContainmentPressurePsia());
        pump.setSteamInletPressurePsig(inletPressure);
        pump.setExhaustPressurePsig(exhaustPressure);
        pump.setVesselPressurePsig(delivery==null ? 0 : delivery.core().getPressurePsig());
        pump.setSuctionPressurePsig(0);
        double temperature=suctionTemperatureC(suctionPool);
        pump.setSuctionTemperatureC(temperature);
        pump.setSuctionFlowLimitKgPerS(delivery==null ? 0
                : Math.min(availableSuctionKg(suctionPool,suctionTank)/dt, design.ratedFlowKgPerS()*waterOpening));

        // Predict demand using the physically available nozzle flow, then claim
        // that steam from the shared ledger. Only re-step if another consumer
        // already took a share. The controller already debited the source vessel.
        double available=source==null || exhaustPool==null || !pump.isRunning() ? 0
                : Math.min(nozzles.stream().mapToDouble(n -> dev.bwr.mod.steam.SteamValveRouting.available(level,steamPort,n)).sum(),
                        design.maximumSteamKgPerS()*exhaust.opening());
        pump.setSteamSupplyLimitKgPerS(available);
        double[] before=pump.toArray();
        pump.step(dt);
        double wantedSteam=pump.getSteamDemandKgPerS();
        double claimedSteam=0;
        for (var nozzle:nozzles) {
            if (claimedSteam>=wantedSteam) break;
            claimedSteam+=dev.bwr.mod.steam.SteamValveRouting.claim(level,steamPort,nozzle,wantedSteam-claimedSteam);
        }
        if (claimedSteam+1e-12<wantedSteam) {
            pump.fromArray(before);
            pump.setSteamSupplyLimitKgPerS(claimedSteam);
            pump.step(dt);
        }
        double wantedWater=delivery==null ? 0 : pump.getFlowKgPerS();
        deliveredFlowKgPerS=drawSuction(suctionPool,suctionTank,wantedWater,dt)/dt;
        suctionShortfallKgPerS=Math.max(0,wantedWater-deliveredFlowKgPerS);
        if (delivery!=null) EccsNetwork.busFor(level,reactorPos).report(getBlockPos(),level.getGameTime(),
                deliveredFlowKgPerS,temperature,0,temperature,0,0,true,false);
        if (exhaustPool!=null) exhaustPool.reportSteamKgPerS(getBlockPos(),level.getGameTime(),claimedSteam,exhaustPressure);
        assemblySteamDrawKgPerS=claimedSteam;
        assemblyConnectionStatus="Steam inlet: "+(source!=null ? "connected" : "no live reactor nozzle")
                +"; exhaust: "+(exhaustPool!=null ? "connected" : "no submerged pool quencher")
                +"; water discharge: "+(delivery!=null ? "connected" : "disconnected");
        setChanged();
    }

    /**
     * Publish this loop's pool-cooling duty to the pool.
     *
     * <p>Reported, not assigned. Writing {@code setRhrDuty} straight into the
     * pool was the pattern {@link EccsNetwork}'s javadoc calls out as wrong: the
     * clearing write only happens on a later tick of <i>this</i> machine, so a
     * pump that was broken or whose chunk unloaded left the pool rejecting up to
     * 30 MW through a heat exchanger that no longer exists â€” persisted to NBT
     * and restored on reload. Assignment also meant two loops lined up for pool
     * cooling produced whichever ticked last instead of their sum.
     *
     * <p>A reported duty expires on its own if it stops being refreshed, and
     * duties from several loops add up.
     */
    private void applyPoolCoolingDuty(SuppressionPoolBlockEntity poolBe, boolean poolCooling) {
        if (poolCooling) {
            if (poolBe != null && level != null) {
                poolBe.reportRhrDuty(getBlockPos(), level.getGameTime(), pump.getSpeedFraction());
            }
            wasCoolingPool = true;
        } else if (wasCoolingPool) {
            if (poolBe != null) {
                poolBe.withdrawRhrDuty(getBlockPos());
            }
            wasCoolingPool = false;
        }
    }

    private boolean isPoolCooling() {
        return mode == Mode.POOL_COOLING && design.isPoolCoolingCapable();
    }

    private double suctionTemperatureC(SuppressionPoolBlockEntity poolBe) {
        if (suctionSource == SuctionSource.SUPPRESSION_POOL && poolBe != null) {
            return poolBe.pool().getTemperatureC();
        }
        return CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C;
    }

    /** Water the chosen source could supply this tick, kg. */
    private double availableSuctionKg(SuppressionPoolBlockEntity poolBe,
                                      CondensateStorageTankBlockEntity tankBe) {
        if (suctionSource == SuctionSource.SUPPRESSION_POOL) {
            if (poolBe == null) {
                return 0.0;
            }
            // Ask the pool, rather than rebuilding its suction floor out here.
            // This used to be `mass - DEFAULT_MASS_KG * 0.05`, a floor fixed at
            // the BWR/6 figure, which is now wrong twice over: the pool is sized
            // to the basin the player built, so any basin under 3400 blocks
            // reported a negative volume and clamped to zero â€” a pump that could
            // never draw a drop however full its pool was.
            return poolBe.pool().getAvailableSuctionKg();
        }
        return incomingWater.availableKg() + (tankBe != null ? tankBe.storedKg() : 0.0);
    }

    private double drawSuction(SuppressionPoolBlockEntity poolBe,
                               CondensateStorageTankBlockEntity tankBe,
                               double kgPerS, double dt) {
        if (!(kgPerS > 0.0)) {
            return 0.0;
        }
        if (suctionSource == SuctionSource.SUPPRESSION_POOL) {
            return poolBe != null ? poolBe.pool().drawSuctionKg(kgPerS, dt) : 0.0;
        }
        double fromBuffer = incomingWater.drawKg(kgPerS * dt);
        return fromBuffer + (tankBe != null ? tankBe.drawKg(Math.max(0, kgPerS * dt - fromBuffer)) : 0);
    }

    // -----------------------------------------------------------------
    // Binding
    // -----------------------------------------------------------------

    public void markBindingDirty() {
        bindingDirty = true;
    }

    /**
     * Re-find the reactor, the pool and the tank, but not often.
     *
     * <p>The scan is a 49-cube, and a neighbour change fires on every redstone
     * edge. Rescanning on each of those would be tens of thousands of block
     * reads a tick for a machine whose neighbours simply blinked. So there are
     * two intervals: {@link #REBIND_INTERVAL_TICKS} while the reactor is missing
     * or something has said the binding changed, and the slow
     * {@link #BOUND_REBIND_INTERVAL_TICKS} sweep otherwise â€” which is what
     * eventually finds a suppression pool or a storage tank built after the
     * machine was placed.
     *
     * <p>The interval applies unconditionally, and especially when the machine
     * is NOT bound. Testing {@code bound &&} here meant an unbound pump â€” the
     * default state of every pump placed before its reactor exists â€” ran the
     * full 117,649-position scan on every one of the twenty ticks a second,
     * which is exactly the case the throttle was written to prevent.
     */
    private void maybeRebind(Level level) {
        if (ticksSinceRebind < Integer.MAX_VALUE) {
            ticksSinceRebind++;
        }
        boolean settled = !bindingDirty
                && isStillBound(level, reactorPos, BwrBlocks.REACTOR_CONTROLLER.get());
        if (ticksSinceRebind < (settled ? BOUND_REBIND_INTERVAL_TICKS : REBIND_INTERVAL_TICKS)) {
            return;
        }
        rebind(level);
        bindingDirty = false;
        ticksSinceRebind = 0;
    }

    private static boolean isStillBound(Level level, BlockPos pos,
                                        net.minecraft.world.level.block.Block block) {
        return pos != null && level.isLoaded(pos) && level.getBlockState(pos).is(block);
    }

    /**
     * Take this machine off its reactor's bus immediately. Called when the
     * block is broken, so the water stops on the same tick instead of a few
     * ticks later when the contribution would have gone stale anyway.
     *
     * <p>The pool-cooling duty is withdrawn here for the same reason. Staleness
     * would clear it within a few ticks on its own, but breaking a machine
     * should stop what it was doing on the tick it stops existing.
     */
    public void detach() {
        if (level == null) {
            return;
        }
        if (reactorPos != null) {
            EccsNetwork.withdraw(level, reactorPos, getBlockPos());
        }
        if (assemblyExhaustPoolPos != null && level.isLoaded(assemblyExhaustPoolPos)
                && level.getBlockEntity(assemblyExhaustPoolPos) instanceof SuppressionPoolBlockEntity exhaustPool) {
            exhaustPool.withdrawSteam(getBlockPos());
        }
        assemblyExhaustPoolPos = null;
        assemblySteamDrawKgPerS = 0;
        deliveredFlowKgPerS = 0;
        if (wasCoolingPool) {
            SuppressionPoolBlockEntity poolBe = pool(level);
            if (poolBe != null) {
                poolBe.withdrawRhrDuty(getBlockPos());
            }
            wasCoolingPool = false;
        }
    }

    private void rebind(Level level) {
        BlockPos from = getBlockPos();
        reactorPos = null;
        poolPos = null;
        tankPos = null;
        for (BlockPos p : BlockPos.betweenClosed(
                from.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                from.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (!level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (reactorPos == null && s.is(BwrBlocks.REACTOR_CONTROLLER.get())) {
                reactorPos = p.immutable();
            } else if (poolPos == null && s.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get())) {
                poolPos = p.immutable();
            } else if (tankPos == null && s.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())) {
                tankPos = p.immutable();
            }
        }
    }

    private ReactorControllerBlockEntity reactor(Level level) {
        if (reactorPos == null || !level.isLoaded(reactorPos)) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    private SuppressionPoolBlockEntity pool(Level level) {
        if (poolPos == null || !level.isLoaded(poolPos)) {
            return null;
        }
        return level.getBlockEntity(poolPos) instanceof SuppressionPoolBlockEntity p ? p : null;
    }

    private CondensateStorageTankBlockEntity tank(Level level) {
        if (tankPos == null || !level.isLoaded(tankPos)) {
            return null;
        }
        return level.getBlockEntity(tankPos) instanceof CondensateStorageTankBlockEntity t ? t : null;
    }

    // -----------------------------------------------------------------
    // Actuators
    // -----------------------------------------------------------------

    /**
     * Start or stop the machine. Bare, like every other actuator in this mod:
     * it checks nothing.
     *
     * <p>Its callers are the two things that carry a command the player gave â€”
     * {@code EccsPumpBlock.neighborChanged} following a redstone level, and the
     * peripheral following Lua. Nothing in the mod calls it off its own bat, and
     * nothing that calls it looks at a plant parameter first.
     *
     * <p>Every setter below is marshalled onto the server thread. Lua reaches
     * them from a CC computer thread, and {@code setChanged()} dispatches
     * neighbour updates into the world â€” see {@link PlantActuators}.
     */
    public void setRunning(boolean running) {
        PlantActuators.run(this, () -> {
            pump.setRunning(running);
            setChanged();
        });
    }

    public boolean isRunning() {
        return pump.isRunning();
    }

    /** Commanded flow as a fraction of rated, 0 to 1. */
    public void setFlowDemandFraction(double fraction) {
        PlantActuators.run(this, () -> {
            pump.setFlowDemandFraction(fraction);
            setChanged();
        });
    }

    public double getFlowDemandFraction() {
        return pump.getFlowDemandFraction();
    }

    public SuctionSource getSuctionSource() {
        return suctionSource;
    }

    /** Line the suction up on the pool or on the tank. Nothing swaps it for you. */
    public void setSuctionSource(SuctionSource source) {
        PlantActuators.run(this, () -> {
            this.suctionSource = source == null ? SuctionSource.SUPPRESSION_POOL : source;
            setChanged();
        });
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Line an RHR loop up for injection or for pool cooling. Ignored on the
     * machines that have no second job. One loop cannot do both at once, which
     * is the choice between saving the core now and keeping the heat sink you
     * will want in an hour.
     */
    public void setMode(Mode mode) {
        PlantActuators.run(this, () -> {
            if (mode != null && (mode == Mode.INJECTION || design.isPoolCoolingCapable())) {
                this.mode = mode;
                setChanged();
            }
        });
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        PlantActuators.run(this, () -> {
            this.computerControlled = computerControlled;
            if (computerControlled) panelControlled = false;
            setChanged();
        });
    }

    // -----------------------------------------------------------------
    // Measurements
    // -----------------------------------------------------------------

    public EccsDesign design() {
        return design;
    }

    public EccsPump pump() {
        return pump;
    }

    public MachineEnergy energy() {
        return energy;
    }

    public double getAssemblySteamDrawKgPerS() { return assemblySteamDrawKgPerS; }

    @Override public void setRemoved() {
        if (level != null && !level.isClientSide() && getBlockState().getBlock() instanceof ProcessAssembly) detach();
        super.setRemoved();
    }

    /** Water this machine actually put into the vessel or onto the fuel, kg/s. */
    public double getDeliveredFlowKgPerS() {
        return deliveredFlowKgPerS;
    }

    /**
     * Flow the pump produced that the suction source could not supply, kg/s.
     * Non-zero means the tank is empty or the pool is down to the level where
     * the pumps lose suction.
     */
    public double getSuctionShortfallKgPerS() {
        return suctionShortfallKgPerS;
    }

    public boolean hasReactor() {
        return reactorPos != null;
    }

    public boolean hasPool() {
        return poolPos != null;
    }

    public boolean hasTank() {
        return tankPos != null;
    }

    public String connectionStatus() {
        if(getBlockState().getBlock() instanceof TurbineAssemblyBlock
                || getBlockState().getBlock() instanceof PumpAssemblyBlock a && a.isFull(getBlockState())) return assemblyConnectionStatus;
        if(isPoolCooling()) return poolPos==null?"Pool disconnected":"Pool connected";
        return reactorPos==null?"Water discharge disconnected":"Water discharge connected";
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (getBlockState().getBlock() instanceof ProcessAssembly) {
            out.add(assemblyConnectionStatus);
            if (design.drive() == EccsDesign.Drive.STEAM_TURBINE)
                out.add(String.format(Locale.ROOT,"Physical steam admission/exhaust: %.3f kg/s",assemblySteamDrawKgPerS));
        }
        out.add(design.displayName() + ": " + design.drive() + ", "
                + design.delivery() + ", rated "
                + String.format(Locale.ROOT, "%.0f kg/s", design.ratedFlowKgPerS()));
        out.add(String.format(Locale.ROOT,
                "%s at %.0f%% demand, speed %.0f%%, delivering %.1f kg/s (%.1f kg/s available)",
                pump.isRunning() ? "RUNNING" : "stopped",
                pump.getFlowDemandFraction() * 100.0,
                pump.getSpeedFraction() * 100.0,
                deliveredFlowKgPerS,
                pump.getAvailableFlowKgPerS()));
        out.add(String.format(Locale.ROOT,
                "Discharge %.0f psi against %.0f psi of developed head; suction on the %s",
                pump.differentialPressurePsi(), pump.getDevelopedHeadPsi(),
                suctionSource == SuctionSource.SUPPRESSION_POOL ? "suppression pool" : "storage tank"));
        if (pump.isAboveShutoffHead()) {
            out.add("Vessel pressure exceeds the head this pump can develop; it is delivering nothing.");
        }
        if (design.drive() == EccsDesign.Drive.STEAM_TURBINE) {
            out.add(String.format(Locale.ROOT, "Turbine drive taking %.2f kg/s of steam, no AC required",
                    pump.getSteamDemandKgPerS()));
        } else {
            out.add(String.format(Locale.ROOT, "Motor drawing %,d / %,d FE (rated %,d FE/t)",
                    energy.getEnergyStored(), energy.getMaxEnergyStored(),
                    EccsPower.fePerTickFromWatts(design.motorRatingWatts())));
        }
        if (pump.isDriveLimited()) {
            out.add("Drive cannot reach full speed on what it is being given.");
        }
        if (isPoolCooling()) {
            out.add("Lined up for suppression pool cooling; it is not injecting.");
        }
        if (suctionShortfallKgPerS > 0.0) {
            out.add(String.format(Locale.ROOT, "Suction source is short by %.1f kg/s.",
                    suctionShortfallKgPerS));
        }
        if (reactorPos == null && !(getBlockState().getBlock() instanceof ProcessAssembly)) {
            out.add("No reactor controller found within " + SEARCH_RADIUS + " blocks.");
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        dev.bwr.mod.reactor.ReactorStateNbt.putDoubles(tag, "Pump", pump.toArray());
        tag.putString("Suction", suctionSource.getSerializedName());
        tag.put("IncomingWater", incomingWater.save(registries));
        tag.putString("Mode", mode.name());
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putBoolean("PanelControlled", panelControlled);
        tag.putInt("Energy", energy.getEnergyStored());
        if (reactorPos != null) {
            tag.putLong("Reactor", reactorPos.asLong());
        }
        if (poolPos != null) {
            tag.putLong("Pool", poolPos.asLong());
        }
        if (tankPos != null) {
            tag.putLong("Tank", tankPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Pump")) {
            pump.fromArray(dev.bwr.mod.reactor.ReactorStateNbt.getDoubles(tag, "Pump"));
        }
        suctionSource = SuctionSource.byName(tag.getString("Suction"));
        incomingWater.load(registries, tag.getCompound("IncomingWater"));
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.INJECTION;
        }
        computerControlled = tag.getBoolean("ComputerControlled");
        panelControlled = !computerControlled && tag.getBoolean("PanelControlled");
        energy.setStored(tag.getInt("Energy"));
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        poolPos = tag.contains("Pool") ? BlockPos.of(tag.getLong("Pool")) : null;
        tankPos = tag.contains("Tank") ? BlockPos.of(tag.getLong("Tank")) : null;
        bindingDirty = true;
    }
}
