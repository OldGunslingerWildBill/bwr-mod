package dev.bwr.mod.panel.display;

/**
 * Character map of a 7-segment digit (pure, no Minecraft dependency). A mask has
 * one bit per segment:
 * <pre>
 *    aaa          a = 1   b = 2   c = 4   d = 8
 *   f   b         e = 16  f = 32  g = 64  dp = 128
 *   f   b
 *    ggg
 *   e   c
 *   e   c
 *    ddd  dp
 * </pre>
 * It can show the digits and the letters {@code A b C d E F G H I J L n o P q r S t U y},
 * {@code -} and {@code _} (either case); every other character is blank, which is
 * the rule of CONTROL-PANELS-SPEC 2.1. A {@code .} is not a digit of its own: it lights the decimal point of
 * the digit before it (see {@link SegmentText#layout}).
 */
public final class SevenSegment {
    private SevenSegment() {
    }

    public static final int A = 1;
    public static final int B = 2;
    public static final int C = 4;
    public static final int D = 8;
    public static final int E = 16;
    public static final int F = 32;
    public static final int G = 64;
    public static final int DP = 128;

    private static final int[] DIGITS = {
            A | B | C | D | E | F,          // 0
            B | C,                          // 1
            A | B | D | E | G,              // 2
            A | B | C | D | G,              // 3
            B | C | F | G,                  // 4
            A | C | D | F | G,              // 5
            A | C | D | E | F | G,          // 6
            A | B | C,                      // 7
            A | B | C | D | E | F | G,      // 8
            A | B | C | D | F | G           // 9
    };

    /** Segment mask of {@code c}, 0 (blank) when a 7-segment digit cannot show it. */
    public static int mask(char c) {
        if (c >= '0' && c <= '9') {
            return DIGITS[c - '0'];
        }
        switch (Character.toUpperCase(c)) {
            case 'A':
                return A | B | C | E | F | G;
            case 'B':
                return C | D | E | F | G;
            case 'C':
                return A | D | E | F;
            case 'D':
                return B | C | D | E | G;
            case 'E':
                return A | D | E | F | G;
            case 'F':
                return A | E | F | G;
            case 'G':
                return A | C | D | E | F;
            case 'H':
                return B | C | E | F | G;
            case 'I':
                return E | F;
            case 'J':
                return B | C | D | E;
            case 'L':
                return D | E | F;
            case 'N':
                return C | E | G;
            case 'O':
                return C | D | E | G;
            case 'P':
                return A | B | E | F | G;
            case 'Q':
                return A | B | C | F | G;
            case 'R':
                return E | G;
            case 'S':
                return A | C | D | F | G;
            case 'T':
                return D | E | F | G;
            case 'U':
                return B | C | D | E | F;
            case 'Y':
                return B | C | D | F | G;
            case '-':
                return G;
            case '_':
                return D;
            default:
                return 0;
        }
    }

    /** Whether {@code c} lights at least one segment. A space is "supported" as blank. */
    public static boolean canShow(char c) {
        return c == ' ' || c == '.' || mask(c) != 0;
    }

    /** Lays {@code text} out on {@code digits} digits: see {@link SegmentText#layout}. */
    public static int[] layout(String text, int digits, boolean alignRight) {
        return SegmentText.layout(text, digits, alignRight, c -> mask((char) c), DP);
    }
}
