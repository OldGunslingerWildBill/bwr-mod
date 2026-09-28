package dev.bwr.core.thermal;

/** Saturated liquid/steam equilibrium in a fixed-volume vessel during a rupture.
 * Solves mass and internal energy after inlet/outlet enthalpy transport. This
 * remains a lumped gameplay model: no jet-pump seal, air ingress or dry-gas EOS.
 */
public final class VesselBlowdown {
    private static final double PSI_TO_KPA = 6.894757;
    public record State(double pressure, double liquid, double steam, double energy) {}
    public static State at(double pressure, double mass, double volume) {
        double p = Saturation.psiaFromPsig(pressure);
        double rf = Saturation.liquidDensityKgPerM3(p), rg = Saturation.vapourDensityKgPerM3(p);
        double liquid = Math.max(0, Math.min(mass, (mass - volume * rg) / (1 - rg / rf)));
        double steam = mass - liquid;
        double energy = liquid * (Saturation.liquidEnthalpyKJPerKg(p) - p * PSI_TO_KPA / rf)
                + steam * (Saturation.vapourEnthalpyKJPerKg(p) - p * PSI_TO_KPA / rg);
        return new State(pressure, liquid, steam, energy);
    }
    public static State solve(double mass, double energy, double volume) {
        double lo = PressureVessel.MINIMUM_PRESSURE_PSIG, hi = PressureVessel.MAXIMUM_PRESSURE_PSIG;
        // At the dry boundary there is no liquid left to flash. Limit the saturated
        // solve to the pressure that the remaining vapour can actually support.
        if (volume * Saturation.vapourDensityKgPerM3(Saturation.psiaFromPsig(hi)) > mass) {
            double a = lo, b = hi;
            for (int i = 0; i < 40; i++) {
                double mid = (a + b) * .5;
                if (volume * Saturation.vapourDensityKgPerM3(Saturation.psiaFromPsig(mid)) > mass) b = mid;
                else a = mid;
            }
            hi = Math.max(lo, a);
        }
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) * .5;
            if (at(mid, mass, volume).energy() > energy) hi = mid;
            else lo = mid;
        }
        return at((lo + hi) * .5, mass, volume);
    }
    private VesselBlowdown() {}
}
