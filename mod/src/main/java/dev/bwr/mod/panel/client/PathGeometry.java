package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.mimic.MimicPath;

/**
 * One mimic path prepared for drawing: its points in real board lengths (a row counts {@code aspect} columns, so a
 * line is as thick and an arrow as true on a sloped benchboard as on a wall), the unit direction of every segment and
 * the distance along the path to every point. Built when a mesh is built, never per frame, except for the cursor's
 * ghost segment.
 */
final class PathGeometry {
    final int n;
    /** Point coordinates in board lengths (y already multiplied by the row aspect). */
    final float[] x;
    final float[] y;
    /** Unit direction of segment {@code i}, from point {@code i} to {@code i + 1}. */
    final float[] dx;
    final float[] dy;
    /** Distance along the path to point {@code i}. */
    final float[] cum;
    final float total;

    PathGeometry(MimicPath path, float aspect) {
        this(path.points(), aspect);
    }

    PathGeometry(int[] points, float aspect) {
        n = points.length / 2;
        x = new float[n];
        y = new float[n];
        dx = new float[Math.max(1, n - 1)];
        dy = new float[Math.max(1, n - 1)];
        cum = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = points[i * 2];
            y[i] = points[i * 2 + 1] * aspect;
        }
        for (int i = 0; i + 1 < n; i++) {
            float ex = x[i + 1] - x[i];
            float ey = y[i + 1] - y[i];
            float length = (float) Math.sqrt(ex * ex + ey * ey);
            if (length < 1.0e-6f) {
                dx[i] = 1.0f;
                dy[i] = 0.0f;
            } else {
                dx[i] = ex / length;
                dy[i] = ey / length;
            }
            cum[i + 1] = cum[i] + length;
        }
        total = n > 0 ? cum[n - 1] : 0.0f;
    }

    /** The segment that distance {@code s} along the path falls in. */
    int segmentAt(float s) {
        int segment = 0;
        while (segment < n - 2 && s > cum[segment + 1]) {
            segment++;
        }
        return segment;
    }

    float segmentLength(int segment) {
        return cum[segment + 1] - cum[segment];
    }
}
