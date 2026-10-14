package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.client.QuadSink;

/**
 * Sends one part of a {@link BakedPanelModel} to a {@link QuadSink}: scales its inches to cells through a
 * {@link Placement}, applies the part's rotation about its pivot or its slide along its axis, and recolours it.
 * All primitive math into a caller-supplied scratch array, so it is safe to call every frame on the live
 * {@code PanelRenderContext} (no allocation) as well as when recording a static mesh.
 */
public final class PartDraw {
    private PartDraw() {
    }

    /** Length of the scratch array {@code emit} needs: four corners of three floats. */
    public static final int SCRATCH = 12;
    /** Alpha of meter windows: they should read as a faint sheen, not a tint. */
    public static final int GLASS_ALPHA = 36;

    /**
     * The colour of face {@code face}: its authored colour, or {@code baseRgb} (when not negative) shaded by the
     * face's brightness relative to its part so authored shading survives recolouring; then scaled by
     * {@code brightness} (1 leaves it, the off-state of a lamp uses a small value).
     */
    public static int faceColor(BakedPanelModel.Part part, int face, int baseRgb, float brightness) {
        int rgb = baseRgb < 0 ? part.faceColor(face) : QuadSink.shade(baseRgb, part.faceShade(face));
        return brightness == 1.0f ? rgb : QuadSink.shade(rgb, brightness);
    }

    /**
     * Emits every face of {@code part}.
     *
     * @param angleDeg   rotation about the part's pivot and axis (ignored without a pivot)
     * @param slide      fraction of the part's travel along its axis, 0..1 (ignored without a travel)
     * @param baseRgb    colour to recolour the part with, or -1 to keep the authored colours
     * @param brightness 1 normally; the dim factor of an unlit lamp lens
     * @param scratch    at least {@link #SCRATCH} floats
     */
    public static void emit(QuadSink sink, BakedPanelModel.Part part, Placement place, float angleDeg, float slide,
                            int baseRgb, float brightness, boolean emissive, float[] scratch) {
        // Rotation matrix about the pivot (identity when there is nothing to turn).
        float r00 = 1, r01 = 0, r02 = 0, r10 = 0, r11 = 1, r12 = 0, r20 = 0, r21 = 0, r22 = 1;
        if (part.hasPivot && angleDeg != 0.0f) {
            double radians = Math.toRadians(angleDeg);
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
            float k = 1.0f - c;
            float ax = part.axisX;
            float ay = part.axisY;
            float az = part.axisZ;
            r00 = c + k * ax * ax;
            r01 = k * ax * ay - s * az;
            r02 = k * ax * az + s * ay;
            r10 = k * ay * ax + s * az;
            r11 = c + k * ay * ay;
            r12 = k * ay * az - s * ax;
            r20 = k * az * ax - s * ay;
            r21 = k * az * ay + s * ax;
            r22 = c + k * az * az;
        }
        float tx = 0;
        float ty = 0;
        float tz = 0;
        if (part.hasTravel && slide != 0.0f) {
            float distance = part.travel * slide;
            tx = part.axisX * distance;
            ty = part.axisY * distance;
            tz = part.axisZ * distance;
        }
        float px = part.pivotX;
        float py = part.pivotY;
        float pz = part.pivotZ;
        float scale = place.scale;
        float rowScale = place.rowScale;
        float ox = place.offsetX;
        float oy = place.offsetY;
        float[] positions = part.positions;
        float[] normals = part.normals;
        for (int f = 0; f < part.faceCount; f++) {
            int base = f * 12;
            for (int corner = 0; corner < 4; corner++) {
                float dx = positions[base + corner * 3] - px;
                float dy = positions[base + corner * 3 + 1] - py;
                float dz = positions[base + corner * 3 + 2] - pz;
                scratch[corner * 3] = ox + (r00 * dx + r01 * dy + r02 * dz + px + tx) * scale;
                scratch[corner * 3 + 1] = oy + (r10 * dx + r11 * dy + r12 * dz + py + ty) * rowScale;
                scratch[corner * 3 + 2] = (r20 * dx + r21 * dy + r22 * dz + pz + tz) * scale;
            }
            float n0 = normals[f * 3];
            float n1 = normals[f * 3 + 1];
            float n2 = normals[f * 3 + 2];
            float faceBrightness = brightness;
            if (emissive) {
                // A glowing face is not lit by the world, so a dome or a ribbed lens would read as a flat disc: shade it
                // by how squarely it faces the viewer (a flat face, normal straight out, is untouched).
                faceBrightness *= 0.6f + 0.4f * Math.max(0.0f, r20 * n0 + r21 * n1 + r22 * n2);
            }
            int rgb = faceColor(part, f, baseRgb, faceBrightness);
            // The bulb transmits most strongly through the crown of a curved lens.
            // Keep the shoulder saturated; flat annunciator/cap faces have no dome hot spot.
            if (emissive && part.role == BakedPanelModel.Role.LAMP && part.faceCount >= 32) {
                float facing = Math.max(0.0f, n2);
                rgb = hotSpot(rgb, 0.9f * (float) Math.pow(facing, 20));
            }
            sink.quad(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5],
                    scratch[6], scratch[7], scratch[8], scratch[9], scratch[10], scratch[11],
                    rgb,
                    r00 * n0 + r01 * n1 + r02 * n2, r10 * n0 + r11 * n1 + r12 * n2, r20 * n0 + r21 * n1 + r22 * n2,
                    emissive);
        }
    }

    private static int hotSpot(int rgb, float amount) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return ((r + Math.round((255 - r) * amount)) << 16)
                | ((g + Math.round((255 - g) * amount)) << 8)
                | (b + Math.round((255 - b) * amount));
    }

    /** Emits a {@link BakedPanelModel.Role#GLASS} part as translucent quads in the part's own colours. */
    public static void emitGlass(QuadSink sink, BakedPanelModel.Part part, Placement place, int alpha, float[] scratch) {
        float scale = place.scale;
        float rowScale = place.rowScale;
        float ox = place.offsetX;
        float oy = place.offsetY;
        for (int f = 0; f < part.faceCount; f++) {
            int base = f * 12;
            for (int corner = 0; corner < 4; corner++) {
                scratch[corner * 3] = ox + part.positions[base + corner * 3] * scale;
                scratch[corner * 3 + 1] = oy + part.positions[base + corner * 3 + 1] * rowScale;
                scratch[corner * 3 + 2] = part.positions[base + corner * 3 + 2] * scale;
            }
            sink.glassQuad(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5],
                    scratch[6], scratch[7], scratch[8], scratch[9], scratch[10], scratch[11],
                    part.faceColor(f), alpha, part.normals[f * 3], part.normals[f * 3 + 1], part.normals[f * 3 + 2]);
        }
    }
}
