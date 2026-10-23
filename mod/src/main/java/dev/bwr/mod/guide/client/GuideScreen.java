package dev.bwr.mod.guide.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.guide.GuideBook;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The guide as an open book: a two-page parchment spread with dark ink, a spine, page edges, page numbers at the foot,
 * page-turn arrows in the bottom corners (with the vanilla page-turn sound) and a red ribbon that goes back to the
 * contents. The first spread is the title page with the contents (each chapter a link with its page number); after it
 * every guide page is one spread: its heading on the left page, its text flowing from the left page to the right one,
 * and its icons and image at the top of the right page. The arrows therefore turn by spread, and Next on the last
 * page of a chapter goes on to the next chapter.
 *
 * <p>The book has a fixed design size ({@link #BW} x {@link #BH} pixels, the size of its texture) and is drawn whole-number
 * times larger when the window has room ({@link #zoom}), so a page holds exactly the same text at every GUI scale and
 * at the smallest window the game allows. All layout below is in design pixels; {@code sx} and {@code sy} turn them
 * into screen pixels. A page whose text does not fit the two columns is a fault ({@link #textOverflows}); the GUI
 * check requires every page of the real book to fit.
 *
 * <p>It is a client-only screen with no slots (the {@link GuideMenu} stands in only so the screen has a container to
 * stand on). Left and Right (and Page Up and Down) turn pages, Home goes to the contents and End to the last page. The
 * place is remembered ({@link GuideProgress}) each time a page is shown, and the book opens there next time; the
 * contents offer to continue from it.
 */
public final class GuideScreen extends AbstractContainerScreen<GuideMenu> {
    /** The size of the open book, in design pixels: the first 316 x 232 of {@code textures/gui/guide_book.png}. */
    static final int BW = 316;
    static final int BH = 232;
    private static final int TEX_W = 512;
    private static final int TEX_H = 256;
    private static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/guide_book.png");

    /** The two text columns: x of each, and their width. */
    private static final int LEFT_X = 17;
    private static final int RIGHT_X = 167;
    private static final int COL_W = 132;
    private static final int HEAD_Y = 11;
    private static final int TITLE_Y = 21;
    private static final int RULE_Y = 32;
    private static final int BODY_TOP = 37;
    private static final int BODY_BOTTOM = 202;
    private static final int LINE = 9;
    private static final int BLANK = 5;
    private static final int FOOT_Y = 207;
    private static final int ARROW_W = 23;
    private static final int ARROW_H = 13;
    private static final int ARROW_Y = 204;
    private static final int BACK_X = 14;
    private static final int NEXT_X = BW - 14 - ARROW_W;
    private static final int RIBBON_X = 286;
    private static final int RIBBON_Y = 4;
    private static final int RIBBON_W = 13;
    private static final int RIBBON_H = 30;
    /** Room kept under the book for its shadow, and at the sides, when the window is large enough to matter. */
    private static final int WINDOW_MARGIN = 2;

    private static final int INK = 0xFF2A1B0E;
    private static final int HEADING = 0xFF1B1008;
    private static final int MUTED = 0xFF6B5636;
    private static final int RULE = 0xFF9A8458;
    private static final int LINK = 0xFF7A1E14;
    private static final int HOVER_FILL = 0x38805A20;
    private static final int BOX_FILL = 0x26402810;

    private enum Kind {
        BODY, MUTED, HEAD, RULE
    }

    /** One run of text (or a rule) at a design position; {@code align} is 0 left, 1 centred, 2 right inside {@code w}. */
    private record Mark(Kind kind, String shown, int x, int y, int w, int align) {
    }

    /** A text that was cut with an ellipsis: its design rectangle and the whole text for a tooltip. */
    private record Cut(int x, int y, int w, String full) {
    }

    private record Line(String text, boolean muted) {
        int height() {
            return text.isEmpty() ? BLANK : LINE;
        }
    }

    /** A contents row: the chapter's title in one or two lines, and the number of its first page. */
    private record RowSpec(int chapter, int y, int height, List<String> lines, String title, String number) {
    }

    private final GuideBook.Book book;
    /** Every guide page in reading order as {chapter, page}, and where each chapter starts in that order. */
    private final List<int[]> flat = new ArrayList<>();
    private final int[] chapterStart;
    private int chapter;
    private int page;

    private int zoom = 1;
    private boolean overflow;
    private boolean narrated;

    // what the open spread shows, built by prepare()
    private final List<Mark> marks = new ArrayList<>();
    private final List<Cut> cuts = new ArrayList<>();
    private final List<RowSpec> rows = new ArrayList<>();
    private GuideMedia media;
    private boolean titleIcon;
    private int continueY;
    private int continueHeight;
    private String continueLabel;
    private GuideProgress.Place place;
    private String spokenPage = "";
    private String note = "";

    /** {@code chapter} below zero shows the contents. */
    public GuideScreen(GuideBook.Book book, Inventory inventory, int chapter, int page) {
        super(new GuideMenu(-1, inventory), inventory, Component.translatable("gui.bwr.guide.title"));
        this.book = book;
        this.chapterStart = new int[book.chapters().size()];
        for (int c = 0; c < book.chapters().size(); c++) {
            chapterStart[c] = flat.size();
            for (int p = 0; p < book.chapters().get(c).pages().size(); p++) {
                flat.add(new int[]{c, p});
            }
        }
        this.chapter = Math.min(chapter, book.chapters().size() - 1);
        this.page = this.chapter < 0 ? 0 : Math.max(0, Math.min(page, book.chapters().get(this.chapter).pages().size() - 1));
        remember();
    }

    private static String text(Component component) {
        return component.getString();
    }

    /** Which chapter is open (-1 for the contents) and which page of it. */
    public int chapter() {
        return chapter;
    }

    public int page() {
        return page;
    }

    /** True when the open spread has more text than its two columns hold (the GUI check requires false for every page). */
    public boolean textOverflows() {
        return overflow;
    }

    /** For the GUI check's message: how much text the open page has against the room for it. */
    public String layoutNote() {
        return note;
    }

    private void remember() {
        if (chapter >= 0) {
            GuideProgress.save(book.chapters().get(chapter).id(), page);
        }
    }

    private void show(int newChapter, int newPage) {
        if (newChapter == chapter && newPage == page) {
            return;
        }
        chapter = newChapter;
        page = newPage;
        remember();
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
        }
        narrated = false;
        rebuildWidgets();
    }

    /** Index of the open spread among the guide pages, or -1 on the contents. */
    private int flatIndex() {
        return chapter < 0 ? -1 : chapterStart[chapter] + page;
    }

    private void goTo(int index) {
        if (index < -1 || index >= flat.size()) {
            return;
        }
        if (index < 0) {
            show(-1, 0);
        } else {
            show(flat.get(index)[0], flat.get(index)[1]);
        }
    }

    private void turn(int delta) {
        goTo(flatIndex() + delta);
    }

    /** The number of the left page of the spread for guide page {@code index} (-1 is the contents spread). */
    private static int leftNumber(int index) {
        return 2 * (index + 1) + 1;
    }

    // ---------------------------------------------------------------
    // Layout (design pixels)
    // ---------------------------------------------------------------

    private int sx(int designX) {
        return leftPos + designX * zoom;
    }

    private int sy(int designY) {
        return topPos + designY * zoom;
    }

    @Override
    protected void init() {
        zoom = Math.max(1, Math.min((width - 2 * WINDOW_MARGIN) / BW, (height - 2 * WINDOW_MARGIN) / BH));
        imageWidth = BW * zoom;
        imageHeight = BH * zoom;
        super.init();
        prepare();
        if (chapter < 0) {
            for (RowSpec row : rows) {
                addRenderableWidget(new BookRow(this, row));
            }
            if (place != null) {
                addRenderableWidget(new BookLink(this));
            }
        } else {
            addRenderableWidget(new BookSprite(sx(RIBBON_X), sy(RIBBON_Y), RIBBON_W * zoom, RIBBON_H * zoom, 368, 0, 384, 0, RIBBON_W, RIBBON_H,
                    text(Component.translatable("gui.bwr.guide.contents")), text(Component.translatable("gui.bwr.guide.contents_tip")), b -> show(-1, 0)));
        }
        if (flatIndex() >= 0) {
            addRenderableWidget(new BookSprite(sx(BACK_X), sy(ARROW_Y), ARROW_W * zoom, ARROW_H * zoom, 344, 0, 344, 16, ARROW_W, ARROW_H,
                    text(Component.translatable("gui.bwr.guide.back")), text(Component.translatable("gui.bwr.guide.back_tip")), b -> turn(-1)));
        }
        if (flatIndex() < flat.size() - 1) {
            addRenderableWidget(new BookSprite(sx(NEXT_X), sy(ARROW_Y), ARROW_W * zoom, ARROW_H * zoom, 320, 0, 320, 16, ARROW_W, ARROW_H,
                    text(Component.translatable("gui.bwr.guide.next")), text(Component.translatable("gui.bwr.guide.next_tip")), b -> turn(1)));
        }
    }

    private static Type type(Kind kind) {
        return switch (kind) {
            case HEAD -> Type.TITLE;
            case MUTED -> Type.CAPTION;
            default -> Type.BODY;
        };
    }

    /** One line of text that is cut with an ellipsis (and given a tooltip) when it is wider than {@code w}. */
    private void single(Kind kind, String text, int x, int y, int w, int align) {
        String shown = Ui.fit(text, type(kind), w);
        if (!shown.equals(text)) {
            cuts.add(new Cut(x, y, w, text));
        }
        marks.add(new Mark(kind, shown, x, y, w, align));
    }

    private void rule(int x, int y, int w) {
        marks.add(new Mark(Kind.RULE, "", x, y, w, 0));
    }

    /**
     * {@code s} word-wrapped to {@code width}, and a word wider than that (a long file name) broken after a hyphen or
     * slash, or where it must; the shared wrapper leaves such a word on a line of its own, wider than the column.
     */
    private static List<String> wrap(String s, Type type, int width) {
        List<String> out = new ArrayList<>();
        for (String line : Ui.wrapStrings(s, width, type, 0)) {
            while (Ui.width(line, type) > width && line.length() > 1) {
                int n = line.length() - 1;
                while (n > 1 && Ui.width(line.substring(0, n), type) > width) {
                    n--;
                }
                for (int i = n; i > Math.max(1, n - 14); i--) {
                    char c = line.charAt(i - 1);
                    if (c == '-' || c == '_' || c == '/') {
                        n = i;
                        break;
                    }
                }
                out.add(line.substring(0, n));
                line = line.substring(n);
            }
            out.add(line);
        }
        return out;
    }

    /** {@code s} wrapped to a column; more than {@code max} lines is a fault, and the lines past it are cut. */
    private List<String> wrapped(String s, int max) {
        List<String> lines = wrap(s, Type.BODY, COL_W);
        if (lines.size() > max) {
            overflow = true;
            lines = lines.subList(0, max);
        }
        return lines;
    }

    private void foot(int index) {
        int left = leftNumber(index);
        single(Kind.MUTED, Integer.toString(left), LEFT_X, FOOT_Y, COL_W, 1);
        single(Kind.MUTED, Integer.toString(left + 1), RIGHT_X, FOOT_Y, COL_W, 1);
    }

    private void prepare() {
        marks.clear();
        cuts.clear();
        rows.clear();
        media = null;
        titleIcon = false;
        place = null;
        continueLabel = null;
        overflow = false;
        if (chapter < 0) {
            prepareContents();
        } else {
            preparePage();
        }
        foot(flatIndex());
    }

    private void prepareContents() {
        // left page: the title page, with the way back to where you stopped and the help at its foot
        titleIcon = true;
        rule(LEFT_X, 20, 50);
        rule(LEFT_X + COL_W - 50, 20, 50);
        single(Kind.HEAD, text(Component.translatable("gui.bwr.guide.title")), LEFT_X, 31, COL_W, 1);
        rule(LEFT_X + 20, 43, COL_W - 40);
        int y = 49;
        for (String line : wrapped(text(Component.translatable("gui.bwr.guide.intro")), 8)) {
            marks.add(new Mark(Kind.BODY, line, LEFT_X, y, COL_W, 0));
            y += LINE;
        }
        y += 4;
        place = GuideProgress.load(book);
        if (place != null) {
            String where = book.chapters().get(book.indexOf(place.chapter())).title();
            continueLabel = text(Component.translatable("gui.bwr.guide.continue", where, place.page() + 1));
            continueHeight = Math.min(2, Ui.wrapStrings(continueLabel, COL_W - 10, Type.BODY, 0).size()) * LINE + 8;
            continueY = y;
            y += continueHeight;
        }
        List<String> help = wrapped(text(Component.translatable("gui.bwr.guide.help")), 6);
        int helpTop = BODY_BOTTOM - help.size() * LINE;
        if (y + 2 > helpTop) {
            overflow = true;
        }
        int hy = helpTop;
        for (String line : help) {
            marks.add(new Mark(Kind.MUTED, line, LEFT_X, hy, COL_W, 0));
            hy += LINE;
        }

        // right page: the chapters, long titles on two lines, each with the number of its first page
        single(Kind.HEAD, text(Component.translatable("gui.bwr.guide.contents")), RIGHT_X, TITLE_Y, COL_W, 0);
        rule(RIGHT_X, RULE_Y, COL_W);
        int n = book.chapters().size();
        int room = BODY_BOTTOM - BODY_TOP;
        List<List<String>> wrappedTitles = new ArrayList<>();
        int doubles = 0;
        for (int i = 0; i < n; i++) {
            String number = Integer.toString(leftNumber(chapterStart[i]));
            List<String> lines = Ui.wrapStrings(book.chapters().get(i).title(), COL_W - Ui.width(number, Type.BODY) - 8, Type.BODY, 2);
            wrappedTitles.add(lines);
            if (lines.size() > 1) {
                doubles++;
            }
        }
        // the roomiest row heights that fit the page: a row of one line is 14 px at best, one of two lines 9 px more
        int single = 14;
        while (single > 10 && (n - doubles) * single + doubles * (single + LINE) > room) {
            single--;
        }
        if ((n - doubles) * single + doubles * (single + LINE) > room) {
            overflow = true;
        }
        int ry = BODY_TOP;
        for (int i = 0; i < n; i++) {
            List<String> lines = wrappedTitles.get(i);
            int height = lines.size() > 1 ? single + LINE : single;
            rows.add(new RowSpec(i, ry, height, lines, book.chapters().get(i).title(), Integer.toString(leftNumber(chapterStart[i]))));
            ry += height;
        }
    }

    private void preparePage() {
        GuideBook.Chapter c = book.chapters().get(chapter);
        GuideBook.Page p = c.pages().get(page);
        single(Kind.MUTED, c.title(), LEFT_X, HEAD_Y, COL_W, 1);
        single(Kind.MUTED, text(Component.translatable("gui.bwr.guide.title")), RIGHT_X, HEAD_Y, COL_W, 1);
        // a heading too long for one line takes two (a third is cut), and everything under it moves down
        List<String> heading = wrap(p.title(), Type.TITLE, COL_W);
        if (heading.size() > 2) {
            heading = new ArrayList<>(heading.subList(0, 2));
            heading.set(1, Ui.fit(heading.get(1) + " ...", Type.TITLE, COL_W));
        }
        for (int i = 0; i < heading.size(); i++) {
            marks.add(new Mark(Kind.HEAD, heading.get(i), LEFT_X, TITLE_Y + i * LINE, COL_W, 0));
        }
        int extra = (heading.size() - 1) * LINE;
        rule(LEFT_X, RULE_Y + extra, COL_W);
        rule(RIGHT_X, RULE_Y, COL_W);
        int top = BODY_TOP + extra;

        int offset = 0;
        GuideMedia pictures = new GuideMedia(p);
        if (!pictures.isEmpty()) {
            media = pictures;
            offset = pictures.place(RIGHT_X, BODY_TOP, COL_W) + 4;
        }

        List<Line> lines = new ArrayList<>();
        for (String s : wrap(p.text(), Type.BODY, COL_W)) {
            lines.add(new Line(s, false));
        }
        if (page == c.pages().size() - 1) {
            lines.add(new Line("", true));
            for (String s : wrap(text(Component.translatable("gui.bwr.guide.more", String.join(", ", c.docs()))), Type.BODY, COL_W)) {
                lines.add(new Line(s, true));
            }
        }
        flow(lines, top, offset);
        spokenPage = text(Component.translatable("gui.bwr.guide.position", c.title(), page + 1, c.pages().size())) + ". " + p.title() + ". " + p.text();
    }

    /**
     * Splits the lines between the two columns so that both end at about the same height (the left one starts under
     * the heading, the right one under the pictures, which take {@code offset} pixels). Text that fits neither column
     * is a fault; the lines that would run past the foot are not drawn.
     */
    private void flow(List<Line> lines, int top, int offset) {
        int capacity = BODY_BOTTOM - top;
        int rightCapacity = BODY_BOTTOM - BODY_TOP;
        int n = lines.size();
        int total = 0;
        for (Line line : lines) {
            total += line.height();
        }
        note = lines.size() + " lines, " + total + " px of text and " + offset + " px of pictures in two columns of " + capacity + " and " + rightCapacity + " px";
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        int leftHeight = 0;
        for (int k = 0; k <= n; k++) {
            int rightHeight = offset + total - leftHeight;
            // balance the bottoms of the two columns (the left one starts lower under a two-line heading)
            int diff = Math.abs((top - BODY_TOP + leftHeight) - rightHeight);
            if (leftHeight <= capacity && rightHeight <= rightCapacity && diff < bestDiff) {
                bestDiff = diff;
                best = k;
            }
            if (k < n) {
                leftHeight += lines.get(k).height();
            }
        }
        if (best < 0) {
            overflow = true;
            best = 0;
            int used = 0;
            while (best < n && used + lines.get(best).height() <= capacity) {
                used += lines.get(best).height();
                best++;
            }
        }
        int y = top;
        for (int i = 0; i < best; i++) {
            y = put(lines.get(i), LEFT_X, y);
        }
        y = BODY_TOP + offset;
        int start = best;
        while (start < n && lines.get(start).text().isEmpty()) {
            start++;
        }
        for (int i = start; i < n; i++) {
            y = put(lines.get(i), RIGHT_X, y);
        }
    }

    /** Adds one line of a column at {@code y} and returns where the next one goes; a line that would pass the foot is not drawn. */
    private int put(Line line, int x, int y) {
        if (y + line.height() > BODY_BOTTOM) {
            if (!line.text().isEmpty()) {
                overflow = true;
            }
            return y + line.height();
        }
        if (!line.text().isEmpty()) {
            if (Ui.width(line.text(), Type.BODY) > COL_W) {
                overflow = true;
            }
            marks.add(new Mark(line.muted() ? Kind.MUTED : Kind.BODY, line.text(), x, y, COL_W, 0));
        }
        return y + line.height();
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int k = zoom;
        g.fill(leftPos + 3 * k, topPos + 3 * k, leftPos + (BW + 3) * k, topPos + (BH + 3) * k, 0x55000000);
        g.blit(TEXTURE, leftPos, topPos, BW * k, BH * k, 0, 0, BW, BH, TEX_W, TEX_H);
        for (Mark m : marks) {
            if (m.kind() == Kind.RULE) {
                g.fill(sx(m.x()), sy(m.y()), sx(m.x() + m.w()), sy(m.y()) + k, RULE);
                continue;
            }
            Type type = type(m.kind());
            int w = Ui.width(m.shown(), type);
            int dx = m.x() + switch (m.align()) {
                case 1 -> (m.w() - w) / 2;
                case 2 -> m.w() - w;
                default -> 0;
            };
            int colour = switch (m.kind()) {
                case HEAD -> HEADING;
                case MUTED -> MUTED;
                default -> INK;
            };
            Ui.text(g, m.shown(), sx(dx), sy(m.y()), type, colour, k);
        }
        if (titleIcon) {
            var pose = g.pose();
            pose.pushPose();
            pose.translate(sx(LEFT_X + (COL_W - 16) / 2), sy(12), 0);
            pose.scale(k, k, 1);
            g.renderItem(new ItemStack(BwrItems.GUIDE_BOOK.get()), 0, 0);
            pose.popPose();
        }
        if (media != null) {
            var pose = g.pose();
            pose.pushPose();
            pose.translate(leftPos, topPos, 0);
            pose.scale(k, k, 1);
            media.render(g);
            pose.popPose();
        }
    }

    /** No vanilla labels: the book draws its own text in {@link #renderBg}. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int dx = Math.floorDiv(mouseX - leftPos, zoom);
        int dy = Math.floorDiv(mouseY - topPos, zoom);
        List<Component> tip = media == null ? List.of() : media.tooltipAt(dx, dy);
        if (tip.isEmpty()) {
            for (Cut cut : cuts) {
                if (dx >= cut.x() && dx < cut.x() + cut.w() && dy >= cut.y() && dy < cut.y() + LINE) {
                    tip = List.of(Component.literal(cut.full()));
                    break;
                }
            }
        }
        if (!tip.isEmpty()) {
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    /** What a screen reader says when a spread opens: where you are and what the page says, once. */
    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        if (!narrated) {
            narrated = true;
            List<String> lines = new ArrayList<>();
            if (chapter < 0) {
                lines.add(text(Component.translatable("gui.bwr.guide.intro")));
                for (RowSpec row : rows) {
                    lines.add(row.title() + ", page " + row.number());
                }
            } else {
                lines.add(spokenPage);
                if (media != null) {
                    media.narrate(lines);
                }
            }
            output.add(NarratedElementType.HINT, Component.literal(String.join(". ", lines)));
        }
        super.updateNarratedWidget(output);
    }

    // ---------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        switch (key) {
            case 263, 266 -> {
                turn(-1);
                return true;
            }
            case 262, 267 -> {
                turn(1);
                return true;
            }
            case 268 -> {
                goTo(-1);
                return true;
            }
            case 269 -> {
                goTo(flat.size() - 1);
                return true;
            }
            default -> {
            }
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

    // ---------------------------------------------------------------
    // Widgets: all of them are plain vanilla buttons drawn as parts of the page, silent because the page turn plays its own sound
    // ---------------------------------------------------------------

    /** A button that makes no click sound of its own (the page turn does) and is drawn by its subclass. */
    private abstract static class PageControl extends Button {
        PageControl(int x, int y, int w, int h, Component label, String tooltip, OnPress onPress) {
            super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.literal(tooltip), label));
        }

        @Override
        public void playDownSound(SoundManager sounds) {
        }

        /** A frame {@code zoom} thick, inside the widget. */
        static void frame(GuiGraphics g, int x, int y, int w, int h, int t, int colour) {
            g.fill(x, y, x + w, y + t, colour);
            g.fill(x, y + h - t, x + w, y + h, colour);
            g.fill(x, y + t, x + t, y + h - t, colour);
            g.fill(x + w - t, y + t, x + w, y + h - t, colour);
        }
    }

    /** A picture from the sheet: a page-turn arrow or the contents ribbon, drawn lighter when pointed at or focused. */
    private static final class BookSprite extends PageControl {
        private final int u;
        private final int v;
        private final int hoverU;
        private final int hoverV;
        private final int sw;
        private final int sh;

        BookSprite(int x, int y, int w, int h, int u, int v, int hoverU, int hoverV, int sw, int sh, String label, String tooltip, OnPress onPress) {
            super(x, y, w, h, Component.literal(label), tooltip, onPress);
            this.u = u;
            this.v = v;
            this.hoverU = hoverU;
            this.hoverV = hoverV;
            this.sw = sw;
            this.sh = sh;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = isHoveredOrFocused();
            g.blit(TEXTURE, getX(), getY(), getWidth(), getHeight(), on ? hoverU : u, on ? hoverV : v, sw, sh, TEX_W, TEX_H);
            if (isFocused()) {
                frame(g, getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, 1, LINK);
            }
        }
    }

    /** A chapter in the contents: its title (on two lines when it is long), a dotted leader and the page number; the whole row is the link. */
    private static final class BookRow extends PageControl {
        private final GuideScreen screen;
        private final RowSpec row;

        BookRow(GuideScreen screen, RowSpec row) {
            super(screen.sx(RIGHT_X), screen.sy(row.y()), COL_W * screen.zoom, row.height() * screen.zoom, Component.literal(row.title()),
                    tooltipFor(screen, row), b -> screen.show(row.chapter(), 0));
            this.screen = screen;
            this.row = row;
        }

        private static String tooltipFor(GuideScreen screen, RowSpec row) {
            String tip = text(Component.translatable("gui.bwr.guide.chapter_tip", row.chapter() + 1,
                    screen.book.chapters().get(row.chapter()).pages().size()));
            boolean cut = !String.join(" ", row.lines()).equals(row.title());
            return cut ? row.title() + ": " + tip : tip;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int k = screen.zoom;
            boolean on = isHoveredOrFocused();
            if (on) {
                g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), HOVER_FILL);
            }
            if (isFocused()) {
                frame(g, getX(), getY(), getWidth(), getHeight(), k, LINK);
            }
            int colour = on ? LINK : INK;
            int lines = row.lines().size();
            int top = getY() + k * ((row.height() - lines * LINE + 1) / 2);
            for (int i = 0; i < lines; i++) {
                Ui.text(g, row.lines().get(i), getX() + 2 * k, top + i * LINE * k, Type.BODY, colour, k);
            }
            // the number and the dotted leader to it sit on the last line of the title
            int ty = top + (lines - 1) * LINE * k;
            int numberW = Ui.width(row.number(), Type.BODY);
            int from = 2 + Ui.width(row.lines().get(lines - 1), Type.BODY) + 4;
            int to = COL_W - 2 - numberW - 3;
            int dots = (to - from) / 6;
            if (dots > 0) {
                Ui.text(g, ". ".repeat(dots), getX() + from * k, ty, Type.BODY, MUTED, k);
            }
            Ui.text(g, row.number(), getX() + getWidth() - (2 + numberW) * k, ty, Type.BODY, colour, k);
        }
    }

    /** "Continue: chapter, page N": a boxed link on the title page that opens the book where you stopped. */
    private static final class BookLink extends PageControl {
        private final GuideScreen screen;
        private final List<String> lines;

        BookLink(GuideScreen screen) {
            super(screen.sx(LEFT_X), screen.sy(screen.continueY), COL_W * screen.zoom, screen.continueHeight * screen.zoom,
                    Component.literal(screen.continueLabel), text(Component.translatable("gui.bwr.guide.continue_tip")),
                    b -> screen.show(screen.book.indexOf(screen.place.chapter()), screen.place.page()));
            this.screen = screen;
            this.lines = Ui.wrapStrings(screen.continueLabel, COL_W - 10, Type.BODY, 2);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int k = screen.zoom;
            boolean on = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), on ? HOVER_FILL : BOX_FILL);
            frame(g, getX(), getY(), getWidth(), getHeight(), k, isFocused() ? LINK : MUTED);
            int colour = on ? LINK : INK;
            for (int i = 0; i < lines.size(); i++) {
                Ui.text(g, lines.get(i), getX() + 5 * k, getY() + (4 + i * LINE) * k, Type.BODY, colour, k);
            }
        }
    }
}
