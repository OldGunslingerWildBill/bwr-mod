package dev.bwr.core.turbine;

/** Conservative game-scale expansion, not a manufacturer performance curve.
 * Every kg can traverse HP then LP once. LP's deferred condenser rejects residual heat.
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
        if(!Double.isFinite(h)||!Double.isFinite(pressure)||pressure<=1.07)return 0;
        double outlet=outletPressure(stage,pressure);
        double nominal=stage==Stage.HP?280:570;
        double reference=stage==Stage.HP?1015.0/145:145.0/1.07;
        double drop=nominal*Math.max(0,Math.min(1,Math.log(pressure/outlet)/Math.log(reference)));
        return Math.max(0,Math.min(drop,h-CONDENSATE_ENTHALPY));
    }
    public static Expansion expand(Stage stage,SteamInventory inlet,SteamInventory exhaust,double wantedKg,double workBudgetKJ,double waterSpaceKg) {
        double specific=specificWork(stage,inlet.enthalpy(),inlet.pressure());
        if(specific<=0||!Double.isFinite(wantedKg)||!Double.isFinite(workBudgetKJ))return new Expansion(0,0,0,0,0);
        double room=stage==Stage.HP?exhaust.space():Math.max(0,waterSpaceKg);
        double n=Math.max(0,Math.min(Math.min(wantedKg,room),Math.min(inlet.mass(),workBudgetKJ/specific)));
        var steam=inlet.take(n);
        double work=n*specific, h=steam.enthalpy()-specific, pressure=outletPressure(stage,steam.pressurePsia());
        if(stage==Stage.HP)exhaust.offer(new SteamInventory.Packet(n,h,pressure));
        double rejected=stage==Stage.LP?n*Math.max(0,h-CONDENSATE_ENTHALPY):0;
        return new Expansion(n,work,stage==Stage.LP?CONDENSATE_ENTHALPY:h,pressure,rejected);
    }
}
