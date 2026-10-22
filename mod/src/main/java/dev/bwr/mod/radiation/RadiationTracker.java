package dev.bwr.mod.radiation;

import dev.bwr.mod.BwrMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Samples the survey meter's dose rate for the players that carry one. Each player is looked at once every
 * {@link #SAMPLE_TICKS} ticks (staggered by entity id, so a server full of players does not all sample on one tick);
 * a player with no meter costs one integer test per tick and one inventory test per sample, and the world is never
 * scanned. A sample adds the dose of the interval to the player's personal dose and, when the meter is held, pushes
 * the reading to that player.
 *
 * <p>Creative and spectator players read the dose rate but accumulate nothing, like the contamination effects they
 * are exempt from.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class RadiationTracker {
    /** Ticks between samples: 4 Hz. */
    public static final int SAMPLE_TICKS = 5;

    private RadiationTracker() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && (player.tickCount + player.getId()) % SAMPLE_TICKS == 0
                && SurveyMeterItem.carriedBy(player)) {
            sample(player);
        }
    }

    /** Where a player's dose rate is measured: the middle of the body. */
    public static Vec3 sensorPosition(ServerPlayer player) {
        return player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
    }

    /** The dose rate at a player now, mSv/h, without recording anything. */
    public static double read(ServerPlayer player) {
        return RadiationField.doseRateMsvPerHour(player.serverLevel(), sensorPosition(player));
    }

    /**
     * One sample for one player, now: reads the dose rate at the player, adds one interval of it to the personal dose
     * when the player carries a meter, and pushes the readout when it is held. Returns the dose rate, mSv/h.
     */
    public static double sample(ServerPlayer player) {
        double rate = read(player);
        boolean held = SurveyMeterItem.heldBy(player);
        if ((held || SurveyMeterItem.carriedBy(player)) && !player.isCreative() && !player.isSpectator()) {
            PlayerDoses.add(player, rate * (SAMPLE_TICKS / 20.0) / 3600.0);
        }
        if (held && !(player instanceof FakePlayer)) {
            PacketDistributor.sendToPlayer(player, new ReadoutPayload(rate, PlayerDoses.get(player)));
        }
        return rate;
    }
}
