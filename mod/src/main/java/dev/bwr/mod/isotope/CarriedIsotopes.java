package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.radiation.RadiationSources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Isotope capsules (any item with an RTG fuel entry that sends out photons) a player carries are a point source at
 * the player, as spent resin is: the survey meter and the area monitor read it, and the carrier's own meter accumulates
 * the dose. The power is grams x photon power per gram x what the capsule wall passes, all from the fuel list, so a
 * 250 g caesium-137 capsule is tens of Sv/h at a metre and an americium-241 capsule a few mSv/h.
 */
public final class CarriedIsotopes {

    private CarriedIsotopes() {
    }

    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "carried_isotopes");

    public static void register() {
        RadiationSources.register(RADIATION_ID, CarriedIsotopes::doseRate);
    }

    /** Photon power of a whole stack, W (the dose model's unit), zero for an item that is not an emitting fuel. */
    public static double gammaWatts(ItemStack stack) {
        RtgFuel fuel = RtgFuels.of(stack);
        if (fuel == null) {
            return 0.0;
        }
        return RadioisotopePower.gammaWatts(fuel.gramsPerItem() * stack.getCount(), fuel.gammaWPerG(), fuel.capsuleTransmission());
    }

    /** Photon power a player carries, W. */
    public static double carriedGammaWatts(Player player) {
        double w = 0.0;
        for (ItemStack stack : player.getInventory().items) {
            w += gammaWatts(stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            w += gammaWatts(stack);
        }
        return w;
    }

    /** What one item of this stack reads at 1 m, mSv/h. */
    public static double doseRateAtOneMetre(ItemStack stack) {
        ItemStack one = stack.copyWithCount(1);
        return DoseModel.pointSourceMsvPerH(gammaWatts(one), 1.0, 1.0);
    }

    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) {
            return 0.0;
        }
        double total = 0.0;
        for (ServerPlayer player : ((ServerLevel) level).players()) {
            if (player.distanceToSqr(at) > DoseModel.MAX_RANGE_M * DoseModel.MAX_RANGE_M) {
                continue;
            }
            total += doseRateOf(player, at);
        }
        return total;
    }

    /** What the capsules a player carries read at {@code at}, mSv/h. */
    public static double doseRateOf(Player player, Vec3 at) {
        double w = carriedGammaWatts(player);
        return w > 0.0 ? DoseModel.pointSourceMsvPerH(w, player.position().add(0, 1, 0).distanceTo(at), 1.0) : 0.0;
    }
}
