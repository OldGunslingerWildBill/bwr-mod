package dev.bwr.mod.guide.client;

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
 * The pictures of a page: its item icons (16 px, each on a small recessed square with the item's name as a tooltip) in
 * rows that wrap at the width of a book column, and its optional image after them (on the last row of icons when it
 * fits, else on a row of its own). An icon whose id is not registered (a mod that is not installed) is left out.
 *
 * <p>Everything here is in design pixels of the book; the screen draws it under a whole-number zoom, so it takes and
 * returns design coordinates only.
 */
final class GuideMedia {
    private static final int ICON = 16;
    private static final int STEP = 20;
    private static final int GAP = 4;

    private final List<ItemStack> icons = new ArrayList<>();
    private final GuideBook.Image image;
    private final ResourceLocation texture;
    private int x;
    private int y;
    private int width;
    private int perRow = 1;
    private int imageX;
    private int imageY;
    private int height;

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

    /** Lays the pictures out with their top-left at {@code (x, y)} inside {@code width}; returns the height used. */
    int place(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.perRow = Math.max(1, (width + 2) / STEP);
        int rows = (icons.size() + perRow - 1) / perRow;
        int h = rows * STEP;
        if (texture != null) {
            int size = image.size();
            int lastRowUsed = icons.isEmpty() ? 0 : ((icons.size() - 1) % perRow + 1) * STEP;
            if (!icons.isEmpty() && lastRowUsed + GAP + size <= width) {
                imageX = x + lastRowUsed + GAP;
                imageY = y + (rows - 1) * STEP;
                h = Math.max(h, (rows - 1) * STEP + size);
            } else {
                imageX = x;
                imageY = y + h;
                h += size;
            }
        }
        this.height = h;
        return h;
    }

    int height() {
        return height;
    }

    private int iconX(int i) {
        return x + (i % perRow) * STEP;
    }

    private int iconY(int i) {
        return y + (i / perRow) * STEP;
    }

    /** Draws the pictures; the pose is already the book's design space. */
    void render(GuiGraphics g) {
        for (int i = 0; i < icons.size(); i++) {
            int ix = iconX(i);
            int iy = iconY(i);
            g.fill(ix - 1, iy - 1, ix + ICON + 1, iy + ICON + 1, 0x30301C08);
            g.fill(ix - 1, iy - 1, ix + ICON + 1, iy, 0x50301C08);
            g.fill(ix - 1, iy - 1, ix, iy + ICON + 1, 0x50301C08);
            g.renderItem(icons.get(i), ix, iy);
        }
        if (texture != null) {
            g.blit(texture, imageX, imageY, image.size(), image.size(), 0, 0, image.textureSize(), image.textureSize(),
                    image.textureSize(), image.textureSize());
        }
    }

    /** The name of the icon under the pointer, in design coordinates, or an empty list. */
    List<Component> tooltipAt(int mouseX, int mouseY) {
        for (int i = 0; i < icons.size(); i++) {
            int ix = iconX(i);
            int iy = iconY(i);
            if (mouseX >= ix && mouseX < ix + ICON && mouseY >= iy && mouseY < iy + ICON) {
                return List.of(icons.get(i).getHoverName());
            }
        }
        return List.of();
    }

    void narrate(List<String> out) {
        if (!icons.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ItemStack s : icons) {
                names.add(s.getHoverName().getString());
            }
            out.add("Shown: " + String.join(", ", names));
        }
    }
}
