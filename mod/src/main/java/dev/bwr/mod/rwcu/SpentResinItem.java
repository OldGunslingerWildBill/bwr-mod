package dev.bwr.mod.rwcu;

import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Spent demineraliser resin: radioactive waste. The bed that came out of a filter-demineraliser carries the activity of
 * the crud it caught (becquerels, per item) and the survey meter reads it: through {@link #register} every spent resin a
 * nearby player carries is a point source at the player (the packaging passes {@link #TRANSMISSION} of the gamma).
 * Nothing disposes of it for the player; that is the player's waste handling.
 */
public class SpentResinItem extends Item {
    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "spent_resin");
    /** What the drum's wall and the resin's own water let through. */
    public static final double TRANSMISSION = 0.25;
    public static final String ACTIVITY_KEY = "ActivityBq";

    public SpentResinItem(Properties properties) { super(properties); }

    public static void register() { RadiationSources.register(RADIATION_ID, SpentResinItem::doseRate); }

    public static ItemStack make(double activityBq, double ionG, double crudG, int count) {
        ItemStack stack = new ItemStack(BwrItems.SPENT_RESIN.get(), count);
        CompoundTag tag = new CompoundTag();
        tag.putDouble(ACTIVITY_KEY, Math.max(0, activityBq));
        if (ionG > 0) tag.putDouble("IonG", ionG);
        if (crudG > 0) tag.putDouble("CrudG", crudG);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Activity of one item of the stack, Bq. */
    public static double activityBq(ItemStack stack) {
        if (!stack.is(BwrItems.SPENT_RESIN.get())) return 0;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : data.copyTag().getDouble(ACTIVITY_KEY);
    }

    /** Total gamma power a player carries in spent resin, watts. */
    public static double carriedGammaWatts(Player player) {
        double bq = 0;
        for (ItemStack stack : player.getInventory().items) bq += activityBq(stack) * stack.getCount();
        for (ItemStack stack : player.getInventory().offhand) bq += activityBq(stack) * stack.getCount();
        return bq * CoolantChemistry.GAMMA_WATTS_PER_BQ;
    }

    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) return 0;
        double total = 0;
        for (ServerPlayer player : ((net.minecraft.server.level.ServerLevel) level).players()) {
            if (player.distanceToSqr(at) > DoseModel.MAX_RANGE_M * DoseModel.MAX_RANGE_M) continue;
            total += doseRateOf(player, at);
        }
        return total;
    }

    /** What the spent resin a player carries reads at {@code at}, mSv/h. */
    public static double doseRateOf(Player player, Vec3 at) {
        double w = carriedGammaWatts(player);
        return w > 0 ? DoseModel.pointSourceMsvPerH(w, player.position().add(0, 1, 0).distanceTo(at), TRANSMISSION) : 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("tooltip.bwr.spent_resin", "Radioactive waste from a filter-demineraliser. Handle it as such."));
        double bq = activityBq(stack);
        if (bq > 0) lines.add(Component.literal(String.format(java.util.Locale.ROOT, "%.2e Bq, %.3f W gamma each", bq,
                bq * CoolantChemistry.GAMMA_WATTS_PER_BQ)));
        super.appendHoverText(stack, context, lines, flag);
    }
}
