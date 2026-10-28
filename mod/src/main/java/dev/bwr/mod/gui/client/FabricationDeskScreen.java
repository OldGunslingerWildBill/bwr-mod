package dev.bwr.mod.gui.client;

import dev.bwr.mod.fabrication.FabricationCategory;
import dev.bwr.mod.fabrication.FabricationCrafting;
import dev.bwr.mod.fabrication.FabricationCrafting.Outcome;
import dev.bwr.mod.fabrication.FabricationRecipe;
import dev.bwr.mod.gui.FabricationDeskMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The fabrication desk (FABRICATION-DESK.md), laid out like the gun smith table of TaCZ: tabs for the categories of part, a
 * scrollable list of the parts in the tab, and for the selected part a preview, its material list with what the player has and
 * what it needs, and a Craft button that is disabled while a material is short. Shift-click crafts up to
 * {@value FabricationRecipe#MAX_BATCH} at once.
 *
 * <p>The recipes are the client's copy of the server's loaded {@code bwr:fabrication} recipes, so the list is whatever recipe
 * set the install has (with or without Mekanism). The counts come from the menu's snapshot (see {@link FabricationDeskMenu}).
 * The screen decides nothing: Craft sends the recipe's key and the server checks the materials, the reach and the permission.
 */
public final class FabricationDeskScreen extends BwrScreen<FabricationDeskMenu> {
    private static final int LIST_ROW = 20;
    private static final int MATERIAL_ROW = 17;
    private static final int PREVIEW = 52;
    private static final FabricationCategory[] CATEGORIES = FabricationCategory.values();

    /** One part: its recipe, what it makes and the name the list shows. */
    public record Part(ResourceLocation id, int key, FabricationRecipe recipe, ItemStack result, String name) {
    }

    private final List<Part> parts = new ArrayList<>();
    private List<Part> shown = List.of();
    private int category;
    private int selected = -1;
    private PartList list;
    private BwrButton craft;

    public FabricationDeskScreen(FabricationDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 224);
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (RecipeHolder<FabricationRecipe> holder : FabricationCrafting.recipes(level.getRecipeManager())) {
                ItemStack result = holder.value().output();
                parts.add(new Part(holder.id(), FabricationCrafting.key(holder.id()), holder.value(), result, result.getHoverName().getString()));
            }
        }
        parts.sort(Comparator.comparing((Part p) -> p.name().toLowerCase(Locale.ROOT)).thenComparing(p -> p.id().toString()));
        // open on the first tab that has a part
        for (int c = 0; c < CATEGORIES.length; c++) {
            if (partsOf(c).size() > 0) {
                category = c;
                break;
            }
        }
        refreshShown();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private List<Part> partsOf(int c) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (p.recipe().category() == CATEGORIES[c]) {
                out.add(p);
            }
        }
        return out;
    }

    private void refreshShown() {
        shown = partsOf(category);
        selected = shown.isEmpty() ? -1 : 0;
        if (list != null) {
            list.scrollToSelection();
        }
    }

    /** The tab and the part in it that are shown (the GUI check opens each state this way). */
    public void select(FabricationCategory tab, int index) {
        category = tab.ordinal();
        refreshShown();
        selected = shown.isEmpty() ? -1 : Math.max(0, Math.min(index, shown.size() - 1));
        if (list != null) {
            list.scrollToSelection();
        }
    }

    /** The part with this result item in the shown tab, or -1. */
    public int indexOf(ResourceLocation resultItem) {
        for (int i = 0; i < shown.size(); i++) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(shown.get(i).result().getItem()).equals(resultItem)) {
                return i;
            }
        }
        return -1;
    }

    public Part selectedPart() {
        return selected >= 0 && selected < shown.size() ? shown.get(selected) : null;
    }

    public List<Part> shownParts() {
        return shown;
    }

    private void choose(int index) {
        if (index >= 0 && index < shown.size()) {
            selected = index;
        }
    }

    // ---- what the numbers say

    private int[] have() {
        Part p = selectedPart();
        return p == null ? new int[0] : p.recipe().held(menu.held);
    }

    private boolean affordable(Part p) {
        return p != null && p.recipe().plan(menu.held, 1) != null;
    }

    /** How many crafts a shift-click would make now. */
    private int batch() {
        Part p = selectedPart();
        if (p == null) {
            return 0;
        }
        ItemStack unit = p.result();
        int perStack = Math.max(1, unit.getMaxStackSize() / Math.max(1, unit.getCount()));
        return p.recipe().affordable(menu.held, Math.min(FabricationRecipe.MAX_BATCH, perStack));
    }

    private void sendCraft() {
        Part p = selectedPart();
        if (p != null) {
            menu.sendCommand(FabricationDeskMenu.CMD_CRAFT, p.key(), hasShiftDown() ? FabricationRecipe.MAX_BATCH : 1);
        }
    }

    // ---- layout

    @Override
    protected UiElement buildUi() {
        String[] labels = new String[CATEGORIES.length];
        String[] tips = new String[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            labels[i] = tr(CATEGORIES[i].labelKey() + ".short");
            tips[i] = tr(CATEGORIES[i].labelKey());
        }
        var tabs = Layout.tabs(labels, tips, () -> category, c -> {
            if (c != category) {
                category = c;
                refreshShown();
            }
        });
        if (list == null) {
            list = new PartList();
        }
        if (craft == null) {
            craft = BwrButton.primary(tr("gui.bwr.fabrication.craft"), tr("gui.bwr.fabrication.craft.tip", FabricationRecipe.MAX_BATCH),
                    b -> sendCraft());
        }
        UiElement previewColumn = new Preview();
        UiElement materials = Layout.stack(Layout.section(tr("gui.bwr.fabrication.materials")), new Materials());
        UiElement detail = Layout.card(() -> selectedPart() == null ? tr("gui.bwr.fabrication.none") : selectedPart().name(),
                Layout.split(BwrStyle.SP2).fixed(previewColumn, PREVIEW).add(materials, 1),
                Layout.flex(),
                Layout.statusLine(this::resultStatus, this::resultText, () -> "").showIf(() -> menu.answered),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP2).add(craft, 1)).compact()
                .status(() -> selectedPart() == null ? Status.NONE : affordable(selectedPart()) ? Status.NORMAL : Status.ALARM,
                        () -> affordable(selectedPart()) ? tr("gui.bwr.fabrication.ready") : tr("gui.bwr.fabrication.short"));
        UiElement listCard = Layout.card("", new ListHost()).compact();
        return Layout.column(BwrStyle.SP1,
                tabs,
                Layout.split(BwrStyle.SP2).add(listCard, 4).add(detail, 6));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int n = batch();
        craft.active = affordable(selectedPart());
        if (hasShiftDown() && n > 1) {
            craft.setMessage(Component.literal(tr("gui.bwr.fabrication.craft.many", n)));
        } else {
            craft.setMessage(Component.literal(tr("gui.bwr.fabrication.craft")));
        }
    }

    // ---- the answer to the last request

    private Status resultStatus() {
        return switch (menu.lastOutcome) {
            case CRAFTED -> menu.lastDropped > 0 ? Status.CAUTION : Status.NORMAL;
            case SHORT, RATE_LIMITED -> Status.CAUTION;
            default -> Status.ALARM;
        };
    }

    private String resultText() {
        Outcome o = menu.lastOutcome;
        Part p = selectedPart();
        return switch (o) {
            case CRAFTED -> menu.lastDropped > 0
                    ? tr("gui.bwr.fabrication.result.crafted_dropped", menu.lastCrafts, menu.lastDropped)
                    : tr("gui.bwr.fabrication.result.crafted", menu.lastCrafts, p == null ? "" : p.name());
            case SHORT -> tr("gui.bwr.fabrication.result.short");
            case TOO_FAR -> tr("gui.bwr.fabrication.result.too_far");
            case NOT_ALLOWED -> tr("gui.bwr.fabrication.result.not_allowed");
            case NO_DESK -> tr("gui.bwr.fabrication.result.no_desk");
            case UNKNOWN_RECIPE -> tr("gui.bwr.fabrication.result.unknown");
            case RATE_LIMITED -> tr("gui.bwr.fabrication.result.rate_limited");
        };
    }

    // ---- draws an item at a whole-number zoom

    private static void drawItem(GuiGraphics g, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) {
            return;
        }
        if (scale == 1.0f) {
            g.renderItem(stack, x, y);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, scale);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** The first item an ingredient accepts (a tag cycles through its items, one a second). */
    private static ItemStack shownItem(SizedIngredient input) {
        ItemStack[] items = input.ingredient().getItems();
        if (items.length == 0) {
            return ItemStack.EMPTY;
        }
        return items[(int) (System.currentTimeMillis() / 1000L % items.length)];
    }

    private static String ingredientName(SizedIngredient input) {
        ItemStack[] items = input.ingredient().getItems();
        return items.length == 0 ? tr("gui.bwr.fabrication.nothing") : items[0].getHoverName().getString();
    }

    // ---- elements

    /** The selected part drawn large in a well, with what one craft makes under it. */
    private final class Preview extends UiElement {
        @Override
        protected int measure(int width) {
            return PREVIEW + 2 + BwrStyle.LINE;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Part p = selectedPart();
            Ui.well(g, x, y, PREVIEW, PREVIEW);
            if (p == null) {
                return;
            }
            drawItem(g, p.result(), x + (PREVIEW - 48) / 2, y + (PREVIEW - 48) / 2, 3.0f);
            String makes = tr("gui.bwr.fabrication.makes", p.result().getCount());
            Ui.text(g, Ui.fit(makes, Type.CAPTION, w), x, y + PREVIEW + 3, Type.CAPTION);
        }

        @Override
        public java.util.List<Component> tooltipAt(int mx, int my) {
            Part p = selectedPart();
            if (p == null || !contains(mx, my) || my > y + PREVIEW) {
                return List.of();
            }
            return List.of(p.result().getHoverName(), Component.literal(tr(CATEGORIES[category].labelKey())));
        }

        @Override
        public void narrate(List<String> out) {
            Part p = selectedPart();
            if (p != null) {
                out.add(tr("gui.bwr.fabrication.makes", p.result().getCount()) + " " + p.name());
            }
        }
    }

    /** The material list: a shape, the item, its name and have / need for each ingredient. */
    private final class Materials extends UiElement {
        @Override
        protected int measure(int width) {
            Part p = selectedPart();
            return p == null ? 0 : p.recipe().inputs().size() * MATERIAL_ROW;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Part p = selectedPart();
            if (p == null) {
                return;
            }
            int[] have = have();
            List<SizedIngredient> inputs = p.recipe().inputs();
            for (int i = 0; i < inputs.size(); i++) {
                SizedIngredient in = inputs.get(i);
                int ry = y + i * MATERIAL_ROW;
                boolean enough = have[i] >= in.count();
                Status st = enough ? Status.NORMAL : Status.ALARM;
                Ui.statusIcon(g, x, ry + (MATERIAL_ROW - BwrStyle.ICON) / 2, st);
                drawItem(g, shownItem(in), x + BwrStyle.ICON + BwrStyle.SP1, ry, 1.0f);
                String count = Math.min(have[i], 9999) + "/" + in.count();
                int cw = Ui.width(count, Type.BODY);
                Ui.text(g, count, x + w - cw, ry + (MATERIAL_ROW - 8) / 2, Type.BODY, st.colour);
                int nameX = x + BwrStyle.ICON + BwrStyle.SP1 + 16 + BwrStyle.SP1;
                String name = ingredientName(in);
                Ui.text(g, Ui.fit(name, Type.BODY, x + w - cw - BwrStyle.SP1 - nameX), nameX, ry + (MATERIAL_ROW - 8) / 2,
                        Type.BODY, enough ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public java.util.List<Component> tooltipAt(int mx, int my) {
            Part p = selectedPart();
            if (p == null || !contains(mx, my)) {
                return List.of();
            }
            int i = (my - y) / MATERIAL_ROW;
            List<SizedIngredient> inputs = p.recipe().inputs();
            if (i < 0 || i >= inputs.size()) {
                return List.of();
            }
            SizedIngredient in = inputs.get(i);
            int have = have()[i];
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(ingredientName(in)));
            tip.add(Component.literal(tr("gui.bwr.fabrication.have_need", have, in.count())));
            ItemStack[] items = in.ingredient().getItems();
            if (items.length > 1) {
                tip.add(Component.literal(tr("gui.bwr.fabrication.any_of", items.length)));
            }
            return tip;
        }

        @Override
        public void narrate(List<String> out) {
            Part p = selectedPart();
            if (p == null) {
                return;
            }
            int[] have = have();
            List<SizedIngredient> inputs = p.recipe().inputs();
            for (int i = 0; i < inputs.size(); i++) {
                out.add(ingredientName(inputs.get(i)) + " " + tr("gui.bwr.fabrication.have_need", have[i], inputs.get(i).count()));
            }
        }
    }

    /** Hosts the part list widget in the layout and answers its tooltips. */
    private final class ListHost extends UiElement {
        @Override
        protected int measure(int width) {
            return 6 * LIST_ROW;
        }

        @Override
        public boolean flexible() {
            return true;
        }

        @Override
        protected void layout() {
            list.setX(x);
            list.setY(y);
            list.setWidth(w);
            list.setHeight(h);
            list.visible = true;
            list.scrollToSelection();
        }

        @Override
        protected void hide() {
            list.visible = false;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
        }

        @Override
        public void collectWidgets(java.util.function.Consumer<AbstractWidget> out) {
            out.accept(list);
        }

        @Override
        public java.util.List<Component> tooltipAt(int mx, int my) {
            return list.tooltipAt(mx, my);
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            return list.mouseScrolled(mx, my, 0.0, delta);
        }

        @Override
        public void narrate(List<String> out) {
            out.add(tr("gui.bwr.fabrication.list", shown.size()));
        }
    }

    /**
     * The scrollable list of the parts in the tab: an item, the name (two lines at most) and, for a part the player can make
     * now, a green circle. Click or Up and Down select; the wheel and Page Up and Down scroll.
     */
    private final class PartList extends AbstractWidget {
        private int offset;

        private final net.minecraft.client.gui.components.Tooltip help =
                net.minecraft.client.gui.components.Tooltip.create(Component.literal(tr("gui.bwr.fabrication.list.tip")));

        PartList() {
            super(0, 0, 100, 100, Component.literal(""));
        }

        /** What the list is, for the screen's accessibility check; the rows have their own tooltips (see {@link #tooltipAt}). */
        @Override
        public net.minecraft.client.gui.components.Tooltip getTooltip() {
            return help;
        }

        private int maxOffset() {
            return Math.max(0, shown.size() * LIST_ROW - height);
        }

        void scrollToSelection() {
            if (selected < 0) {
                offset = 0;
                return;
            }
            int top = selected * LIST_ROW;
            if (top < offset) {
                offset = top;
            } else if (top + LIST_ROW > offset + height) {
                offset = top + LIST_ROW - height;
            }
            offset = Math.max(0, Math.min(offset, maxOffset()));
        }

        private int rowAt(double mx, double my) {
            if (mx < getX() || mx >= getX() + width || my < getY() || my >= getY() + height) {
                return -1;
            }
            int i = (int) ((my - getY() + offset) / LIST_ROW);
            return i >= 0 && i < shown.size() ? i : -1;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            Ui.enableScissor(g, getX(), getY(), getX() + width, getY() + height);
            Ui.clipped(true);
            boolean bar = maxOffset() > 0;
            int rowW = width - (bar ? 5 : 0);
            int hover = rowAt(mx, my);
            for (int i = 0; i < shown.size(); i++) {
                int ry = getY() + i * LIST_ROW - offset;
                if (ry + LIST_ROW < getY() || ry > getY() + height) {
                    continue;
                }
                Part p = shown.get(i);
                boolean on = i == selected;
                g.fill(getX(), ry, getX() + rowW, ry + LIST_ROW - 1, on ? BwrStyle.RAISED : i == hover ? BwrStyle.RAISED_HOVER : BwrStyle.SURFACE);
                if (on) {
                    g.fill(getX(), ry, getX() + 2, ry + LIST_ROW - 1, BwrStyle.ACCENT);
                }
                drawItem(g, p.result(), getX() + 4, ry + 1, 1.0f);
                boolean can = affordable(p);
                int textX = getX() + 4 + 16 + BwrStyle.SP1;
                int textW = rowW - (textX - getX()) - (can ? BwrStyle.ICON + BwrStyle.SP1 + 2 : 2);
                List<String> lines = Ui.wrapStrings(p.name(), textW, Type.BODY, 2);
                int ty = ry + (LIST_ROW - 1 - lines.size() * 9) / 2 + 1;
                for (int l = 0; l < lines.size(); l++) {
                    Ui.text(g, lines.get(l), textX, ty + l * 9, Type.BODY, on ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_SECONDARY);
                }
                if (can) {
                    Ui.statusIcon(g, getX() + rowW - BwrStyle.ICON - 2, ry + (LIST_ROW - 1 - BwrStyle.ICON) / 2, Status.NORMAL);
                }
            }
            if (shown.isEmpty()) {
                Ui.text(g, Ui.fit(tr("gui.bwr.fabrication.empty"), Type.CAPTION, width - 4), getX() + 2, getY() + 4, Type.CAPTION);
            }
            Ui.clipped(false);
            Ui.disableScissor(g);
            if (bar) {
                int bx = getX() + width - 3;
                g.fill(bx, getY(), bx + 3, getY() + height, BwrStyle.INSET);
                int thumb = Math.max(12, height * height / (height + maxOffset()));
                int top = getY() + (int) ((long) (height - thumb) * offset / Math.max(1, maxOffset()));
                g.fill(bx, top, bx + 3, top + thumb, BwrStyle.BORDER_STRONG);
            }
            if (isFocused()) {
                Ui.focusRing(g, getX(), getY(), width, height);
            }
        }

        List<Component> tooltipAt(int mx, int my) {
            int i = rowAt(mx, my);
            if (i < 0) {
                return List.of();
            }
            Part p = shown.get(i);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(p.name()));
            if (affordable(p)) {
                tip.add(Component.literal(tr("gui.bwr.fabrication.can_make")));
            }
            return tip;
        }

        @Override
        public void onClick(double mx, double my) {
            choose(rowAt(mx, my));
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            if (!isMouseOver(mx, my) || maxOffset() == 0 || dy == 0.0) {
                return false;
            }
            offset = Math.max(0, Math.min(maxOffset(), offset - (int) Math.round(dy * LIST_ROW)));
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            if (!isFocused() || shown.isEmpty()) {
                return false;
            }
            int target = switch (key) {
                case 265 -> selected - 1;
                case 264 -> selected + 1;
                case 268 -> 0;
                case 269 -> shown.size() - 1;
                default -> -2;
            };
            if (target == -2) {
                return false;
            }
            choose(Math.max(0, Math.min(shown.size() - 1, target)));
            scrollToSelection();
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            Part p = selectedPart();
            out.add(NarratedElementType.TITLE, Component.literal(tr("gui.bwr.fabrication.list", shown.size())
                    + (p == null ? "" : ", " + p.name())));
        }
    }
}
