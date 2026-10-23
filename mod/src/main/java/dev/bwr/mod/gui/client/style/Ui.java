package dev.bwr.mod.gui.client.style;

import dev.bwr.mod.gui.client.style.BwrStyle.Shape;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The drawing primitives of the style: panel, card, rule, well, status icon and typed text, all built from the
 * {@link BwrStyle} tokens. Everything is plain fills, so it is crisp at every GUI scale and needs no texture.
 */
public final class Ui {
    private Ui() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // ---------------------------------------------------------------
    // Text probe (test harness only): records where every string of the style is drawn
    // ---------------------------------------------------------------

    /** One drawn string: where, how large, whether a scissor clipped it (scrolled content), and the text. */
    public record DrawnText(int x, int y, int width, int height, boolean clipped, String text) {
    }

    private static boolean probing;
    private static int clipDepth;
    private static final List<DrawnText> PROBE = new ArrayList<>();

    /** Starts recording drawn text (the GUI check turns this on; nothing else does). */
    public static void startProbe() {
        PROBE.clear();
        probing = true;
    }

    /** Stops recording and returns what was drawn since {@link #startProbe}. */
    public static List<DrawnText> endProbe() {
        probing = false;
        return new ArrayList<>(PROBE);
    }

    /** Marks the start (true) or end (false) of drawing inside a scissor, so the probe knows the text may be clipped. */
    public static void clipped(boolean on) {
        clipDepth += on ? 1 : -1;
    }

    // ---------------------------------------------------------------
    // Batching
    // ---------------------------------------------------------------

    private static int batchDepth;
    private static boolean legacy;

    /**
     * Draws the way the style did before drawing was batched and its text cached: one draw call per fill and per string,
     * every string shaped and measured afresh. The pictures are the same; the GUI check turns this on to prove it by
     * drawing the reactor panel both ways and comparing the pixels.
     */
    public static void setLegacyDrawing(boolean on) {
        legacy = on;
        clearTextCache();
    }

    /** True while {@link #setLegacyDrawing} is on. */
    public static boolean legacyDrawing() {
        return legacy;
    }
    private static FormattedCharSequence[] queuedText = new FormattedCharSequence[128];
    private static Matrix4f[] queuedPose = new Matrix4f[128];
    private static int[] queuedX = new int[128];
    private static int[] queuedY = new int[128];
    private static int[] queuedColour = new int[128];
    private static int[] queuedScale = new int[128];
    private static int queued;
    private static final Matrix4f SCRATCH = new Matrix4f();

    /**
     * Runs {@code drawing} with the GUI's vertex buffer held open, so that fills and text reach the GPU together instead
     * of one draw call each ({@code GuiGraphics} flushes after every fill and every string otherwise, which made a
     * control panel cost a couple of hundred draw calls a frame). Inside it {@link #text} is held back and drawn after the
     * fills, at the end of the batch or just before a scissor changes ({@link #enableScissor}, {@link #disableScissor}),
     * so that a run of strings is one draw call rather than one for every string between two fills (each string keeps the
     * pose it was drawn under). That puts text over
     * every fill drawn in the same batch: a screen that batches must not draw a fill over its own text. Safe to nest:
     * only the outermost call opens and closes the batch.
     */
    public static void batch(GuiGraphics g, Runnable drawing) {
        if (batchDepth > 0) {
            drawing.run();
            return;
        }
        batchDepth++;
        try {
            g.drawManaged(() -> {
                drawing.run();
                drawQueuedText(g);
            });
        } finally {
            batchDepth--;
            discardQueuedText();
        }
    }

    /** {@link GuiGraphics#enableScissor}, after the text held back so far (it belongs outside the new scissor). */
    public static void enableScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        drawQueuedText(g);
        g.enableScissor(x1, y1, x2, y2);
    }

    /** {@link GuiGraphics#disableScissor}, after the text held back so far (it belongs inside the scissor being left). */
    public static void disableScissor(GuiGraphics g) {
        drawQueuedText(g);
        g.disableScissor();
    }

    private static void queueText(GuiGraphics g, FormattedCharSequence text, int x, int y, int scale, int colour) {
        if (queued == queuedText.length) {
            int n = queued * 2;
            queuedText = java.util.Arrays.copyOf(queuedText, n);
            queuedPose = java.util.Arrays.copyOf(queuedPose, n);
            queuedX = java.util.Arrays.copyOf(queuedX, n);
            queuedY = java.util.Arrays.copyOf(queuedY, n);
            queuedColour = java.util.Arrays.copyOf(queuedColour, n);
            queuedScale = java.util.Arrays.copyOf(queuedScale, n);
        }
        queuedText[queued] = text;
        if (queuedPose[queued] == null) {
            queuedPose[queued] = new Matrix4f();
        }
        queuedPose[queued].set(g.pose().last().pose());
        queuedX[queued] = x;
        queuedY[queued] = y;
        queuedColour[queued] = colour;
        queuedScale[queued] = scale;
        queued++;
    }

    private static void drawQueuedText(GuiGraphics g) {
        int n = queued;
        queued = 0;
        Font font = font();
        var buffers = g.bufferSource();
        for (int i = 0; i < n; i++) {
            // the same call GuiGraphics.drawString makes, with the pose the string was queued under
            Matrix4f pose = queuedPose[i];
            int scale = queuedScale[i];
            if (scale == 1) {
                font.drawInBatch(queuedText[i], (float) queuedX[i], (float) queuedY[i], queuedColour[i], false, pose, buffers,
                        Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            } else {
                SCRATCH.set(pose).translate(queuedX[i], queuedY[i], 0).scale(scale, scale, 1);
                font.drawInBatch(queuedText[i], 0f, 0f, queuedColour[i], false, SCRATCH, buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            }
            queuedText[i] = null;
        }
    }

    private static final int FULL_BRIGHT = 15728880;

    private static void discardQueuedText() {
        java.util.Arrays.fill(queuedText, 0, queued, null);
        queued = 0;
    }

    private static void drawNow(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int scale, int colour) {
        if (scale == 1) {
            g.drawString(font, text, x, y, colour, false);
            return;
        }
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, colour, false);
        pose.popPose();
    }

    // ---------------------------------------------------------------
    // Surfaces
    // ---------------------------------------------------------------

    /** A one pixel frame inside {@code (x, y, w, h)}. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y + 1, x + 1, y + h - 1, colour);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }

    /** The window: a strong frame, the panel colour and a title bar of {@link BwrStyle#TITLE_BAR} px. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BwrStyle.PANEL);
        g.fill(x + 1, y + 1, x + w - 1, y + BwrStyle.TITLE_BAR, BwrStyle.TITLEBAR);
        g.fill(x + 1, y + BwrStyle.TITLE_BAR, x + w - 1, y + BwrStyle.TITLE_BAR + 1, BwrStyle.BORDER);
        frame(g, x, y, w, h, BwrStyle.BORDER_STRONG);
    }

    /** A card: the surface colour with a quiet border. */
    public static void card(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BwrStyle.SURFACE);
        frame(g, x, y, w, h, BwrStyle.BORDER);
    }

    /** A recessed well, for troughs, fields and the core map. */
    public static void well(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BwrStyle.INSET);
        frame(g, x, y, w, h, BwrStyle.BORDER);
    }

    /** A horizontal rule. */
    public static void rule(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, BwrStyle.RULE);
    }

    /** The background of one 16 x 16 item slot whose item is drawn at {@code (x, y)}: an 18 x 18 bevelled well. */
    public static void slotWell(GuiGraphics g, int x, int y) {
        int left = x - 1;
        int top = y - 1;
        g.fill(left, top, left + 18, top + 18, BwrStyle.INSET);
        g.fill(left, top, left + 18, top + 1, BwrStyle.BORDER_STRONG);
        g.fill(left, top, left + 1, top + 18, BwrStyle.BORDER_STRONG);
        g.fill(left, top + 17, left + 18, top + 18, BwrStyle.RULE);
        g.fill(left + 17, top, left + 18, top + 18, BwrStyle.RULE);
    }

    /** A keyboard focus ring: a white frame one pixel outside the control, over a dark one so it reads on any surface. */
    public static void focusRing(GuiGraphics g, int x, int y, int w, int h) {
        frame(g, x - 1, y - 1, w + 2, h + 2, BwrStyle.FOCUS);
        frame(g, x, y, w, h, BwrStyle.INSET);
    }

    // ---------------------------------------------------------------
    // Status icon
    // ---------------------------------------------------------------

    /**
     * A 7 x 7 icon whose silhouette identifies the status without colour: circle (normal), triangle (caution),
     * diamond (alarm), square (info), ring (off).
     */
    public static void statusIcon(GuiGraphics g, int x, int y, Status status) {
        Shape shape = status.shape;
        int c = status.colour;
        switch (shape) {
            case CIRCLE -> {
                g.fill(x + 2, y, x + 5, y + 1, c);
                g.fill(x + 1, y + 1, x + 6, y + 2, c);
                g.fill(x, y + 2, x + 7, y + 5, c);
                g.fill(x + 1, y + 5, x + 6, y + 6, c);
                g.fill(x + 2, y + 6, x + 5, y + 7, c);
            }
            case TRIANGLE -> {
                g.fill(x + 3, y, x + 4, y + 1, c);
                g.fill(x + 2, y + 1, x + 5, y + 2, c);
                g.fill(x + 2, y + 2, x + 5, y + 3, c);
                g.fill(x + 1, y + 3, x + 6, y + 5, c);
                g.fill(x, y + 5, x + 7, y + 7, c);
            }
            case DIAMOND -> {
                g.fill(x + 3, y, x + 4, y + 1, c);
                g.fill(x + 2, y + 1, x + 5, y + 2, c);
                g.fill(x + 1, y + 2, x + 6, y + 3, c);
                g.fill(x, y + 3, x + 7, y + 4, c);
                g.fill(x + 1, y + 4, x + 6, y + 5, c);
                g.fill(x + 2, y + 5, x + 5, y + 6, c);
                g.fill(x + 3, y + 6, x + 4, y + 7, c);
            }
            case SQUARE -> g.fill(x + 1, y + 1, x + 6, y + 6, c);
            case RING -> {
                g.fill(x + 2, y, x + 5, y + 1, c);
                g.fill(x + 1, y + 1, x + 2, y + 2, c);
                g.fill(x + 5, y + 1, x + 6, y + 2, c);
                g.fill(x, y + 2, x + 1, y + 5, c);
                g.fill(x + 6, y + 2, x + 7, y + 5, c);
                g.fill(x + 1, y + 5, x + 2, y + 6, c);
                g.fill(x + 5, y + 5, x + 6, y + 6, c);
                g.fill(x + 2, y + 6, x + 5, y + 7, c);
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------

    /**
     * A label that was written in capitals, as the style shows it: in sentence case ("LOAD" shows as "Load", "K-INF" as
     * "K-inf"). The label itself, which a screen reader says and a lookup matches, is not changed. Other text is returned as it is.
     */
    public static String sentence(String s) {
        if (legacy) {
            return sentenceUncached(s);
        }
        String hit = SENTENCES.get(s);
        if (hit == null) {
            hit = sentenceUncached(s);
            if (SENTENCES.size() >= SHAPED_LIMIT) {
                SENTENCES.clear();
            }
            SENTENCES.put(s, hit);
        }
        return hit;
    }

    private static String sentenceUncached(String s) {
        if (s.length() > 1 && s.chars().anyMatch(Character::isLetter) && s.equals(s.toUpperCase(Locale.ROOT))) {
            return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
        }
        return s;
    }

    /** The string as the type shows it (the header type is upper case). */
    public static String styled(String s, Type type) {
        return type.upper ? s.toUpperCase(Locale.ROOT) : s;
    }

    /** One string as the style draws it: its glyph sequence and that sequence's width at scale 1. */
    private record Shaped(FormattedCharSequence sequence, int width) {
    }

    /** Most strings a type keeps shaped, and most sentence-case labels kept: a screen shows well under a hundred of either, so this only stops a leak. */
    private static final int SHAPED_LIMIT = 1024;
    private static final java.util.HashMap<String, String> SENTENCES = new java.util.HashMap<>();
    @SuppressWarnings("unchecked")
    private static final java.util.HashMap<String, Shaped>[] SHAPED = new java.util.HashMap[Type.values().length];
    private static Object shapedLanguage;
    private static Font shapedFont;

    static {
        for (int i = 0; i < SHAPED.length; i++) {
            SHAPED[i] = new java.util.HashMap<>();
        }
    }

    /**
     * Forgets every shaped string. Building a glyph sequence (bidirectional reordering) and measuring it is most of the
     * cost of laying text out, and a panel draws the same few dozen strings every frame, so they are kept; a font or a
     * language change (a resource reload) makes them stale.
     */
    public static void clearTextCache() {
        for (var map : SHAPED) {
            map.clear();
        }
        SENTENCES.clear();
        shapedLanguage = null;
        shapedFont = null;
    }

    private static Shaped shape(String s, Type type, Font font) {
        Component c = Component.literal(styled(s, type));
        FormattedCharSequence sequence = (type.bold ? c.copy().withStyle(ChatFormatting.BOLD) : c).getVisualOrderText();
        return new Shaped(sequence, font.width(sequence));
    }

    private static Shaped shaped(String s, Type type) {
        Font font = font();
        if (legacy) {
            return shape(s, type, font);
        }
        Object language = Language.getInstance();
        if (language != shapedLanguage || font != shapedFont) {
            clearTextCache();
            shapedLanguage = language;
            shapedFont = font;
        }
        var map = SHAPED[type.ordinal()];
        Shaped hit = map.get(s);
        if (hit == null) {
            if (map.size() >= SHAPED_LIMIT) {
                map.clear();
            }
            hit = shape(s, type, font);
            map.put(s, hit);
        }
        return hit;
    }

    private static FormattedCharSequence sequence(String s, Type type) {
        return shaped(s, type).sequence();
    }

    public static int width(String s, Type type) {
        return shaped(s, type).width() * type.scale;
    }

    public static int height(Type type) {
        return font().lineHeight * type.scale;
    }

    /** Draws {@code s} at {@code (x, y)} (top-left) in the type's own colour. */
    public static void text(GuiGraphics g, String s, int x, int y, Type type) {
        text(g, s, x, y, type, type.colour);
    }

    public static void text(GuiGraphics g, String s, int x, int y, Type type, int colour) {
        text(g, s, x, y, type, colour, 1);
    }

    /**
     * Draws {@code s} enlarged by a whole {@code zoom} on top of the type's own scale, with {@code (x, y)} the top-left in
     * screen pixels (so the probe sees where the text really is). The guide's book is laid out at a fixed design size and
     * drawn zoomed to fit the window; text zoomed by a whole number stays crisp.
     */
    public static void text(GuiGraphics g, String s, int x, int y, Type type, int colour, int zoom) {
        if (probing && !s.isEmpty()) {
            PROBE.add(new DrawnText(x, y, width(s, type) * zoom, height(type) * zoom, clipDepth > 0, s));
        }
        FormattedCharSequence sequence = sequence(s, type);
        if (batchDepth > 0) {
            queueText(g, sequence, x, y, type.scale * zoom, colour);
            return;
        }
        drawNow(g, font(), sequence, x, y, type.scale * zoom, colour);
    }

    /** Right-aligns {@code s} so that it ends at {@code right}. */
    public static void textRight(GuiGraphics g, String s, int right, int y, Type type, int colour) {
        text(g, s, right - width(s, type), y, type, colour);
    }

    /** Centres {@code s} on {@code cx}. */
    public static void textCentred(GuiGraphics g, String s, int cx, int y, Type type, int colour) {
        text(g, s, cx - width(s, type) / 2, y, type, colour);
    }

    /** Whether {@code s} would be cut by {@link #fit}. */
    public static boolean overflows(String s, Type type, int maxWidth) {
        return width(s, type) > maxWidth;
    }

    /** {@code s} cut to {@code maxWidth} with an ellipsis when it does not fit. */
    public static String fit(String s, Type type, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (width(s, type) <= maxWidth) {
            return s;
        }
        String dots = "...";
        int budget = maxWidth - width(dots, type);
        if (budget <= 0) {
            return "";
        }
        Font font = font();
        String cut = font.plainSubstrByWidth(styled(s, type), budget / type.scale);
        // plainSubstrByWidth measures the regular weight; trim again for bold.
        while (!cut.isEmpty() && width(cut, type) > budget) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + dots;
    }

    /**
     * Word-wraps {@code s} to {@code width} as plain strings (a newline starts a new line). With {@code maxLines} above zero
     * the text is cut to that many lines and the last one ends in an ellipsis. Never empty.
     */
    public static List<String> wrapStrings(String s, int width, Type type, int maxLines) {
        List<String> out = new ArrayList<>();
        int limit = Math.max(8, width);
        for (String paragraph : s.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String trial = current.length() == 0 ? word : current + " " + word;
                if (width(trial, type) <= limit) {
                    current = new StringBuilder(trial);
                } else if (current.length() == 0) {
                    String rest = word;
                    while (width(rest, type) > limit) {
                        String part = font().plainSubstrByWidth(rest, limit / type.scale);
                        if (part.isEmpty()) {
                            break;
                        }
                        out.add(part);
                        rest = rest.substring(part.length());
                    }
                    current = new StringBuilder(rest);
                } else {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
            out.add(current.toString());
        }
        if (maxLines > 0 && out.size() > maxLines) {
            StringBuilder tail = new StringBuilder(out.get(maxLines - 1));
            for (int i = maxLines; i < out.size(); i++) {
                tail.append(' ').append(out.get(i));
            }
            List<String> cut = new ArrayList<>(out.subList(0, maxLines - 1));
            cut.add(fit(tail.toString(), type, limit));
            return cut;
        }
        return out;
    }

    /** Word-wraps {@code s} to {@code width}; never empty. */
    public static List<FormattedCharSequence> wrap(String s, int width) {
        List<FormattedCharSequence> lines = font().split(FormattedText.of(s), Math.max(8, width));
        return lines.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : lines;
    }
}
