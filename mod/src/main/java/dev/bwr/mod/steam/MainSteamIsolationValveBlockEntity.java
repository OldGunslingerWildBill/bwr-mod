package dev.bwr.mod.steam;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Main steam isolation valve — {@code SPEC.md} section 9.5.
 *
 * <p>Player-actuated, like everything else that can be commanded. Closing it
 * isolates the vessel, and the pressurisation transient that follows is
 * <i>emergent</i>: steam generation continues, removal stops, pressure climbs,
 * voids collapse, moderation increases and power surges. Nothing in the mod
 * scripts that sequence — it falls out of the physics, which is exactly why
 * real BWRs scram on MSIV closure rather than waiting for a flux trip.
 *
 * <p>The valve strokes over a few seconds rather than snapping shut, because
 * how fast it closes determines how sharp the transient is.
 */
public class MainSteamIsolationValveBlockEntity extends BlockEntity {

    /** Full stroke time, seconds. Real MSIVs close in three to five. */
    public static final double STROKE_SECONDS = 4.0;

    /** One game tick, seconds. The valve strokes on the server tick. */
    private static final double TICK_SECONDS = 0.05;

    /** 1.0 fully open, 0.0 fully shut. */
    private volatile double position = 1.0;
    private volatile boolean demandOpen = true;
    private volatile boolean computerControlled;

    public MainSteamIsolationValveBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.MSIV.get(), pos, state);
    }

    /**
     * The valve's own ticker.
     *
     * <p>There was none. {@code tickValve} was documented as being called by
     * "the controller that owns the steam line" and nothing called it, the block
     * did not override {@code getTicker}, and no consumer read
     * {@link #getPosition()} — so an MSIV flipped its blockstate, changed its
     * demand, and did absolutely nothing to the plant. The stroke happens here
     * and {@code TurbineSteamOutletBlockEntity} throttles its delivered steam by
     * the position, which is what makes the closure transient of
     * {@code SPEC.md} section 6.3 emerge instead of being described.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  MainSteamIsolationValveBlockEntity be) {
        be.tickValve(TICK_SECONDS);
    }

    /** Advance the stroke. Called by the ticker, once per server tick. */
    public void tickValve(double dtSeconds) {
        double target = demandOpen ? 1.0 : 0.0;
        if (position == target) {
            // A valve sitting at its end stop is not a change. Marking the chunk
            // dirty twenty times a second for every MSIV in the world would be.
            return;
        }
        double step = dtSeconds / STROKE_SECONDS;
        if (position < target) {
            position = Math.min(target, position + step);
        } else {
            position = Math.max(target, position - step);
        }
        setChanged();
    }

    public double getPosition() {
        return position;
    }

    public boolean isDemandOpen() {
        return demandOpen;
    }

    /** Commanded from redstone or Lua. Never from the physics. */
    public void setDemandOpen(boolean open) {
        this.demandOpen = open;
        setChanged();
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        this.computerControlled = computerControlled;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Position", position);
        tag.putBoolean("DemandOpen", demandOpen);
        tag.putBoolean("ComputerControlled", computerControlled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Clamped, because this is the one path into the stroke that does not go
        // through tickValve. A NaN out of a hand-edited save is not merely a bad
        // reading: tickValve compares against its target with < and >, both of
        // which are false for NaN, so the stroke sticks at NaN for ever and every
        // consumer that multiplies by it — the nozzles and the turbine outlet
        // both do — gets a NaN steam flow. Clamping here costs nothing and the
        // consumers guard as well, on the principle that a value crossing a
        // boundary is checked on both sides of it.
        double saved = tag.contains("Position") ? tag.getDouble("Position") : 1.0;
        position = Double.isFinite(saved) ? Math.max(0.0, Math.min(1.0, saved)) : 1.0;
        demandOpen = !tag.contains("DemandOpen") || tag.getBoolean("DemandOpen");
        computerControlled = tag.getBoolean("ComputerControlled");
    }
}
