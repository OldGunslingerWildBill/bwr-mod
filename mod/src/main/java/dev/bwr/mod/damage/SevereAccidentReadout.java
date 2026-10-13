package dev.bwr.mod.damage;

import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.SevereAccident;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text status lines for the controller's INFO readout. Measurements only;
 * nothing here decides what is dangerous, in keeping with the rest of the
 * plant — the alarms are the player's to write.
 */
public final class SevereAccidentReadout {

    private SevereAccidentReadout() {
    }

    public static List<String> statusLines(ReactorCore core) {
        return BwrText.english(statusComponents(core));
    }

    public static List<Component> statusComponents(ReactorCore core) {
        List<Component> out = new ArrayList<>();
        if (core == null) {
            return out;
        }
        if (core.isNaturalCirculationDominant()) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.core_flow_on_natural_circulation_ated",
                    "Core flow on natural circulation: %s%% rated",
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * core.getNaturalCirculationFlowFraction())));
        }

        SevereAccident sa = core.getSevereAccident();
        if (sa.getWorstStage() != SevereAccident.Stage.INTACT) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.core_damage_stage",
                    "Core damage: %s (stage %s)%s",
                    dev.bwr.mod.text.StatusNames.accidentStage(sa.getStage()),
                    String.format(java.util.Locale.ROOT, "%d", sa.getStage().number()),
                    (sa.isPermanentDamage() ? BwrText.tr("status.bwr.severe_accident_readout.permanent", ", permanent") : Component.empty())));
        }
        if (sa.getFailedCladFraction() > 0.0) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.failed_cladding_eleased_f_core_inventory",
                    "Failed cladding %s%%, released %s%% of core inventory",
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * sa.getFailedCladFraction()),
                    String.format(java.util.Locale.ROOT, "%.2f", 100.0 * sa.getReleasedFraction())));
        }
        if (sa.getMeltedFraction() > 0.0) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.core_melted_n_lower_plenum_utside_vessel",
                    "Core melted %s%%, in lower plenum %s%%, outside vessel %s%%",
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * sa.getMeltedFraction()),
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * sa.getLowerPlenumDebrisFraction()),
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * sa.getExVesselFraction())));
        }
        if (sa.getLowerPlenumDebrisFraction() > 0.0) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.lower_plenum_debris_c_lower_head_creep",
                    "Lower plenum debris %s C, lower head creep %s%%",
                    String.format(java.util.Locale.ROOT, "%.0f", sa.getDebrisTemperatureC()),
                    String.format(java.util.Locale.ROOT, "%.0f", 100.0 * sa.getLowerHeadDamage())));
        }
        if (sa.isLowerHeadFailed()) {
            out.add(BwrText.tr("status.bwr.severe_accident_readout.lower_head_failed_concrete_ablated_m", "Lower head FAILED; concrete ablated %s m%s" , String.format(java.util.Locale.ROOT, "%.2f", sa.getAblationDepthM()), (sa.isBasematFailed() ? BwrText.tr("status.bwr.severe_accident_readout.basemat_penetrated", " (basemat penetrated)") : (sa.isCoreConcreteInteractionActive() ? BwrText.tr(
                    "status.bwr.severe_accident_readout.at_mm_h",
                    " at %s mm/h",
                    String.format(java.util.Locale.ROOT, "%.0f", sa.getAblationRateMmPerHour())) : BwrText.tr("status.bwr.severe_accident_readout.corium_cooled", ", corium cooled")))));
        }

        ContainmentAtmosphere ct = core.getContainment();
        if (core.getBoundaryStress().isInterfacingSystemsLoca())
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.rhr_low_pressure_line_ruptured_outside_containment_vessel",
                    "RHR low-pressure line RUPTURED outside containment; vessel inventory bypasses the drywell."));
        out.add(BwrText.tr("status.bwr.severe_accident_readout.containment_h2_2_psig", "Containment: H2 %s%%, O2 %s%%, %s psig%s%s%s" , String.format(java.util.Locale.ROOT, "%.2f", ct.getHydrogenPercent()), String.format(java.util.Locale.ROOT, "%.1f", ct.getOxygenPercent()), String.format(java.util.Locale.ROOT, "%.1f", ct.getPressurePsig()), (ct.isPurgeActive() ? (ct.isInertingCommanded() ? BwrText.tr("status.bwr.severe_accident_readout.nitrogen_purge_running", ", nitrogen purge running") : BwrText.tr("status.bwr.severe_accident_readout.air_purge_running", ", air purge running")) : (ct.isInerted() ? BwrText.tr("status.bwr.severe_accident_readout.inerted", ", inerted") : Component.empty())), (ct.isVentOpen() ? BwrText.tr("status.bwr.severe_accident_readout.vent_open", ", vent OPEN") : Component.empty()), (ct.isFailed() ? BwrText.tr(
                "status.bwr.severe_accident_readout.failed",
                ", FAILED (%s)",
                dev.bwr.mod.text.StatusNames.failureMode(ct.getFailureMode())) : Component.empty())));
        out.add(BwrText.tr(
                "status.bwr.severe_accident_readout.containment_steam_kg_gas_c_drywell_wetwell_psig",
                "Containment steam %s%% (%s kg), gas %s C, drywell %s / wetwell %s psig, spray %s kg/s",
                String.format(java.util.Locale.ROOT, "%.1f", ct.getSteamPercent()),
                String.format(java.util.Locale.ROOT, "%.0f", ct.getSteamKg()),
                String.format(java.util.Locale.ROOT, "%.1f", ct.getGasTemperatureC()),
                String.format(java.util.Locale.ROOT, "%.1f", ct.getDrywellPressurePsig()),
                String.format(java.util.Locale.ROOT, "%.1f", ct.getWetwellPressurePsig()),
                String.format(java.util.Locale.ROOT, "%.1f", ct.getLastSprayFlowKgPerS())));
        if (ct.getBurnCount() > 0) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.hydrogen_burns_kg_burned_peak_psig",
                    "Hydrogen burns: %s, %s kg burned, peak %s psig",
                    String.format(java.util.Locale.ROOT, "%d", ct.getBurnCount()),
                    String.format(java.util.Locale.ROOT, "%.0f", ct.getHydrogenBurnedKg()),
                    String.format(java.util.Locale.ROOT, "%.0f", ct.getPeakPressurePsig())));
        }
        if (ct.getEnvironmentalReleaseFraction() > 0.0 || ct.getPlantContaminationIndex() > 1.0e-6) {
            out.add(BwrText.tr(
                    "status.bwr.severe_accident_readout.release_to_environment_f_core_plant_contamination_index",
                    "Release to environment %s%% of core; plant contamination index %s",
                    String.format(java.util.Locale.ROOT, "%.3f", 100.0 * ct.getEnvironmentalReleaseFraction()),
                    String.format(java.util.Locale.ROOT, "%.2e", ct.getPlantContaminationIndex())));
        }
        return out;
    }
}
