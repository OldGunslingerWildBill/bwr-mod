package dev.bwr.mod.panel.client;

import java.util.Arrays;

/**
 * Records quads in component space so they can be replayed every frame without
 * re-running any model code. Build once per component revision, then
 * {@link #build()} into an immutable {@link StaticMesh}.
 */
public final class MeshBuilder implements QuadSink {
    private float[] positions = new float[12 * 16];
    private int[] colors = new int[16];
    private byte[] flags = new byte[16];
    private int quads;

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3,
                     int rgb, int normal, boolean emissive) {
        if (quads == colors.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
            flags = Arrays.copyOf(flags, flags.length * 2);
        }
        int base = quads * 12;
        positions[base] = x0;
        positions[base + 1] = y0;
        positions[base + 2] = z0;
        positions[base + 3] = x1;
        positions[base + 4] = y1;
        positions[base + 5] = z1;
        positions[base + 6] = x2;
        positions[base + 7] = y2;
        positions[base + 8] = z2;
        positions[base + 9] = x3;
        positions[base + 10] = y3;
        positions[base + 11] = z3;
        colors[quads] = rgb;
        flags[quads] = (byte) (normal | (emissive ? 0x10 : 0));
        quads++;
    }

    public StaticMesh build() {
        return new StaticMesh(Arrays.copyOf(positions, quads * 12), Arrays.copyOf(colors, quads),
                Arrays.copyOf(flags, quads));
    }
}
