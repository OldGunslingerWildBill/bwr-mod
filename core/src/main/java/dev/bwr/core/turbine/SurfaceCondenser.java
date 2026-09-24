package dev.bwr.core.turbine;

import dev.bwr.core.thermal.*;

/** Finite surface condenser. Cooling water and steam exchange energy but never mix. */
public final class SurfaceCondenser {
    public static final double HEAT_REJECTION_MW=2750, COOLING_KG_PER_S=60_000;
    public static final double COLD_C=13, HOT_C=24, CONDENSATE_C=40;
    public static final int COOLING_CAPACITY=1_000_000, CONDENSATE_CAPACITY=200_000;
    /** Effective exhaust volume for this game-scale module; not an OEM sizing parameter. */
    public static final double EXHAUST_VOLUME_M3=4000;
    public final SteamInventory steam=new SteamInventory(10_000);
    private final WaterInventory cold=new WaterInventory(COOLING_CAPACITY,COLD_C),hot=new WaterInventory(COOLING_CAPACITY,HOT_C),condensate=new WaterInventory(CONDENSATE_CAPACITY,CONDENSATE_C);
    private double steamRate,coolingRate,rejectedMW;
    public double cold(){return cold.mass();}public double hot(){return hot.mass();}public double condensate(){return condensate.mass();}
    public double coldC(){return cold.temperatureC();}public double hotC(){return hot.temperatureC();}public double condensateC(){return condensate.temperatureC();}
    public double coldH(){return cold.enthalpy();}public double hotH(){return hot.enthalpy();}public double condensateH(){return condensate.enthalpy();}
    public double steamRate(){return steamRate;}public double coolingRate(){return coolingRate;}public double rejectedMW(){return rejectedMW;}
    public double fillCold(double wanted,boolean simulate){return fillCold(wanted,Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C),simulate);}
    public double fillCold(double wanted,double h,boolean simulate){return cold.fill(wanted,h,simulate);}
    /** Makeup mixes with the finite hotwell inventory, never the circulating-water circuit. */
    public double fillMakeup(double wanted,double h,boolean simulate){return condensate.fill(wanted,h,simulate);}
    public double drainHot(double wanted,boolean simulate){return hot.drain(wanted,simulate);}
    public double drainCondensate(double wanted,boolean simulate){return condensate.drain(wanted,simulate);}
    public double acceptLegacyCondensate(double wanted){return condensate.fill(wanted,Saturation.subcooledLiquidEnthalpyKJPerKg(CONDENSATE_C),false);}
    /** Cooling approach plus finite exhaust inventory; no automatic control or vacuum pump is implied. */
    public double backpressurePsia(){
        double sinkC=Math.max(CONDENSATE_C,coldC()+27);
        double saturation=Saturation.pressurePsiaFromTemperatureCelsius(sinkC);
        double inventoryPressure=steam.mass()*.4615*(sinkC+273.15)/EXHAUST_VOLUME_M3/6.894757;
        return Math.min(3208,Math.max(saturation,inventoryPressure));
    }
    public double vacuumInHg(){return Math.max(0,(14.696-backpressurePsia())*2.03602);}
    public void clearReadouts(){steamRate=coolingRate=rejectedMW=0;}
    public void tick(double seconds){
        clearReadouts();if(!Double.isFinite(seconds)||seconds<=0||steam.mass()<=0)return;
        double liquidC=Math.max(coldC()+27,Saturation.temperatureCelsiusFromPsia(backpressurePsia()));
        double liquidH=Saturation.subcooledLiquidEnthalpyKJPerKg(liquidC);
        double heatPerKg=steam.enthalpy()-liquidH;if(heatPerKg<=0)return;
        double outH=Saturation.subcooledLiquidEnthalpyKJPerKg(Math.min(liquidC-5,coldC()+11));
        double rise=outH-cold.enthalpy();if(rise<=0)return;
        double cooling=Math.min(cold.mass(),Math.min(hot.space(),COOLING_KG_PER_S*seconds));
        double heat=Math.min(HEAT_REJECTION_MW*1000*seconds,cooling*rise);
        double kg=Math.min(steam.mass(),Math.min(condensate.space(),heat/heatPerKg));
        if(kg<=0)return;
        var removed=steam.take(kg);heat=removed.mass()*(removed.enthalpy()-liquidH);cooling=heat/rise;
        cold.drain(cooling,false);hot.fill(cooling,outH,false);condensate.fill(removed.mass(),liquidH,false);
        steamRate=removed.mass()/seconds;coolingRate=cooling/seconds;rejectedMW=heat/seconds/1000;
    }
    public void restore(double coldKg,double hotKg,double condensed){restore(coldKg,hotKg,condensed,Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C),Saturation.subcooledLiquidEnthalpyKJPerKg(HOT_C),Saturation.subcooledLiquidEnthalpyKJPerKg(CONDENSATE_C));}
    public void restore(double coldKg,double hotKg,double condensed,double coldH,double hotH,double condensateH){cold.restore(coldKg,coldH);hot.restore(hotKg,hotH);condensate.restore(condensed,condensateH);clearReadouts();}
}
