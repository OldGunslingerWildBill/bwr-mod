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
 *
 * <p>An item may be limited to some of its type's footprints (the Yokogawa DX1000 and DX2000 are two items of the
 * paperless recorder, and the size picks the make): it starts at the first of them and cycles only through them.
 */
public final class ComponentItem extends Item {
    private final ResourceLocation typeId;
    /** The footprint indices this item may use, the first being where a new stack starts; null for all of them. */
    private final int[] variants;

    public ComponentItem(Properties properties, ResourceLocation typeId) {
        this(properties, typeId, null);
    }

    public ComponentItem(Properties properties, ResourceLocation typeId, int[] variants) {
        super(properties);
        this.typeId = typeId;
        this.variants = variants == null || variants.length == 0 ? null : variants.clone();
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
        int[] allowed = stack.getItem() instanceof ComponentItem item ? item.variants : null;
        if (allowed != null) {
            if (stored != null) {
                for (int index : allowed) {
                    if (index == stored) {
                        return Math.max(0, Math.min(count - 1, index));
                    }
                }
            }
            return Math.max(0, Math.min(count - 1, allowed[0]));
        }
        if (stored != null) return Math.max(0, Math.min(count - 1, stored));
        // Only new, unconfigured stacks take a default. Saved size indices and placed component dimensions
        // remain authoritative. A type with real sizes starts at the first of them; the others at the size their
        // baked model reads best at.
        if (type.realDefaultIndex() >= 0) {
            return type.realDefaultIndex();
        }
        String path = type.id().getPath();
        String style = PanelStyles.newStyle(path);
        return Math.max(0, Math.min(count - 1,
                PanelStyles.preferredFootprint(path, style, PanelStyles.defaultVariant(path, style))));
    }

    /** The footprint this stack places, or null when the stack is not a component item. */
    public static Footprint footprint(ItemStack stack) {
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return null;
        }
        return item.type().footprints().get(variant(stack, item.type()));
    }

    /** Server: moves the stack to the next larger footprint of its type (the smallest after the largest) and says so on the action bar. */
    public static void cycle(ItemStack stack, Player player) {
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return;
        }
        ComponentType type = item.type();
        int current = variant(stack, type);
        int next = nextVariant(item, type, current);
        stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), next);
        announce(player, type, next);
    }

    /** The size after {@code current} in the order smallest to largest, wrapping; limited to this item's sizes. */
    private static int nextVariant(ComponentItem item, ComponentType type, int current) {
        int[] order = type.sizeOrder();
        int at = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == current) {
                at = i;
            }
        }
        for (int step = 1; step <= order.length; step++) {
            int candidate = order[(at + step) % order.length];
            if (item.allows(candidate)) {
                return candidate;
            }
        }
        return current;
    }

    /** The footprint indices this item offers, smallest to biggest (what the size picker lists). */
    public int[] sizes() {
        return java.util.Arrays.stream(type().sizeOrder()).filter(this::allows).toArray();
    }

    /** Whether this item may be set to footprint index {@code index} (a limited item, like a DX1000, only to its own). */
    private boolean allows(int index) {
        if (variants == null) {
            return true;
        }
        for (int allowed : variants) {
            if (allowed == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * Server: sets the stack to footprint index {@code index} (the size picker). Refused, changing nothing, when the
     * stack is not a component item, the index is not one of its type's sizes, or this item may not use it.
     */
    public static boolean select(ItemStack stack, Player player, int index) {
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return false;
        }
        ComponentType type = item.type();
        if (index < 0 || index >= type.footprints().size() || !item.allows(index)) {
            return false;
        }
        stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), index);
        announce(player, type, index);
        return true;
    }

    private static void announce(Player player, ComponentType type, int index) {
        Footprint footprint = type.footprints().get(index);
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
        if (type == null || type.footprints().size() < 2 || (variants != null && variants.length < 2)) {
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
            if (type().footprints().size() > 1 && (variants == null || variants.length > 1)) {
                tooltip.add(Component.translatable("tooltip.bwr.panel.footprint_hint").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
