package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

import java.util.List;

/**
 * The mimic tape (spec 1): a tool for drawing flow lines on control boards. Using it on a panel face works as the
 * mode says (draw, erase, edit); the work happens in {@link MimicOps}, reached from the client input hook by
 * packet and from {@code PanelBlock} by the vanilla right-click. Using it on the air, or on a block that is not a
 * panel, finishes the path being drawn. The mode and the unfinished path are data components of the stack; the
 * stack count is the tape that is left.
 */
public final class MimicTapeItem extends Item {
    public MimicTapeItem(Properties properties) {
        super(properties);
    }

    public static MimicMode mode(ItemStack stack) {
        Integer stored = stack.get(BwrDataComponents.MIMIC_MODE.get());
        return stored == null ? MimicMode.DRAW : MimicMode.byId(stored);
    }

    public static void setMode(ItemStack stack, MimicMode mode) {
        if (mode == MimicMode.DRAW) {
            stack.remove(BwrDataComponents.MIMIC_MODE.get());
        } else {
            stack.set(BwrDataComponents.MIMIC_MODE.get(), mode.ordinal());
        }
    }

    /** The path being drawn with this stack, or null. */
    public static MimicDraft draft(ItemStack stack) {
        return stack.get(BwrDataComponents.MIMIC_DRAFT.get());
    }

    /** The tape in either hand, main hand first, or empty. */
    public static ItemStack held(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof MimicTapeItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A sneak-click on a panel must reach the block: that is how a free-angle point is added. */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return level.getBlockState(pos).getBlock() instanceof PanelBlock;
    }

    /** A block that is not a panel: it finishes the path being drawn. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof PanelBlock) {
            return InteractionResult.PASS;
        }
        if (draft(context.getItemInHand()) == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sender) {
            MimicOps.finish(sender, context.getItemInHand());
        }
        return InteractionResult.CONSUME;
    }

    /** The air: it finishes the path being drawn. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (draft(stack) == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide() && player instanceof ServerPlayer sender) {
            MimicOps.finish(sender, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.bwr.mimic_tape.mode", Component.translatable(mode(stack).translationKey()))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.bwr.mimic_tape.draw").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.mimic_tape.finish").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.mimic_tape.modes").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.mimic_tape.cost").withStyle(ChatFormatting.DARK_GRAY));
    }
}
