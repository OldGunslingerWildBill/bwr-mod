package dev.bwr.mod.damage;

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
        List<String> out = new ArrayList<>();
        if (core == null) {
            return out;
        }
        if (core.isNaturalCirculationDominant()) {
            out.add(String.format(Locale.ROOT, "Core flow on natural circulation: %.1f%% rated",
                    100.0 * core.getNaturalCirculationFlowFraction()));
        }

        SevereAccident sa = core.getSevereAccident();
        if (sa.getWorstStage() != SevereAccident.Stage.INTACT) {
            out.add(String.format(Locale.ROOT, "Core damage: %s (stage %d)%s",
                    sa.getStage().displayName(), sa.getStage().number(),
                    sa.isPermanentDamage() ? ", permanent" : ""));
        }
        if (sa.getFailedCladFraction() > 0.0) {
            out.add(String.format(Locale.ROOT, "Failed cladding %.1f%%, released %.2f%% of core inventory",
                    100.0 * sa.getFailedCladFraction(), 100.0 * sa.getReleasedFraction()));
        }
        if (sa.getMeltedFraction() > 0.0) {
            out.add(String.format(Locale.ROOT, "Core melted %.1f%%, in lower plenum %.1f%%, outside vessel %.1f%%",
                    100.0 * sa.getMeltedFraction(), 100.0 * sa.getLowerPlenumDebrisFraction(),
                    100.0 * sa.getExVesselFraction()));
        }
        if (sa.getLowerPlenumDebrisFraction() > 0.0) {
            out.add(String.format(Locale.ROOT, "Lower plenum debris %.0f C, lower head creep %.0f%%",
                    sa.getDebrisTemperatureC(), 100.0 * sa.getLowerHeadDamage()));
        }
        if (sa.isLowerHeadFailed()) {
            out.add(String.format(Locale.ROOT, "Lower head FAILED; concrete ablated %.2f m%s",
                    sa.getAblationDepthM(), sa.isBasematFailed() ? " (basemat penetrated)"
                            : sa.isCoreConcreteInteractionActive()
                            ? String.format(Locale.ROOT, " at %.0f mm/h", sa.getAblationRateMmPerHour())
                            : ", corium cooled"));
        }

        ContainmentAtmosphere ct = core.getContainment();
        out.add(String.format(Locale.ROOT, "Containment: H2 %.2f%%, O2 %.1f%%, %.1f psig%s%s%s",
                ct.getHydrogenPercent(), ct.getOxygenPercent(), ct.getPressurePsig(),
                ct.isPurgeActive() ? (ct.isInertingCommanded() ? ", nitrogen purge running" : ", air purge running")
                        : ct.isInerted() ? ", inerted" : "",
                ct.isVentOpen() ? ", vent OPEN" : "",
                ct.isFailed() ? ", FAILED (" + ct.getFailureMode().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")" : ""));
        out.add(String.format(Locale.ROOT,
                "Containment steam %.1f%% (%.0f kg), gas %.1f C, drywell/wetwell %.1f psig, spray %.1f kg/s",
                ct.getSteamPercent(), ct.getSteamKg(), ct.getGasTemperatureC(),
                ct.getPressurePsig(), ct.getLastSprayFlowKgPerS()));
        if (ct.getBurnCount() > 0) {
            out.add(String.format(Locale.ROOT, "Hydrogen burns: %d, %.0f kg burned, peak %.0f psig",
                    ct.getBurnCount(), ct.getHydrogenBurnedKg(), ct.getPeakPressurePsig()));
        }
        if (ct.getEnvironmentalReleaseFraction() > 0.0 || ct.getPlantContaminationIndex() > 1.0e-6) {
            out.add(String.format(Locale.ROOT, "Release to environment %.3f%% of core; plant contamination index %.2e",
                    100.0 * ct.getEnvironmentalReleaseFraction(), ct.getPlantContaminationIndex()));
        }
        return out;
    }
}
