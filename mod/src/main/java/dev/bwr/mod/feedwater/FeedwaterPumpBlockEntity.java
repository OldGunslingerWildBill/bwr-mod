package dev.bwr.mod.feedwater;

import dev.bwr.core.ReactorCore;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.core.eccs.EccsPump;
import dev.bwr.core.eccs.PumpDesign;
import dev.bwr.core.feedwater.FeedwaterDesign;
import dev.bwr.core.feedwater.FeedwaterHeating;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.EccsNetwork;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.eccs.MachineEnergy;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorStateNbt;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The block entity behind a reactor feed pump — {@code SPEC.md} section 15.
 *
 * <p>Thin, in the same way {@code EccsPumpBlockEntity} is thin: all the pump
 * behaviour is {@link EccsPump} in the physics module, which has no Minecraft on
 * its classpath and which is a general two-drive centrifugal pump rather than
 * anything to do with emergency cooling. This class finds the reactor, finds
 * water, moves energy and mass across the boundary, and posts what it delivered
 * to the reactor's bus.
 *
 * <h2>Where the water comes from</h2>
 * A feed pump takes condensate, and in this mod condensate is whatever the
 * player pipes in. There is no condenser block and there is not going to be one:
 * the Mekanism turbine <b>is</b> the condenser, it already receives this plant's
 * steam through the turbine steam outlet, and the water it gives back is exactly
 * the water that belongs in the feedwater line. So the pump exposes a plain
 * NeoForge fluid tank at the model's suction flange. Saved compact machines
 * retain their original all-face access.
 *
 * <p>A condensate storage tank within reach is used as well, after the pump's
 * own buffer runs dry. That is not a fallback bolted on for convenience — it is
 * how a real plant starts up, with the feed pumps on CST suction before there is
 * any steam to condense.
 *
 * <h2>What it does not do</h2>
 * It never starts itself, never stops itself, and has no idea what the vessel
 * level is. There is no level element, no steam flow element, no three-element
 * controller, no minimum flow protection and no trip. {@link #setRunning} and
 * {@link #setFlowDemandFraction} are driven by a redstone signal or by the
 * player's Lua and by nothing else. If nobody wrote the control program, the
 * level goes wherever boil-off and a fixed demand take it.
 *
 * <h2>The couplings that make the choice of pump matter</h2>
 * All of these fall out of the physics rather than being written as rules: a
 * motor drive stops dead without a bus and it is the largest electrical load in
 * the plant; a turbine drive costs no electricity but takes steam out of the
 * vessel to run, so it cannot start on a cold plant and it fades away as the
 * vessel depressurises; and the shutoff head means neither of them puts a drop
 * into an over-pressurised vessel however hard it turns.
 */
public class FeedwaterPumpBlockEntity extends BlockEntity {

    /** How far a pump looks for its reactor and for a condensate storage tank. */
    private static final int SEARCH_RADIUS = 24;

    /** Shortest interval between full neighbourhood scans, ticks. */
    private static final int REBIND_INTERVAL_TICKS = 40;

    /**
     * Interval between rescans once the pump has its reactor, ticks. Thirty
     * seconds, matching the ECCS machines, and it is what eventually finds a
     * condensate storage tank built after the pump was.
     */
    private static final int BOUND_REBIND_INTERVAL_TICKS = 600;

    /**
     * Suction buffer, millibuckets — and therefore kilograms, on the same
     * one-mB-is-one-kg identity {@code CondensateStorageTankBlockEntity}
     * explains.
     *
     * <p>Sized at roughly two seconds of one pump's rated flow. Big enough that
     * an ordinary pipe delivering in lumps does not make the pump stutter, and
     * far too small to be a water supply in its own right: a pump whose
     * condensate return has stopped runs this dry in a couple of seconds and
     * then delivers what the pipe delivers, which is the honest behaviour.
     */
    public static final int SUCTION_BUFFER_MB = 2_000;

    private final FeedwaterDesign design;
    private final EccsPump pump;
    private final MachineEnergy energy;

    /**
     * The pump's own suction buffer. Water only — a feed pump is not a place to
     * put lava, and refusing it here is cheaper than explaining it later.
     */
    private final FluidTank suction = new FluidTank(SUCTION_BUFFER_MB,
            stack -> stack.getFluid() == Fluids.WATER) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    // Commands and published measurements are volatile because Lua reads them
    // from a CC computer thread. Writes are marshalled onto the server thread by
    // PlantActuators; these give the reads the matching visibility guarantee.

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
        pump.setSpeedDemandFraction(fraction); setChanged();
    }


    private BlockPos reactorPos;
    private BlockPos tankPos;
    private boolean bindingDirty = true;
    private int ticksSinceRebind = REBIND_INTERVAL_TICKS;

    /**
     * Sub-kilogram remainder already handed to the pump but not yet taken out of
     * {@link #suction}, carried between ticks.
     *
     * <p>The same device {@code CondensateStorageTankBlockEntity.drawKg} uses and
     * for the same reason, which is sharper here than it is there. A fluid tank
     * counts in whole millibuckets and a tick is a twentieth of a second, so a
     * pump delivering 10 kg/s wants half a kilogram per tick — and flooring that
     * to whole units delivers <b>nothing, ever</b>. Feedwater would work at full
     * demand and silently not work at low demand, which is precisely the state a
     * plant is in during a startup.
     *
     * <p>It is a debt against the buffer contents, so it is subtracted from what
     * is available and it survives a save.
     */
    private double pendingBufferDrawKg;

    /** Last tick's figures, kept for the panel, the peripheral and the chat readout. */
    private volatile double deliveredFlowKgPerS;
    private volatile double suctionShortfallKgPerS;
    private volatile double steamDrawKgPerS;
    private volatile String assemblyConnections = "Awaiting pipe survey";

    public FeedwaterPumpBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.FEEDWATER_PUMP.get(), pos, state);
        this.design = FeedwaterPumpBlock.designOf(state);
        this.pump = new EccsPump(design);
        this.energy = new MachineEnergy(EccsPower.fePerTickFromWatts(design.motorRatingWatts()));
        EccsNetwork.ensureListenerRegistered();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FeedwaterPumpBlockEntity be) {
        be.tick(level);
    }

    // -----------------------------------------------------------------
    // The tick
    // -----------------------------------------------------------------

    private void tick(Level level) {
        if(getBlockState().getBlock() instanceof PumpAssemblyBlock assembly && assembly.isFull(getBlockState())) {
            tickAssembly(level,assembly);
            return;
        }
        maybeRebind(level);

        final double dt = 0.05;
        ReactorControllerBlockEntity reactor = reactor(level);
        ReactorCore core = reactor != null ? reactor.core() : null;
        CondensateStorageTankBlockEntity tankBe = tank(level);

        double vesselPsig = core != null ? core.getPressurePsig() : 0.0;

        // What the pump is pushing into, and what it is pushing from. Suction is
        // effectively atmospheric: the buffer is a pipe run, not a pressurised
        // feed train, so the pump does the entire lift from zero to dome
        // pressure on its own. That is why its shaft rating is what it is.
        pump.setVesselPressurePsig(vesselPsig);
        pump.setSuctionPressurePsig(0.0);
        pump.setExhaustPressurePsig(
                Saturation.psigFromPsia(FeedwaterDesign.DRIVE_EXHAUST_PSIA));

        double suctionTemperatureC = CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C;
        pump.setSuctionTemperatureC(suctionTemperatureC);
        pump.setSuctionFlowLimitKgPerS(availableSuctionKg(tankBe) / dt);

        pump.setElectricalPowerAvailableWatts(Math.min(
                EccsPower.wattsFromFePerTick(energy.getEnergyStored()),
                design.motorRatingWatts()));

        pump.step(dt);

        // Spend what the motor actually drew. A turbine pump draws nothing and
        // its buffer never moves, which is the whole reason it survives a
        // blackout — and the reason a cable run to one visibly does nothing.
        int spend = EccsPower.fePerTickFromWatts(pump.getElectricalDemandWatts());
        if (spend > 0) {
            energy.drain(spend);
        }

        // No reactor to feed means no water leaves the suction. A pump running
        // on nothing is recirculating through its own minimum flow line.
        double wantedKgPerS = (reactor != null && core != null) ? pump.getFlowKgPerS() : 0.0;
        double gotKg = drawSuction(tankBe, wantedKgPerS, dt);
        deliveredFlowKgPerS = dt > 0.0 ? gotKg / dt : 0.0;
        suctionShortfallKgPerS = Math.max(0.0, wantedKgPerS - deliveredFlowKgPerS);
        steamDrawKgPerS = pump.getSteamDemandKgPerS();

        if (reactor != null && core != null) {
            // The relief claim is the drive type, not the current steam flow.
            // See reportFeedwater: a claim that switches off when the pump stops
            // latches the core's last relief figure in place.
            EccsNetwork.busFor(level, reactorPos).reportFeedwater(getBlockPos(),
                    level.getGameTime(), deliveredFlowKgPerS, suctionTemperatureC,
                    steamDrawKgPerS, design.drive() == PumpDesign.Drive.STEAM_TURBINE);
        }

        setChanged();
    }

    private void tickAssembly(Level level, PumpAssemblyBlock assembly) {
        final double dt=.05;
        var state=getBlockState();
        boolean complete=assembly.complete(level,getBlockPos(),state);
        var inlet=AssemblyPlumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_SUCTION);
        var discharge=AssemblyPlumbing.trace(level,getBlockPos(),state,AssemblyPort.WATER_DISCHARGE);
        var tank=complete ? AssemblyPlumbing.endpoint(level,inlet,CondensateStorageTankBlockEntity.class) : null;
        var delivery=complete ? AssemblyPlumbing.waterReceiver(level,discharge) : null;
        if(delivery!=null && !delivery.isFormed()) delivery=null;
        BlockPos next=delivery==null?null:delivery.getBlockPos();
        if(reactorPos!=null && !reactorPos.equals(next)) EccsNetwork.withdraw(level,reactorPos,getBlockPos());
        reactorPos=next; tankPos=tank==null?null:tank.getBlockPos();
        pump.setVesselPressurePsig(delivery==null?0:delivery.core().getPressurePsig());
        pump.setSuctionPressurePsig(0);
        double temperature=CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C;
        pump.setSuctionTemperatureC(temperature);
        pump.setSuctionFlowLimitKgPerS(delivery==null || !inlet.valid()?0:
                Math.min(availableSuctionKg(tank)/dt,design.ratedFlowKgPerS()*Math.min(inlet.opening(),discharge.opening())));
        pump.setElectricalPowerAvailableWatts(Math.min(EccsPower.wattsFromFePerTick(energy.getEnergyStored()),design.motorRatingWatts()));
        steamDrawKgPerS=0;
        if(design.drive()==PumpDesign.Drive.STEAM_TURBINE) {
            var admission=AssemblyPlumbing.trace(level,getBlockPos(),state,AssemblyPort.STEAM_INLET);
            var exhaust=AssemblyPlumbing.trace(level,getBlockPos(),state,AssemblyPort.STEAM_EXHAUST);
            var nozzles=AssemblyPlumbing.nozzles(level,admission);
            var source=AssemblyPlumbing.source(level,nozzles);
            var sink=complete?AssemblyPlumbing.endpoint(level,exhaust,TurbineSteamOutletBlockEntity.class):null;
            if(sink!=null) sink.connectPumpExhaust();
            double available=complete && source!=null && sink!=null && pump.isRunning()
                    ? Math.min(nozzles.stream().mapToDouble(n -> n.getLastFlowKgPerS()).sum(),
                        Math.min(design.maximumSteamKgPerS()*Math.min(admission.opening(),exhaust.opening()),sink.pumpExhaustCapacityKgPerS(level.getGameTime()))) : 0;
            pump.setSteamInletPressurePsig(source==null?0:source.core().getPressurePsig());
            pump.setExhaustPressurePsig(Saturation.psigFromPsia(FeedwaterDesign.DRIVE_EXHAUST_PSIA));
            pump.setSteamSupplyLimitKgPerS(available);
            double[] before=pump.toArray(); pump.step(dt);
            double wanted=pump.getSteamDemandKgPerS();
            for(var nozzle:nozzles) if(steamDrawKgPerS<wanted)
                steamDrawKgPerS+=nozzle.claimFlowKgPerS(level.getGameTime(),wanted-steamDrawKgPerS);
            if(steamDrawKgPerS+1e-12<wanted) { pump.fromArray(before); pump.setSteamSupplyLimitKgPerS(steamDrawKgPerS); pump.step(dt); }
            if(sink!=null) sink.receivePumpExhaustKgPerS(level.getGameTime(),steamDrawKgPerS);
        } else pump.step(dt);
        energy.drain(EccsPower.fePerTickFromWatts(pump.getElectricalDemandWatts()));
        double wanted=delivery==null?0:pump.getFlowKgPerS();
        deliveredFlowKgPerS=drawSuction(tank,wanted,dt)/dt;
        suctionShortfallKgPerS=Math.max(0,wanted-deliveredFlowKgPerS);
        if(delivery!=null) EccsNetwork.busFor(level,reactorPos).reportFeedwater(getBlockPos(),level.getGameTime(),deliveredFlowKgPerS,temperature,0,false);
        assemblyConnections="Discharge: "+(delivery==null?"no connected reactor":"connected")
                +"; water: "+(tank==null?"suction buffer":"connected storage tank and suction buffer");
        setChanged();
    }

    @Override public void setRemoved() {
        if(level!=null && !level.isClientSide() && reactorPos!=null) EccsNetwork.withdraw(level,reactorPos,getBlockPos());
        super.setRemoved();
    }

    /** Water the pump could take this tick from every source it has, kg. */
    private double availableSuctionKg(CondensateStorageTankBlockEntity tankBe) {
        double own = Math.max(0.0, suction.getFluidAmount() - pendingBufferDrawKg);
        double tank = tankBe != null ? tankBe.storedKg() : 0.0;
        return own + tank;
    }

    /**
     * Take water for the suction: the pump's own buffer first, then a condensate
     * storage tank if one is bound.
     *
     * <p>Own buffer first on purpose. It is the pipe from the condenser, it
     * refills continuously while the plant is running, and draining it before
     * the tank means the finite, deliberately-scarce CST inventory is only spent
     * when the return has actually failed — which is the state a player wants to
     * be able to see in the tank level rather than have quietly papered over.
     *
     * @return kilograms actually supplied, less than asked for once the sources
     *         are running out
     */
    private double drawSuction(CondensateStorageTankBlockEntity tankBe, double kgPerS, double dt) {
        if (!(kgPerS > 0.0)) {
            return 0.0;
        }
        double wantedKg = kgPerS * dt;

        // The buffer counts in whole millibuckets and a tick's draw is usually
        // not a whole number of them, so the remainder is carried rather than
        // rounded away. See pendingBufferDrawKg: flooring here delivers nothing
        // at all below 20 kg/s.
        double bufferAvailable = suction.getFluidAmount() - pendingBufferDrawKg;
        double fromBuffer = Math.max(0.0, Math.min(wantedKg, bufferAvailable));
        if (fromBuffer > 0.0) {
            pendingBufferDrawKg += fromBuffer;
            int whole = (int) Math.floor(pendingBufferDrawKg);
            if (whole > 0) {
                FluidStack drained = suction.drain(whole, IFluidHandler.FluidAction.EXECUTE);
                // Anything the buffer could not honour stays on the books, so the
                // next call sees less than it thinks it has. That under-delivers
                // by strictly less than a kilogram and repays itself as soon as
                // the condensate return refills — the conservative direction.
                pendingBufferDrawKg -= drained.getAmount();
            }
        }

        double remaining = wantedKg - fromBuffer;
        double fromTank = (remaining > 0.0 && tankBe != null) ? tankBe.drawKg(remaining) : 0.0;
        return fromBuffer + fromTank;
    }

    // -----------------------------------------------------------------
    // Binding
    // -----------------------------------------------------------------

    public void markBindingDirty() {
        bindingDirty = true;
    }

    /**
     * Re-find the reactor and the condensate storage tank, but not often.
     *
     * <p>Two intervals, for the reason the ECCS machines have two: a 49-cube
     * scan on every redstone edge would be tens of thousands of block reads a
     * tick for a pump whose neighbours merely blinked, while never rescanning at
     * all leaves a pump bound to a tank that was broken and rebuilt one block
     * over, drawing from a position with nothing in it and reporting that it is
     * running the whole time.
     */
    private void maybeRebind(Level level) {
        if (ticksSinceRebind < Integer.MAX_VALUE) {
            ticksSinceRebind++;
        }
        boolean reactorMissing = reactorPos == null
                || !(level.isLoaded(reactorPos)
                     && level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity);
        int interval = (bindingDirty || reactorMissing)
                ? REBIND_INTERVAL_TICKS : BOUND_REBIND_INTERVAL_TICKS;
        if (ticksSinceRebind < interval) {
            return;
        }
        ticksSinceRebind = 0;
        bindingDirty = false;

        BlockPos foundReactor = null;
        BlockPos foundTank = null;
        BlockPos here = getBlockPos();
        for (BlockPos p : BlockPos.betweenClosed(
                here.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                here.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            BlockState s = level.getBlockState(p);
            if (foundReactor == null && s.is(BwrBlocks.REACTOR_CONTROLLER.get())) {
                foundReactor = p.immutable();
            } else if (foundTank == null && s.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())) {
                foundTank = p.immutable();
            }
            if (foundReactor != null && foundTank != null) {
                break;
            }
        }
        // A reactor that is merely out of a loaded chunk has not gone away, so
        // an unsuccessful scan only clears a binding it actually replaced.
        if (foundReactor != null) {
            reactorPos = foundReactor;
        }
        tankPos = foundTank != null ? foundTank : tankPos;
    }

    private ReactorControllerBlockEntity reactor(Level level) {
        if (reactorPos == null || !level.isLoaded(reactorPos)) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    private CondensateStorageTankBlockEntity tank(Level level) {
        if (tankPos == null || !level.isLoaded(tankPos)) {
            return null;
        }
        return level.getBlockEntity(tankPos) instanceof CondensateStorageTankBlockEntity t
                ? t : null;
    }

    /**
     * Drop this pump's contribution the moment the block stops existing, rather
     * than waiting for it to go stale. Losing a feed pump should show up in
     * vessel level on the next tick, not three ticks later.
     */
    public void detach() {
        if (level != null && reactorPos != null) {
            EccsNetwork.withdraw(level, reactorPos, getBlockPos());
        }
    }

    // -----------------------------------------------------------------
    // Commands. Every one of these is an actuator and none of them is a decision
    // -----------------------------------------------------------------

    public void setRunning(boolean running) {
        pump.setRunning(running);
        setChanged();
    }

    public boolean isRunning() {
        return pump.isRunning();
    }

    /**
     * Commanded flow, as a fraction of this pump's rated capacity. This is the
     * throttle: the player's level control program writes it, and the pump
     * delivers what the curve and the drive allow against the pressure it is
     * actually pushing into, which is generally less.
     */
    public void setFlowDemandFraction(double fraction) {
        pump.setFlowDemandFraction(fraction);
        setChanged();
    }

    public double getFlowDemandFraction() {
        return pump.getFlowDemandFraction();
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        this.computerControlled = computerControlled;
            if (computerControlled) panelControlled = false;
        setChanged();
    }

    // -----------------------------------------------------------------
    // Measurements
    // -----------------------------------------------------------------

    public FeedwaterDesign design() {
        return design;
    }

    public EccsPump pump() {
        return pump;
    }

    public MachineEnergy energy() {
        return energy;
    }

    /** The suction buffer, for the fluid capability and for pipes. */
    public FluidTank suction() {
        return suction;
    }

    /** Water this pump actually put into the vessel, kg/s. */
    public double getDeliveredFlowKgPerS() {
        return deliveredFlowKgPerS;
    }

    /**
     * How much of what the pump developed could not be supplied, kg/s. A
     * measurement of the condensate return against the demand, not a permissive:
     * nothing throttles the pump on its behalf.
     */
    public double getSuctionShortfallKgPerS() {
        return suctionShortfallKgPerS;
    }

    /** Steam the turbine drive is taking out of the vessel, kg/s. Zero on a motor. */
    public double getSteamDrawKgPerS() {
        return steamDrawKgPerS;
    }

    /** Water in the pump's own suction buffer, kg. */
    public double getSuctionBufferKg() {
        return suction.getFluidAmount();
    }

    /** True when this pump has a live reactor controller to deliver into. */
    public boolean isAttached() {
        if (reactorPos == null) {
            return false;
        }
        if (level == null || !level.isLoaded(reactorPos)) {
            // Out of a loaded chunk is not the same as gone, the same
            // distinction the rebind draws before it drops a binding.
            return true;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity;
    }

    public BlockPos getReactorPos() {
        return reactorPos;
    }

    /**
     * Temperature this pump's water would arrive at if it were the whole plant's
     * feedwater, degrees C. Published for the readout only — the figure the core
     * is actually given is computed on the bus from every pump's flow together,
     * because how hot the heater string runs depends on total steam flow and not
     * on any one machine.
     */
    public double getIndicatedFeedwaterTemperatureC() {
        return FeedwaterHeating.finalTemperatureC(
                CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C,
                deliveredFlowKgPerS / FeedwaterDesign.RATED_FEEDWATER_FLOW_KG_PER_S);
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if(getBlockState().getBlock() instanceof PumpAssemblyBlock b && b.isFull(getBlockState())) out.add(assemblyConnections);
        out.add(String.format(Locale.ROOT, "%s: %s, demand %.0f%%, delivering %.1f kg/s of %.0f rated",
                design.displayName(), pump.isRunning() ? "running" : "stopped",
                pump.getFlowDemandFraction() * 100.0, deliveredFlowKgPerS,
                design.ratedFlowKgPerS()));
        out.add(String.format(Locale.ROOT, "Shaft %.0f%%, developing %.0f psi against %.0f psi",
                pump.getSpeedFraction() * 100.0, pump.getDevelopedHeadPsi(),
                pump.differentialPressurePsi()));
        if (design.drive() == PumpDesign.Drive.STEAM_TURBINE) {
            out.add(String.format(Locale.ROOT, "Drive steam %.2f kg/s, exhausting to the condenser",
                    steamDrawKgPerS));
        } else {
            out.add(String.format(Locale.ROOT, "Motor buffer %,d / %,d FE, %,d FE/t at full load",
                    energy.getEnergyStored(), energy.getMaxEnergyStored(),
                    EccsPower.fePerTickFromWatts(design.motorRatingWatts())));
        }
        out.add(String.format(Locale.ROOT, "Suction buffer %,.0f / %d kg%s",
                getSuctionBufferKg(), SUCTION_BUFFER_MB,
                tankPos != null ? ", condensate storage tank behind it" : ", no storage tank found"));
        if (suctionShortfallKgPerS > 0.01) {
            out.add(String.format(Locale.ROOT,
                    "Short of suction by %.1f kg/s — the condensate return is not keeping up.",
                    suctionShortfallKgPerS));
        }
        if (pump.isAboveShutoffHead()) {
            out.add("Vessel pressure is above this pump's shutoff head. No water is entering.");
        }
        if (!isAttached()) {
            out.add(getBlockState().getBlock() instanceof PumpAssemblyBlock b && b.isFull(getBlockState())
                    ? "No formed reactor connected to the water discharge port."
                    : "No reactor controller within " + SEARCH_RADIUS + " blocks. Delivering nothing.");
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // The pump's own state includes the shaft speed, so a pump unloaded
        // mid-coastdown resumes mid-coastdown rather than restarting from rest.
        ReactorStateNbt.putDoubles(tag, "Pump", pump.toArray());
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putBoolean("PanelControlled", panelControlled);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.put("Suction", suction.writeToNBT(registries, new CompoundTag()));
        // A real debt against the buffer, so forgiving it on reload would create
        // water. Same reasoning as the condensate storage tank's PendingDraw.
        tag.putDouble("PendingDraw", pendingBufferDrawKg);
        if (reactorPos != null) {
            tag.putLong("Reactor", reactorPos.asLong());
        }
        if (tankPos != null) {
            tag.putLong("Tank", tankPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Pump")) {
            pump.fromArray(ReactorStateNbt.getDoubles(tag, "Pump"));
        }
        computerControlled = tag.getBoolean("ComputerControlled");
        panelControlled = !computerControlled && tag.getBoolean("PanelControlled");
        energy.setStored(tag.getInt("Energy"));
        if (tag.contains("Suction")) {
            suction.readFromNBT(registries, tag.getCompound("Suction"));
        }
        double pending = tag.getDouble("PendingDraw");
        pendingBufferDrawKg = Double.isFinite(pending) ? Math.max(0.0, pending) : 0.0;
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        tankPos = tag.contains("Tank") ? BlockPos.of(tag.getLong("Tank")) : null;
        bindingDirty = true;
    }
}
