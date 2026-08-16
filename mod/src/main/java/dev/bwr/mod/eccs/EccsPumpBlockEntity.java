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
import net.neoforged.neoforge.energy.EnergyStorage;

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

    // Commands and published measurements are volatile because Lua reads them
    // from a CC computer thread. Writes are marshalled onto the server thread by
    // PlantActuators; these give the reads the matching visibility guarantee.
    private volatile SuctionSource suctionSource = SuctionSource.SUPPRESSION_POOL;
    private volatile Mode mode = Mode.INJECTION;

    /** When true, redstone is ignored and Lua owns the start command. */
    private volatile boolean computerControlled;

    private BlockPos reactorPos;
    private BlockPos poolPos;
    private BlockPos tankPos;
    private boolean bindingDirty = true;
    private int ticksSinceRebind = REBIND_INTERVAL_TICKS;
    private boolean wasCoolingPool;

    /** Last tick's delivered figures, kept for the panel and the peripheral. */
    private volatile double deliveredFlowKgPerS;
    private volatile double suctionShortfallKgPerS;

    /**
     * Accepts energy and never gives it back. Sized from the machine's real
     * motor rating through {@link EccsPower}, so a turbine-driven machine
     * genuinely refuses electricity rather than politely not needing it.
     */
    private static final class MachineEnergy extends EnergyStorage {

        MachineEnergy(int fePerTick) {
            super(Math.max(1, fePerTick * 20), Math.max(0, fePerTick), 0);
        }

        void drain(int amount) {
            this.energy = Math.max(0, this.energy - amount);
        }

        void setStored(int stored) {
            this.energy = Math.max(0, Math.min(this.capacity, stored));
        }
    }

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
        // callers in the same tick — this exhaust and the relief valves — would
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

    /**
     * Publish this loop's pool-cooling duty to the pool.
     *
     * <p>Reported, not assigned. Writing {@code setRhrDuty} straight into the
     * pool was the pattern {@link EccsNetwork}'s javadoc calls out as wrong: the
     * clearing write only happens on a later tick of <i>this</i> machine, so a
     * pump that was broken or whose chunk unloaded left the pool rejecting up to
     * 30 MW through a heat exchanger that no longer exists — persisted to NBT
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
            // reported a negative volume and clamped to zero — a pump that could
            // never draw a drop however full its pool was.
            return poolBe.pool().getAvailableSuctionKg();
        }
        return tankBe != null ? tankBe.storedKg() : 0.0;
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
        return tankBe != null ? tankBe.drawKg(kgPerS * dt) : 0.0;
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
     * reads a tick for a machine whose neighbours simply blinked. So a rescan
     * happens only when something is actually unbound or has been replaced, and
     * never more than once every {@link #REBIND_INTERVAL_TICKS}.
     */
    private void maybeRebind(Level level) {
        if (ticksSinceRebind < Integer.MAX_VALUE) {
            ticksSinceRebind++;
        }
        boolean bound = isStillBound(level, reactorPos, BwrBlocks.REACTOR_CONTROLLER.get());
        if (!bindingDirty && bound) {
            return;
        }
        // The interval applies unconditionally, and especially when the machine
        // is NOT bound. Testing `bound &&` here meant an unbound pump — the
        // default state of every pump placed before its reactor exists — ran the
        // full 117,649-position scan on every one of the twenty ticks a second,
        // which is exactly the case the throttle was written to prevent.
        if (ticksSinceRebind < REBIND_INTERVAL_TICKS) {
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
        if (reactorPos == null) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    private SuppressionPoolBlockEntity pool(Level level) {
        if (poolPos == null) {
            return null;
        }
        return level.getBlockEntity(poolPos) instanceof SuppressionPoolBlockEntity p ? p : null;
    }

    private CondensateStorageTankBlockEntity tank(Level level) {
        if (tankPos == null) {
            return null;
        }
        return level.getBlockEntity(tankPos) instanceof CondensateStorageTankBlockEntity t ? t : null;
    }

    // -----------------------------------------------------------------
    // Actuators
    // -----------------------------------------------------------------

    /**
     * Start or stop the machine. Bare, like every other actuator in this mod:
     * it checks nothing, and nothing in the mod ever calls it.
     *
     * <p>Every setter below is marshalled onto the server thread. Lua reaches
     * them from a CC computer thread, and {@code setChanged()} dispatches
     * neighbour updates into the world — see {@link PlantActuators}.
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

    public EnergyStorage energy() {
        return energy;
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

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(design.displayName() + " — " + design.drive() + ", "
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
        if (reactorPos == null) {
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
        tag.putString("Mode", mode.name());
        tag.putBoolean("ComputerControlled", computerControlled);
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
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.INJECTION;
        }
        computerControlled = tag.getBoolean("ComputerControlled");
        energy.setStored(tag.getInt("Energy"));
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        poolPos = tag.contains("Pool") ? BlockPos.of(tag.getLong("Pool")) : null;
        tankPos = tag.contains("Tank") ? BlockPos.of(tag.getLong("Tank")) : null;
        bindingDirty = true;
    }
}
