package dev.bwr.mod.panel.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The Tick size setting ({@link GaugeScale}) on a baked meter, whose graduations are faces of its body: finds the
 * printed tick marks and lengthens or thickens them, in the model's own inches, so they keep the model's proportions at
 * any component size.
 *
 * <p>A tick is a flat face lying on the scale (facing the viewer, within {@value #ON_SCALE} in of the numerals'
 * elevation), dark like printed ink, a thin rectangle (at least
 * {@value #THIN} times as long as it is thick) and short next to the meter (under {@value #SHORT} of its smaller
 * side). It keeps the end away from the numbers, where the graduations line up (the outer arc of a dial, the pointer's
 * side of an edgewise strip), and grows toward the numbers; it never grows more than half way to the nearest numeral
 * anchor, and never thicker than {@value #MAX_FILL} of the gap to its neighbour.
 */
public final class PrintedTicks {
    private PrintedTicks() {
    }

    private static final float THIN = 2.5f;
    private static final float SHORT = 0.3f;
    private static final float MAX_FILL = 0.6f;
    private static final double INK = 0.2;
    private static final float ON_SCALE = 0.05f;

    /** {@code part} with its ticks {@code factor} times as long and thick (each limited as above); the part itself when it has none. */
    public static BakedPanelModel.Part resize(BakedPanelModel model, BakedPanelModel.Part part, float factor) {
        if (factor == 1.0f || part.moving()) {
            return part;
        }
        List<float[]> anchors = new ArrayList<>();
        float scaleZ = 0.0f;
        for (int i = 0; i < model.labelCount(); i++) {
            BakedPanelModel.Label label = model.label(i);
            if (label.id.startsWith("scale")) {
                anchors.add(new float[] {label.x, label.y});
                scaleZ += label.z;
            }
        }
        if (anchors.isEmpty()) {
            return part;
        }
        scaleZ /= Math.max(1, anchors.size());
        float shortLimit = SHORT * Math.min(model.footprintW, model.footprintH);
        // Per tick face: index, the fixed end (x, y), the unit direction toward the numbers, its length and thickness,
        // and how far its moving end is from the nearest numeral anchor.
        List<float[]> ticks = new ArrayList<>();
        for (int f = 0; f < part.faceCount; f++) {
            if (part.normal(f, 2) < 0.9f || PanelModelParser.luminance(part.faceColor(f)) > INK
                    || Math.abs(part.corner(f, 0, 2) - scaleZ) > ON_SCALE || edge(part, f, 2, 3) < 1.0e-5f || edge(part, f, 3, 0) < 1.0e-5f) {
                continue;
            }
            float e01 = edge(part, f, 0, 1);
            float e12 = edge(part, f, 1, 2);
            float length = Math.max(e01, e12);
            float thick = Math.min(e01, e12);
            if (thick <= 1.0e-5f || length < THIN * thick || length > shortLimit) {
                continue;
            }
            // The ends: midpoints of the two short edges.
            int[] endA = e01 >= e12 ? new int[] {0, 3} : new int[] {0, 1};
            int[] endB = e01 >= e12 ? new int[] {1, 2} : new int[] {3, 2};
            float ax = mid(part, f, endA, 0);
            float ay = mid(part, f, endA, 1);
            float bx = mid(part, f, endB, 0);
            float by = mid(part, f, endB, 1);
            float da = nearest(anchors, ax, ay);
            float db = nearest(anchors, bx, by);
            boolean aFixed = da >= db;
            float fx = aFixed ? ax : bx;
            float fy = aFixed ? ay : by;
            float mx = aFixed ? bx : ax;
            float my = aFixed ? by : ay;
            ticks.add(new float[] {f, fx, fy, (mx - fx) / length, (my - fy) / length, length, thick, Math.min(da, db)});
        }
        if (ticks.isEmpty()) {
            return part;
        }
        float lengthFactor = factor;
        float thickFactor = factor;
        if (factor > 1.0f) {
            for (float[] t : ticks) {
                lengthFactor = Math.min(lengthFactor, 1.0f + 0.5f * t[7] / t[5]);
                float gap = Float.MAX_VALUE;
                float cx = t[1] + t[3] * t[5] / 2.0f;
                float cy = t[2] + t[4] * t[5] / 2.0f;
                for (float[] u : ticks) {
                    if (u != t) {
                        gap = Math.min(gap, (float) Math.hypot(u[1] + u[3] * u[5] / 2.0f - cx, u[2] + u[4] * u[5] / 2.0f - cy));
                    }
                }
                if (gap < Float.MAX_VALUE) {
                    thickFactor = Math.min(thickFactor, MAX_FILL * gap / t[6]);
                }
            }
            lengthFactor = Math.max(1.0f, lengthFactor);
            thickFactor = Math.max(1.0f, thickFactor);
        }
        float[] positions = part.positions.clone();
        for (float[] t : ticks) {
            int f = (int) t[0];
            float dx = t[3];
            float dy = t[4];
            for (int c = 0; c < 4; c++) {
                int at = f * 12 + c * 3;
                float px = positions[at] - t[1];
                float py = positions[at + 1] - t[2];
                float along = px * dx + py * dy;
                float across = -px * dy + py * dx;
                along *= lengthFactor;
                across *= thickFactor;
                positions[at] = t[1] + along * dx - across * dy;
                positions[at + 1] = t[2] + along * dy + across * dx;
            }
        }
        float[] pivot = part.hasPivot ? new float[] {part.pivotX, part.pivotY, part.pivotZ} : null;
        return new BakedPanelModel.Part(part.name, part.role, part.tint, pivot, new float[] {part.axisX, part.axisY, part.axisZ},
                part.travel, part.hasTravel, positions, part.normals, part.colors, part.shades);
    }

    private static float edge(BakedPanelModel.Part part, int face, int c0, int c1) {
        return (float) Math.hypot(part.corner(face, c1, 0) - part.corner(face, c0, 0), part.corner(face, c1, 1) - part.corner(face, c0, 1));
    }

    private static float mid(BakedPanelModel.Part part, int face, int[] corners, int axis) {
        return (part.corner(face, corners[0], axis) + part.corner(face, corners[1], axis)) / 2.0f;
    }

    private static float nearest(List<float[]> anchors, float x, float y) {
        float best = Float.MAX_VALUE;
        for (float[] a : anchors) {
            best = Math.min(best, (float) Math.hypot(a[0] - x, a[1] - y));
        }
        return best;
    }
}
