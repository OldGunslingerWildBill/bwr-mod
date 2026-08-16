package dev.bwr.mod.reactor;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.ReactorState;
import dev.bwr.mod.damage.BoundaryDamageNbt;
import dev.bwr.mod.damage.BoundaryDamageReadout;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import dev.bwr.mod.rods.ControlRodDriveNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The reactor. Owns the {@link ReactorCore} physics instance, ticks it once per
 * game tick server-side, and holds the structure it is attached to.
 *
 * <h2>What this class does not do</h2>
 * It does not scram on anything. It does not trip, alarm, interlock, or protect.
 * The plant will happily run itself to destruction if nobody is watching,
 * because the reactor protection system is the player's to write in CC:Tweaked.
 * The only automatic consequence in the whole plant is material damage from
 * abuse, and that is physics rather than policy.
 *
 * <h2>Persistence</h2>
 * Freeze and resume, per {@code SPEC.md} section 11. The entire
 * {@link ReactorState} is serialised, so a reactor unloaded mid-transient
 * resumes mid-transient — the player comes back to exactly the situation they
 * left, minus all their situational awareness.
 */
public class ReactorControllerBlockEntity extends BlockEntity {

    /** Client sync interval in ticks. 5 ticks is 4 Hz, per SPEC section 14. */
    private static final int SYNC_INTERVAL_TICKS = 5;

    /**
     * How often the structure is re-checked even when nothing told us to.
     *
     * <p>Validation is driven by {@link #markStructureDirty()}, but vanilla only
     * fires {@code neighborChanged} on the six blocks orthogonally adjacent to
     * the one that changed, so only a block <i>touching the controller</i> can
     * announce itself. Mining a vessel block on the far wall, or a sparger
     * segment, used to leave the plant formed with a hole in it: the vessel went
     * on holding 1025 psig through the gap and nothing ever noticed. Five
     * seconds is slow enough that the scan cost is irrelevant — one pass is
     * bounded by the 21x21x21 maximum interior, and it only runs on a controller
     * whose chunk is loaded — and fast enough that a player who breaks something
     * sees the consequence while they are still standing there.
     *
     * <p>This is safe to do only because re-validating a structure no longer
     * rebuilds the core; see {@link #revalidate}.
     */
    private static final int REVALIDATE_INTERVAL_TICKS = 100;

    /**
     * Startup neutron source installed in every core this mod builds.
     *
     * <p>Not a physics constant and not a default: it is a piece of hardware the
     * plant is built with, so the block entity has to install it the same way
     * the standalone harness and every acceptance test do. {@code CoreConfig}
     * ships 1.0e-12, which is three orders of magnitude low — a healthy
     * shut-down core sits at about 0.015 cps against a 3.0 cps bottom of scale,
     * so every source range channel reads 00.00 on a perfectly good plant,
     * indistinguishable from a de-energised chain or a failed chamber, and the
     * 1/M approach to critical that the source term exists to make possible
     * cannot be plotted at all. Sized in {@code TransientHarness}, which is
     * referenced rather than copied so the two cannot drift apart; it is a
     * compile-time constant, so nothing of the harness reaches a running game.
     */
    private static final double INSTALLED_NEUTRON_SOURCE_PER_SECOND =
            dev.bwr.core.harness.TransientHarness.CALIBRATED_SOURCE_PER_SECOND;

    /**
     * Steam discharged through an open vessel head, kg/s per psi of gauge
     * pressure. See {@link #applyVesselHeadDischarge}.
     *
     * <p>Sized as choked flow through the head opening rather than picked to
     * taste. A BWR/6 vessel is about 6.4 m across, so the open head is roughly
     * 32 m2, and choked steam at atmospheric conditions passes on the order of
     * 160 kg/s per m2 — about 5200 kg/s through the whole opening at one
     * atmosphere of differential, which is the 350 kg/s per psi below.
     *
     * <p>The number that matters is the comparison: rated steam generation is
     * about 1940 kg/s, so a core at <b>full power</b> with the head off settles
     * at some 6 psig, and one on decay heat sits a fraction of a psi above
     * containment. That is the intended answer — an open vessel does not hold
     * pressure, whatever the core is doing — and it is why the exact
     * coefficient is not delicate. It is also gentle numerically: the vessel's
     * pressure capacity is hundreds of kg per psi, so this is a time constant
     * of a second or two against a 50 ms tick, nowhere near stiff.
     */
    private static final double OPEN_HEAD_DISCHARGE_KG_PER_S_PER_PSI = 350.0;

    private final CoreConfig config = new CoreConfig();
    private ReactorCore core;
    private ControlRodDriveNetwork rodNetwork;

    private ReactorStructure structure;
    private ValidationResult lastValidation = new ValidationResult();
    private boolean structureDirty = true;
    private int sinceRevalidate;
    private VesselState vesselState = VesselState.SHUTDOWN;

    /** Satellite recirculation pumps that have announced themselves. */
    private final Set<BlockPos> pumpPositions = new LinkedHashSet<>();

    private int sinceSync;
    private ReactorState pendingRestore;

    /**
     * The rod demand pattern as it was saved, held until a network exists to put
     * it in. {@link ReactorState} has no component for rod demand, so without
     * this a withdrawal in flight when the chunk unloaded comes back collapsed
     * onto the notch the rods had reached.
     */
    private int[] pendingRodDemand;

    /**
     * The recirculation flow fraction this controller last derived from its
     * pumps, or NaN before it has derived one. See {@link #gatherPumpFlow}.
     */
    private double lastPumpFlowFraction = Double.NaN;

    /**
     * The vessel geometry the rod-to-lattice map in the solver was built for,
     * or {@link Long#MIN_VALUE} before one has been supplied.
     * See {@link #installRodLatticeMap}.
     */
    private long rodLatticeMapSignature = Long.MIN_VALUE;

    /**
     * Steam this controller last discharged through an open head, kg/s.
     * See {@link #applyVesselHeadDischarge}.
     */
    private double lastHeadDischargeKgPerS;

    /**
     * Overpressure damage, held across a rebuild of the core object. Damage is
     * a property of the plant's metal rather than of whatever {@link ReactorCore}
     * instance happens to be modelling it, so it survives chunk unload and it
     * survives a vessel block being replaced.
     */
    private net.minecraft.nbt.CompoundTag pendingBoundaryRestore;

    /**
     * The core loading as it was saved, held until a structure exists to put it
     * in. Fuel is not part of {@link ReactorState} — exposure lives on the
     * bundle, per SPEC section 2.3 — so the lattice is persisted alongside it.
     */
    private net.minecraft.nbt.ListTag pendingFuelRestore;

    public ReactorControllerBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.REACTOR_CONTROLLER.get(), pos, state);
    }

    // -----------------------------------------------------------------
    // Ticking
    // -----------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  ReactorControllerBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        if (++sinceRevalidate >= REVALIDATE_INTERVAL_TICKS) {
            // A member block that is not a direct neighbour of the controller
            // cannot announce its own removal, so the structure is swept on a
            // timer as well as on notification. See REVALIDATE_INTERVAL_TICKS.
            sinceRevalidate = 0;
            structureDirty = true;
        }
        if (structureDirty) {
            revalidate(level);
            structureDirty = false;
        }
        if (core == null || structure == null) {
            return;
        }

        gatherPumpFlow(level);
        applyVesselHeadDischarge();

        // Order matters. The drive network moves rods and spends accumulator
        // charge before the physics steps, then writes hardware condition back
        // afterwards, so the core sees a consistent rod configuration for the
        // whole tick.
        rodNetwork.preStep(config.tickSeconds);
        core.step();
        rodNetwork.postStep();

        if (++sinceSync >= SYNC_INTERVAL_TICKS) {
            sinceSync = 0;
            syncToClients();
        }
        setChanged();
    }

    /**
     * Sum flow from every satellite pump. An unpowered or broken pump simply
     * contributes nothing — losing a pump reduces flow, it never invalidates
     * the multiblock or stops the reactor ({@code SPEC.md} section 4.2).
     */
    private void gatherPumpFlow(Level level) {
        double total = 0.0;
        var iterator = pumpPositions.iterator();
        while (iterator.hasNext()) {
            BlockPos p = iterator.next();
            if (!level.isLoaded(p)) {
                // Not "the pump is gone", only "we cannot see it from here".
                // Level.getBlockEntity returns null for an unloaded chunk
                // exactly as it does for a removed block, and the association is
                // saved, so treating the two the same made a pump 12 blocks away
                // in a neighbouring chunk permanently deregister itself the
                // moment that chunk unloaded. Nothing ever re-adds one —
                // addPump is only reached from block placement — so the plant
                // ran on fewer pumps than were installed for good. Keep the
                // registration; it contributes nothing while it is not ticking.
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof RecirculationPumpBlockEntity pump)) {
                iterator.remove(); // pump was broken or replaced
                continue;
            }
            pump.tickPump(config.tickSeconds);
            total += pump.getActualSpeedFraction();
        }
        // Pumps ADD capacity: flow scales with pump count times speed
        // ({@code SPEC.md} section 4.2 and 4.4), clamped because rated flow is
        // rated flow. It used to be the arithmetic mean over the registered
        // pumps, which meant placing a second, not-yet-spinning pump next to a
        // running one instantly halved core flow — void rose, power fell about
        // 30%, all from putting a block down. A mean also makes recirculation
        // capacity independent of how many pumps you built, which is the
        // opposite of the intended mechanic.
        double fraction = Math.min(1.0, total);

        // Two writers, one field. The pumps are the plant's own hardware, and
        // ReactorPeripheral.setRecirculationFlow is a player actuator that
        // writes the same core demand from the computer thread. Pushing the
        // pump figure unconditionally every tick made the Lua write dead on
        // arrival: it survived at most one tick and the program could not even
        // detect that it had been ignored, so flow control — the defining BWR
        // manoeuvre, and the only handle on power that does not move a rod —
        // was unreachable from a computer.
        //
        // So the pumps write on CHANGE, not on level. They take the demand back
        // the moment they actually move, which is what "the hardware is the
        // authority" means; between moves whatever wrote last stands. Nothing
        // here decides which writer is right — it is last-writer-wins, with the
        // pumps counting as a writer only when they have something new to say.
        if (fraction != lastPumpFlowFraction
                || core.getRecirculationFlowFractionDemand() == lastPumpFlowFraction) {
            core.setRecirculationFlowFraction(fraction);
            lastPumpFlowFraction = fraction;
        }
    }

    /**
     * A vessel with its head off vents to containment.
     *
     * <h2>Why this exists, and why it is a leak rather than a pressure clamp</h2>
     * {@link VesselState#canHoldPressure()} had no consumer at all, so removing
     * the head was a refuelling gate and nothing else: open the head at 20 psig,
     * close the screen, withdraw rods and the plant pressurised normally through
     * an opening the size of the vessel. The head is hardware and an open hole
     * is a physical fact, not a policy, so it belongs in the model.
     *
     * <p>It is deliberately <b>not</b> done by pinning dome pressure. The vessel
     * model owns pressure; what an open head physically is, is a very large flow
     * area to containment, and {@code ReactorCore.setSteamLeakKgPerS} is exactly
     * a discharge path out of the steam space — added to whatever the boundary
     * damage model is already pouring out of its own breaks, so this never plugs
     * a hole the plant tore in itself. No core change was needed and none was
     * made.
     *
     * <p>Steam only. The opening is the top head, so the vessel does not drain
     * through it; the inventory still goes down, because the water boils and the
     * steam leaves.
     *
     * <h2>This controller owns the manual steam leak</h2>
     * The core holds one scalar for it, so there can only be one writer, the
     * same way {@code ControlRodDriveNetwork} owns {@code setRodNotchDemand} and
     * for the same reason. Anything added later that wants to stage a leak —
     * a scenario command, a peripheral actuator — must add its flow to what this
     * method computes rather than writing the core directly, or it will survive
     * exactly one tick. The write is skipped entirely while the head is on and
     * nothing is being discharged, so a value written from elsewhere is at least
     * not stamped on every tick of normal operation.
     */
    private void applyVesselHeadDischarge() {
        double discharge = vesselState.canHoldPressure()
                ? 0.0
                // Flow stops when the vessel reaches containment pressure, which
                // is where an open vessel sits. Keying on gauge pressure is what
                // keeps this from dragging the dome down to the pressure model's
                // numerical floor, some 14 psi below atmospheric.
                : OPEN_HEAD_DISCHARGE_KG_PER_S_PER_PSI * Math.max(0.0, core.getPressurePsig());
        if (discharge == 0.0 && lastHeadDischargeKgPerS == 0.0) {
            return;
        }
        core.setSteamLeakKgPerS(discharge);
        lastHeadDischargeKgPerS = discharge;
    }

    // -----------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------

    /** Called on neighbour change; validation is deferred to the next tick. */
    public void markStructureDirty() {
        structureDirty = true;
    }

    /**
     * Re-derive the structure, and rebuild the core <b>only</b> if the vessel in
     * front of us is genuinely a different reactor.
     *
     * <h2>The rule, and why it is the rod count and not the structure field</h2>
     * The test used to be {@code structure == null || rod count changed}, and
     * because a failed validation sets {@code structure} to null, having once
     * been broken forced the very rebuild the null was there to avoid. Breaking
     * a single vessel block and putting it straight back therefore ran
     * {@code new ReactorCore(config)}: every loaded fuel assembly was discarded
     * with no item drop and no message, the running transient and its decay heat
     * inventory went with it, and {@code initialiseHotShutdown()} reset the
     * boundary damage model, healing accumulated overpressure stress and even
     * repairing broken lines. The next autosave wrote the empty lattice and made
     * all of it permanent. It was a free full repair of a wrecked plant, and a
     * free deletion of the player's entire fuel cycle, from breaking and
     * replacing one block.
     *
     * <p>So the question a rebuild answers is "does this core have the wrong
     * number of rods for this vessel", and {@code core.getControlRodCount()}
     * answers it directly. Re-forming a vessel of the same size keeps the core
     * object, and with it the fuel, the transient and the damage record; only
     * the drives are re-bound.
     *
     * <p>When a rebuild <i>is</i> required — the player resized the vessel — the
     * live core is snapshotted into the pending fields first, so the rebuild
     * restores from what is actually in the world rather than from an on-disk
     * snapshot that was consumed and nulled on the first load.
     */
    private void revalidate(Level level) {
        ValidationResult result = new ValidationResult();
        ReactorStructure found = ReactorStructure.validate(level, getBlockPos(), result);
        lastValidation = result;

        if (found == null) {
            structure = null;
            // The core is kept, not discarded: breaking one vessel block should
            // not delete a running transient and all its decay heat. That
            // promise is only worth anything because the rebuild test below
            // ignores this field.
            return;
        }

        structure = found;
        boolean rebuild = core == null || core.getControlRodCount() != found.controlRodCount();

        // Kept truthful whether or not a rebuild follows. Both are copied into
        // the core at construction and never read again, so writing them here
        // only ever affects the next core built — which is the point.
        config.controlRodCount = found.controlRodCount();
        config.assemblyCount = found.assemblyCount();

        if (rebuild) {
            if (core != null) {
                // Snapshot before discarding. The pending fields are consumed
                // and nulled the first time they are used, so on any rebuild
                // after the first there is nothing on disk left to restore from
                // and the world in front of us is the only authority.
                pendingRestore = core.toState();
                pendingFuelRestore = writeCoreFuel();
                pendingBoundaryRestore = BoundaryDamageNbt.write(core.getBoundaryStress());
                pendingRodDemand = rodNetwork == null
                        ? null : rodNetwork.getCommandedNotchIndices();
            }
            core = new ReactorCore(config);
            // A calibrated startup source, because a plant is built with one.
            // Before any restore: fromState carries the source strength the
            // reactor was saved with, and a saved reactor's source is its own.
            core.setNeutronSourceStrengthPerSecond(INSTALLED_NEUTRON_SOURCE_PER_SECOND);
            // Before the hot shutdown initialisation, because the fuel decides
            // what the reactivity balance it settles is.
            applyCoreFuel();
            core.initialiseHotShutdown();
            rodNetwork = new ControlRodDriveNetwork(core, found.controlRodCount());
            if (pendingRestore != null) {
                tryRestore(pendingRestore);
                pendingRestore = null;
            }
            // After tryRestore, never before. fromState puts the rods back where
            // the player left them, and the network's demand array is what
            // preStep writes into the core every tick — so a network still
            // holding the pre-restore pattern drives every rod straight back
            // out of it. Left unsynchronised, and zero being
            // NOTCH_INDEX_FULLY_INSERTED rather than "no demand", a fresh
            // network shut the reactor down on formation and on every single
            // chunk reload, with no scram signal to explain it.
            rodNetwork.setCore(core);
            if (pendingRodDemand != null) {
                // A demand in flight when the chunk unloaded outranks the
                // positions the rods had reached; if the pattern does not fit
                // this core the achieved positions setCore just installed stand.
                rodNetwork.restoreCommandedNotchIndices(pendingRodDemand);
                pendingRodDemand = null;
            }
            // The pumps have to re-establish their claim on the flow demand
            // against a core that has never heard from them.
            lastPumpFlowFraction = Double.NaN;
            // After the core is built and restored, because
            // initialiseHotShutdown() deliberately resets the damage model to a
            // pristine plant and this puts the plant's real history back.
            BoundaryDamageNbt.seedFrom(core.getBoundaryStress(), getBlockPos());
            if (pendingBoundaryRestore != null) {
                BoundaryDamageNbt.read(pendingBoundaryRestore, core.getBoundaryStress());
                pendingBoundaryRestore = null;
            }
            // After the restore, not before: the snapshot carries the
            // configuration the plant had when it was saved, and the structure
            // in front of us now is the authority on the one it has today.
            //
            // Zero reactor internal pumps, because the RIP block of
            // SPEC section 4.4 has not been built yet. This one call is the
            // whole wiring that upgrade needs — the damage model already
            // handles both configurations and is tested on both.
            core.getBoundaryStress().setPlantConfiguration(
                    BoundaryDamageNbt.configurationFor(0));
        }

        // This is what makes rod i in the physics the same rod as drive i in
        // the world. Called on every validation rather than only on a rebuild,
        // because a core can arrive without a map for reasons the rebuild flag
        // does not cover; the method itself decides whether anything moved.
        installRodLatticeMap(found);
        bindDrives(level);
    }

    /**
     * Tell the nodal solve which lattice positions each drive actually shadows.
     *
     * <h2>Why this call is load-bearing</h2>
     * {@link ReactorStructure#validate} numbers rods in raster order over the
     * interior, so rod 0 is a <b>corner</b> drive, and {@link #bindDrives} binds
     * the drive at {@code crdPositions().get(i)} to rod {@code i}.
     * {@code RodLatticeMap.centreOutward}, which is what the solver builds for
     * itself when nobody supplies a map, numbers rods by distance rank, so
     * <i>its</i> rod 0 owns the centre-most 2x2 group. Left unsupplied, the two
     * index spaces are a permutation of each other and the mismatch is silent:
     * every derived number stays self-consistent while describing a different
     * reactor. Withdrawing the corner drive puts the hot spot in the middle of
     * the core, and a stuck peripheral rod is modelled as a stuck central one.
     * <b>Do not delete this call on the grounds that the solver manages without
     * it.</b> It manages, it is simply wrong.
     *
     * <p>The map is geometry, not occupancy, so it is rebuilt only when the
     * vessel's shape or its rod count moves, or when a rebuilt core has arrived
     * with no map of its own. {@code hasExplicitRodLatticeMap()} is what
     * distinguishes the second case; a fresh {@link ReactorCore} answers false
     * there, and this runs on the five-second revalidation sweep as well as on
     * formation, so re-supplying it unconditionally would allocate a 961-entry
     * map several times a minute for nothing.
     */
    private void installRodLatticeMap(ReactorStructure found) {
        int latticeWidth = core.getCoreLoading().latticeWidth();
        int width = found.interiorMax().getX() - found.interiorMin().getX() + 1;
        int depth = found.interiorMax().getZ() - found.interiorMin().getZ() + 1;
        // Disjoint bit ranges, so this is a lossless key rather than a hash:
        // the interior is bounded by MAX_INTERIOR, the rod count by a quarter of
        // its square, and the lattice by CoreLoading.
        long signature = ((long) width << 48) | ((long) depth << 32)
                | ((long) found.controlRodCount() << 16) | latticeWidth;
        if (signature == rodLatticeMapSignature
                && core.getNodalFluxSolver().hasExplicitRodLatticeMap()) {
            return;
        }
        core.getNodalFluxSolver().setRodLatticeMap(found.rodLatticeMap(latticeWidth));
        rodLatticeMapSignature = signature;
    }

    /** Attach each drive block to its slot in the network. */
    private void bindDrives(Level level) {
        rodNetwork.unbindAll();
        List<BlockPos> crds = structure.crdPositions();
        for (int i = 0; i < crds.size(); i++) {
            if (level.getBlockEntity(crds.get(i)) instanceof ControlRodDriveBlockEntity drive) {
                drive.attach(this, i);
                rodNetwork.bind(i, drive.hardware());
            }
        }
    }

    /** Register a satellite pump. Called by the pump when it is placed. */
    public void addPump(BlockPos pos) {
        pumpPositions.add(pos);
        setChanged();
    }

    /** Deregister a satellite pump. Called when it is broken. */
    public void removePump(BlockPos pos) {
        pumpPositions.remove(pos);
        setChanged();
    }

    /** Satellite pumps currently attached, however many of them are running. */
    public int pumpCount() {
        return pumpPositions.size();
    }

    // -----------------------------------------------------------------
    // Accessors for the peripheral and the world
    // -----------------------------------------------------------------

    public ReactorCore core() {
        return core;
    }

    public ControlRodDriveNetwork rods() {
        return rodNetwork;
    }

    public ReactorStructure structure() {
        return structure;
    }

    public boolean isFormed() {
        return structure != null && core != null;
    }

    public VesselState vesselState() {
        return vesselState;
    }

    /**
     * Move the head. Refuses to open a vessel that still holds pressure — that
     * is a physical impossibility, not a permissive: you cannot unbolt a head
     * against a thousand psi.
     *
     * @return null on success, or the reason it was refused
     */
    public String setVesselState(VesselState next) {
        if (next == VesselState.REFUELING && core != null) {
            double psig = core.getPressurePsig();
            if (psig > 25.0) {
                return "cannot remove the head at " + Math.round(psig)
                        + " psig; the vessel must be depressurised first";
            }
        }
        vesselState = next;
        setChanged();
        return null;
    }

    public double sprayRingCompleteness() {
        return structure == null ? 0.0 : structure.sprayRingCompleteness();
    }

    // -----------------------------------------------------------------
    // Refuelling — SPEC section 10
    // -----------------------------------------------------------------

    /**
     * How many core positions this vessel has. The lattice
     * {@code CoreLoading} indexes is a fixed 31x31 square; this is how much of
     * it a vessel of this footprint actually uses, and
     * {@link dev.bwr.mod.gui.CoreLattice} turns the two into the centre-outward
     * slot ordering the GUI and the wire format share.
     */
    public int assemblyCount() {
        return structure == null ? 0 : structure.assemblyCount();
    }

    /** Lattice square for each core slot, centre-outward. Empty until formed. */
    public int[] corePositions() {
        if (core == null || structure == null) {
            return new int[0];
        }
        return dev.bwr.mod.gui.CoreLattice.corePositions(
                core.getCoreLoading().latticeWidth(), structure.assemblyCount());
    }

    /**
     * Why fuel cannot be moved right now, or null if it can.
     *
     * <p>This names the condition that is actually in the way rather than
     * saying no. It is not a permissive and not a setpoint: an open vessel is a
     * physical prerequisite for lifting a bundle out of a core, and the head is
     * held on by a thousand psi of steam, not by an interlock.
     */
    public String refuellingBlockedReason() {
        if (!isFormed()) {
            return "the reactor is not formed, so there is no core to refuel";
        }
        if (vesselState == VesselState.REFUELING) {
            return null;
        }
        double psig = core.getPressurePsig();
        if (psig > 25.0) {
            return "the vessel head is on, and it cannot be removed at "
                    + Math.round(psig) + " psig; depressurise below 25 psig first";
        }
        return "the vessel head is on; the vessel is at " + Math.round(psig)
                + " psig so the head can be removed, but nobody has removed it yet";
    }

    /**
     * Take the bundle out of a lattice position and hand it back as an item
     * carrying its exposure. Empty stack if the position was empty.
     */
    public ItemStack unloadAssembly(int latticePosition) {
        if (core == null) {
            return ItemStack.EMPTY;
        }
        dev.bwr.core.fuel.FuelAssembly assembly = core.getCoreLoading().unload(latticePosition);
        if (assembly == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get());
        dev.bwr.mod.fuel.FuelAssemblies.store(stack, assembly);
        setChanged();
        return stack;
    }

    /**
     * Put a bundle into an empty lattice position, exposure and all.
     *
     * @return false if the position was already occupied or the stack is not a bundle
     */
    public boolean loadAssembly(int latticePosition, ItemStack stack) {
        if (core == null || stack.isEmpty()
                || !stack.is(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get())) {
            return false;
        }
        if (core.getCoreLoading().isOccupied(latticePosition)) {
            return false;
        }
        core.getCoreLoading().load(latticePosition,
                dev.bwr.mod.fuel.FuelAssemblies.load(stack));
        setChanged();
        return true;
    }

    /** Exchange two lattice positions. Exposure travels with the bundle. */
    public void swapAssemblies(int a, int b) {
        if (core != null) {
            core.getCoreLoading().swap(a, b);
            setChanged();
        }
    }

    /**
     * Put the saved core loading back, or empty the core if there is none.
     *
     * <p>A freshly built vessel arrives <b>empty</b>. {@code ReactorCore}
     * constructs itself around a full core of fresh LEU because its standalone
     * harness and its acceptance tests need fuel in it, but a multiblock a
     * player has just welded together has not been fuelled yet, and handing them
     * 748 free bundles they could pull straight back out through the refuelling
     * screen would delete the entire fuel cycle. Fuel comes out of the
     * fabricator.
     *
     * <p><b>The {@code unloadAll()} below is only ever safe because this method
     * is called on the very next statement after {@code new ReactorCore(config)}
     * and on no other path.</b> The bundles it discards are the synthetic fresh
     * LEU the constructor loads for the benefit of the standalone harness, never
     * anything a player fabricated. {@code CoreLoading.unloadAll} is
     * {@code Arrays.fill(positions, null)} — the assemblies are dropped on the
     * floor, not returned as items — so calling this against a fuelled core
     * destroys up to 441 bundles and their accumulated exposure with no item
     * drop and no message. {@link #revalidate} snapshots the loading into
     * {@code pendingFuelRestore} before it discards a live core for exactly this
     * reason. Do not call this from anywhere else.
     */
    private void applyCoreFuel() {
        var loading = core.getCoreLoading();
        loading.unloadAll();
        if (pendingFuelRestore == null) {
            return;
        }
        int lattice = loading.latticeWidth();
        for (int i = 0; i < pendingFuelRestore.size(); i++) {
            CompoundTag entry = pendingFuelRestore.getCompound(i);
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= lattice * lattice) {
                continue;
            }
            dev.bwr.mod.fuel.FuelAssemblyData.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, entry.get("Fuel"))
                    .resultOrPartial(message -> lastValidation.degrade(
                            "a saved fuel bundle at core position " + slot
                                    + " could not be read: " + message))
                    .ifPresent(data -> loading.load(slot,
                            dev.bwr.mod.fuel.FuelAssemblies.toCore(data)));
        }
        pendingFuelRestore = null;
    }

    /** Human-readable status, shown when a player right-clicks the controller. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (structure == null) {
            out.add("Reactor not formed.");
            out.addAll(lastValidation.messages());
            if (out.size() == 1) {
                out.add("No vessel found around this controller.");
            }
            return out;
        }
        out.add("Reactor formed: " + structure.assemblyCount() + " assemblies, "
                + structure.controlRodCount() + " control rods, vessel " + vesselState.getSerializedName());
        if (core != null) {
            out.add(String.format("%.2f%% rated, %.0f psig, %d/%d accumulators charged",
                    core.getTotalPowerFractionOfRated() * 100.0,
                    core.getPressurePsig(),
                    core.getChargedAccumulatorCount(),
                    core.getControlRodCount()));
            out.addAll(BoundaryDamageReadout.statusLines(core.getBoundaryStress()));
        }
        out.addAll(lastValidation.messages());
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence — freeze and resume, SPEC section 11
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("VesselState", vesselState.getSerializedName());

        net.minecraft.nbt.ListTag pumps = new net.minecraft.nbt.ListTag();
        for (BlockPos p : pumpPositions) {
            pumps.add(net.minecraft.nbt.LongTag.valueOf(p.asLong()));
        }
        tag.put("Pumps", pumps);

        tag.put("CoreFuel", core != null ? writeCoreFuel() : savedFuelOrEmpty());

        if (core != null) {
            tag.put("Core", ReactorStateNbt.write(core.toState()));
            // Overpressure damage is not part of ReactorState — it is a
            // property of the plant's metal rather than of its operating point
            // — so it is saved alongside rather than inside it.
            tag.put(BoundaryDamageNbt.KEY, BoundaryDamageNbt.write(core.getBoundaryStress()));
        } else {
            // Never formed since load: the structure is still broken, so the
            // snapshot is sitting in the pending fields with nothing to apply it
            // to. Write it back out. Dropping it — which is what happened
            // before, because only the boundary record had this fallback — meant
            // that a chunk which reloaded while the vessel had a hole in it lost
            // the entire reactor state on the next autosave, and repairing the
            // shell an hour later built a fresh hot-shutdown core over the top
            // of an accident that had been in progress.
            if (pendingRestore != null) {
                tag.put("Core", ReactorStateNbt.write(pendingRestore));
            }
            if (pendingBoundaryRestore != null) {
                tag.put(BoundaryDamageNbt.KEY, pendingBoundaryRestore);
            }
        }

        // The operator's standing rod demand. ReactorState has no component for
        // it, so it is persisted here rather than lost; see
        // ControlRodDriveNetwork.restoreCommandedNotchIndices.
        int[] demand = rodNetwork != null ? rodNetwork.getCommandedNotchIndices() : pendingRodDemand;
        if (demand != null && demand.length > 0) {
            tag.putIntArray("RodDemand", demand);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        vesselState = VesselState.byName(tag.getString("VesselState"));

        pumpPositions.clear();
        net.minecraft.nbt.ListTag pumps = tag.getList("Pumps", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < pumps.size(); i++) {
            pumpPositions.add(BlockPos.of(((net.minecraft.nbt.LongTag) pumps.get(i)).getAsLong()));
        }

        if (tag.contains("Core")) {
            ReactorState restored = ReactorStateNbt.read(tag.getCompound("Core"));
            // The structure has not been validated yet at load time, so the core
            // does not exist. Hold the snapshot until the first validation builds
            // a core with a matching rod count.
            pendingRestore = restored;
        }
        if (tag.contains(BoundaryDamageNbt.KEY)) {
            pendingBoundaryRestore = tag.getCompound(BoundaryDamageNbt.KEY);
        }
        pendingFuelRestore = tag.contains("CoreFuel")
                ? tag.getList("CoreFuel", net.minecraft.nbt.Tag.TAG_COMPOUND)
                : null;
        pendingRodDemand = tag.contains("RodDemand") ? tag.getIntArray("RodDemand") : null;
        structureDirty = true;
    }

    /** The lattice as it stands, one entry per occupied position. */
    private net.minecraft.nbt.ListTag writeCoreFuel() {
        net.minecraft.nbt.ListTag fuel = new net.minecraft.nbt.ListTag();
        var loading = core.getCoreLoading();
        for (int i = 0; i < loading.positionCount(); i++) {
            dev.bwr.core.fuel.FuelAssembly assembly = loading.assemblyAt(i);
            if (assembly == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", i);
            dev.bwr.mod.fuel.FuelAssemblyData.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                            dev.bwr.mod.fuel.FuelAssemblyData.of(assembly))
                    .result()
                    .ifPresent(encoded -> entry.put("Fuel", encoded));
            fuel.add(entry);
        }
        return fuel;
    }

    /** Never drop a saved core because the structure has not formed yet. */
    private net.minecraft.nbt.ListTag savedFuelOrEmpty() {
        return pendingFuelRestore == null ? new net.minecraft.nbt.ListTag() : pendingFuelRestore;
    }

    private void tryRestore(ReactorState state) {
        try {
            core.fromState(state);
        } catch (IllegalArgumentException e) {
            // Rod count changed while unloaded — the player rebuilt the vessel.
            // Keep the fresh core rather than refusing to load the chunk.
            lastValidation.degrade("saved reactor state did not match the rebuilt vessel: "
                    + e.getMessage() + "; the core was reinitialised");
        }
    }

    // -----------------------------------------------------------------
    // Client sync — 4 Hz, server-authoritative
    // -----------------------------------------------------------------

    private void syncToClients() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("VesselState", vesselState.getSerializedName());
        tag.putBoolean("Formed", isFormed());
        if (core != null) {
            // The client renders and displays; it never recomputes physics.
            tag.putDouble("Power", core.getTotalPowerFractionOfRated());
            tag.putDouble("Pressure", core.getPressurePsig());
            tag.putDouble("Level", core.getIndicatedLevelIn());
            tag.putInt("ChargedAccumulators", core.getChargedAccumulatorCount());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        vesselState = VesselState.byName(tag.getString("VesselState"));
        // Everything getUpdateTag sends is stored, not just the vessel state.
        // Five of the six keys used to be read and thrown away here, so the
        // 4 Hz broadcast was bandwidth with no consumer, and a client-side
        // renderer or tooltip written against getUpdateTag — the natural place
        // to look — would have found default values on arrival.
        clientFormed = tag.getBoolean("Formed");
        clientPowerFractionOfRated = tag.getDouble("Power");
        clientPressurePsig = tag.getDouble("Pressure");
        clientLevelIn = tag.getDouble("Level");
        clientChargedAccumulators = tag.getInt("ChargedAccumulators");
    }

    // -----------------------------------------------------------------
    // Client mirrors of the synced readout
    //
    // Written only by handleUpdateTag, so they are meaningful on the client and
    // stale on the server, where the core itself is the authority. For the
    // BlockEntityRenderer and any tooltip; nothing on the server should read
    // them.
    // -----------------------------------------------------------------

    private boolean clientFormed;
    private double clientPowerFractionOfRated;
    private double clientPressurePsig;
    private double clientLevelIn;
    private int clientChargedAccumulators;

    /** @see #handleUpdateTag */
    public boolean clientFormed() {
        return clientFormed;
    }

    /** Total power as a fraction of rated, as last synced. @see #handleUpdateTag */
    public double clientPowerFractionOfRated() {
        return clientPowerFractionOfRated;
    }

    /** Dome pressure, psig, as last synced. @see #handleUpdateTag */
    public double clientPressurePsig() {
        return clientPressurePsig;
    }

    /** Indicated vessel level, inches, as last synced. @see #handleUpdateTag */
    public double clientLevelIn() {
        return clientLevelIn;
    }

    /** Charged accumulators, as last synced. @see #handleUpdateTag */
    public int clientChargedAccumulators() {
        return clientChargedAccumulators;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
