package dev.bwr.mod.rods;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The block-entity shell around one {@link ControlRodDriveHardware}.
 *
 * <p>All the interesting behaviour lives in the hardware object, which is plain
 * Java and unit-testable. This class only does the Minecraft parts: holding the
 * energy and water buffers, persisting them, and telling the controller it
 * exists.
 *
 * <h2>Fail-safe, and why it matters</h2>
 * Normal rod motion needs power <b>and</b> water. Scram fires from the
 * pre-charged accumulator on <i>loss</i> of signal, so a total loss of power
 * drives rods <b>in</b> — the failure mode of the control system is the safe
 * state. But the accumulator only recharges when both supplies are present,
 * which means scram capability decays even with power intact if the water
 * supply is lost. A drive with a flat accumulator and no power is a stuck rod:
 * a consequence of neglect, never a dice roll.
 */
public class ControlRodDriveBlockEntity extends BlockEntity implements ControlRodDriveAccess {

    /** One game tick, seconds. The drive's own clock, independent of the multiblock. */
    private static final double TICK_SECONDS = 0.05;

    private final ControlRodDriveHardware hardware = new ControlRodDriveHardware();

    /** Energy face, wired up by {@link ControlRodDriveCapabilities}. */
    private final DriveEnergy energy = new DriveEnergy();

    /** Water face, wired up by {@link ControlRodDriveCapabilities}. */
    private final DriveWater water = new DriveWater();

    private BlockPos controllerPos;
    private int rodIndex = -1;

    public ControlRodDriveBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONTROL_ROD_DRIVE.get(), pos, state);
    }

    public ControlRodDriveHardware hardware() {
        return hardware;
    }

    /**
     * Spend this drive's supplies and accumulate its wear, once per tick,
     * whether or not the multiblock around it is formed.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  ControlRodDriveBlockEntity be) {
        be.hardware.tickHardware(TICK_SECONDS);
        // The buffers moved, so the chunk is no longer what is on disk. Same
        // reasoning and same cost as RecirculationPumpBlockEntity.tickPump: a
        // drive that spent its supplies and then unloaded without being marked
        // would come back with them.
        be.setChanged();
    }

    /** Called by the controller during validation to claim this drive. */
    public void attach(ReactorControllerBlockEntity controller, int index) {
        BlockPos pos = controller.getBlockPos();
        if (pos.equals(controllerPos) && index == rodIndex) {
            // Re-validation re-binds every drive, and after the periodic
            // revalidation timer that happens several times a minute on a
            // 177-rod core. Only mark the chunk dirty when the binding actually
            // moved, so a formed plant is not re-saving 177 unchanged block
            // entities for nothing.
            return;
        }
        this.controllerPos = pos;
        this.rodIndex = index;
        setChanged();
    }

    public boolean isAttached() {
        return rodIndex >= 0;
    }

    /**
     * Tell the controller its structure changed, because nothing else will.
     *
     * <p>Vanilla only fires {@code neighborChanged} on the six blocks touching
     * the one that changed, and a drive sits two blocks below the vessel floor —
     * never adjacent to a wall-mounted controller. Without this hook, mining a
     * drive left the multiblock formed with
     * {@code ControlRodDriveNetwork.drives[]} still holding the hardware of a
     * block entity that no longer exists in the world, reporting a full operable
     * count and driving a rod from a drive that is not there.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        notifyController();
    }

    private void notifyController() {
        if (level == null || level.isClientSide() || controllerPos == null) {
            return;
        }
        // Never reach into an unloaded chunk from here: setRemoved also fires on
        // chunk unload, and getBlockEntity would drag the controller's chunk
        // back in to tell it about a drive that is only going away temporarily.
        if (!level.isLoaded(controllerPos)) {
            return;
        }
        if (level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity controller) {
            controller.markStructureDirty();
        }
    }

    // --- Supplies -----------------------------------------------------

    /** The energy face for cables. @see ControlRodDriveCapabilities */
    public IEnergyStorage energy() {
        return energy;
    }

    /** The water face for pipes. @see ControlRodDriveCapabilities */
    public IFluidHandler water() {
        return water;
    }

    /**
     * Accepts energy and never gives it back. The buffer itself lives on the
     * hardware object in doubles; this is only the integer-FE face NeoForge
     * cables speak.
     */
    private final class DriveEnergy implements IEnergyStorage {

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            // Whole FE only, decided here rather than truncated afterwards, so
            // that a simulate and the execute that follows it agree exactly.
            // Truncating the hardware's answer instead would let a fractional
            // acceptance be taken from the cable and reported as zero.
            double space = hardware.getEnergyCapacityFe() - hardware.getEnergyStoredFe();
            int room = (int) Math.floor(Math.min(toReceive, Math.max(0.0, space)));
            if (room <= 0) {
                return 0;
            }
            int accepted = (int) hardware.receiveEnergyFe(room, simulate);
            if (accepted > 0 && !simulate) {
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, hardware.getEnergyStoredFe());
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, hardware.getEnergyCapacityFe());
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    /**
     * Accepts demineralised water and never gives it back. Water is consumed by
     * the purge flow into the vessel and by charging the accumulator, so a drive
     * with no pipe to it runs its line dry and stops answering normal motion
     * demands while its accumulator quietly stops recharging — SPEC 3.3's
     * non-obvious failure mode, and the reason this face exists.
     */
    private final class DriveWater implements IFluidHandler {

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            int stored = (int) hardware.getWaterStoredMb();
            return stored <= 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, stored);
        }

        @Override
        public int getTankCapacity(int tank) {
            return (int) Math.min(Integer.MAX_VALUE, hardware.getWaterCapacityMb());
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return stack.getFluid() == Fluids.WATER;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty() || resource.getFluid() != Fluids.WATER) {
                return 0;
            }
            // Whole millibuckets only; see DriveEnergy.receiveEnergy.
            double space = hardware.getWaterCapacityMb() - hardware.getWaterStoredMb();
            int room = (int) Math.floor(Math.min(resource.getAmount(), Math.max(0.0, space)));
            if (room <= 0) {
                return 0;
            }
            int accepted = (int) hardware.receiveWaterMb(room, action.simulate());
            if (accepted > 0 && action.execute()) {
                setChanged();
            }
            return accepted;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    // --- ControlRodDriveAccess ---------------------------------------

    @Override
    public int rodIndex() {
        return rodIndex;
    }

    /**
     * The <b>hydraulic</b> accumulator charge, mirrored out of the core by
     * {@link ControlRodDriveNetwork#postStep()}.
     *
     * <h2>Not the energy buffer, and the difference is scram capability</h2>
     * This used to return {@code energyStoredFe / energyCapacityFe}, which is a
     * different quantity in different units answering a different question. The
     * FE buffer is about three minutes of solenoid draw and it is full whenever
     * a cable is attached; the accumulator is the stored water pressure that
     * physically pushes the piston, it is spent by a scram, and
     * {@code ReactorCore} owns it — {@link ControlRodDriveHardware} says as much
     * in its own division-of-labour comment, and
     * {@link ControlRodDriveHardware#getAccumulatorCharge()} is the field the
     * network writes it into every tick.
     *
     * <p>The two only look alike while nothing is wrong. Fire a scram and the
     * accumulators dump to zero while the FE buffers stay full, so the old
     * reading showed a core of fully charged accumulators seconds after they
     * had all been spent — and this number, not the energy buffer, is what
     * {@code SPEC.md} section 3.3's "how many rods will actually insert if I
     * scram right now" is computed from. They also diverge the other way: lose
     * the water supply with the bus energised and the accumulator bleeds away
     * while the FE buffer reads 100%, which is the exact failure mode the drive
     * model exists to make visible.
     *
     * <p>An unbound drive reports 1.0, which is correct rather than a
     * placeholder: a drive sitting in an unformed structure with power and water
     * really does hold a charged accumulator. {@link #energyStoredFe()} and
     * {@link #energyCapacityFe()} publish the FE buffer for anyone who wants it.
     */
    @Override
    public double accumulatorCharge() {
        return hardware.getAccumulatorCharge();
    }

    @Override
    public boolean powered() {
        return hardware.isPowered();
    }

    @Override
    public boolean waterSupplied() {
        return hardware.isWaterSupplied();
    }

    @Override
    public double health() {
        return hardware.getHealth();
    }

    @Override
    public boolean failed() {
        return hardware.isFailed();
    }

    @Override
    public boolean canPerformNormalMotion() {
        return hardware.canPerformNormalMotion();
    }

    @Override
    public double energyStoredFe() {
        return hardware.getEnergyStoredFe();
    }

    @Override
    public double energyCapacityFe() {
        return hardware.getEnergyCapacityFe();
    }

    @Override
    public double energyDrawFePerTick() {
        return ControlRodDriveHardware.IDLE_DRAW_FE_PER_SECOND / 20.0;
    }

    @Override
    public double waterStoredMb() {
        return hardware.getWaterStoredMb();
    }

    @Override
    public double waterCapacityMb() {
        return hardware.getWaterCapacityMb();
    }

    @Override
    public int notchIndex() {
        if (controllerPos == null || level == null || rodIndex < 0) {
            return 0;
        }
        if (level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c
                && c.core() != null) {
            return c.core().getRodNotchIndex(rodIndex);
        }
        return 0;
    }

    @Override
    public int notchLabel() {
        return notchIndex() * dev.bwr.core.PhysicalConstants.ROD_NOTCH_STEP;
    }

    // --- Persistence --------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RodIndex", rodIndex);
        if (controllerPos != null) {
            tag.putLong("Controller", controllerPos.asLong());
        }
        tag.putDouble("EnergyFe", hardware.getEnergyStoredFe());
        tag.putDouble("WaterMb", hardware.getWaterStoredMb());
        tag.putDouble("Health", hardware.getHealth());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rodIndex = tag.contains("RodIndex") ? tag.getInt("RodIndex") : -1;
        controllerPos = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
        hardware.setEnergyStoredFe(tag.getDouble("EnergyFe"));
        hardware.setWaterStoredMb(tag.getDouble("WaterMb"));
        if (tag.contains("Health")) {
            hardware.setHealth(tag.getDouble("Health"));
        }
    }
}
