package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.model.PanelModelSet;
import dev.bwr.mod.panel.model.PanelModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small stand-in panel models written in exactly the JSON format of {@code art/models/panel/panel_export.py}
 * (format 1, inches, y down, label anchors), so the loader, the transforms and the renderer can be exercised
 * before the real models exist. They are crude on purpose: a plate, a handle, a flag, a needle. Devtest only.
 */
public final class PanelModelFixtures {
    private PanelModelFixtures() {
    }

    // ---- a tiny JSON model builder

    static final class Model {
        final String name;
        final double w;
        final double h;
        private final List<String> parts = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();

        Model(String name, double w, double h) {
            this.name = name;
            this.w = w;
            this.h = h;
        }

        Part part(String partName, String role) {
            return new Part(this, partName, role);
        }

        Model label(String id, double x, double y, double z, double height, String align, double rot) {
            labels.add(String.format(Locale.ROOT,
                    "{\"id\":\"%s\",\"pos\":[%.4f,%.4f,%.4f],\"h\":%.4f,\"align\":\"%s\",\"rot\":%.3f}",
                    id, x, y, z, height, align, rot));
            return this;
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"format\":1,\"name\":\"%s\",\"units\":\"inch\",\"footprint\":{\"w\":%.4f,\"h\":%.4f},"
                            + "\"source\":\"test fixture\",\"parts\":[%s],\"labels\":[%s]}",
                    name, w, h, String.join(",", parts), String.join(",", labels));
        }
    }

    static final class Part {
        private final Model model;
        private final String name;
        private final String role;
        private String tint;
        private double[] pivot;
        private double[] axis;
        private Double travel;
        private final List<String> faces = new ArrayList<>();

        Part(Model model, String name, String role) {
            this.model = model;
            this.name = name;
            this.role = role;
        }

        Part tint(String value) {
            this.tint = value;
            return this;
        }

        Part pivot(double x, double y, double z) {
            this.pivot = new double[] {x, y, z};
            this.axis = new double[] {0, 0, 1};
            return this;
        }

        Part travel(double amount, double ax, double ay, double az) {
            this.travel = amount;
            this.axis = new double[] {ax, ay, az};
            return this;
        }

        private static String vec(double x, double y, double z) {
            return String.format(Locale.ROOT, "[%.4f,%.4f,%.4f]", x, y, z);
        }

        /** A face from 3 or 4 corners {x, y, z} with an explicit unit normal. */
        Part face(String hex, double nx, double ny, double nz, double[]... corners) {
            StringBuilder v = new StringBuilder();
            for (double[] c : corners) {
                if (v.length() > 0) {
                    v.append(',');
                }
                v.append(vec(c[0], c[1], c[2]));
            }
            faces.add(String.format(Locale.ROOT, "{\"v\":[%s],\"n\":%s,\"c\":\"%s\"}", v, vec(nx, ny, nz), hex));
            return this;
        }

        /** A flat rectangle facing the operator at elevation z. */
        Part rect(String hex, double x0, double y0, double x1, double y1, double z) {
            return face(hex, 0, 0, 1, new double[] {x0, y0, z}, new double[] {x1, y0, z}, new double[] {x1, y1, z},
                    new double[] {x0, y1, z});
        }

        /** A solid box: a top and four sides, each with its outward normal (y is down the face). */
        Part box(String hex, double x0, double y0, double z0, double x1, double y1, double z1) {
            rect(hex, x0, y0, x1, y1, z1);
            face(hex, -1, 0, 0, new double[] {x0, y0, z0}, new double[] {x0, y0, z1}, new double[] {x0, y1, z1},
                    new double[] {x0, y1, z0});
            face(hex, 1, 0, 0, new double[] {x1, y0, z0}, new double[] {x1, y1, z0}, new double[] {x1, y1, z1},
                    new double[] {x1, y0, z1});
            face(hex, 0, -1, 0, new double[] {x0, y0, z0}, new double[] {x1, y0, z0}, new double[] {x1, y0, z1},
                    new double[] {x0, y0, z1});
            face(hex, 0, 1, 0, new double[] {x0, y1, z0}, new double[] {x0, y1, z1}, new double[] {x1, y1, z1},
                    new double[] {x1, y1, z0});
            return this;
        }

        /**
         * A convex outline extruded from z0 to z1: a fan top and one side per edge, each side with its own
         * outward normal (so a round shape has a different normal on every facet).
         */
        Part prism(String sideHex, String topHex, double z0, double z1, double[][] outline) {
            double cx = 0;
            double cy = 0;
            for (double[] p : outline) {
                cx += p[0];
                cy += p[1];
            }
            cx /= outline.length;
            cy /= outline.length;
            for (int i = 0; i < outline.length; i++) {
                double[] a = outline[i];
                double[] b = outline[(i + 1) % outline.length];
                face(topHex, 0, 0, 1, new double[] {cx, cy, z1}, new double[] {a[0], a[1], z1}, new double[] {b[0], b[1], z1});
                double nx = b[1] - a[1];
                double ny = -(b[0] - a[0]);
                double length = Math.hypot(nx, ny);
                nx /= length;
                ny /= length;
                if (nx * ((a[0] + b[0]) / 2 - cx) + ny * ((a[1] + b[1]) / 2 - cy) < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                face(sideHex, nx, ny, 0, new double[] {a[0], a[1], z0}, new double[] {b[0], b[1], z0},
                        new double[] {b[0], b[1], z1}, new double[] {a[0], a[1], z1});
            }
            return this;
        }

        /** A regular polygon (a disc when sides is large). */
        Part disc(String sideHex, String topHex, double cx, double cy, double radius, double z0, double z1, int sides) {
            double[][] outline = new double[sides][];
            for (int i = 0; i < sides; i++) {
                double a = 2 * Math.PI * i / sides;
                outline[i] = new double[] {cx + radius * Math.sin(a), cy - radius * Math.cos(a)};
            }
            return prism(sideHex, topHex, z0, z1, outline);
        }

        Model done() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"role\":\"%s\",\"tint\":%s,\"pivot\":%s,\"axis\":%s,\"travel\":%s,\"faces\":[",
                    name, role, tint == null ? "null" : "\"" + tint + "\"",
                    pivot == null ? "null" : vec(pivot[0], pivot[1], pivot[2]),
                    axis == null ? "null" : vec(axis[0], axis[1], axis[2]),
                    travel == null ? "null" : String.format(Locale.ROOT, "%.4f", travel)));
            sb.append(String.join(",", faces)).append("]}");
            model.parts.add(sb.toString());
            return model;
        }
    }

    // ---- the fixtures

    /** A plate with a bright border, in the control-room black. */
    private static Part plate(Model m, String hex, double z) {
        Part body = m.part("body", "paint");
        body.box(hex, 0, 0, -0.02, m.w, m.h, z);
        double t = 0.07;
        String trim = "B8BCC0";
        body.box(trim, 0, 0, z, m.w, t, z + 0.03).box(trim, 0, m.h - t, z, m.w, m.h, z + 0.03)
                .box(trim, 0, t, z, t, m.h - t, z + 0.03).box(trim, m.w - t, t, z, m.w, m.h - t, z + 0.03);
        return body;
    }

    /**
     * A handle: a tapered arm from the pivot with a hub, pointing up (direction -1) or down (+1) as modelled.
     */
    private static Model handle(Model m, double px, double py, double length, double width, String hex, double z0,
                                double z1, int direction) {
        Part handle = m.part("handle", "paint").pivot(px, py, z0);
        double[][] arm = {{px - width * 0.7, py}, {px - width * 0.35, py + direction * length},
                {px + width * 0.35, py + direction * length}, {px + width * 0.7, py}, {px, py - direction * width * 0.7}};
        handle.prism(hex, hex, z0, z1, arm);
        handle.disc(hex, "303030", px, py, width * 0.8, z0, z1 + 0.05, 14);
        return handle.done();
    }

    /**
     * Breaker control switch in the real models' convention: a plate, a target window, a pistol-ish grip that
     * hangs straight down at NORMAL, and legends left (TRIP) and right (CLOSE), tilted away from the vertical.
     * Footprint 2:3.
     */
    public static String controlSwitch(String name, double handleLength, String handleHex) {
        Model m = new Model(name, 2.4, 3.6);
        plate(m, "141414", 0.12).done();
        m.part("target", "flag").rect("C03030", 0.7, 0.45, 1.7, 1.0, 0.15).done();
        handle(m, 1.2, 1.5, handleLength, 0.22, handleHex, 0.15, 0.55, 1);
        m.label("caption", 1.2, 0.25, 0.15, 0.2, "center", 0);
        m.label("left", 0.5, 1.25, 0.15, 0.17, "center", -55);
        m.label("right", 1.9, 1.25, 0.15, 0.17, "center", 55);
        m.label("maker", 1.2, 3.3, 0.15, 0.1, "center", 0);
        return m.json();
    }

    /**
     * Rotary selector: plate, handle modelled pointing at 12 o'clock, and pos1..pos6 anchors on a circle of
     * radius 1.05 around the shaft at bearings -112.5, -67.5, ... +112.5 (clockwise from up).
     */
    public static String selector(String name, double handleLength, String handleHex) {
        Model m = new Model(name, 2.4, 3.0);
        plate(m, "141414", 0.12).done();
        handle(m, 1.2, 1.6, handleLength, 0.2, handleHex, 0.15, 0.5, -1);
        for (int i = 0; i < 6; i++) {
            double bearing = Math.toRadians(-112.5 + 45.0 * i);
            m.label("pos" + (i + 1), 1.2 + 1.05 * Math.sin(bearing), 1.6 - 1.05 * Math.cos(bearing), 0.15, 0.15, "center", 0);
        }
        m.label("caption", 1.2, 0.3, 0.15, 0.18, "center", 0);
        return m.json();
    }

    /**
     * Key switch. GE style: the key handle modelled at OFF pointing at 12 o'clock, OFF legend at pos3 (0 degrees)
     * and ON legend at pos4 (45 degrees). Westinghouse style: the key modelled hanging down midway between its
     * positions, OFF legend "left" and ON legend "right" (49 degrees either side of up); a tumbler lock also has a
     * plug that turns 0 locked, 90 degrees unlocked.
     */
    public static String keySwitch(String name, boolean westinghouse, boolean tumbler) {
        Model m = new Model(name, 1.8, 2.4);
        plate(m, "141414", 0.1).done();
        m.part("lock", "paint").disc("C8CCD0", "C8CCD0", 0.9, 1.3, 0.55, 0.1, 0.2, 16).done();
        if (tumbler) {
            Part plug = m.part("plug", "paint").pivot(0.9, 0.55, 0.2);
            plug.disc("808890", "A0A8B0", 0.9, 0.55, 0.25, 0.2, 0.3, 10);
            plug.box("202020", 0.86, 0.35, 0.3, 0.94, 0.75, 0.34);
            plug.done();
        }
        if (westinghouse) {
            handle(m, 0.9, 1.3, 0.5, 0.15, "1A1A1A", 0.2, 0.4, 1);
            double dx = 0.8 * Math.sin(Math.toRadians(49));
            double dy = 0.8 * Math.cos(Math.toRadians(49));
            m.label("left", 0.9 - dx, 1.3 - dy, 0.12, 0.12, "right", 0);
            m.label("right", 0.9 + dx, 1.3 - dy, 0.12, 0.12, "left", 0);
        } else {
            handle(m, 0.9, 1.3, 0.5, 0.15, "1A1A1A", 0.2, 0.4, -1);
            m.label("pos3", 0.9, 1.3 - 0.85, 0.12, 0.12, "center", 0);
            m.label("pos4", 0.9 + 0.85 * Math.sin(Math.toRadians(45)), 1.3 - 0.85 * Math.cos(Math.toRadians(45)), 0.12, 0.12, "center", 0);
        }
        m.label("caption", 0.9, 0.2, 0.12, 0.16, "center", 0);
        return m.json();
    }

    /** Pushbutton: chrome bezel, a recolourable cap that slides 0.15 in, an optional lamp lens, a legend anchor. */
    public static String pushbutton(String name, boolean illuminated) {
        Model m = new Model(name, 2.0, 2.0);
        m.part("bezel", "paint").disc("C8CCD0", "C8CCD0", 1.0, 1.0, 0.95, 0.0, 0.2, 20).done();
        Part cap = m.part("cap", "paint").tint("red").travel(0.15, 0, 0, -1);
        cap.disc("A02020", "C03030", 1.0, 1.0, 0.6, 0.2, 0.5, 20);
        cap.done();
        if (illuminated) {
            m.part("lens", "lamp").tint("amber").disc("D0A000", "FFB000", 1.0, 1.0, 0.25, 0.5, 0.56, 12).done();
        }
        m.label("legend", 1.0, 1.0, 0.57, 0.18, "center", 0);
        return m.json();
    }

    /** Indicating light: bezel and a lens (role lamp) in the lamp's colour. */
    public static String light(String name, boolean square) {
        Model m = new Model(name, 1.6, 1.6);
        Part bezel = m.part("bezel", "paint");
        if (square) {
            bezel.box("C8CCD0", 0.05, 0.05, 0.0, 1.55, 1.55, 0.2);
        } else {
            bezel.disc("C8CCD0", "C8CCD0", 0.8, 0.8, 0.75, 0.0, 0.2, 20);
        }
        bezel.done();
        Part lens = m.part("lens", "lamp").tint("red");
        if (square) {
            lens.box("C03030", 0.25, 0.25, 0.2, 1.35, 1.35, 0.45);
        } else {
            lens.disc("B02828", "E04040", 0.8, 0.8, 0.5, 0.2, 0.45, 18);
        }
        lens.done();
        m.label("legend", 0.8, 0.8, 0.46, 0.2, "center", 0);
        return m.json();
    }

    /** Fluted setpoint knob: dial plate, a knob with a pointer rib that turns +-135 degrees. */
    public static String knob(String name) {
        Model m = new Model(name, 2.4, 2.4);
        Part plate = m.part("plate", "paint");
        plate.box("141414", 0, 0, -0.02, 2.4, 2.4, 0.1).done();
        Part knob = m.part("knob", "paint").pivot(1.2, 1.2, 0.1);
        knob.disc("2A2A2A", "3A3A3A", 1.2, 1.2, 0.8, 0.1, 0.6, 12);
        knob.box("F2F0E8", 1.15, 0.3, 0.6, 1.25, 1.0, 0.64);
        knob.done();
        m.label("caption", 1.2, 0.2, 0.1, 0.16, "center", 0);
        m.label("min", 0.53, 1.87, 0.1, 0.12, "center", 0);
        m.label("mid", 1.2, 0.5, 0.1, 0.12, "center", 0);
        m.label("max", 1.87, 1.87, 0.1, 0.12, "center", 0);
        return m.json();
    }

    /** Round meter with six numbered scale anchors, like the real AB-40 model. */
    public static String dial(String name) {
        return dial(name, false, true);
    }

    /**
     * Round meter: white face, pointer pivoting at the centre, a glass window, units and caption anchors, and
     * either six numbered scale anchors (scale0..scale5 on a circle of radius 1.45 at -125 + 50 i degrees, as the
     * real model does) or a single "scale" anchor at the top.
     */
    public static String dial(String name, boolean red, boolean numbered) {
        double s = 4.33;
        Model m = new Model(name, s, s);
        m.part("case", "paint").box("1C1C1C", 0, 0, -0.02, s, s, 0.2).done();
        m.part("face", "paint").disc("F2F0E8", "F2F0E8", s / 2, s / 2, 1.9, 0.2, 0.24, 28).done();
        String ink = red ? "E8402A" : "101010";
        Part needle = m.part("needle", "needle").pivot(s / 2, s / 2, 0.3);
        needle.prism(ink, ink, 0.26, 0.3, new double[][] {{s / 2 - 0.04, s / 2 + 0.3}, {s / 2 - 0.02, s / 2 - 1.6},
                {s / 2 + 0.02, s / 2 - 1.6}, {s / 2 + 0.04, s / 2 + 0.3}});
        needle.done();
        m.part("window", "glass").rect("E0F0FF", 0.05, 0.05, s - 0.05, s - 0.05, 0.45).done();
        if (numbered) {
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(-125 + 50 * i);
                m.label("scale" + i, s / 2 + 1.45 * Math.sin(a), s / 2 - 1.45 * Math.cos(a), 0.25, 0.2, "center", 0);
            }
        } else {
            m.label("scale", s / 2, s / 2 - 1.45, 0.25, 0.2, "center", 0);
        }
        m.label("units", s / 2, s / 2 + 0.9, 0.25, 0.2, "center", 0);
        m.label("caption", s / 2, s - 0.45, 0.25, 0.2, "center", 0);
        return m.json();
    }

    /** Edgewise meter with six numbered scale anchors, like the real Type 180 models. */
    public static String edgewise(String name, boolean vertical) {
        return edgewise(name, vertical, false, true);
    }

    /**
     * Edgewise meter: a case, a white scale strip, a pointer that slides 4.6 inches up (vertical) or right
     * (horizontal), a flat window, and units. Numbered anchors put scale0 at the low end of the travel and scale5
     * at the high end; otherwise a single "scale" anchor marks the low end.
     */
    public static String edgewise(String name, boolean vertical, boolean red, boolean numbered) {
        double w = vertical ? 2.294 : 6.04;
        double h = vertical ? 6.04 : 2.294;
        double travel = 4.6;
        String ink = red ? "E8402A" : "101010";
        Model m = new Model(name, w, h);
        m.part("case", "paint").box("1C1C1C", 0, 0, -0.02, w, h, 0.15).done();
        m.part("face", "paint").rect("F2F0E8", 0.35, 0.35, w - 0.35, h - 0.35, 0.18).done();
        if (vertical) {
            m.part("needle", "needle").travel(travel, 0, -1, 0).rect(ink, 0.45, h - 0.65, w - 0.45, h - 0.55, 0.22).done();
            if (numbered) {
                for (int i = 0; i < 6; i++) {
                    m.label("scale" + i, 0.9, h - 0.6 - travel * i / 5, 0.2, 0.18, "right", 0);
                }
            } else {
                m.label("scale", 0.75, h - 0.6, 0.2, 0.18, "center", 0);
            }
            m.label("units", w / 2 + 0.3, h / 2, 0.2, 0.16, "center", 90);
        } else {
            m.part("needle", "needle").travel(travel, 1, 0, 0).rect(ink, 0.65, 0.45, 0.75, h - 0.45, 0.22).done();
            if (numbered) {
                for (int i = 0; i < 6; i++) {
                    m.label("scale" + i, 0.7 + travel * i / 5, 0.75, 0.2, 0.18, "center", 0);
                }
            } else {
                m.label("scale", 0.7, 0.75, 0.2, 0.18, "center", 0);
            }
            m.label("units", w - 0.6, h - 0.5, 0.2, 0.16, "right", 0);
        }
        m.part("window", "glass").rect("E0F0FF", 0.05, 0.05, w - 0.05, h - 0.05, 0.3).done();
        return m.json();
    }

    // ---- sets

    /** One fixture text for every model id of the style table, so each (style, variant) can be shown. */
    public static Map<String, String> forTable() {
        Map<String, String> texts = new TreeMap<>();
        for (PanelStyles.Entry entry : PanelStyles.TABLE) {
            String id = entry.models().get(0);
            if (texts.containsKey(id)) {
                continue;
            }
            texts.put(id, forType(entry, id));
        }
        return texts;
    }

    private static String forType(PanelStyles.Entry entry, String id) {
        boolean westinghouse = PanelStyles.WESTINGHOUSE.equals(entry.style());
        double length = westinghouse ? 1.1 : 1.35;
        String handleHex = westinghouse ? "1A1A1A" : entry.variant().equals("lever") ? "C0261E" : "1A1A1A";
        return switch (entry.type()) {
            case "control_switch" -> controlSwitch(id, entry.variant().equals("pistol_hd") ? 1.55 : length, handleHex);
            case "rotary_selector" -> selector(id, entry.variant().equals("mini_slim") ? 0.7 : length - 0.2, handleHex);
            case "key_switch" -> keySwitch(id, westinghouse, entry.variant().equals("tumbler"));
            case "pushbutton" -> pushbutton(id, entry.variant().equals("illuminated"));
            case "lamp" -> light(id, entry.variant().equals("square"));
            case "setpoint_knob" -> knob(id);
            case "dial_gauge" -> dial(id, id.endsWith("_red"), true);
            case "edgewise_gauge_vertical" -> edgewise(id, true, id.endsWith("_red"), true);
            case "edgewise_gauge_horizontal" -> edgewise(id, false, id.endsWith("_red"), true);
            default -> throw new IllegalStateException("no fixture for " + entry.type());
        };
    }

    /** Parses {@link #forTable()} and registers the models as fixtures (real loaded models still win). */
    public static void install() {
        PanelModelSet.Result result = PanelModelSet.loadAll(forTable());
        if (!result.problems().isEmpty()) {
            throw new IllegalStateException("fixture models rejected: " + result.problems());
        }
        PanelModels.setFixtures(result.models());
    }
}
