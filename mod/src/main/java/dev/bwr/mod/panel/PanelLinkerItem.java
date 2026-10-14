package dev.bwr.mod.panel;

import dev.bwr.mod.fuel.BwrDataComponents;
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

import java.util.List;

/**
 * The panel linker (spec section 7). Sneak-right-click a plant device to store it; right-click a panel
 * component to open the binding screen listing that device's signals and commands (the client input hook
 * and {@link PanelBlock} do that part). Sneak-right-click the air to forget the device. Hovering a bound
 * component outlines the linked device (client).
 *
 * <p>The stored device is a {@link GlobalPos} data component, so it survives in chests and across
 * restarts. Whether it can actually be bound (same dimension, within {@link PanelConfig#linkRange()}, still
 * there) is decided when a binding is made, by {@code WiringOps}.
 */
public final class PanelLinkerItem extends Item {
    public PanelLinkerItem(Properties properties) {
        super(properties);
    }

    /** The device stored on this stack, or null. */
    public static GlobalPos linked(ItemStack stack) {
        return stack.get(BwrDataComponents.LINKED_DEVICE.get());
    }

    /** The linker in either hand, or empty. */
    public static ItemStack held(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof PanelLinkerItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if(player!=null&&!player.isShiftKeyDown()&&level.getBlockEntity(pos) instanceof dev.bwr.mod.grid.GridTieBlockEntity tie){
            if(level.isClientSide())return InteractionResult.SUCCESS;
            if(!(player instanceof ServerPlayer sender))return InteractionResult.PASS;
            GlobalPos selected=linked(context.getItemInHand());boolean success=false;String action="refused";
            if(player.getAbilities().mayBuild&&dev.bwr.mod.world.AssemblyAccess.permitted(level,player,pos)
                    &&selected!=null&&selected.dimension().equals(level.dimension())&&level.isLoaded(selected.pos())
                    &&selected.pos().distSqr(pos)<=(double)PanelConfig.linkRange()*PanelConfig.linkRange()
                    &&dev.bwr.mod.world.AssemblyAccess.permitted(level,player,selected.pos())){
                var device=dev.bwr.mod.signal.DeviceResolver.resolve(level,selected.pos());
                if(device instanceof dev.bwr.mod.power.PowerModuleBlockEntity gen&&gen.block().generator()){
                    success=tie.bindGenerator(gen.getBlockPos());if(success)action="generator";
                }else if(device instanceof dev.bwr.mod.grid.FeMeterBlockEntity meter){
                    success=tie.toggleMeter(meter);if(success)action="meter";
                }
            }
            sender.displayClientMessage(Component.translatable("message.bwr.grid_linker."+action),true);
            return InteractionResult.CONSUME;
        }
        if (player == null || !player.isShiftKeyDown() || level.getBlockState(pos).getBlock() instanceof PanelBlock) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sender)) {
            return InteractionResult.PASS;
        }
        if (SignalProviders.at(level, pos) == null) {
            sender.displayClientMessage(Component.translatable("message.bwr.panel_linker.no_signals"), true);
            return InteractionResult.CONSUME;
        }
        context.getItemInHand().set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), pos.immutable()));
        sender.displayClientMessage(Component.translatable("message.bwr.panel_linker.linked",
                level.getBlockState(pos).getBlock().getName(), pos.getX(), pos.getY(), pos.getZ()), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && linked(stack) != null) {
            if (!level.isClientSide()) {
                stack.remove(BwrDataComponents.LINKED_DEVICE.get());
                player.displayClientMessage(Component.translatable("message.bwr.panel_linker.cleared"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos device = linked(stack);
        if (device == null) {
            tooltip.add(Component.translatable("tooltip.bwr.panel_linker.empty").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos pos = device.pos();
            tooltip.add(Component.translatable("tooltip.bwr.panel_linker.device", pos.getX(), pos.getY(), pos.getZ(),
                    device.dimension().location().toString()).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("tooltip.bwr.panel_linker.store").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.panel_linker.bind").withStyle(ChatFormatting.GRAY));
    }
}
