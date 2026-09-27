package dev.bwr.core.fuel;

/** Visual proportions from GE ABWR DCD Tier 2, Table 5.3-2 (dimensions in mm).
 * These describe the shroud, not the discrete Minecraft pump/CRD capacity mask.
 * A block-wide construction envelope must not determine the visible annulus. */
public final class AbwrShroudGeometry {
    public static final double VESSEL_ID_MM = 7112.0;
    public static final double SHROUD_OD_MM = 5600.7;
    public static final double SHROUD_WALL_MM = 57.2;
    // Matches the Blender vessel barrel's inner surface, per outside block width.
    public static final double VESSEL_INNER_RADIUS = .448;
    public static final double OUTER_RADIUS = VESSEL_INNER_RADIUS * SHROUD_OD_MM / VESSEL_ID_MM;
    public static final double INNER_RADIUS = VESSEL_INNER_RADIUS * (SHROUD_OD_MM - 2 * SHROUD_WALL_MM) / VESSEL_ID_MM;
    // Game-art flange and fuel clearance; not manufacturer engineering dimensions.
    public static final double RIM_RADIUS = OUTER_RADIUS + .008;
    public static final double FUEL_RADIUS = INNER_RADIUS * .98;

    public static double gapBlocks(int outsideWidth) {
        return outsideWidth * (VESSEL_INNER_RADIUS - OUTER_RADIUS);
    }
    private AbwrShroudGeometry() {}
}
