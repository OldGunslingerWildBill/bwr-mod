package dev.bwr.mod.reactor.client;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.VesselProfile;

import java.util.ArrayList;
import java.util.List;

import static dev.bwr.mod.reactor.client.VesselModel.along;
import static dev.bwr.mod.reactor.client.VesselModel.m;
import static dev.bwr.mod.reactor.client.VesselModel.put;

/**
 * The v2 internals, drawn only while the inside of the vessel can be seen (RPV-REFERENCE.md): three cached meshes, so lifting the
 * dryer or the separators out rebuilds nothing else.
 * <ul>
 *   <li>{@link #buildFixed}: shroud support plate, core shroud stepping out to the upper shroud at the top guide, shroud flange,
 *   core plate, top-guide rim, the six feedwater spargers, the core-spray lines, LPCI couplings, guide rods and dryer brackets
 *   (the jet pumps, the fuel and the spray rings keep their own geometry);</li>
 *   <li>{@link #buildSeparators}: the shroud head with its standpipes and steam separators (Columbia's 225, at the real pitch),
 *   the long hold-down bolts and the lifting posts;</li>
 *   <li>{@link #buildDryer}: support ring, skirt to below the water, drain channels, six vane banks with hoods and troughs, and
 *   four lifting rods with eyes reaching into the head.</li>
 * </ul>
 * All coordinates are the vessel's (origin at the footprint centre, envelope floor), so the same mesh is drawn in the vessel, on
 * the bridge's hoist and on a storage pad, moved by the pose.
 */
public final class VesselInternalsModel {
    private VesselInternalsModel() {
    }

    static void buildFixed(MachineMeshCache.Builder b, VesselProfile p) {
        float w = p.width, d = p.depth;
        double k = p.upperShroud / .418;
        double yShf = p.shroudFlange(), yTg = p.topGuide();
        if (!p.internalPumps) {
            b.part(m("rpv_support_plate"), 0, 1.62, 0, w, .1f, d);
            b.part(m("rpv_core_shroud"), 0, 1.27, 0, w, (float) (yTg - 1.27), d);
            b.part(m("rpv_shroud_step"), 0, yTg, 0, w, .12f, d);
            b.part(m("rpv_upper_shroud"), 0, yTg + .12, 0, w, (float) (yShf - .1 - yTg - .12), d);
            b.part(m("rpv_core_plate"), 0, 1.76, 0, w, .14f, d);
            b.part(m("rpv_top_guide_rim"), 0, p.coreTop + .02, 0, w, (float) (yTg - p.coreTop - .02), d);
        } else {
            // The ABWR shroud is drawn to the top of the fuel by ReactorCoreGeometry; it goes on straight up to its flange.
            b.part(m("rpv_upper_shroud"), 0, p.coreTop + .1, 0, (float) (w * k), (float) (yShf - .1 - p.coreTop - .1), (float) (d * k));
        }
        b.part(m("rpv_shroud_flange"), 0, yShf - .1, 0, (float) (w * k), .1f, (float) (d * k));
        // feedwater spargers: one curved 6 in header per nozzle, a tee from the nozzle, end brackets
        double rFw = (p.upperShroud * w + .12 + p.ri) / 2 / w;
        double yFw = p.up(VesselProfile.IN_FEEDWATER);
        double rp = Math.max(.05, 3 * p.sH);
        for (double deg : new double[]{45, 90, 135, 225, 270, 315}) {
            double a = Math.toRadians(deg);
            arc(b, rFw, yFw, a - Math.toRadians(26), a + Math.toRadians(26), rp, w, d, 8);
            along(b, "rpv_pipe", p.ri * Math.cos(a), yFw, p.ri * p.ey * Math.sin(a), rFw * w * Math.cos(a), yFw, rFw * d * Math.sin(a), rp * 1.3);
            for (double e : new double[]{a - Math.toRadians(26), a + Math.toRadians(26)})
                put(b, "rpv_cube_dark", rFw * w * Math.cos(e), yFw, rFw * d * Math.sin(e), e, rp * 2.4, rp * 3.2, rp * 2.4);
        }
        // core spray: two lines divide inside the vessel, run round the annulus, drop to the shroud and feed the rings
        double yCs = p.up(VesselProfile.IN_CORE_SPRAY), rLine = .433;
        double rpc = Math.max(.045, 2.5 * p.sH);
        double rRing = p.sprayRing;
        for (int loop = 0; loop < 2; loop++) {
            double a0 = loop * Math.PI;
            double ringY = p.sprayRingHeight(loop == 0);
            along(b, "rpv_pipe", p.ri * Math.cos(a0), yCs, p.ri * p.ey * Math.sin(a0), rLine * w * Math.cos(a0), yCs, rLine * d * Math.sin(a0), rpc * 1.2);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double a1 = a0 + sgn * Math.toRadians(70);
                arc(b, rLine, yCs, Math.min(a0, a1), Math.max(a0, a1), rpc, w, d, 10);
                double x1 = rLine * w * Math.cos(a1), z1 = rLine * d * Math.sin(a1);
                along(b, "rpv_pipe", x1, yCs, z1, x1, yShf + .2, z1, rpc);
                double xs = p.upperShroud * w * Math.cos(a1), zs = p.upperShroud * d * Math.sin(a1);
                along(b, "rpv_pipe", x1, yShf + .2, z1, xs, yShf - .25, zs, rpc);
                along(b, "rpv_pipe", xs, yShf - .25, zs, rRing * w * Math.cos(a1), ringY, rRing * d * Math.sin(a1), rpc);
            }
        }
        // LPCI couplings through the upper shroud
        double yL = p.up(VesselProfile.IN_LPCI);
        for (double deg : new double[]{90, 210, 330}) {
            double a = Math.toRadians(deg);
            along(b, "rpv_pipe", p.ri * Math.cos(a), yL, p.ri * p.ey * Math.sin(a), p.upperShroud * w * Math.cos(a), yL, p.upperShroud * d * Math.sin(a),
                    Math.max(.06, 6 * p.sH));
        }
        // guide rods for the dryer and the shroud head, and the dryer support brackets on the wall
        double yRing = p.up(VesselProfile.IN_DRYER_RING);
        for (double deg : new double[]{5, 185}) {
            double a = Math.toRadians(deg), r = p.ri - .12;
            b.part(m("rpv_rod"), r * Math.cos(a), yShf, r * p.ey * Math.sin(a), (float) Math.max(.04, .01 * w), (float) (p.flangeFace - .15 - yShf),
                    (float) Math.max(.04, .01 * w));
        }
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(22.5 + 90 * i), r = p.ri - .1;
            put(b, "rpv_cube_dark", r * Math.cos(a), yRing - .12, r * p.ey * Math.sin(a), a, .2, .2, Math.max(.3, .04 * w));
        }
    }

    /** A pipe bent round the vessel axis at radius {@code r} (a fraction of the width), from angle a0 to a1. */
    private static void arc(MachineMeshCache.Builder b, double r, double y, double a0, double a1, double radius, float w, float d, int steps) {
        for (int i = 0; i < steps; i++) {
            double u = a0 + (a1 - a0) * i / steps, v = a0 + (a1 - a0) * (i + 1) / steps;
            along(b, "rpv_pipe", r * w * Math.cos(u), y, r * d * Math.sin(u), r * w * Math.cos(v), y, r * d * Math.sin(v), radius);
        }
    }

    // ---------------------------------------------------------------
    // Shroud head and steam separators
    // ---------------------------------------------------------------

    /** Separator centres (x, z) on a hexagonal grid at the real pitch, at most 225; at least half a block apart. */
    public static List<double[]> separatorGrid(VesselProfile p) {
        double rArr = p.upperShroud * Math.min(p.width, p.depth) - .2;
        double pitch = Math.max(13.4 * p.sH, .5);
        while (true) {
            List<double[]> pts = new ArrayList<>();
            int rows = (int) (rArr / (pitch * .866)) + 1;
            for (int j = -rows; j <= rows; j++) {
                double off = (Math.floorMod(j, 2)) * pitch / 2, yy = j * pitch * .866;
                int span = (int) (rArr / pitch) + 2;
                for (int i = -span; i <= span; i++) {
                    double xx = i * pitch + off;
                    if (Math.hypot(xx, yy) <= rArr - pitch * .5) pts.add(new double[]{xx * p.width / (double) Math.min(p.width, p.depth),
                            yy * p.depth / (double) Math.min(p.width, p.depth), pitch});
                }
            }
            if (pts.size() <= 225) return pts;
            pitch *= 1.01;
        }
    }

    static double shroudHeadRise(VesselProfile p) {
        return Math.max(.12, p.up(VesselProfile.IN_SHROUD_DOME) - p.shroudFlange() - .1);
    }

    static void buildSeparators(MachineMeshCache.Builder b, VesselProfile p) {
        float w = p.width, d = p.depth;
        double k = p.upperShroud / .418;
        double yShf = p.shroudFlange(), rise = shroudHeadRise(p);
        b.part(m("rpv_shroud_flange"), 0, yShf, 0, (float) (w * k), .12f, (float) (d * k));
        b.part(m("rpv_shroud_head"), 0, yShf + .12, 0, (float) (w * k), (float) rise, (float) (d * k));
        double yBody = p.up(VesselProfile.IN_SEP_BODY), yTop = p.up(VesselProfile.IN_SEP_TOP);
        var grid = separatorGrid(p);
        double pitch = grid.isEmpty() ? .5 : grid.getFirst()[2];
        double rSep = Math.min(.47 * pitch, Math.max(6.375 * p.sH, .45 * pitch));
        double rStd = .52 * rSep;
        double rUs = p.upperShroud;
        for (double[] c : grid) {
            double rr = Math.hypot(c[0] / (rUs * w), c[1] / (rUs * d));
            double base = yShf + .12 + rise * Math.sqrt(Math.max(0, 1 - rr * rr)) - .05;
            b.part(m("rpv_standpipe"), c[0], base, c[1], (float) rStd, (float) (yBody - .25 - base), (float) rStd);
            b.part(m("rpv_separator"), c[0], yBody - .25, c[1], (float) rSep, (float) (yTop - yBody + .25), (float) rSep);
        }
        // long hold-down bolts reaching above the separators, and four lifting posts with eyes
        int bolts = (int) Math.max(12, Math.min(48, Math.round(Math.PI * 2 * rUs * w / 1.6)));
        double rb = Math.max(.03, .006 * w);
        for (int i = 0; i < bolts; i++) {
            double a = (i + .5) * Math.PI * 2 / bolts;
            double x = (rUs * w + .07) * Math.cos(a), z = (rUs * d + .07) * Math.sin(a);
            b.part(m("rpv_stud"), x, yShf - .05, z, (float) rb, (float) (yTop + .35 - yShf), (float) rb);
            b.part(m("rpv_nut"), x, yTop + .3, z, (float) (rb * 1.9), (float) (rb * 2), (float) (rb * 1.9));
        }
        double eye = p.separatorEyeRadius();
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i);
            double x = .55 * rUs * w * Math.cos(a), z = .55 * rUs * d * Math.sin(a);
            double rr = .55;
            double base = yShf + .12 + rise * Math.sqrt(1 - rr * rr);
            float post = (float) Math.max(.05, .01 * w);
            b.part(m("rpv_rod"), x, base, z, post, (float) (yTop + .45 - base), post);
            put(b, "rpv_eye", x, yTop + .45 + eye, z, a + Math.PI / 2, eye, eye, eye);
        }
    }

    // ---------------------------------------------------------------
    // Steam dryer
    // ---------------------------------------------------------------

    static void buildDryer(MachineMeshCache.Builder b, VesselProfile p) {
        float w = p.width, d = p.depth;
        double yRing = p.up(VesselProfile.IN_DRYER_RING), ySkirt = p.up(VesselProfile.IN_SKIRT_BOTTOM), yTop = p.up(VesselProfile.IN_DRYER_TOP);
        b.part(m("rpv_dryer_ring"), 0, yRing - .14, 0, w, .18f, d);
        b.part(m("rpv_dryer_skirt"), 0, ySkirt, 0, w, (float) (yRing - .14 - ySkirt), d);
        b.part(m("rpv_dryer_skirt_ring"), 0, ySkirt - .02, 0, w, .12f, d);
        for (int i = 0; i < 8; i++) {
            double a = (i + .5) * Math.PI * 2 / 8;
            put(b, "rpv_cube_ss", (.428 * w + .04) * Math.cos(a), (ySkirt + yRing) / 2, (.428 * d + .04) * Math.sin(a), a,
                    .08, yRing - ySkirt - .2, Math.max(.12, .02 * w));
        }
        // six vane banks along x; seven plenums alternate inlet (hood over it) and outlet (open to the dome, closed below)
        double rBank = .427;
        double span = 2 * rBank;
        double gap = span / (6 * 1.2 + 7), bank = 1.2 * gap;
        double y0 = -span / 2, z0 = yRing + .05, hb = yTop - yRing - .25;
        for (int i = 0; i < 13; i++) {
            double wi = i % 2 == 1 ? bank : gap;
            double zc = y0 + wi / 2;
            y0 += wi;
            double lim = Math.abs(zc) + wi / 2;
            if (lim >= rBank) lim = Math.abs(zc);
            double half = Math.sqrt(Math.max(0, rBank * rBank - lim * lim)) - .05 / w;
            if (half * w <= .2) continue;
            double len = 2 * half * w, cz = zc * d;
            if (i % 2 == 1) {
                b.part(m("rpv_dryer_bank"), 0, z0, cz, (float) len, (float) hb, (float) (bank * d));
            } else if (i % 4 == 0) {
                b.part(m("rpv_dryer_hood"), 0, z0 + hb, cz, (float) len, (float) Math.min(.5, .35 * wi * d), (float) (wi * d));
            } else {
                b.part(m("rpv_cube_dark"), 0, z0 + .02, cz, (float) len, .04f, (float) (wi * d));
            }
            for (int s = -1; s <= 1; s += 2) b.part(m("rpv_cube_dark"), s * half * w, z0 + hb / 2 + .05, cz, .06f, (float) (hb + .1), (float) (wi * d));
        }
        b.part(m("rpv_dryer_band"), 0, z0, 0, w, (float) (hb + .1), d);
        double eyeY = p.dryerEyeY(), eye = p.dryerEyeRadius();
        float rod = (float) Math.max(.05, .009 * w);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i);
            double x = (rBank * w - .15) * Math.cos(a), z = (rBank * d - .15) * Math.sin(a);
            b.part(m("rpv_rod"), x, yRing - .14, z, rod, (float) (eyeY - yRing + .14), rod);
            put(b, "rpv_eye", x, eyeY + eye, z, a + Math.PI / 2, eye, eye, eye);
        }
    }
}
