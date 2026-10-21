package dev.bwr.mod.gui.client.style;

import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The building blocks of a screen: containers (column, split, card), text (section header, label-value row, note,
 * line, status line), a meter, rows of controls, tabs and a box for one big widget. Each is a {@link UiElement}; the
 * factory methods at the top are what a screen calls.
 *
 * <p>Every text element truncates with an ellipsis and offers the whole text as a tooltip, wraps instead where it is a
 * paragraph, and says what a screen reader should read out; every element can hide itself with
 * {@link UiElement#showIf}. Values are suppliers, looked up each frame, so none of it needs rebuilding when the menu
 * changes.
 */
public final class Layout {
    private Layout() {
    }

    // ---------------------------------------------------------------
    // Factories
    // ---------------------------------------------------------------

    public static Column column(int gap, UiElement... kids) {
        return new Column(gap, kids);
    }

    /** A column with no gap: rows of a card sit flush, spacing is added with {@link #gap}. */
    public static Column stack(UiElement... kids) {
        return new Column(0, kids);
    }

    public static Split split(int gap, UiElement... kids) {
        Split s = new Split(gap);
        for (UiElement k : kids) {
            s.add(k, 1);
        }
        return s;
    }

    public static Card card(String title, UiElement... rows) {
        return new Card(() -> title, rows);
    }

    /** A card whose title depends on the machine's state. */
    public static Card card(Supplier<String> title, UiElement... rows) {
        return new Card(title, rows);
    }

    public static Section section(String title) {
        return new Section(title);
    }

    /** Forces its child to exactly {@code height} px (the child fills it). */
    public static Sized sized(int height, UiElement child) {
        return new Sized(height, child);
    }

    /** A viewport that scrolls its child with the mouse wheel and Page Up / Page Down when the child is taller. Flexible. */
    public static ScrollArea scrollArea(int minHeight, UiElement child) {
        return new ScrollArea(minHeight, child);
    }

    public static Spacer gap(int px) {
        return new Spacer(px, false);
    }

    /** A spacer that takes whatever height is left in its column. */
    public static Spacer flex() {
        return new Spacer(0, true);
    }

    public static ValueRow row(String label, Supplier<String> value, String unit) {
        return new ValueRow(() -> label, value, () -> unit);
    }

    /** A row whose unit changes with the machine's state (empty while the value is a word such as "No flow"). */
    public static ValueRow row(String label, Supplier<String> value, Supplier<String> unit) {
        return new ValueRow(() -> label, value, unit);
    }

    public static ValueRow row(Supplier<String> label, Supplier<String> value, String unit) {
        return new ValueRow(label, value, () -> unit);
    }

    public static ValueRow row(Supplier<String> label, Supplier<String> value, Supplier<String> unit) {
        return new ValueRow(label, value, unit);
    }

    public static ValueRow row(String label, Supplier<String> value) {
        return new ValueRow(() -> label, value, () -> "");
    }

    public static Note note(Supplier<String> text) {
        return new Note(text);
    }

    public static Note note(String text) {
        return new Note(() -> text);
    }

    public static Line line(Supplier<String> text, Type type) {
        return new Line(text, type);
    }

    public static StatusLine statusLine(Supplier<Status> status, Supplier<String> text, Supplier<String> right) {
        return new StatusLine(status, text, right);
    }

    public static Meter meter(String name, DoubleSupplier fraction, Supplier<Status> status, Supplier<String> text) {
        return new Meter(name, fraction, status, text);
    }

    public static Controls controls(int gap) {
        return new Controls(gap);
    }

    public static Tabs tabs(String[] labels, String[] tips, IntSupplier selected, IntConsumer onSelect) {
        return new Tabs(labels, tips, selected, onSelect);
    }

    public static Fill fill(AbstractWidget widget, int minHeight) {
        return new Fill(widget, minHeight);
    }

    /** A colour scale: a swatch gradient with a caption at each end. {@code colourAt} maps 0 to 1 onto an ARGB colour. */
    public static Legend legend(Supplier<String> low, Supplier<String> high, java.util.function.DoubleToIntFunction colourAt) {
        return new Legend(low, high, colourAt);
    }

    /** A status icon and word for the title bar. */
    public static Chip chip(Supplier<Status> status, Supplier<String> text) {
        return new Chip(status, text);
    }

    /** The right-aligned items of the title bar, in the order given (left to right). */
    public static TitleItems titleItems(UiElement... items) {
        return new TitleItems(items);
    }

    // ---------------------------------------------------------------
    // Containers
    // ---------------------------------------------------------------

    /** Children stacked top to bottom; flexible children share any height left over. */
    public static final class Column extends UiElement {
        private final int gap;
        private final List<UiElement> kids = new ArrayList<>();

        Column(int gap, UiElement... kids) {
            this.gap = gap;
            this.kids.addAll(List.of(kids));
        }

        public Column add(UiElement kid) {
            kids.add(kid);
            return this;
        }

        @Override
        public boolean flexible() {
            for (UiElement k : kids) {
                if (k.isVisible() && k.flexible()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected int measure(int width) {
            int total = 0;
            int shown = 0;
            for (UiElement k : kids) {
                if (k.isVisible()) {
                    total += k.naturalHeight(width);
                    shown++;
                }
            }
            return total + Math.max(0, shown - 1) * gap;
        }

        @Override
        protected void layout() {
            int unit = 0;
            for (UiElement k : kids) {
                if (k instanceof ValueRow r && r.isVisible()) {
                    unit = Math.max(unit, r.unitWidth());
                }
            }
            for (UiElement k : kids) {
                if (k instanceof ValueRow r) {
                    r.unitColumn(unit);
                }
            }
            int natural = measure(w);
            int extra = Math.max(0, h - natural);
            int flex = 0;
            for (UiElement k : kids) {
                if (k.isVisible() && k.flexible()) {
                    flex++;
                }
            }
            int cy = y;
            boolean first = true;
            int given = 0;
            int flexSeen = 0;
            for (UiElement k : kids) {
                if (!k.isVisible()) {
                    k.hideTree();
                    continue;
                }
                if (!first) {
                    cy += gap;
                }
                first = false;
                int kh = k.naturalHeight(w);
                if (k.flexible() && flex > 0) {
                    flexSeen++;
                    int share = flexSeen == flex ? extra - given : extra / flex;
                    given += share;
                    kh += share;
                }
                k.place(x, cy, w, kh);
                cy += kh;
            }
        }

        @Override
        protected void hide() {
            kids.forEach(UiElement::hideTree);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            for (UiElement k : kids) {
                if (k.isVisible()) {
                    k.render(g, mx, my);
                }
            }
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            kids.forEach(k -> k.collectWidgets(out));
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            for (UiElement k : kids) {
                List<Component> t = k.tooltipAt(mx, my);
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return super.tooltipAt(mx, my);
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            for (UiElement k : kids) {
                if (k.isVisible() && k.scroll(mx, my, delta)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean page(int direction) {
            for (UiElement k : kids) {
                if (k.isVisible() && k.page(direction)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void narrate(List<String> out) {
            for (UiElement k : kids) {
                if (k.isVisible()) {
                    k.narrate(out);
                }
            }
        }
    }

    /** Children side by side. A positive weight shares the free width; a negative one is a fixed width in px. */
    public static final class Split extends UiElement {
        private final int gap;
        private final List<UiElement> kids = new ArrayList<>();
        private final List<Integer> weights = new ArrayList<>();
        private int stackBelow;

        Split(int gap) {
            this.gap = gap;
        }

        /** On a narrow screen (less than {@code px} wide) the children are stacked, each as wide as the whole, instead of side by side. */
        public Split stackBelow(int px) {
            this.stackBelow = px;
            return this;
        }

        private boolean stacked(int width) {
            return stackBelow > 0 && width < stackBelow;
        }

        public Split add(UiElement kid, int weight) {
            kids.add(kid);
            weights.add(weight);
            return this;
        }

        /** Adds a child that is {@code px} wide. */
        public Split fixed(UiElement kid, int px) {
            return add(kid, -px);
        }

        private int[] widths(int width) {
            int n = 0;
            int fixed = 0;
            int weightSum = 0;
            for (int i = 0; i < kids.size(); i++) {
                if (!kids.get(i).isVisible()) {
                    continue;
                }
                n++;
                if (weights.get(i) < 0) {
                    fixed += -weights.get(i);
                } else {
                    weightSum += weights.get(i);
                }
            }
            int free = Math.max(0, width - fixed - Math.max(0, n - 1) * gap);
            int[] out = new int[kids.size()];
            int usedFree = 0;
            int lastWeighted = -1;
            for (int i = 0; i < kids.size(); i++) {
                if (kids.get(i).isVisible() && weights.get(i) >= 0) {
                    lastWeighted = i;
                }
            }
            for (int i = 0; i < kids.size(); i++) {
                if (!kids.get(i).isVisible()) {
                    continue;
                }
                if (weights.get(i) < 0) {
                    out[i] = -weights.get(i);
                } else {
                    out[i] = i == lastWeighted ? free - usedFree : free * weights.get(i) / Math.max(1, weightSum);
                    usedFree += out[i];
                }
            }
            return out;
        }

        @Override
        protected int measure(int width) {
            if (stacked(width)) {
                int total = 0;
                int shown = 0;
                for (UiElement k : kids) {
                    if (k.isVisible()) {
                        total += k.naturalHeight(width);
                        shown++;
                    }
                }
                return total + Math.max(0, shown - 1) * gap;
            }
            int[] ws = widths(width);
            int tallest = 0;
            for (int i = 0; i < kids.size(); i++) {
                if (kids.get(i).isVisible()) {
                    tallest = Math.max(tallest, kids.get(i).naturalHeight(ws[i]));
                }
            }
            return tallest;
        }

        @Override
        protected void layout() {
            if (stacked(w)) {
                int cy = y;
                boolean firstKid = true;
                for (UiElement k : kids) {
                    if (!k.isVisible()) {
                        k.hideTree();
                        continue;
                    }
                    if (!firstKid) {
                        cy += gap;
                    }
                    firstKid = false;
                    int kh = k.naturalHeight(w);
                    k.place(x, cy, w, kh);
                    cy += kh;
                }
                return;
            }
            int[] ws = widths(w);
            int cx = x;
            boolean first = true;
            for (int i = 0; i < kids.size(); i++) {
                UiElement k = kids.get(i);
                if (!k.isVisible()) {
                    k.hideTree();
                    continue;
                }
                if (!first) {
                    cx += gap;
                }
                first = false;
                k.place(cx, y, ws[i], h);
                cx += ws[i];
            }
        }

        @Override
        protected void hide() {
            kids.forEach(UiElement::hideTree);
        }

        @Override
        public boolean flexible() {
            return !stacked(w) && kids.stream().anyMatch(UiElement::flexible);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            for (UiElement k : kids) {
                if (k.isVisible()) {
                    k.render(g, mx, my);
                }
            }
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            kids.forEach(k -> k.collectWidgets(out));
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            for (UiElement k : kids) {
                List<Component> t = k.tooltipAt(mx, my);
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return super.tooltipAt(mx, my);
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            for (UiElement k : kids) {
                if (k.isVisible() && k.scroll(mx, my, delta)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean page(int direction) {
            for (UiElement k : kids) {
                if (k.isVisible() && k.page(direction)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void narrate(List<String> out) {
            for (UiElement k : kids) {
                if (k.isVisible()) {
                    k.narrate(out);
                }
            }
        }
    }

    /** A titled card: surface fill, quiet border, the header type and a rule under the title, content padded by 8 px. */
    public static final class Card extends UiElement {
        private final Supplier<String> title;
        private final Column inner;
        private Supplier<Status> status;
        private Supplier<String> statusWord;
        private int pad = BwrStyle.SP2;

        Card(Supplier<String> title, UiElement... rows) {
            this.title = title;
            this.inner = new Column(0, rows);
        }

        /** Shows a status (icon and word) at the right of the title. */
        public Card status(Supplier<Status> status) {
            this.status = status;
            return this;
        }

        /** Shows a status with its own word (for example HEAD OFF) at the right of the title. */
        public Card status(Supplier<Status> status, Supplier<String> word) {
            this.status = status;
            this.statusWord = word;
            return this;
        }

        /** Padding of 4 px instead of 8, for a card in a tight column. */
        public Card compact() {
            this.pad = BwrStyle.SP1;
            return this;
        }

        private int headerHeight() {
            return title.get().isEmpty() ? 0 : BwrStyle.ROW + BwrStyle.SP1;
        }

        @Override
        protected int measure(int width) {
            return pad + headerHeight() + inner.naturalHeight(width - 2 * pad) + pad;
        }

        @Override
        protected void layout() {
            int top = pad + headerHeight();
            inner.place(x + pad, y + top, w - 2 * pad, Math.max(0, h - top - pad));
        }

        @Override
        protected void hide() {
            inner.hideTree();
        }

        @Override
        public boolean flexible() {
            return inner.flexible();
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Ui.card(g, x, y, w, h);
            String title = this.title.get();
            if (!title.isEmpty()) {
                int right = x + w - BwrStyle.SP2;
                if (status != null) {
                    Status s = status.get();
                    if (s != Status.NONE) {
                        String word = statusWord == null ? s.label : statusWord.get();
                        int ww = Ui.width(word, Type.CAPTION);
                        Ui.text(g, word, right - ww, y + BwrStyle.SP2 + 1, Type.CAPTION, s.colour);
                        Ui.statusIcon(g, right - ww - BwrStyle.SP1 - BwrStyle.ICON, y + BwrStyle.SP2 + 1, s);
                        right -= ww + BwrStyle.SP1 + BwrStyle.ICON + BwrStyle.SP2;
                    }
                }
                Ui.text(g, Ui.fit(title, Type.HEADER, right - x - BwrStyle.SP2), x + BwrStyle.SP2, y + BwrStyle.SP2 + 1, Type.HEADER);
                Ui.rule(g, x + BwrStyle.SP2, y + BwrStyle.SP2 + BwrStyle.ROW - 1, w - 2 * BwrStyle.SP2);
            }
            inner.render(g, mx, my);
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            inner.collectWidgets(out);
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            List<Component> t = inner.tooltipAt(mx, my);
            return t.isEmpty() ? super.tooltipAt(mx, my) : t;
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            return inner.scroll(mx, my, delta);
        }

        @Override
        public boolean page(int direction) {
            return inner.page(direction);
        }

        @Override
        public void narrate(List<String> out) {
            String name = title.get();
            if (!name.isEmpty()) {
                out.add(name);
            }
            inner.narrate(out);
        }
    }

    // ---------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------

    /** A section header outside a card: the header type over a rule. */
    public static final class Section extends UiElement {
        private final String title;

        Section(String title) {
            this.title = title;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW + BwrStyle.SP1;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Ui.text(g, Ui.fit(title, Type.HEADER, w), x, y + 1, Type.HEADER);
            Ui.rule(g, x, y + BwrStyle.ROW - 1, w);
        }

        @Override
        public void narrate(List<String> out) {
            out.add(title);
        }
    }

    /** Gives its child exactly the height it is told to. */
    public static final class Sized extends UiElement {
        private final int height;
        private final UiElement child;

        Sized(int height, UiElement child) {
            this.height = height;
            this.child = child;
        }

        @Override
        protected int measure(int width) {
            return height;
        }

        @Override
        protected void layout() {
            child.place(x, y, w, h);
        }

        @Override
        protected void hide() {
            child.hideTree();
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            child.render(g, mx, my);
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            child.collectWidgets(out);
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            return child.tooltipAt(mx, my);
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            return child.scroll(mx, my, delta);
        }

        @Override
        public boolean page(int direction) {
            return child.page(direction);
        }

        @Override
        public void narrate(List<String> out) {
            child.narrate(out);
        }
    }

    /** A clipped viewport onto a taller child, with a scroll bar. Holds no widgets (they could not be clipped). */
    public static final class ScrollArea extends UiElement {
        private final int minHeight;
        private final UiElement child;
        private int offset;
        private int maxOffset;

        ScrollArea(int minHeight, UiElement child) {
            this.minHeight = minHeight;
            this.child = child;
        }

        @Override
        protected int measure(int width) {
            return minHeight;
        }

        @Override
        public boolean flexible() {
            return true;
        }

        @Override
        protected void layout() {
            int inner = w - BwrStyle.SP2;
            int natural = child.naturalHeight(inner);
            maxOffset = Math.max(0, natural - h);
            offset = Math.max(0, Math.min(offset, maxOffset));
            child.place(x, y - offset, maxOffset > 0 ? inner : w, natural);
        }

        @Override
        protected void hide() {
            child.hideTree();
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            g.enableScissor(x, y, x + w, y + h);
            Ui.clipped(true);
            child.render(g, mx, my);
            Ui.clipped(false);
            g.disableScissor();
            if (maxOffset > 0) {
                int bx = x + w - 3;
                g.fill(bx, y, bx + 3, y + h, BwrStyle.INSET);
                int thumb = Math.max(12, h * h / (h + maxOffset));
                int top = y + (int) ((long) (h - thumb) * offset / Math.max(1, maxOffset));
                g.fill(bx, top, bx + 3, top + thumb, BwrStyle.BORDER_STRONG);
            }
        }

        @Override
        public boolean scroll(double mx, double my, double delta) {
            if (!contains((int) mx, (int) my) || maxOffset == 0) {
                return false;
            }
            offset = Math.max(0, Math.min(maxOffset, offset - (int) Math.round(delta * BwrStyle.ROW * 2)));
            return true;
        }

        @Override
        public boolean page(int direction) {
            if (maxOffset == 0) {
                return false;
            }
            int before = offset;
            offset = Math.max(0, Math.min(maxOffset, offset + direction * Math.max(BwrStyle.ROW, h - BwrStyle.ROW)));
            return offset != before;
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            return contains(mx, my) ? child.tooltipAt(mx, my) : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            child.narrate(out);
        }
    }

    public static final class Spacer extends UiElement {
        private final int px;
        private final boolean flex;

        Spacer(int px, boolean flex) {
            this.px = px;
            this.flex = flex;
        }

        @Override
        protected int measure(int width) {
            return px;
        }

        @Override
        public boolean flexible() {
            return flex;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
        }
    }

    /**
     * Label on the left, value right-aligned in a column that ends where the other rows' values end, unit in a column
     * of its own after it. An optional status puts its icon before the value and its colour on it; a label or value that
     * does not fit is cut with an ellipsis and the tooltip carries the whole row.
     */
    public static final class ValueRow extends UiElement {
        private static final int MIN_LABEL = 24;

        private final Supplier<String> label;
        private final Supplier<String> value;
        private final Supplier<String> unitSupplier;
        private String unit = "";
        private Supplier<Status> status = () -> Status.NONE;
        private int unitColumn;
        private int rowHeight = BwrStyle.ROW;
        private String lastLabel = "";
        private String lastValue = "";
        private Status lastStatus = Status.NONE;
        private boolean cut;

        ValueRow(Supplier<String> label, Supplier<String> value, Supplier<String> unit) {
            this.label = label;
            this.value = value;
            this.unitSupplier = unit;
        }

        public ValueRow status(Supplier<Status> status) {
            this.status = status;
            return this;
        }

        public ValueRow status(Status status) {
            this.status = () -> status;
            return this;
        }

        /** A row 10 px high instead of 12, for a card that has to hold many rows (text sits one pixel from the top). */
        public ValueRow dense() {
            this.rowHeight = 10;
            return this;
        }

        int unitWidth() {
            unit = unitSupplier.get();
            return unit.isEmpty() ? 0 : Ui.width(unit, Type.CAPTION);
        }

        void unitColumn(int px) {
            this.unitColumn = px;
        }

        @Override
        protected int measure(int width) {
            return rowHeight;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            String lab = label.get();
            String val = value.get();
            Status st = status.get();
            unit = unitSupplier.get();
            lastLabel = lab;
            lastValue = val;
            lastStatus = st;

            int right = x + w;
            int unitX = right - unitColumn;
            int valueRight = unitColumn > 0 ? unitX - BwrStyle.SP1 : right;
            int iconW = st == Status.NONE ? 0 : BwrStyle.ICON + BwrStyle.SP1;
            int maxValue = valueRight - x - MIN_LABEL - BwrStyle.SP1 - iconW;
            String shownValue = Ui.fit(val, Type.BODY, maxValue);
            int valueX = valueRight - Ui.width(shownValue, Type.BODY);
            int labelMax = valueX - iconW - x - BwrStyle.SP2;
            String shownLabel = Ui.fit(lab, Type.BODY, labelMax);
            cut = !shownValue.equals(val) || !shownLabel.equals(lab);

            int ty = y + (rowHeight - 8) / 2;
            Ui.text(g, shownLabel, x, ty, Type.BODY, BwrStyle.TEXT_SECONDARY);
            if (st != Status.NONE) {
                Ui.statusIcon(g, valueX - iconW, y + (rowHeight - BwrStyle.ICON) / 2, st);
            }
            Ui.text(g, shownValue, valueX, ty, Type.BODY, st == Status.NONE ? BwrStyle.TEXT_PRIMARY : st.colour);
            if (!unit.isEmpty()) {
                Ui.text(g, unit, unitX, ty, Type.CAPTION);
            }
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            if (!contains(mx, my)) {
                return List.of();
            }
            List<Component> explicit = super.tooltipAt(mx, my);
            if (explicit.isEmpty() && !cut) {
                return List.of();
            }
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(lastLabel + ": " + lastValue + (unit.isEmpty() ? "" : " " + unit)));
            if (lastStatus != Status.NONE) {
                out.add(Component.literal(lastStatus.label).withStyle(Style.EMPTY.withColor(lastStatus.colour & 0xFFFFFF)));
            }
            out.addAll(explicit);
            return out;
        }

        @Override
        public void narrate(List<String> out) {
            String unitText = unitSupplier.get();
            String text = label.get() + ", " + value.get() + (unitText.isEmpty() ? "" : " " + unitText);
            Status st = status.get();
            out.add(st == Status.NONE ? text : text + ", " + st.label.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** One line of text in a type, cut with an ellipsis (and a tooltip with the whole text) when it does not fit. */
    public static final class Line extends UiElement {
        private final Supplier<String> text;
        private final Type type;
        private Supplier<Status> status = () -> Status.NONE;
        private String last = "";
        private boolean cut;

        Line(Supplier<String> text, Type type) {
            this.text = text;
            this.type = type;
        }

        public Line status(Supplier<Status> status) {
            this.status = status;
            return this;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            String full = text.get();
            Status st = status.get();
            last = full;
            int indent = st == Status.NONE ? 0 : BwrStyle.ICON + BwrStyle.SP1;
            String shown = Ui.fit(full, type, w - indent);
            cut = !shown.equals(full);
            if (st != Status.NONE) {
                Ui.statusIcon(g, x, y + (BwrStyle.ROW - BwrStyle.ICON) / 2, st);
            }
            Ui.text(g, shown, x + indent, y + (BwrStyle.ROW - 8) / 2, type, st == Status.NONE ? type.colour : st.colour);
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            if (!contains(mx, my)) {
                return List.of();
            }
            List<Component> explicit = super.tooltipAt(mx, my);
            if (!explicit.isEmpty()) {
                return explicit;
            }
            return cut ? List.of(Component.literal(last)) : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            Status st = status.get();
            String s = text.get();
            out.add(st == Status.NONE ? s : s + ", " + st.label.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** A wrapped paragraph in caption type (or body type), optionally with a status icon, a colour and a line limit. */
    public static final class Note extends UiElement {
        private final Supplier<String> text;
        private Type type = Type.CAPTION;
        private Supplier<Status> status = () -> Status.NONE;
        private int maxLines;
        private String cachedText;
        private int cachedWidth = -1;
        private List<String> lines = List.of();

        Note(Supplier<String> text) {
            this.text = text;
        }

        public Note body() {
            this.type = Type.BODY;
            return this;
        }

        public Note status(Supplier<Status> status) {
            this.status = status;
            return this;
        }

        /** Cuts the paragraph to {@code n} lines, the last ending in an ellipsis; the tooltip then carries the whole text. */
        public Note maxLines(int n) {
            this.maxLines = n;
            return this;
        }

        private int indent() {
            return status.get() == Status.NONE ? 0 : BwrStyle.ICON + BwrStyle.SP1;
        }

        private List<String> wrapped(int width) {
            String s = text.get();
            int inner = width - indent();
            if (!s.equals(cachedText) || inner != cachedWidth) {
                cachedText = s;
                cachedWidth = inner;
                lines = Ui.wrapStrings(s, inner, type, maxLines);
            }
            return lines;
        }

        @Override
        protected int measure(int width) {
            return wrapped(width).size() * BwrStyle.LINE;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Status st = status.get();
            int colour = st == Status.NONE ? type.colour : st.colour;
            int indent = indent();
            if (st != Status.NONE) {
                Ui.statusIcon(g, x, y + 1, st);
            }
            List<String> ls = wrapped(w);
            for (int i = 0; i < ls.size(); i++) {
                Ui.text(g, ls.get(i), x + indent, y + i * BwrStyle.LINE + 1, type, colour);
            }
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            if (!contains(mx, my)) {
                return List.of();
            }
            List<Component> explicit = super.tooltipAt(mx, my);
            if (!explicit.isEmpty()) {
                return explicit;
            }
            if (maxLines > 0 && Ui.wrapStrings(text.get(), w - indent(), type, 0).size() > maxLines) {
                return List.of(Component.literal(text.get()));
            }
            return List.of();
        }

        @Override
        public void narrate(List<String> out) {
            Status st = status.get();
            String s = text.get();
            out.add(st == Status.NONE ? s : st.label.toLowerCase(java.util.Locale.ROOT) + ": " + s);
        }
    }

    /** An icon, a word and optional detail: a state a reader should see at a glance ("Running", "Computer holds control"). */
    public static final class StatusLine extends UiElement {
        private final Supplier<Status> status;
        private final Supplier<String> text;
        private final Supplier<String> right;
        private Supplier<Status> rightStatus = () -> Status.NONE;
        private String lastText = "";
        private String lastRight = "";
        private boolean cut;

        StatusLine(Supplier<Status> status, Supplier<String> text, Supplier<String> right) {
            this.status = status;
            this.text = text;
            this.right = right;
        }

        /** Gives the right-hand text a status of its own (colour and icon), such as a SCRAM next to the vessel state. */
        public StatusLine rightStatus(Supplier<Status> rightStatus) {
            this.rightStatus = rightStatus;
            return this;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Status st = status.get();
            String t = text.get();
            String r = right == null ? "" : right.get();
            lastText = t;
            lastRight = r;
            int ty = y + (BwrStyle.ROW - 8) / 2;
            int left = x;
            if (st != Status.NONE) {
                Ui.statusIcon(g, x, y + (BwrStyle.ROW - BwrStyle.ICON) / 2, st);
                left += BwrStyle.ICON + BwrStyle.SP1;
            }
            Status rs = rightStatus.get();
            int rightIcon = rs == Status.NONE || r.isEmpty() ? 0 : BwrStyle.ICON + BwrStyle.SP1;
            int rightW = r.isEmpty() ? 0 : Math.min(Ui.width(r, Type.CAPTION) + rightIcon, w / 2);
            String shownRight = Ui.fit(r, Type.CAPTION, Math.max(0, rightW - rightIcon));
            String shownText = Ui.fit(t, Type.BODY, x + w - left - (rightW > 0 ? rightW + BwrStyle.SP2 : 0));
            cut = !shownRight.equals(r) || !shownText.equals(t);
            Ui.text(g, shownText, left, ty, Type.BODY, st == Status.NONE ? BwrStyle.TEXT_PRIMARY : st.colour);
            if (rightW > 0) {
                Ui.textRight(g, shownRight, x + w, ty, Type.CAPTION, rs == Status.NONE ? BwrStyle.TEXT_SECONDARY : rs.colour);
                if (rightIcon > 0) {
                    Ui.statusIcon(g, x + w - Ui.width(shownRight, Type.CAPTION) - BwrStyle.SP1 - BwrStyle.ICON,
                            y + (BwrStyle.ROW - BwrStyle.ICON) / 2, rs);
                }
            }
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            if (!contains(mx, my)) {
                return List.of();
            }
            List<Component> explicit = super.tooltipAt(mx, my);
            if (!explicit.isEmpty()) {
                return explicit;
            }
            return cut ? List.of(Component.literal(lastRight.isEmpty() ? lastText : lastText + " - " + lastRight)) : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            Status st = status.get();
            String t = text.get();
            String r = right == null ? "" : right.get();
            String s = (st == Status.NONE ? t : t + ", " + st.label.toLowerCase(java.util.Locale.ROOT)) + (r.isEmpty() ? "" : ", " + r);
            out.add(s);
        }
    }

    // ---------------------------------------------------------------
    // Meter
    // ---------------------------------------------------------------

    /**
     * A horizontal bar meter: an inset trough with quarter ticks, a fill, and the value as text at the right. The fill
     * takes the status colour when there is one and the accent colour otherwise; the status icon sits before the text.
     */
    public static final class Meter extends UiElement {
        private final String name;
        private final DoubleSupplier fraction;
        private final Supplier<Status> status;
        private final Supplier<String> text;
        private String label = "";

        Meter(String name, DoubleSupplier fraction, Supplier<Status> status, Supplier<String> text) {
            this.name = name;
            this.fraction = fraction;
            this.status = status == null ? () -> Status.NONE : status;
            this.text = text;
        }

        /** A short label at the left of the bar. */
        public Meter label(String label) {
            this.label = label;
            return this;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            double f = Math.max(0.0, Math.min(1.0, fraction.getAsDouble()));
            if (Double.isNaN(f)) {
                f = 0.0;
            }
            Status st = status.get();
            String t = text == null ? "" : text.get();
            int textW = t.isEmpty() ? 0 : Ui.width(t, Type.BODY);
            int iconW = st == Status.NONE ? 0 : BwrStyle.ICON + BwrStyle.SP1;
            int labelW = label.isEmpty() ? 0 : Ui.width(label, Type.BODY) + BwrStyle.SP2;
            int bx = x + labelW;
            int barW = Math.max(16, w - labelW - (textW > 0 ? textW + BwrStyle.SP2 : 0) - iconW);
            int by = y + (BwrStyle.ROW - BwrStyle.METER) / 2;
            if (!label.isEmpty()) {
                Ui.text(g, label, x, y + (BwrStyle.ROW - 8) / 2, Type.BODY, BwrStyle.TEXT_SECONDARY);
            }
            Ui.well(g, bx, by, barW, BwrStyle.METER);
            int inner = barW - 2;
            int filled = (int) Math.round(f * inner);
            g.fill(bx + 1, by + 1, bx + 1 + filled, by + BwrStyle.METER - 1, st == Status.NONE ? BwrStyle.ACCENT : st.colour);
            for (int q = 1; q < 4; q++) {
                int tx = bx + 1 + inner * q / 4;
                g.fill(tx, by + 1, tx + 1, by + 3, 0x66000000);
            }
            if (st != Status.NONE) {
                Ui.statusIcon(g, bx + barW + BwrStyle.SP1, y + (BwrStyle.ROW - BwrStyle.ICON) / 2, st);
            }
            if (!t.isEmpty()) {
                Ui.textRight(g, t, x + w, y + (BwrStyle.ROW - 8) / 2, Type.BODY, st == Status.NONE ? BwrStyle.TEXT_PRIMARY : st.colour);
            }
        }

        @Override
        public void narrate(List<String> out) {
            Status st = status.get();
            String t = text == null ? "" : text.get();
            out.add(name + (t.isEmpty() ? "" : ", " + t) + (st == Status.NONE ? "" : ", " + st.label.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    // ---------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------

    /** A row of widgets (and an optional leading label), sharing the width by weight. */
    public static final class Controls extends UiElement {
        private record Item(AbstractWidget widget, int weight, int fixed, BooleanSupplier shown) {
        }

        private final int gap;
        private final List<Item> items = new ArrayList<>();
        private Supplier<String> label;
        private int labelWidth;

        Controls(int gap) {
            this.gap = gap;
        }

        /** A text label before the widgets, {@code px} wide. */
        public Controls label(Supplier<String> text, int px) {
            this.label = text;
            this.labelWidth = px;
            return this;
        }

        public Controls label(String text, int px) {
            return label(() -> text, px);
        }

        public Controls add(AbstractWidget widget, int weight) {
            items.add(new Item(widget, weight, 0, () -> true));
            return this;
        }

        public Controls add(AbstractWidget widget, int weight, BooleanSupplier shown) {
            items.add(new Item(widget, weight, 0, shown));
            return this;
        }

        public Controls fixed(AbstractWidget widget, int px) {
            items.add(new Item(widget, 0, px, () -> true));
            return this;
        }

        public Controls fixed(AbstractWidget widget, int px, BooleanSupplier shown) {
            items.add(new Item(widget, 0, px, shown));
            return this;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.CONTROL;
        }

        /** Fixed-width items only (a row of small buttons in the title bar): their widths and gaps; otherwise all the room. */
        @Override
        public int preferredWidth(int available) {
            int total = 0;
            int n = 0;
            for (Item it : items) {
                if (it.shown.getAsBoolean()) {
                    if (it.fixed == 0) {
                        return available;
                    }
                    total += it.fixed;
                    n++;
                }
            }
            return total + Math.max(0, n - 1) * gap;
        }

        @Override
        protected void layout() {
            int left = x + (label == null ? 0 : labelWidth + BwrStyle.SP1);
            int avail = x + w - left;
            int n = 0;
            int fixed = 0;
            int weights = 0;
            for (Item it : items) {
                if (it.shown.getAsBoolean()) {
                    n++;
                    fixed += it.fixed;
                    weights += it.weight;
                }
            }
            int free = Math.max(0, avail - fixed - Math.max(0, n - 1) * gap);
            int used = 0;
            int seen = 0;
            int cx = left;
            for (Item it : items) {
                if (!it.shown.getAsBoolean()) {
                    it.widget.visible = false;
                    continue;
                }
                seen++;
                int width;
                if (it.fixed > 0) {
                    width = it.fixed;
                } else {
                    boolean lastWeighted = true;
                    for (int j = items.indexOf(it) + 1; j < items.size(); j++) {
                        if (items.get(j).shown.getAsBoolean() && items.get(j).fixed == 0) {
                            lastWeighted = false;
                        }
                    }
                    width = lastWeighted ? free - used : free * it.weight / Math.max(1, weights);
                    used += width;
                }
                Placeable.place(it.widget, cx, y, Math.max(8, width), Math.min(BwrStyle.CONTROL, Math.max(BwrStyle.TAB, h)));
                it.widget.visible = true;
                cx += width + gap;
            }
        }

        @Override
        protected void hide() {
            items.forEach(it -> it.widget.visible = false);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            if (label != null) {
                Ui.text(g, Ui.fit(label.get(), Type.BODY, labelWidth), x, y + (BwrStyle.CONTROL - 8) / 2, Type.BODY, BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            items.forEach(it -> out.accept(it.widget));
        }

        @Override
        public void narrate(List<String> out) {
            // the widgets narrate themselves
        }
    }

    /** A strip of tabs over a rule; the selected tab is raised. */
    public static final class Tabs extends UiElement {
        private final List<BwrTab> tabs = new ArrayList<>();
        private final String[] labels;

        Tabs(String[] labels, String[] tips, IntSupplier selected, IntConsumer onSelect) {
            this.labels = labels;
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                tabs.add(new BwrTab(labels[i], tips[i], () -> selected.getAsInt() == index, b -> onSelect.accept(index), dir -> {
                    int next = Math.floorMod(index + dir, labels.length);
                    onSelect.accept(next);
                    BwrTab.focus(tabs.get(next));
                }));
            }
        }

        public List<BwrTab> tabs() {
            return tabs;
        }

        @Override
        public int preferredWidth(int available) {
            int total = labels.length - 1;
            for (String label : labels) {
                total += Math.max(44, Ui.width(label, Type.BODY) + BwrStyle.SP4);
            }
            return total;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.TAB;
        }

        @Override
        protected void layout() {
            int[] ws = new int[tabs.size()];
            int total = 0;
            for (int i = 0; i < ws.length; i++) {
                ws[i] = Math.max(44, Ui.width(labels[i], Type.BODY) + BwrStyle.SP4);
                total += ws[i];
            }
            total += (ws.length - 1);
            int cx = x;
            double squeeze = total > w ? (double) (w - (ws.length - 1)) / (total - (ws.length - 1)) : 1.0;
            for (int i = 0; i < ws.length; i++) {
                int tw = (int) Math.floor(ws[i] * squeeze);
                Placeable.place(tabs.get(i), cx, y, tw, BwrStyle.TAB);
                tabs.get(i).visible = true;
                cx += tw + 1;
            }
        }

        @Override
        protected void hide() {
            tabs.forEach(t -> t.visible = false);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Ui.rule(g, x, y + BwrStyle.TAB - 1, w);
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            tabs.forEach(out);
        }
    }

    /** A colour scale for a map: a gradient of swatches with a caption at each end. */
    public static final class Legend extends UiElement {
        private static final int STEPS = 32;
        private final Supplier<String> low;
        private final Supplier<String> high;
        private final java.util.function.DoubleToIntFunction colourAt;

        Legend(Supplier<String> low, Supplier<String> high, java.util.function.DoubleToIntFunction colourAt) {
            this.low = low;
            this.high = high;
            this.colourAt = colourAt;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            String l = low.get();
            String hi = high.get();
            int lw = Ui.width(l, Type.CAPTION);
            int hw = Ui.width(hi, Type.CAPTION);
            int ty = y + (BwrStyle.ROW - 8) / 2;
            Ui.text(g, l, x, ty, Type.CAPTION);
            Ui.textRight(g, hi, x + w, ty, Type.CAPTION, BwrStyle.TEXT_SECONDARY);
            int bx = x + lw + BwrStyle.SP2;
            int bw = Math.max(16, w - lw - hw - 2 * BwrStyle.SP2);
            int by = y + (BwrStyle.ROW - BwrStyle.SP2) / 2;
            Ui.frame(g, bx - 1, by - 1, bw + 2, BwrStyle.SP2 + 2, BwrStyle.BORDER);
            for (int i = 0; i < STEPS; i++) {
                int x0 = bx + bw * i / STEPS;
                int x1 = bx + bw * (i + 1) / STEPS;
                g.fill(x0, by, x1, by + BwrStyle.SP2, colourAt.applyAsInt(i / (STEPS - 1.0)));
            }
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Colour scale from " + low.get() + " to " + high.get());
        }
    }

    /** A status icon and word, sized to its text. */
    public static final class Chip extends UiElement {
        private final Supplier<Status> status;
        private final Supplier<String> text;

        Chip(Supplier<Status> status, Supplier<String> text) {
            this.status = status;
            this.text = text;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW;
        }

        @Override
        public int preferredWidth(int available) {
            Status st = status.get();
            return Ui.width(text.get(), Type.CAPTION) + (st == Status.NONE ? 0 : BwrStyle.ICON + BwrStyle.SP1);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            Status st = status.get();
            int left = x;
            if (st != Status.NONE) {
                Ui.statusIcon(g, x, y + (h - BwrStyle.ICON) / 2, st);
                left += BwrStyle.ICON + BwrStyle.SP1;
            }
            Ui.text(g, text.get(), left, y + (h - 8) / 2, Type.CAPTION, st == Status.NONE ? BwrStyle.TEXT_SECONDARY : st.colour);
        }

        @Override
        public void narrate(List<String> out) {
            out.add(text.get());
        }
    }

    /** The items of the title bar, placed from the right edge leftwards, each at its preferred width. */
    public static final class TitleItems extends UiElement {
        private final List<UiElement> items = new ArrayList<>();

        TitleItems(UiElement... items) {
            this.items.addAll(List.of(items));
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.TITLE_BAR - 4;
        }

        @Override
        public int preferredWidth(int available) {
            int total = 0;
            int n = 0;
            for (UiElement item : items) {
                if (item.isVisible()) {
                    total += item.preferredWidth(available);
                    n++;
                }
            }
            return total + Math.max(0, n - 1) * BwrStyle.SP2;
        }

        @Override
        protected void layout() {
            int right = x + w;
            for (int i = items.size() - 1; i >= 0; i--) {
                UiElement item = items.get(i);
                if (!item.isVisible()) {
                    item.hideTree();
                    continue;
                }
                int pw = item.preferredWidth(w);
                right -= pw;
                item.place(right, y, pw, h);
                right -= BwrStyle.SP2;
            }
        }

        @Override
        protected void hide() {
            items.forEach(UiElement::hideTree);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            for (UiElement item : items) {
                if (item.isVisible()) {
                    item.render(g, mx, my);
                }
            }
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            items.forEach(i -> i.collectWidgets(out));
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            for (UiElement item : items) {
                List<Component> t = item.tooltipAt(mx, my);
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return List.of();
        }

        @Override
        public void narrate(List<String> out) {
            items.forEach(i -> i.narrate(out));
        }
    }

    /** One widget that fills the box it is given (at least {@code minHeight} high). Flexible. */
    public static final class Fill extends UiElement {
        private final AbstractWidget widget;
        private final int minHeight;

        Fill(AbstractWidget widget, int minHeight) {
            this.widget = widget;
            this.minHeight = minHeight;
        }

        @Override
        protected int measure(int width) {
            return minHeight;
        }

        @Override
        public boolean flexible() {
            return true;
        }

        @Override
        protected void layout() {
            Placeable.place(widget, x, y, w, h);
            widget.visible = true;
        }

        @Override
        protected void hide() {
            widget.visible = false;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
        }

        @Override
        public void collectWidgets(Consumer<AbstractWidget> out) {
            out.accept(widget);
        }
    }
}
