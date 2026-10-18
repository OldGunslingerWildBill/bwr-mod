package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ComponentType;
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
        register(ComponentTypes.LAMP.id(), styled(new LampModel(), BakedComponentModel.Kind.LAMP, ComponentTypes.LAMP));
        // inputs and indicators (spec 2.1, first two tables)
        register(ComponentTypes.PUSHBUTTON.id(),
                styled(new PushbuttonModel(), BakedComponentModel.Kind.PUSHBUTTON, ComponentTypes.PUSHBUTTON));
        register(ComponentTypes.TOGGLE_SWITCH.id(), new ToggleSwitchModel());
        register(ComponentTypes.ROTARY_SELECTOR.id(),
                styled(new RotarySelectorModel(), BakedComponentModel.Kind.ROTARY_SELECTOR, ComponentTypes.ROTARY_SELECTOR));
        register(ComponentTypes.CONTROL_SWITCH.id(),
                styled(new ControlSwitchModel(), BakedComponentModel.Kind.CONTROL_SWITCH, ComponentTypes.CONTROL_SWITCH));
        register(ComponentTypes.KEY_SWITCH.id(),
                styled(new KeySwitchModel(), BakedComponentModel.Kind.KEY_SWITCH, ComponentTypes.KEY_SWITCH));
        register(ComponentTypes.SETPOINT_KNOB.id(),
                styled(new SetpointKnobModel(), BakedComponentModel.Kind.SETPOINT_KNOB, ComponentTypes.SETPOINT_KNOB));
        register(ComponentTypes.THUMBWHEEL.id(), new ThumbwheelModel());
        register(ComponentTypes.ANNUNCIATOR.id(), new AnnunciatorModel());
        register(ComponentTypes.BAR_GRAPH.id(), new BarGraphModel());
        // gauges, displays and mimic
        register(ComponentTypes.DIAL_GAUGE.id(),
                styled(new DialGaugeModel(), BakedComponentModel.Kind.DIAL_GAUGE, ComponentTypes.DIAL_GAUGE));
        register(ComponentTypes.EDGEWISE_GAUGE_VERTICAL.id(),
                styled(new EdgewiseGaugeModel(true), BakedComponentModel.Kind.EDGEWISE, ComponentTypes.EDGEWISE_GAUGE_VERTICAL));
        register(ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL.id(),
                styled(new EdgewiseGaugeModel(false), BakedComponentModel.Kind.EDGEWISE, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL));
        register(ComponentTypes.STRIP_CHART.id(), new StripChartModel());
        register(ComponentTypes.SEVEN_SEGMENT_DISPLAY.id(), new SegmentDisplayModel(false));
        register(ComponentTypes.SIXTEEN_SEGMENT_DISPLAY.id(), new SegmentDisplayModel(true));
        register(ComponentTypes.CHARACTER_LCD.id(), new LcdModel());
        register(ComponentTypes.GRAPHIC_DISPLAY.id(), new GraphicDisplayModel());
        register(ComponentTypes.MIMIC_LINE.id(), new MimicLineModel());
        register(ComponentTypes.MIMIC_SYMBOL.id(), new MimicSymbolModel());
        register(ComponentTypes.FULL_CORE_DISPLAY.id(), new FullCoreDisplayModel());
        register(ComponentTypes.ROD_SELECT_MATRIX.id(), new RodSelectMatrixModel());
        register(ComponentTypes.PAPERLESS_RECORDER.id(), new RecorderModel());
        // the owner's CAD hardware: one fixed model each, the vertical edgewise strip as the fallback look of the bar gauge
        register(ComponentTypes.BAR_GAUGE.id(), new StyledComponentModel(new EdgewiseGaugeModel(true),
                BakedComponentModel.Kind.EDGEWISE, ComponentTypes.BAR_GAUGE.id().getPath(), "bar_gauge"));
        register(ComponentTypes.RPS_TRIP.id(), new StyledComponentModel(new RpsTripModel(), BakedComponentModel.Kind.RPS_TRIP,
                ComponentTypes.RPS_TRIP.id().getPath(), "rps_trip_assembly"));
        register(ComponentTypes.PUSH_TURN_SWITCH.id(), new StyledComponentModel(new PushTurnModel(),
                BakedComponentModel.Kind.PUSH_TURN, ComponentTypes.PUSH_TURN_SWITCH.id().getPath(), "push_turn_switch"));
    }

    /**
     * The model for a type that has baked looks: it draws whatever baked model the component's style selects and
     * falls back to {@code classic} (the procedural placeholder) when the style is classic or the model is missing.
     */
    private static ComponentModel styled(ComponentModel classic, BakedComponentModel.Kind kind, ComponentType type) {
        return new StyledComponentModel(classic, kind, type.id().getPath());
    }

    public static void register(ResourceLocation typeId, ComponentModel model) {
        // FCD and rod matrix already fit geometry to the real row aspect.
        MODELS.put(typeId, model instanceof StyledComponentModel || model instanceof FullCoreDisplayModel
                || model instanceof RodSelectMatrixModel || model instanceof RecorderModel ? model : new ProportionedComponentModel(model));
    }

    public static ComponentModel get(ResourceLocation typeId) {
        ComponentModel model = MODELS.get(typeId);
        return model == null ? SLAB : model;
    }
}
