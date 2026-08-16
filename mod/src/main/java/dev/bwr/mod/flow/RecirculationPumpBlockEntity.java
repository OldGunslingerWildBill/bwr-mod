package dev.bwr.mod.flow;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * An external recirculation pump — {@code SPEC.md} section 4.2.
 *
 * <p>A satellite block, not part of the multiblock proper: it announces itself
 * to a controller when placed and drops out when broken. An invalid or
 * unpowered pump contributes zero flow <b>without</b> invalidating the
 * multiblock. Losing a pump reduces flow; it must never scram the plant.
 *
 * <h2>Coastdown</h2>
 * Speed follows a first-order lag with an <i>asymmetric</i> time constant:
 * slower on deceleration than acceleration, modelling flywheel coastdown. This
 * is the most recognisable behaviour in a loss-of-power event and it is what
 * turns an instant flow loss into a survivable decay.
 *
 * <p>Nothing here decides anything. The commanded speed comes from a player —
 * a GUI slider or their Lua — and the pump does what it is told, limited only
 * by the power it is actually receiving.
 */
public class RecirculationPumpBlockEntity extends BlockEntity {

    /** Below this the pump simply does not turn. */
    public static final double MIN_FE_PER_TICK = 1_000.0;
    /** Power draw at full commanded speed. */
    public static final double MAX_FE_PER_TICK = 500_000.0;

    /** Spin-up time constant, seconds. */
    public static final double TAU_ACCELERATE_S = 3.0;
    /** Coastdown time constant, seconds. Deliberately longer — flywheel inertia. */
    public static final double TAU_DECELERATE_S = 11.0;

    /** Shortest interval between attempts to (re)find a controller, ticks. */
    private static final int REBIND_INTERVAL_TICKS = 40;

    /** How far a pump will look for a controller to attach to. */
    static final int SEARCH_RADIUS = 12;

    private double targetSpeedFraction;
    private double actualSpeedFraction;
    private double energyStoredFe;
    private double energyCapacityFe = MAX_FE_PER_TICK * 4.0;

    /** When true the GUI slider is greyed out and Lua owns the demand. */
    private boolean computerControlled;

    private BlockPos controllerPos;
    private int ticksSinceRebind = REBIND_INTERVAL_TICKS;

    /**
     * The Forge Energy face of the pump.
     *
     * <p>Without this the block has no {@code Capabilities.EnergyStorage.BLOCK}
     * at all, so no cable can find anything to push into,
     * {@link #receiveEnergyFe} is never called from anywhere,
     * {@link #maxAchievableSpeedFraction()} is pinned at zero and forced
     * circulation — the flow-control reactivity lever of {@code SPEC.md}
     * section 4.2 — does not exist in a running game. It is registered in
     * {@code EccsCapabilities.register}.
     *
     * <p>Deliberately a view over the existing {@code double} bookkeeping rather
     * than a NeoForge {@code EnergyStorage} field: the pump's buffer is spent by
     * {@link #tickPump} in fractional FE per tick and the coastdown depends on
     * that fraction, so the double is the authority and this only rounds at the
     * boundary where whole FE are actually exchanged.
     */
    private final IEnergyStorage energy = new IEnergyStorage() {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (maxReceive <= 0) {
                return 0;
            }
            // Capped at the pump's own rated draw, the way the ECCS machines'
            // buffers are capped at their motor ratings: the nameplate is what
            // the supply to this machine is sized for, so a grid that cannot
            // sustain it browns the pump out through
            // maxAchievableSpeedFraction() rather than topping the buffer up in
            // one enormous transfer.
            int offered = (int) Math.min(maxReceive, MAX_FE_PER_TICK);
            // Floor the room to whole FE before offering it, so the number we
            // report accepted is exactly the number we store. Accepting a
            // fraction and reporting the truncation would quietly create energy
            // in the pump on every transfer.
            double room = Math.max(0.0, energyCapacityFe - energyStoredFe);
            int whole = (int) Math.min(offered, Math.floor(room));
            if (whole <= 0) {
                return 0;
            }
            return (int) receiveEnergyFe(whole, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.max(0.0, Math.min(Integer.MAX_VALUE, Math.floor(energyStoredFe)));
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.max(0.0, Math.min(Integer.MAX_VALUE, Math.floor(energyCapacityFe)));
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public RecirculationPumpBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RECIRCULATION_PUMP.get(), pos, state);
    }

    /** The energy face, for the capability registration. */
    public IEnergyStorage energy() {
        return energy;
    }

    /**
     * The pump's own ticker. It does binding and nothing else — the speed
     * integration stays in {@link #tickPump}, which the controller drives inside
     * {@code gatherPumpFlow} so that pump speed and core flow move in the same
     * tick.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  RecirculationPumpBlockEntity be) {
        be.maybeRebind(level);
    }

    /**
     * Advance the pump one step. Called by the controller so that pump state and
     * core flow are updated in the same tick.
     */
    public void tickPump(double dtSeconds) {
        double achievable = maxAchievableSpeedFraction();
        double effectiveTarget = Math.min(targetSpeedFraction, achievable);

        double tau = effectiveTarget >= actualSpeedFraction ? TAU_ACCELERATE_S : TAU_DECELERATE_S;
        actualSpeedFraction += (effectiveTarget - actualSpeedFraction) * (dtSeconds / tau);
        if (actualSpeedFraction < 1.0e-4) {
            actualSpeedFraction = 0.0;
        }

        // Spend what the pump is actually drawing.
        double drawPerTick = MAX_FE_PER_TICK * actualSpeedFraction;
        energyStoredFe = Math.max(0.0, energyStoredFe - drawPerTick * dtSeconds * 20.0);
        setChanged();
    }

    /**
     * The fastest this pump could run on the energy available, 0..1. Below the
     * minimum draw it is zero — that is how a brownout slows the plant instead
     * of producing nonsense.
     */
    public double maxAchievableSpeedFraction() {
        double availablePerTick = Math.min(energyStoredFe, MAX_FE_PER_TICK);
        if (availablePerTick < MIN_FE_PER_TICK) {
            return 0.0;
        }
        return Math.min(1.0, availablePerTick / MAX_FE_PER_TICK);
    }

    /** True when the pump cannot reach what it was told to do. */
    public boolean isPowerLimited() {
        return targetSpeedFraction > maxAchievableSpeedFraction() + 1.0e-6;
    }

    // --- Commanded state ---------------------------------------------

    public double getTargetSpeedFraction() {
        return targetSpeedFraction;
    }

    /** Single authoritative demand, written by both the slider and Lua. */
    public void setTargetSpeedFraction(double fraction) {
        this.targetSpeedFraction = Math.min(1.0, Math.max(0.0, fraction));
        setChanged();
    }

    public double getActualSpeedFraction() {
        return actualSpeedFraction;
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        this.computerControlled = computerControlled;
        setChanged();
    }

    // --- Energy -------------------------------------------------------

    public double getEnergyStoredFe() {
        return energyStoredFe;
    }

    public double getEnergyCapacityFe() {
        return energyCapacityFe;
    }

    public double receiveEnergyFe(double offered, boolean simulate) {
        double accepted = Math.min(offered, energyCapacityFe - energyStoredFe);
        if (accepted <= 0.0) {
            return 0.0;
        }
        if (!simulate) {
            energyStoredFe += accepted;
            setChanged();
        }
        return accepted;
    }

    // --- Controller association ---------------------------------------

    public void bindController(ReactorControllerBlockEntity controller) {
        this.controllerPos = controller.getBlockPos();
        controller.addPump(getBlockPos());
        setChanged();
    }

    public BlockPos getControllerPos() {
        return controllerPos;
    }

    /**
     * Re-announce this pump to a controller, at most every
     * {@link #REBIND_INTERVAL_TICKS}.
     *
     * <p>Binding used to happen once, in {@code setPlacedBy}, and nowhere else.
     * That made the plant silently depend on build order: pumps placed before
     * the reactor controller existed never joined
     * {@code ReactorControllerBlockEntity}'s pump set, so core flow stayed at
     * zero forever with no message anywhere. Breaking and replacing the
     * controller block had the same effect, because the replacement starts with
     * an empty set and nothing ever re-announced. {@code setPlacedBy} also does
     * not fire for {@code /setblock}, piston movement or structure placement.
     * Binding is therefore derived from what is in the world, not from an event
     * that may never have happened.
     *
     * <p>The re-announcement is deliberately unconditional while bound:
     * {@code addPump} is a set insert, so repeating it is free, and it is what
     * repopulates a freshly placed controller without anyone scanning anything.
     * The 25-cube scan only runs when there is no live controller at
     * {@code controllerPos} at all.
     */
    private void maybeRebind(Level level) {
        if (ticksSinceRebind < Integer.MAX_VALUE) {
            ticksSinceRebind++;
        }
        if (ticksSinceRebind < REBIND_INTERVAL_TICKS) {
            return;
        }
        ticksSinceRebind = 0;

        ReactorControllerBlockEntity controller = null;
        if (controllerPos != null && level.isLoaded(controllerPos)
                && level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c) {
            controller = c;
        }
        if (controller == null) {
            controllerPos = null;
            controller = findController(level, getBlockPos());
        }
        if (controller != null) {
            bindController(controller);
        }
    }

    /** Nearest reactor controller within {@link #SEARCH_RADIUS}, or null. */
    static ReactorControllerBlockEntity findController(Level level, BlockPos from) {
        for (BlockPos p : BlockPos.betweenClosed(
                from.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                from.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (level.getBlockState(p).is(BwrBlocks.REACTOR_CONTROLLER.get())
                    && level.getBlockEntity(p) instanceof ReactorControllerBlockEntity c) {
                return c;
            }
        }
        return null;
    }

    // --- Persistence ---------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Target", targetSpeedFraction);
        // Actual speed is saved deliberately: a pump unloaded mid-coastdown must
        // resume mid-coastdown, per SPEC section 11.
        tag.putDouble("Actual", actualSpeedFraction);
        tag.putDouble("EnergyFe", energyStoredFe);
        tag.putBoolean("ComputerControlled", computerControlled);
        if (controllerPos != null) {
            tag.putLong("Controller", controllerPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetSpeedFraction = tag.getDouble("Target");
        actualSpeedFraction = tag.getDouble("Actual");
        energyStoredFe = tag.getDouble("EnergyFe");
        computerControlled = tag.getBoolean("ComputerControlled");
        controllerPos = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
    }
}
