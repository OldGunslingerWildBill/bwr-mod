package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SegmentDisplayData;

/**
 * 7- and 16-segment displays. The static mesh is a black glass plate with
 * every segment of every digit drawn dark; each frame the lit segments are
 * drawn on top, full-bright, from the mask array the data keeps (recomputed
 * only when the text, an input or the config changed). A digit cell is 2 x 3
 * cells; segment geometry is a table of lines in digit space, built once.
 */
final class SegmentDisplayModel implements ComponentModel {
    private static final int GLASS = 0x0B0D0E;
    private static final float T = 0.24f;
    private static final float MX = 0.28f;
    private static final float MY = 0.28f;
    private static final float DP_X = 1.86f;
    private static final float DP_Y = 2.72f;
    private static final float DP_SIZE = 0.22f;

    /** x0, y0, x1, y1, width per segment; the last entry is the decimal point. */
    private static final float[][] SEVEN = buildSeven();
    private static final float[][] SIXTEEN = buildSixteen();

    private final boolean sixteen;

    SegmentDisplayModel(boolean sixteen) {
        this.sixteen = sixteen;
    }

    private static float[][] buildSeven() {
        float xl = MX + T / 2;
        float xr = 2 - MX - T / 2;
        float yt = MY + T / 2;
        float ym = 1.5f;
        float yb = 3 - MY - T / 2;
        return new float[][] {
                {MX + 0.2f, yt, 2 - MX - 0.2f, yt, T},          // a
                {xr, MY + 0.2f, xr, ym - 0.14f, T},             // b
                {xr, ym + 0.14f, xr, 3 - MY - 0.2f, T},         // c
                {MX + 0.2f, yb, 2 - MX - 0.2f, yb, T},          // d
                {xl, ym + 0.14f, xl, 3 - MY - 0.2f, T},         // e
                {xl, MY + 0.2f, xl, ym - 0.14f, T},             // f
                {MX + 0.2f, ym, 2 - MX - 0.2f, ym, T},          // g
                {DP_X, DP_Y, DP_X, DP_Y, DP_SIZE},              // dp
        };
    }

    private static float[][] buildSixteen() {
        float xl = MX + T / 2;
        float xr = 2 - MX - T / 2;
        float yt = MY + T / 2;
        float ym = 1.5f;
        float yb = 3 - MY - T / 2;
        float d = 0.18f;
        return new float[][] {
                {MX + 0.2f, yt, 0.92f, yt, T},                  // a top left
                {1.08f, yt, 2 - MX - 0.2f, yt, T},              // b top right
                {xr, MY + 0.2f, xr, ym - 0.14f, T},             // c upper right
                {xr, ym + 0.14f, xr, 3 - MY - 0.2f, T},         // d lower right
                {1.08f, yb, 2 - MX - 0.2f, yb, T},              // e bottom right
                {MX + 0.2f, yb, 0.92f, yb, T},                  // f bottom left
                {xl, ym + 0.14f, xl, 3 - MY - 0.2f, T},         // g lower left
                {xl, MY + 0.2f, xl, ym - 0.14f, T},             // h upper left
                {MX + 0.2f, ym, 0.92f, ym, T},                  // i middle left
                {1.08f, ym, 2 - MX - 0.2f, ym, T},              // j middle right
                {MX + 0.3f, MY + 0.35f, 0.88f, ym - 0.28f, d},  // k diagonal upper left
                {1.0f, MY + 0.2f, 1.0f, ym - 0.14f, T},         // l centre upper
                {2 - MX - 0.3f, MY + 0.35f, 1.12f, ym - 0.28f, d}, // m diagonal upper right
                {2 - MX - 0.3f, 3 - MY - 0.35f, 1.12f, ym + 0.28f, d}, // n diagonal lower right
                {1.0f, ym + 0.14f, 1.0f, 3 - MY - 0.2f, T},     // o centre lower
                {MX + 0.3f, 3 - MY - 0.35f, 0.88f, ym + 0.28f, d}, // p diagonal lower left
                {DP_X, DP_Y, DP_X, DP_Y, DP_SIZE},              // dp
        };
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        SegmentDisplayData data = (SegmentDisplayData) component.data();
        float[][] table = sixteen ? SIXTEEN : SEVEN;
        sink.box(0, 0, component.w(), component.h(), 0, 0.6f, GLASS, false);
        int dim = QuadSink.shade(data.color, 0.13f);
        int digits = component.w() / 2;
        for (int digit = 0; digit < digits; digit++) {
            float dx = digit * 2.0f;
            for (float[] s : table) {
                Shapes.bar(sink, dx + s[0], s[1], dx + s[2], s[3], s[4], 0.65f, dim, false);
            }
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        SegmentDisplayData data = (SegmentDisplayData) component.data();
        int[] masks = data.masks(component);
        float[][] table = sixteen ? SIXTEEN : SEVEN;
        int color = data.color;
        for (int digit = 0; digit < masks.length; digit++) {
            int mask = masks[digit];
            if (mask == 0) {
                continue;
            }
            float dx = digit * 2.0f;
            for (int bit = 0; bit < table.length; bit++) {
                if ((mask >> bit & 1) != 0) {
                    float[] s = table[bit];
                    Shapes.bar(context, dx + s[0], s[1], dx + s[2], s[3], s[4], 0.75f, color, true);
                }
            }
        }
    }
}
