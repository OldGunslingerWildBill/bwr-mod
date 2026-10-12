package dev.bwr.mod.panel.display;

/**
 * Character map of a 16-segment alphanumeric digit (pure, no Minecraft
 * dependency). Segment letters, one bit each in order {@code a..p}:
 * <pre>
 *    a   b           a top left    b top right    c upper right   d lower right
 *   h k l m c        e bottom right f bottom left g lower left    h upper left
 *    i   j           i middle left j middle right k diagonal UL   l vertical top
 *   g p o n d        m diagonal UR n diagonal LR  o vertical bottom p diagonal LL
 *    f   e
 * </pre>
 * The decimal point is {@link #DP} (bit 16). All printable ASCII (32..126) is
 * covered; lowercase letters show as capitals. Anything else is blank.
 */
public final class SixteenSegment {
    private SixteenSegment() {
    }

    public static final int SEGMENTS = 16;
    public static final int DP = 1 << 16;

    /** Index 0 is ASCII 32; each entry lists the lit segment letters. */
    private static final String[] TABLE = new String[95];

    static {
        for (int i = 0; i < TABLE.length; i++) {
            TABLE[i] = "";
        }
        put(' ', "");
        put('!', "l");
        put('"', "lc");
        put('#', "ijlocdfe");
        put('$', "abhijdeflo");
        put('%', "hcmpgd");
        put('&', "ahkipgfn");
        put('\'', "l");
        put('(', "mn");
        put(')', "kp");
        put('*', "kmnpijlo");
        put('+', "ijlo");
        put(',', "p");
        put('-', "ij");
        put('.', "f");
        put('/', "mp");
        put('0', "abcdefgh");
        put('1', "cd");
        put('2', "abcjigfe");
        put('3', "abcdjef");
        put('4', "hijcd");
        put('5', "abhijdef");
        put('6', "abhgijdef");
        put('7', "abcd");
        put('8', "abcdefghij");
        put('9', "abchijdef");
        put(':', "lo");
        put(';', "lp");
        put('<', "mn");
        put('=', "ijfe");
        put('>', "kp");
        put('?', "abcjo");
        put('@', "abcdghjfe");
        put('A', "abcdhgij");
        put('B', "abcdeflojb");
        put('C', "abhgfe");
        put('D', "abcdefol");
        put('E', "abhgfei");
        put('F', "abhgi");
        put('G', "abhgfedj");
        put('H', "hgcdij");
        put('I', "abfelo");
        put('J', "cdefg");
        put('K', "hgimn");
        put('L', "hgfe");
        put('M', "ghcdkm");
        put('N', "ghcdkn");
        put('O', "abcdefgh");
        put('P', "abchgij");
        put('Q', "abcdefghn");
        put('R', "abchgijn");
        put('S', "abhijdef");
        put('T', "ablo");
        put('U', "hgfecd");
        put('V', "hgpm");
        put('W', "hgcdpn");
        put('X', "kmnp");
        put('Y', "kmo");
        put('Z', "abmpfe");
        put('[', "abhgfe");
        put('\\', "kn");
        put(']', "abcdfe");
        put('^', "pn");
        put('_', "fe");
        put('`', "k");
        put('{', "abihgfjl");
        put('|', "lo");
        put('}', "abjcdfil");
        put('~', "ipjm");
    }

    private static void put(char c, String segments) {
        TABLE[c - 32] = segments;
    }

    private static int bits(String segments) {
        int mask = 0;
        for (int i = 0; i < segments.length(); i++) {
            mask |= 1 << (segments.charAt(i) - 'a');
        }
        return mask;
    }

    private static final int[] MASKS = new int[95];

    static {
        for (int i = 0; i < MASKS.length; i++) {
            MASKS[i] = bits(TABLE[i]);
        }
    }

    /** Segment mask of {@code c} (lowercase shows as capital), 0 (blank) for a space or anything unprintable. */
    public static int mask(char c) {
        char upper = Character.toUpperCase(c);
        return upper >= 32 && upper <= 126 ? MASKS[upper - 32] : 0;
    }

    /** Whether the display has a glyph for {@code c}. */
    public static boolean canShow(char c) {
        char upper = Character.toUpperCase(c);
        return upper >= 32 && upper <= 126;
    }

    /** Lays {@code text} out on {@code chars} characters: see {@link SegmentText#layout}. */
    public static int[] layout(String text, int chars, boolean alignRight) {
        return SegmentText.layout(text, chars, alignRight, c -> mask((char) c), DP);
    }
}
