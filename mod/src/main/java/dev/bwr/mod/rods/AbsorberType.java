package dev.bwr.mod.rods;

/**
 * Neutron absorber loaded into a cruciform control blade — {@code SPEC.md}
 * section 3.1.
 *
 * <p>Two variants, and the tradeoff between them is depletion, not worth. Boron
 * carbide is the standard absorber; the B-10 in it is consumed by the (n,alpha)
 * reaction it exists to perform, so a B4C blade loses worth over its life and
 * eventually has to be replaced. Hafnium absorbs by transmutation through a
 * chain of isotopes that are themselves absorbers, so a hafnium blade lasts far
 * longer for the same initial worth and costs correspondingly more to build.
 *
 * <p>Both numbers here are hardware nameplate. Nothing in this enum decides
 * anything: {@link #wornWorthScale(double)} reports what a blade of this type is
 * worth after a given exposure, and it is the caller's business what to do about
 * it.
 *
 * <p>Pure Java — no Minecraft imports, so this is usable from the physics-facing
 * side of the mod as well as from block state.
 */
public enum AbsorberType {

    /**
     * Boron carbide, the standard BWR absorber. Full worth when fresh, and its
     * B-10 burns out steadily under flux.
     */
    BORON_CARBIDE("boron_carbide", 1.0, 8.0e4),

    /**
     * Hafnium. Slightly less worth per blade than fresh B4C, but roughly five
     * times the life, because its absorption products keep absorbing.
     */
    HAFNIUM("hafnium", 0.92, 4.0e5);

    private final String serialisedName;
    private final double freshWorthScale;
    private final double exposureForHalfWorthMwd;

    AbsorberType(String serialisedName, double freshWorthScale, double exposureForHalfWorthMwd) {
        this.serialisedName = serialisedName;
        this.freshWorthScale = freshWorthScale;
        this.exposureForHalfWorthMwd = exposureForHalfWorthMwd;
    }

    /** Stable identifier for NBT and for the Lua readout. Never localise this. */
    public String serialisedName() {
        return serialisedName;
    }

    /** Worth of a fresh blade of this type, as a multiple of the core's nominal per-rod worth. */
    public double freshWorthScale() {
        return freshWorthScale;
    }

    /** Exposure at which a blade of this type has lost half its absorber, MWd. */
    public double exposureForHalfWorthMwd() {
        return exposureForHalfWorthMwd;
    }

    /**
     * Worth scale of a blade of this type after the given exposure, as a
     * multiple of the core's nominal per-rod worth. Exponential depletion —
     * absorber is consumed in proportion to how much of it is left.
     *
     * @param exposureMwd accumulated blade exposure, megawatt-days
     */
    public double wornWorthScale(double exposureMwd) {
        double exposure = (exposureMwd > 0.0 && Double.isFinite(exposureMwd)) ? exposureMwd : 0.0;
        return freshWorthScale * Math.pow(0.5, exposure / exposureForHalfWorthMwd);
    }

    /** Look up by {@link #serialisedName()}, falling back to B4C for unknown input. */
    public static AbsorberType fromSerialisedName(String name) {
        for (AbsorberType type : values()) {
            if (type.serialisedName.equals(name)) {
                return type;
            }
        }
        return BORON_CARBIDE;
    }
}
