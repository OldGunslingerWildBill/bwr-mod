package dev.bwr.mod.panel.client;

import java.util.Arrays;

/**
 * Records quads in component space so they can be replayed every frame without
 * re-running any model code. Build once per component revision, then
 * {@link #build()} into an immutable {@link StaticMesh}.
 *
 * <p>Quads recorded with an arbitrary normal keep it in a parallel array that is only allocated once the
 * first such quad arrives, so the procedural placeholder bodies cost nothing extra.
 */
public final class MeshBuilder implements QuadSink {
    private float[] positions = new float[12 * 16];
    private int[] colors = new int[16];
    private byte[] flags = new byte[16];
    private float[] normals;
    private int quads;

    private void reserve() {
        if (quads == colors.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
            flags = Arrays.copyOf(flags, flags.length * 2);
            if (normals != null) {
                normals = Arrays.copyOf(normals, normals.length * 2);
            }
        }
    }

    private int record(float x0, float y0, float z0, float x1, float y1, float z1,
                       float x2, float y2, float z2, float x3, float y3, float z3, int rgb, int flag) {
        reserve();
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
        flags[quads] = (byte) flag;
        return quads++;
    }

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3,
                     int rgb, int normal, boolean emissive) {
        record(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb, normal | (emissive ? 0x10 : 0));
    }

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3,
                     int rgb, float nx, float ny, float nz, boolean emissive) {
        if (normals == null) {
            normals = new float[colors.length * 3];
        }
        int index = record(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb,
                NORMAL_CUSTOM | (emissive ? 0x10 : 0));
        normals[index * 3] = nx;
        normals[index * 3 + 1] = ny;
        normals[index * 3 + 2] = nz;
    }

    public StaticMesh build() {
        return new StaticMesh(Arrays.copyOf(positions, quads * 12), Arrays.copyOf(colors, quads),
                Arrays.copyOf(flags, quads), normals == null ? null : Arrays.copyOf(normals, quads * 3));
    }
}
