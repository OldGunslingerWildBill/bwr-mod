package dev.bwr.mod.fuel;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.radiation.BlockShielding;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spent fuel out of the water as a radiation source: the assemblies a player carries are a point source at the player, as
 * isotope capsules and spent resin are ({@link dev.bwr.mod.isotope.CarriedIsotopes}), and an assembly lying on the ground
 * (a dropped item) is a point source where it lies. The survey meter and the area monitor read both, and the carrier's
 * own meter accumulates the dose.
 *
 * <p>The power is the gamma share of the decay heat ({@link BridgeMotion#gammaWatts}, the share the bridge and the pool
 * use) of <b>every assembly in the stack</b>: assemblies stack as items for bulk moving, and a stack of sixty-four fresh
 * discharges is sixty-four times the source of one. That, and not a stack limit, is what makes carrying spent fuel by
 * hand the wrong idea; a fresh assembly has no decay heat and reads nothing. Nothing here shields: a pocket is not a
 * cask, and a dropped stack is read through whatever blocks lie on the line ({@link BlockShielding}), water included.
 *
 * <p>Read a few times a second by an instrument, never per tick: the work is the players of the level and the item
 * entities within reading range of the point.
 */
public final class CarriedFuel {

    private CarriedFuel() {
    }

    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "carried_fuel");

    /**
     * Decay heat of one assembly above which its tooltip warns about carrying it, W. A hundred watts of decay heat is
     * fifty watts of gamma, tens of sieverts an hour at a metre: there is no spent assembly a player should pocket, and
     * the line is there so nobody finds that out from the dosimeter.
     */
    public static final double WARNING_DECAY_WATTS = 100.0;

    public static void register() {
        RadiationSources.register(RADIATION_ID, CarriedFuel::doseRate);
    }

    private static boolean isAssembly(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BwrItems.FUEL_ASSEMBLY.get());
    }

    /** Gamma power of a whole stack of assemblies at {@code gameTime}, W (the dose model's unit); zero for anything else. */
    public static double gammaWatts(ItemStack stack, long gameTime) {
        return isAssembly(stack) ? BridgeMotion.gammaWatts(SpentFuelHeat.decayWatts(stack, gameTime)) : 0.0;
    }

    /** Gamma power of the assemblies a player carries (main inventory and offhand), W. */
    public static double carriedGammaWatts(Player player) {
        long now = player.level().getGameTime();
        double w = 0.0;
        for (ItemStack stack : player.getInventory().items) {
            w += gammaWatts(stack, now);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            w += gammaWatts(stack, now);
        }
        return w;
    }

    /** What the assemblies a player carries read at {@code at}, mSv/h. */
    public static double doseRateOf(Player player, Vec3 at) {
        double w = carriedGammaWatts(player);
        return w > 0.0 ? BlockShielding.pointSource(player.level(), player.position().add(0, 1, 0), at, w, 1.0, false) : 0.0;
    }

    /** What a dropped stack of assemblies reads at {@code at}, mSv/h; zero for any other item entity. */
    public static double doseRateOf(ItemEntity item, Vec3 at) {
        double w = gammaWatts(item.getItem(), item.level().getGameTime());
        return w > 0.0 ? BlockShielding.pointSource(item.level(), item.position().add(0, 0.25, 0), at, w, 1.0, false) : 0.0;
    }

    /** The dose rate at {@code at} from every carried and dropped assembly within reading range, mSv/h. */
    static double doseRate(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server)) {
            return 0.0;
        }
        double range = DoseModel.MAX_RANGE_M;
        double total = 0.0;
        for (ServerPlayer player : server.players()) {
            if (player.distanceToSqr(at) > range * range) {
                continue;
            }
            total += doseRateOf(player, at);
        }
        AABB box = new AABB(at.x - range, at.y - range, at.z - range, at.x + range, at.y + range, at.z + range);
        for (ItemEntity item : server.getEntitiesOfClass(ItemEntity.class, box, e -> isAssembly(e.getItem()))) {
            total += doseRateOf(item, at);
        }
        return total;
    }
}
