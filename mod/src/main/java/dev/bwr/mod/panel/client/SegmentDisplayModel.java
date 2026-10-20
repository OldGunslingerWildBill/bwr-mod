package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.panel.component.SegmentDisplayType;

/**
 * 7- and 16-segment displays. The static mesh is a black glass plate with
 * every segment of every digit drawn dark; each frame the lit segments are
 * drawn on top, full-bright, from the mask array the data keeps (recomputed
 * only when the text, an input or the config changed). A digit cell is 5 x 8
 * cells; segment geometry is a table of lines in digit space, built once.
 */
final class SegmentDisplayModel implements ComponentModel {
    private static final int GLASS = 0x0B0D0E;
    private static final float DW = SegmentDisplayType.DIGIT_W;
    private static final float DH = SegmentDisplayType.DIGIT_H;
    /** Segment thickness and the margin round the glyph. */
    private static final float T = 0.85f;
    private static final float MX = 0.55f;
    private static final float MY = 0.7f;
    /** Gap an end of a segment keeps from the corner. */
    private static final float E = T * 0.7f;
    private static final float DP_X = DW - 0.4f;
    private static final float DP_Y = DH - MY - T / 2;
    private static final float DP_SIZE = 0.75f;

    /** x0, y0, x1, y1, width per segment; the last entry is the decimal point. */
    private static final float[][] SEVEN = buildSeven();
    private static final float[][] SIXTEEN = buildSixteen();

    private final boolean sixteen;

    SegmentDisplayModel(boolean sixteen) {
        this.sixteen = sixteen;
    }

    private static float[][] buildSeven() {
        float xl = MX + T / 2;
        float xr = DW - MX - T / 2 - 0.5f;
        float yt = MY + T / 2;
        float ym = DH / 2;
        float yb = DH - MY - T / 2;
        return new float[][] {
                {xl + E, yt, xr - E, yt, T},                    // a
                {xr, yt + E, xr, ym - E * 0.5f, T},             // b
                {xr, ym + E * 0.5f, xr, yb - E, T},             // c
                {xl + E, yb, xr - E, yb, T},                    // d
                {xl, ym + E * 0.5f, xl, yb - E, T},             // e
                {xl, yt + E, xl, ym - E * 0.5f, T},             // f
                {xl + E, ym, xr - E, ym, T},                    // g
                {DP_X, DP_Y, DP_X, DP_Y, DP_SIZE},              // dp
        };
    }

    private static float[][] buildSixteen() {
        float xl = MX + T / 2;
        float xr = DW - MX - T / 2 - 0.5f;
        float xc = (xl + xr) / 2;
        float yt = MY + T / 2;
        float ym = DH / 2;
        float yb = DH - MY - T / 2;
        float gap = 0.2f;
        float d = T * 0.75f;
        return new float[][] {
                {xl + E, yt, xc - gap, yt, T},                  // a top left
                {xc + gap, yt, xr - E, yt, T},                  // b top right
                {xr, yt + E, xr, ym - E * 0.5f, T},             // c upper right
                {xr, ym + E * 0.5f, xr, yb - E, T},             // d lower right
                {xc + gap, yb, xr - E, yb, T},                  // e bottom right
                {xl + E, yb, xc - gap, yb, T},                  // f bottom left
                {xl, ym + E * 0.5f, xl, yb - E, T},             // g lower left
                {xl, yt + E, xl, ym - E * 0.5f, T},             // h upper left
                {xl + E, ym, xc - gap, ym, T},                  // i middle left
                {xc + gap, ym, xr - E, ym, T},                  // j middle right
                {xl + E * 0.9f, yt + E * 1.1f, xc - 0.45f, ym - E * 0.9f, d},  // k diagonal upper left
                {xc, yt + E, xc, ym - E * 0.5f, T},             // l centre upper
                {xr - E * 0.9f, yt + E * 1.1f, xc + 0.45f, ym - E * 0.9f, d},  // m diagonal upper right
                {xr - E * 0.9f, yb - E * 1.1f, xc + 0.45f, ym + E * 0.9f, d},  // n diagonal lower right
                {xc, ym + E * 0.5f, xc, yb - E, T},             // o centre lower
                {xl + E * 0.9f, yb - E * 1.1f, xc - 0.45f, ym + E * 0.9f, d},  // p diagonal lower left
                {DP_X, DP_Y, DP_X, DP_Y, DP_SIZE},              // dp
        };
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        SegmentDisplayData data = (SegmentDisplayData) component.data();
        float[][] table = sixteen ? SIXTEEN : SEVEN;
        sink.box(0, 0, component.w(), component.h(), 0, 1.0f, GLASS, false);
        int dim = QuadSink.shade(data.color, 0.13f);
        float pitch = SegmentDisplayType.digitWidth(component.h());
        float sx = pitch / DW;
        float sy = component.h() / DH;
        float sw = (float) Math.sqrt(sx * sy);
        int digits = Math.max(1, (int) (component.w() / pitch));
        for (int digit = 0; digit < digits; digit++) {
            float dx = digit * pitch;
            for (float[] s : table) {
                Shapes.bar(sink, dx + s[0] * sx, s[1] * sy, dx + s[2] * sx, s[3] * sy, s[4] * sw, 1.05f, dim, false);
            }
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        SegmentDisplayData data = (SegmentDisplayData) component.data();
        int[] masks = data.masks(component);
        float[][] table = sixteen ? SIXTEEN : SEVEN;
        int color = data.color;
        float pitch = SegmentDisplayType.digitWidth(component.h());
        float sx = pitch / DW;
        float sy = component.h() / DH;
        float sw = (float) Math.sqrt(sx * sy);
        for (int digit = 0; digit < masks.length; digit++) {
            int mask = masks[digit];
            if (mask == 0) {
                continue;
            }
            float dx = digit * pitch;
            for (int bit = 0; bit < table.length; bit++) {
                if ((mask >> bit & 1) != 0) {
                    float[] s = table[bit];
                    Shapes.bar(context, dx + s[0] * sx, s[1] * sy, dx + s[2] * sx, s[3] * sy, s[4] * sw, 1.2f, color, true);
                }
            }
        }
    }
}
