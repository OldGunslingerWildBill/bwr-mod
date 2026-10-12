package dev.bwr.mod.panel.display;

import java.util.function.IntUnaryOperator;

/** Shared text-to-digit layout for the segment displays (pure). */
public final class SegmentText {
    private SegmentText() {
    }

    /**
     * Lays {@code text} out on {@code cells} digits. Each character takes a digit, except a {@code .}, which lights
     * the decimal point of the digit before it (a leading {@code .}, or one after a digit whose point is already lit,
     * takes a blank digit of its own). Text longer than the display is cut at the right; shorter text is padded
     * with blanks on the left ({@code alignRight}) or on the right.
     *
     * @param encoder maps a character to its segment mask
     * @param dpBit   the decimal point bit of the display
     */
    public static int[] layout(String text, int cells, boolean alignRight, IntUnaryOperator encoder, int dpBit) {
        int[] out = new int[cells];
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean pointFree = used > 0 && (out[used - 1] & dpBit) == 0;
            if (c == '.' && pointFree) {
                out[used - 1] |= dpBit;
            } else if (used < cells) {
                out[used++] = c == '.' ? dpBit : encoder.applyAsInt(c);
            } else {
                break;
            }
        }
        if (alignRight && used < cells) {
            int shift = cells - used;
            System.arraycopy(out, 0, out, shift, used);
            java.util.Arrays.fill(out, 0, shift, 0);
        }
        return out;
    }

    /** Fits {@code text} to {@code width} characters (cut at the right, padded with spaces at the left or right). */
    public static String fit(String text, int width, boolean alignRight) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        String pad = " ".repeat(width - text.length());
        return alignRight ? pad + text : text + pad;
    }
}
