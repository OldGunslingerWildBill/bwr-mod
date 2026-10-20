package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.Legends;

import java.util.List;

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
    /** The one model of a type that has no styles (bar gauge, RPS trip assembly, push-to-turn switch), or null. */
    private final String fixedModelId;

    StyledComponentModel(ComponentModel classic, BakedComponentModel.Kind kind, String type) {
        this(classic, kind, type, null);
    }

    StyledComponentModel(ComponentModel classic, BakedComponentModel.Kind kind, String type, String fixedModelId) {
        this.classic = new ProportionedComponentModel(classic);
        this.kind = kind;
        this.type = type;
        this.fixedModelId = fixedModelId;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        buildStatic(sink, component, 1.0f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        BakedComponentModel baked = fixedModelId == null ? BakedComponentModel.create(component, kind, type, rowAspect)
                : BakedComponentModel.createFixed(component, kind, type, fixedModelId, rowAspect);
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

    @Override
    public long poseKey(PanelRenderContext context, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            return classic.poseKey(context, component);
        }
        baked.updatePose(context, component);
        return baked.poseKey();
    }

    @Override
    public void emitPosed(QuadSink sink, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            classic.emitPosed(sink, component);
        } else {
            baked.emitPosed(sink);
        }
    }

    @Override
    public void renderLive(PanelRenderContext context, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            classic.renderLive(context, component);
        } else {
            baked.renderLive(context);
        }
    }

    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            classic.renderLegends(context, component);
        } else {
            baked.renderLegends(context);
        }
    }

    @Override
    public void renderGlass(PanelRenderContext context, PanelComponent component) {
        BakedComponentModel baked = RenderCache.of(component).baked;
        if (baked == null) {
            classic.renderGlass(context, component);
        } else {
            baked.renderGlass(context);
        }
    }

    /** The baked model's legends when the component draws baked, else the classic look's text. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        BakedComponentModel baked = fixedModelId == null ? BakedComponentModel.create(component, kind, type, rowAspect)
                : BakedComponentModel.createFixed(component, kind, type, fixedModelId, rowAspect);
        return baked == null ? classic.textLayout(component, rowAspect) : baked.legendItems();
    }
}
