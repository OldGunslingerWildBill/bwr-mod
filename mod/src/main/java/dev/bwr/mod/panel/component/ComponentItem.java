package dev.bwr.mod.panel.component;

import dev.bwr.mod.fuel.BwrDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The item form of a {@link ComponentType}. Placing it is handled by the panel
 * block and the client input hook (a component item never places like a
 * block); this class only knows its type and which of the type's footprints the
 * stack is set to. Right-click in the air cycles the footprint.
 */
public final class ComponentItem extends Item {
    private final ResourceLocation typeId;

    public ComponentItem(Properties properties, ResourceLocation typeId) {
        super(properties);
        this.typeId = typeId;
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    public ComponentType type() {
        return ComponentTypes.get(typeId);
    }

    public static int variant(ItemStack stack, ComponentType type) {
        Integer stored = stack.get(BwrDataComponents.COMPONENT_VARIANT.get());
        int count = type.footprints().size();
        return stored == null ? 0 : Math.max(0, Math.min(count - 1, stored));
    }

    /** The footprint this stack places, or null when the stack is not a component item. */
    public static Footprint footprint(ItemStack stack) {
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return null;
        }
        return item.type().footprints().get(variant(stack, item.type()));
    }

    /** Server: moves the stack to the next footprint of its type and says so on the action bar. */
    public static void cycle(ItemStack stack, Player player) {
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return;
        }
        ComponentType type = item.type();
        int next = (variant(stack, type) + 1) % type.footprints().size();
        stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), next);
        Footprint footprint = type.footprints().get(next);
        player.displayClientMessage(Component.translatable("message.bwr.panel.footprint",
                Component.translatable(type.translationKey()), footprint.w(), footprint.h()), true);
    }

    /** Sneak-clicking a panel with a component still places it. */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.level.LevelReader level,
                                      net.minecraft.core.BlockPos pos, Player player) {
        return level.getBlockState(pos).getBlock() instanceof dev.bwr.mod.panel.PanelBlock;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ComponentType type = type();
        if (type == null || type.footprints().size() < 2) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide()) {
            cycle(stack, player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Footprint footprint = footprint(stack);
        if (footprint != null) {
            tooltip.add(Component.translatable("tooltip.bwr.panel.footprint", footprint.w(), footprint.h())
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.bwr.panel.footprint_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
