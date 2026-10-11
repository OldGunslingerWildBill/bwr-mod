package dev.bwr.mod.containment;

import java.util.Locale;

/** Gameplay labels only; the process pressure solver remains BoundaryStress. */
public final class ContainmentPenetrationRatings {
    private ContainmentPenetrationRatings() {}

    public static double containmentPsig() { return ContainmentConfig.PENETRATION_CONTAINMENT_PSIG.get(); }
    public static String nameplate(ContainmentPenetrationBlock.Service service) {
        String process = switch (service) {
            case STEAM -> String.format(Locale.ROOT,
                    "ASME Section III-inspired | Process design: %.0f psig @ 575 F; code limit: %.0f psig",
                    ContainmentConfig.STEAM_DESIGN_PSIG.get(), ContainmentConfig.STEAM_CODE_LIMIT_PSIG.get());
            case WATER -> String.format(Locale.ROOT,
                    "ASME Section III-inspired | Process design: %.0f psi", ContainmentConfig.WATER_DESIGN_PSI.get());
            case PASS_THROUGH -> String.format(Locale.ROOT,
                    "ASME Class 600-inspired | Process design: %.0f psi", ContainmentConfig.PASS_THROUGH_DESIGN_PSI.get());
            case POWER -> String.format(Locale.ROOT,
                    "IEEE 317-inspired electrical | FE cap: %d/t", ContainmentConfig.POWER_PENETRATION_FE_PER_TICK.get());
            case DATA -> "IEEE 317-inspired signal pass-through | no process pressure";
        };
        return process + String.format(Locale.ROOT, " | Containment: %.0f psig (simulation)", containmentPsig());
    }
}
