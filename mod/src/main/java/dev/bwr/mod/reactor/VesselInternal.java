package dev.bwr.mod.reactor;

/**
 * The two removable assemblies above the core that a real BWR lifts out before refuelling: the steam dryer (first out, last
 * in) and the shroud head with its standpipes and steam separators. See RPV-REFERENCE.md, "Removable dryer and separators".
 */
public enum VesselInternal {
    DRYER("dryer", "steam dryer", 0.2),
    SEPARATOR("separator", "steam separator assembly", 10.0);

    /** The CC and save name. */
    public final String id;
    /** The English name. */
    public final String title;
    /**
     * Its activation and contamination as a gamma point source, equivalent watts (DoseModel). Game-scale estimates that
     * give about 0.1 Sv/h at 1 m from a dryer and several Sv/h from a separator assembly in air, which is why both are
     * stored under water.
     */
    public final double gammaWatts;

    VesselInternal(String id, String title, double gammaWatts) {
        this.id = id;
        this.title = title;
        this.gammaWatts = gammaWatts;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public static VesselInternal byId(String id) {
        for (VesselInternal v : values()) {
            if (v.id.equalsIgnoreCase(id)) return v;
        }
        return null;
    }
}
