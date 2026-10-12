package dev.bwr.mod.panel.client;

/**
 * Flat shapes made of quads, for the procedural placeholder bodies. All of it
 * is primitive math into a {@link QuadSink}, so it is safe to call every frame
 * on a {@link PanelRenderContext} (no allocation) as well as when recording a
 * static mesh. Angles are radians measured clockwise from "up" (toward -y), the
 * way a gauge reads.
 */
final class Shapes {
    private Shapes() {
    }

    /** A triangle (as a quad with a repeated corner). */
    static void tri(QuadSink s, float x0, float y0, float x1, float y1, float x2, float y2, float z, int rgb,
                    boolean emissive) {
        s.quad(x0, y0, z, x1, y1, z, x2, y2, z, x2, y2, z, rgb, QuadSink.NORMAL_FACE, emissive);
    }

    /** A filled circle approximated by {@code segments} triangles. */
    static void disc(QuadSink s, float cx, float cy, float r, float z, int rgb, int segments, boolean emissive) {
        float step = (float) (2.0 * Math.PI / segments);
        float px = cx;
        float py = cy - r;
        for (int i = 1; i <= segments; i++) {
            float a = i * step;
            float x = cx + (float) Math.sin(a) * r;
            float y = cy - (float) Math.cos(a) * r;
            tri(s, cx, cy, px, py, x, y, z, rgb, emissive);
            px = x;
            py = y;
        }
    }

    /** Part of a ring between radii {@code r0} and {@code r1}, from angle {@code a0} to {@code a1}. */
    static void ring(QuadSink s, float cx, float cy, float r0, float r1, float a0, float a1, float z, int rgb,
                     float stepRadians, boolean emissive) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(a1 - a0) / stepRadians));
        float da = (a1 - a0) / steps;
        for (int i = 0; i < steps; i++) {
            float a = a0 + i * da;
            float b = a + da;
            float sa = (float) Math.sin(a);
            float ca = (float) Math.cos(a);
            float sb = (float) Math.sin(b);
            float cb = (float) Math.cos(b);
            s.quad(cx + sa * r0, cy - ca * r0, z, cx + sa * r1, cy - ca * r1, z,
                    cx + sb * r1, cy - cb * r1, z, cx + sb * r0, cy - cb * r0, z,
                    rgb, QuadSink.NORMAL_FACE, emissive);
        }
    }

    /** A line of constant {@code width} from (x0, y0) to (x1, y1). */
    static void bar(QuadSink s, float x0, float y0, float x1, float y1, float width, float z, int rgb,
                    boolean emissive) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0e-6f) {
            float h = width / 2.0f;
            s.rect(x0 - h, y0 - h, width, width, z, rgb, emissive);
            return;
        }
        float nx = -dy / length * width / 2.0f;
        float ny = dx / length * width / 2.0f;
        s.quad(x0 + nx, y0 + ny, z, x1 + nx, y1 + ny, z, x1 - nx, y1 - ny, z, x0 - nx, y0 - ny, z,
                rgb, QuadSink.NORMAL_FACE, emissive);
    }

    /** A line from the point at radius {@code r0} to the point at {@code r1} along angle {@code a} from (cx, cy). */
    static void spoke(QuadSink s, float cx, float cy, float r0, float r1, float a, float width, float z, int rgb,
                      boolean emissive) {
        float sa = (float) Math.sin(a);
        float ca = (float) Math.cos(a);
        bar(s, cx + sa * r0, cy - ca * r0, cx + sa * r1, cy - ca * r1, width, z, rgb, emissive);
    }
}
