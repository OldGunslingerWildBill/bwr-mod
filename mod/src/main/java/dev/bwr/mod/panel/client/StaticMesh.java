package dev.bwr.mod.panel.client;

/** Immutable recorded geometry of one component, replayed through a {@link PanelRenderContext} with no allocation. */
public final class StaticMesh {
    public static final StaticMesh EMPTY = new StaticMesh(new float[0], new int[0], new byte[0], null);

    private final float[] positions;
    private final int[] colors;
    private final byte[] flags;
    /** Three floats per quad for quads recorded with {@link QuadSink#NORMAL_CUSTOM}; null if there are none. */
    private final float[] normals;

    StaticMesh(float[] positions, int[] colors, byte[] flags, float[] normals) {
        this.positions = positions;
        this.colors = colors;
        this.flags = flags;
        this.normals = normals;
    }

    public int quadCount() {
        return colors.length;
    }

    public void emit(QuadSink out) {
        for (int i = 0; i < colors.length; i++) {
            int b = i * 12;
            int kind = flags[i] & 0x0F;
            boolean emissive = (flags[i] & 0x10) != 0;
            if (kind == QuadSink.NORMAL_CUSTOM) {
                out.quad(positions[b], positions[b + 1], positions[b + 2], positions[b + 3], positions[b + 4],
                        positions[b + 5], positions[b + 6], positions[b + 7], positions[b + 8], positions[b + 9],
                        positions[b + 10], positions[b + 11], colors[i],
                        normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2], emissive);
            } else {
                out.quad(positions[b], positions[b + 1], positions[b + 2], positions[b + 3], positions[b + 4],
                        positions[b + 5], positions[b + 6], positions[b + 7], positions[b + 8], positions[b + 9],
                        positions[b + 10], positions[b + 11], colors[i], kind, emissive);
            }
        }
    }
}
