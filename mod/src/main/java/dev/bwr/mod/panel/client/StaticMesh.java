package dev.bwr.mod.panel.client;

/** Immutable recorded geometry of one component, replayed through a {@link PanelRenderContext} with no allocation. */
public final class StaticMesh {
    public static final StaticMesh EMPTY = new StaticMesh(new float[0], new int[0], new byte[0]);

    private final float[] positions;
    private final int[] colors;
    private final byte[] flags;

    StaticMesh(float[] positions, int[] colors, byte[] flags) {
        this.positions = positions;
        this.colors = colors;
        this.flags = flags;
    }

    public int quadCount() {
        return colors.length;
    }

    public void emit(PanelRenderContext out) {
        for (int i = 0; i < colors.length; i++) {
            int b = i * 12;
            out.quad(positions[b], positions[b + 1], positions[b + 2], positions[b + 3], positions[b + 4],
                    positions[b + 5], positions[b + 6], positions[b + 7], positions[b + 8], positions[b + 9],
                    positions[b + 10], positions[b + 11], colors[i], flags[i] & 0x0F, (flags[i] & 0x10) != 0);
        }
    }
}
