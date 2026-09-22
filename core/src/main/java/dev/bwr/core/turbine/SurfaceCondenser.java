package dev.bwr.core.turbine;

import dev.bwr.core.thermal.Saturation;

/** Finite first-pass surface condenser. Cooling water never mixes with condensate.
 * Temperatures are design-point assumptions until plant water carries enthalpy. */
public final class SurfaceCondenser {
    public static final double HEAT_REJECTION_MW=2750, COOLING_KG_PER_S=60_000;
    public static final double COLD_C=13, HOT_C=24, CONDENSATE_C=40;
    public static final int COOLING_CAPACITY=1_000_000, CONDENSATE_CAPACITY=200_000;
    public final SteamInventory steam=new SteamInventory(10_000);
    private double cold,hot,condensate;
    private double steamRate,coolingRate,rejectedMW;
    public double cold(){return cold;}
    public double hot(){return hot;}
    public double condensate(){return condensate;}
    public double steamRate(){return steamRate;}
    public double coolingRate(){return coolingRate;}
    public double rejectedMW(){return rejectedMW;}
    public double fillCold(double wanted,boolean simulate){double n=amount(wanted,COOLING_CAPACITY-cold);if(!simulate)cold+=n;return n;}
    public double drainHot(double wanted,boolean simulate){double n=amount(wanted,hot);if(!simulate)hot-=n;return n;}
    public double drainCondensate(double wanted,boolean simulate){double n=amount(wanted,condensate);if(!simulate)condensate-=n;return n;}
    /** Migration of water already condensed by older LP saves; this never condenses steam. */
    public double acceptLegacyCondensate(double wanted){double n=amount(wanted,CONDENSATE_CAPACITY-condensate);condensate+=n;return n;}
    private static double amount(double wanted,double available){return Double.isFinite(wanted)?Math.max(0,Math.min(wanted,available)):0;}
    public void clearReadouts(){steamRate=coolingRate=rejectedMW=0;}
    public void tick(double seconds){
        clearReadouts();if(!Double.isFinite(seconds)||seconds<=0||steam.mass()<=0)return;
        double liquidH=Saturation.subcooledLiquidEnthalpyKJPerKg(CONDENSATE_C);
        double heatPerKg=steam.enthalpy()-liquidH;
        if(heatPerKg<=0)return;
        double waterRise=Saturation.subcooledLiquidEnthalpyKJPerKg(HOT_C)-Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C);
        double cooling=Math.max(0,Math.min(Math.min(cold,COOLING_CAPACITY-hot),COOLING_KG_PER_S*seconds));
        double heat=Math.min(HEAT_REJECTION_MW*1000*seconds,cooling*waterRise);
        double kg=Math.min(steam.mass(),Math.min(CONDENSATE_CAPACITY-condensate,heat/heatPerKg));
        if(kg<=0)return;
        var packet=steam.take(kg);kg=packet.mass();heat=kg*heatPerKg;cooling=heat/waterRise;
        cold=Math.max(0,cold-cooling);hot=Math.min(COOLING_CAPACITY,hot+cooling);condensate+=kg;
        steamRate=kg/seconds;coolingRate=cooling/seconds;rejectedMW=heat/seconds/1000;
    }
    public void restore(double coldKg,double hotKg,double condensateKg){
        cold=amount(coldKg,COOLING_CAPACITY);hot=amount(hotKg,COOLING_CAPACITY);condensate=amount(condensateKg,CONDENSATE_CAPACITY);clearReadouts();
    }
}
