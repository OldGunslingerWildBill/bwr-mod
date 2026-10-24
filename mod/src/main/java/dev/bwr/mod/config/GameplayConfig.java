package dev.bwr.mod.config;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.ContainmentWallStrength;
import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.fuel.CoreInsert;
import dev.bwr.mod.fuel.SpecialtyRodItem;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world gameplay calibration. Defaults reproduce the pre-L1 behavior. */
public final class GameplayConfig {
    private GameplayConfig() {}
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue FAILURE_HAZARD_PER_SECOND = BUILDER
            .comment("Random pressure-boundary failure hazard per second per excess stress and pressure; zero disables only random rupture")
            .defineInRange("failureHazardPerSecond", BoundaryStress.HAZARD_PER_SECOND, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue ACCIDENT_PROGRESSION_MULTIPLIER = BUILDER
            .comment("Multiplier for relocation, lower-head creep, basemat attack, and ex-vessel release rates; 1 is the original calibration")
            .defineInRange("accidentProgressionMultiplier", 1.0, 0.01, 100.0);
    public static final ModConfigSpec.DoubleValue CONTAMINATION_HALF_LIFE_SECONDS = BUILDER
            .comment("Half-life in simulated seconds of plant and site contamination hazard indices")
            .defineInRange("contaminationHalfLifeSeconds", ContainmentAtmosphere.CONTAMINATION_HALF_LIFE_S, 60.0, 31_536_000.0);
    public static final ModConfigSpec.DoubleValue CONTAMINATION_DOSE_MULTIPLIER = BUILDER
            .comment("Multiplier on the hazard used for nearby player status effects; zero disables dose effects without erasing contamination")
            .defineInRange("contaminationDoseMultiplier", 1.0, 0.0, 100.0);
    public static final ModConfigSpec.DoubleValue ANTIMONY_EXPOSURE_HOURS = BUILDER
            .comment("Rated-flux hours to activate an antimony-beryllium source")
            .defineInRange("antimonyExposureHours", 48.0, 0.01, 100_000.0);
    public static final ModConfigSpec.DoubleValue COBALT_EXPOSURE_HOURS = BUILDER
            .comment("Rated-flux hours to complete a cobalt target")
            .defineInRange("cobaltExposureHours", 72.0, 0.01, 100_000.0);
    public static final ModConfigSpec.DoubleValue TRITIUM_EXPOSURE_HOURS = BUILDER
            .comment("Rated-flux hours to complete a tritium target")
            .defineInRange("tritiumExposureHours", 168.0, 0.01, 100_000.0);
    public static final ModConfigSpec.DoubleValue SILICON_EXPOSURE_HOURS = BUILDER
            .comment("Rated-flux hours to complete a silicon target")
            .defineInRange("siliconExposureHours", 24.0, 0.01, 100_000.0);
    public static final ModConfigSpec.DoubleValue WALL_STRENGTH_K = BUILDER
            .comment("Containment wall psig contribution coefficient multiplied by square root of block blast resistance")
            .defineInRange("wallStrengthK", ContainmentWallStrength.DEFAULT_K, 0.0, 10_000.0);
    public static final ModConfigSpec.DoubleValue WALL_STRENGTH_CAP_PSIG = BUILDER
            .comment("Maximum psig contribution of one wall block")
            .defineInRange("wallStrengthCapPsig", ContainmentWallStrength.DEFAULT_PER_BLOCK_CAP_PSIG, 0.0, 100_000.0);
    public static final ModConfigSpec.IntValue TRITIUM_SAMPLES_PER_ROD = BUILDER
            .comment("Sealed tritium samples harvested from one completed target rod")
            .defineInRange("tritiumSamplesPerRod", SpecialtyRodItem.TRITIUM_SAMPLES_PER_ROD, 1, 100_000);

    public static final ModConfigSpec.DoubleValue ROD_COLLET_FAILURE_HAZARD = BUILDER
            .comment("Optional per-rod collet failure hazard per simulated second; 0 disables random drift (default). Example rare rate: 0.000001")
            .defineInRange("rodColletFailureHazardPerSecond", 0.0, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue ROD_DRIFT_RATE = BUILDER
            .comment("Outward travel of an unlatched rod in notch spans per second; gameplay calibration")
            .defineInRange("rodDriftNotchesPerSecond", 0.1, 0.001, 24.0);

    public static final ModConfigSpec.DoubleValue REPROCESSING_MAX_DECAY_HEAT_KW = BUILDER
            .comment("Highest decay heat, in kW per assembly, at which the reprocessing plant will start on a spent assembly. "
                    + "A fresh discharge is 100 to 300 kW; the default is reached in about one to two hours of cooling. "
                    + "Zero turns the check off. Assemblies with no recorded discharge time count as long-cooled")
            .defineInRange("reprocessingMaxDecayHeatKw", 50.0, 0.0, 100_000.0);
    public static final ModConfigSpec.BooleanValue URANIUM_ORE_GENERATION = BUILDER
            .comment("Generate this mod's uranium ore (small blobs and the large veins) in newly generated chunks. Never touches chunks that already exist. "
                    + "Read when the server starts: changing it needs a restart to take effect on the biome lists (the vein feature itself re-reads it for every chunk). "
                    + "To turn off Mekanism's uranium instead, use Mekanism's own world config.")
            .define("uraniumOreGeneration", true);

    public static final ModConfigSpec.BooleanValue INSTANT_REFUELLING = BUILDER
            .comment("Old behaviour: load, unload and swap fuel instantly by clicking the refuelling map on the reactor panel. "
                    + "Default off: fuel moves on the refuelling bridge (rails, a console, the cattle chute and the spent fuel pool; see REFUELLING-BRIDGE.md). "
                    + "Existing worlds keep their fuel either way; with this off the map still shows the core but its buttons point you to the bridge")
            .define("instantRefuelling", false);

    public static final ModConfigSpec.BooleanValue INSTANT_HEAD_REMOVAL = BUILDER
            .comment("Old behaviour: the reactor panel's HEAD OFF / head on button lifts and seats the vessel head at once, with no stud tensioner and no crane. "
                    + "Default off: the head's studs are detensioned with a Stud Tensioner and the head is lifted onto a Head Stand by the reactor building crane "
                    + "(see BUILDING-CRANE.md); the button then explains the procedure. A head that is already off in an existing world stays off either way")
            .define("instantHeadRemoval", false);

    public static final ModConfigSpec.BooleanValue BRIDGE_LIFTS_INTERNALS = BUILDER
            .comment("Legacy (the alpha.53 dryer and separator lifts): the refuelling bridge can also lift the steam dryer and the separator assembly out of the open "
                    + "vessel onto Equipment Storage Pads. Current behaviour is that the reactor building crane does those lifts and the refuelling bridge moves fuel only; "
                    + "turn this off to get that (the bridge then refuses the lifts and points to the crane)")
            .define("bridgeLiftsInternals", true);

    public static final ModConfigSpec.BooleanValue GIVE_GUIDE_ON_FIRST_JOIN = BUILDER
            .comment("Give each player one Operator's Guide (the in-game guide book) the first time they join this world, once only. "
                    + "A player who has had one is never given another, whether or not they still have it; craft it with a book and a nameplate. "
                    + "Off: nobody is given one, and players who have not had one get theirs when it is turned back on (see GUIDE.md)")
            .define("giveGuideOnFirstJoin", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double failureHazardPerSecond() { return read(FAILURE_HAZARD_PER_SECOND); }
    public static double accidentProgressionMultiplier() { return read(ACCIDENT_PROGRESSION_MULTIPLIER); }
    public static double contaminationHalfLifeSeconds() { return read(CONTAMINATION_HALF_LIFE_SECONDS); }
    public static double contaminationDoseMultiplier() { return read(CONTAMINATION_DOSE_MULTIPLIER); }
    public static double wallStrengthK() { return read(WALL_STRENGTH_K); }
    public static double wallStrengthCapPsig() { return read(WALL_STRENGTH_CAP_PSIG); }
    public static double reprocessingMaxDecayHeatKw() { return read(REPROCESSING_MAX_DECAY_HEAT_KW); }
    /** Whether the refuelling map may move fuel instantly. False until the server config says otherwise (and before it is loaded). */
    public static boolean instantRefuelling() {
        return SPEC.isLoaded() ? INSTANT_REFUELLING.get() : INSTANT_REFUELLING.getDefault();
    }

    /** Whether the reactor panel's head button lifts and seats the head at once (no stud tensioner, no crane). False until the server config says otherwise. */
    public static boolean instantHeadRemoval() {
        return SPEC.isLoaded() ? INSTANT_HEAD_REMOVAL.get() : INSTANT_HEAD_REMOVAL.getDefault();
    }

    /** Whether the refuelling bridge still lifts the dryer and the separator assembly (the crane does it when this is off). */
    public static boolean bridgeLiftsInternals() {
        return SPEC.isLoaded() ? BRIDGE_LIFTS_INTERNALS.get() : BRIDGE_LIFTS_INTERNALS.getDefault();
    }

    /** Whether a player's first join gives them the guide book. True until the server config says otherwise (and before it is loaded). */
    public static boolean giveGuideOnFirstJoin() {
        return SPEC.isLoaded() ? GIVE_GUIDE_ON_FIRST_JOIN.get() : GIVE_GUIDE_ON_FIRST_JOIN.getDefault();
    }

    public static int tritiumSamplesPerRod() {
        return SPEC.isLoaded() ? TRITIUM_SAMPLES_PER_ROD.get() : TRITIUM_SAMPLES_PER_ROD.getDefault();
    }

    /** Whether new chunks get uranium ore. True until the server config says otherwise (and before it is loaded). */
    public static boolean uraniumOreGeneration() {
        return SPEC.isLoaded() ? URANIUM_ORE_GENERATION.get() : URANIUM_ORE_GENERATION.getDefault();
    }

    private static double read(ModConfigSpec.DoubleValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    /** Refreshes global insert ratings when a server config is loaded or reloaded. */
    public static void refreshIrradiationTimes() {
        CoreInsert.Kind.ANTIMONY_BERYLLIUM_SOURCE.setExposureSeconds(read(ANTIMONY_EXPOSURE_HOURS) * 3600);
        CoreInsert.Kind.COBALT_TARGET.setExposureSeconds(read(COBALT_EXPOSURE_HOURS) * 3600);
        CoreInsert.Kind.TRITIUM_TARGET.setExposureSeconds(read(TRITIUM_EXPOSURE_HOURS) * 3600);
        CoreInsert.Kind.SILICON_TARGET.setExposureSeconds(read(SILICON_EXPOSURE_HOURS) * 3600);
    }

    /** Config is applied to already-loaded cores, including after hot reload. */
    public static void applyTo(ReactorCore core) {
        if (core == null) return;
        core.getBoundaryStress().setFailureHazardPerSecond(failureHazardPerSecond());
        core.getConfig().rodColletFailureHazardPerSecond = read(ROD_COLLET_FAILURE_HAZARD);
        core.getConfig().rodDriftNotchesPerSecond = read(ROD_DRIFT_RATE);
        core.getSevereAccident().setProgressionMultiplier(accidentProgressionMultiplier());
        core.getContainment().setContaminationHalfLifeSeconds(contaminationHalfLifeSeconds());
        refreshIrradiationTimes();
    }
}
