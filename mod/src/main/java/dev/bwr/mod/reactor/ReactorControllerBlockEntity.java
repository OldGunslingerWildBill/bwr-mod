package dev.bwr.mod.reactor;

import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.ReactorState;
import dev.bwr.mod.damage.BoundaryDamageNbt;
import dev.bwr.mod.damage.BoundaryDamageReadout;
import dev.bwr.mod.damage.SevereAccidentNbt;
import dev.bwr.mod.damage.SevereAccidentReadout;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import dev.bwr.mod.rods.ControlRodDriveNetwork;
import dev.bwr.mod.rods.RodSelection;
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
    private final ReactorBoundaryEffects boundaryEffects = new ReactorBoundaryEffects();
    private final ReactorSevereAccidentEffects severeEffects = new ReactorSevereAccidentEffects();
    public ReactorSevereAccidentEffects severeEffects() { return severeEffects; }
    /** Nozzle and line breaks of the pressure boundary, for the screen and Lua. Server side. */
    public ReactorBoundaryEffects boundaryEffects() { return boundaryEffects; }
    /** The formed vessel's envelope on the server, or null while unformed. */
    public VesselAppearance.Envelope vesselEnvelope() { return vesselEnvelope; }
    /** The cavity seal survey of the formed reactor, or null while unformed (or before the first survey). */
    public CavitySeal.Result cavity() { return cavity; }
    /**
     * Height of the water in the well and its annulus above the bottom of the vessel, blocks: 0 while the vessel holds nothing
     * above the flange or there is no usable well. It is what the clients draw and what the bridge's chute meets.
     */
    /** World y of the water the player sees over this open vessel: the drawn surface in the vessel, or the flooded well's above it. */
    public double drawnWaterSurfaceY() {
        if (vesselEnvelope == null || core == null) return Double.NaN;
        double inVessel = VesselWaterGeometry.height(vesselEnvelope, core.getTwoPhaseLevelIn());
        return vesselEnvelope.min().getY() + Math.max(inVessel, wellSurfaceLocal());
    }

    public double wellSurfaceLocal() {
        if (cavity == null || cavity.profile() == null || core == null) return 0;
        var vessel = core.getPressureVessel();
        double water = vessel.getWellWaterM3();
        if (!(water > 0)) return 0;
        return cavity.profile().surface(water,
                (vessel.getCollapsedLevelIn() - dev.bwr.core.thermal.PressureVessel.FLANGE_LEVEL_IN) * dev.bwr.core.thermal.PressureVessel.METRES_PER_INCH);
    }
    private int clientBoundaryMask;
    private long[] clientBoundaryLeaks = new long[0];
    public boolean clientHeadFailed(){return (clientBoundaryMask & ReactorBoundaryEffects.HEAD)!=0;}
    public long[] clientBoundaryLeaks(){return clientBoundaryLeaks;}
    private long[] clientLineBreaks = new long[0];
    private byte[] clientLineBreakJets = new byte[0];
    /** Open pressure-boundary breaks along a line (not at a nozzle): their positions, for the plume. */
    public long[] clientLineBreaks(){return clientLineBreaks;}
    /** The outward direction of each of {@link #clientLineBreaks()}, as {@code Direction.get3DDataValue}. */
    public byte[] clientLineBreakJets(){return clientLineBreakJets;}

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
    /** The operator's selected rod and held continuous controls; see {@link RodSelection}. */
    private final RodSelection rodSelection = new RodSelection();
    /** A saved selection waiting for the structure to be validated (the rod count is not known before). */
    private int pendingSelectedRod;

    private ReactorStructure structure;
    private VesselAppearance.Envelope vesselEnvelope;
    /**
     * The cavity seal and well as last surveyed (server side; null while unformed). Derived from the blocks, so nothing of it is
     * saved; the water that stands above the flange is, as an inventory, only while there is some (see {@code WellInventoryKg}).
     */
    private CavitySeal.Result cavity;
    /** The survey being stepped, or null. */
    private CavitySeal.Survey cavitySurvey;
    /** Liquid inventory saved with water over the flange, applied once the well is surveyed so the level reads as it was. */
    private double pendingWellMassKg = -1;
    private VesselAppearance.Envelope clientVesselEnvelope;
    private VesselCoreAppearance clientCoreAppearance;
    private double[] clientBladeInsertion=new double[0];
    private double[] clientBladeFrom=new double[0];
    private long clientBladeSyncTick;
    private int coreLayoutVersion = dev.bwr.core.fuel.CompactCoreLayout.VERSION;
    private int savedInteriorWidth, savedInteriorDepth;
    private int[] savedDriveCells=new int[0];
    public int coreLayoutVersion() { return coreLayoutVersion; }
    private ValidationResult lastValidation = new ValidationResult();
    private boolean structureDirty = true;
    private int sinceRevalidate;
    private VesselState vesselState = VesselState.SHUTDOWN;
    /** The steam dryer and the separator assembly that are out of the vessel, and where each is (a storage pad, or the bridge
     *  console whose hoist holds it). Empty, and not saved, while both are installed, as in every older save. */
    private final java.util.EnumMap<VesselInternal, BlockPos> internalsOut = new java.util.EnumMap<>(VesselInternal.class);
    private int clientInternalsOut;
    /**
     * Closure studs whose nuts are off (0: every stud at load, the default and not saved), counted while the head is on. With the head
     * off every stud is bare. The reactor building crane lifts the head only when this equals the stud count; the vessel holds pressure
     * only when it is zero.
     */
    private int studsOff;
    /** Where the head is while it is off: the console of the crane whose hook holds it, or a head stand. Null with the head off means it is laid down somewhere (older worlds, or the instant button). Not saved while null. */
    private BlockPos headAt;
    private int clientStudsOff;

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
    private double deliveredRecirculationFraction;

    /**
     * Overpressure damage, held across a rebuild of the core object. Damage is
     * a property of the plant's metal rather than of whatever {@link ReactorCore}
     * instance happens to be modelling it, so it survives chunk unload and it
     * survives a vessel block being replaced.
     */
    private net.minecraft.nbt.CompoundTag pendingBoundaryRestore;
    /** Core damage and containment gas awaiting a formed core; see SevereAccidentNbt. */
    private net.minecraft.nbt.CompoundTag pendingSevereRestore;

    /**
     * The core loading as it was saved, held until a structure exists to put it
     * in. Fuel is not part of {@link ReactorState} — exposure lives on the
     * bundle, per SPEC section 2.3 — so the lattice is persisted alongside it.
     */
    private net.minecraft.nbt.ListTag pendingFuelRestore;
    private Object fuelDefinitionRevision;

    /**
     * The 1 Hz nodal flux solve iterates on the shared {@code bwr-flux-N} pool and its answer is applied
     * {@link ReactorCore#DEFERRED_NODAL_APPLY_TICKS} ticks after it starts, so it costs the server thread only the
     * assembly of the problem. {@code -Dbwr.fluxAsync=false} iterates on the server thread instead, applied on the
     * same tick, with bit-identical results (for comparing the two).
     */
    static final ReactorCore.NodalRefresh NODAL_REFRESH =
            Boolean.parseBoolean(System.getProperty("bwr.fluxAsync", "true"))
                    ? ReactorCore.NodalRefresh.DEFERRED_ASYNC : ReactorCore.NodalRefresh.DEFERRED_INLINE;

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
            long probe = dev.bwr.mod.TickProbe.start();
            revalidate(level);
            dev.bwr.mod.TickProbe.stop("  Reactor.revalidate", probe);
            structureDirty = false;
        }
        if (core == null || structure == null) {
            dev.bwr.mod.advancement.PlantMilestones.reactorUnavailable(this);
            return;
        }

        if (cavitySurvey != null) {
            long cavityProbe = dev.bwr.mod.TickProbe.start();
            if (cavitySurvey.step(CavitySeal.READS_PER_TICK)) applyCavitySurvey();
            dev.bwr.mod.TickProbe.stop("  Reactor.cavitySurvey", cavityProbe);
        }

        dev.bwr.mod.advancement.PlantMilestones.beforeReactorStep(this);
        dev.bwr.mod.config.GameplayConfig.applyTo(core);

        long probe = dev.bwr.mod.TickProbe.start();
        refreshFuelDefinitions();
        gatherPumpFlow(level);
        applyVesselSteamDischarge(level);
        // The head is off: water the well cannot hold goes over the flange (see PressureVessel.Well). A fact about the hardware.
        core.getPressureVessel().setWellSpilling(!headSealed());
        drainCavity(level);
        dev.bwr.mod.TickProbe.stop("  Reactor.pumps+nozzles", probe);

        // Order matters. The drive network moves rods and spends accumulator
        // charge before the physics steps, then writes hardware condition back
        // afterwards, so the core sees a consistent rod configuration for the
        // whole tick.
        probe = dev.bwr.mod.TickProbe.start();
        rodSelection.tick(rodNetwork, core);
        rodNetwork.preStep(config.tickSeconds);
        boundaryEffects.apply(this,vesselEnvelope);
        dev.bwr.mod.TickProbe.stop("  Reactor.rods+boundary", probe);
        var containmentController = dev.bwr.mod.containment.ContainmentControllerRegistry
                .linkedReactor(level, getBlockPos());
        if (containmentController == null) {
            var airlock = dev.bwr.mod.containment.ContainmentControllerRegistry.forcedAirlockFor(level, getBlockPos());
            core.getContainment().setLeakArea(airlock == null ? 1.0 : 0.35);
            core.getContainment().setFailurePressurePsig(
                    airlock == null ? dev.bwr.core.accident.ContainmentAtmosphere.FAILURE_PRESSURE_PSIG
                            : airlock.failurePressurePsig());
            core.setContainmentBypassFlowFraction(0.0);
        } else {
            // The boundary was open last tick and a sealed survey now links it:
            // the breach has been rebuilt. Melt-through stays failed.
            if (core.getContainment().getLeakArea() > 0.0) core.getContainment().clearStructuralFailure();
            core.getContainment().setLeakArea(0.0);
            core.getContainment().setFailurePressurePsig(containmentController.failurePressurePsig());
            if (core.getContainment().getVolumeM3() != containmentController.freeVolume())
                core.getContainment().setFreeVolumeM3(containmentController.freeVolume());
            core.setContainmentBypassFlowFraction(containmentController.bypassFlowFraction(this));
        }
        dev.bwr.mod.containment.ContainmentEquipmentRegistry.apply(this, containmentController);
        dev.bwr.mod.containment.ContainmentSteamCoupling.beforeReactorStep(this, containmentController);
        probe = dev.bwr.mod.TickProbe.start();
        core.step();
        dev.bwr.mod.TickProbe.stop("  Reactor.core.step", probe);
        dev.bwr.mod.TickProbe.add("    Reactor.flux start (assemble)", core.getLastStepNodalBeginNanos());
        dev.bwr.mod.TickProbe.add("    Reactor.flux wait for worker", core.getLastStepNodalWaitNanos());
        probe = dev.bwr.mod.TickProbe.start();
        rodNetwork.postStep();
        severeEffects.afterStep(this);
        dev.bwr.mod.advancement.PlantMilestones.afterReactorStep(this);
        refreshHeadAccess();
        dev.bwr.mod.TickProbe.stop("  Reactor.post", probe);

        if (++sinceSync >= SYNC_INTERVAL_TICKS) {
            sinceSync = 0;
            probe = dev.bwr.mod.TickProbe.start();
            syncToClients();
            dev.bwr.mod.TickProbe.stop("  Reactor.syncToClients", probe);
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
        for(BlockPos p:pumpPositions) if(dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof RecirculationPumpBlockEntity pump
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
            if (!dev.bwr.mod.world.LoadedWorld.available(level, p)) {
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
            if (!(dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof RecirculationPumpBlockEntity pump)) {
                iterator.remove(); // pump was broken or replaced
                continue;
            }
            pump.tickPump(config.tickSeconds);
        }
        // Each connected circuit shares its external drive rating between its
        // installed paired jets. Internal pumps add their own direct capacity.
        var hardware = dev.bwr.mod.flow.RecirculationNetwork.measure(level,this,pumpPositions);
        double fraction = hardware.fraction();
        deliveredRecirculationFraction = fraction;
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
        double head = headSealed()
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
            if (dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof RpvSteamOutletBlockEntity nozzle) {
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
    public double deliveredRecirculationFraction() { return deliveredRecirculationFraction; }
    public int connectedExternalPumpCount() { return connectedExternalPumps; }

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
        boundaryEffects.invalidate();
        refreshHeadAccess();
        VesselJetAccess.update(level,getBlockPos(),vesselEnvelope);
        VesselDriveAccess.update(level,getBlockPos(),vesselEnvelope);
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
            cavity = null;
            cavitySurvey = null;
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
            result.fail(getBlockPos(), BwrText.tr(
                    "status.bwr.reactor_controller.this_controller_retains_a_x_core_restore_that",
                    "This controller retains a %sx%s core. Restore that footprint; to resize, shut down, cool and defuel, then replace the controller.",
                    savedInteriorWidth+2,
                    savedInteriorDepth+2));
            structure = null;
            return;
        }
        // Preserve fuel and reject a shrink before changing any live core or port owner.
        var allowed=new java.util.HashSet<Integer>();
        for(int slot:found.fuelPositions()) allowed.add(slot);
        var saved=core!=null ? writeCoreFuel() : savedFuelOrEmpty();
        for(int i=0;i<saved.size();i++) if(!allowed.contains(saved.getCompound(i).getInt("Slot"))) {
            result.fail(getBlockPos(), BwrText.tr(
                    "status.bwr.reactor_controller.fuel_occupies_positions_reserved_by_the_new_vessel",
                    "Fuel occupies positions reserved by the new vessel/RIP layout. Restore the previous layout and unload those bundles, or recover the fuel by removing the controller."));
            structure=null;
            return;
        }
        int[] nextDriveCells=found.driveCells();
        // Alpha.15 and earlier compact saves used the complete footprint's raster drive list.
        if(coreLayoutVersion!=1 && savedDriveCells.length==0 && savedInteriorWidth>0)
            savedDriveCells=new dev.bwr.core.fuel.CompactCoreLayout(savedInteriorWidth,savedInteriorDepth).drives()
                    .stream().mapToInt(d->d.x()*21+d.z()).toArray();
        boolean driveLayoutChanged=coreLayoutVersion!=1 && savedDriveCells.length>0
                && !java.util.Arrays.equals(savedDriveCells,nextDriveCells);
        if(driveLayoutChanged) {
            var snapshot=core==null?pendingRestore:core.toState();
            var demand=rodNetwork==null?pendingRodDemand:rodNetwork.getCommandedNotchIndices();
            try {
                if(snapshot!=null)CoreDriveRemap.state(snapshot,savedDriveCells,nextDriveCells);
                CoreDriveRemap.demands(demand,savedDriveCells,nextDriveCells);
            }catch(IllegalArgumentException invalid){
                result.fail(getBlockPos(), BwrText.tr(
                        "status.bwr.reactor_controller.cannot_reconcile_saved_rip_drive_layout",
                        "Cannot reconcile saved RIP/drive layout: %s",
                        invalid.getMessage()));structure=null;return;
            }
        }
        structure = found;
        savedInteriorWidth = width;
        savedInteriorDepth = depth;
        setChanged();
        for (BlockPos portPos : found.waterInjectionPositions())
            if (dev.bwr.mod.world.LoadedWorld.blockEntity(level, portPos) instanceof RpvWaterInjectionPortBlockEntity port)
                port.noteController(getBlockPos());
        boolean rebuild = core == null || core.getControlRodCount() != found.controlRodCount() || driveLayoutChanged;

        // Kept truthful whether or not a rebuild follows. Both are copied into
        // the core at construction and never read again, so writing them here
        // only ever affects the next core built — which is the point.
        config.controlRodCount = found.controlRodCount();
        config.assemblyCount = found.assemblyCount();

        if (rebuild) {
            if (core != null) {
                dev.bwr.mod.advancement.PlantMilestones.reactorUnavailable(this);
                // Snapshot before discarding. The pending fields are consumed
                // and nulled the first time they are used, so on any rebuild
                // after the first there is nothing on disk left to restore from
                // and the world in front of us is the only authority.
                pendingRestore = core.toState();
                pendingWellMassKg = core.getPressureVessel().getWellWaterM3() > 0 ? core.getPressureVessel().getLiquidMassKg() : -1;
                pendingRatedCoreFlowKgPerS = core.getVoidModel().getRatedCoreFlowKgPerS();
                pendingFuelRestore = writeCoreFuel();
                pendingBoundaryRestore = BoundaryDamageNbt.write(core.getBoundaryStress());
                pendingSevereRestore = SevereAccidentNbt.write(core);
                pendingRodDemand = rodNetwork == null
                        ? null : rodNetwork.getCommandedNotchIndices();
            }
            if(driveLayoutChanged) {
                if(pendingRestore!=null)pendingRestore=CoreDriveRemap.state(pendingRestore,savedDriveCells,nextDriveCells);
                pendingRodDemand=CoreDriveRemap.demands(pendingRodDemand,savedDriveCells,nextDriveCells);
            }
            // The rod numbers mean other rods after a drive remap, so a selection does not survive one.
            int keptSelection = driveLayoutChanged ? 0 : pendingSelectedRod > 0 ? pendingSelectedRod : rodSelection.selected();
            pendingSelectedRod = 0;
            if (core != null) core.discardPendingNodalRefresh();
            core = new ReactorCore(config, new dev.bwr.core.fuel.CoreLoading(found.latticeWidth()));
            core.setNodalRefresh(NODAL_REFRESH);
            core.seedRodColletFailures(getBlockPos().asLong() ^ 0x43ad3672b16f0e95L);
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
            rodSelection.restore(keptSelection, found.controlRodCount());
            // After the core is built and restored, because initialiseCold()
            // deliberately resets the damage model to a pristine plant and this
            // puts the plant's real history back.
            BoundaryDamageNbt.seedFrom(core.getBoundaryStress(), getBlockPos());
            if (pendingBoundaryRestore != null) {
                BoundaryDamageNbt.read(pendingBoundaryRestore, core.getBoundaryStress());
                pendingBoundaryRestore = null;
            }
            // Same rule for core damage and containment gas: a rebuilt core
            // starts pristine, and the plant's real history goes back on top.
            if (pendingSevereRestore != null) {
                SevereAccidentNbt.read(pendingSevereRestore, core);
                pendingSevereRestore = null;
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

        savedDriveCells=nextDriveCells;
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
        var visualKinds=new java.util.HashMap<BlockPos,VesselAppearance.PortKind>();
        for(var p:BlockPos.betweenClosed(min,max)) {
            if(p.getX()!=min.getX() && p.getX()!=max.getX()
                    && p.getY()!=min.getY() && p.getY()!=max.getY()
                    && p.getZ()!=min.getZ() && p.getZ()!=max.getZ())continue;
            if(level.isLoaded(p)) {
                var s=level.getBlockState(p);
                var kind=s.getBlock() instanceof ReactorControllerBlock?VesselAppearance.PortKind.CONTROLLER
                        :s.getBlock() instanceof RpvWaterInjectionPortBlock?VesselAppearance.PortKind.WATER
                        :s.getBlock() instanceof RpvSteamOutletBlock?VesselAppearance.PortKind.STEAM:VesselAppearance.PortKind.LEGACY;
                // Do not disguise an incorrectly oriented water block as a working outward nozzle.
                if(kind==VesselAppearance.PortKind.WATER&&s.getValue(RpvWaterInjectionPortBlock.FACING)!=VesselAppearance.outwardFace(p,min,max))
                    kind=VesselAppearance.PortKind.LEGACY;
                if(kind!=VesselAppearance.PortKind.LEGACY)visualKinds.put(p.immutable(),kind);
                if(!s.isAir() && !s.is(dev.bwr.mod.registry.BwrBlocks.REACTOR_VESSEL.get())
                        && !s.is(dev.bwr.mod.registry.BwrBlocks.RIP_PUMP.get()))visualPorts.add(p.immutable());
            }
        }
        var visualSpargers=new java.util.ArrayList<VesselAppearance.Sparger>();
        for(var loop:CoreSpraySpargerBlock.Loop.values()) {
            for(int y:found.spargerHeights(loop)) {
            for(int x=min.getX()+1;x<max.getX();x++)for(int z=min.getZ()+1;z<max.getZ();z++) {
                if(x!=min.getX()+1 && x!=max.getX()-1 && z!=min.getZ()+1 && z!=max.getZ()-1)continue;
                var p=new BlockPos(x,y,z);if(!level.isLoaded(p))continue;
                var s=level.getBlockState(p);
                if(s.is(dev.bwr.mod.registry.BwrBlocks.CORE_SPRAY_SPARGER.get()) && s.getValue(CoreSpraySpargerBlock.LOOP)==loop)
                    visualSpargers.add(new VesselAppearance.Sparger(p,loop));
            }
            }
        }
        var visualJets=new java.util.ArrayList<VesselAppearance.Jet>();
        for(var p:dev.bwr.mod.flow.RecirculationNetwork.jets(level,this)) {
                var s=level.getBlockState(p);
                visualJets.add(new VesselAppearance.Jet(p.immutable(),s.getValue(dev.bwr.mod.eccs.PumpAssemblyBlock.FACING),s.getValue(dev.bwr.mod.flow.JetPumpBlock.NARROW)));
            }
        var driveCells=new java.util.ArrayList<BlockPos>();
        for(int x=min.getX()+1;x<max.getX();x++)for(int z=min.getZ()+1;z<max.getZ();z++) {
            var p=new BlockPos(x,min.getY()-1,z);
            if(level.isLoaded(p)&&level.getBlockState(p).is(dev.bwr.mod.registry.BwrBlocks.CONTROL_ROD_DRIVE.get()))driveCells.add(p);
        }
        vesselEnvelope=new VesselAppearance.Envelope(min,max,visualPorts,visualSpargers,visualKinds,visualJets,driveCells);
        surveyCavity();
    }

    /**
     * The cavity seal and the well above it. A survey is started whenever the structure revalidates (formation, a changed block next to
     * the controller, the five second sweep) and is then stepped {@link CavitySeal#READS_PER_TICK} block reads a tick (see {@link #tick}) until it
     * finishes, so no tick pays for the whole of a large vessel's well (up to about 190 000 reads); a newer revalidation replaces one still
     * running. Until it finishes the last answer stands (the drawn seal and the vessel's well are kept, so nothing flickers). A block in an
     * unloaded chunk also leaves the last answer standing. The result is given to the vessel as the space above its flange, which changes how
     * the water inventory reads as a level above the flange and nothing below it.
     */
    private void surveyCavity() {
        cavitySurvey = new CavitySeal.Survey(level, vesselEnvelope);
        // keep the picture the clients already have while the new survey runs
        if (cavity != null && cavity.visual() != null && cavity.bottomY() == vesselEnvelope.min().getY() && cavity.height() == vesselEnvelope.height()) {
            vesselEnvelope = vesselEnvelope.withCavity(cavity.visual());
        }
    }

    /** The cavity drain ports last found in this reactor's well area, re-read when a port came or went or the well was surveyed. */
    private java.util.List<BlockPos> drainPorts = java.util.List.of();
    private int drainGeneration = -1;

    /** The loaded cavity drain ports in this reactor's well area, for the INFO tab and the computers. Server side. */
    public java.util.List<CavityDrainPortBlockEntity> cavityDrains() {
        if (level == null || level.isClientSide()) return java.util.List.of();
        refreshDrainPorts();
        var out = new java.util.ArrayList<CavityDrainPortBlockEntity>(drainPorts.size());
        for (BlockPos p : drainPorts)
            if (dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof CavityDrainPortBlockEntity port && !port.isRemoved()) out.add(port);
        return out;
    }

    private void refreshDrainPorts() {
        if (drainGeneration == CavityDrains.generation()) return;
        drainGeneration = CavityDrains.generation();
        drainPorts = cavity == null || vesselEnvelope == null ? java.util.List.of()
                : CavityDrains.near(level, vesselEnvelope.min(), vesselEnvelope.max(), CavitySeal.WELL_EXTENT + 1);
    }

    /** Steps the drain ports in the well. No ports: one comparison and a return. */
    private void drainCavity(Level level) {
        refreshDrainPorts();
        if (drainPorts.isEmpty()) return;
        for (BlockPos p : drainPorts)
            if (dev.bwr.mod.world.LoadedWorld.blockEntity(level, p) instanceof CavityDrainPortBlockEntity port && !port.isRemoved())
                port.step(this, config.tickSeconds);
    }

    /** True when a survey has been made and none is running. */
    public boolean cavitySurveyDone() {
        return cavity != null && cavitySurvey == null;
    }

    /** Runs the rest of a pending cavity survey now, in one go. For tests and tools; the game steps it a few thousand reads a tick. */
    public void finishCavitySurvey() {
        if (cavitySurvey != null) {
            cavitySurvey.step(Integer.MAX_VALUE);
            applyCavitySurvey();
        }
    }

    private void applyCavitySurvey() {
        var survey = cavitySurvey.result();
        cavitySurvey = null;
        if (survey == null || vesselEnvelope == null) return;
        cavity = survey;
        drainGeneration = -1;
        var previous = vesselEnvelope;
        vesselEnvelope = vesselEnvelope.withCavity(survey.visual());
        if (!java.util.Objects.equals(previous, vesselEnvelope)) syncToClients();
        if (core == null) return;
        var vessel = core.getPressureVessel();
        vessel.setWell(survey.profile() != null ? survey.profile().toWell() : dev.bwr.core.thermal.PressureVessel.Well.none());
        if (pendingWellMassKg > 0) {
            vessel.restoreLiquidMassKg(pendingWellMassKg);
            pendingWellMassKg = -1;
        }
    }

    @Override public void onLoad() {
        super.onLoad();
        dev.bwr.mod.advancement.PlantMilestones.loaded(this);
        structureDirty = true;
        FormedReactorRegistry.add(this);
    }

    @Override public void setRemoved() {
        if (core != null) core.discardPendingNodalRefresh();
        dev.bwr.mod.advancement.PlantMilestones.unloaded(this);
        VesselJetAccess.update(level,getBlockPos(),null);
        VesselDriveAccess.update(level,getBlockPos(),null);
        VesselHeadAccess.update(level,getBlockPos(),null,false);
        VesselAppearance.update(level,getBlockPos(),null);
        FormedReactorRegistry.remove(this);
        dev.bwr.mod.flow.RecirculationNetwork.invalidateSurvey(level,getBlockPos());
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        if (core != null) core.discardPendingNodalRefresh();
        dev.bwr.mod.advancement.PlantMilestones.unloaded(this);
        VesselJetAccess.update(level,getBlockPos(),null);
        VesselDriveAccess.update(level,getBlockPos(),null);
        VesselHeadAccess.update(level,getBlockPos(),null,false);
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
        // A different RIP arrangement can retain the same rod count.
        if(!java.util.Arrays.equals(savedDriveCells,found.driveCells()))rodLatticeMapSignature=Long.MIN_VALUE;
        if (signature == rodLatticeMapSignature
                && core.getNodalFluxSolver().hasExplicitRodLatticeMap()) {
            return;
        }
        core.getNodalFluxSolver().setRodLatticeMap(found.rodLatticeMap(latticeWidth));
        var displayMap = CoreRodMap.fromStructure(found);
        int[] cols = new int[displayMap.rodCount()], rows = cols.clone();
        for (int i = 0; i < cols.length; i++) { cols[i] = displayMap.col(i); rows[i] = displayMap.row(i); }
        core.installLprmLayout(dev.bwr.core.instrument.LprmLayout.fromRodGrid(core.getCoreLoading(),
                core.getNodalFluxSolver().rodLatticeMap(), cols, rows));
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

    private ReactorStructure coreMapFor;
    private CoreRodMap coreMap;

    /**
     * Where each rod and LPRM string sits on the display grid, with the GE coordinates, or null when the reactor is
     * not formed. Rebuilt only when revalidation made a new structure object.
     */
    public CoreRodMap coreMap() {
        ReactorStructure current = structure;
        if (current == null || core == null) {
            return null;
        }
        if (coreMapFor != current) {
            try {
                coreMap = CoreRodMap.fromStructure(current);
            } catch (IllegalArgumentException e) {
                coreMap = null; // not displayable (wider than the grid); readouts say no signal
            }
            coreMapFor = current;
        }
        return coreMap;
    }

    /** The selected rod, 1-based; 0 when none is selected or the reactor is not formed. */
    public int selectedRod() {
        return isFormed() && rodNetwork != null ? rodSelection.selected() : 0;
    }

    /**
     * Selects a rod (1-based) or clears the selection (0). Moves nothing.
     *
     * @return false when the reactor is not formed or {@code rod} is not a rod of this core
     */
    public boolean selectRod(int rod) {
        if (!isFormed() || rodNetwork == null) {
            return false;
        }
        boolean ok = rodSelection.select(rod, core.getControlRodCount());
        if (ok) {
            setChanged();
        }
        return ok;
    }

    /** Steps the selected rod one notch ({@code +1} out, {@code -1} in) through the drive network. */
    public boolean stepSelectedRod(int direction) {
        return isFormed() && rodNetwork != null && rodSelection.step(rodNetwork, direction);
    }

    /** Holds or releases a continuous rod motion control ({@code +1} withdraw, {@code -1} insert). */
    public boolean holdSelectedRod(int direction, boolean held) {
        if (!isFormed() || rodNetwork == null) {
            return false;
        }
        return rodSelection.hold(direction, held, core.getControlRodCount());
    }

    /** Whether a continuous rod motion control is currently held ({@code +1} withdraw, {@code -1} insert). */
    public boolean selectedRodHeld(int direction) {
        return direction > 0 ? rodSelection.withdrawHeld() : rodSelection.insertHeld();
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
        Component message = setVesselStateMessage(next);
        return message == null ? null : BwrText.english(message);
    }

    public Component setVesselStateMessage(VesselState next) {
        if (next.canHoldPressure()) {
            Component why = headSeatRefusal();
            if (why != null) return why;
        }
        if (next == VesselState.REFUELING && core != null) {
            double psig = core.getPressurePsig();
            if (psig > 25.0) {
                return BwrText.tr("status.bwr.reactor_controller.cannot_remove_the_head_at",
                        "cannot remove the head at %s psig; the vessel must be depressurised first", Math.round(psig));
            }
        }
        vesselState = next;
        // The instant way (the panel button with instantHeadRemoval on, a program, a test): the head is on and tight, or off and laid down somewhere.
        headAt = null;
        studsOff = 0;
        refreshHeadAccess();
        setChanged();
        syncToClients();
        return null;
    }

    /** What stops the head from going back on, from the dryer or the separators being out or from a failed head; null if nothing does. */
    private Component headSeatRefusal() {
        if (!internalsOut.isEmpty())
            return BwrText.tr("status.bwr.reactor_controller.internals_out_head",
                    "The head cannot go back on: the %s is out of the vessel. Set the separator assembly and then the dryer back in with the reactor building crane first.",
                    (internalsOut.containsKey(VesselInternal.SEPARATOR) ? VesselInternal.SEPARATOR : VesselInternal.DRYER).title);
        if (core != null && core.getBoundaryStress().isBroken(dev.bwr.core.boundary.BoundaryComponent.REACTOR_VESSEL_HEAD))
            return BwrText.tr("status.bwr.reactor_controller.head_failed",
                    "The vessel head has failed; changing the refuelling state cannot repair pressure-boundary damage.");
        return null;
    }

    // -----------------------------------------------------------------
    // The closure studs and the head's place (BUILDING-CRANE.md)
    // -----------------------------------------------------------------

    /** Studs in the closure flange of this vessel (the drawn count, 24..92; 74 on the reference vessel); 0 before it is formed. */
    public int studTotal() {
        var e = vesselEnvelope != null ? vesselEnvelope : clientVesselEnvelope;
        return e == null ? 0 : VesselProfile.of(e, false).studCount();
    }

    /** Studs whose nuts are off: all of them with the head off, otherwise the count the tensioner has reached. */
    public int studsOff() {
        int total = studTotal();
        return vesselState == VesselState.REFUELING ? total : Math.min(studsOff, total);
    }

    /** Client side of {@link #studsOff()}, from the synced state. */
    public int clientStudsOff() {
        int total = studTotal();
        return vesselState == VesselState.REFUELING ? total : Math.min(clientStudsOff, total);
    }

    /** Studs at load. */
    public int studsTensioned() {
        return studTotal() - studsOff();
    }

    /** The head is on the flange with every stud at load: the only state in which the vessel holds pressure. */
    public boolean headSealed() {
        return vesselState.canHoldPressure() && studsOff <= 0;
    }

    /** Where the head is while it is off (a crane console or a head stand), or null (laid down, or the head is on). */
    public BlockPos headLocation() {
        return vesselState == VesselState.REFUELING ? headAt : null;
    }

    /** Why the studs cannot be backed off now, or null. */
    public Component studLoosenBlocked() {
        if (!isFormed()) return BwrText.tr("status.bwr.reactor_controller.internals_not_formed", "the reactor is not formed");
        if (vesselState == VesselState.REFUELING)
            return BwrText.tr("status.bwr.reactor_controller.studs_head_off", "the head is off: the studs are already bare");
        double psig = core.getPressurePsig();
        if (psig > 25.0)
            return BwrText.tr("status.bwr.reactor_controller.studs_pressure",
                    "cannot detension the studs at %s psig; the vessel must be depressurised to 25 psig or less first", Math.round(psig));
        if (studsOff() >= studTotal())
            return BwrText.tr("status.bwr.reactor_controller.studs_all_off", "all %s studs are already detensioned", studTotal());
        return null;
    }

    /** Why the studs cannot be brought to load now, or null. */
    public Component studTightenBlocked() {
        if (!isFormed()) return BwrText.tr("status.bwr.reactor_controller.internals_not_formed", "the reactor is not formed");
        if (vesselState == VesselState.REFUELING)
            return BwrText.tr("status.bwr.reactor_controller.studs_set_head", "the head is off: set it back on the flange with the crane before the studs can be tensioned");
        if (studsOff() <= 0)
            return BwrText.tr("status.bwr.reactor_controller.studs_all_on", "all %s studs are already at load", studTotal());
        return null;
    }

    /** The tensioner has backed one more stud off or brought one more to load: {@code count} studs are now off. */
    public void setStudsOff(int count) {
        int next = Math.max(0, Math.min(studTotal(), count));
        if (next != studsOff) {
            studsOff = next;
            setChanged();
            syncToClients();
        }
    }

    /** Why the crane cannot lift the head now, or null. */
    public Component headLiftBlocked() {
        if (!isFormed()) return BwrText.tr("status.bwr.reactor_controller.internals_not_formed", "the reactor is not formed");
        if (vesselState == VesselState.REFUELING)
            return BwrText.tr("status.bwr.reactor_controller.head_already_off", "the head is already off the vessel");
        if (!dev.bwr.core.refuelling.CraneMotion.headLiftable(studsOff, studTotal()))
            return BwrText.tr("status.bwr.reactor_controller.studs_tensioned",
                    "%s of the %s closure studs are still tensioned: detension them with a stud tensioner first", studsTensioned(), studTotal());
        if (core.getPressurePsig() > 25.0)
            return BwrText.tr("status.bwr.reactor_controller.the_vessel_head_is_on_and_it_cannot",
                    "the vessel head is on, and it cannot be removed at %s psig; depressurise below 25 psig first", Math.round(core.getPressurePsig()));
        if (core.getBoundaryStress().isBroken(dev.bwr.core.boundary.BoundaryComponent.REACTOR_VESSEL_HEAD))
            return BwrText.tr("status.bwr.reactor_controller.head_failed",
                    "The vessel head has failed; changing the refuelling state cannot repair pressure-boundary damage.");
        return null;
    }

    /** Why the crane cannot set the head on the flange now, or null. */
    public Component headSeatBlocked() {
        if (!isFormed()) return BwrText.tr("status.bwr.reactor_controller.internals_not_formed", "the reactor is not formed");
        if (vesselState != VesselState.REFUELING)
            return BwrText.tr("status.bwr.reactor_controller.head_already_on", "the head is already on the vessel");
        return headSeatRefusal();
    }

    /** The crane has taken the head off the flange: the vessel is open, and the head is on the hook of the crane at {@code at}. */
    public void headLifted(BlockPos at) {
        vesselState = VesselState.REFUELING;
        headAt = at.immutable();
        studsOff = 0;
        refreshHeadAccess();
        setChanged();
        syncToClients();
    }

    /** The head has moved: onto a head stand at {@code at}, or (null) it is laid down somewhere. */
    public void headMoved(BlockPos at) {
        if (vesselState == VesselState.REFUELING && !java.util.Objects.equals(headAt, at)) {
            headAt = at == null ? null : at.immutable();
            setChanged();
            syncToClients();
        }
    }

    /** The crane has set the head on the flange: it is on, with every nut off until the tensioner brings the studs to load. */
    public void headSeated() {
        vesselState = VesselState.SHUTDOWN;
        headAt = null;
        studsOff = studTotal();
        refreshHeadAccess();
        setChanged();
        syncToClients();
    }

    /** What the INFO tab and the computer say about the head and its studs. */
    public Component headSummary() {
        int total = studTotal();
        if (vesselState == VesselState.REFUELING) {
            if (headAt == null)
                return BwrText.tr("status.bwr.reactor_controller.head_off_laid", "Head off, laid down clear of the vessel. The studs are bare. The crane can set it back on the flange.");
            return BwrText.tr("status.bwr.reactor_controller.head_off_at", "Head off, at %s. The studs are bare. The crane can set it back on the flange.", headAt.toShortString());
        }
        if (studsOff <= 0)
            return BwrText.tr("status.bwr.reactor_controller.head_on_tight", "Head on, all %s closure studs at load. To open the vessel: depressurise, detension the studs with a stud tensioner, then lift the head with the crane.", total);
        if (studsOff >= total)
            return BwrText.tr("status.bwr.reactor_controller.head_on_loose", "Head on, all %s studs detensioned (nuts off). The vessel cannot hold pressure. The crane can lift the head.", total);
        return BwrText.tr("status.bwr.reactor_controller.head_on_partial", "Head on, %s of %s studs at load. The vessel cannot hold pressure until all are.", total - studsOff, total);
    }

    private void refreshHeadAccess() {
        boolean failed=core!=null&&core.getBoundaryStress().isBroken(dev.bwr.core.boundary.BoundaryComponent.REACTOR_VESSEL_HEAD);
        VesselHeadAccess.update(level,getBlockPos(),vesselEnvelope,!vesselState.canHoldPressure()||failed);
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
        Component message = refuellingBlockedMessage();
        return message == null ? null : BwrText.english(message);
    }

    public Component refuellingBlockedMessage() {
        if (!isFormed()) {
            return BwrText.tr(
                    "status.bwr.reactor_controller.the_reactor_is_not_formed_so_there_is",
                    "the reactor is not formed, so there is no core to refuel");
        }
        if (vesselState == VesselState.REFUELING) {
            // instantRefuelling is the shortcut that skips the machinery: the dryer and the separators are not in the fuel's way then.
            if (dev.bwr.mod.config.GameplayConfig.instantRefuelling()) {
                return null;
            }
            if (internalInstalled(VesselInternal.DRYER)) {
                return BwrText.tr("status.bwr.reactor_controller.dryer_installed",
                        "the steam dryer is still in the vessel: lift it to a storage pad with the refuelling bridge, then the steam separator assembly");
            }
            if (internalInstalled(VesselInternal.SEPARATOR)) {
                return BwrText.tr("status.bwr.reactor_controller.separator_installed",
                        "the steam separator assembly is still on the core: lift it to a storage pad with the refuelling bridge");
            }
            return null;
        }
        double psig = core.getPressurePsig();
        if (psig > 25.0) {
            return BwrText.tr("status.bwr.reactor_controller.the_vessel_head_is_on_and_it_cannot",
                    "the vessel head is on, and it cannot be removed at %s psig; depressurise below 25 psig first", Math.round(psig));
        }
        return BwrText.tr("status.bwr.reactor_controller.head_can_be_removed",
                "the vessel head is on; the vessel is at %s psig so the head can be removed, but nobody has removed it yet", Math.round(psig));
    }

    // -----------------------------------------------------------------
    // The steam dryer and the separator assembly (RPV-REFERENCE.md)
    // -----------------------------------------------------------------

    public boolean internalInstalled(VesselInternal which) {
        return !internalsOut.containsKey(which);
    }

    /** Where an assembly that is out of the vessel is (a storage pad, or the console of the bridge holding it); null while installed. */
    public BlockPos internalLocation(VesselInternal which) {
        return internalsOut.get(which);
    }

    public int internalsOutMask() {
        int mask = 0;
        for (VesselInternal v : internalsOut.keySet()) mask |= v.bit();
        return mask;
    }

    private Component internalsAccess() {
        if (!isFormed()) return BwrText.tr("status.bwr.reactor_controller.internals_not_formed", "the reactor is not formed");
        if (vesselState != VesselState.REFUELING) return BwrText.tr("status.bwr.reactor_controller.internals_head_on", "the vessel head is on");
        return null;
    }

    /** Why this assembly cannot be lifted out now, or null. The dryer comes out first, and both only with the vessel flooded to its flange. */
    public Component internalLiftBlocked(VesselInternal which) {
        Component access = internalsAccess();
        if (access != null) return access;
        if (!internalInstalled(which)) return BwrText.tr("status.bwr.reactor_controller.internal_already_out", "the %s is already out of the vessel", which.title);
        if (which == VesselInternal.SEPARATOR && internalInstalled(VesselInternal.DRYER))
            return BwrText.tr("status.bwr.reactor_controller.dryer_first", "the steam dryer sits over the separators and must come out first");
        if (core.getCollapsedLevelIn() < dev.bwr.core.thermal.PressureVessel.FLANGE_LEVEL_IN - 1.0)
            return BwrText.tr("status.bwr.reactor_controller.internals_flood_first",
                    "flood the vessel to its flange first (the level is %s in; the flange is at +%s in): the assemblies are lifted out of a full vessel into the flooded well",
                    Math.round(core.getCollapsedLevelIn()), Math.round(dev.bwr.core.thermal.PressureVessel.FLANGE_LEVEL_IN));
        return null;
    }

    /** Why this assembly cannot be set back now, or null. The separator assembly goes in first. */
    public Component internalInstallBlocked(VesselInternal which) {
        Component access = internalsAccess();
        if (access != null) return access;
        if (internalInstalled(which)) return BwrText.tr("status.bwr.reactor_controller.internal_already_in", "the %s is already in the vessel", which.title);
        if (which == VesselInternal.DRYER && !internalInstalled(VesselInternal.SEPARATOR))
            return BwrText.tr("status.bwr.reactor_controller.separator_first", "the separator assembly goes back in before the dryer");
        return null;
    }

    /** The assembly has left its seat (latched by the bridge) or moved on (set on a pad): {@code at} is where it is now. */
    public void internalOut(VesselInternal which, BlockPos at) {
        internalsOut.put(which, at.immutable());
        setChanged();
        syncToClients();
    }

    /** The assembly is back in the vessel. */
    public void internalIn(VesselInternal which) {
        if (internalsOut.remove(which) != null) {
            setChanged();
            syncToClients();
        }
    }

    /** What the INFO tab and the computer say about the two assemblies. */
    public Component internalsSummary() {
        return Component.empty().append(headSummary()).append(" ").append(internalsOnlySummary());
    }

    private Component internalsOnlySummary() {
        if (internalsOut.isEmpty()) {
            return BwrText.tr("status.bwr.reactor_controller.internals_installed",
                    "Steam dryer and separator assembly installed. They come out with the head off and the vessel flooded to its flange, with the reactor building crane.");
        }
        StringBuilder where = new StringBuilder();
        for (var entry : internalsOut.entrySet()) {
            if (where.length() > 0) where.append("; ");
            BlockPos p = entry.getValue();
            where.append(entry.getKey().title).append(" at ").append(p.getX()).append(", ").append(p.getY()).append(", ").append(p.getZ());
        }
        return BwrText.tr("status.bwr.reactor_controller.internals_out",
                "Not operable: the %s. The head cannot go on until both are back in the vessel (separator assembly first, then the dryer).", where.toString());
    }

    public boolean clientDryerInstalled() {
        return (clientInternalsOut & VesselInternal.DRYER.bit()) == 0;
    }

    public boolean clientSeparatorInstalled() {
        return (clientInternalsOut & VesselInternal.SEPARATOR.bit()) == 0;
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
        // The decay heat clock of the spent fuel pool and the reprocessing plant starts here, in game time, so it
        // keeps running while the bundle is an item.
        if (level != null) dev.bwr.mod.fuel.SpentFuelHeat.stamp(stack, level.getGameTime());
        dev.bwr.mod.advancement.PlantMilestones.fuelUnloaded(this, latticePosition);
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
        else {
            core.getCoreLoading().load(latticePosition, dev.bwr.mod.fuel.FuelAssemblies.load(stack));
            dev.bwr.mod.advancement.PlantMilestones.fuelLoaded(this, latticePosition);
        }
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
                        .result().ifPresent(data -> {
                            var stack = dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(
                                    dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get(), data);
                            if (level != null) dev.bwr.mod.fuel.SpentFuelHeat.stamp(stack, level.getGameTime());
                            result.add(stack);
                        });
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
                        .resultOrPartial(message -> lastValidation.degrade(BwrText.tr("status.bwr.reactor_controller.invalid_saved_insert", "Invalid saved insert: %s" , message)))
                        .ifPresent(data -> { if (!loading.isOccupied(slot)) loading.loadInsert(slot, data.toCore()); });
                continue;
            }
            dev.bwr.mod.fuel.FuelAssemblyData.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, entry.get("Fuel"))
                    .resultOrPartial(message -> lastValidation.degrade(BwrText.tr(
                            "status.bwr.reactor_controller.a_saved_fuel_bundle_at_core_position_could",
                            "a saved fuel bundle at core position %s could not be read: %s",
                            slot,
                            message)))
                    .ifPresent(data -> loading.load(slot,
                            dev.bwr.mod.fuel.FuelAssemblies.toCore(data)));
        }
        pendingFuelRestore = null;
    }

    /** Human-readable status, shown when a player right-clicks the controller. */
    public List<String> statusLines() {
        return BwrText.english(statusComponents());
    }

    public List<Component> statusComponents() {
        List<Component> out = new ArrayList<>();
        out.add(BwrText.tr(
                "status.bwr.reactor_controller.matched_jet_assemblies_unmatched_external_internal_pumps_maximum",
                "Matched jet assemblies: %s; unmatched: %s; external/internal pumps: %s/%s; maximum forced flow: %s%%",
                String.format(java.util.Locale.ROOT, "%d", connectedJetPairs),
                String.format(java.util.Locale.ROOT, "%d", unmatchedJets),
                String.format(java.util.Locale.ROOT, "%d", connectedExternalPumps),
                String.format(java.util.Locale.ROOT, "%d", installedInternalPumps),
                String.format(java.util.Locale.ROOT, "%.0f", 100*recirculationCapacityFraction)));
        if (structure == null) {
            out.add(BwrText.tr("status.bwr.reactor_controller.reactor_not_formed", "Reactor not formed."));
            out.addAll(lastValidation.components());
            if (out.size() == 1) {
                out.add(BwrText.tr("status.bwr.reactor_controller.no_vessel_found_around_this_controller", "No vessel found around this controller."));
            }
            return out;
        }
        out.add(BwrText.tr(
                "status.bwr.reactor_controller.core_layout_one_drive_block_per_blade",
                "Core layout: %s; one drive block per blade.",
                (coreLayoutVersion == 1 ? BwrText.tr("status.bwr.reactor_controller.legacy", "legacy") : BwrText.tr("status.bwr.reactor_controller.compact", "compact"))));
        out.add(BwrText.tr(
                "status.bwr.reactor_controller.reactor_formed_assemblies_control_rods_vessel",
                "Reactor formed: %s assemblies, %s control rods, vessel %s",
                structure.assemblyCount(),
                structure.controlRodCount(),
                dev.bwr.mod.text.StatusNames.vessel(vesselState)));
        if (core != null) {
            out.add(BwrText.tr(
                    "status.bwr.reactor_controller.ated_psig_accumulators_charged",
                    "%s%% rated, %s psig, %s/%s accumulators charged",
                    String.format(java.util.Locale.ROOT, "%.2f", core.getTotalPowerFractionOfRated() * 100.0),
                    String.format(java.util.Locale.ROOT, "%.0f", core.getPressurePsig()),
                    String.format(java.util.Locale.ROOT, "%d", core.getChargedAccumulatorCount()),
                    String.format(java.util.Locale.ROOT, "%d", core.getControlRodCount())));
            // What the vessel's own steam penetrations are doing. A measurement,
            // and the one a player standing at the reactor wants while they are
            // working out why pressure is going the way it is.
            out.add(BwrText.tr(
                    "status.bwr.reactor_controller.rpv_steam_outlet_s_fitted_passing_kg_s",
                    "%s RPV steam outlet(s) fitted, passing %s kg/s; core boiling %s kg/s",
                    String.format(java.util.Locale.ROOT, "%d", structure.steamOutletCount()),
                    String.format(java.util.Locale.ROOT, "%.1f", lastSteamOutletFlowKgPerS),
                    String.format(java.util.Locale.ROOT, "%.1f", core.getSteamGenerationKgPerS())));
            out.addAll(BoundaryDamageReadout.statusComponents(core.getBoundaryStress()));
            if(level!=null) out.addAll(BoundaryDamageReadout.breakComponents(boundaryEffects,level.getGameTime()));
            out.addAll(SevereAccidentReadout.statusComponents(core));
        }
        out.addAll(lastValidation.components());
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence — freeze and resume, SPEC section 11
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        boundaryEffects.write(tag);
        severeEffects.write(tag);
        tag.putString("VesselState", vesselState.getSerializedName());
        tag.putInt("CoreLayoutVersion", coreLayoutVersion);
        tag.putInt("CoreInteriorWidth", savedInteriorWidth);
        tag.putInt("CoreInteriorDepth", savedInteriorDepth);
        tag.putIntArray("CoreDriveCells",savedDriveCells);

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
            // Water over the flange: the level alone does not say how much sits in the annulus, so the inventory is kept too.
            // Absent (and so default) while the vessel holds nothing above its flange, as in every older save.
            if (pendingWellMassKg > 0) tag.putDouble("WellInventoryKg", pendingWellMassKg);
            else if (core.getPressureVessel().getWellWaterM3() > 0) tag.putDouble("WellInventoryKg", core.getPressureVessel().getLiquidMassKg());
            // Overpressure damage is not part of ReactorState — it is a
            // property of the plant's metal rather than of its operating point
            // — so it is saved alongside rather than inside it.
            tag.put(BoundaryDamageNbt.KEY, BoundaryDamageNbt.write(core.getBoundaryStress()));
            tag.put(SevereAccidentNbt.KEY, SevereAccidentNbt.write(core));
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
                if (pendingWellMassKg > 0) tag.putDouble("WellInventoryKg", pendingWellMassKg);
            }
            if (pendingBoundaryRestore != null) {
                tag.put(BoundaryDamageNbt.KEY, pendingBoundaryRestore);
            }
            if (pendingSevereRestore != null) {
                tag.put(SevereAccidentNbt.KEY, pendingSevereRestore);
            }
        }

        // The operator's standing rod demand. ReactorState has no component for
        // it, so it is persisted here rather than lost; see
        // ControlRodDriveNetwork.restoreCommandedNotchIndices.
        int[] demand = rodNetwork != null ? rodNetwork.getCommandedNotchIndices() : pendingRodDemand;
        if (demand != null && demand.length > 0) {
            tag.putIntArray("RodDemand", demand);
        }
        // Old saves have no key, which loads as no selection.
        int selection = rodNetwork != null ? rodSelection.selected() : pendingSelectedRod;
        if (selection > 0) {
            tag.putInt("SelectedRod", selection);
        }
        // Only while an assembly is out of the vessel; older saves have no key and load as both installed.
        if (!internalsOut.isEmpty()) {
            CompoundTag out = new CompoundTag();
            internalsOut.forEach((which, at) -> out.putLong(which.id, at.asLong()));
            tag.put("InternalsOut", out);
        }
        // Only while the head is on with some studs off, or off and on a stand or a hook; older saves have neither key (every stud at load, the head laid down).
        if (studsOff > 0 && vesselState != VesselState.REFUELING) tag.putInt("StudsOff", studsOff);
        if (headAt != null && vesselState == VesselState.REFUELING) tag.putLong("HeadAt", headAt.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boundaryEffects.read(tag);
        severeEffects.read(tag);
        vesselState = VesselState.byName(tag.getString("VesselState"));
        studsOff = vesselState == VesselState.REFUELING ? 0 : Math.max(0, tag.getInt("StudsOff"));
        headAt = vesselState == VesselState.REFUELING && tag.contains("HeadAt") ? BlockPos.of(tag.getLong("HeadAt")) : null;
        internalsOut.clear();
        if (tag.contains("InternalsOut", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag out = tag.getCompound("InternalsOut");
            for (VesselInternal v : VesselInternal.values()) if (out.contains(v.id)) internalsOut.put(v, BlockPos.of(out.getLong(v.id)));
        }
        // Missing key is an old save, including a temporarily broken vessel.
        coreLayoutVersion = tag.contains("CoreLayoutVersion") ? tag.getInt("CoreLayoutVersion") : 1;
        savedInteriorWidth = tag.getInt("CoreInteriorWidth");
        savedInteriorDepth = tag.getInt("CoreInteriorDepth");
        savedDriveCells=tag.getIntArray("CoreDriveCells");

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
        if (tag.contains(SevereAccidentNbt.KEY)) {
            pendingSevereRestore = tag.getCompound(SevereAccidentNbt.KEY);
        }
        pendingFuelRestore = tag.contains("CoreFuel")
                ? tag.getList("CoreFuel", net.minecraft.nbt.Tag.TAG_COMPOUND)
                : null;
        pendingRodDemand = tag.contains("RodDemand") ? tag.getIntArray("RodDemand") : null;
        pendingWellMassKg = tag.contains("WellInventoryKg") ? tag.getDouble("WellInventoryKg") : -1;
        pendingSelectedRod = Math.max(0, tag.getInt("SelectedRod"));
        rodSelection.clear();
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
            // Restored detector coordinates may describe an earlier vessel footprint.
            rodLatticeMapSignature = Long.MIN_VALUE;
            installRodLatticeMap(structure);
        } catch (IllegalArgumentException e) {
            // Rod count changed while unloaded — the player rebuilt the vessel.
            // Keep the fresh core rather than refusing to load the chunk.
            lastValidation.degrade(BwrText.tr(
                    "status.bwr.reactor_controller.saved_reactor_state_did_not_match_the_rebuilt",
                    "saved reactor state did not match the rebuilt vessel: %s; the core was reinitialised",
                    e.getMessage()));
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
        long probe = dev.bwr.mod.TickProbe.start();
        try {
            return buildUpdateTag(registries);
        } finally {
            dev.bwr.mod.TickProbe.stop("  Reactor.getUpdateTag (per tracked chunk update)", probe);
        }
    }

    private CompoundTag buildUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("VesselState", vesselState.getSerializedName());
        tag.putBoolean("Formed", isFormed());
        if (vesselEnvelope != null) vesselEnvelope.write(tag);
        int internals = internalsOutMask();
        if (internals != 0) tag.putInt("VisualInternalsOut", internals);
        if (studsOff > 0 && vesselState != VesselState.REFUELING) tag.putInt("VisualStudsOff", studsOff);
        var visualCore=VesselCoreAppearance.capture(this);
        if(visualCore!=null) {
            visualCore.write(tag);
            int[] blades=new int[core.getControlRodCount()];
            for(int i=0;i<blades.length;i++)blades[i]=(int)Math.round(Math.clamp(1-core.getRodPositionNotches(i)/24.0,0,1)*65535);
            tag.putIntArray("VisualBladeInsertion",blades);
        }
        if (core != null) {
            // The client renders and displays; it never recomputes physics.
            tag.putDouble("Power", core.getTotalPowerFractionOfRated());
            tag.putInt("VisualBoundaryMask",ReactorBoundaryEffects.mask(core));
            tag.putLongArray("VisualBoundaryLeaks",boundaryEffects.leakPositions());
            long[] lineBreaks=boundaryEffects.lineBreakPositions();
            if(lineBreaks.length>0) {
                tag.putLongArray("VisualLineBreaks",lineBreaks);
                tag.putByteArray("VisualLineBreakJets",boundaryEffects.lineBreakJets());
            }
            tag.putDouble("Pressure", core.getPressurePsig());
            tag.putDouble("Level", core.getIndicatedLevelIn());
            // The free surface follows physical level, not reference-leg instrument error.
            tag.putDouble("VisualWaterLevel", core.getTwoPhaseLevelIn());
            tag.putBoolean("VisualWaterPresent",core.getPressureVessel().getLiquidMassKg()>0.001);
            double wellSurface=wellSurfaceLocal();
            if(wellSurface>0)tag.putDouble("VisualWellSurface",wellSurface);
            tag.putInt("ChargedAccumulators", core.getChargedAccumulatorCount());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        clientBoundaryMask=tag.getInt("VisualBoundaryMask");
        clientBoundaryLeaks=tag.getLongArray("VisualBoundaryLeaks");
        clientLineBreaks=tag.getLongArray("VisualLineBreaks");
        clientLineBreakJets=tag.getByteArray("VisualLineBreakJets");
        boolean hadWater=clientWaterPresent;
        double previousWater=clientWaterLevelIn(0);
        vesselState = VesselState.byName(tag.getString("VesselState"));
        // Everything getUpdateTag sends is stored, not just the vessel state.
        // Five of the six keys used to be read and thrown away here, so the
        // 4 Hz broadcast was bandwidth with no consumer, and a client-side
        // renderer or tooltip written against getUpdateTag — the natural place
        // to look — would have found default values on arrival.
        clientFormed = tag.getBoolean("Formed");
        clientInternalsOut = tag.getInt("VisualInternalsOut");
        clientStudsOff = tag.getInt("VisualStudsOff");
        clientVesselEnvelope = VesselAppearance.read(tag);
        var nextCore=VesselCoreAppearance.read(tag,clientVesselEnvelope);
        // Preserve identity when only physics measurements change: no mesh rebuild.
        if(!java.util.Objects.equals(clientCoreAppearance,nextCore)) {
            clientCoreAppearance=nextCore;
            clientHasFuel=nextCore!=null&&nextCore.cells().stream().anyMatch(c->c.content()==1);
        }
        var blades=tag.getIntArray("VisualBladeInsertion");
        double[] from=new double[clientCoreAppearance==null?0:clientCoreAppearance.drives().size()];
        for(int i=0;i<from.length;i++)from[i]=clientBladeInsertion(i,0);
        boolean first=from.length!=clientBladeInsertion.length;
        clientBladeInsertion=clientCoreAppearance==null?new double[0]:new double[clientCoreAppearance.drives().size()];
        for(int i=0;i<clientBladeInsertion.length&&i<blades.length;i++)clientBladeInsertion[i]=Math.clamp(blades[i]/65535.0,0,1);
        clientBladeFrom=first?clientBladeInsertion.clone():from;
        clientBladeSyncTick=level==null?0:level.getGameTime();
        VesselJetAccess.update(level,getBlockPos(),clientVesselEnvelope);
        VesselDriveAccess.update(level,getBlockPos(),clientVesselEnvelope);
        VesselAppearance.update(level,getBlockPos(),clientVesselEnvelope);
        VesselHeadAccess.update(level,getBlockPos(),clientVesselEnvelope,!vesselState.canHoldPressure()||clientHeadFailed());
        clientPowerFractionOfRated = tag.getDouble("Power");
        clientPressurePsig = tag.getDouble("Pressure");
        clientLevelIn = tag.getDouble("Level");
        clientWaterPresent=clientFormed&&tag.getBoolean("VisualWaterPresent");
        double water=tag.getDouble("VisualWaterLevel");
        clientWaterLevel=Double.isFinite(water)?water:-530;
        clientWaterFrom=hadWater?previousWater:clientWaterLevel;
        clientWaterSyncTick=level==null?0:level.getGameTime();
        clientChargedAccumulators = tag.getInt("ChargedAccumulators");
        double well=tag.getDouble("VisualWellSurface");
        clientWellSurface=Double.isFinite(well)&&well>0?well:0;
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
    private boolean clientHasFuel;
    public boolean clientHasFuel(){return clientHasFuel;}
    private double clientPressurePsig;
    private double clientLevelIn;
    private int clientChargedAccumulators;
    private boolean clientWaterPresent;
    private double clientWaterLevel=-530,clientWaterFrom=-530;
    private long clientWaterSyncTick;
    private double clientWellSurface;
    /** Height of the water in the well above the bottom of the vessel, blocks, as last synced; 0 when there is none. */
    public double clientWellSurface(){return clientWellSurface;}

    public boolean clientWaterPresent(){return clientWaterPresent;}
    public double clientWaterLevelIn(float partial) {
        double t=level==null?1:Math.clamp((level.getGameTime()-clientWaterSyncTick+partial)/SYNC_INTERVAL_TICKS,0,1);
        return clientWaterFrom+(clientWaterLevel-clientWaterFrom)*t;
    }

    /** @see #handleUpdateTag */
    public boolean clientFormed() {
        return clientFormed;
    }

    public VesselAppearance.Envelope clientVesselEnvelope() { return clientVesselEnvelope; }
    public VesselCoreAppearance clientCoreAppearance() { return clientCoreAppearance; }
    public double clientBladeInsertion(int i,float partial) {
        if(i<0||i>=clientBladeInsertion.length)return 0;
        double fraction=level==null?1:Math.clamp((level.getGameTime()-clientBladeSyncTick+partial)/SYNC_INTERVAL_TICKS,0,1);
        return clientBladeFrom[i]+(clientBladeInsertion[i]-clientBladeFrom[i])*fraction;
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
