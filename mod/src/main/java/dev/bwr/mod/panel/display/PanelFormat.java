package dev.bwr.mod.panel.display;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/**
 * The display format strings of CONTROL-PANELS-SPEC 2.2, as a pure function with
 * no Minecraft dependency.
 *
 * <pre>
 *   PWR {0:5.1f}%     {0:04d}     {1}     {{literal braces}}
 * </pre>
 * A field is {@code {index}} or {@code {index:spec}}; {@code index} is the
 * display input (0..15) and {@code spec} is
 * <pre>  [&lt;|&gt;] [+] [0] [width] [.precision] [f|d|x|X]</pre>
 * <ul>
 *   <li>{@code f}: fixed point, {@code precision} digits (default 2).</li>
 *   <li>{@code d}: the value rounded to a whole number. {@code x}/{@code X}: hexadecimal of the rounded value.</li>
 *   <li>No type: the raw value, a whole number without decimals, otherwise up to six significant digits.
 *       A bare precision ({@code {0:.1}}) means {@code f}.</li>
 *   <li>{@code width}: minimum width; numbers align right ({@code <} aligns left); {@code 0} pads with zeros after the sign;
 *       {@code +} always shows a sign.</li>
 *   <li><b>Overflow</b>: when a number does not fit its explicit width the field shows {@code OFL}
 *       (padded to the width), or dashes when the width is under three.</li>
 *   <li><b>No signal</b> ({@code NaN}, or an input nothing feeds): dashes, {@code width} of them, four when no width is given.</li>
 *   <li>Infinity counts as overflow.</li>
 * </ul>
 * Text that is not a valid field (an unclosed brace, a bad index, an unknown spec) is copied through unchanged, so a
 * mistyped format shows what was typed rather than failing.
 */
public final class PanelFormat {
    private PanelFormat() {
    }

    public static final String NO_SIGNAL = "----";
    public static final String OVERFLOW = "OFL";
    public static final int MAX_WIDTH = 64;
    public static final int MAX_PRECISION = 9;
    public static final int MAX_INDEX = 15;

    /** Formats {@code pattern}, reading input {@code i} from {@code values}. */
    public static String format(String pattern, IntToDoubleFunction values) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(pattern.length() + 8);
        int n = pattern.length();
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (i + 1 < n && pattern.charAt(i + 1) == '{') {
                    out.append('{');
                    i += 2;
                    continue;
                }
                int close = pattern.indexOf('}', i + 1);
                if (close > 0 && field(out, pattern.substring(i + 1, close), values)) {
                    i = close + 1;
                    continue;
                }
                out.append('{');
                i++;
            } else if (c == '}') {
                out.append('}');
                i += (i + 1 < n && pattern.charAt(i + 1) == '}') ? 2 : 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Whether {@code pattern} contains at least one replacement field. */
    public static boolean hasFields(String pattern) {
        if (pattern == null) {
            return false;
        }
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (i + 1 < n && pattern.charAt(i + 1) == '{') {
                    i++;
                } else if (pattern.indexOf('}', i + 1) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean field(StringBuilder out, String body, IntToDoubleFunction values) {
        int colon = body.indexOf(':');
        String index = colon < 0 ? body : body.substring(0, colon);
        String spec = colon < 0 ? "" : body.substring(colon + 1);
        if (index.isEmpty() || index.length() > 2 || !isDigits(index)) {
            return false;
        }
        int number = Integer.parseInt(index);
        if (number > MAX_INDEX) {
            return false;
        }
        double value = values == null ? Double.NaN : values.applyAsDouble(number);
        String text = formatNumber(value, spec);
        if (text == null) {
            return false;
        }
        out.append(text);
        return true;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * One number under one {@code spec} (see the class description), or {@code null} when the spec is not valid.
     * An empty spec is the raw form.
     */
    public static String formatNumber(double value, String spec) {
        int p = 0;
        int n = spec.length();
        boolean left = false;
        boolean plus = false;
        boolean zero = false;
        if (p < n && (spec.charAt(p) == '<' || spec.charAt(p) == '>')) {
            left = spec.charAt(p) == '<';
            p++;
        }
        if (p < n && spec.charAt(p) == '+') {
            plus = true;
            p++;
        }
        if (p < n && spec.charAt(p) == '0') {
            zero = true;
            p++;
        }
        int width = 0;
        int start = p;
        while (p < n && digit(spec.charAt(p))) {
            p++;
        }
        if (p > start) {
            if (p - start > 3) {
                return null;
            }
            width = Math.min(MAX_WIDTH, Integer.parseInt(spec.substring(start, p)));
        }
        int precision = -1;
        if (p < n && spec.charAt(p) == '.') {
            p++;
            start = p;
            while (p < n && digit(spec.charAt(p))) {
                p++;
            }
            if (p == start || p - start > 2) {
                return null;
            }
            precision = Math.min(MAX_PRECISION, Integer.parseInt(spec.substring(start, p)));
        }
        char type = 0;
        if (p < n) {
            type = spec.charAt(p);
            if (type != 'f' && type != 'd' && type != 'x' && type != 'X' || p + 1 != n) {
                return null;
            }
        } else if (precision >= 0) {
            type = 'f';
        }
        if (Double.isNaN(value)) {
            return "-".repeat(width > 0 ? width : NO_SIGNAL.length());
        }
        String body = Double.isInfinite(value) ? null : digits(value, type, precision);
        if (body == null) {
            return overflow(width, left);
        }
        boolean negative = body.startsWith("-");
        String magnitude = negative ? body.substring(1) : body;
        String sign = negative ? "-" : plus ? "+" : "";
        if (width > 0 && sign.length() + magnitude.length() > width) {
            return overflow(width, left);
        }
        int pad = Math.max(0, width - sign.length() - magnitude.length());
        StringBuilder out = new StringBuilder(width + 8);
        if (pad > 0 && !left && !zero) {
            out.append(" ".repeat(pad));
        }
        out.append(sign);
        if (pad > 0 && zero && !left) {
            out.append("0".repeat(pad));
        }
        out.append(magnitude);
        if (pad > 0 && left) {
            out.append(" ".repeat(pad));
        }
        return out.toString();
    }

    private static String overflow(int width, boolean left) {
        if (width < OVERFLOW.length()) {
            return "-".repeat(Math.max(1, width));
        }
        String pad = " ".repeat(width - OVERFLOW.length());
        return left ? OVERFLOW + pad : pad + OVERFLOW;
    }

    /** The number without width handling, or null when it is out of range. */
    private static String digits(double value, char type, int precision) {
        switch (type) {
            case 'f' -> {
                if (Math.abs(value) >= 1.0e15) {
                    return null;
                }
                String text = String.format(Locale.ROOT, "%." + (precision < 0 ? 2 : precision) + "f", value);
                return negativeZero(text) ? text.substring(1) : text;
            }
            case 'd', 'x', 'X' -> {
                if (Math.abs(value) >= 9.0e15) {
                    return null;
                }
                long whole = Math.round(value);
                if (type == 'd') {
                    return Long.toString(whole);
                }
                String hex = Long.toHexString(Math.abs(whole));
                hex = type == 'X' ? hex.toUpperCase(Locale.ROOT) : hex;
                return whole < 0 ? "-" + hex : hex;
            }
            default -> {
                if (Math.abs(value) >= 1.0e15) {
                    return null;
                }
                if (value == Math.rint(value)) {
                    return Long.toString((long) value);
                }
                String text = new BigDecimal(value).round(new MathContext(6)).stripTrailingZeros().toPlainString();
                return negativeZero(text) ? text.substring(1) : text;
            }
        }
    }

    /** "-0.00" style output, which should read as plain zero. */
    private static boolean negativeZero(String text) {
        if (!text.startsWith("-")) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '0' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
