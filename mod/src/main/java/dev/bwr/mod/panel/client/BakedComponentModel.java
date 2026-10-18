package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.KeySwitchData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Detents;
import dev.bwr.mod.panel.model.FlagState;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Poses;
import dev.bwr.mod.panel.model.Recolour;

import java.util.ArrayList;
import java.util.List;

/**
 * One component drawn from a baked Blender model: made once per component revision by
 * {@link StyledComponentModel} (so everything here that depends only on configuration is worked out then), and
 * asked every frame to draw the moving parts.
 *
 * <h3>What is recorded and what is drawn per frame</h3>
 * <ul>
 *   <li>{@link #buildStatic}: parts that never move and never change colour with state (role paint or needle
 *       without a pivot or travel), scaled to the component's cells.</li>
 *   <li>{@link #renderDynamic}: parts with a pivot or travel (handles, keys, knobs, needles, caps), lamp lenses
 *       (glowing when lit, dim when off), target flags (red or green), then the legends at the label anchors
 *       (full detail only), then the glass.</li>
 * </ul>
 * Glass is drawn per frame, not recorded: it is translucent and must come after everything it covers.
 *
 * <h3>With the panel's GPU cache</h3>
 * The renderer keeps the solid geometry of a whole panel block in one vertex buffer (see {@link PanelMeshCache})
 * and splits {@link #renderDynamic} in three: {@link #updatePose} reads the component's state once a frame,
 * {@link #poseKey} says whether the posed geometry changed (it then rebuilds the block's buffer through
 * {@link #emitPosed}, which draws the parts that only change when the component is operated: handles, caps,
 * keys, knobs, lenses, flags), and every frame {@link #renderLive} draws what moves continuously (the pointers
 * of the two meters, which follow a signal), then {@link #renderLegends} and {@link #renderGlass}. The renderer
 * calls each of the three for all the components of a block in turn, so the buffer source switches render type
 * (solid, text, glass) a few times per block instead of a few times per component.
 *
 * <h3>State to pose</h3>
 * See {@link Poses}: a control switch handle swings -45, 0, +45 degrees for CLOSE, NORMAL, OPEN; a selector
 * steps 45 degrees per detent about its middle; a key turns a quarter; the setpoint knob sweeps 270 degrees; a
 * dial pointer sweeps 250 degrees and an edgewise pointer slides its travel, both over the gauge scale with the
 * configured lag; a pushbutton cap slides its travel while held.
 */
final class BakedComponentModel {
    /** Which component type is being drawn: decides how state becomes pose. */
    enum Kind {
        CONTROL_SWITCH, ROTARY_SELECTOR, KEY_SWITCH, PUSHBUTTON, LAMP, SETPOINT_KNOB, DIAL_GAUGE, EDGEWISE,
        /** The RPS trip assembly: two caps, {@code cap_a} and {@code cap_b}, each slides while its output is 1. */
        RPS_TRIP,
        /** The push-to-turn switch: one knob that turns to AUTO or ARM and slides in while pushed. */
        PUSH_TURN
    }

    /** Brightness of an unlit lamp lens (the classic models use the same factor). */
    static final float LAMP_OFF = 0.22f;
    /** Name of the optional demand-marker part of an edgewise meter. */
    static final String DEMAND = "demand";
    /** Name of the second cap of an RPS trip assembly (the first, {@code cap_a}, follows the common slide). */
    static final String CAP_B = "cap_b";
    /** Name of a tumbler lock's plug: turns 0 locked, 90 degrees unlocked (the key switch is on). */
    static final String PLUG = "plug";
    static final float PLUG_UNLOCKED_DEG = 90.0f;
    /** Lift of legends above the surface under them, cells. */
    private static final float TEXT_LIFT = 0.05f;

    private final Kind kind;
    private final Placement place;
    /** The printed face of a bar gauge (scale, bands, units, caption), or null for every other model. */
    private final BarGaugeFace face;
    /** The colour bands laid on a baked switchboard meter, or null when it has none in use. */
    private final BandOverlay bands;
    private final BakedPanelModel.Part[] staticParts;
    private final int[] staticBase;
    private final BakedPanelModel.Part[] dynamicParts;
    private final int[] dynamicBase;
    private final BakedPanelModel.Part[] glassParts;
    private final Legends.Item[] items;
    private final float[] itemWidth;
    private final int legendCapRgb;
    /** Handle angle of each detent of a selector or key switch (from the legend anchors when the model has them). */
    private final float[] detents;
    private final float[] scratch = new float[PartDraw.SCRATCH];
    private boolean measured;
    /** Whether the moving parts follow a signal (a meter's pointer): they are drawn live, never kept in the block's buffer. */
    private final boolean continuous;

    // The pose of the moving parts this frame, set by updatePose.
    private float poseAngle;
    private float poseSlide;
    private float poseSlideB;
    private float poseDemand = Float.NaN;
    private boolean poseLit;
    private int poseFlagRgb = FlagState.NEUTRAL;

    private BakedComponentModel(BakedPanelModel model, PanelStyles.Resolved resolved, PanelComponent component, Kind kind,
                                String type, float rowAspect) {
        int needleRgb = resolved.needleRgb();
        this.kind = kind;
        this.continuous = kind == Kind.DIAL_GAUGE || kind == Kind.EDGEWISE;
        ComponentData data = component.data();
        if (data instanceof GaugeData gauge && gauge.isBarGauge()) {
            this.face = new BarGaugeFace(model, gauge, component.w(), component.h(), rowAspect);
            this.place = face.place;
        } else {
            this.face = null;
            this.place = Placement.fit(model, component.w(), component.h(), rowAspect);
        }
        this.bands = data instanceof GaugeData gauge && face == null && (kind == Kind.DIAL_GAUGE || kind == Kind.EDGEWISE)
                ? BandOverlay.of(model, place, gauge, kind == Kind.DIAL_GAUGE) : null;
        this.legendCapRgb = Recolour.configuredColour(data);
        List<BakedPanelModel.Part> statics = new ArrayList<>();
        List<BakedPanelModel.Part> dynamics = new ArrayList<>();
        List<BakedPanelModel.Part> glass = new ArrayList<>();
        for (int i = 0; i < model.partCount(); i++) {
            BakedPanelModel.Part part = model.part(i);
            if (part.role == BakedPanelModel.Role.GLASS) {
                glass.add(part);
            } else if (part.moving() || part.role == BakedPanelModel.Role.LAMP || part.role == BakedPanelModel.Role.FLAG
                    || part.name.equals(DEMAND)) {
                dynamics.add(part);
            } else {
                statics.add(part);
            }
        }
        this.staticParts = statics.toArray(new BakedPanelModel.Part[0]);
        this.dynamicParts = dynamics.toArray(new BakedPanelModel.Part[0]);
        this.glassParts = glass.toArray(new BakedPanelModel.Part[0]);
        this.staticBase = bases(staticParts, data, needleRgb);
        this.dynamicBase = bases(dynamicParts, data, needleRgb);
        List<Legends.Item> legends = Legends.items(model, place, type, component, resolved.key());
        if (face != null) {
            legends.addAll(face.legends(component.label()));
        }
        this.items = legends.toArray(new Legends.Item[0]);
        this.itemWidth = new float[items.length];
        this.detents = switch (kind) {
            case ROTARY_SELECTOR -> Detents.angles(model, ((RotarySelectorData) data).positions);
            case KEY_SWITCH -> resolved.key() != null
                    ? new float[] {resolved.key().offDegrees(), resolved.key().onDegrees()}
                    : new float[] {Poses.keyAngle(false), Poses.keyAngle(true)};
            default -> new float[1];
        };
    }

    /**
     * The model that draws a bar gauge, an RPS trip assembly or a push-to-turn switch: one fixed model (they have no
     * style table row, so no styles and variants), or null when the model file is not loaded and the classic look is
     * drawn instead.
     */
    static BakedComponentModel createFixed(PanelComponent component, Kind kind, String type, String modelId, float rowAspect) {
        BakedPanelModel model = PanelModels.get(modelId);
        return model == null ? null
                : new BakedComponentModel(model, new PanelStyles.Resolved(modelId, PanelStyles.NO_COLOUR, null), component, kind,
                        type, rowAspect);
    }

    /**
     * The model that draws {@code component}, or null for the classic procedural look: its style is classic,
     * the table has no row for the choice, or none of the row's model files is loaded. {@code rowAspect} is the
     * real length of a row relative to a column on the panel it is drawn on (see {@link Placement}).
     */
    static BakedComponentModel create(PanelComponent component, Kind kind, String type, float rowAspect) {
        ComponentData data = component.data();
        String style;
        String variant;
        if (data instanceof ControlSwitchData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof RotarySelectorData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof KeySwitchData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof PushbuttonData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof LampData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof SetpointKnobData d) {
            style = d.style;
            variant = d.variant;
        } else if (data instanceof GaugeData d) {
            style = d.style;
            variant = d.variant;
        } else {
            return null;
        }
        PanelStyles.Resolved resolved = PanelStyles.resolve(type, style, variant, PanelModels::has);
        if (resolved == null) {
            return null;
        }
        BakedPanelModel model = PanelModels.get(resolved.modelId());
        return model == null ? null : new BakedComponentModel(model, resolved, component, kind, type, rowAspect);
    }

    // ---- colour

    private static int[] bases(BakedPanelModel.Part[] parts, ComponentData data, int needleRgb) {
        int[] bases = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bases[i] = Recolour.baseFor(parts[i], data, needleRgb);
        }
        return bases;
    }

    // ---- drawing

    void buildStatic(QuadSink sink) {
        if (face != null) {
            face.emitStatic(sink);
        }
        for (int i = 0; i < staticParts.length; i++) {
            PartDraw.emit(sink, staticParts[i], place, 0.0f, 0.0f, staticBase[i], 1.0f, false, scratch);
        }
        if (bands != null) {
            bands.emit(sink);
        }
    }

    /** Draws everything that moves, the legends and the glass straight to {@code context}: the panel without its GPU cache. */
    void renderDynamic(PanelRenderContext context, PanelComponent component) {
        updatePose(context, component);
        emitMoving(context);
        renderLegends(context);
        renderGlass(context);
    }

    /**
     * Reads the component's state into the pose of the moving parts. Once per frame, before {@link #poseKey},
     * {@link #emitPosed} and {@link #renderLive}: a meter's pointer lags the signal by the frame time, so this
     * is not something to call twice.
     */
    void updatePose(PanelRenderContext context, PanelComponent component) {
        float angle = 0.0f;
        float slide = 0.0f;
        float slideB = 0.0f;
        float demand = Float.NaN;
        boolean lit = false;
        int flagRgb = FlagState.NEUTRAL;
        switch (kind) {
            case CONTROL_SWITCH -> {
                ControlSwitchData data = (ControlSwitchData) component.data();
                angle = Poses.controlSwitchAngle((int) Math.round(component.output(0)));
                boolean red = LampData.active(component.input(0));
                boolean green = LampData.active(component.input(1));
                flagRgb = FlagState.colour(red, green, data.lastOperated,
                        !ControlSwitchData.FLAG_RED_AFTER_OPEN.equals(data.flagRed));
                lit = red || green;
            }
            case ROTARY_SELECTOR -> angle = detents[Math.max(0, Math.min(detents.length - 1, (int) component.output(0)))];
            case KEY_SWITCH -> {
                lit = component.output(0) > 0.5;
                angle = detents[lit ? 1 : 0];
            }
            case PUSHBUTTON -> {
                lit = component.output(0) > 0.5;
                slide = Poses.capSlide(lit);
            }
            case LAMP -> {
                LampData data = (LampData) component.data();
                lit = LampData.active(component.input(0))
                        && !(LampData.SLOW.equals(data.flash) && !context.blinkOn(1.0f))
                        && !(LampData.FAST.equals(data.flash) && !context.blinkOn(4.0f));
            }
            case SETPOINT_KNOB -> angle = Poses.knobAngle(component.output(0));
            case RPS_TRIP -> {
                slide = Poses.capSlide(component.output(0) > 0.5);
                slideB = Poses.capSlide(component.output(1) > 0.5);
            }
            case PUSH_TURN -> {
                angle = Poses.detentAngle(component.output(0) > 0.5 ? 1 : 0, 2);
                slide = Poses.capSlide(component.output(1) > 0.5);
            }
            case DIAL_GAUGE -> {
                GaugeData data = (GaugeData) component.data();
                float position = GaugeScratch.of(component).follow((float) data.fraction(component.input(0)),
                        data.damping, context.gameTime());
                angle = Poses.needleAngle(position, Poses.DIAL_SWEEP_DEG);
            }
            case EDGEWISE -> {
                GaugeData data = (GaugeData) component.data();
                GaugeScratch scratchState = GaugeScratch.of(component);
                slide = Poses.pointerSlide(scratchState.follow((float) data.fraction(component.input(0)),
                        data.damping, context.gameTime()));
                double wanted = component.input(1);
                if (Double.isNaN(wanted)) {
                    scratchState.markerPrimed = false;
                } else {
                    demand = Poses.pointerSlide(scratchState.followMarker((float) data.fraction(wanted), data.damping));
                }
            }
        }
        poseAngle = angle;
        poseSlide = slide;
        poseSlideB = slideB;
        poseDemand = demand;
        poseLit = lit;
        poseFlagRgb = flagRgb;
    }

    /**
     * A key for the geometry {@link #emitPosed} would draw in the current pose: equal keys mean equal geometry.
     * Zero for a meter, whose moving parts are drawn live and never recorded.
     */
    long poseKey() {
        if (continuous) {
            return 0L;
        }
        long key = Float.floatToIntBits(poseAngle);
        key = key * 31 + Float.floatToIntBits(poseSlide);
        key = key * 31 + Float.floatToIntBits(poseSlideB);
        key = key * 31 + (poseLit ? 1 : 0);
        return key * 31 + poseFlagRgb;
    }

    /** The parts that only change when the component is operated, in the current pose (for the block's vertex buffer). */
    void emitPosed(QuadSink sink) {
        if (!continuous) {
            emitMoving(sink);
        }
    }

    /** What changes without the component being operated: a meter's pointer. */
    void renderLive(PanelRenderContext context) {
        if (continuous) {
            emitMoving(context);
        }
    }

    /** Every part with a pivot or travel, lamp lens and target flag in the current pose. */
    private void emitMoving(QuadSink sink) {
        for (int i = 0; i < dynamicParts.length; i++) {
            BakedPanelModel.Part part = dynamicParts[i];
            float partSlide = part.hasTravel ? (kind == Kind.RPS_TRIP && part.name.equals(CAP_B) ? poseSlideB : poseSlide) : 0.0f;
            if (part.name.equals(DEMAND)) {
                if (Float.isNaN(poseDemand)) {
                    continue; // no demand signal: the marker is not shown
                }
                partSlide = poseDemand;
            }
            int base = dynamicBase[i];
            float brightness = 1.0f;
            boolean emissive = false;
            if (part.role == BakedPanelModel.Role.LAMP) {
                emissive = poseLit;
                brightness = poseLit ? 1.0f : LAMP_OFF;
            } else if (part.role == BakedPanelModel.Role.FLAG) {
                base = poseFlagRgb;
            }
            float partAngle = part.hasPivot ? poseAngle : 0.0f;
            if (kind == Kind.KEY_SWITCH && part.name.equals(PLUG)) {
                partAngle = poseLit ? PLUG_UNLOCKED_DEG : 0.0f; // a tumbler lock's plug follows the key, not the handle
            }
            PartDraw.emit(sink, part, place, partAngle, partSlide, base, brightness, emissive, scratch);
        }
    }

    /** The legends at the label anchors: full detail only, and never recorded (see the class comment). */
    void renderLegends(PanelRenderContext context) {
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            drawLegends(context, poseLit);
        }
    }

    /** The glass, translucent and so after everything it covers: full detail only, and never recorded. */
    void renderGlass(PanelRenderContext context) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        for (int i = 0; i < glassParts.length; i++) {
            PartDraw.emitGlass(context, glassParts[i], place, PartDraw.GLASS_ALPHA, scratch);
        }
    }

    private void drawLegends(PanelRenderContext context, boolean lit) {
        if (items.length == 0) {
            return;
        }
        if (!measured) {
            for (int i = 0; i < items.length; i++) {
                itemWidth[i] = context.font().width(items[i].text());
            }
            measured = true;
        }
        for (int i = 0; i < items.length; i++) {
            Legends.Item item = items[i];
            float width = itemWidth[i];
            float dx = switch (item.align()) {
                case LEFT -> 0.0f;
                case CENTER -> -width / 2.0f;
                case RIGHT -> -width;
            };
            // A legend printed on a lamp lens reads dark against the lit lens and light against the dim one.
            int ink = item.onLamp() ? Legends.lampInk(legendCapRgb >= 0 ? legendCapRgb : item.surfaceRgb(), lit) : item.ink();
            context.textRotated(item.text(), item.x(), item.y(), item.capCells() / Legends.CAP_PIXELS, item.rotation(),
                    item.z() + TEXT_LIFT, ink, item.onLamp(), dx, -Legends.CAP_PIXELS / 2.0f);
        }
    }
}
