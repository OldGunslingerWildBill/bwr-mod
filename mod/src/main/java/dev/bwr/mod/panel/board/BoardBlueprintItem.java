package dev.bwr.mod.panel.board;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The board blueprint: one item for every premade board. The board it places is a data component of the stack (a
 * {@code panel_board} template id; a plain stack places the first board); right-clicking the air steps to the next
 * board (sneak: the one before), and the creative tab holds one stack per board. While it is held and the crosshair is
 * on a block the client draws the board as an outline in the world (green where it fits, red where something is in the
 * way: {@code BoardPreview}), and right-clicking the block places it, with the clicked spot as the front left corner as
 * the operator sees it and the panels facing the player.
 *
 * <p>Creative: free. Survival: the panel blocks, one item per component and the mimic tape its paths need are taken
 * from the inventory ({@link BoardPlacer}); what is missing is listed and nothing is placed. The blueprint is not used
 * up. The board is placed unwired.
 */
public final class BoardBlueprintItem extends Item {
    public BoardBlueprintItem(Properties properties) {
        super(properties);
    }

    /** A blueprint stack for {@code id}. */
    public static ItemStack stackFor(ResourceLocation id) {
        ItemStack stack = new ItemStack(BwrItems.BOARD_BLUEPRINT.get());
        stack.set(BwrDataComponents.BOARD_TEMPLATE.get(), id);
        return stack;
    }

    /** The board the stack places: its stored id if that board exists, else the first board; null when there is none. */
    public static BoardTemplate template(ItemStack stack) {
        ResourceLocation id = stack.get(BwrDataComponents.BOARD_TEMPLATE.get());
        BoardTemplate template = BoardTemplates.get(id);
        if (template == null) {
            List<BoardTemplate> all = BoardTemplates.ordered();
            template = all.isEmpty() ? null : all.get(0);
        }
        return template;
    }

    /** Where the board's origin goes for a click: on the clicked block when it can be replaced, else in front of the face. */
    public static BlockPos originFor(Level level, BlockPos clicked, Direction face, UseOnContext context) {
        return level.getBlockState(clicked).canBeReplaced(new BlockPlaceContext(context)) ? clicked : clicked.relative(face);
    }

    /** The way the panels face for a player looking in {@code horizontal}: toward the player. */
    public static Direction facingFor(Direction horizontal) {
        return horizontal.getOpposite();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BoardTemplate template = template(stack);
        if (template == null) {
            say(player, BwrText.tr("message.bwr.board.no_boards", "There are no premade boards in this world"));
            return InteractionResult.FAIL;
        }
        BlockPos origin = originFor(level, context.getClickedPos(), context.getClickedFace(), context);
        BoardPlacer.Outcome outcome = BoardPlacer.place((ServerLevel) level, player, template, origin,
                facingFor(context.getHorizontalDirection()));
        say(player, outcome.message(template.title()));
        return outcome.ok() ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }

    /** Right-click in the air: the next board (sneak: the previous one). */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        BoardTemplate current = template(stack);
        BoardTemplate next = BoardTemplates.cycle(current == null ? null : current.id(), player.isShiftKeyDown());
        if (next == null) {
            say(player, BwrText.tr("message.bwr.board.no_boards", "There are no premade boards in this world"));
            return InteractionResultHolder.fail(stack);
        }
        stack.set(BwrDataComponents.BOARD_TEMPLATE.get(), next.id());
        List<BoardTemplate> all = BoardTemplates.ordered();
        say(player, BwrText.tr("message.bwr.board.selected", "Board: %s (%s of %s)", next.title(), all.indexOf(next) + 1, all.size()));
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static void say(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        BoardTemplate template = template(stack);
        return template == null ? super.getName(stack)
                : BwrText.tr("item.bwr.board_blueprint.named", "Board Blueprint: %s", template.title());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BoardTemplate template = template(stack);
        if (template == null) {
            return;
        }
        if (!template.description().isEmpty()) {
            tooltip.add(Component.literal(template.description()).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(BwrText.tr("tooltip.bwr.board.contents", "%s components on %s panel blocks, placed unwired",
                template.components().size(), new BoardPlan(template, BlockPos.ZERO, Direction.NORTH).panelPositions().size())
                .withStyle(ChatFormatting.GRAY));
        if (!template.source().isEmpty()) {
            tooltip.add(Component.literal(template.source()).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(BwrText.tr("tooltip.bwr.board.use", "Right-click a block to place the board there; the outline shows where it goes")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(BwrText.tr("tooltip.bwr.board.cycle", "Right-click the air to pick the next board (sneak: the previous one)")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(BwrText.tr("tooltip.bwr.board.cost", "Creative: free. Survival: uses the panels, parts and mimic tape from your inventory")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
