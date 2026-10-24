package dev.bwr.mod.reactor.client;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselProfile;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The v2 pressure vessel's outside (RPV-REFERENCE.md): support skirt, bottom head, cylinder with Columbia's course seams,
 * closure flange and studs, the top head with its crown nozzles and lifting lugs, nozzle forgings and safe ends on the
 * player's ports, and the small instrument nozzles nothing connects to. Unit Blender parts ({@code build_parts.py}) placed by
 * {@link VesselProfile}; built once into the vessel's cached mesh.
 */
public final class VesselModel {
    /** The unit parts exported from art/models/reactor_vessel_v2/parts (tools-export-reactor-vessel.py). */
    public static final String[] PARTS = {"rpv_bottom_head", "rpv_barrel", "rpv_vessel_flange", "rpv_head_flange", "rpv_head_dome", "rpv_seam",
            "rpv_seam_v", "rpv_skirt", "rpv_skirt_base", "rpv_anchor_nut", "rpv_forging", "rpv_safe_end", "rpv_instrument", "rpv_stud", "rpv_nut",
            "rpv_crown_neck", "rpv_crown_flange", "rpv_lug", "rpv_core_shroud", "rpv_shroud_step", "rpv_upper_shroud", "rpv_shroud_flange",
            "rpv_support_plate", "rpv_top_guide_rim", "rpv_core_plate", "rpv_shroud_head", "rpv_standpipe", "rpv_separator", "rpv_eye", "rpv_rod",
            "rpv_pipe", "rpv_dryer_ring", "rpv_dryer_skirt", "rpv_dryer_skirt_ring", "rpv_dryer_band", "rpv_dryer_bank", "rpv_dryer_hood",
            "rpv_cube_dark", "rpv_cube_ss", "rpv_cube_bolt", "rpv_cube_skirt", "failed_head"};

    private VesselModel() {
    }

    static ModelResourceLocation m(String part) {
        return ReactorVesselRenderer.model(part);
    }

    /** A part at (x, y, z), turned {@code yaw} radians about Y (local +x goes to (cos yaw, sin yaw) in x, z), scaled. */
    static void put(MachineMeshCache.Builder b, String part, double x, double y, double z, double yaw, double sx, double sy, double sz) {
        b.pose.pushPose();
        b.pose.translate(x, y, z);
        if (yaw != 0) b.pose.mulPose(com.mojang.math.Axis.YP.rotation((float) -yaw));
        b.pose.scale((float) sx, (float) sy, (float) sz);
        b.model(m(part));
        b.pose.popPose();
    }

    /** A part whose local +z runs from a to b, with radii rx, ry across it and its length stretched to |b - a|. */
    static void along(MachineMeshCache.Builder b, String part, double ax, double ay, double az, double bx, double by, double bz, double r) {
        var v = new Vector3f((float) (bx - ax), (float) (by - ay), (float) (bz - az));
        float length = v.length();
        if (length < 1e-4) return;
        b.pose.pushPose();
        b.pose.translate(ax, ay, az);
        b.pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), v.normalize()));
        b.pose.scale((float) r, (float) r, length);
        b.model(m(part));
        b.pose.popPose();
    }

    // ---------------------------------------------------------------
    // The outside
    // ---------------------------------------------------------------

    static void buildShell(MachineMeshCache.Builder b, VesselAppearance.Envelope e, boolean closed, boolean failed) {
        buildShell(b, e, closed, failed, true);
    }

    /** {@code nuts}: whether the closed head carries its nuts and washers (off while the studs are detensioned; the renderer then draws any still on). */
    static void buildShell(MachineMeshCache.Builder b, VesselAppearance.Envelope e, boolean closed, boolean failed, boolean nuts) {
        var p = VesselProfile.of(e, false);
        float w = e.width(), d = e.depth();
        // bottom head, cylinder (a small overlap at both joints), vessel flange forging
        b.part(m("rpv_bottom_head"), 0, p.y0o, 0, w, (float) p.hbO, d);
        double taperBottom = p.flangeFace - p.flangeHeight * 1.45;
        b.part(m("rpv_barrel"), 0, p.knuckle - .03, 0, w, (float) (taperBottom - p.knuckle + .06), d);
        b.part(m("rpv_vessel_flange"), 0, p.flangeFace - p.flangeHeight, 0, w, (float) p.flangeHeight, d);
        seams(b, p, w, d, taperBottom);
        skirt(b, p, w, d);
        instrumentNozzles(b, e, p);
        int studs = p.studCount();
        double rs = p.studRadius();
        if (failed) {
            b.part(m("failed_head"), 0, p.flangeFace, 0, w, (float) p.headRise, d);
        } else {
            // Studs stay in the flange with the head off, as at a real plant; the nuts and washers go with the head.
            double top = p.flangeFace + p.flangeHeight + 2.6 * rs, bottom = p.flangeFace - .55 * p.flangeHeight;
            for (int i = 0; i < studs; i++) {
                double a = (i + .5) * Math.PI * 2 / studs;
                b.part(m("rpv_stud"), .472 * w * Math.cos(a), bottom, .472 * d * Math.sin(a), (float) rs, (float) (top - bottom), (float) rs);
            }
        }
        if (closed) {
            b.pose.pushPose();
            b.pose.translate(0, p.flangeFace, 0);
            buildHead(b, w, d, (float) p.headRise, nuts);
            b.pose.popPose();
        }
    }

    private static void seams(MachineMeshCache.Builder b, VesselProfile p, float w, float d, double taperBottom) {
        double bead = Math.max(.035, .012 * w);
        java.util.List<Double> zs = new java.util.ArrayList<>();
        zs.add(p.knuckle);
        for (double in : VesselProfile.IN_SEAMS) zs.add(p.up(in));
        zs.add(taperBottom - .04);
        zs.removeIf(z -> z < p.knuckle - .01 || z > taperBottom);
        java.util.Collections.sort(zs);
        for (double z : zs) b.part(m("rpv_seam"), 0, z - bead, 0, w, (float) (2 * bead), d);
        for (int k = 0; k + 1 < zs.size(); k++) {
            for (int j = 0; j < 3; j++) {
                double a = j * Math.PI * 2 / 3 + (k % 2) * Math.PI / 3 + .3;
                double x = (.46 * w + bead * .3) * Math.cos(a), z = (.46 * d + bead * .3) * Math.sin(a);
                double y0 = zs.get(k) + bead, y1 = zs.get(k + 1) - bead;
                if (y1 > y0) b.part(m("rpv_seam_v"), x, y0, z, (float) (bead * .7), (float) (y1 - y0), (float) (bead * .7));
            }
        }
    }

    private static void skirt(MachineMeshCache.Builder b, VesselProfile p, float w, float d) {
        double attach = p.bottomOuterY(.80);
        b.part(m("rpv_skirt_base"), 0, 0, 0, w, .12f, d);
        b.part(m("rpv_skirt"), 0, .12, 0, w, (float) (attach + .05 - .12), d);
        int nuts = (int) Math.max(16, Math.min(64, Math.round(Math.PI * 2 * .3956 * w / .55)));
        for (int i = 0; i < nuts; i++) {
            double a = (i + .5) * Math.PI * 2 / nuts;
            b.part(m("rpv_anchor_nut"), .4056 * w * Math.cos(a), .12, .4056 * d * Math.sin(a), .055f, .09f, .055f);
        }
        double r = (.3956 + .368) / 2;
        for (double deg : new double[]{100, 280}) {
            double a = Math.toRadians(deg);
            double h = (attach - .12) * .5;
            put(b, "rpv_cube_bolt", (r * w + .012) * Math.cos(a), .12 + (attach - .12) * .45, (r * d + .012) * Math.sin(a), a,
                    .03, h, Math.min(.9, .09 * w));
        }
    }

    private static void instrumentNozzles(MachineMeshCache.Builder b, VesselAppearance.Envelope e, VesselProfile p) {
        double[][] at = {{10, VesselProfile.IN_JP_INSTR}, {190, VesselProfile.IN_JP_INSTR}, {100, VesselProfile.IN_TAF}, {280, VesselProfile.IN_TAF},
                {160, VesselProfile.IN_SKIRT_BOTTOM}, {340, VesselProfile.IN_SKIRT_BOTTOM}, {110, VesselProfile.IN_SEP_TOP_SECTION}, {250, VesselProfile.IN_SEP_TOP_SECTION}};
        double r = Math.max(.05, 1.5 * p.sH);
        for (double[] n : at) {
            double a = Math.toRadians(n[0]);
            double y = Math.max(p.up(n[1]), p.knuckle + .2);
            double sx = p.ri * Math.cos(a), sz = p.ri * p.ey * Math.sin(a);
            double len = Math.max(.18, p.wall + .12);
            var dir = new Vector3f((float) Math.cos(a), 0, (float) (Math.sin(a) * p.ey)).normalize();
            // the nozzle runs out of the wall along the outward normal; the drain is at the bottom of the head
            b.pose.pushPose();
            b.pose.translate(sx, y, sz);
            b.pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), dir));
            b.pose.scale((float) r, (float) r, (float) len);
            b.model(m("rpv_instrument"));
            b.pose.popPose();
        }
        // bottom drain
        double a = Math.toRadians(225), x = .3 * .46 * e.width() * Math.cos(a), z = .3 * .46 * e.depth() * Math.sin(a);
        b.part(m("rpv_rod"), x, .05, z, (float) (r * 1.4), (float) (p.bottomOuterY(.3) - .05 + .02), (float) (r * 1.4));
    }

    /** The removable head, from its flange face up: flange forging, dome, crown nozzles, lifting lugs, nuts and washers. Shared with the thrown head. */
    public static void buildHead(MachineMeshCache.Builder b, float w, float d, float rise) {
        buildHead(b, w, d, rise, true);
    }

    /** The removable head; {@code nuts} false leaves the nuts and washers off (the studs stay in the vessel's flange): a head the crane carries, or lays on its stand. */
    public static void buildHead(MachineMeshCache.Builder b, float w, float d, float rise, boolean nuts) {
        double fl = VesselProfile.flangeHeight(rise);
        double sH = 2 * .448 * w / VesselProfile.IN_DIAMETER;
        b.part(m("rpv_head_flange"), 0, 0, 0, w, (float) fl, d);
        b.part(m("rpv_head_dome"), 0, 0, 0, w, rise, d);
        // head spray (centre), vent and a spare, each on a bolted flange
        double[][] crown = {{0, 0, 6, 1.0}, {.09, .05, 4, .8}, {-.08, .07, 4, .7}};
        for (double[] c : crown) {
            double x = c[0] * w, z = c[1] * d;
            double rf = Math.min(.99, Math.hypot(x / (.46 * w), z / (.46 * d)));
            double surface = rise * Math.sqrt(Math.max(0, 1 - rf * rf));
            double rb = Math.max(.06, c[2] / 2 * sH) * 1.6;
            double top = surface + Math.max(.25, c[3] * .5 * rise / 3);
            b.part(m("rpv_crown_neck"), x, surface - .2, z, (float) rb, (float) (top - surface + .2), (float) rb);
            b.part(m("rpv_crown_flange"), x, top, z, (float) rb, (float) rb, (float) rb);
        }
        double lug = Math.max(.35, .09 * Math.min(w, d));
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i);
            double y = rise * Math.sqrt(1 - .55 * .55);
            put(b, "rpv_lug", .55 * .46 * w * Math.cos(a), y - lug * .1, .55 * .46 * d * Math.sin(a), a, lug, lug, lug);
        }
        if (!nuts) return;
        var p = VesselProfile.of((int) w, (int) d, 10, false);
        int studs = p.studCount();
        double rs = p.studRadius();
        for (int i = 0; i < studs; i++) {
            double a = (i + .5) * Math.PI * 2 / studs;
            b.part(m("rpv_nut"), .472 * w * Math.cos(a), fl, .472 * d * Math.sin(a), (float) (1.6 * rs), (float) (1.9 * rs), (float) (1.6 * rs));
        }
    }

    // ---------------------------------------------------------------
    // Ports: the player's interfaces get a forging and a safe end
    // ---------------------------------------------------------------

    /** Forging radius by service: a steam outlet is the big 26 in class nozzle, the water ports 12 in class. */
    static double forgingRadius(VesselProfile p, VesselAppearance.PortKind kind) {
        return switch (kind) {
            case STEAM -> Math.clamp(13 * 1.55 * p.sH, .36, .62);
            case CONTROLLER -> .30;
            default -> Math.clamp(6 * 1.55 * p.sH, .30, .45);
        };
    }

    static void buildPorts(MachineMeshCache.Builder b, VesselAppearance.Envelope e) {
        var p = VesselProfile.of(e, false);
        var pose = b.pose;
        float w = e.width(), d = e.depth(), h = e.height();
        double cx = e.min().getX() + w / 2, cz = e.min().getZ() + d / 2;
        for (var port : e.ports()) {
            double x = port.getX() + .5 - cx, z = port.getZ() + .5 - cz, y = port.getY() + .5 - e.min().getY();
            Direction face = e.face(port);
            double radiusX = .46 * w, radiusZ = .46 * d;
            double startX = x, startY = y, startZ = z;
            double r = Math.sqrt(x * x / (radiusX * radiusX) + z * z / (radiusZ * radiusZ));
            if (face.getAxis().isHorizontal()) {
                // Project radially onto the shell at that height (the dished heads included), then angle the spool to the interface.
                double section = p.shellSection(y);
                startX = x * section * .98 / Math.max(.01, r);
                startZ = z * section * .98 / Math.max(.01, r);
            } else {
                double scale = Math.min(1, .94 / Math.max(.01, r));
                startX *= scale;
                startZ *= scale;
                r *= scale;
                startY = face == Direction.UP ? p.headOuterY(r) - .08 : p.bottomOuterY(r) + .08;
            }
            var kind = e.kind(port);
            boolean integrated = kind != VesselAppearance.PortKind.LEGACY;
            boolean panel = kind == VesselAppearance.PortKind.CONTROLLER;
            double tip = integrated ? (panel ? .30 : .50) : -.45;
            double endX = x + face.getStepX() * tip, endY = y + face.getStepY() * tip, endZ = z + face.getStepZ() * tip;
            double neck = integrated ? (panel ? .14 : .60) : 0;
            var vector = new Vector3f((float) (endX - face.getStepX() * neck - startX),
                    (float) (endY - face.getStepY() * neck - startY), (float) (endZ - face.getStepZ() * neck - startZ));
            float length = vector.length();
            if (length > .02) {
                pose.pushPose();
                pose.translate(startX, startY, startZ);
                pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), vector.normalize()));
                b.part(m("spool"), 0, 0, 0, 1, 1, length);
                if (!panel) {
                    double rf = forgingRadius(p, kind);
                    double lf = Math.min(length * .75, Math.max(.25, rf * 1.1));
                    b.part(m("rpv_forging"), 0, 0, -.05, (float) rf, (float) rf, (float) (lf + .05));
                    if (length > lf + .1) b.part(m("rpv_safe_end"), 0, 0, lf, .33f, .33f, (float) Math.min(.5, length - lf - .02));
                }
                pose.popPose();
            }
            if (integrated) {
                pose.pushPose();
                pose.translate(endX, endY, endZ);
                pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1),
                        new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ())));
                b.part(m(panel ? "controller_panel" : kind == VesselAppearance.PortKind.WATER ? "water_nozzle" : "steam_nozzle"), 0, 0, 0, 1, 1, 1);
                pose.popPose();
            }
        }
    }
}
