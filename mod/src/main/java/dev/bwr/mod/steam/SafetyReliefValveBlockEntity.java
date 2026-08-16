package dev.bwr.mod.steam;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * A safety/relief valve — {@code SPEC.md} section 6.5, as amended.
 *
 * <h2>This valve does not open by itself</h2>
 * On a real plant an SRV has a spring safety function that lifts on pressure
 * with no power and no logic. In this mod it does <b>not</b>: SRVs are actuated
 * by the player, through CC:Tweaked or redstone, and nothing in the mod opens
 * one on their behalf.
 *
 * <p>That is a deliberate design decision and it has a consequence worth
 * stating plainly: <b>there is no automatic overpressure protection anywhere in
 * this plant.</b> If nobody is watching and nobody wrote the Lua, pressure
 * climbs until the vessel is damaged. The overpressure stress model is the only
 * thing that responds on its own, which is why it accumulates damage gradually
 * rather than failing at a threshold.
 *
 * <h2>Discharge must be underwater</h2>
 * A relief valve venting to atmosphere does not suppress anything. Validation
 * requires the discharge point to be submerged, per {@code SPEC.md} section 6.6
 * — a structural requirement, not decoration.
 */
public class SafetyReliefValveBlockEntity extends BlockEntity {

    /** Flow one fully open valve can pass at rated pressure, kg/s. */
    public static final double CAPACITY_KG_PER_S = 42.0;

    /**
     * Downstream-to-upstream absolute pressure ratio below which the throat is
     * choked, for steam.
     *
     * <p>{@code (2 / (gamma + 1)) ^ (gamma / (gamma - 1))} with gamma = 1.3 for
     * saturated steam. Below this the throat is at the speed of sound and the
     * downstream pressure stops mattering: flow becomes proportional to
     * upstream absolute pressure alone, which is exactly the form real relief
     * valve sizing uses.
     */
    public static final double CRITICAL_PRESSURE_RATIO = 0.5457;

    /** How far below the valve we look for the suppression pool surface. */
    private static final int DISCHARGE_SEARCH_DEPTH = 24;

    private volatile boolean open;
    private volatile boolean computerControlled;
    private volatile double lastFlowKgPerS;
    private volatile boolean dischargeSubmerged;

    /**
     * Which suppression pool has already taken this valve's discharge this
     * tick, and when. Two pool controllers can sit within search range of one
     * valve; without this each would condense the same steam into its own pool
     * object and the plant would gain heat and water out of nothing.
     */
    private BlockPos condensationOwner;
    private long condensationOwnerTick = Long.MIN_VALUE;

    public SafetyReliefValveBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.SAFETY_RELIEF_VALVE.get(), pos, state);
    }

    // --- Actuation: the player's, never the mod's --------------------

    public boolean isOpen() {
        return open;
    }

    /** Open or close the valve. Called from redstone or from Lua. Never from physics. */
    public void setOpen(boolean open) {
        this.open = open;
        setChanged();
    }

    /**
     * Claim this valve's discharge for one suppression pool for one tick.
     *
     * <p>The first pool to ask on a given tick owns the discharge and every
     * other pool is told no, so the steam is condensed exactly once however
     * many pool controllers can see the valve. Bookkeeping, not a permissive:
     * nothing here decides whether the valve should be open.
     *
     * @return true if the caller owns this valve's discharge this tick
     */
    public boolean claimCondensation(BlockPos poolPos, long gameTime) {
        if (poolPos == null) {
            return false;
        }
        if (condensationOwnerTick != gameTime) {
            condensationOwnerTick = gameTime;
            condensationOwner = poolPos.immutable();
            return true;
        }
        return poolPos.equals(condensationOwner);
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        this.computerControlled = computerControlled;
        setChanged();
    }

    /**
     * Steam this valve is passing right now against atmospheric back-pressure,
     * kg/s.
     *
     * <p>Convenience over {@link #flowKgPerS(double, double)} for callers that
     * have not resolved a suppression pool and therefore do not know the
     * containment pressure the valve is discharging into. Containment is not
     * modelled yet ({@code SPEC.md} section 16) and the pool holds itself at
     * atmospheric, so the two agree in every plant that exists today; when a
     * containment model arrives, the two-argument form is the one that keeps
     * being right.
     */
    public double flowKgPerS(double domePressurePsig) {
        return flowKgPerS(domePressurePsig, dev.bwr.core.PhysicalConstants.ATMOSPHERIC_PSI);
    }

    /**
     * Steam this valve is passing right now, kg/s, against a stated downstream
     * pressure.
     *
     * <h2>The flow is a differential, and it is choked nearly all the time</h2>
     * This used to be {@code CAPACITY * sqrt(P_up / P_rated)} — an absolute
     * pressure ratio with no downstream term in it at all. That overstates
     * relief badly at low vessel pressure, which is precisely the regime a
     * player is in after an ADS blowdown, and it left a cliff at the bottom: at
     * 0.0 psig the guard returned zero while at 0.001 psig the same valve
     * claimed 5 kg/s.
     *
     * <p>What a relief valve actually does: with the pool at roughly atmospheric
     * and the vessel anywhere near operating pressure the throat is choked, and
     * choked mass flow is proportional to <i>upstream absolute pressure</i>,
     * not to its square root — that is the form ASME sizing uses. So rated
     * capacity is scaled linearly by {@code P_up / P_rated}, which reproduces
     * 42 kg/s at rated conditions exactly as before.
     *
     * <p>Only once the vessel comes down within about a factor of two of
     * containment does the downstream pressure start to matter. There the
     * standard subcritical correction applies, falling smoothly to zero as the
     * vessel equalises with the pool — so a valve left open at the end of a
     * blowdown stops passing steam because there is no differential left, not
     * because a threshold cut it off.
     *
     * <p>A closed valve, or one whose discharge is not submerged, passes
     * nothing.
     */
    public double flowKgPerS(double domePressurePsig, double downstreamPsia) {
        if (!open || !dischargeSubmerged) {
            lastFlowKgPerS = 0.0;
            return 0.0;
        }
        double ratedPsia =
                Saturation.psiaFromPsig(dev.bwr.core.PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
        double upstreamPsia = Saturation.psiaFromPsig(domePressurePsig);
        double downstream = Math.max(0.0, downstreamPsia);
        if (upstreamPsia <= downstream) {
            lastFlowKgPerS = 0.0;
            return 0.0;
        }

        double ratio = downstream / upstreamPsia;
        double subcritical = 1.0;
        if (ratio > CRITICAL_PRESSURE_RATIO) {
            // Fraction of the choked rate still passing as the seat unchokes.
            // Meets 1.0 at the critical ratio and reaches 0.0 at equalisation,
            // so there is no step anywhere.
            double x = (ratio - CRITICAL_PRESSURE_RATIO) / (1.0 - CRITICAL_PRESSURE_RATIO);
            subcritical = Math.sqrt(Math.max(0.0, 1.0 - x * x));
        }

        lastFlowKgPerS = CAPACITY_KG_PER_S * (upstreamPsia / ratedPsia) * subcritical;
        return lastFlowKgPerS;
    }

    public double getLastFlowKgPerS() {
        return lastFlowKgPerS;
    }

    // --- Validation ---------------------------------------------------

    public boolean isDischargeSubmerged() {
        return dischargeSubmerged;
    }

    /**
     * Look downward from the valve for standing water. A valve that vents into
     * air is a valid block but a useless one, and the controller reports it.
     */
    public boolean revalidateDischarge(Level level) {
        BlockPos.MutableBlockPos p = getBlockPos().mutable();
        for (int i = 0; i < DISCHARGE_SEARCH_DEPTH; i++) {
            p.move(0, -1, 0);
            if (level.getFluidState(p).getType() == Fluids.WATER
                    || level.getFluidState(p).getType() == Fluids.FLOWING_WATER) {
                dischargeSubmerged = true;
                setChanged();
                return true;
            }
        }
        dischargeSubmerged = false;
        setChanged();
        return false;
    }

    // --- Persistence ---------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Open", open);
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putBoolean("Submerged", dischargeSubmerged);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        open = tag.getBoolean("Open");
        computerControlled = tag.getBoolean("ComputerControlled");
        dischargeSubmerged = tag.getBoolean("Submerged");
    }
}
