package dev.bwr.core.turbine;

/** Conservative game-scale expansion, not a manufacturer performance curve.
 * Every kg can traverse HP then LP once. Both stages discharge steam; only a condenser removes its residual heat.
 */
public final class PowerTurbine {
    public static final double GENERATOR_EFFICIENCY=.985;
    public static final double GENERATOR_RATING_W=1_500_000_000;
    public static final double CONDENSATE_ENTHALPY=167.2; // 40 C approximation
    public enum Stage { HP, LP }
    public record Expansion(double mass,double workKJ,double outletEnthalpy,double outletPressure,double rejectedHeatKJ) {}
    private PowerTurbine() {}
    public static double outletPressure(Stage stage,double inlet) { return stage==Stage.HP?Math.min(145,inlet*.25):1.07; }
    public static double specificWork(Stage stage,double h,double pressure) {
        return specificWork(stage,h,pressure,outletPressure(stage,pressure));
    }
    public static double specificWork(Stage stage,double h,double pressure,double outlet) {
        if(!Double.isFinite(h)||!Double.isFinite(pressure)||!Double.isFinite(outlet)||outlet<=0||pressure<=outlet)return 0;
        double nominal=stage==Stage.HP?280:570;
        double reference=stage==Stage.HP?1015.0/145:145.0/1.07;
        double drop=nominal*Math.max(0,Math.min(1,Math.log(pressure/outlet)/Math.log(reference)));
        return Math.max(0,Math.min(drop,h-CONDENSATE_ENTHALPY));
    }
    public static Expansion expand(Stage stage,SteamInventory inlet,SteamInventory exhaust,double wantedKg,double workBudgetKJ) {
        return expand(stage,inlet,exhaust,wantedKg,workBudgetKJ,outletPressure(stage,inlet.pressure()));
    }
    public static Expansion expand(Stage stage,SteamInventory inlet,SteamInventory exhaust,double wantedKg,double workBudgetKJ,double outletPsia) {
        double specific=specificWork(stage,inlet.enthalpy(),inlet.pressure(),outletPsia);
        if(specific<=0||!Double.isFinite(wantedKg)||!Double.isFinite(workBudgetKJ))return new Expansion(0,0,0,0,0);
        double room=exhaust==null?0:exhaust.space();
        double n=Math.max(0,Math.min(Math.min(wantedKg,room),Math.min(inlet.mass(),workBudgetKJ/specific)));
        var steam=inlet.take(n);
        double work=n*specific, h=steam.enthalpy()-specific, pressure=outletPsia;
        if(exhaust!=null)exhaust.offer(new SteamInventory.Packet(n,h,pressure));
        return new Expansion(n,work,h,pressure,0);
    }
}
