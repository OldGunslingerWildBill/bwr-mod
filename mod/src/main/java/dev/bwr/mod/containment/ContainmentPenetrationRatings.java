package dev.bwr.mod.containment;

import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Gameplay labels only; the process pressure solver remains BoundaryStress. */
public final class ContainmentPenetrationRatings {
    private ContainmentPenetrationRatings() {}

    public static double containmentPsig() { return ContainmentConfig.PENETRATION_CONTAINMENT_PSIG.get(); }
    public static String nameplate(ContainmentPenetrationBlock.Service service) {
        return BwrText.english(nameplateComponent(service));
    }
    public static Component nameplateComponent(ContainmentPenetrationBlock.Service service) {
        Component process = switch (service) {
            case STEAM -> BwrText.tr(
                    "status.bwr.containment_penetration_ratings.asme_section_iii_inspired_process_design_psig_575",
                    "ASME Section III-inspired | Process design: %s psig @ 575 F; code limit: %s psig",
                    String.format(java.util.Locale.ROOT, "%.0f", ContainmentConfig.STEAM_DESIGN_PSIG.get()),
                    String.format(java.util.Locale.ROOT, "%.0f", ContainmentConfig.STEAM_CODE_LIMIT_PSIG.get()));
            case WATER -> BwrText.tr(
                    "status.bwr.containment_penetration_ratings.asme_section_iii_inspired_process_design_psi",
                    "ASME Section III-inspired | Process design: %s psi",
                    String.format(java.util.Locale.ROOT, "%.0f", ContainmentConfig.WATER_DESIGN_PSI.get()));
            case PASS_THROUGH -> BwrText.tr(
                    "status.bwr.containment_penetration_ratings.asme_class_600_inspired_process_design_psi",
                    "ASME Class 600-inspired | Process design: %s psi",
                    String.format(java.util.Locale.ROOT, "%.0f", ContainmentConfig.PASS_THROUGH_DESIGN_PSI.get()));
            case POWER -> BwrText.tr(
                    "status.bwr.containment_penetration_ratings.ieee_317_inspired_electrical_fe_cap_t",
                    "IEEE 317-inspired electrical | FE cap: %s/t",
                    String.format(java.util.Locale.ROOT, "%d", ContainmentConfig.POWER_PENETRATION_FE_PER_TICK.get()));
            case DATA -> BwrText.tr(
                    "status.bwr.containment_penetration_ratings.ieee_317_inspired_signal_pass_through_no_process",
                    "IEEE 317-inspired signal pass-through | no process pressure");
        };
        return process.copy().append(BwrText.tr(
                "status.bwr.containment_penetration_ratings.containment_psig_simulation",
                " | Containment: %s psig (simulation)",
                String.format(java.util.Locale.ROOT, "%.0f", containmentPsig())));
    }
}
