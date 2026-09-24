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
    /** Gameplay electrical loads: motor while opening, solenoid while held open. */
    public static final int ENERGY_CAPACITY_FE = 20_000;
    public static final int OPENING_FE_PER_TICK = 100;
    public static final int HOLDING_FE_PER_TICK = 20;
    private int energyFe;
    private final net.neoforged.neoforge.energy.IEnergyStorage energy = new net.neoforged.neoforge.energy.IEnergyStorage() {
        public int receiveEnergy(int amount, boolean simulate) {
            int accepted = Math.min(Math.max(0,amount),ENERGY_CAPACITY_FE-energyFe);
            if(!simulate && accepted>0){energyFe+=accepted;setChanged();}
            return accepted;
        }
        public int extractEnergy(int amount,boolean simulate){return 0;}
        public int getEnergyStored(){return energyFe;}
        public int getMaxEnergyStored(){return ENERGY_CAPACITY_FE;}
        public boolean canExtract(){return false;}
        public boolean canReceive(){return true;}
    };

    /** 1.0 fully open, 0.0 fully shut. */
    private volatile double position;
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
        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0) return;
        // The spring can always close; electrical energy is required to open
        // or hold the valve. Commands never grant free actuator power.
        double openingSeconds=Math.min(dtSeconds,(1-position)*STROKE_SECONDS);
        double cost=Math.ceil((openingSeconds*OPENING_FE_PER_TICK
                +(dtSeconds-openingSeconds)*HOLDING_FE_PER_TICK)/TICK_SECONDS-1e-9);
        boolean powered=demandOpen && cost<=energyFe;
        if(powered && cost>0){energyFe-=(int)cost;setChanged();}
        double target = powered ? 1.0 : 0.0;
        if (position == target) {
            syncOpenState();
            return;
        }
        double step = dtSeconds / STROKE_SECONDS;
        if (Math.abs(position - target) <= step + 1e-12) {
            // Avoid an extra tick at the end stop from accumulated floating-point error.
            position = target;
        } else if (position < target) {
            position = Math.min(target, position + step);
        } else {
            position = Math.max(target, position - step);
        }
        setChanged();
        syncOpenState();
    }
    private void syncOpenState(){
        if(level!=null && !level.isClientSide() && getBlockState().getValue(MainSteamIsolationValveBlock.OPEN)!=(position>0))
            level.setBlock(worldPosition,getBlockState().setValue(MainSteamIsolationValveBlock.OPEN,position>0),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    public net.neoforged.neoforge.energy.IEnergyStorage energy(){return energy;}
    public boolean isPowered(){return energyFe>=(position<1?OPENING_FE_PER_TICK:HOLDING_FE_PER_TICK);}
    public int getRequiredFePerTick(){return demandOpen?(position<1?OPENING_FE_PER_TICK:HOLDING_FE_PER_TICK):0;}
    // This valve has no comparator output. Avoid vanilla's adjacent comparator
    // scan (and possible chunk loads) for every electrical transfer.
    @Override public void setChanged(){if(level!=null)level.blockEntityChanged(worldPosition);}

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
        tag.putInt("EnergyFe",energyFe);
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
        double saved = tag.contains("Position") ? tag.getDouble("Position") : 0.0;
        position = Double.isFinite(saved) ? Math.max(0.0, Math.min(1.0, saved)) : 0.0;
        demandOpen = !tag.contains("DemandOpen") || tag.getBoolean("DemandOpen");
        computerControlled = tag.getBoolean("ComputerControlled");
        energyFe=Math.clamp(tag.getInt("EnergyFe"),0,ENERGY_CAPACITY_FE);
    }
}
