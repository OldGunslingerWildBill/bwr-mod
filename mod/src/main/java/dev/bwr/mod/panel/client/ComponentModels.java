package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Client registry from component type id to its {@link ComponentModel}. One
 * line per type in {@link #bootstrap()}; a type without a model draws as a grey
 * slab so a half-finished type is visible rather than invisible. To swap a
 * procedural body for a baked Blender model, register the new model under the
 * same id (re-registering replaces).
 */
public final class ComponentModels {
    private ComponentModels() {
    }

    private static final Map<ResourceLocation, ComponentModel> MODELS = new HashMap<>();

    /** Placeholder for types that have no model yet. */
    private static final ComponentModel SLAB = new ComponentModel() {
        @Override
        public void buildStatic(QuadSink sink, PanelComponent component) {
            sink.box(0, 0, component.w(), component.h(), 0, 1, 0x808080, false);
        }

        @Override
        public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        }
    };

    static {
        bootstrap();
    }

    private static void bootstrap() {
        register(ComponentTypes.NAMEPLATE.id(), new NameplateModel());
        register(ComponentTypes.LAMP.id(), new LampModel());
    }

    public static void register(ResourceLocation typeId, ComponentModel model) {
        MODELS.put(typeId, model);
    }

    public static ComponentModel get(ResourceLocation typeId) {
        ComponentModel model = MODELS.get(typeId);
        return model == null ? SLAB : model;
    }
}
