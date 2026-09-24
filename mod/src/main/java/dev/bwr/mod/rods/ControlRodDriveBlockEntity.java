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

    @Override public void setChanged(){
        // Drives have no comparator output. Persist supply/wear changes without vanilla's
        // second-neighbour comparator scan, which can load the next chunk across a dense bank.
        if(level!=null)level.blockEntityChanged(worldPosition);
    }

    /**
     * Spend this drive's supplies and accumulate its wear, once per tick,
     * whether or not the multiblock around it is formed.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  ControlRodDriveBlockEntity be) {
        var bank=ControlRodDriveSupplies.at(level,pos);
        if(bank!=null)bank.balance(level.getGameTime());
        be.hardware.tickHardware(TICK_SECONDS);
        // The buffers moved, so the chunk is no longer what is on disk. Same
        // reasoning and same cost as RecirculationPumpBlockEntity.tickPump: a
        // drive that spent its supplies and then unloaded without being marked
        // would come back with them.
        be.setChanged();
    }

    /**
     * Called by the controller during validation to claim this drive.
     *
     * <p><b>The controller is taken for its position and never stored.</b> What
     * this drive keeps is a {@code BlockPos}, resolved back through
     * {@link Level#getBlockEntity} at the moment it is needed, exactly as
     * {@code TurbineSteamOutletBlockEntity} keeps its controller. Holding the
     * block entity itself would be the more convenient field and it is the wrong
     * one: a removed {@code BlockEntity} that is still strongly referenced keeps
     * its {@link Level} — and therefore every loaded chunk and entity in it —
     * reachable, and a 177-rod core would hand out 177 such references on every
     * revalidation. Keep it a position.
     */
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
        if(level!=null)ControlRodDriveSupplies.changed(level,worldPosition);
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

    private ControlRodDriveSupplies.Bank bank(){return ControlRodDriveSupplies.at(level,worldPosition);}

    /** Retained handles resolve the live bank even after a drive/chunk replacement. */
    private final class DriveEnergy implements IEnergyStorage {
        public int receiveEnergy(int offered,boolean simulate){var b=bank();return b==null?0:b.receive(offered,false,simulate);}
        public int extractEnergy(int requested,boolean simulate){return 0;}
        public int getEnergyStored(){var b=bank();return b==null?0:b.stored(false);}
        public int getMaxEnergyStored(){var b=bank();return b==null?0:b.capacity(false);}
        public boolean canExtract(){return false;}
        public boolean canReceive(){return bank()!=null;}
    }

    /** All ports in a bank resolve to one identity, preventing double reservation by a pipe tee. */
    private final class DriveWater implements dev.bwr.mod.world.LiveCapabilities.FluidProxy {
        public IFluidHandler current(){var b=bank();return b==null?null:b.water;}
        public int getTanks(){return 1;}
        public FluidStack getFluidInTank(int i){var f=current();return f==null?FluidStack.EMPTY:f.getFluidInTank(i);}
        public int getTankCapacity(int i){var f=current();return f==null?0:f.getTankCapacity(i);}
        public boolean isFluidValid(int i,FluidStack f){return i==0&&f.is(Fluids.WATER);}
        public int fill(FluidStack f,FluidAction action){var h=current();return h==null?0:h.fill(f,action);}
        public FluidStack drain(FluidStack f,FluidAction action){return FluidStack.EMPTY;}
        public FluidStack drain(int n,FluidAction action){return FluidStack.EMPTY;}
    }

    @Override public void onLoad(){super.onLoad();if(level!=null)ControlRodDriveSupplies.changed(level,worldPosition);}

    /** Accumulator state, health and rod motion remain local to this drive. */
    @Override public int rodIndex(){return rodIndex;}
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

    /**
     * What the drive actually spent on its last step, FE.
     *
     * <p>This returned {@code IDLE_DRAW_FE_PER_SECOND / 20}, a compile-time
     * constant of 5 FE/t, while {@link ControlRodDriveAccess#energyDrawFePerTick()}
     * promises a figure that is "higher while the accumulator is charging" —
     * which is the only part of a drive's electrical behaviour worth publishing.
     * A drive recharging after a scram draws nine times its idle figure
     * ({@link ControlRodDriveHardware#CHARGING_DRAW_FE_PER_SECOND} is 800 FE/s
     * against 100 idle), and it is 177 of them doing it at once that browns out
     * a bus shared with the recirculation pumps. A constant 5 FE/t reports the
     * one number that is never interesting and hides the one that is.
     *
     * <p>{@link ControlRodDriveHardware#tickHardware} has been computing the
     * real figure every tick all along and storing it in
     * {@code lastEnergyDrawFePerTick}, where it had no readers at all. This is
     * that field, which is a measurement of the drive rather than a restatement
     * of a constant. It reads zero for the one tick between a drive being
     * constructed and first ticking, which is honest: it has not drawn anything
     * yet.
     */
    @Override
    public double energyDrawFePerTick() {
        return hardware.getLastEnergyDrawFe();
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
        // Same rule as notifyController: never reach into an unloaded chunk.
        // getBlockEntity drags the controller's chunk back in to answer, and
        // this is a readout — a player right-clicking a drive, or a peripheral
        // polling one, must not be able to load chunks by asking questions.
        if (!level.isLoaded(controllerPos)) {
            return 0;
        }
        if (level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c
                && c.core() != null) {
            return c.core().getRodNotchIndex(rodIndex);
        }
        return 0;
    }

    // --- Readout ------------------------------------------------------

    /**
     * The drive's condition, for the right-click readout — {@code SPEC.md}
     * section 3.3's per-drive supply and wear figures.
     *
     * <p>This exists because none of them were reachable. Every measurement on
     * {@link ControlRodDriveAccess} was implemented and then read by nothing at
     * all: the panel gets its four aggregate counts straight off
     * {@link ControlRodDriveNetwork}, and there is no drive peripheral and no
     * drive screen. So the entire mechanic the drive model was built for — a
     * water supply you can lose without noticing, an accumulator that quietly
     * stops recharging when you do, a collet you can grind flat by dry
     * stroking — was invisible one drive at a time. A player could see that 3
     * of 177 drives were inoperable and had no way whatsoever to find out
     * <i>which</i> three or why. Right-clicking the drive is how every other
     * machine block in this mod answers that question, and now this one does
     * too.
     *
     * <p>Measurements only. It reports what the hardware is, never whether that
     * is acceptable: there is no line here that says a drive is unsafe, that
     * scram capability is short, or that the player ought to do anything. The
     * one derived statement, "cannot index a notch under normal control", is the
     * hardware fact {@link ControlRodDriveAccess#canPerformNormalMotion()}
     * already defines and is exactly as much of an opinion as an ammeter is.
     */
    public java.util.List<String> statusLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        var bank=bank();
        if(bank!=null)out.add("Shared supply manifold: "+bank.size()+" connected drives; feed water and FE on any exposed faces.");
        if (rodIndex < 0) {
            out.add("Control rod drive: not bound to a rod. "
                    + "It still spends its supplies and still holds its charge.");
        } else {
            out.add(String.format(java.util.Locale.ROOT,
                    "Control rod drive for rod %d, at notch %02d", rodIndex, notchLabel()));
        }
        out.add(String.format(java.util.Locale.ROOT,
                "Accumulator %.0f%% charged", accumulatorCharge() * 100.0));
        out.add(String.format(java.util.Locale.ROOT,
                "Power %s: %,.0f / %,.0f FE, drawing %.1f FE/t",
                powered() ? "supplied" : "LOST", energyStoredFe(), energyCapacityFe(),
                energyDrawFePerTick()));
        out.add(String.format(java.util.Locale.ROOT,
                "Water %s: %,.0f / %,.0f mB",
                waterSupplied() ? "supplied" : "DRY", waterStoredMb(), waterCapacityMb()));
        out.add(String.format(java.util.Locale.ROOT,
                "Mechanism %.0f%%%s", health() * 100.0, failed() ? " — seized" : ""));
        if (!canPerformNormalMotion()) {
            out.add("This drive cannot index a notch under normal control. "
                    + "Scram insertion is a separate hydraulic path and is not affected.");
        }
        return out;
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
