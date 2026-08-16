package dev.bwr.mod.reactor;

import net.minecraft.util.StringRepresentable;

/**
 * Whether the vessel head is on, and therefore whether the vessel can hold
 * pressure at all — {@code SPEC.md} section 10.
 *
 * <p>This is a property of the hardware, not a mode the reactor protection
 * system selects. Nothing here decides whether it is safe to move between
 * states; {@link #canHoldPressure()} reports a physical fact and the refuelling
 * GUI refuses to open the head under pressure because you physically cannot
 * unbolt a pressurised vessel, not because a setpoint says no.
 *
 * <h2>The head is wired to the physics, and this is where it enters</h2>
 * {@link #canHoldPressure()} is read by
 * {@code ReactorControllerBlockEntity.applyVesselHeadDischarge}, which pushes a
 * steam discharge into the core every tick while the head is off. It had no
 * consumer at all for a long time, and the consequence was that removing the
 * head was a refuelling gate and nothing else: a vessel with no head on it went
 * on holding 1025 psig indefinitely, and the pressure, void and reactivity loop
 * behaved exactly as it does with the head bolted down. Open the head at
 * 20 psig, close the screen, withdraw rods and start up, and the plant
 * pressurised normally through an opening the size of the vessel.
 *
 * <p>It was left unwired on the grounds that the coupling had nowhere to land,
 * and that turned out to be wrong. An open head is not a clamp on dome
 * pressure — that would be the physics module's business and getting it wrong
 * would be worse than the gap — it is a very large flow area to containment,
 * and {@code ReactorCore.setSteamLeakKgPerS} is precisely a discharge path out
 * of the steam space, additive to whatever the boundary damage model is already
 * venting. No core-side actuator was required. See
 * {@code ReactorControllerBlockEntity.OPEN_HEAD_DISCHARGE_KG_PER_S_PER_PSI} for
 * how the size of the hole is arrived at.
 *
 * <p>{@link #OPERATING} is still unreachable: the panel's head toggle only ever
 * sends {@link #REFUELING} or {@link #SHUTDOWN}, and this class draws the same
 * physical conclusion from OPERATING as from SHUTDOWN, so nothing is lost by
 * the panel not distinguishing them. It is kept because it is the state that
 * would carry the distinction, and removing it would have to be undone.
 */
public enum VesselState implements StringRepresentable {

    /** Head bolted, vessel intact, pressure boundary closed. */
    OPERATING("operating", true),

    /**
     * Head bolted but the plant is cold and depressurised. Distinct from
     * OPERATING only in that the refuelling sequence may proceed from here.
     */
    SHUTDOWN("shutdown", true),

    /**
     * Head removed. The vessel is open to containment and cannot hold pressure.
     * This is the only state in which the core is visible, and therefore the one
     * occasion the rod and assembly models are rendered at all.
     */
    REFUELING("refueling", false);

    private final String name;
    private final boolean holdsPressure;

    VesselState(String name, boolean holdsPressure) {
        this.name = name;
        this.holdsPressure = holdsPressure;
    }

    /** False when the head is off: the vessel simply vents to containment. */
    public boolean canHoldPressure() {
        return holdsPressure;
    }

    /** Rods and assemblies are rendered only when someone can actually see them. */
    public boolean rendersInternals() {
        return this == REFUELING;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static VesselState byName(String s) {
        for (VesselState v : values()) {
            if (v.name.equals(s)) {
                return v;
            }
        }
        return SHUTDOWN;
    }
}
