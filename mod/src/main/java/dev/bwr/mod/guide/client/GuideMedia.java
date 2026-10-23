package dev.bwr.mod.guide.client;

import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.guide.GuideBook;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip of pictures above a page's text: its item icons (16 px, each with the item's name as a tooltip) and, to the
 * right of them, its optional image. An icon whose id is not registered (a mod that is not installed) is left out.
 */
final class GuideMedia extends UiElement {
    private static final int ICON = 16;
    private static final int STEP = 20;

    private final List<ItemStack> icons = new ArrayList<>();
    private final GuideBook.Image image;
    private final ResourceLocation texture;

    GuideMedia(GuideBook.Page page) {
        for (String id : page.icons()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
                Item item = BuiltInRegistries.ITEM.get(key);
                icons.add(new ItemStack(item));
            }
        }
        this.image = page.image();
        this.texture = image == null ? null : ResourceLocation.tryParse(image.texture());
    }

    boolean isEmpty() {
        return icons.isEmpty() && texture == null;
    }

    @Override
    protected int measure(int width) {
        return Math.max(icons.isEmpty() ? 0 : ICON + 2, image == null ? 0 : image.size());
    }

    private int iconsRight() {
        return x + icons.size() * STEP;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        int iconY = y + (h - ICON) / 2;
        for (int i = 0; i < icons.size(); i++) {
            int ix = x + i * STEP;
            if (ix + ICON > x + w) {
                break;
            }
            g.renderItem(icons.get(i), ix, iconY);
        }
        if (texture != null) {
            int ix = Math.min(iconsRight() + BwrStyle.SP2, x + w - image.size());
            g.blit(texture, ix, y, image.size(), image.size(), 0, 0, image.textureSize(), image.textureSize(),
                    image.textureSize(), image.textureSize());
        }
    }

    @Override
    public List<Component> tooltipAt(int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return List.of();
        }
        int iconY = y + (h - ICON) / 2;
        for (int i = 0; i < icons.size(); i++) {
            int ix = x + i * STEP;
            if (mouseX >= ix && mouseX < ix + ICON && mouseY >= iconY && mouseY < iconY + ICON) {
                return List.of(icons.get(i).getHoverName());
            }
        }
        return List.of();
    }

    @Override
    public void narrate(List<String> out) {
        if (!icons.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ItemStack s : icons) {
                names.add(s.getHoverName().getString());
            }
            out.add("Shown: " + String.join(", ", names));
        }
    }
}
