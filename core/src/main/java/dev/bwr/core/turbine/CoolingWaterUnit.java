package dev.bwr.core.turbine;

import dev.bwr.core.thermal.Saturation;

/** Finite cooling segments with conserved water enthalpy. Chemistry is not simulated. */
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
    private final dev.bwr.core.thermal.WaterInventory input,output;
    private double flow,loss,heat,totalLoss;
    public CoolingWaterUnit(Design d){design=d;input=new dev.bwr.core.thermal.WaterInventory(d.capacity,d.tower?24:13);output=new dev.bwr.core.thermal.WaterInventory(d.capacity,13);}
    public double input(){return input.mass();}public double output(){return output.mass();}
    public double inputC(){return input.temperatureC();}public double outputC(){return output.temperatureC();}
    public double inputH(){return input.enthalpy();}public double outputH(){return output.enthalpy();}
    public double flow(){return flow;}public double loss(){return loss;}public double heatMW(){return heat;}
    public double totalLoss(){return totalLoss;}
    public double fillInput(double wanted,boolean simulate){return fillInput(wanted,Saturation.subcooledLiquidEnthalpyKJPerKg(design.tower?24:13),simulate);}
    public double fillInput(double wanted,double h,boolean simulate){return input.fill(wanted,h,simulate);}
    public double fillMakeup(double wanted,boolean simulate){return fillMakeup(wanted,Saturation.subcooledLiquidEnthalpyKJPerKg(13),simulate);}
    public double fillMakeup(double wanted,double h,boolean simulate){return output.fill(wanted,h,simulate);}
    public double drain(double wanted,boolean simulate){return output.drain(wanted,simulate);}
    public void clearReadouts(){flow=loss=heat=0;}
    public void tick(double seconds,double speed){
        clearReadouts();if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(speed))return;
        double retained=design.tower?1-LOSS_FRACTION:1;
        double moved=Math.min(input.mass(),Math.min(design.flow*seconds*Math.clamp(speed,0,1),output.space()/retained));
        if(moved<=0)return;
        double hotH=input.enthalpy(),outH=hotH;
        if(design.tower) {
            // Design cooling range is 11 C per pass, bounded by the ambient approach.
            outH=Saturation.subcooledLiquidEnthalpyKJPerKg(Math.max(13,input.temperatureC()-11));
            outH=Math.min(outH,hotH); // cold makeup is never heated by the tower.
            loss=moved*LOSS_FRACTION/seconds;totalLoss+=moved*LOSS_FRACTION;
            // Lost water carries its remaining sensible heat; this reports heat transferred to air.
            heat=moved*(hotH-outH)/seconds/1000;
        }
        input.drain(moved,false);output.fill(moved*retained,outH,false);flow=moved/seconds;
    }
    public void restore(double in,double out,double lost){restore(in,out,lost,Saturation.subcooledLiquidEnthalpyKJPerKg(design.tower?24:13),Saturation.subcooledLiquidEnthalpyKJPerKg(13));}
    public void restore(double in,double out,double lost,double inH,double outH){input.restore(in,inH);output.restore(out,outH);totalLoss=Double.isFinite(lost)?Math.max(0,lost):0;clearReadouts();}
}
