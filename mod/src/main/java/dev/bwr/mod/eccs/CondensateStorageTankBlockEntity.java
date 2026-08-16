package dev.bwr.mod.eccs;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
 * The condensate storage tank — the cold, finite half of {@code SPEC.md}
 * section 9.2's suction choice.
 *
 * <h2>One millibucket is one kilogram</h2>
 * Not an arbitrary conversion: a Minecraft water source block is one cubic
 * metre and one bucket, and a cubic metre of water is a tonne. So a bucket is
 * 1000 mB and 1000 kg, and the two units are the same number. That makes the
 * 2,000,000 mB capacity here 2000 tonnes, or about 528,000 US gallons — which
 * is the size of a real BWR condensate storage tank, and it is why the numbers
 * below come out where they should:
 *
 * <ul>
 *   <li>RCIC at 700 gpm drains a full tank in about twelve and a half hours.</li>
 *   <li>HPCI at 5000 gpm drains it in about one and three quarters.</li>
 *   <li>A low pressure system at 6350 gpm drains it in under an hour and a
 *       half, which is why nobody sensible runs LPCS on tank suction.</li>
 * </ul>
 *
 * <p>Nothing here switches to pool suction when the tank runs down, warns that
 * it is running down, or refuses to empty itself. The level is published; what
 * to do about it is Lua's problem.
 */
public class CondensateStorageTankBlockEntity extends BlockEntity {

    /** Millibuckets, and therefore kilograms, at full. */
    public static final int CAPACITY_MB = 2_000_000;

    /** Temperature of stored condensate, degrees C. Cold, which is the whole point. */
    public static final double STORED_TEMPERATURE_C = 32.0;

    private final FluidTank tank = new FluidTank(CAPACITY_MB,
            stack -> stack.getFluid() == Fluids.WATER) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    /** Sub-kilogram remainder carried between ticks. See {@link #drawKg(double)}. */
    private double pendingDrawKg;

    public CondensateStorageTankBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(), pos, state);
    }

    /** The tank itself, for the fluid capability and for pipes. */
    public FluidTank tank() {
        return tank;
    }

    /** Water in the tank, kg. */
    public double storedKg() {
        return tank.getFluidAmount();
    }

    /** Capacity, kg. */
    public double capacityKg() {
        return tank.getCapacity();
    }

    /** How full, 0 to 1. */
    public double levelFraction() {
        return tank.getCapacity() > 0 ? (double) tank.getFluidAmount() / tank.getCapacity() : 0.0;
    }

    /**
     * Take water for a pump suction.
     *
     * <p>A fluid tank counts in whole millibuckets and a tick is a twentieth of
     * a second, so SLC at 2.7 kg/s wants 0.136 kg per tick — which would round
     * either to nothing forever or to a whole kilogram, an eightfold
     * over-draw. The sub-kilogram remainder is therefore carried between ticks
     * and the tank is drained in whole units when it accumulates, so the
     * reported flow stays smooth and the inventory stays exact.
     *
     * @return kilograms actually supplied, less than asked for once the tank is
     *         running out
     */
    public double drawKg(double wantedKg) {
        if (!(wantedKg > 0.0)) {
            return 0.0;
        }
        // pendingDrawKg is water already handed to a pump that has NOT yet been
        // taken out of the FluidTank, so it is a debt against the tank contents
        // and it must be SUBTRACTED here. Adding it — which this line used to do
        // — makes the debt itself look like inventory: once the tank empties
        // with any remainder left over, tank.drain returns nothing, the debt
        // stops being repaid, and it becomes the sole term in `available`. Each
        // call then adds its own delivery back into it and the tank supplies an
        // unbounded amount of water it does not have. Do not "simplify" the sign.
        double available = storedKg() - pendingDrawKg;
        double delivered = Math.min(wantedKg, available);
        if (!(delivered > 0.0)) {
            return 0.0;
        }
        pendingDrawKg += delivered;
        int whole = (int) Math.floor(pendingDrawKg);
        if (whole > 0) {
            FluidStack drained = tank.drain(whole, IFluidHandler.FluidAction.EXECUTE);
            // If the tank could not honour the whole amount the residue stays on
            // the books as a debt, so the next call sees less than it thinks it
            // has. That under-delivers by strictly less than one kilogram and
            // repays itself the moment the tank is refilled — the conservative
            // direction, which is the one to be wrong in.
            pendingDrawKg -= drained.getAmount();
        }
        return delivered;
    }

    /** Put water in by hand, for creative staging and for the bucket. */
    public void fillKg(double kg) {
        if (!(kg > 0.0)) {
            return;
        }
        tank.fill(new FluidStack(Fluids.WATER, (int) Math.min(Integer.MAX_VALUE, kg)),
                IFluidHandler.FluidAction.EXECUTE);
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "Condensate storage tank: %,.0f / %,.0f kg (%.1f%%) at %.0f degC",
                storedKg(), capacityKg(), levelFraction() * 100.0, STORED_TEMPERATURE_C));
        out.add("Cold suction, and finite. The suppression pool is the endless, self-heating alternative.");
        return out;
    }

    // --- Persistence ----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        // The carried remainder is a real debt against the tank contents, so it
        // has to survive a save or the sub-kilogram already handed to a pump is
        // silently forgiven on reload.
        tag.putDouble("PendingDraw", pendingDrawKg);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Tank")) {
            tank.readFromNBT(registries, tag.getCompound("Tank"));
        }
        double pending = tag.getDouble("PendingDraw");
        pendingDrawKg = Double.isFinite(pending) ? Math.max(0.0, pending) : 0.0;
    }
}
