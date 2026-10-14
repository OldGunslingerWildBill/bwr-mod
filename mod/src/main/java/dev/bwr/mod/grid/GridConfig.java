package dev.bwr.mod.grid;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class GridConfig {
    private static final ModConfigSpec.Builder B=new ModConfigSpec.Builder();
    public static final ModConfigSpec.DoubleValue MAXIMUM_MW=B.comment("Maximum external FE demand per grid tie; game calibration, no automatic breaker action.")
            .defineInRange("gridTieMaxMW",1500.0,0.0,48000.0);
    public static final ModConfigSpec SPEC=B.build();
    private GridConfig() {}
    public static double maximumMW(){return MAXIMUM_MW.get();}
}
