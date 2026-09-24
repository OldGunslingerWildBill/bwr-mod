package dev.bwr.core.pool;
import dev.bwr.core.thermal.*;

/** Finite secondary inventory and a closed primary pool loop. The circuits exchange only heat. */
public final class RhrHeatExchanger {
    public static final int CAPACITY_KG=12_000;
    public static final double COLD_C=13,HOT_DESIGN_C=24,SECONDARY_FLOW=2400,RATING_MW=100;
    private static final double CP=4.184;
    private final WaterInventory cold=new WaterInventory(CAPACITY_KG,COLD_C),hot=new WaterInventory(CAPACITY_KG,COLD_C);
    private double primaryFlow,secondaryFlow,heatMW,primaryOutC,secondaryOutC;
    public double cold(){return cold.mass();}public double hot(){return hot.mass();}
    public double coldH(){return cold.enthalpy();}public double hotH(){return hot.enthalpy();}
    public double coldTemperatureC(){return cold.temperatureC();}public double hotTemperatureC(){return hot.temperatureC();}
    public double primaryFlow(){return primaryFlow;}public double secondaryFlow(){return secondaryFlow;}
    public double secondaryOutC(){return secondaryFlow>0?secondaryOutC:coldTemperatureC();}
    public double primaryOutC(){return primaryOutC;}public double heatMW(){return heatMW;}
    public double hotEnergyKJ(){return hot.energyKJ()-hot.mass()*Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C);}
    public double fillCold(double kg,boolean simulate){return fillCold(kg,Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C),simulate);}
    public double fillCold(double kg,double h,boolean simulate){return cold.fill(kg,h,simulate);}
    public double drainHot(double kg,boolean simulate){return hot.drain(kg,simulate);}
    public void clearReadouts(){primaryFlow=secondaryFlow=heatMW=primaryOutC=0;}
    public void step(SuppressionPool pool,double flow,double seconds) {
        clearReadouts();if(pool==null||!Double.isFinite(flow)||flow<=0||!Double.isFinite(seconds)||seconds<=0)return;
        double primaryKg=Math.min(flow*seconds,pool.getAvailableSuctionKg());
        double secondaryKg=Math.min(cold.mass(),Math.min(SECONDARY_FLOW*seconds,hot.space()));
        if(primaryKg<=0||secondaryKg<=0)return;
        double inlet=pool.getTemperatureC(),coldC=cold.temperatureC(),inH=cold.enthalpy();
        double requested=Math.min(RATING_MW*seconds,.8*Math.min(primaryKg,secondaryKg)*CP*Math.max(0,inlet-coldC)/1000);
        double ceiling=Saturation.subcooledLiquidEnthalpyKJPerKg(Math.min(inlet,coldC+11));
        requested=Math.min(requested,secondaryKg*Math.max(0,ceiling-inH)/1000);
        double removed=pool.removeHeatMJ(requested,coldC);
        double outH=inH+removed*1000/secondaryKg;
        cold.drain(secondaryKg,false);hot.fill(secondaryKg,outH,false);
        primaryFlow=primaryKg/seconds;secondaryFlow=secondaryKg/seconds;heatMW=removed/seconds;
        primaryOutC=inlet-removed*1000/(primaryKg*CP);secondaryOutC=WaterInventory.temperature(outH);
    }
    public void restore(double coldKg,double hotKg,double energyKJ){
        double h=Saturation.subcooledLiquidEnthalpyKJPerKg(COLD_C);
        restore(coldKg,hotKg,h,hotKg>0&&Double.isFinite(energyKJ)?h+Math.max(0,energyKJ)/hotKg:h);
    }
    public void restore(double coldKg,double hotKg,double coldH,double hotH){cold.restore(coldKg,coldH);hot.restore(hotKg,hotH);clearReadouts();}
}
