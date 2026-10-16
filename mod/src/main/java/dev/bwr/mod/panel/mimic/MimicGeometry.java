package dev.bwr.mod.panel.mimic;

import java.util.List;

/**
 * The arithmetic of mimic paths, with no Minecraft types: snapping a cursor to the grid, distances, junction tests
 * and the tape cost. The server (authority) and the client (preview, hover) call the same functions, so what the
 * player sees under the cursor is what the server decides.
 *
 * <p>Coordinates are grid cells; {@code aspect} is the real length of one row relative to one column (1.0 on a vertical
 * board, about 1.41 on a benchboard), so a distance can be measured on the board itself.
 */
public final class MimicGeometry {
    private MimicGeometry() {
    }

    /** A free-angle segment within this many degrees of an axis or a 45 degree diagonal snaps to it. */
    private static final double SNAP_DEGREES = 8.0;
    /** Cells of tape one tape item covers. */
    public static final double CELLS_PER_TAPE = 32.0;

    /**
     * The grid point for a cursor at {@code (cx, cy)}.
     *
     * @param hasLast whether a previous point exists; without one the cursor just rounds to the nearest grid point
     * @param free    sneaking: any angle (snapped onto an axis or a 45 degree diagonal when close to one); otherwise
     *                the segment is orthogonal, along whichever axis the cursor is farther from the last point
     */
    public static int[] snap(boolean hasLast, int lastX, int lastY, double cx, double cy, boolean free) {
        int tx = (int) Math.round(cx);
        int ty = (int) Math.round(cy);
        if (!hasLast) {
            return new int[] {tx, ty};
        }
        int dx = tx - lastX;
        int dy = ty - lastY;
        if (!free) {
            return Math.abs(dx) >= Math.abs(dy) ? new int[] {lastX + dx, lastY} : new int[] {lastX, lastY + dy};
        }
        if (dx == 0 && dy == 0) {
            return new int[] {tx, ty};
        }
        double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
        if (angle <= SNAP_DEGREES) {
            return new int[] {tx, lastY};
        }
        if (angle >= 90.0 - SNAP_DEGREES) {
            return new int[] {lastX, ty};
        }
        if (Math.abs(angle - 45.0) <= SNAP_DEGREES) {
            int d = (int) Math.round((Math.abs(dx) + Math.abs(dy)) / 2.0);
            return new int[] {lastX + (dx < 0 ? -d : d), lastY + (dy < 0 ? -d : d)};
        }
        return new int[] {tx, ty};
    }

    /** Length of a polyline measured on the board. */
    public static double length(int[] points, double aspect) {
        double total = 0.0;
        for (int i = 2; i + 1 < points.length; i += 2) {
            double dx = points[i] - points[i - 2];
            double dy = (points[i + 1] - points[i - 1]) * aspect;
            total += Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    /** Tape items a path of this length costs in survival: one per 32 cells, at least one. */
    public static int cost(double length) {
        return Math.max(1, (int) Math.ceil(length / CELLS_PER_TAPE - 1.0e-9));
    }

    /** Distance from {@code (px, py)} to the segment {@code a-b}, measured on the board. */
    public static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by,
                                           double aspect) {
        double dx = bx - ax;
        double dy = (by - ay) * aspect;
        double qx = px - ax;
        double qy = (py - ay) * aspect;
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq < 1.0e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, (qx * dx + qy * dy) / lengthSq));
        double ex = qx - dx * t;
        double ey = qy - dy * t;
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** Distance from {@code (px, py)} to the polyline. */
    public static double distance(MimicPath path, double px, double py, double aspect) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.pointCount(); i++) {
            best = Math.min(best, distanceToSegment(px, py, path.x(i - 1), path.y(i - 1), path.x(i), path.y(i), aspect));
        }
        return best;
    }

    /** How close the cursor must be to a path's centre line to select it: its half width plus a margin. */
    public static double pickTolerance(MimicPath path) {
        return Math.max(1.2, path.style().width / 2.0 + 0.8);
    }

    /** The path under the cursor (the nearest within its tolerance; the later one on a tie), or null. */
    public static MimicPath pick(List<MimicPath> paths, double px, double py, double aspect) {
        MimicPath best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MimicPath path : paths) {
            double d = distance(path, px, py, aspect);
            if (d <= pickTolerance(path) && d <= bestDistance) {
                best = path;
                bestDistance = d;
            }
        }
        return best;
    }

    // ---- exact integer tests

    /** Whether grid point {@code p} lies on the closed segment {@code a-b}. */
    public static boolean onSegment(int px, int py, int ax, int ay, int bx, int by) {
        long cross = (long) (bx - ax) * (py - ay) - (long) (by - ay) * (px - ax);
        if (cross != 0) {
            return false;
        }
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && py >= Math.min(ay, by) && py <= Math.max(ay, by);
    }

    /**
     * Whether vertex {@code vertex} of {@code path} is a junction dot (spec 2): it lies on another path, on the inside
     * of one of its segments or at one of its interior points. Two paths that merely cross, or meet end to end, share
     * no such point and get no dot; an end that carries an arrow keeps the arrow instead.
     */
    public static boolean junction(MimicPath path, int vertex, List<MimicPath> others) {
        boolean end = vertex == 0 || vertex == path.pointCount() - 1;
        if (end && (vertex == 0 ? path.style().startEnd : path.style().endEnd) == MimicStyle.END_ARROW) {
            return false;
        }
        int px = path.x(vertex);
        int py = path.y(vertex);
        for (MimicPath other : others) {
            if (other == path || other.pathId() == path.pathId()) {
                continue;
            }
            int last = other.pointCount() - 1;
            for (int i = 1; i <= last; i++) {
                if (!onSegment(px, py, other.x(i - 1), other.y(i - 1), other.x(i), other.y(i))) {
                    continue;
                }
                boolean atStart = px == other.x(i - 1) && py == other.y(i - 1);
                boolean atEnd = px == other.x(i) && py == other.y(i);
                boolean otherEnd = (atStart && i - 1 == 0) || (atEnd && i == last);
                if (!otherEnd) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Smallest and largest coordinates of a flat point array: {@code minX, minY, maxX, maxY}. */
    public static int[] bounds(int[] points) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < points.length; i += 2) {
            minX = Math.min(minX, points[i]);
            maxX = Math.max(maxX, points[i]);
            minY = Math.min(minY, points[i + 1]);
            maxY = Math.max(maxY, points[i + 1]);
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    /** Whether the polyline has two equal neighbouring points (a zero length segment). */
    public static boolean hasZeroSegment(int[] points) {
        for (int i = 2; i + 1 < points.length; i += 2) {
            if (points[i] == points[i - 2] && points[i + 1] == points[i - 1]) {
                return true;
            }
        }
        return false;
    }

    /** The point array shifted by {@code (dx, dy)}. */
    public static int[] shifted(int[] points, int dx, int dy) {
        int[] out = new int[points.length];
        for (int i = 0; i + 1 < points.length; i += 2) {
            out[i] = points[i] + dx;
            out[i + 1] = points[i + 1] + dy;
        }
        return out;
    }
}
