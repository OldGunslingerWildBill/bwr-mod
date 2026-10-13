package dev.bwr.mod.text;

import net.minecraft.network.chat.Component;

/** Localized hardware and accident states; serialized identifiers stay unchanged. */
public final class StatusNames {
    private StatusNames() { }

    public static Component boundary(dev.bwr.core.boundary.BoundaryComponent value) {
        return switch (value) {
            case MAIN_STEAM_LINE -> BwrText.tr("status.bwr.state.boundary.main_steam_line", "Main steam line");
            case FEEDWATER_LINE -> BwrText.tr("status.bwr.state.boundary.feedwater_line", "Feedwater line");
            case RECIRCULATION_LINE -> BwrText.tr("status.bwr.state.boundary.recirculation_line", "Recirculation line");
            case REACTOR_VESSEL_HEAD -> BwrText.tr("status.bwr.state.boundary.reactor_vessel_head", "Reactor vessel head");
        };
    }

    public static Component boundaryLower(dev.bwr.core.boundary.BoundaryComponent value) {
        return switch (value) {
            case MAIN_STEAM_LINE -> BwrText.tr("status.bwr.state.boundarylower.main_steam_line", "main steam line");
            case FEEDWATER_LINE -> BwrText.tr("status.bwr.state.boundarylower.feedwater_line", "feedwater line");
            case RECIRCULATION_LINE -> BwrText.tr("status.bwr.state.boundarylower.recirculation_line", "recirculation line");
            case REACTOR_VESSEL_HEAD -> BwrText.tr("status.bwr.state.boundarylower.reactor_vessel_head", "reactor vessel head");
        };
    }

    public static Component accidentStage(dev.bwr.core.accident.SevereAccident.Stage value) {
        return switch (value) {
            case INTACT -> BwrText.tr("status.bwr.state.accidentstage.intact", "Intact");
            case CORE_UNCOVERY -> BwrText.tr("status.bwr.state.accidentstage.core_uncovery", "Core uncovery");
            case CLADDING_HEATUP -> BwrText.tr("status.bwr.state.accidentstage.cladding_heatup", "Cladding heatup");
            case ZIRCONIUM_REACTION -> BwrText.tr("status.bwr.state.accidentstage.zirconium_water_reaction", "Zirconium-water reaction");
            case CLADDING_FAILURE -> BwrText.tr("status.bwr.state.accidentstage.cladding_failure", "Cladding failure");
            case FUEL_MELT -> BwrText.tr("status.bwr.state.accidentstage.fuel_melt_and_relocation", "Fuel melt and relocation");
            case LOWER_HEAD_FAILURE -> BwrText.tr("status.bwr.state.accidentstage.lower_head_failure", "Lower head failure");
            case CORE_CONCRETE_INTERACTION -> BwrText.tr("status.bwr.state.accidentstage.core_concrete_interaction", "Core-concrete interaction");
        };
    }

    public static Component failureMode(dev.bwr.core.accident.ContainmentAtmosphere.FailureMode value) {
        return switch (value) {
            case NONE -> BwrText.tr("status.bwr.state.failuremode.none", "none");
            case OVERPRESSURE -> BwrText.tr("status.bwr.state.failuremode.overpressure", "overpressure");
            case DETONATION -> BwrText.tr("status.bwr.state.failuremode.detonation", "detonation");
            case BASEMAT_MELT_THROUGH -> BwrText.tr("status.bwr.state.failuremode.basemat_melt_through", "basemat melt through");
        };
    }

    public static Component vessel(dev.bwr.mod.reactor.VesselState value) {
        return switch (value) {
            case OPERATING -> BwrText.tr("status.bwr.state.vessel.operating", "operating");
            case SHUTDOWN -> BwrText.tr("status.bwr.state.vessel.shutdown", "shutdown");
            case REFUELING -> BwrText.tr("status.bwr.state.vessel.refueling", "refueling");
        };
    }

    public static Component penetration(dev.bwr.mod.containment.ContainmentPenetrationBlock.Service value) {
        return switch (value) {
            case STEAM -> BwrText.tr("status.bwr.state.penetration.steam", "STEAM");
            case WATER -> BwrText.tr("status.bwr.state.penetration.water", "WATER");
            case POWER -> BwrText.tr("status.bwr.state.penetration.power", "POWER");
            case DATA -> BwrText.tr("status.bwr.state.penetration.data", "DATA");
            case PASS_THROUGH -> BwrText.tr("status.bwr.state.penetration.pass_through", "PASS THROUGH");
        };
    }
}
