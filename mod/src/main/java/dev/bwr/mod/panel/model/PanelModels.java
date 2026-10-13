package dev.bwr.mod.panel.model;

import java.util.Map;

/**
 * The client's registry of loaded panel models, by model id (the file name without {@code .json}). Replaced
 * wholesale by {@code PanelModelLoader} on every resource reload; reads never see a half-built map because the
 * maps are immutable and swapped through a volatile field. {@link #generation()} changes on every swap so
 * cached meshes know to rebuild.
 *
 * <p>Fixtures are a development aid: models registered by the client check scene (or a test) that stand in for
 * ids no resource pack provides yet. A real loaded model always wins over a fixture of the same id.
 */
public final class PanelModels {
    private PanelModels() {
    }

    private static volatile Map<String, BakedPanelModel> loaded = Map.of();
    private static volatile Map<String, BakedPanelModel> fixtures = Map.of();
    private static volatile int generation;

    /** The model with this id, or null when none is loaded. */
    public static BakedPanelModel get(String id) {
        BakedPanelModel model = loaded.get(id);
        return model != null ? model : fixtures.get(id);
    }

    public static boolean has(String id) {
        return get(id) != null;
    }

    /** Changes whenever the set of models does. */
    public static int generation() {
        return generation;
    }

    public static int loadedCount() {
        return loaded.size();
    }

    /** Replaces everything loaded from resources. */
    public static synchronized void replace(Map<String, BakedPanelModel> models) {
        loaded = Map.copyOf(models);
        generation++;
    }

    /** Replaces the development fixtures. */
    public static synchronized void setFixtures(Map<String, BakedPanelModel> models) {
        fixtures = Map.copyOf(models);
        generation++;
    }
}
