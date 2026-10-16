package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.mimic.MimicStyle;

/**
 * Turns a prepared path ({@link PathGeometry}) into flat quads for a {@link QuadSink}: a line of constant width with
 * mitred corners, solid or dashed, triangular arrow heads, end dots and junction dots. Everything is worked out in
 * real board lengths and converted to grid cells on the way out ({@code y / aspect}).
 *
 * <p>With clipping on, every polygon is cut to the cell rectangle of the block being drawn (a path that runs across
 * several blocks is drawn by each of them, one piece each, with no overdraw). Everything is baked when the panel's
 * mesh is built; nothing here runs per frame.
 */
final class MimicMesher {
    /** The cell rectangle of a block is grown by this much so two neighbouring pieces overlap and leave no seam. */
    private static final float OVERLAP = 0.02f;
    private static final int CIRCLE_SEGMENTS = 16;

    private QuadSink sink;
    private float aspect = 1.0f;
    private boolean clip;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private int rgb;
    private float z;
    private final float[] in = new float[64];
    private final float[] out = new float[64];

    /**
     * Starts drawing into {@code sink}. {@code clip} cuts everything to one block's 16 x 16 cells (grown by
     * {@link #OVERLAP}); without it the geometry is drawn whole (the preview, which spans blocks in one frame).
     */
    void begin(QuadSink sink, float aspect, boolean clip) {
        this.sink = sink;
        this.aspect = aspect;
        this.clip = clip;
        this.minX = -OVERLAP;
        this.minY = -OVERLAP * aspect;
        this.maxX = 16.0f + OVERLAP;
        this.maxY = (16.0f + OVERLAP) * aspect;
    }

    void paint(int rgb, float z) {
        this.rgb = rgb;
        this.z = z;
    }

    private static final float COS_45 = 0.707f;
    private final float[] at = new float[2];
    private final float[] at2 = new float[2];

    // ---- sizes

    static float arrowLength(int width) {
        return 2.5f + 1.1f * width;
    }

    static float arrowHalfWidth(int width) {
        return 0.5f * width + 1.2f;
    }

    /** Arrow head length for a style: its own size when set, otherwise one that follows the line width. */
    static float arrowLength(MimicStyle style) {
        return style.arrowSize > 0 ? style.arrowSize : arrowLength(style.width);
    }

    /** Half the arrow head's base for a style; always wider than the line it ends. */
    static float arrowHalfWidth(MimicStyle style) {
        float half = style.arrowSize > 0 ? style.arrowSize * 0.45f : arrowHalfWidth(style.width);
        return Math.max(half, style.width / 2.0f + 0.6f);
    }

    static float dotRadius(int width) {
        return 0.5f * width + 1.0f;
    }

    // ---- polygons

    void triangle(float x0, float y0, float x1, float y1, float x2, float y2) {
        in[0] = x0;
        in[1] = y0;
        in[2] = x1;
        in[3] = y1;
        in[4] = x2;
        in[5] = y2;
        emit(3);
    }

    void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        in[0] = x0;
        in[1] = y0;
        in[2] = x1;
        in[3] = y1;
        in[4] = x2;
        in[5] = y2;
        in[6] = x3;
        in[7] = y3;
        emit(4);
    }

    void circle(float cx, float cy, float radius) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
            in[i * 2] = cx + (float) Math.cos(angle) * radius;
            in[i * 2 + 1] = cy + (float) Math.sin(angle) * radius;
        }
        emit(CIRCLE_SEGMENTS);
    }

    /** An arrow head: the tip at {@code (tipX, tipY)}, pointing along the unit vector {@code (dirX, dirY)}. */
    void head(float tipX, float tipY, float dirX, float dirY, float length, float halfWidth) {
        float baseX = tipX - dirX * length;
        float baseY = tipY - dirY * length;
        float nx = -dirY * halfWidth;
        float ny = dirX * halfWidth;
        triangle(tipX, tipY, baseX + nx, baseY + ny, baseX - nx, baseY - ny);
    }

    private void emit(int count) {
        float[] poly = in;
        int n = count;
        if (clip) {
            n = clipEdge(in, n, out, true, minX, true);
            n = clipEdge(out, n, in, true, maxX, false);
            n = clipEdge(in, n, out, false, minY, true);
            n = clipEdge(out, n, in, false, maxY, false);
            poly = in;
        }
        if (n < 3) {
            return;
        }
        if (n == 3) {
            vertexQuad(poly, 0, 1, 2, 2);
        } else if (n == 4) {
            vertexQuad(poly, 0, 1, 2, 3);
        } else {
            for (int i = 1; i + 1 < n; i++) {
                vertexQuad(poly, 0, i, i + 1, i + 1);
            }
        }
    }

    private void vertexQuad(float[] p, int a, int b, int c, int d) {
        sink.quad(p[a * 2], p[a * 2 + 1] / aspect, z, p[b * 2], p[b * 2 + 1] / aspect, z,
                p[c * 2], p[c * 2 + 1] / aspect, z, p[d * 2], p[d * 2 + 1] / aspect, z,
                rgb, QuadSink.NORMAL_FACE, false);
    }

    /** Sutherland-Hodgman against one axis-aligned edge; keeps the side where the coordinate is on the inside. */
    private static int clipEdge(float[] from, int count, float[] to, boolean xAxis, float bound, boolean keepGreater) {
        int n = 0;
        int axis = xAxis ? 0 : 1;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float ax = from[i * 2];
            float ay = from[i * 2 + 1];
            float bx = from[j * 2];
            float by = from[j * 2 + 1];
            float av = axis == 0 ? ax : ay;
            float bv = axis == 0 ? bx : by;
            boolean aIn = keepGreater ? av >= bound : av <= bound;
            boolean bIn = keepGreater ? bv >= bound : bv <= bound;
            if (aIn) {
                to[n * 2] = ax;
                to[n * 2 + 1] = ay;
                n++;
            }
            if (aIn != bIn) {
                float t = (bound - av) / (bv - av);
                to[n * 2] = ax + (bx - ax) * t;
                to[n * 2 + 1] = ay + (by - ay) * t;
                n++;
            }
        }
        return n;
    }

    // ---- the line

    /**
     * The centre line of a path as a band of width {@code 2 * half}, mitred at the corners. {@code trimStart} and
     * {@code trimEnd} shorten the two ends (where an arrow head takes over). A dashed band keeps one pattern along the
     * whole path, {@code on} long and {@code off} apart.
     */
    void line(PathGeometry g, float half, boolean dashed, float on, float off, float trimStart, float trimEnd) {
        int segments = g.n - 1;
        if (segments < 1) {
            return;
        }
        // [segment][start left x, y, start right x, y, end left x, y, end right x, y]
        float[] joint = new float[segments * 8];
        for (int s = 0; s < segments; s++) {
            float nx = -g.dy[s] * half;
            float ny = g.dx[s] * half;
            int base = s * 8;
            joint[base] = g.x[s] + nx;
            joint[base + 1] = g.y[s] + ny;
            joint[base + 2] = g.x[s] - nx;
            joint[base + 3] = g.y[s] - ny;
            joint[base + 4] = g.x[s + 1] + nx;
            joint[base + 5] = g.y[s + 1] + ny;
            joint[base + 6] = g.x[s + 1] - nx;
            joint[base + 7] = g.y[s + 1] - ny;
        }
        for (int v = 1; v < g.n - 1; v++) {
            miter(g, v, half, joint);
        }
        float period = on + off;
        float keepFrom = trimStart;
        float keepTo = g.total - trimEnd;
        for (int s = 0; s < segments; s++) {
            float length = g.segmentLength(s);
            // The part of this segment between the two trims (an arrow head may cover several short curve pieces).
            float from = Math.max(0.0f, keepFrom - g.cum[s]);
            float to = Math.min(length, keepTo - g.cum[s]);
            if (to - from <= 1.0e-3f) {
                continue;
            }
            if (!dashed) {
                strip(g, s, from, to, half, joint);
                continue;
            }
            float globalFrom = g.cum[s] + from;
            float globalTo = g.cum[s] + to;
            for (int k = (int) Math.floor(globalFrom / period); k * period < globalTo; k++) {
                float a = Math.max(k * period, globalFrom) - g.cum[s];
                float b = Math.min(k * period + on, globalTo) - g.cum[s];
                if (b - a > 1.0e-3f) {
                    strip(g, s, a, b, half, joint);
                }
            }
        }
    }

    /** One band of segment {@code s} from distance {@code a} to {@code b}; a cut at a corner takes the mitre points. */
    private void strip(PathGeometry g, int s, float a, float b, float half, float[] joint) {
        int base = s * 8;
        float nx = -g.dy[s] * half;
        float ny = g.dx[s] * half;
        float length = g.segmentLength(s);
        float alx;
        float aly;
        float arx;
        float ary;
        if (a <= 1.0e-4f) {
            alx = joint[base];
            aly = joint[base + 1];
            arx = joint[base + 2];
            ary = joint[base + 3];
        } else {
            float px = g.x[s] + g.dx[s] * a;
            float py = g.y[s] + g.dy[s] * a;
            alx = px + nx;
            aly = py + ny;
            arx = px - nx;
            ary = py - ny;
        }
        float blx;
        float bly;
        float brx;
        float bry;
        if (b >= length - 1.0e-4f) {
            blx = joint[base + 4];
            bly = joint[base + 5];
            brx = joint[base + 6];
            bry = joint[base + 7];
        } else {
            float px = g.x[s] + g.dx[s] * b;
            float py = g.y[s] + g.dy[s] * b;
            blx = px + nx;
            bly = py + ny;
            brx = px - nx;
            bry = py - ny;
        }
        quad(alx, aly, blx, bly, brx, bry, arx, ary);
    }

    /**
     * The corner at point {@code v}: the two bands share the mitre points when the turn is gentle enough; a sharp
     * turn (a path doubling back) is bevelled, each band keeping its own corners with a triangle filling the outside.
     */
    private void miter(PathGeometry g, int v, float half, float[] joint) {
        int before = (v - 1) * 8;
        int after = v * 8;
        float pnx = -g.dy[v - 1];
        float pny = g.dx[v - 1];
        float nnx = -g.dy[v];
        float nny = g.dx[v];
        float denominator = 1.0f + pnx * nnx + pny * nny;
        if (denominator >= 0.35f) {
            float mx = (pnx + nnx) * half / denominator;
            float my = (pny + nny) * half / denominator;
            float lx = g.x[v] + mx;
            float ly = g.y[v] + my;
            float rx = g.x[v] - mx;
            float ry = g.y[v] - my;
            joint[before + 4] = lx;
            joint[before + 5] = ly;
            joint[before + 6] = rx;
            joint[before + 7] = ry;
            joint[after] = lx;
            joint[after + 1] = ly;
            joint[after + 2] = rx;
            joint[after + 3] = ry;
            return;
        }
        // Bevel: the band ends keep their own corners; fill the gap on the outside of the turn.
        float cross = g.dx[v - 1] * g.dy[v] - g.dy[v - 1] * g.dx[v];
        int outer = cross > 0.0f ? 2 : 0;
        triangle(g.x[v], g.y[v], joint[before + 4 + outer], joint[before + 5 + outer], joint[after + outer],
                joint[after + 1 + outer]);
    }

    // ---- whole path

    /**
     * Draws the line of a path with its two ends: arrow heads (the line is shortened under the head), dots or nothing.
     * The line and the dots take the current paint; the arrow heads are drawn in {@code arrowRgb} at {@code arrowZ}
     * (a style may give its arrows their own colour). Does not draw the arrows along the path or the junction dots.
     */
    void body(PathGeometry g, MimicStyle style, int arrowRgb, float arrowZ) {
        if (g.n < 2 || g.total <= 1.0e-4f) {
            return;
        }
        int lineRgb = rgb;
        float lineZ = z;
        float half = style.width / 2.0f;
        float on = 3.0f + 0.5f * style.width;
        float off = 2.0f + 0.5f * style.width;
        float length = arrowLength(style);
        float wing = arrowHalfWidth(style);
        boolean startArrow = style.startEnd == MimicStyle.END_ARROW;
        boolean endArrow = style.endEnd == MimicStyle.END_ARROW;
        float limit = g.total * (startArrow && endArrow ? 0.45f : 0.9f);
        float startLength = startArrow ? Math.min(length, limit) : 0.0f;
        float endLength = endArrow ? Math.min(length, limit) : 0.0f;
        line(g, half, style.dashed, on, off, startLength * 0.9f, endLength * 0.9f);
        int last = g.n - 1;
        if (style.startEnd == MimicStyle.END_DOT) {
            circle(g.x[0], g.y[0], dotRadius(style.width));
        }
        if (style.endEnd == MimicStyle.END_DOT) {
            circle(g.x[last], g.y[last], dotRadius(style.width));
        }
        paint(arrowRgb, arrowZ);
        float look = Math.min(1.0f, g.total * 0.25f);
        if (startLength > 0.0f) {
            // Points back along the path, along its direction at the first point.
            g.pointAt(look, at);
            headAlong(g.x[0], g.y[0], at[0], at[1], startLength, wing * startLength / length);
        }
        if (endLength > 0.0f) {
            g.pointAt(g.total - look, at);
            headAlong(g.x[last], g.y[last], at[0], at[1], endLength, wing * endLength / length);
        }
        paint(lineRgb, lineZ);
    }

    /** An arrow head with its tip at {@code (tipX, tipY)}, pointing away from {@code (fromX, fromY)}. */
    private void headAlong(float tipX, float tipY, float fromX, float fromY, float length, float halfWidth) {
        float ex = tipX - fromX;
        float ey = tipY - fromY;
        float l = (float) Math.sqrt(ex * ex + ey * ey);
        if (l < 1.0e-5f) {
            return;
        }
        head(tipX, tipY, ex / l, ey / l, length, halfWidth);
    }

    /**
     * The arrows along a path, one every {@code style.flowEvery} cells starting {@code phase} cells in, in the current
     * paint. Each points along the path where it sits (on a curve, along the curve); one that would sit across a sharp
     * corner, or run into an end arrow, is left out.
     */
    void flow(PathGeometry g, MimicStyle style, float phase) {
        float period = style.flowEvery;
        if (period <= 0.0f) {
            return;
        }
        float length = arrowLength(style);
        float wing = arrowHalfWidth(style);
        float startGap = style.startEnd == MimicStyle.END_ARROW ? length * 1.4f : length * 0.6f;
        float endGap = style.endEnd == MimicStyle.END_ARROW ? length * 1.4f : length * 0.6f;
        for (float s = phase; s <= g.total; s += period) {
            if (s < startGap || s + length > g.total - endGap) {
                continue;
            }
            // Leave out an arrow that would sit on a bend: the path turns more than 45 degrees under it.
            int first = g.segmentAt(s);
            int lastSegment = g.segmentAt(s + length);
            if (g.dx[first] * g.dx[lastSegment] + g.dy[first] * g.dy[lastSegment] < COS_45) {
                continue;
            }
            // Centred on the line, pointing along it where it sits (on a curve, along the curve).
            float middle = s + length / 2.0f;
            g.pointAt(middle, at);
            g.pointAt(Math.max(0.0f, middle - 0.5f), at2);
            float ex = at[0] - at2[0];
            float ey = at[1] - at2[1];
            g.pointAt(Math.min(g.total, middle + 0.5f), at2);
            ex += at2[0] - at[0];
            ey += at2[1] - at[1];
            float l = (float) Math.sqrt(ex * ex + ey * ey);
            if (l < 1.0e-5f) {
                continue;
            }
            ex /= l;
            ey /= l;
            head(at[0] + ex * length / 2.0f, at[1] + ey * length / 2.0f, ex, ey, length, wing);
        }
    }
}
