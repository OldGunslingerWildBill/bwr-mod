package dev.bwr.core.turbine;

/** Finite, mixed steam inventory. Enthalpy is kJ/kg; mass is kg. */
public final class SteamInventory {
    /** {@code moisture} is the liquid mass fraction carried in the packet (carryover); dry steam is 0. */
    public record Packet(double mass, double enthalpy, double pressurePsia, double moisture) {
        public Packet(double mass, double enthalpy, double pressurePsia) { this(mass,enthalpy,pressurePsia,0); }
        public double energyKJ() { return mass*enthalpy; }
    }
    private final double capacity;
    private double mass, energy, pressure, liquid;
    public SteamInventory(double capacity) {
        if(!Double.isFinite(capacity)||capacity<=0)throw new IllegalArgumentException("capacity");
        this.capacity=capacity;
    }
    public double mass() { return mass; }
    public double space() { return capacity-mass; }
    public double enthalpy() { return mass>0?energy/mass:0; }
    public double pressure() { return mass>0?pressure:0; }
    public double energyKJ() { return energy; }
    /** Liquid mass fraction of what is held: carryover the steam arrived with. */
    public double moisture() { return mass>0?liquid/mass:0; }
    public double offer(Packet p) {
        if(!Double.isFinite(p.mass)||!Double.isFinite(p.enthalpy)||!Double.isFinite(p.pressurePsia)
                ||p.mass<=0||p.enthalpy<=0||p.enthalpy>5000||p.pressurePsia<=0||p.pressurePsia>3208)return 0;
        double accepted=Math.min(space(),p.mass);
        if(accepted<=0)return 0;
        pressure=mass==0?p.pressurePsia:Math.min(pressure,p.pressurePsia);
        mass+=accepted;energy+=accepted*p.enthalpy;
        if(Double.isFinite(p.moisture)&&p.moisture>0)liquid+=accepted*Math.min(1,p.moisture);
        return accepted;
    }
    public Packet take(double wanted) {
        double n=Double.isFinite(wanted)?Math.max(0,Math.min(mass,wanted)):0;
        Packet p=new Packet(n,enthalpy(),pressure(),moisture());
        mass-=n;energy-=p.energyKJ();liquid=mass>0?liquid-n*p.moisture():0;
        if(mass<1e-10){mass=0;energy=0;pressure=0;liquid=0;}
        return p;
    }
    public void restore(double kg,double h,double psia) { restore(kg,h,psia,0); }
    public void restore(double kg,double h,double psia,double moisture) { mass=0;energy=0;pressure=0;liquid=0;offer(new Packet(kg,h,psia,moisture)); }
}
