package dev.bwr.core.turbine;

import dev.bwr.core.thermal.Saturation;

/** Finite design-point cooling hardware. Port roles carry the hot/cold meaning;
 * ordinary Minecraft water does not yet transport temperature or chemistry. */
public final class CoolingWaterUnit {
    public enum Design {
        NATURAL("natural_draft_tower",60_000,0,1_000_000,true),
        MECHANICAL("mechanical_draft_tower",10_000,600_000,200_000,true),
        CIRCULATING("circulating_water_pump",30_000,11_000_000,300_000,false),
        MAKEUP("makeup_water_pump",1_500,600_000,15_000,false),
        INTAKE("screened_water_intake",6_000,0,6_000,false);
        public final String id;public final double flow,watts;public final int capacity;public final boolean tower;
        Design(String id,double flow,double watts,int capacity,boolean tower){this.id=id;this.flow=flow;this.watts=watts;this.capacity=capacity;this.tower=tower;}
    }
    /** Initial gameplay loss: evaporation and blowdown together, requiring makeup. */
    public static final double LOSS_FRACTION=.02;
    public final Design design;
    private double input,output,flow,loss,heat,totalLoss;
    public CoolingWaterUnit(Design d){design=d;}
    public double input(){return input;}public double output(){return output;}
    public double flow(){return flow;}public double loss(){return loss;}public double heatMW(){return heat;}
    public double totalLoss(){return totalLoss;}
    private static double amount(double wanted,double room){return Double.isFinite(wanted)?Math.max(0,Math.min(wanted,room)):0;}
    public double fillInput(double wanted,boolean simulate){double n=amount(wanted,design.capacity-input);if(!simulate)input+=n;return n;}
    public double fillMakeup(double wanted,boolean simulate){double n=amount(wanted,design.capacity-output);if(!simulate)output+=n;return n;}
    public double drain(double wanted,boolean simulate){double n=amount(wanted,output);if(!simulate)output-=n;return n;}
    public void clearReadouts(){flow=loss=heat=0;}
    public void tick(double seconds,double speed){
        clearReadouts();if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(speed))return;
        double retained=design.tower?1-LOSS_FRACTION:1;
        double moved=Math.min(input,Math.min(design.flow*seconds*Math.clamp(speed,0,1),(design.capacity-output)/retained));
        if(moved<=0)return;input-=moved;output+=moved*retained;flow=moved/seconds;
        if(design.tower){loss=moved*LOSS_FRACTION/seconds;totalLoss+=moved*LOSS_FRACTION;
            heat=flow*(Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.HOT_C)-Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.COLD_C))/1000;}
    }
    public void restore(double in,double out,double lost){input=amount(in,design.capacity);output=amount(out,design.capacity);totalLoss=amount(lost,Double.MAX_VALUE);clearReadouts();}
}
