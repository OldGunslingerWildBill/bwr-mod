package dev.bwr.mod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of a uranium "super vein": a long, winding ribbon of altered host rock with ore in it, a few hundred blocks
 * long. This class is plain geometry, with no Minecraft types, so the same code is used by the world generator
 * ({@link UraniumVeinFeature}), by the tests and by the check that measures what generation produced.
 *
 * <h2>Why a vein is a pure function of the world seed and a grid cell</h2>
 * A worldgen feature may write only inside the chunk it is placed in (plus one chunk around it) because the chunks
 * beyond that may be unloaded or already finished. A vein several hundred blocks long crosses dozens of chunks, so it
 * cannot be placed by one chunk. Instead the world is divided into square cells (384 blocks by default). A cell holds at
 * most one vein, whose existence roll, path, widths and branches come from a random generator seeded by
 * {@code (world seed, cell x, cell z, salt)} and nothing else. Every chunk the vein passes through asks "which veins of
 * the cells around me reach into my 16 x 16 columns?", rebuilds them (from a small cache) and places only the blocks that
 * are inside its own columns. Every decision about a block (host or body, filler or ore, raw ore block or not) is a hash
 * of the seed and the block position. So:
 * <ul>
 *   <li>no chunk ever writes into another chunk, loaded or not, however long the vein;</li>
 *   <li>the slices of neighbouring chunks meet exactly, whatever order the chunks are generated in;</li>
 *   <li>generating a chunk twice gives the same blocks, and a chunk generated now does not depend on any other.</li>
 * </ul>
 *
 * <h2>The body</h2>
 * A vein is a chain of straight segments ({@link #STEP} blocks long) with a heading that wanders and a pitch that stays
 * within about 17 degrees of level, so it is a sheet or a lens rather than a pipe: wider than it is thick, tapering to a
 * point at both ends, swelling and pinching along its length, with up to {@code branchesMax} shorter side ribbons.
 * A block is in the body when its normalised distance to the ribbon, {@code sqrt((u / W)^2 + (v / H)^2)} (W the half
 * width, H the half thickness at that point, u and v the offsets across and up), plus a lump of low-frequency noise on
 * the edge, is below one. Inside the body the host rock becomes filler (granite, tuff...); pockets of the filler become
 * ore, richer towards the middle; a few of the ore spots are raw uranium blocks.
 */
public final class UraniumVeins {

    private UraniumVeins() {
    }

    /** Spacing of the control points of a ribbon, in blocks. */
    public static final int STEP = 8;

    /** What a block of the body becomes. */
    public enum Kind { FILL, ORE, RAW }

    /** Receives the blocks of a vein that lie inside the box asked for. */
    public interface Sink {
        void accept(int x, int y, int z, Kind kind);
    }

    /**
     * The shape tunables. They are the same for every biome group by default, so a vein keeps its outline where it
     * passes from one group's chunks into another's (only its chance of existing and its grade change). Changing them
     * in one datapack file and not the others makes the vein's outline differ at the seam.
     *
     * @param cellSize        side of a grid cell, blocks; one vein at most per cell
     * @param minY            lowest height the centre of a vein may have
     * @param maxY            highest
     * @param lengthMin       the main ribbon's length, blocks (the whole vein with its branches is longer)
     * @param lengthMax       ditto
     * @param halfWidthMin    half the main ribbon's width at its widest, blocks
     * @param halfWidthMax    ditto
     * @param halfThicknessMin half its thickness at the widest
     * @param halfThicknessMax ditto
     * @param branchesMax     most side ribbons (0 to this many)
     * @param salt            changes every vein in the world, for a second, independent set
     */
    public record Geometry(int cellSize, int minY, int maxY, int lengthMin, int lengthMax, float halfWidthMin,
                           float halfWidthMax, float halfThicknessMin, float halfThicknessMax, int branchesMax, int salt) {

        public static final Geometry DEFAULT = new Geometry(384, -48, 52, 200, 360, 4.5f, 8.0f, 2.5f, 4.5f, 2, 0);

        public static final Codec<Geometry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(64, 4096).optionalFieldOf("cell_size", DEFAULT.cellSize).forGetter(Geometry::cellSize),
                Codec.intRange(-2048, 2048).optionalFieldOf("min_y", DEFAULT.minY).forGetter(Geometry::minY),
                Codec.intRange(-2048, 2048).optionalFieldOf("max_y", DEFAULT.maxY).forGetter(Geometry::maxY),
                Codec.intRange(16, 1024).optionalFieldOf("length_min", DEFAULT.lengthMin).forGetter(Geometry::lengthMin),
                Codec.intRange(16, 1024).optionalFieldOf("length_max", DEFAULT.lengthMax).forGetter(Geometry::lengthMax),
                Codec.floatRange(0.5f, 32f).optionalFieldOf("half_width_min", DEFAULT.halfWidthMin).forGetter(Geometry::halfWidthMin),
                Codec.floatRange(0.5f, 32f).optionalFieldOf("half_width_max", DEFAULT.halfWidthMax).forGetter(Geometry::halfWidthMax),
                Codec.floatRange(0.5f, 32f).optionalFieldOf("half_thickness_min", DEFAULT.halfThicknessMin).forGetter(Geometry::halfThicknessMin),
                Codec.floatRange(0.5f, 32f).optionalFieldOf("half_thickness_max", DEFAULT.halfThicknessMax).forGetter(Geometry::halfThicknessMax),
                Codec.intRange(0, 6).optionalFieldOf("branches_max", DEFAULT.branchesMax).forGetter(Geometry::branchesMax),
                Codec.INT.optionalFieldOf("salt", DEFAULT.salt).forGetter(Geometry::salt)
        ).apply(i, Geometry::new));

        /** The farthest any block of a vein can be from the random point its cell gave it, blocks. */
        double reach() {
            double half = Math.max(lengthMin, lengthMax) * 0.5;
            // a side ribbon starts on the main one and is at most 0.35 of its length
            return half + 0.35 * Math.max(lengthMin, lengthMax) + 1.7 * Math.max(halfWidthMax, halfThicknessMax) + 4;
        }

        /** How many cells away from a chunk's own a vein can start and still reach into the chunk. */
        public int reachCells() {
            return (int) Math.ceil(reach() / cellSize);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hashing and noise: pure functions of the seed and the position.

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long hash(long seed, long a, long b, long c) {
        long h = mix(seed ^ 0x9E3779B97F4A7C15L);
        h = mix(h ^ (a * 0x632BE59BD9B4E019L));
        h = mix(h ^ (b * 0x8CB92BA72F3D8DD7L));
        return mix(h ^ (c * 0xD6E8FEB86659FD93L));
    }

    static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static double lattice(long seed, int x, int y, int z) {
        return unit(hash(seed, x, y, z));
    }

    /** Smooth value noise in [0, 1). */
    static double noise3(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = x - x0, fy = y - y0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double c000 = lattice(seed, x0, y0, z0), c100 = lattice(seed, x0 + 1, y0, z0);
        double c010 = lattice(seed, x0, y0 + 1, z0), c110 = lattice(seed, x0 + 1, y0 + 1, z0);
        double c001 = lattice(seed, x0, y0, z0 + 1), c101 = lattice(seed, x0 + 1, y0, z0 + 1);
        double c011 = lattice(seed, x0, y0 + 1, z0 + 1), c111 = lattice(seed, x0 + 1, y0 + 1, z0 + 1);
        double x00 = c000 + (c100 - c000) * fx, x10 = c010 + (c110 - c010) * fx;
        double x01 = c001 + (c101 - c001) * fx, x11 = c011 + (c111 - c011) * fx;
        double y0v = x00 + (x10 - x00) * fy, y1v = x01 + (x11 - x01) * fy;
        return y0v + (y1v - y0v) * fz;
    }

    // ------------------------------------------------------------------------------------------------------------
    // A vein

    /** One ribbon: a chain of segments with a cross-section that changes along its length. */
    static final class Ribbon {
        final int n;                       // control points
        final double[] x, y, z;
        final double[] tx, ty, tz, nx, nz, bx, by, bz, len;   // per segment: unit tangent, horizontal normal, binormal, length
        final double halfWidth, halfThickness;
        final double[] amp = new double[3], freq = new double[3], phase = new double[3];
        final double radius;               // bounding radius used to prune segments
        final double[] sc;                 // cross-section scale at each control point

        Ribbon(double[] x, double[] y, double[] z, double halfWidth, double halfThickness, SplittableRandom rng) {
            this.n = x.length;
            this.x = x;
            this.y = y;
            this.z = z;
            this.halfWidth = halfWidth;
            this.halfThickness = halfThickness;
            int s = n - 1;
            tx = new double[s];
            ty = new double[s];
            tz = new double[s];
            nx = new double[s];
            nz = new double[s];
            bx = new double[s];
            by = new double[s];
            bz = new double[s];
            len = new double[s];
            for (int i = 0; i < s; i++) {
                double dx = x[i + 1] - x[i], dy = y[i + 1] - y[i], dz = z[i + 1] - z[i];
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                len[i] = l;
                tx[i] = dx / l;
                ty[i] = dy / l;
                tz[i] = dz / l;
                // horizontal normal = T x up, normalised (pitch is limited, so T is never vertical)
                double hx = -tz[i], hz = tx[i];
                double hl = Math.sqrt(hx * hx + hz * hz);
                nx[i] = hx / hl;
                nz[i] = hz / hl;
                // binormal = N x T
                bx[i] = -nz[i] * ty[i];
                by[i] = nz[i] * tx[i] - nx[i] * tz[i];
                bz[i] = nx[i] * ty[i];
            }
            double[] baseFreq = {1.4, 3.1, 5.9};
            double[] baseAmp = {0.22, 0.14, 0.08};
            for (int k = 0; k < 3; k++) {
                amp[k] = baseAmp[k];
                freq[k] = baseFreq[k] * (0.85 + 0.3 * rng.nextDouble());
                phase[k] = rng.nextDouble() * Math.PI * 2;
            }
            radius = 1.7 * Math.max(halfWidth, halfThickness) + 1;
            sc = new double[n];
            for (int i = 0; i < n; i++) {
                sc[i] = scale(i / (double) (n - 1));
            }
        }

        /** Cross-section scale along the ribbon, s in [0, 1]: a tapered lens times a slow swelling. */
        double scale(double s) {
            double env = Math.pow(Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, s))), 0.55);
            double swell = 0.8;
            for (int k = 0; k < 3; k++) {
                swell += amp[k] * Math.sin(2 * Math.PI * freq[k] * s + phase[k]);
            }
            return Math.max(0.1, env) * swell;
        }
    }

    /** A vein: its existence roll and its ribbons. */
    public static final class Vein {
        public final int cellX, cellZ;
        /** The vein exists where this is below the chance of the biome group of the chunk asking. */
        public final double roll;
        final long salt;
        final List<Ribbon> ribbons;
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        /** Blocks of the main ribbon's centre line, for the checks and the screenshots. */
        public final double[][] centreLine;

        Vein(int cellX, int cellZ, double roll, long salt, List<Ribbon> ribbons) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.roll = roll;
            this.salt = salt;
            this.ribbons = ribbons;
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
            double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (Ribbon r : ribbons) {
                for (int i = 0; i < r.n; i++) {
                    x0 = Math.min(x0, r.x[i] - r.radius);
                    x1 = Math.max(x1, r.x[i] + r.radius);
                    y0 = Math.min(y0, r.y[i] - r.radius);
                    y1 = Math.max(y1, r.y[i] + r.radius);
                    z0 = Math.min(z0, r.z[i] - r.radius);
                    z1 = Math.max(z1, r.z[i] + r.radius);
                }
            }
            minX = x0;
            minY = y0;
            minZ = z0;
            maxX = x1;
            maxY = y1;
            maxZ = z1;
            Ribbon main = ribbons.get(0);
            centreLine = new double[main.n][];
            for (int i = 0; i < main.n; i++) {
                centreLine[i] = new double[] {main.x[i], main.y[i], main.z[i]};
            }
        }

        /** True when the box (inclusive block coordinates) can hold a block of this vein. */
        public boolean intersects(int x0, int y0, int z0, int x1, int y1, int z1) {
            return x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ;
        }
    }

    private record CellKey(long seed, int cx, int cz, Geometry g) {
    }

    private static final ConcurrentHashMap<CellKey, Vein> CACHE = new ConcurrentHashMap<>();

    /**
     * The vein that the cell {@code (cellX, cellZ)} may hold in the world with this seed. It always exists as geometry;
     * whether the cell really has one is a comparison of {@link Vein#roll} against a chance, made by the caller, so
     * that a higher chance always includes every vein a lower one has.
     */
    public static Vein vein(long worldSeed, int cellX, int cellZ, Geometry g) {
        if (CACHE.size() > 2048) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(new CellKey(worldSeed, cellX, cellZ, g), k -> build(k.seed, k.cx, k.cz, k.g));
    }

    private static double gauss(SplittableRandom rng) {
        // sum of 4 uniforms: close enough to a bell, cheap and portable
        return (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 2.0) * 1.7320508;
    }

    private static Vein build(long worldSeed, int cellX, int cellZ, Geometry g) {
        long veinSalt = hash(worldSeed, cellX, cellZ, 0x5A17L + g.salt);
        SplittableRandom rng = new SplittableRandom(veinSalt);
        double roll = rng.nextDouble();
        double sx = (cellX + rng.nextDouble()) * g.cellSize;
        double sz = (cellZ + rng.nextDouble()) * g.cellSize;
        int lo = Math.min(g.minY, g.maxY), hi = Math.max(g.minY, g.maxY);
        double margin = Math.min(14, (hi - lo) / 3.0);
        double sy = lo + margin + rng.nextDouble() * Math.max(0, hi - lo - 2 * margin);
        double length = g.lengthMin + rng.nextDouble() * Math.max(0, g.lengthMax - g.lengthMin);
        double heading = rng.nextDouble() * Math.PI * 2;
        double w0 = g.halfWidthMin + rng.nextDouble() * Math.max(0, g.halfWidthMax - g.halfWidthMin);
        double h0 = g.halfThicknessMin + rng.nextDouble() * Math.max(0, g.halfThicknessMax - g.halfThicknessMin);

        List<Ribbon> ribbons = new ArrayList<>();
        Ribbon main = walk(rng, sx, sy, sz, heading, length, w0, h0, lo, hi, true);
        ribbons.add(main);
        int branches = g.branchesMax <= 0 ? 0 : rng.nextInt(g.branchesMax + 1);
        for (int b = 0; b < branches; b++) {
            int k = main.n / 5 + rng.nextInt(Math.max(1, main.n * 3 / 5));
            k = Math.min(k, main.n - 2);
            double tangent = Math.atan2(main.tz[k], main.tx[k]);
            double side = rng.nextBoolean() ? 1 : -1;
            double bHeading = tangent + side * (0.6 + 0.5 * rng.nextDouble());
            double bLength = length * (0.2 + 0.15 * rng.nextDouble());
            double scale = 0.55 + 0.2 * rng.nextDouble();
            ribbons.add(walk(rng, main.x[k], main.y[k], main.z[k], bHeading, bLength, w0 * scale, h0 * scale, lo, hi, false));
        }
        return new Vein(cellX, cellZ, roll, veinSalt, ribbons);
    }

    /** A random walk of control points, {@code centred} so the walk's middle point is at the start point. */
    private static Ribbon walk(SplittableRandom rng, double sx, double sy, double sz, double heading, double length,
                               double w0, double h0, int lo, int hi, boolean centred) {
        int n = Math.max(4, (int) Math.round(length / STEP) + 1);
        double[] x = new double[n], y = new double[n], z = new double[n];
        double hd = heading, pitch = 0, py = 0;
        double wiggle = rng.nextDouble() * Math.PI * 2;
        for (int i = 1; i < n; i++) {
            hd += 0.26 * gauss(rng) + 0.09 * Math.sin(i * 0.33 + wiggle);
            pitch += 0.09 * gauss(rng) - 0.05 * (py / 12.0);
            pitch = Math.max(-0.3, Math.min(0.3, pitch));
            double c = Math.cos(pitch);
            x[i] = x[i - 1] + STEP * Math.cos(hd) * c;
            z[i] = z[i - 1] + STEP * Math.sin(hd) * c;
            py += STEP * Math.sin(pitch);
            y[i] = py;
        }
        int anchor = centred ? n / 2 : 0;
        double ox = sx - x[anchor], oy = sy - y[anchor], oz = sz - z[anchor];
        double minC = lo + 2, maxC = hi - 2;
        for (int i = 0; i < n; i++) {
            x[i] += ox;
            z[i] += oz;
            y[i] = maxC < minC ? sy : Math.max(minC, Math.min(maxC, y[i] + oy));
        }
        return new Ribbon(x, y, z, w0, h0, rng);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The blocks of a vein inside a box

    /** Points at which the quantile table of the ore score is sampled. */
    private static final double[] SCORE_QUANTILES = scoreQuantiles();

    private static double score(long salt, double x, double y, double z) {
        double pocket = noise3(salt + 11, x * 0.27, y * 0.27, z * 0.27);
        double grain = unit(hash(salt + 12, (long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z)));
        return 0.7 * pocket + 0.3 * grain;
    }

    private static double[] scoreQuantiles() {
        int samples = 40_000;
        double[] v = new double[samples];
        SplittableRandom r = new SplittableRandom(0x0DEC0DEDL);
        for (int i = 0; i < samples; i++) {
            v[i] = score(0x1234567L, r.nextInt(-5000, 5000), r.nextInt(-300, 300), r.nextInt(-5000, 5000));
        }
        Arrays.sort(v);
        double[] q = new double[1001];
        for (int i = 0; i <= 1000; i++) {
            q[i] = v[Math.min(samples - 1, (int) ((long) i * (samples - 1) / 1000))];
        }
        return q;
    }

    /** The score below which a fraction {@code p} of the body is ore (the score is not uniform; this is its quantile). */
    private static double scoreThreshold(double p) {
        if (p <= 0) {
            return -1;
        }
        if (p >= 1) {
            return 2;
        }
        double f = p * 1000;
        int i = (int) f;
        return SCORE_QUANTILES[i] + (SCORE_QUANTILES[Math.min(1000, i + 1)] - SCORE_QUANTILES[i]) * (f - i);
    }

    /**
     * Calls {@code sink} for every block of {@code vein} inside the box (inclusive), once each.
     *
     * @param oreDensity    the share of the body that is ore at the centre line (about 0.6 of that at the edge)
     * @param rawBlockChance the share of the ore spots that are raw uranium blocks
     * @return the number of blocks given to the sink
     */
    public static int slice(Vein vein, long worldSeed, int x0, int y0, int z0, int x1, int y1, int z1,
                            double oreDensity, double rawBlockChance, Sink sink) {
        int bx0 = Math.max(x0, (int) Math.floor(vein.minX)), bx1 = Math.min(x1, (int) Math.ceil(vein.maxX));
        int by0 = Math.max(y0, (int) Math.floor(vein.minY)), by1 = Math.min(y1, (int) Math.ceil(vein.maxY));
        int bz0 = Math.max(z0, (int) Math.floor(vein.minZ)), bz1 = Math.min(z1, (int) Math.ceil(vein.maxZ));
        if (bx0 > bx1 || by0 > by1 || bz0 > bz1) {
            return 0;
        }
        int sx = bx1 - bx0 + 1, sy = by1 - by0 + 1, sz = bz1 - bz0 + 1;
        float[] best = new float[sx * sy * sz];
        Arrays.fill(best, Float.MAX_VALUE);
        boolean touched = false;
        for (Ribbon r : vein.ribbons) {
            int segs = r.n - 1;
            for (int i = 0; i < segs; i++) {
                double ax = r.x[i], ay = r.y[i], az = r.z[i];
                double ex = r.x[i + 1], ey = r.y[i + 1], ez = r.z[i + 1];
                int sx0 = Math.max(bx0, (int) Math.floor(Math.min(ax, ex) - r.radius));
                int sx1 = Math.min(bx1, (int) Math.ceil(Math.max(ax, ex) + r.radius));
                int sy0 = Math.max(by0, (int) Math.floor(Math.min(ay, ey) - r.radius));
                int sy1 = Math.min(by1, (int) Math.ceil(Math.max(ay, ey) + r.radius));
                int sz0 = Math.max(bz0, (int) Math.floor(Math.min(az, ez) - r.radius));
                int sz1 = Math.min(bz1, (int) Math.ceil(Math.max(az, ez) + r.radius));
                if (sx0 > sx1 || sy0 > sy1 || sz0 > sz1) {
                    continue;
                }
                touched = true;
                double tx = r.tx[i], ty = r.ty[i], tz = r.tz[i], len = r.len[i];
                double nx = r.nx[i], nz = r.nz[i], bx = r.bx[i], by = r.by[i], bz = r.bz[i];
                for (int x = sx0; x <= sx1; x++) {
                    double dx = x + 0.5 - ax;
                    for (int z = sz0; z <= sz1; z++) {
                        double dz = z + 0.5 - az;
                        for (int y = sy0; y <= sy1; y++) {
                            double dy = y + 0.5 - ay;
                            double a = dx * tx + dy * ty + dz * tz;
                            double ac = a < 0 ? 0 : Math.min(a, len);
                            double ox = dx - tx * ac, oy = dy - ty * ac, oz = dz - tz * ac;
                            double u = ox * nx + oz * nz;
                            double v = ox * bx + oy * by + oz * bz;
                            double sc = r.sc[i] + (r.sc[i + 1] - r.sc[i]) * (ac / len);
                            double w = r.halfWidth * sc, h = r.halfThickness * sc;
                            double axial = (a - ac) / w;
                            double rr = Math.sqrt((u / w) * (u / w) + (v / h) * (v / h) + axial * axial);
                            int idx = ((x - bx0) * sz + (z - bz0)) * sy + (y - by0);
                            if (rr < best[idx]) {
                                best[idx] = (float) rr;
                            }
                        }
                    }
                }
            }
        }
        if (!touched) {
            return 0;
        }
        int placed = 0;
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                for (int y = by0; y <= by1; y++) {
                    float rr = best[((x - bx0) * sz + (z - bz0)) * sy + (y - by0)];
                    if (rr > 1.3f) {
                        continue;
                    }
                    double edge = noise3(vein.salt + 7, x * 0.19 + 0.5, y * 0.19 + 0.5, z * 0.19 + 0.5);
                    double eff = rr + (edge - 0.5) * 0.55;
                    if (eff >= 1.0) {
                        continue;
                    }
                    double pOre = oreDensity * (0.6 + 0.8 * (1.0 - Math.max(0.0, eff)));
                    Kind kind = Kind.FILL;
                    if (score(vein.salt, x + 0.5, y + 0.5, z + 0.5) < scoreThreshold(Math.min(1.0, pOre))) {
                        kind = unit(hash(vein.salt + 13, x, y, z)) < rawBlockChance ? Kind.RAW : Kind.ORE;
                    }
                    sink.accept(x, y, z, kind);
                    placed++;
                }
            }
        }
        return placed;
    }
}
