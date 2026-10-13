package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/**
 * The model registered for a component type that has baked looks: it draws the baked model the component's
 * style and variant select (see {@code PanelStyles}) and otherwise hands over to the classic procedural model,
 * which is also what a missing or rejected model file means. The choice is made once per revision in
 * {@link #buildStatic} and kept in the component's {@link RenderCache}; the renderer also rebuilds the mesh
 * whenever the loaded model set changes, so a resource reload swaps looks without touching any component.
 */
final class StyledComponentModel implements ComponentModel {
    private final ComponentModel classic;
    private final BakedComponentModel.Kind kind;
    private final String type;

    StyledComponentModel(ComponentModel classic, BakedComponentModel.Kind kind, String type) {
        this.classic = classic;
        this.kind = kind;
        this.type = type;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        buildStatic(sink, component, 1.0f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        BakedComponentModel baked = BakedComponentModel.create(component, kind, type, rowAspect);
        RenderCache.of(component).baked = baked;
        if (baked == null) {
            classic.buildStatic(sink, component, rowAspect);
        } else {
            baked.buildStatic(sink);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            classic.renderDynamic(context, component);
        } else {
            baked.renderDynamic(context, component);
        }
    }
}
