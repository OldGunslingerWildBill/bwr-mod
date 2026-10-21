package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.client.BarGaugeFace;
import dev.bwr.mod.panel.client.ComponentModel;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.BarGraphData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModels;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The regression guard for how gauge text and tick marks scale with the component ({@link GaugeScale}): every
 * gauge-like component type is drawn through its real registered model ({@link ComponentModels}: the baked model where
 * one is loaded, the classic look otherwise) into a recording sink at all its sizes, and its printed text
 * ({@link ComponentModel#textLayout}) and tick marks are measured as shares of the size it is drawn at.
 *
 * <ul>
 *   <li>Scale invariance: a size that fits at least twice into the type's design size (its first footprint) is
 *       compared with that multiple of itself (the same shape, bigger). Its text and marks may be no more than
 *       {@value #HIGH} times their share there (a least size fixed in cells, the bug this guards, made a 1 x 3 bar
 *       gauge's numbers twice and its ticks five times their share) and no less than {@value #LOW} (they do not vanish;
 *       a real size bar graph's finer LEDs are 0.75). Every other size may be no more than {@value #BIG_HIGH} times
 *       the design size's share.</li>
 *   <li>Fit: at every size, and at Text size 50, 100 and 200 %, every piece of text lies inside the drawn component
 *       and clear of every other.</li>
 *   <li>Coverage: every registered component type is either probed here or listed in {@link #NOT_GAUGES} with the
 *       reason, so a new type fails until someone decides.</li>
 * </ul>
 * Pure: no world, no fixture origin.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GaugeScaleGuardTests {
    private GaugeScaleGuardTests() {
    }

    /** Share ratios allowed for a size against its multiple (see the class comment). */
    static final float HIGH = 1.1f;
    static final float LOW = 0.6f;
    static final float BIG_HIGH = 1.25f;
    /** The classic gauges' ink (DialGaugeModel, EdgewiseGaugeModel), the GE meters' printed ink, the thumbwheel's engraved marks. */
    private static final int CLASSIC_INK = 0x202020;
    private static final int GE_INK = 0x1A1A1A;
    private static final int ENGRAVED = 0xE8E8E8;

    /**
     * One gauge-like look to check: a type, how a component of it is set up, the colours of its marks, sizes beyond its
     * list, and whether it is a row of digits (a segment display, a thumbwheel): those are measured against their
     * height and every size compared with the design size, since a row of digits keeps its digits' shape at any length.
     */
    record Probe(String name, ComponentType type, Consumer<PanelComponent> setup, MarkColours marks, List<Footprint> extraSizes,
                 boolean digitRow) {
        Probe(String name, ComponentType type, Consumer<PanelComponent> setup, MarkColours marks, List<Footprint> extraSizes) {
            this(name, type, setup, marks, extraSizes, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The colours of a probe's tick-like marks for a set-up component. */
    interface MarkColours {
        Set<Integer> of(PanelComponent component);
    }

    private static Consumer<PanelComponent> gauge(String style) {
        return c -> {
            GaugeData data = (GaugeData) c.data();
            if (style != null) {
                data.style = style;
                data.variant = PanelStyles.defaultVariant(data.type, style);
            }
            if (!data.isBarGauge()) {
                data.units = "MPa";
            }
        };
    }

    private static MarkColours colours(int... rgb) {
        Set<Integer> set = new TreeSet<>();
        for (int c : rgb) {
            set.add(c);
        }
        return c -> set;
    }

    /** Every gauge-like look. A new gauge type goes here (or, if it prints nothing, in {@link #NOT_GAUGES}). */
    static final List<Probe> PROBES = List.of(
            // The real size bar gauge is not on the type's list yet (CONTROL-PANELS.md): checked here all the same.
            new Probe("bar gauge", ComponentTypes.BAR_GAUGE, gauge(null), colours(BarGaugeFace.INK), List.of(Footprint.of(1, 3))),
            new Probe("dial gauge GE", ComponentTypes.DIAL_GAUGE, gauge(PanelStyles.GE), colours(GE_INK), List.of()),
            new Probe("dial gauge classic", ComponentTypes.DIAL_GAUGE, gauge(PanelStyles.CLASSIC), colours(CLASSIC_INK), List.of()),
            new Probe("edgewise vertical GE", ComponentTypes.EDGEWISE_GAUGE_VERTICAL, gauge(PanelStyles.GE), colours(GE_INK), List.of()),
            new Probe("edgewise vertical classic", ComponentTypes.EDGEWISE_GAUGE_VERTICAL, gauge(PanelStyles.CLASSIC),
                    colours(CLASSIC_INK), List.of()),
            new Probe("edgewise horizontal GE", ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, gauge(PanelStyles.GE), colours(GE_INK),
                    List.of()),
            new Probe("edgewise horizontal classic", ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, gauge(PanelStyles.CLASSIC),
                    colours(CLASSIC_INK), List.of()),
            new Probe("bar graph", ComponentTypes.BAR_GRAPH, c -> {
            }, c -> {
                BarGraphData d = (BarGraphData) c.data();
                return Set.of(QuadSink.shade(d.color, 0.22f), QuadSink.shade(0xFFB000, 0.22f), QuadSink.shade(0xE02020, 0.22f));
            }, List.of()),
            new Probe("seven segment display", ComponentTypes.SEVEN_SEGMENT_DISPLAY, c -> {
            }, c -> Set.of(QuadSink.shade(((SegmentDisplayData) c.data()).color, 0.13f)), List.of(), true),
            new Probe("sixteen segment display", ComponentTypes.SIXTEEN_SEGMENT_DISPLAY, c -> {
            }, c -> Set.of(QuadSink.shade(((SegmentDisplayData) c.data()).color, 0.13f)), List.of(), true),
            new Probe("thumbwheel", ComponentTypes.THUMBWHEEL, c -> {
            }, colours(ENGRAVED), List.of(), true),
            new Probe("annunciator", ComponentTypes.ANNUNCIATOR, c -> {
                AnnunciatorData d = (AnnunciatorData) c.data();
                d.line1 = "RPV LEVEL";
                d.line2 = "LOW L3";
            }, colours(), List.of()));

    /** Every registered type that is not checked here, and why. */
    static final Map<String, String> NOT_GAUGES = Map.ofEntries(
            Map.entry("nameplate", "a label plate: its lines are fitted to the plate at every size"),
            Map.entry("lamp", "a lens and its legend plate, a baked model in proportion"),
            Map.entry("pushbutton", "a control: a baked model in proportion"),
            Map.entry("toggle_switch", "a control without a printed scale"),
            Map.entry("rotary_selector", "a control: its position names are baked legends"),
            Map.entry("control_switch", "a control: its captions are baked legends"),
            Map.entry("key_switch", "a control: OFF and ON are baked legends"),
            Map.entry("setpoint_knob", "a control: its 0-50-100 are baked legends"),
            Map.entry("strip_chart", "a chart: no text, and its sizes (16 x 16 and up) are all at or above its design size"),
            Map.entry("character_lcd", "a display: pixels on a texture fitted to the window"),
            Map.entry("graphic_display", "a display: pixels on a texture fitted to the window"),
            Map.entry("mimic_line", "mimic: a line of the board's own width"),
            Map.entry("mimic_symbol", "mimic: a symbol drawn to its footprint"),
            Map.entry("full_core_display", "a core map with its own fitted lettering (FcdModelTests)"),
            Map.entry("rod_select_matrix", "a core map with its own fitted lettering (FcdModelTests)"),
            Map.entry("paperless_recorder", "a recorder: its screen is a pixel canvas texture and its case a placed model; "
                    + "no size below 12 x 12"),
            Map.entry("rps_trip", "a control: its two legends are baked"),
            Map.entry("push_turn_switch", "a control: its legends are baked"),
            Map.entry("controller_station", "a control station: every printed length is a share of its height and the scaling is checked by ControllerStationTests.printedTextScalesWithTheStationAndStaysOnIt"),
            Map.entry("synchroscope", "a round meter whose three legends are shares of its face radius; scaling checked by SynchroscopeTests"),
            Map.entry("two_pen_recorder", "a recorder: no scale text, only a header with two numbers and a mark, all shares of its case; sizes from 2 x 2"),
            Map.entry("joystick_switch", "a control: its four legends are shares of the plate and fitted to it (JoystickTests)"),
            Map.entry("led_rod_display", "a core map: its LEDs are drawn in tiles of the bound core and its only text is a caption fitted to the margin (LedRodDisplayTests)"));

    // ---- recording

    /** Records the static quads of a component model. */
    private static final class Recorder implements QuadSink {
        final List<float[]> quads = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();

        @Override
        public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
            quads.add(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3});
            colours.add(rgb);
        }
    }

    /**
     * What a component draws at one size, measured: the box round everything drawn ({x0, y0, x1, y1}), its smaller side
     * (what shares are of), each mark's thickness and length, and the text.
     */
    record Shot(Footprint size, float[] bounds, float ref, List<float[]> marks, List<Legends.Item> text) {
        float textShare(int i) {
            return text.get(i).capCells() / ref;
        }

        /** {max thickness, min thickness, max length, min length} of the marks, shares of {@link #ref}; null without marks. */
        float[] markShares() {
            if (marks.isEmpty()) {
                return null;
            }
            float[] out = {0.0f, Float.MAX_VALUE, 0.0f, Float.MAX_VALUE};
            for (float[] m : marks) {
                out[0] = Math.max(out[0], m[0] / ref);
                out[1] = Math.min(out[1], m[0] / ref);
                out[2] = Math.max(out[2], m[1] / ref);
                out[3] = Math.min(out[3], m[1] / ref);
            }
            return out;
        }
    }

    /** The shipped baked models the probes need, loaded as development fixtures for the length of {@code body}. */
    static void withModels(Runnable body) {
        Map<String, BakedPanelModel> models = new HashMap<>();
        for (String id : new String[] {"bar_gauge", BarGaugeFace.CASE_MODEL, "ge_ab40", "ge_ab40_red", "ge_180_vertical",
                "ge_180_vertical_red", "ge_180_horizontal", "ge_180_horizontal_red"}) {
            models.put(id, HardwareStage.shipped(id));
        }
        PanelModels.setFixtures(models);
        try {
            body.run();
        } finally {
            PanelModels.setFixtures(Map.of());
        }
    }

    static PanelComponent component(Probe probe, Footprint size, int textSize, int tickSize) {
        PanelComponent component = new PanelComponent(1, probe.type(), 0, 0, size, probe.type().labelPrefix() + "-1");
        probe.setup().accept(component);
        if (component.data() instanceof GaugeData data) {
            data.textSize = textSize;
            data.tickSize = tickSize;
        }
        return component;
    }

    /** Draws {@code probe} at {@code size} through its registered model and measures it. */
    static Shot shot(Probe probe, Footprint size, int textSize, int tickSize) {
        PanelComponent component = component(probe, size, textSize, tickSize);
        ComponentModel model = ComponentModels.get(probe.type().id());
        Recorder recorder = new Recorder();
        model.buildStatic(recorder, component, 1.0f);
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] q : recorder.quads) {
            for (int c = 0; c < 4; c++) {
                bounds[0] = Math.min(bounds[0], q[c * 3]);
                bounds[1] = Math.min(bounds[1], q[c * 3 + 1]);
                bounds[2] = Math.max(bounds[2], q[c * 3]);
                bounds[3] = Math.max(bounds[3], q[c * 3 + 1]);
            }
        }
        float ref = probe.digitRow() ? bounds[3] - bounds[1] : Math.min(bounds[2] - bounds[0], bounds[3] - bounds[1]);
        Set<Integer> markColours = probe.marks().of(component);
        List<float[]> marks = new ArrayList<>();
        for (int i = 0; i < recorder.quads.size(); i++) {
            if (markColours.contains(recorder.colours.get(i))) {
                float[] m = mark(recorder.quads.get(i));
                if (m != null) {
                    marks.add(m);
                }
            }
        }
        return new Shot(size, bounds, ref, marks, model.textLayout(component, 1.0f));
    }

    /** {thickness, length} of a flat, non-degenerate quad (its shorter and longer side); null for any other. */
    static float[] mark(float[] q) {
        float[] edges = new float[4];
        for (int c = 0; c < 4; c++) {
            int n = (c + 1) % 4;
            edges[c] = (float) Math.sqrt(sq(q[n * 3] - q[c * 3]) + sq(q[n * 3 + 1] - q[c * 3 + 1]) + sq(q[n * 3 + 2] - q[c * 3 + 2]));
            if (edges[c] < 1.0e-5f) {
                return null;
            }
        }
        // Facing out of the panel (the bar gauge's ticks lean back with its curved face, never more than 40 degrees).
        float ax = q[6] - q[0];
        float ay = q[7] - q[1];
        float az = q[8] - q[2];
        float bx = q[9] - q[3];
        float by = q[10] - q[4];
        float bz = q[11] - q[5];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        if (Math.abs(nz) < 0.7f * (float) Math.sqrt(nx * nx + ny * ny + nz * nz)) {
            return null;
        }
        return new float[] {Math.min(edges[0], edges[1]), Math.max(edges[0], edges[1])};
    }

    private static float sq(float v) {
        return v * v;
    }

    /** All the sizes of a probe: the type's list and the extra ones. */
    static List<Footprint> sizes(Probe probe) {
        List<Footprint> sizes = new ArrayList<>(probe.type().footprints());
        sizes.addAll(probe.extraSizes());
        return sizes;
    }

    /** {@code size} times the largest whole number that keeps it within {@code design} both ways (0 when it is not smaller). */
    static int multiple(Footprint size, Footprint design) {
        return Math.min(design.w() / size.w(), design.h() / size.h());
    }

    /** The failures (empty when it scales as GaugeScale says) of {@code probe} with the sliders at {@code text} and {@code tick}. */
    static List<String> scaleInvarianceFailures(Probe probe, int text, int tick) {
        List<String> failures = new ArrayList<>();
        Footprint design = probe.type().footprints().get(0);
        Shot designShot = shot(probe, design, text, tick);
        for (Footprint size : sizes(probe)) {
            if (size.equals(design)) {
                continue;
            }
            int m = multiple(size, design);
            Shot small = shot(probe, size, text, tick);
            if (probe.digitRow()) {
                compare(probe, small, designShot, LOW, HIGH, failures);
            } else if (m >= 2) {
                Shot big = shot(probe, Footprint.of(size.w() * m, size.h() * m), text, tick);
                compare(probe, small, big, LOW, HIGH, failures);
            } else {
                compare(probe, small, designShot, 0.0f, BIG_HIGH, failures);
            }
        }
        return failures;
    }

    /** Every share of {@code shot} against {@code against}'s: within {@code low} .. {@code high} times it. */
    private static void compare(Probe probe, Shot shot, Shot against, float low, float high, List<String> failures) {
        String where = probe + " " + shot.size() + " against " + against.size() + ": ";
        if (shot.text().size() != against.text().size()) {
            // A row of digits prints as many as it has: compare the largest and the smallest.
            if (shot.text().isEmpty() || against.text().isEmpty()) {
                failures.add(where + "prints " + shot.text().size() + " pieces of text, not " + against.text().size());
            } else {
                ratio(where + "largest text", maxText(shot), maxText(against), low, high, failures);
                ratio(where + "smallest text", minText(shot), minText(against), low, high, failures);
            }
        } else {
            for (int i = 0; i < shot.text().size(); i++) {
                ratio(where + "text '" + shot.text().get(i).text() + "'", shot.textShare(i), against.textShare(i), low, high, failures);
            }
        }
        float[] a = shot.markShares();
        float[] b = against.markShares();
        if ((a == null) != (b == null)) {
            failures.add(where + "has marks at one size and none at the other");
        } else if (a != null) {
            String[] names = {"thickest mark", "thinnest mark", "longest mark", "shortest mark"};
            for (int k = 0; k < 4; k++) {
                ratio(where + names[k], a[k], b[k], low, high, failures);
            }
        }
    }

    private static float maxText(Shot shot) {
        float out = 0.0f;
        for (int i = 0; i < shot.text().size(); i++) {
            out = Math.max(out, shot.textShare(i));
        }
        return out;
    }

    private static float minText(Shot shot) {
        float out = Float.MAX_VALUE;
        for (int i = 0; i < shot.text().size(); i++) {
            out = Math.min(out, shot.textShare(i));
        }
        return out;
    }

    private static void ratio(String what, float share, float against, float low, float high, List<String> failures) {
        float r = share / against;
        if (!(r >= low && r <= high)) {
            failures.add(String.format(java.util.Locale.ROOT, "%s is %.4f of the size, %.2f times the %.4f there (allowed %.2f..%.2f)",
                    what, share, r, against, low, high));
        }
    }

    /** The failures of the fit check: every piece of text inside what is drawn and clear of every other. */
    static List<String> fitFailures(Probe probe, int text) {
        List<String> failures = new ArrayList<>();
        for (Footprint size : sizes(probe)) {
            Shot shot = shot(probe, size, text, GaugeScale.DEFAULT_PERCENT);
            String where = probe + " " + size + " text " + text + " %: ";
            float[] bounds = shot.bounds();
            List<float[]> boxes = new ArrayList<>();
            for (Legends.Item item : shot.text()) {
                float[] box = Legends.box(item, 1.0, 1.0f);
                boxes.add(box);
                if (!Legends.inside(box, bounds)) {
                    failures.add(where + "'" + item.text() + "' leaves the component: " + java.util.Arrays.toString(box)
                            + " outside " + java.util.Arrays.toString(bounds));
                }
            }
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (Legends.overlap(boxes.get(i), boxes.get(j))) {
                        failures.add(where + "'" + shot.text().get(i).text() + "' runs into '" + shot.text().get(j).text() + "'");
                    }
                }
            }
        }
        return failures;
    }

    /** Fails with the first few failures (a game test's message goes into a book, which has a length limit); all go to the log. */
    static void assertNone(GameTestHelper helper, List<String> failures, String what) {
        if (!failures.isEmpty()) {
            com.mojang.logging.LogUtils.getLogger().error("GAUGE SCALE GUARD {}: {} failures\n  {}", what, failures.size(),
                    String.join("\n  ", failures));
        }
        helper.assertTrue(failures.isEmpty(), what + ": " + failures.size() + " failures (all in the log), first:\n  "
                + String.join("\n  ", failures.subList(0, Math.min(6, failures.size()))));
    }

    // ---- the tests

    @GameTest(template = "empty")
    public static void everyComponentTypeIsCheckedOrExemptWithAReason(GameTestHelper helper) {
        Set<String> probed = new TreeSet<>();
        for (Probe probe : PROBES) {
            probed.add(probe.type().id().getPath());
        }
        List<String> failures = new ArrayList<>();
        for (ComponentType type : ComponentTypes.all()) {
            String id = type.id().getPath();
            boolean checked = probed.contains(id);
            boolean exempt = NOT_GAUGES.containsKey(id);
            if (checked == exempt) {
                failures.add(id + (checked ? " is both probed and exempt" : " is neither probed by GaugeScaleGuardTests.PROBES nor "
                        + "listed in NOT_GAUGES: decide whether it prints a scale, and add it to one of them"));
            }
        }
        for (String id : NOT_GAUGES.keySet()) {
            if (ComponentTypes.all().stream().noneMatch(t -> t.id().getPath().equals(id))) {
                failures.add(id + " is exempt but no longer registered");
            }
        }
        assertNone(helper, failures, "coverage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gaugeTextAndTicksAreTheSameShareOfTheGaugeAtEverySize(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        withModels(() -> {
            for (Probe probe : PROBES) {
                failures.addAll(scaleInvarianceFailures(probe, GaugeScale.DEFAULT_PERCENT, GaugeScale.DEFAULT_PERCENT));
                if (probe.type().createData() instanceof GaugeData) {
                    failures.addAll(scaleInvarianceFailures(probe, 160, 60));
                }
            }
        });
        assertNone(helper, failures, "scale invariance");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gaugeTextFitsItsComponentAndNeverRunsIntoItsNeighbours(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        withModels(() -> {
            for (Probe probe : PROBES) {
                if (probe.type().createData() instanceof GaugeData) {
                    for (int text : new int[] {GaugeScale.MIN_PERCENT, GaugeScale.DEFAULT_PERCENT, GaugeScale.MAX_PERCENT}) {
                        failures.addAll(fitFailures(probe, text));
                    }
                } else {
                    failures.addAll(fitFailures(probe, GaugeScale.DEFAULT_PERCENT));
                }
            }
        });
        assertNone(helper, failures, "fit");
        helper.succeed();
    }

    /** Every probe draws something measurable, so a probe that silently records nothing cannot pass vacuously. */
    @GameTest(template = "empty")
    public static void everyProbeDrawsTextOrMarks(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        withModels(() -> {
            for (Probe probe : PROBES) {
                for (Footprint size : sizes(probe)) {
                    Shot shot = shot(probe, size, GaugeScale.DEFAULT_PERCENT, GaugeScale.DEFAULT_PERCENT);
                    if (shot.text().isEmpty() && shot.marks().isEmpty()) {
                        failures.add(probe + " " + size + " draws no text and no marks");
                    }
                    if (probe.type().createData() instanceof GaugeData && shot.marks().size() < 10) {
                        failures.add(probe + " " + size + " has only " + shot.marks().size() + " tick marks");
                    }
                }
            }
        });
        assertNone(helper, failures, "probes");
        helper.succeed();
    }

    /**
     * The owner's picture (references/gauges/owner-bar-gauge-text-proportions.png): the numbers are a fixed share of the
     * face, about a quarter to a third of its width for the widest, and well under the spacing of the numbered ticks;
     * the real size 1 x 3 gauge is the 10 x 16 one smaller.
     */
    @GameTest(template = "empty")
    public static void barGaugeNumbersSitInProportionLikeTheReference(GameTestHelper helper) {
        Probe probe = PROBES.get(0);
        withModels(() -> {
            float share = -1.0f;
            for (Footprint size : List.of(Footprint.of(1, 3), Footprint.of(10, 16), Footprint.of(16, 32))) {
                Shot shot = shot(probe, size, GaugeScale.DEFAULT_PERCENT, GaugeScale.DEFAULT_PERCENT);
                float faceWidth = shot.ref() * 4.2f / 5.0f; // the face between the rails is 4.2 of the case's 5 cm
                float widest = 0.0f;
                float cap = 0.0f;
                List<Float> rows = new ArrayList<>();
                for (Legends.Item item : shot.text()) {
                    if (item.align() == BakedPanelModel.Align.RIGHT) {
                        float[] box = Legends.box(item, 1.0, 1.0f);
                        widest = Math.max(widest, box[2] - box[0]);
                        cap = item.capCells();
                        rows.add(item.y());
                    }
                }
                float spacing = Math.abs(rows.get(1) - rows.get(0));
                helper.assertTrue(widest / faceWidth > 0.15f && widest / faceWidth < 0.4f,
                        size + ": the widest number is " + widest / faceWidth + " of the face's width");
                helper.assertTrue(cap < 0.4f * spacing, size + ": a number is " + cap / spacing + " of the spacing of the numbers");
                if (share < 0.0f) {
                    share = cap / shot.ref();
                } else if (size.equals(Footprint.of(10, 16))) {
                    helper.assertTrue(Math.abs(cap / shot.ref() / share - 1.0f) < 0.02f,
                            "the 1 x 3 gauge's numbers are the same share of it as the 10 x 16 gauge's: " + share + " vs " + cap / shot.ref());
                }
            }
        });
        helper.succeed();
    }
}
