package dev.bwr.mod.panel;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.signal.DeviceResolver;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * The Networking Wrench: a second wiring tool beside the Panel Linker, which stays as it is. It keeps the selected
 * device in the same {@code LINKED_DEVICE} component, so {@code WiringOps} accepts either tool.
 *
 * <ul>
 *   <li>Plain right-click on a plant device selects it (the action bar names it and counts its signals); clicking
 *       the same device again deselects it. Sneak-right-click in the air deselects too.</li>
 *   <li>Sneak-right-click on a device opens its Networking screen.</li>
 *   <li>Right-click a panel component with a device selected to open the binding screen (the client input hook and
 *       {@link PanelBlock} do that part, exactly as for the linker).</li>
 * </ul>
 * Nothing here wires, renames or commands anything by itself: every change is a click on a button.
 */
public final class NetworkingWrenchItem extends Item {
    public NetworkingWrenchItem(Properties properties) {
        super(properties);
    }

    /** The device selected on this stack, or null. */
    public static GlobalPos selected(ItemStack stack) {
        return stack.get(BwrDataComponents.LINKED_DEVICE.get());
    }

    /** Whether the block at {@code pos} belongs to a device that reports signals (either side). */
    public static boolean providesSignals(Level level, BlockPos pos) {
        BlockEntity owner = DeviceResolver.resolve(level, pos);
        return owner != null && SignalProviders.of(owner) != null;
    }

    /** Whether two positions are cells of the same device. */
    public static boolean sameDevice(Level level, BlockPos a, BlockPos b) {
        if (a.equals(b)) {
            return true;
        }
        BlockEntity first = DeviceResolver.resolve(level, a);
        return first != null && first == DeviceResolver.resolve(level, b);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null || level.getBlockState(pos).getBlock() instanceof PanelBlock) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return providesSignals(level, pos) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer sender)) {
            return InteractionResult.PASS;
        }
        SignalProvider provider = SignalProviders.at(level, pos);
        if (provider == null) {
            sender.displayClientMessage(Component.translatable("message.bwr.networking_wrench.no_signals"), true);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            dev.bwr.mod.panel.networking.NetworkingOps.openScreen(sender, pos);
            return InteractionResult.CONSUME;
        }
        ItemStack stack = context.getItemInHand();
        GlobalPos current = selected(stack);
        if (current != null && current.dimension().equals(level.dimension()) && sameDevice(level, current.pos(), pos)) {
            stack.remove(BwrDataComponents.LINKED_DEVICE.get());
            sender.displayClientMessage(Component.translatable("message.bwr.networking_wrench.deselected",
                    MachineNames.display(level, pos)), true);
            return InteractionResult.CONSUME;
        }
        stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), pos.immutable()));
        sender.displayClientMessage(Component.translatable("message.bwr.networking_wrench.selected",
                MachineNames.display(level, pos), countSignals(provider)), true);
        return InteractionResult.CONSUME;
    }

    private static int countSignals(SignalProvider provider) {
        return provider.signals().size();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && selected(stack) != null) {
            if (!level.isClientSide()) {
                stack.remove(BwrDataComponents.LINKED_DEVICE.get());
                player.displayClientMessage(Component.translatable("message.bwr.networking_wrench.cleared"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos device = selected(stack);
        if (device == null) {
            tooltip.add(Component.translatable("tooltip.bwr.networking_wrench.empty").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos pos = device.pos();
            tooltip.add(Component.translatable("tooltip.bwr.panel_linker.device", pos.getX(), pos.getY(), pos.getZ(),
                    device.dimension().location().toString()).withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("tooltip.bwr.networking_wrench.select").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.networking_wrench.screen").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.networking_wrench.bind").withStyle(ChatFormatting.GRAY));
    }
}
