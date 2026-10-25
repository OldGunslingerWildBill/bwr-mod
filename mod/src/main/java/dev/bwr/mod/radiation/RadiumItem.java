package dev.bwr.mod.radiation;

import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Radium (item id {@code radium}): a small sealed capsule of radium salt, a radioactive source material. It is the same kind of
 * thing as {@code SpentResinItem} (a carried item that is a gamma point source at its carrier, read by the survey meter, adding
 * to the dose of a player who carries a meter), with a fixed activity: one item is {@link #ACTIVITY_BQ} becquerels, about ten
 * millicuries, which is about ten milligrams of radium-226. Radium is a rare byproduct of mining thorium ore (the thorium decay series
 * passes through radium-228); it is not made any other way. This mod gives it no use: it is source material for other things to be
 * built on (see THORIUM.md).
 */
public class RadiumItem extends Item {
    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "radium");
    /** About ten millicuries, ten milligrams of radium-226 (a luminous-paint or needle source), in each item. */
    public static final double ACTIVITY_BQ = 3.7e8;
    /** What the glass and the capsule let through. */
    public static final double TRANSMISSION = 0.5;

    public RadiumItem(Properties properties) {
        super(properties);
    }

    public static void register() {
        RadiationSources.register(RADIATION_ID, RadiumItem::doseRate);
    }

    /** Total gamma power a player carries in radium, watts. */
    public static double carriedGammaWatts(Player player) {
        double count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(BwrItems.RADIUM.get())) count += stack.getCount();
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(BwrItems.RADIUM.get())) count += stack.getCount();
        }
        return count * ACTIVITY_BQ * CoolantChemistry.GAMMA_WATTS_PER_BQ;
    }

    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) return 0;
        double total = 0;
        for (ServerPlayer player : ((ServerLevel) level).players()) {
            if (player.distanceToSqr(at) > DoseModel.MAX_RANGE_M * DoseModel.MAX_RANGE_M) continue;
            total += doseRateOf(player, at);
        }
        return total;
    }

    /** What the radium a player carries reads at {@code at}, mSv/h. */
    public static double doseRateOf(Player player, Vec3 at) {
        double w = carriedGammaWatts(player);
        return w > 0 ? DoseModel.pointSourceMsvPerH(w, player.position().add(0, 1, 0).distanceTo(at), TRANSMISSION) : 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.radium", "Radioactive source material: a sealed capsule of radium. Carrying it adds to your dose; a survey meter reads it."));
        lines.add(Component.literal(String.format(java.util.Locale.ROOT, "%.2e Bq, %.3f mW gamma each", ACTIVITY_BQ,
                ACTIVITY_BQ * CoolantChemistry.GAMMA_WATTS_PER_BQ * 1000.0)));
        super.appendHoverText(stack, context, lines, flag);
    }
}
