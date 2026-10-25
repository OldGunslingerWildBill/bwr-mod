package dev.bwr.mod.signal.adapters;

import dev.bwr.core.chemistry.FilterDemineralizer;
import dev.bwr.mod.rwcu.RwcuDeminBlockEntity;
import dev.bwr.mod.signal.Schema;

/**
 * The RWCU stainless steel nanofilter: what its element holds, how much of its life is used, the pressure drop across it, the
 * water through it and the activity it carries. Read only: changing the element is a player's action with an item, and the
 * pump (not in this table) is what starts the water.
 */
final class RwcuSignals {
    private RwcuSignals() {}

    static final Schema<RwcuDeminBlockEntity> NANOFILTER = new Schema<RwcuDeminBlockEntity>()
            .number("element_used", "Element used", "%", 0, 100, be -> 100.0 * be.bed().exhaustion())
            .number("crud_held", "Crud held", "g", 0, FilterDemineralizer.CRUD_CAPACITY_G, be -> be.bed().crudLoadG())
            .number("ions_held", "Dissolved ions held", "g", 0, FilterDemineralizer.ION_CAPACITY_G, be -> be.bed().ionLoadG())
            .number("pressure_drop", "Pressure drop", "psi", 0, FilterDemineralizer.DP_SPENT_PSI * 1.5,
                    be -> be.bed().pressureDropPsi(be.flowKgPerS()))
            .number("flow", "Water through the element", "kg/s", 0, 100, RwcuDeminBlockEntity::flowKgPerS)
            .number("water_temperature", "Water temperature", "degC", 0, 350, RwcuDeminBlockEntity::inletC)
            .number("activity", "Radioactivity held", "Bq", 0, 1.0e15, be -> be.bed().activityBq())
            .number("gamma_power", "Gamma power", "W", 0, 100, be -> be.bed().gammaWatts())
            .bool("element_present", "Element fitted", be -> be.bed().bedPresent())
            .bool("spent", "Element spent", be -> be.bed().bedPresent() && be.bed().exhaustion() >= 1.0)
            .bool("filtering", "Filtering", RwcuDeminBlockEntity::flowing);
}
