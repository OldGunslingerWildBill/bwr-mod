package dev.bwr.mod.eccs;

import net.minecraft.util.StringRepresentable;

/**
 * Where an emergency pump takes its water from — {@code SPEC.md} section 9.2.
 *
 * <p>The tradeoff is real and it is the reason this is switchable at all:
 *
 * <ul>
 *   <li><b>Suppression pool.</b> A closed loop. Inject pool water, it boils,
 *       the relief valves put it back, and the pool gets hotter every time
 *       round. Unlimited, and self-heating — and once the pool loses its
 *       subcooling it stops condensing steam and starts pressurising
 *       containment, which then takes power away from the turbine-driven
 *       pumps. Everything is connected.</li>
 *   <li><b>Condensate storage tank.</b> Cold, does not heat the pool, and
 *       finite. Roughly twelve hours of RCIC, or under two of HPCI.</li>
 * </ul>
 *
 * <p>"Swap to pool suction when the tank runs low" is a line of Lua somebody
 * has to write. Nothing in this mod writes it for them.
 */
public enum SuctionSource implements StringRepresentable {

    /** The suppression pool. Endless, warm, and it warms further as you use it. */
    SUPPRESSION_POOL("pool"),

    /** The condensate storage tank. Cold and finite. */
    CONDENSATE_TANK("tank");

    private final String name;

    SuctionSource(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Parse a name as written in Lua, defaulting to the pool. */
    public static SuctionSource byName(String name) {
        if (name != null) {
            for (SuctionSource s : values()) {
                if (s.name.equalsIgnoreCase(name) || s.name().equalsIgnoreCase(name)) {
                    return s;
                }
            }
        }
        return SUPPRESSION_POOL;
    }
}
