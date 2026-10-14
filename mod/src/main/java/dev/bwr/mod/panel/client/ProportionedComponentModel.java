package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.PlacedQuadSink;

/** Preserves the vertical placeholder's proportions on every panel slope. */
final class ProportionedComponentModel implements ComponentModel {
    private final ComponentModel source;
    ProportionedComponentModel(ComponentModel source) { this.source=source; }
    @Override public void buildStatic(QuadSink sink,PanelComponent c) { buildStatic(sink,c,1); }
    @Override public void buildStatic(QuadSink sink,PanelComponent c,float aspect) {
        var fit=Placement.fit(c.w(),c.h(),c.w(),c.h(),aspect);
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
}
