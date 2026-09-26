package dev.bwr.mod.eccs;

import dev.bwr.core.ReactorCore;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Automatic Depressurisation System — {@code SPEC.md} section 9.1.
 *
 * <h2>Automatic in name only, and deliberately so</h2>
 * On a real plant ADS is a logic package that blows the vessel down by itself
 * when it sees low level and confirmed low pressure permissives. Here it is a
 * bank of solenoids and a nitrogen bottle, and it opens the relief valves when
 * the player's Lua tells it to and at no other time. Every setpoint that made
 * the real system automatic is a number the player has to pick.
 *
 * <h2>The handoff this exists to create</h2>
 * The low pressure systems develop 230 to 300 psi of head. A vessel at
 * 1025 psig is simply harder than they are, so they deliver nothing at all
 * until it is blown down. Blowing it down is not free:
 *
 * <ul>
 *   <li>Every kilogram relieved lands in the suppression pool and heats it,
 *       spending the heat sink you will need for the rest of the event.</li>
 *   <li>The pressure drop flashes vessel water to steam — level swells, then
 *       collapses well below where it started as the inventory leaves.</li>
 *   <li>Below a few tens of psig the turbine-driven systems lose their motive
 *       steam. Depressurising trades RCIC and HPCI for LPCS and LPCI, and it is
 *       a one-way trade until the plant is re-pressurised.</li>
 * </ul>
 *
 * <h2>Nitrogen</h2>
 * ADS valves are held open pneumatically. A full charge holds the whole bank
 * open for about half an hour with no electrical supply at all, and a powered
 * compressor recharges faster than the bank leaks — so ADS works in a station
 * blackout, for a while, and then the valves shut themselves. That is hardware
 * behaviour of the same kind as the scram accumulators, and it is a documented
 * real-plant failure mode.
 */
public class AdsControllerBlockEntity extends BlockEntity {

    /** How far the controller looks for its reactor and its relief valves. */
    private static final int SEARCH_RADIUS = 24;

    /** Fraction of the nitrogen charge spent per second holding the whole bank open. */
    public static final double HOLD_DRAIN_PER_SECOND = 1.0 / 1800.0;

    /** Fraction recovered per second by the compressor when it has power. */
    public static final double RECHARGE_PER_SECOND = 1.0 / 600.0;

    /** Compressor draw, FE per tick. Trivial next to a pump motor. */
    public static final int COMPRESSOR_FE_PER_TICK = 200;

    /** Shortest interval between full neighbourhood scans, ticks. */
    private static final int REBIND_INTERVAL_TICKS = 40;

    /**
     * Interval between rescans once the controller has a reactor and a valve
     * bank, ticks.
     *
     * <p>There has to be one. Binding was previously refreshed only when
     * something set {@link #bindingDirty} — placement, and a neighbour change
     * against this block — or while the controller had nothing bound at all.
     * Neither fires for work done anywhere else in the search radius, and
     * everything a player does to an ADS bank after the first valve is exactly
     * that: stacking a second and third relief valve onto the bank, draining
     * the pool out from under one, digging the discharge deeper. A controller
     * bound to one valve went on commanding one valve forever, so
     * {@code getValveCount()} — the number a blowdown program sizes its demand
     * from — was frozen at whatever happened to be there when the ADS was
     * dropped, and the extra valves the player had welded on did nothing.
     *
     * <p>Same value and same reasoning as the suppression pool's formed
     * revalidation interval, and the same cost: a 49-cube of block states plus
     * a short downward walk per valve, once every thirty seconds.
     */
    private static final int BOUND_REBIND_INTERVAL_TICKS = 600;

    private final MachineEnergy energy = new MachineEnergy();

    // Volatile because Lua reads them from a CC computer thread; the matching
    // writes are marshalled onto the server thread by PlantActuators.
    private int division=1;
    public int getDivision(){return division;}
    private volatile boolean open;
    private volatile double valveDemandFraction = 1.0;
    private volatile double nitrogenCharge = 1.0;
    private volatile boolean computerControlled;

    private BlockPos reactorPos;
    private final List<BlockPos> valves = new ArrayList<>();
    private final Set<BlockPos> held = new LinkedHashSet<>();
    private boolean bindingDirty = true;
    private int ticksSinceRebind = REBIND_INTERVAL_TICKS;

    private volatile double blowdownKgPerS;

    private static final class MachineEnergy extends EnergyStorage {

        MachineEnergy() {
            super(COMPRESSOR_FE_PER_TICK * 200, COMPRESSOR_FE_PER_TICK * 4, 0);
        }

        void drain(int amount) {
            this.energy = Math.max(0, this.energy - amount);
        }

        void setStored(int stored) {
            this.energy = Math.max(0, Math.min(this.capacity, stored));
        }
    }

    public AdsControllerBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.ADS_CONTROLLER.get(), pos, state);
        EccsNetwork.ensureListenerRegistered();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  AdsControllerBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        maybeRebind(level);

        final double dt = 0.05;
        ReactorControllerBlockEntity reactor = reactor(level);
        ReactorCore core = reactor != null ? reactor.core() : null;
        double domePsig = core != null ? core.getPressurePsig() : 0.0;

        int wanted = (nitrogenCharge > 0.0 && open)
                ? (int) Math.round(Math.min(1.0, Math.max(0.0, valveDemandFraction)) * valves.size())
                : 0;

        commandValves(level, wanted);

        // Nitrogen: leaks while the bank is held open, refills whenever the
        // compressor has power. With power the compressor wins; without it the
        // bank empties and the valves shut themselves.
        double openFraction = valves.isEmpty() ? 0.0 : (double) held.size() / valves.size();
        nitrogenCharge -= HOLD_DRAIN_PER_SECOND * openFraction * dt;
        if (energy.getEnergyStored() >= COMPRESSOR_FE_PER_TICK && nitrogenCharge < 1.0) {
            nitrogenCharge += RECHARGE_PER_SECOND * dt;
            energy.drain(COMPRESSOR_FE_PER_TICK);
        }
        nitrogenCharge = Math.min(1.0, Math.max(0.0, nitrogenCharge));

        // The relief channel: steam that has physically left the vessel through
        // an open valve. Reported for every open, submerged valve this
        // controller can see, not only the ones it opened itself, because the
        // vessel does not care who turned the handle.
        ReactorEccsBus bus = (reactor != null && core != null && reactorPos != null)
                ? EccsNetwork.busFor(level, reactorPos) : null;
        blowdownKgPerS = reportReliefFlow(level, bus, domePsig);

        setChanged();
    }

    /** Hold the first {@code wanted} valves open and release the rest. */
    private void commandValves(Level level, int wanted) {
        int opened = 0;
        Iterator<BlockPos> it = valves.iterator();
        while (it.hasNext()) {
            BlockPos vp = it.next();
            boolean shouldHold = opened < wanted;
            if (!(level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)) {
                // The valve is gone — broken, or its chunk went away. Drop it
                // from both collections instead of skipping it. Skipping left
                // the dead position in `valves` and in `held` until something
                // happened to force a rebind, which breaking a valve ten blocks
                // away does not, so getValveCount() and getOpenValveCount() —
                // the two numbers a blowdown program sizes its demand from —
                // kept counting it, and openFraction kept draining nitrogen to
                // hold a valve that does not exist.
                it.remove();
                held.remove(vp);
                continue;
            }
            if(srv.getDivision()!=division){
                if(held.remove(vp))setValve(level,vp,srv,false);
                it.remove();continue;
            }
            if (shouldHold) {
                opened++;
                held.add(vp);
                setValve(level, vp, srv, true);
            } else if (held.remove(vp)) {
                setValve(level, vp, srv, false);
            }
        }
    }

    /**
     * Drive one valve, and take or give back the channel that drives it.
     *
     * <p><b>Claiming is not bookkeeping, it is what makes the valve stay put.</b>
     * {@code SafetyReliefValveBlock.neighborChanged} takes the
     * {@code !isComputerControlled()} branch on every redstone edge that reaches
     * the valve, reads {@code hasNeighborSignal()} — false, for a valve the ADS
     * is holding open pneumatically and nobody has wired — and slams it shut,
     * block state and all. This controller would put it back on its next tick,
     * so the valve chattered, and every reseat is a tick of relief flow the
     * vessel did not get: the pool controller and the flow meter both read
     * {@code srv.isOpen()} on their own tick, and an edge landing between the
     * two reads zero.
     *
     * <p>It does not stop at one valve either. That reseat writes the block
     * state with {@code UPDATE_ALL}, which fires {@code neighborChanged} on the
     * six blocks around it — including the next valve of a contiguous bank,
     * which also has no redstone on it, which also shuts, which updates
     * <i>its</i> neighbours. One edge anywhere against a stacked bank walks the
     * whole bank shut in a single tick. A blowdown the player had commanded and
     * could see commanded was being interrupted by a comparator two blocks
     * away.
     *
     * <p>The claim is released the moment ADS stops driving the valve, so the
     * valve goes back to answering redstone rather than being frozen out of it
     * forever by a controller that has finished with it. Same at
     * {@link #detach()}, for a controller that is being broken while it holds a
     * bank open. This is the ordinary claim protocol the pumps and the turbine
     * outlet already use, and it decides <i>who</i> commands the valve — never
     * whether the valve should be open, which stays the player's.
     */
    private static void setValve(Level level, BlockPos pos, SafetyReliefValveBlockEntity srv,
                                 boolean openIt) {
        if (srv.isComputerControlled() != openIt) {
            srv.setComputerControlled(openIt);
        }
        if (srv.isOpen() != openIt) {
            srv.setOpen(openIt);
        }
        BlockState s = level.getBlockState(pos);
        if (s.hasProperty(SafetyReliefValveBlock.OPEN)
                && s.getValue(SafetyReliefValveBlock.OPEN) != openIt) {
            // Clients only: a neighbour update here would re-enter this
            // controller's own validation on every valve stroke.
            level.setBlock(pos, s.setValue(SafetyReliefValveBlock.OPEN, openIt),
                    Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Post each valve's flow to the bus and return the bank total.
     *
     * <p><b>The contribution is keyed on the valve, not on this controller.</b>
     * Contributions in {@link ReactorEccsBus} are summed across keys, so keying
     * on the controller meant two ADS controllers within sight of the same
     * relief bank — a perfectly ordinary redundancy build — each reported the
     * whole bank under a different key and the vessel blew down at twice the
     * physical rate. Keyed on the valve, a second reporter simply overwrites the
     * same entry with the same number, and the steam is counted exactly once
     * however many controllers or suppression pools can see it. That is also
     * what lets the suppression pool report the same valves (so a redstone-only
     * relief scheme with no ADS anywhere still takes steam out of the vessel)
     * without either half being double-counted.
     */
    private double reportReliefFlow(Level level, ReactorEccsBus bus, double domePsig) {
        double total = 0.0;
        long gameTime = level.getGameTime();
        for (BlockPos vp : valves) {
            if (!(level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)) {
                continue;
            }
            double flow = srv.flowKgPerS(domePsig);
            total += flow;
            srv.reportRelief(bus,gameTime,flow);
        }
        return total;
    }

    // --- Binding --------------------------------------------------------

    public void setDivision(int value){
        int next=Math.clamp(value,0,4);if(next==division)return;
        if(level!=null)for(BlockPos vp:held)if(level.isLoaded(vp)&&level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv)setValve(level,vp,srv,false);
        division=next;bindingDirty=true;ticksSinceRebind=REBIND_INTERVAL_TICKS;setChanged();
    }
    public void markBindingDirty() {
        bindingDirty = true;
    }

    /**
     * Re-find the reactor and the valve bank.
     *
     * <p>Two intervals. {@link #REBIND_INTERVAL_TICKS} is the floor while
     * something is missing or something has said the binding changed: the scan
     * is a 49-cube and it walks downward from every relief valve looking for
     * water, so running it on every redstone edge would be expensive for no
     * gain. {@link #BOUND_REBIND_INTERVAL_TICKS} is the slow sweep that runs
     * even when the controller is perfectly happy, and it is the one that lets
     * a valve added to the bank later be noticed at all.
     */
    private void maybeRebind(Level level) {
        if (ticksSinceRebind < Integer.MAX_VALUE) {
            ticksSinceRebind++;
        }
        boolean settled = !bindingDirty && reactorPos != null && !valves.isEmpty();
        if (ticksSinceRebind < (settled ? BOUND_REBIND_INTERVAL_TICKS : REBIND_INTERVAL_TICKS)) {
            return;
        }
        rebind(level);
        bindingDirty = false;
        ticksSinceRebind = 0;
    }

    /**
     * Take this controller off its reactor's bus and let go of its valves.
     *
     * <p>Releasing the claim matters as much as withdrawing from the bus. A
     * controller broken while it held a bank open would otherwise leave every
     * one of those valves flagged computer-controlled with nothing left to
     * command them: {@code SafetyReliefValveBlock.neighborChanged} would keep
     * skipping the redstone branch, so the valves would sit open forever,
     * blowing the vessel down, deaf to the switch the player reaches for. The
     * valves keep whatever position they are in — which is what a solenoid rack
     * losing its supply actually does — and answer redstone again from the next
     * neighbour update.
     */
    public void detach() {
        if (level == null) {
            return;
        }
        if (reactorPos != null) {
            EccsNetwork.withdraw(level, reactorPos, getBlockPos());
        }
        for (BlockPos vp : held) {
            if (level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv) {
                srv.setComputerControlled(false);
            }
        }
        held.clear();
    }

    private void rebind(Level level) {
        BlockPos from = getBlockPos();
        reactorPos = null;
        valves.clear();
        for (BlockPos p : BlockPos.betweenClosed(
                from.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                from.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (!level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (reactorPos == null && s.is(BwrBlocks.REACTOR_CONTROLLER.get())) {
                reactorPos = p.immutable();
            } else if ((s.getBlock() instanceof dev.bwr.mod.steam.SafetyReliefValveBlock)
                    && level.getBlockEntity(p) instanceof SafetyReliefValveBlockEntity srv) {
                // A valve venting into air suppresses nothing and is not part of
                // a depressurisation the containment can survive, so it is not
                // counted. That is validation of plumbing, not a permissive.
                if (srv.getDivision()==division && srv.revalidateDischarge(level)) {
                    valves.add(p.immutable());
                }
            }
        }
        // Valves that have left the bank are released, not merely forgotten.
        // `held.retainAll(valves)` dropped a valve whose discharge had stopped
        // being submerged — someone drained the pool under it — while leaving it
        // flagged computer-controlled with nothing driving it any more, which is
        // the same valve deaf to its own redstone that detach() exists to
        // prevent. A valve that is out of the bank goes back to the player.
        for (Iterator<BlockPos> it = held.iterator(); it.hasNext(); ) {
            BlockPos vp = it.next();
            if (!valves.contains(vp)) {
                // Keep the pending release until the valve is loaded again.
                if (!level.isLoaded(vp)) continue;
                if (level.getBlockEntity(vp) instanceof SafetyReliefValveBlockEntity srv) {
                    srv.setComputerControlled(false);
                }
                it.remove();
            }
        }
    }

    private ReactorControllerBlockEntity reactor(Level level) {
        if (reactorPos == null || !level.isLoaded(reactorPos)) {
            return null;
        }
        return level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity c ? c : null;
    }

    // --- Actuators ------------------------------------------------------

    /**
     * Blow the vessel down. Unconditional: it does not look at level, at
     * pressure, at whether the low pressure pumps are even running, or at
     * whether the suppression pool has any capacity left. Every one of those
     * judgements is the player's, in Lua.
     */
    public void setOpen(boolean open) {
        // Marshalled onto the server thread: Lua calls this from a CC computer
        // thread and setChanged() dispatches neighbour updates. See
        // PlantActuators.
        PlantActuators.run(this, () -> {
            this.open = open;
            setChanged();
        });
    }

    public boolean isOpen() {
        return open;
    }

    /** What fraction of the available valve bank to hold open, 0 to 1. */
    public void setValveDemandFraction(double fraction) {
        PlantActuators.run(this, () -> {
            if (Double.isFinite(fraction)) {
                this.valveDemandFraction = Math.min(1.0, Math.max(0.0, fraction));
                setChanged();
            }
        });
    }

    public double getValveDemandFraction() {
        return valveDemandFraction;
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        PlantActuators.run(this, () -> {
            this.computerControlled = computerControlled;
            setChanged();
        });
    }

    // --- Measurements ---------------------------------------------------

    /** Relief valves this controller can actually use — present and submerged. */
    public int getValveCount() {
        return valves.size();
    }

    /** Valves this controller is currently holding open. */
    public int getHeldOpenCount() {
        return held.size();
    }

    /** Steam leaving the vessel through every open valve on this bank, kg/s. */
    public double getBlowdownKgPerS() {
        return blowdownKgPerS;
    }

    /** Pneumatic charge left in the nitrogen bottles, 0 to 1. */
    public double getNitrogenCharge() {
        return nitrogenCharge;
    }

    public EnergyStorage energy() {
        return energy;
    }

    public boolean hasReactor() {
        return reactorPos != null;
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT,
                "Division: %d relief valves available, %d held open, demand %.0f%%",
                valves.size(), held.size(), valveDemandFraction * 100.0));
        out.add(String.format(Locale.ROOT, "Blowing down %.1f kg/s; nitrogen %.0f%%; %,d FE stored",
                blowdownKgPerS, nitrogenCharge * 100.0, energy.getEnergyStored()));
        if (valves.isEmpty()) {
            out.add("No relief valve within " + SEARCH_RADIUS
                    + " blocks discharges underwater; there is nothing to blow down through.");
        }
        if (nitrogenCharge <= 0.0 && open) {
            out.add("Nitrogen bottles are empty; the valves have shut themselves.");
        }
        if (reactorPos == null) {
            out.add("No reactor controller found within " + SEARCH_RADIUS + " blocks.");
        }
        return out;
    }

    /**
     * A stored fraction as a usable 0..1, with a non-finite value replaced by
     * {@code fallback} rather than propagated.
     *
     * @param fallback what a hardware fraction reads as when nothing sensible
     *                 was saved — full, for both of the fields that use this,
     *                 because a fresh nitrogen bottle is full and an ADS with no
     *                 demand recorded is one that will open its whole bank
     */
    private static double clampFraction(double value, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    // --- Persistence -----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Division",division);
        tag.putBoolean("Open", open);
        tag.putDouble("ValveDemand", valveDemandFraction);
        tag.putDouble("Nitrogen", nitrogenCharge);
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putInt("Energy", energy.getEnergyStored());
        if (reactorPos != null) {
            tag.putLong("Reactor", reactorPos.asLong());
        }
        ListTag heldTag = new ListTag();
        for (BlockPos p : held) {
            heldTag.add(LongTag.valueOf(p.asLong()));
        }
        tag.put("Held", heldTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        division=Math.clamp(tag.getInt("Division"),0,4);
        open = tag.getBoolean("Open");
        // Sanitised on the way in, for the reason the suppression pool's load
        // path spells out: clamping with Math.min/Math.max does not stop a
        // non-finite value, it propagates one. Both of these fields are only
        // ever clamped that way at their point of use, so a NaN read off disk
        // stays NaN for the life of the world — and a NaN here does not fail
        // loudly, it fails silent and permanent. `nitrogenCharge > 0.0` is
        // false against NaN, so `wanted` is zero and the bank never opens
        // however hard the player pulls the lever; the recharge line then
        // writes NaN straight back over itself, so no amount of compressor
        // power recovers it. A NaN valve demand rounds to zero and does the
        // same thing one layer down. Either way the readouts say 0% and there
        // is nothing anywhere to say why. Neither field has a live writer that
        // can produce one, which is exactly why this is the load path's job:
        // it is old saves and hand-edited NBT this is standing under.
        valveDemandFraction = tag.contains("ValveDemand")
                ? clampFraction(tag.getDouble("ValveDemand"), 1.0) : 1.0;
        nitrogenCharge = tag.contains("Nitrogen")
                ? clampFraction(tag.getDouble("Nitrogen"), 1.0) : 1.0;
        computerControlled = tag.getBoolean("ComputerControlled");
        energy.setStored(tag.getInt("Energy"));
        reactorPos = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : null;
        held.clear();
        ListTag heldTag = tag.getList("Held", Tag.TAG_LONG);
        for (int i = 0; i < heldTag.size(); i++) {
            held.add(BlockPos.of(((LongTag) heldTag.get(i)).getAsLong()));
        }
        bindingDirty = true;
    }
}
