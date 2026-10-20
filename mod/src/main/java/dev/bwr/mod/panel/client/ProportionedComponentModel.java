package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.PlacedQuadSink;

import java.util.ArrayList;
import java.util.List;

/** Preserves the vertical placeholder's proportions on every panel slope. */
final class ProportionedComponentModel implements ComponentModel {
    private final ComponentModel source;
    ProportionedComponentModel(ComponentModel source) { this.source=source; }

    /**
     * How much of its elevation a placeholder keeps: the parts are drawn a cell or two proud, which suits a
     * component a few cells across; a real size one (a cell or two) would grow a post. Components 3 cells across
     * or more (every size before real sizes) keep their elevation as drawn.
     */
    static float elevationFactor(PanelComponent c) {
        return Math.min(1.0f, Math.min(c.w(), c.h()) / 3.0f);
    }
    @Override public void buildStatic(QuadSink sink,PanelComponent c) { buildStatic(sink,c,1); }
    @Override public void buildStatic(QuadSink sink,PanelComponent c,float aspect) {
        var fit=Placement.fit(c.w(),c.h(),c.w(),c.h(),aspect).withElevation(elevationFactor(c));
        var placed=new PlacedQuadSink(sink,fit);
        RenderCache.of(c).classicSink=placed;
        source.buildStatic(placed,c);
    }
    @Override public long poseKey(PanelRenderContext ctx,PanelComponent c) { return source.poseKey(ctx,c); }
    @Override public void emitPosed(QuadSink sink,PanelComponent c) {
        source.emitPosed(RenderCache.of(c).classicSink.target(sink),c);
    }
    @Override public boolean legendsCached() { return source.legendsCached(); }
    @Override public void renderDynamic(PanelRenderContext ctx,PanelComponent c) {
        ctx.classicPlacement(RenderCache.of(c).classicSink.placement);
        try { source.renderDynamic(ctx,c); } finally { ctx.classicPlacement(null); }
    }
    @Override public void renderLive(PanelRenderContext ctx,PanelComponent c) {
        ctx.classicPlacement(RenderCache.of(c).classicSink.placement);
        try { source.renderLive(ctx,c); } finally { ctx.classicPlacement(null); }
    }
    @Override public void renderLegends(PanelRenderContext ctx,PanelComponent c) {
        ctx.classicPlacement(RenderCache.of(c).classicSink.placement);
        try { source.renderLegends(ctx,c); } finally { ctx.classicPlacement(null); }
    }
    @Override public void emitLegends(PanelRenderContext ctx,PanelComponent c) {
        ctx.classicPlacement(RenderCache.of(c).classicSink.placement);
        try { source.emitLegends(ctx,c); } finally { ctx.classicPlacement(null); }
    }
    @Override public void renderGlass(PanelRenderContext ctx,PanelComponent c) {
        ctx.classicPlacement(RenderCache.of(c).classicSink.placement);
        try { source.renderGlass(ctx,c); } finally { ctx.classicPlacement(null); }
    }
    /** The placeholder's text, mapped through the same fit as its geometry. */
    @Override public List<Legends.Item> textLayout(PanelComponent c, float aspect) {
        var fit=Placement.fit(c.w(),c.h(),c.w(),c.h(),aspect).withElevation(elevationFactor(c));
        List<Legends.Item> out=new ArrayList<>();
        for (Legends.Item i : source.textLayout(c,aspect)) {
            out.add(new Legends.Item(i.text(),fit.x(i.x()),fit.y(i.y()),fit.z(i.z()),fit.length(i.capCells()),i.rotation(),
                    i.align(),i.ink(),i.onLamp(),i.surfaceRgb(),i.tilt()));
        }
        return out;
    }
}
