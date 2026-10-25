package dev.bwr.mod.radiation;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorSevereAccidentEffects;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolRegistry;
import dev.bwr.mod.text.BwrText;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What the water in a spent fuel pool or in a flooded reactor well does to a person in it. The radiation field already says how
 * hot the water is near the fuel ({@link RadiationField}); this is the part that makes it hurt.
 *
 * <p>A player whose body is <b>in the water of a formed pool</b> (inside its interior, under its surface, {@link SpentFuelPoolBlockEntity#inWater})
 * or <b>in the water of a reactor with its head off</b> ({@link RadiationField#inOpenWater}) is sampled every {@link #SAMPLE_TICKS} ticks,
 * staggered by player. The sample reads the same function the survey meter reads ({@link RadiationTracker#read}), so a meter and the harm
 * can never disagree, then:
 * <ul>
 *   <li>adds the dose of the interval to the player's personal dose, <b>whether or not a meter is carried</b> (a person in the water is
 *       exposed whether or not they can measure it; a carried meter already records its own dose, so it is not added twice);</li>
 *   <li>applies the mod's own effects ({@link ReactorSevereAccidentEffects#dose}) for the hazard that dose rate stands for
 *       ({@link DoseModel#hazardFromDoseRate}): hunger from about 150 mSv/h, weakness and poison from 1.5 Sv/h, wither from 15 Sv/h and wither III
 *       from 150 Sv/h, the same thresholds that contamination uses;</li>
 *   <li>the first time it reaches the hunger threshold, tells the player, with the reading.</li>
 * </ul>
 * Creative and spectator players are exempt (nothing is recorded, no effect, no message). The contamination multiplier of the server config
 * is not applied: it scales the plant's airborne activity, not a source in the water, and the reading must equal the harm.
 *
 * <p>Cost: no block ticker and no world scan. A player who is not in such water costs one integer test per tick, and a sample is one
 * containment test per formed pool and per formed reactor of the level, then one {@code RadiationField} evaluation only for a player who is in the water.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class ImmersionDose {
    /** Ticks between samples of one player: twice a second. */
    public static final int SAMPLE_TICKS = 10;

    /** The dose rate from which the player is told, mSv/h: the hunger threshold of the contamination effects (hazard 1e-4). */
    public static final double WARN_MSV_PER_H = DoseModel.doseRateFromHazard(1.0e-4);

    private static final Set<UUID> WARNED = new HashSet<>();

    private ImmersionDose() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && (player.tickCount + player.getId()) % SAMPLE_TICKS == 0) {
            sample(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        synchronized (WARNED) {
            WARNED.remove(event.getEntity().getUUID());
        }
    }

    /** Whether the body of this player (the middle of it, where the meter measures) is in the water of a formed pool or a flooded open reactor. */
    public static boolean immersed(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 at = RadiationTracker.sensorPosition(player);
        for (SpentFuelPoolBlockEntity pool : SpentFuelPoolRegistry.controllers(level)) {
            if (pool.inWater(at.x, at.y, at.z)) {
                return true;
            }
        }
        for (ReactorControllerBlockEntity reactor : FormedReactorRegistry.controllers(level)) {
            if (RadiationField.inOpenWater(reactor, at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One sample for one player, now. Returns the dose rate in mSv/h if the player is in such water and subject to it, or {@code -1} when
     * nothing was done (not in the water, creative or spectator).
     */
    public static double sample(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator() || !immersed(player)) {
            synchronized (WARNED) {
                WARNED.remove(player.getUUID());
            }
            return -1.0;
        }
        double rate = RadiationTracker.read(player);
        if (!SurveyMeterItem.carriedBy(player)) {
            PlayerDoses.add(player, rate * (SAMPLE_TICKS / 20.0) / 3600.0);
        }
        ReactorSevereAccidentEffects.dose(player, DoseModel.hazardFromDoseRate(rate));
        boolean high = rate >= WARN_MSV_PER_H;
        boolean first = false;
        synchronized (WARNED) {
            if (high) {
                first = WARNED.add(player.getUUID());
            } else {
                WARNED.remove(player.getUUID());
            }
        }
        if (first) {
            player.displayClientMessage(BwrText.tr("message.bwr.immersion.burning_warmth",
                    "You feel a burning warmth on your skin. The water here is not safe: get out. Dose rate %s, your dose so far %s.",
                    DoseModel.formatRate(rate), DoseModel.formatDose(PlayerDoses.get(player))), false);
        }
        return rate;
    }

    /** Whether the player has been told about this stay in the water (tests). */
    public static boolean warned(ServerPlayer player) {
        synchronized (WARNED) {
            return WARNED.contains(player.getUUID());
        }
    }
}
