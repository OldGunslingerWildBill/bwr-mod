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
     * pressure. See {@link #applyVesselSteamDischarge}.
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
    private VesselAppearance.Envelope vesselEnvelope;
    private VesselAppearance.Envelope clientVesselEnvelope;
    private int coreLayoutVersion = dev.bwr.core.fuel.CompactCoreLayout.VERSION;
    private int savedInteriorWidth, savedInteriorDepth;
    public int coreLayoutVersion() { return coreLayoutVersion; }
    private ValidationResult lastValidation = new ValidationResult();
    private boolean structureDirty = true;
    private int sinceRevalidate;
    private VesselState vesselState = VesselState.SHUTDOWN;

    /** Satellite recirculation pumps that have announced themselves. */
    private final Set<BlockPos> pumpPositions = new LinkedHashSet<>();
    private volatile double recirculationCapacityFraction;
    private volatile int connectedJetPairs,installedInternalPumps,unmatchedJets,connectedExternalPumps;
    public double getRecirculationCapacityFraction() { return recirculationCapacityFraction; }
    public int getConnectedJetPairs() { return connectedJetPairs; }
    public int getInstalledInternalPumps() { return installedInternalPumps; }
    public int getUnmatchedJets() { return unmatchedJets; }
    public int getConnectedExternalPumps() { return connectedExternalPumps; }
    public double getConfiguredRatedThermalMW() { return config.ratedThermalMW; }

    private int sinceSync;
    private ReactorState pendingRestore;
    // Flow fractions in Core are relative to this saved reference. Keep it even
    // while an unformed vessel holds a pending snapshot, then rebase on formation.
    private double pendingRatedCoreFlowKgPerS = dev.bwr.core.thermal.VoidModel.RATED_CORE_FLOW_KG_PER_S;

    /**
     * The rod demand pattern as it was saved, held until a network exists to put
     * it in. {@link ReactorState} has no component for rod demand, so without
     * this a withdrawal in flight when the chunk unloaded comes back collapsed
     * onto the notch the rods had reached.
     */
    private int[] pendingRodDemand;

    /**
     * The vessel geometry the rod-to-lattice map in the solver was built for,
     * or {@link Long#MIN_VALUE} before one has been supplied.
     * See {@link #installRodLatticeMap}.
     */
    private long rodLatticeMapSignature = Long.MIN_VALUE;

    /**
     * Steam this controller last discharged out of the vessel by the paths it
     * owns — an open head plus every RPV steam nozzle in the shell, kg/s.
     * See {@link #applyVesselSteamDischarge}.
     */
    private double lastVesselDischargeKgPerS;

    /**
     * Of that, the part passing through the RPV steam nozzles, kg/s. Kept
     * separately only so the status text can quote it; nothing reads it to make
     * a decision.
     */
    private double lastSteamOutletFlowKgPerS;

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
    private Object fuelDefinitionRevision;

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

        refreshFuelDefinitions();
        gatherPumpFlow(level);
        applyVesselSteamDischarge(level);

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

    /** CC demand commands the connected motors; it cannot manufacture delivered flow. */
    public void setRecirculationDemand(double fraction) {
        if(level==null || core==null || structure==null || !Double.isFinite(fraction)) return;
        var measured=dev.bwr.mod.flow.RecirculationNetwork.measure(level,this,pumpPositions);
        double speed=dev.bwr.core.flow.RecirculationSizing.speedForDemand(fraction,
                dev.bwr.mod.flow.RecirculationNetwork.sizing(structure).requiredJets(),
                measured.jetUnits(),measured.externalPumps(),measured.internalPumps());
        for(BlockPos p:pumpPositions) if(level.isLoaded(p)
                && level.getBlockEntity(p) instanceof RecirculationPumpBlockEntity pump
                && (dev.bwr.mod.flow.RecirculationNetwork.installedRip(level,structure,p)
                    || dev.bwr.mod.flow.RecirculationNetwork.connectedController(level,p)==this)) {
            pump.setComputerControlled(true);
            pump.setTargetSpeedFraction(speed);
        }
    }

    private void gatherPumpFlow(Level level) {
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
        }
        // Each connected circuit shares its external drive rating between its
        // installed paired jets. Internal pumps add their own direct capacity.
        var hardware = dev.bwr.mod.flow.RecirculationNetwork.measure(level,this,pumpPositions);
        double fraction = hardware.fraction();
        recirculationCapacityFraction=hardware.maximum();
        connectedJetPairs=hardware.pairedJets();
        installedInternalPumps=hardware.internalPumps();
        unmatchedJets=hardware.unmatchedJets();
        connectedExternalPumps=hardware.externalPumps();
        core.getBoundaryStress().setPlantConfiguration(BoundaryDamageNbt.configurationFor(installedInternalPumps));
        // Delivery always comes from powered physical hardware; commands target motor speed.
        core.setRecirculationFlowFraction(fraction);
    }

    /**
     * Every path steam leaves this vessel by that the controller itself owns:
     * an open head, and the RPV steam nozzles welded into the shell.
     *
     * <h2>Why one method and one write</h2>
     * The core holds a single scalar for discharge out of the steam space, so
     * there can be exactly one writer of it — the same rule that makes
     * {@code ControlRodDriveNetwork} the only caller of {@code setRodNotchDemand}
     * and {@code ReactorEccsBus} the only caller of
     * {@code setReliefSteamFlowKgPerS}. Two per-tick writers on one scalar do
     * not average, they alternate: whichever ticked last that tick wins, and the
     * other's steam disappears from the pressure balance for 50 ms at a time.
     * So the head and the nozzles are summed here and written once.
     *
     * <h2>Why the nozzles are not on the turbine channel</h2>
     * Physically the main steam nozzles are where turbine steam leaves, and
     * {@code setTurbineSteamFlowKgPerS} would be the natural home for them. It
     * is taken: {@code TurbineSteamOutletBlockEntity} writes the pooled total of
     * every outlet on this reactor into it on every one of its own ticks, and a
     * nozzle contribution written there would be erased by the next outlet tick
     * or would erase it. The vessel model does not distinguish the four steam
     * sinks anyway — {@code PressureVessel.step} adds turbine, bypass, relief and
     * leak into one {@code steamOut} term and its own javadoc says it "does not
     * care which is which" — so routing the nozzles through the channel this
     * class already owns costs nothing physically and keeps the single-writer
     * rule intact.
     *
     * <p>The two used to be genuinely parallel: a plant with a nozzle <i>and</i>
     * a turbine outlet lost steam twice, once down each channel, with no pipe
     * required between them and no relationship at all. They are in series now,
     * and the series runs the other way round — an outlet that can follow a steam
     * line back to a nozzle claims a share of what that nozzle is already passing
     * through {@code RpvSteamOutletBlockEntity.claimFlowKgPerS} and contributes
     * <b>zero</b> to the turbine channel, because the steam it hands to Mekanism
     * came out of the vessel here. {@code setTurbineSteamFlowKgPerS} is left for
     * the outlets that have no nozzle upstream of them, which is every outlet
     * built before nozzles existed. See {@code TurbineSteamOutletBlockEntity} for
     * why those keep working exactly as they did.
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
     * exactly one tick. The write is skipped entirely while nothing at all is
     * being discharged, so a value written from elsewhere is at least not
     * stamped on every tick of normal operation.
     */
    private void applyVesselSteamDischarge(Level level) {
        double head = vesselState.canHoldPressure()
                ? 0.0
                // Flow stops when the vessel reaches containment pressure, which
                // is where an open vessel sits. Keying on gauge pressure is what
                // keeps this from dragging the dome down to the pressure model's
                // numerical floor, some 14 psi below atmospheric.
                : OPEN_HEAD_DISCHARGE_KG_PER_S_PER_PSI * Math.max(0.0, core.getPressurePsig());
        double discharge = head + gatherSteamOutletFlow(level);
        if (discharge == 0.0 && lastVesselDischargeKgPerS == 0.0) {
            return;
        }
        core.setSteamLeakKgPerS(discharge);
        lastVesselDischargeKgPerS = discharge;
    }

    /**
     * Ask every RPV steam nozzle in the shell what it is passing, and total it.
     *
     * <p>The nozzles are pure hardware: each one answers with its stop position
     * times its choked-flow capacity at the pressure it is being handed, and
     * nothing here or there looks at whether that flow is a good idea. Wide open
     * on a cold vessel and the plant depressurises; shut at power and pressure
     * climbs. This method only adds up.
     *
     * <p>{@code isLoaded} rather than a null check on the block entity, for the
     * reason {@link #gatherPumpFlow} spells out: a vessel 21 blocks across
     * straddles chunk borders, and {@code Level.getBlockEntity} answers null for
     * an unloaded chunk exactly as it does for a broken block. Treating the two
     * the same would make a nozzle in a neighbouring chunk read as shut, which
     * on a plant running at power is a step change in steam removal caused by
     * nothing the player did.
     *
     * <p>Each nozzle is also told which controller polled it. That is what lets
     * a {@code TurbineSteamOutletBlockEntity} on the far end of a steam line find
     * its reactor <b>by following the pipe</b> rather than by looking for any
     * controller within twelve blocks of itself, which is how the two ends of one
     * steam path became two independent draws on one vessel in the first place.
     * The controller is the right place to say it because the controller is the
     * only thing that knows a nozzle is genuinely in its shell, and it is already
     * standing here holding the list.
     */
    private double gatherSteamOutletFlow(Level level) {
        if (structure == null || structure.steamOutletCount() == 0) {
            lastSteamOutletFlowKgPerS = 0.0;
            return 0.0;
        }
        double domePressurePsig = core.getPressurePsig();
        double total = 0.0;
        for (BlockPos p : structure.steamOutletPositions()) {
            if (!level.isLoaded(p)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof RpvSteamOutletBlockEntity nozzle) {
                nozzle.refreshAttachmentPeriodically(level);
                nozzle.noteController(getBlockPos());
                total += nozzle.flowKgPerS(domePressurePsig);
            }
        }
        lastSteamOutletFlowKgPerS = total;
        return total;
    }

    /** Steam leaving through the RPV steam nozzles on the last tick, kg/s. */
    public double steamOutletFlowKgPerS() {
        return lastSteamOutletFlowKgPerS;
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
     * inventory went with it, and the shutdown initialisation reset the
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
        var previous = vesselEnvelope;
        vesselEnvelope = null;
        revalidateStructure(level);
        // Unformed reactors return before the normal periodic sync. Send the
        // changed boundary now so clients never retain a closed-looking vessel.
        if (!java.util.Objects.equals(previous, vesselEnvelope)) syncToClients();
    }

    private void revalidateStructure(Level level) {
        FormedReactorRegistry.remove(this);
        dev.bwr.mod.flow.RecirculationNetwork.invalidateSurvey(level,getBlockPos());
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

        int width = found.interiorMax().getX()-found.interiorMin().getX()+1;
        int depth = found.interiorMax().getZ()-found.interiorMin().getZ()+1;
        // Compact rod IDs belong to this footprint. Never reinterpret a saved
        // transient or an in-flight drive demand as a different physical blade.
        if (coreLayoutVersion != 1 && savedInteriorWidth > 0
                && (width != savedInteriorWidth || depth != savedInteriorDepth)) {
            result.fail(getBlockPos(), "This controller retains a " + (savedInteriorWidth+2) + "x"
                    + (savedInteriorDepth+2) + " core. Restore that footprint; to resize, shut down, cool and defuel, then replace the controller.");
            structure = null;
            return;
        }
        // Preserve fuel and reject a shrink before changing any live core or port owner.
        var allowed=new java.util.HashSet<Integer>();
        for(int slot:found.fuelPositions()) allowed.add(slot);
        var saved=core!=null ? writeCoreFuel() : savedFuelOrEmpty();
        for(int i=0;i<saved.size();i++) if(!allowed.contains(saved.getCompound(i).getInt("Slot"))) {
            result.fail(getBlockPos(),"Cannot shrink vessel while fuel occupies outer positions. Restore the previous vessel and unload those bundles, or recover the fuel by removing the controller.");
            structure=null;
            return;
        }
        structure = found;
        savedInteriorWidth = width;
        savedInteriorDepth = depth;
        setChanged();
        for (BlockPos portPos : found.waterInjectionPositions())
            if (level.isLoaded(portPos) && level.getBlockEntity(portPos) instanceof RpvWaterInjectionPortBlockEntity port)
                port.noteController(getBlockPos());
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
                pendingRatedCoreFlowKgPerS = core.getVoidModel().getRatedCoreFlowKgPerS();
                pendingFuelRestore = writeCoreFuel();
                pendingBoundaryRestore = BoundaryDamageNbt.write(core.getBoundaryStress());
                pendingRodDemand = rodNetwork == null
                        ? null : rodNetwork.getCommandedNotchIndices();
            }
            core = new ReactorCore(config, new dev.bwr.core.fuel.CoreLoading(found.latticeWidth()));
            // Install before restoring: fromState re-solves the saved rod pattern.
            installRodLatticeMap(found);
            if (pendingRestore != null) core.getVoidModel().setRatedCoreFlowKgPerS(pendingRatedCoreFlowKgPerS);
            // A calibrated startup source, because a plant is built with one.
            // Before any restore: fromState carries the source strength the
            // reactor was saved with, and a saved reactor's source is its own.
            core.setNeutronSourceStrengthPerSecond(INSTALLED_NEUTRON_SOURCE_PER_SECOND);
            // Before the shutdown initialisation, because the fuel decides
            // what the reactivity balance it settles is.
            applyCoreFuel();
            // COLD, not hot. Welding a vessel together must not hand the player
            // 190 GJ of stored heat that nothing in the model produced: the
            // first playtest formed an empty vessel and the panel read
            // 1,025 psig and 287 C with no fuel in the lattice and no candidate
            // source for any of it. Hot standby is a state the operator
            // achieves, not an initial condition. initialiseHotShutdown() still
            // exists and is still correct — the test harness and the acceptance
            // suite legitimately want a hot core to start from — but a plant
            // that has never run starts cold and the player heats it up.
            core.initialiseCold();
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
            // After the core is built and restored, because initialiseCold()
            // deliberately resets the damage model to a pristine plant and this
            // puts the plant's real history back.
            BoundaryDamageNbt.seedFrom(core.getBoundaryStress(), getBlockPos());
            if (pendingBoundaryRestore != null) {
                BoundaryDamageNbt.read(pendingBoundaryRestore, core.getBoundaryStress());
                pendingBoundaryRestore = null;
            }
            // After the restore, not before: the snapshot carries the
            // configuration the plant had when it was saved, and the structure
            // in front of us now is the authority on the one it has today.
            //
            // Initial geometry is refreshed from the actual mounted RIPs by
            // gatherPumpFlow, including after a saved core is restored.
            core.getBoundaryStress().setPlantConfiguration(
                    BoundaryDamageNbt.configurationFor(0));
        }

        double ratedFlow=dev.bwr.mod.flow.RecirculationNetwork.sizing(found).ratedFlowKgPerS();
        if (ratedFlow != core.getVoidModel().getRatedCoreFlowKgPerS()) {
            core.setRatedCoreFlowKgPerS(ratedFlow);
        }

        // This is what makes rod i in the physics the same rod as drive i in
        // the world. Called on every validation rather than only on a rebuild,
        // because a core can arrive without a map for reasons the rebuild flag
        // does not cover; the method itself decides whether anything moved.
        installRodLatticeMap(found);
        bindDrives(level);
        FormedReactorRegistry.add(this);
        var min=found.interiorMin().offset(-1,-1,-1);
        var max=found.interiorMax().offset(1,1,1);
        var visualPorts=new java.util.ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(min,max)) {
            if(p.getX()!=min.getX() && p.getX()!=max.getX()
                    && p.getY()!=min.getY() && p.getY()!=max.getY()
                    && p.getZ()!=min.getZ() && p.getZ()!=max.getZ())continue;
            if(level.isLoaded(p)) {
                var s=level.getBlockState(p);
                if(!s.isAir() && !s.is(dev.bwr.mod.registry.BwrBlocks.REACTOR_VESSEL.get()))visualPorts.add(p.immutable());
            }
        }
        var visualSpargers=new java.util.ArrayList<VesselAppearance.Sparger>();
        for(var loop:CoreSpraySpargerBlock.Loop.values()) {
            int y=CoreSpraySpargerBlock.requiredY(loop,found.topOfActiveFuelY());
            for(int x=min.getX()+1;x<max.getX();x++)for(int z=min.getZ()+1;z<max.getZ();z++) {
                if(x!=min.getX()+1 && x!=max.getX()-1 && z!=min.getZ()+1 && z!=max.getZ()-1)continue;
                var p=new BlockPos(x,y,z);if(!level.isLoaded(p))continue;
                var s=level.getBlockState(p);
                if(s.is(dev.bwr.mod.registry.BwrBlocks.CORE_SPRAY_SPARGER.get()) && s.getValue(CoreSpraySpargerBlock.LOOP)==loop)
                    visualSpargers.add(new VesselAppearance.Sparger(p,loop));
            }
        }
        vesselEnvelope=new VesselAppearance.Envelope(min,max,visualPorts,visualSpargers);
    }

    @Override public void onLoad() {
        super.onLoad();
        structureDirty = true;
        FormedReactorRegistry.add(this);
    }

    @Override public void setRemoved() {
        VesselAppearance.update(level,getBlockPos(),null);
        FormedReactorRegistry.remove(this);
        dev.bwr.mod.flow.RecirculationNetwork.invalidateSurvey(level,getBlockPos());
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        VesselAppearance.update(level,getBlockPos(),null);
        FormedReactorRegistry.remove(this);
        dev.bwr.mod.flow.RecirculationNetwork.invalidateSurvey(level,getBlockPos());
        super.onChunkUnloaded();
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

    /** Fuel capacity of the saved layout, independent of physical floor block count. */
    public int assemblyCount() {
        return structure == null ? 0 : structure.assemblyCount();
    }

    /** Lattice square for each core slot, centre-outward. Empty until formed. */
    public int[] corePositions() {
        if (core == null || structure == null) {
            return new int[0];
        }
        return structure.fuelPositions();
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
        var insert = core.getCoreLoading().unloadInsert(latticePosition);
        if (insert != null) {
            setChanged();
            return dev.bwr.mod.fuel.SpecialtyRodItem.stack(dev.bwr.mod.fuel.CoreInsertData.of(insert));
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
                || !dev.bwr.mod.fuel.SpecialtyRodItem.isCoreItem(stack)) {
            return false;
        }
        if (structure == null || java.util.Arrays.stream(corePositions()).noneMatch(p -> p == latticePosition)
                || core.getCoreLoading().isOccupied(latticePosition)) {
            return false;
        }
        if (stack.is(dev.bwr.mod.registry.BwrItems.SPECIALTY_ROD.get()))
            core.getCoreLoading().loadInsert(latticePosition, dev.bwr.mod.fuel.SpecialtyRodItem.data(stack).toCore());
        else core.getCoreLoading().load(latticePosition, dev.bwr.mod.fuel.FuelAssemblies.load(stack));
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

    /** Consume the inventory once, before block-entity removal. Works before first formation too. */
    public List<ItemStack> takeFuelForRemoval() {
        List<ItemStack> result=new ArrayList<>();
        if(core!=null) {
            var loading=core.getCoreLoading();
            for(int i=0;i<loading.positionCount();i++) if(loading.isOccupied(i)) result.add(unloadAssembly(i));
        } else if(pendingFuelRestore!=null) {
            for(int i=0;i<pendingFuelRestore.size();i++) {
                var entry=pendingFuelRestore.getCompound(i);
                if (entry.contains("Insert")) {
                    dev.bwr.mod.fuel.CoreInsertData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, entry.get("Insert"))
                            .result().ifPresent(data -> result.add(dev.bwr.mod.fuel.SpecialtyRodItem.stack(data)));
                    continue;
                }
                dev.bwr.mod.fuel.FuelAssemblyData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,entry.get("Fuel"))
                        .result().ifPresent(data -> result.add(dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(
                                dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get(),data)));
            }
        }
        pendingFuelRestore=null;
        setChanged();
        return result;
    }

    /** Refresh on the server thread, retaining each assembly's exposure and enrichment. */
    public void refreshFuelDefinitions() {
        Object revision=dev.bwr.mod.fuel.FuelTypes.revision();
        if(core==null || revision==fuelDefinitionRevision) return;
        var loading=core.getCoreLoading();
        for(int i=0;i<loading.positionCount();i++) if(loading.assemblyAt(i) != null) {
            var assembly=loading.assemblyAt(i);
            var type=dev.bwr.mod.fuel.FuelTypes.byNameOrFallback(assembly.fuelType().name());
            if(type!=assembly.fuelType()) loading.load(i,dev.bwr.core.fuel.FuelAssembly.restore(type,
                    assembly.enrichmentWeightFraction(),assembly.heavyMetalMassKg(),assembly.burnupMwdPerTonne(),assembly.gadoliniaRemainingFraction()));
        }
        core.refreshFuelDefinitions();
        fuelDefinitionRevision=revision;
        setChanged();
    }

    /** Restore player-owned bundles into the newly created empty loading. Never grants fuel. */
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
            if (entry.contains("Insert")) {
                dev.bwr.mod.fuel.CoreInsertData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, entry.get("Insert"))
                        .resultOrPartial(message -> lastValidation.degrade("Invalid saved insert: " + message))
                        .ifPresent(data -> { if (!loading.isOccupied(slot)) loading.loadInsert(slot, data.toCore()); });
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
        out.add(String.format("Matched jet assemblies: %d; unmatched: %d; external/internal pumps: %d/%d; maximum forced flow: %.0f%%",
                connectedJetPairs,unmatchedJets,connectedExternalPumps,installedInternalPumps,100*recirculationCapacityFraction));
        if (structure == null) {
            out.add("Reactor not formed.");
            out.addAll(lastValidation.messages());
            if (out.size() == 1) {
                out.add("No vessel found around this controller.");
            }
            return out;
        }
        out.add("Core layout: " + (coreLayoutVersion == 1 ? "legacy" : "compact") + "; one drive block per blade.");
        out.add("Reactor formed: " + structure.assemblyCount() + " assemblies, "
                + structure.controlRodCount() + " control rods, vessel " + vesselState.getSerializedName());
        if (core != null) {
            out.add(String.format("%.2f%% rated, %.0f psig, %d/%d accumulators charged",
                    core.getTotalPowerFractionOfRated() * 100.0,
                    core.getPressurePsig(),
                    core.getChargedAccumulatorCount(),
                    core.getControlRodCount()));
            // What the vessel's own steam penetrations are doing. A measurement,
            // and the one a player standing at the reactor wants while they are
            // working out why pressure is going the way it is.
            out.add(String.format("%d RPV steam outlet(s) fitted, passing %.1f kg/s;"
                            + " core boiling %.1f kg/s",
                    structure.steamOutletCount(), lastSteamOutletFlowKgPerS,
                    core.getSteamGenerationKgPerS()));
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
        tag.putInt("CoreLayoutVersion", coreLayoutVersion);
        tag.putInt("CoreInteriorWidth", savedInteriorWidth);
        tag.putInt("CoreInteriorDepth", savedInteriorDepth);

        net.minecraft.nbt.ListTag pumps = new net.minecraft.nbt.ListTag();
        for (BlockPos p : pumpPositions) {
            pumps.add(net.minecraft.nbt.LongTag.valueOf(p.asLong()));
        }
        tag.put("Pumps", pumps);

        tag.put("CoreFuel", core != null ? writeCoreFuel() : savedFuelOrEmpty());
        tag.putDouble("RatedCoreFlowKgPerS", core != null
                ? core.getVoidModel().getRatedCoreFlowKgPerS() : pendingRatedCoreFlowKgPerS);

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
        // Missing key is an old save, including a temporarily broken vessel.
        coreLayoutVersion = tag.contains("CoreLayoutVersion") ? tag.getInt("CoreLayoutVersion") : 1;
        savedInteriorWidth = tag.getInt("CoreInteriorWidth");
        savedInteriorDepth = tag.getInt("CoreInteriorDepth");

        pumpPositions.clear();
        net.minecraft.nbt.ListTag pumps = tag.getList("Pumps", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < pumps.size(); i++) {
            pumpPositions.add(BlockPos.of(((net.minecraft.nbt.LongTag) pumps.get(i)).getAsLong()));
        }

        if (tag.contains("Core")) {
            double savedRating=tag.getDouble("RatedCoreFlowKgPerS");
            pendingRatedCoreFlowKgPerS=Double.isFinite(savedRating) && savedRating>0
                    ? savedRating : dev.bwr.core.thermal.VoidModel.RATED_CORE_FLOW_KG_PER_S;
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
            var insert = loading.insertAt(i);
            if (insert != null) {
                CompoundTag entry = new CompoundTag(); entry.putInt("Slot", i);
                dev.bwr.mod.fuel.CoreInsertData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                        dev.bwr.mod.fuel.CoreInsertData.of(insert)).result().ifPresent(data -> entry.put("Insert", data));
                fuel.add(entry); continue;
            }
            if (assembly == null) { continue; }
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
        if (vesselEnvelope != null) vesselEnvelope.write(tag);
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
        vesselState = VesselState.byName(tag.getString("VesselState"));
        // Everything getUpdateTag sends is stored, not just the vessel state.
        // Five of the six keys used to be read and thrown away here, so the
        // 4 Hz broadcast was bandwidth with no consumer, and a client-side
        // renderer or tooltip written against getUpdateTag — the natural place
        // to look — would have found default values on arrival.
        clientFormed = tag.getBoolean("Formed");
        clientVesselEnvelope = VesselAppearance.read(tag);
        VesselAppearance.update(level,getBlockPos(),clientVesselEnvelope);
        clientPowerFractionOfRated = tag.getDouble("Power");
        clientPressurePsig = tag.getDouble("Pressure");
        clientLevelIn = tag.getDouble("Level");
        clientChargedAccumulators = tag.getInt("ChargedAccumulators");
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        // NeoForge's default packet handler loads persistent NBT, not the
        // client-mirror handler. Initial chunk tags and live updates need the
        // same path, particularly when an already rendered vessel becomes unformed.
        handleUpdateTag(packet.getTag(), registries);
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

    public VesselAppearance.Envelope clientVesselEnvelope() { return clientVesselEnvelope; }

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
