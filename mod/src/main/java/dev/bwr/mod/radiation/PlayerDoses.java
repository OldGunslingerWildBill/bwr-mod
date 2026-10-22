package dev.bwr.mod.radiation;

import dev.bwr.mod.registry.BwrAttachments;
import net.minecraft.world.entity.player.Player;

/**
 * The personal dose a player has accumulated, in mSv, stored on the player as a data attachment (saved only when it is
 * above zero, kept through death, so an old player file loads with none). It grows only while the player carries a
 * survey meter ({@link RadiationTracker}) and is reset by a sneak-use of the meter.
 */
public final class PlayerDoses {
    private PlayerDoses() {
    }

    /** The accumulated dose, mSv. */
    public static double get(Player player) {
        return player.getData(BwrAttachments.PLAYER_DOSE.get());
    }

    /** Adds {@code msv} (ignored unless positive and finite) and returns the new total. */
    public static double add(Player player, double msv) {
        double total = get(player);
        if (msv > 0.0 && Double.isFinite(msv)) {
            total += msv;
            player.setData(BwrAttachments.PLAYER_DOSE.get(), total);
        }
        return total;
    }

    /** Resets the dose to zero and returns what it was. */
    public static double reset(Player player) {
        double was = get(player);
        if (was != 0.0) {
            player.removeData(BwrAttachments.PLAYER_DOSE.get());
        }
        return was;
    }
}
