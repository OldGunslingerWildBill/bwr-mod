package dev.bwr.mod.reactor;

import dev.bwr.mod.text.BwrText;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.SevereAccident;
import dev.bwr.mod.accident.CoriumConfig;
import dev.bwr.mod.accident.CoriumPool;
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

/**
 * World consequences of the severe accident and containment models.
 *
 * <p>Events come from the core exactly once and are never saved, so nothing
 * here replays after a reload. Hydrogen burns use real explosions with
 * {@link Level.ExplosionInteraction#NONE}: they hurt anyone nearby and knock
 * things about but do not delete the plant's blocks, which keeps the vessel
 * multiblock formed so its damage, corium and contamination remain readable.
 * The plant is wrecked and contaminated, not erased (SPEC 8.4).
 *
 * <p>Contamination is applied as vanilla status effects to players near the
 * reactor, scaled by the decaying hazard indices from the containment model
 * and by distance. Creative and spectator players are exempt.
 *
 * <p>Ex-vessel corium is the one consequence that does change blocks: the
 * {@link CoriumPool} melts the floor under a failed vessel as the concrete
 * ablates, a few positions per tick, unless the server config turns world
 * alteration off. Unlike events, its progress is saved.
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

    private int sinceDose;

    private final CoriumPool corium = new CoriumPool();

    public CoriumPool corium() {
        return corium;
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
        for (SevereAccident.Event event : core.getSevereAccident().drainEvents()) {
            onCoreEvent(level, pos, event);
        }
        ReactorStructure structure = reactor.structure();
        corium.update(level, structure == null ? null : structure.interiorMin(),
                structure == null ? null : structure.interiorMax(), core.getSevereAccident(),
                CoriumConfig.alterWorld(), CoriumConfig.blocksPerTick());
        var containmentController = dev.bwr.mod.containment.ContainmentControllerRegistry
                .linkedReactor(level, pos);
        BlockPos breachedAt = null;
        for (ContainmentAtmosphere.Event event : core.getContainment().drainEvents()) {
            boolean failure = event == ContainmentAtmosphere.Event.HYDROGEN_DETONATION
                    || event == ContainmentAtmosphere.Event.CONTAINMENT_OVERPRESSURE_FAILURE;
            if (failure && breachedAt == null && containmentController != null)
                breachedAt = containmentController.ruptureWeakest(level);
            onContainmentEvent(level, failure && breachedAt != null ? breachedAt : pos, event);
        }
        if (++sinceDose >= DOSE_INTERVAL_TICKS) {
            sinceDose = 0;
            applyDoses(level, pos, core.getContainment());
        }
    }

    public void write(CompoundTag tag) {
        SevereAccidentNbt.writeCorium(tag, corium);
    }

    public void read(CompoundTag tag) {
        SevereAccidentNbt.readCorium(tag, corium);
    }

    private static void onCoreEvent(ServerLevel level, BlockPos pos, SevereAccident.Event event) {
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
            }
            case BASEMAT_MELT_THROUGH -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.reactor_corium_has_penetrated_the_basemat_containment_lost",
                        "Reactor: corium has penetrated the basemat. Containment lost."));
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 6f, 0.3f);
            }
        }
    }

    private static void onContainmentEvent(ServerLevel level, BlockPos pos, ContainmentAtmosphere.Event event) {
        double x = pos.getX() + 0.5, y = pos.getY() + 2.0, z = pos.getZ() + 0.5;
        switch (event) {
            case HYDROGEN_FLAMMABLE -> notice(level, pos, BwrText.tr(
                    "status.bwr.reactor_severe_accident_effects.containment_hydrogen_concentration_is_flammable",
                    "Containment: hydrogen concentration is flammable."));
            case HYDROGEN_DEFLAGRATION -> {
                notice(level, pos, BwrText.tr("status.bwr.reactor_severe_accident_effects.containment_hydrogen_burn", "Containment: HYDROGEN BURN."));
                level.explode(null, x, y, z, 4.0f, Level.ExplosionInteraction.NONE);
            }
            case HYDROGEN_DETONATION -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.containment_hydrogen_detonation_containment_destroyed",
                        "Containment: HYDROGEN DETONATION. Containment destroyed."));
                level.explode(null, x, y, z, 10.0f, Level.ExplosionInteraction.NONE);
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 32f, 0.5f);
            }
            case CONTAINMENT_OVERPRESSURE_FAILURE -> {
                notice(level, pos, BwrText.tr(
                        "status.bwr.reactor_severe_accident_effects.containment_overpressure_failure_uncontrolled_release",
                        "Containment: OVERPRESSURE FAILURE. Uncontrolled release."));
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 12f, 0.6f);
            }
        }
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
            double hazard = plantHazardAt(plant, d2) + siteHazardAt(site, d2);
            dose(player, hazard * dev.bwr.mod.config.GameplayConfig.contaminationDoseMultiplier());
        }
    }

    /**
     * The in-plant part of the hazard at squared distance {@code d2} (blocks) from the controller: the plant index
     * (airborne plus decaying deposited activity) over {@code 1 + d2 / 12^2}. Shared with the survey meter and the area
     * monitor ({@code RadiationField}), so an instrument can never disagree with what the game does to a player.
     */
    public static double plantHazardAt(double plantIndex, double d2) {
        return plantIndex / (1.0 + d2 / (PLANT_HALF_DISTANCE_BLOCKS * PLANT_HALF_DISTANCE_BLOCKS));
    }

    /** The environmental part of the hazard at squared distance {@code d2}: the weighted site index over {@code 1 + d2 / 64^2}. */
    public static double siteHazardAt(double siteIndex, double d2) {
        return SITE_HAZARD_WEIGHT * siteIndex / (1.0 + d2 / (SITE_HALF_DISTANCE_BLOCKS * SITE_HALF_DISTANCE_BLOCKS));
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
