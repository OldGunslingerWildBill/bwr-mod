package dev.bwr.core.eccs;

/** Finite, well-mixed SLC inventory. Kilograms are conserved across filling and withdrawal. */
public final class BoronSolution {
    public static final double CAPACITY_KG=15_000, CHARGE_KG=25, MAX_BORATE_FRACTION=.13;
    /** Sodium pentaborate equivalent; a game calibration, not a chemistry simulation. */
    public static final double BORON_FRACTION_OF_BORATE=.183;
    private double mass, borate, heat;
    public double massKg(){return mass;}
    public double borateKg(){return borate;}
    public double temperatureC(){return mass>0?heat/mass:20;}
    public double boronFraction(){return mass>0?borate*BORON_FRACTION_OF_BORATE/mass:0;}
    public double addWater(double kg,double c,boolean execute){
        if(!Double.isFinite(kg)||!Double.isFinite(c)||kg<=0)return 0;
        double accepted=Math.min(kg,CAPACITY_KG-mass);
        if(execute){mass+=accepted;heat+=accepted*Math.clamp(c,0,350);}
        return accepted;
    }
    public boolean charge(boolean execute){
        if(mass+CHARGE_KG>CAPACITY_KG || (borate+CHARGE_KG)/(mass+CHARGE_KG)>MAX_BORATE_FRACTION)return false;
        if(execute){double c=temperatureC();mass+=CHARGE_KG;borate+=CHARGE_KG;heat+=CHARGE_KG*c;}
        return true;
    }
    public record Batch(double massKg,double boronKg,double temperatureC){}
    public Batch remove(double kg,boolean execute){
        double amount=Double.isFinite(kg)?Math.clamp(kg,0,mass):0;
        var batch=new Batch(amount,amount*boronFraction(),temperatureC());
        if(execute && amount>0){double remaining=1-amount/mass;mass-=amount;borate*=remaining;heat*=remaining;}
        return batch;
    }
    public void restore(double kg,double salt,double c){
        mass=Double.isFinite(kg)?Math.clamp(kg,0,CAPACITY_KG):0;
        borate=Double.isFinite(salt)?Math.clamp(salt,0,mass*MAX_BORATE_FRACTION):0;
        heat=mass*(Double.isFinite(c)?Math.clamp(c,0,350):20);
    }
}
