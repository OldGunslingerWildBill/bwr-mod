package dev.bwr.mod.eccs;

/** Physical connection roles. A flange does not join steam and water circuits. */
public enum AssemblyPort {
    STEAM_INLET, STEAM_EXHAUST, WATER_SUCTION, WATER_DISCHARGE;

    public boolean isSteam() {
        return this == STEAM_INLET || this == STEAM_EXHAUST;
    }
}
