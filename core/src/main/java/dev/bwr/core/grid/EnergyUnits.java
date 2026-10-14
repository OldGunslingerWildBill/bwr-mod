package dev.bwr.core.grid;

/** The existing game economy: 13.4 W per FE/t at 20 simulation ticks/s. */
public final class EnergyUnits {
    public static final double WATTS_PER_FE_PER_TICK=13.4;
    public static final double JOULES_PER_FE=.67;
    private EnergyUnits() {}
    public static double mw(double fePerTick){return fePerTick*WATTS_PER_FE_PER_TICK/1e6;}
    public static double fePerTick(double mw){return mw*1e6/WATTS_PER_FE_PER_TICK;}
    public static double mwh(long fe){return fe*(JOULES_PER_FE/3.6e9);}
    public static long add(long a,long b){return Long.MAX_VALUE-a<b?Long.MAX_VALUE:a+b;}
}
