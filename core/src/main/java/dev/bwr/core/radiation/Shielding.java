package dev.bwr.core.radiation;

/**
 * Shielding by matter on the line between a gamma source and a dose point: half-value layers and the path through a grid
 * of one-metre cells. Pure arithmetic, no Minecraft.
 *
 * <h2>One energy, broad beam</h2>
 * Every material has one half-value layer at about <b>1 MeV</b>, the energy {@link DoseModel} uses for everything (it sits between
 * caesium-137's 0.662 MeV and cobalt-60's 1.17 and 1.33 MeV, and is the middle of a fission product spectrum). The values are the
 * rounded <b>broad-beam</b> figures of shielding practice, which include the build-up of scattered photons in a thick wall: lead
 * 1 cm, steel 2.5 cm, concrete 6 cm (typical health physics tables, for example Cember and Johnson, Introduction to Health
 * Physics, give about 1.2, 2.1 and 6.2 cm for cobalt-60 and 0.65, 1.6 and 4.8 cm for caesium-137; the narrow-beam values from the
 * NIST XCOM mass attenuation coefficients at 1 MeV are 0.86, 1.47 and 4.6 cm, kept here as {@code NIST_*} for the docs). A wall a player builds is a thick broad-beam shield, and the broad-beam value is the
 * conservative one. Lead's 1 cm is also the default {@code lead_hvl_cm} of the RTG fuel list (RTG.md), so a lead block and the
 * Large RTG's housing agree.
 *
 * <p><b>Water is the exception</b>: {@link #WATER_HVL_CM} is the dose model's own {@link DoseModel#WATER_ATTENUATION_PER_M} (9.8 cm),
 * so a water block and the pool's water are one number and a ray through either gives the same answer.
 *
 * <p>Other materials scale concrete's mass half-value layer (6 cm x 2.3 g/cm3 = 13.8 g/cm2; at 1 MeV Compton scattering dominates
 * and the mass attenuation of light materials is nearly the same) by their density: earth 1.5 g/cm3 about 9 cm, wood 0.6 g/cm3
 * about 23 cm, glass 2.5 g/cm3 about 5.5 cm. A low-energy emitter (americium-241's 60 keV) is stopped much sooner in reality, so
 * for it this shielding is conservative.
 *
 * <h2>Transmission</h2>
 * {@code T = product over the path of 2^(-path / HVL) = e^(-sum mu x path)}: {@link #transmission} of the summed optical
 * depth. Air is not here: {@link DoseModel#pointSourceMsvPerH} already applies its {@code e^(-0.0076 r)}.
 */
public final class Shielding {
    private Shielding() {
    }

    /** ln 2. */
    public static final double LN2 = Math.log(2.0);

    /** Beyond this optical depth {@code e^-x} is below 1e-300: the transmission is zero and the march can stop. */
    public static final double MAX_OPTICAL_DEPTH = 690.0;

    /** Half-value layer of water, cm: the dose model's own water coefficient, so a block of water and the pool's water are one number. */
    public static final double WATER_HVL_CM = 100.0 * LN2 / DoseModel.WATER_ATTENUATION_PER_M;

    /** Half-value layer of lead, cm (broad beam, about 1 MeV; RTG.md's default {@code lead_hvl_cm}). */
    public static final double LEAD_HVL_CM = 1.0;

    /** Half-value layer of steel and iron, cm (broad beam, about 1 MeV); every metal block but lead counts as steel. */
    public static final double STEEL_HVL_CM = 2.5;

    /** Half-value layer of ordinary concrete, cm (broad beam, about 1 MeV); stone, ores and bricks count as concrete. */
    public static final double CONCRETE_HVL_CM = 6.0;

    /** Leaded (high-density) concrete, cm: Immersive Engineering's leaded concrete. */
    public static final double LEADED_CONCRETE_HVL_CM = 3.5;

    /** The mod's three shield plug concrete tiers, cm: steel-laden concrete, more steel and netherite, mostly netherite. */
    public static final double SHIELD_PLUG_HVL_CM = 5.0;
    public static final double HEAVY_SHIELD_PLUG_HVL_CM = 3.5;
    public static final double ULTRA_SHIELD_PLUG_HVL_CM = 2.5;

    /** Earth (dirt, sand, gravel; about 1.5 g/cm3), cm. */
    public static final double EARTH_HVL_CM = 9.0;

    /** Wood (about 0.6 g/cm3), cm. */
    public static final double WOOD_HVL_CM = 23.0;

    /** Glass (about 2.5 g/cm3), cm. A glass block or pane counts as {@link #GLASS_FILL} of its cell: a 1 cm glazed panel, not a cube of glass. */
    public static final double GLASS_HVL_CM = 5.5;
    public static final double GLASS_FILL = 0.01;

    /** Narrow-beam half-value layers at 1 MeV from the NIST XCOM mass attenuation coefficients (mu/rho x rho), cm: for the docs only. */
    public static final double NIST_LEAD_HVL_CM = LN2 / (0.07102 * 11.35);
    public static final double NIST_IRON_HVL_CM = LN2 / (0.05995 * 7.874);
    public static final double NIST_CONCRETE_HVL_CM = LN2 / (0.06495 * 2.30);

    /** Attenuation coefficient per metre for a half-value layer in centimetres; zero for none, a non-positive or a non-finite one. */
    public static double attenuationPerM(double hvlCm) {
        return hvlCm > 0.0 && Double.isFinite(hvlCm) ? LN2 / (hvlCm / 100.0) : 0.0;
    }

    /** The half-value layer, cm, of an attenuation coefficient per metre (infinite for none). */
    public static double hvlCm(double attenuationPerM) {
        return attenuationPerM > 0.0 ? 100.0 * LN2 / attenuationPerM : Double.POSITIVE_INFINITY;
    }

    /** {@code e^(-opticalDepth)}, 1 for none, exactly 0 past {@link #MAX_OPTICAL_DEPTH}. */
    public static double transmission(double opticalDepth) {
        if (!(opticalDepth > 0.0)) {
            return 1.0;
        }
        return opticalDepth >= MAX_OPTICAL_DEPTH ? 0.0 : Math.exp(-opticalDepth);
    }

    /** {@code 2^(-pathCm / hvlCm)}: what gets through a slab of one material. */
    public static double slabTransmission(double pathCm, double hvlCm) {
        return transmission(attenuationPerM(hvlCm) * Math.max(0.0, pathCm) / 100.0);
    }

    /** Centimetres of lead that would attenuate as much as this optical depth. */
    public static double leadEquivalentCm(double opticalDepth) {
        return opticalDepth > 0.0 ? opticalDepth / attenuationPerM(LEAD_HVL_CM) * 100.0 : 0.0;
    }

    /**
     * Attenuation per metre of one cell: the solid part ({@code solidPerM}, already scaled by how much of the cell it fills) and
     * the water in it ({@code waterFraction} of the cell, a water block or a waterlogged block's water), the water only when it is
     * counted. A source whose own transmission already has the water on the line in it (the spent fuel pool's racks, an assembly
     * on the bridge, the open core) passes {@code countWater = false}: its water is in its own number and must not be counted twice.
     */
    public static double cellAttenuationPerM(double solidPerM, double waterFraction, boolean countWater) {
        double solid = solidPerM > 0.0 && Double.isFinite(solidPerM) ? solidPerM : 0.0;
        double water = countWater && waterFraction > 0.0 ? Math.min(1.0, waterFraction) * DoseModel.WATER_ATTENUATION_PER_M : 0.0;
        return solid + water;
    }

    /** One cell crossed by a ray and the length of the ray inside it. */
    @FunctionalInterface
    public interface CellVisitor {
        /** Return false to stop the march. */
        boolean visit(int x, int y, int z, double lengthM);
    }

    /**
     * Walks the unit cells (cell {@code (x, y, z)} spans {@code [x, x+1)} on each axis) that the straight segment from A to B
     * crosses, in order from A, giving each the length of the segment inside it (3D DDA, Amanatides and Woo 1987). The lengths
     * add up to |AB|. A segment along a cell face is given to the cells on one side of it, never to both. Returns the number of
     * cells visited.
     */
    public static int march(double ax, double ay, double az, double bx, double by, double bz, CellVisitor visitor) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length)) {
            return 0;
        }
        int x = floor(ax), y = floor(ay), z = floor(az);
        int ex = floor(bx), ey = floor(by), ez = floor(bz);
        if (length < 1.0e-12) {
            visitor.visit(x, y, z, 0.0);
            return 1;
        }
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        // parametric distance (0..1 along AB) to the next boundary on each axis, and between boundaries
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1 - ax : ax - x) / Math.abs(dx));
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? y + 1 - ay : ay - y) / Math.abs(dy));
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1 - az : az - z) / Math.abs(dz));
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        int maxCells = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 1;
        double t = 0.0;
        int visited = 0;
        while (visited < maxCells) {
            double next = Math.min(1.0, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            visited++;
            double segment = (next - t) * length;
            if (segment > 0.0 || visited == maxCells) {
                if (!visitor.visit(x, y, z, Math.max(0.0, segment))) {
                    return visited;
                }
            }
            if (next >= 1.0 || x == ex && y == ey && z == ez) {
                return visited;
            }
            t = next;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return visited;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
