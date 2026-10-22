package dev.bwr.mod.reactor;

import dev.bwr.mod.text.BwrText;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.BlastPhysics;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.SevereAccident;
import dev.bwr.mod.accident.AccidentBlast;
import dev.bwr.mod.accident.CoriumConfig;
import dev.bwr.mod.accident.CoriumPool;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.damage.SevereAccidentNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * World consequences of the severe accident and containment models.
 *
 * <p>Events come from the core exactly once and are never saved, so nothing
 * here replays after a reload. Explosions damage the world, always: the
 * hydrogen burn, the detonation, the containment failure and the lower head
 * failure each queue an {@link AccidentBlast} job sized from the energy the
 * core reports (burned hydrogen, burn pressure, containment volume, debris
 * fraction) through {@link BlastPhysics}. The job breaks blocks whose rating is
 * below the local pressure, through the standard explosion and block-break
 * events, and never loads a chunk. The formed vessel, the reactor and
 * containment controllers survive every blast, so the plant's damage stays
 * readable; everything else is subject to its blast resistance.
 *
 * <p>Contamination is applied as vanilla status effects to players near the
 * reactor, scaled by the decaying hazard indices from the containment model
 * and by distance. Creative and spectator players are exempt.
 *
 * <p>Ex-vessel corium changes blocks too: the {@link CoriumPool} melts the
 * floor under a failed vessel as the concrete ablates, a few positions per
 * tick, unless the server config turns that off. Unlike events, its progress
 * is saved, and so are the blasts still to finish.
 */
public final class ReactorSevereAccidentEffects {

    /** How often contamination doses are applied, ticks. */
    public static final int DOSE_INTERVAL_TICKS = 40;

    /** Distance at which the in-plant hazard falls to half, blocks. */
    public static final double PLANT_HALF_DISTANCE_BLOCKS = 12.0;

    /** Distance at which the site hazard falls to half, blocks. */
    public static final double SITE_HALF_DISTANCE_BLOCKS = 64.0;

    /** Players beyond this distance are never dosed, blocks. */
    public static final double MAX_DOSE_DISTANCE_BLOCKS = 256.0;

    /** Players within this distance hear about events on their action bar, blocks. */
    public static final double NOTICE_DISTANCE_BLOCKS = 96.0;

    /** Site releases are spread over a wide area; this weights them against in-plant activity. */
    public static final double SITE_HAZARD_WEIGHT = 20.0;

    /** Ticks the corium pool waits for the pedestal blast before anchoring on whatever floor is there. */
    public static final int PEDESTAL_WAIT_TICKS = 200;

    private int sinceDose;

    private int pedestalWaitTicks;

    /**
     * The interior box of the containment room the last time this reactor was linked to one. A burn after the
     * breach, when no sealed room links any more, is still a burn in that room, so the blast stays inside it
     * instead of falling back to open space and taking the walls and the ground with it. Saved only when set.
     */
    private BlockPos roomMin;
    private BlockPos roomMax;

    private final CoriumPool corium = new CoriumPool();

    private final AccidentBlast.Queue blasts = new AccidentBlast.Queue();

    public CoriumPool corium() {
        return corium;
    }

    /** Blasts still to finish; saved with the reactor. */
    public AccidentBlast.Queue blasts() {
        return blasts;
    }

    public void afterStep(ReactorControllerBlockEntity reactor) {
        if (!(reactor.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ReactorCore core = reactor.core();
        if (core == null) {
            return;
        }
        BlockPos pos = reactor.getBlockPos();
        ReactorStructure structure = reactor.structure();
        for (SevereAccident.Event event : core.getSevereAccident().drainEvents()) {
            onCoreEvent(level, reactor, structure, event);
        }
        var containmentController = dev.bwr.mod.containment.ContainmentControllerRegistry
                .linkedReactor(level, pos);
        if (containmentController != null && containmentController.interiorMin() != null
                && containmentController.interiorMax() != null) {
            roomMin = containmentController.interiorMin().immutable();
            roomMax = containmentController.interiorMax().immutable();
        }
        BlockPos breachedAt = null;
        for (ContainmentAtmosphere.EventDetail detail : core.getContainment().drainEventDetails()) {
            ContainmentAtmosphere.Event event = detail.event();
            boolean failure = event == ContainmentAtmosphere.Event.HYDROGEN_DETONATION
                    || event == ContainmentAtmosphere.Event.CONTAINMENT_OVERPRESSURE_FAILURE;
            if (failure && breachedAt == null && containmentController != null)
                breachedAt = breach(level, containmentController, detail);
            onContainmentEvent(level, reactor, structure, containmentController,
                    failure && breachedAt != null ? breachedAt : pos, detail);
        }
        if (!blasts.isEmpty()) {
            blasts.run(level);
        }
        // The pool anchors on the first full-cube floor under the vessel, so it waits for the pedestal blast, but not
        // for ever: a blast stuck on an unloaded chunk must not hold the corium back.
        boolean pedestal = blasts.has(AccidentBlast.Kind.PEDESTAL);
        pedestalWaitTicks = pedestal ? pedestalWaitTicks + 1 : 0;
        if (!pedestal || pedestalWaitTicks > PEDESTAL_WAIT_TICKS) {
            corium.update(level, structure == null ? null : structure.interiorMin(),
                    structure == null ? null : structure.interiorMax(), core.getSevereAccident(),
                    CoriumConfig.alterWorld(), CoriumConfig.blocksPerTick());
        }
        if (++sinceDose >= DOSE_INTERVAL_TICKS) {
            sinceDose = 0;
            applyDoses(level, pos, core.getContainment());
        }
    }

    /** Where hydrogen burns: the middle of the vessel top, where the release and the relief valves are. */
    private static Vec3 ignition(BlockPos controller, ReactorStructure structure) {
        if (structure == null) {
            return new Vec3(controller.getX() + 0.5, controller.getY() + 2.0, controller.getZ() + 0.5);
        }
        BlockPos lo = structure.interiorMin(), hi = structure.interiorMax();
        return new Vec3((lo.getX() + hi.getX() + 1) / 2.0, hi.getY() + 0.5, (lo.getZ() + hi.getZ() + 1) / 2.0);
    }

    /**
     * Open the containment at its weakest spot, sized by the failure. The room reports the leak at once and the
     * wall blocks go through the protected path of {@link AccidentBlast}.
     *
     * @return the weakest spot, or null if there was nothing to open (not sealed, or a boundary chunk unloaded)
     */
    private BlockPos breach(ServerLevel level, ContainmentControllerBlockEntity room,
                            ContainmentAtmosphere.EventDetail detail) {
        var weakest = room.weakestSpot();
        if (weakest == null) {
            return null;
        }
        boolean detonation = detail.event() == ContainmentAtmosphere.Event.HYDROGEN_DETONATION;
        double load = detonation ? BlastPhysics.detonationPeakPsig(detail.peakPsig()) : detail.peakPsig();
        double tnt = detonation ? BlastPhysics.detonationTntKg(detail.hydrogenBurnedKg())
                : BlastPhysics.containmentBurstTntKg(detail.peakPsig(), detail.volumeM3());
        double centreLoad = BlastPhysics.breachLoadPsi(load, weakest.ratingPsig());
        var plan = room.planBreach(level, centreLoad, BlastPhysics.breachScaleBlocks(tnt));
        if (plan == null) {
            return null;
        }
        float radius = detonation ? 10.0f : 6.0f;
        blasts.add(AccidentBlast.breach(plan.centre().getX() + 0.5, plan.centre().getY() + 0.5,
                plan.centre().getZ() + 0.5, radius, plan.blocks()));
        return plan.centre();
    }

    public void write(CompoundTag tag) {
        SevereAccidentNbt.writeCorium(tag, corium);
        blasts.write(tag);
        if (roomMin != null && roomMax != null) {
            tag.putLong("AccidentRoomMin", roomMin.asLong());
            tag.putLong("AccidentRoomMax", roomMax.asLong());
        }
    }

    public void read(CompoundTag tag) {
        SevereAccidentNbt.readCorium(tag, corium);
        blasts.read(tag);
        roomMin = roomMax = null;
        if (tag.contains("AccidentRoomMin", net.minecraft.nbt.Tag.TAG_LONG)
                && tag.contains("AccidentRoomMax", net.minecraft.nbt.Tag.TAG_LONG)) {
            roomMin = BlockPos.of(tag.getLong("AccidentRoomMin"));
            roomMax = BlockPos.of(tag.getLong("AccidentRoomMax"));
        }
    }

    private void onCoreEvent(ServerLevel level, ReactorControllerBlockEntity reactor, ReactorStructure structure,
                             SevereAccident.Event event) {
        BlockPos pos = reactor.getBlockPos();
        switch (event) {
            case CLADDING_FAILURE -> notice(level, pos, BwrText.tr(
                    "status.bwr.reactor_severe_accident_effects.reactor_fuel_cladding_failure_coolant_activity_rising",
                    "Reactor: fuel cladding failure. Coolant activity rising."));
            case FUEL_MELT_ONSET -> notice(level, pos, BwrText.tr("status.bwr.reactor_severe_accident_effects.reactor_core_melt_in_progress", "Reactor: core melt in progress."));
            case CORE_RELOCATION -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.reactor_molten_core_relocating_to_the_lower_plenum",
                        "Reactor: molten core relocating to the lower plenum."));
                level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 3f, 0.5f);
            }
            case LOWER_HEAD_FAILURE -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.reactor_vessel_lower_head_failed_corium_on_the",
                        "Reactor: VESSEL LOWER HEAD FAILED. Corium on the drywell floor."));
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 8f, 0.4f);
                lowerHeadFailed(reactor);
            }
            case BASEMAT_MELT_THROUGH -> {
                // The floor column itself is the corium pool's work, along the ablation depth.
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.reactor_corium_has_penetrated_the_basemat_containment_lost",
                        "Reactor: corium has penetrated the basemat. Containment lost."));
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 6f, 0.3f);
            }
        }
    }

    /**
     * The debris leaves the vessel: the molten fuel-coolant interaction in the cavity breaks the pedestal under the
     * footprint of the vessel. Queued once, from the core event.
     */
    public void lowerHeadFailed(ReactorControllerBlockEntity reactor) {
        ReactorStructure structure = reactor.structure();
        if (!(reactor.getLevel() instanceof ServerLevel level) || structure == null || reactor.core() == null) {
            return;
        }
        var accident = reactor.core().getSevereAccident();
        double tnt = BlastPhysics.lowerHeadTntKg(
                accident.getLowerPlenumDebrisFraction() + accident.getExVesselFraction(), accident.getSizeScale());
        blasts.add(AccidentBlast.pedestal(level, structure.interiorMin(), structure.interiorMax(), tnt, 4.0f));
    }

    private void onContainmentEvent(ServerLevel level, ReactorControllerBlockEntity reactor, ReactorStructure structure,
                                    ContainmentControllerBlockEntity room, BlockPos pos,
                                    ContainmentAtmosphere.EventDetail detail) {
        double x = pos.getX() + 0.5, y = pos.getY() + 2.0, z = pos.getZ() + 0.5;
        Vec3 fire = ignition(reactor.getBlockPos(), structure);
        BlockPos[] box = roomMin == null || roomMax == null ? null : new BlockPos[] {roomMin, roomMax};
        switch (detail.event()) {
            case HYDROGEN_FLAMMABLE -> notice(level, pos, BwrText.tr(
                    "status.bwr.reactor_severe_accident_effects.containment_hydrogen_concentration_is_flammable",
                    "Containment: hydrogen concentration is flammable."));
            case HYDROGEN_DEFLAGRATION -> {
                notice(level, pos, BwrText.tr("status.bwr.reactor_severe_accident_effects.containment_hydrogen_burn", "Containment: HYDROGEN BURN."));
                level.explode(null, x, y, z, 4.0f, Level.ExplosionInteraction.NONE);
                // Room pressure falling with distance from the flame and clipped to the burn's AICC peak:
                // weak fixtures go, walls and heavy blocks do not.
                blasts.add(protect(AccidentBlast.interior(level, fire.x, fire.y, fire.z,
                        BlastPhysics.deflagrationTntKg(detail.hydrogenBurnedKg()),
                        Math.max(0.0, detail.peakPsig()), 4.0f, box), structure));
            }
            case HYDROGEN_DETONATION -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.containment_hydrogen_detonation_containment_destroyed",
                        "Containment: HYDROGEN DETONATION. Containment destroyed."));
                level.explode(null, x, y, z, 10.0f, Level.ExplosionInteraction.NONE);
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 32f, 0.5f);
                blasts.add(protect(AccidentBlast.interior(level, fire.x, fire.y, fire.z,
                        BlastPhysics.detonationTntKg(detail.hydrogenBurnedKg()),
                        BlastPhysics.detonationPeakPsig(detail.peakPsig()), 10.0f, box), structure));
            }
            case CONTAINMENT_OVERPRESSURE_FAILURE -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.containment_overpressure_failure_uncontrolled_release",
                        "Containment: OVERPRESSURE FAILURE. Uncontrolled release."));
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 12f, 0.6f);
                if (room != null && room.weakestSpot() != null && !pos.equals(reactor.getBlockPos())) {
                    // The jet through the hole: the pressure difference across the failed wall, falling with the
                    // burst's energy, sweeps weak fixtures near the breach.
                    double rating = room.weakestSpot().ratingPsig();
                    double excess = Math.max(0.0, BlastPhysics.breachLoadPsi(detail.peakPsig(), rating) - rating);
                    blasts.add(protect(AccidentBlast.interior(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            BlastPhysics.containmentBurstTntKg(detail.peakPsig(), detail.volumeM3()), excess, 6.0f, box),
                            structure));
                }
            }
        }
    }

    private static AccidentBlast.Job protect(AccidentBlast.Job job, ReactorStructure structure) {
        return structure == null ? job : job.protect(structure.interiorMin(), structure.interiorMax());
    }

    private static void notice(ServerLevel level, BlockPos pos, Component text) {
        double limit = NOTICE_DISTANCE_BLOCKS * NOTICE_DISTANCE_BLOCKS;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= limit) {
                player.displayClientMessage(text, false);
            }
        }
    }

    private static void applyDoses(ServerLevel level, BlockPos pos, ContainmentAtmosphere containment) {
        double plant = containment.getPlantContaminationIndex();
        double site = containment.getSiteContaminationIndex();
        if (!(plant > 0.0) && !(site > 0.0)) {
            return;
        }
        double limit = MAX_DOSE_DISTANCE_BLOCKS * MAX_DOSE_DISTANCE_BLOCKS;
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }
            double d2 = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d2 > limit) {
                continue;
            }
            double hazard = plant / (1.0 + d2 / (PLANT_HALF_DISTANCE_BLOCKS * PLANT_HALF_DISTANCE_BLOCKS))
                    + SITE_HAZARD_WEIGHT * site / (1.0 + d2 / (SITE_HALF_DISTANCE_BLOCKS * SITE_HALF_DISTANCE_BLOCKS));
            dose(player, hazard * dev.bwr.mod.config.GameplayConfig.contaminationDoseMultiplier());
        }
    }

    /**
     * Hazard is in fraction-of-core-inventory units. The gap release of a whole
     * core is 0.05; a full melt releases several tenths. Thresholds are game
     * calibration: a cladding failure makes the reactor hall unpleasant, a melt
     * makes it lethal, and site releases reach further at lower intensity.
     */
    static void dose(ServerPlayer player, double hazard) {
        int duration = DOSE_INTERVAL_TICKS + 20;
        if (hazard > 1.0e-4) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER, duration, 0, false, true));
        }
        if (hazard > 1.0e-3) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, 0, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.POISON, duration, 0, false, true));
        }
        if (hazard > 1.0e-2) {
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, duration, 0, false, true));
        }
        if (hazard > 1.0e-1) {
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, duration, 2, false, true));
        }
    }
}
