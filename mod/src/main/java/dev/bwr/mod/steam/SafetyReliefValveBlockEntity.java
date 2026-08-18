package dev.bwr.mod.steam;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.SuppressionPoolQuencherBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>There are two ways to be underwater and {@link DischargePath} names them.
 * The proper one is a discharge line: pressurised tube from this valve down to
 * a {@code bwr:suppression_pool_quencher} submerged in the basin, which is what
 * a real plant has. The other is the one this mod shipped with — the valve
 * simply standing over open water, found by looking straight down — which
 * remains valid so that plants already built go on working, and which the pool
 * controller reports as the degraded path it is. Which of the two applies is a
 * measurement about pipework, published through {@link #dischargePath()}; it is
 * not a judgement about whether the valve ought to be open.
 */
public class SafetyReliefValveBlockEntity extends BlockEntity {

    /**
     * How this valve's discharge reaches water, if it does.
     *
     * <p>A description of what is built, nothing more. The valve passes steam
     * on anything but {@link #NONE} and the pool decides what to do with it.
     */
    public enum DischargePath {
        /** Nothing under this valve and no quencher on its line. */
        NONE,
        /** Standing water somewhere below, with no discharge line to it. */
        OPEN_WATER,
        /** A steam line from this valve to a submerged quencher. */
        QUENCHER
    }

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

    /**
     * Shortest interval between walks of this valve's steam line, ticks.
     *
     * <p>{@link #revalidateDischarge} is called from a neighbour change, which
     * fires on every redstone edge — and a relief valve driven by a redstone
     * clock sees twenty of those a second. An unthrottled line walk there is
     * {@link SteamLineNetwork#MAX_LINE_BLOCKS} worth of block lookups twenty
     * times a second per valve, for an answer that changes only when somebody
     * lays or breaks a pipe. The <i>submersion</i> of the quenchers already
     * found is re-tested on every call regardless, because that is a handful of
     * fluid lookups and it is the half of the answer that moves on its own when
     * a pool is drained.
     */
    private static final int LINE_SURVEY_INTERVAL_TICKS = 20;

    private volatile boolean open;
    private volatile boolean computerControlled;
    private volatile double lastFlowKgPerS;
    private volatile boolean dischargeSubmerged;
    private volatile DischargePath dischargePath = DischargePath.NONE;

    /**
     * Quenchers found on this valve's steam line by the last walk, submerged or
     * not. A cache of pipework, refilled at most every
     * {@link #LINE_SURVEY_INTERVAL_TICKS} and re-tested for submersion on every
     * use, so a drained pool is noticed at once and a rebuilt line within a
     * second.
     *
     * <p>Positions, never block references: a cached block entity outlives the
     * block it belongs to, and this codebase has been bitten by that before.
     */
    private final List<BlockPos> lineQuenchers = new ArrayList<>();
    private long lastLineSurveyTick = Long.MIN_VALUE;

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
     * How this valve's discharge reaches water, as of the last
     * {@link #revalidateDischarge}.
     *
     * <p>A measurement of what is built. The suppression pool reads it to tell
     * steam that arrived through a quencher from steam that arrived by falling
     * into open water, because those two condense differently; nothing here
     * decides whether the valve should be open.
     */
    public DischargePath dischargePath() {
        return dischargePath;
    }

    /**
     * Work out whether this valve has anywhere to discharge to, and by which
     * route.
     *
     * <p>The discharge line is looked for first and wins, because it is the
     * real hardware: a valve piped to a submerged quencher discharges through
     * that quencher wherever the valve itself happens to be standing, which is
     * the whole reason the block exists — relief valves belong up on the steam
     * line at vessel elevation, not hovering over the pool because that is the
     * only place the old straight-down search could find water.
     *
     * <p>Falling back to that straight-down search is what keeps every plant
     * built before quenchers existed working exactly as it did. It is kept
     * deliberately and it is not deprecated; it is reported by the pool as the
     * lesser path, which is a different thing from being refused.
     *
     * <p>The signature is unchanged — still {@code boolean}, still "is there a
     * discharge" — because {@code AdsControllerBlockEntity} and
     * {@code SafetyReliefValveBlock} both call it for exactly that answer and
     * neither cares how the steam gets to the water.
     */
    public boolean revalidateDischarge(Level level) {
        DischargePath found = findDischargePath(level);
        boolean submerged = found != DischargePath.NONE;
        // setChanged() dispatches neighbour updates, and this runs from a
        // neighbour change on every redstone edge. Only write when the answer
        // actually moved.
        if (found != dischargePath || submerged != dischargeSubmerged) {
            dischargePath = found;
            dischargeSubmerged = submerged;
            setChanged();
        }
        return submerged;
    }

    private DischargePath findDischargePath(Level level) {
        if (hasSubmergedQuencher(level)) {
            return DischargePath.QUENCHER;
        }
        BlockPos.MutableBlockPos p = getBlockPos().mutable();
        for (int i = 0; i < DISCHARGE_SEARCH_DEPTH; i++) {
            p.move(0, -1, 0);
            if (level.getFluidState(p).getType() == Fluids.WATER
                    || level.getFluidState(p).getType() == Fluids.FLOWING_WATER) {
                return DischargePath.OPEN_WATER;
            }
        }
        return DischargePath.NONE;
    }

    /**
     * Whether this valve's steam line ends in a quencher that is under water.
     *
     * <p>Two questions with very different costs, so they are asked at very
     * different rates. Where the pipes go is walked at most every
     * {@link #LINE_SURVEY_INTERVAL_TICKS}; whether those quenchers are wet is
     * asked every time, because it changes when somebody drains the pool and
     * nothing fires a neighbour change here when they do.
     */
    private boolean hasSubmergedQuencher(Level level) {
        long now = level.getGameTime();
        // Long.MIN_VALUE as the initial value would overflow on subtraction, so
        // the two ends are compared rather than differenced.
        if (lastLineSurveyTick == Long.MIN_VALUE
                || now < lastLineSurveyTick
                || now - lastLineSurveyTick >= LINE_SURVEY_INTERVAL_TICKS) {
            lastLineSurveyTick = now;
            lineQuenchers.clear();
            lineQuenchers.addAll(SteamLineNetwork.survey(level, getBlockPos()).quenchers());
        }
        for (BlockPos q : lineQuenchers) {
            // An unloaded position is skipped, not taken as absent: reading it
            // would generate terrain, and treating it as gone would make a
            // quencher across a chunk border flicker in and out of existence.
            if (!level.isLoaded(q)) {
                continue;
            }
            if (level.getBlockState(q).is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get())
                    && SuppressionPoolQuencherBlock.isSubmerged(level, q)) {
                return true;
            }
        }
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
        // The path is derived rather than saved, so the NBT written by this
        // class is byte-for-byte what it was before quenchers existed and no
        // save needs migrating either way. A valve reloads reading as the
        // discharge it had, and the first revalidateDischarge — which the pool
        // and the ADS controller both run within a second of the chunk coming
        // back — replaces the guess with the truth.
        dischargePath = dischargeSubmerged ? DischargePath.OPEN_WATER : DischargePath.NONE;
    }
}
