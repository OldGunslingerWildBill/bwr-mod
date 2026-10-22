package dev.bwr.mod.radiation;

import dev.bwr.core.radiation.DoseModel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The handheld survey meter and dosimeter.
 *
 * <ul>
 *   <li><b>Held</b> (either hand): the server samples the dose rate at the holder four times a second and sends it to
 *       that player ({@link RadiationTracker}); the client shows it on the HUD and clicks like a Geiger counter.</li>
 *   <li><b>Carried</b> (anywhere in the inventory): the holder's personal dose accumulates, in mSv, stored on the player.</li>
 *   <li><b>Right-click</b>: the reading and the dose on the action bar. <b>Sneak-right-click</b>: resets the dose.</li>
 * </ul>
 * The meter only reads {@link RadiationField}; it changes nothing in the plant and nothing acts on its reading.
 */
public final class SurveyMeterItem extends Item {
    public SurveyMeterItem(Properties properties) {
        super(properties);
    }

    /** Whether a stack is a survey meter. */
    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof SurveyMeterItem;
    }

    /** A meter in either hand. */
    public static boolean heldBy(Player player) {
        return is(player.getMainHandItem()) || is(player.getOffhandItem());
    }

    /** A meter anywhere in the inventory (hotbar, main, armour slots and off hand). */
    public static boolean carriedBy(Player player) {
        return player.getInventory().contains(SurveyMeterItem::is);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer sender)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (player.isShiftKeyDown()) {
            double was = PlayerDoses.reset(player);
            sender.displayClientMessage(Component.translatable("message.bwr.survey_meter.reset", DoseModel.formatDose(was)), true);
        } else {
            double rate = RadiationTracker.read(sender);
            sender.displayClientMessage(Component.translatable("message.bwr.survey_meter.reading",
                    DoseModel.formatRate(rate), DoseModel.formatDose(PlayerDoses.get(player))), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.bwr.survey_meter.hold").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.survey_meter.carry").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.survey_meter.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.survey_meter.units").withStyle(ChatFormatting.DARK_GRAY));
    }
}
