package dev.bwr.mod.guide.client;

import dev.bwr.mod.gui.client.BwrScreen;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.guide.GuideBook;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * The guide: a chapter list, and pages of wrapped text with optional item icons and an optional small image, with Back,
 * Contents and Next. It is a {@link BwrScreen} like the machine screens, so it has the same window, scaling, tab order,
 * narration and scroll fallback; everything it shows comes from a {@link GuideBook}, which comes from JSON files.
 *
 * <p>Left and Right turn pages. The place is remembered ({@link GuideProgress}) each time a page is shown, and the
 * book opens there next time. Next on the last page of a chapter goes on to the next chapter, Back the other way.
 */
public final class GuideScreen extends BwrScreen<GuideMenu> {
    private final GuideBook.Book book;
    private int chapter;
    private int page;
    private Layout.ScrollArea textArea;

    /** {@code chapter} below zero shows the chapter list. */
    public GuideScreen(GuideBook.Book book, Inventory inventory, int chapter, int page) {
        super(new GuideMenu(-1, inventory), inventory, Component.translatable("gui.bwr.guide.title"), 440, 250);
        this.book = book;
        this.chapter = Math.min(chapter, book.chapters().size() - 1);
        this.page = this.chapter < 0 ? 0 : Math.max(0, Math.min(page, book.chapters().get(this.chapter).pages().size() - 1));
        remember();
    }

    private static String text(Component component) {
        return component.getString();
    }

    /** Which chapter is open (-1 for the chapter list) and which page of it. */
    public int chapter() {
        return chapter;
    }

    public int page() {
        return page;
    }

    /** True when the open page's text is longer than the room for it (so that it scrolls); the GUI check requires false. */
    public boolean textOverflows() {
        return textArea != null && textArea.overflows();
    }

    private void remember() {
        if (chapter >= 0) {
            GuideProgress.save(book.chapters().get(chapter).id(), page);
        }
    }

    private void show(int newChapter, int newPage) {
        chapter = newChapter;
        page = newPage;
        remember();
        rebuildWidgets();
    }

    private void turn(int delta) {
        if (chapter < 0) {
            return;
        }
        int c = chapter;
        int p = page + delta;
        if (p < 0) {
            if (c == 0) {
                return;
            }
            c--;
            p = book.chapters().get(c).pages().size() - 1;
        } else if (p >= book.chapters().get(c).pages().size()) {
            if (c == book.chapters().size() - 1) {
                return;
            }
            c++;
            p = 0;
        }
        show(c, p);
    }

    private boolean atStart() {
        return chapter == 0 && page == 0;
    }

    private boolean atEnd() {
        return chapter == book.chapters().size() - 1 && page == book.chapters().get(chapter).pages().size() - 1;
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    @Override
    protected UiElement buildUi() {
        textArea = null;
        return chapter < 0 ? listUi() : pageUi();
    }

    private UiElement listUi() {
        List<UiElement> kids = new ArrayList<>();
        kids.add(Layout.note(text(Component.translatable("gui.bwr.guide.intro"))).body());
        GuideProgress.Place place = GuideProgress.load(book);
        if (place != null) {
            int c = book.indexOf(place.chapter());
            String where = book.chapters().get(c).title();
            BwrButton resume = BwrButton.primary(text(Component.translatable("gui.bwr.guide.continue", where, place.page() + 1)),
                    text(Component.translatable("gui.bwr.guide.continue_tip")), b -> show(c, place.page()));
            kids.add(Layout.controls(0).add(resume, 1));
        }
        List<UiElement> left = new ArrayList<>();
        List<UiElement> right = new ArrayList<>();
        int n = book.chapters().size();
        for (int i = 0; i < n; i++) {
            GuideBook.Chapter c = book.chapters().get(i);
            int index = i;
            BwrButton button = BwrButton.secondary(c.title(), text(Component.translatable("gui.bwr.guide.chapter_tip", i + 1, c.pages().size())), b -> show(index, 0));
            (i < (n + 1) / 2 ? left : right).add(Layout.controls(0).add(button, 1));
        }
        kids.add(Layout.split(BwrStyle.SP1,
                Layout.column(BwrStyle.SP1, left.toArray(new UiElement[0])),
                Layout.column(BwrStyle.SP1, right.toArray(new UiElement[0]))));
        kids.add(Layout.note(text(Component.translatable("gui.bwr.guide.help"))));
        return Layout.column(BwrStyle.SP2, kids.toArray(new UiElement[0]));
    }

    private UiElement pageUi() {
        GuideBook.Chapter c = book.chapters().get(chapter);
        GuideBook.Page p = c.pages().get(page);
        List<UiElement> kids = new ArrayList<>();
        kids.add(Layout.line(p::title, Type.SECTION));
        GuideMedia media = new GuideMedia(p);
        if (!media.isEmpty()) {
            kids.add(media);
        }
        List<UiElement> body = new ArrayList<>();
        body.add(Layout.note(p::text).body());
        if (page == c.pages().size() - 1) {
            body.add(Layout.note(text(Component.translatable("gui.bwr.guide.more", String.join(", ", c.docs())))));
        }
        textArea = Layout.scrollArea(40, Layout.column(BwrStyle.SP2, body.toArray(new UiElement[0])));
        kids.add(textArea);

        BwrButton back = BwrButton.secondary(text(Component.translatable("gui.bwr.guide.back")), text(Component.translatable("gui.bwr.guide.back_tip")), b -> turn(-1));
        BwrButton contents = BwrButton.secondary(text(Component.translatable("gui.bwr.guide.contents")), text(Component.translatable("gui.bwr.guide.contents_tip")), b -> show(-1, 0));
        BwrButton next = BwrButton.primary(text(Component.translatable("gui.bwr.guide.next")), text(Component.translatable("gui.bwr.guide.next_tip")), b -> turn(1));
        back.active = !atStart();
        next.active = !atEnd();
        kids.add(Layout.controls(BwrStyle.SP1).add(back, 1).add(contents, 1).add(next, 1));
        return Layout.column(BwrStyle.SP1, kids.toArray(new UiElement[0]));
    }

    /** On a page, where you are, at the right of the title bar: the chapter and the page. */
    @Override
    protected UiElement buildTitleItems() {
        if (chapter < 0) {
            return null;
        }
        GuideBook.Chapter c = book.chapters().get(chapter);
        String position = text(Component.translatable("gui.bwr.guide.position", c.title(), page + 1, c.pages().size()));
        return Layout.titleItems(Layout.chip(() -> Status.NONE, () -> position));
    }

    // ---------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (chapter >= 0 && (key == 263 || key == 262)) {
            turn(key == 263 ? -1 : 1);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** The guide has no slots: a click is never an inventory click, and closing it needs no word with the server. */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
