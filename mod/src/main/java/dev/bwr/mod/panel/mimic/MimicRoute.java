package dev.bwr.mod.panel.mimic;

/**
 * The shape of a mimic path between its clicked points, like draw.io connectors (owner's change 2026-10-16): the
 * points are expanded into the dense polyline that is actually drawn and picked. Pure arithmetic with no Minecraft
 * types, shared by the server (picking a path to erase or edit) and the client (meshing), so both agree on where a
 * path runs.
 *
 * <ul>
 *   <li><b>Route</b> {@code elbow}: between two points that are not on one axis, the path turns twice at right angles
 *       through the midpoint of the longer axis (draw.io's orthogonal connector). Straight leaves the points alone.</li>
 *   <li><b>Corners</b> {@code rounded}: every corner gets a circular fillet of the style's radius (cut back to fit the
 *       two segments it joins). {@code curved}: one smooth centripetal Catmull-Rom curve through all the points (no
 *       cusps or self-loops on uneven spacing). {@code sharp} leaves the corners as they are.</li>
 * </ul>
 * All of it is worked out in real board lengths (a row is {@code aspect} columns long, so a fillet is round on a
 * sloped benchboard too) and returned in grid cells.
 */
public final class MimicRoute {
    private MimicRoute() {
    }

    /** Straight pieces per fillet and per curve span; enough to look round at 1 to 2 blocks. */
    private static final int FILLET_STEPS = 8;
    private static final int CURVE_STEPS = 12;
    /** Upper bound on the expanded polyline, so a 64-point curved path stays cheap to mesh and pick. */
    public static final int MAX_EXPANDED_POINTS = 1024;

    /**
     * The polyline that is drawn for {@code points} (flat {@code x0, y0, x1, y1, ...} grid cells) in {@code style}, as
     * flat doubles in grid cells. A straight, sharp path returns its own points.
     */
    public static double[] expand(int[] points, MimicStyle style, double aspect) {
        double[] p = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            p[i] = points[i];
        }
        if (style == null || points.length < 4) {
            return p;
        }
        double a = aspect > 1.0e-6 ? aspect : 1.0;
        // Work in board lengths.
        for (int i = 1; i < p.length; i += 2) {
            p[i] *= a;
        }
        if (MimicStyle.ROUTE_ELBOW.equals(style.route)) {
            p = elbow(p);
        }
        if (MimicStyle.CORNERS_ROUNDED.equals(style.corners)) {
            p = fillet(p, style.radius);
        } else if (MimicStyle.CORNERS_CURVED.equals(style.corners)) {
            p = curve(p);
        }
        p = dropDuplicates(p);
        for (int i = 1; i < p.length; i += 2) {
            p[i] /= a;
        }
        return p;
    }

    /** Two right-angle turns between every pair of points that differ in both x and y. */
    static double[] elbow(double[] p) {
        int n = p.length / 2;
        double[] out = new double[Math.min(MAX_EXPANDED_POINTS, n * 3) * 2];
        int m = 0;
        out[m++] = p[0];
        out[m++] = p[1];
        for (int i = 1; i < n && m + 6 <= out.length; i++) {
            double x0 = p[i * 2 - 2];
            double y0 = p[i * 2 - 1];
            double x1 = p[i * 2];
            double y1 = p[i * 2 + 1];
            if (Math.abs(x1 - x0) > 1.0e-9 && Math.abs(y1 - y0) > 1.0e-9) {
                if (Math.abs(x1 - x0) >= Math.abs(y1 - y0)) {
                    double xm = (x0 + x1) / 2.0;
                    out[m++] = xm;
                    out[m++] = y0;
                    out[m++] = xm;
                    out[m++] = y1;
                } else {
                    double ym = (y0 + y1) / 2.0;
                    out[m++] = x0;
                    out[m++] = ym;
                    out[m++] = x1;
                    out[m++] = ym;
                }
            }
            out[m++] = x1;
            out[m++] = y1;
        }
        return java.util.Arrays.copyOf(out, m);
    }

    /** Each interior corner replaced by a circular arc of radius {@code radius}, cut back to fit its two segments. */
    static double[] fillet(double[] p, double radius) {
        int n = p.length / 2;
        if (n < 3) {
            return p;
        }
        double[] out = new double[Math.min(MAX_EXPANDED_POINTS, n * (FILLET_STEPS + 2)) * 2];
        int m = 0;
        out[m++] = p[0];
        out[m++] = p[1];
        for (int i = 1; i < n - 1 && m + (FILLET_STEPS + 2) * 2 <= out.length; i++) {
            double px = p[i * 2 - 2];
            double py = p[i * 2 - 1];
            double cx = p[i * 2];
            double cy = p[i * 2 + 1];
            double nx = p[i * 2 + 2];
            double ny = p[i * 2 + 3];
            double ax = px - cx;
            double ay = py - cy;
            double bx = nx - cx;
            double by = ny - cy;
            double la = Math.hypot(ax, ay);
            double lb = Math.hypot(bx, by);
            if (la < 1.0e-9 || lb < 1.0e-9) {
                continue;
            }
            ax /= la;
            ay /= la;
            bx /= lb;
            by /= lb;
            double cos = Math.max(-1.0, Math.min(1.0, ax * bx + ay * by));
            double angle = Math.acos(cos); // the inside angle at the corner
            if (angle > Math.PI - 1.0e-3 || angle < 1.0e-3) {
                // Straight through, or doubling back: no fillet.
                out[m++] = cx;
                out[m++] = cy;
                continue;
            }
            // Distance from the corner to where the arc touches each segment, limited to half of each segment.
            double tan = Math.tan(angle / 2.0);
            double cut = Math.min(radius / tan, Math.min(la, lb) / 2.0);
            double r = cut * tan;
            double sx = cx + ax * cut;
            double sy = cy + ay * cut;
            double ex = cx + bx * cut;
            double ey = cy + by * cut;
            // The arc centre lies on the bisector, at r / sin(angle / 2) from the corner.
            double hx = ax + bx;
            double hy = ay + by;
            double hl = Math.hypot(hx, hy);
            double d = r / Math.sin(angle / 2.0);
            double ox = cx + hx / hl * d;
            double oy = cy + hy / hl * d;
            double a0 = Math.atan2(sy - oy, sx - ox);
            double a1 = Math.atan2(ey - oy, ex - ox);
            double sweep = a1 - a0;
            while (sweep > Math.PI) {
                sweep -= 2.0 * Math.PI;
            }
            while (sweep < -Math.PI) {
                sweep += 2.0 * Math.PI;
            }
            for (int k = 0; k <= FILLET_STEPS; k++) {
                double t = a0 + sweep * k / FILLET_STEPS;
                out[m++] = ox + Math.cos(t) * r;
                out[m++] = oy + Math.sin(t) * r;
            }
        }
        out[m++] = p[n * 2 - 2];
        out[m++] = p[n * 2 - 1];
        return java.util.Arrays.copyOf(out, m);
    }

    /** One centripetal Catmull-Rom curve through every point (the ends are extended by reflection). */
    static double[] curve(double[] p) {
        int n = p.length / 2;
        if (n < 3) {
            return p;
        }
        int steps = Math.max(2, Math.min(CURVE_STEPS, (MAX_EXPANDED_POINTS - 1) / (n - 1)));
        double[] out = new double[((n - 1) * steps + 1) * 2];
        int m = 0;
        for (int i = 0; i < n - 1; i++) {
            double x0 = i == 0 ? 2 * p[0] - p[2] : p[i * 2 - 2];
            double y0 = i == 0 ? 2 * p[1] - p[3] : p[i * 2 - 1];
            double x1 = p[i * 2];
            double y1 = p[i * 2 + 1];
            double x2 = p[i * 2 + 2];
            double y2 = p[i * 2 + 3];
            double x3 = i + 2 < n ? p[i * 2 + 4] : 2 * x2 - x1;
            double y3 = i + 2 < n ? p[i * 2 + 5] : 2 * y2 - y1;
            double t0 = 0.0;
            double t1 = t0 + Math.sqrt(Math.max(1.0e-9, Math.hypot(x1 - x0, y1 - y0)));
            double t2 = t1 + Math.sqrt(Math.max(1.0e-9, Math.hypot(x2 - x1, y2 - y1)));
            double t3 = t2 + Math.sqrt(Math.max(1.0e-9, Math.hypot(x3 - x2, y3 - y2)));
            for (int k = (i == 0 ? 0 : 1); k <= steps; k++) {
                double t = t1 + (t2 - t1) * k / steps;
                out[m++] = barryGoldman(x0, x1, x2, x3, t0, t1, t2, t3, t);
                out[m++] = barryGoldman(y0, y1, y2, y3, t0, t1, t2, t3, t);
            }
        }
        return java.util.Arrays.copyOf(out, m);
    }

    private static double barryGoldman(double p0, double p1, double p2, double p3, double t0, double t1, double t2,
                                       double t3, double t) {
        double a1 = (t1 - t) / (t1 - t0) * p0 + (t - t0) / (t1 - t0) * p1;
        double a2 = (t2 - t) / (t2 - t1) * p1 + (t - t1) / (t2 - t1) * p2;
        double a3 = (t3 - t) / (t3 - t2) * p2 + (t - t2) / (t3 - t2) * p3;
        double b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2;
        double b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3;
        return (t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2;
    }

    /** The polyline without consecutive (near) duplicate points. */
    private static double[] dropDuplicates(double[] p) {
        double[] out = new double[p.length];
        int m = 0;
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (m >= 2 && Math.abs(out[m - 2] - p[i]) < 1.0e-6 && Math.abs(out[m - 1] - p[i + 1]) < 1.0e-6) {
                continue;
            }
            out[m++] = p[i];
            out[m++] = p[i + 1];
        }
        return java.util.Arrays.copyOf(out, m);
    }
}
