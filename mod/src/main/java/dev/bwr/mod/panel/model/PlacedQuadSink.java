package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.client.QuadSink;

/** Applies the same physical fit to procedural geometry as to baked parts. */
public final class PlacedQuadSink implements QuadSink {
    private QuadSink target;
    public final Placement placement;
    public PlacedQuadSink(QuadSink target, Placement placement) { this.target=target; this.placement=placement; }
    public PlacedQuadSink target(QuadSink sink) { target=sink; return this; }
    @Override public void quad(float x0,float y0,float z0,float x1,float y1,float z1,
            float x2,float y2,float z2,float x3,float y3,float z3,int rgb,int normal,boolean emissive) {
        var p=placement;
        target.quad(p.x(x0),p.y(y0),p.z(z0),p.x(x1),p.y(y1),p.z(z1),
                p.x(x2),p.y(y2),p.z(z2),p.x(x3),p.y(y3),p.z(z3),rgb,normal,emissive);
    }
    @Override public void quad(float x0,float y0,float z0,float x1,float y1,float z1,
            float x2,float y2,float z2,float x3,float y3,float z3,int rgb,float nx,float ny,float nz,boolean emissive) {
        var p=placement;
        target.quad(p.x(x0),p.y(y0),p.z(z0),p.x(x1),p.y(y1),p.z(z1),
                p.x(x2),p.y(y2),p.z(z2),p.x(x3),p.y(y3),p.z(z3),rgb,nx,ny,nz,emissive);
    }
}
