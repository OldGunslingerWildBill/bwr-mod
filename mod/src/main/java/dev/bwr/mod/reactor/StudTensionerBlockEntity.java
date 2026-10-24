package dev.bwr.mod.reactor;

import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

/**
 * The stud tensioner: a hydraulic power pack on the refuel floor beside the well, and the tool it drives, which goes round the
 * closure flange of the reactor vessel one stud at a time. Detensioning stretches a stud, backs its nut off and moves on; at the end
 * of a pass every nut is off, the studs stay in the flange and the crane can lift the head. Retensioning, after the crane has set the
 * head back, brings the studs to load again and the vessel can hold pressure.
 *
 * <p>A stud takes {@link CraneMotion#DETENSION_TICKS_PER_STUD} / {@link CraneMotion#RETENSION_TICKS_PER_STUD} ticks and
 * {@link CraneMotion#DETENSION_FE_PER_STUD} / {@link CraneMotion#RETENSION_FE_PER_STUD} FE, taken from the pack's buffer when the stud
 * is begun. Out of power it stops where it is and says so; a player starts it again. No ticker while idle: a scheduled block tick
 * runs once a tick only while a pass is in progress. The progress itself is the reactor's stud count, so a world closed mid-pass
 * resumes where it was, the stud in hand included.
 */
public class StudTensionerBlockEntity extends BlockEntity {

    public enum Mode { IDLE, LOOSEN, TIGHTEN }

    /** Most blocks from the vessel's footprint the pack may stand (the tool's hose reaches the flange from there). */
    public static final int REACH = 8;
    public static final int FE_CAPACITY = 24_000;
    public static final int FE_RECEIVE_PER_TICK = 2_400;

    private Mode mode = Mode.IDLE;
    private int ticksInStud;
    private int energyFe;
    @Nullable
    private Component message;
    // what the client draws: the reactor, its size, the stud in hand
    private BlockPos reactor = BlockPos.ZERO;
    private int width, depth, height, studTotal, studIndex;
    private int ticksRun;

    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int accepted = Math.min(Math.min(Math.max(0, amount), FE_RECEIVE_PER_TICK), FE_CAPACITY - energyFe);
            if (!simulate && accepted > 0) {
                energyFe += accepted;
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energyFe;
        }

        @Override
        public int getMaxEnergyStored() {
            return FE_CAPACITY;
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

    public StudTensionerBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.STUD_TENSIONER.get(), pos, state);
    }

    public IEnergyStorage energy() {
        return energy;
    }

    public Mode mode() {
        return mode;
    }

    public boolean running() {
        return mode != Mode.IDLE;
    }

    @Nullable
    public Component message() {
        return message;
    }

    public int energyFe() {
        return energyFe;
    }

    /** Progress through the stud in hand, 0..1. */
    public double studProgress() {
        return mode == Mode.IDLE ? 0.0 : Math.min(1.0, ticksInStud / (double) CraneMotion.ticksPerStud(mode == Mode.LOOSEN));
    }

    /** How many times the scheduled tick has run (tests: it must not move while idle). */
    public int ticksRun() {
        return ticksRun;
    }

    /** The test hook that fills the buffer, as a power cable would. */
    public void fill(int fe) {
        energyFe = Math.max(0, Math.min(FE_CAPACITY, fe));
        setChanged();
    }

    /** What the client draws: the stud in hand (index round the flange), the count, the vessel's size. */
    public int clientStud() {
        return studIndex;
    }

    public int clientStudTotal() {
        return studTotal;
    }

    public BlockPos clientReactor() {
        return reactor;
    }

    @Nullable
    public VesselProfile clientProfile() {
        return width <= 0 ? null : VesselProfile.of(width, depth, height, false);
    }

    // ---------------------------------------------------------------
    // The reactor it works on
    // ---------------------------------------------------------------

    /** The formed reactor whose flange this pack reaches (the nearest), or null. */
    @Nullable
    public ReactorControllerBlockEntity findReactor() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        ReactorControllerBlockEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ReactorControllerBlockEntity r : FormedReactorRegistry.controllers(level)) {
            var e = r.vesselEnvelope();
            if (e == null || !r.isFormed()) {
                continue;
            }
            double dx = Math.max(Math.max(e.min().getX() - worldPosition.getX(), worldPosition.getX() - (e.max().getX() + 1)), 0);
            double dz = Math.max(Math.max(e.min().getZ() - worldPosition.getZ(), worldPosition.getZ() - (e.max().getZ() + 1)), 0);
            boolean tall = worldPosition.getY() >= e.min().getY() && worldPosition.getY() <= e.max().getY() + REACH;
            if (tall && dx <= REACH && dz <= REACH) {
                double d = dx * dx + dz * dz + r.getBlockPos().distSqr(worldPosition) * 1e-6;
                if (d < bestD) {
                    bestD = d;
                    best = r;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    /** Starts (or, with a pass under way, refuses) a pass: loosening every stud, or bringing every stud to load. Returns the reason it did not start, or null. */
    @Nullable
    public Component start(boolean loosen) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        if (running()) {
            return BwrText.tr("status.bwr.stud_tensioner.busy", "The tensioner is already working. Stop it first to change direction.");
        }
        ReactorControllerBlockEntity r = findReactor();
        if (r == null) {
            return BwrText.tr("status.bwr.stud_tensioner.no_reactor",
                    "No formed reactor flange within %s blocks. Stand the stud tensioner on the refuel floor beside the well.", REACH);
        }
        Component why = loosen ? r.studLoosenBlocked() : r.studTightenBlocked();
        if (why != null) {
            return BwrText.tr("status.bwr.stud_tensioner.cannot_start", "The tensioner cannot start: %s.", why);
        }
        mode = loosen ? Mode.LOOSEN : Mode.TIGHTEN;
        ticksInStud = 0;
        message = null;
        remember(r);
        setChanged();
        sync();
        schedule(server);
        return null;
    }

    /** Stops where it is. The stud in hand starts over next time; the studs already done stay done. */
    public void stop() {
        if (mode != Mode.IDLE) {
            mode = Mode.IDLE;
            ticksInStud = 0;
            message = BwrText.tr("status.bwr.stud_tensioner.stopped", "Stopped.");
            setChanged();
            sync();
        }
    }

    private void schedule(ServerLevel server) {
        if (!server.getBlockTicks().hasScheduledTick(worldPosition, getBlockState().getBlock())) {
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    private void remember(ReactorControllerBlockEntity r) {
        var e = r.vesselEnvelope();
        reactor = r.getBlockPos().immutable();
        if (e != null) {
            width = e.width();
            depth = e.depth();
            height = e.height();
        }
        studTotal = r.studTotal();
        studIndex = mode == Mode.LOOSEN ? Math.min(r.studsOff(), Math.max(0, studTotal - 1)) : Math.max(0, r.studsOff() - 1);
    }

    private void finish(ServerLevel server, Component why) {
        mode = Mode.IDLE;
        ticksInStud = 0;
        message = why;
        setChanged();
        sync();
    }

    /** The scheduled block tick: one a tick while a pass is under way, never otherwise. */
    public void scheduledTick(ServerLevel server) {
        ticksRun++;
        if (mode == Mode.IDLE) {
            return;
        }
        ReactorControllerBlockEntity r = level.isLoaded(reactor) && level.getBlockEntity(reactor) instanceof ReactorControllerBlockEntity c
                && c.isFormed() && !c.isRemoved() ? c : null;
        if (r == null) {
            finish(server, BwrText.tr("status.bwr.stud_tensioner.reactor_gone", "Stopped: the reactor is not there or not loaded."));
            return;
        }
        boolean loosen = mode == Mode.LOOSEN;
        if (ticksInStud == 0) {
            Component why = loosen ? r.studLoosenBlocked() : r.studTightenBlocked();
            if (why != null) {
                boolean done = loosen ? r.studsOff() >= r.studTotal() : r.studsOff() <= 0;
                finish(server, done ? (loosen
                        ? BwrText.tr("status.bwr.stud_tensioner.done_loose", "Every stud is detensioned and its nut is off. The head can be lifted.")
                        : BwrText.tr("status.bwr.stud_tensioner.done_tight", "Every stud is at load. The vessel can hold pressure."))
                        : BwrText.tr("status.bwr.stud_tensioner.cannot_go_on", "Stopped: %s.", why));
                return;
            }
            int fe = CraneMotion.fePerStud(loosen);
            if (energyFe < fe) {
                finish(server, BwrText.tr("status.bwr.stud_tensioner.no_power",
                        "Stopped: out of power. The next stud needs %s FE and the pack holds %s. Supply power and start it again.", fe, energyFe));
                return;
            }
            energyFe -= fe;
            remember(r);
            sync();
        }
        if (++ticksInStud >= CraneMotion.ticksPerStud(loosen)) {
            ticksInStud = 0;
            r.setStudsOff(r.studsOff() + (loosen ? 1 : -1));
            remember(r);
            boolean done = loosen ? r.studsOff() >= r.studTotal() : r.studsOff() <= 0;
            if (done) {
                finish(server, loosen
                        ? BwrText.tr("status.bwr.stud_tensioner.done_loose", "Every stud is detensioned and its nut is off. The head can be lifted.")
                        : BwrText.tr("status.bwr.stud_tensioner.done_tight", "Every stud is at load. The vessel can hold pressure."));
                return;
            }
            sync();
        }
        setChanged();
        schedule(server);
    }

    // ---------------------------------------------------------------
    // Saves and sync
    // ---------------------------------------------------------------

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && mode != Mode.IDLE) {
            schedule(server);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (energyFe > 0) {
            tag.putInt("Energy", energyFe);
        }
        if (mode != Mode.IDLE) {
            tag.putString("Mode", mode.name());
            tag.putLong("Reactor", reactor.asLong());
            tag.putIntArray("Vessel", new int[]{width, depth, height, studTotal, studIndex});
            if (ticksInStud > 0) {
                tag.putInt("Ticks", ticksInStud);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energyFe = Math.max(0, Math.min(FE_CAPACITY, tag.getInt("Energy")));
        mode = Mode.IDLE;
        if (tag.contains("Mode")) {
            try {
                mode = Mode.valueOf(tag.getString("Mode"));
            } catch (IllegalArgumentException e) {
                mode = Mode.IDLE;
            }
        }
        reactor = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : BlockPos.ZERO;
        int[] v = tag.getIntArray("Vessel");
        width = v.length > 0 ? v[0] : 0;
        depth = v.length > 1 ? v[1] : 0;
        height = v.length > 2 ? v[2] : 0;
        studTotal = v.length > 3 ? v[3] : 0;
        studIndex = v.length > 4 ? v[4] : 0;
        ticksInStud = mode == Mode.IDLE ? 0 : Math.max(0, tag.getInt("Ticks"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
