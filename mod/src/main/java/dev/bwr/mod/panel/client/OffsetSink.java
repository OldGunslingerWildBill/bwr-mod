package dev.bwr.mod.panel.client;

/**
 * A {@link QuadSink} that moves everything it receives by a fixed offset in the face plane and passes it on: how a
 * model made for one tile or one module is drawn hundreds of times in one component. Allocation free after
 * construction; the target and the offset are set before each use.
 */
final class OffsetSink implements QuadSink {
    private QuadSink target;
    private float dx;
    private float dy;

    OffsetSink at(QuadSink target, float dx, float dy) {
        this.target = target;
        this.dx = dx;
        this.dy = dy;
        return this;
    }

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                     float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
        target.quad(x0 + dx, y0 + dy, z0, x1 + dx, y1 + dy, z1, x2 + dx, y2 + dy, z2, x3 + dx, y3 + dy, z3,
                rgb, normal, emissive);
    }

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                     float x3, float y3, float z3, int rgb, float nx, float ny, float nz, boolean emissive) {
        target.quad(x0 + dx, y0 + dy, z0, x1 + dx, y1 + dy, z1, x2 + dx, y2 + dy, z2, x3 + dx, y3 + dy, z3,
                rgb, nx, ny, nz, emissive);
    }

    @Override
    public void glassQuad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                          float x3, float y3, float z3, int rgb, int alpha, float nx, float ny, float nz) {
        target.glassQuad(x0 + dx, y0 + dy, z0, x1 + dx, y1 + dy, z1, x2 + dx, y2 + dy, z2, x3 + dx, y3 + dy, z3,
                rgb, alpha, nx, ny, nz);
    }
}
