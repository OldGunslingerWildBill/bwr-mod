package dev.bwr.core.thermal;

/** A well-mixed liquid segment. Stores mass and energy; pipes do not store either. */
public final class WaterInventory {
    private final double capacity,emptyC;
    private double mass,energy;
    public WaterInventory(double capacity,double emptyC){
        if(!Double.isFinite(capacity)||capacity<0||!Double.isFinite(emptyC)||emptyC<0||emptyC>500)throw new IllegalArgumentException("Invalid water inventory design");
        this.capacity=capacity;this.emptyC=emptyC;
    }
    public double mass(){return mass;}
    public double space(){return Math.max(0,capacity-mass);}
    public double energyKJ(){return energy;}
    public double enthalpy(){return mass>0?energy/mass:Saturation.subcooledLiquidEnthalpyKJPerKg(emptyC);}
    public double temperatureC(){return temperature(enthalpy());}
    public static double temperature(double h){
        double a=Saturation.SUBCOOLED_ENTHALPY_LINEAR_KJ_PER_KG_C,b=Saturation.SUBCOOLED_ENTHALPY_QUADRATIC_KJ_PER_KG_C2;
        return Double.isFinite(h)&&h>0?2*h/(a+Math.sqrt(a*a+4*b*h)):0;
    }
    public double fill(double kg,double h,boolean simulate){
        if(!Double.isFinite(kg)||kg<=0||!Double.isFinite(h)||h<0||h>5000)return 0;
        double n=Math.min(space(),kg);if(!simulate){mass+=n;energy+=n*h;}return n;
    }
    public double drain(double kg,boolean simulate){
        double n=Double.isFinite(kg)?Math.max(0,Math.min(mass,kg)):0;
        if(!simulate&&n>0){energy*=Math.max(0,(mass-n)/mass);mass-=n;if(mass==0)energy=0;}return n;
    }
    public void restore(double kg,double h){mass=energy=0;fill(kg,h,false);}
}
